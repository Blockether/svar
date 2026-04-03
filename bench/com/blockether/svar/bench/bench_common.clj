(ns com.blockether.svar.bench.bench-common
  "Shared utilities for svar benchmarks.

   Provides: parallel batch runner, Pi agent execution,
   result persistence, progress printing, and constants."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [taoensso.trove :as trove])
  (:import
   (java.time Instant)
   (java.util.concurrent TimeUnit)))

;; =============================================================================
;; Constants
;; =============================================================================

(def problem-timeout-ms 300000)  ;; 5 minutes per problem
(def pi-timeout-ms 300000)       ;; 5 minutes for pi agent
(def parallelism 4)              ;; 4 problems at once

;; =============================================================================
;; Code fence stripping
;; =============================================================================

(defn strip-code-fence
  "Strips markdown code fences and backticks from a string."
  [s]
  (let [trimmed (str/trim (str s))]
    (if-let [m (re-find #"(?s)^```\w*\s*(.*?)\s*```$" trimmed)]
      (str/trim (second m))
      (str/replace trimmed #"^`|`$" ""))))

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
                                                            :data {:idx idx :error (ex-message e)}
                                                            :msg "Evaluation failed"})
                                               {:error (ex-message e) :correct? false :duration-ms 0}))]
                            [(inc idx) item eval-result])))
                      batch)]
        (doseq [f futures]
          (let [[q-num item eval-result] (deref f problem-timeout-ms
                                           [0 nil {:error "Batch timeout" :correct? false :duration-ms 0}])
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
