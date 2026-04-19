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
   :api-usage {:prompt_tokens 10 :completion_tokens 20 :total_tokens token-count}
   :tokens {:total token-count}})

(defn- transient-error
  "Throws an ex-info that `router-transient-error?` will classify as retryable."
  [status]
  (throw (ex-info (str "HTTP " status)
           {:type :svar.core/http-error :status status})))

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
        (expect (nil? (:require-reasoning? prefs)))))))

;; =============================================================================
;; Tier 1 — with-provider-fallback happy/sad paths
;; =============================================================================

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
      (expect (nil? (:routed/fallback-trace result)))
      ;; Top-level totals
      (expect (= 1   (get-in stats [:total :requests])))
      (expect (= 150 (get-in stats [:total :tokens])))
      ;; Per-provider avg latency — work took 50ms on our mock clock
      (expect (pos? (get-in stats [:providers :solo :cumulative :avg-latency-ms]))))))

(defdescribe with-provider-fallback-transient-then-success-test
  "P1 returns a transient error (e.g. 503), router retries and eventually
   succeeds on P2 once P1's CB opens.

   Note: `with-provider-fallback` does NOT exclude previously-tried providers
   from subsequent `select-and-claim!` calls — it relies on the circuit
   breaker to take P1 out of rotation. So with `:failure-threshold 1` a
   single P1 failure opens the CB and P2 wins immediately."

  (it "falls through to P2 and trace includes P1's error"
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
                       :p1 (transient-error 503)
                       :p2 (success-result 100))))]
      (expect (= 2 @calls))
      (expect (= :p2 (:routed/provider-id result)))
      (let [trace (:routed/fallback-trace result)]
        (expect (vector? trace))
        (expect (= 1 (count trace)))
        (expect (= :p1 (get-in trace [0 :provider-id])))
        (expect (= 503 (get-in trace [0 :status])))))))

(defdescribe with-provider-fallback-all-exhausted-test
  "Every provider returns a transient error → `:svar.llm/all-providers-exhausted`
   with `:tried` and `:fallback-trace` populated."

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
          (expect (= #{:p1 :p2} (:tried (ex-data e)))))))))

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
      ;; and go straight to P2 with no fallback trace.
      (let [result (router/with-provider-fallback r {:strategy :root}
                     (fn [_ _] (success-result 10)))]
        (expect (= :p2 (:routed/provider-id result)))
        (expect (nil? (:routed/fallback-trace result)))))))

(defdescribe circuit-breaker-half-open-cycle-test
  "After `:recovery-ms` elapses the CB effectively reports `:half-open`; a
   successful probe closes it; a failed probe immediately re-opens it."

  (it "open → half-open (time) → closed (probe success)"
    (let [[clock clock-atom] (mock-clock)
          r (llm/make-router
              [{:id :p1 :api-key "k" :base-url "http://p1" :models [{:name "m1"}]}
               {:id :p2 :api-key "k" :base-url "http://p2" :models [{:name "m2"}]}]
              {:clock clock
               :failure-threshold 2
               :recovery-ms 10000})]
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
              [;; blockether gpt-5-mini: 0.25 + 2.00 = 2.25
               {:id :blockether :api-key "k"
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
    ;;   gpt-5.1  — 1.00 + 1.00  = $2.00/M blended,   reasoning
    (let [r (llm/make-router
              [{:id :openai :api-key "k"
                :models [{:name "gpt-4o"} {:name "gpt-5-mini"} {:name "gpt-5.1"}]}])]
      ;; Without filter, cheapest-by-pricing wins: gpt-5.1 ($2.00)
      (let [[_ model] (router/select-provider r {:prefer :cost})]
        (expect (= "gpt-5.1" (:name model))))
      ;; With :reasoning :deep filter, still gpt-5.1 (it IS reasoning-capable, cheapest)
      (let [{:keys [prefs]} (router/resolve-routing r {:optimize :cost :reasoning :deep})
            [_ model] (router/select-provider r prefs)]
        (expect (= "gpt-5.1" (:name model))))))

  (it "`:reasoning :deep` filters OUT a non-reasoning model even when it's cheapest"
    ;; Construct a case where the cheapest model is explicitly NOT reasoning,
    ;; to prove the filter actually changes selection:
    ;;   gpt-4o (non-reasoning, $12.50)
    ;;   o3-mini (reasoning, $1.10 + $4.40 = $5.50)
    ;;   gpt-5.1 (reasoning, $2.00) — cheapest reasoning-capable
    ;; Without the filter gpt-5.1 wins anyway (both cheapest AND reasoning),
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

  (it "`:reasoning :deep` with zero reasoning-capable models returns nil selection"
    ;; OpenAI fleet without any reasoning-capable model
    (let [r (llm/make-router
              [{:id :openai :api-key "k"
                :models [{:name "gpt-4o"} {:name "gpt-4.1"}]}])]
      (let [{:keys [prefs]} (router/resolve-routing r {:reasoning :deep})
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
                 :api-usage {:prompt_tokens 1000 :completion_tokens 2000 :total_tokens 3000}}))
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
                         :api-usage {:prompt_tokens 100 :completion_tokens 50
                                     :total_tokens 150}}))
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

    (describe "`:frontier :slow` family (o-series / reasoner / thinking)"
      (it "o1 prefix"        (expect (= :frontier (:intelligence (infer "o1-new")))))
      (it "o3- prefix"       (expect (= :frontier (:intelligence (infer "o3-super")))))
      (it "o4- prefix"       (expect (= :frontier (:intelligence (infer "o4-large")))))
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
                                 :api-usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (llm/ask! r {:spec {} :messages [{:role "user" :content "hi"}]
                     :routing {:optimize :cost}
                     :reasoning :deep}))
      ;; Without :reasoning the router would have picked cheap-dumb; with it
      ;; the filter excludes cheap-dumb and pricey-smart wins despite being
      ;; ~25x more expensive.
      (expect (= "pricey-smart" (:model @captured))))))
