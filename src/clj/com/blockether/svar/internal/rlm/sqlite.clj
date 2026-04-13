(ns com.blockether.svar.internal.rlm.sqlite
  "SQLite store for RLM. Replaces Datalevin.

   Schema: 11 entity-side tables + 1 unified FTS5 search virtual table.
   Driver: org.xerial/sqlite-jdbc, accessed via next.jdbc + HoneySQL.

   Connection lifecycle:
     (open-store db-spec)   → {:datasource ds :path ... :owned? bool :mode ...}
     (close-store store)    → idempotent dispose

   db-spec mirrors the legacy Datalevin API:
     nil              — no DB (returns nil)
     :temp            — ephemeral file under java.io.tmpdir, deleted on close
     \"path/to.db\"   — persistent SQLite file
     {:path \"...\"}  — persistent SQLite file
     {:datasource ds} — caller-owned DataSource (NOT closed on dispose)

   PRAGMAs applied per-connection:
     journal_mode=WAL, synchronous=NORMAL, foreign_keys=ON, busy_timeout=30000."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [com.blockether.svar.internal.util :as util]
   [honey.sql :as sql]
   [honey.sql.helpers :as h]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [taoensso.trove :as trove])
  (:import
   (java.time Instant)
   (java.util Date UUID)
   (javax.sql DataSource)
   (org.sqlite SQLiteConfig SQLiteConfig$JournalMode SQLiteConfig$SynchronousMode)
   (org.sqlite.javax SQLiteConnectionPoolDataSource)))

;; =============================================================================
;; Type coercion at the Datalevin↔SQLite boundary
;; =============================================================================

(defn ->id
  "UUID/string → canonical TEXT id. Nil → nil."
  [v]
  (cond
    (nil? v) nil
    (uuid? v) (str v)
    (string? v) v
    :else (str v)))

(defn ->uuid
  "TEXT id → UUID. Nil → nil."
  ^UUID [v]
  (cond
    (nil? v) nil
    (uuid? v) v
    (string? v) (try (UUID/fromString v) (catch IllegalArgumentException _ nil))
    :else nil))

(defn ->kw
  "Keyword/string → TEXT (no leading colon). Nil → nil."
  [v]
  (cond
    (nil? v) nil
    (keyword? v) (subs (str v) 1)
    :else (str v)))

(defn ->kw-back
  "TEXT → keyword. Nil → nil."
  [v]
  (when (and v (not= "" v))
    (keyword v)))

(defn ->epoch-ms
  "java.util.Date / Instant → epoch-ms long. Nil → nil."
  [v]
  (cond
    (nil? v) nil
    (instance? Date v) (.getTime ^Date v)
    (instance? Instant v) (.toEpochMilli ^Instant v)
    (number? v) (long v)
    :else nil))

(defn ->date
  "epoch-ms long → java.util.Date. Nil → nil."
  ^Date [v]
  (when v (Date. (long v))))

;; =============================================================================
;; DDL — Schema
;; =============================================================================

