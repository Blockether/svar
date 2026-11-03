(ns com.blockether.svar.internal.spec-test
  "Tests for structured output specification DSL.
   
   Ported from unbound.backend.shared.llm.internal.spec-test"
  (:require [clojure.string :as str]
            [lazytest.core :refer [defdescribe describe expect it throws?]]
            [com.blockether.svar.internal.spec :as sut])
  (:import [java.time LocalDate OffsetDateTime]))

(defdescribe
  spec->str-test
  "Tests for BAML format generation"
  (describe "simple non-namespaced keywords"
            (it "converts simple spec to BAML format"
                (let [result (#'sut/spec->str
                              (sut/spec (sut/field ::sut/name :name
                                                   ::sut/type :spec.type/string
                                                   ::sut/cardinality :spec.cardinality/one
                                                   ::sut/description "User's name")
                                        (sut/field ::sut/name :age
                                                   ::sut/type :spec.type/int
                                                   ::sut/cardinality :spec.cardinality/one
                                                   ::sut/description "Age")))]
                  (expect (str/includes? result "// User's name (required)"))
                  (expect (str/includes? result "name: string,"))
                  (expect (str/includes? result "// Age (required)"))
                  (expect (str/includes? result "age: int,")))))
  (describe "with enum values"
            (it "includes union literals in output"
                (let [result (#'sut/spec->str
                              (sut/spec (sut/field ::sut/name :role
                                                   ::sut/type :spec.type/string
                                                   ::sut/cardinality :spec.cardinality/one
                                                   ::sut/description "Role"
                                                   ::sut/values {"admin" "Full system access"
                                                                 "user" "Standard user access"})))]
                  (expect (str/includes? result "// Role (required)"))
                  (expect (str/includes? result "admin"))
                  (expect (str/includes? result "user"))
                  (expect (str/includes? result " or ")))))
  (describe "optional field"
            (it "appends 'or null' to type for optional fields"
                (let [result (#'sut/spec->str
                              (sut/spec (sut/field ::sut/name :nick
                                                   ::sut/type :spec.type/string
                                                   ::sut/cardinality :spec.cardinality/one
                                                   ::sut/description "Nickname"
                                                   ::sut/required false)))]
                  (expect (str/includes? result "// Nickname (optional)"))
                  (expect (str/includes? result "nick: string or null,")))))
  (describe "cardinality many"
            (it "outputs array type with []"
                (let [result (#'sut/spec->str
                              (sut/spec (sut/field ::sut/name :tags
                                                   ::sut/type :spec.type/string
                                                   ::sut/cardinality :spec.cardinality/many
                                                   ::sut/description "Tags")))]
                  (expect (str/includes? result "// Tags (required)"))
                  (expect (str/includes? result "tags: string[],")))))
  (describe "namespaced keywords"
            (it "converts single namespace to nested object"
                (let [result (#'sut/spec->str
                              (sut/spec (sut/field ::sut/name :address/street
                                                   ::sut/type :spec.type/string
                                                   ::sut/cardinality :spec.cardinality/one
                                                   ::sut/description "Street")))]
                  (expect (str/includes? result "address: {"))
                  (expect (str/includes? result "// Street (required)"))
                  (expect (str/includes? result "street: string,"))))))

(defdescribe
  str->data-test
  "Tests for JSON parsing"
  (describe "arrays"
            (it "parses array of objects"
                (expect (= (:value (sut/str->data "{\"items\": [{\"name\": \"x\", \"value\": 1}]}"))
                           {:items [{:name "x" :value 1}]}))))
  (describe "malformed JSON - unquoted"
            (it "handles unquoted keys"
                (expect (= (:value (sut/str->data "{name: \"John\"}")) {:name "John"})))
            (it "handles unquoted string values"
                (expect (= (:value (sut/str->data "{\"name\": John}")) {:name "John"}))))
  (describe "malformed JSON - trailing commas"
            (it "handles trailing comma in object"
                (expect (= (:value (sut/str->data "{\"name\": \"John\",}")) {:name "John"})))
            (it "handles trailing comma in array"
                (expect (= (:value (sut/str->data "{\"tags\": [\"a\", \"b\",]}"))
                           {:tags ["a" "b"]}))))
  (describe
    "markdown code blocks"
    (it "extracts JSON from markdown"
        (expect (= (:value (sut/str->data "```json\n{\"name\": \"John\"}\n```")) {:name "John"})))
    (it "handles code block without language tag"
        (expect (= (:value (sut/str->data "```\n{\"name\": \"John\"}\n```")) {:name "John"}))))
  (describe
    "complex example"
    (it
      "parses mixed data types"
      (expect
        (=
          (:value
            (sut/str->data
              "{\"name\": \"John\", \"age\": 30, \"tags\": [\"dev\"], \"address\": {\"city\": \"NYC\"}}"))
          {:name "John" :age 30 :tags ["dev"] :address {:city "NYC"}})))))

(defdescribe data->str-test
             "Tests for JSON serialization"
             (describe "date/datetime serialization"
                       (it "serializes LocalDate to ISO string"
                           (let [date
                                 (LocalDate/of 2024 1 15)

                                 result
                                 (sut/data->str {:date date})]

                             (expect (str/includes? result "\"2024-01-15\""))))
                       (it "serializes OffsetDateTime to ISO string"
                           (let [dt
                                 (OffsetDateTime/parse "2024-01-15T10:30:00Z")

                                 result
                                 (sut/data->str {:datetime dt})]

                             (expect (str/includes? result "\"2024-01-15T10:30Z\"")))))
             (describe "roundtrip tests"
                       (it "roundtrips maps nested in a vector"
                           (expect (= (:value (sut/str->data (sut/data->str
                                                               {:items [{:x 1} {:x {:y "a"}}]})))
                                      {:items [{:x 1} {:x {:y "a"}}]})))))

