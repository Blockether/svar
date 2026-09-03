(ns com.blockether.svar.internal.router-reasoning-test
  "Tests for the abstract reasoning-level → provider-specific translation.

   Covers:
   - `normalize-reasoning-level` vocabulary + OpenAI-alias back-compat.
   - `reasoning-extra-body` producing the right wire shape per api-style.
   - Silent no-op for non-reasoning models and unknown levels.
   - Anthropic budget-tokens magnitudes matching the documented thresholds.
   - Anthropic max_tokens clamp when thinking is enabled."
  (:require [lazytest.core :refer [defdescribe describe expect it]]
            [com.blockether.svar.internal.modelsdev :as modelsdev]
            [com.blockether.svar.internal.router :as router]
            [com.blockether.svar.internal.llm :as llm]))

(defdescribe normalize-reasoning-level-test
             "Vocabulary normalization: canonical low, balanced, and deep keywords."
             (describe "canonical vocabulary (low/balanced/deep)"
                       (it "accepts canonical keywords"
                           (expect (= :low (router/normalize-reasoning-level :low)))
                           (expect (= :balanced (router/normalize-reasoning-level :balanced)))
                           (expect (= :deep (router/normalize-reasoning-level :deep))))
                       (it "accepts canonical strings, trimmed + case-insensitive"
                           (expect (= :low (router/normalize-reasoning-level "LOW")))
                           (expect (= :balanced (router/normalize-reasoning-level " Balanced ")))
                           (expect (= :deep (router/normalize-reasoning-level "DEEP")))))
             (it "rejects retired effort names"
                 (expect (nil? (router/normalize-reasoning-level :quick)))
                 (expect (nil? (router/normalize-reasoning-level :medium)))
                 (expect (nil? (router/normalize-reasoning-level :high)))
                 (expect (nil? (router/normalize-reasoning-level "quick"))))
             (describe "invalid input"
                       (it "returns nil for unknown vocabulary"
                           (expect (nil? (router/normalize-reasoning-level :turbo)))
                           (expect (nil? (router/normalize-reasoning-level "ultra"))))
                       (it "returns nil for nil / empty / non-string/keyword"
                           (expect (nil? (router/normalize-reasoning-level nil)))
                           (expect (nil? (router/normalize-reasoning-level "")))
                           (expect (nil? (router/normalize-reasoning-level 42)))
                           (expect (nil? (router/normalize-reasoning-level {:level :deep}))))))

