(ns com.blockether.svar.bench.mbpp
  "MBPP benchmark harness for svar.

   Dataset/source:
   Google Research MBPP (Mostly Basic Python Problems)
   https://github.com/google-research/google-research/tree/master/mbpp

   Usage:
     clojure -M:bench -- --bench mbpp --mode ask --model gpt-4o"
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.svar.bench.python-tasks :as py]
   [com.blockether.svar.core :as svar]
   [taoensso.trove :as trove])
  (:import
   (java.time Instant)))

(def ^:private dataset-url
  "https://raw.githubusercontent.com/google-research/google-research/master/mbpp/mbpp.jsonl")

(def ^:private dataset-path
  "bench/data/mbpp/mbpp.jsonl")

(def ^:private CODE_SPEC
  (svar/spec
    (svar/field svar/NAME :code
      svar/TYPE svar/TYPE_STRING
      svar/CARDINALITY svar/CARDINALITY_ONE
      svar/DESCRIPTION "Python code implementing the requested function(s).")))

(defn- ensure-dataset!
  []
  (py/download-if-missing! dataset-url dataset-path))

(defn- load-dataset
  []
  (ensure-dataset!)
  (with-open [rdr (io/reader dataset-path)]
    (mapv (fn [line] (json/read-json line :key-fn keyword)) (line-seq rdr))))

(defn- build-task-prompt
  [task]
  (let [tests (:test_list task)
        tests-preview (take 5 tests)]
    (str
      "Solve this MBPP Python task. Return ONLY Python code, no markdown.\n\n"
      "Task ID: " (:task_id task) "\n"
      "Problem:\n" (:text task) "\n\n"
      "Public tests:\n"
      (str/join "\n" tests-preview))))

(defn- eval-task-ask!
  [router task model timeout-ms include-challenge-tests?]
  (let [ask-result (svar/ask! router {:spec CODE_SPEC
                                      :messages [(svar/system "You write correct Python code. Return only code, no markdown.")
                                                 (svar/user (build-task-prompt task))]
                                      :model model})
        code (py/strip-fence (get-in ask-result [:result :code]))
        tests (if include-challenge-tests?
                (vec (concat (:test_list task) (:challenge_test_list task)))
                (vec (:test_list task)))
        test-setup (:test_setup_code task)
        script (str code "\n\n"
                 test-setup "\n"
                 (str/join "\n" tests)
                 "\n")
        run (py/run-python-script! script timeout-ms)]
    {:correct? (:ok? run)
     :failure (if (:ok? run) nil (:output run))
     :timeout? (:timeout? run)
     :code code
     :tokens (:tokens ask-result)
     :cost (:cost ask-result)
     :duration-ms (:duration-ms ask-result)}))

(defn- ensure-results-dir! []
  (py/ensure-dir! "bench/results"))

