(ns com.blockether.svar.internal.format-retry-live-test
  "Live tests against GLM-serving endpoints, pinning the prose-leak
   hardening introduced in v0.4: `:format-retries`, `:json-object-mode?`,
   and the full forensic envelope on schema rejection.

   Endpoints exercised:
     - `:zai-coding`  -> `https://api.z.ai/api/coding/paas/v4`  (Coding Plan)
     - `:blockether`  -> `https://llm.blockether.com/v1`        (proxy in front of GLM)

   The Z.ai direct PaaS endpoint (`:zai` -> `https://api.z.ai/api/paas/v4`)
   is intentionally NOT covered here: the prose-leak quirk reproduces on the
   subscription-billed Coding Plan and the Blockether proxy that fronts
   GLM, which are the surfaces real users hit. Direct PaaS coverage lives
   separately in `router_zai_live_test.clj` for metadata wiring sanity.

   Gated on whichever env var unlocks each:
     - `ZAI_CODING_PLAN_API_KEY`  -> runs `:zai-coding` tests
        Fallback chain: ZAI_CODING_PLAN_API_KEY -> Z_AI_CODING_API_KEY -> ZAI_API_KEY.
     - `BLOCKETHER_LLM_API_KEY`   -> runs `:blockether` proxy tests
        Fallback chain: BLOCKETHER_LLM_API_KEY -> BLOCKETHER_OPENAI_API_KEY.

   Without those keys every `it` passes vacuously. With them we verify:

     - `glm-5.1` and `glm-4.7` carry `:json-object-mode? true` in their
       provider-scoped metadata (regression -- the auto-injection path
       depends on this metadata).
     - `:json-object-mode? true` (default for GLM) reaches the wire
       without a 400 -- the endpoint accepts
       `response_format: {type: \"json_object\"}`.
     - `:json-object-mode? false` opt-out reaches the wire without injection
       and still succeeds.
     - `:format-retries 2` on a complex spec under `:reasoning :deep`
       succeeds end-to-end. If the model happens to emit prose on the first
       attempt (the GLM prose-leak quirk), `:format-attempts` is populated
       with the failed attempt's full content; otherwise it isn't.
     - Caller `:extra-body :response_format` is forwarded verbatim and
       overrides svar's auto-injection.

   Note: these do NOT attempt to deterministically force the GLM-5.1 prose
   regression -- that's a probabilistic provider quirk we cannot reliably
   trigger. The tests verify the wire shape svar produces is one the
   endpoint accepts, and that a real round-trip with retries enabled
   succeeds.

   Token spend: each `it` makes a single tiny call (one-word answer). Full
   run is well under 1c across both endpoints."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.core :as svar]
   [com.blockether.svar.internal.router :as router]))

;; =============================================================================
;; Env-var gating
;; =============================================================================

(defn- zai-coding-key [] (or (System/getenv "ZAI_CODING_PLAN_API_KEY")
                           (System/getenv "Z_AI_CODING_API_KEY")
                           (System/getenv "ZAI_API_KEY")))
(defn- blockether-key [] (or (System/getenv "BLOCKETHER_LLM_API_KEY")
                           (System/getenv "BLOCKETHER_OPENAI_API_KEY")))
(defn- blockether-base-url []
  (or (System/getenv "BLOCKETHER_LLM_API_BASE_URL")
    (System/getenv "BLOCKETHER_OPENAI_BASE_URL")
    "https://llm.blockether.com/v1"))

(defn- zai-coding-enabled? [] (some? (zai-coding-key)))
(defn- blockether-enabled? [] (some? (blockether-key)))

(defn- zai-coding-router [model-names]
  (svar/make-router
    [{:id :zai-coding
      :api-key (zai-coding-key)
      :models (mapv (fn [n] {:name n}) model-names)}]))

(defn- blockether-router [model-names]
  (svar/make-router
    [{:id :blockether
      :api-key (blockether-key)
      :base-url (blockether-base-url)
      :models (mapv (fn [n] {:name n}) model-names)}]))

;; =============================================================================
;; Specs
;; =============================================================================

(def ^:private answer-spec
  (svar/spec
    (svar/field svar/NAME :answer
      svar/TYPE svar/TYPE_STRING
      svar/CARDINALITY svar/CARDINALITY_ONE
      svar/DESCRIPTION "The single-word answer")))

