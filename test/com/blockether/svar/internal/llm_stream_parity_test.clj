(ns com.blockether.svar.internal.llm-stream-parity-test
  "The two stream transports must decode and account a response the same way.

   SSE and WebSocket each drive their own event pipeline, so every protocol rule
   this library adds has to land twice - and twice it did not: the reasoning
   summary-part boundaries, then the semantic watchdog and its progress profile,
   shipped on SSE while a Codex session on a socket carried neither. These cases
   push the SAME event script through BOTH readers and compare what came back."
  (:require [babashka.http-client :as http]
            [charred.api :as json]
            [com.blockether.svar.internal.llm :as sut]
            [lazytest.core :refer [defdescribe expect it]]
            [taoensso.trove :as trove])
  (:import (java.io ByteArrayInputStream)
           (java.util.concurrent TimeoutException)))

(def ^:private receive-websocket-response!
  (ns-resolve 'com.blockether.svar.internal.llm 'receive-websocket-response!))

(def ^:private keepalive
  "Liveness only: the connection is healthy, the model produced nothing."
  {"type" "response.in_progress" "response" {"status" "in_progress"}})

(def ^:private progress-script
  [keepalive {"type" "response.reasoning_summary_text.delta" "delta" "plan"} keepalive
   {"type" "response.output_text.delta" "delta" "ok"}
   {"type" "response.completed"
    "response" {"id" "resp_1"
                "status" "completed"
                "output" [{"id" "msg_1"
                           "type" "message"
                           "role" "assistant"
                           "content" [{"type" "output_text" "text" "ok"}]}]
                "usage" {"input_tokens" 10 "output_tokens" 2 "total_tokens" 12}}}])

