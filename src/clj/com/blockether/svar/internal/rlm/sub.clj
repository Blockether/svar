(ns com.blockether.svar.internal.rlm.sub
  "Sub-RLM iterated execution. Wraps the existing iteration-loop with a
   constructed system prompt (from skill bodies) and delegates to the parent
   env's SCI ctx. Shared sandbox — sub-RLM defs are visible to the parent.

   This is the Phase 4 primitive: when sub-rlm-query receives :max-iter > 1
   (with or without :skills), routing.clj delegates here instead of doing a
   single-shot llm/ask!.

   NOTE: does NOT require rlm.core to avoid cyclic dep (core→routing→sub→core).
   Instead, iteration-loop-fn is resolved lazily via requiring-resolve."
  (:require
   [clojure.string :as str]
   [taoensso.trove :as trove]))

(defn- resolve-iteration-loop
  "Lazily resolves rlm.core/iteration-loop to break the cyclic dependency.
   Cached after first resolve by Clojure's requiring-resolve."
  []
  @(requiring-resolve 'com.blockether.svar.internal.rlm.core/iteration-loop))

(defn- resolve-skill-manage
  "Lazily resolves rlm.skills/skill-manage to break import dep."
  []
  @(requiring-resolve 'com.blockether.svar.internal.rlm.skills/skill-manage))

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
                  skill-manage-fn (resolve-skill-manage)
                  trace-summary (cond-> {:status status
                                         :iterations (:iter sub-result)
                                         :confidence confidence}
                                  (:content sub-result)
                                  (assoc :answer-preview
                                    (let [c (:content sub-result)]
                                      (if (> (count c) 200) (subs c 0 200) c))))
                  ;; Use a cheap sub-rlm to analyze what went wrong/right
                  make-sub-rlm (resolve-iteration-loop)
                  refine-prompt (str "A skill named :" (name skill-name)
                                  " was used and produced this outcome:\n"
                                  (pr-str trace-summary)
                                  "\n\nCurrent skill abstract: " (pr-str (:abstract skill))
                                  "\n\nBased on the outcome, write a BETTER abstract (≤200 chars) "
                                  "that more accurately describes what this skill does and when it works. "
                                  "If the skill failed, note the limitation. "
                                  "Return ONLY the new abstract text, nothing else.")
                  refine-result (make-sub-rlm
                                  rlm-env
                                  refine-prompt
                                  {:max-iterations 1
                                   :cancel-atom (atom false)
                                   :max-consecutive-errors 1
                                   :max-restarts 0})
                  new-abstract (when-let [a (:answer refine-result)]
                                 (let [s (str (if (map? a) (:result a) a))]
                                   (when-not (str/blank? s)
                                     (let [trimmed (str/trim s)]
                                       (if (> (count trimmed) 200)
                                         (str (subs trimmed 0 197) "...")
                                         trimmed)))))]
              (when new-abstract
                (skill-manage-fn (:db-info-atom rlm-env) skill-registry-atom
                  :refine {:name skill-name :abstract new-abstract})
                (trove/log! {:level :info :id ::skill-auto-refined
                             :data {:skill skill-name
                                    :old-abstract (:abstract skill)
                                    :new-abstract new-abstract
                                    :trigger (or status :low-confidence)}
                             :msg "Skill auto-refined after execution"})))
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
  (let [iteration-loop (resolve-iteration-loop)
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
