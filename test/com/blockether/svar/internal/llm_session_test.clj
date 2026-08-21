(ns com.blockether.svar.internal.llm-session-test
  "Explicit session contract for stateful OpenAI Codex Responses calls."
  (:require
   [charred.api :as json]
   [com.blockether.svar.core :as svar]
   [com.blockether.svar.internal.llm :as sut]
   [lazytest.core :refer [defdescribe expect it]])
  (:import
   (java.net.http WebSocket)
   (java.util.concurrent CompletableFuture TimeoutException)))

(def ^:private await-websocket-future!
  (ns-resolve 'com.blockether.svar.internal.llm 'await-websocket-future!))
(def ^:private close-websocket!
  (ns-resolve 'com.blockether.svar.internal.llm 'close-websocket!))

(defn- completed-event [id text]
  (json/write-json-str
    {"type" "response.completed"
     "response" {"id" id
                 "status" "completed"
                 "output" [{"id" (str "msg_" id)
                            "type" "message"
                            "role" "assistant"
                            "content" [{"type" "output_text" "text" text}]}]
                 "usage" {"input_tokens" 10 "output_tokens" 2 "total_tokens" 12}}}))

(defn- tool-call-event []
  (json/write-json-str
    {"type" "response.completed"
     "response" {"id" "resp_tool"
                 "status" "completed"
                 "output" [{"id" "fc_1"
                            "call_id" "call_1"
                            "type" "function_call"
                            "name" "run"
                            "arguments" "{\"x\":1}"}]
                 "usage" {"input_tokens" 10 "output_tokens" 2 "total_tokens" 12}}}))

(defn- fake-websocket-factory [events sent closes]
  (fn [_]
    {:send! (fn [payload]
              (swap! sent conj (json/read-json payload :key-fn keyword)))
     :receive! (fn [_] (let [event (first @events)]
                         (swap! events subvec 1)
                         event))
     :close! (fn [] (swap! closes inc))}))

(defn- test-websocket [close-future aborts]
  (reify WebSocket
    (sendText [_ _ _] (CompletableFuture/completedFuture nil))
    (sendBinary [_ _ _] (CompletableFuture/completedFuture nil))
    (sendPing [_ _] (CompletableFuture/completedFuture nil))
    (sendPong [_ _] (CompletableFuture/completedFuture nil))
    (sendClose [_ _ _] close-future)
    (request [_ _])
    (getSubprotocol [_] "")
    (isOutputClosed [_] false)
    (isInputClosed [_] false)
    (abort [_] (swap! aborts inc))))

(defn- codex-router []
  (svar/make-router
    [{:id :openai-codex
      :api-key "test-key"
      :base-url "https://chatgpt.com/backend-api"
      :api-style :openai-compatible-responses
      :responses-path "/codex/responses"
      :models [{:name "gpt-5.6" :context 100000 :input 1.0 :output 1.0}]}]))

(defdescribe websocket-resource-cleanup-test
  (it "cancels an unfinished WebSocket operation after its timeout"
    (let [future (CompletableFuture.)]
      (expect (= TimeoutException
                (try
                  (await-websocket-future! future 1)
                  nil
                  (catch Throwable e (class e)))))
      (expect (.isCancelled future))))

  (it "aborts the socket when graceful close times out"
    (let [close-future (CompletableFuture.)
          aborts (atom 0)]
      (close-websocket! (test-websocket close-future aborts) 1)
      (expect (.isCancelled close-future))
      (expect (= 1 @aborts))))

  (it "aborts the socket when graceful close fails"
    (let [close-future (doto (CompletableFuture.)
                         (.completeExceptionally (RuntimeException. "close failed")))
          aborts (atom 0)]
      (close-websocket! (test-websocket close-future aborts) 1)
      (expect (= 1 @aborts))))

  (it "does not abort the socket after a successful graceful close"
    (let [aborts (atom 0)]
      (close-websocket!
        (test-websocket (CompletableFuture/completedFuture nil) aborts))
      (expect (zero? @aborts))))

  (it "rethrows the cause behind a failed WebSocket operation"
    ;; A session that saw only the JDK's ExecutionException wrapper could not
    ;; recognize a lost connection, so it degraded the turn to HTTP instead of
    ;; reconnecting.
    (let [failed (doto (CompletableFuture.)
                   (.completeExceptionally (java.io.IOException. "connection lost")))]
      (expect (= java.io.IOException
                (try
                  (await-websocket-future! failed 100)
                  nil
                  (catch Throwable e (class e))))))))

