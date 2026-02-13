(ns com.blockether.svar.spec-test
  "Tests for structured output specification DSL.
   
   Ported from unbound.backend.shared.llm.internal.spec-test"
  (:require
   [clojure.string :as str]
   [lazytest.core :refer [defdescribe describe expect it throws?]]
   [com.blockether.svar.spec :as sut])
  (:import
   [java.time LocalDate OffsetDateTime]))

(defdescribe spec->str-test
  "Tests for BAML format generation"

  (describe "simple non-namespaced keywords"
            (it "converts simple spec to BAML format"
                (let [result (#'sut/spec->str
                              (sut/spec
                               (sut/field ::sut/name :name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "User's name")
                               (sut/field ::sut/name :age ::sut/type :spec.type/int ::sut/cardinality :spec.cardinality/one ::sut/description "Age")))]
                  (expect (str/includes? result "// User's name (required)"))
                  (expect (str/includes? result "name: string,"))
                  (expect (str/includes? result "// Age (required)"))
                  (expect (str/includes? result "age: int,")))))

  (describe "with enum values"
            (it "includes union literals in output"
                (let [result (#'sut/spec->str
                              (sut/spec
                               (sut/field ::sut/name :role ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Role"
                                          ::sut/values {"admin" "Full system access" "user" "Standard user access"})))]
                  (expect (str/includes? result "// Role (required)"))
                  (expect (str/includes? result "admin"))
                  (expect (str/includes? result "user"))
                  (expect (str/includes? result " or ")))))

  (describe "optional field"
            (it "appends 'or null' to type for optional fields"
                (let [result (#'sut/spec->str
                              (sut/spec
                               (sut/field ::sut/name :nick ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Nickname" ::sut/required false)))]
                  (expect (str/includes? result "// Nickname (optional)"))
                  (expect (str/includes? result "nick: string or null,")))))

  (describe "cardinality many"
            (it "outputs array type with []"
                (let [result (#'sut/spec->str
                              (sut/spec
                               (sut/field ::sut/name :tags ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/many ::sut/description "Tags")))]
                  (expect (str/includes? result "// Tags (required)"))
                  (expect (str/includes? result "tags: string[],")))))

  (describe "namespaced keywords"
            (it "converts single namespace to nested object"
                (let [result (#'sut/spec->str
                              (sut/spec
                               (sut/field ::sut/name :address/street ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Street")))]
                  (expect (str/includes? result "address: {"))
                  (expect (str/includes? result "// Street (required)"))
                  (expect (str/includes? result "street: string,"))))

            (it "converts deeply nested namespace to nested objects"
                (let [result (#'sut/spec->str
                              (sut/spec
                               (sut/field ::sut/name :org.division.team/name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Team name")))]
                  (expect (str/includes? result "team: {"))
                  (expect (str/includes? result "// Team name (required)"))
                  (expect (str/includes? result "name: string,"))))))

(defdescribe str->data-test
  "Tests for JSON parsing"

  (describe "valid JSON"
            (it "parses simple object"
                (expect (= (sut/str->data "{\"name\": \"John\"}")
                           {:name "John"})))

            (it "parses integer value"
                (expect (= (sut/str->data "{\"age\": 30}")
                           {:age 30})))

            (it "parses boolean true"
                (expect (= (sut/str->data "{\"active\": true}")
                           {:active true})))

            (it "parses float value"
                (expect (= (sut/str->data "{\"score\": 3.14}")
                           {:score 3.14})))

            (it "parses null value"
                (expect (= (sut/str->data "{\"data\": null}")
                           {:data nil}))))

  (describe "nested objects"
            (it "parses deeply nested object"
                (expect (= (sut/str->data "{\"a\": {\"b\": {\"c\": 1}}}")
                           {:a {:b {:c 1}}})))

            (it "parses multiple nested fields"
                (expect (= (sut/str->data "{\"address\": {\"street\": \"Main St\", \"city\": \"Boston\"}}")
                           {:address {:street "Main St" :city "Boston"}}))))

  (describe "arrays"
            (it "parses simple array"
                (expect (= (sut/str->data "{\"tags\": [\"a\", \"b\"]}")
                           {:tags ["a" "b"]})))

            (it "parses array of objects"
                (expect (= (sut/str->data "{\"items\": [{\"name\": \"x\", \"value\": 1}]}")
                           {:items [{:name "x" :value 1}]}))))

  (describe "malformed JSON - unquoted"
            (it "handles unquoted keys"
                (expect (= (sut/str->data "{name: \"John\"}")
                           {:name "John"})))

            (it "handles unquoted string values"
                (expect (= (sut/str->data "{\"name\": John}")
                           {:name "John"}))))

  (describe "malformed JSON - trailing commas"
            (it "handles trailing comma in object"
                (expect (= (sut/str->data "{\"name\": \"John\",}")
                           {:name "John"})))

            (it "handles trailing comma in array"
                (expect (= (sut/str->data "{\"tags\": [\"a\", \"b\",]}")
                           {:tags ["a" "b"]}))))

  (describe "markdown code blocks"
            (it "extracts JSON from markdown"
                (expect (= (sut/str->data "```json\n{\"name\": \"John\"}\n```")
                           {:name "John"})))

            (it "handles code block without language tag"
                (expect (= (sut/str->data "```\n{\"name\": \"John\"}\n```")
                           {:name "John"}))))

  (describe "complex example"
            (it "parses mixed data types"
                (expect (= (sut/str->data "{\"name\": \"John\", \"age\": 30, \"tags\": [\"dev\"], \"address\": {\"city\": \"NYC\"}}")
                           {:name "John" :age 30 :tags ["dev"] :address {:city "NYC"}})))))

(defdescribe data->str-test
  "Tests for JSON serialization"

  (describe "basic serialization"
            (it "serializes simple map to JSON"
                (let [result (sut/data->str {:name "John" :age 30})]
                  (expect (str/includes? result "\"name\""))
                  (expect (str/includes? result "\"John\""))
                  (expect (str/includes? result "\"age\""))
                  (expect (str/includes? result "30"))))

            (it "serializes nested map"
                (let [result (sut/data->str {:a {:b {:c 1}}})]
                  (expect (str/includes? result "\"a\""))
                  (expect (str/includes? result "\"b\""))
                  (expect (str/includes? result "\"c\""))))

            (it "serializes arrays"
                (let [result (sut/data->str {:tags ["a" "b"]})]
                  (expect (str/includes? result "\"tags\""))
                  (expect (str/includes? result "["))
                  (expect (str/includes? result "]")))))

  (describe "date/datetime serialization"
            (it "serializes LocalDate to ISO string"
                (let [date (LocalDate/of 2024 1 15)
                      result (sut/data->str {:date date})]
                  (expect (str/includes? result "\"2024-01-15\""))))

            (it "serializes OffsetDateTime to ISO string"
                (let [dt (OffsetDateTime/parse "2024-01-15T10:30:00Z")
                      result (sut/data->str {:datetime dt})]
                  (expect (str/includes? result "\"2024-01-15T10:30Z\"")))))

  (describe "roundtrip tests"
            (it "roundtrips simple map"
                (expect (= (sut/str->data (sut/data->str {:name "John" :age 30}))
                           {:name "John" :age 30})))

            (it "roundtrips nested map"
                (expect (= (sut/str->data (sut/data->str {:a {:b {:c 1}}}))
                           {:a {:b {:c 1}}})))

            (it "roundtrips vector"
                (expect (= (sut/str->data (sut/data->str {:tags ["a" "b"]}))
                           {:tags ["a" "b"]})))

            (it "roundtrips array of objects"
                (expect (= (sut/str->data (sut/data->str {:items [{:x 1} {:x 2}]}))
                           {:items [{:x 1} {:x 2}]})))))

(defdescribe validate-data-test
  "Tests for data validation"

  (describe "valid data"
            (it "validates simple valid data"
                (let [s (sut/spec (sut/field ::sut/name :name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Name"))]
                  (expect (= {:valid? true} (sut/validate-data s {:name "John"})))))

            (it "validates multiple fields"
                (let [s (sut/spec (sut/field ::sut/name :name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Name")
                                  (sut/field ::sut/name :age ::sut/type :spec.type/int ::sut/cardinality :spec.cardinality/one ::sut/description "Age"))]
                  (expect (= {:valid? true} (sut/validate-data s {:name "John" :age 30}))))))

  (describe "missing required field"
            (it "returns invalid for missing required field"
                (let [s (sut/spec (sut/field ::sut/name :name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Name"))]
                  (expect (not (:valid? (sut/validate-data s {})))))))

  (describe "optional fields"
            (it "accepts nil for optional field"
                (let [s (sut/spec (sut/field ::sut/name :nick ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Nick" ::sut/required false))]
                  (expect (= {:valid? true} (sut/validate-data s {:nick nil})))))

            (it "accepts missing optional field"
                (let [s (sut/spec (sut/field ::sut/name :nick ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Nick" ::sut/required false))]
                  (expect (= {:valid? true} (sut/validate-data s {}))))))

  (describe "type mismatch"
            (it "returns invalid for wrong type"
                (let [s (sut/spec (sut/field ::sut/name :age ::sut/type :spec.type/int ::sut/cardinality :spec.cardinality/one ::sut/description "Age"))]
                  (expect (not (:valid? (sut/validate-data s {:age "thirty"})))))))

  (describe "enum validation"
            (it "validates correct enum value"
                (let [s (sut/spec (sut/field ::sut/name :role ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Role"
                                             ::sut/values {"admin" "Full access" "user" "Standard access"}))]
                  (expect (= {:valid? true} (sut/validate-data s {:role "admin"})))))

            (it "returns invalid for wrong enum value"
                (let [s (sut/spec (sut/field ::sut/name :role ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Role"
                                             ::sut/values {"admin" "Full access" "user" "Standard access"}))]
                  (expect (not (:valid? (sut/validate-data s {:role "guest"})))))))

  (describe "cardinality many"
            (it "validates vector of correct type"
                (let [s (sut/spec (sut/field ::sut/name :tags ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/many ::sut/description "Tags"))]
                  (expect (= {:valid? true} (sut/validate-data s {:tags ["a" "b"]})))))

            (it "returns invalid for wrong type in vector"
                (let [s (sut/spec (sut/field ::sut/name :nums ::sut/type :spec.type/int ::sut/cardinality :spec.cardinality/many ::sut/description "Numbers"))]
                  (expect (not (:valid? (sut/validate-data s {:nums [1 "two" 3]})))))))

  (describe "nested paths"
            (it "validates nested path"
                (let [s (sut/spec (sut/field ::sut/name :address/city ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "City"))]
                  (expect (= {:valid? true} (sut/validate-data s {:address {:city "NYC"}})))))

            (it "returns invalid for missing nested required field"
                (let [s (sut/spec (sut/field ::sut/name :address/city ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "City"))]
                  (expect (not (:valid? (sut/validate-data s {:address {}}))))))))

(defdescribe spec->prompt-test
  "Tests for BAML-style prompt generation"

  (it "generates BAML-style prompt"
      (let [s (sut/spec (sut/field ::sut/name :x ::sut/type :spec.type/int ::sut/cardinality :spec.cardinality/one ::sut/description "X"))
            result (sut/spec->prompt s)]
        (expect (str/includes? result "Answer in JSON using this schema:"))
        (expect (str/includes? result "// X (required)"))
        (expect (str/includes? result "x: int,"))))

  (it "includes nested objects in prompt"
      (let [s (sut/spec (sut/field ::sut/name :address/city ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "City"))
            result (sut/spec->prompt s)]
        (expect (str/includes? result "address: {"))
        (expect (str/includes? result "// City (required)"))
        (expect (str/includes? result "city: string,"))))

  (it "includes array types in prompt"
      (let [s (sut/spec (sut/field ::sut/name :tags ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/many ::sut/description "Tags"))
            result (sut/spec->prompt s)]
        (expect (str/includes? result "// Tags (required)"))
        (expect (str/includes? result "tags: string[],"))))

  (it "generates prompt for fixed-size vector types"
      (let [s (sut/spec (sut/field ::sut/name :bbox ::sut/type :spec.type/int-v-4 ::sut/cardinality :spec.cardinality/one ::sut/description "Bounding box"))
            result (sut/spec->prompt s)]
        (expect (str/includes? result "bbox: int[4],"))
        (expect (str/includes? result "(exactly 4 elements)")))))

(defdescribe field-test
  "Tests for field definition function"

  (describe "basic field creation"
            (it "creates basic field with description"
                (expect (= (sut/field ::sut/name :name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "User name")
                           {::sut/name :name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "User name"})))

            (it "creates field with cardinality many"
                (expect (= (sut/field ::sut/name :tags ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/many ::sut/description "Tags list")
                           {::sut/name :tags ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/many ::sut/description "Tags list"})))

            (it "creates optional field with nil union"
                (expect (= (sut/field ::sut/name :nick ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Nickname" ::sut/required false)
                           {::sut/name :nick ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Nickname" ::sut/union #{::sut/nil}}))))

  (describe "enum values"
            (it "creates field with enum values map"
                (expect (= (sut/field ::sut/name :role ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Role"
                                      ::sut/values {"admin" "Full access" "user" "Standard access"})
                           {::sut/name :role ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Role"
                            ::sut/values {"admin" "Full access" "user" "Standard access"}})))

            (it "throws when values is a vector instead of map"
                (expect (throws? clojure.lang.ExceptionInfo
                                 #(sut/field ::sut/name :role ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one
                                             ::sut/description "Role" ::sut/values ["admin" "user"]))))

            (it "throws when values map has nil description"
                (expect (throws? clojure.lang.ExceptionInfo
                                 #(sut/field ::sut/name :role ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one
                                             ::sut/description "Role" ::sut/values {"admin" nil "user" "Standard access"})))))

  (describe "nested paths"
            (it "creates field with namespaced keyword path"
                (expect (= (sut/field ::sut/name :address/street ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Street")
                           {::sut/name :address/street ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Street"})))

            (it "creates field with deeply nested path"
                (expect (= (sut/field ::sut/name :org.division.team/name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Team name")
                           {::sut/name :org.division.team/name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Team name"}))))

  (describe "invalid keyword format (dots in name)"
            (it "throws on keyword with dot in name part"
                (expect (throws? clojure.lang.ExceptionInfo
                                 #(sut/field ::sut/name :users.name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Invalid"))))

            (it "allows dots in namespace (Datomic style)"
                (expect (= {::sut/name :org.division/name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Valid"}
                           (sut/field ::sut/name :org.division/name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Valid")))))

  (describe "invalid descriptions"
            (it "throws on description with brackets"
                (expect (throws? clojure.lang.ExceptionInfo
                                 #(sut/field ::sut/name :x ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Name [required]"))))

            (it "throws on description with equals sign"
                (expect (throws? clojure.lang.ExceptionInfo
                                 #(sut/field ::sut/name :x ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Type=value")))))

  (describe "missing required options"
            (it "throws when name is missing"
                (expect (throws? clojure.lang.ExceptionInfo
                                 #(sut/field ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "No name"))))

            (it "throws when type is missing"
                (expect (throws? clojure.lang.ExceptionInfo
                                 #(sut/field ::sut/name :x ::sut/cardinality :spec.cardinality/one ::sut/description "No type"))))

            (it "throws when cardinality is missing"
                (expect (throws? clojure.lang.ExceptionInfo
                                 #(sut/field ::sut/name :x ::sut/type :spec.type/string ::sut/description "No cardinality"))))

            (it "throws when description is missing"
                (expect (throws? clojure.lang.ExceptionInfo
                                 #(sut/field ::sut/name :x ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one)))))

  (describe "invalid options"
            (it "throws on invalid type"
                (expect (throws? clojure.lang.ExceptionInfo
                                 #(sut/field ::sut/name :x ::sut/type ::sut/invalid ::sut/cardinality :spec.cardinality/one ::sut/description "Bad type"))))

            (it "throws on invalid cardinality"
                (expect (throws? clojure.lang.ExceptionInfo
                                 #(sut/field ::sut/name :x ::sut/type :spec.type/string ::sut/cardinality ::sut/invalid ::sut/description "Bad card"))))))

(defdescribe spec-test
  "Tests for spec creation"

  (it "creates spec from field definitions"
      (expect (= (sut/spec
                  (sut/field ::sut/name :name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Name")
                  (sut/field ::sut/name :age ::sut/type :spec.type/int ::sut/cardinality :spec.cardinality/one ::sut/description "Age"))
                 {::sut/fields [{::sut/name :name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Name"}
                                {::sut/name :age ::sut/type :spec.type/int ::sut/cardinality :spec.cardinality/one ::sut/description "Age"}]}))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(defdescribe integration-test
  "End-to-end integration tests: spec -> prompt -> parse -> validate"

  (describe "full roundtrip with valid JSON"
            (it "handles simple spec roundtrip"
                (let [s (sut/spec
                         (sut/field ::sut/name :name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "User name")
                         (sut/field ::sut/name :age ::sut/type :spec.type/int ::sut/cardinality :spec.cardinality/one ::sut/description "Age"))
                      prompt (sut/spec->prompt s)
                      llm-response "{\"name\": \"Alice\", \"age\": 30}"
                      parsed (sut/str->data llm-response)
                      validation (sut/validate-data s parsed)]
                  (expect (str/includes? prompt "Answer in JSON using this schema:"))
                  (expect (= {:name "Alice" :age 30} parsed))
                  (expect (= {:valid? true} validation)))))

  (describe "roundtrip with malformed JSON"
            (it "handles unquoted keys and values"
                (let [s (sut/spec
                         (sut/field ::sut/name :name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Name"))
                      llm-response "{name: Alice}"
                      parsed (sut/str->data llm-response)
                      validation (sut/validate-data s parsed)]
                  (expect (= {:name "Alice"} parsed))
                  (expect (= {:valid? true} validation))))

            (it "handles trailing commas"
                (let [s (sut/spec
                         (sut/field ::sut/name :items ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/many ::sut/description "Items"))
                      llm-response "{\"items\": [\"a\", \"b\",]}"
                      parsed (sut/str->data llm-response)
                      validation (sut/validate-data s parsed)]
                  (expect (= {:items ["a" "b"]} parsed))
                  (expect (= {:valid? true} validation))))

            (it "handles markdown code blocks"
                (let [s (sut/spec
                         (sut/field ::sut/name :value ::sut/type :spec.type/int ::sut/cardinality :spec.cardinality/one ::sut/description "Value"))
                      llm-response "Here's the data:\n```json\n{\"value\": 42}\n```"
                      parsed (sut/str->data llm-response)
                      validation (sut/validate-data s parsed)]
                  (expect (= {:value 42} parsed))
                  (expect (= {:valid? true} validation)))))

  (describe "roundtrip with nested objects"
            (it "handles nested objects from namespaced keywords"
                (let [s (sut/spec
                         (sut/field ::sut/name :address/street ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Street")
                         (sut/field ::sut/name :address/city ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "City"))
                      prompt (sut/spec->prompt s)
                      llm-response "{\"address\": {\"street\": \"Main St\", \"city\": \"Boston\"}}"
                      parsed (sut/str->data llm-response)
                      validation (sut/validate-data s parsed)]
                  (expect (str/includes? prompt "address: {"))
                  (expect (= {:address {:street "Main St" :city "Boston"}} parsed))
                  (expect (= {:valid? true} validation)))))

  (describe "validation failures"
            (it "detects missing required fields"
                (let [s (sut/spec
                         (sut/field ::sut/name :name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Name"))
                      llm-response "{}"
                      parsed (sut/str->data llm-response)
                      validation (sut/validate-data s parsed)]
                  (expect (= false (:valid? validation)))
                  (expect (some #(= :missing-required-field (:error %)) (:errors validation)))))

            (it "detects type mismatches"
                (let [s (sut/spec
                         (sut/field ::sut/name :age ::sut/type :spec.type/int ::sut/cardinality :spec.cardinality/one ::sut/description "Age"))
                      llm-response "{\"age\": \"thirty\"}"
                      parsed (sut/str->data llm-response)
                      validation (sut/validate-data s parsed)]
                  (expect (= false (:valid? validation)))
                  (expect (some #(= :type-mismatch (:error %)) (:errors validation)))))

            (it "detects invalid enum values"
                (let [s (sut/spec
                         (sut/field ::sut/name :role ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Role"
                                    ::sut/values {"admin" "Full access" "user" "Standard access"}))
                      llm-response "{\"role\": \"guest\"}"
                      parsed (sut/str->data llm-response)
                      validation (sut/validate-data s parsed)]
                  (expect (= false (:valid? validation)))
                  (expect (some #(= :invalid-enum-value (:error %)) (:errors validation)))))))

;; =============================================================================
;; Reference Field Tests
;; =============================================================================

(defdescribe build-ref-registry-test
  "Tests for build-ref-registry function"

  (it "builds registry from refs in spec"
      (let [addr-spec (sut/spec :Address
                                (sut/field ::sut/name :street ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Street"))
            person-spec (sut/spec :Person
                                  {:refs [addr-spec]}
                                  (sut/field ::sut/name :name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Name"))
            registry (sut/build-ref-registry person-spec)]
        (expect (contains? registry :Address))
        (expect (= addr-spec (get registry :Address)))))

  (it "returns empty map for spec with no refs"
      (let [s (sut/spec (sut/field ::sut/name :x ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "X"))]
        (expect (= {} (sut/build-ref-registry s)))))

  (it "throws on duplicate spec names"
      (let [spec1 (sut/spec :Dup (sut/field ::sut/name :a ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "A"))
            spec2 (sut/spec :Dup (sut/field ::sut/name :b ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "B"))
            parent (sut/spec :Parent {:refs [spec1 spec2]} (sut/field ::sut/name :x ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "X"))]
        (expect (throws? clojure.lang.ExceptionInfo
                         #(sut/build-ref-registry parent))))))

(defdescribe ref-field-test
  "Tests for ::ref field type"

  (describe "ref validation"
            (it "throws when ref target is not in refs"
                (expect (throws? clojure.lang.ExceptionInfo
                                 #(sut/spec
                                   (sut/field ::sut/name :items
                                              ::sut/type :spec.type/ref
                                              ::sut/cardinality :spec.cardinality/many
                                              ::sut/description "Items"
                                              ::sut/target :item)))))

            (it "allows ref when target exists in refs"
                (let [item-spec (sut/spec :item
                                          (sut/field ::sut/name :name ::sut/type :spec.type/string
                                                     ::sut/cardinality :spec.cardinality/one ::sut/description "Name"))
                      result (sut/spec
                              {:refs [item-spec]}
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

            (it "strips ! from bang keywords"
                (let [result (#'sut/keyword->path :reset!)]
                  (expect (= "reset" result))))

            (it "handles namespaced keywords with special chars"
                (let [result (#'sut/keyword->path :claims/verifiable?)]
                  (expect (= "claims.verifiable" result)))))

  (describe "spec->str generates correct BAML for predicate fields"
            (it "strips ? from field name in output"
                (let [result (#'sut/spec->str
                              (sut/spec
                               (sut/field ::sut/name :valid? ::sut/type :spec.type/bool ::sut/cardinality :spec.cardinality/one ::sut/description "Is valid")))]
                  (expect (str/includes? result "valid: bool,"))
                  (expect (not (str/includes? result "valid?:"))))))

  (describe "str->data-with-spec remaps keys correctly"
            (it "remaps simple predicate key"
                (let [s (sut/spec (sut/field ::sut/name :valid? ::sut/type :spec.type/bool ::sut/cardinality :spec.cardinality/one ::sut/description "Valid"))
                      result (sut/str->data-with-spec "{\"valid\": true}" s)]
                  (expect (contains? result :valid?))
                  (expect (= true (:valid? result)))))

            (it "handles multiple special char fields"
                (let [s (sut/spec
                         (sut/field ::sut/name :active? ::sut/type :spec.type/bool ::sut/cardinality :spec.cardinality/one ::sut/description "Active")
                         (sut/field ::sut/name :confirmed! ::sut/type :spec.type/bool ::sut/cardinality :spec.cardinality/one ::sut/description "Confirmed"))
                      result (sut/str->data-with-spec "{\"active\": true, \"confirmed\": false}" s)]
                  (expect (contains? result :active?))
                  (expect (contains? result :confirmed!))
                  (expect (= true (:active? result)))
                  (expect (= false (:confirmed! result)))))))

;; =============================================================================
;; Keyword Type Tests
;; =============================================================================

(defdescribe keyword-type-test
  "Tests for :spec.type/keyword - rendered as string, keywordized on parse"

  (describe "spec->str renders keyword as string"
            (it "outputs string type in BAML"
                (let [result (#'sut/spec->str
                              (sut/spec
                               (sut/field ::sut/name :status
                                          ::sut/type :spec.type/keyword
                                          ::sut/cardinality :spec.cardinality/one
                                          ::sut/description "Current status")))]
                  (expect (str/includes? result "status: string,")))))

  (describe "str->data-with-spec converts to keywords"
            (it "converts single keyword field"
                (let [s (sut/spec
                         (sut/field ::sut/name :status
                                    ::sut/type :spec.type/keyword
                                    ::sut/cardinality :spec.cardinality/one
                                    ::sut/description "Status"))
                      result (sut/str->data-with-spec "{\"status\": \"active\"}" s)]
                  (expect (= :active (:status result)))
                  (expect (keyword? (:status result)))))

            (it "converts keyword field in array"
                (let [s (sut/spec
                         (sut/field ::sut/name :tags
                                    ::sut/type :spec.type/keyword
                                    ::sut/cardinality :spec.cardinality/many
                                    ::sut/description "Tags"))
                      result (sut/str->data-with-spec "{\"tags\": [\"foo\", \"bar\", \"baz\"]}" s)]
                  (expect (= [:foo :bar :baz] (:tags result)))
                  (expect (every? keyword? (:tags result)))))))

;; =============================================================================
;; Fixed-Size Vector Type Tests
;; =============================================================================

(defdescribe fixed-size-vector-type-test
  "Tests for fixed-size vector types: :spec.type/int-v-N, :spec.type/string-v-N, :spec.type/double-v-N"

  (describe "parse-vector-type"
            (it "parses int-v-N format"
                (let [result (#'sut/parse-vector-type :spec.type/int-v-4)]
                  (expect (= {:base-type :int :size 4} result))))

            (it "parses string-v-N format"
                (let [result (#'sut/parse-vector-type :spec.type/string-v-2)]
                  (expect (= {:base-type :string :size 2} result))))

            (it "parses double-v-N format"
                (let [result (#'sut/parse-vector-type :spec.type/double-v-3)]
                  (expect (= {:base-type :double :size 3} result))))

            (it "returns nil for standard types"
                (expect (nil? (#'sut/parse-vector-type :spec.type/string)))
                (expect (nil? (#'sut/parse-vector-type :spec.type/int)))))

  (describe "field creation with vector types"
            (it "accepts int-v-N type"
                (let [f (sut/field ::sut/name :bbox
                                   ::sut/type :spec.type/int-v-4
                                   ::sut/cardinality :spec.cardinality/one
                                   ::sut/description "Bounding box coordinates")]
                  (expect (= :spec.type/int-v-4 (::sut/type f)))))

            (it "accepts string-v-N type"
                (let [f (sut/field ::sut/name :names
                                   ::sut/type :spec.type/string-v-2
                                   ::sut/cardinality :spec.cardinality/one
                                   ::sut/description "First and last name")]
                  (expect (= :spec.type/string-v-2 (::sut/type f))))))

  (describe "spec->str BAML generation"
            (it "renders int-v-N as int[N]"
                (let [result (#'sut/spec->str
                              (sut/spec
                               (sut/field ::sut/name :bbox
                                          ::sut/type :spec.type/int-v-4
                                          ::sut/cardinality :spec.cardinality/one
                                          ::sut/description "Bounding box")))]
                  (expect (str/includes? result "bbox: int[4],"))
                  (expect (str/includes? result "(exactly 4 elements)")))))

  (describe "validate-data with vector types"
            (it "validates correct int vector"
                (let [s (sut/spec
                         (sut/field ::sut/name :bbox
                                    ::sut/type :spec.type/int-v-4
                                    ::sut/cardinality :spec.cardinality/one
                                    ::sut/description "Bounding box"))
                      result (sut/validate-data s {:bbox [10 20 100 200]})]
                  (expect (= {:valid? true} result))))

            (it "rejects vector with wrong size"
                (let [s (sut/spec
                         (sut/field ::sut/name :bbox
                                    ::sut/type :spec.type/int-v-4
                                    ::sut/cardinality :spec.cardinality/one
                                    ::sut/description "Bounding box"))
                      result (sut/validate-data s {:bbox [10 20 100]})]
                  (expect (not (:valid? result)))))

            (it "rejects vector with wrong element type"
                (let [s (sut/spec
                         (sut/field ::sut/name :bbox
                                    ::sut/type :spec.type/int-v-4
                                    ::sut/cardinality :spec.cardinality/one
                                    ::sut/description "Bounding box"))
                      result (sut/validate-data s {:bbox [10 "twenty" 100 200]})]
                  (expect (not (:valid? result))))))

  (describe "full roundtrip with vector types"
            (it "spec -> prompt -> parse -> validate for int-v-4"
                (let [s (sut/spec
                         (sut/field ::sut/name :bbox
                                    ::sut/type :spec.type/int-v-4
                                    ::sut/cardinality :spec.cardinality/one
                                    ::sut/description "Bounding box"))
                      prompt (sut/spec->prompt s)
                      llm-response "{\"bbox\": [10, 20, 100, 200]}"
                      parsed (sut/str->data llm-response)
                      validation (sut/validate-data s parsed)]
                  (expect (str/includes? prompt "bbox: int[4],"))
                  (expect (= [10 20 100 200] (:bbox parsed)))
                  (expect (= {:valid? true} validation))))))

;; =============================================================================
;; Enum Value Descriptions in Output
;; =============================================================================

(defdescribe enum-value-descriptions-test
  "Tests for rendering enum value descriptions in BAML output"

  (it "includes enum value descriptions as comments"
      (let [result (#'sut/spec->str
                    (sut/spec
                     (sut/field ::sut/name :status
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
      (let [result (#'sut/spec->str
                    (sut/spec
                     (sut/field ::sut/name :priority
                                ::sut/type :spec.type/string
                                ::sut/cardinality :spec.cardinality/one
                                ::sut/description "Priority level"
                                ::sut/values {"high" "Urgent"
                                              "low" "Can wait"
                                              "medium" "Normal priority"})))
            high-pos (str/index-of result "\"high\"")
            low-pos (str/index-of result "\"low\"")
            medium-pos (str/index-of result "\"medium\"")]
        (expect (< high-pos low-pos))
        (expect (< low-pos medium-pos)))))

;; =============================================================================
;; Bare Array Auto-Wrapping
;; =============================================================================

(defdescribe bare-array-wrapping-test
  "Tests for auto-wrapping bare arrays when spec expects object with single array field.
   LLMs often return `[...]` when asked for a list, even when spec defines `{nodes: [...]}`."

  (describe "spec with single cardinality-many field"
            (it "wraps bare array in the expected field name"
                (let [spec-def (sut/spec
                                (sut/field ::sut/name :nodes
                                           ::sut/type :spec.type/string
                                           ::sut/cardinality :spec.cardinality/many
                                           ::sut/description "List of nodes"))
            ;; LLM returns bare array instead of {nodes: [...]}
                      llm-response "[\"a\", \"b\", \"c\"]"
                      parsed (sut/str->data-with-spec llm-response spec-def)]
                  (expect (= {:nodes ["a" "b" "c"]} parsed))))

            (it "works with complex objects in array"
                (let [item-spec (sut/spec :Item
                                          (sut/field ::sut/name :id ::sut/type :spec.type/int ::sut/cardinality :spec.cardinality/one ::sut/description "ID")
                                          (sut/field ::sut/name :name ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/one ::sut/description "Name"))
                      spec-def (sut/spec {:refs [item-spec]}
                                         (sut/field ::sut/name :items
                                                    ::sut/type :spec.type/ref
                                                    ::sut/target :Item
                                                    ::sut/cardinality :spec.cardinality/many
                                                    ::sut/description "List of items"))
                      llm-response "[{\"id\": 1, \"name\": \"first\"}, {\"id\": 2, \"name\": \"second\"}]"
                      parsed (sut/str->data-with-spec llm-response spec-def)]
                  (expect (= {:items [{:id 1 :name "first"} {:id 2 :name "second"}]} parsed)))))

  (describe "spec with multiple fields - no wrapping"
            (it "returns bare array as-is when spec has multiple fields"
                (let [spec-def (sut/spec
                                (sut/field ::sut/name :items ::sut/type :spec.type/string ::sut/cardinality :spec.cardinality/many ::sut/description "Items")
                                (sut/field ::sut/name :count ::sut/type :spec.type/int ::sut/cardinality :spec.cardinality/one ::sut/description "Count"))
                      llm-response "[\"a\", \"b\"]"
                      parsed (sut/str->data-with-spec llm-response spec-def)]
        ;; Should NOT wrap because spec has multiple fields
                  (expect (= ["a" "b"] parsed)))))

  (describe "normal object response - no wrapping needed"
            (it "passes through object responses unchanged"
                (let [spec-def (sut/spec
                                (sut/field ::sut/name :nodes
                                           ::sut/type :spec.type/string
                                           ::sut/cardinality :spec.cardinality/many
                                           ::sut/description "List of nodes"))
            ;; LLM returns properly wrapped object
                      llm-response "{\"nodes\": [\"a\", \"b\", \"c\"]}"
                      parsed (sut/str->data-with-spec llm-response spec-def)]
                  (expect (= {:nodes ["a" "b" "c"]} parsed))))))
