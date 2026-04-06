(ns com.blockether.svar.core
  "LLM interaction utilities for structured and unstructured outputs.
   
   SVAR = Structured Validated Automated Reasoning
   
    Provides main functions:
    - `ask!` - Structured output using the spec DSL
    - `abstract!` - Text summarization using Chain of Density prompting
    - `eval!` - LLM self-evaluation for reliability and accuracy assessment
    - `refine!` - Iterative refinement using decomposition and verification
    - `models!` - Fetch available models from the LLM API
    - `sample!` - Generate test data samples matching a spec
    
    Guardrails:
    - `static-guard` - Pattern-based prompt injection detection
    - `moderation-guard` - LLM-based content moderation
    - `guard` - Run one or more guards on input
    
    Humanization:
    - `humanize-string` - Strip AI-style phrases from text
    - `humanize-data` - Humanize string values in data structures
    - `humanizer` - Create a reusable humanizer function
    
     PageIndex:
     - `index!` - Index a document file (PDF, MD, TXT) and save structured data
     - `load-index` - Load an indexed document from a pageindex directory
    
     Re-exports spec DSL (`field`, `spec`, `str->data`, `str->data-with-spec`,
     `data->str`, `validate-data`, `spec->prompt`, `build-ref-registry`),
     RLM (`create-env`, `register-env-fn!`, `register-env-def!`, `ingest-to-env!`, `dispose-env!`,
     `query-env!`, `pprint-trace`, `print-trace`, `generate-qa-env!`),
     PageIndex (`index!`, `load-index`), and
     `make-router` so users can require only this namespace.
   
   Configuration:
   LLM calls route automatically via the default router.

    Example:
     (ask! {:spec my-spec
            :messages [(system \"Help the user.\")
                       (user \"What is 2+2?\")]
            :model \"gpt-4o\"})
   
   References:
   - Chain of Density: https://arxiv.org/abs/2309.04269
   - LLM Self-Evaluation: https://learnprompting.org/docs/reliability/lm_self_eval
   - DuTy: https://learnprompting.org/docs/advanced/decomposition/duty-distinct-chain-of-thought
   - CoVe: https://learnprompting.org/docs/advanced/self_criticism/chain_of_verification"
  (:require
   [com.blockether.svar.internal.guard :as guard]
   [com.blockether.svar.internal.humanize :as humanize]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.rlm :as rlm]
   [com.blockether.svar.internal.spec :as spec]))

;; =============================================================================
;; Re-export router functions
;; =============================================================================

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def make-router
  "Creates a router from a vector of provider maps. See internal.llm for details."
  llm/make-router)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def router-stats
  "Returns cumulative + windowed stats for the router."
  llm/router-stats)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def reset-budget!
  "Resets the router's token/cost budget counters to zero."
  llm/reset-budget!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def reset-provider!
  "Manually resets a provider's circuit breaker to :closed."
  llm/reset-provider!)

;; =============================================================================
;; Re-export spec DSL
;; =============================================================================

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def field
  "Creates a field definition for a spec. See spec namespace for details."
  spec/field)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def spec
  "Creates a spec definition from field definitions. See spec namespace for details."
  spec/spec)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def build-ref-registry
  "Builds a registry of referenced specs. See spec namespace for details."
  spec/build-ref-registry)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def str->data
  "Parses LLM response string to Clojure data. See spec namespace for details."
  spec/str->data)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def str->data-with-spec
  "Parses LLM response with spec validation. See spec namespace for details."
  spec/str->data-with-spec)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def data->str
  "Serializes Clojure data to LLM-compatible string. See spec namespace for details."
  spec/data->str)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def validate-data
  "Validates parsed data against a spec. See spec namespace for details."
  spec/validate-data)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def spec->prompt
  "Generates LLM prompt from a spec. See spec namespace for details."
  spec/spec->prompt)

;; =============================================================================
;; Spec Field Option Keywords
;; =============================================================================