;; A multi-field iteration-envelope-shaped spec -- multiple required fields
;; with strict typing. This is the flavour of spec where GLM-5.1 historically
;; leaks prose into `content` instead of producing the schema-conformant
;; object, especially under `:reasoning :deep`.
(def ^:private iteration-shaped-spec
  (svar/spec
    (svar/field svar/NAME :thinking
      svar/TYPE svar/TYPE_STRING
      svar/CARDINALITY svar/CARDINALITY_ONE
      svar/DESCRIPTION "What you're thinking about the task")
    (svar/field svar/NAME :answer
      svar/TYPE svar/TYPE_STRING
      svar/CARDINALITY svar/CARDINALITY_ONE
      svar/DESCRIPTION "The single-word answer")
    (svar/field svar/NAME :confidence
      svar/TYPE svar/TYPE_KEYWORD
      svar/CARDINALITY svar/CARDINALITY_ONE
      svar/DESCRIPTION "How confident you are: low, medium, or high"
      svar/VALUES ["low" "medium" "high"])))

;; =============================================================================
;; Metadata sanity -- GLM models carry :json-object-mode? after normalize
;; =============================================================================

(defdescribe glm-json-object-mode-metadata-test
  "Verifies `KNOWN_PROVIDER_MODELS` flags GLM models for auto-injection on
   the endpoints exercised below. Runs unconditionally -- only touches static
   tables, no HTTP."

  (describe ":zai-coding provider GLM models"
    (it "glm-4.7 has :json-object-mode? true"
      (expect (true? (:json-object-mode?
                      (router/provider-model-entry :zai-coding "glm-4.7")))))
    (it "glm-5.1 has :json-object-mode? true"
      (expect (true? (:json-object-mode?
                      (router/provider-model-entry :zai-coding "glm-5.1"))))))

  (describe ":blockether proxy GLM models"
    (it "glm-5.1 has :json-object-mode? true"
      (expect (true? (:json-object-mode?
                      (router/provider-model-entry :blockether "glm-5.1")))))
    (it "glm-4.7 has :json-object-mode? true"
      (expect (true? (:json-object-mode?
                      (router/provider-model-entry :blockether "glm-4.7"))))))

  (describe "non-GLM models are NOT flagged"
    (it "gpt-5-mini has no :json-object-mode? flag"
      (expect (not (:json-object-mode?
                    (router/provider-model-entry :openai "gpt-5-mini")))))
    (it "claude-sonnet-4-6 has no :json-object-mode? flag"
      (expect (not (:json-object-mode?
                    (router/provider-model-entry :anthropic "claude-sonnet-4-6")))))))

(defdescribe glm-router-propagates-json-object-mode-test
  "Verifies `normalize-provider` carries the metadata flag onto the resolved
   model-map. Runs unconditionally."

  (describe "make-router on :zai-coding with glm-4.7"
    (it "model-map gets :json-object-mode? true via Coding-Plan-scoped metadata"
      (let [r (svar/make-router
                [{:id :zai-coding
                  :api-key "sk-fake"
                  :models [{:name "glm-4.7"}]}])
            model (first (filter #(= "glm-4.7" (:name %))
                           (:models (first (:providers r)))))]
        (expect (true? (:json-object-mode? model))))))

  (describe "make-router on :blockether with glm-4.7"
    (it "model-map gets :json-object-mode? true via Blockether-scoped metadata"
      (let [r (svar/make-router
                [{:id :blockether
                  :api-key "sk-fake"
                  :base-url "https://llm.blockether.com/v1"
                  :models [{:name "glm-4.7"}]}])
            model (first (filter #(= "glm-4.7" (:name %))
                           (:models (first (:providers r)))))]
        (expect (true? (:json-object-mode? model)))))))

;; =============================================================================
;; Live: :zai-coding endpoint (Coding Plan)
;; =============================================================================

(defdescribe zai-coding-glm-json-object-mode-accepted-live-test
  (describe ":json-object-mode? auto-on for glm-4.7 on :zai-coding"
    (it "Coding Plan endpoint accepts response_format json_object (no 400)"
      (when (zai-coding-enabled?)
        (let [r (zai-coding-router ["glm-4.7"])
              ;; No explicit :json-object-mode? -- model metadata flips it on.
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'coded'.")]
                        :reasoning :deep})]
          (expect (= "glm-4.7" (:routed/model result)))
          (expect (= :zai-coding (:routed/provider-id result)))
          (expect (some? (get-in result [:result :answer])))
          (expect (pos? (:duration-ms result)))))))

  (describe ":json-object-mode? false opt-out for glm-4.7 on :zai-coding"
    (it "explicit false suppresses auto-injection and still succeeds"
      (when (zai-coding-enabled?)
        (let [r (zai-coding-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'optout'.")]
                        :json-object-mode? false})]
          (expect (= :zai-coding (:routed/provider-id result)))
          (expect (string? (get-in result [:result :answer])))))))

  (describe ":extra-body :response_format wins on :zai-coding"
    (it "caller's explicit response_format is forwarded verbatim"
      (when (zai-coding-enabled?)
        (let [r (zai-coding-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'wins'.")]
                        :extra-body {:response_format {:type "json_object"}}})]
          (expect (= :zai-coding (:routed/provider-id result)))
          (expect (string? (get-in result [:result :answer]))))))))

