(ns com.blockether.svar.internal.rlm.core
  (:require
   [clojure.string :as str]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.router :as router]
   [com.blockether.svar.internal.rlm.db :as rlm-db
    :refer [create-rlm-conn dispose-rlm-conn!
            db-list-documents db-list-final-results
            db-store-pageindex-document! str-truncate]]
   [com.blockether.svar.internal.rlm.data :as rlm-data]
   [com.blockether.svar.internal.rlm.routing
    :refer [make-routed-sub-rlm-query-fn resolve-root-model provider-has-reasoning?]]
   [com.blockether.svar.internal.rlm.schema
    :refer [ENTITY_EXTRACTION_SPEC ENTITY_EXTRACTION_OBJECTIVE
            ITERATION_SPEC ITERATION_SPEC_CODE_ONLY
            *eval-timeout-ms*
            validate-final bytes->base64 *rlm-ctx*]]
   [com.blockether.svar.internal.rlm.skills :as rlm-skills]
   [com.blockether.svar.internal.rlm.tools :refer [create-sci-context realize-value build-var-index]]
   [com.blockether.svar.internal.paren-repair :as paren-repair]
   [edamame.core :as edamame]
   [com.blockether.svar.internal.jsonish :as jsonish]
   [com.blockether.svar.internal.spec :as spec]
   [datalevin.core :as d]
   [sci.core :as sci]
   [taoensso.trove :as trove]))

(declare build-system-prompt run-iteration format-executions)

