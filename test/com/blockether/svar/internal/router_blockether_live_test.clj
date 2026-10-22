(ns com.blockether.svar.internal.router-blockether-live-test
  "Live router tests against the real Blockether One endpoint.

   Each `it` is gated with `(when (ts/blockether-enabled?) ...)`: without a key
   it passes vacuously. With a key, each test makes one small call to the
   fastest models (`ts/FAST_MODEL`, `ts/SECOND_MODEL`).

   These tests prove what the unit tests cannot:
     - `:optimize :cost` routing reaches the cheaper model's endpoint.
     - `:reasoning :deep` reaches the wire and the reply still parses.
     - `:reasoning` filters the `:optimize` candidates before the call.
     - `:routed/model` is the exact model that answered."
  (:require [lazytest.core :refer [defdescribe describe expect it]]
            [com.blockether.svar.core :as svar]
            [com.blockether.svar.test-support :as ts]))

(defn- router-with-models [models] (svar/make-router [(ts/blockether-provider models)]))

(def ^:private answer-spec
  (svar/spec (svar/field svar/NAME
                         :answer
                         svar/TYPE
                         svar/TYPE_STRING
                         svar/CARDINALITY
                         svar/CARDINALITY_ONE
                         svar/DESCRIPTION
                         "The single-word answer")))

(defdescribe blockether-smoke-test
             (describe "basic ask! against Blockether One"
                       (it "returns a parsed structured response"
                           (when (ts/blockether-enabled?)
                             (let [r
                                   (router-with-models [ts/FAST_MODEL])

                                   result
                                   (svar/ask! r
                                              {:spec answer-spec
                                               :messages
                                               [(svar/user
                                                  "What is 2+2? Reply with just the number.")]
                                               :model ts/FAST_MODEL
                                               :reasoning :low})]

                               (expect (string? (get-in result [:result :answer])))
                               (expect (pos? (:duration-ms result)))
                               (expect (= ts/FAST_MODEL (:routed/model result)))
                               (expect (= :blockether (:routed/provider-id result))))))))

(defdescribe blockether-optimize-cost-live-test
             (describe ":optimize :cost picks the cheapest model in the fleet"
                       (it "routes to the cheaper model with real pricing"
                           (when (ts/blockether-enabled?)
                             ;; FAST_MODEL is 4x cheaper than SECOND_MODEL (ts/MODEL_META).
                             (let [r
                                   (router-with-models [ts/SECOND_MODEL ts/FAST_MODEL])

                                   result
                                   (svar/ask! r
                                              {:spec answer-spec
                                               :messages [(svar/user "Reply with the word 'ok'.")]
                                               :routing {:optimize :cost}
                                               :reasoning :low})]

                               (expect (= ts/FAST_MODEL (:routed/model result)))
                               (expect (some? (get-in result [:result :answer]))))))))

(defdescribe
  blockether-reasoning-end-to-end-test
  (describe ":reasoning :deep on an effort-style reasoning model"
            (it "the endpoint accepts the translated effort and the reply parses"
                (when (ts/blockether-enabled?)
                  (let [r
                        (router-with-models [ts/FAST_MODEL])

                        result
                        (svar/ask! r
                                   {:spec answer-spec
                                    :messages [(svar/user "Reply with the word 'thought'.")]
                                    :reasoning :deep})]

                    (expect (= ts/FAST_MODEL (:routed/model result)))
                    (expect (some? (get-in result [:result :answer])))))))
  (describe ":reasoning :deep combined with :optimize :cost"
            (it "picks the cheapest REASONING-CAPABLE model, not the cheapest overall"
                (when (ts/blockether-enabled?)
                  ;; SECOND_MODEL is declared non-reasoning and nearly free here, so
                  ;; it wins on pure cost. The :reasoning filter must exclude it.
                  (let [r
                        (router-with-models [{:name ts/SECOND_MODEL
                                              :reasoning? false
                                              :pricing {:input 0.01 :output 0.01}} ts/FAST_MODEL])

                        result
                        (svar/ask! r
                                   {:spec answer-spec
                                    :messages [(svar/user "Reply with the word 'ok'.")]
                                    :routing {:optimize :cost}
                                    :reasoning :deep})]

                    (expect (= ts/FAST_MODEL (:routed/model result)))
                    (expect (some? (get-in result [:result :answer]))))))))
