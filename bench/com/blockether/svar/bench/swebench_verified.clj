(ns com.blockether.svar.bench.swebench-verified
  "SWE-bench Verified benchmark - 500 human-verified GitHub issues.

   Uses the official SWE-bench harness for evaluation (requires Docker +
   `pip install swebench`). Workflow:
   1. Agent generates a unified diff patch per task in parallel
   2. Patches written to predictions.jsonl
   3. Shell out to `python -m swebench.harness.run_evaluation`
   4. Parse the harness report and mark each task resolved/unresolved

   Without Docker/swebench: predictions are still saved, but :correct?
   will be nil. Run the harness manually on the saved predictions.jsonl.

   Agents: :query-env (svar RLM) | :pi (Pi coding agent)
   Usage: clojure -M:bench -- --bench swebench-verified --agent query-env ..."
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.svar.bench.common :as common]
   [taoensso.trove :as trove])
  (:import
   (java.io BufferedReader InputStreamReader)))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private dataset-url
  "https://datasets-server.huggingface.co/rows?dataset=princeton-nlp%2FSWE-bench_Verified&config=default&split=test&offset=%d&length=100")
(def ^:private dataset-path "bench/data/swebench_verified/swe_bench_verified.json")
(def ^:private dataset-pages 5)
(def ^:private predictions-root "bench/swebench-harness")
(def ^:private harness-max-workers 4)

;; =============================================================================
;; Dataset
;; =============================================================================

(defn- fetch-page! [offset]
  (let [url (format dataset-url offset)]
    (trove/log! {:level :info :id ::fetch-page
                 :data {:offset offset}
                 :msg "Fetching SWE-bench Verified page"})
    (with-open [in (io/input-stream url)]
      (slurp in))))

(defn- download-dataset! []
  (.mkdirs (io/file "bench/data/swebench_verified"))
  (let [rows (vec
               (mapcat
                 (fn [page]
                   (let [offset (* page 100)
                         raw    (fetch-page! offset)
                         parsed (json/read-json raw :key-fn keyword)]
                     (map :row (:rows parsed))))
                 (range dataset-pages)))]
    (spit dataset-path (json/write-json-str rows))
    (trove/log! {:level :info :id ::dataset-downloaded
                 :data {:count (count rows)}
                 :msg "SWE-bench Verified dataset downloaded"})
    rows))

(defn- ensure-dataset! []
  (let [f (io/file dataset-path)]
    (if (.exists f)
      nil
      (download-dataset!))))

(defn- load-dataset []
  (ensure-dataset!)
  (json/read-json (slurp dataset-path) :key-fn keyword))

;; =============================================================================
;; Prompt
;; =============================================================================

(defn- build-prompt [task]
  (str "You are solving a real GitHub issue from " (:repo task) ".\n\n"
    "PROBLEM STATEMENT:\n" (:problem_statement task) "\n\n"
    (when-let [hints (:hints_text task)]
      (when-not (str/blank? hints) (str "HINTS FROM MAINTAINERS:\n" hints "\n\n")))
    "Your task: produce a unified diff patch that fixes the issue.\n"
    "The patch must be in standard git diff format starting with 'diff --git'.\n"
    "DO NOT write any files to disk. DO NOT modify files directly. "
    "Return ONLY the unified diff as your final answer.\n"))

;; =============================================================================
;; Patch extraction
;; =============================================================================

