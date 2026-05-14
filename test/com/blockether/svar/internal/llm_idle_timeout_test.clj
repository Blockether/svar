(ns com.blockether.svar.internal.llm-idle-timeout-test
  "Regression: streaming HTTP responses that wedge mid-flight must surface
   `:svar.core/stream-idle-timeout` and release the calling thread quickly.

   Pre-fix behavior: `http-post-stream!` relied on
   `HttpRequest.Builder.timeout` to bound the call, but on JDK 25 +
   HTTP/2 streaming bodies that timer doesn't reliably fire when the
   upstream sends headers and then stalls without body frames. Real
   reproduction: Vis conv `1b7603b9-...` iter 7 sat in
   `CompletableFuture.get -> HttpClient.send` for 11+ minutes against
   z.ai glm-5.1, well past svar's 5-min `DEFAULT_TIMEOUT_MS`, with no
   exception ever raised. The idle-stream watchdog closes the
   `InputStream` after `:idle-timeout-ms` of zero inter-chunk bytes
   regardless of whether the JDK timer is alive, which is the signal
   that actually maps to \"upstream is dead\".

   These tests pin the watchdog helper in isolation (no real HTTP):

   1. fires when no bytes arrive within the timeout.
   2. does NOT fire while the producer keeps emitting bytes.
   3. cleanly exits when the caller flips `alive?` to false."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.llm :as sut])
  (:import
   (java.io ByteArrayInputStream PipedInputStream PipedOutputStream)
   (java.util.concurrent.atomic AtomicBoolean)))

(def ^:private start-watchdog @#'sut/start-idle-stream-watchdog!)

(defn- close-tracking-stream
  "Wraps `delegate` and atomically sets `closed?` when `.close` is invoked.
   Lets the test observe whether the watchdog actually called close on the
   stream (vs. just exiting via the alive? flag)."
  ^java.io.InputStream [^java.io.InputStream delegate ^AtomicBoolean closed?]
  (proxy [java.io.InputStream] []
    (read
      ([] (.read delegate))
      ([b] (.read delegate ^bytes b))
      ([b off len] (.read delegate ^bytes b (int off) (int len))))
    (close []
      (.set closed? true)
      (.close delegate))
    (available [] (.available delegate))))

(defdescribe idle-stream-watchdog-test
  (describe "fires when inter-chunk gap exceeds idle-timeout-ms"
    (it "closes the stream and invokes on-fire once"
      (let [closed?       (AtomicBoolean. false)
            stream        (close-tracking-stream
                            (ByteArrayInputStream. (byte-array 0))
                            closed?)
            last-byte-ns  (atom (System/nanoTime))
            alive?        (atom true)
            fired-elapsed (atom nil)
            on-fire       (fn [elapsed-ms] (reset! fired-elapsed elapsed-ms))
            ;; 200ms is plenty: watchdog checks every ~50ms, so it should
            ;; observe the staleness on the second tick (~200-250ms in).
            idle-ms       200
            t             (start-watchdog stream idle-ms last-byte-ns alive? on-fire)]
        ;; Give the watchdog a hard upper bound to fire. Anything more than
        ;; ~2x idle-ms is a regression. Poll with 25ms granularity.
        (loop [waited 0]
          (cond
            (.get closed?)   :done
            (>= waited 1000) :timed-out
            :else            (do (Thread/sleep 25) (recur (+ waited 25)))))
        (reset! alive? false)
        (.interrupt ^Thread t)
        (expect (.get closed?))
        (expect (number? @fired-elapsed))
        (expect (>= (long @fired-elapsed) idle-ms)))))

  (describe "does NOT fire while bytes are flowing"
    (it "keeps the stream alive across multiple writes"
      (let [pipe-out      (PipedOutputStream.)
            pipe-in       (PipedInputStream. pipe-out 1024)
            closed?       (AtomicBoolean. false)
            stream        (close-tracking-stream pipe-in closed?)
            last-byte-ns  (atom (System/nanoTime))
            alive?        (atom true)
            fired?        (atom false)
            on-fire       (fn [_] (reset! fired? true))
            idle-ms       250
            t             (start-watchdog stream idle-ms last-byte-ns alive? on-fire)]
        ;; Simulate a healthy SSE producer: write a byte every 80ms for
        ;; 600ms total. Each write bumps last-byte-ns, so the watchdog
        ;; never sees a 250ms idle window.
        (try
          (dotimes [_ 8]
            (Thread/sleep 80)
            (.write pipe-out (int 42))
            (.flush pipe-out)
            (reset! last-byte-ns (System/nanoTime)))
          (finally
            (reset! alive? false)
            (.interrupt ^Thread t)
            (.close pipe-out)))
        (expect (false? (.get closed?)))
        (expect (false? @fired?)))))

  (describe "exits cleanly on alive? false"
    (it "does not call close after caller signals shutdown"
      (let [closed?      (AtomicBoolean. false)
            stream       (close-tracking-stream
                           (ByteArrayInputStream. (byte-array 0))
                           closed?)
            last-byte-ns (atom (System/nanoTime))
            alive?       (atom true)
            fired?       (atom false)
            on-fire      (fn [_] (reset! fired? true))
            t            (start-watchdog stream 5000 last-byte-ns alive? on-fire)]
        ;; Caller flips alive? before idle-timeout-ms elapses; watchdog
        ;; should wake from sleep, see alive? false, exit the loop.
        (Thread/sleep 50)
        (reset! alive? false)
        (.interrupt ^Thread t)
        (.join ^Thread t 1000)
        (expect (false? (.isAlive t)))
        (expect (false? (.get closed?)))
        (expect (false? @fired?))))))
