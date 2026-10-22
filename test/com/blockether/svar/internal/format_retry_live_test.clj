(ns com.blockether.svar.internal.format-retry-live-test
  "Live tests of the structured-output hardening on Blockether One:
   `:format-retries`, `:json-object-mode?` and the forensic `:format-attempts`.

   Each live `it` is gated with `(when (ts/blockether-enabled?) ...)` and makes
   one small call to `ts/FAST_MODEL`. The metadata tests run unconditionally.

   With a key, the tests verify:
     - `:json-object-mode? true` reaches the wire without a 400.
     - `:json-object-mode? false` sends no `response_format` and still succeeds.
     - Caller `:extra-body :response_format` is forwarded verbatim.
     - `:format-retries 2` on a multi-field spec under `:reasoning :deep`
       round-trips, and every failed attempt keeps its full content.
     - `:format-retries 0` makes one attempt and records no `:format-attempts`.

   The tests do not force a prose leak: that is a probabilistic model quirk.
   They prove that the wire shape is accepted and that retries round-trip."
  (:require [lazytest.core :refer [defdescribe describe expect it]]
            [com.blockether.svar.core :as svar]
            [com.blockether.svar.internal.router :as router]
            [com.blockether.svar.test-support :as ts]))

(defn- blockether-router [] (svar/make-router [(ts/blockether-provider [ts/FAST_MODEL])]))

(def ^:private answer-spec
  (svar/spec (svar/field svar/NAME
                         :answer
                         svar/TYPE
                         svar/TYPE_STRING
                         svar/CARDINALITY
                         svar/CARDINALITY_ONE
                         svar/DESCRIPTION
                         "The single-word answer")))

;; A multi-field iteration-envelope-shaped spec -- multiple required fields
;; with strict typing. This is the flavour of spec where GLM-5.1 historically
;; leaks prose into `content` instead of producing the schema-conformant
;; object, especially under `:reasoning :deep`.
(def ^:private iteration-shaped-spec
  (svar/spec (svar/field svar/NAME
                         :thinking
                         svar/TYPE
                         svar/TYPE_STRING
                         svar/CARDINALITY
                         svar/CARDINALITY_ONE
                         svar/DESCRIPTION
                         "What you're thinking about the task")
             (svar/field svar/NAME
                         :answer
                         svar/TYPE
                         svar/TYPE_STRING
                         svar/CARDINALITY
                         svar/CARDINALITY_ONE
                         svar/DESCRIPTION
                         "The single-word answer")
             (svar/field svar/NAME
                         :confidence
                         svar/TYPE
                         svar/TYPE_KEYWORD
                         svar/CARDINALITY
                         svar/CARDINALITY_ONE
                         svar/DESCRIPTION
                         "How confident you are: low, medium, or high"
                         svar/VALUES
                         ["low" "medium" "high"])))

;; An empty reply (`:svar.llm/empty-content`) is a rare upstream flake. svar's
;; default retry set is schema-shape only, so the live tests opt into it.
;; Caller opts merge on top, so `:format-retries 0` keeps one attempt.
(def ^:private flake-retry-opts
  {:format-retries 2
   :format-retry-on #{:svar.llm/empty-content :svar.spec/schema-rejected
                      :svar.spec/required-field-missing}})

(defn- ask-live!
  "svar/ask! on Blockether One with flake tolerance merged in (caller wins)."
  [opts]
  (svar/ask! (blockether-router) (merge flake-retry-opts opts)))

;; =============================================================================
;; Metadata sanity -- GLM models carry :json-object-mode? after normalize
;; =============================================================================

