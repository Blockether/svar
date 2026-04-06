(ns com.blockether.svar.internal.rlm.db
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [com.blockether.svar.internal.rlm.schema :refer [RLM_SCHEMA]]
   [datalevin.core :as d]
   [com.blockether.svar.internal.util :as util]
   [taoensso.trove :as trove]))

(declare db-list-page-nodes)
(declare db-list-toc-entries)
(declare db-list-entities)
(declare get-page-vitality)
(declare compute-node-vitality)

(defn str-truncate [s n] (when s (if (> (count s) n) (subs s 0 n) s)))

(defn str-lower [s] (when s (str/lower-case s)))

(defn str-includes? [s substr] (when s (str/includes? s substr)))

(defn create-rlm-conn
  "Creates or wraps a Datalevin connection for RLM.

   - conn: external connection (unified DB). Svar will NOT close it on dispose.
     Caller must ensure RLM_SCHEMA is merged into the external DB schema.
   - path: persistent DB at given path. Svar owns and closes it.
   - neither: temp DB (deleted on dispose).

   For unified storage, pass :conn to create-env."
  [{:keys [conn path]}]
  (if conn
    {:conn conn :path nil :owned? false}
    (let [dir (or path (str (System/getProperty "java.io.tmpdir") "/rlm-" (util/uuid)))
          c (d/get-conn dir RLM_SCHEMA)]
      {:conn c :path dir :owned? (nil? path)})))

