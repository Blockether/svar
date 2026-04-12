(ns com.blockether.svar.internal.rlm.qa-manifest
  "QA manifest — crash-resumable state for query-env-qa!.
   Manages corpus snapshots, content hashing, and manifest persistence."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [com.blockether.svar.internal.rlm.db :as rlm-db]
   [datalevin.core :as d]
   [taoensso.trove :as trove])
  (:import
   [java.nio.file AtomicMoveNotSupportedException CopyOption Files StandardCopyOption]
   [java.security MessageDigest]
   [java.time Instant]))

;; -----------------------------------------------------------------------------
;; Constants
;; -----------------------------------------------------------------------------

(def ^:const QA_MANIFEST_VERSION 2)

;; -----------------------------------------------------------------------------
;; Hashing helpers
;; -----------------------------------------------------------------------------

(defn- sha256-hex
  ^String [^String s]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes s "UTF-8"))
    (apply str (map #(format "%02x" %) (.digest digest)))))

(defn- digest-update!
  [^MessageDigest digest x]
  (.update digest (.getBytes (pr-str x) "UTF-8"))
  (.update digest (.getBytes "\n" "UTF-8"))
  digest)

(defn- digest->sha256
  ^String [^MessageDigest digest]
  (str "sha256:" (apply str (map #(format "%02x" %) (.digest digest)))))

;; -----------------------------------------------------------------------------
;; Corpus snapshot
;; -----------------------------------------------------------------------------

(defn- qa-corpus-revision
  [db-info]
  (long (rlm-db/get-corpus-revision db-info)))

(defn- qa-corpus-documents
  [db]
  (->> (d/q '[:find [(pull ?e [:document/id :document/name :document/title
                               :document/extension :document/abstract]) ...]
              :where [?e :document/id _]]
         db)
    (sort-by (juxt :document/id :document/name))
    vec))

(defn- qa-corpus-toc-entries
  [db]
  (->> (d/q '[:find [(pull ?e [:document.toc/id :document.toc/document-id
                               :document.toc/title :document.toc/level
                               :document.toc/target-page :document.toc/target-section-id
                               :document.toc/description]) ...]
              :where [?e :document.toc/id _]]
         db)
    (sort-by (juxt :document.toc/document-id :document.toc/target-page
               :document.toc/level :document.toc/title :document.toc/id))
    vec))

(defn- qa-corpus-page-nodes
  [db]
  (->> (d/q '[:find [(pull ?e [:page.node/id :page.node/document-id :page.node/page-id
                               :page.node/type :page.node/local-id
                               :page.node/content :page.node/description]) ...]
              :where [?e :page.node/id _]]
         db)
    (sort-by (juxt :page.node/document-id :page.node/page-id
               :page.node/local-id :page.node/id))
    vec))

(defn- qa-corpus-content-hash
  [docs toc nodes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [doc docs] (digest-update! digest doc))
    (doseq [entry toc] (digest-update! digest entry))
    (doseq [node nodes] (digest-update! digest node))
    (digest->sha256 digest)))

(defn- qa-corpus-cache-hit!
  [env revision]
  (let [stats (when-let [corpus-atom (:qa-corpus-atom env)]
                (:stats (swap! corpus-atom update :stats
                          #(-> %
                             (update :hits (fnil inc 0))
                             (assoc :last-revision revision)))))]
    (trove/log! {:level :info :id ::qa-corpus-cache-hit
                 :data {:revision revision
                        :hits (:hits stats)
                        :misses (:misses stats)}
                 :msg "QA corpus snapshot cache hit"})))

(defn- qa-corpus-cache-miss!
  [env revision digest-ms]
  (let [stats (when-let [corpus-atom (:qa-corpus-atom env)]
                (:stats (swap! corpus-atom update :stats
                          #(-> %
                             (update :misses (fnil inc 0))
                             (assoc :last-digest-ms digest-ms)
                             (assoc :last-revision revision)))))]
    (trove/log! {:level :info :id ::qa-corpus-cache-miss
                 :data {:revision revision
                        :digest-ms digest-ms
                        :hits (:hits stats)
                        :misses (:misses stats)}
                 :msg "QA corpus snapshot cache miss"})))

(defn- compute-qa-corpus-snapshot
  [db revision]
  (let [docs (qa-corpus-documents db)
        toc (qa-corpus-toc-entries db)
        nodes (qa-corpus-page-nodes db)]
    {:revision revision
     :document-count (count docs)
     :toc-count (count toc)
     :node-count (count nodes)
     :content-hash (qa-corpus-content-hash docs toc nodes)}))

