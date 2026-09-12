(ns com.blockether.svar.internal.llm-interrupt-reclassify-test
  "Regression: the pre-headers `HttpClient.send` is declared
   `throws InterruptedException`, so a caller interrupt (our TTFT/cancel
   watchdog lever, or an external one) can escape RAW — unwrapped past the
   ExceptionInfo/IOException catches in `http-post-stream!`. Before the fix it
   leaked as a BARE `InterruptedException`, which downstream retry layers
   (vis's `call-provider-with-interrupt-retry!`) misread as a spurious blip and
   re-sent, doubling an already-elapsed stall into a second full timeout window.

   `reclassify-pre-headers-interrupt!` reclassifies OUR OWN watchdog fires into
   the same typed errors as the wrapped paths and propagates a genuinely
   external interrupt verbatim. Tests cover classification and the real local
   HTTP watchdog through the router boundary."
  (:require [lazytest.core :refer [defdescribe describe expect it throws?]]
            [com.blockether.svar.internal.llm :as sut]
            [com.blockether.svar.internal.router :as router])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors TimeUnit]))

(def ^:private reclassify @#'sut/reclassify-pre-headers-interrupt!)

(def ^:private url "https://example.test/v1/chat")

(def ^:private ttft-ms 1500)

(defdescribe
  reclassify-pre-headers-interrupt-test
  (describe
    "cancel watchdog fired: raw interrupt -> typed :stream-cancelled"
    (it
      "throws ex-info typed :svar.core/stream-cancelled, chaining the cause, clearing the interrupt"
      (let [raw
            (InterruptedException. "parked send interrupted")

            ;; Fresh flag so an unrelated test's interrupt can't leak in.
            _
            (Thread/interrupted)

            ex
            (try (reclassify raw (atom true) (atom false) url ttft-ms)
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]

        (expect (some? ex))
        (expect (= :svar.core/stream-cancelled (:type (ex-data ex))))
        (expect (true? (:stream? (ex-data ex))))
        (expect (= url (:url (ex-data ex))))
        (expect (identical? raw (ex-cause ex)))
        ;; The flag must be CONSUMED (Thread/interrupted clears it) so it does
        ;; not poison unrelated code further up the stack.
        (expect (false? (Thread/interrupted))))))
  (describe
    "TTFT watchdog fired: raw interrupt -> typed :stream-ttft-timeout"
    (it "throws ex-info typed :svar.core/stream-ttft-timeout carrying ttft-timeout-ms + cause-class"
        (let [raw
              (InterruptedException. "no headers")

              _
              (Thread/interrupted)

              ex
              (try (reclassify raw (atom false) (atom true) url ttft-ms)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))

              ed
              (ex-data ex)]

          (expect (some? ex))
          (expect (= :svar.core/stream-ttft-timeout (:type ed)))
          (expect (true? (:stream? ed)))
          (expect (= url (:url ed)))
          (expect (= ttft-ms (:ttft-timeout-ms ed)))
          (expect (= "java.lang.InterruptedException" (:cause-class ed)))
          (expect (identical? raw (ex-cause ex)))
          (expect (false? (Thread/interrupted))))))
  (describe
    "neither watchdog fired: genuinely external interrupt"
    (it "re-throws the SAME InterruptedException (not reclassified) and restores the interrupt flag"
        (let [raw
              (InterruptedException. "external")

              _
              (Thread/interrupted)

              ex
              (try (reclassify raw (atom false) (atom false) url ttft-ms)
                   nil
                   ;; Must be the raw InterruptedException, NOT an ExceptionInfo.
                   (catch InterruptedException e e))

              ;; Read (and clear) the flag once; the helper restored it before throw.
              flag-was-set?
              (Thread/interrupted)]

          (expect (identical? raw ex))
          (expect (not (instance? clojure.lang.ExceptionInfo ex)))
          (expect (true? flag-was-set?)))))
  (describe "cancel takes precedence over TTFT when both fired"
            (it "classifies as :stream-cancelled"
                (let [raw
                      (InterruptedException.)

                      _
                      (Thread/interrupted)

                      ex
                      (try (reclassify raw (atom true) (atom true) url ttft-ms)
                           nil
                           (catch clojure.lang.ExceptionInfo e e))]

                  (expect (= :svar.core/stream-cancelled (:type (ex-data ex))))
                  (Thread/interrupted))))
  (describe "always throws"
            (it "never returns normally for any flag combination"
                (doseq [[c t] [[false false] [true false] [false true] [true true]]]
                  (Thread/interrupted)
                  (expect (throws?
                            Throwable
                            #(reclassify (InterruptedException.) (atom c) (atom t) url ttft-ms)))
                  (Thread/interrupted)))))

;; Blockether/vis#210: the typed timeout has already consumed its own interrupt.
(defdescribe
  routed-watchdog-interrupt-test
  (it
    "leaves a real pre-header timeout safe for nonzero caller backoff and a second request"
    (let [server
          (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)

          executor
          (Executors/newCachedThreadPool)

          requests
          (atom 0)

          release-first
          (promise)

          r
          (sut/make-router
            [{:id :local :api-key "test" :base-url "http://127.0.0.1" :models [{:name "gpt-4o"}]}]
            {:rate-limit {:same-provider-delays-ms []}})]

      (.createContext
        server
        "/stream"
        (reify
          HttpHandler
            (handle [_ exchange]
              (let [^HttpExchange exchange exchange]
                (try (.readAllBytes (.getRequestBody exchange))
                     (if (= 1 (swap! requests inc))
                       (deref release-first 5000 nil)
                       (do (.set (.getResponseHeaders exchange) "content-type" "text/event-stream")
                           (.sendResponseHeaders exchange 200 0)
                           (with-open [out (.getResponseBody exchange)]
                             (.write out
                                     (.getBytes "data: {\"text\":\"ok\"}\n\ndata: [DONE]\n\n"
                                                "UTF-8")))))
                     (finally (.close exchange)))))))
      (.setExecutor server executor)
      (.start server)
      (try
        (let [url
              (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/stream")

              cancel?
              (atom false)

              request!
              (fn []
                (binding [sut/*cancel-fn*
                          #(deref cancel?)

                          sut/*stream-semantic-timeout-ms*
                          1000]

                  (router/with-provider-fallback r
                                                 {}
                                                 (fn [_ _]
                                                   (@#'sut/http-post-stream!
                                                    url
                                                    {:stream true}
                                                    {"content-type" "application/json"}
                                                    5000
                                                    500
                                                    1000
                                                    (fn [chunk]
                                                      {:content-delta (get chunk "text")})
                                                    (fn [_]))))))

              error
              (try (request!) nil (catch Exception e e))]

          (expect (= :svar.core/stream-ttft-timeout
                     (some #(when (= :svar.core/stream-ttft-timeout (:type (ex-data %)))
                              (:type (ex-data %)))
                           (take-while some? (iterate ex-cause error)))))
          (expect (= 1 @requests))
          (expect (false? (.isInterrupted (Thread/currentThread))))
          ;; Do not clear here: the original failure is this exact sleep throwing.
          (Thread/sleep 25)
          (deliver release-first true)
          (expect (= "ok" (:content (request!))))
          (expect (= 2 @requests))
          ;; Blockether/vis#210: an abandoned pre-header cancellation poll must not
          ;; interrupt later work when the old caller predicate becomes true.
          (reset! cancel? true)
          (Thread/sleep 1200)
          (expect (false? (.isInterrupted (Thread/currentThread)))))
        (finally (Thread/interrupted)
                 (deliver release-first true)
                 (.stop server 0)
                 (.shutdownNow executor)
                 (.awaitTermination executor 5 TimeUnit/SECONDS)))))
  (it "preserves external interrupts, cancellation, and Stop racing with a typed timeout"
      (doseq [[error live-interrupt?] [[(InterruptedException. "Stop") false]
                                       [(ex-info "wrapper" {} (InterruptedException. "Stop")) false]
                                       [(ex-info "cancelled"
                                                 {:type :svar.core/stream-cancelled}
                                                 (InterruptedException. "Stop")) false]
                                       [(ex-info "timeout"
                                                 {:type :svar.core/stream-ttft-timeout}
                                                 (InterruptedException. "watchdog")) true]]]
        (try (when live-interrupt? (.interrupt (Thread/currentThread)))
             (let [caught (try (@#'router/propagate-interrupt! error) nil (catch Exception e e))]
               (expect (identical? error caught))
               (expect (.isInterrupted (Thread/currentThread))))
             (finally (Thread/interrupted))))))
