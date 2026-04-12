(ns com.blockether.svar.internal.rlm
  "Recursive Language Model (RLM) for processing arbitrarily large contexts."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.rlm.core :as rlm-core]
   [com.blockether.svar.internal.rlm.db :as rlm-db]
   [com.blockether.svar.internal.rlm.git :as rlm-git]
   [com.blockether.svar.internal.rlm.pageindex :as rlm-pageindex]
   [com.blockether.svar.internal.rlm.pageindex.markdown :as markdown]
   [com.blockether.svar.internal.rlm.pageindex.pdf :as pdf]
   [com.blockether.svar.internal.rlm.pageindex.vision :as vision]
   [com.blockether.svar.internal.rlm.routing :as rlm-routing]
   [com.blockether.svar.internal.rlm.query :as rlm-query]
   [com.blockether.svar.internal.rlm.schema :as schema]
   [com.blockether.svar.internal.rlm.trace :as rlm-trace]
   [com.blockether.svar.internal.rlm.trajectory :as trajectory]
   [com.blockether.svar.internal.rlm.qa :as rlm-qa]
   [com.blockether.svar.internal.rlm.qa-manifest :as rlm-qa-manifest]
   [com.blockether.svar.internal.rlm.skills :as rlm-skills]
   [com.blockether.svar.internal.rlm.tools :as rlm-tools]
   [com.blockether.svar.internal.util :as util]
   [taoensso.trove :as trove])
  (:import
   [java.security MessageDigest]
   [java.time Instant]
   [java.util Date]))

