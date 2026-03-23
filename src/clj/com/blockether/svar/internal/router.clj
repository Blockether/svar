(ns com.blockether.svar.internal.router
  "Smart provider routing for RLM workloads.

   Routes LLM calls across multiple OpenAI-compatible providers with:
   - Use-case aware model selection (:root, :sub, :extraction, :refinement, :planning)
   - Sliding-window rate limit tracking (RPM/TPM per provider)
   - Automatic 429 fallback to next available provider
   - Provider capability filtering (:tools)

   Usage:
   ```clojure
   (def router (make-router [{:id :openai
                               :api-key \"sk-...\"
                               :base-url \"https://api.openai.com/v1\"
                               :models {:root \"gpt-4o\"
                                        :sub \"gpt-4o-mini\"
                                        :extraction \"gpt-4o-mini\"
                                        :refinement \"gpt-4o\"
                                        :planning \"gpt-4o\"}
                               :tools #{:chat :vision :function-calling}
                               :rpm 500 :tpm 200000 :priority 0}]))

   (routed-chat-completion router messages :sub)
   ;; => {:content \"...\" :reasoning nil :api-usage {...}}

   (routed-ask! router {:spec my-spec :messages [...]} :extraction)
   ;; => {:result {...} :tokens {...} :cost {...}}
   ```"
  (:require
   [clojure.core.async :as async]
   [com.blockether.svar.internal.llm :as llm]
   [taoensso.trove :as trove]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private WINDOW_MS
  "Sliding window duration for rate limit tracking (60 seconds)."
  60000)

(def ^:private COOLDOWN_MS
  "Cooldown period after a 429 response (60 seconds)."
  60000)

(def ^:private MAX_WAIT_MS
  "Maximum time to wait for a provider to become available."
  30000)

;; =============================================================================
;; Rate Limit State
;; =============================================================================

(defn- now-ms [] (System/currentTimeMillis))

(defn- prune-window
  "Removes entries older than WINDOW_MS from a sliding window."
  [entries]
  (let [cutoff (- (now-ms) WINDOW_MS)]
    (filterv #(> (if (map? %) (:ts %) %) cutoff) entries)))

(defn- rpm-count
  "Returns the number of requests in the current sliding window."
  [provider-state]
  (count (prune-window (:requests provider-state []))))

(defn- tpm-count
  "Returns the total tokens consumed in the current sliding window."
  [provider-state]
  (reduce + 0 (map :n (prune-window (:tokens provider-state [])))))

(defn- in-cooldown?
  "Returns true if the provider is in cooldown (recently 429'd)."
  [provider-state]
  (when-let [until (:cooldown-until provider-state)]
    (> until (now-ms))))

(defn- available?
  "Returns true if the provider has RPM/TPM headroom and is not in cooldown."
  [provider provider-state]
  (and (not (in-cooldown? provider-state))
       (< (rpm-count provider-state) (:rpm provider Long/MAX_VALUE))
       (< (tpm-count provider-state) (:tpm provider Long/MAX_VALUE))))

;; =============================================================================
;; Provider Selection
;; =============================================================================

(defn- resolve-model
  "Resolves a model name from a provider's :models map.
   `use-case` is a keyword like :root, :sub, :extraction, :refinement, :planning.
   Returns the model name string or nil if the provider doesn't support this use-case."
  [provider use-case]
  (get (:models provider) use-case))

(defn select-provider
  "Picks the best available provider for a use-case.

   Params:
   `router` - Router from make-router.
   `use-case` - Keyword. One of :root, :sub, :extraction, :refinement, :planning.
   `opts` - Map, optional:
     - :tools - Set of required tool capabilities (e.g. #{:vision}).

   Returns [provider model-name] or nil if none available."
  ([router use-case] (select-provider router use-case {}))
  ([router use-case {:keys [tools]}]
   (let [{:keys [providers state]} router
         current-state @state
         candidates (->> providers
                         (filter #(some? (resolve-model % use-case)))
                         (filter #(available? % (get current-state (:id %) {})))
                         (filter #(or (nil? tools) (every? (or (:tools %) #{}) tools))))]
     (when (seq candidates)
       (let [provider (first (sort-by #(:priority % 0) candidates))]
         [provider (resolve-model provider use-case)])))))

(defn- earliest-available
  "Returns the earliest timestamp when any provider will become available for a use-case."
  [router use-case]
  (let [{:keys [providers state]} router
        current-state @state]
    (->> providers
         (filter #(some? (resolve-model % use-case)))
         (keep #(:cooldown-until (get current-state (:id %) {})))
         (filter #(> % (now-ms)))
         sort
         first)))

;; =============================================================================
;; State Updates
;; =============================================================================

(defn record-request!
  "Records a completed request for rate limit tracking."
  [router provider-id token-count]
  (let [ts (now-ms)]
    (swap! (:state router) update provider-id
           (fn [s]
             (-> (or s {})
                 (update :requests (fn [r] (conj (prune-window (or r [])) ts)))
                 (update :tokens (fn [t] (conj (prune-window (or t [])) {:ts ts :n (or token-count 0)}))))))))

(defn record-rate-limit!
  "Marks a provider as rate-limited (429 received). Sets cooldown."
  [router provider-id]
  (trove/log! {:level :warn :data {:provider provider-id :cooldown-ms COOLDOWN_MS}
               :msg "Provider rate-limited, entering cooldown"})
  (swap! (:state router) assoc-in [provider-id :cooldown-until] (+ (now-ms) COOLDOWN_MS)))

;; =============================================================================
;; Internal: Provider Retry Loop
;; =============================================================================

(defn- with-provider-fallback
  "Executes `f` with provider fallback on 429.
   `f` receives [provider model-name] and should return a result or throw.
   Retries with next provider on 429."
  [router use-case opts f]
  (let [tried (atom #{})]
    (loop [attempts 0]
      (if-let [[provider model] (select-provider router use-case opts)]
        (let [provider-id (:id provider)]
          (swap! tried conj provider-id)
          (let [result (try
                         (f provider model)
                         (catch Exception e
                           (let [status (:status (ex-data e))]
                             (if (= status 429)
                               (do (record-rate-limit! router provider-id)
                                   ::rate-limited)
                               (throw e)))))]
            (if (= result ::rate-limited)
              (recur (inc attempts))
              (do (record-request! router provider-id
                                   (or (get-in result [:api-usage :total_tokens])
                                       (get-in result [:tokens :total])
                                       0))
                  result))))
        ;; No provider available — wait for earliest cooldown
        (let [earliest (earliest-available router use-case)]
          (if (and earliest (< attempts 3))
            (let [wait-ms (min (- earliest (now-ms)) MAX_WAIT_MS)]
              (when (pos? wait-ms)
                (trove/log! {:level :info :data {:wait-ms wait-ms :use-case use-case}
                             :msg "All providers busy, waiting for cooldown"})
                (async/<!! (async/timeout wait-ms)))
              (recur (inc attempts)))
            (throw (ex-info "All providers exhausted for use-case"
                            {:type :svar.router/all-providers-exhausted
                             :use-case use-case
                             :tried @tried}))))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn make-router
  "Creates a provider router from a vector of provider configs.

   Each provider:
   - :id       - Keyword. Unique identifier (e.g. :openai, :together).
   - :api-key  - String. Bearer token.
   - :base-url - String. API base URL.
   - :models   - Map of use-case keyword to model name string:
                 {:root \"gpt-4o\"           ;; iteration loop
                  :sub \"gpt-4o-mini\"       ;; llm-query sub-calls from SCI
                  :extraction \"gpt-4o-mini\" ;; entity extraction
                  :refinement \"gpt-4o\"     ;; answer refinement
                  :planning \"gpt-4o\"}      ;; pre-planning phase
   - :tools    - Set of capabilities (optional): #{:chat :vision :function-calling}
   - :rpm      - Integer. Requests per minute limit (optional, default unlimited).
   - :tpm      - Integer. Tokens per minute limit (optional, default unlimited).
   - :priority - Integer. Tiebreaker — lower = preferred (optional, default 0).

   Returns router map. Pass to routed-chat-completion, routed-ask!."
  [providers]
  {:providers (vec providers)
   :state (atom (zipmap (map :id providers)
                        (repeat {:requests [] :tokens [] :cooldown-until nil})))})

(defn routed-chat-completion
  "Like llm/chat-completion but routes across providers by use-case.

   Params:
   `router` - Router from make-router.
   `messages` - Vector of message maps.
   `use-case` - Keyword. :root, :sub, :extraction, :refinement, or :planning.

   Returns:
   Same as llm/chat-completion: {:content :reasoning :api-usage}"
  [router messages use-case]
  (with-provider-fallback router use-case {}
    (fn [provider model]
      (llm/chat-completion messages model
                           (:api-key provider)
                           (:base-url provider)))))

(defn routed-ask!
  "Like llm/ask! but routes across providers by use-case.

   Params:
   `router` - Router from make-router.
   `ask-opts` - Map. Same as llm/ask! but WITHOUT :config, :model, :api-key, :base-url
                (these are resolved from the provider).
   `use-case` - Keyword. :root, :sub, :extraction, :refinement, or :planning.
   `opts` - Map, optional:
     - :tools - Set of required capabilities (e.g. #{:vision}).

   Returns:
   Same as llm/ask!: {:result :tokens :cost :duration-ms}"
  ([router ask-opts use-case] (routed-ask! router ask-opts use-case {}))
  ([router ask-opts use-case opts]
   (with-provider-fallback router use-case opts
     (fn [provider model]
       (llm/ask! (assoc ask-opts
                        :model model
                        :api-key (:api-key provider)
                        :base-url (:base-url provider)))))))

(defn sanitize-config
  "Strips sensitive data (api-keys) from a config map.
   Safe to store in env or log."
  [config]
  (update config :providers
          (fn [providers]
            (mapv #(dissoc % :api-key) providers))))
