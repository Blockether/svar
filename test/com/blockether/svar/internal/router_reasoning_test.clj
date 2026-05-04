(ns com.blockether.svar.internal.router-reasoning-test
  "Tests for the abstract reasoning-level → provider-specific translation.

   Covers:
   - `normalize-reasoning-level` vocabulary + OpenAI-alias back-compat.
   - `reasoning-extra-body` producing the right wire shape per api-style.
   - Silent no-op for non-reasoning models and unknown levels.
   - Anthropic budget-tokens magnitudes matching the documented thresholds.
   - Anthropic max_tokens clamp when thinking is enabled."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.router :as router]
   [com.blockether.svar.internal.llm :as llm]))

(defdescribe normalize-reasoning-level-test
  "Vocabulary normalization: canonical keywords + OpenAI aliases."

  (describe "canonical vocabulary (quick/balanced/deep)"
    (it "accepts canonical keywords"
      (expect (= :quick    (router/normalize-reasoning-level :quick)))
      (expect (= :balanced (router/normalize-reasoning-level :balanced)))
      (expect (= :deep     (router/normalize-reasoning-level :deep))))

    (it "accepts canonical strings, trimmed + case-insensitive"
      (expect (= :quick    (router/normalize-reasoning-level "quick")))
      (expect (= :balanced (router/normalize-reasoning-level " Balanced ")))
      (expect (= :deep     (router/normalize-reasoning-level "DEEP")))))

  (describe "OpenAI-style aliases (back-compat)"
    (it "maps low/medium/high to quick/balanced/deep"
      (expect (= :quick    (router/normalize-reasoning-level :low)))
      (expect (= :balanced (router/normalize-reasoning-level :medium)))
      (expect (= :deep     (router/normalize-reasoning-level :high)))
      (expect (= :quick    (router/normalize-reasoning-level "low")))
      (expect (= :balanced (router/normalize-reasoning-level "MEDIUM")))
      (expect (= :deep     (router/normalize-reasoning-level " high ")))))

  (describe "invalid input"
    (it "returns nil for unknown vocabulary"
      (expect (nil? (router/normalize-reasoning-level :turbo)))
      (expect (nil? (router/normalize-reasoning-level "ultra"))))

    (it "returns nil for nil / empty / non-string/keyword"
      (expect (nil? (router/normalize-reasoning-level nil)))
      (expect (nil? (router/normalize-reasoning-level "")))
      (expect (nil? (router/normalize-reasoning-level 42)))
      (expect (nil? (router/normalize-reasoning-level {:level :deep}))))))

