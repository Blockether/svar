(ns com.blockether.svar.bench.python-tasks
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.nio.file Files)
   (java.util.concurrent TimeUnit)))

(defn ensure-dir!
  [path]
  (.mkdirs (io/file path)))

(defn download-if-missing!
  [url dest-path]
  (let [f (io/file dest-path)]
    (if (.exists f)
      dest-path
      (do
        (ensure-dir! (.getParent f))
        (with-open [in (io/input-stream url)
                    out (io/output-stream f)]
          (io/copy in out))
        dest-path))))

(defn strip-fence
  "Extracts Python code from LLM output with multi-block aware selection.
   Pi/RLM outputs often contain multiple fenced blocks (test output + final
   code). Strategy:
     1. Prefer the LAST ```python/py tagged block (final answer).
     2. Fall back to the LAST untagged ``` block.
     3. Fall back to raw input."
  [raw]
  (let [s (str/trim (str raw))
        tagged (re-seq #"(?s)```(?:python|py)\s*(.*?)\s*```" s)
        untagged (re-seq #"(?s)```\s*(.*?)\s*```" s)]
    (cond
      (seq tagged)   (str/trim (second (last tagged)))
      (seq untagged) (str/trim (second (last untagged)))
      :else          s)))

(defn run-python-script!
  [script timeout-ms]
  (let [tmp (Files/createTempFile "svar-bench-" ".py" (make-array java.nio.file.attribute.FileAttribute 0))
        path (.toFile tmp)]
    (spit path script)
    (try
      (let [pb (ProcessBuilder. (into-array String ["python3" "-I" (.getAbsolutePath path)]))
            _ (.redirectErrorStream pb true)
            proc (.start pb)
            output-future (future (slurp (.getInputStream proc)))
            finished? (.waitFor proc timeout-ms TimeUnit/MILLISECONDS)
            output (if finished? (deref output-future 5000 "") "")]
        (if finished?
          {:ok? (zero? (.exitValue proc))
           :exit-code (.exitValue proc)
           :output output
           :timeout? false}
          (do
            (.destroyForcibly proc)
            (future-cancel output-future)
            {:ok? false
             :exit-code nil
             :output "Timed out"
             :timeout? true})))
      (finally
        (.delete path)))))
