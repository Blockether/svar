(ns com.blockether.svar.internal.rlm.tools
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [com.blockether.svar.internal.rlm.db :as db :refer :all]
   [com.blockether.svar.internal.spec :as spec]
   [com.blockether.svar.internal.util :as util]
   [datalevin.core :as d]
   [sci.core :as sci]))

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

(defn- str-join [sep coll] (str/join sep coll))

(defn- str-split [s re] (when s (str/split s re)))

(defn- str-replace [s match replacement] (when s (str/replace s match replacement)))

(defn- str-trim [s] (when s (str/trim s)))

(defn- str-upper [s] (when s (str/upper-case s)))

(defn- str-blank? [s] (str/blank? s))

(defn- str-starts-with? [s prefix] (when s (str/starts-with? s prefix)))

(defn- str-ends-with? [s suffix] (when s (str/ends-with? s suffix)))

;; =============================================================================
;; Stdout Truncation (RLM Paper §3: constant-size metadata)
;; =============================================================================

(defn truncate-for-history
  "Truncates output for conversation history, adding length metadata."
  [s max-chars]
  (if (or (nil? s) (<= (count s) max-chars))
    s
    (str (subs s 0 max-chars)
         "\n... [" (- (count s) max-chars) " more chars — store in a variable with (def my-var ...)]")))

;; =============================================================================
;; Raw Text Access (RLM Paper §3: symbolic handle to prompt P)
;; =============================================================================

(defn build-raw-text
  "Concatenates all page node content from Datalevin conn into a single string."
  [conn]
  (when conn
    (->> (d/q '[:find [(pull ?e [:page.node/content :page.node/document-id :page.node/page-id :page.node/id]) ...]
                :where [?e :page.node/id _]]
              (d/db conn))
         (sort-by (juxt :page.node/document-id :page.node/page-id :page.node/id))
         (keep :page.node/content)
         (str/join "\n"))))

