(ns com.blockether.svar.internal.rlm.sub
  "Sub-RLM iterated execution. Wraps the existing iteration-loop with a
   constructed system prompt (from skill bodies) and delegates to the parent
   env's SCI ctx. Shared sandbox — sub-RLM defs are visible to the parent.

   This is the Phase 4 primitive: when sub-rlm-query receives :max-iter > 1
   (with or without :skills), routing.clj delegates here instead of doing a
   single-shot llm/ask!.

   CYCLIC DEP NOTE: rlm.core requires rlm.routing which requires this ns. So
   we cannot :require rlm.core — it would create core → routing → sub → core.
   iteration-loop is injected at load time via set-iteration-loop! called
   from rlm.core (see bottom of that file). No per-call requiring-resolve."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [com.blockether.svar.internal.rlm.skills :as rlm-skills]
   [taoensso.trove :as trove]))

;; Wired at load time by rlm.core — see bottom of rlm/core.clj.
;; Holds the iteration-loop fn. Deref once per run-sub-rlm call.
(defonce ^:private iteration-loop-ref (atom nil))

(defn set-iteration-loop!
  "Called once by rlm.core to inject iteration-loop without a cyclic require.
   Idempotent — subsequent calls replace the reference."
  [f]
  (reset! iteration-loop-ref f))

(defn- iteration-loop-fn []
  (or @iteration-loop-ref
    (throw (ex-info "iteration-loop not wired — rlm.core must be loaded before sub-rlm calls"
             {:type :rlm/iteration-loop-unwired}))))

(defn- auto-refine-async!
  "Fires an async skill refinement pass after a sub-rlm-query with skills.
   Non-blocking — runs in a future, never delays the response.

   Checks:
   - Skills were loaded (not a bare sub-rlm-query)
   - Outcome was either failure (:status non-nil) or success with low confidence
   - db-info-atom and skill-registry-atom available for skill-manage

   Refinement uses a cheap single-shot sub-rlm-query to analyze what happened
   and patch/refine the skill accordingly."
  [rlm-env skills-loaded sub-result]
  (when (and (seq skills-loaded)
          (:skill-registry-atom rlm-env)
          (:db-info-atom rlm-env))
    (let [status (:status sub-result)
          confidence (get-in sub-result [:result :confidence])
          needs-refine? (or (some? status)                              ;; failed
                          (= confidence :low)                         ;; low confidence
                          (and (nil? (:content sub-result))           ;; no answer
                            (nil? (:result sub-result))))]
      (when needs-refine?
        (future
          (try
            (let [skill-name (first skills-loaded)
                  skill-registry-atom (:skill-registry-atom rlm-env)
                  skill (get @skill-registry-atom skill-name)
                  skill-manage-fn rlm-skills/skill-manage
                  trace-summary (cond-> {:status status
                                         :iterations (:iter sub-result)
                                         :confidence confidence}
                                  (:content sub-result)
                                  (assoc :answer-preview
                                    (let [c (:content sub-result)]
                                      (if (> (count c) 200) (subs c 0 200) c))))
                  ;; Use a cheap sub-rlm to analyze what went wrong/right
                  make-sub-rlm (iteration-loop-fn)
                  refine-prompt (str "A skill named :" (name skill-name)
                                  " was used and produced this outcome:\n"
                                  (pr-str trace-summary)
                                  "\n\nCurrent skill abstract: " (pr-str (:abstract skill))
                                  "\n\nCurrent skill body (procedure):\n" (:body skill)
                                  "\n\n---\nAnalyze the outcome. Return a JSON-like map with TWO keys:\n"
                                  "1. \"abstract\" — better abstract (≤200 chars). More accurate for what the skill does/when it works. Note limitations if it failed.\n"
                                  "2. \"body-patch\" — if the procedure needs fixing, return {\"old\": \"exact text to replace\", \"new\": \"replacement\"}. If procedure is fine, return null.\n"
                                  "Example: {\"abstract\": \"OCR image PDFs, fails on encrypted\", \"body-patch\": {\"old\": \"step 3 text\", \"new\": \"revised step 3\"}}\n"
                                  "Return ONLY the map, no explanation.")
                  refine-result (make-sub-rlm
                                  rlm-env
                                  refine-prompt
                                  {:max-iterations 1
                                   :cancel-atom (atom false)
                                   :max-consecutive-errors 1
                                   :max-restarts 0})
                  raw-answer (when-let [a (:answer refine-result)]
                               (str (if (map? a) (:result a) a)))
                  ;; Try to parse as EDN map
                  parsed (when (and raw-answer (not (str/blank? raw-answer)))
                           (try (edn/read-string raw-answer)
                                (catch Exception e
                                  (trove/log! {:level :debug :id ::skill-refine-edn-parse-fallback
                                               :data {:error (ex-message e)}
                                               :msg "Skill refine answer not valid EDN, skipping patch"})
                                  nil)))
                  new-abstract (when-let [a (get parsed "abstract")]
                                 (when (and (string? a) (not (str/blank? a)))
                                   (let [trimmed (str/trim a)]
                                     (if (> (count trimmed) 200)
                                       (str (subs trimmed 0 197) "...")
                                       trimmed))))
                  body-patch (get parsed "body-patch")]
              ;; Apply abstract refinement
              (when new-abstract
                (skill-manage-fn (:db-info-atom rlm-env) skill-registry-atom
                  :refine {:name skill-name :abstract new-abstract})
                (trove/log! {:level :info :id ::skill-auto-refined-abstract
                             :data {:skill skill-name
                                    :old-abstract (:abstract skill)
                                    :new-abstract new-abstract
                                    :trigger (or status :low-confidence)}
                             :msg "Skill abstract auto-refined"}))
              ;; Apply body patch if provided
              (when (and (map? body-patch) (get body-patch "old") (get body-patch "new"))
                (skill-manage-fn (:db-info-atom rlm-env) skill-registry-atom
                  :patch {:name skill-name
                          :old (get body-patch "old")
                          :new (get body-patch "new")})
                (trove/log! {:level :info :id ::skill-auto-refined-body
                             :data {:skill skill-name
                                    :trigger (or status :low-confidence)}
                             :msg "Skill body auto-patched"})))
            (catch Exception e
              (trove/log! {:level :warn :id ::skill-auto-refine-failed
                           :data {:error (ex-message e)
                                  :skills skills-loaded}
                           :msg "Skill auto-refine failed — non-blocking, swallowed"}))))))))

