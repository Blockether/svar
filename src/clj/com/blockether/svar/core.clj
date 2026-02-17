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
     `make-config` so users can require only this namespace.
   
   Configuration:
   Config MUST be passed explicitly to all LLM functions via the :config parameter.
   No global state. No dependency injection.
   
    Example:
    (def config (make-config {:api-key \"sk-...\" :base-url \"https://api.openai.com/v1\"}))
     (ask! {:config config
            :spec my-spec
            :messages [(system \"Help the user.\")
                       (user \"What is 2+2?\")]
            :model \"gpt-4o\"})
   
   References:
   - Chain of Density: https://arxiv.org/abs/2309.04269
   - LLM Self-Evaluation: https://learnprompting.org/docs/reliability/lm_self_eval
   - DuTy: https://learnprompting.org/docs/advanced/decomposition/duty-distinct-chain-of-thought
   - CoVe: https://learnprompting.org/docs/advanced/self_criticism/chain_of_verification"
  (:require
   [com.blockether.svar.internal.config :as config]
   [com.blockether.svar.internal.guard :as guard]
   [com.blockether.svar.internal.humanize :as humanize]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.rlm :as rlm]
   [com.blockether.svar.internal.rlm.internal.pageindex.core :as pageindex]
   [com.blockether.svar.internal.spec :as spec]))

;; =============================================================================
;; Re-export config functions
;; =============================================================================

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def make-config
  "Creates an LLM configuration map. See internal.config for details."
  config/make-config)

;; =============================================================================
;; Re-export spec DSL
;; =============================================================================

(def field
  "Creates a field definition for a spec. See spec namespace for details."
  spec/field)

(def spec
  "Creates a spec definition from field definitions. See spec namespace for details."
  spec/spec)

(def build-ref-registry
  "Builds a registry of referenced specs. See spec namespace for details."
  spec/build-ref-registry)

(def str->data
  "Parses LLM response string to Clojure data. See spec namespace for details."
  spec/str->data)

(def str->data-with-spec
  "Parses LLM response with spec validation. See spec namespace for details."
  spec/str->data-with-spec)

(def data->str
  "Serializes Clojure data to LLM-compatible string. See spec namespace for details."
  spec/data->str)

(def validate-data
  "Validates parsed data against a spec. See spec namespace for details."
  spec/validate-data)

(def spec->prompt
  "Generates LLM prompt from a spec. See spec namespace for details."
  spec/spec->prompt)

;; =============================================================================
;; Spec Field Option Keywords
;; =============================================================================

;; These are the namespaced keywords used in field definitions.
;; Re-exported here for convenience so users can require only svar.core.

(def NAME
  "Field option: Field name as Datomic-style keyword (e.g., :user/name)."
  ::spec/name)

(def TYPE
  "Field option: Field type (e.g., :spec.type/string, :spec.type/int)."
  ::spec/type)

(def CARDINALITY
  "Field option: Field cardinality (:spec.cardinality/one or :spec.cardinality/many)."
  ::spec/cardinality)

(def DESCRIPTION
  "Field option: Human-readable field description."
  ::spec/description)

(def REQUIRED
  "Field option: Whether field is required (default: true). Set to false for optional."
  ::spec/required)

(def VALUES
  "Field option: Enum values as map {value description}."
  ::spec/values)

(def TARGET
  "Field option: Reference target for :spec.type/ref fields."
  ::spec/target)

(def UNION
  "Field option: Set of allowed nil types (used internally for optional fields)."
  ::spec/union)

(def KEY-NS
  "Spec option: Namespace prefix to add to keys during parsing."
  ::spec/key-ns)

(def HUMANIZE
  "Field option: When true, marks field for humanization via :humanizer in ask!."
  ::spec/humanize?)

;; =============================================================================
;; Type Keywords
;; =============================================================================

;; Base types
(def TYPE_STRING
  "Type: String value."
  :spec.type/string)

(def TYPE_INT
  "Type: Integer value."
  :spec.type/int)

(def TYPE_FLOAT
  "Type: Floating point value."
  :spec.type/float)

(def TYPE_BOOL
  "Type: Boolean value."
  :spec.type/bool)

(def TYPE_DATE
  "Type: ISO date (YYYY-MM-DD)."
  :spec.type/date)

(def TYPE_DATETIME
  "Type: ISO datetime."
  :spec.type/datetime)

(def TYPE_REF
  "Type: Reference to another spec."
  :spec.type/ref)

(def TYPE_KEYWORD
  "Type: Clojure keyword (rendered as string, keywordized on parse)."
  :spec.type/keyword)

;; Fixed-size integer vector types (1-12 elements)
(def TYPE_INT_V_1 "Type: Fixed-size integer vector (1 element)." :spec.type/int-v-1)
(def TYPE_INT_V_2 "Type: Fixed-size integer vector (2 elements)." :spec.type/int-v-2)
(def TYPE_INT_V_3 "Type: Fixed-size integer vector (3 elements)." :spec.type/int-v-3)
(def TYPE_INT_V_4 "Type: Fixed-size integer vector (4 elements)." :spec.type/int-v-4)
(def TYPE_INT_V_5 "Type: Fixed-size integer vector (5 elements)." :spec.type/int-v-5)
(def TYPE_INT_V_6 "Type: Fixed-size integer vector (6 elements)." :spec.type/int-v-6)
(def TYPE_INT_V_7 "Type: Fixed-size integer vector (7 elements)." :spec.type/int-v-7)
(def TYPE_INT_V_8 "Type: Fixed-size integer vector (8 elements)." :spec.type/int-v-8)
(def TYPE_INT_V_9 "Type: Fixed-size integer vector (9 elements)." :spec.type/int-v-9)
(def TYPE_INT_V_10 "Type: Fixed-size integer vector (10 elements)." :spec.type/int-v-10)
(def TYPE_INT_V_11 "Type: Fixed-size integer vector (11 elements)." :spec.type/int-v-11)
(def TYPE_INT_V_12 "Type: Fixed-size integer vector (12 elements)." :spec.type/int-v-12)

;; Fixed-size string vector types (1-12 elements)
(def TYPE_STRING_V_1 "Type: Fixed-size string vector (1 element)." :spec.type/string-v-1)
(def TYPE_STRING_V_2 "Type: Fixed-size string vector (2 elements)." :spec.type/string-v-2)
(def TYPE_STRING_V_3 "Type: Fixed-size string vector (3 elements)." :spec.type/string-v-3)
(def TYPE_STRING_V_4 "Type: Fixed-size string vector (4 elements)." :spec.type/string-v-4)
(def TYPE_STRING_V_5 "Type: Fixed-size string vector (5 elements)." :spec.type/string-v-5)
(def TYPE_STRING_V_6 "Type: Fixed-size string vector (6 elements)." :spec.type/string-v-6)
(def TYPE_STRING_V_7 "Type: Fixed-size string vector (7 elements)." :spec.type/string-v-7)
(def TYPE_STRING_V_8 "Type: Fixed-size string vector (8 elements)." :spec.type/string-v-8)
(def TYPE_STRING_V_9 "Type: Fixed-size string vector (9 elements)." :spec.type/string-v-9)
(def TYPE_STRING_V_10 "Type: Fixed-size string vector (10 elements)." :spec.type/string-v-10)
(def TYPE_STRING_V_11 "Type: Fixed-size string vector (11 elements)." :spec.type/string-v-11)
(def TYPE_STRING_V_12 "Type: Fixed-size string vector (12 elements)." :spec.type/string-v-12)

;; Fixed-size double vector types (1-12 elements)
(def TYPE_DOUBLE_V_1 "Type: Fixed-size double vector (1 element)." :spec.type/double-v-1)
(def TYPE_DOUBLE_V_2 "Type: Fixed-size double vector (2 elements)." :spec.type/double-v-2)
(def TYPE_DOUBLE_V_3 "Type: Fixed-size double vector (3 elements)." :spec.type/double-v-3)
(def TYPE_DOUBLE_V_4 "Type: Fixed-size double vector (4 elements)." :spec.type/double-v-4)
(def TYPE_DOUBLE_V_5 "Type: Fixed-size double vector (5 elements)." :spec.type/double-v-5)
(def TYPE_DOUBLE_V_6 "Type: Fixed-size double vector (6 elements)." :spec.type/double-v-6)
(def TYPE_DOUBLE_V_7 "Type: Fixed-size double vector (7 elements)." :spec.type/double-v-7)
(def TYPE_DOUBLE_V_8 "Type: Fixed-size double vector (8 elements)." :spec.type/double-v-8)
(def TYPE_DOUBLE_V_9 "Type: Fixed-size double vector (9 elements)." :spec.type/double-v-9)
(def TYPE_DOUBLE_V_10 "Type: Fixed-size double vector (10 elements)." :spec.type/double-v-10)
(def TYPE_DOUBLE_V_11 "Type: Fixed-size double vector (11 elements)." :spec.type/double-v-11)
(def TYPE_DOUBLE_V_12 "Type: Fixed-size double vector (12 elements)." :spec.type/double-v-12)

;; =============================================================================
;; Cardinality Keywords
;; =============================================================================

(def CARDINALITY_ONE
  "Cardinality: Single value."
  :spec.cardinality/one)

(def CARDINALITY_MANY
  "Cardinality: Vector of values."
  :spec.cardinality/many)

;; =============================================================================
;; Re-export humanize functions
;; =============================================================================

(def humanize-string
  "Removes AI-style phrases from text to make it sound more natural.
   See internal.humanize for details."
  humanize/humanize-string)

(def humanize-data
  "Recursively humanizes all strings in a data structure.
   See internal.humanize for details."
  humanize/humanize-data)

(def humanizer
  "Creates a humanization function with optional custom patterns.
   See internal.humanize for details."
  humanize/humanizer)

(def HUMANIZE_DEFAULT_PATTERNS
  "Default patterns for AI phrase humanization (safe + aggressive combined).
   Preserved for backward compatibility."
  humanize/DEFAULT_PATTERNS)

(def HUMANIZE_SAFE_PATTERNS
  "Safe humanization patterns: AI identity, refusal, knowledge, punctuation.
   These are unambiguously AI artifacts, safe for arbitrary text."
  humanize/SAFE_PATTERNS)

(def HUMANIZE_AGGRESSIVE_PATTERNS
  "Aggressive humanization patterns: hedging, overused verbs/adjectives/nouns, cliches.
   May match valid English -- opt-in only via {:aggressive? true}."
  humanize/AGGRESSIVE_PATTERNS)

;; =============================================================================
;; Re-export guard functions
;; =============================================================================

(def static-guard
  "Creates a guard function that checks for prompt injection patterns.
   See internal.guard for details."
  guard/static)

(def moderation-guard
  "Creates a guard function that uses LLM to check content against policies.
   See internal.guard for details."
  guard/moderation)

(def guard
  "Runs guard(s) on input. See internal.guard for details."
  guard/guard)

(def GUARD_DEFAULT_INJECTION_PATTERNS
  "Default patterns for prompt injection detection."
  guard/DEFAULT_INJECTION_PATTERNS)

(def GUARD_DEFAULT_MODERATION_POLICIES
  "Default OpenAI moderation policies to check."
  guard/DEFAULT_MODERATION_POLICIES)

;; =============================================================================
;; Re-export LLM client functions
;; =============================================================================

(def image
  "Creates an image attachment for use with `user` messages.
   See internal.llm for details."
  llm/image)

(def system
  "Creates a system message. See internal.llm for details."
  llm/system)

(def user
  "Creates a user message, optionally with images. See internal.llm for details."
  llm/user)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def assistant
  "Creates an assistant message. See internal.llm for details."
  llm/assistant)

(def ask!
  "Asks the LLM and returns structured Clojure data with token usage and cost.
   See internal.llm for details."
  llm/ask!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def abstract!
  "Creates a dense, entity-rich summary using Chain of Density prompting.
   See internal.llm for details."
  llm/abstract!)

(def eval!
  "Evaluates an LLM output using LLM self-evaluation.
   See internal.llm for details."
  llm/eval!)

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

(def create-env
  "Creates an RLM environment for processing large contexts via iterative code execution.
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

(def ingest-to-env!
  "Ingests documents into an RLM environment for querying.
   See internal.rlm for details."
  rlm/ingest-to-env!)

(def dispose-env!
  "Disposes an RLM environment and cleans up resources.
   See internal.rlm for details."
  rlm/dispose-env!)

(def query-env!
  "Runs a query against an RLM environment using iterative code execution.
   See internal.rlm for details."
  rlm/query-env!)

(def pprint-trace
  "Pretty-prints an RLM trace to a string.
   See internal.rlm for details."
  rlm/pprint-trace)

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
     :config          - LLM config override
     :pages           - Page selector (1-indexed). Limits which pages are indexed.
                        Supports: integer, [from to] range, or [[1 3] 5 [7 10]] mixed.
                        nil = all pages (default).
     
     Vision extraction:
      :vision-model    - Model for vision page extraction
     :parallel        - Max concurrent vision page extractions (default: 3)
     
     Quality refinement:
     :refine?         - Enable post-extraction quality refinement (default: false)
     :refine-model    - Model for eval/refine steps (default: \"gpt-4o\")
     :parallel-refine - Max concurrent eval/refine operations (default: 2)
   
   Example:
     (svar/index! \"docs/manual.pdf\")
     (svar/index! \"docs/manual.pdf\" {:pages [1 5]})
     (svar/index! \"docs/manual.pdf\" {:pages [[1 3] 5 [7 10]]})
     (svar/index! \"docs/manual.pdf\" {:vision-model \"gpt-4o\"
                                       :parallel 5
                                       :refine? true
                                       :refine-model \"gpt-4o-mini\"
                                       :parallel-refine 3})
   
   See internal.rlm.internal.pageindex.core for full options."
  pageindex/index!)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def load-index
  "Load an indexed document from a .pageindex directory.
   
   Reads the EDN + PNG files produced by `index!` and returns the document map.
   Also supports loading legacy formats for backward compatibility.
   
   Example:
     (svar/load-index \"docs/manual.pageindex\")
   
   See internal.rlm.internal.pageindex.core for details."
  pageindex/load-index)