(defn- extract-patch
  "Extracts a unified diff from LLM output. Tries in order:
   1. Fenced block with 'diff' or 'patch' language tag
   2. Any fenced block containing 'diff --git'
   3. Substring from first 'diff --git' to end of string
   4. Raw input if it starts with 'diff --git'
   Returns empty string if no valid patch found."
  [raw]
  (if (or (nil? raw) (str/blank? raw))
    ""
    (let [s (str/trim raw)]
      (or
        ;; 1. Fenced block tagged diff or patch
        (when-let [m (re-find #"(?s)```(?:diff|patch)\s*\n(.*?)```" s)]
          (let [block (str/trim (second m))]
            (when (str/includes? block "diff --git") block)))
        ;; 2. Any fenced block containing diff --git
        (when-let [m (re-find #"(?s)```[^\n]*\n(.*?)```" s)]
          (let [block (str/trim (second m))]
            (when (str/includes? block "diff --git") block)))
        ;; 3. Substring from first diff --git
        (when-let [idx (str/index-of s "diff --git")]
          (str/trim (subs s idx)))
        ;; 4. Raw if already a patch
        (when (str/starts-with? s "diff --git") s)
        ""))))

;; =============================================================================
;; Phase 1: Collect predictions
;; =============================================================================

(defn- collect-query-env-prediction! [router task model run-ts]
  (common/run-query-env-task!
    {:bench     "swebench-verified"
     :router    router
     :task      task
     :model     model
     :run-ts    run-ts
     :task-id   (or (:instance_id task) (str (hash task)))
     :prompt-fn build-prompt
     :score-fn  (fn [_t result duration]
                  (let [raw-answer (str/trim (str (:answer result)))
                        patch      (extract-patch raw-answer)]
                    (when (str/blank? patch)
                      (trove/log! {:level :warn :id ::empty-patch
                                   :data {:instance-id (:instance_id _t)}
                                   :msg "Agent produced no valid patch"}))
                    {:model-patch (:model-patch result patch)
                     :answer      raw-answer
                     :iterations  (:iterations result)
                     :tokens      (:tokens result)
                     :cost        (:cost result)
                     :duration-ms duration}))}))

(defn- collect-pi-prediction! [task model]
  (let [pi-result (common/run-pi! (build-prompt task) model)]
    (if (:timed-out? pi-result)
      {:model-patch ""
       :answer      nil
       :duration-ms (:duration-ms pi-result)}
      (let [raw-answer (str/trim (str (:output pi-result)))
            patch      (extract-patch raw-answer)]
        (when (str/blank? patch)
          (trove/log! {:level :warn :id ::empty-patch
                       :data {:instance-id (:instance_id task)}
                       :msg "Pi agent produced no valid patch"}))
        {:model-patch patch
         :answer      raw-answer
         :duration-ms (:duration-ms pi-result)}))))

(defn- collect-predictions!
  "Runs all tasks in parallel and returns a vector of prediction maps.
   Each map: {:instance-id :model-patch :answer :iterations :tokens :cost :duration-ms}"
  [agent-name tasks router model run-ts]
  (let [total-q (count tasks)
        pi-model (str "blockether/" model)
        state   (atom {:done 0 :total-duration-ms 0 :total-iterations 0 :results []})

        collect-fn (case agent-name
                     :query-env (fn [task]
                                  (collect-query-env-prediction! router task model run-ts))
                     :pi        (fn [task]
                                  (collect-pi-prediction! task pi-model)))]

    (println (format "\nPhase 1: Collecting predictions [agent=%s model=%s tasks=%d parallel=%d]\n"
               (name agent-name) model total-q common/parallelism))

    (doseq [batch (partition-all common/parallelism (map-indexed vector tasks))]
      (let [futures (mapv
                      (fn [[idx task]]
                        (future
                          (let [pred (try
                                       (collect-fn task)
                                       (catch Throwable e
                                         (trove/log! {:level :warn :id ::collect-error
                                                      :data {:idx idx :error (ex-message e)}
                                                      :msg "Prediction collection failed"})
                                         {:model-patch ""
                                          :answer      nil
                                          :error       (ex-message e)
                                          :duration-ms 0}))]
                            {:instance-id (:instance_id task)
                             :task-num    (inc idx)
                             :repo        (:repo task)
                             :model-patch (or (:model-patch pred) "")
                             :answer      (:answer pred)
                             :iterations  (:iterations pred)
                             :tokens      (:tokens pred)
                             :cost        (:cost pred)
                             :duration-ms (or (:duration-ms pred) 0)
                             :error       (:error pred)})))
                      batch)]
        (doseq [f futures]
          (let [pred (deref f common/problem-timeout-ms
                       {:instance-id nil :model-patch "" :duration-ms 0
                        :error "Batch timeout"})]
            (swap! state
              (fn [s]
                (-> s
                  (update :done inc)
                  (update :total-duration-ms + (or (:duration-ms pred) 0))
                  (update :total-iterations + (or (:iterations pred) 0))
                  (update :results conj pred))))))

        (let [s    @state
              done (:done s)
              avg-ms (if (pos? done) (/ (double (:total-duration-ms s)) done) 0.0)
              avg-iters (when (= agent-name :query-env)
                          (/ (double (:total-iterations s)) (max 1 done)))]
          (common/print-progress done total-q 0 0 agent-name avg-iters avg-ms)
          (flush))))

    (:results @state)))

;; =============================================================================
;; Phase 2: Write predictions.jsonl
;; =============================================================================

(defn- sanitize-run-id [s]
  (str/replace (str s) #"[^a-zA-Z0-9_-]" "-"))

(defn- write-predictions!
  [predictions run-id model]
  (let [dir  (str predictions-root "/" run-id)
        path (str dir "/predictions.jsonl")]
    (.mkdirs (io/file dir))
    (with-open [w (io/writer path)]
      (doseq [p predictions]
        (.write w (json/write-json-str
                    {"instance_id"        (:instance-id p)
                     "model_name_or_path" model
                     "model_patch"        (or (:model-patch p) "")}))
        (.write w "\n")))
    (trove/log! {:level :info :id ::predictions-written
                 :data {:path path :count (count predictions)}
                 :msg "Predictions written"})
    path))

;; =============================================================================
;; Phase 3: Run the harness
;; =============================================================================

(defn- run-harness!
  "Shells out to python -m swebench.harness.run_evaluation.
   Returns path to the report directory, or throws with a useful error message
   if swebench is not installed or Docker is unavailable."
  [predictions-path run-id report-dir]
  (.mkdirs (io/file report-dir))
  (let [args ["python" "-m" "swebench.harness.run_evaluation"
               "--dataset_name"   "princeton-nlp/SWE-bench_Verified"
               "--predictions_path" predictions-path
               "--max_workers"    (str harness-max-workers)
               "--run_id"         run-id
               "--report_dir"     report-dir]
        pb   (ProcessBuilder. (into-array String args))
        _    (.redirectErrorStream pb true)
        proc (.start pb)]
    (println (format "\nPhase 3: Running SWE-bench harness (this may take 30-60 minutes)..."))
    (println (format "  predictions: %s" predictions-path))
    (println (format "  run_id:      %s" run-id))
    (println (format "  report_dir:  %s\n" report-dir))

    ;; Stream output line by line as it arrives
    (let [stdout-lines (atom [])
          rdr (BufferedReader. (InputStreamReader. (.getInputStream proc)))]
      (loop []
        (when-let [line (.readLine rdr)]
          (println (str "  [harness] " line))
          (swap! stdout-lines conj line)
          (recur)))

      (let [exit-code (.waitFor proc)
            stdout    (str/join "\n" @stdout-lines)]
        (when (pos? exit-code)
          (let [msg (cond
                      (or (str/includes? stdout "No module named swebench")
                        (str/includes? stdout "command not found"))
                      (str "swebench not installed. Install with:\n"
                        "  pip install swebench\n"
                        "Also requires Docker to be installed and running.")

                      (str/includes? stdout "docker")
                      "Docker does not appear to be running. Start Docker and retry."

                      :else
                      (format "Harness exited with code %d. Check output above." exit-code))]
            (throw (ex-info msg {:exit-code exit-code :stdout stdout}))))
        report-dir))))

;; =============================================================================
;; Phase 4: Parse the harness report
;; =============================================================================

(defn- find-report-json
  "Globs report-dir for *.json files and returns the first one found, or nil."
  [report-dir]
  (let [dir (io/file report-dir)
        files (when (.exists dir)
                (filter #(str/ends-with? (.getName %) ".json") (file-seq dir)))]
    (first (filter #(.isFile %) files))))

(defn- parse-report
  "Parses the harness report JSON. Returns a map of instance_id -> status keyword.
   Status is one of: :resolved :unresolved :error :empty :no-generation"
  [report-dir]
  (if-let [f (find-report-json report-dir)]
    (let [report (json/read-json (slurp f) :key-fn keyword)]
      (trove/log! {:level :info :id ::report-parsed
                   :data {:file (.getPath f)
                          :resolved  (:resolved_instances report 0)
                          :total     (:total_instances report 0)}
                   :msg "Harness report parsed"})
      (merge
        (into {} (map (fn [id] [id :resolved])      (or (:resolved_ids report) [])))
        (into {} (map (fn [id] [id :unresolved])    (or (:unresolved_ids report) [])))
        (into {} (map (fn [id] [id :error])         (or (:error_ids report) [])))
        (into {} (map (fn [id] [id :empty])         (or (:empty_patch_ids report) [])))
        (into {} (map (fn [id] [id :no-generation]) (or (:no_generation_ids report) [])))))
    (do
      (trove/log! {:level :warn :id ::report-not-found
                   :data {:report-dir report-dir}
                   :msg "WARNING: No harness report JSON found - harness may have crashed"})
      (println (format "\nWARNING: No report JSON found in %s. Harness may have crashed mid-run." report-dir))
      {})))

;; =============================================================================
;; Phase 5: Build final results
;; =============================================================================

(defn- make-result-rec
  [prediction report-map]
  (let [instance-id    (:instance-id prediction)
        harness-status (get report-map instance-id :not-evaluated)
        resolved?      (= :resolved harness-status)
        correct?       (if (= harness-status :not-evaluated) nil resolved?)]
    {:task-num       (:task-num prediction)
     :instance-id    instance-id
     :repo           (:repo prediction)
     :bench          "swebench-verified"
     :type           :result
     :correct?       correct?
     :harness-status harness-status
     :model-patch    (:model-patch prediction)
     :answer         (:answer prediction)
     :iterations     (:iterations prediction)
     :tokens         (:tokens prediction)
     :cost           (:cost prediction)
     :duration-ms    (:duration-ms prediction)
     :error          (:error prediction)}))

;; =============================================================================
;; Phase 6: Summary
;; =============================================================================

(defn- print-swebench-summary
  [agent-name model total-q predictions results report-map]
  (let [resolved    (count (filter #(= :resolved (:harness-status %)) results))
        unresolved  (count (filter #(= :unresolved (:harness-status %)) results))
        errors      (count (filter #(= :error (:harness-status %)) results))
        empty-count (count (filter #(= :empty (:harness-status %)) results))
        not-eval    (count (filter #(= :not-evaluated (:harness-status %)) results))
        total-dur   (reduce + 0 (map #(or (:duration-ms %) 0) predictions))
        avg-dur-s   (if (pos? total-q) (/ (double total-dur) total-q 1000.0) 0.0)
        total-cost  (reduce + 0.0 (map #(double (or (get-in % [:cost :total-cost]) 0.0)) predictions))
        pct         (if (pos? total-q) (* 100.0 (/ (double resolved) total-q)) 0.0)]
    (println)
    (println "swebench-verified Benchmark Results")
    (println "=======================")
    (println (format "Mode:       %s" (if (= agent-name :query-env) "query-env! (RLM)" (str agent-name))))
    (println (format "Model:      %s" model))
    (println (format "Tasks:      %d/%d" total-q total-q))
    (println (format "Resolved:   %d (%.1f%%)" resolved pct))
    (println (format "Unresolved: %d" unresolved))
    (println (format "Errors:     %d" errors))
    (println (format "Empty:      %d" empty-count))
    (when (pos? not-eval)
      (println (format "Not evaluated: %d" not-eval)))
    (println (format "Avg duration: %.1fs" avg-dur-s))
    (println (format "Total cost: $%.4f" total-cost))))

;; =============================================================================
;; Main runner
;; =============================================================================

(defn run-benchmark!
  "Runs SWE-bench Verified benchmark using the official SWE-bench harness.
   opts: :agent :model :provider :limit :offset :router :run-ts"
  [opts]
  (let [agent-name (get opts :agent :query-env)
        model      (get opts :model "gpt-4o")
        provider   (get opts :provider :blockether)
        router     (:router opts)
        offset     (get opts :offset 0)
        limit      (get opts :limit nil)
        run-ts     (or (:run-ts opts) (str (java.time.Instant/now)))
        run-id     (str "svar-" (sanitize-run-id run-ts))
        report-dir (str predictions-root "/" run-id "/report")

        _ (if (and (= agent-name :query-env) (nil? router))
            (throw (ex-info "Missing :router for query-env agent" {:type :bench/missing-router}))
            nil)

        _ (trove/log! {:level :info :id ::bench-start
                       :data {:agent agent-name :model model :offset offset :limit limit :run-id run-id}
                       :msg "Starting SWE-bench Verified benchmark"})

        dataset  (load-dataset)
        total-ds (count dataset)
        tasks    (vec (cond->> (drop offset dataset) limit (take limit)))
        total-q  (count tasks)]

    ;; Phase 1: Collect predictions
    (let [predictions (collect-predictions! agent-name tasks router model run-ts)

          ;; Phase 2: Write predictions.jsonl
          predictions-path (write-predictions! predictions run-id model)

          ;; Phases 3-4: Run harness and parse report (may throw)
          [report-map harness-ok?]
          (try
            (run-harness! predictions-path run-id report-dir)
            [(parse-report report-dir) true]
            (catch Throwable e
              (println (format "\nWARNING: Harness failed: %s" (ex-message e)))
              (println "Predictions have been saved. Run the harness manually:")
              (println (format "  python -m swebench.harness.run_evaluation \\"))
              (println (format "    --dataset_name princeton-nlp/SWE-bench_Verified \\"))
              (println (format "    --predictions_path %s \\" predictions-path))
              (println (format "    --max_workers %d \\" harness-max-workers))
              (println (format "    --run_id %s \\" run-id))
              (println (format "    --report_dir %s" report-dir))
              [{} false]))

          ;; Phase 5: Build final results
          results (mapv #(make-result-rec % report-map) predictions)

          ;; Save
          saved (common/save-results! "swebench-verified" agent-name model results)

          resolved   (count (filter #(= :resolved (:harness-status %)) results))
          unresolved (count (filter #(= :unresolved (:harness-status %)) results))
          errors-cnt (count (filter #(or (= :error (:harness-status %))
                                      (some? (:error %))) results))
          accuracy   (if (pos? total-q) (/ (double resolved) total-q) 0.0)
          total-dur  (reduce + 0 (map #(or (:duration-ms %) 0) predictions))
          avg-dur    (if (pos? total-q) (/ (double total-dur) total-q) 0.0)
          total-cost (reduce + 0.0 (map #(double (or (get-in % [:cost :total-cost]) 0.0)) predictions))
          avg-iters  (when (= agent-name :query-env)
                       (let [iters (keep :iterations predictions)]
                         (when (seq iters)
                           (/ (double (reduce + 0 iters)) (count iters)))))]

      ;; Phase 6: Print summary
      (print-swebench-summary agent-name model total-q predictions results report-map)
      (println (format "\nPredictions: %s" predictions-path))
      (println (format "Results saved to: %s" saved))

      {:bench            "swebench-verified"
       :mode             agent-name
       :model            model
       :total-questions  total-q
       :total-dataset    total-ds
       :correct          resolved
       :incorrect        unresolved
       :errors           (if harness-ok? errors-cnt total-q)
       :accuracy         accuracy
       :avg-duration-ms  avg-dur
       :avg-iterations   avg-iters
       :total-cost       total-cost
       :results          results
       :saved-to         saved
       :predictions-path predictions-path})))
