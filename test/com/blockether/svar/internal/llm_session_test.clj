(ns com.blockether.svar.internal.llm-session-test
  "Explicit session contract for stateful OpenAI Codex Responses calls."
  (:require
   [charred.api :as json]
   [com.blockether.svar.core :as svar]
   [com.blockether.svar.internal.failure :as failure]
   [com.blockether.svar.internal.llm :as sut]
   [lazytest.core :refer [defdescribe expect it]])
  (:import
   (java.net.http WebSocket)
   (java.util.concurrent CompletableFuture TimeoutException)))

(def ^:private await-websocket-future!
  (ns-resolve 'com.blockether.svar.internal.llm 'await-websocket-future!))
(def ^:private close-websocket!
  (ns-resolve 'com.blockether.svar.internal.llm 'close-websocket!))
(def ^:private websocket-event-error
  (ns-resolve 'com.blockether.svar.internal.llm 'websocket-event-error))

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

(defn- fake-websocket-factory
  ([events sent closes] (fake-websocket-factory events sent closes (atom 0)))
  ([events sent closes aborts]
   (fn [_]
     {:send! (fn [payload]
               (swap! sent conj (json/read-json payload :key-fn keyword)))
      :receive! (fn [_] (let [event (first @events)]
                          (swap! events subvec 1)
                          event))
      :close! (fn [] (swap! closes inc))
      :abort! (fn [] (swap! aborts inc))})))

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