(defn build-page-texts
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

(defn realize-value
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

(defn make-search-learnings-fn
  "Creates a function for the LLM to search learnings semantically.
   Returns learnings with :learning/id for voting. Supports optional tag filtering."
  [db-info-atom]
  (fn search-learnings
    ([query] (search-learnings query 5))
    ([query top-k] (search-learnings query top-k nil))
    ([query top-k tags]
     (if-let [db-info @db-info-atom]
       (db-get-learnings db-info query (cond-> {:top-k top-k}
                                         (seq tags) (assoc :tags tags)))
       []))))

(defn make-learning-stats-fn
  "Creates a function for the LLM to get learning statistics."
  [db-info-atom]
  (fn learning-stats []
    (if-let [db-info @db-info-atom]
      (db-learning-stats db-info)
      {:total-learnings 0 :active-learnings 0 :decayed-learnings 0
       :with-context 0 :without-context 0
       :total-votes 0 :total-applications 0
       :all-tags []})))

(defn make-list-learning-tags-fn
  "Creates a function for the LLM to list all known tags with their definitions."
  [db-info-atom]
  (fn list-learning-tags []
    (if-let [db-info @db-info-atom]
      (or (db-list-tags db-info) [])
      [])))

;; =============================================================================
;; PageIndex Document Storage System
;; =============================================================================

;; -----------------------------------------------------------------------------
;; Document Storage
;; -----------------------------------------------------------------------------

(defn make-store-toc-entry-fn
  "Creates store-document-toc! — store a PageIndex TOC entry."
  [db-info-atom]
  (fn store-document-toc!
    ([entry] (store-document-toc! entry nil))
    ([entry doc-id]
     (when-let [db-info @db-info-atom]
       (db-store-toc-entry! db-info entry (or doc-id "manual"))))))

(defn make-search-toc-entries-fn
  "Creates search-document-toc — search document TOC entries by title/description."
  [db-info-atom]
  (fn search-document-toc
    ([query] (search-document-toc query 10))
    ([query top-k]
     (if-let [db-info @db-info-atom]
       (db-search-toc-entries db-info query {:top-k top-k})
       []))))

(defn make-get-toc-entry-fn
  "Creates get-document-toc-entry — get a document TOC entry by ID."
  [db-info-atom]
  (fn get-document-toc-entry [entry-id]
    (when-let [db-info @db-info-atom]
      (db-get-toc-entry db-info entry-id))))

(defn make-list-toc-entries-fn
  "Creates list-document-toc — list document TOC entries."
  [db-info-atom]
  (fn list-document-toc
    ([] (list-document-toc {}))
    ([opts]
     (if-let [db-info @db-info-atom]
       (db-list-toc-entries db-info opts)
       []))))

;; -----------------------------------------------------------------------------
;; Document Page SCI Functions (for LLM to search actual document content)
;; -----------------------------------------------------------------------------

(defn make-search-page-nodes-fn
  "Creates search-document-pages — fulltext search across document page content."
  [db-info-atom]
  (fn search-document-pages
    ([query] (search-document-pages query 10))
    ([query top-k]
     (if-let [db-info @db-info-atom]
       (db-search-page-nodes db-info query {:top-k top-k})
       []))
    ([query top-k opts]
     (if-let [db-info @db-info-atom]
       (db-search-page-nodes db-info query (merge {:top-k top-k} opts))
       []))))

(def ^:private P_ADD_PAGE_SIZE
  "Characters per chunk when P-add! returns a document as a vector of pages."
  4000)

(defn- chunk-text
  "Splits text into ~4000 char pages at paragraph boundaries."
  [text]
  (let [page-size P_ADD_PAGE_SIZE]
    (loop [remaining (str/split text #"\n\n+")
           current [] current-size 0 result []]
      (if (empty? remaining)
        (if (seq current)
          (conj result (str/join "\n\n" current))
          result)
        (let [para (first remaining)
              para-size (count para)]
          (if (and (> current-size 0) (> (+ current-size para-size) page-size))
            (recur remaining [] 0 (conj result (str/join "\n\n" current)))
            (recur (rest remaining) (conj current para)
                   (+ current-size para-size) result)))))))

(defn make-get-page-node-fn
  "Creates P-add! — fetches content using Datalevin lookup ref syntax.

   Returns:
     [:page.node/id id]    → content string (single node)
     [:document/id id]     → vector of ~4000 char page strings (chunked)
     [:document.toc/id id] → TOC entry title/description string

   The LLM stores results in variables:
     (def clause (P-add! [:page.node/id \"abc\"]))
     (def doc (P-add! [:document/id \"doc-1\"]))
     (count doc)      ;; number of pages
     (nth doc 5)      ;; page 5 content"
  [db-info-atom]
  (fn P-add! [lookup-ref]
    (when-let [{:keys [conn] :as db-info} @db-info-atom]
      (when (and (vector? lookup-ref) (= 2 (count lookup-ref)))
        (let [[attr id] lookup-ref]
          (case attr
            :page.node/id
            (when-let [node (db-get-page-node db-info id)]
              (or (:page.node/content node) (:page.node/description node) ""))

            :document/id
            (let [nodes (d/q '[:find [(pull ?e [:page.node/content :page.node/page-id]) ...]
                               :in $ ?doc-id
                               :where [?e :page.node/document-id ?doc-id]]
                             (d/db conn) id)]
              (when (seq nodes)
                (let [full-text (->> nodes
                                     (sort-by :page.node/page-id)
                                     (keep :page.node/content)
                                     (str/join "\n"))]
                  (chunk-text full-text))))

            :document.toc/id
            (when-let [toc (db-get-toc-entry db-info id)]
              (or (:document.toc/description toc) (:document.toc/title toc) ""))

            ;; Unknown attribute
            (throw (ex-info (str "P-add! unknown lookup attribute: " attr
                                 ". Use :page.node/id, :document/id, or :document.toc/id")
                            {:type :svar/invalid-lookup-ref :attr attr :id id}))))))))

(defn make-list-page-nodes-fn
  "Creates list-document-pages — list/filter document page nodes."
  [db-info-atom]
  (fn list-document-pages
    ([] (list-document-pages {}))
    ([opts]
     (if-let [db-info @db-info-atom]
       (db-list-page-nodes db-info opts)
       []))))

(defn make-list-documents-fn
  "Creates a function for the LLM to list stored documents with abstracts and TOC."
  [db-info-atom]
  (fn list-documents
    ([] (list-documents {}))
    ([opts]
     (if-let [db-info @db-info-atom]
       (db-list-documents db-info opts)
       []))))

(defn make-get-document-fn
  "Creates a function for the LLM to get a document by ID."
  [db-info-atom]
  (fn get-document [doc-id]
    (when-let [db-info @db-info-atom]
      (db-get-document db-info doc-id))))

;; -----------------------------------------------------------------------------
;; Document Entity SCI Functions (for LLM to search/get extracted entities)
;; -----------------------------------------------------------------------------

(defn make-search-entities-fn
  "Creates search-document-entities — search extracted entities by name/description."
  [db-info-atom]
  (fn search-document-entities
    ([query] (search-document-entities query 10))
    ([query top-k] (search-document-entities query top-k {}))
    ([query top-k opts]
     (if-let [db-info @db-info-atom]
       (db-search-entities db-info query (merge {:top-k top-k} opts))
       []))))

(defn make-get-entity-fn
  "Creates get-document-entity — get an extracted entity by ID."
  [db-info-atom]
  (fn get-document-entity [entity-id]
    (when-let [db-info @db-info-atom]
      (db-get-entity db-info entity-id))))

(defn make-list-entities-fn
  "Creates list-document-entities — list extracted entities with optional filters."
  [db-info-atom]
  (fn list-document-entities
    ([] (list-document-entities {}))
    ([opts]
     (if-let [db-info @db-info-atom]
       (db-list-entities db-info opts)
       []))))

(defn make-list-relationships-fn
  "Creates list-document-relationships — list relationships for an entity."
  [db-info-atom]
  (fn list-document-relationships
    ([entity-id] (list-document-relationships entity-id {}))
    ([entity-id opts]
     (if-let [db-info @db-info-atom]
       (db-list-relationships db-info entity-id opts)
       []))))

(defn make-entity-stats-fn
  "Creates document-entity-stats — entity/relationship aggregate statistics."
  [db-info-atom]
  (fn document-entity-stats []
    (if-let [db-info @db-info-atom]
      (db-entity-stats db-info)
      {:total-entities 0 :types {} :total-relationships 0})))

(defn make-cite-fn
  "Creates CITE function for the LLM to cite claims with sources."
  [claims-atom]
  (fn CITE
    ([claim-text document-id page section quote]
     (CITE claim-text document-id page section quote 1.0))
    ([claim-text document-id page section quote confidence]
     (let [claim {:claim/id (util/uuid)
                  :claim/text claim-text
                  :claim/document-id document-id
                  :claim/page (long (if (string? page) (Long/parseLong page) page))
                  :claim/section section
                  :claim/quote quote
                  :claim/confidence (float (if (string? confidence) (Double/parseDouble confidence) confidence))
                  :claim/created-at (java.util.Date.)}]
       (swap! claims-atom conj claim)
       {:cited true :claim-id (:claim/id claim) :claim-text claim-text}))))

(defn make-cite-unverified-fn
  "Creates CITE-UNVERIFIED function for claims without source verification."
  [claims-atom]
  (fn CITE-UNVERIFIED
    [claim-text]
    (let [claim {:claim/id (util/uuid)
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

(defn make-list-claims-fn
  "Creates list-claims function to retrieve accumulated claims."
  [claims-atom]
  (fn list-claims
    []
    (vec @claims-atom)))

;; =============================================================================
;; History Query Functions (for LLM to call)
;; =============================================================================

(defn make-search-history-fn
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

(defn make-get-history-fn
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

(defn make-history-stats-fn
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

(defn create-sci-context
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
                       'FINAL (fn
                                ([answer]
                                 (let [v (realize-value answer)
                                       v (if (and (map? v) (vector? (:answer v)))
                                           (clojure.string/join (map str (:answer v)))
                                           v)]
                                   {:rlm/final true :rlm/answer {:result v :type (type v)}
                                    :rlm/confidence (if (map? answer) (get answer :confidence :high) :high)
                                    :rlm/sources (when (map? answer) (get answer :sources))
                                    :rlm/reasoning (when (map? answer) (get answer :reasoning))
                                    :rlm/learn (when (map? answer) (get answer :learn))}))
                                ([answer opts]
                                 (let [v (realize-value answer)
                                       v (if (vector? v)
                                           (clojure.string/join (map str v))
                                           v)]
                                   {:rlm/final true
                                    :rlm/answer {:result v :type (type v)}
                                    :rlm/confidence (get opts :confidence :high)
                                    :rlm/sources (get opts :sources)
                                    :rlm/reasoning (get opts :reasoning)
                                    :rlm/learn (get opts :learn)})))

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
                       ;; Document page functions - search returns brief metadata, P-add! fetches full content
                       'search-document-pages (make-search-page-nodes-fn db-info-atom)
                       'P-add! (make-get-page-node-fn db-info-atom)
                       'list-document-pages (make-list-page-nodes-fn db-info-atom)
                       ;; Document TOC functions - table of contents
                       'store-document-toc! (make-store-toc-entry-fn db-info-atom)
                       'search-document-toc (make-search-toc-entries-fn db-info-atom)
                       'get-document-toc-entry (make-get-toc-entry-fn db-info-atom)
                       'list-document-toc (make-list-toc-entries-fn db-info-atom)
                       ;; Document entity functions - extracted entities
                       'search-document-entities (make-search-entities-fn db-info-atom)
                       'get-document-entity (make-get-entity-fn db-info-atom)
                       'list-document-entities (make-list-entities-fn db-info-atom)
                       'list-document-relationships (make-list-relationships-fn db-info-atom)
                       'document-entity-stats (make-entity-stats-fn db-info-atom)})
        ;; Learnings functions - DB-backed with voting and tags (pass db-info-atom)
        learning-bindings (if db-info-atom
                            {'search-learnings (make-search-learnings-fn db-info-atom)
                             'learning-stats (make-learning-stats-fn db-info-atom)
                             'list-learning-tags (make-list-learning-tags-fn db-info-atom)}
                            {})
        ;; Raw text access (RLM paper §3: symbolic handle to P)
        ;; P = the symbolic handle to the input. Per the paper: P lives as a variable
        ;; in the REPL, NOT in the context window. The LLM uses (subs P 0 1000),
        ;; (re-seq #"pattern" P), (P-page n), etc. to explore it programmatically.
        ;;
        ;; P is the structured workspace — the ENTIRE context surface the LLM sees.
        ;; :last-iteration — auto-managed by system, last execution results
        ;; :context        — vector of strings, LLM-managed working memory
        ;; :learnings      — priority-ranked insights [{:text str :priority :high|:medium|:low}]
        initial-context (if (and context-data (not= context-data ""))
                          [(str "[initial] " (if (string? context-data) context-data (pr-str context-data)))]
                          [])
        p-atom (atom {:context initial-context
                      :learnings []
                      :vars {}})
        raw-text-bindings {'P-atom p-atom
                           'P p-atom
                           ;; Context management — stack of strings (newest last, oldest trimmed first)
                           'ctx-add! (fn [text]
                                       (swap! p-atom update :context conj (str text))
                                       (str "Added to context (" (count (:context @p-atom)) " items)"))
                           'ctx-remove! (fn [idx]
                                          (swap! p-atom update :context
                                                 (fn [ctx]
                                                   (let [i (if (neg? idx) (+ (count ctx) idx) idx)]
                                                     (into (subvec ctx 0 i) (subvec ctx (inc i))))))
                                          (str "Removed from context (" (count (:context @p-atom)) " items)"))
                           'ctx-clear! (fn [] (swap! p-atom assoc :context []) "Context cleared")
                           'ctx-replace! (fn [from-idx to-idx replacement]
                                           (swap! p-atom update :context
                                                  (fn [ctx]
                                                    (let [before (subvec ctx 0 from-idx)
                                                          after  (when (< (inc to-idx) (count ctx))
                                                                   (subvec ctx (inc to-idx)))]
                                                      (into (conj before (str replacement))
                                                            (or after [])))))
                                           (str "Replaced [" from-idx "-" to-idx "] (" (count (:context @p-atom)) " items)"))
                           ;; Learnings — priority-based
                           'learn! (fn
                                     ([text] (swap! p-atom update :learnings conj {:text (str text) :priority :medium})
                                      (str "Learned (" (count (:learnings @p-atom)) " total)"))
                                     ([text priority] (swap! p-atom update :learnings conj {:text (str text) :priority priority})
                                      (str "Learned (" (count (:learnings @p-atom)) " total)")))
                           'forget! (fn [idx] (swap! p-atom update :learnings
                                                     (fn [ls] (into (subvec ls 0 idx) (subvec ls (inc idx)))))
                                     (str "Forgot learning (" (count (:learnings @p-atom)) " remaining)"))
                           ;; Cross-query persistent variables
                           'persist! (fn [name value]
                                      (swap! p-atom assoc-in [:vars (str name)] (realize-value value))
                                      (str "Persisted '" name "' (survives across queries)"))
                           'recall (fn [name]
                                    (get-in @p-atom [:vars (str name)]))
                           'persisted (fn []
                                       (let [vs (:vars @p-atom)]
                                         (when (seq vs)
                                           (mapv (fn [[k v]] {:name k :type (str (type v))
                                                              :preview (subs (pr-str v) 0 (min 100 (count (pr-str v))))})
                                                 vs))))}
        all-bindings (merge SAFE_BINDINGS base-bindings rlm-bindings db-bindings
                            learning-bindings raw-text-bindings
                            (or custom-bindings {}))]
    {:sci-ctx (sci/init {:namespaces {'user all-bindings}
                         :classes {'java.util.regex.Pattern java.util.regex.Pattern
                                   'java.util.regex.Matcher java.util.regex.Matcher
                                   'java.time.LocalDate java.time.LocalDate
                                   'java.time.Period java.time.Period
                                   'java.util.UUID java.util.UUID}
                         :deny '[require import ns eval load-string read-string]})
     :p-atom p-atom}))
