(ns com.blockether.svar.internal.humanize
  "AI response humanization module.
    
    Removes AI-style phrases and patterns from LLM outputs to make responses
    sound more natural and human-like.
    
    Two tiers of patterns:
    - SAFE_PATTERNS (default): AI identity, refusal, knowledge, punctuation.
      Unambiguously AI-generated; safe for arbitrary text.
    - AGGRESSIVE_PATTERNS (opt-in): hedging, overused verbs/adjectives/nouns,
      opening/closing cliches. May match valid English in non-AI text."
  (:require
   [clojure.string :as str])
  (:import
   [java.util.regex Matcher Pattern]))

;; ============================================================================
;; Pattern Constants
;; ============================================================================

(def AI_IDENTITY_PATTERNS
  "Map of AI self-reference phrases to their replacements.
   
   These patterns identify phrases where the AI explicitly refers to itself
   as an AI, language model, or assistant. Removing these makes responses
   sound more natural and less robotic."
  {"As an AI" ""
   "As an AI assistant" ""
   "As a language model" ""
   "As an artificial intelligence" ""
   "I'm an AI" ""
   "I am an AI" ""
   "I'm a language model" ""
   "I am a language model" ""
   "I'm a large language model" ""
   "I am a large language model" ""
   "Being an AI" ""
   "Being an AI assistant" ""
   "As a helpful assistant" ""
   "As an AI, I" "I"
   "As an AI assistant, I" "I"
   "As a language model, I" "I"
   "As an artificial intelligence, I" "I"
   "I'm an AI," "I'm"
   "I am an AI," "I am"
   "I'm a language model," "I'm"
   "I am a language model," "I am"
   "I'm a large language model," "I'm"
   "I am a large language model," "I am"
   "As an AI, " ""
   "As an AI assistant, " ""
   "As a language model, " ""
   "As an artificial intelligence, " ""
   "I should note that I'm an AI" "I should note"
   "I should mention that I'm an AI" "I should mention"
   "I should clarify that I'm an AI" "I should clarify"
   "I should point out that I'm an AI" "I should point out"})

(def REFUSAL_PATTERNS
  "Map of refusal and inability phrases to their replacements.
   
   These patterns identify phrases where the AI refuses to do something
   or claims inability. Replacing them with more natural alternatives
   makes the response sound more conversational."
  {"I cannot and will not" "I won't"
   "I'm not able to" "I can't"
   "I am not able to" "I can't"
   "I don't have the ability to" "I can't"
   "I do not have the ability to" "I can't"
   "It's not within my capabilities" "I can't"
   "It is not within my capabilities" "I can't"
   "I'm unable to" "I can't"
   "I am unable to" "I can't"
   "I cannot" "I can't"
   "I'm not able" "I can't"
   "I am not able" "I can't"
   ;; T2: Removed identity no-ops ("I'm not designed to" -> "I'm not designed to")
   "I am not designed to" "I'm not designed to"
   "I am not programmed to" "I'm not programmed to"
   "I cannot provide" "I can't provide"
   "I'm unable to provide" "I can't provide"
   "I am unable to provide" "I can't provide"
   "I cannot assist with" "I can't help with"})

(def HEDGING_PATTERNS
  "Map of hedging and filler phrases to their replacements.
   
   These patterns identify phrases that add unnecessary hedging or filler
   to responses. Removing them makes the response more direct and confident."
  {"It's important to note that" ""
   "It is important to note that" ""
   "It's worth mentioning that" ""
   "It is worth mentioning that" ""
   "Please note that" ""
   "I should mention that" ""
   "I should note that" ""
   "I should clarify that" ""
   "I should point out that" ""
   "It's crucial to understand that" ""
   "It is crucial to understand that" ""
   "As you may know" ""
   "As you may be aware" ""
   "Generally speaking" ""
   "In general" ""
   "Typically" ""
   "Usually" ""
   "In most cases" ""
   "For the most part" ""
   "It's worth noting that" ""
   "It is worth noting that" ""
   "It should be noted that" ""
   "It should be mentioned that" ""
   "I want to emphasize that" ""
   "I want to stress that" ""
   "I want to highlight that" ""
   "I want to point out that" ""
   "I want to clarify that" ""
   "I want to note that" ""
   "I want to mention that" ""
   "It's important to understand that" ""
   "It is important to understand that" ""
   "It's essential to understand that" ""
   "It is essential to understand that" ""})

