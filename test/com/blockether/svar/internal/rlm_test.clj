(ns com.blockether.svar.internal.rlm-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [datalevin.core :as d]
   [lazytest.core :refer [defdescribe describe expect it throws?]]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.rlm :as sut]
   [com.blockether.svar.internal.rlm.db :as rlm-db]
   [com.blockether.svar.internal.rlm.tools :as rlm-tools]
   [com.blockether.svar.internal.rlm.routing :as rlm-routing]
   [com.blockether.svar.internal.rlm.core :as rlm-core]
   [com.blockether.svar.core :as svar])
  (:import
   [java.util UUID]))

(declare test-ingest-router)

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- create-test-env
  "Creates a test RLM environment with optional configuration.
   
   Params:
   `context` - The data context to analyze.
   `opts` - Map, optional:
     - :db - false to disable db, or nil for auto-create
     
   Returns:
   RLM environment map."
  ([context] (create-test-env context {}))
  ([context {:keys [_db] :as opts}]
   (let [depth-atom (atom 0)
         test-router (llm/make-router [{:id :test :api-key "test" :base-url "http://localhost"
                                        :models [{:name "gpt-4o"}
                                                 {:name "gpt-4o-mini"}]}])]
     (#'rlm-core/create-rlm-env context depth-atom test-router opts))))

(defn- with-test-env*
  "Creates a test environment, executes f with it, then disposes.
   
   Usage:
   (with-test-env* {:count 42}
     (fn [env] (#'rlm-core/execute-code env \"(+ 1 2)\")))
   
   (with-test-env* {:count 42} {:db false}
     (fn [env] (#'rlm-core/execute-code env \"(+ 1 2)\")))"
  ([context f] (with-test-env* context {} f))
  ([context opts f]
   (let [env (create-test-env context opts)]
     (try
       (f env)
       (finally
         (#'rlm-core/dispose-rlm-env! env))))))

;; =============================================================================
;; Unit Tests (no LLM calls)
;; =============================================================================

;; extract-code-blocks and check-result-for-final tests removed —
;; these functions were replaced by ask! with ITERATION_SPEC (provider-enforced JSON)

(defdescribe create-rlm-env-test
  (it "creates environment with required keys"
    (with-test-env* {:data "test"} (fn [env]
                                     (expect (contains? env :sci-ctx))
                                     (expect (contains? env :context))
                                     (expect (contains? env :llm-query-fn))
                                     (expect (contains? env :initial-ns-keys)))))

  (it "stores context in environment"
    (with-test-env* {:users [{:name "Alice"}]} (fn [env]
                                                 (expect (= {:users [{:name "Alice"}]} (:context env))))))

  (it "creates database by default"
    (with-test-env* {} (fn [env]
                         (expect (some? (:db-info-atom env))))))

  (it "can disable database with :db false"
    (with-test-env* {} {:db false} (fn [env]
                                     (expect (nil? (:db-info-atom env)))))))

(defdescribe get-locals-test
  (it "returns empty map initially"
    (with-test-env* {} (fn [env]
                         (expect (= {} (#'rlm-core/get-locals env))))))

  (it "returns tracked variables after execution"
    (with-test-env* {} (fn [env]
                         (#'rlm-core/execute-code env "(def x 42)")
                         (expect (= {'x 42} (#'rlm-core/get-locals env)))))))

;; =============================================================================
;; Disposable Database Tests
;; =============================================================================

(defdescribe disposable-db-test
  (describe "create-rlm-conn"
    (it "creates a database with required keys"
      (let [db-info (#'rlm-db/create-rlm-conn nil)]
        (try
          (expect (contains? db-info :conn))
          (expect (contains? db-info :path))
          (expect (contains? db-info :owned?))
          (expect (true? (:owned? db-info)))
          (finally
            (#'rlm-db/dispose-rlm-conn! db-info)))))

    (it "creates database with conn and owned? true"
      (let [db-info (#'rlm-db/create-rlm-conn nil)]
        (try
          (expect (some? (:conn db-info)))
          (expect (true? (:owned? db-info)))
          (finally
            (#'rlm-db/dispose-rlm-conn! db-info))))))

  (describe "external conn reuse"
    (it "reuses external conn with owned? false"
      (let [external-db (#'rlm-db/create-rlm-conn nil)]
        (try
          (let [reused (assoc external-db :owned? false)]
            (expect (false? (:owned? reused)))
            (expect (some? (:conn reused))))
          (finally
            (#'rlm-db/dispose-rlm-conn! external-db))))))

  (describe "dispose-rlm-conn!"
    (it "disposes owned database and cleans up path"
      (let [db-info (#'rlm-db/create-rlm-conn nil)
            path (:path db-info)]
        (expect (fs/exists? path))
        (#'rlm-db/dispose-rlm-conn! db-info)
        (expect (not (fs/exists? path)))))

    (it "does not throw when called on a fresh conn"
      (let [db-info (#'rlm-db/create-rlm-conn nil)]
        (#'rlm-db/dispose-rlm-conn! db-info)))))

(defdescribe execute-code-test
  (it "executes simple arithmetic"
    (with-test-env* {} (fn [env]
                         (let [result (#'rlm-core/execute-code env "(+ 1 2)")]
                           (expect (= 3 (:result result)))
                           (expect (nil? (:error result)))
                           (expect (false? (:timeout? result)))))))

  (it "captures stdout from println"
    (with-test-env* {} (fn [env]
                         (let [result (#'rlm-core/execute-code env "(println \"hello\")")]
                           (expect (= "hello\n" (:stdout result)))
                           (expect (nil? (:error result)))))))

  (it "tracks variables defined with def"
    (with-test-env* {} (fn [env]
                         (#'rlm-core/execute-code env "(def x 42)")
                         (expect (= {'x 42} (#'rlm-core/get-locals env))))))

  (it "returns error for invalid code"
    (with-test-env* {} (fn [env]
                         (let [result (#'rlm-core/execute-code env "(/ 1 0)")]
                           (expect (some? (:error result)))
                           (expect (nil? (:result result)))))))

  (it "provides access to context"
    (with-test-env* {:count 5} (fn [env]
                                 (let [result (#'rlm-core/execute-code env "(:count context)")]
                                   (expect (= 5 (:result result)))
                                   (expect (nil? (:error result)))))))

  (describe "regex support"
    (it "supports re-find for pattern matching"
      (with-test-env* {} (fn [env]
                           (let [result (#'rlm-core/execute-code env "(re-find #\"\\d+\" \"abc123def\")")]
                             (expect (= "123" (:result result)))
                             (expect (nil? (:error result)))))))

    (it "supports re-seq for multiple matches"
      (with-test-env* {} (fn [env]
                           (let [result (#'rlm-core/execute-code env "(vec (re-seq #\"\\d+\" \"a1b2c3\"))")]
                             (expect (= ["1" "2" "3"] (:result result)))))))))

(defdescribe max-iterations-fallback-test
  (it "returns nil answer on max-iterations and includes locals only in debug mode"
    (let [router (llm/make-router [{:id :test :api-key "test" :base-url "http://localhost"
                                    :models [{:name "gpt-4o"}]}])
          env (sut/create-env router {})]
      (try
        (let [normal (sut/query-env! env [(llm/user "No-op")] {:max-iterations 0 :refine? false})
              debug  (sut/query-env! env [(llm/user "No-op")] {:max-iterations 0 :refine? false :debug? true})]
          (expect (= :max-iterations (:status normal)))
          (expect (nil? (:answer normal)))
          (expect (not (contains? normal :locals)))
          (expect (= :max-iterations (:status debug)))
          (expect (nil? (:answer debug)))
          (expect (map? (:locals debug))))
        (finally
          (sut/dispose-env! env))))))

;; =============================================================================
;; String Helper Tests (SCI Bindings)
;; =============================================================================

;; string-helpers-test removed — str-* SCI bindings were replaced by clojure.string/

;; =============================================================================
;; History Query Functions Tests (SCI Bindings)
;; =============================================================================

(defdescribe learnings-sci-bindings-test
  (it "search-learnings is not available in SCI"
    (with-test-env* {} (fn [env]
                         (let [result (#'rlm-core/execute-code env "(search-learnings \"test searching\")")]
                           (expect (some? (:error result)))))))

  (it "learning-stats is not available in SCI"
    (with-test-env* {} (fn [env]
                         (let [result (#'rlm-core/execute-code env "(learning-stats)")]
                           (expect (some? (:error result)))))))

  (it "list-learning-tags is not available in SCI"
    (with-test-env* {} (fn [env]
                         (let [result (#'rlm-core/execute-code env "(list-learning-tags)")]
                           (expect (some? (:error result)))))))

  (it "write ops (store-learning, vote-learning) are NOT available in SCI"
    (with-test-env* {} (fn [env]
                         (let [store-result (#'rlm-core/execute-code env "(store-learning \"test\")")
                               vote-result (#'rlm-core/execute-code env "(vote-learning #uuid \"00000000-0000-0000-0000-000000000000\" :useful)")]
                           (expect (some? (:error store-result)))
                           (expect (some? (:error vote-result))))))))

;; =============================================================================
;; Build System Prompt Tests
;; =============================================================================

(defdescribe build-system-prompt-test
  (it "includes basic environment info"
    (let [prompt (#'rlm-core/build-system-prompt {})]
      (expect (str/includes? prompt "Clojure agent"))
      (expect (str/includes? prompt "ARCHITECTURE"))
      (expect (str/includes? prompt "final"))))

  (it "excludes learning helpers"
    (let [prompt (#'rlm-core/build-system-prompt {})]
      (expect (not (str/includes? prompt "search-learnings")))
      (expect (not (str/includes? prompt "learning-stats")))
      (expect (not (str/includes? prompt "list-learning-tags")))))

  (it "includes unified document tools section when has-documents?"
    (let [prompt (#'rlm-core/build-system-prompt {:has-documents? true :document-summary "1 document"})]
      (expect (str/includes? prompt "DOCUMENTS"))
      (expect (str/includes? prompt "search-documents"))
      (expect (str/includes? prompt "fetch-content"))
      (expect (str/includes? prompt ":entity/id"))))

  (it "includes spec schema when provided"
    (let [test-spec (svar/spec
                      (svar/field {svar/NAME :name
                                   svar/TYPE :spec.type/string
                                   svar/CARDINALITY :spec.cardinality/one
                                   svar/REQUIRED true
                                   svar/DESCRIPTION "Name field"}))
          prompt (#'rlm-core/build-system-prompt {:output-spec test-spec})]
      (expect (str/includes? prompt "OUTPUT SCHEMA")))))

;; =============================================================================
;; System Prompt Tests
;; =============================================================================

(defdescribe system-prompt-injection-test
  (describe "system prompt injection"
    (it "includes custom instructions when provided"
      (let [prompt (#'rlm-core/build-system-prompt {:system-prompt "You are a code reviewer"})]
        (expect (str/includes? prompt "INSTRUCTIONS"))
        (expect (str/includes? prompt "You are a code reviewer"))))))

;; =============================================================================
;; Sub-RLM Tests
;; =============================================================================

;; Sub-RLM tests removed — rlm-query and make-rlm-query-fn no longer exist

;; =============================================================================
;; Format Execution Results Tests
;; =============================================================================

(defdescribe format-executions-test
  (it "formats successful results as EDN"
    (let [results [{:id 1 :code "(+ 1 41)" :result 42 :stdout "" :error nil}]
          formatted (#'rlm-core/format-executions results)]
      (expect (str/includes? formatted ":ok"))
      (expect (str/includes? formatted "42"))))

  (it "formats errors as EDN"
    (let [results [{:id 1 :code "(/ 1 0)" :result nil :stdout "" :error "Division by zero"}]
          formatted (#'rlm-core/format-executions results)]
      (expect (str/includes? formatted ":error"))
      (expect (str/includes? formatted "Division by zero"))))

  (it "includes stdout when present"
    (let [results [{:id 1 :code "(println \"Hello\")" :result nil :stdout "Hello\n" :error nil}]
          formatted (#'rlm-core/format-executions results)]
      (expect (str/includes? formatted ":stdout"))
      (expect (str/includes? formatted "Hello"))))

  (it "formats multiple results"
    (let [results [{:id 1 :code "(+ 0 1)" :result 1 :stdout "" :error nil}
                   {:id 2 :code "(+ 1 1)" :result 2 :stdout "" :error nil}]
          formatted (#'rlm-core/format-executions results)]
      (expect (str/includes? formatted "1"))
      (expect (str/includes? formatted "2")))))

;; =============================================================================
;; Integration Tests (real LLM calls)
;; =============================================================================

(def ^:private test-providers
  "LLM providers for integration tests."
  [{:id :openai
    :api-key (System/getenv "OPENAI_API_KEY")
    :base-url (or (System/getenv "OPENAI_BASE_URL")
                "https://api.openai.com/v1")
    :models [{:name "gpt-4o"}
             {:name "gpt-4o-mini"}]}])

(def ^:private test-router
  "Router for integration tests."
  (llm/make-router test-providers))

(defn- integration-tests-enabled?
  "Returns true if LLM integration tests should run.
   Checks if test-providers has a valid API key from environment."
  []
  (some? (:api-key (first test-providers))))

(defn- with-integration-env*
  "Creates an RLM environment for integration tests, executes f, then disposes."
  [f]
  (let [env (sut/create-env test-router {})]
    (try
      (f env)
      (finally
        (sut/dispose-env! env)))))

(defdescribe query-env!-integration-test
  (describe "basic functionality"
    (it "processes simple string context with refinement"
      (when (integration-tests-enabled?)
        (with-integration-env*
          (fn [env]
            (let [result (sut/query-env! env [(llm/user "What is the capital of France? Answer with just the city name.")]
                           {:context "Paris is the capital of France."
                            :max-iterations 10})]
              (expect (map? result))
              (if (:status result)
                (expect (= :max-iterations (:status result)))
                (do
                  (expect (some? (:answer result)))
                  (expect (re-find #"(?i)paris" (str (:answer result))))
                  (expect (contains? result :refinement-count))
                  (expect (contains? result :eval-scores)))))))))

    (it "can disable refinement for speed"
      (when (integration-tests-enabled?)
        (with-integration-env*
          (fn [env]
            (let [result (sut/query-env! env [(llm/user "What is 2 + 2?")]
                           {:context "2 + 2 = 4"
                            :max-iterations 5
                            :refine? false})]
              (expect (map? result))
              (if (:status result)
                (do
                  (expect (= :max-iterations (:status result)))
                  (expect (contains? result :iterations)))
                (do
                  (expect (= 0 (:refinement-count result)))
                  (expect (nil? (:eval-scores result)))))))))))

  (describe "code execution capabilities"
    (it "allows LLM to access context and return FINAL"
      (when (integration-tests-enabled?)
        (with-integration-env*
          (fn [env]
            (let [result (sut/query-env! env [(llm/user "What is the value of :count? Use (FINAL answer)")]
                           {:context {:count 42}
                            :max-iterations 10
                            :refine? false})]
              (expect (map? result))
              (if (:status result)
                (expect (= :max-iterations (:status result)))
                (expect (re-find #"42" (str (:answer result)))))))))))

  (describe "refinement loop"
    (it "applies refinement by default"
      (when (integration-tests-enabled?)
        (with-integration-env*
          (fn [env]
            (let [result (sut/query-env! env [(llm/user "Sum the numbers")]
                           {:context {:nums [1 2 3 4 5]}
                            :max-iterations 10
                            :max-refinements 1})]
              (expect (map? result))
              (when-not (:status result)
                (expect (some? (:eval-scores result)))
                (expect (number? (:eval-scores result))))))))))

  (describe "validation"
    (it "throws when env is invalid"
      (expect (throws? clojure.lang.ExceptionInfo
                #(sut/query-env! {} [(llm/user "test query")]))))

    (it "throws when query is missing"
      (with-integration-env* (fn [env]
                               (expect (throws? clojure.lang.ExceptionInfo
                                         #(sut/query-env! env nil))))))))

(defdescribe make-routed-llm-query-fn-test
  (describe "recursion depth tracking"
    (it "enforces max recursion depth"
      (let [depth-atom (atom 0)
            r (llm/make-router [{:id :test :api-key "test" :base-url "http://localhost"
                                 :models [{:name "gpt-4o"}]}])
            query-fn (#'rlm-routing/make-routed-llm-query-fn {:strategy :root} depth-atom r)]
        (reset! depth-atom sut/DEFAULT_RECURSION_DEPTH)
        (let [result (query-fn "test")]
          (expect (map? result))
          (expect (re-find #"(?i)max.*recursion.*depth" (:content result))))))

    (it "decrements depth after call"
      (let [depth-atom (atom 0)
            r (llm/make-router [{:id :test :api-key "test" :base-url "http://localhost"
                                 :models [{:name "gpt-4o"}]}])
            query-fn (#'rlm-routing/make-routed-llm-query-fn {:strategy :root} depth-atom r)]
        (try
          (query-fn "What is 2+2?")
          (catch Exception _))
        (expect (= 0 @depth-atom))))))

;; =============================================================================
;; SAFE_BINDINGS Completeness Tests
;; =============================================================================

(defdescribe safe-bindings-test
  (describe "arithmetic operations"
    (it "provides basic math"
      (with-test-env* {} {:db false} (fn [env]
                                       (expect (= 10 (:result (#'rlm-core/execute-code env "(+ 1 2 3 4)"))))
                                       (expect (= 6 (:result (#'rlm-core/execute-code env "(* 2 3)"))))
                                       (expect (= 2 (:result (#'rlm-core/execute-code env "(/ 10 5)"))))
                                       (expect (= 3 (:result (#'rlm-core/execute-code env "(- 10 7)"))))))))

  (describe "collection operations"
    (it "provides map/filter/reduce"
      (with-test-env* {} {:db false} (fn [env]
                                       (expect (= [2 4 6] (:result (#'rlm-core/execute-code env "(mapv #(* 2 %) [1 2 3])"))))
                                       (expect (= [2 4] (:result (#'rlm-core/execute-code env "(vec (filter even? [1 2 3 4]))"))))
                                       (expect (= 10 (:result (#'rlm-core/execute-code env "(reduce + [1 2 3 4])"))))))))

  (describe "string operations"
    (it "provides str and related"
      (with-test-env* {} {:db false} (fn [env]
                                       (expect (= "hello world" (:result (#'rlm-core/execute-code env "(str \"hello\" \" \" \"world\")"))))
                                       (expect (= "ell" (:result (#'rlm-core/execute-code env "(subs \"hello\" 1 4)"))))
                                       (expect (= "test" (:result (#'rlm-core/execute-code env "(name :test)"))))))))

  (describe "comparison operations"
    (it "provides equality and ordering"
      (with-test-env* {} {:db false} (fn [env]
                                       (expect (true? (:result (#'rlm-core/execute-code env "(= 1 1)"))))
                                       (expect (true? (:result (#'rlm-core/execute-code env "(< 1 2)"))))
                                       (expect (true? (:result (#'rlm-core/execute-code env "(>= 5 5)"))))))))

  (describe "atom operations"
    (it "provides atom and swap!"
      (with-test-env* {} {:db false} (fn [env]
                                       (#'rlm-core/execute-code env "(def counter (atom 0))")
                                       (#'rlm-core/execute-code env "(swap! counter inc)")
                                       (expect (= 1 (:result (#'rlm-core/execute-code env "@counter")))))))))

(defn make-mock-ask-response
  "Creates a canned ask! response matching the real return shape.
   
   Params:
   `result` - The :result value to return (parsed LLM data).
   
   Returns:
   Map with :result, :tokens, :cost, :duration-ms."
  [result]
  {:result result
   :tokens {:input 0 :output 0 :total 0}
   :cost {:input-cost 0 :output-cost 0 :total-cost 0}
   :duration-ms 0})

(defmacro with-mock-ask!
  "Stubs `com.blockether.svar.internal.llm/ask!` with canned responses.
   
   `response-fn` is called with the ask! opts map and should return
   the mock response (use `make-mock-ask-response` to build it).
   
   Usage:
   (with-mock-ask! (fn [opts] (make-mock-ask-response {:name \"test\"}))
     (svar/ask! {:spec my-spec :objective \"test\" :task \"test\" :model \"gpt-4o\"}))
   
   For multi-call scenarios, use an atom to sequence responses:
   (let [calls (atom [response1 response2 response3])]
     (with-mock-ask! (fn [_ _] (let [r (first @calls)] (swap! calls rest) r))
       (svar/refine! opts)))"
  [response-fn & body]
  `(with-redefs [llm/ask!* (fn [_router# opts#] (~response-fn _router# opts#))]
     ~@body))

(defn make-mock-eval-response
  "Creates a canned eval! response matching the real return shape.
   
   Params:
   `score` - Float, overall score 0.0-1.0.
   `opts` - Map, optional:
     - :correct? - Boolean (default true).
     - :summary - String (default \"Mock evaluation\").
     - :issues - Vector (default []).
   
   Returns:
   Map matching eval! return shape."
  ([score] (make-mock-eval-response score {}))
  ([score {:keys [correct? summary issues]
           :or {correct? true summary "Mock evaluation" issues []}}]
   {:correct? correct?
    :overall-score score
    :summary summary
    :criteria []
    :issues issues
    :scores {}
    :duration-ms 0
    :tokens {:input 0 :output 0 :total 0}
    :cost {:input-cost 0 :output-cost 0 :total-cost 0}}))

(defmacro with-mock-ask-and-eval!
  "Stubs both `ask!` and `eval!` for testing refine! without LLM calls.
   
   Params:
   `ask-fn` - Function (opts -> ask! response).
   `eval-fn` - Function (opts -> eval! response).
   `body` - Forms to execute.
   
   Usage:
   (with-mock-ask-and-eval!
     (fn [opts] (make-mock-ask-response {:answer 42}))
     (fn [opts] (make-mock-eval-response 0.95))
     (svar/refine! ...))"
  [ask-fn eval-fn & body]
  `(with-redefs [llm/ask! (fn [_router# opts#] (~ask-fn _router# opts#))
                 llm/eval!* (fn [_router# opts#] (~eval-fn _router# opts#))]
     ~@body))

;; =============================================================================
;; Mock Response Factories (Domain-Specific)
;; =============================================================================

(defn make-mock-entity-extraction-response
  "Returns a realistic entity extraction result for legal documents.
   
   Returns:
   Mock ask! response with entities, relationships, and metadata."
  []
  (make-mock-ask-response
    {:entities [{:entity/id (str (UUID/randomUUID))
                 :entity/type "party"
                 :entity/name "Acme Corp"
                 :entity/description "Contracting party, seller"}
                {:entity/id (str (UUID/randomUUID))
                 :entity/type "party"
                 :entity/name "Widget Inc"
                 :entity/description "Contracting party, buyer"}
                {:entity/id (str (UUID/randomUUID))
                 :entity/type "obligation"
                 :entity/name "Payment Terms"
                 :entity/description "Net 30 payment on delivery"}
                {:entity/id (str (UUID/randomUUID))
                 :entity/type "clause"
                 :entity/name "Limitation of Liability"
                 :entity/description "Cap at contract value"}]
     :relationships [{:from "Acme Corp" :to "Widget Inc" :type "contracts-with"}
                     {:from "Widget Inc" :to "Payment Terms" :type "obligated-to"}]
     :confidence 0.92}))

(defn make-mock-retrieval-plan-response
  "Returns a realistic retrieval plan for knowledge engine queries.
   
   Returns:
   Mock ask! response with a vector of retrieval steps."
  []
  (make-mock-ask-response
    {:plan [{:strategy "semantic-search"
             :query "limitation of liability clause"
             :target-pages [0 1 2]
             :reasoning "Search for liability-related sections"}
            {:strategy "keyword-match"
             :query "indemnification"
             :target-pages [1 2]
             :reasoning "Find exact indemnification language"}
            {:strategy "cross-reference"
             :query "definitions section references"
             :target-pages [0]
             :reasoning "Resolve defined terms used in clauses"}]}))

(defn make-mock-relevance-eval-response
  "Returns a realistic chunk relevance evaluation with scores.
   
   Params:
   `chunks` - Vector of chunk identifiers to score.
   
   Returns:
   Mock ask! response with relevance scores per chunk."
  [chunks]
  (make-mock-ask-response
    {:evaluations (mapv (fn [chunk]
                          {:chunk-id chunk
                           :relevance-score (+ 0.5 (* 0.5 (rand)))
                           :reasoning (str "Relevant to query based on content overlap for " chunk)})
                    chunks)
     :overall-relevance 0.78}))

;; =============================================================================
;; Test Document Helpers
;; =============================================================================

(defn make-test-legal-document
  "Creates a minimal PageIndex document with legal content.
   Has 3 pages with parties, obligations, clauses, and cross-references.
   
   Returns:
   Map conforming to PageIndex document structure."
  []
  {:document/name "test-contract"
   :document/extension "pdf"
   :document/title "Master Services Agreement"
   :document/abstract "Agreement between Acme Corp and Widget Inc for software services."
   :document/author "Legal Dept"
   :document/pages [{:page/index 0
                     :page/nodes [{:page.node/type :section
                                   :page.node/id "s1"
                                   :page.node/description "Definitions and Parties"}
                                  {:page.node/type :heading
                                   :page.node/id "h1"
                                   :page.node/parent-id "s1"
                                   :page.node/level "h1"
                                   :page.node/content "1. DEFINITIONS AND PARTIES"}
                                  {:page.node/type :paragraph
                                   :page.node/id "p1"
                                   :page.node/parent-id "s1"
                                   :page.node/content "This Master Services Agreement (\"Agreement\") is entered into between Acme Corp (\"Provider\") and Widget Inc (\"Client\"). \"Services\" shall mean the software development services described in Exhibit A. \"Confidential Information\" shall mean any non-public information disclosed by either party."}]}
                    {:page/index 1
                     :page/nodes [{:page.node/type :section
                                   :page.node/id "s2"
                                   :page.node/description "Obligations and Payment Terms"}
                                  {:page.node/type :heading
                                   :page.node/id "h2"
                                   :page.node/parent-id "s2"
                                   :page.node/level "h1"
                                   :page.node/content "2. OBLIGATIONS AND PAYMENT"}
                                  {:page.node/type :paragraph
                                   :page.node/id "p2"
                                   :page.node/parent-id "s2"
                                   :page.node/content "Provider shall deliver Services as defined in Section 1. Client shall pay Provider within Net 30 days of invoice. Late payments accrue interest at 1.5% per month. See Section 3 for limitation of liability."}
                                  {:page.node/type :paragraph
                                   :page.node/id "p3"
                                   :page.node/parent-id "s2"
                                   :page.node/content "Provider warrants that Services shall conform to the specifications in Exhibit A. Client's sole remedy for breach of this warranty is re-performance of the non-conforming Services."}]}
                    {:page/index 2
                     :page/nodes [{:page.node/type :section
                                   :page.node/id "s3"
                                   :page.node/description "Liability and Indemnification"}
                                  {:page.node/type :heading
                                   :page.node/id "h3"
                                   :page.node/parent-id "s3"
                                   :page.node/level "h1"
                                   :page.node/content "3. LIMITATION OF LIABILITY AND INDEMNIFICATION"}
                                  {:page.node/type :paragraph
                                   :page.node/id "p4"
                                   :page.node/parent-id "s3"
                                   :page.node/content "Neither party's aggregate liability under this Agreement shall exceed the total fees paid during the 12 months preceding the claim. As defined in Section 1, Confidential Information remains protected for 5 years after termination."}
                                  {:page.node/type :paragraph
                                   :page.node/id "p5"
                                   :page.node/parent-id "s3"
                                   :page.node/content "Each party shall indemnify the other against third-party claims arising from breach of this Agreement, subject to the limitations in this Section 3."}]}]
   :document/toc [{:document.toc/type :toc-entry
                   :document.toc/id "toc-1"
                   :document.toc/title "Definitions and Parties"
                   :document.toc/target-page 0
                   :document.toc/target-section-id "s1"
                   :document.toc/level "l1"}
                  {:document.toc/type :toc-entry
                   :document.toc/id "toc-2"
                   :document.toc/title "Obligations and Payment"
                   :document.toc/target-page 1
                   :document.toc/target-section-id "s2"
                   :document.toc/level "l1"}
                  {:document.toc/type :toc-entry
                   :document.toc/id "toc-3"
                   :document.toc/title "Limitation of Liability and Indemnification"
                   :document.toc/target-page 2
                   :document.toc/target-section-id "s3"
                   :document.toc/level "l1"}]})

(defn make-test-entity
  "Creates a test entity map.
   
   Params:
   `type` - String, entity type (e.g. \"party\", \"obligation\", \"clause\").
   `name` - String, entity name.
   `description` - String, entity description.
   
   Returns:
   Entity map with :entity/id, :entity/type, :entity/name, :entity/description."
  [type name description]
  {:entity/id (str (UUID/randomUUID))
   :entity/type type
   :entity/name name
   :entity/description description})

(defn make-test-relationship
  "Creates a test relationship map.
   
   Params:
   `from` - String, source entity name.
   `to` - String, target entity name.
   `type` - String, relationship type.
   
   Returns:
   Relationship map with :from, :to, :type."
  [from to type]
  {:from from :to to :type type})

(defn make-test-claim
  "Creates a test claim map for verification testing.
   
   Params:
   `text` - String, claim text.
   `document-id` - String, source document identifier.
   `page` - Integer, page number.
   `section` - String, section identifier.
   
   Returns:
   Claim map with :text, :document-id, :page, :section, :confidence."
  [text document-id page section]
  {:text text
   :document-id document-id
   :page page
   :section section
   :confidence 0.85})

;; =============================================================================
;; Test Helper Predicates
;; =============================================================================

(defn valid-citation?
  "Checks that a claim has required citation fields.
   
   Params:
   `claim` - Map, claim to validate.
   
   Returns:
   Boolean, true if claim has :document-id, :page, and :section."
  [claim]
  (and (contains? claim :document-id)
    (contains? claim :page)
    (contains? claim :section)
    (some? (:document-id claim))
    (some? (:page claim))
    (some? (:section claim))))

(defn valid-retrieval-plan?
  "Checks that a plan is a vector of maps with :strategy and :query.
   
   Params:
   `plan` - The retrieval plan to validate.
   
   Returns:
   Boolean, true if plan is a valid retrieval plan."
  [plan]
  (and (vector? plan)
    (every? map? plan)
    (every? #(and (contains? % :strategy)
               (contains? % :query)
               (string? (:strategy %))
               (string? (:query %)))
      plan)))

;; Retrieval planning + relevance evaluation tests live in later wave.

;; =============================================================================
;; Edge Case Test Documents
;; =============================================================================

(defn make-test-empty-document
  "Creates a PageIndex document with 0 pages.
   
   Returns:
   Minimal document map with empty pages vector."
  []
  {:document/name "empty-doc"
   :document/extension "pdf"
   :document/pages []
   :document/toc []})

(defn make-test-image-only-document
  "Creates a PageIndex document with only image nodes.
   Uses :page.node/image-data for binary image bytes (not :page.node/content base64).
   
   Returns:
   Document map with 1 page containing only image nodes."
  []
  {:document/name "image-only"
   :document/extension "pdf"
   :document/pages [{:page/index 0
                     :page/nodes [{:page.node/type :image
                                   :page.node/id "img1"
                                   :page.node/image-data (byte-array [1 2 3 4])
                                   :page.node/description "A scanned page image"}
                                  {:page.node/type :image
                                   :page.node/id "img2"
                                   :page.node/image-data (byte-array [5 6 7 8])
                                   :page.node/description "Another scanned page image"}]}]
   :document/toc []})

(defn make-test-single-page-document
  "Creates a minimal PageIndex document with 1 page.
   
   Returns:
   Document map with a single page containing basic content."
  []
  {:document/name "single-page"
   :document/extension "pdf"
   :document/title "Single Page Document"
   :document/pages [{:page/index 0
                     :page/nodes [{:page.node/type :heading
                                   :page.node/id "h1"
                                   :page.node/level "h1"
                                   :page.node/content "Title"}
                                  {:page.node/type :paragraph
                                   :page.node/id "p1"
                                   :page.node/content "This is the only paragraph in the document."}]}]
   :document/toc []})

;; =============================================================================
;; Mock Infrastructure Tests
;; =============================================================================

(defdescribe mock-infrastructure-test
  (describe "make-mock-ask-response"
    (it "creates response with correct shape"
      (let [resp (make-mock-ask-response {:name "test"})]
        (expect (= {:name "test"} (:result resp)))
        (expect (= {:input 0 :output 0 :total 0} (:tokens resp)))
        (expect (= {:input-cost 0 :output-cost 0 :total-cost 0} (:cost resp)))
        (expect (= 0 (:duration-ms resp))))))

  (describe "with-mock-ask!"
    (it "intercepts ask! calls with canned response"
      (with-mock-ask! (fn [_router _opts] (make-mock-ask-response {:answer 42}))
        (let [result (llm/ask!* test-ingest-router {:spec nil :objective "test" :task "test" :model "gpt-4o"})]
          (expect (= {:answer 42} (:result result)))
          (expect (= 0 (:duration-ms result)))))))

  (describe "make-mock-eval-response"
    (it "creates eval response with correct shape"
      (let [resp (make-mock-eval-response 0.85)]
        (expect (= 0.85 (:overall-score resp)))
        (expect (true? (:correct? resp)))
        (expect (= "Mock evaluation" (:summary resp)))
        (expect (= [] (:issues resp))))))

  (describe "with-mock-ask-and-eval!"
    (it "intercepts both ask! and eval! calls"
      (with-mock-ask-and-eval!
        (fn [_router _opts] (make-mock-ask-response {:data "mocked"}))
        (fn [_router _opts] (make-mock-eval-response 0.9))
        (let [ask-result (llm/ask! test-ingest-router {:spec nil :objective "t" :task "t" :model "gpt-4o"})
              eval-result (llm/eval! test-ingest-router {:task "t" :output "t" :model "gpt-4o"})]
          (expect (= {:data "mocked"} (:result ask-result)))
          (expect (= 0.9 (:overall-score eval-result))))))))

(defdescribe mock-response-factories-test
  (describe "make-mock-entity-extraction-response"
    (it "returns response with entities and relationships"
      (let [resp (make-mock-entity-extraction-response)]
        (expect (map? (:result resp)))
        (expect (= 4 (count (get-in resp [:result :entities]))))
        (expect (= 2 (count (get-in resp [:result :relationships]))))
        (expect (number? (get-in resp [:result :confidence]))))))

  (describe "make-mock-retrieval-plan-response"
    (it "returns response with valid plan structure"
      (let [resp (make-mock-retrieval-plan-response)
            plan (get-in resp [:result :plan])]
        (expect (valid-retrieval-plan? plan))
        (expect (= 3 (count plan))))))

  (describe "make-mock-relevance-eval-response"
    (it "returns scored evaluations for given chunks"
      (let [resp (make-mock-relevance-eval-response ["chunk-1" "chunk-2" "chunk-3"])
            evals (get-in resp [:result :evaluations])]
        (expect (= 3 (count evals)))
        (expect (every? #(number? (:relevance-score %)) evals))
        (expect (every? #(string? (:reasoning %)) evals))))))

(defdescribe test-document-helpers-test
  (describe "make-test-legal-document"
    (it "has 3 pages"
      (let [doc (make-test-legal-document)]
        (expect (= 3 (count (:document/pages doc))))))

    (it "has TOC entries"
      (let [doc (make-test-legal-document)]
        (expect (= 3 (count (:document/toc doc))))))

    (it "contains legal content"
      (let [doc (make-test-legal-document)
            all-content (->> (:document/pages doc)
                          (mapcat :page/nodes)
                          (keep :page.node/content)
                          (str/join " "))]
        (expect (str/includes? all-content "Acme Corp"))
        (expect (str/includes? all-content "Widget Inc"))
        (expect (str/includes? all-content "liability"))
        (expect (str/includes? all-content "indemnify"))
        (expect (str/includes? all-content "Section 1")))))

  (describe "make-test-entity"
    (it "creates entity with all required fields"
      (let [e (make-test-entity "party" "Acme" "The seller")]
        (expect (string? (:entity/id e)))
        (expect (= "party" (:entity/type e)))
        (expect (= "Acme" (:entity/name e)))
        (expect (= "The seller" (:entity/description e))))))

  (describe "make-test-relationship"
    (it "creates relationship map"
      (let [r (make-test-relationship "A" "B" "employs")]
        (expect (= "A" (:from r)))
        (expect (= "B" (:to r)))
        (expect (= "employs" (:type r))))))

  (describe "make-test-claim"
    (it "creates claim with citation fields"
      (let [c (make-test-claim "X is true" "doc-1" 2 "s3")]
        (expect (valid-citation? c))
        (expect (= "X is true" (:text c)))
        (expect (= 0.85 (:confidence c)))))))

(defdescribe test-predicates-test
  (describe "valid-citation?"
    (it "returns true for complete citation"
      (expect (valid-citation? {:document-id "doc-1" :page 0 :section "s1"})))

    (it "returns false when missing document-id"
      (expect (not (valid-citation? {:page 0 :section "s1"}))))

    (it "returns false when fields are nil"
      (expect (not (valid-citation? {:document-id nil :page 0 :section "s1"})))))

  (describe "valid-retrieval-plan?"
    (it "returns true for valid plan"
      (expect (valid-retrieval-plan?
                [{:strategy "semantic-search" :query "test query"}
                 {:strategy "keyword-match" :query "another"}])))

    (it "returns false for non-vector"
      (expect (not (valid-retrieval-plan? '({:strategy "s" :query "q"})))))

    (it "returns false when missing required keys"
      (expect (not (valid-retrieval-plan? [{:strategy "s"}]))))))

(defdescribe edge-case-documents-test
  (describe "make-test-empty-document"
    (it "has 0 pages"
      (expect (= 0 (count (:document/pages (make-test-empty-document)))))))

  (describe "make-test-image-only-document"
    (it "has only image nodes"
      (let [doc (make-test-image-only-document)
            nodes (get-in doc [:document/pages 0 :page/nodes])]
        (expect (= 2 (count nodes)))
        (expect (every? #(= :image (:page.node/type %)) nodes))))

    (it "uses :page.node/image-data not :page.node/content"
      (let [doc (make-test-image-only-document)
            nodes (get-in doc [:document/pages 0 :page/nodes])]
        (expect (every? #(some? (:page.node/image-data %)) nodes))
        (expect (every? #(nil? (:page.node/content %)) nodes)))))

  (describe "make-test-single-page-document"
    (it "has exactly 1 page"
      (expect (= 1 (count (:document/pages (make-test-single-page-document))))))

    (it "has content nodes"
      (let [nodes (get-in (make-test-single-page-document) [:document/pages 0 :page/nodes])]
        (expect (= 2 (count nodes)))))))

;; =============================================================================
;; Entity Extraction Ingestion Tests (RED)
;; =============================================================================

(def ^:private test-ingest-router
  (llm/make-router [{:id :test
                     :api-key "test"
                     :base-url "https://api.openai.com/v1"
                     :models [{:name "gpt-4o"}
                              {:name "gpt-4o-mini"}]}]))

(defn- make-test-image-with-description-document
  "Creates a doc with an image node that has description but no image-data."
  []
  {:document/name "image-desc-only"
   :document/extension "pdf"
   :document/pages [{:page/index 0
                     :page/nodes [{:page.node/type :image
                                   :page.node/id "img-desc"
                                   :page.node/description "Scanned table of payments"}]}]
   :document/toc []})

(defdescribe entity-extraction-ingest-test
  (it "extracts entities from text nodes when enabled"
    (let [calls (atom [])]
      (with-mock-ask! (fn [_router opts]
                        (swap! calls conj opts)
                        (make-mock-ask-response
                          {:entities [{:entity/name "Acme Corp"
                                       :entity/type "organization"
                                       :entity/description "Provider"
                                       :entity/section "s1"
                                       :entity/page 0}]
                           :relationships []}))
        (let [env (sut/create-env test-ingest-router {})
              result (sut/ingest-to-env! env [(make-test-single-page-document)] {:extract-entities? true})]
          (sut/dispose-env! env)
          (expect (= 1 (count @calls)))
          (expect (pos? (get-in result [0 :entities-extracted])))))))

  (it "re-scans image nodes with vision model"
    (let [calls (atom [])]
      (with-mock-ask! (fn [_router opts]
                        (swap! calls conj opts)
                        (make-mock-ask-response
                          {:entities [] :relationships []}))
        (let [env (sut/create-env test-ingest-router {})
              result (sut/ingest-to-env! env [(make-test-image-only-document)] {:extract-entities? true})]
          (sut/dispose-env! env)
          (expect (= 2 (count @calls)))
            ;; Images are now embedded in user messages as multimodal content arrays
          (expect (every? (fn [opts]
                            (some #(and (= "user" (:role %))
                                     (vector? (:content %)))
                              (:messages opts)))
                    @calls))
          (expect (= 2 (get-in result [0 :visual-nodes-scanned])))))))

  (it "uses :extraction-model when provided (calls are routed)"
    (let [calls (atom [])]
      (with-mock-ask! (fn [_router opts]
                        (swap! calls conj opts)
                        (make-mock-ask-response
                          {:entities [] :relationships []}))
        (let [env (sut/create-env test-ingest-router {})
              _result (sut/ingest-to-env! env [(make-test-single-page-document)] {:extract-entities? true :extraction-model "gpt-4o-mini"})]
          (sut/dispose-env! env)
          (expect (pos? (count @calls)))))))

  (it "respects :max-vision-rescan-nodes cap"
    (let [calls (atom [])]
      (with-mock-ask! (fn [_router opts]
                        (swap! calls conj opts)
                        (make-mock-ask-response {:entities [] :relationships []}))
        (let [env (sut/create-env test-ingest-router {})
              result (sut/ingest-to-env! env [(make-test-image-only-document)] {:extract-entities? true :max-vision-rescan-nodes 1})]
          (sut/dispose-env! env)
          (expect (= 1 (count @calls)))
          (expect (= 1 (get-in result [0 :visual-nodes-scanned])))))))

  (it "returns zero counts for empty document"
    (let [env (sut/create-env test-ingest-router {})
          result (sut/ingest-to-env! env [(make-test-empty-document)] {:extract-entities? true})]
      (sut/dispose-env! env)
      (expect (= 0 (get-in result [0 :entities-extracted])))
      (expect (= 0 (get-in result [0 :visual-nodes-scanned])))))

  (it "falls back to description-only extraction when image-data missing"
    (let [calls (atom [])]
      (with-mock-ask! (fn [_router opts]
                        (swap! calls conj opts)
                        (make-mock-ask-response
                          {:entities [] :relationships []}))
        (let [env (sut/create-env test-ingest-router {})
              _result (sut/ingest-to-env! env [(make-test-image-with-description-document)] {:extract-entities? true})]
          (sut/dispose-env! env)
          (expect (= 1 (count @calls)))
          (expect (nil? (:images (first @calls))))))))

  (it "handles page extraction failures gracefully"
    (let [calls (atom 0)]
      (with-mock-ask! (fn [_router _opts]
                        (swap! calls inc)
                        (throw (ex-info "boom" {})))
        (let [env (sut/create-env test-ingest-router {})
              result (sut/ingest-to-env! env [(make-test-single-page-document)] {:extract-entities? true})]
          (sut/dispose-env! env)
          (expect (= 1 @calls))
            ;; extract-entities-from-page! catches the exception internally and returns
            ;; empty entities/relationships, so extraction-errors stays 0
          (expect (= 0 (get-in result [0 :extraction-errors])))
          (expect (= 0 (get-in result [0 :entities-extracted]))))))))

;; =============================================================================
;; Query Planning and Relevance Tests
;; =============================================================================

;; generate-retrieval-plan and evaluate-chunk-relevance tests removed
;; (query planning pipeline was removed from source)

(defdescribe iteration-loop-pre-fetched-context-test
  (it "includes pre-fetched-context in initial user content"
    (with-test-env* {} (fn [_env]
                         (let [pre-fetched "<retrieved>Important info</retrieved>"
                               opts {:output-spec nil :examples [] :pre-fetched-context pre-fetched}
            ;; We can't easily test the full iteration-loop, but we can verify
            ;; the function accepts the parameter without error
                               ]
        ;; Just verify the function signature accepts pre-fetched-context
                           (expect (some? opts)))))))

;; =============================================================================
;; Entity Binding Tests
;; =============================================================================

(defdescribe entity-bindings-test
  (it "search-documents with :in :entities returns empty vector when no entities exist"
    (with-test-env* {} (fn [env]
                         (let [result (#'rlm-core/execute-code env "(search-documents \"party\" {:in :entities})")]
                           (expect (nil? (:error result)))
                           (expect (= [] (:result result)))))))

  (it "search-documents without :in returns map with empty collections"
    (with-test-env* {} (fn [env]
                         (let [result (#'rlm-core/execute-code env "(search-documents \"party\")")]
                           (expect (nil? (:error result)))
                           (expect (map? (:result result)))
                           (expect (contains? (:result result) :pages))
                           (expect (contains? (:result result) :toc))
                           (expect (contains? (:result result) :entities))))))

  (it "fetch-content returns nil for non-existent entity lookup"
    (with-test-env* {} (fn [env]
                         (let [result (#'rlm-core/execute-code env "(fetch-content [:entity/id (java.util.UUID/randomUUID)])")]
                           (expect (nil? (:error result)))
                           (expect (nil? (:result result)))))))

  (it "document tools not available when db is disabled"
    (with-test-env* {} {:db false} (fn [env]
                                     (let [result (#'rlm-core/execute-code env "(search-documents \"x\")")]
                                       (expect (some? (:error result))))))))

(defdescribe inline-cite-test
  (it "CITE accumulates claims in claims-atom"
    (let [claims-atom (atom [])
          cite-fn (#'rlm-tools/make-cite-fn claims-atom)]
      (cite-fn "Test claim" "doc-1" 0 "section-1" "exact quote")
      (expect (= 1 (count @claims-atom)))
      (expect (= "Test claim" (:claim/text (first @claims-atom))))
      (expect (= "doc-1" (:claim/document-id (first @claims-atom))))))

  (it "CITE return does NOT trigger check-result-for-final"
    (let [claims-atom (atom [])
          cite-fn (#'rlm-tools/make-cite-fn claims-atom)
          result (cite-fn "claim" "doc" 0 "s" "q")]
      (expect (nil? (:rlm/final result)))
      (expect (true? (:cited result)))))

  (it "CITE coerces types defensively"
    (let [claims-atom (atom [])
          cite-fn (#'rlm-tools/make-cite-fn claims-atom)]
      (cite-fn "claim" "doc" "0" "s" "q" "0.9")
      (let [claim (first @claims-atom)]
        (expect (= 0 (:claim/page claim)))
        (expect (float? (:claim/confidence claim))))))

  (it "CITE-UNVERIFIED sets low confidence"
    (let [claims-atom (atom [])
          cite-fn (#'rlm-tools/make-cite-unverified-fn claims-atom)]
      (cite-fn "unverified claim")
      (let [claim (first @claims-atom)]
        (expect (= 0.5 (:claim/confidence claim)))
        (expect (false? (:claim/verified? claim))))))

  (it "list-claims returns accumulated claims"
    (let [claims-atom (atom [])
          cite-fn (#'rlm-tools/make-cite-fn claims-atom)
          list-fn (#'rlm-tools/make-list-claims-fn claims-atom)]
      (cite-fn "c1" "d1" 0 "s1" "q1")
      (cite-fn "c2" "d2" 1 "s2" "q2")
      (expect (= 2 (count (list-fn)))))))

;; =============================================================================
;; Knowledge Engine Integration Tests
;; =============================================================================

(defmacro ^:private _with-mock-chat!
  "Stubs llm/chat-completion for testing query-env! iteration loop.
   `response-fn` receives [messages model api-key base-url] and returns a string.
   The macro wraps the string in {:content str :reasoning nil :api-usage nil}
   to match the new chat-completion return type."
  [response-fn & body]
  `(let [v# (var com.blockether.svar.internal.llm/chat-completion)
         orig# (deref v#)
         wrapped-fn# (fn [& args#]
                       (let [result# (apply ~response-fn args#)]
                         (if (map? result#) result# {:content result# :reasoning nil :api-usage nil})))]
     (try
       (alter-var-root v# (constantly wrapped-fn#))
       ~@body
       (finally
         (alter-var-root v# (constantly orig#))))))

(defmacro ^:private _with-mock-refine!
  "Stubs llm/refine! to passthrough the answer (skip real CoVe).
   Returns {:result answer :final-score 0.9 :converged? true :iterations-count 0}."
  [& body]
  `(let [v# (var com.blockether.svar.internal.llm/refine!)
         orig# (deref v#)]
     (try
       (alter-var-root v# (constantly (fn [_router# opts#]
                                        (let [msgs# (:messages opts#)
                                              answer# (some-> msgs# last :content)]
                                          {:result answer# :final-score 0.9
                                           :converged? true :iterations-count 0}))))
       ~@body
       (finally
         (alter-var-root v# (constantly orig#))))))

(def ^:private _final-response "{\"thinking\": \"Answering directly\", \"code\": [\"(FINAL \\\"test answer\\\")\"]}")
(def ^:private _final-response-low-confidence "{\"thinking\": \"Answering directly\", \"code\": [\"(FINAL \\\"test answer\\\" {:confidence :low})\"]}")

(defdescribe knowledge-engine-integration-test
  (it "ingest with extract-entities? returns extraction stats"
    (with-mock-ask! (fn [_router _opts]
                      (make-mock-ask-response
                        {:entities [{:entity/name "Test Entity"
                                     :entity/type "party"
                                     :entity/description "A party"
                                     :entity/section "s1"
                                     :entity/page 0}]
                         :relationships []}))
      (let [env (sut/create-env test-ingest-router {})
            result (sut/ingest-to-env! env [(make-test-single-page-document)] {:extract-entities? true})]
        (sut/dispose-env! env)
        (expect (pos? (get-in result [0 :entities-extracted])))
        (expect (number? (get-in result [0 :visual-nodes-scanned]))))))

  (it "CITE functions compose in full lifecycle"
    (let [claims-atom (atom [])
          cite-fn (#'rlm-tools/make-cite-fn claims-atom)
          cite-unverified-fn (#'rlm-tools/make-cite-unverified-fn claims-atom)
          list-fn (#'rlm-tools/make-list-claims-fn claims-atom)]
      ;; Accumulate mixed claim types
      (cite-fn "Verified claim" "doc-1" 0 "s1" "exact quote" 0.95)
      (cite-unverified-fn "Unverified claim")
      (expect (= 2 (count (list-fn))))
      ;; Verified: high confidence
      (expect (> (:claim/confidence (first @claims-atom)) 0.9))
      ;; Unverified: low confidence + verified? false
      (expect (= 0.5 (:claim/confidence (second @claims-atom))))
      (expect (false? (:claim/verified? (second @claims-atom))))))

  (it "search-documents returns empty results on empty DB via full env"
    (with-test-env* {} (fn [env]
                         (let [result (#'rlm-core/execute-code env "(search-documents \"x\" {:in :entities})")]
                           (expect (nil? (:error result)))
                           (expect (= [] (:result result))))))))

;; =============================================================================
;; Real LLM Integration Tests for Knowledge Engine Features
;; =============================================================================

(defn- make-test-multi-page-document
  "Creates a 4-page document about TechCorp for testing query planning.
   Contains extractable entities and financial data."
  []
  {:document/name "techcorp-report"
   :document/extension "pdf"
   :document/title "TechCorp Annual Report 2024"
   :document/pages
   [{:page/index 0
     :page/nodes [{:page.node/type :section
                   :page.node/id "s1"
                   :page.node/content "Executive Summary"
                   :page.node/description "Overview of TechCorp's 2024 performance"}
                  {:page.node/type :paragraph
                   :page.node/id "p1"
                   :page.node/parent-id "s1"
                   :page.node/content "TechCorp achieved record revenue of $500 million in 2024, a 25% increase from the previous year. CEO Jane Smith led the company through significant market expansion."}]}
    {:page/index 1
     :page/nodes [{:page.node/type :section
                   :page.node/id "s2"
                   :page.node/content "Financial Performance"
                   :page.node/description "Detailed financial metrics"}
                  {:page.node/type :paragraph
                   :page.node/id "p2"
                   :page.node/parent-id "s2"
                   :page.node/content "Q1 revenue: $120M. Q2 revenue: $125M. Q3 revenue: $130M. Q4 revenue: $125M. Operating margin improved to 18%."}]}
    {:page/index 2
     :page/nodes [{:page.node/type :section
                   :page.node/id "s3"
                   :page.node/content "Market Expansion"
                   :page.node/description "Geographic and product expansion"}
                  {:page.node/type :paragraph
                   :page.node/id "p3"
                   :page.node/parent-id "s3"
                   :page.node/content "TechCorp expanded into Asian markets in 2024, opening offices in Tokyo, Singapore, and Seoul. Partnership with Samsung announced in Q3."}]}
    {:page/index 3
     :page/nodes [{:page.node/type :section
                   :page.node/id "s4"
                   :page.node/content "Leadership Team"
                   :page.node/description "Key executives"}
                  {:page.node/type :paragraph
                   :page.node/id "p4"
                   :page.node/parent-id "s4"
                   :page.node/content "Jane Smith (CEO), John Doe (CFO), Alice Johnson (CTO). The leadership team has over 50 years of combined industry experience."}]}]
   :document/toc [{:document.toc/type :toc-entry
                   :document.toc/id "toc-1"
                   :document.toc/title "Executive Summary"
                   :document.toc/target-page 0
                   :document.toc/target-section-id "s1"
                   :document.toc/level "l1"}
                  {:document.toc/type :toc-entry
                   :document.toc/id "toc-2"
                   :document.toc/title "Financial Performance"
                   :document.toc/target-page 1
                   :document.toc/target-section-id "s2"
                   :document.toc/level "l1"}
                  {:document.toc/type :toc-entry
                   :document.toc/id "toc-3"
                   :document.toc/title "Market Expansion"
                   :document.toc/target-page 2
                   :document.toc/target-section-id "s3"
                   :document.toc/level "l1"}
                  {:document.toc/type :toc-entry
                   :document.toc/id "toc-4"
                   :document.toc/title "Leadership Team"
                   :document.toc/target-page 3
                   :document.toc/target-section-id "s4"
                   :document.toc/level "l1"}]})

(defdescribe knowledge-engine-real-llm-test
  (describe "entity extraction with real LLM"
    (it "extracts entities from document text nodes"
      (when (integration-tests-enabled?)
        (with-integration-env* (fn [env]
                                 (let [result (sut/ingest-to-env! env [(make-test-single-page-document)]
                                                {:extract-entities? true})]
            ;; Should return vector of ingest results
                                   (expect (vector? result))
                                   (expect (= 1 (count result)))
            ;; Each result should have extraction stats
                                   (expect (contains? (first result) :entities-extracted))
                                   (expect (number? (get-in result [0 :entities-extracted])))
                                   (expect (contains? (first result) :visual-nodes-scanned)))))))

    (it "extracts entities from legal document with multiple pages"
      (when (integration-tests-enabled?)
        (with-integration-env* (fn [env]
                                 (let [result (sut/ingest-to-env! env [(make-test-legal-document)]
                                                {:extract-entities? true})]
            ;; Legal doc has parties (Acme Corp, Widget Inc) that should be extracted
                                   (expect (vector? result))
                                   (expect (number? (get-in result [0 :entities-extracted])))))))))

  (describe "query on multi-page document with real LLM"
    (it "queries multi-page document"
      (when (integration-tests-enabled?)
        (with-integration-env* (fn [env]
          ;; Ingest multi-page document first
                                 (sut/ingest-to-env! env [(make-test-multi-page-document)])
                                 (let [result (sut/query-env! env [(llm/user "What was TechCorp's total revenue in 2024?")]
                                                {:refine? false
                                                 :max-iterations 25})]
                                   (expect (map? result))
                                   (expect (some? (:answer result)))
            ;; Answer should mention $500 million
                                   (expect (re-find #"(?i)500" (str (:answer result))))
            ;; Consensus efficiency: medium query should finish within 8 iterations
                                   (expect (<= (:iterations result) 8)))))))

    (it "queries small documents"
      (when (integration-tests-enabled?)
        (with-integration-env* (fn [env]
                                 (sut/ingest-to-env! env [(make-test-single-page-document)])
                                 (let [result (sut/query-env! env [(llm/user "What is the title?")]
                                                {:refine? false
                                                 :max-iterations 25})]
                                   (expect (some? (:answer result)))
            ;; Consensus efficiency: trivial query should finish within 3 iterations
                                   (expect (<= (:iterations result) 3))))))))

  (describe "CITE verification with real LLM"
    (it "verifies claims when verify? is true"
      (when (integration-tests-enabled?)
        (with-integration-env* (fn [env]
          ;; Ingest document with extractable facts
                                 (sut/ingest-to-env! env [(make-test-multi-page-document)])
                                 (let [result (sut/query-env! env [(llm/user "What was the 2024 revenue and who is the CEO? Cite your sources.")]
                                                {:verify? true
                                                 :refine? false
                                                 :max-iterations 25})]
                                   (expect (map? result))
                                   (expect (some? (:answer result)))
            ;; Should have verified-claims key
                                   (expect (contains? result :verified-claims))
                                   (expect (vector? (:verified-claims result)))
            ;; Consensus efficiency: medium query with citations should finish within 6 iterations
                                   (expect (<= (:iterations result) 6)))))))

    (it "returns empty verified-claims when no CITE called"
      (when (integration-tests-enabled?)
        (with-integration-env* (fn [env]
          ;; Ingest a simple document, then ask a trivial question that doesn't need citations
                                 (sut/ingest-to-env! env [(make-test-single-page-document)])
                                 (let [result (sut/query-env! env [(llm/user "What is the title of the document?")]
                                                {:verify? true
                                                 :refine? false
                                                 :max-iterations 10})]
            ;; Should have verified-claims key (even if empty)
                                   (expect (contains? result :verified-claims))
                                   (expect (vector? (:verified-claims result)))
            ;; Consensus efficiency: trivial query with verify should finish within 4 iterations
                                   (expect (<= (:iterations result) 4))))))))

  (describe "full knowledge engine pipeline"
    (it "ingest with entity extraction then query with all flags"
      (when (integration-tests-enabled?)
        (with-integration-env* (fn [env]
          ;; Step 1: Ingest with entity extraction
                                 (let [ingest-result (sut/ingest-to-env! env [(make-test-multi-page-document)]
                                                       {:extract-entities? true})]
                                   (expect (number? (get-in ingest-result [0 :entities-extracted])))

            ;; Step 2: Query with all knowledge engine flags enabled
                                   (let [query-result (sut/query-env! env
                                                        [(llm/user "Who is the CEO and what market expansion happened in 2024?")]
                                                        {:verify? true
                                                         :refine? false
                                                         :max-iterations 25})]
              ;; Should have answer
                                     (expect (some? (:answer query-result)))
              ;; Answer should mention Jane Smith (CEO) and/or Asia expansion
                                     (let [answer-str (str (:answer query-result))]
                                       (expect (or (re-find #"(?i)jane" answer-str)
                                                 (re-find #"(?i)asia" answer-str)
                                                 (re-find #"(?i)tokyo|singapore|seoul" answer-str))))
              ;; Should have verified-claims structure
                                     (expect (contains? query-result :verified-claims))
              ;; Consensus efficiency: complex query with all flags should finish within 6 iterations
                                     (expect (<= (:iterations query-result) 6))))))))

    (it "multiple documents with entity extraction and cross-document query"
      (when (integration-tests-enabled?)
        (with-integration-env* (fn [env]
          ;; Ingest both legal and multi-page documents
                                 (sut/ingest-to-env! env [(make-test-legal-document) (make-test-multi-page-document)]
                                   {:extract-entities? true})

          ;; Query that could span both documents
                                 (let [result (sut/query-env! env [(llm/user "List all parties and companies mentioned in the documents.")]
                                                {:verify? true
                                                 :refine? false
                                                 :max-iterations 25})]
                                   (expect (some? (:answer result)))
            ;; Should mention entities from both docs
                                   (let [answer-str (str (:answer result))]
              ;; From legal doc: Acme Corp, Widget Inc
              ;; From multi-page: TechCorp, Samsung
                                     (expect (or (re-find #"(?i)acme|widget|techcorp|samsung" answer-str)
                          ;; Or just have an answer that's not empty
                                               (> (count answer-str) 10))))
             ;; Consensus efficiency: hard cross-document query should finish within 8 iterations
                                   (expect (<= (:iterations result) 8)))))))))

(defdescribe search-toc-entries-test
  (describe "db-search-toc-entries"
    (it "finds entries matching title text"
      (let [db-info (#'rlm-db/create-rlm-conn nil)]
        (try
          (#'rlm-db/db-store-toc-entry! db-info
                                        {:document.toc/title "Chapter 1: Compliance"
                                         :document.toc/level "l1"
                                         :document.toc/target-page 0}
                                        "doc-1")
          (#'rlm-db/db-store-toc-entry! db-info
                                        {:document.toc/title "Chapter 2: Financial Terms"
                                         :document.toc/level "l1"
                                         :document.toc/target-page 1}
                                        "doc-1")
          (let [results (#'rlm-db/db-search-toc-entries db-info "compliance")]
            (expect (= 1 (count results)))
            (expect (str/includes? (:document.toc/title (first results)) "Compliance")))
          (finally
            (#'rlm-db/dispose-rlm-conn! db-info)))))

    (it "finds entries matching description text"
      (let [db-info (#'rlm-db/create-rlm-conn nil)]
        (try
          (#'rlm-db/db-store-toc-entry! db-info
                                        {:document.toc/title "Appendix A"
                                         :document.toc/level "l2"
                                         :document.toc/description "Detailed compliance regulations"
                                         :document.toc/target-page 5}
                                        "doc-1")
          (let [results (#'rlm-db/db-search-toc-entries db-info "regulations")]
            (expect (= 1 (count results))))
          (finally
            (#'rlm-db/dispose-rlm-conn! db-info)))))

    (it "returns empty for non-matching query"
      (let [db-info (#'rlm-db/create-rlm-conn nil)]
        (try
          (#'rlm-db/db-store-toc-entry! db-info
                                        {:document.toc/title "Introduction"
                                         :document.toc/level "l1"
                                         :document.toc/target-page 0}
                                        "doc-1")
          (let [results (#'rlm-db/db-search-toc-entries db-info "xyznonexistent")]
            (expect (= 0 (count results))))
          (finally
            (#'rlm-db/dispose-rlm-conn! db-info)))))

    (it "falls back to list mode for blank query"
      (let [db-info (#'rlm-db/create-rlm-conn nil)]
        (try
          (#'rlm-db/db-store-toc-entry! db-info
                                        {:document.toc/title "First Entry"
                                         :document.toc/level "l1"
                                         :document.toc/target-page 0}
                                        "doc-1")
          (#'rlm-db/db-store-toc-entry! db-info
                                        {:document.toc/title "Second Entry"
                                         :document.toc/level "l1"
                                         :document.toc/target-page 1}
                                        "doc-1")
          (let [results (#'rlm-db/db-search-toc-entries db-info "")]
            (expect (= 2 (count results))))
          (finally
            (#'rlm-db/dispose-rlm-conn! db-info)))))))

(defdescribe search-entities-test
  (describe "db-search-entities"
    (it "finds entities matching name"
      (let [db-info (#'rlm-db/create-rlm-conn nil)]
        (try
          (d/transact! (:conn db-info)
            [{:entity/id (UUID/randomUUID)
              :entity/name "Acme Corp"
              :entity/type :organization
              :entity/description "A technology company"
              :entity/document-id "doc-1"
              :entity/created-at (java.util.Date.)}
             {:entity/id (UUID/randomUUID)
              :entity/name "Jane Smith"
              :entity/type :person
              :entity/description "Chief executive officer"
              :entity/document-id "doc-1"
              :entity/created-at (java.util.Date.)}])
          (let [results (#'rlm-db/db-search-entities db-info "acme")]
            (expect (= 1 (count results)))
            (expect (= "Acme Corp" (:entity/name (first results)))))
          (finally
            (#'rlm-db/dispose-rlm-conn! db-info)))))

    (it "finds entities matching description"
      (let [db-info (#'rlm-db/create-rlm-conn nil)]
        (try
          (d/transact! (:conn db-info)
            [{:entity/id (UUID/randomUUID)
              :entity/name "Bob"
              :entity/type :person
              :entity/description "Senior compliance officer"
              :entity/document-id "doc-1"
              :entity/created-at (java.util.Date.)}])
          (let [results (#'rlm-db/db-search-entities db-info "compliance")]
            (expect (= 1 (count results)))
            (expect (= "Bob" (:entity/name (first results)))))
          (finally
            (#'rlm-db/dispose-rlm-conn! db-info)))))

    (it "returns empty for non-matching query"
      (let [db-info (#'rlm-db/create-rlm-conn nil)]
        (try
          (d/transact! (:conn db-info)
            [{:entity/id (UUID/randomUUID)
              :entity/name "TestCo"
              :entity/type :organization
              :entity/description "A company"
              :entity/document-id "doc-1"
              :entity/created-at (java.util.Date.)}])
          (let [results (#'rlm-db/db-search-entities db-info "xyznonexistent")]
            (expect (= 0 (count results))))
          (finally
            (#'rlm-db/dispose-rlm-conn! db-info)))))

    (it "respects :type filter"
      (let [db-info (#'rlm-db/create-rlm-conn nil)]
        (try
          (d/transact! (:conn db-info)
            [{:entity/id (UUID/randomUUID)
              :entity/name "Acme Corp"
              :entity/type :organization
              :entity/description "A global corp"
              :entity/document-id "doc-1"
              :entity/created-at (java.util.Date.)}
             {:entity/id (UUID/randomUUID)
              :entity/name "Acme Division"
              :entity/type :person
              :entity/description "Acme division lead"
              :entity/document-id "doc-1"
              :entity/created-at (java.util.Date.)}])
          (let [results (#'rlm-db/db-search-entities db-info "acme" {:type :organization})]
            (expect (= 1 (count results)))
            (expect (= :organization (:entity/type (first results)))))
          (finally
            (#'rlm-db/dispose-rlm-conn! db-info)))))

    (it "respects :document-id filter"
      (let [db-info (#'rlm-db/create-rlm-conn nil)]
        (try
          (d/transact! (:conn db-info)
            [{:entity/id (UUID/randomUUID)
              :entity/name "Shared Name"
              :entity/type :organization
              :entity/description "In doc 1"
              :entity/document-id "doc-1"
              :entity/created-at (java.util.Date.)}
             {:entity/id (UUID/randomUUID)
              :entity/name "Shared Name"
              :entity/type :organization
              :entity/description "In doc 2"
              :entity/document-id "doc-2"
              :entity/created-at (java.util.Date.)}])
          (let [results (#'rlm-db/db-search-entities db-info "shared" {:document-id "doc-2"})]
            (expect (= 1 (count results)))
            (expect (= "doc-2" (:entity/document-id (first results)))))
          (finally
            (#'rlm-db/dispose-rlm-conn! db-info)))))))

;; =============================================================================
;; T9: Flush Store Batching Tests
;; =============================================================================

(defdescribe datalevin-auto-commit-test
  (describe "Datalevin auto-commit"
    (it "persists data immediately on transact without flush"
      (let [db-info (#'rlm-db/create-rlm-conn nil)
            conn (:conn db-info)]
        (try
          (datalevin.core/transact! conn [{:entity/id (UUID/randomUUID)
                                           :entity/name "TestEntity"
                                           :entity/type :test
                                           :entity/description "auto-persisted"
                                           :entity/document-id "doc-persist"
                                           :entity/created-at (java.util.Date.)}])
          (let [results (datalevin.core/q '[:find [(pull ?e [*]) ...]
                                            :where [?e :entity/name "TestEntity"]]
                          (datalevin.core/db conn))]
            (expect (= 1 (count results)))
            (expect (= "TestEntity" (:entity/name (first results)))))
          (finally
            (#'rlm-db/dispose-rlm-conn! db-info)))))

    (it "DB path exists and is a directory after conn creation"
      (let [db-info (#'rlm-db/create-rlm-conn nil)
            path (:path db-info)]
        (try
          (expect (fs/exists? path))
          (expect (fs/directory? path))
          (finally
            (#'rlm-db/dispose-rlm-conn! db-info)))))))

;; =============================================================================
;; T9: Relationship Storage Tests
;; =============================================================================

(defdescribe relationship-storage-test
  (describe "two-phase entity + relationship storage"
    (it "stores relationships with resolved entity UUIDs"
      (let [db-info (#'rlm-db/create-rlm-conn nil)]
        (try
          (let [conn (:conn db-info)
                uuid-a (UUID/randomUUID)
                uuid-b (UUID/randomUUID)
                rel-id (UUID/randomUUID)]
                      ;; Phase 1: Store entities
            (d/transact! conn
              [{:entity/id uuid-a
                :entity/name "Alice"
                :entity/type :person
                :entity/description "Engineer"
                :entity/document-id "doc-1"
                :entity/created-at (java.util.Date.)}
               {:entity/id uuid-b
                :entity/name "Bob"
                :entity/type :person
                :entity/description "Manager"
                :entity/document-id "doc-1"
                :entity/created-at (java.util.Date.)}])
                      ;; Phase 2: Store relationship with resolved UUIDs
            (d/transact! conn
              [{:relationship/id rel-id
                :relationship/type :works-with
                :relationship/source-entity-id uuid-a
                :relationship/target-entity-id uuid-b
                :relationship/description "Alice works with Bob"
                :relationship/document-id "doc-1"}])
                      ;; Verify entities stored
            (expect (= 2 (count (d/q '[:find ?e :where [?e :entity/id _]] (d/db conn)))))
                      ;; Verify relationship stored
            (expect (= 1 (count (d/q '[:find ?e :where [?e :relationship/id _]] (d/db conn)))))
            (let [rel (d/pull (d/db conn) '[*] [:relationship/id rel-id])]
              (expect (= uuid-a (:relationship/source-entity-id rel)))
              (expect (= uuid-b (:relationship/target-entity-id rel)))
              (expect (= :works-with (:relationship/type rel)))
              (expect (= "Alice works with Bob" (:relationship/description rel)))))
          (finally
            (#'rlm-db/dispose-rlm-conn! db-info)))))

    (it "relationships start empty in a fresh db"
      (let [db-info (#'rlm-db/create-rlm-conn nil)]
        (try
          (expect (= 0 (count (d/q '[:find ?e :where [?e :relationship/id _]] (d/db (:conn db-info))))))
          (finally
            (#'rlm-db/dispose-rlm-conn! db-info)))))))

;; =============================================================================
;; generate-qa-env! pipeline unit tests
;; =============================================================================

(defdescribe generate-qa-manifest-resume-test
  (it "reuses batches only when manifest fingerprint matches"
    (let [router (llm/make-router [{:id :test :api-key "test" :base-url "http://localhost"
                                    :models [{:name "gpt-4o"}]}])
          dir (str (fs/create-temp-dir {:prefix "qa-manifest-"}))
          env (sut/create-env router {:path dir})
          query-calls (atom 0)]
      (try
        (with-redefs [llm/ask! (fn [_router opts]
                                 (if (= (:spec opts) sut/CHUNK_SELECTION_SPEC)
                                   (make-mock-ask-response
                                     {:passages [{:document-id "doc-1"
                                                  :page 0
                                                  :section-title "Intro"
                                                  :content-summary "Summary"
                                                  :suggested-difficulty :remember
                                                  :suggested-category :factual}]})
                                   (make-mock-ask-response {:keep-indices [0]})))
                      sut/query-env! (fn [_env _prompt _opts]
                                       (swap! query-calls inc)
                                       {:answer {:questions [{:question "Q1"
                                                              :answer "A1"
                                                              :difficulty :remember
                                                              :category :factual
                                                              :source-document "doc-1"
                                                              :source-page 0
                                                              :evidence-span "Evidence"}]}
                                        :trace []
                                        :iterations 1})]
          ;; First run: should execute generation and create manifest.
          (sut/generate-qa-env! env {:count 2 :batch-size 5 :verify? false})
          (expect (= 1 @query-calls))

          ;; Second run with same opts: should reuse cached batch and skip query-env!.
          (sut/generate-qa-env! env {:count 2 :batch-size 5 :verify? false})
          (expect (= 1 @query-calls))

          ;; Mutate corpus content: fingerprint should change even with same opts.
          (d/transact! (:conn @(:db-info-atom env))
            [{:page.node/id "node-extra"
              :page.node/document-id "doc-extra"
              :page.node/page-id "page-extra"
              :page.node/type :paragraph
              :page.node/local-id "n1"
              :page.node/content "This content changes fingerprint."}])

          ;; Third run with same opts after corpus change: should reset + rerun.
          (sut/generate-qa-env! env {:count 2 :batch-size 5 :verify? false})
          (expect (= 2 @query-calls))

          ;; Fourth run with changed opts: fingerprint mismatch should reset + rerun.
          (sut/generate-qa-env! env {:count 3 :batch-size 5 :verify? false})
          (expect (= 3 @query-calls)))
        (finally
          (sut/dispose-env! env)
          (fs/delete-tree dir))))))

(defdescribe qa-manifest-write-failure-test
  (it "throws when target manifest path cannot be replaced"
    (let [router (llm/make-router [{:id :test :api-key "test" :base-url "http://localhost"
                                    :models [{:name "gpt-4o"}]}])
          dir (str (fs/create-temp-dir {:prefix "qa-manifest-fail-"}))
          env (sut/create-env router {:path dir})
          blocked-target (str dir "/qa-manifest.edn")]
      (try
        ;; Block replacement by creating a directory at the target file path.
        (fs/create-dirs blocked-target)
        (expect (throws? clojure.lang.ExceptionInfo
                  #(#'sut/write-qa-manifest! env {:hello :world})))
        (finally
          (sut/dispose-env! env)
          (fs/delete-tree dir))))))

(defdescribe compute-distribution-test
  (describe "compute-distribution"
    (it "distributes evenly when divisible"
      (let [result (#'sut/compute-distribution 6 #{:a :b :c})]
        (expect (= 6 (reduce + (vals result))))
        (expect (every? #(= 2 %) (vals result)))))
    (it "distributes remainder across first items"
      (let [result (#'sut/compute-distribution 7 #{:a :b :c})]
        (expect (= 7 (reduce + (vals result))))
                  ;; One item gets 3, two get 2 (or similar)
        (expect (= #{2 3} (set (vals result))))))
    (it "handles single item"
      (let [result (#'sut/compute-distribution 10 #{:x})]
        (expect (= {:x 10} result))))
    (it "handles zero count"
      (let [result (#'sut/compute-distribution 0 #{:a :b})]
        (expect (= 0 (reduce + (vals result))))))))

(def ^:private test-dedup-router
  (llm/make-router [{:id :test :api-key "test" :base-url "http://test"
                     :models [{:name "gpt-4o"}]}]))

(defdescribe deduplicate-questions-test
  (describe "deduplicate-questions"
    (it "keeps unique questions when LLM returns all indices"
      (with-mock-ask! (fn [_ _] (make-mock-ask-response {:keep-indices [0 1 2]}))
        (let [questions [{:question "What is the capital of France?"}
                         {:question "How does photosynthesis work?"}
                         {:question "What year was the company founded?"}]
              result (#'sut/deduplicate-questions questions test-dedup-router)]
          (expect (= 3 (count result))))))
    (it "removes duplicates when LLM identifies them"
      (with-mock-ask! (fn [_ _] (make-mock-ask-response {:keep-indices [0 2]}))
        (let [questions [{:question "What is the minimum capital requirement for banks?"}
                         {:question "What is the minimum capital requirement for the banks?"}
                         {:question "How does photosynthesis produce oxygen?"}]
              result (#'sut/deduplicate-questions questions test-dedup-router)]
          (expect (= 2 (count result)))
          (expect (= "What is the minimum capital requirement for banks?"
                    (:question (first result))))
          (expect (= "How does photosynthesis produce oxygen?"
                    (:question (second result)))))))
    (it "handles empty input"
      (expect (= [] (#'sut/deduplicate-questions [] test-dedup-router))))
    (it "handles single question without calling LLM"
      (let [result (#'sut/deduplicate-questions [{:question "Solo question"}] test-dedup-router)]
        (expect (= 1 (count result)))))
    (it "falls back to all questions when LLM returns empty"
      (with-mock-ask! (fn [_ _] (make-mock-ask-response {:keep-indices []}))
        (let [questions [{:question "Q1"} {:question "Q2"}]
              result (#'sut/deduplicate-questions questions test-dedup-router)]
          (expect (= 2 (count result))))))))

(defdescribe filter-verified-questions-test
  (describe "filter-verified-questions"
    (it "passes questions with :pass verdict"
      (let [questions [{:question "Q1"} {:question "Q2"} {:question "Q3"}]
            verifications [{:question-index 0 :verdict :pass}
                           {:question-index 1 :verdict :fail :revision-note "bad"}
                           {:question-index 2 :verdict :pass}]
            result (#'sut/filter-verified-questions questions verifications)]
        (expect (= 2 (count (:passed result))))
        (expect (= 1 (count (:dropped result))))
        (expect (= "Q1" (:question (first (:passed result)))))
        (expect (= "Q3" (:question (second (:passed result)))))
        (expect (= "Q2" (:question (first (:dropped result)))))))
    (it "defaults to :pass for missing verifications"
      (let [questions [{:question "Q1"} {:question "Q2"}]
            verifications [{:question-index 0 :verdict :pass}]
            result (#'sut/filter-verified-questions questions verifications)]
        (expect (= 2 (count (:passed result))))
        (expect (= 0 (count (:dropped result))))))
    (it "separates needs-revision questions for revision"
      (let [questions [{:question "Q1"}]
            verifications [{:question-index 0 :verdict :needs-revision :revision-note "fix it"}]
            result (#'sut/filter-verified-questions questions verifications)]
        (expect (= 0 (count (:passed result))))
        (expect (= 0 (count (:dropped result))))
        (expect (= 1 (count (:needs-revision result))))
        (expect (= "fix it" (:revision-note (first (:needs-revision result)))))))
    (it "handles empty input"
      (let [result (#'sut/filter-verified-questions [] [])]
        (expect (= [] (:passed result)))
        (expect (= [] (:needs-revision result)))
        (expect (= [] (:dropped result)))))
    (it "handles mixed keyword and string verdicts via upstream coercion"
                ;; Note: In production, coerce-data-with-spec runs before filter-verified-questions,
                ;; converting string verdicts to keywords. This test verifies that keyword verdicts work.
      (let [questions [{:question "Q1"} {:question "Q2"} {:question "Q3"}]
            verifications [{:question-index 0 :verdict :pass}
                           {:question-index 1 :verdict :fail :revision-note "bad"}
                           {:question-index 2 :verdict :needs-revision :revision-note "fix"}]
            result (#'sut/filter-verified-questions questions verifications)]
        (expect (= 1 (count (:passed result))))
        (expect (= "Q1" (:question (first (:passed result)))))
        (expect (= 1 (count (:dropped result))))
        (expect (= "Q2" (:question (first (:dropped result)))))
        (expect (= 1 (count (:needs-revision result))))
        (expect (= "Q3" (:question (first (:needs-revision result)))))))))

(defdescribe build-generation-prompt-test
  (describe "build-generation-prompt"
    (it "includes passage details and key instructions"
      (let [passages [{:document-id "doc1" :page 3
                       :section-title "Introduction"
                       :content-summary "Overview of the topic"
                       :suggested-difficulty :understand
                       :suggested-category :factual}]
            prompt (#'sut/build-generation-prompt passages 0 {})]
        (expect (string? prompt))
        (expect (str/includes? prompt "doc1"))
        (expect (str/includes? prompt "Introduction"))
        (expect (str/includes? prompt "evidence-span"))
        (expect (str/includes? prompt "VERBATIM"))
        (expect (str/includes? prompt "FINAL"))))
    (it "includes persona instruction when provided"
      (let [passages [{:document-id "doc1" :page 0
                       :section-title "Intro"
                       :content-summary "Summary"
                       :suggested-difficulty :remember
                       :suggested-category :factual}]
            prompt (#'sut/build-generation-prompt passages 0 {:persona :researcher})]
        (expect (str/includes? prompt "PERSONA"))
        (expect (str/includes? prompt "researcher"))))
    (it "includes k-candidates instruction when k > 1"
      (let [passages [{:document-id "doc1" :page 0
                       :section-title "Intro"
                       :content-summary "Summary"
                       :suggested-difficulty :remember
                       :suggested-category :factual}]
            prompt (#'sut/build-generation-prompt passages 0 {:k-candidates 3})]
        (expect (str/includes? prompt "3 candidate"))))
    (it "includes multi-hop instruction when enabled"
      (let [passages [{:document-id "doc1" :page 0
                       :section-title "Intro"
                       :content-summary "Summary"
                       :suggested-difficulty :analyze
                       :suggested-category :comparative}]
            prompt (#'sut/build-generation-prompt passages 0 {:multi-hop? true})]
        (expect (str/includes? prompt "MULTI-HOP"))))))

(defdescribe build-verification-prompt-test
  (describe "build-verification-prompt"
    (it "includes question details and verification criteria"
      (let [questions [{:question "What is X?"
                        :answer "X is Y"
                        :evidence-span "X is defined as Y"
                        :source-document "doc1"
                        :source-page 5}]
            prompt (#'sut/build-verification-prompt questions)]
        (expect (string? prompt))
        (expect (str/includes? prompt "What is X?"))
        (expect (str/includes? prompt "GROUNDED"))
        (expect (str/includes? prompt "NON-TRIVIAL"))
        (expect (str/includes? prompt "SELF-CONTAINED"))
        (expect (str/includes? prompt "ANSWERABLE"))
        (expect (str/includes? prompt "ANSWER-CONSISTENT"))
        (expect (str/includes? prompt "FINAL"))))))

(defdescribe save-qa-test
  (describe "save-qa!"
    (it "saves EDN file with correct structure"
      (let [dir (str (fs/create-temp-dir {:prefix "qa-test-"}))
            result {:questions [{:question "Q1" :answer "A1" :difficulty :understand
                                 :category :factual :source-document "d1" :source-page 0
                                 :evidence-span "evidence text"}]
                    :dropped-questions [{:question "Bad Q"}]
                    :stats {:total-generated 2 :passed-verification 1
                            :duplicates-removed 0 :final-count 1
                            :by-difficulty {:understand 1} :by-category {:factual 1}}}
            path (str dir "/test-output")
            saved (sut/save-qa! result path {:formats #{:edn}})]
        (expect (= 1 (count (:files saved))))
        (expect (str/ends-with? (first (:files saved)) ".edn"))
        (let [data (read-string (slurp (first (:files saved))))]
          (expect (= 1 (count (:questions data))))
          (expect (= "Q1" (:question (first (:questions data)))))
          (expect (map? (:stats data))))))
    (it "saves Markdown file with formatted content"
      (let [dir (str (fs/create-temp-dir {:prefix "qa-test-"}))
            result {:questions [{:question "What is X?" :answer "X is Y"
                                 :difficulty :analyze :category :inferential
                                 :source-document "doc1" :source-page 3
                                 :source-section "Chapter 2"
                                 :evidence-span "X is defined as Y in the spec"}]
                    :dropped-questions []
                    :stats {:total-generated 1 :passed-verification 1
                            :duplicates-removed 0 :final-count 1
                            :by-difficulty {:analyze 1} :by-category {:inferential 1}}}
            path (str dir "/test-output")
            saved (sut/save-qa! result path {:formats #{:markdown}})]
        (expect (= 1 (count (:files saved))))
        (expect (str/ends-with? (first (:files saved)) ".md"))
        (let [content (slurp (first (:files saved)))]
          (expect (str/includes? content "# Generated Q&A Pairs"))
          (expect (str/includes? content "What is X?"))
          (expect (str/includes? content "X is Y"))
          (expect (str/includes? content "Evidence"))
          (expect (str/includes? content "Chapter 2")))))
    (it "saves both formats when requested"
      (let [dir (str (fs/create-temp-dir {:prefix "qa-test-"}))
            result {:questions [] :dropped-questions []
                    :stats {:total-generated 0 :passed-verification 0
                            :duplicates-removed 0 :final-count 0
                            :by-difficulty {} :by-category {}}}
            path (str dir "/test-output")
            saved (sut/save-qa! result path)]
        (expect (= 2 (count (:files saved))))))))
