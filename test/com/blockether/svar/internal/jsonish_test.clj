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
    (it "extracts JSON from ```json fence"
      (let [result (sut/parse-json "Here's the data:\n```json\n{\"x\":1}\n```")]
        (expect (= {:x 1} (:value result)))
        (expect (some #(re-find #"ExtractedFromMarkdown" %) (:warnings result)))))

    (it "extracts JSON from untagged fence"
      (let [result (sut/parse-json "```\n{\"a\": 1}\n```")]
        (expect (= {:a 1} (:value result)))))

    (it "extracts EDN from ```clojure fence"
      (let [result (sut/parse-json "```clojure\n{:code [{:expr \"(+ 1 2)\" :time-ms 50}]}\n```")]
        (expect (= {:code [{:expr "(+ 1 2)" :time-ms 50}]} (:value result)))))

    (it "extracts EDN from ```edn fence"
      (let [result (sut/parse-json "```edn\n{:code [{:expr \"foo\" :time-ms 10}]}\n```")]
        (expect (= {:code [{:expr "foo" :time-ms 10}]} (:value result)))))

    (it "extracts from ```clojure fence with leading prose"
      (let [result (sut/parse-json "Let me do stuff.\n```clojure\n{:code [{:expr \"x\" :time-ms 10}]}\n```")]
        (expect (= {:code [{:expr "x" :time-ms 10}]} (:value result))))))

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

;; =============================================================================
;; EDN / Clojure-style lenient parsing tests
;;
;; GLM-5.1 and other reasoning models frequently emit Clojure/EDN syntax
;; instead of strict JSON — keyword keys (:code, :expr), keyword values
;; (:quick, :deep), trailing whitespace/newlines before the opening brace.
;; These tests pin the exact behaviours we rely on in production.
;; =============================================================================

(defdescribe edn-lenient-test
  "EDN / Clojure-style lenient parsing — real model output shapes"

  (describe "EDN keyword keys"
    (it "parses top-level EDN keyword key :code"
      (let [result (sut/parse-json "{:code [{:expr \"(def foo 42)\" :time-ms 100}]}")]
        (expect (= {:code [{:expr "(def foo 42)" :time-ms 100}]}
                  (:value result)))))

    (it "parses multiple EDN keyword keys at top level"
      (let [result (sut/parse-json "{:code [{:expr \"x\" :time-ms 10}] :answer \"done\"}")]
        (expect (= {:code [{:expr "x" :time-ms 10}] :answer "done"}
                  (:value result)))))

    (it "parses nested EDN keyword keys in array elements"
      (let [result (sut/parse-json "{:code [{:expr \"(+ 1 2)\" :time-ms 50} {:expr \"(def m {})\" :time-ms 100}]}")]
        (expect (= {:code [{:expr "(+ 1 2)" :time-ms 50}
                           {:expr "(def m {})" :time-ms 100}]}
                  (:value result)))))

    (it "parses leading-newline EDN — real model output shape"
      (let [result (sut/parse-json "\n{:code [{:expr \"(list-dir \\\".\\\" {:glob \\\"**/*.css\\\"})\" :time-ms 2000}]}")]
        (expect (= {:code [{:expr "(list-dir \".\" {:glob \"**/*.css\"})" :time-ms 2000}]}
                  (:value result)))))

    (it "produces a warning noting the EDN keyword key strip"
      (let [result (sut/parse-json "{:code []}")]
        (expect (some #(re-find #"EDN keyword key" %) (:warnings result)))))

    (it "does not strip colons that are the key-value separator"
      ;; `:code` key + value `[]` — the separator `:` must not be eaten
      (let [result (sut/parse-json "{:code []}")]
        (expect (= {:code []} (:value result))))))

  (describe "EDN keyword values"
    (it "parses :quick as the string \"quick\""
      (let [result (sut/parse-json "{:next {:reasoning :quick}}")]
        (expect (= {:next {:reasoning "quick"}} (:value result)))))

    (it "parses :deep as the string \"deep\""
      (let [result (sut/parse-json "{:next {:reasoning :deep}}")]
        (expect (= {:next {:reasoning "deep"}} (:value result)))))

    (it "parses :balanced as the string \"balanced\""
      (let [result (sut/parse-json "{:next {:reasoning :balanced}}")]
        (expect (= {:next {:reasoning "balanced"}} (:value result)))))

    (it "parses mixed EDN keys + EDN keyword values"
      (let [result (sut/parse-json "{:code [{:expr \"x\" :time-ms 10}] :next {:reasoning :deep}}")]
        (expect (= {:code [{:expr "x" :time-ms 10}] :next {:reasoning "deep"}}
                  (:value result)))))

    (it "produces a warning noting the EDN keyword value strip"
      (let [result (sut/parse-json "{\"reasoning\": :quick}")]
        (expect (some #(re-find #"EDN keyword value" %) (:warnings result)))))

    (it "parses keyword value as first value in a map"
      (let [result (sut/parse-json "{:status :ok}")]
        (expect (= {:status "ok"} (:value result)))))

    (it "parses keyword value after a JSON-quoted key"
      (let [result (sut/parse-json "{\"mode\": :streaming}")]
        (expect (= {:mode "streaming"} (:value result)))))

    (it "parses keyword values inside an array"
      (let [result (sut/parse-json "{:tags [:alpha :beta :gamma]}")]
        (expect (= {:tags ["alpha" "beta" "gamma"]} (:value result)))))

    (it "parses keyword value in a top-level bare array"
      (let [result (sut/parse-json "[:foo :bar :baz]")]
        (expect (= ["foo" "bar" "baz"] (:value result)))))

    (it "parses keyword values with hyphens and dots"
      (let [result (sut/parse-json "{:content-type :application/json :encoding :utf-8}")]
        (expect (= {:content-type "application/json" :encoding "utf-8"}
                  (:value result)))))

    (it "parses keyword values with namespaces (slash-separated)"
      (let [result (sut/parse-json "{:type :spec.type/string :required :spec/required}")]
        (expect (= {:type "spec.type/string" :required "spec/required"}
                  (:value result)))))

    (it "parses keyword value followed immediately by closing brace"
      (let [result (sut/parse-json "{:a {:b :done}}")]
        (expect (= {:a {:b "done"}} (:value result)))))

    (it "parses keyword value followed by another EDN key (implicit comma)"
      (let [result (sut/parse-json "{:status :ok :code 200 :msg \"success\"}")]
        (expect (= {:status "ok" :code 200 :msg "success"}
                  (:value result)))))

    (it "parses keyword value at end of array element before next element"
      (let [result (sut/parse-json "[{:mode :fast} {:mode :slow}]")]
        (expect (= [{:mode "fast"} {:mode "slow"}] (:value result)))))

    (it "parses keyword value followed by closing bracket in array"
      (let [result (sut/parse-json "{:items [{:state :pending} {:state :active}]}")]
        (expect (= {:items [{:state "pending"} {:state "active"}]}
                  (:value result)))))

    (it "parses nil-like keyword :nil as the string \"nil\" not actual null"
      (let [result (sut/parse-json "{:x :nil}")]
        (expect (= {:x "nil"} (:value result)))))

    (it "parses keyword value with numbers in name"
      (let [result (sut/parse-json "{:protocol :http2 :fallback :tls1.3}")]
        (expect (= {:protocol "http2" :fallback "tls1.3"}
                  (:value result)))))

    (it "parses deeply nested keyword values at multiple levels"
      (let [result (sut/parse-json "{:a {:state :running :b {:state :idle :c {:state :stopped}}}}")]
        (expect (= "running" (get-in (:value result) [:a :state])))
        (expect (= "idle" (get-in (:value result) [:a :b :state])))
        (expect (= "stopped" (get-in (:value result) [:a :b :c :state])))))

    (it "parses keyword value mixed with boolean and number siblings"
      (let [result (sut/parse-json "{:enabled true :level 5 :mode :turbo :label \"fast\"}")]
        (expect (= true (:enabled (:value result))))
        (expect (= 5 (:level (:value result))))
        (expect (= "turbo" (:mode (:value result))))
        (expect (= "fast" (:label (:value result)))))))

  (describe "incomplete / truncated inputs (lenient end)"
    (it "parses map with missing outer closing brace"
      (let [result (sut/parse-json "{:code [{:expr \"foo\" :time-ms 10}]")]
        ;; Should recover — either via completion path or fixing parser
        (expect (map? (:value result)))
        (expect (contains? (:value result) :code))))

    (it "parses deeply nested EDN with missing closing braces"
      (let [result (sut/parse-json "{:next {:reasoning :deep")]
        (expect (map? (:value result)))))

    (it "parses array with missing closing bracket"
      (let [result (sut/parse-json "[{:expr \"x\" :time-ms 10}")]
        (expect (sequential? (:value result)))))

    (it "parses empty map"
      (expect (= {:value {} :warnings []}
                (sut/parse-json "{}"))))

    (it "parses empty array"
      (expect (= {:value [] :warnings []}
                (sut/parse-json "[]")))))

  (describe "mixed JSON + EDN (real production variants)"
    (it "handles JSON keys mixed with EDN keyword values"
      (let [result (sut/parse-json "{\"reasoning\": :quick, \"code\": []}")]
        (expect (= "quick" (get (:value result) :reasoning)))))

    (it "handles JSON keys, JSON values, no EDN"
      (let [result (sut/parse-json "{\"code\": [{\"expr\": \"(+ 1 2)\", \"time-ms\": 50}]}")]
        (expect (= {:code [{:expr "(+ 1 2)" :time-ms 50}]}
                  (:value result)))
        (expect (empty? (:warnings result)))))

    (it "handles EDN with Clojure-style map inside :expr string"
      ;; The :expr value itself is a Clojure form — should remain a string
      (let [result (sut/parse-json "{:code [{:expr \"(list-dir \\\".\\\" {:glob \\\"**/*.clj\\\"})\" :time-ms 3000}]}")]
        (expect (= "(list-dir \".\" {:glob \"**/*.clj\"})"
                  (get-in (:value result) [:code 0 :expr]))))))

  (describe "deeply nested structures"
    (it "parses 3-level deep EDN maps"
      (let [result (sut/parse-json "{:a {:b {:c 42}}}")]
        (expect (= {:a {:b {:c 42}}} (:value result)))))

    (it "parses nested EDN map inside array inside map"
      (let [result (sut/parse-json "{:results [{:meta {:tags [:important :urgent]} :score 95}]}")]
        (expect (= {:results [{:meta {:tags ["important" "urgent"]} :score 95}]}
                  (:value result)))))

    (it "parses deeply nested mixed keyword values and maps"
      (let [result (sut/parse-json "{:config {:mode :fast :limits {:max-depth 10 :timeout 5000} :flags [:verbose :debug]}}")]
        (expect (= {:config {:mode "fast"
                             :limits {:max-depth 10 :timeout 5000}
                             :flags ["verbose" "debug"]}}
                  (:value result)))))

    (it "parses array of arrays with EDN keywords"
      (let [result (sut/parse-json "{:matrix [[{:val 1} {:val 2}] [{:val 3} {:val 4}]]}")]
        (expect (= {:matrix [[{:val 1} {:val 2}] [{:val 3} {:val 4}]]}
                  (:value result)))))

    (it "parses real iteration-spec shape with :final nested map"
      (let [result (sut/parse-json "{:code [{:expr \"(+ 1 2)\" :time-ms 50}] :final {:answer \"The answer is 3\" :confidence :high :language :en}}")]
        (expect (= {:code [{:expr "(+ 1 2)" :time-ms 50}]
                    :final {:answer "The answer is 3"
                            :confidence "high"
                            :language "en"}}
                  (:value result)))))

    (it "parses multiple code blocks with nested forms as strings"
      (let [result (sut/parse-json "{:code [{:expr \"(defn greet [name] (str \\\"Hello \\\" name))\" :time-ms 200} {:expr \"(greet \\\"world\\\")\" :time-ms 50}]}")]
        (expect (= 2 (count (get-in (:value result) [:code]))))
        (expect (= "(defn greet [name] (str \"Hello \" name))"
                  (get-in (:value result) [:code 0 :expr])))))

    (it "parses deeply truncated nested structure"
      (let [result (sut/parse-json "{:code [{:expr \"(+ 1 2)\" :time-ms 50}] :next {:reasoning :deep :context {:depth 3")]
        (expect (map? (:value result)))
        (expect (contains? (:value result) :code))
        (expect (contains? (:value result) :next))))

    (it "parses nested EDN inside clojure markdown fence"
      (let [result (sut/parse-json "Here is the result:\n```clojure\n{:code [{:expr \"(map inc [1 2 3])\" :time-ms 100}] :final {:answer \"[2 3 4]\" :confidence :high}}\n```")]
        (expect (= {:code [{:expr "(map inc [1 2 3])" :time-ms 100}]
                    :final {:answer "[2 3 4]" :confidence "high"}}
                  (:value result)))))

    (it "parses nested EDN inside edn markdown fence"
      (let [result (sut/parse-json "```edn\n{:config {:providers [{:id :openai :model \"gpt-4\"} {:id :anthropic :model \"claude\"}]}}\n```")]
        (expect (= {:config {:providers [{:id "openai" :model "gpt-4"}
                                         {:id "anthropic" :model "claude"}]}}
                  (:value result)))))

    (it "parses 4-level deep nesting with mixed types"
      (let [result (sut/parse-json "{:l1 {:l2 {:l3 {:l4 \"bottom\"}}}}")]
        (expect (= "bottom" (get-in (:value result) [:l1 :l2 :l3 :l4])))))

    (it "parses empty nested structures"
      (let [result (sut/parse-json "{:a {} :b [] :c {:d [] :e {}}}")]
        (expect (= {:a {} :b [] :c {:d [] :e {}}} (:value result))))))

  (describe "brutally nested / complex real-world shapes"
    (it "parses 6-level deep EDN nesting"
      (let [result (sut/parse-json "{:a {:b {:c {:d {:e {:f 999}}}}}}")]
        (expect (= 999 (get-in (:value result) [:a :b :c :d :e :f])))))

    (it "parses array of deeply nested maps with keyword values at every level"
      (let [result (sut/parse-json
                     "{:steps [{:action :read :target {:path \"/src/core.clj\" :opts {:encoding :utf8 :follow-links true}}} {:action :write :target {:path \"/out.txt\" :opts {:mode :overwrite :create-parents true}}}]}")]
        (expect (= "read" (get-in (:value result) [:steps 0 :action])))
        (expect (= "/src/core.clj" (get-in (:value result) [:steps 0 :target :path])))
        (expect (= "utf8" (get-in (:value result) [:steps 0 :target :opts :encoding])))
        (expect (= "overwrite" (get-in (:value result) [:steps 1 :target :opts :mode])))
        (expect (= true (get-in (:value result) [:steps 1 :target :opts :create-parents])))))

    (it "parses multi-turn conversation shape with nested arrays of maps"
      (let [result (sut/parse-json
                     "{:code [{:expr \"(require '[clojure.string :as str])\" :time-ms 10} {:expr \"(str/split \\\"a,b,c\\\" #\\\",\\\")\" :time-ms 50} {:expr \"(def results (map str/upper-case [\\\"hello\\\" \\\"world\\\"]))\" :time-ms 100}] :next {:reasoning :balanced :hints [{:type :perf :msg \"avoid reflection\"}]}}")]
        (expect (= 3 (count (get-in (:value result) [:code]))))
        (expect (= "balanced" (get-in (:value result) [:next :reasoning])))
        (expect (= "perf" (get-in (:value result) [:next :hints 0 :type])))
        (expect (= "avoid reflection" (get-in (:value result) [:next :hints 0 :msg])))))

    (it "parses map where values are heterogeneous: strings, numbers, booleans, keywords, nested maps, arrays"
      (let [result (sut/parse-json
                     "{:name \"project-x\" :version 3 :stable true :license :mit :deps [{:group \"org.clojure\" :artifact \"clojure\" :version \"1.11.1\"} {:group \"com.blockether\" :artifact \"svar\" :version \"0.3.7\"}] :config {:jvm-opts [\"-Xmx2g\" \"-server\"] :features {:hot-reload true :aot false}}}")]
        (expect (= "project-x" (:name (:value result))))
        (expect (= 3 (:version (:value result))))
        (expect (= true (:stable (:value result))))
        (expect (= "mit" (:license (:value result))))
        (expect (= 2 (count (:deps (:value result)))))
        (expect (= "1.11.1" (get-in (:value result) [:deps 0 :version])))
        (expect (= ["-Xmx2g" "-server"] (get-in (:value result) [:config :jvm-opts])))
        (expect (= false (get-in (:value result) [:config :features :aot])))))

    (it "parses array at top level containing nested EDN maps"
      (let [result (sut/parse-json
                     "[{:id 1 :children [{:id 11 :leaf true} {:id 12 :children [{:id 121 :leaf true}]}]} {:id 2 :leaf true}]")]
        (expect (= 2 (count (:value result))))
        (expect (= 1 (get-in (:value result) [0 :id])))
        (expect (= true (get-in (:value result) [0 :children 0 :leaf])))
        (expect (= 121 (get-in (:value result) [0 :children 1 :children 0 :id])))
        (expect (= true (get-in (:value result) [1 :leaf])))))

    (it "parses clojure fence with multi-line EDN (newlines inside fence)"
      (let [input (str "```clojure\n"
                    "{:code\n"
                    " [{:expr \"(+ 1 2)\"\n"
                    "   :time-ms 50}\n"
                    "  {:expr \"(* 3 4)\"\n"
                    "   :time-ms 30}]\n"
                    " :final {:answer \"done\"}}\n"
                    "```")
            result (sut/parse-json input)]
        (expect (= 2 (count (get-in (:value result) [:code]))))
        (expect (= "(+ 1 2)" (get-in (:value result) [:code 0 :expr])))
        (expect (= 30 (get-in (:value result) [:code 1 :time-ms])))
        (expect (= "done" (get-in (:value result) [:final :answer])))))

    (it "parses EDN with string values containing colons, braces, brackets"
      (let [result (sut/parse-json
                     "{:expr \"(let [{:keys [a b]} m] (str a \\\":\\\" b))\" :note \"handles {:keys} destructuring [and arrays]\"}")]
        (expect (= "(let [{:keys [a b]} m] (str a \":\" b))"
                  (:expr (:value result))))
        (expect (= "handles {:keys} destructuring [and arrays]"
                  (:note (:value result))))))

    (it "parses massive iteration-spec shape: code + next + final all present"
      (let [result (sut/parse-json
                     "{:code [{:expr \"(def db (connect! {:host \\\"localhost\\\" :port 5432}))\" :time-ms 2000} {:expr \"(query db \\\"SELECT * FROM users LIMIT 10\\\")\" :time-ms 500}] :next {:reasoning :deep :optimize [{:type :cache :ttl 3600} {:type :batch :size 100}]} :final {:answer \"Found 10 users\" :confidence :high :sources [\"db/users\"] :language :en}}")]
        (expect (= 2 (count (get-in (:value result) [:code]))))
        (expect (= 2000 (get-in (:value result) [:code 0 :time-ms])))
        (expect (= "deep" (get-in (:value result) [:next :reasoning])))
        (expect (= 3600 (get-in (:value result) [:next :optimize 0 :ttl])))
        (expect (= "Found 10 users" (get-in (:value result) [:final :answer])))
        (expect (= "high" (get-in (:value result) [:final :confidence])))
        (expect (= ["db/users"] (get-in (:value result) [:final :sources])))))

    (it "parses truncated mid-string inside deeply nested structure"
      (let [result (sut/parse-json
                     "{:code [{:expr \"(defn process [items] (reduce (fn [acc x] (update acc :count inc)) {:count 0} items))\" :time-ms 300}] :next {:reasoning :deep :context {:vars [{:name \"process\" :type :fn}] :depth 5")]
        (expect (map? (:value result)))
        (expect (= 1 (count (get-in (:value result) [:code]))))
        (expect (= 300 (get-in (:value result) [:code 0 :time-ms])))
        (expect (= "deep" (get-in (:value result) [:next :reasoning])))))

    (it "parses EDN fence with keyword values in arrays (vector of keywords)"
      (let [result (sut/parse-json "```edn\n{:permissions [:read :write :admin] :roles [{:name \"superuser\" :grants [:read :write :admin :delete]} {:name \"viewer\" :grants [:read]}]}\n```")]
        (expect (= ["read" "write" "admin"] (get-in (:value result) [:permissions])))
        (expect (= ["read" "write" "admin" "delete"]
                  (get-in (:value result) [:roles 0 :grants])))
        (expect (= ["read"] (get-in (:value result) [:roles 1 :grants])))))

    (it "parses interleaved JSON + EDN keys in nested structure"
      (let [result (sut/parse-json
                     "{\"outer\": {:inner-edn [{:x 1 :y 2} {:x 3 :y 4}] :meta {\"created\": \"2024-01-01\" :status :active}}}")]
        (expect (= 1 (get-in (:value result) [:outer :inner-edn 0 :x])))
        (expect (= 4 (get-in (:value result) [:outer :inner-edn 1 :y])))
        (expect (= "2024-01-01" (get-in (:value result) [:outer :meta :created])))
        (expect (= "active" (get-in (:value result) [:outer :meta :status])))))))

