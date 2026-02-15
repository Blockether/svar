(ns com.blockether.svar.internal.humanize-test
  "Tests for AI response humanization."
  (:require
   [clojure.string :as str]
   [com.blockether.svar.internal.humanize :as sut]
   [lazytest.core :refer [defdescribe describe expect it]]))

;; =============================================================================
;; Constants
;; =============================================================================

(defdescribe constants-test
  "Tests for pattern constants"

  (it "DEFAULT_PATTERNS is a non-empty map"
      (expect (map? sut/DEFAULT_PATTERNS))
      (expect (pos? (count sut/DEFAULT_PATTERNS))))

  (it "SAFE_PATTERNS is a non-empty map"
      (expect (map? sut/SAFE_PATTERNS))
      (expect (pos? (count sut/SAFE_PATTERNS))))

  (it "AGGRESSIVE_PATTERNS is a non-empty map"
      (expect (map? sut/AGGRESSIVE_PATTERNS))
      (expect (pos? (count sut/AGGRESSIVE_PATTERNS))))

  (it "DEFAULT_PATTERNS equals merge of SAFE + AGGRESSIVE"
      (expect (= sut/DEFAULT_PATTERNS (merge sut/SAFE_PATTERNS sut/AGGRESSIVE_PATTERNS))))

  (it "SAFE_PATTERNS contains AI identity, refusal, knowledge, punctuation"
      (doseq [k (keys sut/AI_IDENTITY_PATTERNS)]
        (expect (contains? sut/SAFE_PATTERNS k)))
      (doseq [k (keys sut/REFUSAL_PATTERNS)]
        (expect (contains? sut/SAFE_PATTERNS k)))
      (doseq [k (keys sut/KNOWLEDGE_PATTERNS)]
        (expect (contains? sut/SAFE_PATTERNS k)))
      (doseq [k (keys sut/PUNCTUATION_PATTERNS)]
        (expect (contains? sut/SAFE_PATTERNS k))))

  (it "each pattern category is a non-empty map"
      (doseq [patterns [sut/AI_IDENTITY_PATTERNS
                        sut/REFUSAL_PATTERNS
                        sut/HEDGING_PATTERNS
                        sut/KNOWLEDGE_PATTERNS
                        sut/PUNCTUATION_PATTERNS
                        sut/OVERUSED_VERBS_PATTERNS
                        sut/OVERUSED_ADJECTIVES_PATTERNS
                        sut/OVERUSED_NOUNS_AND_TRANSITIONS_PATTERNS
                        sut/OPENING_CLICHE_PATTERNS
                        sut/CLOSING_CLICHE_PATTERNS]]
        (expect (map? patterns))
        (expect (pos? (count patterns)))))

  (it "no identity patterns exist (key == value)"
      (doseq [[k v] sut/DEFAULT_PATTERNS]
        (expect (not= k v)))))

;; =============================================================================
;; T1: Regression test corpus -- must-change strings (safe mode)
;; =============================================================================

