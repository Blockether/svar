(ns com.blockether.svar.internal.router
  "Router: provider/model registry, circuit breakers, rate limiting, budget tracking,
   and routing resolution.

   Extracted from defaults.clj (provider/model metadata) and llm.clj (routing logic)
   to provide a single cohesive namespace for all routing concerns."
  (:require
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.internal.modelsdev :as modelsdev]
   [taoensso.trove :as trove])
  (:import
   (com.knuddels.jtokkit Encodings)
   (com.knuddels.jtokkit.api
     Encoding
     EncodingRegistry
     EncodingType
     IntArrayList
     ModelType)
   (java.io ByteArrayInputStream)
   (java.net HttpURLConnection URI)
   (java.util Base64 Locale UUID)
   (javax.imageio ImageIO ImageReader)
   (javax.imageio.stream ImageInputStream)))

;; =============================================================================
;; Known Providers
;; =============================================================================

(def KNOWN_PROVIDERS
  {:openai      {:base-url "https://api.openai.com/v1"           :rpm 500 :tpm 2000000
                 :env-keys ["OPENAI_API_KEY"]
                 :default-models [{:name "gpt-5"} {:name "gpt-5-mini"} {:name "gpt-4o"} {:name "gpt-4o-mini"} {:name "o3-mini"}]}
   :anthropic   {:base-url "https://api.anthropic.com/v1"        :rpm 500 :tpm 2000000
                 :env-keys ["ANTHROPIC_API_KEY"] :api-style :anthropic
                 :default-models [{:name "claude-opus-4-8"} {:name "claude-opus-4-7"} {:name "claude-opus-4-6"} {:name "claude-sonnet-4-6"} {:name "claude-haiku-4-5"}]}
   :anthropic-coding-plan
   {:base-url "https://api.anthropic.com/v1" :rpm 500 :tpm 2000000
    :env-keys [] :api-style :anthropic
    :provider-model-source :anthropic
    ;; OAuth coding plan: use retail Anthropic pricing for honest metering
    ;; once the included quota is exhausted (see internal/modelsdev).
    :pricing-source :anthropic
    :default-models [{:name "claude-opus-4-8"} {:name "claude-opus-4-7"} {:name "claude-opus-4-6"} {:name "claude-sonnet-4-6"} {:name "claude-haiku-4-5"}]
    :prepend-default-models? true}
   :zai         {:base-url "https://api.z.ai/api/anthropic/v1" :api-style :anthropic :rpm 500 :tpm 2000000 ; GLM rides the z.ai Anthropic-Messages endpoint — native tool_use. The chat wire (/paas/v4) is XML-poisoned (see TOOL_CALLING.md).
                 :env-keys ["ZAI_API_KEY"]
                 :default-models [{:name "glm-5.2"} {:name "glm-5-turbo"} {:name "glm-5.1"} {:name "glm-4.7"} {:name "glm-4.6v"}]}
   :zai-coding  {:base-url "https://api.z.ai/api/anthropic/v1" :api-style :anthropic :rpm 500 :tpm 2000000
                 ;; Coding Plan endpoint, but for budget accounting we use
                 ;; retail :zai per-token rates (the plan meters overage at
                 ;; the same rates). `:provider-model-source :zai` lets the
                 ;; svar overlay inherit `:json-object-mode?` from :zai's
                 ;; GLM table so we don't duplicate the entries.
                 :pricing-source :zai
                 :provider-model-source :zai
                 :env-keys ["ZAI_CODING_API_KEY" "ZAI_API_KEY"]}
   ;; Z.ai Coding Plan runtime alias. Vis registers this as
   ;; `:zai-coding-plan` (see `vis-provider-zai`); without the alias the
   ;; catalog miss reproduces the same `max_tokens = 2048` bug observed
   ;; on Copilot. Same policy as `:zai-coding`; keep both keys in sync.
   :zai-coding-plan
   {:base-url "https://api.z.ai/api/anthropic/v1" :api-style :anthropic :rpm 500 :tpm 2000000
    :pricing-source :zai
    :provider-model-source :zai
    :env-keys ["ZAI_CODING_API_KEY" "ZAI_API_KEY"]
    :default-models [{:name "glm-5.2"} {:name "glm-5-turbo"} {:name "glm-4.7"} {:name "glm-5.1"}]}
   ;; Native Google Gemini (generateContent), NOT the OpenAI-compat shim.
   ;; `:api-style :gemini` selects the native wire: `tool_use` ↔ `functionCall`,
   ;; results ↔ `functionResponse`, auth via `x-goog-api-key`. Clean native
   ;; function calling (unlike GLM-on-chat, which z.ai poisons with an XML
   ;; tool-call prompt).
   :gemini      {:base-url "https://generativelanguage.googleapis.com/v1beta" :rpm 500 :tpm 2000000
                 :api-style :gemini
                 :default-models [{:name "gemini-3-pro-preview"} {:name "gemini-3-flash-preview"}
                                  {:name "gemini-2.5-pro"} {:name "gemini-2.5-flash"}]
                 :env-keys ["GEMINI_API_KEY" "GOOGLE_API_KEY"]}
   :openrouter  {:base-url "https://openrouter.ai/api/v1"        :rpm 500 :tpm 2000000
                 :env-keys ["OPENROUTER_API_KEY"]}
   ;; Mistral — OpenAI-compatible `/v1/chat/completions`. No `:api-style` needed
   ;; (default OpenAI chat wire). The `:mistral` slug already exists in the bundled
   ;; `resources/models.dev.json` (env `MISTRAL_API_KEY`, full model catalog with
   ;; real context windows), so pricing/context/capabilities auto-resolve via
   ;; `modelsdev/provider-models`; NO `KNOWN_PROVIDER_MODELS` overlay required.
   ;; See `provider-pricing-source` (router.clj) — it keys models.dev off `:id`,
   ;; and `:mistral` is already a known catalog slug.
   :mistral     {:base-url "https://api.mistral.ai/v1"  :rpm 500 :tpm 2000000
                 :env-keys ["MISTRAL_API_KEY"]
                 :default-models [{:name "mistral-large-latest"}
                                  {:name "mistral-medium-latest"}
                                  {:name "mistral-small-latest"}
                                  {:name "codestral-latest"}]}
   :github-copilot {:base-url "https://api.individual.githubcopilot.com" :rpm 500 :tpm 2000000
                    :default-models [{:name "claude-opus-4.8"} {:name "claude-sonnet-4.6"} {:name "claude-haiku-4.5"} {:name "gpt-5.4"} {:name "gpt-5.4-mini"} {:name "gpt-5.3-codex"}]
                    :llm-headers {"Editor-Version" "vscode/1.100.0"
                                  "Editor-Plugin-Version" "copilot-chat/0.26.7"
                                  "Copilot-Integration-Id" "vscode-chat"
                                  "User-Agent" "GitHubCopilotChat/0.26.7"}
                    ;; Copilot currently exposes many historical GPT models; keep
                    ;; the GPT family at gpt-5.3+ only. Other families (Claude,
                    ;; Gemini, Grok) stay selectable.
                    :min-gpt-version [5 3]
                    :exclude-models #{"gpt-4o" "gpt-4.1"
                                      "gpt-5" "gpt-5-mini" "gpt-5.1"
                                      "gpt-5.1-codex" "gpt-5.1-codex-max" "gpt-5.1-codex-mini"
                                      ;; Copilot advertises this on some accounts,
                                      ;; then rejects inference with
                                      ;; `400 model_not_supported`. Keep it out of
                                      ;; cost routing until endpoint support is real.
                                      "grok-code-fast-1"}
                    :env-keys ["COPILOT_GITHUB_TOKEN" "GH_TOKEN" "GITHUB_TOKEN"]}
   ;; Copilot plan tiers — runtime provider IDs (one per OAuth plan) that
   ;; INHERIT policy from `:github-copilot` via `:provider-model-source`.
   ;; `known-provider` merges the base entry under each tier-specific
   ;; override so `:exclude-models`, `:min-gpt-version`, `:llm-headers`,
   ;; etc. apply uniformly; only `:base-url` differs per tier so token
   ;; exchange points at the right host. Without these aliases the
   ;; model catalog (`KNOWN_PROVIDER_MODELS :github-copilot`) was
   ;; invisible to plan-tier providers — every registration fell back
   ;; to bare `KNOWN_MODEL_METADATA` (capabilities + intelligence only),
   ;; lost `:context` / `:api-style` / `:reasoning-style`, and
   ;; `auto-params` produced `max_tokens = 0.25 * 8192 = 2048` per
   ;; request. Observed symptom on session 52983a42 (2026-05-20):
   ;; claude-sonnet-4.6 burning the whole 2048-token budget on hidden
   ;; reasoning and surfacing `:svar.llm/empty-content` /
   ;; `:vis/comment-only-block` errors mid-turn.
   :github-copilot-individual
   {:base-url "https://api.individual.githubcopilot.com"
    :provider-model-source :github-copilot}
   :github-copilot-business
   {:base-url "https://api.business.githubcopilot.com"
    :provider-model-source :github-copilot}
   :github-copilot-enterprise
   {:base-url "https://api.enterprise.githubcopilot.com"
    :provider-model-source :github-copilot}
   :openai-codex {:base-url "https://chatgpt.com/backend-api"     :rpm 500 :tpm 2000000
                  :env-keys [] :api-style :openai-compatible-responses
                  :default-models [{:name "gpt-5.5"} {:name "gpt-5.4"} {:name "gpt-5.3-codex"}]
                  ;; Keep Codex GPT models at gpt-5.3+ only.
                  :min-gpt-version [5 3]
                  :exclude-models #{"gpt-4o" "gpt-4.1"
                                    "gpt-5" "gpt-5-mini" "gpt-5.1"
                                    "gpt-5.1-codex" "gpt-5.1-codex-max" "gpt-5.1-codex-mini"}
                  ;; Codex plan: pull retail OpenAI pricing for metering
                  ;; via internal/modelsdev (`:pricing-source` overlay).
                  :pricing-source :openai
                  :responses-path "/codex/responses"
                  ;; `/codex/models` returns the live Codex inference
                  ;; catalog (gpt-5.3-codex et al.) and refuses without
                  ;; `client_version`. The bare `/models` route under
                  ;; the same host returns the chatgpt.com product
                  ;; catalog (research, agent-mode, ...) which is not
                  ;; the inference fleet. We pin a known-good
                  ;; `client_version` here so callers don't have to
                  ;; care about wire details. The shape under
                  ;; `:models` is `{slug, display_name, ...}` — the
                  ;; `normalize-models-response` in `internal/llm`
                  ;; promotes `:slug` to `:id` so downstream filters
                  ;; work unchanged.
                  ;;
                  ;; Codex gates new model rollouts behind
                  ;; `client_version`: <0.99 only sees gpt-5.2; 0.99
                  ;; adds gpt-5.4/5.3-codex; >=1.0.0 adds gpt-5.5
                  ;; and gpt-5.3-codex-spark. Pin a high version so
                  ;; svar surfaces the whole fleet — OpenAI server
                  ;; doesn't validate that we actually run Codex CLI.
                  :models-path "/codex/models"
                  :models-query-params {"client_version" "1.0.0"}
                  :extra-body {:store false
                               :include ["reasoning.encrypted_content"]
                               :reasoning {:summary "detailed"}
                               :text {:verbosity "low"}}}
   ;; Ollama (v0.14.0+, Jan 2026) and LM Studio (v0.4.1+, Jan 2026) both serve
   ;; a native Anthropic Messages endpoint at `<host>/v1/messages` — base-url
   ;; ends in `/v1` and the :anthropic builder appends `/messages`. Prefer it
   ;; over chat-completions so local Claude-class models get native thinking
   ;; blocks (Ollama maps `thinking`; LM Studio supports it from v0.4.x).
   ;; Neither validates the key, but Ollama REQUIRES the header present, so a
   ;; non-blank placeholder ships by default. (Prompt caching isn't supported
   ;; locally — irrelevant for a local box.) Model discovery still uses the
   ;; OpenAI `/v1/models` (Ollama) / `/api/v0/models` (LM Studio) paths below,
   ;; independent of the chat api-style.
   :ollama      {:base-url "http://localhost:11434/v1"            :rpm 1000 :tpm 10000000
                 :api-style :anthropic :api-key "ollama"
                 :env-keys []}
   ;; LM Studio's OpenAI-compatible `/v1/models` omits context length, so
   ;; svar would fall back to DEFAULT_CONTEXT_LIMIT and cripple a model that
   ;; actually serves 100k+. Its native REST endpoint at `/api/v0/models`
   ;; (host root, NOT under `/v1`) reports `max_context_length`,
   ;; `loaded_context_length`, and `capabilities` — `models!` reads it via the
   ;; `:models-base :host` + `:models-shape :lmstudio` hooks below.
   :lmstudio    {:base-url "http://localhost:1234/v1"             :rpm 1000 :tpm 10000000
                 :api-style :anthropic :api-key "lmstudio"
                 :env-keys []
                 :models-path "/api/v0/models"
                 :models-base :host
                 :models-shape :lmstudio}})

;; =============================================================================
;; Provider-independent model-family metadata
;; =============================================================================