(defdescribe reasoning-extra-body-test
  "Translator: abstract level → provider wire shape."

  (describe "OpenAI-compatible chat api-style"
    (it "emits flat :reasoning_effort for reasoning-capable models"
      (let [gpt5 {:name "gpt-5" :reasoning? true}]
        (expect (= {:reasoning_effort "low"}
                  (router/reasoning-extra-body :openai-compatible-chat gpt5 :quick)))
        (expect (= {:reasoning_effort "medium"}
                  (router/reasoning-extra-body :openai-compatible-chat gpt5 :balanced)))
        (expect (= {:reasoning_effort "high"}
                  (router/reasoning-extra-body :openai-compatible-chat gpt5 :deep)))))

    (it "accepts OpenAI-alias input too"
      (let [o3 {:name "o3" :reasoning? true}]
        (expect (= {:reasoning_effort "low"}
                  (router/reasoning-extra-body :openai-compatible-chat o3 :low)))
        (expect (= {:reasoning_effort "high"}
                  (router/reasoning-extra-body :openai-compatible-chat o3 "HIGH"))))))

  (describe "Anthropic api-style"
    (it "emits nested :thinking block for reasoning-capable models"
      ;; Fully-specified model (explicit :reasoning-style)
      (let [claude {:name "claude-sonnet-4-5" :reasoning? true :reasoning-style :anthropic-thinking}]
        (expect (= {:thinking {:type "enabled" :budget_tokens 1024}}
                  (router/reasoning-extra-body :anthropic claude :quick)))
        (expect (= {:thinking {:type "enabled" :budget_tokens 8192}}
                  (router/reasoning-extra-body :anthropic claude :balanced)))
        (expect (= {:thinking {:type "enabled" :budget_tokens 24000}}
                  (router/reasoning-extra-body :anthropic claude :deep)))))

    (it "infers :anthropic-thinking from :api-style :anthropic when style is unset"
      (let [claude {:name "unknown-claude" :reasoning? true}]
        (expect (= {:thinking {:type "enabled" :budget_tokens 8192}}
                  (router/reasoning-extra-body :anthropic claude :balanced)))))

    (it "budget_tokens values are strictly ascending"
      (let [claude {:name "claude-opus-4-5" :reasoning? true :reasoning-style :anthropic-thinking}
            budget (fn [lvl] (get-in (router/reasoning-extra-body :anthropic claude lvl)
                               [:thinking :budget_tokens]))]
        (expect (< (budget :quick) (budget :balanced) (budget :deep))))))

  (describe "Z.ai / GLM binary thinking"
    (it "emits `{:thinking {:type \"disabled\"}}` for :quick"
      (let [glm {:name "glm-4.6" :reasoning? true :reasoning-style :zai-thinking}]
        (expect (= {:thinking {:type "disabled"}}
                  (router/reasoning-extra-body :openai-compatible-chat glm :quick)))))

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
      (let [glm {:name "glm-4.7" :reasoning? true :reasoning-style :zai-thinking}
            out (router/reasoning-extra-body :openai-compatible-chat glm :deep)]
        (expect (nil? (get-in out [:thinking :budget_tokens]))))))

  (describe "Z.ai preserved thinking (clear_thinking: false)"
    (let [glm {:name "glm-4.7" :reasoning? true :reasoning-style :zai-thinking}]

      (it "WITHOUT `:preserved-thinking?` → no `clear_thinking` key in body"
        (let [out (router/reasoning-extra-body :openai-compatible-chat glm :deep)]
          (expect (= {:thinking {:type "enabled"}} out))
          (expect (nil? (get-in out [:thinking :clear_thinking])))))

      (it "WITH `:preserved-thinking? true` + `:deep` → emits clear_thinking: false"
        (let [out (router/reasoning-extra-body :openai-compatible-chat glm :deep
                    {:preserved-thinking? true})]
          (expect (= {:thinking {:type "enabled" :clear_thinking false}} out))))

      (it "preserved flag propagates even when thinking is disabled"
        ;; Edge: caller says \"don't think this turn, but preserve reasoning
        ;; from prior turns\". The abstract level disables thinking; the
        ;; preserved flag still rides along so the server can retain history.
        (let [out (router/reasoning-extra-body :openai-compatible-chat glm :quick
                    {:preserved-thinking? true})]
          (expect (= {:thinking {:type "disabled" :clear_thinking false}} out))))

      (it "`:preserved-thinking? false` → same as omitting it"
        (let [out-false  (router/reasoning-extra-body :openai-compatible-chat glm :deep
                           {:preserved-thinking? false})
              out-absent (router/reasoning-extra-body :openai-compatible-chat glm :deep)]
          (expect (= out-false out-absent))
          (expect (nil? (get-in out-false [:thinking :clear_thinking])))))

      (it "is silently ignored on :openai-effort models"
        (let [gpt5 {:name "gpt-5" :reasoning? true :reasoning-style :openai-effort}
              out (router/reasoning-extra-body :openai-compatible-chat gpt5 :deep
                    {:preserved-thinking? true})]
          (expect (= {:reasoning_effort "high"} out))
          (expect (nil? (:thinking out)))))

      (it "is silently ignored on :anthropic-thinking models"
        (let [claude {:name "claude-sonnet-4-5" :reasoning? true
                      :reasoning-style :anthropic-thinking}
              out (router/reasoning-extra-body :anthropic claude :deep
                    {:preserved-thinking? true})]
          (expect (= {:thinking {:type "enabled" :budget_tokens 24000}} out))
          (expect (nil? (get-in out [:thinking :clear_thinking])))))

      (it "`:preserved-thinking?` without `:reasoning` level is a full no-op"
        ;; Don't silently emit clear_thinking with no thinking block — the
        ;; reasoning translator short-circuits when level is nil.
        (expect (nil? (router/reasoning-extra-body :openai-compatible-chat glm nil
                        {:preserved-thinking? true}))))))

  (describe "non-reasoning models (silent no-op)"
    (it "returns nil when model lacks :reasoning? flag"
      (expect (nil? (router/reasoning-extra-body :openai-compatible-chat    {:name "gpt-4o"} :deep)))
      (expect (nil? (router/reasoning-extra-body :anthropic {:name "claude-haiku-3"} :deep))))

    (it "returns nil when :reasoning? is explicitly false"
      (expect (nil? (router/reasoning-extra-body :openai-compatible-chat {:name "gpt-4o" :reasoning? false} :deep)))))

  (describe "unknown level (silent no-op)"
    (it "returns nil when level is unknown"
      (expect (nil? (router/reasoning-extra-body :openai-compatible-chat {:name "gpt-5" :reasoning? true} :turbo)))
      (expect (nil? (router/reasoning-extra-body :openai-compatible-chat {:name "gpt-5" :reasoning? true} nil)))))

  (describe "api-style fallback"
    (it "treats unknown api-style as openai-compatible-chat (most gateways are openai-compatible)"
      (let [model {:name "deepseek-reasoner" :reasoning? true}]
        (expect (= {:reasoning_effort "high"}
                  (router/reasoning-extra-body :custom-gateway model :deep)))
        (expect (= {:reasoning_effort "low"}
                  (router/reasoning-extra-body nil model :quick)))))

    (it "explicit :reasoning-style always wins over api-style inference"
      ;; A z.ai model behind an OpenAI-compat api-style still emits z.ai thinking.
      (let [glm {:name "glm-4.6" :reasoning? true :reasoning-style :zai-thinking}]
        (expect (= {:thinking {:type "enabled"}}
                  (router/reasoning-extra-body :openai-compatible-chat glm :deep))))
      ;; Even under `:anthropic`, explicit openai-effort wins.
      (let [weird {:name "custom-o3" :reasoning? true :reasoning-style :openai-effort}]
        (expect (= {:reasoning_effort "high"}
                  (router/reasoning-extra-body :anthropic weird :deep)))))))

