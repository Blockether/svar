(ns com.blockether.svar.internal.modelsdev-test
  "Lookups in the bundled models.dev catalog."
  (:require [lazytest.core :refer [defdescribe expect it]]
            [com.blockether.svar.internal.modelsdev :as modelsdev]))

(defn- catalog-of
  [model-ids]
  {:acme {:models (into {}
                        (map (fn [id]
                               [(keyword id) {:id id :name id}]))
                        model-ids)}})

(defn- counted-lookups
  "Runs `f` and returns `[result normalize-model-calls]`."
  [f]
  (let [calls
        (atom 0)

        normalize
        modelsdev/normalize-model]

    (with-redefs [modelsdev/normalize-model (fn [m]
                                              (swap! calls inc)
                                              (normalize m))]
      [(f) @calls])))

(defdescribe
  provider-models-test
  ;; Perf report: the gateway model picker and pricing lookups normalized every
  ;; model of a provider on each call; GET /v1/sessions/:id/model spent most
  ;; of its 15 ms in normalize-model.
  (it "normalizes the models of one catalog provider once"
      (with-redefs [modelsdev/catalog (delay (catalog-of ["m-1" "m-2" "m-3"]))]
        (let [[first-models first-calls] (counted-lookups #(modelsdev/provider-models :acme))
              [again-models again-calls] (counted-lookups #(modelsdev/provider-models :acme))]

          (expect (= #{"m-1" "m-2" "m-3"} (set (keys first-models))))
          (expect (= 3 first-calls))
          (expect (= first-models again-models))
          (expect (zero? again-calls)))))
  (it "reads the models of a new catalog value again"
      (with-redefs [modelsdev/catalog (delay (catalog-of ["old"]))]
        (modelsdev/provider-models :acme))
      (with-redefs [modelsdev/catalog (delay (catalog-of ["new"]))]
        (expect (= #{"new"} (set (keys (modelsdev/provider-models :acme)))))))
  (it "returns no models for a provider that the catalog does not list"
      (with-redefs [modelsdev/catalog (delay (catalog-of ["m-1"]))]
        (expect (= {} (modelsdev/provider-models :unknown))))))

(defdescribe
  normalize-model-limits-test
  ;; models.dev lists `limit: {context: 0, output: 0}` for image and video models. The Vis
  ;; model catalog sent `output_limit: 0`, and the SDK refused the whole list.
  (it "drops a token limit that is not positive"
      (let [model (modelsdev/normalize-model {:id "img" :limit {:context 0 :input 0 :output 0}})]
        (expect (= {:name "img"} (select-keys model [:name :context :input-limit :output-limit])))))
  (it "keeps positive token limits"
      (expect (= {:context 8192 :input-limit 4096 :output-limit 1024}
                 (select-keys (modelsdev/normalize-model
                                {:id "m" :limit {:context 8192 :input 4096 :output 1024}})
                              [:context :input-limit :output-limit])))))