(defdescribe zai-coding-glm-format-retries-live-test
  (describe ":format-retries 2 on glm-4.7 :reasoning :deep on :zai-coding"
    (it "Coding Plan + format-retries + iteration-shaped spec round-trip succeeds"
      (when (zai-coding-enabled?)
        (let [r (zai-coding-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec iteration-shaped-spec
                        :messages [(svar/user "Pick one word for 'hello'. Be brief.")]
                        :reasoning :deep
                        :format-retries 2})]
          (expect (= "glm-4.7" (:routed/model result)))
          (expect (= :zai-coding (:routed/provider-id result)))
          (expect (some? (get-in result [:result :answer])))
          (expect (string? (get-in result [:result :thinking])))
          (expect (#{:low :medium :high} (get-in result [:result :confidence])))
          ;; If retries fired, every recorded attempt carries the FULL content
          ;; verbatim (no truncation invariant).
          (when-let [attempts (:format-attempts result)]
            (doseq [att attempts]
              (when-not (:ok? att)
                (expect (string? (:content att)))
                (expect (some? (:reason att))))))))))

  (describe ":format-retries 2 on glm-5.1 :reasoning :deep on :zai-coding"
    (it "the historical prose-leak combo -- round-trip succeeds"
      (when (zai-coding-enabled?)
        (let [r (zai-coding-router ["glm-5.1"])
              result (svar/ask! r
                       {:spec iteration-shaped-spec
                        :messages [(svar/user "Pick one word for 'goodbye'. Be brief.")]
                        :reasoning :deep
                        :format-retries 2})]
          (expect (= "glm-5.1" (:routed/model result)))
          (expect (= :zai-coding (:routed/provider-id result)))
          (expect (some? (get-in result [:result :answer])))
          (expect (string? (get-in result [:result :thinking])))
          (expect (#{:low :medium :high} (get-in result [:result :confidence])))))))

  (describe ":format-retries 0 on glm-4.7 :reasoning :quick on :zai-coding"
    (it "baseline: single attempt, simple spec, :format-attempts absent on success"
      (when (zai-coding-enabled?)
        (let [r (zai-coding-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'baseline'.")]
                        :reasoning :quick
                        :format-retries 0})]
          (expect (= :zai-coding (:routed/provider-id result)))
          (expect (some? (get-in result [:result :answer])))
          (expect (nil? (:format-attempts result))))))))

;; =============================================================================
;; Live: Blockether proxy in front of GLM
;; =============================================================================
;;
;; Blockether's LiteLLM proxy fronts both OpenAI- and GLM-style backends and
;; was the surface where the prose-leak quirk originally surfaced for users
;; (`glm-5.1` via the Blockether One endpoint emitting prose under `:deep`).
;; The same hardening must hold here:
;;
;;   - `:json-object-mode?` flag travels through the proxy intact
;;     (LiteLLM forwards `response_format` to the upstream GLM provider).
;;   - `:format-retries` round-trip succeeds.
;;   - The exact spec shape from the bug report (multi-field iteration-like
;;     map under `:reasoning :deep`) lands a parsed result.

(defdescribe blockether-glm-json-object-mode-accepted-live-test
  (describe ":json-object-mode? auto-on for glm-4.7 via :blockether proxy"
    (it "Blockether proxy forwards response_format json_object to GLM (no 400)"
      (when (blockether-enabled?)
        (let [r (blockether-router ["glm-4.7"])
              ;; No explicit :json-object-mode? -- model metadata flips it on,
              ;; svar injects, proxy forwards, GLM accepts.
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'proxied'.")]})]
          (expect (= "glm-4.7" (:routed/model result)))
          (expect (= :blockether (:routed/provider-id result)))
          (expect (string? (get-in result [:result :answer])))
          (expect (pos? (:duration-ms result)))))))

  (describe ":json-object-mode? auto-on for glm-5.1 via :blockether proxy"
    ;; Direct, standalone proof that glm-5.1 via :blockether accepts
    ;; svar's auto-injected `response_format: {type: "json_object"}`.
    ;; Simple answer-spec, no retries, no reasoning -- isolates the
    ;; json-mode wire shape from everything else. If THIS fails on a future
    ;; Blockether/LiteLLM deploy, the bigger combined tests below will fail
    ;; too, so this is the cheapest signal that json-mode wiring still works
    ;; for the model that historically leaks prose.
    (it "glm-5.1 via Blockether accepts response_format json_object (no 400)"
      (when (blockether-enabled?)
        (let [r (blockether-router ["glm-5.1"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'jsonmode'.")]})]
          (expect (= "glm-5.1" (:routed/model result)))
          (expect (= :blockether (:routed/provider-id result)))
          (expect (string? (get-in result [:result :answer])))
          (expect (pos? (:duration-ms result))))))

    (it "glm-5.1 via Blockether + :reasoning :deep + json-mode (default) succeeds"
      ;; Proves json-mode auto-injection survives under :deep reasoning
      ;; specifically (the regime where the prose-leak quirk historically
      ;; fires).
      (when (blockether-enabled?)
        (let [r (blockether-router ["glm-5.1"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'deepjson'.")]
                        :reasoning :deep})]
          (expect (= "glm-5.1" (:routed/model result)))
          (expect (= :blockether (:routed/provider-id result)))
          (expect (string? (get-in result [:result :answer])))))))

  (describe ":json-object-mode? false opt-out for glm-4.7 via :blockether proxy"
    (it "explicit false suppresses auto-injection and still succeeds via proxy"
      (when (blockether-enabled?)
        (let [r (blockether-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'optout'.")]
                        :json-object-mode? false})]
          (expect (= :blockether (:routed/provider-id result)))
          (expect (string? (get-in result [:result :answer])))))))

  (describe ":extra-body :response_format wins via :blockether proxy"
    (it "caller's explicit response_format reaches the proxy verbatim"
      (when (blockether-enabled?)
        (let [r (blockether-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'wins'.")]
                        :extra-body {:response_format {:type "json_object"}}})]
          (expect (= :blockether (:routed/provider-id result)))
          (expect (string? (get-in result [:result :answer]))))))))

(defdescribe blockether-glm-format-retries-live-test
  (describe ":format-retries 2 on glm-4.7 :reasoning :deep via :blockether proxy"
    (it "prose-leak-prone shape -- multi-field spec round-trip succeeds"
      (when (blockether-enabled?)
        (let [r (blockether-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec iteration-shaped-spec
                        :messages [(svar/user "Pick one word for 'hello'. Be brief.")]
                        :reasoning :deep
                        :format-retries 2})]
          (expect (= "glm-4.7" (:routed/model result)))
          (expect (= :blockether (:routed/provider-id result)))
          (expect (some? (get-in result [:result :answer])))
          (expect (string? (get-in result [:result :thinking])))
          (expect (#{:low :medium :high} (get-in result [:result :confidence])))
          ;; If retries fired through the proxy, every recorded attempt
          ;; carries the FULL content verbatim (no truncation).
          (when-let [attempts (:format-attempts result)]
            (doseq [att attempts]
              (when-not (:ok? att)
                (expect (string? (:content att)))
                (expect (some? (:reason att))))))))))

  (describe ":format-retries 2 on glm-5.1 :reasoning :deep via :blockether proxy"
    (it "the exact historical-offender model+endpoint+reasoning combo -- round-trip succeeds"
      (when (blockether-enabled?)
        (let [r (blockether-router ["glm-5.1"])
              result (svar/ask! r
                       {:spec iteration-shaped-spec
                        :messages [(svar/user "Pick one word for 'goodbye'. Be brief.")]
                        :reasoning :deep
                        :format-retries 2})]
          (expect (= "glm-5.1" (:routed/model result)))
          (expect (= :blockether (:routed/provider-id result)))
          (expect (some? (get-in result [:result :answer])))
          (expect (string? (get-in result [:result :thinking])))
          (expect (#{:low :medium :high} (get-in result [:result :confidence])))))))

  (describe ":format-retries 0 on glm-4.7 :reasoning :quick via :blockether proxy"
    (it "baseline: single attempt, simple spec, :format-attempts absent on success"
      (when (blockether-enabled?)
        (let [r (blockether-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'baseline'.")]
                        :reasoning :quick
                        :format-retries 0})]
          (expect (= :blockether (:routed/provider-id result)))
          (expect (some? (get-in result [:result :answer])))
          (expect (nil? (:format-attempts result))))))))
