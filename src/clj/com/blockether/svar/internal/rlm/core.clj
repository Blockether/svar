(ns com.blockether.svar.internal.rlm.core
  (:require
   [clojure.string :as str]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.defaults :as defaults]
   [com.blockether.svar.internal.rlm.db
    :refer [create-rlm-conn dispose-rlm-conn!
            db-list-documents db-store-final-result!
            db-list-final-results db-store-pageindex-document! str-truncate]]
   [com.blockether.svar.internal.rlm.routing
    :refer [make-routed-llm-query-fn resolve-root-model provider-has-reasoning?]]
   [com.blockether.svar.internal.rlm.schema
    :refer [ENTITY_EXTRACTION_SPEC ENTITY_EXTRACTION_OBJECTIVE
            ITERATION_SPEC ITERATION_SPEC_CODE_ONLY
            EVAL_TIMEOUT_MS
            MAX_ITERATIONS MAX_ITERATION_CAP
            bytes->base64 *rlm-ctx*]]
   [com.blockether.svar.internal.rlm.tools :refer [create-sci-context realize-value build-var-index]]
   [com.blockether.svar.internal.jsonish :as jsonish]
   [com.blockether.svar.internal.spec :as spec]
   [com.blockether.svar.internal.util :as util]
   [datalevin.core :as d]
   [sci.core :as sci]
   [taoensso.trove :as trove]))

(declare build-system-prompt run-iteration format-executions)

(defn rlm-debug!
  "Logs at :info level only when :rlm-debug? is true in *rlm-ctx*.
   Includes :rlm-phase from context automatically in data."
  [data msg]
  (when (:rlm-debug? *rlm-ctx*)
    (trove/log! {:level :info :data (assoc data :rlm-phase (:rlm-phase *rlm-ctx*)) :msg msg})))

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
     - :db - External db connection, false to disable, or nil for auto-create.
     - :path - Path for persistent DB (history survives across sessions).
     - :documents - Vector of PageIndex documents to preload (stored exactly as-is).

   Returns:
   Map with :sci-ctx, :context, :llm-query-fn, :locals-atom,
   :inject-atom, :db-info-atom, :router."
  ([context-data depth-atom router]
   (create-rlm-env context-data depth-atom router {}))
  ([context-data depth-atom router {:keys [db path documents]}]
   (when-not router
     (throw (ex-info "Router is required for RLM environment" {:type :rlm/missing-router})))
   (let [locals-atom (atom {})
         db-info (cond
                   (false? db) nil
                   (and (map? db) (:conn db)) (assoc db :owned? false)
                   :else (create-rlm-conn path))
         db-info-atom (when db-info (atom db-info))
         _ (when (and db-info-atom (seq documents))
             (doseq [doc documents]
               (db-store-pageindex-document! @db-info-atom doc)))
         llm-query-fn (make-routed-llm-query-fn {:strategy :root} depth-atom router)
         {:keys [sci-ctx inject-atom initial-ns-keys]} (create-sci-context context-data llm-query-fn db-info-atom nil)]
     {:sci-ctx sci-ctx :initial-ns-keys initial-ns-keys :context context-data
      :llm-query-fn llm-query-fn :inject-atom inject-atom
      :locals-atom locals-atom :db-info-atom db-info-atom
      :router router})))

(defn dispose-rlm-env! [{:keys [db-info-atom]}]
  (when db-info-atom (dispose-rlm-conn! @db-info-atom)))

(defn get-locals [rlm-env] @(:locals-atom rlm-env))

;; =============================================================================
;; Code Execution
;; =============================================================================

