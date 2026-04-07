(ns com.blockether.svar.bench.benches.fourclojure
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
   [edamame.core :as edamame]
   [com.blockether.svar.bench.common :as common]
   [com.blockether.svar.core :as svar]
   [taoensso.trove :as trove])
  (:import
   (java.nio.file Files)
   (java.util.concurrent TimeUnit)))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private dataset-path "bench/data/4clojure/4clojure.jsonl")
(def ^:private bb-timeout-ms 60000)

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

(defn- single-form?
  "Returns true if candidate is a single Clojure form (safe for def binding)."
  [s]
  (try
    (= 1 (count (edamame/parse-string-all s {:all true})))
    (catch Exception _ false)))

(defn- build-bb-script [tests candidate]
  (let [use-def? (single-form? candidate)
        filled-tests (if use-def?
                       tests ;; tests keep __ literal, resolved via (def __)
                       (mapv #(str/replace % "__" candidate) tests))
        test-forms (str/join "\n"
                     (map-indexed
                       (fn [i test-str]
                         (format "(swap! results conj (try (if %s :pass [:fail %d]) (catch Exception e [:error %d (ex-message e)])))"
                           test-str i i))
                       filled-tests))]
    (str "(def results (atom []))\n"
      "(def is identity)\n"
      (when use-def? (str "(def __ " candidate ")\n"))
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
    (spit "/tmp/last-bb-script.clj" script)
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
  (str "4CLOJURE PROBLEM\n\n"
    "Title: " title "\n"
    "Description: " (str/replace description "\n" " ") "\n\n"
    "Test forms (your expression replaces __):\n"
    (str/join "\n" (map #(str "  " %) tests))
    (when (seq restricted)
      (str "\n\nRestricted (do NOT use these): " (str/join ", " restricted)))
    "\n\nSelf-test block (run in code[] BEFORE submitting final):\n"
    "(let [__ YOUR_SOLUTION]\n"
    (str/join "\n" (map #(str "  (assert " % ")") tests))
    "\n  :all-tests-pass)\n"
    "\nFinal answer = single inline Clojure expression that replaces __."))

(defn- build-pi-prompt [{:keys [title description tests restricted]}]
  (str "4CLOJURE PROBLEM\n\n"
    "Title: " title "\n"
    "Description: " (str/replace description "\n" " ") "\n\n"
    "Test forms (your expression replaces __):\n"
    (str/join "\n" (map #(str "  " %) tests))
    (if (seq restricted)
      (str "\n\nRestricted (do NOT use these): " (str/join ", " restricted))
      "")
    "\n\nReturn ONLY the Clojure expression that replaces __. No explanation, no markdown.\n"
    "- Nested #() is ILLEGAL. Use (fn [...] ...) for inner lambdas.\n"
    "- Quote list literals: '(1 2 3) not (1 2 3)\n"
    "- % args only work inside #(). Don't use them standalone."))

;; =============================================================================
;; Agent eval functions
;; =============================================================================

(defn- eval-query-env! [router problem model run-ts debug?]
  (common/run-query-env-task!
    {:bench      "4clojure"
     :router     router
     :task       problem
     :model      model
     :run-ts     run-ts
     :task-id    (or (:id problem) (:title problem) (str (hash problem)))
     :prompt-fn  build-prompt
     :query-opts (cond-> {:system-prompt "ALWAYS run the self-test block in code[] before submitting final. Never skip testing."}
                   debug? (assoc :debug? true))
     :score-fn  (fn [p result duration]
                  (let [answer (str/trim (str (or (:answer result) "")))
                        score  (if (str/blank? answer)
                                 {:all-passed? false :passed 0 :total (count (:tests p))
                                  :failures [{:error "Empty answer"}]}
                                 (verify-with-bb (:tests p) answer))]
                    {:correct?    (:all-passed? score)
                     :answer      answer
                     :passed      (:passed score)
                     :total-tests (:total score)
                     :failures    (:failures score)
                     :iterations  (:iterations result)
                     :tokens      (:tokens result)
                     :cost        (:cost result)
                     :duration-ms duration}))}))

(defn- eval-pi! [problem model & {:keys [router]}]
  (let [prompt   (build-pi-prompt problem)
        pi-result (if router
                    (common/run-pi-local! prompt router)
                    (common/run-pi! prompt model))]
    (if (:timed-out? pi-result)
      {:correct? false :answer nil :duration-ms (:duration-ms pi-result)
       :failures [{:error "pi timed out"}]}
      (let [answer (common/strip-code-fence (:output pi-result))
            score  (if (str/blank? answer)
                     {:all-passed? false :passed 0 :total (count (:tests problem))
                      :failures [{:error "Empty answer"}]}
                     (verify-with-bb (:tests problem) answer))]
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
        provider   (get opts :provider :blockether)
        pi-model   (str (name provider) "/" model)
        router     (:router opts)
        offset     (get opts :offset 0)
        limit      (get opts :limit nil)
        run-ts     (str (java.time.Instant/now))

        _ (if (and (= agent-name :query-env) (nil? router))
            (throw (ex-info "Missing :router for query-env agent" {:type :bench/missing-router}))
            nil)

        _ (trove/log! {:level :info :id ::bench-start
                       :data {:agent agent-name :model model :offset offset :limit limit}
                       :msg "Starting 4clojure benchmark"})

        ids      (when-let [raw (get opts :ids nil)]
                   (set (map #(Long/parseLong %) raw)))
        dataset  (load-dataset)
        total-ds (count dataset)
        filtered (if ids
                   (filter #(contains? ids (:id %)) dataset)
                   (drop offset dataset))
        problems (vec (cond->> filtered limit (take limit)))

        debug?   (get opts :debug? false)
        local?   (contains? #{:lmstudio :ollama} provider)
        eval-fn  (case agent-name
                   :query-env (fn [problem] (eval-query-env! router problem model run-ts debug?))
                   :pi        (if local?
                                (fn [problem] (eval-pi! problem pi-model :router router))
                                (fn [problem] (eval-pi! problem pi-model))))]

    (common/run-parallel-bench! "4clojure" agent-name model problems total-ds eval-fn make-result-rec)))
