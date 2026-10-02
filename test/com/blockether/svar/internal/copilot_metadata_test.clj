(ns com.blockether.svar.internal.copilot-metadata-test
  (:require [com.blockether.svar.core :as svar]
            [com.blockether.svar.internal.llm :as llm]
            [com.blockether.svar.internal.failure :as failure]
            [lazytest.core :refer [defdescribe expect it]]))

(defn- copilot-router
  [model]
  (svar/make-router [{:id :github-copilot
                      :api-key "test"
                      :base-url "https://gateway.example.com/v1"
                      :models [model]}]))

(defn- budget-error
  [model]
  (try (svar/context-budget (copilot-router model) {})
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; Regression: Blockether/vis#304. A newly listed model must not need a catalog release.
(defdescribe
  copilot-live-capabilities
  (it "reads limits, explicit capabilities and the supported wire for a new Claude model"
      (llm/clear-models-cache!)
      (with-redefs-fn {#'llm/http-get!
                       (fn [& _]
                         {"data" [{"id" "claude-sonnet-future"
                                   "supported_endpoints" ["/v1/messages" "/chat/completions"]
                                   "capabilities" {"tokenizer" "cl100k_base"
                                                   "supports" {"vision" true
                                                               "tool_calls" true
                                                               "parallel_tool_calls" false
                                                               "reasoning" true}
                                                   "limits" {"max_context_window_tokens" 200000
                                                             "max_prompt_tokens" 168000
                                                             "max_output_tokens" 32000}}}]})}
        #(let [m (first (svar/models! (copilot-router {:name "claude-sonnet-future"}))) model
               (assoc m :name (:id m)) budget (svar/context-budget (copilot-router model) {})]
           (expect (= :anthropic (:api-style m))) (expect (true? (:vision? m))) (expect
                                                                                  (true?
                                                                                    (:tool-call?
                                                                                      m)))
           (expect (false? (:parallel-tool-calls? m))) (expect (true? (:reasoning? m)))
           (expect (= 200000 (:context budget))) (expect (= 168000 (:max-input-tokens budget)))
           (expect (= 32000 (:output-reserve budget))) (expect (> (:max-input-tokens budget)
                                                                  17177)))))
  (it "prefers Responses over chat when a newly listed GPT model supports both"
      (let [m (first (@#'llm/shape-models
                      :github-copilot
                      [{"id" "gpt-future"
                        "supported_endpoints" ["/chat/completions" "/responses"]
                        "capabilities" {"supports" {"vision" false}}}]))]
        (expect (= :openai-compatible-responses (:api-style m)))
        (expect (false? (:vision? m)))))
  (it "does not reinterpret another provider's endpoint fields as Copilot policy"
      (expect (nil? (:api-style (first (@#'llm/shape-models
                                        nil
                                        [{"supported_endpoints" ["/v1/messages"]}])))))))

(defdescribe
  copilot-missing-budget-diagnostic
  (it "reports missing Sonnet limits instead of presenting the 6144-token fallback as real"
      (let [data (budget-error {:name "claude-sonnet-5.5"})]
        (expect (= :svar.llm/model-metadata-unavailable (:type data)))
        (expect (= :github-copilot (:provider-id data)))
        (expect (= "claude-sonnet-5.5" (:model data)))
        (expect (= :missing-input-limit (:reason data)))
        (expect (nil? (:max-input-tokens data)))
        (expect (= {:category :invalid-request :retryable? false :reached-model? false}
                   (select-keys (failure/classify (ex-info "Model metadata unavailable" data))
                                [:category :retryable? :reached-model?])))))
  (it "never treats an output-only cap as an input window"
      (expect (= :missing-input-limit
                 (:reason (budget-error {:name "claude-sonnet-future" :output-limit 8192})))))
  (it "rejects malformed and contradictory limits without inventing a context window"
      (doseq [limits [{:context -1} {:input-limit "200000"} {:context 200000 :input-limit 300000}
                      {:context 200000 :output-limit 200000}]]
        (let [data (budget-error (merge {:name "claude-sonnet-future"} limits))]
          (expect (= :svar.llm/model-metadata-unavailable (:type data)))
          (expect (= :invalid-limits (:reason data))))))
  (it "accepts explicit input-only metadata without subtracting the output cap twice"
      (let [budget (svar/context-budget (copilot-router {:name "claude-sonnet-future"
                                                         :input-limit 168000
                                                         :output-limit 32000})
                                        {})]
        (expect (= 168000 (:max-input-tokens budget)))
        (expect (= 32000 (:output-reserve budget))))))
