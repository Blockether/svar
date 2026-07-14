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
   external interrupt verbatim. These tests pin it in isolation (no real HTTP,
   no threads)."
  (:require
   [lazytest.core :refer [defdescribe describe expect it throws?]]
   [com.blockether.svar.internal.llm :as sut]))

(def ^:private reclassify @#'sut/reclassify-pre-headers-interrupt!)

(def ^:private url "https://example.test/v1/chat")
(def ^:private ttft-ms 1500)

(defdescribe reclassify-pre-headers-interrupt-test
  (describe "cancel watchdog fired: raw interrupt -> typed :stream-cancelled"
    (it "throws ex-info typed :svar.core/stream-cancelled, chaining the cause, clearing the interrupt"
      (let [raw (InterruptedException. "parked send interrupted")
            ;; Fresh flag so an unrelated test's interrupt can't leak in.
            _   (Thread/interrupted)
            ex  (try (reclassify raw (atom true) (atom false) url ttft-ms)
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

  (describe "TTFT watchdog fired: raw interrupt -> typed :stream-ttft-timeout"
    (it "throws ex-info typed :svar.core/stream-ttft-timeout carrying ttft-timeout-ms + cause-class"
      (let [raw (InterruptedException. "no headers")
            _   (Thread/interrupted)
            ex  (try (reclassify raw (atom false) (atom true) url ttft-ms)
                     nil
                     (catch clojure.lang.ExceptionInfo e e))
            ed  (ex-data ex)]
        (expect (some? ex))
        (expect (= :svar.core/stream-ttft-timeout (:type ed)))
        (expect (true? (:stream? ed)))
        (expect (= url (:url ed)))
        (expect (= ttft-ms (:ttft-timeout-ms ed)))
        (expect (= "java.lang.InterruptedException" (:cause-class ed)))
        (expect (identical? raw (ex-cause ex)))
        (expect (false? (Thread/interrupted))))))

  (describe "neither watchdog fired: genuinely external interrupt"
    (it "re-throws the SAME InterruptedException (not reclassified) and restores the interrupt flag"
      (let [raw (InterruptedException. "external")
            _   (Thread/interrupted)
            ex  (try (reclassify raw (atom false) (atom false) url ttft-ms)
                     nil
                     ;; Must be the raw InterruptedException, NOT an ExceptionInfo.
                     (catch InterruptedException e e))
            ;; Read (and clear) the flag once; the helper restored it before throw.
            flag-was-set? (Thread/interrupted)]
        (expect (identical? raw ex))
        (expect (not (instance? clojure.lang.ExceptionInfo ex)))
        (expect (true? flag-was-set?)))))

  (describe "cancel takes precedence over TTFT when both fired"
    (it "classifies as :stream-cancelled"
      (let [raw (InterruptedException.)
            _   (Thread/interrupted)
            ex  (try (reclassify raw (atom true) (atom true) url ttft-ms)
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (expect (= :svar.core/stream-cancelled (:type (ex-data ex))))
        (Thread/interrupted))))

  (describe "always throws"
    (it "never returns normally for any flag combination"
      (doseq [[c t] [[false false] [true false] [false true] [true true]]]
        (Thread/interrupted)
        (expect (throws? Throwable
                  #(reclassify (InterruptedException.) (atom c) (atom t) url ttft-ms)))
        (Thread/interrupted)))))
