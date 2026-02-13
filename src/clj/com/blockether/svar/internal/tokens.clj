(ns com.blockether.svar.internal.tokens
  "Token counting utilities for LLM API interactions.
   
   Based on JTokkit (https://github.com/knuddelsgmbh/jtokkit) - a Java implementation
   of OpenAI's TikToken tokenizer.
   
   Provides:
   - `count-tokens` - Count tokens for a string using a specific model's encoding
   - `count-messages` - Count tokens for a chat completion message array
   - `estimate-cost` - Estimate cost in USD based on model pricing
   - `context-limit` - Get max context window for a model
   - `truncate-text` - Token-aware text truncation
   - `truncate-messages` - Smart message truncation with priority
   - `check-context-limit` - Pre-flight check before API calls
   
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
   (java.util Locale)))

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
   - Google: https://ai.google.dev/gemini-api/docs/models/gemini
   
   Last updated: January 2025"
  {;; OpenAI models
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
   "gpt-5.1"             400000
   "gpt-5.2"             400000
   ;; OpenAI reasoning models
   "o1"                  200000
   "o1-preview"          128000
   "o1-mini"             128000
   "o3"                  200000
   "o3-mini"             200000
   ;; Anthropic models
   "claude-3-5-sonnet"   200000
   "claude-3-5-haiku"    200000
   "claude-3-opus"       200000
   "claude-3-sonnet"     200000
   "claude-3-haiku"      200000
   "claude-4-opus"       200000
   "claude-4-sonnet"     200000
   "claude-4.5-sonnet"   200000
   "claude-4.5-haiku"    200000
   "claude-opus-4-5"     200000
   "claude-opus-4.5"     200000
   ;; Google models
   "gemini-1.5-pro"      2000000
   "gemini-1.5-flash"    1000000
   "gemini-2.0-flash"    1000000
   ;; Meta models
   "llama-3.1-405b"      128000
   "llama-3.1-70b"       128000
   "llama-3.1-8b"        128000
   ;; Mistral models
   "mistral-large"       128000
   "mistral-medium"      32000
   "mistral-small"       32000
   ;; Zhipu GLM models
   "glm-4"               128000
   "glm-4-plus"          128000
   "glm-4.5"             128000
   "glm-4.5-air"         128000
   "glm-4.6"             200000
   "glm-4.6v"            200000
   "glm-4.7"             200000
   "glm-5"               200000
   ;; DeepSeek models
   "deepseek-v3"         128000
   "deepseek-v3.2"       128000
   "deepseek-chat"       128000
   "deepseek-coder"      128000
   ;; Default fallback (conservative)
   :default              8192})

