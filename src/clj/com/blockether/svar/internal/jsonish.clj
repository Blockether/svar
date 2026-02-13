(ns com.blockether.svar.internal.jsonish
  "Wrapper for the JsonishParser Java class.
   
   Provides SAP (Schemaless Adaptive Parsing) for malformed JSON from LLMs.
   Handles unquoted keys/values, trailing commas, markdown code blocks, etc."
  (:require
   [clojure.string :as str])
  (:import
   [com.blockether.svar JsonishParser JsonishParser$ParseCandidate]
   [java.util List Map]))

(defn- normalize-key
  "Normalizes a JSON key to idiomatic Clojure keyword.
   
   Converts underscores to dashes (snake_case -> kebab-case).
   
   Params:
   `k` - String. JSON key to normalize.
   
   Returns:
   Keyword. Normalized Clojure keyword."
  [k]
  (-> k
      (str/replace "_" "-")
      keyword))

(defn- java->clojure
  "Converts Java Map/List to Clojure data structures recursively.
   
   Normalizes map keys from snake_case to kebab-case.
   
   Params:
   `obj` - Object. Java object to convert (Map, List, or primitive).
   
   Returns:
   Clojure data structure (map with keyword keys, vector, or primitive)."
  [obj]
  (cond
    (instance? Map obj)
    (into {} (map (fn [[k v]] [(normalize-key k) (java->clojure v)]) obj))

    (instance? List obj)
    (mapv java->clojure obj)

    :else
    obj))

(defn parse-json
  "Parses JSON-ish string using full SAP (Schemaless Adaptive Parsing) cascade.
   
   Handles malformed JSON from LLMs using a multi-stage parsing strategy:
   1. Strict JSON - Valid JSON without any fixes (score: 100)
   2. Markdown extraction - JSON from code blocks (score: 90)
   3. Multi-object extraction - All balanced {}/[] in text (score: 70-80)
   4. Fixing parser - Malformed JSON with repairs (score: 10-50)
   5. String fallback - Raw input as string (score: 0)
   
   Returns the best candidate (highest score) with applied fixes as warnings.
   
   Params:
   `input` - String. JSON-ish input to parse. Must not be nil or empty.
   
   Returns:
   Map with:
   - :value - Parsed Clojure data structure
   - :warnings - Vector of fix descriptions applied during parsing
   
   Examples:
   (parse-json \"{\\\"a\\\": 1}\")
   => {:value {:a 1}, :warnings []}
   
   (parse-json \"{a: 1, b: 2,}\")
   => {:value {:a 1, :b 2}, :warnings [\"Unquoted key: a\" \"Trailing comma\"]}
   
   (parse-json \"```json\\n{\\\"x\\\":42}\\n```\")
   => {:value {:x 42}, :warnings [\"ExtractedFromMarkdown:json\"]}
   
   Throws:
   IllegalArgumentException if input is nil or empty."
  [input]
  (when (or (nil? input) (empty? input))
    (throw (IllegalArgumentException. "Input cannot be nil or empty")))

  (let [parser (JsonishParser.)
        candidates (.parseWithCandidates parser input)
        ^JsonishParser$ParseCandidate best (first candidates)]
    {:value (java->clojure (.value best))
     :warnings (vec (.fixes best))}))
