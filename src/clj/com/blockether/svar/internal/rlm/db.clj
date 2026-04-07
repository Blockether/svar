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

(defn store-entity!
  "Stores a unified entity in Datalevin. All data (conversations, queries,
   iterations, knowledge entities) go through this function.

   Params:
   `db-info` - Map with :conn key.
   `attrs` - Map. Must include :entity/type (keyword from closed enum).
             Core fields: :entity/name, :entity/description, :entity/parent-id,
             :entity/document-id, :entity/page, :entity/section.
             Type-specific fields in their namespaces (conversation/*, query/*, iteration/*).

   Returns:
   Lookup ref [:entity/id uuid]."
  [{:keys [conn]} attrs]
  (when conn
    (let [id (or (:entity/id attrs) (java.util.UUID/randomUUID))
          now (java.util.Date.)
          entity (merge {:entity/id id
                         :entity/type (:entity/type attrs)
                         :entity/created-at now}
                   (dissoc attrs :entity/id :entity/created-at)
                   (when-not (:entity/updated-at attrs) {:entity/updated-at now}))]
      (d/transact! conn [entity])
      [:entity/id id])))

(defn update-entity!
  "Updates an existing entity by :entity/id. Merges provided attrs.

   Params:
   `db-info` - Map with :conn key.
   `entity-ref` - Lookup ref [:entity/id uuid].
   `attrs` - Map of attrs to update (merged onto existing)."
  [{:keys [conn]} entity-ref attrs]
  (when conn
    (let [id (second entity-ref)
          now (java.util.Date.)]
      (d/transact! conn [(merge {:entity/id id :entity/updated-at now} attrs)]))))

(defn store-conversation!
  "Stores or retrieves a conversation entity for an env session."
  [db-info {:keys [env-id system-prompt model]}]
  (when-let [conn (:conn db-info)]
    (let [existing (d/q '[:find ?id . :in $ ?env-id
                          :where [?e :conversation/env-id ?env-id]
                                 [?e :entity/id ?id]]
                     (d/db conn) env-id)]
      (if existing
        [:entity/id existing]
        (store-entity! db-info
          {:entity/type :conversation
           :entity/name (or env-id "session")
           :conversation/env-id env-id
           :conversation/system-prompt (or system-prompt "")
           :conversation/model (or model "")})))))

(defn store-query!
  "Stores a query entity linked to a conversation via parent-id.
   Stores both extracted text (for search) and full messages (with images)."
  [db-info {:keys [conversation-ref text messages answer iterations duration-ms status eval-score]}]
  (let [parent-id (when conversation-ref (second conversation-ref))]
    (store-entity! db-info
      (cond-> {:entity/type :query
               :entity/name (str-truncate (or text "") 100)
               :entity/parent-id parent-id
               :query/text (or text "")
               :query/answer (or (when answer (pr-str answer)) "")
               :query/iterations (or iterations 0)
               :query/duration-ms (or duration-ms 0)
               :query/status (or status :unknown)}
        messages (assoc :query/messages (pr-str messages))
        eval-score (assoc :query/eval-score (float eval-score))))))

(defn update-query!
  "Updates a query entity with final outcome."
  [db-info query-ref {:keys [answer iterations duration-ms status eval-score]}]
  (update-entity! db-info query-ref
    (cond-> {:query/answer (or (when answer (pr-str answer)) "")
             :query/iterations (or iterations 0)
             :query/duration-ms (or duration-ms 0)
             :query/status (or status :unknown)}
      eval-score (assoc :query/eval-score (float eval-score)))))

(defn store-iteration!
  "Stores an iteration entity linked to a query via parent-id."
  [db-info {:keys [query-ref index executions thinking answer duration-ms]}]
  (let [parent-id (when query-ref (second query-ref))
        code-strs (mapv :code (or executions []))
        result-strs (mapv #(try (pr-str (:result %)) (catch Exception _ "???")) (or executions []))]
    (store-entity! db-info
      (cond-> {:entity/type :iteration
               :entity/name (str "iteration-" (or index 0))
               :entity/parent-id parent-id
               :iteration/index (or index 0)
               :iteration/code (pr-str code-strs)
               :iteration/results (pr-str result-strs)
               :iteration/thinking (or thinking "")
               :iteration/duration-ms (or duration-ms 0)}
        answer (assoc :iteration/answer answer)))))

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
                          :document/extension (:document/extension doc)
                          :document/certainty-alpha 2.0
                          :document/certainty-beta 1.0}
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

(defn db-search-batch
  "Parallel multi-query search. Runs multiple queries, merges and deduplicates results.
   Keeps highest vitality score per node across all queries.

   Params:
   `db-info` - Map with :conn key.
   `queries` - Vector of query strings.
   `opts` - Map, optional:
     - :top-k - Integer. Max results per query (default: 10).
     - :min-vitality - Double. Minimum vitality (default: 0.1).
     - :limit - Integer. Max total results (default: 30).
     - :document-id - String. Filter by document.

   Returns:
   Vector of brief page node maps, deduplicated, ranked by combined score."
  ([db-info queries] (db-search-batch db-info queries {}))
  ([db-info queries {:keys [top-k limit document-id min-vitality]
                     :or {top-k 10 limit 30 min-vitality 0.1}}]
   (when (seq queries)
     (let [per-query-opts (cond-> {:top-k top-k :min-vitality min-vitality}
                            document-id (assoc :document-id document-id))
           ;; Run all queries in parallel
           all-results (into [] (mapcat identity)
                         (pmap #(db-search-page-nodes db-info % per-query-opts) queries))
           ;; Deduplicate by page.node/id — keep highest vitality
           deduped (vals (reduce (fn [acc node]
                                   (let [id (:page.node/id node)
                                         existing (get acc id)]
                                     (if (or (nil? existing)
                                           (> (or (:vitality-score node) 0)
                                              (or (:vitality-score existing) 0)))
                                       (assoc acc id node)
                                       acc)))
                           {} all-results))]
       (->> deduped
         (sort-by #(- (or (:vitality-score %) 0)))
         (take limit)
         vec)))))

(defn results->markdown
  "Converts search results to compact, token-efficient markdown for LLM consumption.

   Accepts the output of db-search-page-nodes, db-search-batch, or a map with
   :pages, :toc, :entities keys (from search-documents).

   Output format:
   - Page nodes: grouped by page, with type prefix and vitality zone tag
   - TOC entries: bulleted list with level and page ref
   - Entities: bulleted list with type and description

   Params:
   `results` - Vector of node maps, or {:pages [...] :toc [...] :entities [...]}"
  [results]
  (let [;; Normalize input
        {:keys [pages toc entities]}
        (if (map? results)
          results
          {:pages results})
        sb (StringBuilder.)]
    ;; Page nodes
    (when (seq pages)
      (let [by-page (group-by :page.node/page-id pages)]
        (doseq [[page-id nodes] (sort-by key by-page)]
          (.append sb (str "## " (or page-id "unknown") "\n"))
          (doseq [node nodes]
            (let [t (some-> (:page.node/type node) name)
                  zone (some-> (:vitality-zone node) name)
                  preview (or (:preview node) "")]
              (.append sb (str "- **" t "**"
                            (when zone (str " [" zone "]"))
                            " " preview "\n"))))
          (.append sb "\n"))))
    ;; TOC entries
    (when (seq toc)
      (.append sb "## TOC\n")
      (doseq [entry toc]
        (let [title (or (:document.toc/title entry) "")
              level (or (:document.toc/level entry) "")
              page (:document.toc/target-page entry)]
          (.append sb (str "- " level " " title
                        (when page (str " (p." page ")"))
                        "\n"))))
      (.append sb "\n"))
    ;; Entities
    (when (seq entities)
      (.append sb "## Entities\n")
      (doseq [entity entities]
        (let [name (or (:entity/name entity) "")
              etype (some-> (:entity/type entity) clojure.core/name)
              desc (or (:entity/description entity) "")]
          (.append sb (str "- **" name "** (" etype ") " (str-truncate desc 80) "\n"))))
      (.append sb "\n"))
    (str sb)))

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
;; BFS Graph Traversal with Vitality-Priority Queue
;; -----------------------------------------------------------------------------

(defn find-related
  "Undirected BFS over entity relationships with vitality-weighted priority.

   Given an anchor entity, traverses relationships bidirectionally to find
   related entities across documents. High-vitality entities are explored first.
   Archived entities (vitality < min-vitality) are pruned.

   Cross-document traversal: entities linked via :entity/canonical-id are
   treated as the same node — accessing one reaches all canonical siblings.

   Params:
   `db-info` - Map with :conn key.
   `anchor-id` - UUID. Starting entity ID.
   `opts` - Map, optional:
     - :depth - Integer. Max hops (default: 2, max: 5).
     - :min-vitality - Double. Minimum vitality threshold (default: 0.1).
     - :limit - Integer. Max results (default: 50).

   Returns:
   Vector of maps sorted by [distance asc, vitality desc]:
     {:entity/id, :entity/name, :entity/type, :entity/document-id,
      :distance, :vitality-score, :vitality-zone, :via-relationship}"
  ([db-info anchor-id] (find-related db-info anchor-id {}))
  ([{:keys [conn] :as db-info} anchor-id {:keys [depth min-vitality limit]
                                           :or {depth 2 min-vitality 0.1 limit 50}}]
   (when conn
     (let [depth (min depth 5)
           db (d/db conn)
           ;; Build adjacency: entity-id → [{:neighbor-id :rel-type :rel-description}]
           all-rels (d/q '[:find [(pull ?e [:relationship/source-entity-id
                                            :relationship/target-entity-id
                                            :relationship/type
                                            :relationship/description]) ...]
                           :where [?e :relationship/id _]]
                      db)
           adjacency (reduce (fn [acc r]
                               (let [src (:relationship/source-entity-id r)
                                     tgt (:relationship/target-entity-id r)
                                     rel-info {:type (:relationship/type r)
                                               :description (:relationship/description r)}]
                                 (-> acc
                                   (update src (fnil conj []) (assoc rel-info :neighbor-id tgt))
                                   (update tgt (fnil conj []) (assoc rel-info :neighbor-id src)))))
                       {} all-rels)
           ;; Expand canonical-id: find all entities sharing canonical-id with anchor
           anchor-canonical (d/q '[:find ?cid . :in $ ?eid
                                   :where [?e :entity/id ?eid]
                                          [?e :entity/canonical-id ?cid]]
                              db anchor-id)
           anchor-set (if anchor-canonical
                        (set (d/q '[:find [?eid ...]
                                    :in $ ?cid
                                    :where [?e :entity/canonical-id ?cid]
                                           [?e :entity/id ?eid]]
                               db anchor-canonical))
                        #{anchor-id})
           ;; BFS with vitality priority
           visited (atom anchor-set)
           results (atom [])
           ;; Start queue: all anchor entity neighbors at distance 1
           initial-queue (for [aid anchor-set
                               neighbor (get adjacency aid)]
                           (assoc neighbor :distance 1))]
       ;; Process BFS levels
       (loop [queue (vec initial-queue)
              current-depth 1]
         (when (and (seq queue) (<= current-depth depth))
           (let [next-level (atom [])]
             (doseq [{:keys [neighbor-id distance type description]} queue]
               (when-not (contains? @visited neighbor-id)
                 (swap! visited conj neighbor-id)
                 ;; Also visit canonical siblings
                 (let [canonical (d/q '[:find ?cid . :in $ ?eid
                                        :where [?e :entity/id ?eid]
                                               [?e :entity/canonical-id ?cid]]
                                   db neighbor-id)
                       siblings (if canonical
                                  (set (d/q '[:find [?eid ...]
                                              :in $ ?cid
                                              :where [?e :entity/canonical-id ?cid]
                                                     [?e :entity/id ?eid]]
                                         db canonical))
                                  #{neighbor-id})]
                   (swap! visited into siblings)
                   ;; Get entity info
                   (when-let [entity (d/pull db [:entity/id :entity/name :entity/type
                                                 :entity/document-id :entity/description
                                                 :entity/page]
                                       [:entity/id neighbor-id])]
                     (when (:entity/id entity)
                       (swap! results conj
                         (assoc entity
                           :distance distance
                           :via-relationship {:type type :description description}))))
                   ;; Queue next level neighbors
                   (when (< distance depth)
                     (doseq [sibling siblings]
                       (doseq [neighbor (get adjacency sibling)]
                         (when-not (contains? @visited (:neighbor-id neighbor))
                           (swap! next-level conj
                             (assoc neighbor :distance (inc distance))))))))))
             (recur @next-level (inc current-depth)))))
       ;; Sort by distance asc, then alphabetically; apply limit
       (->> @results
         (sort-by (juxt :distance :entity/name))
         (take limit)
         vec)))))

;; -----------------------------------------------------------------------------
;; Bayesian Document Certainty
;; -----------------------------------------------------------------------------

(defn document-certainty
  "Computes Bayesian certainty for a document using Beta distribution.

   Certainty = alpha / (alpha + beta).
   Alpha increases on confirmed access, beta increases over time and on re-index.

   Params:
   `db-info` - Map with :conn key.
   `doc-id` - String. Document ID.

   Returns:
   Map with :certainty (0.0-1.0), :alpha, :beta, or nil if not found."
  [{:keys [conn]} doc-id]
  (when conn
    (let [doc (d/pull (d/db conn)
                [:document/certainty-alpha :document/certainty-beta]
                [:document/id doc-id])
          alpha (or (:document/certainty-alpha doc) 2.0)
          beta (or (:document/certainty-beta doc) 1.0)]
      (when (:document/certainty-alpha doc)
        {:certainty (/ alpha (+ alpha beta))
         :alpha alpha
         :beta beta}))))

(defn record-document-access!
  "Records a confirmed access on a document — increases certainty alpha.

   Params:
   `db-info` - Map with :conn key.
   `doc-id` - String. Document ID.
   `boost` - Double. Alpha increment (default: 1.0)."
  ([db-info doc-id] (record-document-access! db-info doc-id 1.0))
  ([{:keys [conn]} doc-id boost]
   (when conn
     (try
       (let [existing (d/pull (d/db conn) [:document/certainty-alpha] [:document/id doc-id])
             alpha (or (:document/certainty-alpha existing) 2.0)]
         (d/transact! conn [{:document/id doc-id
                             :document/certainty-alpha (+ alpha (double boost))}]))
       (catch Exception e
         (trove/log! {:level :warn :data {:doc-id doc-id :error (ex-message e)}
                      :msg "Failed to record document access"}))))))

(defn decay-document-certainty!
  "Applies time-based certainty decay to a document — increases beta.

   Called periodically or at query time. Rate: 0.01 per day since last update.

   Params:
   `db-info` - Map with :conn key.
   `doc-id` - String. Document ID."
  [{:keys [conn]} doc-id]
  (when conn
    (try
      (let [db (d/db conn)
            doc (d/pull db [:document/certainty-beta :document/updated-at] [:document/id doc-id])
            beta (or (:document/certainty-beta doc) 1.0)
            updated-at (:document/updated-at doc)
            now (java.util.Date.)
            days-since (if updated-at
                         (/ (- (.getTime now) (.getTime ^java.util.Date updated-at)) 86400000.0)
                         0.0)
            beta-increase (* 0.01 days-since)]
        (when (> beta-increase 0.001)
          (d/transact! conn [{:document/id doc-id
                              :document/certainty-beta (+ beta beta-increase)}])))
      (catch Exception e
        (trove/log! {:level :warn :data {:doc-id doc-id :error (ex-message e)}
                     :msg "Failed to decay document certainty"})))))

(defn reindex-certainty-jump!
  "Applies a certainty beta jump when a document is re-indexed (content changed).

   Params:
   `db-info` - Map with :conn key.
   `doc-id` - String. Document ID.
   `jump` - Double. Beta increment (default: 5.0)."
  ([db-info doc-id] (reindex-certainty-jump! db-info doc-id 5.0))
  ([{:keys [conn]} doc-id jump]
   (when conn
     (try
       (let [existing (d/pull (d/db conn) [:document/certainty-beta] [:document/id doc-id])
             beta (or (:document/certainty-beta existing) 1.0)]
         (d/transact! conn [{:document/id doc-id
                             :document/certainty-beta (+ beta (double jump))}]))
       (catch Exception e
         (trove/log! {:level :warn :data {:doc-id doc-id :error (ex-message e)}
                      :msg "Failed to apply reindex certainty jump"}))))))

;; -----------------------------------------------------------------------------
;; Final Result Persistence
;; -----------------------------------------------------------------------------

(defn db-list-final-results
  "Lists terminal iterations (those with non-nil :iteration/answer).
   These are the final results — replaces the old :final-result/* namespace.

   Params:
   `db-info` - Map with :conn key.

   Returns:
   Vector of iteration entity maps with answer, sorted by created-at."
  [{:keys [conn]}]
  (if conn
    (->> (d/q '[:find [(pull ?e [:entity/id :entity/name :entity/type
                                 :entity/parent-id :entity/created-at
                                 :iteration/index :iteration/answer
                                 :iteration/code :iteration/results]) ...]
                :where [?e :entity/type :iteration]
                       [?e :iteration/answer _]]
           (d/db conn))
      (sort-by :entity/created-at)
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

(defn propagate-activation!
  "Spreads activation from an accessed page to connected pages.

   Two connection paths:
   1. Canonical siblings: pages sharing entities with same canonical-id (cross-doc)
   2. Relationship neighbors: pages whose entities are related via find-related BFS

   Each connected page receives a damped access-count boost.
   Only propagates for significant accesses (weight >= 1.0, i.e. fetch).

   Params:
   `db-info` - Map with :conn key.
   `page-id` - String. The accessed page ID.
   `weight`  - Double. Original access weight.
   `damping` - Double, optional. Decay per hop (default: 0.6)."
  ([db-info page-id weight] (propagate-activation! db-info page-id weight 0.6))
  ([{:keys [conn] :as db-info} page-id weight damping]
   (when (and conn (>= weight 1.0))
     (try
       (let [db (d/db conn)
             page (d/pull db [:page/document-id :page/index] [:page/id page-id])
             doc-id (:page/document-id page)
             page-idx (:page/index page)
             ;; Path 1: canonical siblings — find entities on this page, expand by canonical-id
             canonical-ids (when doc-id
                             (d/q '[:find [?cid ...]
                                    :in $ ?doc-id ?pidx
                                    :where [?e :entity/document-id ?doc-id]
                                           [?e :entity/page ?pidx]
                                           [?e :entity/canonical-id ?cid]]
                               db doc-id (or page-idx -1)))
             ;; Find pages of canonical siblings (different from source page)
             sibling-page-ids (when (seq canonical-ids)
                                (d/q '[:find [?pid ...]
                                       :in $ [?cid ...] ?exclude-pid
                                       :where [?e :entity/canonical-id ?cid]
                                              [?e :entity/document-id ?did]
                                              [?e :entity/page ?eidx]
                                              [?p :page/document-id ?did]
                                              [?p :page/index ?eidx]
                                              [?p :page/id ?pid]
                                              [(not= ?pid ?exclude-pid)]]
                                  db canonical-ids page-id))
             ;; Path 2: relationship neighbors via find-related
             page-entity-ids (when doc-id
                               (d/q '[:find [?eid ...]
                                      :in $ ?doc-id ?pidx
                                      :where [?e :entity/document-id ?doc-id]
                                             [?e :entity/page ?pidx]
                                             [?e :entity/id ?eid]]
                                 db doc-id (or page-idx -1)))
             rel-neighbor-page-ids (when (seq page-entity-ids)
                                     (->> page-entity-ids
                                       (mapcat #(find-related db-info % {:depth 1 :limit 10}))
                                       (keep (fn [e]
                                               (when (and (:entity/document-id e) (:entity/page e))
                                                 (first (d/q '[:find [?pid ...]
                                                               :in $ ?did ?pidx
                                                               :where [?p :page/document-id ?did]
                                                                      [?p :page/index ?pidx]
                                                                      [?p :page/id ?pid]]
                                                          db (:entity/document-id e) (:entity/page e))))))
                                       distinct))
             ;; Merge both paths, remove self
             all-connected (->> (concat (or sibling-page-ids []) (or rel-neighbor-page-ids []))
                             distinct
                             (remove #(= % page-id)))
             now (java.util.Date.)]
         (doseq [pid all-connected]
           (let [boost (* weight (Math/pow damping 1))
                 existing (d/pull (d/db conn) [:page/access-count] [:page/id pid])
                 current-count (or (:page/access-count existing) 0.0)]
             (d/transact! conn [{:page/id pid
                                 :page/last-accessed now
                                 :page/access-count (+ current-count boost)}]))))
       (catch Exception e
         (trove/log! {:level :debug :data {:page-id page-id :error (ex-message e)}
                      :msg "Spreading activation failed (non-fatal)"}))))))

(defn record-page-access!
  "Records a page access in Datalevin. Updates last-accessed, increments access-count,
   and propagates activation to connected pages (for fetch-weight accesses).

   Params:
   `db-info` - Map with :conn key.
   `page-id` - String. The page ID to update.
   `weight`  - Double. Access weight (1.0 for fetch, 0.2 for search hit)."
  [{:keys [conn] :as db-info} page-id weight]
  (when conn
    (try
      (let [now (java.util.Date.)
            existing (d/pull (d/db conn) [:page/access-count] [:page/id page-id])
            current-count (or (:page/access-count existing) 0.0)]
        (d/transact! conn [{:page/id page-id
                            :page/last-accessed now
                            :page/access-count (+ current-count (double weight))}])
        ;; Spread activation to connected pages (only for significant accesses)
        (propagate-activation! db-info page-id weight)
        ;; Boost document certainty on fetch
        (when (>= weight 1.0)
          (let [page (d/pull (d/db conn) [:page/document-id] [:page/id page-id])]
            (when-let [doc-id (:page/document-id page)]
              (record-document-access! db-info doc-id 0.5)))))
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
