(ns com.blockether.svar.internal.rlm.qa
  "QA generation pipeline — multi-stage Q&A pair generation from ingested documents."
  (:require
   [clojure.core.async :as async]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.rlm.db :as rlm-db]
   [com.blockether.svar.internal.rlm.qa-manifest :as qa-manifest]
   [com.blockether.svar.internal.rlm.schema :as schema]
   [com.blockether.svar.internal.util :as util]
   [sci.core :as sci]
   [taoensso.trove :as trove])
  (:import
   [java.time Instant]))

;; -----------------------------------------------------------------------------
;; Schema refs (defined in schema.clj)
;; -----------------------------------------------------------------------------

(def ^:private GENERATION_PERSONAS schema/GENERATION_PERSONAS)
(def ^:private DEDUP_SPEC schema/DEDUP_SPEC)
(def ^:private REVISION_SPEC schema/REVISION_SPEC)
(def ^:private CHUNK_SELECTION_SPEC schema/CHUNK_SELECTION_SPEC)
(def ^:private QUESTIONIFY_SPEC schema/QUESTIONIFY_SPEC)
(def ^:private VERIFICATION_SPEC schema/VERIFICATION_SPEC)

;; -----------------------------------------------------------------------------
;; Prompt builders
;; -----------------------------------------------------------------------------

