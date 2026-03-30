(ns com.blockether.svar.internal.rlm.core
  (:require
   [clojure.string :as str]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.providers :as providers]
   [com.blockether.svar.internal.rlm.db
    :refer [create-rlm-conn dispose-rlm-conn! store-message! store-executions!
            get-recent-messages db-list-documents
            db-store-pageindex-document! db-store-learning! db-vote-learning! str-truncate]]
   [com.blockether.svar.internal.rlm.routing
    :refer [make-routed-llm-query-fn resolve-root-model provider-has-reasoning?]]
   [com.blockether.svar.internal.rlm.schema
    :refer [ENTITY_EXTRACTION_SPEC ENTITY_EXTRACTION_OBJECTIVE
            ITERATION_SPEC ITERATION_SPEC_CODE_ONLY
            LEARNING_VOTE_SPEC AUTOLEARN_SPEC AUTOLEARN_ITERATION_THRESHOLD
            INLINE_RESULT_THRESHOLD EVAL_TIMEOUT_MS
            MAX_ITERATIONS MAX_ITERATION_CAP
            bytes->base64 *max-recursion-depth* *rlm-ctx*]]
   [com.blockether.svar.internal.rlm.tools :refer [create-sci-context realize-value]]
   [com.blockether.svar.internal.spec :as spec]
   [com.blockether.svar.internal.tokens :as tokens]
   [com.blockether.svar.internal.util :as util]
   [datalevin.core :as d]
   [sci.core :as sci]
   [taoensso.trove :as trove]))

(declare make-rlm-query-fn run-sub-rlm
  build-system-prompt run-iteration auto-store-results! format-executions)

(defn rlm-debug!
  "Logs at :info level only when :rlm-debug? is true in *rlm-ctx*.
   Includes :rlm-phase from context automatically in data."
  [data msg]
  (when (:rlm-debug? *rlm-ctx*)
    (trove/log! {:level :info :data (assoc data :rlm-phase (:rlm-phase *rlm-ctx*)) :msg msg})))

(defn select-rlm-iteration-context
  "Selects messages for an RLM iteration using recency-based selection.
   
   Optimized for RLM iteration loops:
   1. ALWAYS preserves system prompt (first message)
   2. ALWAYS preserves most recent messages (for conversation continuity)
   3. Uses recency to select MIDDLE messages within budget
    
    This ensures the LLM maintains coherent conversation flow while having
    access to recent past exploration.
   
   Params:
   `db-info` - Map with :store key (or nil)
   `current-messages` - Vector. Current messages array from iteration loop
   `max-tokens` - Integer. Token budget
   `opts` - Map, optional:
     - :model - Model for token counting (default: gpt-4o)
     - :preserve-recent - Number of recent messages to always keep (default: 4)"
  ([db-info current-messages max-tokens]
   (select-rlm-iteration-context db-info current-messages max-tokens {}))
  ([db-info current-messages max-tokens
    {:keys [model preserve-recent] :or {preserve-recent 4}}]
   (assert model "model is required for select-rlm-iteration-context (resolve from router)")
   (let [;; If no DB or not enough messages, just truncate normally
         msg-count (count current-messages)]
     (if (or (nil? db-info) (nil? (:conn db-info)) (<= msg-count (inc preserve-recent)))
       (tokens/truncate-messages model current-messages max-tokens)

       ;; Recency-based selection
       (let [;; Always keep system prompt (first message)
             system-msg (first current-messages)
             system-tokens (tokens/count-messages model [system-msg])

             ;; Always keep most recent N messages for continuity
             recent-msgs (vec (take-last preserve-recent (rest current-messages)))
             recent-tokens (tokens/count-messages model recent-msgs)

             ;; Calculate budget for middle messages
             fixed-overhead (+ system-tokens recent-tokens 100) ; 100 token buffer
             history-budget (- max-tokens fixed-overhead)]

         (if (<= history-budget 0)
           ;; No room for history - just use system + recent
           (vec (concat [system-msg] recent-msgs))

           ;; Select recent past messages within budget
           (let [;; Middle messages (not system, not recent)
                 middle-count (- msg-count 1 preserve-recent)

                 ;; Get recent messages (recency-based selection)
                 similar (when (pos? middle-count)
                           (let [msgs (get-recent-messages db-info (min 20 (* 2 middle-count)))]
                             (->> (or msgs [])
                               (remove #(= :system (:role %)))
                               vec)))

                 ;; Select within budget, sorted by recency (most recent first)
                 selected-middle (loop [candidates (or similar [])
                                        selected []
                                        used-tokens 0]
                                   (if (or (empty? candidates)
                                         (>= used-tokens history-budget))
                                     selected
                                     (let [msg (first candidates)
                                           msg-tokens (:tokens msg 50)] ; Default 50 if missing
                                       (if (<= (+ used-tokens msg-tokens) history-budget)
                                         (recur (rest candidates)
                                           (conj selected {:role (name (:role msg))
                                                           :content (:content msg)})
                                           (+ used-tokens msg-tokens))
                                         ;; Skip this one, try next
                                         (recur (rest candidates) selected used-tokens)))))

                 ;; Reverse to get chronological order (oldest first)
                 ;; since messages are sorted by recency
                 ordered-middle (vec (reverse selected-middle))]

             ;; Combine: system + selected history + recent
             (vec (concat [system-msg] ordered-middle recent-msgs)))))))))

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
   Map with :sci-ctx, :context, :llm-query-fn, :rlm-query-fn, :locals-atom,
   :db-info-atom, :history-enabled?, :router."
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
         rlm-query-fn (when db-info-atom
                        (make-rlm-query-fn {:strategy :root} depth-atom router db-info-atom))
         {:keys [sci-ctx p-atom]} (create-sci-context context-data llm-query-fn rlm-query-fn locals-atom db-info-atom nil)]
     {:sci-ctx sci-ctx :p-atom p-atom :context context-data
      :llm-query-fn llm-query-fn :rlm-query-fn rlm-query-fn
      :locals-atom locals-atom :db-info-atom db-info-atom
      :history-enabled? (boolean db-info-atom) :router router})))

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

