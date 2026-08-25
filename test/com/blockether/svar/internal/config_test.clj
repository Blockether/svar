(ns com.blockether.svar.internal.config-test
  "Tests for router creation and defaults."
  (:require [lazytest.core :refer [defdescribe describe expect it]]
            [com.blockether.svar.internal.router :as defaults]
            [com.blockether.svar.internal.llm :as llm]
            [com.blockether.svar.core :as svar]))

(defdescribe
  make-router-test
  "Tests for make-router function"
  (describe
    "with explicit providers"
    (it "creates router with a single provider"
        (let [r (llm/make-router [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}])]
          (expect (= 1 (count (:providers r))))
          (expect (some? (:state r)))
          (expect (= :openai (:id (first (:providers r)))))))
    (it "creates router with multiple providers"
        (let [r (llm/make-router
                  [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}
                   {:id :anthropic :api-key "sk-test2" :models [{:name "claude-sonnet-4-6"}]}])]
          (expect (= 2 (count (:providers r))))))
    (it "sets default network timeouts"
        (let [r (llm/make-router [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}])]
          (expect (= defaults/DEFAULT_TIMEOUT_MS (get-in r [:network :timeout-ms])))
          (expect (= defaults/DEFAULT_FIRST_BYTE_TIMEOUT_MS
                     (get-in r [:network :first-byte-timeout-ms])))))
    (it "allows custom timeouts via :network"
        (let [r (llm/make-router [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}]
                                 {:network {:timeout-ms 300000 :first-byte-timeout-ms 600000}})]
          (expect (= 300000 (get-in r [:network :timeout-ms])))
          (expect (= 600000 (get-in r [:network :first-byte-timeout-ms])))))
    (it "sets check-context? to true by default in :tokens"
        (let [r (llm/make-router [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}])]
          (expect (true? (get-in r [:tokens :check-context?])))))
    (it "allows disabling check-context? via :tokens"
        (let [r (llm/make-router [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}]
                                 {:tokens {:check-context? false}})]
          (expect (false? (get-in r [:tokens :check-context?])))))
    (it "merges network over defaults"
        (let [r (llm/make-router [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}]
                                 {:network {:max-retries 10}})]
          (expect (= 10 (get-in r [:network :max-retries])))
          ;; Other defaults preserved
          (expect (= 1000 (get-in r [:network :initial-delay-ms])))))
    (it "merges pricing over defaults in :tokens"
        (let [r (llm/make-router [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}]
                                 {:tokens {:pricing {"my-model" {:input 1.0 :output 2.0}}}})]
          (expect (= {:input 1.0 :output 2.0} (get-in r [:tokens :pricing "my-model"])))
          ;; Built-in defaults still present
          (expect (some? (get-in r [:tokens :pricing "gpt-4o"])))))
    (it "merges context-limits over defaults in :tokens"
        (let [r (llm/make-router [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}]
                                 {:tokens {:context-limits {"my-model" 65536}}})]
          (expect (= 65536 (get-in r [:tokens :context-limits "my-model"])))
          ;; Built-in defaults still present
          (expect (some? (get-in r [:tokens :context-limits "gpt-4o"]))))))
  (describe "with invalid params"
            (it "throws on empty providers"
                (try (llm/make-router [])
                     (expect false "Should have thrown")
                     (catch clojure.lang.ExceptionInfo e
                       (expect (= :svar/no-providers (:type (ex-data e)))))))
            (it "throws on non-sequential providers"
                (try (llm/make-router {:id :openai})
                     (expect false "Should have thrown")
                     (catch clojure.lang.ExceptionInfo e
                       (expect (= :svar/invalid-providers (:type (ex-data e))))))))
  (describe
    "budget configuration"
    (it "creates router with budget"
        (let [r (llm/make-router [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}]
                                 {:budget {:max-tokens 1000000 :max-cost 5.0}})]
          (expect (= {:max-tokens 1000000 :max-cost 5.0} (:budget r)))
          (expect (some? (:budget-state r)))))
    (it "creates router without budget by default"
        (let [r (llm/make-router [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}])]
          (expect (nil? (:budget r)))
          (expect (nil? (:budget-state r))))))
  (describe
    "circuit breaker configuration"
    (it "uses default CB thresholds"
        (let [r (llm/make-router [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}])]
          (expect (= 5 (:failure-threshold r)))
          (expect (= 60000 (:recovery-ms r)))))
    (it "allows custom CB thresholds"
        (let [r (llm/make-router [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}]
                                 {:failure-threshold 3 :recovery-ms 30000})]
          (expect (= 3 (:failure-threshold r)))
          (expect (= 30000 (:recovery-ms r)))))))

