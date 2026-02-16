(ns com.blockether.svar.internal.llm
  "LLM client layer: HTTP transport, message construction, and all LLM interaction
   functions (ask!, abstract!, eval!, refine!, models!, sample!).

   Extracted from svar.core to break the cyclic dependency between core and rlm.
   rlm.clj requires this namespace directly instead of svar.core."
  (:require
   [babashka.http-client :as http]
   [charred.api :as json]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.internal.config :as config]
   [com.blockether.svar.internal.spec :as spec]
   [com.blockether.svar.internal.tokens :as tokens]
   [com.blockether.svar.internal.util :as util]
   [taoensso.trove :as trove]))

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
                          (cond-> (merge (ex-data e) {:type :svar.core/http-error
                                                      :llm-request sanitized-request})
                            api-key-error (assoc :api-key-error api-key-error))))))))

;; PUBLIC — rlm.clj accesses this directly
(defn chat-completion
  "Calls the LLM API (OpenAI compatible) with the given messages."
  ([messages model api-key base-url]
   (chat-completion messages model api-key base-url {}))
  ([messages model api-key base-url retry-opts]
   (let [timeout-ms (get retry-opts :timeout-ms config/DEFAULT_TIMEOUT_MS)]
     (chat-completion-with-retry
      messages model api-key base-url retry-opts timeout-ms))))

(defn- build-system-prompt
  "Builds the system prompt with the objective wrapped in XML tags."
  [objective]
  (str "<objective>\n" objective "\n</objective>"))

(defn- url? [s] (or (str/starts-with? s "http://") (str/starts-with? s "https://")))

(defn- build-user-content
  "Builds user message content, supporting both text-only and multimodal formats."
  [text images]
  (if (empty? images)
    ;; Text-only: return plain string
    text
    ;; Multimodal: build content array with images first, then text
    (let [image-blocks (mapv (fn [{:keys [url base64 media-type] :or {media-type "image/png"}}]
                               {:type "image_url"
                                :image_url {:url (if url
                                                   url
                                                   (str "data:" media-type ";base64," base64))}})
                             images)
          text-block {:type "text" :text text}]
      (conj image-blocks text-block))))

;; =============================================================================
;; Message Construction Helpers (Public API)
;; =============================================================================