(defn- open-test-session [router opts]
  (svar/open-session router (assoc opts :websocket-prewarm? false)))
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
        (with-open [session (open-test-session (codex-router)
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
          aborts (atom 0)
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
                       :close! (fn [] (swap! closes inc))
                       :abort! (fn [] (swap! aborts inc))}))]
      (with-redefs [sut/open-responses-websocket! factory]
        (with-open [session (open-test-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}})]
          (svar/ask! session "one")
          (expect (= "second" (:content (svar/ask! session "two"))))
          (let [replay (-> @sent last second)]
            (expect (nil? (:previous_response_id replay)))
            (expect (= 3 (count (:input replay)))))))
      (expect (= 2 @opens))
      ;; The lost connection is ABORTED, never closed by handshake: waiting for a
      ;; close frame from a socket that is already gone stalls the recovery.
      (expect (= 1 @aborts))
      (expect (= 1 @closes))))

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
                       :close! (fn [] nil)
                       :abort! (fn [] nil)}))]
      (with-redefs [sut/open-responses-websocket! factory
                    sut/openai-responses-completion (fn [_ _] (swap! http-calls inc) nil)]
        (with-open [session (open-test-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}})]
          (svar/ask! session "one")
          (expect (= "turn2" (:content (svar/ask! session "two"))))))
      (expect (= 2 @opens))
      (expect (zero? @http-calls))))

  (it "degrades a turn to HTTP when the socket cannot be opened at all"
    ;; Only an IOException - the JDK's own handshake refusal - degraded to the
    ;; stateless path. A bad URI, a changed client shape or a provider that
    ;; answers a plain error object ended the turn with no answer, although the
    ;; HTTP transport would have served it.
    (let [http-inputs (atom [])]
      (with-redefs [sut/open-responses-websocket!
                    (fn [_] (throw (IllegalArgumentException. "invalid URI")))
                    sut/openai-responses-completion
                    (fn [body _]
                      (swap! http-inputs conj (count (:input body)))
                      {:content "http answer" :api-usage {}})]
        (with-open [session (open-test-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}
                               :websocket-max-retries 1
                               :websocket-retry-delay-ms 0})]
          (expect (= "http answer" (:content (svar/ask! session "one"))))
          (expect (= "http answer" (:content (svar/ask! session "two"))))))
      ;; Each fallback turn carries the FULL canonical history, never a delta.
      (expect (= [1 3] @http-inputs))))

  (it "never masks a caller cancellation as an unavailable transport"
    (with-redefs [sut/open-responses-websocket!
                  (fn [_] (throw (ex-info "cancelled" {:type :svar.core/stream-cancelled})))
                  sut/openai-responses-completion
                  (fn [_ _] {:content "http answer" :api-usage {}})]
      (with-open [session (open-test-session (codex-router)
                            {:routing {:provider :openai-codex :model "gpt-5.6"}})]
        (expect (= :svar.core/stream-cancelled
                  (try (svar/ask! session "one")
                       nil
                       (catch Throwable e (:type (ex-data e)))))))))

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
        (with-open [session (open-test-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}})]
          (svar/ask! session "one")
          (expect (= "second" (:content (svar/ask! session "two"))))
          (let [[_ continuation replay] @sent]
            (expect (= "resp_1" (:previous_response_id continuation)))
            (expect (nil? (:previous_response_id replay)))
            (expect (= 3 (count (:input replay)))))))))

  (it "types a rate limit from the socket like the retryable one from SSE"
    ;; A 429 that arrived as a websocket `error` event carried no status, so the
    ;; router read a terminal session error and ended the turn - while the very
    ;; same rate limit over SSE was retried.
    (let [error (websocket-event-error {"type" "error"
                                        "error" {"code" "rate_limit_exceeded"
                                                 "message" "Rate limit reached"}})]
      (expect (= 429 (:status (ex-data error))))
      (expect (failure/transient-error? error))))

  (it "lets the ordinary retry ladder finish a turn a socket rate limit interrupted"
    ;; Typing the error is only half the contract: the retry has to reach the
    ;; SAME session - reusing its socket and its continuation cursor - instead of
    ;; ending the turn or degrading it to a fresh stateless request.
    (let [events (atom [(completed-event "resp_1" "first")
                        (json/write-json-str
                          {"type" "error"
                           "error" {"code" "rate_limit_exceeded"
                                    "message" "Rate limit reached"}})
                        (completed-event "resp_2" "second")])
          sent (atom [])
          closes (atom 0)
          http-calls (atom 0)
          router (svar/make-router
                   [{:id :openai-codex
                     :api-key "test-key"
                     :base-url "https://chatgpt.com/backend-api"
                     :api-style :openai-compatible-responses
                     :responses-path "/codex/responses"
                     :models [{:name "gpt-5.6" :context 100000 :input 1.0 :output 1.0}]}]
                   {:rate-limit {:same-provider-delays-ms [0 0] :respect-retry-after? false}})]
      (with-redefs [sut/open-responses-websocket!
                    (fake-websocket-factory events sent closes)
                    sut/openai-responses-completion
                    (fn [_ _] (swap! http-calls inc) nil)]
        (with-open [session (open-test-session router
                              {:routing {:provider :openai-codex :model "gpt-5.6"}})]
          (svar/ask! session "one")
          (expect (= "second" (:content (svar/ask! session "two"))))
          (let [[_ rejected retried] @sent]
            (expect (= "resp_1" (:previous_response_id rejected)))
            ;; The retry repeats the same delta on the same cursor.
            (expect (= "resp_1" (:previous_response_id retried)))
            (expect (= 1 (count (:input retried)))))))
      (expect (zero? @http-calls))))

  (it "keeps the status the server wrapped into the error event"
    (let [error (websocket-event-error {"type" "error"
                                        "status_code" 503
                                        "error" {"code" "server_is_busy"
                                                 "message" "Busy"}})]
      (expect (= 503 (:status (ex-data error))))
      (expect (failure/transient-error? error))))

  (it "leaves a rejected continuation cursor classified by its code"
    (let [error (websocket-event-error {"type" "error"
                                        "error" {"code" "previous_response_not_found"
                                                 "message" "Previous response not found"}})]
      (expect (= "previous_response_not_found" (:code (ex-data error))))
      (expect (nil? (:status (ex-data error))))))

  (it "sends a tool result as the next incremental Responses item"
    (let [events (atom [(tool-call-event)
                        (completed-event "resp_2" "done")])
          sent (atom [])
          closes (atom 0)]
      (with-redefs [sut/open-responses-websocket!
                    (fake-websocket-factory events sent closes)]
        (with-open [session (open-test-session (codex-router)
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
        (with-open [session (open-test-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}
                               :websocket-max-retries 1
                               :websocket-retry-delay-ms 0})]
          (expect (= "http fallback" (:content (svar/ask! session "one"))))
          (expect (= 1 (count @http-bodies)))
          (expect (= "one" (get-in @http-bodies [0 :input 0 :content 0 :text])))))))

  (it "leaves the WebSocket for good once a turn has spent its retry ladder"
    ;; Codex parity: after `stream_max_retries` the session sets
    ;; `disable_websockets` and every later turn goes straight to HTTP. A session
    ;; that re-tried the handshake per turn paid a doomed round trip on each one.
    (let [opens (atom 0)
          http-inputs (atom [])
          factory (fn [_]
                    (swap! opens inc)
                    {:send! (fn [_] nil)
                     :receive! (fn [_] (java.io.IOException. "connection lost"))
                     :close! (fn [] nil)
                     :abort! (fn [] nil)})]
      (with-redefs [sut/open-responses-websocket! factory
                    sut/openai-responses-completion
                    (fn [body _]
                      (swap! http-inputs conj (count (:input body)))
                      {:content "http answer" :api-usage {}})]
        (with-open [session (open-test-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}
                               :websocket-max-retries 2
                               :websocket-retry-delay-ms 0})]
          (expect (= "http answer" (:content (svar/ask! session "one"))))
          (expect (= "http answer" (:content (svar/ask! session "two"))))))
      ;; The first turn tries once and reconnects twice; the second never touches
      ;; the socket again, and both carry the FULL canonical history over HTTP.
      (expect (= 3 @opens))
      (expect (= [1 3] @http-inputs))))

  (it "stops upgrading after the endpoint refused the handshake with 426"
    ;; 426 Upgrade Required is the endpoint saying it serves no WebSocket at all,
    ;; so no retry can change the answer - Codex switches that session to HTTP on
    ;; the spot.
    (let [opens (atom 0)
          http-inputs (atom [])]
      (with-redefs [sut/open-responses-websocket!
                    (fn [_]
                      (swap! opens inc)
                      (throw (ex-info "Upgrade Required" {:status 426})))
                    sut/openai-responses-completion
                    (fn [body _]
                      (swap! http-inputs conj (count (:input body)))
                      {:content "http answer" :api-usage {}})]
        (with-open [session (open-test-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}
                               :websocket-retry-delay-ms 0})]
          (expect (= "http answer" (:content (svar/ask! session "one"))))
          (expect (= "http answer" (:content (svar/ask! session "two"))))))
      (expect (= 1 @opens))
      (expect (= [1 3] @http-inputs))))

  (it "tells a streaming caller that its turn started over"
    ;; Every attempt streams its own cumulative text from zero. With no signal a
    ;; consumer that appends deltas kept the text of the attempt the lost
    ;; connection threw away.
    (let [opens (atom 0)
          seen (atom [])
          factory (fn [_]
                    (let [n (swap! opens inc)
                          receives (atom (if (= n 1)
                                           [(json/write-json-str
                                              {"type" "response.output_text.delta"
                                               "delta" "par"})
                                            (java.io.IOException. "connection lost")]
                                           [(completed-event "resp_1" "whole answer")]))]
                      {:send! (fn [_] nil)
                       :receive! (fn [_] (let [event (first @receives)]
                                           (swap! receives subvec 1)
                                           event))
                       :close! (fn [] nil)
                       :abort! (fn [] nil)}))]
      (with-redefs [sut/open-responses-websocket! factory]
        (with-open [session (open-test-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}
                               :websocket-retry-delay-ms 0
                               :on-chunk (fn [event] (swap! seen conj event))})]
          (expect (= "whole answer" (:content (svar/ask! session "one"))))))
      (expect (= "par" (:content (first @seen))))
      (let [restart (first (filter :restarted? @seen))]
        (expect (= :llm.session/stream-restarted (:event/type restart)))
        (expect (= "" (:content restart)))
        (expect (= :reconnect (:reason restart)))
        (expect (= 1 (:attempt restart))))))
  (it "prewarms the first request with generate false before inference"
    (let [events (atom [(completed-event "resp_warm" "")
                        (completed-event "resp_1" "first")])
          sent (atom [])
          closes (atom 0)]
      (with-redefs [sut/open-responses-websocket!
                    (fake-websocket-factory events sent closes)]
        (with-open [session (svar/open-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}})]
          (expect (= "first" (:content (svar/ask! session "one"))))
          (let [[warmup inference] @sent]
            (expect (false? (:generate warmup)))
            (expect (= "resp_warm" (:previous_response_id inference)))
            (expect (= [] (:input inference))))))))

  (it "continues with inference when the optional prewarm is rejected"
    (let [events (atom [(json/write-json-str
                          {"type" "error"
                           "status" 400
                           "error" {"code" "invalid_request"
                                    "message" "Warmup unsupported"}})
                        (completed-event "resp_1" "first")])
          sent (atom [])
          closes (atom 0)]
      (with-redefs [sut/open-responses-websocket!
                    (fake-websocket-factory events sent closes)]
        (with-open [session (svar/open-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}})]
          (expect (= "first" (:content (svar/ask! session "one"))))
          (let [[warmup inference] @sent]
            (expect (false? (:generate warmup)))
            (expect (nil? (:previous_response_id inference)))
            (expect (= 1 (count (:input inference)))))))))

  (it "retries an interrupted first inference from its warmup cursor without duplicating input"
    (let [events (atom [(completed-event "resp_warm" "")
                        (json/write-json-str
                          {"type" "error"
                           "error" {"code" "rate_limit_exceeded"
                                    "message" "Rate limit reached"}})
                        (completed-event "resp_1" "first")])
          sent (atom [])
          closes (atom 0)
          router (svar/make-router
                   [{:id :openai-codex
                     :api-key "test-key"
                     :base-url "https://chatgpt.com/backend-api"
                     :api-style :openai-compatible-responses
                     :responses-path "/codex/responses"
                     :models [{:name "gpt-5.6" :context 100000 :input 1.0 :output 1.0}]}]
                   {:rate-limit {:same-provider-delays-ms [0 0]
                                 :respect-retry-after? false}})]
      (with-redefs [sut/open-responses-websocket!
                    (fake-websocket-factory events sent closes)]
        (with-open [session (svar/open-session router
                              {:routing {:provider :openai-codex :model "gpt-5.6"}})]
          (expect (= "first" (:content (svar/ask! session "one"))))
          (let [[warmup first-attempt retry] @sent]
            (expect (false? (:generate warmup)))
            (expect (= ["resp_warm" "resp_warm"]
                      (mapv :previous_response_id [first-attempt retry])))
            (expect (= [[] []] (mapv :input [first-attempt retry]))))))))

  (it "surfaces normalized Codex rate-limit snapshots without ending the response"
    (let [rate-event (json/write-json-str
                       {"type" "codex.rate_limits"
                        "plan_type" "pro"
                        "metered_limit_name" "codex_other"
                        "rate_limits" {"primary" {"used_percent" 42.5
                                                  "window_minutes" 300
                                                  "reset_at" 1738888888}}
                        "credits" {"has_credits" true
                                   "unlimited" false
                                   "balance" "12.50"}})
          events (atom [rate-event (completed-event "resp_1" "first")])
          sent (atom [])
          closes (atom 0)
          seen (atom [])]
      (with-redefs [sut/open-responses-websocket!
                    (fake-websocket-factory events sent closes)]
        (with-open [session (open-test-session (codex-router)
                              {:routing {:provider :openai-codex :model "gpt-5.6"}
                               :websocket-prewarm? false
                               :on-chunk #(swap! seen conj %)})]
          (let [result (svar/ask! session "one")
                snapshot (:rate-limits result)]
            (expect (= "first" (:content result)))
            (expect (= {:limit-id "codex-other"
                        :plan-type "pro"
                        :primary {:used-percent 42.5
                                  :window-minutes 300
                                  :resets-at 1738888888}
                        :credits {:has-credits true
                                  :unlimited false
                                  :balance "12.50"}}
                      snapshot))
            (expect (= snapshot (:rate-limits
                                 (first (filter #(= :llm.session/rate-limits (:event/type %))
                                          @seen))))))))))

  (it "rejects calls after close without sending another request"
    (let [events (atom [(completed-event "resp_1" "first")])
          sent (atom [])
          closes (atom 0)]
      (with-redefs [sut/open-responses-websocket!
                    (fake-websocket-factory events sent closes)]
        (let [session (open-test-session (codex-router)
                        {:routing {:provider :openai-codex :model "gpt-5.6"}})]
          (svar/ask! session "one")
          (svar/close-session! session)
          (expect (= :svar.session/closed
                    (try (svar/ask! session "two") nil
                         (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
          (expect (= 1 (count @sent))))))))