(defdescribe
  reasoning-extra-body-test
  "Translator: abstract level → provider wire shape."
  (describe "OpenAI-compatible chat api-style"
            (it "emits flat :reasoning_effort for reasoning-capable models"
                (let [gpt5 {:name "gpt-5" :reasoning? true}]
                  (expect (= {:reasoning_effort "low"}
                             (router/reasoning-extra-body :openai-compatible-chat gpt5 :low)))
                  (expect (= {:reasoning_effort "medium"}
                             (router/reasoning-extra-body :openai-compatible-chat gpt5 :balanced)))
                  (expect (= {:reasoning_effort "high"}
                             (router/reasoning-extra-body :openai-compatible-chat gpt5 :deep)))))
            (it "rejects retired abstract aliases"
                (let [gpt5 {:name "gpt-5" :reasoning? true}]
                  (expect (nil? (router/reasoning-extra-body :openai-compatible-chat gpt5 :quick)))
                  (expect (nil?
                            (router/reasoning-extra-body :openai-compatible-chat gpt5 "HIGH"))))))
  (describe
    "Anthropic api-style"
    (it "emits nested :thinking block for reasoning-capable models"
        ;; Fully-specified model (explicit :reasoning-style)
        (let [claude
              {:name "claude-sonnet-4-5" :reasoning? true :reasoning-style :anthropic-thinking}]
          (expect (= {:thinking {:type "enabled" :budget_tokens 1024}}
                     (router/reasoning-extra-body :anthropic claude :low)))
          (expect (= {:thinking {:type "enabled" :budget_tokens 8192}}
                     (router/reasoning-extra-body :anthropic claude :balanced)))
          (expect (= {:thinking {:type "enabled" :budget_tokens 24000}}
                     (router/reasoning-extra-body :anthropic claude :deep)))))
    (it "uses adaptive thinking for Claude Opus 5 / Opus 4.8–4.6 / Sonnet 4.6"
        ;; Regression: these efforts came from the OpenAI column, so `:balanced`
        ;; asked for "medium" — one rung BELOW Anthropic's default — and `:deep`
        ;; asked for "high", which IS the default. Turns that requested deep
        ;; thinking came back with two-word summaries.
        (doseq [[model level effort]
                [["claude-opus-5" :balanced "high"] ["claude-opus-4-8" :balanced "high"]
                 ["claude-opus-4-7" :balanced "high"] ["claude-opus-4-6" :deep "max"]
                 ["claude-sonnet-4-6" :low "low"]]]
          (let [out (router/reasoning-extra-body
                      :anthropic
                      {:name model :reasoning? true :reasoning-style :anthropic-thinking}
                      level)]
            (expect (= {:type "adaptive" :display "summarized"} (:thinking out)))
            (expect (= {:effort effort} (:output_config out)))
            (expect (nil? (get-in out [:thinking :budget_tokens]))))))
    (it "uses adaptive thinking for dashed and dotted Fable 5.1 ids"
        (doseq [model ["claude-fable-5-1" "claude-fable-5.1"]]
          (let [out (router/reasoning-extra-body
                      :anthropic
                      {:name model :reasoning? true :reasoning-style :anthropic-thinking}
                      :deep)]
            (expect (= {:type "adaptive" :display "summarized"} (:thinking out)))
            (expect (= {:effort "max"} (:output_config out)))
            (expect (nil? (get-in out [:thinking :budget_tokens]))))))
    (it "climbs Anthropic's own effort ladder, not OpenAI's"
        ;; docs.claude.com /en/docs/build-with-claude/adaptive-thinking:
        ;; max > xhigh > high (default) > medium > low.
        (let [opus
              {:name "claude-opus-5" :reasoning? true :reasoning-style :anthropic-thinking}

              effort
              (fn [level]
                (get-in (router/reasoning-extra-body :anthropic opus level)
                        [:output_config :effort]))]

          (expect (= "low" (effort :low)))
          (expect (= "high" (effort :balanced)))
          (expect (= "max" (effort :deep)))
          ;; The two columns are deliberately different data: `:balanced` is each
          ;; vendor's own DEFAULT rung — "medium" on OpenAI, "high" on Anthropic —
          ;; and reusing OpenAI's here is what asked Claude to think one rung
          ;; below default. (`:deep` names the ceiling on both, then clamps.)
          (expect (not= (get-in router/REASONING_LEVELS [:balanced :openai-effort])
                        (get-in router/REASONING_LEVELS [:balanced :anthropic-effort])))))
    (it "opts into summarized display even when the caller names no level"
        ;; Regression: a level-less call emitted no `thinking` field at all, so
        ;; Opus 5 / Sonnet 5 / Fable 5 used their own `display: "omitted"`
        ;; default — thinking blocks with an EMPTY thinking field and no
        ;; `thinking_delta` events, i.e. a reasoning surface showing nothing.
        (let [opus {:name "claude-opus-5" :reasoning? true :reasoning-style :anthropic-thinking}]
          (expect (= {:thinking {:type "adaptive" :display "summarized"}}
                     (router/reasoning-extra-body :anthropic opus nil)))
          ;; No level means no DEPTH opinion: Anthropic's default effort stands.
          (expect (nil? (:output_config (router/reasoning-extra-body :anthropic opus nil))))
          (expect (= (router/reasoning-extra-body :anthropic opus nil)
                     (router/reasoning-extra-body :anthropic opus :turbo)))))
    (it "keeps the silent nil for every other level-less model"
        (let [manual
              {:name "claude-sonnet-4-5" :reasoning? true :reasoning-style :anthropic-thinking}

              opus
              {:name "claude-opus-5" :reasoning? true :reasoning-style :anthropic-thinking}

              gpt
              {:name "gpt-5" :reasoning? true}]

          ;; Manual budget_tokens families have no adaptive display to opt into.
          (expect (nil? (router/reasoning-extra-body :anthropic manual nil)))
          ;; `thinking` is meaningless on an OpenAI-compatible wire.
          (expect (nil? (router/reasoning-extra-body :openai-compatible-chat opus nil)))
          (expect (nil? (router/reasoning-extra-body :openai-compatible-chat gpt nil)))
          ;; A model that does not think stays untouched.
          (expect (nil? (router/reasoning-extra-body :anthropic {:name "claude-opus-5"} nil)))))
    (it "infers :anthropic-thinking from :api-style :anthropic when style is unset"
        (let [claude {:name "unknown-claude" :reasoning? true}]
          (expect (= {:thinking {:type "enabled" :budget_tokens 8192}}
                     (router/reasoning-extra-body :anthropic claude :balanced)))))
    (it "budget_tokens values are strictly ascending for manual-thinking Claude models"
        (let [claude
              {:name "claude-opus-4-5" :reasoning? true :reasoning-style :anthropic-thinking}

              budget
              (fn [lvl]
                (get-in (router/reasoning-extra-body :anthropic claude lvl)
                        [:thinking :budget_tokens]))]

          (expect (< (budget :low) (budget :balanced) (budget :deep))))))
  (describe "Z.ai / GLM binary thinking"
            (it "emits `{:thinking {:type \"disabled\"}}` for :low"
                (let [glm {:name "glm-4.6" :reasoning? true :reasoning-style :zai-thinking}]
                  (expect (= {:thinking {:type "disabled"}}
                             (router/reasoning-extra-body :openai-compatible-chat glm :low)))))
            (it "emits `{:thinking {:type \"enabled\"}}` for :balanced and :deep"
                (let [glm {:name "glm-4.6" :reasoning? true :reasoning-style :zai-thinking}]
                  (expect (= {:thinking {:type "enabled"}}
                             (router/reasoning-extra-body :openai-compatible-chat glm :balanced)))
                  (expect (= {:thinking {:type "enabled"}}
                             (router/reasoning-extra-body :openai-compatible-chat glm :deep)))))
            (it "balanced and deep collapse to the same shape (no gradation on z.ai)"
                (let [glm {:name "glm-5.1" :reasoning? true :reasoning-style :zai-thinking}]
                  (expect (= (router/reasoning-extra-body :openai-compatible-chat glm :balanced)
                             (router/reasoning-extra-body :openai-compatible-chat glm :deep)))))
            (it "emits no budget_tokens (z.ai has no such concept)"
                (let [glm
                      {:name "glm-4.7" :reasoning? true :reasoning-style :zai-thinking}

                      out
                      (router/reasoning-extra-body :openai-compatible-chat glm :deep)]

                  (expect (nil? (get-in out [:thinking :budget_tokens]))))))
  (describe
    "Z.ai preserved thinking (clear_thinking: false)"
    (let [glm {:name "glm-4.7" :reasoning? true :reasoning-style :zai-thinking}]
      (it "WITHOUT `:preserved-thinking?` → no `clear_thinking` key in body"
          (let [out (router/reasoning-extra-body :openai-compatible-chat glm :deep)]
            (expect (= {:thinking {:type "enabled"}} out))
            (expect (nil? (get-in out [:thinking :clear_thinking])))))
      (it "WITH `:preserved-thinking? true` + `:deep` → emits clear_thinking: false"
          (let [out (router/reasoning-extra-body :openai-compatible-chat glm
                                                 :deep {:preserved-thinking? true})]
            (expect (= {:thinking {:type "enabled" :clear_thinking false}} out))))
      (it "preserved flag propagates even when thinking is disabled"
          ;; Edge: caller says \"don't think this turn, but preserve reasoning
          ;; from prior turns\". The abstract level disables thinking; the
          ;; preserved flag still rides along so the server can retain history.
          (let [out (router/reasoning-extra-body :openai-compatible-chat glm
                                                 :low {:preserved-thinking? true})]
            (expect (= {:thinking {:type "disabled" :clear_thinking false}} out))))
      (it "`:preserved-thinking? false` → same as omitting it"
          (let [out-false (router/reasoning-extra-body :openai-compatible-chat glm
                                                       :deep {:preserved-thinking? false})
                out-absent (router/reasoning-extra-body :openai-compatible-chat glm :deep)]

            (expect (= out-false out-absent))
            (expect (nil? (get-in out-false [:thinking :clear_thinking])))))
      (it "is silently ignored on :openai-effort models"
          (let [gpt5 {:name "gpt-5" :reasoning? true :reasoning-style :openai-effort}
                out (router/reasoning-extra-body :openai-compatible-chat gpt5
                                                 :deep {:preserved-thinking? true})]

            (expect (= {:reasoning_effort "high"} out))
            (expect (nil? (:thinking out)))))
      (it "is silently ignored on :anthropic-thinking models"
          (let [claude
                {:name "claude-sonnet-4-5" :reasoning? true :reasoning-style :anthropic-thinking}
                out (router/reasoning-extra-body :anthropic claude
                                                 :deep {:preserved-thinking? true})]

            (expect (= {:thinking {:type "enabled" :budget_tokens 24000}} out))
            (expect (nil? (get-in out [:thinking :clear_thinking])))))
      (it "`:preserved-thinking?` without `:reasoning` level is a full no-op"
          ;; Don't silently emit clear_thinking with no thinking block — the
          ;; reasoning translator short-circuits when level is nil.
          (expect (nil? (router/reasoning-extra-body :openai-compatible-chat
                                                     glm
                                                     nil
                                                     {:preserved-thinking? true}))))))
  (describe
    "Z.ai / GLM effort thinking (:zai-effort)"
    ;; GLM chooses thinking DEPTH via reasoning_effort and its rungs are
    ;; low/high/max, which the abstract levels ride 1:1. GLM-5.2 sells nothing
    ;; below "high" — z.ai answers a rung a model does not know with its heavy
    ;; "max" default — so `:low` stops thinking there instead (verified live:
    ;; glm-5.2 honors thinking:{type "disabled"}).
    (let [glm {:name "glm-5.2" :reasoning? true :reasoning-style :zai-effort}]
      (it "`:low` disables thinking (no light effort rung exists)"
          (expect (= {:thinking {:type "disabled"}}
                     (router/reasoning-extra-body :anthropic glm :low))))
      (it "`:low` still disables thinking when the catalog stops at high"
          (expect (= {:thinking {:type "disabled"}}
                     (router/reasoning-extra-body
                       :anthropic
                       (assoc glm :reasoning-options [{:type "effort" :values ["high" "max"]}])
                       :low))))
      (it "`:balanced` keeps thinking on at high effort"
          (expect (= {:reasoning_effort "high" :thinking {:type "enabled"}}
                     (router/reasoning-extra-body :anthropic glm :balanced))))
      (it "`:deep` keeps thinking on at max effort"
          (expect (= {:reasoning_effort "max" :thinking {:type "enabled"}}
                     (router/reasoning-extra-body :anthropic glm :deep))))
      (it "`:low` ignores `:preserved-thinking?` (no thinking → nothing to preserve)"
          (expect (= {:thinking {:type "disabled"}}
                     (router/reasoning-extra-body :anthropic glm
                                                  :low {:preserved-thinking? true})))))
    ;; GLM-5.3 (2026-08-14) advertises ["low" "high" "max"] — the light rung
    ;; GLM-5.2 never had — so the whole ladder is reachable and `:low` spends
    ;; it instead of turning thinking off (see `zai-effort-rung`).
    (let [glm53 {:name "glm-5.3"
                 :reasoning? true
                 :reasoning-style :zai-effort
                 :reasoning-options [{:type "effort" :values ["low" "high" "max"]}]}]
      (it "`:low` thinks at the advertised low rung instead of disabling thinking"
          (expect (= {:reasoning_effort "low" :thinking {:type "enabled"}}
                     (router/reasoning-extra-body :anthropic glm53 :low))))
      (it "`:low` + preserved keeps reasoning across turns"
          (expect (= {:reasoning_effort "low" :thinking {:type "enabled" :clear_thinking false}}
                     (router/reasoning-extra-body :anthropic glm53
                                                  :low {:preserved-thinking? true}))))
      (it "`:balanced` and `:deep` still ride high / max"
          (expect (= {:reasoning_effort "high" :thinking {:type "enabled"}}
                     (router/reasoning-extra-body :anthropic glm53 :balanced)))
          (expect (= {:reasoning_effort "max" :thinking {:type "enabled"}}
                     (router/reasoning-extra-body :anthropic glm53 :deep))))))
  (describe
    "non-reasoning models (silent no-op)"
    (it "returns nil when model lacks :reasoning? flag"
        (expect (nil? (router/reasoning-extra-body :openai-compatible-chat {:name "gpt-4o"} :deep)))
        (expect (nil? (router/reasoning-extra-body :anthropic {:name "claude-haiku-3"} :deep))))
    (it "returns nil when :reasoning? is explicitly false"
        (expect (nil? (router/reasoning-extra-body :openai-compatible-chat
                                                   {:name "gpt-4o" :reasoning? false}
                                                   :deep)))))
  (describe "unknown level (silent no-op)"
            (it "returns nil when level is unknown"
                (expect (nil? (router/reasoning-extra-body :openai-compatible-chat
                                                           {:name "gpt-5" :reasoning? true}
                                                           :turbo)))
                (expect (nil? (router/reasoning-extra-body :openai-compatible-chat
                                                           {:name "gpt-5" :reasoning? true}
                                                           nil)))))
  (describe
    "api-style fallback"
    (it "treats unknown api-style as openai-compatible-chat (most gateways are openai-compatible)"
        (let [model {:name "deepseek-reasoner" :reasoning? true}]
          (expect (= {:reasoning_effort "high"}
                     (router/reasoning-extra-body :custom-gateway model :deep)))
          (expect (= {:reasoning_effort "low"} (router/reasoning-extra-body nil model :low)))))
    (it "explicit :reasoning-style always wins over api-style inference"
        ;; A z.ai model behind an OpenAI-compat api-style still emits z.ai thinking.
        (let [glm {:name "glm-4.6" :reasoning? true :reasoning-style :zai-thinking}]
          (expect (= {:thinking {:type "enabled"}}
                     (router/reasoning-extra-body :openai-compatible-chat glm :deep))))
        ;; Even under `:anthropic`, explicit openai-effort wins.
        (let [weird {:name "custom-reasoner" :reasoning? true :reasoning-style :openai-effort}]
          (expect (= {:reasoning_effort "high"}
                     (router/reasoning-extra-body :anthropic weird :deep)))))))

