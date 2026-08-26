(ns com.blockether.svar.internal.llm-session-test
  "Explicit session contract for stateful OpenAI Codex Responses calls."
  (:require [charred.api :as json]
            [clojure.string :as str]
            [com.blockether.svar.core :as svar]
            [com.blockether.svar.internal.failure :as failure]
            [com.blockether.svar.internal.llm :as sut]
            [lazytest.core :refer [defdescribe expect it]])
  (:import (java.net.http WebSocket)
           (java.util.concurrent CompletableFuture CountDownLatch TimeUnit TimeoutException)))

(def ^:private await-websocket-future!
  (ns-resolve 'com.blockether.svar.internal.llm 'await-websocket-future!))
(def ^:private close-websocket! (ns-resolve 'com.blockether.svar.internal.llm 'close-websocket!))
(def ^:private websocket-event-error
  (ns-resolve 'com.blockether.svar.internal.llm 'websocket-event-error))
(def ^:private previous-response-missing?
  (ns-resolve 'com.blockether.svar.internal.llm 'previous-response-missing?))

(defn- completed-event
  [id text]
  (json/write-json-str {"type" "response.completed"
                        "response" {"id" id
                                    "status" "completed"
                                    "output" [{"id" (str "msg_" id)
                                               "type" "message"
                                               "role" "assistant"
                                               "content" [{"type" "output_text" "text" text}]}]
                                    "usage"
                                    {"input_tokens" 10 "output_tokens" 2 "total_tokens" 12}}}))

(defn- reasoning-echo-event
  "Terminal Codex response shaped the way the ChatGPT backend really answers:
   the request's reasoning CONFIG echoed under `reasoning`, and the reasoning
   TEXT - when the model wrote a summary at all - as an `output` item."
  [id text summary]
  (json/write-json-str {"type" "response.completed"
                        "response"
                        {"id" id
                         "status" "completed"
                         "reasoning" {"effort" "high" "summary" "detailed"}
                         "output" (into (if summary
                                          [{"id" (str "rs_" id)
                                            "type" "reasoning"
                                            "summary" [{"type" "summary_text" "text" summary}]}]
                                          [])
                                        [{"id" (str "msg_" id)
                                          "type" "message"
                                          "role" "assistant"
                                          "content" [{"type" "output_text" "text" text}]}])
                         "usage" {"input_tokens" 10 "output_tokens" 2 "total_tokens" 12}}}))
(defn- tool-call-event
  ([] (tool-call-event "resp_tool" "fc_1" "call_1"))
  ([response-id item-id call-id]
   (json/write-json-str {"type" "response.completed"
                         "response" {"id" response-id
                                     "status" "completed"
                                     "output" [{"id" item-id
                                                "call_id" call-id
                                                "type" "function_call"
                                                "name" "run"
                                                "arguments" "{\"x\":1}"}]
                                     "usage"
                                     {"input_tokens" 10 "output_tokens" 2 "total_tokens" 12}}})))

(defn- turn-state-event
  [value]
  (json/write-json-str {"type" "response.metadata" "headers" {"x-codex-turn-state" value}}))

(defn- fake-websocket-factory
  ([events sent closes] (fake-websocket-factory events sent closes (atom 0)))
  ([events sent closes aborts]
   (fn [_]
     {:send! (fn [payload]
               (swap! sent conj (json/read-json payload :key-fn keyword)))
      :receive! (fn [_]
                  (let [event (first @events)]
                    (swap! events subvec 1)
                    event))
      :close! (fn []
                (swap! closes inc))
      :abort! (fn []
                (swap! aborts inc))})))

(defn- quiet-websocket-factory
  "A socket that emits no frame, while exposing send/close/abort synchronization."
  [^CountDownLatch sent closes aborts]
  (fn [_]
    {:send! (fn [_]
              (.countDown sent))
     :receive! (fn [_]
                 ;; Keep the fake bounded even before the cancellation fix so the
                 ;; test fails by outcome instead of parking the suite.
                 (Thread/sleep 25)
                 (throw (TimeoutException. "quiet socket")))
     :close! (fn []
               (swap! closes inc))
     :abort! (fn []
               (swap! aborts inc))}))

