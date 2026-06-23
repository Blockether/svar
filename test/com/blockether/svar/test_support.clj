(ns com.blockether.svar.test-support
  "Shared helpers for svar's live/integration test gating.")

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
