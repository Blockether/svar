(ns com.blockether.svar.internal.llm-output-budget-test
  "Output-budget recovery (Blockether/vis#296): every wire reports an exhausted
   output budget as `:svar.llm/max-tokens-exceeded`, and `ask-code!` re-sends a
   reasoning-only failure once with a doubled budget, bounded by the model's
   output ceiling and context window.

   Covers:
     - re-send budget and limit arithmetic
     - routed Chat: heal, exhaust, ceiling reached, caller cancellation
     - routed Responses over the real SSE parser: raised `max_output_tokens`, and
       no re-send on Codex, which strips the control"
  (:require [babashka.http-client :as http]
            [charred.api :as json]
            [lazytest.core :refer [defdescribe describe expect it]]
            [com.blockether.svar.core :as svar]
            [com.blockether.svar.internal.llm :as sut]
            [com.blockether.svar.internal.router :as router]))

(def ^:private resend-budget @#'sut/output-budget-resend-budget)

(def ^:private budget-limit @#'sut/output-budget-limit)

;; Deterministic pricing (USD per 1M tokens); routed calls otherwise consult the
;; live models.dev catalog.
(defn- fixed-pricing [_ _] {:input 10.0 :output 20.0})

(defn- approx= [a b] (< (Math/abs (- (double a) (double b))) 1e-9))

(defn- thrown-data [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- retry-events
  [events]
  (->> events
       (filter #(= :llm.routing/provider-retry (:event/type %)))
       (mapv #(select-keys % [:reason :attempt :max-retries :max-output-tokens]))))

(defdescribe
  output-budget-arithmetic-test
  (describe
    "re-send budget"
    (it "doubles the exhausted budget when only reasoning streamed"
        (expect (= 2000
                   (resend-budget {:max-output-tokens 1000 :api-usage {:output-tokens 1000}} nil))))
    (it "doubles the billed output when it exceeds the sent budget"
        (expect (= 3000
                   (resend-budget {:max-output-tokens 1000 :api-usage {:output-tokens 1500}} nil))))
    (it "stops at the limit" (expect (= 1500 (resend-budget {:max-output-tokens 1000} 1500))))
    (it "gives up when the limit leaves no room above the exhausted budget"
        (expect (nil? (resend-budget {:max-output-tokens 1000} 1000))))
    (it "gives up when the request carried no budget"
        (expect (nil? (resend-budget {:max-output-tokens nil} nil))))
    (it "gives up after answer text or a tool call"
        (expect (nil? (resend-budget {:max-output-tokens 1000 :partial-content "Part"} nil)))
        (expect (nil? (resend-budget {:max-output-tokens 1000 :content-acc-len 4} nil)))
        (expect (nil? (resend-budget {:max-output-tokens 1000 :tool-call-count 1} nil)))))
  (describe "re-send limit"
            (it "takes the smaller of the output ceiling and the context room"
                (expect (= 4000 (budget-limit 4000 100000 {:api-usage {:input-tokens 1000}})))
                (expect (= 3000 (budget-limit 65536 5000 {:api-usage {:input-tokens 2000}}))))
            (it "uses only the bounds that are known"
                (expect (= 4000 (budget-limit 4000 nil {})))
                (expect (= 3000 (budget-limit nil 5000 {:api-usage {:input-tokens 2000}})))
                (expect (nil? (budget-limit nil 5000 {}))))))

;; =============================================================================
;; Routed Chat: mock `chat-completion`, record each request's extra body
;; =============================================================================

(defn- mock-chat
  "with-redefs target for `llm/chat-completion`: returns `replies` in order (the
   last one repeats) and records each request's extra body in `bodies`."
  [replies bodies]
  (fn [_messages _model _api-key _url retry-opts]
    (let [n (count (swap! bodies conj (:extra-body retry-opts)))]
      (get replies (dec n) (peek replies)))))

(defn- chat-router
  [model]
  (svar/make-router [{:id :test
                      :api-key "sk-test"
                      :base-url "https://gateway.example.com/v1"
                      :api-style :openai-compatible-chat
                      :models [(merge {:name "test-model" :context 100000} model)]}]))

(defn- capped-reply
  "A reasoning-only Chat reply stopped by the output budget."
  [output-tokens]
  {:content ""
   :tool-calls []
   :api-usage
   {:input-tokens 1000 :output-tokens output-tokens :total-tokens (+ 1000 (long output-tokens))}
   :http-response {:status 200}
   :stream-finalization {:finish-reason "length"}})

(def ^:private chat-answer
  {:content "done"
   :tool-calls []
   :api-usage {:input-tokens 1000 :output-tokens 10 :total-tokens 1010}
   :http-response {:status 200}
   :stream-finalization {:finish-reason "stop"}})

(def ^:private budget-model {:output-limit 1000 :output-ceiling 4000})

(defdescribe
  chat-output-budget-resend-test
  (it "re-sends once with a doubled budget and bills the discarded attempt"
      (let [bodies
            (atom [])

            events
            (atom [])]

        (with-redefs [router/provider-model-pricing
                      fixed-pricing

                      sut/chat-completion
                      (mock-chat [(capped-reply 1000) chat-answer] bodies)]

          (let [result (svar/ask-code! (chat-router budget-model)
                                       {:messages [(svar/user "Say done.")]
                                        :on-chunk #(swap! events conj %)})]
            (expect (= "done" (:content result)))
            (expect (= [1000 2000] (mapv :max_tokens @bodies)))
            (expect (= 1 (:output-budget-resends result)))
            (expect (= {:input-tokens 1000 :output-tokens 1000 :total-tokens 2000}
                       (:output-budget-resend-usage result)))
            ;; (2000 in * $10/M) + (1010 out * $20/M) = 0.0402
            (expect (approx= 0.0402 (get-in result [:cost :total-cost])))
            (expect (= [{:reason :output-budget-exhausted
                         :attempt 1
                         :max-retries 1
                         :max-output-tokens 2000}]
                       (retry-events @events)))))))
  (it "stops after one re-send with the typed failure and its evidence"
      (let [bodies (atom [])]
        (with-redefs [router/provider-model-pricing fixed-pricing
                      sut/chat-completion (mock-chat [(capped-reply 1000) (capped-reply 2000)]
                                                     bodies)]

          (let [data (thrown-data #(svar/ask-code! (chat-router budget-model)
                                                   {:messages [(svar/user "Say done.")]}))]
            (expect (= [1000 2000] (mapv :max_tokens @bodies)))
            (expect (= :svar.llm/max-tokens-exceeded (:type data)))
            (expect (= 1 (:output-budget-resends data)))
            (expect (= 2000 (:max-output-tokens data)))
            (expect (= 4000 (:output-ceiling data)))
            (expect (= 2000 (get-in data [:api-usage :output-tokens])))
            (expect (= {:input-tokens 1000 :output-tokens 1000 :total-tokens 2000}
                       (:output-budget-resend-usage data)))))))
  (it "does not re-send a budget that already equals the output ceiling"
      (let [bodies (atom [])]
        (with-redefs [router/provider-model-pricing fixed-pricing
                      sut/chat-completion (mock-chat [(capped-reply 4000) chat-answer] bodies)]

          (let [data (thrown-data #(svar/ask-code! (chat-router {:output-limit 4000
                                                                 :output-ceiling 4000})
                                                   {:messages [(svar/user "Say done.")]}))]
            (expect (= [4000] (mapv :max_tokens @bodies)))
            (expect (= :svar.llm/max-tokens-exceeded (:type data)))
            (expect (= 0 (:output-budget-resends data)))
            (expect (not (contains? data :output-budget-resend-usage)))))))
  (it "does not re-send after the caller cancels"
      (let [bodies
            (atom [])

            cancelled
            (atom false)

            chat
            (mock-chat [(capped-reply 1000) chat-answer] bodies)]

        (with-redefs [router/provider-model-pricing
                      fixed-pricing

                      sut/chat-completion
                      (fn [& args]
                        (reset! cancelled true)
                        (apply chat args))]

          (let [data (thrown-data #(svar/ask-code! (chat-router budget-model)
                                                   {:messages [(svar/user "Say done.")]
                                                    :cancel-fn (fn []
                                                                 @cancelled)}))]
            (expect (= 1 (count @bodies)))
            (expect (= :svar.llm/max-tokens-exceeded (:type data)))
            (expect (= 0 (:output-budget-resends data))))))))

;; =============================================================================
;; Routed Responses: stub `http/post` so the real SSE parser raises the failure
;; =============================================================================

(defn- sse-body
  [events]
  (java.io.ByteArrayInputStream.
    (.getBytes ^String (apply str (map #(str "data: " (json/write-json-str %) "\n\n") events))
               "UTF-8")))

(defn- stub-responses
  "with-redefs target for `http/post`: streams `replies` in order (the last one
   repeats) and records each parsed request body in `bodies`."
  [replies bodies]
  (fn [_ opts]
    (let [n (count (swap! bodies conj (json/read-json (:body opts))))]
      {:status 200 :body (sse-body (get replies (dec n) (peek replies)))})))

(defn- responses-router
  [provider]
  (svar/make-router
    [(merge {:id :fixture
             :api-key "test"
             :base-url "https://gateway.example.com/v1"
             :api-style :openai-compatible-responses
             :models
             [{:name "gpt-6-sol" :context 272000 :output-limit 32768 :output-ceiling 65536}]}
            provider)]))

(def ^:private responses-capped
  [{"type" "response.incomplete"
    "response" {"incomplete_details" {"reason" "max_output_tokens"}
                "usage" {"input_tokens" 1000
                         "output_tokens" 32768
                         "output_tokens_details" {"reasoning_tokens" 32768}}}}])

(def ^:private responses-answer
  [{"type" "response.output_text.delta" "delta" "Done."}
   {"type" "response.completed"
    "response" {"status" "completed" "usage" {"input_tokens" 1000 "output_tokens" 4}}}])

(defdescribe
  responses-output-budget-resend-test
  (it "raises max_output_tokens on the re-send of a streaming call"
      (let [bodies
            (atom [])

            events
            (atom [])]

        (with-redefs [router/provider-model-pricing
                      fixed-pricing

                      http/post
                      (stub-responses [responses-capped responses-answer] bodies)]

          (let [result (svar/ask-code! (responses-router {})
                                       {:messages [(svar/user "Reply briefly")]
                                        :on-chunk #(swap! events conj %)})]
            (expect (= "Done." (:content result)))
            (expect (= [32768 65536] (mapv #(get % "max_output_tokens") @bodies)))
            (expect (every? #(not (contains? % "max_tokens")) @bodies))
            (expect (= 1 (:output-budget-resends result)))
            (expect (= [{:reason :output-budget-exhausted
                         :attempt 1
                         :max-retries 1
                         :max-output-tokens 65536}]
                       (retry-events @events)))))))
  (it "does not re-send on Codex, which strips the budget control"
      (let [bodies (atom [])]
        (with-redefs [router/provider-model-pricing fixed-pricing
                      http/post (stub-responses [responses-capped responses-answer] bodies)]

          (let [data (thrown-data #(svar/ask-code! (responses-router {:responses-path
                                                                      "/codex/responses"})
                                                   {:messages [(svar/user "Reply briefly")]}))]
            (expect (= 1 (count @bodies)))
            (expect (not (contains? (first @bodies) "max_output_tokens")))
            (expect (= :svar.llm/max-tokens-exceeded (:type data)))
            (expect (nil? (:max-output-tokens data)))
            (expect (= 0 (:output-budget-resends data))))))))
