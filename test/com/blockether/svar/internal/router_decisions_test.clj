(ns com.blockether.svar.internal.router-decisions-test
  "End-to-end router decision tests: `resolve-routing` branches, circuit
   breaker state machine, provider fallback, and correctness of the three
   selection dials — `:cost` by real pricing, `:intelligence` across the
   fleet, and `:reasoning` as a selection filter.

   All tests use a deterministic clock (`:clock (fn [] @clock-atom)`) and
   capture-style mocks for the `f` passed to `with-provider-fallback`, so
   we never hit real networks."
  (:require
   [lazytest.core :refer [defdescribe describe expect it throws?]]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.router :as router]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- mock-clock
  "Returns [clock-fn clock-atom]. `@clock-atom` is the current ms reading."
  []
  (let [a (atom 0)]
    [(fn [] @a) a]))

(defn- advance! [clock-atom ms]
  (swap! clock-atom + ms))

(defn- success-result
  "Canned LLM response that `with-provider-fallback`'s `f` can return."
  [token-count]
  {:result {:answer "ok"}
   :api-usage {:input-tokens 10 :output-tokens 20 :total_tokens token-count}
   :tokens {:total token-count}})

(defn- transient-error
  "Throws an ex-info that `router-transient-error?` will classify as retryable."
  [status]
  (throw (ex-info (str "HTTP " status)
           {:type :svar.core/http-error :status status})))

(defn- model-unsupported-error
  "Throws the shape `with-provider-fallback` sees when an endpoint rejects the
   selected model: a 400 carrying the upstream body on `:body`."
  ([] (model-unsupported-error "The model `grok-code-fast-1` is not supported."))
  ([body]
   (throw (ex-info "Exceptional status code: 400"
            {:type :svar.core/http-error :status 400 :body body}))))

(defn- auth-400-error
  "A 400 that is NOT a model problem (bad credentials). Must NOT be treated as
   model-unsupported, otherwise a credential bug churns the whole fleet."
  []
  (throw (ex-info "Exceptional status code: 400"
           {:type :svar.core/http-error :status 400
            :body "bad request: Authorization header is badly formatted"})))

(defn- stream-truncated-error
  "Throws the zero-content SSE EOF failure seen when a provider closes
   stream after an error event but before a terminal marker."
  ([] (stream-truncated-error 0))
  ([content-len]
   (throw (ex-info "Stream ended before terminal marker."
            (cond-> {:type :svar.core/stream-truncated
                     :stream? true
                     :content-acc-len content-len}
              (pos? (long content-len)) (assoc :partial-content "x"))))))

;; =============================================================================
;; Tier 1 — resolve-routing branches
;; =============================================================================

(defdescribe resolve-routing-branches-test
  "Every `cond` branch of `resolve-routing` must produce the right prefs. This
   is the public entrypoint for `:routing` opts, so every public call depends
   on this translation being correct."

  (let [router (llm/make-router
                 [{:id :prov-a :api-key "k" :base-url "http://a"
                   :models [{:name "m-a1"} {:name "m-a2"}]}
                  {:id :prov-b :api-key "k" :base-url "http://b"
                   :models [{:name "m-b1"}]}])]

    (it "no opts → `:strategy :root`"
      (let [{:keys [prefs error-strategy]} (router/resolve-routing router {})]
        (expect (= :root (:strategy prefs)))
        (expect (= :hybrid error-strategy))))

    (it "`:optimize :intelligence` only → `{:prefer :intelligence}`"
      (let [{:keys [prefs]} (router/resolve-routing router {:optimize :intelligence})]
        (expect (= :intelligence (:prefer prefs)))
        (expect (nil? (:strategy prefs)))))

    (it "`:model` only → `:force-model` + strategy :root"
      (let [{:keys [prefs]} (router/resolve-routing router {:model "m-b1"})]
        (expect (= "m-b1" (:force-model prefs)))
        (expect (= :root (:strategy prefs)))))

    (it "`:provider` only → `:force-provider` + default `:prefer :cost`"
      (let [{:keys [prefs]} (router/resolve-routing router {:provider :prov-b})]
        (expect (= :prov-b (:force-provider prefs)))
        (expect (= :cost (:prefer prefs)))))

    (it "`:provider` + `:optimize` → `:force-provider` + `:prefer` from optimize"
      (let [{:keys [prefs]} (router/resolve-routing router
                              {:provider :prov-a :optimize :speed})]
        (expect (= :prov-a (:force-provider prefs)))
        (expect (= :speed (:prefer prefs)))))

    (it "`:provider` + `:model` exact → both force-* set"
      (let [{:keys [prefs]} (router/resolve-routing router
                              {:provider :prov-a :model "m-a2"})]
        (expect (= :prov-a (:force-provider prefs)))
        (expect (= "m-a2" (:force-model prefs)))))

    (it "`:provider` + unknown-provider throws :svar/routing-resolution-failed"
      (expect (throws? clojure.lang.ExceptionInfo
                #(router/resolve-routing router
                   {:provider :nonexistent :model "m-a1"}))))

    (it "`:provider` + model-not-in-provider throws"
      (expect (throws? clojure.lang.ExceptionInfo
                #(router/resolve-routing router
                   {:provider :prov-a :model "m-b1"})))) ;; m-b1 is in :prov-b

    (it "`:on-transient-error` overrides default :hybrid"
      (let [{:keys [error-strategy]} (router/resolve-routing router
                                       {:on-transient-error :fail})]
        (expect (= :fail error-strategy))))

    (it "`:reasoning` sets `:require-reasoning? true` in prefs"
      (let [{:keys [prefs]} (router/resolve-routing router
                              {:optimize :cost :reasoning :deep})]
        (expect (true? (:require-reasoning? prefs)))
        (expect (= :cost (:prefer prefs)))))

    (it "absent `:reasoning` leaves `:require-reasoning?` unset"
      (let [{:keys [prefs]} (router/resolve-routing router {:optimize :cost})]
        (expect (nil? (:require-reasoning? prefs)))))

    (it "`:on-chunk` is threaded into prefs so live routing events surface"
      ;; Caller's `:on-chunk` (set by every routed entrypoint like
      ;; `ask-code!`) must reach `with-provider-fallback`'s prefs map
      ;; verbatim; otherwise routing events fire only in the final
      ;; `:routed/trace` and the TUI sees no progress during 429 retry
      ;; sleeps. Regression guard for LLM_SPEC "emitted live to caller".
      (let [cb (fn [_e])
            {:keys [prefs]} (router/resolve-routing router
                              {:optimize :cost :on-chunk cb})]
        (expect (identical? cb (:on-chunk prefs)))))

    (it "absent `:on-chunk` leaves prefs without one"
      (let [{:keys [prefs]} (router/resolve-routing router {:optimize :cost})]
        (expect (not (contains? prefs :on-chunk)))))))

;; =============================================================================
;; Tier 1 — with-provider-fallback happy/sad paths
;; =============================================================================

(defdescribe force-provider-pinning-test
  "Regression: `:force-provider` must actually RESTRICT selection to the
   pinned provider, even when another (higher-priority) provider exposes the
   same model name. Pre-fix the key was set in prefs but read nowhere, so the
   pin was silently ignored and the first-priority provider won — meaning
   `{:routing {:provider :openai}}` could be served by a different provider."
  ;; Both providers expose the SAME model name; :first has higher priority
  ;; (vector order), so without honoring the pin it always wins selection.
  (let [router (llm/make-router
                 [{:id :first  :api-key "k" :base-url "http://1" :models [{:name "shared"}]}
                  {:id :second :api-key "k" :base-url "http://2" :models [{:name "shared"}]}])]

    (it "provider + model pin routes to the pinned (lower-priority) provider"
      (let [{:keys [prefs]} (router/resolve-routing router {:provider :second :model "shared"})
            [prov _] (router/select-provider router prefs)]
        (expect (= :second (:id prov)))))

    (it "provider-only pin (no model) routes to the pinned provider"
      (let [{:keys [prefs]} (router/resolve-routing router {:provider :second})
            [prov _] (router/select-provider router prefs)]
        (expect (= :second (:id prov)))))

    (it "unpinned selection still allows the higher-priority provider"
      (let [{:keys [prefs]} (router/resolve-routing router {:model "shared"})
            [prov _] (router/select-provider router prefs)]
        (expect (= :first (:id prov)))))))

