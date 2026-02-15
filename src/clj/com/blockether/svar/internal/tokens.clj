(ns com.blockether.svar.internal.tokens
  "Token counting utilities for LLM API interactions.
   
   Based on JTokkit (https://github.com/knuddelsgmbh/jtokkit) - a Java implementation
   of OpenAI's TikToken tokenizer.
   
   Provides:
   - `count-tokens` - Count tokens for a string using a specific model's encoding
   - `count-messages` - Count tokens for a chat completion message array
   - `estimate-cost` - Estimate cost in USD based on model pricing
   - `count-and-estimate` - Count tokens and estimate cost in one call
   - `context-limit` - Get max context window for a model
   - `max-input-tokens` - Get max input tokens (context minus output reserve)
   - `truncate-text` - Token-aware text truncation
    - `truncate-messages` - Smart message truncation with priority
    - `check-context-limit` - Pre-flight check before API calls
    - `format-cost` - Format USD cost for display
   - `get-model-pricing` - Look up per-model pricing info
   
   Note: Token counts are approximate. Chat completion API payloads have ~25 token
   error margin due to internal OpenAI formatting that isn't publicly documented."
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
;; Registry and Encoding Setup
;; =============================================================================

(def ^:private ^EncodingRegistry registry
  "Shared encoding registry instance."
  (Encodings/newDefaultEncodingRegistry))

(defn- model->encoding
  "Gets the encoding for a given model name.
   
   Params:
   `model-name` - String. Model name like 'gpt-4o', 'gpt-4', 'gpt-3.5-turbo'.
   
   Returns:
   Encoding instance for the model.
   
   Falls back to cl100k_base for unknown models."
  ^Encoding [^String model-name]
  (try
    (let [^ModelType model-type (.orElseThrow (ModelType/fromName model-name))]
      (.getEncodingForModel registry model-type))
    (catch Exception _
      ;; Fallback to cl100k_base for unknown models (covers most modern models)
      (.getEncoding registry EncodingType/CL100K_BASE))))

;; =============================================================================
;; Model Context Limits
;; =============================================================================

(def DEFAULT_CONTEXT_LIMITS
  "Default maximum context window sizes for LLM models (in tokens).
   
   These are the TOTAL context limits (input + output).
   Override per-model via :context-limits in make-config.
   
   Sources:
   - OpenAI: https://platform.openai.com/docs/models
   - Anthropic: https://docs.anthropic.com/en/docs/about-claude/models
   - Google: https://cloud.google.com/vertex-ai/generative-ai/docs/models
   - Zhipu: https://docs.z.ai/guides/overview/pricing
   - DeepSeek: https://api-docs.deepseek.com/quick_start/pricing
   - Mistral: https://docs.mistral.ai/models
   
   Last updated: February 2026"
  {;; OpenAI GPT-4 models
   "gpt-4o"              128000
   "gpt-4o-2024-11-20"   128000
   "gpt-4o-2024-08-06"   128000
   "gpt-4o-mini"         128000
   "gpt-4-turbo"         128000
   "gpt-4-turbo-preview" 128000
   "gpt-4"               8192
   "gpt-4-32k"           32768
   "gpt-3.5-turbo"       16385
   "gpt-3.5-turbo-16k"   16385
   ;; OpenAI GPT-5 models (400k context, 128k max output)
   "gpt-5"               400000
   "gpt-5-mini"          400000
   "gpt-5-nano"          400000
   "gpt-5.1"             400000
   "gpt-5.2"             400000
   ;; OpenAI reasoning models
   "o1"                  200000
   "o1-preview"          128000
   "o1-mini"             128000
   "o3"                  200000
   "o3-pro"              200000
   "o3-mini"             200000
   "o4-mini"             200000
   ;; Anthropic models — Claude 3.x (legacy)
   "claude-3-5-sonnet"   200000
   "claude-3-5-haiku"    200000
   "claude-3-opus"       200000
   "claude-3-sonnet"     200000
   "claude-3-haiku"      200000
   ;; Anthropic models — Claude 4.x
   "claude-opus-4-6"     200000   ; 1M beta available
   "claude-opus-4-5"     200000
   "claude-opus-4-1"     200000
   "claude-opus-4-0"     200000
   "claude-sonnet-4-5"   200000   ; 1M beta available
   "claude-sonnet-4-0"   200000   ; 1M beta available
   "claude-haiku-4-5"    200000
   ;; Anthropic — legacy aliases (for partial matching compat)
   "claude-4-opus"       200000
   "claude-4-sonnet"     200000
   "claude-4.5-sonnet"   200000
   "claude-4.5-haiku"    200000
   "claude-opus-4.5"     200000
   ;; Google Gemini models
   "gemini-1.5-pro"      2000000
   "gemini-1.5-flash"    1000000
   "gemini-2.0-flash"    1048576
   "gemini-2.5-flash"    1048576
   "gemini-2.5-pro"      1048576
   ;; Meta models
   "llama-3.1-405b"      128000
   "llama-3.1-70b"       128000
   "llama-3.1-8b"        128000
   ;; Mistral models
   "mistral-large"       128000
   "mistral-medium"      32000
   "mistral-medium-3"    131000
   "mistral-small"       32000
   "mistral-small-3.1"   128000
   "codestral-2"         128000
   ;; Zhipu GLM models
   "glm-4"               128000
   "glm-4-plus"          128000
   "glm-4.5"             128000
   "glm-4.5-air"         128000
   "glm-4.6"             200000
   "glm-4.6v"            128000   ; vision model, 128K not 200K
   "glm-4.7"             200000
   "glm-4.7-flashx"      200000
   "glm-5"               200000
   "glm-5-code"          200000
   ;; DeepSeek models
   "deepseek-v3"         128000
   "deepseek-v3.2"       128000
   "deepseek-chat"       128000
   "deepseek-coder"      128000
   "deepseek-reasoner"   128000
   ;; Default fallback (conservative)
   :default              8192})

(def DEFAULT_OUTPUT_RESERVE
  "Default number of tokens to reserve for model output.
   0 means no reservation — let the API handle overflow naturally.
   Override per-call via :output-reserve in check-context-limit or ask! opts."
  0)

(def DEFAULT_TRIM_RATIO
  "Default ratio of context to use (leaving room for output).
   0.75 means use 75% for input, reserve 25% for output."
  0.75)

(defn context-limit
  "Returns the maximum context window size for a model.
   
   Params:
   `model` - String. Model name.
   `context-limits` - Map, optional. Override map (merged defaults from config).
   
   Returns:
   Integer. Maximum context tokens.
   
   Example:
   (context-limit \"gpt-4o\")
   ;; => 128000"
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
  "Calculates maximum input tokens for a model, reserving space for output.
   
   Params:
   `model` - String. Model name.
   `opts` - Map, optional:
     - :output-reserve - Integer. Tokens to reserve for output.
         Defaults to model's max output tokens (from DEFAULT_MAX_OUTPUT_TOKENS).
     - :trim-ratio - Float. Alternative: use ratio of context (default: nil).
         When set, overrides :output-reserve.
   
   Returns:
    Integer. Maximum input tokens.
    
    Example:
    (max-input-tokens \"gpt-4o\")
    ;; => 128000 (128000 - 0, default reserve is 0)
    
    (max-input-tokens \"gpt-4o\" {:output-reserve 4096})
    ;; => 123904 (128000 - 4096)
    
    (max-input-tokens \"gpt-4o\" {:trim-ratio 0.75})
    ;; => 96000 (128000 * 0.75)"
  (^long [^String model]
   (max-input-tokens model {}))
  (^long [^String model {:keys [output-reserve trim-ratio context-limits]}]
   (let [limit (context-limit model (or context-limits DEFAULT_CONTEXT_LIMITS))
         effective-reserve (or output-reserve DEFAULT_OUTPUT_RESERVE)]
     (if trim-ratio
       (long (* (long limit) (double trim-ratio)))
       (- (long limit) (long effective-reserve))))))

;; =============================================================================
;; Token Counting
;; =============================================================================

(defn count-tokens
  "Counts tokens for a given text string using the specified model's encoding.
   
   Params:
   `model` - String. Model name (e.g., 'gpt-4o', 'gpt-4', 'gpt-3.5-turbo').
   `text` - String. The text to count tokens for.
   
   Returns:
   Integer. Number of tokens.
   
   Example:
   (count-tokens \"gpt-4o\" \"Hello, world!\")
   ;; => 4"
  ^long [^String model ^String text]
  (let [encoding (model->encoding model)]
    (.countTokens encoding text)))

(defn- tokens-per-message
  "Returns the number of overhead tokens per message for a model.
   This accounts for role, name fields, and message separators.
   
   Based on OpenAI's documentation and empirical testing."
  [^String model]
  (cond
    (str/includes? model "gpt-4o")       4  ; gpt-4o and variants
    (str/includes? model "gpt-4")        3  ; gpt-4 and variants
    (str/includes? model "gpt-3.5")      4  ; gpt-3.5-turbo variants
    (str/includes? model "claude")       3  ; Anthropic models (approximate)
    (str/includes? model "llama")        3  ; Meta Llama models
    (str/includes? model "mistral")      3  ; Mistral models
    :else                                4)) ; Default conservative estimate

(defn- tokens-per-name
  "Returns additional tokens if a message has a 'name' field."
  [^String _model]
  1)

;; =============================================================================
;; Image Token Estimation (OpenAI Vision Formula)
;; =============================================================================
;;
;; OpenAI charges tokens for images based on dimensions and detail level:
;;   - detail "low"  → 85 tokens (fixed, any size)
;;   - detail "high" / "auto" / nil →
;;       1. Scale longest side to ≤ 2048
;;       2. Scale shortest side to ≤ 768
;;       3. Tile into 512×512 blocks
;;       4. tokens = 170 × tiles + 85
;;
;; For base64 data URLs we decode and read actual image dimensions.
;; For HTTP(S) URLs we fetch the image header (lazy read, only metadata).
;; Falls back to 765 tokens (~1024×1024 high-detail) when dimensions
;; cannot be determined.
;;
;; Reference: https://platform.openai.com/docs/guides/vision/calculating-costs

(def ^:private ^:const IMAGE_TOKEN_FALLBACK
  "Fallback token estimate when image dimensions can't be determined.
   Equivalent to a 1024×1024 image at high detail:
   ceil(1024/512) × ceil(1024/512) = 2×2 = 4 tiles → 170×4+85 = 765."
  765)

(def ^:private ^:const IMAGE_URL_TIMEOUT_MS
  "Timeout in milliseconds for fetching image headers from URLs.
   Kept short since this runs during token counting."
  3000)

(defn- image-dimensions-from-stream
  "Reads image width and height from an ImageInputStream without
   decoding pixel data. Returns [width height] or nil.
   
   Params:
   `iis` - ImageInputStream. Source to read from.
   
   Returns:
   Vector of [width height] or nil if format is unrecognized."
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
  "Extracts image dimensions from a base64-encoded image string.
   Decodes the base64 payload and reads the image header for
   width/height without fully decoding pixel data.
   
   Params:
   `base64-str` - String. Raw base64 data (no data: prefix).
   
   Returns:
   Vector of [width height] or nil on failure."
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
   image header metadata. Uses a short timeout to avoid blocking
   token counting operations.
   
   Params:
   `url-str` - String. Full HTTP(S) URL to the image.
   
   Returns:
   Vector of [width height] or nil on failure/timeout."
  [^String url-str]
  (try
    (let [url (.toURL (URI. url-str))
          conn ^HttpURLConnection (.openConnection url)]
      (.setConnectTimeout conn IMAGE_URL_TIMEOUT_MS)
      (.setReadTimeout conn IMAGE_URL_TIMEOUT_MS)
      (.setRequestMethod conn "GET")
      ;; Request only the first 64KB — enough for any image header
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
  "Applies OpenAI's vision token formula given image dimensions.
   
   detail \"low\"       → 85 tokens (fixed).
   detail \"high\"/nil  → tile-based calculation.
   
   Params:
   `width`  - Long. Image width in pixels.
   `height` - Long. Image height in pixels.
   `detail` - String or nil. \"low\", \"high\", or \"auto\".
   
   Returns:
   Long. Estimated token count."
  ^long [^long width ^long height detail]
  (if (= "low" detail)
    85
    ;; high / auto / nil — tile-based
    (let [;; Step 1: scale so longest side ≤ 2048
          max-side (double (max width height))
          scale1 (if (> max-side 2048.0) (/ 2048.0 max-side) 1.0)
          w1 (* (double width) scale1)
          h1 (* (double height) scale1)
          ;; Step 2: scale so shortest side ≤ 768
          min-side (min w1 h1)
          scale2 (if (> min-side 768.0) (/ 768.0 min-side) 1.0)
          w2 (* w1 scale2)
          h2 (* h1 scale2)
          ;; Step 3: count 512×512 tiles
          tiles-w (long (Math/ceil (/ w2 512.0)))
          tiles-h (long (Math/ceil (/ h2 512.0)))
          tiles (* tiles-w tiles-h)]
      (+ (* 170 tiles) 85))))

(defn- estimate-image-block-tokens
  "Estimates tokens for a single image_url message block.
   
   Dispatches based on URL type:
   - data: URLs → decode base64, read dimensions, apply formula
   - http(s): URLs → fetch image header, read dimensions, apply formula
   - Unknown → fallback estimate
   
   Params:
   `block` - Map. An image_url content block with shape
             {:type \"image_url\" :image_url {:url \"...\" :detail \"...\"}}.
   
   Returns:
   Long. Estimated token count for this image."
  ^long [block]
  (let [url (get-in block [:image_url :url] "")
        detail (get-in block [:image_url :detail])]
    (if (= "low" detail)
      85
      (let [dims (cond
                   ;; Base64 data URL: data:image/png;base64,<payload>
                   (str/starts-with? url "data:")
                   (let [comma-idx (str/index-of url ",")]
                     (when comma-idx
                       (image-dimensions-from-base64 (subs url (inc (long comma-idx))))))

                   ;; Remote URL
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
  "Extracts text content and image token counts from a message content field.
   
   Handles both:
   - String content (regular messages)
   - Vector content (multimodal messages with text + images)
   
   For images, calculates accurate token counts using OpenAI's vision
   formula when dimensions are available (base64 or fetchable URL),
   otherwise falls back to a conservative estimate.
   
   Params:
   `content` - String or vector. The message content.
   
   Returns:
   Map with:
   - :text         - String. Concatenated text from text blocks.
   - :image-tokens - Long. Total estimated tokens for all image blocks."
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
                       :image-tokens (+ (long image-tokens)
                                        (long (estimate-image-block-tokens block)))}

                      :else
                      {:texts texts :image-tokens image-tokens}))
                  {:texts [] :image-tokens 0}
                  content)]
      {:text (str/join "\n" texts) :image-tokens image-tokens})

    :else
    {:text "" :image-tokens 0}))

(defn count-messages
  "Counts tokens for a chat completion message array.
   
   Accounts for:
   - Message content tokens (text blocks tokenized via JTokkit)
   - Role field overhead
   - Per-message formatting overhead
   - Reply priming (every reply is primed with <|start|>assistant<|message|>)
   - Multimodal content (images sized via OpenAI's vision tile formula
     when dimensions are available, conservative fallback otherwise)
   
   Params:
   `model` - String. Model name.
   `messages` - Vector of maps with :role and :content keys.
               Content can be string (text) or vector (multimodal).
   
   Returns:
   Integer. Total token count for the messages.
   
   Example:
   (count-messages \"gpt-4o\" 
                   [{:role \"system\" :content \"You are helpful.\"}
                    {:role \"user\" :content \"Hello!\"}])
   ;; => 15"
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
        ;; Every reply is primed with <|start|>assistant<|message|>
        reply-priming 3]
    (+ (long message-tokens) reply-priming)))

;; =============================================================================
;; Cost Estimation
;; =============================================================================

(def DEFAULT_MODEL_PRICING
  "Default pricing per 1M tokens in USD as of February 2026.
   Format: {:input price-per-1M :output price-per-1M}
   Override per-model via :pricing in make-config.
   
   Sources:
   - OpenAI: https://developers.openai.com/api/docs/pricing/
   - Anthropic: https://docs.claude.com/en/about-claude/pricing
   - Google: https://cloud.google.com/vertex-ai/generative-ai/pricing
   - Zhipu: https://docs.z.ai/guides/overview/pricing
   - DeepSeek: https://api-docs.deepseek.com/quick_start/pricing
   - Mistral: https://docs.mistral.ai/models
   
   Note: These are approximate and may change. Update periodically."
  {;; OpenAI GPT-4 models
   "gpt-4o"              {:input 2.50   :output 10.00}
   "gpt-4o-2024-11-20"   {:input 2.50   :output 10.00}
   "gpt-4o-mini"         {:input 0.15   :output 0.60}
   "gpt-4-turbo"         {:input 10.00  :output 30.00}
   "gpt-4"               {:input 30.00  :output 60.00}
   "gpt-3.5-turbo"       {:input 0.50   :output 1.50}
   ;; OpenAI GPT-5 models
   "gpt-5"               {:input 1.25   :output 10.00}
   "gpt-5-mini"          {:input 0.25   :output 2.00}
   "gpt-5-nano"          {:input 0.05   :output 0.40}
   "gpt-5.1"             {:input 1.25   :output 10.00}
   "gpt-5.2"             {:input 1.75   :output 14.00}
   ;; OpenAI reasoning models
   "o1"                  {:input 15.00  :output 60.00}
   "o1-mini"             {:input 3.00   :output 12.00}
   "o1-pro"              {:input 150.00 :output 600.00}
   "o3"                  {:input 2.00   :output 8.00}
   "o3-pro"              {:input 20.00  :output 80.00}
   "o3-mini"             {:input 1.10   :output 4.40}
   "o4-mini"             {:input 1.10   :output 4.40}
   ;; Anthropic Claude 3.x (legacy)
   "claude-3-5-sonnet"   {:input 3.00   :output 15.00}
   "claude-3-5-haiku"    {:input 0.80   :output 4.00}
   "claude-3-opus"       {:input 15.00  :output 75.00}
   ;; Anthropic Claude 4.x
   "claude-opus-4-6"     {:input 5.00   :output 25.00}
   "claude-opus-4-5"     {:input 5.00   :output 25.00}
   "claude-opus-4-1"     {:input 15.00  :output 75.00}
   "claude-opus-4-0"     {:input 15.00  :output 75.00}
   "claude-sonnet-4-5"   {:input 3.00   :output 15.00}
   "claude-sonnet-4-0"   {:input 3.00   :output 15.00}
   "claude-haiku-4-5"    {:input 1.00   :output 5.00}
   ;; Google Gemini models
   "gemini-2.5-pro"      {:input 1.25   :output 10.00}
   "gemini-2.5-flash"    {:input 0.30   :output 2.50}
   "gemini-2.0-flash"    {:input 0.10   :output 0.40}
   ;; Zhipu GLM models
   "glm-5"               {:input 1.00   :output 3.20}
   "glm-5-code"          {:input 1.20   :output 5.00}
   "glm-4.7"             {:input 0.60   :output 2.20}
   "glm-4.7-flashx"      {:input 0.07   :output 0.40}
   "glm-4.6"             {:input 0.60   :output 2.20}
   "glm-4.6v"            {:input 0.30   :output 0.90}
   ;; DeepSeek models (cache-miss pricing)
   "deepseek-chat"       {:input 0.28   :output 0.42}
   "deepseek-reasoner"   {:input 0.28   :output 0.50}
   ;; Mistral models
   "mistral-medium-3"    {:input 0.40   :output 2.00}
   "codestral-2"         {:input 0.30   :output 0.90}
   ;; Default fallback (conservative estimate)
   :default              {:input 5.00   :output 15.00}})

(defn- get-model-pricing
  "Gets pricing for a model, with fallback to default.
   
   Handles model name variations by checking for partial matches."
  ([^String model]
   (get-model-pricing model DEFAULT_MODEL_PRICING))
  ([^String model pricing]
   (or (get pricing model)
       ;; Try partial matching for versioned model names
       (some (fn [[k v]]
               (when (and (string? k) (str/includes? model k))
                 v))
             pricing)
       (:default pricing))))

(defn estimate-cost
  "Estimates the cost in USD for a given token count.
   
   Params:
   `model` - String. Model name.
   `input-tokens` - Integer. Number of input (prompt) tokens.
   `output-tokens` - Integer. Number of output (completion) tokens.
   
   Returns:
   Map with:
   - :input-cost - Float. Cost for input tokens in USD.
   - :output-cost - Float. Cost for output tokens in USD.
   - :total-cost - Float. Total cost in USD.
   - :model - String. The model used for pricing.
   - :pricing - Map. The pricing rates used.
   
   Example:
   (estimate-cost \"gpt-4o\" 1000 500)
   ;; => {:input-cost 0.0025
   ;;     :output-cost 0.005
   ;;     :total-cost 0.0075
   ;;     :model \"gpt-4o\"
   ;;     :pricing {:input 2.50 :output 10.00}}"
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
  "Counts tokens and estimates cost in one call.
   
   Params:
   `model` - String. Model name.
   `messages` - Vector. Input messages for the prompt.
   `output-text` - String. The response text.
   `opts` - Map, optional:
     - :pricing - Map. Per-model pricing overrides.
     - :input-tokens - Integer, optional. Pre-counted input tokens.
         When provided, skips re-tokenizing messages (avoids duplicate work
         when check-context-limit already counted them).
   
   Returns:
   Map with:
   - :input-tokens - Integer. Number of input tokens.
   - :output-tokens - Integer. Number of output tokens.
   - :total-tokens - Integer. Total tokens used.
   - :cost - Map with :input-cost, :output-cost, :total-cost in USD.
   
   Example:
   (count-and-estimate \"gpt-4o\"
                       [{:role \"user\" :content \"Hello!\"}]
                       \"Hello! How can I help you today?\")
   ;; => {:input-tokens 8
   ;;     :output-tokens 9
   ;;     :total-tokens 17
   ;;     :cost {:input-cost 0.00002 :output-cost 0.00009 :total-cost 0.00011 ...}}"
  ([^String model messages ^String output-text]
   (count-and-estimate model messages output-text {}))
  ([^String model messages ^String output-text {:keys [pricing input-tokens]}]
   (let [input-tokens (long (or input-tokens (count-messages model messages)))
         output-tokens (count-tokens model output-text)
         total-tokens (+ input-tokens output-tokens)
         cost (estimate-cost model input-tokens output-tokens
                             (or pricing DEFAULT_MODEL_PRICING))]
     {:input-tokens input-tokens
      :output-tokens output-tokens
      :total-tokens total-tokens
      :cost cost})))

(defn format-cost
  "Formats a cost value as a human-readable USD string.
   
   Params:
   `cost` - Number. Cost in USD.
   
   Returns:
   String. Formatted cost (e.g., \"$0.0025\" or \"<$0.0001\").
   
   Example:
   (format-cost 0.0025)
   ;; => \"$0.0025\""
  [cost]
  (if (< (double cost) 0.0001)
    "<$0.0001"
    ;; Use Locale/US to ensure period as decimal separator regardless of system locale
    (String/format Locale/US "$%.4f" (into-array Object [(double cost)]))))

;; =============================================================================
;; Token-Aware Truncation
;; =============================================================================

(defn truncate-text
  "Truncates text to fit within a token limit.
   
   Uses proper tokenization to ensure accurate truncation.
   Does NOT cut in the middle of multi-token words.
   
   Params:
   `model` - String. Model name for tokenization.
   `text` - String. Text to truncate.
   `max-tokens` - Integer. Maximum tokens allowed.
   `opts` - Map, optional:
     - :truncation-marker - String. Appended when truncated (default: nil).
     - :from - Keyword. Where to truncate: :end (default), :start, or :middle.
   
   Returns:
   String. Truncated text, or original if within limit.
   
   Example:
   (truncate-text \"gpt-4o\" \"Hello world, this is a test\" 5)
   ;; => \"Hello world,\"
   
   (truncate-text \"gpt-4o\" long-text 1000 {:truncation-marker \"...\"})
   ;; => \"First part of text...\""
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
             ;; Convert back to IntArrayList for .decode method
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

(defn truncate-messages
  "Truncates a message array to fit within a token limit.
   
   Strategy (priority-based):
   1. ALWAYS preserve system message (index 0) if present
   2. ALWAYS preserve the most recent user message
   3. Trim from the MIDDLE (oldest conversation turns)
   4. This respects LLM primacy/recency bias
   
   Params:
   `model` - String. Model name.
   `messages` - Vector. Chat messages [{:role :content}].
   `max-tokens` - Integer. Maximum total tokens allowed.
   
   Returns:
   Vector. Truncated messages that fit within limit.
   
   Example:
   (truncate-messages \"gpt-4o\" messages 4000)"
  [^String model messages ^long max-tokens]
  (let [current-tokens (count-messages model messages)]
    (if (<= current-tokens max-tokens)
      messages
      ;; Need to truncate
      (let [has-system? (= "system" (some-> messages first :role name))
            system-msg (when has-system? (first messages))
            system-tokens (if system-msg (count-messages model [system-msg]) 0)

            ;; Get the most recent user message
            last-user-idx (loop [i (dec (count messages))]
                            (cond
                              (< i 0) nil
                              (= "user" (some-> (get messages i) :role name)) i
                              :else (recur (dec i))))
            last-user-msg (when last-user-idx (get messages last-user-idx))
            last-user-tokens (if last-user-msg (count-messages model [last-user-msg]) 0)

            ;; Messages in the middle that can be trimmed
            middle-start (if has-system? 1 0)
            middle-end (or last-user-idx (count messages))
            middle-msgs (subvec messages middle-start middle-end)

            ;; Available budget for middle messages
            fixed-tokens (+ system-tokens last-user-tokens 10) ; 10 token buffer
            available-for-middle (- max-tokens fixed-tokens)

            ;; Select middle messages from most recent, working backwards
            selected-middle (loop [remaining (reverse middle-msgs)
                                   selected []
                                   used-tokens 0]
                              (if (empty? remaining)
                                (reverse selected)
                                (let [msg (first remaining)
                                      msg-tokens (count-messages model [msg])
                                      new-total (+ used-tokens msg-tokens)]
                                  (if (<= new-total available-for-middle)
                                    (recur (rest remaining)
                                           (conj selected msg)
                                           new-total)
                                    ;; Skip this message, it doesn't fit
                                    (recur (rest remaining) selected used-tokens)))))]
        ;; Reconstruct message array
        (vec (concat
              (when system-msg [system-msg])
              selected-middle
              (when last-user-msg [last-user-msg])))))))

;; =============================================================================
;; Pre-flight Context Checking
;; =============================================================================

(defn check-context-limit
  "Checks if messages fit within model context limit.
   
   Use this BEFORE making API calls to get clear error messages
   instead of cryptic API errors.
   
   Params:
   `model` - String. Model name.
   `messages` - Vector. Chat messages.
    `opts` - Map, optional:
      - :output-reserve - Integer. Tokens reserved for output (default: 0).
          When 0, the full context window is available for input — the API
          handles output allocation naturally.
      - :throw? - Boolean. Throw exception if over limit (default: false).
      - :context-limits - Map. Per-model context window overrides.
    
    Returns:
    Map with:
      - :ok? - Boolean. True if messages fit.
      - :input-tokens - Integer. Counted input tokens.
      - :max-input-tokens - Integer. Maximum allowed.
      - :context-limit - Integer. Model's total context.
      - :output-reserve - Integer. Effective output reserve used.
      - :overflow - Integer. How many tokens over limit (0 if ok).
      - :error - String or nil. Error message if not ok.
    
    Example:
    (check-context-limit \"gpt-4o\" messages)
    ;; => {:ok? true :input-tokens 5000 :max-input-tokens 128000 ...}
    
    (check-context-limit \"gpt-4o\" messages {:output-reserve 4096})
    ;; => {:ok? true :input-tokens 5000 :max-input-tokens 123904 ...}
    
    (check-context-limit \"gpt-4\" huge-messages {:throw? true})
    ;; => throws ExceptionInfo with detailed error"
  ([^String model messages]
   (check-context-limit model messages {}))
  ([^String model messages {:keys [output-reserve throw? context-limits] :or {output-reserve DEFAULT_OUTPUT_RESERVE throw? false}}]
   (let [ctx-limit (long (context-limit model (or context-limits DEFAULT_CONTEXT_LIMITS)))
         effective-reserve (long output-reserve)
         max-input (- ctx-limit effective-reserve)
         input-tokens (long (count-messages model messages))
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
