(ns com.blockether.svar.internal.router-zai-catalog-test
  "Static checks of the z.ai provider and GLM model tables. No HTTP calls."
  (:require [lazytest.core :refer [defdescribe describe expect it]]
            [com.blockether.svar.internal.router :as router]))

(defdescribe
  zai-and-coding-metadata-sanity-test
  "These checks run unconditionally — they only touch svar's static metadata
   tables, no HTTP. Guards against someone editing `KNOWN_PROVIDERS` /
   `KNOWN_PROVIDER_MODELS` and accidentally desyncing the two Z.ai surfaces."
  (describe "Pricing tables"
            (it ":zai-coding mirrors :zai pricing (subscription-overage parity)"
                ;; Compare per-token public rates only — the catalog reports `:cache-read 0`
                ;; on the coding-plan entry and a real cache rate on direct :zai. The
                ;; subscription-overage invariant is about input/output/cached-input parity.
                (let [keys-of-interest [:input :cached-input :output]]
                  (doseq [name ["glm-4.6" "glm-4.6v" "glm-4.7" "glm-5.1" "glm-5-turbo"
                                "glm-5v-turbo"]]
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
                (expect (nil? (router/model-output-ceiling {:context 100000}))))))
