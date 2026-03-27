#!/usr/bin/env -S clojure -M
;; Usage: clojure -M scripts/index_pdf.clj
;;
;; Indexes schema-therapy.pdf using svar's PageIndex and produces:
;;   schema-therapy.pageindex/
;;     document.edn    — full structured document (pages, nodes, TOC)
;;     images/         — extracted page images as PNGs

(require '[com.blockether.svar.core :as svar])

(def pdf-path "schema-therapy.pdf")

(println "=== Indexing" pdf-path "===")
(println "Using glm-4.6v for vision extraction.")
(println "Parallel: 3 concurrent pages (default)")
(println)

(let [start (System/currentTimeMillis)
      result (svar/index! pdf-path {:vision-model "glm-4.6v"})
      doc (:document result)
      elapsed (/ (- (System/currentTimeMillis) start) 1000.0)]

  (println "=== Done ===")
  (println "Output:" (:output-path result))
  (println "Pages:" (count (:document/pages doc)))
  (println "TOC entries:" (count (:document/toc doc)))
  (println "Total nodes:" (reduce + (map #(count (:page/nodes %)) (:document/pages doc))))
  (println (format "Time: %.1fs" elapsed))
  (println)
  (println "The document.edn in the output directory can be loaded with:")
  (println "  (svar/load-index \"" (:output-path result) "\")"))
