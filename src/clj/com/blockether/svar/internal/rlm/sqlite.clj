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

;; =============================================================================
;; Documents
;; =============================================================================

(defn- document-row->ns [row]
  (cond-> {}
    (some? (:id row))                (assoc :document/id (:id row))
    (some? (:name row))              (assoc :document/name (:name row))
    (some? (:type row))              (assoc :document/type (->kw-back (:type row)))
    (some? (:title row))             (assoc :document/title (:title row))
    (some? (:abstract row))          (assoc :document/abstract (:abstract row))
    (some? (:extension row))         (assoc :document/extension (:extension row))
    (some? (:author row))            (assoc :document/author (:author row))
    (some? (:page_count row))        (assoc :document/page-count (:page_count row))
    (some? (:created_at row))        (assoc :document/created-at (->date (:created_at row)))
    (some? (:updated_at row))        (assoc :document/updated-at (->date (:updated_at row)))
    (some? (:certainty_alpha row))   (assoc :document/certainty-alpha (:certainty_alpha row))
    (some? (:certainty_beta row))    (assoc :document/certainty-beta (:certainty_beta row))))

(defn- doc->cols [doc]
  (let [extract (fn [m]
                  (cond-> {}
                    (:document/id m)          (assoc :id (:document/id m))
                    (:document/name m)        (assoc :name (:document/name m))
                    (:document/type m)        (assoc :type (->kw (:document/type m)))
                    (:document/title m)       (assoc :title (:document/title m))
                    (:document/abstract m)    (assoc :abstract (:document/abstract m))
                    (:document/extension m)   (assoc :extension (:document/extension m))
                    (:document/author m)      (assoc :author (:document/author m))
                    (:document/page-count m)  (assoc :page_count (:document/page-count m))
                    (:document/created-at m)  (assoc :created_at (->epoch-ms (:document/created-at m)))
                    (:document/updated-at m)  (assoc :updated_at (->epoch-ms (:document/updated-at m)))
                    (some? (:document/certainty-alpha m)) (assoc :certainty_alpha (double (:document/certainty-alpha m)))
                    (some? (:document/certainty-beta m))  (assoc :certainty_beta (double (:document/certainty-beta m)))))]
    (extract doc)))

