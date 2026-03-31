(ns com.blockether.svar.bench.runner
  "Unified benchmark runner for svar.

   Usage:
     clojure -M:bench -- --bench gsm8k --mode ask --limit 50
     clojure -M:bench -- --bench gsm8k --mode query-env --model claude-opus-4-6
     clojure -M:bench -- --bench all --limit 50
     clojure -M:bench -- --list

   Every benchmark's run-fn MUST return a unified result map:
     {:bench           \"gsm8k\"
      :mode            :ask | :query-env
      :model           \"gpt-4o\"
      :total-questions 50
      :total-dataset   1319
      :correct         45
      :incorrect       3
      :errors          2
      :accuracy        0.9
      :avg-duration-ms 1200.0
      :avg-iterations  nil | 2.3
      :avg-tokens      {:input 120 :output 30}
      :total-cost      0.42
      :results         [{:q-num 1 :question \"...\" :gold 18 :predicted 18
                         :correct? true :error nil :tokens {...} :cost {...}
                         :duration-ms 1234 :iterations nil}]
      :saved-to        \"bench/results/gsm8k-ask-gpt-4o-2026-03-31.edn\"}"
  (:require
   [com.blockether.svar.bench.gsm8k :as gsm8k]))

;; =============================================================================
;; Registry
;; =============================================================================

(def ^:private BENCHMARKS
  [{:name        "gsm8k"
    :description "Grade School Math 8K — 1319 math word problems (Cobbe et al., 2021)"
    :tests       "Code execution accuracy, iteration efficiency, cost"
    :run-fn      gsm8k/run-benchmark!}])

(defn- find-bench [name]
  (first (filter #(= name (:name %)) BENCHMARKS)))

;; =============================================================================
;; Unified summary printer
;; =============================================================================

(defn print-summary
  "Prints a unified summary from any benchmark result map."
  [{:keys [bench mode model total-questions total-dataset correct incorrect errors
           accuracy avg-duration-ms avg-iterations avg-tokens total-cost]}]
  (println)
  (println (format "%s Benchmark Results" (or bench "?")))
  (println "=======================")
  (println (format "Mode:       %s" (case mode :ask "ask! (direct)" :query-env "query-env! (RLM)" (str mode))))
  (println (format "Model:      %s" model))
  (println (format "Questions:  %d/%d" total-questions total-dataset))
  (println (format "Correct:    %d (%.1f%%)" correct (* 100.0 (double (or accuracy 0)))))
  (println (format "Incorrect:  %d" incorrect))
  (println (format "Errors:     %d" errors))
  (println (format "Avg duration: %.1fs" (/ (double (or avg-duration-ms 0)) 1000.0)))
  (when avg-iterations
    (println (format "Avg iterations: %.1f" (double avg-iterations))))
  (when avg-tokens
    (println (format "Avg tokens: {input: %d, output: %d}"
               (long (:input avg-tokens 0))
               (long (:output avg-tokens 0)))))
  (when total-cost
    (println (format "Total cost: $%.4f" (double total-cost)))))

;; =============================================================================
;; CLI
;; =============================================================================

(defn- print-list []
  (println "\nAvailable benchmarks:\n")
  (doseq [{:keys [name description tests]} BENCHMARKS]
    (println (format "  %-12s %s" name description))
    (println (format "  %-12s Tests: %s" "" tests))
    (println))
  (println "Usage: clojure -M:bench -- --bench <name> [--mode ask|query-env] [--model MODEL] [--limit N] [--offset N]")
  (println "       clojure -M:bench -- --bench all [--limit N]")
  (println "       clojure -M:bench -- --list"))

(defn- parse-args [args]
  (loop [remaining (vec args) acc {}]
    (if (empty? remaining)
      acc
      (let [k (first remaining)
            v (second remaining)]
        (cond
          (= k "--list")   (assoc acc :list? true)
          (= k "--bench")  (recur (drop 2 remaining) (assoc acc :bench v))
          (= k "--mode")   (recur (drop 2 remaining) (assoc acc :mode (keyword v)))
          (= k "--model")  (recur (drop 2 remaining) (assoc acc :model v))
          (= k "--limit")  (recur (drop 2 remaining) (assoc acc :limit (Long/parseLong v)))
          (= k "--offset") (recur (drop 2 remaining) (assoc acc :offset (Long/parseLong v)))
          :else            (recur (rest remaining) acc))))))

(defn- run-one! [bench-name opts]
  (if-let [bench (find-bench bench-name)]
    (let [result ((:run-fn bench) opts)]
      (print-summary result)
      (when-let [f (:saved-to result)]
        (println (format "\nResults saved to: %s" f)))
      result)
    (do
      (println (format "Unknown benchmark: %s" bench-name))
      (print-list)
      (System/exit 1))))

(defn- run-all! [opts]
  (println "\n━━━ Running ALL benchmarks ━━━\n")
  (let [results (doall
                  (for [{:keys [name]} BENCHMARKS]
                    (do
                      (println (format "──── %s ────" name))
                      {:bench name :result (run-one! name opts)})))]
    (println "\n━━━ Combined Summary ━━━\n")
    (doseq [{:keys [bench result]} results]
      (println (format "  %-12s %d/%d correct (%.1f%%) | $%.4f"
                 bench
                 (:correct result 0)
                 (:total-questions result 0)
                 (* 100.0 (double (:accuracy result 0)))
                 (double (:total-cost result 0)))))
    (println)
    results))

(defn -main [& args]
  (let [parsed (parse-args args)
        bench  (:bench parsed)
        opts   (dissoc parsed :bench :list?)]
    (cond
      (:list? parsed)     (print-list)
      (nil? bench)        (do (println "Error: --bench <name> is required (or --list to see options)")
                              (print-list)
                              (System/exit 1))
      (= bench "all")    (run-all! opts)
      :else               (run-one! bench opts))
    (System/exit 0)))
