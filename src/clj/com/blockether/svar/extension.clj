(ns com.blockether.svar.extension
  "Extension-facing helpers for router limits, parse diagnostics, and
   provider-state provenance handoff. These functions keep extension/TUI code
   out of svar internals while preserving the full forensic state needed to
   resume provider-specific conversations."
  (:require
   [com.blockether.svar.internal.jsonish :as jsonish]
   [com.blockether.svar.internal.spec :as spec])
  (:import
   (java.util UUID)))

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

;; =============================================================================
;; Provenance lifecycle + refs
;; =============================================================================

(defn- response-provider-state
  [x]
  (or (:provider-state x)
    (some->> (:format-attempts x)
      reverse
      (keep :provider-state)
      first)
    (when (= :openai-responses (:provider x)) x)))

(defn- stable-provenance-id
  [provider-state]
  (str "svar-prov-"
    (UUID/nameUUIDFromBytes (.getBytes (pr-str provider-state) "UTF-8"))))

(defn provenance-ref
  "Builds a stable provenance reference from an `ask!`/`ask-code!` result or
   a raw `:provider-state` map.

   The returned map intentionally carries the full `:provider-state`. Extension
   code can persist it under `:id`, then resume a later call with:
   `{:provider-state (:provider-state ref)}`. Returns nil when no provider
   continuation state is present."
  ([x]
   (provenance-ref x nil))
  ([x {:keys [id created-at-ms]}]
   (when-let [provider-state (response-provider-state x)]
     (let [items (:reasoning-items provider-state)]
       {:id (or id (stable-provenance-id provider-state))
        :type :svar.provenance/provider-state
        :provider (:provider provider-state)
        :created-at-ms (or created-at-ms (System/currentTimeMillis))
        :items-count (count items)
        :item-ids (->> items (keep :id) vec)
        :provider-state provider-state}))))

(defn provenance-lifecycle
  "Returns provenance lifecycle data for extension hosts.

   Zero-arity returns the contract. One-arity inspects a result/provider-state
   and returns capture/resume data. Resume by merging `:resume-opts` into the
   next svar call opts. Clear by omitting `:provider-state`."
  ([]
   {:stages [{:stage :capture
              :description "Read :provider-state from ask!/ask-code! result."}
             {:stage :persist
              :description "Store (provenance-ref result) by :id in extension state."}
             {:stage :resume
              :description "Pass {:provider-state (:provider-state ref)} on the next call."}
             {:stage :clear
              :description "Drop the ref or omit :provider-state to start fresh."}]
    :ref-key :provider-state
    :ref-fn 'com.blockether.svar.extension/provenance-ref})
  ([x]
   (let [ref (provenance-ref x)]
     {:available? (boolean ref)
      :stage (if ref :captured :absent)
      :ref ref
      :resume-opts (when ref {:provider-state (:provider-state ref)})
      :lifecycle (provenance-lifecycle)})))