(defn- test-websocket
  [close-future aborts]
  (reify
    WebSocket
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

(defn- codex-router
  []
  (svar/make-router [{:id :openai-codex
                      :api-key "test-key"
                      :base-url "https://chatgpt.com/backend-api"
                      :api-style :openai-compatible-responses
                      :responses-path "/codex/responses"
                      :models [{:name "gpt-5.6" :context 100000 :input 1.0 :output 1.0}
                               {:name "gpt-5.6-terra" :context 100000 :input 1.0 :output 1.0}]}]))

(defn- open-test-session
  [router opts]
  (svar/open-session router (assoc opts :websocket-prewarm? false)))
(defdescribe
  websocket-resource-cleanup-test
  (it "cancels an unfinished WebSocket operation after its timeout"
      (let [future (CompletableFuture.)]
        (expect (= TimeoutException
                   (try (await-websocket-future! future 1) nil (catch Throwable e (class e)))))
        (expect (.isCancelled future))))
  (it "cancels a pending WebSocket operation from the caller hook"
      (let [future
            (CompletableFuture.)

            type
            (binding [sut/*cancel-fn* (constantly true)]
              (try (await-websocket-future! future 1000)
                   nil
                   (catch Throwable e (:type (ex-data e)))))]

        (expect (= :svar.core/stream-cancelled type))
        (expect (.isCancelled future))))
  (it "aborts the socket when graceful close times out"
      (let [close-future
            (CompletableFuture.)

            aborts
            (atom 0)]

        (close-websocket! (test-websocket close-future aborts) 1)
        (expect (.isCancelled close-future))
        (expect (= 1 @aborts))))
  (it "aborts the socket when graceful close fails"
      (let [close-future
            (doto (CompletableFuture.) (.completeExceptionally (RuntimeException. "close failed")))

            aborts
            (atom 0)]

        (close-websocket! (test-websocket close-future aborts) 1)
        (expect (= 1 @aborts))))
  (it "does not abort the socket after a successful graceful close"
      (let [aborts (atom 0)]
        (close-websocket! (test-websocket (CompletableFuture/completedFuture nil) aborts))
        (expect (zero? @aborts))))
  (it "rethrows the cause behind a failed WebSocket operation"
      ;; A session that saw only the JDK's ExecutionException wrapper could not
      ;; recognize a lost connection, so it degraded the turn to HTTP instead of
      ;; reconnecting.
      (let [failed (doto (CompletableFuture.)
                     (.completeExceptionally (java.io.IOException. "connection lost")))]
        (expect (= java.io.IOException
                   (try (await-websocket-future! failed 100) nil (catch Throwable e (class e))))))))

(defdescribe
  codex-responses-session-test
  (it "continues a second turn with only its delta and previous response id"
      (let [events
            (atom [(completed-event "resp_1" "first") (completed-event "resp_2" "second")])

            sent
            (atom [])

            closes
            (atom 0)]

        (with-redefs [sut/open-responses-websocket! (fake-websocket-factory events sent closes)]
          (with-open [session (open-test-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}})]
            (expect (= "first" (:content (svar/ask! session "one"))))
            (expect (= "second" (:content (svar/ask! session "two"))))
            (let [[first-request second-request] @sent]
              (expect (= "response.create" (:type first-request)))
              (expect (true? (:stream first-request)))
              (expect (nil? (:previous_response_id first-request)))
              (expect (string? (:prompt_cache_key first-request)))
              (expect (= (:prompt_cache_key first-request) (:prompt_cache_key second-request)))
              (expect (= "resp_1" (:previous_response_id second-request)))
              (expect (= 1 (count (:input second-request))))
              (expect (= "two" (get-in second-request [:input 0 :content 0 :text])))))
          (expect (= 1 @closes)))))
  (it
    "rotates an aged socket and replays canonical history with the same cache key"
    (let [opens
          (atom 0)

          sent
          (atom [])

          closes
          (atom [])

          aborts
          (atom [])

          factory
          (fn [_]
            (let [n
                  (swap! opens inc)

                  events
                  (atom (if (= 1 n)
                          [(turn-state-event "turn-state-1")
                           (tool-call-event "resp_tool" "fc_1" "call_1")
                           (completed-event "resp_unrotated" "old socket")]
                          [(completed-event "resp_2" "rotated")]))]

              {:opened-ns (if (= 1 n) (- (System/nanoTime) 3360000000000) (System/nanoTime))
               :send! (fn [payload]
                        (swap! sent conj [n (json/read-json payload :key-fn keyword)]))
               :receive! (fn [_]
                           (let [event (first @events)]
                             (swap! events subvec 1)
                             event))
               :close! (fn []
                         (swap! closes conj n))
               :abort! (fn []
                         (swap! aborts conj n))}))]

      (with-redefs [sut/open-responses-websocket! factory]
        (with-open [session (open-test-session (codex-router)
                                               {:routing {:provider :openai-codex :model "gpt-5.6"}
                                                :tools [{:name "run"
                                                         :description "Runs a task"
                                                         :schema {:type "object"}}]})]
          (expect (= :tool-calls (:stop-reason (svar/ask! session "one"))))
          (expect (= "rotated"
                     (:content (svar/ask! session
                                          {:role "user"
                                           :content [{:type "tool_result"
                                                      :tool_use_id "call_1|fc_1"
                                                      :content "ok"}]}))))
          (let [[[first-socket first-request] [second-socket replay]] @sent]
            (expect (= [1 2] [first-socket second-socket]))
            (expect (nil? (:previous_response_id first-request)))
            (expect (nil? (:previous_response_id replay)))
            (expect (= 3 (count (:input replay))))
            (expect (= (:prompt_cache_key first-request) (:prompt_cache_key replay)))
            (expect (= "turn-state-1" (get-in replay [:client_metadata :x-codex-turn-state]))))))
      (expect (= 2 @opens))
      (expect (= [1 2] @closes))
      (expect (empty? @aborts))))
  (it
    "exposes Svar-owned transport telemetry without leaking provider state"
    (let [events
          (atom [(completed-event "resp_warm" "") (completed-event "resp_1" "first")
                 (completed-event "resp_2" "second")])

          sent
          (atom [])

          closes
          (atom 0)

          status-fn
          (ns-resolve 'com.blockether.svar.core 'session-status)]

      (expect (some? status-fn))
      (when status-fn
        (with-redefs [sut/open-responses-websocket! (fake-websocket-factory events sent closes)]
          (with-open [session (svar/open-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}})]
            (svar/ask! session "one")
            (svar/ask! session "two")
            (let [{:keys [provider-id transport prompt-cache]} (status-fn session)]
              (expect (= :openai-codex provider-id))
              (expect (= :websocket (:mode transport)))
              (expect (<= 0 (:connection-age-ms transport)))
              (expect (= {:websocket-opens 1
                          :prewarm-requests 1
                          :initial-requests 0
                          :delta-requests 2
                          :full-replay-requests 0
                          :reconnects 0
                          :cursor-resets 0
                          :history-resets 0
                          :model-resets 0
                          :http-fallbacks 0
                          :rotations 0}
                         (:counters transport)))
              (expect (not-any? #(contains? transport %) [:socket :cursor :turn-state]))
              (expect (= :provider-prompt-cache (:kind prompt-cache)))
              (expect (= 2 (:sample-count prompt-cache)))
              (expect (= 0 (:token-read-percent prompt-cache)))
              (expect (= 0 (:request-hit-percent prompt-cache)))))))))
  (it "never sends an explicit cache breakpoint to the ChatGPT Codex backend"
      ;; Regression: from the second request of a session onwards the body carried
      ;; a rolling `prompt_cache_breakpoint` (the first request has one input
      ;; boundary, so no marker is written yet) and the Codex backend answered
      ;; HTTP 400 "prompt_cache_breakpoint is not supported on this model" - the
      ;; turn died, and every identical resend after it died the same way.
      (let [events
            (atom [(completed-event "resp_1" "first") (completed-event "resp_2" "second")
                   (completed-event "resp_3" "third")])

            sent
            (atom [])

            closes
            (atom 0)]

        (with-redefs [sut/open-responses-websocket! (fake-websocket-factory events sent closes)]
          (with-open [session (open-test-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}})]
            (svar/ask! session "one")
            (svar/ask! session "two")
            (svar/ask! session "three")
            (expect (= 3 (count @sent)))
            (expect (nil? (re-find #"prompt_cache_breakpoint" (json/write-json-str @sent))))
            ;; the prompt cache still rides on the stable key Codex itself uses
            (expect (string? (:prompt_cache_key (first @sent))))))))
  (it "never sends an explicit cache breakpoint in a session delta"
      ;; Regression: the delta a session sends from its second turn onwards decided
      ;; the explicit `prompt_cache_breakpoint` marker from the MODEL NAME alone, so
      ;; a caller marking a block for caching put on the wire the very field the
      ;; ChatGPT Codex backend refuses with HTTP 400 - and it did so on the
      ;; incremental turn, where a resend after emitted output cannot self-heal.
      (let [events
            (atom [(completed-event "resp_1" "first") (completed-event "resp_2" "second")])

            sent
            (atom [])

            closes
            (atom 0)]

        (with-redefs [sut/open-responses-websocket! (fake-websocket-factory events sent closes)]
          (with-open [session (open-test-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}})]
            (svar/ask! session "one")
            (svar/ask! session
                       {:input [{:role "user"
                                 :content [{:type "text" :text "two" :svar/cache true}]}]})
            (let [delta-request (second @sent)]
              (expect (= "resp_1" (:previous_response_id delta-request)))
              (expect (= 1 (count (:input delta-request))))
              (expect (nil? (re-find #"prompt_cache_breakpoint"
                                     (json/write-json-str delta-request)))))))))
  (it "never reads the reasoning config echo as the model's thinking"
      ;; Regression: a Responses envelope echoes the request's reasoning CONFIG
      ;; back under `reasoning` ({"effort" "high" "summary" "detailed"}), and the
      ;; terminal extraction read that setting as reasoning TEXT, beating the real
      ;; summary from the output items. Every answer reached the caller with a
      ;; thinking block whose whole content was the literal word `detailed`.
      (let [events
            (atom [(reasoning-echo-event "resp_1" "answer" "**Checking the footer**")
                   (reasoning-echo-event "resp_2" "second" nil)])

            sent
            (atom [])

            closes
            (atom 0)]

        (with-redefs [sut/open-responses-websocket! (fake-websocket-factory events sent closes)]
          (with-open [session (open-test-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}})]
            (let [summarized (svar/ask! session "one")
                  silent (svar/ask! session "two")]

              (expect (= "answer" (:content summarized)))
              (expect (= "**Checking the footer**" (:reasoning summarized)))
              ;; a turn the model wrote no summary for carries NO thinking at all
              (expect (= "second" (:content silent)))
              (expect (nil? (:reasoning silent))))))))
  (it "reuses one socket while a model change starts a full Responses chain"
      ;; Codex keeps the provider transport alive across model switches, but a
      ;; continuation cursor is model-bound: the first request for the new model
      ;; must replay canonical history before later turns become incremental again.
      (let [events
            (atom [(completed-event "resp_1" "first") (completed-event "resp_2" "second")
                   (completed-event "resp_3" "third")])

            sent
            (atom [])

            opens
            (atom 0)

            closes
            (atom 0)

            factory
            (fake-websocket-factory events sent closes)]

        (with-redefs [sut/open-responses-websocket! (fn [opts]
                                                      (swap! opens inc)
                                                      (factory opts))]
          (with-open [session (open-test-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}})]
            (expect (= "first" (:content (svar/ask! session "one"))))
            (expect (= "second"
                       (:content (svar/ask! session
                                            {:input "two" :routing {:model "gpt-5.6-terra"}}))))
            (expect (= "third"
                       (:content (svar/ask! session
                                            {:input "three" :routing {:model "gpt-5.6-terra"}}))))
            (let [[first-request switched-request continued-request] @sent]
              (expect (= "gpt-5.6" (:model first-request)))
              (expect (= "gpt-5.6-terra" (:model switched-request)))
              (expect (nil? (:previous_response_id switched-request)))
              (expect (= 3 (count (:input switched-request))))
              (expect (= "resp_2" (:previous_response_id continued-request)))
              (expect (= 1 (count (:input continued-request)))))))
        (expect (= 1 @opens))))
  (it "opens a fresh socket when the user changes model after a terminal stream error"
      ;; Regression, Vis session 78b0c0b5: a terminal Codex frame stayed attached
      ;; to the logical session, so the next model inherited a poisoned stream.
      (let [events
            (atom [(completed-event "resp_1" "first")
                   (json/write-json-str {"type" "response.failed"
                                         "response" {"status" "failed"
                                                     "error" {"code" "invalid_request_error"
                                                              "status" 400
                                                              "message" "Request rejected"}}})
                   (completed-event "resp_2" "switched")])

            sent
            (atom [])

            opens
            (atom 0)

            closes
            (atom 0)

            aborts
            (atom 0)

            factory
            (fake-websocket-factory events sent closes aborts)]

        (with-redefs [sut/open-responses-websocket! (fn [opts]
                                                      (swap! opens inc)
                                                      (factory opts))]
          (with-open [session (open-test-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}})]
            (expect (= "first" (:content (svar/ask! session "one"))))
            (expect (some? (try (svar/ask! session "rejected") nil (catch Throwable e e))))
            (expect (= "switched"
                       (:content (svar/ask! session
                                            {:input "try another model"
                                             :routing {:model "gpt-5.6-terra"}}))))
            (let [[_ failed switched] @sent]
              (expect (= "resp_1" (:previous_response_id failed)))
              (expect (nil? (:previous_response_id switched)))
              (expect (= "gpt-5.6-terra" (:model switched)))
              (expect (= 3 (count (:input switched)))))))
        (expect (= 2 @opens))
        (expect (= 1 @aborts))))
  (it
    "replaces canonical history on the same socket and continues from the new chain"
    (let [events
          (atom [(completed-event "resp_warm" "") (completed-event "resp_1" "first")
                 (completed-event "resp_2" "rebased") (completed-event "resp_3" "continued")])

          sent
          (atom [])

          opens
          (atom 0)

          closes
          (atom 0)

          factory
          (fake-websocket-factory events sent closes)

          replacement
          [{:role "system" :content "new instructions"} {:role "user" :content "start here"}]]

      (with-redefs [sut/open-responses-websocket! (fn [opts]
                                                    (swap! opens inc)
                                                    (factory opts))]
        (with-open [session (svar/open-session (codex-router)
                                               {:routing {:provider :openai-codex
                                                          :model "gpt-5.6"}})]
          (expect (= "first" (:content (svar/ask! session "one"))))
          (expect (= "rebased" (:content (svar/ask! session {:history replacement}))))
          (expect (= "continued" (:content (svar/ask! session "next"))))
          (expect (= 4 (count @sent)))
          (expect (= 1 (count (filter #(false? (:generate %)) @sent))))
          (let [[warmup first-request replay continued] @sent
                history (svar/session-history session)]

            (expect (false? (:generate warmup)))
            (expect (= "resp_warm" (:previous_response_id first-request)))
            (expect (nil? (:previous_response_id replay)))
            (expect (= 1 (count (:input replay))))
            (expect (= "resp_2" (:previous_response_id continued)))
            (expect (= 1 (count (:input continued))))
            (expect (= replacement (subvec history 0 2)))
            (expect (= ["system" "user" "assistant" "user" "assistant"] (mapv :role history))))))
      (expect (= 1 @opens))))
  (it "rejects a provider change inside one explicit session"
      (with-open [session (open-test-session (codex-router)
                                             {:routing {:provider :openai-codex :model "gpt-5.6"}})]
        (expect (= :svar.session/provider-switch
                   (try (svar/ask! session {:input "move" :routing {:provider :anthropic}})
                        nil
                        (catch Throwable e (:type (ex-data e))))))))
  (it
    "reconnects with canonical full-history replay after transport loss"
    (let [opens
          (atom 0)

          sent
          (atom [])

          closes
          (atom 0)

          aborts
          (atom 0)

          factory
          (fn [_]
            (let [n
                  (swap! opens inc)

                  receives
                  (atom (if (= n 1)
                          [(completed-event "resp_1" "first")
                           (java.io.IOException. "connection lost")]
                          [(completed-event "resp_2" "second")]))]

              {:send! (fn [payload]
                        (swap! sent conj [(dec n) (json/read-json payload :key-fn keyword)]))
               :receive! (fn [_]
                           (let [event (first @receives)]
                             (swap! receives subvec 1)
                             event))
               :close! (fn []
                         (swap! closes inc))
               :abort! (fn []
                         (swap! aborts inc))}))]

      (with-redefs [sut/open-responses-websocket! factory]
        (with-open [session (open-test-session (codex-router)
                                               {:routing {:provider :openai-codex
                                                          :model "gpt-5.6"}})]
          (svar/ask! session "one")
          (expect (= "second" (:content (svar/ask! session "two"))))
          (let [replay (-> @sent
                           last
                           second)]
            (expect (nil? (:previous_response_id replay)))
            (expect (= 3 (count (:input replay)))))))
      (expect (= 2 @opens))
      ;; The lost connection is ABORTED, never closed by handshake: waiting for a
      ;; close frame from a socket that is already gone stalls the recovery.
      (expect (= 1 @aborts))
      (expect (= 1 @closes))))
  (it
    "reconnects when a send fails on a lost connection"
    (let [opens
          (atom 0)

          http-calls
          (atom 0)

          sends
          (atom 0)

          factory
          (fn [_]
            (let [n
                  (swap! opens inc)

                  receives
                  (atom [(completed-event (str "resp_" n) (str "turn" n))])]

              {:send! (fn [_]
                        (when (and (= 1 n) (= 2 (swap! sends inc)))
                          (throw (java.io.IOException. "connection lost"))))
               :receive! (fn [_]
                           (let [event (first @receives)]
                             (swap! receives subvec 1)
                             event))
               :close! (fn []
                         nil)
               :abort! (fn []
                         nil)}))]

      (with-redefs [sut/open-responses-websocket!
                    factory

                    sut/openai-responses-completion
                    (fn [_ _]
                      (swap! http-calls inc)
                      nil)]

        (with-open [session (open-test-session (codex-router)
                                               {:routing {:provider :openai-codex
                                                          :model "gpt-5.6"}})]
          (svar/ask! session "one")
          (expect (= "turn2" (:content (svar/ask! session "two"))))))
      (expect (= 2 @opens))
      (expect (zero? @http-calls))))
  (it
    "degrades a turn to HTTP when the socket cannot be opened at all"
    ;; Only an IOException - the JDK's own handshake refusal - degraded to the
    ;; stateless path. A bad URI, a changed client shape or a provider that
    ;; answers a plain error object ended the turn with no answer, although the
    ;; HTTP transport would have served it.
    (let [http-inputs (atom [])]
      (with-redefs [sut/open-responses-websocket! (fn [_]
                                                    (throw (IllegalArgumentException.
                                                             "invalid URI")))
                    sut/openai-responses-completion (fn [body _]
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
  (it
    "never masks a caller cancellation as an unavailable transport"
    (with-redefs [sut/open-responses-websocket!
                  (fn [_]
                    (throw (ex-info "cancelled" {:type :svar.core/stream-cancelled})))

                  sut/openai-responses-completion
                  (fn [_ _]
                    {:content "http answer" :api-usage {}})]

      (with-open [session (open-test-session (codex-router)
                                             {:routing {:provider :openai-codex :model "gpt-5.6"}})]
        (expect (= :svar.core/stream-cancelled
                   (try (svar/ask! session "one") nil (catch Throwable e (:type (ex-data e)))))))))
  (it "aborts a quiet Responses socket when the caller cancels"
      ;; Regression: `:cancel-fn` protected SSE reads but a Responses WebSocket
      ;; could remain parked until its idle timeout and keep the turn busy.
      (let [cancel?
            (atom false)

            sent
            (CountDownLatch. 1)

            closes
            (atom 0)

            aborts
            (atom 0)

            http-calls
            (atom 0)]

        (with-redefs [sut/open-responses-websocket!
                      (quiet-websocket-factory sent closes aborts)

                      sut/openai-responses-completion
                      (fn [_ _]
                        (swap! http-calls inc)
                        {:content "fallback" :api-usage {}})]

          (with-open [session (open-test-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}
                                                  :cancel-fn #(deref cancel?)
                                                  :websocket-max-retries 0})]
            (let [pending (future (try {:result (svar/ask! session "one")}
                                       (catch Throwable e {:error e})))]
              (try (expect (.await sent 1 TimeUnit/SECONDS))
                   (reset! cancel? true)
                   (let [outcome (deref pending 2000 ::timeout)]
                     (expect (not= ::timeout outcome))
                     (expect (= :svar.core/stream-cancelled (:type (ex-data (:error outcome))))))
                   (finally (future-cancel pending))))))
        (expect (= 1 @aborts))
        (expect (zero? @closes))
        (expect (zero? @http-calls))))
  (it "aborts an active socket when its session is closed"
      ;; Closing an idle session is graceful; closing an in-flight turn is
      ;; cancellation and must not reconnect or fall through to HTTP.
      (let [sent
            (CountDownLatch. 1)

            closes
            (atom 0)

            aborts
            (atom 0)

            http-calls
            (atom 0)]

        (with-redefs [sut/open-responses-websocket!
                      (quiet-websocket-factory sent closes aborts)

                      sut/openai-responses-completion
                      (fn [_ _]
                        (swap! http-calls inc)
                        {:content "fallback" :api-usage {}})]

          (let [session
                (open-test-session (codex-router)
                                   {:routing {:provider :openai-codex :model "gpt-5.6"}
                                    :websocket-max-retries 0})

                pending
                (future (try {:result (svar/ask! session "one")} (catch Throwable e {:error e})))]

            (try (expect (.await sent 1 TimeUnit/SECONDS))
                 (svar/close-session! session)
                 (let [outcome (deref pending 2000 ::timeout)]
                   (expect (not= ::timeout outcome))
                   (expect (= :svar.core/stream-cancelled (:type (ex-data (:error outcome))))))
                 (finally (future-cancel pending) (svar/close-session! session)))))
        (expect (= 1 @aborts))
        (expect (zero? @closes))
        (expect (zero? @http-calls))))
  (it "replays full history when the server rejects its continuation cursor"
      (let [events
            (atom [(completed-event "resp_1" "first")
                   (json/write-json-str {"type" "error"
                                         "error" {"code" "previous_response_not_found"
                                                  "message" "Previous response not found"}})
                   (completed-event "resp_2" "second")])

            sent
            (atom [])

            closes
            (atom 0)]

        (with-redefs [sut/open-responses-websocket! (fake-websocket-factory events sent closes)]
          (with-open [session (open-test-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}})]
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
  (it "lets the ordinary retry ladder finish a turn on a fresh socket"
      ;; Typing the error is only half the contract: a terminal stream error invalidates
      ;; the physical stream. The router retry reaches the SAME logical session, but it
      ;; replays canonical history over a new socket instead of reusing the failed one.
      (let [events
            (atom [(completed-event "resp_1" "first")
                   (json/write-json-str {"type" "error"
                                         "error" {"code" "rate_limit_exceeded"
                                                  "message" "Rate limit reached"}})
                   (completed-event "resp_2" "second")])

            sent
            (atom [])

            opens
            (atom 0)

            closes
            (atom 0)

            aborts
            (atom 0)

            factory
            (fake-websocket-factory events sent closes aborts)

            http-calls
            (atom 0)

            router
            (svar/make-router [{:id :openai-codex
                                :api-key "test-key"
                                :base-url "https://chatgpt.com/backend-api"
                                :api-style :openai-compatible-responses
                                :responses-path "/codex/responses"
                                :models [{:name "gpt-5.6" :context 100000 :input 1.0 :output 1.0}]}]
                              {:rate-limit {:same-provider-delays-ms [0 0]
                                            :respect-retry-after? false}})]

        (with-redefs [sut/open-responses-websocket!
                      (fn [opts]
                        (swap! opens inc)
                        (factory opts))

                      sut/openai-responses-completion
                      (fn [_ _]
                        (swap! http-calls inc)
                        nil)]

          (with-open [session (open-test-session router
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}})]
            (svar/ask! session "one")
            (expect (= "second" (:content (svar/ask! session "two"))))
            (let [[_ rejected retried] @sent]
              (expect (= "resp_1" (:previous_response_id rejected)))
              (expect (nil? (:previous_response_id retried)))
              (expect (= 3 (count (:input retried)))))))
        (expect (= 2 @opens))
        (expect (= 1 @aborts))
        (expect (zero? @http-calls))))
  (it "retries a nested response failure after aborting its physical socket"
      ;; Regression, vis session 1413beac: `response.failed` nests its error below
      ;; `response`. The WebSocket parser missed it, so the second failure was
      ;; untyped and ended a 66-iteration turn instead of using the retry ladder.
      ;; Codex also drops the failed physical stream before that ladder retries.
      (let [events
            (atom [(completed-event "resp_1" "first")
                   (json/write-json-str {"type" "response.failed"
                                         "response" {"status" "failed"
                                                     "error" {"code" "internal_error"
                                                              "message"
                                                              "You can retry your request."}}})
                   (completed-event "resp_2" "second")])

            sent
            (atom [])

            opens
            (atom 0)

            closes
            (atom 0)

            aborts
            (atom 0)

            factory
            (fake-websocket-factory events sent closes aborts)

            router
            (svar/make-router [{:id :openai-codex
                                :api-key "test-key"
                                :base-url "https://chatgpt.com/backend-api"
                                :api-style :openai-compatible-responses
                                :responses-path "/codex/responses"
                                :models [{:name "gpt-5.6" :context 100000 :input 1.0 :output 1.0}]}]
                              {:rate-limit {:same-provider-delays-ms [0 0]
                                            :respect-retry-after? false}})]

        (with-redefs [sut/open-responses-websocket! (fn [opts]
                                                      (swap! opens inc)
                                                      (factory opts))]
          (with-open [session (open-test-session router
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}})]
            (svar/ask! session "one")
            (let [result (svar/ask! session "two")
                  [_ failed retried] @sent]

              (expect (= "second" (:content result)))
              (expect (= 500 (get-in result [:routed/trace 0 :status])))
              (expect (= :server-error (get-in result [:routed/trace 0 :reason])))
              (expect (= "resp_1" (:previous_response_id failed)))
              (expect (nil? (:previous_response_id retried)))
              (expect (= 3 (count (:input retried)))))))
        (expect (= 2 @opens))
        (expect (= 1 @aborts))))
  (it "keeps the status the server wrapped into the error event"
      (let [error (websocket-event-error {"type" "error"
                                          "status_code" 503
                                          "error" {"code" "server_is_busy" "message" "Busy"}})]
        (expect (= 503 (:status (ex-data error))))
        (expect (failure/transient-error? error))))
  (it "leaves a rejected continuation cursor classified by its code"
      (let [error (websocket-event-error {"type" "error"
                                          "error" {"code" "previous_response_not_found"
                                                   "message" "Previous response not found"}})]
        (expect (= "previous_response_not_found" (:code (ex-data error))))
        (expect (nil? (:status (ex-data error))))))
  (it "reads the prose form of a rejected continuation cursor"
      ;; Regression, vis session 9cc1d0a0: ChatGPT rejects a forgotten cursor in
      ;; PROSE with spaces, never with the underscored field name, so the rewind
      ;; below stayed unreachable and a recoverable 400 ended the turn.
      (let [error (websocket-event-error
                    {"type" "response.failed"
                     "response" {"status" "failed"
                                 "error" {"code" "invalid_request_error"
                                          "status" 400
                                          "message"
                                          "Previous response with id 'resp_1' not found."}}})]
        (expect (= 400 (:status (ex-data error))))
        (expect (previous-response-missing? error))))
  (it "rewinds to the full history when the server forgot the cursor"
      ;; Regression, vis session 9cc1d0a0: the same 400 escaped to the router,
      ;; which correctly refuses to retry a 400 - so a turn died holding a healthy
      ;; socket and the whole canonical history it could have replayed.
      (let [events
            (atom [(completed-event "resp_1" "first")
                   (json/write-json-str
                     {"type" "response.failed"
                      "response" {"status" "failed"
                                  "error" {"code" "invalid_request_error"
                                           "status" 400
                                           "message"
                                           "Previous response with id 'resp_1' not found."}}})
                   (completed-event "resp_2" "second")])

            sent
            (atom [])

            opens
            (atom 0)

            closes
            (atom 0)

            aborts
            (atom 0)

            restarts
            (atom [])

            factory
            (fake-websocket-factory events sent closes aborts)]

        (with-redefs [sut/open-responses-websocket! (fn [opts]
                                                      (swap! opens inc)
                                                      (factory opts))]
          (with-open [session (open-test-session
                                (codex-router)
                                {:routing {:provider :openai-codex :model "gpt-5.6"}
                                 :on-chunk (fn [chunk]
                                             (when (:restarted? chunk)
                                               (swap! restarts conj (:reason chunk))))})]
            (svar/ask! session "one")
            (let [result (svar/ask! session "two")
                  [opening rejected replayed] @sent]

              (expect (= "second" (:content result)))
              (expect (= "resp_1" (:previous_response_id rejected)))
              ;; The rewind drops the cursor and replays everything the session owns.
              (expect (nil? (:previous_response_id replayed)))
              (expect (< (count (:input rejected)) (count (:input replayed))))
              (expect (< (count (:input opening)) (count (:input replayed))))
              (expect (= [:cursor-rejected] @restarts))
              ;; The router never saw it: recovery belongs to the session that
              ;; still holds the canonical history.
              (expect (empty? (:routed/trace result))))))
        ;; Codex aborts every physical stream that emitted a terminal error; the
        ;; canonical replay starts on a fresh socket instead of reusing a poisoned one.
        (expect (= 2 @opens))
        (expect (= 1 @aborts))))
  (it "sends a tool result as the next incremental Responses item"
      (let [events
            (atom [(tool-call-event) (completed-event "resp_2" "done")])

            sent
            (atom [])

            closes
            (atom 0)]

        (with-redefs [sut/open-responses-websocket! (fake-websocket-factory events sent closes)]
          (with-open [session (open-test-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}
                                                  :tools [{:name "run"
                                                           :description "Runs a task"
                                                           :schema {:type "object"}}]})]
            (let [first-result (svar/ask! session "run it")]
              (expect (= :tool-calls (:stop-reason first-result)))
              (expect (= "call_1|fc_1" (get-in first-result [:tool-calls 0 :id]))))
            (expect (= "done"
                       (:content (svar/ask! session
                                            {:role "user"
                                             :content [{:type "tool_result"
                                                        :tool_use_id "call_1|fc_1"
                                                        :content "ok"}]}))))
            (let [delta-request (second @sent)]
              (expect (= "resp_tool" (:previous_response_id delta-request)))
              (expect (= [{:type "function_call_output" :call_id "call_1" :output "ok"}]
                         (:input delta-request))))))))
  (it "replays one Codex turn-state value through tool follow-ups and resets it for the next user"
      (let [events
            (atom [(turn-state-event "turn-state-1") (tool-call-event "resp_tool_1" "fc_1" "call_1")
                   (turn-state-event "turn-state-2") (tool-call-event "resp_tool_2" "fc_2" "call_2")
                   (completed-event "resp_3" "done") (completed-event "resp_4" "next")])

            sent
            (atom [])

            closes
            (atom 0)

            tool-result
            (fn [call-id]
              {:role "user" :content [{:type "tool_result" :tool_use_id call-id :content "ok"}]})]

        (with-redefs [sut/open-responses-websocket! (fake-websocket-factory events sent closes)]
          (with-open [session (open-test-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}
                                                  :tools [{:name "run"
                                                           :description "Runs a task"
                                                           :schema {:type "object"}}]})]
            (expect (= :tool-calls (:stop-reason (svar/ask! session "run it"))))
            (expect (= :tool-calls (:stop-reason (svar/ask! session (tool-result "call_1|fc_1")))))
            (expect (= "done" (:content (svar/ask! session (tool-result "call_2|fc_2")))))
            (expect (= "next" (:content (svar/ask! session "new user turn"))))
            (expect (= [nil "turn-state-1" "turn-state-1" nil]
                       (mapv #(get-in % [:client_metadata :x-codex-turn-state]) @sent)))))))
  (it
    "carries Codex turn state through the sticky HTTP fallback without exposing policy to callers"
    (let [http-opts
          (atom [])

          calls
          (atom 0)

          tool-message
          {:role "assistant"
           :content [{:type "tool_use" :id "call_1|fc_1" :name "run" :input {:x 1}}]}]

      (with-redefs [sut/open-responses-websocket!
                    (fn [_]
                      (throw (java.io.IOException. "upgrade unavailable")))

                    sut/openai-responses-completion
                    (fn [_body opts]
                      (swap! http-opts conj opts)
                      (case (swap! calls inc)
                        1
                        {:content nil
                         :assistant-message tool-message
                         :tool-calls [{:id "call_1|fc_1" :name "run" :arguments {:x 1}}]
                         :stop-reason :tool-calls
                         :api-usage {:input-tokens 10 :output-tokens 2}
                         :http-response {:status 200
                                         :headers {"x-codex-turn-state" "http-turn-state"}}}

                        {:content "done"
                         :assistant-message {:role "assistant"
                                             :content [{:type "text" :text "done"}]}
                         :tool-calls []
                         :stop-reason :end
                         :api-usage {:input-tokens 10 :output-tokens 2}
                         :http-response {:status 200 :headers {}}}))]

        (with-open [session (open-test-session (codex-router)
                                               {:routing {:provider :openai-codex :model "gpt-5.6"}
                                                :websocket-max-retries 0
                                                :tools [{:name "run"
                                                         :description "Runs a task"
                                                         :schema {:type "object"}}]})]
          (expect (= :tool-calls (:stop-reason (svar/ask! session "run it"))))
          (expect (= "done"
                     (:content (svar/ask! session
                                          {:role "user"
                                           :content [{:type "tool_result"
                                                      :tool_use_id "call_1|fc_1"
                                                      :content "ok"}]}))))
          (expect (= "done" (:content (svar/ask! session "new user turn"))))
          (expect (= [nil "http-turn-state" nil]
                     (mapv #(get-in % [:headers "x-codex-turn-state"]) @http-opts)))))))
  (it "falls back to the existing HTTP Responses transport when WebSocket setup fails"
      (let [http-bodies (atom [])]
        (with-redefs [sut/open-responses-websocket! (fn [_]
                                                      (throw (java.io.IOException.
                                                               "upgrade unavailable")))
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
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}
                                                  :websocket-max-retries 1
                                                  :websocket-retry-delay-ms 0})]
            (expect (= "http fallback" (:content (svar/ask! session "one"))))
            (expect (= 1 (count @http-bodies)))
            (expect (= "one" (get-in @http-bodies [0 :input 0 :content 0 :text])))))))
  (it "leaves the WebSocket for good once a turn has spent its retry ladder"
      ;; Codex parity: after `stream_max_retries` the session sets
      ;; `disable_websockets` and every later turn goes straight to HTTP. A session
      ;; that re-tried the handshake per turn paid a doomed round trip on each one.
      (let [opens
            (atom 0)

            http-inputs
            (atom [])

            factory
            (fn [_]
              (swap! opens inc)
              {:send! (fn [_]
                        nil)
               :receive! (fn [_]
                           (java.io.IOException. "connection lost"))
               :close! (fn []
                         nil)
               :abort! (fn []
                         nil)})]

        (with-redefs [sut/open-responses-websocket!
                      factory

                      sut/openai-responses-completion
                      (fn [body _]
                        (swap! http-inputs conj (count (:input body)))
                        {:content "http answer" :api-usage {}})]

          (with-open [session (open-test-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}
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
      (let [opens
            (atom 0)

            http-inputs
            (atom [])]

        (with-redefs [sut/open-responses-websocket!
                      (fn [_]
                        (swap! opens inc)
                        (throw (ex-info "Upgrade Required" {:status 426})))

                      sut/openai-responses-completion
                      (fn [body _]
                        (swap! http-inputs conj (count (:input body)))
                        {:content "http answer" :api-usage {}})]

          (with-open [session (open-test-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}
                                                  :websocket-retry-delay-ms 0})]
            (expect (= "http answer" (:content (svar/ask! session "one"))))
            (expect (= "http answer" (:content (svar/ask! session "two"))))))
        (expect (= 1 @opens))
        (expect (= [1 3] @http-inputs))))
  (it
    "tells a streaming caller that its turn started over"
    ;; Every attempt streams its own cumulative text from zero. With no signal a
    ;; consumer that appends deltas kept the text of the attempt the lost
    ;; connection threw away.
    (let [opens
          (atom 0)

          seen
          (atom [])

          factory
          (fn [_]
            (let [n
                  (swap! opens inc)

                  receives
                  (atom (if (= n 1)
                          [(json/write-json-str {"type" "response.output_text.delta" "delta" "par"})
                           (java.io.IOException. "connection lost")]
                          [(completed-event "resp_1" "whole answer")]))]

              {:send! (fn [_]
                        nil)
               :receive! (fn [_]
                           (let [event (first @receives)]
                             (swap! receives subvec 1)
                             event))
               :close! (fn []
                         nil)
               :abort! (fn []
                         nil)}))]

      (with-redefs [sut/open-responses-websocket! factory]
        (with-open [session (open-test-session (codex-router)
                                               {:routing {:provider :openai-codex :model "gpt-5.6"}
                                                :websocket-retry-delay-ms 0
                                                :on-chunk (fn [event]
                                                            (swap! seen conj event))})]
          (expect (= "whole answer" (:content (svar/ask! session "one"))))))
      (expect (= "par" (:content (first @seen))))
      (let [restart (first (filter :restarted? @seen))]
        (expect (= :llm.session/stream-restarted (:event/type restart)))
        (expect (= "" (:content restart)))
        (expect (= :reconnect (:reason restart)))
        (expect (= 1 (:attempt restart))))))
  (it "prewarms the first request with generate false before inference"
      (let [events
            (atom [(completed-event "resp_warm" "") (completed-event "resp_1" "first")])

            sent
            (atom [])

            closes
            (atom 0)]

        (with-redefs [sut/open-responses-websocket! (fake-websocket-factory events sent closes)]
          (with-open [session (svar/open-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}})]
            (expect (= "first" (:content (svar/ask! session "one"))))
            (let [[warmup inference] @sent]
              (expect (false? (:generate warmup)))
              (expect (= "resp_warm" (:previous_response_id inference)))
              (expect (= [] (:input inference))))))))
  (it "does not turn a cancelled prewarm into an inference"
      (let [events
            (atom [(ex-info "cancelled" {:type :svar.core/stream-cancelled})
                   (completed-event "resp_1" "must not run")])

            sent
            (atom [])

            closes
            (atom 0)

            aborts
            (atom 0)]

        (with-redefs [sut/open-responses-websocket!
                      (fake-websocket-factory events sent closes aborts)]
          (with-open [session (svar/open-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}})]
            (expect
              (= :svar.core/stream-cancelled
                 (try (svar/ask! session "one") nil (catch Throwable e (:type (ex-data e))))))))
        (expect (= 1 (count @sent)))
        (expect (false? (:generate (first @sent))))
        (expect (= 1 @aborts))
        (expect (zero? @closes))))
  (it "continues with inference when the optional prewarm is rejected"
      (let [events
            (atom [(json/write-json-str {"type" "error"
                                         "status" 400
                                         "error" {"code" "invalid_request"
                                                  "message" "Warmup unsupported"}})
                   (completed-event "resp_1" "first")])

            sent
            (atom [])

            closes
            (atom 0)]

        (with-redefs [sut/open-responses-websocket! (fake-websocket-factory events sent closes)]
          (with-open [session (svar/open-session (codex-router)
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}})]
            (expect (= "first" (:content (svar/ask! session "one"))))
            (let [[warmup inference] @sent]
              (expect (false? (:generate warmup)))
              (expect (nil? (:previous_response_id inference)))
              (expect (= 1 (count (:input inference)))))))))
  (it
    "replays the first inference after its warmup socket emits a terminal error"
    (let [events
          (atom [(completed-event "resp_warm" "")
                 (json/write-json-str {"type" "error"
                                       "error" {"code" "rate_limit_exceeded"
                                                "message" "Rate limit reached"}})
                 (completed-event "resp_1" "first")])

          sent
          (atom [])

          opens
          (atom 0)

          closes
          (atom 0)

          aborts
          (atom 0)

          factory
          (fake-websocket-factory events sent closes aborts)

          router
          (svar/make-router [{:id :openai-codex
                              :api-key "test-key"
                              :base-url "https://chatgpt.com/backend-api"
                              :api-style :openai-compatible-responses
                              :responses-path "/codex/responses"
                              :models [{:name "gpt-5.6" :context 100000 :input 1.0 :output 1.0}]}]
                            {:rate-limit {:same-provider-delays-ms [0 0]
                                          :respect-retry-after? false}})]

      (with-redefs [sut/open-responses-websocket! (fn [opts]
                                                    (swap! opens inc)
                                                    (factory opts))]
        (with-open [session (svar/open-session router
                                               {:routing {:provider :openai-codex
                                                          :model "gpt-5.6"}})]
          (expect (= "first" (:content (svar/ask! session "one"))))
          (let [[warmup first-attempt retry] @sent]
            (expect (false? (:generate warmup)))
            (expect (= "resp_warm" (:previous_response_id first-attempt)))
            (expect (nil? (:previous_response_id retry)))
            (expect (= [] (:input first-attempt)))
            (expect (= 1 (count (:input retry)))))))
      (expect (= 2 @opens))
      (expect (= 1 @aborts))))
  (it
    "surfaces normalized Codex rate-limit snapshots without ending the response"
    (let [rate-event
          (json/write-json-str {"type" "codex.rate_limits"
                                "plan_type" "pro"
                                "metered_limit_name" "codex_other"
                                "rate_limits" {"primary" {"used_percent" 42.5
                                                          "window_minutes" 300
                                                          "reset_at" 1738888888}}
                                "credits" {"has_credits" true "unlimited" false "balance" "12.50"}})

          events
          (atom [rate-event (completed-event "resp_1" "first")])

          sent
          (atom [])

          closes
          (atom 0)

          seen
          (atom [])]

      (with-redefs [sut/open-responses-websocket! (fake-websocket-factory events sent closes)]
        (with-open [session (open-test-session (codex-router)
                                               {:routing {:provider :openai-codex :model "gpt-5.6"}
                                                :websocket-prewarm? false
                                                :on-chunk #(swap! seen conj %)})]
          (let [result (svar/ask! session "one")
                snapshot (:rate-limits result)]

            (expect (= "first" (:content result)))
            (expect (= {:limit-id "codex-other"
                        :plan-type "pro"
                        :primary {:used-percent 42.5 :window-minutes 300 :resets-at 1738888888}
                        :credits {:has-credits true :unlimited false :balance "12.50"}}
                       snapshot))
            (expect (= snapshot
                       (:rate-limits (first (filter #(= :llm.session/rate-limits (:event/type %))
                                                    @seen))))))))))
  (it "rejects calls after close without sending another request"
      (let [events
            (atom [(completed-event "resp_1" "first")])

            sent
            (atom [])

            closes
            (atom 0)]

        (with-redefs [sut/open-responses-websocket! (fake-websocket-factory events sent closes)]
          (let [session (open-test-session (codex-router)
                                           {:routing {:provider :openai-codex :model "gpt-5.6"}})]
            (svar/ask! session "one")
            (svar/close-session! session)
            (expect (= :svar.session/closed
                       (try (svar/ask! session "two")
                            nil
                            (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
            (expect (= 1 (count @sent)))
            (expect (= 1 @closes)))))))

(def ^:private receive-websocket-response!
  (ns-resolve 'com.blockether.svar.internal.llm 'receive-websocket-response!))

(defn- reasoning-stream-events
  "One Codex reasoning stream: two summary parts inside the first reasoning
   item, a third inside a SECOND item, then the terminal response."
  [headline-a headline-b headline-c]
  (let [part
        (fn [text]
          {"type" "summary_text" "text" text})

        item
        (fn [id parts]
          {"id" id "type" "reasoning" "summary" parts})]

    (mapv json/write-json-str
          [{"type" "response.output_item.added" "item" (item "rs_1" [])}
           {"type" "response.reasoning_summary_part.added" "part" (part "")}
           {"type" "response.reasoning_summary_text.delta" "delta" headline-a}
           {"type" "response.reasoning_summary_part.done" "part" (part headline-a)}
           {"type" "response.reasoning_summary_part.added" "part" (part "")}
           {"type" "response.reasoning_summary_text.delta" "delta" headline-b}
           {"type" "response.reasoning_summary_part.done" "part" (part headline-b)}
           {"type" "response.output_item.done"
            "item" (item "rs_1" [(part headline-a) (part headline-b)])}
           {"type" "response.output_item.added" "item" (item "rs_2" [])}
           {"type" "response.reasoning_summary_part.added" "part" (part "")}
           {"type" "response.reasoning_summary_text.delta" "delta" headline-c}
           {"type" "response.reasoning_summary_part.done" "part" (part headline-c)}
           {"type" "response.output_item.done" "item" (item "rs_2" [(part headline-c)])}
           {"type" "response.completed"
            "response" {"id" "resp_1"
                        "status" "completed"
                        "output" []
                        "usage" {"input_tokens" 10 "output_tokens" 2 "total_tokens" 12}}}])))

(defdescribe
  websocket-reasoning-boundary-test
  (it "keeps the streamed reasoning cumulative append-only across summary parts"
      ;; Regression: only the SSE reader ran events through
      ;; `enrich-responses-reasoning-event`, so a WebSocket session streamed the
      ;; summary parts with no separator - two bold headlines glued into the
      ;; `****` artifact - and then REWROTE the cumulative at
      ;; `response.completed`, where the terminal join puts the "\n\n" back.
      ;; A rewritten cumulative also breaks every append-only consumer, which
      ;; slices the increment off the previous length.
      (let [headline-a
            "**Designing process role derivation**"

            headline-b
            "**Implementing lazy log timestamps**"

            headline-c
            "**Investigating GraalPy engine logs**"

            events
            (atom (reasoning-stream-events headline-a headline-b headline-c))

            chunks
            (atom [])

            socket
            {:receive! (fn [_]
                         (let [event (first @events)]
                           (swap! events subvec 1)
                           event))}

            result
            (receive-websocket-response! socket
                                         {:timeout-ms 1000
                                          :url "https://example.test/codex/responses"
                                          :on-chunk (fn [chunk]
                                                      (swap! chunks conj chunk))})

            streamed
            (keep :reasoning @chunks)]

        (expect (= (str/join "\n\n" [headline-a headline-b headline-c]) (:reasoning result)))
        (expect (= (:reasoning result) (last streamed)))
        (expect (nil? (re-find #"\*\*\*\*" (:reasoning result))))
        (expect (every? #(str/starts-with? (:reasoning result) %) streamed)))))

(defn- stalled-websocket-factory
  "A socket that keeps proving it is alive and never carries model progress.
   `cap` bounds a regression: with no semantic deadline the reader would read
   liveness frames until the frame timeout, which is minutes."
  [opens sent aborts cap]
  (let [frame
        (json/write-json-str {"type" "response.in_progress" "response" {"status" "in_progress"}})

        frames
        (atom 0)]

    (fn [_]
      (swap! opens inc)
      {:send! (fn [payload]
                (swap! sent conj (json/read-json payload :key-fn keyword)))
       :receive!
       (fn [wait-ms]
         (Thread/sleep (long (min 5 (long wait-ms))))
         (if (< (long (swap! frames inc)) (long cap)) frame (throw (TimeoutException. "no frame"))))
       :close! (fn []
                 nil)
       :abort! (fn []
                 (swap! aborts inc))})))

(defdescribe
  websocket-semantic-timeout-test
  (it "drops a socket that stays alive without model progress"
      ;; Only the SSE reader armed the semantic watchdog, so a Codex session
      ;; whose socket kept sending `response.in_progress` held the turn open for
      ;; as long as the frame timeout allowed - a live connection with nothing to
      ;; show for it - and the stalled socket was then handed to the next turn.
      (let [opens
            (atom 0)

            sent
            (atom [])

            aborts
            (atom 0)

            router
            (svar/make-router
              [{:id :openai-codex
                :api-key "test-key"
                :base-url "https://chatgpt.com/backend-api"
                :api-style :openai-compatible-responses
                :responses-path "/codex/responses"
                :models [{:name "gpt-5.6" :context 100000 :input 1.0 :output 1.0}]}]
              {:network {:semantic-timeout-ms 50 :idle-timeout-ms 2000 :max-retries 1}
               :rate-limit {:same-provider-delays-ms [0 0] :respect-retry-after? false}})]

        (with-redefs [sut/open-responses-websocket!
                      (stalled-websocket-factory opens sent aborts 400)]
          (with-open [session (open-test-session router
                                                 {:routing {:provider :openai-codex
                                                            :model "gpt-5.6"}})]
            (let [started-ns (System/nanoTime)
                  error (try (svar/ask! session "one") nil (catch Exception e e))
                  elapsed-ms (quot (- (System/nanoTime) started-ns) 1000000)
                  attempts (:attempts (ex-data error))]

              ;; The ROUTER owns the verdict: it spent its own ladder on a
              ;; classified stall instead of the transport quietly replaying.
              (expect (= :svar.llm/provider-unavailable (:type (ex-data error))))
              (expect (some #(re-find #"semantic timeout" (str (:error %))) attempts))
              ;; Caught by the 50ms semantic deadline, never by the 2s frame one.
              (expect (< elapsed-ms 1500))
              (expect (pos? @opens))
              ;; Every stalled socket is given up, never carried into the next
              ;; attempt: its pending response still owns the connection.
              (expect (= @opens @aborts))))))))
