(ns com.blockether.svar.internal.rlm.schema
  (:require
   [charred.api :as json]
   [clojure.java.process :as proc]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [com.blockether.svar.internal.paren-repair :as paren-repair]
   [com.blockether.svar.internal.spec :as spec]
   [fast-edn.core :as edn])
  (:import
   [java.util Base64]))

(def MAX_ITERATIONS
  "Default iteration budget before forcing termination.
   The LLM can extend this at runtime via (request-more-iterations n)."
  50)

(def MAX_ITERATION_CAP
  "Absolute ceiling for iterations. No amount of request-more-iterations
   can exceed this. Safety valve against runaway loops."
  500)

(def MAX_EXTENSION_PER_REQUEST
  "Maximum iterations that can be granted per single request-more-iterations call.
   Prevents the LLM from requesting 500 iterations in one shot."
  50)

(def DEFAULT_RECURSION_DEPTH
  "Default maximum depth of nested rlm-query calls. Can be overridden via :max-recursion-depth."
  5)

(def EVAL_TIMEOUT_MS
  "Timeout in milliseconds for code evaluation in SCI sandbox.
   Must be long enough for nested llm-query calls."
  120000)

;; =============================================================================
;; Closed Enums — Entity Types and Relationship Types
;; =============================================================================

(def ENTITY_TYPE_VALUES
  "Closed enum of entity types with LLM-friendly descriptions.
   Used in ENTITY_SPEC for structured extraction and in :entity/type schema field."
  {"concept"      "Named idea, theory, schema, model, defined term, or domain keyword"
   "person"       "Named individual — author, researcher, historical figure, practitioner"
   "technique"    "Method, procedure, algorithm, intervention, protocol, or design pattern"
   "organization" "Company, institution, research group, team, or standards body"
   "project"      "Codebase, repository, product, or system"
   "module"       "Namespace, package, component, service, or bounded context"
   "file"         "Source file, config file, or resource"
   "symbol"       "Function, var, class, method, API endpoint, or command"
   "decision"     "Architectural or design choice with rationale"
   "observation"  "Finding, insight, pattern noticed, or measurement"
   "event"        "Deployment, incident, release, milestone, or significant occurrence"
   "conversation" "RLM conversation session with user"
   "query"        "Single query-env! call within a conversation"
   "iteration"    "One LLM reasoning iteration within a query"})

(def RELATIONSHIP_TYPE_VALUES
  "Closed enum of relationship types with LLM-friendly descriptions.
   Used in RELATIONSHIP_SPEC for structured extraction and in :relationship/type schema field."
  {"defines"      "Source defines, creates, or introduces target"
   "references"   "Source mentions, cites, or refers to target"
   "depends-on"   "Source requires or depends on target to function"
   "implements"   "Source implements, realizes, or concretizes target"
   "supersedes"   "Source replaces, updates, or deprecates target"
   "contradicts"  "Source conflicts with or opposes target"
   "motivated"    "Source motivated, caused, or led to target"
   "contains"     "Source contains or is parent of target"
   "related-to"   "General semantic association between source and target"})

;; =============================================================================
;; Entity Extraction Specs (LLM structured output)
;; =============================================================================

