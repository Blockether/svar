(ns com.blockether.svar.extension-test
  (:require
   [com.blockether.svar.core :as svar]
   [com.blockether.svar.extension :as ext]
   [com.blockether.svar.internal.router :as router]
   [lazytest.core :refer [defdescribe describe expect it]]))

(defn- person-spec
  []
  (svar/spec
    (svar/field svar/NAME :name
      svar/TYPE svar/TYPE_STRING
      svar/CARDINALITY svar/CARDINALITY_ONE
      svar/DESCRIPTION "Person name")
    (svar/field svar/NAME :age
      svar/TYPE svar/TYPE_INT
      svar/CARDINALITY svar/CARDINALITY_ONE
      svar/DESCRIPTION "Person age")))

(defdescribe provider-limits-extension-test
  (describe "provider-limits"
    (it "exposes rpm/tpm usage, remaining capacity, models, and budget"
      (let [clock (atom 0)
            r (svar/make-router
                [{:id :ext-provider
                  :base-url "http://ext-provider"
                  :api-key "k"
                  :rpm 2
                  :tpm 100
                  :models [{:name "m1"}]}]
                {:clock (fn [] @clock)
                 :budget {:max-tokens 1000 :max-cost 10.0}})]
        (router/with-provider-fallback r {:strategy :root}
          (fn [_provider _model]
            (swap! clock + 10)
            {:result {:ok true}
             :api-usage {:input-tokens 10
                         :output-tokens 20
                         :total-tokens 30}
             :tokens {:total 30}}))
        (let [limits (ext/provider-limits r)]
          (expect (= 60000 (:window-ms limits)))
          (expect (= ["m1"] (get-in limits [:providers :ext-provider :models])))
          (expect (= 2 (get-in limits [:providers :ext-provider :rpm :limit])))
          (expect (= 1 (get-in limits [:providers :ext-provider :rpm :used])))
          (expect (= 1 (get-in limits [:providers :ext-provider :rpm :remaining])))
          (expect (= 100 (get-in limits [:providers :ext-provider :tpm :limit])))
          (expect (= 30 (get-in limits [:providers :ext-provider :tpm :used])))
          (expect (= 70 (get-in limits [:providers :ext-provider :tpm :remaining])))
          (expect (= :closed (get-in limits [:providers :ext-provider :circuit-breaker])))
          (expect (= {:max-tokens 1000 :max-cost 10.0} (get-in limits [:budget :limit])))
          (expect (= 30 (get-in limits [:budget :spent :total-tokens])))
          (expect (= 970 (get-in limits [:budget :remaining :tokens]))))))))

(defdescribe parse-diagnose-extension-test
  (describe "parse-diagnose"
    (it "returns parser warnings and spec result on valid jsonish input"
      (let [diagnosis (ext/parse-diagnose "{name: \"Ada\", age: 37,}" (person-spec))]
        (expect (true? (:ok? diagnosis)))
        (expect (= :schema (:phase diagnosis)))
        (expect (= {:name "Ada" :age 37} (:result diagnosis)))
        (expect (= {:valid? true} (:validation diagnosis)))
        (expect (seq (:warnings diagnosis)))))

    (it "returns schema rejection data instead of throwing"
      (let [diagnosis (ext/parse-diagnose "plain prose" (person-spec))]
        (expect (false? (:ok? diagnosis)))
        (expect (= :schema (:phase diagnosis)))
        (expect (= :svar.spec/schema-rejected (get-in diagnosis [:error :type])))
        (expect (= :not-a-map (get-in diagnosis [:error :reason])))
        (expect (= "String" (get-in diagnosis [:error :received-type])))))

    (it "works without a spec"
      (let [diagnosis (ext/parse-diagnose "{city: \"Paris\"}")]
        (expect (true? (:ok? diagnosis)))
        (expect (= :parse (:phase diagnosis)))
        (expect (= {:city "Paris"} (:value diagnosis)))))))