(defdescribe
  router-stats-test
  "Tests for router-stats function"
  (it "returns empty stats for fresh router"
      (let [r
            (llm/make-router [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}])

            stats
            (llm/router-stats r)]

        (expect (= 0 (get-in stats [:total :requests])))
        (expect (= 0 (get-in stats [:total :tokens])))
        (expect (= :closed (get-in stats [:providers :openai :circuit-breaker])))))
  (it "includes budget info when budget configured"
      (let [r
            (llm/make-router [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}]
                             {:budget {:max-tokens 1000 :max-cost 1.0}})

            stats
            (llm/router-stats r)]

        (expect (some? (:budget stats)))
        (expect (= 0 (get-in stats [:budget :spent :total-tokens]))))))

(defdescribe
  prompt-cache-metric-test
  "Svar-owned, route-local provider prompt-cache telemetry."
  (it
    "reports token-weighted reads separately from request hit frequency"
    (let [now
          (atom 1000)

          router
          (llm/make-router [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}]
                           {:clock #(long @now)})

          complete!
          (fn [scope input cached]
            (defaults/with-provider-fallback
              router
              {:force-provider :openai :force-model "gpt-4o" :prompt-cache-scope scope}
              (fn [_ _]
                {:api-usage {:input-tokens input
                             :output-tokens 0
                             :total-tokens input
                             :input-tokens-details
                             {:regular (- input cached) :cache-write 0 :cache-read cached}}})))

          _
          (complete! "session-1" 10000 9000)

          second-result
          (complete! "session-1" 1000 0)

          status
          (svar/prompt-cache-status router "session-1" :openai "gpt-4o")]

      (expect (= status (:prompt-cache second-result)))
      (expect (= :provider-prompt-cache (:kind status)))
      (expect (= :openai (:provider-id status)))
      (expect (= "gpt-4o" (:model status)))
      (expect (true? (:fresh? status)))
      (expect (= 0 (:latest-age-ms status)))
      (expect (= 8 (:window-size status)))
      (expect (= 2 (:sample-count status)))
      (expect (= 1 (:hit-requests status)))
      (expect (= 11000 (:input-tokens status)))
      (expect (= 9000 (:cache-read-tokens status)))
      (expect (= 82 (:token-read-percent status)))
      (expect (= 50 (:request-hit-percent status)))
      (expect (= status (svar/prompt-cache-status router)))
      (complete! "session-2" 500 0)
      (expect (= 1 (:sample-count (svar/prompt-cache-status router "session-2" :openai "gpt-4o"))))
      (expect (= 2
                 (:sample-count (svar/prompt-cache-status router "session-1" :openai "gpt-4o"))))))
  (it
    "expires stale observations, bounds the window, and isolates model routes"
    (let [now
          (atom 1000)

          router
          (llm/make-router
            [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"} {:name "gpt-4.1"}]}]
            {:clock #(long @now) :prompt-cache {:window-size 2 :fresh-for-ms 1000}})

          complete!
          (fn [model input cached]
            (defaults/with-provider-fallback
              router
              {:force-provider :openai :force-model model :prompt-cache-scope "session-1"}
              (fn [_ _]
                {:api-usage {:input-tokens input
                             :output-tokens 0
                             :total-tokens input
                             :input-tokens-details
                             {:regular (- input cached) :cache-write 0 :cache-read cached}}})))]

      (complete! "gpt-4o" 100 100)
      (swap! now + 100)
      (complete! "gpt-4o" 100 0)
      (swap! now + 100)
      (complete! "gpt-4o" 100 100)
      (expect (= 2 (:sample-count (svar/prompt-cache-status router "session-1" :openai "gpt-4o"))))
      (expect (= 50
                 (:request-hit-percent
                   (svar/prompt-cache-status router "session-1" :openai "gpt-4o"))))
      (expect (nil? (svar/prompt-cache-status router "session-1" :openai "gpt-4.1")))
      (swap! now + 1001)
      (let [stale (svar/prompt-cache-status router "session-1" :openai "gpt-4o")]
        (expect (false? (:fresh? stale)))
        (expect (= 1001 (:latest-age-ms stale)))
        (expect (= 0 (:sample-count stale)))
        (expect (nil? (:token-read-percent stale)))
        (expect (nil? (:request-hit-percent stale))))
      (complete! "gpt-4.1" 200 0)
      (let [fresh (svar/prompt-cache-status router)]
        (expect (= "gpt-4.1" (:model fresh)))
        (expect (true? (:fresh? fresh)))
        (expect (= 1 (:sample-count fresh)))
        (expect (= 0 (:token-read-percent fresh)))
        (expect (= 0 (:request-hit-percent fresh))))))
  (it
    "ages cache observations from request start and prunes inactive identities"
    (let [now
          (atom 1000)

          router
          (llm/make-router [{:id :anthropic :api-key "sk-test" :models [{:name "claude-test"}]}]
                           {:clock #(long @now) :prompt-cache {:window-size 8 :fresh-for-ms 1000}})

          complete!
          (fn [scope duration-ms]
            (defaults/with-provider-fallback
              router
              {:force-provider :anthropic :force-model "claude-test" :prompt-cache-scope scope}
              (fn [_ _]
                (swap! now + duration-ms)
                {:api-usage {:input-tokens 100
                             :output-tokens 0
                             :total-tokens 100
                             :input-tokens-details {:regular 0 :cache-write 0 :cache-read 100}}})))]

      (complete! "old-session" 900)
      (expect (= 900
                 (:latest-age-ms
                   (svar/prompt-cache-status router "old-session" :anthropic "claude-test"))))
      (swap! now + 101)
      (expect (false? (:fresh?
                        (svar/prompt-cache-status router "old-session" :anthropic "claude-test"))))
      (complete! "new-session" 0)
      (expect (nil? (svar/prompt-cache-status router "old-session" :anthropic "claude-test"))))))
(defdescribe reset-budget-test
             "Tests for reset-budget! function"
             (it "resets budget counters"
                 (let [r (llm/make-router
                           [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}]
                           {:budget {:max-tokens 1000 :max-cost 1.0}})]
                   ;; Simulate spend
                   (swap! (:budget-state r) assoc :total-tokens 500 :total-cost 0.5)
                   (llm/reset-budget! r)
                   (expect (= 0 (:total-tokens @(:budget-state r))))
                   (expect (= 0.0 (:total-cost @(:budget-state r)))))))

(defdescribe reset-provider-test
             "Tests for reset-provider! function"
             (it "resets circuit breaker to closed"
                 (let [r (llm/make-router
                           [{:id :openai :api-key "sk-test" :models [{:name "gpt-4o"}]}])]
                   ;; Simulate open CB
                   (swap! (:state r) assoc-in [:openai :cb-state] :open)
                   (swap! (:state r) assoc-in [:openai :cb-failures] 5)
                   (llm/reset-provider! r :openai)
                   (let [ps (get @(:state r) :openai)]
                     (expect (= :closed (:cb-state ps)))
                     (expect (= 0 (:cb-failures ps)))))))
