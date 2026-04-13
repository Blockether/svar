(ns com.blockether.svar.internal.rlm.db
  "Thin facade over rlm.sqlite — Datalevin is gone. Preserves the legacy
   public API (fn names + namespaced return shapes) so callers don't change.

   db-info map layout:
     {:datasource javax.sql.DataSource
      :path        \"/path/to.db\"  ;; nil for :external
      :owned?      bool
      :mode        :temp|:persistent|:external}

   Connection open/close is delegated to rlm.sqlite/open-store + close-store.
   All data operations route through rlm.sqlite."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [com.blockether.svar.internal.rlm.sqlite :as sq]
   [edamame.core :as edamame]
   [taoensso.trove :as trove]))

;; -----------------------------------------------------------------------------
;; Small utilities (kept here for back-compat imports)
;; -----------------------------------------------------------------------------

(defn str-truncate [s n] (when s (if (> (count s) n) (subs s 0 n) s)))
(defn str-lower [s] (when s (str/lower-case s)))
(defn str-includes? [s substr] (when s (str/includes? s substr)))

(defn- read-edn-safe [s fallback]
  (if (or (nil? s) (= "" s))
    fallback
    (try (edn/read-string s)
         (catch Exception _ fallback))))

(defn- entity-order-key [entity]
  [(:entity/created-at entity) (:entity/id entity)])

;; -----------------------------------------------------------------------------
;; Connection lifecycle
;; -----------------------------------------------------------------------------

(def create-rlm-conn
  "Open a SQLite RLM store. Drop-in replacement for the legacy Datalevin
   constructor. See rlm.sqlite/open-store for db-spec forms."
  sq/open-store)

(def dispose-rlm-conn!
  "Close an RLM store. Idempotent. See rlm.sqlite/close-store."
  sq/close-store)

;; -----------------------------------------------------------------------------
;; Corpus metadata
;; -----------------------------------------------------------------------------

(def get-corpus-revision    sq/get-corpus-revision)
(def bump-corpus-revision!  sq/bump-corpus-revision!)

;; -----------------------------------------------------------------------------
;; Entity / Conversation / Query / Iteration
;; -----------------------------------------------------------------------------

(def store-entity!     sq/store-entity!)
(def update-entity!    sq/update-entity!)

(def store-conversation!              sq/store-conversation!)
(def db-get-conversation              sq/db-get-conversation)
(def db-find-latest-conversation-ref  sq/db-find-latest-conversation-ref)
(def db-find-named-conversation-ref   sq/db-find-named-conversation-ref)
(def db-resolve-conversation-ref      sq/db-resolve-conversation-ref)

(def store-query!   sq/store-query!)
(def update-query!  sq/update-query!)

(def store-iteration!        sq/store-iteration!)
(def db-list-iteration-vars  sq/db-list-iteration-vars)

(def db-list-conversation-queries  sq/db-list-conversation-queries)
(def db-list-query-iterations      sq/db-list-query-iterations)
(def db-list-final-results         sq/db-list-final-results)
(def db-list-queries               sq/db-list-queries)
(def db-document-page-nodes-full   sq/db-document-page-nodes-full)

;; -----------------------------------------------------------------------------
;; Derived views over conversations / queries / iterations
;; -----------------------------------------------------------------------------

