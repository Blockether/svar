(ns com.blockether.svar.bench.runner
  "Unified benchmark runner for svar.

   Usage:
     clojure -M:bench -- --bench gsm8k --mode ask --limit 50
     clojure -M:bench -- --bench gsm8k --mode query-env --model claude-opus-4-6
     clojure -M:bench -- --bench all --limit 50
     clojure -M:bench -- --list

   Benchmarks:
     gsm8k    - Grade School Math (1319 problems) — tests code execution + reasoning
     all      - Run all benchmarks sequentially"
  (:require
   [com.blockether.svar.bench.gsm8k :as gsm8k]))

;; =============================================================================
;; Registry
;; =============================================================================

(def ^:private BENCHMARKS
  "Available benchmarks. Each is a map with :name, :description, :run-fn."
  [{:name        "gsm8k"
    :description "Grade School Math 8K — 1319 math word problems (Cobbe et al., 2021)"
    :tests       "Code execution accuracy, iteration efficiency, cost"
    :run-fn      gsm8k/run-benchmark!}])

(defn- find-bench [name]
  (first (filter #(= name (:name %)) BENCHMARKS)))

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
    (do
      (println (format "\n━━━ %s ━━━" (:description bench)))
      ((:run-fn bench) opts))
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
    (println "\n━━━ Summary ━━━\n")
    (doseq [{:keys [bench result]} results]
      (let [pct (if (pos? (:total-questions result 0))
                  (/ (* 100.0 (:correct result 0)) (:total-questions result 1))
                  0.0)]
        (println (format "  %-12s %d/%d correct (%.1f%%)"
                   bench (:correct result 0) (:total-questions result 0) pct))))
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