(def KNOWN_MODEL_METADATA
  "Per-model static metadata. `:reasoning?` flags a model whose provider
   accepts a reasoning-depth parameter. `:reasoning-style` (optional) pins the
   wire shape to emit — see `REASONING_LEVELS` keys. When omitted, the style
   is inferred from the provider's `:api-style` (`:anthropic` → anthropic
   thinking, everything else → openai-effort)."
  {;; ── OpenAI GPT-4o ────────────────────────────────────────────────────────
   "gpt-4o"                    {:intelligence :high     :speed :medium :capabilities #{:chat :vision}}

   ;; ── OpenAI GPT-4.1 ──────────────────────────────────────────────────────
   "gpt-4.1"                   {:intelligence :high     :speed :medium :capabilities #{:chat :vision}}

   ;; ── OpenAI GPT-5 (reasoning-capable, reasoning_effort) ──────────────────
   "gpt-5"                     {:intelligence :frontier :speed :medium :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}
   "gpt-5-mini"                {:intelligence :high     :speed :fast   :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}
   "gpt-5.1"                   {:intelligence :frontier :speed :medium :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}
   "gpt-5.1-codex"             {:intelligence :frontier :speed :medium :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}
   "gpt-5.1-codex-mini"        {:intelligence :high     :speed :fast   :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}
   "gpt-5.1-codex-max"         {:intelligence :frontier :speed :medium :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}
   "gpt-5.3-codex"             {:intelligence :high     :speed :medium :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}
   "gpt-5.3-codex-spark"       {:intelligence :high     :speed :fast   :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}
   "gpt-5.4"                   {:intelligence :frontier :speed :medium :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}
   "gpt-5.4-mini"              {:intelligence :high     :speed :fast   :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}
   "gpt-5.5"                   {:intelligence :frontier :speed :fast   :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}

   ;; ── Anthropic Claude Fable / Mythos / 4.x (adaptive + extended thinking) ─
   "claude-fable-5"            {:intelligence :frontier :speed :slow   :capabilities #{:chat :vision} :reasoning? true :reasoning-style :anthropic-thinking}
   "claude-mythos-5"           {:intelligence :frontier :speed :slow   :capabilities #{:chat :vision} :reasoning? true :reasoning-style :anthropic-thinking}
   "claude-opus-4-8"           {:intelligence :frontier :speed :slow   :capabilities #{:chat :vision} :reasoning? true :reasoning-style :anthropic-thinking}
   "claude-opus-4-7"           {:intelligence :frontier :speed :slow   :capabilities #{:chat :vision} :reasoning? true :reasoning-style :anthropic-thinking}
   "claude-opus-4-6"           {:intelligence :frontier :speed :slow   :capabilities #{:chat :vision} :reasoning? true :reasoning-style :anthropic-thinking}
   "claude-opus-4-5"           {:intelligence :frontier :speed :slow   :capabilities #{:chat :vision} :reasoning? true :reasoning-style :anthropic-thinking}
   "claude-sonnet-4"           {:intelligence :high     :speed :medium :capabilities #{:chat :vision} :reasoning? true :reasoning-style :anthropic-thinking}
   "claude-sonnet-4-6"         {:intelligence :high     :speed :medium :capabilities #{:chat :vision} :reasoning? true :reasoning-style :anthropic-thinking}
   "claude-sonnet-4-5"         {:intelligence :high     :speed :medium :capabilities #{:chat :vision} :reasoning? true :reasoning-style :anthropic-thinking}
   "claude-sonnet-4-20250514"  {:intelligence :high     :speed :medium :capabilities #{:chat :vision} :reasoning? true :reasoning-style :anthropic-thinking}
   "claude-haiku-4-5"          {:intelligence :medium   :speed :fast   :capabilities #{:chat :vision} :reasoning? true :reasoning-style :anthropic-thinking}

   ;; ── Google Gemini 2.5 (reasoning_effort via OpenAI-compat gateway) ──────
   "gemini-2.5-pro"            {:intelligence :frontier :speed :medium :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}
   "gemini-2.5-flash"          {:intelligence :high     :speed :fast   :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}
   "gemini-3-flash-preview"    {:intelligence :high     :speed :fast   :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}
   "gemini-3-pro-preview"      {:intelligence :frontier :speed :medium :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}
   "gemini-3.1-pro-preview"    {:intelligence :frontier :speed :medium :capabilities #{:chat :vision} :reasoning? true :reasoning-style :openai-effort}
   "gemini-2.0-flash"          {:intelligence :high     :speed :fast   :capabilities #{:chat :vision}}

   ;; ── xAI / Copilot coding models ────────────────────────────────────────
   "grok-code-fast-1"          {:intelligence :high     :speed :fast   :capabilities #{:chat} :reasoning? true :reasoning-style :openai-effort}

   ;; ── Zhipu / ZAI (GLM-4.6+ binary thinking: `thinking: {type: enabled}`) ─
   ;; Z.ai's chat/completions endpoint is OpenAI-compatible for everything
   ;; EXCEPT reasoning — it uses a binary `thinking` object at the top level.
   ;; Streaming delta surfaces reasoning_content (already handled).
   "glm-4.6"                   {:intelligence :high     :speed :medium :capabilities #{:chat}         :reasoning? true :reasoning-style :anthropic-thinking}
   "glm-4.6v"                  {:intelligence :high     :speed :medium :capabilities #{:chat :vision} :reasoning? true :reasoning-style :anthropic-thinking}
   "glm-4.7"                   {:intelligence :high     :speed :medium :capabilities #{:chat}         :reasoning? true :reasoning-style :anthropic-thinking}
   "glm-5.1"                   {:intelligence :high     :speed :medium :capabilities #{:chat}         :reasoning? true :reasoning-style :anthropic-thinking}
   "glm-5.2"                   {:intelligence :high     :speed :medium :capabilities #{:chat}         :reasoning? true :reasoning-style :zai-effort}
   "glm-5-turbo"               {:intelligence :high     :speed :fast   :capabilities #{:chat}         :reasoning? true :reasoning-style :anthropic-thinking}
   "glm-5v-turbo"              {:intelligence :high     :speed :fast   :capabilities #{:chat :vision} :reasoning? true :reasoning-style :anthropic-thinking}

   ;; ── DeepSeek (reasoning_effort on reasoner only) ────────────────────────
   "deepseek-v3"               {:intelligence :high     :speed :medium :capabilities #{:chat}}
   "deepseek-v3.2"             {:intelligence :high     :speed :medium :capabilities #{:chat}}
   "deepseek-chat"             {:intelligence :high     :speed :medium :capabilities #{:chat}}
   "deepseek-reasoner"         {:intelligence :frontier :speed :slow   :capabilities #{:chat} :reasoning? true :reasoning-style :openai-effort}})

;; =============================================================================
;; Reasoning-depth translation (abstract → provider-specific)
;; =============================================================================

(def REASONING_LEVELS
  "Abstract reasoning levels translated per reasoning-style.
   Vocabulary is intentionally provider-neutral — callers pass :quick|:balanced|:deep
   and svar picks the right on-the-wire shape for the selected model.

   Sub-key semantics:
     `:openai-effort`      → flat top-level `:reasoning_effort` string.
                             Used by GPT-5.x, o-series, Gemini 2.5 via OpenAI gateway,
                             DeepSeek Reasoner, Copilot GPT-5+, and most
                             OpenAI-compatible reasoners.
     `:anthropic-thinking` → Claude thinking controls.
                             Claude Opus 4.8 / Opus 4.7 / Opus 4.6 / Sonnet 4.6 use
                             adaptive thinking + output_config.effort. Older
                             Claude 4 models use manual budget_tokens.
     `:zai-thinking`       → binary `:thinking {:type \"enabled\"|\"disabled\"}` on
                             Z.ai / GLM-4.6+. No budget_tokens — thinking is on/off.
                             `:quick` disables, `:balanced`/`:deep` enable.
                             See also `:preserved-thinking?` below for the
                             `clear_thinking: false` flag that keeps reasoning
                             across assistant turns.
     `:server-managed`     → explicit no-op style for proxies that gate
                             reasoning server-side and reject (or silently
                             mis-route) client-supplied `reasoning_effort`
                             / `thinking` fields. Modeled on pi-ai's
                             `compat.supportsReasoningEffort: false` flag
                             for Copilot Claude / Gemini / Grok: every
                             entry in `REASONING_LEVELS` resolves to nil,
                             so `reasoning-extra-body` returns nil and the
                             wire body carries no reasoning field at all.
                             Without this style, the May 2026 Copilot Claude
                             switch from `:api-style :anthropic` (Anthropic
                             /messages with thinking blocks) to
                             `:openai-compatible-chat` (with `reasoning_effort`)
                             made Copilot proxy bias Claude into excessive
                             autonomous reasoning loops — observable on
                             session 52983a42 / 831cedee as 5K-8K output
                             tokens per iteration burned on hidden thinking."
  {:quick    {:openai-effort "low"    :anthropic-thinking 1024  :zai-thinking "disabled" :zai-effort "off"  :server-managed nil}
   :balanced {:openai-effort "medium" :anthropic-thinking 8192  :zai-thinking "enabled"  :zai-effort "high" :server-managed nil}
   :deep     {:openai-effort "high"   :anthropic-thinking 24000 :zai-thinking "enabled"  :zai-effort "max"  :server-managed nil}})

(defn normalize-reasoning-level
  "Coerce any accepted spelling to a canonical :quick|:balanced|:deep keyword.
   Accepts:
     - :quick / :balanced / :deep (keywords, case-insensitive)
     - \"quick\" / \"balanced\" / \"deep\" (strings, case-insensitive)
     - OpenAI-style aliases :low→:quick, :medium→:balanced, :high→:deep
       (so `:reasoning_effort` migrations don't break).
   Returns nil for unknown input."
  [v]
  (let [raw (cond
              (keyword? v) (name v)
              (string? v)  v
              :else        nil)
        s (when raw (str/lower-case (str/trim raw)))]
    (case s
      ("quick" "low")       :quick
      ("balanced" "medium") :balanced
      ("deep" "high")       :deep
      nil)))

(defn- infer-reasoning-style
  "Pick a reasoning-style for a model that lacks an explicit `:reasoning-style`.
   Conservative fallback so unknown reasoning-capable models still produce
   *something* sensible:
     - `:api-style :anthropic` → `:anthropic-thinking`
     - everything else          → `:openai-effort` (most gateways accept it)"
  [api-style model-map]
  (or (:reasoning-style model-map)
    (if (= api-style :anthropic) :anthropic-thinking :openai-effort)))

(defn- anthropic-adaptive-thinking-model?
  "Fable 5 / Mythos 5 / Opus 4.8–4.7 reject manual budget_tokens. Opus 4.6
   and Sonnet 4.6 still accept manual thinking today, but Anthropic marks it
   deprecated. Use adaptive thinking for all families listed here. Accept
   dot/dash aliases so Copilot-style names do not regress."
  [model-name]
  (boolean
    (re-find #"(?i)^claude-(?:fable-5|mythos-5|opus-4[-.][6-8]|sonnet-4[-.]6)(?:$|-)"
      (str model-name))))

(defn- anthropic-thinking-extra-body
  [model-map norm budget]
  (if (anthropic-adaptive-thinking-model? (:name model-map))
    {:thinking {:type "adaptive"
                :display "summarized"}
     :output_config {:effort (get-in REASONING_LEVELS [norm :openai-effort])}}
    {:thinking {:type "enabled" :budget_tokens budget}}))

(defn reasoning-extra-body
  "Translates an abstract reasoning level into provider-specific extra-body.
   Returns nil when:
     - `level` is nil / unknown
     - the selected model is not flagged `:reasoning?`
     - the reasoning-style has no mapping in REASONING_LEVELS.

   Dispatches on the model's `:reasoning-style` first (explicit pin), falling
   back to inference from `api-style` when the model doesn't declare one.

   Callers pass the returned map through merge into their extra-body; silent
   nil keeps non-reasoning models untouched.

   Four-arity form takes an opts map:
     `:preserved-thinking?` — Z.ai-only. Emits `clear_thinking: false` inside
        the `:thinking` block, asking the server to retain reasoning_content
        from previous assistant turns (Preserved Thinking, GLM-5 / GLM-4.7).
        Callers using this MUST echo the complete, unmodified reasoning_content
        back to the API in subsequent assistant turns, otherwise cache hit
        rates and model quality degrade. No-op on non-z.ai reasoning styles
        and on the Coding Plan endpoint (which has preserved thinking on
        server-side by default, but setting the flag explicitly is harmless)."
  ([api-style model-map level]
   (reasoning-extra-body api-style model-map level nil))
  ([api-style model-map level {:keys [preserved-thinking?]}]
   (when-let [norm (normalize-reasoning-level level)]
     (when (:reasoning? model-map)
       (let [raw-style (infer-reasoning-style api-style model-map)
             ;; The emitted reasoning BODY must be valid for the WIRE, not just
             ;; the model's native convention. A model can be served across
             ;; wires — e.g. Claude Opus via GitHub Copilot's OpenAI-compatible
             ;; CHAT endpoint, or any Anthropic model behind an OpenAI proxy.
             ;; Anthropic's `{:thinking …}` / `:output_config` fields are
             ;; MEANINGLESS on an OpenAI-compatible wire: the endpoint silently
             ;; ignores them (so the model runs with NO reasoning — the "same
             ;; Opus is worse on Copilot" report) or 400s. On a non-Anthropic
             ;; wire, coerce an Anthropic-thinking pin to the OpenAI reasoning
             ;; knob (`reasoning_effort`) — the correct shape for chat
             ;; completions; ignored harmlessly if the proxy doesn't honor it.
             style (if (and (= raw-style :anthropic-thinking)
                         (not= api-style :anthropic))
                     :openai-effort
                     raw-style)
             mapped (get-in REASONING_LEVELS [norm style])]
         (when mapped
           (case style
             :openai-effort      {:reasoning_effort mapped}
             :anthropic-thinking (anthropic-thinking-extra-body model-map norm mapped)
             :zai-thinking       {:thinking (cond-> {:type mapped}
                                              ;; `clear_thinking: false` = keep reasoning_content
                                              ;; across turns. Only meaningful on Z.ai GLM-5 / 4.7+.
                                              preserved-thinking? (assoc :clear_thinking false))}
             ;; GLM-5.2+ (DeepSeek-V4 mechanism): thinking is ON and the DEPTH
             ;; is chosen by `reasoning_effort` — but GLM only accepts "high"/
             ;; "max" (NOT OpenAI's low/medium/high; anything else falls back to
             ;; the heavy "max" default), so there is NO genuinely-light effort
             ;; rung. The only way to stop GLM-5.2 over-reasoning on a `:quick`
             ;; turn is to turn thinking OFF — verified live against z.ai's
             ;; Anthropic endpoint: glm-5.2 honors `thinking:{type "disabled"}`
             ;; (clean `text` answer, `stop_reason "end_turn"`, zero reasoning
             ;; burn), whereas a small `max_tokens` cap just truncates mid-think
             ;; and starves the answer (600-token cap → all thinking, no reply).
             ;; `:quick` → "off" disables thinking; `:balanced`/`:deep` keep it
             ;; on at high/max effort.
             :zai-effort         (if (= mapped "off")
                                   {:thinking {:type "disabled"}}
                                   {:reasoning_effort mapped
                                    :thinking (cond-> {:type "enabled"}
                                                preserved-thinking? (assoc :clear_thinking false))})
             nil)))))))

;; =============================================================================
;; Provider-scoped model availability, pricing, and context limits
;; =============================================================================

;; Pricing data last verified: 2026-04-12.
;; USD per million tokens. Values drift — re-audit quarterly.
;; Authoritative sources:
;;   Anthropic:   https://docs.claude.com/en/docs/about-claude/models/pricing
;;   OpenAI:      https://openai.com/api/pricing/
;;   Google:      https://ai.google.dev/gemini-api/docs/pricing
;;   Z.ai / GLM:  https://docs.z.ai/guides/overview/pricing
;;   MiniMax:     https://platform.minimax.io/docs/guides/pricing-paygo
(def KNOWN_PROVIDER_MODELS
  ;; `:json-object-mode?` — flagged on models that benefit from sending
  ;; OpenAI's `response_format: {type: "json_object"}` automatically. The
  ;; GLM family is known to leak prose into `content` under `:deep` reasoning
  ;; without this flag, producing bare strings like `"Looking at..."` where
  ;; svar expects a JSON object. svar auto-injects on `:openai-compatible-chat`
  ;; api-style only; callers can override via the top-level
  ;; `:json-object-mode?` opt or by setting `:response_format` directly in
  ;; `:extra-body`.
  ;; Slimmed: pricing/context flow from models.dev catalog by default
  ;; (`internal/modelsdev`); overlays here add only what catalog can't
  ;; express — OpenAI long-context tiers (`:input-over-272k`), Anthropic
  ;; 5m/1h cache tiers, GLM `:json-object-mode?`, Copilot per-model wire
  ;; overrides (`:extra-body`, `:reasoning-style`).
  {:openai
   {;; Long-context tier pricing (>272k tokens) — not in models.dev.
    "gpt-5.4"                   {:pricing {:input-over-272k 5.00 :cached-input-over-272k 0.50
                                           :output-over-272k 22.50}}
    "gpt-5.5"                   {:pricing {:input-over-272k 10.00 :cached-input-over-272k 1.00
                                           :output-over-272k 45.00}}}

   :openai-codex
   ;; Codex prompt budget = 272K input (catalog reports 400K product window
   ;; = 272K input + 128K output). Pricing flows from `:pricing-source :openai`.
   {"gpt-5"                     {:context 400000}
    "gpt-5.1"                   {:context 128000}
    "gpt-5.3-codex"             {:context 272000}
    "gpt-5.4"                   {:context 272000
                                 :pricing {:input-over-272k 5.00 :cached-input-over-272k 0.50
                                           :output-over-272k 22.50}}
    "gpt-5.4-mini"              {:context 272000}
    "gpt-5.5"                   {:context 272000
                                 :pricing {:input-over-272k 10.00 :cached-input-over-272k 1.00
                                           :output-over-272k 45.00}}}

   :anthropic
   {"claude-fable-5"            {:pricing {:input 10.00 :cached-input 1.00  :cache-write-5m 12.50 :cache-write-1h 20.00 :output 50.00} :context 1000000}
    "claude-mythos-5"           {:pricing {:input 10.00 :cached-input 1.00  :cache-write-5m 12.50 :cache-write-1h 20.00 :output 50.00} :context 1000000}
    "claude-opus-4-8"           {:pricing {:input 5.00  :cached-input 0.50  :cache-write-5m 6.25  :cache-write-1h 10.00 :output 25.00} :context 1000000}
    "claude-opus-4-7"           {:pricing {:input 5.00  :cached-input 0.50  :cache-write-5m 6.25  :cache-write-1h 10.00 :output 25.00} :context 1000000}
    "claude-opus-4-6"           {:pricing {:input 5.00  :cached-input 0.50  :cache-write-5m 6.25  :cache-write-1h 10.00 :output 25.00} :context 1000000}
    "claude-opus-4-5"           {:pricing {:input 5.00  :cached-input 0.50  :cache-write-5m 6.25  :cache-write-1h 10.00 :output 25.00} :context 200000}
    "claude-sonnet-4"           {:pricing {:input 3.00  :cached-input 0.30  :cache-write-5m 3.75  :cache-write-1h 6.00  :output 15.00} :context 200000}
    "claude-sonnet-4-6"         {:pricing {:input 3.00  :cached-input 0.30  :cache-write-5m 3.75  :cache-write-1h 6.00  :output 15.00} :context 200000}
    "claude-sonnet-4-5"         {:pricing {:input 3.00  :cached-input 0.30  :cache-write-5m 3.75  :cache-write-1h 6.00  :output 15.00} :context 200000}
    "claude-sonnet-4-20250514"  {:pricing {:input 3.00  :cached-input 0.30  :cache-write-5m 3.75  :cache-write-1h 6.00  :output 15.00} :context 200000}
    "claude-haiku-4-5"          {:pricing {:input 1.00  :cached-input 0.10  :cache-write-5m 1.25  :cache-write-1h 2.00  :output 5.00}  :context 200000}}

   :zai
   ;; Direct z.ai API — per-token billing. Pricing from z.ai dashboard
   ;; (docs.z.ai/guides/pricing). Keep in sync with :zai-coding below.
   {"glm-4.6"                   {:pricing {:input 0.60  :cached-input 0.11  :output 2.20}  :context 200000  :json-object-mode? true}
    "glm-4.6v"                  {:pricing {:input 0.30  :cached-input 0.05  :output 0.90}  :context 128000  :json-object-mode? true}
    "glm-4.7"                   {:pricing {:input 0.60  :cached-input 0.11  :output 2.20}  :context 200000  :json-object-mode? true}
    ;; `:output-limit` 32768 — a DELIBERATE agentic-responsiveness cap, not
    ;; just z.ai's hard ceiling. z.ai rejects max_tokens > 131072 with HTTP
    ;; 400, and the auto budget (context/4) is 50K–250K for these models, so
    ;; SOME overlay is mandatory to avoid 400ing every call. We cap LOWER
    ;; (32768) on purpose: GLM thinking is unbounded (`thinking.type:enabled`
    ;; has no budget — `budget_tokens` is accepted-but-ignored on the
    ;; Anthropic wire), and max_tokens caps thinking + content COMBINED. A
    ;; huge budget let GLM reason ~100K tokens and emit ZERO content; 32768
    ;; keeps coding turns bounded while leaving ample room for a real answer
    ;; (validated: merge / LRU-cache prompts produce code well within it).
    ;; Raise per-call via `:extra-body {:max_tokens N}` (≤131072) when a turn
    ;; genuinely needs a longer output.
    "glm-5.1"                   {:pricing {:input 1.40  :cached-input 0.26  :output 4.40}  :context 200000  :output-limit 32768 :json-object-mode? true}
    "glm-5.2"                   {:pricing {:input 1.40  :cached-input 0.26  :output 4.40}  :context 1000000 :output-limit 32768 :json-object-mode? true}
    "glm-5-turbo"               {:pricing {:input 1.20  :cached-input 0.24  :output 4.00}  :context 200000  :output-limit 32768 :json-object-mode? true}
    "glm-5v-turbo"              {:pricing {:input 1.20  :cached-input 0.24  :output 4.00}  :context 200000  :output-limit 32768 :json-object-mode? true}
    "minimax-m2.7:cloud"        {:pricing {:input 0.30  :output 1.20}  :context 200000}
    "gemma4:31b-cloud"          {:pricing {:input 0.30  :output 0.90}  :context 128000}
    "qwen3.5:397b-cloud"        {:pricing {:input 1.20  :output 5.00}  :context 128000}}

   ;; :zai-coding inherits :zai's overlay table via `:provider-model-source :zai`
   ;; declared on the provider — no duplicate model map.

   :github-copilot
   ;; Copilot /models reports total context for GPT reasoning models, but the
   ;; prompt/input budget is smaller because 128K output is reserved. svar's
   ;; `:context` is input budget for pre-flight checks.
   ;; Claude on Copilot routes through the NATIVE Anthropic wire
   ;; (`:api-style :anthropic` → `{base}/messages`). Probed live 2026-06-16
   ;; against api.{individual,business}.githubcopilot.com: `/v1/messages`
   ;; returns 200 with `copilot_usage` token details AND honours
   ;; `cache_control` prompt caching. `/chat/completions` exists only at the
   ;; ROOT (not under /v1) and — critically — does NOT cache the prompt
   ;; prefix, so agent loops re-read the same context every turn and
   ;; "repeat the same work over and over". svar briefly routed Claude
   ;; through `:openai-compatible-chat` (commit 33e2f0fc73) and then pushed
   ;; `reasoning_effort` (commit 30e03c1258) which spiralled agent loops
   ;; (sessions 52983a42 / 831cedee, 14 iterations). Returning to the
   ;; Anthropic wire fixes the cache regression at its root; consumers must
   ;; supply a `…/v1` base-url so `{base}/messages` resolves to
   ;; `…/v1/messages` (the vis Copilot provider does this).
   ;;
   ;; Reasoning stays server-managed: `:reasoning-style :server-managed`
   ;; resolves to nil in REASONING_LEVELS, so `reasoning-extra-body` emits
   ;; no `thinking` field at all — Claude thinks on its own (visible in the
   ;; response) and we don't push the lever that caused the spiral.
   {"claude-opus-4.8"           {:pricing {:input 0.0 :output 0.0}                  :api-style :anthropic :reasoning? true :reasoning-style :server-managed}
    "claude-opus-4.7"           {:pricing {:input 0.0 :output 0.0}                  :api-style :anthropic :reasoning? true :reasoning-style :server-managed}
    ;; Several Claude rows once carried Anthropic-native context copied
    ;; verbatim (`:context 1000000`) or stale Copilot caps (`144000`).
    ;; Both distort `auto-params` and pre-flight checks. Let refreshed
    ;; models.dev supply Copilot limits (currently 200K total / 168K
    ;; input for Opus 4.8 / 4.7 / 4.6 / Sonnet 4.6).
    "claude-opus-4.6"           {:pricing {:input 0.0 :output 0.0}                  :api-style :anthropic :reasoning? true :reasoning-style :server-managed}
    "claude-opus-4.5"           {:pricing {:input 0.0 :output 0.0} :context 160000  :api-style :anthropic :reasoning? true :reasoning-style :server-managed}
    "claude-sonnet-4"           {:pricing {:input 0.0 :output 0.0} :context 216000  :api-style :anthropic :reasoning? true :reasoning-style :server-managed}
    "claude-sonnet-4.6"         {:pricing {:input 0.0 :output 0.0}                  :api-style :anthropic :reasoning? true :reasoning-style :server-managed}
    "claude-sonnet-4.5"         {:pricing {:input 0.0 :output 0.0} :context 144000  :api-style :anthropic :reasoning? true :reasoning-style :server-managed}
    "claude-haiku-4.5"          {:pricing {:input 0.0 :output 0.0} :context 144000  :api-style :anthropic :reasoning? true :reasoning-style :server-managed}

    "gpt-5"                     {:pricing {:input 0.0 :output 0.0} :context 128000 :api-style :openai-compatible-responses :reasoning-style :openai-effort
                                 :extra-body {:store false :include ["reasoning.encrypted_content"] :reasoning {:effort "medium" :summary "detailed"}}}
    "gpt-5-mini"                {:pricing {:input 0.0 :output 0.0} :context 264000 :api-style :openai-compatible-responses :reasoning-style :openai-effort
                                 :extra-body {:store false :include ["reasoning.encrypted_content"] :reasoning {:effort "medium" :summary "detailed"}}}
    "gpt-5.1"                   {:pricing {:input 0.0 :output 0.0} :context 264000 :api-style :openai-compatible-responses :reasoning-style :openai-effort
                                 :extra-body {:store false :include ["reasoning.encrypted_content"] :reasoning {:effort "medium" :summary "detailed"}}}
    "gpt-5.1-codex"             {:pricing {:input 0.0 :output 0.0} :context 272000 :api-style :openai-compatible-responses :reasoning-style :openai-effort
                                 :extra-body {:store false :include ["reasoning.encrypted_content"] :reasoning {:effort "medium" :summary "detailed"}}}
    "gpt-5.1-codex-max"         {:pricing {:input 0.0 :output 0.0} :context 272000 :api-style :openai-compatible-responses :reasoning-style :openai-effort
                                 :extra-body {:store false :include ["reasoning.encrypted_content"] :reasoning {:effort "medium" :summary "detailed"}}}
    "gpt-5.1-codex-mini"        {:pricing {:input 0.0 :output 0.0} :context 272000 :api-style :openai-compatible-responses :reasoning-style :openai-effort
                                 :extra-body {:store false :include ["reasoning.encrypted_content"] :reasoning {:effort "medium" :summary "detailed"}}}
    "gpt-5.3-codex"             {:pricing {:input 0.0 :output 0.0} :context 272000 :api-style :openai-compatible-responses :reasoning-style :openai-effort
                                 :extra-body {:store false :include ["reasoning.encrypted_content"] :reasoning {:effort "medium" :summary "detailed"}}}
    "gpt-5.4"                   {:pricing {:input 0.0 :output 0.0} :context 272000 :api-style :openai-compatible-responses :reasoning-style :openai-effort
                                 :extra-body {:store false :include ["reasoning.encrypted_content"] :reasoning {:effort "medium" :summary "detailed"}}}
    "gpt-5.4-mini"              {:pricing {:input 0.0 :output 0.0} :context 272000 :api-style :openai-compatible-responses :reasoning-style :openai-effort
                                 :extra-body {:store false :include ["reasoning.encrypted_content"] :reasoning {:effort "medium" :summary "detailed"}}}
    "gpt-5.5"                   {:pricing {:input 0.0 :output 0.0} :context 272000 :api-style :openai-compatible-responses :reasoning-style :openai-effort
                                 :extra-body {:store false :include ["reasoning.encrypted_content"] :reasoning {:effort "medium" :summary "detailed"}}}

    "gpt-4.1"                   {:pricing {:input 0.0 :output 0.0} :context 128000}
    "gpt-4o"                    {:pricing {:input 0.0 :output 0.0} :context 128000}
    ;; Gemini + Grok on Copilot also gate reasoning server-side
    ;; (pi-ai `compat.supportsReasoningEffort: false`). The proxy
    ;; routes these to their upstream backends — Anthropic for Claude,
    ;; Google for Gemini, xAI for Grok — and the upstream APIs do not
    ;; accept a top-level `reasoning_effort`. Match Claude's policy:
    ;; flag reasoning capability but stop pushing client-side hints.
    "gemini-2.5-pro"            {:pricing {:input 0.0 :output 0.0} :context 128000 :reasoning? true :reasoning-style :server-managed}
    "gemini-3-flash-preview"    {:pricing {:input 0.0 :output 0.0} :context 128000 :reasoning? true :reasoning-style :server-managed}
    "gemini-3-pro-preview"      {:pricing {:input 0.0 :output 0.0} :context 128000 :reasoning? true :reasoning-style :server-managed}
    "gemini-3.1-pro-preview"    {:pricing {:input 0.0 :output 0.0} :context 128000 :reasoning? true :reasoning-style :server-managed}
    "grok-code-fast-1"          {:pricing {:input 0.0 :output 0.0} :context 128000 :reasoning? true :reasoning-style :server-managed}}

   :openrouter
   {"gpt-4o"                    {:pricing {:input 2.50  :cached-input 1.25  :output 10.00} :context 128000}
    "claude-sonnet-4-6"         {:pricing {:input 3.00  :cached-input 0.30  :cache-write-5m 3.75  :cache-write-1h 6.00  :output 15.00} :context 200000}
    "gemini-2.0-flash"          {:pricing {:input 0.10  :cached-input 0.025 :output 0.40}  :context 1000000}
    ;; Public Google Gemini retail pricing (long-context tiered) — indexed under
    ;; openrouter since it's the multi-provider gateway in svar's defaults;
    ;; `tokens/estimate-cost` flattens by model name across providers.
    "gemini-2.5-pro"            {:pricing {:input 1.25 :cached-input 0.125 :output 10.00
                                           :input-over-200k 2.50 :cached-input-over-200k 0.25
                                           :output-over-200k 15.00}
                                 :context 2000000}}

   :ollama
   {}

   ;; :lmstudio — user-supplied local models; pricing/context come from
   ;; the caller's `:models` config (no built-in catalog).
   :lmstudio {}})

;; =============================================================================
;; Derived compatibility maps
;; =============================================================================

(defn- known-provider
  "Resolve a runtime provider id to its `KNOWN_PROVIDERS` config,
   following the `:provider-model-source` redirect so plan-tier
   providers (`:github-copilot-individual` / `-business` / `-enterprise`
   of `:github-copilot`; `:anthropic-coding-plan` of `:anthropic`;
   `:zai-coding-plan` / `:zai-coding` of `:zai` for model catalog) all
   pick up `:exclude-models`, `:min-gpt-version`, `:llm-headers`,
   `:rpm`, `:tpm`, etc. from the shared base entry instead of having
   the tier alias re-state them.

   Merge order: the source entry sits UNDER the alias entry so an
   alias may override a single field (e.g. unique `:base-url` per
   Copilot tier) without dropping the rest of the shared policy.

   Without this resolver, alias entries that only carry a
   `:provider-model-source` pointer would silently lose every
   `(get KNOWN_PROVIDERS pid)` lookup result outside the explicit
   `provider-model-source` / `provider-pricing-source` accessors
   below — the exact failure mode behind session 52983a42's looping
   claude-sonnet-4.6 turn: tier registration found no `:context`, fell
   back to the 8192 default, and capped `max_tokens` at 2048.

   Returns nil when `provider-id` is unknown so callers can still
   distinguish 'unknown' from 'known with empty policy'."
  [provider-id]
  (when-let [direct (get KNOWN_PROVIDERS provider-id)]
    (if-let [source (:provider-model-source direct)]
      (merge (get KNOWN_PROVIDERS source) direct)
      direct)))

(defn- pid-visible? [pid model-name]
  (letfn [(parse-gpt-version [model-name]
            (when-let [[_ major minor] (re-find #"(?i)^gpt-(\d+)(?:\.(\d+))?" (str model-name))]
              [(Long/parseLong major) (Long/parseLong (or minor "0"))]))
          (version< [a b] (neg? (compare (vec a) (vec b))))]
    (let [known (known-provider pid)
          excluded (set (:exclude-models known))
          version (parse-gpt-version model-name)
          min-version (:min-gpt-version known)]
      (not (or (contains? excluded model-name)
             (and version min-version (version< version min-version)))))))

(defn- merged-provider-models
  "Catalog ⊕ overlay model map for one svar provider id (visible entries only).
   Same merge rules as `provider-model-entry` but enumerated."
  [pid]
  (let [overlay (get KNOWN_PROVIDER_MODELS
                  (or (get-in KNOWN_PROVIDERS [pid :provider-model-source]) pid))
        catalog (modelsdev/provider-models
                  (or (get-in KNOWN_PROVIDERS [pid :pricing-source])
                    (get-in KNOWN_PROVIDERS [pid :provider-model-source])
                    pid))]
    (reduce
      (fn [acc nm]
        (if-not (pid-visible? pid nm)
          acc
          (let [c (get catalog nm)
                o (get overlay nm)
                merged (cond-> (merge c o)
                         (and (:pricing c) (:pricing o))
                         (assoc :pricing (merge (:pricing c) (:pricing o))))]
            (assoc acc nm merged))))
      {}
      (into (set (keys catalog)) (keys overlay)))))

(def ^:const DEFAULT_CONTEXT_LIMIT
  "Fallback context window (tokens) for a model svar knows nothing about:
   absent from models.dev, absent from any provider overlay, and carrying no
   caller-supplied `:context`. Deliberately conservative — better to
   under-promise the window than advertise tokens the backend will silently
   truncate. Local providers (LM Studio / Ollama) should override this with a
   detected `:context` (see `models!`); this is only the true-unknown floor."
  8192)

(def MODEL_CONTEXT_LIMITS
  "Best-effort flattened model context limits for legacy token utilities.
    When a model exists on multiple providers with different contexts, the most
    conservative context is used. Provider-aware code should use
    provider-model-context instead."
  (assoc
    (reduce
      (fn [acc pid]
        (reduce-kv (fn [macc model-name {:keys [context]}]
                     (if (nil? context)
                       macc
                       (update macc model-name
                         (fn [existing]
                           (if (nil? existing)
                             (long context)
                             (min (long existing) (long context)))))))
          acc (merged-provider-models pid)))
      {} (keys KNOWN_PROVIDERS))
    :default DEFAULT_CONTEXT_LIMIT))

(def MODEL_PRICING
  "Best-effort flattened model pricing for legacy token utilities.
    When a model exists on multiple providers, the lowest total pricing is chosen.
    Provider-aware code should NOT use this — use provider-model-pricing instead."
  (assoc
    (reduce
      (fn [acc pid]
        (reduce-kv (fn [macc model-name {:keys [pricing]}]
                     (if-not pricing
                       macc
                       (let [pricing-total (+ (double (:input pricing 0.0))
                                             (double (:output pricing 0.0)))]
                         ;; Subscription/local providers advertise 0/0 for
                         ;; routing; legacy cost utilities prefer paid rates.
                         (if (zero? pricing-total)
                           macc
                           (update macc model-name
                             (fn [existing]
                               (if (or (nil? existing)
                                     (< pricing-total
                                       (+ (double (:input existing))
                                         (double (:output existing)))))
                                 pricing
                                 existing)))))))
          acc (merged-provider-models pid)))
      {} (keys KNOWN_PROVIDERS))
    :default {:input 5.0 :output 15.0}))

;; =============================================================================
;; Configuration Defaults
;; =============================================================================

(def DEFAULT_TIMEOUT_MS
  "Default HTTP request timeout in milliseconds (5 minutes).
   Reasoning models (e.g. glm-5-turbo) may need extended time for chain-of-thought."
  300000)

(def DEFAULT_TTFT_TIMEOUT_MS
  "Default time-to-first-token timeout (ms) for streaming HTTP responses.
   Bounds the wait between sending the HTTP request and receiving response
   headers. On fire, raises `:svar.core/stream-ttft-timeout` and the caller
   thread's interrupt unparks the underlying `CompletableFuture.get`.

   30 s default. Tight enough to surface stuck provider connections
   inside one iteration (the original 90 s default sometimes wasted a
   whole autoresearch iter waiting for headers), generous enough for
   real reasoning cold starts — z.ai glm-5.1 has been observed sending
   first headers between 8 and 22 s. Disable per-call with
   `:ttft-timeout-ms nil`; pass a larger value for slow reasoning
   models with long pre-stream queues."
  30000)

(def DEFAULT_IDLE_TIMEOUT_MS
  "Default idle-stream timeout (ms) for streaming HTTP responses. If no
   SSE bytes arrive for this long the underlying `InputStream` is closed
   and the call surfaces `:svar.core/stream-idle-timeout`. Distinct from
   `DEFAULT_TIMEOUT_MS` (whole-request cap): the idle watchdog tolerates
   arbitrarily long total durations as long as the stream keeps emitting
   bytes (content deltas, SSE `: ping` keepalives, or even blank
   separators — the watchdog resets on every `.readLine`, so it's
   ping-aware for free).

   2 minutes (120000 ms) is the considered sweet spot:
     - Matches Anthropic's own SDK proposal (anthropics/anthropic-sdk-
       typescript#867 suggests `120_000` per-request, #959 ships 90s
       default with ping-reset).
     - ~4× Anthropic's published `ping` interval (15-30 s) for safety.
     - Catches real hangs (e.g. z.ai glm streams that simply stop
       sending body frames after headers) in 2 minutes instead of
       forever — the original 5-minute `DEFAULT_TIMEOUT_MS` doesn't
       reliably fire on JDK 25 + HTTP/2 streaming.
     - Anthropic's documented worst case for legitimate extended
       thinking on Opus 4.5 is ~185 s with zero events (see
       anthropics/claude-agent-sdk-typescript#44). Callers running
       extended-thinking workloads should bump this to 240-300 s, or
       pass `:idle-timeout-ms nil` to disable.

   Disable per-call: `(svar/ask-code! router {... :idle-timeout-ms nil})`.

   45 s default. Catches genuinely hung streams (no SSE bytes, no
   keepalive pings) in well under a minute while still allowing the
   provider to take up to ~40 s between tokens during extended
   reasoning. The original 120 s default let timeouts blow the whole
   per-task budget."
  45000)

(def DEFAULT_SEMANTIC_TIMEOUT_MS
  "Default semantic-stream timeout (ms) for streaming HTTP responses.
   Nil by default: disabled unless caller opts in. If enabled and bytes
   keep arriving (SSE pings/comments) but no model/progress event arrives
   for this long, the stream is closed and surfaced as
   `:svar.core/stream-semantic-timeout`.

   Distinct from `DEFAULT_IDLE_TIMEOUT_MS`: idle watches transport
   liveness; semantic watches model progress. Enable per-call with e.g.
   `:semantic-timeout-ms 180000`."
  nil)

(def DEFAULT_RETRY
  "Default retry policy for transient HTTP errors."
  {:max-retries 5
   :initial-delay-ms 1000
   :max-delay-ms 60000
   :multiplier 2.0})

(def DEFAULT_RATE_LIMIT_ROUTING
  "Default router-owned 429 policy.

   `:same-provider-delays-ms` — sleep schedule for same-provider retries.
   `:fallback-after-ms`       — hard cap on wall time the 429 phase can
                                consume (measured from the FIRST 429
                                caught). When the configured delay vector
                                is exhausted OR elapsed ≥ budget, the
                                router falls back to the next provider.
                                Each delay clamps to remaining budget so
                                the loop never overshoots.
   `:respect-retry-after?`    — honor server `Retry-After` header value
                                in place of the configured delay; the
                                clamp to remaining budget still applies.
   `:fallback-provider?`      — when budget is exhausted, fall back to
                                the next provider/model. Set false to
                                surface the rate-limit error to the
                                caller instead.

   The 60 000 ms (60 s) default tolerates Anthropic / OpenAI / z.ai
   Retry-After values that can land between 30-60 s under quota
   pressure on reasoning-heavy workloads, while still bounding the
   wait so a single user request cannot hang for minutes."
  {:same-provider-delays-ms [2000 3000 6000]
   :fallback-after-ms 60000
   :respect-retry-after? true
   :fallback-provider? true})

(def DEFAULT_OUTPUT_RESERVE
  "Default number of tokens to reserve for model output.
   0 means no reservation — let the API handle overflow naturally."
  0)

;; =============================================================================
;; Model metadata lookup + fallback inference
;; =============================================================================

(defn- regex-infer-metadata [model-name]
  (let [m (str/lower-case (or model-name ""))]
    (cond
      (re-find #"mini|haiku|flash|lite|small|nano" m)
      {:intelligence :medium :speed :fast
       :capabilities (cond-> #{:chat}
                       (re-find #"vision|claude|gemini|gpt-4o|glm.*v|pixtral" m) (conj :vision))}

      (re-find #"reasoner|thinking" m)
      {:intelligence :frontier :speed :slow :capabilities #{:chat}}

      (re-find #"opus|frontier" m)
      {:intelligence :frontier :speed :slow
       :capabilities (cond-> #{:chat} (re-find #"claude" m) (conj :vision))}

      (re-find #"sonnet|gpt-4o|gpt-5|pro|large|gemini-2\.[0-9]-pro|gemini-2\.5" m)
      {:intelligence :high :speed :medium
       :capabilities (cond-> #{:chat}
                       (re-find #"vision|claude|gemini|gpt-4o|gpt-5|pixtral|glm.*v" m) (conj :vision))}

      :else
      {:intelligence :medium :speed :medium
       :capabilities (cond-> #{:chat}
                       (re-find #"vision|glm.*v|pixtral" m) (conj :vision))})))

(defn model-key-variants
  "Catalog/overlay lookup keys for a model name, tolerant of the dotted vs
   dashed version separator. vis's canonical id and `KNOWN_MODEL_METADATA` are
   DASHED (`claude-opus-4-8`); the Copilot overlay in `KNOWN_PROVIDER_MODELS`
   is DOTTED (`claude-opus-4.8`). Without this, a dashed name reaching the
   Copilot provider misses the overlay → `:api-style` falls back to
   `:openai-compatible-chat` (the /chat/completions wire, no prompt cache) and
   Claude-on-Copilot 404s / loses caching. We try the name AS-IS first (so
   `gpt-5.4` / `glm-5.2` hit their dotted keys directly), then the dot↔dash
   variants in the numeric version tail."
  [name]
  (let [s (str name)]
    (distinct
      [s
       (str/replace s #"(\d)\.(\d)" "$1-$2")   ; dotted → dashed (4.8 → 4-8)
       (str/replace s #"(\d)-(\d)" "$1.$2")]))) ; dashed → dotted (4-8 → 4.8)

(defn lookup-by-model-variants
  "First value from map `m` matching any `model-key-variants` of `name`, or nil."
  [m name]
  (some #(get m %) (model-key-variants name)))

(defn infer-model-metadata
  "Returns provider-independent model metadata.
    Looks up KNOWN_MODEL_METADATA first (tolerant of dotted/dashed version
    separators). Falls back to regex inference for unknown models.
    Explicit fields in model-map override inferred values."
  [{:keys [name] :as model-map}]
  (let [base (or (lookup-by-model-variants KNOWN_MODEL_METADATA name)
               (regex-infer-metadata name))
        inferred (assoc base :name name)]
    (merge inferred (dissoc model-map :name))))

(defn normalize-model
  "Normalizes a model entry: {:name \"gpt-4o\"} -> full provider-independent model metadata."
  [model-map]
  (when (and (:name model-map) (not (str/blank? (str (:name model-map)))))
    (infer-model-metadata model-map)))

(defn- configured-model-inputs
  [known provider-map]
  (let [configured (:models provider-map)
        defaults (:default-models known)]
    (cond
      (:prepend-default-models? known) (concat defaults configured)
      (seq configured) configured
      :else defaults)))

(defn- conj-model-once
  [models model]
  (if (some #(= (:name %) (:name model)) models)
    models
    (conj models model)))

(defn- parse-gpt-version
  "Extract comparable GPT version [major minor] from ids such as
   gpt-4o, gpt-5, gpt-5.3-codex, or gpt-5.4-mini. Non-GPT ids return nil."
  [model-name]
  (when-let [[_ major minor] (re-find #"(?i)^gpt-(\d+)(?:\.(\d+))?" (str model-name))]
    [(Long/parseLong major) (Long/parseLong (or minor "0"))]))

(defn- version< [a b]
  (neg? (compare (vec a) (vec b))))

(defn provider-excluded-model?
  "True when a provider-scoped catalog marks a model unavailable.
   Provider config may add `:exclude-models` as exact model names and/or
   `:min-gpt-version` such as [5 3] to hide older GPT family models.
   Uses `known-provider` so plan-tier aliases inherit exclusion lists
   from their base entry (e.g. all three Copilot tiers honour the same
   `:exclude-models #{gpt-4o ...}` defined on `:github-copilot`)."
  [provider-id model-name]
  (let [known (known-provider provider-id)
        excluded (set (:exclude-models known))
        version (parse-gpt-version model-name)
        min-version (:min-gpt-version known)]
    (or (contains? excluded model-name)
      (and version min-version (version< version min-version)))))

(defn provider-model-visible?
  "True when provider-scoped model filters allow `model-name`."
  [provider-id model-name]
  (not (provider-excluded-model? provider-id model-name)))

(defn- provider-model-source
  "Catalog id used to look up `KNOWN_PROVIDER_MODELS` (the svar wire/policy
   overlay) for a given runtime provider id. Honours
   `:provider-model-source` so plan tiers inherit their model overlay
   from the base provider — keeps the resolver one-liner intentional
   (we don't want `known-provider`'s recursive merge here; the
   models.dev lookup wants the bare catalog key)."
  [provider-id]
  (or (get-in KNOWN_PROVIDERS [provider-id :provider-model-source]) provider-id))

(defn- provider-pricing-source
  "Catalog provider-id used to resolve pricing/context/modalities from
   models.dev. Honors `:pricing-source` overlay (e.g. `:openai-codex` →
   `:openai` for retail metering, `:zai-coding` → `:zai-coding-plan` for
   id-mapping). Defaults to `provider-model-source`."
  [provider-id]
  (or (get-in KNOWN_PROVIDERS [provider-id :pricing-source])
    (provider-model-source provider-id)))

(defn provider-model-entry
  "Returns provider-scoped entry for a provider/model, or nil if excluded.

   Composition:
     1. Catalog entry from models.dev (pricing, context, modalities,
        capability flags, family, knowledge cutoff, release dates) —
        looked up under `:pricing-source` if declared, else `:id`.
     2. svar overlay from KNOWN_PROVIDER_MODELS (wire/policy keys:
        `:api-style`, `:reasoning-style`, `:json-object-mode?`,
        `:extra-body`, plus any pricing/context overrides).

   Overlay wins on conflicts. Pricing maps deep-merge so an overlay
   can override a single rate without dropping `:cache-read` /
   `:cache-write` from the catalog."
  [provider-id model-name]
  (when-not (provider-excluded-model? provider-id model-name)
    (let [overlay (lookup-by-model-variants
                    (get KNOWN_PROVIDER_MODELS (provider-model-source provider-id))
                    model-name)
          catalog (lookup-by-model-variants
                    (modelsdev/provider-models (provider-pricing-source provider-id))
                    model-name)]
      (when (or overlay catalog)
        (cond-> (merge catalog overlay)
          (and (:pricing catalog) (:pricing overlay))
          (assoc :pricing (merge (:pricing catalog) (:pricing overlay))))))))

(defn provider-model-pricing
  "Returns provider-scoped pricing for provider/model, falling back to flattened MODEL_PRICING."
  [provider-id model-name]
  (or (:pricing (provider-model-entry provider-id model-name))
    (get MODEL_PRICING model-name)
    (:default MODEL_PRICING)))

(defn provider-model-context
  "Returns provider-scoped context window for provider/model, falling back to flattened MODEL_CONTEXT_LIMITS."
  [provider-id model-name]
  (or (:context (provider-model-entry provider-id model-name))
    (get MODEL_CONTEXT_LIMITS model-name)
    (:default MODEL_CONTEXT_LIMITS)))

(defn provider-base-url
  "Sane default base-url svar knows for `provider-id` (honours plan-tier
   `:provider-model-source` inheritance). Vis provider extensions use this as
   their `:base-url` preset and override it only for local/custom endpoints
   (e.g. ollama, lmstudio). Returns nil for unknown providers."
  [provider-id]
  (or (get-in KNOWN_PROVIDERS [provider-id :base-url])
    (get-in KNOWN_PROVIDERS [(provider-model-source provider-id) :base-url])))

(defn provider-default-models
  "Sane default model NAMES (vec of strings) svar curates for `provider-id`,
   honouring plan-tier `:provider-model-source` inheritance. This is the SINGLE
   source of truth: vis provider extensions use it as their `:default-models`
   preset and override only when they want a different curated set. Empty vec
   for unknown providers."
  [provider-id]
  (->> (or (get-in KNOWN_PROVIDERS [provider-id :default-models])
         (get-in KNOWN_PROVIDERS [(provider-model-source provider-id) :default-models]))
    (keep (fn [m] (cond (string? m) m (map? m) (:name m))))
    vec))

;; =============================================================================
;; Provider normalization
;; =============================================================================

(defn normalize-provider
  "Normalizes a provider entry:
    - resolves :base-url from KNOWN_PROVIDERS if not provided
    - derives :priority from vector index
    - derives :root from first model
    - merges provider-independent model metadata with provider-scoped pricing/context

   Uses `known-provider` for the policy lookup so plan-tier aliases
   inherit `:exclude-models`, `:llm-headers`, `:rpm`/`:tpm`, default
   models, and any other shared field from their base entry; only the
   tier-local overrides (`:base-url`, ...) win on conflict."
  [idx provider-map]
  (let [id (:id provider-map)
        known (known-provider id)
        base-url (or (:base-url provider-map) (:base-url known))
        rpm (or (:rpm provider-map) (:rpm known) 500)
        tpm (or (:tpm provider-map) (:tpm known) 2000000)
        exclude-models (set (concat (:exclude-models known) (:exclude-models provider-map)))
        models (->> (configured-model-inputs known provider-map)
                 (keep (fn [m]
                         (when-let [normalized (normalize-model m)]
                           (when-not (contains? exclude-models (:name normalized))
                             (merge normalized
                               (provider-model-entry id (:name normalized)))))))
                 (reduce conj-model-once []))
        root-name (:name (first models))]
    (when-not id
      (throw (ex-info "Provider :id is required" {:provider provider-map})))
    (when-not base-url
      (throw (ex-info (str "Provider :base-url required for unknown provider " id
                        ". Known providers: " (str/join ", " (map name (keys KNOWN_PROVIDERS))))
               {:type :svar/unknown-provider :id id})))
    (when (empty? models)
      (throw (ex-info (str "Provider " id " has no models") {:type :svar/no-models :id id})))
    (cond-> {:id id
             :api-key (:api-key provider-map)
             :base-url base-url
             :api-style (or (:api-style provider-map) (:api-style known) :openai-compatible-chat)
             :priority idx
             :rpm rpm
             :tpm tpm
             :root root-name
             :models models}
      (some? (or (:responses-path provider-map) (:responses-path known)))
      (assoc :responses-path (or (:responses-path provider-map) (:responses-path known)))

      (some? (or (:llm-headers provider-map) (:llm-headers known)))
      (assoc :llm-headers (merge (:llm-headers known) (:llm-headers provider-map)))

      (some? (or (:extra-body provider-map) (:extra-body known)))
      (assoc :extra-body (merge (:extra-body known) (:extra-body provider-map))))))

;; =============================================================================
;; Context limit lookup
;; =============================================================================

(defn context-limit
  "Returns provider-agnostic conservative context window size for a model.

   Params:
   `model` - String. Model name.
   `context-limits` - Map, optional. Override map (merged defaults from config).

   Returns:
   Integer. Conservative context tokens."
  (^long [^String model]
   (context-limit model MODEL_CONTEXT_LIMITS))
  (^long [^String model context-limits]
   (or (get context-limits model)
       ;; Try partial matching for versioned model names
     (some (fn [[k v]]
             (when (and (string? k) (str/includes? model k))
               v))
       context-limits)
     (:default context-limits))))

;; =============================================================================
;; Router internals — time / rate windows
;; =============================================================================

(def ^:private router-default-opts
  {:window-ms              60000
   :cooldown-ms            60000
   :max-wait-ms            30000
   :transient-status-codes #{429 500 502 503 504 529}
   :rate-limit DEFAULT_RATE_LIMIT_ROUTING
   ;; Circuit breaker defaults
   :failure-threshold   5
   :recovery-ms         60000})

(def ^:private INTELLIGENCE_ORDER
  {:frontier 4 :high 3 :medium 2 :low 1})

;; NOTE: there is intentionally no COST_ORDER — the `:cost` strategy
;; dispatches on the real `:pricing {:input N :output M}` attached to
;; every normalized model (and falls back to a `:cost :low|:medium|:high`
;; tag for legacy/synthetic fixtures). A keyword-rank table would
;; quietly prefer a `:medium` $5/1M model over a `:high` $0.50/1M
;; model, which is the opposite of what the strategy promises.

(def ^:private SPEED_ORDER
  {:fast 3 :medium 2 :slow 1})

(defn- router-now-ms ^long [router] ((:clock router)))

(defn- router-prune-window
  [router entries]
  (let [cutoff (- (router-now-ms router) (long (:window-ms router)))]
    (filterv (fn [e]
               (let [^long ts (if (map? e) (:ts e) e)]
                 (> ts cutoff)))
      entries)))

(defn- rpm-count ^long [router ps]
  (count (router-prune-window router (:requests ps []))))

(defn- tpm-count ^long [router ps]
  (long (reduce + 0 (map :n (router-prune-window router (:tokens ps []))))))

;; =============================================================================
;; Circuit Breaker — three-state: :closed → :open → :half-open → :closed
;; =============================================================================

(defn- cb-state
  "Returns the effective circuit breaker state for a provider, accounting for
   time-based open→half-open transitions."
  [router ps]
  (let [state (or (:cb-state ps) :closed)]
    (if (and (= state :open)
          (:cb-open-until ps)
          (>= (router-now-ms router) (long (:cb-open-until ps))))
      :half-open
      state)))

(defn- cb-available?
  "Returns true if the circuit breaker allows a request."
  [router ps]
  (let [state (cb-state router ps)]
    (or (= state :closed)
      (= state :half-open))))

(defn- cb-record-failure!
  "Records a failure. Transitions closed→open after threshold, half-open→open immediately."
  [router provider-id is-rate-limit?]
  (let [now (router-now-ms router)
        recovery-ms (long (if is-rate-limit?
                            (:cooldown-ms router)
                            (:recovery-ms router)))
        threshold (long (:failure-threshold router))]
    (swap! (:state router) update provider-id
      (fn [ps]
        (let [current-state (cb-state router ps)
              new-failures (unchecked-inc (long (or (:cb-failures ps) 0)))]
          (if (or (= current-state :half-open)
                (>= new-failures threshold))
            (do (trove/log! {:level :warn
                             :data {:provider provider-id :cb-state :open
                                    :recovery-ms recovery-ms :failures new-failures
                                    :trigger (if is-rate-limit? :rate-limit :transient-error)}
                             :msg "Circuit breaker opened"})
              (assoc ps
                :cb-state :open
                :cb-failures new-failures
                :cb-open-until (+ now recovery-ms)))
            (assoc ps :cb-failures new-failures)))))))

(defn- cb-record-success!
  "Records a success. Transitions half-open→closed, resets failure count."
  [router provider-id]
  (swap! (:state router) update provider-id
    (fn [ps]
      (let [current-state (cb-state router ps)]
        (if (= current-state :half-open)
          (do (trove/log! {:level :info :data {:provider provider-id}
                           :msg "Circuit breaker closed (probe succeeded)"})
            (assoc ps :cb-state :closed :cb-failures 0 :cb-open-until nil))
          ;; In closed state, reset consecutive failures on success
          (assoc ps :cb-failures 0))))))

;; =============================================================================
;; Budget — pre-flight rejection when cumulative spend exceeds limits
;; =============================================================================

(defn- budget-check!
  "Throws if the router's token budget is exhausted. Called before each request."
  [router]
  (when-let [budget (:budget router)]
    (let [{:keys [total-tokens total-cost]} @(:budget-state router)
          max-tokens (:max-tokens budget)
          max-cost (:max-cost budget)]
      (when (and max-tokens (>= (long total-tokens) (long max-tokens)))
        (throw (ex-info "Token budget exhausted"
                 {:type :svar/budget-exhausted
                  :budget budget
                  :spent {:tokens total-tokens :cost total-cost}})))
      (when (and max-cost (>= (double total-cost) (double max-cost)))
        (throw (ex-info "Cost budget exhausted"
                 {:type :svar/budget-exhausted
                  :budget budget
                  :spent {:tokens total-tokens :cost total-cost}}))))))

(declare estimate-cost)

(defn- budget-record!
  "Records token usage and cost against the router's budget.

   Reads the Phase A canonical usage shape —
   `{:input-tokens N :output-tokens N :input-tokens-details {...}
   :total-tokens N}` — where `:input-tokens` is ALWAYS TOTAL (inclusive
   across providers). No provider branching needed any more."
  [router _provider-id model-name api-usage]
  (when (:budget-state router)
    (let [input-tokens  (long (or (:input-tokens api-usage) 0))
          output-tokens (long (or (:output-tokens api-usage) 0))
          total-tokens  (long (or (:total-tokens api-usage)
                                (+ input-tokens output-tokens)))
          pricing-map   {model-name (provider-model-pricing _provider-id model-name)}
          cost          (estimate-cost model-name input-tokens output-tokens
                          pricing-map
                          {:api-usage api-usage})]
      (swap! (:budget-state router)
        (fn [bs]
          (-> bs
            (update :total-tokens + total-tokens)
            (update :total-cost + (:total-cost cost))))))))

;; =============================================================================
;; Cumulative stats recording
;; =============================================================================

(defn- record-cumulative!
  "Records cumulative stats for a provider after a successful request."
  [router provider-id token-count latency-ms]
  (swap! (:state router) update provider-id
    (fn [ps]
      (-> ps
        (update-in [:cum :requests] (fnil inc 0))
        (update-in [:cum :total-tokens] (fnil + 0) (or token-count 0))
        (update-in [:cum :latencies] (fnil conj []) latency-ms)))))

;; =============================================================================
;; Provider availability + model selection
;; =============================================================================

(defn- provider-available? [router provider ps]
  (and (cb-available? router ps)
    (< (rpm-count router ps) (long (:rpm provider Long/MAX_VALUE)))
    (< (tpm-count router ps) (long (:tpm provider Long/MAX_VALUE)))))

(defn- preference-sort-key
  "Returns a sort key fn for a single preference keyword.
   Lower values = better (for sort-by ascending).

   `:cost` dispatches on what's actually available on the model map:
     1. `:pricing {:input N :output M}` (USD per 1M tokens, attached to every
        known model by `normalize-provider`) → sorted by `(+ input output)`.
        The real-pricing path — this is what production decisions use.
     2. legacy/test-only `:cost :low|:medium|:high` tag → scaled to match so
        existing synthetic test fixtures still work (low=0.1, medium=1, high=10).
     3. neither → `Double/POSITIVE_INFINITY` so unpriced models sort last and
        the router prefers any priced model over an unknown one."
  [pref]
  (case pref
    :cost         (fn [m]
                    (let [p (:pricing m)
                          tag (:cost m)]
                      (cond
                        p   (+ (double (or (:input p) 0.0))
                              (double (or (:output p) 0.0)))
                        tag (case tag :low 0.1 :medium 1.0 :high 10.0 0.0)
                        :else Double/POSITIVE_INFINITY)))
    :intelligence (fn [m] (- (long (get INTELLIGENCE_ORDER (:intelligence m) 0))))
    :speed        (fn [m] (- (long (get SPEED_ORDER (:speed m) 0))))
    nil))

(defn- force-provider-filter
  "Restricts a provider seq to `:force-provider` when set (explicit provider
   pin from `{:routing {:provider ...}}`). Without this, a pinned provider is
   silently ignored and selection can route to any other provider that happens
   to expose the same model name. Returns `providers` unchanged when no pin."
  [prefs providers]
  (if-let [fp (:force-provider prefs)]
    (filter #(= (:id %) fp) providers)
    providers))

(defn- resolve-model
  "Returns the best model map for a provider given preferences, or nil.
   :prefer can be a keyword (:cost, :intelligence, :speed) or a vector of keywords
   for multi-criteria sorting, e.g. [:cost :speed] = cheapest first, then fastest.

   Precedence:
     1. :force-model  — exact model name, honored across all strategies.
     2. :strategy :root with no force — provider's root (first) model.
     3. :prefer / :capabilities / :require-reasoning? — filtered & sorted selection.

   Filters applied (in order):
     - `:capabilities` — model's `:capabilities` set must be a superset.
     - `:require-reasoning?` — only `:reasoning? true` models survive. Set
        automatically by `resolve-routing` when caller supplies a `:reasoning`
        level. Ensures `{:routing {:optimize :cost} :reasoning :deep}` picks
        the cheapest *reasoning-capable* model, not silently drops the depth.
     - `:exclude-model` — skip a named model (used for post-failure retry).
     - `:exclude-models` — set of model NAMES to skip. Honored by EVERY
        branch (root / force / prefer) so a model the endpoint rejected as
        unsupported is never re-selected — otherwise `:strategy :root` would
        keep handing back the same dead root and spin `with-provider-fallback`
        forever."
  [provider prefs]
  (let [require-reasoning? (:require-reasoning? prefs)
        reasoning-ok? (fn [m] (if require-reasoning? (:reasoning? m) true))
        exclude-set (or (:exclude-models prefs) #{})
        not-excluded? (fn [m] (and m (not (contains? exclude-set (:name m)))))]
    (cond
      ;; Explicit force-model wins regardless of strategy AND regardless of
      ;; `:require-reasoning?`. The caller named this model, so reasoning is a
      ;; best-effort hint: applied iff the model is `:reasoning?` (otherwise
      ;; `reasoning-extra-body` no-ops), never a reason to refuse routing. The
      ;; reasoning FILTER still applies to `:prefer`/optimize below, where svar
      ;; — not the caller — chooses among models.
      (:force-model prefs)
      (let [m (first (filter #(= (:name %) (:force-model prefs)) (:models provider)))]
        (when (not-excluded? m) m))

      ;; Root model: there's nothing to choose between, so honor it regardless
      ;; of `:require-reasoning?` (same best-effort reasoning semantics as
      ;; force-model). This is what lets a single local provider (e.g. LM Studio)
      ;; serve `:reasoning`-flagged turns instead of "all providers exhausted".
      (= (:strategy prefs) :root)
      (let [root-name (:root provider)
            m (first (filter #(= (:name %) root-name) (:models provider)))]
        (when (not-excluded? m) m))

      :else
      (let [required-caps (or (:capabilities prefs) #{})
            exclude (:exclude-model prefs)
            candidates (->> (:models provider)
                         (filter #(every? (:capabilities %) required-caps))
                         (filter reasoning-ok?)
                         (filter #(if exclude (not= (:name %) exclude) true))
                         (remove #(contains? exclude-set (:name %))))]
        (when (seq candidates)
          (let [prefer (:prefer prefs)
                prefs-vec (cond
                            (vector? prefer) prefer
                            (keyword? prefer) [prefer]
                            :else nil)]
            (if (seq prefs-vec)
              (let [key-fns (keep preference-sort-key prefs-vec)]
                (first (sort-by (fn [m] (mapv #(% m) key-fns)) candidates)))
              (first candidates))))))))

(defn- candidate-sort-key
  "Composite sort key for cross-provider candidate selection.

   Default: `[model-score provider-priority]` — model-score (from the `:prefer`
   keys) dominates, so `(:optimize :intelligence)` picks the frontier-tier model
   across the whole fleet before provider priority breaks ties.

   With `:provider-order` (an explicit ordered vector of provider ids, set by
   `{:routing {:prefer-providers [...]}}`): `[provider-rank model-score
   provider-priority]` — the declared PROVIDER order dominates and model-score
   only breaks ties WITHIN a provider, so each plan still yields its optimized
   model. Providers absent from the list sort last. Combined with
   `with-provider-fallback`, this walks the preference chain natively: a failed
   provider/model is excluded and the next-ranked candidate is selected.

   Returns `[[] priority]` when no `:prefer` is set — priority-only ordering,
   which preserves the historical `:strategy :root` / force-model semantics."
  [prefs]
  (let [prefer (:prefer prefs)
        prefs-vec (cond (vector? prefer) prefer
                    (keyword? prefer) [prefer]
                    :else nil)
        key-fns (keep preference-sort-key prefs-vec)
        model-score (fn [m] (if (seq key-fns) (mapv #(% m) key-fns) []))
        order (:provider-order prefs)
        order-rank (when (seq order)
                     (let [idx (zipmap order (range))
                           miss (count order)]
                       (fn [p] (long (get idx (:id p) miss)))))]
    (if order-rank
      (fn [[p m]] [(order-rank p) (model-score m) (:priority p 0)])
      (fn [[p m]] [(model-score m) (:priority p 0)]))))

(defn select-provider
  "Returns [provider model-map] or nil. Read-only.

   Cross-provider ranking: models are scored by `:prefer` first, provider
   priority second. So `:optimize :intelligence` picks the frontier model
   across the whole fleet; ties are broken by provider vector order."
  [router prefs]
  (let [{:keys [providers state]} router
        current-state @state
        candidates (->> (force-provider-filter prefs providers)
                     (keep (fn [p] (when-let [m (resolve-model p prefs)] [p m])))
                     (filter (fn [[p _]] (provider-available? router p (get current-state (:id p) {})))))]
    (when (seq candidates)
      (first (sort-by (candidate-sort-key prefs) candidates)))))

(defn- select-and-claim!
  "Atomically selects best provider and claims a request slot.
   Uses the same cross-provider composite sort as `select-provider`.

   Honors `:exclude-providers` (a set of provider IDs) in `prefs`. Used by
   `with-provider-fallback` to route around providers that have already
   failed format-parse contracts in the current call — since a format
   failure is provider/model-specific, retrying the same combo is wasted
   tokens."
  [router prefs]
  (let [{:keys [providers state]} router
        sort-key (candidate-sort-key prefs)
        excluded (or (:exclude-providers prefs) #{})]
    (loop []
      (let [current @state
            ts (router-now-ms router)
            candidates (->> (force-provider-filter prefs providers)
                         (remove #(contains? excluded (:id %)))
                         (keep (fn [p] (when-let [m (resolve-model p prefs)] [p m])))
                         (filter (fn [[p _]] (provider-available? router p (get current (:id p) {})))))]
        (when (seq candidates)
          (let [[provider model-map] (first (sort-by sort-key candidates))
                pid (:id provider)
                new-state (update-in current [pid :requests]
                            (fn [r] (conj (router-prune-window router (or r [])) ts)))]
            (if (compare-and-set! state current new-state)
              [provider model-map]
              (recur))))))))

(defn- earliest-available [router prefs]
  (let [{:keys [providers state]} router
        current-state @state
        _ts (router-now-ms router)
        window-ms (:window-ms router)
        excluded (or (:exclude-providers prefs) #{})]
    (->> (force-provider-filter prefs providers)
      (remove #(contains? excluded (:id %)))
      (filter #(some? (resolve-model % prefs)))
      (keep (fn [p]
              (let [ps (get current-state (:id p) {})
                    ;; For circuit breaker: use open-until as earliest time
                    cb-ready (when (= :open (or (:cb-state ps) :closed))
                               (:cb-open-until ps))
                    requests (sort (mapv #(if (map? %) (:ts %) %)
                                     (router-prune-window router (:requests ps []))))
                    rpm-ready (when (and (seq requests)
                                      (>= (count requests) (long (:rpm p Long/MAX_VALUE))))
                                (+ (long (first requests)) (long window-ms)))
                    times (remove nil? [cb-ready rpm-ready])]
                (when (seq times) (apply max times)))))
      sort first)))

(defn- record-tokens! [router provider-id token-count]
  (let [ts (router-now-ms router)]
    (swap! (:state router) update-in [provider-id :tokens]
      (fn [t] (conj (router-prune-window router (or t [])) {:ts ts :n (or token-count 0)})))))

(defn- router-transient-error? [router e]
  (let [data (ex-data e)
        status (:status data)
        etype (:type data)
        codes (:transient-status-codes router)
        msg (ex-message e)
        msg-lower (str/lower-case (or msg ""))
        stream-output-started? (or (pos? (long (or (:content-acc-len data) 0)))
                                 (pos? (long (or (:reasoning-acc-len data) 0)))
                                 (some? (:partial-content data))
                                 (some? (:reasoning data)))]
    (boolean
      (or (and status (contains? codes status))
        (and (= etype :svar.core/http-error)
          (or (some-> msg (str/includes? "timed out"))
            (and (:stream? data)
              (not stream-output-started?)
              (or (str/includes? msg-lower "stream connection error")
                (str/includes? msg-lower "connection reset")
                (str/includes? msg-lower "connection closed")
                (str/includes? msg-lower "closed")))))
        ;; SSE EOF before the provider terminal marker (`stream-truncated`) OR
        ;; an explicit `response.incomplete` (`stream-incomplete`, e.g. Copilot's
        ;; intermittent reason-null incomplete) — both before any visible content
        ;; — are transport/provider failures, not a model answer. RETRY them
        ;; (consistent with the OpenAI Codex CLI, which raises + retries on
        ;; incomplete; early-close/incomplete usually succeeds on retry). If
        ;; content already started, throw instead: svar can't rewind streamed
        ;; chunks, so replaying would duplicate output (the caller's rewind-retry
        ;; layer owns that case).
        (and (contains? #{:svar.core/stream-truncated :svar.core/stream-incomplete} etype)
          (not stream-output-started?))
        (instance? java.net.ConnectException e)
        (instance? java.net.SocketTimeoutException e)
        (some-> (.getCause ^Throwable e)
          ((fn [c] (or (instance? java.net.ConnectException c)
                     (instance? java.net.SocketTimeoutException c)))))))))

(def ^:private DEFAULT_FORMAT_ERROR_TYPES
  "Exception `:type`s that signal a structured-output schema/format failure
   by the provider. When `:on-format-error :fallback-provider` is set on a
   call, `with-provider-fallback` treats these as transient and tries the
   next provider/model in the fleet (excluding the one that just failed)."
  #{:svar.spec/schema-rejected
    :svar.spec/required-field-missing})

(defn- format-error?
  "Returns true when the exception is a typed schema/format failure that
   the caller has opted into provider-fallback for via `:on-format-error
   :fallback-provider` in prefs."
  [prefs e]
  (and (= :fallback-provider (:on-format-error prefs))
    (let [t (:type (ex-data e))
          retry-set (or (:format-retry-on prefs) DEFAULT_FORMAT_ERROR_TYPES)]
      (contains? retry-set t))))

(def ^:private MODEL_UNSUPPORTED_PATTERNS
  "Substrings (lower-cased) in an upstream 400/404 error body that mean the
   SELECTED MODEL is unusable on this endpoint — the provider advertises it
   but rejects inference. Distinct from auth / quota / request-shape 400s
   (e.g. `authorization header is badly formatted`), which must NOT match so
   we don't churn the whole fleet on a credential problem."
  ["model_not_supported" "model not supported" "unsupported model"
   "model_not_found" "model not found" "no such model"
   "unknown model" "invalid model" "not a valid model"
   "model does not exist" "the model `" "does not exist or you do not have access"])

(defn- model-unsupported-error?
  "True when a 400/404 indicates the SELECTED MODEL is unusable on this
   endpoint, as opposed to an auth/quota/request-shape failure. Detected
   from the captured upstream error body so `with-provider-fallback` can
   route to a sibling model (same or another provider) instead of failing
   the whole call. Body-driven, not status-driven: a bare 400 is NOT enough."
  [e]
  (let [data (ex-data e)
        status (:status data)
        hay (str/lower-case (str (or (:body data) "") " " (or (ex-message e) "")))]
    (boolean
      (and (contains? #{400 404} status)
        (some #(str/includes? hay %) MODEL_UNSUPPORTED_PATTERNS)))))

(defn- routing-event
  [router event-type data]
  (assoc data
    :event/id (str (UUID/randomUUID))
    :event/type event-type
    :at-ms (router-now-ms router)))

(defn- emit-routing-event!
  [prefs event]
  (when-let [on-chunk (:on-chunk prefs)]
    (on-chunk event))
  event)

(defn- append-routing-event!
  [trace prefs event]
  (swap! trace conj event)
  (emit-routing-event! prefs event))

(defn- stream-content-started?
  [e]
  (let [data (ex-data e)]
    (or (pos? (long (or (:content-acc-len data) 0)))
      (some? (:partial-content data)))))

(defn- retry-after-header-ms
  [e]
  (let [headers (:headers (ex-data e))
        retry-after (or (get headers "retry-after")
                      (get headers "Retry-After")
                      (get headers :retry-after)
                      (get headers :Retry-After))]
    (when retry-after
      (try
        (* 1000 (Long/parseLong (str/trim (str retry-after))))
        (catch Exception _ nil)))))

(defn- rate-limit-delay-ms
  "Raw delay candidate for the next same-provider retry, in ms.

   Combines (in order of precedence):
     - `Retry-After` HTTP header (when `:respect-retry-after?` is true);
     - `:same-provider-delays-ms[attempt-index]` from policy.

   Returns nil once the configured delay schedule is exhausted (caller
   then consults `:fallback-after-ms` for budget padding before falling
   back to the next provider)."
  [router e attempt-index]
  (let [policy (:rate-limit router)
        configured (nth (vec (:same-provider-delays-ms policy)) attempt-index nil)
        retry-after (when (:respect-retry-after? policy)
                      (retry-after-header-ms e))]
    (when (some? configured)
      (long (or retry-after configured)))))

(defn- provider-label
  [provider]
  (some-> (:id provider) name))

(defn- propagate-interrupt!
  "Re-throw an `InterruptedException` (or any cause that wraps one)
   immediately. Retry / fallback loops MUST NOT swallow interrupts —
   that defeats user cancellation (Vis `Esc`), turning an explicit
   abort into another retry slot. The `catch Exception` clauses below
   call this first so cancellation walks the stack cleanly instead of
   being mistaken for a transient provider failure."
  [^Throwable e]
  (when (or (instance? InterruptedException e)
          (some #(instance? InterruptedException %)
            (take-while some? (iterate (fn [^Throwable t] (.getCause t)) (.getCause e)))))
    ;; Restore interrupt status — we caught it once; the next blocking
    ;; call up the stack should see the flag again so its own
    ;; cancellation paths fire.
    (.interrupt (Thread/currentThread))
    (throw e)))

(defn- handle-rate-limit-retries
  "Same-provider retry loop for 429 before any streamed content.

   Policy keys consulted:
     - `:same-provider-delays-ms` — vector of pre-retry sleeps;
     - `:fallback-after-ms`       — wall-clock budget for the whole 429
                                    phase (measured from `start-ms`,
                                    which is when the first 429 was
                                    caught for this provider attempt);
     - `:respect-retry-after?`    — let server-side `Retry-After` header
                                    override the configured delay;
     - `:fallback-provider?`      — gate on whether a budget-exhausted
                                    rate-limit error may fall through
                                    to the next provider.

   Budget semantics (`:fallback-after-ms` is a HARD CAP on the
   same-provider phase, not a minimum wait):
     1. each configured delay is clamped to remaining budget so the
        loop never overshoots `:fallback-after-ms`;
     2. once the configured delay vector is exhausted OR `elapsed
        ≥ :fallback-after-ms`, we return `:rate-limit-budget-exhausted?`
        immediately so the outer fallback fires with no extra wait;
     3. when `:fallback-after-ms` is 0 we fall back without any
        same-provider retry, regardless of `:same-provider-delays-ms`.

   Returns either `{:success ...}` (next call after sleep succeeded) or
   `{:error e :rate-limit-budget-exhausted? true :fallback-provider? ?
     :elapsed-ms N}` so the caller can emit a fallback event with the
   measured wall-clock elapsed."
  [router prefs trace provider model-map f e start-ms]
  (let [policy   (:rate-limit router)
        budget   (some-> (:fallback-after-ms policy) long)
        budget?  (some? budget)
        elapsed* #(- (long (router-now-ms router)) (long start-ms))
        remain*  #(if budget? (max 0 (- (long budget) (long (elapsed*)))) Long/MAX_VALUE)
        budget-exhausted-result
        (fn [last-error]
          {:error last-error
           :rate-limit-budget-exhausted? true
           :fallback-provider? (:fallback-provider? policy)
           :elapsed-ms (long (elapsed*))})]
    (loop [retry-index 0
           last-error e]
      (let [raw      (rate-limit-delay-ms router last-error retry-index)
            remain   (long (remain*))
            ;; Clamp configured delay to remaining budget so the same-provider
            ;; phase never overshoots `:fallback-after-ms`.
            delay-ms (when (and (some? raw) (or (not budget?) (pos? remain)))
                       (long (if budget? (min (long raw) remain) (long raw))))]
        (cond
          ;; A retry is still in budget — sleep, then re-invoke `f`.
          (some? delay-ms)
          (do
            (append-routing-event! trace prefs
              (routing-event router :llm.routing/provider-retry
                {:status (:status (ex-data last-error))
                 :reason :rate-limit
                 :provider (provider-label provider)
                 :model (:name model-map)
                 :attempt (inc retry-index)
                 :delay-ms delay-ms
                 :elapsed-ms (long (elapsed*))
                 :error (ex-message last-error)}))
            ;; Plain `Thread/sleep` instead of `(async/<!! (async/timeout
            ;; ...))` so a Vis-side `Esc` (= thread interrupt) wakes the
            ;; loop immediately with an `InterruptedException`. Core.async
            ;; parks block on a CountDownLatch whose interrupt semantics
            ;; vary by version; `Thread/sleep` is the contract we want.
            (when (pos? (long delay-ms))
              (Thread/sleep (long delay-ms)))
            (let [outcome (try
                            {:success (f provider model-map)}
                            (catch Exception next-error
                              ;; Cancellation MUST escape the retry loop —
                              ;; otherwise an interrupted sleep gets
                              ;; classified as `next-error` and the loop
                              ;; spins on every subsequent Esc tick (vis
                              ;; user-reported regression: \"clicking Esc
                              ;; over and over, nothing happens\").
                              (propagate-interrupt! next-error)
                              {:error next-error}))]
              (if (and (:error outcome)
                    (= 429 (:status (ex-data (:error outcome))))
                    (router-transient-error? router (:error outcome)))
                (if (stream-content-started? (:error outcome))
                  (throw (:error outcome))
                  (recur (inc retry-index) (:error outcome)))
                ;; Non-429 outcome (success OR different transient).
                ;; Forward as-is so the outer handler labels the reason
                ;; correctly (a 503 after a 429 retry is `:transient-error`,
                ;; NOT `:rate-limit-budget-exhausted`). Attach `:elapsed-ms`
                ;; on the error path so the fallback event still carries
                ;; the wall-time the same-provider phase consumed.
                (cond-> outcome
                  (:error outcome) (assoc :elapsed-ms (long (elapsed*)))))))

          ;; Configured schedule exhausted OR budget gone — fall back
          ;; now. No extra padding; `:fallback-after-ms` is a cap, not
          ;; a target.
          :else
          (budget-exhausted-result last-error))))))

(defn with-provider-fallback [router prefs f]
  (budget-check! router)
  (let [tried (atom #{})
        ;; Providers that hit a non-format failure (rate-limit budget
        ;; exhausted, transient 5xx, etc.) get added here so the next
        ;; `select-and-claim!` skips them. Without this guard the loop
        ;; would pick the SAME provider again and emit a bogus
        ;; `provider-fallback: codex/gpt-5.3 → codex/gpt-5.3` event —
        ;; observed in production when a single-model chain hits 429
        ;; repeatedly (every fallback resolved back to the offender).
        rate-limited (atom #{})
        format-failed (atom #{})
        ;; Last format-error caught — surfaced verbatim when no provider can
        ;; take call. Keeps schema-rejected ex-data, not opaque exhaustion.
        last-format-error (atom nil)
        ;; Models the provider advertises but rejects at inference time
        ;; (400/404 `model_not_supported`). Excluded by NAME (not provider)
        ;; so a sibling model on the SAME provider can still serve the call.
        model-unsupported (atom #{})
        last-unsupported-error (atom nil)
        ;; Last transient (5xx / network) error caught — surfaced verbatim when a
        ;; SINGLE provider was ever in play, so a pinned / only-provider failure
        ;; reads as ONE provider being unavailable, not a fleet "all exhausted".
        last-transient-error (atom nil)
        trace (atom [])
        selected (atom nil)
        pending-fallback (atom nil)
        ;; Ordered record of EVERY failed attempt — `{:provider :model :status
        ;; :reason :error}` per provider tried. Unlike `pending-fallback` (which
        ;; is last-wins) this accumulates, so an "all providers exhausted" failure
        ;; can name WHY each provider bowed out (`anthropic: 429 · openai: 401`)
        ;; instead of a generic message. Surfaced on the terminal ex-info.
        failed-attempts (atom [])
        max-wait-ms (:max-wait-ms router)]
    (loop [attempts 0]
      (let [iter-prefs (cond-> prefs
                         (seq @format-failed) (update :exclude-providers
                                                (fnil into #{}) @format-failed)
                         (seq @rate-limited)  (update :exclude-providers
                                                (fnil into #{}) @rate-limited)
                         (seq @model-unsupported) (update :exclude-models
                                                    (fnil into #{}) @model-unsupported))]
        (if-let [[provider model-map] (select-and-claim! router iter-prefs)]
          (let [pid (:id provider)
                start-ms (router-now-ms router)]
            (swap! tried conj pid)
            (when-not @selected
              (reset! selected {:provider (provider-label provider)
                                :model (:name model-map)}))
            (when-let [{:keys [from-provider from-model status reason error elapsed-ms]} @pending-fallback]
              (append-routing-event! trace prefs
                (routing-event router
                  (case reason
                    :format-error :llm.routing/format-fallback
                    :model-unsupported :llm.routing/model-fallback
                    :llm.routing/provider-fallback)
                  (cond-> {:status status
                           :reason (if (= reason :rate-limit) :rate-limit-budget-exhausted reason)
                           :from-provider (name from-provider)
                           :from-model from-model
                           :to-provider (provider-label provider)
                           :to-model (:name model-map)
                           :error error}
                    (some? elapsed-ms) (assoc :elapsed-ms (long elapsed-ms)))))
              (reset! pending-fallback nil))
            (let [result (try
                           {:success (f provider model-map)}
                           (catch Exception e
                             ;; Cancellation MUST escape — see propagate-interrupt!.
                             (propagate-interrupt! e)
                             (cond
                               (and (router-transient-error? router e)
                                 (stream-content-started? e))
                               (throw e)

                               (and (= 429 (:status (ex-data e)))
                                 (router-transient-error? router e))
                               (handle-rate-limit-retries router prefs trace provider model-map f e start-ms)

                               (router-transient-error? router e)
                               {:error e}

                               (format-error? prefs e)
                               {:format-error e}

                               (model-unsupported-error? e)
                               {:model-unsupported e}

                               :else (throw e))))]
              (cond
                (:success result)
                (let [result (:success result)
                      token-count (or (get-in result [:api-usage :total-tokens])
                                    (get-in result [:tokens :total])
                                    0)
                      latency-ms (- (router-now-ms router) start-ms)
                      trace-value @trace]
                  (record-tokens! router pid token-count)
                  (cb-record-success! router pid)
                  (record-cumulative! router pid token-count latency-ms)
                  (budget-record! router pid (:name model-map)
                    (or (:api-usage result)
                      {:input-tokens 0 :output-tokens 0 :total-tokens 0
                       :input-tokens-details {:regular 0 :cache-write 0 :cache-read 0}}))
                  (cond-> (assoc result
                            :routed/provider-id pid
                            :routed/model (:name model-map)
                            :routed/base-url (:base-url provider)
                            :routed/selected @selected
                            :routed/actual {:provider (provider-label provider)
                                            :model (:name model-map)}
                            :routed/fallback? (boolean (seq (filter #(not= :llm.routing/provider-retry (:event/type %)) trace-value))))
                    (seq trace-value) (assoc :routed/trace trace-value)))

                (:format-error result)
                (let [e (:format-error result)]
                  (trove/log! {:level :warn
                               :id ::format-error-fallback
                               :data {:provider-id pid
                                      :model (:name model-map)
                                      :ex-type (:type (ex-data e))}
                               :msg "format error: trying next provider"})
                  (swap! format-failed conj pid)
                  (reset! last-format-error e)
                  (reset! pending-fallback {:from-provider pid
                                            :from-model (:name model-map)
                                            :status (:status (ex-data e))
                                            :reason :format-error
                                            :error (ex-message e)})
                  (swap! failed-attempts conj {:provider pid :model (:name model-map)
                                               :status (:status (ex-data e)) :reason :format-error
                                               :error (ex-message e)})
                  (recur (inc attempts)))

                (:model-unsupported result)
                (let [e (:model-unsupported result)]
                  (trove/log! {:level :warn
                               :id ::model-unsupported
                               :data {:provider-id pid
                                      :model (:name model-map)
                                      :status (:status (ex-data e))}
                               :msg "model unsupported by endpoint: excluding model, trying next"})
                  ;; Exclude the MODEL name (not the provider): sibling models
                  ;; on the same provider may still serve the call.
                  (swap! model-unsupported conj (:name model-map))
                  (reset! last-unsupported-error e)
                  (reset! pending-fallback {:from-provider pid
                                            :from-model (:name model-map)
                                            :status (:status (ex-data e))
                                            :reason :model-unsupported
                                            :error (ex-message e)})
                  (swap! failed-attempts conj {:provider pid :model (:name model-map)
                                               :status (:status (ex-data e)) :reason :model-unsupported
                                               :error (ex-message e)})
                  (recur (inc attempts)))

                (:error result)
                (let [e (:error result)
                      status (:status (ex-data e))
                      reason (cond
                               (:rate-limit-budget-exhausted? result) :rate-limit
                               (= 429 status) :rate-limit
                               :else :transient-error)]
                  (trove/log! {:level :warn
                               :id ::provider-retry
                               :data {:provider-id pid
                                      :error (ex-message e)}
                               :msg "provider attempt failed"})
                  (cb-record-failure! router pid (= 429 status))
                  ;; Mark this provider as rate-limited / transient so the
                  ;; next `select-and-claim!` skips it. Otherwise a chain
                  ;; with a single model would loop forever resolving the
                  ;; offender right back into itself.
                  (swap! rate-limited conj pid)
                  (reset! last-transient-error e)
                  (if (and (= reason :rate-limit)
                        (false? (:fallback-provider? result)))
                    (throw e)
                    (do
                      (reset! pending-fallback
                        (cond-> {:from-provider pid
                                 :from-model (:name model-map)
                                 :status status
                                 :reason reason
                                 :error (ex-message e)}
                          (some? (:elapsed-ms result)) (assoc :elapsed-ms (:elapsed-ms result))))
                      (swap! failed-attempts conj
                        (cond-> {:provider pid :model (:name model-map)
                                 :status status :reason reason :error (ex-message e)}
                          (some? (:elapsed-ms result)) (assoc :elapsed-ms (:elapsed-ms result))))
                      (recur (inc attempts))))))))
          (cond
            (and @last-format-error (seq @format-failed))
            (let [e @last-format-error]
              (throw (ex-info (ex-message e)
                       (merge (ex-data e)
                         {:routed/trace @trace
                          :tried @tried
                          :format-failed @format-failed
                          :attempts @failed-attempts})
                       e)))

            ;; Every candidate model was rejected as unsupported and nothing
            ;; else can take the call — surface the concrete upstream reason
            ;; rather than a generic "all providers exhausted".
            (and @last-unsupported-error (seq @model-unsupported))
            (let [e @last-unsupported-error]
              (throw (ex-info (ex-message e)
                       (merge (ex-data e)
                         {:routed/trace @trace
                          :tried @tried
                          :model-unsupported @model-unsupported
                          :attempts @failed-attempts})
                       e)))

            :else
            (let [earliest (earliest-available router iter-prefs)]
              (if (and earliest (< attempts 3))
                (let [wait-ms (min (- (long earliest) (router-now-ms router)) (long max-wait-ms))]
                  (when (pos? wait-ms)
                    (trove/log! {:level :info :data {:wait-ms wait-ms :prefs iter-prefs}
                                 :msg "All providers busy, waiting"})
                    ;; Interruptible sleep — see comment in handle-rate-limit-retries.
                    (Thread/sleep (long wait-ms)))
                  (recur (inc attempts)))
                ;; No candidate could take the call. A SINGLE provider having been
                ;; in play is NOT a fleet exhaustion — it's one provider being
                ;; unavailable, and the CALLER owns where to go next. Surface it as
                ;; `:provider-unavailable` (carrying the upstream transient's
                ;; status/body) so the fleet-wide "all exhausted" wording is
                ;; reserved for a real multi-provider walk.
                (let [single? (<= (count @tried) 1)
                      te @last-transient-error
                      te-data (when te (ex-data te))]
                  (throw (ex-info (if single? "Provider unavailable" "All providers exhausted")
                           (cond-> {:type (if single?
                                            :svar.llm/provider-unavailable
                                            :svar.llm/all-providers-exhausted)
                                    :prefs iter-prefs :tried @tried
                                    :format-failed @format-failed
                                    :attempts @failed-attempts
                                    :routed/trace @trace}
                             (and single? (:status te-data)) (assoc :status (:status te-data))
                             (and single? (:body te-data)) (assoc :body (:body te-data))))))))))))))

;; =============================================================================
;; Router creation
;; =============================================================================

(defn make-router
  "Creates a router from a vector of provider maps.

   Vector order = priority (first provider is highest priority).
   First model in provider vector = root model.
   Provider :base-url auto-resolved from KNOWN_PROVIDERS for known IDs.
   Model metadata auto-inferred from :name and merged with provider-scoped pricing/context.
   Duplicate provider :id values are a hard error.

   `opts` - Optional map:
     :network   - {:timeout-ms N :max-retries N ...} router-level network defaults
     :tokens    - {:check-context? bool :pricing {} :context-limits {}} token defaults
     :budget    - {:max-tokens N :max-cost N} spend limits (nil = no limit)
     :rate-limit - {:same-provider-delays-ms [...] :fallback-after-ms N ...}
     :failure-threshold - Int. Failures before circuit opens (default: 5)
     :recovery-ms       - Int. Ms before open→half-open (default: 60000)

   Example:
     (make-router [{:id :blockether :api-key <key>
                    :models [{:name <model-a>} {:name <model-b>}]}
                   {:id :openai :api-key <key>
                    :models [{:name <model-a>} {:name <model-b>}]}]
                  {:budget {:max-tokens 1000000 :max-cost 5.0}})"
  ([providers] (make-router providers {}))
  ([providers opts]
   (when-not (sequential? providers)
     (throw (ex-info "make-router expects a vector of provider maps" {:type :svar/invalid-providers :got (type providers)})))
   (when (empty? providers)
     (throw (ex-info "make-router requires at least one provider" {:type :svar/no-providers})))
   (let [normalized (vec (map-indexed normalize-provider providers))
         ids (map :id normalized)
         dupes (keys (filter (fn [[_ n]] (> (long n) 1)) (frequencies ids)))
         merged (merge router-default-opts opts)
         budget (:budget opts)
         init-provider-state {:requests [] :tokens []
                              :cb-state :closed :cb-failures 0 :cb-open-until nil
                              :cum {:requests 0 :total-tokens 0 :latencies []}}]
     (when (seq dupes)
       (throw (ex-info (str "Duplicate provider IDs: " (str/join ", " (map name dupes)))
                {:type :svar/duplicate-provider-ids :ids dupes})))
     {:providers              normalized
      :state                  (atom (zipmap ids (repeat init-provider-state)))
      :budget                 budget
      :budget-state           (when budget (atom {:total-tokens 0 :total-cost 0.0}))
      :network                (merge DEFAULT_RETRY
                                {:timeout-ms         DEFAULT_TIMEOUT_MS
                                 :ttft-timeout-ms    DEFAULT_TTFT_TIMEOUT_MS
                                 :idle-timeout-ms    DEFAULT_IDLE_TIMEOUT_MS
                                 :semantic-timeout-ms DEFAULT_SEMANTIC_TIMEOUT_MS}
                                (:network opts))
      :tokens                 {:check-context? (let [cc (:check-context? (:tokens opts))]
                                                 (if (some? cc) cc true))
                               :pricing (merge MODEL_PRICING
                                          (:pricing (:tokens opts)))
                               :context-limits (merge MODEL_CONTEXT_LIMITS
                                                 (:context-limits (:tokens opts)))
                               :output-reserve (:output-reserve (:tokens opts))}
      :clock                  (get opts :clock #(System/currentTimeMillis))
      :window-ms              (:window-ms merged)
      :cooldown-ms            (:cooldown-ms merged)
      :max-wait-ms            (:max-wait-ms merged)
      :rate-limit             (merge DEFAULT_RATE_LIMIT_ROUTING (:rate-limit merged))
      :failure-threshold   (:failure-threshold merged)
      :recovery-ms         (:recovery-ms merged)
      :transient-status-codes (:transient-status-codes merged)})))

;; =============================================================================
;; Routing resolution
;; =============================================================================

(defn resolve-routing
  "Resolves :routing opts to prefs for with-provider-fallback.
   Returns {:prefs prefs-map :error-strategy kw}.
   Throws on invalid provider/model combinations.

   `:prefer-providers` (vector of provider ids) declares an ordered provider
   preference: svar picks the `:optimize`-best model WITHIN each provider and
   walks the list (via `with-provider-fallback`) on failure. Providers not in
   the list are tried last. This is the framework primitive for cheap
   side-channel tasks (e.g. auto-titling) that want a deliberate plan order +
   smallest model per plan instead of one global cost winner — callers no
   longer hand-roll a per-provider retry loop.

   `:reasoning` in the routing opts (abstract level — :quick/:balanced/:deep
   or strings/aliases) implies `:require-reasoning? true` in prefs, which
   filters model selection to `:reasoning? true` models in `resolve-model`.
   This makes `{:optimize :cost :reasoning :deep}` pick the cheapest
   *reasoning-capable* model rather than silently dropping `:deep` when the
   cost-cheapest model happens to be non-reasoning."
  [router routing-opts]
  (let [{:keys [optimize provider model on-transient-error reasoning
                prefer-providers on-format-error format-retry-on on-chunk]} routing-opts
        error-strategy (or on-transient-error :hybrid)
        ;; Build prefs map for with-provider-fallback
        base-prefs (cond
                     ;; Exact provider + model override
                     (and provider model)
                     (let [p (first (filter #(= (:id %) provider) (:providers router)))]
                       (when-not p
                         (throw (ex-info (str "Unknown provider: " provider)
                                  {:type :svar/routing-resolution-failed
                                   :provider provider
                                   :available (mapv :id (:providers router))})))
                       (when-not (some #(= (:name %) model) (:models p))
                         (throw (ex-info (str "Model " model " not found in provider " provider)
                                  {:type :svar/routing-resolution-failed
                                   :provider provider :model model
                                   :available (mapv :name (:models p))})))
                       {:strategy :root :force-provider provider :force-model model})
                     ;; Provider override + optimize: honor the optimization
                     ;; WITHIN the pinned provider (force-provider restricts to
                     ;; it; :prefer selects the model). Without optimize, keep
                     ;; the historical `:strategy :root` so a bare provider pin
                     ;; still resolves to that provider's root model.
                     (and provider optimize)
                     {:force-provider provider :prefer optimize}

                     provider
                     {:strategy :root :force-provider provider
                      :prefer :cost}
                     ;; Model override — find in any provider
                     model
                     {:strategy :root :force-model model}
                     ;; Ordered provider preference: pick the `:optimize`-best
                     ;; model WITHIN each provider, walking the declared order
                     ;; (with-provider-fallback handles fall-through on
                     ;; failure). Use for cheap side-channel tasks where the
                     ;; caller wants a deliberate plan order + smallest model
                     ;; per plan, not a single global cost winner.
                     (seq prefer-providers)
                     {:prefer (or optimize :cost)
                      :provider-order (vec prefer-providers)}
                     ;; Optimize across all
                     optimize
                     {:prefer optimize}
                     ;; Default — first model of first provider
                     :else
                     {:strategy :root})
        prefs (cond-> base-prefs
                reasoning            (assoc :require-reasoning? true)
                ;; Format-error fallback opts — honored by
                ;; `with-provider-fallback`. When `:on-format-error
                ;; :fallback-provider`, schema/format-typed exceptions are
                ;; treated as transient and the next provider/model in the
                ;; fleet (excluding the offender) is tried.
                on-format-error     (assoc :on-format-error on-format-error)
                format-retry-on     (assoc :format-retry-on format-retry-on)
                ;; Routing trace events fire live through this callback
                ;; (same map shape later present in `:routed/trace`).
                ;; Without this passthrough, routing events surface only
                ;; in the final result — caller/TUI sees no progress
                ;; during multi-second 429 retry sleeps.
                on-chunk            (assoc :on-chunk on-chunk))]
    {:prefs prefs
     :error-strategy error-strategy}))

;; =============================================================================
;; Token Encoding Registry
;; =============================================================================

(def ^:private ^EncodingRegistry registry
  "Shared encoding registry instance."
  (Encodings/newDefaultEncodingRegistry))

(defn- model->encoding
  "Gets the encoding for a given model name.

   jtokkit only ships OpenAI (tiktoken) encodings. When a model is in
   jtokkit's `ModelType` table (e.g. `gpt-4o` → o200k_base, `gpt-4` →
   cl100k_base) we use that exact mapping. Everything else — Claude,
   Gemini, GLM, and newer OpenAI models not yet in this jtokkit version
   (`gpt-5.x`) — falls back to o200k_base.

   o200k_base is the right fallback, NOT cl100k_base. This is the only
   place token counts are ESTIMATED; the real per-call counts always come
   from the provider's `usage` (see `count-and-estimate`, which prefers
   `:api-usage`). The estimate is used for pre-flight context checks and
   `truncate-text`. Measured against live provider `usage.prompt_tokens`
   on a 674-char mixed English/Polish/code/unicode prompt:

     model           provider  cl100k   o200k
     gpt-4o            230      +4.3%   -3.0%
     gemini-2.5-pro    219      +9.6%   +1.8%
     glm-4.7           231      +3.9%   -3.5%

   o200k is closer for EVERY provider measured (Gemini dramatically so),
   and ties cl100k on plain English/code while cutting overcount ~18-23%
   on diacritics/CJK/emoji. Fixed per-family multipliers are deliberately
   avoided: Anthropic re-tokenizes between model versions (Opus 4.6→4.7
   shifted 1.0-1.35×), so any hard-coded factor goes stale. Callers who
   need exact Claude/Gemini counts should use the provider count_tokens
   API; this fallback is a fast, dependency-free, version-stable estimate."
  ^Encoding [^String model-name]
  (try
    (let [^ModelType model-type (.orElseThrow (ModelType/fromName model-name))]
      (.getEncodingForModel registry model-type))
    (catch Exception _
      (.getEncoding registry EncodingType/O200K_BASE))))

;; =============================================================================
;; Token Input Limits
;; =============================================================================

(defn max-input-tokens
  "Calculates maximum input tokens for a model, reserving space for output."
  (^long [^String model]
   (max-input-tokens model {}))
  (^long [^String model {:keys [output-reserve trim-ratio context-limits]}]
   (let [limit (context-limit model (or context-limits MODEL_CONTEXT_LIMITS))
         effective-reserve (or output-reserve DEFAULT_OUTPUT_RESERVE)]
     (if trim-ratio
       (long (* limit (double trim-ratio)))
       (- limit (long effective-reserve))))))

;; =============================================================================
;; Token Counting
;; =============================================================================

(defn count-tokens
  "Counts tokens for a given text string using the specified model's encoding."
  ^long [^String model ^String text]
  (let [encoding (model->encoding model)]
    (.countTokens encoding text)))

(defn- tokens-per-message
  "Returns the number of overhead tokens per message for a model."
  [^String model]
  (cond
    (str/includes? model "gpt-4o")       4
    (str/includes? model "gpt-4")        3
    (str/includes? model "gpt-3.5")      4
    (str/includes? model "claude")       3
    (str/includes? model "llama")        3
    (str/includes? model "mistral")      3
    :else                                4))

(defn- tokens-per-name
  "Returns additional tokens if a message has a 'name' field."
  [^String _model]
  1)

;; =============================================================================
;; Image Token Estimation (OpenAI Vision Formula)
;; =============================================================================

(def ^:private ^:const IMAGE_TOKEN_FALLBACK
  "Fallback token estimate when image dimensions can't be determined."
  765)

(def ^:private ^:const IMAGE_URL_TIMEOUT_MS
  "Timeout in milliseconds for fetching image headers from URLs."
  3000)

(defn- image-dimensions-from-stream
  "Reads image width and height from an ImageInputStream without
   decoding pixel data. Returns [width height] or nil."
  [^ImageInputStream iis]
  (let [readers (ImageIO/getImageReaders iis)]
    (when (.hasNext readers)
      (let [^ImageReader reader (.next readers)]
        (try
          (.setInput reader iis true true)
          [(.getWidth reader 0) (.getHeight reader 0)]
          (finally
            (.dispose reader)))))))

(defn- image-dimensions-from-base64
  "Extracts image dimensions from a base64-encoded image string."
  [^String base64-str]
  (try
    (let [decoder (Base64/getDecoder)
          bytes (.decode decoder base64-str)
          bais (ByteArrayInputStream. bytes)
          iis (ImageIO/createImageInputStream bais)]
      (when iis
        (try
          (image-dimensions-from-stream iis)
          (finally
            (.close iis)))))
    (catch Exception _ nil)))

(defn- image-dimensions-from-url
  "Fetches image dimensions from an HTTP(S) URL by reading only the
   image header metadata."
  [^String url-str]
  (try
    (let [url (.toURL (URI. url-str))
          conn ^HttpURLConnection (.openConnection url)]
      (.setConnectTimeout conn IMAGE_URL_TIMEOUT_MS)
      (.setReadTimeout conn IMAGE_URL_TIMEOUT_MS)
      (.setRequestMethod conn "GET")
      (.setRequestProperty conn "Range" "bytes=0-65535")
      (try
        (let [is (.getInputStream conn)
              iis (ImageIO/createImageInputStream is)]
          (when iis
            (try
              (image-dimensions-from-stream iis)
              (finally
                (.close iis)
                (.close is)))))
        (finally
          (.disconnect conn))))
    (catch Exception _ nil)))

(defn- calculate-image-tokens
  "Applies OpenAI's vision token formula given image dimensions."
  ^long [^long width ^long height detail]
  (if (= "low" detail)
    85
    (let [max-side (double (max width height))
          scale1 (if (> max-side 2048.0) (/ 2048.0 max-side) 1.0)
          w1 (* (double width) scale1)
          h1 (* (double height) scale1)
          min-side (min w1 h1)
          scale2 (if (> min-side 768.0) (/ 768.0 min-side) 1.0)
          w2 (* w1 scale2)
          h2 (* h1 scale2)
          tiles-w (long (Math/ceil (/ w2 512.0)))
          tiles-h (long (Math/ceil (/ h2 512.0)))
          tiles (* tiles-w tiles-h)]
      (+ (* 170 tiles) 85))))

(defn- estimate-image-block-tokens
  "Estimates tokens for a single image_url message block."
  ^long [block]
  (let [url (get-in block [:image_url :url] "")
        detail (get-in block [:image_url :detail])]
    (if (= "low" detail)
      85
      (let [dims (cond
                   (str/starts-with? url "data:")
                   (let [comma-idx (str/index-of url ",")]
                     (when comma-idx
                       (image-dimensions-from-base64 (subs url (inc (long comma-idx))))))

                   (or (str/starts-with? url "http://")
                     (str/starts-with? url "https://"))
                   (image-dimensions-from-url url)

                   :else nil)]
        (if dims
          (calculate-image-tokens (long (first dims)) (long (second dims)) detail)
          IMAGE_TOKEN_FALLBACK)))))

;; =============================================================================
;; Content Extraction for Token Counting
;; =============================================================================

(defn- extract-text-from-content
  "Extracts text content and image token counts from a message content field."
  [content]
  (cond
    (string? content)
    {:text content :image-tokens 0}

    (vector? content)
    (let [{:keys [texts image-tokens]}
          (reduce (fn [{:keys [texts ^long image-tokens]} block]
                    (cond
                      (and (map? block) (= "text" (:type block)))
                      {:texts (conj texts (:text block))
                       :image-tokens image-tokens}

                      (and (map? block) (= "image_url" (:type block)))
                      {:texts texts
                       :image-tokens (+ image-tokens
                                       (long (estimate-image-block-tokens block)))}

                      :else
                      {:texts texts :image-tokens image-tokens}))
            {:texts [] :image-tokens 0}
            content)]
      {:text (str/join "\n" texts) :image-tokens image-tokens})

    :else
    {:text "" :image-tokens 0}))

(defn count-messages
  "Counts tokens for a chat completion message array."
  ^long [^String model messages]
  (let [encoding (model->encoding model)
        tpm (tokens-per-message model)
        tpn (tokens-per-name model)
        message-tokens (reduce
                         (fn [^long acc {:keys [role content name] :as _message}]
                           (let [{:keys [text image-tokens]} (extract-text-from-content content)]
                             (+ acc
                               (long tpm)
                               (long (.countTokens encoding (or (some-> role clojure.core/name) "")))
                               (long (.countTokens encoding (or text "")))
                               (long image-tokens)
                               (if name
                                 (+ (long tpn) (long (.countTokens encoding name)))
                                 0))))
                         0
                         messages)
        reply-priming 3]
    (+ (long message-tokens) reply-priming)))

;; =============================================================================
;; Cost Estimation
;; =============================================================================

(defn- get-model-pricing
  "Gets pricing for a model, with fallback to default.
   Handles model name variations by checking for partial matches."
  ([^String model]
   (get-model-pricing model MODEL_PRICING))
  ([^String model pricing]
   (or (get pricing model)
     (some (fn [[k v]]
             (when (and (string? k) (str/includes? model k))
               v))
       pricing)
     (:default pricing))))

(defn- million-cost
  [tokens rate]
  (* (/ (double (max 0 (long (or tokens 0)))) 1000000.0)
    (double (or rate 0.0))))

(defn- tier-rate
  "Some providers price full requests differently when prompt/input size
   crosses a public threshold. `selector-tokens` is prompt/input size."
  [pricing k selector-tokens]
  (let [selector-tokens (long (or selector-tokens 0))
        over-272k (keyword (str (name k) "-over-272k"))
        over-200k (keyword (str (name k) "-over-200k"))]
    (or (when (> selector-tokens 272000)
          (get pricing over-272k))
      (when (> selector-tokens 200000)
        (get pricing over-200k))
      (get pricing k))))

(defn- cache-write-rate
  [pricing ttl]
  (case ttl
    :1h (or (:cache-write-1h pricing) (:cache-write pricing) (:input pricing))
    (or (:cache-write-5m pricing) (:cache-write pricing) (:input pricing))))

(defn- usage-cache-tokens
  "Pull cache-read / cache-write tokens out of the Phase A canonical
   `:input-tokens-details` shape. Returns 0 / 0 when the shape is
   absent."
  [api-usage]
  (let [details (:input-tokens-details api-usage)]
    {:cached-tokens (long (or (:cache-read details) 0))
     :cache-creation-tokens (long (or (:cache-write details) 0))}))

(defn estimate-cost
  "Estimates USD cost with separate uncached input, cached input, cache
   creation, and output components. Rates are USD per 1M tokens.

   Since svar 0.6.0 the canonical usage shape is INCLUSIVE —
   `:input-tokens` is always the TOTAL prompt tokens regardless of
   provider (Anthropic-additive raw values are summed at the canonical
   normalizer boundary). Cached and cache-creation tokens are
   SUBSETS of `:input-tokens`, so they're subtracted to compute the
   uncached-regular portion before pricing. No more
   `:cache-tokens-in-input?` flag — the meaning is uniform."
  ([model input-tokens output-tokens]
   (estimate-cost model input-tokens output-tokens MODEL_PRICING))
  ([model input-tokens output-tokens pricing-map]
   (estimate-cost model input-tokens output-tokens pricing-map {}))
  ([model input-tokens output-tokens pricing-map opts]
   (let [pricing (get-model-pricing model pricing-map)
         {:keys [cached-tokens cache-creation-tokens]}
         (merge (usage-cache-tokens (:api-usage opts))
           (select-keys opts [:cached-tokens :cache-creation-tokens]))
         cached-tokens         (long (or cached-tokens 0))
         cache-creation-tokens (long (or cache-creation-tokens 0))
         input-uncached-tokens (max 0 (- (long input-tokens) cached-tokens cache-creation-tokens))
         input-rate (tier-rate pricing :input input-tokens)
         cached-rate (or (tier-rate pricing :cached-input input-tokens) input-rate)
         output-rate (tier-rate pricing :output input-tokens)
         cache-write-rate (cache-write-rate pricing (or (:cache-creation-ttl opts) :5min))
         input-uncached-cost (double (million-cost input-uncached-tokens input-rate))
         input-cached-cost (double (million-cost cached-tokens cached-rate))
         input-cache-write-cost (double (million-cost cache-creation-tokens cache-write-rate))
         input-cost (+ input-uncached-cost input-cached-cost input-cache-write-cost)
         output-cost (double (million-cost output-tokens output-rate))
         total-cost (+ input-cost output-cost)]
     {:input-cost input-cost
      :input-uncached-cost input-uncached-cost
      :input-cached-cost input-cached-cost
      :input-cache-write-cost input-cache-write-cost
      :cache-read-cost input-cached-cost
      :cache-write-cost input-cache-write-cost
      :output-cost output-cost
      :total-cost total-cost
      :input-uncached-tokens input-uncached-tokens
      :input-cached-tokens cached-tokens
      :input-cache-write-tokens cache-creation-tokens
      :output-tokens output-tokens
      :model model
      :pricing pricing})))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn- content-cache-ttl
  [content]
  (cond
    (map? content)
    (when (:svar/cache content)
      (:svar/cache-ttl content))

    (sequential? content)
    (some content-cache-ttl content)

    :else nil))

(defn- messages-cache-creation-ttl
  [messages]
  (if (some #(= :1h (content-cache-ttl (:content %))) messages)
    :1h
    :5min))

(defn count-and-estimate
  "Counts tokens and estimates cost in one call. Cost map separates
   uncached input, cached-read input, cache-write input, output, and total."
  ([^String model messages ^String output-text]
   (count-and-estimate model messages output-text {}))
  ([^String model messages ^String output-text {:keys [pricing input-tokens api-usage
                                                       cache-creation-ttl]}]
   (let [;; Canonical shape (Phase A): :input-tokens is TOTAL,
         ;; :input-tokens-details holds subset breakdown.
         input-tokens   (long (or (:input-tokens api-usage)
                                input-tokens
                                (count-messages model messages)))
         output-tokens  (long (or (:output-tokens api-usage)
                                (count-tokens model output-text)))
         reasoning-tokens (long (or (get-in api-usage [:output-tokens-details :reasoning])
                                  0))
         details        (:input-tokens-details api-usage)
         cached-tokens  (long (or (:cache-read details) 0))
         cache-creation-tokens (long (or (:cache-write details) 0))
         total-tokens   (+ input-tokens output-tokens)
         cost (estimate-cost model input-tokens output-tokens
                (or pricing MODEL_PRICING)
                {:api-usage api-usage
                 :cached-tokens cached-tokens
                 :cache-creation-tokens cache-creation-tokens
                 :cache-creation-ttl (or cache-creation-ttl
                                       (messages-cache-creation-ttl messages))})]
     {:input-tokens input-tokens
      :output-tokens output-tokens
      :reasoning-tokens reasoning-tokens
      :cached-tokens cached-tokens
      :cache-creation-tokens cache-creation-tokens
      :total-tokens total-tokens
      :cost cost})))

(defn format-cost
  "Formats a cost value as a human-readable USD string."
  [cost]
  (if (< (double cost) 0.0001)
    "<$0.0001"
    (String/format Locale/US "$%.4f" (into-array Object [(double cost)]))))

;; =============================================================================
;; Token-Aware Truncation
;; =============================================================================

(defn truncate-text
  "Truncates text to fit within a token limit.
   Uses proper tokenization to ensure accurate truncation."
  ([^String model ^String text ^long max-tokens]
   (truncate-text model text max-tokens {}))
  ([^String model ^String text ^long max-tokens {:keys [truncation-marker from] :or {from :end}}]
   (when (nil? text) (anomaly/incorrect! "Cannot truncate nil text" {:type :svar.tokens/nil-input}))
   (when (<= max-tokens 0) (anomaly/incorrect! "max-tokens must be positive" {:type :svar.tokens/invalid-limit :max-tokens max-tokens}))
   (let [^Encoding encoding (model->encoding model)
         ^IntArrayList tokens (.encode encoding text)
         token-count (.size tokens)]
     (if (<= token-count max-tokens)
       text
       (let [marker-tokens (if truncation-marker (.size (.encode encoding ^String truncation-marker)) 0)
             effective-max (int (- max-tokens (long marker-tokens)))
              ;; Convert IntArrayList to int[] for slicing
             token-array (.toArray tokens)
             truncated-ints (case from
                              :end (take effective-max token-array)
                              :start (drop (- token-count effective-max) token-array)
                              :middle (let [half (quot effective-max 2)
                                            first-half (take half token-array)
                                            second-half (drop (- token-count half) token-array)]
                                        (concat first-half second-half)))
             truncated-list (let [list (IntArrayList.)]
                              (doseq [t truncated-ints] (.add list (int t)))
                              list)
             truncated-text (.decode encoding truncated-list)]
         (if truncation-marker
           (case from
             :end (str truncated-text truncation-marker)
             :start (str truncation-marker truncated-text)
             :middle (let [parts (str/split truncated-text #"\s+" 2)]
                       (if (> (count parts) 1)
                         (str (first parts) truncation-marker (second parts))
                         (str truncated-text truncation-marker))))
           truncated-text))))))

;; =============================================================================
;; Pre-flight Context Checking
;; =============================================================================

(def ^:const CONTEXT_REFINE_UTILIZATION
  "Utilization threshold above which an offline tiktoken estimate is no
   longer trustworthy enough to decide the overflow verdict. When the
   offline estimate puts a prompt at or above this fraction of the input
   budget AND the caller supplied an `:exact-count-fn` (e.g. Anthropic's
   `count_tokens` API), `check-context-limit` re-counts exactly. Below it
   the offline estimate already decides correctly, so no exact count is
   fetched — this is the policy that keeps a network round-trip off the
   hot path for the common small-prompt case."
  0.85)

(defn check-context-limit
  "Checks if messages fit within model context limit.

   Token-count sources, in priority order:

   1. `:input-tokens` (optional) — a pre-counted value used verbatim.
   2. `:exact-count-fn` (optional) — a 0-arg thunk returning an exact
      count (long) or nil. Called ONLY when the offline estimate's
      utilization is at/above `CONTEXT_REFINE_UTILIZATION`, i.e. near the
      limit where precision flips the verdict; nil result falls back to
      the offline estimate. This is where the refinement *policy* lives —
      callers (e.g. the Anthropic `count_tokens` path) inject only the
      *transport*.
   3. Offline `count-messages` tiktoken estimate."
  ([^String model messages]
   (check-context-limit model messages {}))
  ([^String model messages {:keys [output-reserve throw? context-limits input-tokens exact-count-fn]
                            :or {output-reserve DEFAULT_OUTPUT_RESERVE throw? false}}]
   (let [ctx-limit (context-limit model (or context-limits MODEL_CONTEXT_LIMITS))
         effective-reserve (long output-reserve)
         max-input (- ctx-limit effective-reserve)
         offline-tokens (long (or input-tokens (count-messages model messages)))
         ;; Refine near the limit: only when the cheap estimate says we're
         ;; close enough that its imprecision could change ok?/overflow.
         refine? (and exact-count-fn
                   (nil? input-tokens)
                   (>= (/ (double offline-tokens) (double max-input))
                     CONTEXT_REFINE_UTILIZATION))
         input-tokens (long (or (when refine? (exact-count-fn))
                              offline-tokens))
         ok? (<= input-tokens max-input)
         overflow (if ok? 0 (- input-tokens max-input))
         result {:ok? ok?
                 :input-tokens input-tokens
                 :max-input-tokens max-input
                 :context-limit ctx-limit
                 :output-reserve effective-reserve
                 :overflow overflow
                 :utilization (double (/ input-tokens max-input))
                 :error (when-not ok?
                          (format "Context overflow: %d tokens exceed limit of %d (model %s has %d context, reserving %d for output). Reduce input by %d tokens."
                            input-tokens max-input model ctx-limit effective-reserve overflow))}]
     (when (and throw? (not ok?))
       (anomaly/incorrect! (:error result)
         {:type :svar.tokens/context-overflow
          :model model
          :input-tokens input-tokens
          :max-input-tokens max-input
          :overflow overflow}))
     result)))

;; =============================================================================
;; Router observability + management
;; =============================================================================

(defn router-stats
  "Returns cumulative + windowed stats for the router."
  [router]
  (let [current-state @(:state router)
        budget-state (when (:budget-state router) @(:budget-state router))
        provider-stats
        (reduce-kv
          (fn [acc pid ps]
            (let [windowed-requests (router-prune-window router (:requests ps []))
                  windowed-tokens (router-prune-window router (:tokens ps []))
                  cum (or (:cum ps) {})
                  latencies (or (:latencies cum) [])
                  avg-latency (if (seq latencies)
                                (/ (double (reduce + 0 latencies)) (long (count latencies)))
                                0.0)]
              (assoc acc pid
                {:circuit-breaker (cb-state router ps)
                 :cb-failures (or (:cb-failures ps) 0)
                 :windowed {:requests (count windowed-requests)
                            :tokens (reduce + 0 (map :n windowed-tokens))}
                 :cumulative {:requests (or (:requests cum) 0)
                              :total-tokens (or (:total-tokens cum) 0)
                              :avg-latency-ms avg-latency}})))
          {} current-state)
        total-requests (reduce + 0 (map #(get-in % [1 :cumulative :requests]) provider-stats))
        total-tokens (reduce + 0 (map #(get-in % [1 :cumulative :total-tokens]) provider-stats))]
    (cond-> {:total {:requests total-requests
                     :tokens total-tokens}
             :providers provider-stats}
      budget-state (assoc :budget {:limit (:budget router)
                                   :spent budget-state}))))

(defn reset-budget!
  "Resets the router's token/cost budget counters to zero."
  [router]
  (when-let [bs (:budget-state router)]
    (reset! bs {:total-tokens 0 :total-cost 0.0}))
  router)

(defn reset-provider!
  "Manually resets a provider's circuit breaker to :closed."
  [router provider-id]
  (swap! (:state router) update provider-id
    (fn [ps]
      (assoc ps :cb-state :closed :cb-failures 0 :cb-open-until nil)))
  router)

(defn resolve-effective-model
  "Resolves the effective routed model from the router, optionally applying
   routing overrides.

   Returns a model descriptor map (or nil when no provider is available):
   {:name :reasoning? :provider :api-style :pricing :context :intelligence :speed ...}

   `overrides` (optional map):
     :optimize  — :cost | :speed | :intelligence (translated to :prefer)
     :provider  — explicit provider id keyword
     :model     — explicit model name string
     :reasoning — reasoning level keyword (implies reasoning-capable model)

   (resolve-effective-model router)                                         ;; root model
   (resolve-effective-model router {:optimize :cost})                       ;; cheapest
   (resolve-effective-model router {:optimize :intelligence})               ;; frontier
   (resolve-effective-model router {:provider :openai :model \"gpt-5-mini\"}) ;; exact"
  ([router]
   (resolve-effective-model router nil))
  ([router overrides]
   (when router
     (let [routing-opts (select-keys overrides [:optimize :provider :model :reasoning])
           {:keys [prefs]} (resolve-routing router routing-opts)
           [provider model-map] (select-provider router prefs)
           reasoning-level (some-> (:reasoning overrides) normalize-reasoning-level)]
       (when model-map
         (cond-> {:name         (:name model-map)
                  :reasoning?   (boolean (:reasoning? model-map))
                  :provider     (:id provider)
                  :api-style    (:api-style provider)
                  :pricing      (:pricing model-map)
                  :context      (:context model-map)
                  :intelligence (:intelligence model-map)
                  :speed        (:speed model-map)}
           reasoning-level (assoc :reasoning reasoning-level)))))))


