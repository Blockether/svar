(ns com.blockether.svar.internal.rlm.concurrency
  "Concurrency primitives for sub-rlm-query-batch and nested calls.

   Provides a reentrant semaphore (thread-id keyed) that prevents deadlock
   when a batched sub-rlm-query item itself calls sub-rlm-query (nesting).
   Same thread re-enters without blocking; different threads contend fairly."
  (:import
   [java.util.concurrent ConcurrentHashMap Semaphore]))

(defn make-reentrant-semaphore
  "Creates a reentrant semaphore with `permits` slots.
   Thread-id keyed: same thread can re-acquire without blocking.
   Different threads contend for permits fairly (FIFO).

   Returns a map with :acquire! and :release! fns."
  [permits]
  (let [sem (Semaphore. (int permits) true)
        ;; Track per-thread reentrant depth
        thread-depths (ConcurrentHashMap.)]
    {:acquire!
     (fn acquire! []
       (let [tid (.getId (Thread/currentThread))
             depth (.getOrDefault thread-depths tid (int 0))]
         (if (pos? depth)
           ;; Reentrant: already holding a permit on this thread
           (.put thread-depths tid (int (inc depth)))
           ;; First acquisition: block for a real permit
           (do (.acquire sem)
               (.put thread-depths tid (int 1))))))

     :release!
     (fn release! []
       (let [tid (.getId (Thread/currentThread))
             depth (.getOrDefault thread-depths tid (int 0))]
         (when (pos? depth)
           (if (= depth 1)
             ;; Last level: release the real permit
             (do (.remove thread-depths tid)
                 (.release sem))
             ;; Unwinding reentrant depth
             (.put thread-depths tid (int (dec depth)))))))

     :permits (fn [] (.availablePermits sem))
     :queued  (fn [] (.getQueueLength sem))}))

(defn compute-deadline
  "Computes the absolute deadline Instant for a sub-rlm-query call.
   Respects parent deadline via *sub-rlm-deadline* — child never exceeds parent.

   `timeout-ms` — caller's requested timeout (or nil for env default).
   `parent-deadline` — current *sub-rlm-deadline* value (Instant or nil).
   `default-timeout-ms` — from concurrency settings.

   Returns java.time.Instant or nil (unbounded)."
  [timeout-ms parent-deadline default-timeout-ms]
  (let [effective-ms (or timeout-ms default-timeout-ms)
        now (java.time.Instant/now)
        caller-deadline (when effective-ms
                          (.plusMillis now (long effective-ms)))]
    (cond
      (and caller-deadline parent-deadline)
      (if (.isBefore caller-deadline parent-deadline)
        caller-deadline
        parent-deadline)

      caller-deadline caller-deadline
      parent-deadline parent-deadline
      :else nil)))

(defn remaining-ms
  "Milliseconds remaining until deadline, or nil if no deadline.
   Returns 0 when deadline has passed."
  [deadline]
  (when deadline
    (max 0 (- (.toEpochMilli ^java.time.Instant deadline)
             (.toEpochMilli (java.time.Instant/now))))))
