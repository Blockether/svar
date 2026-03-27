(ns com.blockether.svar.internal.rlm.routing
  (:require
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.rlm.schema :refer [*max-recursion-depth*]]
   [taoensso.trove :as trove]))

(defn- with-depth-tracking
  "Executes f within recursion depth tracking. Returns error content map on max
   depth exceeded or on exception (surfaced to the LLM so it can adapt)."
  [depth-atom prefs f]
  (if (>= @depth-atom *max-recursion-depth*)
    {:content (str "Max recursion depth (" *max-recursion-depth* ") exceeded") :error true}
    (try
      (swap! depth-atom inc)
      (f)
      (catch Exception e
        (trove/log! {:level :warn :data {:error (ex-message e) :prefs prefs} :msg "llm-query failed"})
        {:content (str "ERROR: " (ex-message e)) :error true})
      (finally (swap! depth-atom dec)))))

(defn- call-hook!
  [hooks-atom hook-name phase data]
  (when-let [hooks (some-> hooks-atom deref)]
    (when-let [hook-fn (get-in hooks [hook-name phase])]
      (try (hook-fn data) (catch Exception _ nil)))))

(defn make-routed-llm-query-fn
  "Creates an llm-query function that routes across providers via a router.
   Errors are caught and returned as {:content \"ERROR: ...\" :error true} so the
   LLM can see them and adapt (e.g., retry with different approach, call FINAL).

   `prefs` — preferences map, e.g. {:strategy :root} or {:prefer :cost :capabilities #{:chat}}"
  [prefs depth-atom rlm-router & [{:keys [hooks-atom]}]]
  (fn llm-query
    ([prompt]
     (with-depth-tracking depth-atom prefs
       (fn []
         (call-hook! hooks-atom :llm-call :pre {:prompt prompt :opts nil})
         (let [result (llm/routed-chat-completion rlm-router [{:role "user" :content prompt}] prefs)]
           (call-hook! hooks-atom :llm-call :post {:prompt prompt :response (:content result) :reasoning (:reasoning result)})
           result))))
    ([prompt opts]
     (with-depth-tracking depth-atom prefs
       (fn []
         (call-hook! hooks-atom :llm-call :pre {:prompt prompt :opts opts})
         (let [result (if-let [spec (:spec opts)]
                        {:content (pr-str (:result (llm/ask! {:spec spec
                                                              :messages [(llm/user prompt)]
                                                              :router rlm-router
                                                              :prefer (:prefer prefs)
                                                              :strategy (:strategy prefs)
                                                              :capabilities (:capabilities prefs)})))}
                        (llm/routed-chat-completion rlm-router [{:role "user" :content prompt}] prefs))]
           (call-hook! hooks-atom :llm-call :post {:prompt prompt :response (:content result) :reasoning (:reasoning result)})
           result))))))

(defn resolve-root-model
  "Resolves the root model name from a router, or falls back to a default.
   Used for token counting (store-message!, truncate-messages)."
  [rlm-router]
  (when rlm-router
    (when-let [[_provider model-map] (llm/select-provider rlm-router {:strategy :root})]
      (:name model-map))))

(defn provider-has-reasoning?
  "Checks if the root provider has native reasoning (thinking) capability.

   Params:
   `rlm-router` - Router from llm/make-router, or nil.

   Returns:
   Boolean. True if the root model's :reasoning? flag is set."
  [rlm-router]
  (when rlm-router
    (when-let [[_provider model] (llm/select-provider rlm-router {:strategy :root})]
      (boolean (:reasoning? model)))))
