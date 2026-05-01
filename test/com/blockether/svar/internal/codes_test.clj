(ns com.blockether.svar.internal.codes-test
  "Tests for fenced code-block extraction (codes/extract-code-blocks,
   codes/select-blocks, codes/concat-sources)."
  (:require
   [lazytest.core :refer [defdescribe describe expect it throws?]]
   [com.blockether.svar.internal.codes :as sut]))

(defdescribe extract-code-blocks-test
  "extract-code-blocks parses fenced and unfenced text."

  (describe "tagged fences"
    (it "extracts a single ```clojure``` fence"
      (let [blocks (sut/extract-code-blocks "intro\n```clojure\n(def x 1)\n```\nend")]
        (expect (= [{:lang "clojure" :source "(def x 1)"}] blocks))))

    (it "lower-cases the lang tag"
      (let [blocks (sut/extract-code-blocks "```Clojure\n(+ 1 2)\n```")]
        (expect (= "clojure" (:lang (first blocks))))))

    (it "extracts multiple tagged fences in order"
      (let [blocks (sut/extract-code-blocks
                     "```clojure\n(def a 1)\n```\nbla\n```clojure\n(def b 2)\n```")]
        (expect (= 2 (count blocks)))
        (expect (= "(def a 1)" (:source (first blocks))))
        (expect (= "(def b 2)" (:source (second blocks))))))

    (it "accepts a glued opener where code starts immediately after the lang tag"
      (let [blocks (sut/extract-code-blocks "```clojure(answer \"4\")\n```")]
        (expect (= [{:lang "clojure" :source "(answer \"4\")"}] blocks))))

    (it "accepts a glued opener without a lang tag"
      (let [blocks (sut/extract-code-blocks "```(answer \"4\")\n```")]
        (expect (= [{:lang nil :source "(answer \"4\")"}] blocks))))

    (it "accepts a glued closing fence after the last form"
      (let [blocks (sut/extract-code-blocks "```clojure\n(answer \"4\")```")]
        (expect (= [{:lang "clojure" :source "(answer \"4\")"}] blocks))))

    (it "does not swallow an empty fence plus following clojure fence into source"
      (let [blocks (sut/extract-code-blocks "```clojure\n```\n\n```clojure\n(answer \"4\")\n```")]
        (expect (= [{:lang "clojure" :source ""}
                    {:lang "clojure" :source "(answer \"4\")"}]
                  blocks)))))

  (describe "untagged fences"
    (it "extracts ``` (no lang) as :lang nil"
      (let [blocks (sut/extract-code-blocks "```\n(def y 7)\n```")]
        (expect (= [{:lang nil :source "(def y 7)"}] blocks)))))

  (describe "mixed langs"
    (it "preserves :lang per block"
      (let [text   (str "```clojure\n(def a 1)\n```\n"
                     "```bash\necho hi\n```\n"
                     "```\n(def b 2)\n```")
            blocks (sut/extract-code-blocks text)]
        (expect (= ["clojure" "bash" nil] (mapv :lang blocks))))))

  (describe "lenient+ no-fence fallback"
    (it "treats raw code with no fences as one untagged block"
      (let [blocks (sut/extract-code-blocks "(def x 1)\n(def y 2)")]
        (expect (= [{:lang nil :source "(def x 1)\n(def y 2)"}] blocks))))

    (it "trims surrounding whitespace in the fallback path"
      (let [blocks (sut/extract-code-blocks "\n\n  (+ 1 2)  \n\n")]
        (expect (= "(+ 1 2)" (:source (first blocks)))))))

  (describe "malformed fences"
    (it "returns [] instead of leaking raw fence markers"
      (expect (= [] (sut/extract-code-blocks "```\n\n```clojure"))))

    (it "returns [] for an unclosed fence instead of falling back to raw markdown"
      (expect (= [] (sut/extract-code-blocks "```clojure\n(def x 1)")))))

  (describe "empty input"
    (it "returns [] for nil"
      (expect (= [] (sut/extract-code-blocks nil))))

    (it "returns [] for empty string"
      (expect (= [] (sut/extract-code-blocks ""))))

    (it "returns [] for whitespace-only string"
      (expect (= [] (sut/extract-code-blocks "   \n\n  ")))))

  (describe "internal whitespace preservation"
    (it "keeps internal blank lines verbatim"
      (let [src "(def a 1)\n\n(def b 2)"
            blocks (sut/extract-code-blocks (str "```clojure\n" src "\n```"))]
        (expect (= src (:source (first blocks))))))))

