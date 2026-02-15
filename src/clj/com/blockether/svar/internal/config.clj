(ns com.blockether.svar.internal.config
  "LLM configuration management.
   
   Provides a single `make-config` for creating validated config maps.
   No DI, no global state. Config is a plain immutable map.
   
    Environment variables (used as fallback for :api-key and :base-url):
     - BLOCKETHER_OPENAI_API_KEY (checked first)
     - BLOCKETHER_OPENAI_BASE_URL (checked first)
     - OPENAI_API_KEY
     - OPENAI_BASE_URL
   
   Usage:
   (def config (make-config {:api-key \"sk-...\"
                              :base-url \"https://api.openai.com/v1\"
                              :model \"gpt-4o\"}))
    (ask! {:config config :spec my-spec :messages [(system \"...\") (user \"...\")]})"
  (:require
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.internal.tokens :as tokens]))

;; =============================================================================
;; Defaults
;; =============================================================================

(def DEFAULT_MODEL
  "Default LLM model when not specified."
  "gpt-4o")

(def DEFAULT_BASE_URL
  "Default OpenAI API base URL."
  "https://api.openai.com/v1")

(def DEFAULT_TIMEOUT_MS
  "Default HTTP request timeout in milliseconds (3 minutes)."
  180000)

(def DEFAULT_RETRY
  "Default retry policy for transient HTTP errors."
  {:max-retries 5
   :initial-delay-ms 1000
   :max-delay-ms 60000
   :multiplier 2.0})

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- get-env
  "Gets environment variable value, returns nil if empty."
  [var-name]
  (let [value (System/getenv var-name)]
    (when (and value (not (empty? value)))
      value)))

;; =============================================================================
;; make-config
;; =============================================================================

(defn make-config
  "Creates an LLM configuration map with defaults and env var fallback.
   
   Params:
   `opts` - Map with keys:
      - :api-key - String, optional. Falls back to BLOCKETHER_OPENAI_API_KEY,
                    then OPENAI_API_KEY env var.
       - :base-url - String, optional. Falls back to BLOCKETHER_OPENAI_BASE_URL,
                    then OPENAI_BASE_URL env var, then DEFAULT_BASE_URL.
      - :model - String, optional. Default model for all calls (default: \"gpt-4o\").
      - :network - Map, optional. Network settings (merged over defaults):
          - :timeout-ms - Integer. HTTP request timeout (default: 180000).
          - :max-retries - Integer. Maximum retry attempts (default: 5).
          - :initial-delay-ms - Integer. First retry delay (default: 1000).
          - :max-delay-ms - Integer. Maximum retry delay (default: 60000).
          - :multiplier - Double. Backoff multiplier (default: 2.0).
      - :tokens - Map, optional. Token-related settings:
          - :check-context? - Boolean. Pre-flight context limit check (default: true).
          - :pricing - Map. Per-model pricing overrides, merged over built-in defaults.
              Keys are model name strings, values are {:input price-per-1M :output price-per-1M}.
          - :context-limits - Map. Per-model context window overrides, merged over built-in defaults.
              Keys are model name strings, values are integer token counts.
          - :output-reserve - Integer. Tokens reserved for output in pre-flight context check.
              Defaults to model's max output tokens (model-aware). Set to 0 to disable.
   
   Returns:
   Validated config map with all defaults applied.
   
   Throws:
   ExceptionInfo if no API key available from any source.
   
   Example:
   (make-config {:api-key \"sk-...\" :base-url \"https://api.openai.com/v1\"})
    (make-config {:api-key \"sk-...\" :model \"gpt-4o-mini\"
                   :network {:timeout-ms 300000}})
    (make-config {}) ;; uses env vars for api-key/base-url"
  ([] (make-config {}))
  ([{:keys [api-key base-url model network tokens]}]
   (let [api-key (or api-key
                       (get-env "BLOCKETHER_OPENAI_API_KEY")
                       (get-env "OPENAI_API_KEY"))
           base-url (or base-url
                        (get-env "BLOCKETHER_OPENAI_BASE_URL")
                        (get-env "OPENAI_BASE_URL")
                        DEFAULT_BASE_URL)]
       (when-not api-key
         (anomaly/incorrect! "LLM API key required. Set BLOCKETHER_OPENAI_API_KEY or OPENAI_API_KEY env var, or pass :api-key."
                            {:type :svar/missing-api-key}))
      {:api-key api-key
       :base-url base-url
       :model (or model DEFAULT_MODEL)
       :network (merge DEFAULT_RETRY
                       {:timeout-ms DEFAULT_TIMEOUT_MS}
                       network)
       :tokens {:check-context? (if (some? (:check-context? tokens)) (:check-context? tokens) true)
                :pricing (merge tokens/DEFAULT_MODEL_PRICING (:pricing tokens))
                :context-limits (merge tokens/DEFAULT_CONTEXT_LIMITS (:context-limits tokens))
                :output-reserve (:output-reserve tokens)}})))
