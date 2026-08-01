(ns com.blockether.svar.internal.image-dimensions-test
  "Contract for the image HEADER reader behind vision token estimation.

   Token counting needs one thing from an image: its pixel size. svar used to
   ask `javax.imageio` for it, which pulls the whole `java.desktop` module —
   an AWT toolkit, fontconfig and image-reader SPI — into every process that
   merely counts tokens, and into every GraalVM native image embedding svar.

   The replacement is plain Java — `com.blockether.svar.ImageHeader` — reading
   the size straight out of the file header, so the cases below are the header
   layouts themselves: PNG's IHDR, JPEG's SOFn
   after a segment walk, GIF's logical screen, BMP's (possibly negative,
   top-down) INFOHEADER, and all three WebP chunk flavours. Everything
   unrecognised or truncated must answer nil — never throw, never spin — so
   the caller falls back to its fixed estimate."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.router :as sut])
  (:import
   (java.util Base64)))

(def ^:private dimensions #'sut/image-dimensions)

(defn- bytes' ^bytes [xs] (byte-array (map unchecked-byte xs)))

(defn- ascii [^String s] (map int s))

(defn- be32 [^long n]
  [(bit-and (bit-shift-right n 24) 0xff)
   (bit-and (bit-shift-right n 16) 0xff)
   (bit-and (bit-shift-right n 8) 0xff)
   (bit-and n 0xff)])

(defn- le16 [^long n] [(bit-and n 0xff) (bit-and (bit-shift-right n 8) 0xff)])

(defn- le24 [^long n]
  [(bit-and n 0xff)
   (bit-and (bit-shift-right n 8) 0xff)
   (bit-and (bit-shift-right n 16) 0xff)])

(defn- le32 [^long n] (into (le24 n) [(bit-and (bit-shift-right n 24) 0xff)]))

(defn- png [w h]
  (bytes' (concat [0x89] (ascii "PNG\r\n") [0x1a 0x0a]
            (be32 13) (ascii "IHDR") (be32 w) (be32 h)
            [8 6 0 0 0])))

(defn- gif [w h] (bytes' (concat (ascii "GIF89a") (le16 w) (le16 h) [0x70 0 0])))

(defn- bmp [w h]
  (bytes' (concat (ascii "BM") (repeat 16 0) (le32 w) (le32 h) [1 0 24 0])))

(defn- jpeg
  "SOI, a JFIF APP0 that must be SKIPPED by length, then the SOF0 that counts."
  [w h]
  (bytes' (concat [0xff 0xd8]
            [0xff 0xe0] (reverse (le16 16)) (ascii "JFIF") [0 1 1 0 0 1 0 1 0 0]
            [0xff 0xdb] (reverse (le16 4)) [0 0]
            [0xff 0xc0] (reverse (le16 17)) [8]
            (reverse (le16 h)) (reverse (le16 w))
            [3 1 0x22 0 2 0x11 1 3 0x11 1])))

(defn- webp [fourcc payload]
  (bytes' (concat (ascii "RIFF") (le32 (+ 12 (count payload))) (ascii "WEBP")
            (ascii fourcc) (le32 (count payload)) payload)))

(defn- webp-lossy [w h]
  (webp "VP8 " (concat [0x30 0x01 0x00 0x9d 0x01 0x2a] (le16 w) (le16 h) [0 0])))

(defn- webp-lossless [w h]
  (let [wm (dec (long w)) hm (dec (long h))]
    (webp "VP8L" [0x2f
                  (bit-and wm 0xff)
                  (bit-or (bit-shift-right wm 8) (bit-and (bit-shift-left hm 6) 0xc0))
                  (bit-and (bit-shift-right hm 2) 0xff)
                  (bit-and (bit-shift-right hm 10) 0x0f)
                  0 0 0 0 0])))

(defn- webp-extended [w h]
  (webp "VP8X" (concat [0x10 0 0 0] (le24 (dec (long w))) (le24 (dec (long h))) [0 0 0 0])))

(defdescribe image-dimensions-test
  (describe "recognised headers"
    (it "reads PNG IHDR"
      (expect (= [637 213] (dimensions (png 637 213)))))

    (it "reads JPEG SOFn after walking past APP0 and DQT"
      (expect (= [637 213] (dimensions (jpeg 637 213)))))

    (it "reads the GIF logical screen"
      (expect (= [637 213] (dimensions (gif 637 213)))))

    (it "reads BMP, and a top-down BMP's NEGATIVE height as a size"
      (expect (= [637 213] (dimensions (bmp 637 213))))
      (expect (= [637 213] (dimensions (bmp 637 (- 4294967296 213))))))

    (it "reads all three WebP chunk kinds"
      (expect (= [637 213] (dimensions (webp-lossy 637 213))))
      (expect (= [637 213] (dimensions (webp-lossless 637 213))))
      (expect (= [637 213] (dimensions (webp-extended 637 213)))))

    (it "handles 1-pixel and very large sizes"
      (expect (= [1 1] (dimensions (png 1 1))))
      (expect (= [16383 16383] (dimensions (webp-lossy 16383 16383))))
      (expect (= [30000 20000] (dimensions (png 30000 20000))))))

  (describe "everything else is nil, never a throw"
    (it "rejects garbage, empty and nil input"
      (expect (nil? (dimensions nil)))
      (expect (nil? (dimensions (byte-array 0))))
      (expect (nil? (dimensions (bytes' (range 32)))))
      (expect (nil? (dimensions (bytes' (ascii "%PDF-1.7 not an image at all"))))))

    (it "rejects a TRUNCATED jpeg instead of scanning forever"
      (let [full (jpeg 637 213)]
        (expect (nil? (dimensions (java.util.Arrays/copyOf full 12))))))

    (it "rejects a PNG whose IHDR never arrives"
      (expect (nil? (dimensions (bytes' (concat [0x89] (ascii "PNG\r\n") [0x1a 0x0a]
                                          (be32 13) (ascii "junk") (be32 10) (be32 10)))))))

    (it "rejects a RIFF container that is not WebP"
      (expect (nil? (dimensions (bytes' (concat (ascii "RIFF") (le32 0) (ascii "WAVE")
                                          (repeat 16 0)))))))))

(defdescribe vision-token-estimate-test
  (describe "data: URLs are measured, not guessed"
    (it "prices a 637x213 PNG by its real size"
      (let [estimate #'sut/estimate-image-block-tokens
            b64 (.encodeToString (Base64/getEncoder) (png 637 213))
            block {:type "image_url"
                   :image_url {:url (str "data:image/png;base64," b64)}}]
        ;; 637x213 needs no downscale: 2x1 tiles of 512 => 2*170 + 85.
        (expect (= 425 (long (estimate block))))))

    (it "falls back when the payload is unreadable"
      (let [estimate #'sut/estimate-image-block-tokens
            b64 (.encodeToString (Base64/getEncoder) (bytes' (range 32)))]
        (expect (= 765 (long (estimate {:type "image_url"
                                        :image_url {:url (str "data:image/png;base64," b64)}}))))))

    (it "keeps honouring detail=low without touching the bytes"
      (let [estimate #'sut/estimate-image-block-tokens]
        (expect (= 85 (long (estimate {:type "image_url"
                                       :image_url {:url "https://example.invalid/x.png"
                                                   :detail "low"}}))))))))
