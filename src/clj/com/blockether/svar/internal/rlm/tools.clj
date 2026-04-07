(ns com.blockether.svar.internal.rlm.tools
  (:require
   #_{:clj-kondo/ignore [:unused-namespace]}
   [clojure.set :as set]
   [clojure.string :as str]
   #_{:clj-kondo/ignore [:unused-namespace]}
   [clojure.walk :as walk]
   [com.blockether.svar.internal.rlm.db :as db
    :refer [db-get-entity db-get-page-node db-get-toc-entry
            db-list-relationships db-search-entities db-search-page-nodes
            db-search-toc-entries record-page-access! str-truncate]]
   [com.blockether.svar.internal.spec :as spec]
   [com.blockether.svar.internal.util :as util]
   [datalevin.core :as d]
   [sci.core :as sci]))

(defn- ns->sci-map
  "Builds an SCI :namespaces entry map from a Clojure namespace's public vars.
   Pulls the entire ns-publics surface so models can use everything a real
   namespace offers without us enumerating fns manually.
   NOTE: prefer sci/copy-ns for standard namespaces (preserves doc/arglists).
   Use this only for namespaces where copy-ns fails (e.g. charred.api)."
  [ns-sym]
  (require ns-sym)
  (into {} (for [[sym v] (ns-publics (the-ns ns-sym))
                 :when (and (var? v) (not (:macro (meta v))))]
             [sym @v])))

(def EXTRA_BINDINGS
  "Extra bindings beyond what SCI provides by default.
   SCI already ships with all of clojure.core. We only add
   Clojure 1.11/1.12 additions that SCI doesn't have yet.
   Models use str/join, set/union etc. via namespace aliases."
  {'abs abs, 'parse-long parse-long, 'parse-double parse-double,
   'parse-boolean parse-boolean, 'parse-uuid parse-uuid,
   'infinite? infinite?, 'NaN? NaN?})

;; =============================================================================
;; Debug Logging
;; =============================================================================

(def ^:private REALIZE_LAZY_LIMIT
  "Upper bound on elements pulled from a lazy sequence by `realize-value`.
   Prevents OOM when models feed infinite seqs like `(range)` or
   `(iterate inc 0)` into the result path. Kept low (100) by default -
   the model can explicitly `(vec (take n my-seq))` for larger slices."
  100)