(defn build-toc-based-selection-prompt
  "Builds prompt for Phase 1 fast-model selection using pre-gathered TOC data.
   All corpus structure is provided inline — no SCI loop needed."
  [{:keys [count difficulty-dist category-dist documents toc-entries page-nodes]}]
  (let [difficulties (str/join ", " (map name difficulty-dist))
        categories (str/join ", " (map name category-dist))
        ;; Format document overview
        docs-section
        (str/join "\n"
          (map (fn [doc]
                 (str "- " (or (:document/title doc) (:document/name doc))
                   " (ID: " (:document/id doc) ")"
                   (when-let [abstract (:document/abstract doc)]
                     (str "\n  Abstract: " (rlm-db/str-truncate abstract 300)))))
            documents))
        ;; Format TOC — indented by level for structure
        toc-section
        (str/join "\n"
          (map (fn [e]
                 (let [level-num (let [l (:document.toc/level e)]
                                   (if (string? l)
                                     (parse-long (re-find #"\d+" (str l)))
                                     (or l 0)))
                       indent (str/join (repeat (min 4 (or level-num 0)) "  "))]
                   (str indent "- [" (:document.toc/id e) " p" (:document.toc/target-page e) "] "
                     (:document.toc/title e)
                     (when-let [desc (:document.toc/description e)]
                       (str " — " (rlm-db/str-truncate desc 100))))))
            toc-entries))
        ;; Format page content summaries — grouped by document
        content-section
        (str/join "\n"
          (->> page-nodes
            (filter #(or (not-empty (:page.node/content %))
                       (not-empty (:page.node/description %))))
            (map (fn [node]
                   (str "  [" (:page.node/document-id node)
                     " p" (:page.node/page-id node)
                     " " (name (or (:page.node/type node) :unknown)) "] "
                     (or (not-empty (:page.node/content node))
                       (:page.node/description node)))))))]
    (str "You are selecting diverse passages from a document corpus for question-answer generation.

YOUR TASK: Select exactly " count " passages that will serve as source material for generating Q&A pairs.
Each passage should come from a distinct section of the corpus to maximize coverage.

AVAILABLE DOCUMENTS:
" docs-section "

TABLE OF CONTENTS:
" (if (seq toc-section) toc-section "(no TOC entries)") "

CONTENT SUMMARIES (truncated previews):
" (if (seq content-section) content-section "(no content previews)") "

SELECTION CRITERIA:
- If multiple documents exist, select from ALL of them proportionally
- Within each document, spread selections across different chapters/sections
- Avoid selecting adjacent pages — skip at least 2-3 pages between selections
- Cover different content types: definitions, processes, examples, data, arguments
- Assign difficulty levels using round-robin across: " difficulties "
- Assign categories using round-robin across: " categories "

SKIP passages that are:
- Table of contents or index pages
- Pages with only images and no descriptive text
- Extremely short content (less than 2 sentences)
- Repeated/boilerplate content (headers, footers, page numbers)

For each passage, provide:
- document-id: The document ID from the list above
- page: The 0-based page number
- section-title: The section or heading title from the TOC
- content-summary: A 1-2 sentence description of what makes this passage suitable for Q&A
- suggested-difficulty: One of " difficulties "
- suggested-category: One of " categories)))

(defn build-generation-prompt
  "Builds prompt for Phase 2: Q&A generation from selected passages.
   Supports optional persona styling, k-candidates selection, and multi-hop mode."
  [passages batch-index {:keys [persona k-candidates multi-hop?]}]
  (let [passage-descriptions
        (str/join "\n\n"
          (map-indexed
            (fn [i p]
              (str "PASSAGE " (inc i) ":\n"
                "  Document: " (:document-id p) "\n"
                "  Page: " (:page p) "\n"
                "  Section: " (:section-title p) "\n"
                "  Summary: " (:content-summary p) "\n"
                "  Target difficulty: " (name (or (:suggested-difficulty p) :understand)) "\n"
                "  Target category: " (name (or (:suggested-category p) :factual))))
            passages))
        k (or k-candidates 1)
        per-passage-count (if (> k 1)
                            (str "Generate " k " candidate Q&A pairs per passage. "
                              "Rate each candidate 1-5 on quality (groundedness, clarity, specificity). "
                              "Then keep only the BEST candidate per passage in your final output.")
                            "Generate 1-2 Q&A pairs per passage.")
        persona-instruction (when persona
                              (str "\n\nPERSONA: " (get GENERATION_PERSONAS persona
                                                     (str "You are " (name persona) ". Ask questions from this perspective.")) "\n"))
        multi-hop-instruction (when multi-hop?
                                "\n\nMULTI-HOP QUESTIONS: Some passages are paired across different sections. For paired passages, generate questions that REQUIRE information from BOTH passages to answer — the answer should synthesize facts from multiple sources. Mark these as 'analyze' or 'create' difficulty.\n")]
    (str "You are generating high-quality question-answer pairs from specific passages in the corpus.
This is batch " batch-index ". " per-passage-count
      (or persona-instruction "")
      (or multi-hop-instruction "") "

PASSAGES TO PROCESS:
" passage-descriptions "

STEP-BY-STEP INSTRUCTIONS:
1. For each passage above, search for its actual content:
   (search-page-nodes \"<key terms from section>\" 5 {:document-id \"<doc-id>\"})
   or (list-page-nodes {:document-id \"<doc-id>\" :page-id \"<page>\"})
2. Read the full content of relevant page nodes using (get-page-node node-id)
3. " per-passage-count "

CRITICAL REQUIREMENTS FOR EACH Q&A PAIR:
- question: Self-contained, understandable WITHOUT seeing the document. Never reference
  'the document', 'the text', 'this section', 'the author'. A reader should understand
  what is being asked without any context.
- answer: Accurate, grounded in the source text. Should be 1-3 sentences.
- evidence-span: A VERBATIM QUOTE from the source document. Copy the exact text.
  This is NOT a paraphrase — it must be findable in the document word-for-word.
  Keep it to 1-3 sentences maximum.
- source-document: The document ID from list-documents
- source-page: The page number (0-based) where the evidence appears
- source-section: The section heading (if known)
- difficulty: Use the suggested difficulty from the passage description
- category: Use the suggested category from the passage description

EXAMPLES OF GOOD QUESTIONS:
  Q: What is the minimum capitalization requirement for banks under Basel III?
  A: Banks must maintain a minimum Common Equity Tier 1 ratio of 4.5% of risk-weighted assets.
  evidence-span: \"Banks are required to maintain a minimum CET1 ratio of 4.5 percent of risk-weighted assets\"

EXAMPLES OF BAD QUESTIONS (DO NOT GENERATE THESE):
  BAD: What does this document say about banks? (references 'this document')
  BAD: What is discussed in Section 3? (references section numbers)
  BAD: What is Basel III? (answerable without the document — too generic)
  BAD: According to the text, what is mentioned? (references 'the text')

After generating all Q&A pairs, call (FINAL {:questions [...]}).")))

(defn create-multi-hop-pairs
  "Creates multi-hop passage pairs from selected passages.
   Pairs passages from different sections/documents for cross-reference questions."
  [passages]
  (when (>= (count passages) 2)
    (let [by-doc (group-by :document-id passages)
          ;; Cross-document pairs (highest value)
          cross-doc-pairs
          (when (> (count by-doc) 1)
            (let [doc-ids (vec (keys by-doc))]
              (for [i (range (min 3 (dec (count doc-ids))))
                    :let [p1 (first (get by-doc (nth doc-ids i)))
                          p2 (first (get by-doc (nth doc-ids (inc i))))]
                    :when (and p1 p2)]
                [p1 p2])))
          ;; Within-document pairs from different sections (skip adjacent pages)
          within-doc-pairs
          (for [[_doc-id doc-passages] by-doc
                :let [sorted (vec (sort-by :page doc-passages))]
                i (range (dec (count sorted)))
                :let [p1 (nth sorted i)
                      p2 (nth sorted (min (+ i 2) (dec (count sorted))))]
                :when (and p1 p2 (not= p1 p2)
                        (> (Math/abs (- (:page p2) (:page p1))) 1))]
            [p1 p2])]
      (vec (take 3 (concat cross-doc-pairs within-doc-pairs))))))

(defn build-verification-prompt
  "Builds prompt for Phase 3: verify Q&A pairs against source material."
  [questions]
  (let [question-descriptions
        (str/join "\n\n"
          (map-indexed
            (fn [i q]
              (str "QUESTION " i " (index " i "):\n"
                "  Q: " (:question q) "\n"
                "  A: " (:answer q) "\n"
                "  Evidence: " (rlm-db/str-truncate (or (:evidence-span q) "") 200) "\n"
                "  Source: " (:source-document q) " page " (:source-page q)))
            questions))]
    (str "You are a quality auditor verifying question-answer pairs against source documents.
For each Q&A pair below, verify it meets quality standards.

Q&A PAIRS TO VERIFY:
" question-descriptions "

FOR EACH QUESTION, PERFORM THESE CHECKS:
1. GROUNDED: Search for the evidence-span in the source document:
   (search-page-nodes \"<key phrase from evidence>\" 5 {:document-id \"<doc-id>\"})
   Does the evidence span actually exist (or closely match) in the document? Does it support the answer?

2. NON-TRIVIAL: Is this question meaningful? Would answering it require actually reading the document?
   FAIL if: the question just asks 'What is [heading text]?' or could be answered by reading only titles.

3. SELF-CONTAINED: Can someone understand this question without seeing the source document?
   FAIL if: the question references 'the document', 'this section', 'the text', 'the author' etc.

4. ANSWERABLE: Can the question be answered solely from the evidence span provided?
   The evidence should contain sufficient information to derive the answer without external knowledge.
   FAIL if: the answer requires facts not present in the evidence span.

5. ANSWER-CONSISTENT: Does the provided answer accurately match what the question asks?
   The answer should directly address the question's intent and be supported by the evidence.
   FAIL if: the answer addresses a different aspect, misinterprets the question, or contradicts the evidence.

VERDICT CRITERIA:
- pass: All five checks pass
- fail: Evidence is fabricated/hallucinated, question is trivially bad, OR answer contradicts evidence
- needs-revision: Minor issues (e.g., evidence is paraphrased rather than verbatim, but answer is correct)

After verifying all questions, call (FINAL {:verifications [...]}).
Each verification must include: question-index, grounded, non-trivial, self-contained, answerable, answer-consistent, verdict, and revision-note (if applicable).")))

;; -----------------------------------------------------------------------------
;; Utility functions
;; -----------------------------------------------------------------------------

(defn compute-distribution
  "Computes target counts per item, distributing total-count evenly across items."
  [total-count items]
  (let [item-vec (vec items)
        n (count item-vec)
        base (quot total-count n)
        remainder (rem total-count n)]
    (into {} (map-indexed (fn [i item]
                            [item (if (< i remainder) (inc base) base)])
               item-vec))))

(defn dedup-batch
  "Deduplicates a single batch of questions via LLM. Returns kept questions."
  [questions rlm-router]
  (let [numbered-list
        (str/join "\n"
          (map-indexed
            (fn [i q]
              (str "[" i "] " (:question q)))
            questions))
        result (llm/ask! rlm-router
                 {:spec DEDUP_SPEC
                  :messages
                  [(llm/system "You are a deduplication engine. Given a numbered list of questions, identify semantic duplicates — questions that ask the same thing in different words. For each group of duplicates, keep only the BEST version (most clear, specific, and well-phrased). Return the 0-based indices of questions to KEEP.")
                   (llm/user (str "Identify and remove semantic duplicates from this list. Return indices of questions to KEEP (one per duplicate group, choosing the best phrasing):\n\n" numbered-list))]
                  :routing {:optimize :cost}})
        keep-indices (set (or (:keep-indices (:result result)) []))
        kept (vec (keep-indexed
                    (fn [i q]
                      (when (contains? keep-indices i)
                        q))
                    questions))]
    ;; Fallback: if LLM returns empty or error, keep all
    (if (seq kept) kept questions)))

(def ^:private DEDUP_WINDOW_SIZE
  "Max questions per dedup LLM call to avoid context overload."
  20)

(defn deduplicate-questions
  "Removes semantically duplicate questions using LLM judgment.

   Processes in sliding windows of 20 to avoid overwhelming the LLM
   with too many questions in a single call.

   Params:
   `questions` - Vector of question maps with :question key.
   `rlm-router` - Router for LLM calls.

   Returns:
   Vector of unique questions."
  [questions rlm-router]
  (if (<= (count questions) 1)
    questions
    (let [total (count questions)
          ;; Small batch: single pass
          kept (if (<= total DEDUP_WINDOW_SIZE)
                 (dedup-batch questions rlm-router)
                 ;; Large batch: process in windows, then cross-window pass
                 (let [windows (partition-all DEDUP_WINDOW_SIZE questions)
                       per-window (vec (mapcat #(dedup-batch (vec %) rlm-router) windows))]
                   ;; Cross-window dedup on accumulated results
                   (if (> (count per-window) 1)
                     (dedup-batch per-window rlm-router)
                     per-window)))
          dropped-count (- total (count kept))]
      (when (pos? dropped-count)
        (trove/log! {:level :info :id ::qa-dedup
                     :data {:original total
                            :kept (count kept)
                            :dropped dropped-count}
                     :msg "LLM deduplication complete"}))
      kept)))

(defn revise-questions
  "Revises questions that received needs-revision verdict.

   Params:
   `questions` - Vector of question maps with :revision-note key.
   `rlm-router` - Router for LLM calls.

   Returns:
   Vector of revised question maps (without :revision-note)."
  [questions rlm-router]
  (if (empty? questions)
    []
    (let [revision-descriptions
          (str/join "\n\n"
            (map-indexed
              (fn [i q]
                (str "QUESTION " i ":\n"
                  "  Q: " (:question q) "\n"
                  "  A: " (:answer q) "\n"
                  "  Evidence: " (rlm-db/str-truncate (or (:evidence-span q) "") 200) "\n"
                  "  Source: " (:source-document q) " page " (:source-page q) "\n"
                  "  Issue: " (or (:revision-note q) "Minor quality issue")))
              questions))
          result (llm/ask! rlm-router
                   {:spec REVISION_SPEC
                    :messages
                    [(llm/system "You are a question revision engine. Given Q&A pairs with identified issues, fix the problems while preserving the core question intent, answer accuracy, and evidence grounding. Keep the same source-document, source-page, difficulty, and category. Fix only the identified issue.")
                     (llm/user (str "Revise these questions to fix the identified issues:\n\n" revision-descriptions))]
                    :routing {:optimize :cost}})
          revised (or (:questions (:result result)) [])]
      (trove/log! {:level :info :id ::qa-revision
                   :data {:input (count questions)
                          :revised (count revised)}
                   :msg "Question revision complete"})
      ;; Fallback: if revision fails, return originals without revision-note
      (if (seq revised)
        revised
        (mapv #(dissoc % :revision-note) questions)))))

(defn filter-verified-questions
  "Splits questions into passed/needs-revision/dropped based on verification results."
  [questions verifications]
  (let [;; Pad verifications with :pass for any missing indices
        ver-map (into {} (map (fn [v] [(:question-index v) v]) verifications))
        results (map-indexed
                  (fn [i q]
                    (let [v (get ver-map i {:verdict :pass})
                          verdict (:verdict v)]
                      (when-not (= :pass verdict)
                        (trove/log! {:level :debug :id ::qa-filter
                                     :data {:index i :verdict verdict :note (:revision-note v)
                                            :question (rlm-db/str-truncate (:question q) 100)}
                                     :msg "Question failed verification"}))
                      {:question q :verification v
                       :passed? (= :pass verdict)
                       :needs-revision? (= :needs-revision verdict)}))
                  questions)]
    {:passed (mapv :question (filter :passed? results))
     :needs-revision (mapv (fn [r] (assoc (:question r) :revision-note (get-in r [:verification :revision-note])))
                       (filter :needs-revision? results))
     :dropped (mapv :question (filter #(and (not (:passed? %)) (not (:needs-revision? %))) results))
     :results (mapv :verification results)}))

;; -----------------------------------------------------------------------------
;; Fork env for parallel query
;; -----------------------------------------------------------------------------

(defn fork-env-for-query
  "Creates a lightweight fork of the env for parallel query-env! calls.
   Uses sci/fork for cheap SCI context isolation — new vars don't leak back to parent."
  [env]
  (assoc env
    :sci-ctx (sci/fork (:sci-ctx env))
    ;; Fork-local var index cache/revision (must not be shared across SCI forks).
    :var-index-cache-atom (atom {:revision -1 :index nil})
    :var-index-revision-atom (atom 0)))

;; -----------------------------------------------------------------------------
;; Main QA pipeline
;; -----------------------------------------------------------------------------

(defn query-env-qa!
  "Generates question-answer pairs from ingested documents.
   Supports crash-resume via qa-manifest.edn for persistent envs.

   Uses a multi-stage pipeline leveraging the RLM's iterative code execution:

   Phase 1 - Passage Selection: Explores the corpus structure via TOC and content
   search, selects diverse passages covering different sections and topics.

   Phase 2 - Q&A Generation: For each batch of selected passages, generates
   grounded question-answer pairs with evidence spans extracted from source text.

   Phase 3 - Verification: Each Q&A pair is verified against the source material
   for groundedness, non-triviality, and self-containedness.

   Phase 4 - Deduplication: Near-duplicate questions are removed and diversity
   across difficulty levels and categories is verified.

   Params:
   `env` - RLM environment from create-env with ingested documents.
   `opts` - Map, optional:
     - :count - Integer. Target number of Q&A pairs (default: 10).
     - :difficulty - Set of keywords. Bloom's taxonomy levels to include
       (default: #{:remember :understand :apply :analyze :evaluate :create}).
     - :categories - Set of keywords. Question types to include
       (default: #{:factual :inferential :comparative :analytical :definitional :procedural}).
     - :model - String. Override default model.
      - :batch-size - Integer. Passages per generation batch (default: 5).
      - :parallel - Integer. Number of parallel batch workers for Phase 2 (default: 3).
      - :selection-model - String. Fast/cheap model for Phase 1 passage selection (default: :model).
      - :k-candidates - Integer. Generate k candidates per passage, keep best (default: 1).
      - :multi-hop? - Boolean. Generate cross-section questions from passage pairs (default: false).
      - :personas - Set of keywords. Persona styles to rotate across batches for diversity.
        Available: :student, :researcher, :practitioner, :examiner, :journalist (default: nil).
      - :verify? - Boolean. Run verification phase (default: true).
      - :debug? - Boolean. Verbose logging (default: false).

   Returns:
   Map with:
     - :questions - Vector of verified Q&A maps, each with :question, :answer,
       :evidence-span, :source-document, :source-page, :source-section,
       :difficulty, :category.
     - :dropped-questions - Vector of Q&A maps that failed verification.
     - :verification-results - Vector of verification result maps.
     - :phase-traces - Map of {:selection :generation :verification} traces.
     - :stats - Map with :total-generated, :passed-verification, :duplicates-removed,
       :final-count, :by-difficulty (counts), :by-category (counts).
     - :iterations - Total iterations across all phases.
     - :duration-ms - Total execution time."
  ([env query-env!-fn] (query-env-qa! env query-env!-fn {}))
  ([env query-env!-fn {:keys [target-count difficulty categories model batch-size verify? debug? parallel
                              selection-model k-candidates multi-hop? personas]
                       :or {target-count 10
                            difficulty #{:remember :understand :apply :analyze :evaluate :create}
                            categories #{:factual :inferential :comparative :analytical :definitional :procedural}
                            batch-size 5
                            verify? true
                            debug? false
                            parallel 3
                            k-candidates 1}}]
   (when-not (:db-info-atom env)
     (anomaly/incorrect! "Invalid RLM environment" {:type :rlm/invalid-env}))

   (let [start-time (System/nanoTime)
         config (:config env)
         rlm-router (:router env)
         effective-model (or model (:default-model config))
         effective-selection-model (or selection-model effective-model)
         db-info @(:db-info-atom env)
          ;; Select 1.5x passages for filtering headroom
         passage-count (int (Math/ceil (* target-count 1.5)))
         qa-run-opts {:count target-count
                      :difficulty (vec (sort difficulty))
                      :categories (vec (sort categories))
                      :model effective-model
                      :selection-model effective-selection-model
                      :batch-size batch-size
                      :verify? verify?
                      :parallel parallel
                      :k-candidates k-candidates
                      :multi-hop? (boolean multi-hop?)
                      :personas (vec (sort (or personas #{})))}
         qa-run-fingerprint (qa-manifest/qa-manifest-fingerprint env db-info qa-run-opts)

          ;; ===== MANIFEST: Check for resumable state =====
         existing-qa-manifest (qa-manifest/read-qa-manifest env)
         manifest-compatible? (= qa-run-fingerprint (:fingerprint existing-qa-manifest))
         manifest-reset? (and existing-qa-manifest (not manifest-compatible?))
         _ (when manifest-reset?
             (trove/log! {:level :info :id ::qa-manifest-reset
                          :data {:old-fingerprint (:fingerprint existing-qa-manifest)
                                 :new-fingerprint qa-run-fingerprint}
                          :msg "Resetting qa-manifest: run inputs changed"}))
         manifest-atom (atom (if manifest-compatible?
                               existing-qa-manifest
                               (qa-manifest/fresh-qa-manifest qa-run-fingerprint)))
         _ (when (or (nil? existing-qa-manifest) manifest-reset?)
             (qa-manifest/write-qa-manifest! env @manifest-atom))
         _ (when (and manifest-compatible? existing-qa-manifest (not (:completed-at existing-qa-manifest)))
             (trove/log! {:level :info :id ::qa-resume
                          :data {:batches-done (->> (:batches existing-qa-manifest)
                                                 (filter (fn [[_ v]] (= :done (:status v))))
                                                 count)}
                          :msg "Resuming query-env-qa! from manifest"}))

          ;; ===== PHASE 1: Passage Selection (fast-model TOC routing) =====
         phase1 (if (= :done (get-in @manifest-atom [:phase1 :status]))
                  (do (trove/log! {:level :info :id ::qa-phase1-cached
                                   :data {:passages (count (get-in @manifest-atom [:phase1 :passages]))}
                                   :msg "Phase 1: Reusing cached passages from manifest"})
                      {:passages (get-in @manifest-atom [:phase1 :passages])
                       :trace [] :iterations 0})
                  (do
                    (trove/log! {:level :info :id ::qa-phase1
                                 :data {:target-passages passage-count :target-questions target-count
                                        :selection-model effective-selection-model}
                                 :msg "Phase 1: Selecting passages via fast-model TOC routing"})
                    (let [corpus-documents (rlm-db/db-list-documents db-info)
                          corpus-toc (rlm-db/db-list-toc-entries db-info)
                          corpus-nodes (rlm-db/db-list-page-nodes db-info {:limit 500})
                          selection-prompt (build-toc-based-selection-prompt
                                             {:count passage-count
                                              :difficulty-dist difficulty
                                              :category-dist categories
                                              :documents corpus-documents
                                              :toc-entries corpus-toc
                                              :page-nodes corpus-nodes})
                          selection-result (llm/ask! rlm-router {:spec CHUNK_SELECTION_SPEC
                                                                 :messages [(llm/system "You are a passage selection engine for Q&A generation. Select diverse passages from the corpus based on the provided structure. Return your selections in the required JSON format.")
                                                                            (llm/user selection-prompt)]
                                                                 :routing {:optimize :cost}})
                          ps (or (:passages (:result selection-result)) [])]
                      (swap! manifest-atom assoc :phase1 {:status :done :passages ps})
                      (qa-manifest/write-qa-manifest! env @manifest-atom)
                      (trove/log! {:level :info :id ::qa-phase1-done
                                   :data {:passages-selected (count ps)
                                          :model-used effective-selection-model}
                                   :msg "Phase 1 complete"})
                      {:passages ps
                       :trace (:trace selection-result)
                       :iterations (or (:iterations selection-result) 0)})))
         passages (:passages phase1)

         ;; ===== PHASE 2: Batched Q&A Generation (parallel via core.async) =====
         ;; Prepare persona rotation
         persona-vec (when (seq personas) (vec personas))
         ;; Add multi-hop passage pairs if enabled
         multi-hop-pairs (when multi-hop? (create-multi-hop-pairs passages))
         _ (trove/log! {:level :info :id ::qa-phase2
                        :data {:passages (count passages) :batch-size batch-size
                               :parallel parallel
                               :k-candidates k-candidates
                               :multi-hop-pairs (count (or multi-hop-pairs []))
                               :personas (when persona-vec (mapv name persona-vec))}
                        :msg "Phase 2: Generating Q&A pairs in parallel batches"})
         ;; Build standard batches from passages
         standard-batches (vec (partition-all batch-size passages))
         ;; Add multi-hop batches (pairs flattened into batches)
         multi-hop-batches (when (seq multi-hop-pairs)
                             (vec (partition-all batch-size (mapcat identity multi-hop-pairs))))
         all-batches (into standard-batches (or multi-hop-batches []))
         standard-batch-count (count standard-batches)
         work-items (map-indexed
                      (fn [idx batch]
                        (let [is-multi-hop? (>= idx standard-batch-count)
                              persona (when persona-vec
                                        (nth persona-vec (mod idx (count persona-vec))))]
                          {:batch-idx idx :batch (vec batch)
                           :persona persona :multi-hop? is-multi-hop?
                           :k-candidates k-candidates}))
                      all-batches)
         ;; Filter out already-done batches from manifest
         manifest-batches (:batches @manifest-atom)
         cached-results (vec (keep (fn [{:keys [batch-idx]}]
                                     (when-let [mb (get manifest-batches batch-idx)]
                                       (when (= :done (:status mb))
                                         {:batch-idx batch-idx
                                          :questions (or (:questions mb) [])
                                          :trace []
                                          :iterations 0})))
                               work-items))
         pending-items (vec (remove (fn [{:keys [batch-idx]}]
                                      (= :done (:status (get manifest-batches batch-idx))))
                              work-items))
         _ (when (seq cached-results)
             (trove/log! {:level :info :id ::qa-phase2-cached
                          :data {:cached-batches (count cached-results)
                                 :pending-batches (count pending-items)}
                          :msg "Phase 2: Reusing cached batch results from manifest"}))
         result-chan (async/chan (max 1 (count pending-items)))
         _ (when (seq pending-items)
             (async/pipeline-blocking
               parallel
               result-chan
               (map (fn [{:keys [batch-idx batch persona multi-hop? k-candidates]}]
                      (trove/log! {:level :debug :id ::qa-batch
                                   :data {:batch-idx batch-idx :passages (count batch)
                                          :persona persona :multi-hop? multi-hop?}
                                   :msg (str "Generating batch " batch-idx)})
                      (let [max-retries 3]
                        (loop [attempt 1]
                          (let [result
                                (try
                                  (let [forked-env (fork-env-for-query env)
                                        prompt (build-generation-prompt batch batch-idx
                                                 {:persona persona
                                                  :k-candidates k-candidates
                                                  :multi-hop? multi-hop?})
                                        result (query-env!-fn forked-env prompt
                                                 {:spec QUESTIONIFY_SPEC
                                                  :debug? debug?
                                                  :max-iterations 20
                                                  :model effective-model})]
                                    {:batch-idx batch-idx
                                     :questions (or (get-in result [:answer :questions]) [])
                                     :trace (:trace result)
                                     :iterations (or (:iterations result) 0)})
                                  (catch Exception e
                                    (trove/log! {:level :warn :id ::qa-batch-retry
                                                 :data {:batch batch-idx :attempt attempt
                                                        :max-retries max-retries :error (ex-message e)}
                                                 :msg (str "Batch " batch-idx " failed (attempt " attempt "/" max-retries ")")})
                                    nil))]
                            (if (or result (>= attempt max-retries))
                              (let [final-result (or result {:batch-idx batch-idx :questions [] :trace [] :iterations 0})]
                                ;; Checkpoint batch to manifest
                                (qa-manifest/update-qa-batch-status! env manifest-atom batch-idx
                                  {:status (if result :done :error)
                                   :questions (:questions final-result)})
                                final-result)
                              (do (Thread/sleep (* attempt 1000))
                                  (recur (inc attempt)))))))))
               (async/to-chan! pending-items)))
         _ (when (empty? pending-items) (async/close! result-chan))
         ;; Collect new results + merge cached, sort by batch index
         new-results (loop [acc []]
                       (if-let [result (async/<!! result-chan)]
                         (recur (conj acc result))
                         acc))
         generation-results (vec (sort-by :batch-idx (into cached-results new-results)))
         all-questions (vec (mapcat :questions generation-results))
         _ (trove/log! {:level :info :id ::qa-phase2-done
                        :data {:total-generated (count all-questions)}
                        :msg "Phase 2 complete"})

         ;; ===== PHASE 3: Verification + Revision (optional) =====
         {:keys [passed dropped results ver-trace ver-iterations]}
         (if (and verify? (seq all-questions))
           (do
             (trove/log! {:level :info :id ::qa-phase3
                          :data {:questions-to-verify (count all-questions)}
                          :msg "Phase 3: Verifying Q&A pairs against source material"})
             (let [ver-prompt (build-verification-prompt all-questions)
                   ver-result (query-env!-fn env ver-prompt
                                {:spec VERIFICATION_SPEC
                                 :debug? debug?
                                 :max-iterations 15
                                 :model effective-model})
                   verifications (or (get-in ver-result [:answer :verifications]) [])
                   filtered (filter-verified-questions all-questions verifications)
                   ;; Revision sub-phase: revise needs-revision questions instead of dropping
                   revised (when (seq (:needs-revision filtered))
                             (trove/log! {:level :info :id ::qa-phase3-revision
                                          :data {:needs-revision (count (:needs-revision filtered))}
                                          :msg "Phase 3: Revising questions with minor issues"})
                             (revise-questions (:needs-revision filtered) rlm-router))
                   all-passed (into (:passed filtered) (or revised []))]
               (trove/log! {:level :info :id ::qa-phase3-done
                            :data {:passed (count (:passed filtered))
                                   :revised (count (or revised []))
                                   :dropped (count (:dropped filtered))}
                            :msg "Phase 3 complete"})
               {:passed all-passed
                :dropped (:dropped filtered)
                :results (:results filtered)
                :ver-trace (:trace ver-result)
                :ver-iterations (or (:iterations ver-result) 0)}))
           {:passed all-questions :dropped [] :results []
            :ver-trace [] :ver-iterations 0})

         ;; ===== PHASE 4: Deduplication & Final Selection =====
         deduped (deduplicate-questions passed rlm-router)
         final-questions (vec (take target-count deduped))

         ;; ===== Build stats =====
         duration-ms (util/elapsed-since start-time)
         total-iterations (+ (or (:iterations phase1) 0)
                            (reduce + 0 (map :iterations generation-results))
                            ver-iterations)
         stats {:total-generated (count all-questions)
                :passed-verification (count passed)
                :duplicates-removed (- (count passed)
                                      (count deduped))
                :final-count (count final-questions)
                :by-difficulty (frequencies (map :difficulty final-questions))
                :by-category (frequencies (map :category final-questions))}]

     (trove/log! {:level :info :id ::qa-done :data stats
                  :msg "query-env-qa! complete"})

     ;; Mark manifest as completed
     (swap! manifest-atom assoc :completed-at (str (Instant/now)))
     (qa-manifest/write-qa-manifest! env @manifest-atom)

     {:questions final-questions
      :dropped-questions dropped
      :verification-results results
      :phase-traces {:selection (:trace phase1)
                     :generation (mapv :trace generation-results)
                     :verification ver-trace}
      :stats stats
      :iterations total-iterations
      :duration-ms duration-ms})))

;; -----------------------------------------------------------------------------
;; save-qa! — serialize Q&A results to EDN and Markdown
;; -----------------------------------------------------------------------------

(defn save-qa!
  "Saves query-env-qa! results to EDN and/or Markdown files.

   Params:
   `result` - Map. Result from query-env-qa!.
   `path` - String. Base file path without extension.
   `opts` - Map, optional:
     - :formats - Set of keywords. Output formats (default: #{:edn :markdown}).
     - :include-dropped? - Boolean. Include dropped questions (default: false).
     - :include-stats? - Boolean. Include generation stats (default: true).

   Returns:
   Map with :files - vector of written file paths."
  ([result path] (save-qa! result path {}))
  ([result path {:keys [formats include-dropped? include-stats?]
                 :or {formats #{:edn :markdown}
                      include-dropped? false
                      include-stats? true}}]
   (let [written-files (atom [])]

     ;; EDN output
     (when (contains? formats :edn)
       (let [edn-path (str path ".edn")
             data (cond-> {:questions (:questions result)}
                    include-dropped? (assoc :dropped-questions (:dropped-questions result))
                    include-stats? (assoc :stats (:stats result)))]
         (spit edn-path (pr-str data))
         (swap! written-files conj edn-path)
         (trove/log! {:level :info :id ::save-qa-edn
                      :data {:path edn-path :questions (count (:questions result))}
                      :msg "Saved Q&A results as EDN"})))

     ;; Markdown output
     (when (contains? formats :markdown)
       (let [md-path (str path ".md")
             questions (:questions result)
             sb (StringBuilder.)]
         (.append sb "# Generated Q&A Pairs\n\n")
         ;; Stats section
         (when include-stats?
           (let [s (:stats result)]
             (.append sb "## Statistics\n\n")
             (.append sb "| Metric | Value |\n|--------|-------|\n")
             (.append sb (str "| Total generated | " (:total-generated s) " |\n"))
             (.append sb (str "| Passed verification | " (:passed-verification s) " |\n"))
             (.append sb (str "| Duplicates removed | " (:duplicates-removed s) " |\n"))
             (.append sb (str "| Final count | " (:final-count s) " |\n"))
             (.append sb (str "| By difficulty | " (pr-str (:by-difficulty s)) " |\n"))
             (.append sb (str "| By category | " (pr-str (:by-category s)) " |\n\n"))))
         ;; Questions section
         (.append sb "## Questions\n\n")
         (doseq [[i q] (map-indexed vector questions)]
           (.append sb (str "### Q" (inc i)
                         " [" (name (or (:difficulty q) :unknown))
                         " / " (name (or (:category q) :unknown)) "]\n\n"))
           (.append sb (str "**Question:** " (:question q) "\n\n"))
           (.append sb (str "**Answer:** " (:answer q) "\n\n"))
           (when (:evidence-span q)
             (.append sb (str "**Evidence:**\n> " (:evidence-span q) "\n\n")))
           (.append sb (str "**Source:** " (:source-document q)
                         ", page " (:source-page q)
                         (when (:source-section q) (str " — " (:source-section q)))
                         "\n\n"))
           (.append sb "---\n\n"))
         ;; Dropped questions section
         (when (and include-dropped? (seq (:dropped-questions result)))
           (.append sb "## Dropped Questions\n\n")
           (doseq [[i q] (map-indexed vector (:dropped-questions result))]
             (.append sb (str "### Dropped Q" (inc i) "\n\n"))
             (.append sb (str "**Question:** " (:question q) "\n\n"))
             (.append sb (str "**Answer:** " (:answer q) "\n\n"))
             (.append sb "---\n\n")))
         (spit md-path (str sb))
         (swap! written-files conj md-path)
         (trove/log! {:level :info :id ::save-qa-md
                      :data {:path md-path :questions (count questions)}
                      :msg "Saved Q&A results as Markdown"})))

     {:files @written-files})))
