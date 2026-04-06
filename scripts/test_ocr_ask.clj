#!/usr/bin/env -S clojure -M
;; Test: 2-pass OCR on first 10 pages of schema-therapy.pdf
;; Usage: clojure -M scripts/test_ocr_ask.clj

(require '[com.blockether.svar.core :as svar]
         '[com.blockether.svar.internal.rlm.pageindex.vision :as vision]
         '[com.blockether.svar.internal.rlm.pageindex.pdf :as pdf])

(def api-key  (System/getenv "BLOCKETHER_OPENAI_API_KEY"))
(def base-url (System/getenv "BLOCKETHER_OPENAI_BASE_URL"))

;; Single router: LM Studio (glm-ocr) + Blockether (gpt-5-mini)
(def router
  (svar/make-router
    [{:id       :lmstudio
      :api-key  "lm-studio"
      :base-url "http://localhost:1234/v1"
      :models   [{:name "glm-ocr"}]}
     {:id       :blockether
      :api-key  api-key
      :base-url base-url
      :models   [{:name "gpt-5-mini"}]}]))

(def pdf-path "schema-therapy.pdf")
(def page-count 10)

(println "=== OCR 2-pass test: first" page-count "pages ===")
(println "Pass 1: glm-ocr (LM Studio, local) → raw markdown")
(println "Pass 2: gpt-5-mini (Blockether) → structured nodes")
(println)

;; Extract first 10 pages using extract-text-from-pdf with OCR mode
(let [page-set (set (range page-count))  ;; 0-indexed: 0-9
      t0 (System/currentTimeMillis)
      pages (vision/extract-text-from-pdf pdf-path
              {:rlm-router router
               :objective  vision/DEFAULT_VISION_OBJECTIVE
               :ocr-model  "glm-ocr"
               :text-model "gpt-5-mini"
               :parallel   2
               :timeout-ms 180000
               :page-set   page-set})
      ms (- (System/currentTimeMillis) t0)]
  (println)
  (println (format "=== Done in %.1fs ===" (/ ms 1000.0)))
  (println "Pages:" (count pages))
  (println "Total nodes:" (reduce + (map #(count (:page/nodes %)) pages)))
  (println)
  (doseq [page (sort-by :page/index pages)]
    (let [nodes (:page/nodes page)]
      (println (format "--- Page %d: %d nodes ---" (inc (:page/index page)) (count nodes)))
      (doseq [node nodes]
        (let [t    (:page.node/type node)
              lvl  (:page.node/level node)
              text (or (:page.node/content node) (:page.node/description node) "")
              pid  (:page.node/parent-id node)]
          (println (format "  [%s%s] id=%s parent=%s | %s"
                     (name t)
                     (if lvl (str "/" lvl) "")
                     (:page.node/id node)
                     (or pid "-")
                     (subs text 0 (min 80 (count text))))))))))
