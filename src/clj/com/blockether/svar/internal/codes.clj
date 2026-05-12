(ns com.blockether.svar.internal.codes
  "Fenced code-block extraction from raw LLM text responses.

   Pure parsing. No HTTP, no provider knowledge. Used by `ask-code!` to turn
   a plain-text completion (`(defn …) wrapped in ```clojure fences`) into a
   single concatenated source string ready for the caller to read/eval.

   Lenient+ rules — handles the three shapes models produce in practice:
     1. Tagged fence:        ```clojure\\n…\\n```
     2. Untagged fence:      ```\\n…\\n```
     3. No fence at all:     entire response treated as one untagged block.

   Multiple fences are concatenated (joined with a blank line) so the model
   can split a response across narration + multiple code blobs and the
   caller still receives one source string."
  (:require
   [clojure.string :as str])
  (:import
   (com.blockether.svar FenceBlocksParser FenceBlocksParser$Block
     FenceNormalizer)))

(defn- normalize-fences
  "Apply opener / inline-boundary / closer normalization in a single linear
   pass over the input. Implemented in Java
   ({@link com.blockether.svar.FenceNormalizer}) because the previous
   pure-regex pipeline blew up to O(N²) on long streamed buffers (Vis conv
   0c8188ac, live thread dump showed the JVM pinned in
   `Pattern$BmpCharPropertyGreedy.match`)."
  [s]
  (FenceNormalizer/normalize s))

(defn- parse-fenced-blocks
  "Walk fence-normalized lines and emit a `{:blocks :saw-fence? :malformed?}`
   map. Delegates to {@link com.blockether.svar.FenceBlocksParser} which
   runs the line-classifier and body assembly in one Java pass —
   significantly faster than the prior `str/split` + `re-matches` per
   line implementation on multi-MB buffers."
  [s]
  (let [result (FenceBlocksParser/parse s)
        java-blocks (.blocks result)
        blocks (mapv (fn [^FenceBlocksParser$Block b]
                       {:lang (.lang b) :source (.source b)})
                 java-blocks)]
    (cond-> {:blocks blocks :saw-fence? (.sawFence result)}
      (.malformed result) (assoc :malformed? true))))

(defn extract-code-blocks
  "Parse fenced code blocks from `raw` text.

   Returns a vector of `{:lang <str-or-nil> :source <str>}`. `:lang` is the
   tag after the opening ``` (lower-cased), or `nil` for untagged fences.
   `:source` is the body verbatim (newlines preserved; no trim applied to
   internal whitespace).

   Lenient+ fallback: when `raw` contains NO fenced block at all, returns
   one block `{:lang nil :source <trimmed-raw>}`. Empty / blank input
   returns `[]`."
  [raw]
  (let [s (normalize-fences raw)
        {:keys [blocks saw-fence?]} (parse-fenced-blocks s)]
    (if (seq blocks)
      blocks
      (let [trimmed (str/trim s)]
        (cond
          (str/blank? trimmed) []
          saw-fence? []
          (str/includes? trimmed "```") []
          :else [{:lang nil :source trimmed}])))))

(defn select-blocks
  "Filter `blocks` to those whose `:lang` matches `lang` (case-insensitive)
   OR is `nil` (untagged — treated as a match for any language).

   `lang` MUST be a non-blank string; pass `\"clojure\"`, `\"python\"`, etc."
  [blocks lang]
  (when-not (and (string? lang) (not (str/blank? lang)))
    (throw (IllegalArgumentException.
             (str "select-blocks: lang must be a non-blank string, got "
               (pr-str lang)))))
  (let [target (str/lower-case lang)]
    (filterv #(let [bl (:lang %)] (or (nil? bl) (= bl target)))
      blocks)))

(defn concat-sources
  "Concatenate non-blank `:source` values, joined by a blank-line separator."
  [blocks]
  (str/join "\n\n" (keep (fn [{:keys [source]}]
                           (when-not (str/blank? source) source))
                     blocks)))
