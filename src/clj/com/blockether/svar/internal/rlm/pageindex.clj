(ns com.blockether.svar.internal.rlm.pageindex
  "PageIndex: pure helpers, serialization, load-index and inspect — extracted from rlm.clj."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.internal.rlm.schema :as schema]
   [com.blockether.svar.internal.util :as util]
   [fast-edn.core :as fast-edn]
   [taoensso.trove :as trove]))

;; =============================================================================
;; Continuation Grouping
;; =============================================================================

(defn- visual-node?
  "Returns true if node is a visual element (image or table)."
  [node]
  (#{:image :table} (:page.node/type node)))

(defn- last-visual-of-type
  "Finds the last visual node of the given type on a page."
  [page node-type]
  (->> (:page/nodes page)
    (filter #(= node-type (:page.node/type %)))
    last))

(defn group-continuations
  "Groups continuation nodes across pages by assigning a shared :page.node/group-id.

   Walks pages in order. When a visual node (image/table) has continuation?=true,
   looks back to the last same-type node on the preceding page and assigns both
   the same group-id UUID. Propagates group-id forward for 3+ page chains.

   Params:
   `pages` - Vector of page maps with :page/nodes (must have UUIDs already).

   Returns:
   Updated pages with :page.node/group-id assigned to grouped nodes."
  [pages]
  (if (< (count pages) 2)
    pages
    (let [group-assignments (atom {})
          _ (doseq [i (range 1 (count pages))]
              (let [prev-page (nth pages (dec (long i)))
                    curr-page (nth pages i)]
                (doseq [node (:page/nodes curr-page)]
                  (when (and (visual-node? node)
                          (:page.node/continuation? node))
                    (let [node-type (:page.node/type node)
                          prev-visual (last-visual-of-type prev-page node-type)]
                      (when prev-visual
                        (let [prev-id (:page.node/id prev-visual)
                              curr-id (:page.node/id node)
                              existing-group (get @group-assignments prev-id)
                              group-id (or existing-group (str (util/uuid)))]
                          (swap! group-assignments assoc prev-id group-id)
                          (swap! group-assignments assoc curr-id group-id))))))))
          assignments @group-assignments]
      (if (empty? assignments)
        pages
        (mapv (fn [page]
                (update page :page/nodes
                  (fn [nodes]
                    (mapv (fn [node]
                            (if-let [gid (get assignments (:page.node/id node))]
                              (assoc node :page.node/group-id gid)
                              node))
                      nodes))))
          pages)))))

;; =============================================================================
;; Page Range Normalization
;; =============================================================================

(defn- validate-page-number
  "Validates a single 1-indexed page number against total-page-count.
   Throws on invalid input."
  [n total-page-count]
  (when-not (integer? n)
    (anomaly/incorrect! (str "Page spec must be an integer, got: " (pr-str n))
      {:type :svar.pageindex/invalid-page-spec
       :value n}))
  (when (< n 1)
    (anomaly/incorrect! (str "Page number must be >= 1, got: " n)
      {:type :svar.pageindex/invalid-page-spec
       :value n}))
  (when (> n total-page-count)
    (anomaly/incorrect! (str "Page " n " exceeds total page count " total-page-count)
      {:type :svar.pageindex/page-out-of-bounds
       :value n
       :total-page-count total-page-count})))

(defn- normalize-range
  "Expands a [from to] 1-indexed range into a set of 0-indexed page indices."
  [[from to] total-page-count]
  (validate-page-number from total-page-count)
  (validate-page-number to total-page-count)
  (when (> from to)
    (anomaly/incorrect! (str "Invalid page range: start " from " > end " to)
      {:type :svar.pageindex/invalid-page-range
       :from from
       :to to}))
  (set (range (dec from) to)))

(defn normalize-page-spec
  "Normalizes a page specification into a set of 0-indexed page indices.

   Accepts:
   - nil             → nil (all pages)
   - integer n       → #{(dec n)} (single 1-indexed page)
   - [from to]       → set of 0-indexed pages in range (both ints, exactly 2 elements)
   - [[1 3] 5 [7 10]] → union of expanded ranges and single pages

   Throws on invalid input (out of bounds, bad types, reversed ranges)."
  [pages-spec total-page-count]
  (cond
    (nil? pages-spec)
    nil

    (integer? pages-spec)
    (do (validate-page-number pages-spec total-page-count)
        #{(dec pages-spec)})

    (vector? pages-spec)
    (if (and (= 2 (count pages-spec))
          (every? integer? pages-spec))
      (normalize-range pages-spec total-page-count)
      (reduce (fn [acc item]
                (cond
                  (vector? item)
                  (into acc (normalize-range item total-page-count))

                  (integer? item)
                  (do (validate-page-number item total-page-count)
                      (conj acc (dec item)))

                  :else
                  (anomaly/incorrect! (str "Invalid element in page spec: " (pr-str item))
                    {:type :svar.pageindex/invalid-page-spec
                     :value item})))
        #{}
        pages-spec))

    :else
    (anomaly/incorrect! (str "Invalid page spec type: " (pr-str pages-spec))
      {:type :svar.pageindex/invalid-page-spec
       :value pages-spec})))

(defn filter-pages
  "Filters a page-list by a set of 0-indexed page indices.

   If page-set is nil, returns page-list unchanged (all pages).
   Otherwise returns only pages whose :page/index is in page-set."
  [page-list page-set]
  (if (nil? page-set)
    page-list
    (filterv #(contains? page-set (:page/index %)) page-list)))

;; =============================================================================
;; Public Serialization API
;; =============================================================================

(defn write-document-edn!
  "Writes a document to an EDN file, extracting image bytes to separate PNG files.

   Image data (byte arrays) in :page.node/image-data are written as PNG files
   in an 'images' subdirectory. The EDN stores the relative path instead of bytes.

   Instants are serialized as #inst tagged literals (EDN native).

   Params:
   `output-dir` - String. Path to the output directory (e.g., 'docs/manual.pageindex').
   `document` - Map. The PageIndex document.

   Returns:
   The output directory path."
  [output-dir document]
  (let [dir-file (io/file output-dir)
        images-dir (io/file output-dir "images")
        doc-with-paths (update document :document/pages
                         (fn [pages]
                           (mapv (fn [page]
                                   (update page :page/nodes
                                     (fn [nodes]
                                       (mapv (fn [node]
                                               (let [img-bytes (:page.node/image-data node)]
                                                 (if (and (bytes? img-bytes)
                                                       (#{:image :table} (:page.node/type node)))
                                                   (let [img-name (str (:page.node/id node) ".png")
                                                         img-path (io/file images-dir img-name)]
                                                     (when-not (.exists images-dir)
                                                       (.mkdirs images-dir))
                                                     (with-open [out (io/output-stream img-path)]
                                                       (.write out ^bytes img-bytes))
                                                     (trove/log! {:level :debug
                                                                  :data {:node-id (:page.node/id node) :path (str "images/" img-name)}
                                                                  :msg "Wrote image file"})
                                                     (-> node
                                                       (dissoc :page.node/image-data)
                                                       (assoc :page.node/image-path (str "images/" img-name))))
                                                   node)))
                                         nodes))))
                             pages)))
        edn-file (io/file dir-file "document.edn")]
    (when-not (.exists dir-file)
      (.mkdirs dir-file))
    (spit edn-file (with-out-str (pprint/pprint doc-with-paths)))
    (trove/log! {:level :debug :data {:path (str edn-file)} :msg "Wrote document EDN"})
    output-dir))

(defn read-document-edn
  "Reads a document from an EDN file, resolving image paths back to byte arrays.

   Image paths in :page.node/image-path are read back as byte arrays
   into :page.node/image-data.

   Params:
   `index-dir` - String. Path to the pageindex directory.

   Returns:
   The PageIndex document map with image bytes restored."
  [index-dir]
  (let [edn-file (io/file index-dir "document.edn")
        doc (fast-edn/read-once edn-file)]
    (update doc :document/pages
      (fn [pages]
        (mapv (fn [page]
                (update page :page/nodes
                  (fn [nodes]
                    (mapv (fn [node]
                            (if-let [img-rel-path (:page.node/image-path node)]
                              (let [img-file (io/file index-dir img-rel-path)]
                                (if (.exists img-file)
                                  (let [img-bytes (let [ba (byte-array (.length img-file))]
                                                    (with-open [is (java.io.FileInputStream. img-file)]
                                                      (.read is ba))
                                                    ba)]
                                    (-> node
                                      (dissoc :page.node/image-path)
                                      (assoc :page.node/image-data img-bytes)))
                                  (do
                                    (trove/log! {:level :warn
                                                 :data {:path (str img-file)}
                                                 :msg "Image file not found, skipping"})
                                    (dissoc node :page.node/image-path))))
                              node))
                      nodes))))
          pages)))))

;; =============================================================================
;; load-index
;; =============================================================================

(defn- ensure-absolute
  "Ensure the path is absolute."
  [path]
  (if (fs/absolute? path)
    (str path)
    (str (fs/absolutize path))))

(defn load-index
  "Load an indexed document from a pageindex directory (EDN + PNG files).

   Params:
   `index-path` - String. Path to the pageindex directory.

   Returns:
   The RLM document map.

   Throws:
   - ex-info if path not found or not a directory
   - ex-info if document fails spec validation

   Example:
   (load-index \"docs/manual.pageindex\")"
  [index-path]
  (let [abs-path (ensure-absolute index-path)]
    (when-not (fs/exists? abs-path)
      (trove/log! {:level :error :data {:path abs-path} :msg "Index path not found"})
      (anomaly/not-found! "Index path not found" {:type :svar.pageindex/index-not-found :path abs-path}))
    (when-not (fs/directory? abs-path)
      (anomaly/incorrect! "Index path must be a pageindex directory"
        {:type :svar.pageindex/invalid-path :path abs-path}))
    (trove/log! {:level :debug :data {:path abs-path} :msg "Loading index"})
    (let [document (read-document-edn abs-path)]
      (when-not (schema/valid-document? document)
        (let [explanation (schema/explain-document document)]
          (trove/log! {:level :error :data {:path abs-path :explanation explanation} :msg "Loaded document failed spec validation"})
          (anomaly/incorrect! "Loaded document failed spec validation"
            {:type :rlm/invalid-document
             :path abs-path
             :explanation explanation})))
      (trove/log! {:level :info :data {:path abs-path
                                       :document/name (:document/name document)
                                       :pages (count (:document/pages document))
                                       :toc-entries (count (:document/toc document))}
                   :msg "Loaded document"})
      document)))

;; =============================================================================
;; inspect
;; =============================================================================

(defn- print-toc-tree
  "Print the TOC as a tree structure."
  [toc]
  (doseq [entry toc]
    (let [depth (dec (count (str/split (or (:node/structure entry) "1") #"\.")))
          indent (str/join "" (repeat depth "  "))]
      (printf "%s%s %s\n" indent (or (:node/structure entry) "?") (:node/title entry)))))

(defn ^:export inspect
  "Load and print a full summary of an indexed document including TOC tree.

   Params:
   `doc-or-path` - Either a document map or String path to EDN file.

   Returns:
   Summary map with document stats.

   Throws:
   - ex-info if path provided and file not found
   - ex-info if document fails spec validation

   Example:
   (inspect \"docs/manual.edn\")
   (inspect my-document)"
  [doc-or-path]
  (trove/log! {:level :debug :data {:input (if (string? doc-or-path) doc-or-path :document-map)} :msg "Inspecting document"})
  (let [doc (if (string? doc-or-path)
              (load-index doc-or-path)
              (do
                (when-not (schema/valid-document? doc-or-path)
                  (let [explanation (schema/explain-document doc-or-path)]
                    (trove/log! {:level :error :data {:explanation explanation} :msg "Document failed spec validation"})
                    (anomaly/incorrect! "Document failed spec validation"
                      {:type :rlm/invalid-document
                       :explanation explanation})))
                doc-or-path))
        toc (:document/toc doc)
        pages (:document/pages doc)]
    (println "\n=== Document Summary ===")
    (println "Name:      " (:document/name doc))
    (println "Title:     " (or (:document/title doc) "(none)"))
    (println "Extension: " (:document/extension doc))
    (println "Author:    " (or (:document/author doc) "(none)"))
    (println "Pages:     " (count pages))
    (println "TOC entries:" (count toc))
    (println "Created:   " (:document/created-at doc))
    (println "Updated:   " (:document/updated-at doc))
    (when (:document/abstract doc)
      (println "\n--- Abstract ---")
      (println (:document/abstract doc)))
    (println "\n--- TOC Tree ---")
    (print-toc-tree toc)
    (println)
    {:document/name (:document/name doc)
     :document/title (:document/title doc)
     :page-count (count pages)
     :toc-count (count toc)
     :has-abstract (boolean (:document/abstract doc))}))

;; =============================================================================
;; index! — incremental document indexing
;; =============================================================================
;; NOTE: index! is defined in rlm.clj (not here) because it calls extract-pdf-pages
;; and finalize-pdf-document which are private fns in rlm.clj that tests redef via
;; with-redefs on com.blockether.svar.internal.rlm/extract-text etc.
