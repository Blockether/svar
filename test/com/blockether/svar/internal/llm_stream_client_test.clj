(ns com.blockether.svar.internal.llm-stream-client-test
  "Regression: the streaming POST must reuse the HTTP/1.1-pinned shared client.

   `http-post-stream!` previously omitted `:client`, so streaming fell back to
   babashka's default HTTP/2 client. Local servers (LM Studio, Ollama) hang on
   HTTP/2 streaming bodies — the call wedged with zero SSE chunks. Non-streaming
   `http-post!` always passed the shared (HTTP/1.1) client, masking the bug for
   `:on-chunk`-less calls."
  (:require
   [babashka.http-client :as http]
   [clojure.string :as str]
   [lazytest.core :refer [defdescribe expect it]]
   [com.blockether.svar.internal.llm :as sut])
  (:import
   (java.io ByteArrayInputStream)))

(def ^:private http-post-stream! @#'sut/http-post-stream!)
(def ^:private extract-stream-delta @#'sut/extract-stream-delta)
(def ^:private current-http-client @#'sut/current-http-client)

(defn- sse-stream
  "Minimal terminating OpenAI-style SSE body."
  ^java.io.InputStream []
  (ByteArrayInputStream.
    (.getBytes
      (str "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
        "data: [DONE]\n\n")
      "UTF-8")))

(defdescribe streaming-uses-shared-http1-client-test
  (it "passes :client = the HTTP/1.1-pinned shared client to http/post"
    (let [captured (atom nil)]
      (with-redefs [http/post (fn [_url opts]
                                (reset! captured opts)
                                {:status 200 :headers {} :body (sse-stream)})]
        ;; ttft/idle 0 → watchdogs disabled; this returns once the SSE ends.
        (http-post-stream! "http://localhost:1234/v1/chat/completions"
          {:model "m" :messages []} {} 5000 0 0 extract-stream-delta (fn [_])))
      (expect (some? @captured))
      ;; The exact client instance — not merely present, but the shared one
      ;; (which is built with :version :http1.1).
      (expect (identical? (current-http-client) (:client @captured)))
      ;; And it must be a streaming request.
      (expect (= :stream (:as @captured))))))

(defn- sse-body
  "Raw SSE stream from `[event-type json-data-line]` pairs."
  ^java.io.InputStream [pairs]
  (ByteArrayInputStream.
    (.getBytes
      ^String (apply str (for [[t d] pairs] (str "event: " t "\ndata: " d "\n\n")))
      "UTF-8")))

(defdescribe response-failed-surfaces-provider-error-test
  ;; Regression: a Responses `response.failed` event (rate limit, upstream
  ;; error) was dropped on the floor - the stream ended "cleanly" with no
  ;; output and was misclassified as `:svar.llm/empty-content`, so the
  ;; empty-reply ladder re-sent the SAME request 3x into the SAME failure and
  ;; the user saw a bogus "Model returned an empty response" instead of the
  ;; provider's actual error. Codex parses this event into a typed error.
  (it "throws typed :svar.core/stream-failed carrying the provider code+message"
    (let [body (sse-body
                 [["response.created"
                   "{\"type\":\"response.created\",\"response\":{\"status\":\"in_progress\"}}"]
                  ["response.failed"
                   (str "{\"type\":\"response.failed\",\"response\":{\"status\":\"failed\","
                     "\"error\":{\"code\":\"rate_limit_exceeded\","
                     "\"message\":\"Rate limit reached. Please try again in 11.054s.\"}}}")]])
          ex (try
               (with-redefs [http/post (fn [_url _opts] {:status 200 :headers {} :body body})]
                 (http-post-stream! "http://localhost:1234/v1/responses"
                   {:model "m"} {} 5000 0 0 extract-stream-delta (fn [_]))
                 nil)
               (catch clojure.lang.ExceptionInfo e e))]
      (expect (some? ex))
      (let [data (ex-data ex)]
        (expect (= :svar.core/stream-failed (:type data)))
        (expect (= "rate_limit_exceeded" (:provider-error-code data)))
        (expect (= 429 (:status data)))
        (expect (str/includes? (ex-message ex) "Rate limit reached"))))))

(defdescribe anthropic-mid-stream-error-surfaces-transient-status-test
  ;; Regression: Anthropic (and Copilot-Claude on /v1/messages) signal a burst
  ;; via a mid-stream SSE `error` event whose KIND is under `:type`
  ;; (`overloaded_error` / `rate_limit_error` / `api_error`), NOT `:code`.
  ;; stream-failed-error read only :code → status nil → router-transient-error?
  ;; false → single-provider tab died instantly instead of retrying. Now the
  ;; error :type maps to the transient HTTP-status equivalent.
  (it "maps Anthropic error :type to a transient :status so the router retries"
    (doseq [[etype expected-status] [["overloaded_error" 529]
                                     ["rate_limit_error" 429]
                                     ["api_error" 500]]]
      (let [body (sse-body
                   [["error"
                     (str "{\"type\":\"error\",\"error\":{\"type\":\"" etype
                       "\",\"message\":\"boom\"}}")]])
            ex (try
                 (with-redefs [http/post (fn [_url _opts] {:status 200 :headers {} :body body})]
                   (http-post-stream! "http://localhost:1234/v1/messages"
                     {:model "m"} {} 5000 0 0 extract-stream-delta (fn [_]))
                   nil)
                 (catch clojure.lang.ExceptionInfo e e))]
        (expect (some? ex))
        (let [data (ex-data ex)]
          (expect (= :svar.core/stream-failed (:type data)))
          (expect (= etype (:provider-error-code data)))
          (expect (= expected-status (:status data)))))))
  ;; Cross-validated with opencode `parseStreamError` (packages/opencode/src/provider/error.ts):
  ;; it retries mid-stream `server_is_overloaded`/`server_error` (isRetryable true) and
  ;; treats `insufficient_quota`/`usage_not_included`/`invalid_prompt` as non-retryable.
  ;; These OpenAI Codex/ChatGPT-backend codes arrive under error `:code` (not `:type`).
  (it "maps OpenAI Codex error :code to a transient :status (opencode parseStreamError parity)"
    (doseq [[ecode expected-status] [["server_is_overloaded" 529]
                                     ["server_error" 500]
                                     ["overloaded" 529]
                                     ["rate_limit_exceeded" 429]]]
      (let [body (sse-body
                   [["error"
                     (str "{\"type\":\"error\",\"error\":{\"code\":\"" ecode
                       "\",\"message\":\"boom\"}}")]])
            ex (try
                 (with-redefs [http/post (fn [_url _opts] {:status 200 :headers {} :body body})]
                   (http-post-stream! "http://localhost:1234/v1/responses"
                     {:model "m"} {} 5000 0 0 extract-stream-delta (fn [_]))
                   nil)
                 (catch clojure.lang.ExceptionInfo e e))]
        (expect (some? ex))
        (let [data (ex-data ex)]
          (expect (= :svar.core/stream-failed (:type data)))
          (expect (= ecode (:provider-error-code data)))
          (expect (= expected-status (:status data))))))))

(defdescribe reasoning-summary-cross-item-boundary-test
  ;; Regression: the "\n\n" summary-part separator was only emitted WITHIN one
  ;; reasoning item; a second reasoning item's first headline glued onto the
  ;; previous item's last one as `...statuses****Planning...` in the TUI.
  (it "separates summary text across reasoning ITEMS (no ** glue)"
    (let [body (sse-body
                 [["response.output_item.added"
                   "{\"type\":\"response.output_item.added\",\"item\":{\"type\":\"reasoning\",\"id\":\"r1\",\"summary\":[]}}"]
                  ["response.reasoning_summary_part.added"
                   "{\"type\":\"response.reasoning_summary_part.added\",\"part\":{\"type\":\"summary_text\",\"text\":\"\"}}"]
                  ["response.reasoning_summary_text.delta"
                   "{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"**First**\"}"]
                  ["response.output_item.done"
                   "{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"reasoning\",\"id\":\"r1\"}}"]
                  ["response.output_item.added"
                   "{\"type\":\"response.output_item.added\",\"item\":{\"type\":\"reasoning\",\"id\":\"r2\",\"summary\":[]}}"]
                  ["response.reasoning_summary_part.added"
                   "{\"type\":\"response.reasoning_summary_part.added\",\"part\":{\"type\":\"summary_text\",\"text\":\"\"}}"]
                  ["response.reasoning_summary_text.delta"
                   "{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"**Second**\"}"]
                  ["response.completed"
                   "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}"]])
          reasoning-final (atom nil)]
      (with-redefs [http/post (fn [_ _] {:status 200 :headers {} :body body})]
        (http-post-stream! "http://localhost:1234/v1/responses"
          {:model "m"} {} 5000 0 0 extract-stream-delta
          (fn [{:keys [reasoning-acc]}]
            (when (seq reasoning-acc) (reset! reasoning-final reasoning-acc)))))
      (expect (= "**First**\n\n**Second**" @reasoning-final))
      (expect (not (str/includes? (str @reasoning-final) "****"))))))