(defdescribe
  validate-data-test
  "Tests for data validation"
  (describe "valid data"
            (it "validates simple valid data"
                (let [s (sut/spec (sut/field ::sut/name :name
                                             ::sut/type :spec.type/string
                                             ::sut/cardinality :spec.cardinality/one
                                             ::sut/description "Name"))]
                  (expect (= {:valid? true} (sut/validate-data s {:name "John"}))))))
  (describe "missing required field"
            (it "returns invalid for missing required field"
                (let [s (sut/spec (sut/field ::sut/name :name
                                             ::sut/type :spec.type/string
                                             ::sut/cardinality :spec.cardinality/one
                                             ::sut/description "Name"))]
                  (expect (not (:valid? (sut/validate-data s {})))))))
  (describe "optional fields"
            (it "accepts nil for optional field"
                (let [s (sut/spec (sut/field ::sut/name :nick
                                             ::sut/type :spec.type/string
                                             ::sut/cardinality :spec.cardinality/one
                                             ::sut/description "Nick"
                                             ::sut/required false))]
                  (expect (= {:valid? true} (sut/validate-data s {:nick nil})))))
            (it "accepts missing optional field"
                (let [s (sut/spec (sut/field ::sut/name :nick
                                             ::sut/type :spec.type/string
                                             ::sut/cardinality :spec.cardinality/one
                                             ::sut/description "Nick"
                                             ::sut/required false))]
                  (expect (= {:valid? true} (sut/validate-data s {}))))))
  (describe "type mismatch"
            (it "returns invalid for wrong type"
                (let [s (sut/spec (sut/field ::sut/name :age
                                             ::sut/type :spec.type/int
                                             ::sut/cardinality :spec.cardinality/one
                                             ::sut/description "Age"))]
                  (expect (not (:valid? (sut/validate-data s {:age "thirty"})))))))
  (describe "enum validation"
            (it "returns invalid for wrong enum value (map form)"
                (let [s (sut/spec (sut/field ::sut/name :role
                                             ::sut/type :spec.type/string
                                             ::sut/cardinality :spec.cardinality/one
                                             ::sut/description "Role"
                                             ::sut/values {"admin" "Full access"
                                                           "user" "Standard access"}))]
                  (expect (not (:valid? (sut/validate-data s {:role "guest"}))))))
            (it "returns invalid for wrong enum value (vector form)"
                (let [s
                      (sut/spec (sut/field ::sut/name :confidence
                                           ::sut/type :spec.type/string
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Confidence"
                                           ::sut/values ["high" "medium" "low"]))

                      result
                      (sut/validate-data s {:confidence "maybe"})]

                  (expect (not (:valid? result)))
                  (expect (= ["high" "medium" "low"] (:allowed-values (first (:errors result)))))))
            (it "vector-form enum validates cardinality many"
                (let [s (sut/spec (sut/field ::sut/name :tags
                                             ::sut/type :spec.type/string
                                             ::sut/cardinality :spec.cardinality/many
                                             ::sut/description "Tags"
                                             ::sut/values ["red" "green" "blue"]))]
                  (expect (:valid? (sut/validate-data s {:tags ["red" "green"]})))
                  (expect (not (:valid? (sut/validate-data s {:tags ["red" "purple"]})))))))
  (describe "cardinality many"
            (it "returns invalid for wrong type in vector"
                (let [s (sut/spec (sut/field ::sut/name :nums
                                             ::sut/type :spec.type/int
                                             ::sut/cardinality :spec.cardinality/many
                                             ::sut/description "Numbers"))]
                  (expect (not (:valid? (sut/validate-data s {:nums [1 "two" 3]})))))))
  (describe "nested paths"
            (it "validates nested path"
                (let [s (sut/spec (sut/field ::sut/name :address/city
                                             ::sut/type :spec.type/string
                                             ::sut/cardinality :spec.cardinality/one
                                             ::sut/description "City"))]
                  (expect (= {:valid? true} (sut/validate-data s {:address {:city "NYC"}})))))
            (it "returns invalid for missing nested required field"
                (let [s (sut/spec (sut/field ::sut/name :address/city
                                             ::sut/type :spec.type/string
                                             ::sut/cardinality :spec.cardinality/one
                                             ::sut/description "City"))]
                  (expect (not (:valid? (sut/validate-data s {:address {}}))))))))

(defdescribe
  spec->prompt-test
  "Tests for BAML-style prompt generation"
  (it "generates BAML-style prompt"
      (let [s
            (sut/spec (sut/field ::sut/name :x
                                 ::sut/type :spec.type/int
                                 ::sut/cardinality :spec.cardinality/one
                                 ::sut/description "X"))

            result
            (sut/spec->prompt s)]

        (expect (str/includes? result "RESPONSE FORMAT"))
        (expect (str/includes? result "// X (required)"))
        (expect (str/includes? result "x: int,"))))
  (describe "values-only enum shorthand (vector form)"
            (it "emits an inline type union with every value"
                ;; BAML output escapes the JSON quotes so each value appears as
                ;; \"value\" in the rendered prompt string.
                (let [s
                      (sut/spec (sut/field ::sut/name :confidence
                                           ::sut/type :spec.type/string
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Confidence"
                                           ::sut/values ["high" "medium" "low"]))

                      result
                      (sut/spec->prompt s)]

                  (expect (str/includes? result "\\\"high\\\""))
                  (expect (str/includes? result "\\\"medium\\\""))
                  (expect (str/includes? result "\\\"low\\\""))
                  (expect (str/includes? result "or"))))
            (it "does NOT emit per-value comment lines"
                ;; This is the whole point of the shorthand — no wasted tokens on
                ;; self-explanatory enums.
                (let [s
                      (sut/spec (sut/field ::sut/name :confidence
                                           ::sut/type :spec.type/string
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Confidence"
                                           ::sut/values ["high" "medium" "low"]))

                      result
                      (sut/spec->prompt s)]

                  (expect (not (str/includes? result "//   - \"high\":")))
                  (expect (not (str/includes? result "//   - \"medium\":")))
                  (expect (not (str/includes? result "//   - \"low\":"))))))
  (describe "transitive ref usage (regression)"
            ;; Real-world repro from vis: an iteration spec held PLAN_STATE
            ;; (which referenced PLAN_ITEM) but only PLAN_STATE was listed in
            ;; the main spec's :refs. Older count-ref-usages walked the main
            ;; spec's fields ONLY — plan_item ended up with count=0, was
            ;; partitioned as "unused", and dropped from the rendered prompt.
            ;; Models then saw `items: plan_item[]` with no plan_item class
            ;; defined and reliably degraded into positional arrays.
            (let [item
                  (sut/spec :item
                            (sut/field ::sut/name :id
                                       ::sut/type :spec.type/int
                                       ::sut/cardinality :spec.cardinality/one
                                       ::sut/required true
                                       ::sut/description "Item id")
                            (sut/field ::sut/name :label
                                       ::sut/type :spec.type/string
                                       ::sut/cardinality :spec.cardinality/one
                                       ::sut/required true
                                       ::sut/description "Item label"))

                  group
                  (sut/spec :group
                            {:refs [item]}
                            (sut/field ::sut/name :name
                                       ::sut/type :spec.type/string
                                       ::sut/cardinality :spec.cardinality/one
                                       ::sut/required true
                                       ::sut/description "Group name")
                            (sut/field ::sut/name :items
                                       ::sut/type :spec.type/ref
                                       ::sut/target :item
                                       ::sut/cardinality :spec.cardinality/many
                                       ::sut/required true
                                       ::sut/description "Member items"))

                  top
                  (sut/spec :top
                            {:refs [group]}
                            (sut/field ::sut/name :group
                                       ::sut/type :spec.type/ref
                                       ::sut/target :group
                                       ::sut/cardinality :spec.cardinality/one
                                       ::sut/required true
                                       ::sut/description "The group"))

                  rendered
                  (sut/spec->prompt top)]

              (it "emits a class for transitively-referenced refs"
                  ;; The main spec only references :group directly, but :item is
                  ;; reachable via :group.items — it MUST appear as a class.
                  (expect (str/includes? rendered "item {")))
              (it "keeps the field reference to the transitively-used class"
                  (expect (str/includes? rendered "items: item[],")))
              (it "emits a class for the directly-used ref too"
                  (expect (str/includes? rendered "group {"))))))

