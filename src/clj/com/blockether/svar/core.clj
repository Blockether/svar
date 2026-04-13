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
     `query-env!`, `pprint-trace`, `print-trace`, `query-env-qa!`),
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
;; Router
;; =============================================================================

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def make-router "Creates a router from a vector of provider maps." llm/make-router)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def router-stats "Returns cumulative + windowed stats for the router." llm/router-stats)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def reset-budget! "Resets the router's token/cost budget counters to zero." llm/reset-budget!)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def reset-provider! "Manually resets a provider's circuit breaker to :closed." llm/reset-provider!)

;; =============================================================================
;; Spec DSL
;; =============================================================================

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def field "Creates a field definition for a spec." spec/field)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def spec "Creates a spec definition from field definitions." spec/spec)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def build-ref-registry "Builds a registry of referenced specs." spec/build-ref-registry)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def str->data "Parses LLM response string to Clojure data." spec/str->data)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def str->data-with-spec "Parses LLM response with spec validation." spec/str->data-with-spec)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def data->str "Serializes Clojure data to LLM-compatible string." spec/data->str)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def validate-data "Validates parsed data against a spec." spec/validate-data)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def spec->prompt "Generates LLM prompt from a spec." spec/spec->prompt)

;; =============================================================================
;; Spec Field Option + Type Keywords
;; =============================================================================

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def NAME "Field option: Field name as Datomic-style keyword." ::spec/name)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def TYPE "Field option: Field type." ::spec/type)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def CARDINALITY "Field option: Field cardinality." ::spec/cardinality)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def DESCRIPTION "Field option: Human-readable field description." ::spec/description)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def REQUIRED "Field option: Whether field is required (default: true)." ::spec/required)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def VALUES "Field option: Enum values as map {value description}." ::spec/values)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def TARGET "Field option: Reference target for :spec.type/ref fields." ::spec/target)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def UNION "Field option: Set of allowed nil types." ::spec/union)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def KEY-NS "Spec option: Namespace prefix to add to keys during parsing." ::spec/key-ns)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def HUMANIZE "Field option: When true, marks field for humanization." ::spec/humanize?)

;; Base types
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_STRING "Type: String value." :spec.type/string)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_INT "Type: Integer value." :spec.type/int)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_FLOAT "Type: Floating point value." :spec.type/float)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_BOOL "Type: Boolean value." :spec.type/bool)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DATE "Type: ISO date (YYYY-MM-DD)." :spec.type/date)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_DATETIME "Type: ISO datetime." :spec.type/datetime)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_REF "Type: Reference to another spec." :spec.type/ref)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def TYPE_KEYWORD "Type: Clojure keyword." :spec.type/keyword)

;; Fixed-size vector types (generated — 36 defs for INT/STRING/DOUBLE × 1..12)
(doseq [[prefix kw-prefix] [["INT" "int"] ["STRING" "string"] ["DOUBLE" "double"]]
        n (range 1 13)]
  (let [sym (symbol (str "TYPE_" prefix "_V_" n))
        kw (keyword "spec.type" (str kw-prefix "-v-" n))
        doc (format "Type: Fixed-size %s vector (%d element%s)." kw-prefix n (if (= n 1) "" "s"))]
    (intern *ns* (with-meta sym {:doc doc :clj-kondo/ignore [:clojure-lsp/unused-public-var]}) kw)))

;; Cardinality
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def CARDINALITY_ONE "Cardinality: Single value." :spec.cardinality/one)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def CARDINALITY_MANY "Cardinality: Vector of values." :spec.cardinality/many)

;; =============================================================================
;; Humanize
;; =============================================================================

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def humanize-string "Removes AI-style phrases from text." humanize/humanize-string)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def humanize-data "Recursively humanizes all strings in a data structure." humanize/humanize-data)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def humanizer "Creates a humanization function with optional custom patterns." humanize/humanizer)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def HUMANIZE_SAFE_PATTERNS "Safe humanization patterns: AI identity, refusal, knowledge, punctuation." humanize/SAFE_PATTERNS)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def HUMANIZE_AGGRESSIVE_PATTERNS "Aggressive humanization patterns: hedging, overused verbs/adjectives/nouns." humanize/AGGRESSIVE_PATTERNS)

