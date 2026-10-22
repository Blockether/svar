(ns com.blockether.svar.internal.router-zai-catalog-test
  "Static checks of the z.ai provider and GLM model tables. No HTTP calls."
  (:require [lazytest.core :refer [defdescribe describe expect it]]
            [com.blockether.svar.internal.router :as router]))

(defdescribe
  zai-and-coding-metadata-sanity-test
  "These checks run unconditionally — they only touch svar's static metadata
   tables, no HTTP. Guards against someone editing `KNOWN_PROVIDERS` /
   `KNOWN_PROVIDER_MODELS` and accidentally desyncing the two Z.ai surfaces."
  (describe "KNOWN_PROVIDERS entries"
            ;; GLM rides the z.ai ANTHROPIC-Messages endpoint (native tool_use). The
            ;; chat wire (/paas/v4) poisons tool calls with an XML prompt, so all zai
            ;; providers point at /api/anthropic/v1 with :api-style :anthropic.
            (it ":zai uses the Anthropic-Messages endpoint (native tool_use)"
                (expect (= "https://api.z.ai/api/anthropic/v1"
                           (:base-url (:zai router/KNOWN_PROVIDERS))))
                (expect (= :anthropic (:api-style (:zai router/KNOWN_PROVIDERS)))))
            (it ":zai-coding uses the Anthropic-Messages endpoint"
                (expect (= "https://api.z.ai/api/anthropic/v1"
                           (:base-url (:zai-coding router/KNOWN_PROVIDERS))))
                (expect (= :anthropic (:api-style (:zai-coding router/KNOWN_PROVIDERS)))))
            (it ":zai-coding falls back to ZAI_API_KEY when ZAI_CODING_API_KEY is absent"
                (expect (= ["ZAI_CODING_API_KEY" "ZAI_API_KEY"]
                           (:env-keys (:zai-coding router/KNOWN_PROVIDERS))))))
  (describe
    "Pricing tables"
    (it ":zai has pricing for the 6 reasoning-capable GLM models"
        (doseq [name ["glm-4.6" "glm-4.6v" "glm-4.7" "glm-5.1" "glm-5-turbo" "glm-5v-turbo"]]
          (expect (some? (:pricing (router/provider-model-entry :zai name))))))
    (it ":zai-coding mirrors :zai pricing (subscription-overage parity)"
        ;; Compare per-token public rates only — the catalog reports `:cache-read 0`
        ;; on the coding-plan entry and a real cache rate on direct :zai. The
        ;; subscription-overage invariant is about input/output/cached-input parity.
        (let [keys-of-interest [:input :cached-input :output]]
          (doseq [name ["glm-4.6" "glm-4.6v" "glm-4.7" "glm-5.1" "glm-5-turbo" "glm-5v-turbo"]]
            (let [zai (select-keys (:pricing (router/provider-model-entry :zai name))
                                   keys-of-interest)
                  cod (select-keys (:pricing (router/provider-model-entry :zai-coding name))
                                   keys-of-interest)]

              (expect (= zai cod)))))))
  (describe "Output ceiling"
            ;; z.ai answers max_tokens 131073 with HTTP 400 code 1210 "range [1,131072]"
            ;; for every capped GLM, so a caller may double the 32768 agentic cap once.
            (it "records the hard max_tokens ceiling above every GLM agentic cap"
                (doseq [pid
                        [:zai :zai-coding :zai-coding-plan]

                        name
                        ["glm-5.1" "glm-5.2" "glm-5.3" "glm-5.3-flash" "glm-5.3-flashx"
                         "glm-5-turbo" "glm-5v-turbo"]]

                  (let [m (router/provider-model-entry pid name)]
                    (expect (= 32768 (router/model-output-budget m)) (str pid " " name))
                    (expect (= 131072 (router/model-output-ceiling m)) (str pid " " name)))))
            (it "falls back to the output limit when no separate ceiling is declared"
                (expect (= 8000 (router/model-output-ceiling {:output-limit 8000})))
                (expect (nil? (router/model-output-ceiling {:context 100000})))))
  ;; GLM-5.3 landed on the Coding Plan first: models.dev carries it under
  ;; `zai-coding-plan` / `zhipuai-coding-plan`, NOT yet under retail `zai`,
  ;; so svar's own overlay is the only source of its pricing, context, output
  ;; cap and effort rungs on every z.ai surface.
  (describe "GLM-5.3"
            (it "remains curated on every z.ai surface"
                (doseq [pid [:zai :zai-coding :zai-coding-plan]]
                  (expect (contains? (set (router/provider-default-models pid)) "glm-5.3"))))
            (it "carries retail pricing, 1M context and the agentic output cap"
                (doseq [pid [:zai :zai-coding :zai-coding-plan]]
                  (let [m (router/provider-model-entry pid "glm-5.3")]
                    (expect (= 1.40 (:input (:pricing m))))
                    (expect (= 0.26 (:cached-input (:pricing m))))
                    (expect (= 4.40 (:output (:pricing m))))
                    (expect (= 1000000 (:context m)))
                    (expect (= 32768 (:output-limit m)))
                    (expect (true? (:json-object-mode? m))))))
            (it "advertises the light effort rung GLM-5.2 lacks"
                (expect (= [{:type "effort" :values ["low" "high" "max"]}]
                           (:reasoning-options (router/provider-model-entry :zai "glm-5.3"))))))
  (describe "GLM-5.3-Flash"
            (it "is the first curated default on every z.ai surface"
                (doseq [pid [:zai :zai-coding :zai-coding-plan]]
                  (expect (= "glm-5.3-flash" (first (router/provider-default-models pid))))))
            (it "carries stable list pricing, 1M context and the agentic output cap"
                (doseq [pid [:zai :zai-coding :zai-coding-plan]]
                  (let [m (router/provider-model-entry pid "glm-5.3-flash")]
                    (expect (= 0.15 (:input (:pricing m))))
                    (expect (= 0.03 (:cached-input (:pricing m))))
                    (expect (= 0.50 (:output (:pricing m))))
                    (expect (= 1000000 (:context m)))
                    (expect (= 32768 (:output-limit m)))
                    (expect (true? (:json-object-mode? m))))))
            (it "is a fast multimodal GLM effort model"
                (let [metadata
                      (get router/KNOWN_MODEL_METADATA "glm-5.3-flash")

                      entry
                      (router/provider-model-entry :zai-coding-plan "glm-5.3-flash")]

                  (expect (= :fast (:speed metadata)))
                  (expect (= #{:chat :vision} (:capabilities metadata)))
                  (expect (= :zai-effort (:reasoning-style metadata)))
                  (expect (= [{:type "effort" :values ["low" "high" "max"]}]
                             (:reasoning-options entry))))))
  (describe
    "GLM-5.3-FlashX"
    (it "is curated on every z.ai surface without displacing Flash"
        (doseq [pid [:zai :zai-coding :zai-coding-plan]]
          (let [defaults (router/provider-default-models pid)]
            (expect (contains? (set defaults) "glm-5.3-flashx"))
            (expect (= "glm-5.3-flash" (first defaults))))))
    (it "carries 2.5x the Flash rate, 1M context and the agentic output cap"
        (doseq [pid [:zai :zai-coding :zai-coding-plan]]
          (let [flash (:pricing (router/provider-model-entry pid "glm-5.3-flash"))
                entry (router/provider-model-entry pid "glm-5.3-flashx")
                flashx (:pricing entry)]

            (expect (= 0.375 (:input flashx)))
            (expect (= 0.075 (:cached-input flashx)))
            (expect (= 1.25 (:output flashx)))
            (doseq [k [:input :cached-input :output]]
              (expect (> 1e-9 (Math/abs (- (* 2.5 (double (k flash))) (double (k flashx)))))))
            (expect (= 1000000 (:context entry)))
            (expect (= 32768 (:output-limit entry)))
            (expect (true? (:json-object-mode? entry))))))
    (it "is the same fast multimodal effort model as Flash"
        (let [flash
              (get router/KNOWN_MODEL_METADATA "glm-5.3-flash")

              flashx
              (get router/KNOWN_MODEL_METADATA "glm-5.3-flashx")]

          (expect (= flash flashx))
          (expect (= :fast (:speed flashx)))
          (expect (= #{:chat :vision} (:capabilities flashx)))
          (expect (= :zai-effort (:reasoning-style flashx)))
          (expect (= [{:type "effort" :values ["low" "high" "max"]}]
                     (:reasoning-options (router/provider-model-entry :zai "glm-5.3-flashx"))))))))
