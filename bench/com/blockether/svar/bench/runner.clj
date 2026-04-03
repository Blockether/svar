(ns com.blockether.svar.bench.runner
  "Unified benchmark runner for svar.

   Usage:
      clojure -M:bench -- --bench 4clojure --limit 20 --model gpt-4o
      clojure -M:bench -- --bench humaneval --agent pi --model blockether/glm-5-turbo
      clojure -M:bench -- --bench all --limit 50
      clojure -M:bench -- --list
      clojure -M:bench -- --scores"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.svar.bench.fourclojure :as fourclojure]
   [com.blockether.svar.bench.humaneval :as humaneval]
   [com.blockether.svar.core :as svar]))

;; =============================================================================
;; Router
;; =============================================================================

(def ^:private ROUTER (atom nil))
(def ^:private ROUTER_KEY (atom nil))

(def ^:private PROVIDERS
  "Known benchmark providers with their env vars and base URLs."
  {:blockether {:env-keys ["BLOCKETHER_LLM_API_KEY" "BLOCKETHER_OPENAI_API_KEY"]
                :base-url "https://llm.blockether.com/v1"}
   :zai-coding {:env-keys ["ZAI_CODING_API_KEY" "ZAI_API_KEY"]
                :base-url "https://api.z.ai/api/coding/paas/v4"}
   :zai        {:env-keys ["ZAI_API_KEY"]
                :base-url "https://api.z.ai/api/paas/v4"}
   :openai     {:env-keys ["OPENAI_API_KEY"]
                :base-url "https://api.openai.com/v1"}})

(defn- resolve-provider-key
  "Resolves API key from environment for a provider config."
  [{:keys [env-keys]}]
  (some #(System/getenv %) env-keys))

(defn- make-bench-router
  "Creates a benchmark router for a provider + model."
  [provider-id model]
  (let [provider-cfg (get PROVIDERS provider-id)
        api-key      (when provider-cfg (resolve-provider-key provider-cfg))]
    (if (nil? api-key)
      ;; Fallback: try all providers
      (let [[pid cfg key] (some (fn [[pid cfg]]
                                  (when-let [k (resolve-provider-key cfg)]
                                    [pid cfg k]))
                            PROVIDERS)]
        (if key
          (svar/make-router [{:id pid :api-key key
                              :base-url (:base-url cfg)
                              :models [{:name model}]}])
          (throw (ex-info "No API key found for any provider"
                   {:type :bench/missing-api-key
                    :providers (keys PROVIDERS)}))))
      (svar/make-router [{:id provider-id :api-key api-key
                          :base-url (:base-url provider-cfg)
                          :models [{:name model}]}]))))

(defn- ensure-router!
  [provider-id model]
  (let [key [provider-id model]]
    (if (and (some? @ROUTER) (= @ROUTER_KEY key))
      @ROUTER
      (let [router (make-bench-router provider-id model)]
        (reset! ROUTER router)
        (reset! ROUTER_KEY key)
        router))))

;; =============================================================================
;; Registry
;; =============================================================================

(def ^:private BENCHMARKS
  [{:name        "4clojure"
    :description "4Clojure — 151 idiomatic Clojure coding problems"
    :tests       "pass@1 on all test forms via bb verification"
    :run-fn      fourclojure/run-benchmark!}
   {:name        "humaneval"
    :description "OpenAI HumanEval — 164 Python coding tasks"
    :tests       "pass@1 on canonical unit tests via python3"
    :run-fn      humaneval/run-benchmark!}])

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
  (println (format "Mode:       %s" (if (= mode :query-env) "query-env! (RLM)" (str mode))))
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
  (println "Usage: clojure -M:bench -- --bench <name> [--agent query-env|pi] [--provider blockether|zai-coding|zai] [--model MODEL] [--limit N] [--offset N]")
  (println "       clojure -M:bench -- --bench 4clojure --agent query-env --provider zai-coding --model glm-5-turbo")
  (println "       clojure -M:bench -- --bench all --limit 50")
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
          (= k "--agent")  (recur (drop 2 remaining) (assoc acc :agent (keyword v)))
          (= k "--provider") (recur (drop 2 remaining) (assoc acc :provider (keyword v)))
          (= k "--model")  (recur (drop 2 remaining) (assoc acc :model v))
          (= k "--limit")  (recur (drop 2 remaining) (assoc acc :limit (Long/parseLong v)))
          (= k "--offset") (recur (drop 2 remaining) (assoc acc :offset (Long/parseLong v)))
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
                 (if (some #(contains? parsed %) [:model :limit :offset])
                   "4clojure"
                   nil))
        opts   (dissoc parsed :bench :list? :scores?)]
    (cond
      (:list? parsed)     (print-list)
      (:scores? parsed)   (print-aggregated-scores)
      (nil? bench)        (do (println "Error: --bench <name> is required (or --list to see options)")
                            (print-list)
                            (System/exit 1))
      :else               (let [model    (or (:model opts) "gpt-4o")
                                provider (or (:provider opts) :blockether)
                                run-opts (assoc opts :router (ensure-router! provider model))]
                            (if (= bench "all")
                              (run-all! run-opts)
                              (run-one! bench run-opts))))
    (System/exit 0)))
