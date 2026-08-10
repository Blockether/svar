(ns com.blockether.svar.internal.router-capabilities-test
  "Wire capabilities svar stamps on every resolved model.

   `:reasoning-effort?` and `:verbosity-style` exist so a CHANNEL never has to
   ask which provider it is talking to. Both are decided by the wire the model
   rides, which only the router knows: one `github-copilot` provider serves an
   Anthropic wire for Claude and an OpenAI Responses wire for GPT, so a
   provider-keyword test gets both answers wrong at once."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
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
  (models-of {:id :github-copilot :api-key "test-key" :models (mapv (fn [n] {:name n}) model-names)}))

(defdescribe reasoning-effort-capability-test
  "`:reasoning-effort?` answers 'may the CALLER pick a depth', not 'does it think'."

  (describe "GitHub Copilot serves two wires under one provider id"
    (it "grants effort to the GPT tier, which rides the Responses wire"
      (let [m (get (copilot "gpt-5.5") "gpt-5.5")]
        (expect (true? (:reasoning? m)))
        (expect (= :openai-compatible-responses (:api-style m)))
        (expect (= :openai-effort (:reasoning-style m)))
        (expect (true? (:reasoning-effort? m)))))

    (it "refuses effort for the Claude tier, whose depth the proxy manages"
      ;; Regression: the proxy spiralled agent loops when svar pushed
      ;; `reasoning_effort` at Copilot Claude, so that row is `:server-managed`
      ;; and emits nothing tunable. A channel offering the control there offers
      ;; a control that cannot reach the wire.
      (let [m (get (copilot "claude-sonnet-4.6") "claude-sonnet-4.6")]
        (expect (true? (:reasoning? m)))
        (expect (= :server-managed (:reasoning-style m)))
        (expect (false? (:reasoning-effort? m))))))

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
    (let [provider (router/normalize-provider 0 {:id :openai-codex :api-key "test-key"})
          m (first (filter #(= "gpt-5.5" (:name %)) (:models provider)))]
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
               (get (models-of {:id :anthropic :api-key "test-key"}) "claude-sonnet-4-6")]]
      (expect (nil? (:verbosity-style m)))
      (expect (nil? (:verbosity-options m))))))

(defdescribe resolve-effective-model-capabilities-test
  "The resolved-model map is the ONE thing a channel reads, so it carries them."

  (it "surfaces both capabilities for the routed model"
    (let [router (router/make-router
                   [{:id :github-copilot
                     :api-key "test-key"
                     :models [{:name "gpt-5.5"} {:name "claude-sonnet-4.6"}]}])
          info (router/resolve-effective-model router)]
      (expect (= "gpt-5.5" (:name info)))
      (expect (true? (:reasoning-effort? info)))
      (expect (= :openai-text (:verbosity-style info)))
      (expect (= ["low" "medium" "high"] (:verbosity-options info)))))

  (it "surfaces the refusal for the Claude tier of the SAME provider"
    (let [router (router/make-router
                   [{:id :github-copilot
                     :api-key "test-key"
                     :models [{:name "claude-sonnet-4.6"} {:name "gpt-5.5"}]}])
          info (router/resolve-effective-model router)]
      (expect (= "claude-sonnet-4.6" (:name info)))
      (expect (false? (:reasoning-effort? info)))
      (expect (nil? (:verbosity-style info))))))
