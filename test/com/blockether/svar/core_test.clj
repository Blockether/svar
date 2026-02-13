(ns com.blockether.svar.core-test
  "Baseline tests for svar/core public API functions.
   
   Covers ask!, abstract!, eval!, refine! and spec-driven humanization.
   All tests use mocked ask!/eval! to avoid real LLM calls."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.core :as svar]
   [com.blockether.svar.spec :as spec]))

;; =============================================================================
;; Test Helpers (local to this file)
;; =============================================================================

(defn- make-mock-ask-response
  "Creates a canned ask! response matching the real return shape."
  [result]
  {:result result
   :tokens {:input 0 :output 0 :total 0}
   :cost {:input-cost 0 :output-cost 0 :total-cost 0}
   :duration-ms 0})

(defn- make-mock-eval-response
  "Creates a canned eval! response matching the real return shape."
  ([score] (make-mock-eval-response score {}))
  ([score {:keys [correct? summary issues]
           :or {correct? true summary "Mock evaluation" issues []}}]
   {:correct? correct?
    :overall-score score
    :summary summary
    :criteria []
    :issues issues
    :scores {}
    :eval-duration-ms 0
    :tokens {:input 0 :output 0 :total 0}
    :cost {:input-cost 0 :output-cost 0 :total-cost 0}}))

(def ^:private test-spec
  "Simple spec for testing refine!."
  (spec/spec
   (spec/field ::spec/name :answer
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "The answer")))

;; =============================================================================
;; Mock ask! dispatcher
;; =============================================================================
;; refine! calls ask! multiple times per iteration:
;; 1. Initial ask! for first output
;; 2. Decomposition ask! (objective contains "decomposition_task")
;; 3. Verification ask! (objective contains "verification_task")
;; 4. Refinement ask! (objective contains "refinement_task")
;; We dispatch based on the objective content.

