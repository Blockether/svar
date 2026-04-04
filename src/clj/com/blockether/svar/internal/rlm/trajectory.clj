(ns com.blockether.svar.internal.rlm.trajectory
  "Trajectory collection, filtering, and export for RLM fine-tuning.
   Per Zhang et al. (2025): 1,000 filtered trajectories can improve
   a small model by 28.3% on long-context tasks.

   Each iteration snapshot captures the EXACT messages sent to the LLM
   and the parsed response — no reconstruction drift."
  (:require
   [charred.api :as json]
   [clojure.edn :as edn]
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

(defn list-iterations
  "Lists iteration snapshots for a trajectory env-id, sorted by index."
  [{:keys [conn]} env-id]
  (when conn
    (let [db (d/db conn)]
      (->> (d/q '[:find [(pull ?e [*]) ...]
                  :in $ ?env-id
                  :where [?e :iteration/env-id ?env-id]]
             db env-id)
        (sort-by :iteration/index)))))

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
  [{:keys [conn] :as db-info} env-id max-iterations]
  (when conn
    (let [iterations (list-iterations db-info env-id)
          ;; Collect all code from iteration responses
          all-code (->> iterations
                     (mapcat (fn [it]
                               (let [resp (try (edn/read-string (:iteration/response it)) (catch Exception _ nil))]
                                 (or (:code resp) []))))
                     (str/join "\n"))
          iter-count (count iterations)
          error-count (count (filter #(nil? (:iteration/response %)) iterations))
          score (atom 0)]
      (when (re-find #"\(def\s+" all-code) (swap! score + 2))
      (when (re-find #"\((?:llm-query|rlm-query)\s" all-code) (swap! score + 3))
      (when (re-find #"\(llm-query-batch\s" all-code) (swap! score + 2))
      (when (re-find #"\((?:search-document-pages|search-document-toc|search-document-entities)\s" all-code) (swap! score + 1))
      (when (and (pos? max-iterations) (< iter-count (/ max-iterations 2))) (swap! score + 2))
      (when (> error-count 2) (swap! score - 2))
      ;; Check final answer length
      (when-let [last-iter (last iterations)]
        (let [resp (try (edn/read-string (:iteration/response last-iter)) (catch Exception _ nil))
              answer (get-in resp [:final :answer])]
          (when (and answer (< (count answer) 20))
            (swap! score - 1))))
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
                          (filter #(if-let [es (:trajectory/eval-score %)]
                                     (>= es min-eval-score)
                                     true)))
          scored (->> hard-filtered
                   (map (fn [t]
                          (let [base-score (score-trajectory db-info (:trajectory/env-id t) max-iterations)
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
  "Reconstructs fine-tuning conversation from iteration snapshots.

   Each iteration becomes a pair: [input-messages, assistant-response].
   The input-messages are the EXACT messages sent to the LLM.
   The response is the ITERATION_SPEC JSON the model produced.

   Returns vector of iteration maps sorted by index."
  [{:keys [conn] :as db-info} env-id]
  (when conn
    (let [iterations (list-iterations db-info env-id)]
      (mapv (fn [it]
              {:index (:iteration/index it)
               :input-messages (try (edn/read-string (:iteration/input-messages it)) (catch Exception _ []))
               :response (try (edn/read-string (:iteration/response it)) (catch Exception _ nil))
               :code (try (edn/read-string (:iteration/code it)) (catch Exception _ []))
               :results (try (edn/read-string (:iteration/results it)) (catch Exception _ []))
               :final (:iteration/final it)
               :thinking (:iteration/thinking it)
               :duration-ms (:iteration/duration-ms it)})
        iterations))))

(defn- format-for-training
  "Converts iteration snapshots to OpenAI messages format for fine-tuning.

   Each iteration becomes: input-messages + assistant response.
   This exactly matches what the LLM saw and produced at inference time."
  [iterations]
  (->> iterations
    (mapcat (fn [{:keys [input-messages response]}]
              (let [;; Input messages as-is (system + user)
                    input (mapv (fn [{:keys [role content]}]
                                  {"role" role "content" content})
                            input-messages)
                    ;; Assistant response as ITERATION_SPEC JSON
                    assistant {"role" "assistant"
                               "content" (if response
                                           (json/write-json-str response)
                                           "")}]
                (conj input assistant))))
    vec))

(defn export-trajectories!
  "Exports filtered trajectories as JSONL for fine-tuning.

   Each line is a JSON object with 'messages' array in OpenAI format.
   Messages are the exact LLM input/output from iteration snapshots.

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
                          (when-let [iters (seq (reconstruct-conversation db-info (:trajectory/env-id t)))]
                            {:trajectory t
                             :messages (format-for-training iters)})))
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
