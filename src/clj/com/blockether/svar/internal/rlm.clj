(ns com.blockether.svar.internal.rlm
  "Recursive Language Model (RLM) for processing arbitrarily large contexts."
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.internal.jsonish :as jsonish]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.rlm.core :as rlm-core]
   [com.blockether.svar.internal.rlm.db :as rlm-db]
   [com.blockether.svar.internal.rlm.pageindex.markdown :as markdown]
   [com.blockether.svar.internal.rlm.pageindex.pdf :as pdf]
   [com.blockether.svar.internal.rlm.pageindex.vision :as vision]
   [com.blockether.svar.internal.rlm.routing :as rlm-routing]
   [com.blockether.svar.internal.rlm.schema :as schema]
   [com.blockether.svar.internal.rlm.trajectory :as trajectory]
   [com.blockether.svar.internal.rlm.tools :as rlm-tools]
   [com.blockether.svar.internal.spec :as spec]
   [com.blockether.svar.internal.tokens :as tokens]
   [com.blockether.svar.internal.util :as util]
   [datalevin.core :as d]
   [fast-edn.core :as edn]
   [taoensso.trove :as trove])
  (:import
   [java.time Instant]
   [java.util Date]))

(def RLM_SCHEMA schema/RLM_SCHEMA)
(def ENTITY_EXTRACTION_SPEC schema/ENTITY_EXTRACTION_SPEC)
(def MAX_ITERATIONS schema/MAX_ITERATIONS)
(def DEFAULT_RECURSION_DEPTH schema/DEFAULT_RECURSION_DEPTH)
(def EVAL_TIMEOUT_MS schema/EVAL_TIMEOUT_MS)
(def ^:dynamic *max-recursion-depth* schema/*max-recursion-depth*)
(def ^:dynamic *rlm-ctx* schema/*rlm-ctx*)

(def get-recent-messages rlm-db/get-recent-messages)
(def db-learning-stats rlm-db/db-learning-stats)

(def GENERATION_PERSONAS schema/GENERATION_PERSONAS)
(def DEDUP_SPEC schema/DEDUP_SPEC)
(def REVISION_SPEC schema/REVISION_SPEC)
(def CHUNK_SELECTION_SPEC schema/CHUNK_SELECTION_SPEC)
(def QUESTIONIFY_SPEC schema/QUESTIONIFY_SPEC)
(def VERIFICATION_SPEC schema/VERIFICATION_SPEC)

(defn make-default-hooks
  "Build Datalevin-backed hook/data callbacks from a db-info atom.

   Use this to create hooks/data callbacks backed by YOUR database (unified DB).
   Pass the result as :hooks to create-env.

   Params:
   `db-info-atom` - Atom holding {:conn datalevin-conn}.

   Returns:
   Map of hooks/data callbacks."
  [db-info-atom]
  {;; === Read operations — consumers (vis, etc.) use these ===
    ;; Messages: full entities with thinking + executions embedded
   :get-recent-messages       (fn [limit] (get-recent-messages @db-info-atom limit))
   :count-history-tokens      (fn [] (rlm-db/count-history-tokens @db-info-atom))
    ;; Learnings: read-only — store/vote are auto-managed by RLM internally
   :search-learnings     (fn [query opts] (rlm-db/db-get-learnings @db-info-atom query
                                                                   (assoc opts :track-usage? false)))
   :learning-stats       (fn [] (rlm-db/db-learning-stats @db-info-atom))
    ;; Documents: read-only access to ingested content
   :list-documents       (fn [opts] (rlm-db/db-list-documents @db-info-atom opts))
   :get-document         (fn [doc-id] (rlm-db/db-get-document @db-info-atom doc-id))
   :search-page-nodes    (fn [query opts] (rlm-db/db-search-page-nodes @db-info-atom query opts))
   :get-page-node        (fn [node-id] (rlm-db/db-get-page-node @db-info-atom node-id))
   :list-page-nodes      (fn [opts] (rlm-db/db-list-page-nodes @db-info-atom opts))
   :search-toc-entries   (fn [query opts] (rlm-db/db-search-toc-entries @db-info-atom query opts))
   :get-toc-entry        (fn [entry-id] (rlm-db/db-get-toc-entry @db-info-atom entry-id))
   :list-toc-entries     (fn [opts] (rlm-db/db-list-toc-entries @db-info-atom opts))})

(defn call-hook!
  "Safely invoke a hook. Returns nil. Exceptions are caught and ignored."
  [hooks-atom hook-name phase data]
  (when-let [hooks (some-> hooks-atom deref)]
    (when-let [hook-fn (get-in hooks [hook-name phase])]
      (try
        (hook-fn data)
        (catch Exception _ nil)))))

(defn- deep-merge
  "Recursively merges maps. Non-map values are replaced by right-most value."
  [& maps]
  (letfn [(m [a b]
            (merge-with (fn [x y]
                          (if (and (map? x) (map? y))
                            (m x y)
                            y))
                        a b))]
    (reduce m {} maps)))

(defn create-env
  "Creates an RLM environment (component) for document ingestion and querying.
   
    The environment holds:
    - In-memory store for documents, learnings, and conversation history
    - LLM configuration for queries
    - SCI sandbox context with custom bindings
   
   Usage:
   ```clojure
   (def env (rlm/create-env {:config llm-config}))
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
   - :config - Required. LLM config with :providers (vector of provider maps) and :default-model.
   - :path - Optional. Path for persistent DB. If provided, data survives across sessions.
   - :hooks - Optional. Map of hooks/data callbacks to override defaults. Keys match
        make-default-hooks output (:get-recent-messages, :count-history-tokens, etc.).

    Returns:
    RLM environment map (component). Pass to register-env-fn!, register-env-def!, ingest-to-env!, query-env!, dispose-env!."
  [{:keys [config path conn hooks]}]
  (when-not config
    (anomaly/incorrect! "Missing :config" {:type :rlm/missing-config}))
  (when-not (seq (:providers config))
    (anomaly/incorrect! "Missing :providers in config — provide at least one provider"
                        {:type :rlm/missing-providers}))
  (let [rlm-router (llm/make-router (:providers config))
        depth-atom (atom 0)
        locals-atom (atom {})
        custom-bindings-atom (atom {})
        custom-docs-atom (atom [])
        db-info (rlm-db/create-rlm-conn {:conn conn :path path})
        db-info-atom (atom db-info)
        merged-hooks (deep-merge (make-default-hooks db-info-atom) (or hooks {}))
        hooks-atom (atom merged-hooks)
        llm-query-fn (rlm-routing/make-routed-llm-query-fn {:strategy :root} depth-atom rlm-router {:hooks-atom hooks-atom})
        sub-llm-query-fn (rlm-routing/make-routed-llm-query-fn {:prefer :cost :capabilities #{:chat}} depth-atom rlm-router {:hooks-atom hooks-atom})
        rlm-query-fn (rlm-core/make-rlm-query-fn {:strategy :root} depth-atom rlm-router db-info-atom)
        env-id (str (util/uuid))]
    {:env-id env-id
     :config (llm/sanitize-config config)
     :depth-atom depth-atom
     :locals-atom locals-atom
     :hooks-atom hooks-atom
     :custom-bindings-atom custom-bindings-atom
     :custom-docs-atom custom-docs-atom
     :db-info-atom db-info-atom
     :hooks merged-hooks
     :router rlm-router
     :llm-query-fn llm-query-fn
     :sub-llm-query-fn sub-llm-query-fn
     :rlm-query-fn rlm-query-fn
     :history-enabled? true}))

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
  (let [wrapped-fn (fn [& args]
                     (call-hook! (:hooks-atom env) :tool-call :pre {:sym sym :args args})
                     (try
                       (let [result (apply f args)]
                         (call-hook! (:hooks-atom env) :tool-call :post {:sym sym :args args :result result})
                         result)
                       (catch Exception e
                         (call-hook! (:hooks-atom env) :tool-call :post {:sym sym :args args :error (ex-message e)})
                         (throw e))))]
    (swap! (:custom-bindings-atom env) assoc sym wrapped-fn))
  (swap! (:custom-docs-atom env) conj (assoc tool-def :type :fn :sym sym))
  env)

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
     results)))

(defn dispose-env!
  "Disposes an RLM environment and releases resources.
   
    For persistent DBs (created with :path), data is preserved.
   For disposable DBs, all data is deleted.
   
   Params:
   `env` - RLM environment from create-env."
  [env]
  (when-let [db-info-atom (:db-info-atom env)]
    (rlm-db/dispose-rlm-conn! @db-info-atom)))

(defn query-env!
  "Runs a query on an RLM environment using iterative LLM code execution.
   
   The LLM can use these functions during execution:
   
   Document search:
    - (list-documents) - List all stored documents
    - (get-document doc-id) - Get document metadata
    - (search-page-nodes query) - List/filter actual content
    - (get-page-node node-id) - Get full page node content
    - (list-page-nodes opts) - List page nodes with filters
    - (search-toc-entries query) - List/filter table of contents
    - (get-toc-entry entry-id) - Get TOC entry
    - (list-toc-entries) - List all TOC entries
    
    History:
    - (search-history n) - Get recent messages
    - (get-history n) - Get recent messages
    
    Learnings:
    - (store-learning insight) - Store meta-insight
    - (search-learnings query) - Search learnings
    
    Params:
    `env` - RLM environment from create-env.
    `query-str` - String. The question to answer.
   `opts` - Map, optional:
     - :context - Data to analyze. Per RLM paper: when string, becomes P (the symbolic handle)
                   with get-page and page-count for programmatic access. Structured data
                   (maps, vectors) is accessible as `context` variable in SCI.
     - :spec - Output spec for structured answers.
     - :model - Override config's default model.
     - :max-iterations - Max code iterations (default: 50).
     - :max-refinements - Max refine iterations (default: 1).
      - :threshold - Min eval score 0.0-1.0 for refinement early stop (default: 0.8).
      - :verify? - Enable claim verification with citations (default: false).
      - :max-context-tokens - Token budget for context.
      - :hooks - Per-query hook/data callback overrides merged for this query only.
      - :debug? - Enable verbose debug logging (default: false). Logs iteration details,
        code execution, LLM responses at :info level with :rlm-phase context.
    
    Returns:
   Map with:
     - :answer - Final (possibly refined) answer string, or parsed spec data.
     - :raw-answer - Original answer before refinement.
     - :trace - Vector of iteration trace entries, each containing:
         {:iteration N
          :response \"LLM response text\"
          :executions [{:id 0 :code \"(+ 1 2)\" :result 3 :stdout \"\" :error nil :execution-time-ms 5}
                       {:id 1 :code \"(FINAL answer)\" :result {:rlm/final true ...} ...}]
          :final? boolean}
     - :iterations - Total number of iterations executed.
     - :eval-scores - Evaluation scores from refinement (if enabled).
     - :refinement-count - Number of refinement iterations.
      - :duration-ms - Total execution time in milliseconds.
      - :history-tokens - Approximate token count of conversation history.
      - :tokens - Token usage across all iterations: {:input N :output N :reasoning N :cached N :total N}.
      - :cost - Estimated USD cost: {:input-cost N :output-cost N :total-cost N :model \"...\"}.
      - :status - Only present on failure, e.g. :max-iterations."
  ([env query-str]
   (query-env! env query-str {}))
  ([env query-str {:keys [context spec model max-iterations max-refinements threshold
                          max-context-tokens max-recursion-depth verify?
                          system-prompt plan? autolearn? debug? hooks]
                   :or {max-iterations MAX_ITERATIONS max-refinements 1 threshold 0.8
                        max-recursion-depth DEFAULT_RECURSION_DEPTH verify? false
                        plan? false autolearn? true debug? false}}]
   (when-not (:db-info-atom env)
     (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))
   (when-not query-str
     (anomaly/incorrect! "Missing query" {:type :rlm/missing-query}))
   (let [rlm-router (:router env)
           ;; Resolve root model name for token counting / refine! config
         root-model (or (when rlm-router (rlm-routing/resolve-root-model rlm-router)) model)
          ;; Rebuild SCI context with current context-data and custom bindings
         locals-atom (:locals-atom env)
         db-info-atom (:db-info-atom env)
         llm-query-fn (:llm-query-fn env)
         rlm-query-fn (:rlm-query-fn env)
         sub-llm-fn (or (:sub-llm-query-fn env) (:llm-query-fn env))
         hooks-atom (:hooks-atom env)
         original-hooks (when hooks-atom @hooks-atom)
         custom-bindings (when-let [atom (:custom-bindings-atom env)] @atom)
         custom-docs (when-let [atom (:custom-docs-atom env)] @atom)
         claims-atom (when verify? (atom []))
         cite-bindings (when verify?
                         {'CITE (rlm-tools/make-cite-fn claims-atom)
                          'CITE-UNVERIFIED (rlm-tools/make-cite-unverified-fn claims-atom)
                          'list-claims (rlm-tools/make-list-claims-fn claims-atom)})
         cite-docs (when verify?
                     [{:type :fn :sym 'CITE :doc "(CITE claim-text document-id page section quote) or (CITE claim-text document-id page section quote confidence) - Cite a claim with source evidence. Returns {:cited true :claim-id uuid :claim-text text}"}
                      {:type :fn :sym 'CITE-UNVERIFIED :doc "(CITE-UNVERIFIED claim-text) - Record a claim without source verification. Lower confidence."}
                      {:type :fn :sym 'list-claims :doc "(list-claims) - List all claims cited so far in this query."}
                      {:type :note :sym 'CITE-PRIORITY :doc "CITE is OPTIONAL. ALWAYS call (FINAL answer) as soon as you have the answer. Only use CITE BEFORE calling FINAL if the query explicitly asks for citations. Do NOT delay FINAL to gather citations."}])
          ;; Adaptive iteration budget: the LLM can request more iterations at runtime.
          ;; Capped at MAX_ITERATION_CAP to prevent runaway loops.
         max-iterations-atom (atom max-iterations)
         budget-bindings {'request-more-iterations
                          (fn [n]
                            (let [requested (max 1 (min (long n) (long schema/MAX_EXTENSION_PER_REQUEST)))
                                  current @max-iterations-atom
                                  new-budget (min (+ current requested) (long schema/MAX_ITERATION_CAP))]
                              (reset! max-iterations-atom new-budget)
                              (let [granted (- new-budget current)]
                                (trove/log! {:level :info :id ::iteration-budget
                                             :data {:requested (long n) :granted granted
                                                    :new-budget new-budget :cap schema/MAX_ITERATION_CAP}
                                             :msg "LLM requested more iterations"})
                                {:granted granted :new-budget new-budget :cap schema/MAX_ITERATION_CAP})))}
         llm-query-overrides {'llm-query sub-llm-fn
                              'llm-query-batch (fn [prompts]
                                                 (let [chs (mapv (fn [p]
                                                                   (when false
                                                                     (try
                                                                       (sub-llm-fn p)
                                                                       (catch Exception e
                                                                         {:error (ex-message e)}))))
                                                                 prompts)]
                                                   (mapv async/<!! chs)))}
         sci-ctx (rlm-tools/create-sci-context context sub-llm-fn rlm-query-fn locals-atom db-info-atom
                                               (merge custom-bindings cite-bindings budget-bindings llm-query-overrides))
         rlm-env (assoc env :sci-ctx sci-ctx :context context :max-iterations-atom max-iterations-atom)
         env-id (:env-id env)]
     (binding [*rlm-ctx* {:rlm-env-id env-id :rlm-type :main :rlm-debug? debug? :rlm-phase :query}]
       (binding [*max-recursion-depth* max-recursion-depth]
         (try
           (when hooks-atom
             (reset! hooks-atom (deep-merge (or original-hooks {}) (or hooks {}))))
           (rlm-core/rlm-debug! {:query query-str :root-model root-model :max-iterations max-iterations
                                 :verify? verify? :plan? plan?
                                 :refine-mode :final-confidence} "RLM query-env! started")
           (call-hook! hooks-atom :query :pre {:query query-str
                                               :context context
                                               :opts {:model model
                                                      :max-iterations max-iterations
                                                      :verify? verify?
                                                      :plan? plan?}})
           (let [start-time (System/nanoTime)
                 db-info @db-info-atom
                ;; Infer document names for scope-aware learning retrieval
                 doc-names (when (:conn db-info)
                             (try (->> (d/q '[:find [?n ...]
                                              :where [?e :document/name ?n]]
                                            (d/db (:conn db-info)))
                                       (mapv (fn [n] (let [ext (when-let [i (str/last-index-of n ".")] (subs n i))]
                                                       (or ext n)))))
                                  (catch Exception _ [])))
                 ;; Auto-fetch query-relevant learnings only (scope-filtered)
                 active-learnings (when (:conn db-info)
                                    (rlm-db/db-get-learnings db-info query-str
                                                             {:top-k 5 :track-usage? true :doc-names doc-names}))
                 ;; Auto-fetch all tag definitions for context injection
                 tag-definitions (when (:conn db-info)
                                   (rlm-db/db-list-tags db-info))
               ;; Optional planning phase — LLM outlines approach before code execution
                 plan-result (when plan?
                               (llm/ask! {:messages [(llm/system "You are a planning assistant. Given a query and available document tools, outline a clear 3-5 step approach to answer the query. Be specific about which tools to use and in what order. Do NOT write code — just describe the strategy.")
                                                     (llm/user (str "Query: " query-str))]
                                          :router rlm-router
                                          :prefer :intelligence :capabilities #{:chat}}))
                 plan-context (when-let [plan (:result plan-result)]
                                (str "<plan>\n" plan "\n</plan>"))
                ;; iteration-loop returns {:answer :trace :iterations} or {:answer :trace :iterations :status :locals}
                 iteration-result (rlm-core/iteration-loop rlm-env query-str
                                                           {:max-iterations max-iterations
                                                            :output-spec spec
                                                            :learnings active-learnings
                                                            :tag-definitions tag-definitions
                                                            :max-context-tokens max-context-tokens
                                                            :custom-docs (into (or custom-docs []) cite-docs)
                                                            :system-prompt system-prompt
                                                            :pre-fetched-context plan-context
                                                            :hooks-atom hooks-atom})
                 {answer :answer
                  trace :trace
                  iterations :iterations
                  status :status
                  tokens :tokens
                  cost :cost
                  confidence :confidence
                  learn-items :learn} iteration-result
                ;; Mutable cost accumulator — iteration costs are the baseline,
                ;; refinement + auto-vote costs get merged in
                 total-tokens-atom (atom (or tokens {}))
                 total-cost-atom (atom (or cost {}))
                 merge-cost! (fn [extra-tokens extra-cost]
                               (when extra-tokens
                                 (swap! total-tokens-atom
                                        (fn [acc]
                                          (merge-with + acc
                                                      (select-keys extra-tokens [:input :output :reasoning :cached :total])))))
                               (when extra-cost
                                 (swap! total-cost-atom
                                        (fn [acc]
                                          (merge-with + (select-keys acc [:input-cost :output-cost :total-cost])
                                                      (select-keys extra-cost [:input-cost :output-cost :total-cost]))))))
                 ;; Merge planning cost if planning phase ran
                 _ (when plan-result
                     (merge-cost! (:tokens plan-result) (:cost plan-result)))]
             (if status
               ;; Execution hit max iterations - return with trace
               (let [duration-ms (util/elapsed-since start-time)]
                  ;; Auto-vote via LLM: evaluate which learnings were relevant (even in failure)
                 (when-let [{:keys [tokens cost]} (rlm-core/reflect-vote! db-info rlm-router active-learnings query-str :failure nil)]
                   (merge-cost! tokens cost))
                 (rlm-core/rlm-debug! {:status status :iterations iterations :duration-ms duration-ms
                                       :auto-voted (count active-learnings)} "RLM query-env! finished (max iterations)")
                 (try
                   (rlm-db/store-trajectory! @db-info-atom
                                             {:env-id env-id :query query-str :status status
                                              :answer (:result answer answer) :iterations iterations
                                              :duration-ms duration-ms :model root-model
                                              :doc-pages (try (when-let [c (:conn @db-info-atom)]
                                                                (or (d/q '[:find (count ?e) . :where [?e :page/id _]] (d/db c)) 0))
                                                              (catch Exception _ 0))})
                   (catch Exception e
                     (trove/log! {:level :warn :data {:error (ex-message e)}
                                  :msg "Failed to store trajectory (max iterations)"})))
                 (let [result-map (cond-> {:answer nil
                                           :raw-answer (:result answer answer)
                                           :status status
                                           :trace trace
                                           :iterations iterations
                                           :duration-ms duration-ms
                                           :tokens @total-tokens-atom
                                           :cost @total-cost-atom}
                                    verify? (assoc :verified-claims (vec @claims-atom)))]
                   (call-hook! hooks-atom :query :post {:query query-str
                                                        :answer nil
                                                        :trace trace
                                                        :iterations iterations
                                                        :duration-ms duration-ms
                                                        :status status})
                   result-map))
              ;; Normal completion - refine and finalize
               (let [answer-value (:result answer answer)
                     refine? (= confidence :low)
                     ;; Refinement path: stringify for LLM round-trip.
                    ;; No-refine path: keep the native Clojure value from FINAL.
                     {final-answer :answer
                      eval-scores  :eval-scores
                      refinement-count :refinement-count}
                     (if refine?
                       (let [answer-as-str (rlm-core/answer-str answer)
                             conn (:conn db-info)
                             stored-docs (when conn
                                           (let [docs (d/q '[:find [(pull ?e [*]) ...]
                                                             :where [?e :document/id _]]
                                                           (d/db conn))]
                                             (when (seq docs)
                                               (mapv (fn [doc]
                                                       (let [doc-id (or (:document/id doc) (:document/name doc))
                                                             nodes (d/q '[:find [(pull ?e [:page.node/page-id :page.node/content]) ...]
                                                                          :in $ ?doc-id
                                                                          :where [?e :page.node/document-id ?doc-id]]
                                                                        (d/db conn) doc-id)]
                                                         {:id doc-id
                                                          :pages (mapv (fn [pn]
                                                                         {:page (or (:page.node/page-id pn) "0")
                                                                          :text (or (:page.node/content pn) "")})
                                                                       nodes)}))
                                                     docs))))
                            ;; Build refine config from a DIFFERENT model than root (cross-verification).
                            ;; If only one model available, falls back to same model (better than nothing).
                             [refine-provider refine-model-map]
                             (or (llm/select-provider rlm-router
                                                      {:prefer :intelligence :capabilities #{:chat}
                                                       :exclude-model root-model})
                                 (llm/select-provider rlm-router
                                                      {:prefer :intelligence :capabilities #{:chat}}))
                             refine-config (when refine-provider
                                             {:api-key (:api-key refine-provider)
                                              :base-url (:base-url refine-provider)
                                              :default-model (:name refine-model-map)})
                             refine-model (or (:name refine-model-map) root-model)
                             refine-opts (cond-> {:spec spec
                                                  :messages [(llm/system (str "You are verifying and refining an answer to a specific query. "
                                                                              "Check the answer for accuracy, completeness, and correctness."))
                                                             (llm/user (str "<query>\n" query-str "\n</query>\n\n"
                                                                            "<answer>\n" answer-as-str "\n</answer>"))]
                                                  :config refine-config :model refine-model
                                                  :iterations max-refinements :threshold threshold}
                                           (seq stored-docs) (assoc :documents stored-docs))
                             raw-refine (llm/refine! refine-opts)
                            ;; Merge actual refinement cost (refine!* now returns :tokens and :cost)
                             _ (merge-cost! (:tokens raw-refine) (:cost raw-refine))
                             refined-str (:result raw-refine)
                           ;; If refinement didn't change the answer (converged or identical text),
                           ;; keep the original Clojure value from FINAL — no re-parse needed.
                             answer-unchanged? (or (:converged? raw-refine)
                                                   (= (str refined-str) answer-as-str))
                             parsed (if answer-unchanged?
                                      answer-value
                                    ;; Refinement modified the answer — parse the new text through spec.
                                      (if (and spec (string? refined-str))
                                        (try
                                          (spec/str->data-with-spec refined-str spec)
                                          (catch Exception _
                                            (let [fallback (llm/ask! {:spec spec
                                                                      :messages [(llm/system "Extract structured data.")
                                                                                 (llm/user (str "From:\n" refined-str))]
                                                                      :router rlm-router
                                                                      :prefer :cost :capabilities #{:chat}})]
                                              (merge-cost! (:tokens fallback) (:cost fallback))
                                              (:result fallback))))
                                        refined-str))]
                         {:answer parsed
                          :eval-scores (:final-score raw-refine)
                          :refinement-count (:iterations-count raw-refine)})
                     ;; No refinement — value is Clojure from FINAL.
                     ;; When spec is provided, coerce through SAP for type correctness
                     ;; (e.g., string "pass" → keyword :pass for :spec.type/keyword fields).
                       {:answer (if spec
                                  (try (spec/coerce-data-with-spec answer-value spec)
                                       (catch Exception _ answer-value))
                                  answer-value)
                        :eval-scores nil
                        :refinement-count 0})
                     duration-ms (util/elapsed-since start-time)
                     history-tokens (rlm-db/count-history-tokens @db-info-atom)]
                 (when (and verify? (seq @claims-atom))
                   (let [db-info @db-info-atom
                         conn (:conn db-info)
                         query-id (util/uuid)]
                     (doseq [claim @claims-atom]
                       (try
                         (d/transact! conn [(merge claim {:claim/id (util/uuid)
                                                          :claim/query-id query-id
                                                          :claim/verified? (boolean (get claim :claim/verified? true))})])
                         (catch Exception e
                           (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Failed to store claim"}))))))
                 ;; Final answer is already stored as structured assistant message in iteration-loop
                 ;; (with :thinking and linked :execution/* entities)
                 ;; Auto-vote via LLM: evaluate which learnings actually helped
                 (when-let [{:keys [tokens cost]} (rlm-core/reflect-vote! db-info rlm-router active-learnings query-str :success
                                                                          (rlm-db/str-truncate (pr-str final-answer) 300))]
                   (merge-cost! tokens cost))
                   ;; Auto-extract new learnings from successful multi-iteration queries
                 (when autolearn?
                   (let [inferred-scope (when (seq doc-names)
                                          (let [exts (distinct (filter #(str/starts-with? % ".") doc-names))]
                                            (when (= 1 (count exts))
                                              (str "*" (first exts)))))]
                     (async/thread
                       (try
                         (rlm-core/reflect-extract! db-info rlm-router query-str
                                                    (rlm-db/str-truncate (pr-str final-answer) 300)
                                                    iterations trace inferred-scope nil)
                         (catch Exception e
                           (trove/log! {:level :warn :data {:error (ex-message e)}
                                        :msg "Auto-extract failed (async)"}))))))
                 (when (seq learn-items)
                   (let [model-scope (str "model:" root-model)
                         inferred-scope (when (seq doc-names)
                                          (let [exts (distinct (filter #(str/starts-with? % ".") doc-names))]
                                            (when (= 1 (count exts))
                                              (str "*" (first exts)))))]
                     (doseq [{:keys [insight tags]} learn-items]
                       (when (and insight (not (str/blank? insight)))
                         (when (seq tags)
                           (doseq [tag-name tags]
                             (rlm-db/db-upsert-tag! db-info tag-name nil)))
                         (rlm-db/db-store-learning! db-info
                                                    {:learning/insight insight
                                                     :learning/context (str "Query: " (rlm-db/str-truncate query-str 200))
                                                     :learning/tags (vec tags)
                                                     :learning/scope (or inferred-scope model-scope)})))))
                 (rlm-core/rlm-debug! {:iterations iterations :duration-ms duration-ms
                                       :refinement-count refinement-count
                                       :confidence confidence
                                       :learn-count (count learn-items)
                                       :auto-voted (count active-learnings)
                                       :answer-preview (rlm-db/str-truncate (pr-str final-answer) 200)} "RLM query-env! finished (success)")
                 (try
                   (rlm-db/store-trajectory! @db-info-atom
                                             {:env-id env-id :query query-str :status :success
                                              :answer final-answer :iterations iterations
                                              :duration-ms duration-ms :model root-model
                                              :eval-score eval-scores
                                              :doc-pages (try (when-let [c (:conn @db-info-atom)]
                                                                (or (d/q '[:find (count ?e) . :where [?e :page/id _]] (d/db c)) 0))
                                                              (catch Exception _ 0))})
                   (catch Exception e
                     (trove/log! {:level :warn :data {:error (ex-message e)}
                                  :msg "Failed to store trajectory (success)"})))
                 (let [result-map (cond-> {:answer final-answer
                                           :raw-answer answer-value
                                           :eval-scores eval-scores
                                           :refinement-count refinement-count
                                           :trace trace
                                           :iterations iterations
                                           :duration-ms duration-ms
                                           :history-tokens history-tokens
                                           :tokens @total-tokens-atom
                                           :cost @total-cost-atom}
                                    (some? confidence) (assoc :confidence confidence)
                                    (seq learn-items) (assoc :learn learn-items)
                                    verify? (assoc :verified-claims (vec @claims-atom)))]
                   (call-hook! hooks-atom :query :post {:query query-str
                                                        :answer final-answer
                                                        :trace trace
                                                        :iterations iterations
                                                        :duration-ms duration-ms
                                                        :status :success})
                   result-map))))
           (finally
             (when hooks-atom
               (reset! hooks-atom (or original-hooks {}))))))))))

(defn list-trajectories
  "Lists trajectory records from an RLM environment.
   See trajectory/list-trajectories for opts."
  [env & [opts]]
  (trajectory/list-trajectories @(:db-info-atom env) opts))

(defn export-trajectories!
  "Exports filtered trajectories as JSONL for fine-tuning.
   See trajectory/export-trajectories! for opts."
  [env output-dir & [opts]]
  (trajectory/export-trajectories! @(:db-info-atom env) output-dir opts))

;; =============================================================================
;; Trace Pretty Printing
;; =============================================================================

(defn format-trace
  "Formats an RLM execution trace into a string. Internal helper."
  [trace {:keys [max-response-length max-code-length max-result-length show-stdout?]
          :or {max-response-length 500 max-code-length 300 max-result-length 200
               show-stdout? true}}]
  (let [truncate (fn [s n] (if (and s (> (count s) n)) (str (subs s 0 n) "...") s))
        format-execution (fn [{:keys [id code result stdout error execution-time-ms]}]
                           (str "║ [" id "] "
                                (truncate (str/replace (or code "") #"\n" " ") max-code-length) "\n"
                                "║     "
                                (if error
                                  (str "ERROR: " error)
                                  (str "=> " (truncate (pr-str result) max-result-length)))
                                (when execution-time-ms (str " (" execution-time-ms "ms)"))
                                (when (and show-stdout? stdout (not (str/blank? stdout)))
                                  (str "\n║     STDOUT: " (truncate stdout max-result-length)))))
        format-iteration (fn [{:keys [iteration response executions final?]}]
                           (str "\n"
                                "╔══════════════════════════════════════════════════════════════════════════════\n"
                                "║ ITERATION " iteration (when final? " [FINAL]") "\n"
                                "╠══════════════════════════════════════════════════════════════════════════════\n"
                                "║ RESPONSE:\n"
                                "║ " (str/replace (truncate response max-response-length) #"\n" "\n║ ") "\n"
                                (when (seq executions)
                                  (str "╠──────────────────────────────────────────────────────────────────────────────\n"
                                       "║ EXECUTIONS (" (count executions) "):\n"
                                       (str/join "\n"
                                                 (map format-execution executions))
                                       "\n"))
                                "╚══════════════════════════════════════════════════════════════════════════════"))]
    (if (empty? trace)
      "No trace entries."
      (str "RLM EXECUTION TRACE (" (count trace) " iterations)\n"
           (str/join "\n" (map format-iteration trace))))))

(defn pprint-trace
  "Pretty-prints an RLM execution trace to stdout for debugging.

   Prints the formatted trace to *out* and returns the formatted string.

   Params:
   `trace` - Vector of trace entries from query-env! result.
   `opts` - Map, optional:
     - :max-response-length - Truncate LLM response (default: 500).
     - :max-code-length - Truncate code blocks (default: 300).
     - :max-result-length - Truncate execution results (default: 200).
     - :show-stdout? - Show stdout output (default: true).

   Returns:
   String with formatted trace output (also printed to stdout)."
  ([trace] (pprint-trace trace {}))
  ([trace opts]
   (let [s (format-trace trace opts)]
     (println s)
     s)))

(defn print-trace
  "Prints an RLM execution trace to stdout. Alias for pprint-trace."
  ([trace] (pprint-trace trace))
  ([trace opts] (pprint-trace trace opts)))

;; =============================================================================
;; generate-qa-env! - Multi-stage Q&A generation from ingested documents
;; =============================================================================

;; -- Bloom's taxonomy difficulty levels --

(defn build-toc-based-selection-prompt
  "Builds prompt for Phase 1 fast-model selection using pre-gathered TOC data.
   All corpus structure is provided inline — no SCI loop needed."
  [{:keys [count difficulty-dist category-dist documents toc-entries page-nodes]}]
  (let [difficulties (str/join ", " (map name difficulty-dist))
        categories (str/join ", " (map name category-dist))
        ;; Format document overview
        docs-section
        (str/join "\n"
                  (map (fn [doc]
                         (str "- " (or (:document/title doc) (:document/name doc))
                              " (ID: " (:document/id doc) ")"
                              (when-let [abstract (:document/abstract doc)]
                                (str "\n  Abstract: " (rlm-db/str-truncate abstract 300)))))
                       documents))
        ;; Format TOC — indented by level for structure
        toc-section
        (str/join "\n"
                  (map (fn [e]
                         (let [level-num (let [l (:document.toc/level e)]
                                           (if (string? l)
                                             (parse-long (re-find #"\d+" (str l)))
                                             (or l 0)))
                               indent (str/join (repeat (min 4 (or level-num 0)) "  "))]
                           (str indent "- [" (:document.toc/id e) " p" (:document.toc/target-page e) "] "
                                (:document.toc/title e)
                                (when-let [desc (:document.toc/description e)]
                                  (str " — " (rlm-db/str-truncate desc 100))))))
                       toc-entries))
        ;; Format page content summaries — grouped by document
        content-section
        (str/join "\n"
                  (->> page-nodes
                       (filter #(or (not-empty (:page.node/content %))
                                    (not-empty (:page.node/description %))))
                       (map (fn [node]
                              (str "  [" (:page.node/document-id node)
                                   " p" (:page.node/page-id node)
                                   " " (name (or (:page.node/type node) :unknown)) "] "
                                   (or (not-empty (:page.node/content node))
                                       (:page.node/description node)))))))]
    (str "You are selecting diverse passages from a document corpus for question-answer generation.

YOUR TASK: Select exactly " count " passages that will serve as source material for generating Q&A pairs.
Each passage should come from a distinct section of the corpus to maximize coverage.

AVAILABLE DOCUMENTS:
" docs-section "

TABLE OF CONTENTS:
" (if (seq toc-section) toc-section "(no TOC entries)") "

CONTENT SUMMARIES (truncated previews):
" (if (seq content-section) content-section "(no content previews)") "

SELECTION CRITERIA:
- If multiple documents exist, select from ALL of them proportionally
- Within each document, spread selections across different chapters/sections
- Avoid selecting adjacent pages — skip at least 2-3 pages between selections
- Cover different content types: definitions, processes, examples, data, arguments
- Assign difficulty levels using round-robin across: " difficulties "
- Assign categories using round-robin across: " categories "

SKIP passages that are:
- Table of contents or index pages
- Pages with only images and no descriptive text
- Extremely short content (less than 2 sentences)
- Repeated/boilerplate content (headers, footers, page numbers)

For each passage, provide:
- document-id: The document ID from the list above
- page: The 0-based page number
- section-title: The section or heading title from the TOC
- content-summary: A 1-2 sentence description of what makes this passage suitable for Q&A
- suggested-difficulty: One of " difficulties "
- suggested-category: One of " categories)))

(defn build-generation-prompt
  "Builds prompt for Phase 2: Q&A generation from selected passages.
   Supports optional persona styling, k-candidates selection, and multi-hop mode."
  [passages batch-index {:keys [persona k-candidates multi-hop?]}]
  (let [passage-descriptions
        (str/join "\n\n"
                  (map-indexed
                   (fn [i p]
                     (str "PASSAGE " (inc i) ":\n"
                          "  Document: " (:document-id p) "\n"
                          "  Page: " (:page p) "\n"
                          "  Section: " (:section-title p) "\n"
                          "  Summary: " (:content-summary p) "\n"
                          "  Target difficulty: " (name (or (:suggested-difficulty p) :understand)) "\n"
                          "  Target category: " (name (or (:suggested-category p) :factual))))
                   passages))
        k (or k-candidates 1)
        per-passage-count (if (> k 1)
                            (str "Generate " k " candidate Q&A pairs per passage. "
                                 "Rate each candidate 1-5 on quality (groundedness, clarity, specificity). "
                                 "Then keep only the BEST candidate per passage in your final output.")
                            "Generate 1-2 Q&A pairs per passage.")
        persona-instruction (when persona
                              (str "\n\nPERSONA: " (get GENERATION_PERSONAS persona
                                                        (str "You are " (name persona) ". Ask questions from this perspective.")) "\n"))
        multi-hop-instruction (when multi-hop?
                                "\n\nMULTI-HOP QUESTIONS: Some passages are paired across different sections. For paired passages, generate questions that REQUIRE information from BOTH passages to answer — the answer should synthesize facts from multiple sources. Mark these as 'analyze' or 'create' difficulty.\n")]
    (str "You are generating high-quality question-answer pairs from specific passages in the corpus.
This is batch " batch-index ". " per-passage-count
         (or persona-instruction "")
         (or multi-hop-instruction "") "

PASSAGES TO PROCESS:
" passage-descriptions "

STEP-BY-STEP INSTRUCTIONS:
1. For each passage above, search for its actual content:
   (search-page-nodes \"<key terms from section>\" 5 {:document-id \"<doc-id>\"})
   or (list-page-nodes {:document-id \"<doc-id>\" :page-id \"<page>\"})
2. Read the full content of relevant page nodes using (get-page-node node-id)
3. " per-passage-count "

CRITICAL REQUIREMENTS FOR EACH Q&A PAIR:
- question: Self-contained, understandable WITHOUT seeing the document. Never reference
  'the document', 'the text', 'this section', 'the author'. A reader should understand
  what is being asked without any context.
- answer: Accurate, grounded in the source text. Should be 1-3 sentences.
- evidence-span: A VERBATIM QUOTE from the source document. Copy the exact text.
  This is NOT a paraphrase — it must be findable in the document word-for-word.
  Keep it to 1-3 sentences maximum.
- source-document: The document ID from list-documents
- source-page: The page number (0-based) where the evidence appears
- source-section: The section heading (if known)
- difficulty: Use the suggested difficulty from the passage description
- category: Use the suggested category from the passage description

EXAMPLES OF GOOD QUESTIONS:
  Q: What is the minimum capitalization requirement for banks under Basel III?
  A: Banks must maintain a minimum Common Equity Tier 1 ratio of 4.5% of risk-weighted assets.
  evidence-span: \"Banks are required to maintain a minimum CET1 ratio of 4.5 percent of risk-weighted assets\"

EXAMPLES OF BAD QUESTIONS (DO NOT GENERATE THESE):
  BAD: What does this document say about banks? (references 'this document')
  BAD: What is discussed in Section 3? (references section numbers)
  BAD: What is Basel III? (answerable without the document — too generic)
  BAD: According to the text, what is mentioned? (references 'the text')

After generating all Q&A pairs, call (FINAL {:questions [...]}).")))

(defn create-multi-hop-pairs
  "Creates multi-hop passage pairs from selected passages.
   Pairs passages from different sections/documents for cross-reference questions."
  [passages]
  (when (>= (clojure.core/count passages) 2)
    (let [;; Group by document
          by-doc (group-by :document-id passages)
          pairs (atom [])
          passage-vec (vec passages)]
      ;; Cross-document pairs (highest value)
      (when (> (clojure.core/count by-doc) 1)
        (let [doc-ids (vec (keys by-doc))]
          (doseq [i (range (min 3 (dec (clojure.core/count doc-ids))))]
            (let [p1 (first (get by-doc (nth doc-ids i)))
                  p2 (first (get by-doc (nth doc-ids (inc i))))]
              (when (and p1 p2)
                (swap! pairs conj [p1 p2]))))))
      ;; Within-document pairs from different sections (skip adjacent pages)
      (doseq [[_doc-id doc-passages] by-doc]
        (let [sorted (vec (sort-by :page doc-passages))]
          (doseq [i (range (dec (clojure.core/count sorted)))]
            (let [p1 (nth sorted i)
                  p2 (nth sorted (min (+ i 2) (dec (clojure.core/count sorted))))]
              (when (and p1 p2 (not= p1 p2)
                         (> (Math/abs (- (:page p2) (:page p1))) 1))
                (swap! pairs conj [p1 p2]))))))
      ;; Return up to 3 pairs
      (vec (take 3 @pairs)))))

(defn build-verification-prompt
  "Builds prompt for Phase 3: verify Q&A pairs against source material."
  [questions]
  (let [question-descriptions
        (str/join "\n\n"
                  (map-indexed
                   (fn [i q]
                     (str "QUESTION " i " (index " i "):\n"
                          "  Q: " (:question q) "\n"
                          "  A: " (:answer q) "\n"
                          "  Evidence: " (rlm-db/str-truncate (or (:evidence-span q) "") 200) "\n"
                          "  Source: " (:source-document q) " page " (:source-page q)))
                   questions))]
    (str "You are a quality auditor verifying question-answer pairs against source documents.
For each Q&A pair below, verify it meets quality standards.

Q&A PAIRS TO VERIFY:
" question-descriptions "

FOR EACH QUESTION, PERFORM THESE CHECKS:
1. GROUNDED: Search for the evidence-span in the source document:
   (search-page-nodes \"<key phrase from evidence>\" 5 {:document-id \"<doc-id>\"})
   Does the evidence span actually exist (or closely match) in the document? Does it support the answer?

2. NON-TRIVIAL: Is this question meaningful? Would answering it require actually reading the document?
   FAIL if: the question just asks 'What is [heading text]?' or could be answered by reading only titles.

3. SELF-CONTAINED: Can someone understand this question without seeing the source document?
   FAIL if: the question references 'the document', 'this section', 'the text', 'the author' etc.

4. ANSWERABLE: Can the question be answered solely from the evidence span provided?
   The evidence should contain sufficient information to derive the answer without external knowledge.
   FAIL if: the answer requires facts not present in the evidence span.

5. ANSWER-CONSISTENT: Does the provided answer accurately match what the question asks?
   The answer should directly address the question's intent and be supported by the evidence.
   FAIL if: the answer addresses a different aspect, misinterprets the question, or contradicts the evidence.

VERDICT CRITERIA:
- pass: All five checks pass
- fail: Evidence is fabricated/hallucinated, question is trivially bad, OR answer contradicts evidence
- needs-revision: Minor issues (e.g., evidence is paraphrased rather than verbatim, but answer is correct)

After verifying all questions, call (FINAL {:verifications [...]}).
Each verification must include: question-index, grounded, non-trivial, self-contained, answerable, answer-consistent, verdict, and revision-note (if applicable).")))

;; -- Utility functions --

(defn compute-distribution
  "Computes target counts per item, distributing total-count evenly across items."
  [total-count items]
  (let [item-vec (vec items)
        n (clojure.core/count item-vec)
        base (quot total-count n)
        remainder (rem total-count n)]
    (into {} (map-indexed (fn [i item]
                            [item (if (< i remainder) (inc base) base)])
                          item-vec))))

(defn dedup-batch
  "Deduplicates a single batch of questions via LLM. Returns kept questions."
  [questions rlm-router]
  (let [numbered-list
        (str/join "\n"
                  (map-indexed
                   (fn [i q]
                     (str "[" i "] " (:question q)))
                   questions))
        result (llm/ask!
                {:spec DEDUP_SPEC
                 :messages
                 [(llm/system "You are a deduplication engine. Given a numbered list of questions, identify semantic duplicates — questions that ask the same thing in different words. For each group of duplicates, keep only the BEST version (most clear, specific, and well-phrased). Return the 0-based indices of questions to KEEP.")
                  (llm/user (str "Identify and remove semantic duplicates from this list. Return indices of questions to KEEP (one per duplicate group, choosing the best phrasing):\n\n" numbered-list))]
                 :router rlm-router
                 :prefer :cost :capabilities #{:chat}})
        keep-indices (set (or (:keep-indices (:result result)) []))
        kept (vec (keep-indexed
                   (fn [i q]
                     (when (contains? keep-indices i)
                       q))
                   questions))]
    ;; Fallback: if LLM returns empty or error, keep all
    (if (seq kept) kept questions)))

(def ^:private DEDUP_WINDOW_SIZE
  "Max questions per dedup LLM call to avoid context overload."
  20)

(defn deduplicate-questions
  "Removes semantically duplicate questions using LLM judgment.

   Processes in sliding windows of 20 to avoid overwhelming the LLM
   with too many questions in a single call.

   Params:
   `questions` - Vector of question maps with :question key.
   `rlm-router` - Router for LLM calls.

   Returns:
   Vector of unique questions."
  [questions rlm-router]
  (if (<= (clojure.core/count questions) 1)
    questions
    (let [total (clojure.core/count questions)
          ;; Small batch: single pass
          kept (if (<= total DEDUP_WINDOW_SIZE)
                 (dedup-batch questions rlm-router)
                 ;; Large batch: process in windows, then cross-window pass
                 (let [windows (partition-all DEDUP_WINDOW_SIZE questions)
                       per-window (vec (mapcat #(dedup-batch (vec %) rlm-router) windows))]
                   ;; Cross-window dedup on accumulated results
                   (if (> (clojure.core/count per-window) 1)
                     (dedup-batch per-window rlm-router)
                     per-window)))
          dropped-count (- total (clojure.core/count kept))]
      (when (pos? dropped-count)
        (trove/log! {:level :info :id ::qa-dedup
                     :data {:original total
                            :kept (clojure.core/count kept)
                            :dropped dropped-count}
                     :msg "LLM deduplication complete"}))
      kept)))

(defn revise-questions
  "Revises questions that received needs-revision verdict.

   Params:
   `questions` - Vector of question maps with :revision-note key.
   `rlm-router` - Router for LLM calls.

   Returns:
   Vector of revised question maps (without :revision-note)."
  [questions rlm-router]
  (if (empty? questions)
    []
    (let [revision-descriptions
          (str/join "\n\n"
                    (map-indexed
                     (fn [i q]
                       (str "QUESTION " i ":\n"
                            "  Q: " (:question q) "\n"
                            "  A: " (:answer q) "\n"
                            "  Evidence: " (rlm-db/str-truncate (or (:evidence-span q) "") 200) "\n"
                            "  Source: " (:source-document q) " page " (:source-page q) "\n"
                            "  Issue: " (or (:revision-note q) "Minor quality issue")))
                     questions))
          result (llm/ask!
                  {:spec REVISION_SPEC
                   :messages
                   [(llm/system "You are a question revision engine. Given Q&A pairs with identified issues, fix the problems while preserving the core question intent, answer accuracy, and evidence grounding. Keep the same source-document, source-page, difficulty, and category. Fix only the identified issue.")
                    (llm/user (str "Revise these questions to fix the identified issues:\n\n" revision-descriptions))]
                   :router rlm-router
                   :prefer :cost :capabilities #{:chat}})
          revised (or (:questions (:result result)) [])]
      (trove/log! {:level :info :id ::qa-revision
                   :data {:input (clojure.core/count questions)
                          :revised (clojure.core/count revised)}
                   :msg "Question revision complete"})
      ;; Fallback: if revision fails, return originals without revision-note
      (if (seq revised)
        revised
        (mapv #(dissoc % :revision-note) questions)))))

(defn filter-verified-questions
  "Splits questions into passed/needs-revision/dropped based on verification results."
  [questions verifications]
  (let [;; Pad verifications with :pass for any missing indices
        ver-map (into {} (map (fn [v] [(:question-index v) v]) verifications))
        results (map-indexed
                 (fn [i q]
                   (let [v (get ver-map i {:verdict :pass})
                         verdict (:verdict v)]
                     (when-not (= :pass verdict)
                       (trove/log! {:level :debug :id ::qa-filter
                                    :data {:index i :verdict verdict :note (:revision-note v)
                                           :question (rlm-db/str-truncate (:question q) 100)}
                                    :msg "Question failed verification"}))
                     {:question q :verification v
                      :passed? (= :pass verdict)
                      :needs-revision? (= :needs-revision verdict)}))
                 questions)]
    {:passed (mapv :question (filter :passed? results))
     :needs-revision (mapv (fn [r] (assoc (:question r) :revision-note (get-in r [:verification :revision-note])))
                           (filter :needs-revision? results))
     :dropped (mapv :question (filter #(and (not (:passed? %)) (not (:needs-revision? %))) results))
     :results (mapv :verification results)}))

;; -- Main pipeline --

(defn fork-env-for-query
  "Creates a copy of the env with a fresh locals-atom for parallel query-env! calls.
   Shares immutable config, db-info-atom, and query functions. Isolates mutable locals
   so concurrent queries don't clobber each other's SCI variables."
  [env]
  (assoc env :locals-atom (atom {})))

(defn generate-qa-env!
  "Generates question-answer pairs from ingested documents.

   Uses a multi-stage pipeline leveraging the RLM's iterative code execution:

   Phase 1 - Passage Selection: Explores the corpus structure via TOC and content
   search, selects diverse passages covering different sections and topics.

   Phase 2 - Q&A Generation: For each batch of selected passages, generates
   grounded question-answer pairs with evidence spans extracted from source text.

   Phase 3 - Verification: Each Q&A pair is verified against the source material
   for groundedness, non-triviality, and self-containedness.

   Phase 4 - Deduplication: Near-duplicate questions are removed and diversity
   across difficulty levels and categories is verified.

   Params:
   `env` - RLM environment from create-env with ingested documents.
   `opts` - Map, optional:
     - :count - Integer. Target number of Q&A pairs (default: 10).
     - :difficulty - Set of keywords. Bloom's taxonomy levels to include
       (default: #{:remember :understand :apply :analyze :evaluate :create}).
     - :categories - Set of keywords. Question types to include
       (default: #{:factual :inferential :comparative :analytical :definitional :procedural}).
     - :model - String. Override default model.
      - :batch-size - Integer. Passages per generation batch (default: 5).
      - :parallel - Integer. Number of parallel batch workers for Phase 2 (default: 3).
      - :selection-model - String. Fast/cheap model for Phase 1 passage selection (default: :model).
      - :k-candidates - Integer. Generate k candidates per passage, keep best (default: 1).
      - :multi-hop? - Boolean. Generate cross-section questions from passage pairs (default: false).
      - :personas - Set of keywords. Persona styles to rotate across batches for diversity.
        Available: :student, :researcher, :practitioner, :examiner, :journalist (default: nil).
      - :verify? - Boolean. Run verification phase (default: true).
      - :debug? - Boolean. Verbose logging (default: false).

   Returns:
   Map with:
     - :questions - Vector of verified Q&A maps, each with :question, :answer,
       :evidence-span, :source-document, :source-page, :source-section,
       :difficulty, :category.
     - :dropped-questions - Vector of Q&A maps that failed verification.
     - :verification-results - Vector of verification result maps.
     - :phase-traces - Map of {:selection :generation :verification} traces.
     - :stats - Map with :total-generated, :passed-verification, :duplicates-removed,
       :final-count, :by-difficulty (counts), :by-category (counts).
     - :iterations - Total iterations across all phases.
     - :duration-ms - Total execution time."
  ([env] (generate-qa-env! env {}))
  ([env {:keys [count difficulty categories model batch-size verify? debug? parallel
                selection-model k-candidates multi-hop? personas]
         :or {count 10
              difficulty #{:remember :understand :apply :analyze :evaluate :create}
              categories #{:factual :inferential :comparative :analytical :definitional :procedural}
              batch-size 5
              verify? true
              debug? false
              parallel 3
              k-candidates 1}}]
   (when-not (:db-info-atom env)
     (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))

   (let [start-time (System/nanoTime)
         config (:config env)
         rlm-router (:router env)
         effective-model (or model (:default-model config))
          ;; Select 1.5x passages for filtering headroom
         passage-count (int (Math/ceil (* count 1.5)))

         ;; ===== PHASE 1: Passage Selection (fast-model TOC routing) =====
         effective-selection-model (or selection-model effective-model)
         db-info @(:db-info-atom env)
         _ (trove/log! {:level :info :id ::qa-phase1
                        :data {:target-passages passage-count :target-questions count
                               :selection-model effective-selection-model}
                        :msg "Phase 1: Selecting passages via fast-model TOC routing"})
          ;; Gather corpus structure programmatically (no SCI loop)
         corpus-documents (rlm-db/db-list-documents db-info)
         corpus-toc (rlm-db/db-list-toc-entries db-info)
         corpus-nodes (rlm-db/db-list-page-nodes db-info {:limit 500})
         selection-prompt (build-toc-based-selection-prompt
                           {:count passage-count
                            :difficulty-dist difficulty
                            :category-dist categories
                            :documents corpus-documents
                            :toc-entries corpus-toc
                            :page-nodes corpus-nodes})
         selection-result (llm/ask! {:spec CHUNK_SELECTION_SPEC
                                     :messages [(llm/system "You are a passage selection engine for Q&A generation. Select diverse passages from the corpus based on the provided structure. Return your selections in the required JSON format.")
                                                (llm/user selection-prompt)]
                                     :router rlm-router
                                     :prefer :cost :capabilities #{:chat}})
         passages (or (:passages (:result selection-result)) [])
         _ (trove/log! {:level :info :id ::qa-phase1-done
                        :data {:passages-selected (clojure.core/count passages)
                               :model-used effective-selection-model}
                        :msg "Phase 1 complete"})

         ;; ===== PHASE 2: Batched Q&A Generation (parallel via core.async) =====
         ;; Prepare persona rotation
         persona-vec (when (seq personas) (vec personas))
         ;; Add multi-hop passage pairs if enabled
         multi-hop-pairs (when multi-hop? (create-multi-hop-pairs passages))
         _ (trove/log! {:level :info :id ::qa-phase2
                        :data {:passages (clojure.core/count passages) :batch-size batch-size
                               :parallel parallel
                               :k-candidates k-candidates
                               :multi-hop-pairs (clojure.core/count (or multi-hop-pairs []))
                               :personas (when persona-vec (mapv name persona-vec))}
                        :msg "Phase 2: Generating Q&A pairs in parallel batches"})
         ;; Build standard batches from passages
         standard-batches (vec (partition-all batch-size passages))
         ;; Add multi-hop batches (pairs flattened into batches)
         multi-hop-batches (when (seq multi-hop-pairs)
                             (vec (partition-all batch-size (mapcat identity multi-hop-pairs))))
         all-batches (into standard-batches (or multi-hop-batches []))
         batch-count (clojure.core/count all-batches)
         standard-batch-count (clojure.core/count standard-batches)
         work-items (map-indexed
                     (fn [idx batch]
                       (let [is-multi-hop? (>= idx standard-batch-count)
                             persona (when persona-vec
                                       (nth persona-vec (mod idx (clojure.core/count persona-vec))))]
                         {:batch-idx idx :batch (vec batch)
                          :persona persona :multi-hop? is-multi-hop?
                          :k-candidates k-candidates}))
                     all-batches)
         result-chan (async/chan batch-count)
         _ (async/pipeline-blocking
            parallel

            result-chan
            (map (fn [{:keys [batch-idx batch persona multi-hop? k-candidates]}]
                   (trove/log! {:level :debug :id ::qa-batch
                                :data {:batch batch-idx :passages-in-batch (clojure.core/count batch)
                                       :persona (when persona (name persona))
                                       :multi-hop? multi-hop?}
                                :msg (str "Generating batch " batch-idx)})
                   (try
                     (let [forked-env (fork-env-for-query env)
                           prompt (build-generation-prompt batch batch-idx
                                                           {:persona persona
                                                            :k-candidates k-candidates
                                                            :multi-hop? multi-hop?})
                           result (query-env! forked-env prompt
                                              {:spec QUESTIONIFY_SPEC
                                               :debug? debug?
                                               :max-iterations 20
                                               :model effective-model})]
                       {:batch-idx batch-idx
                        :questions (or (get-in result [:answer :questions]) [])
                        :trace (:trace result)
                        :iterations (or (:iterations result) 0)})
                     (catch Exception e
                       (trove/log! {:level :error :id ::qa-batch-error
                                    :data {:batch batch-idx :error (ex-message e)}
                                    :msg "Batch generation failed"})
                       {:batch-idx batch-idx
                        :questions []
                        :trace []
                        :iterations 0}))))
            (async/to-chan! work-items))
         ;; Collect results from pipeline and sort by batch index for deterministic order
         generation-results (let [results (loop [acc []]
                                            (if-let [result (async/<!! result-chan)]
                                              (recur (conj acc result))
                                              acc))]
                              (vec (sort-by :batch-idx results)))
         all-questions (vec (mapcat :questions generation-results))
         _ (trove/log! {:level :info :id ::qa-phase2-done
                        :data {:total-generated (clojure.core/count all-questions)}
                        :msg "Phase 2 complete"})

         ;; ===== PHASE 3: Verification + Revision (optional) =====
         {:keys [passed dropped results ver-trace ver-iterations]}
         (if (and verify? (seq all-questions))
           (do
             (trove/log! {:level :info :id ::qa-phase3
                          :data {:questions-to-verify (clojure.core/count all-questions)}
                          :msg "Phase 3: Verifying Q&A pairs against source material"})
             (let [ver-prompt (build-verification-prompt all-questions)
                   ver-result (query-env! env ver-prompt
                                          {:spec VERIFICATION_SPEC
                                           :debug? debug?
                                           :max-iterations 15
                                           :model effective-model})
                   verifications (or (get-in ver-result [:answer :verifications]) [])
                   filtered (filter-verified-questions all-questions verifications)
                   ;; Revision sub-phase: revise needs-revision questions instead of dropping
                   revised (when (seq (:needs-revision filtered))
                             (trove/log! {:level :info :id ::qa-phase3-revision
                                          :data {:needs-revision (clojure.core/count (:needs-revision filtered))}
                                          :msg "Phase 3: Revising questions with minor issues"})
                             (revise-questions (:needs-revision filtered) rlm-router))
                   all-passed (into (:passed filtered) (or revised []))]
               (trove/log! {:level :info :id ::qa-phase3-done
                            :data {:passed (clojure.core/count (:passed filtered))
                                   :revised (clojure.core/count (or revised []))
                                   :dropped (clojure.core/count (:dropped filtered))}
                            :msg "Phase 3 complete"})
               {:passed all-passed
                :dropped (:dropped filtered)
                :results (:results filtered)
                :ver-trace (:trace ver-result)
                :ver-iterations (or (:iterations ver-result) 0)}))
           {:passed all-questions :dropped [] :results []
            :ver-trace [] :ver-iterations 0})

         ;; ===== PHASE 4: Deduplication & Final Selection =====
         deduped (deduplicate-questions passed rlm-router)
         final-questions (vec (take count deduped))

         ;; ===== Build stats =====
         duration-ms (util/elapsed-since start-time)
         total-iterations (+ (or (:iterations selection-result) 0)
                             (reduce + 0 (map :iterations generation-results))
                             ver-iterations)
         stats {:total-generated (clojure.core/count all-questions)
                :passed-verification (clojure.core/count passed)
                :duplicates-removed (- (clojure.core/count passed)
                                       (clojure.core/count deduped))
                :final-count (clojure.core/count final-questions)
                :by-difficulty (frequencies (map :difficulty final-questions))
                :by-category (frequencies (map :category final-questions))}]

     (trove/log! {:level :info :id ::qa-done :data stats
                  :msg "generate-qa-env! complete"})

     {:questions final-questions
      :dropped-questions dropped
      :verification-results results
      :phase-traces {:selection (:trace selection-result)
                     :generation (mapv :trace generation-results)
                     :verification ver-trace}
      :stats stats
      :iterations total-iterations
      :duration-ms duration-ms})))

;; =============================================================================
;; save-qa! - Serialize Q&A results to EDN and Markdown
;; =============================================================================

(defn save-qa!
  "Saves generate-qa-env! results to EDN and/or Markdown files.

   Params:
   `result` - Map. Result from generate-qa-env!.
   `path` - String. Base file path without extension.
   `opts` - Map, optional:
     - :formats - Set of keywords. Output formats (default: #{:edn :markdown}).
     - :include-dropped? - Boolean. Include dropped questions (default: false).
     - :include-stats? - Boolean. Include generation stats (default: true).

   Returns:
   Map with :files - vector of written file paths."
  ([result path] (save-qa! result path {}))
  ([result path {:keys [formats include-dropped? include-stats?]
                 :or {formats #{:edn :markdown}
                      include-dropped? false
                      include-stats? true}}]
   (let [written-files (atom [])]

     ;; EDN output
     (when (contains? formats :edn)
       (let [edn-path (str path ".edn")
             data (cond-> {:questions (:questions result)}
                    include-dropped? (assoc :dropped-questions (:dropped-questions result))
                    include-stats? (assoc :stats (:stats result)))]
         (spit edn-path (pr-str data))
         (swap! written-files conj edn-path)
         (trove/log! {:level :info :id ::save-qa-edn
                      :data {:path edn-path :questions (clojure.core/count (:questions result))}
                      :msg "Saved Q&A results as EDN"})))

     ;; Markdown output
     (when (contains? formats :markdown)
       (let [md-path (str path ".md")
             questions (:questions result)
             sb (StringBuilder.)]
         (.append sb "# Generated Q&A Pairs\n\n")
         ;; Stats section
         (when include-stats?
           (let [s (:stats result)]
             (.append sb "## Statistics\n\n")
             (.append sb (str "| Metric | Value |\n|--------|-------|\n"))
             (.append sb (str "| Total generated | " (:total-generated s) " |\n"))
             (.append sb (str "| Passed verification | " (:passed-verification s) " |\n"))
             (.append sb (str "| Duplicates removed | " (:duplicates-removed s) " |\n"))
             (.append sb (str "| Final count | " (:final-count s) " |\n"))
             (.append sb (str "| By difficulty | " (pr-str (:by-difficulty s)) " |\n"))
             (.append sb (str "| By category | " (pr-str (:by-category s)) " |\n\n"))))
         ;; Questions section
         (.append sb "## Questions\n\n")
         (doseq [[i q] (map-indexed vector questions)]
           (.append sb (str "### Q" (inc i)
                            " [" (name (or (:difficulty q) :unknown))
                            " / " (name (or (:category q) :unknown)) "]\n\n"))
           (.append sb (str "**Question:** " (:question q) "\n\n"))
           (.append sb (str "**Answer:** " (:answer q) "\n\n"))
           (when (:evidence-span q)
             (.append sb (str "**Evidence:**\n> " (:evidence-span q) "\n\n")))
           (.append sb (str "**Source:** " (:source-document q)
                            ", page " (:source-page q)
                            (when (:source-section q) (str " — " (:source-section q)))
                            "\n\n"))
           (.append sb "---\n\n"))
         ;; Dropped questions section
         (when (and include-dropped? (seq (:dropped-questions result)))
           (.append sb "## Dropped Questions\n\n")
           (doseq [[i q] (map-indexed vector (:dropped-questions result))]
             (.append sb (str "### Dropped Q" (inc i) "\n\n"))
             (.append sb (str "**Question:** " (:question q) "\n\n"))
             (.append sb (str "**Answer:** " (:answer q) "\n\n"))
             (.append sb "---\n\n")))
         (spit md-path (str sb))
         (swap! written-files conj md-path)
         (trove/log! {:level :info :id ::save-qa-md
                      :data {:path md-path :questions (clojure.core/count questions)}
                      :msg "Saved Q&A results as Markdown"})))

     {:files @written-files})))

;; =============================================================================
;; PageIndex Core (merged from internal.pageindex.core)
;; =============================================================================

;; Ensure java.time.Instant prints as #inst in EDN (pprint only knows java.util.Date)
(defmethod print-method Instant [^Instant inst ^java.io.Writer w]
  (print-method (Date/from inst) w))

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
        ;; Build mapping of local-id -> UUID for all nodes on this page
        ;; Collect IDs from both :page.node/id and :document.toc/id
        id-mapping (reduce
                    (fn [acc node]
                      (let [local-id (or (:page.node/id node) (:document.toc/id node))]
                        (if local-id
                          (assoc acc local-id (str (util/uuid)))
                          acc)))
                    {}
                    nodes)
        ;; Translate IDs in all nodes (both namespaces)
        translated-nodes (mapv
                          (fn [node]
                            (cond-> node
                               ;; Translate :page.node/id
                              (:page.node/id node)
                              (assoc :page.node/id (get id-mapping (:page.node/id node)))

                               ;; Translate :page.node/parent-id (if exists and in mapping)
                              (and (:page.node/parent-id node)
                                   (get id-mapping (:page.node/parent-id node)))
                              (assoc :page.node/parent-id (get id-mapping (:page.node/parent-id node)))

                               ;; Translate :page.node/target-section-id (if exists and in mapping)
                              (and (:page.node/target-section-id node)
                                   (get id-mapping (:page.node/target-section-id node)))
                              (assoc :page.node/target-section-id (get id-mapping (:page.node/target-section-id node)))

                               ;; Translate :document.toc/id
                              (:document.toc/id node)
                              (assoc :document.toc/id (get id-mapping (:document.toc/id node)))

                               ;; Translate :document.toc/parent-id (if exists and in mapping)
                              (and (:document.toc/parent-id node)
                                   (get id-mapping (:document.toc/parent-id node)))
                              (assoc :document.toc/parent-id (get id-mapping (:document.toc/parent-id node)))

                               ;; Translate :document.toc/target-section-id (if exists and in mapping)
                               ;; Note: This references a :page.node/id, so we use the same mapping
                              (and (:document.toc/target-section-id node)
                                   (get id-mapping (:document.toc/target-section-id node)))
                              (assoc :document.toc/target-section-id (get id-mapping (:document.toc/target-section-id node)))))
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
;; Continuation Grouping
;; =============================================================================

(defn- visual-node?
  "Returns true if node is a visual element (image or table)."
  [node]
  (#{:image :table} (:page.node/type node)))

(defn- last-visual-of-type
  "Finds the last visual node of the given type on a page."
  [page node-type]
  (->> (:page/nodes page)
       (filter #(= node-type (:page.node/type %)))
       last))

(defn group-continuations
  "Groups continuation nodes across pages by assigning a shared :page.node/group-id.
   
   Walks pages in order. When a visual node (image/table) has continuation?=true,
   looks back to the last same-type node on the preceding page and assigns both
   the same group-id UUID. Propagates group-id forward for 3+ page chains.
   
   Params:
   `pages` - Vector of page maps with :page/nodes (must have UUIDs already).
   
   Returns:
   Updated pages with :page.node/group-id assigned to grouped nodes."
  [pages]
  (if (< (count pages) 2)
    pages
    (let [;; Build a mutable state: node-id -> group-id
          group-assignments (atom {})
          ;; Process pages pairwise
          _ (doseq [i (range 1 (count pages))]
              (let [prev-page (nth pages (dec (long i)))
                    curr-page (nth pages i)]
                (doseq [node (:page/nodes curr-page)]
                  (when (and (visual-node? node)
                             (:page.node/continuation? node))
                    (let [node-type (:page.node/type node)
                          prev-visual (last-visual-of-type prev-page node-type)]
                      (when prev-visual
                        (let [prev-id (:page.node/id prev-visual)
                              curr-id (:page.node/id node)
                              ;; Propagate existing group-id or create new one
                              existing-group (get @group-assignments prev-id)
                              group-id (or existing-group (str (util/uuid)))]
                          ;; Assign group-id to both predecessor and current
                          (swap! group-assignments assoc prev-id group-id)
                          (swap! group-assignments assoc curr-id group-id))))))))
          assignments @group-assignments]
      (if (empty? assignments)
        pages
        (mapv (fn [page]
                (update page :page/nodes
                        (fn [nodes]
                          (mapv (fn [node]
                                  (if-let [gid (get assignments (:page.node/id node))]
                                    (assoc node :page.node/group-id gid)
                                    node))
                                nodes))))
              pages)))))

;; =============================================================================
;; TOC Post-Processing
;; =============================================================================

(defn- collect-all-nodes
  "Collects all nodes from all pages into a flat sequence.
   
   Params:
   `pages` - Vector of page maps with :page/nodes.
   
   Returns:
   Lazy sequence of all nodes across all pages."
  [pages]
  (mapcat :page/nodes pages))

(defn- has-toc-entries?
  "Returns true if any TocEntry nodes exist in the pages.
   
   Params:
   `pages` - Vector of page maps with :page/nodes.
   
   Returns:
   Boolean."
  [pages]
  (boolean (some #(= :toc-entry (:document.toc/type %)) (collect-all-nodes pages))))

(defn- heading-level->toc-level
  "Converts heading level (h1, h2, etc.) to TOC level (l1, l2, etc.).
   
   Params:
   `heading-level` - String like 'h1', 'h2', etc.
   
   Returns:
   String like 'l1', 'l2', etc."
  [heading-level]
  (when heading-level
    (str "l" (subs heading-level 1))))

(defn- build-toc-from-structure
  "Builds TOC entries from Section/Heading structure.
   
   Scans all pages for Section nodes that have associated Heading nodes,
   and creates TocEntry nodes linking to them.
   
   Params:
   `pages` - Vector of page maps with :page/nodes.
   
   Returns:
   Vector of TocEntry node maps, or empty vector if no sections found."
  [pages]
  (let [all-nodes (vec (collect-all-nodes pages))
        ;; Build a map of section-id -> heading for that section
        ;; A heading belongs to a section if its parent-id matches the section's id
        section-headings (reduce
                          (fn [acc node]
                            (if (and (= :heading (:page.node/type node))
                                     (:page.node/parent-id node))
                              (assoc acc (:page.node/parent-id node) node)
                              acc))
                          {}
                          all-nodes)
        ;; Find all sections and create TOC entries
        sections (filter #(= :section (:page.node/type %)) all-nodes)
        ;; Find page index for each section
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
  "Links existing TocEntry nodes to matching Section nodes.
   
   Matches TocEntry titles to Heading content to find the target Section.
   Uses normalized exact matching (trim + lowercase) for robustness.
   Also copies the Section's description to the TocEntry.
   
   Params:
   `pages` - Vector of page maps with :page/nodes (must have UUIDs already).
   
   Returns:
   Updated pages with TocEntry target-section-id and description populated where matches found."
  [pages]
  (let [all-nodes (vec (collect-all-nodes pages))
        ;; Build map of section-id -> section node (for description lookup)
        section-by-id (reduce
                       (fn [acc node]
                         (if (= :section (:page.node/type node))
                           (assoc acc (:page.node/id node) node)
                           acc))
                       {}
                       all-nodes)
        ;; Build map of normalized heading content -> section-id
        ;; A heading's parent-id is the section it introduces
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
    ;; Update TocEntry nodes with target-section-id and description
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
  "Post-processes pages to ensure TOC exists and is properly linked.
   
   1. If no TocEntry nodes exist, generates TOC from Section/Heading structure
   2. If TocEntry nodes exist, links target-section-id to matching Sections
   
   Params:
   `pages` - Vector of page maps with :page/nodes.
   
   Returns:
   Map with:
     :pages - Updated pages (with linked TocEntry if they existed)
     :toc - Vector of TocEntry nodes (generated or extracted from pages)"
  [pages]
  (if (has-toc-entries? pages)
    ;; TOC exists - link entries to sections and extract them
    (let [linked-pages (link-toc-entries pages)
          toc-entries (vec (filter #(= :toc-entry (:document.toc/type %))
                                   (collect-all-nodes linked-pages)))]
      (trove/log! {:level :debug :data {:toc-entries (count toc-entries)}
                   :msg "Linked existing TOC entries to sections"})
      {:pages linked-pages
       :toc toc-entries})
    ;; No TOC - generate from structure
    (let [generated-toc (build-toc-from-structure pages)]
      (trove/log! {:level :debug :data {:generated-entries (count generated-toc)}
                   :msg "Generated TOC from document structure"})
      {:pages pages
       :toc generated-toc})))

;; =============================================================================
;; Document Abstract Generation
;; =============================================================================

(defn- collect-section-descriptions
  "Collects all :page.node/description values from Section nodes across all pages.
   
   Params:
   `pages` - Vector of page maps with :page/nodes.
   
   Returns:
   Vector of non-empty description strings."
  [pages]
  (->> pages
       (mapcat :page/nodes)
       (filter #(= :section (:page.node/type %)))
       (keep :page.node/description)
       (filter seq)
       vec))

(defn- generate-document-abstract
  "Generates a document-level abstract from all section descriptions.
   
   Collects all :page.node/description values from Section nodes and uses
   abstract! to create a cohesive document summary.
   
   Params:
   `pages` - Vector of page maps with :page/nodes.
   `opts` - Map with :model and :config keys for LLM.
   
   Returns:
   String. Document abstract, or nil if no section descriptions found."
  [pages {:keys [rlm-router]}]
  (let [descriptions (collect-section-descriptions pages)]
    (when (seq descriptions)
      (trove/log! {:level :info :data {:section-count (count descriptions)}
                   :msg "Generating document abstract from section descriptions"})
      (let [;; Combine all descriptions into a single text for summarization
            combined-text (str/join "\n\n" descriptions)
             ;; Target ~150 words for document abstract
            abstracts (llm/abstract! {:text combined-text
                                      :router rlm-router
                                      :strategy :root
                                      :target-length 150
                                      :iterations 3})]
        (when (seq abstracts)
          (let [abstract (:summary (last abstracts))]
            (trove/log! {:level :info :data {:abstract-length (count abstract)}
                         :msg "Document abstract generated"})
            abstract))))))

;; =============================================================================
;; Page Range Normalization
;; =============================================================================

(defn- validate-page-number
  "Validates a single 1-indexed page number against total-page-count.
   Throws on invalid input."
  [n total-page-count]
  (when-not (integer? n)
    (anomaly/incorrect! (str "Page spec must be an integer, got: " (pr-str n))
                        {:type :svar.pageindex/invalid-page-spec
                         :value n}))
  (when (< n 1)
    (anomaly/incorrect! (str "Page number must be >= 1, got: " n)
                        {:type :svar.pageindex/invalid-page-spec
                         :value n}))
  (when (> n total-page-count)
    (anomaly/incorrect! (str "Page " n " exceeds total page count " total-page-count)
                        {:type :svar.pageindex/page-out-of-bounds
                         :value n
                         :total-page-count total-page-count})))

(defn- normalize-range
  "Expands a [from to] 1-indexed range into a set of 0-indexed page indices."
  [[from to] total-page-count]
  (validate-page-number from total-page-count)
  (validate-page-number to total-page-count)
  (when (> from to)
    (anomaly/incorrect! (str "Invalid page range: start " from " > end " to)
                        {:type :svar.pageindex/invalid-page-range
                         :from from
                         :to to}))
  (set (range (dec from) to)))

(defn normalize-page-spec
  "Normalizes a page specification into a set of 0-indexed page indices.
   
   Accepts:
   - nil             → nil (all pages)
   - integer n       → #{(dec n)} (single 1-indexed page)
   - [from to]       → set of 0-indexed pages in range (both ints, exactly 2 elements)
   - [[1 3] 5 [7 10]] → union of expanded ranges and single pages
   
   Throws on invalid input (out of bounds, bad types, reversed ranges)."
  [pages-spec total-page-count]
  (cond
    (nil? pages-spec)
    nil

    (integer? pages-spec)
    (do (validate-page-number pages-spec total-page-count)
        #{(dec pages-spec)})

    (vector? pages-spec)
    (if (and (= 2 (count pages-spec))
             (every? integer? pages-spec))
      ;; Two-integer vector → range [from to]
      (normalize-range pages-spec total-page-count)
      ;; Mixed vector of ranges and singles
      (reduce (fn [acc item]
                (cond
                  (vector? item)
                  (into acc (normalize-range item total-page-count))

                  (integer? item)
                  (do (validate-page-number item total-page-count)
                      (conj acc (dec item)))

                  :else
                  (anomaly/incorrect! (str "Invalid element in page spec: " (pr-str item))
                                      {:type :svar.pageindex/invalid-page-spec
                                       :value item})))
              #{}
              pages-spec))

    :else
    (anomaly/incorrect! (str "Invalid page spec type: " (pr-str pages-spec))
                        {:type :svar.pageindex/invalid-page-spec
                         :value pages-spec})))

(defn filter-pages
  "Filters a page-list by a set of 0-indexed page indices.
   
   If page-set is nil, returns page-list unchanged (all pages).
   Otherwise returns only pages whose :page/index is in page-set."
  [page-list page-set]
  (if (nil? page-set)
    page-list
    (filterv #(contains? page-set (:page/index %)) page-list)))

;; =============================================================================
;; Text Extraction
;; =============================================================================

(defn- extract-text
  "Extract text from document.
   
   Routes to appropriate extractor based on file type:
   - PDF: Convert to images, then vision LLM extraction
   - Markdown: Parse heading structure (no LLM - deterministic)
   - Text: Uses LLM for text extraction
   - Image: Direct vision LLM extraction
   
   Markdown parsing is fast and deterministic - top-level headings become pages.
   
   Throws for unsupported file types.
   
   Returns page-list vector."
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
    ;; For PDFs, resolve :pages to :page-set before extraction so vision layer
    ;; can skip LLM calls for excluded pages.
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
;; Input Type Detection
;; =============================================================================

(defn- detect-input-type
  "Detects the type of input for build-index dispatch.
   
   Params:
   `input` - String. Either a file path or raw content.
   `opts` - Map. Options that may contain :content-type.
   
   Returns:
   Keyword - :path (file path) or :string (raw content)."
  [input opts]
  (cond
    ;; Explicit content-type means it's raw string content
    (:content-type opts)
    :string

    ;; Check if file actually exists - this is the only reliable check
    ;; We don't use heuristics because content can contain paths/URLs
    (file-path? input)
    :path

    ;; Default to string content (will require :content-type validation later)
    :else
    :string))

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
  (fn [input & [opts]]
    (detect-input-type input (or opts {}))))

;; =============================================================================
;; build-index - :path method (file path input)
;; =============================================================================

(defmethod build-index :path
  [file-path & [opts]]
  ;; Validate file exists
  (when-not (.exists (io/file file-path))
    (anomaly/not-found! (str "File not found: " file-path)
                        {:type :svar.pageindex/file-not-found :file file-path}))
   ;; Validate file type is supported
  (when-not (supported-extension? file-path)
    (let [extension (extract-extension file-path)]
      (anomaly/unsupported! (str "Unsupported file type: " (or extension "unknown"))
                            {:type :svar.pageindex/unsupported-file-type
                             :file file-path
                             :extension extension
                             :supported-extensions SUPPORTED_EXTENSIONS})))
  (let [vision-model (or (:model opts) vision/DEFAULT_VISION_MODEL)
        vision-objective (or (:objective opts) vision/DEFAULT_VISION_OBJECTIVE)
        ;; Create router from config or env vars — ALL LLM calls go through the router
        vision-router (or (:rlm-router opts)
                          (llm/config->router (cond-> (or (:config opts) {})
                                                vision-model (assoc :model vision-model))))
        output-dir (:output-dir opts)
        vision-opts {:rlm-router vision-router :objective vision-objective}]
    (trove/log! {:level :info :data {:file file-path}
                 :msg "Starting text extraction from file"})
    (let [page-list-all (extract-text file-path (merge opts vision-opts))
          ;; Step 0: Filter pages if :pages specified (safety net — vision layer
          ;; already skips LLM calls for excluded pages, but this handles non-PDF
          ;; extractors and stubbed extract-text in tests)
          page-set (when-let [pages (:pages opts)]
                     (normalize-page-spec pages (count page-list-all)))
          page-list-raw (filter-pages page-list-all page-set)
           ;; Step 1: Translate local IDs to global UUIDs
          page-list-uuids (translate-all-ids page-list-raw)
          ;; Step 2: Group continuation nodes across pages
          page-list (group-continuations page-list-uuids)
          doc-name (extract-doc-name file-path)
          extension (extract-extension file-path)
          ftype (file-type file-path)
          ;; Extract PDF metadata if available
          file-metadata (when (= :pdf ftype)
                          (try
                            (pdf/pdf-metadata file-path)
                            (catch Exception e
                              (trove/log! {:level :warn :data {:error (ex-message e)}
                                           :msg "Failed to extract PDF metadata"})
                              nil)))
          ;; Step 3: Post-process TOC (build/link with UUIDs)
          {:keys [pages toc]} (postprocess-toc page-list)
          pages-with-images (if output-dir
                              (let [dir-file (io/file output-dir)]
                                (when-not (.exists dir-file)
                                  (anomaly/not-found! (str "Output directory not found: " output-dir)
                                                      {:type :svar.pageindex/output-dir-not-found :output-dir output-dir}))
                                (mapv (fn [page]
                                        (update page :page/nodes
                                                (fn [nodes]
                                                  (mapv (fn [node]
                                                          (let [img-bytes (:page.node/image-data node)]
                                                            (if (and (#{:image :table} (:page.node/type node))
                                                                     img-bytes)
                                                              (do
                                                                (try
                                                                  (let [file-path (fs/path output-dir (str (:page.node/id node) ".png"))]
                                                                    (with-open [out (io/output-stream (io/file (str file-path)))]
                                                                      (.write out ^bytes img-bytes)))
                                                                  (catch Exception e
                                                                    (trove/log! {:level :warn
                                                                                 :data {:node-id (:page.node/id node)
                                                                                        :error (ex-message e)}
                                                                                 :msg "Failed to write image bytes to output directory"})))
                                                                (dissoc node :page.node/image-data))
                                                              node)))
                                                        nodes))))
                                      pages))
                              pages)
           ;; Step 3: Generate document abstract from section descriptions
          abstract-opts {:rlm-router vision-router}
          document-abstract (generate-document-abstract pages-with-images abstract-opts)
           ;; Step 4: Infer title if not in metadata
          metadata-title (:title file-metadata)
          inferred-title (when-not metadata-title
                           (vision/infer-document-title pages {:rlm-router vision-router}))
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
       :document/pages pages-with-images
       :document/toc toc
       :document/created-at (or (:created-at file-metadata) now)
       :document/updated-at (or (:updated-at file-metadata) now)
       :document/author (:author file-metadata)})))

;; =============================================================================
;; build-index - :string method (raw content input)
;; =============================================================================

(defmethod build-index :string
  [content & [opts]]
  (let [{:keys [content-type doc-name doc-title doc-author created-at updated-at]} (or opts {})
        vision-model (or (:model opts) vision/DEFAULT_VISION_MODEL)
        vision-objective (or (:objective opts) vision/DEFAULT_VISION_OBJECTIVE)
        ;; Create router from config or env vars
        vision-router (or (:rlm-router opts)
                          (llm/config->router (cond-> (or (:config opts) {})
                                                vision-model (assoc :model vision-model))))
        vision-opts {:rlm-router vision-router :objective vision-objective}]
    ;; Validate required options
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
          ;; Step 1: Translate local IDs to global UUIDs
          page-list-uuids (translate-all-ids page-list-raw)
          ;; Step 2: Group continuation nodes across pages
          page-list (group-continuations page-list-uuids)
          ;; Step 3: Post-process TOC (build/link with UUIDs)
          {:keys [pages toc]} (postprocess-toc page-list)
          ;; Step 4: Generate document abstract from section descriptions
          abstract-opts {:rlm-router vision-router}
          document-abstract (generate-document-abstract pages abstract-opts)
          ;; Step 5: Infer title if not provided
          inferred-title (when-not doc-title
                           (vision/infer-document-title pages {:rlm-router vision-router}))
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
;; EDN + PNG Serialization
;; =============================================================================

(defn- derive-index-path
  "Derive the EDN output directory from the input file path.
   
   Example: /path/to/document.pdf -> /path/to/document.pageindex/"
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

;; =============================================================================
;; Public Serialization API
;; =============================================================================

(defn write-document-edn!
  "Writes a document to an EDN file, extracting image bytes to separate PNG files.
   
   Image data (byte arrays) in :page.node/image-data are written as PNG files
   in an 'images' subdirectory. The EDN stores the relative path instead of bytes.
   
   Instants are serialized as #inst tagged literals (EDN native).
   
   Params:
   `output-dir` - String. Path to the output directory (e.g., 'docs/manual.pageindex').
   `document` - Map. The PageIndex document.
   
   Returns:
   The output directory path."
  [output-dir document]
  (let [dir-file (io/file output-dir)
        images-dir (io/file output-dir "images")
        ;; Extract images and replace bytes with relative paths
        doc-with-paths (update document :document/pages
                               (fn [pages]
                                 (mapv (fn [page]
                                         (update page :page/nodes
                                                 (fn [nodes]
                                                   (mapv (fn [node]
                                                           (let [img-bytes (:page.node/image-data node)]
                                                             (if (and (bytes? img-bytes)
                                                                      (#{:image :table} (:page.node/type node)))
                                                               (let [img-name (str (:page.node/id node) ".png")
                                                                     img-path (io/file images-dir img-name)]
                                                                 ;; Ensure images dir exists
                                                                 (when-not (.exists images-dir)
                                                                   (.mkdirs images-dir))
                                                                 ;; Write PNG
                                                                 (with-open [out (io/output-stream img-path)]
                                                                   (.write out ^bytes img-bytes))
                                                                 (trove/log! {:level :debug
                                                                              :data {:node-id (:page.node/id node) :path (str "images/" img-name)}
                                                                              :msg "Wrote image file"})
                                                                 ;; Replace bytes with relative path
                                                                 (-> node
                                                                     (dissoc :page.node/image-data)
                                                                     (assoc :page.node/image-path (str "images/" img-name))))
                                                               node)))
                                                         nodes))))
                                       pages)))
        edn-file (io/file dir-file "document.edn")]
    ;; Ensure output dir exists
    (when-not (.exists dir-file)
      (.mkdirs dir-file))
    ;; Write pretty-printed EDN
    (spit edn-file (with-out-str (pprint/pprint doc-with-paths)))
    (trove/log! {:level :debug :data {:path (str edn-file)} :msg "Wrote document EDN"})
    output-dir))

(defn read-document-edn
  "Reads a document from an EDN file, resolving image paths back to byte arrays.
   
   Image paths in :page.node/image-path are read back as byte arrays
   into :page.node/image-data.
   
   Params:
   `index-dir` - String. Path to the pageindex directory.
   
   Returns:
   The PageIndex document map with image bytes restored."
  [index-dir]
  (let [edn-file (io/file index-dir "document.edn")
        doc (edn/read-once edn-file)]
    ;; Resolve image paths back to byte arrays
    (update doc :document/pages
            (fn [pages]
              (mapv (fn [page]
                      (update page :page/nodes
                              (fn [nodes]
                                (mapv (fn [node]
                                        (if-let [img-rel-path (:page.node/image-path node)]
                                          (let [img-file (io/file index-dir img-rel-path)]
                                            (if (.exists img-file)
                                              (let [img-bytes (let [ba (byte-array (.length img-file))]
                                                                (with-open [is (java.io.FileInputStream. img-file)]
                                                                  (.read is ba))
                                                                ba)]
                                                (-> node
                                                    (dissoc :page.node/image-path)
                                                    (assoc :page.node/image-data img-bytes)))
                                              (do
                                                (trove/log! {:level :warn
                                                             :data {:path (str img-file)}
                                                             :msg "Image file not found, skipping"})
                                                (dissoc node :page.node/image-path))))
                                          node))
                                      nodes))))
                    pages)))))

(defn ^:export index!
  "Index a document file and save the result as EDN + PNG files.
   
   Takes a file path (PDF, MD, TXT) and runs build-index to extract structure.
   The result is saved as a directory alongside the original (or custom path):
     document.pageindex/
       document.edn    — structured data (EDN)
       images/          — extracted images as PNG files
   
       Params:
       `file-path` - String. Path to the document file.
       `opts` - Map, optional:
         - :output - Custom output directory path (default: same dir, .pageindex extension)
         - :config - LLM config override
         - :pages - Page selector (1-indexed). Limits which pages are indexed.
                    Supports: integer, [from to] range, or [[1 3] 5 [7 10]] mixed vector.
                    nil = all pages (default).
         
         Vision extraction:
         - :vision-model - String. Model for vision page extraction (default: DEFAULT_VISION_MODEL).
         - :parallel - Integer. Max concurrent vision page extractions for PDFs (default: 3)
         
         Quality refinement (opt-in):
         - :refine? - Boolean. Enable post-extraction quality refinement (default: false)
         - :refine-model - String. Model for eval/refine steps (default: \"gpt-4o\")
         - :parallel-refine - Integer. Max concurrent eval/refine operations (default: 2)
         - :refine-iterations - Integer. Max refine iterations per page (default: 1)
         - :refine-threshold - Float. Min eval score to pass (default: 0.8)
         - :refine-sample-size - Integer. Pages to sample for eval (default: 3)
       
       Returns:
       Map with :document (the indexed document) and :output-path (directory where files were saved).
       
       Throws:
       - ex-info if file not found
       - ex-info if document fails spec validation
       - ex-info if :pages references out-of-bounds or invalid page numbers
       
       Example:
       (index! \"docs/manual.pdf\")
       ;; => {:document {...} :output-path \"docs/manual.pageindex\"}
       
       ;; Index only pages 1 through 5
       (index! \"docs/manual.pdf\" {:pages [1 5]})
       
       ;; Index specific pages: 1-3, 5, and 7-10
       (index! \"docs/manual.pdf\" {:pages [[1 3] 5 [7 10]]})
       
       ;; Separate models for vision vs refinement
       (index! \"docs/manual.pdf\" {:vision-model \"gpt-4o\"
                                    :refine? true
                                    :refine-model \"gpt-4o-mini\"
                                    :parallel-refine 3})"
  ([file-path] (index! file-path {}))
  ([file-path {:keys [output vision-model parallel parallel-refine
                      refine? refine-model refine-iterations
                      refine-threshold refine-sample-size pages]}]
   (let [abs-path (ensure-absolute file-path)
         output-path (or output (derive-index-path abs-path))]

      ;; Validate input exists
     (when-not (fs/exists? abs-path)
       (trove/log! {:level :error :data {:path abs-path} :msg "File not found"})
       (anomaly/not-found! "File not found" {:type :svar.pageindex/file-not-found :path abs-path}))

     (trove/log! {:level :info :data {:input abs-path :output output-path :refine? (boolean refine?)}
                  :msg "Starting document indexing"})

      ;; Run indexing
     (let [index-opts (cond-> {}
                        vision-model (assoc :model vision-model)
                        parallel (assoc :parallel parallel)
                        parallel-refine (assoc :parallel-refine parallel-refine)
                        refine? (assoc :refine? refine?)
                        refine-model (assoc :refine-model refine-model)
                        refine-iterations (assoc :refine-iterations refine-iterations)
                        refine-threshold (assoc :refine-threshold refine-threshold)
                        refine-sample-size (assoc :refine-sample-size refine-sample-size)
                        pages (assoc :pages pages))
           _ (trove/log! {:level :debug :data {:opts index-opts} :msg "Running build-index"})
           document (build-index abs-path index-opts)
           _ (trove/log! {:level :debug :data {:document/name (:document/name document)} :msg "build-index complete"})]

       ;; Validate the document - throw on failure
       (when-not (schema/valid-document? document)
         (let [explanation (schema/explain-document document)]
           (trove/log! {:level :error :data {:explanation explanation} :msg "Document failed spec validation"})
           (anomaly/incorrect! "Document failed spec validation"
                               {:type :rlm/invalid-document
                                :document/name (:document/name document)
                                :explanation explanation})))

       (trove/log! {:level :debug :msg "Spec validation passed"})

      ;; Save as EDN + PNG files
       (trove/log! {:level :debug :data {:path output-path} :msg "Writing EDN + PNG files"})
       (write-document-edn! output-path document)

       (trove/log! {:level :info :data {:document/name (:document/name document)
                                        :pages (count (:document/pages document))
                                        :toc-entries (count (:document/toc document))
                                        :output-path output-path}
                    :msg "Document indexed and saved successfully"})

       {:document document
        :output-path output-path}))))

(defn load-index
  "Load an indexed document from a pageindex directory (EDN + PNG files).
   
   Also supports loading legacy Nippy files for backward compatibility.
   
   Params:
   `index-path` - String. Path to the pageindex directory or legacy .nippy file.
   
   Returns:
   The RLM document map.
   
   Throws:
   - ex-info if path not found
   - ex-info if document fails spec validation
   
   Example:
   (load-index \"docs/manual.pageindex\")"
  [index-path]
  (let [abs-path (ensure-absolute index-path)]
    (when-not (fs/exists? abs-path)
      (trove/log! {:level :error :data {:path abs-path} :msg "Index path not found"})
      (anomaly/not-found! "Index path not found" {:type :svar.pageindex/index-not-found :path abs-path}))

    (trove/log! {:level :debug :data {:path abs-path} :msg "Loading index"})
    (let [document (if (fs/directory? abs-path)
                     ;; New format: directory with document.edn + images/
                     (read-document-edn abs-path)
                     ;; Legacy: could be a plain EDN file
                     (edn/read-once (io/file abs-path)))]

      ;; Validate the document - throw on failure
      (when-not (schema/valid-document? document)
        (let [explanation (schema/explain-document document)]
          (trove/log! {:level :error :data {:path abs-path :explanation explanation} :msg "Loaded document failed spec validation"})
          (anomaly/incorrect! "Loaded document failed spec validation"
                              {:type :rlm/invalid-document
                               :path abs-path
                               :explanation explanation})))

      (trove/log! {:level :info :data {:path abs-path
                                       :document/name (:document/name document)
                                       :pages (count (:document/pages document))
                                       :toc-entries (count (:document/toc document))}
                   :msg "Loaded document"})
      document)))

(defn- print-toc-tree
  "Print the TOC as a tree structure."
  [toc]
  (doseq [entry toc]
    (let [depth (dec (count (str/split (or (:node/structure entry) "1") #"\.")))
          indent (str/join "" (repeat depth "  "))]
      (printf "%s%s %s\n" indent (or (:node/structure entry) "?") (:node/title entry)))))

(defn ^:export inspect
  "Load and print a full summary of an indexed document including TOC tree.
   
   Params:
   `doc-or-path` - Either a document map or String path to EDN file.
   
   Returns:
   Summary map with document stats.
   
   Throws:
   - ex-info if path provided and file not found
   - ex-info if document fails spec validation
   
   Example:
   (inspect \"docs/manual.edn\")
   (inspect my-document)"
  [doc-or-path]
  (trove/log! {:level :debug :data {:input (if (string? doc-or-path) doc-or-path :document-map)} :msg "Inspecting document"})
  (let [doc (if (string? doc-or-path)
              (load-index doc-or-path)
              ;; Validate document map if passed directly
              (do
                (when-not (schema/valid-document? doc-or-path)
                  (let [explanation (schema/explain-document doc-or-path)]
                    (trove/log! {:level :error :data {:explanation explanation} :msg "Document failed spec validation"})
                    (anomaly/incorrect! "Document failed spec validation"
                                        {:type :rlm/invalid-document
                                         :explanation explanation})))
                doc-or-path))
        toc (:document/toc doc)
        pages (:document/pages doc)]
    (println "\n=== Document Summary ===")
    (println "Name:      " (:document/name doc))
    (println "Title:     " (or (:document/title doc) "(none)"))
    (println "Extension: " (:document/extension doc))
    (println "Author:    " (or (:document/author doc) "(none)"))
    (println "Pages:     " (count pages))
    (println "TOC entries:" (count toc))
    (println "Created:   " (:document/created-at doc))
    (println "Updated:   " (:document/updated-at doc))
    (when (:document/abstract doc)
      (println "\n--- Abstract ---")
      (println (:document/abstract doc)))
    (println "\n--- TOC Tree ---")
    (print-toc-tree toc)
    (println)
    {:document/name (:document/name doc)
     :document/title (:document/title doc)
     :page-count (count pages)
     :toc-count (count toc)
     :has-abstract (boolean (:document/abstract doc))}))
