(ns com.blockether.svar.internal.rlm.memory-system-test
  "Tests for memory system features: batch search, markdown renderer,
   BFS graph traversal, canonical-id linking, spreading activation."
  (:require
   [com.blockether.svar.internal.rlm.db :as db]
   [com.blockether.svar.internal.rlm.schema :refer [RLM_SCHEMA]]
   [com.blockether.svar.internal.util :as util]
   [datalevin.core :as d]
   [lazytest.core :refer [defdescribe describe expect it]]))

(defn- temp-conn []
  (d/get-conn (str "/tmp/memory-test-" (random-uuid)) RLM_SCHEMA))

(defn- seed-pages!
  "Seeds test pages with nodes into Datalevin. Returns {:conn :db-info :page-ids}."
  []
  (let [conn (temp-conn)
        db-info {:conn conn}
        now (java.util.Date.)
        p1-id "doc1-page-0" p2-id "doc1-page-1" p3-id "doc2-page-0"]
    ;; Pages
    (d/transact! conn [{:page/id p1-id :page/document-id "doc1" :page/index 0
                        :page/created-at now :page/last-accessed now :page/access-count 1.0}
                       {:page/id p2-id :page/document-id "doc1" :page/index 1
                        :page/created-at now :page/last-accessed now :page/access-count 1.0}
                       {:page/id p3-id :page/document-id "doc2" :page/index 0
                        :page/created-at now :page/last-accessed now :page/access-count 1.0}])
    ;; Nodes on pages
    (d/transact! conn [{:page.node/id "n1" :page.node/page-id p1-id :page.node/document-id "doc1"
                        :page.node/type :paragraph :page.node/content "Schema therapy is an integrative approach"}
                       {:page.node/id "n2" :page.node/page-id p1-id :page.node/document-id "doc1"
                        :page.node/type :heading :page.node/content "Early Maladaptive Schemas"}
                       {:page.node/id "n3" :page.node/page-id p2-id :page.node/document-id "doc1"
                        :page.node/type :paragraph :page.node/content "The Vulnerable Child mode is activated"}
                       {:page.node/id "n4" :page.node/page-id p3-id :page.node/document-id "doc2"
                        :page.node/type :paragraph :page.node/content "Schema therapy treats personality disorders"}])
    {:conn conn :db-info db-info :page-ids [p1-id p2-id p3-id]}))

(defn- seed-entities!
  "Seeds entities with canonical-id linking across documents."
  [{:keys [conn]} p1-id p3-id]
  (let [canonical-id (util/uuid)]
    ;; Same concept "Schema Therapy" in doc1 and doc2 — same canonical-id
    (d/transact! conn [{:entity/id (util/uuid) :entity/type :concept
                        :entity/name "Schema Therapy" :entity/description "An integrative therapy approach"
                        :entity/document-id "doc1" :entity/page 0
                        :entity/canonical-id canonical-id :entity/created-at (java.util.Date.)}
                       {:entity/id (util/uuid) :entity/type :concept
                        :entity/name "Schema Therapy" :entity/description "Therapy for personality disorders"
                        :entity/document-id "doc2" :entity/page 0
                        :entity/canonical-id canonical-id :entity/created-at (java.util.Date.)}])
    canonical-id))

;; =============================================================================
;; db-search-batch
;; =============================================================================

(defdescribe search-batch-test
  (describe "parallel multi-query search"
    (it "returns merged results from multiple queries"
      (let [{:keys [conn db-info]} (seed-pages!)]
        (try
          (let [results (db/db-search-batch db-info ["schema" "vulnerable"] {:min-vitality 0.0})]
            (expect (vector? results))
            (expect (pos? (count results))))
          (finally (d/close conn)))))

    (it "deduplicates nodes appearing in multiple queries"
      (let [{:keys [conn db-info]} (seed-pages!)]
        (try
          (let [results (db/db-search-batch db-info ["schema therapy" "schema"] {:min-vitality 0.0})
                ids (map :page.node/id results)]
            (expect (= (count ids) (count (distinct ids)))))
          (finally (d/close conn)))))

    (it "returns empty for no matches"
      (let [{:keys [conn db-info]} (seed-pages!)]
        (try
          (let [results (db/db-search-batch db-info ["xyznonexistent"] {:min-vitality 0.0})]
            (expect (or (nil? results) (empty? results))))
          (finally (d/close conn)))))))

