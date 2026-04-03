(ns com.blockether.svar.bench.fourclojure
  "4Clojure benchmark — 151 idiomatic Clojure coding problems.

   Agents: :query-env (svar RLM) | :pi (Pi coding agent)
   Verification: babashka (bb) subprocess.

   Usage:
     clojure -M:bench -- --bench 4clojure --limit 20 --model gpt-4o
     clojure -M:bench -- --bench 4clojure --agent pi --model blockether/glm-5-turbo"
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.svar.bench.bench-common :as common]
   [com.blockether.svar.core :as svar]
   [taoensso.trove :as trove])
  (:import
   (java.nio.file Files)
   (java.util.concurrent TimeUnit)))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private dataset-path "bench/data/4clojure/4clojure.jsonl")
(def ^:private bb-timeout-ms 30000)

;; =============================================================================
;; Dataset
;; =============================================================================

(defn- load-dataset []
  (with-open [rdr (io/reader dataset-path)]
    (mapv (fn [line]
            (let [obj (json/read-json line :key-fn keyword)]
              {:id          (:id obj)
               :title       (:title obj)
               :description (:description obj)
               :tests       (vec (:tests obj))
               :restricted  (vec (or (:restricted obj) []))}))
      (line-seq rdr))))

;; =============================================================================
;; bb verification
;; =============================================================================

(defn- substitute-blank [test-form candidate]
  (str/replace test-form "__" candidate))

(defn- build-bb-script [tests candidate]
  (let [filled-tests (mapv #(substitute-blank % candidate) tests)
        test-forms   (str/join "\n"
                       (map-indexed
                         (fn [_i test-str]
                           (format "(let [result (try (if %s :pass [:fail %s (pr-str (try %s (catch Exception e (str \"error: \" (ex-message e)))))])
                                                     (catch Exception e [:error %s (ex-message e)]))]
                                     (swap! results conj result))"
                             test-str (pr-str test-str) test-str (pr-str test-str)))
                         filled-tests))]
    (str "(def results (atom []))\n"
      test-forms "\n"
      "(let [rs @results
             passed (count (filter #(= :pass %) rs))
             total (count rs)
             failures (vec (remove #(= :pass %) rs))]
         (prn {:all-passed? (= passed total) :passed passed :total total :failures failures}))")))

(defn- run-bb! [script]
  (let [tmp  (Files/createTempFile "4clj-bench-" ".clj"
               (make-array java.nio.file.attribute.FileAttribute 0))
        path (.toFile tmp)]
    (spit path script)
    (try
      (let [pb (ProcessBuilder. (into-array String ["bb" (.getAbsolutePath path)]))
            _  (.redirectErrorStream pb true)
            proc (.start pb)
            output-future (future (slurp (.getInputStream proc)))
            finished? (.waitFor proc bb-timeout-ms TimeUnit/MILLISECONDS)
            output (if finished? (deref output-future 5000 "") "")]
        (if finished?
          {:ok? (zero? (.exitValue proc)) :output (str/trim output) :timeout? false}
          (do (.destroyForcibly proc) (future-cancel output-future)
              {:ok? false :output "Timed out" :timeout? true})))
      (finally
        (.delete path)))))

(defn- verify-with-bb [tests candidate]
  (let [bb-result (run-bb! (build-bb-script tests candidate))]
    (if (:ok? bb-result)
      (try
        (read-string (:output bb-result))
        (catch Exception _
          {:all-passed? false :passed 0 :total (count tests)
           :failures [{:error (str "Parse error: " (:output bb-result))}]}))
      {:all-passed? false :passed 0 :total (count tests)
       :failures [{:error (if (:timeout? bb-result) "bb timed out"
                              (str "bb: " (:output bb-result)))}]})))

;; =============================================================================
;; Prompt
;; =============================================================================

(defn- build-prompt [{:keys [title description tests restricted]}]
  (str "Solve this Clojure problem.\n\n"
    "Title: " title "\n"
    "Description: " (str/replace description "\n" " ") "\n\n"
    "Test forms (your expression replaces __):\n"
    (str/join "\n" (map #(str "  " %) tests))
    (if (seq restricted)
      (str "\n\nRestricted (do NOT use these): " (str/join ", " restricted))
      "")
    "\n\nReturn ONLY a valid Clojure expression that replaces __. "
    "No markdown, no explanation, no code fences. Just the raw Clojure expression."))

;; =============================================================================
;; Agent eval functions
;; =============================================================================

(defn- eval-query-env! [router problem model]
  (let [env   (svar/create-env router {})
        start (System/currentTimeMillis)]
    (try
      (let [result (svar/query-env! env (build-prompt problem) {:model model :max-iterations 20 :debug? true})
            answer (str/trim (str (:answer result)))
            score  (verify-with-bb (:tests problem) answer)]
        {:correct?    (:all-passed? score)
         :answer      answer
         :passed      (:passed score)
         :total-tests (:total score)
         :failures    (:failures score)
         :iterations  (:iterations result)
         :tokens      (:tokens result)
         :cost        (:cost result)
         :duration-ms (- (System/currentTimeMillis) start)})
      (finally
        (svar/dispose-env! env)))))

(defn- eval-pi! [problem model]
  (let [pi-result (common/run-pi! (build-prompt problem) model)]
    (if (:timed-out? pi-result)
      {:correct? false :answer nil :duration-ms (:duration-ms pi-result)
       :failures [{:error "pi timed out"}]}
      (let [answer (common/strip-code-fence (:output pi-result))
            score  (verify-with-bb (:tests problem) answer)]
        {:correct?    (:all-passed? score)
         :answer      answer
         :passed      (:passed score)
         :total-tests (:total score)
         :failures    (:failures score)
         :duration-ms (:duration-ms pi-result)}))))

;; =============================================================================
;; Result record builder
;; =============================================================================

(defn- make-result-rec [q-num problem eval-result]
  {:q-num       q-num
   :problem-id  (:id problem)
   :title       (:title problem)
   :correct?    (boolean (:correct? eval-result))
   :answer      (:answer eval-result)
   :passed      (:passed eval-result)
   :total-tests (:total-tests eval-result)
   :failures    (:failures eval-result)
   :error       (:error eval-result)
   :tokens      (:tokens eval-result)
   :cost        (:cost eval-result)
   :duration-ms (:duration-ms eval-result)
   :iterations  (:iterations eval-result)})

;; =============================================================================
;; Main runner
;; =============================================================================

(defn run-benchmark!
  "Runs 4clojure benchmark.
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
                       :msg "Starting 4clojure benchmark"})

        dataset  (load-dataset)
        total-ds (count dataset)
        problems (vec (cond->> (drop offset dataset) limit (take limit)))

        eval-fn  (case agent-name
                   :query-env (fn [problem] (eval-query-env! router problem model))
                   :pi        (fn [problem] (eval-pi! problem model)))]

    (common/run-parallel-bench! "4clojure" agent-name model problems total-ds eval-fn make-result-rec)))
