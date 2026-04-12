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
   [taoensso.trove :as trove]))

(defn- resolve-iteration-loop
  "Lazily resolves rlm.core/iteration-loop to break the cyclic dependency.
   Cached after first resolve by Clojure's requiring-resolve."
  []
  @(requiring-resolve 'com.blockether.svar.internal.rlm.core/iteration-loop))

(defn run-sub-rlm
  "Runs an iterated sub-RLM query using the existing iteration-loop machinery.

   Reuses the parent env's SCI ctx (Option C �� shared sandbox, no isolation).
   Sub-RLM def'd vars persist in the parent. This is intentional: the parent
   RLM can read sub-RLM outputs without explicit plumbing.

   Params:
   `rlm-env`  — parent RLM env (from create-env). Must have :sci-ctx, :router,
                 :db-info-atom.
   `prompt`   — string, the user query for the sub-RLM.
   `opts`     — map:
     :system-prompt   — string, prepended as system message. Typically skill
                         bodies concatenated. Nil → default RLM system prompt.
     :max-iter        — int, iteration cap (default 5).
     :cancel-atom     — atom, cooperative cancellation. Nil �� fresh atom.
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
                 (when (seq blocks) blocks)))]
    (trove/log! {:level :info :id ::sub-rlm-done
                 :data {:iter (:iterations result)
                        :status (:status result)
                        :has-answer (some? answer-str)
                        :code-blocks (count code)}
                 :msg "Sub-RLM iterated query done"})
    (cond-> {:content       answer-str
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
      include-trace (assoc :trace (:trace result)))))