;; These are the namespaced keywords used in field definitions.
;; Re-exported here for convenience so users can require only svar.core.

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def NAME
  "Field option: Field name as Datomic-style keyword (e.g., :user/name)."
  ::spec/name)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE
  "Field option: Field type (e.g., :spec.type/string, :spec.type/int)."
  ::spec/type)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def CARDINALITY
  "Field option: Field cardinality (:spec.cardinality/one or :spec.cardinality/many)."
  ::spec/cardinality)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def DESCRIPTION
  "Field option: Human-readable field description."
  ::spec/description)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def REQUIRED
  "Field option: Whether field is required (default: true). Set to false for optional."
  ::spec/required)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def VALUES
  "Field option: Enum values as map {value description}."
  ::spec/values)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TARGET
  "Field option: Reference target for :spec.type/ref fields."
  ::spec/target)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def UNION
  "Field option: Set of allowed nil types (used internally for optional fields)."
  ::spec/union)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def KEY-NS
  "Spec option: Namespace prefix to add to keys during parsing."
  ::spec/key-ns)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def HUMANIZE
  "Field option: When true, marks field for humanization via :humanizer in ask!."
  ::spec/humanize?)

;; =============================================================================
;; Type Keywords
;; =============================================================================

;; Base types
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_STRING
  "Type: String value."
  :spec.type/string)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_INT
  "Type: Integer value."
  :spec.type/int)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_FLOAT
  "Type: Floating point value."
  :spec.type/float)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_BOOL
  "Type: Boolean value."
  :spec.type/bool)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DATE
  "Type: ISO date (YYYY-MM-DD)."
  :spec.type/date)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DATETIME
  "Type: ISO datetime."
  :spec.type/datetime)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_REF
  "Type: Reference to another spec."
  :spec.type/ref)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_KEYWORD
  "Type: Clojure keyword (rendered as string, keywordized on parse)."
  :spec.type/keyword)

;; Fixed-size integer vector types (1-12 elements)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_INT_V_1 "Type: Fixed-size integer vector (1 element)." :spec.type/int-v-1)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_INT_V_2 "Type: Fixed-size integer vector (2 elements)." :spec.type/int-v-2)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_INT_V_3 "Type: Fixed-size integer vector (3 elements)." :spec.type/int-v-3)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_INT_V_4 "Type: Fixed-size integer vector (4 elements)." :spec.type/int-v-4)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_INT_V_5 "Type: Fixed-size integer vector (5 elements)." :spec.type/int-v-5)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_INT_V_6 "Type: Fixed-size integer vector (6 elements)." :spec.type/int-v-6)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_INT_V_7 "Type: Fixed-size integer vector (7 elements)." :spec.type/int-v-7)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_INT_V_8 "Type: Fixed-size integer vector (8 elements)." :spec.type/int-v-8)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_INT_V_9 "Type: Fixed-size integer vector (9 elements)." :spec.type/int-v-9)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_INT_V_10 "Type: Fixed-size integer vector (10 elements)." :spec.type/int-v-10)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_INT_V_11 "Type: Fixed-size integer vector (11 elements)." :spec.type/int-v-11)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_INT_V_12 "Type: Fixed-size integer vector (12 elements)." :spec.type/int-v-12)

;; Fixed-size string vector types (1-12 elements)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_STRING_V_1 "Type: Fixed-size string vector (1 element)." :spec.type/string-v-1)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_STRING_V_2 "Type: Fixed-size string vector (2 elements)." :spec.type/string-v-2)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_STRING_V_3 "Type: Fixed-size string vector (3 elements)." :spec.type/string-v-3)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_STRING_V_4 "Type: Fixed-size string vector (4 elements)." :spec.type/string-v-4)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_STRING_V_5 "Type: Fixed-size string vector (5 elements)." :spec.type/string-v-5)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_STRING_V_6 "Type: Fixed-size string vector (6 elements)." :spec.type/string-v-6)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_STRING_V_7 "Type: Fixed-size string vector (7 elements)." :spec.type/string-v-7)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_STRING_V_8 "Type: Fixed-size string vector (8 elements)." :spec.type/string-v-8)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_STRING_V_9 "Type: Fixed-size string vector (9 elements)." :spec.type/string-v-9)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_STRING_V_10 "Type: Fixed-size string vector (10 elements)." :spec.type/string-v-10)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_STRING_V_11 "Type: Fixed-size string vector (11 elements)." :spec.type/string-v-11)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_STRING_V_12 "Type: Fixed-size string vector (12 elements)." :spec.type/string-v-12)

