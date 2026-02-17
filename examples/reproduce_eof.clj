;; Minimal reproduction of GLM-4.6v EOF issue through Blockether proxy.
;; Demonstrates consistent connection drops for large vision responses.

(ns reproduce-eof
  (:require [com.blockether.svar.internal.rlm.internal.pageindex.vision :as vision]
            [com.blockether.svar.internal.config :as config]))

(defn -main
  "Extracts page 13 from chapter.pdf using glm-4.6v.
   Runs 5 times to demonstrate the EOF pattern."
  [& args]
  (let [pdf-path (or (first args) "resources-test/chapter.pdf")
        model "glm-4.6v"
        cfg (config/make-config)
        max-attempts 5]

    (println "=== GLM-4.6v EOF Reproduction ===")
    (println "PDF:" pdf-path)
    (println "Model:" model)
    (println "Max attempts:" max-attempts)
    (println)

    (loop [attempt 1]
      (println (str "\nAttempt " attempt "/" max-attempts "..."))
      (try
        (let [result (vision/extract-text-from-pdf pdf-path
                                                   {:model model
                                                    :config cfg
                                                    :timeout-ms 480000
                                                    :parallel 1})]
          (println "Result:" (pr-str result)))
        (catch Exception e
          (println (str "ERROR: " (ex-message e)))))

      (if (>= attempt max-attempts)
        (do
          (println "\n=== Reproduction Complete ===")
          (System/exit 0))
        (recur (inc attempt))))))