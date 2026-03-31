(ns com.blockether.svar.bench.gsm8k
  "GSM8K benchmark harness for svar.

   Evaluates two modes:
     :ask       - Direct structured prompting via svar/ask!
     :query-env - Full RLM with code execution via svar/query-env!

   Usage:
     clojure -M:bench
     clojure -M:bench -- --mode ask --limit 50 --model gpt-4o"
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.svar.core :as svar]
   [taoensso.trove :as trove])
  (:import
   (java.time Instant)))

;; =============================================================================
;; Dataset loading
;; =============================================================================

(def ^:private dataset-path
  "bench/data/gsm8k-test.jsonl")

(defn- load-dataset
  "Loads GSM8K test JSONL. Returns vector of {:question :answer} maps."
  []
  (with-open [rdr (io/reader dataset-path)]
    (mapv (fn [line]
            (let [obj (json/read-json line :key-fn keyword)]
              {:question (:question obj)
               :answer   (:answer obj)}))
      (line-seq rdr))))

;; =============================================================================
;; Answer parsing
;; =============================================================================

(defn parse-gold-answer
  "Extracts the numeric answer after '####' from a GSM8K gold answer string.
   Strips commas. Returns a number or nil."
  [answer-str]
  (when (string? answer-str)
    (when-let [m (re-find #"####\s*(-?[\d,]+\.?\d*)" answer-str)]
      (let [num-str (-> (second m)
                        (str/replace "," ""))]
        (try
          (if (str/includes? num-str ".")
            (Double/parseDouble num-str)
            (Long/parseLong num-str))
          (catch Exception _ nil))))))

(defn parse-number-from-answer
  "Extracts a number from a free-form answer value.
   Handles: numbers, strings with $, commas, trailing punctuation."
  [v]
  (cond
    (number? v) v
    (nil? v)    nil
    :else
    (let [s (-> (str v)
                (str/replace "$" "")
                (str/replace "," "")
                str/trim)]
      ;; Try last number in the string
      (when-let [m (last (re-seq #"-?[\d]+\.?\d*" s))]
        (try
          (if (str/includes? m ".")
            (Double/parseDouble m)
            (Long/parseLong m))
          (catch Exception _ nil))))))

(defn answers-match?
  "Returns true if predicted and gold answers are equal.
   Integers compared as longs; decimals compared with tolerance 1e-6."
  [predicted gold]
  (and (some? predicted)
       (some? gold)
       (let [p (if (integer? predicted) (long predicted) (double predicted))
             g (if (integer? gold) (long gold) (double gold))]
         (if (and (integer? predicted) (integer? gold))
           (= (long predicted) (long gold))
           (< (Math/abs (- (double p) (double g))) 1e-6)))))

;; =============================================================================
;; Evaluation modes
;; =============================================================================

(defn eval-ask!
  "Evaluates a single GSM8K question via svar/ask! (direct structured prompting).
   Returns {:answer :tokens :cost :duration-ms}."
  [question model]
  (let [spec (svar/spec
               (svar/field svar/NAME :answer
                 svar/TYPE svar/TYPE_INT
                 svar/CARDINALITY svar/CARDINALITY_ONE
                 svar/DESCRIPTION "The numeric answer to the math problem"))
        result (svar/ask! {:spec     spec
                           :messages [(svar/system "Solve this math problem step by step. Return the final numeric answer.")
                                      (svar/user question)]
                           :model    model})]
    {:answer      (get-in result [:result :answer])
     :tokens      (:tokens result)
     :cost        (:cost result)
     :duration-ms (:duration-ms result)}))

(defn eval-query-env!
  "Evaluates a single GSM8K question via svar/query-env! (full RLM with code execution).
   Returns {:answer :iterations :tokens :cost :duration-ms}."
  [question model]
  (let [config (svar/make-config {:model model})
        env    (svar/create-env {:config config})
        start  (System/currentTimeMillis)
        result (svar/query-env! env question {:max-iterations 10})]
    (svar/dispose-env! env)
    {:answer      (parse-number-from-answer (:answer result))
     :iterations  (:iterations result)
     :tokens      (:tokens result)
     :cost        (:cost result)
     :duration-ms (- (System/currentTimeMillis) start)}))

;; =============================================================================
;; Results persistence
;; =============================================================================

(defn- ensure-results-dir! []
  (.mkdirs (io/file "bench/results")))

(defn- save-results!
  "Saves benchmark results to bench/results/gsm8k-{mode}-{model}-{ts}.edn"
  [mode model results summary]
  (ensure-results-dir!)
  (let [ts       (.toString (Instant/now))
        ts-safe  (str/replace ts ":" "-")
        model-safe (str/replace model "/" "-")
        filename (str "bench/results/gsm8k-" (name mode) "-" model-safe "-" ts-safe ".edn")
        data     {:mode mode :model model :summary summary :results results}]
    (spit filename (pr-str data))
    (trove/log! {:level :info :id ::bench-saved :data {:file filename} :msg "Benchmark results saved"})
    filename))

;; =============================================================================
;; Progress / summary printing
;; =============================================================================

(defn- print-progress
  [done total correct errors mode avg-iters avg-ms]
  (let [pct (if (pos? done) (/ (* 100.0 correct) done) 0.0)
        iter-str (when (= mode :query-env) (format " | avg-iter: %.1f" (double avg-iters)))]
    (println (format "[%d/%d] correct: %d (%.1f%%)%s | avg-ms: %d"
               done total correct pct (or iter-str "") (long avg-ms)))))

(defn- print-summary
  [{:keys [mode model total-questions total-dataset correct incorrect errors
           avg-duration-ms avg-tokens total-cost]}]
  (let [pct (if (pos? total-questions) (/ (* 100.0 correct) total-questions) 0.0)]
    (println)
    (println "GSM8K Benchmark Results")
    (println "=======================")
    (println (format "Mode:      %s" (case mode :ask "ask! (direct)" :query-env "query-env! (RLM)" (name mode))))
    (println (format "Model:     %s" model))
    (println (format "Questions: %d/%d" total-questions total-dataset))
    (println (format "Correct:   %d (%.1f%%)" correct pct))
    (println (format "Incorrect: %d" incorrect))
    (println (format "Errors:    %d" errors))
    (println (format "Avg duration: %.1fs" (/ avg-duration-ms 1000.0)))
    (when avg-tokens
      (println (format "Avg tokens: {input: %d, output: %d}"
                 (long (:input avg-tokens 0))
                 (long (:output avg-tokens 0)))))
    (when total-cost
      (println (format "Total cost: $%.4f" (double (:total-cost total-cost 0.0)))))))

;; =============================================================================
;; Main runner
;; =============================================================================

(defn run-benchmark!
  "Runs GSM8K benchmark.

   opts:
     :mode      - :ask or :query-env (default :ask)
     :model     - model name (default from env / router)
     :limit     - max questions to run (default all 1319)
     :offset    - skip first N questions (default 0)
     :parallel  - number of parallel workers (default 1, sequential)"
  [opts]
  (let [mode      (get opts :mode :ask)
        model     (get opts :model "gpt-4o")
        offset    (get opts :offset 0)
        limit     (get opts :limit nil)
        _parallel (get opts :parallel 1) ;; reserved for future use

        _        (trove/log! {:level :info :id ::bench-start
                               :data {:mode mode :model model :offset offset :limit limit}
                               :msg "Starting GSM8K benchmark"})

        dataset  (load-dataset)
        total-ds (count dataset)
        slice    (cond->> (drop offset dataset)
                   limit (take limit))
        questions (vec slice)
        total-q   (count questions)

        eval-fn  (case mode
                   :ask       eval-ask!
                   :query-env eval-query-env!
                   (throw (ex-info (str "Unknown mode: " mode) {:mode mode})))

        ;; Accumulator state
        state    (atom {:correct 0 :incorrect 0 :errors 0
                        :results []
                        :total-duration-ms 0
                        :total-input-tokens 0 :total-output-tokens 0
                        :total-cost 0.0
                        :total-iterations 0})]

    (println (format "\nRunning GSM8K [mode=%s model=%s questions=%d offset=%d]\n"
               (name mode) model total-q offset))

    (doseq [[idx item] (map-indexed vector questions)]
      (let [q-num      (inc idx)
            question   (:question item)
            gold       (parse-gold-answer (:answer item))
            eval-result (try
                          (eval-fn question model)
                          (catch Exception e
                            (trove/log! {:level :warn :id ::bench-error
                                          :data {:q-num q-num :error (ex-message e)}
                                          :msg "Question evaluation failed"})
                            {:error (ex-message e) :answer nil :duration-ms 0}))
            predicted  (:answer eval-result)
            correct?   (answers-match? predicted gold)
            error?     (boolean (:error eval-result))
            result-rec {:q-num      q-num
                        :question   question
                        :gold       gold
                        :predicted  predicted
                        :correct?   correct?
                        :error      (:error eval-result)
                        :tokens     (:tokens eval-result)
                        :cost       (:cost eval-result)
                        :duration-ms (:duration-ms eval-result)
                        :iterations (:iterations eval-result)}]

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
                (update :total-cost + (or (get-in eval-result [:cost :total-cost]) 0.0))
                (update :total-iterations + (or (:iterations eval-result) 0)))))

        ;; Print progress every 10 questions
        (when (or (zero? (mod q-num 10)) (= q-num total-q))
          (let [s          @state
                done       q-num
                avg-ms     (/ (double (:total-duration-ms s)) done)
                avg-iters  (if (pos? done) (/ (double (:total-iterations s)) done) 0.0)]
            (print-progress done total-q (:correct s) (:errors s) mode avg-iters avg-ms)))))

    ;; Build final summary
    (let [s         @state
          n         (max 1 total-q)
          avg-dur   (/ (double (:total-duration-ms s)) n)
          avg-tokens {:input  (/ (double (:total-input-tokens s)) n)
                      :output (/ (double (:total-output-tokens s)) n)}
          total-cost {:total-cost (:total-cost s)}
          summary   {:mode             mode
                     :model            model
                     :total-questions  total-q
                     :total-dataset    total-ds
                     :correct          (:correct s)
                     :incorrect        (:incorrect s)
                     :errors           (:errors s)
                     :avg-duration-ms  avg-dur
                     :avg-tokens       avg-tokens
                     :total-cost       total-cost}]

      (print-summary summary)

      (let [saved (save-results! mode model (:results s) summary)]
        (println (format "\nResults saved to: %s" saved)))

      summary)))

;; =============================================================================
;; CLI entry point
;; =============================================================================

(defn -main
  "Entry point for clojure -M:bench.
   Parses --mode, --model, --limit, --offset from args."
  [& args]
  (let [arg-map (loop [remaining (vec args) acc {}]
                  (if (< (count remaining) 2)
                    acc
                    (let [k (first remaining)
                          v (second remaining)]
                      (cond
                        (= k "--mode")   (recur (drop 2 remaining) (assoc acc :mode (keyword v)))
                        (= k "--model")  (recur (drop 2 remaining) (assoc acc :model v))
                        (= k "--limit")  (recur (drop 2 remaining) (assoc acc :limit (Long/parseLong v)))
                        (= k "--offset") (recur (drop 2 remaining) (assoc acc :offset (Long/parseLong v)))
                        :else            (recur (rest remaining) acc)))))
        opts (cond-> {:mode :ask :model "gpt-4o"}
               (:mode arg-map)   (assoc :mode (:mode arg-map))
               (:model arg-map)  (assoc :model (:model arg-map))
               (:limit arg-map)  (assoc :limit (:limit arg-map))
               (:offset arg-map) (assoc :offset (:offset arg-map)))]
    (run-benchmark! opts)
    (System/exit 0)))
