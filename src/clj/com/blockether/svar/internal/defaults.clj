(ns com.blockether.svar.internal.defaults
  "Single source of truth for LLM provider metadata, model metadata, token counting,
   cost estimation, and configuration defaults.

   Consolidates:
   - Provider transport defaults (base-url, rpm, tpm)
   - Provider-independent model metadata (intelligence, speed, capabilities)
   - Provider-scoped pricing and context windows
   - Token counting (JTokkit-based)
   - Cost estimation and context checking
   - Network/retry defaults

   Usage:
   (make-router [{:id :openai :api-key \"sk-...\" :models [{:name \"gpt-4o\"}]}])"
  (:require
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly])
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
   (java.util Base64 Locale)
   (javax.imageio ImageIO ImageReader)
   (javax.imageio.stream ImageInputStream)))

;; =============================================================================
;; Known Providers
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
   "deepseek-reasoner"         {:intelligence :frontier :speed :slow   :capabilities #{:chat} :reasoning-params {:reasoning_effort "medium"}}})

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
    "o3-mini"                   {:pricing {:input 1.10  :output 4.40}  :context 200000}
    "minimax-m2.5"              {:pricing {:input 0.50  :output 2.00}  :context 128000}
    "minimax-m2.7:cloud"        {:pricing {:input 0.80  :output 3.00}  :context 128000}}

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

;; Aliases for backward compatibility within the codebase
(def DEFAULT_CONTEXT_LIMITS
  "Context window sizes derived from KNOWN_PROVIDER_MODELS. Single source of truth."
  MODEL_CONTEXT_LIMITS)

(def DEFAULT_MODEL_PRICING
  "Model pricing derived from KNOWN_PROVIDER_MODELS. Single source of truth."
  MODEL_PRICING)

;; =============================================================================
;; Model metadata lookup + fallback inference
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

(defn model-pricing [model-name]
  (or (get MODEL_PRICING model-name)
    (:default MODEL_PRICING)))

(defn model-capabilities [model-name]
  (or (:capabilities (get KNOWN_MODEL_METADATA model-name))
    #{:chat}))

;; =============================================================================
;; Configuration Defaults
;; =============================================================================

(def DEFAULT_MODEL
  "Default LLM model. No hardcoded fallback."
  nil)

(def DEFAULT_BASE_URL
  "Default OpenAI API base URL."
  "https://api.openai.com/v1")

(def DEFAULT_TIMEOUT_MS
  "Default HTTP request timeout in milliseconds (3 minutes)."
  180000)

(def DEFAULT_RETRY
  "Default retry policy for transient HTTP errors."
  {:max-retries 5
   :initial-delay-ms 1000
   :max-delay-ms 60000
   :multiplier 2.0})

;; =============================================================================
;; Token Encoding Registry
;; =============================================================================

(def ^:private ^EncodingRegistry registry
  "Shared encoding registry instance."
  (Encodings/newDefaultEncodingRegistry))

(defn- model->encoding
  "Gets the encoding for a given model name.
   Falls back to cl100k_base for unknown models."
  ^Encoding [^String model-name]
  (try
    (let [^ModelType model-type (.orElseThrow (ModelType/fromName model-name))]
      (.getEncodingForModel registry model-type))
    (catch Exception _
      (.getEncoding registry EncodingType/CL100K_BASE))))

;; =============================================================================
;; Token Context Limits
;; =============================================================================

(def DEFAULT_OUTPUT_RESERVE
  "Default number of tokens to reserve for model output.
   0 means no reservation — let the API handle overflow naturally."
  0)

(defn context-limit
  "Returns the maximum context window size for a model.

   Params:
   `model` - String. Model name.
   `context-limits` - Map, optional. Override map (merged defaults from config).

   Returns:
   Integer. Maximum context tokens."
  (^long [^String model]
   (context-limit model DEFAULT_CONTEXT_LIMITS))
  (^long [^String model context-limits]
   (or (get context-limits model)
       ;; Try partial matching for versioned model names
     (some (fn [[k v]]
             (when (and (string? k) (str/includes? model k))
               v))
       context-limits)
     (:default context-limits))))

(defn max-input-tokens
  "Calculates maximum input tokens for a model, reserving space for output."
  (^long [^String model]
   (max-input-tokens model {}))
  (^long [^String model {:keys [output-reserve trim-ratio context-limits]}]
   (let [limit (context-limit model (or context-limits DEFAULT_CONTEXT_LIMITS))
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
          (reduce (fn [{:keys [texts image-tokens]} block]
                    (cond
                      (and (map? block) (= "text" (:type block)))
                      {:texts (conj texts (:text block))
                       :image-tokens image-tokens}

                      (and (map? block) (= "image_url" (:type block)))
                      {:texts texts
                       :image-tokens (+ image-tokens
                                       (estimate-image-block-tokens block))}

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
   (get-model-pricing model DEFAULT_MODEL_PRICING))
  ([^String model pricing]
   (or (get pricing model)
     (some (fn [[k v]]
             (when (and (string? k) (str/includes? model k))
               v))
       pricing)
     (:default pricing))))

(defn estimate-cost
  "Estimates the cost in USD for a given token count."
  ([^String model ^long input-tokens ^long output-tokens]
   (estimate-cost model input-tokens output-tokens DEFAULT_MODEL_PRICING))
  ([^String model ^long input-tokens ^long output-tokens pricing-map]
   (let [pricing (get-model-pricing model pricing-map)
         input-cost (* (/ (double input-tokens) 1000000.0) (double (:input pricing)))
         output-cost (* (/ (double output-tokens) 1000000.0) (double (:output pricing)))
         total-cost (+ input-cost output-cost)]
     {:input-cost input-cost
      :output-cost output-cost
      :total-cost total-cost
      :model model
      :pricing pricing})))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn count-and-estimate
  "Counts tokens and estimates cost in one call."
  ([^String model messages ^String output-text]
   (count-and-estimate model messages output-text {}))
  ([^String model messages ^String output-text {:keys [pricing input-tokens api-usage]}]
   (let [input-tokens (long (or (:prompt_tokens api-usage) input-tokens (count-messages model messages)))
         output-tokens (long (or (:completion_tokens api-usage) (count-tokens model output-text)))
         reasoning-tokens (long (or (get-in api-usage [:completion_tokens_details :reasoning_tokens]) 0))
         cached-tokens (long (or (get-in api-usage [:prompt_tokens_details :cached_tokens]) 0))
         total-tokens (+ input-tokens output-tokens)
         cost (estimate-cost model input-tokens output-tokens
                (or pricing DEFAULT_MODEL_PRICING))]
     {:input-tokens input-tokens
      :output-tokens output-tokens
      :reasoning-tokens reasoning-tokens
      :cached-tokens cached-tokens
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

(defn check-context-limit
  "Checks if messages fit within model context limit."
  ([^String model messages]
   (check-context-limit model messages {}))
  ([^String model messages {:keys [output-reserve throw? context-limits] :or {output-reserve DEFAULT_OUTPUT_RESERVE throw? false}}]
   (let [ctx-limit (context-limit model (or context-limits DEFAULT_CONTEXT_LIMITS))
         effective-reserve (long output-reserve)
         max-input (- ctx-limit effective-reserve)
         input-tokens (count-messages model messages)
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

