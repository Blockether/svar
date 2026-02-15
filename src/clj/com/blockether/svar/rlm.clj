(ns com.blockether.svar.rlm
  "Recursive Language Model (RLM) for processing arbitrarily large contexts.
   
   RLM enables an LLM to iteratively write and execute Clojure code to examine,
   filter, and process large contexts that exceed token limits. The LLM writes
   code that runs in a sandboxed SCI (Small Clojure Interpreter) environment,
   inspects results, and decides whether to continue iterating or return a final
   answer.
   
   ## API
   
   ```clojure
   ;; 1. Create environment (holds DB, config, SCI context)
   (def env (rlm/create-env {:config llm-config :db-path \"/tmp/my-rlm\"}))
   
   ;; 2. Ingest documents (can call multiple times)
   (rlm/ingest! env documents)
   (rlm/ingest! env more-documents)
   
   ;; 3. Run queries (reuses same env)
   (rlm/query! env \"What is X?\")
   (rlm/query! env \"Find Y\" {:spec my-spec})
   
   ;; 4. Dispose when done
   (rlm/dispose! env)
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
   [clojure.pprint :as pprint]
   [clojure.set :as set]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.core :as svar]
    [com.blockether.svar.internal.jsonish :as jsonish]
     [com.blockether.svar.internal.tokens :as tokens]
     [com.blockether.svar.internal.util :as util]
     [com.blockether.svar.rlm.internal.pageindex.spec :as rlm-spec]
   [fast-edn.core :as edn]
   [clojure.java.io :as io]
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

(def ^:private ENTITY_EXTRACTION_OBJECTIVE
  "Extract entities and relationships from the provided content.\n\nReturn only the fields in the schema.\nFocus on concrete entities, avoid duplication, and include page/section when known.")

(def ^:private ENTITY_SPEC
  "Spec for extracted entities."
  (svar/svar-spec
   :entity
   {svar/KEY-NS "entity"}
   (svar/field {svar/NAME :name
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Entity name"})
   (svar/field {svar/NAME :type
                svar/TYPE :spec.type/keyword
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Entity type (e.g. :party, :organization, :obligation, :term, :condition)"})
   (svar/field {svar/NAME :description
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Entity description"})
   (svar/field {svar/NAME :section
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "Section identifier or label"})
   (svar/field {svar/NAME :page
                svar/TYPE :spec.type/int
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "Page index (0-based)"})))

(def ^:private RELATIONSHIP_SPEC
  "Spec for extracted relationships."
  (svar/svar-spec
   :relationship
   {svar/KEY-NS "relationship"}
   (svar/field {svar/NAME :source
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Source entity name"})
   (svar/field {svar/NAME :target
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Target entity name"})
   (svar/field {svar/NAME :type
                svar/TYPE :spec.type/keyword
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Relationship type (e.g. :owns, :obligates, :references, :defines)"})
   (svar/field {svar/NAME :description
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/REQUIRED false
                svar/DESCRIPTION "Relationship description"})))

(def ENTITY_EXTRACTION_SPEC
  "Spec for entity extraction output."
  (svar/svar-spec
   {:refs [ENTITY_SPEC RELATIONSHIP_SPEC]}
   (svar/field {svar/NAME :entities
                svar/TYPE :spec.type/ref
                svar/TARGET :entity
                svar/CARDINALITY :spec.cardinality/many
                svar/DESCRIPTION "Extracted entities"})
   (svar/field {svar/NAME :relationships
                svar/TYPE :spec.type/ref
                svar/TARGET :relationship
                svar/CARDINALITY :spec.cardinality/many
                svar/REQUIRED false
                svar/DESCRIPTION "Extracted relationships"})))

(def ^:private ITERATION_SPEC
  "Spec for each RLM iteration response. Forces structured output from LLM."
  (svar/svar-spec
   (svar/field {svar/NAME :thinking
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/one
                svar/DESCRIPTION "Your reasoning: what you observed, what you learned, what to do next"})
   (svar/field {svar/NAME :code
                svar/TYPE :spec.type/string
                svar/CARDINALITY :spec.cardinality/many
                svar/DESCRIPTION "Clojure expressions to execute. Use (FINAL answer) when done."})))

(defn bytes->base64
  "Converts raw bytes to a base64 string.
   
   Params:
   `bs` - byte[]. Raw bytes.
   
   Returns:
   String. Base64-encoded representation."
  [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(def ^:dynamic *max-recursion-depth*
  "Dynamic var for max recursion depth. Bound per query! call."
  DEFAULT_RECURSION_DEPTH)

(def ^:dynamic *rlm-ctx*
  "Dynamic context for RLM debug logging. Bind with {:rlm-debug? true :rlm-phase :phase-name :rlm-env-id \"...\"}."
  nil)

;; Forward declarations for mutually dependent functions
(declare make-llm-query-fn make-rlm-query-fn run-sub-rlm
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
;; In-Memory Store
;; =============================================================================

(def ^:private EMPTY_STORE
  "Empty store structure — the shape of all in-memory RLM data."
  {:messages []
   :learnings []
   :documents []
   :pages []
   :page-nodes []
   :toc-entries []
   :entities []
   :relationships []
   :claims []
   :schema {}
   :dirty? false})

(defn- flush-store-now!
  "Immediately persists the store atom to EDN on disk (if path is set).
   Resets the dirty flag after successful write."
  [{:keys [store path]}]
  (when (and path store)
    (try
      (let [store-file (io/file path "store.edn")]
        (io/make-parents store-file)
        (spit store-file (with-out-str (pprint/pprint (dissoc @store :dirty?))))
        (swap! store assoc :dirty? false))
      (catch Exception e
        (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Failed to flush store to EDN"})))))

(defn- mark-dirty!
  "Marks the store as dirty (has unsaved changes)."
  [{:keys [store]}]
  (when store
    (swap! store assoc :dirty? true)))

(defn- flush-store!
  "Marks the store as dirty for deferred persistence.
   Use flush-store-now! for immediate write."
  [db-info]
  (mark-dirty! db-info))

(defn- create-temp-db-path []
  (let [tmp-dir (System/getProperty "java.io.tmpdir")
        unique-id (str (java.util.UUID/randomUUID))]
    (str tmp-dir "/rlm-db-" unique-id)))

(defn- create-disposable-db
  "Creates a disposable in-memory store for RLM use.
   
   Params:
   `opts` - Map, optional:
     - :schema - Map. Initial schema to merge.
   
   Returns:
   Map with :store (atom), :path, :owned?."
  ([] (create-disposable-db {}))
  ([{:keys [schema]}]
   (let [path (create-temp-db-path)]
     {:store (atom (cond-> EMPTY_STORE
                     schema (update :schema merge schema)))
      :path path
      :owned? true})))

(defn- wrap-external-db
  "Wraps an externally-provided store atom (will NOT be disposed)."
  [store-atom]
  {:store store-atom :path nil :owned? false})

(defn- rlm-store?
  "Returns true if value is an RLM store (map with :store atom)."
  [x]
  (and (map? x) (contains? x :store) (instance? clojure.lang.Atom (:store x))))

(defn- dispose-db!
  "Flushes dirty data and disposes a database if it's owned."
  [{:keys [store path owned?] :as db-info}]
  ;; Always flush dirty data before disposing
  (when (and store (:dirty? @store))
    (flush-store-now! db-info))
  (when owned?
    (try
      (when (and path (fs/exists? path)) (fs/delete-tree path))
      (catch Exception e
        (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Failed to dispose RLM database"})))))

(declare get-recent-messages)
(declare db-list-page-nodes)
(declare db-list-toc-entries)
(declare db-list-entities)

;; =============================================================================
;; Message History Schema
;; =============================================================================

(def MESSAGE_HISTORY_SCHEMA
  "Schema for storing conversation messages."
  {:message/id        {:db/unique :db.unique/identity
                       :db/valueType :db.type/uuid}
   :message/role      {:db/valueType :db.type/keyword}  ;; :user, :assistant, :system
   :message/content   {:db/valueType :db.type/string}
   :message/tokens    {:db/valueType :db.type/long}
   :message/timestamp {:db/valueType :db.type/instant}
   :message/iteration {:db/valueType :db.type/long}})   ;; Which iteration this message was from

(defn- init-message-history!
  "Initializes the message history schema in the store.
   
   Params:
   `db-info` - Map with :store atom.
   
   Returns:
   :schema-initialized"
  [{:keys [store]}]
  (when store
    (swap! store update :schema merge MESSAGE_HISTORY_SCHEMA)
    :schema-initialized))

(defn- store-message!
  "Stores a conversation message.
   
   Params:
   `db-info` - Map with :store key.
   `role` - Keyword. :user, :assistant, or :system.
   `content` - String. Message content.
   `opts` - Map, optional:
     - :iteration - Integer. Which iteration this message is from.
     - :tokens - Integer. Pre-computed token count (computed if not provided).
     - :model - String. Model for token counting (default: gpt-4o).
   
   Returns:
   Map with :id, :role, :content, :tokens, :timestamp."
  ([db-info role content]
   (store-message! db-info role content {}))
  ([{:keys [store] :as db-info} role content {:keys [iteration tokens model] :or {model "gpt-4o"}}]
   (when (and store (not (str/blank? content)))  ;; Skip empty content
     (let [msg-id (java.util.UUID/randomUUID)
           ;; Lazy require to avoid circular dependency
            token-count (or tokens
                            (try
                              (tokens/count-tokens model content)
                              (catch Exception _ (quot (count content) 4))))
           timestamp (java.util.Date.)
           msg {:message/id msg-id
                :message/role role
                :message/content content
                :message/tokens token-count
                :message/timestamp timestamp
                :message/iteration (or iteration 0)}]
       (swap! store update :messages conj msg)
       (flush-store! db-info)
       {:id msg-id :role role :content content :tokens token-count :timestamp timestamp}))))

(defn- get-recent-messages
  "Gets the most recent messages by timestamp.
   
   Params:
   `db-info` - Map with :store key.
   `limit` - Integer. Maximum messages to return.
   
   Returns:
   Vector of maps with :content, :role, :tokens, :timestamp."
  [{:keys [store]} limit]
  (when store
    (->> (:messages @store)
         (map (fn [msg]
                {:content (:message/content msg)
                 :role (:message/role msg)
                 :tokens (:message/tokens msg)
                 :timestamp (:message/timestamp msg)}))
         (sort-by :timestamp #(compare %2 %1)) ; Most recent first
         (take limit)
         vec)))

(defn- count-history-tokens
  "Counts total tokens in message history.
   
   Params:
   `db-info` - Map with :store key.
   
   Returns:
   Integer. Total tokens across all stored messages."
  [{:keys [store]}]
  (when store
    (reduce + 0 (map :message/tokens (:messages @store)))))

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
     (if (or (nil? db-info) (nil? (:store db-info)) (<= msg-count (inc preserve-recent)))
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

;; =============================================================================
;; Node Resource Schema (PageIndex-based)
;; =============================================================================

;; =============================================================================
;; PageIndex Document Schema (Complete Structure)
;; =============================================================================

(def DOCUMENT_SCHEMA
  "Schema for storing PageIndex documents exactly as produced by PageIndex.
   Matches :document/* namespace from com.blockether.svar.rlm.internal.pageindex.spec."
  {:document/id         {:db/unique :db.unique/identity
                         :db/valueType :db.type/string
                         :db/doc "Generated UUID string for the document"}
   :document/name       {:db/valueType :db.type/string
                         :db/doc "Filename without extension"}
   :document/title      {:db/valueType :db.type/string
                         :db/doc "Extracted title from metadata or first heading"}
   :document/abstract   {:db/valueType :db.type/string
                         :db/doc "LLM-generated summary from section descriptions"}
   :document/extension  {:db/valueType :db.type/string
                         :db/doc "File type: pdf, md, txt, docx, html"}
   :document/author     {:db/valueType :db.type/string
                         :db/doc "Document author if available"}
   :document/created-at {:db/valueType :db.type/instant
                         :db/doc "When document was created/indexed"}
   :document/updated-at {:db/valueType :db.type/instant
                         :db/doc "When document was last updated"}})

(def PAGE_SCHEMA
  "Schema for storing PageIndex pages exactly as produced by PageIndex.
   Matches :page/* namespace from com.blockether.svar.rlm.internal.pageindex.spec."
  {:page/id          {:db/unique :db.unique/identity
                      :db/valueType :db.type/string
                      :db/doc "Generated UUID string: document-id + page-index"}
   :page/document-id {:db/valueType :db.type/string
                      :db/doc "Reference to parent document"}
   :page/index       {:db/valueType :db.type/long
                      :db/doc "Page number (0-based)"}})

(def PAGE_NODE_SCHEMA
  "Schema for storing PageIndex page nodes exactly as produced by PageIndex.
   Matches :page.node/* namespace from com.blockether.svar.rlm.internal.pageindex.spec.
   These are the actual content elements: paragraphs, headings, images, tables, etc."
  {:page.node/id           {:db/unique :db.unique/identity
                            :db/valueType :db.type/string
                            :db/doc "Unique ID: page-id + node-id"}
   :page.node/page-id      {:db/valueType :db.type/string
                            :db/doc "Reference to parent page"}
   :page.node/document-id  {:db/valueType :db.type/string
                            :db/doc "Reference to parent document (for direct queries)"}
   :page.node/local-id     {:db/valueType :db.type/string
                            :db/doc "Original node ID within page (1, 2, 3, etc.)"}
   :page.node/type         {:db/valueType :db.type/keyword
                            :db/doc "Node type: :section :heading :paragraph :list-item :image :table :header :footer :metadata"}
   :page.node/parent-id    {:db/valueType :db.type/string
                            :db/doc "Parent node ID for hierarchy (nil for top-level)"}
   :page.node/level        {:db/valueType :db.type/string
                            :db/doc "Level: h1-h6 for headings, l1-l6 for lists, paragraph/citation/code for paragraphs"}
   :page.node/content      {:db/valueType :db.type/string
                            :db/doc "Text content for text nodes"}
   :page.node/image-data   {:db/valueType :db.type/bytes
                            :db/doc "Raw PNG image bytes for visual nodes (images/tables)"}
   :page.node/description  {:db/valueType :db.type/string
                            :db/doc "AI-generated description for sections/images/tables"}
   :page.node/continuation? {:db/valueType :db.type/boolean
                             :db/doc "True if continues from previous page"}
   :page.node/caption      {:db/valueType :db.type/string
                            :db/doc "Caption text for images/tables"}
   :page.node/kind         {:db/valueType :db.type/string
                            :db/doc "Kind of visual: photo, diagram, chart, data, form, etc."}
   :page.node/bbox         {:db/valueType :db.type/string
                            :db/doc "Bounding box as JSON string [xmin, ymin, xmax, ymax]"}
   :page.node/group-id     {:db/valueType :db.type/string
                            :db/doc "Shared UUID for continuation grouping across pages"}})

(def TOC_ENTRY_SCHEMA
  "Schema for storing PageIndex TOC entries exactly as produced by PageIndex.
   Matches :document.toc/* namespace from com.blockether.svar.rlm.internal.pageindex.spec."
  {:document.toc/id           {:db/unique :db.unique/identity
                               :db/valueType :db.type/string
                               :db/doc "UUID string identifier for the TOC entry"}
   :document.toc/document-id  {:db/valueType :db.type/string
                               :db/doc "Reference to parent document"}
   :document.toc/type         {:db/valueType :db.type/keyword
                               :db/doc "Entry type (always :toc-entry)"}
   :document.toc/parent-id    {:db/valueType :db.type/string
                               :db/doc "Parent entry ID (nil for root entries)"}
   :document.toc/title        {:db/valueType :db.type/string
                               :db/doc "TOC entry title"}
   :document.toc/description  {:db/valueType :db.type/string
                               :db/doc "Optional description"}
   :document.toc/target-page  {:db/valueType :db.type/long
                               :db/doc "Target page (0-based index)"}
   :document.toc/target-section-id {:db/valueType :db.type/string
                                    :db/doc "UUID string linking to page node"}
   :document.toc/level        {:db/valueType :db.type/string
                               :db/doc "Level (l1, l2, l3, etc.)"}
   :document.toc/created-at   {:db/valueType :db.type/instant
                               :db/doc "When entry was stored in RLM"}})

(def LEARNING_SCHEMA
  "Schema for storing learnings (meta-insights).
   Learnings capture HOW to approach problems, not just query→answer pairs.
   
   Voting system:
   - Learnings are voted on after tasks complete (positive/negative)
   - Learnings with >70% negative votes after 5+ total votes are 'decayed' (filtered from queries)
   - :applied-count tracks how many times a learning was retrieved"
  {:learning/id             {:db/unique :db.unique/identity
                             :db/valueType :db.type/uuid
                             :db/doc "Unique identifier for the learning"}
   :learning/insight        {:db/valueType :db.type/string
                             :db/doc "The learning/insight text"}
   :learning/context        {:db/valueType :db.type/string
                             :db/doc "Task/domain context this learning applies to"}
   :learning/timestamp      {:db/valueType :db.type/instant
                             :db/doc "When learning was stored"}
   ;; Voting fields
   :learning/useful-count   {:db/valueType :db.type/long
                             :db/doc "Number of positive votes (learning was helpful)"}
   :learning/not-useful-count {:db/valueType :db.type/long
                               :db/doc "Number of negative votes (learning was not helpful)"}
   :learning/applied-count  {:db/valueType :db.type/long
                             :db/doc "Number of times this learning was retrieved/used"}
   :learning/last-evaluated {:db/valueType :db.type/instant
                             :db/doc "When learning was last evaluated for usefulness"}})

;; =============================================================================
;; Entity Schema (Generic Entity Attributes)
;; =============================================================================

(def ENTITY_SCHEMA
  "Schema for storing generic entities extracted from documents.
   Entities are the fundamental building blocks: parties, obligations, conditions, terms, clauses, cross-references.
   Each entity has a type and description."
  {:entity/id              {:db/unique :db.unique/identity
                            :db/valueType :db.type/uuid
                            :db/doc "Unique identifier for the entity"}
   :entity/name            {:db/valueType :db.type/string
                            :db/doc "Entity name or label"}
   :entity/type            {:db/valueType :db.type/keyword
                            :db/doc "Entity type: :party, :obligation, :condition, :term, :clause, :cross-reference"}
   :entity/description     {:db/valueType :db.type/string
                            :db/doc "Extracted context/description of the entity"}
   :entity/document-id     {:db/valueType :db.type/string
                            :db/doc "Reference to source document"}
   :entity/page            {:db/valueType :db.type/long
                            :db/doc "Source page number"}
   :entity/section         {:db/valueType :db.type/string
                            :db/doc "Source section identifier"}
   :entity/created-at      {:db/valueType :db.type/instant
                            :db/doc "When entity was created/extracted"}})

;; =============================================================================
;; Legal Entity Schema (Legal-Specific Extensions)
;; =============================================================================

(def LEGAL_ENTITY_SCHEMA
  "Schema for legal-specific entity attributes.
   Extends ENTITY_SCHEMA with domain-specific fields for parties, obligations, conditions, etc."
  {:legal/party-role       {:db/valueType :db.type/keyword
                            :db/doc "Party role: :plaintiff, :defendant, :contractor, :client, :guarantor"}
   :legal/obligation-type  {:db/valueType :db.type/keyword
                            :db/doc "Obligation type: :payment, :delivery, :performance, :confidentiality"}
   :legal/condition-type   {:db/valueType :db.type/keyword
                            :db/doc "Condition type: :precedent, :subsequent, :termination, :force-majeure"}
   :legal/effective-date   {:db/valueType :db.type/string
                            :db/doc "Effective date reference from document"}
   :legal/expiry-date      {:db/valueType :db.type/string
                            :db/doc "Expiry date reference from document"}})

;; =============================================================================
;; Relationship Schema (Entity Relationships)
;; =============================================================================

(def RELATIONSHIP_SCHEMA
  "Schema for storing relationships between entities.
   Relationships capture how entities interact: references, definitions, obligations, conditions, amendments."
  {:relationship/id            {:db/unique :db.unique/identity
                                :db/valueType :db.type/uuid
                                :db/doc "Unique identifier for the relationship"}
   :relationship/source-entity-id {:db/valueType :db.type/uuid
                                   :db/doc "Source entity UUID"}
   :relationship/target-entity-id {:db/valueType :db.type/uuid
                                   :db/doc "Target entity UUID"}
   :relationship/type          {:db/valueType :db.type/keyword
                                :db/doc "Relationship type: :references, :defines, :obligates, :conditions, :amends"}
   :relationship/document-id   {:db/valueType :db.type/string
                                :db/doc "Source document reference"}
   :relationship/description   {:db/valueType :db.type/string
                                :db/doc "Relationship context/description"}})

;; =============================================================================
;; Claim Schema (Verified Claims Storage)
;; =============================================================================

(def CLAIM_SCHEMA
  "Schema for storing verified claims extracted from documents.
   Claims are assertions with citations, confidence scores, and verification verdicts."
  {:claim/id                {:db/unique :db.unique/identity
                             :db/valueType :db.type/uuid
                             :db/doc "Unique identifier for the claim"}
   :claim/text              {:db/valueType :db.type/string
                             :db/doc "The claim text"}
   :claim/document-id       {:db/valueType :db.type/string
                             :db/doc "Source document reference"}
   :claim/page              {:db/valueType :db.type/long
                             :db/doc "Source page number"}
   :claim/section           {:db/valueType :db.type/string
                             :db/doc "Source section identifier"}
   :claim/quote             {:db/valueType :db.type/string
                             :db/doc "Exact quote from source"}
   :claim/confidence        {:db/valueType :db.type/float
                             :db/doc "Confidence score (0.0 to 1.0)"}
   :claim/query-id          {:db/valueType :db.type/uuid
                             :db/doc "Links to which query produced this claim"}
   :claim/verified?         {:db/valueType :db.type/boolean
                             :db/doc "Whether claim has been verified"}
   :claim/verification-verdict {:db/valueType :db.type/string
                                :db/doc "Verification result: correct, incorrect, partially-correct, uncertain"}
   :claim/created-at        {:db/valueType :db.type/instant
                             :db/doc "When claim was created"}})

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
  "Minimum cosine similarity to consider a learning relevant."
  0.6)

(defn- init-learning-schema!
  "Initializes the learning schema in the store.
   
   Params:
   `db-info` - Map with :store atom.
   
   Returns:
   :schema-initialized"
  [{:keys [store]}]
  (when store
    (swap! store update :schema merge LEARNING_SCHEMA)
    :schema-initialized))

(defn- db-store-learning!
  "Stores a meta-insight/learning for future retrieval.
   
   Learnings capture HOW to approach problems, not just query→answer pairs.
   Initializes voting counts to 0.
   
   Params:
   `db-info` - Map with :store key.
   `insight` - String. The learning/insight to store.
   `context` - String, optional. Task/domain context.
   
   Returns:
   Map with :learning/id, :learning/insight, :learning/context, :learning/timestamp."
  ([db-info insight] (db-store-learning! db-info insight nil))
  ([{:keys [store] :as db-info} insight context]
   (when store
     (let [learning-id (java.util.UUID/randomUUID)
           timestamp (java.util.Date.)
           learning (cond-> {:learning/id learning-id
                             :learning/insight insight
                             :learning/timestamp timestamp
                             :learning/useful-count 0
                             :learning/not-useful-count 0
                             :learning/applied-count 0}
                      context (assoc :learning/context context))]
       (swap! store update :learnings conj learning)
       (flush-store! db-info)
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
  "Returns true if a learning has decayed (>70% negative votes after 5+ total votes).
   
   Params:
   `useful-count` - Number of positive votes.
   `not-useful-count` - Number of negative votes.
   
   Returns:
   Boolean."
  [useful-count not-useful-count]
  (let [total (+ (or useful-count 0) (or not-useful-count 0))]
    (and (>= total DECAY_MIN_VOTES)
         (> (/ (or not-useful-count 0) total) DECAY_THRESHOLD))))

(defn- db-vote-learning!
  "Records a vote for a learning's usefulness.
   
   Params:
   `db-info` - Map with :store key.
   `learning-id` - UUID. The learning to vote on.
   `vote` - Keyword. Either :useful or :not-useful.
   
   Returns:
   Updated learning map with new vote counts, or nil if learning not found."
  [{:keys [store] :as db-info} learning-id vote]
  (when store
    (let [learnings (:learnings @store)
          entity (first (filter #(= learning-id (:learning/id %)) learnings))]
      (when entity
        (let [current-useful (or (:learning/useful-count entity) 0)
              current-not-useful (or (:learning/not-useful-count entity) 0)
              [new-useful new-not-useful] (case vote
                                            :useful [(inc current-useful) current-not-useful]
                                            :not-useful [current-useful (inc current-not-useful)]
                                            [current-useful current-not-useful])]
          (swap! store update :learnings
                 (fn [ls]
                   (mapv (fn [l]
                           (if (= learning-id (:learning/id l))
                             (assoc l :learning/useful-count new-useful
                                    :learning/not-useful-count new-not-useful
                                    :learning/last-evaluated (java.util.Date.))
                             l))
                         ls)))
          (flush-store! db-info)
          {:learning/id learning-id
           :learning/insight (:learning/insight entity)
           :learning/useful-count new-useful
           :learning/not-useful-count new-not-useful
           :learning/decayed? (learning-decayed? new-useful new-not-useful)})))))

(defn- db-increment-applied-count!
  "Increments the applied count for a learning (called when learning is retrieved).
   
   Params:
   `db-info` - Map with :store key.
   `learning-id` - UUID. The learning that was applied.
   
   Returns:
   New applied count, or nil if learning not found."
  [{:keys [store] :as db-info} learning-id]
  (when store
    (let [entity (first (filter #(= learning-id (:learning/id %)) (:learnings @store)))]
      (when entity
        (let [new-count (inc (or (:learning/applied-count entity) 0))]
          (swap! store update :learnings
                 (fn [ls]
                   (mapv (fn [l]
                           (if (= learning-id (:learning/id l))
                             (assoc l :learning/applied-count new-count)
                             l))
                         ls)))
          (flush-store! db-info)
          new-count)))))

(defn- db-get-learnings
  "Searches learnings by insight and context text, sorted by recency.
   
   Filters out decayed learnings (>70% negative votes after 5+ total votes).
   Automatically increments applied-count for returned learnings.
   When query is nil/blank, returns all non-decayed learnings by recency.
   
   Params:
   `db-info` - Map with :store key.
   `query` - String. Case-insensitive text search over insight and context.
   `opts` - Map, optional:
     - :top-k - Integer. Max learnings to return (default: 5).
     - :include-decayed? - Boolean. Include decayed learnings (default: false).
     - :track-usage? - Boolean. Increment applied-count (default: true).
   
   Returns:
   Vector of learning maps with :learning/id, :insight, :context, :useful-count, :not-useful-count."
  ([db-info query] (db-get-learnings db-info query {}))
  ([{:keys [store] :as db-info} query {:keys [top-k include-decayed? track-usage?]
                                       :or {top-k 5 include-decayed? false track-usage? true}}]
   (when store
     (let [q (when-not (str/blank? (str query)) (str-lower query))
           filtered (->> (:learnings @store)
                         (map (fn [l]
                                {:learning/id (:learning/id l)
                                 :insight (:learning/insight l)
                                 :context (:learning/context l)
                                 :timestamp (:learning/timestamp l)
                                 :useful-count (or (:learning/useful-count l) 0)
                                 :not-useful-count (or (:learning/not-useful-count l) 0)
                                 :decayed? (learning-decayed? (or (:learning/useful-count l) 0)
                                                              (or (:learning/not-useful-count l) 0))}))
                         (filter #(or include-decayed? (not (:decayed? %))))
                         (filter (fn [l]
                                   (if q
                                     (or (str-includes? (str-lower (:insight l)) q)
                                         (str-includes? (str-lower (:context l)) q))
                                     true)))
                         (sort-by :timestamp #(compare %2 %1))
                         (take top-k)
                         vec)]
       ;; Track usage by incrementing applied-count
       (when track-usage?
         (doseq [{:keys [learning/id]} filtered]
           (db-increment-applied-count! db-info id)))
       filtered))))

(defn- db-learning-stats
  "Gets statistics about stored learnings including voting stats.
   
   Params:
   `db-info` - Map with :store key.
   
   Returns:
   Map with :total-learnings, :active-learnings, :decayed-learnings,
   :with-context, :without-context, :total-votes, :total-applications."
  [{:keys [store]}]
  (when store
    (let [all-learnings (:learnings @store)
          total (count all-learnings)
          with-context (count (filter #(some? (:learning/context %)) all-learnings))
          decayed (count (filter #(learning-decayed? (or (:learning/useful-count %) 0)
                                                     (or (:learning/not-useful-count %) 0))
                                all-learnings))
          total-votes (reduce + (map #(+ (or (:learning/useful-count %) 0)
                                         (or (:learning/not-useful-count %) 0))
                                     all-learnings))
          total-applications (reduce + (map #(or (:learning/applied-count %) 0) all-learnings))]
      {:total-learnings total
       :active-learnings (- total decayed)
       :decayed-learnings decayed
       :with-context with-context
       :without-context (- total with-context)
       :total-votes total-votes
       :total-applications total-applications})))

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

(defn- init-document-schema!
  "Initializes all PageIndex document schemas in the database.
    
    Schemas initialized:
    - DOCUMENT_SCHEMA (:document/*)
    - PAGE_SCHEMA (:page/*)
    - PAGE_NODE_SCHEMA (:page.node/*)
    - TOC_ENTRY_SCHEMA (:document.toc/*)
    - ENTITY_SCHEMA (:entity/*)
    - LEGAL_ENTITY_SCHEMA (:legal/*)
    - RELATIONSHIP_SCHEMA (:relationship/*)
    - CLAIM_SCHEMA (:claim/*)
    
    Params:
    `db-info` - Map with :store key containing the store atom.
    
    Returns:
    :schema-initialized"
  [{:keys [store]}]
  (when store
    (swap! store update :schema merge
           DOCUMENT_SCHEMA PAGE_SCHEMA PAGE_NODE_SCHEMA TOC_ENTRY_SCHEMA
           ENTITY_SCHEMA LEGAL_ENTITY_SCHEMA RELATIONSHIP_SCHEMA CLAIM_SCHEMA)
    :schema-initialized))

;; -----------------------------------------------------------------------------
;; Document Storage
;; -----------------------------------------------------------------------------

(defn- db-store-document!
  "Stores a PageIndex document (metadata only, not pages/toc).
   
   Params:
   `db-info` - Map with :store key.
   `doc` - PageIndex document map.
   `doc-id` - String. Generated document ID.
   
   Returns:
   The stored document entity."
  [{:keys [store] :as db-info} doc doc-id]
  (when store
    (let [entity (cond-> {:document/id doc-id
                          :document/name (:document/name doc)
                          :document/extension (:document/extension doc)}
                   (:document/title doc) (assoc :document/title (:document/title doc))
                   (:document/abstract doc) (assoc :document/abstract (:document/abstract doc))
                   (:document/author doc) (assoc :document/author (:document/author doc))
                   (:document/created-at doc) (assoc :document/created-at (:document/created-at doc))
                   (:document/updated-at doc) (assoc :document/updated-at (:document/updated-at doc)))]
      (swap! store update :documents conj entity)
      (flush-store! db-info)
      entity)))

(defn- get-document-toc
  "Gets TOC entries for a document, formatted as a readable list."
  [{:keys [store]} doc-id]
  (when store
    (->> (:toc-entries @store)
         (filter #(= doc-id (:document.toc/document-id %)))
         (map (fn [e]
                {:title (:document.toc/title e)
                 :level (:document.toc/level e)
                 :page (:document.toc/target-page e)}))
         (sort-by (juxt :level :page))
         vec)))

(defn- db-get-document
   "Gets a document by ID with abstract and TOC.
   
   Params:
   `db-info` - Map with :store key.
   `doc-id` - String. Document ID.
   
   Returns:
   Document map with :document/toc (formatted list) or nil."
  [{:keys [store] :as db-info} doc-id]
  (when store
    (let [doc (first (filter #(= doc-id (:document/id %)) (:documents @store)))]
      (when doc
        (let [toc (get-document-toc db-info doc-id)]
          (assoc doc :document/toc toc))))))

(defn- db-list-documents
  "Lists all stored documents with abstracts and TOC summaries.
   
   Each document includes:
   - Basic metadata (id, name, title, extension)
   - Abstract (if available)
   - TOC as formatted list: [{:title \"Chapter 1\" :level \"l1\" :page 0} ...]
   
   Params:
   `db-info` - Map with :store key.
   `opts` - Map, optional:
     - :limit - Integer. Max results (default: 100).
     - :include-toc? - Boolean. Include TOC (default: true).
   
   Returns:
   Vector of document maps with :document/toc."
   ([db-info] (db-list-documents db-info {}))
   ([{:keys [store] :as db-info} {:keys [limit include-toc?] :or {limit 100 include-toc? true}}]
    (when store
      (let [docs (:documents @store)]
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
  [{:keys [store] :as db-info} page doc-id]
  (when store
    (let [page-id (str doc-id "-page-" (:page/index page))
          page-data {:page/id page-id
                     :page/document-id doc-id
                     :page/index (:page/index page)}]
      (swap! store update :pages conj page-data)
      (flush-store! db-info)
      page-id)))

(defn- db-get-page
  "Gets a page by ID.
   
   Params:
   `db-info` - Map with :store key.
   `page-id` - String. Page ID.
   
   Returns:
   Page map or nil."
  [{:keys [store]} page-id]
  (when store
    (first (filter #(= page-id (:page/id %)) (:pages @store)))))

(defn- db-list-pages
  "Lists pages for a document.
   
   Params:
   `db-info` - Map with :store key.
   `doc-id` - String. Document ID.
   
   Returns:
   Vector of page maps sorted by index."
  [{:keys [store]} doc-id]
  (when store
    (->> (:pages @store)
         (filter #(= doc-id (:page/document-id %)))
         (map #(select-keys % [:page/id :page/index :page/document-id]))
         (sort-by :page/index)
         vec)))

;; -----------------------------------------------------------------------------
;; Page Node Storage & Search
;; -----------------------------------------------------------------------------

(defn- db-store-page-node!
  "Stores a page node (internal - called by db-store-pageindex-document!)."
  [{:keys [store] :as db-info} node page-id doc-id]
  (when store
    (let [node-id (str page-id "-node-" (:page.node/id node))
          visual-node? (#{:image :table} (:page.node/type node))
          img-bytes (:page.node/image-data node)
          image-too-large? (and visual-node?
                                img-bytes
                                (> (alength ^bytes img-bytes) 5242880))
          image-data (when (and visual-node?
                                img-bytes
                                (not image-too-large?))
                       img-bytes)
          tx-data [(cond-> {:page.node/id node-id
                            :page.node/page-id page-id
                            :page.node/document-id doc-id
                            :page.node/local-id (:page.node/id node)
                            :page.node/type (:page.node/type node)}
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
                     (:page.node/group-id node) (assoc :page.node/group-id (:page.node/group-id node)))]]
      (when image-too-large?
        (trove/log! {:level :warn
                     :data {:page-node-id node-id
                            :bytes-size (alength ^bytes img-bytes)}
                     :msg "Skipping page node image-data (exceeds 5MB limit)"}))
      (swap! store update :page-nodes conj (first tx-data))
      (flush-store! db-info)
      node-id)))

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
  ([{:keys [store] :as db-info} query {:keys [top-k document-id type] :or {top-k 10}}]
   (if (str/blank? (str query))
     (db-list-page-nodes db-info {:document-id document-id
                                  :type type
                                  :limit top-k})
     (when store
       (let [q (str-lower query)]
         (->> (:page-nodes @store)
              (filter (fn [n]
                        (or (str-includes? (str-lower (:page.node/content n)) q)
                            (str-includes? (str-lower (:page.node/description n)) q))))
              (filter #(or (nil? document-id) (= document-id (:page.node/document-id %))))
              (filter #(or (nil? type) (= type (:page.node/type %))))
              (take top-k)
              (mapv (fn [n]
                      {:page.node/id (:page.node/id n)
                       :page.node/page-id (:page.node/page-id n)
                       :page.node/document-id (:page.node/document-id n)
                       :page.node/type (:page.node/type n)
                       :page.node/level (:page.node/level n)
                       :page.node/local-id (:page.node/local-id n)
                       :page.node/content (:page.node/content n)
                       :page.node/description (:page.node/description n)}))))))))

(defn- db-get-page-node
  "Gets a page node by ID with full details.
   
   Params:
   `db-info` - Map with :store key.
   `node-id` - String. Page node ID.
   
   Returns:
   Page node map or nil."
  [{:keys [store]} node-id]
  (when store
    (first (filter #(= node-id (:page.node/id %)) (:page-nodes @store)))))

(defn- db-list-page-nodes
  "Lists page nodes, optionally filtered.
   
   Params:
   `db-info` - Map with :store key.
   `opts` - Map, optional:
     - :page-id - String. Filter by page.
     - :document-id - String. Filter by document.
     - :type - Keyword. Filter by node type.
     - :limit - Integer. Max results (default: 100).
   
   Returns:
   Vector of page node maps."
   ([db-info] (db-list-page-nodes db-info {}))
   ([{:keys [store]} {:keys [page-id document-id type limit] :or {limit 100}}]
    (when store
      (->> (:page-nodes @store)
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
                    :page.node/description (str-truncate (:page.node/description n) 200)}))))))

;; -----------------------------------------------------------------------------
;; TOC Entry Storage
;; -----------------------------------------------------------------------------

(defn- db-store-toc-entry!
  "Stores a PageIndex TOC entry exactly as-is.
   
   Preserves the exact structure from PageIndex - no transformation.
   Only adds :document.toc/created-at.
   
   Params:
   `db-info` - Map with :store key.
   `entry` - Map with PageIndex TOC entry fields (document.toc/* namespace).
   `doc-id` - String, optional. Parent document ID (defaults to \"standalone\").
   
   Returns:
   The stored entry."
  ([db-info entry] (db-store-toc-entry! db-info entry "standalone"))
  ([{:keys [store] :as db-info} entry doc-id]
   (when store
     (let [timestamp (java.util.Date.)
           entry-data (assoc entry
                             :document.toc/document-id doc-id
                             :document.toc/created-at timestamp)]
       (swap! store update :toc-entries conj entry-data)
       (flush-store! db-info)
       entry-data))))

(defn- db-search-toc-entries
  "Searches TOC entries by title and description text.
   
   Params:
   `db-info` - Map with :store key.
   `query` - String. Case-insensitive text search over title and description.
             When nil/blank, falls back to list mode.
   `opts` - Map, optional:
     - :top-k - Integer. Max results (default: 10).
   
   Returns:
   Vector of TOC entry maps with :document.toc/* fields."
  ([db-info query] (db-search-toc-entries db-info query {}))
  ([{:keys [store] :as db-info} query {:keys [top-k] :or {top-k 10}}]
   (if (str/blank? (str query))
     (db-list-toc-entries db-info {:limit top-k})
     (when store
       (let [q (str-lower query)]
         (->> (:toc-entries @store)
              (filter (fn [e]
                        (or (str-includes? (str-lower (:document.toc/title e)) q)
                            (str-includes? (str-lower (:document.toc/description e)) q))))
              (take top-k)
              (mapv (fn [e]
                      {:document.toc/id (:document.toc/id e)
                       :document.toc/title (:document.toc/title e)
                       :document.toc/level (:document.toc/level e)
                       :document.toc/description (when-not (= "" (str (:document.toc/description e)))
                                                   (:document.toc/description e))
                       :document.toc/target-page (:document.toc/target-page e)}))))))))

(defn- db-get-toc-entry
  "Gets a TOC entry by ID with full details.
   
   Params:
   `db-info` - Map with :store key.
   `entry-id` - String. The TOC entry ID.
   
   Returns:
   TOC entry map with all fields, or nil if not found."
  [{:keys [store]} entry-id]
  (when store
    (first (filter #(= entry-id (:document.toc/id %)) (:toc-entries @store)))))

(defn- db-list-toc-entries
  "Lists all TOC entries, optionally filtered.
   
   Params:
   `db-info` - Map with :store key.
   `opts` - Map, optional:
     - :parent-id - String. Filter by parent.
     - :limit - Integer. Max results (default: 100).
   
   Returns:
   Vector of TOC entry maps."
   ([db-info] (db-list-toc-entries db-info {}))
   ([{:keys [store]} {:keys [parent-id limit] :or {limit 100}}]
    (when store
      (->> (:toc-entries @store)
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
           vec))))

;; -----------------------------------------------------------------------------
;; Entity/Relationship Query Functions
;; -----------------------------------------------------------------------------

(defn- db-search-entities
  "Searches entities by name and description text, optionally filtered by type and document.
   
   Params:
   `db-info` - Map with :store key.
   `query` - String. Case-insensitive text search over name and description.
             When nil/blank, falls back to list mode.
   `opts` - Map, optional:
     - :top-k - Integer. Max results (default: 10).
     - :type - Keyword. Filter by entity type.
     - :document-id - String. Filter by document.
   
   Returns:
   Vector of entity maps."
  ([db-info query] (db-search-entities db-info query {}))
  ([{:keys [store] :as db-info} query {:keys [top-k type document-id] :or {top-k 10}}]
   (if (str/blank? (str query))
     (db-list-entities db-info {:type type
                                :document-id document-id
                                :limit top-k})
     (when store
       (let [q (str-lower query)]
         (->> (:entities @store)
              (filter (fn [e]
                        (or (str-includes? (str-lower (:entity/name e)) q)
                            (str-includes? (str-lower (:entity/description e)) q))))
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
   `db-info` - Map with :store key.
   `entity-id` - UUID. Entity ID.
   
   Returns:
   Entity map or nil."
  [{:keys [store]} entity-id]
  (when store
    (first (filter #(= entity-id (:entity/id %)) (:entities @store)))))

(defn- db-list-entities
  "Lists entities, optionally filtered.
   
   Params:
   `db-info` - Map with :store key.
   `opts` - Map, optional:
     - :type - Keyword. Filter by entity type.
     - :document-id - String. Filter by document.
     - :limit - Integer. Max results (default: 100).
   
   Returns:
   Vector of entity maps."
   ([db-info] (db-list-entities db-info {}))
   ([{:keys [store]} {:keys [type document-id limit] :or {limit 100}}]
    (when store
      (->> (:entities @store)
           (map (fn [e]
                  {:entity/id (:entity/id e)
                   :entity/name (:entity/name e)
                   :entity/type (:entity/type e)
                   :entity/description (when-not (= "" (str (:entity/description e))) (:entity/description e))
                   :entity/document-id (:entity/document-id e)
                   :entity/page (:entity/page e)
                   :entity/section (when-not (= "" (str (:entity/section e))) (:entity/section e))}))
           (filter #(or (nil? type) (= type (:entity/type %))))
           (filter #(or (nil? document-id) (= document-id (:entity/document-id %))))
           (sort-by :entity/name)
           (take limit)
           vec))))

(defn- db-list-relationships
  "Lists relationships for an entity (as source or target).
   
   Params:
   `db-info` - Map with :store key.
   `entity-id` - UUID. Entity ID.
   `opts` - Map, optional:
     - :type - Keyword. Filter by relationship type.
   
   Returns:
   Vector of relationship maps."
   ([db-info entity-id] (db-list-relationships db-info entity-id {}))
   ([{:keys [store]} entity-id {:keys [type]}]
    (when store
      (->> (:relationships @store)
           (filter #(or (= entity-id (:relationship/source-entity-id %))
                        (= entity-id (:relationship/target-entity-id %))))
           (map (fn [r]
                  {:relationship/id (:relationship/id r)
                   :relationship/type (:relationship/type r)
                   :relationship/source-entity-id (:relationship/source-entity-id r)
                   :relationship/target-entity-id (:relationship/target-entity-id r)
                   :relationship/description (when-not (= "" (str (:relationship/description r)))
                                               (:relationship/description r))}))
           (filter #(or (nil? type) (= type (:relationship/type %))))
           vec))))

(defn- db-entity-stats
  "Gets entity and relationship statistics.
   
   Params:
   `db-info` - Map with :store key.
   
   Returns:
   Map with :total-entities, :types (map of type->count), :total-relationships."
  [{:keys [store]}]
  (if store
    (let [entities (:entities @store)
          types-map (frequencies (map :entity/type entities))
          rel-count (count (:relationships @store))]
      {:total-entities (count entities)
       :types types-map
       :total-relationships rel-count})
    {:total-entities 0 :types {} :total-relationships 0}))

;; -----------------------------------------------------------------------------
;; High-Level Document Storage
;; -----------------------------------------------------------------------------

(defn- db-store-pageindex-document!
  "Stores an entire PageIndex document with all its components.
   
   Stores the complete document structure exactly as PageIndex produces it:
   - Document metadata
   - All pages
   - All page nodes
   - All TOC entries
   
   Params:
   `db-info` - Map with :store key.
   `doc` - Complete PageIndex document (spec-validated).
   
   Returns:
   Map with :document-id and counts of stored entities."
  [db-info doc]
  (let [doc-id (str (java.util.UUID/randomUUID))
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
                       'FINAL-VAR (fn [var-name] (let [v (realize-value (get @locals-atom var-name))]
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
                       'spec svar/svar-spec
                       'field svar/field
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
        all-bindings (merge SAFE_BINDINGS base-bindings rlm-bindings db-bindings
                            example-bindings learning-bindings (or custom-bindings {}))]
    (sci/init {:namespaces {'user all-bindings}
               :classes {'java.util.regex.Pattern java.util.regex.Pattern
                         'java.util.regex.Matcher java.util.regex.Matcher
                         'java.time.LocalDate java.time.LocalDate
                         'java.time.Period java.time.Period
                         'java.util.UUID java.util.UUID}
               :deny '[require import ns eval load-string read-string]})))

(defn- create-persistent-db
  "Creates a persistent store at the given path.
   Loads existing data from store.edn if present.
   Unlike create-disposable-db, this store is NOT deleted when disposed."
  [path]
  (let [store-file (io/file path "store.edn")
        existing-data (when (.exists store-file)
                        (try
                          (edn/read-string (slurp store-file))
                          (catch Exception e
                            (trove/log! {:level :warn :data {:error (ex-message e)}
                                         :msg "Failed to load existing store.edn, starting fresh"})
                            nil)))]
    {:store (atom (or existing-data EMPTY_STORE))
     :path path
     :owned? false}))

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
     - :db-path - Path for persistent DB (history survives across sessions).
     - :documents - Vector of PageIndex documents to preload (stored exactly as-is).
   
   Returns:
   Map with :sci-ctx, :context, :llm-query-fn, :rlm-query-fn, :locals-atom, 
   :db-info-atom, :history-enabled?
   
    Note: History tracking is ALWAYS enabled when a store is available.
    The same in-memory store is used for conversation history, documents, and learnings."
  ([context-data model depth-atom api-key base-url]
   (create-rlm-env context-data model depth-atom api-key base-url {}))
  ([context-data model depth-atom api-key base-url {:keys [db db-opts db-path documents config]}]
   (let [locals-atom (atom {})
          db-info (cond
                    (false? db) nil
                    (rlm-store? db) (assoc db :owned? false)
                   ;; Persistent DB path provided - history survives across sessions
                   db-path (create-persistent-db db-path)
                   ;; Default: disposable in-memory DB
                   :else (create-disposable-db (or db-opts {})))
         db-info-atom (when db-info (atom db-info))
         ;; Initialize all schemas when DB is available
         _ (when db-info-atom
             (init-message-history! @db-info-atom)
             (init-document-schema! @db-info-atom)
             (init-learning-schema! @db-info-atom))
         ;; Preload documents if provided (stores complete structure)
         _ (when (and db-info-atom (seq documents))
             (doseq [doc documents]
               (db-store-pageindex-document! @db-info-atom doc)))
         ;; Create query functions
         llm-query-fn (make-llm-query-fn model depth-atom api-key base-url)
         rlm-query-fn (when db-info-atom
                        (make-rlm-query-fn model depth-atom api-key base-url db-info-atom))]
     {:sci-ctx (create-sci-context context-data llm-query-fn rlm-query-fn locals-atom db-info-atom nil)
      :context context-data
      :llm-query-fn llm-query-fn
      :rlm-query-fn rlm-query-fn
      :locals-atom locals-atom
      :db-info-atom db-info-atom
      :history-enabled? (boolean db-info-atom)})))

(defn- dispose-rlm-env! [{:keys [db-info-atom]}]
  (when db-info-atom (dispose-db! @db-info-atom)))

(defn- get-locals [rlm-env] @(:locals-atom rlm-env))

;; =============================================================================
;; Code Execution
;; =============================================================================

(defn- execute-code [{:keys [sci-ctx locals-atom]} code]
  (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-phase :execute-code})]
    (let [_ (rlm-debug! {:code-preview (str-truncate code 200)} "Executing code")
          start-time (System/currentTimeMillis)
          vars-before (try (sci/eval-string* sci-ctx "(ns-interns 'user)") (catch Exception _ {}))
          execution-future (future
                             (try
                               (let [stdout-writer (java.io.StringWriter.)
                                     result (binding [*out* stdout-writer] (sci/eval-string* sci-ctx code))]
                                 {:result result :stdout (str stdout-writer) :error nil})
                               (catch Exception e {:result nil :stdout "" :error (ex-message e)})))
          execution-result (try (deref execution-future EVAL_TIMEOUT_MS nil)
                                (catch Exception e {:result nil :stdout "" :error (ex-message e)}))
          execution-time (- (System/currentTimeMillis) start-time)
          timed-out? (nil? execution-result)]
      (if timed-out?
        (do (future-cancel execution-future)
            (rlm-debug! {:execution-time-ms execution-time} "Code execution timed out")
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
  "Creates the llm-query function for simple text queries (no code execution)."
  [model depth-atom api-key base-url]
  (fn llm-query
    ([prompt]
     (if (>= @depth-atom *max-recursion-depth*)
       (str "Max recursion depth (" *max-recursion-depth* ") exceeded")
       (try
         (swap! depth-atom inc)
         (#'svar/chat-completion [{:role "user" :content prompt}] model api-key base-url)
         (finally (swap! depth-atom dec)))))
    ([prompt opts]
     (if (>= @depth-atom *max-recursion-depth*)
       (str "Max recursion depth (" *max-recursion-depth* ") exceeded")
       (try
         (swap! depth-atom inc)
         (if-let [spec (:spec opts)]
            (:result (svar/ask! {:spec spec :messages [(svar/system "You are a helpful assistant.") (svar/user prompt)]
                                 :model model :api-key api-key :base-url base-url}))
           (#'svar/chat-completion [{:role "user" :content prompt}] model api-key base-url))
         (finally (swap! depth-atom dec)))))))

(defn- make-rlm-query-fn
  "Creates the rlm-query function for sub-RLM queries that share the same database.
   
    This allows the LLM to spawn sub-queries with code execution that reuse
    the same database, enabling complex multi-step analysis."
  [model depth-atom api-key base-url db-info-atom]
  (fn rlm-query
    ([context sub-query]
     (rlm-query context sub-query {}))
    ([context sub-query opts]
     (if (>= @depth-atom *max-recursion-depth*)
       {:error (str "Max recursion depth (" *max-recursion-depth* ") exceeded")}
       (try
         (swap! depth-atom inc)
         (run-sub-rlm context sub-query model api-key base-url db-info-atom
                      (merge {:max-iterations 10} opts))
         (finally (swap! depth-atom dec)))))))

(defn- run-sub-rlm
  "Runs a sub-RLM query that shares the same database as the parent.
   
    This enables nested RLM queries where the sub-query can read/write
    to the same store, access the same history, etc.
   
   Params:
   `context` - Data context for the sub-query
   `query` - The question to answer
   `model` - LLM model
   `api-key` - API key
   `base-url` - Base URL
   `db-info-atom` - Shared database atom from parent RLM
   `opts` - Options including :max-iterations, :spec"
  [context query model api-key base-url db-info-atom opts]
  (let [sub-env-id (str (java.util.UUID/randomUUID))
        parent-env-id (:rlm-env-id *rlm-ctx*)
        depth-atom (atom 0)
        locals-atom (atom {})
        ;; Create query functions that share the same DB
        llm-query-fn (make-llm-query-fn model depth-atom api-key base-url)
        rlm-query-fn (make-rlm-query-fn model depth-atom api-key base-url db-info-atom)
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
        _ (store-message! db-info :user initial-user-content {:iteration 0 :model model})]
    (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-env-id sub-env-id :rlm-type :sub :rlm-parent-id parent-env-id :rlm-phase :sub-iteration-loop})]
      (rlm-debug! {:query query :max-iterations max-iterations :parent-env-id parent-env-id} "Sub-RLM started")
      (loop [iteration 0 messages initial-messages]
        (if (>= iteration max-iterations)
          (do (trove/log! {:level :warn :data {:iteration iteration} :msg "Sub-RLM max iterations reached"})
              {:status :max-iterations :iterations iteration})
          (let [{:keys [response thinking executions final-result]}
                (run-iteration sub-env messages model api-key base-url)]
            (store-message! db-info :assistant response {:iteration iteration :model model})
            (if final-result
              (do (rlm-debug! {:iteration iteration} "Sub-RLM FINAL detected")
                  {:answer (:result (:answer final-result) (:answer final-result)) :iterations iteration})
              (let [exec-feedback (format-executions executions)
                    iteration-header (str "[Iteration " (inc iteration) "/" max-iterations "]")
                    user-feedback (if (empty? executions)
                                    (str iteration-header "\nNo code was executed. You MUST include Clojure expressions in the \"code\" JSON array. Respond with valid JSON: {\"thinking\": \"...\", \"code\": [\"...\"]}")
                                    (str iteration-header "\n" exec-feedback))]
                (rlm-debug! {:iteration iteration :code-blocks (count executions) :has-thinking? (some? thinking)} "Sub-RLM iteration feedback")
                (store-message! db-info :user user-feedback {:iteration (inc iteration) :model model})
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

(defn- format-custom-docs
  "Formats custom docs for the system prompt."
  [custom-docs]
  (when (seq custom-docs)
    (str "\n<custom_tools>\n"
         (str/join "\n"
                   (map (fn [{:keys [type sym doc]}]
                          (str "  <" (name type) " name=\"" sym "\">" doc "</" (name type) ">"))
                        custom-docs))
         "\n</custom_tools>\n")))

(defn- build-system-prompt
  "Builds the system prompt. Optionally includes spec schema, examples, history tools, and custom docs."
  [{:keys [output-spec examples history-enabled? custom-docs]}]
  (str "<rlm_environment>
<role>You are an expert Clojure programmer analyzing data in a sandboxed environment.</role>

<available_tools>
  <tool name=\"context\">The data context - access as 'context' variable</tool>
  <tool name=\"llm-query\">(llm-query prompt) or (llm-query prompt {:spec my-spec}) - Simple text query to LLM</tool>
  <tool name=\"rlm-query\">(rlm-query sub-context query) or (rlm-query sub-context query {:spec s :max-iterations n}) - Spawn sub-RLM with code execution, SHARES the same database</tool>
  <tool name=\"FINAL\">(FINAL answer) - MUST call when you have the answer</tool>
  <tool name=\"FINAL-VAR\">(FINAL-VAR 'var-name) - return a variable's value</tool>
  <tool name=\"list-locals\">(list-locals) - see all variables you've defined (functions show as &lt;fn&gt;, large collections summarized)</tool>
  <tool name=\"get-local\">(get-local 'var-name) - get full value of a specific variable you defined</tool>
</available_tools>

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
              (svar/spec->prompt output-spec)
              "\n</expected_output_schema>\n"))

       ;; Include examples
       (format-examples-for-prompt examples)

       "\n<workflow>
0. FIRST: Check <context> - if it directly answers the query, call (FINAL answer) immediately without searching
1. If more info needed, check available documents: (list-documents)
2. Browse TOC to understand document structure: (list-toc-entries)
3. Pick sections from TOC, get content: (get-page-node node-id) or (list-page-nodes {:document-id id})
4. Check entities if relevant: (entity-stats), then (list-entities {:type :party})
5. Write code to analyze data, store intermediate results with def"
       (when history-enabled?
         "\n6. Use get-history to check recent conversation")
       "\n" (if history-enabled? "7" "6") ". Call (FINAL answer) when done
</workflow>

<response_format>
" (svar/spec->prompt ITERATION_SPEC) "
EVERY response MUST be valid JSON with 'thinking' and 'code' fields. No markdown, no prose outside JSON.
</response_format>

<critical>
- CLOJURE SYNTAX: ALL function calls MUST be wrapped in parentheses. `(list-documents)` calls the function, `list-documents` just references the function object and returns nothing useful. Same for FINAL: `(FINAL answer)` terminates, `FINAL answer` does NOT.
- FAST PATH: If <context> already contains the answer, call (FINAL answer) IMMEDIATELY - no searching needed!
- USE list-page-nodes or search-page-nodes FOR CONTENT (not just TOC entries!)
- ALWAYS call (FINAL answer) when you have the answer - don't keep searching after finding it
- Max " MAX_ITERATIONS " iterations, " (/ EVAL_TIMEOUT_MS 1000) "s timeout per execution"
       (when history-enabled?
         "\n- Use get-history to check recent conversation and avoid repeating earlier work")
       "
</critical>
</rlm_environment>"))

;; =============================================================================
;; Iteration Loop
;; =============================================================================

(defn- run-iteration [rlm-env messages model api-key base-url]
  (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-phase :run-iteration})]
    (let [_ (rlm-debug! {:model model :msg-count (count messages)} "LLM call started")
          response (#'svar/chat-completion messages model api-key base-url)
          _ (rlm-debug! {:response-len (count response)
                         :response-preview (str-truncate response 300)} "LLM response received")
        ;; Parse structured response via spec (primary), fall back to code fences
          parsed (try (let [p (svar/str->data-with-spec response ITERATION_SPEC)]
                        (rlm-debug! {} "Response parsed via ITERATION_SPEC (structured)")
                        p)
                      (catch Exception e
                      ;; Fallback: extract code from markdown fences
                        (rlm-debug! {:parse-error (ex-message e)} "Spec parse failed, falling back to markdown extraction")
                        {:thinking response :code (extract-code-blocks response)}))
          thinking (:thinking parsed)
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
                            (str "  <value>" (pr-str (realize-value result)) "</value>"
                                 (when-not (str/blank? stdout) (str "\n  <stdout>" stdout "</stdout>")))))
                        "\n</result_" id ">"))
                 executions)))

(defn- iteration-loop [rlm-env query model api-key base-url max-iterations
                       {:keys [output-spec examples max-context-tokens custom-docs pre-fetched-context]}]
  (let [history-enabled? (:history-enabled? rlm-env)
        system-prompt (build-system-prompt {:output-spec output-spec
                                            :examples examples
                                            :history-enabled? history-enabled?
                                            :custom-docs custom-docs})
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
        _ (when history-enabled?
            (store-message! db-info :system system-prompt {:iteration 0 :model model})
            (store-message! db-info :user initial-user-content {:iteration 0 :model model}))]
    (rlm-debug! {:query query :max-iterations max-iterations :model model
                 :has-output-spec? (some? output-spec) :has-pre-fetched? (some? pre-fetched-context)
                 :msg-count (count initial-messages)} "Iteration loop started")
    (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-phase :iteration-loop})]
      (loop [iteration 0 messages initial-messages trace []]
        (if (>= iteration max-iterations)
          (let [locals (get-locals rlm-env)
                useful-value (some->> locals vals (filter #(and (some? %) (not (fn? %)))) last)]
            (trove/log! {:level :warn :data {:iteration iteration} :msg "Max iterations reached"})
            {:answer (if useful-value (pr-str useful-value) nil)
             :status :max-iterations
             :locals locals
             :trace trace
             :iterations iteration})
          (let [_ (rlm-debug! {:iteration iteration :msg-count (count messages)} "Iteration start")
              ;; Smart context management: use semantic selection when history enabled, else simple truncation
                effective-messages (cond
                                   ;; Semantic context selection when history enabled + budget set + enough messages
                                     (and history-enabled? max-context-tokens (> (count messages) 4))
                                      (select-rlm-iteration-context
                                       db-info messages max-context-tokens
                                       {:model model})

                                   ;; Simple truncation when just budget set
                                     max-context-tokens
                                      (tokens/truncate-messages model messages max-context-tokens)

                                   ;; No budget - use all messages
                                     :else messages)
                {:keys [response thinking executions final-result]} (run-iteration rlm-env effective-messages model api-key base-url)
              ;; Build trace entry for this iteration
                trace-entry {:iteration iteration
                             :response response
                             :thinking thinking
                             :executions executions
                             :final? (boolean final-result)}]
          ;; Store assistant response if tracking
            (when history-enabled?
              (store-message! db-info :assistant response {:iteration iteration :model model}))
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
                  (store-message! db-info :user user-feedback {:iteration (inc iteration) :model model}))
                (recur (inc iteration)
                       (conj messages
                             {:role "assistant" :content response}
                             {:role "user" :content user-feedback})
                       (conj trace trace-entry))))))))))

;; =============================================================================
;; Entity Extraction Functions
;; =============================================================================

(defn- extract-entities-from-page!
  "Extracts entities from a page's text nodes using LLM.
   
   Params:
   `text-content` - String. Combined text from page nodes.
   `model` - String. Model name for extraction.
   `config` - Map. LLM configuration.
   
   Returns:
   Map with :entities and :relationships keys (empty if extraction fails)."
  [text-content model config]
  (try
    (let [truncated (if (> (count text-content) 8000) (subs text-content 0 8000) text-content)
          response (svar/ask! {:config config
                                :spec ENTITY_EXTRACTION_SPEC
                                :messages [(svar/system ENTITY_EXTRACTION_OBJECTIVE)
                                           (svar/user truncated)]
                                :model model})]
      (or (:result response) {:entities [] :relationships []}))
    (catch Exception e
      (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Entity extraction failed for page"})
      {:entities [] :relationships []})))

(defn- extract-entities-from-visual-node!
  "Extracts entities from a visual node (image/table) using vision or text.
   
   Params:
   `node` - Map. Page node with :page.node/type, :page.node/image-data, :page.node/description.
   `model` - String. Model name for extraction.
   `config` - Map. LLM configuration.
   
   Returns:
   Map with :entities and :relationships keys (empty if extraction fails)."
  [node model config]
  (try
    (let [image-data (:page.node/image-data node)
          description (:page.node/description node)]
      (cond
        ;; Has image data - use vision
        image-data
        (let [b64 (bytes->base64 image-data)
               response (svar/ask! {:config config
                                    :spec ENTITY_EXTRACTION_SPEC
                                    :messages [(svar/system ENTITY_EXTRACTION_OBJECTIVE)
                                               (svar/user (or description "Extract entities from this image")
                                                          (svar/image b64 "image/png"))]
                                    :model model})]
          (or (:result response) {:entities [] :relationships []}))
        ;; Has description only - text extraction
        description
        (let [response (svar/ask! {:config config
                                    :spec ENTITY_EXTRACTION_SPEC
                                    :messages [(svar/system ENTITY_EXTRACTION_OBJECTIVE)
                                               (svar/user description)]
                                    :model model})]
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
   `model` - String. Model name for extraction.
   `config` - Map. LLM configuration.
   `opts` - Map. Options with :max-extraction-pages, :max-vision-rescan-nodes.
   
   Returns:
   Map with extraction statistics: :entities-extracted, :relationships-extracted, 
   :pages-processed, :extraction-errors, :visual-nodes-scanned."
  [db-info document model config opts]
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
                (let [result (extract-entities-from-page! text model config)]
                  (swap! entities-atom into (:entities result))
                  (swap! relationships-atom into (:relationships result)))
                (catch Exception _ (swap! errors-atom inc))))))
        ;; Extract from visual nodes (capped)
        (doseq [vnode visual-nodes]
          (when (< @vision-count-atom max-vision)
            (try
              (let [result (extract-entities-from-visual-node! vnode model config)]
                (swap! vision-count-atom inc)
                (swap! entities-atom into (:entities result))
                (swap! relationships-atom into (:relationships result)))
              (catch Exception _ (swap! errors-atom inc)))))))
    ;; Store entities and relationships in DB (two-phase)
    (let [entities @entities-atom
          relationships @relationships-atom
          name->uuid (atom {})]
      ;; Phase 1: Store entities, build name→UUID map
      (doseq [entity entities]
        (try
          (let [entity-id (java.util.UUID/randomUUID)
                entity-name (or (:entity/name entity) (:name entity) "unknown")
                entity-data (merge {:entity/id entity-id
                                    :entity/name entity-name
                                    :entity/type (or (:entity/type entity) (:type entity) :unknown)
                                    :entity/description (or (:entity/description entity) (:description entity) "")
                                    :entity/document-id (str doc-id)
                                    :entity/created-at (java.util.Date.)}
                                   (when-let [s (or (:entity/section entity) (:section entity))]
                                     {:entity/section s})
                                   (when-let [p (or (:entity/page entity) (:page entity))]
                                     {:entity/page (long p)}))]
            (swap! (:store db-info) update :entities conj entity-data)
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
              (swap! (:store db-info) update :relationships conj
                     {:relationship/id (java.util.UUID/randomUUID)
                      :relationship/type (or (:relationship/type rel) (:type rel) :unknown)
                      :relationship/source-entity-id src-id
                      :relationship/target-entity-id tgt-id
                      :relationship/description (or (:relationship/description rel) (:description rel) "")
                      :relationship/document-id (str doc-id)
                      :relationship/created-at (java.util.Date.)})))
          (catch Exception e
            (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Failed to store relationship"}))))
      (flush-store! db-info)
      {:entities-extracted (count entities)
       :relationships-extracted (count relationships)
       :pages-processed (count pages)
       :extraction-errors @errors-atom
       :visual-nodes-scanned @vision-count-atom})))

;; =============================================================================
;; Public API - Component-Based Architecture
;; =============================================================================

(defn create-env
  "Creates an RLM environment (component) for document ingestion and querying.
   
    The environment holds:
    - In-memory store for documents, learnings, and conversation history
    - LLM configuration for queries
    - SCI sandbox context with custom bindings
   
   Usage:
   ```clojure
   (def env (rlm/create-env {:config llm-config}))
   (rlm/register-fn! env 'my-fn (fn [x] (* x 2)) \"(my-fn x) - Doubles x\")
   (rlm/register-def! env 'MAX_RETRIES 3 \"MAX_RETRIES - Max retry attempts\")
   (rlm/ingest! env documents)
   (rlm/query! env \"What is X?\")
   (rlm/dispose! env)
   ```
   
   Params:
   - :config - Required. LLM config with :api-key, :base-url, :default-model.
   - :db-path - Optional. Path for persistent DB. If provided, data survives across sessions.
   
   Returns:
   RLM environment map (component). Pass to register-fn!, register-def!, ingest!, query!, dispose!."
  [{:keys [config db-path]}]
  (when-not config
    (anomaly/incorrect! "Missing :config" {:type :rlm/missing-config}))
  (let [api-key (:api-key config)
        base-url (:base-url config)
        model (:default-model config)
        depth-atom (atom 0)
        locals-atom (atom {})
        ;; Custom bindings for SCI (registered via register-fn!/register-def!)
        custom-bindings-atom (atom {})
        custom-docs-atom (atom [])
        db-info (if db-path
                  (create-persistent-db db-path)
                  (create-disposable-db {}))
        db-info-atom (atom db-info)
        ;; Initialize all schemas
        _ (init-message-history! db-info)
        _ (init-document-schema! db-info)
        _ (init-learning-schema! db-info)
        ;; Create query functions
        llm-query-fn (make-llm-query-fn model depth-atom api-key base-url)
        rlm-query-fn (make-rlm-query-fn model depth-atom api-key base-url db-info-atom)
        env-id (str (java.util.UUID/randomUUID))]
    {:env-id env-id
     :config config
     :depth-atom depth-atom
     :locals-atom locals-atom
     :custom-bindings-atom custom-bindings-atom
     :custom-docs-atom custom-docs-atom
     :db-info-atom db-info-atom
     :llm-query-fn llm-query-fn
     :rlm-query-fn rlm-query-fn
     :history-enabled? true}))

(defn register-fn!
  "Registers a function in the RLM environment's SCI sandbox.
   
   The function becomes available to the LLM during code execution.
   The doc-string is included in the system prompt so the LLM knows how to use it.
   
   Params:
   `env` - RLM environment from create-env.
   `sym` - Symbol. The function name (e.g., 'fetch-weather).
   `f` - Function. The implementation.
   `doc-string` - String. Documentation for the LLM (e.g., \"(fetch-weather city) - Returns weather data\").
   
   Returns:
   The environment (for chaining)."
  [env sym f doc-string]
  (when-not (:custom-bindings-atom env)
    (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))
  (when-not (symbol? sym)
    (anomaly/incorrect! "sym must be a symbol" {:type :rlm/invalid-sym :sym sym}))
  (when-not (fn? f)
    (anomaly/incorrect! "f must be a function" {:type :rlm/invalid-fn}))
  (when-not (string? doc-string)
    (anomaly/incorrect! "doc-string must be a string" {:type :rlm/invalid-doc}))
  (swap! (:custom-bindings-atom env) assoc sym f)
  (swap! (:custom-docs-atom env) conj {:type :fn :sym sym :doc doc-string})
  env)

(defn register-def!
  "Registers a constant/value in the RLM environment's SCI sandbox.
   
   The value becomes available to the LLM during code execution.
   The doc-string is included in the system prompt so the LLM knows about it.
   
   Params:
   `env` - RLM environment from create-env.
   `sym` - Symbol. The constant name (e.g., 'MAX_RETRIES).
   `value` - Any value. The constant value.
   `doc-string` - String. Documentation for the LLM (e.g., \"MAX_RETRIES - Maximum retry attempts\").
   
   Returns:
   The environment (for chaining)."
  [env sym value doc-string]
  (when-not (:custom-bindings-atom env)
    (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))
  (when-not (symbol? sym)
    (anomaly/incorrect! "sym must be a symbol" {:type :rlm/invalid-sym :sym sym}))
  (when-not (string? doc-string)
    (anomaly/incorrect! "doc-string must be a string" {:type :rlm/invalid-doc}))
  (swap! (:custom-bindings-atom env) assoc sym value)
  (swap! (:custom-docs-atom env) conj {:type :def :sym sym :doc doc-string})
  env)

(defn ingest!
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
  ([env documents] (ingest! env documents {}))
  ([env documents opts]
   (when-not (:db-info-atom env)
     (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))
   (when-not (rlm-spec/valid-documents? documents)
     (anomaly/incorrect! "Invalid documents - must be vector of PageIndex documents"
                         {:type :rlm/invalid-documents
                          :explanation (rlm-spec/explain-documents documents)}))
   (let [db-info @(:db-info-atom env)
          config (:config env)
          extract? (:extract-entities? opts false)
          extraction-model (or (:extraction-model opts) (:default-model config))
          base-results (mapv #(db-store-pageindex-document! db-info %) documents)
          results (if extract?
                    (mapv (fn [doc base-result]
                            (let [extraction-result (extract-entities-from-document! db-info doc extraction-model config opts)]
                              (merge base-result extraction-result)))
                          documents base-results)
                    base-results)]
      ;; Single flush after all documents are ingested
      (flush-store-now! db-info)
      results)))

(defn dispose!
  "Disposes an RLM environment and releases resources.
   
   For persistent DBs (created with :db-path), data is preserved.
   For disposable DBs, all data is deleted.
   
   Params:
   `env` - RLM environment from create-env."
  [env]
  (when-let [db-info-atom (:db-info-atom env)]
    (dispose-db! @db-info-atom)))

(defn query!
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
     - :min-score - Min eval score (default: 32/40).
     - :refine? - Enable refinement (default: true).
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
   (query! env query-str {}))
  ([env query-str {:keys [context spec model max-iterations max-refinements min-score
                           refine? learn? max-context-tokens max-recursion-depth verify-claims?
                           plan? debug?]
                    :or {max-iterations MAX_ITERATIONS max-refinements 1 min-score 32
                         refine? true learn? true max-recursion-depth DEFAULT_RECURSION_DEPTH verify-claims? false
                         plan? false debug? false}}]
   (when-not (:db-info-atom env)
     (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))
   (when-not query-str
     (anomaly/incorrect! "Missing query" {:type :rlm/missing-query}))
   (let [config (:config env)
         api-key (:api-key config)
         base-url (:base-url config)
         model (or model (:default-model config))
         ;; Rebuild SCI context with current context-data and custom bindings
         locals-atom (:locals-atom env)
         db-info-atom (:db-info-atom env)
         llm-query-fn (:llm-query-fn env)
         rlm-query-fn (:rlm-query-fn env)
         custom-bindings (when-let [atom (:custom-bindings-atom env)] @atom)
         custom-docs (when-let [atom (:custom-docs-atom env)] @atom)
         claims-atom (when verify-claims? (atom []))
         cite-bindings (when verify-claims?
                         {'CITE (make-cite-fn claims-atom)
                          'CITE-UNVERIFIED (make-cite-unverified-fn claims-atom)
                          'list-claims (make-list-claims-fn claims-atom)})
         cite-docs (when verify-claims?
                     [{:type :fn :sym 'CITE :doc "(CITE claim-text document-id page section quote) or (CITE claim-text document-id page section quote confidence) - Cite a claim with source evidence. Returns {:cited true :claim-id uuid :claim-text text}"}
                      {:type :fn :sym 'CITE-UNVERIFIED :doc "(CITE-UNVERIFIED claim-text) - Record a claim without source verification. Lower confidence."}
                      {:type :fn :sym 'list-claims :doc "(list-claims) - List all claims cited so far in this query."}
                      {:type :note :sym 'CITE-PRIORITY :doc "CITE is OPTIONAL. ALWAYS call (FINAL answer) as soon as you have the answer. Only use CITE BEFORE calling FINAL if the query explicitly asks for citations. Do NOT delay FINAL to gather citations."}])
         sci-ctx (create-sci-context context llm-query-fn rlm-query-fn locals-atom db-info-atom (merge custom-bindings cite-bindings))
         rlm-env (assoc env :sci-ctx sci-ctx :context context)
         env-id (:env-id env)]
     (binding [*rlm-ctx* {:rlm-env-id env-id :rlm-type :main :rlm-debug? debug? :rlm-phase :query}]
       (binding [*max-recursion-depth* max-recursion-depth]
         (rlm-debug! {:query query-str :model model :max-iterations max-iterations
                       :verify-claims? verify-claims? :plan? plan?
                       :refine? refine?} "RLM query! started")
          (let [start-time (System/nanoTime)
                examples (get-examples query-str {})
                db-info @db-info-atom
                ;; Optional planning phase — LLM outlines approach before code execution
                plan-context (when plan?
                               (let [plan-result (svar/ask! {:config config
                                                             :messages [(svar/system "You are a planning assistant. Given a query and available document tools, outline a clear 3-5 step approach to answer the query. Be specific about which tools to use and in what order. Do NOT write code — just describe the strategy.")
                                                                        (svar/user (str "Query: " query-str))]
                                                             :model model})]
                                 (when-let [plan (:result plan-result)]
                                   (str "<plan>\n" plan "\n</plan>"))))
                 ;; iteration-loop returns {:answer :trace :iterations} or {:answer :trace :iterations :status :locals}
                iteration-result (iteration-loop rlm-env query-str model api-key base-url max-iterations
                                                 {:output-spec spec
                                                  :examples examples
                                                  :max-context-tokens max-context-tokens
                                                  :custom-docs (into (or custom-docs []) cite-docs)
                                                  :pre-fetched-context plan-context})
               {:keys [answer trace iterations status]} iteration-result]
           (if status
               ;; Execution hit max iterations - return with trace
               (let [duration-ms (util/elapsed-since start-time)]
                 (rlm-debug! {:status status :iterations iterations :duration-ms duration-ms} "RLM query! finished (max iterations)")
                 (flush-store-now! db-info)
                (cond-> {:answer nil
                        :raw-answer (:result answer answer)
                        :status status
                        :trace trace
                        :iterations iterations
                        :duration-ms duration-ms}
                 verify-claims? (assoc :verified-claims (vec @claims-atom))))
             ;; Normal completion - refine and finalize
              (let [answer-value (:result answer answer)
                    answer-as-str (answer-str answer)
                    ;; Build documents for CoVe grounded verification
                    stored-docs (when-let [docs (seq (:documents @(:store db-info)))]
                                  (mapv (fn [doc]
                                          {:id (or (:document/id doc) (:document/name doc))
                                           :pages (mapv (fn [pn]
                                                          {:page (or (:page.node/page-id pn) "0")
                                                           :text (or (:page.node/content pn) "")})
                                                        (filter #(= (or (:document/id doc) (:document/name doc))
                                                                    (:page.node/document-id %))
                                                                (:page-nodes @(:store db-info))))})
                                        docs))
                    refine-opts (cond-> {:spec spec
                                        :messages [(svar/system (str "You are verifying and refining an answer to a specific query. "
                                                                     "Check the answer for accuracy, completeness, and correctness."))
                                                   (svar/user (str "<query>\n" query-str "\n</query>\n\n"
                                                                   "<answer>\n" answer-as-str "\n</answer>"))]
                                        :config config :model model
                                        :iterations max-refinements :threshold min-score}
                                  (seq stored-docs) (assoc :documents stored-docs))
                   raw-refine (if refine?
                                (svar/refine! refine-opts)
                                {:result answer-as-str :final-score nil :iterations-count 0})
                   refined-result {:answer (:result raw-refine)
                                   :eval-scores (:final-score raw-refine)
                                   :refinement-count (:iterations-count raw-refine)}
                   final-answer (if (and spec (string? (:answer refined-result)))
                                  (try
                                    (svar/str->data-with-spec (:answer refined-result) spec)
                                    (catch Exception _
                                       (:result (svar/ask! {:config config :spec spec
                                                            :messages [(svar/system "Extract structured data.")
                                                                       (svar/user (str "From:\n" (:answer refined-result)))]
                                                            :model model}))))
                                   ;; No spec - use original realized value if no refinement happened
                                  (if refine?
                                    (:answer refined-result)
                                    answer-value))
                    duration-ms (util/elapsed-since start-time)
                    history-tokens (count-history-tokens @db-info-atom)]
               (when (and learn? (:eval-scores refined-result))
                 (store-example! query-str (str-truncate (pr-str context) 200)
                                 (str final-answer) (get-in refined-result [:eval-scores :total] 0) nil))
                (when (and verify-claims? (seq @claims-atom))
                  (let [db-info @db-info-atom
                        query-id (UUID/randomUUID)]
                    (doseq [claim @claims-atom]
                      (try
                        (swap! (:store db-info) update :claims conj
                               (merge claim {:claim/query-id query-id
                                             :claim/verified? (boolean (get claim :claim/verified? true))}))
                        (flush-store! db-info)
                        (catch Exception e
                          (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Failed to store claim"}))))))
                (rlm-debug! {:iterations iterations :duration-ms duration-ms
                              :refinement-count (:refinement-count refined-result)
                              :answer-preview (str-truncate (pr-str final-answer) 200)} "RLM query! finished (success)")
                 ;; Single flush after query completion (all messages, claims, learnings batched)
                 (flush-store-now! db-info)
                 (cond-> {:answer final-answer
                         :raw-answer answer-value
                         :eval-scores (:eval-scores refined-result)
                         :refinement-count (:refinement-count refined-result)
                         :trace trace
                         :iterations iterations
                         :duration-ms duration-ms
                         :history-tokens history-tokens}
                  verify-claims? (assoc :verified-claims (vec @claims-atom)))))))))))

;; =============================================================================
;; Trace Pretty Printing
;; =============================================================================

(defn pprint-trace
  "Pretty-prints an RLM execution trace for debugging.
   
   Params:
   `trace` - Vector of trace entries from query! result.
   `opts` - Map, optional:
     - :max-response-length - Truncate LLM response (default: 500).
     - :max-code-length - Truncate code blocks (default: 300).
     - :max-result-length - Truncate execution results (default: 200).
     - :show-stdout? - Show stdout output (default: true).
   
   Returns:
   String with formatted trace output."
  ([trace] (pprint-trace trace {}))
  ([trace {:keys [max-response-length max-code-length max-result-length show-stdout?]
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
            (str/join "\n" (map format-iteration trace)))))))

(defn print-trace
  "Prints an RLM execution trace to stdout. See pprint-trace for options."
  ([trace] (println (pprint-trace trace)))
  ([trace opts] (println (pprint-trace trace opts))))
