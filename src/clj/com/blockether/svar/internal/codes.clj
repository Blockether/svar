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

(def ^:private FENCE_LINE_RE #"^([ \t]*)(`{3,})([A-Za-z0-9_+\-]*)[ \t]*$")

(defn- normalize-lines [s]
  (-> (or s "")
    (str/replace "\r\n" "\n")
    (str/replace "\r" "\n")))

(defn- fence-line [line]
  (when-let [[_ indent ticks lang] (re-matches FENCE_LINE_RE line)]
    {:indent indent
     :ticks ticks
     :len (count ticks)
     :lang (when (seq lang) (str/lower-case lang))}))

(defn- normalize-fence-openers [s]
  ;; Streams can glue first token after fence: ```clojure(answer …)
  ;; or ```clojure (answer …). Treat delimiter as if newline followed tag.
  (-> s
    (str/replace #"(?m)^([ \t]*```(?:[A-Za-z0-9_+\-]+)?)(?=[\(\[\{])" "$1\n")
    (str/replace #"(?m)^([ \t]*```(?:[A-Za-z0-9_+\-]+)?)[ \t]+(?=[\(\[\{])" "$1\n")))

(defn- normalize-fence-closers [s]
  ;; Models can glue closing fence after last form: (answer …)```.
  ;; Keep body, move fence to own line.
  (str/replace s #"(?m)([^\r\n`])([ \t]*`{3,}[ \t]*)$" "$1\n$2"))

(defn- normalize-inline-fence-boundaries [s]
  ;; Models also glue close + next open on one line:
  ;; (def x 1)```` ```clojure. Split only when next opener has a real
  ;; >=3-backtick fence. Shorter "``clojure" stays malformed and is
  ;; rejected by the parser instead of guessed.
  (str/replace s #"(?m)([^\r\n`])(`{3,})[ \t]+(`{3,}[A-Za-z0-9_+\-]*)" "$1\n$2\n$3"))

(defn- malformed-fence-fragment? [source]
  ;; A source body still containing close + short/open-ish fence means
  ;; extraction did not find a safe boundary. Reject whole extraction.
  (boolean (re-find #"`{3,}[ \t]+`{1,2}[A-Za-z0-9_+\-]+" (or source ""))))

(defn- parse-fenced-blocks [s]
  ;; Line parser, not one regex. Handles:
  ;; - normal close newline open: ```\n```clojure
  ;; - glued close+open inside current fence: ``````clojure
  ;; - close glued to code then next opener on same line, after normalizer
  ;; Closing fence may use >= opener backticks. Glued close+open needs
  ;; enough ticks for both close + next open. Unclosed/malformed fence => [].
  (let [lines (str/split (normalize-lines s) #"\n" -1)]
    (loop [remaining lines
           in-block? false
           open-len nil
           open-lang nil
           body []
           blocks []
           saw-fence? false]
      (if-let [line (first remaining)]
        (let [f (fence-line line)]
          (cond
            (and (not in-block?) f)
            (recur (next remaining) true (:len f) (:lang f) [] blocks true)

            (and in-block? f (nil? (:lang f)) (>= (long (:len f)) (long open-len)))
            (recur (next remaining) false nil nil []
              (conj blocks {:lang open-lang :source (str/join "\n" body)})
              true)

            (and in-block? f (:lang f) (>= (long (:len f)) (+ (long open-len) 3)))
            (let [next-open-len (- (long (:len f)) (long open-len))]
              (recur (next remaining) true next-open-len (:lang f) []
                (conj blocks {:lang open-lang :source (str/join "\n" body)})
                true))

            :else
            (recur (next remaining) in-block? open-len open-lang
              (if in-block? (conj body line) body)
              blocks
              (or saw-fence? (some? f)))))
        (if in-block?
          {:blocks [] :saw-fence? true :malformed? true}
          (if (some (comp malformed-fence-fragment? :source) blocks)
            {:blocks [] :saw-fence? true :malformed? true}
            {:blocks blocks :saw-fence? saw-fence?}))))))

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
            normalize-fence-closers
            normalize-inline-fence-boundaries)
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