(defdescribe field-test
             "Tests for field definition function"
             (describe "basic field creation"
                       (it "creates optional field with nil union"
                           (expect (= (sut/field ::sut/name :nick
                                                 ::sut/type :spec.type/string
                                                 ::sut/cardinality :spec.cardinality/one
                                                 ::sut/description "Nickname"
                                                 ::sut/required false)
                                      {::sut/name :nick
                                       ::sut/type :spec.type/string
                                       ::sut/cardinality :spec.cardinality/one
                                       ::sut/description "Nickname"
                                       ::sut/union #{::sut/nil}}))))
             (describe "enum values"
                       (it "throws when values is an empty vector"
                           (expect (throws? clojure.lang.ExceptionInfo
                                            #(sut/field ::sut/name :role
                                                        ::sut/type :spec.type/string
                                                        ::sut/cardinality :spec.cardinality/one
                                                        ::sut/description "Role"
                                                        ::sut/values []))))
                       (it "throws when values vector contains non-string entries"
                           (expect (throws? clojure.lang.ExceptionInfo
                                            #(sut/field ::sut/name :role
                                                        ::sut/type :spec.type/string
                                                        ::sut/cardinality :spec.cardinality/one
                                                        ::sut/description "Role"
                                                        ::sut/values ["admin" 42]))))
                       (it "throws when values is neither a map nor a vector"
                           (expect (throws? clojure.lang.ExceptionInfo
                                            #(sut/field ::sut/name :role
                                                        ::sut/type :spec.type/string
                                                        ::sut/cardinality :spec.cardinality/one
                                                        ::sut/description "Role"
                                                        ::sut/values #{"admin" "user"}))))
                       (it "throws when values map has nil description"
                           (expect (throws? clojure.lang.ExceptionInfo
                                            #(sut/field ::sut/name :role
                                                        ::sut/type :spec.type/string
                                                        ::sut/cardinality :spec.cardinality/one
                                                        ::sut/description "Role"
                                                        ::sut/values {"admin" nil
                                                                      "user" "Standard access"})))))
             (describe "invalid keyword format (dots in name)"
                       (it "throws on keyword with dot in name part"
                           (expect (throws? clojure.lang.ExceptionInfo
                                            #(sut/field ::sut/name :users.name
                                                        ::sut/type :spec.type/string
                                                        ::sut/cardinality :spec.cardinality/one
                                                        ::sut/description "Invalid"))))
                       (it "allows dots in namespace (Datomic style)"
                           (expect (= {::sut/name :org.division/name
                                       ::sut/type :spec.type/string
                                       ::sut/cardinality :spec.cardinality/one
                                       ::sut/description "Valid"}
                                      (sut/field ::sut/name :org.division/name
                                                 ::sut/type :spec.type/string
                                                 ::sut/cardinality :spec.cardinality/one
                                                 ::sut/description "Valid")))))
             (describe "missing required options"
                       (it "throws when name is missing"
                           (expect (throws? clojure.lang.ExceptionInfo
                                            #(sut/field ::sut/type :spec.type/string
                                                        ::sut/cardinality :spec.cardinality/one
                                                        ::sut/description "No name"))))
                       (it "throws when type is missing"
                           (expect (throws? clojure.lang.ExceptionInfo
                                            #(sut/field ::sut/name :x
                                                        ::sut/cardinality :spec.cardinality/one
                                                        ::sut/description "No type"))))
                       (it "throws when cardinality is missing"
                           (expect (throws? clojure.lang.ExceptionInfo
                                            #(sut/field ::sut/name :x
                                                        ::sut/type :spec.type/string
                                                        ::sut/description "No cardinality"))))
                       (it "throws when description is missing"
                           (expect (throws? clojure.lang.ExceptionInfo
                                            #(sut/field ::sut/name :x
                                                        ::sut/type :spec.type/string
                                                        ::sut/cardinality :spec.cardinality/one)))))
             (describe "invalid options"
                       (it "throws on invalid type"
                           (expect (throws? clojure.lang.ExceptionInfo
                                            #(sut/field ::sut/name :x
                                                        ::sut/type ::sut/invalid
                                                        ::sut/cardinality :spec.cardinality/one
                                                        ::sut/description "Bad type"))))
                       (it "throws on invalid cardinality"
                           (expect (throws? clojure.lang.ExceptionInfo
                                            #(sut/field ::sut/name :x
                                                        ::sut/type :spec.type/string
                                                        ::sut/cardinality ::sut/invalid
                                                        ::sut/description "Bad card"))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(defdescribe
  integration-test
  "End-to-end integration tests: spec -> prompt -> parse -> validate"
  (describe "roundtrip with malformed JSON"
            (it "handles markdown code blocks"
                (let [s
                      (sut/spec (sut/field ::sut/name :value
                                           ::sut/type :spec.type/int
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Value"))

                      llm-response
                      "Here's the data:\n```json\n{\"value\": 42}\n```"

                      parsed
                      (:value (sut/str->data llm-response))

                      validation
                      (sut/validate-data s parsed)]

                  (expect (= {:value 42} parsed))
                  (expect (= {:valid? true} validation)))))
  (describe "validation failures"
            (it "detects missing required fields"
                (let [s
                      (sut/spec (sut/field ::sut/name :name
                                           ::sut/type :spec.type/string
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Name"))

                      llm-response
                      "{}"

                      parsed
                      (:value (sut/str->data llm-response))

                      validation
                      (sut/validate-data s parsed)]

                  (expect (= false (:valid? validation)))
                  (expect (some #(= :missing-required-field (:error %)) (:errors validation)))))
            (it "detects type mismatches"
                (let [s
                      (sut/spec (sut/field ::sut/name :age
                                           ::sut/type :spec.type/int
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Age"))

                      llm-response
                      "{\"age\": \"thirty\"}"

                      parsed
                      (:value (sut/str->data llm-response))

                      validation
                      (sut/validate-data s parsed)]

                  (expect (= false (:valid? validation)))
                  (expect (some #(= :type-mismatch (:error %)) (:errors validation)))))
            (it "detects invalid enum values"
                (let [s
                      (sut/spec (sut/field ::sut/name :role
                                           ::sut/type :spec.type/string
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Role"
                                           ::sut/values {"admin" "Full access"
                                                         "user" "Standard access"}))

                      llm-response
                      "{\"role\": \"guest\"}"

                      parsed
                      (:value (sut/str->data llm-response))

                      validation
                      (sut/validate-data s parsed)]

                  (expect (= false (:valid? validation)))
                  (expect (some #(= :invalid-enum-value (:error %)) (:errors validation)))))))

;; =============================================================================
;; Reference Field Tests
;; =============================================================================

(defdescribe build-ref-registry-test
             "Tests for build-ref-registry function"
             (it "builds registry from refs in spec"
                 (let [addr-spec
                       (sut/spec :Address
                                 (sut/field ::sut/name :street
                                            ::sut/type :spec.type/string
                                            ::sut/cardinality :spec.cardinality/one
                                            ::sut/description "Street"))

                       person-spec
                       (sut/spec :Person
                                 {:refs [addr-spec]}
                                 (sut/field ::sut/name :name
                                            ::sut/type :spec.type/string
                                            ::sut/cardinality :spec.cardinality/one
                                            ::sut/description "Name"))

                       registry
                       (sut/build-ref-registry person-spec)]

                   (expect (contains? registry :Address))
                   (expect (= addr-spec (get registry :Address)))))
             (it "returns empty map for spec with no refs"
                 (let [s (sut/spec (sut/field ::sut/name :x
                                              ::sut/type :spec.type/string
                                              ::sut/cardinality :spec.cardinality/one
                                              ::sut/description "X"))]
                   (expect (= {} (sut/build-ref-registry s)))))
             (it "throws on duplicate spec names"
                 (let [spec1
                       (sut/spec :Dup
                                 (sut/field ::sut/name :a
                                            ::sut/type :spec.type/string
                                            ::sut/cardinality :spec.cardinality/one
                                            ::sut/description "A"))

                       spec2
                       (sut/spec :Dup
                                 (sut/field ::sut/name :b
                                            ::sut/type :spec.type/string
                                            ::sut/cardinality :spec.cardinality/one
                                            ::sut/description "B"))

                       parent
                       (sut/spec :Parent
                                 {:refs [spec1 spec2]}
                                 (sut/field ::sut/name :x
                                            ::sut/type :spec.type/string
                                            ::sut/cardinality :spec.cardinality/one
                                            ::sut/description "X"))]

                   (expect (throws? clojure.lang.ExceptionInfo #(sut/build-ref-registry parent))))))

(defdescribe ref-field-test
             "Tests for ::ref field type"
             (describe "ref validation"
                       (it "throws when ref target is not in refs"
                           (expect (throws? clojure.lang.ExceptionInfo
                                            #(sut/spec (sut/field ::sut/name :items
                                                                  ::sut/type :spec.type/ref
                                                                  ::sut/cardinality
                                                                  :spec.cardinality/many
                                                                  ::sut/description "Items"
                                                                  ::sut/target :item)))))
                       (it "allows ref when target exists in refs"
                           (let [item-spec
                                 (sut/spec :item
                                           (sut/field ::sut/name :name
                                                      ::sut/type :spec.type/string
                                                      ::sut/cardinality :spec.cardinality/one
                                                      ::sut/description "Name"))

                                 result
                                 (sut/spec {:refs [item-spec]}
                                           (sut/field ::sut/name :items
                                                      ::sut/type :spec.type/ref
                                                      ::sut/cardinality :spec.cardinality/many
                                                      ::sut/description "Items"
                                                      ::sut/target :item))]

                             (expect (= 1 (count (::sut/refs result))))
                             (expect (= 1 (count (::sut/fields result))))))))

;; =============================================================================
;; Special Character Handling Tests (?!*+)
;; =============================================================================

(defdescribe special-char-handling-test
             "Tests for handling Clojure special characters (?!*+) in field names"
             (describe "keyword->path strips special chars"
                       (it "strips ? from predicate keywords"
                           (let [result (#'sut/keyword->path :valid?)]
                             (expect (= "valid" result))))
                       (it "handles namespaced keywords with special chars"
                           (let [result (#'sut/keyword->path :claims/verifiable?)]
                             (expect (= "claims.verifiable" result)))))
             (describe "spec->str generates correct BAML for predicate fields"
                       (it "strips ? from field name in output"
                           (let [result (#'sut/spec->str
                                         (sut/spec (sut/field ::sut/name :valid?
                                                              ::sut/type :spec.type/bool
                                                              ::sut/cardinality
                                                              :spec.cardinality/one
                                                              ::sut/description "Is valid")))]
                             (expect (str/includes? result "valid: bool,"))
                             (expect (not (str/includes? result "valid?:"))))))
             (describe "str->data-with-spec remaps keys correctly"
                       (it "remaps simple predicate key"
                           (let [s
                                 (sut/spec (sut/field ::sut/name :valid?
                                                      ::sut/type :spec.type/bool
                                                      ::sut/cardinality :spec.cardinality/one
                                                      ::sut/description "Valid"))

                                 result
                                 (sut/str->data-with-spec "{\"valid\": true}" s)]

                             (expect (contains? result :valid?))
                             (expect (= true (:valid? result)))))))

;; =============================================================================
;; Keyword Type Tests
;; =============================================================================

(defdescribe
  keyword-type-test
  "Tests for :spec.type/keyword - rendered as string, keywordized on parse"
  (describe "spec->str renders keyword as string"
            (it "outputs string type in BAML"
                (let [result (#'sut/spec->str
                              (sut/spec (sut/field ::sut/name :status
                                                   ::sut/type :spec.type/keyword
                                                   ::sut/cardinality :spec.cardinality/one
                                                   ::sut/description "Current status")))]
                  (expect (str/includes? result "status: string,")))))
  (describe "str->data-with-spec converts to keywords"
            (it "converts single keyword field"
                (let [s
                      (sut/spec (sut/field ::sut/name :status
                                           ::sut/type :spec.type/keyword
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Status"))

                      result
                      (sut/str->data-with-spec "{\"status\": \"active\"}" s)]

                  (expect (= :active (:status result)))
                  (expect (keyword? (:status result)))))
            (it "converts keyword field in array"
                (let [s
                      (sut/spec (sut/field ::sut/name :tags
                                           ::sut/type :spec.type/keyword
                                           ::sut/cardinality :spec.cardinality/many
                                           ::sut/description "Tags"))

                      result
                      (sut/str->data-with-spec "{\"tags\": [\"foo\", \"bar\", \"baz\"]}" s)]

                  (expect (= [:foo :bar :baz] (:tags result)))
                  (expect (every? keyword? (:tags result)))))))

(defdescribe
  keyword-enum-validation-test
  "Keyword-typed fields are keywordized on parse, but enum VALUES are declared
   as strings. Validation must canonicalize both sides — otherwise no value can
   ever satisfy a TYPE_KEYWORD + VALUES field (strings fail the type check,
   keywords fail the enum check)."
  (describe "cardinality one"
            (it "accepts a keyword matching a string-declared enum value"
                (let [s (sut/spec (sut/field ::sut/name :topic
                                             ::sut/type :spec.type/keyword
                                             ::sut/cardinality :spec.cardinality/one
                                             ::sut/description "Topic"
                                             ::sut/values {"ai" "AI/ML" "databases" "Storage"}))]
                  (expect (= {:valid? true} (sut/validate-data s {:topic :ai})))
                  (expect (= {:valid? true} (sut/validate-data s {:topic :databases})))))
            (it "rejects a keyword outside the enum"
                (let [s
                      (sut/spec (sut/field ::sut/name :topic
                                           ::sut/type :spec.type/keyword
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Topic"
                                           ::sut/values {"ai" "AI/ML" "databases" "Storage"}))

                      result
                      (sut/validate-data s {:topic :cooking})]

                  (expect (= false (:valid? result)))
                  (expect (some #(= :invalid-enum-value (:error %)) (:errors result)))))
            (it "accepts enum VALUES declared as vector shorthand"
                (let [s (sut/spec (sut/field ::sut/name :status
                                             ::sut/type :spec.type/keyword
                                             ::sut/cardinality :spec.cardinality/one
                                             ::sut/description "Status"
                                             ::sut/values ["active" "inactive"]))]
                  (expect (= {:valid? true} (sut/validate-data s {:status :active}))))))
  (describe "cardinality many"
            (it "accepts a vector of keywords all within the enum"
                (let [s (sut/spec (sut/field ::sut/name :tags
                                             ::sut/type :spec.type/keyword
                                             ::sut/cardinality :spec.cardinality/many
                                             ::sut/description "Tags"
                                             ::sut/values {"foo" "F" "bar" "B"}))]
                  (expect (= {:valid? true} (sut/validate-data s {:tags [:foo :bar]})))))
            (it "rejects a vector containing a keyword outside the enum"
                (let [s
                      (sut/spec (sut/field ::sut/name :tags
                                           ::sut/type :spec.type/keyword
                                           ::sut/cardinality :spec.cardinality/many
                                           ::sut/description "Tags"
                                           ::sut/values {"foo" "F" "bar" "B"}))

                      result
                      (sut/validate-data s {:tags [:foo :nope]})]

                  (expect (= false (:valid? result)))
                  (expect (some #(= :invalid-enum-value (:error %)) (:errors result))))))
  (describe "parse → validate roundtrip"
            (it "validate-data accepts str->data-with-spec output for keyword enums"
                (let [s
                      (sut/spec (sut/field ::sut/name :topic
                                           ::sut/type :spec.type/keyword
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Topic"
                                           ::sut/values {"ai" "AI/ML" "databases" "Storage"}))

                      parsed
                      (sut/str->data-with-spec "{\"topic\": \"databases\"}" s)]

                  (expect (= {:topic :databases} parsed))
                  (expect (= {:valid? true} (sut/validate-data s parsed)))))))

;; =============================================================================
;; Fixed-Size Vector Type Tests
;; =============================================================================

(defdescribe
  fixed-size-vector-type-test
  "Tests for fixed-size vector types: :spec.type/int-v-N, :spec.type/string-v-N, :spec.type/double-v-N"
  (describe "parse-vector-type"
            (it "parses int-v-N format"
                (let [result (#'sut/parse-vector-type :spec.type/int-v-4)]
                  (expect (= {:base-type :int :size 4} result))))
            (it "returns nil for standard types"
                (expect (nil? (#'sut/parse-vector-type :spec.type/string)))
                (expect (nil? (#'sut/parse-vector-type :spec.type/int)))))
  (describe "spec->str BAML generation"
            (it "renders int-v-N as int[N]"
                (let [result (#'sut/spec->str
                              (sut/spec (sut/field ::sut/name :bbox
                                                   ::sut/type :spec.type/int-v-4
                                                   ::sut/cardinality :spec.cardinality/one
                                                   ::sut/description "Bounding box")))]
                  (expect (str/includes? result "bbox: int[4],"))
                  (expect (str/includes? result "(exactly 4 elements)")))))
  (describe "validate-data with vector types"
            (it "validates correct int vector"
                (let [s
                      (sut/spec (sut/field ::sut/name :bbox
                                           ::sut/type :spec.type/int-v-4
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Bounding box"))

                      result
                      (sut/validate-data s {:bbox [10 20 100 200]})]

                  (expect (= {:valid? true} result))))
            (it "rejects vector with wrong size"
                (let [s
                      (sut/spec (sut/field ::sut/name :bbox
                                           ::sut/type :spec.type/int-v-4
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Bounding box"))

                      result
                      (sut/validate-data s {:bbox [10 20 100]})]

                  (expect (not (:valid? result)))))
            (it "rejects vector with wrong element type"
                (let [s
                      (sut/spec (sut/field ::sut/name :bbox
                                           ::sut/type :spec.type/int-v-4
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Bounding box"))

                      result
                      (sut/validate-data s {:bbox [10 "twenty" 100 200]})]

                  (expect (not (:valid? result)))))))

;; =============================================================================
;; Enum Value Descriptions in Output
;; =============================================================================

(defdescribe enum-value-descriptions-test
             "Tests for rendering enum value descriptions in BAML output"
             (it "includes enum value descriptions as comments"
                 (let [result (#'sut/spec->str
                               (sut/spec (sut/field ::sut/name :status
                                                    ::sut/type :spec.type/string
                                                    ::sut/cardinality :spec.cardinality/one
                                                    ::sut/description "Current status"
                                                    ::sut/values {"pending" "Awaiting processing"
                                                                  "done" "Successfully completed"
                                                                  "failed" "Processing failed"})))]
                   (expect (str/includes? result "- \"done\": Successfully completed"))
                   (expect (str/includes? result "- \"failed\": Processing failed"))
                   (expect (str/includes? result "- \"pending\": Awaiting processing"))))
             (it "sorts enum values alphabetically"
                 (let [result
                       (#'sut/spec->str
                        (sut/spec (sut/field ::sut/name :priority
                                             ::sut/type :spec.type/string
                                             ::sut/cardinality :spec.cardinality/one
                                             ::sut/description "Priority level"
                                             ::sut/values {"high" "Urgent"
                                                           "low" "Can wait"
                                                           "medium" "Normal priority"})))

                       high-pos
                       (str/index-of result "\"high\"")

                       low-pos
                       (str/index-of result "\"low\"")

                       medium-pos
                       (str/index-of result "\"medium\"")]

                   (expect (< high-pos low-pos))
                   (expect (< low-pos medium-pos)))))

;; =============================================================================
;; Bare Array Auto-Wrapping
;; =============================================================================

(defdescribe
  bare-array-wrapping-test
  "Tests for bare array handling when spec has single array field.
   Single :many specs pass through bare arrays as-is. Multi-field specs
   still merge/unwrap as before."
  (describe "spec with single cardinality-many field"
            (it "passes bare array through as-is (no wrapping)"
                (let [spec-def
                      (sut/spec (sut/field ::sut/name :nodes
                                           ::sut/type :spec.type/string
                                           ::sut/cardinality :spec.cardinality/many
                                           ::sut/description "List of nodes"))

                      ;; LLM returns bare array instead of {nodes: [...]}
                      llm-response
                      "[\"a\", \"b\", \"c\"]"

                      parsed
                      (sut/str->data-with-spec llm-response spec-def)]

                  (expect (= ["a" "b" "c"] parsed))))
            (it "passes through complex objects in array with defaults applied"
                (let [item-spec
                      (sut/spec :Item
                                (sut/field ::sut/name :id
                                           ::sut/type :spec.type/int
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "ID")
                                (sut/field ::sut/name :name
                                           ::sut/type :spec.type/string
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Name"))

                      spec-def
                      (sut/spec {:refs [item-spec]}
                                (sut/field ::sut/name :items
                                           ::sut/type :spec.type/ref
                                           ::sut/target :Item
                                           ::sut/cardinality :spec.cardinality/many
                                           ::sut/description "List of items"))

                      llm-response
                      "[{\"id\": 1, \"name\": \"first\"}, {\"id\": 2, \"name\": \"second\"}]"

                      parsed
                      (sut/str->data-with-spec llm-response spec-def)]

                  (expect (= [{:id 1 :name "first"} {:id 2 :name "second"}] parsed)))))
  (describe "spec with multiple fields - no wrapping"
            (it "rejects bare array of strings when spec has multiple fields"
                (let [spec-def
                      (sut/spec (sut/field ::sut/name :items
                                           ::sut/type :spec.type/string
                                           ::sut/cardinality :spec.cardinality/many
                                           ::sut/description "Items")
                                (sut/field ::sut/name :count
                                           ::sut/type :spec.type/int
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Count"))

                      llm-response
                      "[\"a\", \"b\"]"]

                  ;; Bare string array + multi-field spec = structural mismatch → rejection
                  (expect (throws? clojure.lang.ExceptionInfo
                                   #(sut/str->data-with-spec llm-response spec-def))))))
  (describe "multi-element array of maps merged when spec expects map"
            (it "merges split objects into single map"
                (let [spec-def
                      (sut/spec (sut/field ::sut/name :summary
                                           ::sut/type :spec.type/string
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Summary")
                                (sut/field ::sut/name :entities
                                           ::sut/type :spec.type/string
                                           ::sut/cardinality :spec.cardinality/many
                                           ::sut/description "Entities"))

                      llm-response
                      "[{\"summary\": \"A summary\"}, {\"entities\": [\"e1\", \"e2\"]}]"

                      parsed
                      (sut/str->data-with-spec llm-response spec-def)]

                  (expect (= {:summary "A summary" :entities ["e1" "e2"]} parsed)))))
  (describe "normal object response - no wrapping needed"
            (it "passes through object responses unchanged"
                (let [spec-def
                      (sut/spec (sut/field ::sut/name :nodes
                                           ::sut/type :spec.type/string
                                           ::sut/cardinality :spec.cardinality/many
                                           ::sut/description "List of nodes"))

                      ;; LLM returns properly wrapped object
                      llm-response
                      "{\"nodes\": [\"a\", \"b\", \"c\"]}"

                      parsed
                      (sut/str->data-with-spec llm-response spec-def)]

                  (expect (= {:nodes ["a" "b" "c"]} parsed))))))

;; =============================================================================
;; Spec Field Defaults — null/missing field coercion in str->data-with-spec
;; =============================================================================

(def ^:private cod-entity-spec
  "Reusable entity sub-spec for field defaults tests."
  (sut/spec :CodEntity
            (sut/field ::sut/name :entity
                       ::sut/type :spec.type/string
                       ::sut/cardinality :spec.cardinality/one
                       ::sut/description "Entity name")
            (sut/field ::sut/name :rationale
                       ::sut/type :spec.type/string
                       ::sut/cardinality :spec.cardinality/one
                       ::sut/description "Why salient")
            (sut/field ::sut/name :score
                       ::sut/type :spec.type/float
                       ::sut/cardinality :spec.cardinality/one
                       ::sut/description "Salience score 0.0-1.0")))

(def ^:private cod-spec
  "Reusable CoD spec with ref entities for field defaults tests."
  (sut/spec {:refs [cod-entity-spec]}
            (sut/field ::sut/name :entities
                       ::sut/type :spec.type/ref
                       ::sut/cardinality :spec.cardinality/many
                       ::sut/target :CodEntity
                       ::sut/description "Salient entities")
            (sut/field ::sut/name :summary
                       ::sut/type :spec.type/string
                       ::sut/cardinality :spec.cardinality/one
                       ::sut/description "The summary")))

(defdescribe
  spec-field-defaults-test
  "Tests for apply-spec-field-defaults — SAP coercion of null/missing fields.
   A required field that is missing or null throws, so those cases pass :partial? true."
  (describe "cardinality-many null coercion"
            (it "coerces null :many field to empty vector"
                (let [parsed (sut/str->data-with-spec "{\"entities\": null, \"summary\": \"ok\"}"
                                                      cod-spec)]
                  (expect (= [] (:entities parsed)))
                  (expect (= "ok" (:summary parsed)))))
            (it "coerces missing :many field to empty vector"
                (let [parsed (sut/str->data-with-spec "{\"summary\": \"ok\"}" cod-spec)]
                  (expect (= [] (:entities parsed)))
                  (expect (= "ok" (:summary parsed))))))
  (describe "cardinality-one missing key insertion"
            (it "adds missing :one field with nil value"
                (let [parsed
                      (sut/str->data-with-spec "{\"entities\": []}" cod-spec {:partial? true})]
                  (expect (contains? parsed :summary))
                  (expect (nil? (:summary parsed))))))
  (describe "nested ref defaults"
            (it "adds missing fields inside ref items"
                (let [parsed (sut/str->data-with-spec
                               "{\"entities\": [{\"entity\": \"Voyager\"}], \"summary\": \"ok\"}"
                               cod-spec
                               {:partial? true})]
                  ;; :rationale and :score were missing in the entity — should be added as nil
                  (expect (contains? (first (:entities parsed)) :rationale))
                  (expect (contains? (first (:entities parsed)) :score))
                  (expect (nil? (:rationale (first (:entities parsed)))))
                  (expect (nil? (:score (first (:entities parsed)))))
                  (expect (= "Voyager" (:entity (first (:entities parsed))))))))
  (describe "non-map result passthrough"
            (it "rejects plain text when LLM produces no JSON"
                (expect (throws? clojure.lang.ExceptionInfo
                                 #(sut/str->data-with-spec "I cannot find entities." cod-spec))))))

;; =============================================================================
;; coerce-data-with-spec — Clojure data coercion (pre-parsed Clojure data)
;; =============================================================================

(def ^:private verification-result-spec
  "Spec fixture for testing coercion of pre-parsed Clojure data."
  (sut/spec :verification
            {::sut/key-ns "verification"}
            (sut/field {::sut/name :question-index
                        ::sut/type :spec.type/int
                        ::sut/cardinality :spec.cardinality/one
                        ::sut/description "Index"})
            (sut/field {::sut/name :verdict
                        ::sut/type :spec.type/keyword
                        ::sut/cardinality :spec.cardinality/one
                        ::sut/values {"pass" "OK" "fail" "Bad" "needs-revision" "Fixable"}
                        ::sut/description "Verdict"})))

(def ^:private verification-spec
  "Wrapper spec with :many ref field."
  (sut/spec {:refs [verification-result-spec]}
            (sut/field {::sut/name :verifications
                        ::sut/type :spec.type/ref
                        ::sut/target :verification
                        ::sut/cardinality :spec.cardinality/many
                        ::sut/description "Results"})))

(def ^:private questions-spec
  "Spec with single :many field for array normalization testing."
  (sut/spec (sut/field {::sut/name :questions
                        ::sut/type :spec.type/string
                        ::sut/cardinality :spec.cardinality/many
                        ::sut/description "Questions"})))

(defdescribe coerce-data-with-spec-test
             "Tests for coerce-data-with-spec — type coercion on Clojure data from SCI."
             (describe "keyword type coercion"
                       (it "coerces string verdict to keyword"
                           (let [data
                                 {:verifications [{:question-index 0 :verdict "pass"}
                                                  {:question-index 1 :verdict "fail"}]}

                                 result
                                 (sut/coerce-data-with-spec data verification-spec)]

                             (expect (= :pass (:verdict (first (:verifications result)))))
                             (expect (= :fail (:verdict (second (:verifications result))))))))
             (describe "does NOT apply key-ns namespacing"
                       (it "keeps keys un-namespaced despite spec having key-ns"
                           (let [data
                                 {:verifications [{:question-index 0 :verdict "pass"}]}

                                 result
                                 (sut/coerce-data-with-spec data verification-spec)]

                             ;; Keys should stay as :verdict, NOT become :verification/verdict
                             (expect (contains? (first (:verifications result)) :verdict))
                             (expect (not (contains? (first (:verifications result))
                                                     :verification/verdict))))))
             (describe "array normalization"
                       (it "passes bare vector through as-is for single :many field"
                           (let [data
                                 ["Q1" "Q2" "Q3"]

                                 result
                                 (sut/coerce-data-with-spec data questions-spec)]

                             (expect (= ["Q1" "Q2" "Q3"] result)))))
             (describe "field defaults"
                       (it "coerces nil :many field to empty vector"
                           (let [data
                                 {:verifications nil}

                                 result
                                 (sut/coerce-data-with-spec data verification-spec)]

                             (expect (= [] (:verifications result))))))
             (describe "non-map passthrough"
                       (it "returns string unchanged"
                           (expect (= "hello"
                                      (sut/coerce-data-with-spec "hello" verification-spec))))))

;; =============================================================================
;; validate-data with TYPE_REF
;; =============================================================================

(defdescribe
  validate-data-ref-test
  "Tests for validate-data recursing into TYPE_REF sub-specs."
  (let [address-spec
        (sut/spec :Address
                  (sut/field ::sut/name :street
                             ::sut/type :spec.type/string
                             ::sut/cardinality :spec.cardinality/one
                             ::sut/description "Street address")
                  (sut/field ::sut/name :city
                             ::sut/type :spec.type/string
                             ::sut/cardinality :spec.cardinality/one
                             ::sut/description "City name"))

        company-spec
        (sut/spec {:refs [address-spec]}
                  (sut/field ::sut/name :name
                             ::sut/type :spec.type/string
                             ::sut/cardinality :spec.cardinality/one
                             ::sut/description "Company name")
                  (sut/field ::sut/name :headquarters
                             ::sut/type :spec.type/ref
                             ::sut/cardinality :spec.cardinality/one
                             ::sut/target :Address
                             ::sut/description "HQ address")
                  (sut/field ::sut/name :branches
                             ::sut/type :spec.type/ref
                             ::sut/cardinality :spec.cardinality/many
                             ::sut/target :Address
                             ::sut/description "Branch offices"))]

    (describe "cardinality one ref"
              (it "validates valid ref data"
                  (expect (= {:valid? true}
                             (sut/validate-data company-spec
                                                {:name "Acme"
                                                 :headquarters {:street "123 Main" :city "SF"}
                                                 :branches [{:street "456 Oak" :city "LA"}]}))))
              (it "catches missing required field in ref"
                  (let [result (sut/validate-data company-spec
                                                  {:name "Acme"
                                                   :headquarters {:city "SF"}
                                                   :branches [{:street "456 Oak" :city "LA"}]})]
                    (expect (not (:valid? result)))
                    (expect (some #(= :street (:field %)) (:errors result))))))
    (describe "cardinality many ref"
              (it "catches invalid data in array ref items"
                  (let [result (sut/validate-data company-spec
                                                  {:name "Acme"
                                                   :headquarters {:street "123 Main" :city "SF"}
                                                   :branches [{:street "456 Oak" :city "LA"}
                                                              {:city "NY"}]})]
                    (expect (not (:valid? result)))
                    (expect (some #(= :street (:field %)) (:errors result))))))
    (describe
      "ref with enum VALUES"
      (let [sentiment-spec
            (sut/spec :Sentiment
                      (sut/field ::sut/name :label
                                 ::sut/type :spec.type/string
                                 ::sut/cardinality :spec.cardinality/one
                                 ::sut/description "Sentiment label"
                                 ::sut/values
                                 {"positive" "Good" "negative" "Bad" "neutral" "Neutral"})
                      (sut/field ::sut/name :score
                                 ::sut/type :spec.type/float
                                 ::sut/cardinality :spec.cardinality/one
                                 ::sut/description "Confidence"))

            review-spec
            (sut/spec {:refs [sentiment-spec]}
                      (sut/field ::sut/name :text
                                 ::sut/type :spec.type/string
                                 ::sut/cardinality :spec.cardinality/one
                                 ::sut/description "Review text")
                      (sut/field ::sut/name :sentiments
                                 ::sut/type :spec.type/ref
                                 ::sut/cardinality :spec.cardinality/many
                                 ::sut/target :Sentiment
                                 ::sut/description "Detected sentiments"))]

        (it "catches invalid enum values in ref items"
            (let [result (sut/validate-data review-spec
                                            {:text "Great product"
                                             :sentiments [{:label "positive" :score 0.95}
                                                          {:label "amazing" :score 0.8}]})]
              (expect (not (:valid? result)))
              (expect (some #(= :invalid-enum-value (:error %)) (:errors result)))))))))

(defn- one-field-spec
  [field-type & opts]
  (sut/spec (apply sut/field
              ::sut/name
              :answer
              ::sut/type
              field-type
              ::sut/cardinality
              :spec.cardinality/one ::sut/description
              "Answer" opts)))

(defn- rejection-type
  [thunk]
  (try (thunk) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defdescribe
  str->data-with-spec-shape-test
  "svar unwraps a wrapped scalar and rejects other wrong field shapes, so ask!
   retries them. Seen live: nemotron-3-ultra answered {\"answer\": {\"answer\": \"wins\"}}
   and {\"answer\": null} for a required string field, and svar returned both."
  (describe "required :one fields"
            (it "rejects null"
                (expect (= :svar.spec/required-field-missing
                           (rejection-type #(sut/str->data-with-spec "{\"answer\": null}"
                                                                     (one-field-spec
                                                                       :spec.type/string))))))
            (it "rejects a missing field"
                (expect (= :svar.spec/required-field-missing
                           (rejection-type
                             #(sut/str->data-with-spec "{}" (one-field-spec :spec.type/string))))))
            (it "keeps nil for an optional field"
                (expect (= {:answer nil}
                           (sut/str->data-with-spec
                             "{}"
                             (one-field-spec :spec.type/string ::sut/required false))))))
  (describe "scalar :one fields"
            (it "unwraps a one-entry map"
                (expect (= {:answer "wins"}
                           (sut/str->data-with-spec "{\"answer\": {\"answer\": \"wins\"}}"
                                                    (one-field-spec :spec.type/string))))
                (expect (= {:answer "wins"}
                           (sut/str->data-with-spec "{\"answer\": {\"a\": {\"b\": \"wins\"}}}"
                                                    (one-field-spec :spec.type/string)))))
            (it "unwraps a one-item vector"
                (expect (= {:answer "wins"}
                           (sut/str->data-with-spec "{\"answer\": [\"wins\"]}"
                                                    (one-field-spec :spec.type/string)))))
            (it "unwraps into a keyword field"
                (expect (= {:answer :low}
                           (sut/str->data-with-spec "{\"answer\": {\"level\": \"low\"}}"
                                                    (one-field-spec :spec.type/keyword)))))
            (it "rejects a map with more than one entry"
                (let [data (try (sut/str->data-with-spec
                                  "{\"answer\": {\"winner\": \"rock\", \"loser\": \"scissors\"}}"
                                  (one-field-spec :spec.type/string))
                                (catch clojure.lang.ExceptionInfo e (ex-data e)))]
                  (expect (= :svar.spec/schema-rejected (:type data)))
                  (expect (= :field-type-mismatch (:reason data)))
                  (expect (= :answer (:field data)))
                  (expect (string? (:message data)))))
            (it "rejects a vector with more than one item"
                (expect (= :svar.spec/schema-rejected
                           (rejection-type #(sut/str->data-with-spec "{\"answer\": [\"a\", \"b\"]}"
                                                                     (one-field-spec
                                                                       :spec.type/string))))))
            (it "accepts date strings and namespaced keys"
                (expect (= {:answer "2026-01-02"}
                           (sut/str->data-with-spec "{\"answer\": \"2026-01-02\"}"
                                                    (one-field-spec :spec.type/date))))
                (expect (= {:page.node/kind "heading"}
                           (sut/str->data-with-spec
                             "{\"kind\": \"heading\"}"
                             (sut/spec :node
                                       {::sut/key-ns "page.node"}
                                       (sut/field ::sut/name :kind
                                                  ::sut/type :spec.type/string
                                                  ::sut/cardinality :spec.cardinality/one
                                                  ::sut/description "Kind")))))))
  (describe "ref fields"
            (it "unwraps or rejects a value inside a ref item"
                (let [item
                      (sut/spec :Item
                                (sut/field ::sut/name :title
                                           ::sut/type :spec.type/string
                                           ::sut/cardinality :spec.cardinality/one
                                           ::sut/description "Title"))

                      root
                      (sut/spec :Root
                                {:refs [item]}
                                (sut/field ::sut/name :items
                                           ::sut/type :spec.type/ref
                                           ::sut/target :Item
                                           ::sut/cardinality :spec.cardinality/many
                                           ::sut/description "Items"))]

                  (expect (= {:items [{:title "a"}]}
                             (sut/str->data-with-spec "{\"items\": [{\"title\": \"a\"}]}" root)))
                  (expect (= {:items [{:title "x"}]}
                             (sut/str->data-with-spec "{\"items\": [{\"title\": {\"t\": \"x\"}}]}"
                                                      root)))
                  (expect (= :svar.spec/schema-rejected
                             (rejection-type #(sut/str->data-with-spec
                                                "{\"items\": [{\"title\": {\"x\": 1, \"y\": 2}}]}"
                                                root)))))))
  (describe "partial results"
            (it "skips the checks with :partial? true"
                (expect (= {:answer nil}
                           (sut/str->data-with-spec "{}"
                                                    (one-field-spec :spec.type/string)
                                                    {:partial? true}))))))