(defn- save-results!
  [bench mode model results summary]
  (ensure-results-dir!)
  (let [ts (.toString (Instant/now))
        ts-safe (str/replace ts ":" "-")
        model-safe (str/replace model "/" "-")
        run-id (str bench "-" (name mode) "-" model-safe "-" ts-safe)
        filename (str "bench/results/bench-" run-id ".ednl")
        _summary summary
        base {:bench bench :mode mode :model model :run-id run-id}
        result-lines (map #(merge base {:type :result} %) results)
        content (str/join "\n" (map pr-str result-lines))]
    (spit filename content)
    (trove/log! {:level :info :id ::bench-saved :data {:file filename} :msg "Benchmark results saved"})
    filename))

(defn- print-progress
  [done total correct errors avg-ms]
  (let [pct (if (pos? done) (/ (* 100.0 correct) done) 0.0)
        err-str (if (pos? errors) (format " | errors: %d" errors) "")]
    (println (format "[%d/%d] pass@1: %d (%.1f%%)%s | avg-ms: %d"
               done total correct pct err-str (long avg-ms)))))

(defn run-benchmark!
  "Runs MBPP benchmark.

   opts:
     :mode      - only :ask supported
     :model     - model name
     :limit     - max tasks to run (default all)
     :offset    - skip first N tasks
     :timeout-ms - python test timeout per task (default 8000)
     :include-challenge-tests? - include challenge_test_list (default false)
     :router    - required router"
  [opts]
  (let [mode (get opts :mode :ask)
        model (get opts :model "gpt-4o")
        router (if-let [r (:router opts)]
                 r
                 (throw (ex-info "Missing :router in benchmark opts"
                          {:type :bench/missing-router
                           :hint "Construct router in bench runner and pass via opts"})))
        _mode-check (if (= mode :ask)
                      :ok
                      (throw (ex-info "mbpp supports only --mode ask" {:mode mode})))
        timeout-ms (get opts :timeout-ms 8000)
        include-challenge-tests? (boolean (get opts :include-challenge-tests? false))
        offset (get opts :offset 0)
        limit (get opts :limit nil)
        _ (trove/log! {:level :info :id ::bench-start
                       :data {:mode mode :model model :offset offset :limit limit :timeout-ms timeout-ms :include-challenge-tests? include-challenge-tests?}
                       :msg "Starting MBPP benchmark"})
        dataset (load-dataset)
        total-ds (count dataset)
        slice (cond->> (drop offset dataset)
                limit (take limit))
        tasks (vec slice)
        total-q (count tasks)
        state (atom {:correct 0 :incorrect 0 :errors 0
                     :results []
                     :total-duration-ms 0
                     :total-input-tokens 0 :total-output-tokens 0
                     :total-cost 0.0})]

    (println (format "\nRunning MBPP [mode=%s model=%s tasks=%d offset=%d]\n"
               (name mode) model total-q offset))

    (doseq [[idx task] (map-indexed vector tasks)]
      (let [q-num (inc idx)
            eval-result (try
                          (eval-task-ask! router task model timeout-ms include-challenge-tests?)
                          (catch Exception e
                            (trove/log! {:level :warn :id ::bench-error
                                         :data {:q-num q-num :error (ex-message e)}
                                         :msg "Task evaluation failed"})
                            {:error (ex-message e) :correct? false :duration-ms 0}))
            correct? (boolean (:correct? eval-result))
            error? (boolean (:error eval-result))
            result-rec {:task-num q-num
                        :task-id (:task_id task)
                        :correct? correct?
                        :timeout? (:timeout? eval-result)
                        :code (:code eval-result)
                        :failure (:failure eval-result)
                        :error (:error eval-result)
                        :tokens (:tokens eval-result)
                        :cost (:cost eval-result)
                        :duration-ms (:duration-ms eval-result)}]

        (swap! state
          (fn [s]
            (-> s
              (update :correct + (if (and correct? (not error?)) 1 0))
              (update :incorrect + (if (and (not correct?) (not error?)) 1 0))
              (update :errors + (if error? 1 0))
              (update :results conj result-rec)
              (update :total-duration-ms + (or (:duration-ms eval-result) 0))
              (update :total-input-tokens + (or (get-in eval-result [:tokens :input]) 0))
              (update :total-output-tokens + (or (get-in eval-result [:tokens :output]) 0))
              (update :total-cost + (or (get-in eval-result [:cost :total-cost]) 0.0)))))

        (if (or (= q-num 1) (zero? (mod q-num 10)) (= q-num total-q))
          (let [s @state
                done q-num
                avg-ms (/ (double (:total-duration-ms s)) done)]
            (print-progress done total-q (:correct s) (:errors s) avg-ms)
            (flush))
          nil)))

    (let [s @state
          n (max 1 total-q)
          avg-dur (/ (double (:total-duration-ms s)) n)
          avg-toks {:input (/ (double (:total-input-tokens s)) n)
                    :output (/ (double (:total-output-tokens s)) n)}
          accuracy (if (pos? total-q) (/ (double (:correct s)) total-q) 0.0)
          saved (save-results! "mbpp" mode model (:results s) nil)]
      {:bench "mbpp"
       :mode mode
       :model model
       :total-questions total-q
       :total-dataset total-ds
       :correct (:correct s)
       :incorrect (:incorrect s)
       :errors (:errors s)
       :accuracy accuracy
       :avg-duration-ms avg-dur
       :avg-iterations nil
       :avg-tokens avg-toks
       :total-cost (:total-cost s)
       :results (:results s)
       :saved-to saved})))
