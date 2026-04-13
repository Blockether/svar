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
   [com.blockether.svar.internal.rlm.git :as rlm-git]
   [com.blockether.svar.internal.spec :as spec]
   [com.blockether.svar.internal.util :as util]
   [sci.addons.future :as sci-future]
   [sci.core :as sci]
   [taoensso.trove :as trove]))

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

(def ^:private FETCH_CONTENT_PAGE_SIZE
  "Characters per chunk when fetch-document-content returns a document as a vector of pages."
  4000)

(defn- chunk-text
  "Splits text into ~4000 char pages at paragraph boundaries."
  [text]
  (let [page-size FETCH_CONTENT_PAGE_SIZE]
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
  [db-info]
  (fn search-documents
    ([query] (search-documents query {}))
    ([query {:keys [in top-k document-id type] :or {top-k 10}}]
     (if db-info
       (let [do-pages #(let [results (db-search-page-nodes db-info query
                                       (cond-> {:top-k top-k}
                                         document-id (assoc :document-id document-id)
                                         type (assoc :type type)))]
                         ;; Track search hit access (weight 0.2) for returned pages
                         (let [page-ids (distinct (keep :page.node/page-id results))]
                           (doseq [page-id page-ids]
                             (record-page-access! db-info page-id 0.2))
                           ;; Record co-occurrence for search result pages
                           (db/record-cooccurrences! db-info page-ids))
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

(defn make-fetch-document-content-fn
  "Creates fetch-document-content — fetches content using Datalevin lookup ref syntax.

   Returns:
     [:page.node/id id]    → content string
     [:document/id id]     → vector of ~4000 char page strings (chunked)
     [:document.toc/id id] → TOC entry description string
     [:entity/id id]       → {:entity {...} :relationships [...]}

   The LLM stores results in variables:
     (def clause (fetch-document-content [:page.node/id \"abc\"]))
     (def doc (fetch-document-content [:document/id \"doc-1\"]))
     (count doc)      ;; number of pages
     (nth doc 5)      ;; page 5 content
     (def p (fetch-document-content [:entity/id \"e1\"]))
     (:entity p)      ;; entity map
     (:relationships p) ;; connected entities"
  [db-info]
  (fn fetch-document-content [lookup-ref]
    (when db-info
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
            (let [nodes (db/db-document-page-nodes-full db-info id)
                  ;; Track access for all pages in document (weight 1.0)
                  page-ids (distinct (keep :page.node/page-id nodes))]
              (doseq [pid page-ids]
                (record-page-access! db-info pid 1.0))
              ;; Record co-occurrence for all pages in document
              (db/record-cooccurrences! db-info page-ids)
              (when (seq nodes)
                (let [full-text (->> nodes
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

            (throw (ex-info (str "fetch-document-content unknown lookup attribute: " attr
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

(defn make-session-history-fn
  "Creates session-history for browsing prior query summaries in a conversation."
  [db-info conversation-ref]
  (fn session-history
    ([]
     (session-history nil))
    ([n]
     (if db-info
       (let [history (db/db-query-history db-info conversation-ref)
             selected (if (some? n) (take-last (max 0 (long n)) history) history)]
         (mapv #(select-keys % [:query-pos :query-id :text :answer-preview :status :iterations :key-vars :created-at])
           selected))
       []))))

(defn- resolve-query-ref
  [db-info conversation-ref query-selector]
  (let [history (db/db-query-history db-info conversation-ref)]
    (cond
      (nil? query-selector) (some-> history last :query-ref)
      (integer? query-selector) (some->> history (filter #(= (:query-pos %) query-selector)) first :query-ref)
      (and (vector? query-selector) (= :entity/id (first query-selector))) query-selector
      (uuid? query-selector) [:entity/id query-selector]
      :else nil)))

(defn make-session-code-fn
  "Creates session-code for browsing prior query code blocks."
  [db-info conversation-ref]
  (fn session-code
    ([query-selector]
     (if db-info
       (if-let [query-ref (resolve-query-ref db-info conversation-ref query-selector)]
         (db/db-query-code db-info query-ref)
         [])
       []))))

(defn make-session-results-fn
  "Creates session-results for browsing prior query results and restorable vars."
  [db-info conversation-ref]
  (fn session-results
    ([query-selector]
     (if db-info
       (if-let [query-ref (resolve-query-ref db-info conversation-ref query-selector)]
         (db/db-query-results db-info query-ref)
         [])
       []))))

(defn make-restore-var-fn
  "Creates restore-var for fetching the latest persisted data var from prior iterations."
  [db-info conversation-ref]
  (fn restore-var
    ([sym]
     (restore-var sym {}))
    ([sym opts]
     (if db-info
       (let [sym (if (symbol? sym) sym (symbol (str sym)))
             registry (db/db-latest-var-registry db-info conversation-ref
                        (select-keys (or opts {}) [:max-scan-queries]))]
         (if-let [{:keys [value]} (get registry sym)]
           value
           (throw (ex-info (str "No restorable var found for " sym)
                    {:type :rlm/restore-var-missing :symbol sym}))))
       (throw (ex-info "No DB available for restore-var" {:type :rlm/no-db}))))))

(defn make-restore-vars-fn
  "Creates restore-vars for batch fetching latest persisted data vars."
  [restore-var-fn]
  (fn restore-vars
    ([syms]
     (restore-vars syms {}))
    ([syms opts]
     (into {}
       (map (fn [sym]
              (try
                [sym (restore-var-fn sym opts)]
                (catch Exception e
                  [sym {:error {:type (:type (ex-data e))
                                :symbol sym
                                :message (ex-message e)}}]))))
       syms))))

;; =============================================================================
;; SCI Context Helpers
;; =============================================================================

(declare make-git-sci-bindings)

(defn sci-update-binding!
  "Update a binding in an existing SCI context.
   Ensures the symbol is a real SCI var before interning the value,
   since bindings from sci/init :namespaces are not SCI vars."
  [sci-ctx sym val]
  (let [ns-obj (sci/find-ns sci-ctx 'sandbox)]
    ;; Promote to SCI var if needed (sci/init :namespaces creates plain values)
    (sci/eval-string+ sci-ctx (str "(def " sym " nil)") {:ns ns-obj})
    (sci/intern sci-ctx ns-obj sym val)))

;; =============================================================================
;; SCI Context Creation
;; =============================================================================

(defn create-sci-context
  "Creates the SCI sandbox context with all available bindings.

   Params:
   `context-data` - The data context to analyze
   `sub-rlm-query-fn` - Function for simple LLM text queries
   `db-info` - Database info map (can be nil)
   `conversation-ref` - Active conversation lookup ref (can be nil)
   `custom-bindings` - Map of symbol->value for custom bindings (can be nil)"
  [context-data sub-rlm-query-fn db-info conversation-ref custom-bindings]
  (let [restore-var-fn (when (and db-info conversation-ref)
                         (make-restore-var-fn db-info conversation-ref))
        base-bindings {'context context-data
                       'sub-rlm-query sub-rlm-query-fn
                       'spec spec/spec
                       'field spec/field
                       ;; Date helper functions
                       'parse-date parse-date 'date-before? date-before? 'date-after? date-after?
                       'days-between days-between 'date-plus-days date-plus-days
                       'date-minus-days date-minus-days 'date-format date-format 'today-str today-str}
        db-bindings (when db-info
                      (cond->
                       {;; Unified document tools
                        'search-documents (make-search-documents-fn db-info)
                        'fetch-document-content (make-fetch-document-content-fn db-info)
                        'find-related (fn find-related
                                        ([entity-id] (when db-info (db/find-related db-info entity-id)))
                                        ([entity-id opts] (when db-info (db/find-related db-info entity-id opts))))
                        'search-batch (fn search-batch
                                        ([queries] (when db-info (db/db-search-batch db-info queries)))
                                        ([queries opts] (when db-info (db/db-search-batch db-info queries opts))))
                        'results->md (fn results->md [results] (db/results->markdown results))
                        'search-entities (fn search-entities
                                           ([query] (when db-info (db-search-entities db-info query)))
                                           ([query opts] (when db-info (db-search-entities db-info query opts))))
                        'get-entity (fn get-entity
                                      [entity-id] (when db-info (db-get-entity db-info entity-id)))
                        'list-relationships (fn list-relationships
                                              ([entity-id] (when db-info (db-list-relationships db-info entity-id)))
                                              ([entity-id opts] (when db-info (db-list-relationships db-info entity-id opts))))}
                        (and db-info conversation-ref)
                        (assoc 'session-history (make-session-history-fn db-info conversation-ref)
                          'session-code (make-session-code-fn db-info conversation-ref)
                          'session-results (make-session-results-fn db-info conversation-ref))
                        restore-var-fn
                        (assoc 'restore-var restore-var-fn
                          'restore-vars (make-restore-vars-fn restore-var-fn))))
        ;; Git SCI bindings are always present when a DB is configured. They
        ;; read :repo entities from DB on each call (no atom), open repos
        ;; lazily via `with-repo-for-*`, and error cleanly with
        ;; `:rlm/no-git-repos` when no `ingest-git!` call has landed yet.
        git-bindings (when db-info
                       (make-git-sci-bindings db-info))
        all-bindings (merge EXTRA_BINDINGS base-bindings db-bindings
                       git-bindings
                       (or custom-bindings {}))
        ;; Proper SCI namespaces via sci/copy-ns (preserves doc, arglists, meta)
        str-ns  (sci/create-ns 'clojure.string nil)
        set-ns  (sci/create-ns 'clojure.set nil)
        walk-ns (sci/create-ns 'clojure.walk nil)
        ;; zprint/lazytest: can't use copy-ns (macros), manual requiring-resolve
        zp-resolve (fn [sym] (deref (requiring-resolve (symbol "zprint.core" (str sym)))))
        lt-resolve (fn [sym] (deref (requiring-resolve (symbol "lazytest.core" (str sym)))))
        sandbox-ns (sci/create-ns 'sandbox nil)
        sci-ctx (sci/init (sci-future/install {:namespaces {'sandbox all-bindings
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
                                                       *in* *out* *err* *command-line-args*]}))]
    ;; Post-init: wrap restore-var/restore-vars to also bind in SCI namespace
    (when restore-var-fn
      (let [binding-restore-var (fn binding-restore-var
                                  ([sym] (binding-restore-var sym {}))
                                  ([sym opts]
                                   (let [val (restore-var-fn sym opts)]
                                     (sci-update-binding! sci-ctx sym val)
                                     val)))
            binding-restore-vars (fn binding-restore-vars
                                   ([syms]
                                    (binding-restore-vars syms {}))
                                   ([syms opts]
                                    (into {}
                                      (map (fn [sym]
                                             (try
                                               [sym (binding-restore-var sym opts)]
                                               (catch Exception e
                                                 [sym {:error {:type (:type (ex-data e))
                                                               :symbol sym
                                                               :message (ex-message e)}}]))))
                                      syms)))]
        (sci-update-binding! sci-ctx 'restore-var binding-restore-var)
        (sci-update-binding! sci-ctx 'restore-vars binding-restore-vars)))
    ;; Inject doc metadata so (doc fn-name) works in SCI
    (doseq [[sym doc args] [['sub-rlm-query "Ask a sub-LLM anything. Returns text or structured data." '([prompt] [prompt {:spec spec}])]
                            ['sub-rlm-query-batch "Parallel batch of LLM sub-calls. Returns vector of results." '([[prompt1 prompt2 ...]])]
                            ['request-more-iterations "Request n more iterations. Returns {:granted n :new-budget N}." '([n])]
                            ['spec "Create a structured output spec." '([& fields])]
                            ['field "Create a spec field." '([& kvs])]
                            ['context "The data context passed to query-env!." nil]
                            ['parse-date "Parse ISO date string to LocalDate." '([s])]
                            ['today-str "Today as ISO-8601 string." '([])]
                             ;; Document navigation — 2 unified tools
                            ['search-documents "Search across documents. No :in = search everywhere (pages+toc+entities).\n  (search-documents \"query\") → {:pages [...] :toc [...] :entities [...]}\n  (search-documents \"query\" {:in :pages})      ;; pages only\n  (search-documents \"query\" {:in :toc})        ;; TOC only\n  (search-documents \"query\" {:in :entities})   ;; entities only\n  Opts: :top-k :document-id :type" '([query] [query opts])]
                            ['fetch-document-content "Fetch full content by lookup ref.\n  [:page.node/id \"id\"]    → page text\n  [:document/id \"id\"]     → vector of ~4K char pages\n  [:document.toc/id \"id\"] → TOC entry description\n  [:entity/id \"id\"]       → {:entity {...} :relationships [...]}" '([lookup-ref])]
                            ['find-related "BFS graph traversal from an anchor entity.\n  (find-related entity-id)              ;; depth 2\n  (find-related entity-id {:depth 3})   ;; deeper\n  Returns related entities sorted by distance, with cross-document canonical linking." '([entity-id] [entity-id opts])]
                            ['search-entities "Search entities by name/description text.\n  (search-entities \"schema therapy\")\n  (search-entities \"auth\" {:top-k 20 :type :concept :document-id doc})\n  Returns ranked entity maps." '([query] [query opts])]
                            ['get-entity "Get a single entity by UUID.\n  (get-entity entity-uuid)  → entity map or nil" '([entity-id])]
                            ['list-relationships "List all relationships where entity is source or target.\n  (list-relationships entity-id)\n  (list-relationships entity-id {:type :depends-on})\n  Returns vector of relationship maps." '([entity-id] [entity-id opts])]
                            ['search-batch "Parallel multi-query search. Deduplicates, ranks by vitality.\n  (search-batch [\"schemas\" \"modes\" \"treatment\"])\n  (search-batch [\"q1\" \"q2\"] {:top-k 5 :limit 20})" '([queries] [queries opts])]
                            ['results->md "Convert search results to compact markdown for LLM.\n  (results->md (search-batch [...]))\n  (results->md (search-documents \"query\"))" '([results])]
                            ['session-history "List prior query summaries in the current conversation.\n  (session-history)\n  (session-history 5) ;; last 5 queries" '([] [n])]
                            ['session-code "Get prior query code blocks by query position or ref.\n  (session-code 0)\n  (session-code [:entity/id uuid])" '([query-selector])]
                            ['session-results "Get prior query execution results and restorable vars.\n  (session-results 0)" '([query-selector])]
                            ['restore-var "Restore a persisted data var from a prior iteration, binding it in the sandbox.\n  (restore-var 'anomalies)  ;; binds anomalies and returns its value\n  Opts: {:max-scan-queries N} limits lookup to recent queries." '([sym] [sym opts])]
                            ['restore-vars "Batch restore persisted data vars, binding each success in the sandbox.\n  (restore-vars ['a 'b])  ;; returns {a val-a, b {:error {...}}} on partial failure\n  Opts: {:max-scan-queries N} limits lookup to recent queries." '([syms] [syms opts])]
                            ;; Git — conditionally usable; always bound when DB exists,
                            ;; errors cleanly (:rlm/no-git-repos) until ingest-git! runs.
                            ['git-search-commits "Query ingested git commits. All filters optional, AND semantics. Cross-repo — pass :document-id to scope.\n  (git-search-commits {:category :bug :since \"2025-06-01\" :path \"src/\" :author-email \"a@x\" :ticket \"SVAR-42\" :limit 20})" '([] [opts])]
                            ['git-commit-history "Recent commits across all ingested repos.\n  (git-commit-history {:limit 20})" '([] [opts])]
                            ['git-commits-by-ticket "Commits that reference a ticket.\n  (git-commits-by-ticket \"SVAR-42\")" '([ticket-ref])]
                            ['git-commit-parents "Parent SHAs of a commit (DB-backed, reads :commit/parents).\n  (git-commit-parents \"abc123\")" '([sha])]
                            ['git-file-history "Commits touching a file, most-recent first. JGit, follows renames.\n  Single repo: (git-file-history \"src/foo.clj\")\n  Multi-repo:  (git-file-history \"/abs/path/svar/src/foo.clj\" {:n 10})" '([path] [path opts])]
                            ['git-blame "Per-line blame attribution, inclusive line range (1-indexed). Follows renames.\n  Single repo: (git-blame \"src/foo.clj\" 42 58)\n  Multi-repo:  (git-blame \"/abs/path/svar/src/foo.clj\" 42 58)\n  Returns vec of {:line :sha :short :author :email :date :content}." '([path from to])]
                            ['git-commit-diff "Unified patch for a commit. SHA auto-dispatches to owning repo. Refs like HEAD are ambiguous multi-repo.\n  (git-commit-diff \"abc123\")" '([sha])]]]
      (when (:val (sci/eval-string+ sci-ctx (str "(resolve '" sym ")") {:ns sandbox-ns}))
        (sci/eval-string+ sci-ctx
          (str "(def ^{:doc " (pr-str doc)
            (when args (str " :arglists (quote " (pr-str args) ")"))
            "} " sym " " sym ")")
          {:ns sandbox-ns})))
    ;; NOTE: We do NOT call (sci/alter-var-root sci/ns ...) here because it's GLOBAL,
    ;; not per-context. Instead, callers use (sci/eval-string+ ctx code {:ns sandbox-ns})
    ;; for per-call namespace scoping. sandbox-ns is returned for this purpose.
    {:sci-ctx sci-ctx
     :sandbox-ns sandbox-ns
     :initial-ns-keys (set (keys (:val (sci/eval-string+ sci-ctx "(ns-publics 'sandbox)" {:ns sandbox-ns}))))}))

;; =============================================================================
;; Var Index
;; =============================================================================

(def ^:private ^:const MAX_VAR_INDEX_ROWS 40)
(def ^:private ^:const MAX_VAR_INDEX_COUNT 1000)
(def ^:private ^:const MAX_VAR_INDEX_PREVIEW 150)

(defn build-var-index
  "Builds a formatted var index table from user-def'd vars in the SCI context.
   Filters out initial bindings (tools, helpers) using initial-ns-keys.
   Returns nil if no user vars exist.

   Row format: `name | type | size | doc — preview`
   - doc    : from the docstring passed to `(def sym \"doc\" val)`
   - preview: `pr-str` of the value, truncated to MAX_VAR_INDEX_PREVIEW chars.
              The preview is the quick-glance snapshot; for the full value
              the agent runs the var in :code and reads <execution_results>."
  ([sci-ctx initial-ns-keys]
   (build-var-index sci-ctx initial-ns-keys nil))
  ([sci-ctx initial-ns-keys sandbox]
   (try
     (let [sandbox-map (or sandbox (get-in @(:env sci-ctx) [:namespaces 'sandbox]))
           var-info (into {}
                      (for [[s v] sandbox-map
                            :when (symbol? s)]
                        [s {:val (if (instance? clojure.lang.IDeref v) @v v)
                            :doc (:doc (meta v))
                            :arglists (:arglists (meta v))}]))
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
                                          (or (map? val) (vector? val) (set? val))
                                          (str (count val) " items")
                                          (sequential? val)
                                          (let [n (bounded-count MAX_VAR_INDEX_COUNT val)]
                                            (if (= n MAX_VAR_INDEX_COUNT)
                                              (str MAX_VAR_INDEX_COUNT "+ items")
                                              (str n " items")))
                                          :else "\u2014")
                                   preview (if (fn? val)
                                             "\u2014"
                                             (str-truncate (pr-str val) MAX_VAR_INDEX_PREVIEW))]
                               {:name (str sym) :type type-label :size size
                                :doc (if doc (str-truncate doc 80) "\u2014")
                                :preview preview}))))]
       (when (seq entries)
         (let [visible (vec (take MAX_VAR_INDEX_ROWS entries))
               omitted (- (count entries) (count visible))
               max-name (max 4 (apply max (map #(count (:name %)) visible)))
               max-type (max 4 (apply max (map #(count (:type %)) visible)))
               max-size (max 4 (apply max (map #(count (:size %)) visible)))
               pad (fn [s n] (str s (apply str (repeat (max 0 (- n (count s))) \space))))
               header (str "  " (pad "name" max-name) " | " (pad "type" max-type) " | " (pad "size" max-size) " | doc / preview")
               sep (str "  " (apply str (repeat max-name \-)) "-+-" (apply str (repeat max-type \-)) "-+-" (apply str (repeat max-size \-)) "-+------------------")
               rows (mapcat (fn [{:keys [name type size doc preview]}]
                              [(str "  " (pad name max-name) " | " (pad type max-type) " | " (pad size max-size) " | " doc)
                               (str "  " (pad "" max-name) " | " (pad "" max-type) " | " (pad "" max-size) " | preview: " preview)])
                      visible)
               footer (when (pos? omitted)
                        (str "  ... " omitted " more vars omitted"))]
           (str/join "\n" (concat [header sep] rows (when footer [footer]))))))
     (catch Exception _ nil))))

;; =============================================================================
;; Git Tool Binding — lazy-open per call, no atoms. Bindings live in the
;; standard SCI sandbox (see make-git-sci-bindings below); :repo entities
;; are read from Datalevin on every call. System prompt renders
;; per-attached-repo GIT REPO blocks via core/format-git-context.
;; =============================================================================

(defn- strip-worktree-prefix
  "If `abs-path` starts with `worktree-abs`, return the remainder (without
   leading separator). Otherwise return `abs-path` unchanged."
  [^String abs-path ^String worktree-abs]
  (if (str/starts-with? abs-path worktree-abs)
    (let [tail (subs abs-path (count worktree-abs))]
      (cond-> tail
        (and (seq tail) (= (first tail) java.io.File/separatorChar))
        (subs 1)))
    abs-path))

(defn- with-repo-for-path
  "Resolve the attached `:repo` entity that owns `path` (read from DB), open
   its Repository via rlm-git/open-repo, run `(f repo relative-path)`, close
   the Repository in finally.

   Dispatch rules:
   * 0 repos → :rlm/no-git-repos
   * 1 repo  → use it; relative or absolute path both accepted
   * N repos → absolute path required (:rlm/no-repo-for-path :reason
     :relative-path otherwise); picks the repo whose `:repo/path` prefix
     matches the absolute path."
  [db-info ^String path f]
  (let [repos (when db-info (db/db-list-repos db-info))]
    (cond
      (empty? repos)
      (throw (ex-info "No git repositories attached. Call rlm/ingest-git! first."
               {:type :rlm/no-git-repos}))

      (= 1 (count repos))
      (let [repo-meta (first repos)
            ^org.eclipse.jgit.lib.Repository repo (rlm-git/open-repo (:repo/path repo-meta))]
        (when-not repo
          (throw (ex-info (str "Attached repo " (pr-str (:repo/name repo-meta))
                            " no longer opens at " (pr-str (:repo/path repo-meta)))
                   {:type :rlm/repo-open-failed
                    :repo-name (:repo/name repo-meta)
                    :repo-path (:repo/path repo-meta)})))
        (try
          (let [wt (.getAbsolutePath (.getWorkTree repo))
                rel (strip-worktree-prefix (str path) wt)]
            (f repo rel))
          (finally (.close repo))))

      :else
      (let [pf (java.io.File. path)]
        (when-not (.isAbsolute pf)
          (throw (ex-info (str "Multi-repo mode requires an absolute path. Got "
                            (pr-str path) ". Attached repos: "
                            (pr-str (mapv :repo/name repos)))
                   {:type :rlm/no-repo-for-path
                    :path path
                    :reason :relative-path
                    :attached (mapv :repo/name repos)})))
        (let [abs (.getAbsolutePath pf)
              hit (->> repos
                    (filter (fn [rm]
                              (let [rp (:repo/path rm)]
                                (or (= abs rp)
                                  (str/starts-with? abs (str rp java.io.File/separator))))))
                    first)]
          (when-not hit
            (throw (ex-info (str "No attached git repo owns path " (pr-str path)
                              ". Pass an absolute path inside one of: "
                              (pr-str (mapv :repo/name repos)))
                     {:type :rlm/no-repo-for-path
                      :path path
                      :attached (mapv :repo/name repos)})))
          (let [^org.eclipse.jgit.lib.Repository repo (rlm-git/open-repo (:repo/path hit))]
            (try
              (let [wt (.getAbsolutePath (.getWorkTree repo))
                    rel (strip-worktree-prefix abs wt)]
                (f repo rel))
              (finally (.close repo)))))))))

(defn- repo-has-object?
  "True iff `repo` contains a git object for `sha`. Uses ObjectReader.has so
   we verify actual object presence — NOT just that `.resolve` parses the
   SHA as syntactically valid (which it does for any 40-char hex even when
   the object doesn't exist in this repo)."
  [^org.eclipse.jgit.lib.Repository repo ^String sha]
  (try
    (when-let [oid (.resolve repo sha)]
      (with-open [reader (.newObjectReader repo)]
        (.has reader oid)))
    (catch Exception _ false)))

(defn- with-repo-for-sha
  "Resolve the attached `:repo` entity whose object database contains `sha`
   (read from DB, open each candidate, presence-check, keep first match open
   for the callback, close everything else immediately).

   Dispatch rules:
   * 0 repos → :rlm/no-git-repos
   * 1 repo  → use it
   * N repos → iterate; first presence-match wins. Ref names like \"HEAD\"
     are ambiguous in multi-repo and throw :rlm/ambiguous-ref."
  [db-info ^String sha f]
  (let [repos (when db-info (db/db-list-repos db-info))]
    (cond
      (empty? repos)
      (throw (ex-info "No git repositories attached. Call rlm/ingest-git! first."
               {:type :rlm/no-git-repos}))

      (= 1 (count repos))
      (let [repo-meta (first repos)
            ^org.eclipse.jgit.lib.Repository repo (rlm-git/open-repo (:repo/path repo-meta))]
        (when-not repo
          (throw (ex-info (str "Attached repo " (pr-str (:repo/name repo-meta))
                            " no longer opens at " (pr-str (:repo/path repo-meta)))
                   {:type :rlm/repo-open-failed})))
        (try (f repo) (finally (.close repo))))

      :else
      (let [ref-like? (contains? #{"HEAD" "FETCH_HEAD" "ORIG_HEAD" "main" "master"} sha)]
        (when ref-like?
          (throw (ex-info (str "Ref " (pr-str sha)
                            " is ambiguous across multiple attached repos "
                            (pr-str (mapv :repo/name repos))
                            ". Pass an actual SHA instead.")
                   {:type :rlm/ambiguous-ref
                    :ref sha
                    :attached (mapv :repo/name repos)})))
        (loop [remaining repos]
          (if-let [rm (first remaining)]
            (let [^org.eclipse.jgit.lib.Repository repo (rlm-git/open-repo (:repo/path rm))]
              (if (and repo (repo-has-object? repo sha))
                (try (f repo) (finally (.close repo)))
                (do (when repo (try (.close repo) (catch Exception _ nil)))
                    (recur (rest remaining)))))
            (throw (ex-info (str "SHA " (pr-str sha) " not found in any attached repo. Attached: "
                              (pr-str (mapv :repo/name repos)))
                     {:type :rlm/no-repo-for-sha
                      :sha sha
                      :attached (mapv :repo/name repos)}))))))))

(defn make-git-sci-bindings
  "Return the map of git-* SCI symbol → fn for a given `db-info`.
   Bindings are always present in the sandbox once the env has a DB; they
   error cleanly (`:rlm/no-git-repos`) when no `:repo` entity has been
   ingested yet. All JGit-backed tools open + close Repository instances
   per call via `with-repo-for-*`."
  [db-info]
  {'git-search-commits
   (fn git-search-commits
     ([] (git-search-commits {}))
     ([opts]
      (when db-info
        (db/db-search-commits db-info opts))))

   'git-commit-history
   (fn git-commit-history
     ([] (git-commit-history {}))
     ([opts]
      (when db-info
        (db/db-search-commits db-info opts))))

   'git-commits-by-ticket
   (fn git-commits-by-ticket
     [ticket-ref]
     (when db-info
       (db/db-search-commits db-info {:ticket ticket-ref})))

   'git-commit-parents
   (fn git-commit-parents
     [sha]
     (when db-info
       (let [commit (db/db-commit-by-sha db-info sha)]
         (vec (:commit/parents commit)))))

   'git-file-history
   (fn git-file-history
     ([path] (git-file-history path {}))
     ([path opts]
      (with-repo-for-path db-info path
        (fn [repo rel] (rlm-git/file-history repo rel opts)))))

   'git-blame
   (fn git-blame
     [path from to]
     (with-repo-for-path db-info path
       (fn [repo rel] (rlm-git/blame repo rel from to))))

   'git-commit-diff
   (fn git-commit-diff
     [sha]
     (with-repo-for-sha db-info sha
       (fn [repo] (rlm-git/commit-diff repo sha))))})

;; =============================================================================
;; Hook System v3 — per-tool + global hooks with policy/observation split
;; =============================================================================
;;
;; Per-tool hooks are attached to a registered tool-def and form three chains:
;;   :before — can transform args, skip execution, or fail fast with an error
;;   :wrap   — ring-style middleware around the fn (vec-LAST = outermost)
;;   :after  — can transform result/error, observe, or recover from failure
;;
;; Global hooks live in a `:hooks` map on `query-env!` opts and are PURE
;; OBSERVERS (return values ignored). They fire on lifecycle events the LLM
;; loop emits: :on-iteration-start / :on-iteration / :on-cancel / :on-error /
;; :on-final / :on-chunk / :on-tool-invoked / :on-tool-completed.
;;
;; Policy (deny / transform / recover) goes in per-tool hooks. Globals are for
;; logging, metrics, UI streaming — fire-and-forget, exceptions swallowed.

(def MAX_HOOK_DEPTH
  "Ceiling on :invoke recursion depth per top-level tool dispatch. Each
   top-level (LLM-initiated) call starts fresh at depth 0; make-invoke-fn
   passes (inc depth) to nested calls. No dynamic vars — depth is an
   immutable int threaded through execute-tool's ctx arg."
  8)

(def DEFAULT_QUERY_CTX
  "Fallback query context used by register-env-fn!'s immediate SCI flash
   when no query-env! call is active. Per-tool hook chains still fire
   (they live in the registry), but global observers are nil — they only
   trigger when query-env! re-flashes tool bindings with a per-query ctx."
  {:hooks nil
   :iteration-atom nil
   :depth 0
   :parent-dispatch-id nil})

(defn- status-id
  [status]
  (when status
    (keyword "rlm.status" (name status))))

(defn- ensure-error-map
  [err default-id]
  (cond
    (nil? err) nil
    (map? err) (cond-> err
                 (not (:error-id err)) (assoc :error-id default-id))
    :else {:type :rlm/unknown-tool-error
           :error-id default-id
           :message (str err)}))

(declare execute-tool)

(defn- gen-anon-id
  "Generate a unique anonymous hook id for a normalize-hooks call. Uses
   `gensym` so anon ids never collide with ids from a prior registration."
  [stage]
  (gensym (str "anon/" (name stage) "-")))

(defn- normalize-hook-entry
  "Coerce one hook entry (fn OR {:id :fn} map) into a canonical {:id :fn} map.
   Anonymous fns get an auto-generated id via gen-anon-id."
  [stage entry]
  (cond
    (fn? entry)
    {:id (gen-anon-id stage) :fn entry}

    (and (map? entry) (fn? (:fn entry)))
    (update entry :id #(or % (gen-anon-id stage)))

    :else
    (throw (ex-info (str "Invalid hook entry for :" (name stage) " — "
                      "must be a fn or {:id :fn} map")
             {:type :rlm/invalid-hook-entry :stage stage :entry entry}))))

(defn normalize-hooks
  "Coerce a hook spec for one stage into a canonical vec of {:id :fn} maps.
   Accepts: nil / single fn / single map / vec of fns and/or maps.

   Examples:
     (normalize-hooks :before nil)                         ;=> []
     (normalize-hooks :before my-fn)                       ;=> [{:id :anon/...  :fn my-fn}]
     (normalize-hooks :before {:id :x :fn my-fn})          ;=> [{:id :x :fn my-fn}]
     (normalize-hooks :before [{:id :x :fn f1} f2])        ;=> [{:id :x :fn f1} {:id :anon/... :fn f2}]"
  [stage hooks]
  (cond
    (nil? hooks) []
    (fn? hooks) [(normalize-hook-entry stage hooks)]
    (map? hooks) [(normalize-hook-entry stage hooks)]
    (sequential? hooks) (mapv #(normalize-hook-entry stage %) hooks)
    :else
    (throw (ex-info (str "Invalid hooks shape for :" (name stage))
             {:type :rlm/invalid-hooks-shape :stage stage :hooks hooks}))))

(defn merge-hook-chain
  "Merge incoming hook entries into an existing chain by :id.
   * Same :id → replace in place, preserving the old position.
   * New :id → appended to the end of the chain.
   * Old :id not in incoming → preserved untouched.

   Supports layered registration: call register-env-fn! multiple times to
   add hooks incrementally without explicit unregister steps."
  [existing incoming]
  (let [incoming-by-id (into {} (map (juxt :id identity)) incoming)
        ;; Replace in place
        merged (mapv (fn [entry]
                       (if-let [updated (get incoming-by-id (:id entry))]
                         updated
                         entry))
                 existing)
        existing-ids (set (map :id existing))
        ;; Append new entries (not in existing)
        appended (vec (remove #(contains? existing-ids (:id %)) incoming))]
    (vec (concat merged appended))))

(defn- merge-tool-def-hooks
  "Merge a new tool-def's hook chains into an existing tool-def's hook chains.
   :fn, :doc, :params, :returns are replaced wholesale. Hook chains are
   merged by :id via merge-hook-chain."
  [existing new-def]
  (let [new-before (normalize-hooks :before (:before new-def))
        new-after  (normalize-hooks :after  (:after new-def))
        new-wrap   (normalize-hooks :wrap   (:wrap new-def))
        old-hooks  (:hooks existing {})]
    (-> new-def
      (assoc :hooks {:before (merge-hook-chain (:before old-hooks []) new-before)
                     :after  (merge-hook-chain (:after old-hooks [])  new-after)
                     :wrap   (merge-hook-chain (:wrap old-hooks [])   new-wrap)})
      (dissoc :before :after :wrap))))

(defn- run-before-chain
  "Run :before hooks in registration order. Each hook receives the current
   invocation map (with possibly-updated :args from prior hooks). Return
   value conventions:
     nil / {}        → proceed unchanged
     {:args v}       → transform args, continue chain
     {:skip v}       → short-circuit, :after runs with {:result v :skipped? true}
     {:error e}      → short-circuit, :after runs with {:error e :skipped? true}
     (thrown)        → treated as {:error :hook-exception}
     anything else   → logged warn, ignored

   Precedence when a return map has multiple keys: :error > :skip > :args.

   Returns {:args :short-circuit :skipped? :hook-errors} where :short-circuit
   is nil (proceed) or a map containing :result and/or :error to use
   instead of calling the tool fn."
  [hooks invocation]
  (reduce
    (fn [acc {:keys [id fn]}]
      (let [current-inv (assoc invocation :args (:args acc))]
        (try
          (let [ret (fn current-inv)]
            (cond
              (or (nil? ret) (= {} ret))
              acc

              (and (map? ret) (contains? ret :error))
              (reduced (assoc acc
                         :short-circuit {:result nil
                                         :error (ensure-error-map (:error ret)
                                                  :rlm.error/hook-before-returned-error)}
                         :skipped? true))

              (and (map? ret) (contains? ret :skip))
              (reduced (assoc acc
                         :short-circuit {:result (:skip ret) :error nil}
                         :skipped? true))

              (and (map? ret) (contains? ret :args))
              (assoc acc :args (:args ret))

              :else
              (do (trove/log! {:level :warn
                               :data {:stage :before :id id :ret ret}
                               :msg "Before hook returned unknown shape; ignoring"})
                  acc)))
          (catch Throwable t
            (reduced (assoc acc
                       :short-circuit {:result nil
                                       :error {:type :hook-exception
                                               :error-id :rlm.error/hook-exception
                                               :message (ex-message t)
                                               :stage :before
                                               :id id}}
                       :skipped? true))))))
    {:args (:args invocation) :short-circuit nil :skipped? false}
    hooks))

(defn- run-after-chain
  "Run :after hooks in registration order on the outcome. Each hook can
   transform result and/or error, or observe. Return value conventions:
     nil / {}                       → pass through
     {:result v}                    → replace result
     {:error e}                     → replace error
     {:result v :error nil}         → recover from failure (explicit)
     {:result nil :error e}         → flip success → failure (explicit)
     (thrown)                       → added to :hook-errors, original flows
     anything else                  → logged warn, ignored

   NB: unlike :before, :after runs in registration order (NOT reversed).
   :before/:after are independent sequential chains, not paired setup/cleanup.
   If you need stack semantics, use :wrap."
  [hooks initial-outcome]
  (reduce
    (fn [outcome {:keys [id fn]}]
      (try
        (let [ret (fn outcome)]
          (cond
            (or (nil? ret) (= {} ret))
            outcome

            (and (map? ret) (or (contains? ret :result) (contains? ret :error)))
            (cond-> outcome
              (contains? ret :result) (assoc :result (:result ret))
              (contains? ret :error)  (assoc :error
                                        (ensure-error-map (:error ret)
                                          :rlm.error/hook-after-returned-error)))

            :else
            (do (trove/log! {:level :warn
                             :data {:stage :after :id id :ret ret}
                             :msg "After hook returned unknown shape; ignoring"})
                outcome)))
        (catch Throwable t
          (update outcome :hook-errors (fnil conj [])
            {:stage :after :id id :error {:type :hook-exception
                                          :error-id :rlm.error/hook-exception
                                          :message (ex-message t)}}))))
    initial-outcome
    hooks))

(defn- compose-wrap-chain
  "Compose :wrap middleware around `base-handler` using ring semantics:
   the LAST entry in the `wraps` vec is the OUTERMOST. Matches
   (-> handler inner-wrap outer-wrap) — outer sees the invocation first,
   inner sees it last, and outer sees the outcome last.

   Each wrap entry's :fn is a middleware of shape:
     (fn [handler] (fn [invocation] ... outcome ...))"
  [wraps base-handler]
  (reduce
    (fn [handler {:keys [fn]}]
      (fn handler))
    base-handler
    wraps))

(defn- make-invoke-fn
  "Build an `(fn [sym args])` closure used as `:invoke` on the invocation
   map. Calls another registered tool through its own per-tool hook chain,
   BYPASSING global observer hooks (the child query-ctx has :hooks nil
   so globals don't double-count internal dispatches).

   Recursion is depth-tracked via an explicit `:depth` int on the
   query-ctx — each invoke creates a CHILD ctx with (inc depth). When
   depth reaches MAX_HOOK_DEPTH, `:invoke` throws :rlm/hook-recursion-limit."
  [env tool-registry-atom parent-dispatch-id parent-query-ctx]
  (fn invoke [sym args]
    (let [next-depth (inc (:depth parent-query-ctx 0))]
      (when (>= next-depth MAX_HOOK_DEPTH)
        (throw (ex-info (str "Hook :invoke recursion limit reached ("
                          MAX_HOOK_DEPTH ")")
                 {:type :rlm/hook-recursion-limit
                  :depth next-depth
                  :sym sym}))))
    (let [tool-def (get @tool-registry-atom sym)]
      (when-not tool-def
        (throw (ex-info (str "Unknown tool for :invoke — " sym " not registered")
                 {:type :rlm/unknown-invoke-tool :sym sym})))
      ;; Child ctx: depth+1, parent-id = this caller's dispatch-id,
      ;; :hooks nil so internal invoke calls don't fire observer globals.
      (let [child-ctx (-> parent-query-ctx
                        (assoc :hooks nil
                          :depth (inc (:depth parent-query-ctx 0))
                          :parent-dispatch-id parent-dispatch-id))
            outcome (execute-tool env sym (:fn tool-def) (vec args)
                      {:tool-hooks (:hooks tool-def)
                       :tool-registry-atom tool-registry-atom
                       :query-ctx child-ctx})]
        (if (:error outcome)
          (throw (ex-info (get-in outcome [:error :message] "invoke failed")
                   {:type :rlm/invoke-error :outcome outcome}))
          (:result outcome))))))

(defn execute-tool
  "Core tool invocation pipeline. Runs :before chain → :wrap middleware →
   fn → :after chain, firing global :on-tool-invoked / :on-tool-completed
   observers around the whole thing.

   All per-query state is passed explicitly via `:query-ctx`:
     {:hooks             global hooks map or nil
      :iteration-atom    atom storing current iteration index or nil
      :depth             int — current :invoke chain depth
      :parent-dispatch-id string or nil — caller's dispatch-id}

   Returns the final outcome map:
     {:sym :args :iteration :env :dispatch-id :parent-dispatch-id :invoke :cancel!
      :result :error :duration-ms :skipped? :hook-errors}"
  [env sym user-fn args {:keys [tool-hooks tool-registry-atom query-ctx]}]
  (let [{:keys [hooks iteration-atom parent-dispatch-id]} (or query-ctx DEFAULT_QUERY_CTX)
        dispatch-id (str (java.util.UUID/randomUUID))
        cancel-atom (:cancel-atom env)
        before-hooks (or (:before tool-hooks) [])
        after-hooks  (or (:after tool-hooks)  [])
        wrap-hooks   (or (:wrap tool-hooks)   [])
        invoke-fn (make-invoke-fn env tool-registry-atom dispatch-id
                    (or query-ctx DEFAULT_QUERY_CTX))
        cancel-fn (fn [] (when cancel-atom (reset! cancel-atom true)))
        current-iteration (if iteration-atom @iteration-atom 0)
        invocation {:sym sym
                    :args args
                    :iteration current-iteration
                    :env env
                    :dispatch-id dispatch-id
                    :parent-dispatch-id parent-dispatch-id
                    :invoke invoke-fn
                    :cancel! cancel-fn}]
    ;; Global :on-tool-invoked — pure observer, return ignored
    (when-let [g (:on-tool-invoked hooks)]
      (try (g invocation)
           (catch Throwable t
             (trove/log! {:level :warn
                          :data {:error (ex-message t)}
                          :msg ":on-tool-invoked observer threw; ignoring"}))))
    (let [{transformed-args :args short-circuit :short-circuit skipped? :skipped?}
          (run-before-chain before-hooks invocation)
          start-ns (System/nanoTime)
          ;; Execute (either short-circuited or through wraps + fn)
          {:keys [result error]}
          (cond
            short-circuit
            short-circuit

            :else
            (let [base-handler (fn [inv]
                                 (try
                                   {:result (apply user-fn (:args inv)) :error nil}
                                   (catch Throwable t
                                     {:result nil
                                      :error {:type :tool-exception
                                              :error-id :rlm.error/tool-exception
                                              :message (ex-message t)
                                              :data (ex-data t)}})))
                  composed (compose-wrap-chain wrap-hooks base-handler)]
              (try
                (composed (assoc invocation :args transformed-args))
                (catch Throwable t
                  {:result nil
                   :error {:type :wrap-exception
                           :error-id :rlm.error/wrap-exception
                           :message (ex-message t)}}))))
          duration-ms (double (/ (- (System/nanoTime) start-ns) 1e6))
          initial-outcome (merge invocation
                            {:args transformed-args
                             :result result
                             :error (ensure-error-map error :rlm.error/tool-error)
                             :duration-ms duration-ms
                             :skipped? (boolean skipped?)
                             :hook-errors []})
          final-outcome (let [post-after (run-after-chain after-hooks initial-outcome)
                              status (cond
                                       (:error post-after) :error
                                       (:skipped? post-after) :skipped
                                       :else :success)]
                          (assoc post-after :status status :status-id (status-id status)))]
      ;; Global :on-tool-completed — pure observer, return ignored
      (when-let [g (:on-tool-completed hooks)]
        (try (g final-outcome)
             (catch Throwable t
               (trove/log! {:level :warn
                            :data {:error (ex-message t)}
                            :msg ":on-tool-completed observer threw; ignoring"}))))
      final-outcome)))

(defn wrap-tool-for-sci
  "Build the fn that gets bound into the SCI sandbox for a registered tool.
   Closes over a `query-ctx` — either DEFAULT_QUERY_CTX (used by
   register-env-fn!'s immediate flash when no query is active) or a
   per-query ctx built by query-env! with the current :hooks +
   iteration-atom. Each call starts a fresh chain with depth 0.

   Returns :result on success or throws an ex-info on :error (SCI surfaces
   throws as execution errors, which the iteration loop feeds back to the
   LLM in <execution_results>)."
  ([env sym user-fn tool-registry-atom]
   (wrap-tool-for-sci env sym user-fn tool-registry-atom DEFAULT_QUERY_CTX))
  ([env sym user-fn tool-registry-atom query-ctx]
   (fn wrapped-tool [& args]
     (let [outcome (execute-tool env sym user-fn (vec args)
                     {:tool-hooks (get-in @tool-registry-atom [sym :hooks])
                      :tool-registry-atom tool-registry-atom
                      :query-ctx (assoc query-ctx
                                   :depth 0
                                   :parent-dispatch-id nil)})]
       (if-let [err (:error outcome)]
         (throw (ex-info (or (:message err) "tool error") err))
         (:result outcome))))))

(defn list-tool-hooks
  "Return a map describing the hook chains registered for `sym`.
   Shape: {:before [{:id :position :fn-name}] :after [...] :wrap [...]}
   Returns nil if `sym` isn't registered."
  [hook-registry-atom sym]
  (when-let [tool-def (get @hook-registry-atom sym)]
    (let [describe (fn [entries]
                     (vec (map-indexed
                            (fn [i {:keys [id fn]}]
                              {:id id
                               :position i
                               :fn-name (or (some-> fn class .getSimpleName) "fn")})
                            entries)))
          hooks (:hooks tool-def {})]
      {:before (describe (:before hooks []))
       :after  (describe (:after hooks []))
       :wrap   (describe (:wrap hooks []))})))

(defn list-registered-tools
  "Return a vec of {:sym :hook-counts} maps summarizing every tool
   registered in `hook-registry-atom`. Hook counts are per stage."
  [hook-registry-atom]
  (vec
    (for [[sym tool-def] @hook-registry-atom]
      {:sym sym
       :hook-counts {:before (count (get-in tool-def [:hooks :before]))
                     :after  (count (get-in tool-def [:hooks :after]))
                     :wrap   (count (get-in tool-def [:hooks :wrap]))}})))

(defn unregister-hook!
  "Remove a hook entry by :id from a given stage (:before / :after / :wrap).
   Returns true if a matching entry was removed, false otherwise."
  [hook-registry-atom sym stage id]
  (let [removed? (atom false)]
    (swap! hook-registry-atom
      (fn [registry]
        (if-let [tool-def (get registry sym)]
          (let [old-entries (get-in tool-def [:hooks stage] [])
                new-entries (vec (remove #(= id (:id %)) old-entries))]
            (when (not= (count old-entries) (count new-entries))
              (reset! removed? true))
            (assoc-in registry [sym :hooks stage] new-entries))
          registry)))
    @removed?))

(defn register-tool-def!
  "Register or layer a tool-def in `hook-registry-atom`.
   Merges hook chains by :id per merge-tool-def-hooks. Returns the
   canonicalized tool-def (with :hooks key)."
  [hook-registry-atom sym tool-def]
  (let [canonical (merge-tool-def-hooks
                    (get @hook-registry-atom sym {})
                    tool-def)]
    (swap! hook-registry-atom assoc sym canonical)
    canonical))