(def KNOWLEDGE_PATTERNS
  "Map of training data and knowledge cutoff phrases to their replacements.
    
    These patterns identify phrases where the AI refers to its training data,
    knowledge cutoff, or limitations. Replacing them makes the response
    sound more current and less robotic."
  {"my training data" "available information"
   "my knowledge cutoff" "current information"
   "my knowledge base" "available information"
   "my training" "available information"
   "As of my last update" ""
   "As of my last training" ""
   "Based on my training" ""
   "Based on my training data" ""
   "According to my training" ""
   "From my training" ""
   "In my training data" ""
   "In my training" ""
   "My knowledge was last updated" "Information was last updated"
   "My training data ends" "Available information ends"
   "My knowledge cutoff is" "Current information is"
   "I was trained on" "Available information includes"
   "I was last trained" "Information was last updated"
   "My training ended" "Available information ends"})

(def PUNCTUATION_PATTERNS
  "Map of punctuation patterns to their normalized forms.
    
    These patterns normalize AI-typical punctuation overuse, particularly
    em dashes which are the #1 tell of AI-generated text. AI uses em dashes
    obsessively where humans would use commas, parentheses, or periods.
    
    Patterns handle em dashes (—) and double hyphens (--) in various positions:
    - With spaces on both sides (middle of sentence)
    - At the start of a phrase
    - At the end of a phrase"
  {" — " ", "
   "— " ", "
   " —" ","
   " -- " ", "
   "-- " ", "
   " --" ","})