(defn image
  "Creates an image attachment for use with `user` messages.
   
   Accepts either base64-encoded image data or an HTTP(S) URL.
   URLs are passed through directly to the LLM API; base64 strings
   are wrapped in a data URI with the given media-type.
   
   Params:
   `source` - String. Base64-encoded image data or an image URL (http/https).
   `media-type` - String, optional. MIME type (default: \"image/png\").
                  Ignored when source is a URL.
   
   Returns:
   Map marker that `user` recognizes and converts to multimodal content.
   When source is a URL, returns {:svar/type :image :url \"...\"}.
   When source is base64, returns {:svar/type :image :base64 \"...\" :media-type \"...\"}."
  ([source]
   (image source "image/png"))
  ([source media-type]
   (if (url? source)
     {:svar/type :image :url source}
     {:svar/type :image :base64 source :media-type media-type})))

(defn system
  "Creates a system message.
   
   Params:
   `content` - String. System instructions / objective.
   
   Returns:
   Message map with :role \"system\"."
  [content]
  {:role "system" :content content})

(defn user
  "Creates a user message, optionally with images for multimodal models.
   
   Params:
   `content` - String. The user's message text.
   `images` - Zero or more image maps created with `image`."
  [content & images]
  (if (seq images)
    {:role "user"
      :content (build-user-content content (mapv #(select-keys % [:url :base64 :media-type]) images))}
    {:role "user" :content content}))

(defn assistant
  "Creates an assistant message (for few-shot examples or conversation history).
   
   Params:
   `content` - String. The assistant's response.
   
   Returns:
   Message map with :role \"assistant\"."
  [content]
  {:role "assistant" :content content})

;; =============================================================================
;; Config Resolution Helper
;; =============================================================================

(defn- resolve-opts
  "Extracts effective config values from opts, falling back to config defaults.
   If no :config provided, creates one from env vars."
  [{:keys [config model timeout-ms check-context? output-reserve]}]
  (let [config (or config (config/make-config))
        {:keys [network tokens]} config]
    {:config config
     :model (or model (:model config))
     :timeout-ms (or timeout-ms (:timeout-ms network))
     :check-context? (if (some? check-context?) check-context? (:check-context? tokens))
     :output-reserve (or output-reserve (:output-reserve tokens))
     :api-key (:api-key config)
     :base-url (:base-url config)
     :network network
     :pricing (:pricing tokens)
     :context-limits (:context-limits tokens)}))

;; =============================================================================
;; ask! - Main structured output function
;; =============================================================================

(defn- apply-spec-humanizer
  "Applies a humanizer function to spec fields marked with ::humanize? true."
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
   
   Supports multimodal input via the `user` + `image` helpers.
   
   Params:
   `opts` - Map with keys:
     - :spec - Spec definition, required.
     - :messages - Vector of message maps, required.
     - :model - String, required. LLM model to use.
     - :config - Map, optional. LLM config from make-config.
     - :humanizer - Function, optional. Applied to ::spec/humanize? fields.
     - :output-reserve - Integer, optional.
     - :check-context? - Boolean, optional.
     - :timeout-ms - Integer, optional.
   
   Returns:
   Map with :result, :tokens, :cost, :duration-ms."
  [{:keys [spec messages humanizer] :as opts}]
  (let [{:keys [model api-key base-url timeout-ms check-context? output-reserve network pricing context-limits]} (resolve-opts opts)
        chat-url (str base-url "/chat/completions")
        schema-prompt (spec/spec->prompt spec)
        ;; Process messages: wrap system content with build-system-prompt
        processed-msgs (mapv (fn [{:keys [role content] :as msg}]
                               (if (= role "system")
                                 (assoc msg :content (build-system-prompt content))
                                 msg))
                             messages)
        ;; Append schema prompt as final user message
        messages (conj processed-msgs {:role "user" :content schema-prompt})
          ;; Pre-flight context check (also counts input tokens for reuse)
          check-opts (cond-> {:context-limits context-limits}
                       output-reserve (assoc :output-reserve output-reserve))
          context-check (when check-context?
                          (let [check (tokens/check-context-limit model messages check-opts)]
                            (when-not (:ok? check)
                              (anomaly/incorrect! (:error check)
                                                  {:type :svar.core/context-overflow
                                                   :model model
                                                   :input-tokens (:input-tokens check)
                                                   :max-input-tokens (:max-input-tokens check)
                                                   :overflow (:overflow check)
                                                   :utilization (:utilization check)
                                                   :suggestion (str "Reduce task content by ~"
                                                                    (int (* (double (:overflow check)) 0.75)) " words, "
                                                                    "or use a larger context model.")}))
                            check))
          ;; API call
          retry-opts (merge network {:timeout-ms timeout-ms})
          [response duration-ms] (util/with-elapsed
                                   (chat-completion messages model api-key chat-url retry-opts))
          ;; Token counting — reuse pre-counted input tokens when available
          token-stats (tokens/count-and-estimate model messages response
                                                 (cond-> {:pricing pricing}
                                                   context-check (assoc :input-tokens (:input-tokens context-check))))
          ;; Parse response
          raw-result (spec/str->data-with-spec response spec)
          ;; Apply spec-driven humanization if humanizer fn provided
          result (if humanizer
                   (apply-spec-humanizer raw-result spec humanizer)
                   raw-result)]
      {:result result
       :tokens {:input (:input-tokens token-stats)
                :output (:output-tokens token-stats)
                :total (:total-tokens token-stats)}
       :cost (select-keys (:cost token-stats) [:input-cost :output-cost :total-cost])
       :duration-ms duration-ms}))

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
                                :messages [(system objective)
                                           (user task)]
                                :model model
                                :config config})]
    {:iterations (conj iterations result)
     :previous-summary (:summary result)}))

(defn abstract!
  "Creates a dense, entity-rich summary of text using Chain of Density prompting.
   
   Based on \"From Sparse to Dense: GPT-4 Summarization with Chain of Density Prompting\"
   (Adams et al., 2023). Iteratively refines a summary by identifying missing salient
   entities and incorporating them while maintaining a fixed length."
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
  "Evaluates an LLM output using LLM self-evaluation for reliability and accuracy."
  [{:keys [task output messages criteria ground-truths context]
    :as opts
    :or {criteria EVAL_CRITERIA}}]
  (let [{:keys [config model]} (resolve-opts opts)
        ;; Resolve task: explicit :task wins, else extract from :messages
        effective-task (or task
                          (when messages
                            (->> messages
                                 (remove #(= "assistant" (:role %)))
                                 (map :content)
                                 (str/join "\n")))
                          "")
        eval-spec (build-eval-spec criteria)
        objective (build-eval-objective criteria ground-truths)
        eval-task (build-eval-task effective-task output context)
        [{:keys [result tokens cost]} duration-ms]
        (util/with-elapsed
          (ask! {:spec eval-spec
                 :messages [(system objective)
                            (user eval-task)]
                 :model model
                 :config config}))
        scores (build-scores result)]
    (assoc result
           :scores scores
           :duration-ms duration-ms
           :tokens tokens
           :cost cost)))

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
                   :messages [(system (build-decomposition-objective original-objective))
                              (user (build-decomposition-task original-task output))]
                   :model model
                   :config config})))

