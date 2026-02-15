(ns com.blockether.svar.rlm.internal.pageindex.core
  "Main API for RLM document indexing - extracts structured data from documents.
   
   Primary functions:
   - `build-index` - Extract structure from file path or string content
   - `index!` - Index and save to EDN + PNG files
   - `load-index` - Load indexed document from EDN directory
   - `inspect` - Print full document summary with TOC tree
   - `print-toc-tree` - Print a formatted TOC tree from TOC entries
   
   Supported file types:
   - PDF (.pdf) - Uses vision LLM for node-based extraction
   - Markdown (.md, .markdown) - Parses heading structure into pages (no LLM needed)
   - Plain text (.txt, .text) - Uses LLM for text extraction
   - Images (.png, .jpg, .jpeg, .gif, .bmp, .webp) - Direct vision LLM extraction
   
   Markdown files are parsed deterministically by heading structure:
   - Top-level headings (h1, or first level found) become page boundaries
   - Nested headings become section nodes within each page
   - No LLM required for structure extraction
   
   Post-processing:
   1. Translates local node IDs to globally unique UUIDs
   2. If no TOC exists in document, generates one from Section/Heading structure
   3. Links TocEntry target-section-id to matching Section nodes
   4. Generates document abstract from all section descriptions using Chain of Density
   
   Usage:
   (require '[com.blockether.svar.rlm.internal.pageindex.core :as pageindex])
   
   ;; Index a PDF
   (def doc (pageindex/build-index \"manual.pdf\"))
   
   ;; Index and save to EDN + PNG files
   (pageindex/index! \"manual.pdf\")
   ;; => {:document {...} :output-path \"manual.pageindex\"}
   
   ;; Load and inspect (includes TOC tree)
   (pageindex/inspect \"manual.pageindex\")"
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.core :as svar]
    [com.blockether.svar.internal.util :as util]
    [com.blockether.svar.rlm.internal.pageindex.markdown :as markdown]
   [com.blockether.svar.rlm.internal.pageindex.pdf :as pdf]
   [com.blockether.svar.rlm.internal.pageindex.spec :as rlm-spec]
   [com.blockether.svar.rlm.internal.pageindex.vision :as vision]
   [fast-edn.core :as edn]
   [taoensso.trove :as trove])
  (:import
   [java.time Instant]
   [java.util Date UUID]))

;; Ensure java.time.Instant prints as #inst in EDN (pprint only knows java.util.Date)
(defmethod print-method Instant [^Instant inst ^java.io.Writer w]
  (print-method (Date/from inst) w))

;; =============================================================================
;; Helper: Extract Document Name
;; =============================================================================

(defn- extract-doc-name
  "Extracts document name from file path (without extension).
   
   Params:
   `file-path` - String. Path to file.
   
   Returns:
   String. Document name without extension."
  [file-path]
  (-> file-path
      (io/file)
      (.getName)
      (str/replace #"\.(pdf|md|markdown|txt|text)$" "")))

(defn- extract-extension
  "Extracts file extension from file path.
   
   Params:
   `file-path` - String. Path to file.
   
   Returns:
   String. File extension (e.g., \"pdf\", \"md\", \"txt\")."
  [file-path]
  (let [name (.getName (io/file file-path))]
    (when-let [idx (str/last-index-of name ".")]
      (subs name (inc (long idx))))))

;; =============================================================================
;; File Type Detection (moved from text.clj)
;; =============================================================================

(defn- file-type
  "Determines the type of file based on extension.
   
   Params:
   `file-path` - String. Path to file.
   
   Returns:
   Keyword - :pdf, :markdown, :text, :image, or :unknown."
  [file-path]
  (let [ext (some-> (extract-extension file-path) str/lower-case)]
    (cond
      (= "pdf" ext) :pdf
      (#{"md" "markdown"} ext) :markdown
      (#{"txt" "text"} ext) :text
      (#{"png" "jpg" "jpeg" "gif" "bmp" "webp"} ext) :image
      :else :unknown)))

;; =============================================================================
;; Supported File Types
;; =============================================================================

(def ^:private SUPPORTED_EXTENSIONS
  "Set of supported file extensions."
  #{".pdf" ".md" ".markdown" ".txt" ".text" ".png" ".jpg" ".jpeg" ".gif" ".bmp" ".webp"})

(defn- supported-extension?
  "Returns true if file path has a supported extension."
  [file-path]
  (let [lower-path (str/lower-case file-path)]
    (some #(str/ends-with? lower-path %) SUPPORTED_EXTENSIONS)))

(defn- file-path?
  "Returns true if input is a valid file path (file must exist).
   
   We don't use heuristics like 'contains /' because content strings
   can contain paths (URLs, code examples, etc.). The only reliable
   check is whether the file actually exists."
  [input]
  (try
    (let [file (io/file input)]
      (and (.exists file)
           (.isFile file)))
    (catch Exception _
      ;; Invalid path (e.g., too long, invalid characters)
      false)))

;; =============================================================================
;; ID Translation (Local IDs → Global UUIDs)
;; =============================================================================

(defn- translate-page-ids
  "Translates local node IDs to globally unique UUIDs for a single page.
   
   Each page extraction produces local IDs (1, 2, 3...) that collide across pages.
   This function:
   1. Creates a mapping of local-id -> UUID for all nodes on the page
   2. Updates all :page.node/id and :document.toc/id to use UUIDs
   3. Updates all parent-id references to use UUIDs
   4. Updates all target-section-id references to use UUIDs
   
   Handles both :page.node/* namespace (most nodes) and :document.toc/* namespace (TOC entries).
   
   Params:
   `page` - Map with :page/index and :page/nodes.
   
   Returns:
   Updated page with all IDs translated to UUIDs."
  [page]
  (let [nodes (:page/nodes page)
        ;; Build mapping of local-id -> UUID for all nodes on this page
        ;; Collect IDs from both :page.node/id and :document.toc/id
        id-mapping (reduce
                    (fn [acc node]
                      (let [local-id (or (:page.node/id node) (:document.toc/id node))]
                        (if local-id
                          (assoc acc local-id (str (UUID/randomUUID)))
                          acc)))
                    {}
                    nodes)
        ;; Translate IDs in all nodes (both namespaces)
        translated-nodes (mapv
                          (fn [node]
                            (cond-> node
                               ;; Translate :page.node/id
                              (:page.node/id node)
                              (assoc :page.node/id (get id-mapping (:page.node/id node)))

                               ;; Translate :page.node/parent-id (if exists and in mapping)
                              (and (:page.node/parent-id node)
                                   (get id-mapping (:page.node/parent-id node)))
                              (assoc :page.node/parent-id (get id-mapping (:page.node/parent-id node)))

                               ;; Translate :page.node/target-section-id (if exists and in mapping)
                              (and (:page.node/target-section-id node)
                                   (get id-mapping (:page.node/target-section-id node)))
                              (assoc :page.node/target-section-id (get id-mapping (:page.node/target-section-id node)))

                               ;; Translate :document.toc/id
                              (:document.toc/id node)
                              (assoc :document.toc/id (get id-mapping (:document.toc/id node)))

                               ;; Translate :document.toc/parent-id (if exists and in mapping)
                              (and (:document.toc/parent-id node)
                                   (get id-mapping (:document.toc/parent-id node)))
                              (assoc :document.toc/parent-id (get id-mapping (:document.toc/parent-id node)))

                               ;; Translate :document.toc/target-section-id (if exists and in mapping)
                               ;; Note: This references a :page.node/id, so we use the same mapping
                              (and (:document.toc/target-section-id node)
                                   (get id-mapping (:document.toc/target-section-id node)))
                              (assoc :document.toc/target-section-id (get id-mapping (:document.toc/target-section-id node)))))
                          nodes)]
    (assoc page :page/nodes translated-nodes)))

(defn- translate-all-ids
  "Translates all local node IDs to globally unique UUIDs across all pages.
   
   Params:
   `pages` - Vector of page maps.
   
   Returns:
   Vector of pages with all IDs translated to UUIDs."
  [pages]
  (mapv translate-page-ids pages))

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
    (let [;; Build a mutable state: node-id -> group-id
          group-assignments (atom {})
          ;; Process pages pairwise
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
                              ;; Propagate existing group-id or create new one
                              existing-group (get @group-assignments prev-id)
                              group-id (or existing-group (str (UUID/randomUUID)))]
                          ;; Assign group-id to both predecessor and current
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
;; TOC Post-Processing
;; =============================================================================

(defn- collect-all-nodes
  "Collects all nodes from all pages into a flat sequence.
   
   Params:
   `pages` - Vector of page maps with :page/nodes.
   
   Returns:
   Lazy sequence of all nodes across all pages."
  [pages]
  (mapcat :page/nodes pages))

(defn- has-toc-entries?
  "Returns true if any TocEntry nodes exist in the pages.
   
   Params:
   `pages` - Vector of page maps with :page/nodes.
   
   Returns:
   Boolean."
  [pages]
  (boolean (some #(= :toc-entry (:document.toc/type %)) (collect-all-nodes pages))))

(defn- heading-level->toc-level
  "Converts heading level (h1, h2, etc.) to TOC level (l1, l2, etc.).
   
   Params:
   `heading-level` - String like 'h1', 'h2', etc.
   
   Returns:
   String like 'l1', 'l2', etc."
  [heading-level]
  (when heading-level
    (str "l" (subs heading-level 1))))

(defn- build-toc-from-structure
  "Builds TOC entries from Section/Heading structure.
   
   Scans all pages for Section nodes that have associated Heading nodes,
   and creates TocEntry nodes linking to them.
   
   Params:
   `pages` - Vector of page maps with :page/nodes.
   
   Returns:
   Vector of TocEntry node maps, or empty vector if no sections found."
  [pages]
  (let [all-nodes (vec (collect-all-nodes pages))
        ;; Build a map of section-id -> heading for that section
        ;; A heading belongs to a section if its parent-id matches the section's id
        section-headings (reduce
                          (fn [acc node]
                            (if (and (= :heading (:page.node/type node))
                                     (:page.node/parent-id node))
                              (assoc acc (:page.node/parent-id node) node)
                              acc))
                          {}
                          all-nodes)
        ;; Find all sections and create TOC entries
        sections (filter #(= :section (:page.node/type %)) all-nodes)
        ;; Find page index for each section
        section-page-index (reduce
                            (fn [acc {:keys [page/index page/nodes]}]
                              (reduce
                               (fn [acc2 node]
                                 (if (= :section (:page.node/type node))
                                   (assoc acc2 (:page.node/id node) index)
                                   acc2))
                               acc
                               nodes))
                            {}
                            pages)]
    (vec
     (keep
      (fn [section]
        (when-let [heading (get section-headings (:page.node/id section))]
          {:document.toc/type :toc-entry
           :document.toc/id (str (UUID/randomUUID))
           :document.toc/parent-id nil
           :document.toc/title (:page.node/content heading)
           :document.toc/description (:page.node/description section)
           :document.toc/target-page (get section-page-index (:page.node/id section))
           :document.toc/target-section-id (:page.node/id section)
           :document.toc/level (heading-level->toc-level (:page.node/level heading))}))
      sections))))

(defn- link-toc-entries
  "Links existing TocEntry nodes to matching Section nodes.
   
   Matches TocEntry titles to Heading content to find the target Section.
   Uses normalized exact matching (trim + lowercase) for robustness.
   Also copies the Section's description to the TocEntry.
   
   Params:
   `pages` - Vector of page maps with :page/nodes (must have UUIDs already).
   
   Returns:
   Updated pages with TocEntry target-section-id and description populated where matches found."
  [pages]
  (let [all-nodes (vec (collect-all-nodes pages))
        ;; Build map of section-id -> section node (for description lookup)
        section-by-id (reduce
                       (fn [acc node]
                         (if (= :section (:page.node/type node))
                           (assoc acc (:page.node/id node) node)
                           acc))
                       {}
                       all-nodes)
        ;; Build map of normalized heading content -> section-id
        ;; A heading's parent-id is the section it introduces
        heading->section (reduce
                          (fn [acc node]
                            (if (and (= :heading (:page.node/type node))
                                     (:page.node/content node)
                                     (:page.node/parent-id node))
                              (let [normalized (-> (:page.node/content node)
                                                   str/trim
                                                   str/lower-case)]
                                (assoc acc normalized (:page.node/parent-id node)))
                              acc))
                          {}
                          all-nodes)]
    ;; Update TocEntry nodes with target-section-id and description
    (mapv
     (fn [page]
       (update page :page/nodes
               (fn [nodes]
                 (mapv
                  (fn [node]
                    (if (and (= :toc-entry (:document.toc/type node))
                             (nil? (:document.toc/target-section-id node))
                             (:document.toc/title node))
                      (let [normalized-title (-> (:document.toc/title node)
                                                 str/trim
                                                 str/lower-case)
                            section-id (get heading->section normalized-title)
                            section (when section-id (get section-by-id section-id))]
                        (if section-id
                          (cond-> node
                            true (assoc :document.toc/target-section-id section-id)
                            (:page.node/description section) (assoc :document.toc/description (:page.node/description section)))
                          node))
                      node))
                  nodes))))
     pages)))

(defn- postprocess-toc
  "Post-processes pages to ensure TOC exists and is properly linked.
   
   1. If no TocEntry nodes exist, generates TOC from Section/Heading structure
   2. If TocEntry nodes exist, links target-section-id to matching Sections
   
   Params:
   `pages` - Vector of page maps with :page/nodes.
   
   Returns:
   Map with:
     :pages - Updated pages (with linked TocEntry if they existed)
     :toc - Vector of TocEntry nodes (generated or extracted from pages)"
  [pages]
  (if (has-toc-entries? pages)
    ;; TOC exists - link entries to sections and extract them
    (let [linked-pages (link-toc-entries pages)
          toc-entries (vec (filter #(= :toc-entry (:document.toc/type %))
                                   (collect-all-nodes linked-pages)))]
      (trove/log! {:level :debug :data {:toc-entries (count toc-entries)}
                   :msg "Linked existing TOC entries to sections"})
      {:pages linked-pages
       :toc toc-entries})
    ;; No TOC - generate from structure
    (let [generated-toc (build-toc-from-structure pages)]
      (trove/log! {:level :debug :data {:generated-entries (count generated-toc)}
                   :msg "Generated TOC from document structure"})
      {:pages pages
       :toc generated-toc})))

;; =============================================================================
;; Document Abstract Generation
;; =============================================================================

(defn- collect-section-descriptions
  "Collects all :page.node/description values from Section nodes across all pages.
   
   Params:
   `pages` - Vector of page maps with :page/nodes.
   
   Returns:
   Vector of non-empty description strings."
  [pages]
  (->> pages
       (mapcat :page/nodes)
       (filter #(= :section (:page.node/type %)))
       (keep :page.node/description)
       (filter seq)
       vec))

(defn- generate-document-abstract
  "Generates a document-level abstract from all section descriptions.
   
   Collects all :page.node/description values from Section nodes and uses
   abstract! to create a cohesive document summary.
   
   Params:
   `pages` - Vector of page maps with :page/nodes.
   `opts` - Map with :model and :config keys for LLM.
   
   Returns:
   String. Document abstract, or nil if no section descriptions found."
  [pages {:keys [model config]}]
  (let [descriptions (collect-section-descriptions pages)]
    (when (seq descriptions)
      (trove/log! {:level :info :data {:section-count (count descriptions)}
                   :msg "Generating document abstract from section descriptions"})
      (let [;; Combine all descriptions into a single text for summarization
            combined-text (str/join "\n\n" descriptions)
            ;; Target ~150 words for document abstract
            abstracts (svar/abstract! {:text combined-text
                                       :model model
                                       :target-length 150
                                       :iterations 3
                                       :config config})]
        (when (seq abstracts)
          (let [abstract (:summary (last abstracts))]
            (trove/log! {:level :info :data {:abstract-length (count abstract)}
                         :msg "Document abstract generated"})
            abstract))))))

;; =============================================================================
;; Text Extraction
;; =============================================================================

(defn- extract-text
  "Extract text from document.
   
   Routes to appropriate extractor based on file type:
   - PDF: Convert to images, then vision LLM extraction
   - Markdown: Parse heading structure (no LLM - deterministic)
   - Text: Uses LLM for text extraction
   - Image: Direct vision LLM extraction
   
   Markdown parsing is fast and deterministic - top-level headings become pages.
   
   Throws for unsupported file types.
   
   Returns page-list vector."
  [file-path opts]
  (let [ftype (file-type file-path)]
    (when (= :unknown ftype)
      (let [extension (extract-extension file-path)]
        (anomaly/unsupported! (str "Unsupported file type: " (or extension "unknown"))
                              {:type :svar.pageindex/unsupported-file-type
                               :file file-path
                               :extension extension
                               :supported-extensions SUPPORTED_EXTENSIONS})))
     (trove/log! {:level :info :data {:file file-path :type ftype}
                  :msg "Extracting text from document"})
    (let [[page-list duration-ms]
          (util/with-elapsed
            (case ftype
              :pdf (vision/extract-text-from-pdf file-path opts)
              :markdown (markdown/markdown-file->pages file-path)
              :text (vision/extract-text-from-text-file file-path opts)
              :image (vision/extract-text-from-image-file file-path opts)))]
      (trove/log! {:level :info :data {:pages (count page-list)
                                       :type ftype
                                       :duration-ms duration-ms}
                   :msg "Text extraction complete"})
      page-list)))

;; =============================================================================
;; Input Type Detection
;; =============================================================================

(defn- detect-input-type
  "Detects the type of input for build-index dispatch.
   
   Params:
   `input` - String. Either a file path or raw content.
   `opts` - Map. Options that may contain :content-type.
   
   Returns:
   Keyword - :path (file path) or :string (raw content)."
  [input opts]
  (cond
    ;; Explicit content-type means it's raw string content
    (:content-type opts)
    :string

    ;; Check if file actually exists - this is the only reliable check
    ;; We don't use heuristics because content can contain paths/URLs
    (file-path? input)
    :path

    ;; Default to string content (will require :content-type validation later)
    :else
    :string))

;; =============================================================================
;; Main API - Multimethod
;; =============================================================================

(defmulti build-index
  "Builds an index from a document by extracting content as nodes.
   
   Multimethod that dispatches based on input type:
   - `:path` - File path (auto-detects type from extension: .pdf, .md, .txt)
   - `:string` - Raw string content (requires :content-type in opts)
   
   Supported file types:
   - PDF (.pdf) - Uses vision LLM for node-based extraction
   - Markdown (.md, .markdown) - Parses headings as heading/paragraph nodes
   - Plain text (.txt, .text) - Chunks by paragraphs into paragraph nodes
   
   Post-processing:
   - If document has TOC pages, extracts TocEntry nodes and links to Sections
   - If no TOC exists, generates one from Section/Heading structure
   
    Params:
    `input` - String. File path or raw content.
    `opts` - Optional map with:
      ;; For dispatch (string input)
      `:content-type` - Keyword. Required for string input: :md, :markdown, :txt, :text
      `:doc-name` - String. Document name (required for string input).
      
      ;; For metadata (string input only - PDF extracts from file)
      `:doc-title` - String. Document title.
      `:doc-author` - String. Document author.
      `:created-at` - Instant. Creation date.
      `:updated-at` - Instant. Modification date.
      
      ;; For processing
      `:model` - String. Vision LLM model to use.
      
      ;; Quality refinement (opt-in)
      `:refine?` - Boolean, optional. Enable post-extraction quality refinement (default: false).
      `:refine-model` - String, optional. Model for eval/refine steps (default: \"gpt-4o\").
      `:refine-iterations` - Integer, optional. Max refine iterations per page (default: 1).
      `:refine-threshold` - Float, optional. Min eval score to pass (default: 0.8).
      `:refine-sample-size` - Integer, optional. Pages to sample for eval (default: 3).
                              For PDFs, samples first + last + random middle pages.
   
   Returns:
   Map with:
     `:document/name` - String. Document name without extension.
     `:document/title` - String or nil. Document title from metadata.
     `:document/abstract` - String or nil. Document summary generated from section descriptions.
     `:document/extension` - String. File extension (pdf, md, txt).
     `:document/pages` - Vector of page maps with:
       - `:page/index` - Integer (0-indexed)
       - `:page/nodes` - Vector of content nodes (heading, paragraph, image, table, etc.)
     `:document/toc` - Vector of TocEntry nodes (extracted or generated):
        - `:document.toc/type` - :toc-entry
        - `:document.toc/id` - UUID string
        - `:document.toc/title` - Entry title text
        - `:document.toc/description` - Section description (copied from linked Section)
        - `:document.toc/target-page` - Page number (0-indexed) or nil
        - `:document.toc/target-section-id` - UUID of linked Section node or nil
        - `:document.toc/level` - Nesting level (l1, l2, etc.)
     `:document/created-at` - Instant. Creation date from metadata or now.
     `:document/updated-at` - Instant. Modification date from metadata or now.
     `:document/author` - String or nil. Document author from metadata."
  (fn [input & [opts]]
    (detect-input-type input (or opts {}))))

;; =============================================================================
;; build-index - :path method (file path input)
;; =============================================================================

(defmethod build-index :path
  [file-path & [opts]]
  ;; Validate file exists
   (when-not (.exists (io/file file-path))
     (anomaly/not-found! (str "File not found: " file-path)
                         {:type :svar.pageindex/file-not-found :file file-path}))
   ;; Validate file type is supported
   (when-not (supported-extension? file-path)
     (let [extension (extract-extension file-path)]
       (anomaly/unsupported! (str "Unsupported file type: " (or extension "unknown"))
                             {:type :svar.pageindex/unsupported-file-type
                              :file file-path
                              :extension extension
                              :supported-extensions SUPPORTED_EXTENSIONS})))
  (let [vision-model (or (:model opts) vision/DEFAULT_VISION_MODEL)
        vision-objective (or (:objective opts) vision/DEFAULT_VISION_OBJECTIVE)
        vision-config (:config opts)
        output-dir (:output-dir opts)
        vision-opts {:model vision-model :objective vision-objective :config vision-config}]
    (trove/log! {:level :info :data {:file file-path}
                 :msg "Starting text extraction from file"})
    (let [page-list-raw (extract-text file-path (merge opts vision-opts))
           ;; Step 1: Translate local IDs to global UUIDs
          page-list-uuids (translate-all-ids page-list-raw)
          ;; Step 2: Group continuation nodes across pages
          page-list (group-continuations page-list-uuids)
          doc-name (extract-doc-name file-path)
          extension (extract-extension file-path)
          ftype (file-type file-path)
          ;; Extract PDF metadata if available
          file-metadata (when (= :pdf ftype)
                          (try
                            (pdf/pdf-metadata file-path)
                            (catch Exception e
                              (trove/log! {:level :warn :data {:error (ex-message e)}
                                           :msg "Failed to extract PDF metadata"})
                              nil)))
          ;; Step 3: Post-process TOC (build/link with UUIDs)
          {:keys [pages toc]} (postprocess-toc page-list)
          pages-with-images (if output-dir
                              (let [dir-file (io/file output-dir)]
                                (when-not (.exists dir-file)
                                   (anomaly/not-found! (str "Output directory not found: " output-dir)
                                                       {:type :svar.pageindex/output-dir-not-found :output-dir output-dir}))
                                (mapv (fn [page]
                                        (update page :page/nodes
                                                (fn [nodes]
                                                  (mapv (fn [node]
                                                          (let [img-bytes (:page.node/image-data node)]
                                                            (if (and (#{:image :table} (:page.node/type node))
                                                                     img-bytes)
                                                              (do
                                                                (try
                                                                  (let [file-path (fs/path output-dir (str (:page.node/id node) ".png"))]
                                                                    (with-open [out (io/output-stream (io/file (str file-path)))]
                                                                      (.write out ^bytes img-bytes)))
                                                                  (catch Exception e
                                                                    (trove/log! {:level :warn
                                                                                 :data {:node-id (:page.node/id node)
                                                                                        :error (ex-message e)}
                                                                                 :msg "Failed to write image bytes to output directory"})))
                                                                (dissoc node :page.node/image-data))
                                                              node)))
                                                        nodes))))
                                      pages))
                              pages)
           ;; Step 3: Generate document abstract from section descriptions
          abstract-opts {:model vision-model :config vision-config}
          document-abstract (generate-document-abstract pages-with-images abstract-opts)
          ;; Step 4: Infer title if not in metadata
          metadata-title (:title file-metadata)
          inferred-title (when-not metadata-title
                           (vision/infer-document-title pages {:model vision-model :config vision-config}))
          final-title (or metadata-title inferred-title)
          now (Instant/now)]
      (trove/log! {:level :info :data {:document/name doc-name
                                       :pages (count pages)
                                       :toc-entries (count toc)
                                       :has-metadata (boolean file-metadata)
                                       :title-inferred (boolean inferred-title)
                                       :has-abstract (boolean document-abstract)}
                   :msg "Text extraction complete"})
      {:document/name doc-name
       :document/title final-title
       :document/abstract document-abstract
       :document/extension extension
       :document/pages pages-with-images
       :document/toc toc
       :document/created-at (or (:created-at file-metadata) now)
       :document/updated-at (or (:updated-at file-metadata) now)
       :document/author (:author file-metadata)})))

;; =============================================================================
;; build-index - :string method (raw content input)
;; =============================================================================

(defmethod build-index :string
  [content & [opts]]
  (let [{:keys [content-type doc-name doc-title doc-author created-at updated-at]} (or opts {})
        vision-model (or (:model opts) vision/DEFAULT_VISION_MODEL)
        vision-objective (or (:objective opts) vision/DEFAULT_VISION_OBJECTIVE)
        vision-config (:config opts)
        vision-opts {:model vision-model :objective vision-objective :config vision-config}]
    ;; Validate required options
     (when-not content-type
       (anomaly/incorrect! "Missing required :content-type option for string input"
                           {:type :svar.pageindex/missing-content-type :valid-types [:md :txt]}))
     (when-not doc-name
       (anomaly/incorrect! "Missing required :doc-name option for string input" {:type :svar.pageindex/missing-doc-name}))
    (trove/log! {:level :info :data {:doc-name doc-name :content-type content-type}
                 :msg "Starting text extraction from string content"})
    (let [page-list-raw (case content-type
                          :pdf (anomaly/unsupported! "PDF content-type not supported for string input"
                                                     {:type :svar.pageindex/pdf-string-unsupported
                                                      :hint "PDF requires vision extraction from file path"})
                          :md (markdown/markdown->pages content)
                          :markdown (markdown/markdown->pages content)
                          :txt (vision/extract-text-from-string content (merge opts vision-opts))
                          :text (vision/extract-text-from-string content (merge opts vision-opts))
                          (anomaly/incorrect! "Unknown content-type"
                                               {:type :svar.pageindex/unknown-content-type
                                                :content-type content-type
                                                :valid-types [:md :txt]}))
          ;; Step 1: Translate local IDs to global UUIDs
          page-list-uuids (translate-all-ids page-list-raw)
          ;; Step 2: Group continuation nodes across pages
          page-list (group-continuations page-list-uuids)
          ;; Step 3: Post-process TOC (build/link with UUIDs)
          {:keys [pages toc]} (postprocess-toc page-list)
          ;; Step 4: Generate document abstract from section descriptions
          abstract-opts {:model vision-model :config vision-config}
          document-abstract (generate-document-abstract pages abstract-opts)
          ;; Step 5: Infer title if not provided
          inferred-title (when-not doc-title
                           (vision/infer-document-title pages {:model vision-model :config vision-config}))
          final-title (or doc-title inferred-title)
          extension (name content-type)
          now (Instant/now)]
      (trove/log! {:level :info :data {:document/name doc-name
                                       :pages (count pages)
                                       :toc-entries (count toc)
                                       :title-inferred (boolean inferred-title)
                                       :has-abstract (boolean document-abstract)}
                   :msg "Text extraction complete"})
      {:document/name doc-name
       :document/title final-title
       :document/abstract document-abstract
       :document/extension extension
       :document/pages pages
       :document/toc toc
       :document/created-at (or created-at now)
       :document/updated-at (or updated-at now)
       :document/author doc-author})))

;; =============================================================================
;; EDN + PNG Serialization
;; =============================================================================

(defn- derive-index-path
  "Derive the EDN output directory from the input file path.
   
   Example: /path/to/document.pdf -> /path/to/document.pageindex/"
  [input-path]
  (let [parent (fs/parent input-path)
        base-name (fs/strip-ext (fs/file-name input-path))]
    (str (when parent (str parent "/")) base-name ".pageindex")))

(defn- ensure-absolute
  "Ensure the path is absolute."
  [path]
  (if (fs/absolute? path)
    (str path)
    (str (fs/absolutize path))))

;; =============================================================================
;; Public Serialization API
;; =============================================================================

(defn- write-document-edn!
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
        ;; Extract images and replace bytes with relative paths
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
                                                                 ;; Ensure images dir exists
                                                                 (when-not (.exists images-dir)
                                                                   (.mkdirs images-dir))
                                                                 ;; Write PNG
                                                                 (with-open [out (io/output-stream img-path)]
                                                                   (.write out ^bytes img-bytes))
                                                                 (trove/log! {:level :debug
                                                                              :data {:node-id (:page.node/id node) :path (str "images/" img-name)}
                                                                              :msg "Wrote image file"})
                                                                 ;; Replace bytes with relative path
                                                                 (-> node
                                                                     (dissoc :page.node/image-data)
                                                                     (assoc :page.node/image-path (str "images/" img-name))))
                                                               node)))
                                                         nodes))))
                                       pages)))
        edn-file (io/file dir-file "document.edn")]
    ;; Ensure output dir exists
    (when-not (.exists dir-file)
      (.mkdirs dir-file))
    ;; Write pretty-printed EDN
    (spit edn-file (with-out-str (pprint/pprint doc-with-paths)))
    (trove/log! {:level :debug :data {:path (str edn-file)} :msg "Wrote document EDN"})
    output-dir))

(defn- read-document-edn
  "Reads a document from an EDN file, resolving image paths back to byte arrays.
   
   Image paths in :page.node/image-path are read back as byte arrays
   into :page.node/image-data.
   
   Params:
   `index-dir` - String. Path to the pageindex directory.
   
   Returns:
   The PageIndex document map with image bytes restored."
  [index-dir]
   (let [edn-file (io/file index-dir "document.edn")
        doc (edn/read-once edn-file)]
    ;; Resolve image paths back to byte arrays
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

(defn ^:export index!
  "Index a document file and save the result as EDN + PNG files.
   
   Takes a file path (PDF, MD, TXT) and runs build-index to extract structure.
   The result is saved as a directory alongside the original (or custom path):
     document.pageindex/
       document.edn    — structured data (EDN)
       images/          — extracted images as PNG files
   
    Params:
    `file-path` - String. Path to the document file.
    `opts` - Map, optional:
      - :output - Custom output directory path (default: same dir, .pageindex extension)
      - :model - LLM model override for vision extraction
      - :config - LLM config override
      - :refine? - Boolean. Enable post-extraction quality refinement (default: false)
      - :refine-model - String. Model for eval/refine steps (default: \"gpt-4o\")
      - :refine-iterations - Integer. Max refine iterations per page (default: 1)
      - :refine-threshold - Float. Min eval score to pass (default: 0.8)
      - :refine-sample-size - Integer. Pages to sample for eval (default: 3)
    
    Returns:
    Map with :document (the indexed document) and :output-path (directory where files were saved).
    
    Throws:
    - ex-info if file not found
    - ex-info if document fails spec validation
    
    Example:
    (index! \"docs/manual.pdf\")
    ;; => {:document {...} :output-path \"docs/manual.pageindex\"}
    
    ;; With quality refinement
    (index! \"docs/manual.pdf\" {:refine? true :refine-model \"gpt-4o\"})"
  ([file-path] (index! file-path {}))
   ([file-path {:keys [output model config
                      refine? refine-model refine-iterations
                      refine-threshold refine-sample-size]}]
    (let [abs-path (ensure-absolute file-path)
          output-path (or output (derive-index-path abs-path))]

      ;; Validate input exists
       (when-not (fs/exists? abs-path)
         (trove/log! {:level :error :data {:path abs-path} :msg "File not found"})
         (anomaly/not-found! "File not found" {:type :svar.pageindex/file-not-found :path abs-path}))

      (trove/log! {:level :info :data {:input abs-path :output output-path :refine? (boolean refine?)}
                   :msg "Starting document indexing"})

      ;; Run indexing
      (let [index-opts (cond-> {}
                         model (assoc :model model)
                         config (assoc :config config)
                         refine? (assoc :refine? refine?)
                         refine-model (assoc :refine-model refine-model)
                         refine-iterations (assoc :refine-iterations refine-iterations)
                         refine-threshold (assoc :refine-threshold refine-threshold)
                         refine-sample-size (assoc :refine-sample-size refine-sample-size))
           _ (trove/log! {:level :debug :data {:opts index-opts} :msg "Running build-index"})
           document (build-index abs-path index-opts)
           _ (trove/log! {:level :debug :data {:document/name (:document/name document)} :msg "build-index complete"})]

       ;; Validate the document - throw on failure
       (when-not (rlm-spec/valid-document? document)
         (let [explanation (rlm-spec/explain-document document)]
           (trove/log! {:level :error :data {:explanation explanation} :msg "Document failed spec validation"})
           (anomaly/incorrect! "Document failed spec validation"
                               {:type :rlm/invalid-document
                                :document/name (:document/name document)
                                :explanation explanation})))

       (trove/log! {:level :debug :msg "Spec validation passed"})

      ;; Save as EDN + PNG files
       (trove/log! {:level :debug :data {:path output-path} :msg "Writing EDN + PNG files"})
       (write-document-edn! output-path document)

       (trove/log! {:level :info :data {:document/name (:document/name document)
                                        :pages (count (:document/pages document))
                                        :toc-entries (count (:document/toc document))
                                        :output-path output-path}
                    :msg "Document indexed and saved successfully"})

       {:document document
        :output-path output-path}))))

(defn load-index
  "Load an indexed document from a pageindex directory (EDN + PNG files).
   
   Also supports loading legacy Nippy files for backward compatibility.
   
   Params:
   `index-path` - String. Path to the pageindex directory or legacy .nippy file.
   
   Returns:
   The RLM document map.
   
   Throws:
   - ex-info if path not found
   - ex-info if document fails spec validation
   
   Example:
   (load-index \"docs/manual.pageindex\")"
  [index-path]
  (let [abs-path (ensure-absolute index-path)]
    (when-not (fs/exists? abs-path)
      (trove/log! {:level :error :data {:path abs-path} :msg "Index path not found"})
       (anomaly/not-found! "Index path not found" {:type :svar.pageindex/index-not-found :path abs-path}))

    (trove/log! {:level :debug :data {:path abs-path} :msg "Loading index"})
    (let [document (if (fs/directory? abs-path)
                     ;; New format: directory with document.edn + images/
                     (read-document-edn abs-path)
                     ;; Legacy: could be a plain EDN file
                     (edn/read-once (io/file abs-path)))]

      ;; Validate the document - throw on failure
      (when-not (rlm-spec/valid-document? document)
        (let [explanation (rlm-spec/explain-document document)]
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
              ;; Validate document map if passed directly
              (do
                (when-not (rlm-spec/valid-document? doc-or-path)
                  (let [explanation (rlm-spec/explain-document doc-or-path)]
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