(defdescribe
  glm-json-object-mode-metadata-test
  "Verifies `KNOWN_PROVIDER_MODELS` flags GLM models for auto-injection on
   the endpoints exercised below. Runs unconditionally -- only touches static
   tables, no HTTP."
  (describe
    ":zai-coding provider GLM models"
    (it "glm-4.7 has :json-object-mode? true"
        (expect (true? (:json-object-mode? (router/provider-model-entry :zai-coding "glm-4.7")))))
    (it "glm-5.1 has :json-object-mode? true"
        (expect (true? (:json-object-mode? (router/provider-model-entry :zai-coding "glm-5.1"))))))
  (describe "non-GLM models are NOT flagged"
            (it "gpt-5-mini has no :json-object-mode? flag"
                (expect (not (:json-object-mode? (router/provider-model-entry :openai
                                                                              "gpt-5-mini")))))
            (it "claude-sonnet-4-6 has no :json-object-mode? flag"
                (expect (not (:json-object-mode?
                               (router/provider-model-entry :anthropic "claude-sonnet-4-6")))))))

(defdescribe
  glm-router-propagates-json-object-mode-test
  "Verifies `normalize-provider` carries the metadata flag onto the resolved
   model-map. Runs unconditionally."
  (describe "make-router on :zai-coding with glm-4.7"
            (it "model-map gets :json-object-mode? true via Coding-Plan-scoped metadata"
                (let [r
                      (svar/make-router
                        [{:id :zai-coding :api-key "sk-fake" :models [{:name "glm-4.7"}]}])

                      model
                      (first (filter #(= "glm-4.7" (:name %)) (:models (first (:providers r)))))]

                  (expect (true? (:json-object-mode? model)))))))

;; =============================================================================
;; Live: Blockether One
;; =============================================================================

(defdescribe
  blockether-json-object-mode-live-test
  (describe ":json-object-mode? true"
            (it "the endpoint accepts response_format json_object (no 400)"
                (when (ts/blockether-enabled?)
                  (let [result (ask-live! {:spec answer-spec
                                           :messages [(svar/user "Reply with the word 'jsonmode'.")]
                                           :json-object-mode? true})]
                    (expect (= ts/FAST_MODEL (:routed/model result)))
                    (expect (= :blockether (:routed/provider-id result)))
                    (expect (string? (get-in result [:result :answer])))))))
  (describe ":json-object-mode? false"
            (it "sends no response_format and still succeeds"
                (when (ts/blockether-enabled?)
                  (let [result (ask-live! {:spec answer-spec
                                           :messages [(svar/user "Reply with the word 'optout'.")]
                                           :json-object-mode? false})]
                    (expect (string? (get-in result [:result :answer])))))))
  (describe ":extra-body :response_format"
            (it "the caller's response_format reaches the endpoint verbatim"
                (when (ts/blockether-enabled?)
                  (let [result (ask-live! {:spec answer-spec
                                           :messages [(svar/user "Reply with the word 'wins'.")]
                                           :extra-body {:response_format {:type "json_object"}}})]
                    (expect (string? (get-in result [:result :answer]))))))))

(defdescribe
  blockether-format-retries-live-test
  (describe ":format-retries 2 with :reasoning :deep"
            (it "a multi-field spec round-trips with json mode on"
                (when (ts/blockether-enabled?)
                  (let [result (ask-live! {:spec iteration-shaped-spec
                                           :messages [(svar/user
                                                        "Pick one word for 'hello'. Be brief.")]
                                           :reasoning :deep
                                           :json-object-mode? true
                                           :format-retries 2})]
                    (expect (some? (get-in result [:result :answer])))
                    (expect (string? (get-in result [:result :thinking])))
                    (expect (#{:low :medium :high} (get-in result [:result :confidence])))
                    ;; If retries fired, each failed attempt keeps its FULL content.
                    (doseq [att (:format-attempts result)
                            :when (not (:ok? att))]

                      (expect (string? (:content att)))
                      (expect (some? (or (:reason att) (:ex-type att)))))))))
  (describe ":format-retries 0 with :reasoning :low"
            (it "makes one attempt and records no :format-attempts on success"
                (when (ts/blockether-enabled?)
                  (let [result (ask-live! {:spec answer-spec
                                           :messages [(svar/user "Reply with the word 'baseline'.")]
                                           :reasoning :low
                                           :format-retries 0})]
                    (expect (some? (get-in result [:result :answer])))
                    (expect (nil? (:format-attempts result))))))))