(defn dispose-rlm-conn!
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

(defn store-conversation!
  "Stores or retrieves a conversation entity for an env session."
  [{:keys [conn]} {:keys [env-id system-prompt model]}]
  (when conn
    (let [existing (d/q '[:find ?e . :in $ ?env-id :where [?e :conversation/env-id ?env-id]]
                     (d/db conn) env-id)]
      (or existing
        (let [conv-id (java.util.UUID/randomUUID)]
          (d/transact! conn [{:conversation/id conv-id
                              :conversation/env-id env-id
                              :conversation/system-prompt (or system-prompt "")
                              :conversation/model (or model "")
                              :conversation/timestamp (java.util.Date.)}])
          [:conversation/id conv-id])))))

(defn store-query!
  "Stores a query outcome linked to a conversation."
  [{:keys [conn]} {:keys [conversation-ref text answer iterations duration-ms status eval-score]}]
  (when conn
    (let [query-id (java.util.UUID/randomUUID)]
      (d/transact! conn [(cond-> {:query/id query-id
                                  :query/conversation conversation-ref
                                  :query/text (or text "")
                                  :query/answer (or (when answer (pr-str answer)) "")
                                  :query/iterations (or iterations 0)
                                  :query/duration-ms (or duration-ms 0)
                                  :query/status (or status :unknown)
                                  :query/timestamp (java.util.Date.)}
                           eval-score (assoc :query/eval-score (float eval-score)))])
      [:query/id query-id])))

(defn update-query!
  "Updates a query record with final outcome."
  [{:keys [conn]} query-ref {:keys [answer iterations duration-ms status eval-score]}]
  (when conn
    (d/transact! conn [(cond-> {:query/id (second query-ref)
                                :query/answer (or (when answer (pr-str answer)) "")
                                :query/iterations (or iterations 0)
                                :query/duration-ms (or duration-ms 0)
                                :query/status (or status :unknown)}
                         eval-score (assoc :query/eval-score (float eval-score)))])))

(defn store-iteration!
  "Stores an iteration snapshot linked to a query."
  [{:keys [conn]} {:keys [query-ref index response executions thinking final duration-ms]}]
  (when conn
    (let [iter-id (java.util.UUID/randomUUID)
          code-strs (mapv :code (or executions []))
          result-strs (mapv #(try (pr-str (:result %)) (catch Exception _ "???")) (or executions []))]
      (d/transact! conn [(cond-> {:iteration/id iter-id
                                  :iteration/query query-ref
                                  :iteration/index (or index 0)
                                  :iteration/response (pr-str response)
                                  :iteration/code (pr-str code-strs)
                                  :iteration/results (pr-str result-strs)
                                  :iteration/thinking (or thinking "")
                                  :iteration/duration-ms (or duration-ms 0)
                                  :iteration/timestamp (java.util.Date.)}
                           final (assoc :iteration/final final))])
      [:iteration/id iter-id])))

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

(defn db-list-documents
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

;; -----------------------------------------------------------------------------
;; Page Node Storage & Search
;; -----------------------------------------------------------------------------

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

(defn- brevify-node
  "Strips full content from a page node, replacing with a 150-char preview.
   Preserves vitality fields (:vitality-score, :vitality-zone) if present.
   The LLM uses fetch-content to load full content into P when needed."
  [node]
  (let [content (or (:page.node/content node) (:page.node/description node) "")
        preview (if (> (count content) 150)
                  (str (subs content 0 150) "...")
                  content)]
    (-> node
      (dissoc :page.node/content :page.node/description)
      (assoc :preview preview
        :content-length (count content)))))

(defn db-search-page-nodes
  "Searches page nodes by text content with vitality-weighted reranking.

   Returns BRIEF results — metadata + 150-char preview + vitality score/zone.
   Use fetch-content to fetch full content into a variable.

   Results are ranked by: 0.7 × text-relevance + 0.3 × vitality-score.
   Archived nodes (vitality < 0.1) are filtered by default.

   Params:
   `db-info` - Map with :conn key.
   `query` - String. Case-insensitive text search over content and description.
             When nil/blank, falls back to list mode.
   `opts` - Map, optional:
     - :top-k - Integer. Max results (default: 10).
     - :document-id - String. Filter by document.
     - :type - Keyword. Filter by node type (:paragraph, :heading, etc.).
     - :min-vitality - Double. Minimum vitality threshold (default: 0.1, set 0.0 to include archived).

   Returns:
   Vector of brief page node maps with :vitality-score, :vitality-zone added."
  ([db-info query] (db-search-page-nodes db-info query {}))
  ([{:keys [conn] :as db-info} query {:keys [top-k document-id type min-vitality]
                                      :or {top-k 10 min-vitality 0.1}}]
   (if (str/blank? (str query))
     (mapv brevify-node (db-list-page-nodes db-info {:document-id document-id :type type :limit top-k}))
     (when conn
       (let [raw-results (->> (search-page-nodes-raw conn query)
                           (filter #(or (nil? document-id) (= document-id (:page.node/document-id %))))
                           (filter #(or (nil? type) (= type (:page.node/type %)))))
             ;; Cache page vitality lookups (many nodes share the same page)
             page-vitality-cache (atom {})
             cached-page-vitality (fn [page-id]
                                    (if-let [cached (get @page-vitality-cache page-id)]
                                      cached
                                      (let [v (or (get-page-vitality db-info page-id)
                                                {:score 1.0 :zone :active})]
                                        (swap! page-vitality-cache assoc page-id v)
                                        v)))
             ;; Assign relevance rank (position in fulltext results = relevance signal)
             total (max 1 (count raw-results))
             ranked (->> raw-results
                      (map-indexed
                        (fn [idx node]
                          (let [page-id (:page.node/page-id node)
                                page-v (cached-page-vitality page-id)
                                node-v (compute-node-vitality (:score page-v) (:page.node/type node))
                                ;; Relevance: 1.0 for first result, decreasing
                                relevance (- 1.0 (/ (double idx) total))
                                ;; Combined score
                                combined (+ (* 0.7 relevance) (* 0.3 (:score node-v)))]
                            (assoc node
                              ::combined-score combined
                              :vitality-score (:score node-v)
                              :vitality-zone (:zone node-v)))))
                      ;; Filter by min-vitality
                      (filter #(>= (:vitality-score %) min-vitality))
                      ;; Sort by combined score descending
                      (sort-by ::combined-score #(compare %2 %1))
                      (take top-k))]
         (mapv #(-> % (dissoc ::combined-score) brevify-node) ranked))))))

(defn db-get-page-node
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

(defn db-list-page-nodes
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

(defn db-store-toc-entry!
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
                        (assoc :document.toc/id (str (util/uuid))))]
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

(defn db-search-toc-entries
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

(defn db-get-toc-entry
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

(defn db-list-toc-entries
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

(defn db-search-entities
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

(defn db-get-entity
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

(defn db-list-entities
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

(defn db-list-relationships
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

;; -----------------------------------------------------------------------------
;; Final Result Persistence
;; -----------------------------------------------------------------------------

(defn db-store-final-result!
  "Stores a final result in Datalevin for cross-session persistence.

   Params:
   `db-info` - Map with :conn key.
   `result` - Map with :answer, :confidence, :summary keys.
   `query` - String. The user query that produced this result.
   `env-id` - String. RLM environment ID.

   Returns:
   Map with :final-result/id and :final-result/index."
  [{:keys [conn]} result query env-id]
  (when conn
    (let [;; Count existing final results to determine next index
          existing (d/q '[:find (count ?e) .
                          :where [?e :final-result/id _]]
                     (d/db conn))
          idx (inc (or existing 0))
          id (util/uuid)
          entity {:final-result/id id
                  :final-result/env-id (or env-id "unknown")
                  :final-result/index idx
                  :final-result/answer (str (:answer result))
                  :final-result/confidence (or (:confidence result) :high)
                  :final-result/summary (or (:summary result) "")
                  :final-result/query (or query "")
                  :final-result/timestamp (java.util.Date.)}]
      (d/transact! conn [entity])
      {:final-result/id id :final-result/index idx})))

(defn db-list-final-results
  "Lists all final results from Datalevin, ordered by index.

   Params:
   `db-info` - Map with :conn key.

   Returns:
   Vector of maps with :final-result/* keys."
  [{:keys [conn]}]
  (if conn
    (->> (d/q '[:find [(pull ?e [:final-result/id :final-result/index
                                 :final-result/answer :final-result/confidence
                                 :final-result/summary :final-result/query
                                 :final-result/timestamp]) ...]
                :where [?e :final-result/id _]]
           (d/db conn))
      (sort-by :final-result/index)
      vec)
    []))

;; -----------------------------------------------------------------------------
;; High-Level Document Storage
;; -----------------------------------------------------------------------------

(defn- build-page-entity
  "Builds a page datom without transacting.
   Initializes vitality tracking fields: created-at = now, access-count = 0."
  [page doc-id]
  (let [page-id (str doc-id "-page-" (:page/index page))
        now (java.util.Date.)]
    {:entity {:page/id page-id
              :page/document-id doc-id
              :page/index (:page/index page)
              :page/created-at now
              :page/last-accessed now
              :page/access-count 1.0}  ;; ingestion counts as first access
     :page-id page-id}))

(defn- build-page-node-entity
  "Builds a page node datom without transacting."
  [node page-id doc-id]
  (let [node-id (str page-id "-node-" (or (:page.node/id node) (util/uuid)))
        visual-node? (#{:image :table} (:page.node/type node))
        img-bytes (:page.node/image-data node)
        image-too-large? (and visual-node? img-bytes (> (alength ^bytes img-bytes) 5242880))
        image-data (when (and visual-node? img-bytes (not image-too-large?)) img-bytes)]
    (when image-too-large?
      (trove/log! {:level :warn :data {:page-node-id node-id :bytes-size (alength ^bytes img-bytes)}
                   :msg "Skipping page node image-data (exceeds 5MB limit)"}))
    (cond-> {:page.node/id node-id :page.node/page-id page-id
             :page.node/document-id doc-id :page.node/type (:page.node/type node)}
      (:page.node/id node)             (assoc :page.node/local-id (:page.node/id node))
      (:page.node/parent-id node)      (assoc :page.node/parent-id (:page.node/parent-id node))
      (:page.node/level node)          (assoc :page.node/level (:page.node/level node))
      (and (not visual-node?) (:page.node/content node)) (assoc :page.node/content (:page.node/content node))
      image-data                        (assoc :page.node/image-data image-data)
      (:page.node/description node)    (assoc :page.node/description (:page.node/description node))
      (some? (:page.node/continuation? node)) (assoc :page.node/continuation? (:page.node/continuation? node))
      (:page.node/caption node)        (assoc :page.node/caption (:page.node/caption node))
      (:page.node/kind node)           (assoc :page.node/kind (:page.node/kind node))
      (:page.node/bbox node)           (assoc :page.node/bbox (pr-str (:page.node/bbox node)))
      (:page.node/group-id node)       (assoc :page.node/group-id (:page.node/group-id node)))))

(defn db-store-pageindex-document!
  "Stores an entire PageIndex document with all its components.
   Batches pages+nodes and TOC entries into bulk transactions.

   Params:
   `db-info` - Map with :conn key.
   `doc` - Complete PageIndex document (spec-validated).

   Returns:
   Map with :document-id and counts of stored entities."
  [{:keys [conn] :as db-info} doc]
  (let [doc-id (str (util/uuid))
        ;; Store raw document for replay/debugging
        _ (d/transact! conn [{:raw-document/id doc-id :raw-document/content (pr-str doc)}])
        ;; Store document metadata
        _ (db-store-document! db-info doc doc-id)
        ;; Build all page + node entities in memory, then batch-transact
        pages-and-nodes (vec (mapcat (fn [page]
                                       (let [{:keys [entity page-id]} (build-page-entity page doc-id)
                                             node-entities (mapv #(build-page-node-entity % page-id doc-id)
                                                             (:page/nodes page))]
                                         (cons entity node-entities)))
                               (:document/pages doc)))
        page-count (count (:document/pages doc))
        node-count (- (count pages-and-nodes) page-count)
        _ (when (seq pages-and-nodes)
            (d/transact! conn pages-and-nodes))
        ;; Build all TOC entries and batch-transact
        toc-entities (vec (keep (fn [entry]
                                  (let [entry-id (str doc-id "-toc-" (or (:document.toc/id entry) (util/uuid)))]
                                    (cond-> {:document.toc/id entry-id
                                             :document.toc/document-id doc-id}
                                      (:document.toc/title entry) (assoc :document.toc/title (:document.toc/title entry))
                                      (:document.toc/description entry) (assoc :document.toc/description (:document.toc/description entry))
                                      (:document.toc/target-page entry) (assoc :document.toc/target-page (:document.toc/target-page entry))
                                      (:document.toc/level entry) (assoc :document.toc/level (:document.toc/level entry))
                                      (:document.toc/parent-id entry) (assoc :document.toc/parent-id (:document.toc/parent-id entry)))))
                            (:document/toc doc)))
        _ (when (seq toc-entities)
            (d/transact! conn toc-entities))]
    {:document-id doc-id
     :pages-stored page-count
     :nodes-stored node-count
     :toc-entries-stored (count toc-entities)}))

;; -----------------------------------------------------------------------------
;; Vitality: ACT-R Memory Decay with Type-Aware Metabolic Rates
;; -----------------------------------------------------------------------------

(def ^:private METABOLIC_RATES
  "Decay speed multipliers per node type. Lower = slower decay (more persistent).
   Section/Heading are structural scaffolding, TocEntry is navigation — they persist.
   Paragraph/ListItem are content that ages normally."
  {:section   0.3
   :heading   0.3
   :paragraph 1.0
   :list-item 1.0
   :toc-entry 0.1
   :table     0.5
   :image     0.5
   :header    0.8
   :footer    0.8
   :metadata  0.3
   :entity    0.8})

(def ^:private VITALITY_ZONES
  "Zone classification thresholds. Sorted descending for first-match."
  [[0.6 :active]
   [0.3 :stale]
   [0.1 :fading]
   [0.0 :archived]])

(defn vitality-zone
  "Classifies a vitality score into a zone: :active, :stale, :fading, or :archived."
  [score]
  (or (some (fn [[threshold zone]] (when (>= score threshold) zone)) VITALITY_ZONES)
    :archived))

(defn compute-page-vitality
  "Computes vitality score (0.0–1.0) for a page using ACT-R base-level activation.

   Formula:
     B = ln(access-count / (1 - d)) - d × ln(lifetime-days)
     base-vitality = sigmoid(B) = 1 / (1 + e^(-B))

   Then modulated by:
     structural-boost = 1 + 0.1 × min(children-count, 10)

   Returns map with :score (0.0–1.0) and :zone (:active/:stale/:fading/:archived).

   Params:
   `access-count` - Double. Accumulated access count (fetch=1.0, search-hit=0.2).
   `created-at`   - java.util.Date. When the page was ingested.
   `last-accessed` - java.util.Date. Last access time.
   `children-count` - Long. Number of child nodes on this page.
   `now`           - java.util.Date, optional. Current time (default: now)."
  ([access-count created-at last-accessed children-count]
   (compute-page-vitality access-count created-at last-accessed children-count (java.util.Date.)))
  ([access-count created-at last-accessed children-count now]
   (let [d 0.5 ;; ACT-R decay parameter
         ;; Use time-since-last-access as primary decay driver (not lifetime).
         ;; Re-accessing a page resets its decay clock — core ACT-R behavior.
         recency-ms (max 1 (- (.getTime ^java.util.Date now) (.getTime ^java.util.Date last-accessed)))
         recency-days (max 0.001 (/ recency-ms 86400000.0))
         access (max 0.01 (double access-count)) ;; avoid ln(0)
         ;; ACT-R base-level activation: B = ln(n/(1-d)) - d*ln(T)
         ;; where n = access count, T = days since last access
         B (- (Math/log (/ access (- 1.0 d)))
             (* d (Math/log recency-days)))
         ;; Sigmoid to [0, 1]
         base-vitality (/ 1.0 (+ 1.0 (Math/exp (- B))))
         ;; Structural boost: pages with many children resist decay (max 1.5x)
         structural-boost (+ 1.0 (* 0.05 (min children-count 10)))
         ;; Final score clamped to [0, 1]
         score (min 1.0 (* base-vitality structural-boost))]
     {:score score
      :zone (vitality-zone score)})))

(defn compute-node-vitality
  "Computes vitality for a specific node, combining page vitality with type metabolic rate.

   Params:
   `page-vitality-score` - Double. The page's vitality score (0.0–1.0).
   `node-type`           - Keyword. The node type (:section, :paragraph, etc.).

   Returns map with :score and :zone."
  [page-vitality-score node-type]
  (let [metabolic-rate (get METABOLIC_RATES node-type 1.0)
        ;; Power law: effective = page_v ^ metabolic_rate
        ;; rate 0.3 (section): 0.1^0.3 = 0.50 (slow decay), 0.0^0.3 = 0.0 (dead is dead)
        ;; rate 1.0 (paragraph): 0.1^1.0 = 0.10 (normal decay)
        ;; rate 0.1 (toc-entry): 0.1^0.1 = 0.79 (very slow decay)
        effective-score (if (pos? page-vitality-score)
                          (Math/pow page-vitality-score metabolic-rate)
                          0.0)]
    {:score effective-score
     :zone (vitality-zone effective-score)}))

(defn record-page-access!
  "Records a page access in Datalevin. Updates last-accessed and increments access-count.

   Params:
   `db-info` - Map with :conn key.
   `page-id` - String. The page ID to update.
   `weight`  - Double. Access weight (1.0 for fetch, 0.2 for search hit)."
  [{:keys [conn]} page-id weight]
  (when conn
    (try
      (let [now (java.util.Date.)
            existing (d/pull (d/db conn) [:page/access-count] [:page/id page-id])
            current-count (or (:page/access-count existing) 0.0)]
        (d/transact! conn [{:page/id page-id
                            :page/last-accessed now
                            :page/access-count (+ current-count (double weight))}]))
      (catch Exception e
        (trove/log! {:level :warn :data {:page-id page-id :error (ex-message e)}
                     :msg "Failed to record page access"})))))

(defn get-page-vitality
  "Fetches page temporal data and computes its vitality.

   Params:
   `db-info` - Map with :conn key.
   `page-id` - String. The page ID.

   Returns map with :score, :zone, :access-count, :last-accessed, :created-at
   or nil if page not found."
  [{:keys [conn]} page-id]
  (when conn
    (let [db (d/db conn)
          page (d/pull db
                 [:page/created-at :page/last-accessed :page/access-count :page/index]
                 [:page/id page-id])
          ;; Count children (nodes on this page) — same DB snapshot
          children-count (or (first (d/q '[:find [(count ?n)]
                                           :in $ ?pid
                                           :where [?n :page.node/page-id ?pid]]
                                      db page-id))
                           0)]
      (when (:page/created-at page)
        (let [{:keys [score zone]} (compute-page-vitality
                                     (or (:page/access-count page) 0.0)
                                     (:page/created-at page)
                                     (or (:page/last-accessed page) (:page/created-at page))
                                     children-count)]
          {:score score
           :zone zone
           :access-count (or (:page/access-count page) 0.0)
           :last-accessed (:page/last-accessed page)
           :created-at (:page/created-at page)
           :children-count children-count})))))

;; -----------------------------------------------------------------------------
;; TOC Entry SCI Functions (for LLM to call during execution)
;; -----------------------------------------------------------------------------
