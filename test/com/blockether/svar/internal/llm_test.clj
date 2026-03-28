(ns com.blockether.svar.internal.llm-test
  "Tests for router model selection, preferences, and fallback logic."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.llm :as sut]))

;;; ── Test fixtures ──────────────────────────────────────────────────────

(def test-providers
  [{:id :provider-a
    :api-key "test"
    :base-url "http://localhost:1"
    :models [{:name "tiny"    :intelligence :low    :speed :fast   :cost :low    :capabilities #{:chat}}
             {:name "medium"  :intelligence :medium :speed :medium :cost :medium :capabilities #{:chat :vision}}
             {:name "big"     :intelligence :high   :speed :slow   :cost :high   :capabilities #{:chat :vision}}]}
   {:id :provider-b
    :api-key "test"
    :base-url "http://localhost:2"
    :models [{:name "genius"   :intelligence :frontier :speed :slow   :cost :high   :capabilities #{:chat :vision}}
             {:name "balanced" :intelligence :high     :speed :medium :cost :medium :capabilities #{:chat}}]}])

(defn- make-router [] (sut/make-router test-providers))

(defn- selected-model [router prefs]
  (when-let [[_ model-map] (sut/select-provider router prefs)]
    (:name model-map)))

;;; ── Tests ──────────────────────────────────────────────────────────────

(defdescribe root-strategy-test
  (it "selects first model of highest-priority provider"
    (expect (= "tiny" (selected-model (make-router) {:strategy :root})))))

(defdescribe single-prefer-test
  (describe "prefer :cost"
    (it "selects cheapest model"
      (expect (= "tiny" (selected-model (make-router) {:prefer :cost})))))

  (describe "prefer :intelligence"
    (it "selects most intelligent model from highest-priority provider"
      ;; provider-a has big (high), provider-b has genius (frontier)
      ;; but provider-a has higher priority so big wins
      (expect (= "big" (selected-model (make-router) {:prefer :intelligence})))))

  (describe "prefer :speed"
    (it "selects fastest model"
      (expect (= "tiny" (selected-model (make-router) {:prefer :speed}))))))

(defdescribe vector-prefer-test
  (describe "[:cost :speed]"
    (it "cheapest first, fastest as tiebreaker"
      (expect (= "tiny" (selected-model (make-router) {:prefer [:cost :speed]})))))

  (describe "[:intelligence :cost]"
    (it "smartest first from highest-priority provider"
      ;; provider-a's big (high) wins over provider-b's genius (frontier) due to priority
      (expect (= "big" (selected-model (make-router) {:prefer [:intelligence :cost]})))))

  (describe "[:speed :intelligence]"
    (it "fastest first, smartest as tiebreaker"
      (expect (= "tiny" (selected-model (make-router) {:prefer [:speed :intelligence]})))))

  (describe "single-element vector matches keyword"
    (it "[:cost] same as :cost"
      (let [r (make-router)]
        (expect (= (selected-model r {:prefer :cost})
                   (selected-model r {:prefer [:cost]})))))
    (it "[:intelligence] same as :intelligence"
      (let [r (make-router)]
        (expect (= (selected-model r {:prefer :intelligence})
                   (selected-model r {:prefer [:intelligence]})))))
    (it "[:speed] same as :speed"
      (let [r (make-router)]
        (expect (= (selected-model r {:prefer :speed})
                   (selected-model r {:prefer [:speed]})))))))

(defdescribe capabilities-filter-test
  (describe "requiring :vision"
    (it "excludes non-vision models"
      (expect (not= "tiny" (selected-model (make-router) {:prefer :cost :capabilities #{:vision}}))))

    (it "cheapest with vision is medium"
      (expect (= "medium" (selected-model (make-router) {:prefer :cost :capabilities #{:vision}}))))

    (it "smartest with vision from highest-priority provider"
      ;; provider-a has medium (medium) and big (high) with vision → big wins
      (expect (= "big" (selected-model (make-router) {:prefer :intelligence :capabilities #{:vision}})))))

  (describe "vector prefer with capabilities"
    (it "[:cost :speed] with vision"
      (expect (= "medium" (selected-model (make-router) {:prefer [:cost :speed] :capabilities #{:vision}}))))))

(defdescribe exclude-model-test
  (it "skips excluded model"
    (expect (not= "tiny" (selected-model (make-router) {:prefer :cost :exclude-model "tiny"}))))

  (it "exclude works with vector preferences"
    (expect (not= "tiny" (selected-model (make-router) {:prefer [:cost :speed] :exclude-model "tiny"})))))

(defdescribe edge-cases-test
  (it "no preference returns a model"
    (expect (some? (selected-model (make-router) {}))))

  (it "empty vector returns a model"
    (expect (some? (selected-model (make-router) {:prefer []}))))

  (it "nil prefer returns a model"
    (expect (some? (selected-model (make-router) {:prefer nil})))))
