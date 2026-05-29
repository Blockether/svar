(ns com.blockether.svar.core-test
  "Tests for svar/core public API: message constructors and structured `ask!`
   (mocked + integration). Integration tests (real LLM) are guarded by env var."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.core :as svar]))

;; =============================================================================
;; Message Helpers Tests
;; =============================================================================

(defdescribe message-helpers-test

  (describe "system"
    (it "creates system message map"
      (expect (= {:role "system" :content "You are helpful."}
                (svar/system "You are helpful.")))))

  (describe "user"
    (it "creates user message with string content (no images)"
      (let [msg (svar/user "Hello")]
        (expect (= "user" (:role msg)))
        (expect (= "Hello" (:content msg)))))

    (it "creates user message with multimodal content when images provided"
      (let [img (svar/image "base64data" "image/png")
            msg (svar/user "Describe this" img)]
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
          (expect (= "Describe this" (:text text-part)))))))

  (describe "assistant"
    (it "creates assistant message map"
      (expect (= {:role "assistant" :content "I can help."}
                (svar/assistant "I can help.")))))

  (describe "image"
    (it "creates image attachment map"
      (let [img (svar/image "abc123" "image/jpeg")]
        (expect (= "abc123" (:base64 img)))
        (expect (= "image/jpeg" (:media-type img)))))))

;; =============================================================================
;; Integration Tests (real LLM calls, guarded by env var)
;; =============================================================================

(defn- integration-tests-enabled?
  "Returns true if LLM integration tests should run."
  []
  (or (some? (System/getenv "BLOCKETHER_LLM_API_KEY"))
    (some? (System/getenv "BLOCKETHER_OPENAI_API_KEY"))
    (some? (System/getenv "OPENAI_API_KEY"))))

(defn- make-integration-router
  "Creates a router for real LLM calls from env vars.
   Asserts if no API key — integration tests must have credentials."
  []
  (let [api-key (or (System/getenv "BLOCKETHER_LLM_API_KEY")
                  (System/getenv "BLOCKETHER_OPENAI_API_KEY")
                  (System/getenv "OPENAI_API_KEY"))
        _ (assert api-key "Set BLOCKETHER_LLM_API_KEY, BLOCKETHER_OPENAI_API_KEY, or OPENAI_API_KEY to run integration tests")
        base-url (or (System/getenv "BLOCKETHER_LLM_API_BASE_URL")
                   (System/getenv "BLOCKETHER_OPENAI_BASE_URL")
                   (System/getenv "OPENAI_BASE_URL")
                   "https://api.openai.com/v1")]
    (svar/make-router [{:id :integration
                        :api-key api-key
                        :base-url base-url
                        :models [{:name "gpt-4o"}]}])))

(defn- blockether-one-enabled?
  "Returns true if Blockether One LLM endpoint is configured."
  []
  (some? (System/getenv "BLOCKETHER_LLM_API_KEY")))

(defdescribe blockether-one-glm-test
  (describe "ask! with glm-5.1 via Blockether One"
    (it "parses structured JSON from GLM 5.1"
      (when (blockether-one-enabled?)
        (let [router (svar/make-router [{:id :blockether-integration
                                         :api-key (System/getenv "BLOCKETHER_LLM_API_KEY")
                                         :base-url (or (System/getenv "BLOCKETHER_LLM_API_BASE_URL")
                                                     "https://llm.blockether.com/v1")
                                         :models [{:name "glm-5.1"}]}])
              person-spec (svar/spec
                            (svar/field svar/NAME :name
                              svar/TYPE svar/TYPE_STRING
                              svar/CARDINALITY svar/CARDINALITY_ONE
                              svar/DESCRIPTION "Full name")
                            (svar/field svar/NAME :age
                              svar/TYPE svar/TYPE_INT
                              svar/CARDINALITY svar/CARDINALITY_ONE
                              svar/DESCRIPTION "Age in years"))
              result (svar/ask! router {:spec person-spec
                                        :messages [(svar/system "Extract person data from the text.")
                                                   (svar/user "Alexander is 18 years old.")]
                                        :model "glm-5.1"})]
          ;; ask! returns {:result <data> :tokens :cost :duration-ms}
          (expect (map? result))
          (expect (some? (:result result)))
          (expect (some? (:tokens result)))
          (expect (some? (:cost result)))
          (expect (pos? (:duration-ms result)))

          ;; Parsed data matches expected structure
          (let [data (:result result)]
            (expect (= "Alexander" (:name data)))
            (expect (= 18 (:age data)))))))))

;; =============================================================================
;; Streaming Integration Tests (real LLM calls)
;; =============================================================================