(def OVERUSED_VERBS_PATTERNS
  "Map of AI-overused action verbs to simpler alternatives.
   
   These patterns replace buzzword verbs that AI models overuse with
   simpler, more natural alternatives. 'Delve' is the #1 most
   AI-identifying word, seeing a 25,000% usage increase post-ChatGPT.
   
   All verb forms are included: base, -ing, -ed, -s conjugations."
  {;; Delve family (THE #1 AI word)
   "delve into" "explore"
   "delve" "explore"
   "delving into" "exploring"
   "delving" "exploring"
   "delved into" "explored"
   "delved" "explored"
   "delves into" "explores"
   "delves" "explores"
   ;; Embark family
   "embark on" "start"
   "embarking on" "starting"
   "embarked on" "started"
   "embarks on" "starts"
   ;; Leverage family
   "leverage" "use"
   "leveraging" "using"
   "leveraged" "used"
   "leverages" "uses"
   ;; Utilize family
   "utilize" "use"
   "utilizing" "using"
   "utilized" "used"
   "utilizes" "uses"
   ;; Foster family
   "foster" "encourage"
   "fostering" "encouraging"
   "fostered" "encouraged"
   "fosters" "encourages"
   ;; Harness family
   "harness" "use"
   "harnessing" "using"
   "harnessed" "used"
   "harnesses" "uses"
   ;; Streamline family
   "streamline" "simplify"
   "streamlining" "simplifying"
   "streamlined" "simplified"
   "streamlines" "simplifies"
   ;; Other overused verbs
   "optimize" "improve"
   "optimizing" "improving"
   "optimized" "improved"
   "optimizes" "improves"
   "facilitate" "help"
   "facilitating" "helping"
   "facilitated" "helped"
   "facilitates" "helps"
   "empower" "enable"
   "empowering" "enabling"
   "empowered" "enabled"
   "empowers" "enables"
   "spearhead" "lead"
   "spearheading" "leading"
   "spearheaded" "led"
   "spearheads" "leads"
   "catalyze" "start"
   "catalyzing" "starting"
   "catalyzed" "started"
   "catalyzes" "starts"
   "revolutionize" "change"
   "revolutionizing" "changing"
   "revolutionized" "changed"
   "revolutionizes" "changes"
   "elevate" "improve"
   "elevating" "improving"
   "elevated" "improved"
   "elevates" "improves"
   "amplify" "increase"
   "amplifying" "increasing"
   "amplified" "increased"
   "amplifies" "increases"
   "unlock" "enable"
   "unlocking" "enabling"
   "unlocked" "enabled"
   "unlocks" "enables"
   "navigate" "handle"
   "navigating" "handling"
   "navigated" "handled"
   "navigates" "handles"})

(def OVERUSED_ADJECTIVES_PATTERNS
  "Map of AI-overused adjectives and adverbs to simpler alternatives.
   
   These patterns replace buzzword modifiers that AI models overuse with
   simpler, more natural alternatives. Adverbs like 'arguably' and 
   'undoubtedly' are removed entirely as they add unnecessary hedging.
   
   Includes:
   - Adjectives mapped to simpler equivalents (vibrant -> lively)
   - Adverbs mapped to empty string for removal (undoubtedly -> '')"
  {;; Adjectives
   "vibrant" "lively"
   "pivotal" "important"
   "crucial" "important"
   "intricate" "complex"
   "comprehensive" "complete"
   "robust" "strong"
   "seamless" "smooth"
   "holistic" "complete"
   "cutting-edge" "modern"
   "groundbreaking" "new"
   "innovative" "new"
   "revolutionary" "new"
   "transformative" "significant"
   "dynamic" "active"
   "strategic" "planned"
   "essential" "necessary"
   "vital" "important"
   "unparalleled" "unique"
   "compelling" "strong"
   "impactful" "effective"
   "game-changing" "significant"
   "best-in-class" "excellent"
   "world-class" "excellent"
   "mission-critical" "essential"
   "scalable" "flexible"
   ;; Adverbs to remove (empty replacement)
   "arguably " ""
   "undoubtedly " ""
   "certainly " ""
   "indeed " ""
   "notably " ""
   "essentially " ""
   "fundamentally " ""
   "inherently " ""})

(def OVERUSED_NOUNS_AND_TRANSITIONS_PATTERNS
  "Map of AI-overused nouns and transition words to simpler alternatives.
   
   These patterns replace pretentious nouns (tapestry, paradigm, ecosystem)
   and formal transition words (moreover, furthermore, hence) with simpler,
   more natural alternatives that real humans use in conversation.
   
   Includes:
   - Metaphorical nouns -> concrete alternatives (tapestry -> mix)
   - Business jargon -> plain language (synergy -> cooperation)
   - Formal transitions -> casual connectors (moreover -> also)"
  {;; Nouns (longer patterns first for proper matching)
   "tapestry of" "mix of"
   "tapestry" "mix"
   "landscape of" "field of"
   "landscape" "field"
   "realm of" "area of"
   "realm" "area"
   "paradigm shift" "change"
   "paradigm" "model"
   "synergy" "cooperation"
   "ecosystem" "system"
   "framework" "structure"
   "roadmap" "plan"
   "blueprint" "plan"
   "journey" "process"
   ;; Transitions
   "moreover" "also"
   "furthermore" "also"
   "hence" "so"
   "thus" "so"
   "consequently" "so"
   "accordingly" "so"
   "nevertheless" "but"
   "nonetheless" "but"
   "notwithstanding" "despite"
   "henceforth" "from now on"
   "thereby" "by doing this"
   "wherein" "where"
   "whereby" "by which"
   "heretofore" "before"
   "therein" "in this"})

(def OPENING_CLICHE_PATTERNS
  "Map of AI-typical opening cliches to their replacements or removal.
   
   These patterns identify and remove or simplify common AI introductory
   phrases that signal machine-generated content. Includes:
   - Time-based framing ('In today's digital age...')
   - Topic-staging phrases ('Let's dive into...')
   - Article starters ('This article explores...')
   - Hedged openings ('It goes without saying...')"
  {;; Time-based framing (remove or simplify)
   "In today's digital age, " ""
   "In today's world, " ""
   "In the modern age, " ""
   "In the current era, " ""
   "In the age of " "With "
   "In the ever-evolving landscape of " "In "
   "In the ever-changing world of " "In "
   "In the rapidly shifting landscape of " "In "
   "In the dynamic world of " "In "
   "In the realm of " "In "
   "In the world of " "In "
   "In the context of " "For "
   "In the field of " "In "
   ;; Topic-staging (remove)
   "Let's dive into " ""
   "Let's delve into " ""
   "Let's explore " ""
   "Let's take a closer look at " ""
   "Let's examine " ""
   "Let us explore " ""
   "Let us consider " ""
   "Let me explain " ""
   "Allow me to explain " ""
   ;; Article starters (remove or simplify)
   "This article explores " ""
   "This article examines " ""
   "This guide covers " ""
   "This post discusses " ""
   "In this article, we will " "We will "
   "In this guide, we will " "We will "
   ;; Hedged openings (remove)
   "It goes without saying that " ""
   "Needless to say, " ""
   "Without a doubt, " ""
   "There is no doubt that " ""})

(def CLOSING_CLICHE_PATTERNS
  "Map of AI-typical closing cliches to their removal (empty string).
   
   These patterns identify and remove common AI concluding phrases that
   signal machine-generated content. Includes:
   - Conclusion markers ('In conclusion...', 'To summarize...')
   - Reflective fillers ('As we've seen...', 'As mentioned earlier...')
   - Takeaway templates ('The key takeaway is...', 'This demonstrates...')"
  {;; Conclusion markers
   "In conclusion, " ""
   "In summary, " ""
   "To summarize, " ""
   "To sum up, " ""
   "To conclude, " ""
   "In closing, " ""
   "To wrap things up, " ""
   "All in all, " ""
   "All things considered, " ""
   "At the end of the day, " ""
   "In the final analysis, " ""
   "Ultimately, " ""
   ;; Reflective fillers
   "As we've seen, " ""
   "As we have seen, " ""
   "As discussed above, " ""
   "As mentioned earlier, " ""
   "As noted above, " ""
   "As previously mentioned, " ""
   "With all of this in mind, " ""
   "With that said, " ""
   "That said, " ""
   ;; Takeaway templates
   "The key takeaway is " ""
   "The main takeaway is " ""
   "The bottom line is " ""
   "What this means is " ""
   "This highlights the importance of " ""
   "This underscores the need for " ""
   "This demonstrates that " ""
   "This shows that " ""})

;; ============================================================================
;; Tier Constants (T4)
;; ============================================================================

(def SAFE_PATTERNS
  "Patterns that are unambiguously AI artifacts. Safe for arbitrary text.
   Includes: AI identity, refusal, knowledge, and punctuation patterns."
  (merge AI_IDENTITY_PATTERNS
         REFUSAL_PATTERNS
         KNOWLEDGE_PATTERNS
         PUNCTUATION_PATTERNS))

(def AGGRESSIVE_PATTERNS
  "Patterns that may match valid English. Opt-in only.
   Includes: hedging, overused verbs/adjectives/nouns, opening/closing cliches."
  (merge HEDGING_PATTERNS
         OVERUSED_VERBS_PATTERNS
         OVERUSED_ADJECTIVES_PATTERNS
         OVERUSED_NOUNS_AND_TRANSITIONS_PATTERNS
         OPENING_CLICHE_PATTERNS
         CLOSING_CLICHE_PATTERNS))

(def DEFAULT_PATTERNS
  "Combined map of all humanization patterns (safe + aggressive).
   Preserved for backward compatibility."
  (merge SAFE_PATTERNS AGGRESSIVE_PATTERNS))

;; ============================================================================
;; Helper Functions (T3, T5, T6, T8)
;; ============================================================================

(defn- word-char?
  "Returns true if c is a word character (letter, digit, or underscore)."
  [c]
  (or (Character/isLetterOrDigit ^char c) (= c \_)))

(defn- build-pattern-regex
  "Build a case-insensitive regex for a pattern string with conditional
   word boundaries. If the pattern starts/ends with a word character,
   add a boundary assertion on that side. If it starts/ends with
   whitespace or punctuation, match literally (no boundary needed)."
  ^Pattern [^String pattern]
  (let [escaped (Pattern/quote pattern)
        first-char (.charAt pattern 0)
        last-char (.charAt pattern (dec (.length pattern)))
        prefix (if (word-char? first-char) "(?<=^|[^\\p{L}\\p{N}_])" "")
        suffix (if (word-char? last-char) "(?=$|[^\\p{L}\\p{N}_])" "")]
    (Pattern/compile (str prefix escaped suffix)
                     (bit-or Pattern/CASE_INSENSITIVE Pattern/UNICODE_CASE))))

(defn- detect-case
  "Detect the case pattern of a matched string.
   Returns :upper, :title, or :lower."
  [^String s]
  (let [letters (filter #(Character/isLetter ^char %) s)]
    (cond
      (and (seq letters)
           (every? #(Character/isUpperCase ^char %) letters)) :upper
      (and (pos? (.length s))
           (Character/isUpperCase (.charAt s 0))) :title
      :else :lower)))

(defn- apply-case
  "Apply a case pattern to a replacement string."
  [^String replacement case-pattern]
  (case case-pattern
    :upper (str/upper-case replacement)
    :title (if (pos? (.length replacement))
             (str (str/upper-case (subs replacement 0 1))
                  (subs replacement 1))
             replacement)
    :lower replacement))

(defn- single-word?
  "Returns true if the string contains no whitespace."
  [^String s]
  (not (some #(Character/isWhitespace ^char %) s)))

(defn- replace-phrase
  "Replaces ALL occurrences of a pattern in text using boundary-aware,
   case-insensitive regex matching.
   
   For single-word-to-single-word replacements, preserves the case of
   the original match (title case, ALL CAPS, or lowercase).
   
   Uses conditional word boundaries: boundaries are only required on sides
   where the pattern starts/ends with a word character. Patterns ending with
   spaces, commas, or punctuation match literally on those sides."
  [^String text ^String pattern ^String replacement]
  (let [regex (build-pattern-regex pattern)
        preserve-case? (and (single-word? pattern)
                            (single-word? replacement)
                            (pos? (.length replacement)))]
    (if preserve-case?
      ;; T6: Case-preserving single-word replacement
      (let [matcher (.matcher regex text)
            sb (StringBuffer.)]
        (while (.find matcher)
          (let [matched (.group matcher)
                cased (apply-case replacement (detect-case matched))]
            (.appendReplacement matcher sb (Matcher/quoteReplacement cased))))
        (.appendTail matcher sb)
        (.toString sb))
      ;; Standard replacement (multi-word or empty replacement)
      (str/replace text regex (Matcher/quoteReplacement replacement)))))

;; ============================================================================
;; Exclusion Zones (T5)
;; ============================================================================

(def ^:private exclusion-regex
  "Regex matching regions that should never be humanized:
   - Fenced code blocks (```...```)
   - Inline code (`...`)
   - URLs (http:// or https://)
   - Email addresses"
  #"(?s)```.*?```|`[^`]+`|https?://\S+|[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}")

(defn- extract-exclusion-zones
  "Extract exclusion zones from text. Returns a vector of
   [start end original-text] triples."
  [^String text]
  (let [matcher (.matcher ^Pattern exclusion-regex text)]
    (loop [zones []]
      (if (.find matcher)
        (recur (conj zones [(.start matcher) (.end matcher) (.group matcher)]))
        zones))))

(defn- apply-with-exclusions
  "Apply a transformation function to text while protecting exclusion zones.
   Replaces zones with unique placeholders, applies f, then restores."
  [^String text f]
  (let [zones (extract-exclusion-zones text)]
    (if (empty? zones)
      (f text)
      (let [;; Generate unique placeholders
            placeholders (mapv (fn [i] (str "\u0000EXCL" i "\u0000")) (range (count zones)))
            ;; Replace zones with placeholders (reverse order to preserve indices)
            protected (reduce (fn [t [i [start end _orig]]]
                                (str (subs t 0 start)
                                     (nth placeholders i)
                                     (subs t end)))
                              text
                              (reverse (map-indexed vector zones)))
            ;; Apply transformation
            transformed (f protected)
            ;; Restore original zones
            restored (reduce (fn [t [i [_ _ orig]]]
                               (str/replace t (nth placeholders i) orig))
                             transformed
                             (map-indexed vector zones))]
        restored))))

;; ============================================================================
;; Artifact Cleanup (T8)
;; ============================================================================

(defn- cleanup-artifacts
  "Clean up artifacts caused by phrase removal:
   1. Collapse doubled punctuation (, , -> ,)
   2. Fix spacing before punctuation
   3. Normalize whitespace
   4. Strip leading punctuation/whitespace"
  [^String s]
  (-> s
      ;; Collapse doubled punctuation: ", ," or ",," -> ","
      (str/replace #",\s*," ",")
      (str/replace #"\.\s*\." ".")
      (str/replace #";\s*;" ";")
      ;; Fix spacing before punctuation
      (str/replace #"\s+([,.;:!?])" "$1")
      ;; Normalize whitespace
      (str/replace #"\s+" " ")
      (str/trim)
      ;; Strip leading punctuation/whitespace at string start
      (str/replace #"^[\s,;:]+\s*" "")))

;; ============================================================================
;; Public API
;; ============================================================================

(defn humanize-string
  "Removes AI-style phrases from text to make it sound more natural.
   
   Applies humanization patterns to the input string, replacing AI-specific
   phrases with more natural alternatives or removing them entirely. Patterns
   are applied in order of length (longest first) to avoid partial matches.
   
   Uses boundary-aware matching to prevent false positives inside longer words.
   Protects code blocks, inline code, and URLs from modification.
   
   Two calling conventions:
   
   (humanize-string s)                        ;; safe patterns only (default)
   (humanize-string s {:aggressive? true})    ;; all patterns
   (humanize-string s {:patterns custom-map}) ;; custom pattern map
   
   Legacy 2-arity still supported for backward compatibility:
   (humanize-string s pattern-map)            ;; uses provided map directly
   
   Returns:
   String. The humanized text. Non-string inputs returned unchanged."
  ([s] (humanize-string s {}))
  ([s opts-or-patterns]
   (if-not (string? s)
     s
     (let [patterns (cond
                      ;; opts map with :patterns key
                      (and (map? opts-or-patterns) (contains? opts-or-patterns :patterns))
                      (:patterns opts-or-patterns)
                      ;; opts map with :aggressive? flag
                      (and (map? opts-or-patterns) (:aggressive? opts-or-patterns))
                      DEFAULT_PATTERNS
                      ;; opts map (empty = safe only)
                      (and (map? opts-or-patterns) (empty? opts-or-patterns))
                      SAFE_PATTERNS
                      ;; legacy: raw pattern map passed directly
                      (map? opts-or-patterns)
                      opts-or-patterns
                      ;; fallback
                      :else SAFE_PATTERNS)
           sorted-patterns (sort-by (comp - count key) patterns)]
       (apply-with-exclusions
        s
        (fn [text]
          (-> (reduce (fn [t [pattern replacement]]
                        (replace-phrase t pattern replacement))
                      text
                      sorted-patterns)
              (cleanup-artifacts))))))))

(defn humanize-data
  "Recursively humanizes all strings in a data structure.
   
   Walks through maps, vectors, lists, and sets, applying humanization
   to all string values while preserving the structure and types of
   collections. Non-string values are left unchanged. Map keys are
   preserved unchanged - only values are processed.
   
   Params:
   `data` - Any. The data structure to humanize.
   `opts-or-patterns` - Map, optional. Either an opts map or a pattern map.
   
   Returns:
   Any. The data structure with all strings humanized."
  ([data] (humanize-data data {}))
  ([data opts-or-patterns]
   (cond
     (string? data) (humanize-string data opts-or-patterns)
     (map? data) (into {} (map (fn [[k v]] [k (humanize-data v opts-or-patterns)]) data))
     (vector? data) (mapv #(humanize-data % opts-or-patterns) data)
     (list? data) (apply list (map #(humanize-data % opts-or-patterns) data))
     (set? data) (into #{} (map #(humanize-data % opts-or-patterns) data))
     :else data)))

(defn humanizer
  "Creates a humanization function with configurable patterns.
   
   Returns a function that removes AI-style phrases from text and data
   structures. The returned function can process strings, maps, vectors,
   lists, and sets recursively.
   
   Params:
   `opts` - Map, optional. Configuration options:
     - `:aggressive?` - Boolean. Use all patterns (safe + aggressive).
       Default false (safe patterns only).
     - `:patterns` - Map. Custom pattern map. Overrides :aggressive?.
   
   Examples:
   ;; Default: safe patterns only
   (def h (humanizer))
   (h \"As an AI, I think this is correct.\")
   => \"I think this is correct.\"
   
   ;; Aggressive: all patterns
   (def h (humanizer {:aggressive? true}))
   (h \"We should leverage this.\")
   => \"We should use this.\"
   
   ;; Custom patterns
   (def h (humanizer {:patterns {\"custom\" \"replaced\"}}))
   
   Returns:
   Function. A humanization function `(fn [data] -> humanized-data)`."
  ([] (humanizer {}))
  ([opts]
   (fn [data]
     (humanize-data data opts))))