(def RLM_SCHEMA schema/RLM_SCHEMA)
(def MAX_ITERATIONS schema/MAX_ITERATIONS)
(def DEFAULT_RECURSION_DEPTH schema/DEFAULT_RECURSION_DEPTH)
(def ^:dynamic *max-recursion-depth* schema/*max-recursion-depth*)
;; Use schema/*rlm-ctx* directly — do NOT redefine as a separate dynamic var,
;; as binding a local alias won't propagate to core.clj which imports schema/*rlm-ctx*.

(def GENERATION_PERSONAS schema/GENERATION_PERSONAS)
(def DEDUP_SPEC schema/DEDUP_SPEC)
(def REVISION_SPEC schema/REVISION_SPEC)
(def CHUNK_SELECTION_SPEC schema/CHUNK_SELECTION_SPEC)
(def QUESTIONIFY_SPEC schema/QUESTIONIFY_SPEC)
(def VERIFICATION_SPEC schema/VERIFICATION_SPEC)

;; Q-value reward constants + helpers moved to rlm.query during Phase 5 extraction.

(defn create-env
  "Creates an RLM environment (component) for document ingestion and querying.
   
    The environment holds:
    - In-memory store for documents and conversation history
    - LLM configuration for queries
    - SCI sandbox context with custom bindings
   
   Usage:
   ```clojure
   (def router (llm/make-router providers))
   (def env (rlm/create-env router {:db \"/tmp/my-rlm\"}))
   (rlm/register-env-fn! env 'my-fn (fn [x] (* x 2))
     {:doc \"Doubles a number\"
      :params [{:name \"x\" :type :int :required true :description \"Number to double\"}]
      :returns {:type :int :description \"x * 2\"}})
   (rlm/register-env-def! env 'MAX_RETRIES 3
     {:doc \"Maximum retry attempts\" :returns {:type :int}})
   (rlm/ingest-to-env! env documents)
   (rlm/query-env! env \"What is X?\")
   (rlm/dispose-env! env)
   ```
   
   Params:
   `router` - Required. Router from llm/make-router, pre-built.
   `opts` - Map with:
   - :db - DB spec (required, explicit):
       nil            — no DB (SCI execution only, no document storage)
       :temp          — ephemeral temp DB (deleted on dispose)
       \"path\"         — persistent DB at path (survives sessions)
       {:path \"path\"} — persistent DB at path
       {:conn c}      — external Datalevin connection (auto-merges RLM_SCHEMA)

   Returns:
   RLM environment map (component). Pass to register-env-fn!, register-env-def!, ingest-to-env!, query-env!, dispose-env!."
  [router {:keys [db conversation]}]
  (when-not router
    (anomaly/incorrect! "Missing router" {:type :rlm/missing-router}))
  (let [depth-atom (atom 0)
        ;; Constants and values registered via register-env-def! — flash-bound
        ;; into SCI on each query-env! call. Fns go through tool-registry-atom.
        custom-bindings-atom (atom {})
        custom-docs-atom (atom [])
        ;; Canonical tool registry: {sym → {:fn :doc :params :returns :hooks
        ;;   {:before [{:id :fn}] :after [...] :wrap [...]}}}. Populated by
        ;; register-env-fn! with hook normalization + id-based chain merge.
        ;; On each query-env! call, registered fns are re-flashed into SCI
        ;; as hook-wrapped closures so per-query :hooks + cancel-atom are
        ;; visible to the pipeline.
        tool-registry-atom (atom {})
        db-info (rlm-db/create-rlm-conn db)
        db-info-atom (when db-info (atom db-info))
        var-index-cache-atom (atom {:revision -1 :index nil})
        var-index-revision-atom (atom 0)
        qa-corpus-snapshot-cache-atom (atom nil)
        qa-corpus-snapshot-stats-atom (atom {:hits 0 :misses 0 :last-digest-ms nil :last-revision 0})
        skill-registry-map (rlm-skills/load-skills {})
        skill-registry-atom (atom skill-registry-map)
        ;; Ingest skills into Datalevin as :skill documents (searchable)
        _ (when db-info-atom
            (rlm-skills/ingest-skills! @db-info-atom skill-registry-map))
        rlm-env-atom (atom nil) ;; filled after env construction — sub-rlm-query reads it for iterated path
        sub-rlm-query-fn (rlm-routing/make-routed-sub-rlm-query-fn {} depth-atom router skill-registry-atom rlm-env-atom)
        cheap-sub-rlm-query-fn (rlm-routing/make-routed-sub-rlm-query-fn {:optimize :cost} depth-atom router skill-registry-atom rlm-env-atom)
        env-id (str (util/uuid))
        root-model (or (rlm-routing/resolve-root-model router) "unknown")
        has-reasoning? (boolean (rlm-routing/provider-has-reasoning? router))
        system-prompt (rlm-core/build-system-prompt {:has-reasoning? has-reasoning?
                                                     :skill-registry skill-registry-map})
        resolved-conversation-ref (rlm-db/db-resolve-conversation-ref db-info conversation)
        conversation-ref (or resolved-conversation-ref
                           (rlm-db/store-conversation! db-info
                             {:env-id env-id :model root-model :system-prompt system-prompt}))
        conversation-ref-atom (atom conversation-ref)
        {:keys [sci-ctx sandbox-ns initial-ns-keys]} (rlm-tools/create-sci-context nil cheap-sub-rlm-query-fn db-info-atom conversation-ref-atom @custom-bindings-atom)
        env {:env-id env-id
             :conversation-ref conversation-ref
             :conversation-ref-atom conversation-ref-atom
             :depth-atom depth-atom
             :custom-bindings-atom custom-bindings-atom
             :custom-docs-atom custom-docs-atom
             :tool-registry-atom tool-registry-atom
             :db-info-atom db-info-atom
             :var-index-cache-atom var-index-cache-atom
             :var-index-revision-atom var-index-revision-atom
             :qa-corpus-snapshot-cache-atom qa-corpus-snapshot-cache-atom
             :qa-corpus-snapshot-stats-atom qa-corpus-snapshot-stats-atom
             :sci-ctx sci-ctx
             :sandbox-ns sandbox-ns
             :initial-ns-keys initial-ns-keys
             :router router
             :sub-rlm-query-fn sub-rlm-query-fn
             :cheap-sub-rlm-query-fn cheap-sub-rlm-query-fn
             :skill-registry-atom skill-registry-atom}]
    (reset! rlm-env-atom env)
    env))

(defn- invalidate-qa-corpus-snapshot-cache!
  [env]
  (rlm-qa-manifest/invalidate-qa-corpus-snapshot-cache! env))

(def qa-corpus-snapshot-stats
  "Returns QA corpus snapshot cache stats for observability.
   Shape: {:hits N :misses N :last-digest-ms ms|nil :last-revision rev}."
  rlm-qa-manifest/qa-corpus-snapshot-stats)

(defn register-env-fn!
  "Registers a function in the RLM environment's SCI sandbox.

   The function becomes available to the LLM during code execution.
   Documentation is included in the system prompt so the LLM knows how to use it.

   Params:
   `env` - RLM environment from create-env.
   `sym` - Symbol. The function name (e.g., 'fetch-weather).
   `f` - Function. The implementation.
   `tool-def` - Map. Structured tool definition:
     - :doc - String. Description of what the function does.
     - :params - Vector of parameter maps, each with:
         :name - String. Parameter name.
         :type - Keyword. :string, :int, :keyword, :map, :vector, :any, etc.
         :required - Boolean. Whether the parameter is required (default true).
         :description - String. What the parameter is for.
         :default - Any. Default value (rendered as-is, can be a map, keyword, etc.).
     - :returns - Map. Return type description:
         {:type :map :description \"Weather data with :temp, :humidity\"}
     - :examples - Vector of strings. Usage examples.

   Usage:
   ```clojure
   (register-env-fn! env 'fetch-weather
     (fn [city & [{:keys [units]}]] ...)
     {:doc \"Fetches current weather for a city\"
      :params [{:name \"city\" :type :string :required true :description \"City name\"}
               {:name \"opts\" :type :map :required false
                :description \"Options map with :units (:celsius or :fahrenheit)\"
                :default {:units :celsius}}]
      :returns {:type :map :description \"Weather data with :temp, :humidity, :conditions\"}
      :examples [\"(fetch-weather \\\"Berlin\\\")\"
                 \"(fetch-weather \\\"Tokyo\\\" {:units :fahrenheit})\"]})
   ```

   Hook v3 support: `tool-def` may include :before / :after / :wrap hook
   chains to intercept calls to this tool. Each chain is a single fn, a
   single {:id :fn} map, or a vec of those. Hooks registered on the same
   :id replace in place; new :ids append to the chain. Layering across
   multiple register-env-fn! calls is supported.

   Per-tool hook return conventions (see rlm.tools docstrings):
     :before → {:args v} | {:skip v} | {:error e} | nil
     :after  → {:result v} | {:error e} | {:result v :error nil} | nil
     :wrap   → ring-style middleware, vec-LAST = outermost

   Returns:
   The environment (for chaining)."
  [env sym f tool-def]
  (when-not (:custom-bindings-atom env)
    (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))
  (when-not (symbol? sym)
    (anomaly/incorrect! "sym must be a symbol" {:type :rlm/invalid-sym :sym sym}))
  (when-not (fn? f)
    (anomaly/incorrect! "f must be a function" {:type :rlm/invalid-fn}))
  (when-not (map? tool-def)
    (anomaly/incorrect! "tool-def must be a map" {:type :rlm/invalid-tool-def}))
  ;; Canonicalize hook chains + merge into any prior registration of `sym`.
  ;; Fns live ONLY in tool-registry-atom — NOT in custom-bindings-atom — so
  ;; the per-query flash at query-env! doesn't clobber hook-wrapped bindings.
  (rlm-tools/register-tool-def! (:tool-registry-atom env) sym
    (assoc tool-def :fn f :sym sym :type :fn))
  ;; Immediately inject a HOOK-WRAPPED fn into SCI so the tool is callable
  ;; before the next query-env! re-flash. The wrapper closes over the
  ;; registry-atom so late-merged hooks are picked up on each call.
  (when-let [sci-ctx (:sci-ctx env)]
    (rlm-tools/sci-update-binding! sci-ctx sym
      (rlm-tools/wrap-tool-for-sci env sym f (:tool-registry-atom env))))
  (swap! (:custom-docs-atom env) conj (assoc tool-def :type :fn :sym sym))
  env)

(defn register-hook!
  "Attach a hook to an existing tool's chain.

   Params:
   `env`  - RLM environment.
   `sym`  - Tool symbol (must already be registered via register-env-fn!).
   `opts` - Map with:
     :stage - One of :before / :after / :wrap.
     :id    - Keyword, unique within the stage+tool. Replaces on collision.
     :fn    - Hook function. Shape depends on stage:
              :before — (fn [invocation] ...) → {:args new-args} or {:short-circuit val}
              :after  — (fn [outcome] ...) → {:result new-result} or passthrough
              :wrap   — (fn [handler] (fn [args] ...)) middleware

   Returns the env."
  [env sym {:keys [stage id fn]}]
  (rlm-tools/register-tool-def! (:tool-registry-atom env) sym
    {stage [{:id id :fn fn}]})
  env)

(defn unregister-hook!
  "Remove a per-tool hook entry by :id.

   Params:
   `env`   - RLM environment.
   `sym`   - Tool symbol.
   `stage` - One of :before / :after / :wrap.
   `id`    - Hook id to remove.

   Returns true if a matching entry was removed, false otherwise."
  [env sym stage id]
  (rlm-tools/unregister-hook! (:tool-registry-atom env) sym stage id))

(defn list-tool-hooks
  "Return a map describing the hook chains registered for `sym`:
     {:before [{:id :position :fn-name}] :after [...] :wrap [...]}
   Returns nil when `sym` has no registered tool-def."
  [env sym]
  (rlm-tools/list-tool-hooks (:tool-registry-atom env) sym))

(defn list-registered-tools
  "Return a vec of {:sym :hook-counts} maps describing every tool
   registered via register-env-fn!."
  [env]
  (rlm-tools/list-registered-tools (:tool-registry-atom env)))

(defn register-env-def!
  "Registers a constant/value in the RLM environment's SCI sandbox.

   The value becomes available to the LLM during code execution.
   Documentation is included in the system prompt so the LLM knows about it.

   Params:
   `env` - RLM environment from create-env.
   `sym` - Symbol. The constant name (e.g., 'MAX_RETRIES).
   `value` - Any value. The constant value.
   `tool-def` - Map. Structured definition:
     - :doc - String. Description.
     - :returns - Map. Type/value description:
         {:type :int :description \"Maximum retry attempts\"}

   Returns:
   The environment (for chaining)."
  [env sym value tool-def]
  (when-not (:custom-bindings-atom env)
    (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))
  (when-not (symbol? sym)
    (anomaly/incorrect! "sym must be a symbol" {:type :rlm/invalid-sym :sym sym}))
  (when-not (map? tool-def)
    (anomaly/incorrect! "tool-def must be a map" {:type :rlm/invalid-tool-def}))
  (swap! (:custom-bindings-atom env) assoc sym value)
  ;; Inject into live SCI ctx
  (when-let [sci-ctx (:sci-ctx env)]
    (rlm-tools/sci-update-binding! sci-ctx sym value))
  (swap! (:custom-docs-atom env) conj (assoc tool-def :type :def :sym sym))
  env)

(defn ingest-to-env!
  "Ingests PageIndex documents into an RLM environment.
   
   Stores the complete document structure exactly as PageIndex produces it:
   - Document metadata
   - All pages
   - All page nodes (paragraphs, headings, images, tables)
   - All TOC entries
   
   Can be called multiple times to add more documents.
   
   Params:
   `env` - RLM environment from create-env.
   `documents` - Vector of PageIndex documents (spec-validated).
   `opts` - Optional. Map with extraction options:
     - :extract-entities? - Enable entity extraction (default false)
     - :extraction-model - Model for extraction (default: env's default-model)
     - :max-extraction-pages - Page limit per doc (default 50)
     - :max-vision-rescan-nodes - Cap on vision re-scans per doc (default 10)
   
   Returns:
   Vector of ingestion results, one per document:
   [{:document-id \"...\" :pages-stored N :nodes-stored N :toc-entries-stored N 
     :entities-extracted N :relationships-extracted N :pages-processed N 
     :extraction-errors N :visual-nodes-scanned N}] (extraction fields only if enabled)"
  ([env documents] (ingest-to-env! env documents {}))
  ([env documents opts]
   (when-not (:db-info-atom env)
     (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))
   (when-not (schema/valid-documents? documents)
     (anomaly/incorrect! "Invalid documents - must be vector of PageIndex documents"
       {:type :rlm/invalid-documents
        :explanation (schema/explain-documents documents)}))
   (let [db-info @(:db-info-atom env)
         rlm-router (:router env)
         extract? (:extract-entities? opts false)
         base-results (mapv #(rlm-db/db-store-pageindex-document! db-info %) documents)
         results (if extract?
                   (mapv (fn [doc base-result]
                           (let [extraction-result (rlm-core/extract-entities-from-document! db-info doc rlm-router opts)]
                             (merge base-result extraction-result)))
                     documents base-results)
                   base-results)]
     (when (seq documents)
       (let [revision (rlm-db/bump-corpus-revision! db-info)]
          ;; Invalidate cached corpus digest after corpus mutation.
         (invalidate-qa-corpus-snapshot-cache! env)
         (when-let [stats-atom (:qa-corpus-snapshot-stats-atom env)]
           (swap! stats-atom assoc :last-revision revision))))
     results)))

(defn ingest-git!
  "Ingest git commits from a repository into an RLM environment.

   Opens the repo with JGit (no shell-out), reads commits matching the given
   opts, stores them as `:event` entities (with `:person` authors and `:file`
   entities + relationships), AND persists a `:repo` entity to the DB keyed
   on `:repo-name`. The repo is then closed — the `:repo` entity holds the
   filesystem path, so git SCI tools re-open it lazily per call.

   **No atoms touched. Everything lives in Datalevin.** As a consequence:
   * Persistent DBs resume with attached repos intact across env restarts.
   * `dispose-env!` has nothing git-specific to clean up.
   * Multi-repo ingestion = multiple `ingest-git!` calls with distinct
     `:repo-name` values; each call writes a `:repo` entity.

   SCI sandbox exposes these tools (always bound when a DB is present; they
   error cleanly when no `:repo` entities exist):

     ;; DB-backed (cross-repo; :document-id scopes)
     (git-search-commits opts)   (git-commit-history opts)
     (git-commits-by-ticket ref) (git-commit-parents sha)

     ;; JGit-backed (opens/closes the repo per call)
     (git-file-history path)     (git-blame path from to)
     (git-commit-diff sha)

   Path/SHA auto-dispatch in multi-repo mode: absolute-path match for
   path-based tools, object-presence check across `:repo/path` for
   sha-based tools. Relative paths or ref names like `HEAD` are
   ambiguous in multi-repo and throw.

   Params:
   `env` - RLM environment from create-env.
   `opts` - Map with:
     - :repo-path - String, required. Path to the repo.
     - :repo-name - String, optional. Default = basename of :repo-path.
     - :n         - Integer, optional. Max commits to ingest (default 100).
     - :since     - String, optional. ISO-8601 date; only commits on/after.
     - :since-sha - String, optional. Only commits newer than this SHA.
     - :path      - String, optional. Restrict to commits touching this path.
     - :author    - String, optional. Restrict to this author email.

   Returns:
   Map with {:events-stored :people-stored :files-stored :relationships-stored
             :repo-path :repo-name :head :commits-ingested}."
  [env {:keys [repo-path repo-name n since since-sha path author]
        :or {n 100}
        :as _opts}]
  (when-not (:db-info-atom env)
    (anomaly/incorrect! "Invalid RLM environment (no DB)"
      {:type :rlm/invalid-env}))
  (when-not repo-path
    (anomaly/incorrect! ":repo-path is required"
      {:type :rlm/missing-repo-path}))
  (let [resolved-name (or repo-name
                        (.getName (java.io.File. (str repo-path))))
        resolved-path (.getAbsolutePath (java.io.File. (str repo-path)))
        repo (rlm-git/open-repo resolved-path)]
    (when-not repo
      (anomaly/not-found! (str "Not a git repository: " resolved-path)
        {:type :rlm/not-a-git-repo :repo-path resolved-path}))
    (try
      (let [db-info @(:db-info-atom env)
            commits (rlm-git/read-commits repo
                      (cond-> {:n n}
                        since     (assoc :since since)
                        since-sha (assoc :since-sha since-sha)
                        path      (assoc :path path)
                        author    (assoc :author author)))
            ingest-result (rlm-git/ingest-commits! db-info commits {:repo-name resolved-name})
            head (rlm-git/head-info repo)
            commits-ingested (:events-stored ingest-result)]
        ;; Persist the repo attachment as a :repo entity so git SCI tools
        ;; can look it up and open it lazily on each call.
        (rlm-db/db-store-repo! db-info
          {:name resolved-name
           :path resolved-path
           :head-sha (:sha head)
           :head-short (:short head)
           :branch (:branch head)
           :commits-ingested commits-ingested})
        (trove/log! {:level :info :id ::ingest-git
                     :data {:repo-path resolved-path
                            :repo-name resolved-name
                            :commits-ingested commits-ingested
                            :head head}
                     :msg "git commits ingested"})
        (merge ingest-result
          {:repo-path resolved-path
           :repo-name resolved-name
           :commits-ingested commits-ingested
           :head head}))
      (finally (.close repo)))))

(defn dispose-env!
  "Disposes an RLM environment and releases resources.

    For persistent DBs (created with :path), data is preserved.
   For disposable DBs, all data is deleted.

   Git tools have no resources to release here — `ingest-git!` opens and
   closes repos inside its own body, and SCI git tools open/close repos
   per call via `with-open`. Nothing lives on the env atoms.

   Params:
   `env` - RLM environment from create-env."
  [env]
  (when-let [db-info-atom (:db-info-atom env)]
    (rlm-db/dispose-rlm-conn! @db-info-atom)))

;; =============================================================================
;; query-env! — extracted to rlm.query, re-exposed here for public API stability
;; =============================================================================

(def query-env!
  "Runs a query against an RLM environment using iterative code execution.
   Delegates to rlm.query. See there for full docstring and options."
  rlm-query/query-env!)

(defn list-queries
  "Lists query records from an RLM environment."
  [env & [opts]]
  (trajectory/list-queries @(:db-info-atom env) opts))

(defn export-trajectories!
  "Exports filtered trajectories as JSONL for fine-tuning."
  [env output-dir & [opts]]
  (trajectory/export-trajectories! @(:db-info-atom env) output-dir opts))

;; =============================================================================
;; Trace Pretty Printing
;; =============================================================================

(def format-trace
  "Formats an RLM execution trace into a string. Internal helper."
  rlm-trace/format-trace)

(def pprint-trace
  "Pretty-prints an RLM execution trace to stdout for debugging."
  rlm-trace/pprint-trace)

(def print-trace
  "Prints an RLM execution trace to stdout. Alias for pprint-trace."
  rlm-trace/print-trace)

;; =============================================================================
;; QA pipeline — delegated to rlm.qa / rlm.qa-manifest
;; =============================================================================

;; Private fn delegates (tests access these via #'sut/...)
#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private qa-corpus-snapshot rlm-qa-manifest/qa-corpus-snapshot)
#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private write-qa-manifest! rlm-qa-manifest/write-qa-manifest!)
#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private compute-distribution rlm-qa/compute-distribution)
#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private deduplicate-questions rlm-qa/deduplicate-questions)
#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private filter-verified-questions rlm-qa/filter-verified-questions)
#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private build-generation-prompt rlm-qa/build-generation-prompt)
#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private build-verification-prompt rlm-qa/build-verification-prompt)

(defn query-env-qa!
  "Generates question-answer pairs from ingested documents.
   See rlm.qa/query-env-qa! for full documentation."
  ([env] (query-env-qa! env {}))
  ([env opts]
   (let [renamed-opts (if (contains? opts :count)
                        (-> opts
                          (assoc :target-count (:count opts))
                          (dissoc :count))
                        opts)]
     (rlm-qa/query-env-qa! env query-env! renamed-opts))))

(def save-qa! rlm-qa/save-qa!)

;; Keep build-toc-based-selection-prompt public (used in README/tests)
(def build-toc-based-selection-prompt rlm-qa/build-toc-based-selection-prompt)

;; =============================================================================
;; PageIndex Core (merged from internal.pageindex.core)
;; =============================================================================

;; Ensure java.time.Instant prints as #inst in EDN (pprint only knows java.util.Date)
(defmethod print-method Instant [^Instant inst ^java.io.Writer w]
  (print-method (Date/from inst) w))

;; =============================================================================
;; PageIndex — pure helper delegates
;; =============================================================================

(def normalize-page-spec rlm-pageindex/normalize-page-spec)
(def filter-pages rlm-pageindex/filter-pages)
(def group-continuations rlm-pageindex/group-continuations)
(def write-document-edn! rlm-pageindex/write-document-edn!)
(def read-document-edn rlm-pageindex/read-document-edn)
(def load-index rlm-pageindex/load-index)
(def inspect rlm-pageindex/inspect)

;; =============================================================================
;; Helper: Extract Document Name
;; =============================================================================

(defn- extract-doc-name
  "Extracts document name from file path (without extension).

   Params:
   `file-path` - String. Path to file.

   Returns:
   String. Document name without extension."
  [file-path]
  (-> file-path
    (io/file)
    (.getName)
    (str/replace #"\.(pdf|md|markdown|txt|text)$" "")))

(defn- extract-extension
  "Extracts file extension from file path.

   Params:
   `file-path` - String. Path to file.

   Returns:
   String. File extension (e.g., \"pdf\", \"md\", \"txt\")."
  [file-path]
  (let [name (.getName (io/file file-path))]
    (when-let [idx (str/last-index-of name ".")]
      (subs name (inc (long idx))))))

;; =============================================================================
;; File Type Detection (moved from text.clj)
;; =============================================================================

(defn- file-type
  "Determines the type of file based on extension.

   Params:
   `file-path` - String. Path to file.

   Returns:
   Keyword - :pdf, :markdown, :text, :image, or :unknown."
  [file-path]
  (let [ext (some-> (extract-extension file-path) str/lower-case)]
    (cond
      (= "pdf" ext) :pdf
      (#{"md" "markdown"} ext) :markdown
      (#{"txt" "text"} ext) :text
      (#{"png" "jpg" "jpeg" "gif" "bmp" "webp"} ext) :image
      :else :unknown)))

;; =============================================================================
;; Supported File Types
;; =============================================================================

(def ^:private SUPPORTED_EXTENSIONS
  "Set of supported file extensions."
  #{".pdf" ".md" ".markdown" ".txt" ".text" ".png" ".jpg" ".jpeg" ".gif" ".bmp" ".webp"})

(defn- supported-extension?
  "Returns true if file path has a supported extension."
  [file-path]
  (let [lower-path (str/lower-case file-path)]
    (some #(str/ends-with? lower-path %) SUPPORTED_EXTENSIONS)))

(defn- file-path?
  "Returns true if input is a valid file path (file must exist).

   We don't use heuristics like 'contains /' because content strings
   can contain paths (URLs, code examples, etc.). The only reliable
   check is whether the file actually exists."
  [input]
  (try
    (let [file (io/file input)]
      (and (.exists file)
        (.isFile file)))
    (catch Exception _
      ;; Invalid path (e.g., too long, invalid characters)
      false)))

;; =============================================================================
;; ID Translation (Local IDs → Global UUIDs)
;; =============================================================================

(defn- translate-page-ids
  "Translates local node IDs to globally unique UUIDs for a single page.

   Each page extraction produces local IDs (1, 2, 3...) that collide across pages.
   This function:
   1. Creates a mapping of local-id -> UUID for all nodes on the page
   2. Updates all :page.node/id and :document.toc/id to use UUIDs
   3. Updates all parent-id references to use UUIDs
   4. Updates all target-section-id references to use UUIDs

   Handles both :page.node/* namespace (most nodes) and :document.toc/* namespace (TOC entries).

   Params:
   `page` - Map with :page/index and :page/nodes.

   Returns:
   Updated page with all IDs translated to UUIDs."
  [page]
  (let [nodes (:page/nodes page)
        node-id-mapping (reduce
                          (fn [acc node]
                            (if-let [local-id (:page.node/id node)]
                              (assoc acc local-id (str (util/uuid)))
                              acc))
                          {} nodes)
        toc-id-mapping (reduce
                         (fn [acc node]
                           (if-let [local-id (:document.toc/id node)]
                             (assoc acc local-id (str (util/uuid)))
                             acc))
                         {} nodes)
        translated-nodes (mapv
                           (fn [node]
                             (cond-> node
                               (:page.node/id node)
                               (assoc :page.node/id (get node-id-mapping (:page.node/id node)))

                               (and (:page.node/parent-id node)
                                 (get node-id-mapping (:page.node/parent-id node)))
                               (assoc :page.node/parent-id (get node-id-mapping (:page.node/parent-id node)))

                               (and (:page.node/target-section-id node)
                                 (get node-id-mapping (:page.node/target-section-id node)))
                               (assoc :page.node/target-section-id (get node-id-mapping (:page.node/target-section-id node)))

                               (:document.toc/id node)
                               (assoc :document.toc/id (get toc-id-mapping (:document.toc/id node)))

                               (and (:document.toc/parent-id node)
                                 (get toc-id-mapping (:document.toc/parent-id node)))
                               (assoc :document.toc/parent-id (get toc-id-mapping (:document.toc/parent-id node)))

                               (and (:document.toc/target-section-id node)
                                 (get node-id-mapping (:document.toc/target-section-id node)))
                               (assoc :document.toc/target-section-id (get node-id-mapping (:document.toc/target-section-id node)))))
                           nodes)]
    (assoc page :page/nodes translated-nodes)))

(defn- translate-all-ids
  "Translates all local node IDs to globally unique UUIDs across all pages.

   Params:
   `pages` - Vector of page maps.

   Returns:
   Vector of pages with all IDs translated to UUIDs."
  [pages]
  (mapv translate-page-ids pages))

;; =============================================================================
;; TOC Post-Processing
;; =============================================================================

(defn- collect-all-nodes
  "Collects all nodes from all pages into a flat sequence."
  [pages]
  (mapcat :page/nodes pages))

(defn- has-toc-entries?
  "Returns true if any TocEntry nodes exist in the pages."
  [pages]
  (boolean (some #(= :toc-entry (:document.toc/type %)) (collect-all-nodes pages))))

(defn- heading-level->toc-level
  "Converts heading level (h1, h2, etc.) to TOC level (l1, l2, etc.)."
  [heading-level]
  (when heading-level
    (str "l" (subs heading-level 1))))

(defn- build-toc-from-structure
  "Builds TOC entries from Section/Heading structure."
  [pages]
  (let [all-nodes (vec (collect-all-nodes pages))
        section-headings (reduce
                           (fn [acc node]
                             (if (and (= :heading (:page.node/type node))
                                   (:page.node/parent-id node))
                               (assoc acc (:page.node/parent-id node) node)
                               acc))
                           {}
                           all-nodes)
        sections (filter #(= :section (:page.node/type %)) all-nodes)
        section-page-index (reduce
                             (fn [acc {:keys [page/index page/nodes]}]
                               (reduce
                                 (fn [acc2 node]
                                   (if (= :section (:page.node/type node))
                                     (assoc acc2 (:page.node/id node) index)
                                     acc2))
                                 acc
                                 nodes))
                             {}
                             pages)]
    (vec
      (keep
        (fn [section]
          (when-let [heading (get section-headings (:page.node/id section))]
            {:document.toc/type :toc-entry
             :document.toc/id (str (util/uuid))
             :document.toc/parent-id nil
             :document.toc/title (:page.node/content heading)
             :document.toc/description (:page.node/description section)
             :document.toc/target-page (get section-page-index (:page.node/id section))
             :document.toc/target-section-id (:page.node/id section)
             :document.toc/level (heading-level->toc-level (:page.node/level heading))}))
        sections))))

(defn- link-toc-entries
  "Links existing TocEntry nodes to matching Section nodes."
  [pages]
  (let [all-nodes (vec (collect-all-nodes pages))
        section-by-id (reduce
                        (fn [acc node]
                          (if (= :section (:page.node/type node))
                            (assoc acc (:page.node/id node) node)
                            acc))
                        {}
                        all-nodes)
        heading->section (reduce
                           (fn [acc node]
                             (if (and (= :heading (:page.node/type node))
                                   (:page.node/content node)
                                   (:page.node/parent-id node))
                               (let [normalized (-> (:page.node/content node)
                                                  str/trim
                                                  str/lower-case)]
                                 (assoc acc normalized (:page.node/parent-id node)))
                               acc))
                           {}
                           all-nodes)]
    (mapv
      (fn [page]
        (update page :page/nodes
          (fn [nodes]
            (mapv
              (fn [node]
                (if (and (= :toc-entry (:document.toc/type node))
                      (nil? (:document.toc/target-section-id node))
                      (:document.toc/title node))
                  (let [normalized-title (-> (:document.toc/title node)
                                           str/trim
                                           str/lower-case)
                        section-id (get heading->section normalized-title)
                        section (when section-id (get section-by-id section-id))]
                    (if section-id
                      (cond-> node
                        true (assoc :document.toc/target-section-id section-id)
                        (:page.node/description section) (assoc :document.toc/description (:page.node/description section)))
                      node))
                  node))
              nodes))))
      pages)))

(defn- postprocess-toc
  "Post-processes pages to ensure TOC exists and is properly linked."
  [pages]
  (if (has-toc-entries? pages)
    (let [linked-pages (link-toc-entries pages)
          toc-entries (vec (filter #(= :toc-entry (:document.toc/type %))
                             (collect-all-nodes linked-pages)))
          pages-without-toc (mapv
                              (fn [page]
                                (update page :page/nodes
                                  (fn [nodes]
                                    (filterv #(not= :toc-entry (:document.toc/type %)) nodes))))
                              linked-pages)]
      (trove/log! {:level :debug :data {:toc-entries (count toc-entries)}
                   :msg "Linked existing TOC entries to sections"})
      {:pages pages-without-toc
       :toc toc-entries})
    (let [generated-toc (build-toc-from-structure pages)]
      (trove/log! {:level :debug :data {:generated-entries (count generated-toc)}
                   :msg "Generated TOC from document structure"})
      {:pages pages
       :toc generated-toc})))

;; =============================================================================
;; Document Abstract Generation
;; =============================================================================

(defn- collect-section-descriptions
  "Collects all :page.node/description values from Section nodes across all pages."
  [pages]
  (->> pages
    (mapcat :page/nodes)
    (filter #(= :section (:page.node/type %)))
    (keep :page.node/description)
    (filter seq)
    vec))

(defn- generate-document-abstract
  "Generates a document-level abstract from all section descriptions."
  [pages {:keys [rlm-router text-model]}]
  (let [descriptions (collect-section-descriptions pages)]
    (when (seq descriptions)
      (trove/log! {:level :info :data {:section-count (count descriptions)}
                   :msg "Generating document abstract from section descriptions"})
      (let [combined-text (str/join "\n\n" descriptions)
            abstracts (llm/abstract! rlm-router (cond-> {:text combined-text
                                                         :strategy :root
                                                         :target-length 150
                                                         :iterations 3}
                                                  text-model (assoc :routing {:model text-model})))]
        (when-let [iterations (seq (:result abstracts))]
          (let [abstract (:summary (last iterations))]
            (trove/log! {:level :info :data {:abstract-length (count abstract)}
                         :msg "Document abstract generated"})
            abstract))))))

;; =============================================================================
;; Text Extraction
;; =============================================================================

(defn- detect-input-type
  "Detects the type of input for build-index dispatch."
  [input opts]
  (cond
    (:content-type opts) :string
    (file-path? input) :path
    :else :string))

(defn- extract-text
  "Extract text from document. Routes to appropriate extractor based on file type."
  [file-path opts]
  (let [ftype (file-type file-path)]
    (when (= :unknown ftype)
      (let [extension (extract-extension file-path)]
        (anomaly/unsupported! (str "Unsupported file type: " (or extension "unknown"))
          {:type :svar.pageindex/unsupported-file-type
           :file file-path
           :extension extension
           :supported-extensions SUPPORTED_EXTENSIONS})))
    (trove/log! {:level :info :data {:file file-path :type ftype}
                 :msg "Extracting text from document"})
    (let [pdf-opts (if (and (= :pdf ftype) (:pages opts))
                     (let [total (pdf/page-count file-path)
                           page-set (normalize-page-spec (:pages opts) total)]
                       (assoc opts :page-set page-set))
                     opts)
          [page-list duration-ms]
          (util/with-elapsed
            (case ftype
              :pdf (vision/extract-text-from-pdf file-path pdf-opts)
              :markdown (markdown/markdown-file->pages file-path)
              :text (vision/extract-text-from-text-file file-path opts)
              :image (vision/extract-text-from-image-file file-path opts)))]
      (trove/log! {:level :info :data {:pages (count page-list)
                                       :type ftype
                                       :duration-ms duration-ms}
                   :msg "Text extraction complete"})
      page-list)))

;; =============================================================================
;; Main API - Multimethod
;; =============================================================================

(defmulti build-index
  "Builds an index from a document by extracting content as nodes.

   Multimethod that dispatches based on input type:
   - `:path` - File path (auto-detects type from extension: .pdf, .md, .txt)
   - `:string` - Raw string content (requires :content-type in opts)

   Supported file types:
   - PDF (.pdf) - Uses vision LLM for node-based extraction
   - Markdown (.md, .markdown) - Parses headings as heading/paragraph nodes
   - Plain text (.txt, .text) - Chunks by paragraphs into paragraph nodes

   Post-processing:
   - If document has TOC pages, extracts TocEntry nodes and links to Sections
   - If no TOC exists, generates one from Section/Heading structure

     Params:
     `router` - Router from llm/make-router.
     `input` - String. File path or raw content.
     `opts` - Optional map with:
       ;; For dispatch (string input)
       `:content-type` - Keyword. Required for string input: :md, :markdown, :txt, :text
       `:doc-name` - String. Document name (required for string input).

       ;; For metadata (string input only - PDF extracts from file)
       `:doc-title` - String. Document title.
       `:doc-author` - String. Document author.
       `:created-at` - Instant. Creation date.
       `:updated-at` - Instant. Modification date.

       ;; For processing
       `:model` - String. Vision LLM model to use.
       `:pages` - Page selector (1-indexed). Limits which pages are included.
                  Supports: integer, [from to] range, or [[1 3] 5 [7 10]] mixed vector.
                  nil = all pages (default). Applied after extraction.

       ;; Quality refinement (opt-in)
       `:refine?` - Boolean, optional. Enable post-extraction quality refinement (default: false).
       `:refine-model` - String, optional. Model for eval/refine steps (default: \"gpt-4o\").
       `:refine-iterations` - Integer, optional. Max refine iterations per page (default: 1).
       `:refine-threshold` - Float, optional. Min eval score to pass (default: 0.8).
       `:refine-sample-size` - Integer, optional. Pages to sample for eval (default: 3).
                               For PDFs, samples first + last + random middle pages.

   Returns:
   Map with:
     `:document/name` - String. Document name without extension.
     `:document/title` - String or nil. Document title from metadata.
     `:document/abstract` - String or nil. Document summary generated from section descriptions.
     `:document/extension` - String. File extension (pdf, md, txt).
     `:document/pages` - Vector of page maps with:
       - `:page/index` - Integer (0-indexed)
       - `:page/nodes` - Vector of content nodes (heading, paragraph, image, table, etc.)
     `:document/toc` - Vector of TocEntry nodes (extracted or generated):
        - `:document.toc/type` - :toc-entry
        - `:document.toc/id` - UUID string
        - `:document.toc/title` - Entry title text
        - `:document.toc/description` - Section description (copied from linked Section)
        - `:document.toc/target-page` - Page number (0-indexed) or nil
        - `:document.toc/target-section-id` - UUID of linked Section node or nil
        - `:document.toc/level` - Nesting level (l1, l2, etc.)
     `:document/created-at` - Instant. Creation date from metadata or now.
     `:document/updated-at` - Instant. Modification date from metadata or now.
     `:document/author` - String or nil. Document author from metadata."
  (fn [_router input & [opts]]
    (detect-input-type input (or opts {}))))

;; =============================================================================
;; build-index - :path method (file path input)
;; =============================================================================

(defn- strip-nil-keys
  "Drops entries whose value is nil from a map."
  [m]
  (into (empty m) (remove (fn [[_ v]] (nil? v))) m))

(defn- render-page-pngs!
  "Renders each selected PDF page as a full-page PNG into `<output-dir>/page-NNN.png`."
  [file-path pages output-dir]
  (try
    (let [page-indices (sort (mapv :page/index pages))
          imgs (pdf/pdf->images file-path {:page-set (set page-indices)})]
      (when-not (.exists (io/file output-dir))
        (.mkdirs (io/file output-dir)))
      (doseq [[i buf] (map-indexed vector imgs)
              :let [page-idx (nth (vec page-indices) i)
                    out-file (io/file output-dir (format "page-%03d.png" (inc page-idx)))]
              :when (not (.exists out-file))]
        (javax.imageio.ImageIO/write ^java.awt.image.BufferedImage buf "png" out-file))
      (trove/log! {:level :info :data {:pages (count imgs) :output-dir output-dir}
                   :msg "Rendered full-page PNGs"}))
    (catch Exception e
      (trove/log! {:level :warn :data {:error (ex-message e)}
                   :msg "Failed to render full-page PNGs"}))))

(defn- write-embedded-image-nodes!
  "For every :image/:table node that still carries raw `:page.node/image-data`
   bytes, writes the bytes to `<output-dir>/<node-id>.png`."
  [pages output-dir]
  (let [dir-file (io/file output-dir)]
    (when-not (.exists dir-file)
      (anomaly/not-found! (str "Output directory not found: " output-dir)
        {:type :svar.pageindex/output-dir-not-found :output-dir output-dir}))
    (mapv
      (fn [page]
        (update page :page/nodes
          (fn [nodes]
            (mapv
              (fn [node]
                (let [img-bytes (:page.node/image-data node)]
                  (if (and (#{:image :table} (:page.node/type node)) img-bytes)
                    (let [img-name (str (:page.node/id node) ".png")
                          out-file (io/file output-dir img-name)]
                      (with-open [out (io/output-stream out-file)]
                        (.write out ^bytes img-bytes))
                      (-> node
                        (dissoc :page.node/image-data)
                        (assoc :page.node/image-path (str "images/" img-name))))
                    node)))
              nodes))))
      pages)))

(defn- extract-pdf-pages
  "Phase 1 of the PDF indexing pipeline: run vision extraction, normalize IDs,
   group cross-page continuations, strip nil node keys, write per-page and
   per-image PNGs to `:output-dir`, and return the raw page list plus enough
   metadata for phase 2 to assemble the final document."
  [router file-path opts]
  (when-not (.exists (io/file file-path))
    (anomaly/not-found! (str "File not found: " file-path)
      {:type :svar.pageindex/file-not-found :file file-path}))
  (when-not (supported-extension? file-path)
    (let [extension (extract-extension file-path)]
      (anomaly/unsupported! (str "Unsupported file type: " (or extension "unknown"))
        {:type :svar.pageindex/unsupported-file-type
         :file file-path
         :extension extension
         :supported-extensions SUPPORTED_EXTENSIONS})))
  (let [vision-objective (or (:objective opts) vision/DEFAULT_VISION_OBJECTIVE)
        output-dir (:output-dir opts)
        vision-opts {:rlm-router router :objective vision-objective}
        _ (trove/log! {:level :info :data {:file file-path}
                       :msg "Starting text extraction from file"})
        page-list-all (extract-text file-path (merge opts vision-opts))
        pdf? (= :pdf (file-type file-path))
        page-list-raw (if pdf?
                        page-list-all
                        (filter-pages page-list-all
                          (when (:pages opts)
                            (normalize-page-spec (:pages opts) (count page-list-all)))))
        page-list (-> page-list-raw translate-all-ids group-continuations)
        pages (mapv (fn [p] (update p :page/nodes #(mapv strip-nil-keys %))) page-list)
        ftype (file-type file-path)
        file-metadata (when (= :pdf ftype)
                        (try
                          (pdf/pdf-metadata file-path)
                          (catch Exception e
                            (trove/log! {:level :warn :data {:error (ex-message e)}
                                         :msg "Failed to extract PDF metadata"})
                            nil)))
        _ (when (and output-dir (= :pdf ftype))
            (render-page-pngs! file-path pages output-dir))
        pages (if output-dir
                (write-embedded-image-nodes! pages output-dir)
                pages)]
    {:pages pages
     :doc-name (extract-doc-name file-path)
     :extension (extract-extension file-path)
     :ftype ftype
     :file-metadata file-metadata}))

(defn- finalize-pdf-document
  "Phase 2 of the PDF indexing pipeline: run TOC post-processing, document abstract,
   and title inference once on the merged page set."
  [router {:keys [pages doc-name extension file-metadata]} opts]
  (let [{:keys [pages toc]} (postprocess-toc pages)
        text-model (:text-model opts)
        abstract-opts {:rlm-router router :text-model text-model}
        document-abstract (try
                            (generate-document-abstract pages abstract-opts)
                            (catch Exception e
                              (trove/log! {:level :warn :data {:error (ex-message e)}
                                           :msg "Abstract generation failed — skipping"})
                              nil))
        metadata-title (:title file-metadata)
        inferred-title (when-not metadata-title
                         (try
                           (vision/infer-document-title pages
                             (cond-> {:rlm-router router}
                               text-model (assoc :text-model text-model)))
                           (catch Exception e
                             (trove/log! {:level :warn :data {:error (ex-message e)}
                                          :msg "Title inference failed — skipping"})
                             nil)))
        final-title (or metadata-title inferred-title)
        now (Instant/now)]
    (trove/log! {:level :info :data {:document/name doc-name
                                     :pages (count pages)
                                     :toc-entries (count toc)
                                     :has-metadata (boolean file-metadata)
                                     :title-inferred (boolean inferred-title)
                                     :has-abstract (boolean document-abstract)}
                 :msg "Text extraction complete"})
    {:document/name doc-name
     :document/title final-title
     :document/abstract document-abstract
     :document/extension extension
     :document/pages pages
     :document/toc toc
     :document/created-at (or (:created-at file-metadata) now)
     :document/updated-at (or (:updated-at file-metadata) now)
     :document/author (:author file-metadata)}))

(defmethod build-index :path
  [router file-path & [opts]]
  (finalize-pdf-document router (extract-pdf-pages router file-path opts) opts))

;; =============================================================================
;; build-index - :string method (raw content input)
;; =============================================================================

(defmethod build-index :string
  [router content & [opts]]
  (let [{:keys [content-type doc-name doc-title doc-author created-at updated-at]} (or opts {})
        _vision-model (or (:model opts) vision/DEFAULT_VISION_MODEL)
        vision-objective (or (:objective opts) vision/DEFAULT_VISION_OBJECTIVE)
        vision-opts {:rlm-router router :objective vision-objective}]
    (when-not content-type
      (anomaly/incorrect! "Missing required :content-type option for string input"
        {:type :svar.pageindex/missing-content-type :valid-types [:md :txt]}))
    (when-not doc-name
      (anomaly/incorrect! "Missing required :doc-name option for string input" {:type :svar.pageindex/missing-doc-name}))
    (trove/log! {:level :info :data {:doc-name doc-name :content-type content-type}
                 :msg "Starting text extraction from string content"})
    (let [page-list-raw (case content-type
                          :pdf (anomaly/unsupported! "PDF content-type not supported for string input"
                                 {:type :svar.pageindex/pdf-string-unsupported
                                  :hint "PDF requires vision extraction from file path"})
                          :md (markdown/markdown->pages content)
                          :markdown (markdown/markdown->pages content)
                          :txt (vision/extract-text-from-string content (merge opts vision-opts))
                          :text (vision/extract-text-from-string content (merge opts vision-opts))
                          (anomaly/incorrect! "Unknown content-type"
                            {:type :svar.pageindex/unknown-content-type
                             :content-type content-type
                             :valid-types [:md :txt]}))
          page-list-uuids (translate-all-ids page-list-raw)
          page-list (group-continuations page-list-uuids)
          {:keys [pages toc]} (postprocess-toc page-list)
          abstract-opts {:rlm-router router}
          document-abstract (generate-document-abstract pages abstract-opts)
          inferred-title (when-not doc-title
                           (vision/infer-document-title pages {:rlm-router router}))
          final-title (or doc-title inferred-title)
          extension (name content-type)
          now (Instant/now)]
      (trove/log! {:level :info :data {:document/name doc-name
                                       :pages (count pages)
                                       :toc-entries (count toc)
                                       :title-inferred (boolean inferred-title)
                                       :has-abstract (boolean document-abstract)}
                   :msg "Text extraction complete"})
      {:document/name doc-name
       :document/title final-title
       :document/abstract document-abstract
       :document/extension extension
       :document/pages pages
       :document/toc toc
       :document/created-at (or created-at now)
       :document/updated-at (or updated-at now)
       :document/author doc-author})))

;; =============================================================================
;; index! — incremental document indexing
;; =============================================================================

(defn- derive-index-path
  "Derive the EDN output directory from the input file path."
  [input-path]
  (let [parent (fs/parent input-path)
        base-name (fs/strip-ext (fs/file-name input-path))]
    (str (when parent (str parent "/")) base-name ".pageindex")))

(defn- ensure-absolute
  "Ensure the path is absolute."
  [path]
  (if (fs/absolute? path)
    (str path)
    (str (fs/absolutize path))))

(defn- file-hash
  "Computes SHA-256 hash of a file for change detection."
  [file-path]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 8192)]
    (with-open [is (java.io.FileInputStream. (str file-path))]
      (loop []
        (let [n (.read is buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur)))))
    (str "sha256:" (apply str (map #(format "%02x" %) (.digest digest))))))

(defn- read-manifest
  "Reads manifest.edn from a pageindex directory. Returns nil if not found."
  [output-path]
  (let [f (io/file output-path "manifest.edn")]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(def ^:private manifest-write-lock
  "Serializes manifest.edn writes so parallel per-page workers never interleave bytes."
  (Object.))

(defn- write-manifest!
  "Writes manifest.edn to a pageindex directory. Thread-safe."
  [output-path manifest]
  (locking manifest-write-lock
    (let [dir (io/file output-path)]
      (when-not (.exists dir)
        (.mkdirs dir)))
    (spit (str output-path "/manifest.edn")
      (pr-str manifest))))

(defn- update-manifest-page!
  "Updates a single page's status in the manifest and persists immediately."
  [output-path manifest-atom page-idx status-map]
  (swap! manifest-atom assoc-in [:pages page-idx] status-map)
  (write-manifest! output-path @manifest-atom))

(defn- with-index-lock!
  "Acquires a file lock on `lock.lck` inside the output directory."
  [output-path f]
  (let [dir (io/file output-path)]
    (when-not (.exists dir)
      (.mkdirs dir))
    (let [lock-file (io/file dir "lock.lck")
          raf (java.io.RandomAccessFile. lock-file "rw")
          ch (.getChannel raf)]
      (try
        (let [lock (.tryLock ch)]
          (when-not lock
            (.close ch)
            (.close raf)
            (anomaly/incorrect! "Another index! is already running for this document"
              {:type :svar.pageindex/lock-conflict
               :output-path (str output-path)}))
          (try
            (f)
            (finally
              (.release lock)
              (.close ch)
              (.close raf))))
        (catch java.nio.channels.OverlappingFileLockException _
          (.close ch)
          (.close raf)
          (anomaly/incorrect! "Another index! is already running for this document (same JVM)"
            {:type :svar.pageindex/lock-conflict
             :output-path (str output-path)}))))))

(defn ^:export index!
  "Index a document file with incremental progress tracking.

   First call performs a full index and writes both:
   - document.edn (indexed document)
   - manifest.edn (per-page indexing state)

   Subsequent calls on the same unchanged source file read manifest.edn and:
   - skip pages already marked :done
   - retry pages marked :error or :pending

   If the source file hash changes, a full re-index is performed automatically.
   Set :force? true to force full re-index even when hash matches.

   Returns map with :document, :output-path, :cached?, :hash-changed?, :pages-processed, :errors-count.
   When :hash-changed? is true, callers with db-info should call reindex-certainty-jump!."
  ([file-path] (index! file-path {}))
  ([file-path {:keys [router output vision-model text-model parallel parallel-refine
                      refine? refine-model refine-iterations
                      refine-threshold refine-sample-size pages
                      force? extraction-strategy ocr-model]}]
   (let [extraction-strategy (or extraction-strategy :vision)
         _ (when (and (= :ocr extraction-strategy) (not ocr-model))
             (anomaly/incorrect! ":ocr-model required when :extraction-strategy is :ocr"
               {:type :svar.pageindex/missing-ocr-model
                :extraction-strategy extraction-strategy}))
         abs-path (ensure-absolute file-path)
         output-path (or output (derive-index-path abs-path))
         _ (when-not (fs/exists? abs-path)
             (trove/log! {:level :error :data {:path abs-path} :msg "File not found"})
             (anomaly/not-found! "File not found" {:type :svar.pageindex/file-not-found :path abs-path}))]
     (with-index-lock! output-path
       (fn []
         (let [start-time (System/currentTimeMillis)
               current-hash (file-hash abs-path)
               existing-manifest (read-manifest output-path)
               hash-match? (and existing-manifest (= current-hash (:file-hash existing-manifest)))
               needs-full-reindex? (or force? (not hash-match?))
               file-type* (file-type abs-path)
               total-pages-hint (or (:total-pages existing-manifest)
                                  (when (= :pdf file-type*)
                                    (pdf/page-count abs-path))
                                  (when (= :markdown file-type*)
                                    (count (markdown/markdown-file->pages abs-path)))
                                  (when (#{:text :image} file-type*)
                                    1))
               user-page-set (when (and pages total-pages-hint)
                               (normalize-page-spec pages total-pages-hint))
               done-page-indices (set (keep (fn [[idx status-map]]
                                              (when (= :done (:status status-map)) idx))
                                        (:pages existing-manifest)))
               selected-page-indices (cond
                                       user-page-set (set user-page-set)
                                       total-pages-hint (set (range total-pages-hint))
                                       :else nil)
               pages-to-process (cond
                                  needs-full-reindex? selected-page-indices
                                  selected-page-indices (set/difference selected-page-indices done-page-indices)
                                  :else nil)
               existing-document (when (and hash-match? (not force?) (fs/exists? output-path))
                                   (try
                                     (load-index output-path)
                                     (catch Exception e
                                       (trove/log! {:level :warn
                                                    :data {:output-path output-path :error (ex-message e)}
                                                    :msg "Failed loading existing indexed document; rebuilding"})
                                       nil)))
               manifest-initial-pages (if selected-page-indices
                                        (let [existing-pages (or (:pages existing-manifest) {})
                                              now-str (str (Instant/now))]
                                          (into {}
                                            (map (fn [idx]
                                                   (if (and (not needs-full-reindex?)
                                                         (= :done (:status (get existing-pages idx))))
                                                     [idx (get existing-pages idx)]
                                                     [idx {:status :pending
                                                           :updated-at now-str}]))
                                              (sort selected-page-indices))))
                                        (or (:pages existing-manifest) {}))
               manifest-atom (atom
                               (merge {:file-path (str abs-path)
                                       :file-hash current-hash
                                       :total-pages total-pages-hint
                                       :model (or vision-model "default")
                                       :started-at (str (Instant/now))
                                       :completed-at nil
                                       :errors-count 0
                                       :pages manifest-initial-pages}
                                 (when (and existing-manifest hash-match? (not force?))
                                   (select-keys existing-manifest [:last-successful-index-at]))))
               images-dir (str (io/file output-path "images"))
               _ (.mkdirs (io/file images-dir))
               common-index-opts (cond-> {:output-dir images-dir
                                          :extraction-strategy extraction-strategy}
                                   vision-model (assoc :model vision-model)
                                   text-model (assoc :text-model text-model)
                                   ocr-model (assoc :ocr-model ocr-model)
                                   parallel (assoc :parallel parallel)
                                   parallel-refine (assoc :parallel-refine parallel-refine)
                                   refine? (assoc :refine? refine?)
                                   refine-model (assoc :refine-model refine-model)
                                   refine-iterations (assoc :refine-iterations refine-iterations)
                                   refine-threshold (assoc :refine-threshold refine-threshold)
                                   refine-sample-size (assoc :refine-sample-size refine-sample-size))]

           (write-manifest! output-path @manifest-atom)

           (if (and hash-match?
                 (not force?)
                 existing-document
                 selected-page-indices
                 (empty? pages-to-process))
             (do
               (trove/log! {:level :info
                            :id :svar.pageindex/cached
                            :data {:input abs-path
                                   :output output-path
                                   :cached-pages (count done-page-indices)}
                            :msg "All selected pages already indexed; returning cached document"})
               (write-manifest! output-path (assoc @manifest-atom
                                              :completed-at (str (Instant/now))
                                              :last-successful-index-at (str (Instant/now))))
               {:document existing-document
                :output-path output-path
                :cached? true
                :hash-changed? false
                :pages-processed 0
                :errors-count 0})

             (do
               (trove/log! {:level :info
                            :id :svar.pageindex/start
                            :data {:input abs-path
                                   :output output-path
                                   :mode (cond
                                           force? :forced-full
                                           needs-full-reindex? :full
                                           :else :incremental)
                                   :hash-match? (boolean hash-match?)
                                   :selected-pages (some-> selected-page-indices count)
                                   :done-pages (count done-page-indices)
                                   :to-process (some-> pages-to-process count)}
                            :msg (format "Starting %s indexing — %s pages to process, %d already done"
                                   (name (cond force? :forced-full needs-full-reindex? :full :else :incremental))
                                   (or (some-> pages-to-process count str) "all")
                                   (count done-page-indices))})

               (let [existing-page-map (into {} (map (fn [p] [(:page/index p) p])
                                                  (:document/pages existing-document)))
                     page-map-atom (atom (if needs-full-reindex? {} existing-page-map))
                     toc-atom (atom (or (:document/toc existing-document) []))
                     metadata-atom (atom (merge {:document/name (fs/strip-ext (fs/file-name abs-path))
                                                 :document/extension (some-> (fs/extension abs-path) name)}
                                           (select-keys existing-document
                                             [:document/name :document/title :document/abstract
                                              :document/extension :document/created-at :document/updated-at
                                              :document/author])))
                     processed-count (atom 0)
                     errors-count (atom 0)]

                 (if (seq pages-to-process)
                   (let [total-to-process (count pages-to-process)
                         page-times-atom (atom [])
                         worker-count (max 1 (or parallel 3))
                         pool (java.util.concurrent.Executors/newFixedThreadPool worker-count)
                         per-page-task
                         (fn [n idx]
                           (let [page-num (inc idx)
                                 t0 (System/currentTimeMillis)
                                 avg-ms-per-page (when (seq @page-times-atom)
                                                   (/ (reduce + @page-times-atom) (count @page-times-atom)))
                                 eta-ms (when avg-ms-per-page
                                          (long (* avg-ms-per-page (- total-to-process n))))
                                 progress-pct (Math/round (* 100.0 (/ n total-to-process)))]
                             (trove/log! {:level :info
                                          :id :svar.pageindex/indexing-page
                                          :data (cond-> {:page (inc n)
                                                         :page-number page-num
                                                         :total total-to-process
                                                         :progress-pct progress-pct
                                                         :elapsed-ms (- (System/currentTimeMillis) start-time)
                                                         :errors @errors-count}
                                                  eta-ms (assoc :eta-ms eta-ms))
                                          :msg (format "Indexing page %d/%d (%d%%)" (inc n) total-to-process progress-pct)})
                             (try
                               (let [extracted (extract-pdf-pages router abs-path (assoc common-index-opts :pages page-num))
                                     page (first (:pages extracted))
                                     now (str (Instant/now))
                                     page-elapsed (- (System/currentTimeMillis) t0)]
                                 (when-not page
                                   (anomaly/incorrect! "No page returned for selected page"
                                     {:type :svar.pageindex/missing-page
                                      :page-number page-num}))
                                 (swap! page-map-atom assoc idx page)
                                 (swap! metadata-atom merge
                                   {:document/name (:doc-name extracted)
                                    :document/extension (:extension extracted)
                                    :svar.pageindex/file-metadata (:file-metadata extracted)})
                                 (update-manifest-page! output-path manifest-atom idx
                                   {:status :done
                                    :indexed-at now
                                    :updated-at now
                                    :nodes (count (:page/nodes page))
                                    :elapsed-ms page-elapsed})
                                 (swap! processed-count inc)
                                 (swap! page-times-atom conj page-elapsed)
                                 (let [processed-so-far @processed-count
                                       remaining (- total-to-process processed-so-far)
                                       new-avg (/ (reduce + @page-times-atom) (count @page-times-atom))
                                       new-eta-ms (long (/ (* new-avg remaining) worker-count))]
                                   (trove/log! {:level :info
                                                :id :svar.pageindex/indexed-page
                                                :data {:page-number page-num
                                                       :nodes (count (:page/nodes page))
                                                       :elapsed-ms page-elapsed
                                                       :processed processed-so-far
                                                       :remaining remaining
                                                       :eta-ms new-eta-ms
                                                       :errors @errors-count}
                                                :msg (format "Indexed page %d — %d nodes, %dms (remaining: %d, ETA: %ds)"
                                                       page-num (count (:page/nodes page)) page-elapsed
                                                       remaining (quot new-eta-ms 1000))})))
                               (catch Exception e
                                 (let [now (str (Instant/now))
                                       page-elapsed (- (System/currentTimeMillis) t0)]
                                   (swap! errors-count inc)
                                   (update-manifest-page! output-path manifest-atom idx
                                     {:status :error
                                      :updated-at now
                                      :error (ex-message e)
                                      :elapsed-ms page-elapsed})
                                   (trove/log! {:level :warn
                                                :id :svar.pageindex/page-error
                                                :data {:page-number page-num
                                                       :error (ex-message e)
                                                       :elapsed-ms page-elapsed
                                                       :errors @errors-count
                                                       :remaining (- total-to-process (inc n))}
                                                :msg (format "Page %d failed: %s — marked :error (errors so far: %d)"
                                                       page-num (ex-message e) @errors-count)}))))))
                         futures (mapv (fn [[n idx]]
                                         (.submit pool ^Callable (fn [] (per-page-task n idx))))
                                   (map-indexed vector (sort pages-to-process)))]
                     (try
                       (doseq [^java.util.concurrent.Future f futures]
                         (try (.get f)
                              (catch Exception e
                                (trove/log! {:level :debug :data {:error (ex-message e)}
                                             :msg "Q-value update failed (non-fatal)"}))))
                       (finally
                         (.shutdown pool))))
                   (try
                     (let [doc (build-index router abs-path (cond-> common-index-opts
                                                              pages (assoc :pages pages)))
                           now (str (Instant/now))]
                       (swap! metadata-atom merge
                         (select-keys doc [:document/name :document/title :document/abstract
                                           :document/extension :document/created-at :document/updated-at
                                           :document/author]))
                       (when (seq (:document/toc doc))
                         (reset! toc-atom (:document/toc doc)))
                       (doseq [p (:document/pages doc)]
                         (let [idx (:page/index p)]
                           (swap! page-map-atom assoc idx p)
                           (update-manifest-page! output-path manifest-atom idx
                             {:status :done
                              :indexed-at now
                              :updated-at now
                              :nodes (count (:page/nodes p))})
                           (swap! processed-count inc))))
                     (catch Exception e
                       (swap! errors-count inc)
                       (trove/log! {:level :error
                                    :id :svar.pageindex/bulk-index-error
                                    :data {:error (ex-message e)
                                           :elapsed-ms (- (System/currentTimeMillis) start-time)}
                                    :msg (format "Bulk indexing failed: %s" (ex-message e))}))))

                 (let [final-pages (->> @page-map-atom (sort-by key) (mapv val))
                       final-document (if (seq final-pages)
                                        (finalize-pdf-document
                                          router
                                          {:pages final-pages
                                           :doc-name (or (:document/name @metadata-atom)
                                                       (fs/strip-ext (fs/file-name abs-path)))
                                           :extension (or (:document/extension @metadata-atom)
                                                        (some-> (fs/extension abs-path) name))
                                           :file-metadata (:svar.pageindex/file-metadata @metadata-atom)}
                                          common-index-opts)
                                        (merge @metadata-atom
                                          {:document/pages []
                                           :document/toc @toc-atom}))
                       elapsed-ms (- (System/currentTimeMillis) start-time)
                       all-failed? (and (pos? @errors-count) (empty? final-pages))]
                   (when-not all-failed?
                     (when-not (schema/valid-document? final-document)
                       (let [explanation (schema/explain-document final-document)]
                         (trove/log! {:level :error :data {:explanation explanation} :msg "Document failed spec validation"})
                         (anomaly/incorrect! "Document failed spec validation"
                           {:type :rlm/invalid-document
                            :document/name (:document/name final-document)
                            :explanation explanation})))
                     (write-document-edn! output-path final-document))
                   (let [final-manifest (cond-> (assoc @manifest-atom
                                                  :completed-at (str (Instant/now))
                                                  :total-pages (or total-pages-hint (count final-pages))
                                                  :errors-count @errors-count)
                                          (not all-failed?)
                                          (assoc :last-successful-index-at (str (Instant/now))))]
                     (write-manifest! output-path final-manifest)
                     (trove/log! {:level (if all-failed? :warn :info)
                                  :id :svar.pageindex/complete
                                  :data {:document/name (:document/name final-document)
                                         :pages (count final-pages)
                                         :toc-entries (count (:document/toc final-document))
                                         :output-path output-path
                                         :mode (cond
                                                 force? :forced-full
                                                 needs-full-reindex? :full
                                                 :else :incremental)
                                         :processed @processed-count
                                         :errors @errors-count
                                         :elapsed-ms elapsed-ms}
                                  :msg (format "Indexing %s — %d pages, %d errors, %dms"
                                         (if all-failed? "finished with all pages failed" "complete")
                                         (count final-pages) @errors-count elapsed-ms)})
                     {:document (when-not all-failed? final-document)
                      :output-path output-path
                      :cached? false
                      :hash-changed? (boolean needs-full-reindex?)
                      :pages-processed @processed-count
                      :errors-count @errors-count})))))))))))
