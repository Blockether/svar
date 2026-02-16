(ns com.blockether.svar.internal.rlm.questionify-fixture
  "Fixture generator: questionify chapter.pdf and save as EDN + Markdown.

   Run from REPL:
     (require '[com.blockether.svar.internal.rlm.questionify-fixture :as qf])
     (qf/generate!)

   Or with custom options:
     (qf/generate! {:count 20 :model \"gpt-4o\" :debug? true})

   Prerequisites:
   - OPENAI_API_KEY env var set (or BLOCKETHER_API_KEY)
   - Java classes compiled: clojure -T:build compile-java

   Output:
   - resources-test/chapter-questions/questions.edn
   - resources-test/chapter-questions/questions.md
   - resources-test/chapter-questions/images/*.png"
  (:require
   [com.blockether.svar.core :as svar]
   [com.blockether.svar.internal.rlm.internal.pageindex.core :as pageindex]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private CHAPTER_PDF_PATH
  "Path to chapter.pdf test fixture."
  "resources-test/chapter.pdf")

(def ^:private OUTPUT_PATH
  "Output directory for questionify results."
  "resources-test/chapter-questions")

;; =============================================================================
;; Fixture Generation
;; =============================================================================

(defn generate!
  "Generates Q&A fixture from chapter.pdf using questionify!.

   Full pipeline:
   1. Builds PageIndex from chapter.pdf (vision-based extraction)
   2. Creates RLM environment
   3. Ingests the PageIndex document
   4. Runs questionify! to generate Q&A pairs
   5. Saves results as EDN + Markdown with images

   Params:
   `opts` - Map, optional:
     - :count - Number of Q&A pairs (default: 10)
     - :model - LLM model (default: from config)
     - :difficulty - Set of levels (default: #{:easy :medium :hard})
     - :categories - Set of types (default: #{:factual :inferential :comparative})
     - :verify-answers? - Cross-check answers (default: false)
     - :debug? - Verbose logging (default: false)
     - :output-path - Override output dir (default: resources-test/chapter-questions)

   Returns:
   Map with :questions, :save-result, :duration-ms"
  ([] (generate! {}))
  ([opts]
   (let [output-path (or (:output-path opts) OUTPUT_PATH)
         start-time (System/nanoTime)
         _ (println "=== Questionify Fixture Generator ===")
         _ (println (str "Input:  " CHAPTER_PDF_PATH))
         _ (println (str "Output: " output-path))
         _ (println)

         ;; Step 1: Build PageIndex from PDF
         _ (println "[1/5] Building PageIndex from chapter.pdf...")
         chapter-doc (pageindex/build-index CHAPTER_PDF_PATH
                                            (cond-> {}
                                              (:model opts) (assoc :model (:model opts))))
         _ (println (str "       Pages: " (count (:document/pages chapter-doc))))
         _ (println (str "       TOC entries: " (count (:document/toc chapter-doc))))
         _ (println)

         ;; Step 2: Create RLM environment
         _ (println "[2/5] Creating RLM environment...")
         config (svar/make-config (cond-> {}
                                    (:model opts) (assoc :model (:model opts))))
         env (svar/create-env {:config config})
         _ (println "       Environment ready.")
         _ (println)

         ;; Step 3: Ingest document
         _ (println "[3/5] Ingesting PageIndex document...")
         ingest-result (svar/ingest! env [chapter-doc])
         _ (println (str "       Nodes stored: " (:nodes-stored (first ingest-result))))
         _ (println)

         ;; Step 4: Run questionify!
         q-opts (merge {:count 10
                        :save-path output-path}
                       (select-keys opts [:count :difficulty :categories :model
                                          :verify-answers? :debug? :max-iterations]))
         _ (println (str "[4/5] Running questionify! (target: " (:count q-opts) " questions)..."))
         q-result (svar/questionify! env q-opts)
         _ (println (str "       Generated: " (count (:questions q-result)) " questions"))
         _ (println (str "       Iterations: " (:iterations q-result)))
         _ (println (str "       Duration: " (:duration-ms q-result) "ms"))
         _ (println)

         ;; Step 5: Cleanup
         _ (println "[5/5] Disposing environment...")
         _ (svar/dispose! env)

         elapsed-ms (/ (- (System/nanoTime) start-time) 1e6)]
     (println)
     (println "=== Complete ===")
     (println (str "Total time: " (long elapsed-ms) "ms"))
     (println (str "EDN:      " output-path "/questions.edn"))
     (println (str "Markdown: " output-path "/questions.md"))
     (println (str "Images:   " output-path "/images/"))
     {:questions (:questions q-result)
      :trace (:trace q-result)
      :iterations (:iterations q-result)
      :duration-ms (long elapsed-ms)})))
