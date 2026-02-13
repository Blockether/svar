(ns com.blockether.svar.spec
  "Structured output specification system for LLM responses.
   
   This namespace provides a DSL for defining expected output structures,
   converting specs to LLM prompts, and parsing LLM responses back to Clojure data.
   
   Data Flow:
   1. Define spec with `spec` and `field` functions
   2. Generate prompt with `spec->prompt` (sent to LLM)
   3. Parse response with `str->data` (LLM response -> Clojure map)
   4. Optionally validate with `validate-data`
   5. Optionally serialize with `data->str`"
  (:require
   [charred.api :as json]
   [clojure.set :as set]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.internal.jsonish :as jsonish]
   [taoensso.trove :as trove])
  (:import
   [java.time LocalDate OffsetDateTime ZonedDateTime]))

;; =============================================================================
;; Spec DSL
;; =============================================================================

(def ^:private SPECIAL_CHARS_PATTERN
  "Pattern for Clojure special chars that are not valid in JSON keys.
   Includes: ? (predicates), ! (side-effects), * (dynamic vars), + (constants)."
  #"[?!*+]")

(defn- strip-special-chars
  "Strips Clojure special characters from a string for JSON key compatibility.
   
   Params:
   `s` - String. Input string potentially containing special chars.
   
   Returns:
   String. Input with ?!*+ characters removed."
  [s]
  (str/replace s SPECIAL_CHARS_PATTERN ""))

(defn- keyword->path
  "Converts a Datomic-style namespaced keyword to a dot-separated path string.
   Strips special Clojure chars (?!*+) from the field name for JSON compatibility.
   
   :name -> \"name\"
   :valid? -> \"valid\"
   :org/name -> \"org.name\"
   :claims/verifiable? -> \"claims.verifiable\"
   :org.division.team/name -> \"org.division.team.name\""
  [kw]
  (let [field-name (strip-special-chars (name kw))]
    (if-let [ns (namespace kw)]
      (str ns "." field-name)
      field-name)))

(defn- build-key-mapping
  "Builds a mapping from JSON path strings to original spec field keywords.
   
   This enables round-trip preservation of Clojure special chars (?!*+) that
   are stripped for JSON compatibility but needed in the final Clojure data.
   
   Params:
   `spec-def` - Map. Spec definition with ::fields key.
   
   Returns:
   Map. JSON path string -> original keyword (e.g., {\"verifiable\" :verifiable?})."
  [spec-def]
  (let [fields (::fields spec-def)]
    (reduce (fn [acc field-def]
              (let [field-name (::name field-def)
                    json-path (keyword->path field-name)
                    ;; Get the simple key (last segment of path)
                    simple-key (last (str/split json-path #"\."))
                    ;; Get the original simple name (with special chars)
                    original-simple (keyword (name field-name))]
                ;; Only add mapping if they differ (special chars were stripped)
                (if (not= simple-key (name original-simple))
                  (assoc acc simple-key original-simple)
                  acc)))
            {}
            fields)))

(defn- remap-keys
  "Recursively remaps keys in parsed data using the spec-derived key mapping.
   
   Params:
   `data` - Parsed Clojure data (map, vector, or primitive).
   `key-mapping` - Map of JSON key string -> original keyword.
   
   Returns:
   Data with keys remapped to original spec keywords."
  [data key-mapping]
  (cond
    (map? data)
    (into {}
          (map (fn [[k v]]
                 (let [key-str (name k)
                       ;; Look up in mapping, or keep original
                       remapped-key (get key-mapping key-str k)]
                   [remapped-key (remap-keys v key-mapping)]))
               data))

    (vector? data)
    (mapv #(remap-keys % key-mapping) data)

    :else
    data))

;; Type keywords - using namespace alias pattern :spec.type/*
(def ^:private VALID_TYPES
  "Valid field types using Datomic-style namespaced keywords."
  #{:spec.type/string :spec.type/int :spec.type/float :spec.type/bool
    :spec.type/date :spec.type/datetime :spec.type/ref :spec.type/keyword})

;; Fixed-size vector type patterns: :spec.type/int-v-N, :spec.type/string-v-N, :spec.type/double-v-N
(def ^:private VECTOR_TYPE_PATTERN
  "Regex pattern for fixed-size vector types like :spec.type/int-v-4"
  #"^(int|string|double)-v-(\d+)$")

(defn- parse-vector-type
  "Parses a fixed-size vector type keyword like :spec.type/int-v-4.
   
   Params:
   `type-kw` - Keyword. The type keyword to parse.
   
   Returns:
   Map with :base-type and :size if it's a vector type, nil otherwise.
   
   Examples:
   :spec.type/int-v-4 -> {:base-type :int :size 4}
   :spec.type/string-v-2 -> {:base-type :string :size 2}
   :spec.type/double-v-3 -> {:base-type :double :size 3}
   :spec.type/string -> nil"
  [type-kw]
  (when (and (keyword? type-kw) (= "spec.type" (namespace type-kw)))
    (when-let [match (re-matches VECTOR_TYPE_PATTERN (name type-kw))]
      (let [[_ base-type size-str] match
            size (parse-long size-str)]
        (when (and size (pos? (long size)))
          {:base-type (keyword base-type)
           :size size})))))

(defn- valid-type?
  "Checks if a type keyword is valid (either a base type or a fixed-size vector type).
   
   Params:
   `type-kw` - Keyword. The type to validate.
   
   Returns:
   Boolean. True if the type is valid."
  [type-kw]
  (or (contains? VALID_TYPES type-kw)
      (some? (parse-vector-type type-kw))))

;; Cardinality keywords - using namespace alias pattern :spec.cardinality/*
(def ^:private VALID_CARDINALITIES
  "Valid field cardinalities using Datomic-style namespaced keywords."
  #{:spec.cardinality/one :spec.cardinality/many})

(def ^:private RESERVED_CHARS
  "Characters reserved for spec text format syntax."
  #{\[ \] \; \= \|})

(def ^:private VALUES_RESERVED_CHARS
  "Characters reserved for values in spec text format syntax.
   Includes comma (separator) and colon (value:description delimiter)."
  #{\, \: \[ \] \; \= \|})

(defn- validate-field-options
  "Validates that all required field options are present and valid.
   Throws ex-info if validation fails."
  [the-name the-type the-cardinality the-description the-target]
  (when (nil? the-name)
    (anomaly/incorrect! "Field ::name is required" {:option ::name}))
  (when-not (keyword? the-name)
    (anomaly/incorrect! "Field ::name must be a keyword" {:option ::name :value the-name}))
  ;; Validate Datomic-style keyword format: dots are only allowed in namespace, not in name
  ;; Valid: :name, :org/name, :org.division/name
  ;; Invalid: :users.name (dot in name part - use :users/name instead)
  (when (str/includes? (name the-name) ".")
    (anomaly/incorrect! (str "Field ::name contains dot in name part. "
                             "Use Datomic-style namespaced keywords instead. "
                             "Example: Use :users/name not :users.name")
                        {:option ::name
                         :value the-name
                         :hint (str "Change " the-name " to "
                                    (keyword (str/replace (name the-name) "." "/")))}))
  (when (nil? the-type)
    (anomaly/incorrect! "Field ::type is required" {:option ::type}))
  (when-not (valid-type? the-type)
    (anomaly/incorrect! "Field ::type must be one of the valid types or a fixed-size vector type (e.g., :spec.type/int-v-4)"
                        {:option ::type :value the-type :valid-types VALID_TYPES
                         :vector-type-examples [:spec.type/int-v-4 :spec.type/string-v-2 :spec.type/double-v-3]}))
  (when (nil? the-cardinality)
    (anomaly/incorrect! "Field ::cardinality is required" {:option ::cardinality}))
  (when-not (contains? VALID_CARDINALITIES the-cardinality)
    (anomaly/incorrect! "Field ::cardinality must be :spec.cardinality/one or :spec.cardinality/many"
                        {:option ::cardinality :value the-cardinality :valid-cardinalities VALID_CARDINALITIES}))
  (when (nil? the-description)
    (anomaly/incorrect! "Field ::description is required" {:option ::description}))
  ;; Validate ::target for :spec.type/ref types
  ;; Target can be a single keyword or a vector of keywords (union type)
  (when (= the-type :spec.type/ref)
    (when (nil? the-target)
      (anomaly/incorrect! "Field ::target is required when ::type is :spec.type/ref"
                          {:option ::target :type the-type})))
  (when (and the-target (not= the-type :spec.type/ref))
    (anomaly/incorrect! "Field ::target can only be used with ::type :spec.type/ref"
                        {:option ::target :type the-type :target the-target}))
  (when the-target
    (let [valid-target? (or (keyword? the-target)
                            (and (vector? the-target)
                                 (seq the-target)
                                 (every? keyword? the-target)))]
      (when-not valid-target?
        (anomaly/incorrect! "Field ::target must be a keyword or a vector of keywords (for union types)"
                            {:option ::target :value the-target}))))
  nil)

(defn- validate-description
  "Validates that a description doesn't contain reserved syntax characters.
   Throws ex-info if invalid characters are found."
  [description]
  (let [invalid-chars (filter RESERVED_CHARS description)]
    (when (seq invalid-chars)
      (anomaly/incorrect! "Description contains reserved characters"
                          {:description description
                           :invalid-chars (set invalid-chars)
                           :reserved-chars RESERVED_CHARS}))))

(defn- validate-enum-value
  "Validates that an enum value doesn't contain reserved syntax characters.
   Throws ex-info if invalid characters are found."
  [value]
  (let [invalid-chars (filter VALUES_RESERVED_CHARS value)]
    (when (seq invalid-chars)
      (anomaly/incorrect! "Enum value contains reserved characters"
                          {:value value
                           :invalid-chars (set invalid-chars)
                           :reserved-chars VALUES_RESERVED_CHARS}))))

(defn- process-values
  "Processes :values option - MUST be a map with value->description pairs.
   Vectors are NOT allowed - every enum value must have a description.
   Validates that values and descriptions don't contain reserved characters."
  [v]
  (when (vector? v)
    (anomaly/incorrect! "::values must be a map with descriptions, not a vector. Every enum value requires a description."
                        {:values v
                         :expected-format {"value1" "Description of value1"
                                           "value2" "Description of value2"}}))
  (when-not (map? v)
    (anomaly/incorrect! "::values must be a map with value->description pairs"
                        {:values v
                         :type (type v)}))
  ;; Validate all keys (enum values)
  (doseq [k (keys v)]
    (validate-enum-value k))
  ;; Validate all descriptions - every value MUST have a description
  (doseq [[value desc] v]
    (when (nil? desc)
      (anomaly/incorrect! "Every enum value must have a description"
                          {:value value :description desc}))
    (validate-description desc))
  v)

(defn field
  "Defines a spec field with namespaced keyword options.
   
   Params:
   `::name` - Keyword, required. Field path/name as Datomic-style keyword.
   `::type` - Keyword, required. One of:
     - :spec.type/string - String value
     - :spec.type/int - Integer value
     - :spec.type/float - Floating point value
     - :spec.type/bool - Boolean value
     - :spec.type/date - ISO date (YYYY-MM-DD)
     - :spec.type/datetime - ISO datetime
     - :spec.type/keyword - Clojure keyword (rendered as string, keywordized on parse)
     - :spec.type/ref - Reference to another spec
     - :spec.type/int-v-N - Fixed-size integer vector (e.g., :spec.type/int-v-4 for 4 ints)
     - :spec.type/string-v-N - Fixed-size string vector (e.g., :spec.type/string-v-2)
     - :spec.type/double-v-N - Fixed-size double vector (e.g., :spec.type/double-v-3)
   `::cardinality` - Keyword, required.
     - :spec.cardinality/one - Single value
     - :spec.cardinality/many - Vector of values
   `::description` - String, required. Human-readable description (no reserved chars).
   `::required` - Boolean, optional. false if field can be nil (default: true).
   `::values` - Map, optional. Enum constraint with value->description pairs.
     Every enum value MUST have a description explaining its meaning.
     Example: {\"admin\" \"Full system access\" \"user\" \"Standard access\"}
   `::target` - Keyword or vector of keywords, optional. Required when ::type is :spec.type/ref.
       Single keyword for simple ref, vector for union types (e.g., [:Heading :Paragraph :Image]).
    `::humanize?` - Boolean, optional. When true, marks this field for humanization
       when a :humanizer fn is passed to ask!. Defaults to false.
    
    Returns:
    Map. Field definition with keys ::name, ::type, ::cardinality, ::description,
    and optionally ::union (for optional fields), ::values (for enums), ::target (for refs),
    and ::humanize? (for humanization marking)."
  [& {the-name ::name the-type ::type the-cardinality ::cardinality
      the-description ::description the-required ::required the-values ::values
      the-target ::target the-humanize? ::humanize?
      :or {the-required true the-humanize? false}}]
  (validate-field-options the-name the-type the-cardinality the-description the-target)
  (validate-description the-description)
  (cond-> {::name the-name
           ::type the-type
           ::cardinality the-cardinality
           ::description the-description}
    (not the-required) (assoc ::union #{::nil})
    the-values (assoc ::values (process-values the-values))
    the-target (assoc ::target the-target)
    the-humanize? (assoc ::humanize? true)))

(defn spec
  "Creates a spec from field definitions.
   
   Params:
   `name` - Keyword, optional. Name of the spec (e.g., :Person, :Address).
   `opts` - Map, optional. Options map that may contain:
     `:refs` - Vector of referenced specs for union types.
     `::key-ns` - String. Namespace to add to all keys during parsing.
                  Example: \"page.node\" transforms :type to :page.node/type.
   `fields` - Field definitions. Variadic list of field maps created with `field`.
   
   Examples:
   (spec (field ...))                           ; Anonymous spec
   (spec :Person (field ...))                   ; Named spec
   (spec :Person {:refs [addr-spec]} (field ...)) ; Named spec with refs
   (spec :section {::key-ns \"page.node\"} (field ...)) ; Keys namespaced as :page.node/*
   
   Returns:
   Map. Spec definition with ::fields key and optionally ::spec-name, ::refs, and ::key-ns keys."
  [& args]
  (let [[spec-name opts-map fields] (cond
                                      ;; First arg is keyword (name)
                                      (keyword? (first args))
                                      (let [name-arg (first args)
                                            rest-args (rest args)]
                                        (if (and (map? (first rest-args))
                                                 (or (contains? (first rest-args) :refs)
                                                     (contains? (first rest-args) ::key-ns)))
                                          ;; Has opts map
                                          [name-arg (first rest-args) (rest rest-args)]
                                          ;; No opts map
                                          [name-arg nil rest-args]))

                                      ;; First arg is map (opts without name)
                                      (and (map? (first args))
                                           (or (contains? (first args) :refs)
                                               (contains? (first args) ::key-ns)))
                                      [nil (first args) (rest args)]

                                      ;; No name, no opts
                                      :else
                                      [nil nil args])
        ;; Extract key-ns from opts
        key-ns (::key-ns opts-map)
        ;; Validate key-ns if provided
        _ (when (and key-ns (not (string? key-ns)))
            (anomaly/incorrect! "::key-ns must be a string (e.g., \"page.node\")"
                                {:key-ns key-ns :type (type key-ns)}))
        ;; Validate refs if provided
        refs (when opts-map
               (let [ref-vec (:refs opts-map)]
                 (when ref-vec
                   (when-not (vector? ref-vec)
                     (anomaly/incorrect! "Refs must be a vector" {:refs ref-vec}))
                   (doseq [ref ref-vec]
                     (when-not (map? ref)
                       (anomaly/incorrect! "Each ref must be a spec map" {:ref ref}))
                     (when-not (contains? ref ::fields)
                       (anomaly/incorrect! "Each ref must have ::fields key" {:ref ref})))
                   ref-vec)))
        ;; Build set of available ref names
        available-refs (into #{} (map ::spec-name refs))
        ;; Find all :spec.type/ref fields with ::target
        ref-fields (filter #(= :spec.type/ref (::type %)) fields)
        ;; Validate that all targets exist in refs
        ;; Target can be a single keyword or a vector of keywords (union type)
        _ (doseq [field ref-fields]
            (let [target (::target field)
                  field-name (::name field)
                  ;; Normalize target to a vector for validation
                  targets (if (vector? target) target [target])]
              (doseq [t targets]
                (when-not (contains? available-refs t)
                  (anomaly/incorrect! (str "Field '" field-name "' references target '" t
                                           "' but no ref with that name exists. "
                                           "Add the referenced spec via {:refs [ref-spec]} parameter.")
                                      {:field field-name
                                       :target t
                                       :all-targets targets
                                       :available-refs available-refs
                                       :hint "Use (spec {:refs [referenced-spec]} (field ...)) to register refs"})))))]
    (cond-> {::fields (vec fields)}
      spec-name (assoc ::spec-name spec-name)
      refs (assoc ::refs refs)
      key-ns (assoc ::key-ns key-ns))))

(defn build-ref-registry
  "Builds a registry of referenced specs from a spec's ::refs.
   
   Recursively collects all refs from nested specs and returns a map
   of spec-name -> spec-def. Detects duplicate spec names and throws error.
   
   Params:
   `spec-def` - Map. Spec definition with optional ::refs key.
   
   Returns:
   Map. Registry mapping spec names (keywords) to spec definitions.
   Empty map if no refs.
   
   Throws:
   ExceptionInfo if duplicate spec names are found."
  [spec-def]
  (let [refs (::refs spec-def)]
    (if (empty? refs)
      {}
      (loop [remaining refs
             registry {}]
        (if (empty? remaining)
          registry
          (let [ref (first remaining)
                ref-name (::spec-name ref)]
            (when-not ref-name
              (anomaly/incorrect! "Referenced spec must have ::spec-name"
                                  {:ref ref}))
            (when (contains? registry ref-name)
              (anomaly/incorrect! "Duplicate spec name in refs"
                                  {:spec-name ref-name
                                   :existing (get registry ref-name)
                                   :duplicate ref}))
            ;; Recursively collect refs from this ref
            (let [nested-registry (build-ref-registry ref)
                  ;; Check for conflicts with nested refs
                  conflicts (set/intersection (set (keys registry))
                                              (set (keys nested-registry)))]
              (when (seq conflicts)
                (anomaly/incorrect! "Duplicate spec names in nested refs"
                                    {:conflicts conflicts}))
              (recur (rest remaining)
                     (merge registry {ref-name ref} nested-registry)))))))))

;; =============================================================================
;; BAML-Style Prompt Generation
;; =============================================================================

(def ^:private TYPE_TO_BAML
  "Maps spec types to BAML type strings."
  {:spec.type/string "string"
   :spec.type/int "int"
   :spec.type/float "float"
   :spec.type/bool "bool"
   :spec.type/date "string"      ; Rendered as string with hint in description
   :spec.type/datetime "string"  ; Rendered as string with hint in description
   :spec.type/keyword "string"}) ; Rendered as string, keywordized on parse

(def ^:private VECTOR_BASE_TYPE_TO_BAML
  "Maps vector base types to BAML type strings."
  {:int "int"
   :string "string"
   :double "float"})  ; double maps to float in BAML

(defn- field->baml-type
  "Converts a field definition to BAML type string.
   
   Handles all field types, optionals, arrays, enums, refs, union refs, and fixed-size vectors.
   
   Params:
   `field-def` - Map. Field definition from `field` function.
   
   Returns:
   String. BAML type representation.
   
   Examples:
   :spec.type/string -> \"string\"
   :spec.type/int with ::required false -> \"int or null\"
   :spec.type/string with :spec.cardinality/many -> \"string[]\"
   :spec.type/ref ::target :Address -> \"Address\"
   :spec.type/ref ::target [:Heading :Paragraph :Image] -> \"Heading | Paragraph | Image\"
   :spec.type/string ::values {\"a\" \"desc\"} -> \"\\\"a\\\" or ...\"
   :spec.type/int-v-4 -> \"int[4]\"
   :spec.type/double-v-3 -> \"float[3]\""
  [field-def]
  (let [field-type (::type field-def)
        target (::target field-def)
        cardinality (::cardinality field-def)
        union (::union field-def)
        values (::values field-def)
        vector-type-info (parse-vector-type field-type)

        ;; Base type conversion
        base-type (cond
                    ;; Enum - use union of literal values
                    values
                    (->> (keys values)
                         sort
                         (map #(str "\\\"" % "\\\""))
                         (str/join " or "))

                    ;; Fixed-size vector type - e.g., int[4]
                    vector-type-info
                    (let [{:keys [base-type size]} vector-type-info
                          baml-base (get VECTOR_BASE_TYPE_TO_BAML base-type "string")]
                      (str baml-base "[" size "]"))

                    ;; Ref - use target spec name (single keyword or vector for union)
                    (= field-type :spec.type/ref)
                    (if (vector? target)
                      ;; Union type - render as "Type1 | Type2 | Type3"
                      (->> target
                           (map name)
                           (str/join " | "))
                      ;; Single target
                      (name target))

                    ;; Use mapping for standard types
                    :else
                    (get TYPE_TO_BAML field-type "string"))

        ;; Add array suffix if cardinality is many (NOT for fixed-size vectors)
        with-array (if (and (= cardinality :spec.cardinality/many) (nil? vector-type-info))
                     (str base-type "[]")
                     base-type)

        ;; Add optional suffix if field is optional
        with-optional (if (and union (contains? union ::nil))
                        (str with-array " or null")
                        with-array)]
    with-optional))

(defn- parse-field-path
  "Parses a Datomic-style namespaced keyword into path and field name.
   
   Params:
   `kw` - Keyword. Field name (e.g., :name, :address/city, :org.division/name).
   
   Returns:
   Vector. [path field-name] where path is vector of keywords, field-name is keyword.
   
   Examples:
   :name -> [[] :name]
   :address/city -> [[:address] :city]
   :org.division/name -> [[:org :division] :name]"
  [kw]
  (if-let [ns (namespace kw)]
    ;; Has namespace - split on dots for nested path
    (let [path-parts (str/split ns #"\.")
          path (mapv keyword path-parts)
          field-name (keyword (name kw))]
      [path field-name])
    ;; No namespace - empty path
    [[] kw]))

(defn- group-fields-by-namespace
  "Groups field definitions by their namespace path.
   
   Parses Datomic-style namespaced keywords and groups fields into nested structure.
   
   Params:
   `fields` - Vector. Field definitions from spec.
   
   Returns:
   Map. Path -> vector of fields at that path.
   
   Examples:
   [:address/city :address/zip :name] -> {[] [:name], [:address] [:city :zip]}
   [:org.division/name] -> {[:org :division] [:name]}"
  [fields]
  (reduce (fn [acc field-def]
            (let [field-name (::name field-def)
                  [path simple-name] (parse-field-path field-name)
                  ;; Create new field def with simple name
                  simple-field (assoc field-def ::name simple-name)]
              (update acc path (fnil conj []) simple-field)))
          {}
          fields))

(defn- find-array-containers-with-children
  "Finds field names that are array containers AND have nested child fields.
   These should be rendered as [{ ... }] not as primitive arrays.
   
   Params:
   `fields` - Vector. All field definitions in the spec.
   
   Returns:
   Set of keywords. Field names that are array containers with children."
  [fields]
  (let [;; Find all fields with cardinality many
        array-containers (->> fields
                              (filter #(= :spec.cardinality/many (::cardinality %)))
                              (map ::name)
                              set)
        ;; For each array container, check if there are nested fields
        has-children? (fn [container-name]
                        (let [container-ns (name container-name)]
                          (some (fn [field]
                                  (let [field-name (::name field)
                                        field-ns (namespace field-name)]
                                    (and field-ns
                                         (or (= field-ns container-ns)
                                             (str/starts-with? field-ns (str container-ns "."))))))
                                fields)))]
    (->> array-containers
         (filter has-children?)
         set)))

(defn- render-enum-values-comment
  "Renders enum values with their descriptions as a comment block.
   
   Params:
   `values` - Map. Enum values with descriptions {\"value\" \"description\"}.
   `indent` - String. Indentation prefix.
   
   Returns:
   String. Comment block with enum values and descriptions, or empty string if no values."
  [values indent]
  (if (empty? values)
    ""
    (let [sorted-values (sort-by first values)
          value-lines (map (fn [[v desc]]
                             (str indent "//   - \"" v "\": " desc))
                           sorted-values)]
      (str (str/join "\n" value-lines) "\n"))))

(defn- render-baml-field
  "Renders a single field in BAML format with description and type.
   
   Params:
   `field-def` - Map. Field definition.
   `indent` - String. Indentation prefix (e.g., \"  \").
   
   Returns:
   String. BAML field representation with description comment."
  [field-def indent]
  (let [;; Strip special chars from field name for JSON compatibility
        field-name (strip-special-chars (name (::name field-def)))
        field-type (field->baml-type field-def)
        description (::description field-def)
        type-kw (::type field-def)
        union (::union field-def)
        values (::values field-def)
        vector-type-info (parse-vector-type type-kw)
        ;; Check if field is optional (has ::nil in union)
        optional? (and union (contains? union ::nil))
        ;; Add required/optional suffix
        req-suffix (if optional? " (optional)" " (required)")
        ;; Add date/datetime/vector hints (keyword is just a string to the LLM)
        desc-with-hint (cond
                         (= type-kw :spec.type/date)
                         (str description " (ISO date YYYY-MM-DD)")

                         (= type-kw :spec.type/datetime)
                         (str description " (ISO datetime)")

                         vector-type-info
                         (let [{:keys [size]} vector-type-info]
                           (str description " (exactly " size " elements)"))

                         :else
                         description)
        ;; Combine description and suffix
        full-description (str desc-with-hint req-suffix)
        ;; Render enum values with descriptions if present
        enum-comment (render-enum-values-comment values indent)]
    (str indent "// " full-description "\n"
         enum-comment
         indent field-name ": " field-type ",")))

(defn- build-path-tree
  "Builds a tree structure from grouped paths.
   
   Groups paths by their first segment, recursively building subtrees.
   
   Params:
   `grouped` - Map of path -> fields.
   
   Returns:
   Map with :fields (fields at this level) and :children (map of child-name -> subtree)."
  [grouped]
  (let [root-fields (get grouped [])
        nested (dissoc grouped [])
        ;; Group nested paths by their first segment
        by-first (group-by (fn [[path _]] (first path)) nested)
        children (into {}
                       (map (fn [[first-seg entries]]
                              (let [;; Remove first segment from paths
                                    sub-grouped (into {}
                                                      (map (fn [[path fields]]
                                                             [(vec (rest path)) fields])
                                                           entries))]
                                [first-seg (build-path-tree sub-grouped)]))
                            by-first))]
    {:fields (or root-fields [])
     :children children}))

(declare render-baml-tree)

(defn- render-baml-tree
  "Renders a path tree as BAML nested objects.
   
   Params:
   `tree` - Map with :fields and :children.
   `indent` - String. Current indentation.
   `array-containers` - Set. Field names (keywords) that should render as arrays.
   
   Returns:
   Vector of strings, each a line of BAML output."
  [tree indent array-containers]
  (let [;; Filter out fields that are array containers with children
        ;; (they will be rendered via the children map instead)
        filtered-fields (remove #(contains? array-containers (::name %)) (:fields tree))
        field-lines (map #(render-baml-field % indent) filtered-fields)
        child-lines (mapcat (fn [[child-name child-tree]]
                              (let [array? (contains? array-containers child-name)
                                    ;; For arrays, we need extra indentation for content inside { }
                                    inner-indent (if array?
                                                   (str indent "    ")  ; 4 spaces for array content
                                                   (str indent "  "))   ; 2 spaces for object content
                                    inner-lines (render-baml-tree child-tree inner-indent array-containers)
                                    inner-content (str/join "\n" inner-lines)]
                                (if array?
                                  ;; Render as array of objects: name: [{ ... }],
                                  [(str indent (name child-name) ": [")
                                   (str indent "  {")
                                   inner-content
                                   (str indent "  }")
                                   (str indent "],")]
                                  ;; Render as single object: name: { ... },
                                  [(str indent (name child-name) ": {")
                                   inner-content
                                   (str indent "},")])))
                            (sort (:children tree)))]
    (concat field-lines child-lines)))

(defn- spec->baml-class
  "Renders a single spec as a BAML class block.
   
   Params:
   `spec-def` - Map. Spec definition.
   `class-name` - String or nil. Optional class name for hoisted specs.
   
   Returns:
   String. BAML class representation.
   
   Examples:
   Simple spec -> \"{ field: type, }\"
   Named spec -> \"SpecName { field: type, }\"
   With nested -> \"{ address: { city: string, }, }\""
  [spec-def class-name]
  (let [fields (::fields spec-def)
        grouped (group-fields-by-namespace fields)
        name-prefix (or class-name (some-> spec-def ::spec-name name))
        array-containers (find-array-containers-with-children fields)

        ;; Build tree from grouped paths and render
        tree (build-path-tree grouped)
        body-lines (render-baml-tree tree "  " array-containers)
        body (str/join "\n" body-lines)]

    (if name-prefix
      ;; Named class
      (str name-prefix " {\n" body "\n}")
      ;; Anonymous class
      (str "{\n" body "\n}"))))

(defn- count-ref-usages
  "Counts how many times each ref spec is used in the main spec's fields.
   
   Handles both single keyword targets and vector targets (union types).
   For union types, each type in the union is counted separately.
   
   Params:
   `spec-def` - Map. Main spec definition.
   `ref-registry` - Map. Registry of spec-name -> spec-def.
   
    Returns:
    Map. Spec-name -> usage count."
  [spec-def _ref-registry]
  (let [fields (::fields spec-def)]
    (reduce (fn [counts field-def]
              (if (= (::type field-def) :spec.type/ref)
                (let [target (::target field-def)
                      ;; Normalize target to a vector for uniform handling
                      targets (if (vector? target) target [target])]
                  ;; Increment count for each target in the union
                  (reduce (fn [c t] (update c t (fnil inc 0))) counts targets))
                counts))
            {}
            fields)))

(defn- partition-refs-by-usage
  "Partitions refs into hoisted (used 2+ times) and inlined (used once).
   
   Params:
   `spec-def` - Map. Main spec definition.
   `ref-registry` - Map. Registry of spec-name -> spec-def.
   
   Returns:
   Map with :hoisted and :inlined keys, each containing vector of [name spec-def] pairs."
  [spec-def ref-registry]
  (let [usage-counts (count-ref-usages spec-def ref-registry)
        all-refs (map (fn [[name spec]] [name spec (get usage-counts name 0)])
                      ref-registry)
        hoisted (filter (fn [[_ _ count]] (>= (long count) 2)) all-refs)
        inlined (filter (fn [[_ _ count]] (= count 1)) all-refs)
        unused (filter (fn [[_ _ count]] (= count 0)) all-refs)]
    ;; Warn about unused refs
    (doseq [[name _ _] unused]
      (trove/log! {:level :warn :msg (str "Unused ref in spec: " name)}))
    {:hoisted (mapv (fn [[name spec _]] [name spec]) hoisted)
     :inlined (mapv (fn [[name spec _]] [name spec]) inlined)}))

;; =============================================================================
;; Spec Fields Text Representation - Serialization
;; =============================================================================

(defn- spec->str
  "Converts a spec to BAML-style format for LLM prompt.
   
   Renders hoisted specs (used 2+ times) at top, main spec at bottom.
   Uses pseudo-TypeScript object notation.
   
   Params:
   `the-spec` - Map. Spec definition created with `spec`.
   
   Returns:
   String. BAML-style representation.
   
   Examples:
   Simple spec -> \"{ field: type, }\"
   With refs -> \"Address { city: string, }\\n\\n{ home: Address, }\""
  [the-spec]
  (let [ref-registry (build-ref-registry the-spec)
        {:keys [hoisted inlined]} (partition-refs-by-usage the-spec ref-registry)

        ;; Render hoisted specs (used 2+ times)
        hoisted-lines (map (fn [[_name spec-def]]
                             (spec->baml-class spec-def nil))
                           hoisted)

         ;; Render inlined specs (used exactly once)
        inlined-lines (map (fn [[_name spec-def]]
                             (spec->baml-class spec-def nil))
                           inlined)

        ;; Render main spec
        main-class (spec->baml-class the-spec nil)

        ;; Combine: hoisted specs, then inlined specs, then main spec
        ;; All separated by blank lines
        all-parts (concat hoisted-lines inlined-lines [main-class])]
    (str/join "\n\n" all-parts)))

;; =============================================================================
;; Spec Fields Response Format - Parsing
;; =============================================================================

(defn str->data
  "Parses JSON response from LLM into Clojure data structure.
   
   Uses jsonish parser to handle malformed JSON (unquoted keys/values, trailing commas, etc.).
   
   Params:
   `text` - String. JSON response from LLM (may be malformed).
   
   Returns:
   Map. Parsed Clojure data with keywords as keys, nested maps/vectors as values.
   
   Examples:
   (str->data \"{\\\"name\\\": \\\"John\\\", \\\"age\\\": 30}\")
   => {:name \"John\", :age 30}
   
   (str->data \"{name: John, age: 30}\")  ; Unquoted - still works
   => {:name \"John\", :age 30}
   
   Throws:
   RuntimeException if JSON cannot be parsed."
  [text]
  (let [start-time (System/nanoTime)
        parse-result (jsonish/parse-json text)
        result (:value parse-result)
        warnings (:warnings parse-result)
        duration-ms (/ (- (System/nanoTime) start-time) 1e6)]
    (when (seq warnings)
      (trove/log! {:level :warn :data {:warnings warnings}
                   :msg "JSON parsing warnings"}))
    (trove/log! {:level :debug :data {:duration-ms duration-ms :warnings-count (count warnings)}
                 :msg "Parsed JSON response"})
    result))

(defn- build-keyword-fields
  "Builds a set of field paths that are :spec.type/keyword type.
   These fields need to be converted from strings to keywords after parsing.
   
   Includes fields from both the main spec AND all referenced specs (::refs).
   This ensures keyword fields in union types are also converted.
   
   Params:
   `spec-def` - Map. Spec definition with ::fields key and optional ::refs.
   
   Returns:
   Set. Field name keywords that should be keywordized."
  [spec-def]
  (let [;; Get keyword fields from main spec
        main-fields (::fields spec-def)
        main-keyword-fields (->> main-fields
                                 (filter #(= :spec.type/keyword (::type %)))
                                 (map ::name)
                                 (map #(keyword (name %))))
        ;; Get keyword fields from all referenced specs (for union types)
        refs (::refs spec-def)
        ref-keyword-fields (when refs
                             (->> refs
                                  (mapcat ::fields)
                                  (filter #(= :spec.type/keyword (::type %)))
                                  (map ::name)
                                  (map #(keyword (name %)))))]
    (into #{} (concat main-keyword-fields ref-keyword-fields))))

(defn- keywordize-fields
  "Recursively converts string values to keywords for specified field names.
   
   Params:
   `data` - Parsed Clojure data (map, vector, or primitive).
   `keyword-fields` - Set of field name keywords to convert.
   
   Returns:
   Data with specified string fields converted to keywords."
  [data keyword-fields]
  (cond
    (map? data)
    (into {}
          (map (fn [[k v]]
                 (let [simple-key (keyword (name k))
                       should-keywordize? (contains? keyword-fields simple-key)
                       new-v (cond
                               ;; Keywordize string value
                               (and should-keywordize? (string? v))
                               (keyword v)
                               ;; Keywordize vector of strings
                               (and should-keywordize? (vector? v))
                               (mapv #(if (string? %) (keyword %) %) v)
                               ;; Recurse into nested structures
                               :else
                               (keywordize-fields v keyword-fields))]
                   [k new-v]))
               data))

    (vector? data)
    (mapv #(keywordize-fields % keyword-fields) data)

    :else
    data))

(defn- collect-key-namespaces
  "Collects all ::key-ns values from a spec and its refs.
   
   Returns a map of spec-name -> key-ns string for specs that have ::key-ns defined.
   Also includes nil -> key-ns for the main spec if it has ::key-ns."
  [spec-def]
  (let [main-key-ns (::key-ns spec-def)
        refs (::refs spec-def)
        ref-key-ns (when refs
                     (->> refs
                          (filter ::key-ns)
                          (map (fn [ref] [(::spec-name ref) (::key-ns ref)]))
                          (into {})))]
    (cond-> ref-key-ns
      main-key-ns (assoc nil main-key-ns))))

(defn- namespace-keys
  "Recursively adds namespace to keys based on spec's ::key-ns setting.
   
   For union types, uses the :type field to determine which ref's ::key-ns to use.
   
   Params:
   `data` - Parsed Clojure data (map, vector, or primitive).
   `key-ns-map` - Map of spec-name -> key-ns string (nil key for main spec).
   
   Returns:
   Data with keys namespaced according to spec configuration."
  [data key-ns-map]
  (cond
    (map? data)
    (let [;; Check if this is a union type node by looking for :type field
          type-val (get data :type)
          ;; Find the key-ns to use: either from type-specific ref or main spec (nil key)
          key-ns (or (get key-ns-map type-val)
                     (get key-ns-map nil))]
      (if key-ns
        ;; Apply namespace to all keys
        (into {}
              (map (fn [[k v]]
                     (let [new-key (keyword key-ns (name k))
                           new-v (namespace-keys v key-ns-map)]
                       [new-key new-v]))
                   data))
        ;; No key-ns, just recurse
        (into {}
              (map (fn [[k v]]
                     [k (namespace-keys v key-ns-map)])
                   data))))

    (vector? data)
    (mapv #(namespace-keys % key-ns-map) data)

    :else
    data))

(defn- maybe-wrap-bare-array
  "Wraps a bare array result if the spec expects an object with a single array field.
   
   LLMs often return bare arrays `[...]` when asked for a list, even when the spec
   defines an object like `{nodes: [...]}`. This function detects this case and
   wraps the array automatically.
   
   Params:
   `result` - The parsed JSON result (may be vector or map).
   `spec-def` - The spec definition.
   
   Returns:
   Either the original result (if already a map or spec has multiple fields),
   or the array wrapped in the expected field name."
  [result spec-def]
  (if-not (vector? result)
    ;; Not a bare array, return as-is
    result
    ;; Got a bare array - check if spec has single array field
    (let [fields (::fields spec-def)]
      (if (and (= 1 (count fields))
               (= :spec.cardinality/many (::cardinality (first fields))))
        ;; Spec has exactly one field with cardinality :many - wrap the array
        (let [field-name (::name (first fields))]
          (trove/log! {:level :debug :data {:field field-name}
                       :msg "Auto-wrapping bare array in spec field"})
          {field-name result})
        ;; Multiple fields or not cardinality :many - return as-is
        result))))

(defn str->data-with-spec
  "Parses JSON response from LLM into Clojure data structure with spec-aware processing.
   
   Uses jsonish parser to handle malformed JSON, then:
   1. Remaps keys to match original spec field names (preserves ?!*+ chars)
   2. Converts :spec.type/keyword fields from strings to Clojure keywords
   3. Applies ::key-ns namespace to keys if configured in spec
   
   Params:
   `text` - String. JSON response from LLM (may be malformed).
   `spec-def` - Map. Spec definition used to generate the prompt.
   
   Returns:
   Map. Parsed Clojure data with keys matching original spec field names,
   keyword-typed fields converted to Clojure keywords, and keys namespaced
   if ::key-ns is configured.
   
   Examples:
   (def my-spec (spec (field ::name :valid? ::type :spec.type/bool ...)))
   (str->data-with-spec \"{\\\"valid\\\": true}\" my-spec)
   => {:valid? true}  ; Note: key is :valid? not :valid
   
   (def kw-spec (spec (field ::name :status ::type :spec.type/keyword ...)))
   (str->data-with-spec \"{\\\"status\\\": \\\"active\\\"}\" kw-spec)
   => {:status :active}  ; String converted to keyword
   
   (def ns-spec (spec :node {::key-ns \"page.node\"} (field ::name :type ...)))
   (str->data-with-spec \"{\\\"type\\\": \\\"heading\\\"}\" ns-spec)
   => {:page.node/type \"heading\"}  ; Key namespaced
   
   Throws:
   RuntimeException if JSON cannot be parsed."
  [text spec-def]
  (let [start-time (System/nanoTime)
        parse-result (jsonish/parse-json text)
        raw-result (:value parse-result)
        warnings (:warnings parse-result)
        ;; Auto-wrap bare arrays if spec expects object with single array field
        wrapped-result (maybe-wrap-bare-array raw-result spec-def)
        ;; Build key mapping from spec and remap keys
        key-mapping (build-key-mapping spec-def)
        remapped (if (empty? key-mapping)
                   wrapped-result
                   (remap-keys wrapped-result key-mapping))
        ;; Build keyword fields set and convert strings to keywords
        keyword-fields (build-keyword-fields spec-def)
        keywordized (if (empty? keyword-fields)
                      remapped
                      (keywordize-fields remapped keyword-fields))
        ;; Apply key namespace if configured
        key-ns-map (collect-key-namespaces spec-def)
        result (if (empty? key-ns-map)
                 keywordized
                 (namespace-keys keywordized key-ns-map))
        duration-ms (/ (- (System/nanoTime) start-time) 1e6)]
    (when (seq warnings)
      (trove/log! {:level :warn :data {:warnings warnings}
                   :msg "JSON parsing warnings"}))
    (trove/log! {:level :debug :data {:duration-ms duration-ms
                                   :warnings-count (count warnings)
                                   :key-remaps (count key-mapping)
                                   :keyword-fields (count keyword-fields)
                                   :key-ns-applied (seq key-ns-map)}
                 :msg "Parsed JSON response with spec-aware processing"})
    result))

;; =============================================================================
;; JSON Serialization
;; =============================================================================

(defn- prepare-for-json
  "Prepares Clojure data for JSON serialization.
   Converts dates/datetimes to ISO strings, keywords to strings."
  [data]
  (cond
    (instance? LocalDate data)
    (.toString ^LocalDate data)

    (or (instance? OffsetDateTime data) (instance? ZonedDateTime data))
    (str data)

    (map? data)
    (into {} (map (fn [[k v]] [(name k) (prepare-for-json v)]) data))

    (vector? data)
    (mapv prepare-for-json data)

    :else
    data))

(defn data->str
  "Serializes Clojure data to JSON string.
   
   Converts dates/datetimes to ISO 8601 strings.
   
   Params:
   `data` - Map. Clojure data structure with keyword keys.
   
   Returns:
   String. JSON representation of the data.
   
   Examples:
   (data->str {:name \"John\" :age 42})
   => \"{\\\"name\\\":\\\"John\\\",\\\"age\\\":42}\"
   
   (data->str {:date (LocalDate/of 2024 1 15)})
   => \"{\\\"date\\\":\\\"2024-01-15\\\"}\""
  [data]
  (-> data
      prepare-for-json
      json/write-json-str))

;; =============================================================================
;; Spec Fields Response Format - Validation
;; =============================================================================

(defn- find-array-container-paths
  "Finds paths that are array containers (cardinality many) from the spec fields.
   Returns a set of path strings that represent arrays."
  [fields]
  (->> fields
       (filter #(= :spec.cardinality/many (::cardinality %)))
       (map #(keyword->path (::name %)))
       set))

(defn- has-nested-fields?
  "Checks if an array container path has nested field definitions.
   E.g., 'books' has nested fields if there are fields like 'books.title'."
  [array-path fields]
  (let [prefix (str array-path ".")]
    (some #(str/starts-with? (keyword->path (::name %)) prefix) fields)))

(defn- get-value-at-path
  "Gets value from data at a given path string (e.g., 'address.city').
   If path goes through an array container, returns values from all array items.
   
   Supports both stripped paths (for JSON) and original field keywords (with special chars).
   When original-keyword is provided, uses it for the final key lookup.
   
   Params:
   `data` - The data map to traverse.
   `path-str` - Dot-separated path string (with special chars stripped).
   `array-containers` - Set of paths that are array containers.
   `original-keyword` - Keyword, optional. Original field keyword with special chars.
   
   Returns:
   The value at the path, or a vector of values if path goes through an array."
  ([data path-str array-containers]
   (get-value-at-path data path-str array-containers nil))
  ([data path-str array-containers original-keyword]
   (let [segments (str/split path-str #"\.")
         ;; Use original keyword for final segment if provided
         final-key (if original-keyword
                     (keyword (name original-keyword))
                     (keyword (last segments)))
         ;; Check if any prefix of this path is an array container
         prefixes (reductions (fn [acc seg] (if (empty? acc) seg (str acc "." seg))) "" segments)
         prefixes (rest prefixes) ;; skip empty first element
         array-prefix (first (filter array-containers prefixes))]
     (if (and array-prefix (not= array-prefix path-str))
       ;; Path goes through an array - extract values from each array element
       (let [array-segments (str/split array-prefix #"\.")
             rest-segments (drop (count array-segments) segments)
             array-value (reduce (fn [current seg]
                                   (when (some? current)
                                     (get current (keyword seg))))
                                 data
                                 array-segments)]
         (when (vector? array-value)
           (mapv (fn [item]
                   ;; Use final-key for last segment
                   (let [intermediate-segments (butlast rest-segments)]
                     (-> (reduce (fn [current seg]
                                   (when (some? current)
                                     (get current (keyword seg))))
                                 item
                                 intermediate-segments)
                         (get final-key))))
                 array-value)))
       ;; Regular path traversal - use final-key for last segment
       (let [intermediate-segments (butlast segments)]
         (-> (reduce (fn [current seg]
                       (when (some? current)
                         (get current (keyword seg))))
                     data
                     intermediate-segments)
             (get final-key)))))))

(defn- check-scalar-type
  "Checks if a scalar value matches the expected type."
  [value expected-type]
  (case expected-type
    :spec.type/string (string? value)
    :spec.type/int (int? value)
    :spec.type/float (number? value)
    :spec.type/bool (boolean? value)
    :spec.type/keyword (keyword? value)  ; After post-processing, should be keyword
    :spec.type/date (instance? LocalDate value)
    :spec.type/datetime (or (instance? OffsetDateTime value)
                            (instance? ZonedDateTime value))
    ;; Not a standard type - might be a vector type, handle separately
    false))

(defn- check-vector-element-type
  "Checks if a value matches the base type for a fixed-size vector."
  [value base-type]
  (case base-type
    :int (int? value)
    :string (string? value)
    :double (number? value)
    false))

(defn- check-fixed-size-vector
  "Checks if a value is a valid fixed-size vector with correct size and element types.
   
   Params:
   `value` - The value to check.
   `vector-type-info` - Map with :base-type and :size.
   
   Returns:
   Boolean. True if valid fixed-size vector."
  [value vector-type-info]
  (when vector-type-info
    (let [{:keys [base-type size]} vector-type-info]
      (and (vector? value)
           (= (count value) size)
           (every? #(check-vector-element-type % base-type) value)))))

(defn- check-type
  "Checks if a value matches the expected type. Returns true if valid, false otherwise.
   For cardinality many with nested fields, just checks it's a vector.
   For cardinality many without nested fields, checks each element's type.
   For fixed-size vector types, checks size and element types."
  [value expected-type cardinality has-nested?]
  (cond
    (nil? value) true ;; nil handling is done separately via ::union

    ;; Fixed-size vector type (e.g., :spec.type/int-v-4)
    (parse-vector-type expected-type)
    (check-fixed-size-vector value (parse-vector-type expected-type))

    (= cardinality :spec.cardinality/many)
    (if has-nested?
      (vector? value) ;; Array of objects - just check it's a vector
      (and (vector? value) ;; Simple array - check each element's type
           (every? #(check-scalar-type % expected-type) value)))
    :else
    (check-scalar-type value expected-type)))

(defn- check-type-in-array
  "Checks if all values in a vector match the expected type.
   Handles both scalar types and fixed-size vector types."
  [values expected-type]
  (let [vector-type-info (parse-vector-type expected-type)]
    (if vector-type-info
      ;; Fixed-size vector type - check each value is a valid vector
      (every? #(check-fixed-size-vector % vector-type-info) values)
      ;; Scalar type
      (every? #(check-scalar-type % expected-type) values))))

(defn- check-enum
  "Checks if a value is one of the allowed enum values.
   For cardinality many, checks all items in the vector."
  [value allowed-values cardinality]
  (cond
    (nil? value) true
    (nil? allowed-values) true
    (= cardinality :spec.cardinality/many)
    (and (vector? value)
         (every? #(contains? allowed-values %) value))
    :else
    (contains? allowed-values value)))

(defn- nested-in-array?
  "Checks if a path is nested inside an array container.
   E.g., 'books.title' is nested in 'books' if 'books' is an array container."
  [path-str array-containers]
  (let [segments (str/split path-str #"\.")
        prefixes (reductions (fn [acc seg] (if (empty? acc) seg (str acc "." seg))) "" segments)
        prefixes (butlast (rest prefixes))] ;; skip empty first and the full path itself
    (some array-containers prefixes)))

(defn validate-data
  "Validates parsed data against a spec.
   Returns {:valid? true} if valid, or {:valid? false :errors [...]} with error details.
   
   Handles array of objects pattern where a field with cardinality many contains
   objects with nested fields (e.g., :books with :books.title, :books.year).
   
   Checks:
   - Required fields are present (unless ::union contains ::nil)
   - Values match expected types
   - Enum values are valid (if ::values is specified)
   
   Params:
   `the-spec` - Spec definition (created with spec/spec and spec/field)
   `data` - Parsed Clojure data to validate
   
   Returns:
   Map with :valid? boolean and optional :errors vector of error maps."
  [the-spec data]
  (let [start-time (System/nanoTime)
        fields (::fields the-spec)
        array-containers (find-array-container-paths fields)
        errors (atom [])]
    (doseq [field-def fields]
      (let [field-name (::name field-def)
            path-str (keyword->path field-name)
            field-type (::type field-def)
            cardinality (::cardinality field-def)
            optional? (contains? (::union field-def) ::nil)
            allowed-values (::values field-def)
            in-array? (nested-in-array? path-str array-containers)
            has-nested? (and (= cardinality :spec.cardinality/many)
                             (has-nested-fields? path-str fields))
            ;; Pass original field-name keyword to handle special chars like ?!
            value (get-value-at-path data path-str array-containers field-name)
            missing? (if in-array?
                       (or (nil? value) (every? nil? value))
                       (nil? value))]
        ;; Check required
        (when (and missing? (not optional?))
          (swap! errors conj {:error :missing-required-field
                              :field field-name
                              :path path-str}))
        ;; Check type (only if present)
        (when (not missing?)
          (if in-array?
            ;; For nested fields in arrays, check each extracted value
            (when-not (check-type-in-array value field-type)
              (swap! errors conj {:error :type-mismatch
                                  :field field-name
                                  :path path-str
                                  :expected-type field-type
                                  :actual-value value
                                  :actual-type (type value)}))
            ;; Regular type check
            (when-not (check-type value field-type cardinality has-nested?)
              (swap! errors conj {:error :type-mismatch
                                  :field field-name
                                  :path path-str
                                  :expected-type field-type
                                  :actual-value value
                                  :actual-type (type value)}))))
        ;; Check enum (only if present and has values constraint)
        (when (and (not missing?)
                   allowed-values
                   (not (check-enum value allowed-values cardinality)))
          (swap! errors conj {:error :invalid-enum-value
                              :field field-name
                              :path path-str
                              :value value
                              :allowed-values (keys allowed-values)}))))
    (let [duration-ms (/ (- (System/nanoTime) start-time) 1e6)
          result (if (empty? @errors)
                   {:valid? true}
                   {:valid? false :errors @errors})]
      (if (:valid? result)
        (trove/log! {:level :debug :data {:fields-count (count fields) :duration-ms duration-ms}
                     :msg "Spec validation passed"})
        (trove/log! {:level :warn :data {:fields-count (count fields) :error-count (count @errors) :duration-ms duration-ms}
                     :msg "Spec validation failed"}))
      result)))

;; =============================================================================
;; Spec Fields Text Representation - Prompt Generation
;; =============================================================================

(defn spec->prompt
  "Converts a spec to a full prompt for LLM with BAML-style schema.
   
   Uses simple, direct format without XML tags.
   
   Params:
   `the-spec` - Map. Spec definition created with `spec`.
   
   Returns:
   String. Prompt with BAML-style schema.
   
   Examples:
   \"Answer in JSON using this schema:\\n{ field: type, }\""
  [the-spec]
  (str "Answer in JSON using this schema:\n"
       (spec->str the-spec)))