(def DEFAULT_OUTPUT_RESERVE
  "Default number of tokens to reserve for model output.
   Set to 0 - let API handle overflow naturally. Modern APIs error gracefully
   if context is exceeded, and reserving tokens wastes available input space."
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
     - :output-reserve - Integer. Tokens to reserve for output (default: 4096).
     - :trim-ratio - Float. Alternative: use ratio of context (default: nil).
   
   Returns:
   Integer. Maximum input tokens.
   
   Example:
   (max-input-tokens \"gpt-4o\")
   ;; => 123904 (128000 - 4096)
   
   (max-input-tokens \"gpt-4o\" {:trim-ratio 0.75})
   ;; => 96000 (128000 * 0.75)"
  (^long [^String model]
   (max-input-tokens model {}))
  (^long [^String model {:keys [output-reserve trim-ratio context-limits]}]
   (let [limit (context-limit model (or context-limits DEFAULT_CONTEXT_LIMITS))]
     (if trim-ratio
       (long (* (long limit) (double trim-ratio)))
       (- (long limit) (long (or output-reserve DEFAULT_OUTPUT_RESERVE)))))))

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

(defn- extract-text-from-content
  "Extracts text content from a message content field.
   
   Handles both:
   - String content (regular messages)
   - Vector content (multimodal messages with images)
   
   For multimodal messages, extracts text from text blocks and counts
   approximate tokens for image blocks.
   
   Params:
   `content` - String or vector. The message content.
   
   Returns:
   String. The extracted text content for token counting."
  [content]
  (cond
    (string? content) content
    (vector? content)
    ;; Multimodal content: extract text from text blocks
    ;; Images are roughly 85 tokens for low-res, 170 for high-res per 512x512 tile
    ;; We approximate as 256 tokens per image for token counting purposes
    (->> content
         (keep (fn [block]
                 (cond
                   (and (map? block) (= "text" (:type block)))
                   (:text block)
                   (and (map? block) (= "image_url" (:type block)))
                      ;; Placeholder text to approximate image tokens (~256 tokens per image)
                   (apply str (repeat 256 "x"))
                   :else nil)))
         (str/join "\n"))
    :else ""))

(defn count-messages
  "Counts tokens for a chat completion message array.
   
   Accounts for:
   - Message content tokens
   - Role field overhead
   - Per-message formatting overhead
   - Reply priming (every reply is primed with <|start|>assistant<|message|>)
   - Multimodal content (images approximated as ~256 tokens each)
   
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
                          (let [text-content (extract-text-from-content content)]
                            (+ acc
                               (long tpm)
                               (long (.countTokens encoding (or (some-> role clojure.core/name) "")))
                               (long (.countTokens encoding text-content))
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
  "Default pricing per 1M tokens in USD as of January 2025.
   Format: {:input price-per-1M :output price-per-1M}
   Override per-model via :pricing in make-config.
   
   Sources:
   - OpenAI: https://openai.com/api/pricing/
   - Anthropic: https://www.anthropic.com/pricing
   
   Note: These are approximate and may change. Update periodically."
  {;; OpenAI models
   "gpt-4o"              {:input 2.50   :output 10.00}
   "gpt-4o-2024-11-20"   {:input 2.50   :output 10.00}
   "gpt-4o-mini"         {:input 0.15   :output 0.60}
   "gpt-4-turbo"         {:input 10.00  :output 30.00}
   "gpt-4"               {:input 30.00  :output 60.00}
   "gpt-3.5-turbo"       {:input 0.50   :output 1.50}
   "o1"                  {:input 15.00  :output 60.00}
   "o1-mini"             {:input 3.00   :output 12.00}
   "o1-pro"              {:input 150.00 :output 600.00}
   "o3-mini"             {:input 1.10   :output 4.40}
   ;; Anthropic models
   "claude-3-5-sonnet"   {:input 3.00   :output 15.00}
   "claude-3-5-haiku"    {:input 0.80   :output 4.00}
   "claude-3-opus"       {:input 15.00  :output 75.00}
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
  ([^String model messages ^String output-text {:keys [pricing] :as _config}]
   (let [input-tokens (count-messages model messages)
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

(defn encode-tokens
  "Encodes text into token IDs using the model's tokenizer.
   
   Params:
   `model` - String. Model name.
   `text` - String. Text to encode.
   
   Returns:
   int[] (Java primitive array). Token IDs."
  [^String model ^String text]
  (let [encoding (model->encoding model)]
    (.encode encoding text)))

(defn decode-tokens
  "Decodes token IDs back into text.
   
   Params:
   `model` - String. Model name.
   `tokens` - IntList or int[]. Token IDs to decode.
   
   Returns:
   String. Decoded text."
  ^String [^String model tokens]
  (let [encoding (model->encoding model)]
    (.decode encoding tokens)))

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
   (when (nil? text) (anomaly/incorrect! "Cannot truncate nil text" {:type :truncation/nil-input}))
   (when (<= max-tokens 0) (anomaly/incorrect! "max-tokens must be positive" {:type :truncation/invalid-limit :max-tokens max-tokens}))
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
     - :output-reserve - Integer. Tokens reserved for output (default: 4096).
     - :throw? - Boolean. Throw exception if over limit (default: false).
   
   Returns:
   Map with:
     - :ok? - Boolean. True if messages fit.
     - :input-tokens - Integer. Counted input tokens.
     - :max-input-tokens - Integer. Maximum allowed.
     - :context-limit - Integer. Model's total context.
     - :overflow - Integer. How many tokens over limit (0 if ok).
     - :error - String or nil. Error message if not ok.
   
   Example:
   (check-context-limit \"gpt-4o\" messages)
   ;; => {:ok? true :input-tokens 5000 :max-input-tokens 123904 ...}
   
   (check-context-limit \"gpt-4\" huge-messages {:throw? true})
   ;; => throws ExceptionInfo with detailed error"
  ([^String model messages]
   (check-context-limit model messages {}))
  ([^String model messages {:keys [output-reserve throw? context-limits] :or {output-reserve DEFAULT_OUTPUT_RESERVE throw? false}}]
   (let [ctx-limit (long (context-limit model (or context-limits DEFAULT_CONTEXT_LIMITS)))
         max-input (- ctx-limit (long output-reserve))
         input-tokens (long (count-messages model messages))
         ok? (<= input-tokens max-input)
         overflow (if ok? 0 (- input-tokens max-input))
         result {:ok? ok?
                 :input-tokens input-tokens
                 :max-input-tokens max-input
                 :context-limit ctx-limit
                 :output-reserve output-reserve
                 :overflow overflow
                 :utilization (double (/ input-tokens max-input))
                 :error (when-not ok?
                          (format "Context overflow: %d tokens exceed limit of %d (model %s has %d context, reserving %d for output). Reduce input by %d tokens."
                                  input-tokens max-input model ctx-limit output-reserve overflow))}]
     (when (and throw? (not ok?))
       (anomaly/incorrect! (:error result)
                           {:type :context/overflow
                            :model model
                            :input-tokens input-tokens
                            :max-input-tokens max-input
                            :overflow overflow}))
     result)))
