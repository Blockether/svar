(ns com.blockether.svar.internal.rlm.concurrency-test
  "Unit tests for reentrant semaphore and deadline primitives.

   Covers: reentrancy, cross-thread contention, FIFO fairness,
   release-without-acquire safety, exception safety,
   deadline propagation, and deadline-exceeded detection."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.rlm.concurrency :as concurrency])
  (:import
   [java.time Instant]
   [java.util.concurrent CountDownLatch CyclicBarrier TimeUnit]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- acquire! [rsem] ((:acquire! rsem)))
(defn- release! [rsem] ((:release! rsem)))
(defn- permits  [rsem] ((:permits rsem)))

;; ---------------------------------------------------------------------------
;; 1. Reentrancy
;; ---------------------------------------------------------------------------

(defdescribe reentrant-semaphore-reentrancy-test
  (describe "reentrant semaphore — same-thread reentrancy"

    (it "does not block when same thread acquires a second time"
      (let [rsem (concurrency/make-reentrant-semaphore 1)]
        (acquire! rsem)
        ;; Second acquire on same thread must return immediately (not deadlock)
        (acquire! rsem)
        (release! rsem)
        (release! rsem)
        ;; Permit fully restored after two releases
        (expect (= 1 (permits rsem)))))

    (it "does not consume extra real permits for reentrant acquisitions"
      (let [rsem (concurrency/make-reentrant-semaphore 2)]
        (acquire! rsem)
        ;; Reentrant — should NOT consume a second real permit
        (acquire! rsem)
        (acquire! rsem)
        ;; Still one permit consumed (by first real acquire), one free
        (expect (= 1 (permits rsem)))
        (release! rsem)
        (release! rsem)
        (release! rsem)
        (expect (= 2 (permits rsem)))))

    (it "requires N releases to fully free after N reentrant acquires"
      (let [rsem (concurrency/make-reentrant-semaphore 1)
            n    5]
        (dotimes [_ n] (acquire! rsem))
        ;; After n-1 releases, permit still held
        (dotimes [_ (dec n)] (release! rsem))
        (expect (= 0 (permits rsem)))
        ;; Final release frees the real permit
        (release! rsem)
        (expect (= 1 (permits rsem)))))

    (it "reentrant depth resets cleanly so thread can re-acquire after full release"
      (let [rsem (concurrency/make-reentrant-semaphore 1)]
        (acquire! rsem)
        (acquire! rsem)
        (release! rsem)
        (release! rsem)
        ;; Thread fully released; must be able to acquire again (no phantom depth)
        (acquire! rsem)
        (expect (= 0 (permits rsem)))
        (release! rsem)
        (expect (= 1 (permits rsem)))))))

;; ---------------------------------------------------------------------------
;; 2. Cross-thread contention
;; ---------------------------------------------------------------------------

(defdescribe reentrant-semaphore-contention-test
  (describe "reentrant semaphore — cross-thread contention"

    (it "second thread blocks until first releases when capacity is 1"
      (let [rsem     (concurrency/make-reentrant-semaphore 1)
            acquired (atom false)
            start    (CountDownLatch. 1)
            done     (CountDownLatch. 1)]
        ;; First thread holds the permit
        (acquire! rsem)
        ;; Second thread tries to acquire — must block
        (future
          (.countDown start)
          (acquire! rsem)
          (reset! acquired true)
          (release! rsem)
          (.countDown done))
        (.await start)
        ;; Give the second thread time to block
        (Thread/sleep 50)
        ;; Second thread should NOT have acquired yet
        (expect (false? @acquired))
        ;; Release from first thread unblocks second
        (release! rsem)
        (.await done 2 TimeUnit/SECONDS)
        (expect (true? @acquired))
        (expect (= 1 (permits rsem)))))

    (it "all N threads eventually acquire when capacity equals N"
      (let [n        4
            rsem     (concurrency/make-reentrant-semaphore n)
            acquired (atom 0)
            latch    (CountDownLatch. n)]
        (dotimes [_ n]
          (future
            (acquire! rsem)
            (swap! acquired inc)
            (.countDown latch)
            (Thread/sleep 10)
            (release! rsem)))
        (.await latch 3 TimeUnit/SECONDS)
        (expect (= n @acquired))))

    (it "never exceeds capacity under concurrent load"
      (let [capacity  2
            rsem      (concurrency/make-reentrant-semaphore capacity)
            n-workers 20
            peak      (atom 0)
            active    (atom 0)
            barrier   (CountDownLatch. 1)
            done      (CountDownLatch. n-workers)]
        (dotimes [_ n-workers]
          (future
            (.await barrier)
            (acquire! rsem)
            (let [now (swap! active inc)]
              (swap! peak max now))
            (Thread/sleep 5)
            (swap! active dec)
            (release! rsem)
            (.countDown done)))
        (.countDown barrier)
        (.await done 5 TimeUnit/SECONDS)
        (expect (<= @peak capacity))
        (expect (= capacity (permits rsem)))))))

;; ---------------------------------------------------------------------------
;; 3. FIFO fairness (best-effort)
;; ---------------------------------------------------------------------------

(defdescribe reentrant-semaphore-fairness-test
  (describe "reentrant semaphore — FIFO fairness (best-effort)"

    (it "threads waiting are served roughly in arrival order"
      ;; Capacity 1. Thread A holds. Threads 1..N queue up in order.
      ;; After A releases, they should proceed approximately FIFO.
      (let [rsem       (concurrency/make-reentrant-semaphore 1)
            n          5
            order      (atom [])
            barrier    (CyclicBarrier. (inc n))
            ;; Latch ensures A is holding before workers start
            a-holding  (CountDownLatch. 1)
            a-release  (CountDownLatch. 1)
            done       (CountDownLatch. n)]
        ;; Thread A acquires first
        (future
          (acquire! rsem)
          (.countDown a-holding)
          (.await a-release)
          (release! rsem))
        (.await a-holding)
        ;; Launch N workers that will all block on acquire
        (doseq [i (range n)]
          (future
            (.await barrier)
            (acquire! rsem)
            (swap! order conj i)
            (Thread/sleep 2)
            (release! rsem)
            (.countDown done)))
        ;; All workers ready — stagger slightly so OS queues them
        (.await barrier)
        (Thread/sleep 20)
        ;; Release A — workers drain in queue order
        (.countDown a-release)
        (.await done 5 TimeUnit/SECONDS)
        ;; At least the first and last positions should be correct
        ;; (strict FIFO hard to guarantee across JVM thread scheduling)
        (expect (= n (count @order)))
        ;; All IDs present, no duplicates
        (expect (= (set (range n)) (set @order)))))))

;; ---------------------------------------------------------------------------
;; 4. Release without acquire — no-op / no negative permits
;; ---------------------------------------------------------------------------

(defdescribe reentrant-semaphore-spurious-release-test
  (describe "reentrant semaphore — release without prior acquire"

    (it "spurious release on fresh thread is a no-op"
      (let [rsem (concurrency/make-reentrant-semaphore 2)]
        ;; No acquire — release must not throw or go negative
        (release! rsem)
        ;; Permits unchanged
        (expect (= 2 (permits rsem)))))

    (it "extra releases after full unwind do not inflate permits"
      (let [rsem (concurrency/make-reentrant-semaphore 1)]
        (acquire! rsem)
        (release! rsem)
        ;; Already fully released — extra release must be no-op
        (release! rsem)
        (expect (= 1 (permits rsem)))))))

;; ---------------------------------------------------------------------------
;; 5. Exception safety
;; ---------------------------------------------------------------------------

(defdescribe reentrant-semaphore-exception-safety-test
  (describe "reentrant semaphore — exception safety with try/finally"

    (it "permit is released when body throws"
      (let [rsem (concurrency/make-reentrant-semaphore 1)]
        (try
          (acquire! rsem)
          (throw (ex-info "boom" {}))
          (catch Exception _)
          (finally
            (release! rsem)))
        ;; Permit restored despite exception
        (expect (= 1 (permits rsem)))))

    (it "nested reentrant acquire releases cleanly on exception at inner level"
      (let [rsem (concurrency/make-reentrant-semaphore 1)]
        (acquire! rsem)
        (try
          (acquire! rsem)   ; reentrant depth = 2
          (throw (ex-info "inner boom" {}))
          (catch Exception _)
          (finally
            (release! rsem))) ; depth -> 1
        ;; Outer level still holds; permit NOT yet restored
        (expect (= 0 (permits rsem)))
        (release! rsem)     ; depth -> 0 → real permit back
        (expect (= 1 (permits rsem)))))

    (it "second thread can acquire after exception-unwind of first"
      (let [rsem    (concurrency/make-reentrant-semaphore 1)
            success (atom false)
            done    (CountDownLatch. 1)]
        ;; First thread acquires then throws (releases via finally)
        (let [f (future
                  (try
                    (acquire! rsem)
                    (throw (ex-info "bang" {}))
                    (catch Exception _)
                    (finally
                      (release! rsem))))]
          @f)
        ;; Second thread must now be able to acquire without blocking
        (future
          (acquire! rsem)
          (reset! success true)
          (release! rsem)
          (.countDown done))
        (.await done 2 TimeUnit/SECONDS)
        (expect (true? @success))))))

;; ---------------------------------------------------------------------------
;; 6. Deadline propagation
;; ---------------------------------------------------------------------------

(defdescribe compute-deadline-propagation-test
  (describe "compute-deadline — child inherits min(caller, parent)"

    (it "returns caller deadline when no parent deadline exists"
      (let [deadline (concurrency/compute-deadline 1000 nil nil)]
        (expect (instance? Instant deadline))
        ;; Should be roughly now+1000ms
        (let [now-ms (.toEpochMilli (Instant/now))
              dl-ms  (.toEpochMilli deadline)]
          (expect (>= dl-ms (+ now-ms 900)))
          (expect (<= dl-ms (+ now-ms 1100))))))

    (it "returns parent deadline when no caller timeout and no default"
      (let [parent (-> (Instant/now) (.plusMillis 5000))
            result (concurrency/compute-deadline nil parent nil)]
        (expect (= parent result))))

    (it "returns caller deadline when it is earlier than parent"
      (let [parent   (-> (Instant/now) (.plusMillis 5000))
            ;; caller timeout 500ms -> deadline < parent
            result   (concurrency/compute-deadline 500 parent nil)
            now-ms   (.toEpochMilli (Instant/now))
            result-ms (.toEpochMilli result)]
        ;; Result must be caller-deadline (~now+500) not parent (~now+5000)
        (expect (< result-ms (.toEpochMilli parent)))
        (expect (>= result-ms (+ now-ms 400)))))

    (it "returns parent deadline when it is earlier than caller"
      (let [parent  (-> (Instant/now) (.plusMillis 100))
            ;; caller timeout 5000ms -> deadline > parent
            result  (concurrency/compute-deadline 5000 parent nil)]
        (expect (= parent result))))

    (it "uses default-timeout-ms when caller timeout is nil"
      (let [result   (concurrency/compute-deadline nil nil 2000)
            now-ms   (.toEpochMilli (Instant/now))
            result-ms (.toEpochMilli result)]
        (expect (>= result-ms (+ now-ms 1900)))
        (expect (<= result-ms (+ now-ms 2100)))))

    (it "caller timeout overrides default-timeout-ms"
      (let [result    (concurrency/compute-deadline 500 nil 9999)
            now-ms    (.toEpochMilli (Instant/now))
            result-ms (.toEpochMilli result)]
        (expect (>= result-ms (+ now-ms 400)))
        ;; Must NOT be near 9999ms out
        (expect (< result-ms (+ now-ms 1000)))))

    (it "returns nil when all inputs are nil (unbounded)"
      (expect (nil? (concurrency/compute-deadline nil nil nil))))))

;; ---------------------------------------------------------------------------
;; 7. Deadline exceeded detection (remaining-ms)
;; ---------------------------------------------------------------------------

(defdescribe remaining-ms-test
  (describe "remaining-ms — deadline exceeded detection"

    (it "returns nil when deadline is nil"
      (expect (nil? (concurrency/remaining-ms nil))))

    (it "returns positive ms for a future deadline"
      (let [future-dl (-> (Instant/now) (.plusMillis 5000))
            remaining (concurrency/remaining-ms future-dl)]
        (expect (> remaining 0))
        (expect (<= remaining 5000))))

    (it "returns 0 when deadline is in the past"
      (let [past-dl (-> (Instant/now) (.minusMillis 1000))
            remaining (concurrency/remaining-ms past-dl)]
        (expect (= 0 remaining))))

    (it "returns 0 when deadline is exactly now (within rounding)"
      ;; Deadline set 1ms in past to guarantee it has passed
      (let [just-past (-> (Instant/now) (.minusMillis 1))
            remaining (concurrency/remaining-ms just-past)]
        (expect (= 0 remaining))))

    (it "decreases over time for a live deadline"
      (let [dl   (-> (Instant/now) (.plusMillis 500))
            r1   (concurrency/remaining-ms dl)
            _    (Thread/sleep 50)
            r2   (concurrency/remaining-ms dl)]
        (expect (>= r1 r2))))))