(def ENTITY_EXTRACTION_OBJECTIVE
  "Extract entities and relationships from the provided content.

Return only the fields in the schema. Focus on concrete entities, avoid duplication, and include page/section when known.

ENTITY TYPES (pick exactly one per entity):
- concept: Named idea, theory, schema, model, defined term, or domain keyword
- person: Named individual — author, researcher, historical figure
- technique: Method, procedure, algorithm, intervention, protocol, or design pattern
- organization: Company, institution, research group, team
- project: Codebase, repository, product, or system
- module: Namespace, package, component, service, or bounded context
- file: Source file, config file, or resource
- symbol: Function, var, class, method, API endpoint, or command
- decision: Architectural or design choice with rationale
- observation: Finding, insight, pattern noticed, or measurement
- event: Deployment, incident, release, milestone

RELATIONSHIP TYPES (pick exactly one per relationship):
- defines: Source defines/creates target
- references: Source mentions/cites target
- depends-on: Source requires target
- implements: Source implements/realizes target
- supersedes: Source replaces/deprecates target
- contradicts: Source conflicts with target
- motivated: Source caused/led to target
- contains: Source contains target
- related-to: General association")

(def ENTITY_SPEC
  "Spec for extracted entities."
  (spec/spec
    :entity
    {::spec/key-ns "entity"}
    (spec/field {::spec/name :name
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Entity name"})
    (spec/field {::spec/name :type
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Entity type — must be one of the allowed types"
                 ::spec/values ENTITY_TYPE_VALUES})
    (spec/field {::spec/name :description
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Entity description"})
    (spec/field {::spec/name :section
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Section identifier or label"})
    (spec/field {::spec/name :page
                 ::spec/type :spec.type/int
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Page index (0-based)"})))

(def RELATIONSHIP_SPEC
  "Spec for extracted relationships."
  (spec/spec
    :relationship
    {::spec/key-ns "relationship"}
    (spec/field {::spec/name :source
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Source entity name"})
    (spec/field {::spec/name :target
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Target entity name"})
    (spec/field {::spec/name :type
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Relationship type — must be one of the allowed types"
                 ::spec/values RELATIONSHIP_TYPE_VALUES})
    (spec/field {::spec/name :description
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Relationship description"})))

(def ENTITY_EXTRACTION_SPEC
  "Spec for entity extraction output."
  (spec/spec
    {:refs [ENTITY_SPEC RELATIONSHIP_SPEC]}
    (spec/field {::spec/name :entities
                 ::spec/type :spec.type/ref
                 ::spec/target :entity
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "Extracted entities"})
    (spec/field {::spec/name :relationships
                 ::spec/type :spec.type/ref
                 ::spec/target :relationship
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/required false
                 ::spec/description "Extracted relationships"})))

(defn validate-clojure-code
  "Validates a string that looks like Clojure code.
   Returns nil if valid, or an error string if broken.
   Used by FINAL_SPEC and :code field validators."
  [s]
  (let [s (str/trim (str s))]
    (when (re-find #"^\s*[\(#\[\{]" s)
      (cond
        ;; __ placeholder left in answer - model didn't replace it
        (re-find #"\b__\b" s)
        "Your answer contains '__' placeholder. Replace __ with your actual expression."

        ;; Bare % args outside #() - model wrote (first (drop %2 %1)) instead of #(first (drop %2 %1))
        (and (re-find #"(?<!\w)%[1-9&]?" s)
          (not (str/starts-with? s "#(")))
        "Your answer uses % args (%1, %2) outside #(). Wrap it: #(your-expression) or use (fn [a b] ...)."

        ;; Nested #() - illegal in Clojure
        (re-find #"#\([^)]*#\(" s)
        "Nested #() is illegal in Clojure. Rewrite inner #() as (fn [...] ...)"

        ;; Paren balance check - if repair changes the code, it's unbalanced
        (not= s (paren-repair/repair-code s))
        "Unbalanced delimiters in code. Check your parens/brackets."

        :else nil))))

(defn validate-json
  "Validates a JSON string. Returns nil if valid, error string if broken."
  [s]
  (try (json/read-json (str s)) nil
       (catch Exception e (str "Invalid JSON: " (ex-message e)))))

(defn validate-edn
  "Validates an EDN string. Returns nil if valid, error string if broken."
  [s]
  (try (edn/read-string (str s)) nil
       (catch Exception e (str "Invalid EDN: " (ex-message e)))))

(defn validate-python
  "Validates Python syntax via python3 compile(). Returns nil if valid."
  [s]
  (try
    (let [code (str s)
          p (proc/start {:err :stdout} "python3" "-c"
              (str "compile(" (pr-str code) ", '<answer>', 'exec')"))
          ok (.waitFor p 5000 java.util.concurrent.TimeUnit/MILLISECONDS)]
      (if (and ok (zero? (.exitValue p)))
        nil
        (let [out (slurp (.getInputStream p))]
          (str "Python syntax error: " (str/trim out)))))
    (catch Exception e (str "Python validation failed: " (ex-message e)))))

(defn validate-final
  "Validates a final answer based on its declared type and language.
   answer-type and language are keyword enums from FINAL_SPEC."
  [{:keys [answer answer-type language]}]
  (let [s (str answer)
        atype (or answer-type :code)
        lang  (or language :clojure)]
    (case atype
      :code (case lang
              :clojure (validate-clojure-code s)
              :python  (validate-python s)
              (validate-clojure-code s))
      :data (case lang
              :json (validate-json s)
              :edn  (validate-edn s)
              nil)
      :text nil
      nil)))

(def FINAL_SPEC
  "Nested spec for final answer in iteration response."
  (spec/spec
    :final
    {::spec/key-ns "final"}
    (spec/field {::spec/name :answer
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "The final answer"})
    (spec/field {::spec/name :answer-type
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "What kind of answer is this?"
                 ::spec/values {"code" "Source code (will be validated)"
                                "text" "Natural language / prose"
                                "data" "Structured data (EDN, JSON, etc.)"}})
    (spec/field {::spec/name :language
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Programming language (when answer-type is code)"
                 ::spec/values {"clojure" "Clojure code"
                                "python" "Python code"
                                "json" "JSON data"
                                "edn" "EDN data"}})
    (spec/field {::spec/name :confidence
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Confidence level"
                 ::spec/values {"high" "Very confident in the answer"
                                "medium" "Somewhat confident"
                                "low" "Uncertain, best guess"}})
    (spec/field {::spec/name :sources
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "IDs of sources used to derive the answer. Include page.node IDs, document IDs, or entity IDs that you fetched/searched and actually used. REQUIRED when you used search-documents or fetch-content."})))

(def ITERATION_SPEC
  "Spec for each RLM iteration response. Forces structured output from LLM.
   Used when the provider does NOT have native reasoning (thinking) capability."
  (spec/spec
    {:refs [FINAL_SPEC]}
    (spec/field {::spec/name :thinking
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Your reasoning: what you observed, what you learned, what to do next"})
    (spec/field {::spec/name :code
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "Clojure expressions to execute in the sandbox."})
    (spec/field {::spec/name :next-optimize
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Model preference for next iteration"
                 ::spec/values {"cost" "Cheap model for simple operations"
                                "speed" "Fast model for quick tasks"
                                "intelligence" "Powerful model for hard reasoning"}})
    (spec/field {::spec/name :final
                 ::spec/type :spec.type/ref
                 ::spec/target :final
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Set when you have the final answer. Omit to continue iterating."})))

(def ITERATION_SPEC_CODE_ONLY
  "Spec for RLM iteration response when the provider has native reasoning.
   No 'thinking' field — the model's native reasoning tokens handle that.
   Saves output tokens by not duplicating reasoning in JSON."
  (spec/spec
    {:refs [FINAL_SPEC]}
    (spec/field {::spec/name :code
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "Clojure expressions to execute in the sandbox."})
    (spec/field {::spec/name :next-optimize
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Model preference for next iteration"
                 ::spec/values {"cost" "Cheap model for simple operations"
                                "speed" "Fast model for quick tasks"
                                "intelligence" "Powerful model for hard reasoning"}})
    (spec/field {::spec/name :final
                 ::spec/type :spec.type/ref
                 ::spec/target :final
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Set when you have the final answer. Omit to continue iterating."})))

(defn bytes->base64
  "Converts raw bytes to a base64 string.
   
   Params:
   `bs` - byte[]. Raw bytes.
   
   Returns:
   String. Base64-encoded representation."
  [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(def ^:dynamic *max-recursion-depth*
  "Dynamic var for max recursion depth. Bound per query-env! call."
  DEFAULT_RECURSION_DEPTH)

(def ^:dynamic *rlm-ctx*
  "Dynamic context for RLM debug logging. Bind with {:rlm-debug? true :rlm-phase :phase-name :rlm-env-id \"...\"}."
  nil)

;; Forward declarations for mutually dependent functions
;; =============================================================================
;; SCI Sandbox Configuration
;; =============================================================================

(def RLM_SCHEMA
  "Datalevin schema for all RLM data. Public so callers can merge into their own DB."
  {;; Global RLM metadata
   :rlm.meta/id              {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :rlm.meta/corpus-revision {:db/valueType :db.type/long}
   :rlm.meta/updated-at      {:db/valueType :db.type/instant}

   ;; Documents
   :document/id         {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :document/name       {:db/valueType :db.type/string}
   :document/title      {:db/valueType :db.type/string :db/fulltext true}
   :document/abstract   {:db/valueType :db.type/string :db/fulltext true}
   :document/extension  {:db/valueType :db.type/string}
   :document/author     {:db/valueType :db.type/string}
   :document/page-count {:db/valueType :db.type/long}
   :document/created-at {:db/valueType :db.type/instant}
   :document/updated-at {:db/valueType :db.type/instant}
   ;; Bayesian certainty (Beta distribution for document freshness)
   :document/certainty-alpha {:db/valueType :db.type/double :db/doc "Beta dist alpha — increases on confirmed access"}
   :document/certainty-beta  {:db/valueType :db.type/double :db/doc "Beta dist beta — increases over time, jumps on re-index"}

   ;; Pages
   :page/id           {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :page/document-id  {:db/valueType :db.type/string}
   :page/index        {:db/valueType :db.type/long}
   ;; Vitality tracking (ACT-R memory decay)
   :page/created-at   {:db/valueType :db.type/instant}
   :page/last-accessed {:db/valueType :db.type/instant}
   :page/access-count {:db/valueType :db.type/double}
   ;; RL Q-value for self-improving retrieval
   :page/q-value        {:db/valueType :db.type/double}   ;; 0.0-1.0, default 0.5 = neutral
   :page/q-update-count {:db/valueType :db.type/long}

   ;; Page co-occurrence edges (Ebbinghaus decay)
   :page-cooccurrence/id        {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :page-cooccurrence/page-a    {:db/valueType :db.type/string}
   :page-cooccurrence/page-b    {:db/valueType :db.type/string}
   :page-cooccurrence/strength  {:db/valueType :db.type/double}
   :page-cooccurrence/last-seen {:db/valueType :db.type/instant}

   ;; Page Nodes (content)
   :page.node/id           {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :page.node/page-id      {:db/valueType :db.type/string}
   :page.node/document-id  {:db/valueType :db.type/string}
   :page.node/local-id     {:db/valueType :db.type/string}
   :page.node/type         {:db/valueType :db.type/keyword}
   :page.node/content      {:db/valueType :db.type/string :db/fulltext true}
   :page.node/description  {:db/valueType :db.type/string :db/fulltext true}
   :page.node/level        {:db/valueType :db.type/string}
   :page.node/parent-id    {:db/valueType :db.type/string}
   :page.node/image-data   {:db/valueType :db.type/bytes}
   :page.node/continuation? {:db/valueType :db.type/boolean}
   :page.node/caption      {:db/valueType :db.type/string}
   :page.node/kind         {:db/valueType :db.type/string}
   :page.node/bbox         {:db/valueType :db.type/string}
   :page.node/group-id     {:db/valueType :db.type/string}

   ;; TOC Entries
   :document.toc/id              {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :document.toc/document-id     {:db/valueType :db.type/string}
   :document.toc/type            {:db/valueType :db.type/keyword}
   :document.toc/title           {:db/valueType :db.type/string :db/fulltext true}
   :document.toc/description     {:db/valueType :db.type/string :db/fulltext true}
   :document.toc/target-page     {:db/valueType :db.type/long}
   :document.toc/target-section-id {:db/valueType :db.type/string}
   :document.toc/level           {:db/valueType :db.type/string}
   :document.toc/parent-id       {:db/valueType :db.type/string}
   :document.toc/created-at      {:db/valueType :db.type/instant}

   ;; =========================================================================
   ;; Unified Entity Model
   ;; All data (knowledge entities, conversations, queries, iterations) are
   ;; discriminated by :entity/type from a closed enum. Type-specific attrs
   ;; live in their own namespaces (conversation/*, query/*, iteration/*).
   ;; Hierarchy via :entity/parent-id. Cross-doc linking via :entity/canonical-id.
   ;; =========================================================================

   ;; Core entity fields (every entity has these)
   :entity/id           {:db/valueType :db.type/uuid    :db/unique :db.unique/identity}
   :entity/type         {:db/valueType :db.type/keyword :db/doc "Closed enum — see ENTITY_TYPE_VALUES"}
   :entity/name         {:db/valueType :db.type/string  :db/fulltext true}
   :entity/description  {:db/valueType :db.type/string  :db/fulltext true}
   :entity/parent-id    {:db/valueType :db.type/uuid    :db/doc "Hierarchical containment (conversation→query→iteration)"}
   :entity/document-id  {:db/valueType :db.type/string  :db/doc "Source PageIndex document ID"}
   :entity/page         {:db/valueType :db.type/long    :db/doc "Source page number (0-based)"}
   :entity/section      {:db/valueType :db.type/string  :db/doc "Source section identifier"}
   :entity/created-at   {:db/valueType :db.type/instant}
   :entity/updated-at   {:db/valueType :db.type/instant}
   :entity/canonical-id {:db/valueType :db.type/uuid    :db/doc "Cross-document linking — same name+type = same canonical"}

    ;; Git/commit-specific attrs (entity/type = :event, source = git ingestion)
   :commit/category     {:db/valueType :db.type/keyword :db/doc "Squashed category: :bug, :feature, or :documentation"}
   :commit/sha          {:db/valueType :db.type/string  :db/doc "Git commit SHA"}
   :commit/ticket-refs  {:db/valueType :db.type/string  :db/cardinality :db.cardinality/many :db/doc "Ticket/issue refs (Jira, GitHub)"}
   :commit/file-paths   {:db/valueType :db.type/string  :db/cardinality :db.cardinality/many :db/doc "File paths changed in commit"}
   :commit/date         {:db/valueType :db.type/string  :db/doc "ISO-8601 commit date"}
   :commit/prefix       {:db/valueType :db.type/string  :db/doc "Conventional commit prefix (feat, fix, chore, etc.)"}
   :commit/scope        {:db/valueType :db.type/string  :db/doc "Conventional commit scope (rlm, bench, etc.)"}

    ;; Git/author-specific attrs (entity/type = :person, source = git ingestion)
   :person/email        {:db/valueType :db.type/string  :db/doc "Git author email"}

   ;; Conversation-specific attrs (entity/type = :conversation)
   :conversation/env-id        {:db/valueType :db.type/string  :db/unique :db.unique/identity :db/doc "RLM env-id"}
   :conversation/system-prompt {:db/valueType :db.type/string  :db/doc "System prompt for this session"}
   :conversation/model         {:db/valueType :db.type/string  :db/doc "Root model used"}

   ;; Query-specific attrs (entity/type = :query, parent = conversation)
   :query/messages      {:db/valueType :db.type/string  :db/doc "pr-str of original messages vector (with base64 images)"}
   :query/text          {:db/valueType :db.type/string  :db/fulltext true :db/doc "Extracted text from messages (for fulltext search)"}
   :query/answer        {:db/valueType :db.type/string  :db/doc "Final answer"}
   :query/iterations    {:db/valueType :db.type/long    :db/doc "Number of iterations"}
   :query/duration-ms   {:db/valueType :db.type/long    :db/doc "Total wall-clock time"}
   :query/status        {:db/valueType :db.type/keyword :db/doc ":success :max-iterations :error"}
   :query/eval-score    {:db/valueType :db.type/float   :db/doc "Refinement eval score 0.0-1.0"}

    ;; Iteration-specific attrs (entity/type = :iteration, parent = query)
   :iteration/code        {:db/valueType :db.type/string  :db/doc "pr-str of code strings executed"}
   :iteration/results     {:db/valueType :db.type/string  :db/doc "pr-str of result strings"}
   :iteration/vars        {:db/valueType :db.type/string  :db/doc "Legacy pr-str of restorable vars defined in this iteration"}
   :iteration/answer      {:db/valueType :db.type/string  :db/doc "Final answer when terminal. Nil if not final."}
   :iteration/thinking    {:db/valueType :db.type/string  :db/doc "LLM thinking/reasoning"}
   :iteration/duration-ms {:db/valueType :db.type/long    :db/doc "LLM call duration"}
   :iteration.var/name    {:db/valueType :db.type/string  :db/doc "Persisted restorable var name"}
   :iteration.var/value   {:db/valueType :db.type/string  :db/doc "pr-str of persisted restorable var value"}
   :iteration.var/code    {:db/valueType :db.type/string  :db/doc "Code block that defined the persisted restorable var"}

   ;; Relationships (typed edges between any entities)
   :relationship/id               {:db/valueType :db.type/uuid    :db/unique :db.unique/identity}
   :relationship/type             {:db/valueType :db.type/keyword :db/doc "Closed enum — see RELATIONSHIP_TYPE_VALUES"}
   :relationship/source-entity-id {:db/valueType :db.type/uuid}
   :relationship/target-entity-id {:db/valueType :db.type/uuid}
   :relationship/description      {:db/valueType :db.type/string}
   :relationship/document-id      {:db/valueType :db.type/string}

   ;; Claims (citation verification)
   :claim/id                   {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :claim/text                 {:db/valueType :db.type/string}
   :claim/document-id          {:db/valueType :db.type/string}
   :claim/page                 {:db/valueType :db.type/long}
   :claim/section              {:db/valueType :db.type/string}
   :claim/quote                {:db/valueType :db.type/string}
   :claim/confidence           {:db/valueType :db.type/double}
   :claim/query-id             {:db/valueType :db.type/uuid}
   :claim/verified?            {:db/valueType :db.type/boolean}
   :claim/verification-verdict {:db/valueType :db.type/string}
   :claim/created-at           {:db/valueType :db.type/instant}

   ;; Raw documents (PageIndex source of truth, stored as EDN string)
   :raw-document/id      {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :raw-document/content {:db/valueType :db.type/string}})

(def BLOOM_DIFFICULTIES
  "Bloom's taxonomy cognitive levels as difficulty progression."
  {"remember"    "Simple recall of facts, definitions, or terms directly stated in the text"
   "understand"  "Explain concepts, summarize, paraphrase, or interpret meaning from the text"
   "apply"       "Use information from the text to solve a new problem or scenario"
   "analyze"     "Break down information, identify patterns, compare elements across sections"
   "evaluate"    "Judge, assess, or critique claims, arguments, or evidence from the text"
   "create"      "Synthesize information from multiple parts to form a new conclusion or insight"})

(def QUESTION_CATEGORIES
  "Question type categories."
  {"factual"      "Direct fact extraction — answer is explicitly stated"
   "inferential"  "Requires reasoning from stated facts to reach the answer"
   "comparative"  "Compares or contrasts two or more concepts, entities, or processes"
   "analytical"   "Requires breaking down complex information or identifying relationships"
   "definitional" "Asks for definitions, explanations, or descriptions of concepts"
   "procedural"   "Asks about processes, steps, methods, or how something works"})

(def GENERATION_PERSONAS
  "Persona descriptions to diversify question styles."
  {:student     "You are a curious undergraduate student studying this material for the first time. Ask questions that test foundational understanding and clarify key concepts."
   :researcher  "You are an academic researcher looking for precise details and methodological rigor. Ask technical, specific questions that require careful reading."
   :practitioner "You are a working professional who needs to apply this knowledge. Ask practical, application-oriented questions about how to use the information."
   :examiner    "You are a rigorous exam designer creating assessment questions. Ask questions that test deep comprehension and the ability to distinguish subtle details."
   :journalist  "You are an investigative journalist looking for the most important claims and evidence. Ask questions that probe key findings, numbers, and conclusions."})

;; -- Specs --

(def QUESTION_SPEC
  "Spec for a single generated question-answer pair."
  (spec/spec
    :question
    {::spec/key-ns "question"}
    (spec/field {::spec/name :question
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "The question text — must be self-contained and understandable without the source document"})
    (spec/field {::spec/name :answer
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "The answer, grounded in source material"})
    (spec/field {::spec/name :evidence-span
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Exact verbatim quote from the source document that supports the answer"})
    (spec/field {::spec/name :source-document
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Source document ID"})
    (spec/field {::spec/name :source-page
                 ::spec/type :spec.type/int
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Source page number (0-based)"})
    (spec/field {::spec/name :source-section
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Source section or heading title"})
    (spec/field {::spec/name :difficulty
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/values BLOOM_DIFFICULTIES
                 ::spec/description "Bloom's taxonomy cognitive level"})
    (spec/field {::spec/name :category
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/values QUESTION_CATEGORIES
                 ::spec/description "Question category"})))

(def QUESTIONIFY_SPEC
  "Spec for generate-qa-env! Q&A generation output."
  (spec/spec
    {:refs [QUESTION_SPEC]}
    (spec/field {::spec/name :questions
                 ::spec/type :spec.type/ref
                 ::spec/target :question
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "Generated question-answer pairs"})))

(def PASSAGE_SPEC
  "Spec for a selected passage from Phase 1."
  (spec/spec
    :passage
    {::spec/key-ns "passage"}
    (spec/field {::spec/name :document-id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Document ID"})
    (spec/field {::spec/name :page
                 ::spec/type :spec.type/int
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Page number (0-based)"})
    (spec/field {::spec/name :section-title
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Section or heading title"})
    (spec/field {::spec/name :content-summary
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Brief summary of what this passage covers (1-2 sentences)"})
    (spec/field {::spec/name :suggested-difficulty
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/values BLOOM_DIFFICULTIES
                 ::spec/description "Suggested Bloom's taxonomy difficulty level for questions from this passage"})
    (spec/field {::spec/name :suggested-category
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/values QUESTION_CATEGORIES
                 ::spec/description "Suggested question category for this passage"})))

(def CHUNK_SELECTION_SPEC
  "Spec for Phase 1 passage selection output."
  (spec/spec
    {:refs [PASSAGE_SPEC]}
    (spec/field {::spec/name :passages
                 ::spec/type :spec.type/ref
                 ::spec/target :passage
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "Selected passages for Q&A generation"})))

(def VERIFICATION_RESULT_SPEC
  "Spec for a single verification result."
  (spec/spec
    :verification
    {::spec/key-ns "verification"}
    (spec/field {::spec/name :question-index
                 ::spec/type :spec.type/int
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Index of the question being verified (0-based)"})
    (spec/field {::spec/name :grounded
                 ::spec/type :spec.type/bool
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Whether the evidence span actually exists in the source and supports the answer"})
    (spec/field {::spec/name :non-trivial
                 ::spec/type :spec.type/bool
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Whether the question requires reading the document — not answerable from titles or headings alone"})
    (spec/field {::spec/name :self-contained
                 ::spec/type :spec.type/bool
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Whether the question is understandable without the source document context"})
    (spec/field {::spec/name :answerable
                 ::spec/type :spec.type/bool
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Whether the question can be answered from the evidence span alone, without external knowledge"})
    (spec/field {::spec/name :answer-consistent
                 ::spec/type :spec.type/bool
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Whether the provided answer accurately matches the question's intent and the evidence"})
    (spec/field {::spec/name :verdict
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/values {"pass" "Question meets all quality criteria"
                                "fail" "Question has fundamental issues and should be dropped"
                                "needs-revision" "Question has minor issues but contains value"}
                 ::spec/description "Verification verdict"})
    (spec/field {::spec/name :revision-note
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/required false
                 ::spec/description "Explanation of issues if verdict is not pass"})))

(def VERIFICATION_SPEC
  "Spec for Phase 3 verification output."
  (spec/spec
    {:refs [VERIFICATION_RESULT_SPEC]}
    (spec/field {::spec/name :verifications
                 ::spec/type :spec.type/ref
                 ::spec/target :verification
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "Verification results for each question"})))

;; -- Prompt builders --

(def DEDUP_SPEC
  "Spec for LLM-based semantic deduplication output."
  (spec/spec
    (spec/field {::spec/name :keep-indices
                 ::spec/type :spec.type/int
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "0-based indices of questions to KEEP — one per semantic group, choosing the highest quality version"})))

(def REVISION_SPEC
  "Spec for revising questions that need improvement."
  (spec/spec
    {:refs [QUESTION_SPEC]}
    (spec/field {::spec/name :questions
                 ::spec/type :spec.type/ref
                 ::spec/target :question
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "Revised question-answer pairs"})))

;; =============================================================================
;; PageIndex Specs (merged from internal.pageindex.spec)
;; =============================================================================

;;; ============================================================================
;;; Page Extraction Specs (Node-Based Structure)
;;; ============================================================================

;; Page number (0-based)
(s/def :page/index
  nat-int?)

;; Document node types (keyword values: :section, :heading, :paragraph, etc.)
(s/def :page.node/type
  #{:section :heading :paragraph :list-item :image :table :header :footer :metadata})

;; Node unique identifier (string: "1", "2", "3", etc.)
(s/def :page.node/id
  string?)

;; Parent node ID (for hierarchy - null for top-level)
(s/def :page.node/parent-id
  (s/nilable string?))

;; Node levels (heading: h1-h6, paragraph: paragraph/citation/code/etc., list: l1-l6)
(s/def :page.node/level
  string?)

;; Node text content (text for text nodes)
(s/def :page.node/content
  string?)

;; Node image bytes (PNG)
(s/def :page.node/image-data
  bytes?)

;; Optional: AI-generated description (for sections, images, tables)
(s/def :page.node/description
  (s/nilable string?))

;; Optional: continuation from previous page
(s/def :page.node/continuation?
  boolean?)

;; Optional: caption text from document (for images/tables)
(s/def :page.node/caption
  (s/nilable string?))

;; Optional: kind of visual element (photo, diagram, chart, data, form, etc.)
(s/def :page.node/kind
  string?)

;; Optional: group ID for continuation grouping (shared UUID across pages)
(s/def :page.node/group-id
  string?)

;; Optional: bounding box for visual elements [xmin, ymin, xmax, ymax] in pixels (legacy)
(s/def :page.node/bbox
  (s/coll-of int? :kind vector? :count 4))

;; Optional: index into PDFBox-extracted embedded images (0-based)
(s/def :page.node/image-index
  int?)

;; Single content node within a page (namespaced keys)
;; Note: :page.node/content holds text for text nodes; visual nodes use :page.node/image-data (bytes)
(s/def ::content-node
  (s/keys :req [:page.node/type
                :page.node/id]
    :opt [:page.node/parent-id
          :page.node/level
          :page.node/content
          :page.node/image-data
          :page.node/description
          :page.node/continuation?
          :page.node/caption
          :page.node/kind
          :page.node/bbox
          :page.node/image-index
          :page.node/group-id]))

;; Page nodes: vector of content nodes in reading order
(s/def :page/nodes
  (s/coll-of ::content-node :kind vector?))

;; Page map (extraction result - node-based)
(s/def ::page
  (s/keys :req [:page/index
                :page/nodes]))

;; Page list (vector of pages)
(s/def ::page-list
  (s/coll-of ::page :kind vector?))

;;; ============================================================================
;;; TOC (Table of Contents) Detection Specs
;;; ============================================================================

;;; ============================================================================
;;; Document Specs (RLM output)
;;; ============================================================================

;; Document name: filename without extension
(s/def :document/name
  (s/and string? seq))

;; Document title: extracted from metadata or first heading
(s/def :document/title
  (s/nilable string?))

;; Document abstract: LLM-generated summary from section descriptions
(s/def :document/abstract
  (s/nilable string?))

;; Document extension: file type (pdf, md, txt)
(s/def :document/extension
  (s/and string? #{"pdf" "md" "txt" "docx" "html"}))

;; Document pages: vector of extracted pages
(s/def :document/pages
  ::page-list)

;;; ============================================================================
;;; TOC Entry Specs (document.toc namespace)
;;; ============================================================================

;; TOC entry type
(s/def :document.toc/type
  #{:toc-entry})

;; TOC entry ID (UUID string)
(s/def :document.toc/id
  string?)

;; TOC entry parent ID (nil for root entries)
(s/def :document.toc/parent-id
  (s/nilable string?))

;; TOC entry title
(s/def :document.toc/title
  string?)

;; TOC entry description (optional, can be nil)
(s/def :document.toc/description
  (s/nilable string?))

;; TOC entry target page (0-based index)
(s/def :document.toc/target-page
  nat-int?)

;; TOC entry target section ID (UUID string linking to page node).
;; Nilable because linking happens in post-processing and may not resolve.
(s/def :document.toc/target-section-id
  (s/nilable string?))

;; TOC entry level (l1, l2, l3, etc.)
(s/def :document.toc/level
  string?)

;; Complete TOC entry
(s/def ::toc-entry
  (s/keys :req [:document.toc/type
                :document.toc/id
                :document.toc/title
                :document.toc/target-page
                :document.toc/level]
    :opt [:document.toc/parent-id
          :document.toc/description
          :document.toc/target-section-id]))

;; Document TOC: vector of TOC entries
(s/def :document/toc
  (s/coll-of ::toc-entry :kind vector?))

;; Document timestamps
(s/def :document/created-at
  inst?)

(s/def :document/updated-at
  inst?)

;; Document author
(s/def :document/author
  (s/nilable string?))

;; Complete RLM document
(s/def ::document
  (s/keys :req [:document/name
                :document/extension
                :document/pages
                :document/toc]
    :opt [:document/title
          :document/abstract
          :document/created-at
          :document/updated-at
          :document/author]))

;; Vector of documents
(s/def ::documents
  (s/coll-of ::document :kind vector?))

;;; ============================================================================
;;; Helper Functions
;;; ============================================================================

(defn valid-document?
  "Returns true if document is valid according to ::document spec."
  [document]
  (s/valid? ::document document))

(defn valid-documents?
  "Returns true if documents vector is valid according to ::documents spec."
  [documents]
  (s/valid? ::documents documents))

(defn explain-document
  "Returns explanation of why document is invalid (or nil if valid)."
  [document]
  (when-not (valid-document? document)
    (s/explain-str ::document document)))

(defn explain-documents
  "Returns explanation of why documents vector is invalid (or nil if valid)."
  [documents]
  (when-not (valid-documents? documents)
    (s/explain-str ::documents documents)))