(defdescribe must-change-safe-test
  "Strings that must be changed in SAFE mode (default)"

  (describe "AI identity removal"
            (it "strips 'As an AI, I'"
                (expect (= "I think this is correct."
                           (sut/humanize-string "As an AI, I think this is correct."))))

            (it "strips 'As a language model, '"
                (expect (= "I think this is correct."
                           (sut/humanize-string "As a language model, I think this is correct."))))

            (it "strips 'I'm a large language model,'"
                ;; Pattern "I'm a large language model," -> "I'm", so "I'm + I'm"
                (expect (= "I'm I'm here to help."
                           (sut/humanize-string "I'm a large language model, I'm here to help."))))

            (it "strips 'As an AI assistant, I'"
                (expect (= "I can help with that."
                           (sut/humanize-string "As an AI assistant, I can help with that."))))

            (it "strips 'Being an AI'"
                (expect (= "has its limitations."
                           (sut/humanize-string "Being an AI has its limitations.")))))

  (describe "refusal simplification"
            (it "simplifies 'I cannot and will not'"
                (expect (= "I won't do that."
                           (sut/humanize-string "I cannot and will not do that."))))

            (it "simplifies 'I'm unable to'"
                (expect (= "I can't do that."
                           (sut/humanize-string "I'm unable to do that."))))

            (it "simplifies 'I am unable to provide'"
                (expect (= "I can't provide that information."
                           (sut/humanize-string "I am unable to provide that information."))))

            (it "simplifies 'I cannot assist with'"
                (expect (= "I can't help with that."
                           (sut/humanize-string "I cannot assist with that.")))))

  (describe "knowledge disclaimer removal"
            (it "strips 'Based on my training data'"
                (expect (= "the answer is yes."
                           (sut/humanize-string "Based on my training data, the answer is yes."))))

            (it "replaces 'my training data' with 'available information'"
                (expect (= "Check available information for details."
                           (sut/humanize-string "Check my training data for details."))))

            (it "strips 'As of my last update'"
                (expect (= "the price was $50."
                           (sut/humanize-string "As of my last update, the price was $50."))))

            (it "replaces 'my knowledge cutoff' with 'current information'"
                (expect (= "current information says otherwise."
                           (sut/humanize-string "my knowledge cutoff says otherwise.")))))

  (describe "punctuation normalization"
            (it "replaces em dash with comma"
                (expect (= "this, that and more."
                           (sut/humanize-string "this — that and more."))))

            (it "replaces double hyphen with comma"
                (expect (= "this, that and more."
                           (sut/humanize-string "this -- that and more."))))))

;; =============================================================================
;; T1: Regression test corpus -- must-change strings (aggressive mode)
;; =============================================================================

(defdescribe must-change-aggressive-test
  "Strings that must be changed in AGGRESSIVE mode"

  (describe "hedging removal"
            (it "strips 'It's important to note that'"
                (expect (= "the sky is blue."
                           (sut/humanize-string "It's important to note that the sky is blue." {:aggressive? true}))))

            (it "strips 'Please note that'"
                (expect (= "this is true."
                           (sut/humanize-string "Please note that this is true." {:aggressive? true})))))

  (describe "overused verbs"
            (it "replaces 'delve into' with 'explore'"
                (expect (= "We should explore this topic."
                           (sut/humanize-string "We should delve into this topic." {:aggressive? true}))))

            (it "replaces 'leverage' with 'use'"
                (expect (= "We should use this tool."
                           (sut/humanize-string "We should leverage this tool." {:aggressive? true}))))

            (it "replaces 'utilize' with 'use'"
                (expect (= "You can use this feature."
                           (sut/humanize-string "You can utilize this feature." {:aggressive? true})))))

  (describe "overused adjectives"
            (it "replaces 'vibrant' with 'lively'"
                (expect (= "A lively community."
                           (sut/humanize-string "A vibrant community." {:aggressive? true}))))

            (it "replaces 'robust' with 'strong'"
                (expect (= "A strong solution."
                           (sut/humanize-string "A robust solution." {:aggressive? true})))))

  (describe "nouns and transitions"
            (it "replaces 'moreover' with 'also'"
                (expect (= "also, this is true."
                           (sut/humanize-string "moreover, this is true." {:aggressive? true}))))

            (it "replaces 'paradigm' with 'model'"
                (expect (= "A new model."
                           (sut/humanize-string "A new paradigm." {:aggressive? true})))))

  (describe "opening cliches"
            (it "removes 'In today's digital age, '"
                (expect (= "we use computers."
                           (sut/humanize-string "In today's digital age, we use computers." {:aggressive? true})))))

  (describe "closing cliches"
            (it "removes 'In conclusion, '"
                (expect (= "this works."
                           (sut/humanize-string "In conclusion, this works." {:aggressive? true}))))))

;; =============================================================================
;; T1: Regression test corpus -- must-NOT-change strings
;; =============================================================================

