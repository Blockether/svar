(ns com.blockether.svar.internal.rlm.data
  (:require
   [clojure.string :as str]
   [com.blockether.svar.internal.rlm.db :as rlm-db]
   [com.blockether.svar.internal.rlm.schema :as schema]
   [com.blockether.svar.internal.util :as util]
   [taoensso.trove :as trove]))

(defn- normalize-entity-type
  [raw-type]
  (let [t-name (some-> raw-type name)]
    (if (and t-name (contains? schema/ENTITY_TYPE_VALUES t-name))
      (keyword t-name)
      :concept)))

(defn- normalize-relationship-type
  [raw-type]
  (let [rt-name (some-> raw-type name)]
    (if (and rt-name (contains? schema/RELATIONSHIP_TYPE_VALUES rt-name))
      (keyword rt-name)
      :related-to)))

(defn- entity->attrs
  [db-info doc-id entity]
  (let [entity-id (util/uuid)
        entity-name (or (:entity/name entity) (:name entity) "unknown")
        entity-type (normalize-entity-type (or (:entity/type entity) (:type entity)))
        canonical-id (rlm-db/resolve-canonical-id db-info entity-name entity-type)]
    [entity-name
     entity-id
     (cond-> {:entity/id entity-id
              :entity/name entity-name
              :entity/type entity-type
              :entity/canonical-id canonical-id
              :entity/description (or (:entity/description entity) (:description entity) "")
              :entity/document-id (str doc-id)
              :entity/created-at (java.util.Date.)}
       (or (:entity/section entity) (:section entity))
       (assoc :entity/section (or (:entity/section entity) (:section entity)))
       (or (:entity/page entity) (:page entity))
       (assoc :entity/page (long (or (:entity/page entity) (:page entity)))))]))

(defn- store-entities!
  [db-info doc-id entities]
  (let [name->uuid (atom {})]
    (doseq [entity entities]
      (try
        (let [[entity-name entity-id attrs] (entity->attrs db-info doc-id entity)]
          (rlm-db/store-entity! db-info attrs)
          (swap! name->uuid assoc (str/lower-case entity-name) entity-id))
        (catch Exception e
          (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Failed to store entity"}))))
    @name->uuid))

(defn- store-relationships!
  [db-info doc-id relationships name->uuid]
  (doseq [rel relationships]
    (try
      (let [src-name (or (:relationship/source-entity-id rel) (:source rel))
            tgt-name (or (:relationship/target-entity-id rel) (:target rel))
            src-id (get name->uuid (some-> src-name str str/lower-case))
            tgt-id (get name->uuid (some-> tgt-name str str/lower-case))]
        (when (and src-id tgt-id)
          (rlm-db/store-relationship! db-info
            {:relationship/id (util/uuid)
             :relationship/type (normalize-relationship-type (or (:relationship/type rel) (:type rel)))
             :relationship/source-entity-id src-id
             :relationship/target-entity-id tgt-id
             :relationship/description (or (:relationship/description rel) (:description rel) "")
             :relationship/document-id (str doc-id)})))
      (catch Exception e
        (trove/log! {:level :warn :data {:error (ex-message e)} :msg "Failed to store relationship"})))))

(defn store-extraction-results!
  "Store extracted entities + relationships into the RLM store.
   Returns {:entities-extracted N :relationships-extracted N}."
  [db-info doc-id entities relationships]
  (let [name->uuid (store-entities! db-info doc-id entities)]
    (store-relationships! db-info doc-id relationships name->uuid)
    {:entities-extracted (count entities)
     :relationships-extracted (count relationships)}))
