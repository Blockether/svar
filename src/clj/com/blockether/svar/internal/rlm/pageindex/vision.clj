(ns com.blockether.svar.internal.rlm.pageindex.vision
  "Vision/LLM-based text extraction from documents.
   
   Provides:
   - `image->base64` - Convert BufferedImage to base64 PNG string
   - `extract-text-from-image` - Extract structured nodes from a single BufferedImage (vision)
   - `extract-text-from-pdf` - Extract structured nodes from all pages of a PDF (vision)
   - `extract-text-from-text-file` - Extract from text/markdown file (LLM, no image rendering)
   - `extract-text-from-image-file` - Extract from image file (vision)
   - `extract-text-from-string` - Extract from string content (LLM, no image rendering)
   - `infer-document-title` - Infer a document title from page content using LLM
   
   Configuration is passed explicitly via opts maps.
   Uses multimodal LLM for both image and text extraction.
   Parallel extraction using core.async channels for PDFs."
  (:require
   [clojure.core.async :as async]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.spec :as spec]
   [com.blockether.svar.internal.rlm.pageindex.pdf :as pdf]
   [taoensso.trove :as trove])
  (:import
   [java.awt Color Graphics2D]
   [java.awt.geom AffineTransform]
   [java.awt.image BufferedImage]
   [java.io ByteArrayOutputStream File]
   [java.util Base64]
   [javax.imageio ImageIO]))

;; =============================================================================
;; Default Configuration
;; =============================================================================

(def DEFAULT_VISION_MODEL
  "Default vision model for text extraction."
  "glm-4.6v")

