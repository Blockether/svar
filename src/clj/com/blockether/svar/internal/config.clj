(ns com.blockether.svar.internal.config
  "LLM configuration management.
   
   Provides a single `make-config` for creating validated config maps.
   No DI, no global state. Config is a plain immutable map.
   
   Environment variables (used as fallback for :api-key and :base-url):
   - OPENAI_API_KEY / BLOCKETHER_LLM_API_KEY
   - OPENAI_BASE_URL / BLOCKETHER_LLM_API_BASE_URL
   
   Usage:
   (def config (make-config {:api-key \"sk-...\"
                              :base-url \"https://api.openai.com/v1\"
                              :model \"gpt-4o\"}))
   (ask! {:config config :spec my-spec :task \"...\"})"
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
     - :api-key - String, optional. Falls back to OPENAI_API_KEY or BLOCKETHER_LLM_API_KEY env var.
     - :base-url - String, optional. Falls back to OPENAI_BASE_URL or BLOCKETHER_LLM_API_BASE_URL env var,
                   then DEFAULT_BASE_URL.
     - :model - String, optional. Default model for all calls (default: \"gpt-4o\").
     - :timeout-ms - Integer, optional. HTTP request timeout (default: 180000).
     - :check-context? - Boolean, optional. Pre-flight context limit check (default: true).
     - :retry - Map, optional. Retry policy (merged over DEFAULT_RETRY):
         - :max-retries, :initial-delay-ms, :max-delay-ms, :multiplier
     - :pricing - Map, optional. Per-model pricing overrides, merged over built-in defaults.
         Keys are model name strings, values are {:input price-per-1M :output price-per-1M}.
     - :context-limits - Map, optional. Per-model context window overrides, merged over built-in defaults.
         Keys are model name strings, values are integer token counts.
   
   Returns:
   Validated config map with all defaults applied.
   
   Throws:
   ExceptionInfo if no API key available from any source.
   
   Example:
   (make-config {:api-key \"sk-...\" :base-url \"https://api.openai.com/v1\"})
   (make-config {:api-key \"sk-...\" :model \"gpt-4o-mini\" :timeout-ms 300000})
   (make-config {}) ;; uses env vars for api-key/base-url"
  ([] (make-config {}))
  ([{:keys [api-key base-url model timeout-ms check-context? retry pricing context-limits]}]
   (let [api-key (or api-key
                     (get-env "OPENAI_API_KEY")
                     (get-env "BLOCKETHER_LLM_API_KEY"))
         base-url (or base-url
                      (get-env "OPENAI_BASE_URL")
                      (get-env "BLOCKETHER_LLM_API_BASE_URL")
                      DEFAULT_BASE_URL)]
     (when-not api-key
       (anomaly/incorrect! "LLM API key required. Set OPENAI_API_KEY or BLOCKETHER_LLM_API_KEY env var, or pass :api-key."
                           {:type :svar/missing-api-key}))
     {:api-key api-key
      :base-url base-url
      :model (or model DEFAULT_MODEL)
      :timeout-ms (or timeout-ms DEFAULT_TIMEOUT_MS)
      :check-context? (if (some? check-context?) check-context? true)
      :retry (merge DEFAULT_RETRY retry)
      :pricing (merge tokens/DEFAULT_MODEL_PRICING pricing)
      :context-limits (merge tokens/DEFAULT_CONTEXT_LIMITS context-limits)})))
