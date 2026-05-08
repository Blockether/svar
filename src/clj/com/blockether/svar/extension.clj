(ns com.blockether.svar.extension
  "Extension-facing helpers for router limits and parse diagnostics.

   Preserved-thinking handoff is provider-agnostic via the
   `:assistant-message` field on `ask!` / `ask-code!` results. The
   value is a canonical svar message — `{:role \"assistant\" :content
   [<canonical-blocks>]}` — which callers append to `:messages` on
   the next call. Canonical `{:type \"thinking\"}` content blocks
   carry the per-provider preserved-reasoning state under
   `:thinking-signature`; svar's wire serializers transform them into
   native shapes (Anthropic signed thinking blocks, z.ai
   `reasoning_content` field, OpenAI Responses reasoning input items).
   Plain chat models without preserved thinking just don't surface
   `:assistant-message`, so the same caller pipeline
   `(keep :assistant-message results)` works uniformly across every
   provider with zero per-provider branching."
  (:require
   [com.blockether.svar.internal.jsonish :as jsonish]
   [com.blockether.svar.internal.spec :as spec]))

;; =============================================================================
;; Provider limits
;; =============================================================================

(defn- now-ms
  [router]
  (if-let [clock (:clock router)]
    (clock)
    (System/currentTimeMillis)))

(defn- entry-ts
  [entry]
  (long (if (map? entry) (:ts entry) entry)))

(defn- prune-window
  [router entries]
  (let [cutoff (- (long (now-ms router)) (long (:window-ms router 60000)))]
    (filterv (fn [entry]
               (> (long (entry-ts entry)) (long cutoff)))
      (or entries []))))

(defn- sum-token-window
  [entries]
  (long (reduce + 0 (map #(long (or (:n %) 0)) entries))))

(defn- reset-ms
  [router entries used limit]
  (if (and (pos? (long limit)) (>= (long used) (long limit)) (seq entries))
    (let [window-ms (long (:window-ms router 60000))
          now       (long (now-ms router))
          oldest    (long (reduce (fn [^long acc entry]
                                    (min acc (long (entry-ts entry))))
                            Long/MAX_VALUE
                            entries))
          delta     (long (- (+ oldest window-ms) now))]
      (max 0 (long delta)))
    0))

(defn- circuit-state
  [router provider-state]
  (let [state (or (:cb-state provider-state) :closed)]
    (if (and (= state :open)
          (:cb-open-until provider-state)
          (>= (long (now-ms router)) (long (:cb-open-until provider-state))))
      :half-open
      state)))

(defn- budget-limits
  [router]
  (when (:budget router)
    (let [limit (:budget router)
          spent (or (some-> (:budget-state router) deref)
                  {:total-tokens 0 :total-cost 0.0})
          max-tokens (:max-tokens limit)
          max-cost (:max-cost limit)]
      (cond-> {:limit limit
               :spent spent}
        max-tokens
        (assoc-in [:remaining :tokens]
          (max 0 (- (long max-tokens) (long (:total-tokens spent 0)))))

        max-cost
        (assoc-in [:remaining :cost]
          (max 0.0 (- (double max-cost) (double (:total-cost spent 0.0)))))))))

(defn provider-limits
  "Returns extension-friendly provider limit state for a svar router.

   Shape:
   {:window-ms N
    :providers {provider-id {:rpm {:limit N :used N :remaining N :reset-ms N}
                             :tpm {:limit N :used N :remaining N :reset-ms N}
                             :circuit-breaker :closed|:open|:half-open
                             :models [...]}}
    :budget {:limit ... :spent ... :remaining ...}}

   This is read-only. It does not claim a request slot or mutate router state."
  [router]
  (let [state (or (some-> (:state router) deref) {})
        providers
        (reduce
          (fn [acc provider]
            (let [pid (:id provider)
                  ps (get state pid {})
                  requests (prune-window router (:requests ps))
                  tokens (prune-window router (:tokens ps))
                  rpm-limit (long (:rpm provider Long/MAX_VALUE))
                  tpm-limit (long (:tpm provider Long/MAX_VALUE))
                  rpm-used (long (count requests))
                  tpm-used (long (sum-token-window tokens))]
              (assoc acc pid
                {:rpm {:limit rpm-limit
                       :used rpm-used
                       :remaining (max 0 (- rpm-limit rpm-used))
                       :reset-ms (reset-ms router requests rpm-used rpm-limit)}
                 :tpm {:limit tpm-limit
                       :used tpm-used
                       :remaining (max 0 (- tpm-limit tpm-used))
                       :reset-ms (reset-ms router tokens tpm-used tpm-limit)}
                 :circuit-breaker (circuit-state router ps)
                 :cb-failures (or (:cb-failures ps) 0)
                 :models (mapv :name (:models provider))})))
          {}
          (:providers router))]
    (cond-> {:window-ms (:window-ms router 60000)
             :providers providers}
      (:budget router) (assoc :budget (budget-limits router)))))

;; =============================================================================
;; Parse diagnostics
;; =============================================================================

(defn- exception->data
  [^Throwable e]
  (merge {:message (ex-message e)
          :class (-> e class .getName)}
    (when (instance? clojure.lang.ExceptionInfo e)
      (ex-data e))))

(defn parse-diagnose
  "Diagnoses how svar would parse `text`, optionally against `spec-def`.

   With no spec, returns SAP parser value + warnings. With a spec, runs the
   full spec-aware path (`str->data-with-spec`) and validation. Exceptions are
   captured into `:error`; this function is meant for extension/TUI diagnosis,
   so it returns data instead of throwing on parse/schema failures."
  ([text]
   (parse-diagnose text nil))
  ([text spec-def]
   (try
     (let [{:keys [value warnings] :as parsed} (jsonish/parse-json text)]
       (if spec-def
         (try
           (let [result (spec/str->data-with-spec text spec-def)
                 validation (spec/validate-data spec-def result)]
             {:ok? (:valid? validation)
              :phase :schema
              :parse parsed
              :value value
              :warnings warnings
              :result result
              :validation validation})
           (catch Throwable e
             {:ok? false
              :phase :schema
              :parse parsed
              :value value
              :warnings warnings
              :error (exception->data e)}))
         {:ok? true
          :phase :parse
          :parse parsed
          :value value
          :warnings warnings}))
     (catch Throwable e
       {:ok? false
        :phase :parse
        :error (exception->data e)}))))