(defdescribe must-not-change-safe-test
  "Strings that must NOT be changed in SAFE mode (default)."

  (it "preserves 'Foster City' (proper noun)"
      (expect (= "I live in Foster City"
                 (sut/humanize-string "I live in Foster City"))))

  (it "preserves 'navigation menu' (not a substring match)"
      (expect (= "The navigation menu works"
                 (sut/humanize-string "The navigation menu works"))))

  (it "preserves 'React framework' (technical term)"
      (expect (= "The React framework is popular"
                 (sut/humanize-string "The React framework is popular"))))

  (it "preserves 'Java ecosystem' (technical term)"
      (expect (= "The Java ecosystem is large"
                 (sut/humanize-string "The Java ecosystem is large"))))

  (it "preserves 'elevated permissions' (Linux term)"
      (expect (= "elevated permissions are needed"
                 (sut/humanize-string "elevated permissions are needed"))))

  (it "preserves 'leverage ratio' (finance term)"
      (expect (= "The leverage ratio is 3:1"
                 (sut/humanize-string "The leverage ratio is 3:1"))))

  (it "preserves 'Tuscan landscape' (geography)"
      (expect (= "The Tuscan landscape is beautiful"
                 (sut/humanize-string "The Tuscan landscape is beautiful"))))

  (it "preserves 'journey from Paris' (literal journey)"
      (expect (= "The journey from Paris took 3 hours"
                 (sut/humanize-string "The journey from Paris took 3 hours"))))

  (it "preserves 'Typically' in normal text"
      (expect (= "Typically this takes 2 days"
                 (sut/humanize-string "Typically this takes 2 days"))))

  (it "preserves 'In conclusion' in normal text"
      (expect (= "In conclusion, the results are clear."
                 (sut/humanize-string "In conclusion, the results are clear."))))

  (it "preserves 'robust' in normal text"
      (expect (= "A robust solution is needed."
                 (sut/humanize-string "A robust solution is needed."))))

  (it "preserves plain English text untouched"
      (expect (= "The quick brown fox jumps over the lazy dog."
                 (sut/humanize-string "The quick brown fox jumps over the lazy dog.")))))

;; =============================================================================
;; T3: Boundary-aware matching
;; =============================================================================

(defdescribe boundary-matching-test
  "Tests for word boundary-aware matching"

  (it "does not match 'empower' inside 'empowerment'"
      (expect (= "empowerment of youth"
                 (sut/humanize-string "empowerment of youth" {:aggressive? true}))))

  (it "does match standalone 'empower'"
      (expect (= "enable the team"
                 (sut/humanize-string "empower the team" {:aggressive? true}))))

  (it "replaces ALL occurrences (not just first)"
      (expect (= "explore X, explore Y, explore Z"
                 (sut/humanize-string "delve into X, delve into Y, delve into Z" {:aggressive? true}))))

  (it "handles patterns with trailing space correctly"
      (expect (= "the data is clear."
                 (sut/humanize-string "arguably the data is clear." {:aggressive? true}))))

  (it "handles patterns with comma-space correctly"
      (expect (= "this works."
                 (sut/humanize-string "In conclusion, this works." {:aggressive? true}))))

  (it "handles em-dash patterns correctly"
      (expect (= "this, that"
                 (sut/humanize-string "this — that"))))

  (it "handles case-insensitive matching"
      (expect (= "I think yes."
                 (sut/humanize-string "AS AN AI, I think yes."))))

  (it "does not match 'foster' inside 'fostering' incorrectly"
      (expect (= "encouraging collaboration"
                 (sut/humanize-string "fostering collaboration" {:aggressive? true})))))

;; =============================================================================
;; T4: Tier split
;; =============================================================================

