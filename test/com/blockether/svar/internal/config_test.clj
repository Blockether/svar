(ns com.blockether.svar.internal.config-test
  "Tests for router creation and defaults."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.defaults :as defaults]
   [com.blockether.svar.internal.llm :as llm]))

(defdescribe make-router-test
  "Tests for make-router function"

  (describe "with explicit providers"
    (it "creates router with a single provider"
      (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                                 :models [{:name "gpt-4o"}]}])]
        (expect (= 1 (count (:providers r))))
        (expect (some? (:state r)))
        (expect (= :openai (:id (first (:providers r)))))))

    (it "creates router with multiple providers"
      (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                                 :models [{:name "gpt-4o"}]}
                                {:id :anthropic :api-key "sk-test2"
                                 :models [{:name "claude-sonnet-4-6"}]}])]
        (expect (= 2 (count (:providers r))))))

    (it "sets default network timeout-ms"
      (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                                 :models [{:name "gpt-4o"}]}])]
        (expect (= defaults/DEFAULT_TIMEOUT_MS (get-in r [:network :timeout-ms])))))

    (it "allows custom timeout-ms via :network"
      (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                                 :models [{:name "gpt-4o"}]}]
                {:network {:timeout-ms 300000}})]
        (expect (= 300000 (get-in r [:network :timeout-ms])))))

    (it "sets check-context? to true by default in :tokens"
      (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                                 :models [{:name "gpt-4o"}]}])]
        (expect (true? (get-in r [:tokens :check-context?])))))

    (it "allows disabling check-context? via :tokens"
      (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                                 :models [{:name "gpt-4o"}]}]
                {:tokens {:check-context? false}})]
        (expect (false? (get-in r [:tokens :check-context?])))))

    (it "merges network over defaults"
      (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                                 :models [{:name "gpt-4o"}]}]
                {:network {:max-retries 10}})]
        (expect (= 10 (get-in r [:network :max-retries])))
        ;; Other defaults preserved
        (expect (= 1000 (get-in r [:network :initial-delay-ms])))))

    (it "merges pricing over defaults in :tokens"
      (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                                 :models [{:name "gpt-4o"}]}]
                {:tokens {:pricing {"my-model" {:input 1.0 :output 2.0}}}})]
        (expect (= {:input 1.0 :output 2.0} (get-in r [:tokens :pricing "my-model"])))
        ;; Built-in defaults still present
        (expect (some? (get-in r [:tokens :pricing "gpt-4o"])))))

    (it "merges context-limits over defaults in :tokens"
      (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                                 :models [{:name "gpt-4o"}]}]
                {:tokens {:context-limits {"my-model" 65536}}})]
        (expect (= 65536 (get-in r [:tokens :context-limits "my-model"])))
        ;; Built-in defaults still present
        (expect (some? (get-in r [:tokens :context-limits "gpt-4o"]))))))

  (describe "with invalid params"
    (it "throws on empty providers"
      (try
        (llm/make-router [])
        (expect false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (expect (= :svar/no-providers (:type (ex-data e)))))))

    (it "throws on non-sequential providers"
      (try
        (llm/make-router {:id :openai})
        (expect false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (expect (= :svar/invalid-providers (:type (ex-data e))))))))

  (describe "budget configuration"
    (it "creates router with budget"
      (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                                 :models [{:name "gpt-4o"}]}]
                {:budget {:max-tokens 1000000 :max-cost 5.0}})]
        (expect (= {:max-tokens 1000000 :max-cost 5.0} (:budget r)))
        (expect (some? (:budget-state r)))))

    (it "creates router without budget by default"
      (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                                 :models [{:name "gpt-4o"}]}])]
        (expect (nil? (:budget r)))
        (expect (nil? (:budget-state r))))))

  (describe "circuit breaker configuration"
    (it "uses default CB thresholds"
      (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                                 :models [{:name "gpt-4o"}]}])]
        (expect (= 5 (:cb-failure-threshold r)))
        (expect (= 60000 (:cb-recovery-ms r)))))

    (it "allows custom CB thresholds"
      (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                                 :models [{:name "gpt-4o"}]}]
                {:cb-failure-threshold 3 :cb-recovery-ms 30000})]
        (expect (= 3 (:cb-failure-threshold r)))
        (expect (= 30000 (:cb-recovery-ms r)))))))

(defdescribe router-stats-test
  "Tests for router-stats function"

  (it "returns empty stats for fresh router"
    (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                               :models [{:name "gpt-4o"}]}])
          stats (llm/router-stats r)]
      (expect (= 0 (get-in stats [:total :requests])))
      (expect (= 0 (get-in stats [:total :tokens])))
      (expect (= :closed (get-in stats [:providers :openai :circuit-breaker])))))

  (it "includes budget info when budget configured"
    (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                               :models [{:name "gpt-4o"}]}]
              {:budget {:max-tokens 1000 :max-cost 1.0}})
          stats (llm/router-stats r)]
      (expect (some? (:budget stats)))
      (expect (= 0 (get-in stats [:budget :spent :total-tokens]))))))

(defdescribe reset-budget-test
  "Tests for reset-budget! function"

  (it "resets budget counters"
    (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                               :models [{:name "gpt-4o"}]}]
              {:budget {:max-tokens 1000 :max-cost 1.0}})]
      ;; Simulate spend
      (swap! (:budget-state r) assoc :total-tokens 500 :total-cost 0.5)
      (llm/reset-budget! r)
      (expect (= 0 (:total-tokens @(:budget-state r))))
      (expect (= 0.0 (:total-cost @(:budget-state r)))))))

(defdescribe reset-provider-test
  "Tests for reset-provider! function"

  (it "resets circuit breaker to closed"
    (let [r (llm/make-router [{:id :openai :api-key "sk-test"
                               :models [{:name "gpt-4o"}]}])]
      ;; Simulate open CB
      (swap! (:state r) assoc-in [:openai :cb-state] :open)
      (swap! (:state r) assoc-in [:openai :cb-failures] 5)
      (llm/reset-provider! r :openai)
      (let [ps (get @(:state r) :openai)]
        (expect (= :closed (:cb-state ps)))
        (expect (= 0 (:cb-failures ps)))))))

(defdescribe default-model-test
  "Tests for DEFAULT_MODEL — no hardcoded fallback"

  (it "DEFAULT_MODEL is nil (no hardcoded fallback)"
    (expect (nil? defaults/DEFAULT_MODEL))))
