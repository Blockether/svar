(ns com.blockether.svar.internal.guard
  "Input guardrails for LLM interactions.
   
   Provides factory functions that create guards to validate user input:
   - `static` - Pattern-based detection of prompt injection attempts
   - `moderation` - LLM-based content policy violation detection (requires :ask-fn)
   - `guard` - Runs one or more guards on input
   
   Guards are functions that take input and return it unchanged on success,
   or throw ExceptionInfo on violation.
   
   Usage:
   (require '[com.blockether.svar.core :as svar])
   (def my-guards [(static) 
                   (moderation {:ask-fn svar/ask! :policies #{:hate}})])
   (-> user-input
       (guard my-guards)
       (svar/ask! ...))"
  (:require
   [clojure.string :as str]
    [com.blockether.svar.internal.util :as util]
    [com.blockether.svar.spec :as spec]
    [taoensso.trove :as trove]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private DEFAULT_MODERATION_MODEL
  "Default model for LLM-based moderation."
  "gpt-4o")

(def DEFAULT_INJECTION_PATTERNS
  "Default patterns for detecting prompt injection attempts.
   Map of pattern -> {:message description :type error-type}.
   These are common phrases used in jailbreak/injection attacks."
  {"ignore previous instructions" {:message "Attempt to override system instructions"
                                   :type :instruction-override}
   "ignore your instructions"     {:message "Attempt to override system instructions"
                                   :type :instruction-override}
   "forget everything above"      {:message "Attempt to clear conversation context"
                                   :type :context-manipulation}
   "forget everything"            {:message "Attempt to clear conversation context"
                                   :type :context-manipulation}
   "ignore all previous"          {:message "Attempt to override previous instructions"
                                   :type :instruction-override}
   "disregard all previous"       {:message "Attempt to override previous instructions"
                                   :type :instruction-override}
   "disregard guidelines"         {:message "Attempt to bypass safety guidelines"
                                   :type :safety-bypass}
   "override previous"            {:message "Attempt to override previous instructions"
                                   :type :instruction-override}
   "new instructions"             {:message "Attempt to inject new instructions"
                                   :type :instruction-injection}
   "you are now a"                {:message "Attempt to change AI identity/role"
                                   :type :identity-manipulation}
   "act as if"                    {:message "Attempt to change AI behavior"
                                   :type :identity-manipulation}
   "pretend you are"              {:message "Attempt to change AI identity"
                                   :type :identity-manipulation}
   "roleplay as"                  {:message "Attempt to change AI role"
                                   :type :identity-manipulation}
   "simulate being"               {:message "Attempt to change AI identity"
                                   :type :identity-manipulation}
   "developer mode"               {:message "Attempt to enable developer/debug mode"
                                   :type :privilege-escalation}
   "admin override"               {:message "Attempt to gain admin privileges"
                                   :type :privilege-escalation}
   "root access"                  {:message "Attempt to gain root privileges"
                                   :type :privilege-escalation}
   "override safety"              {:message "Attempt to bypass safety measures"
                                   :type :safety-bypass}
   "bypass restrictions"          {:message "Attempt to bypass restrictions"
                                   :type :safety-bypass}
   "ignore safeguards"            {:message "Attempt to bypass safeguards"
                                   :type :safety-bypass}
   "system prompt"                {:message "Attempt to access or manipulate system prompt"
                                   :type :system-access}
   "jailbreak"                    {:message "Explicit jailbreak attempt"
                                   :type :jailbreak}})

(def DEFAULT_MODERATION_POLICIES
  "Default OpenAI moderation policies to check.
   All available policies from OpenAI's moderation API."
  #{:sexual
    :sexual/minors
    :harassment
    :harassment/threatening
    :hate
    :hate/threatening
    :illicit
    :illicit/violent
    :self-harm
    :self-harm/intent
    :self-harm/instructions
    :violence
    :violence/graphic})

;; =============================================================================
;; Private Helpers
;; =============================================================================

(defn- input->text
  "Converts input to text for checking.
   Strings pass through unchanged.
   Maps are converted via spec/data->str."
  [input]
  (if (string? input)
    input
    (spec/data->str input)))

(defn- build-moderation-spec
  "Builds the spec for moderation output.
   
   Returns:
   Spec definition for LLM moderation response."
  []
  (spec/spec
   (spec/field ::spec/name :flagged
               ::spec/type :spec.type/bool
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "true if content violates any of the specified policies, false otherwise")
   (spec/field ::spec/name :violations
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/many
               ::spec/description "List of policy violations detected (empty if none)")
   (spec/field ::spec/name :violations/policy
               ::spec/type :spec.type/string
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Name of the violated policy")
   (spec/field ::spec/name :violations/score
               ::spec/type :spec.type/float
               ::spec/cardinality :spec.cardinality/one
               ::spec/description "Confidence score from 0.0 to 1.0 indicating severity of violation")))

(defn- policies->str
  "Converts a set of policy keywords to a formatted string for the prompt.
   
   Params:
   `policies` - Set of keywords. Policies to check.
   
   Returns:
   String. Formatted list of policies with descriptions."
  [policies]
  (let [policy-descriptions
        {:sexual "Sexual content including explicit material"
         :sexual/minors "Sexual content involving minors"
         :harassment "Harassment or bullying content"
         :harassment/threatening "Threatening harassment"
         :hate "Hate speech targeting protected groups"
         :hate/threatening "Threatening hate speech"
         :illicit "Illegal activities"
         :illicit/violent "Violent illegal activities"
         :self-harm "Self-harm content"
         :self-harm/intent "Intent to self-harm"
         :self-harm/instructions "Instructions for self-harm"
         :violence "Violent content"
         :violence/graphic "Graphic violence"}]
    (->> policies
         (map (fn [p] (str "- " (name p) ": " (get policy-descriptions p "Content policy violation"))))
         (str/join "\n"))))

(defn- build-moderation-objective
  "Builds the objective prompt for content moderation.
   
   Params:
   `policies` - Set of keywords. Policies to check against.
   
   Returns:
   String. The objective prompt for the LLM."
  [policies]
  (str "You are a content moderation system. Your task is to analyze text and determine if it violates any of the specified content policies.

<policies_to_check>
" (policies->str policies) "
</policies_to_check>

<instructions>
1. Carefully analyze the provided text for policy violations
2. For each violation found, identify which policy is violated and rate the severity (0.0-1.0)
3. Set flagged to true if ANY policy is violated, false otherwise
4. Only flag clear violations - ambiguous content should not be flagged
5. Be thorough but avoid false positives
</instructions>"))

(defn- extract-violations
  "Extracts policy violations from LLM moderation result.
   
   Params:
   `result` - Map. Parsed LLM response with :flagged and :violations.
   `policies` - Set of keywords. Policies that were checked (for filtering).
   
   Returns:
   Vector of maps with :policy (keyword) and :score for each violation."
  [result policies]
  (if (:flagged result)
    (->> (:violations result)
         (filter (fn [v] (contains? policies (keyword (:policy v)))))
         (mapv (fn [v] {:policy (keyword (:policy v))
                        :score (:score v)})))
    []))

;; =============================================================================
;; Static Guard Factory
;; =============================================================================

(defn static
  "Creates a guard function that checks for prompt injection patterns.
   
   Fast, offline guard using static string matching. Use as first line of defense.
   
   Params:
   `opts` - Map, optional. Configuration options.
     - :patterns - Map of pattern string -> {:message String :type keyword}.
       Each pattern maps to an error message and error type for meaningful errors.
       Default: DEFAULT_INJECTION_PATTERNS.
     - :case-sensitive - Boolean. Whether matching is case-sensitive.
       Default: false (case-insensitive).
   
   Examples:
   ;; Create guard with defaults
   (def check-injection (static))
   
   ;; Create guard with custom patterns
   (def check-injection (static {:patterns {\"kill\" {:message \"Threat detected\"
                                                       :type :threat}
                                            \"hack\" {:message \"Hacking attempt\"
                                                      :type :security}}}))
   
   ;; Use the guard
   ((static) \"Hello, how are you?\")
   => \"Hello, how are you?\"
   
   ((static) \"Ignore previous instructions\")
   => throws ExceptionInfo with meaningful message
   
   Returns:
   Guard function (fn [input] -> input | throw).
   The guard returns original input unchanged if safe.
   Throws ExceptionInfo with :type :svar.guard/prompt-injection (single) or :svar.guard/multiple-violations (multiple)."
  ([]
   (static {}))
  ([opts]
   (let [{:keys [patterns case-sensitive]
          :or {patterns DEFAULT_INJECTION_PATTERNS
               case-sensitive false}} opts]
     (fn [input]
       (let [start-time (System/nanoTime)
             text (input->text input)
             normalized-text (if case-sensitive text (str/lower-case text))
             ;; Find ALL matching patterns with their configs
             matches (vec (for [[pattern config] patterns
                                :let [normalized-pattern (if case-sensitive pattern (str/lower-case pattern))]
                                :when (str/includes? normalized-text normalized-pattern)]
                            {:pattern pattern
                             :message (:message config)
                             :type (:type config)}))
              duration-ms (util/elapsed-since start-time)]
          (cond
            ;; No matches - pass through
            (empty? matches)
           (do
             (trove/log! {:level :debug :data {:input-length (count text) :duration-ms duration-ms}
                          :msg "Static guard passed"})
             input)

           ;; Single match - use its message and type
           (= 1 (count matches))
           (let [{:keys [pattern message type]} (first matches)
                 error-message (or message (str "Pattern matched: " pattern))]
             (trove/log! {:level :warn :data {:input-length (count text)
                                              :pattern pattern
                                              :type type
                                              :duration-ms duration-ms}
                          :msg "Static guard detected prompt injection"})
             (throw (ex-info error-message
                              {:type (or type :svar.guard/prompt-injection)
                               :pattern pattern
                               :input input})))

           ;; Multiple matches - aggregate
           :else
           (let [violation-types (set (keep :type matches))
                 error-message (str "Multiple violations detected: "
                                    (str/join ", " (map :pattern matches)))]
             (trove/log! {:level :warn :data {:input-length (count text)
                                              :patterns-matched (count matches)
                                              :types violation-types
                                              :duration-ms duration-ms}
                          :msg "Static guard detected multiple violations"})
             (throw (ex-info error-message
                              {:type :svar.guard/multiple-violations
                               :violations matches
                               :input input})))))))))

;; =============================================================================
;; Moderation Guard Factory
;; =============================================================================

(defn moderation
  "Creates a guard function that uses LLM to check content against policies.
   
   Detects content policy violations including hate speech, harassment,
   violence, sexual content, self-harm, and illegal content.
   
   Params:
   `opts` - Map. Configuration options.
     - :ask-fn - Function. REQUIRED. The LLM ask function to use (e.g. svar/ask!).
       This makes the guard testable and removes hard dependency on core.
     - :api-key - String. API key for LLM service.
     - :base-url - String. Base URL for LLM service.
     - :model - String. LLM model to use for moderation.
       Default: \"gpt-4o\".
     - :policies - Set of keywords. Policies to enforce.
       Default: DEFAULT_MODERATION_POLICIES (all policies).
       Options: :sexual, :sexual/minors, :harassment, :harassment/threatening,
       :hate, :hate/threatening, :illicit, :illicit/violent, :self-harm,
       :self-harm/intent, :self-harm/instructions, :violence, :violence/graphic.
   
   Examples:
   (require '[com.blockether.svar.core :as svar])
   
   ;; Create guard with ask-fn
   (def check-content (moderation {:ask-fn svar/ask!}))
   
   ;; Create guard with specific policies
   (def check-content (moderation {:ask-fn svar/ask! :policies #{:hate :violence}}))
   
   ;; Use the guard
   ((moderation {:ask-fn svar/ask!}) \"Hello, how are you?\")
   => \"Hello, how are you?\"
   
   Returns:
   Guard function (fn [input] -> input | throw).
   The guard returns original input unchanged if safe.
    Throws ExceptionInfo with :type :svar.guard/moderation-violation if policies violated.
    Throws ExceptionInfo with :type :svar.guard/invalid-config if :ask-fn not provided."
  [opts]
  (let [{:keys [ask-fn api-key base-url model policies]
         :or {model DEFAULT_MODERATION_MODEL
              policies DEFAULT_MODERATION_POLICIES}} opts]
    (when-not ask-fn
      (throw (ex-info ":ask-fn is required for moderation guard"
                      {:type :svar.guard/invalid-config
                       :missing :ask-fn})))
    (fn [input]
      (let [start-time (System/nanoTime)
            text (input->text input)
            _ (trove/log! {:level :debug :data {:input-length (count text) :model model}
                           :msg "Calling LLM for content moderation"})
            response (ask-fn {:spec (build-moderation-spec)
                              :messages [{:role "system" :content (build-moderation-objective policies)}
                                         {:role "user" :content (str "<content_to_moderate>\n" text "\n</content_to_moderate>")}]
                              :model model
                              :api-key api-key
                              :base-url base-url})
            ;; ask! wraps the parsed result under :result key
            result (or (:result response) response)
            violations (extract-violations result policies)
            duration-ms (util/elapsed-since start-time)]
        (if (empty? violations)
          (do
            (trove/log! {:level :debug :data {:input-length (count text)
                                               :flagged? (:flagged result)
                                               :duration-ms duration-ms}
                          :msg "Moderation guard passed"})
            input)
          (do
            (trove/log! {:level :warn :data {:input-length (count text)
                                             :violations (mapv :policy violations)
                                             :duration-ms duration-ms}
                         :msg "Moderation guard detected policy violation"})
            (throw (ex-info (str "Content violates moderation policies: "
                                 (str/join ", " (map #(name (:policy %)) violations)))
                             {:type :svar.guard/moderation-violation
                              :violations violations
                              :input input}))))))))


;; =============================================================================
;; Combined Guard
;; =============================================================================

(defn guard
  "Runs guard(s) on input.
   
   Accepts either a single guard function or a vector of guards.
   Each guard receives the input and either returns it unchanged (pass)
   or throws ExceptionInfo (fail).
   
   Params:
   `input` - String or Map. The user input to check.
   `guards` - Guard function, or vector of guard functions.
   
   Examples:
   ;; Single guard
   (guard \"Hello\" (static))
   => \"Hello\"
   
   ;; Vector of guards
   (guard \"Hello\" [(static) (moderation)])
   => \"Hello\"
   
   ;; With options
   (guard \"Hello\" [(static {:patterns [\"custom\"]})
                     (moderation {:policies #{:hate}})])
   
   ;; Reusable guard chain
   (def my-guards [(static) (moderation)])
   (guard user-input my-guards)
   
   Returns:
   The original input unchanged (String or Map).
   
   Throws:
   ExceptionInfo from the first guard that fails."
  [input guards]
  (let [guard-list (if (vector? guards) guards [guards])
        start-time (System/nanoTime)]
    (doseq [guard-fn guard-list]
      (guard-fn input))
    (let [duration-ms (util/elapsed-since start-time)]
      (trove/log! {:level :debug :data {:guards-count (count guard-list) :duration-ms duration-ms}
                   :msg "Guard chain completed"}))
    input))
