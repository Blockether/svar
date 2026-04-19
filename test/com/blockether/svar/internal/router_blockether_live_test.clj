(ns com.blockether.svar.internal.router-blockether-live-test
  "Live router-decision tests against the real Blockether One endpoint.

   Gated with `(when (blockether-enabled?) ...)` per the `CLAUDE.md` convention
   (no `^:live` metadata, no `lazytest/skip`). Without the env var, every `it`
   passes vacuously. With the key, we make real (tiny) LLM calls and verify
   the router's selection is what the decision logic claimed.

   Every test uses prompts sized to keep each call well under 200 output
   tokens — the total cost for running this file end-to-end is a few cents
   on the cheap models we route to (gpt-5-mini, glm-4.6v).

   What these tests PROVE that the unit tests cannot:
     - `normalize-provider` really does attach pricing to Blockether models.
     - `:optimize :cost` routing actually hits the expected endpoint.
     - `:reasoning :deep` reaches gpt-5-mini's `reasoning_effort` path and
       the response comes back parsed correctly (not an HTTP error).
     - `:routed/model` is the exact model name we picked (end-to-end wire)."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.core :as svar]
   [com.blockether.svar.internal.router :as router]))

;; =============================================================================
;; Env-var gating — matches `core_test.clj` `integration-tests-enabled?`
;; pattern: either the Blockether One key OR the legacy OpenAI-compat key
;; unlocks the tests.
;; =============================================================================

(defn- blockether-key []
  (or (System/getenv "BLOCKETHER_LLM_API_KEY")
    (System/getenv "BLOCKETHER_OPENAI_API_KEY")))

(defn- blockether-enabled? []
  (some? (blockether-key)))

(defn- blockether-base-url []
  (or (System/getenv "BLOCKETHER_LLM_API_BASE_URL")
    (System/getenv "BLOCKETHER_OPENAI_BASE_URL")
    "https://llm.blockether.com/v1"))

(defn- router-with-models
  "One Blockether provider with the given model names. Auto-attaches pricing
   + reasoning metadata from svar's `KNOWN_PROVIDER_MODELS` / `KNOWN_MODEL_METADATA`."
  [model-names]
  (svar/make-router
    [{:id :blockether
      :api-key (blockether-key)
      :base-url (blockether-base-url)
      :models (mapv (fn [n] {:name n}) model-names)}]))

;; Minimal spec — one string field. Cheap to generate.
(def ^:private answer-spec
  (svar/spec
    (svar/field svar/NAME :answer
      svar/TYPE svar/TYPE_STRING
      svar/CARDINALITY svar/CARDINALITY_ONE
      svar/DESCRIPTION "The single-word answer")))

;; =============================================================================
;; Smoke test — does the live endpoint actually respond?
;; =============================================================================

(defdescribe blockether-smoke-test
  (describe "basic ask! against Blockether"
    (it "returns a parsed structured response on gpt-5-mini"
      (when (blockether-enabled?)
        (let [r (router-with-models ["gpt-5-mini"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "What is 2+2? Reply with just the number.")]
                        :model "gpt-5-mini"})]
          (expect (map? result))
          (expect (map? (:result result)))
          (expect (string? (get-in result [:result :answer])))
          (expect (pos? (:duration-ms result)))
          (expect (= "gpt-5-mini" (:routed/model result)))
          (expect (= :blockether (:routed/provider-id result))))))))

;; =============================================================================
;; `:optimize :cost` end-to-end — pricing-driven selection really reaches
;; the cheaper model's endpoint, not the expensive one's.
;; =============================================================================

(defdescribe blockether-optimize-cost-live-test
  (describe ":optimize :cost picks the cheapest model in a multi-model fleet"
    (it "routes to gpt-5-mini (cheap) over gpt-4o (expensive) with real pricing"
      (when (blockether-enabled?)
        ;; Blockether prices (per 1M, blended input+output):
        ;;   gpt-4o      = 2.50 + 10.00 = $12.50
        ;;   gpt-5-mini  = 0.25 +  2.00 = $2.25  ← should win
        (let [r (router-with-models ["gpt-4o" "gpt-5-mini"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'ok'.")]
                        :routing {:optimize :cost}})]
          (expect (= "gpt-5-mini" (:routed/model result)))
          (expect (some? (get-in result [:result :answer]))))))))

