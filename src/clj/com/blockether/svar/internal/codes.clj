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
   [clojure.string :as str]))

(def ^:private FENCE_PATTERN
  ;; (?ms)            multiline + dotall.
  ;; ^[ \t]*```       opening fence, possibly indented.
  ;; ([A-Za-z0-9_+\-]*) optional language tag (clojure, bash, c++, etc).
  ;; [ \t]*\R         optional trailing spaces + line terminator.
  ;; (.*?)            lazy body — capture group 2.
  ;; (?:\R)?          optional separator before closer; allows empty body.
  ;; ^[ \t]*```[ \t]*$ closing fence on its own line.
  #"(?ms)^[ \t]*```([A-Za-z0-9_+\-]*)[ \t]*\R(.*?)(?:\R)?^[ \t]*```[ \t]*$")

(defn- normalize-fence-openers [s]
  ;; Streams can glue first token after fence: ```clojure(answer …).
  ;; Treat delimiter as if newline followed tag.
  (str/replace s #"(?m)^([ \t]*```(?:[A-Za-z0-9_+\-]+)?)(?=[\(\[\{])" "$1\n"))

(defn- normalize-fence-closers [s]
  ;; Models can glue closing fence after last form: (answer …)```.
  ;; Keep body, move fence to own line.
  (str/replace s #"(?m)([^\r\n`])([ \t]*`{3,}[ \t]*)$" "$1\n$2"))

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
  (let [s (-> (or raw "")
            normalize-fence-openers
            normalize-fence-closers)
        matches (re-seq FENCE_PATTERN s)
        blocks (mapv (fn [[_ lang body]]
                       {:lang   (when (seq lang) (str/lower-case lang))
                        :source body})
                 matches)]
    (if (seq blocks)
      blocks
      (let [trimmed (str/trim s)]
        (cond
          (str/blank? trimmed) []
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
  "Concatenate non-blank `:source` values, joined by a blank line."
  [blocks]
  (str/join "\n\n" (keep (fn [{:keys [source]}]
                           (when-not (str/blank? source) source))
                     blocks)))
