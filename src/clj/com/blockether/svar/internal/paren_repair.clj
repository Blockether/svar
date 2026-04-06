(ns com.blockether.svar.internal.paren-repair
  "Delimiter and paren repair for LLM-generated Clojure code.

   Inspired by clojure-mcp-light by Bruce Hauman (MIT licensed):
     https://github.com/bhauman/clojure-mcp-light

   Provides:
   - `delimiter-repair` - fixes mismatched delimiters like `)]` -> `])` (local swaps)
   - `paren-repair` - fixes missing or extra delimiters via stack balancing
   - `repair-code` - public entry point, runs delimiter-repair then paren-repair

   Used by the RLM SCI executor to recover from LLM bracket errors without
   forcing the model to re-emit the full code block."
  (:require
   [clojure.string :as str]))

;; =============================================================================
;; Character classification
;; =============================================================================

(def ^:private open->close {\( \) \[ \] \{ \}})
(def ^:private close->open {\) \( \] \[ \} \{})
(def ^:private opens (set (keys open->close)))
(def ^:private closes (set (keys close->open)))

;; =============================================================================
;; Reader state machine - shared by both phases
;; Produces a seq of tokens: {:ch char :kind kind :pos int}
;; kind is one of :open :close :other
;; Tokens inside strings, char literals, and line comments are :other.
;; =============================================================================

