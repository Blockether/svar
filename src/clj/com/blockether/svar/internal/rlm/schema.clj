(ns com.blockether.svar.internal.rlm.schema
  (:require
   [clojure.spec.alpha :as s]
   [com.blockether.svar.internal.spec :as spec])
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

(def INLINE_RESULT_THRESHOLD
  "Results with string representation shorter than this are shown inline.
   Larger results are auto-stored in _rN variables and only metadata
   (type, length, preview, var name) is placed in the history.
   Set high enough that tool results (file reads, directory listings) stay
   visible — the context window budget handles the size, not this threshold."
  10000)

(def ENTITY_EXTRACTION_OBJECTIVE
  "Extract entities and relationships from the provided content.\n\nReturn only the fields in the schema.\nFocus on concrete entities, avoid duplication, and include page/section when known.")

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
                 ::spec/description "Entity type (e.g. :party, :organization, :obligation, :term, :condition)"})
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
                 ::spec/description "Relationship type (e.g. :owns, :obligates, :references, :defines)"})
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

(def ITERATION_SPEC
  "Spec for each RLM iteration response. Forces structured output from LLM.
   Used when the provider does NOT have native reasoning (thinking) capability."
  (spec/spec
    (spec/field {::spec/name :thinking
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Your reasoning: what you observed, what you learned, what to do next"})
    (spec/field {::spec/name :code
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "Clojure expressions to execute. Use (FINAL answer) when done."})))

(def ITERATION_SPEC_CODE_ONLY
  "Spec for RLM iteration response when the provider has native reasoning.
   No 'thinking' field — the model's native reasoning tokens handle that.
   Saves output tokens by not duplicating reasoning in JSON."
  (spec/spec
    (spec/field {::spec/name :code
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "Clojure expressions to execute. Use (FINAL answer) when done."})))

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
  {;; Messages (tagged by env-id to distinguish parent vs sub-RLM)
   ;; :message/content is clean displayable text (user query or final answer).
   ;; Reasoning lives in :message/thinking. Code + results live in :execution/* entities.
   :message/id        {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :message/env-id    {:db/valueType :db.type/string :db/doc "RLM environment that wrote this message"}
   :message/role      {:db/valueType :db.type/keyword}
   :message/content   {:db/valueType :db.type/string :db/fulltext true}
   :message/thinking  {:db/valueType :db.type/string :db/doc "Reasoning/thinking content (native model reasoning or spec-parsed)"}
   :message/tokens    {:db/valueType :db.type/long}
   :message/timestamp {:db/valueType :db.type/instant}
   :message/iteration {:db/valueType :db.type/long}
   :message/result-edn {:db/valueType :db.type/string :db/doc "Full query result as EDN (trace, tokens, cost, answer)"}

   ;; Executions (ordered code blocks + results, linked to assistant messages)
   ;; One message → N executions. Order preserved via :execution/order.
   ;; Replaces storing raw response strings — full structure is queryable.
   :execution/id         {:db/valueType :db.type/uuid   :db/unique :db.unique/identity}
   :execution/message    {:db/valueType :db.type/ref    :db/doc "Parent message entity"}
   :execution/order      {:db/valueType :db.type/long   :db/doc "0-based sequence within the message"}
   :execution/code       {:db/valueType :db.type/string :db/doc "Clojure source code that was executed"}
   :execution/result-edn {:db/valueType :db.type/string :db/doc "EDN-encoded execution result"}
   :execution/stdout     {:db/valueType :db.type/string :db/doc "Captured stdout during execution"}
   :execution/stderr     {:db/valueType :db.type/string :db/doc "Captured stderr during execution"}
   :execution/error      {:db/valueType :db.type/string :db/doc "Error message if execution failed"}
   :execution/time-ms    {:db/valueType :db.type/long   :db/doc "Execution time in milliseconds"}

   ;; Tool Calls (recorded during SCI code execution)
   :tool-call/id          {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :tool-call/env-id      {:db/valueType :db.type/string :db/doc "RLM environment that invoked this tool"}
   :tool-call/tool-name   {:db/valueType :db.type/string :db/doc "SCI symbol name of the tool called"}
   :tool-call/input-edn   {:db/valueType :db.type/string :db/doc "EDN-encoded input parameters"}
   :tool-call/output-edn  {:db/valueType :db.type/string :db/doc "EDN-encoded output (truncated)"}
   :tool-call/error       {:db/valueType :db.type/string :db/doc "Error message if call failed"}
   :tool-call/duration-ms {:db/valueType :db.type/long   :db/doc "Execution time in ms"}
   :tool-call/iteration   {:db/valueType :db.type/long   :db/doc "Which iteration this call happened in"}
   :tool-call/timestamp   {:db/valueType :db.type/instant}

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

   ;; Pages
   :page/id          {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :page/document-id {:db/valueType :db.type/string}
   :page/index       {:db/valueType :db.type/long}

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

   ;; Entities
   :entity/id          {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :entity/name        {:db/valueType :db.type/string :db/fulltext true}
   :entity/type        {:db/valueType :db.type/keyword}
   :entity/description {:db/valueType :db.type/string :db/fulltext true}
   :entity/document-id {:db/valueType :db.type/string}
   :entity/page        {:db/valueType :db.type/long}
   :entity/section     {:db/valueType :db.type/string}
   :entity/created-at  {:db/valueType :db.type/instant}

   ;; Relationships
   :relationship/id               {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :relationship/type             {:db/valueType :db.type/keyword}
   :relationship/source-entity-id {:db/valueType :db.type/uuid}
   :relationship/target-entity-id {:db/valueType :db.type/uuid}
   :relationship/description      {:db/valueType :db.type/string}
   :relationship/document-id      {:db/valueType :db.type/string}

   ;; Learning Tags (first-class entities with definitions)
   :learning-tag/name         {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :learning-tag/definition   {:db/valueType :db.type/string :db/fulltext true}
   :learning-tag/created-at   {:db/valueType :db.type/instant}

   ;; Learnings
   :learning/id               {:db/valueType :db.type/uuid :db/unique :db.unique/identity}
   :learning/insight          {:db/valueType :db.type/string :db/fulltext true}
   :learning/context          {:db/valueType :db.type/string :db/fulltext true}
   :learning/tags             {:db/valueType :db.type/string :db/cardinality :db.cardinality/many}
   :learning/scope            {:db/valueType :db.type/string :db/doc "Glob pattern scoping this learning (nil = global). E.g. *.pdf, contracts/*"}
   :learning/source           {:db/valueType :db.type/keyword :db/doc "How this learning was created: :manual (LLM stored it) or :auto (auto-extracted)"}
   :learning/timestamp        {:db/valueType :db.type/instant}
   :learning/useful-count     {:db/valueType :db.type/long}
   :learning/not-useful-count {:db/valueType :db.type/long}
   :learning/applied-count    {:db/valueType :db.type/long}
   :learning/last-evaluated   {:db/valueType :db.type/instant}

   ;; Claims
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
   :raw-document/content {:db/valueType :db.type/string}

   ;; Trajectories — query session outcomes for training data collection
   :trajectory/id          {:db/valueType :db.type/uuid    :db/unique :db.unique/identity}
   :trajectory/env-id      {:db/valueType :db.type/string  :db/doc "Links to messages sharing this env-id"}
   :trajectory/query       {:db/valueType :db.type/string  :db/fulltext true}
   :trajectory/status      {:db/valueType :db.type/keyword :db/doc ":success, :max-iterations, :error"}
   :trajectory/answer      {:db/valueType :db.type/string  :db/doc "The FINAL answer, pr-str'd"}
   :trajectory/iterations  {:db/valueType :db.type/long}
   :trajectory/duration-ms {:db/valueType :db.type/long}
   :trajectory/model       {:db/valueType :db.type/string  :db/doc "Root model used"}
   :trajectory/doc-pages   {:db/valueType :db.type/long    :db/doc "Number of document pages in context"}
   :trajectory/timestamp   {:db/valueType :db.type/instant}
   :trajectory/score       {:db/valueType :db.type/long    :db/doc "Quality score for filtering (computed on export)"}
   :trajectory/eval-score  {:db/valueType :db.type/float   :db/doc "Refinement eval score 0.0-1.0 (from refine!) — answer quality signal"}})

;; -----------------------------------------------------------------------------
;; Learning Tag CRUD
;; -----------------------------------------------------------------------------

(def DECAY_THRESHOLD
  "Learnings with negative vote ratio above this threshold (after min votes) are decayed."
  0.7)

(def DECAY_MIN_VOTES
  "Minimum total votes before decay filtering applies."
  5)

(def LEARNING_VOTE_ENTRY_SPEC
  "Spec for a single learning vote entry."
  (spec/spec
    :vote-entry
    {::spec/key-ns "vote_entry"}
    (spec/field {::spec/name :id
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "The learning UUID being voted on"})
    (spec/field {::spec/name :vote
                 ::spec/type :spec.type/keyword
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/values {"useful" "Learning directly helped answer the query"
                                "not_useful" "Learning was irrelevant or unhelpful for this query"}
                 ::spec/description "Whether this learning was useful for the query"})
    (spec/field {::spec/name :reason
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "Brief explanation of why this learning was or wasn't useful"})))

(def LEARNING_VOTE_SPEC
  "Spec for LLM-based learning usefulness evaluation."
  (spec/spec
    {:refs [LEARNING_VOTE_ENTRY_SPEC]}
    (spec/field {::spec/name :votes
                 ::spec/type :spec.type/ref
                 ::spec/target :vote-entry
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "One vote per learning that was injected"})))

(def AUTOLEARN_ENTRY_SPEC
  "Spec for a single auto-extracted learning."
  (spec/spec
    :autolearn-entry
    {::spec/key-ns "learning"}
    (spec/field {::spec/name :insight
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "A reusable insight about what strategy/approach worked. Must be general enough to help future queries, not specific to this exact query."})
    (spec/field {::spec/name :context
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/one
                 ::spec/description "When this insight applies (e.g. 'aggregation over large PDFs', 'entity extraction from contracts')"})
    (spec/field {::spec/name :tags
                 ::spec/type :spec.type/string
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "1-3 short lowercase tags for categorization (e.g. 'aggregation', 'pdf', 'search')"})))

(def AUTOLEARN_SPEC
  "Spec for auto-extracted learnings from successful multi-iteration queries."
  (spec/spec
    {:refs [AUTOLEARN_ENTRY_SPEC]}
    (spec/field {::spec/name :learnings
                 ::spec/type :spec.type/ref
                 ::spec/target :autolearn-entry
                 ::spec/cardinality :spec.cardinality/many
                 ::spec/description "1-3 reusable insights extracted from this query's execution trace"})))

(def AUTOLEARN_ITERATION_THRESHOLD
  "Minimum iterations before auto-extracting learnings. Queries with fewer
   iterations are considered trivial — no strategy worth capturing."
  3)

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
;;; Core Domain Specs
;;; ============================================================================

;; Node ID: UUID (java.util.UUID)
(s/def :node/id
  uuid?)

;; Parent ID: Either a node ID or nil (for root nodes)
(s/def :node/parent-id
  (s/nilable :node/id))

;; Node title: Non-empty string
(s/def :node/title
  (s/and string? seq))

;; Start index: First page of section (0-based, so page 1 = index 0)
(s/def :node/start-index
  nat-int?)

;; End index: Last page of section (0-based, so page 10 = index 9)
(s/def :node/end-index
  nat-int?)

;; Physical index: ACTUAL page in PDF where section title appears
;; (May differ from :toc/page due to TOC errors or page numbering schemes)
(s/def :node/physical-index
  nat-int?)

;; Node text content: String (can be empty)
(s/def :node/text
  string?)

;; Node summary: String (generated by LLM or copied from text if short)
(s/def :node/summary
  string?)

;; Node keywords: Vector of relevant keywords extracted from content
;; Example: ["machine learning" "neural networks" "deep learning"]
(s/def :node/keywords
  (s/coll-of string? :kind vector?))

;; Node abbreviations: Vector of abbreviation maps with expansion
;; Example: [{:abbreviation "ML" :expansion "Machine Learning"}]
(s/def :node.abbreviation/abbreviation
  (s/and string? seq))

(s/def :node.abbreviation/expansion
  (s/and string? seq))

(s/def ::abbreviation
  (s/keys :req [:node.abbreviation/abbreviation
                :node.abbreviation/expansion]))

(s/def :node/abbreviations
  (s/coll-of ::abbreviation :kind vector?))

;; Node images: Vector of extracted images with bounding box and base64 data
;; Bounding box is in pixels [xmin, ymin, xmax, ymax]
;; Example: [{:image/page 0 :image/description "Figure 1" :image/bbox [10 20 200 150] :image/data "base64..."}]
(s/def :image/page
  nat-int?)

(s/def :image/description
  string?)

(s/def :image/bbox
  (s/coll-of int? :kind vector? :count 4))

(s/def :image/data
  (s/nilable string?))  ; Base64-encoded PNG, can be nil if extraction failed

(s/def ::extracted-image
  (s/keys :req [:image/page
                :image/description
                :image/bbox]
    :opt [:image/data]))

(s/def :node/images
  (s/coll-of ::extracted-image :kind vector?))

;; Structure index: Hierarchical numbering ("1", "1.1", "1.2.3", etc.)
(s/def :node/structure
  (s/and string?
    #(re-matches #"\d+(\.\d+)*" %)))

;; TOC page: What the table of contents CLAIMS the page number is
;; (Often wrong! Example: TOC says "Chapter 1 ... 1" but it's actually on page 3)
;; Verification & fixing loops correct this to match :node/physical-index
(s/def :toc/page
  (s/nilable nat-int?))

;; Complete node specification
(s/def ::node
  (s/keys :req [:node/id
                :node/title
                :node/start-index
                :node/end-index]
    :opt [:node/parent-id
          :node/physical-index
          :node/text
          :node/summary
          :node/keywords
          :node/abbreviations
          :node/images
          :node/structure
          :toc/page]))

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

;; TOC entry target section ID (UUID string linking to page node)
(s/def :document.toc/target-section-id
  string?)

;; TOC entry level (l1, l2, l3, etc.)
(s/def :document.toc/level
  string?)

;; Complete TOC entry
(s/def ::toc-entry
  (s/keys :req [:document.toc/type
                :document.toc/id
                :document.toc/title
                :document.toc/target-page
                :document.toc/target-section-id
                :document.toc/level]
    :opt [:document.toc/parent-id
          :document.toc/description]))

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
