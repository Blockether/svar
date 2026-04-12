(ns com.blockether.svar.internal.rlm.routing
  (:require
   [clojure.string :as str]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.rlm.schema :refer [*max-recursion-depth* SUB_RLM_QUERY_SPEC]]
   [com.blockether.svar.internal.rlm.sub :as rlm-sub]
   [taoensso.trove :as trove]))

(def ^:private MAX_SKILLS_PER_CALL
  "Hard ceiling on :skills vec length per sub-rlm-query call."
  2)

(defn- with-depth-tracking
  "Executes f within recursion depth tracking. Returns error content map on max
   depth exceeded or on exception (surfaced to the LLM so it can adapt).

   Uses swap-vals! (atomic old+new pair, side-effect-free) so concurrent
   sub-rlm-query-batch calls cannot race past *max-recursion-depth*.
   Never put side effects inside swap!'s fn — it retries on CAS contention."
  [depth-atom prefs f]
  (let [limit (long *max-recursion-depth*)
        [old-d new-d] (swap-vals! depth-atom
                        (fn [d]
                          (if (>= (long d) limit)
                            d
                            (inc (long d)))))
        acquired? (> (long new-d) (long old-d))]
    (if-not acquired?
      {:content (str "Max recursion depth (" limit ") exceeded") :error true}
      (try
        (f)
        (catch Exception e
          (trove/log! {:level :warn :data {:error (ex-message e) :prefs prefs} :msg "sub-rlm-query failed"})
          {:content (str "ERROR: " (ex-message e)) :error true})
        (finally (swap! depth-atom dec))))))

(defn- resolve-skill-messages
  "Resolves :skills opts into a vec of system messages prepended to the ask! call.
   Validates skill names, enforces MAX_SKILLS_PER_CALL. Returns [messages skills-loaded]
   or throws on validation error."
  [skills skill-registry]
  (when (and (seq skills) skill-registry)
    (let [deduped (vec (distinct skills))]
      (when (> (count deduped) MAX_SKILLS_PER_CALL)
        (throw (ex-info (str "Too many skills (" (count deduped) "), max " MAX_SKILLS_PER_CALL)
                 {:type :rlm/too-many-skills :skills deduped :max MAX_SKILLS_PER_CALL})))
      (let [resolved (mapv (fn [skill-name]
                             (let [skill (get skill-registry skill-name)]
                               (when-not skill
                                 (throw (ex-info (str "Unknown skill: " skill-name)
                                          {:type :rlm/unknown-skill :skill skill-name
                                           :available (vec (keys skill-registry))})))
                               skill))
                       deduped)
            body (str/join "\n\n---\n\n"
                   (mapv :body resolved))]
        [{:role "system" :content body} deduped]))))

(defn make-routed-sub-rlm-query-fn
  "Creates a sub-rlm-query function that routes across providers via a router.

   Output contract: {:content <str> :code <vec<str>|nil> :skills-loaded <vec|nil> ...}
     - :content is the prose answer (always present, SUB_RLM_QUERY_SPEC enforced).
     - :code is a vec of Clojure expressions when the sub-LLM emits them, else nil.
     - :skills-loaded echoes which skills were injected (nil when no skills).

   Internals: always calls llm/ask! with SUB_RLM_QUERY_SPEC. Provider-enforced
   JSON output guarantees the shape.

   When opts contains :skills [...], the corresponding skill bodies are
   prepended as system messages. Max 2 skills per call. Unknown skill → error.

   `routing` — default routing opts, e.g. {} or {:optimize :cost}.
   `skill-registry-atom` — atom holding {skill-keyword → skill-def} or nil.
   `rlm-env-atom` — atom holding the parent rlm-env (needed for iterated path).
                     Set after env construction via reset!. Nil → iterated path unavailable."
  [routing depth-atom rlm-router skill-registry-atom rlm-env-atom]
  (fn sub-rlm-query
    ([prompt] (sub-rlm-query prompt {}))
    ([prompt opts]
     (with-depth-tracking depth-atom routing
       (fn []
         (let [call-routing (merge routing (:routing opts {}))
               ;; Skills load ONLY at depth 0 (main RLM → sub-rlm-query).
               ;; Sub-RLMs cannot load skills into sub-sub-RLMs.
               at-root? (zero? @depth-atom)
               [skill-msg skills-loaded] (when (and at-root? (seq (:skills opts)))
                                           (resolve-skill-messages (vec (:skills opts)) (when skill-registry-atom @skill-registry-atom)))
               max-iter (or (:max-iter opts) 1)
               iterated? (> max-iter 1)]
           ;; ITERATED PATH: delegate to run-sub-rlm (reuses iteration-loop)
           (if (and iterated? rlm-env-atom @rlm-env-atom)
             (let [skill-system-prompt (when skill-msg (:content skill-msg))]
               (rlm-sub/run-sub-rlm
                 @rlm-env-atom
                 prompt
                 {:system-prompt  skill-system-prompt
                  :max-iter       max-iter
                  :cancel-atom    (:cancel-atom opts)
                  :routing        call-routing
                  :include-trace  (:include-trace opts)
                  :skills-loaded  skills-loaded}))
             ;; SINGLE-SHOT PATH: llm/ask! with SUB_RLM_QUERY_SPEC
             (let [messages (cond-> []
                              skill-msg (conj skill-msg)
                              true (conj {:role "user" :content prompt}))]
               (trove/log! {:level :info :id ::sub-rlm-call
                            :data {:depth @depth-atom
                                   :prompt-len (count prompt)
                                   :routing call-routing
                                   :skills-loaded skills-loaded}
                            :msg "Sub-RLM query (sub-rlm-query)"})
               (let [r (llm/ask! rlm-router
                         {:spec SUB_RLM_QUERY_SPEC
                          :messages messages
                          :routing call-routing
                          :check-context? false})
                     parsed (:result r)
                     code (when-let [c (:code parsed)]
                            (let [v (vec c)]
                              (when (seq v) v)))
                     result {:content (:content parsed)
                             :code code
                             :skills-loaded skills-loaded
                             :iter 1
                             :tokens (:tokens r)
                             :reasoning (:reasoning r)
                             :routed/provider-id (:routed/provider-id r)
                             :routed/model (:routed/model r)
                             :routed/base-url (:routed/base-url r)}]
                 (trove/log! {:level :info :id ::sub-rlm-response
                              :data {:depth @depth-atom
                                     :content-len (count (str (:content result)))
                                     :code-blocks (count (:code result))
                                     :skills-loaded skills-loaded}
                              :msg "Sub-RLM response"})
                 result)))))))))

(defn resolve-root-model
  "Resolves the root model name from a router, or falls back to a default.
   Used for token counting and cost estimation."
  [rlm-router]
  (when rlm-router
    (when-let [[_provider model-map] (llm/select-provider rlm-router {:strategy :root})]
      (:name model-map))))

(defn provider-has-reasoning?
  "Checks if the root provider has native reasoning (thinking) capability.

   Params:
   `rlm-router` - Router from llm/make-router, or nil.

   Returns:
   Boolean. True if the root model has :reasoning-params set."
  [rlm-router]
  (when rlm-router
    (when-let [[_provider model] (llm/select-provider rlm-router {:strategy :root})]
      (boolean (seq (:reasoning-params model))))))
