(ns com.blockether.svar.internal.llm-http-heal-test
  "Regression: the shared JDK HttpClient's SelectorManager thread can die
   mid-life (interrupted/aborted exchange, or an unhandled error in its run
   loop), after which EVERY send throws 'selector manager closed' until the
   process restarts. `with-http-client-heal` must detect that, rebuild the
   client once, and retry — so a dead client self-heals instead of failing
   every subsequent turn."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.llm :as sut])
  (:import
   (java.util.concurrent RejectedExecutionException)))

(def ^:private dead?  @#'sut/dead-http-client-error?)
(def ^:private heal   @#'sut/with-http-client-heal)

(defdescribe dead-http-client-error?-test
  (describe "recognises a dead shared client"
    (it "matches 'selector manager closed' (incl. nested cause)"
      (expect (dead? (ex-info "java.io.IOException: selector manager closed" {})))
      (expect (dead? (ex-info "wrap" {} (ex-info "HttpClient is stopped" {})))))
    (it "matches RejectedExecutionException (executor shut down)"
      (expect (dead? (RejectedExecutionException. "rejected"))))
    (it "does NOT match ordinary transport errors"
      (expect (false? (dead? (ex-info "connection reset" {}))))
      (expect (false? (dead? (ex-info "HTTP 400 bad request" {})))))))

(defdescribe with-http-client-heal-test
  (describe "rebuilds + retries on a dead client"
    (it "retries exactly once and returns the second result"
      (let [calls (atom 0)
            r (heal (fn [_client]
                      (if (= 1 (swap! calls inc))
                        (throw (ex-info "selector manager closed" {}))
                        :ok)))]
        (expect (= :ok r))
        (expect (= 2 @calls)))))

  (describe "does NOT retry on a normal error"
    (it "propagates the original exception after one call"
      (let [calls (atom 0)]
        (expect (= "boom"
                  (try (heal (fn [_] (swap! calls inc) (throw (ex-info "boom" {}))))
                    (catch Exception e (ex-message e)))))
        (expect (= 1 @calls)))))

  (describe "happy path"
    (it "calls f once and returns its value"
      (let [calls (atom 0)
            r (heal (fn [_] (swap! calls inc) :done))]
        (expect (= :done r))
        (expect (= 1 @calls))))))
