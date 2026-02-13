(ns com.blockether.svar.rlm.internal.pageindex.vision
  "Vision/LLM-based text extraction from documents.
   
   Provides:
   - `image->base64` - Convert BufferedImage to base64 PNG string
   - `extract-text-from-image` - Extract text from a single BufferedImage (vision)
   - `extract-text-from-pdf` - Extract text from all pages of a PDF (vision)
   - `extract-text-from-text-file` - Extract from text/markdown file (direct text)
   - `extract-text-from-image-file` - Extract from image file (vision)
   - `extract-text-from-string` - Extract from string content (direct text)
   
   Configuration is passed explicitly via opts maps.
   Uses multimodal LLM for both image and text extraction.
   Parallel extraction using core.async channels for PDFs."
  (:require
   [clojure.core.async :as async]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.core :as svar]
   [com.blockether.svar.rlm.internal.pageindex.pdf :as pdf]
   [taoensso.trove :as trove])
  (:import
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

(def BBOX_COORDINATE_SCALES
  "Bounding box coordinate scale factors by model.
   
   Vision models return bbox coordinates in different formats:
   - Some use normalized coordinates (0-1000, 0-1, etc.)
   - Some use actual pixel coordinates (nil = no scaling needed)
   
   This map defines the normalization scale for each model.
   If a model returns coords in 0-N range, set scale to N.
   If a model returns actual pixels, set to nil."
  {"glm-4.6v"       1000   ; GLM-4.6V uses 0-1000 normalized coordinates
   "glm-4.6v-flash" 1000   ; GLM-4.6V-Flash likely same as GLM-4.6V
   "glm-4.6v-flashx" 1000  ; GLM-4.6V-FlashX likely same
   "gpt-4o"         nil    ; GPT-4o uses actual pixel coordinates
   "gpt-4-turbo"    nil    ; GPT-4-Turbo uses actual pixels
   "claude-3-opus"  nil    ; Claude uses actual pixels
   "claude-3-sonnet" nil})

(defn- get-bbox-scale
  "Returns the bbox coordinate scale for a given model.
   
   Params:
   `model` - String. Model name.
   
   Returns:
   Integer scale factor (e.g., 1000 for GLM-4.6V), or nil if model uses actual pixels."
  [model]
  (get BBOX_COORDINATE_SCALES model))

(def DEFAULT_VISION_OBJECTIVE
  "Default system prompt for vision-based text extraction."
  "You are an expert document analyzer. Extract document content as typed nodes with hierarchical structure.

Your task is to parse the document into semantic nodes, preserving both reading order AND document hierarchy.
Use parent-id to link content to its parent section. This creates a tree structure from a flat list.

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
  - bbox: [xmin, ymin, xmax, ymax] in pixels (coordinates must match actual image dimensions)
  - caption: Text from document caption (null if no caption present)
  - description: REQUIRED - YOUR description of what the image shows
  NOTE: Detect ALL images including tiny icons, license badges, social media icons in headers/footers

Table - Data tables (DESCRIPTION AND CONTENT ARE REQUIRED)
  - id: Unique identifier
  - parent-id: ID of the Section this table belongs to
  - kind: data, form, layout, comparison, schedule
  - bbox: [xmin, ymin, xmax, ymax] in pixels
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
7. Set continuation=true if content continues from previous page
8. Keep text content exact - no interpretation or summarization
9. Detect ALL visual elements regardless of size - even tiny icons (20x20 pixels or smaller)
10. Include footer/header icons, license badges (Creative Commons, etc.), social media icons, decorative icons
11. Do NOT skip images based on size or perceived importance - capture everything visual
12. For TocEntry: ONLY extract from actual TOC pages - never infer. Set target-section-id to null always.

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
  {type:'Image', id:'9', parent-id:'6', kind:'diagram', bbox:[...], caption:null, description:'A flowchart showing the research methodology with four stages: data collection, preprocessing, analysis, and validation.'}
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

(defn image->bytes
  "Converts a BufferedImage to raw PNG bytes.
   
   Params:
   `image` - BufferedImage. The image to convert.
   
   Returns:
   byte[]. Raw PNG bytes."
  [^BufferedImage image]
  (let [baos (ByteArrayOutputStream.)]
    (ImageIO/write image "PNG" baos)
    (.toByteArray baos)))

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
  (svar/svar-spec
   :section
   {svar/KEY-NS "page.node"}
   (svar/field {svar/NAME :type
                svar/TYPE :spec.type/keyword
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Node type discriminator"
                svar/VALUES {"section" "Section node"}})
   (svar/field {svar/NAME :id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Unique identifier (1, 2, 3, etc.)"})
   (svar/field {svar/NAME :parent-id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "ID of parent section (null for top-level sections)"})
   (svar/field {svar/NAME :description
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Brief summary of what this section contains"})))

(def ^:private heading-spec
  "Spec for heading nodes. Headings belong to sections via parent-id."
  (svar/svar-spec
   :heading
   {svar/KEY-NS "page.node"}
   (svar/field {svar/NAME :type
                svar/TYPE :spec.type/keyword
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Node type discriminator"
                svar/VALUES {"heading" "Heading node"}})
   (svar/field {svar/NAME :id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Unique identifier (1, 2, 3, etc.)"})
   (svar/field {svar/NAME :parent-id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "ID of the section this heading belongs to"})
   (svar/field {svar/NAME :level
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Heading level"
                svar/VALUES heading-level-values})
   (svar/field {svar/NAME :content
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "The heading text"})))

(def ^:private paragraph-spec
  "Spec for paragraph/text content nodes."
  (svar/svar-spec
   :paragraph
   {svar/KEY-NS "page.node"}
   (svar/field {svar/NAME :type
                svar/TYPE :spec.type/keyword
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Node type discriminator"
                svar/VALUES {"paragraph" "Paragraph node"}})
   (svar/field {svar/NAME :id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Unique identifier (1, 2, 3, etc.)"})
   (svar/field {svar/NAME :parent-id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "ID of parent section this paragraph belongs to"})
   (svar/field {svar/NAME :level
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Content type"
                svar/VALUES text-level-values})
   (svar/field {svar/NAME :content
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "The text content"})
   (svar/field {svar/NAME :continuation?
                svar/TYPE :spec.type/bool
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "True if continues from previous page"})))

(def ^:private list-item-spec
  "Spec for list item nodes."
  (svar/svar-spec
   :list-item
   {svar/KEY-NS "page.node"}
   (svar/field {svar/NAME :type
                svar/TYPE :spec.type/keyword
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Node type discriminator"
                svar/VALUES {"list-item" "List item node"}})
   (svar/field {svar/NAME :id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Unique identifier (1, 2, 3, etc.)"})
   (svar/field {svar/NAME :parent-id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "ID of parent section this list item belongs to"})
   (svar/field {svar/NAME :level
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "List nesting level"
                svar/VALUES list-level-values})
   (svar/field {svar/NAME :content
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "The list item text"})
   (svar/field {svar/NAME :continuation?
                svar/TYPE :spec.type/bool
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "True if continues from previous page"})))

(def ^:private toc-entry-spec
  "Spec for table of contents entry nodes (document-level, not page-level)."
  (svar/svar-spec
   :toc-entry
   {svar/KEY-NS "document.toc"}
   (svar/field {svar/NAME :type
                svar/TYPE :spec.type/keyword
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Node type discriminator"
                svar/VALUES {"toc-entry" "Table of contents entry node"}})
   (svar/field {svar/NAME :id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Unique identifier (1, 2, 3, etc.)"})
   (svar/field {svar/NAME :parent-id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "ID of parent section this TOC entry belongs to"})
   (svar/field {svar/NAME :title
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "The TOC entry title text (e.g., 'Chapter 1 Introduction')"})
   (svar/field {svar/NAME :description
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "Brief summary of section contents (copied from linked Section in post-processing)"})
   (svar/field {svar/NAME :target-page
                svar/TYPE :spec.type/int
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "Page number this entry points to (null if not visible)"})
   (svar/field {svar/NAME :target-section-id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "ID of the Section node this entry links to (if identifiable)"})
   (svar/field {svar/NAME :level
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "TOC nesting level"
                svar/VALUES list-level-values})))

(def ^:private image-spec
  "Spec for image nodes. Description is REQUIRED, caption is optional."
  (svar/svar-spec
   :image
   {svar/KEY-NS "page.node"}
   (svar/field {svar/NAME :type
                svar/TYPE :spec.type/keyword
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Node type discriminator"
                svar/VALUES {"image" "Image node"}})
   (svar/field {svar/NAME :id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Unique identifier (1, 2, 3, etc.)"})
   (svar/field {svar/NAME :parent-id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "ID of parent section this image belongs to"})
   (svar/field {svar/NAME :kind
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "What kind of image this is"
                svar/VALUES image-kind-values})
   (svar/field {svar/NAME :bbox
                svar/TYPE :spec.type/int-v-4
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Bounding box as xmin, ymin, xmax, ymax in pixels"})
   (svar/field {svar/NAME :caption
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "Caption from the document (null if no caption present)"})
   (svar/field {svar/NAME :description
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "REQUIRED: AI description of what the image shows"})
   (svar/field {svar/NAME :continuation?
                svar/TYPE :spec.type/bool
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "True if this image continues from previous page (e.g., truncated diagram, split figure)"})))

(def ^:private table-spec
  "Spec for table nodes. Description is REQUIRED, caption is optional."
  (svar/svar-spec
   :table
   {svar/KEY-NS "page.node"}
   (svar/field {svar/NAME :type
                svar/TYPE :spec.type/keyword
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Node type discriminator"
                svar/VALUES {"table" "Table node"}})
   (svar/field {svar/NAME :id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Unique identifier (1, 2, 3, etc.)"})
   (svar/field {svar/NAME :parent-id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "ID of parent section this table belongs to"})
   (svar/field {svar/NAME :kind
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "What kind of table this is"
                svar/VALUES table-kind-values})
   (svar/field {svar/NAME :bbox
                svar/TYPE :spec.type/int-v-4
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Bounding box as xmin, ymin, xmax, ymax in pixels"})
   (svar/field {svar/NAME :caption
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "Caption from the document (null if no caption present)"})
   (svar/field {svar/NAME :description
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "REQUIRED: AI description of the table content and structure"})
   (svar/field {svar/NAME :content
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "REQUIRED: Table data as ASCII art with pipe column separators and dash row separators. Reproduce ALL rows and columns exactly as shown in the document."})
   (svar/field {svar/NAME :continuation?
                svar/TYPE :spec.type/bool
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "True if this table continues from previous page (e.g., 'Table X (cont.)')"})))

(def ^:private header-spec
  "Spec for page header nodes."
  (svar/svar-spec
   :header
   {svar/KEY-NS "page.node"}
   (svar/field {svar/NAME :type
                svar/TYPE :spec.type/keyword
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Node type discriminator"
                svar/VALUES {"header" "Header node"}})
   (svar/field {svar/NAME :id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Unique identifier (1, 2, 3, etc.)"})
   (svar/field {svar/NAME :content
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "The header text"})))

(def ^:private footer-spec
  "Spec for page footer nodes."
  (svar/svar-spec
   :footer
   {svar/KEY-NS "page.node"}
   (svar/field {svar/NAME :type
                svar/TYPE :spec.type/keyword
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Node type discriminator"
                svar/VALUES {"footer" "Footer node"}})
   (svar/field {svar/NAME :id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Unique identifier (1, 2, 3, etc.)"})
   (svar/field {svar/NAME :content
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "The footer text"})))

(def ^:private metadata-spec
  "Spec for document metadata nodes."
  (svar/svar-spec
   :metadata
   {svar/KEY-NS "page.node"}
   (svar/field {svar/NAME :type
                svar/TYPE :spec.type/keyword
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Node type discriminator"
                svar/VALUES {"metadata" "Metadata node"}})
   (svar/field {svar/NAME :id
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Unique identifier (1, 2, 3, etc.)"})
   (svar/field {svar/NAME :content
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "The metadata content (title, version, date, etc.)"})))

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
  (svar/svar-spec
   {:refs [section-spec heading-spec paragraph-spec list-item-spec toc-entry-spec
           image-spec table-spec header-spec footer-spec metadata-spec]}
   (svar/field {svar/NAME :nodes
                svar/TYPE :spec.type/ref
                svar/TARGET [:section :heading :paragraph :list-item :toc-entry
                             :image :table :header :footer :metadata]
                svar/CARDINALITY :spec.cardinality/many
                svar/DESCRIPTION "Document nodes in reading order (top to bottom, left to right)"})))

;; =============================================================================
;; Image Processing
;; =============================================================================

(def ^:private DEFAULT_VISION_TIMEOUT_MS
  "Default timeout for vision LLM requests (6 minutes per image).
   Vision models processing images can take longer than text-only requests."
  360000)

(def ^:private BBOX_PADDING_PX
  "Padding in pixels to add around image bounding boxes.
   Prevents cropping from trimming edges of detected images."
  4)

(defn scale-and-clamp-bbox
  "Scales bounding box from model coordinates to pixel coordinates,
   adds padding, then clamps to valid image dimensions.
   
   Different vision models return bbox in different formats:
   - GLM-4.6V: normalized 0-1000 coordinates
   - GPT-4o/Claude: actual pixel coordinates
   
   Params:
   `bbox` - Vector of [xmin, ymin, xmax, ymax] in model coordinates.
   `width` - Integer. Image width in pixels.
   `height` - Integer. Image height in pixels.
   `bbox-scale` - Integer or nil. If set, coords are in 0-N normalized space
                  and will be scaled to pixels. If nil, coords are already pixels.
   
   Returns:
   Vector of [xmin, ymin, xmax, ymax] in actual pixels with padding, clamped to valid range,
   or nil if invalid."
  [bbox width height bbox-scale]
  (when (and bbox (= 4 (count bbox)))
    (let [[raw-xmin raw-ymin raw-xmax raw-ymax] bbox
          width (long width)
          height (long height)
          ;; Scale from normalized to actual pixel coordinates (if scale is set)
          [xmin ymin xmax ymax] (if bbox-scale
                                  (let [bbox-scale (double bbox-scale)]
                                    [(int (* (double raw-xmin) (/ (double width) bbox-scale)))
                                     (int (* (double raw-ymin) (/ (double height) bbox-scale)))
                                     (int (* (double raw-xmax) (/ (double width) bbox-scale)))
                                     (int (* (double raw-ymax) (/ (double height) bbox-scale)))])
                                  (mapv int [raw-xmin raw-ymin raw-xmax raw-ymax]))
          ;; Add padding (expand the box outward)
          xmin (- (long xmin) BBOX_PADDING_PX)
          ymin (- (long ymin) BBOX_PADDING_PX)
          xmax (+ (long xmax) BBOX_PADDING_PX)
          ymax (+ (long ymax) BBOX_PADDING_PX)
          ;; Clamp to valid range (padding may have pushed outside bounds)
          xmin (max 0 (min xmin width))
          ymin (max 0 (min ymin height))
          xmax (max 0 (min xmax width))
          ymax (max 0 (min ymax height))]
      ;; Ensure we have a valid box (positive dimensions)
      (when (and (< xmin xmax) (< ymin ymax))
        [xmin ymin xmax ymax]))))

(defn extract-image-region
  "Extracts a region from a BufferedImage and returns it as base64.
   
   Params:
   `image` - BufferedImage. The source image.
   `bbox` - Vector of [xmin, ymin, xmax, ymax] in PIXEL coordinates (already scaled).
   
   Returns:
   String. Base64-encoded PNG of the cropped region, or nil if bbox is invalid."
  [^BufferedImage image bbox]
  (when (and bbox (= 4 (count bbox)))
    (let [[xmin ymin xmax ymax] (map int bbox)
          width (- (long xmax) (long xmin))
          height (- (long ymax) (long ymin))]
      (when (and (pos? width) (pos? height))
        (let [cropped (.getSubimage image (int xmin) (int ymin) (int width) (int height))]
          (image->base64 cropped))))))

(defn image->bytes-region
  "Extracts a region from a BufferedImage and returns it as PNG bytes.
   
   Params:
   `image` - BufferedImage. The source image.
   `bbox` - Vector of [xmin, ymin, xmax, ymax] in PIXEL coordinates (already scaled).
   
   Returns:
   byte[]. PNG bytes of the cropped region, or nil if bbox is invalid."
  [^BufferedImage image bbox]
  (when (and bbox (= 4 (count bbox)))
    (let [[xmin ymin xmax ymax] (map int bbox)
          width (- (long xmax) (long xmin))
          height (- (long ymax) (long ymin))]
      (when (and (pos? width) (pos? height))
        (let [cropped (.getSubimage image (int xmin) (int ymin) (int width) (int height))]
          (image->bytes cropped))))))

(defn- visual-node?
  "Checks if a node is a visual node (Image or Table) by presence of :page.node/kind and :page.node/bbox fields."
  [node]
  (and (:page.node/kind node) (:page.node/bbox node)))

(defn- enrich-visual-nodes
  "Enriches visual nodes (images/tables) with extracted image bytes from the source image.
   
   Visual nodes are identified by having :page.node/kind and :page.node/bbox fields (Image and Table types).
   
   Params:
   `nodes` - Vector of all node maps.
   `source-image` - BufferedImage. The source page image for extraction.
   `model` - String. The vision model name (used to determine bbox coordinate scale).
   
    Returns:
     Vector of nodes with :page.node/image-data key added to visual elements that have valid bbox."
  [nodes ^BufferedImage source-image model]
  (let [bbox-scale (get-bbox-scale model)]
    (mapv (fn [node]
            (if (visual-node? node)
              (let [bbox (:page.node/bbox node)
                    img-width (.getWidth source-image)
                    img-height (.getHeight source-image)
                    clamped (scale-and-clamp-bbox bbox img-width img-height bbox-scale)]
                (if clamped
                  (assoc node :page.node/image-data (image->bytes-region source-image clamped)
                         :page.node/bbox clamped)
                  node))
              node))
          nodes)))

(defn extract-text-from-image
  "Extracts document content from a BufferedImage using vision LLM.
   
   Uses typed node structure with parent-id references for hierarchy.
   Sections are logical groupings with AI-generated descriptions.
   Headings are separate nodes that belong to their Section.
   
    Params:
    `image` - BufferedImage. The image to extract from.
    `page-index` - Integer. The page index (0-based).
    `opts` - Map with:
      `:model` - String. Vision model to use.
      `:objective` - String. System prompt for OCR.
      `:config` - Map. LLM config with :api-key, :base-url (from llm-config-component).
      `:timeout-ms` - Integer, optional. HTTP timeout (default: 360000ms / 6 min).
   
    Returns:
    Map with:
      `:page/index` - Integer. The page index.
      `:page/nodes` - Vector of typed document nodes (all fields namespaced as :page.node/X):
        - Section: :page.node/type, :page.node/id, :page.node/parent-id, :page.node/description
        - Heading: :page.node/type, :page.node/id, :page.node/parent-id, :page.node/level, :page.node/content
        - Paragraph: :page.node/type, :page.node/id, :page.node/parent-id, :page.node/level, :page.node/content, :page.node/continuation?
        - ListItem: :page.node/type, :page.node/id, :page.node/parent-id, :page.node/level, :page.node/content, :page.node/continuation?
        - Image: :page.node/type, :page.node/id, :page.node/parent-id, :page.node/kind, :page.node/bbox, :page.node/caption, :page.node/description, :page.node/image-data (bytes)
        - Table: :page.node/type, :page.node/id, :page.node/parent-id, :page.node/kind, :page.node/bbox, :page.node/caption, :page.node/description, :page.node/content (ASCII), :page.node/image-data (bytes)
        - Header: :page.node/type, :page.node/id, :page.node/content
        - Footer: :page.node/type, :page.node/id, :page.node/content
        - Metadata: :page.node/type, :page.node/id, :page.node/content"
  [^BufferedImage image page-index {:keys [model objective timeout-ms config]
                                    :or {timeout-ms DEFAULT_VISION_TIMEOUT_MS}}]
  (let [img-width (.getWidth image)
        img-height (.getHeight image)]
    (trove/log! {:level :info :data {:page page-index :model model :timeout-ms timeout-ms
                                  :image-width img-width :image-height img-height}
                 :msg "Extracting content from page"})
    (let [base64-image (image->base64 image)
          task (format "Extract all content from this document page as typed nodes with parent-id hierarchy. Create Section nodes for headings, and link content to sections via parent-id. For Image and Table nodes, description is REQUIRED.

IMAGE DIMENSIONS: This image is %d pixels wide and %d pixels tall. All bbox coordinates MUST be within these bounds: xmin and xmax in range [0, %d], ymin and ymax in range [0, %d]."
                       img-width img-height img-width img-height)
          response (svar/ask! {:spec vision-response-spec
                               :objective objective
                               :task task
                               :images [{:base64 base64-image :media-type "image/png"}]
                               :model model
                               :config config
                               :check-context? false
                               :timeout-ms timeout-ms})
          raw-nodes (get-in response [:result :nodes] [])
          ;; Enrich visual nodes with extracted base64 data (pass model for bbox scaling)
          nodes (enrich-visual-nodes raw-nodes image model)
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
   
   Returns:
   Vector of maps, one per page:
     `:page/index` - Integer. The page number (0-based).
     `:page/nodes` - Vector of document nodes (see extract-text-from-image for node structure).
   
   Throws:
   Anomaly (fault) if any page fails to extract."
  [pdf-path {:keys [model objective parallel timeout-ms config]
             :or {parallel 4 timeout-ms DEFAULT_VISION_TIMEOUT_MS}}]
   (trove/log! {:level :info :data {:pdf pdf-path :model model :parallel parallel :timeout-ms timeout-ms}
                :msg "Starting PDF text extraction"})
  (let [images (pdf/pdf->images pdf-path)
        page-count (count images)]
     (trove/log! {:level :info :data {:pages page-count} :msg "PDF loaded, extracting text"})

    ;; Handle empty PDF case
    (if (zero? page-count)
      (do
        (trove/log! {:level :warn :data {:pdf pdf-path} :msg "PDF has no pages"})
        [])

      ;; Use core.async for parallel extraction
      (let [result-chan (async/chan page-count)
            ;; Create work items
            work-items (map-indexed (fn [idx img] {:index idx :image img}) images)
            extract-opts {:model model :objective objective :timeout-ms timeout-ms :config config}]

        ;; Start parallel workers - capture errors as data since pipeline-blocking catches exceptions
        (async/pipeline-blocking
         parallel
         result-chan
         (map (fn [{:keys [index image]}]
                (try
                  (extract-text-from-image image index extract-opts)
                  (catch Exception e
                    (let [^java.awt.image.BufferedImage image image
                          ex-data-map (ex-data e)
                           ;; Extract response body for HTTP errors (400, 500, etc.)
                          response-body (:body ex-data-map)
                          status (:status ex-data-map)
                           ;; Extract full LLM request from exception (includes sanitized messages)
                          llm-request (:llm-request ex-data-map)
                           ;; Build basic request info as fallback
                          basic-info {:model model
                                      :timeout-ms timeout-ms
                                      :base-url (get config :base-url)
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
                              {:pdf-path pdf-path
                               :failed-page (:page first-error)
                               :error-message (:message first-error)
                               :total-errors (count errors)
                               :all-errors (mapv :extraction-error errors)})))

          sorted-results)))))

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
  [content page-index {:keys [model objective timeout-ms config]
                       :or {timeout-ms DEFAULT_VISION_TIMEOUT_MS}}]
  (trove/log! {:level :info :data {:page page-index :model model :content-length (count content)}
               :msg "Extracting nodes from text content"})
  (let [response (svar/ask! {:spec vision-response-spec
                             :objective objective
                             :task (str "Extract all content from this document text as typed nodes with parent-id hierarchy. "
                                        "Create Section nodes for headings, and link content to sections via parent-id.\n\n"
                                        "<document_content>\n" content "\n</document_content>")
                             :model model
                             :config config
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
      (anomaly/not-found! "Image file not found" {:path file-path}))
    (let [img (ImageIO/read file)]
      (when-not img
        (anomaly/fault! "Failed to read image file" {:path file-path}))
      img)))

;; =============================================================================
;; Public Extraction Functions
;; =============================================================================

(defn extract-text-from-text-file
  "Extracts document content from a text or markdown file using LLM.
   
   Sends text directly to the multimodal LLM (no image rendering).
   
   Params:
   `file-path` - String. Path to the text/markdown file.
   `opts` - Map with:
     `:model` - String. LLM model to use.
     `:objective` - String. System prompt for extraction.
     `:config` - Map. LLM config with :api-key, :base-url.
     `:timeout-ms` - Integer, optional. HTTP timeout.
   
   Returns:
   Vector with single map:
     `:page/index` - Integer. Always 0.
     `:page/nodes` - Vector of document nodes."
  [file-path {:keys [model] :as opts}]
  (let [file (File. ^String file-path)]
    (when-not (.exists file)
      (anomaly/not-found! "File not found" {:path file-path}))
    (trove/log! {:level :info :data {:file file-path :model model}
                 :msg "Extracting content from text file"})
    (let [content (slurp file)
          result (extract-nodes-from-text content 0 opts)]
      [result])))

(defn extract-text-from-image-file
  "Extracts document content from an image file using vision LLM.
   
   Params:
   `file-path` - String. Path to the image file (.png, .jpg, etc.).
   `opts` - Map with:
     `:model` - String. Vision model to use.
     `:objective` - String. System prompt for OCR.
     `:config` - Map. LLM config with :api-key, :base-url.
     `:timeout-ms` - Integer, optional. HTTP timeout.
   
   Returns:
   Vector with single map:
     `:page/index` - Integer. Always 0.
     `:page/nodes` - Vector of document nodes."
  [file-path {:keys [model] :as opts}]
  (trove/log! {:level :info :data {:file file-path :model model}
               :msg "Extracting text from image file"})
  (let [image (load-image-file file-path)
        result (extract-text-from-image image 0 opts)]
    [result]))

(defn extract-text-from-string
  "Extracts document content from string content using LLM.
   
   Sends text directly to the multimodal LLM (no image rendering).
   
   Params:
   `content` - String. Text/markdown content to extract from.
   `opts` - Map with:
     `:model` - String. LLM model to use.
     `:objective` - String. System prompt for extraction.
     `:config` - Map. LLM config with :api-key, :base-url.
     `:timeout-ms` - Integer, optional. HTTP timeout.
   
   Returns:
   Vector with single map:
     `:page/index` - Integer. Always 0.
     `:page/nodes` - Vector of document nodes."
  [content {:keys [model] :as opts}]
  (trove/log! {:level :info :data {:content-length (count content) :model model}
               :msg "Extracting content from string"})
  (let [result (extract-nodes-from-text content 0 opts)]
    [result]))

;; =============================================================================
;; Title Inference
;; =============================================================================

(def ^:private title-inference-spec
  "Spec for document title inference response."
  (svar/svar-spec
   (svar/field {svar/NAME :title
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "The inferred document title"})))

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
  [pages {:keys [model config timeout-ms]
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
      (let [response (svar/ask! {:spec title-inference-spec
                                 :objective "You are a document analyst. Infer the most appropriate title for a document based on its structure and content."
                                 :task (str "Based on the following document content, infer the document's title. "
                                            "Return the most likely title - it should be concise and descriptive.\n\n"
                                            context)
                                 :model model
                                 :config config
                                 :check-context? false
                                 :timeout-ms timeout-ms})]
        (get-in response [:result :title])))))