(def ^:private streaming-spec
  (svar/spec
    (svar/field svar/NAME :title       svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_ONE svar/DESCRIPTION "Title of the discovery")
    (svar/field svar/NAME :scientist   svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_ONE svar/DESCRIPTION "Name of the scientist")
    (svar/field svar/NAME :year        svar/TYPE svar/TYPE_INT    svar/CARDINALITY svar/CARDINALITY_ONE svar/DESCRIPTION "Year of discovery")
    (svar/field svar/NAME :significance svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_ONE svar/DESCRIPTION "Why this discovery matters")
    (svar/field svar/NAME :related-fields svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_MANY svar/DESCRIPTION "Scientific fields impacted")))

(def ^:private event-spec
  (svar/spec
    (svar/field svar/NAME :event        svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_ONE  svar/DESCRIPTION "Name of the historical event")
    (svar/field svar/NAME :date         svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_ONE  svar/DESCRIPTION "Date or date range")
    (svar/field svar/NAME :location     svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_ONE  svar/DESCRIPTION "Geographic location")
    (svar/field svar/NAME :key-figures  svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_MANY svar/DESCRIPTION "Important people involved")
    (svar/field svar/NAME :causes       svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_MANY svar/DESCRIPTION "Main causes or triggers")
    (svar/field svar/NAME :consequences svar/TYPE svar/TYPE_STRING svar/CARDINALITY svar/CARDINALITY_MANY svar/DESCRIPTION "Major consequences and lasting impact")
    (svar/field svar/NAME :death-toll   svar/TYPE svar/TYPE_INT    svar/CARDINALITY svar/CARDINALITY_ONE  svar/DESCRIPTION "Estimated number of casualties")))

(defdescribe streaming-integration-test
  (describe "streaming penicillin extraction"
    (it "streams partial spec fields progressively"
      (when (integration-tests-enabled?)
        (let [router (make-integration-router)
              chunks (atom [])
              result (svar/ask! router
                       {:spec streaming-spec
                        :messages [(svar/system "Extract the requested information.")
                                   (svar/user "In 1928, Alexander Fleming discovered penicillin when mold contaminated his petri dish and killed bacteria. This revolutionized medicine with antibiotics, saving millions. It impacted microbiology, pharmacology, infectious disease medicine, and public health.")]
                        :model "gpt-4o"
                        :on-chunk (fn [chunk]
                                    (swap! chunks conj chunk)
                                    (println "CHUNK" (count @chunks) "=>" (:result chunk)))})]
          (println "\n=== STREAMING RESULT ===")
          (println "Total chunks:" (count @chunks))
          (println "Final:" (:result result))
          (println "Tokens:" (:tokens result) "Cost:" (:cost result))
          (println "========================\n")
          (expect (some? (:result result)))
          (expect (string? (get-in result [:result :title])))
          (expect (integer? (get-in result [:result :year])))
          (expect (pos? (count @chunks)))
          ;; Reasoning-only chunks may have :result nil; content chunks must have :result
          (expect (some :result @chunks))))))

  (describe "streaming French Revolution extraction (7 fields)"
    (it "streams a complex multi-field spec"
      (when (integration-tests-enabled?)
        (let [router (make-integration-router)
              chunks (atom [])
              result (svar/ask! router
                       {:spec event-spec
                        :messages [(svar/system "Extract detailed structured information about the historical event.")
                                   (svar/user "The French Revolution began in 1789 at Versailles. Fiscal crisis, crop failures, and Enlightenment ideals fueled anger against the monarchy. Key figures: Louis XVI, Marie Antoinette, Robespierre, Danton, Lafayette. The Bastille fell July 14 1789. Results: abolition of feudalism, Declaration of Rights of Man, execution of Louis XVI in 1793, Reign of Terror (~17,000 executions), Napoleon's rise. Total death toll ~40,000. It transformed French society, inspired democracy worldwide, created the metric system and modern citizenship.")]
                        :model "gpt-4o"
                        :on-chunk (fn [chunk]
                                    (swap! chunks conj chunk)
                                    (let [r (:result chunk)
                                          filled (count (filter some? (vals r)))]
                                      (println (str "CHUNK " (count @chunks)
                                                 " [" filled "/7] "
                                                 "event=" (:event r)
                                                 " toll=" (:death-toll r)))))})]
          (println "\n=== COMPLEX STREAMING ===")
          (println "Total chunks:" (count @chunks))
          (println "Final:" (:result result))
          (println "Tokens:" (:tokens result) "Cost:" (:cost result))
          (println "=========================\n")
          (expect (some? (:result result)))
          (expect (string? (get-in result [:result :event])))
          (expect (vector? (get-in result [:result :key-figures])))
          (expect (vector? (get-in result [:result :consequences])))
          (expect (integer? (get-in result [:result :death-toll])))
          (expect (pos? (count @chunks)))
          ;; Reasoning-only chunks may have :result nil; content chunks must have :result
          (expect (some #(map? (:result %)) @chunks)))))))
