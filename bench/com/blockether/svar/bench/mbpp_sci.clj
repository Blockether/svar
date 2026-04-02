(ns com.blockether.svar.bench.mbpp-sci
  "MBPP benchmark variant evaluated with Clojure SCI.

   This benchmark reuses MBPP task prompts, asks for Clojure code,
   translates a supported subset of Python asserts to Clojure data/calls,
   and evaluates pass@1 by executing generated Clojure in SCI."
  (:require
   [charred.api :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.svar.bench.python-tasks :as py]
   [com.blockether.svar.core :as svar]
   [sci.core :as sci]
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
      svar/DESCRIPTION "Clojure code implementing the required function(s).")))

(defn- ensure-dataset!
  []
  (py/download-if-missing! dataset-url dataset-path))

(defn- load-dataset
  []
  (ensure-dataset!)
  (with-open [rdr (io/reader dataset-path)]
    (mapv (fn [line] (json/read-json line :key-fn keyword)) (line-seq rdr))))

(defn- py-single-quoted->edn
  [s]
  (str/replace s #"'([^'\\]*(?:\\.[^'\\]*)*)'"
    (fn [[_ inner]]
      (str "\""
        (-> inner
          (str/replace #"\\'" "'")
          (str/replace #"\"" "\\\""))
        "\""))))

(defn- py-literal->edn-str
  [s]
  (-> s
    py-single-quoted->edn
    (str/replace #"\bTrue\b" "true")
    (str/replace #"\bFalse\b" "false")
    (str/replace #"\bNone\b" "nil")))

(defn- parse-assert-line
  [line]
  (let [trimmed (str/trim line)
        m (re-matches #"assert\s+([A-Za-z_][A-Za-z0-9_]*)\((.*)\)\s*==\s*(.+)$" trimmed)]
    (if (nil? m)
      nil
      (let [[_ fn-name args-src expected-src] m]
        (try
          (let [args (edn/read-string (str "[" (py-literal->edn-str args-src) "]"))
                expected (edn/read-string (py-literal->edn-str expected-src))]
            {:fn-name fn-name
             :args args
             :expected expected
             :source line})
          (catch Exception _
            nil))))))

(defn- compile-task
  [task]
  (let [raw-tests (vec (:test_list task))
        parsed-tests (mapv parse-assert-line raw-tests)
        supported? (every? some? parsed-tests)]
    (assoc task
      :compiled-tests parsed-tests
      :supported? supported?)))

(defn- call-form-str
  [fn-name args]
  (pr-str (into (list (symbol fn-name)) args)))

(defn- assert-preview
  [compiled-test]
  (let [call-src (call-form-str (:fn-name compiled-test) (:args compiled-test))
        expected-src (pr-str (:expected compiled-test))]
    (str "(= " call-src " " expected-src ")")))

(defn- build-task-prompt
  [task]
  (let [tests (:compiled-tests task)
        previews (take 5 tests)]
    (str
      "Solve this MBPP task in Clojure. Return ONLY Clojure code, no markdown.\n\n"
      "Task ID: " (:task_id task) "\n"
      "Problem:\n" (:text task) "\n\n"
      "Target language: Clojure\n"
      "Write one or more defn forms that satisfy these checks:\n"
      (str/join "\n" (map assert-preview previews)))))

(defn- eval-candidate
  [code compiled-tests]
  (let [ctx (sci/init {:deny '[require import ns eval load-string read-string slurp spit]})
        total (count compiled-tests)]
    (try
      (sci/eval-string* ctx code)
      (loop [remaining compiled-tests
             passed 0
             failures []]
        (if (empty? remaining)
          {:all-passed? (= passed total)
           :passed-tests passed
           :total-tests total
           :compile-error nil
           :failures failures}
          (let [{:keys [fn-name args expected]} (first remaining)
                step (try
                       (let [actual (sci/eval-string* ctx (call-form-str fn-name args))]
                         (if (= actual expected)
                           {:pass? true}
                           {:pass? false
                            :failure {:fn-name fn-name
                                      :args args
                                      :expected expected
                                      :actual actual}}))
                       (catch Exception e
                         {:pass? false
                          :failure {:fn-name fn-name
                                    :args args
                                    :expected expected
                                    :error (ex-message e)}}))]
            (recur (rest remaining)
              (if (:pass? step) (inc passed) passed)
              (if (:pass? step)
                failures
                (conj failures (:failure step)))))))
      (catch Exception e
        {:all-passed? false
         :passed-tests 0
         :total-tests total
         :compile-error (ex-message e)
         :failures []}))))

(defn- eval-task-ask!
  [router task model]
  (let [ask-result (svar/ask! router {:spec CODE_SPEC
                                      :messages [(svar/system "You write correct idiomatic Clojure code. Return only code.")
                                                 (svar/user (build-task-prompt task))]
                                      :model model})
        code (py/strip-fence (get-in ask-result [:result :code]))
        score (eval-candidate code (:compiled-tests task))]
    {:correct? (:all-passed? score)
     :passed-tests (:passed-tests score)
     :total-tests (:total-tests score)
     :compile-error (:compile-error score)
     :failures (:failures score)
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
  "Runs MBPP-SCI benchmark.

   opts:
     :mode      - only :ask supported
     :model     - model name
     :limit     - max tasks to run
     :offset    - skip first N tasks
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
                      (throw (ex-info "mbpp-sci supports only --mode ask" {:mode mode})))
        offset (get opts :offset 0)
        limit (get opts :limit nil)
        _ (trove/log! {:level :info :id ::bench-start
                       :data {:mode mode :model model :offset offset :limit limit}
                       :msg "Starting MBPP-SCI benchmark"})
        raw-dataset (load-dataset)
        compiled-dataset (mapv compile-task raw-dataset)
        supported (vec (filter :supported? compiled-dataset))
        total-supported (count supported)
        total-raw (count compiled-dataset)
        slice (cond->> (drop offset supported)
                limit (take limit))
        tasks (vec slice)
        total-q (count tasks)
        state (atom {:correct 0 :incorrect 0 :errors 0
                     :results []
                     :total-duration-ms 0
                     :total-input-tokens 0 :total-output-tokens 0
                     :total-cost 0.0})]

    (println (format "\nRunning MBPP-SCI [mode=%s model=%s tasks=%d offset=%d supported=%d raw=%d]\n"
               (name mode) model total-q offset total-supported total-raw))

    (doseq [[idx task] (map-indexed vector tasks)]
      (let [q-num (inc idx)
            eval-result (try
                          (eval-task-ask! router task model)
                          (catch Exception e
                            (trove/log! {:level :warn :id ::bench-error
                                         :data {:q-num q-num :error (ex-message e)}
                                         :msg "Task evaluation failed"})
                            {:error (ex-message e)
                             :correct? false
                             :passed-tests 0
                             :total-tests (count (:compiled-tests task))
                             :duration-ms 0}))
            correct? (boolean (:correct? eval-result))
            error? (boolean (:error eval-result))
            result-rec {:task-num q-num
                        :task-id (:task_id task)
                        :correct? correct?
                        :passed-tests (:passed-tests eval-result)
                        :total-tests (:total-tests eval-result)
                        :code (:code eval-result)
                        :compile-error (:compile-error eval-result)
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
          saved (save-results! "mbpp-sci" mode model (:results s) nil)]
      {:bench "mbpp-sci"
       :mode mode
       :model model
       :total-questions total-q
       :total-dataset total-supported
       :total-raw-dataset total-raw
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
