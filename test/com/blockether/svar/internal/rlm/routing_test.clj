(ns com.blockether.svar.internal.rlm.routing-test
  "Tests for sub-rlm-query routing — depth tracking, concurrency safety.

   Critical: the depth check must be atomic. Without CAS-style swap!,
   concurrent sub-rlm-query-batch calls could race past *max-recursion-depth*."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.rlm.routing :as routing]
   [com.blockether.svar.internal.rlm.schema :as schema]))

(defn- run-with-depth-tracking
  "Invokes private with-depth-tracking via var deref."
  [depth-atom f]
  ((var-get #'routing/with-depth-tracking) depth-atom {} f))

(defdescribe with-depth-tracking-basic-test
  (describe "with-depth-tracking (private)"
    (it "increments and decrements depth around f"
      (let [depth-atom (atom 0)
            observed (atom nil)]
        (binding [schema/*max-recursion-depth* 3]
          (run-with-depth-tracking depth-atom
            (fn [] (reset! observed @depth-atom) :ok))
          (expect (= 1 @observed))
          (expect (= 0 @depth-atom)))))

    (it "returns error map when depth already at limit"
      (let [depth-atom (atom 3)
            called? (atom false)]
        (binding [schema/*max-recursion-depth* 3]
          (let [result (run-with-depth-tracking depth-atom
                         (fn [] (reset! called? true) :should-not-run))]
            (expect (true? (:error result)))
            (expect (false? @called?))
            (expect (= 3 @depth-atom))))))

    (it "catches exceptions from f and surfaces them as error maps"
      (let [depth-atom (atom 0)]
        (binding [schema/*max-recursion-depth* 3]
          (let [result (run-with-depth-tracking depth-atom
                         (fn [] (throw (ex-info "boom" {}))))]
            (expect (true? (:error result)))
            (expect (= 0 @depth-atom))))))))

(defdescribe with-depth-tracking-concurrency-test
  (describe "with-depth-tracking under concurrent contention"
    (it "never exceeds *max-recursion-depth* with N concurrent acquirers"
      ;; Simulates sub-rlm-query-batch firing parallel sub-RLM calls.
      ;; Each worker tries to increment depth. Without atomic CAS,
      ;; multiple workers could pass the check simultaneously.
      (let [max-depth 3
            depth-atom (atom 0)
            peak-depth (atom 0)
            rejections (atom 0)
            n-workers 50
            barrier (java.util.concurrent.CountDownLatch. 1)]
        (binding [schema/*max-recursion-depth* max-depth]
          (let [futures (doall
                          (for [_ (range n-workers)]
                            (future
                              (.await barrier)
                              (let [result (run-with-depth-tracking depth-atom
                                             (fn []
                                               (swap! peak-depth max @depth-atom)
                                               (Thread/sleep 2)
                                               :ok))]
                                (when (:error result)
                                  (swap! rejections inc))))))]
            (.countDown barrier)
            (doseq [f futures] @f)
            ;; Peak depth must NEVER exceed the limit.
            (expect (<= @peak-depth max-depth))
            ;; Depth returns to zero after all workers complete.
            (expect (= 0 @depth-atom))
            ;; Some workers should have been rejected (can't all fit within max-depth).
            (expect (pos? @rejections))))))))
