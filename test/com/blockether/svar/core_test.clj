(ns com.blockether.svar.core-test
  "Tests for svar/core public API: message constructors and structured `ask!`
   (mocked + integration). Integration tests (real LLM) are guarded by env var."
  (:require [lazytest.core :refer [defdescribe describe expect it]]
            [com.blockether.svar.core :as svar]
            [com.blockether.svar.internal.llm :as llm]
            [com.blockether.svar.test-support :as ts]))

;; =============================================================================
;; Log context
;; =============================================================================

(defdescribe public-catalog-api-test
             (describe "failures and log context"
                       (it "merges nested log context for the body"
                           (expect (= {:query-id "q" :iteration 1}
                                      (svar/with-log-context {:query-id "q"}
                                                             (svar/with-log-context
                                                               {:iteration 1}
                                                               llm/*log-context*)))))))

;; =============================================================================
;; Message Helpers Tests
;; =============================================================================

(defdescribe message-helpers-test
             (describe "user"
                       (it "creates user message with multimodal content when images provided"
                           (let [img
                                 (svar/image "base64data" "image/png")

                                 msg
                                 (svar/user "Describe this" img)]

                             (expect (= "user" (:role msg)))
                             (expect (vector? (:content msg)))
                             (expect (= 2 (count (:content msg))))
                             ;; First element is image_url (images come first in multimodal)
                             (let [img-part (first (:content msg))]
                               (expect (= "image_url" (:type img-part)))
                               (expect (some? (re-find #"base64data"
                                                       (get-in img-part [:image_url :url])))))
                             ;; Second element is text
                             (let [text-part (second (:content msg))]
                               (expect (= "text" (:type text-part)))
                               (expect (= "Describe this" (:text text-part))))))))

;; =============================================================================
;; Integration tests: real calls to Blockether One, gated on its key
;; =============================================================================

(defn- integration-router [] (svar/make-router [(ts/blockether-provider [ts/FAST_MODEL])]))

(defdescribe
  blockether-one-structured-test
  (describe
    "ask! via Blockether One"
    (it
      "parses structured JSON into typed fields"
      (when (ts/blockether-enabled?)
        (let [person-spec
              (svar/spec (svar/field svar/NAME
                                     :name
                                     svar/TYPE
                                     svar/TYPE_STRING
                                     svar/CARDINALITY
                                     svar/CARDINALITY_ONE
                                     svar/DESCRIPTION
                                     "Full name")
                         (svar/field svar/NAME
                                     :age
                                     svar/TYPE
                                     svar/TYPE_INT
                                     svar/CARDINALITY
                                     svar/CARDINALITY_ONE
                                     svar/DESCRIPTION
                                     "Age in years"))

              result
              (svar/ask! (integration-router)
                         {:spec person-spec
                          :messages [(svar/system "Extract person data from the text.")
                                     (svar/user "Alexander is 18 years old.")]
                          :model ts/FAST_MODEL})]

          ;; ask! returns {:result <data> :tokens :cost :duration-ms}
          (expect (some? (:tokens result)))
          (expect (some? (:cost result)))
          (expect (pos? (:duration-ms result)))
          (expect (= {:name "Alexander" :age 18} (select-keys (:result result) [:name :age]))))))))

(def ^:private streaming-spec
  (svar/spec (svar/field svar/NAME
                         :title
                         svar/TYPE
                         svar/TYPE_STRING
                         svar/CARDINALITY
                         svar/CARDINALITY_ONE
                         svar/DESCRIPTION
                         "Title of the discovery")
             (svar/field svar/NAME
                         :year
                         svar/TYPE
                         svar/TYPE_INT
                         svar/CARDINALITY
                         svar/CARDINALITY_ONE
                         svar/DESCRIPTION
                         "Year of discovery")
             (svar/field svar/NAME
                         :related-fields
                         svar/TYPE
                         svar/TYPE_STRING
                         svar/CARDINALITY
                         svar/CARDINALITY_MANY
                         svar/DESCRIPTION
                         "Scientific fields impacted")))

(defdescribe
  streaming-integration-test
  (describe
    "streaming extraction"
    (it
      "streams partial spec fields and returns the final result"
      (when (ts/blockether-enabled?)
        (let
          [chunks
           (atom [])

           result
           (svar/ask!
             (integration-router)
             {:spec streaming-spec
              :messages
              [(svar/system "Extract the requested information.")
               (svar/user
                 "In 1928, Alexander Fleming discovered penicillin. It changed microbiology and pharmacology.")]
              :model ts/FAST_MODEL
              :on-chunk #(swap! chunks conj %)})]

          (expect (string? (get-in result [:result :title])))
          (expect (= 1928 (get-in result [:result :year])))
          (expect (vector? (get-in result [:result :related-fields])))
          ;; Reasoning-only chunks may have :result nil; content chunks must have :result
          (expect (some :result @chunks)))))))
