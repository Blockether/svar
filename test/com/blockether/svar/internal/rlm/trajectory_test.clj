(ns com.blockether.svar.internal.rlm.trajectory-test
  "Unit tests for trajectory collection, filtering, and JSONL export."
  (:require
   [babashka.fs :as fs]
   [charred.api :as json]
   [clojure.string :as str]
   [com.blockether.svar.internal.rlm.db :as rlm-db]
   [com.blockether.svar.internal.rlm.trajectory :as sut]
   [lazytest.core :refer [defdescribe describe expect it throws?]]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- temp-db []
  (#'rlm-db/create-rlm-conn :temp))

(defn- dispose-db! [db-info]
  (#'rlm-db/dispose-rlm-conn! db-info))

(defn- seed-query!
  "Inserts a conversation + query + one iteration into db-info.
   Returns query-ref."
  [db-info {:keys [status iterations answer text eval-score iteration-code]
            :or {status :success iterations 3 answer "42" text "what is 6*7?"
                 iteration-code "(* 6 7)"}}]
  (let [conv-ref (rlm-db/store-conversation! db-info
                   {:env-id (str (random-uuid)) :system-prompt "You are helpful." :model "test"})
        query-ref (rlm-db/store-query! db-info
                    (cond-> {:conversation-ref conv-ref
                             :text text
                             :status status
                             :iterations iterations
                             :answer answer
                             :duration-ms 100}
                      eval-score (assoc :eval-score eval-score)))]
    ;; Insert as many iterations as requested so score-query works
    (dotimes [_ (max 1 iterations)]
      (rlm-db/store-iteration! db-info
        {:query-ref query-ref
         :executions [{:code iteration-code :result answer}]
         :vars []
         :thinking "thinking..."
         :answer answer
         :duration-ms 50}))
    query-ref))

;; =============================================================================
;; list-queries
;; =============================================================================

(defdescribe list-queries-test
  (describe "empty database"
    (it "returns empty seq when no queries stored"
      (let [db-info (temp-db)]
        (try
          (let [result (sut/list-queries db-info)]
            (expect (empty? result)))
          (finally (dispose-db! db-info))))))

  (describe "stored queries"
    (it "returns stored query with expected shape"
      (let [db-info (temp-db)]
        (try
          (seed-query! db-info {:text "test query" :status :success :iterations 3})
          (let [results (sut/list-queries db-info)]
            (expect (= 1 (count results)))
            (let [q (first results)]
              (expect (= :query (:entity/type q)))
              (expect (= :success (:query/status q)))
              (expect (some? (:entity/id q)))
              (expect (some? (:entity/created-at q)))))
          (finally (dispose-db! db-info)))))

    (it "returns multiple stored queries"
      (let [db-info (temp-db)]
        (try
          (seed-query! db-info {:text "q1" :status :success})
          (seed-query! db-info {:text "q2" :status :error})
          (let [results (sut/list-queries db-info)]
            (expect (= 2 (count results))))
          (finally (dispose-db! db-info)))))

    (it "returns nil when conn is nil"
      (let [result (sut/list-queries {:conn nil})]
        (expect (nil? result)))))

  (describe "limit option"
    (it "respects :limit option"
      (let [db-info (temp-db)]
        (try
          (seed-query! db-info {:text "q1"})
          (seed-query! db-info {:text "q2"})
          (seed-query! db-info {:text "q3"})
          (let [results (sut/list-queries db-info {:limit 2})]
            (expect (= 2 (count results))))
          (finally (dispose-db! db-info)))))

    (it "returns all when limit exceeds count"
      (let [db-info (temp-db)]
        (try
          (seed-query! db-info {:text "q1"})
          (seed-query! db-info {:text "q2"})
          (let [results (sut/list-queries db-info {:limit 100})]
            (expect (= 2 (count results))))
          (finally (dispose-db! db-info)))))))

;; =============================================================================
;; Filtering by status
;; =============================================================================

(defdescribe list-queries-status-filter-test
  (it "returns only :success queries when status filter applied"
    (let [db-info (temp-db)]
      (try
        (seed-query! db-info {:text "ok" :status :success})
        (seed-query! db-info {:text "bad" :status :error})
        (let [results (sut/list-queries db-info {:status :success})]
          (expect (= 1 (count results)))
          (expect (= :success (:query/status (first results)))))
        (finally (dispose-db! db-info)))))

  (it "returns only :error queries when status :error applied"
    (let [db-info (temp-db)]
      (try
        (seed-query! db-info {:text "ok" :status :success})
        (seed-query! db-info {:text "bad" :status :error})
        (let [results (sut/list-queries db-info {:status :error})]
          (expect (= 1 (count results)))
          (expect (= :error (:query/status (first results)))))
        (finally (dispose-db! db-info)))))

  (it "returns only :max-iter queries when status :max-iter applied"
    (let [db-info (temp-db)]
      (try
        (seed-query! db-info {:text "ok" :status :success})
        (seed-query! db-info {:text "maxed" :status :max-iter})
        (let [results (sut/list-queries db-info {:status :max-iter})]
          (expect (= 1 (count results)))
          (expect (= :max-iter (:query/status (first results)))))
        (finally (dispose-db! db-info)))))

  (it "returns all queries when no status filter"
    (let [db-info (temp-db)]
      (try
        (seed-query! db-info {:text "ok" :status :success})
        (seed-query! db-info {:text "bad" :status :error})
        (seed-query! db-info {:text "maxed" :status :max-iter})
        (let [results (sut/list-queries db-info)]
          (expect (= 3 (count results))))
        (finally (dispose-db! db-info))))))

;; =============================================================================
;; Filtering by min-iterations
;; =============================================================================

(defdescribe list-queries-min-iterations-filter-test
  (it "excludes queries below min-iterations threshold"
    (let [db-info (temp-db)]
      (try
        (seed-query! db-info {:text "few" :iterations 1})
        (seed-query! db-info {:text "many" :iterations 5})
        (let [results (sut/list-queries db-info {:min-iterations 3})]
          (expect (= 1 (count results)))
          (expect (= "many" (:query/text (first results)))))
        (finally (dispose-db! db-info)))))

  (it "includes queries meeting min-iterations exactly"
    (let [db-info (temp-db)]
      (try
        (seed-query! db-info {:text "exact" :iterations 3})
        (let [results (sut/list-queries db-info {:min-iterations 3})]
          (expect (= 1 (count results))))
        (finally (dispose-db! db-info))))))

;; =============================================================================
;; filter-queries (confidence / eval-score)
;; =============================================================================

(defdescribe filter-queries-eval-score-test
  (it "excludes queries with eval-score below min-eval-score"
    (let [db-info (temp-db)]
      (try
        ;; Need enough iterations to pass hard filters (min-iterations 2, max-iterations*ratio check)
        (seed-query! db-info {:text "low-score" :status :success :iterations 3
                              :eval-score 0.4 :iteration-code "(def x 1)"})
        (seed-query! db-info {:text "high-score" :status :success :iterations 3
                              :eval-score 0.9 :iteration-code "(def x 1)"})
        (let [results (sut/filter-queries db-info {:min-eval-score 0.7 :min-score -100
                                                   :min-iterations 2 :max-iterations 50})]
          (expect (every? #(>= (or (:query/eval-score %) 0.0) 0.7) results)))
        (finally (dispose-db! db-info)))))

  (it "includes queries without eval-score when min-eval-score set"
    (let [db-info (temp-db)]
      (try
        (seed-query! db-info {:text "no-score" :status :success :iterations 3
                              :iteration-code "(def x 1)"})
        (let [results (sut/filter-queries db-info {:min-eval-score 0.6 :min-score -100
                                                   :min-iterations 2 :max-iterations 50})]
          (expect (= 1 (count results))))
        (finally (dispose-db! db-info)))))

  (it "returns empty when no queries pass all hard filters"
    (let [db-info (temp-db)]
      (try
        (seed-query! db-info {:text "error-q" :status :error :iterations 3})
        (let [results (sut/filter-queries db-info {:min-iterations 2 :max-iterations 50
                                                   :min-score -100})]
          (expect (empty? results)))
        (finally (dispose-db! db-info))))))

;; =============================================================================
;; export-trajectories! — JSONL output
;; =============================================================================

(defdescribe export-trajectories-jsonl-test
  (describe "throws when no connection"
    (it "throws ex-info with :trajectory/no-conn type"
      (let [output-dir (str (fs/create-temp-dir {:prefix "traj-test-noconn-"}))]
        (try
          (expect (throws? clojure.lang.ExceptionInfo
                    #(sut/export-trajectories! {:conn nil} output-dir)))
          (finally (fs/delete-tree output-dir))))))

  (describe "throws when no exportable queries"
    (it "throws ex-info with :trajectory/empty type when DB is empty"
      (let [db-info (temp-db)
            output-dir (str (fs/create-temp-dir {:prefix "traj-test-empty-"}))]
        (try
          (expect (throws? clojure.lang.ExceptionInfo
                    #(sut/export-trajectories! db-info output-dir
                       {:filter-opts {:min-iterations 2 :max-iterations 50 :min-score -100}})))
          (finally
            (dispose-db! db-info)
            (fs/delete-tree output-dir))))))

  (describe "creates output directory and files"
    (it "creates train.jsonl, val.jsonl, and metadata.edn"
      (let [db-info (temp-db)
            output-dir (str (fs/create-temp-dir {:prefix "traj-test-output-"}))]
        (try
          ;; Seed enough high-quality queries to pass all filters
          (dotimes [i 5]
            (seed-query! db-info {:text (str "query-" i) :status :success
                                  :iterations 3 :eval-score 0.9
                                  :iteration-code "(def x 1)"}))
          (sut/export-trajectories! db-info output-dir
            {:shuffle? false
             :filter-opts {:min-iterations 2 :max-iterations 50 :min-score -100}})
          (expect (fs/exists? (fs/path output-dir "train.jsonl")))
          (expect (fs/exists? (fs/path output-dir "val.jsonl")))
          (expect (fs/exists? (fs/path output-dir "metadata.edn")))
          (finally
            (dispose-db! db-info)
            (fs/delete-tree output-dir))))))

  (describe "valid JSONL output"
    (it "each line in train.jsonl parses as independent JSON object"
      (let [db-info (temp-db)
            output-dir (str (fs/create-temp-dir {:prefix "traj-test-jsonl-"}))]
        (try
          (dotimes [i 5]
            (seed-query! db-info {:text (str "query-" i) :status :success
                                  :iterations 3 :eval-score 0.9
                                  :iteration-code "(def x 1)"}))
          (sut/export-trajectories! db-info output-dir
            {:shuffle? false
             :filter-opts {:min-iterations 2 :max-iterations 50 :min-score -100}})
          (let [lines (->> (slurp (str output-dir "/train.jsonl"))
                        str/split-lines
                        (remove str/blank?))]
            (expect (pos? (count lines)))
            (doseq [line lines]
              (let [parsed (json/read-json line)]
                (expect (map? parsed))
                (expect (contains? parsed "messages")))))
          (finally
            (dispose-db! db-info)
            (fs/delete-tree output-dir)))))

    (it "each line in val.jsonl parses as independent JSON object"
      (let [db-info (temp-db)
            output-dir (str (fs/create-temp-dir {:prefix "traj-test-val-"}))]
        (try
          (dotimes [i 10]
            (seed-query! db-info {:text (str "query-" i) :status :success
                                  :iterations 3 :eval-score 0.9
                                  :iteration-code "(def x 1)"}))
          (sut/export-trajectories! db-info output-dir
            {:shuffle? false :val-split 0.2
             :filter-opts {:min-iterations 2 :max-iterations 50 :min-score -100}})
          (let [lines (->> (slurp (str output-dir "/val.jsonl"))
                        str/split-lines
                        (remove str/blank?))]
            (expect (pos? (count lines)))
            (doseq [line lines]
              (let [parsed (json/read-json line)]
                (expect (map? parsed))
                (expect (contains? parsed "messages")))))
          (finally
            (dispose-db! db-info)
            (fs/delete-tree output-dir)))))

    (it "messages array contains system and user roles"
      (let [db-info (temp-db)
            output-dir (str (fs/create-temp-dir {:prefix "traj-test-roles-"}))]
        (try
          (dotimes [i 3]
            (seed-query! db-info {:text (str "my-query-" i) :status :success
                                  :iterations 3 :eval-score 0.9
                                  :iteration-code "(def x 1)"}))
          (sut/export-trajectories! db-info output-dir
            {:shuffle? false :val-split 0.0
             :filter-opts {:min-iterations 2 :max-iterations 50 :min-score -100}})
          (let [lines (->> (slurp (str output-dir "/train.jsonl"))
                        str/split-lines
                        (remove str/blank?))]
            (doseq [line lines]
              (let [messages (get (json/read-json line) "messages")
                    roles (set (map #(get % "role") messages))]
                (expect (contains? roles "system"))
                (expect (contains? roles "user"))
                (expect (contains? roles "assistant")))))
          (finally
            (dispose-db! db-info)
            (fs/delete-tree output-dir))))))

  (describe "metadata correctness"
    (it "metadata.edn contains expected keys and counts"
      (let [db-info (temp-db)
            output-dir (str (fs/create-temp-dir {:prefix "traj-test-meta-"}))]
        (try
          (dotimes [i 5]
            (seed-query! db-info {:text (str "q-" i) :status :success
                                  :iterations 3 :eval-score 0.9
                                  :iteration-code "(def x 1)"}))
          (let [meta (sut/export-trajectories! db-info output-dir
                       {:shuffle? false
                        :filter-opts {:min-iterations 2 :max-iterations 50 :min-score -100}})]
            (expect (contains? meta :total-queries))
            (expect (contains? meta :exported))
            (expect (contains? meta :train-count))
            (expect (contains? meta :val-count))
            (expect (contains? meta :avg-score))
            (expect (= (:exported meta)
                      (+ (:train-count meta) (:val-count meta)))))
          (finally
            (dispose-db! db-info)
            (fs/delete-tree output-dir))))))

  (describe "filters applied before export"
    (it "only :success queries are exported — :error queries excluded"
      (let [db-info (temp-db)
            output-dir (str (fs/create-temp-dir {:prefix "traj-test-filter-"}))]
        (try
          (dotimes [i 4]
            (seed-query! db-info {:text (str "ok-" i) :status :success
                                  :iterations 3 :eval-score 0.9
                                  :iteration-code "(def x 1)"}))
          ;; These should be filtered out
          (dotimes [i 3]
            (seed-query! db-info {:text (str "bad-" i) :status :error
                                  :iterations 3}))
          (let [meta (sut/export-trajectories! db-info output-dir
                       {:shuffle? false
                        :filter-opts {:min-iterations 2 :max-iterations 50 :min-score -100}})]
            ;; total-queries reflects only :success queries that pass hard filters
            (expect (<= (:total-queries meta) 4)))
          (finally
            (dispose-db! db-info)
            (fs/delete-tree output-dir))))))

  (describe "trajectory shape"
    (it "exported records include messages with assistant content containing code"
      (let [db-info (temp-db)
            output-dir (str (fs/create-temp-dir {:prefix "traj-test-shape-"}))]
        (try
          (dotimes [i 3]
            (seed-query! db-info {:text (str "q-" i) :status :success
                                  :iterations 3 :eval-score 0.9
                                  :iteration-code "(def result (* 6 7))"}))
          (sut/export-trajectories! db-info output-dir
            {:shuffle? false :val-split 0.0
             :filter-opts {:min-iterations 2 :max-iterations 50 :min-score -100}})
          (let [lines (->> (slurp (str output-dir "/train.jsonl"))
                        str/split-lines
                        (remove str/blank?))]
            (doseq [line lines]
              (let [messages (get (json/read-json line) "messages")
                    assistant-msgs (filter #(= "assistant" (get % "role")) messages)]
                (expect (pos? (count assistant-msgs)))
                ;; Each assistant message content is a JSON string with :code key
                (doseq [msg assistant-msgs]
                  (let [content (json/read-json (get msg "content"))]
                    (expect (map? content)))))))
          (finally
            (dispose-db! db-info)
            (fs/delete-tree output-dir)))))))

;; =============================================================================
;; reconstruct-conversation
;; =============================================================================

(defdescribe reconstruct-conversation-test
  (it "returns empty vec when query has no iterations"
    (let [db-info (temp-db)]
      (try
        (let [conv-ref (rlm-db/store-conversation! db-info
                         {:env-id (str (random-uuid)) :system-prompt "" :model "test"})
              query-ref (rlm-db/store-query! db-info
                          {:conversation-ref conv-ref :text "q" :status :success :iterations 0})]
          (expect (vector? (sut/reconstruct-conversation db-info query-ref)))
          (expect (empty? (sut/reconstruct-conversation db-info query-ref))))
        (finally (dispose-db! db-info)))))

  (it "returns iteration maps with :code, :results, :duration-ms keys"
    (let [db-info (temp-db)]
      (try
        (let [conv-ref (rlm-db/store-conversation! db-info
                         {:env-id (str (random-uuid)) :system-prompt "" :model "test"})
              query-ref (rlm-db/store-query! db-info
                          {:conversation-ref conv-ref :text "q" :status :success :iterations 2})]
          (rlm-db/store-iteration! db-info
            {:query-ref query-ref
             :executions [{:code "(+ 1 2)" :result 3}]
             :vars [] :thinking "hmm" :answer "3" :duration-ms 77})
          (let [iters (sut/reconstruct-conversation db-info query-ref)]
            (expect (= 1 (count iters)))
            (let [it (first iters)]
              (expect (contains? it :code))
              (expect (contains? it :results))
              (expect (contains? it :duration-ms)))))
        (finally (dispose-db! db-info)))))

  (it "attaches :answer when iteration has an answer"
    (let [db-info (temp-db)]
      (try
        (let [conv-ref (rlm-db/store-conversation! db-info
                         {:env-id (str (random-uuid)) :system-prompt "" :model "test"})
              query-ref (rlm-db/store-query! db-info
                          {:conversation-ref conv-ref :text "q" :status :success :iterations 1})]
          (rlm-db/store-iteration! db-info
            {:query-ref query-ref
             :executions [] :vars [] :thinking "" :answer "final-answer" :duration-ms 0})
          (let [iters (sut/reconstruct-conversation db-info query-ref)]
            (expect (= "final-answer" (:answer (first iters))))))
        (finally (dispose-db! db-info)))))

  (it "attaches :thinking when iteration has thinking content"
    (let [db-info (temp-db)]
      (try
        (let [conv-ref (rlm-db/store-conversation! db-info
                         {:env-id (str (random-uuid)) :system-prompt "" :model "test"})
              query-ref (rlm-db/store-query! db-info
                          {:conversation-ref conv-ref :text "q" :status :success :iterations 1})]
          (rlm-db/store-iteration! db-info
            {:query-ref query-ref
             :executions [] :vars [] :thinking "deep thoughts" :answer nil :duration-ms 0})
          (let [iters (sut/reconstruct-conversation db-info query-ref)]
            (expect (= "deep thoughts" (:thinking (first iters))))))
        (finally (dispose-db! db-info))))))