(defdescribe provider-pin-honors-optimize-test
  "`{:provider X :optimize Y}` must select the OPTIMIZED model WITHIN the pinned
   provider — not silently fall back to the provider's root model. Used by
   per-provider 'smallest model' preferences (e.g. auto-titling on a flat-fee
   coding plan: pin the plan, pick its cheapest/fastest model)."
  (let [router (llm/make-router
                 [{:id :plan :api-key "k" :base-url "http://p"
                   :models [{:name "big"   :cost :high}
                            {:name "small" :cost :low}]}])]

    (it "provider + :optimize :cost picks the cheapest model, not the root"
      (let [{:keys [prefs]} (router/resolve-routing router
                              {:provider :plan :optimize :cost})
            [_ model] (router/select-provider router prefs)]
        (expect (nil? (:strategy prefs)))
        (expect (= "small" (:name model)))))

    (it "provider alone still resolves to the root (first) model"
      (let [{:keys [prefs]} (router/resolve-routing router {:provider :plan})
            [_ model] (router/select-provider router prefs)]
        (expect (= :root (:strategy prefs)))
        (expect (= "big" (:name model)))))))

(defdescribe prefer-providers-ordered-chain-test
  "`{:prefer-providers [...]}` walks a declared provider order, picking each
   provider's `:optimize`-best model, and falls through natively on failure.
   Framework primitive that replaces caller-side per-provider retry loops."
  (let [router (llm/make-router
                 ;; Vector order (priority) is DELIBERATELY the reverse of the
                 ;; preference below, to prove provider-order beats priority.
                 [{:id :copilot :api-key "k" :base-url "http://c"
                   :models [{:name "cop-big" :cost :high} {:name "cop-small" :cost :low}]}
                  {:id :anthropic :api-key "k" :base-url "http://a"
                   :models [{:name "opus" :cost :high} {:name "haiku" :cost :low}]}
                  {:id :zai :api-key "k" :base-url "http://z"
                   :models [{:name "glm-turbo" :cost :low}]}])]

    (it "resolve-routing translates :prefer-providers → :provider-order + :prefer"
      (let [{:keys [prefs]} (router/resolve-routing router
                              {:prefer-providers [:zai :anthropic] :optimize [:cost :speed]})]
        (expect (= [:zai :anthropic] (:provider-order prefs)))
        (expect (= [:cost :speed] (:prefer prefs)))
        (expect (nil? (:strategy prefs)))))

    (it "selects the first preferred provider's cheapest model, ignoring priority"
      (let [{:keys [prefs]} (router/resolve-routing router
                              {:prefer-providers [:zai :anthropic :copilot] :optimize :cost})
            [prov model] (router/select-provider router prefs)]
        (expect (= :zai (:id prov)))
        (expect (= "glm-turbo" (:name model)))))

    (it "walks the chain in order on model-unsupported failures, smallest per plan"
      (let [calls (atom [])
            result (router/with-provider-fallback router
                     (:prefs (router/resolve-routing router
                               {:prefer-providers [:zai :anthropic :copilot] :optimize :cost}))
                     (fn [provider model]
                       (swap! calls conj [(:id provider) (:name model)])
                       (if (#{:zai :anthropic} (:id provider))
                         (model-unsupported-error "model_not_supported")
                         (success-result 100))))]
        ;; zai (only model) → anthropic cheapest→dearest (haiku, opus) → copilot
        ;; (cheapest surviving = cop-small). Provider order dominates; within a
        ;; provider the cheapest model is tried first, then its siblings.
        (expect (= [[:zai "glm-turbo"] [:anthropic "haiku"] [:anthropic "opus"] [:copilot "cop-small"]]
                  @calls))
        (expect (= :copilot (:routed/provider-id result)))
        (expect (= "cop-small" (:routed/model result)))))

    (it "providers absent from the preference list are tried last"
      (let [{:keys [prefs]} (router/resolve-routing router
                              {:prefer-providers [:anthropic] :optimize :cost})
            [prov _] (router/select-provider router prefs)]
        (expect (= :anthropic (:id prov)))))))

(defdescribe with-provider-fallback-happy-path-test
  "Successful call: result is annotated with `:routed/provider-id`,
   `:routed/model`, `:routed/base-url`, and the router's stats record the
   request + tokens. Router-stats shape: `{:total {...} :providers {pid {...}}}`."

  (it "annotates result and updates router-stats"
    (let [[clock clock-atom] (mock-clock)
          r (llm/make-router
              [{:id :solo :api-key "k" :base-url "http://solo"
                :models [{:name "m1"}]}]
              {:clock clock})
          _ (advance! clock-atom 100)
          result (router/with-provider-fallback r {:strategy :root}
                   (fn [_provider _model]
                     (advance! clock-atom 50)         ;; simulate work
                     (success-result 150)))
          stats (router/router-stats r)]
      (expect (= :solo (:routed/provider-id result)))
      (expect (= "m1"  (:routed/model result)))
      (expect (= "http://solo" (:routed/base-url result)))
      (expect (nil? (:routed/trace result)))
      ;; Top-level totals
      (expect (= 1   (get-in stats [:total :requests])))
      (expect (= 150 (get-in stats [:total :tokens])))
      ;; Per-provider avg latency — work took 50ms on our mock clock
      (expect (pos? (get-in stats [:providers :solo :cumulative :avg-latency-ms]))))))

(defdescribe with-provider-fallback-transient-then-success-test
  "P1 returns a transient error (e.g. 503 / Anthropic 529 overload), router
   retries and eventually succeeds on P2 once P1's CB opens.

   Note: `with-provider-fallback` does NOT exclude previously-tried providers
   from subsequent `select-and-claim!` calls — it relies on the circuit
   breaker to take P1 out of rotation. So with `:failure-threshold 1` a
   single P1 failure opens the CB and P2 wins immediately."

  (it "falls through to P2 and trace includes P1's error"
    (doseq [status [503 529]]
      (let [[clock _] (mock-clock)
            r (llm/make-router
                [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
                 {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}]
                {:clock clock
                 :failure-threshold 1})   ;; open CB on first failure
            calls (atom 0)
            result (router/with-provider-fallback r {}
                     (fn [provider _model]
                       (swap! calls inc)
                       (case (:id provider)
                         :p1 (transient-error status)
                         :p2 (success-result 100))))]
        (expect (= 2 @calls))
        (expect (= :p2 (:routed/provider-id result)))
        (let [trace (:routed/trace result)]
          (expect (vector? trace))
          (expect (= 1 (count trace)))
          (expect (= :llm.routing/provider-fallback (get-in trace [0 :event/type])))
          (expect (= "p1" (get-in trace [0 :from-provider])))
          (expect (= "p2" (get-in trace [0 :to-provider])))
          (expect (= status (get-in trace [0 :status]))))))))

(defdescribe with-provider-fallback-stream-truncated-test
  (it "falls through to P2 on zero-content stream truncation"
    (let [[clock _] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}]
              {:clock clock
               :failure-threshold 1})
          calls (atom [])
          result (router/with-provider-fallback r {}
                   (fn [provider _model]
                     (swap! calls conj (:id provider))
                     (case (:id provider)
                       :p1 (stream-truncated-error)
                       :p2 (success-result 100))))]
      (expect (= [:p1 :p2] @calls))
      (expect (= :p2 (:routed/provider-id result)))
      (let [trace (:routed/trace result)]
        (expect (= 1 (count trace)))
        (expect (= :llm.routing/provider-fallback (get-in trace [0 :event/type])))
        (expect (= :transient-error (get-in trace [0 :reason])))
        (expect (= "p1" (get-in trace [0 :from-provider])))
        (expect (= "p2" (get-in trace [0 :to-provider])))))))

