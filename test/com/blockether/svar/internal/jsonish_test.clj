(ns com.blockether.svar.internal.jsonish-test
  "Tests for SAP (Schemaless Adaptive Parsing) JSON parser.
   
   Ported from unbound.backend.shared.llm.internal.jsonish-test
   
   NOTE: These tests require Java to be compiled first.
   Run `make compile-java` in the svar package before running tests."
  (:require [lazytest.core :refer [defdescribe describe expect it throws?]]
            [com.blockether.svar.internal.jsonish :as sut]))

;; =============================================================================
;; parse-json function tests
;; =============================================================================

(defdescribe
  parse-json-test
  "Tests for parse-json SAP function"
  (describe "strict JSON parsing"
            (it "parses simple object without warnings"
                (expect (= {:value {:a 1} :warnings []} (sut/parse-json "{\"a\": 1}")))))
  (describe "key normalization (snake_case to kebab-case)"
            (it "converts underscores to dashes in simple keys"
                (let [result (sut/parse-json "{\"key_points\": [\"a\", \"b\"]}")]
                  (expect (= {:key-points ["a" "b"]} (:value result)))))
            (it "converts underscores to dashes in nested keys"
                (let [result (sut/parse-json "{\"outer_key\": {\"inner_key\": 1}}")]
                  (expect (= {:outer-key {:inner-key 1}} (:value result)))))
            (it "converts underscores in array of objects"
                (let [result (sut/parse-json
                               "{\"topics\": [{\"topic_name\": \"A\", \"key_points\": [\"x\"]}]}")]
                  (expect (= {:topics [{:topic-name "A" :key-points ["x"]}]} (:value result)))))
            (it "preserves dashes in keys that already have them"
                (let [result (sut/parse-json "{\"already-kebab\": 1}")]
                  (expect (= {:already-kebab 1} (:value result))))))
  (describe "malformed JSON with fixes"
            (it "parses unquoted keys with warnings"
                (let [result (sut/parse-json "{a: 1}")]
                  (expect (= {:a 1} (:value result)))
                  (expect (seq (:warnings result)))))
            (it "parses trailing comma with warnings"
                (let [result (sut/parse-json "{\"a\": 1,}")]
                  (expect (= {:a 1} (:value result)))
                  (expect (seq (:warnings result)))))
            (it "parses unquoted string values with warnings"
                (let [result (sut/parse-json "{\"name\": John}")]
                  (expect (= {:name "John"} (:value result)))
                  (expect (seq (:warnings result))))))
  (describe "markdown extraction"
            (it "extracts JSON from ```json fence"
                (let [result (sut/parse-json "Here's the data:\n```json\n{\"x\":1}\n```")]
                  (expect (= {:x 1} (:value result)))
                  (expect (some #(re-find #"ExtractedFromMarkdown" %) (:warnings result)))))
            (it "extracts JSON from untagged fence"
                (let [result (sut/parse-json "```\n{\"a\": 1}\n```")]
                  (expect (= {:a 1} (:value result)))))
            (it "extracts EDN from ```clojure fence"
                (let [result (sut/parse-json
                               "```clojure\n{:code [{:expr \"(+ 1 2)\" :time-ms 50}]}\n```")]
                  (expect (= {:code [{:expr "(+ 1 2)" :time-ms 50}]} (:value result))))))
  (describe "multi-object extraction"
            (it "extracts objects from text (returns vector of candidates)"
                (let [result (sut/parse-json "Response: {\"id\": 1} and {\"id\": 2}")]
                  (expect (= [{:id 1} {:id 2}] (:value result)))))
            (it "handles multiple objects in text"
                (let [result (sut/parse-json "{\"status\": \"ok\"} then {\"data\": [1,2,3]}")]
                  (expect (= {:status "ok"} (:value result))))))
  (describe "error handling"
            (it "throws IllegalArgumentException on nil input"
                (expect (throws? IllegalArgumentException #(sut/parse-json nil))))
            (it "throws IllegalArgumentException on empty string"
                (expect (throws? IllegalArgumentException #(sut/parse-json ""))))))

;; =============================================================================
;; EDN / Clojure-style lenient parsing tests
;;
;; GLM-5.1 and other reasoning models frequently emit Clojure/EDN syntax
;; instead of strict JSON — keyword keys (:code, :expr), keyword values
;; (:low, :deep), trailing whitespace/newlines before the opening brace.
;; These tests pin the exact behaviours we rely on in production.
;; =============================================================================

(defdescribe
  edn-lenient-test
  "EDN / Clojure-style lenient parsing — real model output shapes"
  (describe
    "EDN keyword keys"
    (it "parses top-level EDN keyword key :code"
        (let [result (sut/parse-json "{:code [{:expr \"(def foo 42)\" :time-ms 100}]}")]
          (expect (= {:code [{:expr "(def foo 42)" :time-ms 100}]} (:value result)))))
    (it "parses leading-newline EDN — real model output shape"
        (let
          [result
           (sut/parse-json
             "\n{:code [{:expr \"(list-dir \\\".\\\" {:glob \\\"**/*.css\\\"})\" :time-ms 2000}]}")]
          (expect (= {:code [{:expr "(list-dir \".\" {:glob \"**/*.css\"})" :time-ms 2000}]}
                     (:value result)))))
    (it "does not strip colons that are the key-value separator"
        ;; `:code` key + value `[]` — the separator `:` must not be eaten
        (let [result (sut/parse-json "{:code []}")]
          (expect (= {:code []} (:value result))))))
  (describe
    "EDN keyword values"
    (it "parses :low as the string \"low\""
        (let [result (sut/parse-json "{:next {:reasoning :low}}")]
          (expect (= {:next {:reasoning "low"}} (:value result)))))
    (it "parses keyword value after a JSON-quoted key"
        (let [result (sut/parse-json "{\"mode\": :streaming}")]
          (expect (= {:mode "streaming"} (:value result)))))
    (it "parses keyword values inside an array"
        (let [result (sut/parse-json "{:tags [:alpha :beta :gamma]}")]
          (expect (= {:tags ["alpha" "beta" "gamma"]} (:value result)))))
    (it "parses keyword values with hyphens and dots"
        (let [result (sut/parse-json "{:content-type :application/json :encoding :utf-8}")]
          (expect (= {:content-type "application/json" :encoding "utf-8"} (:value result)))))
    (it "parses keyword values with namespaces (slash-separated)"
        (let [result (sut/parse-json "{:type :spec.type/string :required :spec/required}")]
          (expect (= {:type "spec.type/string" :required "spec/required"} (:value result)))))
    (it "parses keyword value followed by another EDN key (implicit comma)"
        (let [result (sut/parse-json "{:status :ok :code 200 :msg \"success\"}")]
          (expect (= {:status "ok" :code 200 :msg "success"} (:value result)))))
    (it "parses nil-like keyword :nil as the string \"nil\" not actual null"
        (let [result (sut/parse-json "{:x :nil}")]
          (expect (= {:x "nil"} (:value result))))))
  (describe "incomplete / truncated inputs (lenient end)"
            (it "parses map with missing outer closing brace"
                (let [result (sut/parse-json "{:code [{:expr \"foo\" :time-ms 10}]")]
                  (expect (= {:code [{:expr "foo" :time-ms 10}]} (:value result)))))
            (it "parses deeply nested EDN with missing closing braces"
                (let [result (sut/parse-json "{:next {:reasoning :deep")]
                  (expect (= {:next {:reasoning "deep"}} (:value result)))))
            (it "parses array with missing closing bracket"
                (let [result (sut/parse-json "[{:expr \"x\" :time-ms 10}")]
                  (expect (= [{:expr "x" :time-ms 10}] (:value result)))))
            (it "parses empty map" (expect (= {:value {} :warnings []} (sut/parse-json "{}")))))
  (describe "mixed JSON + EDN (real production variants)"
            (it "handles JSON keys mixed with EDN keyword values"
                (let [result (sut/parse-json "{\"reasoning\": :low, \"code\": []}")]
                  (expect (= "low" (get (:value result) :reasoning))))))
  (describe
    "deeply nested structures"
    (it "parses deeply truncated nested structure"
        (let
          [result
           (sut/parse-json
             "{:code [{:expr \"(+ 1 2)\" :time-ms 50}] :next {:reasoning :deep :context {:depth 3")]
          (expect (= {:code [{:expr "(+ 1 2)" :time-ms 50}]
                      :next {:reasoning "deep" :context {:depth 3}}}
                     (:value result))))))
  (describe
    "brutally nested / complex real-world shapes"
    (it "parses clojure fence with multi-line EDN (newlines inside fence)"
        (let [input
              (str "```clojure\n" "{:code\n"
                   " [{:expr \"(+ 1 2)\"\n" "   :time-ms 50}\n"
                   "  {:expr \"(* 3 4)\"\n" "   :time-ms 30}]\n"
                   " :final {:answer \"done\"}}\n" "```")

              result
              (sut/parse-json input)]

          (expect (= 2 (count (get-in (:value result) [:code]))))
          (expect (= "(+ 1 2)" (get-in (:value result) [:code 0 :expr])))
          (expect (= 30 (get-in (:value result) [:code 1 :time-ms])))
          (expect (= "done" (get-in (:value result) [:final :answer])))))
    (it
      "parses EDN with string values containing colons, braces, brackets"
      (let
        [result
         (sut/parse-json
           "{:expr \"(let [{:keys [a b]} m] (str a \\\":\\\" b))\" :note \"handles {:keys} destructuring [and arrays]\"}")]
        (expect (= "(let [{:keys [a b]} m] (str a \":\" b))" (:expr (:value result))))
        (expect (= "handles {:keys} destructuring [and arrays]" (:note (:value result))))))
    (it
      "parses interleaved JSON + EDN keys in nested structure"
      (let
        [result
         (sut/parse-json
           "{\"outer\": {:inner-edn [{:x 1 :y 2} {:x 3 :y 4}] :meta {\"created\": \"2024-01-01\" :status :active}}}")]
        (expect (= 1 (get-in (:value result) [:outer :inner-edn 0 :x])))
        (expect (= 4 (get-in (:value result) [:outer :inner-edn 1 :y])))
        (expect (= "2024-01-01" (get-in (:value result) [:outer :meta :created])))
        (expect (= "active" (get-in (:value result) [:outer :meta :status])))))))

;; =============================================================================
;; Unicode escape handling (regression)
;; =============================================================================
;;
;; The hot `parseQuotedString` path previously decoded `\uXXXX` with no bounds
;; or hex validation: a malformed or truncated escape threw, the blanket catch
;; turned it into a whole-response failure, and parse-json fell back to the raw
;; string — which downstream becomes :svar.spec/schema-rejected. One bad escape
;; must NOT sink an otherwise-parseable object.

(defdescribe
  parse-json-unicode-test
  "parse-json must decode valid \\uXXXX escapes and tolerate malformed ones
   (emit a literal rather than rejecting the whole response)."
  (describe "valid escapes decode"
            (it "decodes a basic \\uXXXX escape"
                (expect (= {:a "A"} (:value (sut/parse-json "{\"a\": \"\\u0041\"}")))))
            (it "decodes a surrogate-pair emoji"
                (expect (= {:a "🚀"} (:value (sut/parse-json "{\"a\": \"\\ud83d\\ude80\"}"))))))
  (describe "malformed escapes do not reject the whole object"
            (it "tolerates a truncated \\u escape (too few hex digits before end)"
                (let [v (:value (sut/parse-json "{\"a\": \"x\\u00\"}"))]
                  (expect (map? v)) ;; NOT the raw-string fallback
                  (expect (contains? v :a))))
            (it "tolerates a non-hex \\u escape"
                (let [v (:value (sut/parse-json "{\"a\": \"\\uZZZZ\"}"))]
                  (expect (map? v))
                  (expect (contains? v :a))))
            (it "a bad escape in one field still yields the sibling fields"
                (let [v (:value (sut/parse-json "{\"a\": \"\\uXY\", \"b\": 2}"))]
                  (expect (map? v))
                  (expect (= 2 (:b v)))))))

;; =============================================================================
;; parse-partial function tests
;; =============================================================================

(defdescribe
  parse-partial-test
  "Streaming prefixes: the parser returns what is complete and the text so far
   of a cut-off string."
  (describe "cut-off values"
            (it "returns the text so far of a cut-off string"
                (expect (= {:answer "o"} (sut/parse-partial "{\"answer\": \"o")))
                (expect (= {:code "(+ 1"} (sut/parse-partial "{\"code\": \"(+ 1"))))
            (it "drops a cut-off escape"
                (expect (= {:a "x"} (sut/parse-partial "{\"a\": \"x\\")))
                (expect (= {:a "x"} (sut/parse-partial "{\"a\": \"x\\u00"))))
            (it "drops a key without a value"
                (expect (= {:a 1} (sut/parse-partial "{\"a\": 1, \"b\": ")))
                (expect (= {:a 1} (sut/parse-partial "{\"a\": 1, \"b"))))
            (it "drops a literal or number that can still grow"
                (expect (= {} (sut/parse-partial "{\"a\": tr")))
                (expect (= {} (sut/parse-partial "{\"a\": 12")))
                (expect (= {:a 12} (sut/parse-partial "{\"a\": 12,"))))
            (it "keeps nested values"
                (expect (= {:a {:b "c"}} (sut/parse-partial "{\"a\": {\"b\": \"c")))
                (expect (= {:a ["x" "y"]} (sut/parse-partial "{\"a\": [\"x\", \"y"))))
            (it "reads EDN keyword keys and values"
                (expect (= {:a "low" :b "x"} (sut/parse-partial "{:a :low, :b \"x")))))
  (describe "text around the object"
            (it "skips prose before the first brace"
                (expect (= {:a "x"} (sut/parse-partial "Thinking... {\"a\": \"x"))))
            (it "skips an opening markdown fence"
                (expect (= {:a "x"} (sut/parse-partial "```json\n{\"a\": \"x"))))
            (it "returns nil before any object starts"
                (expect (nil? (sut/parse-partial "no json yet")))
                (expect (nil? (sut/parse-partial "")))
                (expect (nil? (sut/parse-partial nil)))))
  (describe "complete input"
            (it "returns the same map as parse-json"
                (expect (= {:a "done" :b [1 2]}
                           (sut/parse-partial "{\"a\": \"done\", \"b\": [1, 2]}"))))))