(defn- tokenize
  "Walk `s` left-to-right, returning a vector of maps
   {:ch char :kind keyword :pos int}.
   Delimiters inside string literals, character literals, or line comments
   are tagged :other instead of :open/:close so they are invisible to
   the balancer."
  [s]
  (let [n (count s)]
    (loop [i 0
           in-string? false
           escaped? false
           result (transient [])]
      (if (= i n)
        (persistent! result)
        (let [ch (nth s i)]
          (cond
            ;; Inside a string - consume until unescaped close quote
            in-string?
            (cond
              escaped?   (recur (inc i) true  false (conj! result {:ch ch :kind :other :pos i}))
              (= ch \\)  (recur (inc i) true  true  (conj! result {:ch ch :kind :other :pos i}))
              (= ch \")  (recur (inc i) false false (conj! result {:ch ch :kind :other :pos i}))
              :else      (recur (inc i) true  false (conj! result {:ch ch :kind :other :pos i})))

            ;; Character literal: `\X` - next char is literal, never a delimiter
            (and (= ch \\) (< (inc i) n) (not= (nth s (inc i)) \newline))
            (recur (+ i 2) false false
              (-> result
                (conj! {:ch ch :kind :other :pos i})
                (conj! {:ch (nth s (inc i)) :kind :other :pos (inc i)})))

            ;; Line comment - skip to end of line
            (= ch \;)
            (let [end (or (str/index-of s \newline i) n)]
              (recur end false false
                (reduce (fn [acc j]
                          (conj! acc {:ch (nth s j) :kind :other :pos j}))
                  result (range i end))))

            ;; Start of string
            (= ch \")
            (recur (inc i) true false (conj! result {:ch ch :kind :other :pos i}))

            ;; Open delimiter
            (opens ch)
            (recur (inc i) false false (conj! result {:ch ch :kind :open :pos i}))

            ;; Close delimiter
            (closes ch)
            (recur (inc i) false false (conj! result {:ch ch :kind :close :pos i}))

            :else
            (recur (inc i) false false (conj! result {:ch ch :kind :other :pos i}))))))))

;; =============================================================================
;; delimiter-repair - local swap of mismatched closers
;;
;; Algorithm (matches upstream clojure-mcp-light approach):
;; Walk the token stream maintaining an open-paren stack.
;; When we see a close delimiter:
;;   - If stack is non-empty and the close does NOT match the top of stack,
;;     replace it with the correct closer for the top of stack.
;;   - If stack is empty, remove the orphan close (replace with nothing).
;; When we see an open delimiter, push it.
;; Rebuild the string from the modified token stream.
;; =============================================================================

(defn delimiter-repair
  "Fix mismatched closing delimiters by swapping them to match their
   corresponding open delimiter.

   Example: `(foo]` -> `(foo)`
            `(foo [bar)]` -> `(foo [bar])`

   Returns the repaired string, or the original if no change was needed."
  [s]
  (let [tokens (tokenize s)
        ;; Accumulate replacements: pos -> replacement-char (nil = delete)
        rep-atom (atom {})
        stack (reduce
                (fn [stk {:keys [ch kind pos]}]
                  (case kind
                    :open  (conj stk {:ch ch :pos pos})
                    :close (if (seq stk)
                             (let [top (:ch (peek stk))]
                               (if (= ch (open->close top))
                                 ;; matched
                                 (pop stk)
                                 ;; mismatch - replace close with correct closer
                                 (do (swap! rep-atom assoc pos (open->close top))
                                     (pop stk))))
                             ;; orphan close - mark for deletion
                             (do (swap! rep-atom assoc pos nil)
                                 stk))
                    stk))
                []
                tokens)
        rep @rep-atom]
    (if (empty? rep)
      s
      ;; Rebuild string applying replacements
      (let [sb (StringBuilder.)]
        (doseq [{:keys [ch pos]} tokens]
          (if (contains? rep pos)
            (when-let [replacement (get rep pos)]
              (.append sb replacement))
            (.append sb ch)))
        ;; Any unclosed opens on stack get appended as closes
        (doseq [{:keys [ch]} (reverse stack)]
          (.append sb (open->close ch)))
        (str sb)))))

;; =============================================================================
;; paren-repair - global balancing (add missing / remove extra delimiters)
;;
;; Algorithm:
;; Phase 1: Walk tokens left-to-right, track open stack.
;;   - Extra closers (stack empty) are collected for removal.
;; Phase 2: After full scan, anything left on stack = missing closers.
;;   Append them at the end in reverse stack order.
;; Rebuild from token stream with removals and additions.
;; =============================================================================

(defn paren-repair
  "Fix missing or extra delimiters via stack-based global balancing.

   Example: `(foo (bar` -> `(foo (bar))`
            `(foo))` -> `(foo)`
            `[[1 2]]]` -> `[[1 2]]`

   Returns the repaired string, or the original if already balanced."
  [s]
  (let [tokens (tokenize s)
        remove-atom (atom #{})
        stack (reduce
                (fn [stk {:keys [ch kind pos]}]
                  (case kind
                    :open  (conj stk {:ch ch :pos pos})
                    :close (if (and (seq stk) (= ch (open->close (:ch (peek stk)))))
                             ;; matched close - pop
                             (pop stk)
                             ;; orphan or mismatched closer - mark for removal
                             (do (swap! remove-atom conj pos)
                                 stk))
                    stk))
                []
                tokens)
        removals @remove-atom]
    (if (and (empty? removals) (empty? stack))
      s
      (let [sb (StringBuilder.)]
        (doseq [{:keys [ch pos]} tokens]
          (when-not (contains? removals pos)
            (.append sb ch)))
        ;; Append missing closers for unclosed opens
        (doseq [{:keys [ch]} (reverse stack)]
          (.append sb (open->close ch)))
        (str sb)))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn paren-balance
  "Returns the net open-minus-close count for real delimiters in `s`.
   Skips delimiters inside string literals, char literals, and comments.
   Zero means balanced. Positive means unclosed openers."
  [s]
  (let [tokens (tokenize s)]
    (reduce (fn [n {:keys [kind]}]
              (case kind
                :open  (inc n)
                :close (dec n)
                n))
      0 tokens)))

(defn repair-code
  "Run paren-repair followed by delimiter-repair on `s`.
   paren-repair removes orphan closers first; delimiter-repair then fixes
   any remaining mismatched close delimiters.
   Returns the repaired string, or the original string if no change was made."
  [s]
  (if (str/blank? s)
    s
    (-> s paren-repair delimiter-repair)))

(def ^:private parse-error-patterns
  ["Unmatched delimiter"
   "EOF while reading"
   "Unexpected EOF"
   "Invalid token"])

(defn parse-error?
  "Returns true if `error-message` matches one of the known SCI parse error
   patterns that paren repair can potentially fix."
  [error-message]
  (boolean
    (when (string? error-message)
      (some #(str/includes? error-message %) parse-error-patterns))))