(defdescribe with-provider-fallback-rate-limit-trace-test
  "429 retries are router-owned trace events, not hidden llm/with-retry logs."

  (it "retries same provider, then falls back with one event shape"
    (let [[clock _] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}]
              {:clock clock
               :failure-threshold 1
               :rate-limit {:same-provider-delays-ms [0 0]
                            ;; budget > 0 so the configured delays fire;
                            ;; `:fallback-after-ms 0` means "fallback now"
                            ;; per LLM_SPEC step 4 and is covered below.
                            :fallback-after-ms 60000
                            :respect-retry-after? true
                            :fallback-provider? true}})
          calls (atom [])
          live-events (atom [])
          result (router/with-provider-fallback r {:on-chunk #(swap! live-events conj %)}
                   (fn [provider _model]
                     (swap! calls conj (:id provider))
                     (case (:id provider)
                       :p1 (transient-error 429)
                       :p2 (success-result 100))))]
      (expect (= [:p1 :p1 :p1 :p2] @calls))
      (expect (= :p2 (:routed/provider-id result)))
      (let [trace (:routed/trace result)]
        (expect (= trace @live-events))
        (expect (= [:llm.routing/provider-retry
                    :llm.routing/provider-retry
                    :llm.routing/provider-fallback]
                  (mapv :event/type trace)))
        (expect (= [1 2] (mapv :attempt (take 2 trace))))
        (expect (= "p1" (:from-provider (last trace))))
        (expect (= "p2" (:to-provider (last trace)))))))

  (it "`:fallback-after-ms 0` falls back immediately without same-provider retries"
    (let [[clock _] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}]
              {:clock clock
               :failure-threshold 1
               :rate-limit {:same-provider-delays-ms [0 0 0]
                            :fallback-after-ms 0
                            :fallback-provider? true}})
          calls (atom [])
          result (router/with-provider-fallback r {}
                   (fn [provider _model]
                     (swap! calls conj (:id provider))
                     (case (:id provider)
                       :p1 (transient-error 429)
                       :p2 (success-result 100))))]
      (expect (= [:p1 :p2] @calls))
      (let [trace (:routed/trace result)]
        (expect (= [:llm.routing/provider-fallback] (mapv :event/type trace)))
        (expect (some? (:elapsed-ms (first trace)))))))

  (it "`:respect-retry-after?` lets `Retry-After` header override the configured delay"
    (let [[clock _] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}]
              {:clock clock
               :failure-threshold 1
               :rate-limit {:same-provider-delays-ms [10000 10000]
                            :fallback-after-ms 60000
                            :respect-retry-after? true
                            :fallback-provider? true}})
          ;; `Retry-After: 0` from the server clamps the 10s configured
          ;; delay to 0ms; the test would hang for ~20s otherwise.
          retry-after-error #(throw (ex-info "HTTP 429"
                                      {:type :svar.core/http-error
                                       :status 429
                                       :headers {"retry-after" "0"}}))
          calls (atom [])
          result (router/with-provider-fallback r {}
                   (fn [provider _model]
                     (swap! calls conj (:id provider))
                     (case (:id provider)
                       :p1 (retry-after-error)
                       :p2 (success-result 100))))]
      (expect (= [:p1 :p1 :p1 :p2] @calls))
      (let [trace (:routed/trace result)
            retries (filter #(= :llm.routing/provider-retry (:event/type %)) trace)]
        (expect (= 2 (count retries)))
        ;; Header-driven delay should clamp configured 10000ms to 0ms.
        (expect (every? #(zero? (long (:delay-ms %))) retries)))))

  (it "`:respect-retry-after? false` ignores `Retry-After` header"
    (let [[clock _] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}]
              {:clock clock
               :failure-threshold 1
               :rate-limit {:same-provider-delays-ms [0 0]
                            :fallback-after-ms 60000
                            ;; header ignored → configured 0ms delays win
                            :respect-retry-after? false
                            :fallback-provider? true}})
          retry-after-error #(throw (ex-info "HTTP 429"
                                      {:type :svar.core/http-error
                                       :status 429
                                       :headers {"retry-after" "9999"}}))
          calls (atom [])
          result (router/with-provider-fallback r {}
                   (fn [provider _model]
                     (swap! calls conj (:id provider))
                     (case (:id provider)
                       :p1 (retry-after-error)
                       :p2 (success-result 100))))]
      (expect (= [:p1 :p1 :p1 :p2] @calls))
      (let [retries (filter #(= :llm.routing/provider-retry (:event/type %)) (:routed/trace result))]
        (expect (every? #(zero? (long (:delay-ms %))) retries)))))

  (it "does not retry or fallback after streamed content starts"
    (let [[clock _] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}]
              {:clock clock
               :rate-limit {:same-provider-delays-ms [0]
                            :fallback-after-ms 60000
                            :fallback-provider? true}})
          calls (atom [])
          thrown (try
                   (router/with-provider-fallback r {}
                     (fn [provider _model]
                       (swap! calls conj (:id provider))
                       (throw (ex-info "stream failed after content"
                                {:type :svar.core/http-error
                                 :status 429
                                 :content-acc-len 1
                                 :partial-content "x"}))))
                   ::no-throw
                   (catch clojure.lang.ExceptionInfo e e))]
      (expect (not= ::no-throw thrown))
      (expect (= [:p1] @calls))
      (expect (= 1 (:content-acc-len (ex-data thrown))))))

  (it "`:fallback-after-ms` caps the same-provider phase — delays clamp, no padding"
    ;; LLM_SPEC step 4: hard cap on same-provider wall time. Configured
    ;; delay schedule has 100ms entries but budget is 50ms; the first
    ;; delay clamps to 50ms, then the next iteration sees remain=0 and
    ;; falls back — NO extra padding, even though delay[1] is configured.
    ;;
    ;; Uses a controllable clock that ticks forward by the requested
    ;; sleep so elapsed/remain track delays without burning real wall
    ;; time. (The router's `async/<!! timeout` still sleeps, but we
    ;; advance the clock manually before each retry attempt.)
    (let [clock-atom (atom 0)
          budget-ms 50
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}]
              {:clock (fn [] @clock-atom)
               :failure-threshold 1
               :rate-limit {:same-provider-delays-ms [100 100]
                            :fallback-after-ms budget-ms
                            :fallback-provider? true}})
          calls (atom [])
          result (router/with-provider-fallback r {}
                   (fn [provider _model]
                     (swap! calls conj (:id provider))
                     ;; Simulate the configured delay having already
                     ;; elapsed on subsequent retries by advancing the
                     ;; mock clock from the SECOND p1 call onward. The
                     ;; first call captures start-ms at clock=0 so the
                     ;; first retry is in budget; the second call lands
                     ;; with elapsed=budget so the retry loop falls back.
                     (when (and (= (:id provider) :p1) (> (count @calls) 1))
                       (swap! clock-atom + budget-ms))
                     (case (:id provider)
                       :p1 (transient-error 429)
                       :p2 (success-result 100))))]
      (expect (= [:p1 :p1 :p2] @calls))
      ;; one retry fired with the clamped delay (≤ budget), then fallback.
      (let [retries (filter #(= :llm.routing/provider-retry (:event/type %)) (:routed/trace result))
            fb     (first (filter #(= :llm.routing/provider-fallback (:event/type %)) (:routed/trace result)))]
        (expect (= 1 (count retries)))
        (expect (<= (long (:delay-ms (first retries))) budget-ms))
        ;; fallback event carries elapsed-ms measured against the router clock.
        (expect (some? (:elapsed-ms fb)))
        (expect (>= (long (:elapsed-ms fb)) budget-ms)))))

  (it "`:on-chunk` from caller opts surfaces routing events live through `ask-code!`"
    ;; Regression for LLM_SPEC "emitted live to caller/TUI when available".
    ;; Without `:on-chunk` passthrough in `resolve-routing` /
    ;; `routing-opts-with-reasoning`, retry/fallback events only land in
    ;; the final `:routed/trace` — the TUI sees a multi-second hang.
    (let [[clock _] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}]
              {:clock clock
               :failure-threshold 1
               :rate-limit {:same-provider-delays-ms [0]
                            :fallback-after-ms 60000
                            :fallback-provider? true}})
          live-events (atom [])
          attempts (atom 0)]
      (with-redefs [llm/ask-code!* (fn [_r opts]
                                     (swap! attempts inc)
                                     (case (:provider-id opts)
                                       :p1 (transient-error 429)
                                       :p2 {:stop-reason :end
                                            :tool-calls []
                                            :content "ok"
                                            :api-usage {:input-tokens 1 :output-tokens 1 :total-tokens 2}}))]
        (llm/ask-code! r {:messages [{:role "user" :content "hi"}]
                          :on-chunk #(swap! live-events conj %)}))
      (let [types (mapv :event/type @live-events)]
        (expect (= [:llm.routing/provider-retry
                    :llm.routing/provider-fallback]
                  types))
        (expect (= "p1" (:provider (first @live-events))))
        (expect (= "p2" (:to-provider (last @live-events))))))))

(defdescribe with-provider-fallback-all-exhausted-test
  "Every provider returns a transient error → `:svar.llm/all-providers-exhausted`
   with `:tried` and `:routed/trace` populated."

  (it "throws when every provider fails transiently"
    (let [[clock _] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}]
              {:clock clock})]
      (try
        (router/with-provider-fallback r {}
          (fn [_ _] (transient-error 503)))
        (expect false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (expect (= :svar.llm/all-providers-exhausted (:type (ex-data e))))
          (expect (= #{:p1 :p2} (:tried (ex-data e))))
          ;; per-provider breakdown: one attempt per provider, each with the
          ;; provider/model/status/reason so the caller can name WHY each failed.
          (let [attempts (:attempts (ex-data e))]
            (expect (= 2 (count attempts)))
            (expect (= #{:p1 :p2} (set (map :provider attempts))))
            (expect (= #{503} (set (map :status attempts))))
            (expect (every? #{:transient-error} (map :reason attempts)))))))))

(defdescribe with-provider-fallback-single-provider-unavailable-test
  "A SINGLE provider (pinned / only-provider fleet) failing transiently is NOT a
   fleet exhaustion: it throws `:svar.llm/provider-unavailable` (not
   `:all-providers-exhausted`), carrying the upstream transient's status/body so
   the caller can classify + decide where to go next. Regression for the Vis
   'All providers unavailable' card lying about a one-provider turn."
  (it "throws :provider-unavailable with preserved status when the only provider fails"
    (let [[clock _] (mock-clock)
          r (llm/make-router
              [{:id :solo :api-key "k" :base-url "http://solo" :models [{:name "m1"}]}]
              {:clock clock})]
      (try
        (router/with-provider-fallback r {}
          (fn [_ _] (transient-error 503)))
        (expect false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (expect (= :svar.llm/provider-unavailable (:type (ex-data e))))
          (expect (= "Provider unavailable" (ex-message e)))
          (expect (= #{:solo} (:tried (ex-data e))))
          ;; upstream status preserved so downstream classification works
          (expect (= 503 (:status (ex-data e))))
          (let [attempts (:attempts (ex-data e))]
            (expect (= 1 (count attempts)))
            (expect (= :solo (:provider (first attempts))))))))))

;; =============================================================================
;; Tier 1 — Circuit breaker state machine
;; =============================================================================

(defdescribe circuit-breaker-open-on-threshold-test
  "5 consecutive failures on the same provider flip CB :closed → :open. Once
   open, the provider is skipped during selection."

  (it "5 transient failures open the CB; 6th attempt skips P1 and uses P2"
    (let [[clock _] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}]
              {:clock clock
               :failure-threshold 5})]
      ;; Drive 5 P1 failures (each attempt also falls through to P2 success).
      (dotimes [_ 5]
        (router/with-provider-fallback r {:strategy :root}
          (fn [provider _model]
            (case (:id provider)
              :p1 (transient-error 503)
              :p2 (success-result 10)))))
      ;; P1's CB should now be open; the next call should skip P1 entirely
      ;; and go straight to P2 with no routing trace.
      (let [result (router/with-provider-fallback r {:strategy :root}
                     (fn [_ _] (success-result 10)))]
        (expect (= :p2 (:routed/provider-id result)))
        (expect (nil? (:routed/trace result)))))))

(defdescribe circuit-breaker-half-open-cycle-test
  "After `:recovery-ms` elapses the CB effectively reports `:half-open`; a
   successful probe closes it; a failed probe immediately re-opens it."

  (it "open → half-open (time) → closed (probe success)"
    (let [[clock clock-atom] (mock-clock)
          r                  (llm/make-router
                               [{:id       :p1
                                 :api-key  "k"
                                 :base-url "http://p1"
                                 :models   [{:name "m1"}]}
                                {:id       :p2
                                 :api-key  "k"
                                 :base-url "http://p2"
                                 :models   [{:name "m2"}]}]
                               {:clock             clock
                                :failure-threshold 2
                                :recovery-ms       10000})]
      ;; Open the CB on P1 by pushing 2 failures.
      (dotimes [_ 2]
        (router/with-provider-fallback r {:strategy :root}
          (fn [provider _] (case (:id provider) :p1 (transient-error 503)
                                 :p2 (success-result 10)))))
      ;; CB should be open — verify P1 is skipped.
      (let [skipped (router/with-provider-fallback r {:strategy :root}
                      (fn [_ _] (success-result 10)))]
        (expect (= :p2 (:routed/provider-id skipped))))
      ;; Advance clock past recovery → CB transitions to :half-open on next read.
      (advance! clock-atom 11000)
      ;; Probe succeeds → CB back to :closed; P1 wins again.
      (let [probe (router/with-provider-fallback r {:strategy :root}
                    (fn [_ _] (success-result 10)))]
        (expect (= :p1 (:routed/provider-id probe))))))

  (it "half-open → open on single probe failure"
    (let [[clock clock-atom] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}]
              {:clock clock
               :failure-threshold 2
               :recovery-ms 10000})]
      ;; Open CB
      (dotimes [_ 2]
        (router/with-provider-fallback r {:strategy :root}
          (fn [provider _] (case (:id provider) :p1 (transient-error 503)
                                 :p2 (success-result 10)))))
      ;; Advance to half-open
      (advance! clock-atom 11000)
      ;; Probe P1 → fails again; CB should immediately re-open for another recovery-ms
      (router/with-provider-fallback r {:strategy :root}
        (fn [provider _] (case (:id provider) :p1 (transient-error 503)
                               :p2 (success-result 10))))
      ;; P1 should still be skipped — just advancing 1 second is not enough,
      ;; CB is open again for recovery-ms.
      (advance! clock-atom 1000)
      (let [still-skipped (router/with-provider-fallback r {:strategy :root}
                            (fn [_ _] (success-result 10)))]
        (expect (= :p2 (:routed/provider-id still-skipped)))))))

;; =============================================================================
;; Tier 2 — `:optimize` dials pick the right model for real
;; =============================================================================

(defdescribe optimize-cost-by-real-pricing-test
  "Bug #1 regression: `:optimize :cost` must consult the real pricing table
   attached by `normalize-provider`, not the never-populated `:cost` tier tag.
   Regression pins the new pricing-driven sort."

  (it "picks gpt-5-mini ($2.25/M blended) over gpt-4o ($12.50/M blended)"
    (let [r (llm/make-router
              [{:id :openai :api-key "k"
                :models [{:name "gpt-4o"} {:name "gpt-5-mini"}]}])]
      (let [[_ model] (router/select-provider r {:prefer :cost})]
        (expect (= "gpt-5-mini" (:name model))))))

  (it "picks cheaper model across providers"
    (let [r (llm/make-router
              [;; openai gpt-5-mini: 0.25 + 2.00 = 2.25
               {:id :openai :api-key "k"
                :models [{:name "gpt-5-mini"}]}
               ;; openrouter glm-4.6v: 0.30 + 0.90 = 1.20 (cheaper)
               {:id :zai :api-key "k"
                :models [{:name "glm-4.6v"}]}])]
      (let [[provider model] (router/select-provider r {:prefer :cost})]
        (expect (= :zai (:id provider)))
        (expect (= "glm-4.6v" (:name model)))))))

(defdescribe optimize-intelligence-cross-provider-test
  "Bug #2 regression: `:optimize :intelligence` picks the frontier model
   across the whole fleet, with provider priority as a tiebreaker within
   the same tier — not a dominant sort key that buries cross-provider
   frontier models behind lower-tier high-priority ones."

  (it "frontier in low-priority provider wins over high-tier in high-priority"
    (let [r (llm/make-router
              [;; priority 0: only `:high` model
               {:id :openai :api-key "k" :models [{:name "gpt-4o"}]}
               ;; priority 1: `:frontier`
               {:id :anthropic :api-key "k" :models [{:name "claude-opus-4-5"}]}])]
      (let [[provider model] (router/select-provider r {:prefer :intelligence})]
        (expect (= :anthropic (:id provider)))
        (expect (= "claude-opus-4-5" (:name model))))))

  (it "provider priority tiebreaks WITHIN the same tier"
    (let [r (llm/make-router
              [;; both frontier — provider-a (priority 0) wins tiebreak
               {:id :openai    :api-key "k" :models [{:name "gpt-5"}]}
               {:id :anthropic :api-key "k" :models [{:name "claude-opus-4-5"}]}])]
      (let [[provider _] (router/select-provider r {:prefer :intelligence})]
        (expect (= :openai (:id provider)))))))

(defdescribe anthropic-coding-plan-default-model-test
  (it "prepends Claude Opus 4.8 to existing subscription provider configs"
    (let [r (llm/make-router
              [{:id :anthropic-coding-plan
                :api-key "sk-ant-oat01-test"
                :models [{:name "claude-opus-4-6"}
                         {:name "claude-sonnet-4-6"}]}])
          provider (first (:providers r))]
      (expect (= "claude-opus-4-8" (:root provider)))
      ;; `:anthropic-coding-plan` prepends its FULL default catalog
      ;; (opus-4-8 → opus-4-7 → opus-4-6 → fable-5 → sonnet-5 → sonnet-4-6 → haiku-4-5),
      ;; deduped against the caller's configured models via `conj-model-once`.
      ;; The caller's opus-4-6 / sonnet-4-6 collapse into the prepended defaults.
      (expect (= ["claude-opus-4-8" "claude-opus-4-7" "claude-opus-4-6"
                  "claude-fable-5" "claude-sonnet-5" "claude-sonnet-4-6" "claude-haiku-4-5"]
                (mapv :name (:models provider))))
      (expect (= :anthropic (:api-style provider)))
      (expect (= {:input 5.0
                  :cached-input 0.5
                  :cache-write-5m 6.25
                  :cache-write-1h 10.0
                  :output 25.0}
                (select-keys (get-in provider [:models 0 :pricing])
                  [:input :cached-input :cache-write-5m :cache-write-1h :output])))))

  (it "does not claim nonexistent Claude Sonnet 4.8 catalog metadata"
    (expect (nil? (router/provider-model-entry :anthropic-coding-plan "claude-sonnet-4-8")))))

;; =============================================================================
;; Tier 3 — `:reasoning` integrates with routing (selection filter)
;; =============================================================================

(defdescribe reasoning-filter-selects-capable-model-test
  "Bug #3 regression: `:reasoning :deep` must FILTER the candidate set to
   reasoning-capable models, otherwise `{:optimize :cost :reasoning :deep}`
   would pick a cheap non-reasoning model and silently drop `:deep`.

   Uses `:openai` provider id so models get real pricing + reasoning metadata
   attached by `normalize-provider`."

  (it "`:reasoning :deep` + `:optimize :cost` picks cheapest *reasoning-capable*"
    ;; OpenAI fleet with a mix of reasoning and non-reasoning models at
    ;; different price points:
    ;;   gpt-4o   — 2.50 + 10.00 = $12.50/M blended,  NOT reasoning
    ;;   gpt-5-mini — 0.25 + 2.00 = $2.25/M blended,   reasoning
    ;;   gpt-5.1  — 1.25 + 10.00 = $11.25/M blended,  reasoning
    (let [r (llm/make-router
              [{:id :openai :api-key "k"
                :models [{:name "gpt-4o"} {:name "gpt-5-mini"} {:name "gpt-5.1"}]}])]
      ;; Without filter, cheapest-by-pricing wins: gpt-5-mini ($2.25)
      (let [[_ model] (router/select-provider r {:prefer :cost})]
        (expect (= "gpt-5-mini" (:name model))))
      ;; With :reasoning :deep filter, still gpt-5-mini (it IS reasoning-capable, cheapest)
      (let [{:keys [prefs]} (router/resolve-routing r {:optimize :cost :reasoning :deep})
            [_ model] (router/select-provider r prefs)]
        (expect (= "gpt-5-mini" (:name model))))))

  (it "`:reasoning :deep` filters OUT a non-reasoning model even when it's cheapest"
    ;; Construct a case where the cheapest model is explicitly NOT reasoning,
    ;; to prove the filter actually changes selection:
    ;;   gpt-4o (non-reasoning, $12.50)
    ;;   gpt-5-mini (reasoning, $0.25 + $2.00 = $2.25)
    ;;   gpt-5.1 (reasoning, $1.25 + $10.00) — reasoning-capable
    ;; Without the filter gpt-5-mini wins anyway (both cheapest AND reasoning),
    ;; but swap the scenario: explicit pricing on a synthetic fixture where
    ;; the cheapest happens to be non-reasoning.
    (let [r (llm/make-router
              [{:id :synthetic :api-key "k" :base-url "http://x"
                :models [{:name "cheap-dumb"  :pricing {:input 0.10 :output 0.20}
                          :intelligence :low  :speed :fast :capabilities #{:chat}}
                         {:name "pricey-smart" :pricing {:input 2.00 :output 5.00}
                          :intelligence :high :speed :medium :capabilities #{:chat}
                          :reasoning? true :reasoning-style :openai-effort}]}])]
      ;; Without filter — cheapest wins (dumb but cheap)
      (let [[_ model] (router/select-provider r {:prefer :cost})]
        (expect (= "cheap-dumb" (:name model))))
      ;; With :reasoning — filter excludes cheap-dumb, forces pricey-smart
      (let [{:keys [prefs]} (router/resolve-routing r {:optimize :cost :reasoning :deep})
            [_ model] (router/select-provider r prefs)]
        (expect (= "pricey-smart" (:name model))))))

  (it "`:reasoning :deep` WITHOUT optimize routes the root model best-effort"
    ;; Single-provider / local case (e.g. LM Studio): requesting a reasoning
    ;; level must NOT block routing — the root model is honored and reasoning is
    ;; applied iff it supports it. Previously this returned nil → the user saw
    ;; "all providers exhausted" on a non-reasoning local model.
    (let [r (llm/make-router
              [{:id :openai :api-key "k"
                :models [{:name "gpt-4o"} {:name "gpt-4.1"}]}])]
      (let [{:keys [prefs]} (router/resolve-routing r {:reasoning :deep})
            [_ model] (router/select-provider r prefs)]
        (expect (= "gpt-4o" (:name model))))))

  (it "`:reasoning :deep` + `:optimize` with zero reasoning-capable models returns nil"
    ;; When svar is choosing among models (optimize/prefer), the reasoning
    ;; filter still applies so depth is not silently dropped — there is simply
    ;; nothing reasoning-capable to pick here.
    (let [r (llm/make-router
              [{:id :openai :api-key "k"
                :models [{:name "gpt-4o"} {:name "gpt-4.1"}]}])]
      (let [{:keys [prefs]} (router/resolve-routing r {:optimize :cost :reasoning :deep})
            selection (router/select-provider r prefs)]
        (expect (nil? selection))))))

;; =============================================================================
;; Rate limiting — RPM / TPM gates, window pruning, CAS claim
;; =============================================================================

(defn- seed-requests!
  "Manually seed a provider's `:requests` window with `n` timestamps clustered
   at `now`. Used to drive rate-limit gates without actually making API calls."
  [router provider-id now n]
  (swap! (:state router) update-in [provider-id :requests]
    (fn [rs] (into (or rs []) (repeat n now)))))

(defn- seed-tokens!
  "Seed `:tokens` window with `entries` (each `{:ts long :n long}`)."
  [router provider-id entries]
  (swap! (:state router) update-in [provider-id :tokens]
    (fn [ts] (into (or ts []) entries))))

(defdescribe rate-limit-rpm-gate-test
  "`provider-available?` must refuse providers whose in-window request count
   meets or exceeds `:rpm`. Window length is `:window-ms` (default 60s)."

  (it "provider is unavailable when RPM is exhausted"
    (let [[clock clock-atom] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :rpm 3
                :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :rpm 3
                :models [{:name "m2"}]}]
              {:clock clock})
          _ (advance! clock-atom 1000)
          _ (seed-requests! r :p1 @clock-atom 3)   ;; p1 is now at its RPM limit
          ;; select-and-claim should skip p1 and pick p2
          result (router/with-provider-fallback r {}
                   (fn [_ _] (success-result 10)))]
      (expect (= :p2 (:routed/provider-id result)))))

  (it "RPM slot frees up after `:window-ms` elapses"
    (let [[clock clock-atom] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :rpm 2
                :models [{:name "m1"}]}]
              {:clock clock :window-ms 10000})
          _ (advance! clock-atom 0)
          _ (seed-requests! r :p1 0 2)             ;; p1 at RPM limit at t=0
          _ (advance! clock-atom 11000)]           ;; window elapsed
      ;; Old entries pruned out; p1 should be selectable again
      (expect (= :p1 (:routed/provider-id
                      (router/with-provider-fallback r {}
                        (fn [_ _] (success-result 10)))))))))

(defdescribe rate-limit-tpm-gate-test
  "`provider-available?` must refuse providers whose in-window summed token
   count meets or exceeds `:tpm`."

  (it "provider is unavailable when TPM sum is exhausted"
    (let [[clock clock-atom] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :tpm 1000
                :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :tpm 1000
                :models [{:name "m2"}]}]
              {:clock clock})
          _ (advance! clock-atom 500)
          _ (seed-tokens! r :p1 [{:ts @clock-atom :n 600}
                                 {:ts @clock-atom :n 500}])]  ;; sum = 1100 >= 1000
      (expect (= :p2 (:routed/provider-id
                      (router/with-provider-fallback r {}
                        (fn [_ _] (success-result 10))))))))

  (it "TPM slot frees up after window elapses"
    (let [[clock clock-atom] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :tpm 100
                :models [{:name "m1"}]}]
              {:clock clock :window-ms 5000})
          _ (seed-tokens! r :p1 [{:ts 0 :n 500}])
          _ (advance! clock-atom 6000)]          ;; past window
      (expect (= :p1 (:routed/provider-id
                      (router/with-provider-fallback r {}
                        (fn [_ _] (success-result 10)))))))))

