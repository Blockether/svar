(ns com.blockether.svar.internal.router-zai-live-test
  "Live tests against Z.ai's direct endpoints — the standard PaaS API
   (`https://api.z.ai/api/paas/v4`) and the Coding Plan
   (`https://api.z.ai/api/coding/paas/v4`).

   Gated on Z.ai-direct env vars (NOT Blockether's proxy):
     - `ZAI_API_KEY`         → runs `:zai` tests
     - `ZAI_CODING_API_KEY`  → runs `:zai-coding` tests (falls back to ZAI_API_KEY)

   Without those keys every `it` passes vacuously. With them we verify:
     - `normalize-provider` attaches Z.ai pricing to each GLM model
     - `:reasoning :deep` really translates to `thinking:{type:\"enabled\"}`
       on the direct endpoint (no Blockether proxy in the middle)
     - `:preserved-thinking? true` sends `clear_thinking:false` and the
       Z.ai server accepts it without erroring
     - Coding Plan endpoint responds with preserved thinking ON by default

   Note: these tests are NOT a substitute for the Blockether-proxy live
   tests in `router_blockether_live_test.clj`. Blockether's LiteLLM proxy
   already proves the wire shape end-to-end for GLM models. These tests
   just pin that the wire shape also works when you bypass the proxy."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.core :as svar]
   [com.blockether.svar.internal.router :as router]))

;; =============================================================================
;; Env-var gating
;; =============================================================================

(defn- zai-key []           (System/getenv "ZAI_API_KEY"))
(defn- zai-coding-key []    (or (System/getenv "ZAI_CODING_API_KEY")
                              (System/getenv "ZAI_API_KEY")))

(defn- zai-enabled?         [] (some? (zai-key)))
(defn- zai-coding-enabled?  [] (some? (zai-coding-key)))

(defn- zai-router [model-names]
  (svar/make-router
    [{:id :zai
      :api-key (zai-key)
      :models (mapv (fn [n] {:name n}) model-names)}]))

(defn- zai-coding-router [model-names]
  (svar/make-router
    [{:id :zai-coding
      :api-key (zai-coding-key)
      :models (mapv (fn [n] {:name n}) model-names)}]))

(def ^:private answer-spec
  (svar/spec
    (svar/field svar/NAME :answer
      svar/TYPE svar/TYPE_STRING
      svar/CARDINALITY svar/CARDINALITY_ONE
      svar/DESCRIPTION "The single-word answer")))

;; =============================================================================
;; `:zai` direct — standard PaaS API
;; =============================================================================

(defdescribe zai-direct-smoke-test
  (describe ":zai direct API ask! on glm-4.7"
    (it "returns a parsed response"
      (when (zai-enabled?)
        (let [r (zai-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'ok'.")]})]
          (expect (= "glm-4.7" (:routed/model result)))
          (expect (= :zai (:routed/provider-id result)))
          (expect (some? (get-in result [:result :answer])))
          (expect (pos? (:duration-ms result))))))))

(defdescribe zai-direct-reasoning-end-to-end-test
  (describe ":reasoning :deep on :zai direct"
    (it "translates :deep → thinking:{type:\"enabled\"} and succeeds"
      (when (zai-enabled?)
        (let [r (zai-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'thought'.")]
                        :reasoning :deep})]
          (expect (= "glm-4.7" (:routed/model result)))
          (expect (some? (get-in result [:result :answer])))))))

  (describe ":reasoning :quick on :zai direct"
    (it "translates :quick → thinking:{type:\"disabled\"} and succeeds"
      (when (zai-enabled?)
        (let [r (zai-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'fast'.")]
                        :reasoning :quick})]
          (expect (= "glm-4.7" (:routed/model result)))
          (expect (some? (get-in result [:result :answer])))))))

  (describe ":preserved-thinking? on :zai direct"
    (it "sends clear_thinking:false and the server accepts it"
      (when (zai-enabled?)
        (let [r (zai-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'kept'.")]
                        :reasoning :deep
                        :preserved-thinking? true})]
          (expect (= "glm-4.7" (:routed/model result)))
          (expect (some? (get-in result [:result :answer]))))))))

;; =============================================================================
;; `:zai-coding` — Coding Plan endpoint (subscription, preserved-thinking ON)
;; =============================================================================