(defn qa-corpus-snapshot
  "Returns deterministic corpus stats + content hash for manifest fingerprinting.
   Hash includes document metadata, TOC entries, and full page-node text payloads."
  [env {:keys [conn] :as db-info}]
  (if-not conn
    {:revision 0 :document-count 0 :toc-count 0 :node-count 0 :content-hash "sha256:0"}
    (let [corpus-atom (:qa-corpus-atom env)
          db (d/db conn)
          revision (qa-corpus-revision db-info)
          cached (when corpus-atom (:snapshot-cache @corpus-atom))]
      (if (and cached (= revision (:revision cached)))
        (do
          (qa-corpus-cache-hit! env revision)
          (:snapshot cached))
        (let [start (System/nanoTime)
              snapshot (compute-qa-corpus-snapshot db revision)
              digest-ms (/ (- (System/nanoTime) start) 1000000.0)]
          (when corpus-atom
            (swap! corpus-atom assoc :snapshot-cache {:revision revision :snapshot snapshot}))
          (qa-corpus-cache-miss! env revision digest-ms)
          snapshot)))))

(defn invalidate-qa-corpus-snapshot-cache!
  "Resets the corpus snapshot cache so the next call recomputes."
  [env]
  (when-let [corpus-atom (:qa-corpus-atom env)]
    (swap! corpus-atom assoc :snapshot-cache nil)))

(defn qa-corpus-snapshot-stats
  "Returns QA corpus snapshot cache stats for observability.
   Shape: {:hits N :misses N :last-digest-ms ms|nil :last-revision rev}."
  [env]
  (if-let [corpus-atom (:qa-corpus-atom env)]
    (:stats @corpus-atom)
    {:hits 0 :misses 0 :last-digest-ms nil :last-revision 0}))

;; -----------------------------------------------------------------------------
;; Manifest persistence
;; -----------------------------------------------------------------------------

(defn qa-manifest-fingerprint
  "Returns a stable fingerprint for query-env-qa! inputs.
   Includes key generation options + corpus summary so resume only reuses
   manifest state when run inputs are compatible."
  [env {:keys [conn] :as _db-info} qa-opts]
  (let [corpus (qa-corpus-snapshot env {:conn conn})
        payload {:manifest-version QA_MANIFEST_VERSION
                 :options qa-opts
                 :corpus corpus}]
    (str "sha256:" (sha256-hex (pr-str payload)))))

(defn fresh-qa-manifest
  "Returns a fresh (empty) manifest map for the given fingerprint."
  [fingerprint]
  {:manifest-version QA_MANIFEST_VERSION
   :fingerprint fingerprint
   :started-at (str (Instant/now))
   :completed-at nil
   :phase1 {:status :pending}
   :batches {}})

(defn qa-manifest-path
  "Returns qa-manifest.edn path for a persistent env, or nil for ephemeral envs."
  [env]
  (when-let [path (:path @(:db-info-atom env))]
    (str path "/qa-manifest.edn")))

(defn read-qa-manifest
  "Reads qa-manifest.edn. Returns nil if not found or ephemeral env."
  [env]
  (when-let [p (qa-manifest-path env)]
    (let [f (io/file p)]
      (when (.exists f)
        (edn/read-string (slurp f))))))

(def ^:private qa-manifest-write-lock (Object.))

(defn write-qa-manifest!
  "Writes qa-manifest.edn atomically via temp+move. No-op for ephemeral envs."
  [env manifest]
  (when-let [p (qa-manifest-path env)]
    (locking qa-manifest-write-lock
      (let [tmp (str p ".tmp")]
        (spit tmp (pr-str manifest))
        (try
          (try
            (Files/move (.toPath (io/file tmp))
              (.toPath (io/file p))
              (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING
                                      StandardCopyOption/ATOMIC_MOVE]))
            (catch AtomicMoveNotSupportedException _
              (Files/move (.toPath (io/file tmp))
                (.toPath (io/file p))
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
          (when-not (.exists (io/file p))
            (throw (ex-info "qa-manifest write failed" {:path p})))
          (catch Exception e
            (throw (ex-info "Failed to persist qa-manifest.edn"
                     {:path p :tmp tmp :cause (ex-message e)} e))))))))

(defn update-qa-batch-status!
  "Updates a single batch's status in the manifest and persists immediately."
  [env manifest-atom batch-idx status-map]
  (swap! manifest-atom assoc-in [:batches batch-idx] status-map)
  (write-qa-manifest! env @manifest-atom))