(defn- sanitize-code
  "Fix common delimiter mismatches from LLM-generated code.
   1. Strips trailing unmatched } ] ) that cause parse errors.
   2. Appends missing closing delimiters in correct order.
   Handles whitespace between valid code and extra delimiters.
   Safe for multi-form code (def, if, do, defn etc.)."
  [code]
  (let [s (str/trim code)]
    (if (str/blank? s)
      ""
      (let [;; Phase 1: strip trailing unmatched closers
            stripped (loop [s s]
                       (let [trimmed (str/trimr s)
                             last-ch (when (pos? (count trimmed)) (nth trimmed (dec (count trimmed))))
                             opens  (frequencies (filter #{\( \[ \{} trimmed))
                             closes (frequencies (filter #{\) \] \}} trimmed))
                             extra-close (fn [o c] (- (get closes c 0) (get opens o 0)))]
                         (cond
                           (and (= last-ch \}) (pos? (extra-close \{ \})))
                           (recur (subs trimmed 0 (dec (count trimmed))))
                           (and (= last-ch \]) (pos? (extra-close \[ \])))
                           (recur (subs trimmed 0 (dec (count trimmed))))
                           (and (= last-ch \)) (pos? (extra-close \( \))))
                           (recur (subs trimmed 0 (dec (count trimmed))))
                           :else trimmed)))
            ;; Phase 2: add missing closers by tracking open/close stack
            closer-for {\( \) \[ \] \{ \}}
            skip-string (fn [chars]
                          ;; Consume chars until closing unescaped ", return remaining chars
                          (loop [cs chars escaped? false]
                            (if-not cs
                              nil ;; unclosed string
                              (let [ch (first cs)]
                                (cond
                                  escaped? (recur (next cs) false)
                                  (= ch \\) (recur (next cs) true)
                                  (= ch \") (next cs) ;; found closing quote, return rest
                                  :else (recur (next cs) false))))))
            missing (loop [chars (seq stripped) stack []]
                      (if-not chars
                        (apply str (map closer-for (reverse stack)))
                        (let [c (first chars)]
                          (cond
                            (#{\( \[ \{} c) (recur (next chars) (conj stack c))
                            (#{\) \] \}} c) (recur (next chars) (if (seq stack) (pop stack) stack))
                            (= c \") (recur (skip-string (next chars)) stack)
                            :else (recur (next chars) stack)))))]
        (str stripped missing)))))

(defn execute-code [{:keys [sci-ctx locals-atom]} code]
  (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-phase :execute-code})]
    (let [code (sanitize-code code)
          _ (rlm-debug! {:code-preview (str-truncate code 200)} "Executing code")
          start-time (System/currentTimeMillis)
          vars-before (try (sci/eval-string* sci-ctx "(ns-interns 'user)") (catch Exception _ {}))
          ;; Use future (not async/thread) so we can cancel on timeout
          stdout-writer (java.io.StringWriter.)
          stderr-writer (java.io.StringWriter.)
          exec-future (future
                        (try
                          (let [result (binding [*out* stdout-writer
                                                 *err* (java.io.PrintWriter. stderr-writer true)]
                                         (sci/eval-string* sci-ctx code))]
                            {:result result :stdout (str stdout-writer) :stderr (str stderr-writer) :error nil})
                          (catch Exception e {:result nil :stdout (str stdout-writer) :stderr (str stderr-writer) :error (ex-message e)})))
          execution-result (try
                             (deref exec-future EVAL_TIMEOUT_MS nil)
                             (catch Exception e
                               {:result nil :stdout "" :stderr "" :error (ex-message e)}))
          execution-time (- (System/currentTimeMillis) start-time)
          timed-out? (nil? execution-result)]
      (if timed-out?
        (do
          ;; Cancel the future — interrupts the thread
          (future-cancel exec-future)
          ;; Close writers
          (.close stdout-writer)
          (.close stderr-writer)
          (rlm-debug! {:execution-time-ms execution-time} "Code execution timed out")
          {:result nil :stdout "" :stderr "" :error (str "Timeout (" (/ EVAL_TIMEOUT_MS 1000) "s)")
           :execution-time-ms execution-time :timeout? true})
        (let [{:keys [result stdout stderr error]} execution-result
              _ (.close stdout-writer)
              _ (.close stderr-writer)
              vars-after (try (sci/eval-string* sci-ctx "(ns-interns 'user)") (catch Exception _ vars-before))
              new-vars (apply dissoc vars-after (keys vars-before))]
          (when (seq new-vars)
            (swap! locals-atom merge (into {} (map (fn [[k v]] [k (deref v)]) new-vars))))
          (rlm-debug! {:execution-time-ms execution-time
                       :has-error? (some? error)
                       :error error
                       :result-preview (str-truncate (pr-str result) 200)
                       :stdout-preview (when-not (str/blank? stdout) (str-truncate stdout 200))
                       :stderr-preview (when-not (str/blank? stderr) (str-truncate stderr 200))
                       :new-vars (when (seq new-vars) (vec (keys new-vars)))} "Code execution complete")
          {:result result :stdout stdout :stderr stderr :error error :execution-time-ms execution-time :timeout? false})))))

;; =============================================================================
;; FINAL Detection
;; =============================================================================

(defn check-result-for-final [result-map]
  (let [result (:result result-map)]
    (if (and (map? result) (true? (:rlm/final result)))
      (cond-> {:final? true
               :answer (:rlm/answer result)
               :confidence (or (:rlm/confidence result) :high)}
        (:rlm/sources result)   (assoc :sources (:rlm/sources result))
        (:rlm/reasoning result) (assoc :reasoning (:rlm/reasoning result)))
      {:final? false})))

(defn answer-str
  "Extracts a string representation from an RLM answer.
   Answer is {:result value :type type} — returns the :result as a string."
  [answer]
  (let [v (:result answer answer)]
    (if (string? v) v (pr-str v))))

;; =============================================================================
;; Code Extraction
;; =============================================================================

(defn extract-code-blocks [text]
  (let [pattern #"```(?:clojure|repl|clj)?\s*\n([\s\S]*?)\n```"
        matches (re-seq pattern text)]
    (->> matches (map second) (map str/trim) (remove str/blank?) vec)))

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

(defn build-system-prompt
  "Builds the system prompt. Optionally includes spec schema, history tools, and custom docs.

   When :has-reasoning? is true, uses ITERATION_SPEC_CODE_ONLY (no thinking field)
   because the provider emits native reasoning tokens — no need to duplicate in JSON."
  [{:keys [output-spec custom-docs has-documents?
           has-reasoning? system-prompt]}]
  (str "<rlm_environment>
<role>You are an expert Clojure programmer analyzing data in a sandboxed environment.</role>
"
    (when system-prompt
      (str "\n<agent_instructions>\n" system-prompt "\n</agent_instructions>\n"))
    "

<available_tools>
  <tool name=\"context\">The data context - access as 'context' variable</tool>
  <tool name=\"llm-query\">(llm-query prompt) or (llm-query prompt {:spec my-spec}) - Simple text query to LLM</tool>
  <tool name=\"llm-query-batch\">(llm-query-batch [prompt1 prompt2 ...]) - Parallel batch of LLM sub-calls. Returns vector of results. Use for map-reduce patterns over many chunks.</tool>
  <tool name=\"request-more-iterations\">(request-more-iterations n) - Request n more iterations if running low. Returns {:granted n :new-budget N :cap max}.</tool>
</available_tools>
"
    (when has-documents? "
<document_tools>
  <description>Query ingested PageIndex documents. Documents contain metadata, pages with content, and table of contents.</description>
  <tool name=\"list-documents\">(list-documents) or (list-documents {:limit n :include-toc? true}) - List documents with abstracts and TOC. Returns:
    [{:document/id \"...\"
      :document/name \"filename\"
      :document/title \"Document Title\"
      :document/abstract \"Summary of the document...\"
      :document/extension \"pdf\"
      :document/toc [{:title \"Chapter 1\" :level \"l1\" :page 0}
                     {:title \"Section 1.1\" :level \"l2\" :page 3}
                     ...]}]</tool>
  <tool name=\"get-document\">(get-document doc-id) - Get document with abstract and full TOC.</tool>
  <tool name=\"search-document-pages\">(search-document-pages query) or (search-document-pages query top-k) or (search-document-pages query top-k {:document-id id :type :paragraph}) - Fulltext search across document pages. Returns BRIEF metadata: [{:page.node/id :page.node/type :page.node/page-id :page.node/document-id :preview \"first 150 chars...\" :content-length N}...]. Use P-add! to fetch full content.</tool>
  <tool name=\"P-add!\">(P-add! [:page.node/id id]) or (P-add! [:document/id id]) or (P-add! [:document.toc/id id]) - Fetches content using Datalevin lookup ref.
    :page.node/id → content string. Store: (def clause (P-add! [:page.node/id \"abc\"]))
    :document/id → vector of ~4000 char page strings (chunked). Store: (def doc (P-add! [:document/id \"doc-1\"])). Access: (count doc), (nth doc 5), (mapv #(re-seq #\"pattern\" %) doc)
    :document.toc/id → TOC title/description string.</tool>
  <tool name=\"list-document-pages\">(list-document-pages) or (list-document-pages {:page-id id :document-id id :type :heading :limit n}) - List document page nodes with brief metadata.</tool>
  <tool name=\"search-document-toc\">(search-document-toc query) or (search-document-toc query top-k) - Search table of contents by title/description (case-insensitive). Returns [{:document.toc/id :document.toc/title :document.toc/description :document.toc/level :document.toc/target-page}...].</tool>
  <tool name=\"get-document-toc-entry\">(get-document-toc-entry entry-id) - Get full TOC entry by ID.</tool>
  <tool name=\"list-document-toc\">(list-document-toc) or (list-document-toc {:parent-id id :limit n}) - List TOC entries.</tool>
  <tool name=\"store-document-toc!\">(store-document-toc! entry) - Store a PageIndex TOC entry. Returns stored entry.</tool>
  <document_page_schema>
    :page.node/id - String (UUID), unique identifier
    :page.node/type - Keyword: :section :heading :paragraph :list-item :image :table :header :footer :metadata
    :page.node/level - String, e.g. \"h1\"-\"h6\" for headings, \"l1\"-\"l6\" for lists
    :page.node/content - String, the actual text content
    :page.node/description - String, AI-generated description for sections/images/tables
    :page.node/page-id - String, reference to parent page
    :page.node/document-id - String, reference to parent document
  </document_page_schema>
  <document_toc_schema>
    :document.toc/id - String (UUID), unique identifier
    :document.toc/title - String, section title
    :document.toc/description - String or nil, section summary
    :document.toc/target-page - Long, page number (0-based)
    :document.toc/level - String, hierarchy level (\"l1\", \"l2\", \"l3\")
    :document.toc/parent-id - String or nil, parent entry ID
  </document_toc_schema>
  <usage_tips>
    - START HERE: Call (list-documents) to see available documents with abstracts and TOC
    - Use (search-document-pages \"penalty\") to find relevant nodes (returns brief metadata + preview)
    - Use (P-add! [:page.node/id id]) to fetch full content: (def clause (P-add! [:page.node/id \"abc-123\"]))
    - Use (P-add! [:document/id id]) to fetch entire document as text
    - Use (list-document-toc) to understand document structure
    - Filter by :type to find specific content (e.g., :paragraph for text, :heading for titles)
    - Filter by :document-id to search within a specific document
  </usage_tips>
</document_tools>

<document_entity_tools>
  <description>Search, retrieve, and analyze entities extracted from documents: parties, obligations, conditions, terms, clauses, cross-references.</description>
  <tool name=\"search-document-entities\">(search-document-entities query) or (search-document-entities query top-k) or (search-document-entities query top-k {:type :party :document-id \"...\"}) - Search entities by name/description (case-insensitive). Returns [{:entity/id :entity/name :entity/type :entity/description :entity/document-id :entity/page :entity/section}...].</tool>
  <tool name=\"get-document-entity\">(get-document-entity entity-id) - Get full entity by UUID.</tool>
  <tool name=\"list-document-entities\">(list-document-entities) or (list-document-entities {:type :party :document-id \"...\" :limit 50}) - List entities with filters.</tool>
  <tool name=\"list-document-relationships\">(list-document-relationships entity-id) or (list-document-relationships entity-id {:type :references}) - List relationships for an entity (both directions).</tool>
  <tool name=\"document-entity-stats\">(document-entity-stats) - Entity statistics: {:total-entities N :types {:party N :obligation N ...} :total-relationships N}</tool>
  <entity_schema>
    :entity/id - UUID, unique identifier
    :entity/name - String, entity name or label
    :entity/type - Keyword: :party, :obligation, :condition, :term, :clause, :cross-reference
    :entity/description - String, extracted context/description
    :entity/document-id - String, source document reference
    :entity/page - Long, source page number
    :entity/section - String, source section identifier
  </entity_schema>
  <relationship_schema>
    :relationship/id - UUID, unique identifier
    :relationship/type - Keyword: :references, :defines, :obligates, :conditions, :amends
    :relationship/source-entity-id - UUID, source entity
    :relationship/target-entity-id - UUID, target entity
    :relationship/description - String, relationship context
  </relationship_schema>
  <usage_tips>
    - START with (document-entity-stats) to see what entities are available
    - Use (search-document-entities \"party name\") to find specific entities
    - Use (list-document-relationships entity-id) to explore connections
    - Entities give STRUCTURE, document pages give CONTENT — use both together
  </usage_tips>
</document_entity_tools>
")
    "
<helpers>
  Date: parse-date, date-before?, date-after?, days-between, date-plus-days, date-minus-days, date-format, today-str (ISO-8601 format)
  Sets: set-union, set-intersection, set-difference, set-subset?, set-superset?
</helpers>
"
       ;; No history tools in system prompt — locals are injected in every iteration feedback.
       ;; The model always sees its variables and doesn't need to waste iterations on get-history.
    "
<string_helpers>str-lines, str-words, str-truncate, str-join, str-split, str-replace, str-trim, str-lower, str-upper, str-blank?, str-includes?, str-starts-with?, str-ends-with?</string_helpers>

<regex>re-pattern, re-find, re-matches, re-seq</regex>

<safe_functions>map, filter, reduce, assoc, get, keys, vals, first, rest, take, drop, sort, group-by, frequencies, +, -, *, /, etc.</safe_functions>
"
       ;; Include custom docs if provided
    (format-custom-docs custom-docs)

       ;; Include spec schema if provided
    (when output-spec
      (str "\n<expected_output_schema>\n"
        "Your FINAL answer should match this structure:\n"
        (spec/spec->prompt output-spec)
        "\n</expected_output_schema>\n"))

    "
<rlm_patterns>
  Aggregation: partition pages → llm-query-batch → synthesize. Use for counting/summarizing ALL content.
  Retrieval: search-document-pages → P-add! → def result. Use for finding SPECIFIC info.
  Regex: (re-seq #\"pattern\" my-var) for structured extraction across stored text.
</rlm_patterns>

<workflow>
0. FIRST: Check <context> and <var_index> - if they already answer the query, set 'final' immediately
1. If more info needed, check available documents: (list-documents)
2. Browse TOC to understand document structure: (list-document-toc)
3. Pick sections from TOC, fetch content: (def section \"doc for section\" (P-add! [:page.node/id node-id]))
4. For exhaustive analysis: use llm-query-batch over chunks
5. Check entities if relevant: (document-entity-stats), then (list-document-entities {:type :party})
6. Store intermediate results with (def my-var \"docstring\" value) — use docstrings!
7. List vars to carry in 'carry' field for next iteration
8. Set 'final' field when done
</workflow>

<response_format>
" (spec/spec->prompt (if has-reasoning? ITERATION_SPEC_CODE_ONLY ITERATION_SPEC)) "
" (if has-reasoning?
    "EVERY response MUST be valid JSON with a 'code' field. Your reasoning happens natively — do NOT include a 'thinking' field. No markdown, no prose outside JSON."
    "EVERY response MUST be valid JSON with 'thinking' and 'code' fields. No markdown, no prose outside JSON.") "
  The optional 'carry' field lists var names whose FULL VALUES you need in the next iteration.
  Vars not in 'carry' appear only in the <var_index> (name/type/size/doc — no value).
  The optional 'final' field terminates the loop. When non-null, 'code' is IGNORED.
  final format: {\"answer\": \"the answer\", \"confidence\": \"high|medium|low\", \"summary\": \"one-line for var index\"}
  Example continue: {\"thinking\": \"...\", \"code\": [\"...\"], \"carry\": [\"clause\"]}
  Example finalize: {\"thinking\": \"...\", \"code\": [], \"carry\": [], \"final\": {\"answer\": \"The penalty is 5%.\", \"confidence\": \"high\", \"summary\": \"Penalty clause analysis\"}}
</response_format>

<critical>
- SINGLE-SHOT: Each iteration is a fresh prompt. No message history accumulates. State lives in your def'd vars.
- VARS ARE STATE: Use (def var-name \"docstring\" value) to store results. Vars persist across iterations in the SCI sandbox.
- DOCSTRINGS: ALWAYS use docstrings on def — they appear in the <var_index> and help you remember what each var contains.
- VAR INDEX: Every iteration shows a <var_index> table of ALL your def'd vars (name, type, size, docstring).
- CARRY: List var names in 'carry' to get their FULL values next iteration. Uncarried vars appear only in the index (name/type/size/doc).
- EXECUTION RESULTS: After each iteration, <execution_results> shows success/failure per code block.
- EXECUTION JOURNAL: The <execution_journal> shows your thinking + var names from previous iterations. Squashed every 5 iterations.
- CONVERSATION: The <conversation> section links previous queries to their final-result-N vars.
- FINALIZE: Set the 'final' field in your response when done. Code is IGNORED when final is set.
- FAST PATH: If context or var_index already answers the query, set 'final' IMMEDIATELY.
- NEVER REPEAT: If a call returned [] or nil, do NOT call it again. Try a different approach or finalize.
- CLOJURE SYNTAX: ALL function calls MUST be wrapped in parentheses.
- VARS ARE VALUES: Use `my-var` to reference a stored value, NOT `(my-var)`.
- COMBINE STEPS: Code blocks execute sequentially in ONE iteration. Do NOT split read+answer into separate iterations.
- ITERATION BUDGET: " MAX_ITERATIONS " iterations. Hard cap: " MAX_ITERATION_CAP ". " (/ EVAL_TIMEOUT_MS 1000) "s timeout per execution.
</critical>
"
    "
</rlm_environment>"))

;; =============================================================================
;; Iteration Loop
;; =============================================================================

(defn run-iteration
  "Runs a single RLM iteration: LLM call → parse → execute code → check for FINAL.

   Params:
   `rlm-env` - RLM environment map.
   `messages` - Vector of message maps for the LLM.
   `opts` - Map, optional:
     - :iteration-spec - Spec to use for parsing (default: ITERATION_SPEC).
                         When provider has reasoning, pass ITERATION_SPEC_CODE_ONLY."
  [rlm-env messages & [{:keys [iteration-spec on-chunk] :or {iteration-spec ITERATION_SPEC}}]]
  (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-phase :run-iteration})]
    (let [_ (rlm-debug! {:msg-count (count messages)} "LLM call started")
          response-data (llm/routed-chat-completion (:router rlm-env) messages
                          (cond-> {:strategy :root}
                            on-chunk (assoc :on-chunk on-chunk)))
          response (:content response-data)
          model-reasoning (:reasoning response-data)
          _ (rlm-debug! {:response-len (count response)
                         :has-reasoning (some? model-reasoning)
                         :routed-model (:routed/model response-data)
                         :routed-provider (:routed/provider-id response-data)
                         :response-preview (str-truncate response 300)} "LLM response received")
          ;; Parse structured response via spec (primary), fall back to code fences
          ;; When provider has reasoning, iteration-spec is ITERATION_SPEC_CODE_ONLY (no :thinking field)
          parsed (try (let [p (spec/str->data-with-spec response iteration-spec)]
                        (rlm-debug! {:spec (if (= iteration-spec ITERATION_SPEC_CODE_ONLY) :code-only :full)}
                          "Response parsed via iteration spec (structured)")
                        p)
                      (catch Exception e
                        ;; Fallback: extract code from markdown fences
                        (rlm-debug! {:parse-error (ex-message e)} "Spec parse failed, falling back to markdown extraction")
                        {:thinking response :code (extract-code-blocks response)}))
          ;; Native reasoning always takes priority over spec-parsed thinking
          thinking (or model-reasoning (:thinking parsed))
          carry (vec (remove str/blank? (or (:carry parsed) [])))
          ;; Check for final field in response — if present, skip code execution
          response-final (when-let [f (:final parsed)]
                           (when (map? f)
                             {:final? true
                              :answer {:result (str (:answer f)) :type String}
                              :confidence (keyword (or (:confidence f) "high"))
                              :summary (:summary f)}))]
      ;; Early return if final field is set — code is ignored
      (if response-final
        (do (rlm-debug! {:final-answer (str-truncate (str (:answer response-final)) 200)} "Final field detected in response")
            {:response (when-not model-reasoning response)
             :thinking thinking :carry carry :executions [] :final-result response-final
             :api-usage (:api-usage response-data)})
        ;; Normal path: execute code blocks
        (let [code-blocks (vec (remove str/blank? (or (:code parsed) [])))
          ;; Pre-validate: skip blocks that aren't valid Clojure (e.g. prose the LLM
          ;; accidentally put in the :code array). Record them as errors without executing.
          ;; Also detect "prose dump" pattern: if >50% of blocks fail read or are bare
          ;; string literals, auto-wrap them into a FINAL answer.
              validated (mapv (fn [code]
                                (try
                                  (let [sanitized (sanitize-code code)
                                        form (read-string (str "(do " sanitized ")"))
                                   ;; Detect bare string literals — not real code
                                        bare-string? (and (seq? form) (= 2 (count form))
                                                       (string? (second form)))]
                                    {:code sanitized :valid? (not bare-string?)
                                     :bare-string? bare-string?})
                                  (catch Exception e
                                    {:code code :valid? false :error (ex-message e)})))
                          code-blocks)
          ;; Detect prose dump: majority of blocks are invalid or bare strings
              invalid-count (count (remove :valid? validated))
              prose-dump? (and (> (count validated) 3)
                            (> invalid-count (* 0.5 (count validated))))
          ;; If prose dump detected, return as final directly (no code execution)
              _ (when prose-dump?
                  (rlm-debug! {:prose-blocks invalid-count :total (count validated)}
                    "Prose dump detected — auto-wrapping as final"))]
          (if prose-dump?
            (let [prose (->> validated
                          (map (fn [{:keys [code bare-string?]}]
                                 (if bare-string?
                                   (try (read-string code) (catch Exception _ code))
                                   code)))
                          (str/join "\n"))]
              {:response (when-not model-reasoning response)
               :thinking thinking :carry [] :executions []
               :final-result {:final? true :answer {:result prose :type String} :confidence :medium}
               :api-usage (:api-usage response-data)})
            (let [{code-blocks :code-blocks validated :validated}
                  {:code-blocks (mapv :code validated)
                   :validated validated}
                  _ (rlm-debug! {:code-block-count (count code-blocks)
                                 :valid-count (count (filter :valid? validated))
                                 :prose-dump? prose-dump?
                                 :code-previews (mapv #(str-truncate (:code %) 120) validated)} "Code blocks extracted")
                  execution-results (mapv (fn [{:keys [code valid? error]}]
                                            (if valid?
                                              (execute-code rlm-env code)
                                              {:result nil :stdout "" :stderr "" :error error
                                               :execution-time-ms 0 :timeout? false}))
                                      validated)
          ;; Combine code blocks with their execution results
                  raw-executions (mapv (fn [idx {:keys [code]} result]
                                         {:id idx
                                          :code code
                                          :result (:result result)
                                          :stdout (:stdout result)
                                          :stderr (:stderr result)
                                          :error (:error result)
                                          :execution-time-ms (:execution-time-ms result)})
                                   (range)
                                   validated
                                   execution-results)
          ;; Results stay inline — context budget handles size naturally.
          ;; No auto-store: the model sees full results directly.
                  executions raw-executions
                  final-result (some #(let [check (check-result-for-final %)] (when (:final? check) check)) execution-results)]
              {;; When native reasoning is present, the raw response is just a redundant JSON wrapper
       ;; around thinking+code. Use nil to keep the trace clean — thinking is in :thinking.
               :response (when-not model-reasoning response)
               :thinking thinking :carry carry :executions executions :final-result final-result
               :api-usage (:api-usage response-data)})))))))

(defn format-executions
  "Formats executions for LLM feedback as EDN.
   All results shown inline — context budget handles size naturally."
  [executions]
  (str/join "\n"
    (map (fn [{:keys [code error result stdout]}]
           (let [code-str (str/trim (or code ""))
                 val-part (cond
                            error
                            (str ":error " (pr-str error))

                            (fn? result)
                            (str ":error \"" code-str " is a function object. Call it: (" code-str ")\"")

                            :else
                            (str ":ok " (pr-str (realize-value result))))

                 stdout-part (when-not (str/blank? stdout)
                               (str " :stdout " (pr-str stdout)))]
             (str "{" code-str " → " val-part (or stdout-part "") "}")))
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

(defn- build-carried-vars
  "Serializes full values of carried vars from SCI context into XML section.
   Looks up each var name in SCI and renders its pr-str value."
  [sci-ctx carry-list]
  (when (seq carry-list)
    (let [entries (keep
                    (fn [var-name]
                      (try
                        (let [val (sci/eval-string* sci-ctx (str var-name))]
                          (str "  " var-name " = " (pr-str (realize-value val))))
                        (catch Exception _
                          (str "  " var-name " = <not found>"))))
                    carry-list)]
      (when (seq entries)
        (str "<carried_vars>\n"
          (str/join "\n" entries)
          "\n</carried_vars>")))))

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
  "Extracts var names from code blocks by scanning for (def ...) patterns."
  [executions]
  (->> executions
    (keep (fn [{:keys [code error]}]
            (when-not error
              (second (re-find #"\(def\s+(\S+)" (or code ""))))))
    vec))

(defn- rehydrate-final-results!
  "Injects previous final-result-N vars into SCI context from Datalevin.
   Each var gets the summary as its docstring so it appears in var index.
   Returns the list of final results for conversation thread rendering."
  [sci-ctx db-info-atom]
  (when db-info-atom
    (let [results (db-list-final-results @db-info-atom)]
      (doseq [{:keys [final-result/index final-result/answer
                      final-result/confidence final-result/summary]} results]
        (let [var-name (str "final-result-" index)
              doc-str (or summary "Previous query result")
              val-map {:answer answer :confidence confidence}]
          (try
            (sci/eval-string* sci-ctx
              (str "(def ^{:doc " (pr-str doc-str) "} " var-name " " (pr-str val-map) ")"))
            (catch Exception _ nil))))
      results)))

(defn- render-conversation-thread
  "Renders compact conversation thread XML from previous final results and current query."
  [final-results current-query]
  (let [prev-entries (map (fn [{:keys [final-result/index final-result/query]}]
                            (str "  [" index "] " (pr-str (str-truncate (or query "") 100))
                              " \u2192 final-result-" index))
                       final-results)
        current (str "  [current] " (pr-str (str-truncate current-query 100)))]
    (str "<conversation>\n"
      (when (seq prev-entries)
        (str (str/join "\n" prev-entries) "\n"))
      current
      "\n</conversation>")))

(defn iteration-loop [rlm-env query
                      {:keys [output-spec max-context-tokens custom-docs system-prompt
                              pre-fetched-context on-chunk
                              max-iterations max-consecutive-errors max-restarts]}]
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
                             (long (* 0.6 (defaults/context-limit effective-model))))
        ;; Check if root provider has native reasoning (thinking tokens)
        has-reasoning? (boolean (provider-has-reasoning? (:router rlm-env)))
        has-docs? (when-let [db-atom (:db-info-atom rlm-env)]
                    (when-let [db @db-atom]
                      (pos? (count (db-list-documents db {:limit 1 :include-toc? false})))))
        system-prompt (build-system-prompt {:output-spec output-spec
                                            :custom-docs custom-docs
                                            :has-documents? has-docs?
                                            :has-reasoning? has-reasoning?
                                            :system-prompt system-prompt
                                            :max-context-tokens max-context-tokens})
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
        initial-messages [{:role "system" :content system-prompt}
                          {:role "user" :content initial-user-content}]
        ;; Store initial messages if history tracking is enabled
        db-info (when-let [atom (:db-info-atom rlm-env)] @atom)
        env-id (:env-id rlm-env)
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
                                  "You MUST try a DIFFERENT approach, or call (FINAL {:answer [\"your answer\"]}) with what you have."))))
        finalize-cost (fn []
                        (let [{:keys [input-tokens output-tokens reasoning-tokens cached-tokens]} @usage-atom
                              total-tokens (+ input-tokens output-tokens)
                              cost (defaults/estimate-cost effective-model input-tokens output-tokens)]
                          {:tokens {:input input-tokens :output output-tokens
                                    :reasoning reasoning-tokens :cached cached-tokens
                                    :total total-tokens}
                           :cost cost}))
         ;; Rehydrate previous final-result-N vars into SCI context
        prev-final-results (rehydrate-final-results! (:sci-ctx rlm-env) (:db-info-atom rlm-env))
        conversation-thread (render-conversation-thread prev-final-results query)]
    (rlm-debug! {:query query :max-iterations max-iterations :model effective-model
                 :has-output-spec? (some? output-spec) :has-pre-fetched? (some? pre-fetched-context)
                 :has-reasoning? has-reasoning?
                 :prev-final-results (count prev-final-results)
                 :msg-count (count initial-messages)} "Iteration loop started")
    (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-phase :iteration-loop})]
      (loop [iteration 0 messages initial-messages trace [] consecutive-errors 0 restarts 0
             prev-carry [] prev-executions nil prev-iteration -1
             journal []]
        (if (>= iteration (effective-max-iterations))
          (let [locals (get-locals rlm-env)
                useful-value (some->> locals vals (filter #(and (some? %) (not (fn? %)))) last)]
            (trove/log! {:level :warn :data {:iteration iteration} :msg "Max iterations reached"})
            (merge {:answer (if useful-value (pr-str useful-value) nil)
                    :status :max-iterations
                    :locals locals
                    :trace trace
                    :iterations iteration}
              (finalize-cost)))
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
                  [] nil -1 journal))
              (do (trove/log! {:level :warn :data {:iteration iteration :consecutive-errors consecutive-errors
                                                   :restarts restarts} :msg "Error budget exhausted after restart"})
                  (merge {:answer nil :status :error-budget-exhausted :trace trace :iterations iteration}
                    (finalize-cost))))
            (let [_ (rlm-debug! {:iteration iteration :msg-count (count messages)} "Iteration start")
                  ;; Build single-shot prompt: conversation + journal + execution results + carried vars + var index
                  var-index-str (build-var-index (:sci-ctx rlm-env) (:initial-ns-keys rlm-env))
                  carried-vars-str (build-carried-vars (:sci-ctx rlm-env) prev-carry)
                  exec-results-str (format-execution-results prev-executions prev-iteration)
                  journal-str (render-execution-journal journal)
                  iteration-context (str
                                      conversation-thread
                                      (when journal-str (str "\n" journal-str))
                                      (when exec-results-str (str "\n" exec-results-str))
                                      (when carried-vars-str (str "\n" carried-vars-str))
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
                                                 :final nil}))))
                  iteration-result (try
                                     (run-iteration rlm-env effective-messages
                                       (cond-> {:iteration-spec (if has-reasoning?
                                                                  ITERATION_SPEC_CODE_ONLY
                                                                  ITERATION_SPEC)}
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
                                       "The previous attempt failed. Adjust your approach or call (FINAL {:answer [\"your answer\"]}) with what you have.")
                      trace-entry {:iteration iteration :error iter-err :final? false}]
                  (recur (inc iteration)
                    (conj messages {:role "user" :content error-feedback})
                    (conj trace trace-entry)
                    (inc consecutive-errors)
                    restarts
                    [] nil -1 journal))
                ;; Normal path — accumulate token usage
                (let [_ (accumulate-usage! (:api-usage iteration-result))
                      {:keys [response thinking carry executions final-result]} iteration-result
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
                                             :status :success}}))
                        ;; Persist final result to Datalevin for cross-session access
                        (db-store-final-result! db-info
                          {:answer (answer-str (:answer final-result))
                           :confidence (:confidence final-result)
                           :summary (:summary final-result)}
                          query env-id)
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
                                      "Respond: {\"code\": [\"(FINAL {:answer [\\\"your answer\\\"]})\"]} "
                                      "Respond: {\"thinking\": \"...\", \"code\": [\"(FINAL {:answer [\\\"your answer\\\"]})\"]} "))]
                        (recur (inc iteration) ;; still increment to prevent infinite loop
                          (conj messages
                            {:role "assistant" :content (or response thinking "[empty]")}
                            {:role "user" :content nudge})
                          trace ;; DON'T add empty trace entry
                          (inc consecutive-errors)
                          restarts
                          [] nil -1 journal))
                      ;; Normal iteration with executions
                      (let [exec-feedback (format-executions executions)
                            iteration-header (str "[Iteration " (inc iteration) "/" (effective-max-iterations) "]\n"
                                               "{:requirement " (pr-str (str-truncate query 200)) "}")
                            repetition-warning (detect-repetition executions)
                            remaining-iters (- (effective-max-iterations) (inc iteration))
                            budget-warning (when (<= remaining-iters 5)
                                             (str "\n[SYSTEM_NUDGE] Only " remaining-iters " iterations left! "
                                               "Call (FINAL {:answer [\"your findings\"]}) NOW with what you have. DO NOT start new explorations."))
                            force-final-nudge (when (> iteration 20)
                                                (str "\n[SYSTEM_NUDGE] You have been running for " (inc iteration) " iterations. "
                                                  "STOP exploring. Call (FINAL {:answer [...]}) IMMEDIATELY with your current findings."))
                            user-feedback (str iteration-header "\n" exec-feedback repetition-warning budget-warning force-final-nudge)]
                        (rlm-debug! {:iteration iteration
                                     :code-blocks (count executions)
                                     :errors (count (filter :error executions))
                                     :has-thinking? (some? thinking)
                                     :thinking-preview (when thinking (str-truncate thinking 150))
                                     :feedback-len (count user-feedback)} "Iteration feedback")
                        (let [had-successful-execution? (some #(nil? (:error %)) executions)
                              next-errors (if had-successful-execution? 0 (inc consecutive-errors))
                              journal-entry {:iteration iteration
                                             :thinking thinking
                                             :var-names (extract-def-names executions)}]
                          (recur (inc iteration)
                            messages
                            (conj trace trace-entry)
                            next-errors
                            restarts
                            (or carry []) executions iteration
                            (conj journal journal-entry)))))))))))))))

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
                                         :prefer :cost :capabilities #{:chat}})]
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
                                             :prefer :cost :capabilities #{:chat :vision}})]
          (or (:result response) {:entities [] :relationships []}))
        ;; Has description only - text extraction
        description
        (let [response (llm/ask! rlm-router {:spec ENTITY_EXTRACTION_SPEC
                                             :messages [(llm/system ENTITY_EXTRACTION_OBJECTIVE)
                                                        (llm/user description)]
                                             :prefer :cost :capabilities #{:chat}})]
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
                (catch Exception _ (swap! errors-atom inc))))))
        ;; Extract from visual nodes (capped)
        (doseq [vnode visual-nodes]
          (when (< @vision-count-atom max-vision)
            (try
              (let [result (extract-entities-from-visual-node! vnode rlm-router)]
                (swap! vision-count-atom inc)
                (swap! entities-atom into (:entities result))
                (swap! relationships-atom into (:relationships result)))
              (catch Exception _ (swap! errors-atom inc)))))))
    ;; Store entities and relationships in DB (two-phase)
    (let [entities @entities-atom
          relationships @relationships-atom
          conn (:conn db-info)
          name->uuid (atom {})]
      ;; Phase 1: Store entities, build name→UUID map
      (doseq [entity entities]
        (try
          (let [entity-id (util/uuid)
                entity-name (or (:entity/name entity) (:name entity) "unknown")
                entity-data (cond-> {:entity/id entity-id
                                     :entity/name entity-name
                                     :entity/type (or (:entity/type entity) (:type entity) :unknown)
                                     :entity/description (or (:entity/description entity) (:description entity) "")
                                     :entity/document-id (str doc-id)
                                     :entity/created-at (java.util.Date.)}
                              (or (:entity/section entity) (:section entity))
                              (assoc :entity/section (or (:entity/section entity) (:section entity)))
                              (or (:entity/page entity) (:page entity))
                              (assoc :entity/page (long (or (:entity/page entity) (:page entity)))))]
            (d/transact! conn [entity-data])
            (swap! name->uuid assoc (str/lower-case entity-name) entity-id))
          (catch Exception e
            (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Failed to store entity"}))))
      ;; Phase 2: Resolve entity names to UUIDs and store relationships
      (doseq [rel relationships]
        (try
          (let [src-name (or (:relationship/source-entity-id rel) (:source rel))
                tgt-name (or (:relationship/target-entity-id rel) (:target rel))
                src-id (get @name->uuid (some-> src-name str str/lower-case))
                tgt-id (get @name->uuid (some-> tgt-name str str/lower-case))]
            (when (and src-id tgt-id)
              (d/transact! conn [{:relationship/id (util/uuid)
                                  :relationship/type (or (:relationship/type rel) (:type rel) :unknown)
                                  :relationship/source-entity-id src-id
                                  :relationship/target-entity-id tgt-id
                                  :relationship/description (or (:relationship/description rel) (:description rel) "")
                                  :relationship/document-id (str doc-id)}])))
          (catch Exception e
            (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Failed to store relationship"}))))
      {:entities-extracted (count entities)
       :relationships-extracted (count relationships)
       :pages-processed (count pages)
       :extraction-errors @errors-atom
       :visual-nodes-scanned @vision-count-atom})))

;; =============================================================================
;; Public API - Component-Based Architecture
;; =============================================================================
