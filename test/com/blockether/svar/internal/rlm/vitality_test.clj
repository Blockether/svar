(ns com.blockether.svar.internal.rlm.vitality-test
  "Tests for ACT-R vitality system: decay computation, zone classification,
   access tracking, node-type metabolic rates, and search reranking."
  (:require
   [com.blockether.svar.internal.rlm.db :as db]
   [com.blockether.svar.internal.rlm.schema :refer [RLM_SCHEMA]]
   [datalevin.core :as d]
   [lazytest.core :refer [defdescribe describe expect it throws?]]))

(defn- temp-conn
  "Creates a temporary Datalevin connection with RLM schema."
  []
  (d/get-conn (str "/tmp/vitality-test-" (random-uuid)) RLM_SCHEMA))

(defn- ms-ago
  "Returns a Date that is `ms` milliseconds in the past."
  [ms]
  (java.util.Date. (- (System/currentTimeMillis) (long ms))))

(def ^:private ONE_DAY_MS   86400000)
(def ^:private ONE_HOUR_MS  3600000)

;; =============================================================================
;; compute-page-vitality — pure function tests
;; =============================================================================

(defdescribe compute-page-vitality-test
  (describe "freshly created page"
    (it "has high vitality (active zone)"
      (let [now (java.util.Date.)
            {:keys [score zone]} (db/compute-page-vitality 0.0 now now 5 now)]
        (expect (>= score 0.3))
        (expect (not= :archived zone)))))

  (describe "heavily accessed page"
    (it "has higher vitality than unaccessed"
      (let [created (ms-ago (* 30 ONE_DAY_MS))
            now (java.util.Date.)
            unaccessed (db/compute-page-vitality 0.0 created created 5 now)
            accessed (db/compute-page-vitality 50.0 created now 5 now)]
        (expect (> (:score accessed) (:score unaccessed))))))

  (describe "old unaccessed page"
    (it "decays over time"
      (let [now (java.util.Date.)
            recent (db/compute-page-vitality 1.0 (ms-ago ONE_DAY_MS) (ms-ago ONE_DAY_MS) 5 now)
            old (db/compute-page-vitality 1.0 (ms-ago (* 365 ONE_DAY_MS)) (ms-ago (* 365 ONE_DAY_MS)) 5 now)]
        (expect (> (:score recent) (:score old))))))

  (describe "structural boost"
    (it "page with many children has higher vitality"
      (let [created (ms-ago (* 30 ONE_DAY_MS))
            now (java.util.Date.)
            few-children (db/compute-page-vitality 5.0 created (ms-ago ONE_DAY_MS) 1 now)
            many-children (db/compute-page-vitality 5.0 created (ms-ago ONE_DAY_MS) 10 now)]
        (expect (> (:score many-children) (:score few-children)))))))

;; =============================================================================
;; compute-node-vitality — type-aware metabolic rates
;; =============================================================================

(defdescribe compute-node-vitality-test
  (describe "metabolic rates"
    (it "section nodes decay slower than paragraphs"
      (let [page-score 0.5
            section (db/compute-node-vitality page-score :section)
            paragraph (db/compute-node-vitality page-score :paragraph)]
        (expect (> (:score section) (:score paragraph)))))

    (it "toc-entry has highest persistence"
      (let [page-score 0.3
            toc (db/compute-node-vitality page-score :toc-entry)
            paragraph (db/compute-node-vitality page-score :paragraph)]
        (expect (> (:score toc) (:score paragraph)))))

    (it "heading and section have same rate"
      (let [page-score 0.5
            section (db/compute-node-vitality page-score :section)
            heading (db/compute-node-vitality page-score :heading)]
        (expect (= (:score section) (:score heading)))))))

;; =============================================================================
;; vitality-zone — zone classification
;; =============================================================================

(defdescribe vitality-zone-test
  (it "classifies active (>= 0.6)"
    (expect (= :active (db/vitality-zone 0.8)))
    (expect (= :active (db/vitality-zone 0.6))))

  (it "classifies stale (>= 0.3, < 0.6)"
    (expect (= :stale (db/vitality-zone 0.5)))
    (expect (= :stale (db/vitality-zone 0.3))))

  (it "classifies fading (>= 0.1, < 0.3)"
    (expect (= :fading (db/vitality-zone 0.2)))
    (expect (= :fading (db/vitality-zone 0.1))))

  (it "classifies archived (< 0.1)"
    (expect (= :archived (db/vitality-zone 0.05)))
    (expect (= :archived (db/vitality-zone 0.0)))))

;; =============================================================================
;; record-page-access! — Datalevin access tracking
;; =============================================================================

(defdescribe record-page-access-test
  (describe "access tracking in Datalevin"
    (it "increments access-count by weight"
      (let [conn (temp-conn)
            db-info {:conn conn}
            page-id "test-page-1"]
        (try
          ;; Create a page
          (d/transact! conn [{:page/id page-id
                              :page/document-id "doc-1"
                              :page/index 0
                              :page/created-at (java.util.Date.)
                              :page/last-accessed (java.util.Date.)
                              :page/access-count 0.0}])
          ;; Record fetch access (1.0)
          (db/record-page-access! db-info page-id 1.0)
          (let [page (d/pull (d/db conn) [:page/access-count] [:page/id page-id])]
            (expect (= 1.0 (:page/access-count page))))
          ;; Record search hit (0.2)
          (db/record-page-access! db-info page-id 0.2)
          (let [page (d/pull (d/db conn) [:page/access-count] [:page/id page-id])]
            (expect (= 1.2 (:page/access-count page))))
          (finally
            (d/close conn)))))

    (it "updates last-accessed timestamp"
      (let [conn (temp-conn)
            db-info {:conn conn}
            page-id "test-page-2"
            old-time (ms-ago ONE_HOUR_MS)]
        (try
          (d/transact! conn [{:page/id page-id
                              :page/document-id "doc-1"
                              :page/index 0
                              :page/created-at old-time
                              :page/last-accessed old-time
                              :page/access-count 0.0}])
          (db/record-page-access! db-info page-id 1.0)
          (let [page (d/pull (d/db conn) [:page/last-accessed] [:page/id page-id])]
            (expect (.after ^java.util.Date (:page/last-accessed page) old-time)))
          (finally
            (d/close conn)))))))

;; =============================================================================
;; get-page-vitality — end-to-end from Datalevin
;; =============================================================================

(defdescribe get-page-vitality-test
  (it "returns vitality for a stored page"
    (let [conn (temp-conn)
          db-info {:conn conn}
          page-id "test-page-v1"]
      (try
        (d/transact! conn [{:page/id page-id
                            :page/document-id "doc-1"
                            :page/index 0
                            :page/created-at (java.util.Date.)
                            :page/last-accessed (java.util.Date.)
                            :page/access-count 5.0}
                           ;; Add some child nodes
                           {:page.node/id "node-1" :page.node/page-id page-id
                            :page.node/document-id "doc-1" :page.node/type :paragraph}
                           {:page.node/id "node-2" :page.node/page-id page-id
                            :page.node/document-id "doc-1" :page.node/type :heading}])
        (let [v (db/get-page-vitality db-info page-id)]
          (expect (some? v))
          (expect (number? (:score v)))
          (expect (#{:active :stale :fading :archived} (:zone v)))
          (expect (= 5.0 (:access-count v)))
          (expect (= 2 (:children-count v))))
        (finally
          (d/close conn)))))

  (it "returns nil for non-existent page"
    (let [conn (temp-conn)
          db-info {:conn conn}]
      (try
        (expect (nil? (db/get-page-vitality db-info "non-existent")))
        (finally
          (d/close conn))))))
