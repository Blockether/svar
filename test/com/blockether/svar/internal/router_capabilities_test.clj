(ns com.blockether.svar.internal.router-capabilities-test
  "Wire capabilities svar stamps on every resolved model.

   `:reasoning-effort?`, `:verbosity-style` and `:thinking-display-style` exist so
   a CHANNEL never has to ask which provider it is talking to. They are decided
   by the wire the model rides, which only the router knows: one
   `github-copilot` provider serves an Anthropic wire for Claude and an OpenAI
   Responses wire for GPT, so a provider-keyword test gets both answers wrong at
   once."
  (:require [lazytest.core :refer [defdescribe describe expect it]]
            [com.blockether.svar.internal.router :as router]))

(defn- models-of
  "Normalized models of one provider entry, indexed by name."
  [provider-map]
  (->> (router/normalize-provider 0 provider-map)
       :models
       (map (juxt :name identity))
       (into {})))

(defn- copilot
  [& model-names]
  (models-of {:id :github-copilot
              :api-key "test-key"
              :models (mapv (fn [n]
                              {:name n})
                            model-names)}))

(defdescribe
  reasoning-effort-capability-test
  "`:reasoning-effort?` answers 'may the CALLER pick a depth', not 'does it think'."
  (describe "GitHub Copilot serves two wires under one provider id"
            (it "grants effort to the GPT tier, which rides the Responses wire"
                (let [m (get (copilot "gpt-5.5") "gpt-5.5")]
                  (expect (true? (:reasoning? m)))
                  (expect (= :openai-compatible-responses (:api-style m)))
                  (expect (= :openai-effort (:reasoning-style m)))
                  (expect (true? (:reasoning-effort? m)))))
            (it "grants effort to the Claude tier, which rides the native Anthropic wire"
                ;; Regression, GitHub Copilot Enterprise: claude-opus-5 offered no depth
                ;; control at all. The row was `:server-managed` — a guard against the
                ;; spiral that `reasoning_effort` caused on Copilot's OPENAI-compatible
                ;; chat wire. Claude has been back on `/v1/messages` since; there the
                ;; native `thinking`/`output_config` fields are readable, so the caller
                ;; picks the depth and the OpenAI knob is never emitted.
                (let [m (get (copilot "claude-sonnet-4.6") "claude-sonnet-4.6")]
                  (expect (true? (:reasoning? m)))
                  (expect (= :anthropic (:api-style m)))
                  (expect (= :anthropic-thinking (:reasoning-style m)))
                  (expect (true? (:reasoning-effort? m)))))
            (it "emits Anthropic thinking on that wire, never `reasoning_effort`"
                (let [opus
                      (get (copilot "claude-opus-5") "claude-opus-5")

                      haiku
                      (get (copilot "claude-haiku-4.5") "claude-haiku-4.5")

                      body
                      (fn [m level]
                        (router/reasoning-extra-body (:api-style m) m level))]

                  ;; Opus 5 is an adaptive-thinking family: depth is `output_config.effort`.
                  (expect (= {:thinking {:type "adaptive" :display "summarized"}
                              :output_config {:effort "low"}}
                             (body opus :low)))
                  ;; Regression: `:deep` used to resolve through the OPENAI effort column
                  ;; and land on "high" — Anthropic's DEFAULT — so asking for deep
                  ;; thinking bought nothing above the default posture.
                  (expect (= {:thinking {:type "adaptive" :display "summarized"}
                              :output_config {:effort "max"}}
                             (body opus :deep)))
                  ;; Older families still take a manual budget.
                  (expect (= {:thinking {:type "enabled" :budget_tokens 8192}}
                             (body haiku :balanced)))
                  ;; The lever that spiralled the proxy stays unreachable on this wire.
                  (doseq [level
                          [:low :balanced :deep]

                          m
                          [opus haiku]]

                    (expect (nil? (:reasoning_effort (body m level))))))))
  (describe "styles that carry a caller-chosen depth"
            (it "grants effort on the native Anthropic wire (budget tokens)"
                (let [m (get (models-of {:id :anthropic :api-key "test-key"}) "claude-sonnet-4-6")]
                  (expect (= :anthropic-thinking (:reasoning-style m)))
                  (expect (true? (:reasoning-effort? m)))))
            (it "refuses effort for a binary thinking toggle"
                (let [m (get (models-of {:id :custom-thinker
                                         :api-key "test-key"
                                         :base-url "https://example.test/v1"
                                         :models [{:name "glm-4.6"
                                                   :reasoning? true
                                                   :reasoning-style :zai-thinking}]})
                             "glm-4.6")]
                  (expect (= :zai-thinking (:reasoning-style m)))
                  (expect (false? (:reasoning-effort? m)))))
            (it "refuses effort for a model that does not think at all"
                (let [m (get (models-of {:id :custom-plain
                                         :api-key "test-key"
                                         :base-url "https://example.test/v1"
                                         :models [{:name "plain-1"}]})
                             "plain-1")]
                  (expect (not (:reasoning? m)))
                  (expect (false? (:reasoning-effort? m)))))))

