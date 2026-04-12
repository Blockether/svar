(ns com.blockether.svar.internal.rlm.env
  "RLM environment construction and registration helpers.
   Extracts create-env, dispose-env!, register-env-fn!, register-env-def!,
   register-hook!, unregister-hook!, list-tool-hooks, list-registered-tools
   from rlm.clj to isolate env lifecycle from the main facade.

   Atom layout (5 standalone + db-info):
     :depth-atom         — concurrent hot path, must be standalone
     :tool-registry-atom — 10+ access sites, independent lifecycle
     :db-info-atom       — existing DB conn, untouched
     :var-index-atom     — {:cache nil :revision -1} (was two atoms)
     :qa-corpus-atom     — {:snapshot-cache nil :stats {...}} (was two atoms)
     :state-atom         — {:custom-bindings {} :custom-docs []
                            :skill-registry nil :rlm-env nil
                            :conversation-ref nil}"
  (:require
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.internal.rlm.core :as rlm-core]
   [com.blockether.svar.internal.rlm.db :as rlm-db]
   [com.blockether.svar.internal.rlm.routing :as rlm-routing]
   [com.blockether.svar.internal.rlm.skills :as rlm-skills]
   [com.blockether.svar.internal.rlm.tools :as rlm-tools]
   [com.blockether.svar.internal.util :as util]))

;; =============================================================================
;; Accessor helpers — keep callers decoupled from atom layout
;; =============================================================================

(defn env-state
  "Returns current :state-atom value."
  [env]
  @(:state-atom env))

(defn env-update-state!
  "swaps :state-atom with f applied to current value + args."
  [env f & args]
  (apply swap! (:state-atom env) f args))

(defn env-var-index
  "Returns current :var-index-atom value {:cache ... :revision ...}."
  [env]
  @(:var-index-atom env))

(defn env-qa-corpus
  "Returns current :qa-corpus-atom value {:snapshot-cache ... :stats ...}."
  [env]
  @(:qa-corpus-atom env))

;; =============================================================================
;; create-env
;; =============================================================================

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
        ;; Canonical tool registry: {sym → {:fn :doc :params :returns :hooks
        ;;   {:before [{:id :fn}] :after [...] :wrap [...]}}}. Populated by
        ;; register-env-fn! with hook normalization + id-based chain merge.
        ;; On each query-env! call, registered fns are re-flashed into SCI
        ;; as hook-wrapped closures so per-query :hooks + cancel-atom are
        ;; visible to the pipeline.
        tool-registry-atom (atom {})
        db-info (rlm-db/create-rlm-conn db)
        db-info-atom (when db-info (atom db-info))
        ;; Grouped: var-index cache + revision counters (was two separate atoms)
        ;; :index = built index, :revision = rev at build, :current-revision = live counter
        var-index-atom (atom {:index nil :revision -1 :current-revision 0})
        ;; Grouped: QA corpus snapshot cache + stats
        qa-corpus-atom (atom {:snapshot-cache nil
                              :stats {:hits 0 :misses 0
                                      :last-digest-ms nil
                                      :last-revision 0}})
        skill-registry-map (rlm-skills/load-skills {})
        ;; Ingest skills into Datalevin as :skill documents (searchable)
        _ (when db-info-atom
            (rlm-skills/ingest-skills! @db-info-atom skill-registry-map))
        ;; Pooled: low-traffic mutable state grouped into one atom
        state-atom (atom {:custom-bindings {}
                          :custom-docs []
                          :skill-registry skill-registry-map
                          :rlm-env nil           ;; filled below after env construction
                          :conversation-ref nil}) ;; filled below
        ;; rlm-env-atom is a local convenience — sub-rlm-query routing reads
        ;; :rlm-env from state-atom. We use an intermediate atom here so
        ;; make-routed-sub-rlm-query-fn can hold a stable reference.
        rlm-env-atom (atom nil)
        ;; skill-registry-atom is kept as a thin wrapper that reads/writes
        ;; :skill-registry inside state-atom, for compat with routing/sub/skills.
        skill-registry-atom (atom skill-registry-map)
        ;; Inject rlm-core/iteration-loop so make-routed-sub-rlm-query-fn can
        ;; delegate to run-sub-rlm without sub.clj needing a cyclic require on core.
        sub-rlm-query-fn (rlm-routing/make-routed-sub-rlm-query-fn
                           {} depth-atom router skill-registry-atom rlm-env-atom
                           rlm-core/iteration-loop)
        cheap-sub-rlm-query-fn (rlm-routing/make-routed-sub-rlm-query-fn
                                 {:optimize :cost} depth-atom router skill-registry-atom rlm-env-atom
                                 rlm-core/iteration-loop)
        env-id (str (util/uuid))
        root-model (or (rlm-routing/resolve-root-model router) "unknown")
        has-reasoning? (boolean (rlm-routing/provider-has-reasoning? router))
        system-prompt (rlm-core/build-system-prompt {:has-reasoning? has-reasoning?
                                                     :skill-registry skill-registry-map})
        resolved-conversation-ref (rlm-db/db-resolve-conversation-ref db-info conversation)
        conversation-name (when (and (map? conversation) (string? (:name conversation)))
                            (:name conversation))
        conversation-ref (or resolved-conversation-ref
                           (rlm-db/store-conversation! db-info
                             {:env-id env-id :model root-model
                              :system-prompt system-prompt
                              :name conversation-name}))
        {:keys [sci-ctx sandbox-ns initial-ns-keys]}
        (rlm-tools/create-sci-context nil cheap-sub-rlm-query-fn db-info-atom
          ;; conversation-ref-atom: tools.clj reads this to scope DB queries.
          ;; We pass a stable atom; conversation-ref lives in state-atom too
          ;; but tools needs the atom identity for close-over semantics.
          (atom conversation-ref)
          (:custom-bindings @state-atom))
        env {:env-id env-id
             :conversation-ref conversation-ref
             ;; Grouped atoms (new layout)
             :depth-atom depth-atom
             :tool-registry-atom tool-registry-atom
             :db-info-atom db-info-atom
             :var-index-atom var-index-atom
             :qa-corpus-atom qa-corpus-atom
             :state-atom state-atom
             ;; skill-registry-atom kept standalone for routing/sub/skills compat
             :skill-registry-atom skill-registry-atom
             ;; SCI context
             :sci-ctx sci-ctx
             :sandbox-ns sandbox-ns
             :initial-ns-keys initial-ns-keys
             :router router
             :sub-rlm-query-fn sub-rlm-query-fn
             :cheap-sub-rlm-query-fn cheap-sub-rlm-query-fn}]
    ;; Back-fill rlm-env into both atoms
    (reset! rlm-env-atom env)
    (swap! state-atom assoc :rlm-env env :conversation-ref conversation-ref)
    env))