(defn execute-code [{:keys [sci-ctx locals-atom hooks-atom]} code]
  (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-phase :execute-code})]
    (when-let [call-hook (resolve 'com.blockether.svar.internal.rlm/call-hook!)]
      (call-hook hooks-atom :code-exec :pre {:code code}))
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
          (let [result-map {:result result :stdout stdout :stderr stderr :error error :execution-time-ms execution-time :timeout? false}]
            (when-let [call-hook (resolve 'com.blockether.svar.internal.rlm/call-hook!)]
              (call-hook hooks-atom :code-exec :post {:code code
                                                      :result result
                                                      :stdout stdout
                                                      :stderr stderr
                                                      :error error
                                                      :time-ms execution-time}))
            result-map))))))

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
        (:rlm/reasoning result) (assoc :reasoning (:rlm/reasoning result))
        (:rlm/learn result)     (assoc :learn (:rlm/learn result)))
      {:final? false})))

(defn answer-str
  "Extracts a string representation from an RLM answer.
   Answer is {:result value :type type} — returns the :result as a string."
  [answer]
  (let [v (:result answer answer)]
    (if (string? v) v (pr-str v))))

;; =============================================================================
;; Recursive LLM Query
;; =============================================================================

(defn make-rlm-query-fn
  "Creates the rlm-query function for sub-RLM queries that share the same database.

    This allows the LLM to spawn sub-queries with code execution that reuse
    the same database, enabling complex multi-step analysis."
  [prefs depth-atom rlm-router db-info-atom]
  (fn rlm-query
    ([context sub-query]
     (rlm-query context sub-query {}))
    ([context sub-query opts]
     (if (>= @depth-atom *max-recursion-depth*)
       {:error (str "Max recursion depth (" *max-recursion-depth* ") exceeded")}
       (try
         (swap! depth-atom inc)
         (run-sub-rlm context sub-query prefs rlm-router db-info-atom
           (merge {:max-iterations 10} opts))
         (finally (swap! depth-atom dec)))))))

(defn run-sub-rlm
  "Runs a sub-RLM query that shares the same database as the parent.

    This enables nested RLM queries where the sub-query can read/write
    to the same store, access the same history, etc.

   Params:
    `context` - Data context for the sub-query
    `query` - The question to answer
    `prefs` - Preferences map for routing (e.g. {:strategy :root})
    `rlm-router` - Router for LLM calls
    `db-info-atom` - Shared database atom from parent RLM
    `opts` - Options including :max-iterations, :spec"
  [context query prefs rlm-router db-info-atom opts]
  (let [sub-env-id (str (util/uuid))
        parent-env-id (:rlm-env-id *rlm-ctx*)
        depth-atom (atom 0)
        locals-atom (atom {})
         ;; Create query functions that share the same DB
        llm-query-fn (make-routed-llm-query-fn prefs depth-atom rlm-router)
        rlm-query-fn (make-rlm-query-fn prefs depth-atom rlm-router db-info-atom)
        ;; Create SCI context with shared DB (no custom bindings in sub-RLM)
        {:keys [sci-ctx p-atom]} (create-sci-context context llm-query-fn rlm-query-fn locals-atom db-info-atom nil)
        sub-env {:env-id sub-env-id
                 :parent-env-id parent-env-id
                 :sci-ctx sci-ctx
                 :p-atom p-atom
                 :context context
                 :llm-query-fn llm-query-fn
                 :rlm-query-fn rlm-query-fn
                 :locals-atom locals-atom
                 :db-info-atom db-info-atom
                 :router rlm-router
                 :history-enabled? true}
        max-iterations (or (:max-iterations opts) 10)
        output-spec (:spec opts)
        ;; Check if root provider has native reasoning
        has-reasoning? (boolean (provider-has-reasoning? rlm-router))
        iteration-spec (if has-reasoning? ITERATION_SPEC_CODE_ONLY ITERATION_SPEC)
        ;; Run a simplified iteration loop (no refine-eval, no examples)
        system-prompt (build-system-prompt {:output-spec output-spec
                                            :history-enabled? true
                                            :has-reasoning? has-reasoning?})
        context-str (pr-str context)
        context-preview (if (> (count context-str) 1000)
                          (str (subs context-str 0 1000) "\n... [truncated]")
                          context-str)
        initial-user-content (str "{:context " (pr-str context-preview) "\n :requirement " (pr-str query) "}")
        initial-messages [{:role "system" :content system-prompt}
                          {:role "user" :content initial-user-content}]
        ;; Store to shared history
        db-info @db-info-atom
        _ (store-message! db-info :user initial-user-content {:iteration 0 :env-id sub-env-id})]
    (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-env-id sub-env-id :rlm-type :sub :rlm-parent-id parent-env-id :rlm-phase :sub-iteration-loop})]
      (rlm-debug! {:query query :max-iterations max-iterations :parent-env-id parent-env-id
                   :has-reasoning? has-reasoning?} "Sub-RLM started")
      (loop [iteration 0 messages initial-messages]
        (if (>= iteration max-iterations)
          (do (trove/log! {:level :warn :data {:iteration iteration} :msg "Sub-RLM max iterations reached"})
              {:status :max-iterations :iterations iteration})
          (let [{:keys [response thinking executions final-result]}
                (run-iteration sub-env messages {:iteration-spec iteration-spec})]
            (let [stored (store-message! db-info :assistant response
                           {:iteration iteration :env-id sub-env-id :thinking thinking})]
              (when (and stored (seq executions))
                (store-executions! db-info (:id stored) executions)))
            (if final-result
              (do (rlm-debug! {:iteration iteration} "Sub-RLM FINAL detected")
                  {:answer (:result (:answer final-result) (:answer final-result)) :iterations iteration})
              (let [exec-feedback (format-executions executions)
                    iteration-header (str "[Iteration " (inc iteration) "/" max-iterations "]")
                    user-feedback (if (empty? executions)
                                    (str iteration-header "\nNo code was executed. You MUST include Clojure expressions in the \"code\" JSON array. Respond with valid JSON: "
                                      (if has-reasoning?
                                        "{\"code\": [\"...\"]}"
                                        "{\"thinking\": \"...\", \"code\": [\"...\"]}"))
                                    (str iteration-header "\n" exec-feedback))]
                (rlm-debug! {:iteration iteration :code-blocks (count executions) :has-thinking? (some? thinking)} "Sub-RLM iteration feedback")
                (store-message! db-info :tool user-feedback {:iteration (inc iteration) :env-id sub-env-id})
                (recur (inc iteration)
                  (conj messages
                    {:role "assistant" :content response}
                    {:role "user" :content user-feedback}))))))))))

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

