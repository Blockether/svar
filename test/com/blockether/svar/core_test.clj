(ns com.blockether.svar.core-test
  "Baseline tests for svar/core public API functions.
   
   Covers ask!, abstract!, eval!, refine! and spec-driven humanization.
   All tests use mocked ask!/eval! to avoid real LLM calls.
   Integration tests (real LLM) are guarded by OPENAI_API_KEY env var."
  (:require
   [clojure.string :as str]
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.core :as svar]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.spec :as spec]))

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
    :duration-ms 0
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

(defn- extract-system-content
  "Extracts the system message content from a :messages vector."
  [messages]
  (->> messages (filter #(= "system" (:role %))) first :content))

(defn- make-dispatching-ask-fn
  "Creates an ask! mock that dispatches based on system message content.
   
   Params:
   `initial-result` - Result for the initial ask! call.
   `refined-result` - Result for refinement ask! calls.
   
   Returns:
   Function suitable for with-redefs on ask!."
  [initial-result refined-result]
  (let [call-count (atom 0)]
    (fn [{:keys [messages]}]
      (swap! call-count inc)
      (let [objective (extract-system-content messages)]
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
          (make-mock-ask-response initial-result))))))

;; =============================================================================
;; refine! Baseline Tests
;; =============================================================================

(defdescribe refine!-baseline-test
  (describe "return shape"
            (it "returns map with required keys: :result, :final-score, :converged?, :iterations-count"
                (with-redefs [llm/ask! (make-dispatching-ask-fn {:answer "Paris"} {:answer "Paris"})
                              llm/eval! (fn [_opts] (make-mock-eval-response 0.95))]
                  (let [result (svar/refine! {:spec test-spec
                                               :messages [(svar/system "Answer geography questions.")
                                                          (svar/user "What is the capital of France?")]
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
                  (with-redefs [llm/ask! (make-dispatching-ask-fn {:answer "v1"} {:answer "v2"})
                                llm/eval! (fn [_opts]
                                             (swap! eval-call-count inc)
                                   ;; Return low score so it never converges
                                             (make-mock-eval-response 0.3))]
                    (let [result (svar/refine! {:spec test-spec
                                                 :messages [(svar/system "Test objective.")
                                                            (svar/user "Test task.")]
                                                 :model "gpt-4o"
                                       ;; Use defaults: iterations=3, threshold=0.9
                                                })]
            ;; Default is 3 iterations
                      (expect (= 3 (:iterations-count result)))
                      (expect (false? (:converged? result)))))))

            (it "early-stops when score >= threshold (0.9)"
                (let [eval-scores (atom [0.95])] ;; First eval returns high score
                  (with-redefs [llm/ask! (make-dispatching-ask-fn {:answer "perfect"} {:answer "perfect"})
                                llm/eval! (fn [_opts]
                                             (let [score (or (first @eval-scores) 0.95)]
                                               (swap! eval-scores rest)
                                               (make-mock-eval-response score)))]
                    (let [result (svar/refine! {:spec test-spec
                                                 :messages [(svar/system "Test objective.")
                                                            (svar/user "Test task.")]
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
                (with-redefs [llm/ask! (make-dispatching-ask-fn {:answer "42"} {:answer "42"})
                              llm/eval! (fn [_opts] (make-mock-eval-response 0.85))]
                   (let [result (svar/refine! {:spec test-spec
                                               :messages [(svar/system "Answer math questions.")
                                                          (svar/user "What is 6 * 7?")]
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
                (with-redefs [llm/ask! (make-dispatching-ask-fn {:answer "initial"} {:answer "refined"})
                              llm/eval! (fn [_opts] (make-mock-eval-response 0.5))]
                   (let [result (svar/refine! {:spec test-spec
                                               :messages [(svar/system "Test.")
                                                          (svar/user "Test.")]
                                               :model "gpt-4o"
                                               :iterations 1})]
          ;; The result should be the refined version, not the initial
                    (expect (= {:answer "refined"} (:result result))))))

            (it "final-score is a number between 0 and 1"
                (with-redefs [llm/ask! (make-dispatching-ask-fn {:answer "test"} {:answer "test"})
                              llm/eval! (fn [_opts] (make-mock-eval-response 0.75))]
                   (let [result (svar/refine! {:spec test-spec
                                               :messages [(svar/system "Test.")
                                                          (svar/user "Test.")]
                                               :model "gpt-4o"
                                               :iterations 1})]
                    (expect (number? (:final-score result)))
                    (expect (>= (:final-score result) 0.0))
                    (expect (<= (:final-score result) 1.0)))))

            (it "iterations vector tracks refinement history"
                (with-redefs [llm/ask! (make-dispatching-ask-fn {:answer "v1"} {:answer "v2"})
                              llm/eval! (fn [_opts] (make-mock-eval-response 0.6))]
                   (let [result (svar/refine! {:spec test-spec
                                               :messages [(svar/system "Test.")
                                                          (svar/user "Test.")]
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
                (with-redefs [llm/ask! (fn [{:keys [_objective]}]
                                          (make-mock-ask-response
                                           {:entities [{:entity "Test Entity" :rationale "Central topic" :score 0.9}]
                                            :summary "A test summary."}))]
                  (let [result (svar/abstract! {:text "Some article text for summarization."
                                                :model "gpt-4o"
                                                :iterations 2
                                                :target-length 80})]
                    (expect (vector? result))
                    (expect (= 2 (count result))))))

            (it "each iteration has :entities and :summary keys"
                (with-redefs [llm/ask! (fn [_]
                                          (make-mock-ask-response
                                           {:entities [{:entity "Alpha" :rationale "Key concept" :score 0.85}
                                                       {:entity "Beta" :rationale "Supporting concept" :score 0.6}]
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
            (it "entities are maps with :entity, :rationale, and :score"
                (with-redefs [llm/ask! (fn [_]
                                          (make-mock-ask-response
                                           {:entities [{:entity "ACME Corp" :rationale "Main company discussed" :score 0.95}
                                                       {:entity "earnings report" :rationale "Key financial document" :score 0.7}]
                                            :summary "Summary."}))]
                  (let [result (svar/abstract! {:text "ACME Corp reported earnings."
                                                :model "gpt-4o"
                                                :iterations 1})
                        entities (:entities (first result))]
                    (expect (= [{:entity "ACME Corp" :rationale "Main company discussed" :score 0.95}
                                {:entity "earnings report" :rationale "Key financial document" :score 0.7}]
                               entities))
                    (expect (every? #(and (contains? % :entity)
                                         (contains? % :rationale)
                                         (contains? % :score)) entities))))))

  (describe "iteration progression"
            (it "passes previous summary and accumulated entities to subsequent iterations"
                (let [call-log (atom [])]
                  (with-redefs [llm/ask! (fn [{:keys [messages]}]
                                             (let [user-content (->> messages (filter #(= "user" (:role %))) first :content)]
                                               (swap! call-log conj user-content))
                                             (make-mock-ask-response
                                              {:entities [{:entity "Entity A" :rationale "Salient" :score 0.8}]
                                               :summary "Dense summary."}))]
                    (svar/abstract! {:text "Source text."
                                     :model "gpt-4o"
                                     :iterations 2})
                    ;; First call has no previous_summary tag
                    (expect (not (re-find #"previous_summary" (first @call-log))))
                    ;; Second call includes previous_summary
                    (expect (some? (re-find #"previous_summary" (second @call-log))))
                    ;; Second call includes accumulated entities
                    (expect (some? (re-find #"already_extracted_entities" (second @call-log)))))))))

;; =============================================================================
;; eval! Baseline Tests
;; =============================================================================

(defdescribe eval!-baseline-test
  (describe "return shape"
            (it "returns map with required keys"
                (with-redefs [llm/ask! (fn [_]
                                          (make-mock-ask-response
                                           {:correct? true
                                            :overall-score 0.92
                                            :summary "Accurate response."
                                            :criteria [{:name "accuracy"
                                                        :score 0.95
                                                        :confidence 0.9
                                                        :reasoning "Correct."}]
                                            :issues []}))]
                  (let [result (llm/eval! {:task "What is 2+2?"
                                            :output "4"
                                            :model "gpt-4o"})]
                    (expect (boolean? (:correct? result)))
                    (expect (number? (:overall-score result)))
                    (expect (string? (:summary result)))
                    (expect (vector? (:criteria result)))
                    (expect (map? (:scores result)))
                    (expect (number? (:duration-ms result))))))

            (it "builds scores map from criteria"
                (with-redefs [llm/ask! (fn [_]
                                          (make-mock-ask-response
                                           {:correct? true
                                            :overall-score 0.88
                                            :summary "Good."
                                            :criteria [{:name "accuracy" :score 0.95 :confidence 0.9 :reasoning "OK"}
                                                       {:name "completeness" :score 0.80 :confidence 0.85 :reasoning "OK"}]
                                            :issues []}))]
                  (let [result (llm/eval! {:task "Test" :output "Test" :model "gpt-4o"})]
                    (expect (= 0.95 (:accuracy (:scores result))))
                    (expect (= 0.80 (:completeness (:scores result))))
                    (expect (= 0.88 (:overall (:scores result))))))))

  (describe "custom criteria"
            (it "passes custom criteria through to objective"
                (let [ask-args (atom nil)]
                  (with-redefs [llm/ask! (fn [opts]
                                            (reset! ask-args opts)
                                            (make-mock-ask-response
                                             {:correct? true
                                              :overall-score 0.9
                                              :summary "Good."
                                              :criteria [{:name "tone" :score 0.9 :confidence 0.8 :reasoning "OK"}]
                                              :issues []}))]
                    (llm/eval! {:task "Summarize report."
                                 :output "Revenue grew 15%."
                                 :model "gpt-4o"
                                 :criteria {:tone "Is the tone appropriate?"}})
                    ;; System message should mention the custom criterion
                     (expect (some? (re-find #"tone" (extract-system-content (:messages @ask-args)))))))))

  (describe "ground truths"
            (it "includes ground truths in objective when provided"
                (let [ask-args (atom nil)]
                  (with-redefs [llm/ask! (fn [opts]
                                            (reset! ask-args opts)
                                            (make-mock-ask-response
                                             {:correct? true
                                              :overall-score 0.95
                                              :summary "Verified."
                                              :criteria []
                                              :issues []}))]
                    (llm/eval! {:task "What is 2+2?"
                                 :output "4"
                                 :model "gpt-4o"
                                 :ground-truths ["2+2 equals 4"]})
                    ;; System message should contain ground_truths section
                     (let [sys-content (extract-system-content (:messages @ask-args))]
                       (expect (some? (re-find #"ground_truths" sys-content)))
                       (expect (some? (re-find #"2\+2 equals 4" sys-content))))))))

  (describe "issues reporting"
            (it "includes issues when present"
                (with-redefs [llm/ask! (fn [_]
                                          (make-mock-ask-response
                                           {:correct? false
                                            :overall-score 0.3
                                            :summary "Major error."
                                            :criteria [{:name "accuracy" :score 0.1 :confidence 0.95 :reasoning "Wrong"}]
                                            :issues [{:issue "Answer is incorrect"
                                                      :severity "high"
                                                      :confidence 0.95
                                                      :reasoning "2+2 is not 5"}]}))]
                  (let [result (llm/eval! {:task "What is 2+2?"
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
                (with-redefs [llm/ask! (let [original llm/ask!]
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

;; =============================================================================
;; eval! :messages support Tests
;; =============================================================================

(defdescribe eval!-messages-test

  (describe "messages as task source"
            (it "extracts task from user message when :messages provided"
                (let [ask-args (atom nil)]
                  (with-redefs [llm/ask! (fn [opts]
                                            (reset! ask-args opts)
                                            (make-mock-ask-response
                                             {:correct? true
                                              :overall-score 0.9
                                              :summary "Good."
                                              :criteria []
                                              :issues []}))]
                    (llm/eval! {:messages [(svar/user "What is the capital of France?")]
                                 :output "Paris"
                                 :model "gpt-4o"})
                    ;; The user message content from eval task should contain the task text
                    (let [user-msg (->> (:messages @ask-args)
                                        (filter #(= "user" (:role %)))
                                        first :content)]
                      (expect (some? (re-find #"capital of France" user-msg)))))))

            (it "extracts from system + user messages (non-assistant, joined)"
                (let [ask-args (atom nil)]
                  (with-redefs [llm/ask! (fn [opts]
                                            (reset! ask-args opts)
                                            (make-mock-ask-response
                                             {:correct? true
                                              :overall-score 0.85
                                              :summary "OK."
                                              :criteria []
                                              :issues []}))]
                    (llm/eval! {:messages [(svar/system "You are a geography expert.")
                                            (svar/user "What is the capital of France?")]
                                 :output "Paris"
                                 :model "gpt-4o"})
                    ;; Both system and user content should appear in the eval task
                    (let [user-msg (->> (:messages @ask-args)
                                        (filter #(= "user" (:role %)))
                                        first :content)]
                      (expect (some? (re-find #"geography expert" user-msg)))
                      (expect (some? (re-find #"capital of France" user-msg))))))))

  (describe "backward compatibility"
            (it ":task still works without :messages"
                (let [ask-args (atom nil)]
                  (with-redefs [llm/ask! (fn [opts]
                                            (reset! ask-args opts)
                                            (make-mock-ask-response
                                             {:correct? true
                                              :overall-score 0.9
                                              :summary "Good."
                                              :criteria []
                                              :issues []}))]
                    (llm/eval! {:task "What is 2+2?"
                                 :output "4"
                                 :model "gpt-4o"})
                    (let [user-msg (->> (:messages @ask-args)
                                        (filter #(= "user" (:role %)))
                                        first :content)]
                      (expect (some? (re-find #"2\+2" user-msg)))))))

            (it ":task takes precedence when both :task and :messages provided"
                (let [ask-args (atom nil)]
                  (with-redefs [llm/ask! (fn [opts]
                                            (reset! ask-args opts)
                                            (make-mock-ask-response
                                             {:correct? true
                                              :overall-score 0.9
                                              :summary "Good."
                                              :criteria []
                                              :issues []}))]
                    (llm/eval! {:task "explicit task text"
                                 :messages [(svar/user "message task text")]
                                 :output "some output"
                                 :model "gpt-4o"})
                    (let [user-msg (->> (:messages @ask-args)
                                        (filter #(= "user" (:role %)))
                                        first :content)]
                      ;; Explicit :task should win
                      (expect (some? (re-find #"explicit task text" user-msg)))
                      ;; Message text should NOT appear (task takes precedence)
                      (expect (nil? (re-find #"message task text" user-msg))))))))

  (describe "return shape preserved"
            (it "returns all expected keys when using :messages"
                (with-redefs [llm/ask! (fn [_]
                                          (make-mock-ask-response
                                           {:correct? true
                                            :overall-score 0.92
                                            :summary "Accurate."
                                            :criteria [{:name "accuracy" :score 0.95 :confidence 0.9 :reasoning "OK"}]
                                            :issues []}))]
                  (let [result (llm/eval! {:messages [(svar/user "Test task")]
                                            :output "Test output"
                                            :model "gpt-4o"})]
                    (expect (boolean? (:correct? result)))
                    (expect (number? (:overall-score result)))
                    (expect (string? (:summary result)))
                    (expect (vector? (:criteria result)))
                    (expect (map? (:scores result)))
                    (expect (number? (:duration-ms result))))))))

;; =============================================================================
;; sample! Tests
;; =============================================================================

(defdescribe sample!-test

  (describe "zero count edge case"
            (it "returns empty result with zero count"
                (let [result (svar/sample! {:spec test-spec
                                             :count 0
                                             :model "gpt-4o"})]
                  (expect (= [] (:samples result)))
                  (expect (= {} (:scores result)))
                  (expect (= 0.0 (:final-score result)))
                  (expect (true? (:converged? result)))
                  (expect (= 0 (:iterations-count result)))
                  (expect (= 0.0 (:duration-ms result))))))

  (describe "return shape"
            (it "returns map with all expected keys"
                (with-redefs [llm/ask! (fn [_]
                                          (make-mock-ask-response
                                           {:items [{:answer "alpha"} {:answer "beta"}]}))
                              llm/eval! (fn [_] (make-mock-eval-response 0.95))]
                  (let [result (svar/sample! {:spec test-spec
                                               :count 2
                                               :model "gpt-4o"})]
                    (expect (contains? result :samples))
                    (expect (contains? result :scores))
                    (expect (contains? result :final-score))
                    (expect (contains? result :converged?))
                    (expect (contains? result :iterations-count))
                    (expect (contains? result :duration-ms))))))

  (describe "basic generation"
            (it "returns generated samples when score meets threshold"
                (with-redefs [llm/ask! (fn [_]
                                          (make-mock-ask-response
                                           {:items [{:answer "one"} {:answer "two"} {:answer "three"}]}))
                              llm/eval! (fn [_] (make-mock-eval-response 0.95))]
                  (let [result (svar/sample! {:spec test-spec
                                               :count 3
                                               :model "gpt-4o"})]
                    (expect (= 3 (count (:samples result))))
                    (expect (= "one" (:answer (first (:samples result)))))
                    (expect (true? (:converged? result)))
                    (expect (= 1 (:iterations-count result)))))))

  (describe "self-correction loop"
            (it "runs multiple iterations when score below threshold"
                (let [ask-call-count (atom 0)
                      eval-scores (atom [0.3 0.95])]
                  (with-redefs [llm/ask! (fn [_]
                                            (swap! ask-call-count inc)
                                            (make-mock-ask-response
                                             {:items [{:answer (str "v" @ask-call-count)}]}))
                                llm/eval! (fn [_]
                                             (let [score (first @eval-scores)]
                                               (swap! eval-scores rest)
                                               (make-mock-eval-response (or score 0.95))))]
                    (let [result (svar/sample! {:spec test-spec
                                                 :count 1
                                                 :model "gpt-4o"
                                                 :iterations 3
                                                 :threshold 0.9})]
                      ;; Should have run 2 iterations (first low, second high)
                      (expect (= 2 (:iterations-count result)))
                      (expect (true? (:converged? result)))))))

            (it "keeps best samples across iterations"
                (let [eval-call-count (atom 0)]
                  (with-redefs [llm/ask! (fn [_]
                                            (make-mock-ask-response
                                             {:items [{:answer "sample"}]}))
                                llm/eval! (fn [_]
                                             (swap! eval-call-count inc)
                                             ;; All low scores — never converges
                                             (make-mock-eval-response 0.3))]
                    (let [result (svar/sample! {:spec test-spec
                                                 :count 1
                                                 :model "gpt-4o"
                                                 :iterations 2
                                                 :threshold 0.99})]
                      (expect (= 2 (:iterations-count result)))
                      (expect (false? (:converged? result)))
                      ;; Should still have samples (best from all iterations)
                      (expect (seq (:samples result))))))))

  (describe "custom messages"
            (it "passes user messages to ask! with count instruction appended"
                (let [captured-messages (atom nil)]
                  (with-redefs [llm/ask! (fn [{:keys [messages]}]
                                            (reset! captured-messages messages)
                                            (make-mock-ask-response
                                             {:items [{:answer "x"}]}))
                                llm/eval! (fn [_] (make-mock-eval-response 0.95))]
                    (svar/sample! {:spec test-spec
                                    :count 3
                                    :messages [(svar/system "Generate dating profiles.")
                                               (svar/user "Make them diverse.")]
                                    :model "gpt-4o"})
                    (let [msgs @captured-messages
                          ;; First message is system from user, second is user from user,
                          ;; third is appended count instruction, then schema prompt from ask!
                          sys-msg (first msgs)]
                      (expect (= "system" (:role sys-msg)))
                      (expect (some? (re-find #"dating profiles" (:content sys-msg))))
                      ;; Count instruction should be present somewhere in messages
                      (expect (some #(and (= "user" (:role %))
                                         (re-find #"exactly 3" (:content %)))
                                    msgs)))))))

  (describe "iteration control"
            (it "respects :iterations 1 — runs exactly once"
                (let [ask-call-count (atom 0)]
                  (with-redefs [llm/ask! (fn [_]
                                            (swap! ask-call-count inc)
                                            (make-mock-ask-response
                                             {:items [{:answer "only"}]}))
                                llm/eval! (fn [_] (make-mock-eval-response 0.1))]
                    (let [result (svar/sample! {:spec test-spec
                                                 :count 1
                                                 :model "gpt-4o"
                                                 :iterations 1
                                                 :threshold 0.99})]
                      (expect (= 1 (:iterations-count result)))
                      (expect (= 1 @ask-call-count))))))))

;; =============================================================================
;; Message Helpers Tests
;; =============================================================================

(defdescribe message-helpers-test

  (describe "system"
            (it "creates system message map"
                (expect (= {:role "system" :content "You are helpful."}
                           (svar/system "You are helpful.")))))

  (describe "user"
            (it "creates user message with string content (no images)"
                (let [msg (svar/user "Hello")]
                  (expect (= "user" (:role msg)))
                  (expect (= "Hello" (:content msg)))))

            (it "creates user message with multimodal content when images provided"
                (let [img (svar/image "base64data" "image/png")
                      msg (svar/user "Describe this" img)]
                  (expect (= "user" (:role msg)))
                  (expect (vector? (:content msg)))
                  (expect (= 2 (count (:content msg))))
                  ;; First element is image_url (images come first in multimodal)
                  (let [img-part (first (:content msg))]
                    (expect (= "image_url" (:type img-part)))
                    (expect (some? (re-find #"base64data"
                                            (get-in img-part [:image_url :url])))))
                  ;; Second element is text
                  (let [text-part (second (:content msg))]
                    (expect (= "text" (:type text-part)))
                    (expect (= "Describe this" (:text text-part)))))))

  (describe "assistant"
            (it "creates assistant message map"
                (expect (= {:role "assistant" :content "I can help."}
                           (svar/assistant "I can help.")))))

  (describe "image"
            (it "creates image attachment map"
                (let [img (svar/image "abc123" "image/jpeg")]
                  (expect (= "abc123" (:base64 img)))
                  (expect (= "image/jpeg" (:media-type img)))))))

;; =============================================================================
;; Integration Tests (real LLM calls, guarded by env var)
;; =============================================================================

(defn- integration-tests-enabled?
  "Returns true if LLM integration tests should run.
   SVAR's make-config auto-reads BLOCKETHER_OPENAI_API_KEY (primary)
   and OPENAI_API_KEY (fallback)."
  []
  (or (some? (System/getenv "BLOCKETHER_OPENAI_API_KEY"))
      (some? (System/getenv "OPENAI_API_KEY"))))

;; Rich factual text about the Voyager missions — dense with entities, dates,
;; distances, organizations, and scientific concepts. Perfect for CoD testing.
(def ^:private VOYAGER_TEXT
  "Voyager 1, launched by NASA on September 5, 1977, from Cape Canaveral aboard a Titan IIIE-Centaur rocket, is the most distant human-made object from Earth. As of 2024, it is over 24 billion kilometers from Earth, traveling through interstellar space at approximately 17 kilometers per second. The spacecraft carries a Golden Record, a 12-inch gold-plated copper phonograph record containing sounds and images selected to portray the diversity of life and culture on Earth — curated by a committee chaired by Carl Sagan of Cornell University. Voyager 1 made its closest approach to Jupiter on March 5, 1979, discovering volcanic activity on Io and revealing the intricate structure of Jupiter's ring system. It then flew past Saturn on November 12, 1980, where it studied the complex atmosphere and ring system, and made detailed observations of Titan, Saturn's largest moon, whose thick nitrogen atmosphere intrigued scientists. On August 25, 2012, Voyager 1 crossed the heliopause — the boundary where the solar wind gives way to the interstellar medium — becoming the first spacecraft to enter interstellar space. Its twin, Voyager 2, launched 16 days earlier on August 20, 1977, is the only spacecraft to have visited all four giant planets: Jupiter, Saturn, Uranus, and Neptune. Both spacecraft are powered by radioisotope thermoelectric generators (RTGs), which convert heat from the decay of plutonium-238 into electricity, and are expected to continue transmitting data until roughly 2025.")

;; Short scientific text about CRISPR — compact, technical, entity-dense.
(def ^:private CRISPR_TEXT
  "CRISPR-Cas9, developed as a gene-editing tool by Jennifer Doudna at UC Berkeley and Emmanuelle Charpentier at the Max Planck Institute, earned them the 2020 Nobel Prize in Chemistry. The system uses a guide RNA to direct the Cas9 enzyme to a specific DNA sequence, where it creates a double-strand break. The cell's repair machinery then either disables the gene (via non-homologous end joining) or inserts a new sequence (via homology-directed repair). Since 2013, CRISPR has been applied to human diseases including sickle cell disease, beta-thalassemia, and certain cancers. In December 2023, the FDA approved Casgevy (exagamglogene autotemcel), developed by Vertex Pharmaceuticals and CRISPR Therapeutics, as the first CRISPR-based therapy for sickle cell disease. Despite its precision, off-target effects remain a concern — the enzyme occasionally cuts DNA at unintended sites, potentially causing harmful mutations.")

(defdescribe abstract!-integration-test
  (describe "basic Chain of Density with Voyager text"
            (it "produces iterations with entities and summaries"
                (when (integration-tests-enabled?)
                  (let [result (svar/abstract! {:text VOYAGER_TEXT
                                                :model "gpt-4o"
                                                :iterations 3
                                                :target-length 80})]
                    ;; Shape checks
                    (expect (vector? result))
                    (expect (= 3 (count result)))

                    ;; Each iteration has the right structure
                    (doseq [iter result]
                      (expect (contains? iter :entities))
                      (expect (contains? iter :summary))
                      (expect (vector? (:entities iter)))
                      (expect (string? (:summary iter)))
                      ;; Entities are maps with :entity, :rationale, and :score
                      (expect (every? #(and (contains? % :entity)
                                           (contains? % :rationale)
                                           (contains? % :score)) (:entities iter))))

                    ;; First iteration should have some entities
                    (expect (pos? (count (:entities (first result)))))

                    ;; Summaries should be non-empty
                    (expect (pos? (count (:summary (first result)))))
                    (expect (pos? (count (:summary (last result)))))

                    ;; --- Content quality checks ---
                    ;; Across all iterations, must extract at least some key Voyager entities
                    (let [all-entity-names (->> result
                                                (mapcat :entities)
                                                (map :entity)
                                                (map str/lower-case)
                                                set)]
                      ;; "Voyager 1" should always appear (it's the central subject)
                      (expect (some #(str/includes? % "voyager") all-entity-names))
                      ;; At least 2 of these key entities should be found
                      (let [key-entities #{"nasa" "golden record" "jupiter" "saturn" "heliopause"
                                           "carl sagan" "titan" "voyager 2" "interstellar space"}
                            found (count (filter (fn [key-ent]
                                                  (some #(str/includes? % key-ent) all-entity-names))
                                                key-entities))]
                        (expect (>= found 2))))

                    ;; Scores should be in 0.0-1.0 range
                    (doseq [entity (->> result (mapcat :entities))]
                      (expect (<= 0.0 (:score entity) 1.0)))

                    ;; Summaries should mention Voyager
                    (expect (some #(str/includes? (str/lower-case (:summary %)) "voyager") result)))))

            (it "later iterations accumulate more entities without re-extraction"
                (when (integration-tests-enabled?)
                  (let [result (svar/abstract! {:text VOYAGER_TEXT
                                                :model "gpt-4o"
                                                :iterations 3
                                                :target-length 80})
                        ;; Total entity count should grow across iterations
                        cumulative-entities (reductions
                                            (fn [acc iter] (into acc (map :entity (:entities iter))))
                                            #{}
                                            result)]
                    ;; By iteration 3 we should have more total entities than iteration 1
                    (expect (> (count (last cumulative-entities))
                              (count (second cumulative-entities))))

                    ;; Entities from later iterations should be genuinely new (no re-extraction)
                    (let [iter1-names (set (map :entity (:entities (first result))))
                          iter2-names (set (map :entity (:entities (second result))))]
                      ;; Iteration 2 entities should not duplicate iteration 1 exactly
                      (expect (empty? (clojure.set/intersection iter1-names iter2-names))))))))

  (describe "basic Chain of Density with CRISPR text"
            (it "handles technical scientific text"
                (when (integration-tests-enabled?)
                  (let [result (svar/abstract! {:text CRISPR_TEXT
                                                :model "gpt-4o"
                                                :iterations 2
                                                :target-length 60})]
                    (expect (vector? result))
                    (expect (= 2 (count result)))

                    ;; Should extract meaningful scientific entities with rationales and scores
                    (let [all-entities (into [] cat (map :entities result))
                          entity-names (mapv :entity all-entities)
                          lower-names (mapv str/lower-case entity-names)]
                      (expect (pos? (count all-entities)))
                      ;; Each entity has :entity, :rationale, and :score
                      (expect (every? #(and (contains? % :entity)
                                           (contains? % :rationale)
                                           (contains? % :score)) all-entities))
                      ;; Must find CRISPR-Cas9 (central subject, always score ~1.0)
                      (expect (some #(str/includes? % "crispr") lower-names))
                      ;; Must find at least one of the developers
                      (expect (some #(or (str/includes? % "doudna")
                                        (str/includes? % "charpentier")) lower-names))
                      ;; Must find at least one disease application
                      (expect (some #(or (str/includes? % "sickle")
                                        (str/includes? % "beta-thalassemia")
                                        (str/includes? % "cancer")) lower-names))
                      ;; Scores are all within valid range
                      (expect (every? #(<= 0.0 (:score %) 1.0) all-entities))
                      ;; Rationales should be non-empty strings
                      (expect (every? #(pos? (count (:rationale %))) all-entities)))

                    ;; Summaries should mention CRISPR
                    (expect (every? #(str/includes? (str/lower-case (:summary %)) "crispr") result))))))

  (describe "with :eval? quality scoring"
            (it "each iteration gets a numeric score reflecting quality"
                (when (integration-tests-enabled?)
                  (let [result (svar/abstract! {:text CRISPR_TEXT
                                                :model "gpt-4o"
                                                :iterations 2
                                                :target-length 60
                                                :eval? true})]
                    (expect (vector? result))
                    (expect (= 2 (count result)))

                    ;; Each iteration should have a :score in valid range
                    (doseq [iter result]
                      (expect (contains? iter :score))
                      (expect (number? (:score iter)))
                      (expect (<= 0.0 (:score iter) 1.0)))

                    ;; Scores should be reasonable (not degenerate) — at least 0.5
                    ;; for a well-formed summary of factual text
                    (expect (>= (:score (first result)) 0.5))

                    ;; Structure should still be complete
                    (expect (pos? (count (:entities (first result)))))))))

  (describe "with :refine? CoVe verification"
            (it "last iteration is marked as refined"
                (when (integration-tests-enabled?)
                  (let [result (svar/abstract! {:text CRISPR_TEXT
                                                :model "gpt-4o"
                                                :iterations 2
                                                :target-length 60
                                                :refine? true
                                                :threshold 0.9})]
                    (expect (vector? result))
                    (expect (= 2 (count result)))

                    ;; Last iteration should be marked as refined
                    (let [last-iter (last result)]
                      (expect (true? (:refined? last-iter)))
                      (expect (some? (:summary last-iter)))
                      ;; Refinement score key should be present
                      (expect (contains? last-iter :refinement-score))
                      ;; When score is non-nil, it should be numeric
                      (when-let [score (:refinement-score last-iter)]
                        (expect (number? score))))

                    ;; Earlier iterations should not be refined
                    (expect (nil? (:refined? (first result))))))))

  (describe "with :eval? and :refine? combined"
            (it "produces scored iterations with refined final summary"
                (when (integration-tests-enabled?)
                  (let [result (svar/abstract! {:text CRISPR_TEXT
                                                :model "gpt-4o"
                                                :iterations 2
                                                :target-length 60
                                                :eval? true
                                                :refine? true
                                                :threshold 0.9})]
                    (expect (vector? result))
                    (expect (= 2 (count result)))

                    ;; All iterations should have scores
                    (doseq [iter result]
                      (expect (number? (:score iter))))

                    ;; Last iteration should be refined
                    (expect (true? (:refined? (last result))))))))

  (describe "with :special-instructions"
            (it "date-focused instructions produce mostly temporal entities"
                (when (integration-tests-enabled?)
                  (let [result (svar/abstract! {:text VOYAGER_TEXT
                                                :model "gpt-4o"
                                                :iterations 2
                                                :target-length 60
                                                :special-instructions "Focus exclusively on dates and temporal events. Every entity should be a date or time reference."})
                        ;; Collect all first-iteration entities (where special instructions have strongest effect)
                        iter1-entities (:entities (first result))
                        iter1-names (mapv :entity iter1-entities)]
                    (expect (vector? result))
                    (expect (= 2 (count result)))
                    ;; Should still produce valid entity structure
                    (doseq [iter result]
                      (expect (string? (:summary iter)))
                      (expect (vector? (:entities iter)))
                      (expect (every? #(and (contains? % :entity)
                                           (contains? % :rationale)
                                           (contains? % :score)) (:entities iter))))
                    ;; First iteration should have at least one date/temporal entity
                    ;; (The text has: 1977, 1979, 1980, 2012, 2024, 2025)
                    (expect (some #(re-find #"\d{4}" (:entity %)) iter1-entities))))))

  (describe "target-length control"
            (it "produces summaries near the target word count"
                (when (integration-tests-enabled?)
                  (let [result (svar/abstract! {:text VOYAGER_TEXT
                                                :model "gpt-4o"
                                                :iterations 2
                                                :target-length 50})
                        word-count (fn [s] (count (str/split (str/trim s) #"\s+")))]
                    (expect (vector? result))
                    ;; Summaries should be within reasonable range of target (50 words ± 50%)
                    (doseq [iter result]
                      (let [wc (word-count (:summary iter))]
                        (expect (>= wc 25))
                        (expect (<= wc 100)))))))))

