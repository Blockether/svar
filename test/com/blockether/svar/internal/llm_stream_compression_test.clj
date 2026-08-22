(ns com.blockether.svar.internal.llm-stream-compression-test
  "Regression: an SSE stream must never be transport-compressed.

   `http-post-stream!` inherits `babashka.http-client`'s default client opts,
   which advertise `accept-encoding: gzip, deflate` and wrap the response body
   in a `GZIPInputStream`. Upstreams that honour it (api.anthropic.com does)
   then answer `content-encoding: gzip`, and the decompressor cannot yield the
   first line until it has buffered a whole deflate block - so the entire model
   turn arrived in one 1-2ms burst after 7-28s of silence, no matter how
   correctly the SSE loop below reads line by line.

   The test drives the real `http-post-stream!` against a local SSE server that
   gzips (and therefore withholds) its body whenever the client asks for gzip,
   and streams plainly otherwise."
  (:require [lazytest.core :refer [defdescribe describe expect it]]
            [com.blockether.svar.internal.llm :as sut])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.util.zip GZIPOutputStream)))

(def ^:private post-stream! @#'sut/http-post-stream!)

(def ^:private event-count 5)
(def ^:private event-gap-ms 120)

(defn- write-events!
  "Writes `event-count` SSE events, `event-gap-ms` apart, then the DONE marker."
  [^java.io.OutputStream out]
  (dotimes [i event-count]
    (Thread/sleep event-gap-ms)
    (.write out (.getBytes (str "data: {\"text\":\"tok" i "\"}\n\n") "UTF-8"))
    (.flush out))
  (.write out (.getBytes "data: [DONE]\n\n" "UTF-8"))
  (.flush out))

(defn- start-sse-server!
  "Local SSE endpoint. Records the request's accept-encoding in `seen`.
   Asked for gzip, it answers gzip - and a gzip body only reaches the client
   when the stream is closed, which is exactly the production dam."
  [seen]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
      server
      "/v1/stream"
      (reify
        HttpHandler
          (handle [_ exchange]
            (let [^HttpExchange exchange exchange
                  accept (or (.getFirst (.getRequestHeaders exchange) "accept-encoding") "")
                  gzip? (boolean (re-find #"(?i)gzip" accept))]

              (reset! seen accept)
              (.readAllBytes (.getRequestBody exchange))
              (.set (.getResponseHeaders exchange) "content-type" "text/event-stream")
              (when gzip? (.set (.getResponseHeaders exchange) "content-encoding" "gzip"))
              (.sendResponseHeaders exchange 200 0)
              (let [raw (.getResponseBody exchange)]
                (with-open [out (if gzip? (GZIPOutputStream. raw) raw)]
                  (write-events! out)))
              (.close exchange)))))
    (.setExecutor server (java.util.concurrent.Executors/newCachedThreadPool))
    (.start server)
    server))

(defdescribe
  stream-is-never-compressed-test
  (describe
    "SSE POST asks for identity and receives deltas as they are produced"
    (it
      "sees the first delta long before the stream finishes"
      (let [seen
            (atom nil)

            server
            (start-sse-server! seen)

            port
            (.getPort (.getAddress server))

            url
            (str "http://127.0.0.1:" port "/v1/stream")

            start
            (System/nanoTime)

            first-delta-ms
            (atom nil)

            elapsed-ms
            (fn []
              (long (/ (- (System/nanoTime) start) 1000000)))]

        (try (post-stream! url
                           {:stream true}
                           {"content-type" "application/json"}
                           30000
                           nil
                           nil
                           (fn [chunk]
                             {:content-delta (get chunk "text")})
                           (fn [{:keys [content-delta]}]
                             (when (and content-delta (nil? @first-delta-ms))
                               (reset! first-delta-ms (elapsed-ms)))))
             (finally (.stop server 0)))
        ;; The server takes ~600ms to emit its last event; a live stream shows
        ;; the first token after ~120ms. Pre-fix (gzip) nothing arrives until
        ;; the body closes, so this is the assertion that was red.
        (expect (number? @first-delta-ms))
        (expect (< (long @first-delta-ms) (long (* event-gap-ms (dec event-count)))))
        (expect (= "identity" @seen))))))