;; Fixed-size double vector types (1-12 elements)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DOUBLE_V_1 "Type: Fixed-size double vector (1 element)." :spec.type/double-v-1)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DOUBLE_V_2 "Type: Fixed-size double vector (2 elements)." :spec.type/double-v-2)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DOUBLE_V_3 "Type: Fixed-size double vector (3 elements)." :spec.type/double-v-3)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DOUBLE_V_4 "Type: Fixed-size double vector (4 elements)." :spec.type/double-v-4)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DOUBLE_V_5 "Type: Fixed-size double vector (5 elements)." :spec.type/double-v-5)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DOUBLE_V_6 "Type: Fixed-size double vector (6 elements)." :spec.type/double-v-6)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DOUBLE_V_7 "Type: Fixed-size double vector (7 elements)." :spec.type/double-v-7)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DOUBLE_V_8 "Type: Fixed-size double vector (8 elements)." :spec.type/double-v-8)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DOUBLE_V_9 "Type: Fixed-size double vector (9 elements)." :spec.type/double-v-9)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DOUBLE_V_10 "Type: Fixed-size double vector (10 elements)." :spec.type/double-v-10)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DOUBLE_V_11 "Type: Fixed-size double vector (11 elements)." :spec.type/double-v-11)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DOUBLE_V_12 "Type: Fixed-size double vector (12 elements)." :spec.type/double-v-12)

;; =============================================================================
;; Cardinality Keywords
;; =============================================================================

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def CARDINALITY_ONE
  "Cardinality: Single value."
  :spec.cardinality/one)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def CARDINALITY_MANY
  "Cardinality: Vector of values."
  :spec.cardinality/many)

;; =============================================================================
;; Re-export humanize functions
;; =============================================================================

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def humanize-string
  "Removes AI-style phrases from text to make it sound more natural.
   See internal.humanize for details."
  humanize/humanize-string)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def humanize-data
  "Recursively humanizes all strings in a data structure.
   See internal.humanize for details."
  humanize/humanize-data)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def humanizer
  "Creates a humanization function with optional custom patterns.
   See internal.humanize for details."
  humanize/humanizer)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def HUMANIZE_SAFE_PATTERNS
  "Safe humanization patterns: AI identity, refusal, knowledge, punctuation.
   These are unambiguously AI artifacts, safe for arbitrary text."
  humanize/SAFE_PATTERNS)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def HUMANIZE_AGGRESSIVE_PATTERNS
  "Aggressive humanization patterns: hedging, overused verbs/adjectives/nouns, cliches.
   May match valid English -- opt-in only via {:aggressive? true}."
  humanize/AGGRESSIVE_PATTERNS)

;; =============================================================================
;; Re-export guard functions
;; =============================================================================

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def static-guard
  "Creates a guard function that checks for prompt injection patterns.
   See internal.guard for details."
  guard/static)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def moderation-guard
  "Creates a guard function that uses LLM to check content against policies.
   See internal.guard for details."
  guard/moderation)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def guard
  "Runs guard(s) on input. See internal.guard for details."
  guard/guard)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def GUARD_DEFAULT_INJECTION_PATTERNS
  "Default patterns for prompt injection detection."
  guard/DEFAULT_INJECTION_PATTERNS)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def GUARD_DEFAULT_MODERATION_POLICIES
  "Default OpenAI moderation policies to check."
  guard/DEFAULT_MODERATION_POLICIES)

;; =============================================================================
;; Re-export LLM client functions
;; =============================================================================

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def image
  "Creates an image attachment for use with `user` messages.
   See internal.llm for details."
  llm/image)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def system
  "Creates a system message. See internal.llm for details."
  llm/system)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def user
  "Creates a user message, optionally with images. See internal.llm for details."
  llm/user)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def assistant
  "Creates an assistant message. See internal.llm for details."
  llm/assistant)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def ask!
  "Asks the LLM and returns structured Clojure data with token usage and cost.
   See internal.llm for details."
  llm/ask!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def abstract!
  "Creates a dense, entity-rich summary using Chain of Density prompting.
   See internal.llm for details."
  llm/abstract!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def eval!
  "Evaluates an LLM output using LLM self-evaluation.
   See internal.llm for details."
  llm/eval!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def refine!
  "Iteratively refines LLM output using decomposition and verification.
   See internal.llm for details."
  llm/refine!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def models!
  "Fetches available models from the LLM API.
   See internal.llm for details."
  llm/models!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def sample!
  "Generates test data samples matching a spec with self-correction.
   See internal.llm for details."
  llm/sample!)

