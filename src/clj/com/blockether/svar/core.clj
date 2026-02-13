(ns com.blockether.svar.core
  "LLM interaction utilities for structured and unstructured outputs.
   
   SVAR = Structured Validated Automated Reasoning
   
   Provides main functions:
   - `ask!` - Structured output using the spec DSL
   - `abstract!` - Text summarization using Chain of Density prompting
   - `eval!` - LLM self-evaluation for reliability and accuracy assessment
   - `refine!` - Iterative refinement using decomposition and verification
   - `models!` - Fetch available models from the LLM API
   - `sample!` - Generate test data samples matching a spec
   
   Configuration:
   Config MUST be passed explicitly to all LLM functions via the :config parameter.
   No global state. No dependency injection.
   
   Example:
   (def config (config/make-config \"sk-...\" \"https://api.openai.com/v1\"))
   (ask! {:config config
          :spec my-spec
          :objective \"Help the user.\"
          :task \"What is 2+2?\"
          :model \"gpt-4o\"})
   
   References:
   - Chain of Density: https://arxiv.org/abs/2309.04269
   - LLM Self-Evaluation: https://learnprompting.org/docs/reliability/lm_self_eval
   - DuTy: https://learnprompting.org/docs/advanced/decomposition/duty-distinct-chain-of-thought
   - CoVe: https://learnprompting.org/docs/advanced/self_criticism/chain_of_verification"
  (:require
   [babashka.http-client :as http]
   [charred.api :as json]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.internal.config :as config]
   [com.blockether.svar.internal.guard :as guard]
   [com.blockether.svar.internal.humanize :as humanize]
   [com.blockether.svar.internal.tokens :as tokens]
   [com.blockether.svar.spec :as spec]
   [taoensso.trove :as trove]))

;; =============================================================================
;; Re-export config functions
;; =============================================================================

(def make-config
  "Creates an LLM configuration map. See internal.config for details."
  config/make-config)



;; =============================================================================
;; Re-export spec DSL
;; =============================================================================

(def field
  "Creates a field definition for a spec. See spec namespace for details."
  spec/field)

(def svar-spec
  "Creates a spec definition from field definitions. See spec namespace for details."
  spec/spec)

(def build-ref-registry
  "Builds a registry of referenced specs. See spec namespace for details."
  spec/build-ref-registry)

(def str->data
  "Parses LLM response string to Clojure data. See spec namespace for details."
  spec/str->data)

(def str->data-with-spec
  "Parses LLM response with spec validation. See spec namespace for details."
  spec/str->data-with-spec)

(def data->str
  "Serializes Clojure data to LLM-compatible string. See spec namespace for details."
  spec/data->str)

(def validate-data
  "Validates parsed data against a spec. See spec namespace for details."
  spec/validate-data)

(def spec->prompt
  "Generates LLM prompt from a spec. See spec namespace for details."
  spec/spec->prompt)

;; =============================================================================
;; Spec Field Option Keywords
;; =============================================================================

;; These are the namespaced keywords used in field definitions.
;; Re-exported here for convenience so users can require only svar.core.

(def NAME
  "Field option: Field name as Datomic-style keyword (e.g., :user/name)."
  ::spec/name)

(def TYPE
  "Field option: Field type (e.g., :spec.type/string, :spec.type/int)."
  ::spec/type)

(def CARDINALITY
  "Field option: Field cardinality (:spec.cardinality/one or :spec.cardinality/many)."
  ::spec/cardinality)

(def DESCRIPTION
  "Field option: Human-readable field description."
  ::spec/description)

(def REQUIRED
  "Field option: Whether field is required (default: true). Set to false for optional."
  ::spec/required)

(def VALUES
  "Field option: Enum values as map {value description}."
  ::spec/values)

(def TARGET
  "Field option: Reference target for :spec.type/ref fields."
  ::spec/target)

(def UNION
  "Field option: Set of allowed nil types (used internally for optional fields)."
  ::spec/union)

(def KEY-NS
  "Spec option: Namespace prefix to add to keys during parsing."
  ::spec/key-ns)

(def FIELDS
  "Spec structure: Vector of field definitions."
  ::spec/fields)

(def SPEC-NAME
  "Spec structure: Name of the spec (for references)."
  ::spec/spec-name)

(def REFS
  "Spec structure: Vector of referenced specs."
  ::spec/refs)

(def NIL
  "Union value: Represents nil/null in optional fields."
  ::spec/nil)

;; =============================================================================
;; Type Keywords
;; =============================================================================

;; Base types
(def TYPE_STRING
  "Type: String value."
  :spec.type/string)

(def TYPE_INT
  "Type: Integer value."
  :spec.type/int)

(def TYPE_FLOAT
  "Type: Floating point value."
  :spec.type/float)

(def TYPE_BOOL
  "Type: Boolean value."
  :spec.type/bool)

(def TYPE_DATE
  "Type: ISO date (YYYY-MM-DD)."
  :spec.type/date)

(def TYPE_DATETIME
  "Type: ISO datetime."
  :spec.type/datetime)

(def TYPE_REF
  "Type: Reference to another spec."
  :spec.type/ref)

(def TYPE_KEYWORD
  "Type: Clojure keyword (rendered as string, keywordized on parse)."
  :spec.type/keyword)

;; Fixed-size integer vector types (1-12 elements)
(def TYPE_INT_V_1 "Type: Fixed-size integer vector (1 element)." :spec.type/int-v-1)
(def TYPE_INT_V_2 "Type: Fixed-size integer vector (2 elements)." :spec.type/int-v-2)
(def TYPE_INT_V_3 "Type: Fixed-size integer vector (3 elements)." :spec.type/int-v-3)
(def TYPE_INT_V_4 "Type: Fixed-size integer vector (4 elements)." :spec.type/int-v-4)
(def TYPE_INT_V_5 "Type: Fixed-size integer vector (5 elements)." :spec.type/int-v-5)
(def TYPE_INT_V_6 "Type: Fixed-size integer vector (6 elements)." :spec.type/int-v-6)
(def TYPE_INT_V_7 "Type: Fixed-size integer vector (7 elements)." :spec.type/int-v-7)
(def TYPE_INT_V_8 "Type: Fixed-size integer vector (8 elements)." :spec.type/int-v-8)
(def TYPE_INT_V_9 "Type: Fixed-size integer vector (9 elements)." :spec.type/int-v-9)
(def TYPE_INT_V_10 "Type: Fixed-size integer vector (10 elements)." :spec.type/int-v-10)
(def TYPE_INT_V_11 "Type: Fixed-size integer vector (11 elements)." :spec.type/int-v-11)
(def TYPE_INT_V_12 "Type: Fixed-size integer vector (12 elements)." :spec.type/int-v-12)

;; Fixed-size string vector types (1-12 elements)
(def TYPE_STRING_V_1 "Type: Fixed-size string vector (1 element)." :spec.type/string-v-1)
(def TYPE_STRING_V_2 "Type: Fixed-size string vector (2 elements)." :spec.type/string-v-2)
(def TYPE_STRING_V_3 "Type: Fixed-size string vector (3 elements)." :spec.type/string-v-3)
(def TYPE_STRING_V_4 "Type: Fixed-size string vector (4 elements)." :spec.type/string-v-4)
(def TYPE_STRING_V_5 "Type: Fixed-size string vector (5 elements)." :spec.type/string-v-5)
(def TYPE_STRING_V_6 "Type: Fixed-size string vector (6 elements)." :spec.type/string-v-6)
(def TYPE_STRING_V_7 "Type: Fixed-size string vector (7 elements)." :spec.type/string-v-7)
(def TYPE_STRING_V_8 "Type: Fixed-size string vector (8 elements)." :spec.type/string-v-8)
(def TYPE_STRING_V_9 "Type: Fixed-size string vector (9 elements)." :spec.type/string-v-9)
(def TYPE_STRING_V_10 "Type: Fixed-size string vector (10 elements)." :spec.type/string-v-10)
(def TYPE_STRING_V_11 "Type: Fixed-size string vector (11 elements)." :spec.type/string-v-11)
(def TYPE_STRING_V_12 "Type: Fixed-size string vector (12 elements)." :spec.type/string-v-12)

;; Fixed-size double vector types (1-12 elements)
(def TYPE_DOUBLE_V_1 "Type: Fixed-size double vector (1 element)." :spec.type/double-v-1)
(def TYPE_DOUBLE_V_2 "Type: Fixed-size double vector (2 elements)." :spec.type/double-v-2)
(def TYPE_DOUBLE_V_3 "Type: Fixed-size double vector (3 elements)." :spec.type/double-v-3)
(def TYPE_DOUBLE_V_4 "Type: Fixed-size double vector (4 elements)." :spec.type/double-v-4)
(def TYPE_DOUBLE_V_5 "Type: Fixed-size double vector (5 elements)." :spec.type/double-v-5)
(def TYPE_DOUBLE_V_6 "Type: Fixed-size double vector (6 elements)." :spec.type/double-v-6)
(def TYPE_DOUBLE_V_7 "Type: Fixed-size double vector (7 elements)." :spec.type/double-v-7)
(def TYPE_DOUBLE_V_8 "Type: Fixed-size double vector (8 elements)." :spec.type/double-v-8)
(def TYPE_DOUBLE_V_9 "Type: Fixed-size double vector (9 elements)." :spec.type/double-v-9)
(def TYPE_DOUBLE_V_10 "Type: Fixed-size double vector (10 elements)." :spec.type/double-v-10)
(def TYPE_DOUBLE_V_11 "Type: Fixed-size double vector (11 elements)." :spec.type/double-v-11)
(def TYPE_DOUBLE_V_12 "Type: Fixed-size double vector (12 elements)." :spec.type/double-v-12)

;; =============================================================================
;; Cardinality Keywords
;; =============================================================================

(def CARDINALITY_ONE
  "Cardinality: Single value."
  :spec.cardinality/one)

(def CARDINALITY_MANY
  "Cardinality: Vector of values."
  :spec.cardinality/many)

;; =============================================================================
;; Re-export humanize functions
;; =============================================================================

(def humanize-string
  "Removes AI-style phrases from text to make it sound more natural.
   See internal.humanize for details."
  humanize/humanize-string)

(def humanize-data
  "Recursively humanizes all strings in a data structure.
   See internal.humanize for details."
  humanize/humanize-data)

(def humanizer
  "Creates a humanization function with optional custom patterns.
   See internal.humanize for details."
  humanize/humanizer)

(def HUMANIZE_DEFAULT_PATTERNS
  "Default patterns for AI phrase humanization (safe + aggressive combined).
   Preserved for backward compatibility."
  humanize/DEFAULT_PATTERNS)

(def HUMANIZE_SAFE_PATTERNS
  "Safe humanization patterns: AI identity, refusal, knowledge, punctuation.
   These are unambiguously AI artifacts, safe for arbitrary text."
  humanize/SAFE_PATTERNS)

(def HUMANIZE_AGGRESSIVE_PATTERNS
  "Aggressive humanization patterns: hedging, overused verbs/adjectives/nouns, cliches.
   May match valid English -- opt-in only via {:aggressive? true}."
  humanize/AGGRESSIVE_PATTERNS)

;; =============================================================================
;; Re-export guard functions
;; =============================================================================

(def static-guard
  "Creates a guard function that checks for prompt injection patterns.
   See internal.guard for details."
  guard/static)

(def moderation-guard
  "Creates a guard function that uses LLM to check content against policies.
   See internal.guard for details."
  guard/moderation)

(def guard
  "Runs guard(s) on input. See internal.guard for details."
  guard/guard)

(def GUARD_DEFAULT_INJECTION_PATTERNS
  "Default patterns for prompt injection detection."
  guard/DEFAULT_INJECTION_PATTERNS)

(def GUARD_DEFAULT_MODERATION_POLICIES
  "Default OpenAI moderation policies to check."
  guard/DEFAULT_MODERATION_POLICIES)

;; =============================================================================
;; HTTP Utilities
;; =============================================================================

(def ^:private RETRYABLE_STATUS_CODES
  "HTTP status codes that should trigger a retry."
  #{429 502 503 504})

(defn- http-post!
  "Makes an HTTP POST request with JSON body and OAuth token.
   
   Params:
   `url` - String. The URL to POST to.
   `body` - Map. Request body to serialize as JSON.
   `api-key` - String. OAuth bearer token.
   `timeout-ms` - Integer. Request timeout in milliseconds.
   
   Returns:
   Map. Parsed JSON response.
   
   Throws:
   ExceptionInfo on HTTP errors."
  [url body api-key timeout-ms]
  (let [response (http/post url
                            {:headers {"Authorization" (str "Bearer " api-key)
                                       "Content-Type" "application/json"}
                             :body (json/write-json-str body)
                             :timeout timeout-ms})]
    (json/read-json (:body response) :key-fn keyword)))

(defn- http-get!
  "Makes an HTTP GET request with OAuth token.
   
   Params:
   `url` - String. The URL to GET.
   `api-key` - String. OAuth bearer token.
   
   Returns:
   Map. Parsed JSON response."
  [url api-key]
  (let [response (http/get url
                           {:headers {"Authorization" (str "Bearer " api-key)
                                      "Content-Type" "application/json"}})]
    (json/read-json (:body response) :key-fn keyword)))

(defn- with-retry
  "Executes a function with exponential backoff retry for transient errors.
   
   Retries on HTTP status codes 429, 502, 503, 504.
   
   Params:
   `f` - Function to execute (no args).
   `opts` - Map, optional:
     - :max-retries - Integer. Max retry attempts (default: 5).
     - :initial-delay-ms - Integer. Initial backoff (default: 1000ms).
     - :max-delay-ms - Integer. Max backoff cap (default: 60000ms).
     - :multiplier - Number. Backoff multiplier (default: 2.0).
   
   Returns:
   Result of f.
   
   Throws:
   ExceptionInfo if all retries exhausted."
  ([f] (with-retry f {}))
  ([f {:keys [max-retries initial-delay-ms max-delay-ms multiplier]
       :or {max-retries 5
            initial-delay-ms 1000
            max-delay-ms 60000
            multiplier 2.0}}]
   (loop [attempt 1
          delay-ms initial-delay-ms]
     (let [result (try
                    {:success (f)}
                    (catch Exception e
                      (let [ex-data-map (ex-data e)
                            status (:status ex-data-map)]
                        (if (and (contains? RETRYABLE_STATUS_CODES status)
                                 (< attempt (long max-retries)))
                          {:retry true :error e :status status}
                          {:error e}))))]
       (cond
         (:success result) (:success result)
         (:retry result) (do
(trove/log! {:level :warn :data {:attempt attempt
                                                                    :status (:status result)
                                                                    :delay-ms delay-ms}
                                              :msg "Retrying HTTP request"})
                           (Thread/sleep (long delay-ms))
                           (recur (inc attempt)
                                  (min (* (double delay-ms) (double multiplier)) (double max-delay-ms))))
         :else (throw (:error result)))))))

;; =============================================================================
;; LLM API
;; =============================================================================

(defn- extract-content
  "Extracts the assistant's response content from an OpenAI-compatible API response."
  [response]
  (get-in response [:choices 0 :message :content]))

(defn- build-request-body
  "Builds the request body for an OpenAI-compatible chat completion API."
  [messages model]
  {:model model
   :messages messages})

(defn- sanitize-messages-for-logging
  "Removes base64 image data from messages for safe logging.
   
   Replaces large base64 strings with placeholder to avoid huge log entries.
   
   Params:
   `messages` - Vector. Chat messages.
   
   Returns:
   Vector. Messages with base64 data replaced by placeholder."
  [messages]
  (mapv (fn [msg]
          (if (vector? (:content msg))
            ;; Multimodal content - filter out image data
            (update msg :content
                    (fn [content-blocks]
                      (mapv (fn [block]
                              (if (= "image_url" (:type block))
                                ;; Replace base64 data with placeholder
                                (let [url (get-in block [:image_url :url] "")]
                                  (if (str/starts-with? url "data:")
                                    (assoc-in block [:image_url :url]
                                              (str (first (str/split url #",")) ",<BASE64_DATA_TRUNCATED>"))
                                    block))
                                block))
                            content-blocks)))
            ;; Text-only content - pass through
            msg))
        messages))

(def ^:private API_KEY_ERROR_PATTERNS
  "Known error patterns that indicate API key configuration issues."
  [{:pattern #"(?i)no.connected.db"
    :message "API key is invalid or not configured. Check your API key."}
   {:pattern #"(?i)invalid.api.key"
    :message "API key is invalid. Check your API key."}
   {:pattern #"(?i)authentication|unauthorized|api.key"
    :message "API authentication failed. Check your API key."}])

(defn- detect-api-key-error
  "Checks if an error response indicates an API key configuration issue."
  [response-body]
  (when response-body
    (some (fn [{:keys [pattern message]}]
            (when (re-find pattern response-body)
              message))
          API_KEY_ERROR_PATTERNS)))

(defn- chat-completion-with-retry
  "Calls the LLM API with exponential backoff retry for rate limits."
  [messages model api-key base-url retry-opts timeout-ms]
  (let [request-body (build-request-body messages model)
        ;; Ensure URL has /chat/completions endpoint
        chat-url (if (str/ends-with? base-url "/chat/completions")
                   base-url
                   (str base-url "/chat/completions"))]
    (try
      (with-retry
        (fn []
          (let [parsed (http-post! chat-url request-body api-key timeout-ms)]
            (extract-content parsed)))
        retry-opts)
      (catch Exception e
        ;; Check for API key configuration errors
        (let [ex-data-map (ex-data e)
              response-body (:body ex-data-map)
              api-key-error (detect-api-key-error response-body)
              sanitized-request {:model model
                                 :base-url base-url
                                 :timeout-ms timeout-ms
                                 :api-key-length (count api-key)
                                 :api-key-prefix (when api-key (subs api-key 0 (min 8 (count api-key))))
                                 :messages (sanitize-messages-for-logging messages)}
              error-message (if api-key-error
                              (str api-key-error " (Original: " (ex-message e) ")")
                              (ex-message e))]
          (when api-key-error
            (trove/log! {:level :error
                         :data {:api-key-error api-key-error
                                :api-key-length (count api-key)
                                :api-key-prefix (when api-key (subs api-key 0 (min 8 (count api-key))))
                                :response-body response-body}
                         :msg "LLM API key configuration error detected"}))
          (anomaly/fault! error-message
                          (cond-> (merge (ex-data e) {:llm-request sanitized-request})
                            api-key-error (assoc :api-key-error api-key-error))))))))

(defn- chat-completion
  "Calls the LLM API (OpenAI compatible) with the given messages."
  ([messages model api-key base-url]
   (chat-completion messages model api-key base-url {}))
  ([messages model api-key base-url retry-opts]
   (let [timeout-ms (get retry-opts :timeout-ms config/DEFAULT_TIMEOUT_MS)]
     (chat-completion-with-retry
      messages model api-key base-url retry-opts timeout-ms))))

(def ^:private NUCLEUS_PROMPT
  "Nucleus operating principles for improved AI reasoning and output quality.
   Based on https://github.com/michaelwhitford/nucleus"
  "engage nucleus: [phi fractal euler tao pi mu] | [Δ λ ∞/0 | ε/φ Σ/μ c/h] | OODA Human ⊗ AI")

(defn- build-system-prompt
  "Builds the system prompt with the objective wrapped in XML tags."
  [objective]
  (str "<objective>\n" objective "\n</objective>"))

(defn- build-user-content
  "Builds user message content, supporting both text-only and multimodal formats."
  [text images]
  (if (empty? images)
    ;; Text-only: return plain string
    text
    ;; Multimodal: build content array with images first, then text
    (let [image-blocks (mapv (fn [{:keys [base64 media-type] :or {media-type "image/png"}}]
                               {:type "image_url"
                                :image_url {:url (str "data:" media-type ";base64," base64)}})
                             images)
          text-block {:type "text" :text text}]
      (conj image-blocks text-block))))

;; =============================================================================
;; Config Resolution Helper
;; =============================================================================

(defn- resolve-opts
  "Extracts effective config values from opts, falling back to config defaults.
   If no :config provided, creates one from env vars."
  [{:keys [config model timeout-ms check-context?]}]
  (let [config (or config (config/make-config))]
    {:config config
     :model (or model (:model config))
     :timeout-ms (or timeout-ms (:timeout-ms config))
     :check-context? (if (some? check-context?) check-context? (:check-context? config))
     :api-key (:api-key config)
     :base-url (:base-url config)
     :retry (:retry config)
     :pricing (:pricing config)
     :context-limits (:context-limits config)}))

;; =============================================================================
;; ask! - Main structured output function
;; =============================================================================

(defn- apply-spec-humanizer
  "Applies a humanizer function to spec fields marked with ::humanize? true.
   
   Walks the spec's ::fields, finds those with ::spec/humanize? true, and applies
   humanizer-fn to the corresponding string values in result. For fields with
   :spec.cardinality/many, applies to each string element.
   
   Params:
   `result` - Map. Parsed result from LLM.
   `spec-def` - Map. Spec definition with ::fields.
   `humanizer-fn` - Function. (fn [string]) -> humanized string.
   
   Returns:
   Map. Result with humanized string fields."
  [result spec-def humanizer-fn]
  (let [fields (::spec/fields spec-def)
        humanizable-fields (filter ::spec/humanize? fields)]
    (reduce
     (fn [acc field-def]
       (let [field-name (::spec/name field-def)
             ;; Get the simple key (strip namespace for lookup)
             simple-key (keyword (name field-name))
             cardinality (::spec/cardinality field-def)
             current-val (get acc simple-key)]
         (cond
           ;; Single string value
           (and (= cardinality :spec.cardinality/one)
                (string? current-val))
           (assoc acc simple-key (humanizer-fn current-val))

           ;; Many - vector of strings
           (and (= cardinality :spec.cardinality/many)
                (vector? current-val))
           (assoc acc simple-key
                  (mapv (fn [v] (if (string? v) (humanizer-fn v) v))
                        current-val))

           ;; Not a string or nil - leave unchanged
           :else acc)))
     result
     humanizable-fields)))

(defn ask!
  "Asks the LLM and returns structured Clojure data with token usage and cost.
   
   Includes automatic pre-flight context limit checking. If your input exceeds
   the model's context window, throws a clear error with actionable suggestions
   BEFORE making the API call.
   
   Supports multimodal input (images + text) for vision models.
   
   Params:
   `opts` - Map with keys:
     - :spec - Spec definition, required. Created with spec/spec and spec/field.
     - :objective - String, required. System instructions (spec format added automatically).
     - :task - String, required. The current task/prompt.
     - :images - Vector of maps, optional. For vision models. Each map has:
         - :base64 - String. Base64-encoded image data (without data URI prefix).
         - :media-type - String, optional. MIME type (default: \"image/png\").
     - :model - String, required. LLM model to use (e.g., \"gpt-4o\").
     - :config - Map, optional. LLM config from make-config with :api-key, :base-url.
     - :api-key - String, optional. API key (overrides :config).
     - :base-url - String, optional. Base URL (overrides :config).
     - :humanizer - Function, optional. A humanizer fn (fn [string] -> string) that is
         applied to spec fields marked with ::spec/humanize? true. Create one with
         (humanizer) or (humanizer {:aggressive? true}). Defaults to nil (no humanization).
     - :check-context? - Boolean, optional. Check context limit before API call. Defaults to true.
     - :timeout-ms - Integer, optional. HTTP request timeout in milliseconds. Defaults to 180000 (3 min).
   
   Returns:
   Map with keys:
     - :result - Clojure data matching the spec structure.
     - :tokens - Map with :input, :output, :total token counts.
     - :cost - Map with :input-cost, :output-cost, :total-cost in USD.
     - :duration-ms - Float. Total call duration in milliseconds.
   
   Throws:
   ExceptionInfo with :type :context/overflow if input exceeds model limit.
   ExceptionInfo with :type :svar/missing-api-key if no API key provided.
   
   Example:
   (ask! {:config config
          :spec my-spec
          :objective \"You are a helpful math tutor.\"
          :task \"What is 2+2?\"
          :model \"gpt-4o\"
          :humanizer (humanizer {:aggressive? true})})"
  [{:keys [spec objective task images humanizer] :as opts}]
  (let [{:keys [model api-key base-url timeout-ms check-context? retry pricing context-limits]} (resolve-opts opts)
        chat-url (str base-url "/chat/completions")
        image-count (count images)]
    (trove/log! {:level :info :data {:model model :task-len (count task) :images image-count} :msg "SVAR ask!"})
    (let [system-prompt (build-system-prompt objective)
          schema-prompt (spec/spec->prompt spec)
          task-content (build-user-content
                        (str "<current_task>\n" task "\n</current_task>")
                        images)
          messages [{:role "system" :content system-prompt}
                    {:role "user" :content task-content}
                    {:role "system" :content NUCLEUS_PROMPT}
                    {:role "user" :content schema-prompt}]
          ;; Pre-flight context check
          _ (when check-context?
              (let [check (tokens/check-context-limit model messages {:output-reserve 4096 :context-limits context-limits})]
                (when-not (:ok? check)
                   (trove/log! {:level :warn :data {:model model
                                                      :input-tokens (:input-tokens check)
                                                      :max (:max-input-tokens check)}
                                    :msg "Context overflow"})
                  (anomaly/incorrect! (:error check)
                                      {:type :context/overflow
                                       :model model
                                       :input-tokens (:input-tokens check)
                                       :max-input-tokens (:max-input-tokens check)
                                       :overflow (:overflow check)
                                       :utilization (:utilization check)
                                       :suggestion (str "Reduce task content by ~"
                                                        (int (* (double (:overflow check)) 0.75)) " words, "
                                                        "or use a larger context model.")}))))
          ;; API call
          call-start (System/nanoTime)
          retry-opts (merge retry {:timeout-ms timeout-ms})
          response (chat-completion messages model api-key chat-url retry-opts)
          duration-ms (/ (- (System/nanoTime) call-start) 1e6)
          ;; Token counting
          token-stats (tokens/count-and-estimate model messages response {:pricing pricing})
          ;; Parse response
          raw-result (try
                       (spec/str->data-with-spec response spec)
                       (catch Exception e
                          (trove/log! {:level :error :data {:model model :error (ex-message e)} :msg "Parse failed"})
                         (throw e)))
          ;; Apply spec-driven humanization if humanizer fn provided
          result (if humanizer
                   (apply-spec-humanizer raw-result spec humanizer)
                   raw-result)]
      (trove/log! {:level :info :data {:model model
                                       :duration-ms (int duration-ms)
                                       :tokens (:total-tokens token-stats)
                                       :cost (get-in token-stats [:cost :total-cost])}
                    :msg "SVAR complete"})
      {:result result
       :tokens {:input (:input-tokens token-stats)
                :output (:output-tokens token-stats)
                :total (:total-tokens token-stats)}
       :cost (select-keys (:cost token-stats) [:input-cost :output-cost :total-cost])
       :duration-ms duration-ms})))

;; =============================================================================
;; abstract! - Chain of Density summarization
;; =============================================================================

(def ^:private DEFAULT_ITERATIONS
  "Default number of Chain of Density iterations."
  5)

(def ^:private DEFAULT_TARGET_LENGTH
  "Default target length in words for Chain of Density summaries."
  80)

(def ^:private COD_ENTITY_TYPES
  "Shared XML definition of entity types for Chain of Density prompts."
  "<entity_types>
    <type name=\"person\">Named individuals, roles, or groups of people</type>
    <type name=\"organization\">Companies, institutions, agencies, or formal groups</type>
    <type name=\"location\">Geographic places, facilities, or spatial references</type>
    <type name=\"event\">Specific occurrences, actions, or happenings with temporal context</type>
    <type name=\"concept\">Abstract ideas, theories, methods, or technical terms</type>
    <type name=\"artifact\">Physical objects, products, tools, or created works</type>
    <type name=\"quantity\">Numbers, measurements, statistics, or amounts</type>
    <type name=\"temporal\">Dates, time periods, durations, or temporal references</type>
</entity_types>")

(def ^:private COD_ENTITY_CRITERIA
  "Shared XML definition of entity selection criteria."
  "<entity_criteria>
    <criterion name=\"relevant\">Related to the main story or central theme</criterion>
    <criterion name=\"specific\">Descriptive yet concise (5 words or fewer)</criterion>
    <criterion name=\"faithful\">Actually present in the source text</criterion>
</entity_criteria>")

(def ^:private COD_IMPORTANCE_SCALE
  "Shared XML definition of entity importance scoring."
  "<importance_scoring>
    <description>Rate each entity's importance to the core narrative from 0.0 to 1.0</description>
    <scale>
        <level range=\"0.8-1.0\">Critical - Essential to understanding the main point</level>
        <level range=\"0.6-0.8\">High - Significantly enhances comprehension</level>
        <level range=\"0.4-0.6\">Medium - Adds valuable context or detail</level>
        <level range=\"0.2-0.4\">Low - Minor supporting information</level>
        <level range=\"0.0-0.2\">Minimal - Tangential or background detail</level>
    </scale>
    <guideline>Prioritize entities with higher importance scores when space is limited</guideline>
</importance_scoring>")

(def ^:private COD_ENTITY_OUTPUT_FORMAT
  "Shared XML definition of entity output format."
  "<entity_format>
    <description>Each entity must include:</description>
    <field name=\"entity\">The entity text (5 words or fewer)</field>
    <field name=\"type\">One of: person, organization, location, event, concept, artifact, quantity, temporal</field>
    <field name=\"importance\">Float from 0.0 to 1.0 indicating relevance to core narrative</field>
</entity_format>")

(defn- build-cod-first-iteration-objective
  "Builds the Chain of Density objective for the first iteration (no previous summary)."
  [target-length special-instructions]
  (str "<chain_of_density_iteration>
    <task>Create the first sparse summary of the provided text.</task>
    
    <instructions>
        <instruction>Identify 1-3 key entities from the text to include</instruction>
        <instruction>Write a long summary (4-5 sentences, ~" target-length " words)</instruction>
        <instruction>Keep it highly non-specific with verbose fillers like \"this article discusses\"</instruction>
        <instruction>The summary should contain little specific information beyond the identified entities</instruction>
        <instruction>For each entity, classify its type and rate its importance to the narrative</instruction>
    </instructions>
    
    " COD_ENTITY_TYPES "
    
    " COD_ENTITY_CRITERIA "
    
    " COD_IMPORTANCE_SCALE "
    "
       (when special-instructions
         (str "
    <special_instructions>
        " special-instructions "
    </special_instructions>
    "))
       "
    <output_requirements>
        " COD_ENTITY_OUTPUT_FORMAT "
        <field name=\"entities\">List of 1-3 entity objects with entity, type, and importance</field>
        <field name=\"summary\">The initial sparse summary (~" target-length " words)</field>
    </output_requirements>
</chain_of_density_iteration>"))

(defn- build-cod-subsequent-iteration-objective
  "Builds the Chain of Density objective for subsequent iterations (has previous summary)."
  [target-length special-instructions]
  (str "<chain_of_density_iteration>
    <task>Create a denser version of the previous summary by incorporating missing entities.</task>
    
    <process>
        <step number=\"1\">Identify 1-3 informative entities from the SOURCE TEXT that are missing from the PREVIOUS SUMMARY</step>
        <step number=\"2\">Classify each entity by type and rate its importance to the narrative</step>
        <step number=\"3\">Rewrite the summary to incorporate these missing entities while maintaining the same word count</step>
    </process>
    
    " COD_ENTITY_TYPES "
    
    " COD_ENTITY_CRITERIA "
    <criterion name=\"novel\">Not present in the previous summary</criterion>
    
    " COD_IMPORTANCE_SCALE "
    
    <guidelines>
        <instruction>Make every word count by rewriting for better flow</instruction>
        <instruction>Create space through fusion, compression, and removing fillers</instruction>
        <instruction>The summary must be self-contained and understandable without the source</instruction>
        <instruction>Missing entities can appear anywhere in the new summary</instruction>
        <instruction>Never drop entities from the previous summary</instruction>
        <instruction>Prioritize adding higher-importance entities when space is limited</instruction>
        <instruction>If no space can be made, add fewer new entities rather than dropping old ones</instruction>
        <constraint>Maintain exactly the same word count (~" target-length " words)</constraint>
    </guidelines>
    "
       (when special-instructions
         (str "
    <special_instructions>
        " special-instructions "
    </special_instructions>
    "))
       "
    <output_requirements>
        " COD_ENTITY_OUTPUT_FORMAT "
        <field name=\"entities\">List of 1-3 NEW entity objects (with entity, type, importance) added in this iteration</field>
        <field name=\"summary\">The rewritten denser summary (~" target-length " words)</field>
    </output_requirements>
</chain_of_density_iteration>"))

(defn- build-cod-task
  "Builds the task content for a Chain of Density iteration."
  [source-text previous-summary]
  (if previous-summary
    (str "<source_text>\n" source-text "\n</source_text>\n\n"
         "<previous_summary>\n" previous-summary "\n</previous_summary>")
    (str "<source_text>\n" source-text "\n</source_text>")))

(defn- build-cod-spec
  "Builds the spec for a single Chain of Density iteration output."
  []
  (spec/spec
   (spec/field ::spec/name :entities
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/many
               ::spec/description "1-3 entities identified in this iteration")
   (spec/field ::spec/name :entities/entity
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "The entity text, 5 words or fewer")
   (spec/field ::spec/name :entities/type
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/values {"person" "A human being or character"
                              "organization" "Company, institution, or group"
                              "location" "Place, region, or geographic entity"
                              "event" "Occurrence, happening, or occasion"
                              "concept" "Abstract idea, principle, or theory"
                              "artifact" "Object, product, or creation"
                              "quantity" "Amount, number, or measurement"
                              "temporal" "Time, date, or duration"
                              "pattern" "Recurring structure or behavior"
                              "abbreviation" "Acronym or shortened form"
                              "category" "Classification or grouping"}
               ::spec/description "Entity type classification")
   (spec/field ::spec/name :entities/importance
               ::spec/type :spec.type/float
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Importance score from 0.0 to 1.0")
   (spec/field ::spec/name :summary
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "The summary for this iteration")))

(defn- cod-iteration-step
  "Performs a single Chain of Density iteration step."
  [source-text target-length model config special-instructions {:keys [iterations previous-summary] :as _state}]
  (let [first-iteration? (nil? previous-summary)
        objective (if first-iteration?
                    (build-cod-first-iteration-objective target-length special-instructions)
                    (build-cod-subsequent-iteration-objective target-length special-instructions))
        task (build-cod-task source-text previous-summary)
        {:keys [result]} (ask! {:spec (build-cod-spec)
                                :objective objective
                                :task task
                                :model model
                                :config config})]
    {:iterations (conj iterations result)
     :previous-summary (:summary result)}))

(defn abstract!
  "Creates a dense, entity-rich summary of text using Chain of Density prompting.
   
   Based on \"From Sparse to Dense: GPT-4 Summarization with Chain of Density Prompting\"
   (Adams et al., 2023). Iteratively refines a summary by identifying missing salient
   entities and incorporating them while maintaining a fixed length. Each iteration
   makes a separate LLM call, building on the previous summary.
   
   Params:
   `opts` - Map with keys:
     - :text - String, required. The text to summarize (article, conversation, etc.).
     - :model - String, required. LLM model to use (e.g., \"gpt-4o\").
     - :iterations - Integer, optional. Number of refinement iterations (default: 5).
     - :target-length - Integer, optional. Target summary length in words (default: 80).
     - :special-instructions - String, optional. Custom instructions to guide summarization.
     - :config - Map, optional. LLM config from make-config.
     - :api-key - String, optional. API key (overrides :config).
     - :base-url - String, optional. Base URL (overrides :config).
   
   Returns:
   Vector of maps. Each map represents one iteration with keys:
     - :entities - Vector of entity maps, each containing:
       - :entity - String. The entity text (5 words or fewer).
       - :type - String. Entity type.
       - :importance - Float. Score from 0.0 to 1.0.
     - :summary - String. The rewritten summary incorporating those entities.
   
   Example:
   (abstract! {:config config
               :text \"Long article content here...\"
               :model \"gpt-4o\"})"
  [{:keys [text iterations target-length special-instructions] :as opts
    :or {iterations DEFAULT_ITERATIONS
         target-length DEFAULT_TARGET_LENGTH}}]
  (let [{:keys [config model]} (resolve-opts opts)
        step-fn (partial cod-iteration-step text target-length model config special-instructions)
        initial-state {:iterations [] :previous-summary nil}
        final-state (->> initial-state
                         (iterate step-fn)
                         (drop 1)  ;; skip initial state
                         (take iterations)
                         last)]
    (:iterations final-state)))

;; =============================================================================
;; eval! - LLM Self-Evaluation
;; =============================================================================

(def ^:private EVAL_CRITERIA
  "Default evaluation criteria for LLM self-evaluation."
  {:accuracy "Does the output correctly answer the original task/question?"
   :completeness "Does the output fully address all aspects of the task?"
   :relevance "Is the output relevant and on-topic?"
   :coherence "Is the output well-structured and logically coherent?"
   :fairness "Is the output balanced, objective, and free from unfair treatment of any group or perspective?"
   :bias "INVERSE CRITERION - Score the AMOUNT of bias present: 0.0 = no bias (best), 1.0 = extreme bias (worst)."})

(defn- build-eval-spec
  "Builds the spec for evaluation output."
  [_criteria]
  (spec/spec
   ;; Top-level assessment
   (spec/field ::spec/name :overall-score
               ::spec/type :spec.type/float
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Overall quality score from 0.0 to 1.0, weighted average of criteria")
   (spec/field ::spec/name :correct?
               ::spec/type :spec.type/bool
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Whether the output is fundamentally correct (true/false)")
   (spec/field ::spec/name :summary
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Brief overall assessment summary")
   ;; Criteria evaluations as a vector
   (spec/field ::spec/name :criteria
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/many
               ::spec/description "Evaluation of each criterion")
   (spec/field ::spec/name :criteria/name
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Name of the criterion (e.g., accuracy, completeness)")
   (spec/field ::spec/name :criteria/score
               ::spec/type :spec.type/float
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Score from 0.0 to 1.0 for this criterion")
   (spec/field ::spec/name :criteria/confidence
               ::spec/type :spec.type/float
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Confidence in this score assessment from 0.0 to 1.0")
   (spec/field ::spec/name :criteria/reasoning
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Brief reasoning explaining the score")
   ;; Issues as a vector with full details
   (spec/field ::spec/name :issues
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/many
               ::spec/required false
               ::spec/description "List of issues found, empty array if none")
   (spec/field ::spec/name :issues/issue
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Description of the issue")
   (spec/field ::spec/name :issues/severity
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/values {"high" "Major issue that invalidates the output"
                              "medium" "Significant issue that degrades quality"
                              "low" "Minor issue that should be noted"}
               ::spec/description "Severity level of the issue")
   (spec/field ::spec/name :issues/confidence
               ::spec/type :spec.type/float
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Confidence in this issue assessment from 0.0 to 1.0")
   (spec/field ::spec/name :issues/reasoning
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Explanation of why this is an issue")
   (spec/field ::spec/name :issues/mitigation
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/required false
               ::spec/description "Optional suggestion for how to fix or mitigate this issue")))

(defn- build-eval-objective
  "Builds the evaluation objective prompt."
  [criteria ground-truths]
  (let [criteria-list (->> criteria
                           (map (fn [[k v]] (str "- " (name k) ": " v)))
                           (str/join "\n"))
        ground-truths-list (when (seq ground-truths)
                             (->> ground-truths
                                  (map-indexed (fn [i gt] (str "        <fact id=\"" (inc (long i)) "\">" gt "</fact>")))
                                  (str/join "\n")))]
    (str "<evaluation_task>
    <role>You are a rigorous evaluator assessing LLM outputs for quality and correctness.</role>
    
    <instructions>
        <instruction>Carefully analyze the ORIGINAL TASK and the OUTPUT to evaluate</instruction>
        <instruction>Score each criterion from 0.0 to 1.0 with honest, critical assessment</instruction>
        <instruction>For each criterion, provide a score, confidence (0.0-1.0), and reasoning</instruction>
        <instruction>Identify any issues with severity, confidence, reasoning, and optional mitigation</instruction>
        <instruction>Be skeptical - do not assume correctness without verification</instruction>
        <instruction>Consider edge cases and potential failure modes</instruction>
    </instructions>
    
    <evaluation_criteria>
        Evaluate the output against EACH of these criteria (include ALL in the criteria array):
" criteria-list "
    </evaluation_criteria>
    
    <scoring_guidelines>
        <guideline range=\"0.9-1.0\">Excellent - No issues, fully meets criterion</guideline>
        <guideline range=\"0.7-0.9\">Good - Minor issues, mostly meets criterion</guideline>
        <guideline range=\"0.5-0.7\">Acceptable - Some issues, partially meets criterion</guideline>
        <guideline range=\"0.3-0.5\">Poor - Significant issues, barely meets criterion</guideline>
        <guideline range=\"0.0-0.3\">Failing - Major issues, does not meet criterion</guideline>
    </scoring_guidelines>
    "
         (when ground-truths-list
           (str "
    <ground_truths>
        <note>Use these reference facts to verify correctness. The output should align with ALL of these.</note>
" ground-truths-list "
    </ground_truths>
    "))
         "
    <output_requirements>
        <requirement>Include an entry in 'criteria' array for EACH criterion listed above</requirement>
        <requirement>Calculate overall-score as weighted average of criteria scores</requirement>
        <requirement>Set correct? to false if ANY high-severity issue exists</requirement>
        <requirement>For each issue, include: issue description, severity, confidence, reasoning, and optionally a mitigation suggestion</requirement>
        <requirement>List issues from most to least severe (empty array if none)</requirement>
    </output_requirements>
</evaluation_task>")))

(defn- build-eval-task
  "Builds the evaluation task content."
  [original-task output context]
  (str "<original_task>\n" original-task "\n</original_task>\n\n"
       "<output_to_evaluate>\n" (if (string? output) output (pr-str output)) "\n</output_to_evaluate>"
       (when context
         (str "\n\n<additional_context>\n" context "\n</additional_context>"))))

(defn- build-scores
  "Builds scores map from criteria vector."
  [eval-result]
  (let [criteria (:criteria eval-result)
        ;; Build scores map from criteria vector
        criteria-scores (->> criteria
                             (map (fn [{:keys [name score]}]
                                    [(keyword name) score]))
                             (into {}))]
    (assoc criteria-scores :overall (:overall-score eval-result))))

(defn eval!
  "Evaluates an LLM output using LLM self-evaluation for reliability and accuracy.
   
   This function asks an LLM to critically evaluate a previous LLM output against
   specified criteria. Based on LLM Self-Evaluation techniques for improving reliability.
   
   Params:
   `opts` - Map with keys:
     - :task - String, required. The original task/prompt that generated the output.
     - :output - String or data, required. The LLM output to evaluate.
     - :model - String, required. LLM model to use for evaluation.
     - :criteria - Map, optional. Custom evaluation criteria as keyword->description map.
       Defaults to: accuracy, completeness, relevance, coherence, fairness, bias.
     - :ground-truths - Vector of strings, optional. Reference facts to verify correctness.
     - :context - String, optional. Additional context for evaluation.
      - :config - Map, optional. LLM config from make-config.
    
    Returns:
    Map with evaluation results:
      - :correct? - Boolean. Whether the output is fundamentally correct.
      - :overall-score - Float. Overall quality score from 0.0 to 1.0.
      - :summary - String. Brief overall assessment.
      - :criteria - Vector. Evaluation of each criterion.
      - :issues - Vector. Issues found.
      - :scores - Map. Consolidated scores by criterion name.
      - :eval-duration-ms - Float. Time taken for evaluation.
    
    Example:
    (eval! {:config config
            :task \"What is the capital of France?\"
            :output \"The capital of France is Paris.\"
            :model \"gpt-4o\"})"
  [{:keys [task output criteria ground-truths context]
    :as opts
    :or {criteria EVAL_CRITERIA}}]
  (let [{:keys [config model]} (resolve-opts opts)]
    (trove/log! {:level :info :data {:model model :criteria (count criteria)} :msg "SVAR eval"})
    (let [eval-start (System/nanoTime)
          eval-spec (build-eval-spec criteria)
          objective (build-eval-objective criteria ground-truths)
          eval-task (build-eval-task task output context)
          {:keys [result tokens cost]} (ask! {:spec eval-spec
                                              :objective objective
                                              :task eval-task
                                              :model model
                                              :config config})
          eval-duration-ms (/ (- (System/nanoTime) eval-start) 1e6)
          scores (build-scores result)
          final-result (-> result
                           (assoc :scores scores)
                           (assoc :eval-duration-ms eval-duration-ms)
                           (assoc :tokens tokens)
                           (assoc :cost cost))]
      (trove/log! {:level :info :data {:model model
                                            :correct? (:correct? result)
                                            :score (:overall-score result)
                                            :issues (count (:issues result))
                                            :duration-ms (int eval-duration-ms)}
                    :msg "SVAR eval complete"})
      final-result)))

;; =============================================================================
;; refine! - Iterative refinement with decomposition and verification
;; =============================================================================

(def ^:private DEFAULT_REFINE_ITERATIONS
  "Default number of refinement iterations."
  3)

(def ^:private DEFAULT_REFINE_THRESHOLD
  "Default quality threshold for early stopping."
  0.9)

;; Decomposition spec and functions
(defn- build-decomposition-spec
  "Builds the spec for decomposing output into verifiable claims."
  []
  (spec/spec
   (spec/field ::spec/name :claims
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/many
               ::spec/description "Verifiable claims or assertions extracted from the output")
   (spec/field ::spec/name :claims/claim
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "The specific claim or assertion text")
   (spec/field ::spec/name :claims/category
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/values {"factual" "Objective fact that can be independently verified"
                              "inference" "Logical conclusion derived from other information"
                              "subjective" "Opinion, judgment, or subjective assessment"}
               ::spec/description "Category of the claim")
   (spec/field ::spec/name :claims/confidence
               ::spec/type :spec.type/float
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Confidence that this claim is accurate (0.0 to 1.0)")
   (spec/field ::spec/name :claims/verifiable?
               ::spec/type :spec.type/bool
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Whether this claim can be independently verified")))

(defn- build-decomposition-objective
  "Builds the objective prompt for claim decomposition."
  [original-objective]
  (str "<decomposition_task>
    <role>You are an expert analyst who identifies and extracts verifiable claims from text.</role>
    
    <context>
        <original_objective>" original-objective "</original_objective>
    </context>
    
    <instructions>
        <instruction>Analyze the OUTPUT and extract all distinct claims or assertions</instruction>
        <instruction>Each claim should be a single, atomic statement that can be evaluated</instruction>
        <instruction>Categorize each claim as factual, inference, or subjective</instruction>
        <instruction>Rate your confidence in each claim's accuracy (0.0 to 1.0)</instruction>
        <instruction>Mark whether each claim can be independently verified</instruction>
        <instruction>Focus on claims that matter most to the original objective</instruction>
        <instruction>Include both explicit and implicit claims</instruction>
    </instructions>
    
    <claim_guidelines>
        <guideline>Factual claims: Statements about concrete facts, numbers, dates, names</guideline>
        <guideline>Inference claims: Conclusions, implications, or derived statements</guideline>
        <guideline>Subjective claims: Opinions, evaluations, or value judgments</guideline>
        <guideline>Prioritize claims that are critical to the output's correctness</guideline>
    </claim_guidelines>
    
    <output_requirements>
        <requirement>Extract 3-10 claims depending on output complexity</requirement>
        <requirement>Order claims by importance to the original objective</requirement>
        <requirement>Be thorough - missing claims means missing verification opportunities</requirement>
    </output_requirements>
</decomposition_task>"))

(defn- build-decomposition-task
  "Builds the task content for decomposition."
  [original-task output]
  (str "<original_task>\n" original-task "\n</original_task>\n\n"
       "<output_to_decompose>\n" (if (string? output) output (pr-str output)) "\n</output_to_decompose>"))

(defn- decompose-output
  "Extracts verifiable claims from an LLM output using DuTy-inspired decomposition."
  [output original-task original-objective model config]
  (:result (ask! {:spec (build-decomposition-spec)
                  :objective (build-decomposition-objective original-objective)
                  :task (build-decomposition-task original-task output)
                  :model model
                  :config config})))

;; Verification spec and functions
(defn- build-verification-spec
  "Builds the spec for verification of claims."
  [documents]
  (if (seq documents)
    (spec/spec
     (spec/field ::spec/name :verifications
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "Verification results for each claim")
     (spec/field ::spec/name :verifications/claim
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "The original claim being verified")
     (spec/field ::spec/name :verifications/question
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Verification question designed to test the claim")
     (spec/field ::spec/name :verifications/answer
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Answer to the verification question")
     (spec/field ::spec/name :verifications/verdict
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/values {"correct" "Claim is accurate as stated"
                                "incorrect" "Claim contains errors or inaccuracies"
                                "partially-correct" "Claim is partly true but needs refinement"
                                "uncertain" "Cannot determine accuracy with available information"}
                 ::spec/description "Verification verdict for the claim")
     (spec/field ::spec/name :verifications/reasoning
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Explanation of the verification reasoning")
     (spec/field ::spec/name :verifications/correction
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Suggested correction if claim is incorrect or partially correct")
     (spec/field ::spec/name :verifications/document-id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Document ID supporting or contradicting the claim")
     (spec/field ::spec/name :verifications/page
                 ::spec/type :spec.type/int
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Page number supporting or contradicting the claim")
     (spec/field ::spec/name :verifications/section
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Section supporting or contradicting the claim"))
    (spec/spec
     (spec/field ::spec/name :verifications
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "Verification results for each claim")
     (spec/field ::spec/name :verifications/claim
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "The original claim being verified")
     (spec/field ::spec/name :verifications/question
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Verification question designed to test the claim")
     (spec/field ::spec/name :verifications/answer
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Answer to the verification question")
     (spec/field ::spec/name :verifications/verdict
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/values {"correct" "Claim is accurate as stated"
                                "incorrect" "Claim contains errors or inaccuracies"
                                "partially-correct" "Claim is partly true but needs refinement"
                                "uncertain" "Cannot determine accuracy with available information"}
                 ::spec/description "Verification verdict for the claim")
     (spec/field ::spec/name :verifications/reasoning
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Explanation of the verification reasoning")
     (spec/field ::spec/name :verifications/correction
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Suggested correction if claim is incorrect or partially correct"))))

(defn- build-verification-objective
  "Builds the objective prompt for claim verification."
  [original-objective documents]
  (if (seq documents)
    (str "<verification_task>
    <role>You are a rigorous fact-checker who verifies claims through targeted questions.</role>
    
    <context>
        <original_objective>" original-objective "</original_objective>
    </context>
    
    <instructions>
        <instruction>For each claim, generate a specific verification question</instruction>
        <instruction>Answer the verification question independently and honestly</instruction>
        <instruction>Compare your answer against the original claim</instruction>
        <instruction>Determine if the claim is correct, incorrect, partially correct, or uncertain</instruction>
        <instruction>Provide clear reasoning for your verdict</instruction>
        <instruction>If incorrect or partially correct, suggest a specific correction</instruction>
        <instruction>You MUST verify claims against the provided source documents. For each claim, find the specific document, page, and section that supports or contradicts it.</instruction>
    </instructions>
    
    <verification_principles>
        <principle>Be skeptical - do not assume claims are correct</principle>
        <principle>Verification questions should be answerable independently</principle>
        <principle>Focus on the most critical aspects of each claim</principle>
        <principle>Consider edge cases and potential misinterpretations</principle>
        <principle>Mark uncertain when you genuinely cannot verify</principle>
    </verification_principles>
    
    <verdict_guidelines>
        <guideline verdict=\"correct\">Claim fully accurate, no changes needed</guideline>
        <guideline verdict=\"incorrect\">Claim contains clear errors that must be fixed</guideline>
        <guideline verdict=\"partially-correct\">Claim has merit but needs refinement or clarification</guideline>
        <guideline verdict=\"uncertain\">Insufficient information to verify - flag for review</guideline>
    </verdict_guidelines>
</verification_task>")
    (str "<verification_task>
    <role>You are a rigorous fact-checker who verifies claims through targeted questions.</role>
    
    <context>
        <original_objective>" original-objective "</original_objective>
    </context>
    
    <instructions>
        <instruction>For each claim, generate a specific verification question</instruction>
        <instruction>Answer the verification question independently and honestly</instruction>
        <instruction>Compare your answer against the original claim</instruction>
        <instruction>Determine if the claim is correct, incorrect, partially correct, or uncertain</instruction>
        <instruction>Provide clear reasoning for your verdict</instruction>
        <instruction>If incorrect or partially correct, suggest a specific correction</instruction>
    </instructions>
    
    <verification_principles>
        <principle>Be skeptical - do not assume claims are correct</principle>
        <principle>Verification questions should be answerable independently</principle>
        <principle>Focus on the most critical aspects of each claim</principle>
        <principle>Consider edge cases and potential misinterpretations</principle>
        <principle>Mark uncertain when you genuinely cannot verify</principle>
    </verification_principles>
    
    <verdict_guidelines>
        <guideline verdict=\"correct\">Claim fully accurate, no changes needed</guideline>
        <guideline verdict=\"incorrect\">Claim contains clear errors that must be fixed</guideline>
        <guideline verdict=\"partially-correct\">Claim has merit but needs refinement or clarification</guideline>
        <guideline verdict=\"uncertain\">Insufficient information to verify - flag for review</guideline>
    </verdict_guidelines>
</verification_task>")))

(defn- format-claims-for-verification
  "Formats claims into a string for the verification task."
  [claims]
  (->> claims
       (map-indexed (fn [i claim]
                      (str "<claim id=\"" (inc (long i)) "\">\n"
                           "  <text>" (:claim claim) "</text>\n"
                           "  <category>" (:category claim) "</category>\n"
                           "  <confidence>" (:confidence claim) "</confidence>\n"
                           "</claim>")))
       (str/join "\n")))

(def ^:private MAX_DOCUMENT_CONTENT_CHARS 16000)

(defn- truncate-document-pages
  [pages remaining]
  (loop [pages pages
         remaining (long remaining)
         acc []]
    (if (or (empty? pages) (<= remaining 0))
      [acc remaining]
      (let [{:keys [page text]} (first pages)
            text (or text "")
            trimmed (if (> (long (count text)) remaining)
                      (subs text 0 (int remaining))
                      text)
            remaining' (- remaining (long (count trimmed)))
            acc' (conj acc {:page page :text trimmed})]
        (recur (rest pages) remaining' acc')))))

(defn- truncate-documents
  [documents max-chars]
  (loop [documents documents
         remaining (long max-chars)
         acc []]
    (if (or (empty? documents) (<= remaining 0))
      acc
      (let [doc (first documents)
            [pages remaining'] (truncate-document-pages (:pages doc) remaining)
            acc' (conj acc (assoc doc :pages pages))]
        (recur (rest documents) remaining' acc')))))

(defn- build-source-documents-block
  [documents]
  (when (seq documents)
    (let [truncated-documents (truncate-documents documents MAX_DOCUMENT_CONTENT_CHARS)]
      (str "<source_documents>\n"
           (str/join "\n"
                     (map (fn [{:keys [id pages]}]
                            (str "  <document id=\"" id "\">\n"
                                 (str/join "\n"
                                           (map (fn [{:keys [page text]}]
                                                  (str "    <page number=\"" page "\">" text "</page>"))
                                                pages))
                                 "\n  </document>"))
                          truncated-documents))
           "\n</source_documents>"))))

(defn- build-verification-task
  "Builds the task content for verification."
  [original-task claims documents]
  (str "<original_task>\n" original-task "\n</original_task>\n\n"
       "<claims_to_verify>\n" (format-claims-for-verification claims) "\n</claims_to_verify>"
       (when (seq documents)
         (str "\n\n" (build-source-documents-block documents)))))

(defn- verify-claims
  "Verifies extracted claims using CoVe-inspired verification questions."
  [claims original-task original-objective model config documents]
  (:result (ask! {:spec (build-verification-spec documents)
                  :objective (build-verification-objective original-objective documents)
                  :task (build-verification-task original-task claims documents)
                  :model model
                  :config config})))

;; Refinement functions
(defn- format-verifications-for-refinement
  "Formats verification results into feedback for refinement."
  [verifications]
  (->> verifications
       (map-indexed (fn [i v]
                      (str "<verification id=\"" (inc (long i)) "\">\n"
                           "  <claim>" (:claim v) "</claim>\n"
                           "  <verdict>" (:verdict v) "</verdict>\n"
                           "  <reasoning>" (:reasoning v) "</reasoning>\n"
                           (when (:correction v)
                             (str "  <correction>" (:correction v) "</correction>\n"))
                           "</verification>")))
       (str/join "\n")))

(defn- format-issues-for-refinement
  "Formats evaluation issues into feedback for refinement."
  [issues]
  (if (empty? issues)
    "<issues>None identified</issues>"
    (->> issues
         (map-indexed (fn [i issue]
                        (str "<issue id=\"" (inc (long i)) "\" severity=\"" (:severity issue) "\">\n"
                             "  <description>" (:issue issue) "</description>\n"
                             "  <reasoning>" (:reasoning issue) "</reasoning>\n"
                             (when (:mitigation issue)
                               (str "  <mitigation>" (:mitigation issue) "</mitigation>\n"))
                             "</issue>")))
         (str/join "\n"))))

(defn- build-refinement-objective
  "Builds the objective prompt for output refinement."
  [original-objective iteration]
  (str "<refinement_task>
    <role>You are an expert editor who improves outputs based on verification feedback.</role>
    
    <context>
        <original_objective>" original-objective "</original_objective>
        <iteration>" iteration "</iteration>
    </context>
    
    <instructions>
        <instruction>Review the ORIGINAL OUTPUT and the VERIFICATION FEEDBACK</instruction>
        <instruction>Address ALL issues marked as incorrect or partially-correct</instruction>
        <instruction>Apply suggested corrections where provided</instruction>
        <instruction>Maintain all aspects that were verified as correct</instruction>
        <instruction>Improve clarity and accuracy without changing correct content</instruction>
        <instruction>Ensure the refined output fully addresses the original task</instruction>
    </instructions>
    
    <refinement_principles>
        <principle>Prioritize fixing high-severity issues first</principle>
        <principle>Do not introduce new errors while fixing existing ones</principle>
        <principle>Preserve the overall structure and intent of the original output</principle>
        <principle>Be conservative - only change what needs to be changed</principle>
        <principle>Ensure corrections are factually accurate</principle>
    </refinement_principles>
</refinement_task>"))

(defn- build-refinement-task
  "Builds the task content for refinement with full context."
  [original-task current-output verifications evaluation-issues]
  (str "<original_task>\n" original-task "\n</original_task>\n\n"
       "<current_output>\n" (if (string? current-output) current-output (pr-str current-output)) "\n</current_output>\n\n"
       "<verification_feedback>\n" (format-verifications-for-refinement verifications) "\n</verification_feedback>\n\n"
       "<evaluation_issues>\n" (format-issues-for-refinement evaluation-issues) "\n</evaluation_issues>\n\n"
       "<instruction>Generate a refined version of the output that addresses the verification feedback and evaluation issues while maintaining correct content.</instruction>"))

(defn- should-stop?
  "Determines if refinement should stop based on strategy and current state."
  [stop-strategy threshold current-score current-iteration max-iterations]
  (let [current-score (double current-score)
        threshold (double threshold)
        current-iteration (long current-iteration)
        max-iterations (long max-iterations)]
    (case stop-strategy
      :threshold (>= current-score threshold)
      :fixed (>= current-iteration max-iterations)
      :both (or (>= current-score threshold)
                (>= current-iteration max-iterations))
      ;; Default to :both behavior
      (or (>= current-score threshold)
          (>= current-iteration max-iterations)))))

(defn- refinement-iteration-step
  "Performs a single refinement iteration: decompose -> verify -> evaluate -> refine."
  [spec original-objective original-task model config eval-criteria documents
   {:keys [current-output iterations iteration-num prompt-evolution] :as _state}]
  (let [iter-start (System/nanoTime)
        iteration (inc (long iteration-num))
        _ (trove/log! {:level :info :data {:n iteration} :msg "Refine iteration"})

        ;; Step 1: Decompose - extract claims from current output
        decomposition (decompose-output current-output original-task original-objective
                                        model config)
        claims (:claims decomposition)

        ;; Step 2: Verify - check claims with verification questions
        verification (verify-claims claims original-task original-objective model config documents)
        verifications (:verifications verification)

        ;; Step 3: Evaluate - get quality assessment
        evaluation (eval! {:task original-task
                           :output current-output
                           :model model
                           :criteria eval-criteria
                           :config config})

        ;; Step 4: Refine - generate improved output
        refinement-objective (build-refinement-objective original-objective iteration)
        refinement-task (build-refinement-task original-task current-output
                                               verifications (:issues evaluation))
        {:keys [result]} (ask! {:spec spec
                                :objective refinement-objective
                                :task refinement-task
                                :model model
                                :config config})
        refined-output result

        iter-duration-ms (/ (- (System/nanoTime) iter-start) 1e6)

        ;; Build iteration record
        incorrect-count (->> verifications (filter #(= "incorrect" (:verdict %))) count)
        iteration-record {:iteration iteration
                          :output current-output
                          :claims claims
                          :verification {:verifications verifications
                                         :incorrect-count incorrect-count
                                         :uncertain-count (->> verifications
                                                               (filter #(= "uncertain" (:verdict %)))
                                                               count)}
                          :evaluation evaluation
                          :refinements (->> verifications
                                            (filter #(contains? #{"incorrect" "partially-correct"} (:verdict %)))
                                            (mapv #(select-keys % [:claim :verdict :correction])))
                          :duration-ms iter-duration-ms}

        ;; Track prompt evolution
        prompt-record {:objective refinement-objective
                       :task refinement-task
                       :iteration iteration}]

    (trove/log! {:level :info :data {:n iteration
                                             :claims (count claims)
                                             :incorrect incorrect-count
                                             :score (:overall-score evaluation)
                                             :duration-ms (int iter-duration-ms)}
                  :msg "Refine iteration done"})

    {:current-output refined-output
     :iterations (conj iterations iteration-record)
     :iteration-num iteration
     :latest-score (:overall-score evaluation)
     :prompt-evolution (conj prompt-evolution prompt-record)}))

(defn- calculate-deltas
  "Compute score differences between adjacent iterations."
  [scores]
  (if (<= (long (count scores)) 1)
    []
    (mapv #(- (double %1) (double %2)) (rest scores) scores)))

(defn- determine-trend
  "Analyze deltas to determine improvement trend."
  [deltas]
  (if (empty? deltas)
    :stable
    (let [threshold 0.01
          all-positive? (every? #(> (double %) threshold) deltas)
          all-negative? (every? #(< (double %) (- threshold)) deltas)]
      (cond
        all-positive? :improving
        all-negative? :declining
        :else :stable))))

(defn- compute-gradient
  "Compute gradient map from a vector of scores."
  [scores]
  (let [deltas (calculate-deltas scores)
        trend (determine-trend deltas)
        total (if (empty? scores) 0.0 (- (double (last scores)) (double (first scores))))]
    {:deltas deltas
     :trend trend
     :total total}))

(defn refine!
  "Iteratively refines LLM output using decomposition and verification.
   
   Combines DuTy (Duty-Distinct Chain-of-Thought) decomposition with 
   Chain of Verification (CoVe) to reduce hallucinations and improve accuracy.
   Each iteration: decomposes output into claims, verifies each claim,
   evaluates overall quality, and generates a refined version.
   
   Params:
   `opts` - Map with keys:
     - :spec - Spec definition, required. Created with spec/spec and spec/field.
     - :objective - String, required. System instructions for the task.
     - :task - String, required. The task/prompt to refine.
     - :model - String, required. LLM model to use.
     - :iterations - Integer, optional. Max refinement iterations (default: 3).
     - :threshold - Float, optional. Stop early if eval score >= threshold (default: 0.9).
     - :stop-strategy - Keyword, optional. Stopping strategy (default: :both).
     - :window-size - Integer, optional. Number of recent scores to keep (default: 3).
     - :criteria - Map, optional. Custom evaluation criteria for eval!.
      - :config - Map, optional. LLM config from make-config.
    
    Returns:
    Map with refinement results:
      - :result - Map. Final refined output matching the spec structure.
      - :iterations - Vector. Full history of each iteration.
      - :final-score - Float. Final evaluation overall-score.
      - :converged? - Boolean. Whether threshold was reached.
      - :iterations-count - Integer. Total iterations performed.
      - :total-duration-ms - Float. Total time for all operations.
      - :gradient - Map. Gradient metrics.
      - :prompt-evolution - Vector. Prompt history.
      - :window - Map. Sliding window of recent scores.
    
    Example:
    (refine! {:config config
              :spec my-spec
              :objective \"Analyze the data accurately.\"
              :task \"Summarize key findings.\"
              :model \"gpt-4o\"})"
  [{:keys [spec objective task iterations threshold stop-strategy
           window-size criteria documents]
    :as opts
    :or {iterations DEFAULT_REFINE_ITERATIONS
         threshold DEFAULT_REFINE_THRESHOLD
         stop-strategy :both
         window-size 3
         criteria EVAL_CRITERIA}}]
  (let [{:keys [config model]} (resolve-opts opts)
        iterations (if (seq documents) 1 iterations)]
     (trove/log! {:level :info :data {:model model :max-iters iterations :threshold threshold} :msg "SVAR refine"})
    (let [total-start (System/nanoTime)

          ;; Phase 1: Generate initial output
          {:keys [result]} (ask! {:spec spec
                                  :objective objective
                                  :task task
                                  :model model
                                  :config config})
          initial-output result

          ;; Phase 2: Iterative refinement loop
          initial-state {:current-output initial-output
                         :iterations []
                         :iteration-num 0
                         :latest-score 0.0
                         :prompt-evolution []}

          step-fn (partial refinement-iteration-step
                           spec objective task model config criteria documents)

          ;; Run iterations until stopping condition met
          final-state (loop [state initial-state]
                        (if (should-stop? stop-strategy threshold
                                          (:latest-score state)
                                          (:iteration-num state)
                                          iterations)
                          state
                          (recur (step-fn state))))

          ;; Phase 3: Final evaluation
          final-output (:current-output final-state)
          final-evaluation (eval! {:task task
                                   :output final-output
                                   :model model
                                   :criteria criteria
                                   :config config})
          final-score (:overall-score final-evaluation)

          ;; Phase 4: Compute gradient and window
          all-scores (conj (mapv #(get-in % [:evaluation :overall-score]) (:iterations final-state))
                           final-score)
          gradient (compute-gradient all-scores)
          window-scores (vec (take-last window-size all-scores))

          total-duration-ms (/ (- (System/nanoTime) total-start) 1e6)
          iterations-count (:iteration-num final-state)
          converged? (>= (double final-score) (double threshold))]

      (trove/log! {:level :info :data {:iterations iterations-count
                                          :score final-score
                                          :converged? converged?
                                          :trend (:trend gradient)
                                          :duration-ms (int total-duration-ms)}
                    :msg "SVAR refine done"})

      {:result final-output
       :iterations (:iterations final-state)
       :final-evaluation final-evaluation
       :final-score final-score
       :converged? converged?
       :iterations-count iterations-count
       :total-duration-ms total-duration-ms
       :gradient gradient
       :prompt-evolution (:prompt-evolution final-state)
       :window {:size window-size
                :scores window-scores}})))

;; =============================================================================
;; models! - Fetch available models
;; =============================================================================

(defn models!
  "Fetches available models from the LLM API.
   
   Queries the /models endpoint to retrieve the list of models supported
   by the configured LLM provider.
   
    Params:
    `opts` - Map with keys:
      - :config - Map, optional. LLM config from make-config.
    
    Returns:
    Vector of model maps. Each map contains at least:
      - :id - String. Model identifier (e.g., \"gpt-4o\", \"claude-3-5-sonnet\").
      - Other fields vary by provider (created, owned_by, etc.).
    
    Example:
    (models! {:config config})"
  ([] (models! {}))
  ([opts]
   (let [{:keys [api-key base-url]} (resolve-opts opts)
         models-url (str base-url "/models")
          _ (trove/log! {:level :info :data {:url models-url} :msg "Fetching models"})
         body (http-get! models-url api-key)
         models (or (:data body) [])]
     (trove/log! {:level :info :data {:count (count models)} :msg "Models fetched"})
     (vec models))))

;; =============================================================================
;; sample! - Generate test data samples
;; =============================================================================

(defn sample!
  "Generates test data samples matching a spec with criteria evaluation.
   
   Uses the LLM to generate N items that conform to the provided spec,
   then evaluates the generated samples against criteria to assess quality.
   
   Params:
   `opts` - Map with keys:
     - :spec - Spec definition, required. Created with spec/spec and spec/field.
     - :count - Integer, required. Number of samples to generate.
     - :criteria - Map, optional. Evaluation criteria (default: EVAL_CRITERIA).
     - :model - String, required. LLM model to use.
      - :config - Map, optional. LLM config from make-config.
    
    Returns:
    Map with generation results:
      - :samples - Vector. Generated items matching the spec structure.
      - :criteria-scores - Map. Scores for each criterion from eval!.
      - :generation-duration-ms - Float. Total time for generation and evaluation.
    
    Example:
    (sample! {:config config
              :spec user-spec
              :count 5
              :model \"gpt-4o\"})"
  [{:keys [spec criteria]
    :as opts
    n :count
    :or {criteria EVAL_CRITERIA}}]
  (if (zero? (long n))
    {:samples []
     :criteria-scores {}
     :generation-duration-ms 0.0}

    (let [{:keys [config model]} (resolve-opts opts)
          _ (trove/log! {:level :info :data {:model model :count n} :msg "SVAR sample"})
          gen-start (System/nanoTime)

          ;; Build objective for sample generation
          objective (str "You are a test data generator. Generate realistic, diverse samples "
                         "that match the provided specification. Ensure variety and quality.")

          ;; Build task requesting N samples
          task (str "Generate exactly " n " sample items. "
                    "Each item should be unique and realistic. "
                    "Ensure diversity across all samples.")

          ;; Create a spec for the response that wraps individual items
          item-spec (assoc spec ::spec/spec-name :Item)
          items-spec (spec/spec
                      {:refs [item-spec]}
                      (spec/field ::spec/name :items
                                  ::spec/type :spec.type/ref
                                  ::spec/cardinality :spec.cardinality/many
                                  ::spec/description "Array of generated samples"
                                  ::spec/target :Item))

          ;; Generate samples using ask!
          {:keys [result]} (ask! {:spec items-spec
                                  :objective objective
                                  :task task
                                  :model model
                                  :config config})
          samples (vec (:items result))

          ;; Evaluate the generated samples against criteria
          evaluation (eval! {:task task
                             :output result
                             :model model
                             :criteria criteria
                             :config config})

          gen-duration-ms (/ (- (System/nanoTime) gen-start) 1e6)]

      (trove/log! {:level :info :data {:count (count samples)
                                          :duration-ms (int gen-duration-ms)}
                    :msg "SVAR sample done"})

      {:samples samples
       :criteria-scores (:scores evaluation)
       :generation-duration-ms gen-duration-ms})))