(defdescribe tier-split-test
  "Tests for safe/aggressive tier split"

  (describe "safe mode (default)"
            (it "applies AI identity patterns"
                (expect (= "I think yes."
                           (sut/humanize-string "As an AI, I think yes."))))

            (it "applies refusal patterns"
                (expect (= "I won't do that."
                           (sut/humanize-string "I cannot and will not do that."))))

            (it "applies knowledge patterns"
                (expect (= "Check available information."
                           (sut/humanize-string "Check my training data."))))

            (it "applies punctuation patterns"
                (expect (= "this, that"
                           (sut/humanize-string "this — that"))))

            (it "does NOT apply hedging patterns"
                (expect (= "It's important to note that the sky is blue."
                           (sut/humanize-string "It's important to note that the sky is blue."))))

            (it "does NOT apply verb patterns"
                (expect (= "We should leverage this."
                           (sut/humanize-string "We should leverage this."))))

            (it "does NOT apply adjective patterns"
                (expect (= "A robust solution."
                           (sut/humanize-string "A robust solution."))))

            (it "does NOT apply noun/transition patterns"
                (expect (= "moreover, this is true."
                           (sut/humanize-string "moreover, this is true."))))

            (it "does NOT apply opening cliche patterns"
                (expect (= "In today's digital age, we code."
                           (sut/humanize-string "In today's digital age, we code."))))

            (it "does NOT apply closing cliche patterns"
                (expect (= "In conclusion, done."
                           (sut/humanize-string "In conclusion, done.")))))

  (describe "aggressive mode"
            (it "applies all safe patterns plus aggressive"
                (expect (= "I think yes."
                           (sut/humanize-string "As an AI, I think yes." {:aggressive? true}))))

            (it "applies hedging patterns"
                (expect (= "the sky is blue."
                           (sut/humanize-string "It's important to note that the sky is blue." {:aggressive? true}))))

            (it "applies verb patterns"
                (expect (= "We should use this."
                           (sut/humanize-string "We should leverage this." {:aggressive? true}))))

            (it "applies adjective patterns"
                (expect (= "A strong solution."
                           (sut/humanize-string "A robust solution." {:aggressive? true}))))

            (it "applies opening cliche patterns"
                (expect (= "we code."
                           (sut/humanize-string "In today's digital age, we code." {:aggressive? true}))))

            (it "applies closing cliche patterns"
                (expect (= "done."
                           (sut/humanize-string "In conclusion, done." {:aggressive? true})))))

  (describe "custom patterns"
            (it "works with :patterns key"
                (expect (= "world"
                           (sut/humanize-string "hello" {:patterns {"hello" "world"}}))))

            (it "legacy raw map still works"
                (expect (= "world"
                           (sut/humanize-string "hello" {"hello" "world"}))))))

;; =============================================================================
;; T5: Exclusion zones
;; =============================================================================

(defdescribe exclusion-zones-test
  "Tests for code/URL exclusion zones"

  (it "preserves inline code in aggressive mode"
      (expect (= "`optimize(query)` is a function"
                 (sut/humanize-string "`optimize(query)` is a function" {:aggressive? true}))))

  (it "preserves URLs"
      (expect (= "See https://example.com/navigate/to/page for details"
                 (sut/humanize-string "See https://example.com/navigate/to/page for details" {:aggressive? true}))))

  (it "preserves fenced code blocks"
      (let [input "Before ```\noptimize()\nleverage()\n``` After"
            result (sut/humanize-string input {:aggressive? true})]
        (expect (str/includes? result "```\noptimize()\nleverage()\n```"))))

  (it "preserves email addresses"
      (expect (= "Contact admin@example.com for help."
                 (sut/humanize-string "Contact admin@example.com for help." {:aggressive? true}))))

  (it "still replaces patterns outside exclusion zones"
      (let [result (sut/humanize-string "We should leverage `optimize()` to use it" {:aggressive? true})]
        (expect (str/includes? result "`optimize()`"))
        (expect (not (str/includes? result "leverage"))))))

;; =============================================================================
;; T6: Case-preserving replacement
;; =============================================================================

(defdescribe case-preservation-test
  "Tests for case-preserving single-word replacement"

  (it "preserves title case"
      (expect (= "Explore deeper into the topic."
                 (sut/humanize-string "Delve deeper into the topic." {:aggressive? true}))))

  (it "preserves ALL CAPS"
      (expect (= "USE this tool"
                 (sut/humanize-string "LEVERAGE this tool" {:aggressive? true}))))

  (it "preserves lowercase"
      (expect (= "use this tool"
                 (sut/humanize-string "leverage this tool" {:aggressive? true}))))

  (it "does not apply case preservation to multi-word patterns"
      (expect (= "explore this topic"
                 (sut/humanize-string "Delve into this topic" {:aggressive? true}))))

  (it "does not apply case preservation to empty replacements"
      (expect (= "the sky is blue."
                 (sut/humanize-string "It's important to note that the sky is blue." {:aggressive? true})))))

