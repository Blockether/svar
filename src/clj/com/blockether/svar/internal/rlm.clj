(ns com.blockether.svar.internal.rlm
  "Recursive Language Model (RLM) for processing arbitrarily large contexts.
   
   RLM enables an LLM to iteratively write and execute Clojure code to examine,
   filter, and process large contexts that exceed token limits. The LLM writes
   code that runs in a sandboxed SCI (Small Clojure Interpreter) environment,
   inspects results, and decides whether to continue iterating or return a final
   answer.
   
   ## API
   
   ```clojure
   ;; 1. Create environment (holds DB, config, SCI context)
   (def env (rlm/create-env {:config llm-config :path \"/tmp/my-rlm\"}))
   
   ;; 2. Ingest documents (can call multiple times)
   (rlm/ingest-to-env! env documents)
   (rlm/ingest-to-env! env more-documents)
   
   ;; 3. Run queries (reuses same env)
   (rlm/query-env! env \"What is X?\")
   (rlm/query-env! env \"Find Y\" {:spec my-spec})
   
   ;; 4. Dispose when done
   (rlm/dispose-env! env)
   ```
   
   ## Key Features
   
   - Iterative code execution: LLM writes code, sees results, writes more code
   - FINAL termination: LLM signals completion by returning {:FINAL result}
   - Recursive llm-query: Code can call back to the LLM for sub-tasks
   - Sandboxed evaluation: Uses SCI for safe, controlled code execution
   - Documents: Complete structure stored exactly as-is:
   - Documents with metadata
   - Pages with page nodes (paragraphs, headings, images, tables)
   - TOC entries
   - Learnings: DB-backed meta-insights that persist across sessions
   - Spec support: Define output shape, validate FINAL answers
   - Auto-refinement: Self-critique loop improves answer quality
   
   ## LLM Available Functions (in SCI sandbox)
   
   Document search:
    - (list-documents) - List all stored documents
    - (get-document doc-id) - Get document metadata
    - (search-page-nodes query) - List/filter actual content
    - (get-page-node node-id) - Get full page node content
    - (list-page-nodes opts) - List page nodes with filters
    - (search-toc-entries query) - List/filter table of contents
    - (get-toc-entry entry-id) - Get TOC entry
    - (list-toc-entries) - List all TOC entries
     
     Learnings:
    - (store-learning insight) - Store meta-insight
    - (search-learnings query) - Search learnings
    - (vote-learning id :useful/:not-useful) - Vote on learning
    
    History:
    - (search-history n) - Get recent messages (default 5)
    - (get-history n) - Get recent messages (default 10)"
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
   [clojure.set :as set]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.router :as router]
   [com.blockether.svar.internal.spec :as spec]
   [com.blockether.svar.internal.jsonish :as jsonish]
   [com.blockether.svar.internal.tokens :as tokens]
   [com.blockether.svar.internal.util :as util]
   [com.blockether.svar.internal.rlm.internal.pageindex.core :as pageindex]
   [com.blockether.svar.internal.rlm.internal.pageindex.spec :as rlm-spec]
   [datalevin.core :as d]
   [sci.core :as sci]
   [taoensso.trove :as trove])
  (:import
   [java.util Base64 UUID]))

;; =============================================================================
;; Constants
;; =============================================================================

(def MAX_ITERATIONS
  "Maximum number of code execution iterations before forcing termination."
  50)

(def DEFAULT_RECURSION_DEPTH
  "Default maximum depth of nested rlm-query calls. Can be overridden via :max-recursion-depth."
  5)

(def EVAL_TIMEOUT_MS
  "Timeout in milliseconds for code evaluation in SCI sandbox."
  30000)

(def ^:private STDOUT_TRUNCATION_LIMIT
  "Maximum characters of stdout/result to include in iteration feedback.
   Per the RLM paper: 'Only (constant-size) metadata about stdout, like a short
   prefix and length, is appended to the model's history for the next iteration.
   This is key: it forces the model to rely on variables and sub-calls to manage
   long strings instead of polluting its window.'"
  300)

(def ^:private ENTITY_EXTRACTION_OBJECTIVE
  "Extract entities and relationships from the provided content.\n\nReturn only the fields in the schema.\nFocus on concrete entities, avoid duplication, and include page/section when known.")

