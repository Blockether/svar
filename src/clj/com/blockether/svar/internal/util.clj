(ns com.blockether.svar.internal.util
  "Shared internal utilities.")

(defmacro with-elapsed
  "Executes body, returns [result elapsed-ms].

   Example:
   (let [[result duration-ms] (with-elapsed (do-stuff))]
     (println \"Took\" duration-ms \"ms\")
     result)"
  [& body]
  `(let [start# (System/nanoTime)
         result# (do ~@body)]
     [result# (/ (- (System/nanoTime) start#) 1e6)]))

(defn elapsed-since
  "Returns elapsed milliseconds since the given nanoTime start."
  [nano-start]
  (/ (- (System/nanoTime) nano-start) 1e6))