(def ^:private DEF_LIKE_OPS '#{def defn defn- defonce defmulti defmacro})

(defn- parse-forms-safe [code]
  (try (edamame/parse-string-all (or code "") {:all true})
       (catch Exception _ [])))

(defn- form->defined-symbol [form]
  (when (seq? form)
    (let [[op sym & _] form]
      (when (and (contains? DEF_LIKE_OPS op) (symbol? sym))
        sym))))

(defn- iteration->defined-symbols [iteration]
  (->> (read-edn-safe (:iteration/code iteration) [])
    (mapcat parse-forms-safe)
    (keep form->defined-symbol)
    distinct
    vec))

(defn db-query-history
  "Ordered query history for a conversation with compact summaries."
  [db-info conversation-ref]
  (let [queries (db-list-conversation-queries db-info conversation-ref)]
    (mapv (fn [idx query]
            (let [qref [:entity/id (:entity/id query)]
                  iterations (db-list-query-iterations db-info qref)
                  key-vars (->> iterations (mapcat iteration->defined-symbols) distinct vec)
                  answer-preview (str-truncate
                                   (or (some-> (:query/answer query) (read-edn-safe nil) str)
                                     (:query/answer query)
                                     (some-> iterations last :iteration/answer)
                                     "")
                                   160)]
              {:query-pos idx
               :query-ref qref
               :query-id (:entity/id query)
               :created-at (:entity/created-at query)
               :text (:query/text query)
               :status (:query/status query)
               :iterations (count iterations)
               :answer-preview answer-preview
               :key-vars key-vars}))
      (range)
      queries)))

(defn db-query-code
  "Ordered code blocks for a query with metadata."
  [db-info query-ref]
  (->> (db-list-query-iterations db-info query-ref)
    (map-indexed (fn [iter-pos iter]
                   {:iteration-pos iter-pos
                    :created-at (:entity/created-at iter)
                    :code (read-edn-safe (:iteration/code iter) [])
                    :answer (:iteration/answer iter)}))
    vec))

(defn db-query-results
  "Ordered result blocks for a query with vars + answer."
  [db-info query-ref]
  (->> (db-list-query-iterations db-info query-ref)
    (map-indexed (fn [iter-pos iter]
                   (let [iref [:entity/id (:entity/id iter)]]
                     {:iteration-pos iter-pos
                      :created-at (:entity/created-at iter)
                      :results (read-edn-safe (:iteration/results iter) [])
                      :vars (db-list-iteration-vars db-info iref)
                      :answer (:iteration/answer iter)})))
    vec))

(defn db-latest-var-registry
  "Builds latest restorable var registry for a conversation (last write wins)."
  ([db-info conversation-ref] (db-latest-var-registry db-info conversation-ref {}))
  ([db-info conversation-ref {:keys [max-scan-queries]}]
   (let [queries (cond->> (db-list-conversation-queries db-info conversation-ref)
                   max-scan-queries (take-last (max 0 (long max-scan-queries))))]
     (reduce (fn [acc query]
               (let [qref [:entity/id (:entity/id query)]]
                 (reduce (fn [m iter]
                           (reduce (fn [m2 {:keys [name value code]}]
                                     (if name
                                       (assoc m2 (symbol name)
                                         {:value value :code code
                                          :query-id (:entity/id query)
                                          :query-ref qref
                                          :iteration-id (:entity/id iter)
                                          :created-at (:entity/created-at iter)})
                                       m2))
                             m
                             (db-list-iteration-vars db-info [:entity/id (:entity/id iter)])))
                   acc
                   (db-list-query-iterations db-info qref))))
       {}
       queries))))

;; -----------------------------------------------------------------------------
;; Documents + TOC + raw documents
;; -----------------------------------------------------------------------------

(def db-list-documents             sq/db-list-documents)
(def db-get-document               sq/db-get-document)
(def db-store-pageindex-document!  sq/db-store-pageindex-document!)

(def db-store-toc-entry!    sq/db-store-toc-entry!)
(def db-search-toc-entries  sq/db-search-toc-entries)
(def db-get-toc-entry       sq/db-get-toc-entry)
(def db-list-toc-entries    sq/db-list-toc-entries)

;; -----------------------------------------------------------------------------
;; Pages + page nodes (search / list / get)
;; -----------------------------------------------------------------------------

(def db-get-page-node        sq/db-get-page-node)
(def db-list-page-nodes      sq/db-list-page-nodes)
(def db-search-page-nodes    sq/db-search-page-nodes)
(def db-search-batch         sq/db-search-batch)

;; -----------------------------------------------------------------------------
;; Entities + relationships
;; -----------------------------------------------------------------------------

(def db-get-entity          sq/db-get-entity)
(def db-list-entities       sq/db-list-entities)
(def db-search-entities     sq/db-search-entities)
(def db-list-relationships  sq/db-list-relationships)
(def store-relationship!    sq/store-relationship!)

(def find-related           sq/find-related)

(def resolve-canonical-id   sq/resolve-canonical-id)

;; -----------------------------------------------------------------------------
;; Git / repos / commits
;; -----------------------------------------------------------------------------

(def db-store-repo!        sq/db-store-repo!)
(def db-list-repos         sq/db-list-repos)
(def db-get-repo-by-name   sq/db-get-repo-by-name)

(def db-search-commits     sq/db-search-commits)
(def db-commit-by-sha      sq/db-commit-by-sha)
(def store-commit-entity!  sq/store-commit-entity!)

;; -----------------------------------------------------------------------------
;; Vitality / Q-values / Cooccurrence
;; -----------------------------------------------------------------------------

(def vitality-zone          sq/vitality-zone)
(def compute-page-vitality  sq/compute-page-vitality)
(def compute-node-vitality  sq/compute-node-vitality)
(def get-page-vitality      sq/get-page-vitality)

(def get-page-q-value       sq/get-page-q-value)
(def update-page-q-value!   sq/update-page-q-value!)
(def pages-accessed-since   sq/pages-accessed-since)
(def finalize-q-updates!    sq/finalize-q-updates!)

(def record-page-access!     sq/record-page-access!)
(def propagate-activation!   sq/propagate-activation!)

(def record-cooccurrence!        sq/record-cooccurrence!)
(def record-cooccurrences!       sq/record-cooccurrences!)
(def batch-cooccurrence-boosts   sq/batch-cooccurrence-boosts)
(def get-cooccurrence-boost      sq/get-cooccurrence-boost)
(def recently-accessed-page-ids  sq/recently-accessed-page-ids)

;; -----------------------------------------------------------------------------
;; Document certainty (Bayesian)
;; -----------------------------------------------------------------------------

(def document-certainty           sq/document-certainty)
(def record-document-access!      sq/record-document-access!)
(def decay-document-certainty!    sq/decay-document-certainty!)
(def reindex-certainty-jump!      sq/reindex-certainty-jump!)

;; -----------------------------------------------------------------------------
;; Skills
;; -----------------------------------------------------------------------------

(def skill-changed?        sq/skill-changed?)
(def ingest-skills!        sq/ingest-skills!)
(def delete-skill-entity!  sq/delete-skill-entity!)

;; -----------------------------------------------------------------------------
;; Claims (citation verification)
;; -----------------------------------------------------------------------------

(def db-store-claim!       sq/db-store-claim!)
(def db-cited-page-ids     sq/db-cited-page-ids)

;; -----------------------------------------------------------------------------
;; Refinement / stats helpers consumed by core.clj + query.clj
;; -----------------------------------------------------------------------------

(def db-stored-docs-for-refinement  sq/db-stored-docs-for-refinement)
(def db-count-document-pages        sq/db-count-document-pages)
(def db-entity-type-counts          sq/db-entity-type-counts)

;; -----------------------------------------------------------------------------
;; QA manifest corpus helpers
;; -----------------------------------------------------------------------------

(def qa-corpus-documents    sq/qa-corpus-documents)
(def qa-corpus-toc-entries  sq/qa-corpus-toc-entries)
(def qa-corpus-page-nodes   sq/qa-corpus-page-nodes)

;; -----------------------------------------------------------------------------
;; Results rendering
;; -----------------------------------------------------------------------------

(def results->markdown sq/results->markdown)