(defdescribe blockether-optimize-intelligence-decision-test
  ":optimize :intelligence cross-provider logic is exhaustively covered by
   unit tests. Here we only verify the DECISION against the Blockether fleet
   without making a live call — Blockether's LiteLLM proxy currently deploys
   a narrower model set than KNOWN_PROVIDER_MODELS suggests (gpt-5 / gpt-5.1
   return `400 model_not_supported`), which would make a live intelligence
   test flaky. `select-provider` operates on `normalize-provider`'s attached
   metadata, so the decision is exercised even when the model isn't
   currently reachable on the proxy."

  (describe "select-provider picks the frontier-tier model"
    (it "gpt-5 (:frontier) beats gpt-4o (:high) and gpt-5-mini (:high)"
      (when (blockether-enabled?)
        ;; Metadata tiers from KNOWN_MODEL_METADATA:
        ;;   gpt-5       → :frontier (reasoning? true)
        ;;   gpt-4o      → :high
        ;;   gpt-5-mini  → :high (reasoning? true)
        (let [r (router-with-models ["gpt-5-mini" "gpt-4o" "gpt-5"])
              [_ model] (router/select-provider r {:prefer :intelligence})]
          (expect (= "gpt-5" (:name model))))))))

;; =============================================================================
;; `:reasoning :deep` end-to-end — abstract level is translated to
;; provider wire shape and accepted by the real endpoint.
;; =============================================================================

(defdescribe blockether-reasoning-end-to-end-test
  (describe ":reasoning :deep on an OpenAI-style reasoning model (gpt-5-mini)"
    (it "request succeeds with abstract :deep translated to reasoning_effort=high"
      (when (blockether-enabled?)
        (let [r (router-with-models ["gpt-5-mini"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'thought'.")]
                        :reasoning :deep})]
          ;; Wire went through OK — the server accepted the reasoning_effort
          ;; param and returned structured JSON.
          (expect (= "gpt-5-mini" (:routed/model result)))
          (expect (some? (get-in result [:result :answer])))
          ;; Some OpenAI-compatible endpoints surface reasoning tokens in usage.
          ;; Not all do, so this is a soft assertion via `some?` — if usage
          ;; exposes reasoning tokens, they should be non-negative.
          (when-let [usage (:tokens result)]
            (expect (or (nil? (:reasoning_tokens usage))
                      (>= (:reasoning_tokens usage) 0))))))))

  (describe ":reasoning :deep on a Z.ai-style thinking model (glm-4.6v)"
    (it "request succeeds with abstract :deep translated to thinking: {type:\"enabled\"}"
      (when (blockether-enabled?)
        (let [r (router-with-models ["glm-4.6v"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'thought'.")]
                        :reasoning :deep})]
          (expect (= "glm-4.6v" (:routed/model result)))
          (expect (some? (get-in result [:result :answer])))))))

  (describe ":preserved-thinking? on glm-4.6v (clear_thinking: false)"
    ;; Preserved Thinking (z.ai docs): the server retains reasoning_content
    ;; across assistant turns. We can't meaningfully test cross-turn retention
    ;; on a single-shot call, but we CAN verify the wire accepts the flag —
    ;; Blockether's LiteLLM proxy forwards extra_body fields through, so
    ;; a 400 here would indicate a client/proxy shape bug.
    (it "single-shot call succeeds with clear_thinking:false in the body"
      (when (blockether-enabled?)
        (let [r (router-with-models ["glm-4.6v"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'preserved'.")]
                        :reasoning :deep
                        :preserved-thinking? true})]
          (expect (= "glm-4.6v" (:routed/model result)))
          (expect (some? (get-in result [:result :answer])))))))

  (describe ":reasoning :deep combined with :optimize :cost"
    (it "picks the cheapest REASONING-CAPABLE model, not the cheapest overall"
      (when (blockether-enabled?)
        ;; Fleet:
        ;;   gpt-4o     = $12.50, not reasoning  → excluded by :reasoning filter
        ;;   gpt-5-mini = $2.25,  reasoning      (openai-effort)
        ;;   glm-4.6v   = $1.20,  reasoning      (zai-thinking) ← cheapest reasoning
        ;; Without :reasoning, glm-4.6v would still win on pure cost; with the
        ;; filter, gpt-4o is OUT and glm-4.6v remains the cheapest reasoner.
        (let [r (router-with-models ["gpt-4o" "gpt-5-mini" "glm-4.6v"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'ok'.")]
                        :routing {:optimize :cost}
                        :reasoning :deep})]
          (expect (= "glm-4.6v" (:routed/model result)))
          (expect (some? (get-in result [:result :answer]))))))))

