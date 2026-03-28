(ns com.blockether.svar.internal.rlm.db
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [com.blockether.svar.internal.rlm.schema :refer [RLM_SCHEMA DECAY_MIN_VOTES DECAY_THRESHOLD]]
   [com.blockether.svar.internal.tokens :as tokens]
   [datalevin.core :as d]
   [com.blockether.svar.internal.util :as util]
   [taoensso.trove :as trove]))

(declare db-list-page-nodes)
(declare db-list-toc-entries)
(declare db-list-entities)
(declare normalize-learning)

(defn str-truncate [s n] (when s (if (> (count s) n) (subs s 0 n) s)))

(defn str-lower [s] (when s (str/lower-case s)))

(defn str-includes? [s substr] (when s (str/includes? s substr)))

(defn create-rlm-conn
  "Creates or wraps a Datalevin connection for RLM.

   - conn: external connection (unified DB). Svar will NOT close it on dispose.
     Caller must ensure RLM_SCHEMA is merged into the external DB schema.
   - path: persistent DB at given path. Svar owns and closes it.
   - neither: temp DB (deleted on dispose).

   For unified storage, pass :conn AND :hooks callbacks to create-env."
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

(declare get-recent-messages)
(declare db-list-page-nodes)
(declare db-list-toc-entries)
(declare db-list-entities)

;; =============================================================================
;; Message History (Datalevin-backed)
;; =============================================================================

(defn store-message!
  "Stores a conversation message.

   Params:
   `db-info` - Map with :conn key.
   `role` - Keyword. :user, :assistant, or :system.
   `content` - String. Clean displayable content (user query text or final answer).
   `opts` - Map, optional:
     - :iteration - Integer. Which iteration this message is from.
     - :thinking - String. Reasoning/thinking content (native or spec-parsed).
     - :tokens - Integer. Pre-computed token count (computed if not provided).
     - :model - String. Model for token counting (default: gpt-4o).
     - :env-id - String. RLM environment ID (distinguishes parent vs sub-RLM).

   Returns:
   Map with :id, :role, :content, :tokens, :timestamp, :thinking."
  ([db-info role content]
   (store-message! db-info role content {}))
  ([{:keys [conn]} role content {:keys [iteration thinking tokens model env-id]}]
   (when (and conn (not (str/blank? content)))
     (let [msg-id (util/uuid)
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
                 env-id (assoc :message/env-id env-id)
                 (not (str/blank? thinking)) (assoc :message/thinking thinking))]
       (d/transact! conn [msg])
       {:id msg-id :role role :content content :tokens token-count :timestamp timestamp
        :thinking thinking}))))

(defn store-executions!
  "Stores ordered execution entities linked to a message.

   Params:
   `db-info` - Map with :conn key.
   `msg-id` - UUID. The parent message's :message/id.
   `executions` - Vector of execution maps from run-iteration, each with:
     :id (order), :code, :result, :stdout, :stderr, :error, :execution-time-ms

   Returns:
   Vector of execution UUIDs."
  [{:keys [conn]} msg-id executions]
  (when (and conn (seq executions))
    (let [;; Look up the message entity db-id by its :message/id UUID
          msg-eid (d/q '[:find ?e .
                         :in $ ?mid
                         :where [?e :message/id ?mid]]
                       (d/db conn) msg-id)
          entities (mapv (fn [{:keys [id code result stdout stderr error execution-time-ms]}]
                           (cond-> {:execution/id (util/uuid)
                                    :execution/order (long id)
                                    :execution/code (or code "")}
                             msg-eid       (assoc :execution/message msg-eid)
                             result        (assoc :execution/result-edn (pr-str result))
                             (seq stdout)  (assoc :execution/stdout stdout)
                             (seq stderr)  (assoc :execution/stderr stderr)
                             error         (assoc :execution/error error)
                             execution-time-ms (assoc :execution/time-ms (long execution-time-ms))))
                         executions)]
      (d/transact! conn entities)
      (mapv :execution/id entities))))