(defdescribe select-and-claim-updates-request-window-test
  "`select-and-claim!` MUST atomically append the claim timestamp to the
   winning provider's `:requests` window so subsequent calls see the load.
   Verified single-threaded by observing post-call state."

  (it "successful call appends a request timestamp to the winning provider"
    (let [[clock clock-atom] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}]
              {:clock clock})
          _ (advance! clock-atom 1234)
          _ (router/with-provider-fallback r {}
              (fn [_ _] (success-result 50)))
          ps (get @(:state r) :p1)]
      (expect (= 1 (count (:requests ps))))
      ;; timestamp matches clock at claim time
      (let [ts (first (:requests ps))]
        (expect (= 1234 (if (map? ts) (:ts ts) ts))))
      ;; tokens recorded too
      (expect (pos? (reduce + 0 (map :n (:tokens ps))))))))

;; =============================================================================
;; Budget — check + record + ex-info shape
;; =============================================================================

(defdescribe budget-check-exhausted-test
  "`budget-check!` runs at the start of every `with-provider-fallback` call.
   It throws `:svar/budget-exhausted` with `:budget`/`:spent` ex-data when
   either token or cost ceiling is reached."

  (it "throws when :max-tokens has been hit"
    (let [[clock _] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}]
              {:clock clock
               :budget {:max-tokens 100}})]
      ;; Pre-fill the budget-state to exhausted
      (reset! (:budget-state r) {:total-tokens 100 :total-cost 0.0})
      (try
        (router/with-provider-fallback r {} (fn [_ _] (success-result 10)))
        (expect false "should have thrown budget-exhausted")
        (catch clojure.lang.ExceptionInfo e
          (expect (= :svar/budget-exhausted (:type (ex-data e))))
          (expect (= {:max-tokens 100} (:budget (ex-data e))))
          (expect (= 100 (get-in (ex-data e) [:spent :tokens])))
          (expect (re-find #"(?i)token" (.getMessage e)))))))

  (it "throws when :max-cost has been hit (cost-exhausted message)"
    (let [[clock _] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}]
              {:clock clock
               :budget {:max-cost 0.01}})]
      (reset! (:budget-state r) {:total-tokens 0 :total-cost 0.05})
      (try
        (router/with-provider-fallback r {} (fn [_ _] (success-result 10)))
        (expect false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (expect (= :svar/budget-exhausted (:type (ex-data e))))
          (expect (re-find #"(?i)cost" (.getMessage e)))))))

  (it "does NOT throw when budget has headroom"
    (let [[clock _] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}]
              {:clock clock
               :budget {:max-tokens 10000 :max-cost 10.0}})]
      (let [result (router/with-provider-fallback r {}
                     (fn [_ _] (success-result 50)))]
        (expect (some? result))))))

(defdescribe budget-record-accrues-tokens-and-cost-test
  "`budget-record!` accumulates `:total-tokens` and `:total-cost` in
   `:budget-state`, using `provider-model-pricing` for the USD calc.

   Uses :openai + gpt-4o so pricing is attached by `normalize-provider`:
   `gpt-4o = {:input 2.50 :output 10.00}` USD per 1M tokens."

  (it "accrues token counts and USD cost after a successful call"
    (let [[clock _] (mock-clock)
          r (llm/make-router
              [{:id :openai :api-key "k" :models [{:name "gpt-4o"}]}]
              {:clock clock
               :budget {:max-tokens 10000000 :max-cost 100.0}})
          _ (router/with-provider-fallback r {:strategy :root}
              (fn [_ _]
                ;; 1000 prompt + 2000 completion, total matches
                {:result {:answer "ok"}
                 :api-usage {:input-tokens 1000 :output-tokens 2000 :total-tokens 3000}}))
          spent @(:budget-state r)]
      ;; Tokens: sum of prompt + completion = 3000
      (expect (= 3000 (:total-tokens spent)))
      ;; Cost: 1000/1M * $2.50 + 2000/1M * $10.00 = 0.0025 + 0.02 = 0.0225
      (let [expected-cost (+ (* (/ 1000.0 1e6) 2.50)
                            (* (/ 2000.0 1e6) 10.00))]
        (expect (< (Math/abs (- (:total-cost spent) expected-cost)) 0.0001)))))

  (it "router-stats exposes budget limit and spent"
    (let [[clock _] (mock-clock)
          r (llm/make-router
              [{:id :openai :api-key "k" :models [{:name "gpt-4o"}]}]
              {:clock clock :budget {:max-tokens 5000 :max-cost 1.0}})
          _ (router/with-provider-fallback r {:strategy :root}
              (fn [_ _] {:result {}
                         :api-usage {:input-tokens 100 :output-tokens 50
                                     :total-tokens 150}}))
          stats (router/router-stats r)]
      (expect (= {:max-tokens 5000 :max-cost 1.0} (get-in stats [:budget :limit])))
      (expect (= 150 (get-in stats [:budget :spent :total-tokens])))
      (expect (pos? (get-in stats [:budget :spent :total-cost]))))))

;; =============================================================================
;; 429 vs 500 — CB uses `:cooldown-ms` for rate-limit, `:recovery-ms` for
;; generic transient errors. Defaults are equal but configurable separately.
;; =============================================================================

(defdescribe cb-429-vs-500-recovery-branch-test
  "The CB `:cb-open-until` timestamp is driven by `:cooldown-ms` for HTTP 429
   (rate-limit) and by `:recovery-ms` for other transient errors (500/502/...).
   Wire them to distinct values and verify the open-until picks the right one."

  (it "429 failure uses :cooldown-ms"
    (let [[clock clock-atom] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}]
              {:clock clock
               :failure-threshold 1
               :cooldown-ms 7777         ;; 429-specific
               :recovery-ms 99999})      ;; 500-specific
          _ (advance! clock-atom 0)
          _ (router/with-provider-fallback r {:strategy :root}
              (fn [provider _]
                (case (:id provider)
                  :p1 (transient-error 429)       ;; rate-limit branch
                  :p2 (success-result 10))))
          p1-state (get @(:state r) :p1)]
      (expect (= :open (:cb-state p1-state)))
      (expect (= 7777 (:cb-open-until p1-state)))))

  (it "500 failure uses :recovery-ms"
    (let [[clock clock-atom] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}]
              {:clock clock
               :failure-threshold 1
               :cooldown-ms 7777
               :recovery-ms 99999})
          _ (advance! clock-atom 0)
          _ (router/with-provider-fallback r {:strategy :root}
              (fn [provider _]
                (case (:id provider)
                  :p1 (transient-error 503)       ;; NOT 429 → recovery branch
                  :p2 (success-result 10))))
          p1-state (get @(:state r) :p1)]
      (expect (= :open (:cb-state p1-state)))
      (expect (= 99999 (:cb-open-until p1-state))))))