;; =============================================================================
;; T8: Artifact cleanup
;; =============================================================================

(defdescribe artifact-cleanup-test
  "Tests for post-removal artifact cleanup"

  (it "strips leading comma at string start after AI identity removal"
      (expect (= "I think this is correct."
                 (sut/humanize-string "As an AI, I think this is correct."))))

  (it "does not attempt capitalization"
      (let [result (sut/humanize-string "As an AI, the answer is yes.")]
        (expect (= "the answer is yes." result)))))

;; =============================================================================
;; humanize-data
;; =============================================================================

(defdescribe humanize-data-test
  "Tests for humanize-data function"

  (describe "maps"
            (it "humanizes string values in maps"
                (let [result (sut/humanize-data {:answer "As an AI, I think yes."})]
                  (expect (= "I think yes." (:answer result)))))

            (it "preserves map keys unchanged"
                (let [result (sut/humanize-data {:vibrant "vibrant"} {:aggressive? true})]
                  (expect (contains? result :vibrant))
                  (expect (= "lively" (:vibrant result))))))

  (describe "vectors"
            (it "humanizes strings in vectors"
                (let [result (sut/humanize-data ["As an AI, yes." "no"])]
                  (expect (= "yes." (first result)))
                  (expect (= "no" (second result))))))

  (describe "nested structures"
            (it "humanizes deeply nested strings"
                (let [result (sut/humanize-data {:outer {:inner "As an AI, sure."}})]
                  (expect (= "sure." (get-in result [:outer :inner]))))))

  (describe "non-string values"
            (it "leaves numbers unchanged"
                (expect (= {:count 42} (sut/humanize-data {:count 42}))))

            (it "leaves booleans unchanged"
                (expect (= {:flag true} (sut/humanize-data {:flag true})))))

  (describe "with opts"
            (it "passes aggressive mode through"
                (let [result (sut/humanize-data {:msg "A robust solution."} {:aggressive? true})]
                  (expect (= "A strong solution." (:msg result)))))

            (it "passes custom patterns through"
                (let [result (sut/humanize-data {:msg "hello"} {:patterns {"hello" "world"}})]
                  (expect (= "world" (:msg result)))))

            (it "legacy raw map still works"
                (let [result (sut/humanize-data {:msg "hello"} {"hello" "world"})]
                  (expect (= "world" (:msg result)))))))

;; =============================================================================
;; humanizer factory
;; =============================================================================

(defdescribe humanizer-test
  "Tests for humanizer factory function"

  (it "default humanizer uses safe patterns"
      (let [h (sut/humanizer)]
        (expect (= "I think yes." (h "As an AI, I think yes.")))
        ;; Should NOT replace aggressive patterns
        (expect (= "A robust solution." (h "A robust solution.")))))

  (it "aggressive humanizer applies all patterns"
      (let [h (sut/humanizer {:aggressive? true})]
        (expect (= "I think yes." (h "As an AI, I think yes.")))
        (expect (= "A strong solution." (h "A robust solution.")))))

  (it "custom patterns humanizer works"
      (let [h (sut/humanizer {:patterns {"foo" "bar"}})]
        (expect (= "bar baz" (h "foo baz")))))

  (it "works on data structures"
      (let [h (sut/humanizer)]
        (expect (= {:a "I think yes."} (h {:a "As an AI, I think yes."})))))

  (it "works on nested data structures"
      (let [h (sut/humanizer {:aggressive? true})]
        (expect (= {:outer {:inner "A strong solution."}}
                   (h {:outer {:inner "A robust solution."}})))))

  (describe "edge cases"
            (it "returns non-strings unchanged"
                (expect (= 42 (sut/humanize-string 42)))
                (expect (= nil (sut/humanize-string nil)))
                (expect (= :keyword (sut/humanize-string :keyword))))

            (it "handles empty string"
                (expect (= "" (sut/humanize-string ""))))))