(defdescribe
  provider-native-reasoning-effort-test
  "Exact provider-native effort is catalog-gated and does not use aliases."
  (let [glm {:name "glm-5.2"
             :reasoning? true
             :reasoning-style :zai-effort
             :reasoning-options [{:type "effort" :values ["high" "max"]}]}]
    (it "emits the exact GLM-5.2 high body"
        (expect (= {:requested "high"
                    :effective "high"
                    :supported ["high" "max"]
                    :wire-style :zai-effort
                    :extra-body {:thinking {:type "enabled"} :reasoning_effort "high"}}
                   (router/resolve-reasoning-effort :anthropic glm "high"))))
    (it "emits the exact GLM-5.2 max body"
        (expect (= {:thinking {:type "enabled"} :reasoning_effort "max"}
                   (:extra-body (router/resolve-reasoning-effort :anthropic glm "max")))))
    (it "rejects abstract levels and unsupported exact values"
        (doseq [effort ["deep" "medium" :high nil]]
          (let [resolved (router/resolve-reasoning-effort :anthropic glm effort)]
            (expect (nil? (:effective resolved)))
            (expect (= ["high" "max"] (:supported resolved))))))
    (it "rejects a model without catalog-declared effort options"
        (let [resolved
              (router/resolve-reasoning-effort :anthropic (dissoc glm :reasoning-options) "high")]
          (expect (nil? (:effective resolved)))
          (expect (= [] (:supported resolved))))))
  ;; GLM-5.3 sells the light rung GLM-5.2 never had, so the EXACT API reaches
  ;; "low" too — on the models.dev rows that advertise it, and nowhere else.
  (let [glm53 {:name "glm-5.3"
               :reasoning? true
               :reasoning-style :zai-effort
               :reasoning-options [{:type "effort" :values ["low" "high" "max"]}]}]
    (it "emits the exact GLM-5.3 low body"
        (expect (= {:requested "low"
                    :effective "low"
                    :supported ["low" "high" "max"]
                    :wire-style :zai-effort
                    :extra-body {:thinking {:type "enabled"} :reasoning_effort "low"}}
                   (router/resolve-reasoning-effort :anthropic glm53 "low"))))
    (it "refuses an exact `low` on a row whose catalog stops at high"
        (let [glm52 {:name "glm-5.2"
                     :reasoning? true
                     :reasoning-style :zai-effort
                     :reasoning-options [{:type "effort" :values ["high" "max"]}]}
              resolved (router/resolve-reasoning-effort :anthropic glm52 "low")]

          (expect (= "low" (:requested resolved)))
          (expect (nil? (:effective resolved)))
          (expect (= ["high" "max"] (:supported resolved)))))))

