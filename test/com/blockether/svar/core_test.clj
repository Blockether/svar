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
;; Test Router (replaces make-config)
;; =============================================================================

(def ^:private test-router
  "Router for unit tests — mocked LLM calls never reach the network."
  (svar/make-router [{:id :test :api-key "sk-test" :base-url "http://test" :models [{:name "gpt-4o"}]}]))

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

(defmacro ^:private with-mock-llm
  "Mocks both routed and primitive LLM functions to prevent real API calls.
   Accepts :ask and :eval mock functions."
  [{:keys [ask eval]} & body]
  (let [redefs (cond-> []
                 ask (into ['llm/ask! ask 'llm/ask!* ask])
                 eval (into ['llm/eval! eval 'llm/eval!* eval]))]
    `(with-redefs ~redefs
       ~@body)))

(defn- make-dispatching-ask-fn
  "Creates an ask! mock that dispatches based on system message content.
   
   Params:
   `initial-result` - Result for the initial ask! call.
   `refined-result` - Result for refinement ask! calls.
   
   Returns:
   Function suitable for with-redefs on ask!."
  [initial-result refined-result]
  (let [call-count (atom 0)]
    (fn [_router {:keys [messages]}]
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
      (with-mock-llm {:ask (make-dispatching-ask-fn {:answer "Paris"} {:answer "Paris"})
                      :eval (fn [_router _opts] (make-mock-eval-response 0.95))}
        (let [result (svar/refine! test-router
                       {:spec test-spec
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
        (with-mock-llm {:ask (make-dispatching-ask-fn {:answer "v1"} {:answer "v2"})
                        :eval (fn [_router _opts]
                                (swap! eval-call-count inc)
                                 ;; Return low score so it never converges
                                (make-mock-eval-response 0.3))}

          (let [result (svar/refine! test-router
                         {:spec test-spec
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
        (with-mock-llm {:ask (make-dispatching-ask-fn {:answer "perfect"} {:answer "perfect"})
                        :eval (fn [_router _opts]
                                (let [score (or (first @eval-scores) 0.95)]
                                  (swap! eval-scores rest)
                                  (make-mock-eval-response score)))}

          (let [result (svar/refine! test-router
                         {:spec test-spec
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
      (with-mock-llm {:ask (make-dispatching-ask-fn {:answer "42"} {:answer "42"})
                      :eval (fn [_router _opts] (make-mock-eval-response 0.85))}

        (let [result (svar/refine! test-router {:spec test-spec
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
      (with-mock-llm {:ask (make-dispatching-ask-fn {:answer "initial"} {:answer "refined"})
                      :eval (fn [_router _opts] (make-mock-eval-response 0.5))}

        (let [result (svar/refine! test-router {:spec test-spec
                                                :messages [(svar/system "Test.")
                                                           (svar/user "Test.")]
                                                :model "gpt-4o"
                                                :iterations 1})]
          ;; The result should be the refined version, not the initial
          (expect (= {:answer "refined"} (:result result))))))

    (it "final-score is a number between 0 and 1"
      (with-mock-llm {:ask (make-dispatching-ask-fn {:answer "test"} {:answer "test"})
                      :eval (fn [_router _opts] (make-mock-eval-response 0.75))}

        (let [result (svar/refine! test-router {:spec test-spec
                                                :messages [(svar/system "Test.")
                                                           (svar/user "Test.")]
                                                :model "gpt-4o"
                                                :iterations 1})]
          (expect (number? (:final-score result)))
          (expect (>= (:final-score result) 0.0))
          (expect (<= (:final-score result) 1.0)))))

    (it "iterations vector tracks refinement history"
      (with-mock-llm {:ask (make-dispatching-ask-fn {:answer "v1"} {:answer "v2"})
                      :eval (fn [_router _opts] (make-mock-eval-response 0.6))}

        (let [result (svar/refine! test-router {:spec test-spec
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
    (it "returns a map with :result vector of iteration maps"
      (with-mock-llm {:ask (fn [_router {:keys [_objective]}]
                             (make-mock-ask-response
                               {:entities [{:entity "Test Entity" :rationale "Central topic" :score 0.9}]
                                :summary "A test summary."}))}

        (let [response (svar/abstract! test-router {:text "Some article text for summarization."
                                                    :model "gpt-4o"
                                                    :iterations 2
                                                    :target-length 80})
              result (:result response)]
          (expect (map? response))
          (expect (contains? response :tokens))
          (expect (contains? response :cost))
          (expect (vector? result))
          (expect (= 2 (count result))))))

    (it "each iteration has :entities and :summary keys"
      (with-mock-llm {:ask (fn [_router _opts]
                             (make-mock-ask-response
                               {:entities [{:entity "Alpha" :rationale "Key concept" :score 0.85}
                                           {:entity "Beta" :rationale "Supporting concept" :score 0.6}]
                                :summary "Iteration summary."}))}

        (let [response (svar/abstract! test-router {:text "Article text."
                                                    :model "gpt-4o"
                                                    :iterations 1})
              iter (first (:result response))]
          (expect (contains? iter :entities))
          (expect (contains? iter :summary))
          (expect (vector? (:entities iter)))
          (expect (string? (:summary iter)))))))

  (describe "entity structure"
    (it "entities are maps with :entity, :rationale, and :score"
      (with-mock-llm {:ask (fn [_router _opts]
                             (make-mock-ask-response
                               {:entities [{:entity "ACME Corp" :rationale "Main company discussed" :score 0.95}
                                           {:entity "earnings report" :rationale "Key financial document" :score 0.7}]
                                :summary "Summary."}))}

        (let [response (svar/abstract! test-router {:text "ACME Corp reported earnings."
                                                    :model "gpt-4o"
                                                    :iterations 1})
              entities (:entities (first (:result response)))]
          (expect (= [{:entity "ACME Corp" :rationale "Main company discussed" :score 0.95}
                      {:entity "earnings report" :rationale "Key financial document" :score 0.7}]
                    entities))
          (expect (every? #(and (contains? % :entity)
                             (contains? % :rationale)
                             (contains? % :score)) entities))))))

  (describe "iteration progression"
    (it "passes previous summary and accumulated entities to subsequent iterations"
      (let [call-log (atom [])]
        (with-mock-llm {:ask (fn [_router {:keys [messages]}]
                               (let [user-content (->> messages (filter #(= "user" (:role %))) first :content)]
                                 (swap! call-log conj user-content))
                               (make-mock-ask-response
                                 {:entities [{:entity "Entity A" :rationale "Salient" :score 0.8}]
                                  :summary "Dense summary."}))}

          (svar/abstract! test-router {:text "Source text."
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
      (with-mock-llm {:ask (fn [_router _opts]
                             (make-mock-ask-response
                               {:correct? true
                                :overall-score 0.92
                                :summary "Accurate response."
                                :criteria [{:name "accuracy"
                                            :score 0.95
                                            :confidence 0.9
                                            :reasoning "Correct."}]
                                :issues []}))}

        (let [result (llm/eval! test-router {:task "What is 2+2?"
                                             :output "4"
                                             :model "gpt-4o"})]
          (expect (boolean? (:correct? result)))
          (expect (number? (:overall-score result)))
          (expect (string? (:summary result)))
          (expect (vector? (:criteria result)))
          (expect (map? (:scores result)))
          (expect (number? (:duration-ms result))))))

    (it "builds scores map from criteria"
      (with-mock-llm {:ask (fn [_router _opts]
                             (make-mock-ask-response
                               {:correct? true
                                :overall-score 0.88
                                :summary "Good."
                                :criteria [{:name "accuracy" :score 0.95 :confidence 0.9 :reasoning "OK"}
                                           {:name "completeness" :score 0.80 :confidence 0.85 :reasoning "OK"}]
                                :issues []}))}

        (let [result (llm/eval! test-router {:task "Test" :output "Test" :model "gpt-4o"})]
          (expect (= 0.95 (:accuracy (:scores result))))
          (expect (= 0.80 (:completeness (:scores result))))
          (expect (= 0.88 (:overall (:scores result))))))))

  (describe "custom criteria"
    (it "passes custom criteria through to objective"
      (let [ask-args (atom nil)]
        (with-mock-llm {:ask (fn [_router opts]
                               (reset! ask-args opts)
                               (make-mock-ask-response
                                 {:correct? true
                                  :overall-score 0.9
                                  :summary "Good."
                                  :criteria [{:name "tone" :score 0.9 :confidence 0.8 :reasoning "OK"}]
                                  :issues []}))}

          (llm/eval! test-router {:task "Summarize report."
                                  :output "Revenue grew 15%."
                                  :model "gpt-4o"
                                  :criteria {:tone "Is the tone appropriate?"}})
                    ;; System message should mention the custom criterion
          (expect (some? (re-find #"tone" (extract-system-content (:messages @ask-args)))))))))

  (describe "ground truths"
    (it "includes ground truths in objective when provided"
      (let [ask-args (atom nil)]
        (with-mock-llm {:ask (fn [_router opts]
                               (reset! ask-args opts)
                               (make-mock-ask-response
                                 {:correct? true
                                  :overall-score 0.95
                                  :summary "Verified."
                                  :criteria []
                                  :issues []}))}

          (llm/eval! test-router {:task "What is 2+2?"
                                  :output "4"
                                  :model "gpt-4o"
                                  :ground-truths ["2+2 equals 4"]})
                    ;; System message should contain ground_truths section
          (let [sys-content (extract-system-content (:messages @ask-args))]
            (expect (some? (re-find #"ground_truths" sys-content)))
            (expect (some? (re-find #"2\+2 equals 4" sys-content))))))))

  (describe "issues reporting"
    (it "includes issues when present"
      (with-mock-llm {:ask (fn [_router _opts]
                             (make-mock-ask-response
                               {:correct? false
                                :overall-score 0.3
                                :summary "Major error."
                                :criteria [{:name "accuracy" :score 0.1 :confidence 0.95 :reasoning "Wrong"}]
                                :issues [{:issue "Answer is incorrect"
                                          :severity "high"
                                          :confidence 0.95
                                          :reasoning "2+2 is not 5"}]}))}

        (let [result (llm/eval! test-router {:task "What is 2+2?"
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
      (with-mock-llm {:ask (let [original llm/ask!]
                                       ;; We can't easily mock the full ask! pipeline,
                                       ;; so test apply-spec-humanizer directly via the
                                       ;; public humanizer + spec field metadata.
                             original)}

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
        (with-mock-llm {:ask (fn [_router opts]
                               (reset! ask-args opts)
                               (make-mock-ask-response
                                 {:correct? true
                                  :overall-score 0.9
                                  :summary "Good."
                                  :criteria []
                                  :issues []}))}

          (llm/eval! test-router {:messages [(svar/user "What is the capital of France?")]
                                  :output "Paris"
                                  :model "gpt-4o"})
                    ;; The user message content from eval task should contain the task text
          (let [user-msg (->> (:messages @ask-args)
                           (filter #(= "user" (:role %)))
                           first :content)]
            (expect (some? (re-find #"capital of France" user-msg)))))))

    (it "extracts from system + user messages (non-assistant, joined)"
      (let [ask-args (atom nil)]
        (with-mock-llm {:ask (fn [_router opts]
                               (reset! ask-args opts)
                               (make-mock-ask-response
                                 {:correct? true
                                  :overall-score 0.9
                                  :summary "Good."
                                  :criteria []
                                  :issues []}))}

          (llm/eval! test-router {:messages [(svar/system "You are a geography expert.")
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
        (with-mock-llm {:ask (fn [_router opts]
                               (reset! ask-args opts)
                               (make-mock-ask-response
                                 {:correct? true
                                  :overall-score 0.9
                                  :summary "Good."
                                  :criteria []
                                  :issues []}))}

          (llm/eval! test-router {:task "What is 2+2?"
                                  :output "4"
                                  :model "gpt-4o"})
          (let [user-msg (->> (:messages @ask-args)
                           (filter #(= "user" (:role %)))
                           first :content)]
            (expect (some? (re-find #"2\+2" user-msg)))))))

    (it ":task takes precedence when both :task and :messages provided"
      (let [ask-args (atom nil)]
        (with-mock-llm {:ask (fn [_router opts]
                               (reset! ask-args opts)
                               (make-mock-ask-response
                                 {:correct? true
                                  :overall-score 0.9
                                  :summary "Good."
                                  :criteria []
                                  :issues []}))}

          (llm/eval! test-router {:task "explicit task text"
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
      (with-mock-llm {:ask (fn [_router _opts]
                             (make-mock-ask-response
                               {:correct? true
                                :overall-score 0.92
                                :summary "Accurate."
                                :criteria [{:name "accuracy" :score 0.95 :confidence 0.9 :reasoning "OK"}]
                                :issues []}))}

        (let [result (llm/eval! test-router {:messages [(svar/user "Test task")]
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
      (let [result (svar/sample! test-router {:spec test-spec
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
      (with-mock-llm {:ask (fn [_router _opts]
                             (make-mock-ask-response
                               {:items [{:answer "alpha"} {:answer "beta"}]}))
                      :eval (fn [_router _opts] (make-mock-eval-response 0.95))}

        (let [result (svar/sample! test-router {:spec test-spec
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
      (with-mock-llm {:ask (fn [_router _opts]
                             (make-mock-ask-response
                               {:items [{:answer "one"} {:answer "two"} {:answer "three"}]}))
                      :eval (fn [_router _opts] (make-mock-eval-response 0.95))}

        (let [result (svar/sample! test-router {:spec test-spec
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
        (with-mock-llm {:ask (fn [_router _opts]
                               (swap! ask-call-count inc)
                               (make-mock-ask-response
                                 {:items [{:answer (str "v" @ask-call-count)}]}))
                        :eval (fn [_router _opts]
                                (let [score (first @eval-scores)]
                                  (swap! eval-scores rest)
                                  (make-mock-eval-response (or score 0.95))))}

          (let [result (svar/sample! test-router {:spec test-spec
                                                  :count 1
                                                  :model "gpt-4o"
                                                  :iterations 3
                                                  :threshold 0.9})]
                      ;; Should have run 2 iterations (first low, second high)
            (expect (= 2 (:iterations-count result)))
            (expect (true? (:converged? result)))))))

    (it "keeps best samples across iterations"
      (let [eval-call-count (atom 0)]
        (with-mock-llm {:ask (fn [_router _opts]
                               (make-mock-ask-response
                                 {:items [{:answer "sample"}]}))
                        :eval (fn [_router _opts]
                                (swap! eval-call-count inc)
                                           ;; All low scores — never converges
                                (make-mock-eval-response 0.3))}

          (let [result (svar/sample! test-router {:spec test-spec
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
        (with-mock-llm {:ask (fn [_router {:keys [messages]}]
                               (reset! captured-messages messages)
                               (make-mock-ask-response
                                 {:items [{:answer "x"}]}))
                        :eval (fn [_router _opts] (make-mock-eval-response 0.95))}

          (svar/sample! test-router {:spec test-spec
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
        (with-mock-llm {:ask (fn [_router _opts]
                               (swap! ask-call-count inc)
                               (make-mock-ask-response
                                 {:items [{:answer "only"}]}))
                        :eval (fn [_router _opts] (make-mock-eval-response 0.1))}

          (let [result (svar/sample! test-router {:spec test-spec
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
  "Returns true if LLM integration tests should run."
  []
  (or (some? (System/getenv "BLOCKETHER_LLM_API_KEY"))
    (some? (System/getenv "BLOCKETHER_OPENAI_API_KEY"))
    (some? (System/getenv "OPENAI_API_KEY"))))

(defn- make-integration-router
  "Creates a router for real LLM calls from env vars.
   Asserts if no API key — integration tests must have credentials."
  []
  (let [api-key (or (System/getenv "BLOCKETHER_LLM_API_KEY")
                  (System/getenv "BLOCKETHER_OPENAI_API_KEY")
                  (System/getenv "OPENAI_API_KEY"))
        _ (assert api-key "Set BLOCKETHER_LLM_API_KEY, BLOCKETHER_OPENAI_API_KEY, or OPENAI_API_KEY to run integration tests")
        base-url (or (System/getenv "BLOCKETHER_LLM_API_BASE_URL")
                   (System/getenv "BLOCKETHER_OPENAI_BASE_URL")
                   (System/getenv "OPENAI_BASE_URL")
                   "https://api.openai.com/v1")]
    (svar/make-router [{:id :integration
                        :api-key api-key
                        :base-url base-url
                        :models [{:name "gpt-4o"}]}])))

;; =============================================================================
;; Chain of Density — Unit Tests (no API key needed)
;; =============================================================================

(defdescribe cod-prompt-building-test
  (describe "build-cod-first-iteration-objective"
    (it "includes entity criteria, target length, and output requirements"
      (let [build (#'llm/build-cod-first-iteration-objective 80 nil)
            build-with-instr (#'llm/build-cod-first-iteration-objective 60 "Focus on dates")]
        (expect (str/includes? build "<chain_of_density_iteration>"))
        (expect (str/includes? build "~80 words"))
        (expect (str/includes? build "<entity_criteria>"))
        (expect (str/includes? build "atomic"))
        (expect (str/includes? build "<output_requirements>"))
          ;; Without special instructions, no special_instructions block
        (expect (not (str/includes? build "<special_instructions>")))
          ;; With special instructions, block is present
        (expect (str/includes? build-with-instr "<special_instructions>"))
        (expect (str/includes? build-with-instr "Focus on dates")))))

  (describe "build-cod-subsequent-iteration-objective"
    (it "includes novel criterion inside entity_criteria and process steps"
      (let [build (#'llm/build-cod-subsequent-iteration-objective 80 nil)]
        (expect (str/includes? build "<process>"))
        (expect (str/includes? build "do NOT re-extract"))
        (expect (str/includes? build "novel"))
          ;; Novel criterion must be inside entity_criteria (not orphaned)
        (let [criteria-start (str/index-of build "<entity_criteria>")
              criteria-end (str/index-of build "</entity_criteria>")
              novel-pos (str/index-of build "novel")]
          (expect (some? criteria-start))
          (expect (some? criteria-end))
          (expect (< criteria-start novel-pos criteria-end))))))

  (describe "build-cod-task"
    (it "wraps source text in XML and adds context for subsequent iterations"
      (let [first-iter (#'llm/build-cod-task "Hello world" nil nil)
            subsequent (#'llm/build-cod-task "Hello world" "Previous summary" ["entity1" "entity2"])]
          ;; First iteration: only source text
        (expect (str/includes? first-iter "<source_text>"))
        (expect (str/includes? first-iter "Hello world"))
        (expect (not (str/includes? first-iter "<previous_summary>")))
        (expect (not (str/includes? first-iter "<already_extracted_entities>")))
          ;; Subsequent: includes previous summary and accumulated entities
        (expect (str/includes? subsequent "<previous_summary>"))
        (expect (str/includes? subsequent "Previous summary"))
        (expect (str/includes? subsequent "<already_extracted_entities>"))
        (expect (str/includes? subsequent "entity1, entity2")))))

  (describe "build-cod-spec"
    (it "produces a spec with :entities and :summary fields"
      (let [spec (#'llm/build-cod-spec)
            prompt (spec/spec->prompt spec)]
        (expect (str/includes? prompt "entities"))
        (expect (str/includes? prompt "summary"))
        (expect (str/includes? prompt "entity"))
        (expect (str/includes? prompt "rationale"))
        (expect (str/includes? prompt "score"))))))

;; Rich factual text about the Voyager missions — dense with entities, dates,
;; distances, organizations, and scientific concepts. Perfect for CoD testing.
;; `abstract!-integration-test` removed: every case in the block called
;; `svar/abstract!` against the Blockether LiteLLM proxy with gpt-4o
;; (Voyager / CRISPR / scored / refined / target-length variants). The
;; proxy intermittently returns truncated JSON, HTTP 500 from a downstream
;; Copilot auth hiccup, or empty content under load — every flake
;; reproduced in CI was an upstream infra problem, never an svar
;; regression. `abstract!-baseline-test` covers the same code path with
;; mocked LLM responses; once we have a deterministic recording fixture
;; for Blockether One we can put the integration coverage back behind it.

;; =============================================================================
;; Streaming Integration Tests
;; =============================================================================

(defn- blockether-one-enabled?
  "Returns true if Blockether One LLM endpoint is configured."
  []
  (some? (System/getenv "BLOCKETHER_LLM_API_KEY")))

(defdescribe blockether-one-glm-test
  (describe "ask! with glm-5.1 via Blockether One"
    (it "parses structured JSON from GLM 5.1"
      (when (blockether-one-enabled?)
        (let [router (svar/make-router [{:id :blockether-integration
                                         :api-key (System/getenv "BLOCKETHER_LLM_API_KEY")
                                         :base-url (or (System/getenv "BLOCKETHER_LLM_API_BASE_URL")
                                                     "https://llm.blockether.com/v1")
                                         :models [{:name "glm-5.1"}]}])
              person-spec (svar/spec
                            (svar/field svar/NAME :name
                              svar/TYPE svar/TYPE_STRING
                              svar/CARDINALITY svar/CARDINALITY_ONE
                              svar/DESCRIPTION "Full name")
                            (svar/field svar/NAME :age
                              svar/TYPE svar/TYPE_INT
                              svar/CARDINALITY svar/CARDINALITY_ONE
                              svar/DESCRIPTION "Age in years"))
              result (svar/ask! router {:spec person-spec
                                        :messages [(svar/system "Extract person data from the text.")
                                                   (svar/user "Alexander is 18 years old.")]
                                        :model "glm-5.1"})]
          ;; ask! returns {:result <data> :tokens :cost :duration-ms}
          (expect (map? result))
          (expect (some? (:result result)))
          (expect (some? (:tokens result)))
          (expect (some? (:cost result)))
          (expect (pos? (:duration-ms result)))

          ;; Parsed data matches expected structure
          (let [data (:result result)]
            (expect (= "Alexander" (:name data)))
            (expect (= 18 (:age data)))))))))

;; =============================================================================
;; Streaming Integration Tests (real LLM calls)
;; =============================================================================

(def ^:private streaming-spec
  (svar/spec
    (svar/field svar/NAME :title       svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_ONE svar/DESCRIPTION "Title of the discovery")
    (svar/field svar/NAME :scientist   svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_ONE svar/DESCRIPTION "Name of the scientist")
    (svar/field svar/NAME :year        svar/TYPE svar/TYPE_INT    svar/CARDINALITY svar/CARDINALITY_ONE svar/DESCRIPTION "Year of discovery")
    (svar/field svar/NAME :significance svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_ONE svar/DESCRIPTION "Why this discovery matters")
    (svar/field svar/NAME :related-fields svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_MANY svar/DESCRIPTION "Scientific fields impacted")))

(def ^:private event-spec
  (svar/spec
    (svar/field svar/NAME :event        svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_ONE  svar/DESCRIPTION "Name of the historical event")
    (svar/field svar/NAME :date         svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_ONE  svar/DESCRIPTION "Date or date range")
    (svar/field svar/NAME :location     svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_ONE  svar/DESCRIPTION "Geographic location")
    (svar/field svar/NAME :key-figures  svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_MANY svar/DESCRIPTION "Important people involved")
    (svar/field svar/NAME :causes       svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_MANY svar/DESCRIPTION "Main causes or triggers")
    (svar/field svar/NAME :consequences svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_MANY svar/DESCRIPTION "Major consequences and lasting impact")
    (svar/field svar/NAME :death-toll   svar/TYPE svar/TYPE_INT    svar/CARDINALITY svar/CARDINALITY_ONE  svar/DESCRIPTION "Estimated number of casualties")))

(defdescribe streaming-integration-test
  (describe "streaming penicillin extraction"
    (it "streams partial spec fields progressively"
      (when (integration-tests-enabled?)
        (let [router (make-integration-router)
              chunks (atom [])
              result (svar/ask! router
                       {:spec streaming-spec
                        :messages [(svar/system "Extract the requested information.")
                                   (svar/user "In 1928, Alexander Fleming discovered penicillin when mold contaminated his petri dish and killed bacteria. This revolutionized medicine with antibiotics, saving millions. It impacted microbiology, pharmacology, infectious disease medicine, and public health.")]
                        :model "gpt-4o"
                        :on-chunk (fn [chunk]
                                    (swap! chunks conj chunk)
                                    (println "CHUNK" (count @chunks) "=>" (:result chunk)))})]
          (println "\n=== STREAMING RESULT ===")
          (println "Total chunks:" (count @chunks))
          (println "Final:" (:result result))
          (println "Tokens:" (:tokens result) "Cost:" (:cost result))
          (println "========================\n")
          (expect (some? (:result result)))
          (expect (string? (get-in result [:result :title])))
          (expect (integer? (get-in result [:result :year])))
          (expect (pos? (count @chunks)))
          ;; Reasoning-only chunks may have :result nil; content chunks must have :result
          (expect (some :result @chunks))))))

  (describe "streaming French Revolution extraction (7 fields)"
    (it "streams a complex multi-field spec"
      (when (integration-tests-enabled?)
        (let [router (make-integration-router)
              chunks (atom [])
              result (svar/ask! router
                       {:spec event-spec
                        :messages [(svar/system "Extract detailed structured information about the historical event.")
                                   (svar/user "The French Revolution began in 1789 at Versailles. Fiscal crisis, crop failures, and Enlightenment ideals fueled anger against the monarchy. Key figures: Louis XVI, Marie Antoinette, Robespierre, Danton, Lafayette. The Bastille fell July 14 1789. Results: abolition of feudalism, Declaration of Rights of Man, execution of Louis XVI in 1793, Reign of Terror (~17,000 executions), Napoleon's rise. Total death toll ~40,000. It transformed French society, inspired democracy worldwide, created the metric system and modern citizenship.")]
                        :model "gpt-4o"
                        :on-chunk (fn [chunk]
                                    (swap! chunks conj chunk)
                                    (let [r (:result chunk)
                                          filled (count (filter some? (vals r)))]
                                      (println (str "CHUNK " (count @chunks)
                                                 " [" filled "/7] "
                                                 "event=" (:event r)
                                                 " toll=" (:death-toll r)))))})]
          (println "\n=== COMPLEX STREAMING ===")
          (println "Total chunks:" (count @chunks))
          (println "Final:" (:result result))
          (println "Tokens:" (:tokens result) "Cost:" (:cost result))
          (println "=========================\n")
          (expect (some? (:result result)))
          (expect (string? (get-in result [:result :event])))
          (expect (vector? (get-in result [:result :key-figures])))
          (expect (vector? (get-in result [:result :consequences])))
          (expect (integer? (get-in result [:result :death-toll])))
          (expect (pos? (count @chunks)))
          ;; Reasoning-only chunks may have :result nil; content chunks must have :result
          (expect (some #(map? (:result %)) @chunks)))))))
