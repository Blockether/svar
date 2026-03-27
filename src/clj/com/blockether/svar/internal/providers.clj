 (ns com.blockether.svar.internal.providers
   "Single source of truth for LLM provider and model metadata.

    Split into:
    - KNOWN_PROVIDERS      → provider transport defaults (base-url, rpm, tpm)
    - KNOWN_MODEL_METADATA → provider-independent metadata (intelligence, speed, capabilities, reasoning-params)
    - KNOWN_PROVIDER_MODELS → provider-scoped availability, pricing, context windows

    This avoids the incorrect assumption that model pricing/context is globally stable across providers."
   (:require [clojure.string :as str]))

 ;; =============================================================================
 ;; Known providers
 ;; =============================================================================

(def KNOWN_PROVIDERS
  {:blockether {:base-url "https://llm.blockether.com/v1" :rpm 500 :tpm 2000000}
   :openai     {:base-url "https://api.openai.com/v1"     :rpm 500 :tpm 2000000}
   :anthropic  {:base-url "https://api.anthropic.com/v1"  :rpm 500 :tpm 2000000}
   :zai        {:base-url "https://api.zai.com/v1"        :rpm 500 :tpm 2000000}
   :openrouter {:base-url "https://openrouter.ai/api/v1"  :rpm 500 :tpm 2000000}
   :ollama     {:base-url "http://localhost:11434/v1"      :rpm 1000 :tpm 10000000}})

 ;; =============================================================================
 ;; Provider-independent model-family metadata
 ;; =============================================================================

