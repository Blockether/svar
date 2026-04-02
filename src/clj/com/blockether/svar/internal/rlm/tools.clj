(ns com.blockether.svar.internal.rlm.tools
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [com.blockether.svar.internal.rlm.db :as db
    :refer [db-entity-stats db-get-document db-get-entity
            db-get-page-node db-get-toc-entry db-list-documents db-list-entities
            db-list-page-nodes db-list-relationships db-list-toc-entries db-search-entities db-search-page-nodes
            db-search-toc-entries db-store-toc-entry!
            str-includes? str-lower str-truncate]]
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

(defn- str-split [s re]
  (when s (str/split s (if (string? re) (re-pattern re) re))))

(defn- str-replace [s match replacement] (when s (str/replace s match replacement)))

(defn- str-trim [s] (when s (str/trim s)))

(defn- str-upper [s] (when s (str/upper-case s)))

(defn- str-blank? [s] (str/blank? s))

(defn- str-starts-with? [s prefix] (when s (str/starts-with? s prefix)))

(defn- str-ends-with? [s suffix] (when s (str/ends-with? s suffix)))

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
;; SCI Context Creation
;; =============================================================================

(defn create-sci-context
  "Creates the SCI sandbox context with all available bindings.

   Params:
   `context-data` - The data context to analyze
   `llm-query-fn` - Function for simple LLM text queries
   `db-info-atom` - Atom with database info (can be nil)
   `custom-bindings` - Map of symbol->value for custom bindings (can be nil)"
  [context-data llm-query-fn db-info-atom custom-bindings]
  (let [base-bindings {'context context-data
                       'llm-query llm-query-fn
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
        db-bindings (when db-info-atom
                      {;; Document functions - list/get stored documents
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
        inject-atom (atom nil)
        all-bindings (merge SAFE_BINDINGS base-bindings db-bindings
                       (or custom-bindings {})
                       {'__inject__ inject-atom})
        sci-ctx (sci/init {:namespaces {'user all-bindings
                                        'clojure.string {'split str/split 'join str/join 'replace str/replace
                                                         'trim str/trim 'lower-case str/lower-case 'upper-case str/upper-case
                                                         'starts-with? str/starts-with? 'ends-with? str/ends-with?
                                                         'includes? str/includes? 'blank? str/blank?
                                                         'split-lines str/split-lines 'triml str/triml 'trimr str/trimr
                                                         'capitalize str/capitalize 'reverse str/reverse
                                                         're-quote-replacement str/re-quote-replacement}
                                        'clojure.set {'union clojure.set/union 'intersection clojure.set/intersection
                                                      'difference clojure.set/difference 'subset? clojure.set/subset?
                                                      'superset? clojure.set/superset?}}
                           :ns-aliases {'str 'clojure.string
                                        'set 'clojure.set}
                           :classes {'java.util.regex.Pattern java.util.regex.Pattern
                                     'java.util.regex.Matcher java.util.regex.Matcher
                                     'java.time.LocalDate java.time.LocalDate
                                     'java.time.Period java.time.Period
                                     'java.util.UUID java.util.UUID}
                           :deny '[require import ns eval load-string read-string]})]
    {:sci-ctx sci-ctx
     :inject-atom inject-atom
     :initial-ns-keys (set (keys (sci/eval-string* sci-ctx "(ns-publics 'user)")))}))

;; =============================================================================
;; Var Index
;; =============================================================================

(defn build-var-index
  "Builds a formatted var index table from user-def'd vars in the SCI context.
   Filters out initial bindings (tools, helpers) using initial-ns-keys.
   Returns nil if no user vars exist.

   Each row shows: name | type | size | doc
   Doc comes from Clojure docstrings on def."
  [sci-ctx initial-ns-keys]
  (try
    (let [var-info (sci/eval-string* sci-ctx
                     "(into {} (for [[s v] (ns-publics 'user)] [s {:val @v :doc (:doc (meta v))}]))")
          entries (->> var-info
                    (remove (fn [[sym _]] (contains? initial-ns-keys sym)))
                    (remove (fn [[_ {:keys [val]}]] (fn? val)))
                    (sort-by key)
                    (mapv (fn [[sym {:keys [val doc]}]]
                            (let [type-label (cond
                                               (nil? val) "nil"
                                               (map? val) "map"
                                               (vector? val) "vector"
                                               (set? val) "set"
                                               (sequential? val) "seq"
                                               (string? val) "string"
                                               (integer? val) "int"
                                               (float? val) "float"
                                               (boolean? val) "bool"
                                               (keyword? val) "keyword"
                                               (symbol? val) "symbol"
                                               :else (.getSimpleName (class val)))
                                  size (cond
                                         (nil? val) "\u2014"
                                         (string? val) (str (count val) " chars")
                                         (coll? val) (str (count val) " items")
                                         :else "\u2014")]
                              {:name (str sym) :type type-label :size size
                               :doc (if doc (str-truncate doc 80) "\u2014")}))))]
      (when (seq entries)
        (let [max-name (max 4 (apply max (map #(count (:name %)) entries)))
              max-type (max 4 (apply max (map #(count (:type %)) entries)))
              max-size (max 4 (apply max (map #(count (:size %)) entries)))
              pad (fn [s n] (str s (apply str (repeat (max 0 (- n (count s))) \space))))
              header (str "  " (pad "name" max-name) " | " (pad "type" max-type) " | " (pad "size" max-size) " | doc")
              sep (str "  " (apply str (repeat max-name \-)) "-+-" (apply str (repeat max-type \-)) "-+-" (apply str (repeat max-size \-)) "-+----")
              rows (map (fn [{:keys [name type size doc]}]
                          (str "  " (pad name max-name) " | " (pad type max-type) " | " (pad size max-size) " | " doc))
                     entries)]
          (str/join "\n" (concat [header sep] rows)))))
    (catch Exception _ nil)))

;; =============================================================================
;; SCI Context Helpers
;; =============================================================================

(defn sci-update-binding!
  "Update a binding in an existing SCI context via inject-atom.
   Sets the inject-atom to val, then evals (def sym @__inject__) in SCI."
  [sci-ctx inject-atom sym val]
  (reset! inject-atom val)
  (sci/eval-string* sci-ctx (str "(def " sym " @__inject__)")))
