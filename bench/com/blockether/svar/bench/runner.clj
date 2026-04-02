(ns com.blockether.svar.bench.runner
  "Unified benchmark runner for svar.

   Usage:
      clojure -M:bench -- --bench gsm8k --mode ask --limit 50
      clojure -M:bench -- --bench humaneval --mode ask --model gpt-4o
      clojure -M:bench -- --bench mbpp --mode ask --model gpt-5-mini --limit 100
      clojure -M:bench -- --bench gsm8k --mode query-env --model claude-opus-4-6
      clojure -M:bench -- --bench all --limit 50
      clojure -M:bench -- --list
      clojure -M:bench -- --scores

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
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.svar.bench.gsm8k :as gsm8k]
   [com.blockether.svar.bench.humaneval :as humaneval]
   [com.blockether.svar.bench.mbpp :as mbpp]
   [com.blockether.svar.bench.mbpp-sci :as mbpp-sci]
   [com.blockether.svar.core :as svar]))

;; =============================================================================
;; Router
;; =============================================================================

(def ^:private ROUTER (atom nil))
(def ^:private ROUTER_MODEL (atom nil))

(defn- make-bench-router
  "Creates a benchmark router from environment variables for one model."
  [model]
  (let [blockether-key (or (System/getenv "BLOCKETHER_LLM_API_KEY")
                         (System/getenv "BLOCKETHER_OPENAI_API_KEY"))
        openai-key     (System/getenv "OPENAI_API_KEY")
        api-key        (or blockether-key openai-key)
        provider-id    (cond
                         blockether-key :blockether
                         openai-key :openai
                         :else nil)]
    (if (nil? api-key)
      (throw (ex-info "Missing API key for benchmark router"
               {:type :bench/missing-api-key
                :required-one-of ["BLOCKETHER_LLM_API_KEY"
                                  "BLOCKETHER_OPENAI_API_KEY"
                                  "OPENAI_API_KEY"]}))
      (let [base-url (or (System/getenv "BLOCKETHER_LLM_API_BASE_URL")
                       (System/getenv "BLOCKETHER_OPENAI_BASE_URL")
                       (System/getenv "OPENAI_BASE_URL")
                       (cond
                         blockether-key "https://llm.blockether.com/v1"
                         openai-key "https://api.openai.com/v1"
                         :else nil))]
        (svar/make-router [{:id provider-id
                            :api-key api-key
                            :base-url base-url
                            :models [{:name model}]}])))))

(defn- ensure-router!
  [model]
  (if (and (some? @ROUTER) (= @ROUTER_MODEL model))
    @ROUTER
    (let [router (make-bench-router model)]
      (reset! ROUTER router)
      (reset! ROUTER_MODEL model)
      router)))

;; =============================================================================
;; Registry
;; =============================================================================

(def ^:private BENCHMARKS
  [{:name        "gsm8k"
    :description "Grade School Math 8K — 1319 math word problems (Cobbe et al., 2021)"
    :tests       "Code execution accuracy, iteration efficiency, cost"
    :run-fn      gsm8k/run-benchmark!}
   {:name        "humaneval"
    :description "OpenAI HumanEval — 164 Python coding tasks"
    :tests       "pass@1 on canonical unit tests"
    :run-fn      humaneval/run-benchmark!}
   {:name        "mbpp"
    :description "Google MBPP — mostly basic Python problems"
    :tests       "pass@1 on public MBPP test_list asserts"
    :run-fn      mbpp/run-benchmark!}
   {:name        "mbpp-sci"
    :description "MBPP translated to Clojure asserts, executed in SCI"
    :tests       "pass@1 on supported MBPP assert translations"
    :run-fn      mbpp-sci/run-benchmark!}])

(def ^:private AGGREGATE_FILE_PREFIX
  "bench/results/aggregate-")