(def ^:private CAVEMAN_ITERATION_OUTPUT
  "Caveman mode for iterations. Drop: articles, filler, hedging, conjunctions.
Fragments OK. → for causality. One word when enough. Tech terms exact. Code unchanged.
Pattern: [thing] [action] [reason]. [next step].")

(def ^:private FINAL_ANSWER_OUTPUT
  "Normal English. Clear, direct sentences. No AI filler (no \"As an AI\", \"I believe\",
\"In conclusion\"). No hedging. Factual. Technical terms exact.")

(defn rlm-debug!
  "Logs at :info level only when :rlm-debug? is true in *rlm-ctx*.
   Includes :rlm-phase from context automatically in data."
  [data msg]
  (when (:rlm-debug? *rlm-ctx*)
    (trove/log! {:level :info :data (assoc data :rlm-phase (:rlm-phase *rlm-ctx*)) :msg msg})))

(defn- status->id
  [status]
  (when status
    (keyword "rlm.status" (name status))))

;; =============================================================================
;; RLM Environment
;; =============================================================================

(defn create-rlm-env
  "Creates an RLM execution environment (internal use only).

   Params:
   `context-data` - The data context to analyze (can be nil).
   `depth-atom` - Atom tracking recursion depth.
   `router` - Router from llm/make-router. Required.
   `opts` - Map, optional:
     - :db - DB spec: nil (no DB), :temp, path string, {:conn c}, {:path p}
     - :documents - Vector of PageIndex documents to preload (stored exactly as-is).

   Returns:
   Map with :sci-ctx, :context, :sub-rlm-query-fn, :locals-atom,
   :db-info-atom, :router."
  ([context-data depth-atom router]
   (create-rlm-env context-data depth-atom router {}))
  ([context-data depth-atom router {:keys [db documents]}]
   (when-not router
     (throw (ex-info "Router is required for RLM environment" {:type :rlm/missing-router})))
   (let [db-info (create-rlm-conn db)
         db-info-atom (when db-info (atom db-info))
         _ (when (and db-info-atom (seq documents))
             (doseq [doc documents]
               (db-store-pageindex-document! @db-info-atom doc)))
         sub-rlm-query-fn (make-routed-sub-rlm-query-fn {} depth-atom router nil nil)
         {:keys [sci-ctx sandbox-ns initial-ns-keys]} (create-sci-context context-data sub-rlm-query-fn db-info-atom nil nil)]
     {:sci-ctx sci-ctx :sandbox-ns sandbox-ns :initial-ns-keys initial-ns-keys :context context-data
      :sub-rlm-query-fn sub-rlm-query-fn
      :db-info-atom db-info-atom
      :router router})))

(defn dispose-rlm-env! [{:keys [db-info-atom]}]
  (when db-info-atom (dispose-rlm-conn! @db-info-atom)))

(defn get-locals
  "Returns {sym → val} of user-defined vars in SCI sandbox (excludes built-ins).
   Direct atom read from SCI env — zero eval overhead."
  [{:keys [sci-ctx initial-ns-keys]}]
  (try
    (let [sandbox (get-in @(:env sci-ctx) [:namespaces 'sandbox])]
      (persistent!
        (reduce-kv (fn [acc k v]
                     (if (or (contains? initial-ns-keys k) (keyword? k))
                       acc
                       (assoc! acc k (if (instance? clojure.lang.IDeref v) @v v))))
          (transient {}) sandbox)))
    (catch Exception _ {})))

;; =============================================================================
;; Code Execution
;; =============================================================================

(defn- run-sci-code
  "Evaluate `code` in `sci-ctx` with captured stdout/stderr.
   Uses eval-string+ with :ns to ensure code runs in sandbox namespace.
   Returns {:result :stdout :stderr :error} with writers already closed."
  [sci-ctx code & {:keys [sandbox-ns]}]
  (let [stdout-writer (java.io.StringWriter.)
        stderr-writer (java.io.StringWriter.)
        err-pw       (java.io.PrintWriter. stderr-writer true)
        exec-future (future
                      (try
                        (let [result (sci/binding [sci/out stdout-writer
                                                   sci/err err-pw]
                                       (let [ns (or (sci/find-ns sci-ctx 'sandbox) sandbox-ns)]
                                         (:val (sci/eval-string+ sci-ctx code
                                                 (when ns {:ns ns})))))]
                          {:result result :stdout (str stdout-writer) :stderr (str stderr-writer) :error nil})
                        (catch Throwable e
                          {:result nil :stdout (str stdout-writer) :stderr (str stderr-writer)
                           :error (str (.getSimpleName (class e)) ": " (or (ex-message e) (str e)))})))
        timeout-ms (long *eval-timeout-ms*)
        execution-result (try
                           (deref exec-future timeout-ms nil)
                           (catch Throwable e
                             {:result nil :stdout "" :stderr "" :error (str (.getSimpleName (class e)) ": " (ex-message e))}))]
    (.close stdout-writer)
    (.close stderr-writer)
    (if (nil? execution-result)
      (do (future-cancel exec-future)
          {:result nil :stdout "" :stderr "" :error (str "Timeout (" (/ timeout-ms 1000) "s)") :timeout? true})
      execution-result)))

(defn- detect-common-mistakes
  "Pre-exec lint: catches common Clojure mistakes BEFORE SCI eval.
   Returns nil if clean, or an error string with actionable fix."
  [code]
  (let [s (str/trim code)]
    (cond
      ;; Nested #() - illegal in Clojure, cryptic SCI error
      (re-find #"#\([^)]*#\(" s)
      "Nested #() is illegal in Clojure. Rewrite inner #() as (fn [...] ...)"
      ;; Nothing wrong
      :else nil)))

(def ^:private edamame-opts
  "Edamame parser options matching Clojure/SCI syntax.
   :all enables fn literals, deref, var, regex, quote, etc."
  {:all true})

(defn- check-syntax
  "Parses code with edamame. Returns parsed forms or throws."
  [code]
  (edamame/parse-string-all code edamame-opts))

(defn- check-bare-list
  "Detects unquoted list literals like (6 7 8) that parse fine but fail at eval.
   Returns error string or nil."
  [forms]
  (let [first-form (first forms)]
    (when (and (= 1 (count forms))
            (list? first-form) (seq first-form)
            (let [head (first first-form)]
              (not (or (symbol? head) (keyword? head)
                     (list? head) (set? head) (map? head) (vector? head)))))
      (str "Bare list literal: " (pr-str first-form)
        ". Quote it: '(" (str/join " " first-form) ")"))))

(defn- parse-clojure-syntax
  "Validates Clojure syntax using edamame (same parser as SCI).
   Runs all checks: syntax parse, bare list detection.
   Returns nil if valid, or an error string if broken."
  [code]
  (try
    (let [forms (check-syntax code)]
      (or (check-bare-list forms)
        nil))
    (catch Throwable e
      (ex-message e))))

(defn execute-code [{:keys [sci-ctx sandbox-ns]} code]
  (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-phase :execute-code})]
    (let [bal (paren-repair/paren-balance code)
          _ (rlm-debug! {:code code
                         :code-len (count code)
                         :paren-balance bal} "Executing code (full)")
          start-time (System/currentTimeMillis)
          lint-error (detect-common-mistakes code)]
      (if lint-error
        ;; Pre-exec lint caught a known mistake - return clear error without eval
        (do (rlm-debug! {:lint-error lint-error} "Pre-exec lint caught mistake")
            {:result nil :stdout "" :stderr "" :error lint-error
             :execution-time-ms 0 :timeout? false})
        (if-let [parse-error (parse-clojure-syntax code)]
          (do (rlm-debug! {:parse-error parse-error} "Edamame pre-parse failed")
              {:result nil :stdout "" :stderr "" :error parse-error
               :execution-time-ms 0 :timeout? false})
        ;; Normal execution path
          (let [execution-result (run-sci-code sci-ctx code :sandbox-ns sandbox-ns)
                execution-time (- (System/currentTimeMillis) start-time)]
            (if (:timeout? execution-result)
              (do
                (rlm-debug! {:execution-time-ms execution-time} "Code execution timed out — break long-running code into smaller steps or use (deref (future ...) timeout-ms :timeout)")
                (assoc execution-result :execution-time-ms execution-time :timeout? true))
              (let [{:keys [error]} execution-result
                    final-result (if (and error (paren-repair/parse-error? error))
                                   (try
                                     (let [repaired (paren-repair/repair-code code)]
                                       (if (= repaired code)
                                         (do (trove/log! {:level :debug :id ::repair-noop
                                                          :data {:code-len (count code) :error error}
                                                          :msg "Paren repair: no change needed"})
                                             execution-result)
                                         (let [retry (run-sci-code sci-ctx repaired :sandbox-ns sandbox-ns)]
                                           (if (:error retry)
                                             (do (trove/log! {:level :warn :id ::repair-retry-failed
                                                              :data {:original-error error
                                                                     :retry-error (:error retry)
                                                                     :code-len (count code)
                                                                     :repaired-len (count repaired)
                                                                     :added-chars (- (count repaired) (count code))
                                                                     :repaired-tail (subs repaired (max 0 (- (count repaired) 80)))}
                                                              :msg "Paren repair changed code but retry still failed"})
                                                 execution-result)
                                             (do
                                               (trove/log! {:level :info :id ::repair-applied
                                                            :data {:original code :repaired repaired :sci-error error}
                                                            :msg "Paren repair applied successfully"})
                                               (assoc retry :repaired? true))))))
                                     (catch Throwable _
                                       execution-result))
                                   execution-result)
                    {:keys [result stdout stderr error]} final-result]
                (rlm-debug! {:execution-time-ms execution-time
                             :has-error? (some? error)
                             :error error
                             :result-preview (str-truncate (pr-str result) 200)
                             :stdout-preview (when-not (str/blank? stdout) (str-truncate stdout 200))
                             :stderr-preview (when-not (str/blank? stderr) (str-truncate stderr 200))} "Code execution complete")
                (assoc final-result :execution-time-ms execution-time :timeout? false)))))))))

(defn answer-str
  "Extracts a string representation from an RLM answer.
   Answer is {:result value :type type} — returns the :result as a string."
  [answer]
  (let [v (:result answer answer)]
    (if (string? v) v (pr-str v))))

;; =============================================================================
;; System Prompt
;; =============================================================================

(defn- format-param
  "Formats a single parameter for the system prompt."
  [{:keys [name type required description default]}]
  (str "      " name " - " (clojure.core/name (or type :any))
    (when-not (true? required) " (optional)")
    (when (some? default) (str ", default: " (pr-str default)))
    (when description (str " — " description))))

(defn- format-tool-doc
  "Formats a tool doc entry for the system prompt."
  [{:keys [type sym doc params returns examples]}]
  (str "  <" (clojure.core/name type) " name=\"" sym "\">\n"
    (when doc (str "    " doc "\n"))
    (when (seq params)
      (str "    Parameters:\n"
        (str/join "\n" (map format-param params)) "\n"))
    (when returns
      (str "    Returns: " (clojure.core/name (or (:type returns) :any))
        (when (:description returns) (str " — " (:description returns)))
        "\n"))
    (when (seq examples)
      (str "    Examples:\n"
        (str/join "\n" (map #(str "      " %) examples)) "\n"))
    "  </" (clojure.core/name type) ">"))

(defn- format-custom-docs
  "Formats custom docs for the system prompt."
  [custom-docs]
  (when (seq custom-docs)
    (str "\n<custom_tools>\n"
      (str/join "\n" (map format-tool-doc custom-docs))
      "\n</custom_tools>\n")))

(defn build-document-summary
  "Builds a compact summary of available documents for the system prompt.
   Returns nil if no documents are loaded.

   Params:
   `db-info` - Map with :conn key.

   Returns string like:
   \"3 documents, 142 pages, 87 entities (person: 23, org: 15, ...)
    Documents:
      [doc-1] Annual Report 2024 (pdf, 48 pages)
      [doc-2] API Reference (html, 12 pages)
      [doc-3] Meeting Notes (md, 82 pages)\""
  [{:keys [conn]}]
  (when conn
    (let [docs (d/q '[:find [(pull ?e [:document/id :document/name :document/title :document/extension]) ...]
                      :where [?e :document/id _]]
                 (d/db conn))
          page-counts (when (seq docs)
                        (into {}
                          (for [doc docs]
                            [(:document/id doc)
                             (or (d/q '[:find (count ?p) .
                                        :in $ ?doc-id
                                        :where [?p :page.node/document-id ?doc-id]]
                                   (d/db conn) (:document/id doc))
                               0)])))
          total-pages (reduce + 0 (vals page-counts))
          entity-stats (let [entities (d/q '[:find [(pull ?e [:entity/type]) ...]
                                             :where [?e :entity/id _]]
                                        (d/db conn))
                             types-map (frequencies (map :entity/type entities))]
                         {:total (count entities) :types types-map})
          total-entities (:total entity-stats)]
      (when (seq docs)
        (str (count docs) " documents, " total-pages " pages"
          (when (pos? total-entities)
            (str ", " total-entities " entities ("
              (str/join ", " (map (fn [[t c]] (str (name t) ": " c))
                               (take 5 (sort-by val > (:types entity-stats)))))
              ")"))
          "\nDocuments:\n"
          (str/join "\n"
            (map (fn [doc]
                   (let [id (:document/id doc)
                         title (or (:document/title doc) (:document/name doc) id)
                         ext (:document/extension doc)
                         pages (get page-counts id 0)]
                     (str "  [" id "] " title
                       (when ext (str " (" ext ")"))
                       ", " pages " pages")))
              (sort-by :document/id docs))))))))

(defn- format-git-context
  "Render the GIT REPO context block(s) for the system prompt. Caveman style.
   Multi-repo aware: emits one `GIT REPO: <name>` block per attached repo,
   then a single shared tool list.

   `git-repos` is a vec of `:repo/*` entity maps (from db-list-repos) or nil.
   Returns nil when the vec is nil or empty."
  [git-repos]
  (when (seq git-repos)
    (let [multi? (> (count git-repos) 1)
          blocks (for [rm (sort-by :repo/name git-repos)
                       :let [{:repo/keys [name path head-short branch commits-ingested]} rm]]
                   (str "
GIT REPO: " name " (" path ")
  head: " (or head-short "?") (when branch (str " on " branch)) "
  commits ingested: " (or commits-ingested 0)))
          tools (if multi?
                  "
Git tools this session — all prefixed `git-`. JGit-side tools auto-dispatch:
  path-based (git-blame, git-file-history) → pass ABSOLUTE path inside target repo's worktree
  sha-based  (git-commit-diff) → pass SHA; refs like HEAD are ambiguous, forbidden
  (git-search-commits {:category :bug :document-id \"<name>\" ...})
  (git-commit-history {:limit 20 :document-id \"<name>\"})
  (git-commits-by-ticket \"SVAR-42\")
  (git-commit-parents \"abc123def456\")
  (git-file-history \"/abs/path/to/file.clj\" {:n 10})
  (git-blame \"/abs/path/to/file.clj\" 42 58)
  (git-commit-diff \"abc123def456\")
"
                  "
Git tools available this session — all prefixed `git-`:
  (git-search-commits {:category :bug :since \"2025-06-01\" :path \"src/\" ...})
  (git-commit-history {:limit 20})
  (git-commits-by-ticket \"SVAR-42\")
  (git-commit-parents \"HEAD\")
  (git-file-history \"src/foo.clj\")
  (git-blame \"src/foo.clj\" 42 58)
  (git-commit-diff \"HEAD\")
")]
      (str (str/join "\n" blocks) "\n" tools))))

(defn build-system-prompt
  "Builds the system prompt — compact, token-efficient.
   All tool documentation is discoverable via (doc fn-name) in SCI.

   `git-repos` is a vec of `:repo/*` entity maps (from rlm.db/db-list-repos)
   or nil/empty."
  [{:keys [output-spec custom-docs has-reasoning? has-documents? document-summary system-prompt git-repos skill-registry]}]
  (str
    "Clojure SCI agent. Write, exec, iterate.

ARCH:
- Single-shot iter. No msg history. State → def'd vars (persist).
- <var_index> → vars. <execution_results> → last return.
- (doc fn) for docs.
- Aliases: str/ set/ walk/ edn/ json/ zp/ pp/ lt/ test/

LLM SUB-CALLS:
- (sub-rlm-query \"q\") → {:content :code} prose + Clojure code blocks (vec<str>).
- (sub-rlm-query \"q\" {:routing {:optimize :cost}}) → cheap path.
- (sub-rlm-query-batch [\"q1\" \"q2\" ...]) → vec of results. N≥2 independent → batch. Dependent → serial.
- Independent = output of A NOT input of B. Same question × many inputs = batch.
- Shape guaranteed by provider-enforced JSON. :content always present, :code nil when no code applies.
- Eval returned :code via the usual iteration flow (each entry = one complete form).
- Need typed data? Ask the sub-RLM to emit code that defs the data structure; eval it.

GOTCHAS:
- Quote lists: '(1 2 3). Bare () = fn call.
- No nested #(). Use (fn [...] ...).
- 'code' entry = complete expr. No fragments. No split across strings.
- Docstring defs: (def x \"doc\" val) → aids <var_index>.
- AVOID LAZY SEQS. Prefer eager: mapv filterv reduce into.
  - (map f xs) = lazy. (filter p xs) = lazy. (for [...] ...) = lazy. Result not computed.
  - (future (map f xs)) = TRAP. Future holds unrealized seq. Deref → serial on consumer thread. WRONG.
  - Fix: (future (mapv f xs)) OR (future (doall (map f xs))) OR (pmap f xs).
  - Only use lazy when consuming once without realization (very rare here).
- future/promise/deliver available. Parallel = sub-rlm-query-batch (preferred) OR (mapv deref (mapv #(future ...) xs)).
"
    (rlm-skills/skills-manifest-block skill-registry)
    (format-git-context git-repos)
    (when (and has-documents? document-summary)
      (str
        "
DOCUMENTS: " document-summary "

Doc tools (2 fns):
1. (search-documents \"q\") → {:pages [...] :toc [...] :entities [...]} 
   (search-documents \"q\" {:in :pages})  — narrow to :pages|:toc|:entities
   (search-documents \"q\" {:top-k 20 :document-id \"doc-1\"})
2. (fetch-document-content ref) → full content:
   [:page.node/id \"id\"] → page text
   [:document/id \"id\"] → vec of ~4K char pages
   [:document.toc/id \"id\"] → TOC desc
   [:entity/id \"id\"] → {:entity {...} :relationships [...]} 
(def pages (fetch-document-content [:document/id \"doc-1\"]))
Search in English. Translate non-EN queries first.
"))
    (when system-prompt
      (str "\nINSTRUCTIONS:\n" system-prompt "\n"))
    (format-custom-docs custom-docs)
    (when output-spec
      (str "\nOUTPUT SCHEMA:\n" (spec/spec->prompt output-spec) "\n"))
    "
RESPONSE FORMAT:
"
    (spec/spec->prompt (if has-reasoning? ITERATION_SPEC_CODE_ONLY ITERATION_SPEC))
    "
"
    (if has-reasoning?
      "JSON only. Native reasoning — omit 'thinking'."
      "JSON with 'thinking' + 'code'.")
    "
Set 'final' when done: {\"final\": {\"answer\": \"...\", \"confidence\": \"high|medium|low\"}}

RULES:
- ALWAYS test. Untested = wrong.
- No repeat fail → different approach.
- Combine steps per iter.
- <var_index>|<context> answers query → finalize now.

CODE:
- Complete evaluable expr per entry. No split.
- Simplest solution. No over-eng. No unused abstractions. No speculative features. No impossible-error handling.

OUTPUT (iterations):
"
    CAVEMAN_ITERATION_OUTPUT
    "

FINAL ANSWER (when setting 'final'):
"
    FINAL_ANSWER_OUTPUT
    "

Answer → 'final' when done. Explain only if non-obvious. No boilerplate.
"))

;; =============================================================================
;; Iteration Loop
;; =============================================================================

(defn run-iteration
  "Runs a single RLM iteration: ask! → check final → execute code.

   Uses ask! with ITERATION_SPEC for provider-enforced JSON structured output.
   No regex fallback, no code-level FINAL detection.

   Params:
   `rlm-env` - RLM environment map.
   `messages` - Vector of message maps for the LLM.
   `opts` - Map, optional:
     - :iteration-spec - Spec for ask! (default: ITERATION_SPEC).
                         When provider has reasoning, pass ITERATION_SPEC_CODE_ONLY.
     - :on-chunk - Streaming callback function."
  [rlm-env messages & [{:keys [iteration-spec on-chunk routing iteration] :or {iteration-spec ITERATION_SPEC}}]]
  (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-phase :run-iteration})]
    (let [model-name (resolve-root-model (:router rlm-env))
          input-tokens (router/count-messages (or model-name "gpt-4o") messages)
          _ (trove/log! {:level :info :id ::llm-call
                         :data {:env-id (:env-id rlm-env)
                                :iteration iteration
                                :msg-count (count messages)
                                :input-tokens input-tokens}
                         :msg "LLM call started"})
          ;; Use ask! with iteration spec — router auto-resolves max_tokens + reasoning_params
          ask-result (llm/ask! (:router rlm-env)
                       (cond-> {:spec iteration-spec
                                :messages messages
                                :routing (or routing {})
                                :check-context? false}
                         on-chunk (assoc :on-chunk on-chunk)))
          parsed (:result ask-result)
          model-reasoning (:reasoning ask-result)
          _ (rlm-debug! {:has-reasoning (some? model-reasoning)
                         :has-final (some? (:final parsed))
                         :code-count (count (:code parsed))} "ask! response received")
          ;; Native reasoning takes priority over spec-parsed thinking
          thinking (or model-reasoning (:thinking parsed))
          ;; LLM's preference for next iteration's model selection
          next-optimize (when-let [opt (:next-optimize parsed)]
                          (keyword opt))
          ;; Token usage from ask! result
          api-usage {:prompt_tokens (get-in ask-result [:tokens :input] 0)
                     :completion_tokens (get-in ask-result [:tokens :output] 0)
                     :completion_tokens_details {:reasoning_tokens (get-in ask-result [:tokens :reasoning] 0)}
                     :prompt_tokens_details {:cached_tokens (get-in ask-result [:tokens :cached] 0)}}]
      ;; Check for final answer in spec response
      (if-let [final-data (:final parsed)]
        (let [raw-answer (str (:answer final-data))
              final-answer (paren-repair/repair-code raw-answer)
              confidence (or (:confidence final-data) :high)
              ;; Language: SCI execution + validation only for Clojure
              language (when-let [l (:language final-data)] (keyword l))
              clojure? (or (= language :clojure) (nil? language))
              code-blocks (vec (remove str/blank? (or (:code parsed) [])))
              ;; Execute code blocks in SCI — only for Clojure
              exec-results (when (and clojure? (seq code-blocks))
                             (mapv (fn [code] (execute-code rlm-env code)) code-blocks))
              exec-errors (when exec-results
                            (seq (filter :error exec-results)))
              untested? (and clojure? (zero? (or iteration 0)) (empty? code-blocks))
              parse-check (when clojure?
                            (parse-clojure-syntax final-answer))
              validation-error (or (when untested?
                                     "You submitted final without running any code. Run the self-test first.")
                                 (when exec-errors
                                   (str "Code errors before final: " (:error (first exec-errors))))
                                 parse-check
                                 (validate-final {:answer final-answer
                                                  :answer-type (:answer-type final-data)
                                                  :language (:language final-data)}))
              executions (when exec-results
                           (mapv (fn [idx code result]
                                   {:id idx :code code
                                    :result (:result result) :stdout (:stdout result)
                                    :stderr (:stderr result) :error (:error result)
                                    :execution-time-ms (:execution-time-ms result)
                                    :repaired? (:repaired? result)})
                             (range) code-blocks exec-results))]
          (if validation-error
            (do (rlm-debug! {:final-answer (str-truncate final-answer 200)
                             :validation-error validation-error} "FINAL rejected")
                {:response nil :thinking thinking :next-optimize next-optimize
                 :executions (or executions
                               [{:id 0 :code final-answer :result nil :stdout "" :stderr ""
                                 :error validation-error}])
                 :final-result nil :api-usage api-usage
                 :duration-ms (or (:duration-ms ask-result) 0)})
            (let [sources (vec (or (:sources final-data) []))
                  final-result (cond-> {:final? true
                                        :answer {:result final-answer :type String}
                                        :confidence confidence}
                                 (seq sources) (assoc :sources sources)
                                 (:reasoning final-data) (assoc :reasoning (:reasoning final-data)))]
              (rlm-debug! {:final-answer (str-truncate final-answer 200)
                           :confidence confidence
                           :sources-count (count sources)} "Final answer accepted")
              {:response nil :thinking thinking :next-optimize next-optimize
               :executions (or executions []) :final-result final-result :api-usage api-usage
               :duration-ms (or (:duration-ms ask-result) 0)})))
        ;; Normal path: execute code blocks
        (let [raw-blocks (vec (remove str/blank? (or (:code parsed) [])))
              ;; Coalesce fragments: when model splits one expression across multiple
              ;; array entries (one line per string), join unbalanced blocks with the
              ;; next until parens balance. Prevents paren-repair from "fixing" each
              ;; line individually into a broken zero-body form.
              code-blocks (loop [remaining raw-blocks
                                 result []]
                            (if (empty? remaining)
                              result
                              (let [block (first remaining)
                                    bal (paren-repair/paren-balance block)]
                                (if (and (pos? bal) (next remaining))
                                  ;; Unbalanced opener - join with subsequent blocks
                                  (let [[joined rest-blocks]
                                        (loop [acc block
                                               rem (rest remaining)]
                                          (if (or (<= (paren-repair/paren-balance acc) 0) (empty? rem))
                                            [acc rem]
                                            (recur (str acc "\n" (first rem)) (rest rem))))]
                                    (recur rest-blocks (conj result joined)))
                                  (recur (rest remaining) (conj result block))))))
              _ (rlm-debug! {:code-block-count (count code-blocks)
                             :raw-count (count raw-blocks)
                             :code-previews (mapv #(str-truncate % 120) code-blocks)} "Code blocks extracted")
              execution-results (mapv (fn [code]
                                        (execute-code rlm-env code))
                                  code-blocks)
              ;; Combine code blocks with their execution results
              executions (mapv (fn [idx code result]
                                 {:id idx
                                  :code code
                                  :result (:result result)
                                  :stdout (:stdout result)
                                  :stderr (:stderr result)
                                  :error (:error result)
                                  :execution-time-ms (:execution-time-ms result)
                                  :repaired? (:repaired? result)})
                           (range) code-blocks execution-results)]
          {:response nil :thinking thinking :next-optimize next-optimize
           :executions executions :final-result nil :api-usage api-usage
           :duration-ms (or (:duration-ms ask-result) 0)})))))

(defn- error-hint
  "Returns a specialized hint for a known error, or nil. Extracts context
   from the error message for a targeted fix suggestion."
  [error-msg]
  (when error-msg
    (let [e (str error-msg)]
      (cond
        ;; Unable to resolve symbol: X -> tell them exactly which symbol
        (re-find #"Unable to resolve symbol: (\S+)" e)
        (let [[_ sym] (re-find #"Unable to resolve symbol: (\S+)" e)]
          (str "'" sym "' is not defined. (def " sym " ...) or check spelling."))

        ;; Wrong number of args (N) passed to: X
        (re-find #"Wrong number of args \((\d+)\) passed to: (\S+)" e)
        (let [[_ n target] (re-find #"Wrong number of args \((\d+)\) passed to: (\S+)" e)]
          (cond
            (str/includes? target "PersistentVector")
            (str "Vectors take 1 arg (index). Use (nth v idx) or (subvec v start end), not (v " (str/join " " (repeat (parse-long n) "x")) ").")
            :else
            (str "Function expects different arity than " n ". Check with (doc fn-name).")))

        ;; Long cannot be cast to IFn
        (str/includes? e "cannot be cast to clojure.lang.IFn")
        "You're calling a non-function. Bare (1 2 3) calls 1 as fn. Use '(1 2 3) for list literals."

        ;; Nested fn literals
        (str/includes? e "Nested fn")
        "Nested #() is illegal. Rewrite inner #() as (fn [x] ...)."

        ;; Unbound fn
        (re-find #"unbound fn: #'sandbox/(\S+)" e)
        (let [[_ sym] (re-find #"unbound fn: #'sandbox/(\S+)" e)]
          (str "'" sym "' was declared but its defn failed. Fix the defn above first."))

        ;; LazySeq cast
        (str/includes? e "LazySeq")
        "conj/peek/pop need a vector, not a lazy seq. Wrap with (vec ...) first."

        ;; NullPointerException
        (str/includes? e "NullPointerException")
        "Something is nil unexpectedly. Debug with (prn suspect-value) to find which value is nil."

        ;; recur tail position
        (re-find #"recur.*tail" e)
        "recur must be the last expression in a loop/fn body."

        ;; Unmatched delimiter inside map with char literals (\} \{)
        (and (str/includes? e "Unmatched delimiter")
          (str/includes? e "}"))
        "Map literals with \\} or \\{ as keys/values confuse the reader. Use (hash-map \\) \\( \\] \\[ \\} \\{) instead of {\\} \\{ ...}."

        ;; EOF in map/fn context — might be char literal issue
        (and (str/includes? e "EOF while reading")
          (str/includes? e "match {"))
        "If your map contains bracket char literals like \\} \\{, use (hash-map ...) instead of a map literal."

        :else nil))))

(defn format-executions
  "Formats executions for LLM feedback as EDN.
   All results shown inline — context budget handles size naturally.
   Error hints injected only when an error matches a known pattern."
  [executions]
  (str/join "\n"
    (map (fn [{:keys [code error result stdout repaired?]}]
           (let [code-str (str/trim (or code ""))
                 hint (when error (error-hint error))
                 val-part (cond
                            error
                            (str ":error " (pr-str error)
                              (when hint (str " :hint " (pr-str hint))))

                            (fn? result)
                            (str ":error \"" code-str " is a function object. Call it: (" code-str ")\"")

                            :else
                            (str ":ok " (pr-str (realize-value result))))

                 stdout-part (when-not (str/blank? stdout)
                               (str " :stdout " (pr-str stdout)))
                 warning-part (when repaired?
                                " :warning \"auto-repaired delimiters\"")]
             (str "{" code-str " → " val-part (or stdout-part "") (or warning-part "") "}")))
      executions)))

(defn- format-execution-results
  "Formats previous iteration's executions as structured XML receipt.
   Shows success/failure, result-type, size, errors per code block."
  [executions iteration]
  (when (seq executions)
    (str "<execution_results iteration=\"" iteration "\">\n"
      (str/join "\n"
        (map-indexed
          (fn [idx {:keys [code error result stdout stderr]}]
            (let [code-str (str/trim (or code ""))
                  result-info (cond
                                error
                                (str "{:success? false :error " (pr-str (str-truncate error 200)) "}")

                                (fn? result)
                                "{:success? false :error \"Result is a function, not a value\"}"

                                :else
                                (let [v (realize-value result)
                                      type-label (cond
                                                   (nil? v) "nil"
                                                   (map? v) "map"
                                                   (vector? v) "vector"
                                                   (set? v) "set"
                                                   (sequential? v) "seq"
                                                   (string? v) "string"
                                                   (integer? v) "int"
                                                   (float? v) "float"
                                                   (boolean? v) "bool"
                                                   (keyword? v) "keyword"
                                                   :else (.getSimpleName (class v)))
                                      size (cond
                                             (nil? v) ""
                                             (string? v) (str " :size " (count v) "-chars")
                                             (coll? v) (str " :size " (count v) "-items")
                                             :else "")]
                                  (str "{:success? true :result-type " type-label size
                                    (when-not (str/blank? stdout) (str " :stdout " (pr-str (str-truncate stdout 100))))
                                    (when-not (str/blank? stderr) (str " :stderr " (pr-str (str-truncate stderr 100))))
                                    "}")))]
              (str "  [" (inc idx) "] " code-str "\n      " result-info)))
          executions))
      "\n</execution_results>")))

(def ^:private SQUASH_INTERVAL
  "Number of iterations per squash cycle."
  5)

(defn- format-journal-entry
  "Formats a single journal entry as text."
  [{:keys [iteration thinking var-names]}]
  (str "[iteration-" (inc iteration) "] "
    (when thinking (str-truncate thinking 300))
    (when (seq var-names)
      (str "\n    vars: " (str/join ", " var-names)))))

(defn- squash-journal
  "Performs cumulative mechanical squash on journal entries.
   Every SQUASH_INTERVAL iterations, all entries up to the boundary get concatenated
   with --- separators into one squashed block. Returns [squashed-entries recent-entries]."
  [journal]
  (let [n (count journal)
        squash-boundary (* SQUASH_INTERVAL (quot n SQUASH_INTERVAL))]
    (if (< squash-boundary SQUASH_INTERVAL)
      ;; Not enough entries to squash yet
      [nil journal]
      ;; Split into squashed + recent
      [(subvec journal 0 squash-boundary)
       (subvec journal squash-boundary)])))

(defn- render-execution-journal
  "Renders the execution journal as XML for prompt injection.
   Squashed entries are concatenated with --- separators.
   Recent entries are shown individually."
  [journal]
  (when (seq journal)
    (let [[squashed recent] (squash-journal journal)]
      (str "<execution_journal>\n"
        (when (seq squashed)
          (str "  [iterations 1-" (count squashed) " squashed]\n    "
            (str/join "\n    ---\n    "
              (map format-journal-entry squashed))
            "\n"))
        (when (seq recent)
          (str/join "\n"
            (map (fn [entry] (str "  " (format-journal-entry entry))) recent)))
        "\n</execution_journal>"))))

(defn- extract-def-names
  "Extracts var names from code blocks via EDN parsing of def-like forms."
  [executions]
  (->> executions
    (mapcat (fn [{:keys [code error]}]
              (when-not error
                (try
                  (->> (edamame/parse-string-all (or code "") {:all true})
                    (keep (fn [form]
                            (when (seq? form)
                              (let [[op name & _] form]
                                (when (and (contains? '#{def defn defn- defonce defmulti defmacro} op)
                                        (symbol? name))
                                  name)))))
                    distinct)
                  (catch Exception _ [])))))
    (map str)
    vec))

(defn- restorable-var-snapshots
  "Returns serializable snapshots of user vars introduced by this iteration."
  [rlm-env executions]
  (let [execution->defs (mapv (fn [{:keys [error] :as execution}]
                                [execution (when-not error (set (map symbol (extract-def-names [execution]))))])
                          executions)
        defined (into #{} (mapcat second) execution->defs)
        sym->code (reduce (fn [acc [{:keys [code]} defs]]
                            (if (and code (seq defs))
                              (reduce #(assoc %1 %2 code) acc defs)
                              acc))
                    {}
                    execution->defs)
        locals (get-locals rlm-env)]
    (->> locals
      (keep (fn [[sym value]]
              (when (contains? defined sym)
                (let [realized (realize-value value)]
                  (when (or (nil? realized)
                          (string? realized)
                          (number? realized)
                          (keyword? realized)
                          (boolean? realized)
                          (symbol? realized)
                          (map? realized)
                          (vector? realized)
                          (set? realized)
                          (list? realized)
                          (sequential? realized))
                    {:name (str sym)
                     :value realized
                     :code (get sym->code sym)})))))
      vec)))

(defn- build-restore-context
  "Builds compact query-level restore context for a continued conversation."
  [db-info conversation-ref]
  (let [history (when (and db-info conversation-ref)
                  (rlm-db/db-query-history db-info conversation-ref))
        recent (take-last 2 history)]
    (when (seq recent)
      (str "<restore_context>\n"
        "Previous conversation detected. Prior vars are NOT auto-loaded. Restore only what you need.\n"
        "Tools: (session-history), (session-code q), (session-results q), (restore-var 'sym), (restore-vars ['a 'b])\n"
        "Recent queries:\n"
        (str/join "\n"
          (map (fn [{:keys [query-pos text answer-preview key-vars iterations]}]
                 (str "  [" query-pos "] " (pr-str (str-truncate (or text "") 100))
                   "\n    final: " (pr-str (or answer-preview ""))
                   "\n    key-vars: " (pr-str (mapv str key-vars))
                   "\n    iterations: " iterations))
            recent))
        "\n</restore_context>"))))

(defn- rehydrate-final-results!
  "Injects previous final-result-N vars into SCI context from Datalevin.
   Final results are now terminal iterations (with non-nil :iteration/answer).
   Returns the list of final results for conversation thread rendering."
  [sci-ctx db-info-atom conversation-ref]
  (when db-info-atom
    (let [results (db-list-final-results @db-info-atom {:conversation-ref conversation-ref})]
      (doseq [[idx result] (map-indexed vector results)]
        (let [var-name (str "final-result-" (inc idx))
              answer (:iteration/answer result)
              doc-str (str-truncate (or answer "Previous query result") 100)
              val-map {:answer answer}]
          (try
            (let [sandbox-ns (sci/find-ns sci-ctx 'sandbox)]
              (sci/eval-string+ sci-ctx
                (str "(def ^{:doc " (pr-str doc-str) "} " var-name " " (pr-str val-map) ")")
                {:ns sandbox-ns}))
            (catch Exception _ nil))))
      results)))

(defn iteration-loop [rlm-env query
                      {:keys [output-spec max-context-tokens custom-docs system-prompt
                              pre-fetched-context query-ref user-messages
                              max-iterations max-consecutive-errors max-restarts
                              hooks cancel-atom current-iteration-atom]}]
  (let [max-iterations (or max-iterations 50)
        max-consecutive-errors (or max-consecutive-errors 5)
        max-restarts (or max-restarts 3)
        ;; Adaptive budget: if rlm-env has a max-iterations-atom (set by query-env!),
        ;; read from it so the LLM can extend its own budget via (request-more-iterations n).
        ;; Otherwise use the static max-iterations parameter.
        max-iter-atom (:max-iterations-atom rlm-env)
        effective-max-iterations (fn [] (if max-iter-atom @max-iter-atom max-iterations))
        ;; Resolve effective model name for token counting
        effective-model (resolve-root-model (:router rlm-env))
        _ (assert effective-model "Router must resolve a root model — check provider config")
        ;; Default max-context-tokens to 60% of model's context window.
        ;; Prevents unbounded history accumulation (quadratic token growth over iterations).
        max-context-tokens (or max-context-tokens
                             (long (* 0.6 (router/context-limit effective-model))))
        ;; Check if root provider has native reasoning (thinking tokens)
        has-reasoning? (boolean (provider-has-reasoning? (:router rlm-env)))
        has-docs? (when-let [db-atom (:db-info-atom rlm-env)]
                    (when-let [db @db-atom]
                      (pos? (count (db-list-documents db {:limit 1 :include-toc? false})))))
        doc-summary (when (and has-docs? (:db-info-atom rlm-env))
                      (build-document-summary @(:db-info-atom rlm-env)))
        ;; Git repos are read from Datalevin on each iteration (NOT from an
        ;; atom) so persistent conversations resume with attached repos
        ;; intact. Empty list elides the GIT REPO block entirely from the
        ;; system prompt.
        git-repos (when-let [db-atom (:db-info-atom rlm-env)]
                    (when-let [db @db-atom]
                      (rlm-db/db-list-repos db)))
        system-prompt (build-system-prompt {:output-spec output-spec
                                            :custom-docs custom-docs
                                            :has-documents? has-docs?
                                            :document-summary doc-summary
                                            :has-reasoning? has-reasoning?
                                            :system-prompt system-prompt
                                            :git-repos git-repos
                                            :max-context-tokens max-context-tokens
                                            :skill-registry (when-let [a (:skill-registry-atom rlm-env)] @a)})
        context-data (:context rlm-env)
        context-str (pr-str context-data)
        context-preview (if (> (count context-str) 2000)
                          (str (subs context-str 0 2000) "\n... [truncated, use code to explore]")
                          context-str)
        initial-user-content (str "{:context " (pr-str context-preview)
                               "\n :requirement " (pr-str query)
                               (when pre-fetched-context
                                 (str "\n :plan " (pr-str pre-fetched-context)))
                               "}")
        ;; Build initial messages: system + structured context/requirement + original user messages (multimodal)
        initial-messages (into [{:role "system" :content system-prompt}
                                {:role "user" :content initial-user-content}]
                           (when (and user-messages
                                   (some #(sequential? (:content %)) user-messages))
                              ;; Include original multimodal messages (images etc.) as additional context
                             user-messages))
        ;; Store initial messages if history tracking is enabled
        db-info (when-let [atom (:db-info-atom rlm-env)] @atom)
        ;; Cost tracking: accumulate token usage across all iterations
        usage-atom (atom {:input-tokens 0 :output-tokens 0 :reasoning-tokens 0 :cached-tokens 0})
        accumulate-usage! (fn [api-usage]
                            (when api-usage
                              (swap! usage-atom
                                (fn [acc]
                                  (-> acc
                                    (update :input-tokens + (or (:prompt_tokens api-usage) 0))
                                    (update :output-tokens + (or (:completion_tokens api-usage) 0))
                                    (update :reasoning-tokens + (or (get-in api-usage [:completion_tokens_details :reasoning_tokens]) 0))
                                    (update :cached-tokens + (or (get-in api-usage [:prompt_tokens_details :cached_tokens]) 0)))))))
        ;; Repetition detection: track individual call→result pairs across iterations
        call-counts-atom (atom {})  ;; {[code result-str] count}
        detect-repetition (fn [executions]
                            (let [pairs (mapv (fn [e] [(:code e) (str-truncate (str (:result e)) 200)]) executions)
                                  counts (swap! call-counts-atom
                                           (fn [m] (reduce (fn [acc p] (update acc p (fnil inc 0))) m pairs)))
                                  repeated (->> pairs
                                             (filter #(>= (get counts % 0) 3))
                                             (map first))]
                              (when (seq repeated)
                                (str "\n\n⚠ REPETITION DETECTED: These calls have been executed 3+ times with the SAME results:\n"
                                  (str/join "\n" (map #(str "  - " (str-truncate (str %) 80)) (distinct repeated)))
                                  "\nRepeating the same action will NOT produce different results. "
                                  "You MUST try a DIFFERENT approach, or call \final\": {\"answer\": \"your answer\", \"confidence\": \"high\"} with what you have."))))
        finalize-cost (fn []
                        (let [{:keys [input-tokens output-tokens reasoning-tokens cached-tokens]} @usage-atom
                              total-tokens (+ input-tokens output-tokens)
                              cost (router/estimate-cost effective-model input-tokens output-tokens)]
                          {:tokens {:input input-tokens :output output-tokens
                                    :reasoning reasoning-tokens :cached cached-tokens
                                    :total total-tokens}
                           :cost cost}))
        ;; Cache var-index by env-level execution revision so it survives across queries.
        ;; SCI may keep stable sandbox map identity across (def ...) updates.
        var-index-cache-atom (or (:var-index-cache-atom rlm-env) (atom {:revision -1 :index nil}))
        var-index-revision-atom (or (:var-index-revision-atom rlm-env) (atom 0))
        get-var-index (fn []
                        (let [var-index-revision @var-index-revision-atom
                              {:keys [revision index]} @var-index-cache-atom]
                          (if (= revision var-index-revision)
                            index
                            (let [sandbox-map (get-in @(:env (:sci-ctx rlm-env)) [:namespaces 'sandbox])
                                  idx (build-var-index (:sci-ctx rlm-env) (:initial-ns-keys rlm-env) sandbox-map)]
                              (reset! var-index-cache-atom {:revision var-index-revision :index idx})
                              idx))))
        ;; Keep final-result rehydration for backwards-compatible SCI access.
        prev-final-results (rehydrate-final-results! (:sci-ctx rlm-env)
                             (:db-info-atom rlm-env)
                             (:conversation-ref rlm-env))
        ;; Rehydration mutates SCI vars; mark var-index as stale.
        _ (swap! var-index-revision-atom inc)
        restore-context (build-restore-context db-info (:conversation-ref rlm-env))
        on-chunk (:on-chunk hooks)
        on-iteration (:on-iteration hooks)
        on-cancel (:on-cancel hooks)
        emit-hook! (fn [hook-fn payload log-msg]
                     (when hook-fn
                       (try
                         (hook-fn payload)
                         (catch Exception e
                           (trove/log! {:level :warn :data {:error (ex-message e)}
                                        :msg log-msg})))))]
    (rlm-debug! {:query query :max-iterations max-iterations :model effective-model
                 :has-output-spec? (some? output-spec) :has-pre-fetched? (some? pre-fetched-context)
                 :has-reasoning? has-reasoning?
                 :prev-final-results (count prev-final-results)
                 :has-restore-context? (some? restore-context)
                 :msg-count (count initial-messages)} "Iteration loop started")
    (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-phase :iteration-loop})]
      (loop [iteration 0 messages initial-messages trace [] consecutive-errors 0 restarts 0
             prev-executions nil prev-iteration -1
             journal [] prev-optimize nil]
        (when current-iteration-atom
          (reset! current-iteration-atom iteration))
        (cond
          ;; Cooperative cancellation — caller-owned :cancel-atom from query-env!
          ;; (or an internal per-query atom when not supplied).
          (when cancel-atom @cancel-atom)
          (do (trove/log! {:level :info :data {:iteration iteration}
                           :msg "Query cancelled via cancel-atom"})
              (emit-hook! on-cancel {:iteration iteration
                                     :status :cancelled
                                     :status-id (status->id :cancelled)}
                "on-cancel hook threw — swallowing")
              (merge {:answer nil
                      :status :cancelled
                      :status-id (status->id :cancelled)
                      :trace trace
                      :iterations iteration}
                (finalize-cost)))

          (>= iteration (effective-max-iterations))
          (let [debug? (:rlm-debug? *rlm-ctx*)
                locals (when debug? (get-locals rlm-env))]
            (trove/log! {:level :warn :data {:iteration iteration :max (effective-max-iterations)}
                         :msg "Max iterations reached — call (request-more-iterations N) earlier to extend budget"})
            (merge {:answer nil
                    :status :max-iterations
                    :status-id (status->id :max-iterations)
                    :trace trace
                    :iterations iteration}
              (when debug? {:locals locals})
              (finalize-cost)))

          :else
          (if (>= consecutive-errors max-consecutive-errors)
            ;; Strategy restart: instead of terminating, reset with anti-knowledge
            (if (< restarts max-restarts)
              (let [failed-summary (->> trace
                                     (filter :error)
                                     (take 3)
                                     (map #(str "- " (get-in % [:error :message] (str (:error %)))))
                                     (str/join "\n"))
                    restart-hint (str "{:strategy-restart true\n"
                                   " :errors " (pr-str failed-summary) "\n"
                                   " :instruction \"Start fresh with a DIFFERENT strategy. Do NOT repeat the same approach. Consider: different search terms, different tools, different data access pattern.\"\n"
                                   " :requirement " (pr-str query) "}")
                    restart-messages [{:role "system" :content system-prompt}
                                      {:role "user" :content restart-hint}]]
                (trove/log! {:level :info :data {:iteration iteration :restarts (inc restarts)
                                                 :errors consecutive-errors}
                             :msg "Strategy restart — resetting with anti-knowledge"})
                (rlm-debug! {:failed-summary failed-summary} "Strategy restart triggered")
                (recur (inc iteration) restart-messages trace 0 (inc restarts)
                  nil -1 journal nil))
              (do (trove/log! {:level :warn :data {:iteration iteration :consecutive-errors consecutive-errors
                                                   :restarts restarts}
                               :msg "Error budget exhausted — too many consecutive errors across restarts. Simplify your code or break the task into smaller steps."})
                  (merge {:answer nil :status :error-budget-exhausted :trace trace :iterations iteration}
                    {:status-id (status->id :error-budget-exhausted)}
                    (finalize-cost))))
            (let [_ (rlm-debug! {:iteration iteration :msg-count (count messages)} "Iteration start")
                  ;; Build single-shot prompt: conversation + journal + execution results + var index
                  var-index-str (get-var-index)
                  exec-results-str (format-execution-results prev-executions prev-iteration)
                  journal-str (render-execution-journal journal)
                  iteration-context (str
                                      (when restore-context (str restore-context "\n"))
                                      (when journal-str (str "\n" journal-str))
                                      (when exec-results-str (str "\n" exec-results-str))
                                      (when var-index-str
                                        (str "\n<var_index>\n" var-index-str "\n</var_index>")))
                  base-messages (vec (take 2 messages)) ;; [system-prompt, user-query]
                  effective-messages (cond-> base-messages
                                       (not (str/blank? iteration-context))
                                       (conj {:role "user" :content iteration-context}))
                  ;; Streaming callback: parse partial JSON for thinking/code
                  iter-on-chunk (when on-chunk
                                  (fn [accumulated-text]
                                    (let [partial (jsonish/parse-partial accumulated-text)]
                                      (on-chunk {:iteration iteration
                                                 :thinking (or (:thinking partial) accumulated-text)
                                                 :code (when-let [c (:code partial)]
                                                         (if (sequential? c) (vec c) nil))
                                                 :final nil
                                                 :done? false}))))
                  iteration-result (try
                                     (run-iteration rlm-env effective-messages
                                       (cond-> {:iteration-spec (if has-reasoning?
                                                                  ITERATION_SPEC_CODE_ONLY
                                                                  ITERATION_SPEC)
                                                :iteration iteration
                                                :routing (when prev-optimize {:optimize prev-optimize})}
                                         iter-on-chunk (assoc :on-chunk iter-on-chunk)))
                                     (catch Exception e
                                       (let [err-msg (ex-message e)
                                             err-type (:type (ex-data e))]
                                         (trove/log! {:level :warn
                                                      :data {:iteration iteration :error err-msg :type err-type}
                                                      :msg "RLM iteration failed, feeding error to LLM"})
                                         ;; Return ::iteration-error sentinel — loop will feed error to LLM
                                         {::iteration-error {:message err-msg :type err-type}})))]
              (if-let [iter-err (::iteration-error iteration-result)]
                ;; Error path: feed error back to LLM as user message, let it recover
                (let [error-feedback (str "[Iteration " (inc iteration) "/" (effective-max-iterations) "]\n"
                                       "<error>LLM call failed: " (:message iter-err) "</error>\n"
                                       "The previous attempt failed. Adjust your approach or call \final\": {\"answer\": \"your answer\", \"confidence\": \"high\"} with what you have.")
                      trace-entry {:iteration iteration :error iter-err :final? false}]
                  ;; Store error iteration snapshot
                  (rlm-db/store-iteration! db-info
                    {:query-ref query-ref
                     :vars []
                     :executions nil :thinking nil :duration-ms 0})
                  ;; Global observer hook after store-iteration! (error path)
                  (emit-hook! on-iteration
                    {:iteration iteration
                     :status :error
                     :status-id (status->id :error)
                     :thinking nil
                     :executions nil
                     :final-result nil
                     :error iter-err
                     :duration-ms 0}
                    "on-iteration hook threw (error branch) — swallowing")
                  (recur (inc iteration)
                    (conj messages {:role "user" :content error-feedback})
                    (conj trace trace-entry)
                    (inc consecutive-errors)
                    restarts
                    nil -1 journal nil))
                ;; Normal path — accumulate token usage
                (let [_ (accumulate-usage! (:api-usage iteration-result))
                      {:keys [response thinking executions final-result next-optimize]} iteration-result
                      vars-snapshot (restorable-var-snapshots rlm-env executions)
                      ;; Store iteration snapshot — exact input/output for fine-tuning
                      _traj-iter (rlm-db/store-iteration! db-info
                                   {:query-ref query-ref
                                    :executions executions
                                    :vars vars-snapshot
                                    :thinking thinking
                                    :answer (when final-result (answer-str (:answer final-result)))
                                    :duration-ms (or (:duration-ms iteration-result) 0)})
                      ;; Global observer hook after store-iteration! (success/empty/final)
                      _ (emit-hook! on-iteration
                          {:iteration iteration
                           :status (cond
                                     final-result :final
                                     (empty? executions) :empty
                                     :else :success)
                           :status-id (status->id (cond
                                                    final-result :final
                                                    (empty? executions) :empty
                                                    :else :success))
                           :thinking thinking
                           :executions executions
                           :final-result final-result
                           :error nil
                           :duration-ms (or (:duration-ms iteration-result) 0)}
                          "on-iteration hook threw (success branch) — swallowing")
                      trace-entry {:iteration iteration
                                   :response response
                                   :thinking thinking
                                   :executions executions
                                   :final? (boolean final-result)}]
                  (if final-result
                    (do (trove/log! {:level :info :data {:iteration iteration :answer (str-truncate (answer-str (:answer final-result)) 200)} :msg "FINAL detected"})
                        ;; Fire final streaming callback
                        (when on-chunk
                          (on-chunk {:iteration iteration
                                     :thinking thinking
                                     :code (mapv :code executions)
                                     :final {:answer (:answer final-result)
                                             :confidence (:confidence final-result)
                                             :summary (:summary final-result)
                                             :iterations (inc iteration)
                                             :status :success}
                                     :done? true}))
                        ;; Final result persisted via store-iteration! with :iteration/answer
                        (merge (cond-> {:answer (:answer final-result)
                                        :trace (conj trace trace-entry)
                                        :iterations (inc iteration)
                                        :confidence (:confidence final-result)}
                                 (:sources final-result)   (assoc :sources (:sources final-result))
                                 (:reasoning final-result) (assoc :reasoning (:reasoning final-result)))
                          (finalize-cost)))
                    (if (empty? executions)
                      ;; Empty iteration: DON'T increment iteration counter, DON'T add to trace.
                      ;; Retry immediately with a nudge — this doesn't waste an iteration slot.
                      (let [nudge (str "[Iteration " (inc iteration) "/" (effective-max-iterations) "]\n"
                                    "{:requirement " (pr-str (str-truncate query 200)) "}\n"
                                    "⚠ EMPTY — no code executed. You MUST include code. "
                                    (if has-reasoning?
                                      "Respond with code or set final to finish."
                                      "Respond with thinking + code, or set final to finish."))]
                        ;; Store empty iteration snapshot
                        (rlm-db/store-iteration! db-info
                          {:query-ref query-ref
                           :vars []
                           :executions nil :thinking thinking :duration-ms (or (:duration-ms iteration-result) 0)})
                        (recur (inc iteration) ;; still increment to prevent infinite loop
                          (conj messages
                            {:role "assistant" :content (or response thinking "[empty]")}
                            {:role "user" :content nudge})
                          trace ;; DON'T add empty trace entry
                          (inc consecutive-errors)
                          restarts
                          nil -1 journal next-optimize))
                      ;; Normal iteration with executions
                      (let [exec-feedback (format-executions executions)
                            iteration-header (str "[Iteration " (inc iteration) "/" (effective-max-iterations) "]\n"
                                               "{:requirement " (pr-str (str-truncate query 200)) "}")
                            repetition-warning (detect-repetition executions)
                            remaining-iters (- (effective-max-iterations) (inc iteration))
                            budget-warning (when (<= remaining-iters 5)
                                             (str "\n[SYSTEM_NUDGE] Only " remaining-iters " iterations left! "
                                               "Set final NOW with what you have. DO NOT start new explorations."))
                            force-final-nudge (when (> iteration 20)
                                                (str "\n[SYSTEM_NUDGE] You have been running for " (inc iteration) " iterations. "
                                                  "STOP exploring. Set final IMMEDIATELY with your current findings."))
                            user-feedback (str iteration-header "\n" exec-feedback repetition-warning budget-warning force-final-nudge)]
                        (rlm-debug! {:iteration iteration
                                     :code-blocks (count executions)
                                     :errors (count (filter :error executions))
                                     :has-thinking? (some? thinking)
                                     :thinking-preview (when thinking (str-truncate thinking 150))
                                     :feedback-len (count user-feedback)} "Iteration feedback")
                        (let [had-successful-execution? (some #(nil? (:error %)) executions)
                              next-errors (if had-successful-execution? 0 (inc consecutive-errors))
                              _ (when had-successful-execution?
                                  (swap! var-index-revision-atom inc))
                              journal-entry {:iteration iteration
                                             :thinking thinking
                                             :var-names (extract-def-names executions)}]
                          (recur (inc iteration)
                            messages
                            (conj trace trace-entry)
                            next-errors
                            restarts
                            executions iteration
                            (conj journal journal-entry) next-optimize))))))))))))))

;; =============================================================================
;; Entity Extraction Functions
;; =============================================================================

(defn extract-entities-from-page!
  "Extracts entities from a page's text nodes using LLM.

   Params:
   `text-content` - String. Combined text from page nodes.
   `rlm-router` - Router from llm/make-router.

   Returns:
   Map with :entities and :relationships keys (empty if extraction fails)."
  [text-content rlm-router]
  (try
    (let [truncated (if (> (count text-content) 8000) (subs text-content 0 8000) text-content)
          response (llm/ask! rlm-router {:spec ENTITY_EXTRACTION_SPEC
                                         :messages [(llm/system ENTITY_EXTRACTION_OBJECTIVE)
                                                    (llm/user truncated)]
                                         :routing {:optimize :cost}})]
      (or (:result response) {:entities [] :relationships []}))
    (catch Exception e
      (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Entity extraction failed for page"})
      {:entities [] :relationships []})))

(defn extract-entities-from-visual-node!
  "Extracts entities from a visual node (image/table) using vision or text.

   Params:
   `node` - Map. Page node with :page.node/type, :page.node/image-data, :page.node/description.
   `rlm-router` - Router from llm/make-router.

   Returns:
   Map with :entities and :relationships keys (empty if extraction fails)."
  [node rlm-router]
  (try
    (let [image-data (:page.node/image-data node)
          description (:page.node/description node)]
      (cond
        ;; Has image data - use vision
        image-data
        (let [b64 (bytes->base64 image-data)
              response (llm/ask! rlm-router {:spec ENTITY_EXTRACTION_SPEC
                                             :messages [(llm/system ENTITY_EXTRACTION_OBJECTIVE)
                                                        (llm/user (or description "Extract entities from this image")
                                                          (llm/image b64 "image/png"))]
                                             :routing {:optimize :cost}})]
          (or (:result response) {:entities [] :relationships []}))
        ;; Has description only - text extraction
        description
        (let [response (llm/ask! rlm-router {:spec ENTITY_EXTRACTION_SPEC
                                             :messages [(llm/system ENTITY_EXTRACTION_OBJECTIVE)
                                                        (llm/user description)]
                                             :routing {:optimize :cost}})]
          (or (:result response) {:entities [] :relationships []}))
        ;; Neither - skip
        :else
        (do (trove/log! {:level :warn :msg "Visual node has no image-data or description, skipping"})
            {:entities [] :relationships []})))
    (catch Exception e
      (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Visual node extraction failed"})
      {:entities [] :relationships []})))

(defn extract-entities-from-document!
  "Extracts entities from all pages of a document.

   Params:
   `db-info` - Map. Database info with :store key.
   `document` - Map. PageIndex document.
   `rlm-router` - Router from llm/make-router.
   `opts` - Map. Options with :max-extraction-pages, :max-vision-rescan-nodes.

   Returns:
   Map with extraction statistics: :entities-extracted, :relationships-extracted,
   :pages-processed, :extraction-errors, :visual-nodes-scanned."
  [db-info document rlm-router opts]
  (let [max-pages (or (:max-extraction-pages opts) 50)
        max-vision (or (:max-vision-rescan-nodes opts) 10)
        pages (take max-pages (:document/pages document))
        doc-id (:document/id document (:document/name document))
        entities-atom (atom [])
        relationships-atom (atom [])
        errors-atom (atom 0)
        vision-count-atom (atom 0)]
    ;; Process each page
    (doseq [page pages]
      (let [nodes (:page/nodes page)
            text-nodes (filter #(not (#{:image :table} (:page.node/type %))) nodes)
            visual-nodes (filter #(#{:image :table} (:page.node/type %)) nodes)]
        ;; Extract from text
        (when (seq text-nodes)
          (let [text (str/join "\n" (keep :page.node/content text-nodes))]
            (when (not (str/blank? text))
              (try
                (let [result (extract-entities-from-page! text rlm-router)]
                  (swap! entities-atom into (:entities result))
                  (swap! relationships-atom into (:relationships result)))
                (catch Exception e
                  (trove/log! {:level :warn :data {:page (:page/index page) :error (ex-message e)}
                               :msg "Entity extraction failed for page"})
                  (swap! errors-atom inc))))))
        ;; Extract from visual nodes (capped)
        (doseq [vnode visual-nodes]
          (when (< @vision-count-atom max-vision)
            (try
              (let [result (extract-entities-from-visual-node! vnode rlm-router)]
                (swap! vision-count-atom inc)
                (swap! entities-atom into (:entities result))
                (swap! relationships-atom into (:relationships result)))
              (catch Exception e
                (trove/log! {:level :warn :data {:node-type (:page.node/type vnode) :error (ex-message e)}
                             :msg "Entity extraction failed for visual node"})
                (swap! errors-atom inc)))))))
    ;; Persist extraction results (entity + relationship transactions)
    (let [entities @entities-atom
          relationships @relationships-atom
          persisted (rlm-data/store-extraction-results! db-info doc-id entities relationships)]
      {:entities-extracted (:entities-extracted persisted)
       :relationships-extracted (:relationships-extracted persisted)
       :pages-processed (count pages)
       :extraction-errors @errors-atom
       :visual-nodes-scanned @vision-count-atom})))

;; =============================================================================
;; Public API - Component-Based Architecture
;; =============================================================================
