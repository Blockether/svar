#!/usr/bin/env -S clojure -M
;; Usage:
;;   clojure -M scripts/index_pdf.clj [options]
;;
;; Options:
;;   --pdf PATH        PDF path (default: schema-therapy.pdf)
;;   --model MODEL     Vision model (default: glm-4.6v)
;;   --skip N          Skip the first N pages
;;   --limit N         Process at most N pages (starting after --skip)
;;   --pages SPEC      Explicit pages spec as EDN, e.g. "[[1 10]]" or "[1 5 [7 9]]"
;;                     (overrides --skip/--limit)
;;   --parallel N      Max concurrent pages (default: 3)
;;
;; Examples:
;;   clojure -M scripts/index_pdf.clj --limit 10
;;   clojure -M scripts/index_pdf.clj --skip 5 --limit 10
;;   clojure -M scripts/index_pdf.clj --pages "[[1 10]]"
;;
;; Indexes a PDF via svar's PageIndex (vision LLM) and writes:
;;   <name>.pageindex/
;;     document.edn    — structured pages, nodes, TOC
;;     images/         — extracted page PNGs
;;     manifest.edn    — per-page progress (for crash-recovery)

(require '[com.blockether.svar.core :as svar]
         '[com.blockether.svar.internal.rlm :as rlm]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(defn parse-args [args]
  (loop [args args acc {}]
    (if (empty? args)
      acc
      (let [[k v & rst] args]
        (recur rst (assoc acc k v))))))

(def cli (parse-args *command-line-args*))

(def pdf-path   (get cli "--pdf" "schema-therapy.pdf"))
(def model      (get cli "--model" "glm-4.6v"))
(def parallel   (Long/parseLong (get cli "--parallel" "3")))
(def skip       (some-> (get cli "--skip") Long/parseLong))
(def limit      (some-> (get cli "--limit") Long/parseLong))
(def pages-spec (some-> (get cli "--pages") edn/read-string))

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

;; Single router with two models — vision (glm-4.6v) + text (gpt-5-mini).
;; Vision extraction uses root (first model); abstract + title inference go
;; via :text-model which rlm propagates as :routing {:model text-model}.
(def text-model "gpt-5-mini")

(def router
  (svar/make-router
    [{:id       :blockether
      :api-key  api-key
      :base-url base-url
      :models   [{:name model}
                 {:name text-model}]}]))

(println "=== Indexing" pdf-path "===")
(println "Vision model:" model)
(println "Parallel:    " parallel)
(when pages (println "Pages:       " pages))
(println)

;; Call build-index directly (bypass per-page tracking so vision/abstract/title
;; run ONCE for the full selected page range instead of N× per page).
(def output-path
  (let [f (io/file pdf-path)
        parent (.getParentFile (.getAbsoluteFile f))
        base (clojure.string/replace (.getName f) #"\.[^.]+$" "")]
    (.getAbsolutePath (io/file parent (str base ".pageindex")))))

(def images-dir (io/file output-path "images"))
(.mkdirs images-dir)

(let [start   (System/currentTimeMillis)
      opts    (cond-> {:model model
                       :text-model text-model
                       :parallel parallel
                       :output-dir (.getAbsolutePath images-dir)}
                pages (assoc :pages pages))
      doc     (rlm/build-index router pdf-path opts)
      elapsed (/ (- (System/currentTimeMillis) start) 1000.0)
      edn-path (io/file output-path "document.edn")]

  (spit edn-path (pr-str doc))

  (println "=== Done ===")
  (println "Output:      " output-path)
  (println "Pages:       " (count (:document/pages doc)))
  (println "TOC entries: " (count (:document/toc doc)))
  (println "Total nodes: " (reduce + (map #(count (:page/nodes %)) (:document/pages doc))))
  (println (format "Time: %.1fs" elapsed))
  (println)
  (println "Load with:")
  (println (str "  (svar/load-index \"" output-path "\")")))
