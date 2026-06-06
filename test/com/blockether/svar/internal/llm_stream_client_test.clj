(ns com.blockether.svar.internal.llm-stream-client-test
  "Regression: the streaming POST must reuse the HTTP/1.1-pinned shared client.

   `http-post-stream!` previously omitted `:client`, so streaming fell back to
   babashka's default HTTP/2 client. Local servers (LM Studio, Ollama) hang on
   HTTP/2 streaming bodies — the call wedged with zero SSE chunks. Non-streaming
   `http-post!` always passed the shared (HTTP/1.1) client, masking the bug for
   `:on-chunk`-less calls."
  (:require
   [babashka.http-client :as http]
   [lazytest.core :refer [defdescribe expect it]]
   [com.blockether.svar.internal.llm :as sut])
  (:import
   (java.io ByteArrayInputStream)))

(def ^:private http-post-stream! @#'sut/http-post-stream!)
(def ^:private extract-stream-delta @#'sut/extract-stream-delta)
(def ^:private shared-http-client @#'sut/shared-http-client)

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
      (expect (identical? @shared-http-client (:client @captured)))
      ;; And it must be a streaming request.
      (expect (= :stream (:as @captured))))))