;; =============================================================================
;; Guards
;; =============================================================================

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def static-guard "Creates a guard function that checks for prompt injection patterns." guard/static)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def moderation-guard "Creates a guard function that uses LLM to check content against policies." guard/moderation)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def guard "Runs guard(s) on input." guard/guard)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def GUARD_DEFAULT_INJECTION_PATTERNS "Default patterns for prompt injection detection." guard/DEFAULT_INJECTION_PATTERNS)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def GUARD_DEFAULT_MODERATION_POLICIES "Default OpenAI moderation policies to check." guard/DEFAULT_MODERATION_POLICIES)

;; =============================================================================
;; LLM client
;; =============================================================================

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def image "Creates an image attachment for use with `user` messages." llm/image)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def system "Creates a system message." llm/system)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def user "Creates a user message, optionally with images." llm/user)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def assistant "Creates an assistant message." llm/assistant)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def ask! "Asks the LLM and returns structured Clojure data with token usage and cost." llm/ask!)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def abstract! "Creates a dense, entity-rich summary using Chain of Density prompting." llm/abstract!)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def eval! "Evaluates an LLM output using LLM self-evaluation." llm/eval!)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def refine! "Iteratively refines LLM output using decomposition and verification." llm/refine!)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def models! "Fetches available models from the LLM API." llm/models!)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def sample! "Generates test data samples matching a spec with self-correction." llm/sample!)

;; =============================================================================
;; RLM (Recursive Language Model)
;; =============================================================================

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def create-env
  "Creates an RLM environment for processing large contexts via iterative code execution.
   DB modes (via :db key):
     nil                — no DB (SCI only)
     :temp              — ephemeral SQLite, deleted on dispose
     \"path\"             — persistent SQLite at path
     {:path \"path\"}     — persistent SQLite at path
     {:datasource ds}   — caller-owned javax.sql.DataSource (NOT closed on dispose)"
  rlm/create-env)

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def register-env-fn! "Registers a custom function in the RLM's SCI sandbox." rlm/register-env-fn!)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def register-env-def! "Registers a constant in the RLM's SCI sandbox." rlm/register-env-def!)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def ingest-to-env! "Ingests documents into an RLM environment for querying." rlm/ingest-to-env!)
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def dispose-env! "Disposes an RLM environment and cleans up resources." rlm/dispose-env!)

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def query-env!
  "Runs a query against an RLM environment using iterative code execution.

   Takes a messages vector (always a vector, never a string):
     (query-env! env [(user \"What is schema therapy?\")])
     (query-env! env [(user \"Describe this\" (image b64 \"image/png\"))])
     (query-env! env [(user \"Context: schema therapy\")
                      (user \"Explain this diagram\" (image b64))]
                 {:max-iterations 50})"
  rlm/query-env!)

(def register-hook! "Attach a hook to an existing tool's :before / :after / :wrap chain." rlm/register-hook!)
(def unregister-hook! "Remove a per-tool hook entry by :id." rlm/unregister-hook!)
(def list-tool-hooks "Return hook chains registered for a tool symbol." rlm/list-tool-hooks)
(def list-registered-tools "Return a vec of {:sym :hook-counts} for every registered tool." rlm/list-registered-tools)
(def list-queries "Lists query records from an RLM environment." rlm/list-queries)
(def export-trajectories! "Exports filtered trajectories as JSONL for fine-tuning." rlm/export-trajectories!)
(def pprint-trace "Pretty-prints an RLM trace to a string." rlm/pprint-trace)
(def print-trace "Pretty-prints an RLM trace to stdout." rlm/print-trace)
(def query-env-qa! "Generates Q&A pairs from ingested documents using a multi-stage pipeline." rlm/query-env-qa!)
(def save-qa! "Saves query-env-qa! results to EDN and/or Markdown files." rlm/save-qa!)

;; =============================================================================
;; PageIndex
;; =============================================================================

(def index!
  "Index a document file (PDF, MD, TXT) and save structured data as EDN + PNG files.

   Creates a .pageindex directory alongside the original file containing:
     document.edn — structured document data
     images/      — extracted images as PNG files

   Opts:
     :pages              - Page selector (1-indexed), nil = all pages
     :parallel           - Max concurrent page extractions (default: 3)
     :extraction-strategy - :vision (default) or :ocr
     :vision-model       - Model for vision extraction
     :ocr-model          - OCR model name (required for :ocr strategy)
     :text-model         - Text LLM for structuring output
     :refine?            - Enable post-extraction quality refinement (default: false)
     :refine-model       - Model for eval/refine steps
     :parallel-refine    - Max concurrent eval/refine operations (default: 2)"
  rlm/index!)

(def load-index
  "Load an indexed document from a .pageindex directory.
   Reads the EDN + PNG files produced by `index!` and returns the document map."
  rlm/load-index)