;; =============================================================================
;; Filter rejects non-reasoning fleet — no network call needed, but we make
;; the router from real Blockether config so this is a true integration check
;; of the wiring (not just the mock unit test).
;; =============================================================================

(defdescribe blockether-reasoning-filter-rejection-test
  (describe ":reasoning filter on a fleet with zero reasoning-capable models"
    (it "select-provider returns nil when the fleet has no :reasoning? models"
      (when (blockether-enabled?)
        ;; gpt-4o and gpt-4.1 are both NOT flagged :reasoning? in KNOWN_MODEL_METADATA
        (let [r (router-with-models ["gpt-4o" "gpt-4.1"])
              {:keys [prefs]} (router/resolve-routing r {:reasoning :deep})
              selection (router/select-provider r prefs)]
          (expect (nil? selection))
          ;; And a live ask! should fail with the exhausted-providers error —
          ;; no partial work / silent drop of `:reasoning`.
          (try
            (svar/ask! r {:spec answer-spec
                          :messages [(svar/user "ignored")]
                          :reasoning :deep})
            (expect false "should have thrown — no reasoning-capable model")
            (catch clojure.lang.ExceptionInfo e
              (expect (= :svar.llm/all-providers-exhausted (:type (ex-data e)))))))))))

;; =============================================================================
;; Metadata sanity — Blockether really attaches pricing to its models
;; (proves `normalize-provider` is wiring `KNOWN_PROVIDER_MODELS`
;; correctly against the live provider id).
;; =============================================================================

(defdescribe blockether-pricing-attached-test
  (describe "normalize-provider attaches :pricing to Blockether models"
    (it "every declared Blockether model has :pricing {:input N :output M}"
      (when (blockether-enabled?)
        ;; Covers one model from each reasoning-style in Blockether's catalogue:
        ;;   gpt-4o      — non-reasoning OpenAI
        ;;   gpt-5-mini  — :openai-effort reasoning
        ;;   glm-4.6v    — :zai-thinking reasoning (vision)
        ;;   glm-4.7     — :zai-thinking reasoning (text-only)
        (let [names ["gpt-5-mini" "gpt-4o" "glm-4.6v" "glm-4.7"]
              r (router-with-models names)
              provider (first (:providers r))]
          (doseq [m (:models provider)]
            (let [pricing (:pricing m)]
              (expect (map? pricing))
              (expect (number? (:input pricing)))
              (expect (number? (:output pricing)))
              (expect (pos? (+ (:input pricing) (:output pricing))))))
          ;; And reasoning-capable models retain `:reasoning?` after merge
          (let [gpt-5-mini (first (filter #(= "gpt-5-mini" (:name %)) (:models provider)))
                glm-4-6v   (first (filter #(= "glm-4.6v"   (:name %)) (:models provider)))
                glm-4-7    (first (filter #(= "glm-4.7"    (:name %)) (:models provider)))
                gpt-4o     (first (filter #(= "gpt-4o"     (:name %)) (:models provider)))]
            (expect (true? (:reasoning? gpt-5-mini)))
            (expect (= :openai-effort (:reasoning-style gpt-5-mini)))
            (expect (true? (:reasoning? glm-4-6v)))
            (expect (= :zai-thinking (:reasoning-style glm-4-6v)))
            (expect (true? (:reasoning? glm-4-7)))
            (expect (= :zai-thinking (:reasoning-style glm-4-7)))
            (expect (not (:reasoning? gpt-4o)))))))))