(defdescribe
  catalog-clamped-effort-test
  "Every effort svar sends is a value models.dev advertises for that model."
  ;; Regression: the abstract-level path read `REASONING_LEVELS` blind — only
  ;; `resolve-reasoning-effort` consulted the catalog. `:deep` therefore sent one
  ;; fixed rung for every model: too LOW for GPT-5.6 (which sells `xhigh`/`max`)
  ;; and, once Anthropic's column asked for "max", a 400 waiting for any row that
  ;; stops at "high".
  (describe
    "OpenAI-compatible effort"
    (it "reaches the ceiling the catalog advertises"
        (let [luna {:name "gpt-5.6-luna"
                    :reasoning? true
                    :reasoning-options [{:type "effort"
                                         :values ["none" "low" "medium" "high" "xhigh" "max"]}]}]
          (expect (= {:reasoning_effort "max"}
                     (router/reasoning-extra-body :openai-compatible-chat luna :deep)))
          (expect (= {:reasoning_effort "medium"}
                     (router/reasoning-extra-body :openai-compatible-chat luna :balanced)))))
    (it "clamps down to the strongest advertised rung"
        (let [o3 {:name "o3"
                  :reasoning? true
                  :reasoning-options [{:type "effort" :values ["low" "medium" "high"]}]}]
          (expect (= {:reasoning_effort "high"}
                     (router/reasoning-extra-body :openai-compatible-chat o3 :deep)))))
    (it "clamps up when every advertised rung sits above the level"
        (let [pro {:name "gpt-5-pro"
                   :reasoning? true
                   :reasoning-options [{:type "effort" :values ["high"]}]}]
          (expect (= {:reasoning_effort "high"}
                     (router/reasoning-extra-body :openai-compatible-chat pro :low)))))
    (it "never clamps a level down into \"none\" / \"minimal\""
        ;; Regression: clamping only looked DOWNWARD, so the 40 catalog rows that
        ;; sell reasoning as on/off (`["none" "high"]` — Mistral Medium, GLM-5.2 on
        ;; several gateways, `["minimal" "high"]` on the Gemini image rows) answered
        ;; `:low` and `:balanced` with "none": a caller asking for the shallow end
        ;; of thinking silently got NO thinking.
        (let [on-off {:name "mistral-medium-3.5"
                      :reasoning? true
                      :reasoning-options [{:type "effort" :values ["none" "high"]}]}]
          (expect (= {:reasoning_effort "high"}
                     (router/reasoning-extra-body :openai-compatible-chat on-off :low)))
          (expect (= {:reasoning_effort "high"}
                     (router/reasoning-extra-body :openai-compatible-chat on-off :balanced)))))
    (it "omits the field when the row sells no thinking rung at all"
        ;; Sending "none" would disable reasoning, "low" would 400: say nothing and
        ;; let the provider's own default decide.
        (let [off-only {:name "zai-glm-4.7"
                        :reasoning? true
                        :reasoning-options [{:type "effort" :values ["none"]}]}]
          (expect (nil? (router/reasoning-extra-body :openai-compatible-chat off-only :deep)))))
    (it "stays at \"high\" when the catalog advertises nothing"
        ;; No evidence, no claim: `xhigh`/`max` 400 on every older reasoner.
        (let [unknown {:name "some-new-reasoner" :reasoning? true}]
          (expect (= {:reasoning_effort "high"}
                     (router/reasoning-extra-body :openai-compatible-chat unknown :deep))))))
  (describe
    "Anthropic effort"
    (it "clamps :deep to the ladder the model's row stops at"
        (let [custom {:name "vendor-claude-x"
                      :reasoning? true
                      :reasoning-style :anthropic-thinking
                      :reasoning-options [{:type "effort" :values ["low" "high" "xhigh"]}]}]
          (expect (= {:effort "xhigh"}
                     (:output_config (router/reasoning-extra-body :anthropic custom :deep))))))
    (it "keeps the display opt-in when the row sells no thinking rung"
        (let [off-only {:name "vendor-claude-off"
                        :reasoning? true
                        :reasoning-style :anthropic-thinking
                        :reasoning-options [{:type "effort" :values ["none"]}]}]
          (expect (= {:thinking {:type "adaptive" :display "summarized"}}
                     (router/reasoning-extra-body :anthropic off-only :deep)))))
    (it "keeps \"max\" for a Claude row the catalog does not carry"
        (let [opus {:name "claude-opus-5" :reasoning? true :reasoning-style :anthropic-thinking}]
          (expect (= {:effort "max"}
                     (:output_config (router/reasoning-extra-body :anthropic opus :deep)))))))
  (describe
    "the whole bundled catalog"
    (it
      "never emits an effort the model does not advertise"
      (let [checked (atom 0)]
        (doseq [[provider api-style] [[:anthropic :anthropic] [:openai :openai-compatible-chat]
                                      [:github-copilot :openai-compatible-chat]]
                [_ model] (modelsdev/provider-models provider)
                :when (:reasoning? model)
                :let [advertised (into #{}
                                       (mapcat :values)
                                       (filter #(= "effort" (:type %)) (:reasoning-options model)))]
                :when (seq advertised)
                level [:low :balanced :deep]
                :let [body (router/reasoning-extra-body api-style model level)
                      sent (or (:reasoning_effort body) (get-in body [:output_config :effort]))]
                :when sent]

          (swap! checked inc)
          (expect (contains? advertised sent) (str provider "/" (:name model) " " level " → " sent))
          (expect (not (contains? #{"none" "minimal"} sent))
                  (str provider "/" (:name model) " " level " → " sent " disables thinking")))
        ;; The walk is worthless if it silently stops finding models — e.g. a
        ;; provider id that no longer resolves in the bundled catalog.
        (expect (< 100 @checked))))
    (it "never turns thinking off, in any provider of the catalog"
        (let [checked (atom 0)]
          (doseq [[provider entry] @modelsdev/catalog
                  [_ raw] (:models entry)
                  :let [model (modelsdev/normalize-model raw)]
                  :when (seq (:reasoning-options model))
                  api-style [:openai-compatible-chat :anthropic]
                  level [:low :balanced :deep]
                  :let [body (router/reasoning-extra-body api-style model level)
                        sent (or (:reasoning_effort body) (get-in body [:output_config :effort]))]
                  :when sent]

            (swap! checked inc)
            (expect (not (contains? #{"none" "minimal"} sent))
                    (str provider "/" (:name model) " " level " → " sent)))
          (expect (< 1000 @checked))))))

(defdescribe
  catalog-decides-thinking-style-test
  "models.dev, not the model NAME, decides adaptive vs manual Claude thinking."
  ;; Regression: the choice was a hardcoded name regex over a fact the catalog
  ;; already states, so a renamed or brand-new adaptive id silently fell back to
  ;; manual `budget_tokens` (and vice versa).
  (it "an effort-only row is adaptive, whatever the name says"
      (let [custom
            {:name "vendor-claude-x"
             :reasoning? true
             :reasoning-style :anthropic-thinking
             :reasoning-options [{:type "effort" :values ["low" "medium" "high" "max"]}]}

            out
            (router/reasoning-extra-body :anthropic custom :balanced)]

        (expect (= {:type "adaptive" :display "summarized"} (:thinking out)))
        (expect (= {:effort "high"} (:output_config out)))))
  (it "a budget_tokens-only row is manual, whatever the name says"
      (let [named-adaptive {:name "claude-opus-5"
                            :reasoning? true
                            :reasoning-style :anthropic-thinking
                            :reasoning-options [{:type "budget_tokens" :min 1024}]}]
        (expect (= {:thinking {:type "enabled" :budget_tokens 8192}}
                   (router/reasoning-extra-body :anthropic named-adaptive :balanced)))))
  (it "a row advertising BOTH is ambiguous, so the name pattern breaks the tie"
      ;; `effort` predates adaptive thinking: Opus 4.5 takes `output_config.effort`
      ;; but NOT `thinking: {type: "adaptive"}`, while Opus 4.6 takes both.
      (let [both (fn [name*]
                   {:name name*
                    :reasoning? true
                    :reasoning-style :anthropic-thinking
                    :reasoning-options [{:type "effort" :values ["low" "medium" "high" "max"]}
                                        {:type "budget_tokens" :min 1024}]})]
        (expect (= {:thinking {:type "enabled" :budget_tokens 24000}}
                   (router/reasoning-extra-body :anthropic (both "claude-opus-4-5") :deep)))
        (expect (= {:type "adaptive" :display "summarized"}
                   (:thinking
                     (router/reasoning-extra-body :anthropic (both "claude-opus-4-6") :deep))))))
  (it "the exact-effort API sends the adaptive block only to adaptive families"
      (let [opus45
            {:name "claude-opus-4-5"
             :reasoning? true
             :reasoning-style :anthropic-thinking
             :reasoning-options [{:type "effort" :values ["low" "medium" "high"]}
                                 {:type "budget_tokens" :min 1024}]}

            opus5
            {:name "claude-opus-5"
             :reasoning? true
             :reasoning-style :anthropic-thinking
             :reasoning-options [{:type "effort" :values ["low" "medium" "high" "xhigh" "max"]}]}]

        (expect (= {:output_config {:effort "high"}}
                   (:extra-body (router/resolve-reasoning-effort :anthropic opus45 "high"))))
        (expect (= {:output_config {:effort "max"}
                    :thinking {:type "adaptive" :display "summarized"}}
                   (:extra-body (router/resolve-reasoning-effort :anthropic opus5 "max")))))))

(defdescribe
  modelsdev-reasoning-options-test
  (it "normalizes reasoning options while preserving the catalog contract"
      (expect (= [{:type "effort" :values ["high" "max"] :min 1 :max 2}]
                 (:reasoning-options
                   (modelsdev/normalize-model
                     {:id "m"
                      :reasoning_options
                      [{:type "effort" :values ["high" "max"] :min 1 :max 2 :ignored true}]})))))
  (it "retains GLM-5.2 high/max from the bundled catalog"
      (expect (= [{:type "effort" :values ["high" "max"]}]
                 (:reasoning-options (router/provider-model-entry :zai-coding-plan "glm-5.2"))))))

(defdescribe
  known-model-reasoning-flags-test
  "Sanity check that KNOWN_MODEL_METADATA flags the expected reasoning models."
  (it "flags GPT-5 family as reasoning-capable"
      (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "gpt-5"))))
      (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "gpt-5-mini"))))
      (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "gpt-5.1"))))
      (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "gpt-5.3-codex")))))
  (it "does not keep obsolete OpenAI o-series entries"
      (expect (nil? (get router/KNOWN_MODEL_METADATA "o3")))
      (expect (nil? (get router/KNOWN_MODEL_METADATA "o3-mini")))
      (expect (nil? (get router/KNOWN_MODEL_METADATA "o4-mini"))))
  (it "flags Claude 5 / 4.x families as reasoning-capable"
      (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "claude-fable-5-1"))))
      (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "claude-opus-5"))))
      (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "claude-opus-4-8"))))
      (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "claude-opus-4-5"))))
      (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "claude-sonnet-4-5"))))
      (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "claude-haiku-4-5")))))
  (it "does NOT flag non-reasoning models"
      (expect (not (:reasoning? (get router/KNOWN_MODEL_METADATA "gpt-4o"))))
      (expect (not (:reasoning? (get router/KNOWN_MODEL_METADATA "gpt-4.1"))))
      (expect (not (:reasoning? (get router/KNOWN_MODEL_METADATA "deepseek-chat"))))
      (expect (not (:reasoning? (get router/KNOWN_MODEL_METADATA "deepseek-v3"))))
      (expect (not (:reasoning? (get router/KNOWN_MODEL_METADATA "minimax-m2.5")))))
  (it "flags older GLM models with binary :zai-thinking style"
      (doseq [name ["glm-4.6" "glm-4.6v" "glm-4.7" "glm-5.1" "glm-5-turbo" "glm-5v-turbo"]]
        (let [m (get router/KNOWN_MODEL_METADATA name)]
          (expect (true? (:reasoning? m)))
          (expect (= :zai-thinking (:reasoning-style m)))))
      (expect (= :zai-effort (:reasoning-style (get router/KNOWN_MODEL_METADATA "glm-5.2"))))
      ;; GLM-5.3 rides the same effort mechanism; only its rungs are wider.
      (expect (= :zai-effort (:reasoning-style (get router/KNOWN_MODEL_METADATA "glm-5.3")))))
  (it ":zai provider has per-token pricing for every reasoning-capable GLM"
      (doseq [name ["glm-4.6" "glm-4.6v" "glm-4.7" "glm-5.1" "glm-5-turbo" "glm-5v-turbo"]]
        (let [pricing (:pricing (router/provider-model-entry :zai name))]
          (expect (map? pricing))
          (expect (number? (:input pricing)))
          (expect (number? (:output pricing)))
          (expect (pos? (+ (:input pricing) (:output pricing)))))))
  (it ":zai-coding provider has pricing for subscription-overage accounting"
      ;; Coding Plan is subscription-billed but overage meters per-token.
      ;; Router keeps pricing so `budget-record!` stays honest.
      (doseq [name ["glm-4.6" "glm-4.6v" "glm-4.7" "glm-5.1" "glm-5-turbo" "glm-5v-turbo"]]
        (let [pricing (:pricing (router/provider-model-entry :zai-coding name))]
          (expect (map? pricing))
          (expect (pos? (+ (:input pricing) (:output pricing)))))))
  (it "`infer-model-metadata` propagates :reasoning? + :reasoning-style onto user maps"
      (let [resolved (router/infer-model-metadata {:name "gpt-5"})]
        (expect (true? (:reasoning? resolved)))
        (expect (= :openai-effort (:reasoning-style resolved))))
      (let [resolved (router/infer-model-metadata {:name "claude-sonnet-4-5"})]
        (expect (true? (:reasoning? resolved)))
        (expect (= :anthropic-thinking (:reasoning-style resolved))))
      (let [resolved (router/infer-model-metadata {:name "glm-4.6"})]
        (expect (true? (:reasoning? resolved)))
        (expect (= :zai-thinking (:reasoning-style resolved))))
      (let [resolved (router/infer-model-metadata {:name "gpt-4o"})]
        (expect (not (:reasoning? resolved))))))