(def KNOWN_MODEL_METADATA
  {;; ── OpenAI GPT-4o ────────────────────────────────────────────────────────
   "gpt-4o"                    {:intelligence :high     :speed :medium :capabilities #{:chat :vision}}

   ;; ── OpenAI GPT-4.1 ──────────────────────────────────────────────────────
   "gpt-4.1"                   {:intelligence :high     :speed :medium :capabilities #{:chat :vision}}

   ;; ── OpenAI GPT-5 ────────────────────────────────────────────────────────
   "gpt-5"                     {:intelligence :frontier :speed :medium :capabilities #{:chat :vision}}
   "gpt-5-mini"                {:intelligence :high     :speed :fast   :capabilities #{:chat :vision}}
   "gpt-5.1"                   {:intelligence :frontier :speed :medium :capabilities #{:chat :vision}}
   "gpt-5.2"                   {:intelligence :frontier :speed :medium :capabilities #{:chat :vision}}
   "gpt-5.4"                   {:intelligence :frontier :speed :medium :capabilities #{:chat :vision}}

   ;; ── OpenAI Reasoning ────────────────────────────────────────────────────
   "o3"                        {:intelligence :frontier :speed :slow   :capabilities #{:chat} :reasoning-params {:reasoning_effort "medium"}}
   "o3-pro"                    {:intelligence :frontier :speed :slow   :capabilities #{:chat} :reasoning-params {:reasoning_effort "high"}}
   "o3-mini"                   {:intelligence :high     :speed :medium :capabilities #{:chat} :reasoning-params {:reasoning_effort "medium"}}
   "o4-mini"                   {:intelligence :high     :speed :medium :capabilities #{:chat} :reasoning-params {:reasoning_effort "medium"}}

   ;; ── Anthropic Claude 4.x ────────────────────────────────────────────────
   "claude-opus-4-6"           {:intelligence :frontier :speed :slow   :capabilities #{:chat :vision}}
   "claude-opus-4-5"           {:intelligence :frontier :speed :slow   :capabilities #{:chat :vision}}
   "claude-sonnet-4-6"         {:intelligence :high     :speed :medium :capabilities #{:chat :vision}}
   "claude-sonnet-4-5"         {:intelligence :high     :speed :medium :capabilities #{:chat :vision}}
   "claude-sonnet-4-20250514"  {:intelligence :high     :speed :medium :capabilities #{:chat :vision}}
   "claude-haiku-4-5"          {:intelligence :medium   :speed :fast   :capabilities #{:chat :vision}}

   ;; ── Google Gemini ────────────────────────────────────────────────────────
   "gemini-2.5-pro"            {:intelligence :frontier :speed :medium :capabilities #{:chat :vision}}
   "gemini-2.5-flash"          {:intelligence :high     :speed :fast   :capabilities #{:chat :vision}}
   "gemini-2.0-flash"          {:intelligence :high     :speed :fast   :capabilities #{:chat :vision}}

   ;; ── Zhipu / ZAI ─────────────────────────────────────────────────────────
   "glm-5.1"                   {:intelligence :high     :speed :medium :capabilities #{:chat}}
   "glm-4.7"                   {:intelligence :high     :speed :medium :capabilities #{:chat}}
   "glm-4.6v"                  {:intelligence :high     :speed :medium :capabilities #{:chat :vision}}

   ;; ── DeepSeek ─────────────────────────────────────────────────────────────
   "deepseek-v3"               {:intelligence :high     :speed :medium :capabilities #{:chat}}
   "deepseek-v3.2"             {:intelligence :high     :speed :medium :capabilities #{:chat}}
   "deepseek-chat"             {:intelligence :high     :speed :medium :capabilities #{:chat}}
   "deepseek-reasoner"         {:intelligence :frontier :speed :slow   :capabilities #{:chat} :reasoning-params {:reasoning_effort "medium"}}

   ;; ── Mistral ──────────────────────────────────────────────────────────────
   "mistral-large"             {:intelligence :high     :speed :medium :capabilities #{:chat}}
   "mistral-small-3.1"         {:intelligence :medium   :speed :fast   :capabilities #{:chat :vision}}
   "codestral-2"               {:intelligence :high     :speed :fast   :capabilities #{:chat}}})

 ;; =============================================================================
 ;; Provider-scoped model availability, pricing, and context limits
 ;; =============================================================================

(def KNOWN_PROVIDER_MODELS
  {:blockether
   {"claude-opus-4-6"           {:pricing {:input 5.00  :output 25.00} :context 1000000}
    "claude-sonnet-4-6"         {:pricing {:input 3.00  :output 15.00} :context 200000}
    "claude-haiku-4-5"          {:pricing {:input 1.00  :output 5.00}  :context 200000}
    "gemini-2.5-pro"            {:pricing {:input 1.25  :output 10.00} :context 2000000}
    "glm-5.1"                   {:pricing {:input 1.20  :output 5.00}  :context 200000}
    "glm-4.7"                   {:pricing {:input 0.60  :output 2.20}  :context 200000}
    "glm-4.6v"                  {:pricing {:input 0.30  :output 0.90}  :context 128000}
    "gpt-4.1"                   {:pricing {:input 2.00  :output 8.00}  :context 1000000}
    "gpt-4o"                    {:pricing {:input 2.50  :output 10.00} :context 128000}
    "gpt-5"                     {:pricing {:input 1.25  :output 10.00} :context 400000}
    "gpt-5-mini"                {:pricing {:input 0.25  :output 2.00}  :context 128000}
    "gpt-5.1"                   {:pricing {:input 1.25  :output 10.00} :context 128000}
    "gpt-5.2"                   {:pricing {:input 1.75  :output 14.00} :context 200000}
    "gpt-5.4"                   {:pricing {:input 2.50  :output 15.00} :context 1000000}
    "o3-mini"                   {:pricing {:input 1.10  :output 4.40}  :context 200000}}

   :openai
   {"gpt-4o"                    {:pricing {:input 2.50  :output 10.00} :context 128000}
    "gpt-4.1"                   {:pricing {:input 2.00  :output 8.00}  :context 1000000}
    "gpt-5"                     {:pricing {:input 1.25  :output 10.00} :context 400000}
    "gpt-5-mini"                {:pricing {:input 0.25  :output 2.00}  :context 128000}
    "gpt-5.1"                   {:pricing {:input 1.25  :output 10.00} :context 128000}
    "gpt-5.2"                   {:pricing {:input 1.75  :output 14.00} :context 200000}
    "gpt-5.4"                   {:pricing {:input 2.50  :output 15.00} :context 1000000}
    "o3"                        {:pricing {:input 2.00  :output 8.00}  :context 200000}
    "o3-pro"                    {:pricing {:input 20.00 :output 80.00} :context 200000}
    "o3-mini"                   {:pricing {:input 1.10  :output 4.40}  :context 200000}
    "o4-mini"                   {:pricing {:input 1.10  :output 4.40}  :context 200000}}

   :anthropic
   {"claude-opus-4-6"           {:pricing {:input 5.00  :output 25.00} :context 1000000}
    "claude-opus-4-5"           {:pricing {:input 5.00  :output 25.00} :context 200000}
    "claude-sonnet-4-6"         {:pricing {:input 3.00  :output 15.00} :context 200000}
    "claude-sonnet-4-5"         {:pricing {:input 3.00  :output 15.00} :context 200000}
    "claude-sonnet-4-20250514"  {:pricing {:input 3.00  :output 15.00} :context 200000}
    "claude-haiku-4-5"          {:pricing {:input 1.00  :output 5.00}  :context 200000}}

   :zai
   {"glm-5.1"                   {:pricing {:input 1.20  :output 5.00}  :context 200000}
    "glm-4.7"                   {:pricing {:input 0.60  :output 2.20}  :context 200000}
    "glm-4.6v"                  {:pricing {:input 0.30  :output 0.90}  :context 128000}}

   :openrouter
   {"gpt-4o"                    {:pricing {:input 2.50  :output 10.00} :context 128000}
    "claude-sonnet-4-6"         {:pricing {:input 3.00  :output 15.00} :context 200000}
    "gemini-2.0-flash"          {:pricing {:input 0.10  :output 0.40}  :context 1000000}}

   :ollama
   {}})

 ;; =============================================================================
 ;; Derived compatibility maps
 ;; =============================================================================

(def MODEL_CONTEXT_LIMITS
  "Best-effort flattened model context limits for legacy token utilities.
    When a model exists on multiple providers with different contexts, the maximum is used."
  (assoc
   (reduce-kv (fn [acc _pid models]
                (reduce-kv (fn [macc model-name {:keys [context]}]
                             (update macc model-name #(max (or % 0) (or context 0))))
                           acc models))
              {} KNOWN_PROVIDER_MODELS)
   :default 8192))

(def MODEL_PRICING
  "Best-effort flattened model pricing for legacy token utilities.
    When a model exists on multiple providers, the lowest total pricing is chosen.
    Provider-aware code should NOT use this — use provider-model-pricing instead."
  (assoc
   (reduce-kv (fn [acc _pid models]
                (reduce-kv (fn [macc model-name {:keys [pricing]}]
                             (if-not pricing
                               macc
                               (update macc model-name
                                       (fn [existing]
                                         (if (or (nil? existing)
                                                 (< (+ (:input pricing) (:output pricing))
                                                    (+ (:input existing) (:output existing))))
                                           pricing
                                           existing)))))
                           acc models))
              {} KNOWN_PROVIDER_MODELS)
   :default {:input 5.0 :output 15.0}))

 ;; =============================================================================
 ;; Lookup + fallback inference
 ;; =============================================================================

(defn- regex-infer-metadata [model-name]
  (let [m (str/lower-case (or model-name ""))]
    (cond
      (re-find #"mini|haiku|flash|lite|small|nano" m)
      {:intelligence :medium :speed :fast
       :capabilities (cond-> #{:chat}
                       (re-find #"vision|claude|gemini|gpt-4o|glm.*v|pixtral" m) (conj :vision))
       :reasoning-params (when (re-find #"^o[1-9]|^o3|^o4" m) {:reasoning_effort "medium"})}

      (re-find #"^o[1-9]|^o3-|^o4-|reasoner|thinking" m)
      {:intelligence :frontier :speed :slow :capabilities #{:chat}
       :reasoning-params {:reasoning_effort "medium"}}

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

(defn infer-model-metadata
  "Returns provider-independent model metadata.
    Looks up KNOWN_MODEL_METADATA first. Falls back to regex inference for unknown models.
    Explicit fields in model-map override inferred values."
  [{:keys [name] :as model-map}]
  (let [base (or (get KNOWN_MODEL_METADATA name)
                 (regex-infer-metadata name))
        inferred (assoc base :name name)]
    (merge inferred (dissoc model-map :name))))

(defn normalize-model
  "Normalizes a model entry: {:name \"gpt-4o\"} -> full provider-independent model metadata."
  [model-map]
  (when (and (:name model-map) (not (str/blank? (str (:name model-map)))))
    (infer-model-metadata model-map)))

(defn provider-model-entry
  "Returns provider-scoped entry {:pricing ... :context ...} for a provider/model, or nil."
  [provider-id model-name]
  (get-in KNOWN_PROVIDER_MODELS [provider-id model-name]))

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

 ;; =============================================================================
 ;; Provider normalization
 ;; =============================================================================

(defn normalize-provider
  "Normalizes a provider entry:
    - resolves :base-url from KNOWN_PROVIDERS if not provided
    - derives :priority from vector index
    - derives :root from first model
    - merges provider-independent model metadata with provider-scoped pricing/context"
  [idx provider-map]
  (let [id (:id provider-map)
        known (get KNOWN_PROVIDERS id)
        base-url (or (:base-url provider-map) (:base-url known))
        rpm (or (:rpm provider-map) (:rpm known) 500)
        tpm (or (:tpm provider-map) (:tpm known) 2000000)
        models (->> (:models provider-map)
                    (keep (fn [m]
                            (when-let [normalized (normalize-model m)]
                              (merge normalized
                                     (provider-model-entry id (:name normalized))))))
                    vec)
        root-name (:name (first models))]
    (when-not id
      (throw (ex-info "Provider :id is required" {:provider provider-map})))
    (when-not base-url
      (throw (ex-info (str "Provider :base-url required for unknown provider " id
                           ". Known providers: " (str/join ", " (map name (keys KNOWN_PROVIDERS))))
                      {:type :svar/unknown-provider :id id})))
    (when (empty? models)
      (throw (ex-info (str "Provider " id " has no models") {:type :svar/no-models :id id})))
    {:id id
     :api-key (:api-key provider-map)
     :base-url base-url
     :priority idx
     :rpm rpm
     :tpm tpm
     :root root-name
     :models models}))

 ;; =============================================================================
 ;; Provider-agnostic accessors (legacy / fallbacks)
 ;; =============================================================================

(defn context-limit [model-name]
  (or (get MODEL_CONTEXT_LIMITS model-name)
      (:default MODEL_CONTEXT_LIMITS)))

(defn model-pricing [model-name]
  (or (get MODEL_PRICING model-name)
      (:default MODEL_PRICING)))

(defn model-capabilities [model-name]
  (or (:capabilities (get KNOWN_MODEL_METADATA model-name))
      #{:chat}))
