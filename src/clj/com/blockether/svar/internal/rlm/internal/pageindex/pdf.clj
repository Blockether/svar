(ns com.blockether.svar.internal.rlm.internal.pageindex.pdf
  "PDF to images conversion and metadata extraction using Apache PDFBox.
   
   Provides:
   - `pdf->images` - Convert PDF file to vector of BufferedImage objects
   - `page-count` - Get total page count of a PDF file
   - `pdf-metadata` - Extract PDF metadata (author, title, dates, etc.)
   - `detect-text-rotation` - Detect content rotation per page using text position heuristics
   
   Uses PDFBox for reliable PDF rendering at configurable DPI.
   Handles error cases: encrypted PDFs, corrupted files, file not found."
  (:require
   [com.blockether.anomaly.core :as anomaly])
  (:import
   [java.io File IOException]
   [java.util Calendar]
   [org.apache.pdfbox Loader]
   [org.apache.pdfbox.rendering ImageType PDFRenderer]
   [org.apache.pdfbox.text PDFTextStripper TextPosition]))

;; Prevent AWT from spawning a Dock icon / Preview window on macOS.
;; Must be set before the AWT toolkit initializes.
(System/setProperty "java.awt.headless" "true")

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
     `:page-set` - Set of 0-indexed page numbers to render, or nil for all pages.
   
   Returns:
   Vector of BufferedImage objects, one per selected page.
   
   Throws:
   ex-info for not-found, corrupted, or encrypted PDFs."
  ([pdf-path]
   (pdf->images pdf-path {}))
  ([pdf-path {:keys [dpi page-set] :or {dpi DEFAULT_DPI}}]
   (let [file (File. ^String pdf-path)]
     (when-not (.exists file)
       (anomaly/not-found! "PDF file not found" {:type :svar.pdf/file-not-found :path pdf-path}))

     (let [^org.apache.pdfbox.pdmodel.PDDocument document
           (try
             (Loader/loadPDF file)
             (catch IOException e
               (anomaly/fault! "Failed to load PDF - file may be corrupted"
                               {:type :svar.pdf/corrupted-file :path pdf-path :cause (ex-message e)})))]
       (try
         (when (.isEncrypted document)
           (anomaly/incorrect! "PDF is encrypted/password protected"
                               {:type :svar.pdf/encrypted :path pdf-path}))

         (let [renderer (PDFRenderer. document)
               page-count (.getNumberOfPages document)
               indices (if page-set
                         (filterv #(< % page-count) (sort page-set))
                         (range page-count))]
           (mapv (fn [page-idx]
                   (.renderImageWithDPI renderer page-idx (float dpi) ImageType/RGB))
                 indices))

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
      (anomaly/not-found! "PDF file not found" {:type :svar.pdf/file-not-found :path pdf-path}))

    (let [^org.apache.pdfbox.pdmodel.PDDocument document
          (try
            (Loader/loadPDF file)
            (catch IOException e
              (anomaly/fault! "Failed to load PDF - file may be corrupted"
                              {:type :svar.pdf/corrupted-file :path pdf-path :cause (ex-message e)})))]
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
      (anomaly/not-found! "PDF file not found" {:type :svar.pdf/file-not-found :path pdf-path}))

    (let [^org.apache.pdfbox.pdmodel.PDDocument document
          (try
            (Loader/loadPDF file)
            (catch IOException e
              (anomaly/fault! "Failed to load PDF - file may be corrupted"
                              {:type :svar.pdf/corrupted-file :path pdf-path :cause (ex-message e)})))]
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

;; =============================================================================
;; Text Rotation Detection (Heuristic)
;; =============================================================================

(defn detect-text-rotation
  "Detects content rotation for each page of a PDF using text position heuristics.
   
   Analyzes the direction of text characters on each page via PDFBox's TextPosition.
   If the majority of characters flow in a non-standard direction, the page content
   is rotated (e.g., landscape table on a portrait page).
   
   TextPosition directions:
   - 0°: Normal left-to-right text → no correction needed
   - 90°: Text flows bottom-to-top → correct by rotating image 270° CW
   - 180°: Upside-down text → correct by rotating image 180°
   - 270°: Text flows top-to-bottom → correct by rotating image 90° CW
   
   Params:
   `pdf-path` - String. Path to the PDF file.
   `opts` - Optional map with:
     `:page-set` - Set of 0-indexed page numbers to analyze, or nil for all pages.
   
   Returns:
   Vector of integers, one per selected page. Each value is the clockwise rotation
   in degrees (0, 90, 180, or 270) needed to correct the rendered image.
   
   Example:
   [0 0 90] means pages 0-1 are normal, page 2 needs 90° CW rotation."
  ([pdf-path] (detect-text-rotation pdf-path {}))
  ([pdf-path {:keys [page-set]}]
   (let [file (File. ^String pdf-path)]
     (when-not (.exists file)
       (anomaly/not-found! "PDF file not found" {:type :svar.pdf/file-not-found :path pdf-path}))

     (let [^org.apache.pdfbox.pdmodel.PDDocument document
           (try
             (Loader/loadPDF file)
             (catch IOException e
               (anomaly/fault! "Failed to load PDF - file may be corrupted"
                               {:type :svar.pdf/corrupted-file :path pdf-path :cause (ex-message e)})))]
       (try
         (let [page-count (.getNumberOfPages document)
               indices (if page-set
                         (filterv #(< % page-count) (sort page-set))
                         (range page-count))]
           (mapv
            (fn [page-idx]
              ;; Collect text directions for this page
              (let [directions (atom [])
                    stripper (proxy [PDFTextStripper] []
                               (processTextPosition [^TextPosition text]
                                 (swap! directions conj (.getDir text))))]
                (.setStartPage stripper (inc page-idx))
                (.setEndPage stripper (inc page-idx))
                (.getText stripper document)

                ;; Determine majority text direction
                (let [dirs @directions]
                  (if (empty? dirs)
                    0 ;; No text on page — assume correct orientation
                    (let [freq (frequencies (map #(Math/round (double %)) dirs))
                          ;; Find the direction with the most characters
                          [majority-dir _] (apply max-key val freq)
                          ;; The text direction IS the correction needed:
                          ;; Text dir 0° → normal, no rotation
                          ;; Text dir 90° → text sideways, rotate image 90° CW (right)
                          ;; Text dir 180° → upside down, rotate 180°
                          ;; Text dir 270° → text sideways other way, rotate 270° CW
                          correction (case (long majority-dir)
                                       0 0
                                       90 90
                                       180 180
                                       270 270
                                       0)]
                      correction)))))
            indices))
         (finally
           (.close document)))))))

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