(defdescribe known-model-reasoning-flags-test
  "Sanity check that KNOWN_MODEL_METADATA flags the expected reasoning models."

  (it "flags GPT-5 family as reasoning-capable"
    (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "gpt-5"))))
    (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "gpt-5-mini"))))
    (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "gpt-5.1"))))
    (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "gpt-5.3-codex")))))

  (it "flags OpenAI o-series as reasoning-capable"
    (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "o3"))))
    (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "o3-mini"))))
    (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "o4-mini")))))

  (it "flags Claude 4.x family as reasoning-capable"
    (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "claude-opus-4-5"))))
    (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "claude-sonnet-4-5"))))
    (expect (true? (:reasoning? (get router/KNOWN_MODEL_METADATA "claude-haiku-4-5")))))

  (it "does NOT flag non-reasoning models"
    (expect (not (:reasoning? (get router/KNOWN_MODEL_METADATA "gpt-4o"))))
    (expect (not (:reasoning? (get router/KNOWN_MODEL_METADATA "gpt-4.1"))))
    (expect (not (:reasoning? (get router/KNOWN_MODEL_METADATA "deepseek-chat"))))
    (expect (not (:reasoning? (get router/KNOWN_MODEL_METADATA "deepseek-v3"))))
    (expect (not (:reasoning? (get router/KNOWN_MODEL_METADATA "minimax-m2.5")))))

  (it "flags GLM-4.6+ as reasoning-capable with :zai-thinking style"
    (doseq [name ["glm-4.6" "glm-4.6v" "glm-4.7" "glm-5.1" "glm-5-turbo"]]
      (let [m (get router/KNOWN_MODEL_METADATA name)]
        (expect (true? (:reasoning? m)))
        (expect (= :zai-thinking (:reasoning-style m))))))

  (it ":zai provider has per-token pricing for every reasoning-capable GLM"
    (doseq [name ["glm-4.6" "glm-4.6v" "glm-4.7" "glm-5.1" "glm-5-turbo"]]
      (let [pricing (:pricing (router/provider-model-entry :zai name))]
        (expect (map? pricing))
        (expect (number? (:input pricing)))
        (expect (number? (:output pricing)))
        (expect (pos? (+ (:input pricing) (:output pricing)))))))

  (it ":zai-coding provider has pricing for subscription-overage accounting"
    ;; Coding Plan is subscription-billed but overage meters per-token.
    ;; Router keeps pricing so `budget-record!` stays honest.
    (doseq [name ["glm-4.6" "glm-4.6v" "glm-4.7" "glm-5.1" "glm-5-turbo"]]
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

(it "flags GitHub Copilot known models with provider-specific API styles"
  (let [provider (router/normalize-provider 0 {:id :github-copilot
                                               :api-key "test"
                                               :models [{:name "claude-sonnet-4-6"}
                                                        {:name "gpt-5.4"}
                                                        {:name "gpt-5.3-codex"}
                                                        {:name "gpt-5.2-codex"}
                                                        {:name "gpt-4o"}]})
        by-name (zipmap (map :name (:models provider)) (:models provider))]
    (expect (= #{"claude-sonnet-4-6" "gpt-5.4" "gpt-5.3-codex"}
              (set (keys by-name))))
    (expect (= :anthropic (:api-style (get by-name "claude-sonnet-4-6"))))
    (expect (= :openai-compatible-responses (:api-style (get by-name "gpt-5.4"))))
    (expect (= :openai-compatible-responses (:api-style (get by-name "gpt-5.3-codex"))))
    (expect (nil? (get by-name "gpt-5.2-codex")))
    (expect (nil? (get by-name "gpt-4o")))
    (expect (= {:effort "medium" :summary "detailed"}
              (get-in by-name ["gpt-5.4" :extra-body :reasoning])))))

(it "filters OpenAI Codex GPT models below GPT-5.3"
  (let [provider (router/normalize-provider 0 {:id :openai-codex
                                               :api-key "test"
                                               :models [{:name "gpt-5"}
                                                        {:name "gpt-5.1"}
                                                        {:name "gpt-5.2"}
                                                        {:name "gpt-5.2-codex"}
                                                        {:name "gpt-5.3-codex"}
                                                        {:name "gpt-5.4"}
                                                        {:name "gpt-5.5"}]})]
    (expect (= ["gpt-5.3-codex" "gpt-5.4" "gpt-5.5"]
              (mapv :name (:models provider))))
    (expect (router/provider-model-visible? :openai-codex "gpt-5.3-codex"))
    (expect (not (router/provider-model-visible? :openai-codex "gpt-5.2-codex")))))

(defdescribe anthropic-thinking-max-tokens-clamp-test
  "Anthropic's API requires max_tokens > thinking.budget_tokens (thinking +
   output share one pool). When a caller sends thinking but forgets to raise
   max_tokens, we silently bump max_tokens to `budget + reserve` so at least
   a short visible answer fits."
  (let [clamp #'llm/clamp-anthropic-thinking-max-tokens]

    (it "leaves body untouched when :thinking is absent"
      (let [body {:model "gpt-4o" :max_tokens 4096}]
        (expect (= body (clamp body)))))

    (it "leaves body untouched when thinking is not :enabled"
      (let [body {:model "claude-sonnet-4-5" :max_tokens 4096
                  :thinking {:type "disabled"}}]
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
      (doseq [[level expected-budget] [[:quick 1024] [:balanced 8192] [:deep 24000]]]
        (let [tr (router/reasoning-extra-body :anthropic
                   {:name "claude-opus-4-5" :reasoning? true} level)
              body (merge {:model "claude-opus-4-5" :max_tokens 2048} tr)
              clamped (clamp body)]
          (expect (= expected-budget (get-in clamped [:thinking :budget_tokens])))
          (expect (> (:max_tokens clamped) expected-budget)))))))