(defn- bench-from-filename
  [filename]
  (let [bench-names (sort-by count > (map :name BENCHMARKS))]
    (first (filter #(str/starts-with? filename (str % "-")) bench-names))))

(defn- list-result-files
  []
  (let [dir (io/file "bench/results")]
    (if (.exists dir)
      (filter (fn [f]
                (let [n (.getName f)]
                  (and (.isFile f)
                    (str/ends-with? n ".ednl")
                    (str/starts-with? n "bench-"))))
        (file-seq dir))
      [])))

(defn- read-ednl-lines
  [f]
  (let [lines (->> (str/split-lines (slurp f))
                (map str/trim)
                (remove str/blank?))]
    (mapv edn/read-string lines)))

(defn- summarize-run-file
  [f]
  (try
    (let [name (.getName f)
          entries (read-ednl-lines f)
          results (vec (filter (fn [e] (or (nil? (:type e)) (= :result (:type e)))) entries))
          sample (if (seq results)
                   (first results)
                   (first entries))
          run-id (or (:run-id sample) (str/replace name #"\.ednl$" ""))
          total (count results)
          correct (count (filter :correct? results))
          errors (count (filter (fn [r] (some? (:error r))) results))
          incorrect (max 0 (- total correct errors))
          cost (reduce + 0.0 (map (fn [r] (double (or (get-in r [:cost :total-cost]) 0.0))) results))
          bench (or (:bench sample) (bench-from-filename name))]
      {:file (.getPath f)
       :run-id run-id
       :bench bench
       :mode (:mode sample)
       :model (:model sample)
       :total total
       :correct correct
       :incorrect incorrect
       :errors errors
       :cost cost})
    (catch Exception _
      nil)))

(defn- aggregate-run-summaries
  [runs]
  (let [grouped (group-by (fn [r] [(:bench r) (:mode r) (:model r)]) runs)]
    (mapv (fn [[[bench mode model] rows]]
            (let [runs-n (count rows)
                  total (reduce + 0 (map :total rows))
                  correct (reduce + 0 (map :correct rows))
                  incorrect (reduce + 0 (map :incorrect rows))
                  errors (reduce + 0 (map :errors rows))
                  cost (reduce + 0.0 (map :cost rows))
                  accuracy (if (pos? total) (/ (double correct) total) 0.0)]
              {:bench bench
               :mode mode
               :model model
               :runs runs-n
               :total total
               :correct correct
               :incorrect incorrect
               :errors errors
               :accuracy accuracy
               :total-cost cost
               :files (mapv :file rows)}))
      grouped)))

(defn- make-aggregate-path
  []
  (let [ts-safe (str/replace (str (java.time.Instant/now)) ":" "-")]
    (str AGGREGATE_FILE_PREFIX ts-safe ".ednl")))

(defn- write-aggregate-ednl!
  [runs aggregates]
  (let [path (make-aggregate-path)
        out-file (io/file path)
        parent (.getParentFile out-file)]
    (if (some? parent)
      (.mkdirs parent)
      nil)
    (let [run-lines (map #(assoc % :type :run-summary) runs)
          aggregate-lines (map #(assoc % :type :aggregate-summary) aggregates)
          content (str/join "\n" (map pr-str (concat run-lines aggregate-lines)))]
      (spit out-file content))
    path))

(defn- print-aggregated-scores []
  (let [files (list-result-files)
        runs (vec (keep summarize-run-file files))
        aggregates (aggregate-run-summaries runs)
        bench-order (zipmap (map :name BENCHMARKS) (range))]
    (if (empty? aggregates)
      (println "No benchmark result EDNL files found in bench/results")
      (let [sorted-aggs (sort-by (fn [r] [(get bench-order (:bench r) 999)
                                          (str (:mode r))
                                          (:model r)]) aggregates)
            sorted-runs (sort-by (fn [r] [(get bench-order (:bench r) 999)
                                          (str (:mode r))
                                          (:model r)
                                          (:run-id r)]) runs)
            out-path (write-aggregate-ednl! sorted-runs sorted-aggs)]
        (println "\nAggregated benchmark scores (from bench/results/*.ednl)\n")
        (doseq [agg sorted-aggs]
          (println (format "  %-10s %-10s %-14s %6.1f%% (%d/%d) runs=%d errors=%d cost=$%.4f"
                     (:bench agg)
                     (name (:mode agg))
                     (:model agg)
                     (* 100.0 (double (:accuracy agg)))
                     (:correct agg)
                     (:total agg)
                     (:runs agg)
                     (:errors agg)
                     (double (:total-cost agg)))))
        (println)
        (println (format "Wrote aggregate EDNL: %s" out-path))))))

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
  (if avg-iterations
    (println (format "Avg iterations: %.1f" (double avg-iterations)))
    nil)
  (if avg-tokens
    (println (format "Avg tokens: {input: %d, output: %d}"
               (long (:input avg-tokens 0))
               (long (:output avg-tokens 0))))
    nil)
  (if total-cost
    (println (format "Total cost: $%.4f" (double total-cost)))
    nil))

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
  (println "       clojure -M:bench -- --bench humaneval [--model MODEL] [--limit N] [--timeout-ms N]")
  (println "       clojure -M:bench -- --bench mbpp [--model MODEL] [--limit N] [--timeout-ms N] [--include-challenge-tests true|false]")
  (println "       clojure -M:bench -- --bench mbpp-sci [--model MODEL] [--limit N]")
  (println "       clojure -M:bench -- --bench all [--limit N]")
  (println "       clojure -M:bench -- --list")
  (println "       clojure -M:bench -- --scores"))

(defn- parse-args [args]
  (loop [remaining (vec args) acc {}]
    (if (empty? remaining)
      acc
      (let [k (first remaining)
            v (second remaining)]
        (cond
          (= k "--list")   (assoc acc :list? true)
          (= k "--scores") (assoc acc :scores? true)
          (= k "--bench")  (recur (drop 2 remaining) (assoc acc :bench v))
          (= k "--mode")   (recur (drop 2 remaining) (assoc acc :mode (keyword v)))
          (= k "--model")  (recur (drop 2 remaining) (assoc acc :model v))
          (= k "--limit")  (recur (drop 2 remaining) (assoc acc :limit (Long/parseLong v)))
          (= k "--offset") (recur (drop 2 remaining) (assoc acc :offset (Long/parseLong v)))
          (= k "--timeout-ms") (recur (drop 2 remaining) (assoc acc :timeout-ms (Long/parseLong v)))
          (= k "--include-challenge-tests") (recur (drop 2 remaining) (assoc acc :include-challenge-tests? (= "true" (str/lower-case v))))
          :else            (recur (rest remaining) acc))))))

(defn- run-one! [bench-name opts]
  (if-let [bench (find-bench bench-name)]
    (let [result ((:run-fn bench) opts)]
      (print-summary result)
      (if-let [f (:saved-to result)]
        (println (format "\nResults saved to: %s" f))
        nil)
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
        bench  (or (:bench parsed)
                 (if (some #(contains? parsed %) [:mode :model :limit :offset])
                   "gsm8k"
                   nil))
        opts   (dissoc parsed :bench :list? :scores?)]
    (cond
      (:list? parsed)     (print-list)
      (:scores? parsed)   (print-aggregated-scores)
      (nil? bench)        (do (println "Error: --bench <name> is required (or --list to see options)")
                            (print-list)
                            (System/exit 1))
      :else               (let [model (or (:model opts) "gpt-4o")
                                run-opts (assoc opts :router (ensure-router! model))]
                            (if (= bench "all")
                              (run-all! run-opts)
                              (run-one! bench run-opts))))
    (System/exit 0)))