(def DEFAULT_VISION_OBJECTIVE
  "Default system prompt for vision-based text extraction."
  "You are an expert document analyzer. Extract document content as typed nodes with hierarchical structure.

Your task is to parse the document into semantic nodes, preserving both reading order AND document hierarchy.
Use parent-id to link content to its parent section. This creates a tree structure from a flat list.

ABSOLUTE FIDELITY — DO NOT ADD ANYTHING:
- Extract ONLY what is physically present on the page. Never invent, infer, paraphrase, summarize, or complete missing content.
- Do NOT add words, sentences, headings, paragraphs, list items, table rows, or footnotes that do not literally appear on the page.
- Do NOT expand abbreviations, correct typos, normalize punctuation, or translate text.
- Do NOT merge, rephrase, or interpret what the author wrote — copy the exact wording and order.
- If content is cut off at a page boundary, set continuation=true and copy only the visible text as-is; do NOT guess the rest.
- If a field is not visible or not applicable, leave it null. Never fabricate values.
- Section/Image/Table `description` fields must describe ONLY what is shown — no speculation, no outside knowledge, no editorializing.

NODE TYPES:

Section - A logical grouping of content (created when you see a heading)
  - id: Unique identifier (1, 2, 3, etc.)
  - parent-id: ID of parent section (null for top-level sections)
  - description: Descriptive summary (2-3 sentences) explaining WHAT this section covers, 
    its main topics, key concepts, and why it matters. Be specific and informative.

Heading - The actual heading text (belongs to a Section)
  - id: Unique identifier
  - parent-id: ID of the Section this heading introduces
  - level: h1, h2, h3, h4, h5, h6
  - content: The heading text

Paragraph - Body text content
  - id: Unique identifier
  - parent-id: ID of the Section this paragraph belongs to
  - level: paragraph, citation, code, aside, abstract, footnote
  - content: The text content

ListItem - Bulleted or numbered list items
  - id: Unique identifier
  - parent-id: ID of the Section this list belongs to
  - level: l1 (top-level), l2 (nested), l3-l6 (deeper nesting)
  - content: The list item text

TocEntry - Table of contents entry (ONLY from actual TOC pages in the document)
  - id: Unique identifier
  - parent-id: ID of the Section this TOC belongs to (null if standalone TOC page)
  - title: The entry title text EXACTLY as written (e.g., 'Chapter 1 Introduction')
  - target-page: Page number shown next to the entry (null if not visible in document)
  - target-section-id: ALWAYS null (will be linked in post-processing)
  - level: l1 (top-level entry), l2 (sub-entry), l3-l6 (deeper nesting)
  CRITICAL: ONLY extract TocEntry from ACTUAL Table of Contents pages you SEE in the document.
  DO NOT infer or generate TOC entries. If there is no TOC page, do not create TocEntry nodes.

Image - ALL visual elements regardless of size (DESCRIPTION IS REQUIRED)
  - id: Unique identifier
  - parent-id: ID of the Section this image belongs to (null for standalone footer/header icons)
  - kind: photo, diagram, chart, logo, icon, illustration, screenshot, map, formula, signature, badge, unknown
  - image-index: Integer index matching an embedded image from the EMBEDDED IMAGES list (null if no match)
  - caption: Text from document caption (null if no caption present)
  - description: REQUIRED - YOUR description of what the image shows
  NOTE: Detect ALL images including tiny icons, license badges, social media icons in headers/footers

Table - Data tables (DESCRIPTION AND CONTENT ARE REQUIRED)
  - id: Unique identifier
  - parent-id: ID of the Section this table belongs to
  - kind: data, form, layout, comparison, schedule
  - image-index: Integer index if table is rendered as image (null for text-based tables)
  - caption: Text from document caption (null if no caption present)
  - description: REQUIRED - YOUR description of table content and structure
  - content: REQUIRED - Table data as ASCII art. Use | for columns and - for row separators.
    Reproduce ALL rows and columns exactly. Example:
    | Name    | Age | Role      |
    |---------|-----|-----------|
    | Alice   | 30  | Engineer  |
    | Bob     | 25  | Designer  |

Header - Page header (no parent-id)
  - id: Unique identifier
  - content: The header text

Footer - Page footer (no parent-id)
  - id: Unique identifier
  - content: The footer text

Metadata - Document metadata (no parent-id)
  - id: Unique identifier
  - content: Title, version, date, author, etc.

HIERARCHY RULES:
1. When you see a heading, create BOTH a Section AND a Heading node
2. The Heading's parent-id points to the Section it introduces
3. h1 heading -> Section with parent-id: null (top-level)
4. h2 heading -> Section with parent-id pointing to its parent h1 Section
5. h3 heading -> Section with parent-id pointing to its parent h2 Section (and so on)
6. Paragraphs, ListItems, Images, Tables, TocEntries have parent-id pointing to their containing Section
7. Content before the first heading has parent-id: null

CONTENT RULES:
1. Assign unique id to each node (1, 2, 3, etc.)
2. Preserve reading order (top-to-bottom, left-to-right)
3. Section description MUST be informative (2-3 sentences): explain WHAT the section covers, key topics, and why it matters
4. For Image/Table: description is REQUIRED - describe what YOU SEE
5. For Table: content is REQUIRED - reproduce ALL table data as ASCII art (| for columns, - for row separators)
6. For Image/Table: caption is ONLY the document's caption text (null if none)
7. For Image/Table: set image-index to match the corresponding embedded image from the EMBEDDED IMAGES list (by dimensions and visual content)
8. Set continuation=true if content continues from previous page
9. Keep text content exact - no interpretation or summarization
10. Detect ALL visual elements regardless of size - even tiny icons (20x20 pixels or smaller)
11. Include footer/header icons, license badges (Creative Commons, etc.), social media icons, decorative icons
12. Do NOT skip images based on size or perceived importance - capture everything visual
13. For TocEntry: ONLY extract from actual TOC pages - never infer. Set target-section-id to null always.

EXAMPLE STRUCTURE (flat array with parent-id references):
[
  {type:'Section', id:'1', parent-id:null, description:'Document table of contents providing navigation to all chapters and subsections. Lists major topics including introduction, methods, and results with corresponding page numbers.'},
  {type:'Heading', id:'2', parent-id:'1', level:'h1', content:'Contents'},
  {type:'TocEntry', id:'3', parent-id:'1', title:'Chapter 1 Introduction', target-page:1, target-section-id:null, level:'l1'},
  {type:'TocEntry', id:'4', parent-id:'1', title:'1.1 Background', target-page:3, target-section-id:null, level:'l2'},
  {type:'TocEntry', id:'5', parent-id:'1', title:'Chapter 2 Methods', target-page:10, target-section-id:null, level:'l1'},
  {type:'Section', id:'6', parent-id:null, description:'Introduction establishing the research context and motivation. Covers the problem statement, research objectives, and significance of the study. Provides essential background for understanding subsequent chapters.'},
  {type:'Heading', id:'7', parent-id:'6', level:'h1', content:'Chapter 1 Introduction'},
  {type:'Paragraph', id:'8', parent-id:'6', content:'Intro text...'},
  {type:'Image', id:'9', parent-id:'6', kind:'diagram', image-index:0, caption:null, description:'A flowchart showing the research methodology with four stages: data collection, preprocessing, analysis, and validation.'}
]

NOTE: TocEntry nodes are ONLY created when you see an actual Table of Contents page.
The target-section-id is ALWAYS null - linking happens in post-processing, not during extraction.")

;; =============================================================================
;; Image Conversion
;; =============================================================================

(defn image->base64
  "Converts a BufferedImage to a base64-encoded PNG string.
   
   Params:
   `image` - BufferedImage. The image to convert.
   
   Returns:
   String. Base64-encoded PNG data (without data:image/png;base64, prefix)."
  [^BufferedImage image]
  (let [baos (ByteArrayOutputStream.)]
    (ImageIO/write image "PNG" baos)
    (.encodeToString (Base64/getEncoder) (.toByteArray baos))))

(defn- rotate-image
  "Rotates a BufferedImage by the specified degrees clockwise.
   
   Uses Java AWT AffineTransform for rotation. Handles dimension swapping
   for 90° and 270° rotations (width/height are swapped).
   
   Params:
   `image` - BufferedImage. The image to rotate.
   `degrees` - Integer. Rotation in degrees clockwise (90, 180, or 270).
   
   Returns:
   BufferedImage. The rotated image, or the original if degrees is 0."
  [^BufferedImage image degrees]
  (case (int degrees)
    0 image
    (let [src-width (.getWidth image)
          src-height (.getHeight image)
          radians (Math/toRadians (double degrees))
          ;; For 90/270, width and height swap
          [dst-width dst-height] (if (or (= 90 degrees) (= 270 degrees))
                                   [src-height src-width]
                                   [src-width src-height])
          rotated (BufferedImage. dst-width dst-height BufferedImage/TYPE_INT_RGB)
          ^Graphics2D g2d (.createGraphics rotated)
          transform (AffineTransform.)]
      ;; Translate to center of destination, rotate, translate back from center of source
      (.translate transform (/ (double dst-width) 2.0) (/ (double dst-height) 2.0))
      (.rotate transform radians)
      (.translate transform (/ (double src-width) -2.0) (/ (double src-height) -2.0))
      ;; Fill background white (in case of rounding gaps)
      (.setColor g2d Color/WHITE)
      (.fillRect g2d 0 0 dst-width dst-height)
      (.setTransform g2d transform)
      (.drawImage g2d image 0 0 nil)
      (.dispose g2d)
      rotated)))

;; =============================================================================
;; Spec for Vision Response (Union Node Types with parent-id)
;; =============================================================================

;; Heading levels (used in Section's heading)
(def ^:private heading-level-values
  "Valid heading levels."
  {"h1" "Main heading / Document title"
   "h2" "Section heading"
   "h3" "Subsection heading"
   "h4" "Minor heading"
   "h5" "Sub-minor heading"
   "h6" "Smallest heading"})

;; List levels (nesting depth)
(def ^:private list-level-values
  "Valid list nesting levels."
  {"l1" "Top-level list item"
   "l2" "Nested list item (1 level deep)"
   "l3" "Nested list item (2 levels deep)"
   "l4" "Nested list item (3 levels deep)"
   "l5" "Nested list item (4 levels deep)"
   "l6" "Nested list item (5 levels deep)"})

;; Paragraph/text content levels
(def ^:private text-level-values
  "Valid text content levels."
  {"paragraph" "Regular body text paragraph"
   "citation" "Quoted or cited text block"
   "code" "Code block or preformatted text"
   "aside" "Sidebar, callout, note, or tip"
   "abstract" "Document abstract or summary"
   "footnote" "Footnote or endnote text"})

;; Image kind (what type of visual element)
(def ^:private image-kind-values
  "Valid image kinds describing what the visual element is."
  {"photo" "Photograph of real-world scene or object"
   "diagram" "Technical diagram, flowchart, architecture diagram"
   "chart" "Data visualization (bar chart, pie chart, line chart, etc.)"
   "logo" "Brand logo or company mark"
   "icon" "Small icon, UI element, or decorative symbol"
   "badge" "License badge, certification mark, or status indicator (e.g., Creative Commons)"
   "illustration" "Drawing, artwork, decorative image"
   "screenshot" "Screen capture"
   "map" "Geographic map"
   "formula" "Mathematical formula or equation"
   "signature" "Handwritten signature"
   "unknown" "Visual element of unknown or unclear type"})

;; Table kind
(def ^:private table-kind-values
  "Valid table kinds."
  {"data" "Table containing data (rows and columns of information)"
   "form" "Form or template table with fields to fill"
   "layout" "Table used for layout purposes"
   "comparison" "Comparison table between items"
   "schedule" "Schedule or timetable"})

;; =============================================================================
;; Top-Level Node Type Specs
;; =============================================================================

(def ^:private section-spec
  "Spec for section nodes. A section is a logical grouping of content.
   Other nodes reference sections via parent-id to establish hierarchy."
  (spec/spec
    :section
    {::spec/key-ns "page.node"}
    (spec/field {::spec/name :type
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Node type discriminator"
                 ::spec/values {"section" "Section node"}})
    (spec/field {::spec/name :id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Unique identifier (1, 2, 3, etc.)"})
    (spec/field {::spec/name :parent-id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "ID of parent section (null for top-level sections)"})
    (spec/field {::spec/name :description
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Brief summary of what this section contains"})))

(def ^:private heading-spec
  "Spec for heading nodes. Headings belong to sections via parent-id."
  (spec/spec
    :heading
    {::spec/key-ns "page.node"}
    (spec/field {::spec/name :type
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Node type discriminator"
                 ::spec/values {"heading" "Heading node"}})
    (spec/field {::spec/name :id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Unique identifier (1, 2, 3, etc.)"})
    (spec/field {::spec/name :parent-id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "ID of the section this heading belongs to"})
    (spec/field {::spec/name :level
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Heading level"
                 ::spec/values heading-level-values})
    (spec/field {::spec/name :content
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "The heading text"})))

(def ^:private paragraph-spec
  "Spec for paragraph/text content nodes."
  (spec/spec
    :paragraph
    {::spec/key-ns "page.node"}
    (spec/field {::spec/name :type
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Node type discriminator"
                 ::spec/values {"paragraph" "Paragraph node"}})
    (spec/field {::spec/name :id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Unique identifier (1, 2, 3, etc.)"})
    (spec/field {::spec/name :parent-id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "ID of parent section this paragraph belongs to"})
    (spec/field {::spec/name :level
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Content type"
                 ::spec/values text-level-values})
    (spec/field {::spec/name :content
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "The text content"})
    (spec/field {::spec/name :continuation?
                 ::spec/type :spec.type/bool
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "True if continues from previous page"})))

(def ^:private list-item-spec
  "Spec for list item nodes."
  (spec/spec
    :list-item
    {::spec/key-ns "page.node"}
    (spec/field {::spec/name :type
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Node type discriminator"
                 ::spec/values {"list-item" "List item node"}})
    (spec/field {::spec/name :id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Unique identifier (1, 2, 3, etc.)"})
    (spec/field {::spec/name :parent-id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "ID of parent section this list item belongs to"})
    (spec/field {::spec/name :level
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "List nesting level"
                 ::spec/values list-level-values})
    (spec/field {::spec/name :content
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "The list item text"})
    (spec/field {::spec/name :continuation?
                 ::spec/type :spec.type/bool
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "True if continues from previous page"})))

(def ^:private toc-entry-spec
  "Spec for table of contents entry nodes (document-level, not page-level)."
  (spec/spec
    :toc-entry
    {::spec/key-ns "document.toc"}
    (spec/field {::spec/name :type
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Node type discriminator"
                 ::spec/values {"toc-entry" "Table of contents entry node"}})
    (spec/field {::spec/name :id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Unique identifier (1, 2, 3, etc.)"})
    (spec/field {::spec/name :parent-id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "ID of parent section this TOC entry belongs to"})
    (spec/field {::spec/name :title
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "The TOC entry title text (e.g., 'Chapter 1 Introduction')"})
    (spec/field {::spec/name :description
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Brief summary of section contents (copied from linked Section in post-processing)"})
    (spec/field {::spec/name :target-page
                 ::spec/type :spec.type/int
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Page number this entry points to (null if not visible)"})
    (spec/field {::spec/name :target-section-id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "ID of the Section node this entry links to (if identifiable)"})
    (spec/field {::spec/name :level
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "TOC nesting level"
                 ::spec/values list-level-values})))

(def ^:private image-spec
  "Spec for image nodes. Description is REQUIRED, caption is optional."
  (spec/spec
    :image
    {::spec/key-ns "page.node"}
    (spec/field {::spec/name :type
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Node type discriminator"
                 ::spec/values {"image" "Image node"}})
    (spec/field {::spec/name :id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Unique identifier (1, 2, 3, etc.)"})
    (spec/field {::spec/name :parent-id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "ID of parent section this image belongs to"})
    (spec/field {::spec/name :kind
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "What kind of image this is"
                 ::spec/values image-kind-values})
    (spec/field {::spec/name :image-index
                 ::spec/type :spec.type/int
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Index into the embedded images list (0-based). Match to the EMBEDDED IMAGES list. null if no matching embedded image."})
    (spec/field {::spec/name :caption
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Caption from the document (null if no caption present)"})
    (spec/field {::spec/name :description
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "REQUIRED: AI description of what the image shows"})
    (spec/field {::spec/name :continuation?
                 ::spec/type :spec.type/bool
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "True if this image continues from previous page (e.g., truncated diagram, split figure)"})))

(def ^:private table-spec
  "Spec for table nodes. Description is REQUIRED, caption is optional."
  (spec/spec
    :table
    {::spec/key-ns "page.node"}
    (spec/field {::spec/name :type
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Node type discriminator"
                 ::spec/values {"table" "Table node"}})
    (spec/field {::spec/name :id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Unique identifier (1, 2, 3, etc.)"})
    (spec/field {::spec/name :parent-id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "ID of parent section this table belongs to"})
    (spec/field {::spec/name :kind
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "What kind of table this is"
                 ::spec/values table-kind-values})
    (spec/field {::spec/name :image-index
                 ::spec/type :spec.type/int
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Index into the embedded images list (0-based). Match to the EMBEDDED IMAGES list if the table is rendered as an image. null if text-based table."})
    (spec/field {::spec/name :caption
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Caption from the document (null if no caption present)"})
    (spec/field {::spec/name :description
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "REQUIRED: AI description of the table content and structure"})
    (spec/field {::spec/name :content
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "REQUIRED: Table data as ASCII art with pipe column separators and dash row separators. Reproduce ALL rows and columns exactly as shown in the document."})
    (spec/field {::spec/name :continuation?
                 ::spec/type :spec.type/bool
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "True if this table continues from previous page (e.g., 'Table X (cont.)')"})))

(def ^:private header-spec
  "Spec for page header nodes."
  (spec/spec
    :header
    {::spec/key-ns "page.node"}
    (spec/field {::spec/name :type
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Node type discriminator"
                 ::spec/values {"header" "Header node"}})
    (spec/field {::spec/name :id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Unique identifier (1, 2, 3, etc.)"})
    (spec/field {::spec/name :content
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "The header text"})))

(def ^:private footer-spec
  "Spec for page footer nodes."
  (spec/spec
    :footer
    {::spec/key-ns "page.node"}
    (spec/field {::spec/name :type
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Node type discriminator"
                 ::spec/values {"footer" "Footer node"}})
    (spec/field {::spec/name :id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Unique identifier (1, 2, 3, etc.)"})
    (spec/field {::spec/name :content
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "The footer text"})))

(def ^:private metadata-spec
  "Spec for document metadata nodes."
  (spec/spec
    :metadata
    {::spec/key-ns "page.node"}
    (spec/field {::spec/name :type
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Node type discriminator"
                 ::spec/values {"metadata" "Metadata node"}})
    (spec/field {::spec/name :id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Unique identifier (1, 2, 3, etc.)"})
    (spec/field {::spec/name :content
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "The metadata content (title, version, date, etc.)"})))

;; =============================================================================
;; Vision Response Spec with Union Type
;; =============================================================================

(def ^:private vision-response-spec
  "Spec for vision text extraction response.
   
   Contains an array of nodes in reading order. Each node can be one of:
   - section: Groups content under a heading, can have parent-id to another section
   - heading: Heading text, has parent-id to its section
   - paragraph: Text content, has parent-id to its section
   - list-item: List item, has parent-id to its section
   - toc-entry: Table of contents entry with title, target-page, target-section-id, level
   - image: Visual element with REQUIRED description, optional caption, has parent-id
   - table: Data table with REQUIRED description, optional caption, has parent-id
   - header: Page header (no parent-id)
   - footer: Page footer (no parent-id)
   - metadata: Document metadata (no parent-id)
   
   Hierarchy is established via parent-id references, not nesting."
  (spec/spec
    {:refs [section-spec heading-spec paragraph-spec list-item-spec toc-entry-spec
            image-spec table-spec header-spec footer-spec metadata-spec]}
    (spec/field {::spec/name :nodes
                 ::spec/type :spec.type/ref
                 ::spec/target [:section :heading :paragraph :list-item :toc-entry
                                :image :table :header :footer :metadata]
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "Document nodes in reading order (top to bottom, left to right)"})))

;; =============================================================================
;; Image Processing
;; =============================================================================

(def ^:private DEFAULT_VISION_TIMEOUT_MS
  "Default timeout for vision LLM requests (6 minutes per image).
   Vision models processing images can take longer than text-only requests."
  360000)

(defn- visual-node?
  "Checks if a node is a visual node (Image or Table) by presence of :page.node/kind field."
  [node]
  (some? (:page.node/kind node)))

(defn- enrich-visual-nodes
  "Enriches visual nodes (images/tables) with extracted image bytes from PDFBox.

   Visual nodes are identified by having :page.node/kind field (Image and Table types).
   Each visual node may have :page.node/image-index pointing to a PDFBox-extracted image.

   Params:
   `nodes` - Vector of all node maps.
   `pdf-images` - Vector of {:bytes byte[] :width int :height int} from PDFBox extraction.
   `page-index` - Integer. The page index (for logging).

   Returns:
   Vector of nodes with :page.node/image-data added for visual elements with valid image-index."
  [nodes pdf-images page-index]
  (mapv (fn [node]
          (if-let [img-idx (:page.node/image-index node)]
            (if-let [img (get pdf-images img-idx)]
              (do
                (trove/log! {:level :debug
                             :data {:page page-index :image-index img-idx
                                    :width (:width img) :height (:height img)}
                             :msg "Attached PDFBox image to node"})
                (assoc node :page.node/image-data (:bytes img)))
              (do
                (trove/log! {:level :warn
                             :data {:page page-index :image-index img-idx
                                    :available (count pdf-images)}
                             :msg "Image index out of range — no PDFBox image available"})
                node))
            node))
    nodes))

;; =============================================================================
;; Quality Refinement — Eval + Refine Extracted Pages
;; =============================================================================

(def ^:private DEFAULT_REFINE_SAMPLE_SIZE
  "Default number of pages to sample for quality evaluation."
  3)

(def ^:private DEFAULT_REFINE_THRESHOLD
  "Default minimum eval score to pass quality check."
  0.8)

(def ^:private DEFAULT_REFINE_ITERATIONS
  "Default maximum refinement iterations per page."
  1)

(def ^:private PAGE_EVAL_CRITERIA
  "Evaluation criteria for document page extraction quality."
  {:completeness "Does the extraction capture all expected content elements for a document page? Are there enough nodes for the visible content?"
   :structure "Are nodes properly typed (Section, Heading, Paragraph, ListItem, Image, Table) and hierarchically organized with correct parent-id references?"
   :descriptions "Are section descriptions meaningful, specific, and informative 2-3 sentence summaries?"})

(defn- serialize-page-for-eval
  "Serializes page nodes into human-readable text for eval!.
   
   Converts each node to a bracketed type + content line. Used as the
   :output argument to llm/eval! for quality assessment.
   
   Params:
   `page` - Map with :page/nodes.
   
   Returns:
   String. One line per node."
  [page]
  (->> (:page/nodes page)
    (map (fn [node]
           (let [ntype (:page.node/type node)
                 content (:page.node/content node)
                 desc (:page.node/description node)]
             (case ntype
               :section (str "[Section] " desc)
               :heading (str "[Heading " (:page.node/level node) "] " content)
               :paragraph (str "[Paragraph] " (when content (subs content 0 (min 200 (count content)))))
               :list-item (str "[ListItem] " content)
               :image (str "[Image: " (:page.node/kind node) "] " desc)
               :table (str "[Table: " (:page.node/kind node) "] " desc)
               :header (str "[Header] " content)
               :footer (str "[Footer] " content)
               :metadata (str "[Metadata] " content)
               :toc-entry (str "[TOC] " (:document.toc/title node))
               (str "[" (when ntype (name ntype)) "] " (or content desc ""))))))
    (str/join "\n")))

(defn- eval-page-extraction
  "Evaluates quality of a page's node extraction using llm/eval!.
   
   Serializes nodes to text and asks the eval model to assess completeness,
   structure, and description quality.
   
   Params:
   `page` - Map with :page/index and :page/nodes.
   `opts` - Map with :refine-model and :config.
   
   Returns:
   Map with :page-index, :score, :correct?, :summary, :issues."
  [page {:keys [rlm-router]}]
  (let [serialized (serialize-page-for-eval page)
        page-index (:page/index page)]
    (trove/log! {:level :info :data {:page page-index}
                 :msg "Evaluating page extraction quality"})
    (let [result (llm/eval! rlm-router {:task (str "Extract all visible content from document page " page-index
                                                " into structured typed nodes (Section, Heading, Paragraph, ListItem, "
                                                "Image, Table) with correct parent-id hierarchy. Every piece of visible "
                                                "text should be captured. Section descriptions should be meaningful "
                                                "2-3 sentence summaries.")
                                        :output serialized
                                        :strategy :root
                                        :criteria PAGE_EVAL_CRITERIA})]
      (trove/log! {:level :info :data {:page page-index
                                       :score (:overall-score result)
                                       :correct? (:correct? result)}
                   :msg "Page eval complete"})
      {:page-index page-index
       :score (:overall-score result)
       :correct? (:correct? result)
       :summary (:summary result)
       :issues (:issues result)})))

(defn- refine-page-image
  "Re-extracts a page image using llm/refine! for higher quality.
   
   Builds the same messages as extract-text-from-image but uses refine!
   (decompose -> verify -> refine loop) instead of ask!.
   
   Params:
   `image` - BufferedImage. The page image.
   `page-index` - Integer. Page index (0-based).
   `opts` - Map with:
     :refine-model - String. Model for refinement.
     :objective - String. System prompt.
     :config - Map. LLM config.
     :refine-iterations - Integer. Max iterations.
     :refine-threshold - Float. Quality threshold.
   
   Returns:
   Map with :page/index and :page/nodes (enriched with image data)."
  [^BufferedImage image page-index {:keys [rlm-router objective pdf-images
                                           refine-iterations refine-threshold]
                                    :or {refine-iterations DEFAULT_REFINE_ITERATIONS
                                         refine-threshold DEFAULT_REFINE_THRESHOLD}}]
  (let [page-pdf-images (or pdf-images [])]
    (trove/log! {:level :info :data {:page page-index
                                     :iterations refine-iterations :threshold refine-threshold}
                 :msg "Refining page extraction (image)"})
    (let [base64-image (image->base64 image)
          embedded-images-hint (if (seq page-pdf-images)
                                 (format "\n\nEMBEDDED IMAGES: This page has %d embedded images (indexed 0-%d). For Image and Table nodes that correspond to an embedded image, set image-index to the matching index. Available images:\n%s"
                                   (count page-pdf-images)
                                   (dec (count page-pdf-images))
                                   (clojure.string/join "\n" (map-indexed (fn [i img]
                                                                            (format "  [%d] %dx%d pixels" i (:width img) (:height img)))
                                                               page-pdf-images)))
                                 "")
          task (format "Extract all content from this document page as typed nodes with parent-id hierarchy. Create Section nodes for headings, and link content to sections via parent-id. For Image and Table nodes, description is REQUIRED.%s"
                 embedded-images-hint)
          refine-result (llm/refine! rlm-router {:spec vision-response-spec
                                                 :messages [(llm/system (or objective DEFAULT_VISION_OBJECTIVE))
                                                            (llm/user task (llm/image base64-image "image/png"))]
                                                 :strategy :root
                                                 :iterations refine-iterations
                                                 :threshold refine-threshold
                                                 :criteria PAGE_EVAL_CRITERIA})
          raw-nodes (get-in refine-result [:result :nodes] [])
          nodes (enrich-visual-nodes raw-nodes page-pdf-images page-index)]
      (trove/log! {:level :info :data {:page page-index
                                       :nodes (count nodes)
                                       :final-score (:final-score refine-result)
                                       :converged? (:converged? refine-result)
                                       :iterations (:iterations-count refine-result)}
                   :msg "Page refinement complete"})
      {:page/index page-index
       :page/nodes nodes})))

(defn- refine-page-text
  "Re-extracts text content using llm/refine! for higher quality.
   
   Params:
   `content` - String. Text/markdown content.
   `page-index` - Integer. Page index (0-based).
   `opts` - Map with :refine-model, :objective, :config, :refine-iterations, :refine-threshold.
   
   Returns:
   Map with :page/index and :page/nodes."
  [content page-index {:keys [rlm-router objective refine-iterations refine-threshold]
                       :or {refine-iterations DEFAULT_REFINE_ITERATIONS
                            refine-threshold DEFAULT_REFINE_THRESHOLD}}]
  (trove/log! {:level :info :data {:page page-index
                                   :content-length (count content)}
               :msg "Refining page extraction (text)"})
  (let [refine-result (llm/refine! rlm-router {:spec vision-response-spec
                                               :messages [(llm/system (or objective DEFAULT_VISION_OBJECTIVE))
                                                          (llm/user (str "Extract all content from this document text as typed nodes with parent-id hierarchy. "
                                                                      "Create Section nodes for headings, and link content to sections via parent-id.\n\n"
                                                                      "<document_content>\n" content "\n</document_content>"))]
                                               :strategy :root
                                               :iterations refine-iterations
                                               :threshold refine-threshold
                                               :criteria PAGE_EVAL_CRITERIA})
        nodes (get-in refine-result [:result :nodes] [])]
    (trove/log! {:level :info :data {:page page-index
                                     :nodes (count nodes)
                                     :final-score (:final-score refine-result)
                                     :converged? (:converged? refine-result)}
                 :msg "Text refinement complete"})
    {:page/index page-index
     :page/nodes nodes}))

(defn- sample-pages
  "Selects page indices for quality evaluation.
   
   Strategy: first page, last page, and random middle pages up to sample-size.
   
   Params:
   `page-count` - Integer. Total number of pages.
   `sample-size` - Integer. Maximum pages to sample.
   
   Returns:
   Sorted vector of page indices."
  [page-count sample-size]
  (cond
    (<= page-count sample-size) (vec (range page-count))
    (= sample-size 1) [0]
    (= sample-size 2) [0 (dec page-count)]
    :else (let [middle-count (- (long sample-size) 2)
                middle-range (range 1 (dec page-count))
                middle-picks (take middle-count (shuffle middle-range))]
            (vec (sort (into #{0 (dec page-count)} middle-picks))))))

(defn- quality-pass-pdf
  "Post-extraction quality pass for PDF pages.
   
   Samples pages, evaluates extraction quality with eval!, and re-extracts
   pages that fall below the quality threshold using refine!.
   
   Both eval and refine stages run in parallel when :parallel-refine > 1.
   
   Params:
   `pages` - Vector of extracted page maps.
   `images` - Vector of BufferedImages (original, unrotated from pdf->images).
   `page-rotations` - Vector of rotation degrees per page (from heuristic detection).
   `opts` - Map with refine configuration keys:
     :parallel-refine - Integer. Max concurrent eval/refine operations (default: 2).
     :refine-threshold - Float. Min eval score to pass.
     :refine-sample-size - Integer. Pages to sample for eval.
   
   Returns:
   Vector of pages (with low-quality pages replaced by refined versions)."
  [pages images page-rotations opts]
  (let [{:keys [refine-threshold refine-sample-size parallel-refine]
         :or {refine-threshold DEFAULT_REFINE_THRESHOLD
              refine-sample-size DEFAULT_REFINE_SAMPLE_SIZE
              parallel-refine 2}} opts
        sample-indices (sample-pages (count pages) refine-sample-size)]
    (trove/log! {:level :info :data {:total-pages (count pages)
                                     :sample-size (count sample-indices)
                                     :sampled-indices (vec sample-indices)
                                     :threshold refine-threshold
                                     :parallel-refine parallel-refine}
                 :msg "Starting quality pass on sampled pages"})
    ;; Parallel eval: evaluate sampled pages concurrently
    (let [eval-results (if (<= (long parallel-refine) 1)
                         (mapv (fn [idx]
                                 (eval-page-extraction (nth pages idx) opts))
                           sample-indices)
                         (let [in-ch (async/chan (count sample-indices))
                               out-ch (async/chan (count sample-indices))]
                           (async/onto-chan! in-ch (vec sample-indices))
                           (async/pipeline-blocking
                             parallel-refine
                             out-ch
                             (map (fn [idx]
                                    (eval-page-extraction (nth pages idx) opts)))
                             in-ch)
                           (loop [acc []]
                             (if-let [result (async/<!! out-ch)]
                               (recur (conj acc result))
                               acc))))
          bad-pages (filterv #(< (double (:score %)) (double refine-threshold)) eval-results)
          bad-indices (set (map :page-index bad-pages))]
      (trove/log! {:level :info :data {:evaluated (count eval-results)
                                       :below-threshold (count bad-pages)
                                       :bad-indices (vec bad-indices)
                                       :scores (mapv (fn [r] {:page (:page-index r) :score (:score r)}) eval-results)}
                   :msg "Quality evaluation complete"})
      (if (empty? bad-pages)
        (do
          (trove/log! {:level :info :msg "All sampled pages passed quality threshold"})
          pages)
        (do
          (trove/log! {:level :info :data {:refining (count bad-pages)}
                       :msg "Refining pages below quality threshold"})
          ;; Parallel refine: re-extract bad pages concurrently
          (if (<= (long parallel-refine) 1)
            (mapv (fn [page]
                    (if (contains? bad-indices (:page/index page))
                      (let [idx (:page/index page)
                            image (nth images idx)
                            rotation (get page-rotations idx 0)
                            rotated-image (if (pos? (long rotation))
                                            (rotate-image image rotation)
                                            image)]
                        (refine-page-image rotated-image idx opts))
                      page))
              pages)
            ;; Parallel: only refine bad pages, preserve good ones in-place
            (let [bad-page-inputs (filterv #(contains? bad-indices (:page/index %)) pages)
                  in-ch (async/chan (count bad-page-inputs))
                  out-ch (async/chan (count bad-page-inputs))]
              (async/onto-chan! in-ch bad-page-inputs)
              (async/pipeline-blocking
                parallel-refine
                out-ch
                (map (fn [page]
                       (let [idx (:page/index page)
                             image (nth images idx)
                             rotation (get page-rotations idx 0)
                             rotated-image (if (pos? (long rotation))
                                             (rotate-image image rotation)
                                             image)]
                         (refine-page-image rotated-image idx opts))))
                in-ch)
              (let [refined-map (loop [m {}]
                                  (if-let [result (async/<!! out-ch)]
                                    (recur (assoc m (:page/index result) result))
                                    m))]
                (mapv (fn [page]
                        (get refined-map (:page/index page) page))
                  pages)))))))))

(defn- quality-pass-single
  "Post-extraction quality pass for single-page extractors.
   
   Evaluates the extraction and refines if below threshold.
   
   Params:
   `pages` - Vector with single page map.
   `refine-fn` - Function. (fn [page-index opts] -> refined page). Called if below threshold.
   `opts` - Map with refine configuration.
   
   Returns:
   Vector with single page (original or refined)."
  [pages refine-fn opts]
  (let [{:keys [refine-threshold]
         :or {refine-threshold DEFAULT_REFINE_THRESHOLD}} opts
        page (first pages)
        eval-result (eval-page-extraction page opts)]
    (trove/log! {:level :info :data {:score (:score eval-result)
                                     :threshold refine-threshold}
                 :msg "Single page quality evaluation"})
    (if (>= (double (:score eval-result)) (double refine-threshold))
      (do
        (trove/log! {:level :info :msg "Page passed quality threshold"})
        pages)
      (do
        (trove/log! {:level :info :msg "Page below threshold, refining"})
        [(refine-fn 0 opts)]))))

(defn extract-text-from-image
  "Extracts document content from a BufferedImage using vision LLM.
   
   Uses typed node structure with parent-id references for hierarchy.
   Sections are logical groupings with AI-generated descriptions.
   Headings are separate nodes that belong to their Section.
   
     Params:
     `image` - BufferedImage. The image to extract from.
     `page-index` - Integer. The page index (0-based).
     `opts` - Map with:
       `:rlm-router` - Router instance for LLM calls.
       `:objective` - String. System prompt for OCR.
       `:timeout-ms` - Integer, optional. HTTP timeout (default: 360000ms / 6 min)."
  [^BufferedImage image page-index {:keys [rlm-router objective timeout-ms pdf-images]
                                    :or {timeout-ms DEFAULT_VISION_TIMEOUT_MS}}]
  (let [img-width (.getWidth image)
        img-height (.getHeight image)
        page-pdf-images (or pdf-images [])]
    (trove/log! {:level :info :data {:page page-index :timeout-ms timeout-ms
                                     :image-width img-width :image-height img-height
                                     :embedded-images (count page-pdf-images)}
                 :msg "Extracting content from page"})
    (let [base64-image (image->base64 image)
          embedded-images-hint (if (seq page-pdf-images)
                                 (format "\n\nEMBEDDED IMAGES: This page has %d embedded images (indexed 0-%d). For Image and Table nodes that correspond to an embedded image, set image-index to the matching index. Available images:\n%s"
                                   (count page-pdf-images)
                                   (dec (count page-pdf-images))
                                   (clojure.string/join "\n" (map-indexed (fn [i img]
                                                                            (format "  [%d] %dx%d pixels" i (:width img) (:height img)))
                                                               page-pdf-images)))
                                 "")
          task (format "Extract all content from this document page as typed nodes with parent-id hierarchy. Create Section nodes for headings, and link content to sections via parent-id. For Image and Table nodes, description is REQUIRED.%s"
                 embedded-images-hint)
          response (llm/ask! rlm-router {:spec vision-response-spec
                                         :messages [(llm/system objective)
                                                    (llm/user task (llm/image base64-image "image/png"))]
                                         :check-context? false
                                         :timeout-ms timeout-ms})
          raw-nodes (get-in response [:result :nodes] [])
          ;; Enrich visual nodes with PDFBox-extracted images by index
          nodes (enrich-visual-nodes raw-nodes page-pdf-images page-index)
        ;; Count elements for logging
          section-count (count (filter :page.node/description nodes))
          heading-count (count (filter :page.node/level nodes))
          visual-nodes (filter visual-node? nodes)
          image-count (count (filter #(contains? image-kind-values (:page.node/kind %)) visual-nodes))
          table-count (count (filter #(contains? table-kind-values (:page.node/kind %)) visual-nodes))]
      (trove/log! {:level :debug :data {:page page-index
                                        :nodes-count (count nodes)
                                        :sections section-count
                                        :headings heading-count
                                        :images image-count
                                        :tables table-count}
                   :msg "Page extraction complete"})
      {:page/index page-index
       :page/nodes nodes})))

(defn extract-text-from-pdf
  "Extracts document content from all pages of a PDF file using vision LLM.
   
   Uses node-based document structure extraction. Each page contains a vector of
   semantic nodes (headings, paragraphs, images, tables, etc.).
   
     Params:
     `pdf-path` - String. Path to the PDF file.
     `opts` - Map with:
       `:model` - String. Vision model to use.
       `:objective` - String. System prompt for OCR.
       `:config` - Map. LLM config with :api-key, :base-url (from llm-config-component).
       `:parallel` - Integer. Max concurrent extractions (default: 4).
       `:timeout-ms` - Integer, optional. HTTP timeout per page (default: 180000ms / 3 min).
       `:page-set` - Set of 0-indexed page numbers to extract, or nil for all pages.
                     When provided, only pages in the set are sent to the vision LLM.
   
   Returns:
   Vector of maps, one per page:
     `:page/index` - Integer. The page number (0-based).
     `:page/nodes` - Vector of document nodes (see extract-text-from-image for node structure).
   
   Throws:
   Anomaly (fault) if any page fails to extract."
  [pdf-path {:keys [rlm-router objective parallel timeout-ms refine? page-set]
             :or {parallel 3 timeout-ms DEFAULT_VISION_TIMEOUT_MS}
             :as opts}]
  (trove/log! {:level :info :data {:pdf pdf-path :parallel parallel :timeout-ms timeout-ms}
               :msg "Starting PDF text extraction"})
  (let [pdf-page-opts (when page-set {:page-set page-set})
        ;; Only render selected pages (skips CPU-expensive image rendering for excluded pages)
        images (pdf/pdf->images pdf-path (merge {} pdf-page-opts))
        page-count (count images)
        ;; Detect text rotation only for selected pages (no LLM needed)
        page-rotations (try
                         (pdf/detect-text-rotation pdf-path (or pdf-page-opts {}))
                         (catch Exception e
                           (trove/log! {:level :warn
                                        :data {:pdf pdf-path :error (ex-message e)}
                                        :msg "Text rotation detection failed, assuming no rotation"})
                           (vec (repeat page-count 0))))
        ;; Build the page index mapping: images are returned in order of sorted page-set,
        ;; so we need to map them back to their original 0-indexed page numbers.
        page-indices (if page-set
                       (let [total (pdf/page-count pdf-path)]
                         (filterv #(< % total) (sort page-set)))
                       (vec (range page-count)))]
    (when page-set
      (trove/log! {:level :info :data {:selected-pages page-count :page-set page-set}
                   :msg "Rendering only selected pages"}))
    (trove/log! {:level :info :data {:pages page-count :rotations page-rotations}
                 :msg "PDF loaded, extracting text"})

    ;; Handle empty case (no pages or empty page-set)
    (if (zero? page-count)
      (do
        (trove/log! {:level :warn :data {:pdf pdf-path} :msg "PDF has no pages to extract"})
        [])

      ;; Extract embedded images via PDFBox (instant, no LLM needed)
      (let [all-pdf-images (try
                             (pdf/extract-page-images pdf-path (or pdf-page-opts {}))
                             (catch Exception e
                               (trove/log! {:level :warn
                                            :data {:pdf pdf-path :error (ex-message e)}
                                            :msg "PDFBox image extraction failed, continuing without embedded images"})
                               {}))
            _ (trove/log! {:level :info
                           :data {:total-images (reduce + 0 (map count (vals all-pdf-images)))}
                           :msg "Extracted embedded images via PDFBox"})
            ;; Create work items with original page indices and pre-computed rotation
            work-items (map (fn [img page-idx rotation]
                              {:index page-idx
                               :image img
                               :rotation rotation
                               :pdf-images (get all-pdf-images page-idx [])})
                         images page-indices page-rotations)
            result-chan (async/chan (max 1 page-count))
            extract-opts {:rlm-router rlm-router :objective objective :timeout-ms timeout-ms}]

        ;; Start parallel workers - capture errors as data since pipeline-blocking catches exceptions
        ;; Page rotation is detected via PDFBox text position heuristics (no LLM cost).
        ;; This catches landscape content on portrait pages that PDFBox /Rotate misses.
        (async/pipeline-blocking
          parallel
          result-chan
          (map (fn [{:keys [index image rotation pdf-images]}]
                 (try
                  ;; Step 1: Apply rotation correction if needed (heuristic-detected)
                   (let [image (if (pos? rotation)
                                 (do
                                   (trove/log! {:level :info
                                                :data {:page index :rotation rotation}
                                                :msg "Correcting page rotation (heuristic)"})
                                   (rotate-image image rotation))
                                 image)]
                    ;; Step 2: Extract content from (possibly corrected) image
                     (extract-text-from-image image index (assoc extract-opts :pdf-images pdf-images)))
                   (catch Exception e
                     (let [^java.awt.image.BufferedImage image image
                           ex-data-map (ex-data e)
                           ;; Extract response body for HTTP errors (400, 500, etc.)
                           response-body (:body ex-data-map)
                           status (:status ex-data-map)
                           ;; Extract full LLM request from exception (includes sanitized messages)
                           llm-request (:llm-request ex-data-map)
                           ;; Build basic request info as fallback
                           basic-info {:timeout-ms timeout-ms
                                       :objective-length (count objective)
                                       :image-width (.getWidth image)
                                       :image-height (.getHeight image)}
                           ;; Use full LLM request if available, otherwise basic info
                           request-info (if llm-request
                                          (assoc llm-request
                                            :image-width (.getWidth image)
                                            :image-height (.getHeight image))
                                          basic-info)]
                       (trove/log! {:level :error
                                    :data (cond-> {:page index
                                                   :error (ex-message e)
                                                   :request request-info}
                                            status (assoc :status status)
                                            response-body (assoc :response-body response-body))
                                    :msg "Failed to extract text from page"})
                       ;; Return error as data - we'll check after collection
                       {:page/index index
                        :extraction-error (cond-> {:page index
                                                   :message (ex-message e)
                                                   :type (type e)
                                                   :request request-info}
                                            status (assoc :status status)
                                            response-body (assoc :response-body response-body))})))))
          (async/to-chan! work-items))

        ;; Collect results and sort by page number
        (let [results (loop [acc []]
                        (if-let [result (async/<!! result-chan)]
                          (recur (conj acc result))
                          acc))
              sorted-results (vec (sort-by :page/index results))
              ;; Check for any extraction errors
              errors (filter :extraction-error sorted-results)]

          ;; If any page failed, throw exception with details
          (when (seq errors)
            (let [first-error (:extraction-error (first errors))]
              (anomaly/fault! "PDF page extraction failed"
                {:type :svar.vision/pdf-extraction-failed
                 :pdf-path pdf-path
                 :failed-page (:page first-error)
                 :error-message (:message first-error)
                 :total-errors (count errors)
                 :all-errors (mapv :extraction-error errors)})))

          ;; Quality pass: eval sampled pages, refine those below threshold
          (if refine?
            (quality-pass-pdf sorted-results images page-rotations opts)
            sorted-results))))))

;; =============================================================================
;; Text-Based Extraction (No Images - Direct LLM Text Processing)
;; =============================================================================

(defn- extract-nodes-from-text
  "Extracts document nodes from text content using LLM.
   
   Sends text directly to the multimodal LLM (no image rendering needed).
   
   Params:
   `content` - String. Text/markdown content.
   `page-index` - Integer. Page index (0-based).
   `opts` - Map with :model, :objective, :config, :timeout-ms.
   
   Returns:
   Map with :page/index and :page/nodes."
  [content page-index {:keys [rlm-router objective timeout-ms]
                       :or {timeout-ms DEFAULT_VISION_TIMEOUT_MS}}]
  (trove/log! {:level :info :data {:page page-index :content-length (count content)}
               :msg "Extracting nodes from text content"})
  (let [response (llm/ask! rlm-router {:spec vision-response-spec
                                       :messages [(llm/system objective)
                                                  (llm/user (str "Extract all content from this document text as typed nodes with parent-id hierarchy. "
                                                              "Create Section nodes for headings, and link content to sections via parent-id.\n\n"
                                                              "<document_content>\n" content "\n</document_content>"))]
                                       :check-context? false
                                       :timeout-ms timeout-ms})
        nodes (get-in response [:result :nodes] [])
        section-count (count (filter :page.node/description nodes))
        heading-count (count (filter :page.node/level nodes))]
    (trove/log! {:level :debug :data {:page page-index
                                      :nodes-count (count nodes)
                                      :sections section-count
                                      :headings heading-count}
                 :msg "Text extraction complete"})
    {:page/index page-index
     :page/nodes nodes}))

;; =============================================================================
;; Image File Loading
;; =============================================================================

(defn- load-image-file
  "Loads an image file and returns a BufferedImage.
   
   Params:
   `file-path` - String. Path to image file.
   
   Returns:
   BufferedImage.
   
   Throws:
   ex-info if file not found or cannot be read."
  [file-path]
  (let [file (File. ^String file-path)]
    (when-not (.exists file)
      (anomaly/not-found! "Image file not found" {:type :svar.vision/image-not-found :path file-path}))
    (let [img (ImageIO/read file)]
      (when-not img
        (anomaly/fault! "Failed to read image file" {:type :svar.vision/image-read-failed :path file-path}))
      img)))

;; =============================================================================
;; Public Extraction Functions
;; =============================================================================

(defn extract-text-from-text-file
  "Extracts document content from a text or markdown file using LLM.
   
   Sends text directly to the multimodal LLM (no image rendering).
   When :refine? is true, evaluates extraction quality and refines if below threshold.
   
   Params:
   `file-path` - String. Path to the text/markdown file.
   `opts` - Map with:
     `:model` - String. LLM model to use.
     `:objective` - String. System prompt for extraction.
     `:config` - Map. LLM config with :api-key, :base-url.
     `:timeout-ms` - Integer, optional. HTTP timeout.
     `:refine?` - Boolean, optional. Enable quality refinement.
     `:refine-model` - String, optional. Model for eval/refine (default: gpt-4o).
   
   Returns:
   Vector with single map:
     `:page/index` - Integer. Always 0.
     `:page/nodes` - Vector of document nodes."
  [file-path {:keys [rlm-router refine?] :as opts}]
  (let [file (File. ^String file-path)]
    (when-not (.exists file)
      (anomaly/not-found! "File not found" {:type :svar.vision/file-not-found :path file-path}))
    (trove/log! {:level :info :data {:file file-path :has-router (some? rlm-router)}
                 :msg "Extracting content from text file"})
    (let [content (slurp file)
          result (extract-nodes-from-text content 0 opts)
          pages [result]]
      (if refine?
        (quality-pass-single pages
          (fn [idx refine-opts] (refine-page-text content idx refine-opts))
          opts)
        pages))))

(defn extract-text-from-image-file
  "Extracts document content from an image file using vision LLM.
   
   When :refine? is true, evaluates extraction quality and refines if below threshold.
   
   Params:
   `file-path` - String. Path to the image file (.png, .jpg, etc.).
   `opts` - Map with:
     `:model` - String. Vision model to use.
     `:objective` - String. System prompt for OCR.
     `:config` - Map. LLM config with :api-key, :base-url.
     `:timeout-ms` - Integer, optional. HTTP timeout.
     `:refine?` - Boolean, optional. Enable quality refinement.
     `:refine-model` - String, optional. Model for eval/refine (default: gpt-4o).
   
   Returns:
   Vector with single map:
     `:page/index` - Integer. Always 0.
     `:page/nodes` - Vector of document nodes."
  [file-path {:keys [rlm-router refine?] :as opts}]
  (trove/log! {:level :info :data {:file file-path :has-router (some? rlm-router)}
               :msg "Extracting text from image file"})
  (let [image (load-image-file file-path)
        result (extract-text-from-image image 0 opts)
        pages [result]]
    (if refine?
      (quality-pass-single pages
        (fn [idx refine-opts] (refine-page-image image idx refine-opts))
        opts)
      pages)))

(defn extract-text-from-string
  "Extracts document content from string content using LLM.
   
   Sends text directly to the multimodal LLM (no image rendering).
   When :refine? is true, evaluates extraction quality and refines if below threshold.
   
   Params:
   `content` - String. Text/markdown content to extract from.
   `opts` - Map with:
     `:model` - String. LLM model to use.
     `:objective` - String. System prompt for extraction.
     `:config` - Map. LLM config with :api-key, :base-url.
     `:timeout-ms` - Integer, optional. HTTP timeout.
     `:refine?` - Boolean, optional. Enable quality refinement.
     `:refine-model` - String, optional. Model for eval/refine (default: gpt-4o).
   
   Returns:
   Vector with single map:
     `:page/index` - Integer. Always 0.
     `:page/nodes` - Vector of document nodes."
  [content {:keys [rlm-router refine?] :as opts}]
  (trove/log! {:level :info :data {:content-length (count content) :has-router (some? rlm-router)}
               :msg "Extracting content from string"})
  (let [result (extract-nodes-from-text content 0 opts)
        pages [result]]
    (if refine?
      (quality-pass-single pages
        (fn [idx refine-opts] (refine-page-text content idx refine-opts))
        opts)
      pages)))

;; =============================================================================
;; Title Inference
;; =============================================================================

(def ^:private title-inference-spec
  "Spec for document title inference response."
  (spec/spec
    (spec/field {::spec/name :title
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "The inferred document title"})))

(defn infer-document-title
  "Infers document title from extracted content using LLM.
   
   Analyzes the document structure (headings, metadata, first paragraphs)
   to determine the most appropriate title.
   
   Params:
   `pages` - Vector of page maps with :page/nodes.
   `opts` - Map with:
     `:model` - String. LLM model to use.
     `:config` - Map. LLM config with :api-key, :base-url.
     `:timeout-ms` - Integer, optional. HTTP timeout (default: 30000ms).
   
   Returns:
   String. The inferred document title, or nil if cannot be inferred."
  [pages {:keys [rlm-router text-model timeout-ms]
          :or {timeout-ms 30000}}]
  (let [;; Collect relevant content for title inference
        all-nodes (mapcat :page/nodes pages)
        ;; Get first few headings
        headings (->> all-nodes
                   (filter #(= :heading (:page.node/type %)))
                   (take 5)
                   (map :page.node/content))
        ;; Get first few section descriptions  
        sections (->> all-nodes
                   (filter #(= :section (:page.node/type %)))
                   (take 5)
                   (map :page.node/description))
        ;; Get metadata nodes
        metadata (->> all-nodes
                   (filter #(= :metadata (:page.node/type %)))
                   (map :page.node/content))
        ;; Get first paragraph
        first-para (->> all-nodes
                     (filter #(and (= :paragraph (:page.node/type %))
                                (= "paragraph" (:page.node/level %))))
                     first
                     :page.node/content)
        ;; Build context for LLM
        context (str "Document headings:\n"
                  (str/join "\n" (map #(str "- " %) headings))
                  "\n\nSection summaries:\n"
                  (str/join "\n" (map #(str "- " %) (remove nil? sections)))
                  (when (seq metadata)
                    (str "\n\nMetadata:\n" (str/join "\n" metadata)))
                  (when first-para
                    (str "\n\nFirst paragraph:\n" (subs first-para 0 (min 500 (count first-para))))))]
    (when (or (seq headings) (seq sections) (seq metadata))
      (trove/log! {:level :debug :data {:headings (count headings)
                                        :sections (count sections)
                                        :metadata (count metadata)}
                   :msg "Inferring document title"})
      (let [response (llm/ask! rlm-router {:spec title-inference-spec
                                           :messages [(llm/system "You are a document analyst. Infer the most appropriate title for a document based on its structure and content.")
                                                      (llm/user (str "Based on the following document content, infer the document's title. "
                                                                  "Return the most likely title - it should be concise and descriptive.\n\n"
                                                                  context))]
                                           :routing (if text-model
                                                      {:model text-model}
                                                      {:optimize :cost})
                                           :check-context? false
                                           :timeout-ms timeout-ms})]
        (get-in response [:result :title])))))
