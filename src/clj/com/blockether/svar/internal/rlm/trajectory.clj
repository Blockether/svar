(ns com.blockether.svar.internal.rlm.trajectory
  "Trajectory collection, filtering, and export for RLM fine-tuning.
   Per Zhang et al. (2025): 1,000 filtered trajectories can improve
   a small model by 28.3% on long-context tasks."
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [datalevin.core :as d]
   [taoensso.trove :as trove])
  (:import
   [java.io BufferedWriter FileWriter]))

(defn list-trajectories
  "Lists trajectory records from the database.
   opts:
     :status - Filter by status keyword (e.g., :success)
     :limit  - Max results (default: all)
     :min-iterations - Minimum iteration count (default: 0)"
  [{:keys [conn]} & [{:keys [status limit min-iterations] :or {min-iterations 0}}]]
  (when conn
    (let [all (d/q '[:find [(pull ?e [*]) ...]
                     :where [?e :trajectory/id _]]
                (d/db conn))
          filtered (->> all
                     (filter #(>= (or (:trajectory/iterations %) 0) min-iterations))
                     (filter #(if status (= (:trajectory/status %) status) true))
                     (sort-by :trajectory/timestamp)
                     reverse)]
      (if limit (take limit filtered) filtered))))

(defn score-trajectory
  "Scores a trajectory for training quality.
   Higher score = better training example.

   Scoring signals:
   +2 — Used (def ...) for variable storage (teaches variable discipline)
   +3 — Used llm-query or rlm-query (teaches recursion)
   +2 — Used llm-query-batch (teaches parallel fanout)
   +1 — Used search tools (teaches document navigation)
   +2 — Low iteration count relative to budget (efficient strategy)
   -2 — Had consecutive errors > 2 (noisy trace)
   -1 — Very short answer (< 20 chars, likely trivial)"
  [{:keys [conn]} env-id max-iterations]
  (when conn
    (let [db (d/db conn)
          messages (d/q '[:find [(pull ?e [:message/content :message/role :message/iteration]) ...]
                          :in $ ?env-id
                          :where [?e :message/env-id ?env-id]]
                     db env-id)
          exec-codes (->> (d/q '[:find [(pull ?e [:execution/code]) ...]
                                 :in $ ?env-id
                                 :where
                                 [?m :message/env-id ?env-id]
                                 [?m :message/role :assistant]
                                 [?e :execution/message ?m]]
                            db env-id)
                       (map :execution/code)
                       (remove str/blank?))
          all-code (str/join "\n" exec-codes)
          iterations (count (filter #(= (:message/role %) :assistant) messages))
          score (atom 0)]
      (when (re-find #"\(def\s+" all-code) (swap! score + 2))
      (when (re-find #"\((?:llm-query|rlm-query)\s" all-code) (swap! score + 3))
      (when (re-find #"\(llm-query-batch\s" all-code) (swap! score + 2))
      (when (re-find #"\((?:search-document-pages|search-document-toc|search-document-entities)\s" all-code) (swap! score + 1))
      (when (and (pos? max-iterations) (< iterations (/ max-iterations 2))) (swap! score + 2))
      (let [error-count (->> messages
                          (filter #(= (:message/role %) :user))
                          (filter #(when-let [c (:message/content %)] (str/includes? c "<error>")))
                          count)]
        (when (> error-count 2) (swap! score - 2)))
      (let [final-msg (->> messages
                        (filter #(= (:message/role %) :assistant))
                        last)]
        (when (and final-msg (< (count (or (:message/content final-msg) "")) 20))
          (swap! score - 1)))
      @score)))

(defn filter-trajectories
  "Filters and scores trajectories for training export.

   Hard filters:
   - status = :success
   - iterations >= min-iterations (default 2)
   - iterations <= max-iterations * max-iteration-ratio (default 0.5)
   - eval-score >= min-eval-score when available (default 0.6)

   Soft scoring via score-trajectory + eval-score bonus.

   Returns scored trajectories sorted by score descending."
  [{:keys [conn] :as db-info} & [{:keys [min-iterations max-iteration-ratio min-score min-eval-score
                                         limit max-iterations]
                                  :or {min-iterations 2 max-iteration-ratio 0.5 min-score 2
                                       min-eval-score 0.6 limit 1000 max-iterations 50}}]]
  (when conn
    (let [trajectories (list-trajectories db-info {:status :success :min-iterations min-iterations})
          hard-filtered (->> trajectories
                          (filter #(<= (:trajectory/iterations %)
                                     (* max-iterations max-iteration-ratio)))
                             ;; If eval-score exists, it must pass the quality gate
                          (filter #(if-let [es (:trajectory/eval-score %)]
                                     (>= es min-eval-score)
                                     true)))
          scored (->> hard-filtered
                   (map (fn [t]
                          (let [base-score (score-trajectory db-info (:trajectory/env-id t) max-iterations)
                                   ;; Eval-score bonus: 0.8+ = +3, 0.6+ = +1
                                eval-bonus (if-let [es (:trajectory/eval-score t)]
                                             (cond (>= es 0.8) 3
                                                   (>= es 0.6) 1
                                                   :else 0)
                                             0)]
                            (assoc t :trajectory/score (+ base-score eval-bonus)))))
                   (filter #(>= (:trajectory/score %) min-score))
                   (sort-by :trajectory/score >))]
      (if limit (take limit scored) scored))))

(defn reconstruct-conversation
  "Reconstructs the full conversation for a trajectory from DB entities.

   Returns a vector of message maps in chronological order.
   Assistant messages are decomposed into :thinking and :code — matching
   the exact JSON format the model produces at inference time."
  [{:keys [conn]} env-id]
  (when conn
    (let [db (d/db conn)
          messages (->> (d/q '[:find [(pull ?e [* {:execution/_message [:execution/code :execution/order]}]) ...]
                               :in $ ?env-id
                               :where [?e :message/env-id ?env-id]]
                          db env-id)
                     (sort-by (juxt :message/iteration :message/timestamp)))
          conversation (mapv (fn [msg]
                               (let [role (:message/role msg)
                                     content (:message/content msg)]
                                 (if (= role :assistant)
                                   (let [executions (->> (:execution/_message msg)
                                                      (sort-by :execution/order)
                                                      (mapv :execution/code)
                                                      (remove str/blank?)
                                                      vec)
                                         thinking (or (:message/thinking msg) "")]
                                     {:role :assistant
                                      :thinking thinking
                                      :code executions})
                                   {:role role :content content})))
                         messages)]
      conversation)))

(defn- format-for-training
  "Converts a reconstructed conversation to OpenAI messages format for fine-tuning.

   Assistant content is JSON matching the ITERATION_SPEC format:
   {\"thinking\": \"...\", \"code\": [...], \"next-optimize\": null, \"final\": null}
   This ensures training data matches inference-time behavior exactly."
  [conversation]
  (mapv (fn [{:keys [role content thinking code]}]
          (case role
            :system    {"role" "system" "content" content}
            :user      {"role" "user" "content" content}
            :assistant {"role" "assistant"
                        "content" (json/write-json-str
                                    (cond-> {"thinking" (or thinking "") "code" (or code [])}
                                      ;; Don't include next-optimize/final in training — let model learn when to use them
                                      ))}))
    conversation))

(defn export-trajectories!
  "Exports filtered trajectories as JSONL for fine-tuning.

   Each line is a JSON object with 'messages' array in OpenAI format.
   Assistant messages use SUMMARY + CODE structure.

   Params:
   - db-info: Database connection map
   - output-dir: Directory path for output files
   - opts:
     - :val-split - Fraction for validation (default 0.1)
     - :filter-opts - Options passed to filter-trajectories
     - :shuffle? - Shuffle before split (default true)

   Writes:
   - {output-dir}/train.jsonl
   - {output-dir}/val.jsonl
   - {output-dir}/metadata.edn (stats about the export)"
  [{:keys [conn] :as db-info} output-dir & [{:keys [val-split filter-opts shuffle?]
                                             :or {val-split 0.1 shuffle? true}}]]
  (when-not conn
    (throw (ex-info "No database connection" {:type :trajectory/no-conn})))
  (let [trajectories (filter-trajectories db-info filter-opts)
        _ (when (empty? trajectories)
            (trove/log! {:level :warn :msg "No trajectories passed filtering"})
            (throw (ex-info "No trajectories to export" {:type :trajectory/empty})))
        exports (->> trajectories
                  (keep (fn [t]
                          (when-let [conv (seq (reconstruct-conversation db-info (:trajectory/env-id t)))]
                            {:trajectory t
                             :messages (format-for-training conv)})))
                  vec)
        _ (when (empty? exports)
            (trove/log! {:level :warn :msg "No reconstructable trajectories to export"})
            (throw (ex-info "No reconstructable trajectories to export" {:type :trajectory/no-conversation})))
        exports (if shuffle? (shuffle exports) exports)
        val-count (min (count exports) (max 1 (int (* (count exports) val-split))))
        val-set (take val-count exports)
        train-set (drop val-count exports)
        _ (io/make-parents (str output-dir "/train.jsonl"))
        write-jsonl! (fn [path items]
                       (with-open [w (BufferedWriter. (FileWriter. path))]
                         (doseq [{:keys [messages]} items]
                           (.write w (json/write-json-str {"messages" messages}))
                           (.write w "\n"))))
        _ (write-jsonl! (str output-dir "/train.jsonl") train-set)
        _ (write-jsonl! (str output-dir "/val.jsonl") val-set)
        metadata {:total-trajectories (count trajectories)
                  :exported (count exports)
                  :train-count (count train-set)
                  :val-count (count val-set)
                  :avg-score (when (seq exports)
                               (double (/ (reduce + (map #(get-in % [:trajectory :trajectory/score]) exports))
                                         (count exports))))
                  :avg-iterations (when (seq exports)
                                    (double (/ (reduce + (map #(get-in % [:trajectory :trajectory/iterations]) exports))
                                              (count exports))))
                  :models (distinct (map #(get-in % [:trajectory :trajectory/model]) exports))
                  :timestamp (java.util.Date.)}]
    (spit (str output-dir "/metadata.edn") (pr-str metadata))
    (trove/log! {:level :info :data metadata :msg "Trajectories exported"})
    metadata))