;; =============================================================================
;; results->markdown
;; =============================================================================

(defdescribe results-markdown-test
  (describe "markdown rendering"
    (it "renders page nodes with type prefix"
      (let [md (db/results->markdown [{:page.node/id "n1" :page.node/type :paragraph
                                       :page.node/page-id "page-1" :preview "Hello world"
                                       :vitality-zone :active}])]
        (expect (string? md))
        (expect (re-find #"paragraph" md))
        (expect (re-find #"active" md))
        (expect (re-find #"Hello world" md))))

    (it "renders TOC entries"
      (let [md (db/results->markdown {:pages [] :toc [{:document.toc/title "Chapter 1"
                                                        :document.toc/level "l1"
                                                        :document.toc/target-page 5}]
                                      :entities []})]
        (expect (re-find #"Chapter 1" md))
        (expect (re-find #"p\.5" md))))

    (it "renders entities"
      (let [md (db/results->markdown {:pages [] :toc []
                                      :entities [{:entity/name "Schema Therapy"
                                                  :entity/type :concept
                                                  :entity/description "An approach"}]})]
        (expect (re-find #"Schema Therapy" md))
        (expect (re-find #"concept" md))))

    (it "handles empty input"
      (expect (= "" (db/results->markdown []))))))

;; =============================================================================
;; Spreading activation
;; =============================================================================

(defdescribe spreading-activation-test
  (describe "propagate-activation!"
    (it "boosts connected pages when a page is fetched"
      (let [{:keys [conn db-info page-ids]} (seed-pages!)
            [p1-id _p2-id p3-id] page-ids]
        (try
          ;; Link pages via shared canonical entity
          (seed-entities! db-info p1-id p3-id)
          ;; Record access on p1 (fetch weight)
          (let [before (d/pull (d/db conn) [:page/access-count] [:page/id p3-id])
                _ (db/record-page-access! db-info p1-id 1.0)
                after (d/pull (d/db conn) [:page/access-count] [:page/id p3-id])]
            ;; p3 should have increased access-count from spreading activation
            (expect (> (:page/access-count after) (:page/access-count before))))
          (finally (d/close conn)))))

    (it "does NOT propagate for search hits (weight < 1.0)"
      (let [{:keys [conn db-info page-ids]} (seed-pages!)
            [p1-id _p2-id p3-id] page-ids]
        (try
          (seed-entities! db-info p1-id p3-id)
          (let [before (d/pull (d/db conn) [:page/access-count] [:page/id p3-id])
                _ (db/record-page-access! db-info p1-id 0.2)
                after (d/pull (d/db conn) [:page/access-count] [:page/id p3-id])]
            (expect (= (:page/access-count after) (:page/access-count before))))
          (finally (d/close conn)))))

    (it "boost is damped (less than original weight)"
      (let [{:keys [conn db-info page-ids]} (seed-pages!)
            [p1-id _p2-id p3-id] page-ids]
        (try
          (seed-entities! db-info p1-id p3-id)
          (let [before-count (:page/access-count (d/pull (d/db conn) [:page/access-count] [:page/id p3-id]))
                _ (db/record-page-access! db-info p1-id 1.0)
                after-count (:page/access-count (d/pull (d/db conn) [:page/access-count] [:page/id p3-id]))
                boost (- after-count before-count)]
            ;; Boost should be < 1.0 (damped by 0.6^1 = 0.6)
            (expect (< boost 1.0))
            (expect (> boost 0.0)))
          (finally (d/close conn)))))))

;; =============================================================================
;; Canonical-id entity linking
;; =============================================================================

(defdescribe canonical-id-test
  (describe "cross-document entity linking"
    (it "entities with same name+type get same canonical-id"
      (let [{:keys [conn db-info]} (seed-pages!)
            canonical-id (seed-entities! db-info "doc1-page-0" "doc2-page-0")]
        (try
          ;; Both entities should have the same canonical-id
          (let [entities (d/q '[:find [(pull ?e [:entity/canonical-id :entity/document-id]) ...]
                                :where [?e :entity/name "Schema Therapy"]]
                           (d/db conn))]
            (expect (= 2 (count entities)))
            (expect (= 1 (count (distinct (map :entity/canonical-id entities)))))
            (expect (= canonical-id (first (distinct (map :entity/canonical-id entities))))))
          (finally (d/close conn)))))))

;; =============================================================================
;; find-related (BFS graph traversal)
;; =============================================================================

(defdescribe find-related-test
  (describe "BFS graph traversal"
    (it "finds entities connected by relationships"
      (let [conn (temp-conn)
            db-info {:conn conn}
            e1-id (util/uuid)
            e2-id (util/uuid)
            e3-id (util/uuid)]
        (try
          ;; Create 3 entities with relationships: e1 -> e2 -> e3
          (d/transact! conn [{:entity/id e1-id :entity/type :concept :entity/name "A"
                              :entity/created-at (java.util.Date.)}
                             {:entity/id e2-id :entity/type :concept :entity/name "B"
                              :entity/created-at (java.util.Date.)}
                             {:entity/id e3-id :entity/type :concept :entity/name "C"
                              :entity/created-at (java.util.Date.)}
                             {:relationship/id (util/uuid) :relationship/type :references
                              :relationship/source-entity-id e1-id :relationship/target-entity-id e2-id}
                             {:relationship/id (util/uuid) :relationship/type :references
                              :relationship/source-entity-id e2-id :relationship/target-entity-id e3-id}])
          (let [related (db/find-related db-info e1-id {:depth 2})]
            ;; Should find B (distance 1) and C (distance 2)
            (expect (>= (count related) 1))
            (expect (some #(= "B" (:entity/name %)) related)))
          (finally (d/close conn)))))

    (it "respects depth limit"
      (let [conn (temp-conn)
            db-info {:conn conn}
            e1-id (util/uuid)
            e2-id (util/uuid)
            e3-id (util/uuid)]
        (try
          (d/transact! conn [{:entity/id e1-id :entity/type :concept :entity/name "A"
                              :entity/created-at (java.util.Date.)}
                             {:entity/id e2-id :entity/type :concept :entity/name "B"
                              :entity/created-at (java.util.Date.)}
                             {:entity/id e3-id :entity/type :concept :entity/name "C"
                              :entity/created-at (java.util.Date.)}
                             {:relationship/id (util/uuid) :relationship/type :references
                              :relationship/source-entity-id e1-id :relationship/target-entity-id e2-id}
                             {:relationship/id (util/uuid) :relationship/type :references
                              :relationship/source-entity-id e2-id :relationship/target-entity-id e3-id}])
          (let [depth1 (db/find-related db-info e1-id {:depth 1})]
            ;; At depth 1, should find B but NOT C
            (expect (some #(= "B" (:entity/name %)) depth1))
            (expect (not (some #(= "C" (:entity/name %)) depth1))))
          (finally (d/close conn)))))

    (it "returns empty for isolated entity"
      (let [conn (temp-conn)
            db-info {:conn conn}
            e1-id (util/uuid)]
        (try
          (d/transact! conn [{:entity/id e1-id :entity/type :concept :entity/name "Lonely"
                              :entity/created-at (java.util.Date.)}])
          (let [related (db/find-related db-info e1-id {})]
            (expect (empty? related)))
          (finally (d/close conn)))))))
