(ns com.blockether.svar.internal.anthropic-threads-http-test
  (:require [com.blockether.svar.core :as svar]
            [lazytest.core :refer [defdescribe it expect]]
            [charred.api :as json]
            [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

(defn message-text
  [message]
  (let [content (:content message)]
    (if (string? content) content (apply str (map :text content)))))

(defn response-message
  [id text]
  {"id" id
   "type" "message"
   "role" "assistant"
   "model" "claude-sonnet-4-6"
   "content" [{"type" "text" "text" text}]
   "stop_reason" "end_turn"
   "stop_sequence" nil
   "usage" {"input_tokens" 5 "output_tokens" 1}})

(defn sse-message
  [message]
  (apply str
    (map (fn [event]
           (str "event: " (get event "type") "\ndata: " (json/write-json-str event) "\n\n"))
         [{"type" "message_start"
           "message" (assoc message
                       "content" []
                       "stop_reason" nil)}
          {"type" "content_block_start" "index" 0 "content_block" {"type" "text" "text" ""}}
          {"type" "content_block_delta"
           "index" 0
           "delta" {"type" "text_delta" "text" (get-in message ["content" 0 "text"])}}
          {"type" "content_block_stop" "index" 0}
          {"type" "message_delta"
           "delta" {"stop_reason" "end_turn" "stop_sequence" nil}
           "usage" {"output_tokens" 1}} {"type" "message_stop"}])))

(defn fixture
  [handler test-fn]
  (let [seen
        (atom [])

        server
        (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]

    (.createContext
      server
      "/v1/messages"
      (reify
        HttpHandler
          (handle [_ exchange]
            (let [^HttpExchange exchange
                  exchange

                  request
                  (json/read-json (slurp (.getRequestBody exchange)))

                  _
                  (swap! seen conj {:body request :headers (into {} (.getRequestHeaders exchange))})

                  {:keys [status message raw]}
                  (handler (count @seen) request)

                  stream?
                  (true? (get request "stream"))

                  body
                  (.getBytes ^String
                             (or raw
                                 (if stream? (sse-message message) (json/write-json-str message)))
                             "UTF-8")]

              (.set (.getResponseHeaders exchange)
                    "content-type"
                    (if stream? "text/event-stream" "application/json"))
              (.sendResponseHeaders exchange (long (or status 200)) (long (alength body)))
              (with-open [out (.getResponseBody exchange)]
                (.write out body))
              (.close exchange)))))
    (.start server)
    (try (test-fn (svar/make-router
                    [{:id :anthropic
                      :api-key "test-key"
                      :base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1")
                      :api-style :anthropic
                      :models [{:name "claude-sonnet-4-6" :context 200000 :input 1.0 :output 1.0}
                               {:name "claude-opus-4-6" :context 200000 :input 1.0 :output 1.0}]}])
                  seen)
         (finally (.stop server 0)))))

(def session-opts {:routing {:provider :anthropic :model "claude-sonnet-4-6"} :max-tokens 64})

;; User request: Message Threads must work across the public HTTP/SSE boundary.
(defdescribe
  http-session-boundary
  ;; User request: sessions must preserve complete local history with delta transport.
  (it "returns JSON responses and preserves history after compaction"
      (fixture
        (fn [n _]
          {:message (response-message (str "msg_" n) (str "answer" n))})
        (fn [router seen]
          (with-open [session (svar/open-session router session-opts)]
            (expect (= "answer1" (:content (svar/ask! session "first"))))
            (expect (= "answer2" (:content (svar/ask! session "second"))))
            (expect (= ["first" "answer1" "second" "answer2"]
                       (mapv message-text (svar/session-history session))))
            (expect (= "answer3"
                       (:content (svar/ask! session
                                            {:conversation [{:role "user" :content "summary"}]}))))
            (expect (= ["summary" "answer3"] (mapv message-text (svar/session-history session))))
            (expect (= 3 (count @seen)))))))
  (it "delivers SSE text chunks and saves the complete answer"
      (fixture (fn [n _]
                 {:message (response-message (str "msg_" n) "streamed")})
               (fn [router seen]
                 (let [chunks (atom [])]
                   (with-open [session (svar/open-session router
                                                          (assoc session-opts
                                                            :on-chunk #(swap! chunks conj %)))]
                     (expect (= "streamed" (:content (svar/ask! session "first"))))
                     (expect (seq @chunks))
                     (expect (= ["first" "streamed"]
                                (mapv message-text (svar/session-history session))))
                     (expect (= true (get-in @seen [0 :body "stream"])))))))))

(defn body-at [seen n] (:body (nth @seen n)))

(defn create? [body] (= {"type" "create"} (get body "thread")))

(defn continue? [body id] (= {"type" "continue" "previous_message_id" id} (get body "thread")))

(defn wire-texts [body] (mapv #(get % "content") (get body "messages")))

;; User request: Message Threads must work across the public HTTP/SSE boundary.
(defdescribe
  wire-contract
  (it "creates full history then sends only the new input with the completed ID"
      (fixture (fn [n _]
                 {:message (response-message (str "msg_" n) (str "answer" n))})
               (fn [router seen]
                 (with-open [session (svar/open-session router session-opts)]
                   (expect (= "answer1" (:content (svar/ask! session "first"))))
                   (expect (= "answer2" (:content (svar/ask! session "second"))))
                   (expect (create? (body-at seen 0)))
                   (expect (continue? (body-at seen 1) "msg_1"))
                   (expect (= 1 (count (get (body-at seen 1) "messages"))))
                   (expect (not (contains? (body-at seen 1) "system")))
                   (expect (not (contains? (body-at seen 1) "tools")))))))
  (it "recreates on model change but continues on a free max-token change"
      (fixture
        (fn [n _]
          {:message (response-message (str "msg_" n) "answer")})
        (fn [router seen]
          (with-open [session (svar/open-session router session-opts)]
            (expect (= "answer" (:content (svar/ask! session "first"))))
            (expect (= "answer"
                       (:content (svar/ask! session {:input "second" :max-output-tokens 32}))))
            (expect (= "answer"
                       (:content (svar/ask! session
                                            {:input "third" :routing {:model "claude-opus-4-6"}}))))
            (expect (continue? (body-at seen 1) "msg_1"))
            (expect (create? (body-at seen 2)))
            (expect (= 5 (count (get (body-at seen 2) "messages")))))))))

(defn thread-error
  [status code]
  {:status status
   :raw (json/write-json-str {"type" "error"
                              "error" {"type"
                                       (if (= 404 status) "not_found_error" "invalid_request_error")
                                       "message" "fixture failure"
                                       "details" {"error_code" code}}})})

(defn betas-at
  [seen n]
  (->> (:headers (nth @seen n))
       (filter (fn [[k _]]
                 (= "anthropic-beta" (str/lower-case k))))
       (mapcat second)
       (mapcat #(str/split % #","))
       (map str/trim)
       set))

;; User request: Message Threads must work across the public HTTP/SSE boundary.
(defdescribe recovery-contract
             (it "replays exactly once for each recognized missing-or-consumed cursor error"
                 (doseq [[status code] [[404 "thread_not_found"] [400 "thread_already_continued"]
                                        [400 "thread_continue_in_flight"]]]
                   (fixture (fn [n _]
                              (if (= n 2)
                                (thread-error status code)
                                {:message (response-message (str "msg_" n) "answer")}))
                            (fn [router seen]
                              (with-open [session (svar/open-session router session-opts)]
                                (expect (= "answer" (:content (svar/ask! session "first"))))
                                (expect (= "answer" (:content (svar/ask! session "second"))))
                                (expect (= 3 (count @seen)))
                                (expect (continue? (body-at seen 1) "msg_1"))
                                (expect (create? (body-at seen 2)))
                                (expect (= 3 (count (get (body-at seen 2) "messages"))))
                                (expect (= ["first" "answer" "second" "answer"]
                                           (mapv message-text (svar/session-history session)))))))))
             (it "falls back without threads while preserving full history and request settings"
                 (fixture
                   (fn [n _]
                     (if (= n 2)
                       (thread-error 400 "thread_unsupported_request")
                       {:message (response-message (str "msg_" n) "answer")}))
                   (fn [router seen]
                     (with-open [session (svar/open-session router
                                                            (assoc session-opts
                                                              :llm-headers
                                                              {"anthropic-beta"
                                                               "server-side-fallback-2026-07-01"}))]
                       (expect (= "answer" (:content (svar/ask! session "first"))))
                       (expect (= "answer" (:content (svar/ask! session "second"))))
                       (expect (= 3 (count @seen)))
                       (expect (not (contains? (body-at seen 2) "thread")))
                       (expect (contains? (betas-at seen 0) "message-threads-2026-08-12"))
                       (expect (contains? (betas-at seen 2) "server-side-fallback-2026-07-01"))
                       (expect (not (contains? (betas-at seen 2) "message-threads-2026-08-12")))
                       (expect (= 3 (count (get (body-at seen 2) "messages")))))))))

;; User request: Message Threads must work across the public HTTP/SSE boundary.
(defdescribe
  special-response-contract
  (it "continues a tool result without resending the assistant tool use"
      (fixture
        (fn [n _]
          {:message (if (= n 1)
                      (assoc (response-message "msg_tool" "")
                        "stop_reason" "tool_use"
                        "content" [{"type" "tool_use" "id" "tool_1" "name" "run" "input" {}}])
                      (response-message "msg_done" "done"))})
        (fn [router seen]
          (with-open [session (svar/open-session router
                                                 (assoc session-opts
                                                   :tools [{:name "run"
                                                            :description "Run"
                                                            :schema {:type "object"
                                                                     :properties {}}}]))]
            (expect (= :tool-calls (:stop-reason (svar/ask! session "run"))))
            (expect (= "done"
                       (:content (svar/ask! session
                                            {:role "user"
                                             :content [{:type "tool_result"
                                                        :tool_use_id "tool_1"
                                                        :content "ok"}]}))))
            (expect (continue? (body-at seen 1) "msg_tool"))
            (expect (= 1 (count (get (body-at seen 1) "messages"))))
            (expect (= "tool_result" (get-in (body-at seen 1) ["messages" 0 "content" 0 "type"])))
            (expect (not (contains? (body-at seen 1) "tools")))
            (expect (= 4 (count (svar/session-history session))))))))
  (it "keeps fallback text in history but creates rather than continuing its ID"
      (fixture (fn [n _]
                 {:message (cond-> (response-message (str "msg_" n) "answer")
                             (= n 1)
                             (assoc-in ["usage" "iterations"]
                               [{"type" "fallback_message"
                                 "model" "claude-opus-4-6"
                                 "input_tokens" 5
                                 "output_tokens" 1}]))})
               (fn [router seen]
                 (with-open [session (svar/open-session router session-opts)]
                   (expect (= "answer" (:content (svar/ask! session "first"))))
                   (expect (= "answer" (:content (svar/ask! session "second"))))
                   (expect (create? (body-at seen 1)))
                   (expect (= 3 (count (get (body-at seen 1) "messages"))))
                   (expect (= ["first" "answer" "second" "answer"]
                              (mapv message-text (svar/session-history session)))))))))

;; User request: Message Threads must work across the public HTTP/SSE boundary.
(defdescribe
  interrupted-stream-contract
  (it
    "never continues an ID from a stream without message_stop"
    (fixture
      (fn [n _]
        (if (= n 1)
          {:raw
           (str
             "event: message_start\ndata: "
             (json/write-json-str {"type" "message_start"
                                   "message" (assoc (response-message "msg_partial" "")
                                               "content" []
                                               "stop_reason" nil)})
             "\n\n"
             "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
             "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"partial\"}}\n\n")}
          {:message (response-message (str "msg_" n) "complete")}))
      (fn [router seen]
        (with-open [session (svar/open-session router
                                               (assoc session-opts
                                                 :on-chunk (fn [_])))]
          (let [result (try (svar/ask! session "first") (catch Exception _ ::interrupted))]
            (expect (or (= ::interrupted result) (= "complete" (:content result))))
            (expect (= "complete"
                       (:content (svar/ask! session
                                            (if (= ::interrupted result)
                                              {:conversation [{:role "user" :content "first"}]}
                                              "second")))))
            (expect (not-any? #(continue? (:body %) "msg_partial") @seen))
            (expect (create? (body-at seen 1)))
            (expect (= 1 (count (get (body-at seen 1) "messages"))))
            (expect
              (= 1 (count (filter #(= "first" (:content %)) (svar/session-history session)))))))))))

;; User request: Message Threads must work across the public HTTP/SSE boundary.
(defdescribe
  pause-and-error-contract
  (it "continues pause_turn with empty messages and replays the paused assistant on expiry"
      (fixture (fn [n _]
                 (case n
                   1
                   {:message (assoc (response-message "msg_paused" "paused")
                               "stop_reason" "pause_turn")}

                   2
                   (thread-error 404 "thread_not_found")

                   {:message (response-message "msg_finished" "finished")}))
               (fn [router seen]
                 (with-open [session (svar/open-session router session-opts)]
                   (expect (= "paused" (:content (svar/ask! session "first"))))
                   (expect (= "finished" (:content (svar/ask! session []))))
                   (expect (= 3 (count @seen)))
                   (expect (continue? (body-at seen 1) "msg_paused"))
                   (expect (= [] (get (body-at seen 1) "messages")))
                   (expect (create? (body-at seen 2)))
                   (expect (= ["user" "assistant"]
                              (mapv #(get % "role") (get (body-at seen 2) "messages"))))
                   (expect (= ["first" "paused" "finished"]
                              (mapv message-text (svar/session-history session))))))))
  (it "does not turn unrelated model 404s into thread replay or commit failed input"
      (fixture (fn [n _]
                 (if (= n 1)
                   {:message (response-message "msg_first" "answer")}
                   (thread-error 404 "model_not_found")))
               (fn [router seen]
                 (with-open [session (svar/open-session router session-opts)]
                   (expect (= "answer" (:content (svar/ask! session "first"))))
                   (let [error (try (svar/ask! session "second") nil (catch Exception e e))]
                     (expect (instance? Exception error)))
                   (expect (= 2 (count @seen)))
                   (expect (= ["first" "answer"]
                              (mapv message-text (svar/session-history session))))))))
  (it "preserves every explicitly supplied beta when threads are unsupported"
      (fixture (fn [n _]
                 (if (= n 1)
                   (thread-error 400 "thread_unsupported_request")
                   {:message (response-message "msg_done" "answer")}))
               (fn [router seen]
                 (with-open [session
                             (svar/open-session
                               router
                               (assoc session-opts
                                 :llm-headers
                                 {"anthropic-beta"
                                  "server-side-fallback-2026-07-01,message-threads-2026-08-12"}))]
                   (expect (= "answer" (:content (svar/ask! session "first"))))
                   (expect (= 2 (count @seen)))
                   (expect (not (contains? (body-at seen 1) "thread")))
                   (expect (contains? (betas-at seen 1) "server-side-fallback-2026-07-01"))
                   (expect (contains? (betas-at seen 1) "message-threads-2026-08-12")))))))

(defn event-wire
  [event]
  (str "event: " (get event "type") "\ndata: " (json/write-json-str event) "\n\n"))

;; User request: Message Threads must work across the public HTTP/SSE boundary.
(defdescribe
  final-boundary-cases
  (it
    "preserves streamed opaque blocks and citation deltas through expiry replay"
    (let [opaque
          {"type" "web_search_tool_result"
           "tool_use_id" "srv1"
           "content" [{"type" "web_search_result"
                       "url" "https://example.org"
                       "title" "Source"
                       "encrypted_content" "opaque"}]}

          citation
          {"type" "web_search_result_location"
           "url" "https://example.org"
           "title" "Source"
           "cited_text" "evidence"
           "encrypted_index" "index"}

          blocks
          [opaque {"type" "text" "text" "answer" "citations" [citation]}]

          events
          [{"type" "message_start"
            "message" (assoc (response-message "msg_cited" "")
                        "content" []
                        "stop_reason" nil)}
           {"type" "content_block_start" "index" 0 "content_block" opaque}
           {"type" "content_block_stop" "index" 0}
           {"type" "content_block_start" "index" 1 "content_block" {"type" "text" "text" ""}}
           {"type" "content_block_delta" "index" 1 "delta" {"type" "text_delta" "text" "answer"}}
           {"type" "content_block_delta"
            "index" 1
            "delta" {"type" "citations_delta" "citation" citation}}
           {"type" "content_block_stop" "index" 1}
           {"type" "message_delta" "delta" {"stop_reason" "end_turn"} "usage" {"output_tokens" 1}}
           {"type" "message_stop"}]]

      (fixture (fn [n _]
                 (case n
                   1
                   {:raw (apply str (map event-wire events))}

                   2
                   (thread-error 404 "thread_not_found")

                   {:message (response-message "msg_final" "final")}))
               (fn [router seen]
                 (with-open [session (svar/open-session router
                                                        (assoc session-opts
                                                          :on-chunk (fn [_])))]
                   (expect (= "answer" (:content (svar/ask! session "first"))))
                   (expect (= "final" (:content (svar/ask! session "second"))))
                   (expect (= blocks (get-in (body-at seen 2) ["messages" 1 "content"]))))))))
  (it "replays an empty paused assistant after expiry"
      (fixture (fn [n _]
                 (case n
                   1
                   {:message (assoc (response-message "msg_pause" "")
                               "content" []
                               "stop_reason" "pause_turn")}

                   2
                   (thread-error 404 "thread_not_found")

                   {:message (response-message "msg_final" "final")}))
               (fn [router seen]
                 (with-open [session (svar/open-session router session-opts)]
                   (expect (= []
                              (get-in (svar/ask! session "first") [:assistant-message :content])))
                   (expect (= "final" (:content (svar/ask! session []))))
                   (expect (= [] (get (body-at seen 1) "messages")))
                   (expect (= {"role" "assistant" "content" []}
                              (last (get (body-at seen 2) "messages"))))))))
  (it "keeps refusal in visible history but excludes it from fresh model replay"
      (fixture (fn [n _]
                 {:message (if (= n 1)
                             (assoc (response-message "msg_refusal" "No.") "stop_reason" "refusal")
                             (response-message "msg_yes" "Yes."))})
               (fn [router seen]
                 (with-open [session (svar/open-session router session-opts)]
                   (expect (= "No." (:content (svar/ask! session "refused"))))
                   (expect (= "Yes." (:content (svar/ask! session "rephrased"))))
                   (expect (create? (body-at seen 1)))
                   (expect (= [{"role" "user" "content" "rephrased"}]
                              (get (body-at seen 1) "messages")))
                   (expect (= ["refused" "No." "rephrased" "Yes."]
                              (mapv message-text (svar/session-history session)))))))))

;; User request: Message Threads must work across the public HTTP/SSE boundary.
(defdescribe
  logical-response-retry
  (it "starts fresh when the same call retries a completed but rejected response"
      (fixture (fn [n request]
                 {:message (if (= n 1)
                             (-> (response-message "msg_rejected" "")
                                 (assoc "content" []
                                        "stop_reason" "max_tokens")
                                 (assoc-in ["usage" "output_tokens"] (get request "max_tokens")))
                             (response-message "msg_accepted" "accepted"))})
               (fn [router seen]
                 (with-open [session (svar/open-session router
                                                        (assoc session-opts :max-output-tokens 16))]
                   (expect (= "accepted" (:content (svar/ask! session "first"))))
                   (expect (= 2 (count @seen)))
                   (expect (create? (body-at seen 0)))
                   (expect (create? (body-at seen 1)))
                   (expect (= (get (body-at seen 0) "messages") (get (body-at seen 1) "messages")))
                   (expect (= ["first" "accepted"]
                              (mapv message-text (svar/session-history session)))))))))

;; User request: Message Threads must work across the public HTTP/SSE boundary.
(defdescribe logical-continuation-retry
             (it "does not reuse a consumed cursor after a same-call output-budget retry"
                 (fixture (fn [n request]
                            {:message (if (= n 2)
                                        (-> (response-message "msg_rejected" "")
                                            (assoc "content" []
                                                   "stop_reason" "max_tokens")
                                            (assoc-in ["usage" "output_tokens"]
                                                      (get request "max_tokens")))
                                        (response-message (str "msg_" n) "accepted"))})
                          (fn [router seen]
                            (with-open [session (svar/open-session router session-opts)]
                              (expect (= "accepted" (:content (svar/ask! session "first"))))
                              (expect (= "accepted" (:content (svar/ask! session "second"))))
                              (expect (= 3 (count @seen)))
                              (expect (continue? (body-at seen 1) "msg_1"))
                              (expect (create? (body-at seen 2)))
                              (expect (= 3 (count (get (body-at seen 2) "messages"))))
                              (expect (= ["first" "accepted" "second" "accepted"]
                                         (mapv message-text (svar/session-history session)))))))))

;; Audit report: message_delta without message_stop incorrectly committed the cursor.
(defdescribe
  missing-message-stop-regression-test
  (it "rejects EOF after the finish reason and replays the user input once"
      (fixture (fn [n _]
                 (if (= 1 n)
                   {:raw (str/replace (sse-message (response-message "msg_incomplete" "partial"))
                                      (event-wire {"type" "message_stop"})
                                      "")}
                   {:message (response-message "msg_complete" "complete")}))
               (fn [router seen]
                 (with-open [session (svar/open-session router
                                                        (assoc session-opts
                                                          :on-chunk (fn [_])))]
                   (expect (= :svar.core/stream-truncated
                              (try (svar/ask! session "one")
                                   nil
                                   (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
                   (expect (= "complete" (:content (svar/ask! session "one"))))
                   (expect (create? (body-at seen 1)))
                   (expect (= ["one" "complete"]
                              (mapv message-text (svar/session-history session)))))))))
