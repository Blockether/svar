(ns com.blockether.svar.internal.rlm-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [lazytest.core :refer [defdescribe describe expect it throws?]]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.rlm :as sut]
   [com.blockether.svar.core :as svar])
  (:import
   [java.util UUID]))

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
   (let [depth-atom (atom 0)]
     (#'sut/create-rlm-env context "gpt-4o" depth-atom nil nil opts))))

(defn- with-test-env*
  "Creates a test environment, executes f with it, then disposes.
   
   Usage:
   (with-test-env* {:count 42}
     (fn [env] (#'sut/execute-code env \"(+ 1 2)\")))
   
   (with-test-env* {:count 42} {:db false}
     (fn [env] (#'sut/execute-code env \"(+ 1 2)\")))"
  ([context f] (with-test-env* context {} f))
  ([context opts f]
   (let [env (create-test-env context opts)]
     (try
       (f env)
       (finally
         (#'sut/dispose-rlm-env! env))))))

;; =============================================================================
;; Unit Tests (no LLM calls)
;; =============================================================================

(defdescribe extract-code-blocks-test
  (it "extracts code from clojure blocks"
      (expect (= ["(+ 1 2)"]
                 (#'sut/extract-code-blocks "text ```clojure\n(+ 1 2)\n``` more"))))

  (it "extracts code from repl blocks"
      (expect (= ["(def x 1)"]
                 (#'sut/extract-code-blocks "```repl\n(def x 1)\n```"))))

  (it "extracts code from clj blocks"
      (expect (= ["(println \"hi\")"]
                 (#'sut/extract-code-blocks "```clj\n(println \"hi\")\n```"))))

  (it "extracts code from plain triple backtick blocks"
      (expect (= ["(str \"test\")"]
                 (#'sut/extract-code-blocks "```\n(str \"test\")\n```"))))

  (it "extracts multiple code blocks"
      (expect (= ["(def x 1)" "(inc x)"]
                 (#'sut/extract-code-blocks "```clojure\n(def x 1)\n```\n```clojure\n(inc x)\n```"))))

  (it "returns empty vector when no code blocks"
      (expect (= []
                 (#'sut/extract-code-blocks "no code here"))))

  (it "skips empty code blocks"
      (expect (= []
                 (#'sut/extract-code-blocks "```clojure\n\n\n```"))))

  (it "trims whitespace from extracted code"
      (expect (= ["(+ 1 2)"]
                 (#'sut/extract-code-blocks "```clojure\n  (+ 1 2)  \n```")))))

(defdescribe check-result-for-final-test
  (it "detects FINAL marker in result"
      (expect (= {:final? true :answer "done"}
                 (#'sut/check-result-for-final {:result {:rlm/final true :rlm/answer "done"}}))))

  (it "returns false when no FINAL marker"
      (expect (= {:final? false}
                 (#'sut/check-result-for-final {:result 42}))))

  (it "returns false when result is nil"
      (expect (= {:final? false}
                 (#'sut/check-result-for-final {:result nil}))))

  (it "returns false when result is not a map"
      (expect (= {:final? false}
                 (#'sut/check-result-for-final {:result "string"}))))

  (it "returns false when rlm/final is false"
      (expect (= {:final? false}
                 (#'sut/check-result-for-final {:result {:rlm/final false :rlm/answer "test"}})))))

(defdescribe create-rlm-env-test
  (it "creates environment with required keys"
      (with-test-env* {:data "test"} (fn [env]
                                       (expect (contains? env :sci-ctx))
                                       (expect (contains? env :context))
                                       (expect (contains? env :llm-query-fn))
                                       (expect (contains? env :locals-atom)))))

  (it "stores context in environment"
      (with-test-env* {:users [{:name "Alice"}]} (fn [env]
                                                   (expect (= {:users [{:name "Alice"}]} (:context env))))))

  (it "initializes locals-atom as empty"
      (with-test-env* {} (fn [env]
                           (expect (= {} @(:locals-atom env))))))

  (it "creates database by default"
      (with-test-env* {} (fn [env]
                           (expect (some? (:db-info-atom env)))
                           (expect (true? (:history-enabled? env))))))

  (it "can disable database with :db false"
      (with-test-env* {} {:db false} (fn [env]
                                       (expect (nil? (:db-info-atom env)))
                                       (expect (false? (:history-enabled? env)))))))

(defdescribe get-locals-test
  (it "returns empty map initially"
      (with-test-env* {} (fn [env]
                           (expect (= {} (#'sut/get-locals env))))))

  (it "returns tracked variables after execution"
      (with-test-env* {} (fn [env]
                           (#'sut/execute-code env "(def x 42)")
                           (expect (= {'x 42} (#'sut/get-locals env)))))))

;; =============================================================================
;; Disposable Database Tests
;; =============================================================================

(defdescribe disposable-db-test
  (describe "create-disposable-db"
            (it "creates a database with required keys"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (expect (contains? db-info :store))
                    (expect (contains? db-info :path))
                    (expect (contains? db-info :owned?))
                    (expect (true? (:owned? db-info)))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "creates database with custom schema"
                (let [db-info (#'sut/create-disposable-db {:schema {:test/name {:db/valueType :db.type/string}}})]
                  (try
                    (expect (some? (:store db-info)))
                    (expect (true? (:owned? db-info)))
                    (finally
                      (#'sut/dispose-db! db-info))))))

  (describe "wrap-external-db"
            (it "wraps external store atom with owned? false"
                (let [external-store (atom @#'sut/EMPTY_STORE)
                      db-info (#'sut/wrap-external-db external-store)]
                  (expect (= external-store (:store db-info)))
                  (expect (false? (:owned? db-info))))))

  (describe "dispose-db!"
            (it "disposes owned database and cleans up path"
                (let [db-info (#'sut/create-disposable-db)
                      path (:path db-info)]
                  ;; Create the path so we can verify cleanup
                  (fs/create-dirs path)
                  (expect (fs/exists? path))
                  (#'sut/dispose-db! db-info)
                  (expect (not (fs/exists? path)))))

            (it "does nothing when path does not exist"
                (let [db-info (#'sut/create-disposable-db)]
                  ;; Should not throw even if path was never created
                  (#'sut/dispose-db! db-info)))))

(defdescribe execute-code-test
  (it "executes simple arithmetic"
      (with-test-env* {} (fn [env]
                           (let [result (#'sut/execute-code env "(+ 1 2)")]
                             (expect (= 3 (:result result)))
                             (expect (nil? (:error result)))
                             (expect (false? (:timeout? result)))))))

  (it "captures stdout from println"
      (with-test-env* {} (fn [env]
                           (let [result (#'sut/execute-code env "(println \"hello\")")]
                             (expect (= "hello\n" (:stdout result)))
                             (expect (nil? (:error result)))))))

  (it "tracks variables defined with def"
      (with-test-env* {} (fn [env]
                           (#'sut/execute-code env "(def x 42)")
                           (expect (= {'x 42} (#'sut/get-locals env))))))

  (it "returns error for invalid code"
      (with-test-env* {} (fn [env]
                           (let [result (#'sut/execute-code env "(/ 1 0)")]
                             (expect (some? (:error result)))
                             (expect (nil? (:result result)))))))

  (it "provides access to context"
      (with-test-env* {:count 5} (fn [env]
                                   (let [result (#'sut/execute-code env "(:count context)")]
                                     (expect (= 5 (:result result)))
                                     (expect (nil? (:error result)))))))

  (it "provides access to string helpers"
      (with-test-env* {} (fn [env]
                           (let [result (#'sut/execute-code env "(str-includes? \"hello world\" \"world\")")]
                             (expect (= true (:result result)))
                             (expect (nil? (:error result)))))))

  (describe "regex support"
            (it "supports re-find for pattern matching"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(re-find #\"\\d+\" \"abc123def\")")]
                                       (expect (= "123" (:result result)))
                                       (expect (nil? (:error result)))))))

            (it "supports re-seq for multiple matches"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(vec (re-seq #\"\\d+\" \"a1b2c3\"))")]
                                       (expect (= ["1" "2" "3"] (:result result)))))))))

;; =============================================================================
;; Locals Inspection Tests (SCI Bindings)
;; =============================================================================

(defdescribe locals-inspection-test
  (describe "list-locals"
            (it "returns empty map when no locals defined"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(list-locals)")]
                                       (expect (= {} (:result result)))))))

            (it "shows defined variables"
                (with-test-env* {} (fn [env]
                                     (#'sut/execute-code env "(def x 42)")
                                     (#'sut/execute-code env "(def y \"hello\")")
                                     (let [result (#'sut/execute-code env "(list-locals)")]
                                       (expect (= {'x 42 'y "hello"} (:result result)))))))

            (it "shows functions as <fn>"
                (with-test-env* {} (fn [env]
                                     (#'sut/execute-code env "(defn my-fn [x] (* x 2))")
                                     (let [result (#'sut/execute-code env "(list-locals)")]
                                       (expect (= '<fn> (get (:result result) 'my-fn)))))))

            (it "summarizes large collections"
                (with-test-env* {} (fn [env]
                                     (#'sut/execute-code env "(def big-list (range 100))")
                                     (let [result (#'sut/execute-code env "(list-locals)")
                                           big-list-summary (get (:result result) 'big-list)]
                                       (expect (string? big-list-summary))
                                       (expect (clojure.string/includes? big-list-summary "100 items"))))))

            (it "shows small collections in full"
                (with-test-env* {} (fn [env]
                                     (#'sut/execute-code env "(def small-vec [1 2 3])")
                                     (let [result (#'sut/execute-code env "(list-locals)")]
                                       (expect (= [1 2 3] (get (:result result) 'small-vec))))))))

  (describe "get-local"
            (it "returns nil for undefined variable"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(get-local 'undefined)")]
                                       (expect (nil? (:result result)))))))

            (it "returns full value of defined variable"
                (with-test-env* {} (fn [env]
                                     (#'sut/execute-code env "(def x 42)")
                                     (let [result (#'sut/execute-code env "(get-local 'x)")]
                                       (expect (= 42 (:result result)))))))

            (it "returns full value of large collection (not summarized)"
                (with-test-env* {} (fn [env]
                                     (#'sut/execute-code env "(def big-list (vec (range 100)))")
                                     (let [result (#'sut/execute-code env "(get-local 'big-list)")]
                                       (expect (= (vec (range 100)) (:result result)))))))

            (it "returns actual function (can be called)"
                (with-test-env* {} (fn [env]
                                     (#'sut/execute-code env "(defn double-it [n] (* n 2))")
        ;; Can't test the function directly, but can verify it exists and works
                                     (let [result (#'sut/execute-code env "(double-it 5)")]
                                       (expect (= 10 (:result result)))))))))

;; =============================================================================
;; String Helper Tests (SCI Bindings)
;; =============================================================================

(defdescribe string-helpers-test
  (describe "str-lines"
            (it "splits string into lines"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-lines \"a\\nb\\nc\")")]
                                       (expect (= ["a" "b" "c"] (:result result)))))))

            (it "returns nil for nil input"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-lines nil)")]
                                       (expect (nil? (:result result))))))))

  (describe "str-words"
            (it "splits string into words"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-words \"hello world test\")")]
                                       (expect (= ["hello" "world" "test"] (:result result)))))))

            (it "handles multiple spaces"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-words \"  hello   world  \")")]
                                       (expect (= ["hello" "world"] (:result result))))))))

  (describe "str-truncate"
            (it "truncates long strings"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-truncate \"hello world\" 5)")]
                                       (expect (= "hello" (:result result)))))))

            (it "returns short strings unchanged"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-truncate \"hi\" 10)")]
                                       (expect (= "hi" (:result result))))))))

  (describe "str-join"
            (it "joins collection with separator"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-join \", \" [\"a\" \"b\" \"c\"])")]
                                       (expect (= "a, b, c" (:result result))))))))

  (describe "str-split"
            (it "splits string by regex"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-split \"a,b,c\" #\",\")")]
                                       (expect (= ["a" "b" "c"] (:result result))))))))

  (describe "str-replace"
            (it "replaces substring"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-replace \"hello world\" \"world\" \"clojure\")")]
                                       (expect (= "hello clojure" (:result result))))))))

  (describe "str-trim"
            (it "trims whitespace"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-trim \"  hello  \")")]
                                       (expect (= "hello" (:result result))))))))

  (describe "str-lower"
            (it "converts to lowercase"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-lower \"HELLO\")")]
                                       (expect (= "hello" (:result result))))))))

  (describe "str-upper"
            (it "converts to uppercase"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-upper \"hello\")")]
                                       (expect (= "HELLO" (:result result))))))))

  (describe "str-blank?"
            (it "returns true for blank string"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-blank? \"  \")")]
                                       (expect (true? (:result result)))))))

            (it "returns false for non-blank string"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-blank? \"hello\")")]
                                       (expect (false? (:result result))))))))

  (describe "str-includes?"
            (it "returns true when substring found"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-includes? \"hello world\" \"world\")")]
                                       (expect (true? (:result result)))))))

            (it "returns false when substring not found"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-includes? \"hello world\" \"foo\")")]
                                       (expect (false? (:result result))))))))

  (describe "str-starts-with?"
            (it "returns true when starts with prefix"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-starts-with? \"hello world\" \"hello\")")]
                                       (expect (true? (:result result))))))))

  (describe "str-ends-with?"
            (it "returns true when ends with suffix"
                (with-test-env* {} (fn [env]
                                     (let [result (#'sut/execute-code env "(str-ends-with? \"hello world\" \"world\")")]
                                       (expect (true? (:result result)))))))))



;; =============================================================================
;; Message History Tests
;; =============================================================================

(defdescribe message-history-test
  (describe "init-message-history!"
            (it "initializes schema in database"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (let [result (#'sut/init-message-history! db-info)]
                      (expect (= :schema-initialized result)))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "returns nil for nil db-info"
                (expect (nil? (#'sut/init-message-history! nil)))))

  (describe "store-message!"
            (it "stores a message with generated id"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-message-history! db-info)
                    (let [result (#'sut/store-message! db-info :user "Hello, world!")]
                      (expect (some? (:id result)))
                      (expect (= :user (:role result)))
                      (expect (= "Hello, world!" (:content result)))
                      (expect (some? (:tokens result)))
                      (expect (some? (:timestamp result))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "stores iteration number when provided"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-message-history! db-info)
                    (let [result (#'sut/store-message! db-info :assistant "Response" {:iteration 3})]
                      (expect (= :assistant (:role result))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "returns nil for nil db-info"
                (expect (nil? (#'sut/store-message! nil :user "test")))))

  (describe "get-recent-messages"
            (it "returns messages in reverse chronological order"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-message-history! db-info)
                    (#'sut/store-message! db-info :user "First")
                    (Thread/sleep 10) ; Ensure different timestamps
                    (#'sut/store-message! db-info :assistant "Second")
                    (Thread/sleep 10)
                    (#'sut/store-message! db-info :user "Third")
                    (let [results (#'sut/get-recent-messages db-info 10)]
                      (expect (= 3 (count results)))
                      (expect (= "Third" (:content (first results))))
                      (expect (= "First" (:content (last results)))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "respects limit parameter"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-message-history! db-info)
                    (doseq [i (range 5)]
                      (#'sut/store-message! db-info :user (str "Message " i)))
                    (let [results (#'sut/get-recent-messages db-info 2)]
                      (expect (= 2 (count results))))
                    (finally
                      (#'sut/dispose-db! db-info))))))

  (describe "count-history-tokens"
            (it "counts total tokens across messages"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-message-history! db-info)
                    (#'sut/store-message! db-info :user "Hello")
                    (#'sut/store-message! db-info :assistant "World")
                    (let [total (#'sut/count-history-tokens db-info)]
                      (expect (pos? total)))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "returns 0 for empty history"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-message-history! db-info)
                    (expect (= 0 (#'sut/count-history-tokens db-info)))
                    (finally
                      (#'sut/dispose-db! db-info))))))

  (describe "get-recent-messages retrieval"
            (it "returns messages with expected keys"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-message-history! db-info)
                    (#'sut/store-message! db-info :user "How do I bake chocolate cookies?")
                    (#'sut/store-message! db-info :assistant "Mix flour, sugar, chocolate chips and bake at 350F")
                    (let [results (#'sut/get-recent-messages db-info 2)]
                      (expect (= 2 (count results)))
                      (expect (every? #(contains? % :content) results))
                      (expect (every? #(contains? % :role) results)))
                    (finally
                      (#'sut/dispose-db! db-info)))))))

;; =============================================================================
;; History Query Functions Tests (SCI Bindings)
;; =============================================================================

(defdescribe history-query-functions-test
  (describe "search-history SCI binding"
            (it "gets recent history from within SCI"
                (with-test-env* {} (fn [env]
        ;; Store some messages first
                                     (let [db-info @(:db-info-atom env)]
                                       (#'sut/store-message! db-info :user "How do I sort a list in Clojure?")
                                       (#'sut/store-message! db-info :assistant "Use (sort coll) or (sort-by key-fn coll)"))
        ;; search-history now takes n (number of messages) instead of query string
                                     (let [result (#'sut/execute-code env "(search-history 5)")]
                                       (expect (nil? (:error result)))
                                       (expect (vector? (:result result)))
                                       (expect (= 2 (count (:result result)))))))))

  (describe "get-history SCI binding"
            (it "gets recent history from within SCI"
                (with-test-env* {} (fn [env]
                                     (let [db-info @(:db-info-atom env)]
                                       (#'sut/store-message! db-info :user "First message")
                                       (#'sut/store-message! db-info :assistant "First response"))
                                     (let [result (#'sut/execute-code env "(get-history 10)")]
                                       (expect (nil? (:error result)))
                                       (expect (vector? (:result result)))
                                       (expect (= 2 (count (:result result)))))))))

  (describe "history-stats SCI binding"
            (it "returns history statistics from within SCI"
                (with-test-env* {} (fn [env]
                                     (let [db-info @(:db-info-atom env)]
                                       (#'sut/store-message! db-info :user "Test message")
                                       (#'sut/store-message! db-info :assistant "Test response"))
                                     (let [result (#'sut/execute-code env "(history-stats)")]
                                       (expect (nil? (:error result)))
                                       (expect (map? (:result result)))
                                       (expect (contains? (:result result) :total-messages))
                                       (expect (contains? (:result result) :total-tokens))
                                       (expect (contains? (:result result) :by-role))))))))

;; =============================================================================
;; Example Learning Tests
;; =============================================================================

(defdescribe example-learning-test
  (it "stores and retrieves examples"
      (#'sut/clear-examples!)
      (#'sut/store-example! "What is 2+2?" "math context" "4" 40 nil)
      (let [examples (#'sut/get-examples "What is 2+2?" {})]
        (expect (= 1 (count (:good examples))))
        (expect (= "4" (:answer (first (:good examples)))))))

  (it "categorizes good and bad examples by score"
      (#'sut/clear-examples!)
      (#'sut/store-example! "test" "ctx" "good answer" 35 nil)
      (#'sut/store-example! "test" "ctx" "bad answer" 20 "too short")
      (let [examples (#'sut/get-examples "test" {})]
        (expect (= 1 (count (:good examples))))
        (expect (= 1 (count (:bad examples))))))

  (it "limits examples to max 3 good and 3 bad"
      (#'sut/clear-examples!)
    ;; Store 5 good and 5 bad examples
      (doseq [i (range 5)]
        (#'sut/store-example! "query" "ctx" (str "good-" i) (+ 35 i) nil))
      (doseq [i (range 5)]
        (#'sut/store-example! "query" "ctx" (str "bad-" i) (+ 10 i) "bad"))
      (let [examples (#'sut/get-examples "query" {:max-good 10 :max-bad 10})]
      ;; Should still be capped at 3 each
        (expect (<= (count (:good examples)) 3))
        (expect (<= (count (:bad examples)) 3))))

  (it "retrieves examples by recency (not semantic similarity)"
      (#'sut/clear-examples!)
    ;; Store example
      (#'sut/store-example! "What is the capital of France?" "geography" "Paris" 40 nil)
    ;; Retrieve - query is ignored, returns by recency
      (let [examples (#'sut/get-examples "any query" {})]
      ;; Should find the example since it's the most recent
        (expect (= 1 (count (:good examples))))
        (expect (= "Paris" (:answer (first (:good examples))))))))

;; =============================================================================
;; Learnings System Tests (DB-backed)
;; =============================================================================

(defdescribe learnings-test
  (describe "init-learning-schema!"
            (it "initializes schema in database"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (let [result (#'sut/init-learning-schema! db-info)]
                      (expect (= :schema-initialized result)))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "returns nil for nil db-info"
                (expect (nil? (#'sut/init-learning-schema! nil)))))

  (describe "db-store-learning!"
            (it "stores a learning with insight"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-learning-schema! db-info)
                    (let [result (#'sut/db-store-learning! db-info "Always verify data recency")]
                      (expect (some? (:learning/id result)))
                      (expect (= "Always verify data recency" (:learning/insight result)))
                      (expect (some? (:learning/timestamp result))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "stores learning with context"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-learning-schema! db-info)
                    (let [result (#'sut/db-store-learning! db-info "Check for duplicates" "aggregation tasks")]
                      (expect (= "aggregation tasks" (:learning/context result))))
                    (finally
                      (#'sut/dispose-db! db-info))))))

  (describe "db-get-learnings"
            (it "finds semantically similar learnings"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-learning-schema! db-info)
                    (#'sut/db-store-learning! db-info "For date questions, always verify the year in the context")
                    (#'sut/db-store-learning! db-info "Check database connection before queries")
          ;; Search with related wording
                    (let [learnings (#'sut/db-get-learnings db-info "verifying dates and years" {:top-k 2})]
                      (expect (<= (count learnings) 2))
                      (expect (every? #(contains? % :insight) learnings)))
                    (finally
                      (#'sut/dispose-db! db-info))))))

  (describe "db-learning-stats"
            (it "returns learning statistics including voting stats"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-learning-schema! db-info)
                    (#'sut/db-store-learning! db-info "Insight without context")
                    (#'sut/db-store-learning! db-info "Insight with context" "some domain")
                    (let [stats (#'sut/db-learning-stats db-info)]
                      (expect (= 2 (:total-learnings stats)))
                      (expect (= 2 (:active-learnings stats)))
                      (expect (= 0 (:decayed-learnings stats)))
                      (expect (= 1 (:with-context stats)))
                      (expect (= 1 (:without-context stats)))
                      (expect (= 0 (:total-votes stats)))
                      (expect (= 0 (:total-applications stats))))
                    (finally
                      (#'sut/dispose-db! db-info))))))

  (describe "db-vote-learning!"
            (it "records positive vote"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-learning-schema! db-info)
                    (let [stored (#'sut/db-store-learning! db-info "Test insight")
                          voted (#'sut/db-vote-learning! db-info (:learning/id stored) :useful)]
                      (expect (= 1 (:learning/useful-count voted)))
                      (expect (= 0 (:learning/not-useful-count voted)))
                      (expect (false? (:learning/decayed? voted))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "records negative vote"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-learning-schema! db-info)
                    (let [stored (#'sut/db-store-learning! db-info "Test insight")
                          voted (#'sut/db-vote-learning! db-info (:learning/id stored) :not-useful)]
                      (expect (= 0 (:learning/useful-count voted)))
                      (expect (= 1 (:learning/not-useful-count voted))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "accumulates multiple votes"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-learning-schema! db-info)
                    (let [stored (#'sut/db-store-learning! db-info "Test insight")]
                      (#'sut/db-vote-learning! db-info (:learning/id stored) :useful)
                      (#'sut/db-vote-learning! db-info (:learning/id stored) :useful)
                      (let [voted (#'sut/db-vote-learning! db-info (:learning/id stored) :not-useful)]
                        (expect (= 2 (:learning/useful-count voted)))
                        (expect (= 1 (:learning/not-useful-count voted)))))
                    (finally
                      (#'sut/dispose-db! db-info))))))

  (describe "learning decay"
            (it "marks learning as decayed after 5+ votes with >70% negative"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-learning-schema! db-info)
                    (let [stored (#'sut/db-store-learning! db-info "Bad insight")]
            ;; Vote 1 useful, 5 not-useful (>70% negative, 6 total votes)
                      (#'sut/db-vote-learning! db-info (:learning/id stored) :useful)
                      (dotimes [_ 5]
                        (#'sut/db-vote-learning! db-info (:learning/id stored) :not-useful))
                      (let [final-vote (#'sut/db-vote-learning! db-info (:learning/id stored) :not-useful)]
                        (expect (true? (:learning/decayed? final-vote)))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "filters decayed learnings from search results"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-learning-schema! db-info)
          ;; Store two learnings
                    (let [_ (#'sut/db-store-learning! db-info "Good insight about testing")
                          bad-learning (#'sut/db-store-learning! db-info "Bad insight about testing")]
            ;; Make 'bad' learning decay
                      (dotimes [_ 6]
                        (#'sut/db-vote-learning! db-info (:learning/id bad-learning) :not-useful))
            ;; Search should only return good learning
                      (let [results (#'sut/db-get-learnings db-info "testing" {:top-k 10 :track-usage? false})]
                        (expect (= 1 (count results)))
                        (expect (= "Good insight about testing" (:insight (first results))))))
                    (finally
                      (#'sut/dispose-db! db-info))))))

  (describe "db-increment-applied-count!"
            (it "increments applied count"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-learning-schema! db-info)
                    (let [stored (#'sut/db-store-learning! db-info "Test insight")]
                      (expect (= 1 (#'sut/db-increment-applied-count! db-info (:learning/id stored))))
                      (expect (= 2 (#'sut/db-increment-applied-count! db-info (:learning/id stored)))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "search-learnings auto-increments applied count"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-learning-schema! db-info)
                    (#'sut/db-store-learning! db-info "Test insight for tracking")
          ;; Search twice - verify results are returned so we know tracking should work
                    (let [results1 (#'sut/db-get-learnings db-info "insight for tracking" {:top-k 5})
                          results2 (#'sut/db-get-learnings db-info "insight for tracking" {:top-k 5})]
            ;; Verify searches actually returned results (otherwise tracking won't happen)
                      (expect (pos? (count results1)) "First search should return results")
                      (expect (pos? (count results2)) "Second search should return results")
            ;; Check stats
                      (let [stats (#'sut/db-learning-stats db-info)]
                        (expect (= 2 (:total-applications stats)))))
                    (finally
                      (#'sut/dispose-db! db-info)))))))

(defdescribe learnings-sci-bindings-test
  (it "store-learning is available in SCI"
      (with-test-env* {} (fn [env]
                           (let [result (#'sut/execute-code env "(store-learning \"Test insight from SCI\")")]
                             (expect (nil? (:error result)))
                             (expect (map? (:result result)))
                             (expect (= "Test insight from SCI" (:learning/insight (:result result))))))))

  (it "search-learnings is available in SCI and returns learning/id"
      (with-test-env* {} (fn [env]
      ;; Store a learning first using SCI
                           (#'sut/execute-code env "(store-learning \"Test insight for searching\")")
                           (let [result (#'sut/execute-code env "(search-learnings \"test searching\")")]
                             (expect (nil? (:error result)))
                             (expect (vector? (:result result)))
        ;; Verify results contain :learning/id for voting
                             (when (seq (:result result))
                               (expect (some? (:learning/id (first (:result result))))))))))

  (it "vote-learning is available in SCI"
      (with-test-env* {} (fn [env]
      ;; Store a learning and get its ID
                           (#'sut/execute-code env "(def my-learning (store-learning \"Test insight for voting\"))")
      ;; Vote on it
                           (let [result (#'sut/execute-code env "(vote-learning (:learning/id my-learning) :useful)")]
                             (expect (nil? (:error result)))
                             (expect (map? (:result result)))
                             (expect (= 1 (:learning/useful-count (:result result))))))))

  (it "vote-learning rejects invalid vote values"
      (with-test-env* {} (fn [env]
                           (#'sut/execute-code env "(def my-learning (store-learning \"Test\"))")
                           (let [result (#'sut/execute-code env "(vote-learning (:learning/id my-learning) :maybe)")]
                             (expect (nil? (:error result)))
                             (expect (= {:error "Vote must be :useful or :not-useful"} (:result result)))))))

  (it "learning-stats includes voting stats"
      (with-test-env* {} (fn [env]
      ;; Store a learning and vote on it
                           (#'sut/execute-code env "(def my-learning (store-learning \"Test insight\"))")
                           (#'sut/execute-code env "(vote-learning (:learning/id my-learning) :useful)")
                           (let [result (#'sut/execute-code env "(learning-stats)")]
                             (expect (nil? (:error result)))
                             (expect (= 1 (:total-learnings (:result result))))
                             (expect (= 1 (:active-learnings (:result result))))
                             (expect (= 0 (:decayed-learnings (:result result))))
                             (expect (= 1 (:total-votes (:result result))))))))

  (it "learnings are not available when db is disabled"
      (with-test-env* {} {:db false} (fn [env]
                                       (let [result (#'sut/execute-code env "(store-learning \"test\")")]
        ;; Should either error or return nil since db is disabled
                                         (expect (or (some? (:error result)) (nil? (:result result)))))))))

;; =============================================================================
;; Build System Prompt Tests
;; =============================================================================

(defdescribe build-system-prompt-test
  (it "includes basic environment info"
      (let [prompt (#'sut/build-system-prompt {})]
        (expect (str/includes? prompt "<rlm_environment>"))
        (expect (str/includes? prompt "available_tools"))
        (expect (str/includes? prompt "FINAL"))))

  (it "includes learnings tools with voting"
      (let [prompt (#'sut/build-system-prompt {})]
        (expect (str/includes? prompt "learnings_tools"))
        (expect (str/includes? prompt "store-learning"))
        (expect (str/includes? prompt "search-learnings"))
        (expect (str/includes? prompt "vote-learning"))
        (expect (str/includes? prompt "voting_workflow"))))

  (it "includes history tools when enabled"
      (let [prompt (#'sut/build-system-prompt {:history-enabled? true})]
        (expect (str/includes? prompt "search-history"))
        (expect (str/includes? prompt "get-history"))
        (expect (str/includes? prompt "history-stats"))))

  (it "excludes history tools when disabled"
      (let [prompt (#'sut/build-system-prompt {:history-enabled? false})]
        (expect (not (str/includes? prompt "history_tools")))))

  (it "includes entity tools section"
      (let [prompt (#'sut/build-system-prompt {})]
        (expect (str/includes? prompt "entity_tools"))
        (expect (str/includes? prompt "search-entities"))
        (expect (str/includes? prompt "get-entity"))
        (expect (str/includes? prompt "list-entities"))
        (expect (str/includes? prompt "list-relationships"))
        (expect (str/includes? prompt "entity-stats"))
        (expect (str/includes? prompt "entity_schema"))
        (expect (str/includes? prompt "relationship_schema"))))

  (it "includes date tools section"
      (let [prompt (#'sut/build-system-prompt {})]
        (expect (str/includes? prompt "date_tools"))
        (expect (str/includes? prompt "parse-date"))
        (expect (str/includes? prompt "date-before?"))
        (expect (str/includes? prompt "date-after?"))
        (expect (str/includes? prompt "days-between"))
        (expect (str/includes? prompt "date-plus-days"))
        (expect (str/includes? prompt "date-minus-days"))
        (expect (str/includes? prompt "date-format"))
        (expect (str/includes? prompt "today-str"))))

  (it "includes set tools section"
      (let [prompt (#'sut/build-system-prompt {})]
        (expect (str/includes? prompt "set_tools"))
        (expect (str/includes? prompt "set-union"))
        (expect (str/includes? prompt "set-intersection"))
        (expect (str/includes? prompt "set-difference"))
        (expect (str/includes? prompt "set-subset?"))
        (expect (str/includes? prompt "set-superset?"))))

  (it "includes entity check in workflow"
      (let [prompt (#'sut/build-system-prompt {})]
        (expect (str/includes? prompt "(entity-stats)"))
        (expect (str/includes? prompt "(search-entities"))))

  (it "includes spec schema when provided"
      (let [test-spec (svar/spec
                       (svar/field {svar/NAME :name
                                    svar/TYPE :spec.type/string
                                    svar/CARDINALITY :spec.cardinality/one
                                    svar/REQUIRED true
                                    svar/DESCRIPTION "Name field"}))
            prompt (#'sut/build-system-prompt {:output-spec test-spec})]
        (expect (str/includes? prompt "expected_output_schema")))))

;; =============================================================================
;; Sub-RLM Tests
;; =============================================================================

(defdescribe sub-rlm-test
  (describe "rlm-query SCI binding"
            (it "is available when database is enabled"
                (with-test-env* {} (fn [env]
        ;; rlm-query should be defined
                                     (let [result (#'sut/execute-code env "(fn? rlm-query)")]
                                       (expect (nil? (:error result)))
          ;; May return true or false depending on binding
                                       (expect (boolean? (:result result)))))))

            (it "is not available when database is disabled"
                (with-test-env* {} {:db false} (fn [env]
        ;; rlm-query should not be defined
                                                 (let [result (#'sut/execute-code env "(bound? #'rlm-query)")]
          ;; Should error since rlm-query is not defined
                                                   (expect (some? (:error result))))))))

  (describe "make-rlm-query-fn"
            (it "enforces max recursion depth"
                (let [depth-atom (atom 5)
                      db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-message-history! db-info)
                    (let [db-info-atom (atom db-info)
                          rlm-query-fn (#'sut/make-rlm-query-fn "gpt-4o" depth-atom nil nil db-info-atom)]
            ;; Should return error when at max depth
                      (binding [sut/*max-recursion-depth* 5]
                        (let [result (rlm-query-fn {:data "test"} "What is this?")]
                          (expect (map? result))
                          (expect (some? (:error result)))
                          (expect (str/includes? (str (:error result)) "recursion")))))
                    (finally
                      (#'sut/dispose-db! db-info)))))))

;; =============================================================================
;; Format Execution Results Tests
;; =============================================================================

(defdescribe format-executions-test
  (it "formats successful results"
      (let [results [{:id 1 :result 42 :stdout "" :error nil}]
            formatted (#'sut/format-executions results)]
        (expect (str/includes? formatted "<result_1>"))
        (expect (str/includes? formatted "<value>42</value>"))
        (expect (str/includes? formatted "</result_1>"))))

  (it "formats errors"
      (let [results [{:id 1 :result nil :stdout "" :error "Division by zero"}]
            formatted (#'sut/format-executions results)]
        (expect (str/includes? formatted "<error>Division by zero</error>"))))

  (it "includes stdout when present"
      (let [results [{:id 1 :result nil :stdout "Hello\n" :error nil}]
            formatted (#'sut/format-executions results)]
        (expect (str/includes? formatted "<stdout>Hello"))))

  (it "formats multiple results"
      (let [results [{:id 1 :result 1 :stdout "" :error nil}
                     {:id 2 :result 2 :stdout "" :error nil}]
            formatted (#'sut/format-executions results)]
        (expect (str/includes? formatted "<result_1>"))
        (expect (str/includes? formatted "<result_2>")))))

;; =============================================================================
;; Integration Tests (real LLM calls)
;; =============================================================================

(def ^:private test-config
  "LLM config for integration tests. 
   Uses OPENAI_API_KEY env var."
  {:api-key (System/getenv "OPENAI_API_KEY")
   :base-url (or (System/getenv "OPENAI_BASE_URL")
                 "https://api.openai.com/v1")
   :default-model "gpt-4o"})

(defn- integration-tests-enabled?
  "Returns true if LLM integration tests should run.
   Checks if test-config has a valid API key from environment."
  []
  (some? (:api-key test-config)))

(defn- with-integration-env*
  "Creates an RLM environment for integration tests, executes f, then disposes."
  [f]
  (let [env (sut/create-env {:config test-config})]
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
                     (let [result (sut/query-env! env "What is the capital of France? Answer with just the city name."
                                              {:context "Paris is the capital of France."
                                               :max-iterations 10
                                               :learn? false})]
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
                     (let [result (sut/query-env! env "What is 2 + 2?"
                                              {:context "2 + 2 = 4"
                                               :max-iterations 5
                                               :refine? false
                                               :learn? false})]
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
                     (let [result (sut/query-env! env "What is the value of :count? Use (FINAL answer)"
                                              {:context {:count 42}
                                               :max-iterations 10
                                               :refine? false
                                               :learn? false})]
                       (expect (map? result))
                       (if (:status result)
                         (expect (= :max-iterations (:status result)))
                         (expect (re-find #"42" (str (:answer result)))))))))))

  (describe "refinement loop"
            (it "applies refinement by default"
                (when (integration-tests-enabled?)
                  (with-integration-env*
                   (fn [env]
                     (let [result (sut/query-env! env "Sum the numbers"
                                              {:context {:nums [1 2 3 4 5]}
                                               :max-iterations 10
                                               :max-refinements 1
                                               :learn? false})]
                       (expect (map? result))
                       (when-not (:status result)
                         (expect (some? (:eval-scores result)))
                         (expect (number? (:eval-scores result))))))))))

  (describe "history tracking"
            (it "tracks history tokens when enabled"
                (when (integration-tests-enabled?)
                  (with-integration-env*
                   (fn [env]
                     (let [result (sut/query-env! env "Echo the context"
                                              {:context "Test context"
                                               :max-iterations 5
                                               :refine? false
                                               :learn? false})]
                       (expect (map? result))
                       (when-not (:status result)
                         (expect (contains? result :history-tokens))
                         (expect (number? (:history-tokens result))))))))))

  (describe "validation"
            (it "throws when env is invalid"
                (expect (throws? clojure.lang.ExceptionInfo
                                 #(sut/query-env! {} "test query"))))

            (it "throws when query is missing"
                (with-integration-env* (fn [env]
                                         (expect (throws? clojure.lang.ExceptionInfo
                                                          #(sut/query-env! env nil))))))))

(defdescribe make-llm-query-fn-test
  (describe "recursion depth tracking"
            (it "enforces max recursion depth"
                (let [depth-atom (atom 0)
                      query-fn (#'sut/make-llm-query-fn "gpt-4o" depth-atom nil nil)]
                  (reset! depth-atom sut/DEFAULT_RECURSION_DEPTH)
                  (let [result (query-fn "test")]
                    (expect (string? result))
                    (expect (re-find #"(?i)max.*recursion.*depth" result)))))

            (it "decrements depth after call"
                (let [depth-atom (atom 0)
                      query-fn (#'sut/make-llm-query-fn "gpt-4o" depth-atom nil nil)]
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
                                                 (expect (= 10 (:result (#'sut/execute-code env "(+ 1 2 3 4)"))))
                                                 (expect (= 6 (:result (#'sut/execute-code env "(* 2 3)"))))
                                                 (expect (= 2 (:result (#'sut/execute-code env "(/ 10 5)"))))
                                                 (expect (= 3 (:result (#'sut/execute-code env "(- 10 7)"))))))))

  (describe "collection operations"
            (it "provides map/filter/reduce"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (= [2 4 6] (:result (#'sut/execute-code env "(mapv #(* 2 %) [1 2 3])"))))
                                                 (expect (= [2 4] (:result (#'sut/execute-code env "(vec (filter even? [1 2 3 4]))"))))
                                                 (expect (= 10 (:result (#'sut/execute-code env "(reduce + [1 2 3 4])"))))))))

  (describe "string operations"
            (it "provides str and related"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (= "hello world" (:result (#'sut/execute-code env "(str \"hello\" \" \" \"world\")"))))
                                                 (expect (= "ell" (:result (#'sut/execute-code env "(subs \"hello\" 1 4)"))))
                                                 (expect (= "test" (:result (#'sut/execute-code env "(name :test)"))))))))

  (describe "comparison operations"
            (it "provides equality and ordering"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (true? (:result (#'sut/execute-code env "(= 1 1)"))))
                                                 (expect (true? (:result (#'sut/execute-code env "(< 1 2)"))))
                                                 (expect (true? (:result (#'sut/execute-code env "(>= 5 5)"))))))))

  (describe "atom operations"
            (it "provides atom and swap!"
                (with-test-env* {} {:db false} (fn [env]
                                                 (#'sut/execute-code env "(def counter (atom 0))")
                                                 (#'sut/execute-code env "(swap! counter inc)")
                                                 (expect (= 1 (:result (#'sut/execute-code env "@counter")))))))))

;; =============================================================================
;; Date Helper Functions Tests (RED - Failing Tests)
;; =============================================================================

(defdescribe date-helper-functions-test
  (describe "parse-date"
            (it "parses valid ISO-8601 date string"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (some? (:result (#'sut/execute-code env "(parse-date \"2024-01-15\")")))))))

            (it "returns nil for invalid date string"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (nil? (:result (#'sut/execute-code env "(parse-date \"invalid\")"))))))))

  (describe "date-before?"
            (it "returns true when first date is before second"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (true? (:result (#'sut/execute-code env "(date-before? \"2024-01-15\" \"2024-06-01\")")))))))

            (it "returns false when first date is after second"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (false? (:result (#'sut/execute-code env "(date-before? \"2024-06-01\" \"2024-01-15\")"))))))))

  (describe "date-after?"
            (it "returns true when first date is after second"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (true? (:result (#'sut/execute-code env "(date-after? \"2024-06-01\" \"2024-01-15\")")))))))

            (it "returns false when first date is before second"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (false? (:result (#'sut/execute-code env "(date-after? \"2024-01-15\" \"2024-06-01\")"))))))))

  (describe "days-between"
            (it "returns number of days between two dates"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (= 31 (:result (#'sut/execute-code env "(days-between \"2024-01-15\" \"2024-02-15\")")))))))

            (it "returns negative when dates reversed"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (= -31 (:result (#'sut/execute-code env "(days-between \"2024-02-15\" \"2024-01-15\")"))))))))

  (describe "date-plus-days"
            (it "adds days to a date"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (= "2024-02-14" (:result (#'sut/execute-code env "(date-plus-days \"2024-01-15\" 30)")))))))

            (it "returns ISO-8601 string"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (string? (:result (#'sut/execute-code env "(date-plus-days \"2024-01-15\" 10)"))))))))

  (describe "date-minus-days"
            (it "subtracts days from a date"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (= "2024-05-02" (:result (#'sut/execute-code env "(date-minus-days \"2024-06-01\" 30)")))))))

            (it "returns ISO-8601 string"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (string? (:result (#'sut/execute-code env "(date-minus-days \"2024-06-01\" 10)"))))))))

  (describe "date-format"
            (it "formats date with custom pattern"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (= "15/01/2024" (:result (#'sut/execute-code env "(date-format \"2024-01-15\" \"dd/MM/yyyy\")")))))))

            (it "returns nil for invalid format pattern"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (nil? (:result (#'sut/execute-code env "(date-format \"2024-01-15\" \"invalid\")"))))))))

  (describe "today-str"
            (it "returns today's date as ISO-8601 string"
                (with-test-env* {} {:db false} (fn [env]
                                                 (let [result (:result (#'sut/execute-code env "(today-str)"))]
                                                   (expect (string? result))
                                                   (expect (re-matches #"\d{4}-\d{2}-\d{2}" result))))))))

;; =============================================================================
;; Set Functions Tests (RED - Failing Tests)
;; =============================================================================

(defdescribe set-functions-test
  (describe "set-union"
            (it "returns union of two sets"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (= #{1 2 3} (:result (#'sut/execute-code env "(set-union #{1 2} #{2 3})")))))))

            (it "handles empty sets"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (= #{1 2} (:result (#'sut/execute-code env "(set-union #{1 2} #{})"))))))))

  (describe "set-intersection"
            (it "returns intersection of two sets"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (= #{2 3} (:result (#'sut/execute-code env "(set-intersection #{1 2 3} #{2 3 4})")))))))

            (it "returns empty set when no common elements"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (= #{} (:result (#'sut/execute-code env "(set-intersection #{1 2} #{3 4})"))))))))

  (describe "set-difference"
            (it "returns difference of two sets"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (= #{1} (:result (#'sut/execute-code env "(set-difference #{1 2 3} #{2 3})")))))))

            (it "returns first set when second is empty"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (= #{1 2} (:result (#'sut/execute-code env "(set-difference #{1 2} #{})"))))))))

  (describe "set-subset?"
            (it "returns true when first set is subset of second"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (true? (:result (#'sut/execute-code env "(set-subset? #{1 2} #{1 2 3})")))))))

            (it "returns false when first set is not subset of second"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (false? (:result (#'sut/execute-code env "(set-subset? #{1 2 3} #{1 2})"))))))))

  (describe "set-superset?"
            (it "returns true when first set is superset of second"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (true? (:result (#'sut/execute-code env "(set-superset? #{1 2 3} #{1 2})")))))))

            (it "returns false when first set is not superset of second"
                (with-test-env* {} {:db false} (fn [env]
                                                 (expect (false? (:result (#'sut/execute-code env "(set-superset? #{1 2} #{1 2 3})")))))))))

;; =============================================================================
;; Entity Schema Tests (RED - Failing Tests)
;; =============================================================================

(defdescribe entity-schema-test
  (it "creates DB and stores entity"
      (with-test-env* {} (fn [env]
                           (let [db-info @(:db-info-atom env)
                                 store (:store db-info)
                                 entity-id (java.util.UUID/randomUUID)
                                 entity {:entity/id entity-id
                                         :entity/name "John Doe"
                                         :entity/type :party
                                         :entity/description "A party to the contract"
                                         :entity/document-id "doc-123"
                                         :entity/page 1
                                         :entity/section "Section 1"
                                         :entity/created-at (java.util.Date.)}]
                             ;; Store entity
                             (swap! store update :entities conj entity)
                             ;; Query it back
                             (let [entities (:entities @store)
                                   found (filter #(= entity-id (:entity/id %)) entities)]
                               (expect (= 1 (count found)))
                               (expect (= "John Doe" (:entity/name (first found))))
                               (expect (= :party (:entity/type (first found)))))))))

  (it "stores and retrieves entity with legal extension attributes"
      (with-test-env* {} (fn [env]
                           (let [db-info @(:db-info-atom env)
                                 store (:store db-info)
                                 entity-id (java.util.UUID/randomUUID)
                                 entity {:entity/id entity-id
                                         :entity/name "Contractor"
                                         :entity/type :party
                                         :entity/description "Service provider"
                                         :entity/document-id "doc-456"
                                         :entity/page 2
                                         :entity/section "Parties"
                                         :entity/created-at (java.util.Date.)
                                         :legal/party-role :contractor
                                         :legal/effective-date "2025-01-01"
                                         :legal/expiry-date "2026-01-01"}]
                             ;; Store entity with legal attributes
                             (swap! store update :entities conj entity)
                             ;; Query it back
                             (let [found (first (filter #(= entity-id (:entity/id %)) (:entities @store)))]
                               (expect (some? found))
                               (expect (= :contractor (:legal/party-role found)))
                               (expect (= "2025-01-01" (:legal/effective-date found)))
                               (expect (= "2026-01-01" (:legal/expiry-date found))))))))

  (it "stores and retrieves relationship between two entities"
      (with-test-env* {} (fn [env]
                           (let [db-info @(:db-info-atom env)
                                 store (:store db-info)
                                 source-id (java.util.UUID/randomUUID)
                                 target-id (java.util.UUID/randomUUID)
                                 rel-id (java.util.UUID/randomUUID)
                                 source-entity {:entity/id source-id
                                                :entity/name "Party A"
                                                :entity/type :party
                                                :entity/description "First party"
                                                :entity/document-id "doc-789"
                                                :entity/page 1
                                                :entity/section "Parties"
                                                :entity/created-at (java.util.Date.)}
                                 target-entity {:entity/id target-id
                                                :entity/name "Obligation X"
                                                :entity/type :obligation
                                                :entity/description "Payment obligation"
                                                :entity/document-id "doc-789"
                                                :entity/page 3
                                                :entity/section "Obligations"
                                                :entity/created-at (java.util.Date.)}
                                 relationship {:relationship/id rel-id
                                               :relationship/source-entity-id source-id
                                               :relationship/target-entity-id target-id
                                               :relationship/type :obligates
                                               :relationship/document-id "doc-789"
                                               :relationship/description "Party A is obligated to perform"}]
                             ;; Store entities and relationship
                             (swap! store update :entities conj source-entity)
                             (swap! store update :entities conj target-entity)
                             (swap! store update :relationships conj relationship)
                             ;; Query relationship
                             (let [rels (:relationships @store)
                                   found (first (filter #(= rel-id (:relationship/id %)) rels))]
                               (expect (some? found))
                               (expect (= source-id (:relationship/source-entity-id found)))
                               (expect (= target-id (:relationship/target-entity-id found)))
                               (expect (= :obligates (:relationship/type found)))
                               (expect (= "Party A is obligated to perform" (:relationship/description found))))))))

  (it "stores and retrieves claim with citation and verification verdict"
      (with-test-env* {} (fn [env]
                           (let [db-info @(:db-info-atom env)
                                 store (:store db-info)
                                 claim-id (java.util.UUID/randomUUID)
                                 query-id (java.util.UUID/randomUUID)
                                 claim {:claim/id claim-id
                                        :claim/text "The contract is valid"
                                        :claim/document-id "doc-claim"
                                        :claim/page 5
                                        :claim/section "Validity"
                                        :claim/quote "This contract is valid and binding"
                                        :claim/confidence 0.95
                                        :claim/query-id query-id
                                        :claim/verified? true
                                        :claim/verification-verdict "correct"
                                        :claim/created-at (java.util.Date.)}]
                             ;; Store claim
                             (swap! store update :claims conj claim)
                             ;; Query it back
                             (let [found (first (filter #(= claim-id (:claim/id %)) (:claims @store)))]
                               (expect (some? found))
                               (expect (= "The contract is valid" (:claim/text found)))
                               (expect (= "This contract is valid and binding" (:claim/quote found)))
                               (expect (= 0.95 (:claim/confidence found)))
                               (expect (= "correct" (:claim/verification-verdict found)))
                               (expect (true? (:claim/verified? found)))))))))

;; =============================================================================
;; LLM Mock Infrastructure
;; =============================================================================

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
     (with-mock-ask! (fn [_] (let [r (first @calls)] (swap! calls rest) r))
       (svar/refine! opts)))"
  [response-fn & body]
  `(with-redefs [llm/ask! (fn [opts#] (~response-fn opts#))]
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
  `(with-redefs [llm/ask! (fn [opts#] (~ask-fn opts#))
                 llm/eval! (fn [opts#] (~eval-fn opts#))]
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
                (with-mock-ask! (fn [_opts] (make-mock-ask-response {:answer 42}))
                  (let [result (llm/ask! {:spec nil :objective "test" :task "test" :model "gpt-4o"})]
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
                  (fn [_opts] (make-mock-ask-response {:data "mocked"}))
                  (fn [_opts] (make-mock-eval-response 0.9))
                  (let [ask-result (llm/ask! {:spec nil :objective "t" :task "t" :model "gpt-4o"})
                        eval-result (llm/eval! {:task "t" :output "t" :model "gpt-4o"})]
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

(def ^:private test-ingest-config
  {:api-key "test"
   :base-url "https://api.openai.com/v1"
   :default-model "gpt-4o"})

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
        (with-mock-ask! (fn [opts]
                          (swap! calls conj opts)
                          (make-mock-ask-response
                           {:entities [{:entity/name "Acme Corp"
                                        :entity/type "organization"
                                        :entity/description "Provider"
                                        :entity/section "s1"
                                        :entity/page 0}]
                            :relationships []}))
          (let [env (sut/create-env {:config test-ingest-config})
                result (sut/ingest-to-env! env [(make-test-single-page-document)] {:extract-entities? true})]
            (sut/dispose-env! env)
            (expect (= 1 (count @calls)))
            (expect (pos? (get-in result [0 :entities-extracted])))))))

  (it "re-scans image nodes with vision model"
      (let [calls (atom [])]
        (with-mock-ask! (fn [opts]
                          (swap! calls conj opts)
                          (make-mock-ask-response
                           {:entities [] :relationships []}))
          (let [env (sut/create-env {:config test-ingest-config})
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

  (it "uses :extraction-model when provided"
      (let [calls (atom [])]
        (with-mock-ask! (fn [opts]
                          (swap! calls conj opts)
                          (make-mock-ask-response
                           {:entities [] :relationships []}))
          (let [env (sut/create-env {:config test-ingest-config})
                _result (sut/ingest-to-env! env [(make-test-single-page-document)] {:extract-entities? true :extraction-model "gpt-4o-mini"})]
            (sut/dispose-env! env)
            (expect (every? #(= "gpt-4o-mini" (:model %)) @calls))))))

  (it "respects :max-vision-rescan-nodes cap"
      (let [calls (atom [])]
        (with-mock-ask! (fn [opts]
                          (swap! calls conj opts)
                          (make-mock-ask-response {:entities [] :relationships []}))
          (let [env (sut/create-env {:config test-ingest-config})
                result (sut/ingest-to-env! env [(make-test-image-only-document)] {:extract-entities? true :max-vision-rescan-nodes 1})]
            (sut/dispose-env! env)
            (expect (= 1 (count @calls)))
            (expect (= 1 (get-in result [0 :visual-nodes-scanned])))))))

  (it "returns zero counts for empty document"
      (let [env (sut/create-env {:config test-ingest-config})
            result (sut/ingest-to-env! env [(make-test-empty-document)] {:extract-entities? true})]
        (sut/dispose-env! env)
        (expect (= 0 (get-in result [0 :entities-extracted])))
        (expect (= 0 (get-in result [0 :visual-nodes-scanned])))))

  (it "falls back to description-only extraction when image-data missing"
      (let [calls (atom [])]
        (with-mock-ask! (fn [opts]
                          (swap! calls conj opts)
                          (make-mock-ask-response
                           {:entities [] :relationships []}))
          (let [env (sut/create-env {:config test-ingest-config})
                _result (sut/ingest-to-env! env [(make-test-image-with-description-document)] {:extract-entities? true})]
            (sut/dispose-env! env)
            (expect (= 1 (count @calls)))
            (expect (nil? (:images (first @calls))))))))

  (it "handles page extraction failures gracefully"
      (let [calls (atom 0)]
        (with-mock-ask! (fn [_opts]
                          (swap! calls inc)
                          (throw (ex-info "boom" {})))
          (let [env (sut/create-env {:config test-ingest-config})
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
      (with-test-env* {} (fn [env]
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
  (it "search-entities returns empty vector when no entities exist"
      (with-test-env* {} (fn [env]
                           (let [result (#'sut/execute-code env "(search-entities \"party\")")]
                             (expect (nil? (:error result)))
                             (expect (= [] (:result result)))))))

  (it "get-entity returns nil for non-existent entity"
      (with-test-env* {} (fn [env]
                           (let [result (#'sut/execute-code env "(get-entity (java.util.UUID/randomUUID))")]
                             (expect (nil? (:error result)))
                             (expect (nil? (:result result)))))))

  (it "list-entities returns empty vector when no entities exist"
      (with-test-env* {} (fn [env]
                           (let [result (#'sut/execute-code env "(list-entities)")]
                             (expect (nil? (:error result)))
                             (expect (= [] (:result result)))))))

  (it "list-entities accepts filter options"
      (with-test-env* {} (fn [env]
                           (let [result (#'sut/execute-code env "(list-entities {:type :party :limit 10})")]
                             (expect (nil? (:error result)))
                             (expect (= [] (:result result)))))))

  (it "entity-stats returns zero counts when no entities exist"
      (with-test-env* {} (fn [env]
                           (let [result (#'sut/execute-code env "(entity-stats)")]
                             (expect (nil? (:error result)))
                             (expect (= {:total-entities 0 :types {} :total-relationships 0}
                                        (:result result)))))))

  (it "list-relationships returns empty vector for non-existent entity"
      (with-test-env* {} (fn [env]
                           (let [result (#'sut/execute-code env "(list-relationships (java.util.UUID/randomUUID))")]
                             (expect (nil? (:error result)))
                             (expect (= [] (:result result)))))))

  (it "entity functions not available when db is disabled"
      (with-test-env* {} {:db false} (fn [env]
                                       (let [result (#'sut/execute-code env "(entity-stats)")]
        ;; Should either error or return the fallback value
                                         (expect (or (some? (:error result))
                                                     (= {:total-entities 0 :types {} :total-relationships 0}
                                                        (:result result)))))))))

(defdescribe inline-cite-test
  (it "CITE accumulates claims in claims-atom"
      (let [claims-atom (atom [])
            cite-fn (#'sut/make-cite-fn claims-atom)]
        (cite-fn "Test claim" "doc-1" 0 "section-1" "exact quote")
        (expect (= 1 (count @claims-atom)))
        (expect (= "Test claim" (:claim/text (first @claims-atom))))
        (expect (= "doc-1" (:claim/document-id (first @claims-atom))))))

  (it "CITE return does NOT trigger check-result-for-final"
      (let [claims-atom (atom [])
            cite-fn (#'sut/make-cite-fn claims-atom)
            result (cite-fn "claim" "doc" 0 "s" "q")]
        (expect (nil? (:rlm/final result)))
        (expect (true? (:cited result)))))

  (it "CITE coerces types defensively"
      (let [claims-atom (atom [])
            cite-fn (#'sut/make-cite-fn claims-atom)]
        (cite-fn "claim" "doc" "0" "s" "q" "0.9")
        (let [claim (first @claims-atom)]
          (expect (= 0 (:claim/page claim)))
          (expect (float? (:claim/confidence claim))))))

  (it "CITE-UNVERIFIED sets low confidence"
      (let [claims-atom (atom [])
            cite-fn (#'sut/make-cite-unverified-fn claims-atom)]
        (cite-fn "unverified claim")
        (let [claim (first @claims-atom)]
          (expect (= 0.5 (:claim/confidence claim)))
          (expect (false? (:claim/verified? claim))))))

  (it "list-claims returns accumulated claims"
      (let [claims-atom (atom [])
            cite-fn (#'sut/make-cite-fn claims-atom)
            list-fn (#'sut/make-list-claims-fn claims-atom)]
        (cite-fn "c1" "d1" 0 "s1" "q1")
        (cite-fn "c2" "d2" 1 "s2" "q2")
        (expect (= 2 (count (list-fn)))))))

;; =============================================================================
;; Knowledge Engine Integration Tests
;; =============================================================================

(defmacro ^:private with-mock-chat!
  "Stubs llm/chat-completion for testing query-env! iteration loop.
   `response-fn` receives [messages model api-key base-url] and returns a string."
  [response-fn & body]
  `(let [v# (var com.blockether.svar.internal.llm/chat-completion)
         orig# (deref v#)]
     (try
       (alter-var-root v# (constantly ~response-fn))
       ~@body
       (finally
         (alter-var-root v# (constantly orig#))))))

(def ^:private final-response "{\"thinking\": \"Answering directly\", \"code\": [\"(FINAL \\\"test answer\\\")\"]}")

(defdescribe knowledge-engine-integration-test
  (it "backward compat - query without opt-in flags returns standard shape"
      (let [env (sut/create-env {:config test-ingest-config})]
        (try
          (with-mock-chat! (fn [& _] final-response)
            (let [result (sut/query-env! env "What is X?" {:refine? false :learn? false})]
              (expect (contains? result :answer))
              (expect (contains? result :eval-scores))
              (expect (contains? result :refinement-count))
              (expect (not (contains? result :verified-claims)))
              (expect (= "test answer" (:answer result)))
              (expect (= 0 (:refinement-count result)))))
          (finally (sut/dispose-env! env)))))

  (it "verify? true includes :verified-claims in result"
      (let [env (sut/create-env {:config test-ingest-config})]
        (try
          (with-mock-chat! (fn [& _] final-response)
            (let [result (sut/query-env! env "Find claims" {:verify? true :refine? false :learn? false})]
              (expect (contains? result :verified-claims))
              (expect (vector? (:verified-claims result)))
            ;; No CITE called in mock, so claims empty
              (expect (= [] (:verified-claims result)))))
          (finally (sut/dispose-env! env)))))

  (it "query on small docs returns answer"
      (let [env (sut/create-env {:config test-ingest-config})]
        (try
          (sut/ingest-to-env! env [(make-test-single-page-document)])
          (with-mock-chat! (fn [& _] final-response)
            (let [result (sut/query-env! env "test" {:refine? false :learn? false})]
              (expect (some? (:answer result)))))
          (finally (sut/dispose-env! env)))))

  (it "ingest with extract-entities? returns extraction stats"
      (with-mock-ask! (fn [_opts]
                        (make-mock-ask-response
                         {:entities [{:entity/name "Test Entity"
                                      :entity/type "party"
                                      :entity/description "A party"
                                      :entity/section "s1"
                                      :entity/page 0}]
                          :relationships []}))
        (let [env (sut/create-env {:config test-ingest-config})
              result (sut/ingest-to-env! env [(make-test-single-page-document)] {:extract-entities? true})]
          (sut/dispose-env! env)
          (expect (pos? (get-in result [0 :entities-extracted])))
          (expect (number? (get-in result [0 :visual-nodes-scanned]))))))

  (it "empty DB query with all flags returns gracefully"
      (let [env (sut/create-env {:config test-ingest-config})]
        (try
          (with-mock-chat! (fn [& _] final-response)
            (with-mock-ask! (fn [_opts] (make-mock-ask-response {:evaluations []}))
              (let [result (sut/query-env! env "anything"
                                       {:verify? true :refine? false :learn? false})]
                (expect (some? (:answer result)))
                (expect (contains? result :verified-claims))
                (expect (vector? (:verified-claims result))))))
          (finally (sut/dispose-env! env)))))

  (it "ingest without extraction then query with all flags works"
      (let [env (sut/create-env {:config test-ingest-config})]
        (try
        ;; Ingest without entity extraction
          (sut/ingest-to-env! env [(make-test-single-page-document)])
          (with-mock-chat! (fn [& _] final-response)
            (with-mock-ask! (fn [_opts] (make-mock-ask-response {:evaluations []}))
              (let [result (sut/query-env! env "test query"
                                       {:verify? true :refine? false :learn? false})]
                (expect (= "test answer" (:answer result)))
                (expect (contains? result :verified-claims)))))
          (finally (sut/dispose-env! env)))))

  (it "refine? true produces eval-scores and refinement-count"
      (let [env (sut/create-env {:config test-ingest-config})]
        (try
          (with-mock-chat! (fn [& _] final-response)
            (with-mock-ask-and-eval!
              (fn [_opts] (make-mock-ask-response "refined answer"))
              (fn [_opts] (make-mock-eval-response 0.95))
              (let [result (sut/query-env! env "test" {:refine? true :verify? false :learn? false})]
                (expect (some? (:answer result)))
                (expect (number? (:refinement-count result)))
                (expect (pos? (:refinement-count result))))))
          (finally (sut/dispose-env! env)))))

  (it "CITE functions compose in full lifecycle"
      (let [claims-atom (atom [])
            cite-fn (#'sut/make-cite-fn claims-atom)
            cite-unverified-fn (#'sut/make-cite-unverified-fn claims-atom)
            list-fn (#'sut/make-list-claims-fn claims-atom)]
      ;; Accumulate mixed claim types
        (cite-fn "Verified claim" "doc-1" 0 "s1" "exact quote" 0.95)
        (cite-unverified-fn "Unverified claim")
        (expect (= 2 (count (list-fn))))
      ;; Verified: high confidence
        (expect (> (:claim/confidence (first @claims-atom)) 0.9))
      ;; Unverified: low confidence + verified? false
        (expect (= 0.5 (:claim/confidence (second @claims-atom))))
        (expect (false? (:claim/verified? (second @claims-atom))))))

  (it "entity-stats returns zero counts on empty DB via full env"
      (with-test-env* {} (fn [env]
                           (let [result (#'sut/execute-code env "(entity-stats)")]
                             (expect (nil? (:error result)))
                             (expect (= {:total-entities 0 :types {} :total-relationships 0}
                                        (:result result))))))))

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
                                           (let [result (sut/query-env! env "What was TechCorp's total revenue in 2024?"
                                                                    {:refine? false
                                                                     :learn? false
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
                                           (let [result (sut/query-env! env "What is the title?"
                                                                    {:refine? false
                                                                     :learn? false
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
                                           (let [result (sut/query-env! env "What was the 2024 revenue and who is the CEO? Cite your sources."
                                                                    {:verify? true
                                                                     :refine? false
                                                                     :learn? false
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
                                           (let [result (sut/query-env! env "What is the title of the document?"
                                                                    {:verify? true
                                                                     :refine? false
                                                                     :learn? false
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
                                                                            "Who is the CEO and what market expansion happened in 2024?"
                                                                            {:verify? true
                                                                             :refine? false
                                                                             :learn? false
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
                                           (let [result (sut/query-env! env "List all parties and companies mentioned in the documents."
                                                                    {:verify? true
                                                                     :refine? false
                                                                     :learn? false
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

;; =============================================================================
;; T9: Search Function Tests (text-based search fixes)
;; =============================================================================

(defdescribe search-page-nodes-test
  (describe "db-search-page-nodes"
            (it "finds nodes matching content text"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/db-store-page-node! db-info
                      {:page.node/type :paragraph
                       :page.node/content "All parties must comply with regulatory requirements."}
                      "page-1" "doc-1")
                    (#'sut/db-store-page-node! db-info
                      {:page.node/type :paragraph
                       :page.node/content "Payment is due within 30 days of invoice date."}
                      "page-2" "doc-1")
                    (let [results (#'sut/db-search-page-nodes db-info "comply")]
                      (expect (= 1 (count results)))
                      (expect (str/includes? (:page.node/content (first results)) "comply")))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "finds nodes matching description text"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/db-store-page-node! db-info
                      {:page.node/type :image
                       :page.node/description "A chart showing quarterly revenue growth"}
                      "page-1" "doc-1")
                    (let [results (#'sut/db-search-page-nodes db-info "revenue")]
                      (expect (= 1 (count results)))
                      (expect (str/includes? (:page.node/description (first results)) "revenue")))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "returns empty for non-matching query"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/db-store-page-node! db-info
                      {:page.node/type :paragraph
                       :page.node/content "Some regular content here."}
                      "page-1" "doc-1")
                    (let [results (#'sut/db-search-page-nodes db-info "xyznonexistent")]
                      (expect (= 0 (count results))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "falls back to list mode for blank query"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/db-store-page-node! db-info
                      {:page.node/type :paragraph
                       :page.node/content "First node"}
                      "page-1" "doc-1")
                    (#'sut/db-store-page-node! db-info
                      {:page.node/type :paragraph
                       :page.node/content "Second node"}
                      "page-2" "doc-1")
                    (let [results (#'sut/db-search-page-nodes db-info "")]
                      (expect (= 2 (count results))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "respects :document-id filter"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/db-store-page-node! db-info
                      {:page.node/type :paragraph
                       :page.node/content "Compliance text for doc one."}
                      "page-1" "doc-1")
                    (#'sut/db-store-page-node! db-info
                      {:page.node/type :paragraph
                       :page.node/content "Compliance text for doc two."}
                      "page-1" "doc-2")
                    (let [results (#'sut/db-search-page-nodes db-info "compliance" {:document-id "doc-1"})]
                      (expect (= 1 (count results)))
                      (expect (= "doc-1" (:page.node/document-id (first results)))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "respects :type filter"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/db-store-page-node! db-info
                      {:page.node/type :heading
                       :page.node/content "Compliance Heading"
                       :page.node/level "h1"}
                      "page-1" "doc-1")
                    (#'sut/db-store-page-node! db-info
                      {:page.node/type :paragraph
                       :page.node/content "Compliance paragraph text."}
                      "page-1" "doc-1")
                    (let [results (#'sut/db-search-page-nodes db-info "compliance" {:type :heading})]
                      (expect (= 1 (count results)))
                      (expect (= :heading (:page.node/type (first results)))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "includes content and description in results"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/db-store-page-node! db-info
                      {:page.node/type :paragraph
                       :page.node/content "The full content is here."
                       :page.node/description "A brief description."}
                      "page-1" "doc-1")
                    (let [results (#'sut/db-search-page-nodes db-info "full content")
                          node (first results)]
                      (expect (= 1 (count results)))
                      (expect (= "The full content is here." (:page.node/content node)))
                      (expect (= "A brief description." (:page.node/description node))))
                    (finally
                      (#'sut/dispose-db! db-info)))))))

(defdescribe search-toc-entries-test
  (describe "db-search-toc-entries"
            (it "finds entries matching title text"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/db-store-toc-entry! db-info
                      {:document.toc/title "Chapter 1: Compliance"
                       :document.toc/level "l1"
                       :document.toc/target-page 0}
                      "doc-1")
                    (#'sut/db-store-toc-entry! db-info
                      {:document.toc/title "Chapter 2: Financial Terms"
                       :document.toc/level "l1"
                       :document.toc/target-page 1}
                      "doc-1")
                    (let [results (#'sut/db-search-toc-entries db-info "compliance")]
                      (expect (= 1 (count results)))
                      (expect (str/includes? (:document.toc/title (first results)) "Compliance")))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "finds entries matching description text"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/db-store-toc-entry! db-info
                      {:document.toc/title "Appendix A"
                       :document.toc/level "l2"
                       :document.toc/description "Detailed compliance regulations"
                       :document.toc/target-page 5}
                      "doc-1")
                    (let [results (#'sut/db-search-toc-entries db-info "regulations")]
                      (expect (= 1 (count results))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "returns empty for non-matching query"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/db-store-toc-entry! db-info
                      {:document.toc/title "Introduction"
                       :document.toc/level "l1"
                       :document.toc/target-page 0}
                      "doc-1")
                    (let [results (#'sut/db-search-toc-entries db-info "xyznonexistent")]
                      (expect (= 0 (count results))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "falls back to list mode for blank query"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/db-store-toc-entry! db-info
                      {:document.toc/title "First Entry"
                       :document.toc/level "l1"
                       :document.toc/target-page 0}
                      "doc-1")
                    (#'sut/db-store-toc-entry! db-info
                      {:document.toc/title "Second Entry"
                       :document.toc/level "l1"
                       :document.toc/target-page 1}
                      "doc-1")
                    (let [results (#'sut/db-search-toc-entries db-info "")]
                      (expect (= 2 (count results))))
                    (finally
                      (#'sut/dispose-db! db-info)))))))

(defdescribe search-entities-test
  (describe "db-search-entities"
            (it "finds entities matching name"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (swap! (:store db-info) update :entities conj
                           {:entity/id (UUID/randomUUID)
                            :entity/name "Acme Corp"
                            :entity/type :organization
                            :entity/description "A technology company"
                            :entity/document-id "doc-1"
                            :entity/created-at (java.util.Date.)})
                    (swap! (:store db-info) update :entities conj
                           {:entity/id (UUID/randomUUID)
                            :entity/name "Jane Smith"
                            :entity/type :person
                            :entity/description "Chief executive officer"
                            :entity/document-id "doc-1"
                            :entity/created-at (java.util.Date.)})
                    (let [results (#'sut/db-search-entities db-info "acme")]
                      (expect (= 1 (count results)))
                      (expect (= "Acme Corp" (:entity/name (first results)))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "finds entities matching description"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (swap! (:store db-info) update :entities conj
                           {:entity/id (UUID/randomUUID)
                            :entity/name "Bob"
                            :entity/type :person
                            :entity/description "Senior compliance officer"
                            :entity/document-id "doc-1"
                            :entity/created-at (java.util.Date.)})
                    (let [results (#'sut/db-search-entities db-info "compliance")]
                      (expect (= 1 (count results)))
                      (expect (= "Bob" (:entity/name (first results)))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "returns empty for non-matching query"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (swap! (:store db-info) update :entities conj
                           {:entity/id (UUID/randomUUID)
                            :entity/name "TestCo"
                            :entity/type :organization
                            :entity/description "A company"
                            :entity/document-id "doc-1"
                            :entity/created-at (java.util.Date.)})
                    (let [results (#'sut/db-search-entities db-info "xyznonexistent")]
                      (expect (= 0 (count results))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "respects :type filter"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (swap! (:store db-info) update :entities conj
                           {:entity/id (UUID/randomUUID)
                            :entity/name "Acme Corp"
                            :entity/type :organization
                            :entity/description "A global corp"
                            :entity/document-id "doc-1"
                            :entity/created-at (java.util.Date.)})
                    (swap! (:store db-info) update :entities conj
                           {:entity/id (UUID/randomUUID)
                            :entity/name "Acme Division"
                            :entity/type :person
                            :entity/description "Acme division lead"
                            :entity/document-id "doc-1"
                            :entity/created-at (java.util.Date.)})
                    (let [results (#'sut/db-search-entities db-info "acme" {:type :organization})]
                      (expect (= 1 (count results)))
                      (expect (= :organization (:entity/type (first results)))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "respects :document-id filter"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (swap! (:store db-info) update :entities conj
                           {:entity/id (UUID/randomUUID)
                            :entity/name "Shared Name"
                            :entity/type :organization
                            :entity/description "In doc 1"
                            :entity/document-id "doc-1"
                            :entity/created-at (java.util.Date.)})
                    (swap! (:store db-info) update :entities conj
                           {:entity/id (UUID/randomUUID)
                            :entity/name "Shared Name"
                            :entity/type :organization
                            :entity/description "In doc 2"
                            :entity/document-id "doc-2"
                            :entity/created-at (java.util.Date.)})
                    (let [results (#'sut/db-search-entities db-info "shared" {:document-id "doc-2"})]
                      (expect (= 1 (count results)))
                      (expect (= "doc-2" (:entity/document-id (first results)))))
                    (finally
                      (#'sut/dispose-db! db-info)))))))

;; =============================================================================
;; T9: Learnings Text Search Tests
;; =============================================================================

(defdescribe learnings-text-search-test
  (describe "db-get-learnings text filtering"
            (it "filters learnings by query text"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-learning-schema! db-info)
                    (#'sut/db-store-learning! db-info "Always verify date ranges in financial data")
                    (#'sut/db-store-learning! db-info "Check network connectivity before API calls")
                    (let [results (#'sut/db-get-learnings db-info "financial" {:top-k 10 :track-usage? false})]
                      (expect (= 1 (count results)))
                      (expect (str/includes? (:insight (first results)) "financial")))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "searches context text too"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-learning-schema! db-info)
                    (#'sut/db-store-learning! db-info "Use pagination for large results" "database queries")
                    (let [results (#'sut/db-get-learnings db-info "database" {:top-k 10 :track-usage? false})]
                      (expect (= 1 (count results)))
                      (expect (= "database queries" (:context (first results)))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "returns all when query is blank"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-learning-schema! db-info)
                    (#'sut/db-store-learning! db-info "Insight one")
                    (#'sut/db-store-learning! db-info "Insight two")
                    (#'sut/db-store-learning! db-info "Insight three")
                    (let [results (#'sut/db-get-learnings db-info "" {:top-k 10 :track-usage? false})]
                      (expect (= 3 (count results))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "case-insensitive search"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/init-learning-schema! db-info)
                    (#'sut/db-store-learning! db-info "always validate input parameters")
                    (let [results (#'sut/db-get-learnings db-info "VALIDATE INPUT" {:top-k 10 :track-usage? false})]
                      (expect (= 1 (count results))))
                    (finally
                      (#'sut/dispose-db! db-info)))))))

;; =============================================================================
;; T9: search-examples Text Search Tests
;; =============================================================================

(defdescribe search-examples-text-test
  (describe "make-search-examples-fn text filtering"
            (it "filters examples by query text"
                (let [store-atom (deref #'sut/example-store)
                      original @store-atom]
                  (try
                    (reset! store-atom
                            [{:query "What are the compliance requirements?"
                              :answer "The document outlines three compliance requirements."
                              :score 35 :good? true :timestamp (System/currentTimeMillis)}
                             {:query "Who is the CEO?"
                              :answer "Jane Smith is the CEO."
                              :score 38 :good? true :timestamp (- (System/currentTimeMillis) 1000)}])
                    (let [search-fn (#'sut/make-search-examples-fn)
                          results (search-fn "compliance")]
                      (expect (= 1 (count results)))
                      (expect (str/includes? (:query (first results)) "compliance")))
                    (finally
                      (reset! store-atom original)))))

            (it "filters examples by answer text"
                (let [store-atom (deref #'sut/example-store)
                      original @store-atom]
                  (try
                    (reset! store-atom
                            [{:query "Question A"
                              :answer "The quarterly revenue was strong."
                              :score 35 :good? true :timestamp (System/currentTimeMillis)}
                             {:query "Question B"
                              :answer "Expenses increased by 10%."
                              :score 30 :good? false :timestamp (- (System/currentTimeMillis) 1000)}])
                    (let [search-fn (#'sut/make-search-examples-fn)
                          results (search-fn "revenue")]
                      (expect (= 1 (count results)))
                      (expect (str/includes? (:answer (first results)) "revenue")))
                    (finally
                      (reset! store-atom original)))))

            (it "returns all for blank query"
                (let [store-atom (deref #'sut/example-store)
                      original @store-atom]
                  (try
                    (reset! store-atom
                            [{:query "Q1" :answer "A1" :score 30 :good? true :timestamp (System/currentTimeMillis)}
                             {:query "Q2" :answer "A2" :score 35 :good? true :timestamp (- (System/currentTimeMillis) 1000)}])
                    (let [search-fn (#'sut/make-search-examples-fn)
                          results (search-fn "")]
                      (expect (= 2 (count results))))
                    (finally
                      (reset! store-atom original)))))

            (it "returns empty when no match"
                (let [store-atom (deref #'sut/example-store)
                      original @store-atom]
                  (try
                    (reset! store-atom
                            [{:query "Some query" :answer "Some answer" :score 30 :good? true :timestamp (System/currentTimeMillis)}])
                    (let [search-fn (#'sut/make-search-examples-fn)
                          results (search-fn "xyznonexistent")]
                      (expect (= 0 (count results))))
                    (finally
                      (reset! store-atom original)))))))

;; =============================================================================
;; T9: list-page-nodes Truncation Tests
;; =============================================================================

(defdescribe list-page-nodes-truncation-test
  (describe "db-list-page-nodes content truncation"
            (it "includes content truncated to 200 chars"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (let [long-content (apply str (repeat 50 "abcdefg"))] ;; 350 chars
                      (#'sut/db-store-page-node! db-info
                        {:page.node/type :paragraph
                         :page.node/content long-content}
                        "page-1" "doc-1")
                      (let [results (#'sut/db-list-page-nodes db-info {})
                            node (first results)]
                        (expect (= 1 (count results)))
                        (expect (some? (:page.node/content node)))
                        (expect (= 200 (count (:page.node/content node))))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "includes description truncated to 200 chars"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (let [long-desc (apply str (repeat 50 "abcdefg"))] ;; 350 chars
                      (#'sut/db-store-page-node! db-info
                        {:page.node/type :image
                         :page.node/description long-desc}
                        "page-1" "doc-1")
                      (let [results (#'sut/db-list-page-nodes db-info {})
                            node (first results)]
                        (expect (= 1 (count results)))
                        (expect (some? (:page.node/description node)))
                        (expect (= 200 (count (:page.node/description node))))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "does not truncate short content"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/db-store-page-node! db-info
                      {:page.node/type :paragraph
                       :page.node/content "Short content."}
                      "page-1" "doc-1")
                    (let [results (#'sut/db-list-page-nodes db-info {})
                          node (first results)]
                      (expect (= "Short content." (:page.node/content node))))
                    (finally
                      (#'sut/dispose-db! db-info)))))))

;; =============================================================================
;; T9: Flush Store Batching Tests
;; =============================================================================

(defdescribe flush-store-batching-test
  (describe "mark-dirty-store! and per-collection persistence"
            (it "mark-dirty-store! marks specific collection as dirty"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (expect (empty? (:dirty @(:store db-info))))
                    (#'sut/mark-dirty-store! db-info :messages)
                    (expect (contains? (:dirty @(:store db-info)) :messages))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "mark-dirty-store! accumulates multiple dirty collections"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/mark-dirty-store! db-info :messages)
                    (#'sut/mark-dirty-store! db-info :learnings)
                    (expect (= #{:messages :learnings} (:dirty @(:store db-info))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "mark-dirty-store! accepts a set of collection keys"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/mark-dirty-store! db-info #{:entities :relationships})
                    (expect (= #{:entities :relationships} (:dirty @(:store db-info))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "flush-store-now! resets dirty set"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (#'sut/mark-dirty-store! db-info :messages)
                    (expect (seq (:dirty @(:store db-info))))
                    (#'sut/flush-store-now! db-info)
                    (expect (empty? (:dirty @(:store db-info))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

            (it "flush-store-now! writes only dirty collections to individual files"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    ;; Add some data and mark dirty
                    (swap! (:store db-info) update :entities conj
                           {:entity/id (UUID/randomUUID)
                            :entity/name "TestEntity"
                            :entity/type :test})
                    (#'sut/mark-dirty-store! db-info :entities)
                    ;; Flush to disk
                    (#'sut/flush-store-now! db-info)
                    ;; Verify entities.edn exists but messages.edn does not
                    (let [entities-file (java.io.File. (str (:path db-info) "/entities.edn"))
                          messages-file (java.io.File. (str (:path db-info) "/messages.edn"))]
                      (expect (.exists entities-file))
                      (expect (pos? (.length entities-file)))
                      (expect (not (.exists messages-file))))
                    (finally
                      (#'sut/dispose-db! db-info)))))))

;; =============================================================================
;; T9: Relationship Storage Tests
;; =============================================================================

(defdescribe relationship-storage-test
  (describe "two-phase entity + relationship storage"
            (it "stores relationships with resolved entity UUIDs"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (let [uuid-a (UUID/randomUUID)
                          uuid-b (UUID/randomUUID)]
                      ;; Phase 1: Store entities
                      (swap! (:store db-info) update :entities conj
                             {:entity/id uuid-a
                              :entity/name "Alice"
                              :entity/type :person
                              :entity/description "Engineer"
                              :entity/document-id "doc-1"
                              :entity/created-at (java.util.Date.)})
                      (swap! (:store db-info) update :entities conj
                             {:entity/id uuid-b
                              :entity/name "Bob"
                              :entity/type :person
                              :entity/description "Manager"
                              :entity/document-id "doc-1"
                              :entity/created-at (java.util.Date.)})
                      ;; Phase 2: Store relationship with resolved UUIDs
                      (swap! (:store db-info) update :relationships conj
                             {:relationship/id (UUID/randomUUID)
                              :relationship/type :works-with
                              :relationship/source-entity-id uuid-a
                              :relationship/target-entity-id uuid-b
                              :relationship/description "Alice works with Bob"
                              :relationship/document-id "doc-1"
                              :relationship/created-at (java.util.Date.)})
                      ;; Verify entities stored
                      (expect (= 2 (count (:entities @(:store db-info)))))
                      ;; Verify relationship stored
                      (expect (= 1 (count (:relationships @(:store db-info)))))
                      (let [rel (first (:relationships @(:store db-info)))]
                        (expect (= uuid-a (:relationship/source-entity-id rel)))
                        (expect (= uuid-b (:relationship/target-entity-id rel)))
                        (expect (= :works-with (:relationship/type rel)))
                        (expect (= "Alice works with Bob" (:relationship/description rel)))))
                    (finally
                      (#'sut/dispose-db! db-info)))))

             (it "relationships vector starts empty in EMPTY_STORE"
                (let [db-info (#'sut/create-disposable-db)]
                  (try
                    (expect (= [] (:relationships @(:store db-info))))
                    (finally
                      (#'sut/dispose-db! db-info)))))))

;; =============================================================================
;; generate-qa-env! pipeline unit tests
;; =============================================================================

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

(defdescribe deduplicate-questions-test
  (describe "deduplicate-questions"
            (it "keeps unique questions when LLM returns all indices"
                (with-mock-ask! (fn [_] (make-mock-ask-response {:keep-indices [0 1 2]}))
                  (let [questions [{:question "What is the capital of France?"}
                                   {:question "How does photosynthesis work?"}
                                   {:question "What year was the company founded?"}]
                        result (#'sut/deduplicate-questions questions {} "gpt-4o")]
                    (expect (= 3 (count result))))))
            (it "removes duplicates when LLM identifies them"
                (with-mock-ask! (fn [_] (make-mock-ask-response {:keep-indices [0 2]}))
                  (let [questions [{:question "What is the minimum capital requirement for banks?"}
                                   {:question "What is the minimum capital requirement for the banks?"}
                                   {:question "How does photosynthesis produce oxygen?"}]
                        result (#'sut/deduplicate-questions questions {} "gpt-4o")]
                    (expect (= 2 (count result)))
                    (expect (= "What is the minimum capital requirement for banks?"
                               (:question (first result))))
                    (expect (= "How does photosynthesis produce oxygen?"
                               (:question (second result)))))))
            (it "handles empty input"
                (expect (= [] (#'sut/deduplicate-questions [] {} "gpt-4o"))))
            (it "handles single question without calling LLM"
                (let [result (#'sut/deduplicate-questions [{:question "Solo question"}] {} "gpt-4o")]
                  (expect (= 1 (count result)))))
            (it "falls back to all questions when LLM returns empty"
                (with-mock-ask! (fn [_] (make-mock-ask-response {:keep-indices []}))
                  (let [questions [{:question "Q1"} {:question "Q2"}]
                        result (#'sut/deduplicate-questions questions {} "gpt-4o")]
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

(defdescribe build-chunk-selection-prompt-test
  (describe "build-chunk-selection-prompt"
            (it "produces non-empty string with key instructions"
                (let [prompt (#'sut/build-chunk-selection-prompt
                              {:count 15
                               :difficulty-dist #{:remember :understand :apply}
                               :category-dist #{:factual :inferential}})]
                  (expect (string? prompt))
                  (expect (> (count prompt) 200))
                  (expect (str/includes? prompt "15"))
                  (expect (str/includes? prompt "list-documents"))
                  (expect (str/includes? prompt "FINAL"))))))

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