(defdescribe
  provider-model-filter-test
  "Provider-scoped model filters."
  (it "flags current GitHub Copilot models with provider-specific API styles"
      (let [provider
            (router/normalize-provider 0
                                       {:id :github-copilot
                                        :api-key "test"
                                        :models [{:name "claude-opus-5"} {:name "gpt-5.6-luna"}
                                                 {:name "gpt-5.6-sol"} {:name "gpt-5.6-terra"}
                                                 {:name "gpt-5.2"} {:name "grok-code-fast-1"}]})

            by-name
            (zipmap (map :name (:models provider)) (:models provider))]

        (expect (= #{"claude-opus-5" "gpt-5.6-luna" "gpt-5.6-sol" "gpt-5.6-terra"}
                   (set (keys by-name))))
        (expect (= :anthropic (:api-style (get by-name "claude-opus-5"))))
        (expect (= :anthropic-thinking (:reasoning-style (get by-name "claude-opus-5"))))
        (doseq [model ["gpt-5.6-luna" "gpt-5.6-sol" "gpt-5.6-terra"]]
          (expect (= :openai-compatible-responses (:api-style (get by-name model))))
          (expect (= :openai-effort (:reasoning-style (get by-name model))))
          (expect (= 922000 (:context (get by-name model))))
          (expect (= {:effort "medium" :summary "detailed"}
                     (get-in by-name [model :extra-body :reasoning]))))
        (expect (nil? (get by-name "gpt-5.2")))
        (expect (nil? (get by-name "grok-code-fast-1")))))
  (it "filters OpenAI Codex GPT models below GPT-5.3"
      (let [provider (router/normalize-provider
                       0
                       {:id :openai-codex
                        :api-key "test"
                        :models [{:name "gpt-5"} {:name "gpt-5.1"} {:name "gpt-5.1-codex"}
                                 {:name "gpt-5.3-codex"} {:name "gpt-5.4"} {:name "gpt-5.5"}]})]
        (expect (= ["gpt-5.3-codex" "gpt-5.4" "gpt-5.5"] (mapv :name (:models provider))))
        (expect (router/provider-model-visible? :openai-codex "gpt-5.3-codex"))
        (expect (not (router/provider-model-visible? :openai-codex "gpt-5.1-codex"))))))