(defn store-tool-call!
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
    (let [call-id (util/uuid)
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

(defn format-execution
  "Converts a raw Datalevin execution entity to a clean map."
  [e]
  (cond-> {:id (:execution/id e)
           :order (:execution/order e)
           :code (:execution/code e)}
    (:execution/result-edn e) (assoc :result-edn (:execution/result-edn e))
    (:execution/stdout e)     (assoc :stdout (:execution/stdout e))
    (:execution/stderr e)     (assoc :stderr (:execution/stderr e))
    (:execution/error e)      (assoc :error (:execution/error e))
    (:execution/time-ms e)    (assoc :time-ms (:execution/time-ms e))))

(defn get-recent-messages
  "Gets the most recent messages by timestamp, with executions embedded.

   Params:
   `db-info` - Map with :conn key.
   `limit` - Integer. Maximum messages to return.

   Returns:
   Vector of full message maps sorted most-recent first, each with:
   :id, :content, :thinking, :role, :tokens, :timestamp, :iteration, :executions.
   :executions is a vector of ordered execution maps (empty for non-assistant messages)."
  [{:keys [conn]} limit]
  (when conn
    (->> (d/q '[:find [(pull ?e [:message/id :message/content :message/thinking
                                 :message/role :message/tokens :message/timestamp
                                 :message/iteration
                                 {:execution/_message [:execution/id :execution/order :execution/code
                                                       :execution/result-edn :execution/stdout
                                                       :execution/stderr :execution/error
                                                       :execution/time-ms]}]) ...]
                :where [?e :message/id _]]
              (d/db conn))
         (sort-by :message/timestamp #(compare %2 %1))
         (take limit)
         (mapv (fn [m]
                 (let [raw-execs (:execution/_message m)
                       execs (when (seq raw-execs)
                               (->> raw-execs
                                    (sort-by :execution/order)
                                    (mapv format-execution)))]
                   (cond-> {:id (:message/id m)
                            :content (:message/content m)
                            :role (:message/role m)
                            :tokens (:message/tokens m)
                            :timestamp (:message/timestamp m)
                            :iteration (:message/iteration m)
                            :executions (or execs [])}
                     (:message/thinking m) (assoc :thinking (:message/thinking m)))))))))

(defn get-message-executions
  "Gets ordered executions for a message by its UUID.

   Params:
   `db-info` - Map with :conn key.
   `msg-id` - UUID. The message's :message/id.

   Returns:
   Vector of execution maps sorted by :order, each with:
   :id, :order, :code, :result-edn, :stdout, :stderr, :error, :time-ms."
  [{:keys [conn]} msg-id]
  (when conn
    (let [msg-eid (d/q '[:find ?e .
                         :in $ ?mid
                         :where [?e :message/id ?mid]]
                       (d/db conn) msg-id)]
      (when msg-eid
        (->> (d/q '[:find [(pull ?e [:execution/id :execution/order :execution/code
                                     :execution/result-edn :execution/stdout :execution/stderr
                                     :execution/error :execution/time-ms]) ...]
                    :in $ ?msg
                    :where [?e :execution/message ?msg]]
                  (d/db conn) msg-eid)
             (sort-by :execution/order)
             (mapv (fn [e]
                     (cond-> {:id (:execution/id e)
                              :order (:execution/order e)
                              :code (:execution/code e)}
                       (:execution/result-edn e) (assoc :result-edn (:execution/result-edn e))
                       (:execution/stdout e) (assoc :stdout (:execution/stdout e))
                       (:execution/stderr e) (assoc :stderr (:execution/stderr e))
                       (:execution/error e) (assoc :error (:execution/error e))
                       (:execution/time-ms e) (assoc :time-ms (:execution/time-ms e))))))))))