;; =============================================================================
;; dispose-env!
;; =============================================================================

(defn dispose-env!
  "Disposes an RLM environment and releases resources.

   For persistent DBs (created with :path), data is preserved.
   For disposable DBs, all data is deleted.

   Git tools have no resources to release here — ingest-git! opens and
   closes repos inside its own body, and SCI git tools open/close repos
   per call via with-open. Nothing lives on the env atoms.

   Params:
   `env` - RLM environment from create-env."
  [env]
  (when-let [db-info-atom (:db-info-atom env)]
    (rlm-db/dispose-rlm-conn! @db-info-atom)))

;; =============================================================================
;; register-env-fn!
;; =============================================================================

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
  (when-not (:state-atom env)
    (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))
  (when-not (symbol? sym)
    (anomaly/incorrect! "sym must be a symbol" {:type :rlm/invalid-sym :sym sym}))
  (when-not (fn? f)
    (anomaly/incorrect! "f must be a function" {:type :rlm/invalid-fn}))
  (when-not (map? tool-def)
    (anomaly/incorrect! "tool-def must be a map" {:type :rlm/invalid-tool-def}))
  ;; Fns live ONLY in tool-registry-atom — NOT in custom-bindings — so the
  ;; per-query flash at query-env! doesn't clobber hook-wrapped bindings.
  (rlm-tools/register-tool-def! (:tool-registry-atom env) sym
    (assoc tool-def :fn f :sym sym :type :fn))
  ;; Immediately inject a hook-wrapped fn into SCI so the tool is callable
  ;; before the next query-env! re-flash.
  (when-let [sci-ctx (:sci-ctx env)]
    (rlm-tools/sci-update-binding! sci-ctx sym
      (rlm-tools/wrap-tool-for-sci env sym f (:tool-registry-atom env))))
  (swap! (:state-atom env) update :custom-docs conj (assoc tool-def :type :fn :sym sym))
  env)

;; =============================================================================
;; register-env-def!
;; =============================================================================

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
  (when-not (:state-atom env)
    (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))
  (when-not (symbol? sym)
    (anomaly/incorrect! "sym must be a symbol" {:type :rlm/invalid-sym :sym sym}))
  (when-not (map? tool-def)
    (anomaly/incorrect! "tool-def must be a map" {:type :rlm/invalid-tool-def}))
  (swap! (:state-atom env) update :custom-bindings assoc sym value)
  ;; Inject into live SCI ctx
  (when-let [sci-ctx (:sci-ctx env)]
    (rlm-tools/sci-update-binding! sci-ctx sym value))
  (swap! (:state-atom env) update :custom-docs conj (assoc tool-def :type :def :sym sym))
  env)

;; =============================================================================
;; Hook helpers
;; =============================================================================

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