(def ^:private TOKENS_PER_LEARNING
  "Approximate tokens per formatted learning line (insight + tags + context)."
  30)

(defn format-active-learnings
  "Format pre-fetched learnings and tag definitions for injection into the system prompt.

   Params:
   `learnings` - Vector of normalized learning maps (direct matches).
   `tag-definitions` - Vector of {:name :definition} maps for all known tags.
   `max-context-tokens` - Integer, optional. Context budget — learnings get 5% of this.
                          Defaults to 15 learnings max if not provided.

   Returns:
   String with formatted tag glossary + active learnings, or nil if empty."
  ([learnings tag-definitions] (format-active-learnings learnings tag-definitions nil))
  ([learnings tag-definitions max-context-tokens]
   (let [has-learnings? (seq learnings)
         ;; Dynamic cap: 5% of context budget / ~30 tokens per learning. Min 3, max 15.
         max-learnings (if max-context-tokens
                         (-> (quot (* max-context-tokens 5) (* 100 TOKENS_PER_LEARNING))
                           (max 3) (min 15))
                         15)
         capped-learnings (take max-learnings learnings)
         active-tag-names (set (mapcat :tags capped-learnings))
         relevant-tags (when (seq tag-definitions)
                         (filterv #(and (:definition %) (active-tag-names (:name %))) tag-definitions))]
     (when (or has-learnings? (seq relevant-tags))
       (str
         (when (seq relevant-tags)
           (str "\n<learning_tag_glossary>\n"
             "Tag definitions for categorizing learnings:\n"
             (str/join "\n" (map (fn [{:keys [name definition]}]
                                   (str "  - " name ": " definition))
                              relevant-tags))
             "\n</learning_tag_glossary>\n"))
         (when has-learnings?
           (str "\n<active_learnings>\n"
             "These are validated insights from prior sessions. Apply them.\n"
             (str/join "\n" (map-indexed
                              (fn [i l]
                                (str "  " (inc i) ". " (str-truncate (:insight l) 100)
                                  (when (seq (:tags l)) (str " [tags: " (str/join ", " (:tags l)) "]"))
                                  (when (:context l) (str " [context: " (str-truncate (:context l) 60) "]"))))
                              capped-learnings))
             "\n</active_learnings>\n")))))))

(defn build-system-prompt
  "Builds the system prompt. Optionally includes spec schema, history tools, and custom docs.

   When :has-reasoning? is true, uses ITERATION_SPEC_CODE_ONLY (no thinking field)
   because the provider emits native reasoning tokens — no need to duplicate in JSON."
  [{:keys [output-spec history-enabled? custom-docs has-documents? learnings tag-definitions
           has-reasoning? system-prompt max-context-tokens]}]
  (str "<rlm_environment>
<role>You are an expert Clojure programmer analyzing data in a sandboxed environment.</role>
"
    (when system-prompt
      (str "\n<agent_instructions>\n" system-prompt "\n</agent_instructions>\n"))
    "

<available_tools>
  <tool name=\"context\">The data context - access as 'context' variable</tool>
  <tool name=\"llm-query\">(llm-query prompt) or (llm-query prompt {:spec my-spec}) - Simple text query to LLM</tool>
  <tool name=\"rlm-query\">(rlm-query sub-context query) or (rlm-query sub-context query {:spec s :max-iterations n}) - Spawn sub-RLM with code execution, SHARES the same database</tool>
  <tool name=\"llm-query-batch\">(llm-query-batch [prompt1 prompt2 ...]) - Parallel batch of LLM sub-calls. Returns vector of results. Use for map-reduce patterns over many chunks.</tool>
  <tool name=\"FINAL\">(FINAL {:answer [parts...] ...}) - MUST call when you have the answer.
    ALWAYS use the structured map form. :answer is a vector of strings (auto-joined).
    Example: (FINAL {:answer [\"Your answer here\"]})

    opts / map keys:
      :confidence - :high (default), :medium, or :low. When :low, the system runs Chain-of-Verification.
      :sources    - Vector of source maps: [{:source \"path-or-url\" :type :file|:url|:trace :confidence :high|:medium|:low} ...]
      :learn      - Vector of learnings: [{:insight \"What was learned\" :tags [\"tag1\" \"tag2\"]} ...]"
    (if has-reasoning?
      "
    ALWAYS provide :sources when answering from documents.
    Examples:
      (FINAL \"The penalty is 5%\" {:sources [{:source \"node-abc\" :type :trace :confidence :high}]})
      (FINAL {:answer [part1 part2] :confidence :low})
      (FINAL {:answer [intro details conclusion]
              :sources [{:source \"contract.pdf\" :type :file :confidence :high}]
              :learn [{:insight \"Penalties defined in section 7\" :tags [\"contracts\" \"penalties\"]}]})</tool>"
      "
      :reasoning  - String. Brief summary of HOW you derived the answer.
    ALWAYS provide :sources and :reasoning when answering from documents.
    Examples:
      (FINAL \"The penalty is 5%\" {:sources [{:source \"node-abc\" :type :trace :confidence :high}] :reasoning \"Found in section 7.3\"})
      (FINAL {:answer [part1 part2] :confidence :low :reasoning \"Only partial data found\"})
      (FINAL {:answer [intro details conclusion]
              :sources [{:source \"contract.pdf\" :type :file :confidence :high}]
              :reasoning \"Aggregated from 3 sections\"
              :learn [{:insight \"Penalties defined in section 7\" :tags [\"contracts\" \"penalties\"]}]})</tool>")
    "

  <tool name=\"request-more-iterations\">(request-more-iterations n) - Request n more iterations if running low. Returns {:granted n :new-budget N :cap max}.</tool>
</available_tools>
"
    (when has-documents? "
<context_tools>
  <description>P is your structured workspace — the ENTIRE context you see each iteration.
  @P returns {:context [...] :last-iteration [...] :learnings [...]}.
  :context is YOUR working memory (vector of labeled strings). :last-iteration is auto-populated with your last code results. :learnings are priority-ranked insights.</description>
  <tool name=\"ctx-add!\">(ctx-add! text) or (ctx-add! label text) - Add a string to :context. Use this to save findings for next iteration.</tool>
  <tool name=\"ctx-remove!\">(ctx-remove! idx) or (ctx-remove! label) - Remove from :context by index or label.</tool>
  <tool name=\"ctx-clear!\">(ctx-clear!) - Clear all :context entries.</tool>
  <tool name=\"ctx-list\">(ctx-list) - List :context entries with index, label, and char count.</tool>
  <tool name=\"learn!\">(learn! text) or (learn! text :high) - Add a prioritized learning. :high/:medium/:low.</tool>
  <tool name=\"forget!\">(forget! idx) - Remove a learning by index.</tool>
  <usage_tips>
    - SAVE IMPORTANT RESULTS: (ctx-add! \"findings\" (str results)) — anything not in :context is GONE next iteration
    - LABEL YOUR CONTEXT: (ctx-add! \"section-7\" (str section)) — labels make removal easy
    - CLEAN UP: (ctx-remove! \"raw-data\") when you no longer need something — keeps context lean
    - LEARNINGS PERSIST: (learn! \"Penalties defined in section 7\" :high) — high priority = always visible
    - Ingested documents are accessed via document_tools below
  </usage_tips>
</context_tools>

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
  Learnings: (search-learnings query), (search-learnings query top-k [\"tag\"]), (learning-stats), (list-learning-tags)
  Persist insights via FINAL: (FINAL {:answer [answer] :learn [{:insight \"...\" :tags [\"tag\"]}]})
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
  Retrieval: search-document-pages → P-add! → analyze. Use for finding SPECIFIC info.
  Regex: (re-seq #\"pattern\" P) for structured extraction across full text.
  ALWAYS call (FINAL {:answer [answer] :sources [...]}) with source references when answering from documents.
</rlm_patterns>

<workflow>
0. FIRST: Check <context> - if it directly answers the query, call (FINAL {:answer [\"your answer\"]}) immediately without searching
1. If more info needed, check available documents: (list-documents)
2. Browse TOC to understand document structure: (list-document-toc)
3. Pick sections from TOC, fetch content: (def section (P-add! [:page.node/id node-id]))
4. For exhaustive analysis over P: iterate (P-page 0), (P-page 1), ... or use llm-query-batch
5. Check entities if relevant: (document-entity-stats), then (list-document-entities {:type :party})
6. Write code to analyze data, store intermediate results with (def my-var ...)"
    (when history-enabled?
      "")
    "\n" (if history-enabled? "8" "7") ". Call (FINAL {:answer [...]}) when done
</workflow>

<response_format>
" (spec/spec->prompt (if has-reasoning? ITERATION_SPEC_CODE_ONLY ITERATION_SPEC)) "
" (if has-reasoning?
    "EVERY response MUST be valid JSON with a 'code' field. Your reasoning happens natively — do NOT include a 'thinking' field. No markdown, no prose outside JSON."
    "EVERY response MUST be valid JSON with 'thinking' and 'code' fields. No markdown, no prose outside JSON.") "
</response_format>

<critical>
- ALL RESULTS INLINE: Execution results are shown in full. You see everything — no hidden variables.
- COMBINE STEPS: Code blocks execute sequentially in ONE iteration. Do NOT split read+answer into separate iterations. Example: [\"(def content (read-file path))\", \"(def summary (summarize content))\", \"(FINAL {:answer [summary]})\"]
- LONG ANSWERS: Put each part as a separate string in the :answer vector: (FINAL {:answer [\"part 1\" \"part 2\"]}). Elements are auto-joined.
- CLOJURE SYNTAX: ALL function calls MUST be wrapped in parentheses. `(FINAL {:answer [...]})` terminates, `FINAL answer` does NOT.
- VARS ARE VALUES: Use `my-var` to reference a stored value, NOT `(my-var)`. Maps/vectors are not callable with 0 args — `(my-map)` throws an error.
- FAST PATH: If context already contains the answer, call (FINAL {:answer [\"your answer\"]}) IMMEDIATELY.
- NEVER REPEAT: If a call returned [] or nil, do NOT call it again. Try a different approach or FINAL with what you have.
- ALWAYS call (FINAL {:answer [...]}) as soon as you have enough information. NEVER call (FINAL \"string\") directly — always use the {:answer [...]} map form.
- WORKSPACE P: @P is your living memory with {:context [...] :learnings [...]}.
  :context — stack of strings (newest last). YOUR working memory. Use (ctx-add! text) to save, (ctx-remove! idx) to drop.
  :learnings — scratch notes visible in workspace. Use (learn! text :priority). Only learnings passed to (FINAL {:learn [...]}) are persisted.
  CRITICAL: Each iteration you ONLY see [system prompt, query, <workspace>]. No growing history.
  :context is AUTO-POPULATED with reasoning + execution summaries after each iteration.
  :context PERSISTS ACROSS QUERIES — it survives to the next user question in this session.
  USE VARS: (def data (some-call ...)) stores full results in the REPL — they persist across iterations.
  Context only shows [stored in var: data] for def'd values. Access the full value via the var name.
  Use (ctx-add! text) for extra notes. Use (ctx-remove! idx) or (ctx-replace! from to summary) to manage.
  When context grows large, you will receive a [SYSTEM_NUDGE] to clean up — ALWAYS obey it.

[SYSTEM_NUDGE] DEFINITION:
  Messages tagged [SYSTEM_NUDGE] are mandatory system instructions injected during execution.
  They are NOT suggestions. You MUST follow them immediately in your next code block.
  Ignoring a [SYSTEM_NUDGE] will degrade your performance and waste iterations.
- ITERATION BUDGET: " MAX_ITERATIONS " iterations. Hard cap: " MAX_ITERATION_CAP ". " (/ EVAL_TIMEOUT_MS 1000) "s timeout per execution.
</critical>
"
       ;; Inject pre-fetched learnings (model-specific + query-relevant)
    (format-active-learnings learnings tag-definitions max-context-tokens)
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
  [rlm-env messages & [{:keys [iteration-spec] :or {iteration-spec ITERATION_SPEC}}]]
  (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-phase :run-iteration})]
    (let [hooks-atom (:hooks-atom rlm-env)
          _ (rlm-debug! {:msg-count (count messages)} "LLM call started")
          _ (when hooks-atom
              ((resolve 'com.blockether.svar.internal.rlm/call-hook!)
               hooks-atom :llm-call :pre {:prompt (some-> messages last :content) :opts nil}))
          response-data (llm/routed-chat-completion (:router rlm-env) messages {:strategy :root :json-mode? true})
          response (:content response-data)
          model-reasoning (:reasoning response-data)
          _ (when hooks-atom
              ((resolve 'com.blockether.svar.internal.rlm/call-hook!)
               hooks-atom :llm-call :post {:prompt (some-> messages last :content)
                                           :response response
                                           :reasoning model-reasoning
                                           :provider-id (:routed/provider-id response-data)
                                           :model (:routed/model response-data)
                                           :base-url (:routed/base-url response-data)}))
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
          code-blocks (vec (remove str/blank? (or (:code parsed) [])))
          _ (rlm-debug! {:code-block-count (count code-blocks)
                         :code-previews (mapv #(str-truncate % 120) code-blocks)} "Code blocks extracted")
          execution-results (mapv #(execute-code rlm-env %) code-blocks)
          ;; Combine code blocks with their execution results
          raw-executions (mapv (fn [idx code result]
                                 {:id idx
                                  :code code
                                  :result (:result result)
                                  :stdout (:stdout result)
                                  :stderr (:stderr result)
                                  :error (:error result)
                                  :execution-time-ms (:execution-time-ms result)})
                           (range)
                           code-blocks
                           execution-results)
          ;; Results stay inline — context budget handles size naturally.
          ;; No auto-store: the model sees full results directly.
          executions raw-executions
          final-result (some #(let [check (check-result-for-final %)] (when (:final? check) check)) execution-results)]
      {;; When native reasoning is present, the raw response is just a redundant JSON wrapper
       ;; around thinking+code. Use nil to keep the trace clean — thinking is in :thinking.
       :response (when-not model-reasoning response)
       :thinking thinking :executions executions :final-result final-result
       :api-usage (:api-usage response-data)})))

;; =============================================================================
;; Auto-Store Large Results (RLM Paper §2: constant-size metadata in history)
;; =============================================================================

(defn- result-type-label
  "Returns a human-readable type label for an execution result."
  [v]
  (cond
    (nil? v) "nil"
    (string? v) "string"
    (number? v) "number"
    (boolean? v) "boolean"
    (keyword? v) "keyword"
    (symbol? v) "symbol"
    (map? v) (str "map (" (count v) " keys)")
    (vector? v) (str "vector (" (count v) " items)")
    (seq? v) (str "seq (" (count v) " items)")
    (set? v) (str "set (" (count v) " items)")
    (fn? v) "function"
    :else (str (type v))))

(defn auto-store-results!
  "Auto-stores large execution results in SCI REPL variables.
   Per Zhang et al. (2025) Algorithm 1: only constant-size metadata about stdout
   goes into the model's history. Large results are stored as _rN variables that
   the model can reference in subsequent code.

   Mutates: locals-atom, sci-ctx (adds _rN / _stdoutN bindings).
   Returns: executions with :auto-var and :auto-stdout-var keys added where stored."
  [{:keys [sci-ctx locals-atom]} executions]
  (mapv (fn [{:keys [id result stdout error] :as exec}]
          (if error
            exec
            (let [result-str (pr-str (realize-value result))
                  large-result? (and (not (fn? result))
                                  (> (count result-str) INLINE_RESULT_THRESHOLD))
                  large-stdout? (and (not (str/blank? stdout))
                                  (> (count stdout) INLINE_RESULT_THRESHOLD))
                  ;; Auto-store large result
                  exec (if large-result?
                         (let [var-name (str "_r" id)]
                           (swap! locals-atom assoc (symbol var-name) (realize-value result))
                           (try (sci/eval-string* sci-ctx (str "(def " var-name " (get-local '" var-name "))"))
                                (catch Exception _ nil))
                           (assoc exec :auto-var var-name))
                         exec)
                  ;; Auto-store large stdout
                  exec (if large-stdout?
                         (let [var-name (str "_stdout" id)]
                           (swap! locals-atom assoc (symbol var-name) stdout)
                           (try (sci/eval-string* sci-ctx (str "(def " var-name " (get-local '" var-name "))"))
                                (catch Exception _ nil))
                           (assoc exec :auto-stdout-var var-name))
                         exec)]
              exec)))
    executions))

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

(defn iteration-loop [rlm-env query
                      {:keys [output-spec learnings tag-definitions
                              max-context-tokens custom-docs system-prompt
                              pre-fetched-context hooks-atom
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
                             (long (* 0.6 (providers/context-limit effective-model))))
        history-enabled? (:history-enabled? rlm-env)
        ;; Check if root provider has native reasoning (thinking tokens)
        has-reasoning? (boolean (provider-has-reasoning? (:router rlm-env)))
        has-docs? (when-let [db-atom (:db-info-atom rlm-env)]
                    (when-let [db @db-atom]
                      (pos? (count (db-list-documents db {:limit 1 :include-toc? false})))))
        system-prompt (build-system-prompt {:output-spec output-spec
                                            :history-enabled? history-enabled?
                                            :custom-docs custom-docs
                                            :has-documents? has-docs?
                                            :learnings learnings
                                            :tag-definitions tag-definitions
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
                              cost (tokens/estimate-cost effective-model input-tokens output-tokens)]
                          {:tokens {:input input-tokens :output output-tokens
                                    :reasoning reasoning-tokens :cached cached-tokens
                                    :total total-tokens}
                           :cost cost}))
        _ (when history-enabled?
            (store-message! db-info :system system-prompt {:iteration 0 :model effective-model :env-id env-id})
            ;; Store clean user query as content (not XML-wrapped)
            (store-message! db-info :user query {:iteration 0 :model effective-model :env-id env-id}))]
    (rlm-debug! {:query query :max-iterations max-iterations :model effective-model
                 :has-output-spec? (some? output-spec) :has-pre-fetched? (some? pre-fetched-context)
                 :has-reasoning? has-reasoning?
                 :msg-count (count initial-messages)} "Iteration loop started")
    (binding [*rlm-ctx* (merge *rlm-ctx* {:rlm-phase :iteration-loop})]
      (loop [iteration 0 messages initial-messages trace [] consecutive-errors 0 restarts 0]
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
                (when history-enabled?
                  (store-message! db-info :tool restart-hint {:iteration (inc iteration) :model effective-model :env-id env-id}))
                (recur (inc iteration) restart-messages trace 0 (inc restarts)))
              (do (trove/log! {:level :warn :data {:iteration iteration :consecutive-errors consecutive-errors
                                                   :restarts restarts} :msg "Error budget exhausted after restart"})
                  (merge {:answer nil :status :error-budget-exhausted :trace trace :iterations iteration}
                    (finalize-cost))))
            (let [_ (rlm-debug! {:iteration iteration :msg-count (count messages)} "Iteration start")
                  _ (when-let [call-hook (resolve 'com.blockether.svar.internal.rlm/call-hook!)]
                      (call-hook hooks-atom :iteration :pre {:iteration iteration :query query}))
                  ;; RLM context: system prompt + user query + P workspace.
                  ;; P contains everything: :last-iteration (auto), :context (LLM), :learnings (priority).
                  ;; Always include last 3 user messages from DB as safety net
                  recent-user-msgs (when history-enabled?
                                     (try
                                       (let [recent (get-recent-messages db-info 10)]
                                         (->> recent
                                           (filter #(= :user (:role %)))
                                           (drop 1) ;; skip current query
                                           (take 3)
                                           reverse
                                           (mapv :content)))
                                       (catch Exception _ nil)))
                  p-snapshot (let [pa (:p-atom rlm-env)
                                   {:keys [context learnings]} (when pa @pa)
                                   sections (cond-> []
                                              (seq recent-user-msgs)
                                              (conj (str "<recent-messages>\n"
                                                      (str/join "\n" (map #(str "- " %) recent-user-msgs))
                                                      "\n</recent-messages>"))
                                              (seq learnings)
                                              (conj (str "<learnings>\n"
                                                      (str/join "\n"
                                                        (map-indexed (fn [i {:keys [text priority]}]
                                                                       (str "[" i " " (name (or priority :medium)) "] " text))
                                                          learnings))
                                                      "\n</learnings>"))
                                              (seq context)
                                              (conj (str "<context>\n"
                                                      (str/join "\n" context)
                                                      "\n</context>")))]
                               (when (seq sections)
                                 (str "<workspace iteration=\"" iteration "\">\n"
                                   (str/join "\n" sections)
                                   "\n</workspace>")))
                  ;; Budget trimming: remove oldest context entries if P is too large
                  _ (when-let [pa (:p-atom rlm-env)]
                      (let [snapshot-size (count (str p-snapshot))
                            max-p-chars (* 4 (or max-context-tokens 50000))] ;; ~4 chars per token
                        (when (> snapshot-size max-p-chars)
                          (swap! pa update :context
                            (fn [ctx]
                              (loop [c ctx]
                                (if (or (<= (count (str/join "\n" c)) max-p-chars) (< (count c) 2))
                                  c
                                  (recur (vec (rest c))))))))))  ;; drop oldest first
                  ;; Re-snapshot after trimming
                  p-snapshot (or p-snapshot
                               (when-let [pa (:p-atom rlm-env)]
                                 (let [{:keys [context learnings]} @pa
                                       sections (cond-> []
                                                  (seq learnings)
                                                  (conj (str "<learnings>\n"
                                                          (str/join "\n"
                                                            (map-indexed (fn [i {:keys [text priority]}]
                                                                           (str "[" i " " (name (or priority :medium)) "] " text))
                                                              learnings))
                                                          "\n</learnings>"))
                                                  (seq context)
                                                  (conj (str "<context>\n" (str/join "\n" context) "\n</context>")))]
                                   (when (seq sections)
                                     (str "<workspace iteration=\"" iteration "\">\n"
                                       (str/join "\n" sections) "\n</workspace>")))))
                  base-messages (vec (take 2 messages)) ;; [system-prompt, user-query]
                  effective-messages (cond-> base-messages
                                       p-snapshot
                                       (conj {:role "user" :content p-snapshot}))
                  iteration-result (try
                                     (run-iteration rlm-env effective-messages
                                       {:iteration-spec (if has-reasoning?
                                                          ITERATION_SPEC_CODE_ONLY
                                                          ITERATION_SPEC)})
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
                  (when-let [call-hook (resolve 'com.blockether.svar.internal.rlm/call-hook!)]
                    (call-hook hooks-atom :iteration :post trace-entry))
                  (when history-enabled?
                    (store-message! db-info :tool error-feedback {:iteration (inc iteration) :model effective-model :env-id env-id}))
                  (recur (inc iteration)
                    (conj messages {:role "user" :content error-feedback})
                    (conj trace trace-entry)
                    (inc consecutive-errors)
                    restarts))
                ;; Normal path — accumulate token usage
                (let [_ (accumulate-usage! (:api-usage iteration-result))
                      {:keys [response thinking executions final-result]} iteration-result
                      trace-entry {:iteration iteration
                                   :response response
                                   :thinking thinking
                                   :executions executions
                                   :final? (boolean final-result)}]
                  ;; Notify caller of iteration progress
                  (when-let [call-hook (resolve 'com.blockether.svar.internal.rlm/call-hook!)]
                    (call-hook hooks-atom :iteration :post trace-entry))
                  ;; Store structured assistant message + executions
                  ;; When response is nil (reasoning model), use thinking as content
                  ;; On FINAL, include the full trace as result-edn for later reconstruction
                  (when history-enabled?
                    (let [cost-map    (when final-result (finalize-cost))
                          result-edn (when final-result
                                       (pr-str (cond-> (merge {:trace (conj trace trace-entry)
                                                               :answer (:answer final-result)
                                                               :iterations (inc iteration)
                                                               :confidence (:confidence final-result)}
                                                         cost-map)
                                                 (:sources final-result)   (assoc :sources (:sources final-result))
                                                 (:reasoning final-result) (assoc :reasoning (:reasoning final-result))
                                                 (:learn final-result)     (assoc :learn (:learn final-result)))))
                          stored (store-message! db-info :assistant (or response thinking "[no response]")
                                   {:iteration iteration :model effective-model
                                    :env-id env-id :thinking thinking
                                    :result-edn result-edn})]
                      (when (and stored (seq executions))
                        (store-executions! db-info (:id stored) executions))))
                  (if final-result
                    (do (trove/log! {:level :info :data {:iteration iteration :answer (str-truncate (answer-str (:answer final-result)) 200)} :msg "FINAL detected"})
                        (merge (cond-> {:answer (:answer final-result)
                                        :trace (conj trace trace-entry)
                                        :iterations (inc iteration)
                                        :confidence (:confidence final-result)}
                                 (:sources final-result)   (assoc :sources (:sources final-result))
                                 (:reasoning final-result) (assoc :reasoning (:reasoning final-result))
                                 (:learn final-result)     (assoc :learn (:learn final-result)))
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
                          restarts))
                      ;; Normal iteration with executions
                      (let [exec-feedback (format-executions executions)
                            iteration-header (str "[Iteration " (inc iteration) "/" (effective-max-iterations) "]\n"
                                               "{:requirement " (pr-str (str-truncate query 200)) "}")
                            learning-nudge (when (and (pos? iteration) (zero? (mod (inc iteration) 10)))
                                             "\n[Tip: Consider (search-learnings \"your current topic\") for insights from prior sessions.]")
                            repetition-warning (detect-repetition executions)
                            remaining-iters (- (effective-max-iterations) (inc iteration))
                            budget-warning (when (<= remaining-iters 5)
                                             (str "\n[SYSTEM_NUDGE] Only " remaining-iters " iterations left! "
                                               "Call (FINAL {:answer [\"your findings\"]}) NOW with what you have. DO NOT start new explorations."))
                            force-final-nudge (when (> iteration 20)
                                                (str "\n[SYSTEM_NUDGE] You have been running for " (inc iteration) " iterations. "
                                                  "STOP exploring. Call (FINAL {:answer [...]}) IMMEDIATELY with your current findings."))
                            ctx-overflow-nudge (when-let [pa (:p-atom rlm-env)]
                                                 (let [ctx-count (count (:context @pa))]
                                                   (when (> ctx-count 12)
                                                     ;; Force-trim: keep last 10
                                                     (swap! pa update :context (fn [ctx] (vec (take-last 10 ctx))))
                                                     (str "\n[SYSTEM_NUDGE] Context trimmed to last 10 entries (was " ctx-count "). "
                                                       "Use (ctx-replace! from to \"summary\") to compact further."))))
                            user-feedback (str iteration-header "\n" exec-feedback learning-nudge repetition-warning ctx-overflow-nudge budget-warning force-final-nudge)]
                        (rlm-debug! {:iteration iteration
                                     :code-blocks (count executions)
                                     :errors (count (filter :error executions))
                                     :has-thinking? (some? thinking)
                                     :thinking-preview (when thinking (str-truncate thinking 150))
                                     :feedback-len (count user-feedback)} "Iteration feedback")
                        (when history-enabled?
                          (store-message! db-info :tool user-feedback {:iteration (inc iteration) :model effective-model :env-id env-id}))
                        (let [had-successful-execution? (some #(nil? (:error %)) executions)
                              next-errors (if had-successful-execution? 0 (inc consecutive-errors))]
                          ;; Auto-add reasoning + execution summary to P context
                          (when-let [pa (:p-atom rlm-env)]
                            (let [summarize-result (fn [result]
                                                     (let [v (realize-value result)
                                                           s (pr-str v)]
                                                       (cond
                                                         (nil? v) "nil"
                                                         (and (map? v) (:entries v)) (str "{" (:total v) " entries, path: " (:path v) "}")
                                                         (and (map? v) (seq v)) (str "{" (count v) " keys: " (str/join ", " (take 5 (keys v))) (when (> (count v) 5) "...") "}")
                                                         (and (vector? v) (> (count v) 3)) (str "[" (count v) " items]")
                                                         (> (count s) 200) (str (subs s 0 197) "...")
                                                         :else s)))
                                  summary (str "[iter " (inc iteration) "]"
                                            (when thinking (str "\nReasoning: " (str-truncate thinking 300)))
                                            (when (seq executions)
                                              (str "\nExecutions:\n" (str/join "\n"
                                                                       (map (fn [{:keys [code result error stdout]}]
                                                                              (let [code-str (str/trim (or code ""))
                                                                                    is-def? (str/starts-with? code-str "(def ")]
                                                                                (str "  " code-str " → "
                                                                                  (cond
                                                                                    error (str "ERROR: " (str-truncate (str error) 150))
                                                                                    is-def? (let [var-name (second (re-find #"\(def\s+(\S+)" code-str))]
                                                                                              (str "[var: " var-name " = " (summarize-result result) "]"))
                                                                                    :else (summarize-result result))
                                                                                  (when (and stdout (not (str/blank? stdout)))
                                                                                    (str "\n    stdout: " (str-truncate stdout 100))))))
                                                                         executions)))))]
                              (swap! pa update :context conj summary)))
                          (recur (inc iteration)
                            messages
                            (conj trace trace-entry)
                            next-errors
                            restarts))))))))))))))

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
          response (llm/ask! {:spec ENTITY_EXTRACTION_SPEC
                              :messages [(llm/system ENTITY_EXTRACTION_OBJECTIVE)
                                         (llm/user truncated)]
                              :router rlm-router
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
              response (llm/ask! {:spec ENTITY_EXTRACTION_SPEC
                                  :messages [(llm/system ENTITY_EXTRACTION_OBJECTIVE)
                                             (llm/user (or description "Extract entities from this image")
                                               (llm/image b64 "image/png"))]
                                  :router rlm-router
                                  :prefer :cost :capabilities #{:chat :vision}})]
          (or (:result response) {:entities [] :relationships []}))
        ;; Has description only - text extraction
        description
        (let [response (llm/ask! {:spec ENTITY_EXTRACTION_SPEC
                                  :messages [(llm/system ENTITY_EXTRACTION_OBJECTIVE)
                                             (llm/user description)]
                                  :router rlm-router
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
;; Auto-Learn: Vote + Extract
;; =============================================================================
;;
;; Post-query reflection pipeline:
;;   1. reflect-vote!    — cheap LLM evaluates which injected learnings helped
;;   2. reflect-extract! — cheap LLM extracts reusable insights (async)

(defn- cheap-ask!
  "Fires a cheap, non-critical LLM call. Returns response or nil on error."
  [rlm-router spec prompt log-id]
  (try
    (llm/ask! {:spec spec
               :messages [(llm/user prompt)]
               :router rlm-router
               :prefer :cost :capabilities #{:chat}})
    (catch Exception e
      (trove/log! {:level :warn :data {:error (ex-message e)} :msg (str log-id " failed, skipping")})
      nil)))

(defn- summarize-trace
  "Builds a human-readable summary of the last N trace entries (code + results)."
  [trace n]
  (->> trace
    (take-last n)
    (map (fn [t]
           (let [code-preview (when (seq (:executions t))
                                (->> (:executions t)
                                  (map (fn [e]
                                         (str "  " (str-truncate (:code e) 80)
                                           " => " (str-truncate (pr-str (:result e)) 60))))
                                  (str/join "\n")))]
             (str "Iteration " (:iteration t) ":"
               (when (:thinking t) (str " " (str-truncate (:thinking t) 80)))
               (when code-preview (str "\n" code-preview))))))
    (str/join "\n")))

;; =============================================================================
;; Public API - Component-Based Architecture
;; =============================================================================

(defn reflect-vote!
  "Evaluates which injected learnings were useful. Votes drive decay.
   Returns {:tokens :cost} for cost tracking, or nil."
  [db-info rlm-router active-learnings query outcome answer-preview]
  (when (and (:conn db-info) rlm-router (seq active-learnings))
    (let [learnings-summary (str/join "\n"
                              (map-indexed
                                (fn [i l]
                                  (str (inc i) ". [" (:learning/id l) "] " (:insight l)
                                    (when (:context l) (str " (context: " (:context l) ")"))))
                                active-learnings))
          prompt (str "You are evaluating whether learnings/insights were useful for a query.\n\n"
                   "Query: " query "\n"
                   "Outcome: " (name outcome) "\n"
                   (when answer-preview (str "Answer preview: " answer-preview "\n"))
                   "\nLearnings that were injected:\n" learnings-summary "\n\n"
                   "For EACH learning, vote 'useful' if it directly helped answer the query, "
                   "or 'not-useful' if it was irrelevant or unhelpful.")
          response (cheap-ask! rlm-router LEARNING_VOTE_SPEC prompt "reflect-vote!")]
      (when-let [votes (:votes (:result response))]
        (when (sequential? votes)
          (doseq [{:keys [id vote reason]} votes]
            (let [vote-kw (case vote :useful :useful :not_useful :not-useful nil)]
              (when vote-kw
                (when-let [uuid (util/parse-uuid (str id))]
                  (rlm-debug! {:learning-id uuid :vote vote-kw :reason reason} "reflect-vote!")
                  (db-vote-learning! db-info uuid vote-kw)))))))
      (when response
        {:tokens (:tokens response) :cost (:cost response)}))))

(defn reflect-extract!
  "Extracts reusable insights from successful multi-iteration queries.
   Only fires when: iterations > threshold AND query succeeded.
   Returns {:ids [...] :tokens :cost}, or nil."
  [db-info rlm-router query answer-preview iterations trace scope status]
  (when (and (:conn db-info) rlm-router
          (> iterations AUTOLEARN_ITERATION_THRESHOLD)
          (not= status :max-iterations)
          (not= status :error-budget-exhausted))
    (let [prompt (str "You just completed a " iterations "-iteration query. "
                   "Extract 1-3 REUSABLE insights about strategies that worked.\n\n"
                   "Query: " query "\n"
                   "Answer preview: " answer-preview "\n"
                   "Approach summary:\n" (summarize-trace trace 5) "\n\n"
                   "For each insight:\n"
                   "- insight: A concrete, reusable strategy (not query-specific)\n"
                   "- context: When this strategy applies\n"
                   "- tags: 1-3 lowercase category tags\n\n"
                   "Only extract insights that would help with FUTURE similar queries. "
                   "Skip obvious or query-specific observations.")
          response (cheap-ask! rlm-router AUTOLEARN_SPEC prompt "reflect-extract!")]
      (when-let [extracted (:learnings (:result response))]
        (let [ids (->> (when (sequential? extracted) extracted)
                    (keep (fn [{:keys [insight context tags]}]
                            (when (and insight (not (str/blank? insight)))
                              (let [stored (db-store-learning! db-info insight
                                             {:context context
                                              :tags (when (sequential? tags) (vec tags))
                                              :scope scope
                                              :source :auto})]
                                (rlm-debug! {:learning-id (:learning/id stored)
                                             :insight (str-truncate insight 100)
                                             :scope scope}
                                  "reflect-extract!")
                                (:learning/id stored)))))
                    vec)]
          (when (seq ids)
            {:ids ids :tokens (:tokens response) :cost (:cost response)}))))))