(def ^:private DDL
  "All CREATE statements. Order matters: parent tables before child tables.
   IF NOT EXISTS makes this idempotent — safe to run on existing dbs."
  ["PRAGMA foreign_keys = ON"

   ;; --- Core unified entity table ---
   "CREATE TABLE IF NOT EXISTS entity (
      id            TEXT PRIMARY KEY NOT NULL,
      type          TEXT NOT NULL,
      name          TEXT,
      description   TEXT,
      parent_id     TEXT,
      document_id   TEXT,
      page          INTEGER,
      section       TEXT,
      canonical_id  TEXT,
      created_at    INTEGER,
      updated_at    INTEGER
    )"
   "CREATE INDEX IF NOT EXISTS idx_entity_type          ON entity(type)"
   "CREATE INDEX IF NOT EXISTS idx_entity_parent        ON entity(parent_id)"
   "CREATE INDEX IF NOT EXISTS idx_entity_document      ON entity(document_id)"
   "CREATE INDEX IF NOT EXISTS idx_entity_canonical     ON entity(canonical_id)"
   "CREATE INDEX IF NOT EXISTS idx_entity_type_doc_page ON entity(type, document_id, page)"
   "CREATE INDEX IF NOT EXISTS idx_entity_created       ON entity(created_at)"

   ;; --- Per-type entity attribute tables ---
   "CREATE TABLE IF NOT EXISTS conversation_attrs (
      entity_id      TEXT PRIMARY KEY NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
      env_id         TEXT UNIQUE,
      name           TEXT UNIQUE,
      system_prompt  TEXT,
      model          TEXT
    )"

   "CREATE TABLE IF NOT EXISTS query_attrs (
      entity_id    TEXT PRIMARY KEY NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
      messages     TEXT,
      text         TEXT,
      answer       TEXT,
      iterations   INTEGER,
      duration_ms  INTEGER,
      status       TEXT,
      eval_score   REAL
    )"

   "CREATE TABLE IF NOT EXISTS iteration_attrs (
      entity_id    TEXT PRIMARY KEY NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
      code         TEXT,
      results      TEXT,
      vars         TEXT,
      answer       TEXT,
      thinking     TEXT,
      duration_ms  INTEGER
    )"

   "CREATE TABLE IF NOT EXISTS iteration_var_attrs (
      entity_id    TEXT PRIMARY KEY NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
      name         TEXT,
      value        TEXT,
      code         TEXT
    )"

   "CREATE TABLE IF NOT EXISTS repo_attrs (
      entity_id         TEXT PRIMARY KEY NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
      name              TEXT UNIQUE NOT NULL,
      path              TEXT,
      head_sha          TEXT,
      head_short        TEXT,
      branch            TEXT,
      commits_ingested  INTEGER,
      ingested_at       INTEGER
    )"

   "CREATE TABLE IF NOT EXISTS commit_attrs (
      entity_id      TEXT PRIMARY KEY NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
      category       TEXT,
      sha            TEXT,
      date           TEXT,
      prefix         TEXT,
      scope          TEXT,
      author_email   TEXT
    )"
   "CREATE INDEX IF NOT EXISTS idx_commit_sha   ON commit_attrs(sha)"
   "CREATE INDEX IF NOT EXISTS idx_commit_date  ON commit_attrs(date)"
   "CREATE INDEX IF NOT EXISTS idx_commit_email ON commit_attrs(author_email)"

   "CREATE TABLE IF NOT EXISTS commit_ticket_ref (
      entity_id    TEXT NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
      ticket       TEXT NOT NULL,
      PRIMARY KEY (entity_id, ticket)
    )"
   "CREATE INDEX IF NOT EXISTS idx_commit_ticket ON commit_ticket_ref(ticket)"

   "CREATE TABLE IF NOT EXISTS commit_file_path (
      entity_id    TEXT NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
      path         TEXT NOT NULL,
      PRIMARY KEY (entity_id, path)
    )"
   "CREATE INDEX IF NOT EXISTS idx_commit_path ON commit_file_path(path)"

   "CREATE TABLE IF NOT EXISTS commit_parent (
      entity_id    TEXT NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
      parent_sha   TEXT NOT NULL,
      PRIMARY KEY (entity_id, parent_sha)
    )"

   "CREATE TABLE IF NOT EXISTS person_attrs (
      entity_id    TEXT PRIMARY KEY NOT NULL REFERENCES entity(id) ON DELETE CASCADE,
      email        TEXT
    )"
   "CREATE INDEX IF NOT EXISTS idx_person_email ON person_attrs(email)"

   ;; --- Non-entity data ---
   "CREATE TABLE IF NOT EXISTS document (
      id                TEXT PRIMARY KEY NOT NULL,
      name              TEXT,
      type              TEXT,
      title             TEXT,
      abstract          TEXT,
      extension         TEXT,
      author            TEXT,
      page_count        INTEGER,
      created_at        INTEGER,
      updated_at        INTEGER,
      certainty_alpha   REAL,
      certainty_beta    REAL
    )"
   "CREATE INDEX IF NOT EXISTS idx_document_type ON document(type)"

   "CREATE TABLE IF NOT EXISTS skill_attrs (
      document_id   TEXT PRIMARY KEY NOT NULL REFERENCES document(id) ON DELETE CASCADE,
      body          TEXT,
      source_path   TEXT,
      agent_config  TEXT,
      requires      TEXT,
      version       TEXT,
      content_hash  TEXT
    )"

   "CREATE TABLE IF NOT EXISTS page (
      id               TEXT PRIMARY KEY NOT NULL,
      document_id      TEXT REFERENCES document(id) ON DELETE CASCADE,
      idx              INTEGER,
      created_at       INTEGER,
      last_accessed    INTEGER,
      access_count     REAL,
      q_value          REAL,
      q_update_count   INTEGER
    )"
   "CREATE INDEX IF NOT EXISTS idx_page_document      ON page(document_id)"
   "CREATE INDEX IF NOT EXISTS idx_page_doc_idx       ON page(document_id, idx)"
   "CREATE INDEX IF NOT EXISTS idx_page_last_accessed ON page(last_accessed)"

   "CREATE TABLE IF NOT EXISTS page_node (
      id               TEXT PRIMARY KEY NOT NULL,
      page_id          TEXT REFERENCES page(id) ON DELETE CASCADE,
      document_id      TEXT,
      local_id         TEXT,
      type             TEXT,
      content          TEXT,
      description      TEXT,
      level            TEXT,
      parent_id        TEXT,
      image_data       BLOB,
      continuation     INTEGER,
      caption          TEXT,
      kind             TEXT,
      bbox             TEXT,
      group_id         TEXT
    )"
   "CREATE INDEX IF NOT EXISTS idx_node_page     ON page_node(page_id)"
   "CREATE INDEX IF NOT EXISTS idx_node_document ON page_node(document_id)"
   "CREATE INDEX IF NOT EXISTS idx_node_type     ON page_node(type)"

   "CREATE TABLE IF NOT EXISTS document_toc (
      id                  TEXT PRIMARY KEY NOT NULL,
      document_id         TEXT REFERENCES document(id) ON DELETE CASCADE,
      type                TEXT,
      title               TEXT,
      description         TEXT,
      target_page         INTEGER,
      target_section_id   TEXT,
      level               TEXT,
      parent_id           TEXT,
      created_at          INTEGER
    )"
   "CREATE INDEX IF NOT EXISTS idx_toc_document ON document_toc(document_id)"

   "CREATE TABLE IF NOT EXISTS page_cooccurrence (
      id           TEXT PRIMARY KEY NOT NULL,
      page_a       TEXT NOT NULL,
      page_b       TEXT NOT NULL,
      strength     REAL,
      last_seen    INTEGER
    )"
   "CREATE INDEX IF NOT EXISTS idx_cooc_a ON page_cooccurrence(page_a)"
   "CREATE INDEX IF NOT EXISTS idx_cooc_b ON page_cooccurrence(page_b)"

   "CREATE TABLE IF NOT EXISTS relationship (
      id                  TEXT PRIMARY KEY NOT NULL,
      type                TEXT NOT NULL,
      source_entity_id    TEXT,
      target_entity_id    TEXT,
      description         TEXT,
      document_id         TEXT
    )"
   "CREATE INDEX IF NOT EXISTS idx_rel_source ON relationship(source_entity_id)"
   "CREATE INDEX IF NOT EXISTS idx_rel_target ON relationship(target_entity_id)"
   "CREATE INDEX IF NOT EXISTS idx_rel_type   ON relationship(type)"

   "CREATE TABLE IF NOT EXISTS claim (
      id                      TEXT PRIMARY KEY NOT NULL,
      text                    TEXT,
      document_id             TEXT,
      page                    INTEGER,
      section                 TEXT,
      quote                   TEXT,
      confidence              REAL,
      query_id                TEXT,
      verified                INTEGER,
      verification_verdict    TEXT,
      created_at              INTEGER
    )"
   "CREATE INDEX IF NOT EXISTS idx_claim_document ON claim(document_id)"
   "CREATE INDEX IF NOT EXISTS idx_claim_query    ON claim(query_id)"

   "CREATE TABLE IF NOT EXISTS raw_document (
      id        TEXT PRIMARY KEY NOT NULL,
      content   TEXT
    )"

   "CREATE TABLE IF NOT EXISTS rlm_meta (
      id                TEXT PRIMARY KEY NOT NULL,
      corpus_revision   INTEGER,
      updated_at        INTEGER
    )"

   ;; =========================================================================
   ;; FTS5 unified search index
   ;; =========================================================================
   ;; One virtual table indexes ALL text columns from ALL source tables.
   ;; Maintained via per-source triggers below. Query via:
   ;;   SELECT owner_table, owner_id, snippet(...), bm25(search) AS rank
   ;;   FROM search WHERE search MATCH ? ORDER BY rank LIMIT ?
   ;;
   ;; UNINDEXED columns don't bloat the FTS index; they're for filtering/joins.
   "CREATE VIRTUAL TABLE IF NOT EXISTS search USING fts5(
      owner_table  UNINDEXED,
      owner_id     UNINDEXED,
      field        UNINDEXED,
      text,
      tokenize='porter unicode61 remove_diacritics 2'
    )"

   ;; --- Triggers: keep `search` in sync with source tables ---
   ;; Pattern per source: AI inserts non-empty fields; AU deletes prior + re-INS;
   ;; AD deletes all rows for that owner_id.
   ;;
   ;; document → indexes title, abstract
   "CREATE TRIGGER IF NOT EXISTS trg_document_ai
    AFTER INSERT ON document BEGIN
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'document', new.id, 'title', new.title WHERE new.title IS NOT NULL AND new.title <> '';
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'document', new.id, 'abstract', new.abstract WHERE new.abstract IS NOT NULL AND new.abstract <> '';
    END"
   "CREATE TRIGGER IF NOT EXISTS trg_document_au
    AFTER UPDATE ON document BEGIN
      DELETE FROM search WHERE owner_table='document' AND owner_id=old.id;
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'document', new.id, 'title', new.title WHERE new.title IS NOT NULL AND new.title <> '';
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'document', new.id, 'abstract', new.abstract WHERE new.abstract IS NOT NULL AND new.abstract <> '';
    END"
   "CREATE TRIGGER IF NOT EXISTS trg_document_ad
    AFTER DELETE ON document BEGIN
      DELETE FROM search WHERE owner_table='document' AND owner_id=old.id;
    END"

   ;; skill_attrs → indexes body
   "CREATE TRIGGER IF NOT EXISTS trg_skill_ai
    AFTER INSERT ON skill_attrs BEGIN
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'skill', new.document_id, 'body', new.body WHERE new.body IS NOT NULL AND new.body <> '';
    END"
   "CREATE TRIGGER IF NOT EXISTS trg_skill_au
    AFTER UPDATE ON skill_attrs BEGIN
      DELETE FROM search WHERE owner_table='skill' AND owner_id=old.document_id;
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'skill', new.document_id, 'body', new.body WHERE new.body IS NOT NULL AND new.body <> '';
    END"
   "CREATE TRIGGER IF NOT EXISTS trg_skill_ad
    AFTER DELETE ON skill_attrs BEGIN
      DELETE FROM search WHERE owner_table='skill' AND owner_id=old.document_id;
    END"

   ;; page_node → indexes content, description
   "CREATE TRIGGER IF NOT EXISTS trg_page_node_ai
    AFTER INSERT ON page_node BEGIN
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'page_node', new.id, 'content', new.content WHERE new.content IS NOT NULL AND new.content <> '';
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'page_node', new.id, 'description', new.description WHERE new.description IS NOT NULL AND new.description <> '';
    END"
   "CREATE TRIGGER IF NOT EXISTS trg_page_node_au
    AFTER UPDATE ON page_node BEGIN
      DELETE FROM search WHERE owner_table='page_node' AND owner_id=old.id;
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'page_node', new.id, 'content', new.content WHERE new.content IS NOT NULL AND new.content <> '';
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'page_node', new.id, 'description', new.description WHERE new.description IS NOT NULL AND new.description <> '';
    END"
   "CREATE TRIGGER IF NOT EXISTS trg_page_node_ad
    AFTER DELETE ON page_node BEGIN
      DELETE FROM search WHERE owner_table='page_node' AND owner_id=old.id;
    END"

   ;; document_toc → indexes title, description
   "CREATE TRIGGER IF NOT EXISTS trg_toc_ai
    AFTER INSERT ON document_toc BEGIN
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'document_toc', new.id, 'title', new.title WHERE new.title IS NOT NULL AND new.title <> '';
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'document_toc', new.id, 'description', new.description WHERE new.description IS NOT NULL AND new.description <> '';
    END"
   "CREATE TRIGGER IF NOT EXISTS trg_toc_au
    AFTER UPDATE ON document_toc BEGIN
      DELETE FROM search WHERE owner_table='document_toc' AND owner_id=old.id;
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'document_toc', new.id, 'title', new.title WHERE new.title IS NOT NULL AND new.title <> '';
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'document_toc', new.id, 'description', new.description WHERE new.description IS NOT NULL AND new.description <> '';
    END"
   "CREATE TRIGGER IF NOT EXISTS trg_toc_ad
    AFTER DELETE ON document_toc BEGIN
      DELETE FROM search WHERE owner_table='document_toc' AND owner_id=old.id;
    END"

   ;; entity → indexes name, description
   "CREATE TRIGGER IF NOT EXISTS trg_entity_ai
    AFTER INSERT ON entity BEGIN
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'entity', new.id, 'name', new.name WHERE new.name IS NOT NULL AND new.name <> '';
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'entity', new.id, 'description', new.description WHERE new.description IS NOT NULL AND new.description <> '';
    END"
   "CREATE TRIGGER IF NOT EXISTS trg_entity_au
    AFTER UPDATE ON entity BEGIN
      DELETE FROM search WHERE owner_table='entity' AND owner_id=old.id;
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'entity', new.id, 'name', new.name WHERE new.name IS NOT NULL AND new.name <> '';
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'entity', new.id, 'description', new.description WHERE new.description IS NOT NULL AND new.description <> '';
    END"
   "CREATE TRIGGER IF NOT EXISTS trg_entity_ad
    AFTER DELETE ON entity BEGIN
      DELETE FROM search WHERE owner_table='entity' AND owner_id=old.id;
    END"

   ;; query_attrs → indexes text
   "CREATE TRIGGER IF NOT EXISTS trg_query_ai
    AFTER INSERT ON query_attrs BEGIN
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'query', new.entity_id, 'text', new.text WHERE new.text IS NOT NULL AND new.text <> '';
    END"
   "CREATE TRIGGER IF NOT EXISTS trg_query_au
    AFTER UPDATE ON query_attrs BEGIN
      DELETE FROM search WHERE owner_table='query' AND owner_id=old.entity_id;
      INSERT INTO search(owner_table, owner_id, field, text)
        SELECT 'query', new.entity_id, 'text', new.text WHERE new.text IS NOT NULL AND new.text <> '';
    END"
   "CREATE TRIGGER IF NOT EXISTS trg_query_ad
    AFTER DELETE ON query_attrs BEGIN
      DELETE FROM search WHERE owner_table='query' AND owner_id=old.entity_id;
    END"])