(defn count-history-tokens
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

(defn db-upsert-tag!
  "Creates or updates a learning tag with a definition. Upserts by name.
   Only sets :created-at on first creation, preserves it on updates.

   Params:
   `db-info` - Map with :conn key.
   `tag-name` - String. Tag name (lowercased, trimmed).
   `definition` - String or nil. What this tag means.

   Returns:
   Map with :learning-tag/name and :learning-tag/definition."
  [{:keys [conn]} tag-name definition]
  (when (and conn (not (str/blank? tag-name)))
    (let [clean-name (str/lower-case (str/trim tag-name))
          existing (d/pull (d/db conn) '[:learning-tag/name] [:learning-tag/name clean-name])
          entity (cond-> {:learning-tag/name clean-name}
                   ;; Only set created-at on first creation
                   (not (:learning-tag/name existing)) (assoc :learning-tag/created-at (java.util.Date.))
                   definition (assoc :learning-tag/definition definition))]
      (d/transact! conn [entity])
      {:learning-tag/name clean-name
       :learning-tag/definition definition})))

(defn- db-ensure-tags!
  "Ensures all tag names exist in the DB. Creates missing ones in a single transaction.

   Params:
   `db-info` - Map with :conn key.
   `tag-names` - Collection of strings."
  [{:keys [conn]} tag-names]
  (when (and conn (seq tag-names))
    (let [clean-names (->> tag-names
                           (map #(str/lower-case (str/trim (str %))))
                           (remove str/blank?)
                           distinct)
          existing (->> (d/q '[:find [?n ...]
                               :where [?e :learning-tag/name ?n]]
                             (d/db conn))
                        set)
          new-tags (->> clean-names
                        (remove existing)
                        (mapv (fn [n] {:learning-tag/name n
                                       :learning-tag/created-at (java.util.Date.)})))]
      (when (seq new-tags)
        (d/transact! conn new-tags)))))

(defn db-list-tags
  "Lists all learning tags with definitions.

   Params:
   `db-info` - Map with :conn key.

   Returns:
   Vector of maps with :name and :definition, sorted by name."
  [{:keys [conn]}]
  (when conn
    (->> (d/q '[:find [(pull ?e [:learning-tag/name :learning-tag/definition]) ...]
                :where [?e :learning-tag/name _]]
              (d/db conn))
         (mapv (fn [t] {:name (:learning-tag/name t)
                        :definition (:learning-tag/definition t)}))
         (sort-by :name)
         vec)))

;; Forward declarations for dedup check
(declare scan-learnings)

;; -----------------------------------------------------------------------------
;; Learning Deduplication
;; -----------------------------------------------------------------------------

(defn- find-duplicate-learning
  "Checks if a semantically similar learning already exists.
   Returns the first matching learning entity, or nil.
   Uses case-insensitive substring containment in both directions."
  [conn insight]
  (let [insight-lower (str/lower-case (str/trim insight))]
    (->> (try (scan-learnings conn insight)
              (catch Exception _ []))
         (filter (fn [l]
                   (let [existing-lower (str/lower-case (str (:learning/insight l)))]
                     (or (= existing-lower insight-lower)
                         (str/includes? existing-lower insight-lower)
                         (str/includes? insight-lower existing-lower)))))
         first)))

(defn- create-learning-entity!
  "Creates a new learning entity in the DB. Returns the stored learning map."
  [{:keys [conn] :as db-info} insight {:keys [context tags scope source]}]
  (let [clean-tags (when (seq tags)
                     (->> tags (map #(str/lower-case (str/trim (str %)))) (remove str/blank?) vec))
        _ (when (seq clean-tags) (db-ensure-tags! db-info clean-tags))
        learning-id (util/uuid)
        timestamp (java.util.Date.)
        learning (cond-> {:learning/id learning-id
                          :learning/insight insight
                          :learning/timestamp timestamp
                          :learning/useful-count 0
                          :learning/not-useful-count 0
                          :learning/applied-count 0
                          :learning/source (or source :manual)}
                   context (assoc :learning/context context)
                   scope (assoc :learning/scope scope)
                   (seq clean-tags) (assoc :learning/tags clean-tags))]
    (d/transact! conn [learning])
    {:learning/id learning-id
     :learning/insight insight
     :learning/context context
     :learning/tags (or clean-tags [])
     :learning/scope scope
     :learning/timestamp timestamp}))

(defn- existing-learning->result
  "Converts an existing learning entity to a store result with :duplicate? true."
  [entity]
  {:learning/id (:learning/id entity)
   :learning/insight (:learning/insight entity)
   :learning/context (:learning/context entity)
   :learning/tags (or (:learning/tags entity) [])
   :learning/scope (:learning/scope entity)
   :learning/timestamp (:learning/timestamp entity)
   :duplicate? true})

;; -----------------------------------------------------------------------------
;; Learning CRUD
;; -----------------------------------------------------------------------------

(defn db-store-learning!
  "Stores a meta-insight/learning for future retrieval.
   Auto-creates tag entities for any tags that don't exist yet.

   Params:
   `db-info` - Map with :conn key.
   `insight` - String. The learning/insight to store.
   `opts` - Map, optional:
     - :context - String. Task/domain context.
     - :tags - Vector of strings. Categorization tags.
     - :scope - String. Glob pattern to scope this learning (e.g. \"*.pdf\", \"contracts/*\"). Nil = global.
     - :source - Keyword. :manual (LLM stored it) or :auto (auto-extracted). Default :manual.

   Returns:
   Map with :learning/id, :learning/insight, :learning/context, :learning/tags, :learning/scope, :learning/timestamp."
  ([db-info insight] (db-store-learning! db-info insight {}))
  ([{:keys [conn] :as db-info} insight {:keys [context tags scope source] :as opts}]
   (when conn
     (if-let [existing (find-duplicate-learning conn insight)]
       (existing-learning->result existing)
       (create-learning-entity! db-info insight opts)))))

(defn learning-decayed?
  "Returns true if a learning has decayed (>70% negative votes after 5+ total votes)."
  [useful-count not-useful-count]
  (let [total (+ (or useful-count 0) (or not-useful-count 0))]
    (and (>= total DECAY_MIN_VOTES)
         (> (/ (or not-useful-count 0) total) DECAY_THRESHOLD))))

(defn db-vote-learning!
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

(defn db-increment-applied-count!
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

(def ^:private learning-pull-pattern
  '[:learning/id :learning/insight :learning/context :learning/tags :learning/scope :learning/source
    :learning/timestamp :learning/useful-count :learning/not-useful-count :learning/applied-count])

(def ^:private glob-regex-cache
  "Cache compiled regexes for glob patterns. Avoids recompilation on every match."
  (atom {}))

(defn- glob->regex
  "Compiles a glob pattern to a regex, with memoization."
  [pattern]
  (if-let [cached (get @glob-regex-cache pattern)]
    cached
    (let [regex-str (-> pattern
                        (str/replace "." "\\.")
                        (str/replace "*" ".*")
                        (str/replace "?" "."))
          regex (re-pattern (str "(?i)^" regex-str "$"))]
      (swap! glob-regex-cache assoc pattern regex)
      regex)))

(defn- glob-matches?
  "Returns true if `name` matches the glob `pattern`.
   Supports * (any chars) and ? (single char). Nil pattern matches everything."
  [pattern name]
  (if (nil? pattern)
    true
    (boolean (re-matches (glob->regex pattern) (str name)))))

(defn- scope-matches-documents?
  "Returns true if a learning's scope matches any of the given document names/extensions.
   Nil scope = global, always matches."
  [scope doc-names]
  (or (nil? scope)
      (empty? doc-names)
      (some #(glob-matches? scope %) doc-names)))

(defn- fulltext-learnings
  "Search learnings via Datalevin fulltext index."
  [conn query]
  (d/q `[:find [(~'pull ~'?e ~learning-pull-pattern) ...]
         :in ~'$ ~'?q
         :where [(~'fulltext ~'$ ~'?q) [[~'?e]]]]
       (d/db conn) query))

(defn- scan-learnings
  "Search learnings via in-memory substring scan (fallback)."
  [conn query]
  (let [q (str-lower query)
        all (d/q `[:find [(~'pull ~'?e ~learning-pull-pattern) ...]
                   :where [~'?e :learning/id ~'_]]
                 (d/db conn))]
    (filter (fn [l]
              (or (str-includes? (str-lower (str (:learning/insight l))) q)
                  (str-includes? (str-lower (str (:learning/context l))) q)))
            all)))

(defn- all-learnings
  "Get recent learnings from DB (capped at 500 to prevent full table scan)."
  [conn]
  (->> (d/q `[:find [(~'pull ~'?e ~learning-pull-pattern) ...]
              :where [~'?e :learning/id ~'_]]
            (d/db conn))
       (sort-by :learning/timestamp #(compare %2 %1))
       (take 500)
       vec))

(defn- search-or-list-learnings
  "Search learnings with fulltext, falling back to scan. Nil query lists all."
  [conn query]
  (if query
    (try (fulltext-learnings conn query)
         (catch Exception _ (scan-learnings conn query)))
    (all-learnings conn)))

(defn normalize-learning
  "Normalize a raw learning entity into a clean result map with decay status."
  [l]
  (let [useful (or (:learning/useful-count l) 0)
        not-useful (or (:learning/not-useful-count l) 0)
        raw-tags (:learning/tags l)]
    (cond-> {:learning/id (:learning/id l)
             :insight (:learning/insight l)
             :context (:learning/context l)
             :tags (cond
                     (set? raw-tags) (vec raw-tags)
                     (sequential? raw-tags) (vec raw-tags)
                     (string? raw-tags) [raw-tags]
                     :else [])
             :timestamp (:learning/timestamp l)
             :useful-count useful
             :not-useful-count not-useful
             :applied-count (or (:learning/applied-count l) 0)
             :decayed? (learning-decayed? useful not-useful)}
      (:learning/scope l) (assoc :scope (:learning/scope l))
      (:learning/source l) (assoc :source (:learning/source l)))))

(defn db-get-learnings
  "Searches learnings by insight and context text.

   Filters out decayed learnings (>70% negative votes after 5+ total votes).
   Automatically increments applied-count for returned learnings.
   Sorted by relevance: applied-count (proven useful) + recency.

   Params:
   `db-info` - Map with :conn key.
   `query` - String. Case-insensitive text search over insight and context.
   `opts` - Map, optional:
     - :top-k - Integer. Max learnings to return (default: 5).
     - :include-decayed? - Boolean. Include decayed learnings (default: false).
     - :track-usage? - Boolean. Increment applied-count (default: true).
     - :tags - Vector of strings. Filter to learnings that have ANY of the specified tags (OR logic).
     - :all-tags - Vector of strings. Filter to learnings that have ALL specified tags (AND logic).
     - :doc-names - Vector of strings. Document names/extensions to match against :learning/scope.

   Returns:
   Vector of learning maps."
  ([db-info query] (db-get-learnings db-info query {}))
  ([{:keys [conn] :as db-info} query {:keys [top-k include-decayed? track-usage? tags all-tags doc-names]
                                      :or {top-k 5 include-decayed? false track-usage? true}}]
   (when conn
     (let [q (when-not (str/blank? (str query)) query)
           or-tag-set (when (seq tags) (set (map str tags)))
           and-tag-set (when (seq all-tags) (set (map str all-tags)))
           filtered (->> (search-or-list-learnings conn q)
                         (mapv normalize-learning)
                         (filter #(or include-decayed? (not (:decayed? %))))
                         ;; OR tags: learning has ANY of the specified tags
                         (filter #(or (nil? or-tag-set)
                                      (some or-tag-set (:tags %))))
                         ;; AND tags: learning has ALL of the specified tags
                         (filter #(or (nil? and-tag-set)
                                      (every? (set (:tags %)) and-tag-set)))
                         (filter #(scope-matches-documents? (:scope %) doc-names))
                         ;; Sort by applied-count (proven useful) then recency
                         (sort-by (fn [l] [(- (or (:applied-count l) 0))
                                           (- (or (.getTime (:timestamp l)) 0))])
                                  compare)
                         (take top-k)
                         vec)]
       (when track-usage?
         (doseq [{:keys [learning/id]} filtered]
           (db-increment-applied-count! db-info id)))
       filtered))))

(defn db-learning-stats
  "Gets statistics about stored learnings including voting and tag stats.

   Params:
   `db-info` - Map with :conn key.

   Returns:
   Map with :total-learnings, :active-learnings, :decayed-learnings,
   :with-context, :without-context, :total-votes, :total-applications,
   :all-tags (sorted vector of all unique tags)."
  [{:keys [conn]}]
  (if conn
    (let [all (d/q '[:find [(pull ?e [:learning/id :learning/context :learning/tags
                                      :learning/useful-count
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
          total-applications (reduce + (map #(or (:learning/applied-count %) 0) all))
          all-tags (->> all
                        (mapcat (fn [l]
                                  (let [t (:learning/tags l)]
                                    (cond (set? t) (seq t)
                                          (sequential? t) t
                                          (string? t) [t]
                                          :else nil))))
                        distinct
                        sort
                        vec)]
      {:total-learnings total
       :active-learnings (- total decayed)
       :decayed-learnings decayed
       :with-context with-context
       :without-context (- total with-context)
       :total-votes total-votes
       :total-applications total-applications
       :all-tags all-tags})
    {:total-learnings 0 :active-learnings 0 :decayed-learnings 0
     :with-context 0 :without-context 0 :total-votes 0 :total-applications 0
     :all-tags []}))

;; -----------------------------------------------------------------------------
;; Learnings SCI Functions (DB-backed, for LLM to call during execution)
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

(defn db-get-page
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

(defn db-list-pages
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
    (let [node-id (str page-id "-node-" (or (:page.node/id node) (util/uuid)))
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
