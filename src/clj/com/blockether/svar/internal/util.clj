(ns com.blockether.svar.internal.util
  "Shared internal utilities."
  (:refer-clojure :exclude [parse-uuid])
  (:import
   [java.util UUID]))

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

(defn uuid
  "Returns a new random UUID."
  ^UUID []
  (UUID/randomUUID))

(defn parse-uuid
  "Parses a string into a UUID. Returns nil on invalid input."
  ^UUID [^String s]
  (when s
    (try (UUID/fromString s)
         (catch IllegalArgumentException _ nil))))