;; Verification helper functions (truncation, source documents, claim formatting)

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

;; =============================================================================
;; Factored CoVe: Question Planning + Independent Per-Claim Verification
;; =============================================================================

(defn- build-question-planning-spec
  "Builds the spec for generating verification questions (without answers)."
  []
  (spec/spec
   (spec/field ::spec/name :questions
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/many
               ::spec/description "Verification questions, one per claim")
   (spec/field ::spec/name :questions/claim
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "The original claim text being verified")
   (spec/field ::spec/name :questions/question
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "A targeted verification question to test this claim")))

(defn- build-question-planning-objective
  "Builds the system prompt for verification question planning."
  [original-objective]
  (str "<question_planning_task>
    <role>You are an expert fact-checker who designs targeted verification questions.</role>
    
    <context>
        <original_objective>" original-objective "</original_objective>
    </context>
    
    <instructions>
        <instruction>For each claim, generate ONE specific verification question</instruction>
        <instruction>The question should be answerable independently without seeing the original output</instruction>
        <instruction>Questions should test the factual accuracy of the claim</instruction>
        <instruction>Do NOT answer the questions - only generate them</instruction>
    </instructions>
    
    <question_guidelines>
        <guideline>Questions should be specific and testable</guideline>
        <guideline>Avoid yes/no questions - prefer questions that require detailed answers</guideline>
        <guideline>Each question should focus on the most critical aspect of its claim</guideline>
        <guideline>Questions should be self-contained - understandable without additional context</guideline>
    </question_guidelines>
</question_planning_task>"))

(defn- build-question-planning-task
  "Builds the task content for verification question planning."
  [original-task claims]
  (str "<original_task>\n" original-task "\n</original_task>\n\n"
       "<claims_to_plan_questions_for>\n" (format-claims-for-verification claims) "\n</claims_to_plan_questions_for>"))

(defn- plan-verification-questions
  "Step 1 of Factored CoVe: Generate verification questions without answers."
  [claims original-task original-objective model config]
  (:result (ask! {:spec (build-question-planning-spec)
                  :messages [(system (build-question-planning-objective original-objective))
                             (user (build-question-planning-task original-task claims))]
                  :model model
                  :config config})))

(defn- build-single-verification-spec
  "Builds the spec for independently verifying a single claim."
  [has-documents?]
  (if has-documents?
    (spec/spec
     (spec/field ::spec/name :answer
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Your independent answer to the verification question")
     (spec/field ::spec/name :verdict
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/values {"correct" "The claim is accurate based on your independent answer"
                                "incorrect" "The claim contradicts your independent answer"
                                "partially-correct" "The claim is partly accurate but needs refinement"
                                "uncertain" "Cannot determine accuracy with available information"}
                 ::spec/description "Verdict comparing your answer against the original claim")
     (spec/field ::spec/name :reasoning
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Explanation of your reasoning")
     (spec/field ::spec/name :correction
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Suggested correction if claim is incorrect or partially correct")
     (spec/field ::spec/name :document-id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Document ID supporting or contradicting the claim")
     (spec/field ::spec/name :page
                 ::spec/type :spec.type/int
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Page number supporting or contradicting the claim")
     (spec/field ::spec/name :section
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Section supporting or contradicting the claim"))
    (spec/spec
     (spec/field ::spec/name :answer
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Your independent answer to the verification question")
     (spec/field ::spec/name :verdict
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/values {"correct" "The claim is accurate based on your independent answer"
                                "incorrect" "The claim contradicts your independent answer"
                                "partially-correct" "The claim is partly accurate but needs refinement"
                                "uncertain" "Cannot determine accuracy with available information"}
                 ::spec/description "Verdict comparing your answer against the original claim")
     (spec/field ::spec/name :reasoning
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Explanation of your reasoning")
     (spec/field ::spec/name :correction
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Suggested correction if claim is incorrect or partially correct"))))

(defn- build-single-verification-objective
  "Builds the system prompt for independently answering a single verification question."
  [documents]
  (if (seq documents)
    "<independent_verification>
    <role>You are a rigorous fact-checker answering a verification question.</role>
    
    <instructions>
        <instruction>Answer the verification question using ONLY the provided source documents and your knowledge</instruction>
        <instruction>You are NOT shown the original output being verified - this is intentional</instruction>
        <instruction>Answer as accurately and independently as possible</instruction>
        <instruction>Then compare your answer against the original claim to determine a verdict</instruction>
        <instruction>Cite the specific document, page, and section that supports your answer</instruction>
    </instructions>
    
    <principles>
        <principle>Be skeptical - do not assume the claim is correct</principle>
        <principle>Base your answer on evidence, not assumptions</principle>
        <principle>Mark uncertain when you genuinely cannot determine the answer</principle>
    </principles>
</independent_verification>"
    "<independent_verification>
    <role>You are a rigorous fact-checker answering a verification question.</role>
    
    <instructions>
        <instruction>Answer the verification question independently using your knowledge</instruction>
        <instruction>You are NOT shown the original output being verified - this is intentional</instruction>
        <instruction>Answer as accurately and independently as possible</instruction>
        <instruction>Then compare your answer against the original claim to determine a verdict</instruction>
    </instructions>
    
    <principles>
        <principle>Be skeptical - do not assume the claim is correct</principle>
        <principle>Base your answer on evidence, not assumptions</principle>
        <principle>Mark uncertain when you genuinely cannot determine the answer</principle>
    </principles>
</independent_verification>"))

(defn- build-single-verification-task
  "Builds the task for independently verifying a single claim."
  [claim-text question documents]
  (str "<claim_to_verify>" claim-text "</claim_to_verify>\n\n"
       "<verification_question>" question "</verification_question>"
       (when (seq documents)
         (str "\n\n" (build-source-documents-block documents)))))

(defn- verify-single-claim
  "Step 2 of Factored CoVe: Answer a single verification question independently."
  [claim-text question model config documents]
  (:result (ask! {:spec (build-single-verification-spec (boolean (seq documents)))
                  :messages [(system (build-single-verification-objective documents))
                             (user (build-single-verification-task claim-text question documents))]
                  :model model
                  :config config})))

(defn- verifiable-claim?
  "Returns true if a claim should be sent to verification."
  [claim]
  (and (not= false (:verifiable? claim))
       (not= "subjective" (:category claim))))

(defn- filter-verifiable-claims
  "Splits claims into verifiable and non-verifiable groups."
  [claims]
  (let [grouped (group-by verifiable-claim? claims)]
    {:verifiable (get grouped true [])
     :non-verifiable (get grouped false [])}))

(defn- verify-claims
  "Verifies extracted claims using Factored CoVe (independent per-claim verification)."
  [claims original-task original-objective model config documents]
  (let [{:keys [verifiable non-verifiable]} (filter-verifiable-claims claims)]
    (if (empty? verifiable)
      ;; All claims are non-verifiable — return as uncertain
      {:verifications (mapv (fn [claim]
                              {:claim (:claim claim)
                               :question "N/A"
                               :answer "N/A"
                               :verdict "uncertain"
                               :reasoning "Claim is subjective or not independently verifiable"})
                            non-verifiable)}
      ;; Factored CoVe
      (let [;; Step 1: Plan verification questions (one LLM call — sees all claims)
            planning-result (plan-verification-questions verifiable original-task
                                                        original-objective model config)
            planned-questions (:questions planning-result)

            ;; Step 2: Answer each question independently (one LLM call per question)
            factored-verifications
            (mapv (fn [planned-q]
                    (let [claim-text (:claim planned-q)
                          question (:question planned-q)
                          result (verify-single-claim claim-text question model config documents)]
                      (merge {:claim claim-text :question question} result)))
                  planned-questions)

            ;; Non-verifiable claims → uncertain without LLM calls
            skipped-results
            (mapv (fn [claim]
                    {:claim (:claim claim)
                     :question "N/A"
                     :answer "N/A"
                     :verdict "uncertain"
                     :reasoning "Claim is subjective or not independently verifiable"})
                  non-verifiable)]
        {:verifications (into factored-verifications skipped-results)}))))

;; =============================================================================
;; Factor+Revise: Cross-Claim Inconsistency Detection
;; =============================================================================

(defn- format-verifications-for-inconsistency-detection
  "Formats verification results for cross-claim inconsistency detection."
  [verifications]
  (->> verifications
       (map-indexed (fn [i v]
                      (str "<verified_claim id=\"" (inc (long i)) "\">\n"
                           "  <claim>" (:claim v) "</claim>\n"
                           "  <independent_answer>" (:answer v) "</independent_answer>\n"
                           "  <verdict>" (:verdict v) "</verdict>\n"
                           "  <reasoning>" (:reasoning v) "</reasoning>\n"
                           (when (:correction v)
                             (str "  <correction>" (:correction v) "</correction>\n"))
                           "</verified_claim>")))
       (str/join "\n")))

(defn- build-inconsistency-detection-spec
  "Builds the spec for detecting cross-claim inconsistencies."
  []
  (spec/spec
   (spec/field ::spec/name :inconsistencies
              ::spec/type :spec.type/string
              ::spec/cardinality :spec.cardinality/many
              ::spec/description "Cross-claim inconsistencies detected between verification results and original output. Empty array if none found.")
   (spec/field ::spec/name :inconsistencies/claims
              ::spec/type :spec.type/string
              ::spec/cardinality :spec.cardinality/one
              ::spec/description "The conflicting claim texts, quoted verbatim from the verification results")
   (spec/field ::spec/name :inconsistencies/type
              ::spec/type :spec.type/string
              ::spec/cardinality :spec.cardinality/one
              ::spec/values {"contradiction" "Two or more claims directly contradict each other"
                             "inconsistency" "Claims are not directly contradictory but cannot both be fully accurate"
                             "drift" "Verification answer reveals the original output diverged from facts in a way not caught by individual verdicts"}
              ::spec/description "Type of inconsistency")
   (spec/field ::spec/name :inconsistencies/description
              ::spec/type :spec.type/string
              ::spec/cardinality :spec.cardinality/one
              ::spec/description "Clear description of the inconsistency and why it matters")
   (spec/field ::spec/name :inconsistencies/severity
              ::spec/type :spec.type/string
              ::spec/cardinality :spec.cardinality/one
              ::spec/values {"high" "Factual contradiction that must be resolved"
                             "medium" "Notable inconsistency that should be addressed"
                             "low" "Minor tension that may be acceptable"}
              ::spec/description "Severity of the inconsistency")
   (spec/field ::spec/name :inconsistencies/resolution
              ::spec/type :spec.type/string
              ::spec/cardinality :spec.cardinality/one
              ::spec/description "Suggested resolution for the inconsistency")))

(defn- build-inconsistency-detection-objective
  "Builds the system prompt for cross-claim inconsistency detection."
  [original-objective]
  (str "<inconsistency_detection>
    <role>You are an expert analyst who identifies contradictions and inconsistencies across multiple verified claims.</role>

    <context>
        <original_objective>" original-objective "</original_objective>
    </context>

    <instructions>
        <instruction>Compare ALL verification answers against each other to find cross-claim contradictions</instruction>
        <instruction>Compare each verification answer against the original output to find undetected divergences</instruction>
        <instruction>Look for cases where individually 'correct' claims conflict with each other</instruction>
        <instruction>Identify factual drift — where the original output subtly diverges from verification answers</instruction>
        <instruction>If no inconsistencies are found, return an empty inconsistencies array</instruction>
    </instructions>

    <detection_guidelines>
        <guideline>Focus on factual and logical contradictions, not stylistic differences</guideline>
        <guideline>A contradiction is when two claims cannot both be true simultaneously</guideline>
        <guideline>An inconsistency is when claims are in tension but not directly contradictory</guideline>
        <guideline>Drift is when the original output says X but verification evidence says Y, even if the individual verdict was 'correct'</guideline>
        <guideline>Quote the specific conflicting texts verbatim</guideline>
    </detection_guidelines>
</inconsistency_detection>"))

(defn- build-inconsistency-detection-task
  "Builds the task content for cross-claim inconsistency detection."
  [original-output verifications]
  (str "<original_output>\n" (if (string? original-output) original-output (pr-str original-output)) "\n</original_output>\n\n"
       "<verification_results>\n" (format-verifications-for-inconsistency-detection verifications) "\n</verification_results>\n\n"
       "<instruction>Identify any cross-claim contradictions, inconsistencies, or factual drift between the verification results and the original output. If none are found, return an empty inconsistencies array.</instruction>"))

(defn- detect-inconsistencies
  "Step 3 of Factor+Revise CoVe: Explicit cross-claim inconsistency detection."
  [current-output verifications original-objective model config]
  (if (< (long (count verifications)) 2)
    ;; Need at least 2 verified claims to detect cross-claim inconsistencies
    {:inconsistencies []}
    (:result (ask! {:spec (build-inconsistency-detection-spec)
                    :messages [(system (build-inconsistency-detection-objective original-objective))
                               (user (build-inconsistency-detection-task current-output verifications))]
                    :model model
                    :config config}))))

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

(defn- format-inconsistencies-for-refinement
  "Formats cross-claim inconsistencies into feedback for refinement."
  [inconsistencies]
  (if (empty? inconsistencies)
    "<cross_claim_inconsistencies>None detected</cross_claim_inconsistencies>"
    (str "<cross_claim_inconsistencies>\n"
         (->> inconsistencies
              (map-indexed (fn [i incon]
                             (str "  <inconsistency id=\"" (inc (long i)) "\" type=\"" (:type incon) "\" severity=\"" (:severity incon) "\">\n"
                                  "    <claims>" (:claims incon) "</claims>\n"
                                  "    <description>" (:description incon) "</description>\n"
                                  "    <resolution>" (:resolution incon) "</resolution>\n"
                                  "  </inconsistency>")))
              (str/join "\n"))
         "\n</cross_claim_inconsistencies>")))

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
        <instruction>Resolve ALL cross-claim inconsistencies — these are conflicts between claims that were individually verified but contradict each other</instruction>
        <instruction>Maintain all aspects that were verified as correct and are not part of an inconsistency</instruction>
        <instruction>Improve clarity and accuracy without changing correct content</instruction>
        <instruction>Ensure the refined output fully addresses the original task</instruction>
    </instructions>
    
    <refinement_principles>
        <principle>Prioritize resolving cross-claim contradictions — these indicate structural errors in the output</principle>
        <principle>Prioritize fixing high-severity issues next</principle>
        <principle>Do not introduce new errors while fixing existing ones</principle>
        <principle>Preserve the overall structure and intent of the original output</principle>
        <principle>Be conservative - only change what needs to be changed</principle>
        <principle>Ensure corrections are factually accurate</principle>
    </refinement_principles>
</refinement_task>"))

(defn- build-refinement-task
  "Builds the task content for refinement with full context."
  [original-task current-output verifications evaluation-issues inconsistencies]
  (str "<original_task>\n" original-task "\n</original_task>\n\n"
       "<current_output>\n" (if (string? current-output) current-output (pr-str current-output)) "\n</current_output>\n\n"
       "<verification_feedback>\n" (format-verifications-for-refinement verifications) "\n</verification_feedback>\n\n"
       (format-inconsistencies-for-refinement inconsistencies) "\n\n"
       "<evaluation_issues>\n" (format-issues-for-refinement evaluation-issues) "\n</evaluation_issues>\n\n"
       "<instruction>Generate a refined version of the output that resolves all cross-claim inconsistencies, addresses the verification feedback and evaluation issues, while maintaining correct content.</instruction>"))

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
  "Performs a single refinement iteration:
   decompose -> verify -> detect inconsistencies -> evaluate -> refine."
  [spec original-objective original-task model config eval-criteria documents
   {:keys [current-output iterations iteration-num prompt-evolution] :as _state}]
  (let [iteration (inc (long iteration-num))

        [{:keys [claims verifications inconsistencies evaluation
                 refined-output refinement-objective refinement-task]} iter-duration-ms]
        (util/with-elapsed
          (let [;; Step 1: Decompose - extract claims from current output
                decomposition (decompose-output current-output original-task original-objective
                                                model config)
                claims (:claims decomposition)

                ;; Step 2: Verify - check claims with independent per-claim verification
                verification (verify-claims claims original-task original-objective model config documents)
                verifications (:verifications verification)

                ;; Step 3: Detect cross-claim inconsistencies (Factor+Revise)
                inconsistency-result (detect-inconsistencies current-output verifications
                                                             original-objective model config)
                inconsistencies (or (:inconsistencies inconsistency-result) [])

                ;; Step 4: Evaluate - get quality assessment
                evaluation (eval! {:task original-task
                                   :output current-output
                                   :model model
                                   :criteria eval-criteria
                                   :config config})

                ;; Step 5: Refine - generate improved output incorporating all feedback
                refinement-objective (build-refinement-objective original-objective iteration)
                refinement-task (build-refinement-task original-task current-output
                                                       verifications (:issues evaluation)
                                                       inconsistencies)
                {:keys [result]} (ask! {:spec spec
                                        :messages [(system refinement-objective)
                                                   (user refinement-task)]
                                        :model model
                                        :config config})]
            {:claims claims :verifications verifications
             :inconsistencies inconsistencies :evaluation evaluation
             :refined-output result :refinement-objective refinement-objective
             :refinement-task refinement-task}))

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
                          :inconsistencies {:detected inconsistencies
                                            :count (count inconsistencies)
                                            :high-severity-count (->> inconsistencies
                                                                      (filter #(= "high" (:severity %)))
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
   
   Implements the Factor+Revise variant of Chain of Verification (CoVe)
   from Dhuliawala et al. (2023), combined with DuTy decomposition."
  [{:keys [spec messages iterations threshold stop-strategy
           window-size criteria documents]
    :as opts
    :or {iterations DEFAULT_REFINE_ITERATIONS
         threshold DEFAULT_REFINE_THRESHOLD
         stop-strategy :both
         window-size 3
         criteria EVAL_CRITERIA}}]
  (let [{:keys [config model]} (resolve-opts opts)
         ;; Extract objective/task from messages for internal decompose/verify/eval pipeline
         original-objective (or (->> messages (filter #(= "system" (:role %))) first :content) "")
         original-task (or (->> messages (filter #(= "user" (:role %))) first :content) "")
         ;; Phase 1: Generate initial output
          {:keys [result]} (ask! {:spec spec
                                  :messages messages
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
                           spec original-objective original-task model config criteria documents)

          ;; Run iterations until stopping condition met
          [final-state total-duration-ms]
          (util/with-elapsed
            (loop [state initial-state]
              (if (should-stop? stop-strategy threshold
                                (:latest-score state)
                                (:iteration-num state)
                                iterations)
                state
                (recur (step-fn state)))))

          ;; Phase 3: Final evaluation
          final-output (:current-output final-state)
          final-evaluation (eval! {:task original-task
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
          iterations-count (:iteration-num final-state)
          converged? (>= (double final-score) (double threshold))]

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
                 :scores window-scores}}))

;; =============================================================================
;; models! - Fetch available models
;; =============================================================================

(defn models!
  "Fetches available models from the LLM API."
  ([] (models! {}))
  ([opts]
   (let [{:keys [api-key base-url]} (resolve-opts opts)
         models-url (str base-url "/models")
         body (http-get! models-url api-key)
         models (or (:data body) [])]
     (vec models))))

;; =============================================================================
;; sample! - Generate test data samples with self-correction
;; =============================================================================

(def ^:private SAMPLE_CRITERIA
  "Default evaluation criteria for generated samples."
  {:diversity "Are the generated samples diverse and varied across all fields? Samples should cover different cases and not be repetitive."
   :realism "Are the samples realistic and plausible? Values should be believable, not placeholder-like."
   :completeness "Do all samples include all required fields with appropriate values?"
   :correctness "Do the field values match the expected types and constraints (e.g., valid emails, reasonable ages)?"})

(def ^:private DEFAULT_SAMPLE_ITERATIONS
  "Default number of sample refinement iterations."
  3)

(def ^:private DEFAULT_SAMPLE_THRESHOLD
  "Default quality threshold for sample generation."
  0.9)

(defn- build-sample-refinement-feedback
  "Builds feedback for sample refinement based on evaluation results."
  [evaluation n]
  (let [issues (:issues evaluation)
        summary (:summary evaluation)
        scores (:scores evaluation)]
    (str "<evaluation_feedback>\n"
         "  <summary>" summary "</summary>\n"
         "  <scores>" (pr-str (dissoc scores :overall)) "</scores>\n"
         (when (seq issues)
           (str "  <issues>\n"
                (->> issues
                     (map (fn [{:keys [issue severity reasoning mitigation]}]
                            (str "    <issue severity=\"" severity "\">\n"
                                 "      <description>" issue "</description>\n"
                                 (when reasoning (str "      <reasoning>" reasoning "</reasoning>\n"))
                                 (when mitigation (str "      <mitigation>" mitigation "</mitigation>\n"))
                                 "    </issue>")))
                     (str/join "\n"))
                "\n  </issues>\n"))
         "</evaluation_feedback>\n\n"
         "Regenerate " n " improved samples addressing the feedback above. "
         "Maintain what was good and fix the identified issues.")))

(defn sample!
  "Generates test data samples matching a spec with evaluation and self-correction."
  [{:keys [spec messages criteria iterations threshold]
    :as opts
    n :count
    :or {criteria SAMPLE_CRITERIA
         iterations DEFAULT_SAMPLE_ITERATIONS
         threshold DEFAULT_SAMPLE_THRESHOLD}}]
  (if (zero? (long n))
    {:samples []
     :scores {}
     :final-score 0.0
     :converged? true
     :iterations-count 0
     :duration-ms 0.0}

    (let [{:keys [config model]} (resolve-opts opts)

          ;; Build items spec wrapping user's spec
          item-spec (assoc spec ::spec/spec-name :Item)
          items-spec (spec/spec
                      {:refs [item-spec]}
                      (spec/field ::spec/name :items
                                  ::spec/type :spec.type/ref
                                  ::spec/cardinality :spec.cardinality/many
                                  ::spec/description "Array of generated samples"
                                  ::spec/target :Item))

          ;; Build generation messages
          count-instruction (str "Generate exactly " n " sample items. "
                                 "Each item should be unique and realistic. "
                                 "Ensure diversity across all samples.")
          generation-messages (if messages
                                (conj (vec messages) (user count-instruction))
                                [(system (str "You are a test data generator. Generate realistic, diverse "
                                              "samples that match the provided specification. "
                                              "Ensure variety and quality."))
                                 (user count-instruction)])

          ;; Generation + self-correction loop
          [{:keys [samples scores final-score converged? iterations-count]} total-duration-ms]
          (util/with-elapsed
            (loop [iter 0
                   current-messages generation-messages
                   best-samples nil
                   best-score 0.0]
              (let [{:keys [result]} (ask! {:spec items-spec
                                            :messages current-messages
                                            :model model
                                            :config config})
                    current-samples (vec (:items result))

                    ;; Evaluate generated samples
                    evaluation (eval! {:messages generation-messages
                                       :output result
                                       :model model
                                       :criteria criteria
                                       :config config})
                    score (double (:overall-score evaluation))
                    better? (> score (double best-score))
                    new-best-samples (if better? current-samples (or best-samples current-samples))
                    new-best-score (if better? score best-score)]

                (if (or (>= score (double threshold))
                        (>= (long iter) (dec (long iterations))))
                  ;; Done — return best result
                  {:samples new-best-samples
                   :scores (:scores evaluation)
                   :final-score new-best-score
                   :converged? (>= score (double threshold))
                   :iterations-count (inc (long iter))}

                  ;; Self-correct: feed back evaluation and regenerate
                  (let [feedback (build-sample-refinement-feedback evaluation n)]
                    (recur (inc (long iter))
                           (conj current-messages
                                 (assistant (spec/data->str result))
                                 (user feedback))
                           new-best-samples
                           new-best-score))))))]

      {:samples samples
       :scores scores
       :final-score final-score
       :converged? converged?
       :iterations-count iterations-count
       :duration-ms total-duration-ms})))
