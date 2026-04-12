(ns com.blockether.svar.internal.rlm.query
  "query-env! orchestration: context prep, iteration, refinement, Q-value updates, finalization."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.rlm.batch :as rlm-batch]
   [com.blockether.svar.internal.rlm.concurrency :as concurrency]
   [com.blockether.svar.internal.rlm.core :as rlm-core]
   [com.blockether.svar.internal.rlm.db :as rlm-db]
   [com.blockether.svar.internal.rlm.routing :as rlm-routing]
   [com.blockether.svar.internal.rlm.schema :as schema]
   [com.blockether.svar.internal.rlm.skills :as rlm-skills]
   [com.blockether.svar.internal.rlm.tools :as rlm-tools]
   [com.blockether.svar.internal.spec :as spec]
   [com.blockether.svar.internal.util :as util]
   [datalevin.core :as d]
   [taoensso.trove :as trove]))

;; -----------------------------------------------------------------------------
;; Q-value Reward Constants
;; -----------------------------------------------------------------------------

(def ^:const Q_REWARD_UNCITED
  "Penalty reward for pages fetched but not cited in the final answer."
  0.3)

(def ^:const Q_REWARD_FAILURE
  "Low reward for queries that hit max iterations or errored."
  0.1)

(defn- confidence->base-reward
  "Maps eval-scores or confidence level to a base Q-value reward (0.0-1.0).
   Prefers numeric eval-scores when available."
  [eval-scores confidence]
  (cond
    (and eval-scores (number? eval-scores)) (double eval-scores)
    (= confidence :high) 0.9
    (= confidence :medium) 0.6
    (= confidence :low) 0.3
    :else 0.5))

(defn- compute-q-reward
  "Computes the high reward for cited pages, incorporating iteration efficiency.
   Returns a reward clamped to [0.0, 1.0]."
  ^double [eval-scores confidence iterations max-iters]
  (let [efficiency  (- 1.0 (/ (double iterations) (double (max 1 max-iters))))
        base-reward (confidence->base-reward eval-scores confidence)]
    (min 1.0 (+ base-reward (* 0.1 efficiency)))))

;; -----------------------------------------------------------------------------
;; Phase 1 - Prepare query context
;; -----------------------------------------------------------------------------