(defn- sse-body
  [events]
  (str (apply str (map #(str "data: " (json/write-json-str %) "\n\n") events)) "data: [DONE]\n\n"))

(defn- sse-result
  "Runs `events` through the SSE reader of the Responses transport."
  [events opts]
  (with-redefs [http/post (fn [_url _opts]
                            {:status 200
                             :body (ByteArrayInputStream. (.getBytes ^String (sse-body events)
                                                                     "UTF-8"))})]
    (sut/openai-responses-completion
      {:model "test-model" :input [{:role "user" :content [{:type "input_text" :text "hi"}]}]}
      (merge {:api-key "sk-test"
              :base-url "https://example.invalid/v1"
              :responses-path "/codex/responses"}
             opts))))

(defn- websocket-result
  "Runs `events` through the WebSocket reader of the same transport."
  [events opts]
  (let [remaining (atom (mapv json/write-json-str events))]
    (receive-websocket-response!
      {:receive! (fn [_]
                   (let [event (first @remaining)]
                     (swap! remaining subvec 1)
                     event))}
      (merge {:timeout-ms 1000 :url "https://example.test/codex/responses"} opts))))

(defn- keepalive-sse-body
  "An SSE body that proves liveness forever and never carries model output."
  [closed?]
  (let [frame (.getBytes (str "data: " (json/write-json-str keepalive) "\n\n") "UTF-8")]
    (proxy [java.io.InputStream] []
      (read
        ([] -1)
        ([^bytes buf off len]
         (if @closed?
           -1
           (do (Thread/sleep 5)
               (let [n (min (int len) (alength frame))]
                 (System/arraycopy frame 0 buf off n)
                 n)))))
      (close [] (reset! closed? true)))))

(defn- keepalive-socket
  "A socket that answers every read with liveness. `cap` bounds the test when the
   semantic deadline is NOT enforced, so a regression fails instead of hanging."
  [cap]
  (let [frame
        (json/write-json-str keepalive)

        frames
        (atom 0)]

    {:receive! (fn [wait-ms]
                 (Thread/sleep (long (min 5 (long wait-ms))))
                 (if (< (long (swap! frames inc)) (long cap))
                   frame
                   (throw (TimeoutException. "no frame"))))}))

(defn- thrown [f] (try (f) nil (catch Exception e e)))

(defdescribe
  stream-progress-parity-test
  (it "accounts model progress the same way on both transports"
      ;; The profile existed only on the SSE reader, so the transport this
      ;; telemetry was added for - a Codex session on a socket - reported none.
      (let [sse
            (get-in (sse-result progress-script {}) [:stream-finalization :progress])

            ws
            (get-in (websocket-result progress-script {}) [:stream-finalization :progress])]

        (expect (= (select-keys sse [:semantic-events :quiet-events])
                   (select-keys ws [:semantic-events :quiet-events])))
        (expect (= {:semantic-events 3 :quiet-events 2}
                   (select-keys ws [:semantic-events :quiet-events])))
        (expect (every? nat-int? [(:ttft-ms sse) (:ttft-ms ws)]))
        (expect (every? #{:pre-first-token :reasoning :text}
                        [(:max-gap-phase sse) (:max-gap-phase ws)]))))
  (it "raises the same semantic timeout when only keepalives flow"
      ;; A WebSocket that keeps sending `response.in_progress` used to hold the
      ;; turn open for as long as the frame timeout allowed - the SSE reader had
      ;; caught exactly this since the semantic watchdog landed.
      (let [closed?
            (atom false)

            sse
            (thrown #(with-redefs [http/post
                                   (fn [_url _opts]
                                     {:status 200 :body (keepalive-sse-body closed?)})]
                       (sut/openai-responses-completion
                         {:model "test-model"
                          :input [{:role "user" :content [{:type "input_text" :text "hi"}]}]}
                         {:api-key "sk-test"
                          :base-url "https://example.invalid/v1"
                          :responses-path "/codex/responses"
                          :idle-timeout-ms 2000
                          :semantic-timeout-ms 50})))

            ws
            (thrown #(receive-websocket-response! (keepalive-socket 400)
                                                  {:timeout-ms 2000
                                                   :semantic-timeout-ms 50
                                                   :url "https://example.test/codex/responses"}))]

        (expect (= :svar.core/stream-semantic-timeout (:type (ex-data sse))))
        (expect (= (:type (ex-data sse)) (:type (ex-data ws))))
        (expect (= [true true] (mapv #(:safe-to-restart? (ex-data %)) [sse ws])))
        (expect (true? @closed?))
        (expect (= {:semantic-events 0} (select-keys (:progress (ex-data ws)) [:semantic-events])))
        (expect (pos? (:quiet-events (:progress (ex-data ws)))))
        (expect (= :pre-first-token (:phase (ex-data ws))))
        ;; A fired deadline names the liveness frames after the last progress event:
        ;; they tell a quiet model from a dead connection.
        (expect (= ["response.in_progress"]
                   (keys (:quiet-since-semantic (:progress (ex-data ws))))))))
  (it "forwards model progress without text on both transports"
      ;; A reasoning item without a summary moves the semantic deadline but adds no
      ;; text. The caller's stall clock must see it too: Vis saw 300 s of silence in
      ;; CTB round m14c while Svar's 240 s deadline never fired.
      (let [script
            [{"type" "response.reasoning_summary_text.delta" "delta" "plan"} keepalive
             {"type" "response.output_item.added"
              "output_index" 1
              "item" {"id" "rs_2" "type" "reasoning" "summary" []}} (last progress-script)]

            alive
            (fn [run]
              (let [seen (atom [])]
                (run script {:on-chunk #(swap! seen conj %)})
                (mapv :reasoning (filter :thinking-alive? @seen))))]

        (expect (= ["plan"] (alive sse-result)))
        (expect (= ["plan"] (alive websocket-result)))))
  (it "logs a WebSocket idle timeout with the frames it saw"
      ;; The retry ladder reconnects after an idle timeout without a record of its
      ;; own, so a turn that stalled left no Svar line in the caller's log.
      (let [logs
            (atom [])

            frames
            (atom 0)

            error
            (binding [trove/*log-fn* (fn [_ns _coords level id lazy_]
                                       (swap! logs conj
                                         {:level level :id id :data (:data (force lazy_))}))]
              (thrown #(receive-websocket-response!
                         {:receive! (fn [_]
                                      (when (< (long (swap! frames inc)) 3)
                                        (json/write-json-str keepalive)))}
                         {:timeout-ms 50 :url "https://example.test/codex/responses"})))

            record
            (first (filter #(= ::sut/stream-idle-timeout (:id %)) @logs))]

        (expect (instance? TimeoutException error))
        (expect (= :warn (:level record)))
        (expect (= :websocket (get-in record [:data :transport])))
        (expect (= {"response.in_progress" 2} (get-in record [:data :quiet-since-semantic]))))))
