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

     Re-exports spec DSL (`field`, `spec`, `str->data`, `str->data-with-spec`,
     `data->str`, `validate-data`, `spec->prompt`, `build-ref-registry`),
     and `make-router` so users can require only this namespace.

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
   [com.blockether.svar.internal.router :as router]
   [com.blockether.svar.internal.spec :as spec]))

;; =============================================================================
;; Router
;; =============================================================================

(def make-router "Creates a router from a vector of provider maps." llm/make-router)
(def router-stats "Returns cumulative + windowed stats for the router." llm/router-stats)
(def reset-budget! "Resets the router's token/cost budget counters to zero." llm/reset-budget!)
(def reset-provider! "Manually resets a provider's circuit breaker to :closed." llm/reset-provider!)

;; =============================================================================
;; Reasoning depth (abstract, provider-agnostic)
;; =============================================================================

(def REASONING_LEVELS
  "Abstract reasoning depths translated per provider api-style.
   See `com.blockether.svar.internal.router/REASONING_LEVELS`."
  router/REASONING_LEVELS)

(def normalize-reasoning-level
  "Coerce any accepted spelling to canonical :quick|:balanced|:deep.
   Also accepts :low/:medium/:high aliases for OpenAI-style migrations."
  router/normalize-reasoning-level)

(def reasoning-extra-body
  "Translate abstract level → provider-specific extra-body map (or nil).
   Returns nil for non-reasoning models; callers can merge the result into
   their own extra-body."
  router/reasoning-extra-body)

;; =============================================================================
;; Spec DSL
;; =============================================================================

(def field "Creates a field definition for a spec." spec/field)
(def spec "Creates a spec definition from field definitions." spec/spec)
(def build-ref-registry "Builds a registry of referenced specs." spec/build-ref-registry)
(def str->data "Parses LLM response string to Clojure data." spec/str->data)
(def str->data-with-spec "Parses LLM response with spec validation." spec/str->data-with-spec)
(def data->str "Serializes Clojure data to LLM-compatible string." spec/data->str)
(def validate-data "Validates parsed data against a spec." spec/validate-data)
(def spec->prompt "Generates LLM prompt from a spec." spec/spec->prompt)

;; =============================================================================
;; Spec Field Option + Type Keywords
;; =============================================================================

(def NAME "Field option: Field name as Datomic-style keyword." ::spec/name)
(def TYPE "Field option: Field type." ::spec/type)
(def CARDINALITY "Field option: Field cardinality." ::spec/cardinality)
(def DESCRIPTION "Field option: Human-readable field description." ::spec/description)
(def REQUIRED "Field option: Whether field is required (default: true)." ::spec/required)
(def VALUES "Field option: Enum values as map {value description}." ::spec/values)
(def TARGET "Field option: Reference target for :spec.type/ref fields." ::spec/target)
(def UNION "Field option: Set of allowed nil types." ::spec/union)
(def KEY-NS "Spec option: Namespace prefix to add to keys during parsing." ::spec/key-ns)
(def HUMANIZE "Field option: When true, marks field for humanization." ::spec/humanize?)

;; Base types
(def TYPE_STRING "Type: String value." :spec.type/string)
(def TYPE_INT "Type: Integer value." :spec.type/int)
(def TYPE_FLOAT "Type: Floating point value." :spec.type/float)
(def TYPE_BOOL "Type: Boolean value." :spec.type/bool)
(def TYPE_DATE "Type: ISO date (YYYY-MM-DD)." :spec.type/date)
(def TYPE_DATETIME "Type: ISO datetime." :spec.type/datetime)
(def TYPE_REF "Type: Reference to another spec." :spec.type/ref)
(def TYPE_KEYWORD "Type: Clojure keyword." :spec.type/keyword)

;; Fixed-size vector types (generated — 36 defs for INT/STRING/DOUBLE × 1..12)
(doseq [[prefix kw-prefix] [["INT" "int"] ["STRING" "string"] ["DOUBLE" "double"]]
        n (range 1 13)]
  (let [sym (symbol (str "TYPE_" prefix "_V_" n))
        kw (keyword "spec.type" (str kw-prefix "-v-" n))
        doc (format "Type: Fixed-size %s vector (%d element%s)." kw-prefix n (if (= n 1) "" "s"))]
    (intern *ns* (with-meta sym {:doc doc :clj-kondo/ignore [:clojure-lsp/unused-public-var]}) kw)))

;; Cardinality
(def CARDINALITY_ONE "Cardinality: Single value." :spec.cardinality/one)
(def CARDINALITY_MANY "Cardinality: Vector of values." :spec.cardinality/many)

;; =============================================================================
;; Humanize
;; =============================================================================

(def humanize-string "Removes AI-style phrases from text." humanize/humanize-string)
(def humanize-data "Recursively humanizes all strings in a data structure." humanize/humanize-data)
(def humanizer "Creates a humanization function with optional custom patterns." humanize/humanizer)
(def HUMANIZE_SAFE_PATTERNS "Safe humanization patterns: AI identity, refusal, knowledge, punctuation." humanize/SAFE_PATTERNS)
(def HUMANIZE_AGGRESSIVE_PATTERNS "Aggressive humanization patterns: hedging, overused verbs/adjectives/nouns." humanize/AGGRESSIVE_PATTERNS)

;; =============================================================================
;; Guards
;; =============================================================================

(def static-guard "Creates a guard function that checks for prompt injection patterns." guard/static)
(def moderation-guard "Creates a guard function that uses LLM to check content against policies." guard/moderation)
(def guard "Runs guard(s) on input." guard/guard)
(def GUARD_DEFAULT_INJECTION_PATTERNS "Default patterns for prompt injection detection." guard/DEFAULT_INJECTION_PATTERNS)
(def GUARD_DEFAULT_MODERATION_POLICIES "Default OpenAI moderation policies to check." guard/DEFAULT_MODERATION_POLICIES)

;; =============================================================================
;; LLM client
;; =============================================================================

(def image "Creates an image attachment for use with `user` messages." llm/image)
(def system "Creates a system message." llm/system)
(def user "Creates a user message, optionally with images." llm/user)
(def assistant "Creates an assistant message." llm/assistant)
(def cached
  "Wraps text in a cacheable content block. On `:anthropic` api-style emits
   `cache_control: {type: \"ephemeral\"}`; on other styles the marker is stripped."
  llm/cached)
(def ask! "Asks the LLM and returns structured Clojure data with token usage and cost." llm/ask!)
(def abstract! "Creates a dense, entity-rich summary using Chain of Density prompting." llm/abstract!)
(def eval! "Evaluates an LLM output using LLM self-evaluation." llm/eval!)
(def refine! "Iteratively refines LLM output using decomposition and verification." llm/refine!)
(def models! "Fetches available models from the LLM API." llm/models!)
(def sample! "Generates test data samples matching a spec with self-correction." llm/sample!)