(def ^:private ENTITY_SPEC
  "Spec for extracted entities."
  (spec/spec
   :entity
   {::spec/key-ns "entity"}
   (spec/field {::spec/name :name
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Entity name"})
   (spec/field {::spec/name :type
                ::spec/type :spec.type/keyword
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Entity type (e.g. :party, :organization, :obligation, :term, :condition)"})
   (spec/field {::spec/name :description
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Entity description"})
   (spec/field {::spec/name :section
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/required false
                ::spec/description "Section identifier or label"})
   (spec/field {::spec/name :page
                ::spec/type :spec.type/int
                ::spec/cardinality :spec.cardinality/one
                ::spec/required false
                ::spec/description "Page index (0-based)"})))

(def ^:private RELATIONSHIP_SPEC
  "Spec for extracted relationships."
  (spec/spec
   :relationship
   {::spec/key-ns "relationship"}
   (spec/field {::spec/name :source
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Source entity name"})
   (spec/field {::spec/name :target
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Target entity name"})
   (spec/field {::spec/name :type
                ::spec/type :spec.type/keyword
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Relationship type (e.g. :owns, :obligates, :references, :defines)"})
   (spec/field {::spec/name :description
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/required false
                ::spec/description "Relationship description"})))

(def ENTITY_EXTRACTION_SPEC
  "Spec for entity extraction output."
  (spec/spec
   {:refs [ENTITY_SPEC RELATIONSHIP_SPEC]}
   (spec/field {::spec/name :entities
                ::spec/type :spec.type/ref
                ::spec/target :entity
                ::spec/cardinality :spec.cardinality/many
                ::spec/description "Extracted entities"})
   (spec/field {::spec/name :relationships
                ::spec/type :spec.type/ref
                ::spec/target :relationship
                ::spec/cardinality :spec.cardinality/many
                ::spec/required false
                ::spec/description "Extracted relationships"})))

(def ^:private ITERATION_SPEC
  "Spec for each RLM iteration response. Forces structured output from LLM."
  (spec/spec
   (spec/field {::spec/name :thinking
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Your reasoning: what you observed, what you learned, what to do next"})
   (spec/field {::spec/name :code
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/many
                ::spec/description "Clojure expressions to execute. Use (FINAL answer) when done."})))

(defn bytes->base64
  "Converts raw bytes to a base64 string.
   
   Params:
   `bs` - byte[]. Raw bytes.
   
   Returns:
   String. Base64-encoded representation."
  [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(def ^:dynamic *max-recursion-depth*
  "Dynamic var for max recursion depth. Bound per query-env! call."
  DEFAULT_RECURSION_DEPTH)

(def ^:dynamic *rlm-ctx*
  "Dynamic context for RLM debug logging. Bind with {:rlm-debug? true :rlm-phase :phase-name :rlm-env-id \"...\"}."
  nil)

;; Forward declarations for mutually dependent functions
(declare make-llm-query-fn make-routed-llm-query-fn make-rlm-query-fn run-sub-rlm
         build-system-prompt run-iteration format-executions)

;; =============================================================================
;; SCI Sandbox Configuration
;; =============================================================================

(def SAFE_BINDINGS
  "Map of safe clojure.core functions exposed to SCI sandbox."
  {'+ +, '- -, '* *, '/ /,
   'first first, 'rest rest, 'next next, 'cons cons, 'conj conj,
   'map map, 'filter filter, 'reduce reduce, 'reduce-kv reduce-kv,
   'assoc assoc, 'dissoc dissoc, 'get get, 'get-in get-in,
   'update update, 'update-in update-in, 'assoc-in assoc-in,
   'keys keys, 'vals vals, 'count count, 'empty? empty?,
   'seq seq, 'vec vec, 'vector vector, 'list list, 'set set,
   'hash-map hash-map, 'sorted-map sorted-map,
   'nth nth, 'take take, 'drop drop, 'take-while take-while,
   'drop-while drop-while, 'partition partition, 'partition-all partition-all,
   'group-by group-by, 'frequencies frequencies,
   'sort sort, 'sort-by sort-by, 'reverse reverse, 'distinct distinct,
   'mapcat mapcat, 'keep keep, 'keep-indexed keep-indexed,
   'some some, 'every? every?, 'not-every? not-every?, 'not-any? not-any?,
   'nil? nil?, 'some? some?, 'string? string?, 'number? number?,
   'keyword? keyword?, 'map? map?, 'vector? vector?, 'set? set?,
   'pos? pos?, 'neg? neg?, 'zero? zero?, 'even? even?, 'odd? odd?,
   '= =, '== ==, 'not= not=, '< <, '> >, '<= <=, '>= >=,
   'min min, 'max max, 'min-key min-key, 'max-key max-key,
   'inc inc, 'dec dec, 'quot quot, 'rem rem, 'mod mod,
   'not not,
   'str str, 'subs subs, 'name name, 'keyword keyword, 'symbol symbol,
   'int int, 'long long, 'float float, 'double double,
   'println println, 'print print, 'pr pr, 'prn prn, 'format format,
   'identity identity, 'constantly constantly, 'comp comp, 'partial partial,
   'apply apply, 'juxt juxt, 'fnil fnil,
   'atom atom, 'swap! swap!, 'reset! reset!, 'deref deref,
   'rand rand, 'rand-int rand-int, 'rand-nth rand-nth,
   'range range, 'repeat repeat, 'iterate iterate, 'cycle cycle,
    ;; Regex functions
   're-pattern re-pattern, 're-find re-find, 're-matches re-matches,
   're-seq re-seq, 're-groups re-groups, 're-matcher re-matcher,
    ;; Set functions (with set- prefix to avoid collision with core)
   'set-union set/union, 'set-intersection set/intersection,
   'set-difference set/difference, 'set-subset? set/subset?,
   'set-superset? set/superset?})

;; =============================================================================
;; String Helper Functions
;; =============================================================================

(defn- str-lines [s] (when s (str/split-lines s)))
(defn- str-words [s] (when s (str/split (str/trim s) #"\s+")))
(defn- str-truncate [s n] (when s (if (> (count s) n) (subs s 0 n) s)))
(defn- str-join [sep coll] (str/join sep coll))
(defn- str-split [s re] (when s (str/split s re)))
(defn- str-replace [s match replacement] (when s (str/replace s match replacement)))
(defn- str-trim [s] (when s (str/trim s)))
(defn- str-lower [s] (when s (str/lower-case s)))
(defn- str-upper [s] (when s (str/upper-case s)))
(defn- str-blank? [s] (str/blank? s))
(defn- str-includes? [s substr] (when s (str/includes? s substr)))
(defn- str-starts-with? [s prefix] (when s (str/starts-with? s prefix)))
(defn- str-ends-with? [s suffix] (when s (str/ends-with? s suffix)))

;; =============================================================================
;; Stdout Truncation (RLM Paper §3: constant-size metadata)
;; =============================================================================

(defn- truncate-for-history
  "Truncates output for conversation history, adding length metadata."
  [s max-chars]
  (if (or (nil? s) (<= (count s) max-chars))
    s
    (str (subs s 0 max-chars)
         "\n... [" (- (count s) max-chars) " more chars — store in a variable with (def my-var ...)]")))

;; =============================================================================
;; Raw Text Access (RLM Paper §3: symbolic handle to prompt P)
;; =============================================================================

(defn- build-raw-text
  "Concatenates all page node content from Datalevin conn into a single string."
  [conn]
  (when conn
    (->> (d/q '[:find [(pull ?e [:page.node/content :page.node/document-id :page.node/page-id :page.node/id]) ...]
               :where [?e :page.node/id _]]
             (d/db conn))
         (sort-by (juxt :page.node/document-id :page.node/page-id :page.node/id))
         (keep :page.node/content)
         (str/join "\n"))))

(defn- build-page-texts
  "Builds a vector of page texts indexed by page number across all documents."
  [conn]
  (when conn
    (let [page-nodes (d/q '[:find [(pull ?e [:page.node/content :page.node/page-id :page.node/id]) ...]
                            :where [?e :page.node/id _]]
                          (d/db conn))
          pages (d/q '[:find [(pull ?e [:page/id :page/document-id :page/index]) ...]
                       :where [?e :page/id _]]
                     (d/db conn))
          nodes-by-page (group-by :page.node/page-id page-nodes)
          sorted-pages (sort-by (juxt :page/document-id :page/index) pages)]
      (mapv (fn [page]
              (let [nodes (get nodes-by-page (:page/id page) [])
                    sorted-nodes (sort-by :page.node/id nodes)]
                (str/join "\n" (keep :page.node/content sorted-nodes))))
            sorted-pages))))

;; =============================================================================
;; Debug Logging
;; =============================================================================

(defn- rlm-debug!
  "Logs at :info level only when :rlm-debug? is true in *rlm-ctx*.
   Includes :rlm-phase from context automatically in data."
  [data msg]
  (when (:rlm-debug? *rlm-ctx*)
    (trove/log! {:level :info :data (assoc data :rlm-phase (:rlm-phase *rlm-ctx*)) :msg msg})))

(defn- realize-value
  "Recursively realizes lazy sequences in a value to prevent opaque LazySeq@hash.
   Converts lazy seqs to vectors, walks into maps/vectors/sets."
  [v]
  (cond
    (instance? clojure.lang.LazySeq v) (mapv realize-value v)
    (map? v) (persistent! (reduce-kv (fn [m k val] (assoc! m k (realize-value val))) (transient {}) v))
    (vector? v) (mapv realize-value v)
    (set? v) (into #{} (map realize-value) v)
    :else v))

;; =============================================================================
;; Date Helper Functions
;; =============================================================================

(defn- parse-date
  "Parses an ISO-8601 date string (YYYY-MM-DD).
   
   Params:
   `date-str` - String. ISO-8601 date string.
   
   Returns:
   String representation of parsed date, or nil if invalid."
  [date-str]
  (try
    (when date-str
      (str (java.time.LocalDate/parse date-str)))
    (catch Exception _
      nil)))

(defn- date-before?
  "Checks if first date is before second date.
   
   Params:
   `date1` - String. ISO-8601 date string.
   `date2` - String. ISO-8601 date string.
   
   Returns:
   Boolean. True if date1 < date2, false otherwise or on parse error."
  [date1 date2]
  (try
    (when (and date1 date2)
      (.isBefore (java.time.LocalDate/parse date1)
                 (java.time.LocalDate/parse date2)))
    (catch Exception _
      false)))

(defn- date-after?
  "Checks if first date is after second date.
   
   Params:
   `date1` - String. ISO-8601 date string.
   `date2` - String. ISO-8601 date string.
   
   Returns:
   Boolean. True if date1 > date2, false otherwise or on parse error."
  [date1 date2]
  (try
    (when (and date1 date2)
      (.isAfter (java.time.LocalDate/parse date1)
                (java.time.LocalDate/parse date2)))
    (catch Exception _
      false)))

(defn- days-between
  "Calculates number of days between two dates.
   
   Params:
   `date1` - String. ISO-8601 date string.
   `date2` - String. ISO-8601 date string.
   
   Returns:
   Long. Number of days (negative if date1 > date2), or nil on parse error."
  [date1 date2]
  (try
    (when (and date1 date2)
      (.between java.time.temporal.ChronoUnit/DAYS
                (java.time.LocalDate/parse date1)
                (java.time.LocalDate/parse date2)))
    (catch Exception _
      nil)))

(defn- date-plus-days
  "Adds days to a date.
   
   Params:
   `date-str` - String. ISO-8601 date string.
   `days` - Long. Number of days to add.
   
   Returns:
   String. ISO-8601 date string, or nil on parse error."
  [date-str days]
  (try
    (when date-str
      (str (.plusDays (java.time.LocalDate/parse date-str) days)))
    (catch Exception _
      nil)))

(defn- date-minus-days
  "Subtracts days from a date.
   
   Params:
   `date-str` - String. ISO-8601 date string.
   `days` - Long. Number of days to subtract.
   
   Returns:
   String. ISO-8601 date string, or nil on parse error."
  [date-str days]
  (try
    (when date-str
      (str (.minusDays (java.time.LocalDate/parse date-str) days)))
    (catch Exception _
      nil)))

(defn- date-format
  "Formats a date with a custom pattern.
   
   Params:
   `date-str` - String. ISO-8601 date string.
   `pattern` - String. DateTimeFormatter pattern (e.g., \"dd/MM/yyyy\").
   
   Returns:
   String. Formatted date, or nil on parse/format error."
  [date-str pattern]
  (try
    (when (and date-str pattern)
      (let [formatter (java.time.format.DateTimeFormatter/ofPattern pattern)
            date (java.time.LocalDate/parse date-str)]
        (.format date formatter)))
    (catch Exception _
      nil)))

(defn- today-str
  "Returns today's date as ISO-8601 string.
   
   Returns:
   String. Today's date in YYYY-MM-DD format."
  []
  (str (java.time.LocalDate/now)))

;; =============================================================================
;; Datalevin Schema and Connection Lifecycle
;; =============================================================================

(def ^:private RLM_SCHEMA
  "Unified Datalevin schema for all RLM data."
  {;; Messages (tagged by env-id to distinguish parent vs sub-RLM)
   :message/id        {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :message/env-id    {:db/valueType :db.type/string :db/doc "RLM environment that wrote this message"}
   :message/role      {:db/valueType :db.type/keyword}
   :message/content   {:db/valueType :db.type/string :db/fulltext true}
   :message/tokens    {:db/valueType :db.type/long}
   :message/timestamp {:db/valueType :db.type/instant}
   :message/iteration {:db/valueType :db.type/long}

   ;; Tool Calls (recorded during SCI code execution)
   :tool-call/id          {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :tool-call/env-id      {:db/valueType :db.type/string :db/doc "RLM environment that invoked this tool"}
   :tool-call/tool-name   {:db/valueType :db.type/string :db/doc "SCI symbol name of the tool called"}
   :tool-call/input-edn   {:db/valueType :db.type/string :db/doc "EDN-encoded input parameters"}
   :tool-call/output-edn  {:db/valueType :db.type/string :db/doc "EDN-encoded output (truncated)"}
   :tool-call/error       {:db/valueType :db.type/string :db/doc "Error message if call failed"}
   :tool-call/duration-ms {:db/valueType :db.type/long   :db/doc "Execution time in ms"}
   :tool-call/iteration   {:db/valueType :db.type/long   :db/doc "Which iteration this call happened in"}
   :tool-call/timestamp   {:db/valueType :db.type/instant}

   ;; Documents
   :document/id         {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :document/name       {:db/valueType :db.type/string}
   :document/title      {:db/valueType :db.type/string :db/fulltext true}
   :document/abstract   {:db/valueType :db.type/string :db/fulltext true}
   :document/extension  {:db/valueType :db.type/string}
   :document/author     {:db/valueType :db.type/string}
   :document/page-count {:db/valueType :db.type/long}
   :document/created-at {:db/valueType :db.type/instant}
   :document/updated-at {:db/valueType :db.type/instant}

   ;; Pages
   :page/id          {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :page/document-id {:db/valueType :db.type/string}
   :page/index       {:db/valueType :db.type/long}

   ;; Page Nodes (content)
   :page.node/id           {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :page.node/page-id      {:db/valueType :db.type/string}
   :page.node/document-id  {:db/valueType :db.type/string}
   :page.node/local-id     {:db/valueType :db.type/string}
   :page.node/type         {:db/valueType :db.type/keyword}
   :page.node/content      {:db/valueType :db.type/string :db/fulltext true}
   :page.node/description  {:db/valueType :db.type/string :db/fulltext true}
   :page.node/level        {:db/valueType :db.type/string}
   :page.node/parent-id    {:db/valueType :db.type/string}
   :page.node/image-data   {:db/valueType :db.type/bytes}
   :page.node/continuation? {:db/valueType :db.type/boolean}
   :page.node/caption      {:db/valueType :db.type/string}
   :page.node/kind         {:db/valueType :db.type/string}
   :page.node/bbox         {:db/valueType :db.type/string}
   :page.node/group-id     {:db/valueType :db.type/string}

   ;; TOC Entries
   :document.toc/id              {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :document.toc/document-id     {:db/valueType :db.type/string}
   :document.toc/type            {:db/valueType :db.type/keyword}
   :document.toc/title           {:db/valueType :db.type/string :db/fulltext true}
   :document.toc/description     {:db/valueType :db.type/string :db/fulltext true}
   :document.toc/target-page     {:db/valueType :db.type/long}
   :document.toc/target-section-id {:db/valueType :db.type/string}
   :document.toc/level           {:db/valueType :db.type/string}
   :document.toc/parent-id       {:db/valueType :db.type/string}
   :document.toc/created-at      {:db/valueType :db.type/instant}

   ;; Entities
   :entity/id          {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :entity/name        {:db/valueType :db.type/string :db/fulltext true}
   :entity/type        {:db/valueType :db.type/keyword}
   :entity/description {:db/valueType :db.type/string :db/fulltext true}
   :entity/document-id {:db/valueType :db.type/string}
   :entity/page        {:db/valueType :db.type/long}
   :entity/section     {:db/valueType :db.type/string}
   :entity/created-at  {:db/valueType :db.type/instant}

   ;; Relationships
   :relationship/id               {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :relationship/type             {:db/valueType :db.type/keyword}
   :relationship/source-entity-id {:db/valueType :db.type/uuid}
   :relationship/target-entity-id {:db/valueType :db.type/uuid}
   :relationship/description      {:db/valueType :db.type/string}
   :relationship/document-id      {:db/valueType :db.type/string}

   ;; Learnings
   :learning/id               {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :learning/insight          {:db/valueType :db.type/string :db/fulltext true}
   :learning/context          {:db/valueType :db.type/string :db/fulltext true}
   :learning/timestamp        {:db/valueType :db.type/instant}
   :learning/useful-count     {:db/valueType :db.type/long}
   :learning/not-useful-count {:db/valueType :db.type/long}
   :learning/applied-count    {:db/valueType :db.type/long}
   :learning/last-evaluated   {:db/valueType :db.type/instant}

   ;; Claims
   :claim/id                   {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :claim/text                 {:db/valueType :db.type/string}
   :claim/document-id          {:db/valueType :db.type/string}
   :claim/page                 {:db/valueType :db.type/long}
   :claim/section              {:db/valueType :db.type/string}
   :claim/quote                {:db/valueType :db.type/string}
   :claim/confidence           {:db/valueType :db.type/double}
   :claim/query-id             {:db/valueType :db.type/uuid}
   :claim/verified?            {:db/valueType :db.type/boolean}
   :claim/verification-verdict {:db/valueType :db.type/string}
   :claim/created-at           {:db/valueType :db.type/instant}

   ;; Raw documents (PageIndex source of truth, stored as EDN string)
   :raw-document/id      {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :raw-document/content {:db/valueType :db.type/string}})

(defn- create-rlm-conn
  "Creates a Datalevin connection for RLM.
   With path: persistent DB. Without: temp DB (deleted on dispose)."
  [path]
  (let [dir (or path (str (System/getProperty "java.io.tmpdir") "/rlm-" (UUID/randomUUID)))
        conn (d/get-conn dir RLM_SCHEMA)]
    {:conn conn :path dir :owned? (nil? path)}))

(defn- dispose-rlm-conn!
  "Closes the Datalevin connection and deletes temp DB if owned."
  [{:keys [conn path owned?]}]
  (try
    (d/close conn)
    (catch Exception e
      (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Failed to close RLM connection"})))
  (when (and owned? path (fs/exists? path))
    (try
      (fs/delete-tree path)
      (catch Exception e
        (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Failed to delete temp RLM DB"})))))

(declare get-recent-messages)
(declare db-list-page-nodes)
(declare db-list-toc-entries)
(declare db-list-entities)

;; =============================================================================
;; Message History (Datalevin-backed)
;; =============================================================================

(defn- store-message!
  "Stores a conversation message.

   Params:
   `db-info` - Map with :conn key.
   `role` - Keyword. :user, :assistant, or :system.
   `content` - String. Message content.
   `opts` - Map, optional:
     - :iteration - Integer. Which iteration this message is from.
     - :tokens - Integer. Pre-computed token count (computed if not provided).
     - :model - String. Model for token counting (default: gpt-4o).
     - :env-id - String. RLM environment ID (distinguishes parent vs sub-RLM).

   Returns:
   Map with :id, :role, :content, :tokens, :timestamp."
  ([db-info role content]
   (store-message! db-info role content {}))
  ([{:keys [conn]} role content {:keys [iteration tokens model env-id] :or {model "gpt-4o"}}]
   (when (and conn (not (str/blank? content)))
     (let [msg-id (UUID/randomUUID)
           token-count (or tokens
                           (try
                             (tokens/count-tokens model content)
                             (catch Exception _ (quot (count content) 4))))
           timestamp (java.util.Date.)
           msg (cond-> {:message/id msg-id
                        :message/role role
                        :message/content content
                        :message/tokens token-count
                        :message/timestamp timestamp
                        :message/iteration (or iteration 0)}
                 env-id (assoc :message/env-id env-id))]
       (d/transact! conn [msg])
       {:id msg-id :role role :content content :tokens token-count :timestamp timestamp}))))

(defn- store-tool-call!
  "Records a tool invocation in the RLM database.

   Params:
   `db-info` - Map with :conn key.
   `env-id` - String. RLM environment ID.
   `tool-name` - String. SCI symbol name of the tool.
   `input` - Any. Tool input (will be pr-str'd).
   `output` - Any. Tool output (will be pr-str'd, truncated to 2000 chars).
   `error` - String or nil. Error message if call failed.
   `duration-ms` - Long. Execution time.
   `iteration` - Long. Which iteration this call happened in.

   Returns:
   UUID of the stored tool call."
  [{:keys [conn]} env-id tool-name input output error duration-ms iteration]
  (when conn
    (let [call-id (UUID/randomUUID)
          entity (cond-> {:tool-call/id call-id
                          :tool-call/tool-name (str tool-name)
                          :tool-call/input-edn (str-truncate (pr-str input) 2000)
                          :tool-call/iteration (or iteration 0)
                          :tool-call/timestamp (java.util.Date.)}
                   env-id (assoc :tool-call/env-id env-id)
                   output (assoc :tool-call/output-edn (str-truncate (pr-str output) 2000))
                   error (assoc :tool-call/error (str error))
                   duration-ms (assoc :tool-call/duration-ms (long duration-ms)))]
      (d/transact! conn [entity])
      call-id)))

(defn- get-recent-messages
  "Gets the most recent messages by timestamp.

   Params:
   `db-info` - Map with :conn key.
   `limit` - Integer. Maximum messages to return.

   Returns:
   Vector of maps with :content, :role, :tokens, :timestamp."
  [{:keys [conn]} limit]
  (when conn
    (->> (d/q '[:find [(pull ?e [:message/content :message/role :message/tokens :message/timestamp]) ...]
               :where [?e :message/id _]]
             (d/db conn))
         (sort-by :message/timestamp #(compare %2 %1))
         (take limit)
         (mapv (fn [m]
                 {:content (:message/content m)
                  :role (:message/role m)
                  :tokens (:message/tokens m)
                  :timestamp (:message/timestamp m)})))))

(defn- count-history-tokens
  "Counts total tokens in message history.

   Params:
   `db-info` - Map with :conn key.

   Returns:
   Integer. Total tokens across all stored messages."
  [{:keys [conn]}]
  (when conn
    (let [tokens (d/q '[:find ?tokens
                        :where [?e :message/tokens ?tokens]]
                      (d/db conn))]
      (reduce + 0 (map first tokens)))))

;; =============================================================================
;; Smart Context Selection (Semantic + Token-Aware)
;; =============================================================================

(defn- select-rlm-iteration-context
  "Selects messages for an RLM iteration using recency-based selection.
   
   Optimized for RLM iteration loops:
   1. ALWAYS preserves system prompt (first message)
   2. ALWAYS preserves most recent messages (for conversation continuity)
   3. Uses recency to select MIDDLE messages within budget
    
    This ensures the LLM maintains coherent conversation flow while having
    access to recent past exploration.
   
   Params:
   `db-info` - Map with :store key (or nil)
   `current-messages` - Vector. Current messages array from iteration loop
   `max-tokens` - Integer. Token budget
   `opts` - Map, optional:
     - :model - Model for token counting (default: gpt-4o)
     - :preserve-recent - Number of recent messages to always keep (default: 4)"
  ([db-info current-messages max-tokens]
   (select-rlm-iteration-context db-info current-messages max-tokens {}))
  ([db-info current-messages max-tokens
    {:keys [model preserve-recent] :or {model "gpt-4o" preserve-recent 4}}]
   (let [;; If no DB or not enough messages, just truncate normally
         msg-count (count current-messages)]
     (if (or (nil? db-info) (nil? (:conn db-info)) (<= msg-count (inc preserve-recent)))
       (tokens/truncate-messages model current-messages max-tokens)

        ;; Recency-based selection
       (let [;; Always keep system prompt (first message)
             system-msg (first current-messages)
             system-tokens (tokens/count-messages model [system-msg])

             ;; Always keep most recent N messages for continuity
             recent-msgs (vec (take-last preserve-recent (rest current-messages)))
             recent-tokens (tokens/count-messages model recent-msgs)

             ;; Calculate budget for middle messages
             fixed-overhead (+ system-tokens recent-tokens 100) ; 100 token buffer
             history-budget (- max-tokens fixed-overhead)]

         (if (<= history-budget 0)
           ;; No room for history - just use system + recent
           (vec (concat [system-msg] recent-msgs))

            ;; Select recent past messages within budget
           (let [;; Middle messages (not system, not recent)
                 middle-count (- msg-count 1 preserve-recent)

                  ;; Get recent messages (recency-based selection)
                 similar (when (pos? middle-count)
                           (let [msgs (get-recent-messages db-info (min 20 (* 2 middle-count)))]
                             (->> (or msgs [])
                                  (remove #(= :system (:role %)))
                                  vec)))

                  ;; Select within budget, sorted by recency (most recent first)
                 selected-middle (loop [candidates (or similar [])
                                        selected []
                                        used-tokens 0]
                                   (if (or (empty? candidates)
                                           (>= used-tokens history-budget))
                                     selected
                                     (let [msg (first candidates)
                                           msg-tokens (:tokens msg 50)] ; Default 50 if missing
                                       (if (<= (+ used-tokens msg-tokens) history-budget)
                                         (recur (rest candidates)
                                                (conj selected {:role (name (:role msg))
                                                                :content (:content msg)})
                                                (+ used-tokens msg-tokens))
                                         ;; Skip this one, try next
                                         (recur (rest candidates) selected used-tokens)))))

                  ;; Reverse to get chronological order (oldest first)
                  ;; since messages are sorted by recency
                 ordered-middle (vec (reverse selected-middle))]

             ;; Combine: system + selected history + recent
             (vec (concat [system-msg] ordered-middle recent-msgs)))))))))

;; (Individual schema defs removed — unified RLM_SCHEMA used at conn creation)


;; =============================================================================
;; Example Learning (Embedding-Based Semantic Similarity)
;; =============================================================================

(def ^:private MAX_STORED_EXAMPLES
  "Maximum total examples to keep in the store. Oldest are evicted when exceeded."
  100)

(def ^:private SIMILARITY_THRESHOLD
  "Minimum cosine similarity (0-1) to consider an example relevant. 0.7 = fairly similar."
  0.7)

(def ^:private example-store
  "Atom storing examples as a vector of maps, each with :query, :answer, etc."
  (atom []))

(defn- store-example!
  "Stores an example for recency-based retrieval.
   
   Params:
   `query` - String. The query that was asked.
   `context-summary` - String. Brief summary of the context.
   `answer` - String. The answer that was given.
   `score` - Integer. Quality score (0-40).
   `feedback` - String or nil. Why the answer was bad (for bad examples).
   
   Returns:
   The stored example map."
  [query context-summary answer score feedback]
  (let [example {:query query
                 :context-summary context-summary
                 :answer answer
                 :score score
                 :feedback feedback
                 :timestamp (System/currentTimeMillis)
                 :good? (>= score 32)}]
    (swap! example-store
           (fn [examples]
             (let [updated (conj examples example)]
               ;; Evict oldest if over limit
               (if (> (count updated) MAX_STORED_EXAMPLES)
                 (vec (take-last MAX_STORED_EXAMPLES (sort-by :timestamp updated)))
                 updated))))
    example))

(def ^:private MAX_GOOD_EXAMPLES 3)
(def ^:private MAX_BAD_EXAMPLES 3)

(defn- get-examples
  "Retrieves recent examples, split into good and bad.
   
   Params:
   `query` - String. Ignored (kept for signature compatibility).
   `opts` - Map, optional:
     - :max-good - Integer. Max good examples to return (default: 3).
     - :max-bad - Integer. Max bad examples to return (default: 3).
   
   Returns:
   Map with :good and :bad vectors of example maps."
  [_query {:keys [max-good max-bad]
           :or {max-good MAX_GOOD_EXAMPLES max-bad MAX_BAD_EXAMPLES}}]
  (let [examples @example-store]
    (if (empty? examples)
      {:good [] :bad []}
      (let [sorted (->> examples (sort-by :timestamp >))
            good-examples (->> sorted
                               (filter :good?)
                               (take (min max-good MAX_GOOD_EXAMPLES))
                               vec)
            bad-examples (->> sorted
                              (filter (complement :good?))
                              (take (min max-bad MAX_BAD_EXAMPLES))
                              vec)]
        {:good good-examples :bad bad-examples}))))

(defn- clear-examples!
  "Clears all stored examples."
  []
  (reset! example-store []))

(defn- format-examples-for-prompt [{:keys [good bad]}]
  (when (or (seq good) (seq bad))
    (str "\n<learning_from_examples>\n"
         (when (seq good)
           (str "  <good_examples>\n"
                (str/join "\n" (map-indexed (fn [_i ex]
                                              (str "    <example score=\"" (:score ex) "\">\n"
                                                   "      <query>" (:query ex) "</query>\n"
                                                   "      <answer>" (:answer ex) "</answer>\n"
                                                   "    </example>")) good))
                "\n  </good_examples>\n"))
         (when (seq bad)
           (str "  <bad_examples>\n"
                (str/join "\n" (map-indexed (fn [_i ex]
                                              (str "    <example score=\"" (:score ex) "\">\n"
                                                   "      <query>" (:query ex) "</query>\n"
                                                   "      <answer>" (:answer ex) "</answer>\n"
                                                   "      <why_bad>" (or (:feedback ex) "Low score") "</why_bad>\n"
                                                   "    </example>")) bad))
                "\n  </bad_examples>\n"))
         "</learning_from_examples>")))

;; =============================================================================
;; Example Learning SCI Functions (for LLM to call)
;; =============================================================================

(defn- make-search-examples-fn
  "Creates a function for the LLM to search examples by query text.
   When query is nil/blank, returns recent examples by timestamp."
  []
  (fn search-examples
    ([query] (search-examples query 5))
    ([query top-k]
     (let [examples @example-store]
       (if (empty? examples)
         []
         (let [filtered (if (str/blank? (str query))
                          examples
                          (let [q (str-lower query)]
                            (filter (fn [ex]
                                      (or (str-includes? (str-lower (:query ex)) q)
                                          (str-includes? (str-lower (:answer ex)) q)))
                                    examples)))]
           (->> filtered
                (sort-by :timestamp >)
                (take top-k)
                (mapv #(select-keys % [:query :answer :score :good?])))))))))

(defn- make-get-recent-examples-fn
  "Creates a function for the LLM to get recent examples."
  []
  (fn get-recent-examples
    ([] (get-recent-examples 10))
    ([n]
     (let [examples @example-store]
       (->> examples
            (sort-by :timestamp >)
            (take n)
            (mapv #(select-keys % [:query :answer :score :good?])))))))

(defn- make-example-stats-fn
  "Creates a function for the LLM to get example statistics."
  []
  (fn example-stats []
    (let [examples @example-store
          good-count (count (filter :good? examples))
          bad-count (count (filter (complement :good?) examples))]
      {:total-examples (count examples)
       :good-examples good-count
       :bad-examples bad-count
       :avg-score (if (empty? examples)
                    0
                    (double (/ (reduce + (map :score examples)) (count examples))))})))

;; =============================================================================
;; Learnings System (DB-backed Meta-Insights)
;; =============================================================================

(def ^:private LEARNING_SIMILARITY_THRESHOLD
  "Minimum text similarity to consider a learning relevant."
  0.6)

(defn- db-store-learning!
  "Stores a meta-insight/learning for future retrieval.

   Params:
   `db-info` - Map with :conn key.
   `insight` - String. The learning/insight to store.
   `context` - String, optional. Task/domain context.

   Returns:
   Map with :learning/id, :learning/insight, :learning/context, :learning/timestamp."
  ([db-info insight] (db-store-learning! db-info insight nil))
  ([{:keys [conn]} insight context]
   (when conn
     (let [learning-id (UUID/randomUUID)
           timestamp (java.util.Date.)
           learning (cond-> {:learning/id learning-id
                             :learning/insight insight
                             :learning/timestamp timestamp
                             :learning/useful-count 0
                             :learning/not-useful-count 0
                             :learning/applied-count 0}
                      context (assoc :learning/context context))]
       (d/transact! conn [learning])
       {:learning/id learning-id
        :learning/insight insight
        :learning/context context
        :learning/timestamp timestamp}))))

(def ^:private DECAY_THRESHOLD
  "Learnings with negative vote ratio above this threshold (after min votes) are decayed."
  0.7)

(def ^:private DECAY_MIN_VOTES
  "Minimum total votes before decay filtering applies."
  5)

(defn- learning-decayed?
  "Returns true if a learning has decayed (>70% negative votes after 5+ total votes)."
  [useful-count not-useful-count]
  (let [total (+ (or useful-count 0) (or not-useful-count 0))]
    (and (>= total DECAY_MIN_VOTES)
         (> (/ (or not-useful-count 0) total) DECAY_THRESHOLD))))

(defn- db-vote-learning!
  "Records a vote for a learning's usefulness.

   Params:
   `db-info` - Map with :conn key.
   `learning-id` - UUID. The learning to vote on.
   `vote` - Keyword. Either :useful or :not-useful.

   Returns:
   Updated learning map with new vote counts, or nil if learning not found."
  [{:keys [conn]} learning-id vote]
  (when conn
    (let [entity (d/pull (d/db conn) '[*] [:learning/id learning-id])]
      (when (:db/id entity)
        (let [current-useful (or (:learning/useful-count entity) 0)
              current-not-useful (or (:learning/not-useful-count entity) 0)
              [new-useful new-not-useful] (case vote
                                            :useful [(inc current-useful) current-not-useful]
                                            :not-useful [current-useful (inc current-not-useful)]
                                            [current-useful current-not-useful])]
          (d/transact! conn [{:learning/id learning-id
                              :learning/useful-count new-useful
                              :learning/not-useful-count new-not-useful
                              :learning/last-evaluated (java.util.Date.)}])
          {:learning/id learning-id
           :learning/insight (:learning/insight entity)
           :learning/useful-count new-useful
           :learning/not-useful-count new-not-useful
           :learning/decayed? (learning-decayed? new-useful new-not-useful)})))))

(defn- db-increment-applied-count!
  "Increments the applied count for a learning.

   Params:
   `db-info` - Map with :conn key.
   `learning-id` - UUID. The learning that was applied.

   Returns:
   New applied count, or nil if learning not found."
  [{:keys [conn]} learning-id]
  (when conn
    (let [entity (d/pull (d/db conn) '[:learning/applied-count] [:learning/id learning-id])]
      (when entity
        (let [new-count (inc (or (:learning/applied-count entity) 0))]
          (d/transact! conn [{:learning/id learning-id :learning/applied-count new-count}])
          new-count)))))

;; =============================================================================
;; Query Helpers — small, named, documented query functions
;; =============================================================================

(defn- fulltext-learnings
  "Search learnings via Datalevin fulltext index."
  [conn query]
  (d/q '[:find [(pull ?e [:learning/id :learning/insight :learning/context
                           :learning/timestamp :learning/useful-count
                           :learning/not-useful-count :learning/applied-count]) ...]
         :in $ ?q
         :where [(fulltext $ ?q) [[?e]]]]
       (d/db conn) query))

(defn- scan-learnings
  "Search learnings via in-memory substring scan (fallback)."
  [conn query]
  (let [q (str-lower query)
        all (d/q '[:find [(pull ?e [:learning/id :learning/insight :learning/context
                                     :learning/timestamp :learning/useful-count
                                     :learning/not-useful-count :learning/applied-count]) ...]
                   :where [?e :learning/id _]]
                 (d/db conn))]
    (filter (fn [l]
              (or (str-includes? (str-lower (str (:learning/insight l))) q)
                  (str-includes? (str-lower (str (:learning/context l))) q)))
            all)))

(defn- all-learnings
  "Get all learnings from DB."
  [conn]
  (d/q '[:find [(pull ?e [:learning/id :learning/insight :learning/context
                           :learning/timestamp :learning/useful-count
                           :learning/not-useful-count :learning/applied-count]) ...]
         :where [?e :learning/id _]]
       (d/db conn)))

(defn- search-or-list-learnings
  "Search learnings with fulltext, falling back to scan. Nil query lists all."
  [conn query]
  (if query
    (try (fulltext-learnings conn query)
         (catch Exception _ (scan-learnings conn query)))
    (all-learnings conn)))

(defn- normalize-learning
  "Normalize a raw learning entity into a clean result map with decay status."
  [l]
  (let [useful (or (:learning/useful-count l) 0)
        not-useful (or (:learning/not-useful-count l) 0)]
    {:learning/id (:learning/id l)
     :insight (:learning/insight l)
     :context (:learning/context l)
     :timestamp (:learning/timestamp l)
     :useful-count useful
     :not-useful-count not-useful
     :decayed? (learning-decayed? useful not-useful)}))

(defn- db-get-learnings
  "Searches learnings by insight and context text, sorted by recency.

   Filters out decayed learnings (>70% negative votes after 5+ total votes).
   Automatically increments applied-count for returned learnings.
   When query is nil/blank, returns all non-decayed learnings by recency.

   Params:
   `db-info` - Map with :conn key.
   `query` - String. Case-insensitive text search over insight and context.
   `opts` - Map, optional:
     - :top-k - Integer. Max learnings to return (default: 5).
     - :include-decayed? - Boolean. Include decayed learnings (default: false).
     - :track-usage? - Boolean. Increment applied-count (default: true).

   Returns:
   Vector of learning maps with :learning/id, :insight, :context, :useful-count, :not-useful-count."
  ([db-info query] (db-get-learnings db-info query {}))
  ([{:keys [conn] :as db-info} query {:keys [top-k include-decayed? track-usage?]
                                      :or {top-k 5 include-decayed? false track-usage? true}}]
   (when conn
     (let [q (when-not (str/blank? (str query)) query)
           filtered (->> (search-or-list-learnings conn q)
                         (mapv normalize-learning)
                         (filter #(or include-decayed? (not (:decayed? %))))
                         (sort-by :timestamp #(compare %2 %1))
                         (take top-k)
                         vec)]
       (when track-usage?
         (doseq [{:keys [learning/id]} filtered]
           (db-increment-applied-count! db-info id)))
       filtered))))

(defn- db-learning-stats
  "Gets statistics about stored learnings including voting stats.

   Params:
   `db-info` - Map with :conn key.

   Returns:
   Map with :total-learnings, :active-learnings, :decayed-learnings,
   :with-context, :without-context, :total-votes, :total-applications."
  [{:keys [conn]}]
  (if conn
    (let [all (d/q '[:find [(pull ?e [:learning/id :learning/context :learning/useful-count
                                      :learning/not-useful-count :learning/applied-count]) ...]
                    :where [?e :learning/id _]]
                   (d/db conn))
          total (count all)
          with-context (count (filter #(some? (:learning/context %)) all))
          decayed (count (filter #(learning-decayed? (or (:learning/useful-count %) 0)
                                                     (or (:learning/not-useful-count %) 0))
                                 all))
          total-votes (reduce + (map #(+ (or (:learning/useful-count %) 0)
                                         (or (:learning/not-useful-count %) 0))
                                     all))
          total-applications (reduce + (map #(or (:learning/applied-count %) 0) all))]
      {:total-learnings total
       :active-learnings (- total decayed)
       :decayed-learnings decayed
       :with-context with-context
       :without-context (- total with-context)
       :total-votes total-votes
       :total-applications total-applications})
    {:total-learnings 0 :active-learnings 0 :decayed-learnings 0
     :with-context 0 :without-context 0 :total-votes 0 :total-applications 0}))

;; -----------------------------------------------------------------------------
;; Learnings SCI Functions (DB-backed, for LLM to call during execution)
;; -----------------------------------------------------------------------------

(defn- make-store-learning-fn
  "Creates a function for the LLM to store meta-insights during execution."
  [db-info-atom]
  (fn store-learning
    ([insight] (store-learning insight nil))
    ([insight context]
     (when-let [db-info @db-info-atom]
       (db-store-learning! db-info insight context)))))

(defn- make-search-learnings-fn
  "Creates a function for the LLM to search learnings semantically.
   Returns learnings with :learning/id for voting."
  [db-info-atom]
  (fn search-learnings
    ([query] (search-learnings query 5))
    ([query top-k]
     (if-let [db-info @db-info-atom]
       (db-get-learnings db-info query {:top-k top-k})
       []))))

(defn- make-vote-learning-fn
  "Creates a function for the LLM to vote on learning usefulness.
   Call after task completion to rate whether learnings helped."
  [db-info-atom]
  (fn vote-learning
    [learning-id vote]
    (when-let [db-info @db-info-atom]
      (if (#{:useful :not-useful} vote)
        (db-vote-learning! db-info learning-id vote)
        {:error "Vote must be :useful or :not-useful"}))))

(defn- make-learning-stats-fn
  "Creates a function for the LLM to get learning statistics."
  [db-info-atom]
  (fn learning-stats []
    (if-let [db-info @db-info-atom]
      (db-learning-stats db-info)
      {:total-learnings 0 :active-learnings 0 :decayed-learnings 0
       :with-context 0 :without-context 0
       :total-votes 0 :total-applications 0})))

;; =============================================================================
;; PageIndex Document Storage System
;; =============================================================================

;; -----------------------------------------------------------------------------
;; Document Storage
;; -----------------------------------------------------------------------------

(defn- db-store-document!
  "Stores a PageIndex document (metadata only, not pages/toc).

   Params:
   `db-info` - Map with :conn key.
   `doc` - PageIndex document map.
   `doc-id` - String. Generated document ID.

   Returns:
   The stored document entity."
  [{:keys [conn]} doc doc-id]
  (when conn
    (let [entity (cond-> {:document/id doc-id
                          :document/name (:document/name doc)
                          :document/extension (:document/extension doc)}
                   (:document/title doc) (assoc :document/title (:document/title doc))
                   (:document/abstract doc) (assoc :document/abstract (:document/abstract doc))
                   (:document/author doc) (assoc :document/author (:document/author doc))
                   (:document/created-at doc) (assoc :document/created-at (:document/created-at doc))
                   (:document/updated-at doc) (assoc :document/updated-at (:document/updated-at doc)))]
      (d/transact! conn [entity])
      entity)))

(defn- get-document-toc
  "Gets TOC entries for a document, formatted as a readable list."
  [{:keys [conn]} doc-id]
  (when conn
    (->> (d/q '[:find [(pull ?e [:document.toc/title :document.toc/level :document.toc/target-page]) ...]
               :in $ ?doc-id
               :where [?e :document.toc/document-id ?doc-id]]
             (d/db conn) doc-id)
         (map (fn [e]
                {:title (:document.toc/title e)
                 :level (:document.toc/level e)
                 :page (:document.toc/target-page e)}))
         (sort-by (juxt :level :page))
         vec)))

(defn- db-get-document
  "Gets a document by ID with abstract and TOC.

   Params:
   `db-info` - Map with :conn key.
   `doc-id` - String. Document ID.

   Returns:
   Document map with :document/toc (formatted list) or nil."
  [{:keys [conn] :as db-info} doc-id]
  (when conn
    (let [doc (d/pull (d/db conn) '[*] [:document/id doc-id])]
      (when (:db/id doc)
        (-> (dissoc doc :db/id)
            (assoc :document/toc (get-document-toc db-info doc-id)))))))

(defn- db-list-documents
  "Lists all stored documents with abstracts and TOC summaries.

   Params:
   `db-info` - Map with :conn key.
   `opts` - Map, optional:
     - :limit - Integer. Max results (default: 100).
     - :include-toc? - Boolean. Include TOC (default: true).

   Returns:
   Vector of document maps with :document/toc."
   ([db-info] (db-list-documents db-info {}))
   ([{:keys [conn] :as db-info} {:keys [limit include-toc?] :or {limit 100 include-toc? true}}]
    (when conn
      (let [docs (d/q '[:find [(pull ?e [:document/id :document/name :document/title
                                         :document/extension :document/abstract]) ...]
                        :where [?e :document/id _]]
                      (d/db conn))]
        (->> docs
             (map (fn [doc]
                    (cond-> (select-keys doc [:document/id :document/name :document/title :document/extension])
                      (and (:document/abstract doc) (not= "" (:document/abstract doc)))
                      (assoc :document/abstract (:document/abstract doc))
                      include-toc? (assoc :document/toc (get-document-toc db-info (:document/id doc))))))
             (take limit)
             vec)))))

;; -----------------------------------------------------------------------------
;; Page Storage
;; -----------------------------------------------------------------------------

(defn- db-store-page!
  "Stores a page (internal - called by db-store-pageindex-document!)."
  [{:keys [conn]} page doc-id]
  (when conn
    (let [page-id (str doc-id "-page-" (:page/index page))
          page-data {:page/id page-id
                     :page/document-id doc-id
                     :page/index (:page/index page)}]
      (d/transact! conn [page-data])
      page-id)))

(defn- db-get-page
  "Gets a page by ID.

   Params:
   `db-info` - Map with :conn key.
   `page-id` - String. Page ID.

   Returns:
   Page map or nil."
  [{:keys [conn]} page-id]
  (when conn
    (let [e (d/pull (d/db conn) '[*] [:page/id page-id])]
      (when (:db/id e) (dissoc e :db/id)))))

(defn- db-list-pages
  "Lists pages for a document.

   Params:
   `db-info` - Map with :conn key.
   `doc-id` - String. Document ID.

   Returns:
   Vector of page maps sorted by index."
  [{:keys [conn]} doc-id]
  (when conn
    (->> (d/q '[:find [(pull ?e [:page/id :page/index :page/document-id]) ...]
               :in $ ?doc-id
               :where [?e :page/document-id ?doc-id]]
             (d/db conn) doc-id)
         (sort-by :page/index)
         vec)))

;; -----------------------------------------------------------------------------
;; Page Node Storage & Search
;; -----------------------------------------------------------------------------

(defn- db-store-page-node!
  "Stores a page node (internal - called by db-store-pageindex-document!)."
  [{:keys [conn]} node page-id doc-id]
  (when conn
    (let [node-id (str page-id "-node-" (or (:page.node/id node) (UUID/randomUUID)))
          visual-node? (#{:image :table} (:page.node/type node))
          img-bytes (:page.node/image-data node)
          image-too-large? (and visual-node?
                                img-bytes
                                (> (alength ^bytes img-bytes) 5242880))
          image-data (when (and visual-node?
                                img-bytes
                                (not image-too-large?))
                       img-bytes)
          entity (cond-> {:page.node/id node-id
                          :page.node/page-id page-id
                          :page.node/document-id doc-id
                          :page.node/type (:page.node/type node)}
                   (:page.node/id node) (assoc :page.node/local-id (:page.node/id node))
                   (:page.node/parent-id node) (assoc :page.node/parent-id (:page.node/parent-id node))
                   (:page.node/level node) (assoc :page.node/level (:page.node/level node))
                   (and (not visual-node?) (:page.node/content node))
                   (assoc :page.node/content (:page.node/content node))
                   image-data (assoc :page.node/image-data image-data)
                   (:page.node/description node) (assoc :page.node/description (:page.node/description node))
                   (some? (:page.node/continuation? node)) (assoc :page.node/continuation? (:page.node/continuation? node))
                   (:page.node/caption node) (assoc :page.node/caption (:page.node/caption node))
                   (:page.node/kind node) (assoc :page.node/kind (:page.node/kind node))
                   (:page.node/bbox node) (assoc :page.node/bbox (pr-str (:page.node/bbox node)))
                   (:page.node/group-id node) (assoc :page.node/group-id (:page.node/group-id node)))]
      (when image-too-large?
        (trove/log! {:level :warn
                     :data {:page-node-id node-id
                            :bytes-size (alength ^bytes img-bytes)}
                     :msg "Skipping page node image-data (exceeds 5MB limit)"}))
      (d/transact! conn [entity])
      node-id)))

(defn- fulltext-page-nodes
  "Search page nodes via Datalevin fulltext index."
  [conn query]
  (d/q '[:find [(pull ?e [:page.node/id :page.node/page-id :page.node/document-id
                           :page.node/type :page.node/level :page.node/local-id
                           :page.node/content :page.node/description]) ...]
         :in $ ?q
         :where [(fulltext $ ?q) [[?e]]]]
       (d/db conn) query))

(defn- scan-page-nodes
  "Search page nodes via in-memory substring scan (fallback)."
  [conn query]
  (let [q (str-lower query)
        all (d/q '[:find [(pull ?e [:page.node/id :page.node/page-id :page.node/document-id
                                     :page.node/type :page.node/level :page.node/local-id
                                     :page.node/content :page.node/description]) ...]
                   :where [?e :page.node/id _]]
                 (d/db conn))]
    (filter (fn [n]
              (or (str-includes? (str-lower (str (:page.node/content n))) q)
                  (str-includes? (str-lower (str (:page.node/description n))) q)))
            all)))

(defn- search-page-nodes-raw
  "Search page nodes with fulltext, falling back to scan."
  [conn query]
  (try (fulltext-page-nodes conn query)
       (catch Exception _ (scan-page-nodes conn query))))

(defn- db-search-page-nodes
  "Searches page nodes by text content, optionally filtered by document and type.
   
   Params:
   `db-info` - Map with :store key.
   `query` - String. Case-insensitive text search over content and description.
             When nil/blank, falls back to list mode.
   `opts` - Map, optional:
     - :top-k - Integer. Max results (default: 10).
     - :document-id - String. Filter by document.
     - :type - Keyword. Filter by node type (:paragraph, :heading, etc.).
   
   Returns:
   Vector of page node maps with content included."
  ([db-info query] (db-search-page-nodes db-info query {}))
  ([{:keys [conn] :as db-info} query {:keys [top-k document-id type] :or {top-k 10}}]
   (if (str/blank? (str query))
     (db-list-page-nodes db-info {:document-id document-id :type type :limit top-k})
     (when conn
       (->> (search-page-nodes-raw conn query)
            (filter #(or (nil? document-id) (= document-id (:page.node/document-id %))))
            (filter #(or (nil? type) (= type (:page.node/type %))))
            (take top-k)
            vec)))))

(defn- db-get-page-node
  "Gets a page node by ID with full details.

   Params:
   `db-info` - Map with :conn key.
   `node-id` - String. Page node ID.

   Returns:
   Page node map or nil."
  [{:keys [conn]} node-id]
  (when conn
    (let [e (d/pull (d/db conn) '[*] [:page.node/id node-id])]
      (when (:db/id e) (dissoc e :db/id)))))

(defn- db-list-page-nodes
  "Lists page nodes, optionally filtered.

   Params:
   `db-info` - Map with :conn key.
   `opts` - Map, optional:
     - :page-id - String. Filter by page.
     - :document-id - String. Filter by document.
     - :type - Keyword. Filter by node type.
     - :limit - Integer. Max results (default: 100).

   Returns:
   Vector of page node maps."
  ([db-info] (db-list-page-nodes db-info {}))
  ([{:keys [conn]} {:keys [page-id document-id type limit] :or {limit 100}}]
   (when conn
     (let [all (d/q '[:find [(pull ?e [:page.node/id :page.node/page-id :page.node/document-id
                                        :page.node/type :page.node/level :page.node/local-id
                                        :page.node/content :page.node/description]) ...]
                     :where [?e :page.node/id _]]
                    (d/db conn))]
       (->> all
            (filter #(or (nil? page-id) (= page-id (:page.node/page-id %))))
            (filter #(or (nil? document-id) (= document-id (:page.node/document-id %))))
            (filter #(or (nil? type) (= type (:page.node/type %))))
            (take limit)
            (mapv (fn [n]
                    {:page.node/id (:page.node/id n)
                     :page.node/page-id (:page.node/page-id n)
                     :page.node/document-id (:page.node/document-id n)
                     :page.node/type (:page.node/type n)
                     :page.node/level (:page.node/level n)
                     :page.node/local-id (:page.node/local-id n)
                     :page.node/content (str-truncate (:page.node/content n) 200)
                     :page.node/description (str-truncate (:page.node/description n) 200)})))))))

;; -----------------------------------------------------------------------------
;; TOC Entry Storage
;; -----------------------------------------------------------------------------

(defn- db-store-toc-entry!
  "Stores a PageIndex TOC entry exactly as-is.

   Params:
   `db-info` - Map with :conn key.
   `entry` - Map with PageIndex TOC entry fields (document.toc/* namespace).
   `doc-id` - String, optional. Parent document ID (defaults to \"standalone\").

   Returns:
   The stored entry."
  ([db-info entry] (db-store-toc-entry! db-info entry "standalone"))
  ([{:keys [conn]} entry doc-id]
   (when conn
     (let [timestamp (java.util.Date.)
           entry-data (cond-> (assoc entry
                                     :document.toc/document-id doc-id
                                     :document.toc/created-at timestamp)
                        (not (:document.toc/id entry))
                        (assoc :document.toc/id (str (UUID/randomUUID))))]
       (d/transact! conn [entry-data])
       entry-data))))

(defn- fulltext-toc-entries
  "Search TOC entries via Datalevin fulltext index."
  [conn query]
  (d/q '[:find [(pull ?e [:document.toc/id :document.toc/title :document.toc/level
                           :document.toc/description :document.toc/target-page]) ...]
         :in $ ?q
         :where [(fulltext $ ?q) [[?e]]]]
       (d/db conn) query))

(defn- scan-toc-entries
  "Search TOC entries via in-memory substring scan (fallback)."
  [conn query]
  (let [q (str-lower query)
        all (d/q '[:find [(pull ?e [:document.toc/id :document.toc/title :document.toc/level
                                     :document.toc/description :document.toc/target-page]) ...]
                   :where [?e :document.toc/id _]]
                 (d/db conn))]
    (filter (fn [e]
              (or (str-includes? (str-lower (str (:document.toc/title e))) q)
                  (str-includes? (str-lower (str (:document.toc/description e))) q)))
            all)))

(defn- search-toc-entries-raw
  "Search TOC entries with fulltext, falling back to scan."
  [conn query]
  (try (fulltext-toc-entries conn query)
       (catch Exception _ (scan-toc-entries conn query))))

(defn- normalize-toc-entry
  "Normalize a raw TOC entry into a clean result map."
  [e]
  {:document.toc/id (:document.toc/id e)
   :document.toc/title (:document.toc/title e)
   :document.toc/level (:document.toc/level e)
   :document.toc/description (when-not (= "" (str (:document.toc/description e)))
                               (:document.toc/description e))
   :document.toc/target-page (:document.toc/target-page e)})

(defn- db-search-toc-entries
  "Searches TOC entries by title and description text.

   Params:
   `db-info` - Map with :conn key.
   `query` - String. Case-insensitive text search over title and description.
   `opts` - Map, optional:
     - :top-k - Integer. Max results (default: 10).

   Returns:
   Vector of TOC entry maps with :document.toc/* fields."
  ([db-info query] (db-search-toc-entries db-info query {}))
  ([{:keys [conn] :as db-info} query {:keys [top-k] :or {top-k 10}}]
   (if (str/blank? (str query))
     (db-list-toc-entries db-info {:limit top-k})
     (when conn
       (->> (search-toc-entries-raw conn query)
            (take top-k)
            (mapv normalize-toc-entry))))))

(defn- db-get-toc-entry
  "Gets a TOC entry by ID with full details.

   Params:
   `db-info` - Map with :conn key.
   `entry-id` - String. The TOC entry ID.

   Returns:
   TOC entry map with all fields, or nil if not found."
  [{:keys [conn]} entry-id]
  (when conn
    (let [e (d/pull (d/db conn) '[*] [:document.toc/id entry-id])]
      (when (:db/id e) (dissoc e :db/id)))))

(defn- db-list-toc-entries
  "Lists all TOC entries, optionally filtered.

   Params:
   `db-info` - Map with :conn key.
   `opts` - Map, optional:
     - :parent-id - String. Filter by parent.
     - :limit - Integer. Max results (default: 100).

   Returns:
   Vector of TOC entry maps."
  ([db-info] (db-list-toc-entries db-info {}))
  ([{:keys [conn]} {:keys [parent-id limit] :or {limit 100}}]
   (when conn
     (let [all (d/q '[:find [(pull ?e [:document.toc/id :document.toc/title :document.toc/level
                                        :document.toc/description :document.toc/target-page
                                        :document.toc/parent-id]) ...]
                     :where [?e :document.toc/id _]]
                    (d/db conn))]
       (->> all
            (filter #(or (nil? parent-id) (= parent-id (:document.toc/parent-id %))))
            (map (fn [e]
                   {:document.toc/id (:document.toc/id e)
                    :document.toc/title (:document.toc/title e)
                    :document.toc/level (:document.toc/level e)
                    :document.toc/description (when-not (= "" (str (:document.toc/description e)))
                                                (:document.toc/description e))
                    :document.toc/target-page (:document.toc/target-page e)}))
            (sort-by :document.toc/level)
            (take limit)
            vec)))))

;; -----------------------------------------------------------------------------
;; Entity/Relationship Query Functions
;; -----------------------------------------------------------------------------

(defn- db-search-entities
  "Searches entities by name and description text, optionally filtered by type and document.

   Params:
   `db-info` - Map with :conn key.
   `query` - String. Case-insensitive text search.
   `opts` - Map, optional:
     - :top-k - Integer. Max results (default: 10).
     - :type - Keyword. Filter by entity type.
     - :document-id - String. Filter by document.

   Returns:
   Vector of entity maps."
  ([db-info query] (db-search-entities db-info query {}))
  ([{:keys [conn] :as db-info} query {:keys [top-k type document-id] :or {top-k 10}}]
   (if (str/blank? (str query))
     (db-list-entities db-info {:type type :document-id document-id :limit top-k})
     (when conn
       (let [q (str-lower query)
             all (d/q '[:find [(pull ?e [:entity/id :entity/name :entity/type :entity/description
                                         :entity/document-id :entity/page :entity/section]) ...]
                       :where [?e :entity/id _]]
                      (d/db conn))]
         (->> all
              (filter (fn [e]
                        (or (str-includes? (str-lower (str (:entity/name e))) q)
                            (str-includes? (str-lower (str (:entity/description e))) q))))
              (filter #(or (nil? type) (= type (:entity/type %))))
              (filter #(or (nil? document-id) (= document-id (:entity/document-id %))))
              (take top-k)
              (mapv (fn [e]
                      {:entity/id (:entity/id e)
                       :entity/name (:entity/name e)
                       :entity/type (:entity/type e)
                       :entity/description (when-not (= "" (str (:entity/description e))) (:entity/description e))
                       :entity/document-id (:entity/document-id e)
                       :entity/page (:entity/page e)
                       :entity/section (when-not (= "" (str (:entity/section e))) (:entity/section e))}))))))))

(defn- db-get-entity
  "Gets an entity by UUID.

   Params:
   `db-info` - Map with :conn key.
   `entity-id` - UUID. Entity ID.

   Returns:
   Entity map or nil."
  [{:keys [conn]} entity-id]
  (when conn
    (let [e (d/pull (d/db conn) '[*] [:entity/id entity-id])]
      (when (:db/id e) (dissoc e :db/id)))))

(defn- db-list-entities
  "Lists entities, optionally filtered.

   Params:
   `db-info` - Map with :conn key.
   `opts` - Map, optional:
     - :type - Keyword. Filter by entity type.
     - :document-id - String. Filter by document.
     - :limit - Integer. Max results (default: 100).

   Returns:
   Vector of entity maps."
  ([db-info] (db-list-entities db-info {}))
  ([{:keys [conn]} {:keys [type document-id limit] :or {limit 100}}]
   (when conn
     (let [all (d/q '[:find [(pull ?e [:entity/id :entity/name :entity/type :entity/description
                                        :entity/document-id :entity/page :entity/section]) ...]
                     :where [?e :entity/id _]]
                    (d/db conn))]
       (->> all
            (filter #(or (nil? type) (= type (:entity/type %))))
            (filter #(or (nil? document-id) (= document-id (:entity/document-id %))))
            (sort-by :entity/name)
            (take limit)
            (mapv (fn [e]
                    {:entity/id (:entity/id e)
                     :entity/name (:entity/name e)
                     :entity/type (:entity/type e)
                     :entity/description (when-not (= "" (str (:entity/description e))) (:entity/description e))
                     :entity/document-id (:entity/document-id e)
                     :entity/page (:entity/page e)
                     :entity/section (when-not (= "" (str (:entity/section e))) (:entity/section e))}))
            vec)))))

(defn- db-list-relationships
  "Lists relationships for an entity (as source or target).

   Params:
   `db-info` - Map with :conn key.
   `entity-id` - UUID. Entity ID.
   `opts` - Map, optional:
     - :type - Keyword. Filter by relationship type.

   Returns:
   Vector of relationship maps."
  ([db-info entity-id] (db-list-relationships db-info entity-id {}))
  ([{:keys [conn]} entity-id {:keys [type]}]
   (when conn
     (let [all (d/q '[:find [(pull ?e [:relationship/id :relationship/type
                                        :relationship/source-entity-id :relationship/target-entity-id
                                        :relationship/description]) ...]
                     :in $ ?eid
                     :where (or [?e :relationship/source-entity-id ?eid]
                                [?e :relationship/target-entity-id ?eid])]
                    (d/db conn) entity-id)]
       (->> all
            (filter #(or (nil? type) (= type (:relationship/type %))))
            (mapv (fn [r]
                    {:relationship/id (:relationship/id r)
                     :relationship/type (:relationship/type r)
                     :relationship/source-entity-id (:relationship/source-entity-id r)
                     :relationship/target-entity-id (:relationship/target-entity-id r)
                     :relationship/description (when-not (= "" (str (:relationship/description r)))
                                                (:relationship/description r))})))))))

(defn- db-entity-stats
  "Gets entity and relationship statistics.

   Params:
   `db-info` - Map with :conn key.

   Returns:
   Map with :total-entities, :types (map of type->count), :total-relationships."
  [{:keys [conn]}]
  (if conn
    (let [entities (d/q '[:find [(pull ?e [:entity/type]) ...]
                          :where [?e :entity/id _]]
                        (d/db conn))
          types-map (frequencies (map :entity/type entities))
          rel-count (count (d/q '[:find ?e
                                  :where [?e :relationship/id _]]
                                (d/db conn)))]
      {:total-entities (count entities)
       :types types-map
       :total-relationships rel-count})
    {:total-entities 0 :types {} :total-relationships 0}))

;; -----------------------------------------------------------------------------
;; High-Level Document Storage
;; -----------------------------------------------------------------------------

(defn- db-store-pageindex-document!
  "Stores an entire PageIndex document with all its components.

   Params:
   `db-info` - Map with :conn key.
   `doc` - Complete PageIndex document (spec-validated).

   Returns:
   Map with :document-id and counts of stored entities."
  [db-info doc]
  (let [doc-id (str (UUID/randomUUID))
        ;; Store raw document for persistence
        _ (d/transact! (:conn db-info) [{:raw-document/id doc-id
                                         :raw-document/content (pr-str doc)}])
        ;; Store document metadata
        _ (db-store-document! db-info doc doc-id)
        ;; Store pages and their nodes
        page-count (atom 0)
        node-count (atom 0)
        _ (doseq [page (:document/pages doc)]
            (let [page-id (db-store-page! db-info page doc-id)]
              (swap! page-count inc)
              (doseq [node (:page/nodes page)]
                (db-store-page-node! db-info node page-id doc-id)
                (swap! node-count inc))))
        ;; Store TOC entries
        toc-count (atom 0)
        _ (doseq [entry (:document/toc doc)]
            (db-store-toc-entry! db-info entry doc-id)
            (swap! toc-count inc))]
    {:document-id doc-id
     :pages-stored @page-count
     :nodes-stored @node-count
     :toc-entries-stored @toc-count}))

;; -----------------------------------------------------------------------------
;; TOC Entry SCI Functions (for LLM to call during execution)
;; -----------------------------------------------------------------------------

(defn- make-store-toc-entry-fn
  "Creates a function for the LLM to store PageIndex TOC entries."
  [db-info-atom]
  (fn store-toc-entry!
    ([entry] (store-toc-entry! entry nil))
    ([entry doc-id]
     (when-let [db-info @db-info-atom]
       (db-store-toc-entry! db-info entry (or doc-id "manual"))))))

(defn- make-search-toc-entries-fn
  "Creates a function for the LLM to search TOC entries semantically."
  [db-info-atom]
  (fn search-toc-entries
    ([query] (search-toc-entries query 10))
    ([query top-k]
     (if-let [db-info @db-info-atom]
       (db-search-toc-entries db-info query {:top-k top-k})
       []))))

(defn- make-get-toc-entry-fn
  "Creates a function for the LLM to get a TOC entry by ID."
  [db-info-atom]
  (fn get-toc-entry [entry-id]
    (when-let [db-info @db-info-atom]
      (db-get-toc-entry db-info entry-id))))

(defn- make-list-toc-entries-fn
  "Creates a function for the LLM to list TOC entries."
  [db-info-atom]
  (fn list-toc-entries
    ([] (list-toc-entries {}))
    ([opts]
     (if-let [db-info @db-info-atom]
       (db-list-toc-entries db-info opts)
       []))))

;; -----------------------------------------------------------------------------
;; Page Node SCI Functions (for LLM to search actual content)
;; -----------------------------------------------------------------------------

(defn- make-search-page-nodes-fn
  "Creates a function for the LLM to search page nodes (actual content) semantically."
  [db-info-atom]
  (fn search-page-nodes
    ([query] (search-page-nodes query 10))
    ([query top-k]
     (if-let [db-info @db-info-atom]
       (db-search-page-nodes db-info query {:top-k top-k})
       []))
    ([query top-k opts]
     (if-let [db-info @db-info-atom]
       (db-search-page-nodes db-info query (merge {:top-k top-k} opts))
       []))))

(defn- make-get-page-node-fn
  "Creates a function for the LLM to get a page node by ID."
  [db-info-atom]
  (fn get-page-node [node-id]
    (when-let [db-info @db-info-atom]
      (db-get-page-node db-info node-id))))

(defn- make-list-page-nodes-fn
  "Creates a function for the LLM to list page nodes."
  [db-info-atom]
  (fn list-page-nodes
    ([] (list-page-nodes {}))
    ([opts]
     (if-let [db-info @db-info-atom]
       (db-list-page-nodes db-info opts)
       []))))

(defn- make-list-documents-fn
  "Creates a function for the LLM to list stored documents with abstracts and TOC."
  [db-info-atom]
  (fn list-documents
    ([] (list-documents {}))
    ([opts]
     (if-let [db-info @db-info-atom]
       (db-list-documents db-info opts)
       []))))

(defn- make-get-document-fn
  "Creates a function for the LLM to get a document by ID."
  [db-info-atom]
  (fn get-document [doc-id]
    (when-let [db-info @db-info-atom]
      (db-get-document db-info doc-id))))

;; -----------------------------------------------------------------------------
;; Entity SCI Functions (for LLM to search/get extracted entities)
;; -----------------------------------------------------------------------------

(defn- make-search-entities-fn
  "Creates a function for the LLM to search entities semantically."
  [db-info-atom]
  (fn search-entities
    ([query] (search-entities query 10))
    ([query top-k] (search-entities query top-k {}))
    ([query top-k opts]
     (if-let [db-info @db-info-atom]
       (db-search-entities db-info query (merge {:top-k top-k} opts))
       []))))

(defn- make-get-entity-fn
  "Creates a function for the LLM to get an entity by ID."
  [db-info-atom]
  (fn get-entity [entity-id]
    (when-let [db-info @db-info-atom]
      (db-get-entity db-info entity-id))))

(defn- make-list-entities-fn
  "Creates a function for the LLM to list entities with optional filters."
  [db-info-atom]
  (fn list-entities
    ([] (list-entities {}))
    ([opts]
     (if-let [db-info @db-info-atom]
       (db-list-entities db-info opts)
       []))))

(defn- make-list-relationships-fn
  "Creates a function for the LLM to list relationships for an entity."
  [db-info-atom]
  (fn list-relationships
    ([entity-id] (list-relationships entity-id {}))
    ([entity-id opts]
     (if-let [db-info @db-info-atom]
       (db-list-relationships db-info entity-id opts)
       []))))

(defn- make-entity-stats-fn
  "Creates a function for the LLM to get entity/relationship statistics."
  [db-info-atom]
  (fn entity-stats []
    (if-let [db-info @db-info-atom]
      (db-entity-stats db-info)
      {:total-entities 0 :types {} :total-relationships 0})))

(defn- make-cite-fn
  "Creates CITE function for the LLM to cite claims with sources."
  [claims-atom]
  (fn CITE
    ([claim-text document-id page section quote]
     (CITE claim-text document-id page section quote 1.0))
    ([claim-text document-id page section quote confidence]
     (let [claim {:claim/id (UUID/randomUUID)
                  :claim/text claim-text
                  :claim/document-id document-id
                  :claim/page (long (if (string? page) (Long/parseLong page) page))
                  :claim/section section
                  :claim/quote quote
                  :claim/confidence (float (if (string? confidence) (Double/parseDouble confidence) confidence))
                  :claim/created-at (java.util.Date.)}]
       (swap! claims-atom conj claim)
       {:cited true :claim-id (:claim/id claim) :claim-text claim-text}))))

(defn- make-cite-unverified-fn
  "Creates CITE-UNVERIFIED function for claims without source verification."
  [claims-atom]
  (fn CITE-UNVERIFIED
    [claim-text]
    (let [claim {:claim/id (UUID/randomUUID)
                 :claim/text claim-text
                 :claim/document-id nil
                 :claim/page nil
                 :claim/section nil
                 :claim/quote nil
                 :claim/confidence 0.5
                 :claim/verified? false
                 :claim/created-at (java.util.Date.)}]
      (swap! claims-atom conj claim)
      {:cited true :verified? false :claim-id (:claim/id claim) :claim-text claim-text})))

(defn- make-list-claims-fn
  "Creates list-claims function to retrieve accumulated claims."
  [claims-atom]
  (fn list-claims
    []
    (vec @claims-atom)))

;; =============================================================================
;; History Query Functions (for LLM to call)
;; =============================================================================

(defn- make-search-history-fn
  "Creates a function for the LLM to get recent conversation history.
   Takes a number of messages to return (default 5)."
  [db-info-atom]
  (fn search-history
    ([] (search-history 5))
    ([n]
     (if-let [db-info @db-info-atom]
       (let [results (get-recent-messages db-info (or n 5))]
         (mapv (fn [{:keys [content role tokens]}]
                 {:role (name role) :content content :tokens tokens})
               results))
       []))))

(defn- make-get-history-fn
  "Creates a function for the LLM to get recent conversation history."
  [db-info-atom]
  (fn get-history
    ([] (get-history 10))
    ([n]
     (if-let [db-info @db-info-atom]
       (let [results (get-recent-messages db-info n)]
         (mapv (fn [{:keys [content role tokens]}]
                 {:role (name role) :content content :tokens tokens})
               results))
       []))))

(defn- make-history-stats-fn
  "Creates a function for the LLM to get history statistics."
  [db-info-atom]
  (fn history-stats []
    (if-let [db-info @db-info-atom]
      (let [total-tokens (count-history-tokens db-info)
            recent (get-recent-messages db-info 100)]
        {:total-messages (count recent)
         :total-tokens total-tokens
         :by-role (frequencies (map :role recent))})
      {:total-messages 0 :total-tokens 0 :by-role {}})))

;; =============================================================================
;; SCI Context Creation
;; =============================================================================

(defn- create-sci-context
  "Creates the SCI sandbox context with all available bindings.
   
   Params:
   `context-data` - The data context to analyze
   `llm-query-fn` - Function for simple LLM text queries
   `rlm-query-fn` - Function for sub-RLM queries (shares DB) - can be nil
   `locals-atom` - Atom for storing local variables
   `db-info-atom` - Atom with database info (can be nil)
   `custom-bindings` - Map of symbol->value for custom bindings (can be nil)"
  [context-data llm-query-fn rlm-query-fn locals-atom db-info-atom custom-bindings]
  (let [base-bindings {'context context-data
                       'llm-query llm-query-fn
                       'FINAL (fn [answer] (let [v (realize-value answer)]
                                             {:rlm/final true :rlm/answer {:result v :type (type v)}}))

                       ;; Locals inspection tools - LLM can check its own state on demand
                       'list-locals (fn []
                                      (into {}
                                            (map (fn [[k v]]
                                                   [k (cond
                                                        (fn? v) '<fn>
                                                        (and (coll? v) (> (count v) 10))
                                                        (str "<" (type v) " with " (count v) " items>")
                                                        :else v)])
                                                 @locals-atom)))
                       'get-local (fn [var-name] (get @locals-atom var-name))
                       'spec spec/spec
                       'field spec/field
                       'str-join str-join 'str-split str-split 'str-replace str-replace
                       'str-trim str-trim 'str-lower str-lower 'str-upper str-upper
                       'str-blank? str-blank? 'str-includes? str-includes?
                       'str-starts-with? str-starts-with? 'str-ends-with? str-ends-with?
                       'str-lines str-lines 'str-words str-words 'str-truncate str-truncate
                        ;; Date helper functions
                       'parse-date parse-date 'date-before? date-before? 'date-after? date-after?
                       'days-between days-between 'date-plus-days date-plus-days
                       'date-minus-days date-minus-days 'date-format date-format 'today-str today-str}
        ;; Add rlm-query if available (for sub-RLM queries sharing the same DB)
        rlm-bindings (when rlm-query-fn
                       {'rlm-query rlm-query-fn})
        db-bindings (when db-info-atom
                      {;; History query functions - let LLM access its own conversation
                       'search-history (make-search-history-fn db-info-atom)
                       'get-history (make-get-history-fn db-info-atom)
                       'history-stats (make-history-stats-fn db-info-atom)
                       ;; Document functions - list/get stored documents
                       'list-documents (make-list-documents-fn db-info-atom)
                       'get-document (make-get-document-fn db-info-atom)
                       ;; Page node functions - search actual content
                       'search-page-nodes (make-search-page-nodes-fn db-info-atom)
                       'get-page-node (make-get-page-node-fn db-info-atom)
                       'list-page-nodes (make-list-page-nodes-fn db-info-atom)
                       ;; TOC entry functions - table of contents (exact structure)
                       'store-toc-entry! (make-store-toc-entry-fn db-info-atom)
                       'search-toc-entries (make-search-toc-entries-fn db-info-atom)
                       'get-toc-entry (make-get-toc-entry-fn db-info-atom)
                       'list-toc-entries (make-list-toc-entries-fn db-info-atom)
                       ;; Entity functions - search/get extracted entities
                       'search-entities (make-search-entities-fn db-info-atom)
                       'get-entity (make-get-entity-fn db-info-atom)
                       'list-entities (make-list-entities-fn db-info-atom)
                       'list-relationships (make-list-relationships-fn db-info-atom)
                       'entity-stats (make-entity-stats-fn db-info-atom)})
        ;; Example learning functions - always available (uses global example-store)
        example-bindings {'search-examples (make-search-examples-fn)
                          'get-recent-examples (make-get-recent-examples-fn)
                          'example-stats (make-example-stats-fn)}
        ;; Learnings functions - now DB-backed with voting (pass db-info-atom)
        learning-bindings (if db-info-atom
                            {'store-learning (make-store-learning-fn db-info-atom)
                             'search-learnings (make-search-learnings-fn db-info-atom)
                             'vote-learning (make-vote-learning-fn db-info-atom)
                             'learning-stats (make-learning-stats-fn db-info-atom)}
                            {})
        ;; Raw text access (RLM paper §3: symbolic handle to P)
        ;; Uses atoms so P auto-refreshes when docs are ingested mid-query
        raw-text-bindings (when db-info-atom
                            (let [conn (:conn @db-info-atom)
                                  p-atom (atom (build-raw-text conn))
                                  pages-atom (atom (build-page-texts conn))
                                  refresh! (fn []
                                             (let [c (:conn @db-info-atom)]
                                               (reset! p-atom (build-raw-text c))
                                               (reset! pages-atom (build-page-texts c))
                                               (count @p-atom)))]
                              {'P @p-atom
                               'P-len (count @p-atom)
                               'get-page (fn [n] (get @pages-atom n))
                               'page-count (fn [] (count @pages-atom))
                               'refresh-P! refresh!}))
        all-bindings (merge SAFE_BINDINGS base-bindings rlm-bindings db-bindings
                            example-bindings learning-bindings raw-text-bindings
                            (or custom-bindings {}))]
    (sci/init {:namespaces {'user all-bindings}
               :classes {'java.util.regex.Pattern java.util.regex.Pattern
                         'java.util.regex.Matcher java.util.regex.Matcher
                         'java.time.LocalDate java.time.LocalDate
                         'java.time.Period java.time.Period
                         'java.util.UUID java.util.UUID}
               :deny '[require import ns eval load-string read-string]})))


(defn- create-rlm-env
  "Creates an RLM execution environment (internal use only).
   
   Params:
   `context-data` - The data context to analyze (can be nil).
   `model` - LLM model name.
   `depth-atom` - Atom tracking recursion depth.
   `api-key` - API key for LLM.
   `base-url` - Base URL for LLM API.
   `opts` - Map, optional:
     - :db - External db connection, false to disable, or nil for auto-create.
     - :db-opts - Options for auto-created db (:schema).
      - :path - Path for persistent DB (history survives across sessions).
     - :documents - Vector of PageIndex documents to preload (stored exactly as-is).
   
   Returns:
   Map with :sci-ctx, :context, :llm-query-fn, :rlm-query-fn, :locals-atom, 
   :db-info-atom, :history-enabled?
   
    Note: History tracking is ALWAYS enabled when a store is available.
    The same in-memory store is used for conversation history, documents, and learnings."
  ([context-data model depth-atom api-key base-url]
   (create-rlm-env context-data model depth-atom api-key base-url {}))
   ([context-data model depth-atom api-key base-url {:keys [db db-opts path documents config router]}]
   (let [locals-atom (atom {})
          db-info (cond
                    (false? db) nil
                    (and (map? db) (:conn db)) (assoc db :owned? false)
                    :else (create-rlm-conn path))
         db-info-atom (when db-info (atom db-info))
         ;; Preload documents if provided (stores complete structure)
         _ (when (and db-info-atom (seq documents))
             (doseq [doc documents]
               (db-store-pageindex-document! @db-info-atom doc)))
         ;; Create query functions
         llm-query-fn (if router
                        (make-routed-llm-query-fn :root depth-atom router)
                        (make-llm-query-fn model depth-atom api-key base-url))
         rlm-query-fn (when db-info-atom
                        (if router
                          (make-rlm-query-fn :root depth-atom router db-info-atom)
                          (make-rlm-query-fn model depth-atom nil db-info-atom)))]
     (cond-> {:sci-ctx (create-sci-context context-data llm-query-fn rlm-query-fn locals-atom db-info-atom nil)
              :context context-data
              :llm-query-fn llm-query-fn
              :rlm-query-fn rlm-query-fn
              :locals-atom locals-atom
              :db-info-atom db-info-atom
              :history-enabled? (boolean db-info-atom)}
       router (assoc :router router)))))

(defn- dispose-rlm-env! [{:keys [db-info-atom]}]
  (when db-info-atom (dispose-rlm-conn! @db-info-atom)))

(defn- get-locals [rlm-env] @(:locals-atom rlm-env))

;; =============================================================================
;; Code Execution
;; =============================================================================

(defn- execute-code [{:keys [sci-ctx locals-atom]} code]
  (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-phase :execute-code})]
    (let [_ (rlm-debug! {:code-preview (str-truncate code 200)} "Executing code")
          start-time (System/currentTimeMillis)
          vars-before (try (sci/eval-string* sci-ctx "(ns-interns 'user)") (catch Exception _ {}))
          exec-ch (async/thread
                    (try
                      (let [stdout-writer (java.io.StringWriter.)
                            result (binding [*out* stdout-writer] (sci/eval-string* sci-ctx code))]
                        {:result result :stdout (str stdout-writer) :error nil})
                      (catch Exception e {:result nil :stdout "" :error (ex-message e)})))
          [execution-result _] (async/alts!! [exec-ch (async/timeout EVAL_TIMEOUT_MS)])
          execution-time (- (System/currentTimeMillis) start-time)
          timed-out? (nil? execution-result)]
      (if timed-out?
        (do (rlm-debug! {:execution-time-ms execution-time} "Code execution timed out")
            {:result nil :stdout "" :error (str "Timeout (" (/ EVAL_TIMEOUT_MS 1000) "s)")
             :execution-time-ms execution-time :timeout? true})
        (let [{:keys [result stdout error]} execution-result
              vars-after (try (sci/eval-string* sci-ctx "(ns-interns 'user)") (catch Exception _ vars-before))
              new-vars (apply dissoc vars-after (keys vars-before))]
          (when (seq new-vars)
            (swap! locals-atom merge (into {} (map (fn [[k v]] [k (deref v)]) new-vars))))
          (rlm-debug! {:execution-time-ms execution-time
                       :has-error? (some? error)
                       :error error
                       :result-preview (str-truncate (pr-str result) 200)
                       :stdout-preview (when-not (str/blank? stdout) (str-truncate stdout 200))
                       :new-vars (when (seq new-vars) (vec (keys new-vars)))} "Code execution complete")
          {:result result :stdout stdout :error error :execution-time-ms execution-time :timeout? false})))))

;; =============================================================================
;; FINAL Detection
;; =============================================================================

(defn- check-result-for-final [result-map]
  (let [result (:result result-map)]
    (if (and (map? result) (true? (:rlm/final result)))
      {:final? true :answer (:rlm/answer result)}
      {:final? false})))

(defn- answer-str
  "Extracts a string representation from an RLM answer.
   Answer is {:result value :type type} — returns the :result as a string."
  [answer]
  (let [v (:result answer answer)]
    (if (string? v) v (pr-str v))))

(defn- find-final-in-stdout [stdout]
  (when (and stdout (str/includes? stdout ":rlm/final"))
    (try
      (let [parsed (read-string stdout)]
        (when (and (map? parsed) (true? (:rlm/final parsed))) (:rlm/answer parsed)))
      (catch Exception _ nil))))

;; =============================================================================
;; Recursive LLM Query
;; =============================================================================

(defn- make-llm-query-fn
  "Creates the llm-query function for simple text queries (no code execution).
   Returns a map with :content, :reasoning (may be nil), :api-usage.
   Calls on-query callback (if provided) with {:prompt :response :reasoning}."
  [model depth-atom api-key base-url & [{:keys [on-query]}]]
  (fn llm-query
    ([prompt]
     (if (>= @depth-atom *max-recursion-depth*)
       {:content (str "Max recursion depth (" *max-recursion-depth* ") exceeded")}
       (try
         (swap! depth-atom inc)
         (let [result (llm/chat-completion [{:role "user" :content prompt}] model api-key base-url)]
           (when on-query
             (try (on-query {:prompt prompt :response (:content result) :reasoning (:reasoning result)}) (catch Exception _)))
           result)
         (finally (swap! depth-atom dec)))))
    ([prompt opts]
     (if (>= @depth-atom *max-recursion-depth*)
       {:content (str "Max recursion depth (" *max-recursion-depth* ") exceeded")}
       (try
         (swap! depth-atom inc)
         (let [result (if-let [spec (:spec opts)]
                        {:content (pr-str (:result (llm/ask! {:spec spec :messages [(llm/system "You are a helpful assistant.") (llm/user prompt)]
                                                               :model model :api-key api-key :base-url base-url})))}
                        (llm/chat-completion [{:role "user" :content prompt}] model api-key base-url))]
           (when on-query
             (try (on-query {:prompt prompt :response (:content result) :reasoning (:reasoning result)}) (catch Exception _)))
           result)
         (finally (swap! depth-atom dec)))))))

(defn- make-routed-llm-query-fn
  "Creates an llm-query function that routes across providers via a router.
   Errors are caught and returned as {:content \"ERROR: ...\" :error true} so the
   LLM can see them and adapt (e.g., retry with different approach, call FINAL)."
  [use-case depth-atom rlm-router & [{:keys [on-query]}]]
  (fn llm-query
    ([prompt]
     (if (>= @depth-atom *max-recursion-depth*)
       {:content (str "Max recursion depth (" *max-recursion-depth* ") exceeded") :error true}
       (try
         (swap! depth-atom inc)
         (let [result (router/routed-chat-completion rlm-router [{:role "user" :content prompt}] use-case)]
           (when on-query
             (try (on-query {:prompt prompt :response (:content result) :reasoning (:reasoning result)}) (catch Exception _)))
           result)
         (catch Exception e
           (trove/log! {:level :warn :data {:error (ex-message e) :use-case use-case} :msg "llm-query failed"})
           {:content (str "ERROR: " (ex-message e)) :error true})
         (finally (swap! depth-atom dec)))))
    ([prompt opts]
     (if (>= @depth-atom *max-recursion-depth*)
       {:content (str "Max recursion depth (" *max-recursion-depth* ") exceeded") :error true}
       (try
         (swap! depth-atom inc)
         (let [result (if-let [spec (:spec opts)]
                        {:content (pr-str (:result (router/routed-ask! rlm-router
                                                                        {:spec spec :messages [(llm/system "You are a helpful assistant.") (llm/user prompt)]}
                                                                        use-case)))}
                        (router/routed-chat-completion rlm-router [{:role "user" :content prompt}] use-case))]
           (when on-query
             (try (on-query {:prompt prompt :response (:content result) :reasoning (:reasoning result)}) (catch Exception _)))
           result)
         (catch Exception e
           (trove/log! {:level :warn :data {:error (ex-message e) :use-case use-case} :msg "llm-query failed"})
           {:content (str "ERROR: " (ex-message e)) :error true})
         (finally (swap! depth-atom dec)))))))

(defn- make-rlm-query-fn
  "Creates the rlm-query function for sub-RLM queries that share the same database.

    This allows the LLM to spawn sub-queries with code execution that reuse
    the same database, enabling complex multi-step analysis."
  [use-case depth-atom rlm-router db-info-atom]
  (fn rlm-query
    ([context sub-query]
     (rlm-query context sub-query {}))
    ([context sub-query opts]
     (if (>= @depth-atom *max-recursion-depth*)
       {:error (str "Max recursion depth (" *max-recursion-depth* ") exceeded")}
       (try
         (swap! depth-atom inc)
         (run-sub-rlm context sub-query use-case rlm-router db-info-atom
                      (merge {:max-iterations 10} opts))
         (finally (swap! depth-atom dec)))))))

(defn- run-sub-rlm
  "Runs a sub-RLM query that shares the same database as the parent.

    This enables nested RLM queries where the sub-query can read/write
    to the same store, access the same history, etc.

   Params:
   `context` - Data context for the sub-query
   `query` - The question to answer
   `use-case` - Use-case keyword for routing (e.g. :root, :sub)
   `rlm-router` - Router for LLM calls
   `db-info-atom` - Shared database atom from parent RLM
   `opts` - Options including :max-iterations, :spec"
  [context query use-case rlm-router db-info-atom opts]
  (let [sub-env-id (str (java.util.UUID/randomUUID))
        parent-env-id (:rlm-env-id *rlm-ctx*)
        depth-atom (atom 0)
        locals-atom (atom {})
        ;; Create query functions that share the same DB
        llm-query-fn (make-routed-llm-query-fn :root depth-atom rlm-router)
        rlm-query-fn (make-rlm-query-fn :root depth-atom rlm-router db-info-atom)
        ;; Create SCI context with shared DB (no custom bindings in sub-RLM)
        sci-ctx (create-sci-context context llm-query-fn rlm-query-fn locals-atom db-info-atom nil)
        sub-env {:env-id sub-env-id
                 :parent-env-id parent-env-id
                 :sci-ctx sci-ctx
                 :context context
                 :llm-query-fn llm-query-fn
                 :rlm-query-fn rlm-query-fn
                 :locals-atom locals-atom
                 :db-info-atom db-info-atom
                 :router rlm-router
                 :history-enabled? true}
        max-iterations (or (:max-iterations opts) 10)
        output-spec (:spec opts)
        ;; Run a simplified iteration loop (no refine-eval, no examples)
        system-prompt (build-system-prompt {:output-spec output-spec :history-enabled? true})
        context-str (pr-str context)
        context-preview (if (> (count context-str) 1000)
                          (str (subs context-str 0 1000) "\n... [truncated]")
                          context-str)
        initial-user-content (str "<context>\n" context-preview "\n</context>\n\n<query>\n" query "\n</query>")
        initial-messages [{:role "system" :content system-prompt}
                          {:role "user" :content initial-user-content}]
        ;; Store to shared history
        db-info @db-info-atom
        _ (store-message! db-info :user initial-user-content {:iteration 0 :env-id sub-env-id})]
    (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-env-id sub-env-id :rlm-type :sub :rlm-parent-id parent-env-id :rlm-phase :sub-iteration-loop})]
      (rlm-debug! {:query query :max-iterations max-iterations :parent-env-id parent-env-id} "Sub-RLM started")
      (loop [iteration 0 messages initial-messages]
        (if (>= iteration max-iterations)
          (do (trove/log! {:level :warn :data {:iteration iteration} :msg "Sub-RLM max iterations reached"})
              {:status :max-iterations :iterations iteration})
          (let [{:keys [response thinking executions final-result]}
                (run-iteration sub-env messages nil nil nil)]
            (store-message! db-info :assistant response {:iteration iteration :env-id sub-env-id})
            (if final-result
              (do (rlm-debug! {:iteration iteration} "Sub-RLM FINAL detected")
                  {:answer (:result (:answer final-result) (:answer final-result)) :iterations iteration})
              (let [exec-feedback (format-executions executions)
                    iteration-header (str "[Iteration " (inc iteration) "/" max-iterations "]")
                    user-feedback (if (empty? executions)
                                    (str iteration-header "\nNo code was executed. You MUST include Clojure expressions in the \"code\" JSON array. Respond with valid JSON: {\"thinking\": \"...\", \"code\": [\"...\"]}")
                                    (str iteration-header "\n" exec-feedback))]
                (rlm-debug! {:iteration iteration :code-blocks (count executions) :has-thinking? (some? thinking)} "Sub-RLM iteration feedback")
                (store-message! db-info :user user-feedback {:iteration (inc iteration) :env-id sub-env-id})
                (recur (inc iteration)
                       (conj messages
                             {:role "assistant" :content response}
                             {:role "user" :content user-feedback}))))))))))

;; =============================================================================
;; Code Extraction
;; =============================================================================

(defn- extract-code-blocks [text]
  (let [pattern #"```(?:clojure|repl|clj)?\s*\n([\s\S]*?)\n```"
        matches (re-seq pattern text)]
    (->> matches (map second) (map str/trim) (remove str/blank?) vec)))

;; =============================================================================
;; System Prompt
;; =============================================================================

(defn- format-param
  "Formats a single parameter for the system prompt."
  [{:keys [name type required description default]}]
  (str "      " name " - " (clojure.core/name (or type :any))
       (when-not (true? required) " (optional)")
       (when (some? default) (str ", default: " (pr-str default)))
       (when description (str " — " description))))

(defn- format-tool-doc
  "Formats a tool doc entry for the system prompt."
  [{:keys [type sym doc params returns examples]}]
  (str "  <" (clojure.core/name type) " name=\"" sym "\">\n"
       (when doc (str "    " doc "\n"))
       (when (seq params)
         (str "    Parameters:\n"
              (str/join "\n" (map format-param params)) "\n"))
       (when returns
         (str "    Returns: " (clojure.core/name (or (:type returns) :any))
              (when (:description returns) (str " — " (:description returns)))
              "\n"))
       (when (seq examples)
         (str "    Examples:\n"
              (str/join "\n" (map #(str "      " %) examples)) "\n"))
       "  </" (clojure.core/name type) ">"))

(defn- format-custom-docs
  "Formats custom docs for the system prompt."
  [custom-docs]
  (when (seq custom-docs)
    (str "\n<custom_tools>\n"
         (str/join "\n" (map format-tool-doc custom-docs))
         "\n</custom_tools>\n")))

(defn- detect-model-family
  "Detect model family from model name for prompt tuning.

   Params:
   `model` - String or nil. Model name (e.g. \"gpt-4o\", \"claude-opus-4-6\", \"gemini-2.5-pro\").

   Returns:
   Keyword. One of :openai, :anthropic, :google, :unknown."
  [model]
  (let [m (some-> model str/lower-case)]
    (cond
      (nil? m)                                          :unknown
      (or (str/includes? m "gpt") (str/includes? m "o1")
          (str/includes? m "o3") (str/includes? m "o4")) :openai
      (or (str/includes? m "claude") (str/includes? m "anthropic")) :anthropic
      (or (str/includes? m "gemini") (str/includes? m "gemma")) :google
      :else :unknown)))

(defn- format-active-learnings
  "Format pre-fetched learnings for injection into the system prompt.

   These are automatically retrieved at query start — the LLM doesn't need
   to call search-learnings manually for these. Includes model-specific
   learnings when they match the current model.

   Params:
   `learnings` - Vector of normalized learning maps.

   Returns:
   String with formatted learnings section, or nil if empty."
  [learnings]
  (when (seq learnings)
    (str "\n<active_learnings>\n"
         "These are validated insights from prior sessions. Apply them.\n"
         (str/join "\n" (map-indexed
                          (fn [i l]
                            (str "  " (inc i) ". " (:insight l)
                                 (when (:context l) (str " [context: " (:context l) "]"))))
                          learnings))
         "\n</active_learnings>\n")))

(defn- build-system-prompt
  "Builds the system prompt. Optionally includes spec schema, examples, history tools, and custom docs."
  [{:keys [output-spec examples history-enabled? custom-docs has-documents? learnings]}]
  (str "<rlm_environment>
<role>You are an expert Clojure programmer analyzing data in a sandboxed environment.</role>

<available_tools>
  <tool name=\"context\">The data context - access as 'context' variable</tool>
  <tool name=\"llm-query\">(llm-query prompt) or (llm-query prompt {:spec my-spec}) - Simple text query to LLM</tool>
  <tool name=\"rlm-query\">(rlm-query sub-context query) or (rlm-query sub-context query {:spec s :max-iterations n}) - Spawn sub-RLM with code execution, SHARES the same database</tool>
  <tool name=\"llm-query-batch\">(llm-query-batch [prompt1 prompt2 ...]) - Parallel batch of LLM sub-calls. Returns vector of results. Use for map-reduce patterns over many chunks.</tool>
  <tool name=\"FINAL\">(FINAL answer) - MUST call when you have the answer</tool>

  <tool name=\"list-locals\">(list-locals) - see all variables you've defined (functions show as &lt;fn&gt;, large collections summarized)</tool>
  <tool name=\"get-local\">(get-local 'var-name) - get full value of a specific variable you defined</tool>
</available_tools>
"
       (when has-documents? "
<raw_text_tools>
  <description>Direct programmatic access to the full document text. Use for exhaustive iteration, regex across entire content, or arbitrary slicing.</description>
  <tool name=\"P\">P - The full concatenated text of all ingested documents as a single string. Use (subs P start end) for slicing, (count P) for length, (re-seq pattern P) for regex.</tool>
  <tool name=\"P-len\">P-len - Length of P in characters (pre-computed).</tool>
  <tool name=\"get-page\">(get-page n) - Returns the raw text content of page n (0-indexed).</tool>
  <tool name=\"page-count\">(page-count) - Total number of pages across all documents.</tool>
  <usage_tips>
    - For RETRIEVAL tasks (finding specific clauses, entities): prefer search-page-nodes — it's faster
    - For AGGREGATION tasks (counting, summarizing all entries): use get-page + loop — it's exhaustive
    - Combine both: search to find relevant pages, then get-page for full content
    - For regex across entire corpus: (re-seq #\"pattern\" P)
  </usage_tips>
</raw_text_tools>

<document_tools>
  <description>Query stored PageIndex documents. Documents contain metadata, pages, page nodes (actual content), and TOC entries.</description>
  <tool name=\"list-documents\">(list-documents) or (list-documents {:limit n :include-toc? true}) - List documents with abstracts and TOC. Returns:
    [{:document/id \"...\"
      :document/name \"filename\"
      :document/title \"Document Title\"
      :document/abstract \"Summary of the document...\"
      :document/extension \"pdf\"
      :document/toc [{:title \"Chapter 1\" :level \"l1\" :page 0}
                     {:title \"Section 1.1\" :level \"l2\" :page 3}
                     ...]}]</tool>
  <tool name=\"get-document\">(get-document doc-id) - Get document with abstract and full TOC. Returns same structure as list-documents item.</tool>
  <usage_tips>
    - START HERE: Call (list-documents) first to see what's available with abstracts and TOC
    - Use TOC to understand document structure before searching content
    - TOC :page values are 0-indexed (page 0 = first page)
    - TOC :level indicates depth: l1=chapter, l2=section, l3=subsection
  </usage_tips>
</document_tools>

<page_node_tools>
  <description>Search and retrieve actual document content. Page nodes are the content elements: paragraphs, headings, images, tables, etc. This is where the TEXT lives.</description>
  <tool name=\"search-page-nodes\">(search-page-nodes query) or (search-page-nodes query top-k) or (search-page-nodes query top-k {:document-id id :type :paragraph}) - Search page node content by text (case-insensitive). Returns matching nodes with full content: [{:page.node/id :page.node/type :page.node/content :page.node/description :page.node/page-id :page.node/document-id}...]. Pass nil/blank query for list mode (truncated content).</tool>
  <tool name=\"get-page-node\">(get-page-node node-id) - Get full page node by ID. Returns node map with all content.</tool>
  <tool name=\"list-page-nodes\">(list-page-nodes) or (list-page-nodes {:page-id id :document-id id :type :heading :limit n}) - List page nodes with filters.</tool>
  <page_node_schema>
    :page.node/id - String (UUID), unique identifier
    :page.node/type - Keyword: :section :heading :paragraph :list-item :image :table :header :footer :metadata
    :page.node/level - String, e.g. \"h1\"-\"h6\" for headings, \"l1\"-\"l6\" for lists
    :page.node/content - String, the actual text content for text nodes
    :page.node/description - String, AI-generated description for sections/images/tables (visual nodes use this for text representation)
    :page.node/page-id - String, reference to parent page
    :page.node/document-id - String, reference to parent document
  </page_node_schema>
  <usage_tips>
    - USE search-page-nodes or list-page-nodes TO FIND ACTUAL CONTENT
    - Filter by :type to find specific content (e.g., :paragraph for text, :heading for titles)
    - Filter by :document-id to search within a specific document
    - :content has the actual text, :description has AI summary
  </usage_tips>
</page_node_tools>

<toc_entry_tools>
  <description>Table of Contents entries - section titles with page references. Use to understand document structure.</description>
  <tool name=\"store-toc-entry!\">(store-toc-entry! entry) - Store a PageIndex TOC entry exactly as-is. Returns stored entry.</tool>
  <tool name=\"search-toc-entries\">(search-toc-entries query) or (search-toc-entries query top-k) - Search TOC entries by title/description text (case-insensitive). Returns [{:document.toc/id :document.toc/title :document.toc/description :document.toc/level :document.toc/target-page}...]. Pass nil/blank query for list mode.</tool>
  <tool name=\"get-toc-entry\">(get-toc-entry entry-id) - Get full TOC entry by ID string.</tool>
  <tool name=\"list-toc-entries\">(list-toc-entries) or (list-toc-entries {:parent-id id :limit n}) - List TOC entries.</tool>
  <toc_entry_schema>
    :document.toc/id - String (UUID), unique identifier
    :document.toc/title - String, section title
    :document.toc/description - String or nil, section summary
    :document.toc/target-page - Long, page number (0-based)
    :document.toc/level - String, hierarchy level (\"l1\", \"l2\", \"l3\")
    :document.toc/parent-id - String or nil, parent entry ID
  </toc_entry_schema>
  <usage_tips>
    - Use to find WHICH SECTION contains information (then use search-page-nodes for content)
    - Level indicates depth: l1 = chapter, l2 = section, l3 = subsection
    - TOC entries link to page nodes via target-page
  </usage_tips>
</toc_entry_tools>

<entity_tools>
  <description>Search, retrieve, and analyze extracted entities and their relationships. Entities are structured data extracted from documents: parties, obligations, conditions, terms, clauses, cross-references.</description>
  <tool name=\"search-entities\">(search-entities query) or (search-entities query top-k) or (search-entities query top-k {:type :party :document-id \"...\"}) - Search entities by name/description text (case-insensitive). Returns [{:entity/id :entity/name :entity/type :entity/description :entity/document-id :entity/page :entity/section}...]. Pass nil/blank query for list mode.</tool>
  <tool name=\"get-entity\">(get-entity entity-id) - Get full entity by UUID. Returns entity map or nil.</tool>
  <tool name=\"list-entities\">(list-entities) or (list-entities {:type :party :document-id \"...\" :limit 50}) - List entities with optional filters.</tool>
  <tool name=\"list-relationships\">(list-relationships entity-id) or (list-relationships entity-id {:type :references}) - List relationships where entity is source OR target. Returns [{:relationship/id :relationship/type :relationship/source-entity-id :relationship/target-entity-id :relationship/description}...]</tool>
  <tool name=\"entity-stats\">(entity-stats) - Get entity statistics: {:total-entities N :types {:party N :obligation N ...} :total-relationships N}</tool>
  <entity_schema>
    :entity/id - UUID, unique identifier
    :entity/name - String, entity name or label
    :entity/type - Keyword: :party, :obligation, :condition, :term, :clause, :cross-reference
    :entity/description - String, extracted context/description
    :entity/document-id - String, source document reference
    :entity/page - Long, source page number
    :entity/section - String, source section identifier
  </entity_schema>
  <relationship_schema>
    :relationship/id - UUID, unique identifier
    :relationship/type - Keyword: :references, :defines, :obligates, :conditions, :amends
    :relationship/source-entity-id - UUID, source entity
    :relationship/target-entity-id - UUID, target entity
    :relationship/description - String, relationship context
  </relationship_schema>
  <usage_tips>
    - START with (entity-stats) to see what entities are available
    - Use (search-entities \"party name\") to find specific entities
    - Use (list-relationships entity-id) to explore entity connections
    - Filter by :type to find specific entity categories (e.g., :party, :obligation)
    - Entities complement page-node search: entities give STRUCTURE, page-nodes give CONTENT
  </usage_tips>
</entity_tools>
")
       "
<date_tools>
  <description>Date manipulation functions for ISO-8601 date strings (YYYY-MM-DD format).</description>
  <tool name=\"parse-date\">(parse-date date-str) - Parse and validate ISO-8601 date. Returns string or nil.</tool>
  <tool name=\"date-before?\">(date-before? date1 date2) - True if date1 &lt; date2.</tool>
  <tool name=\"date-after?\">(date-after? date1 date2) - True if date1 &gt; date2.</tool>
  <tool name=\"days-between\">(days-between date1 date2) - Number of days between dates (negative if date1 &gt; date2).</tool>
  <tool name=\"date-plus-days\">(date-plus-days date-str days) - Add days to date. Returns ISO string.</tool>
  <tool name=\"date-minus-days\">(date-minus-days date-str days) - Subtract days from date. Returns ISO string.</tool>
  <tool name=\"date-format\">(date-format date-str pattern) - Format date with custom pattern (e.g., \"dd/MM/yyyy\").</tool>
  <tool name=\"today-str\">(today-str) - Returns today's date as ISO-8601 string.</tool>
  <usage_tips>
    - All dates use ISO-8601 format: \"2024-01-15\"
    - Functions return nil on invalid input (no exceptions)
    - Use days-between for deadline/duration calculations
    - Use date-before?/date-after? for date comparisons
  </usage_tips>
</date_tools>

<set_tools>
  <description>Set operations for working with collections as sets.</description>
  <tool name=\"set-union\">(set-union s1 s2) - Union of two sets.</tool>
  <tool name=\"set-intersection\">(set-intersection s1 s2) - Intersection of two sets.</tool>
  <tool name=\"set-difference\">(set-difference s1 s2) - Elements in s1 not in s2.</tool>
  <tool name=\"set-subset?\">(set-subset? s1 s2) - True if s1 is a subset of s2.</tool>
  <tool name=\"set-superset?\">(set-superset? s1 s2) - True if s1 is a superset of s2.</tool>
  <usage_tips>
    - Convert vectors to sets first: (set [1 2 3])
    - Useful for comparing entity lists, finding overlaps, deduplication
  </usage_tips>
</set_tools>

<example_learning_tools>
  <description>Search past successful queries and answers. Use these to learn from previous similar questions and avoid mistakes.</description>
  <tool name=\"search-examples\">(search-examples query) or (search-examples query top-k) - Search past queries/answers by text (case-insensitive). Returns [{:query :answer :score :good?}...]. Pass nil/blank query for recent examples by timestamp.</tool>
  <tool name=\"get-recent-examples\">(get-recent-examples) or (get-recent-examples n) - Get last N examples chronologically. Default 10.</tool>
  <tool name=\"example-stats\">(example-stats) - Get example statistics: {:total-examples :good-examples :bad-examples :avg-score}</tool>
  <usage_tips>
    - Use search-examples early to see how similar questions were answered before
    - Good examples (score >= 32) show successful approaches
    - Bad examples show what to avoid
    - Learn from past mistakes to improve your answers
  </usage_tips>
</example_learning_tools>

<learnings_tools>
  <description>Store, retrieve, and vote on meta-insights about HOW to approach problems (DB-backed, persisted). Unlike examples (query→answer), learnings capture strategies and patterns that work. Learnings are validated through voting - poorly rated learnings decay and are filtered out.</description>
  <tool name=\"store-learning\">(store-learning insight) or (store-learning insight context) - Store a meta-insight you discovered. Examples: 'For date questions, verify year first', 'Check for duplicates before summing'</tool>
  <tool name=\"search-learnings\">(search-learnings query) or (search-learnings query top-k) - Search learnings by insight/context text (case-insensitive). Returns [{:learning/id :insight :context :useful-count :not-useful-count}...]. Pass nil/blank query for recent learnings. Automatically tracks usage.</tool>
  <tool name=\"vote-learning\">(vote-learning learning-id :useful) or (vote-learning learning-id :not-useful) - Vote on whether a learning was helpful. ALWAYS vote after task completion!</tool>
  <tool name=\"learning-stats\">(learning-stats) - Get learning statistics: {:total-learnings :active-learnings :decayed-learnings :total-votes :total-applications}</tool>
  <voting_workflow>
    1. At task START: (search-learnings \"your task\") to get relevant learnings
    2. Note the :learning/id of each learning you use
    3. At task END: Vote on EACH learning you retrieved:
       - (vote-learning learning-id :useful) if it helped
       - (vote-learning learning-id :not-useful) if it didn't help
    4. Learnings with >70% negative votes (after 5+ votes) are automatically filtered out (decayed)
  </voting_workflow>
  <usage_tips>
    - ALWAYS vote after completing a task - this improves learning quality over time
    - Search learnings at the START of a task to benefit from past insights
    - Include context when storing to make learnings more findable
    - Learnings are persisted to EDN and survive across sessions
  </usage_tips>
  <examples>
    (def my-learnings (search-learnings \"calculating totals\"))
    ;; ... use learnings during task ...
    (vote-learning (:learning/id (first my-learnings)) :useful)
    (store-learning \"Sum operations need duplicate checking\" \"aggregation tasks\")
  </examples>
</learnings_tools>
"
       ;; Include history tools if enabled
       (when history-enabled?
         "
<history_tools>
  <description>You can search and retrieve your own conversation history. Use these when you need to recall past exploration, avoid repeating work, or build on previous findings.</description>
  <tool name=\"search-history\">(search-history) or (search-history n) - Get N most recent messages (default 5). Count-based, not text search. Returns [{:role :content :tokens}...]</tool>
  <tool name=\"get-history\">(get-history) or (get-history n) - Get last N messages chronologically. Default 10.</tool>
  <tool name=\"history-stats\">(history-stats) - Get history statistics: {:total-messages :total-tokens :by-role}</tool>
  <usage_tips>
    - Use search-history or get-history to see recent conversation flow
    - Check history-stats to know how much context you've built up
    - Checking history can help you avoid re-doing work from earlier iterations
  </usage_tips>
</history_tools>
")
       "
<string_helpers>str-lines, str-words, str-truncate, str-join, str-split, str-replace, str-trim, str-lower, str-upper, str-blank?, str-includes?, str-starts-with?, str-ends-with?</string_helpers>

<regex>re-pattern, re-find, re-matches, re-seq</regex>

<safe_functions>map, filter, reduce, assoc, get, keys, vals, first, rest, take, drop, sort, group-by, frequencies, +, -, *, /, etc.</safe_functions>
"
       ;; Include custom docs if provided
       (format-custom-docs custom-docs)

       ;; Include spec schema if provided
       (when output-spec
         (str "\n<expected_output_schema>\n"
              "Your FINAL answer should match this structure:\n"
              (spec/spec->prompt output-spec)
              "\n</expected_output_schema>\n"))

       ;; Include examples
       (format-examples-for-prompt examples)

       "
<rlm_patterns>
  <description>Proven strategies for processing large documents. Choose based on task type.</description>

  <pattern name=\"Partition + Map (aggregation tasks)\">
    Use when the answer depends on MANY or ALL entries (counting, summarizing, comparing).
    Process pages in batches with llm-query-batch for parallelism:

    (def total-pages (page-count))
    (def chunk-size 5)
    (def chunks (partition-all chunk-size (range total-pages)))
    (def batch-results
      (llm-query-batch
        (mapv (fn [page-group]
                (str \"Analyze pages \" (first page-group) \"-\" (last page-group) \":\\n\"
                     (str-join \"\\n---\\n\" (mapv get-page page-group))))
              chunks)))
    (def Final (:content (llm-query (str \"Synthesize these results:\\n\" (str-join \"\\n\" (mapv :content batch-results))))))
  </pattern>

  <pattern name=\"Targeted Search (retrieval tasks)\">
    Use when looking for SPECIFIC information (clauses, entities, definitions):

    (def hits (search-page-nodes \"penalty clause\"))
    (def relevant-content (mapv #(get-page-node (:page.node/id %)) hits))
    (FINAL {:answer \"...\" :sources (mapv :page.node/page-id relevant-content)})
  </pattern>

  <pattern name=\"Regex Scan (structured extraction)\">
    Use when data follows a pattern across the full text:

    (def matches (re-seq #\"Section \\d+\\.\\d+\" P))
    (def date-refs (re-seq #\"\\d{4}-\\d{2}-\\d{2}\" P))
  </pattern>
</rlm_patterns>

<workflow>
0. FIRST: Check <context> - if it directly answers the query, call (FINAL answer) immediately without searching
1. If more info needed, check available documents: (list-documents)
2. Browse TOC to understand document structure: (list-toc-entries)
3. Pick sections from TOC, get content: (get-page-node node-id) or (list-page-nodes {:document-id id})
4. For exhaustive analysis, iterate pages: (get-page 0), (get-page 1), ... or use llm-query-batch
5. Check entities if relevant: (entity-stats), then (list-entities {:type :party})
6. Write code to analyze data, store intermediate results with (def my-var ...)"
       (when history-enabled?
         "\n7. Use get-history to check recent conversation")
       "\n" (if history-enabled? "8" "7") ". Call (FINAL answer) when done
</workflow>

<response_format>
" (spec/spec->prompt ITERATION_SPEC) "
EVERY response MUST be valid JSON with 'thinking' and 'code' fields. No markdown, no prose outside JSON.
</response_format>

<critical>
- STDOUT IS TRUNCATED: Output in your history is capped at " STDOUT_TRUNCATION_LIMIT " chars. Store important results in variables with (def my-var ...) — variables persist across ALL iterations. Do NOT rely on reading past output.
- CLOJURE SYNTAX: ALL function calls MUST be wrapped in parentheses. `(list-documents)` calls the function, `list-documents` just references the function object and returns nothing useful. Same for FINAL: `(FINAL answer)` terminates, `FINAL answer` does NOT.
- FAST PATH: If <context> already contains the answer, call (FINAL answer) IMMEDIATELY - no searching needed!
- USE list-page-nodes or search-page-nodes FOR CONTENT (not just TOC entries!)
- FOR LARGE DOCUMENTS: Use llm-query-batch for parallel processing instead of sequential llm-query calls
- ALWAYS call (FINAL answer) when you have the answer - don't keep searching after finding it
- Max " MAX_ITERATIONS " iterations, " (/ EVAL_TIMEOUT_MS 1000) "s timeout per execution"
       (when history-enabled?
         "\n- Use get-history to check recent conversation and avoid repeating earlier work")
       "
</critical>
"
       ;; Inject pre-fetched learnings (model-specific + query-relevant)
       (format-active-learnings learnings)
       "
</rlm_environment>"))

;; =============================================================================
;; Iteration Loop
;; =============================================================================

(defn- run-iteration [rlm-env messages _model api-key base-url]
  (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-phase :run-iteration})]
    (let [_ (rlm-debug! {:msg-count (count messages)} "LLM call started")
          response-data (if-let [r (:router rlm-env)]
                          (router/routed-chat-completion r messages :root)
                          (llm/chat-completion messages _model api-key base-url))
          response (:content response-data)
          model-reasoning (:reasoning response-data)
          _ (rlm-debug! {:response-len (count response)
                         :has-reasoning (some? model-reasoning)
                         :response-preview (str-truncate response 300)} "LLM response received")
        ;; Parse structured response via spec (primary), fall back to code fences
          parsed (try (let [p (spec/str->data-with-spec response ITERATION_SPEC)]
                        (rlm-debug! {} "Response parsed via ITERATION_SPEC (structured)")
                        p)
                      (catch Exception e
                      ;; Fallback: extract code from markdown fences
                        (rlm-debug! {:parse-error (ex-message e)} "Spec parse failed, falling back to markdown extraction")
                        {:thinking response :code (extract-code-blocks response)}))
          thinking (or model-reasoning (:thinking parsed))
          code-blocks (vec (remove str/blank? (or (:code parsed) [])))
          _ (rlm-debug! {:code-block-count (count code-blocks)
                         :code-previews (mapv #(str-truncate % 120) code-blocks)} "Code blocks extracted")
          execution-results (mapv #(execute-code rlm-env %) code-blocks)
        ;; Combine code blocks with their execution results
          executions (mapv (fn [idx code result]
                             {:id idx
                              :code code
                              :result (:result result)
                              :stdout (:stdout result)
                              :error (:error result)
                              :execution-time-ms (:execution-time-ms result)})
                           (range)
                           code-blocks
                           execution-results)
          final-result (or (some #(let [check (check-result-for-final %)] (when (:final? check) check)) execution-results)
                           (some #(when-let [ans (find-final-in-stdout (:stdout %))] {:final? true :answer ans}) execution-results))]
      {:response response :thinking thinking :executions executions :final-result final-result})))

(defn- format-executions
  "Formats executions for LLM feedback. Each execution has :id, :code, :result, :stdout, :error.
   Detects bare symbol references (function objects) and provides corrective feedback."
  [executions]
  (str/join "\n\n"
            (map (fn [{:keys [id code error result stdout]}]
                   (str "<result_" id ">\n"
                        (if error
                          (str "  <error>" error "</error>")
                          (if (fn? result)
                            (str "  <value>" (str/trim code) " evaluated to a function object. "
                                 "Did you mean to call it? Use `(" (str/trim code) ")` instead.</value>")
                            (let [result-str (pr-str (realize-value result))
                                  truncated-result (truncate-for-history result-str STDOUT_TRUNCATION_LIMIT)]
                              (str "  <value>" truncated-result "</value>"
                                   (when-not (str/blank? stdout)
                                     (str "\n  <stdout>" (truncate-for-history stdout STDOUT_TRUNCATION_LIMIT) "</stdout>"))))))
                        "\n</result_" id ">"))
                 executions)))

(defn- resolve-root-model
  "Resolves the root model name from a router, or falls back to a default.
   Used for token counting (store-message!, truncate-messages)."
  [rlm-router]
  (when rlm-router
    (when-let [[_provider model] (router/select-provider rlm-router :root)]
      model)))

(defn- iteration-loop [rlm-env query model api-key base-url max-iterations
                       {:keys [output-spec examples learnings max-context-tokens custom-docs
                               pre-fetched-context on-iteration]}]
  (let [;; Resolve effective model name for token counting
        effective-model (or (when-let [r (:router rlm-env)] (resolve-root-model r)) model "gpt-4o")
        history-enabled? (:history-enabled? rlm-env)
        has-docs? (when-let [db-atom (:db-info-atom rlm-env)]
                    (when-let [db @db-atom]
                      (pos? (count (db-list-documents db {:limit 1 :include-toc? false})))))
        system-prompt (build-system-prompt {:output-spec output-spec
                                            :examples examples
                                            :history-enabled? history-enabled?
                                            :custom-docs custom-docs
                                            :has-documents? has-docs?
                                            :learnings learnings})
        context-data (:context rlm-env)
        context-str (pr-str context-data)
        context-preview (if (> (count context-str) 2000)
                          (str (subs context-str 0 2000) "\n... [truncated, use code to explore]")
                          context-str)
        initial-user-content (str "<context>\n" context-preview "\n</context>\n\n<query>\n" query "\n</query>"
                                  (when pre-fetched-context (str "\n\n" pre-fetched-context)))
        initial-messages [{:role "system" :content system-prompt}
                          {:role "user" :content initial-user-content}]
        ;; Store initial messages if history tracking is enabled
        db-info (when-let [atom (:db-info-atom rlm-env)] @atom)
        env-id (:env-id rlm-env)
        _ (when history-enabled?
            (store-message! db-info :system system-prompt {:iteration 0 :model effective-model :env-id env-id})
            (store-message! db-info :user initial-user-content {:iteration 0 :model effective-model :env-id env-id}))]
    (rlm-debug! {:query query :max-iterations max-iterations :model effective-model
                 :has-output-spec? (some? output-spec) :has-pre-fetched? (some? pre-fetched-context)
                 :msg-count (count initial-messages)} "Iteration loop started")
    (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-phase :iteration-loop})]
      (loop [iteration 0 messages initial-messages trace [] consecutive-errors 0]
        (if (>= iteration max-iterations)
          (let [locals (get-locals rlm-env)
                useful-value (some->> locals vals (filter #(and (some? %) (not (fn? %)))) last)]
            (trove/log! {:level :warn :data {:iteration iteration} :msg "Max iterations reached"})
            {:answer (if useful-value (pr-str useful-value) nil)
             :status :max-iterations
             :locals locals
             :trace trace
             :iterations iteration})
          (if (>= consecutive-errors 5)
            (do (trove/log! {:level :warn :data {:iteration iteration :consecutive-errors consecutive-errors} :msg "Error budget exhausted"})
                {:answer nil :status :error-budget-exhausted :trace trace :iterations iteration})
          (let [_ (rlm-debug! {:iteration iteration :msg-count (count messages)} "Iteration start")
              ;; Smart context management: use semantic selection when history enabled, else simple truncation
                effective-messages (cond
                                   ;; Semantic context selection when history enabled + budget set + enough messages
                                     (and history-enabled? max-context-tokens (> (count messages) 4))
                                      (select-rlm-iteration-context
                                       db-info messages max-context-tokens
                                       {:model effective-model})

                                   ;; Simple truncation when just budget set
                                     max-context-tokens
                                      (tokens/truncate-messages effective-model messages max-context-tokens)

                                   ;; No budget - use all messages
                                     :else messages)
                iteration-result (try
                                   (run-iteration rlm-env effective-messages model api-key base-url)
                                   (catch Exception e
                                     (let [err-msg (ex-message e)
                                           err-type (:type (ex-data e))]
                                       (trove/log! {:level :warn
                                                    :data {:iteration iteration :error err-msg :type err-type}
                                                    :msg "RLM iteration failed, feeding error to LLM"})
                                       ;; Return ::iteration-error sentinel — loop will feed error to LLM
                                       {::iteration-error {:message err-msg :type err-type}})))]
            (if-let [iter-err (::iteration-error iteration-result)]
              ;; Error path: feed error back to LLM as user message, let it recover
              (let [error-feedback (str "[Iteration " (inc iteration) "/" max-iterations "]\n"
                                        "<error>LLM call failed: " (:message iter-err) "</error>\n"
                                        "The previous attempt failed. Adjust your approach or call (FINAL answer) with what you have.")
                    trace-entry {:iteration iteration :error iter-err :final? false}]
                (when on-iteration
                  (try (on-iteration trace-entry) (catch Exception _)))
                (when history-enabled?
                  (store-message! db-info :user error-feedback {:iteration (inc iteration) :model effective-model :env-id env-id}))
                (recur (inc iteration)
                       (conj messages {:role "user" :content error-feedback})
                       (conj trace trace-entry)
                       (inc consecutive-errors)))
              ;; Normal path
              (let [{:keys [response thinking executions final-result]} iteration-result
                    trace-entry {:iteration iteration
                                 :response response
                                 :thinking thinking
                                 :executions executions
                                 :final? (boolean final-result)}]
          ;; Notify caller of iteration progress
            (when on-iteration
              (try (on-iteration trace-entry) (catch Exception _)))
          ;; Store assistant response if tracking
            (when history-enabled?
              (store-message! db-info :assistant response {:iteration iteration :model effective-model :env-id env-id}))
            (if final-result
              (do (trove/log! {:level :info :data {:iteration iteration :answer (str-truncate (answer-str (:answer final-result)) 200)} :msg "FINAL detected"})
                  {:answer (:answer final-result)
                   :trace (conj trace trace-entry)
                   :iterations (inc iteration)})
              (let [exec-feedback (format-executions executions)
                    iteration-header (str "[Iteration " (inc iteration) "/" max-iterations "]")
                    user-feedback (if (empty? executions)
                                    (str iteration-header "\nNo code was executed. You MUST include Clojure expressions in the \"code\" JSON array. Respond with valid JSON: {\"thinking\": \"...\", \"code\": [\"...\"]}")
                                    (str iteration-header "\n" exec-feedback))]
                (rlm-debug! {:iteration iteration
                             :code-blocks (count executions)
                             :errors (count (filter :error executions))
                             :has-thinking? (some? thinking)
                             :thinking-preview (when thinking (str-truncate thinking 150))
                             :feedback-len (count user-feedback)} "Iteration feedback")
              ;; Store user feedback if tracking
                (when history-enabled?
                  (store-message! db-info :user user-feedback {:iteration (inc iteration) :model effective-model :env-id env-id}))
                (recur (inc iteration)
                       (conj messages
                             {:role "assistant" :content (truncate-for-history response 1500)}
                             {:role "user" :content user-feedback})
                       (conj trace trace-entry)
                       0))))))))))))

;; =============================================================================
;; Entity Extraction Functions
;; =============================================================================

(defn- extract-entities-from-page!
  "Extracts entities from a page's text nodes using LLM.

   Params:
   `text-content` - String. Combined text from page nodes.
   `rlm-router` - Router from router/make-router.

   Returns:
   Map with :entities and :relationships keys (empty if extraction fails)."
  [text-content rlm-router]
  (try
    (let [truncated (if (> (count text-content) 8000) (subs text-content 0 8000) text-content)
          response (router/routed-ask! rlm-router
                                       {:spec ENTITY_EXTRACTION_SPEC
                                        :messages [(llm/system ENTITY_EXTRACTION_OBJECTIVE)
                                                   (llm/user truncated)]}
                                       :extraction)]
      (or (:result response) {:entities [] :relationships []}))
    (catch Exception e
      (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Entity extraction failed for page"})
      {:entities [] :relationships []})))

(defn- extract-entities-from-visual-node!
  "Extracts entities from a visual node (image/table) using vision or text.

   Params:
   `node` - Map. Page node with :page.node/type, :page.node/image-data, :page.node/description.
   `rlm-router` - Router from router/make-router.

   Returns:
   Map with :entities and :relationships keys (empty if extraction fails)."
  [node rlm-router]
  (try
    (let [image-data (:page.node/image-data node)
          description (:page.node/description node)]
      (cond
        ;; Has image data - use vision
        image-data
        (let [b64 (bytes->base64 image-data)
              response (router/routed-ask! rlm-router
                                           {:spec ENTITY_EXTRACTION_SPEC
                                            :messages [(llm/system ENTITY_EXTRACTION_OBJECTIVE)
                                                       (llm/user (or description "Extract entities from this image")
                                                                  (llm/image b64 "image/png"))]}
                                           :extraction)]
          (or (:result response) {:entities [] :relationships []}))
        ;; Has description only - text extraction
        description
        (let [response (router/routed-ask! rlm-router
                                           {:spec ENTITY_EXTRACTION_SPEC
                                            :messages [(llm/system ENTITY_EXTRACTION_OBJECTIVE)
                                                       (llm/user description)]}
                                           :extraction)]
          (or (:result response) {:entities [] :relationships []}))
        ;; Neither - skip
        :else
        (do (trove/log! {:level :warn :msg "Visual node has no image-data or description, skipping"})
            {:entities [] :relationships []})))
    (catch Exception e
      (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Visual node extraction failed"})
      {:entities [] :relationships []})))

(defn- extract-entities-from-document!
  "Extracts entities from all pages of a document.

   Params:
   `db-info` - Map. Database info with :store key.
   `document` - Map. PageIndex document.
   `rlm-router` - Router from router/make-router.
   `opts` - Map. Options with :max-extraction-pages, :max-vision-rescan-nodes.

   Returns:
   Map with extraction statistics: :entities-extracted, :relationships-extracted,
   :pages-processed, :extraction-errors, :visual-nodes-scanned."
  [db-info document rlm-router opts]
  (let [max-pages (or (:max-extraction-pages opts) 50)
        max-vision (or (:max-vision-rescan-nodes opts) 10)
        pages (take max-pages (:document/pages document))
        doc-id (:document/id document (:document/name document))
        entities-atom (atom [])
        relationships-atom (atom [])
        errors-atom (atom 0)
        vision-count-atom (atom 0)]
    ;; Process each page
    (doseq [page pages]
      (let [nodes (:page/nodes page)
            text-nodes (filter #(not (#{:image :table} (:page.node/type %))) nodes)
            visual-nodes (filter #(#{:image :table} (:page.node/type %)) nodes)]
        ;; Extract from text
        (when (seq text-nodes)
          (let [text (str/join "\n" (keep :page.node/content text-nodes))]
            (when (not (str/blank? text))
              (try
                (let [result (extract-entities-from-page! text rlm-router)]
                  (swap! entities-atom into (:entities result))
                  (swap! relationships-atom into (:relationships result)))
                (catch Exception _ (swap! errors-atom inc))))))
        ;; Extract from visual nodes (capped)
        (doseq [vnode visual-nodes]
          (when (< @vision-count-atom max-vision)
            (try
              (let [result (extract-entities-from-visual-node! vnode rlm-router)]
                (swap! vision-count-atom inc)
                (swap! entities-atom into (:entities result))
                (swap! relationships-atom into (:relationships result)))
              (catch Exception _ (swap! errors-atom inc)))))))
    ;; Store entities and relationships in DB (two-phase)
    (let [entities @entities-atom
          relationships @relationships-atom
          conn (:conn db-info)
          name->uuid (atom {})]
      ;; Phase 1: Store entities, build name→UUID map
      (doseq [entity entities]
        (try
          (let [entity-id (java.util.UUID/randomUUID)
                entity-name (or (:entity/name entity) (:name entity) "unknown")
                entity-data (cond-> {:entity/id entity-id
                                     :entity/name entity-name
                                     :entity/type (or (:entity/type entity) (:type entity) :unknown)
                                     :entity/description (or (:entity/description entity) (:description entity) "")
                                     :entity/document-id (str doc-id)
                                     :entity/created-at (java.util.Date.)}
                              (or (:entity/section entity) (:section entity))
                              (assoc :entity/section (or (:entity/section entity) (:section entity)))
                              (or (:entity/page entity) (:page entity))
                              (assoc :entity/page (long (or (:entity/page entity) (:page entity)))))]
            (d/transact! conn [entity-data])
            (swap! name->uuid assoc (str/lower-case entity-name) entity-id))
          (catch Exception e
            (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Failed to store entity"}))))
      ;; Phase 2: Resolve entity names to UUIDs and store relationships
      (doseq [rel relationships]
        (try
          (let [src-name (or (:relationship/source-entity-id rel) (:source rel))
                tgt-name (or (:relationship/target-entity-id rel) (:target rel))
                src-id (get @name->uuid (some-> src-name str str/lower-case))
                tgt-id (get @name->uuid (some-> tgt-name str str/lower-case))]
            (when (and src-id tgt-id)
              (d/transact! conn [{:relationship/id (java.util.UUID/randomUUID)
                                  :relationship/type (or (:relationship/type rel) (:type rel) :unknown)
                                  :relationship/source-entity-id src-id
                                  :relationship/target-entity-id tgt-id
                                  :relationship/description (or (:relationship/description rel) (:description rel) "")
                                  :relationship/document-id (str doc-id)}])))
          (catch Exception e
            (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Failed to store relationship"}))))
      {:entities-extracted (count entities)
       :relationships-extracted (count relationships)
       :pages-processed (count pages)
       :extraction-errors @errors-atom
       :visual-nodes-scanned @vision-count-atom})))

;; =============================================================================
;; Public API - Component-Based Architecture
;; =============================================================================

(defn- make-default-persistence
  "Build the default Datalevin-backed persistence callbacks.

   Callers can override any of these by passing a :persistence map to create-env.

   Params:
   `db-info-atom` - Atom holding {:conn datalevin-conn}.

   Returns:
   Map of persistence callbacks."
  [db-info-atom]
  {:store-message!       (fn [role content opts] (store-message! @db-info-atom role content opts))
   :get-recent-messages  (fn [limit] (get-recent-messages @db-info-atom limit))
   :count-history-tokens (fn [] (count-history-tokens @db-info-atom))
   :store-learning!      (fn
                           ([insight] (db-store-learning! @db-info-atom insight))
                           ([insight ctx] (db-store-learning! @db-info-atom insight ctx)))
   :search-learnings     (fn [query opts] (db-get-learnings @db-info-atom query opts))
   :vote-learning!       (fn [id vote] (db-vote-learning! @db-info-atom id vote))
   :learning-stats       (fn [] (db-learning-stats @db-info-atom))
   :list-documents       (fn [opts] (db-list-documents @db-info-atom opts))
   :get-document         (fn [doc-id] (db-get-document @db-info-atom doc-id))
   :search-page-nodes    (fn [query opts] (db-search-page-nodes @db-info-atom query opts))
   :get-page-node        (fn [node-id] (db-get-page-node @db-info-atom node-id))
   :list-page-nodes      (fn [opts] (db-list-page-nodes @db-info-atom opts))
   :search-toc-entries   (fn [query opts] (db-search-toc-entries @db-info-atom query opts))
   :get-toc-entry        (fn [entry-id] (db-get-toc-entry @db-info-atom entry-id))
   :list-toc-entries     (fn [opts] (db-list-toc-entries @db-info-atom opts))})

(defn create-env
  "Creates an RLM environment (component) for document ingestion and querying.
   
    The environment holds:
    - In-memory store for documents, learnings, and conversation history
    - LLM configuration for queries
    - SCI sandbox context with custom bindings
   
   Usage:
   ```clojure
   (def env (rlm/create-env {:config llm-config}))
   (rlm/register-env-fn! env 'my-fn (fn [x] (* x 2))
     {:doc \"Doubles a number\"
      :params [{:name \"x\" :type :int :required true :description \"Number to double\"}]
      :returns {:type :int :description \"x * 2\"}})
   (rlm/register-env-def! env 'MAX_RETRIES 3
     {:doc \"Maximum retry attempts\" :returns {:type :int}})
   (rlm/ingest-to-env! env documents)
   (rlm/query-env! env \"What is X?\")
   (rlm/dispose-env! env)
   ```
   
   Params:
   - :config - Required. LLM config with :providers (vector of provider maps) and :default-model.
   - :path - Optional. Path for persistent DB. If provided, data survives across sessions.
   - :persistence - Optional. Map of persistence callbacks to override defaults. Keys match
       make-default-persistence output (:store-message!, :get-recent-messages, etc.).

    Returns:
    RLM environment map (component). Pass to register-env-fn!, register-env-def!, ingest-to-env!, query-env!, dispose-env!."
  [{:keys [config path persistence]}]
  (when-not config
    (anomaly/incorrect! "Missing :config" {:type :rlm/missing-config}))
  (when-not (seq (:providers config))
    (anomaly/incorrect! "Missing :providers in config — provide at least one provider"
                        {:type :rlm/missing-providers}))
  (let [rlm-router (router/make-router (:providers config))
        depth-atom (atom 0)
        locals-atom (atom {})
        custom-bindings-atom (atom {})
        custom-docs-atom (atom [])
        db-info (create-rlm-conn path)
        db-info-atom (atom db-info)
        store (merge (make-default-persistence db-info-atom) persistence)
        hooks-atom (atom {})
        on-query-fn #(when-let [f (:on-query @hooks-atom)] (f %))
        llm-query-fn (make-routed-llm-query-fn :root depth-atom rlm-router {:on-query on-query-fn})
        sub-llm-query-fn (make-routed-llm-query-fn :sub depth-atom rlm-router {:on-query on-query-fn})
        rlm-query-fn (make-rlm-query-fn :root depth-atom rlm-router db-info-atom)
        env-id (str (java.util.UUID/randomUUID))]
    {:env-id env-id
     :config (router/sanitize-config config)
     :depth-atom depth-atom
     :locals-atom locals-atom
     :hooks-atom hooks-atom
     :custom-bindings-atom custom-bindings-atom
     :custom-docs-atom custom-docs-atom
     :db-info-atom db-info-atom
     :persistence store
     :router rlm-router
     :llm-query-fn llm-query-fn
     :sub-llm-query-fn sub-llm-query-fn
     :rlm-query-fn rlm-query-fn
     :history-enabled? true}))

(defn register-env-fn!
  "Registers a function in the RLM environment's SCI sandbox.

   The function becomes available to the LLM during code execution.
   Documentation is included in the system prompt so the LLM knows how to use it.

   Params:
   `env` - RLM environment from create-env.
   `sym` - Symbol. The function name (e.g., 'fetch-weather).
   `f` - Function. The implementation.
   `tool-def` - Map. Structured tool definition:
     - :doc - String. Description of what the function does.
     - :params - Vector of parameter maps, each with:
         :name - String. Parameter name.
         :type - Keyword. :string, :int, :keyword, :map, :vector, :any, etc.
         :required - Boolean. Whether the parameter is required (default true).
         :description - String. What the parameter is for.
         :default - Any. Default value (rendered as-is, can be a map, keyword, etc.).
     - :returns - Map. Return type description:
         {:type :map :description \"Weather data with :temp, :humidity\"}
     - :examples - Vector of strings. Usage examples.

   Usage:
   ```clojure
   (register-env-fn! env 'fetch-weather
     (fn [city & [{:keys [units]}]] ...)
     {:doc \"Fetches current weather for a city\"
      :params [{:name \"city\" :type :string :required true :description \"City name\"}
               {:name \"opts\" :type :map :required false
                :description \"Options map with :units (:celsius or :fahrenheit)\"
                :default {:units :celsius}}]
      :returns {:type :map :description \"Weather data with :temp, :humidity, :conditions\"}
      :examples [\"(fetch-weather \\\"Berlin\\\")\"
                 \"(fetch-weather \\\"Tokyo\\\" {:units :fahrenheit})\"]})
   ```

   Returns:
   The environment (for chaining)."
  [env sym f tool-def]
  (when-not (:custom-bindings-atom env)
    (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))
  (when-not (symbol? sym)
    (anomaly/incorrect! "sym must be a symbol" {:type :rlm/invalid-sym :sym sym}))
  (when-not (fn? f)
    (anomaly/incorrect! "f must be a function" {:type :rlm/invalid-fn}))
  (when-not (map? tool-def)
    (anomaly/incorrect! "tool-def must be a map" {:type :rlm/invalid-tool-def}))
  (swap! (:custom-bindings-atom env) assoc sym f)
  (swap! (:custom-docs-atom env) conj (assoc tool-def :type :fn :sym sym))
  env)

(defn register-env-def!
  "Registers a constant/value in the RLM environment's SCI sandbox.

   The value becomes available to the LLM during code execution.
   Documentation is included in the system prompt so the LLM knows about it.

   Params:
   `env` - RLM environment from create-env.
   `sym` - Symbol. The constant name (e.g., 'MAX_RETRIES).
   `value` - Any value. The constant value.
   `tool-def` - Map. Structured definition:
     - :doc - String. Description.
     - :returns - Map. Type/value description:
         {:type :int :description \"Maximum retry attempts\"}

   Returns:
   The environment (for chaining)."
  [env sym value tool-def]
  (when-not (:custom-bindings-atom env)
    (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))
  (when-not (symbol? sym)
    (anomaly/incorrect! "sym must be a symbol" {:type :rlm/invalid-sym :sym sym}))
  (when-not (map? tool-def)
    (anomaly/incorrect! "tool-def must be a map" {:type :rlm/invalid-tool-def}))
  (swap! (:custom-bindings-atom env) assoc sym value)
  (swap! (:custom-docs-atom env) conj (assoc tool-def :type :def :sym sym))
  env)

(defn ingest-to-env!
  "Ingests PageIndex documents into an RLM environment.
   
   Stores the complete document structure exactly as PageIndex produces it:
   - Document metadata
   - All pages
   - All page nodes (paragraphs, headings, images, tables)
   - All TOC entries
   
   Can be called multiple times to add more documents.
   
   Params:
   `env` - RLM environment from create-env.
   `documents` - Vector of PageIndex documents (spec-validated).
   `opts` - Optional. Map with extraction options:
     - :extract-entities? - Enable entity extraction (default false)
     - :extraction-model - Model for extraction (default: env's default-model)
     - :max-extraction-pages - Page limit per doc (default 50)
     - :max-vision-rescan-nodes - Cap on vision re-scans per doc (default 10)
   
   Returns:
   Vector of ingestion results, one per document:
   [{:document-id \"...\" :pages-stored N :nodes-stored N :toc-entries-stored N 
     :entities-extracted N :relationships-extracted N :pages-processed N 
     :extraction-errors N :visual-nodes-scanned N}] (extraction fields only if enabled)"
  ([env documents] (ingest-to-env! env documents {}))
  ([env documents opts]
   (when-not (:db-info-atom env)
     (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))
   (when-not (rlm-spec/valid-documents? documents)
     (anomaly/incorrect! "Invalid documents - must be vector of PageIndex documents"
                         {:type :rlm/invalid-documents
                          :explanation (rlm-spec/explain-documents documents)}))
   (let [db-info @(:db-info-atom env)
          rlm-router (:router env)
          extract? (:extract-entities? opts false)
          base-results (mapv #(db-store-pageindex-document! db-info %) documents)
          results (if extract?
                    (mapv (fn [doc base-result]
                            (let [extraction-result (extract-entities-from-document! db-info doc rlm-router opts)]
                              (merge base-result extraction-result)))
                          documents base-results)
                    base-results)]
      results)))

(defn dispose-env!
  "Disposes an RLM environment and releases resources.
   
    For persistent DBs (created with :path), data is preserved.
   For disposable DBs, all data is deleted.
   
   Params:
   `env` - RLM environment from create-env."
  [env]
  (when-let [db-info-atom (:db-info-atom env)]
    (dispose-rlm-conn! @db-info-atom)))

(defn query-env!
  "Runs a query on an RLM environment using iterative LLM code execution.
   
   The LLM can use these functions during execution:
   
   Document search:
    - (list-documents) - List all stored documents
    - (get-document doc-id) - Get document metadata
    - (search-page-nodes query) - List/filter actual content
    - (get-page-node node-id) - Get full page node content
    - (list-page-nodes opts) - List page nodes with filters
    - (search-toc-entries query) - List/filter table of contents
    - (get-toc-entry entry-id) - Get TOC entry
    - (list-toc-entries) - List all TOC entries
    
    History:
    - (search-history n) - Get recent messages
    - (get-history n) - Get recent messages
    
    Learnings:
    - (store-learning insight) - Store meta-insight
    - (search-learnings query) - Search learnings
    
    Params:
    `env` - RLM environment from create-env.
    `query-str` - String. The question to answer.
   `opts` - Map, optional:
     - :context - Data context to analyze.
     - :spec - Output spec for structured answers.
     - :model - Override config's default model.
     - :max-iterations - Max code iterations (default: 50).
     - :max-refinements - Max refine iterations (default: 1).
      - :threshold - Min eval score 0.0-1.0 for refinement early stop (default: 0.8).
      - :verify? - Enable claim verification with citations (default: false).
     - :refine? - Enable refinement (default: true for free-text, false when :spec is provided).
     - :learn? - Store as example (default: true).
      - :max-context-tokens - Token budget for context.
      - :debug? - Enable verbose debug logging (default: false). Logs iteration details,
        code execution, LLM responses at :info level with :rlm-phase context.
    
    Returns:
   Map with:
     - :answer - Final (possibly refined) answer string, or parsed spec data.
     - :raw-answer - Original answer before refinement.
     - :trace - Vector of iteration trace entries, each containing:
         {:iteration N
          :response \"LLM response text\"
          :executions [{:id 0 :code \"(+ 1 2)\" :result 3 :stdout \"\" :error nil :execution-time-ms 5}
                       {:id 1 :code \"(FINAL answer)\" :result {:rlm/final true ...} ...}]
          :final? boolean}
     - :iterations - Total number of iterations executed.
     - :eval-scores - Evaluation scores from refinement (if enabled).
     - :refinement-count - Number of refinement iterations.
     - :duration-ms - Total execution time in milliseconds.
     - :history-tokens - Approximate token count of conversation history.
     - :status - Only present on failure, e.g. :max-iterations."
  ([env query-str]
   (query-env! env query-str {}))
   ([env query-str {:keys [context spec model max-iterations max-refinements threshold
                            refine? learn? max-context-tokens max-recursion-depth verify?
                            plan? debug? on-iteration]
                     :or {max-iterations MAX_ITERATIONS max-refinements 1 threshold 0.8
                          learn? true max-recursion-depth DEFAULT_RECURSION_DEPTH verify? false
                          plan? false debug? false}}]
   (when-not (:db-info-atom env)
      (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))
    (when-not query-str
      (anomaly/incorrect! "Missing query" {:type :rlm/missing-query}))
    (let [refine? (if (some? refine?) refine? (nil? spec))
          rlm-router (:router env)
          ;; Resolve root model name for token counting / refine! config
          root-model (or (when rlm-router (resolve-root-model rlm-router)) model "gpt-4o")
         ;; Rebuild SCI context with current context-data and custom bindings
         locals-atom (:locals-atom env)
         db-info-atom (:db-info-atom env)
         llm-query-fn (:llm-query-fn env)
         rlm-query-fn (:rlm-query-fn env)
         sub-llm-fn (or (:sub-llm-query-fn env) (:llm-query-fn env))
         custom-bindings (when-let [atom (:custom-bindings-atom env)] @atom)
         custom-docs (when-let [atom (:custom-docs-atom env)] @atom)
          claims-atom (when verify? (atom []))
          cite-bindings (when verify?
                          {'CITE (make-cite-fn claims-atom)
                           'CITE-UNVERIFIED (make-cite-unverified-fn claims-atom)
                           'list-claims (make-list-claims-fn claims-atom)})
          cite-docs (when verify?
                     [{:type :fn :sym 'CITE :doc "(CITE claim-text document-id page section quote) or (CITE claim-text document-id page section quote confidence) - Cite a claim with source evidence. Returns {:cited true :claim-id uuid :claim-text text}"}
                      {:type :fn :sym 'CITE-UNVERIFIED :doc "(CITE-UNVERIFIED claim-text) - Record a claim without source verification. Lower confidence."}
                      {:type :fn :sym 'list-claims :doc "(list-claims) - List all claims cited so far in this query."}
                      {:type :note :sym 'CITE-PRIORITY :doc "CITE is OPTIONAL. ALWAYS call (FINAL answer) as soon as you have the answer. Only use CITE BEFORE calling FINAL if the query explicitly asks for citations. Do NOT delay FINAL to gather citations."}])
          llm-query-overrides {'llm-query sub-llm-fn
                               'llm-query-batch (fn [prompts]
                                                  (let [chs (mapv (fn [p]
                                                                    (async/thread
                                                                      (try
                                                                        (sub-llm-fn p)
                                                                        (catch Exception e
                                                                          {:error (ex-message e)}))))
                                                                  prompts)]
                                                    (mapv async/<!! chs)))}
         sci-ctx (create-sci-context context sub-llm-fn rlm-query-fn locals-atom db-info-atom
                                     (merge custom-bindings cite-bindings llm-query-overrides))
         rlm-env (assoc env :sci-ctx sci-ctx :context context)
         env-id (:env-id env)]
     (binding [*rlm-ctx* {:rlm-env-id env-id :rlm-type :main :rlm-debug? debug? :rlm-phase :query}]
       (binding [*max-recursion-depth* max-recursion-depth]
         (rlm-debug! {:query query-str :root-model root-model :max-iterations max-iterations
                        :verify? verify? :plan? plan?
                       :refine? refine?} "RLM query-env! started")
          ;; Set hooks for this query
          (when-let [ha (:hooks-atom env)]
            (reset! ha {:on-query on-iteration}))
          (let [start-time (System/nanoTime)
                examples (get-examples query-str {})
                db-info @db-info-atom
                ;; Auto-fetch relevant learnings: query-relevant + model-specific
                active-learnings (when (:conn db-info)
                                   (let [model-ctx (str "model:" (or root-model "unknown"))
                                         by-query (db-get-learnings db-info query-str
                                                    {:top-k 3 :track-usage? true})
                                         by-model (db-get-learnings db-info model-ctx
                                                    {:top-k 2 :track-usage? true})]
                                     (vec (distinct (concat by-query by-model)))))
                ;; Optional planning phase — LLM outlines approach before code execution
                plan-context (when plan?
                               (let [plan-result (router/routed-ask! rlm-router
                                                                      {:messages [(llm/system "You are a planning assistant. Given a query and available document tools, outline a clear 3-5 step approach to answer the query. Be specific about which tools to use and in what order. Do NOT write code — just describe the strategy.")
                                                                                 (llm/user (str "Query: " query-str))]}
                                                                      :planning)]
                                 (when-let [plan (:result plan-result)]
                                   (str "<plan>\n" plan "\n</plan>"))))
                 ;; iteration-loop returns {:answer :trace :iterations} or {:answer :trace :iterations :status :locals}
                iteration-result (iteration-loop rlm-env query-str nil nil nil max-iterations
                                                 {:output-spec spec
                                                  :examples examples
                                                  :learnings active-learnings
                                                  :max-context-tokens max-context-tokens
                                                  :custom-docs (into (or custom-docs []) cite-docs)
                                                  :pre-fetched-context plan-context
                                                  :on-iteration on-iteration})
               {:keys [answer trace iterations status]} iteration-result]
           (if status
               ;; Execution hit max iterations - return with trace
               (let [duration-ms (util/elapsed-since start-time)]
                 (rlm-debug! {:status status :iterations iterations :duration-ms duration-ms} "RLM query-env! finished (max iterations)")
                (cond-> {:answer nil
                        :raw-answer (:result answer answer)
                        :status status
                        :trace trace
                        :iterations iterations
                        :duration-ms duration-ms}
                  verify? (assoc :verified-claims (vec @claims-atom))))
               ;; Normal completion - refine and finalize
               (let [answer-value (:result answer answer)
                     ;; Refinement path: stringify for LLM round-trip.
                     ;; No-refine path: keep the native Clojure value from FINAL.
                     {final-answer :answer
                      eval-scores  :eval-scores
                      refinement-count :refinement-count}
                     (if refine?
                       (let [answer-as-str (answer-str answer)
                             conn (:conn db-info)
                             stored-docs (when conn
                                           (let [docs (d/q '[:find [(pull ?e [*]) ...]
                                                              :where [?e :document/id _]]
                                                            (d/db conn))]
                                             (when (seq docs)
                                               (mapv (fn [doc]
                                                       (let [doc-id (or (:document/id doc) (:document/name doc))
                                                             nodes (d/q '[:find [(pull ?e [:page.node/page-id :page.node/content]) ...]
                                                                           :in $ ?doc-id
                                                                           :where [?e :page.node/document-id ?doc-id]]
                                                                         (d/db conn) doc-id)]
                                                         {:id doc-id
                                                          :pages (mapv (fn [pn]
                                                                         {:page (or (:page.node/page-id pn) "0")
                                                                          :text (or (:page.node/content pn) "")})
                                                                       nodes)}))
                                                     docs))))
                             ;; Build a temporary config for refine! from the refinement provider
                             refine-config (when-let [[provider refine-model] (router/select-provider rlm-router :refinement)]
                                             {:api-key (:api-key provider)
                                              :base-url (:base-url provider)
                                              :default-model refine-model})
                             refine-opts (cond-> {:spec spec
                                                  :messages [(llm/system (str "You are verifying and refining an answer to a specific query. "
                                                                              "Check the answer for accuracy, completeness, and correctness."))
                                                             (llm/user (str "<query>\n" query-str "\n</query>\n\n"
                                                                            "<answer>\n" answer-as-str "\n</answer>"))]
                                                  :config refine-config :model root-model
                                                   :iterations max-refinements :threshold threshold}
                                           (seq stored-docs) (assoc :documents stored-docs))
                             raw-refine (llm/refine! refine-opts)
                             refined-str (:result raw-refine)
                             ;; If refinement didn't change the answer (converged or identical text),
                             ;; keep the original Clojure value from FINAL — no re-parse needed.
                             answer-unchanged? (or (:converged? raw-refine)
                                                   (= (str refined-str) answer-as-str))
                             parsed (if answer-unchanged?
                                      answer-value
                                      ;; Refinement modified the answer — parse the new text through spec.
                                      (if (and spec (string? refined-str))
                                        (try
                                          (spec/str->data-with-spec refined-str spec)
                                          (catch Exception _
                                            (:result (router/routed-ask! rlm-router
                                                                          {:spec spec
                                                                           :messages [(llm/system "Extract structured data.")
                                                                                      (llm/user (str "From:\n" refined-str))]}
                                                                          :sub))))
                                        refined-str))]
                         {:answer parsed
                          :eval-scores (:final-score raw-refine)
                          :refinement-count (:iterations-count raw-refine)})
                       ;; No refinement — value is Clojure from FINAL.
                       ;; When spec is provided, coerce through SAP for type correctness
                       ;; (e.g., string "pass" → keyword :pass for :spec.type/keyword fields).
                       {:answer (if spec
                                 (try (spec/coerce-data-with-spec answer-value spec)
                                      (catch Exception _ answer-value))
                                 answer-value)
                        :eval-scores nil
                        :refinement-count 0})
                    duration-ms (util/elapsed-since start-time)
                    history-tokens (count-history-tokens @db-info-atom)]
               (when (and learn? eval-scores)
                 (store-example! query-str (str-truncate (pr-str context) 200)
                                  (str final-answer) (get-in eval-scores [:total] 0) nil))
                  (when (and verify? (seq @claims-atom))
                   (let [db-info @db-info-atom
                         conn (:conn db-info)
                         query-id (UUID/randomUUID)]
                     (doseq [claim @claims-atom]
                       (try
                         (d/transact! conn [(merge claim {:claim/id (UUID/randomUUID)
                                                          :claim/query-id query-id
                                                          :claim/verified? (boolean (get claim :claim/verified? true))})])
                         (catch Exception e
                           (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Failed to store claim"}))))))
                 (rlm-debug! {:iterations iterations :duration-ms duration-ms
                               :refinement-count refinement-count
                               :answer-preview (str-truncate (pr-str final-answer) 200)} "RLM query-env! finished (success)")
                  (cond-> {:answer final-answer
                          :raw-answer answer-value
                          :eval-scores eval-scores
                          :refinement-count refinement-count
                          :trace trace
                          :iterations iterations
                          :duration-ms duration-ms
                          :history-tokens history-tokens}
                    verify? (assoc :verified-claims (vec @claims-atom)))))))))))


;; =============================================================================
;; Trace Pretty Printing
;; =============================================================================

(defn- format-trace
  "Formats an RLM execution trace into a string. Internal helper."
  [trace {:keys [max-response-length max-code-length max-result-length show-stdout?]
          :or {max-response-length 500 max-code-length 300 max-result-length 200
               show-stdout? true}}]
  (let [truncate (fn [s n] (if (and s (> (count s) n)) (str (subs s 0 n) "...") s))
        format-execution (fn [{:keys [id code result stdout error execution-time-ms]}]
                           (str "║ [" id "] "
                                (truncate (str/replace (or code "") #"\n" " ") max-code-length) "\n"
                                "║     "
                                (if error
                                  (str "ERROR: " error)
                                  (str "=> " (truncate (pr-str result) max-result-length)))
                                (when execution-time-ms (str " (" execution-time-ms "ms)"))
                                (when (and show-stdout? stdout (not (str/blank? stdout)))
                                  (str "\n║     STDOUT: " (truncate stdout max-result-length)))))
        format-iteration (fn [{:keys [iteration response executions final?]}]
                           (str "\n"
                                "╔══════════════════════════════════════════════════════════════════════════════\n"
                                "║ ITERATION " iteration (when final? " [FINAL]") "\n"
                                "╠══════════════════════════════════════════════════════════════════════════════\n"
                                "║ RESPONSE:\n"
                                "║ " (str/replace (truncate response max-response-length) #"\n" "\n║ ") "\n"
                                (when (seq executions)
                                  (str "╠──────────────────────────────────────────────────────────────────────────────\n"
                                       "║ EXECUTIONS (" (count executions) "):\n"
                                       (str/join "\n"
                                                 (map format-execution executions))
                                       "\n"))
                                "╚══════════════════════════════════════════════════════════════════════════════"))]
    (if (empty? trace)
      "No trace entries."
      (str "RLM EXECUTION TRACE (" (count trace) " iterations)\n"
           (str/join "\n" (map format-iteration trace))))))

(defn pprint-trace
  "Pretty-prints an RLM execution trace to stdout for debugging.

   Prints the formatted trace to *out* and returns the formatted string.

   Params:
   `trace` - Vector of trace entries from query-env! result.
   `opts` - Map, optional:
     - :max-response-length - Truncate LLM response (default: 500).
     - :max-code-length - Truncate code blocks (default: 300).
     - :max-result-length - Truncate execution results (default: 200).
     - :show-stdout? - Show stdout output (default: true).

   Returns:
   String with formatted trace output (also printed to stdout)."
  ([trace] (pprint-trace trace {}))
  ([trace opts]
   (let [s (format-trace trace opts)]
     (println s)
     s)))

(defn print-trace
  "Prints an RLM execution trace to stdout. Alias for pprint-trace."
  ([trace] (pprint-trace trace))
  ([trace opts] (pprint-trace trace opts)))

;; =============================================================================
;; generate-qa-env! - Multi-stage Q&A generation from ingested documents
;; =============================================================================

;; -- Bloom's taxonomy difficulty levels --
(def ^:private BLOOM_DIFFICULTIES
  "Bloom's taxonomy cognitive levels as difficulty progression."
  {"remember"    "Simple recall of facts, definitions, or terms directly stated in the text"
   "understand"  "Explain concepts, summarize, paraphrase, or interpret meaning from the text"
   "apply"       "Use information from the text to solve a new problem or scenario"
   "analyze"     "Break down information, identify patterns, compare elements across sections"
   "evaluate"    "Judge, assess, or critique claims, arguments, or evidence from the text"
   "create"      "Synthesize information from multiple parts to form a new conclusion or insight"})

(def ^:private QUESTION_CATEGORIES
  "Question type categories."
  {"factual"      "Direct fact extraction — answer is explicitly stated"
   "inferential"  "Requires reasoning from stated facts to reach the answer"
   "comparative"  "Compares or contrasts two or more concepts, entities, or processes"
   "analytical"   "Requires breaking down complex information or identifying relationships"
   "definitional" "Asks for definitions, explanations, or descriptions of concepts"
   "procedural"   "Asks about processes, steps, methods, or how something works"})

(def ^:private GENERATION_PERSONAS
  "Persona descriptions to diversify question styles."
  {:student     "You are a curious undergraduate student studying this material for the first time. Ask questions that test foundational understanding and clarify key concepts."
   :researcher  "You are an academic researcher looking for precise details and methodological rigor. Ask technical, specific questions that require careful reading."
   :practitioner "You are a working professional who needs to apply this knowledge. Ask practical, application-oriented questions about how to use the information."
   :examiner    "You are a rigorous exam designer creating assessment questions. Ask questions that test deep comprehension and the ability to distinguish subtle details."
   :journalist  "You are an investigative journalist looking for the most important claims and evidence. Ask questions that probe key findings, numbers, and conclusions."})

;; -- Specs --

(def ^:private QUESTION_SPEC
  "Spec for a single generated question-answer pair."
  (spec/spec
   :question
   {::spec/key-ns "question"}
   (spec/field {::spec/name :question
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "The question text — must be self-contained and understandable without the source document"})
   (spec/field {::spec/name :answer
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "The answer, grounded in source material"})
   (spec/field {::spec/name :evidence-span
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Exact verbatim quote from the source document that supports the answer"})
   (spec/field {::spec/name :source-document
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Source document ID"})
   (spec/field {::spec/name :source-page
                ::spec/type :spec.type/int
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Source page number (0-based)"})
   (spec/field {::spec/name :source-section
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/required false
                ::spec/description "Source section or heading title"})
   (spec/field {::spec/name :difficulty
                ::spec/type :spec.type/keyword
                ::spec/cardinality :spec.cardinality/one
                ::spec/values BLOOM_DIFFICULTIES
                ::spec/description "Bloom's taxonomy cognitive level"})
   (spec/field {::spec/name :category
                ::spec/type :spec.type/keyword
                ::spec/cardinality :spec.cardinality/one
                ::spec/values QUESTION_CATEGORIES
                ::spec/description "Question category"})))

(def ^:private QUESTIONIFY_SPEC
  "Spec for generate-qa-env! Q&A generation output."
  (spec/spec
   {:refs [QUESTION_SPEC]}
   (spec/field {::spec/name :questions
                ::spec/type :spec.type/ref
                ::spec/target :question
                ::spec/cardinality :spec.cardinality/many
                ::spec/description "Generated question-answer pairs"})))

(def ^:private PASSAGE_SPEC
  "Spec for a selected passage from Phase 1."
  (spec/spec
   :passage
   {::spec/key-ns "passage"}
   (spec/field {::spec/name :document-id
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Document ID"})
   (spec/field {::spec/name :page
                ::spec/type :spec.type/int
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Page number (0-based)"})
   (spec/field {::spec/name :section-title
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Section or heading title"})
   (spec/field {::spec/name :content-summary
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Brief summary of what this passage covers (1-2 sentences)"})
   (spec/field {::spec/name :suggested-difficulty
                ::spec/type :spec.type/keyword
                ::spec/cardinality :spec.cardinality/one
                ::spec/values BLOOM_DIFFICULTIES
                ::spec/description "Suggested Bloom's taxonomy difficulty level for questions from this passage"})
   (spec/field {::spec/name :suggested-category
                ::spec/type :spec.type/keyword
                ::spec/cardinality :spec.cardinality/one
                ::spec/values QUESTION_CATEGORIES
                ::spec/description "Suggested question category for this passage"})))

(def ^:private CHUNK_SELECTION_SPEC
  "Spec for Phase 1 passage selection output."
  (spec/spec
   {:refs [PASSAGE_SPEC]}
   (spec/field {::spec/name :passages
                ::spec/type :spec.type/ref
                ::spec/target :passage
                ::spec/cardinality :spec.cardinality/many
                ::spec/description "Selected passages for Q&A generation"})))

(def ^:private VERIFICATION_RESULT_SPEC
  "Spec for a single verification result."
  (spec/spec
   :verification
   {::spec/key-ns "verification"}
   (spec/field {::spec/name :question-index
                ::spec/type :spec.type/int
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Index of the question being verified (0-based)"})
   (spec/field {::spec/name :grounded
                ::spec/type :spec.type/bool
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Whether the evidence span actually exists in the source and supports the answer"})
   (spec/field {::spec/name :non-trivial
                ::spec/type :spec.type/bool
                ::spec/cardinality :spec.cardinality/one
                ::spec/description "Whether the question requires reading the document — not answerable from titles or headings alone"})
    (spec/field {::spec/name :self-contained
                 ::spec/type :spec.type/bool
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Whether the question is understandable without the source document context"})
    (spec/field {::spec/name :answerable
                 ::spec/type :spec.type/bool
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Whether the question can be answered from the evidence span alone, without external knowledge"})
    (spec/field {::spec/name :answer-consistent
                 ::spec/type :spec.type/bool
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Whether the provided answer accurately matches the question's intent and the evidence"})
    (spec/field {::spec/name :verdict
                ::spec/type :spec.type/keyword
                ::spec/cardinality :spec.cardinality/one
                ::spec/values {"pass" "Question meets all quality criteria"
                               "fail" "Question has fundamental issues and should be dropped"
                               "needs-revision" "Question has minor issues but contains value"}
                ::spec/description "Verification verdict"})
   (spec/field {::spec/name :revision-note
                ::spec/type :spec.type/string
                ::spec/cardinality :spec.cardinality/one
                ::spec/required false
                ::spec/description "Explanation of issues if verdict is not pass"})))

(def ^:private VERIFICATION_SPEC
  "Spec for Phase 3 verification output."
  (spec/spec
   {:refs [VERIFICATION_RESULT_SPEC]}
   (spec/field {::spec/name :verifications
                ::spec/type :spec.type/ref
                ::spec/target :verification
                ::spec/cardinality :spec.cardinality/many
                ::spec/description "Verification results for each question"})))

;; -- Prompt builders --

(defn- build-chunk-selection-prompt
  "Builds prompt for Phase 1: diverse passage selection from the corpus.
   Legacy version using SCI code execution — kept for fallback."
  [{:keys [count difficulty-dist category-dist]}]
  (let [difficulties (str/join ", " (map name difficulty-dist))
        categories (str/join ", " (map name category-dist))]
    (str "You are selecting diverse passages from the ingested documents for question-answer generation.

YOUR TASK: Select exactly " count " passages that will serve as source material for generating Q&A pairs.
Each passage should come from a distinct section of the corpus to maximize coverage.

STEP-BY-STEP INSTRUCTIONS:
1. First call (list-documents) to see all available documents with their abstracts and TOC
2. Call (list-toc-entries) to understand the full document structure
3. For each document, use (search-page-nodes nil 20 {:document-id doc-id}) to browse content
4. Select " count " passages from DIFFERENT sections, covering the breadth of the corpus

DIVERSITY REQUIREMENTS:
- If multiple documents exist, select from ALL of them proportionally
- Within each document, spread selections across different chapters/sections
- Avoid selecting adjacent paragraphs — skip at least 2-3 pages between selections
- Cover different content types: definitions, processes, examples, data, arguments
- Assign difficulty levels using round-robin across: " difficulties "
- Assign categories using round-robin across: " categories "

WHAT MAKES A GOOD PASSAGE:
- Contains substantive information (not just headers, footers, or boilerplate)
- Has enough detail to generate a meaningful question AND answer
- The content-summary should capture what makes this passage interesting for Q&A

WHAT MAKES A BAD PASSAGE:
- Table of contents or index pages
- Pages with only images and no descriptive text
- Extremely short content (less than 2 sentences)
- Repeated/boilerplate content (headers, footers, page numbers)

After exploring the corpus, call (FINAL {:passages [...]}) with your selected passages.")))

(defn- build-toc-based-selection-prompt
  "Builds prompt for Phase 1 fast-model selection using pre-gathered TOC data.
   All corpus structure is provided inline — no SCI loop needed."
  [{:keys [count difficulty-dist category-dist documents toc-entries page-nodes]}]
  (let [difficulties (str/join ", " (map name difficulty-dist))
        categories (str/join ", " (map name category-dist))
        ;; Format document overview
        docs-section
        (str/join "\n"
                  (map (fn [doc]
                         (str "- " (or (:document/title doc) (:document/name doc))
                              " (ID: " (:document/id doc) ")"
                              (when-let [abstract (:document/abstract doc)]
                                (str "\n  Abstract: " (str-truncate abstract 300)))))
                       documents))
        ;; Format TOC — indented by level for structure
        toc-section
        (str/join "\n"
                  (map (fn [e]
                         (let [level-num (let [l (:document.toc/level e)]
                                          (if (string? l)
                                            (parse-long (re-find #"\d+" (str l)))
                                            (or l 0)))
                               indent (str/join (repeat (min 4 (or level-num 0)) "  "))]
                           (str indent "- [" (:document.toc/id e) " p" (:document.toc/target-page e) "] "
                                (:document.toc/title e)
                                (when-let [desc (:document.toc/description e)]
                                  (str " — " (str-truncate desc 100))))))
                       toc-entries))
        ;; Format page content summaries — grouped by document
        content-section
        (str/join "\n"
                  (->> page-nodes
                       (filter #(or (not-empty (:page.node/content %))
                                    (not-empty (:page.node/description %))))
                       (map (fn [node]
                              (str "  [" (:page.node/document-id node)
                                   " p" (:page.node/page-id node)
                                   " " (name (or (:page.node/type node) :unknown)) "] "
                                   (or (not-empty (:page.node/content node))
                                       (:page.node/description node)))))))]
    (str "You are selecting diverse passages from a document corpus for question-answer generation.

YOUR TASK: Select exactly " count " passages that will serve as source material for generating Q&A pairs.
Each passage should come from a distinct section of the corpus to maximize coverage.

AVAILABLE DOCUMENTS:
" docs-section "

TABLE OF CONTENTS:
" (if (seq toc-section) toc-section "(no TOC entries)") "

CONTENT SUMMARIES (truncated previews):
" (if (seq content-section) content-section "(no content previews)") "

SELECTION CRITERIA:
- If multiple documents exist, select from ALL of them proportionally
- Within each document, spread selections across different chapters/sections
- Avoid selecting adjacent pages — skip at least 2-3 pages between selections
- Cover different content types: definitions, processes, examples, data, arguments
- Assign difficulty levels using round-robin across: " difficulties "
- Assign categories using round-robin across: " categories "

SKIP passages that are:
- Table of contents or index pages
- Pages with only images and no descriptive text
- Extremely short content (less than 2 sentences)
- Repeated/boilerplate content (headers, footers, page numbers)

For each passage, provide:
- document-id: The document ID from the list above
- page: The 0-based page number
- section-title: The section or heading title from the TOC
- content-summary: A 1-2 sentence description of what makes this passage suitable for Q&A
- suggested-difficulty: One of " difficulties "
- suggested-category: One of " categories)))

(defn- build-generation-prompt
  "Builds prompt for Phase 2: Q&A generation from selected passages.
   Supports optional persona styling, k-candidates selection, and multi-hop mode."
  [passages batch-index {:keys [persona k-candidates multi-hop?]}]
  (let [passage-descriptions
        (str/join "\n\n"
                  (map-indexed
                   (fn [i p]
                     (str "PASSAGE " (inc i) ":\n"
                          "  Document: " (:document-id p) "\n"
                          "  Page: " (:page p) "\n"
                          "  Section: " (:section-title p) "\n"
                          "  Summary: " (:content-summary p) "\n"
                          "  Target difficulty: " (name (or (:suggested-difficulty p) :understand)) "\n"
                          "  Target category: " (name (or (:suggested-category p) :factual))))
                   passages))
        k (or k-candidates 1)
        per-passage-count (if (> k 1)
                            (str "Generate " k " candidate Q&A pairs per passage. "
                                 "Rate each candidate 1-5 on quality (groundedness, clarity, specificity). "
                                 "Then keep only the BEST candidate per passage in your final output.")
                            "Generate 1-2 Q&A pairs per passage.")
        persona-instruction (when persona
                              (str "\n\nPERSONA: " (get GENERATION_PERSONAS persona
                                                        (str "You are " (name persona) ". Ask questions from this perspective.")) "\n"))
        multi-hop-instruction (when multi-hop?
                                "\n\nMULTI-HOP QUESTIONS: Some passages are paired across different sections. For paired passages, generate questions that REQUIRE information from BOTH passages to answer — the answer should synthesize facts from multiple sources. Mark these as 'analyze' or 'create' difficulty.\n")]
    (str "You are generating high-quality question-answer pairs from specific passages in the corpus.
This is batch " batch-index ". " per-passage-count
(or persona-instruction "")
(or multi-hop-instruction "") "

PASSAGES TO PROCESS:
" passage-descriptions "

STEP-BY-STEP INSTRUCTIONS:
1. For each passage above, search for its actual content:
   (search-page-nodes \"<key terms from section>\" 5 {:document-id \"<doc-id>\"})
   or (list-page-nodes {:document-id \"<doc-id>\" :page-id \"<page>\"})
2. Read the full content of relevant page nodes using (get-page-node node-id)
3. " per-passage-count "

CRITICAL REQUIREMENTS FOR EACH Q&A PAIR:
- question: Self-contained, understandable WITHOUT seeing the document. Never reference
  'the document', 'the text', 'this section', 'the author'. A reader should understand
  what is being asked without any context.
- answer: Accurate, grounded in the source text. Should be 1-3 sentences.
- evidence-span: A VERBATIM QUOTE from the source document. Copy the exact text.
  This is NOT a paraphrase — it must be findable in the document word-for-word.
  Keep it to 1-3 sentences maximum.
- source-document: The document ID from list-documents
- source-page: The page number (0-based) where the evidence appears
- source-section: The section heading (if known)
- difficulty: Use the suggested difficulty from the passage description
- category: Use the suggested category from the passage description

EXAMPLES OF GOOD QUESTIONS:
  Q: What is the minimum capitalization requirement for banks under Basel III?
  A: Banks must maintain a minimum Common Equity Tier 1 ratio of 4.5% of risk-weighted assets.
  evidence-span: \"Banks are required to maintain a minimum CET1 ratio of 4.5 percent of risk-weighted assets\"

EXAMPLES OF BAD QUESTIONS (DO NOT GENERATE THESE):
  BAD: What does this document say about banks? (references 'this document')
  BAD: What is discussed in Section 3? (references section numbers)
  BAD: What is Basel III? (answerable without the document — too generic)
  BAD: According to the text, what is mentioned? (references 'the text')

After generating all Q&A pairs, call (FINAL {:questions [...]}).")))

(defn- create-multi-hop-pairs
  "Creates multi-hop passage pairs from selected passages.
   Pairs passages from different sections/documents for cross-reference questions."
  [passages]
  (when (>= (clojure.core/count passages) 2)
    (let [;; Group by document
          by-doc (group-by :document-id passages)
          pairs (atom [])
          passage-vec (vec passages)]
      ;; Cross-document pairs (highest value)
      (when (> (clojure.core/count by-doc) 1)
        (let [doc-ids (vec (keys by-doc))]
          (doseq [i (range (min 3 (dec (clojure.core/count doc-ids))))]
            (let [p1 (first (get by-doc (nth doc-ids i)))
                  p2 (first (get by-doc (nth doc-ids (inc i))))]
              (when (and p1 p2)
                (swap! pairs conj [p1 p2]))))))
      ;; Within-document pairs from different sections (skip adjacent pages)
      (doseq [[_doc-id doc-passages] by-doc]
        (let [sorted (vec (sort-by :page doc-passages))]
          (doseq [i (range (dec (clojure.core/count sorted)))]
            (let [p1 (nth sorted i)
                  p2 (nth sorted (min (+ i 2) (dec (clojure.core/count sorted))))]
              (when (and p1 p2 (not= p1 p2)
                         (> (Math/abs (- (:page p2) (:page p1))) 1))
                (swap! pairs conj [p1 p2]))))))
      ;; Return up to 3 pairs
      (vec (take 3 @pairs)))))
(defn- build-verification-prompt
  "Builds prompt for Phase 3: verify Q&A pairs against source material."
  [questions]
  (let [question-descriptions
        (str/join "\n\n"
                  (map-indexed
                   (fn [i q]
                     (str "QUESTION " i " (index " i "):\n"
                          "  Q: " (:question q) "\n"
                          "  A: " (:answer q) "\n"
                          "  Evidence: " (str-truncate (or (:evidence-span q) "") 200) "\n"
                          "  Source: " (:source-document q) " page " (:source-page q)))
                   questions))]
    (str "You are a quality auditor verifying question-answer pairs against source documents.
For each Q&A pair below, verify it meets quality standards.

Q&A PAIRS TO VERIFY:
" question-descriptions "

FOR EACH QUESTION, PERFORM THESE CHECKS:
1. GROUNDED: Search for the evidence-span in the source document:
   (search-page-nodes \"<key phrase from evidence>\" 5 {:document-id \"<doc-id>\"})
   Does the evidence span actually exist (or closely match) in the document? Does it support the answer?

2. NON-TRIVIAL: Is this question meaningful? Would answering it require actually reading the document?
   FAIL if: the question just asks 'What is [heading text]?' or could be answered by reading only titles.

3. SELF-CONTAINED: Can someone understand this question without seeing the source document?
   FAIL if: the question references 'the document', 'this section', 'the text', 'the author' etc.

4. ANSWERABLE: Can the question be answered solely from the evidence span provided?
   The evidence should contain sufficient information to derive the answer without external knowledge.
   FAIL if: the answer requires facts not present in the evidence span.

5. ANSWER-CONSISTENT: Does the provided answer accurately match what the question asks?
   The answer should directly address the question's intent and be supported by the evidence.
   FAIL if: the answer addresses a different aspect, misinterprets the question, or contradicts the evidence.

VERDICT CRITERIA:
- pass: All five checks pass
- fail: Evidence is fabricated/hallucinated, question is trivially bad, OR answer contradicts evidence
- needs-revision: Minor issues (e.g., evidence is paraphrased rather than verbatim, but answer is correct)

After verifying all questions, call (FINAL {:verifications [...]}).
Each verification must include: question-index, grounded, non-trivial, self-contained, answerable, answer-consistent, verdict, and revision-note (if applicable).")))

;; -- Utility functions --

(defn- compute-distribution
  "Computes target counts per item, distributing total-count evenly across items."
  [total-count items]
  (let [item-vec (vec items)
        n (clojure.core/count item-vec)
        base (quot total-count n)
        remainder (rem total-count n)]
    (into {} (map-indexed (fn [i item]
                            [item (if (< i remainder) (inc base) base)])
                          item-vec))))

(def ^:private DEDUP_SPEC
  "Spec for LLM-based semantic deduplication output."
  (spec/spec
   (spec/field {::spec/name :keep-indices
                ::spec/type :spec.type/int
                ::spec/cardinality :spec.cardinality/many
                ::spec/description "0-based indices of questions to KEEP — one per semantic group, choosing the highest quality version"})))

(defn- dedup-batch
  "Deduplicates a single batch of questions via LLM. Returns kept questions."
  [questions config model]
  (let [numbered-list
        (str/join "\n"
                  (map-indexed
                   (fn [i q]
                     (str "[" i "] " (:question q)))
                   questions))
        result (llm/ask!
                {:config config
                 :spec DEDUP_SPEC
                 :messages
                 [(llm/system "You are a deduplication engine. Given a numbered list of questions, identify semantic duplicates — questions that ask the same thing in different words. For each group of duplicates, keep only the BEST version (most clear, specific, and well-phrased). Return the 0-based indices of questions to KEEP.")
                  (llm/user (str "Identify and remove semantic duplicates from this list. Return indices of questions to KEEP (one per duplicate group, choosing the best phrasing):\n\n" numbered-list))]
                 :model model})
        keep-indices (set (or (:keep-indices (:result result)) []))
        kept (vec (keep-indexed
                   (fn [i q]
                     (when (contains? keep-indices i)
                       q))
                   questions))]
    ;; Fallback: if LLM returns empty or error, keep all
    (if (seq kept) kept questions)))

(def ^:private DEDUP_WINDOW_SIZE
  "Max questions per dedup LLM call to avoid context overload."
  20)

(defn- deduplicate-questions
  "Removes semantically duplicate questions using LLM judgment.

   Processes in sliding windows of 20 to avoid overwhelming the LLM
   with too many questions in a single call. When there are multiple
   windows, a final cross-window pass catches duplicates across windows.

   Params:
   `questions` - Vector of question maps with :question key.
   `config` - svar config map with :api-key etc.
   `model` - String. Model to use for dedup.

   Returns:
   Vector of unique questions."
  [questions config model]
  (if (<= (clojure.core/count questions) 1)
    questions
    (let [total (clojure.core/count questions)
          ;; Small batch: single pass
          kept (if (<= total DEDUP_WINDOW_SIZE)
                 (dedup-batch questions config model)
                 ;; Large batch: process in windows, then cross-window pass
                 (let [windows (partition-all DEDUP_WINDOW_SIZE questions)
                       per-window (vec (mapcat #(dedup-batch (vec %) config model) windows))]
                   ;; Cross-window dedup on accumulated results
                   (if (> (clojure.core/count per-window) 1)
                     (dedup-batch per-window config model)
                     per-window)))
          dropped-count (- total (clojure.core/count kept))]
      (when (pos? dropped-count)
        (trove/log! {:level :info :id ::qa-dedup
                     :data {:original total
                            :kept (clojure.core/count kept)
                            :dropped dropped-count}
                     :msg "LLM deduplication complete"}))
      kept)))

(def ^:private REVISION_SPEC
  "Spec for revising questions that need improvement."
  (spec/spec
   {:refs [QUESTION_SPEC]}
   (spec/field {::spec/name :questions
                ::spec/type :spec.type/ref
                ::spec/target :question
                ::spec/cardinality :spec.cardinality/many
                ::spec/description "Revised question-answer pairs"})))

(defn- revise-questions
  "Revises questions that received needs-revision verdict.

   Takes questions with attached revision notes and asks the LLM to fix
   the identified issues while preserving the core content.

   Params:
   `questions` - Vector of question maps with :revision-note key.
   `config` - svar config map.
   `model` - String. Model to use.

   Returns:
   Vector of revised question maps (without :revision-note)."
  [questions config model]
  (if (empty? questions)
    []
    (let [revision-descriptions
          (str/join "\n\n"
                    (map-indexed
                     (fn [i q]
                       (str "QUESTION " i ":\n"
                            "  Q: " (:question q) "\n"
                            "  A: " (:answer q) "\n"
                            "  Evidence: " (str-truncate (or (:evidence-span q) "") 200) "\n"
                            "  Source: " (:source-document q) " page " (:source-page q) "\n"
                            "  Issue: " (or (:revision-note q) "Minor quality issue")))
                     questions))
          result (llm/ask!
                  {:config config
                   :spec REVISION_SPEC
                   :messages
                   [(llm/system "You are a question revision engine. Given Q&A pairs with identified issues, fix the problems while preserving the core question intent, answer accuracy, and evidence grounding. Keep the same source-document, source-page, difficulty, and category. Fix only the identified issue.")
                    (llm/user (str "Revise these questions to fix the identified issues:\n\n" revision-descriptions))]
                   :model model})
          revised (or (:questions (:result result)) [])]
      (trove/log! {:level :info :id ::qa-revision
                   :data {:input (clojure.core/count questions)
                          :revised (clojure.core/count revised)}
                   :msg "Question revision complete"})
      ;; Fallback: if revision fails, return originals without revision-note
      (if (seq revised)
        revised
        (mapv #(dissoc % :revision-note) questions)))))

(defn- filter-verified-questions
  "Splits questions into passed/needs-revision/dropped based on verification results."
  [questions verifications]
  (let [;; Pad verifications with :pass for any missing indices
        ver-map (into {} (map (fn [v] [(:question-index v) v]) verifications))
        results (map-indexed
                 (fn [i q]
                   (let [v (get ver-map i {:verdict :pass})
                         verdict (:verdict v)]
                     (when-not (= :pass verdict)
                       (trove/log! {:level :debug :id ::qa-filter
                                    :data {:index i :verdict verdict :note (:revision-note v)
                                           :question (str-truncate (:question q) 100)}
                                    :msg "Question failed verification"}))
                     {:question q :verification v
                      :passed? (= :pass verdict)
                      :needs-revision? (= :needs-revision verdict)}))
                 questions)]
    {:passed (mapv :question (filter :passed? results))
     :needs-revision (mapv (fn [r] (assoc (:question r) :revision-note (get-in r [:verification :revision-note])))
                           (filter :needs-revision? results))
     :dropped (mapv :question (filter #(and (not (:passed? %)) (not (:needs-revision? %))) results))
     :results (mapv :verification results)}))

;; -- Main pipeline --

(defn- fork-env-for-query
  "Creates a copy of the env with a fresh locals-atom for parallel query-env! calls.
   Shares immutable config, db-info-atom, and query functions. Isolates mutable locals
   so concurrent queries don't clobber each other's SCI variables."
  [env]
  (assoc env :locals-atom (atom {})))

(defn generate-qa-env!
  "Generates question-answer pairs from ingested documents.

   Uses a multi-stage pipeline leveraging the RLM's iterative code execution:

   Phase 1 - Passage Selection: Explores the corpus structure via TOC and content
   search, selects diverse passages covering different sections and topics.

   Phase 2 - Q&A Generation: For each batch of selected passages, generates
   grounded question-answer pairs with evidence spans extracted from source text.

   Phase 3 - Verification: Each Q&A pair is verified against the source material
   for groundedness, non-triviality, and self-containedness.

   Phase 4 - Deduplication: Near-duplicate questions are removed and diversity
   across difficulty levels and categories is verified.

   Params:
   `env` - RLM environment from create-env with ingested documents.
   `opts` - Map, optional:
     - :count - Integer. Target number of Q&A pairs (default: 10).
     - :difficulty - Set of keywords. Bloom's taxonomy levels to include
       (default: #{:remember :understand :apply :analyze :evaluate :create}).
     - :categories - Set of keywords. Question types to include
       (default: #{:factual :inferential :comparative :analytical :definitional :procedural}).
     - :model - String. Override default model.
      - :batch-size - Integer. Passages per generation batch (default: 5).
      - :parallel - Integer. Number of parallel batch workers for Phase 2 (default: 3).
      - :selection-model - String. Fast/cheap model for Phase 1 passage selection (default: :model).
      - :k-candidates - Integer. Generate k candidates per passage, keep best (default: 1).
      - :multi-hop? - Boolean. Generate cross-section questions from passage pairs (default: false).
      - :personas - Set of keywords. Persona styles to rotate across batches for diversity.
        Available: :student, :researcher, :practitioner, :examiner, :journalist (default: nil).
      - :verify? - Boolean. Run verification phase (default: true).
      - :debug? - Boolean. Verbose logging (default: false).

   Returns:
   Map with:
     - :questions - Vector of verified Q&A maps, each with :question, :answer,
       :evidence-span, :source-document, :source-page, :source-section,
       :difficulty, :category.
     - :dropped-questions - Vector of Q&A maps that failed verification.
     - :verification-results - Vector of verification result maps.
     - :phase-traces - Map of {:selection :generation :verification} traces.
     - :stats - Map with :total-generated, :passed-verification, :duplicates-removed,
       :final-count, :by-difficulty (counts), :by-category (counts).
     - :iterations - Total iterations across all phases.
     - :duration-ms - Total execution time."
  ([env] (generate-qa-env! env {}))
   ([env {:keys [count difficulty categories model batch-size verify? debug? parallel
                 selection-model k-candidates multi-hop? personas]
          :or {count 10
               difficulty #{:remember :understand :apply :analyze :evaluate :create}
               categories #{:factual :inferential :comparative :analytical :definitional :procedural}
               batch-size 5
               verify? true
               debug? false
               parallel 3
               k-candidates 1}}]
   (when-not (:db-info-atom env)
     (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))

   (let [start-time (System/nanoTime)
         config (:config env)
         effective-model (or model (:default-model config))
         ;; Select 1.5x passages for filtering headroom
         passage-count (int (Math/ceil (* count 1.5)))

          ;; ===== PHASE 1: Passage Selection (fast-model TOC routing) =====
          effective-selection-model (or selection-model effective-model)
          db-info @(:db-info-atom env)
          _ (trove/log! {:level :info :id ::qa-phase1
                         :data {:target-passages passage-count :target-questions count
                                :selection-model effective-selection-model}
                         :msg "Phase 1: Selecting passages via fast-model TOC routing"})
          ;; Gather corpus structure programmatically (no SCI loop)
          corpus-documents (db-list-documents db-info)
          corpus-toc (db-list-toc-entries db-info)
          corpus-nodes (db-list-page-nodes db-info {:limit 500})
          selection-prompt (build-toc-based-selection-prompt
                            {:count passage-count
                             :difficulty-dist difficulty
                             :category-dist categories
                             :documents corpus-documents
                             :toc-entries corpus-toc
                             :page-nodes corpus-nodes})
          selection-result (llm/ask! {:config config
                                      :spec CHUNK_SELECTION_SPEC
                                      :messages [(llm/system "You are a passage selection engine for Q&A generation. Select diverse passages from the corpus based on the provided structure. Return your selections in the required JSON format.")
                                                 (llm/user selection-prompt)]
                                      :model effective-selection-model})
          passages (or (:passages (:result selection-result)) [])
          _ (trove/log! {:level :info :id ::qa-phase1-done
                         :data {:passages-selected (clojure.core/count passages)
                                :model-used effective-selection-model}
                         :msg "Phase 1 complete"})

          ;; ===== PHASE 2: Batched Q&A Generation (parallel via core.async) =====
          ;; Prepare persona rotation
          persona-vec (when (seq personas) (vec personas))
          ;; Add multi-hop passage pairs if enabled
          multi-hop-pairs (when multi-hop? (create-multi-hop-pairs passages))
          _ (trove/log! {:level :info :id ::qa-phase2
                         :data {:passages (clojure.core/count passages) :batch-size batch-size
                                 :parallel parallel
                                :k-candidates k-candidates
                                :multi-hop-pairs (clojure.core/count (or multi-hop-pairs []))
                                :personas (when persona-vec (mapv name persona-vec))}
                         :msg "Phase 2: Generating Q&A pairs in parallel batches"})
          ;; Build standard batches from passages
          standard-batches (vec (partition-all batch-size passages))
          ;; Add multi-hop batches (pairs flattened into batches)
          multi-hop-batches (when (seq multi-hop-pairs)
                              (vec (partition-all batch-size (mapcat identity multi-hop-pairs))))
          all-batches (into standard-batches (or multi-hop-batches []))
          batch-count (clojure.core/count all-batches)
          standard-batch-count (clojure.core/count standard-batches)
          work-items (map-indexed
                      (fn [idx batch]
                        (let [is-multi-hop? (>= idx standard-batch-count)
                              persona (when persona-vec
                                        (nth persona-vec (mod idx (clojure.core/count persona-vec))))]
                          {:batch-idx idx :batch (vec batch)
                           :persona persona :multi-hop? is-multi-hop?
                           :k-candidates k-candidates}))
                      all-batches)
          result-chan (async/chan batch-count)
          _ (async/pipeline-blocking
               parallel

              result-chan
              (map (fn [{:keys [batch-idx batch persona multi-hop? k-candidates]}]
                     (trove/log! {:level :debug :id ::qa-batch
                                  :data {:batch batch-idx :passages-in-batch (clojure.core/count batch)
                                         :persona (when persona (name persona))
                                         :multi-hop? multi-hop?}
                                  :msg (str "Generating batch " batch-idx)})
                     (try
                       (let [forked-env (fork-env-for-query env)
                             prompt (build-generation-prompt batch batch-idx
                                      {:persona persona
                                       :k-candidates k-candidates
                                       :multi-hop? multi-hop?})
                             result (query-env! forked-env prompt
                                            {:spec QUESTIONIFY_SPEC
                                             :refine? false
                                             :learn? false
                                             :debug? debug?
                                             :max-iterations 20
                                             :model effective-model})]
                         {:batch-idx batch-idx
                          :questions (or (get-in result [:answer :questions]) [])
                          :trace (:trace result)
                          :iterations (or (:iterations result) 0)})
                       (catch Exception e
                         (trove/log! {:level :error :id ::qa-batch-error
                                      :data {:batch batch-idx :error (ex-message e)}
                                      :msg "Batch generation failed"})
                         {:batch-idx batch-idx
                          :questions []
                          :trace []
                          :iterations 0}))))
              (async/to-chan! work-items))
          ;; Collect results from pipeline and sort by batch index for deterministic order
          generation-results (let [results (loop [acc []]
                                             (if-let [result (async/<!! result-chan)]
                                               (recur (conj acc result))
                                               acc))]
                               (vec (sort-by :batch-idx results)))
          all-questions (vec (mapcat :questions generation-results))
         _ (trove/log! {:level :info :id ::qa-phase2-done
                        :data {:total-generated (clojure.core/count all-questions)}
                        :msg "Phase 2 complete"})

         ;; ===== PHASE 3: Verification + Revision (optional) =====
          {:keys [passed dropped results ver-trace ver-iterations]}
          (if (and verify? (seq all-questions))
            (do
              (trove/log! {:level :info :id ::qa-phase3
                           :data {:questions-to-verify (clojure.core/count all-questions)}
                           :msg "Phase 3: Verifying Q&A pairs against source material"})
              (let [ver-prompt (build-verification-prompt all-questions)
                    ver-result (query-env! env ver-prompt
                                       {:spec VERIFICATION_SPEC
                                        :refine? false
                                        :learn? false
                                        :debug? debug?
                                        :max-iterations 15
                                        :model effective-model})
                    verifications (or (get-in ver-result [:answer :verifications]) [])
                    filtered (filter-verified-questions all-questions verifications)
                    ;; Revision sub-phase: revise needs-revision questions instead of dropping
                    revised (when (seq (:needs-revision filtered))
                              (trove/log! {:level :info :id ::qa-phase3-revision
                                           :data {:needs-revision (clojure.core/count (:needs-revision filtered))}
                                           :msg "Phase 3: Revising questions with minor issues"})
                              (revise-questions (:needs-revision filtered) config effective-model))
                    all-passed (into (:passed filtered) (or revised []))]
                (trove/log! {:level :info :id ::qa-phase3-done
                             :data {:passed (clojure.core/count (:passed filtered))
                                    :revised (clojure.core/count (or revised []))
                                    :dropped (clojure.core/count (:dropped filtered))}
                             :msg "Phase 3 complete"})
                {:passed all-passed
                 :dropped (:dropped filtered)
                 :results (:results filtered)
                 :ver-trace (:trace ver-result)
                 :ver-iterations (or (:iterations ver-result) 0)}))
            {:passed all-questions :dropped [] :results []
             :ver-trace [] :ver-iterations 0})

         ;; ===== PHASE 4: Deduplication & Final Selection =====
         deduped (deduplicate-questions passed config effective-model)
         final-questions (vec (take count deduped))

         ;; ===== Build stats =====
         duration-ms (util/elapsed-since start-time)
         total-iterations (+ (or (:iterations selection-result) 0)
                             (reduce + 0 (map :iterations generation-results))
                             ver-iterations)
         stats {:total-generated (clojure.core/count all-questions)
                :passed-verification (clojure.core/count passed)
                :duplicates-removed (- (clojure.core/count passed)
                                       (clojure.core/count deduped))
                :final-count (clojure.core/count final-questions)
                :by-difficulty (frequencies (map :difficulty final-questions))
                :by-category (frequencies (map :category final-questions))}]

     (trove/log! {:level :info :id ::qa-done :data stats
                  :msg "generate-qa-env! complete"})

     {:questions final-questions
      :dropped-questions dropped
      :verification-results results
      :phase-traces {:selection (:trace selection-result)
                     :generation (mapv :trace generation-results)
                     :verification ver-trace}
      :stats stats
      :iterations total-iterations
      :duration-ms duration-ms})))

;; =============================================================================
;; save-qa! - Serialize Q&A results to EDN and Markdown
;; =============================================================================

(defn save-qa!
  "Saves generate-qa-env! results to EDN and/or Markdown files.

   Params:
   `result` - Map. Result from generate-qa-env!.
   `path` - String. Base file path without extension.
   `opts` - Map, optional:
     - :formats - Set of keywords. Output formats (default: #{:edn :markdown}).
     - :include-dropped? - Boolean. Include dropped questions (default: false).
     - :include-stats? - Boolean. Include generation stats (default: true).

   Returns:
   Map with :files - vector of written file paths."
  ([result path] (save-qa! result path {}))
  ([result path {:keys [formats include-dropped? include-stats?]
                 :or {formats #{:edn :markdown}
                      include-dropped? false
                      include-stats? true}}]
   (let [written-files (atom [])]

     ;; EDN output
     (when (contains? formats :edn)
       (let [edn-path (str path ".edn")
             data (cond-> {:questions (:questions result)}
                    include-dropped? (assoc :dropped-questions (:dropped-questions result))
                    include-stats? (assoc :stats (:stats result)))]
         (spit edn-path (pr-str data))
         (swap! written-files conj edn-path)
         (trove/log! {:level :info :id ::save-qa-edn
                      :data {:path edn-path :questions (clojure.core/count (:questions result))}
                      :msg "Saved Q&A results as EDN"})))

     ;; Markdown output
     (when (contains? formats :markdown)
       (let [md-path (str path ".md")
             questions (:questions result)
             sb (StringBuilder.)]
         (.append sb "# Generated Q&A Pairs\n\n")
         ;; Stats section
         (when include-stats?
           (let [s (:stats result)]
             (.append sb "## Statistics\n\n")
             (.append sb (str "| Metric | Value |\n|--------|-------|\n"))
             (.append sb (str "| Total generated | " (:total-generated s) " |\n"))
             (.append sb (str "| Passed verification | " (:passed-verification s) " |\n"))
             (.append sb (str "| Duplicates removed | " (:duplicates-removed s) " |\n"))
             (.append sb (str "| Final count | " (:final-count s) " |\n"))
             (.append sb (str "| By difficulty | " (pr-str (:by-difficulty s)) " |\n"))
             (.append sb (str "| By category | " (pr-str (:by-category s)) " |\n\n"))))
         ;; Questions section
         (.append sb "## Questions\n\n")
         (doseq [[i q] (map-indexed vector questions)]
           (.append sb (str "### Q" (inc i)
                            " [" (name (or (:difficulty q) :unknown))
                            " / " (name (or (:category q) :unknown)) "]\n\n"))
           (.append sb (str "**Question:** " (:question q) "\n\n"))
           (.append sb (str "**Answer:** " (:answer q) "\n\n"))
           (when (:evidence-span q)
             (.append sb (str "**Evidence:**\n> " (:evidence-span q) "\n\n")))
           (.append sb (str "**Source:** " (:source-document q)
                            ", page " (:source-page q)
                            (when (:source-section q) (str " — " (:source-section q)))
                            "\n\n"))
           (.append sb "---\n\n"))
         ;; Dropped questions section
         (when (and include-dropped? (seq (:dropped-questions result)))
           (.append sb "## Dropped Questions\n\n")
           (doseq [[i q] (map-indexed vector (:dropped-questions result))]
             (.append sb (str "### Dropped Q" (inc i) "\n\n"))
             (.append sb (str "**Question:** " (:question q) "\n\n"))
             (.append sb (str "**Answer:** " (:answer q) "\n\n"))
             (.append sb "---\n\n")))
         (spit md-path (str sb))
         (swap! written-files conj md-path)
         (trove/log! {:level :info :id ::save-qa-md
                      :data {:path md-path :questions (clojure.core/count questions)}
                      :msg "Saved Q&A results as Markdown"})))

     {:files @written-files})))
