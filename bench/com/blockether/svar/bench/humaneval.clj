(ns com.blockether.svar.bench.humaneval
  "HumanEval benchmark — 164 Python coding tasks.

   Agents: :query-env (svar RLM) | :pi (Pi coding agent)
   Verification: python3 subprocess.

   Usage:
     clojure -M:bench -- --bench humaneval --limit 20 --model gpt-4o
     clojure -M:bench -- --bench humaneval --agent pi --model blockether/glm-5-turbo"
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.svar.bench.bench-common :as common]
   [com.blockether.svar.bench.python-tasks :as py]
   [com.blockether.svar.core :as svar]
   [taoensso.trove :as trove])
  (:import
   (java.util.zip GZIPInputStream)))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private dataset-url
  "https://raw.githubusercontent.com/openai/human-eval/master/data/HumanEval.jsonl.gz")
(def ^:private dataset-path "bench/data/humaneval/HumanEval.jsonl.gz")
(def ^:private python-timeout-ms 30000)

;; =============================================================================
;; Dataset
;; =============================================================================

(defn- ensure-dataset! []
  (py/download-if-missing! dataset-url dataset-path))

(defn- load-dataset []
  (ensure-dataset!)
  (with-open [in (io/input-stream dataset-path)
              gz (GZIPInputStream. in)
              rdr (io/reader gz)]
    (mapv (fn [line] (json/read-json line :key-fn keyword)) (line-seq rdr))))

;; =============================================================================
;; Python verification
;; =============================================================================

(defn- verify-python [code task]
  (let [script (str code "\n\n" (:test task) "\ncheck(" (:entry_point task) ")\n")
        run    (py/run-python-script! script python-timeout-ms)]
    {:correct? (:ok? run)
     :failure  (if (:ok? run) nil (:output run))
     :timeout? (:timeout? run)}))

;; =============================================================================
;; Prompt
;; =============================================================================

(defn- build-prompt [task]
  (str "Complete this Python function. "
    "Return ONLY valid Python code, no markdown, no explanation, no code fences.\n\n"
    "Task: " (:task_id task) "\n"
    "Entry point: " (:entry_point task) "\n\n"
    (:prompt task)))

;; =============================================================================
;; Agent eval functions
;; =============================================================================

(defn- eval-query-env! [router task model]
  (let [env   (svar/create-env router {})
        start (System/currentTimeMillis)]
    (try
      (let [result (svar/query-env! env (build-prompt task) {:model model :max-iterations 20 :debug? true})
            code   (py/strip-fence (str/trim (str (:answer result))))
            score  (verify-python code task)]
        {:correct?    (:correct? score)
         :failure     (:failure score)
         :timeout?    (:timeout? score)
         :code        code
         :iterations  (:iterations result)
         :tokens      (:tokens result)
         :cost        (:cost result)
         :duration-ms (- (System/currentTimeMillis) start)})
      (finally
        (svar/dispose-env! env)))))

(defn- eval-pi! [task model]
  (let [pi-result (common/run-pi! (build-prompt task) model)]
    (if (:timed-out? pi-result)
      {:correct? false :failure "pi timed out" :duration-ms (:duration-ms pi-result)}
      (let [code  (py/strip-fence (:output pi-result))
            score (verify-python code task)]
        {:correct?    (:correct? score)
         :failure     (:failure score)
         :timeout?    (:timeout? score)
         :code        code
         :duration-ms (:duration-ms pi-result)}))))

;; =============================================================================
;; Result record builder
;; =============================================================================

(defn- make-result-rec [q-num task eval-result]
  {:task-num    q-num
   :task-id     (:task_id task)
   :entry-point (:entry_point task)
   :correct?    (boolean (:correct? eval-result))
   :timeout?    (:timeout? eval-result)
   :code        (:code eval-result)
   :failure     (:failure eval-result)
   :error       (:error eval-result)
   :tokens      (:tokens eval-result)
   :cost        (:cost eval-result)
   :duration-ms (:duration-ms eval-result)
   :iterations  (:iterations eval-result)})

;; =============================================================================
;; Main runner
;; =============================================================================

(defn run-benchmark!
  "Runs HumanEval benchmark.
   opts: :agent :model :limit :offset :router"
  [opts]
  (let [agent-name (get opts :agent :query-env)
        model      (get opts :model "gpt-4o")
        router     (:router opts)
        offset     (get opts :offset 0)
        limit      (get opts :limit nil)

        _ (if (and (= agent-name :query-env) (nil? router))
            (throw (ex-info "Missing :router for query-env agent" {:type :bench/missing-router}))
            nil)

        _ (trove/log! {:level :info :id ::bench-start
                       :data {:agent agent-name :model model :offset offset :limit limit}
                       :msg "Starting HumanEval benchmark"})

        dataset (load-dataset)
        total-ds (count dataset)
        tasks   (vec (cond->> (drop offset dataset) limit (take limit)))

        eval-fn (case agent-name
                  :query-env (fn [task] (eval-query-env! router task model))
                  :pi        (fn [task] (eval-pi! task model)))]

    (common/run-parallel-bench! "humaneval" agent-name model tasks total-ds eval-fn make-result-rec)))