(defdescribe codex-responses-session-test
  (it "continues a second turn with only its delta and previous response id"
    (let [events (atom [(completed-event "resp_1" "first")
                        (completed-event "resp_2" "second")])
          sent (atom [])
          closes (atom 0)]
      (with-redefs [sut/open-responses-websocket!
                    (fake-websocket-factory events sent closes)]
        (with-open [session (svar/open-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}})]
          (expect (= "first" (:content (svar/ask! session "one"))))
          (expect (= "second" (:content (svar/ask! session "two"))))
          (let [[first-request second-request] @sent]
            (expect (= "response.create" (:type first-request)))
            (expect (true? (:stream first-request)))
            (expect (nil? (:previous_response_id first-request)))
            (expect (string? (:prompt_cache_key first-request)))
            (expect (= (:prompt_cache_key first-request)
                      (:prompt_cache_key second-request)))
            (expect (= "resp_1" (:previous_response_id second-request)))
            (expect (= 1 (count (:input second-request))))
            (expect (= "two" (get-in second-request [:input 0 :content 0 :text])))))
        (expect (= 1 @closes)))))

  (it "reconnects with canonical full-history replay after transport loss"
    (let [opens (atom 0)
          sent (atom [])
          closes (atom 0)
          factory (fn [_]
                    (let [n (swap! opens inc)
                          receives (atom (if (= n 1)
                                           [(completed-event "resp_1" "first")
                                            (java.io.IOException. "connection lost")]
                                           [(completed-event "resp_2" "second")]))]
                      {:send! (fn [payload]
                                (swap! sent conj [(dec n) (json/read-json payload :key-fn keyword)]))
                       :receive! (fn [_] (let [event (first @receives)]
                                           (swap! receives subvec 1)
                                           event))
                       :close! (fn [] (swap! closes inc))}))]
      (with-redefs [sut/open-responses-websocket! factory]
        (with-open [session (svar/open-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}})]
          (svar/ask! session "one")
          (expect (= "second" (:content (svar/ask! session "two"))))
          (let [replay (-> @sent last second)]
            (expect (nil? (:previous_response_id replay)))
            (expect (= 3 (count (:input replay)))))))
      (expect (= 2 @opens))
      (expect (= 2 @closes))))

  (it "reconnects when a send fails on a lost connection"
    (let [opens (atom 0)
          http-calls (atom 0)
          sends (atom 0)
          factory (fn [_]
                    (let [n (swap! opens inc)
                          receives (atom [(completed-event (str "resp_" n) (str "turn" n))])]
                      {:send! (fn [_]
                                (when (and (= 1 n) (= 2 (swap! sends inc)))
                                  (throw (java.io.IOException. "connection lost"))))
                       :receive! (fn [_] (let [event (first @receives)]
                                           (swap! receives subvec 1)
                                           event))
                       :close! (fn [] nil)}))]
      (with-redefs [sut/open-responses-websocket! factory
                    sut/openai-responses-completion (fn [_ _] (swap! http-calls inc) nil)]
        (with-open [session (svar/open-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}})]
          (svar/ask! session "one")
          (expect (= "turn2" (:content (svar/ask! session "two"))))))
      (expect (= 2 @opens))
      (expect (zero? @http-calls))))

  (it "replays full history when the server rejects its continuation cursor"
    (let [events (atom [(completed-event "resp_1" "first")
                        (json/write-json-str
                          {"type" "error"
                           "error" {"code" "previous_response_not_found"
                                    "message" "Previous response not found"}})
                        (completed-event "resp_2" "second")])
          sent (atom [])
          closes (atom 0)]
      (with-redefs [sut/open-responses-websocket!
                    (fake-websocket-factory events sent closes)]
        (with-open [session (svar/open-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}})]
          (svar/ask! session "one")
          (expect (= "second" (:content (svar/ask! session "two"))))
          (let [[_ continuation replay] @sent]
            (expect (= "resp_1" (:previous_response_id continuation)))
            (expect (nil? (:previous_response_id replay)))
            (expect (= 3 (count (:input replay)))))))))

  (it "sends a tool result as the next incremental Responses item"
    (let [events (atom [(tool-call-event)
                        (completed-event "resp_2" "done")])
          sent (atom [])
          closes (atom 0)]
      (with-redefs [sut/open-responses-websocket!
                    (fake-websocket-factory events sent closes)]
        (with-open [session (svar/open-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}
                               :tools [{:name "run"
                                        :description "Runs a task"
                                        :schema {:type "object"}}]})]
          (let [first-result (svar/ask! session "run it")]
            (expect (= :tool-calls (:stop-reason first-result)))
            (expect (= "call_1|fc_1" (get-in first-result [:tool-calls 0 :id]))))
          (expect (= "done"
                    (:content
                     (svar/ask! session
                       {:role "user"
                        :content [{:type "tool_result"
                                   :tool_use_id "call_1|fc_1"
                                   :content "ok"}]}))))
          (let [delta-request (second @sent)]
            (expect (= "resp_tool" (:previous_response_id delta-request)))
            (expect (= [{:type "function_call_output"
                         :call_id "call_1"
                         :output "ok"}]
                      (:input delta-request))))))))

  (it "falls back to the existing HTTP Responses transport when WebSocket setup fails"
    (let [http-bodies (atom [])]
      (with-redefs [sut/open-responses-websocket!
                    (fn [_] (throw (java.io.IOException. "upgrade unavailable")))
                    sut/openai-responses-completion
                    (fn [body _]
                      (swap! http-bodies conj body)
                      {:content "http fallback"
                       :assistant-message {:role "assistant"
                                           :content [{:type "text" :text "http fallback"}]}
                       :tool-calls []
                       :api-usage {:input-tokens 10 :output-tokens 2}
                       :http-response {:status 200 :streaming? true}})]
        (with-open [session (svar/open-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}})]
          (expect (= "http fallback" (:content (svar/ask! session "one"))))
          (expect (= 1 (count @http-bodies)))
          (expect (= "one" (get-in @http-bodies [0 :input 0 :content 0 :text])))))))

  (it "rejects calls after close without sending another request"
    (let [events (atom [(completed-event "resp_1" "first")])
          sent (atom [])
          closes (atom 0)]
      (with-redefs [sut/open-responses-websocket!
                    (fake-websocket-factory events sent closes)]
        (let [session (svar/open-session (codex-router)
                        {:routing {:provider :openai-codex :model "gpt-5.6"}})]
          (svar/ask! session "one")
          (svar/close-session! session)
          (expect (= :svar.session/closed
                    (try (svar/ask! session "two") nil
                         (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
          (expect (= 1 (count @sent))))))))
