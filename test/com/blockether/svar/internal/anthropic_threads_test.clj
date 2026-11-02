(ns com.blockether.svar.internal.anthropic-threads-test
  "Message Threads across the public Svar session boundary."
  (:require [babashka.http-client :as http]
            [charred.api :as json]
            [com.blockether.svar.core :as svar]
            [com.blockether.svar.internal.anthropic-threads :as threads]
            [lazytest.core :refer [defdescribe expect it]]))

(defn- thread-router
  []
  (svar/make-router [{:id :anthropic
                      :api-key "test-key"
                      :base-url "https://example.invalid/v1"
                      :models [{:name "claude-sonnet-4-6" :context 200000}
                               {:name "claude-opus-4-6" :context 200000}]}]))

(defn- message
  [id text]
  {:id id
   :type "message"
   :role "assistant"
   :model "claude-sonnet-4-6"
   :content [{:type "text" :text text}]
   :stop_reason "end_turn"
   :usage {:input_tokens 10 :output_tokens 2}})

(defn- response [body] {:status 200 :headers {} :body (json/write-json-str body)})

(defn- scripted-post
  [requests replies]
  (let [remaining (atom replies)]
    (fn [_url opts]
      (let [request (json/read-json (:body opts) :key-fn keyword)
            reply (first @remaining)]

        (swap! requests conj {:body request :headers (:headers opts)})
        (swap! remaining #(vec (rest %)))
        (when-not reply (throw (ex-info "Unexpected request" {:request request})))
        (let [result (if (fn? reply) (reply request) reply)]
          (if (>= (:status result) 400) (throw (ex-info "HTTP request failed" result)) result))))))

(defn- open-thread
  [opts]
  (svar/open-session (thread-router)
                     (merge {:routing {:provider :anthropic :model "claude-sonnet-4-6"}
                             :messages [(svar/system "Help the user.")]
                             :extra-body {:max_tokens 128}
                             :max-retries 0}
                            opts)))

;; User request: use Anthropic Message Threads in Svar and Vis without resending history.
(defdescribe anthropic-thread-session-test
             (it "sends full context once and only new messages on the next turn"
                 (let [requests (atom [])]
                   (with-redefs [http/post (scripted-post requests
                                                          [(response (message "msg_1" "first"))
                                                           (response (message "msg_2" "second"))])]
                     (with-open [session (open-thread {})]
                       (let [first-result (svar/ask! session "one")
                             second-result (svar/ask! session "two")
                             [first-request second-request] (map :body @requests)]

                         (expect (= "first" (:content first-result)))
                         (expect (= "second" (:content second-result)))
                         (expect (= {:type "create"} (:thread first-request)))
                         (expect (= "Help the user." (get-in first-request [:system 0 :text])))
                         (expect (= {:type "continue" :previous_message_id "msg_1"}
                                    (:thread second-request)))
                         (expect (not (contains? second-request :system)))
                         (expect (not (contains? second-request :tools)))
                         (expect (= [{:role "user" :content "two"}] (:messages second-request)))
                         (expect (= 5 (count (svar/session-history session))))
                         (expect (= "message-threads-2026-08-12"
                                    (get-in (first @requests) [:headers "anthropic-beta"])))))))))

(defn- thread-error
  [status code]
  {:status status
   :headers {}
   :body (json/write-json-str
           {:type "error"
            :error {:type "invalid_request_error" :message code :details {:error_code code}}})})

(defdescribe
  anthropic-thread-recovery-test
  (it "replays an expired thread and keeps caller beta headers on unsupported fallback"
      (let [requests (atom [])]
        (with-redefs [http/post (scripted-post requests
                                               [(response (message "msg_1" "first"))
                                                (thread-error 404 "thread_not_found")
                                                (response (message "msg_2" "second"))
                                                (thread-error 400 "thread_unsupported_request")
                                                (response (message "msg_3" "third"))])]
          (with-open [session (open-thread {:llm-headers {"anthropic-beta" "caller-beta"}})]
            (svar/ask! session "one")
            (expect (= "second" (:content (svar/ask! session "two"))))
            (expect (= "third" (:content (svar/ask! session "three"))))
            (let [[_ failed replay _ fallback] (map :body @requests)]
              (expect (= "continue" (get-in failed [:thread :type])))
              (expect (= "create" (get-in replay [:thread :type])))
              (expect (= 3 (count (:messages replay))))
              (expect (= 5 (count (:messages fallback))))
              (expect (not (contains? fallback :thread)))
              (expect (= "caller-beta" (get-in (last @requests) [:headers "anthropic-beta"]))))))))
  (it "rejects repeated and empty completed turns without another request"
      (let [requests (atom [])]
        (with-redefs [http/post (scripted-post requests [(response (message "msg_1" "first"))])]
          (with-open [session (open-thread {})]
            (svar/ask! session "one")
            (doseq [input [[] {:conversation (svar/session-history session)}]]
              (expect (= :svar.session/invalid-input
                         (try (svar/ask! session input)
                              nil
                              (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
            (expect (= 1 (count @requests)))))))
  (it "resumes pause_turn with empty input and a completed cursor"
      (let [requests (atom [])]
        (with-redefs [http/post (scripted-post requests
                                               [(response (assoc (message "msg_pause" "working")
                                                            :stop_reason "pause_turn"))
                                                (response (message "msg_done" "done"))])]
          (with-open [session (open-thread {})]
            (svar/ask! session "one")
            (expect (= "done" (:content (svar/ask! session []))))
            (expect (= [] (get-in (last @requests) [:body :messages])))
            (expect (= "msg_pause"
                       (get-in (last @requests) [:body :thread :previous_message_id]))))))))

;; Audit of the Message Threads request found rejected responses advancing cursors.
(defdescribe
  thread-acceptance-regression-test
  (it "does not continue a response rejected by outer validation"
      (let [requests (atom [])]
        (with-redefs [http/post (scripted-post requests
                                               [(response (assoc (message "msg_cap" "")
                                                            :stop_reason "max_tokens"))
                                                (response (message "msg_ok" "accepted"))])]
          (with-open [session (open-thread {})]
            (expect (= :svar.llm/max-tokens-exceeded
                       (try (svar/ask! session "one")
                            nil
                            (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
            (expect (false? (get-in (svar/session-status session) [:transport :continuation?])))
            (expect (= "accepted" (:content (svar/ask! session "one"))))
            (expect (= ["create" "create"] (mapv #(get-in % [:body :thread :type]) @requests)))))))
  (it "accepts an empty pause and replays it after expiry"
      (let [requests (atom [])]
        (with-redefs [http/post (scripted-post requests
                                               [(response (assoc (message "msg_pause" "")
                                                            :content []
                                                            :stop_reason "pause_turn"))
                                                {:status 404
                                                 :body (json/write-json-str
                                                         {:error {:details {:error_code
                                                                            "thread_not_found"}}})}
                                                (response (message "msg_done" "done"))])]
          (with-open [session (open-thread {})]
            (expect (= {:role "assistant" :content []}
                       (select-keys (:assistant-message (svar/ask! session "one"))
                                    [:role :content])))
            (expect (= "done" (:content (svar/ask! session []))))
            (expect (= {:role "assistant" :content []}
                       (last (get-in (last @requests) [:body :messages]))))))))
  (it "starts a new model after a paused turn"
      (let [requests (atom [])]
        (with-redefs [http/post (scripted-post requests
                                               [(response (assoc (message "msg_pause" "working")
                                                            :stop_reason "pause_turn"))
                                                (response (message "msg_new" "new model"))])]
          (with-open [session (open-thread {})]
            (svar/ask! session "one")
            (expect (= "new model"
                       (:content (svar/ask! session
                                            {:input "two" :routing {:model "claude-opus-4-6"}})))))
          (expect (= "create" (get-in (last @requests) [:body :thread :type]))))))
  (it "keeps opaque blocks and citations through a full replay"
      (let [requests
            (atom [])

            blocks
            [{:type "server_tool_use" :id "srv1" :name "web_search" :input {:query "q"}}
             {:type "text"
              :text "answer"
              :citations [{:type "web_search_result_location" :cited_text "source"}]}]]

        (with-redefs [http/post (scripted-post
                                  requests
                                  [(response (assoc (message "msg_1" "") :content blocks))
                                   {:status 404
                                    :body (json/write-json-str
                                            {:error {:details {:error_code "thread_not_found"}}})}
                                   (response (message "msg_2" "second"))])]
          (with-open [session (open-thread {})]
            (expect (= "answer" (:content (svar/ask! session "one"))))
            (expect (= "second" (:content (svar/ask! session "two")))))
          (expect (= blocks (get-in (last @requests) [:body :messages 1 :content]))))))
  (it "keeps refusals visible but leaves them out of model replay"
      (let [requests (atom [])]
        (with-redefs [http/post (scripted-post requests
                                               [(response (assoc (message "msg_no" "No.")
                                                            :stop_reason "refusal"))
                                                (response (message "msg_ok" "Yes."))])]
          (with-open [session (open-thread {})]
            (expect (= "No." (:content (svar/ask! session "one"))))
            (expect (= "Yes." (:content (svar/ask! session "rephrased"))))
            (expect (= 5 (count (svar/session-history session)))))
          (expect (= [{:role "user" :content "rephrased"}]
                     (get-in (last @requests) [:body :messages])))))))

;; Audit report: invalid signatures returned on a second full replay after expiry.
(defdescribe
  thinking-replay-regression-test
  (it
    "keeps removed thinking invalid across later replays"
    (let [body
          {:model "claude-sonnet-4-6"
           :system "old"
           :messages [{:role "assistant"
                       :content [{:type "thinking" :thinking "old" :signature "signed-old"}
                                 {:type "text" :text "answer"}]}]}

          initial
          (threads/request-plan {} body {} 1 false 0)

          changed
          (threads/request-plan {:fingerprint (:fingerprint initial)}
                                (assoc body :system "new")
                                {}
                                0
                                false
                                1)

          expired
          (threads/request-plan {:fingerprint (:fingerprint changed)
                                 :invalid-thinking-count (:invalid-thinking-count changed)
                                 :cursor "msg_1"
                                 :completed-at 1}
                                (assoc body :system "new")
                                {}
                                0
                                false
                                300002)]

      (expect (= [{:type "text" :text "answer"}] (get-in changed [:body :messages 0 :content])))
      (expect (= [{:type "text" :text "answer"}] (get-in expired [:body :messages 0 :content]))))))

;; Audit report: rolling cache markers and replaced history broke refusal exclusions.
(defdescribe
  refusal-projection-regression-test
  (it "keeps refusal exclusions when cache markers move before replay"
      (let [requests (atom [])]
        (with-redefs [http/post (scripted-post requests
                                               [(response (assoc (message "msg_no" "No.")
                                                            :stop_reason "refusal"))
                                                (response (message "msg_yes" "Yes."))
                                                (response (message "msg_next" "Next."))
                                                (response (message "msg_last" "Last."))])]
          (with-open [session (open-thread {:prompt-cache-options {}})]
            (svar/ask! session "refused question")
            (svar/ask! session "rephrased")
            (svar/ask! session "more")
            (swap! (:transport-state session) assoc :completed-at 0)
            (expect (= "Last." (:content (svar/ask! session "last"))))
            (expect (= "create" (get-in (last @requests) [:body :thread :type])))
            (expect (= 5 (count (get-in (last @requests) [:body :messages]))))))))
  (it "does not remove a replacement message that follows a refused prefix"
      (let [requests (atom [])]
        (with-redefs [http/post (scripted-post requests
                                               [(response (assoc (message "msg_no" "No.")
                                                            :stop_reason "refusal"))
                                                (response (message "msg_yes" "Yes."))])]
          (with-open [session (open-thread {})]
            (svar/ask! session "original")
            (expect (= "Yes."
                       (:content (svar/ask! session
                                            {:history [(svar/user "original")
                                                       (svar/user "replacement")]}))))
            (expect (= [{:role "user" :content "original"} {:role "user" :content "replacement"}]
                       (get-in (last @requests) [:body :messages]))))))))
