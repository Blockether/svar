(ns com.blockether.svar.test-support "Shared helpers for svar's live/integration test gating.")

(defn env
  "Value of env var `k`, or nil when it is unset OR blank.

   A blank env var (e.g. `BLOCKETHER_LLM_API_KEY=` exported by CI or a
   shell) must read as ABSENT — not as an enabled empty key. `(some? \"\")`
   is `true`, the exact trap that let credential-gated live tests run
   against an empty key and fail. Coercing blank -> nil at the source fixes
   both the `*-enabled?` predicates AND the `(or key-a key-b)` fallback
   chains — an empty string is truthy in Clojure, so it would otherwise be
   picked over a real fallback key."
  [k]
  (let [v (System/getenv k)]
    (when-not (or (nil? v) (.isBlank ^String v)) v)))

(defn first-env
  "First non-blank value among env var names `ks`, or nil. Use for the
   `(or (getenv a) (getenv b) …)` fallback chains so a blank earlier var
   doesn't shadow a real later one."
  [& ks]
  (some env ks))

;; =============================================================================
;; Blockether One — the live endpoint every live test targets
;; =============================================================================

(def FAST_MODEL
  "Fastest reliable chat model on Blockether One for svar (about 1.5 s a call).
   `gpt-oss-120b` (Groq) is faster, but Groq rejects svar's `prompt_cache_key`.
   `nemotron-3-ultra` is as fast, but often nests or drops spec fields."
  "minimax-m3.1-flash:cloud")

(def SECOND_MODEL
  "Second model in multi-model routing tests. Routing excludes it, so the tests
   never call it."
  "nemotron-3-ultra")

(def ^:private MODEL_META
  "Pricing (USD per 1M tokens) and reasoning metadata of the live models.
   `:blockether` is a custom provider id, so the tests supply this metadata."
  {FAST_MODEL {:pricing {:input 0.15 :output 0.60}
               :context 200000
               :reasoning? true
               :reasoning-style :openai-effort}
   SECOND_MODEL {:pricing {:input 0.60 :output 2.40}
                 :context 262144
                 :reasoning? true
                 :reasoning-style :openai-effort}})

(defn blockether-key
  "Blockether One API key, or nil."
  []
  (first-env "BLOCKETHER_LLM_API_KEY" "BLOCKETHER_OPENAI_API_KEY"))

(defn blockether-enabled?
  "True when a Blockether One key is set. Live tests pass vacuously without it."
  []
  (some? (blockether-key)))

(defn blockether-base-url
  "Blockether One base URL."
  []
  (or (first-env "BLOCKETHER_LLM_API_BASE_URL" "BLOCKETHER_OPENAI_BASE_URL")
      "https://llm.blockether.com/v1"))

(defn blockether-provider
  "A `:blockether` provider map. Each model is a name or a model map; known
   names get `MODEL_META`, and model-map keys win over it."
  [models]
  {:id :blockether
   :api-key (blockether-key)
   :base-url (blockether-base-url)
   :models (mapv (fn [m]
                   (let [m (if (string? m) {:name m} m)]
                     (merge (MODEL_META (:name m)) m)))
                 models)})
