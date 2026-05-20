(ns com.blockether.svar.internal.codes
  "Fenced code-block extraction from raw LLM text responses.

   Pure parsing. No HTTP, no provider knowledge. Used by `ask-code!` to turn
   a plain-text completion into a vector of tagged code blocks the caller
   reads/evals directly.

   Extraction rules — `extract-code-blocks` recognizes three shapes:
     1. Tagged fence:        ```clojure\\n…\\n```   →  {:lang \"clojure\" :source …}
     2. Untagged fence:      ```\\n…\\n```          →  {:lang nil       :source …}
     3. No fence at all:     entire response          →  {:lang nil       :source …}

   `select-blocks` then enforces strict lang matching: ONLY blocks whose
   `:lang` equals the caller-supplied target survive. Untagged blocks
   (`:lang nil`) — including the fenceless-fallback case — are DROPPED.
   Models that want their code accepted MUST tag their fence with the
   requested lang."

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

(defn extract-code-blocks-detail
  "Like `extract-code-blocks` but returns the full parser observation:

   `{:blocks      [{:lang :source} ...]
     :saw-fence?  <bool>     ; raw contained at least one fence-shaped line
     :malformed?  <bool>     ; parser saw glued close+open or unclosed terminal fence
   }`

   Callers that need to diagnose multi-fence emission, wrong-lang fences,
   or torn fence boundaries read this instead of the bare vec. The
   fenceless-fallback path still surfaces a single `:lang nil` block; in
   that case `:saw-fence?` is `false`."
  [raw]
  (let [s (normalize-fences raw)
        {:keys [blocks saw-fence? malformed?]} (parse-fenced-blocks s)
        final-blocks
        (if (seq blocks)
          blocks
          (let [trimmed (str/trim s)]
            (cond
              (str/blank? trimmed) []
              saw-fence? []
              (str/includes? trimmed "```") []
              :else [{:lang nil :source trimmed}])))]
    {:blocks      final-blocks
     :saw-fence?  (boolean saw-fence?)
     :malformed?  (boolean malformed?)}))

(defn extract-code-blocks
  "Parse fenced code blocks from `raw` text.

   Returns a vector of `{:lang <str-or-nil> :source <str>}`. `:lang` is the
   tag after the opening ``` (lower-cased), or `nil` for untagged fences.
   `:source` is the body verbatim (newlines preserved; no trim applied to
   internal whitespace).

   Lenient+ fallback: when `raw` contains NO fenced block at all, returns
   one block `{:lang nil :source <trimmed-raw>}`. Empty / blank input
   returns `[]`.

   NOTE: untagged blocks (`:lang nil`) — including the fenceless-fallback —
   are dropped by `select-blocks`. Pre-select consumers see them; routed
   `ask-code!` callers do not.

   Use `extract-code-blocks-detail` when you also need `:saw-fence?` /
   `:malformed?` (e.g. for multi-fence diagnostics)."
  [raw]
  (:blocks (extract-code-blocks-detail raw)))

(defn select-blocks
  "Filter `blocks` to those whose `:lang` STRICTLY equals `lang`
   (case-insensitive).

   Untagged blocks (`:lang nil`) are DROPPED — NOT treated as a wildcard.
   This is a deliberate strictness contract: models must tag their fence
   with the requested lang to have their code accepted. The fenceless-
   fallback path in `extract-code-blocks` also produces `:lang nil` and is
   therefore likewise dropped here.

   `lang` MUST be a non-blank string; pass `\"clojure\"`, `\"python\"`, etc.
   `nil` / `\"\"` / whitespace raise `IllegalArgumentException`."
  [blocks lang]
  (when-not (and (string? lang) (not (str/blank? lang)))
    (throw (IllegalArgumentException.
             (str "select-blocks: lang must be a non-blank string, got "
               (pr-str lang)))))
  (let [target (str/lower-case lang)]
    (filterv #(= (:lang %) target) blocks)))

(defn concat-sources
  "Concatenate non-blank `:source` values, joined by a blank-line separator."
  [blocks]
  (str/join "\n\n" (keep (fn [{:keys [source]}]
                           (when-not (str/blank? source) source))
                     blocks)))
