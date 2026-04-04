(ns com.blockether.svar.internal.rlm.core
  (:require
   [clojure.string :as str]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.router :as router]
   [com.blockether.svar.internal.rlm.db :as rlm-db
    :refer [create-rlm-conn dispose-rlm-conn!
            db-list-documents db-store-final-result!
            db-list-final-results db-store-pageindex-document! str-truncate]]
   [com.blockether.svar.internal.rlm.routing
    :refer [make-routed-llm-query-fn resolve-root-model provider-has-reasoning?]]
   [com.blockether.svar.internal.rlm.schema
    :refer [ENTITY_EXTRACTION_SPEC ENTITY_EXTRACTION_OBJECTIVE
            ITERATION_SPEC ITERATION_SPEC_CODE_ONLY
            EVAL_TIMEOUT_MS
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
   :db-info-atom, :router."
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
         llm-query-fn (make-routed-llm-query-fn {} depth-atom router)
         {:keys [sci-ctx initial-ns-keys]} (create-sci-context context-data llm-query-fn db-info-atom nil)]
     {:sci-ctx sci-ctx :initial-ns-keys initial-ns-keys :context context-data
      :llm-query-fn llm-query-fn
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

(defn build-system-prompt
  "Builds the system prompt — compact, token-efficient.
   All tool documentation is discoverable via (doc fn-name) in SCI."
  [{:keys [output-spec custom-docs has-reasoning? system-prompt]}]
  (str "You are a Clojure agent in a SCI sandbox. Write code, execute, iterate.

ARCHITECTURE:
- Single-shot: each iteration is a fresh prompt. No message history.
- State lives in def'd vars — they persist across iterations.
- <var_index> shows all your vars (name, type, size, docstring).
- <execution_results> shows what your last code returned.
- Use (doc fn-name) to discover any function's purpose and args.
- Use (llm-query \"question\") to ask a sub-LLM for help.
"
    (when system-prompt
      (str "\nINSTRUCTIONS:\n" system-prompt "\n"))

    (format-custom-docs custom-docs)

    (when output-spec
      (str "\nOUTPUT SCHEMA:\n" (spec/spec->prompt output-spec) "\n"))
    "
RESPONSE FORMAT:
" (spec/spec->prompt (if has-reasoning? ITERATION_SPEC_CODE_ONLY ITERATION_SPEC)) "
" (if has-reasoning?
    "Respond with valid JSON. Your reasoning is native — omit 'thinking'."
    "Respond with valid JSON containing 'thinking' and 'code'.") "
Set 'final' when done: {\"final\": {\"answer\": \"...\", \"confidence\": \"high\"}}

RULES:
- Always (def name \"docstring\" value) — docstrings are your memory
- Test code before finalizing
- Never repeat a failed call — try a different approach
- Combine steps in one iteration
- If <var_index> or <context> already answers the query, finalize immediately
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
    (let [context-chars (reduce + 0 (map #(count (str (:content %))) messages))
          _ (trove/log! {:level :info :id ::llm-call
                         :data {:env-id (:env-id rlm-env)
                                :iteration iteration
                                :msg-count (count messages)
                                :context-chars context-chars
                                :context-chars-k (format "%.1fK" (/ context-chars 1000.0))}
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
        (let [final-answer (str (:answer final-data))
              confidence (or (:confidence final-data) :high)
              final-result {:final? true
                            :answer {:result final-answer :type String}
                            :confidence confidence}]
          (rlm-debug! {:final-answer (str-truncate final-answer 200)
                       :confidence confidence} "Final answer in response")
          {:response nil :thinking thinking :next-optimize next-optimize
           :executions [] :final-result final-result :api-usage api-usage})
        ;; Normal path: execute code blocks
        (let [code-blocks (vec (remove str/blank? (or (:code parsed) [])))
              _ (rlm-debug! {:code-block-count (count code-blocks)
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
                                  :execution-time-ms (:execution-time-ms result)})
                           (range) code-blocks execution-results)]
          {:response nil :thinking thinking :next-optimize next-optimize
           :executions executions :final-result nil :api-usage api-usage})))))

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
                              pre-fetched-context on-chunk query-ref
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
                             (long (* 0.6 (router/context-limit effective-model))))
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
                                  "You MUST try a DIFFERENT approach, or call \final\": {\"answer\": \"your answer\", \"confidence\": \"high\"} with what you have."))))
        finalize-cost (fn []
                        (let [{:keys [input-tokens output-tokens reasoning-tokens cached-tokens]} @usage-atom
                              total-tokens (+ input-tokens output-tokens)
                              cost (router/estimate-cost effective-model input-tokens output-tokens)]
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
             prev-executions nil prev-iteration -1
             journal [] prev-optimize nil]
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
                  nil -1 journal nil))
              (do (trove/log! {:level :warn :data {:iteration iteration :consecutive-errors consecutive-errors
                                                   :restarts restarts} :msg "Error budget exhausted after restart"})
                  (merge {:answer nil :status :error-budget-exhausted :trace trace :iterations iteration}
                    (finalize-cost))))
            (let [_ (rlm-debug! {:iteration iteration :msg-count (count messages)} "Iteration start")
                  ;; Build single-shot prompt: conversation + journal + execution results + var index
                  var-index-str (build-var-index (:sci-ctx rlm-env) (:initial-ns-keys rlm-env))
                  exec-results-str (format-execution-results prev-executions prev-iteration)
                  journal-str (render-execution-journal journal)
                  iteration-context (str
                                      conversation-thread
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
                    {:query-ref query-ref :index iteration
                     :response nil :executions nil :thinking nil :duration-ms 0})
                  (recur (inc iteration)
                    (conj messages {:role "user" :content error-feedback})
                    (conj trace trace-entry)
                    (inc consecutive-errors)
                    restarts
                    nil -1 journal nil))
                ;; Normal path — accumulate token usage
                (let [_ (accumulate-usage! (:api-usage iteration-result))
                      {:keys [response thinking executions final-result next-optimize]} iteration-result
                      ;; Store iteration snapshot — exact input/output for fine-tuning
                      _traj-iter (rlm-db/store-iteration! db-info
                                   {:query-ref query-ref :index iteration
                                    :response (cond-> {:thinking (or thinking "")
                                                       :code (mapv :code executions)}
                                                next-optimize (assoc :next-optimize next-optimize)
                                                final-result (assoc :final
                                                               {:answer (answer-str (:answer final-result))
                                                                :confidence (:confidence final-result)}))
                                    :executions executions
                                    :thinking thinking
                                    :final (when final-result (answer-str (:answer final-result)))
                                    :duration-ms (get-in iteration-result [:api-usage :prompt_tokens] 0)})
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
                                      "Respond with code or set final to finish."
                                      "Respond with thinking + code, or set final to finish."))]
                        ;; Store empty iteration snapshot
                        (rlm-db/store-iteration! db-info
                          {:query-ref query-ref :index iteration
                           :response {:thinking (or thinking "") :code []}
                           :executions nil :thinking thinking :duration-ms 0})
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
