#!/usr/bin/env -S clojure -M
;; Usage:
;;   clojure -M scripts/merge_index.clj <out-dir> <in-dir-1> <in-dir-2> [<in-dir-N>...]
;;
;; Merges two or more .pageindex directories into one. Handy for stitching
;; batched runs back together (e.g. pages 1-10 + pages 11-20).
;;
;;   - :document/pages are unioned by :page/index (later dirs override earlier
;;     on conflict — so pass the "authoritative" version last).
;;   - :document/toc entries are concatenated and de-duplicated by
;;     :document.toc/id.
;;   - Scalar metadata (:document/name, :title, :abstract, :author,
;;     :created-at, :updated-at, :extension) is taken from the FIRST input
;;     that has a non-nil value.
;;   - images/ from every input are copied into the output images/ dir.
;;
;; Example:
;;   clojure -M scripts/merge_index.clj \
;;     schema-therapy.pageindex \
;;     schema-therapy.pageindex.p1-10 \
;;     schema-therapy.pageindex.p11-20

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.walk :as walk])

(defn- sanitize-doc
  "Strip keys whose value is nil from every map in the document, and drop
   stray TocEntry maps that slipped into :page/nodes so the result matches
   the stricter load-index spec."
  [doc]
  (let [strip-nils (fn [form]
                     (if (map? form)
                       (into (empty form) (remove (fn [[_ v]] (nil? v))) form)
                       form))
        content-node? (fn [n] (contains? n :page.node/type))]
    (-> doc
      (update :document/pages
        (fn [pages]
          (mapv (fn [p] (update p :page/nodes (fn [ns] (filterv content-node? ns))))
            pages)))
      (->> (walk/postwalk strip-nils)))))

(def args *command-line-args*)

(when (< (count args) 3)
  (binding [*out* *err*]
    (println "Usage: clojure -M scripts/merge_index.clj <out-dir> <in-dir-1> <in-dir-2> [<in-dir-N>...]"))
  (System/exit 1))

(def out-dir (first args))
(def in-dirs (rest args))

(defn- read-doc [dir]
  ;; Tolerate stray tagged literals (e.g. #object[java.time.Instant ...]) by
  ;; turning unknown tags into their value. Sanitize straight away so the
  ;; merged result is spec-valid for load-index.
  (sanitize-doc
    (edn/read-string
      {:default (fn [_tag value] value)}
      (slurp (io/file dir "document.edn")))))

(defn- merge-pages [docs]
  (->> docs
    (mapcat :document/pages)
    (reduce (fn [acc p] (assoc acc (:page/index p) p)) {})
    vals
    (sort-by :page/index)
    vec))

(defn- merge-toc [docs]
  (->> docs
    (mapcat :document/toc)
    (reduce (fn [acc t]
              (let [k (or (:document.toc/id t) t)]
                (if (contains? acc k) acc (assoc acc k t))))
      {})
    vals
    vec))

(defn- first-non-nil [docs k]
  (some #(get % k) docs))

(defn- copy-images! [in-dirs out-images]
  (.mkdirs out-images)
  (doseq [d in-dirs
          :let [src (io/file d "images")]
          :when (.isDirectory src)
          f (.listFiles src)
          :when (.isFile f)]
    (io/copy f (io/file out-images (.getName f)))))

(let [docs  (mapv read-doc in-dirs)
      pages (merge-pages docs)
      toc   (merge-toc docs)
      merged {:document/name       (first-non-nil docs :document/name)
              :document/title      (first-non-nil docs :document/title)
              :document/abstract   (first-non-nil docs :document/abstract)
              :document/extension  (first-non-nil docs :document/extension)
              :document/author     (first-non-nil docs :document/author)
              :document/created-at (first-non-nil docs :document/created-at)
              :document/updated-at (first-non-nil docs :document/updated-at)
              :document/pages      pages
              :document/toc        toc}
      out-file (io/file out-dir "document.edn")]
  (.mkdirs (io/file out-dir))
  (spit out-file (pr-str merged))
  (copy-images! in-dirs (io/file out-dir "images"))

  (println "=== Merged ===")
  (println "Output:     " (.getAbsolutePath (io/file out-dir)))
  (println "Inputs:     " (count in-dirs))
  (println "Pages:      " (count pages) "— indices:" (mapv :page/index pages))
  (println "TOC entries:" (count toc))
  (println "Total nodes:" (reduce + (map #(count (:page/nodes %)) pages))))
