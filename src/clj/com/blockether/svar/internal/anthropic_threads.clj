(ns com.blockether.svar.internal.anthropic-threads
  "Best-effort Anthropic continuation. Canonical history stays with the session."
  (:require [charred.api :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(def ^:private beta "message-threads-2026-08-12")

(def ^:private ttl-ms 300000)

(defn- beta-values
  [headers]
  (into #{}
        (comp (filter #(= "anthropic-beta" (str/lower-case (name (key %)))))
              (mapcat #(str/split (str (val %)) #","))
              (map str/trim)
              (remove str/blank?))
        headers))

(defn- thread-headers
  [headers]
  (assoc (into {} (remove #(= "anthropic-beta" (str/lower-case (name (key %))))) headers)
    "anthropic-beta" (str/join "," (sort (conj (beta-values headers) beta)))))

(defn- fingerprint
  [body headers]
  {:fields (select-keys body
                        [:model :system :tools :tool_choice :thinking :context_management
                         :output_config :cache_control])
   :skills (get-in body [:container :skills])
   :mcp-servers (mapv #(dissoc % :authorization_token) (:mcp_servers body))
   :fallbacks? (contains? body :fallbacks)
   :betas (beta-values headers)
   :identity (hash (into {}
                         (filter #(contains? #{"authorization" "x-api-key"}
                                             (str/lower-case (name (key %)))))
                         headers))})

(defn- without-cache [value] (walk/postwalk #(if (map? %) (dissoc % :cache_control) %) value))

(defn- comparable-messages
  [messages]
  (mapv (fn [message]
          (let [content (:content message)]
            (if (and (vector? content)
                     (= 1 (count content))
                     (= "text" (:type (first content)))
                     (empty? (dissoc (first content) :type :text)))
              (assoc message :content (:text (first content)))
              message)))
        (without-cache messages)))

(defn- refused-indices
  [state messages]
  (let [messages (comparable-messages messages)]
    (into #{}
          (mapcat (fn [{:keys [prefix start]}]
                    (let [n (count prefix)]
                      (when (and (< n (count messages)) (= prefix (subvec messages 0 n)))
                        (range start (inc n))))))
          (:refused-prefixes state))))

(defn- replay-body
  [body delta-count invalid-thinking-count refused]
  (let [messages
        (vec (:messages body))

        old-count
        (max 0 (- (count messages) (long delta-count)))]

    (assoc body
      :messages (into []
                      (keep-indexed
                        (fn [i message]
                          (when-not (contains? refused i)
                            (cond-> (if (< (long i) old-count) (without-cache message) message)
                              (and (< (long i) (long invalid-thinking-count))
                                   (sequential? (:content message)))
                              (update :content
                                      #(filterv (fn [block]
                                                  (not (contains? #{"thinking" "redacted_thinking"}
                                                                  (:type block))))
                                         %))))))
                      messages))))

(defn request-plan
  "Plan a create or a delta request. Free request fields do not reset a thread."
  [state body headers delta-count reset? now]
  (let [headers
        (thread-headers headers)

        fingerprint
        (fingerprint body headers)

        prior
        (:fingerprint state)

        changed-prefix?
        (and prior
             (not= (select-keys (:fields prior) [:system :tools])
                   (select-keys (:fields fingerprint) [:system :tools])))

        invalid-thinking-count
        (if (or reset? changed-prefix?) (count (:messages body)) (:invalid-thinking-count state 0))

        full
        (replay-body (dissoc body :thread)
                     delta-count
                     invalid-thinking-count
                     (if reset? #{} (refused-indices state (vec (:messages body)))))

        continue?
        (and (not (:disabled? state))
             (not reset?)
             (:cursor state)
             (= prior fingerprint)
             (< (- (long now) (long (:completed-at state 0))) (long ttl-ms)))]

    {:fingerprint fingerprint
     :invalid-thinking-count invalid-thinking-count
     :headers headers
     :full full
     :continuation? (boolean continue?)
     :body (if continue?
             (-> full
                 (dissoc :system :tools)
                 (assoc :messages (vec (take-last delta-count (:messages full)))
                        :thread {:type "continue" :previous_message_id (:cursor state)}))
             (cond-> full
               (not (:disabled? state))
               (assoc :thread {:type "create"})))}))

(defn- error-code
  [error]
  (let [body (:body (ex-data error))]
    (try (get-in (if (string? body) (json/read-json body :key-fn keyword) body)
                 [:error :details :error_code])
         (catch Exception _ nil))))

(defn- response-info
  [result]
  (let [wire (get-in result [:http-response :parsed])]
    (if wire
      {:id (get wire "id")
       :stop-reason (get wire "stop_reason")
       :fallback? (boolean (some #(or (get % "fallback_message")
                                      (= "fallback_message" (get % "type")))
                                 (get-in wire ["usage" "iterations"])))}
      (select-keys (:provider-state result) [:id :stop-reason :fallback?]))))

(defn validate-turn!
  "Reject empty completed turns and new input while the server turn is paused."
  [state ^long delta-count ^long message-count]
  (when (or (and (= "pause_turn" (:last-stop-reason state)) (pos? delta-count))
            (and (zero? delta-count)
                 (not= "pause_turn" (:last-stop-reason state))
                 (or (:completed-at state) (zero? message-count))))
    (throw (ex-info "An empty thread turn requires pause_turn; a paused turn requires empty input."
                    {:type :svar.session/invalid-input}))))

(defn discard!
  "Discard a consumed cursor and any response that the caller did not accept."
  [state]
  (swap! state dissoc :cursor :pending))

(defn accept!
  "Commit the cursor only after the complete logical response passes validation."
  [state]
  (swap! state (fn [current]
                 (merge (dissoc current :pending) (:pending current)))))

(defn complete!
  "Send a thread request. Replay protocol misses once; keep normal retries outside."
  [state body headers send! {:keys [delta-count reset?]}]
  (let [original-headers
        headers

        plan
        (request-plan @state body headers delta-count reset? (System/currentTimeMillis))

        headers
        (if (:disabled? @state) headers (:headers plan))]

    (validate-turn! (if (or reset? (not= (:fingerprint @state) (:fingerprint plan))) {} @state)
                    delta-count
                    (count (:messages body)))
    ;; A cursor is single-use, even when later response validation fails.
    (discard! state)
    (loop [request
           (:body plan)

           headers
           headers

           replayed?
           false]

      (let [outcome (try {:result (send! request headers)} (catch Exception error {:error error}))]
        (if-let [error (:error outcome)]
          (let [code (error-code error)
                continuation? (= "continue" (get-in request [:thread :type]))
                recover? (contains? #{"thread_not_found" "thread_already_continued"
                                      "thread_continue_in_flight"}
                                    code)
                disable? (or (= "thread_unsupported_request" code)
                             (and continuation? (= "thread_not_found" code) (:replayed? @state)))]

            (cond (and (:thread request) disable?) (do
                                                     (swap! state assoc :disabled? true :cursor nil)
                                                     (recur (:full plan) original-headers true))
                  (and continuation? recover? (not replayed?))
                  (do (swap! state assoc :cursor nil)
                      (recur (assoc (:full plan) :thread {:type "create"}) headers true))
                  :else (do (swap! state assoc :cursor nil) (throw error))))
          (let [result (:result outcome)
                info (response-info result)
                cursor (when (and (:thread request)
                                  (not (:fallback? info))
                                  (not= "refusal" (:stop-reason info)))
                         (:id info))]

            (swap! state assoc
              :pending
              (cond-> {:cursor cursor
                       :fingerprint (:fingerprint plan)
                       :invalid-thinking-count (:invalid-thinking-count plan)
                       :completed-at (System/currentTimeMillis)
                       :replayed? replayed?
                       :last-stop-reason (:stop-reason info)
                       :refused-prefixes (if reset? [] (:refused-prefixes @state))}
                (= "refusal" (:stop-reason info))
                (assoc :refused-prefixes
                  (conj (if reset? [] (vec (:refused-prefixes @state)))
                        {:prefix (comparable-messages (vec (:messages body)))
                         :start (max 0 (- (count (:messages body)) (long delta-count)))}))))
            (swap! state update
              (if (= "continue" (get-in request [:thread :type])) :delta-requests :full-requests)
              (fnil inc 0))
            result))))))

(defn status
  [state closed?]
  (merge {:mode (if (:disabled? state) :http :anthropic-threads)
          :closed? closed?
          :continuation? (boolean (:cursor state))}
         (select-keys state [:delta-requests :full-requests :history-resets :model-resets])))
