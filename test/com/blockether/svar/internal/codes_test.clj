(ns com.blockether.svar.internal.codes-test
  "Tests for fenced code-block extraction (codes/extract-code-blocks,
   codes/extract-code-blocks-detail, codes/select-blocks,
   codes/concat-sources)."
  (:require
   [clojure.string :as str]
   [lazytest.core :refer [defdescribe describe expect it throws?]]
   [com.blockether.svar.internal.codes :as sut]))

(defdescribe extract-code-blocks-detail-test
  "extract-code-blocks-detail surfaces the parser's :saw-fence? /
   :malformed? flags alongside the same :blocks vec the public
   `extract-code-blocks` returns."

  (describe "shape"
    (it "returns a map with :blocks, :saw-fence?, :malformed?"
      (let [d (sut/extract-code-blocks-detail "```clojure\n(+ 1 2)\n```")]
        (expect (vector? (:blocks d)))
        (expect (boolean? (:saw-fence? d)))
        (expect (boolean? (:malformed? d)))))

    (it ":blocks matches what extract-code-blocks would return"
      (let [raw "```python\nx=1\n```\nprose\n```clojure\n(+ 1 2)\n```"
            d   (sut/extract-code-blocks-detail raw)]
        (expect (= (sut/extract-code-blocks raw) (:blocks d))))))

  (describe ":saw-fence?"
    (it "is true when raw contains a fence"
      (expect (true? (:saw-fence? (sut/extract-code-blocks-detail
                                    "```clojure\n(+ 1 2)\n```")))))

    (it "is false when raw is plain text without fences (fenceless fallback)"
      (let [d (sut/extract-code-blocks-detail "just prose, no fences")]
        (expect (false? (:saw-fence? d)))
        ;; fallback still produces one untagged block
        (expect (= 1 (count (:blocks d))))
        (expect (nil? (:lang (first (:blocks d)))))))

    (it "is false for empty / blank input"
      (expect (false? (:saw-fence? (sut/extract-code-blocks-detail ""))))
      (expect (false? (:saw-fence? (sut/extract-code-blocks-detail "   \n\n  "))))))

  (describe ":malformed?"
    (it "is false on a clean tagged fence"
      (expect (false? (:malformed? (sut/extract-code-blocks-detail
                                     "```clojure\n(+ 1 2)\n```"))))))

  (describe "all-lang surface for callers diagnosing dropped fences"
    (it "reports every fence regardless of lang"
      ;; Caller compares (count :blocks) here with (count (select-blocks
      ;; blocks lang)) downstream to detect wrong-lang drops.
      (let [d (sut/extract-code-blocks-detail
                "```python\nx=1\n```\n```clojure\n(+ 1 2)\n```")]
        (expect (= 2 (count (:blocks d))))
        (expect (= #{"python" "clojure"} (set (map :lang (:blocks d)))))))))

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

    (it "accepts an opener where code starts after a separating space"
      (let [blocks (sut/extract-code-blocks "```clojure (answer \"4\")\n```")]
        (expect (= [{:lang "clojure" :source "(answer \"4\")"}] blocks))))

    (it "does not swallow an empty fence plus following clojure fence into source"
      (let [blocks (sut/extract-code-blocks "```clojure\n```\n\n```clojure\n(answer \"4\")\n```")]
        (expect (= [{:lang "clojure" :source ""}
                    {:lang "clojure" :source "(answer \"4\")"}]
                  blocks))))

    (it "splits close fence followed by clojure opener on the next line"
      (let [blocks (sut/extract-code-blocks "```clojure\n(def x 1)\n```\n```clojure\n(def y 2)\n```")]
        (expect (= [{:lang "clojure" :source "(def x 1)"}
                    {:lang "clojure" :source "(def y 2)"}]
                  blocks))))

    (it "splits glued close-open fence boundaries"
      (let [blocks (sut/extract-code-blocks "```clojure\n(def x 1)\n``````clojure\n(def y 2)\n```")]
        (expect (= [{:lang "clojure" :source "(def x 1)"}
                    {:lang "clojure" :source "(def y 2)"}]
                  blocks))))

    (it "splits a closer glued to code before the next opener on the same line"
      (let [blocks (sut/extract-code-blocks "```clojure\n(def x 1)```` ```clojure\n(def y 2)\n```")]
        (expect (= [{:lang "clojure" :source "(def x 1)"}
                    {:lang "clojure" :source "(def y 2)"}]
                  blocks))))

    (it "splits glued close-open boundaries for longer fences"
      (let [blocks (sut/extract-code-blocks "````clojure\n(def x 1)\n````````clojure\n(def y 2)\n````")]
        (expect (= [{:lang "clojure" :source "(def x 1)"}
                    {:lang "clojure" :source "(def y 2)"}]
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

    (it "implicitly closes a non-empty trailing fence at EOF"
      (expect (= [{:lang "clojure" :source "(def x 1)"}]
                (sut/extract-code-blocks "```clojure\n(def x 1)"))))

    (it "keeps completed blocks when the final fence is missing its closer"
      (expect (= [{:lang "clojure" :source "(def x 1)"}
                  {:lang "clojure" :source "(def y 2)"}]
                (sut/extract-code-blocks "```clojure\n(def x 1)\n```\n\n```clojure\n(def y 2)"))))

    (it "returns [] for a short next opener instead of guessing"
      (expect (= [] (sut/extract-code-blocks "```clojure\n(def x 1)```` ``clojure\n(def y 2)\n```"))))

    ;; Vis session b94052f0 (TUI froze after a 3-iter turn). The model
    ;; emitted a clean ``` closer followed by trailing junk on the same
    ;; line: "```        -   -       ". The Java line classifier only
    ;; matches ``` + optional lang + horizontal-ws + EOL, so the line
    ;; was NOT recognized as a closer → it leaked into the body. The
    ;; downstream Clojure parser (edamame) then read the bare ``` as
    ;; three nested syntax-quote reader macros wrapping the trailing `-`,
    ;; producing a ~1 KB (clojure.core/sequence (clojure.core/seq …))
    ;; macroexpansion that froze the TUI for ~870 ms on every zprint
    ;; pass. A closer with trailing non-lang junk MUST close the block.
    (describe "closer with trailing junk on the same line (Vis b94052f0)"
      (it "closes the block when the closer is followed by whitespace + non-lang chars"
        (let [raw "```clojure\n(done {:answer \"hi\"})\n```        -   -       "
              blocks (sut/extract-code-blocks raw)]
          (expect (= 1 (count blocks)))
          (expect (= "(done {:answer \"hi\"})" (:source (first blocks))))))

      (it "closes the block when the closer is followed by a single non-lang char"
        (let [raw "```clojure\n(+ 1 2)\n``` ?"
              blocks (sut/extract-code-blocks raw)]
          (expect (= 1 (count blocks)))
          (expect (= "(+ 1 2)" (:source (first blocks))))))

      (it "still rejects a non-fence line that merely contains backticks mid-line"
        ;; A real body line `foo ``` bar` should NOT close — only lines
        ;; whose first non-whitespace token is the backtick run count.
        (let [raw "```clojure\nfoo ``` bar\n```"
              blocks (sut/extract-code-blocks raw)]
          (expect (= 1 (count blocks)))
          (expect (= "foo ``` bar" (:source (first blocks))))))))

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
  "select-blocks filters strictly by lang — untagged blocks are DROPPED."

  (it "keeps tagged matches and drops untagged blocks"
    (let [blocks [{:lang "clojure" :source "(a)"}
                  {:lang "bash"    :source "echo"}
                  {:lang nil       :source "(b)"}]]
      (expect (= [{:lang "clojure" :source "(a)"}]
                (sut/select-blocks blocks "clojure")))))

  (it "drops ALL untagged blocks even when none match the target"
    (let [blocks [{:lang nil :source "(a)"}
                  {:lang nil :source "(b)"}]]
      (expect (= [] (sut/select-blocks blocks "clojure")))))

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

  (it "DROPS a fenceless raw response (no explicit lang tag)"
    ;; Strict-lang contract: a model that emits raw code with no fence at
    ;; all produces a `:lang nil` block via the lenient+ fallback, which
    ;; `select-blocks` now drops. Models MUST tag their fence to be
    ;; accepted by routed `ask-code!`.
    (let [response "(def x 42)\n(answer (str x))"
          selected (-> (sut/extract-code-blocks response)
                     (sut/select-blocks "clojure"))]
      (expect (= [] selected))
      (expect (= "" (sut/concat-sources selected))))))

(defdescribe nested-fence-in-clojure-string-test
  ;; Regression for Vis conv 11d4f817 (t12/i1). The model emitted:
  ;;
  ;;   ```clojure
  ;;   (done
  ;;     {:answer "## Status\n\n```clojure\n(deftest x …)\n```"
  ;;      :trailer-drop ["…"]})
  ;;   ```
  ;;
  ;; The nested ```clojure / ``` inside the :answer STRING used to close
  ;; the outer ```clojure fence early, producing a torn (done … block,
  ;; followed by stray (deftest …) and trailing-key garbage blocks.
  ;; Recovery (vis loop/recover-direct-answer-blocks) only handled the
  ;; `:answer`-as-sole-key shape and silently dropped sibling keys.
  ;;
  ;; The fix nests tagged openers inside an already-open block: a
  ;; `\`\`\`clojure` line seen INSIDE a `\`\`\`clojure` body pushes a
  ;; nesting counter, and the matching bare `\`\`\`` closer pops it
  ;; instead of closing the outer block. The outer block only closes
  ;; when nesting depth returns to zero.
  (describe "tagged opener nested inside an already-open same-lang block"
    (it "keeps the outer (done {…}) form whole when an inner ```clojure sample appears inside an :answer string"
      ;; Mirrors what the LLM actually streamed (Vis conv 11d4f817 t12/i1):
      ;; the :answer string carries REAL newlines, and the embedded
      ;; ```clojure / ``` sit on their own lines inside the string.
      (let [raw (str "```clojure\n"
                  "(task-set! :foo {:status :done})\n"
                  "(done\n"
                  "  {:answer \"## Status\n"
                  "\n"
                  "Example:\n"
                  "\n"
                  "```clojure\n"
                  "(deftest x (is (= 1 1)))\n"
                  "```\n"
                  "\n"
                  "End.\"\n"
                  "   :trailer-drop [\"t1/i1\"]})\n"
                  "```")
            d   (sut/extract-code-blocks-detail raw)]
        (expect (= 1 (count (:blocks d))))
        (expect (false? (:malformed? d)))
        (let [src (:source (first (:blocks d)))]
          (expect (str/includes? src "(task-set! :foo"))
          (expect (str/includes? src "(done"))
          (expect (str/includes? src ":trailer-drop"))
          (expect (str/includes? src "(deftest x")))))

    (it "handles multiple nested sample fences in one outer block"
      (let [raw (str "```clojure\n"
                  "(done {:answer \"a```clojure\nx\n```b```bash\nls\n```c\"})\n"
                  "```")
            d   (sut/extract-code-blocks-detail raw)]
        (expect (= 1 (count (:blocks d))))
        (expect (false? (:malformed? d)))
        (expect (str/includes? (:source (first (:blocks d))) ":answer"))))))

(defdescribe extract-code-blocks-perf-test
  ;; Regression for Vis conv 0c8188ac: `normalize-fence-closers` used to
  ;; run a `(?m)…$` regex over the entire buffer. Combined with the LLM
  ;; layer re-parsing on every SSE chunk, total work was O(N²). Even with
  ;; the LLM-layer per-chunk re-parse removed, the normalizers must stay
  ;; linear so callers that DO invoke extraction on a megabyte-scale
  ;; response finish in tens of ms, not minutes.
  (describe "linear-time normalization on large buffers"
    (it "extracts a 1 MB fenced response in well under a second"
      (let [;; ~1 MB of realistic Clojure-looking lines inside one fence,
            ;; with sprinkled backticks/brackets to stress the regexes
            ;; that used to backtrack catastrophically.
            line   "(defn foo-bar [x y] (let [r `(:a :b :c)] (str x y r [1 2] {:k :v})))"
            body   (str/join "\n" (repeat 12000 line))
            response (str "```clojure\n" body "\n```")
            start  (System/nanoTime)
            blocks (sut/extract-code-blocks response)
            elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
        (expect (= 1 (count blocks)))
        (expect (= "clojure" (:lang (first blocks))))
        ;; Generous bound: pre-fix this took tens of seconds on the same
        ;; input. 1.5 s leaves headroom for slow CI without masking a
        ;; reintroduction of the quadratic pass.
        (expect (< elapsed-ms 1500))))))

(defdescribe lenient-block-test
  "lenient-block: the WHOLE reply IS the code. No fence scan, no lang
   filtering, nothing dropped — the opposite of select-blocks' strictness.
   Used by ask-code! `:lenient` for single-engine agent loops."
  (describe "fenceless raw reply is kept verbatim (NOT dropped)"
    (it "stamps the caller lang and keeps the source as-is"
      (let [b (sut/lenient-block "x = cat(\"a\")\ndone(\"\"\"hi\"\"\")" "python")]
        (expect (= {:lang "python"
                    :source "x = cat(\"a\")\ndone(\"\"\"hi\"\"\")"}
                  b)))))
  (describe "single outer wrapper fence is stripped"
    (it "strips a tagged wrapper"
      (expect (= "z = 2" (:source (sut/lenient-block "```python\nz = 2\n```" "python")))))
    (it "strips an untagged wrapper"
      (expect (= "x = 1" (:source (sut/lenient-block "```\nx = 1\n```" "python")))))
    (it "stamps the caller lang, never the wrapper tag"
      (expect (= "python" (:lang (sut/lenient-block "```py\nx = 1\n```" "python"))))))
  (describe "never splits — multiple/interior fences stay verbatim"
    (it "keeps a two-fence reply whole (no multifence behavior)"
      (let [raw "```python\na = 1\n```\nmid\n```python\nb = 2\n```"]
        (expect (= raw (:source (sut/lenient-block raw "python")))))))
  (describe "edge cases"
    (it "returns nil for blank input"
      (expect (nil? (sut/lenient-block "   \n  " "python")))
      (expect (nil? (sut/lenient-block nil "python"))))
    (it "leaves an unclosed opener verbatim (only whole wrappers strip)"
      (expect (= "```python\nx = 1"
                (:source (sut/lenient-block "```python\nx = 1" "python")))))))