(defn run-sub-rlm
  "Runs an iterated sub-RLM query using the existing iteration-loop machinery.

   Reuses the parent env's SCI ctx (Option C — shared sandbox, no isolation).
   Sub-RLM def'd vars persist in the parent. This is intentional: the parent
   RLM can read sub-RLM outputs without explicit plumbing.

   After execution, fires async auto-refine for skills that failed or produced
   low-confidence results. Never blocks the response.

   Params:
   `rlm-env`  — parent RLM env (from create-env). Must have :sci-ctx, :router,
                 :db-info-atom.
   `prompt`   — string, the user query for the sub-RLM.
   `opts`     — map:
     :system-prompt   — string, prepended as system message. Typically skill
                         bodies concatenated. Nil → default RLM system prompt.
     :max-iter        — int, iteration cap (default 5).
     :cancel-atom     — atom, cooperative cancellation. Nil → fresh atom.
     :include-trace   — bool, include :trace in result (default false).

   Returns:
   {:content    <str|nil>       ; final answer text
    :code       <vec<str>|nil>  ; code blocks from the final iteration
    :result     <map|nil>       ; {:answer :confidence :sources :reasoning} when final
    :iter       <int>           ; total iterations executed
    :tokens     <map|nil>       ; {:input :output :reasoning :cached :total}
    :cost       <map|nil>       ; {:input-cost :output-cost :total-cost :model}
    :trace      <vec|nil>       ; iteration trace, only when :include-trace
    :status     <kw|nil>        ; nil on success, :max-iterations, :cancelled, :error-budget-exhausted
    :skills-loaded <vec|nil>}"
  [rlm-env prompt {:keys [system-prompt max-iter cancel-atom
                          include-trace skills-loaded]
                   :or   {max-iter 5}}]
  (trove/log! {:level :info :id ::sub-rlm-start
               :data {:prompt-len (count prompt)
                      :max-iter max-iter
                      :has-system-prompt (some? system-prompt)
                      :skills-loaded skills-loaded}
               :msg "Sub-RLM iterated query starting"})
  (let [iteration-loop (iteration-loop-fn)
        result (iteration-loop
                 rlm-env
                 prompt
                 {:system-prompt          system-prompt
                  :max-iterations         max-iter
                  :cancel-atom            (or cancel-atom (atom false))
                  :max-consecutive-errors 3
                  :max-restarts           1})
        answer-data (:answer result)
        answer-str (when answer-data
                     (if (map? answer-data)
                       (str (:result answer-data))
                       (str answer-data)))
        last-iter-execs (when-let [trace (:trace result)]
                          (:executions (last trace)))
        code (when (seq last-iter-execs)
               (let [blocks (mapv :code last-iter-execs)]
                 (when (seq blocks) blocks)))
        sub-result (cond-> {:content       answer-str
                            :code          code
                            :result        (when answer-data
                                             {:answer     answer-str
                                              :confidence (:confidence result)
                                              :sources    (:sources result)
                                              :reasoning  (:reasoning result)})
                            :iter          (or (:iterations result) 0)
                            :tokens        (:tokens result)
                            :cost          (:cost result)
                            :status        (:status result)
                            :skills-loaded skills-loaded}
                     include-trace (assoc :trace (:trace result)))]
    (trove/log! {:level :info :id ::sub-rlm-done
                 :data {:iter (:iterations result)
                        :status (:status result)
                        :has-answer (some? answer-str)
                        :code-blocks (count code)}
                 :msg "Sub-RLM iterated query done"})
    ;; Fire async auto-refine — never blocks the response
    (auto-refine-async! rlm-env skills-loaded sub-result)
    sub-result))