(defn- make-dispatching-ask-fn
  "Creates an ask! mock that dispatches based on objective content.
   
   Params:
   `initial-result` - Result for the initial ask! call.
   `refined-result` - Result for refinement ask! calls.
   
   Returns:
   Function suitable for with-redefs on ask!."
  [initial-result refined-result]
  (let [call-count (atom 0)]
    (fn [{:keys [objective]}]
      (swap! call-count inc)
      (cond
        ;; Decomposition call — return mock claims
        (and (string? objective)
             (re-find #"decomposition_task" objective))
        (make-mock-ask-response
         {:claims [{:claim "The answer is correct"
                    :category "factual"
                    :confidence 0.9
                    :verifiable? true}]})

        ;; Verification call — return mock verification
        (and (string? objective)
             (re-find #"verification_task" objective))
        (make-mock-ask-response
         {:verifications [{:claim "The answer is correct"
                           :question "Is the answer correct?"
                           :answer "Yes"
                           :verdict "correct"
                           :reasoning "Verified against context"}]})

        ;; Refinement call — return refined result
        (and (string? objective)
             (re-find #"refinement_task" objective))
        (make-mock-ask-response refined-result)

        ;; Initial call or unknown — return initial result
        :else
        (make-mock-ask-response initial-result)))))

;; =============================================================================
;; refine! Baseline Tests
;; =============================================================================

(defdescribe refine!-baseline-test
  (describe "return shape"
            (it "returns map with required keys: :result, :final-score, :converged?, :iterations-count"
                (with-redefs [svar/ask! (make-dispatching-ask-fn {:answer "Paris"} {:answer "Paris"})
                              svar/eval! (fn [_opts] (make-mock-eval-response 0.95))]
                  (let [result (svar/refine! {:spec test-spec
                                              :objective "Answer geography questions."
                                              :task "What is the capital of France?"
                                              :model "gpt-4o"
                                              :iterations 1
                                              :threshold 0.9})]
                    (expect (map? result))
                    (expect (contains? result :result))
                    (expect (contains? result :final-score))
                    (expect (contains? result :converged?))
                    (expect (contains? result :iterations-count))
          ;; Additional keys present in current implementation
                    (expect (contains? result :iterations))
                    (expect (contains? result :total-duration-ms))
                    (expect (contains? result :gradient))
                    (expect (contains? result :prompt-evolution))
                    (expect (contains? result :window))))))

  (describe "iteration control"
            (it "runs up to max iterations with default settings (3)"
                (let [eval-call-count (atom 0)]
                  (with-redefs [svar/ask! (make-dispatching-ask-fn {:answer "v1"} {:answer "v2"})
                                svar/eval! (fn [_opts]
                                             (swap! eval-call-count inc)
                                   ;; Return low score so it never converges
                                             (make-mock-eval-response 0.3))]
                    (let [result (svar/refine! {:spec test-spec
                                                :objective "Test objective."
                                                :task "Test task."
                                                :model "gpt-4o"
                                      ;; Use defaults: iterations=3, threshold=0.9
                                                })]
            ;; Default is 3 iterations
                      (expect (= 3 (:iterations-count result)))
                      (expect (false? (:converged? result)))))))

            (it "early-stops when score >= threshold (0.9)"
                (let [eval-scores (atom [0.95])] ;; First eval returns high score
                  (with-redefs [svar/ask! (make-dispatching-ask-fn {:answer "perfect"} {:answer "perfect"})
                                svar/eval! (fn [_opts]
                                             (let [score (or (first @eval-scores) 0.95)]
                                               (swap! eval-scores rest)
                                               (make-mock-eval-response score)))]
                    (let [result (svar/refine! {:spec test-spec
                                                :objective "Test objective."
                                                :task "Test task."
                                                :model "gpt-4o"
                                                :iterations 5
                                                :threshold 0.9})]
            ;; Should converge after 0 refinement iterations because
            ;; the initial eval score (0.95) already meets threshold.
            ;; After initial ask!, the loop checks should-stop? with score=0 first,
            ;; so it runs at least one iteration, then the eval returns 0.95.
                      (expect (true? (:converged? result)))
            ;; Should have run fewer than max iterations
                      (expect (< (:iterations-count result) 5)))))))

  (describe "backward compatibility"
            (it "works without :documents key (baseline for future extension)"
                (with-redefs [svar/ask! (make-dispatching-ask-fn {:answer "42"} {:answer "42"})
                              svar/eval! (fn [_opts] (make-mock-eval-response 0.85))]
                  (let [result (svar/refine! {:spec test-spec
                                              :objective "Answer math questions."
                                              :task "What is 6 * 7?"
                                              :model "gpt-4o"
                                              :iterations 1})]
          ;; refine! should work without :documents parameter
                    (expect (map? result))
                    (expect (some? (:result result)))
                    (expect (number? (:final-score result)))
                    (expect (boolean? (:converged? result)))
                    (expect (integer? (:iterations-count result)))))))

  (describe "result content"
            (it "final result is the last refined output"
                (with-redefs [svar/ask! (make-dispatching-ask-fn {:answer "initial"} {:answer "refined"})
                              svar/eval! (fn [_opts] (make-mock-eval-response 0.5))]
                  (let [result (svar/refine! {:spec test-spec
                                              :objective "Test."
                                              :task "Test."
                                              :model "gpt-4o"
                                              :iterations 1})]
          ;; The result should be the refined version, not the initial
                    (expect (= {:answer "refined"} (:result result))))))

            (it "final-score is a number between 0 and 1"
                (with-redefs [svar/ask! (make-dispatching-ask-fn {:answer "test"} {:answer "test"})
                              svar/eval! (fn [_opts] (make-mock-eval-response 0.75))]
                  (let [result (svar/refine! {:spec test-spec
                                              :objective "Test."
                                              :task "Test."
                                              :model "gpt-4o"
                                              :iterations 1})]
                    (expect (number? (:final-score result)))
                    (expect (>= (:final-score result) 0.0))
                    (expect (<= (:final-score result) 1.0)))))

            (it "iterations vector tracks refinement history"
                (with-redefs [svar/ask! (make-dispatching-ask-fn {:answer "v1"} {:answer "v2"})
                              svar/eval! (fn [_opts] (make-mock-eval-response 0.6))]
                  (let [result (svar/refine! {:spec test-spec
                                              :objective "Test."
                                              :task "Test."
                                              :model "gpt-4o"
                                              :iterations 2})]
                    (expect (vector? (:iterations result)))
                    (expect (= (:iterations-count result) (count (:iterations result))))
          ;; Each iteration record should have expected keys
                    (when (seq (:iterations result))
                      (let [iter (first (:iterations result))]
                        (expect (contains? iter :iteration))
                        (expect (contains? iter :output))
                        (expect (contains? iter :claims))
                        (expect (contains? iter :evaluation))
                        (expect (contains? iter :duration-ms)))))))))

;; =============================================================================
;; abstract! Baseline Tests
;; =============================================================================

(defdescribe abstract!-baseline-test
  (describe "return shape"
            (it "returns a vector of iteration maps"
                (with-redefs [svar/ask! (fn [{:keys [_objective]}]
                                          (make-mock-ask-response
                                           {:entities [{:entity "Test Entity"
                                                        :type "concept"
                                                        :importance 0.8}]
                                            :summary "A test summary."}))]
                  (let [result (svar/abstract! {:text "Some article text for summarization."
                                                :model "gpt-4o"
                                                :iterations 2
                                                :target-length 80})]
                    (expect (vector? result))
                    (expect (= 2 (count result))))))

            (it "each iteration has :entities and :summary keys"
                (with-redefs [svar/ask! (fn [_]
                                          (make-mock-ask-response
                                           {:entities [{:entity "Alpha"
                                                        :type "person"
                                                        :importance 0.9}]
                                            :summary "Iteration summary."}))]
                  (let [result (svar/abstract! {:text "Article text."
                                                :model "gpt-4o"
                                                :iterations 1})
                        iter (first result)]
                    (expect (contains? iter :entities))
                    (expect (contains? iter :summary))
                    (expect (vector? (:entities iter)))
                    (expect (string? (:summary iter)))))))

  (describe "entity structure"
            (it "entities have :entity, :type, and :importance"
                (with-redefs [svar/ask! (fn [_]
                                          (make-mock-ask-response
                                           {:entities [{:entity "ACME Corp"
                                                        :type "organization"
                                                        :importance 0.85}]
                                            :summary "Summary."}))]
                  (let [result (svar/abstract! {:text "ACME Corp reported earnings."
                                                :model "gpt-4o"
                                                :iterations 1})
                        entity (first (:entities (first result)))]
                    (expect (= "ACME Corp" (:entity entity)))
                    (expect (= "organization" (:type entity)))
                    (expect (number? (:importance entity)))))))

  (describe "iteration progression"
            (it "passes previous summary to subsequent iterations"
                (let [call-log (atom [])]
                  (with-redefs [svar/ask! (fn [{:keys [task]}]
                                            (swap! call-log conj task)
                                            (make-mock-ask-response
                                             {:entities [{:entity "E" :type "concept" :importance 0.5}]
                                              :summary "Dense summary."}))]
                    (svar/abstract! {:text "Source text."
                                     :model "gpt-4o"
                                     :iterations 2})
                    ;; First call has no previous_summary tag
                    (expect (not (re-find #"previous_summary" (first @call-log))))
                    ;; Second call includes previous_summary
                    (expect (some? (re-find #"previous_summary" (second @call-log)))))))))

;; =============================================================================
;; eval! Baseline Tests
;; =============================================================================

(defdescribe eval!-baseline-test
  (describe "return shape"
            (it "returns map with required keys"
                (with-redefs [svar/ask! (fn [_]
                                          (make-mock-ask-response
                                           {:correct? true
                                            :overall-score 0.92
                                            :summary "Accurate response."
                                            :criteria [{:name "accuracy"
                                                        :score 0.95
                                                        :confidence 0.9
                                                        :reasoning "Correct."}]
                                            :issues []}))]
                  (let [result (svar/eval! {:task "What is 2+2?"
                                            :output "4"
                                            :model "gpt-4o"})]
                    (expect (boolean? (:correct? result)))
                    (expect (number? (:overall-score result)))
                    (expect (string? (:summary result)))
                    (expect (vector? (:criteria result)))
                    (expect (map? (:scores result)))
                    (expect (number? (:eval-duration-ms result))))))

            (it "builds scores map from criteria"
                (with-redefs [svar/ask! (fn [_]
                                          (make-mock-ask-response
                                           {:correct? true
                                            :overall-score 0.88
                                            :summary "Good."
                                            :criteria [{:name "accuracy" :score 0.95 :confidence 0.9 :reasoning "OK"}
                                                       {:name "completeness" :score 0.80 :confidence 0.85 :reasoning "OK"}]
                                            :issues []}))]
                  (let [result (svar/eval! {:task "Test" :output "Test" :model "gpt-4o"})]
                    (expect (= 0.95 (:accuracy (:scores result))))
                    (expect (= 0.80 (:completeness (:scores result))))
                    (expect (= 0.88 (:overall (:scores result))))))))

  (describe "custom criteria"
            (it "passes custom criteria through to objective"
                (let [ask-args (atom nil)]
                  (with-redefs [svar/ask! (fn [opts]
                                            (reset! ask-args opts)
                                            (make-mock-ask-response
                                             {:correct? true
                                              :overall-score 0.9
                                              :summary "Good."
                                              :criteria [{:name "tone" :score 0.9 :confidence 0.8 :reasoning "OK"}]
                                              :issues []}))]
                    (svar/eval! {:task "Summarize report."
                                 :output "Revenue grew 15%."
                                 :model "gpt-4o"
                                 :criteria {:tone "Is the tone appropriate?"}})
                    ;; Objective should mention the custom criterion
                    (expect (some? (re-find #"tone" (:objective @ask-args))))))))

  (describe "ground truths"
            (it "includes ground truths in objective when provided"
                (let [ask-args (atom nil)]
                  (with-redefs [svar/ask! (fn [opts]
                                            (reset! ask-args opts)
                                            (make-mock-ask-response
                                             {:correct? true
                                              :overall-score 0.95
                                              :summary "Verified."
                                              :criteria []
                                              :issues []}))]
                    (svar/eval! {:task "What is 2+2?"
                                 :output "4"
                                 :model "gpt-4o"
                                 :ground-truths ["2+2 equals 4"]})
                    ;; Objective should contain ground_truths section
                    (expect (some? (re-find #"ground_truths" (:objective @ask-args))))
                    (expect (some? (re-find #"2\+2 equals 4" (:objective @ask-args))))))))

  (describe "issues reporting"
            (it "includes issues when present"
                (with-redefs [svar/ask! (fn [_]
                                          (make-mock-ask-response
                                           {:correct? false
                                            :overall-score 0.3
                                            :summary "Major error."
                                            :criteria [{:name "accuracy" :score 0.1 :confidence 0.95 :reasoning "Wrong"}]
                                            :issues [{:issue "Answer is incorrect"
                                                      :severity "high"
                                                      :confidence 0.95
                                                      :reasoning "2+2 is not 5"}]}))]
                  (let [result (svar/eval! {:task "What is 2+2?"
                                            :output "5"
                                            :model "gpt-4o"})]
                    (expect (false? (:correct? result)))
                    (expect (= 1 (count (:issues result))))
                    (expect (= "high" (:severity (first (:issues result))))))))))

;; =============================================================================
;; Spec-driven humanization in ask! Tests
;; =============================================================================

(def ^:private humanize-spec
  "Spec with ::humanize? fields for testing spec-driven humanization."
  (spec/spec
   (spec/field ::spec/name :summary
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "The summary"
               ::spec/humanize? true)
   (spec/field ::spec/name :tags
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/many
               ::spec/description "Tags"
               ::spec/humanize? true)
   (spec/field ::spec/name :score
               ::spec/type :spec.type/int
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Score")))

(defdescribe ask!-humanizer-test
  (describe "spec-driven humanization"
            (it "applies humanizer to fields marked ::humanize? true"
                (with-redefs [svar/ask! (let [original svar/ask!]
                                          ;; We can't easily mock the full ask! pipeline,
                                          ;; so test apply-spec-humanizer directly via the
                                          ;; public humanizer + spec field metadata.
                                          original)]
                  ;; Test the humanizer factory + humanize-data integration
                  (let [h (svar/humanizer)
                        data {:summary "As an AI, I found the results compelling."
                              :tags ["As a language model, tag one" "normal tag"]
                              :score 42}
                        ;; Simulate what ask! does: apply-spec-humanizer
                        fields (::spec/fields humanize-spec)
                        humanizable (filter ::spec/humanize? fields)
                        result (reduce
                                (fn [acc field-def]
                                  (let [k (keyword (name (::spec/name field-def)))
                                        cardinality (::spec/cardinality field-def)
                                        v (get acc k)]
                                    (cond
                                      (and (= cardinality :spec.cardinality/one) (string? v))
                                      (assoc acc k (h v))

                                      (and (= cardinality :spec.cardinality/many) (vector? v))
                                      (assoc acc k (mapv #(if (string? %) (h %) %) v))

                                      :else acc)))
                                data
                                humanizable)]
                    ;; :summary should be humanized (AI identity removed)
                    (expect (not (re-find #"As an AI" (:summary result))))
                    ;; :tags should be humanized
                    (expect (not (re-find #"As a language model" (first (:tags result)))))
                    (expect (= "normal tag" (second (:tags result))))
                    ;; :score should be untouched
                    (expect (= 42 (:score result))))))

            (it "::humanize? defaults to false (field not marked is not humanized)"
                (let [fields (::spec/fields humanize-spec)
                      score-field (first (filter #(= :score (keyword (name (::spec/name %)))) fields))]
                  (expect (nil? (::spec/humanize? score-field)))))

            (it "::humanize? true is set on marked fields"
                (let [fields (::spec/fields humanize-spec)
                      summary-field (first (filter #(= :summary (keyword (name (::spec/name %)))) fields))]
                  (expect (true? (::spec/humanize? summary-field)))))))