(defn install-schema!
  "Idempotently installs the RLM schema on `ds`. Returns the datasource."
  [^DataSource ds]
  (with-open [conn (jdbc/get-connection ds)]
    (doseq [stmt DDL]
      (jdbc/execute! conn [stmt])))
  ds)

;; =============================================================================
;; Connection management
;; =============================================================================

(defn- pooled-datasource
  "Builds an SQLiteConnectionPoolDataSource pre-configured with the standard
   PRAGMAs (WAL, synchronous=NORMAL, foreign_keys=ON, busy_timeout=30000)."
  ^DataSource [^String url]
  (let [cfg (doto (SQLiteConfig.)
              (.setJournalMode SQLiteConfig$JournalMode/WAL)
              (.setSynchronous SQLiteConfig$SynchronousMode/NORMAL)
              (.enforceForeignKeys true)
              (.setBusyTimeout 30000))
        ds  (SQLiteConnectionPoolDataSource. cfg)]
    (.setUrl ds url)
    ds))

(defn- url-for-path [^String path] (str "jdbc:sqlite:" path))

(defn open-store
  "Opens or wraps a SQLite store for RLM. Drop-in shape replacement for
   `create-rlm-conn`. See ns docstring for db-spec forms.

   Returns nil when db-spec is nil. Otherwise returns:
     {:datasource ds :path \"...\" :owned? bool :mode :temp|:persistent|:external}"
  [db-spec]
  (cond
    (nil? db-spec)
    nil

    (= :temp db-spec)
    (let [path (str (System/getProperty "java.io.tmpdir") "/rlm-sqlite-" (util/uuid) ".db")
          ds   (pooled-datasource (url-for-path path))]
      (install-schema! ds)
      {:datasource ds :path path :owned? true :mode :temp})

    (string? db-spec)
    (let [ds (pooled-datasource (url-for-path db-spec))]
      (install-schema! ds)
      {:datasource ds :path db-spec :owned? false :mode :persistent})

    (map? db-spec)
    (cond
      (:datasource db-spec)
      (do (install-schema! (:datasource db-spec))
          {:datasource (:datasource db-spec) :path nil :owned? false :mode :external})

      (:path db-spec)
      (let [path (:path db-spec)
            ds   (pooled-datasource (url-for-path path))]
        (install-schema! ds)
        {:datasource ds :path path :owned? false :mode :persistent})

      :else
      (throw (ex-info "Invalid db-spec map — expected :datasource or :path"
               {:type :rlm/invalid-db-spec :db-spec db-spec})))

    :else
    (throw (ex-info "Invalid db-spec — expected nil, :temp, path string, or {:datasource ...}/{:path ...}"
             {:type :rlm/invalid-db-spec :db-spec db-spec}))))

(defn close-store
  "Releases resources for an RLM store. Idempotent.

   Temp DB files are deleted. External datasources are NOT closed.
   Pooled SQLite datasources are GC'd; explicit close not required."
  [store]
  (when store
    (let [{:keys [path mode owned?]} store]
      (when (and (= :temp mode) owned? path (fs/exists? path))
        (try
          (fs/delete-if-exists path)
          ;; SQLite WAL files
          (fs/delete-if-exists (str path "-wal"))
          (fs/delete-if-exists (str path "-shm"))
          (fs/delete-if-exists (str path "-journal"))
          (catch Exception e
            (trove/log! {:level :warn :data {:error (ex-message e)}
                         :msg "Failed to delete temp SQLite DB"})))))))

;; =============================================================================
;; Internal helpers
;; =============================================================================

(defn- ds [db-info] (:datasource db-info))

(defn- now-ms ^long [] (System/currentTimeMillis))

(defn- read-edn-safe
  "Tolerant EDN reader. Empty/nil → fallback. Parse error → fallback (logged)."
  [s fallback]
  (if (or (nil? s) (= "" s))
    fallback
    (try
      (edn/read-string s)
      (catch Exception e
        (trove/log! {:level :debug :id ::read-edn-safe-fallback
                     :data {:error (ex-message e)}
                     :msg "EDN parse failed, returning fallback"})
        fallback))))

(defn- exec!
  "Run a HoneySQL map (or [sql & params] vec) against the datasource.
   Returns affected rows."
  [db-info q]
  (let [stmt (if (map? q) (sql/format q) q)]
    (jdbc/execute! (ds db-info) stmt {:builder-fn rs/as-unqualified-lower-maps})))

(defn- query!
  "Run a HoneySQL map and return rows with unqualified lower-case keys."
  [db-info q]
  (let [stmt (if (map? q) (sql/format q) q)]
    (jdbc/execute! (ds db-info) stmt {:builder-fn rs/as-unqualified-lower-maps})))

(defn- query-one!
  "Run a HoneySQL map and return the first row (or nil)."
  [db-info q]
  (first (query! db-info q)))

(defn- entity-ref->id
  "Lookup ref [:entity/id uuid] → string TEXT id. Tolerant of bare UUID/string."
  [ref]
  (cond
    (nil? ref) nil
    (and (vector? ref) (= :entity/id (first ref))) (->id (second ref))
    (uuid? ref) (->id ref)
    (string? ref) ref
    :else nil))

(defn- id->entity-ref
  "string TEXT id → [:entity/id uuid] lookup ref."
  [id]
  (when id [:entity/id (->uuid id)]))

;; =============================================================================
;; Type-keyword ↔ extension-table mapping
;; =============================================================================

(def ^:private TYPE->EXT-TABLE
  "Maps :entity/type values (keyword) to their extension attrs table.
   Types not in this map have no extension table — only entity row exists."
  {:conversation   :conversation_attrs
   :query          :query_attrs
   :iteration      :iteration_attrs
   :iteration-var  :iteration_var_attrs
   :event          :commit_attrs       ;; git commit
   :repo           :repo_attrs
   :person         :person_attrs})

;; Per-type column lists (used for projection on read; SELECT *)
;; These mirror the SQL column names (snake_case). Conversion to namespaced
;; keys happens in row->entity below.

(def ^:private CONVERSATION-COLS [:env_id :name :system_prompt :model])
(def ^:private QUERY-COLS        [:messages :text :answer :iterations :duration_ms :status :eval_score])
(def ^:private ITERATION-COLS    [:code :results :vars :answer :thinking :duration_ms])
(def ^:private ITER-VAR-COLS     [:name :value :code])
(def ^:private REPO-COLS         [:name :path :head_sha :head_short :branch :commits_ingested :ingested_at])
(def ^:private COMMIT-COLS       [:category :sha :date :prefix :scope :author_email])
(def ^:private PERSON-COLS       [:email])

(defn- ext-cols-for [type-kw]
  (case type-kw
    :conversation  CONVERSATION-COLS
    :query         QUERY-COLS
    :iteration     ITERATION-COLS
    :iteration-var ITER-VAR-COLS
    :repo          REPO-COLS
    :event         COMMIT-COLS
    :person        PERSON-COLS
    nil))

;; =============================================================================
;; Row → entity map projection
;; =============================================================================
;;
;; SQL gives us flat rows with snake_case keys; legacy datalevin code expected
;; namespaced keys per attribute group (:entity/id, :conversation/env-id, etc.).
;; We rebuild that shape here so callers don't notice the storage swap.

(defn- entity-base
  "Project the core entity columns from a flat row into namespaced keys."
  [row]
  (let [type-kw (->kw-back (:type row))]
    (cond-> {:entity/id          (->uuid (:id row))
             :entity/type        type-kw}
      (some? (:name row))         (assoc :entity/name (:name row))
      (some? (:description row))  (assoc :entity/description (:description row))
      (some? (:parent_id row))    (assoc :entity/parent-id (->uuid (:parent_id row)))
      (some? (:document_id row))  (assoc :entity/document-id (:document_id row))
      (some? (:page row))         (assoc :entity/page (:page row))
      (some? (:section row))      (assoc :entity/section (:section row))
      (some? (:canonical_id row)) (assoc :entity/canonical-id (->uuid (:canonical_id row)))
      (some? (:created_at row))   (assoc :entity/created-at (->date (:created_at row)))
      (some? (:updated_at row))   (assoc :entity/updated-at (->date (:updated_at row))))))

(defn- conversation-attrs->ns
  [row]
  (cond-> {}
    (some? (:env_id row))        (assoc :conversation/env-id (:env_id row))
    (some? (:name row))          (assoc :conversation/name (:name row))
    (some? (:system_prompt row)) (assoc :conversation/system-prompt (:system_prompt row))
    (some? (:model row))         (assoc :conversation/model (:model row))))

(defn- query-attrs->ns
  [row]
  (cond-> {}
    (some? (:messages row))    (assoc :query/messages (:messages row))
    (some? (:text row))        (assoc :query/text (:text row))
    (some? (:answer row))      (assoc :query/answer (:answer row))
    (some? (:iterations row))  (assoc :query/iterations (:iterations row))
    (some? (:duration_ms row)) (assoc :query/duration-ms (:duration_ms row))
    (some? (:status row))      (assoc :query/status (->kw-back (:status row)))
    (some? (:eval_score row))  (assoc :query/eval-score (float (:eval_score row)))))

(defn- iteration-attrs->ns
  [row]
  (cond-> {}
    (some? (:code row))        (assoc :iteration/code (:code row))
    (some? (:results row))     (assoc :iteration/results (:results row))
    (some? (:vars row))        (assoc :iteration/vars (:vars row))
    (some? (:answer row))      (assoc :iteration/answer (:answer row))
    (some? (:thinking row))    (assoc :iteration/thinking (:thinking row))
    (some? (:duration_ms row)) (assoc :iteration/duration-ms (:duration_ms row))))

(defn- iteration-var-attrs->ns
  [row]
  (cond-> {}
    (some? (:name row))  (assoc :iteration.var/name (:name row))
    (some? (:value row)) (assoc :iteration.var/value (:value row))
    (some? (:code row))  (assoc :iteration.var/code (:code row))))

(defn- ext-attrs->ns [type-kw row]
  (case type-kw
    :conversation  (conversation-attrs->ns row)
    :query         (query-attrs->ns row)
    :iteration     (iteration-attrs->ns row)
    :iteration-var (iteration-var-attrs->ns row)
    {}))

(defn- fetch-entity
  "Pull a single entity by string TEXT id, joined with its per-type ext attrs.
   Returns the legacy namespaced map or nil. Two queries (entity, then ext)
   to avoid SELECT * ambiguity across joined tables."
  [db-info entity-id]
  (when entity-id
    (when-let [base (query-one! db-info
                      {:select [:*] :from :entity
                       :where [:= :id entity-id]})]
      (let [type-kw (->kw-back (:type base))
            ext-tbl (TYPE->EXT-TABLE type-kw)
            ext-row (when ext-tbl
                      (query-one! db-info
                        {:select (ext-cols-for type-kw) :from ext-tbl
                         :where [:= :entity_id entity-id]}))]
        (merge (entity-base base)
               (when ext-row (ext-attrs->ns type-kw ext-row)))))))

(defn- fetch-entities
  "Pull multiple entities by string TEXT ids, joined with per-type ext attrs.
   Single query per type — used to materialize lists efficiently."
  [db-info entity-ids]
  (when (seq entity-ids)
    (let [bases (query! db-info
                  {:select [:*] :from :entity
                   :where [:in :id entity-ids]})
          by-type (group-by #(->kw-back (:type %)) bases)]
      (vec
        (mapcat (fn [[type-kw rows]]
                  (let [ids (mapv :id rows)
                        ext-tbl (TYPE->EXT-TABLE type-kw)
                        ext-rows (when (and ext-tbl (seq ids))
                                   (query! db-info
                                     {:select (cons :entity_id (ext-cols-for type-kw))
                                      :from ext-tbl
                                      :where [:in :entity_id ids]}))
                        ext-by-id (into {} (map (fn [r] [(:entity_id r) r])) ext-rows)]
                    (mapv (fn [base]
                            (merge (entity-base base)
                                   (when-let [ext (get ext-by-id (:id base))]
                                     (ext-attrs->ns type-kw ext))))
                      rows)))
          by-type)))))

;; =============================================================================
;; Entity store / update — generic
;; =============================================================================

(defn- split-entity-attrs
  "Splits a legacy-style attrs map (with :entity/* and :conversation/* etc.)
   into {:entity-cols {col v} :ext-cols {col v}}.
   Drops keys that don't match any known column."
  [attrs]
  (let [type-kw (some-> attrs :entity/type)
        out (atom {:entity-cols {} :ext-cols {}})
        e   (fn [col v] (swap! out assoc-in [:entity-cols col] v))
        x   (fn [col v] (swap! out assoc-in [:ext-cols col] v))]
    (doseq [[k v] attrs]
      (case k
        :entity/id          (e :id (->id v))
        :entity/type        (e :type (->kw v))
        :entity/name        (e :name v)
        :entity/description (e :description v)
        :entity/parent-id   (e :parent_id (->id v))
        :entity/document-id (e :document_id v)
        :entity/page        (e :page v)
        :entity/section     (e :section v)
        :entity/canonical-id (e :canonical_id (->id v))
        :entity/created-at  (e :created_at (->epoch-ms v))
        :entity/updated-at  (e :updated_at (->epoch-ms v))

        :conversation/env-id        (x :env_id v)
        :conversation/name          (x :name v)
        :conversation/system-prompt (x :system_prompt v)
        :conversation/model         (x :model v)

        :query/messages    (x :messages v)
        :query/text        (x :text v)
        :query/answer      (x :answer v)
        :query/iterations  (x :iterations v)
        :query/duration-ms (x :duration_ms v)
        :query/status      (x :status (->kw v))
        :query/eval-score  (x :eval_score (when v (double v)))

        :iteration/code        (x :code v)
        :iteration/results     (x :results v)
        :iteration/vars        (x :vars v)
        :iteration/answer      (x :answer v)
        :iteration/thinking    (x :thinking v)
        :iteration/duration-ms (x :duration_ms v)

        :iteration.var/name  (x :name v)
        :iteration.var/value (x :value v)
        :iteration.var/code  (x :code v)

        ;; silently drop unknown keys — preserves Datalevin's open-attr behavior
        nil))
    (assoc @out :type type-kw)))

(defn- upsert-entity-row!
  "Insert-or-replace an entity row. Returns the string id."
  [db-info entity-cols]
  (let [{:keys [id]} entity-cols]
    (jdbc/execute! (ds db-info)
      (sql/format
        {:insert-into :entity
         :values [entity-cols]
         :on-conflict [:id]
         :do-update-set (vec (remove #{:id} (keys entity-cols)))}))
    id))

(defn- upsert-ext-row!
  "Insert-or-replace a per-type ext row. No-op when ext-cols is empty."
  [db-info type-kw entity-id ext-cols]
  (when-let [tbl (and (seq ext-cols) (TYPE->EXT-TABLE type-kw))]
    (let [pk-col (if (= type-kw :repo) :entity_id :entity_id)
          row (assoc ext-cols pk-col entity-id)]
      (jdbc/execute! (ds db-info)
        (sql/format
          {:insert-into tbl
           :values [row]
           :on-conflict [pk-col]
           :do-update-set (vec (remove #{pk-col} (keys row)))})))))

(defn store-entity!
  "Stores an entity. Mirrors db.clj/store-entity!.

   Generates an :entity/id UUID if absent. Stamps :entity/created-at and
   :entity/updated-at when missing. Returns the lookup ref [:entity/id uuid].

   When db-info has no :datasource (legacy nil-db case), returns nil and
   performs no work — matches the old behavior."
  [db-info attrs]
  (when (ds db-info)
    (let [id (or (:entity/id attrs) (UUID/randomUUID))
          now (Date.)
          attrs+ (cond-> (assoc attrs
                          :entity/id id
                          :entity/type (or (:entity/type attrs)
                                         (throw (ex-info "store-entity! requires :entity/type"
                                                  {:type :rlm/missing-entity-type :attrs attrs}))))
                   (not (:entity/created-at attrs)) (assoc :entity/created-at now)
                   (not (:entity/updated-at attrs)) (assoc :entity/updated-at now))
          {:keys [entity-cols ext-cols type]} (split-entity-attrs attrs+)]
      (jdbc/with-transaction [tx (ds db-info)]
        (let [tx-info {:datasource tx}]
          (upsert-entity-row! tx-info entity-cols)
          (upsert-ext-row!    tx-info type (->id id) ext-cols)))
      [:entity/id id])))

(defn update-entity!
  "Merges attrs onto an existing entity by lookup ref. Stamps :entity/updated-at."
  [db-info entity-ref attrs]
  (when (ds db-info)
    (let [id (entity-ref->id entity-ref)
          base-row (when id (query-one! db-info {:select [:type] :from :entity :where [:= :id id]}))
          existing-type (->kw-back (:type base-row))
          merged (cond-> attrs
                   (and existing-type (not (:entity/type attrs))) (assoc :entity/type existing-type)
                   (not (:entity/updated-at attrs))               (assoc :entity/updated-at (Date.))
                   true                                           (assoc :entity/id (->uuid id)))
          {:keys [entity-cols ext-cols type]} (split-entity-attrs merged)]
      (when id
        (jdbc/with-transaction [tx (ds db-info)]
          (let [tx-info {:datasource tx}]
            (when (seq entity-cols)
              (upsert-entity-row! tx-info entity-cols))
            (upsert-ext-row! tx-info (or type existing-type) id ext-cols)))))))

;; =============================================================================
;; Conversation
;; =============================================================================

(defn store-conversation!
  "Stores or retrieves a conversation entity for an env session.

   When :name is supplied, it's stored as :conversation/name (UNIQUE) so
   subsequent calls can look up the conversation in shared-DB scenarios."
  [db-info {:keys [env-id system-prompt model name]}]
  (when (ds db-info)
    (let [existing (query-one! db-info
                     {:select [:entity_id]
                      :from :conversation_attrs
                      :where [:= :env_id env-id]})]
      (if existing
        [:entity/id (->uuid (:entity_id existing))]
        (store-entity! db-info
          (cond-> {:entity/type :conversation
                   :entity/name (or name env-id "session")
                   :conversation/env-id env-id
                   :conversation/system-prompt (or system-prompt "")
                   :conversation/model (or model "")}
            (string? name) (assoc :conversation/name name)))))))

(defn db-get-conversation
  "Returns a conversation entity by lookup ref or nil."
  [db-info conversation-ref]
  (when (and (ds db-info) (vector? conversation-ref))
    (fetch-entity db-info (entity-ref->id conversation-ref))))

(defn db-find-latest-conversation-ref
  "Returns lookup ref for the most recently created conversation, or nil."
  [db-info]
  (when (ds db-info)
    (when-let [row (query-one! db-info
                     {:select [:id]
                      :from :entity
                      :where [:= :type "conversation"]
                      :order-by [[:created_at :desc] [:id :desc]]
                      :limit 1})]
      (id->entity-ref (:id row)))))

(defn db-find-named-conversation-ref
  "Returns lookup ref for a conversation with the given :conversation/name, or nil."
  [db-info nm]
  (when (and (ds db-info) (string? nm))
    (when-let [row (query-one! db-info
                     {:select [:entity_id]
                      :from :conversation_attrs
                      :where [:= :name nm]})]
      (id->entity-ref (:entity_id row)))))

(defn db-resolve-conversation-ref
  "Resolves a conversation selector to a lookup ref. Mirrors db.clj signature."
  [db-info selector]
  (cond
    (nil? selector) nil
    (= :latest selector) (db-find-latest-conversation-ref db-info)
    (and (vector? selector) (= :entity/id (first selector))) selector
    (uuid? selector) [:entity/id selector]
    (and (map? selector) (string? (:name selector)))
    (db-find-named-conversation-ref db-info (:name selector))
    :else nil))

;; =============================================================================
;; Query
;; =============================================================================

(defn store-query!
  "Stores a query entity linked to a conversation via parent-id."
  [db-info {:keys [conversation-ref text messages answer iterations duration-ms status eval-score]}]
  (let [parent-id (when conversation-ref (second conversation-ref))]
    (store-entity! db-info
      (cond-> {:entity/type :query
               :entity/name (let [t (or text "")]
                              (subs t 0 (min (count t) 100)))
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

;; =============================================================================
;; Iteration + iteration-vars
;; =============================================================================

(defn store-iteration!
  "Stores an iteration entity linked to a query via parent-id, plus child
   iteration-var entities for any restorable vars."
  [db-info {:keys [query-ref executions thinking answer duration-ms vars]}]
  (let [parent-id (when query-ref (second query-ref))
        executions (or executions [])
        code-strs (mapv :code executions)
        result-strs (mapv #(try (pr-str (:result %))
                                (catch Exception e
                                  (trove/log! {:level :warn :data {:error (ex-message e)}
                                               :msg "Failed to serialize execution result"})
                                  "???"))
                      executions)
        iter-ref (store-entity! db-info
                   (cond-> {:entity/type :iteration
                            :entity/parent-id parent-id
                            :iteration/code (pr-str code-strs)
                            :iteration/results (pr-str result-strs)
                            :iteration/thinking (or thinking "")
                            :iteration/duration-ms (or duration-ms 0)}
                     answer (assoc :iteration/answer answer)))]
    (doseq [{:keys [name value code]} (or vars [])]
      (when name
        (store-entity! db-info
          {:entity/type :iteration-var
           :entity/name (str name)
           :entity/parent-id (second iter-ref)
           :iteration.var/name (str name)
           :iteration.var/value (pr-str value)
           :iteration.var/code (or code "")})))
    iter-ref))

(defn db-list-iteration-vars
  "Lists persisted restorable vars for an iteration. Returns plain {:name :value :code} maps,
   matching the db.clj contract."
  [db-info iteration-ref]
  (if (and (ds db-info) iteration-ref)
    (let [iter-id (entity-ref->id iteration-ref)
          rows (query! db-info
                 {:select [:e.created_at :v.name :v.value :v.code]
                  :from [[:entity :e]]
                  :join [[:iteration_var_attrs :v] [:= :e.id :v.entity_id]]
                  :where [:and [:= :e.type "iteration-var"]
                          [:= :e.parent_id iter-id]]
                  :order-by [[:e.created_at :asc] [:e.id :asc]]})]
      (mapv (fn [r] {:name (:name r)
                     :value (read-edn-safe (:value r) nil)
                     :code  (:code r)}) rows))
    []))

(defn db-list-conversation-queries
  "Lists query entities for a conversation ordered by created-at."
  [db-info conversation-ref]
  (if (and (ds db-info) conversation-ref)
    (let [conv-id (entity-ref->id conversation-ref)
          ids (mapv :id (query! db-info
                          {:select [:id]
                           :from :entity
                           :where [:and [:= :type "query"]
                                   [:= :parent_id conv-id]]
                           :order-by [[:created_at :asc] [:id :asc]]}))]
      (fetch-entities db-info ids))
    []))

(defn db-list-query-iterations
  "Lists iteration entities for a query ordered by created-at."
  [db-info query-ref]
  (if (and (ds db-info) query-ref)
    (let [q-id (entity-ref->id query-ref)
          ids (mapv :id (query! db-info
                          {:select [:id]
                           :from :entity
                           :where [:and [:= :type "iteration"]
                                   [:= :parent_id q-id]]
                           :order-by [[:created_at :asc] [:id :asc]]}))]
      (fetch-entities db-info ids))
    []))

(defn db-list-final-results
  "Lists terminal iterations (those with non-nil :iteration/answer).
   Optional :conversation-ref scopes to a single conversation's tree."
  ([db-info] (db-list-final-results db-info {}))
  ([db-info {:keys [conversation-ref]}]
   (if (ds db-info)
     (let [conv-id (when conversation-ref (entity-ref->id conversation-ref))
           sql (if conv-id
                 {:select [:e.id]
                  :from [[:entity :e]]
                  :join [[:iteration_attrs :ia] [:= :e.id :ia.entity_id]
                         [:entity :q] [:= :q.id :e.parent_id]]
                  :where [:and
                          [:= :e.type "iteration"]
                          [:not= :ia.answer nil]
                          [:= :q.parent_id conv-id]]
                  :order-by [[:e.created_at :asc] [:e.id :asc]]}
                 {:select [:e.id]
                  :from [[:entity :e]]
                  :join [[:iteration_attrs :ia] [:= :e.id :ia.entity_id]]
                  :where [:and [:= :e.type "iteration"]
                          [:not= :ia.answer nil]]
                  :order-by [[:e.created_at :asc] [:e.id :asc]]})
           ids (mapv :id (query! db-info sql))]
       (fetch-entities db-info ids))
     [])))

;; =============================================================================
;; Corpus revision (rlm_meta)
;; =============================================================================

(def ^:private CORPUS-META-ID "global")

(defn get-corpus-revision
  "Returns current corpus revision (long), defaulting to 0 if uninitialized."
  [db-info]
  (if-not (ds db-info)
    0
    (or (some-> (query-one! db-info {:select [:corpus_revision] :from :rlm_meta
                                     :where [:= :id CORPUS-META-ID]})
          :corpus_revision)
      0)))

(defn bump-corpus-revision!
  "Atomically increments corpus revision. Returns the new revision."
  [db-info]
  (if-not (ds db-info)
    0
    (let [new-rev (inc (long (get-corpus-revision db-info)))]
      (jdbc/execute! (ds db-info)
        (sql/format
          {:insert-into :rlm_meta
           :values [{:id CORPUS-META-ID
                     :corpus_revision new-rev
                     :updated_at (now-ms)}]
           :on-conflict [:id]
           :do-update-set [:corpus_revision :updated_at]}))
      new-rev)))
