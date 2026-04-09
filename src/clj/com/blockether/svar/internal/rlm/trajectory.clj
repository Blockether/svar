(ns com.blockether.svar.internal.rlm.trajectory
  "Trajectory collection, filtering, and export for RLM fine-tuning.
   Per Zhang et al. (2025): 1,000 filtered trajectories can improve
   a small model by 28.3% on long-context tasks.

   Hierarchy: conversation → query → iteration
   Each iteration snapshot captures the EXACT response from the LLM."
  (:require
   [charred.api :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [datalevin.core :as d]
   [taoensso.trove :as trove])
  (:import
   [java.io BufferedWriter FileWriter]))

(defn- entity-order-key
  [entity]
  [(:entity/created-at entity) (:entity/id entity)])

(defn list-queries
  "Lists query entities from the database.
   opts:
     :status - Filter by status keyword (e.g., :success)
     :limit  - Max results (default: all)
     :min-iterations - Minimum iteration count (default: 0)"
  [{:keys [conn]} & [{:keys [status limit min-iterations] :or {min-iterations 0}}]]
  (when conn
    (let [all (d/q '[:find [(pull ?e [*]) ...]
                     :where [?e :entity/type :query]]
                (d/db conn))
          filtered (->> all
                      (filter #(>= (or (:query/iterations %) 0) min-iterations))
                      (filter #(if status (= (:query/status %) status) true))
                      (sort-by entity-order-key)
                      reverse)]
      (if limit (take limit filtered) filtered))))

(defn list-iterations
  "Lists iteration entities for a query via parent-id, sorted by created-at."
  [{:keys [conn]} query-ref]
  (when conn
    (let [db (d/db conn)
          query-id (cond
                     (vector? query-ref) (second query-ref)
                     (uuid? query-ref) query-ref
                     :else nil)]
      (when query-id
        (->> (d/q '[:find [(pull ?e [*]) ...]
                    :in $ ?parent-id
                    :where [?e :entity/type :iteration]
                    [?e :entity/parent-id ?parent-id]]
               db query-id)
          (sort-by entity-order-key))))))

(defn score-query
  "Scores a query trajectory for training quality.
   Higher score = better training example.

   Scoring signals:
   +2 — Used (def ...) for variable storage
   +3 — Used llm-query or rlm-query (teaches recursion)
   +2 — Used llm-query-batch (teaches parallel fanout)
   +1 — Used search tools (teaches document navigation)
   +2 — Low iteration count relative to budget (efficient strategy)
   -2 — Had consecutive errors > 2 (noisy trace)
   -1 — Very short answer (< 20 chars, likely trivial)"
  [{:keys [conn] :as db-info} query-ref max-iterations]
  (when conn
    (let [iterations (list-iterations db-info query-ref)
          all-code (->> iterations
                     (mapcat (fn [it]
                               (try (edn/read-string (or (:iteration/code it) "[]"))
                                    (catch Exception _ []))))
                     (str/join "\n"))
          iter-count (count iterations)
          error-count (count (filter #(nil? (:iteration/code %)) iterations))
          score (atom 0)]
      (when (re-find #"\(def\s+" all-code) (swap! score + 2))
      (when (re-find #"\((?:llm-query|rlm-query)\s" all-code) (swap! score + 3))
      (when (re-find #"\(llm-query-batch\s" all-code) (swap! score + 2))
      (when (re-find #"\((?:search-documents|fetch-content)\s" all-code) (swap! score + 1))
      (when (and (pos? max-iterations) (< iter-count (/ max-iterations 2))) (swap! score + 2))
      (when (> error-count 2) (swap! score - 2))
      (when-let [last-iter (last iterations)]
        (when (and (:iteration/answer last-iter) (< (count (:iteration/answer last-iter)) 20))
          (swap! score - 1)))
      @score)))

(defn filter-queries
  "Filters and scores queries for training export.

   Hard filters:
   - status = :success
   - iterations >= min-iterations (default 2)
   - iterations <= max-iterations * max-iteration-ratio (default 0.5)
   - eval-score >= min-eval-score when available (default 0.6)

   Returns scored queries sorted by score descending."
  [{:keys [conn] :as db-info} & [{:keys [min-iterations max-iteration-ratio min-score min-eval-score
                                         limit max-iterations]
                                  :or {min-iterations 2 max-iteration-ratio 0.5 min-score 2
                                       min-eval-score 0.6 limit 1000 max-iterations 50}}]]
  (when conn
    (let [queries (list-queries db-info {:status :success :min-iterations min-iterations})
          hard-filtered (->> queries
                          (filter #(<= (:query/iterations %)
                                     (* max-iterations max-iteration-ratio)))
                          (filter #(if-let [es (:query/eval-score %)]
                                     (>= es min-eval-score)
                                     true)))
          scored (->> hard-filtered
                   (map (fn [q]
                          (let [base-score (score-query db-info [:entity/id (:entity/id q)] max-iterations)
                                eval-bonus (if-let [es (:query/eval-score q)]
                                             (cond (>= es 0.8) 3
                                                   (>= es 0.6) 1
                                                   :else 0)
                                             0)]
                            (assoc q :query/score (+ base-score eval-bonus)))))
                   (filter #(>= (:query/score %) min-score))
                   (sort-by :query/score >))]
      (if limit (take limit scored) scored))))

(defn reconstruct-conversation
  "Reconstructs fine-tuning data from iteration snapshots for a query.

   Returns vector of iteration maps sorted by created-at, each with:
   :code, :results, :thinking, :duration-ms, and optionally :answer."
  [{:keys [conn] :as db-info} query-ref]
  (when conn
    (let [iterations (list-iterations db-info query-ref)]
      (mapv (fn [it]
              (cond-> {:code (try (edn/read-string (:iteration/code it)) (catch Exception _ []))
                       :results (try (edn/read-string (:iteration/results it)) (catch Exception _ []))
                       :duration-ms (:iteration/duration-ms it)}
                (:iteration/answer it) (assoc :answer (:iteration/answer it))
                (seq (:iteration/thinking it)) (assoc :thinking (:iteration/thinking it))))
        iterations))))

(defn- format-for-training
  "Converts iteration snapshots to OpenAI messages format for fine-tuning.

   Each iteration becomes an assistant message with thinking + code + answer."
  [query-text system-prompt iterations]
  (let [base [{"role" "system" "content" (or system-prompt "")}
              {"role" "user" "content" (or query-text "")}]]
    (->> iterations
      (reduce (fn [msgs {:keys [thinking code answer]}]
                (let [content (cond-> {}
                                (seq thinking) (assoc :thinking thinking)
                                (seq code) (assoc :code code)
                                answer (assoc :answer answer))]
                  (conj msgs {"role" "assistant"
                              "content" (json/write-json-str content)})))
        base)
      vec)))

(defn export-trajectories!
  "Exports filtered query trajectories as JSONL for fine-tuning.

   Each line is a JSON object with 'messages' array in OpenAI format.

   Params:
   - db-info: Database connection map
   - output-dir: Directory path for output files
   - opts:
     - :val-split - Fraction for validation (default 0.1)
     - :filter-opts - Options passed to filter-queries
     - :shuffle? - Shuffle before split (default true)

   Writes:
   - {output-dir}/train.jsonl
   - {output-dir}/val.jsonl
   - {output-dir}/metadata.edn"
  [{:keys [conn] :as db-info} output-dir & [{:keys [val-split filter-opts shuffle?]
                                             :or {val-split 0.1 shuffle? true}}]]
  (when-not conn
    (throw (ex-info "No database connection" {:type :trajectory/no-conn})))
  (let [queries (filter-queries db-info filter-opts)
        _ (when (empty? queries)
            (trove/log! {:level :warn :msg "No queries passed filtering"})
            (throw (ex-info "No queries to export" {:type :trajectory/empty})))
        ;; Look up conversation (parent of query) for system-prompt
        get-system-prompt (fn [q]
                            (when-let [parent-id (:entity/parent-id q)]
                              (let [conv (d/pull (d/db conn) '[:conversation/system-prompt] [:entity/id parent-id])]
                                (:conversation/system-prompt conv))))
        exports (->> queries
                  (keep (fn [q]
                          (let [qref [:entity/id (:entity/id q)]
                                iters (seq (reconstruct-conversation db-info qref))]
                            (when iters
                              {:query q
                               :messages (format-for-training (:query/text q) (get-system-prompt q) iters)}))))
                  vec)
        _ (when (empty? exports)
            (trove/log! {:level :warn :msg "No reconstructable queries to export"})
            (throw (ex-info "No reconstructable queries" {:type :trajectory/no-conversation})))
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
        metadata {:total-queries (count queries)
                  :exported (count exports)
                  :train-count (count train-set)
                  :val-count (count val-set)
                  :avg-score (when (seq exports)
                               (double (/ (reduce + (map #(get-in % [:query :query/score]) exports))
                                         (count exports))))
                  :avg-iterations (when (seq exports)
                                    (double (/ (reduce + (map #(get-in % [:query :query/iterations]) exports))
                                              (count exports))))
                  :timestamp (java.util.Date.)}]
    (spit (str output-dir "/metadata.edn") (pr-str metadata))
    (trove/log! {:level :info :data metadata :msg "Trajectories exported"})
    metadata))