;; =============================================================================
;; Re-export RLM (Recursive Language Model) — direct requires, no requiring-resolve
;; =============================================================================

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def RLM_SCHEMA
  "Datalevin schema for RLM data. Merge into your app's schema for unified DB."
  rlm/RLM_SCHEMA)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def create-env
  "Creates an RLM environment for processing large contexts via iterative code execution.
   Accepts :conn for unified DB, :path for standalone, or neither for temp DB.
   See internal.rlm for details."
  rlm/create-env)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def register-env-fn!
  "Registers a custom function in the RLM's SCI sandbox.
   See internal.rlm for details."
  rlm/register-env-fn!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def register-env-def!
  "Registers a constant in the RLM's SCI sandbox.
   See internal.rlm for details."
  rlm/register-env-def!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def ingest-to-env!
  "Ingests documents into an RLM environment for querying.
   See internal.rlm for details."
  rlm/ingest-to-env!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def dispose-env!
  "Disposes an RLM environment and cleans up resources.
   See internal.rlm for details."
  rlm/dispose-env!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def query-env!
  "Runs a query against an RLM environment using iterative code execution.
   See internal.rlm for details."
  rlm/query-env!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def list-queries
  "Lists query records from an RLM environment.
   See internal.rlm for details."
  rlm/list-queries)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def export-trajectories!
  "Exports filtered trajectories as JSONL for fine-tuning.
   See internal.rlm for details."
  rlm/export-trajectories!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def pprint-trace
  "Pretty-prints an RLM trace to a string.
   See internal.rlm for details."
  rlm/pprint-trace)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def print-trace
  "Pretty-prints an RLM trace to stdout.
   See internal.rlm for details."
  rlm/print-trace)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def generate-qa-env!
  "Generates question-answer pairs from ingested documents using a multi-stage pipeline.
   See internal.rlm for details."
  rlm/generate-qa-env!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def save-qa!
  "Saves generate-qa-env! results to EDN and/or Markdown files.
   See internal.rlm for details."
  rlm/save-qa!)

;; =============================================================================
;; Re-export PageIndex functions
;; =============================================================================

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def index!
  "Index a document file (PDF, MD, TXT) and save structured data as EDN + PNG files.

   Creates a .pageindex directory alongside the original file containing:
     document.edn — structured document data
     images/      — extracted images as PNG files

   Opts:
     :pages           - Page selector (1-indexed). Limits which pages are indexed.
                        Supports: integer, [from to] range, or [[1 3] 5 [7 10]] mixed.
                        nil = all pages (default).
     :parallel        - Max concurrent page extractions (default: 3).

     Extraction strategy (controls how page content is extracted):
     :extraction-strategy - :vision (default) or :ocr.
       :vision — Single-pass: sends page image to a vision LLM with structured output spec.
                 Slower but handles images/tables/diagrams directly from the visual.
       :ocr    — Two-pass: (1) sends page image to a local OCR model for raw text extraction,
                 then (2) sends that text to a text LLM with structured output spec.
                 Dramatically faster when using a local OCR model (e.g. glm-ocr via LM Studio).

     Vision strategy options:
     :vision-model    - Model for vision page extraction (e.g. \"glm-4.6v\").

     OCR strategy options:
     :ocr-model       - OCR model name (e.g. \"glm-ocr\"). Required when :extraction-strategy is :ocr.
                        Must be available in the router as a provider model.
     :text-model      - Text LLM for structuring OCR output and abstract/title inference.
                        Also used in :vision strategy for abstract/title. (e.g. \"gpt-5-mini\")

     Quality refinement:
     :refine?         - Enable post-extraction quality refinement (default: false)
     :refine-model    - Model for eval/refine steps (default: \"gpt-4o\")
     :parallel-refine - Max concurrent eval/refine operations (default: 2)

   Example:
     ;; Vision strategy (default):
     (svar/index! \"docs/manual.pdf\")
     (svar/index! \"docs/manual.pdf\" {:vision-model \"gpt-4o\" :parallel 5})

     ;; OCR strategy (fast, local OCR + remote text LLM):
     (svar/index! \"docs/manual.pdf\" {:extraction-strategy :ocr
                                       :ocr-model \"glm-ocr\"
                                       :text-model \"gpt-5-mini\"
                                       :parallel 3})

   See internal.rlm for full options."
  rlm/index!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def load-index
  "Load an indexed document from a .pageindex directory.

   Reads the EDN + PNG files produced by `index!` and returns the document map.

   Example:
     (svar/load-index \"docs/manual.pageindex\")

   See internal.rlm for details."
  rlm/load-index)