(defdescribe verbosity-capability-test
             "`text.verbosity` belongs to the OpenAI Responses envelope, not to a vendor."
             (it "stamps the Responses wire with the style and its vocabulary"
                 (let [provider
                       (router/normalize-provider 0 {:id :openai-codex :api-key "test-key"})

                       m
                       (first (filter #(= "gpt-5.5" (:name %)) (:models provider)))]

                   (expect (= :openai-compatible-responses (:api-style provider)))
                   (expect (= :openai-text (:verbosity-style m)))
                   (expect (= ["low" "medium" "high"] (:verbosity-options m)))))
             (it "stamps Copilot GPT the same way, because it is the same wire"
                 ;; The whole point of the capability: nothing here says `:openai-codex`.
                 (let [m (get (copilot "gpt-5.5") "gpt-5.5")]
                   (expect (= :openai-text (:verbosity-style m)))
                   (expect (= router/VERBOSITY_LEVELS (:verbosity-options m)))))
             (it "leaves the key absent on wires that reject the field"
                 (doseq [m [(get (copilot "claude-sonnet-4.6") "claude-sonnet-4.6")
                            (get (models-of {:id :anthropic :api-key "test-key"})
                                 "claude-sonnet-4-6")]]
                   (expect (nil? (:verbosity-style m)))
                   (expect (nil? (:verbosity-options m))))))

(defdescribe
  reasoning-effort-options-capability-test
  "`:reasoning-effort-options` lists the exact rungs a caller may pin on the model's wire."
  (it "stamps every advertised rung for the depth-selectable tiers"
      (expect (= ["low" "medium" "high" "max"]
                 (:reasoning-effort-options (get (models-of {:id :anthropic :api-key "test-key"})
                                                 "claude-sonnet-4-6"))))
      (expect (= ["none" "low" "medium" "high" "xhigh"]
                 (:reasoning-effort-options (get (copilot "gpt-5.5") "gpt-5.5")))))
  (it "leaves the key absent when the model offers no rung"
      ;; Haiku 4.5 takes manual budget tokens; models.dev sells it no effort rung.
      (expect (nil? (:reasoning-effort-options (get (models-of {:id :anthropic :api-key "test-key"})
                                                    "claude-haiku-4-5"))))
      (expect (nil? (:reasoning-effort-options (get (models-of {:id :custom-thinker
                                                                :api-key "test-key"
                                                                :base-url "https://example.test/v1"
                                                                :models [{:name "glm-4.6"
                                                                          :reasoning? true
                                                                          :reasoning-style
                                                                          :zai-thinking}]})
                                                    "glm-4.6"))))))

(defdescribe
  thinking-display-capability-test
  "`thinking.display` exists only on adaptive Claude thinking on the Anthropic wire."
  (it "stamps adaptive Claude with the style and its vocabulary"
      (doseq [m [(get (copilot "claude-opus-5") "claude-opus-5")
                 (get (models-of {:id :anthropic :api-key "test-key"}) "claude-sonnet-4-6")]]
        (expect (= :anthropic-display (:thinking-display-style m)))
        (expect (= router/THINKING_DISPLAY_OPTIONS (:thinking-display-options m)))
        (expect (= ["summarized" "omitted"] (:thinking-display-options m)))))
  (it "leaves the key absent where the request has no display field"
      ;; Haiku 4.5 takes manual budget thinking, GPT rides the Responses
      ;; wire, and Z.ai GLM only turns thinking on or off, also on the
      ;; Anthropic wire.
      (doseq [m [(get (copilot "claude-haiku-4.5") "claude-haiku-4.5")
                 (get (copilot "gpt-5.5") "gpt-5.5")
                 (get (models-of
                        {:id :custom-glm
                         :api-key "test-key"
                         :base-url "https://example.test/anthropic"
                         :api-style :anthropic
                         :models [{:name "glm-5.2" :reasoning? true :reasoning-style :zai-effort}]})
                      "glm-5.2")]]
        (expect (nil? (:thinking-display-style m)))
        (expect (nil? (:thinking-display-options m))))))

(defdescribe
  resolve-effective-model-capabilities-test
  "The resolved-model map is the ONE thing a channel reads, so it carries them."
  (it "surfaces both capabilities for the routed model"
      (let [router
            (router/make-router [{:id :github-copilot
                                  :api-key "test-key"
                                  :models [{:name "gpt-5.5"} {:name "claude-sonnet-4.6"}]}])

            info
            (router/resolve-effective-model router)]

        (expect (= "gpt-5.5" (:name info)))
        (expect (true? (:reasoning-effort? info)))
        (expect (= :openai-text (:verbosity-style info)))
        (expect (= ["low" "medium" "high"] (:verbosity-options info)))
        (expect (= ["none" "low" "medium" "high" "xhigh"] (:reasoning-effort-options info)))
        (expect (nil? (:thinking-display-style info)))))
  (it "surfaces depth-but-no-verbosity for the Claude tier of the SAME provider"
      (let [router
            (router/make-router [{:id :github-copilot
                                  :api-key "test-key"
                                  :models [{:name "claude-sonnet-4.6"} {:name "gpt-5.5"}]}])

            info
            (router/resolve-effective-model router)]

        (expect (= "claude-sonnet-4.6" (:name info)))
        ;; Anthropic wire: the depth knob is native, the OpenAI text knob is not.
        (expect (true? (:reasoning-effort? info)))
        (expect (nil? (:verbosity-style info)))
        ;; Adaptive Claude: the thinking summary can be turned off.
        (expect (= :anthropic-display (:thinking-display-style info)))
        (expect (= router/THINKING_DISPLAY_OPTIONS (:thinking-display-options info)))))
  (it "surfaces the refusal when the wire really does manage depth itself"
      (let [router
            (router/make-router [{:id :proxy-that-gates
                                  :api-key "test-key"
                                  :base-url "https://example.test/v1"
                                  :models [{:name "gemini-3-pro-preview"
                                            :reasoning? true
                                            :reasoning-style :server-managed}]}])

            info
            (router/resolve-effective-model router)]

        (expect (false? (:reasoning-effort? info)))
        (expect (nil? (:reasoning-effort-options info)))
        (expect (nil? (:verbosity-style info))))))

(defdescribe
  copilot-capabilities-test
  "ONE `:github-copilot` provider covers every seat tier, and its capability
   stamps come from the wire each catalog model names — never from the provider
   id, which is where a stamp used to go missing."
  (it "stamps github-copilot from the wire, not from its provider id"
      (let [router
            (router/make-router [{:id :github-copilot :api-key "test-key"}])

            by-name
            (into {} (map (juxt :name identity)) (:models (first (:providers router))))

            gpt
            (some by-name ["gpt-5.6-sol" "gpt-5.5"])

            claude
            (some by-name ["claude-opus-5" "claude-sonnet-4.6"])]

        ;; The provider inherits a catalog at all.
        (expect (seq by-name))
        ;; GPT rides the Responses wire: a caller may pick both knobs.
        (expect (true? (:reasoning-effort? gpt)))
        (expect (= :openai-effort (:reasoning-style gpt)))
        (expect (= :openai-text (:verbosity-style gpt)))
        ;; Claude rides the native Anthropic wire: depth yes, verbosity no.
        (expect (true? (:reasoning-effort? claude)))
        (expect (= :anthropic-thinking (:reasoning-style claude)))
        (expect (nil? (:verbosity-style claude))))))
