(ns com.blockether.svar.internal.llm-ttft-propagation-test
  "Regression: a pre-headers TTFT watchdog abort must reach the ROUTER carrying its
   own type.

   `http-post-stream!` raises `:svar.core/stream-ttft-timeout` when no response
   headers arrived within the TTFT window. `chat-completion-streaming` used to
   re-wrap it as a generic `:svar.core/http-error`, because
   `stream-finalization-error?` did not know the type. The router's
   `transient-error?` then saw neither a watchdog type nor the phrase
   \"timed out\" in the message, so it declined to fall back and a request that
   never left the ground — zero bytes streamed, nothing side-effecting — became a
   terminal turn failure with no retry anywhere."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.failure :as failure]
   [com.blockether.svar.internal.llm :as sut]))

(def ^:private finalization? @#'sut/stream-finalization-error?)

(defn- ttft-timeout-ex []
  (ex-info "Stream TTFT timeout (300000ms with no response headers): "
    {:type :svar.core/stream-ttft-timeout
     :stream? true
     :url "https://example.test/v1/messages"
     :ttft-timeout-ms 300000}))

(defdescribe ttft-timeout-propagation-test
  (describe "the TTFT abort is a stream finalization error"
    (it "propagates verbatim instead of being re-wrapped as :svar.core/http-error"
      (expect (true? (finalization? (ttft-timeout-ex))))))

  (describe "the router can act on it"
    (it "calls the typed abort transient, so provider fallback is offered"
      (expect (true? (failure/transient-error? (ttft-timeout-ex)))))

    (it "cannot recognise the re-wrapped shape — which is why the type must survive"
      (expect (false? (failure/transient-error?
                        (ex-info "Stream TTFT timeout (300000ms with no response headers): "
                          {:type :svar.core/http-error :stream? true})))))))