(defdescribe select-blocks-test
  "select-blocks filters by lang, treating untagged blocks as matches."

  (it "keeps tagged matches and untagged blocks"
    (let [blocks [{:lang "clojure" :source "(a)"}
                  {:lang "bash"    :source "echo"}
                  {:lang nil       :source "(b)"}]]
      (expect (= [{:lang "clojure" :source "(a)"}
                  {:lang nil       :source "(b)"}]
                (sut/select-blocks blocks "clojure")))))

  (it "is case-insensitive on lang"
    (let [blocks [{:lang "clojure" :source "(a)"}]]
      (expect (= 1 (count (sut/select-blocks blocks "CLOJURE"))))))

  (it "returns [] when nothing matches"
    (let [blocks [{:lang "bash" :source "echo"}
                  {:lang "python" :source "print"}]]
      (expect (= [] (sut/select-blocks blocks "clojure")))))

  (it "throws on nil lang"
    (expect (throws? IllegalArgumentException
              #(sut/select-blocks [] nil))))

  (it "throws on blank lang"
    (expect (throws? IllegalArgumentException
              #(sut/select-blocks [] "")))
    (expect (throws? IllegalArgumentException
              #(sut/select-blocks [] "   ")))))

(defdescribe concat-sources-test
  "concat-sources joins block sources with a blank-line separator."

  (it "joins multiple sources with double newline"
    (expect (= "(def a 1)\n\n(def b 2)"
              (sut/concat-sources [{:lang "clojure" :source "(def a 1)"}
                                   {:lang "clojure" :source "(def b 2)"}]))))

  (it "returns empty string for []"
    (expect (= "" (sut/concat-sources []))))

  (it "skips blank sources"
    (expect (= "(def x 1)"
              (sut/concat-sources [{:lang "clojure" :source ""}
                                   {:lang "clojure" :source "(def x 1)"}]))))

  (it "returns the single source verbatim for one block"
    (expect (= "(def x 1)"
              (sut/concat-sources [{:lang "clojure" :source "(def x 1)"}])))))

(defdescribe end-to-end-clojure-extraction-test
  "Realistic LLM responses round-trip through extract → select → concat."

  (it "extracts and concatenates a multi-block clojure response"
    (let [response (str "Here's the plan:\n\n"
                     "```clojure\n(def step1 :ok)\n```\n\n"
                     "Now the action:\n\n"
                     "```clojure\n(answer \"done\")\n```\n")
          source (-> (sut/extract-code-blocks response)
                   (sut/select-blocks "clojure")
                   sut/concat-sources)]
      (expect (= "(def step1 :ok)\n\n(answer \"done\")" source))))

  (it "skips ```bash``` blocks when selecting clojure"
    (let [response (str "```bash\nls -la\n```\n"
                     "```clojure\n(answer \"hi\")\n```")
          source (-> (sut/extract-code-blocks response)
                   (sut/select-blocks "clojure")
                   sut/concat-sources)]
      (expect (= "(answer \"hi\")" source))))

  (it "handles a fenceless raw response as a single Clojure block"
    (let [response "(def x 42)\n(answer (str x))"
          source (-> (sut/extract-code-blocks response)
                   (sut/select-blocks "clojure")
                   sut/concat-sources)]
      (expect (= "(def x 42)\n(answer (str x))" source)))))
