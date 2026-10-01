(ns com.blockether.svar.internal.usage-test
  "Output throughput over a run of model calls."
  (:require [lazytest.core :refer [defdescribe describe expect it]]
            [com.blockether.svar.internal.usage :as sut]))

(defn- model-call
  "A call result whose response ran from `started-ms` to `responded-ms`. The
   tools the call requested run after `responded-ms`, so their time never
   reaches `:duration-ms`."
  [started-ms responded-ms output reasoning]
  {:tokens {:output output :reasoning reasoning}
   :duration-ms (- (long responded-ms) (long started-ms))})

(defdescribe
  tokens-per-second-test
  (describe "a run of model calls"
            (it "measures output throughput across calls without tool time"
                ;; Call 1 responds from 8000 to 10000 ms; its tools run until 20000 ms.
                ;; Call 2 responds from 27000 to 30000 ms; its tools run until 31000 ms.
                ;; 50 output tokens over 5 s of response time is 10 tok/s. The turn
                ;; wall time (23 s) would give 2.2 tok/s, and adding the reasoning
                ;; tokens, which output already includes, would give 13 tok/s.
                (expect (= 10.0
                           (sut/tokens-per-second [(model-call 8000 10000 20 5)
                                                   (model-call 27000 30000 30 10)]))))
            (it "counts a negative response time as zero"
                (expect (= 10.0
                           (sut/tokens-per-second [{:tokens {:output 20} :duration-ms -500}
                                                   {:tokens {:output 30} :duration-ms 5000}]))))
            (it "counts a call without token usage as zero output"
                (expect (= 5.0
                           (sut/tokens-per-second [{:duration-ms 5000}
                                                   {:tokens {:output 50} :duration-ms 5000}])))))
  (describe
    "no throughput"
    (it "omits throughput when a response time is unavailable"
        (expect (nil? (sut/tokens-per-second [{:tokens {:output 20}}])))
        (expect (nil? (sut/tokens-per-second [(model-call 0 1000 10 0) {:tokens {:output 20}}]))))
    (it "omits throughput without calls"
        (expect (nil? (sut/tokens-per-second [])))
        (expect (nil? (sut/tokens-per-second nil))))
    (it "omits throughput without output or response time"
        (expect (nil? (sut/tokens-per-second [{:tokens {:output 0} :duration-ms 1000}])))
        (expect (nil? (sut/tokens-per-second [{:tokens {:output 20} :duration-ms 0}])))
        (expect (nil? (sut/tokens-per-second [{:tokens {:output 20} :duration-ms -500}]))))))
