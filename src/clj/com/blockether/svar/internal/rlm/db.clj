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

(declare db-list-page-nodes)
(declare db-list-toc-entries)
(declare db-list-entities)

(defn store-trajectory!
  "Stores a trajectory record for training data collection.
   Called at the end of query-env! with the query outcome."
  [{:keys [conn]} {:keys [env-id query status answer iterations duration-ms model doc-pages eval-score]}]
  (when conn
    (let [traj-id (java.util.UUID/randomUUID)]
      (d/transact! conn [(cond-> {:trajectory/id traj-id
                                  :trajectory/env-id (or env-id "")
                                  :trajectory/query (or query "")
                                  :trajectory/status (or status :unknown)
                                  :trajectory/answer (or (when answer (pr-str answer)) "")
                                  :trajectory/iterations (or iterations 0)
                                  :trajectory/duration-ms (or duration-ms 0)
                                  :trajectory/model (or model "")
                                  :trajectory/doc-pages (or doc-pages 0)
                                  :trajectory/timestamp (java.util.Date.)}
                           eval-score (assoc :trajectory/eval-score (float eval-score)))])
      traj-id)))

(defn store-iteration!
  "Stores a complete iteration snapshot — exact LLM input/output for fine-tuning.
   Captures the EXACT messages sent to LLM and the parsed response."
  [{:keys [conn]} {:keys [env-id index input-messages response executions thinking duration-ms]}]
  (when conn
    (let [iter-id (java.util.UUID/randomUUID)
          code-strs (mapv :code (or executions []))
          result-strs (mapv #(try (pr-str (:result %)) (catch Exception _ "???")) (or executions []))]
      (d/transact! conn [{:iteration/id iter-id
                          :iteration/env-id (or env-id "")
                          :iteration/index (or index 0)
                          :iteration/input-messages (pr-str input-messages)
                          :iteration/response (pr-str response)
                          :iteration/code (pr-str code-strs)
                          :iteration/results (pr-str result-strs)
                          :iteration/thinking (or thinking "")
                          :iteration/duration-ms (or duration-ms 0)
                          :iteration/timestamp (java.util.Date.)}])
      iter-id)))

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

(defn db-get-document
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
   The LLM uses P-add! to load full content into P when needed."
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
  "Searches page nodes by text content, optionally filtered by document and type.
   
   Returns BRIEF results by default — metadata + 150-char preview.
   Use P-add! to fetch full content into a variable.
   
   Params:
   `db-info` - Map with :store key.
   `query` - String. Case-insensitive text search over content and description.
             When nil/blank, falls back to list mode.
   `opts` - Map, optional:
     - :top-k - Integer. Max results (default: 10).
     - :document-id - String. Filter by document.
     - :type - Keyword. Filter by node type (:paragraph, :heading, etc.).
   
   Returns:
   Vector of brief page node maps: {:page.node/id :page.node/type :page.node/page-id
                                     :page.node/document-id :preview :content-length}"
  ([db-info query] (db-search-page-nodes db-info query {}))
  ([{:keys [conn] :as db-info} query {:keys [top-k document-id type] :or {top-k 10}}]
   (if (str/blank? (str query))
     (mapv brevify-node (db-list-page-nodes db-info {:document-id document-id :type type :limit top-k}))
     (when conn
       (->> (search-page-nodes-raw conn query)
         (filter #(or (nil? document-id) (= document-id (:page.node/document-id %))))
         (filter #(or (nil? type) (= type (:page.node/type %))))
         (take top-k)
         (mapv brevify-node))))))

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

(defn db-entity-stats
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
  "Builds a page datom without transacting."
  [page doc-id]
  (let [page-id (str doc-id "-page-" (:page/index page))]
    {:entity {:page/id page-id :page/document-id doc-id :page/index (:page/index page)}
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
;; TOC Entry SCI Functions (for LLM to call during execution)
;; -----------------------------------------------------------------------------
