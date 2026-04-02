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
  [raw]
  (let [s (str/trim (str raw))
        m (re-find #"(?s)^```(?:python|py)?\s*(.*?)\s*```$" s)]
    (if m
      (str/trim (second m))
      s)))

(defn run-python-script!
  [script timeout-ms]
  (let [tmp (Files/createTempFile "svar-bench-" ".py" (make-array java.nio.file.attribute.FileAttribute 0))
        path (.toFile tmp)]
    (spit path script)
    (try
      (let [pb (ProcessBuilder. (into-array String ["python3" "-I" (.getAbsolutePath path)]))
            _ (.redirectErrorStream pb true)
            proc (.start pb)
            finished? (.waitFor proc timeout-ms TimeUnit/MILLISECONDS)
            output (slurp (.getInputStream proc))]
        (if finished?
          {:ok? (zero? (.exitValue proc))
           :exit-code (.exitValue proc)
           :output output
           :timeout? false}
          (do
            (.destroyForcibly proc)
            {:ok? false
             :exit-code nil
             :output "Timed out"
             :timeout? true})))
      (finally
        (.delete path)))))
