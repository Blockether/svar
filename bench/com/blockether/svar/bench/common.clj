(ns com.blockether.svar.bench.common
  "Shared utilities for svar benchmarks.

   Provides: parallel batch runner, Pi agent execution,
   result persistence, progress printing, and constants."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.svar.core :as svar]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.rlm.trajectory :as trajectory]
   [datalevin.core :as d]
   [taoensso.trove :as trove])
  (:import
   (java.nio.file Files)
   (java.time Instant)
   (java.util.concurrent TimeUnit)))

;; =============================================================================
;; Constants
;; =============================================================================

(def problem-timeout-ms 600000)  ;; 10 minutes per problem
(def pi-timeout-ms 300000)       ;; 5 minutes for pi agent
(def ^:dynamic parallelism 4)    ;; 4 problems at once, overridable via --parallel

(def trajectories-root "bench/trajectories")

(defn- sanitize-path-segment [s]
  (str/replace (str s) #"[^a-zA-Z0-9_-]" "-"))

(defn trajectory-edn-path
  "Returns the final EDN file path where a task's trajectory will be written.
   Layout: bench/trajectories/{bench}/{model}/{run-ts}/{task-id}.edn"
  [bench model run-ts task-id]
  (let [dir (str trajectories-root "/" bench "/"
              (sanitize-path-segment model) "/"
              (sanitize-path-segment run-ts))]
    (.mkdirs (io/file dir))
    (str dir "/" (sanitize-path-segment task-id) ".edn")))

(defn trajectory-temp-db-path
  "Returns a fresh temp directory path for a task's Datalevin DB.
   The caller is responsible for deleting it after extracting the trajectory."
  [task-id]
  (let [tmp-root (System/getProperty "java.io.tmpdir")
        dir (str tmp-root "/svar-bench-db-"
              (sanitize-path-segment task-id) "-"
              (System/nanoTime))]
    (.mkdirs (io/file dir))
    dir))

(defn- strip-db-refs
  "Removes :db/id keys and replaces {:db/id N} ref maps with nil.
   These are Datalevin internals meaningless outside the DB."
  [m]
  (cond
    (map? m) (into {}
               (keep (fn [[k v]]
                       (when-not (= :db/id k)
                         [k (strip-db-refs v)])))
               m)
    (vector? m) (mapv strip-db-refs m)
    :else m))

(defn- pull-conversation
  "Pulls full conversation entity from db-info, given a ref (eid or {:db/id N})."
  [{:keys [conn]} conv-ref]
  (when (and conn conv-ref)
    (let [eid (if (map? conv-ref) (:db/id conv-ref) conv-ref)]
      (when eid
        (-> (d/pull (d/db conn) '[*] eid)
          (dissoc :db/id))))))

(defn persist-trajectory!
  "Extracts all queries and iteration snapshots from an RLM env's DB and
   writes them to a fully denormalized EDN file. Conversation data (system-prompt,
   model, env-id) is inlined into each query. All :db/id refs stripped."
  [env edn-path]
  (when-let [db-info-atom (:db-info-atom env)]
    (when-let [db-info @db-info-atom]
      (let [queries (trajectory/list-queries db-info)]
        (when (seq queries)
          (let [enriched (mapv (fn [q]
                                 (let [conv (pull-conversation db-info (:query/conversation q))
                                       iters (vec (trajectory/list-iterations db-info [:query/id (:query/id q)]))]
                                   (-> q
                                     (dissoc :query/conversation)
                                     (assoc :conversation conv
                                            :iterations (mapv #(dissoc % :iteration/query) iters))
                                     strip-db-refs)))
                           queries)]
            (spit edn-path (pr-str enriched))
            edn-path))))))

(defn cleanup-temp-db!
  "Recursively deletes a temp DB directory. Safe to call on missing paths."
  [db-path]
  (when (and db-path (fs/exists? db-path))
    (fs/delete-tree db-path)))

(def ^:private DEFAULT_QUERY_ENV_OPTS
  {:max-iterations 20 :debug? true})

(defn run-query-env-task!
  "High-level wrapper that runs a single task through svar/query-env! with full
   trajectory plumbing: temp Datalevin DB per task, trajectory persisted as EDN,
   DB cleaned up afterwards. Removes the boilerplate from every benchmark.

   Params map:
     :bench     String - benchmark name (used in trajectory path)
     :router    Router instance
     :task      The task/problem map
     :model     Model name string
     :run-ts    Run timestamp string (shared across tasks in a single run)
     :task-id   String - unique per task, used in trajectory + temp DB paths
     :prompt-fn (task) -> String - builds the user prompt from the task
     :score-fn  (task result duration-ms) -> result-map - scores the outcome
                and shapes the final per-task record
     :query-opts Map, optional - extra opts merged into query-env! call
                 (e.g. :system-prompt for bench-specific instructions)

   Returns whatever score-fn returns."
  [{:keys [bench router task model run-ts task-id prompt-fn score-fn query-opts]}]
  (let [edn-path (trajectory-edn-path bench model run-ts task-id)
        db-path  (trajectory-temp-db-path task-id)
        env      (svar/create-env router {:path db-path})
        start    (System/currentTimeMillis)]
    (try
      (let [result   (svar/query-env! env (prompt-fn task)
                       (merge DEFAULT_QUERY_ENV_OPTS {:model model} query-opts))
            duration (- (System/currentTimeMillis) start)]
        (persist-trajectory! env edn-path)
        (score-fn task result duration))
      (finally
        (svar/dispose-env! env)
        (cleanup-temp-db! db-path)))))

;; =============================================================================
;; Code fence stripping
;; =============================================================================

(defn strip-code-fence
  "Extracts code from markdown output. Tries in order:
   1. Last fenced code block (```...```)
   2. Last inline backtick (`...`)
   3. Full string as-is if no markdown found."
  [s]
  (let [trimmed (str/trim (str s))
        ;; Try fenced code blocks — take the last one
        fenced (re-seq #"(?s)```\w*\s*(.*?)\s*```" trimmed)
        ;; Try inline backticks — take the last one
        inline (re-seq #"`([^`]+)`" trimmed)]
    (cond
      (seq fenced) (str/trim (second (last fenced)))
      (seq inline) (str/trim (second (last inline)))
      :else        trimmed)))

;; =============================================================================
;; Pi agent execution
;; =============================================================================

(defn run-pi!
  "Runs Pi coding agent in print mode with a prompt.
   Returns {:output :duration-ms :timed-out?}."
  [prompt model]
  (let [start  (System/currentTimeMillis)
        args   (cond-> ["pi" "-p" "--no-session"]
                 model (into ["--model" model])
                 true  (conj prompt))
        pb     (ProcessBuilder. (into-array String args))
        _      (.redirectErrorStream pb true)
        _      (.redirectInput pb (java.lang.ProcessBuilder$Redirect/from (io/file "/dev/null")))
        proc   (.start pb)
        output-future (future (slurp (.getInputStream proc)))
        finished? (.waitFor proc pi-timeout-ms TimeUnit/MILLISECONDS)
        duration  (- (System/currentTimeMillis) start)]
    (if (not finished?)
      (do (.destroyForcibly proc)
          (future-cancel output-future)
          {:output nil :duration-ms duration :timed-out? true})
      {:output      (deref output-future 5000 "")
       :duration-ms duration
       :timed-out?  false})))

(defn run-pi-local!
  "Runs a single-shot LLM call via router (for local providers like lmstudio).
   Same interface as run-pi! — returns {:output :duration-ms :timed-out?}."
  [prompt router]
  (let [start (System/currentTimeMillis)]
    (try
      (let [result (llm/routed-chat-completion router
                     [{:role "system" :content "You are a Clojure coding assistant. Return ONLY the answer expression, no markdown, no explanation."}
                      {:role "user" :content prompt}]
                     {})]
        {:output      (or (:content result) "")
         :duration-ms (- (System/currentTimeMillis) start)
         :timed-out?  false})
      (catch Exception e
        (trove/log! {:level :warn :id ::pi-local-error
                     :data {:error (ex-message e)}
                     :msg "Pi-local LLM call failed"})
        {:output nil :duration-ms (- (System/currentTimeMillis) start) :timed-out? true}))))

;; =============================================================================
;; Results persistence
;; =============================================================================

(defn save-results!
  "Saves benchmark results to EDNL file. Returns filename."
  [bench agent-name model results]
  (.mkdirs (io/file "bench/results"))
  (let [ts         (.toString (Instant/now))
        ts-safe    (str/replace ts ":" "-")
        model-safe (str/replace model "/" "-")
        agent-safe (name agent-name)
        run-id     (str bench "-" agent-safe "-" model-safe "-" ts-safe)
        filename   (str "bench/results/bench-" run-id ".ednl")
        base       {:bench bench :mode agent-name :model model :run-id run-id}
        result-lines (map #(merge base {:type :result} %) results)
        content    (str/join "\n" (map pr-str result-lines))]
    (spit filename content)
    (trove/log! {:level :info :id ::bench-saved :data {:file filename} :msg "Benchmark results saved"})
    filename))

;; =============================================================================
;; Progress printing
;; =============================================================================

(defn print-progress
  [done total correct errors agent-name avg-iters avg-ms]
  (let [pct      (if (pos? done) (/ (* 100.0 correct) done) 0.0)
        err-str  (if (pos? errors) (format " | errors: %d" errors) "")
        iter-str (if avg-iters (format " | avg-iter: %.1f" (double avg-iters)) "")]
    (println (format "[%d/%d] [%s] pass@1: %d (%.1f%%)%s%s | avg-ms: %d"
               done total (name agent-name) correct pct err-str iter-str (long avg-ms)))))

;; =============================================================================
;; Python task helpers
;; =============================================================================

(defn ensure-dir!
  [path]
  (.mkdirs (io/file path)))

(defn download-if-missing!
  [url dest-path]
  (let [f (io/file dest-path)]
    (if (.exists f)
      dest-path
      (do
        (ensure-dir! (.getParent f))
        (with-open [in (io/input-stream url)
                    out (io/output-stream f)]
          (io/copy in out))
        dest-path))))

(defn strip-fence
  "Extracts Python code from LLM output with multi-block aware selection.
   Pi/RLM outputs often contain multiple fenced blocks (test output + final
   code). Strategy:
     1. Prefer the LAST ```python/py tagged block (final answer).
     2. Fall back to the LAST untagged ``` block.
     3. Fall back to raw input."
  [raw]
  (let [s (str/trim (str raw))
        tagged (re-seq #"(?s)```(?:python|py)\s*(.*?)\s*```" s)
        untagged (re-seq #"(?s)```\s*(.*?)\s*```" s)]
    (cond
      (seq tagged)   (str/trim (second (last tagged)))
      (seq untagged) (str/trim (second (last untagged)))
      :else          s)))

(defn run-python-script!
  [script timeout-ms]
  (let [tmp (Files/createTempFile "svar-bench-" ".py" (make-array java.nio.file.attribute.FileAttribute 0))
        path (.toFile tmp)]
    (spit path script)
    (try
      (let [pb (ProcessBuilder. (into-array String ["python3" "-I" (.getAbsolutePath path)]))
            _ (.redirectErrorStream pb true)
            proc (.start pb)
            output-future (future (slurp (.getInputStream proc)))
            finished? (.waitFor proc timeout-ms TimeUnit/MILLISECONDS)
            output (if finished? (deref output-future 5000 "") "")]
        (if finished?
          {:ok? (zero? (.exitValue proc))
           :exit-code (.exitValue proc)
           :output output
           :timeout? false}
          (do
            (.destroyForcibly proc)
            (future-cancel output-future)
            {:ok? false
             :exit-code nil
             :output "Timed out"
             :timeout? true})))
      (finally
        (.delete path)))))

;; =============================================================================
;; Parallel batch runner
;; =============================================================================

(defn run-parallel-bench!
  "Generic parallel benchmark runner.

   Args:
     bench-name  - benchmark name for results file
     agent-name  - :query-env or :pi
     model       - model name
     items       - vector of items to evaluate
     total-ds    - total dataset size (for display)
     eval-fn     - (fn [item] -> {:correct? :answer :duration-ms ...})
     result-fn   - (fn [idx item eval-result] -> result-record map)

   Returns unified benchmark result map."
  [bench-name agent-name model items total-ds eval-fn result-fn]
  (let [total-q (count items)
        state   (atom {:correct 0 :incorrect 0 :errors 0
                       :results []
                       :total-duration-ms 0
                       :total-input-tokens 0 :total-output-tokens 0
                       :total-cost 0.0
                       :total-iterations 0
                       :done 0})]

    (println (format "\nRunning %s [agent=%s model=%s items=%d parallel=%d]\n"
               bench-name (name agent-name) model total-q parallelism))

    (doseq [batch (partition-all parallelism (map-indexed vector items))]
      (let [futures (mapv
                      (fn [[idx item]]
                        (future
                          (let [eval-result (try
                                             (eval-fn item)
                                             (catch Throwable e
                                               (trove/log! {:level :warn :id ::bench-error
                                                            :data {:idx idx
                                                                   :item (select-keys item [:id :title :task_id :instance_id])
                                                                   :error (ex-message e)}
                                                            :msg "Evaluation failed"})
                                               {:error (ex-message e) :correct? false :duration-ms 0}))]
                            [(inc idx) item eval-result])))
                      batch)]
        (doseq [[f [idx orig-item]] (map vector futures batch)]
          (let [[q-num item eval-result] (or (deref f problem-timeout-ms nil)
                                             (do (trove/log! {:level :warn :id ::task-timeout
                                                              :data {:idx idx :item (select-keys orig-item [:id :title :task_id :instance_id])}
                                                              :msg "Task timed out (10 min)"})
                                                 [(inc idx) orig-item {:error "Task timeout (10 min)" :correct? false :duration-ms 0}]))
                correct?   (boolean (:correct? eval-result))
                error?     (boolean (:error eval-result))
                result-rec (result-fn q-num item eval-result)]

            (swap! state
              (fn [s]
                (-> s
                  (update :done inc)
                  (update :correct + (if (and correct? (not error?)) 1 0))
                  (update :incorrect + (if (and (not correct?) (not error?)) 1 0))
                  (update :errors + (if error? 1 0))
                  (update :results conj result-rec)
                  (update :total-duration-ms + (or (:duration-ms eval-result) 0))
                  (update :total-input-tokens + (or (get-in eval-result [:tokens :input]) 0))
                  (update :total-output-tokens + (or (get-in eval-result [:tokens :output]) 0))
                  (update :total-cost + (or (get-in eval-result [:cost :total-cost]) 0.0))
                  (update :total-iterations + (or (:iterations eval-result) 0)))))))

        ;; Print progress after each batch
        (let [s         @state
              done      (:done s)
              avg-ms    (if (pos? done) (/ (double (:total-duration-ms s)) done) 0.0)
              avg-iters (if (and (= agent-name :query-env) (pos? done))
                          (/ (double (:total-iterations s)) done)
                          nil)]
          (print-progress done total-q (:correct s) (:errors s) agent-name avg-iters avg-ms)
          (flush))))

    ;; Build unified result
    (let [s         @state
          n         (max 1 total-q)
          avg-dur   (/ (double (:total-duration-ms s)) n)
          avg-toks  {:input  (/ (double (:total-input-tokens s)) n)
                     :output (/ (double (:total-output-tokens s)) n)}
          accuracy  (if (pos? total-q) (/ (double (:correct s)) total-q) 0.0)
          avg-iters (if (= agent-name :query-env)
                      (/ (double (:total-iterations s)) n)
                      nil)
          saved     (save-results! bench-name agent-name model (:results s))]
      {:bench            bench-name
       :mode             agent-name
       :model            model
       :total-questions  total-q
       :total-dataset    total-ds
       :correct          (:correct s)
       :incorrect        (:incorrect s)
       :errors           (:errors s)
       :accuracy         accuracy
       :avg-duration-ms  avg-dur
       :avg-iterations   avg-iters
       :avg-tokens       avg-toks
       :total-cost       (:total-cost s)
       :results          (:results s)
       :saved-to         saved})))