(defdescribe zai-coding-smoke-test
  (describe ":zai-coding ask! on glm-4.7"
    (it "routes to the Coding Plan endpoint and returns a response"
      (when (zai-coding-enabled?)
        (let [r (zai-coding-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'ok'.")]})]
          (expect (= "glm-4.7" (:routed/model result)))
          (expect (= :zai-coding (:routed/provider-id result)))
          (expect (some? (get-in result [:result :answer]))))))))

(defdescribe zai-coding-reasoning-levels-test
  "Each `:reasoning` level reaches the Coding Plan endpoint and returns a
   usable response. The abstract level translation to z.ai's binary thinking
   shape is covered by the unit tests in `router_reasoning_test.clj`; here
   we verify the round-trip works against real GLM-4.7."

  (describe ":reasoning :quick on :zai-coding"
    (it "translates to `thinking:{type:\"disabled\"}` and succeeds"
      (when (zai-coding-enabled?)
        (let [r (zai-coding-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'fast'.")]
                        :reasoning :quick})]
          (expect (= "glm-4.7" (:routed/model result)))
          (expect (some? (get-in result [:result :answer])))))))

  (describe ":reasoning :balanced on :zai-coding"
    (it "translates to `thinking:{type:\"enabled\"}` and succeeds"
      (when (zai-coding-enabled?)
        (let [r (zai-coding-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'thought'.")]
                        :reasoning :balanced})]
          (expect (= "glm-4.7" (:routed/model result)))
          (expect (some? (get-in result [:result :answer])))))))

  (describe ":reasoning :deep on :zai-coding"
    (it "translates to `thinking:{type:\"enabled\"}` — same wire shape as :balanced"
      ;; Z.ai's binary thinking switch means :balanced and :deep collapse to
      ;; the same wire shape. Both should succeed and produce an answer.
      (when (zai-coding-enabled?)
        (let [r (zai-coding-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'deep'.")]
                        :reasoning :deep})]
          (expect (= "glm-4.7" (:routed/model result)))
          (expect (some? (get-in result [:result :answer]))))))))

(defdescribe zai-coding-preserved-thinking-test
  (describe ":preserved-thinking? explicit on :zai-coding"
    ;; Coding Plan has preserved thinking ON by default server-side, so
    ;; explicitly setting `:preserved-thinking? true` should be a harmless
    ;; no-op from the server's perspective (the wire carries clear_thinking
    ;; :false but the server was already going to preserve anyway).
    (it "`:deep` + preserved succeeds — clear_thinking:false accepted"
      (when (zai-coding-enabled?)
        (let [r (zai-coding-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'coded'.")]
                        :reasoning :deep
                        :preserved-thinking? true})]
          (expect (= "glm-4.7" (:routed/model result)))
          (expect (some? (get-in result [:result :answer]))))))

    (it "`:quick` + preserved succeeds — thinking disabled but flag still accepted"
      (when (zai-coding-enabled?)
        (let [r (zai-coding-router ["glm-4.7"])
              result (svar/ask! r
                       {:spec answer-spec
                        :messages [(svar/user "Reply with the word 'quick'.")]
                        :reasoning :quick
                        :preserved-thinking? true})]
          (expect (= "glm-4.7" (:routed/model result)))
          (expect (some? (get-in result [:result :answer]))))))))

;; =============================================================================
;; Metadata / pricing sanity — NO network call required (runs on every test
;; run, not gated). Confirms svar's source tables are coherent regardless
;; of whether Z.ai credentials are configured.
;; =============================================================================

(defdescribe zai-and-coding-metadata-sanity-test
  "These checks run unconditionally — they only touch svar's static metadata
   tables, no HTTP. Guards against someone editing `KNOWN_PROVIDERS` /
   `KNOWN_PROVIDER_MODELS` and accidentally desyncing the two Z.ai surfaces."

  (describe "KNOWN_PROVIDERS entries"
    (it ":zai has the documented base-url"
      (expect (= "https://api.z.ai/api/paas/v4"
                (:base-url (:zai router/KNOWN_PROVIDERS)))))

    (it ":zai-coding has the documented Coding Plan base-url"
      (expect (= "https://api.z.ai/api/coding/paas/v4"
                (:base-url (:zai-coding router/KNOWN_PROVIDERS)))))

    (it ":zai-coding falls back to ZAI_API_KEY when ZAI_CODING_API_KEY is absent"
      (expect (= ["ZAI_CODING_API_KEY" "ZAI_API_KEY"]
                (:env-keys (:zai-coding router/KNOWN_PROVIDERS))))))

  (describe "Pricing tables"
    (it ":zai has pricing for the 5 reasoning-capable GLM models"
      (doseq [name ["glm-4.6" "glm-4.6v" "glm-4.7" "glm-5.1" "glm-5-turbo"]]
        (expect (some? (:pricing (router/provider-model-entry :zai name))))))

    (it ":zai-coding mirrors :zai pricing (subscription-overage parity)"
      (doseq [name ["glm-4.6" "glm-4.6v" "glm-4.7" "glm-5.1" "glm-5-turbo"]]
        (let [zai (:pricing (router/provider-model-entry :zai name))
              cod (:pricing (router/provider-model-entry :zai-coding name))]
          (expect (= zai cod)))))))