(defn- prepare-query-context
  "Validates inputs, resolves SCI bindings, sets up atoms.
   Returns a map of all computed context needed for subsequent phases."
  [env messages opts]
  (let [{:keys [context spec model max-iterations max-refinements threshold
                max-context-tokens max-recursion-depth verify? concurrency
                system-prompt plan? debug? hooks cancel-atom eval-timeout-ms]
         :or   {max-iterations      schema/MAX_ITERATIONS
                max-refinements     1
                threshold           0.8
                max-recursion-depth schema/DEFAULT_RECURSION_DEPTH
                verify?             false
                plan?               false
                debug?              false}} opts]
    (when-not (:db-info-atom env)
      (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))
    (when-not (and (vector? messages) (seq messages))
      (anomaly/incorrect! "messages must be a non-empty vector of message maps, e.g. [(llm/user \"...\")]"
        {:type :rlm/invalid-messages :got (type messages)}))
    (when (and (some? eval-timeout-ms) (not (integer? eval-timeout-ms)))
      (anomaly/incorrect! ":eval-timeout-ms must be an integer (milliseconds)"
        {:type     :rlm/invalid-eval-timeout
         :got      eval-timeout-ms
         :got-type (type eval-timeout-ms)}))
    (let [cancel-atom            (or cancel-atom (atom false))
          query-str              (str/join "\n"
                                   (keep (fn [m]
                                           (let [c (:content m)]
                                             (cond
                                               (string? c)     c
                                               (sequential? c) (str/join " " (keep #(when (= "text" (:type %)) (:text %)) c))
                                               :else           nil)))
                                     messages))
          rlm-router             (:router env)
          root-model             (or (when rlm-router (rlm-routing/resolve-root-model rlm-router)) model)
          depth-atom             (atom 0)
          db-info-atom           (:db-info-atom env)
          cheap-sub-rlm-fn       (rlm-routing/make-routed-sub-rlm-query-fn
                                   {:optimize :cost} depth-atom rlm-router
                                   (:skill-registry-atom env) (atom env)
                                   rlm-core/iteration-loop)
          custom-bindings        (when-let [a (:state-atom env)] (:custom-bindings @a))
          custom-docs            (when-let [a (:state-atom env)] (:custom-docs @a))
          claims-atom            (when verify? (atom []))
          current-iteration-atom (atom 0)
          cite-bindings          (when verify?
                                   {'CITE            (rlm-tools/make-cite-fn claims-atom)
                                    'CITE-UNVERIFIED (rlm-tools/make-cite-unverified-fn claims-atom)
                                    'list-claims     (rlm-tools/make-list-claims-fn claims-atom)})
          cite-docs              (when verify?
                                   [{:type :fn   :sym 'CITE
                                     :doc  "(CITE claim-text document-id page section quote) or (CITE claim-text document-id page section quote confidence) - Cite a claim with source evidence. Returns {:cited true :claim-id uuid :claim-text text}"}
                                    {:type :fn   :sym 'CITE-UNVERIFIED
                                     :doc  "(CITE-UNVERIFIED claim-text) - Record a claim without source verification. Lower confidence."}
                                    {:type :fn   :sym 'list-claims
                                     :doc  "(list-claims) - List all claims cited so far in this query."}
                                    {:type :note :sym 'CITE-PRIORITY
                                     :doc  "CITE is OPTIONAL. ALWAYS call (FINAL answer) as soon as you have the answer. Only use CITE BEFORE calling FINAL if the query explicitly asks for citations. Do NOT delay FINAL to gather citations."}])
          max-iterations-atom    (atom max-iterations)
          budget-bindings        {'request-more-iterations
                                  (fn [n]
                                    (let [requested  (max 1 (min (long n) (long schema/MAX_EXTENSION_PER_REQUEST)))
                                          current    @max-iterations-atom
                                          new-budget (min (+ current requested) (long schema/MAX_ITERATION_CAP))]
                                      (reset! max-iterations-atom new-budget)
                                      (let [granted (- new-budget current)]
                                        (trove/log! {:level :info :id ::iteration-budget
                                                     :data {:requested  (long n) :granted granted
                                                            :new-budget new-budget :cap schema/MAX_ITERATION_CAP}
                                                     :msg  "LLM requested more iterations"})
                                        {:granted granted :new-budget new-budget :cap schema/MAX_ITERATION_CAP})))}
          sub-rlm-query-overrides {'sub-rlm-query       cheap-sub-rlm-fn
                                   'sub-rlm-query-batch (fn [items]
                                                          (rlm-batch/sub-rlm-query-batch cheap-sub-rlm-fn items))
                                   'skill-manage        (fn [action o]
                                                          (rlm-skills/skill-manage db-info-atom (:skill-registry-atom env) action o))}
          sci-ctx                (:sci-ctx env)
          tool-registry-atom     (:tool-registry-atom env)
          query-ctx              {:hooks hooks :iteration-atom current-iteration-atom}
          _                      (when (and sci-ctx tool-registry-atom)
                                   (doseq [[sym {:keys [fn]}] @tool-registry-atom]
                                     (when fn
                                       (rlm-tools/sci-update-binding! sci-ctx sym
                                         (rlm-tools/wrap-tool-for-sci env sym fn tool-registry-atom query-ctx)))))
          _                      (let [per-query (merge {'sub-rlm-query cheap-sub-rlm-fn}
                                                   budget-bindings cite-bindings
                                                   (or custom-bindings {}) sub-rlm-query-overrides)]
                                   (doseq [[sym val] per-query]
                                     (when val
                                       (rlm-tools/sci-update-binding! sci-ctx sym val))))
          query-start-time       (java.util.Date.)
          rlm-env                (assoc env :context context :max-iterations-atom max-iterations-atom)
          env-id                 (:env-id env)]
      {:cancel-atom            cancel-atom
       :query-str              query-str
       :rlm-router             rlm-router
       :root-model             root-model
       :db-info-atom           db-info-atom
       :custom-docs            custom-docs
       :claims-atom            claims-atom
       :current-iteration-atom current-iteration-atom
       :cite-docs              cite-docs
       :max-iterations-atom    max-iterations-atom
       :query-start-time       query-start-time
       :rlm-env                rlm-env
       :env-id                 env-id
       :spec                   spec
       :model                  model
       :max-iterations         max-iterations
       :max-refinements        max-refinements
       :threshold              threshold
       :max-context-tokens     max-context-tokens
       :max-recursion-depth    max-recursion-depth
       :verify?                verify?
       :concurrency            concurrency
       :system-prompt          system-prompt
       :plan?                  plan?
       :debug?                 debug?
       :hooks                  hooks
       :eval-timeout-ms        eval-timeout-ms
       :messages               messages})))

;; -----------------------------------------------------------------------------
;; Phase 2 - Run iteration phase
;; -----------------------------------------------------------------------------

(defn- run-iteration-phase
  "Runs the optional planning phase then the main iteration loop.
   Returns iteration-result, query-ref, cost atoms, and merge-cost! fn."
  [{:keys [rlm-router rlm-env query-str messages spec max-iterations
           max-context-tokens custom-docs cite-docs system-prompt
           current-iteration-atom hooks cancel-atom plan? db-info-atom]}]
  (let [db-info          @db-info-atom
        query-ref        (rlm-db/store-query! db-info
                           {:conversation-ref (:conversation-ref rlm-env)
                            :text             query-str
                            :messages         messages
                            :status           :running})
        plan-result      (when plan?
                           (llm/ask! rlm-router
                             {:messages [(llm/system "You are a planning assistant. Given a query and available document tools, outline a clear 3-5 step approach to answer the query. Be specific about which tools to use and in what order. Do NOT write code — just describe the strategy.")
                                         (llm/user (str "Query: " query-str))]
                              :routing  {:optimize :intelligence}}))
        plan-context     (when-let [plan (:result plan-result)]
                           (str "<plan>\n" plan "\n</plan>"))
        iteration-result (rlm-core/iteration-loop rlm-env query-str
                           {:max-iterations         max-iterations
                            :query-ref              query-ref
                            :output-spec            spec
                            :max-context-tokens     max-context-tokens
                            :custom-docs            (into (or custom-docs []) cite-docs)
                            :system-prompt          system-prompt
                            :pre-fetched-context    plan-context
                            :user-messages          messages
                            :current-iteration-atom current-iteration-atom
                            :hooks                  hooks
                            :cancel-atom            cancel-atom})
        {iter-tokens :tokens
         iter-cost   :cost} iteration-result
        total-tokens-atom (atom (or iter-tokens {}))
        total-cost-atom   (atom (or iter-cost {}))
        merge-cost!       (fn [extra-tokens extra-cost]
                            (when extra-tokens
                              (swap! total-tokens-atom
                                (fn [acc]
                                  (merge-with + acc
                                    (select-keys extra-tokens [:input :output :reasoning :cached :total])))))
                            (when extra-cost
                              (swap! total-cost-atom
                                (fn [acc]
                                  (merge-with + (select-keys acc [:input-cost :output-cost :total-cost])
                                    (select-keys extra-cost [:input-cost :output-cost :total-cost]))))))]
    (when plan-result
      (merge-cost! (:tokens plan-result) (:cost plan-result)))
    {:iteration-result  iteration-result
     :query-ref         query-ref
     :total-tokens-atom total-tokens-atom
     :total-cost-atom   total-cost-atom
     :merge-cost!       merge-cost!}))

;; -----------------------------------------------------------------------------
;; Phase 3 - Run refinement phase
;; -----------------------------------------------------------------------------

(defn- run-refinement-phase
  "Cross-model verify when confidence is :low.
   Returns {:answer ... :eval-scores ... :refinement-count ...}."
  [{:keys [rlm-router root-model spec max-refinements threshold query-str
           db-info-atom merge-cost!]}
   iter-answer confidence]
  (let [answer-value (:result iter-answer iter-answer)
        refine?      (= confidence :low)]
    (if refine?
      (let [answer-as-str    (rlm-core/answer-str iter-answer)
            conn             (:conn @db-info-atom)
            stored-docs      (when conn
                               (let [docs (d/q '[:find [(pull ?e [*]) ...]
                                                 :where [?e :document/id _]]
                                            (d/db conn))]
                                 (when (seq docs)
                                   (mapv (fn [doc]
                                           (let [doc-id (or (:document/id doc) (:document/name doc))
                                                 nodes  (d/q '[:find [(pull ?e [:page.node/page-id :page.node/content]) ...]
                                                               :in $ ?doc-id
                                                               :where [?e :page.node/document-id ?doc-id]]
                                                          (d/db conn) doc-id)]
                                             {:id    doc-id
                                              :pages (mapv (fn [pn]
                                                             {:page (or (:page.node/page-id pn) "0")
                                                              :text (or (:page.node/content pn) "")})
                                                       nodes)}))
                                     docs))))
            [refine-provider
             refine-model-map] (or (llm/select-provider rlm-router
                                     {:prefer        :intelligence
                                      :capabilities  #{:chat}
                                      :exclude-model root-model})
                                 (llm/select-provider rlm-router
                                   {:routing {:optimize :intelligence}}))
            refine-config    (when refine-provider
                               {:api-key       (:api-key refine-provider)
                                :base-url      (:base-url refine-provider)
                                :default-model (:name refine-model-map)})
            refine-model     (or (:name refine-model-map) root-model)
            refine-opts      (cond-> {:spec       spec
                                      :messages   [(llm/system (str "You are verifying and refining an answer to a specific query. "
                                                                 "Check the answer for accuracy, completeness, and correctness."))
                                                   (llm/user (str "<query>\n" query-str "\n</query>\n\n"
                                                               "<answer>\n" answer-as-str "\n</answer>"))]
                                      :config     refine-config
                                      :model      refine-model
                                      :iterations max-refinements
                                      :threshold  threshold}
                               (seq stored-docs) (assoc :documents stored-docs))
            raw-refine        (llm/refine! rlm-router refine-opts)
            _                 (merge-cost! (:tokens raw-refine) (:cost raw-refine))
            refined-str       (:result raw-refine)
            answer-unchanged? (or (:converged? raw-refine)
                                (= (str refined-str) answer-as-str))
            parsed            (if answer-unchanged?
                                answer-value
                                (if (and spec (string? refined-str))
                                  (try
                                    (spec/str->data-with-spec refined-str spec)
                                    (catch Exception e
                                      (trove/log! {:level :debug :data {:error (ex-message e)}
                                                   :msg   "Spec parse failed after refinement, retrying with fallback LLM"})
                                      (let [fallback (llm/ask! rlm-router
                                                       {:spec     spec
                                                        :messages [(llm/system "Extract structured data.")
                                                                   (llm/user (str "From:\n" refined-str))]
                                                        :routing  {:optimize :cost}})]
                                        (merge-cost! (:tokens fallback) (:cost fallback))
                                        (:result fallback))))
                                  refined-str))]
        {:answer           parsed
         :eval-scores      (:final-score raw-refine)
         :refinement-count (:iterations-count raw-refine)})
      {:answer           (if spec
                           (try
                             (spec/coerce-data-with-spec answer-value spec)
                             (catch Exception e
                               (trove/log! {:level :warn :data {:error (ex-message e)}
                                            :msg   "Spec coercion failed, returning uncoerced answer"})
                               answer-value))
                           answer-value)
       :eval-scores      nil
       :refinement-count 0})))

;; -----------------------------------------------------------------------------
;; Phase 4 - Update Q-values
;; -----------------------------------------------------------------------------

(defn- update-q-values!
  "Applies Q-value reward updates for cited/uncited pages."
  [{:keys [db-info-atom query-start-time max-iterations-atom]}
   {:keys [sources eval-scores confidence iterations max-iterations status]}]
  (try
    (if status
      ;; failure path - low reward for all accessed pages
      (let [accessed (rlm-db/pages-accessed-since @db-info-atom query-start-time)]
        (when (seq accessed)
          (rlm-db/finalize-q-updates! @db-info-atom accessed Q_REWARD_FAILURE)))
      ;; success path - differentiate cited vs uncited
      (let [accessed         (rlm-db/pages-accessed-since @db-info-atom query-start-time)
            cited-source-ids (set (or sources []))
            cited-page-ids   (when (and (seq cited-source-ids) (:conn @db-info-atom))
                               (let [conn (:conn @db-info-atom)]
                                 (set (d/q '[:find  [?pid ...]
                                             :in    $ [?sid ...]
                                             :where (or [?e :page.node/id ?sid]
                                                      [?e :page/id ?sid]
                                                      [?e :page.node/document-id ?sid])
                                             [?e :page.node/page-id ?pid]]
                                        (d/db conn) (vec cited-source-ids)))))
            max-iters        (or @max-iterations-atom max-iterations)
            high-reward      (compute-q-reward eval-scores confidence iterations max-iters)]
        (when (seq accessed)
          (if (seq cited-page-ids)
            (do
              (rlm-db/finalize-q-updates! @db-info-atom cited-page-ids high-reward)
              (let [uncited (set/difference accessed (or cited-page-ids #{}))]
                (when (seq uncited)
                  (rlm-db/finalize-q-updates! @db-info-atom uncited Q_REWARD_UNCITED))))
            (rlm-db/finalize-q-updates! @db-info-atom accessed Q_REWARD_UNCITED)))))
    (catch Exception e
      (trove/log! {:level :debug :data {:error (ex-message e)}
                   :msg   "Q-value update failed (non-fatal)"}))))

;; -----------------------------------------------------------------------------
;; Phase 5 - Finalize query result
;; -----------------------------------------------------------------------------

(defn- finalize-query-result
  "Persists claims, updates DB query record, emits debug log, builds result map."
  [{:keys [db-info-atom verify? claims-atom]}
   {:keys [query-ref start-time iterations status status-id trace locals
           answer raw-answer final-answer eval-scores refinement-count
           confidence sources reasoning total-tokens-atom total-cost-atom]}]
  (let [duration-ms (util/elapsed-since start-time)]
    (if status
      ;; failure path
      (do
        (rlm-core/rlm-debug! {:status status :iterations iterations :duration-ms duration-ms}
          "RLM query-env! finished (max iterations)")
        (try
          (rlm-db/update-query! @db-info-atom query-ref
            {:answer      (:result answer answer)
             :iterations  iterations
             :duration-ms duration-ms
             :status      status})
          (catch Exception e
            (trove/log! {:level :warn :data {:error (ex-message e)}
                         :msg   "Failed to update query (max iterations)"})))
        (cond-> {:answer      nil
                 :raw-answer  (:result answer answer)
                 :status      status
                 :status-id   status-id
                 :trace       trace
                 :iterations  iterations
                 :duration-ms duration-ms
                 :tokens      @total-tokens-atom
                 :cost        @total-cost-atom}
          (some? locals) (assoc :locals locals)
          verify?        (assoc :verified-claims (vec @claims-atom))))
      ;; success path
      (do
        (when (and verify? (seq @claims-atom))
          (let [db-info  @db-info-atom
                conn     (:conn db-info)
                query-id (util/uuid)]
            (doseq [claim @claims-atom]
              (try
                (d/transact! conn [(merge claim {:claim/id        (util/uuid)
                                                 :claim/query-id  query-id
                                                 :claim/verified? (boolean (get claim :claim/verified? true))})])
                (catch Exception e
                  (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Failed to store claim"}))))))
        (rlm-core/rlm-debug! {:iterations       iterations
                              :duration-ms      duration-ms
                              :refinement-count refinement-count
                              :confidence       confidence
                              :answer-preview   (rlm-db/str-truncate (pr-str final-answer) 200)}
          "RLM query-env! finished (success)")
        (try
          (rlm-db/update-query! @db-info-atom query-ref
            {:answer      final-answer
             :iterations  iterations
             :duration-ms duration-ms
             :status      :success
             :eval-score  eval-scores})
          (catch Exception e
            (trove/log! {:level :warn :data {:error (ex-message e)}
                         :msg   "Failed to update query (success)"})))
        (cond-> {:answer           final-answer
                 :raw-answer       raw-answer
                 :eval-scores      eval-scores
                 :refinement-count refinement-count
                 :trace            trace
                 :iterations       iterations
                 :duration-ms      duration-ms
                 :tokens           @total-tokens-atom
                 :cost             @total-cost-atom}
          (some? confidence) (assoc :confidence confidence)
          (seq sources)      (assoc :sources sources)
          (some? reasoning)  (assoc :reasoning reasoning)
          verify?            (assoc :verified-claims (vec @claims-atom)))))))

;; -----------------------------------------------------------------------------
;; Public entry point
;; -----------------------------------------------------------------------------

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

    Params:
    `env` - RLM environment from create-env.
    `messages` - Vector of message maps. Always a vector, e.g.:
                 [(llm/user \"What is schema therapy?\")]
                 [(llm/user \"Describe this\" (llm/image b64 \"image/png\"))]
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
                       ...]
          :carry \"accumulated carry text\"}
     - :iterations - Number of iterations used.
     - :duration-ms - Query duration in milliseconds.
     - :tokens - Token usage map {:input N :output N :total N}.
     - :cost - Cost map {:input-cost N :output-cost N :total-cost N}.
     - :eval-scores - Numeric evaluation score from refinement (nil if no refinement).
     - :refinement-count - Number of refinement iterations performed.
     - :confidence - Confidence level (:high/:medium/:low) from final iteration.
     - :sources - Vector of source IDs cited in the answer.
     - :verified-claims - Verified claims (only when :verify? true).
     - :reasoning - String summary of how the answer was derived (from LLM's FINAL call).
     - :status - Only present on failure, e.g. :max-iterations."
  ([env messages]
   (query-env! env messages {}))
  ([env messages opts]
   (let [ctx (prepare-query-context env messages opts)
         {:keys [max-recursion-depth eval-timeout-ms concurrency
                 debug? query-str root-model max-iterations verify?
                 plan? db-info-atom max-iterations-atom
                 query-start-time env-id]} ctx
         merged-concurrency (merge schema/DEFAULT_CONCURRENCY concurrency)]
     (binding [schema/*rlm-ctx*               {:rlm-env-id env-id :rlm-type :main
                                               :rlm-debug? debug? :rlm-phase :query}
               schema/*eval-timeout-ms*       (schema/clamp-eval-timeout-ms
                                                (or eval-timeout-ms schema/*eval-timeout-ms*))
               schema/*concurrency*           merged-concurrency
               schema/*concurrency-semaphore* (concurrency/make-reentrant-semaphore
                                                (:max-parallel-llm merged-concurrency))
               schema/*sub-rlm-deadline*      nil]
       (binding [schema/*max-recursion-depth* max-recursion-depth]
         (rlm-core/rlm-debug! {:query          query-str
                               :root-model     root-model
                               :max-iterations max-iterations
                               :verify?        verify?
                               :plan?          plan?
                               :refine-mode    :final-confidence}
           "RLM query-env! started")
         (let [start-time   (System/nanoTime)
               phase2       (run-iteration-phase ctx)
               {:keys [iteration-result query-ref
                       total-tokens-atom total-cost-atom merge-cost!]} phase2
               {iter-answer :answer
                trace       :trace
                iterations  :iterations
                status      :status
                status-id   :status-id
                locals      :locals
                confidence  :confidence
                sources     :sources
                reasoning   :reasoning} iteration-result
               ;; native Clojure value from FINAL (mirrors original answer-value)
               answer-value (:result iter-answer iter-answer)]
           (if status
             ;; failure - update Q with low reward, no refinement
             (do
               (update-q-values!
                 {:db-info-atom        db-info-atom
                  :query-start-time    query-start-time
                  :max-iterations-atom max-iterations-atom}
                 {:sources    sources :eval-scores nil :confidence confidence
                  :iterations iterations :max-iterations max-iterations
                  :status     status})
               (finalize-query-result
                 ctx
                 {:query-ref         query-ref
                  :start-time        start-time
                  :iterations        iterations
                  :status            status
                  :status-id         status-id
                  :trace             trace
                  :locals            locals
                  :answer            iter-answer
                  :raw-answer        nil
                  :final-answer      nil
                  :eval-scores       nil
                  :refinement-count  nil
                  :confidence        confidence
                  :sources           sources
                  :reasoning         reasoning
                  :total-tokens-atom total-tokens-atom
                  :total-cost-atom   total-cost-atom}))
             ;; success - refine first so eval-scores feed Q-reward
             (let [refine-result (run-refinement-phase
                                   (assoc ctx :merge-cost! merge-cost!)
                                   iter-answer confidence)
                   {:keys [answer
                           eval-scores
                           refinement-count]} refine-result]
               (update-q-values!
                 {:db-info-atom        db-info-atom
                  :query-start-time    query-start-time
                  :max-iterations-atom max-iterations-atom}
                 {:sources    sources :eval-scores eval-scores :confidence confidence
                  :iterations iterations :max-iterations max-iterations
                  :status     nil})
               (finalize-query-result
                 ctx
                 {:query-ref         query-ref
                  :start-time        start-time
                  :iterations        iterations
                  :status            nil
                  :status-id         nil
                  :trace             trace
                  :locals            nil
                  :answer            iter-answer
                  :raw-answer        answer-value
                  :final-answer      answer
                  :eval-scores       eval-scores
                  :refinement-count  refinement-count
                  :confidence        confidence
                  :sources           sources
                  :reasoning         reasoning
                  :total-tokens-atom total-tokens-atom
                  :total-cost-atom   total-cost-atom})))))))))