(defn realize-value
  "Recursively realizes lazy sequences in a value to prevent opaque LazySeq@hash.
   Converts lazy seqs to vectors (bounded), walks into maps/vectors/sets.
   Lazy seqs longer than REALIZE_LAZY_LIMIT are truncated with a trailing
   marker so downstream code can still serialize them safely."
  [v]
  (cond
    (instance? clojure.lang.LazySeq v)
    (let [head (take (inc REALIZE_LAZY_LIMIT) v)
          realized (mapv realize-value (take REALIZE_LAZY_LIMIT head))]
      (if (> (count head) REALIZE_LAZY_LIMIT)
        (conj realized (str "...<truncated lazy seq at " REALIZE_LAZY_LIMIT " elements>"))
        realized))
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

;; -----------------------------------------------------------------------------
;; Unified Document Tools
;; -----------------------------------------------------------------------------

(def ^:private P_ADD_PAGE_SIZE
  "Characters per chunk when fetch-content returns a document as a vector of pages."
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

(defn make-search-documents-fn
  "Creates search-documents — unified search across pages, TOC, and entities.

   Usage:
     (search-documents \"query\")                     ;; searches everywhere (default)
     (search-documents \"query\" {:in :pages})        ;; pages only
     (search-documents \"query\" {:in :toc})          ;; TOC only
     (search-documents \"query\" {:in :entities})     ;; entities only
     (search-documents \"query\" {:top-k 20 :document-id \"doc-1\"})

   Returns:
     :in omitted → {:pages [...] :toc [...] :entities [...]}
     :in set     → vector of results for that target"
  [db-info-atom]
  (fn search-documents
    ([query] (search-documents query {}))
    ([query {:keys [in top-k document-id type] :or {top-k 10}}]
     (if-let [db-info @db-info-atom]
       (let [do-pages #(let [results (db-search-page-nodes db-info query
                                       (cond-> {:top-k top-k}
                                         document-id (assoc :document-id document-id)
                                         type (assoc :type type)))]
                         ;; Track search hit access (weight 0.2) for returned pages
                         (doseq [page-id (distinct (keep :page.node/page-id results))]
                           (record-page-access! db-info page-id 0.2))
                         results)
             do-toc #(db-search-toc-entries db-info query {:top-k top-k})
             do-ents #(db-search-entities db-info query
                        (cond-> {:top-k top-k}
                          document-id (assoc :document-id document-id)
                          type (assoc :type type)))]
         (case in
           :pages (do-pages)
           :toc (do-toc)
           :entities (do-ents)
           {:pages (do-pages) :toc (do-toc) :entities (do-ents)}))
       (if in [] {:pages [] :toc [] :entities []})))))

(defn make-fetch-content-fn
  "Creates fetch-content — fetches content using Datalevin lookup ref syntax.

   Returns:
     [:page.node/id id]    → content string
     [:document/id id]     → vector of ~4000 char page strings (chunked)
     [:document.toc/id id] → TOC entry description string
     [:entity/id id]       → {:entity {...} :relationships [...]}

   The LLM stores results in variables:
     (def clause (fetch-content [:page.node/id \"abc\"]))
     (def doc (fetch-content [:document/id \"doc-1\"]))
     (count doc)      ;; number of pages
     (nth doc 5)      ;; page 5 content
     (def p (fetch-content [:entity/id \"e1\"]))
     (:entity p)      ;; entity map
     (:relationships p) ;; connected entities"
  [db-info-atom]
  (fn fetch-content [lookup-ref]
    (when-let [{:keys [conn] :as db-info} @db-info-atom]
      (when (and (vector? lookup-ref) (= 2 (count lookup-ref)))
        (let [[attr id] lookup-ref]
          (case attr
            :page.node/id
            (when-let [node (db-get-page-node db-info id)]
              ;; Track page access (weight 1.0) — resolve node's page-id
              (when-let [page-id (:page.node/page-id node)]
                (record-page-access! db-info page-id 1.0))
              (or (:page.node/content node) (:page.node/description node) ""))

            :document/id
            (let [nodes (d/q '[:find [(pull ?e [:page.node/content :page.node/page-id]) ...]
                               :in $ ?doc-id
                               :where [?e :page.node/document-id ?doc-id]]
                          (d/db conn) id)
                  ;; Track access for all pages in document (weight 1.0)
                  page-ids (distinct (keep :page.node/page-id nodes))]
              (doseq [pid page-ids]
                (record-page-access! db-info pid 1.0))
              (when (seq nodes)
                (let [full-text (->> nodes
                                  (sort-by :page.node/page-id)
                                  (keep :page.node/content)
                                  (str/join "\n"))]
                  (chunk-text full-text))))

            :document.toc/id
            (when-let [toc (db-get-toc-entry db-info id)]
              (or (:document.toc/description toc) (:document.toc/title toc) ""))

            :entity/id
            (when-let [entity (db-get-entity db-info id)]
              {:entity entity
               :relationships (db-list-relationships db-info id {})})

            (throw (ex-info (str "fetch-content unknown lookup attribute: " attr
                              ". Use :page.node/id, :document/id, :document.toc/id, or :entity/id")
                     {:type :svar/invalid-lookup-ref :attr attr :id id}))))))))

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
                       ;; Date helper functions
                       'parse-date parse-date 'date-before? date-before? 'date-after? date-after?
                       'days-between days-between 'date-plus-days date-plus-days
                       'date-minus-days date-minus-days 'date-format date-format 'today-str today-str}
        db-bindings (when db-info-atom
                      {;; Unified document tools
                       'search-documents (make-search-documents-fn db-info-atom)
                       'fetch-content (make-fetch-content-fn db-info-atom)})
        all-bindings (merge EXTRA_BINDINGS base-bindings db-bindings
                       (or custom-bindings {}))
        ;; Proper SCI namespaces via sci/copy-ns (preserves doc, arglists, meta)
        str-ns  (sci/create-ns 'clojure.string nil)
        set-ns  (sci/create-ns 'clojure.set nil)
        walk-ns (sci/create-ns 'clojure.walk nil)
        ;; zprint/lazytest: can't use copy-ns (macros), manual requiring-resolve
        zp-resolve (fn [sym] (deref (requiring-resolve (symbol "zprint.core" (str sym)))))
        lt-resolve (fn [sym] (deref (requiring-resolve (symbol "lazytest.core" (str sym)))))
        sci-ctx (sci/init {:namespaces {'user all-bindings
                                        'clojure.string (sci/copy-ns clojure.string str-ns)
                                        'clojure.set (sci/copy-ns clojure.set set-ns)
                                        'clojure.walk (sci/copy-ns clojure.walk walk-ns)
                                        ;; fast-edn: copy-ns may not work (.cljc), use ns->sci-map
                                        'fast-edn.core (ns->sci-map 'fast-edn.core)
                                        'clojure.edn (ns->sci-map 'fast-edn.core)
                                        ;; zprint: manual bindings
                                        'zprint.core {'zprint-str (zp-resolve 'zprint-str)
                                                      'zprint (zp-resolve 'zprint)
                                                      'czprint-str (zp-resolve 'czprint-str)
                                                      'czprint (zp-resolve 'czprint)
                                                      'zprint-file-str (zp-resolve 'zprint-file-str)
                                                      'set-options! (zp-resolve 'set-options!)
                                                      'configure-all! (zp-resolve 'configure-all!)}
                                        'clojure.pprint {'pprint (zp-resolve 'zprint)
                                                         'pprint-str (zp-resolve 'zprint-str)}
                                        ;; lazytest: fn-based API for testing in sandbox
                                        'lazytest.core {'expect-fn (lt-resolve 'expect-fn)
                                                        'ok? (lt-resolve 'ok?)
                                                        'throws? (lt-resolve 'throws?)
                                                        'causes? (lt-resolve 'causes?)
                                                        'causes-with-msg? (lt-resolve 'causes-with-msg?)}
                                        ;; clojure.test alias -> lazytest fn API for model compat
                                        'clojure.test {'is (lt-resolve 'expect-fn)
                                                       'throws? (lt-resolve 'throws?)}
                                        ;; charred: ns->sci-map (no macros, works fine)
                                        'charred.api (ns->sci-map 'charred.api)}
                           :ns-aliases {'str 'clojure.string
                                        'edn 'fast-edn.core
                                        'zp 'zprint.core
                                        'pprint 'clojure.pprint
                                        'pp 'clojure.pprint
                                        'set 'clojure.set
                                        'walk 'clojure.walk
                                        'json 'charred.api
                                        'lt 'lazytest.core
                                        'test 'clojure.test}
                           :classes {'java.lang.Character Character
                                     'java.lang.Math Math
                                     'java.lang.String String
                                     'java.lang.Integer Integer
                                     'java.lang.Long Long
                                     'java.lang.Double Double
                                     'java.lang.Boolean Boolean
                                     'java.util.Collections java.util.Collections
                                     'java.util.Arrays java.util.Arrays
                                     'java.util.regex.Pattern java.util.regex.Pattern
                                     'java.util.regex.Matcher java.util.regex.Matcher
                                     'java.time.LocalDate java.time.LocalDate
                                     'java.time.Period java.time.Period
                                     'java.util.UUID java.util.UUID
                                     'clojure.lang.PersistentQueue clojure.lang.PersistentQueue
                                     'java.math.BigInteger java.math.BigInteger
                                     'java.math.BigDecimal java.math.BigDecimal}
                           ;; Bare class imports matching Clojure/Babashka defaults
                           :imports '{;; Safe java.lang types (NO Object, Thread, Class - reflection/DoS)
                                      Boolean java.lang.Boolean
                                      Byte java.lang.Byte
                                      Character java.lang.Character
                                      Comparable java.lang.Comparable
                                      Double java.lang.Double
                                      Exception java.lang.Exception
                                      Float java.lang.Float
                                      Integer java.lang.Integer
                                      Long java.lang.Long
                                      Math java.lang.Math
                                      Number java.lang.Number
                                      Short java.lang.Short
                                      String java.lang.String
                                      StringBuilder java.lang.StringBuilder
                                      ;; Utility classes
                                      Arrays java.util.Arrays
                                      Collections java.util.Collections
                                      UUID java.util.UUID
                                      Pattern java.util.regex.Pattern
                                      Matcher java.util.regex.Matcher
                                      LocalDate java.time.LocalDate
                                      PersistentQueue clojure.lang.PersistentQueue
                                      BigInteger java.math.BigInteger
                                      BigDecimal java.math.BigDecimal}
                           :deny '[;; No code loading / evaluation
                                   require import ns eval load-string load-file
                                   read-string find-ns
                                   ;; No filesystem I/O
                                   slurp spit
                                   ;; No var mutation from sandbox (alter-var-root allowed — needed by letfn in SCI)
                                   intern
                                   ;; No shell / process execution
                                   sh
                                   ;; No IO handles
                                   *in* *out* *err* *command-line-args*]})]
    ;; Inject doc metadata so (doc fn-name) works in SCI
    (doseq [[sym doc args] [['llm-query "Ask a sub-LLM anything. Returns text or structured data." '([prompt] [prompt {:spec spec}])]
                            ['llm-query-batch "Parallel batch of LLM sub-calls. Returns vector of results." '([[prompt1 prompt2 ...]])]
                            ['request-more-iterations "Request n more iterations. Returns {:granted n :new-budget N}." '([n])]
                            ['spec "Create a structured output spec." '([& fields])]
                            ['field "Create a spec field." '([& kvs])]
                            ['context "The data context passed to query-env!." nil]
                            ['parse-date "Parse ISO date string to LocalDate." '([s])]
                            ['today-str "Today as ISO-8601 string." '([])]
                             ;; Document navigation — 2 unified tools
                            ['search-documents "Search across documents. No :in = search everywhere (pages+toc+entities).\n  (search-documents \"query\") → {:pages [...] :toc [...] :entities [...]}\n  (search-documents \"query\" {:in :pages})      ;; pages only\n  (search-documents \"query\" {:in :toc})        ;; TOC only\n  (search-documents \"query\" {:in :entities})   ;; entities only\n  Opts: :top-k :document-id :type" '([query] [query opts])]
                            ['fetch-content "Fetch full content by lookup ref.\n  [:page.node/id \"id\"]    → page text\n  [:document/id \"id\"]     → vector of ~4K char pages\n  [:document.toc/id \"id\"] → TOC entry description\n  [:entity/id \"id\"]       → {:entity {...} :relationships [...]}" '([lookup-ref])]]]
      (when (sci/eval-string* sci-ctx (str "(resolve '" sym ")"))
        (sci/eval-string* sci-ctx
          (str "(def ^{:doc " (pr-str doc)
            (when args (str " :arglists (quote " (pr-str args) ")"))
            "} " sym " " sym ")"))))
    {:sci-ctx sci-ctx
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
                     "(into {} (for [[s v] (ns-publics 'user)] [s {:val @v :doc (:doc (meta v)) :arglists (:arglists (meta v))}]))")
          entries (->> var-info
                    (remove (fn [[sym _]] (contains? initial-ns-keys sym)))
                    (sort-by key)
                    (mapv (fn [[sym {:keys [val doc arglists]}]]
                            (let [type-label (cond
                                               (nil? val) "nil"
                                               (fn? val) (if arglists (str "fn " arglists) "fn")
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
  "Update a binding in an existing SCI context.
   Ensures the symbol is a real SCI var before interning the value,
   since bindings from sci/init :namespaces are not SCI vars."
  [sci-ctx sym val]
  (let [ns-obj (sci/find-ns sci-ctx 'user)]
    ;; Promote to SCI var if needed (sci/init :namespaces creates plain values)
    (sci/eval-string* sci-ctx (str "(def " sym " nil)"))
    (sci/intern sci-ctx ns-obj sym val)))
