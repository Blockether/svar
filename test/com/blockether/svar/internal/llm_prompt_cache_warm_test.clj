(ns com.blockether.svar.internal.llm-prompt-cache-warm-test
  (:require [lazytest.core :refer [defdescribe describe expect it]]
            [com.blockether.svar.core :as svar]
            [com.blockether.svar.internal.llm :as sut]
            [com.blockether.svar.internal.router :as router]))

(defn- fixed-pricing [_ _] {:input 10.0 :cache-read 1.0 :output 20.0})

(def ^:private capped-reply
  {:content ""
   :tool-calls []
   :api-usage {:input-tokens 1000
               :output-tokens 1
               :total-tokens 1001
               :input-tokens-details {:regular 0 :cache-write 0 :cache-read 1000}}
   :http-response {:status 200}
   :stream-finalization {:finish-reason "length"}})

(defn- recording-chat
  "Answers every call with `reply`, or throws it, and records each call's retry opts."
  [calls reply]
  (fn [_messages _model _api-key url retry-opts]
    (swap! calls conj (assoc retry-opts :url url))
    (if (instance? Throwable reply) (throw reply) reply)))

(defn- provider
  [id api-style]
  {:id id
   :api-key "sk-test"
   :base-url (str "https://" (name id) ".example.com/v1")
   :api-style api-style
   :models [{:name "test-model" :context 100000}]})

(defn- warm!
  [router calls reply opts]
  (with-redefs [router/provider-model-pricing
                fixed-pricing

                sut/chat-completion
                (recording-chat calls reply)]

    (svar/warm-prompt-cache! router (merge {:messages [(svar/user "Say done.")]} opts))))

(defn- thrown [f] (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(defdescribe
  warm-prompt-cache-test
  (describe "a successful warm"
            (it "sends one capped attempt without retries or streaming"
                (let [calls
                      (atom [])

                      chunks
                      (atom 0)

                      result
                      (warm! (svar/make-router [(provider :test :openai-compatible-chat)])
                             calls
                             capped-reply
                             {:on-chunk (fn [_]
                                          (swap! chunks inc))})

                      [call]
                      @calls]

                  (expect (= 1 (count @calls)))
                  (expect (= 0 (:max-retries call)))
                  (expect (= 1 (get-in call [:extra-body :max_tokens])))
                  (expect (nil? (:on-chunk call)))
                  (expect (= 0 @chunks))
                  (expect (= 1000 (get-in result [:api-usage :input-tokens-details :cache-read])))
                  (expect (= :test (:routed/provider-id result)))))
            (it "uses the Responses API minimum output budget"
                (let [calls (atom [])]
                  (warm! (svar/make-router [(provider :test :openai-compatible-responses)])
                         calls
                         capped-reply
                         {})
                  (expect (= 16 (get-in (first @calls) [:extra-body :max_tokens])))))
            (it "records the spend in the router budget"
                (let [calls
                      (atom [])

                      router
                      (svar/make-router [(provider :test :openai-compatible-chat)]
                                        {:budget {:max-tokens 1000000}})]

                  (warm! router calls capped-reply {})
                  (expect (pos? (get-in (router/router-stats router)
                                        [:budget :spent :total-tokens])))))))

(defdescribe
  warm-prompt-cache-failure-test
  (describe
    "a refused or failed warm"
    (it "refuses Anthropic budget thinking before it sends"
        (let [calls
              (atom [])

              error
              (thrown #(warm! (svar/make-router [(provider :test :anthropic)])
                              calls
                              capped-reply
                              {:extra-body {:thinking {:type "enabled" :budget_tokens 2048}}}))]

          (expect (= :svar.llm/prompt-cache-warm-unsupported (:type (ex-data error))))
          (expect (empty? @calls))))
    (it "throws a provider failure without a fallback"
        (let [calls
              (atom [])

              router
              (svar/make-router [(provider :a :openai-compatible-chat)
                                 (provider :b :openai-compatible-chat)])

              error
              (thrown #(warm! router
                              calls
                              (ex-info "Upstream failed." {:type :test/upstream :status 503})
                              {}))]

          (expect (= :test/upstream (:type (ex-data error))))
          (expect (= 1 (count @calls)))))))
