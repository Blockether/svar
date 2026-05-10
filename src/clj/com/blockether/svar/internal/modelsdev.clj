(ns com.blockether.svar.internal.modelsdev
  "models.dev catalog loader.

   Reads the bundled `resources/models.dev.json` snapshot (refreshed via
   `make refresh-models`) and exposes a normalized view that downstream
   router code merges with `KNOWN_PROVIDERS` wire/policy overlay.

   Catalog wins for: pricing, context, modalities, capability flags,
   release dates, family.
   svar overlay wins for: api-style, reasoning-style, llm-headers,
   env-keys, base-url, paths, extra-body, exclude-models, rate budgets,
   default-models.

   Plan-vs-retail pricing — per-provider entries on models.dev already
   reflect plan zeros (e.g. `github-copilot`, `zai-coding-plan` ship
   {input:0, output:0}). For svar's `:openai-codex` and
   `:anthropic-coding-plan` we explicitly want **retail** pricing
   (the user pays at API rates once metered), so the overlay declares
   `:pricing-source` to redirect catalog lookup to the retail provider."
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]))

(def ^:private RESOURCE "models.dev.json")

(defn- load-raw []
  (when-let [r (io/resource RESOURCE)]
    (with-open [rdr (io/reader r)]
      (json/read-json rdr :key-fn keyword))))

(def catalog
  "Delayed lazy load of models.dev snapshot.
   Map: provider-id (keyword) -> raw provider entry."
  (delay
    (when-let [raw (load-raw)]
      ;; raw keys are strings of provider ids; keyword them for consistency.
      (into {} (map (fn [[k v]] [(keyword (name k)) v])) raw))))

(defn normalize-model
  "Translate one models.dev model entry → svar-shaped model metadata.

   Output keys (all optional):
     :name             — canonical wire id (string)
     :display-name     — human label
     :family           — model family slug
     :pricing          — {:input :output :cache-read :cache-write}
     :context          — input + output budget (tokens)
     :input-limit      — input-only cap (when separate)
     :output-limit     — max output tokens
     :reasoning?       — reasoning model flag
     :tool-call?       — supports tools
     :attachment?      — supports file/image input
     :temperature?     — temperature param respected
     :open-weights?    — open-weight model
     :modalities       — {:input #{...} :output #{...}}
     :knowledge-cutoff — yyyy-mm string
     :release-date     — yyyy-mm-dd
     :last-updated     — yyyy-mm-dd"
  [m]
  (let [d     (fn [n] (when (some? n) (double n)))
        cost  (:cost m)
        lim   (:limit m)
        modal (:modalities m)]
    (cond-> {:name (:id m)}
      (:name m)          (assoc :display-name (:name m))
      (:family m)        (assoc :family (:family m))
      cost               (assoc :pricing
                           ;; Coerce to double — models.dev ships ints
                           ;; (`5`) where svar code/tests assume floats (`5.0`).
                           (cond-> {}
                             (some? (:input cost))       (assoc :input (d (:input cost)))
                             (some? (:output cost))      (assoc :output (d (:output cost)))
                             ;; Surface cache_read under both modern
                             ;; (`:cache-read`) and svar-legacy (`:cached-input`)
                             ;; keys so existing token/estimate-cost paths
                             ;; pick it up without an overlay shim.
                             (some? (:cache_read cost))  (assoc :cache-read   (d (:cache_read cost))
                                                           :cached-input (d (:cache_read cost)))
                             (some? (:cache_write cost)) (assoc :cache-write (d (:cache_write cost)))))
      (:context lim)     (assoc :context (:context lim))
      (:input lim)       (assoc :input-limit (:input lim))
      (:output lim)      (assoc :output-limit (:output lim))
      (some? (:reasoning m))    (assoc :reasoning? (boolean (:reasoning m)))
      (some? (:tool_call m))    (assoc :tool-call? (boolean (:tool_call m)))
      (some? (:attachment m))   (assoc :attachment? (boolean (:attachment m)))
      (some? (:temperature m))  (assoc :temperature? (boolean (:temperature m)))
      (some? (:open_weights m)) (assoc :open-weights? (boolean (:open_weights m)))
      modal              (assoc :modalities
                           {:input  (set (map keyword (:input modal)))
                            :output (set (map keyword (:output modal)))})
      (:knowledge m)     (assoc :knowledge-cutoff (:knowledge m))
      (:release_date m)  (assoc :release-date (:release_date m))
      (:last_updated m)  (assoc :last-updated (:last_updated m)))))

(defn provider-models
  "Returns a map of model-name (string) → normalized metadata for
   provider-id, pulled from the models.dev catalog.

   Returns {} when the provider is unknown to models.dev."
  [provider-id]
  (let [entry (get @catalog provider-id)]
    (->> (:models entry)
      vals
      (map normalize-model)
      (reduce (fn [acc m] (assoc acc (:name m) m)) {}))))

(defn provider-meta
  "Returns top-level provider info from models.dev:
     {:id :env :api :name :doc :npm}"
  [provider-id]
  (when-let [e (get @catalog provider-id)]
    (select-keys e [:id :env :api :name :doc :npm])))

(defn merge-overlay
  "Merge svar wire/policy overlay onto a models.dev catalog model entry.
   Overlay keys win (api-style, reasoning-style, extra-body, etc).
   Pricing/context/modalities flow from catalog unless overlay overrides."
  [catalog-entry overlay-entry]
  (merge catalog-entry overlay-entry
    ;; pricing: deep-merge so an overlay can override one rate without
    ;; nuking the cache-read/cache-write tier from the catalog.
    (when (or (:pricing catalog-entry) (:pricing overlay-entry))
      {:pricing (merge (:pricing catalog-entry) (:pricing overlay-entry))})))

(defn resolve-models
  "Build the final model map for a svar provider id.

   Lookup chain:
     1. catalog provider = (:pricing-source overlay) or provider-id
     2. catalog model map → normalize
     3. overlay (svar-side) wins on api-style/reasoning-style/extra-body
     4. overlay-only entries (not in catalog) pass through unchanged

   `overlay-models` is the per-model map from KNOWN_PROVIDER_MODELS."
  [provider-id overlay-models {:keys [pricing-source]}]
  (let [src        (or pricing-source provider-id)
        cat-models (provider-models src)
        all-names  (into (set (keys cat-models)) (keys overlay-models))]
    (reduce
      (fn [acc nm]
        (assoc acc nm
          (merge-overlay (get cat-models nm) (get overlay-models nm))))
      {}
      all-names)))
