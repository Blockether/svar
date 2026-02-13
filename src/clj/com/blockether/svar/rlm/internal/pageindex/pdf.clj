(ns com.blockether.svar.rlm.internal.pageindex.pdf
  "PDF to images conversion and metadata extraction using Apache PDFBox.
   
   Provides:
   - `pdf->images` - Convert PDF file to vector of BufferedImage objects
   - `pdf-metadata` - Extract PDF metadata (author, title, dates, etc.)
   
   Uses PDFBox for reliable PDF rendering at configurable DPI.
   Handles error cases: encrypted PDFs, corrupted files, file not found."
  (:require
   [com.blockether.anomaly.core :as anomaly])
  (:import
   [java.io File IOException]
   [java.util Calendar]
   [org.apache.pdfbox Loader]
   [org.apache.pdfbox.rendering ImageType PDFRenderer]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private DEFAULT_DPI
  "Default DPI for rendering PDF pages as images.
   150 DPI provides good balance between quality and file size for vision LLMs."
  150)

;; =============================================================================
;; PDF to Images Conversion
;; =============================================================================

(defn pdf->images
  "Converts a PDF file to a vector of BufferedImage objects.
   
   Params:
   `pdf-path` - String. Path to the PDF file.
   `opts` - Optional map with:
     `:dpi` - Integer. Rendering DPI (default 150).
   
   Returns:
   Vector of BufferedImage objects, one per page.
   
   Throws:
   ex-info for not-found, corrupted, or encrypted PDFs."
  ([pdf-path]
   (pdf->images pdf-path {}))
  ([pdf-path {:keys [dpi] :or {dpi DEFAULT_DPI}}]
   (let [file (File. ^String pdf-path)]
     (when-not (.exists file)
       (anomaly/not-found! "PDF file not found" {:path pdf-path}))

     (let [^org.apache.pdfbox.pdmodel.PDDocument document
           (try
             (Loader/loadPDF file)
             (catch IOException e
               (anomaly/fault! "Failed to load PDF - file may be corrupted"
                               {:path pdf-path :cause (ex-message e)})))]
       (try
         (when (.isEncrypted document)
           (anomaly/incorrect! "PDF is encrypted/password protected"
                               {:path pdf-path}))

         (let [renderer (PDFRenderer. document)
               page-count (.getNumberOfPages document)]
           (mapv (fn [page-idx]
                   (.renderImageWithDPI renderer page-idx (float dpi) ImageType/RGB))
                 (range page-count)))

         (finally
           (.close document)))))))

(defn page-count
  "Returns the number of pages in a PDF file.
   
   Params:
   `pdf-path` - String. Path to the PDF file.
   
   Returns:
   Integer. Number of pages.
   
   Throws:
   Same exceptions as `pdf->images`."
  [pdf-path]
  (let [file (File. ^String pdf-path)]
    (when-not (.exists file)
      (anomaly/not-found! "PDF file not found" {:path pdf-path}))

    (let [^org.apache.pdfbox.pdmodel.PDDocument document
          (try
            (Loader/loadPDF file)
            (catch IOException e
              (anomaly/fault! "Failed to load PDF - file may be corrupted"
                              {:path pdf-path :cause (ex-message e)})))]
      (try
        (.getNumberOfPages document)
        (finally
          (.close document))))))

;; =============================================================================
;; PDF Metadata Extraction
;; =============================================================================

(defn- calendar->instant
  "Converts a Java Calendar to an Instant, or nil if calendar is nil."
  [^Calendar cal]
  (when cal
    (.toInstant cal)))

(defn pdf-metadata
  "Extracts metadata from a PDF file.
   
   Params:
   `pdf-path` - String. Path to the PDF file.
   
   Returns:
   Map with:
     `:author` - String or nil. Document author.
     `:title` - String or nil. Document title.
     `:subject` - String or nil. Document subject.
     `:creator` - String or nil. Creating application.
     `:producer` - String or nil. PDF producer.
     `:created-at` - Instant or nil. Creation date.
     `:updated-at` - Instant or nil. Modification date.
     `:keywords` - String or nil. Document keywords.
   
   Throws:
   Same exceptions as `pdf->images`."
  [pdf-path]
  (let [file (File. ^String pdf-path)]
    (when-not (.exists file)
      (anomaly/not-found! "PDF file not found" {:path pdf-path}))

    (let [^org.apache.pdfbox.pdmodel.PDDocument document
          (try
            (Loader/loadPDF file)
            (catch IOException e
              (anomaly/fault! "Failed to load PDF - file may be corrupted"
                              {:path pdf-path :cause (ex-message e)})))]
      (try
        (let [^org.apache.pdfbox.pdmodel.PDDocumentInformation info
              (.getDocumentInformation document)]
          {:author (.getAuthor info)
           :title (.getTitle info)
           :subject (.getSubject info)
           :creator (.getCreator info)
           :producer (.getProducer info)
           :created-at (calendar->instant (.getCreationDate info))
           :updated-at (calendar->instant (.getModificationDate info))
           :keywords (.getKeywords info)})
        (finally
          (.close document))))))

(comment
  ;; Example usage
  (def images (pdf->images "resources-test/example.pdf"))

  (count images)
  ;; => 3 (or however many pages)

  (first images)
  ;; => #object[java.awt.image.BufferedImage ...]

  ;; With custom DPI
  (def high-res-images (pdf->images "test.pdf" {:dpi 300}))

  ;; Get page count without loading all images
  (page-count "resources-test/example.pdf")
  ;; => 3

  ;; Get PDF metadata
  (pdf-metadata "resources-test/example.pdf")
  ;; => {:author "John Doe" :title "Example" :created-at #inst "..." ...}
  )
