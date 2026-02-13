(ns com.blockether.svar.internal.jsonish-test
  "Tests for SAP (Schemaless Adaptive Parsing) JSON parser.
   
   Ported from unbound.backend.shared.llm.internal.jsonish-test
   
   NOTE: These tests require Java to be compiled first.
   Run `make compile-java` in the svar package before running tests."
  (:require
   [lazytest.core :refer [defdescribe describe expect it throws?]]
   [com.blockether.svar.internal.jsonish :as sut]))

;; =============================================================================
;; parse-json function tests
;; =============================================================================

(defdescribe parse-json-test
  "Tests for parse-json SAP function"

  (describe "strict JSON parsing"
            (it "parses simple object without warnings"
                (expect (= {:value {:a 1}, :warnings []}
                           (sut/parse-json "{\"a\": 1}"))))

            (it "parses nested object without warnings"
                (expect (= {:value {:a {:b 2}}, :warnings []}
                           (sut/parse-json "{\"a\": {\"b\": 2}}"))))

            (it "parses array without warnings"
                (expect (= {:value {:items [1 2 3]}, :warnings []}
                           (sut/parse-json "{\"items\": [1, 2, 3]}")))))

  (describe "key normalization (snake_case to kebab-case)"
            (it "converts underscores to dashes in simple keys"
                (let [result (sut/parse-json "{\"key_points\": [\"a\", \"b\"]}")]
                  (expect (= {:key-points ["a" "b"]} (:value result)))))

            (it "converts underscores to dashes in nested keys"
                (let [result (sut/parse-json "{\"outer_key\": {\"inner_key\": 1}}")]
                  (expect (= {:outer-key {:inner-key 1}} (:value result)))))

            (it "converts underscores in array of objects"
                (let [result (sut/parse-json "{\"topics\": [{\"topic_name\": \"A\", \"key_points\": [\"x\"]}]}")]
                  (expect (= {:topics [{:topic-name "A" :key-points ["x"]}]} (:value result)))))

            (it "handles mixed keys with and without underscores"
                (let [result (sut/parse-json "{\"simple\": 1, \"with_underscore\": 2, \"multi_word_key\": 3}")]
                  (expect (= {:simple 1 :with-underscore 2 :multi-word-key 3} (:value result)))))

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
            (it "extracts JSON from markdown code block"
                (let [result (sut/parse-json "Here's the data:\n```json\n{\"x\":1}\n```")]
                  (expect (= {:x 1} (:value result)))
                  (expect (some #(re-find #"ExtractedFromMarkdown" %) (:warnings result)))))

            (it "extracts JSON from code block without language tag"
                (let [result (sut/parse-json "```\n{\"a\": 1}\n```")]
                  (expect (= {:a 1} (:value result))))))

  (describe "multi-object extraction"
            (it "extracts objects from text (returns vector of candidates)"
                (let [result (sut/parse-json "Response: {\"id\": 1} and {\"id\": 2}")]
                  (expect (or (map? (:value result))
                              (vector? (:value result))))))

            (it "handles multiple objects in text"
                (let [result (sut/parse-json "{\"status\": \"ok\"} then {\"data\": [1,2,3]}")]
                  (expect (or (map? (:value result))
                              (vector? (:value result)))))))

  (describe "error handling"
            (it "throws IllegalArgumentException on nil input"
                (expect (throws? IllegalArgumentException
                                 #(sut/parse-json nil))))

            (it "throws IllegalArgumentException on empty string"
                (expect (throws? IllegalArgumentException
                                 #(sut/parse-json ""))))))
