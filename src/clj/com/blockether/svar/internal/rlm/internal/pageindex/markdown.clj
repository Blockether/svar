(ns com.blockether.svar.internal.rlm.internal.pageindex.markdown
  "Markdown parsing for RLM - extracts hierarchical structure from markdown files.
   
   Primary functions:
   - `markdown->pages` - Main API: convert markdown string to page-based format
   - `markdown-file->pages` - Convenience: reads file and calls markdown->pages
   
   Design:
   - Top-level headings (h1, or first heading level found) become 'pages'
   - Nested headings become nodes within each page
   - Code blocks are skipped when parsing headings
   - Each section includes text from heading to next heading
   - No LLM required - deterministic parsing"
  (:require
   [clojure.string :as str]
   [taoensso.trove :as trove]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private HEADING_PATTERN
  "Regex pattern for markdown headings (# through ######)."
  #"^(#{1,6})\s+(.+)$")

(def ^:private CODE_BLOCK_PATTERN
  "Regex pattern for code block delimiters (triple backticks)."
  #"^```")

;; =============================================================================
;; Heading Extraction
;; =============================================================================

(defn- parse-heading-line
  "Parses a single line to extract heading info if it's a heading.
   
   Params:
   `line` - String. The line to parse.
   `line-num` - Integer. 1-based line number.
   
   Returns:
   Map with :title, :level, :line-num if heading, nil otherwise."
  [line line-num]
  (when-let [match (re-matches HEADING_PATTERN (str/trim line))]
    (let [[_ hashes title] match]
      {:title (str/trim title)
       :level (count hashes)
       :line-num line-num})))

(defn parse-headings
  "Extracts all headings from markdown content.
   
   Skips headings inside code blocks (``` ... ```).
   
   Params:
   `content` - String. Markdown content.
   
   Returns:
   Vector of heading maps with :title, :level, :line-num (1-based)."
  [content]
  (let [lines (str/split-lines content)]
    (loop [remaining lines
           line-num 1
           in-code-block? false
           headings []]
      (if (empty? remaining)
        headings
        (let [line (first remaining)
              trimmed (str/trim line)
              ;; Toggle code block state
              new-in-code-block? (if (re-matches CODE_BLOCK_PATTERN trimmed)
                                   (not in-code-block?)
                                   in-code-block?)
              ;; Parse heading only if not in code block and not toggling into one
              heading (when (and (not in-code-block?)
                                 (not (re-matches CODE_BLOCK_PATTERN trimmed)))
                        (parse-heading-line line line-num))]
          (recur (rest remaining)
                 (inc line-num)
                 new-in-code-block?
                 (if heading
                   (conj headings heading)
                   headings)))))))

;; =============================================================================
;; Section Content Extraction
;; =============================================================================

(defn extract-sections
  "Extracts text content for each heading section.
   
   Each section's text spans from its heading line to the line before the next heading.
   
   Params:
   `content` - String. Markdown content.
   `headings` - Vector of heading maps from parse-headings.
   
   Returns:
   Vector of heading maps with :text added (content of that section)."
  [content headings]
  (let [lines (vec (str/split-lines content))
        total-lines (count lines)]
    (mapv (fn [heading next-heading]
            (let [start-line (dec (long (:line-num heading)))  ;; 0-based
                  end-line (if next-heading
                             (dec (long (:line-num next-heading)))  ;; Up to next heading
                             total-lines)
                  section-lines (subvec lines start-line end-line)
                  text (str/join "\n" section-lines)]
              (assoc heading :text (str/trim text))))
          headings
          (concat (rest headings) [nil]))))  ;; nil marks last section

;; =============================================================================
;; Tree Building
;; =============================================================================

(defn- build-tree-from-sections
  "Builds a hierarchical tree from sections using stack-based algorithm.
   
   Returns vector of root nodes. Each node has:
   - :title, :level, :line-num, :text
   - :children - vector of child nodes (empty if leaf)
   
   Params:
   `sections` - Vector of section maps with :title, :level, :line-num, :text.
   
   Returns:
   Vector of root nodes with nested :children."
  [sections]
  (when (seq sections)
    (let [;; Convert to atoms for easy mutation during tree building
          nodes (mapv (fn [s] (atom (assoc s :children []))) sections)
          stack (atom [])  ;; Stack of [atom-node, level]
          roots (atom [])]

      (doseq [node-atom nodes]
        (let [{:keys [level]} @node-atom]
          ;; Pop stack until we find a parent
          (while (and (seq @stack)
                      (>= (long (second (peek @stack))) (long level)))
            (swap! stack pop))

          (if (empty? @stack)
            ;; No parent - this is a root
            (swap! roots conj node-atom)
            ;; Has parent - add as child
            (let [[parent-atom _] (peek @stack)]
              (swap! parent-atom update :children conj node-atom)))

          ;; Push current node onto stack
          (swap! stack conj [node-atom level])))

      ;; Deref all atoms recursively to get plain data
      (letfn [(deref-tree [node-atom]
                (let [node @node-atom]
                  (-> node
                      (update :children #(mapv deref-tree %))
                      (dissoc :level))))]  ;; Remove :level from output
        (mapv deref-tree @roots)))))

;; =============================================================================
;; Convert Tree to Pages
;; =============================================================================

(defn- tree-node->page-nodes
  "Converts a tree node and its children to flat page nodes.
   
   Creates:
   - Section node for the tree node
   - Heading node linked to section
   - Paragraph nodes for any body text (excluding heading line)
   - Recursively processes children
   
   Params:
   `tree-node` - Tree node with :title, :line-num, :text, :children.
   `id-counter` - Atom with current ID counter.
   `parent-section-id` - String or nil. ID of parent section.
   
   Returns:
   Vector of page.node maps."
  [tree-node id-counter parent-section-id]
  (let [section-id (str (swap! id-counter inc))
        heading-id (str (swap! id-counter inc))

        ;; Extract body text (text after the heading line)
        full-text (:text tree-node)
        lines (str/split-lines full-text)
        body-lines (rest lines)  ;; Skip heading line
        body-text (str/trim (str/join "\n" body-lines))

        ;; Section node
        section-node {:page.node/type :section
                      :page.node/id section-id
                      :page.node/parent-id parent-section-id
                      :page.node/description nil}  ;; Could generate with LLM later

        ;; Heading node
        heading-level (str "h" (or (:level tree-node) 1))
        heading-node {:page.node/type :heading
                      :page.node/id heading-id
                      :page.node/parent-id section-id
                      :page.node/level heading-level
                      :page.node/content (:title tree-node)}

        ;; Paragraph node for body text (if any)
        paragraph-nodes (when (seq body-text)
                          (let [para-id (str (swap! id-counter inc))]
                            [{:page.node/type :paragraph
                              :page.node/id para-id
                              :page.node/parent-id section-id
                              :page.node/level "paragraph"
                              :page.node/content body-text}]))

        ;; Recursively process children
        child-nodes (mapcat #(tree-node->page-nodes % id-counter section-id)
                            (:children tree-node))]

    (concat [section-node heading-node]
            paragraph-nodes
            child-nodes)))

(defn- determine-top-level
  "Determines the top heading level in the document.
   
   Returns the minimum level found (e.g., 1 for h1, 2 if no h1 exists).
   
   Params:
   `headings` - Vector of heading maps with :level.
   
   Returns:
   Integer. The top (minimum) heading level."
  [headings]
  (if (empty? headings)
    1
    (apply min (map :level headings))))

(defn- group-by-top-level
  "Groups sections into pages based on top-level headings.
   
   Each top-level heading starts a new page. All nested headings
   until the next top-level heading belong to that page.
   
   Params:
   `sections` - Vector of section maps with :level.
   `top-level` - Integer. The level that defines page boundaries.
   
   Returns:
   Vector of vectors, each inner vector is sections for one page."
  [sections top-level]
  (when (seq sections)
    (let [;; Find indices where top-level sections start
          page-starts (keep-indexed
                       (fn [idx section]
                         (when (= (:level section) top-level)
                           idx))
                       sections)]
      (if (empty? page-starts)
        ;; No top-level headings - everything is one page
        [sections]
        ;; Group by page boundaries
        (let [;; Handle content before first top-level heading
              pre-content (when (pos? (long (first page-starts)))
                            (subvec sections 0 (first page-starts)))
              ;; Group remaining sections
              page-groups (map (fn [start-idx end-idx]
                                 (subvec sections start-idx end-idx))
                               page-starts
                               (concat (rest page-starts) [(count sections)]))]
          (if pre-content
            (cons pre-content page-groups)
            page-groups))))))

;; =============================================================================
;; Main API
;; =============================================================================

(defn markdown->pages
  "Converts markdown content to page-based format for RLM indexing.
   
   Design:
   - Top-level headings (lowest level found, usually h1) become page boundaries
   - Each page contains all nested sections until the next top-level heading
   - Sections become :section nodes with :heading and :paragraph children
   
   Params:
   `content` - String. Markdown content.
   
   Returns:
   Vector of page maps:
     :page/index - Integer (0-indexed)
     :page/nodes - Vector of node maps (:page.node/type, :page.node/id, etc.)"
  [content]
  (trove/log! {:level :info :data {:content-length (count content)}
               :msg "Parsing markdown structure"})

  (let [headings (parse-headings content)
        _ (trove/log! {:level :debug :data {:heading-count (count headings)}
                       :msg "Extracted headings from markdown"})

        sections (extract-sections content headings)
        top-level (determine-top-level headings)
        _ (trove/log! {:level :debug :data {:top-level top-level}
                       :msg "Determined top heading level"})

        ;; Group sections by top-level headings (each group = one page)
        page-groups (group-by-top-level sections top-level)
        _ (trove/log! {:level :debug :data {:page-count (count page-groups)}
                       :msg "Grouped sections into pages"})]

    ;; Convert each page group to our page format
    (vec
     (map-indexed
      (fn [page-idx page-sections]
        (let [id-counter (atom 0)
                ;; Build tree for this page's sections
              tree-roots (build-tree-from-sections page-sections)
                ;; Convert tree to flat page nodes
              nodes (vec (mapcat #(tree-node->page-nodes % id-counter nil)
                                 tree-roots))]
          (trove/log! {:level :debug :data {:page page-idx :nodes (count nodes)}
                       :msg "Converted page sections to nodes"})
          {:page/index page-idx
           :page/nodes nodes}))
      page-groups))))

(defn markdown-file->pages
  "Converts a markdown file to page-based format.
   
   Params:
   `file-path` - String. Path to markdown file.
   
   Returns:
   Vector of page maps (see markdown->pages)."
  [file-path]
  (trove/log! {:level :info :data {:file file-path}
               :msg "Reading markdown file"})
  (let [content (slurp file-path)]
    (markdown->pages content)))

;; =============================================================================
;; Comment Block - Examples
;; =============================================================================

(comment
  ;; Example usage
  (def sample-md "# Introduction

This is the intro paragraph.

## Background

Some background info.

### Details

More details here.

## Methods

The methods section.

# Results

The results.

## Analysis

Analysis of results.")

  (parse-headings sample-md)
  ;; => [{:title "Introduction", :level 1, :line-num 1}
  ;;     {:title "Background", :level 2, :line-num 5}
  ;;     {:title "Details", :level 3, :line-num 9}
  ;;     {:title "Methods", :level 2, :line-num 13}
  ;;     {:title "Results", :level 1, :line-num 17}
  ;;     {:title "Analysis", :level 2, :line-num 21}]

  (def sections (extract-sections sample-md (parse-headings sample-md)))

  (markdown->pages sample-md)
  ;; Returns 2 pages: Introduction (with Background, Details, Methods) and Results (with Analysis)
  )
