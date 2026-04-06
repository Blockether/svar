#!/usr/bin/env -S clojure -M
;; Usage:
;;   clojure -M scripts/index_pdf.clj [options]
;;
;; Options:
;;   --pdf PATH        PDF path (default: schema-therapy.pdf)
;;   --strategy STR    Extraction strategy: "vision" (default) or "ocr"
;;                       vision — single-pass: image → vision LLM with spec → structured nodes
;;                       ocr    — two-pass:   image → OCR model → raw text → text LLM with spec → structured nodes
;;                                (dramatically faster for local OCR models like glm-ocr)
;;   --model MODEL     Vision model for strategy=vision (default: glm-4.6v)
;;   --ocr-model MODEL OCR model for strategy=ocr (e.g. "glm-ocr"). Required when --strategy ocr.
;;   --text-model MODEL Text LLM for structuring (strategy=ocr) and abstract/title (both).
;;                       Default: gpt-5-mini.
;;   --ocr-base-url URL Base URL for OCR provider (default: http://localhost:1234/v1, LM Studio)
;;   --skip N          Skip the first N pages
;;   --limit N         Process at most N pages (starting after --skip)
;;   --pages SPEC      Explicit pages spec as EDN, e.g. "[[1 10]]" or "[1 5 [7 9]]"
;;                     (overrides --skip/--limit)
;;   --parallel N      Max concurrent pages (default: 3)
;;   --force           Ignore existing manifest and reindex every selected page
;;
;; Examples:
;;   # Vision (single-pass, remote vision LLM):
;;   clojure -M scripts/index_pdf.clj --limit 10
;;
;;   # OCR (two-pass, local OCR + remote text LLM — much faster):
;;   clojure -M scripts/index_pdf.clj --strategy ocr --ocr-model glm-ocr --limit 10
;;
;; Indexes a PDF via svar's PageIndex and writes:
;;   <name>.pageindex/
;;     document.edn    — structured pages, nodes, TOC
;;     images/         — extracted page PNGs
;;     manifest.edn    — per-page progress (for crash-recovery)

(require '[com.blockether.svar.core :as svar]
         '[clojure.edn :as edn])

(def ^:private boolean-flags #{"--force"})

(defn parse-args [args]
  (loop [args args acc {}]
    (cond
      (empty? args) acc
      (contains? boolean-flags (first args)) (recur (rest args) (assoc acc (first args) true))
      :else (let [[k v & rst] args] (recur rst (assoc acc k v))))))

(def cli (parse-args *command-line-args*))

(def pdf-path     (get cli "--pdf" "schema-therapy.pdf"))
(def strategy     (keyword (get cli "--strategy" "vision")))
(def model        (get cli "--model" "glm-4.6v"))
(def ocr-model    (get cli "--ocr-model"))
(def text-model   (get cli "--text-model" "gpt-5-mini"))
(def ocr-base-url (get cli "--ocr-base-url" "http://localhost:1234/v1"))
(def parallel     (Long/parseLong (get cli "--parallel" "3")))
(def skip         (some-> (get cli "--skip") Long/parseLong))
(def limit        (some-> (get cli "--limit") Long/parseLong))
(def pages-spec   (some-> (get cli "--pages") edn/read-string))
(def force?       (boolean (get cli "--force")))

(when (and (= :ocr strategy) (not ocr-model))
  (binding [*out* *err*]
    (println "ERROR: --ocr-model required when --strategy ocr"))
  (System/exit 1))

(def pages
  (cond
    pages-spec pages-spec
    (or skip limit)
    (let [start (inc (or skip 0))
          end   (if limit (+ start (dec limit)) start)]
      [[start end]])
    :else nil))

;; Router — Blockether One (always available in this dev env), routes to any model.
(def api-key  (System/getenv "BLOCKETHER_OPENAI_API_KEY"))
(def base-url (System/getenv "BLOCKETHER_OPENAI_BASE_URL"))

(when-not (and api-key base-url)
  (binding [*out* *err*]
    (println "ERROR: BLOCKETHER_OPENAI_API_KEY and BLOCKETHER_OPENAI_BASE_URL required."))
  (System/exit 1))

;; Build router with providers based on strategy.
;; Vision strategy: Blockether with vision model + text model.
;; OCR strategy: LM Studio with OCR model + Blockether with text model.
(def router
  (svar/make-router
    (cond-> [{:id       :blockether
              :api-key  api-key
              :base-url base-url
              :models   (cond-> [{:name text-model}]
                          (= :vision strategy) (conj {:name model}))}]
      (= :ocr strategy)
      (conj {:id       :lmstudio
             :api-key  "lm-studio"
             :base-url ocr-base-url
             :models   [{:name ocr-model}]}))))

(println "=== Indexing" pdf-path "===")
(println "Strategy:    " (name strategy))
(case strategy
  :vision (println "Vision model:" model)
  :ocr    (do (println "OCR model:   " ocr-model)
              (println "OCR endpoint:" ocr-base-url)))
(println "Text model:  " text-model)
(println "Parallel:    " parallel)
(when pages (println "Pages:       " pages))
(println)

;; Delegate to svar/index! which handles:
;;   - per-page extraction (parallel) — vision or OCR strategy
;;   - manifest.edn with per-page {:status :done|:error|:pending} tracking
;;     → crash recovery: re-running skips :done pages and retries :error pages
;;   - single-pass TOC linking + abstract + title inference (across ALL pages)
;;   - document.edn + images/page-NNN.png + images/<uuid>.png output
(let [start  (System/currentTimeMillis)
      opts   (cond-> {:router router
                      :extraction-strategy strategy
                      :text-model text-model
                      :parallel parallel}
               (= :vision strategy) (assoc :vision-model model)
               (= :ocr strategy)    (assoc :ocr-model ocr-model)
               pages                (assoc :pages pages)
               force?               (assoc :force? true))
      result (svar/index! pdf-path opts)
      doc    (:document result)
      elapsed (/ (- (System/currentTimeMillis) start) 1000.0)]

  (println "=== Done ===")
  (println "Output:      " (:output-path result))
  (println "Pages:       " (count (:document/pages doc)))
  (println "TOC entries: " (count (:document/toc doc)))
  (println "Total nodes: " (reduce + (map #(count (:page/nodes %)) (:document/pages doc))))
  (println (format "Time: %.1fs" elapsed))
  (println)
  (println "Load with:")
  (println (str "  (svar/load-index \"" (:output-path result) "\")"))
  (when (pos? (:errors-count result 0))
    (println)
    (println "!! " (:errors-count result) " page(s) errored. Re-run the same command to retry just those pages.")
    (println "   Inspect manifest.edn in the output dir to see which pages failed and why.")))
