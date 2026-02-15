(ns com.blockether.svar.internal.config-test
  "Tests for LLM configuration management."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.config :as config]))

(defdescribe make-config-test
  "Tests for make-config function"

  (describe "with explicit params"
            (it "creates config with api-key and base-url"
                (let [cfg (config/make-config {:api-key "sk-test-key"
                                               :base-url "https://api.openai.com/v1"})]
                  (expect (= "sk-test-key" (:api-key cfg)))
                  (expect (= "https://api.openai.com/v1" (:base-url cfg)))
                  (expect (= config/DEFAULT_MODEL (:model cfg)))))

            (it "allows custom model"
                (let [cfg (config/make-config {:api-key "sk-test"
                                               :base-url "https://api.example.com"
                                               :model "gpt-4o-mini"})]
                  (expect (= "gpt-4o-mini" (:model cfg)))))

            (it "sets default timeout-ms in :network"
                (let [cfg (config/make-config {:api-key "sk-test"
                                               :base-url "https://api.example.com"})]
                  (expect (= config/DEFAULT_TIMEOUT_MS (get-in cfg [:network :timeout-ms])))))

            (it "allows custom timeout-ms via :network"
                (let [cfg (config/make-config {:api-key "sk-test"
                                               :base-url "https://api.example.com"
                                               :network {:timeout-ms 300000}})]
                  (expect (= 300000 (get-in cfg [:network :timeout-ms])))))

            (it "sets check-context? to true by default in :tokens"
                (let [cfg (config/make-config {:api-key "sk-test"})]
                  (expect (true? (get-in cfg [:tokens :check-context?])))))

            (it "allows disabling check-context? via :tokens"
                (let [cfg (config/make-config {:api-key "sk-test"
                                               :tokens {:check-context? false}})]
                  (expect (false? (get-in cfg [:tokens :check-context?])))))

            (it "merges network over defaults"
                (let [cfg (config/make-config {:api-key "sk-test"
                                               :network {:max-retries 10}})]
                  (expect (= 10 (get-in cfg [:network :max-retries])))
                  ;; Other defaults preserved
                  (expect (= 1000 (get-in cfg [:network :initial-delay-ms])))))

            (it "merges pricing over defaults in :tokens"
                (let [cfg (config/make-config {:api-key "sk-test"
                                               :tokens {:pricing {"my-model" {:input 1.0 :output 2.0}}}})]
                  (expect (= {:input 1.0 :output 2.0} (get-in cfg [:tokens :pricing "my-model"])))
                  ;; Built-in defaults still present
                  (expect (some? (get-in cfg [:tokens :pricing "gpt-4o"])))))

            (it "merges context-limits over defaults in :tokens"
                (let [cfg (config/make-config {:api-key "sk-test"
                                               :tokens {:context-limits {"my-model" 65536}}})]
                  (expect (= 65536 (get-in cfg [:tokens :context-limits "my-model"])))
                  ;; Built-in defaults still present
                  (expect (some? (get-in cfg [:tokens :context-limits "gpt-4o"]))))))

  (describe "with missing params"
            (it "throws on missing api-key when no env var set"
                (if (or (System/getenv "BLOCKETHER_OPENAI_API_KEY")
                        (System/getenv "OPENAI_API_KEY"))
                  (expect true) ;; Skip when any api-key env var is set
                  (try
                    (config/make-config {})
                    (expect false "Should have thrown")
                    (catch clojure.lang.ExceptionInfo e
                      (expect (= :svar/missing-api-key (:type (ex-data e))))))))

            (it "falls back to env var for base-url"
                (let [cfg (config/make-config {:api-key "sk-test"})
                      expected-url (or (System/getenv "BLOCKETHER_OPENAI_BASE_URL")
                                       (System/getenv "OPENAI_BASE_URL")
                                       config/DEFAULT_BASE_URL)]
                  (expect (= expected-url (:base-url cfg)))))))

(defdescribe default-model-test
  "Tests for DEFAULT_MODEL constant"

  (it "DEFAULT_MODEL is gpt-4o"
      (expect (= "gpt-4o" config/DEFAULT_MODEL))))