(defdescribe
  anthropic-thinking-max-tokens-clamp-test
  "Anthropic's API requires max_tokens > thinking.budget_tokens (thinking +
   output share one pool). When a caller sends thinking but forgets to raise
   max_tokens, we silently bump max_tokens to `budget + reserve` so at least
   a short visible answer fits."
  (let [clamp #'llm/clamp-anthropic-thinking-max-tokens]
    (it "leaves body untouched when :thinking is absent"
        (let [body {:model "gpt-4o" :max_tokens 4096}]
          (expect (= body (clamp body)))))
    (it "leaves body untouched when thinking is not :enabled"
        (let [body {:model "claude-sonnet-4-5" :max_tokens 4096 :thinking {:type "disabled"}}]
          (expect (= body (clamp body)))))
    (it "bumps max_tokens when below budget + reserve"
        (let [body {:model "claude-sonnet-4-5"
                    :max_tokens 4096
                    :thinking {:type "enabled" :budget_tokens 24000}}
              clamped (clamp body)]

          (expect (> (:max_tokens clamped) 24000))
          (expect (= 25024 (:max_tokens clamped))) ;; 24000 + 1024 reserve
          (expect (= (:thinking body) (:thinking clamped)))))
    (it "leaves max_tokens alone when already above budget + reserve"
        (let [body {:model "claude-sonnet-4-5"
                    :max_tokens 50000
                    :thinking {:type "enabled" :budget_tokens 24000}}]
          (expect (= body (clamp body)))))
    (it "handles each documented reasoning level"
        (doseq [[level expected-budget] [[:low 1024] [:balanced 8192] [:deep 24000]]]
          (let [tr (router/reasoning-extra-body :anthropic
                                                {:name "claude-opus-4-5" :reasoning? true}
                                                level)
                body (merge {:model "claude-opus-4-5" :max_tokens 2048} tr)
                clamped (clamp body)]

            (expect (= expected-budget (get-in clamped [:thinking :budget_tokens])))
            (expect (> (:max_tokens clamped) expected-budget)))))))
