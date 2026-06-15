(ns com.blockether.svar.internal.llm-cancel-test
  "Regression: caller-driven cancellation (`*cancel-fn*`) must abort an
   in-flight streaming call FAST, in every phase. A blocking JDK socket
   read ignores `Thread.interrupt()`, so the cancel watchdog applies both
   levers on fire:

   - closes the body `InputStream` (unblocks a parked post-headers
     `.readLine`), and
   - interrupts the caller thread (unparks the pre-headers
     `CompletableFuture.get` and any retry/backoff `Thread/sleep`).

   The reader loop additionally checks the flag between SSE lines so an
   actively-streaming response breaks within one delta. These tests pin
   `start-cancel-watchdog!` in isolation (no real HTTP)."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.llm :as sut])
  (:import
   (java.io ByteArrayInputStream)
   (java.util.concurrent.atomic AtomicBoolean)))

(def ^:private start-cancel-watchdog @#'sut/start-cancel-watchdog!)

(defn- close-tracking-stream
  "Wraps `delegate`, setting `closed?` when `.close` is invoked, so the test
   can observe the watchdog's stream-close lever."
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

(defdescribe cancel-watchdog-test
  (describe "fires when cancel-requested? becomes true, POST-headers (stream present)"
    (it "closes the stream and sets cancel-fired? once, WITHOUT interrupting the caller"
      ;; Post-headers the caller is parked in OUR read loop, so the watchdog
      ;; closes the body to unblock `.readLine` and deliberately does NOT
      ;; interrupt — interrupting the shared JDK client mid-send wedges its
      ;; SelectorManager ("selector manager closed" on every later send).
      (let [caller        (Thread/currentThread)
            closed?       (AtomicBoolean. false)
            stream        (close-tracking-stream
                            (ByteArrayInputStream. (byte-array 0))
                            closed?)
            stream-ref    (atom stream)
            cancel-flag   (atom false)
            cancel-req?   (fn [] @cancel-flag)
            cancel-fired? (atom false)
            alive?        (atom true)
            _             (Thread/interrupted) ; clear leftover interrupt
            t             (start-cancel-watchdog caller cancel-req?
                            stream-ref cancel-fired? alive?)]
        (reset! cancel-flag true)
        ;; Deterministic wait: poll the fired flag (watchdog polls every ~50ms)
        ;; up to ~2s instead of racing a fixed sleep.
        (loop [n 0]
          (when (and (not @cancel-fired?) (< n 100))
            (Thread/sleep 20)
            (recur (inc n))))
        (reset! alive? false)
        (.interrupt ^Thread t)
        (expect (true? @cancel-fired?))
        (expect (.get closed?))
        ;; The caller must NOT have been interrupted on the stream-present path.
        ;; `Thread/interrupted` reads+clears this thread's flag.
        (expect (false? (Thread/interrupted))))))

  (describe "fires when cancel-requested? becomes true, PRE-headers (no stream yet)"
    (it "interrupts the caller and sets cancel-fired?, with no stream to close"
      ;; Before the body arrives the caller is parked in HttpClient.send ->
      ;; CompletableFuture.get; the only lever is interrupting it.
      (let [caller        (Thread/currentThread)
            stream-ref    (atom nil)
            cancel-flag   (atom false)
            cancel-req?   (fn [] @cancel-flag)
            cancel-fired? (atom false)
            alive?        (atom true)
            _             (Thread/interrupted)
            t             (start-cancel-watchdog caller cancel-req?
                            stream-ref cancel-fired? alive?)
            interrupted?  (try
                            (reset! cancel-flag true)
                            (Thread/sleep 2000)
                            false
                            (catch InterruptedException _ true))]
        (reset! alive? false)
        (.interrupt ^Thread t)
        (expect interrupted?)
        (expect (true? @cancel-fired?)))))

  (describe "does NOT fire while cancel-requested? stays false"
    (it "leaves the stream open, caller uninterrupted, cancel-fired? false"
      (let [caller        (Thread/currentThread)
            closed?       (AtomicBoolean. false)
            stream        (close-tracking-stream
                            (ByteArrayInputStream. (byte-array 0))
                            closed?)
            stream-ref    (atom stream)
            cancel-req?   (fn [] false)
            cancel-fired? (atom false)
            alive?        (atom true)
            _             (Thread/interrupted)
            t             (start-cancel-watchdog caller cancel-req?
                            stream-ref cancel-fired? alive?)
            interrupted?  (try (Thread/sleep 400) false
                               (catch InterruptedException _ true))]
        (reset! alive? false)
        (.interrupt ^Thread t)
        (expect (false? interrupted?))
        (expect (false? @cancel-fired?))
        (expect (false? (.get closed?))))))

  (describe "exits cleanly on alive? false"
    (it "does not fire after caller signals shutdown"
      (let [caller        (Thread/currentThread)
            closed?       (AtomicBoolean. false)
            stream        (close-tracking-stream
                            (ByteArrayInputStream. (byte-array 0))
                            closed?)
            stream-ref    (atom stream)
            cancel-fired? (atom false)
            alive?        (atom true)
            _             (Thread/interrupted)
            t             (start-cancel-watchdog caller (fn [] false)
                            stream-ref cancel-fired? alive?)]
        (Thread/sleep 50)
        (reset! alive? false)
        (.interrupt ^Thread t)
        (.join ^Thread t 1000)
        (expect (false? (.isAlive t)))
        (expect (false? @cancel-fired?))
        (expect (false? (.get closed?)))))))