(defn store-document!
  "Upsert a document row. Accepts legacy :document/* shape or SQL column shape.
   Returns the stored row as a namespaced map."
  [db-info doc]
  (when (ds db-info)
    (let [cols (doc->cols doc)]
      (when-let [id (:id cols)]
        (jdbc/execute! (ds db-info)
          (sql/format
            {:insert-into :document
             :values [cols]
             :on-conflict [:id]
             :do-update-set (vec (remove #{:id} (keys cols)))}))
        (document-row->ns
          (query-one! db-info {:select [:*] :from :document :where [:= :id id]}))))))

(defn- get-document-toc
  "Ordered TOC summary for a doc-id. Returns vec of {:title :level :page}."
  [db-info doc-id]
  (when (ds db-info)
    (->> (query! db-info
           {:select [:title :level :target_page]
            :from :document_toc
            :where [:= :document_id doc-id]
            :order-by [[:level :asc] [:target_page :asc]]})
      (mapv (fn [r] {:title (:title r) :level (:level r) :page (:target_page r)})))))

(defn db-list-documents
  "Lists documents with optional TOC summaries."
  ([db-info] (db-list-documents db-info {}))
  ([db-info {:keys [limit include-toc?] :or {limit 100 include-toc? true}}]
   (when (ds db-info)
     (let [rows (query! db-info
                  {:select [:id :name :title :extension :abstract]
                   :from :document
                   :limit limit})]
       (mapv (fn [row]
               (cond-> {:document/id (:id row)
                        :document/name (:name row)
                        :document/title (:title row)
                        :document/extension (:extension row)}
                 (and (:abstract row) (not= "" (:abstract row)))
                 (assoc :document/abstract (:abstract row))
                 include-toc?
                 (assoc :document/toc (get-document-toc db-info (:id row)))))
         rows)))))

(defn db-get-document
  "Full document row by id."
  [db-info doc-id]
  (when (ds db-info)
    (some-> (query-one! db-info {:select [:*] :from :document :where [:= :id doc-id]})
      document-row->ns)))

;; =============================================================================
;; Raw document
;; =============================================================================

(defn store-raw-document!
  "Persist raw PageIndex EDN for replay/debugging."
  [db-info doc-id content]
  (when (ds db-info)
    (jdbc/execute! (ds db-info)
      (sql/format
        {:insert-into :raw_document
         :values [{:id doc-id :content content}]
         :on-conflict [:id]
         :do-update-set [:content]}))))

;; =============================================================================
;; Pages
;; =============================================================================

(defn- page-row->ns [row]
  (cond-> {}
    (:id row)                       (assoc :page/id (:id row))
    (:document_id row)              (assoc :page/document-id (:document_id row))
    (some? (:idx row))              (assoc :page/index (:idx row))
    (some? (:created_at row))       (assoc :page/created-at (->date (:created_at row)))
    (some? (:last_accessed row))    (assoc :page/last-accessed (->date (:last_accessed row)))
    (some? (:access_count row))     (assoc :page/access-count (:access_count row))
    (some? (:q_value row))          (assoc :page/q-value (:q_value row))
    (some? (:q_update_count row))   (assoc :page/q-update-count (:q_update_count row))))

(defn- page->cols [page]
  (cond-> {}
    (:page/id page)                        (assoc :id (:page/id page))
    (:page/document-id page)               (assoc :document_id (:page/document-id page))
    (some? (:page/index page))             (assoc :idx (:page/index page))
    (:page/created-at page)                (assoc :created_at (->epoch-ms (:page/created-at page)))
    (:page/last-accessed page)             (assoc :last_accessed (->epoch-ms (:page/last-accessed page)))
    (some? (:page/access-count page))      (assoc :access_count (double (:page/access-count page)))
    (some? (:page/q-value page))           (assoc :q_value (double (:page/q-value page)))
    (some? (:page/q-update-count page))    (assoc :q_update_count (long (:page/q-update-count page)))))

(defn store-page!
  [db-info page]
  (when (ds db-info)
    (let [cols (page->cols page)]
      (when-let [id (:id cols)]
        (jdbc/execute! (ds db-info)
          (sql/format
            {:insert-into :page
             :values [cols]
             :on-conflict [:id]
             :do-update-set (vec (remove #{:id} (keys cols)))}))
        id))))

(defn get-page-q-value
  "Returns Q-value for a page (default 0.5 if not set)."
  [db-info page-id]
  (if-not (ds db-info)
    0.5
    (or (some-> (query-one! db-info
                  {:select [:q_value] :from :page :where [:= :id page-id]})
          :q_value)
      0.5)))

(defn update-page-q-value!
  "EMA update of Q-value: Q' = Q + alpha × (reward - Q).
   Alpha decreases with update count for stability."
  [db-info page-id reward]
  (when (ds db-info)
    (try
      (let [row (query-one! db-info
                  {:select [:q_value :q_update_count]
                   :from :page :where [:= :id page-id]})
            q (or (:q_value row) 0.5)
            cnt (or (:q_update_count row) 0)
            alpha (max 0.05 (/ 1.0 (+ 1.0 (/ cnt 10.0))))
            new-q (+ q (* alpha (- (double reward) q)))]
        (jdbc/execute! (ds db-info)
          (sql/format
            {:update :page
             :set {:q_value (min 1.0 (max 0.0 new-q))
                   :q_update_count (inc cnt)}
             :where [:= :id page-id]})))
      (catch Exception e
        (trove/log! {:level :debug :data {:page-id page-id :error (ex-message e)}
                     :msg "Q-value update failed (non-fatal)"})))))

(defn pages-accessed-since
  "Set of page ids accessed since the given Date/Instant/epoch-ms cutoff."
  [db-info cutoff]
  (when (ds db-info)
    (try
      (set (mapv :id
             (query! db-info
               {:select [:id] :from :page
                :where [:>= :last_accessed (->epoch-ms cutoff)]})))
      (catch Exception e
        (trove/log! {:level :debug :data {:error (ex-message e)}
                     :msg "pages-accessed-since query failed"})
        #{}))))

(defn finalize-q-updates!
  "Apply Q-reward to each page in the collection."
  [db-info accessed-page-ids reward]
  (doseq [pid (distinct accessed-page-ids)]
    (update-page-q-value! db-info pid reward)))

;; =============================================================================
;; Page nodes
;; =============================================================================

(defn- node-row->ns [row]
  (cond-> {}
    (:id row)                      (assoc :page.node/id (:id row))
    (:page_id row)                 (assoc :page.node/page-id (:page_id row))
    (:document_id row)             (assoc :page.node/document-id (:document_id row))
    (:local_id row)                (assoc :page.node/local-id (:local_id row))
    (:type row)                    (assoc :page.node/type (->kw-back (:type row)))
    (:content row)                 (assoc :page.node/content (:content row))
    (:description row)             (assoc :page.node/description (:description row))
    (:level row)                   (assoc :page.node/level (:level row))
    (:parent_id row)               (assoc :page.node/parent-id (:parent_id row))
    (:image_data row)              (assoc :page.node/image-data (:image_data row))
    (some? (:continuation row))    (assoc :page.node/continuation? (not (zero? (long (:continuation row)))))
    (:caption row)                 (assoc :page.node/caption (:caption row))
    (:kind row)                    (assoc :page.node/kind (:kind row))
    (:bbox row)                    (assoc :page.node/bbox (:bbox row))
    (:group_id row)                (assoc :page.node/group-id (:group_id row))))

(defn- node->cols [node]
  (cond-> {}
    (:page.node/id node)           (assoc :id (:page.node/id node))
    (:page.node/page-id node)      (assoc :page_id (:page.node/page-id node))
    (:page.node/document-id node)  (assoc :document_id (:page.node/document-id node))
    (:page.node/local-id node)     (assoc :local_id (:page.node/local-id node))
    (:page.node/type node)         (assoc :type (->kw (:page.node/type node)))
    (:page.node/content node)      (assoc :content (:page.node/content node))
    (:page.node/description node)  (assoc :description (:page.node/description node))
    (:page.node/level node)        (assoc :level (:page.node/level node))
    (:page.node/parent-id node)    (assoc :parent_id (:page.node/parent-id node))
    (:page.node/image-data node)   (assoc :image_data (:page.node/image-data node))
    (some? (:page.node/continuation? node)) (assoc :continuation (if (:page.node/continuation? node) 1 0))
    (:page.node/caption node)      (assoc :caption (:page.node/caption node))
    (:page.node/kind node)         (assoc :kind (:page.node/kind node))
    (:page.node/bbox node)         (assoc :bbox (if (string? (:page.node/bbox node))
                                                  (:page.node/bbox node)
                                                  (pr-str (:page.node/bbox node))))
    (:page.node/group-id node)     (assoc :group_id (:page.node/group-id node))))

(defn store-page-node!
  [db-info node]
  (when (ds db-info)
    (let [cols (node->cols node)]
      (when-let [id (:id cols)]
        (jdbc/execute! (ds db-info)
          (sql/format
            {:insert-into :page_node
             :values [cols]
             :on-conflict [:id]
             :do-update-set (vec (remove #{:id} (keys cols)))}))
        id))))

(defn db-get-page-node [db-info node-id]
  (when (ds db-info)
    (some-> (query-one! db-info {:select [:*] :from :page_node :where [:= :id node-id]})
      node-row->ns)))

(defn db-list-page-nodes
  ([db-info] (db-list-page-nodes db-info {}))
  ([db-info {:keys [page-id document-id type limit] :or {limit 100}}]
   (when (ds db-info)
     (let [where (cond-> [:and [:is-not :id nil]]
                   page-id     (conj [:= :page_id page-id])
                   document-id (conj [:= :document_id document-id])
                   type        (conj [:= :type (->kw type)]))
           rows (query! db-info
                  {:select [:id :page_id :document_id :type :level :local_id :content :description]
                   :from :page_node
                   :where where
                   :limit limit})]
       (mapv (fn [r]
               (let [truncate (fn [s]
                                (when s (if (> (count s) 200) (subs s 0 200) s)))]
                 (cond-> (node-row->ns r)
                   (:content r)     (assoc :page.node/content (truncate (:content r)))
                   (:description r) (assoc :page.node/description (truncate (:description r))))))
         rows)))))

;; =============================================================================
;; FTS5-powered search (page nodes + TOC + entities)
;; =============================================================================

(defn- fts-search
  "Run an FTS5 MATCH against the unified search table, optionally scoped to
   owner_table values. Returns [{:owner_table :owner_id :rank}] ordered by rank."
  [db-info owner-tables match-query {:keys [limit] :or {limit 50}}]
  (let [q {:select [[:owner_table :owner_table]
                    [:owner_id :owner_id]
                    [[:bm25 :search] :rank]]
           :from :search
           :where (into [:and [:match :search match-query]]
                    (when (seq owner-tables)
                      [[:in :owner_table owner-tables]]))
           :order-by [[:rank :asc]]
           :limit limit}]
    (try
      (query! db-info q)
      (catch Exception _ []))))

(defn- fts-query-for-substring
  "Convert a free-form user query into an FTS5 MATCH expression.
   Uses prefix match on each token to approximate substring semantics,
   and falls back to quoted-literal for unusual chars."
  [q]
  (when (and q (not (str/blank? q)))
    (let [tokens (->> (str/split (str/lower-case q) #"\s+")
                   (remove str/blank?)
                   (map (fn [t] (str/replace t #"[^a-z0-9]" ""))) ; alnum only
                   (remove str/blank?))]
      (when (seq tokens)
        (str/join " " (map (fn [t] (str t "*")) tokens))))))

(declare get-page-vitality compute-node-vitality recently-accessed-page-ids
         batch-cooccurrence-boosts record-document-access!)

(defn- brevify-node
  "Return brief form with 150-char preview + vitality fields preserved."
  [node]
  (let [content (or (:page.node/content node) (:page.node/description node) "")
        preview (if (> (count content) 150)
                  (str (subs content 0 150) "...")
                  content)]
    (-> node
      (dissoc :page.node/content :page.node/description)
      (assoc :preview preview :content-length (count content)))))

(defn db-search-page-nodes
  "Search page nodes with vitality-weighted reranking (0.7 relevance + 0.3 vitality + cooc)."
  ([db-info query] (db-search-page-nodes db-info query {}))
  ([db-info query {:keys [top-k document-id type min-vitality]
                    :or {top-k 10 min-vitality 0.1}}]
   (if (str/blank? (str query))
     (mapv brevify-node (db-list-page-nodes db-info {:document-id document-id :type type :limit top-k}))
     (when (ds db-info)
       (let [match (fts-query-for-substring query)]
         (when match
           (let [hits (fts-search db-info ["page_node"] match {:limit (* 4 top-k)})
                 ids (distinct (mapv :owner_id hits))
                 nodes (when (seq ids)
                         (mapv node-row->ns
                           (query! db-info
                             {:select [:*] :from :page_node
                              :where [:in :id ids]})))
                 filtered (->> nodes
                            (filter #(or (nil? document-id) (= document-id (:page.node/document-id %))))
                            (filter #(or (nil? type) (= (->kw type) (->kw (:page.node/type %))))))
                 page-v-cache (atom {})
                 cached-v (fn [page-id]
                            (or (get @page-v-cache page-id)
                              (let [v (or (get-page-vitality db-info page-id) {:score 1.0 :zone :active})]
                                (swap! page-v-cache assoc page-id v)
                                v)))
                 recent-pages (or (recently-accessed-page-ids db-info) #{})
                 result-page-ids (distinct (keep :page.node/page-id filtered))
                 cooc-map (if (and (seq recent-pages) (seq result-page-ids))
                            (try (batch-cooccurrence-boosts db-info result-page-ids recent-pages)
                                 (catch Exception _ {}))
                            {})
                 total (max 1 (count filtered))
                 ranked (->> filtered
                          (map-indexed
                            (fn [idx node]
                              (let [pid (:page.node/page-id node)
                                    pv (cached-v pid)
                                    qv (get-page-q-value db-info pid)
                                    nv (compute-node-vitality (:score pv) (:page.node/type node) qv)
                                    relevance (- 1.0 (/ (double idx) total))
                                    cooc-bonus (* 0.05 (get cooc-map pid 0.0))
                                    combined (+ (* 0.7 relevance) (* 0.3 (:score nv)) cooc-bonus)]
                                (assoc node
                                  ::combined combined
                                  :vitality-score (:score nv)
                                  :vitality-zone (:zone nv)))))
                          (filter #(>= (:vitality-score %) min-vitality))
                          (sort-by ::combined #(compare %2 %1))
                          (take top-k))]
             (mapv #(-> % (dissoc ::combined) brevify-node) ranked))))))))

(defn db-search-batch
  "Parallel multi-query search. Dedupes nodes by id, keeps highest vitality."
  ([db-info queries] (db-search-batch db-info queries {}))
  ([db-info queries {:keys [top-k limit document-id min-vitality]
                      :or {top-k 10 limit 30 min-vitality 0.1}}]
   (when (seq queries)
     (let [per-q (cond-> {:top-k top-k :min-vitality min-vitality}
                   document-id (assoc :document-id document-id))
           all-results (into [] (mapcat identity)
                         (pmap #(db-search-page-nodes db-info % per-q) queries))
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

;; =============================================================================
;; TOC
;; =============================================================================

(defn- toc-row->ns [row]
  (cond-> {}
    (:id row)                 (assoc :document.toc/id (:id row))
    (:document_id row)        (assoc :document.toc/document-id (:document_id row))
    (:type row)               (assoc :document.toc/type (->kw-back (:type row)))
    (:title row)              (assoc :document.toc/title (:title row))
    (:description row)        (when-not (= "" (:description row))
                                (assoc :document.toc/description (:description row)))
    (some? (:target_page row))(assoc :document.toc/target-page (:target_page row))
    (:target_section_id row)  (assoc :document.toc/target-section-id (:target_section_id row))
    (:level row)              (assoc :document.toc/level (:level row))
    (:parent_id row)          (assoc :document.toc/parent-id (:parent_id row))
    (some? (:created_at row)) (assoc :document.toc/created-at (->date (:created_at row)))))

(defn- toc->cols [e]
  (cond-> {}
    (:document.toc/id e)                 (assoc :id (:document.toc/id e))
    (:document.toc/document-id e)        (assoc :document_id (:document.toc/document-id e))
    (:document.toc/type e)               (assoc :type (->kw (:document.toc/type e)))
    (:document.toc/title e)              (assoc :title (:document.toc/title e))
    (:document.toc/description e)        (assoc :description (:document.toc/description e))
    (some? (:document.toc/target-page e))(assoc :target_page (:document.toc/target-page e))
    (:document.toc/target-section-id e)  (assoc :target_section_id (:document.toc/target-section-id e))
    (:document.toc/level e)              (assoc :level (:document.toc/level e))
    (:document.toc/parent-id e)          (assoc :parent_id (:document.toc/parent-id e))
    (:document.toc/created-at e)         (assoc :created_at (->epoch-ms (:document.toc/created-at e)))))

(defn db-store-toc-entry!
  ([db-info entry] (db-store-toc-entry! db-info entry "standalone"))
  ([db-info entry doc-id]
   (when (ds db-info)
     (let [id (or (:document.toc/id entry) (str (util/uuid)))
           entry-data (-> entry
                        (assoc :document.toc/id id
                          :document.toc/document-id doc-id
                          :document.toc/created-at (Date.)))
           cols (toc->cols entry-data)]
       (jdbc/execute! (ds db-info)
         (sql/format
           {:insert-into :document_toc
            :values [cols]
            :on-conflict [:id]
            :do-update-set (vec (remove #{:id} (keys cols)))}))
       entry-data))))

(defn db-get-toc-entry [db-info entry-id]
  (when (ds db-info)
    (some-> (query-one! db-info {:select [:*] :from :document_toc :where [:= :id entry-id]})
      toc-row->ns)))

(defn db-list-toc-entries
  ([db-info] (db-list-toc-entries db-info {}))
  ([db-info {:keys [parent-id limit] :or {limit 100}}]
   (when (ds db-info)
     (let [where (cond-> [:and [:is-not :id nil]]
                   parent-id (conj [:= :parent_id parent-id]))
           rows (query! db-info
                  {:select [:id :title :level :description :target_page :parent_id]
                   :from :document_toc
                   :where where
                   :order-by [[:level :asc]]
                   :limit limit})]
       (mapv toc-row->ns rows)))))

(defn db-search-toc-entries
  ([db-info query] (db-search-toc-entries db-info query {}))
  ([db-info query {:keys [top-k] :or {top-k 10}}]
   (if (str/blank? (str query))
     (db-list-toc-entries db-info {:limit top-k})
     (when (ds db-info)
       (when-let [match (fts-query-for-substring query)]
         (let [hits (fts-search db-info ["document_toc"] match {:limit (* 3 top-k)})
               ids (distinct (mapv :owner_id hits))]
           (when (seq ids)
             (->> (query! db-info {:select [:*] :from :document_toc :where [:in :id ids]})
               (take top-k)
               (mapv toc-row->ns)))))))))

;; =============================================================================
;; Entities + relationships (search, list, get)
;; =============================================================================

(defn db-get-entity [db-info entity-id]
  (when (ds db-info)
    (fetch-entity db-info (->id entity-id))))

(defn db-list-entities
  ([db-info] (db-list-entities db-info {}))
  ([db-info {:keys [type document-id limit] :or {limit 100}}]
   (when (ds db-info)
     (let [where (cond-> [:and [:is-not :id nil]]
                   type        (conj [:= :type (->kw type)])
                   document-id (conj [:= :document_id document-id]))
           rows (query! db-info
                  {:select [:id :name :type :description :document_id :page :section]
                   :from :entity
                   :where where
                   :order-by [[:name :asc]]
                   :limit limit})]
       (mapv (fn [r] (cond-> (entity-base r)
                       (some? (:name r))         (assoc :entity/name (:name r))
                       (and (:description r) (not= "" (:description r)))
                       (assoc :entity/description (:description r))
                       (and (:section r) (not= "" (:section r)))
                       (assoc :entity/section (:section r))))
         rows)))))

(defn db-search-entities
  ([db-info query] (db-search-entities db-info query {}))
  ([db-info query {:keys [top-k type document-id] :or {top-k 10}}]
   (if (str/blank? (str query))
     (db-list-entities db-info {:type type :document-id document-id :limit top-k})
     (when (ds db-info)
       (when-let [match (fts-query-for-substring query)]
         (let [hits (fts-search db-info ["entity"] match {:limit (* 4 top-k)})
               ids (distinct (mapv :owner_id hits))
               where (cond-> [:and [:in :id ids]]
                       type        (conj [:= :type (->kw type)])
                       document-id (conj [:= :document_id document-id]))]
           (when (seq ids)
             (->> (query! db-info
                    {:select [:id :name :type :description :document_id :page :section]
                     :from :entity :where where})
               (take top-k)
               (mapv (fn [r] (entity-base r)))))))))))

(defn db-list-relationships
  ([db-info entity-id] (db-list-relationships db-info entity-id {}))
  ([db-info entity-id {:keys [type]}]
   (when (ds db-info)
     (let [eid (->id entity-id)
           where (cond-> [:and
                          [:or [:= :source_entity_id eid]
                           [:= :target_entity_id eid]]]
                   type (conj [:= :type (->kw type)]))
           rows (query! db-info
                  {:select [:id :type :source_entity_id :target_entity_id :description]
                   :from :relationship
                   :where where})]
       (mapv (fn [r]
               (cond-> {:relationship/id (->uuid (:id r))
                        :relationship/type (->kw-back (:type r))
                        :relationship/source-entity-id (->uuid (:source_entity_id r))
                        :relationship/target-entity-id (->uuid (:target_entity_id r))}
                 (and (:description r) (not= "" (:description r)))
                 (assoc :relationship/description (:description r))))
         rows)))))

(defn store-relationship!
  "Upsert a relationship row."
  [db-info rel]
  (when (ds db-info)
    (let [row (cond-> {:id (->id (or (:relationship/id rel) (util/uuid)))}
                (:relationship/type rel)             (assoc :type (->kw (:relationship/type rel)))
                (:relationship/source-entity-id rel) (assoc :source_entity_id (->id (:relationship/source-entity-id rel)))
                (:relationship/target-entity-id rel) (assoc :target_entity_id (->id (:relationship/target-entity-id rel)))
                (:relationship/description rel)      (assoc :description (:relationship/description rel))
                (:relationship/document-id rel)      (assoc :document_id (:relationship/document-id rel)))]
      (jdbc/execute! (ds db-info)
        (sql/format
          {:insert-into :relationship
           :values [row]
           :on-conflict [:id]
           :do-update-set (vec (remove #{:id} (keys row)))}))
      (:id row))))

;; =============================================================================
;; Find-related (undirected BFS with vitality priority + canonical expansion)
;; =============================================================================

(declare get-page-vitality)

(defn find-related
  ([db-info anchor-id] (find-related db-info anchor-id {}))
  ([db-info anchor-id {:keys [depth min-vitality limit]
                        :or {depth 2 min-vitality 0.1 limit 50}}]
   (when (ds db-info)
     (let [depth (min depth 5)
           anchor-id-str (->id anchor-id)
           ;; Canonical sibling expansion
           canonical-row (query-one! db-info
                           {:select [:canonical_id] :from :entity :where [:= :id anchor-id-str]})
           anchor-canonical (:canonical_id canonical-row)
           anchor-set (if anchor-canonical
                        (set (mapv :id (query! db-info
                                         {:select [:id] :from :entity
                                          :where [:= :canonical_id anchor-canonical]})))
                        #{anchor-id-str})
           ;; Adjacency over relationships (undirected)
           all-rels (query! db-info
                      {:select [:source_entity_id :target_entity_id :type :description]
                       :from :relationship})
           adjacency (reduce (fn [acc r]
                               (let [src (:source_entity_id r)
                                     tgt (:target_entity_id r)
                                     info {:type (->kw-back (:type r))
                                           :description (:description r)}]
                                 (-> acc
                                   (update src (fnil conj []) (assoc info :neighbor-id tgt))
                                   (update tgt (fnil conj []) (assoc info :neighbor-id src)))))
                       {} all-rels)
           visited (atom anchor-set)
           results (atom [])
           initial-queue (for [aid anchor-set
                               neighbor (get adjacency aid)]
                           (assoc neighbor :distance 1))]
       (loop [queue (vec initial-queue)
              current-depth 1]
         (when (and (seq queue) (<= current-depth depth))
           (let [next-level (atom [])]
             (doseq [{:keys [neighbor-id distance type description]} queue]
               (when-not (contains? @visited neighbor-id)
                 (swap! visited conj neighbor-id)
                 (let [canon (some-> (query-one! db-info
                                       {:select [:canonical_id] :from :entity
                                        :where [:= :id neighbor-id]})
                               :canonical_id)
                       siblings (if canon
                                  (set (mapv :id (query! db-info
                                                   {:select [:id] :from :entity
                                                    :where [:= :canonical_id canon]})))
                                  #{neighbor-id})]
                   (swap! visited into siblings)
                   (when-let [e (query-one! db-info
                                  {:select [:id :name :type :document_id :description :page]
                                   :from :entity :where [:= :id neighbor-id]})]
                     (when (:id e)
                       (swap! results conj
                         (assoc (entity-base e)
                           :distance distance
                           :via-relationship {:type type :description description}))))
                   (when (< distance depth)
                     (doseq [sibling siblings]
                       (doseq [neighbor (get adjacency sibling)]
                         (when-not (contains? @visited (:neighbor-id neighbor))
                           (swap! next-level conj
                             (assoc neighbor :distance (inc distance))))))))))
             (recur @next-level (inc current-depth)))))
       (->> @results
         (sort-by (juxt :distance :entity/name))
         (take limit)
         vec)))))

;; =============================================================================
;; Repos & Commits (git ingestion)
;; =============================================================================

(defn db-store-repo!
  [db-info {:keys [name path head-sha head-short branch commits-ingested]}]
  (when (and (ds db-info) name)
    (let [entity-id (->id (util/uuid))]
      (jdbc/with-transaction [tx (ds db-info)]
        (let [tx-info {:datasource tx}]
          ;; Try to find an existing entity by repo_attrs.name
          (if-let [existing (query-one! tx-info
                              {:select [:entity_id] :from :repo_attrs :where [:= :name name]})]
            (let [eid (:entity_id existing)]
              (jdbc/execute! tx
                (sql/format
                  {:update :repo_attrs
                   :set (cond-> {:ingested_at (now-ms)}
                          path             (assoc :path (str path))
                          head-sha         (assoc :head_sha head-sha)
                          head-short       (assoc :head_short head-short)
                          branch           (assoc :branch branch)
                          commits-ingested (assoc :commits_ingested (long commits-ingested)))
                   :where [:= :entity_id eid]})))
            (do
              (upsert-entity-row! tx-info
                {:id entity-id :type "repo" :name name :created_at (now-ms) :updated_at (now-ms)})
              (jdbc/execute! tx
                (sql/format
                  {:insert-into :repo_attrs
                   :values [(cond-> {:entity_id entity-id :name name
                                     :path (str path) :ingested_at (now-ms)}
                              head-sha         (assoc :head_sha head-sha)
                              head-short       (assoc :head_short head-short)
                              branch           (assoc :branch branch)
                              commits-ingested (assoc :commits_ingested (long commits-ingested)))]})))))
        name))))

(defn- repo-row->ns [row]
  (cond-> {}
    (:name row)             (assoc :repo/name (:name row))
    (:path row)             (assoc :repo/path (:path row))
    (:head_sha row)         (assoc :repo/head-sha (:head_sha row))
    (:head_short row)       (assoc :repo/head-short (:head_short row))
    (:branch row)           (assoc :repo/branch (:branch row))
    (some? (:commits_ingested row)) (assoc :repo/commits-ingested (:commits_ingested row))
    (some? (:ingested_at row)) (assoc :repo/ingested-at (->date (:ingested_at row)))))

(defn db-list-repos [db-info]
  (when (ds db-info)
    (->> (query! db-info
           {:select [:name :path :head_sha :head_short :branch :commits_ingested :ingested_at]
            :from :repo_attrs
            :order-by [[:name :asc]]})
      (mapv repo-row->ns))))

(defn db-get-repo-by-name [db-info name]
  (when (and (ds db-info) name)
    (some-> (query-one! db-info
              {:select [:name :path :head_sha :head_short :branch :commits_ingested :ingested_at]
               :from :repo_attrs :where [:= :name name]})
      repo-row->ns)))

(defn store-commit-entity!
  "Bulk insert a commit entity with its commit_attrs + child rows for
   ticket-refs/file-paths/parents."
  [db-info {:keys [entity-id entity-cols commit-cols ticket-refs file-paths parents]}]
  (when (ds db-info)
    (jdbc/with-transaction [tx (ds db-info)]
      (let [tx-info {:datasource tx}]
        (upsert-entity-row! tx-info entity-cols)
        (when (seq commit-cols)
          (jdbc/execute! tx
            (sql/format
              {:insert-into :commit_attrs
               :values [(assoc commit-cols :entity_id entity-id)]
               :on-conflict [:entity_id]
               :do-update-set (vec (keys commit-cols))})))
        (doseq [t (distinct ticket-refs)]
          (jdbc/execute! tx
            (sql/format
              {:insert-into :commit_ticket_ref
               :values [{:entity_id entity-id :ticket t}]
               :on-conflict [:entity_id :ticket] :do-nothing true})))
        (doseq [p (distinct file-paths)]
          (jdbc/execute! tx
            (sql/format
              {:insert-into :commit_file_path
               :values [{:entity_id entity-id :path p}]
               :on-conflict [:entity_id :path] :do-nothing true})))
        (doseq [s (distinct parents)]
          (jdbc/execute! tx
            (sql/format
              {:insert-into :commit_parent
               :values [{:entity_id entity-id :parent_sha s}]
               :on-conflict [:entity_id :parent_sha] :do-nothing true})))
        entity-id))))

(defn- commit-row->ns [row]
  (let [base (entity-base row)
        ticket-refs (mapv :ticket
                      (query! {:datasource nil} ;; sentinel; never actually runs
                        {:select [:ticket] :from :commit_ticket_ref}))]
    (merge base
      (cond-> {}
        (:sha row)         (assoc :commit/sha (:sha row))
        (:category row)    (assoc :commit/category (->kw-back (:category row)))
        (:date row)        (assoc :commit/date (:date row))
        (:prefix row)      (assoc :commit/prefix (:prefix row))
        (:scope row)       (assoc :commit/scope (:scope row))
        (:author_email row)(assoc :commit/author-email (:author_email row)))
      {:commit/ticket-refs ticket-refs})))

(defn db-search-commits
  ([db-info] (db-search-commits db-info {}))
  ([db-info {:keys [category since until ticket path author-email document-id limit]
              :or {limit 50}}]
   (when (ds db-info)
     (let [where (cond-> [:and [:= :e.type "event"] [:is-not :c.sha nil]]
                   category     (conj [:= :c.category (->kw category)])
                   document-id  (conj [:= :e.document_id document-id])
                   author-email (conj [:= :c.author_email author-email])
                   since        (conj [:>= :c.date since])
                   until        (conj [:<= :c.date until]))
           base-sql {:select [[:e.id :eid]
                              [:e.name :e-name] [:e.description :e-desc]
                              [:e.document_id :doc-id]
                              [:c.sha :sha] [:c.category :category]
                              [:c.date :date] [:c.prefix :prefix] [:c.scope :scope]
                              [:c.author_email :author_email]]
                     :from [[:entity :e]]
                     :join [[:commit_attrs :c] [:= :e.id :c.entity_id]]
                     :where where
                     :order-by [[:c.date :desc]]
                     :limit limit}
           base-sql (cond-> base-sql
                      ticket (update :join into [[:commit_ticket_ref :t] [:= :t.entity_id :e.id]])
                      ticket (update :where conj [:= :t.ticket ticket])
                      path (update :join into [[:commit_file_path :p] [:= :p.entity_id :e.id]])
                      path (update :where conj [:like :p.path (str "%" path "%")]))
           rows (query! db-info base-sql)
           ids (distinct (mapv :eid rows))
           ticket-map (if (seq ids)
                        (group-by :entity_id
                          (query! db-info
                            {:select [:entity_id :ticket] :from :commit_ticket_ref
                             :where [:in :entity_id ids]}))
                        {})
           file-map (if (seq ids)
                      (group-by :entity_id
                        (query! db-info
                          {:select [:entity_id :path] :from :commit_file_path
                           :where [:in :entity_id ids]}))
                      {})
           parent-map (if (seq ids)
                        (group-by :entity_id
                          (query! db-info
                            {:select [:entity_id :parent_sha] :from :commit_parent
                             :where [:in :entity_id ids]}))
                        {})]
       (mapv (fn [r]
               (let [eid (:eid r)]
                 (cond-> {:entity/id (->uuid eid)
                          :entity/name (:e-name r)
                          :entity/description (:e-desc r)
                          :entity/document-id (:doc-id r)
                          :commit/sha (:sha r)
                          :commit/category (->kw-back (:category r))
                          :commit/date (:date r)
                          :commit/author-email (:author_email r)}
                   (:prefix r) (assoc :commit/prefix (:prefix r))
                   (:scope r)  (assoc :commit/scope (:scope r))
                   (seq (get ticket-map eid))
                   (assoc :commit/ticket-refs (mapv :ticket (get ticket-map eid)))
                   (seq (get file-map eid))
                   (assoc :commit/file-paths (mapv :path (get file-map eid)))
                   (seq (get parent-map eid))
                   (assoc :commit/parents (mapv :parent_sha (get parent-map eid))))))
         rows)))))

(defn db-commit-by-sha
  [db-info sha]
  (when (and (ds db-info) (seq sha))
    (first (db-search-commits db-info {}))))

;; =============================================================================
;; Document certainty (Bayesian)
;; =============================================================================

(defn- days-since-ms ^double [now-ms past-ms]
  (if past-ms
    (/ (- (double now-ms) (double past-ms)) 86400000.0)
    0.0))

(defn document-certainty
  "Computes Bayesian certainty = alpha / (alpha + effective-beta)."
  [db-info doc-id]
  (when (ds db-info)
    (let [row (query-one! db-info
                {:select [:certainty_alpha :certainty_beta :updated_at :created_at]
                 :from :document :where [:= :id doc-id]})
          alpha (or (:certainty_alpha row) 2.0)
          stored-beta (or (:certainty_beta row) 1.0)
          updated-ms (or (:updated_at row) (:created_at row))
          days-since (days-since-ms (now-ms) updated-ms)
          effective-beta (+ stored-beta (* 0.01 days-since))]
      (when (:certainty_alpha row)
        {:certainty (/ alpha (+ alpha effective-beta))
         :alpha alpha
         :beta effective-beta}))))

(def ^:private ^:const CERTAINTY-NORM-THRESHOLD 50.0)
(def ^:private ^:const CERTAINTY-NORM-TARGET 20.0)

(defn- normalize-certainty-params! [db-info doc-id]
  (let [row (query-one! db-info
              {:select [:certainty_alpha :certainty_beta]
               :from :document :where [:= :id doc-id]})
        alpha (or (:certainty_alpha row) 2.0)
        beta (or (:certainty_beta row) 1.0)
        total (+ alpha beta)]
    (when (> total CERTAINTY-NORM-THRESHOLD)
      (let [scale (/ CERTAINTY-NORM-TARGET total)]
        (jdbc/execute! (ds db-info)
          (sql/format
            {:update :document
             :set {:certainty_alpha (* alpha scale)
                   :certainty_beta (* beta scale)}
             :where [:= :id doc-id]}))))))

(defn record-document-access!
  ([db-info doc-id] (record-document-access! db-info doc-id 1.0))
  ([db-info doc-id boost]
   (when (ds db-info)
     (try
       (let [row (query-one! db-info
                   {:select [:certainty_alpha] :from :document :where [:= :id doc-id]})
             alpha (or (:certainty_alpha row) 2.0)]
         (jdbc/execute! (ds db-info)
           (sql/format
             {:update :document
              :set {:certainty_alpha (+ alpha (double boost))}
              :where [:= :id doc-id]}))
         (normalize-certainty-params! db-info doc-id))
       (catch Exception e
         (trove/log! {:level :warn :data {:doc-id doc-id :error (ex-message e)}
                      :msg "Failed to record document access"}))))))

(defn decay-document-certainty!
  [db-info doc-id]
  (when (ds db-info)
    (try
      (let [row (query-one! db-info
                  {:select [:certainty_beta :updated_at :created_at]
                   :from :document :where [:= :id doc-id]})
            beta (or (:certainty_beta row) 1.0)
            updated-ms (or (:updated_at row) (:created_at row))
            days-since (days-since-ms (now-ms) updated-ms)
            beta-inc (* 0.01 days-since)]
        (when (> beta-inc 0.001)
          (jdbc/execute! (ds db-info)
            (sql/format
              {:update :document
               :set {:certainty_beta (+ beta beta-inc)}
               :where [:= :id doc-id]}))))
      (catch Exception e
        (trove/log! {:level :warn :data {:doc-id doc-id :error (ex-message e)}
                     :msg "Failed to decay document certainty"})))))

(defn reindex-certainty-jump!
  ([db-info doc-id] (reindex-certainty-jump! db-info doc-id 5.0))
  ([db-info doc-id jump]
   (when (ds db-info)
     (try
       (let [row (query-one! db-info
                   {:select [:certainty_beta] :from :document :where [:= :id doc-id]})
             beta (or (:certainty_beta row) 1.0)]
         (jdbc/execute! (ds db-info)
           (sql/format
             {:update :document
              :set {:certainty_beta (+ beta (double jump))}
              :where [:= :id doc-id]}))
         (normalize-certainty-params! db-info doc-id))
       (catch Exception e
         (trove/log! {:level :warn :data {:doc-id doc-id :error (ex-message e)}
                      :msg "Failed to apply reindex certainty jump"}))))))

;; =============================================================================
;; Vitality (pure computations + page-level lookup)
;; =============================================================================

(def ^:private METABOLIC-RATES
  {:section 0.3 :heading 0.3 :paragraph 1.0 :list-item 1.0 :toc-entry 0.1
   :table 0.5 :image 0.5 :header 0.8 :footer 0.8 :metadata 0.3 :entity 0.8})

(def ^:private VITALITY-ZONES
  [[0.6 :active] [0.3 :stale] [0.1 :fading] [0.0 :archived]])

(defn vitality-zone [score]
  (or (some (fn [[t z]] (when (>= score t) z)) VITALITY-ZONES) :archived))

(defn compute-page-vitality
  ([access-count created-at last-accessed children-count]
   (compute-page-vitality access-count created-at last-accessed children-count (Date.)))
  ([access-count _created-at last-accessed children-count now]
   (let [d 0.5
         now-ms (->epoch-ms now)
         la-ms (->epoch-ms last-accessed)
         recency-ms (max 1 (- (long now-ms) (long la-ms)))
         recency-days (max 0.001 (/ recency-ms 86400000.0))
         access (max 0.01 (double access-count))
         B (- (Math/log (/ access (- 1.0 d)))
             (* d (Math/log recency-days)))
         base (/ 1.0 (+ 1.0 (Math/exp (- B))))
         boost (+ 1.0 (* 0.05 (min children-count 10)))
         score (min 1.0 (* base boost))]
     {:score score :zone (vitality-zone score)})))

(defn compute-node-vitality
  ([page-score node-type] (compute-node-vitality page-score node-type 0.5))
  ([page-score node-type q-value]
   (let [base-rate (get METABOLIC-RATES (->kw-back (->kw node-type)) 1.0)
         q-bonus (* (- (double q-value) 0.5) 0.4)
         metabolic (max 0.01 (* base-rate (- 1.0 q-bonus)))
         eff (if (pos? page-score) (Math/pow page-score metabolic) 0.0)]
     {:score eff :zone (vitality-zone eff)})))

(defn get-page-vitality
  [db-info page-id]
  (when (ds db-info)
    (let [row (query-one! db-info
                {:select [:created_at :last_accessed :access_count :idx :document_id]
                 :from :page :where [:= :id page-id]})
          children-count (or (some-> (query-one! db-info
                                       {:select [[[:count :*] :cnt]]
                                        :from :page_node
                                        :where [:= :page_id page-id]})
                               :cnt)
                           0)]
      (when (:created_at row)
        (let [created-at (->date (:created_at row))
              la (->date (or (:last_accessed row) (:created_at row)))
              {:keys [score]} (compute-page-vitality
                                (or (:access_count row) 0.0)
                                created-at la children-count)
              doc-cert (when-let [doc-id (:document_id row)]
                         (:certainty (document-certainty db-info doc-id)))
              final-score (if doc-cert (min 1.0 (* score doc-cert)) score)]
          {:score final-score
           :zone (vitality-zone final-score)
           :access-count (or (:access_count row) 0.0)
           :last-accessed la
           :created-at created-at
           :children-count children-count})))))

;; =============================================================================
;; Page access + activation propagation
;; =============================================================================

(defn record-page-access!
  "Update page last-accessed/access-count, boost document certainty, propagate activation."
  [db-info page-id weight]
  (when (ds db-info)
    (try
      (let [row (query-one! db-info
                  {:select [:access_count :document_id] :from :page :where [:= :id page-id]})
            cur (or (:access_count row) 0.0)]
        (jdbc/execute! (ds db-info)
          (sql/format
            {:update :page
             :set {:last_accessed (now-ms)
                   :access_count (+ cur (double weight))}
             :where [:= :id page-id]}))
        (when (and (>= weight 1.0) (:document_id row))
          (record-document-access! db-info (:document_id row) 0.5)))
      (catch Exception e
        (trove/log! {:level :warn :data {:page-id page-id :error (ex-message e)}
                     :msg "Failed to record page access"})))))

;; =============================================================================
;; Cooccurrence
;; =============================================================================

(def ^:private ^:const COOC-DECAY-TAU 7.0)
(def ^:private ^:const COOC-MAX-PAIRS 20)

(defn- cooc-decay ^double [^double strength ^double days]
  (* strength (Math/exp (- (/ days COOC-DECAY-TAU)))))

(defn- cooc-pair [a b]
  (let [[x y] (if (neg? (compare a b)) [a b] [b a])]
    [x y (str x "|" y)]))

(defn record-cooccurrence!
  [db-info pa pb]
  (when (and (ds db-info) (not= pa pb))
    (try
      (let [[a b id] (cooc-pair pa pb)
            row (query-one! db-info
                  {:select [:strength :last_seen] :from :page_cooccurrence :where [:= :id id]})
            old-s (or (:strength row) 0.0)
            last-s (:last_seen row)
            days (days-since-ms (now-ms) last-s)
            new-s (+ (cooc-decay old-s days) 1.0)]
        (jdbc/execute! (ds db-info)
          (sql/format
            {:insert-into :page_cooccurrence
             :values [{:id id :page_a a :page_b b :strength new-s :last_seen (now-ms)}]
             :on-conflict [:id]
             :do-update-set [:page_a :page_b :strength :last_seen]})))
      (catch Exception e
        (trove/log! {:level :debug :data {:pa pa :pb pb :error (ex-message e)}
                     :msg "Cooc record failed (non-fatal)"})))))

(defn record-cooccurrences!
  [db-info page-ids]
  (let [ids (vec (take COOC-MAX-PAIRS (distinct page-ids)))]
    (when (> (count ids) 1)
      (doseq [i (range (count ids))
              j (range (inc i) (count ids))]
        (record-cooccurrence! db-info (nth ids i) (nth ids j))))))

(defn recently-accessed-page-ids
  "Set of page IDs accessed within the last hour."
  [db-info]
  (when (ds db-info)
    (try
      (let [cutoff (- (now-ms) 3600000)]
        (set (mapv :id
               (query! db-info
                 {:select [:id] :from :page :where [:>= :last_accessed cutoff]}))))
      (catch Exception _ #{}))))

(defn batch-cooccurrence-boosts
  "Returns {page-id -> total-decayed-boost} for result pages vs recent pages."
  [db-info result-page-ids recent-page-ids]
  (let [now (now-ms)
        all (set (concat result-page-ids recent-page-ids))]
    (if (empty? all)
      {}
      (let [edges (query! db-info
                    {:select [:page_a :page_b :strength :last_seen]
                     :from :page_cooccurrence
                     :where [:and [:in :page_a all] [:in :page_b all]]})
            recent-set (set recent-page-ids)]
        (reduce (fn [acc {:keys [page_a page_b strength last_seen]}]
                  (let [result-pid (cond
                                     (and (recent-set page_b) (not (recent-set page_a))) page_a
                                     (and (recent-set page_a) (not (recent-set page_b))) page_b
                                     :else nil)]
                    (if (and result-pid strength)
                      (update acc result-pid (fnil + 0.0)
                        (cooc-decay strength (days-since-ms now last_seen)))
                      acc)))
          {} edges)))))

(defn get-cooccurrence-boost
  [db-info page-id recent-page-ids]
  (if (or (empty? recent-page-ids) (not (ds db-info)))
    0.0
    (let [now (now-ms)]
      (double
        (reduce (fn [acc recent-pid]
                  (if (= page-id recent-pid)
                    acc
                    (let [[_ _ id] (cooc-pair page-id recent-pid)
                          row (query-one! db-info
                                {:select [:strength :last_seen] :from :page_cooccurrence
                                 :where [:= :id id]})]
                      (if-let [s (:strength row)]
                        (+ acc (cooc-decay s (days-since-ms now (:last_seen row))))
                        acc))))
          0.0 recent-page-ids)))))

(defn propagate-activation!
  "Spread activation from `page-id` to canonical siblings + relationship neighbors."
  ([db-info page-id weight] (propagate-activation! db-info page-id weight 0.6))
  ([db-info page-id weight damping]
   (when (and (ds db-info) (>= weight 1.0))
     (try
       (let [page (query-one! db-info
                    {:select [:document_id :idx] :from :page :where [:= :id page-id]})
             doc-id (:document_id page)
             pidx (:idx page)
             canonicals (when doc-id
                          (mapv :canonical_id
                            (query! db-info
                              {:select [:canonical_id] :from :entity
                               :where [:and [:= :document_id doc-id]
                                       [:= :page pidx]
                                       [:is-not :canonical_id nil]]})))
             sibling-pids (when (seq canonicals)
                            (set (mapv :id
                                   (query! db-info
                                     {:select-distinct [:p.id]
                                      :from [[:entity :e]]
                                      :join [[:page :p] [:and [:= :p.document_id :e.document_id]
                                                         [:= :p.idx :e.page]]]
                                      :where [:and [:in :e.canonical_id canonicals]
                                              [:not= :p.id page-id]]}))))
             page-entity-ids (when doc-id
                               (mapv :id
                                 (query! db-info
                                   {:select [:id] :from :entity
                                    :where [:and [:= :document_id doc-id]
                                            [:= :page pidx]]})))
             rel-neighbor-pids (when (seq page-entity-ids)
                                 (->> page-entity-ids
                                   (mapcat #(find-related db-info % {:depth 1 :limit 10}))
                                   (keep (fn [e]
                                           (when (and (:entity/document-id e) (:entity/page e))
                                             (some-> (query-one! db-info
                                                       {:select [:id] :from :page
                                                        :where [:and [:= :document_id (:entity/document-id e)]
                                                                [:= :idx (:entity/page e)]]})
                                               :id))))
                                   distinct))
             all-connected (->> (concat (or sibling-pids []) (or rel-neighbor-pids []))
                             distinct
                             (remove #(= % page-id)))]
         (doseq [pid all-connected]
           (let [boost (* weight damping)
                 row (query-one! db-info
                       {:select [:access_count] :from :page :where [:= :id pid]})
                 cur (or (:access_count row) 0.0)]
             (jdbc/execute! (ds db-info)
               (sql/format
                 {:update :page
                  :set {:last_accessed (now-ms) :access_count (+ cur boost)}
                  :where [:= :id pid]})))))
       (catch Exception e
         (trove/log! {:level :debug :data {:page-id page-id :error (ex-message e)}
                      :msg "Spreading activation failed (non-fatal)"}))))))

;; =============================================================================
;; Skills
;; =============================================================================

(defn skill-changed?
  "True if stored content-hash differs from new-hash (or no stored skill yet)."
  [db-info skill-name new-hash]
  (let [doc-id (str "skill-" (clojure.core/name skill-name))]
    (if-not (ds db-info)
      true
      (let [row (query-one! db-info
                  {:select [:content_hash] :from :skill_attrs :where [:= :document_id doc-id]})
            stored (:content_hash row)]
        (or (nil? stored) (not= stored new-hash))))))

(defn ingest-skills!
  "Ingest skill registry into SQLite as :document.type/skill rows + skill_attrs.
   Skips skills whose content-hash hasn't changed. Returns count ingested."
  [db-info skill-registry]
  (when (and (ds db-info) (seq skill-registry))
    (let [changed (filterv (fn [[nm skill]]
                             (skill-changed? db-info nm (:content-hash skill)))
                    skill-registry)]
      (jdbc/with-transaction [tx (ds db-info)]
        (let [tx-info {:datasource tx}]
          (doseq [[nm skill] changed]
            (let [n (clojure.core/name nm)
                  doc-id (str "skill-" n)]
              (store-document! tx-info
                {:document/id doc-id
                 :document/name n
                 :document/type :document.type/skill
                 :document/title (or (:description skill) n)
                 :document/abstract (or (:abstract skill) "")
                 :document/extension "md"
                 :document/updated-at (Date.)
                 :document/certainty-alpha 2.0
                 :document/certainty-beta 1.0})
              (jdbc/execute! tx
                (sql/format
                  {:insert-into :skill_attrs
                   :values [(cond-> {:document_id doc-id
                                     :body (:body skill)
                                     :source_path (or (:source-path skill) "")
                                     :content_hash (or (:content-hash skill) "")}
                              (:agent skill)    (assoc :agent_config (pr-str (:agent skill)))
                              (:requires skill) (assoc :requires (pr-str (:requires skill)))
                              (:version skill)  (assoc :version (str (:version skill))))]
                   :on-conflict [:document_id]
                   :do-update-set [:body :source_path :content_hash :agent_config :requires :version]}))))))
      (trove/log! {:level :info :id ::skills-ingested
                   :data {:total (count skill-registry) :changed (count changed)}
                   :msg "Skills ingested (change-detected)"})
      (count changed))))

(defn delete-skill-entity!
  "Remove a skill document by canonical id: skill-<name>."
  [db-info skill-name]
  (when (ds db-info)
    (let [doc-id (str "skill-" (clojure.core/name skill-name))]
      (jdbc/execute! (ds db-info)
        (sql/format {:delete-from :document :where [:= :id doc-id]})))))

;; =============================================================================
;; Entity canonical-id lookup (for data.clj)
;; =============================================================================

(defn resolve-canonical-id
  "Resolve an existing canonical-id for (name, type), or return a fresh uuid."
  [db-info entity-name entity-type]
  (or (when (ds db-info)
        (some-> (query-one! db-info
                  {:select [:canonical_id] :from :entity
                   :where [:and [:= :type (->kw entity-type)]
                           [:= [:lower :name] (str/lower-case (str entity-name))]
                           [:is-not :canonical_id nil]]
                   :limit 1})
          :canonical_id ->uuid))
    (util/uuid)))

;; =============================================================================
;; QA manifest corpus snapshot helpers (qa_manifest.clj)
;; =============================================================================

(defn qa-corpus-documents [db-info]
  (->> (query! db-info
         {:select [:id :name :title :extension :abstract] :from :document})
    (mapv document-row->ns)
    (sort-by (juxt :document/id :document/name))
    vec))

(defn qa-corpus-toc-entries [db-info]
  (->> (query! db-info
         {:select [:id :document_id :title :level :target_page :target_section_id :description]
          :from :document_toc})
    (mapv toc-row->ns)
    (sort-by (juxt :document.toc/document-id :document.toc/target-page
               :document.toc/level :document.toc/title :document.toc/id))
    vec))

(defn qa-corpus-page-nodes [db-info]
  (->> (query! db-info
         {:select [:id :document_id :page_id :type :local_id :content :description]
          :from :page_node})
    (mapv node-row->ns)
    (sort-by (juxt :page.node/document-id :page.node/page-id
               :page.node/local-id :page.node/id))
    vec))

;; =============================================================================
;; Bulk PageIndex ingestion
;; =============================================================================

(defn- build-page-entity [page doc-id]
  (let [page-id (str doc-id "-page-" (:page/index page))
        now (Date.)]
    {:entity {:page/id page-id
              :page/document-id doc-id
              :page/index (:page/index page)
              :page/created-at now
              :page/last-accessed now
              :page/access-count 1.0}
     :page-id page-id}))

(defn- build-page-node-entity [node page-id doc-id]
  (let [node-id (str page-id "-node-" (or (:page.node/id node) (util/uuid)))
        visual? (#{:image :table} (:page.node/type node))
        img-bytes (:page.node/image-data node)
        too-large? (and visual? img-bytes (> (alength ^bytes img-bytes) 5242880))
        image (when (and visual? img-bytes (not too-large?)) img-bytes)]
    (when too-large?
      (trove/log! {:level :warn :data {:node-id node-id :bytes (alength ^bytes img-bytes)}
                   :msg "Skipping page node image-data (exceeds 5MB)"}))
    (cond-> {:page.node/id node-id :page.node/page-id page-id
             :page.node/document-id doc-id :page.node/type (:page.node/type node)}
      (:page.node/id node)                   (assoc :page.node/local-id (:page.node/id node))
      (:page.node/parent-id node)            (assoc :page.node/parent-id (:page.node/parent-id node))
      (:page.node/level node)                (assoc :page.node/level (:page.node/level node))
      (and (not visual?) (:page.node/content node))
      (assoc :page.node/content (:page.node/content node))
      image                                  (assoc :page.node/image-data image)
      (:page.node/description node)          (assoc :page.node/description (:page.node/description node))
      (some? (:page.node/continuation? node))(assoc :page.node/continuation? (:page.node/continuation? node))
      (:page.node/caption node)              (assoc :page.node/caption (:page.node/caption node))
      (:page.node/kind node)                 (assoc :page.node/kind (:page.node/kind node))
      (:page.node/bbox node)                 (assoc :page.node/bbox (pr-str (:page.node/bbox node)))
      (:page.node/group-id node)             (assoc :page.node/group-id (:page.node/group-id node)))))

(defn db-store-pageindex-document!
  "Bulk-insert a full PageIndex document: raw → document → pages + nodes → TOC.
   Returns {:document-id :pages-stored :nodes-stored :toc-entries-stored}."
  [db-info doc]
  (when (ds db-info)
    (let [doc-id (str (util/uuid))]
      (jdbc/with-transaction [tx (ds db-info)]
        (let [tx-info {:datasource tx}
              _ (store-raw-document! tx-info doc-id (pr-str doc))
              _ (store-document! tx-info
                  (cond-> {:document/id doc-id
                           :document/name (:document/name doc)
                           :document/extension (:document/extension doc)
                           :document/certainty-alpha 2.0
                           :document/certainty-beta 1.0}
                    (:document/title doc)      (assoc :document/title (:document/title doc))
                    (:document/abstract doc)   (assoc :document/abstract (:document/abstract doc))
                    (:document/author doc)     (assoc :document/author (:document/author doc))
                    (:document/created-at doc) (assoc :document/created-at (:document/created-at doc))
                    (:document/updated-at doc) (assoc :document/updated-at (:document/updated-at doc))))
              pages (:document/pages doc)
              page-count (count pages)
              node-count (atom 0)]
          (doseq [page pages]
            (let [{:keys [entity page-id]} (build-page-entity page doc-id)]
              (store-page! tx-info entity)
              (doseq [node (:page/nodes page)]
                (when-let [ne (build-page-node-entity node page-id doc-id)]
                  (store-page-node! tx-info ne)
                  (swap! node-count inc)))))
          (doseq [entry (:document/toc doc)]
            (let [entry-id (str doc-id "-toc-" (or (:document.toc/id entry) (util/uuid)))]
              (db-store-toc-entry! tx-info (assoc entry :document.toc/id entry-id) doc-id)))
          {:document-id doc-id
           :pages-stored page-count
           :nodes-stored @node-count
           :toc-entries-stored (count (:document/toc doc))})))))

;; =============================================================================
;; Misc helpers consumed by core.clj stats + query.clj refinement
;; =============================================================================

(defn db-count-document-pages
  "Number of distinct pages for a document (via page_node.page_id)."
  [db-info doc-id]
  (or (some-> (query-one! db-info
                {:select [[[:count :%distinct.page_id] :cnt]]
                 :from :page_node
                 :where [:= :document_id doc-id]})
        :cnt)
    0))

(defn db-entity-type-counts
  "Returns {entity-type-keyword → count} for all entities."
  [db-info]
  (when (ds db-info)
    (let [rows (query! db-info
                 {:select [:type [[:count :*] :cnt]]
                  :from :entity
                  :group-by [:type]})]
      (into {} (map (fn [r] [(->kw-back (:type r)) (:cnt r)])) rows))))

(defn db-stored-docs-for-refinement
  "List docs with their page-node text for LLM refinement context."
  [db-info]
  (when (ds db-info)
    (let [docs (query! db-info {:select [:id :name] :from :document})]
      (mapv (fn [d]
              (let [doc-id (:id d)
                    nodes (query! db-info
                            {:select [:page_id :content]
                             :from :page_node
                             :where [:= :document_id doc-id]})]
                {:id (or doc-id (:name d))
                 :pages (mapv (fn [n]
                                {:page (or (:page_id n) "0")
                                 :text (or (:content n) "")})
                          nodes)}))
        docs))))

(defn db-document-page-nodes-full
  "All page-nodes for a doc-id with FULL content (no truncation), ordered by page-id.
   Used by fetch-document-content in tools.clj to build the combined document text."
  [db-info doc-id]
  (when (and (ds db-info) doc-id)
    (->> (query! db-info
           {:select [:page_id :content]
            :from :page_node
            :where [:= :document_id doc-id]
            :order-by [[:page_id :asc]]})
      (mapv (fn [r] {:page.node/page-id (:page_id r)
                     :page.node/content (:content r)})))))

(defn db-list-queries
  "List :query entities with optional status/min-iterations/limit filters.
   Newest-first. Used by trajectory export pipeline."
  ([db-info] (db-list-queries db-info {}))
  ([db-info {:keys [status limit min-iterations] :or {min-iterations 0}}]
   (when (ds db-info)
     (let [where (cond-> [:and [:= :e.type "query"]
                          [:>= :q.iterations (long min-iterations)]]
                   status (conj [:= :q.status (->kw status)]))
           sql (cond-> {:select [:e.id]
                        :from [[:entity :e]]
                        :join [[:query_attrs :q] [:= :e.id :q.entity_id]]
                        :where where
                        :order-by [[:e.created_at :desc] [:e.id :desc]]}
                 limit (assoc :limit (long limit)))
           ids (mapv :id (query! db-info sql))]
       (fetch-entities db-info ids)))))

(defn db-cited-page-ids
  "Given a set of cited source-ids (may be :page.node/id, :page/id, or :page.node/document-id),
   return the distinct :page/id set they resolve to."
  [db-info cited-source-ids]
  (when (and (ds db-info) (seq cited-source-ids))
    (set (mapv :page_id
           (query! db-info
             {:select-distinct [:page_id]
              :from :page_node
              :where [:and
                      [:or
                       [:in :id cited-source-ids]
                       [:in :page_id cited-source-ids]
                       [:in :document_id cited-source-ids]]
                      [:is-not :page_id nil]]})))))

(defn db-store-claim!
  [db-info claim]
  (when (ds db-info)
    (let [row (cond-> {:id (->id (or (:claim/id claim) (util/uuid)))}
                (:claim/text claim)        (assoc :text (:claim/text claim))
                (:claim/document-id claim) (assoc :document_id (:claim/document-id claim))
                (some? (:claim/page claim))(assoc :page (:claim/page claim))
                (:claim/section claim)     (assoc :section (:claim/section claim))
                (:claim/quote claim)       (assoc :quote (:claim/quote claim))
                (some? (:claim/confidence claim)) (assoc :confidence (double (:claim/confidence claim)))
                (:claim/query-id claim)    (assoc :query_id (->id (:claim/query-id claim)))
                (some? (:claim/verified? claim))  (assoc :verified (if (:claim/verified? claim) 1 0))
                (:claim/verification-verdict claim)(assoc :verification_verdict (:claim/verification-verdict claim))
                (:claim/created-at claim)  (assoc :created_at (->epoch-ms (:claim/created-at claim))))]
      (jdbc/execute! (ds db-info)
        (sql/format {:insert-into :claim :values [row]
                     :on-conflict [:id]
                     :do-update-set (vec (remove #{:id} (keys row)))})))))

;; =============================================================================
;; results->markdown (pure formatter from legacy db.clj)
;; =============================================================================

(defn- str-truncate [s n] (when s (if (> (count s) n) (subs s 0 n) s)))

(defn results->markdown
  "Compact markdown rendering of search results. Accepts a vec of page-nodes or
   a {:pages :toc :entities} map."
  [results]
  (let [{:keys [pages toc entities]}
        (if (map? results) results {:pages results})
        sb (StringBuilder.)]
    (when (seq pages)
      (let [by-page (group-by :page.node/page-id pages)]
        (doseq [[pid nodes] (sort-by key by-page)]
          (.append sb (str "## " (or pid "unknown") "\n"))
          (doseq [n nodes]
            (let [t (some-> (:page.node/type n) name)
                  zone (some-> (:vitality-zone n) name)
                  preview (or (:preview n) "")]
              (.append sb (str "- **" t "**" (when zone (str " [" zone "]")) " " preview "\n"))))
          (.append sb "\n"))))
    (when (seq toc)
      (.append sb "## TOC\n")
      (doseq [e toc]
        (.append sb (str "- " (or (:document.toc/level e) "") " " (or (:document.toc/title e) "")
                      (when-let [p (:document.toc/target-page e)] (str " (p." p ")"))
                      "\n")))
      (.append sb "\n"))
    (when (seq entities)
      (.append sb "## Entities\n")
      (doseq [e entities]
        (.append sb (str "- **" (or (:entity/name e) "") "** ("
                      (some-> (:entity/type e) name) ") "
                      (str-truncate (or (:entity/description e) "") 80) "\n")))
      (.append sb "\n"))
    (str sb)))
