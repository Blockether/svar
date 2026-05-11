(ns com.blockether.svar.bench.fence-normalizer-bench-test
  "Smoke tests for the bench fixtures. The real performance work is in
   the bench namespace itself; this test guards against fixture-building
   bugs (wrong sizes, mis-counted lines) that would silently invalidate
   the bench output."
  (:require
   [clojure.string :as str]
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.bench.fence-normalizer-bench :as sut]
   [com.blockether.svar.internal.codes :as codes]))

(defdescribe fixture-shape-test
  (describe "fenced-response"
    (it "wraps the requested number of lines in a single clojure fence"
      (let [s (sut/fenced-response 5)]
        (expect (str/starts-with? s "```clojure\n"))
        (expect (str/ends-with? s "\n```"))
        (let [blocks (codes/extract-code-blocks s)]
          (expect (= 1 (count blocks)))
          (expect (= "clojure" (:lang (first blocks))))
          (expect (= 5 (count (str/split-lines (:source (first blocks))))))))))

  (describe "pathological-buffer"
    (it "produces a single-line buffer ending with a glued closer"
      (let [s (sut/pathological-buffer 50)]
        (expect (not (str/includes? s "\n")))
        (expect (str/ends-with? s "```"))
        ;; Even on a glued-closer single line the parser must terminate
        ;; quickly and return a sensible result.
        (let [blocks (codes/extract-code-blocks s)]
          (expect (vector? blocks)))))))
