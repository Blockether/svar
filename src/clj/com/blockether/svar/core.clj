(ns com.blockether.svar.core
  "LLM interaction utilities for structured and unstructured outputs.

   SVAR = Structured Validated Automated Reasoning

    Scope: structured LLM output + provider routing. Main functions:
    - `ask!` - Structured output using the spec DSL
    - `ask-code!` - native tool-calling completion (the model acts via tools)
    - `models!` - Fetch available models from the LLM API

     Re-exports the spec DSL (`field`, `spec`, `str->data`, `str->data-with-spec`,
     `data->str`, `validate-data`, `spec->prompt`, `build-ref-registry`) and
     `make-router` so users can require only this namespace. The provider
     catalog, token counting, pricing and failure classification that routing
     uses are public here too (`KNOWN_PROVIDERS`, `count-tokens`,
     `estimate-cost`, `classify-failure`, ...).

   Configuration:
   LLM calls route automatically via the router.

    Example:
     (ask! router {:spec my-spec
                   :messages [(system \"Help the user.\")
                              (user \"What is 2+2?\")]
                   :model \"gpt-4o\"})"
  (:require [com.blockether.svar.internal.failure :as failure]
            [com.blockether.svar.internal.llm :as llm]
            [com.blockether.svar.internal.router :as router]
            [com.blockether.svar.internal.spec :as spec]))

;; =============================================================================
;; Router
;; =============================================================================

(def make-router "Creates a router from a vector of provider maps." llm/make-router)

(def router-stats "Returns cumulative + windowed stats for the router." llm/router-stats)

(defn prompt-cache-status
  "Returns Svar-owned, route-local provider prompt-cache telemetry."
  ([router] (llm/prompt-cache-status router))
  ([router cache-scope provider-id model]
   (llm/prompt-cache-status router cache-scope provider-id model)))

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
  "Coerce any accepted spelling to canonical :low|:balanced|:deep.
   Also accepts :low/:medium/:high aliases for OpenAI-style migrations."
  router/normalize-reasoning-level)

(def reasoning-extra-body
  "Translate abstract level → provider-specific extra-body map (or nil).
   Returns nil for non-reasoning models; callers can merge the result into
   their own extra-body."
  router/reasoning-extra-body)

(def resolve-reasoning-effort
  "Resolve exact provider-native `high|max` support and wire evidence.
   No abstract reasoning aliases or automatic translations are applied."
  router/resolve-reasoning-effort)

;; =============================================================================
;; Provider defaults (single source of truth — consumers override these)
;; =============================================================================

(def provider-base-url
  "Sane default base-url svar knows for a provider id (plan-tier aware).
   Consumers (e.g. vis provider extensions) use it as the preset default and
   override only for local/custom endpoints."
  router/provider-base-url)

(def provider-default-models
  "Sane default model NAMES (vec of strings) svar curates for a provider id
   (plan-tier aware). The single source of truth — consumers use it as their
   `:default-models` and override only for a different curated set."
  router/provider-default-models)

(def sort-models
  "Model names, or maps with `:name`, best first in svar's canonical cross-provider
   order (`MODEL_ORDER`). With a provider id, that provider's lead models come first
   and its catalog rows place models the order does not name. Consumers sort their
   model lists with it so pickers, the first model and fallbacks agree."
  router/sort-models)

;; =============================================================================
;; Provider catalog, tokens and pricing
;; =============================================================================

(def KNOWN_PROVIDERS
  "Svar's built-in provider catalog: provider id -> defaults such as `:base-url`,
   `:api-style`, `:env-keys`, `:default-models`, rate limits and plan-tier policy.
   Consumers layer their own provider metadata over these defaults."
  router/KNOWN_PROVIDERS)

(def normalize-provider
  "Normalizes one provider entry the way `make-router` does: fills `:base-url`
   from `KNOWN_PROVIDERS`, derives `:priority` and `:root`, and merges model
   metadata with provider-scoped pricing and context limits.
   Takes the entry's position and the entry."
  router/normalize-provider)

(def provider-model-metadata
  "Metadata for one model as a provider serves it (capabilities, pricing,
   context limits), without building a router. Takes a provider id and a model
   map with at least `:name`."
  router/provider-model-metadata)

(def hidden-model?
  "True when model lists leave `model-name` out on every provider: stealth models and
   previews. A model configured by name still routes."
  router/hidden-model?)

(def provider-model-visible?
  "True when model lists offer `model-name` for the provider: its model filters allow
   it, `hidden-model?` does not hide it, and it is not outdated there. Outdated models
   are older than their family's minimum version, dated Claude snapshots or deprecated in
   the provider's models.dev catalog; local providers list every model they serve."
  router/provider-model-visible?)

(def resolve-effective-model
  "The model descriptor a router would route to, optionally under routing
   overrides such as `:optimize`, `:provider` or `:model`. Returns nil when no
   provider is available."
  router/resolve-effective-model)

(def count-tokens
  "Counts the tokens of a text string with the model's tokenizer. A tokenizer's
   special tokens count as the plain text they are."
  router/count-tokens)

(def count-messages
  "Estimates the tokens of a message vector for a model, including reasoning,
   tool payloads, text and images. Provider usage stays authoritative."
  router/count-messages)

(def MODEL_PRICING
  "Flattened model name -> pricing table (USD per 1M tokens) that
   `estimate-cost` uses when no pricing map is given. A model served by several
   providers takes the cheapest total."
  router/MODEL_PRICING)

(def estimate-cost
  "Estimates USD cost from input and output tokens, with separate uncached
   input, cached input, cache creation and output components. Rates are USD per
   1M tokens."
  router/estimate-cost)

;; =============================================================================
;; Failures and log context
;; =============================================================================

(def classify-failure
  "Classifies a provider or gateway failure into a stable shape:
   `{:category :retryable? :reached-model? :status :request-id :summary
   :next-step ...}`."
  failure/classify)

(def STREAM_WATCHDOG_ERROR_TYPES
  "Typed stream aborts (time to first token, idle and semantic watchdogs) that
   are safe to retry only before visible output."
  failure/STREAM_WATCHDOG_ERROR_TYPES)

(defmacro with-log-context
  "Evaluates `body` with `context` merged into the map svar adds to its HTTP
   logs, e.g. `{:query-id \"abc\" :iteration 0}`."
  [context & body]
  `(binding [llm/*log-context* (merge llm/*log-context* ~context)]
     ~@body))

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
(doseq [[prefix kw-prefix]
        [["INT" "int"] ["STRING" "string"] ["DOUBLE" "double"]]

        n
        (range 1 13)]

  (let [sym
        (symbol (str "TYPE_" prefix "_V_" n))

        kw
        (keyword "spec.type" (str kw-prefix "-v-" n))

        doc
        (format "Type: Fixed-size %s vector (%d element%s)." kw-prefix n (if (= n 1) "" "s"))]

    (intern *ns* (with-meta sym {:doc doc :clj-kondo/ignore [:clojure-lsp/unused-public-var]}) kw)))

;; Cardinality
(def CARDINALITY_ONE "Cardinality: Single value." :spec.cardinality/one)

(def CARDINALITY_MANY "Cardinality: Vector of values." :spec.cardinality/many)

;; =============================================================================
;; LLM client
;; =============================================================================

(def image "Creates an image attachment for use with `user` messages." llm/image)

(def system "Creates a system message." llm/system)

(def user "Creates a user message, optionally with images." llm/user)

(def assistant "Creates an assistant message." llm/assistant)

(def cached
  "Wraps text in a cacheable content block. Anthropic emits `cache_control`.
   GPT-5.6+ Responses emits explicit prompt-cache breakpoints, including a
   rolling prior-turn boundary; older OpenAI-compatible styles strip the marker."
  llm/cached)

(def prompt-cache-context
  "Returns Svar's opaque fixed-prefix/cache-namespace identity for routed tool calls."
  llm/prompt-cache-context)

(def open-session
  "Opens a sequential LLM session. OpenAI Codex uses a persistent Responses
   WebSocket and server continuation, with no default request byte ceiling.
   A cursorless request above `:websocket-max-full-request-bytes` or a
   session-local ceiling learned from a 1009 close uses HTTP for that turn.
   Smaller replays can re-enter WebSocket. Close the session with
   `close-session!` or `with-open`."
  llm/open-session)

(def close-session!
  "Closes an explicit LLM session; active transport aborts, idle transport closes gracefully. Idempotent."
  llm/close-session!)

(def session-history "Returns an explicit session's canonical replay history." llm/session-history)

(def session-status
  "Returns provider-safe transport telemetry for an explicit session."
  llm/session-status)

(def ask!
  "Asks the LLM and returns structured Clojure data with token usage and cost.
   Responses calls also return :request-accounting; see `ask-code!`.
   With an explicit session, appends one native completion turn; `{:history [...]}`
   replaces canonical history while retaining the physical provider connection."
  llm/ask!)

(def ask-code!
  "Native tool-calling completion. Sibling of `ask!` (structured `:spec`).
   The model takes action by calling a `:tool`; no tool call ⇒ its text is the
   final answer (`:stop-reason :end`). Returns {:stop-reason :tool-calls|:end
   :tool-calls :content :assistant-message :reasoning :tokens :cost :duration-ms
   :rate-limit :prompt-cache-context}. `:rate-limit` (when the provider sent quota
   headers) carries `{:resets-at-ms <epoch-ms> :remaining :limit :windows}`.

   Responses calls also return content-free :request-accounting on the result and
   final :on-chunk callback, even with :check-context? false or missing usage:
   {:source :svar-estimate :projection :prepared-request :model <actual model>
    :api-style :openai-compatible-responses :input-tokens N
    :components {:messages N :instructions N :tools N :output-format N :reply-priming N}}.
   Components sum to :input-tokens; messages/instructions include their framing.
   Counts cover the final attempt after replay filtering, tool shaping and body
   overrides, not discarded retries. For WebSocket continuation this is the full
   prepared context, not just the transmitted delta. Preflight and provider
   context-overflow ex-data carry the rejected request's :request-accounting.
   Other API styles omit it. No prompt, tool payload, signature or credential is
   included. Text/schema tokens use the model tokenizer; images, opaque reasoning
   and framing remain estimates. Provider usage (including cached input) stays
   authoritative; this API neither rescales usage nor sums retries.

   `:input-token-estimator` optionally accepts a request map with :provider-id,
   :model, :messages, opaque :prompt-cache-context, :tokenizer and :local-input-tokens.
   Return a nonnegative input count only for a validated unchanged measured prefix
   on this exact route/account/policy; return nil otherwise. Preflight then uses the
   estimate instead of recounting that prefix. Provider usage is never overwritten.
   Supported tokenizer declarations are o200k_base and cl100k_base; other names
   retain the model-name/local fallback, so all local counts remain estimates."
  llm/ask-code!)

(def context-budget
  "Resolve a route's input/output budget without inference. See internal.llm/context-budget."
  llm/context-budget)

(def model-catalog-identity
  "Credential-safe account/endpoint identity for persisting live model metadata."
  llm/model-catalog-identity)

(def models!
  "Fetch models with published :context (total window), :input-limit, :output-limit
   and :tokenizer metadata when available. Missing fields remain absent."
  llm/models!)