;; =============================================================================
;; regex-infer-metadata — fallback tier inference for unknown model names
;; =============================================================================

(defdescribe regex-infer-metadata-test
  "When a model name isn't in `KNOWN_MODEL_METADATA`, the router falls back
   to regex inference. These tests pin the pattern → tier mapping so adding
   a new model family to production doesn't silently misclassify.

   Private fn — access via `#'`. The patterns match LOWERCASED name."

  (let [infer #'router/regex-infer-metadata]

    (describe "`:medium :fast` family (mini/haiku/flash/lite/small/nano)"
      (it "mini suffix"  (expect (= :medium (:intelligence (infer "custom-mini")))))
      (it "haiku family" (expect (= :medium (:intelligence (infer "claude-haiku-unknown")))))
      (it "flash family" (expect (= :medium (:intelligence (infer "future-flash")))))
      (it "nano suffix"  (expect (= :medium (:intelligence (infer "acme-nano"))))))

    (describe "`:frontier :slow` family (reasoner / thinking)"
      (it "does not special-case obsolete o-series prefixes"
        (expect (= :medium (:intelligence (infer "o1-new"))))
        (expect (= :medium (:intelligence (infer "o3-super"))))
        (expect (= :high (:intelligence (infer "o4-large")))))
      (it "reasoner keyword" (expect (= :frontier (:intelligence (infer "custom-reasoner")))))
      (it "thinking keyword" (expect (= :frontier (:intelligence (infer "future-thinking"))))))

    (describe "`:frontier :slow` opus/frontier family"
      (it "opus → frontier"    (expect (= :frontier (:intelligence (infer "claude-opus-x")))))
      (it "frontier → frontier" (expect (= :frontier (:intelligence (infer "acme-frontier-v1"))))))

    (describe "`:high :medium` sonnet/pro/large family"
      (it "sonnet" (expect (= :high (:intelligence (infer "claude-sonnet-99")))))
      (it "pro"    (expect (= :high (:intelligence (infer "custom-pro")))))
      (it "gpt-5"  (expect (= :high (:intelligence (infer "gpt-5-custom"))))))

    (describe "`:medium :medium` default"
      (it "unknown name" (expect (= :medium (:intelligence (infer "totally-random-model"))))))

    (describe "vision capability triggers"
      (it "claude pulls in :vision on :medium :fast path"
        (expect (contains? (:capabilities (infer "claude-haiku-new")) :vision)))
      (it "glm*v pulls in :vision on default path"
        (expect (contains? (:capabilities (infer "glm-8v")) :vision)))
      (it "plain o-series stays chat-only"
        (expect (= #{:chat} (:capabilities (infer "o5-pro"))))))

    (describe "no :reasoning? flag from regex path"
      ;; Important: regex inference never sets :reasoning?. Unknown reasoning
      ;; models slip through as non-reasoning — document via test.
      (it "o-series inference leaves :reasoning? unset"
        (expect (nil? (:reasoning? (infer "o5-new")))))
      (it "opus inference leaves :reasoning? unset"
        (expect (nil? (:reasoning? (infer "claude-opus-99"))))))))

(defdescribe ask-with-reasoning-integration-test
  "End-to-end: `llm/ask!` routes the request through the reasoning filter so
   a mixed fleet cannot pick a non-reasoning model when `:reasoning` is set.
   Verifies the wiring from `ask!` → `routing-opts-with-reasoning` →
   `resolve-routing` → `resolve-model`."

  (it "reasoning filter reaches ask!'s provider selection"
    (let [captured (atom nil)
          ;; Synthetic fleet: cheapest is non-reasoning, forcing the filter
          ;; to change the selection vs. a pure `:cost` pass.
          r (llm/make-router
              [{:id :synth :api-key "k" :base-url "http://x"
                :models [{:name "cheap-dumb"   :pricing {:input 0.10 :output 0.20}
                          :intelligence :low   :speed :fast  :capabilities #{:chat}}
                         {:name "pricey-smart" :pricing {:input 2.00 :output 5.00}
                          :intelligence :high  :speed :medium :capabilities #{:chat}
                          :reasoning? true :reasoning-style :openai-effort}]}])]
      (with-redefs [llm/ask!* (fn [_r opts]
                                (reset! captured opts)
                                {:result {:answer "ok"}
                                 :api-usage {:input-tokens 1 :output-tokens 1 :total-tokens 2}})]
        (llm/ask! r {:spec {} :messages [{:role "user" :content "hi"}]
                     :routing {:optimize :cost}
                     :reasoning :deep}))
      ;; Without :reasoning the router would have picked cheap-dumb; with it
      ;; the filter excludes cheap-dumb and pricey-smart wins despite being
      ;; ~25x more expensive.
      (expect (= "pricey-smart" (:model @captured))))))

(defdescribe rate-limited-provider-not-replayed-as-fallback-test
  ;; Regression: before this fix `with-provider-fallback` only tracked
  ;; `:exclude-providers` from format-errors. A rate-limit-budget-exhausted
  ;; provider stayed in the chain, so `select-and-claim!` would pick it
  ;; again on the next iteration and the trace showed bogus
  ;; `provider-fallback: codex/gpt-5.3 → codex/gpt-5.3` events.
  ;; Production sample (Vis conversation 2026-05-21):
  ;;   * Recap: Provider retry: openai-codex/gpt-5.3-codex — 429
  ;;   * Recap: Provider fallback: openai-codex/gpt-5.3-codex →
  ;;             openai-codex/gpt-5.3-codex — Exceptional status code: 429
  (it "excludes a rate-limit-exhausted provider on the next fallback iteration"
    (let [[clock-fn clock-atom] (mock-clock)
          calls (atom 0)
          f-429 (fn [_p _m]
                  (swap! calls inc)
                  (advance! clock-atom 1)
                  (throw (ex-info "rate-limited"
                           {:type :svar.core/http-error :status 429})))
          r (llm/make-router
              [{:id :test :api-key "k" :base-url "http://x"
                :models [{:name "only-model"}]}]
              {:clock clock-fn
               :rate-limit {:same-provider-delays-ms [1]
                            :fallback-after-ms 5
                            :fallback-provider? true
                            :respect-retry-after? false}})
          result (try
                   (router/with-provider-fallback r {} f-429)
                   (catch Exception e {:thrown-type (:type (ex-data e))
                                       :tried       (:tried (ex-data e))}))]
      ;; ONE provider ever tried → `:provider-unavailable`, NOT the fleet-wide
      ;; `:all-providers-exhausted` (reserved for a real multi-provider walk).
      (expect (= :svar.llm/provider-unavailable (:thrown-type result)))
      ;; The offending provider must show up in `:tried` exactly once,
      ;; not be replayed back into the chain.
      (expect (= #{:test} (:tried result)))
      ;; Bounded retry count: configured delay schedule + cooldown wait,
      ;; not an infinite same-provider loop. Pre-fix this could blow past
      ;; 30+ calls on a single-model chain.
      (expect (< @calls 20)))))

(defdescribe rate-limit-retry-respects-thread-interrupt-test
  ;; Regression: pre-fix `handle-rate-limit-retries` slept on
  ;; `(async/<!! (async/timeout N))` and the surrounding `catch Exception`
  ;; swallowed `InterruptedException`, classifying the wake as the next
  ;; transient failure. User-driven cancellation (Vis `Esc`) therefore
  ;; failed to break out of the retry loop — reproduced live in the same
  ;; 2026-05-21 codex incident where pressing Esc had no visible effect.
  (it "propagates InterruptedException out of the retry sleep instead of treating it as a retryable error"
    (let [worker-thread (atom nil)
          slow-f (fn [_p _m]
                   (reset! worker-thread (Thread/currentThread))
                   (throw (ex-info "slow"
                            {:type :svar.core/http-error :status 429})))
          r (llm/make-router
              [{:id :slow :api-key "k" :base-url "http://x"
                :models [{:name "m"}]}]
              ;; Long delays + long budget so the retry loop WOULD sleep
              ;; for tens of seconds if interrupts were swallowed.
              {:rate-limit {:same-provider-delays-ms [60000]
                            :fallback-after-ms 120000
                            :fallback-provider? true
                            :respect-retry-after? false}})
          start (System/currentTimeMillis)
          worker (future
                   (try
                     (router/with-provider-fallback r {} slow-f)
                     :no-exception
                     (catch InterruptedException _ :interrupted)
                     (catch Exception e
                       {:class (.getSimpleName (class e))
                        :has-interrupt-cause?
                        (boolean
                          (loop [^Throwable t (.getCause e)]
                            (cond
                              (nil? t) false
                              (instance? InterruptedException t) true
                              :else (recur (.getCause t)))))})))]
      ;; Wait briefly for the worker to enter the retry sleep.
      (loop [n 0]
        (cond
          (some? @worker-thread) :ready
          (>= n 50) (throw (ex-info "worker never started" {}))
          :else (do (Thread/sleep 20) (recur (inc n)))))
      ;; Interrupt the carrier; the retry loop must wake immediately,
      ;; not consume the full 60s sleep budget.
      (.interrupt ^Thread @worker-thread)
      (let [outcome (deref worker 3000 :timeout)
            elapsed (- (System/currentTimeMillis) start)]
        (expect (not= :timeout outcome))
        (expect (not= :no-exception outcome))
        ;; <2s = orders of magnitude under the 60s configured retry sleep,
        ;; so we know the interrupt escaped the loop instead of being
        ;; absorbed.
        (expect (< elapsed 2000))))))

;; =============================================================================
;; Model-unsupported fallback — provider advertises a model but rejects it at
;; inference (400/404 `model_not_supported`). Route to a sibling model by NAME
;; instead of failing the whole call. Regression: Copilot `grok-code-fast-1`.
;; =============================================================================

(defdescribe with-provider-fallback-model-unsupported-test
  "400/404 `model_not_supported` excludes the MODEL (not the provider) and
   retries a sibling — including on the same provider."

  (it "falls back to a sibling model on the SAME provider, excluding by name"
    (let [r (llm/make-router
              [{:id :copilot :api-key "k" :base-url "http://c"
                :models [{:name "grok-code-fast-1"} {:name "gpt-5.4-mini"}]}])
          calls (atom [])
          live (atom [])
          result (router/with-provider-fallback r {:on-chunk #(swap! live conj %)}
                   (fn [_provider model]
                     (swap! calls conj (:name model))
                     (if (= "grok-code-fast-1" (:name model))
                       (model-unsupported-error)
                       (success-result 100))))]
      ;; root (first) model tried, rejected, excluded; sibling served.
      (expect (= ["grok-code-fast-1" "gpt-5.4-mini"] @calls))
      (expect (= "gpt-5.4-mini" (:routed/model result)))
      (expect (:routed/fallback? result))
      (let [trace (:routed/trace result)]
        (expect (= trace @live))
        (expect (= [:llm.routing/model-fallback] (mapv :event/type trace)))
        (expect (= "grok-code-fast-1" (:from-model (last trace))))
        (expect (= "gpt-5.4-mini" (:to-model (last trace))))
        (expect (= :model-unsupported (:reason (last trace)))))))

  (it "falls back across providers when no sibling exists"
    (let [r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}])
          calls (atom [])
          result (router/with-provider-fallback r {}
                   (fn [provider model]
                     (swap! calls conj [(:id provider) (:name model)])
                     (if (= "m1" (:name model))
                       (model-unsupported-error "model_not_supported")
                       (success-result 100))))]
      (expect (= [[:p1 "m1"] [:p2 "m2"]] @calls))
      (expect (= "m2" (:routed/model result)))))

  (it "does NOT loop forever under :strategy :root (root honors exclusion)"
    (let [r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}])
          calls (atom [])
          result (router/with-provider-fallback r {:strategy :root}
                   (fn [provider model]
                     (swap! calls conj [(:id provider) (:name model)])
                     (if (= "m1" (:name model))
                       (model-unsupported-error "no such model")
                       (success-result 100))))]
      (expect (= [[:p1 "m1"] [:p2 "m2"]] @calls))
      (expect (= "m2" (:routed/model result)))))

  (it "an auth 400 is NOT mistaken for model-unsupported — it propagates"
    (let [r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}])
          calls (atom [])]
      (expect (throws? clojure.lang.ExceptionInfo
                #(router/with-provider-fallback r {}
                   (fn [_provider model]
                     (swap! calls conj (:name model))
                     (auth-400-error)))))
      ;; Only the first model attempted; no fleet churn on a credential bug.
      (expect (= ["m1"] @calls))))

  (it "every model unsupported → throws the concrete upstream reason"
    (let [r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1"
                :models [{:name "m1"} {:name "m2"}]}])
          thrown (try
                   (router/with-provider-fallback r {}
                     (fn [_provider _model] (model-unsupported-error "model_not_supported")))
                   :no-throw
                   (catch clojure.lang.ExceptionInfo e e))]
      (expect (not= :no-throw thrown))
      (expect (= 400 (:status (ex-data thrown))))
      (expect (= #{"m1" "m2"} (:model-unsupported (ex-data thrown)))))))

(defdescribe copilot-claude-dotted-or-dashed-test
  ;; Regression: vis's canonical Claude id is DASHED (claude-opus-4-8) but the
  ;; Copilot overlay (KNOWN_PROVIDER_MODELS) is keyed DOTTED (claude-opus-4.8).
  ;; A dashed name reaching the Copilot provider used to miss the overlay →
  ;; :api-style fell back to :openai-compatible-chat (/chat/completions, no
  ;; prompt cache) and Claude-on-Copilot 404'd. `model-key-variants` makes the
  ;; lookup tolerant so BOTH forms resolve to the native /v1/messages wire.
  (it "routes Claude-on-Copilot to the anthropic wire for dashed AND dotted ids"
    (doseq [model-name ["claude-opus-4-8" "claude-opus-4.8"
                        "claude-sonnet-4-6" "claude-sonnet-4.6"]]
      (let [p (router/normalize-provider 0 {:id :github-copilot-business
                                            :api-key "x"
                                            :models [{:name model-name}]})
            m (first (:models p))]
        (expect (= :anthropic (:api-style m)))
        (expect (= :server-managed (:reasoning-style m))))))

  (it "model-key-variants tries as-is first, then dot↔dash version variants"
    (expect (= ["claude-opus-4-8" "claude-opus-4.8"]
              (router/model-key-variants "claude-opus-4-8")))
    (expect (= ["claude-opus-4.8" "claude-opus-4-8"]
              (router/model-key-variants "claude-opus-4.8")))
    ;; GPT/glm dotted ids resolve as-is first (their catalog keys are dotted).
    (expect (= "gpt-5.4" (first (router/model-key-variants "gpt-5.4"))))))

(defdescribe known-provider-default-models-test
  (it "uses curated Mistral defaults when caller omits :models"
    (let [p (router/normalize-provider 0 {:id :mistral :api-key "x"})
          models (mapv :name (:models p))
          large (first (:models p))]
      (expect (= :mistral (:id p)))
      (expect (= :openai-compatible-chat (:api-style p)))
      (expect (= ["mistral-large-latest"
                  "mistral-medium-latest"
                  "mistral-small-latest"
                  "codestral-latest"]
                models))
      (expect (= 262144 (:context large)))
      (expect (= 262144 (:output-limit large))))))
