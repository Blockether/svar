(ns com.blockether.svar.internal.format-retry-test
  "Tests for `:format-retries` on `ask!*` — in-process retry when the
   provider returns content that fails schema parsing.

   Mocks `llm/chat-completion` to return canned responses deterministically,
   so we can pin the retry contract without paying for real LLM calls:

     - first response prose, second valid → success with `:format-attempts`
     - all responses prose → terminal throw with full envelope
     - non-retryable types do not retry
     - streaming forces retries to 0
     - `:json-object-mode?` injects `response_format` on `:openai-compatible-chat` only
     - caller `:extra-body :response_format` always wins
     - error envelope is full (no truncation)

   Live equivalents in `format_retry_zai_live_test.clj` exercise the same
   contract end-to-end against GLM-5.1, but those are gated on a real key."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.core :as svar]
   [com.blockether.svar.internal.llm :as llm]))

;; =============================================================================
;; Helpers — mock chat-completion
;; =============================================================================

(defn- mock-chat-completion
  "Returns a function suitable for `with-redefs` on `llm/chat-completion`
   that emits the next response from `responses` per call. Each response is
   a map with at minimum `:content`. `:reasoning`, `:api-usage`,
   `:http-response` are passed through if present.

   Also captures every call into `calls-atom` so tests can assert on the
   request shape (notably `:extra-body :response_format`)."
  [responses calls-atom]
  (let [idx (atom 0)
        rs  (vec responses)]
    (fn [messages model api-key url retry-opts]
      (let [n (long @idx)
            response (or (get rs n) (last rs))]
        (swap! calls-atom conj
          {:messages messages :model model :api-key api-key
           :url url :retry-opts retry-opts
           :attempt n})
        (swap! idx inc)
        (merge {:content       (:content response "")
                :reasoning     (:reasoning response)
                :api-usage     (:api-usage response {:prompt_tokens 10 :completion_tokens 20 :total_tokens 30})
                :http-response (:http-response response {:status 200 :url url})}
          (select-keys response [:content :reasoning :api-usage :http-response]))))))

(defn- test-router []
  (svar/make-router
    [{:id :test
      :api-key "sk-test"
      :base-url "https://example.invalid/v1"
      :api-style :openai-compatible-chat
      :models [{:name "test-model"}]}]))

(def ^:private answer-spec
  (svar/spec
    (svar/field svar/NAME :answer
      svar/TYPE svar/TYPE_STRING
      svar/CARDINALITY svar/CARDINALITY_ONE
      svar/DESCRIPTION "The single-word answer")))

;; =============================================================================
;; Format retry — happy-path retry recovers from prose
;; =============================================================================

(defdescribe format-retry-recovers-on-second-attempt-test
  (describe ":format-retries 2 with first-response-prose, second-response-valid"
    (it "succeeds and exposes :format-attempts with the failed attempt recorded"
      (let [calls (atom [])
            responses [{:content "Looking at what I have so far, I need to think..."}
                       {:content "{\"answer\":\"ok\"}"}]]
        (with-redefs [llm/chat-completion (mock-chat-completion responses calls)]
          (let [result (svar/ask! (test-router)
                         {:spec answer-spec
                          :messages [(svar/user "Reply with the word ok.")]
                          :format-retries 2})]
            ;; Final result reflects the second (valid) response
            (expect (= "ok" (get-in result [:result :answer])))
            ;; Two HTTP calls were made — one bad, one good
            (expect (= 2 (count @calls)))
            ;; Forensic record of every attempt is preserved on the result
            (expect (vector? (:format-attempts result)))
            (expect (= 2 (count (:format-attempts result))))
            (let [[fail-att succ-att] (:format-attempts result)]
              (expect (false? (:ok? fail-att)))
              (expect (true? (:ok? succ-att)))
              ;; Full content of the failed attempt is preserved verbatim,
              ;; not truncated.
              (expect (= "Looking at what I have so far, I need to think..."
                        (:content fail-att)))
              ;; Reason captured from the spec rejection
              (expect (= :not-a-map (:reason fail-att)))
              (expect (= "String" (:received-type fail-att))))
            ;; Second call's messages should include the FORMAT RETRY turn —
            ;; assistant block carrying the bad content + user retry prompt.
            (let [retry-msgs (:messages (second @calls))
                  last-two (take-last 2 retry-msgs)]
              (expect (= ["assistant" "user"] (mapv :role last-two)))
              (expect (re-find #"FORMAT RETRY"
                        (get-in (second last-two) [:content 0 :text]))))))))))

;; =============================================================================
;; Format retry — exhausted retries throws with FULL envelope
;; =============================================================================

(defdescribe format-retry-exhausted-throws-with-envelope-test
  (describe ":format-retries 1 with both responses prose"
    (it "throws after 2 attempts (initial + 1 retry) with full forensic envelope"
      (let [calls (atom [])
            ;; A long prose blob to verify NO truncation happens.
            long-prose (apply str "Looking at what I have so far, " (repeat 200 "blah blah "))
            responses [{:content long-prose}
                       {:content long-prose}]]
        (with-redefs [llm/chat-completion (mock-chat-completion responses calls)]
          (let [thrown (try (svar/ask! (test-router)
                              {:spec answer-spec
                               :messages [(svar/user "Reply with the word ok.")]
                               :format-retries 1})
                            ::no-throw
                            (catch clojure.lang.ExceptionInfo e e))]
            (expect (not= ::no-throw thrown))
            (let [data (ex-data thrown)]
              (expect (= :svar.spec/schema-rejected (:type data)))
              ;; Envelope merged in
              (expect (= "test-model" (:model data)))
              (expect (= :openai-compatible-chat (:api-style data)))
              (expect (some? (:chat-url data)))
              (expect (some? (:http-response data)))
              ;; Full content preserved (NO truncation — bug report demand)
              (expect (= long-prose (:content data)))
              ;; Format attempts vector with both bad attempts
              (expect (vector? (:format-attempts data)))
              (expect (= 2 (count (:format-attempts data))))
              (expect (every? (complement :ok?) (:format-attempts data)))
              (expect (= 1 (:format-retries-attempted data)))
              (expect (= 1 (:format-retries-allowed data)))
              ;; Each attempt's content is the FULL untruncated string
              (doseq [att (:format-attempts data)]
                (expect (= long-prose (:content att)))))
            (expect (= 2 (count @calls)))))))))

;; =============================================================================
;; Format retry — :format-retries 0 (default) throws on first failure
;; =============================================================================

(defdescribe format-retry-zero-throws-immediately-test
  (describe ":format-retries 0 (default) on first prose response"
    (it "throws after a single attempt with full envelope"
      (let [calls (atom [])
            responses [{:content "Looking at what I have so far"}
                       ;; never reached
                       {:content "{\"answer\":\"ok\"}"}]]
        (with-redefs [llm/chat-completion (mock-chat-completion responses calls)]
          (let [thrown (try (svar/ask! (test-router)
                              {:spec answer-spec
                               :messages [(svar/user "Reply ok")]})
                            ::no-throw
                            (catch clojure.lang.ExceptionInfo e e))]
            (expect (not= ::no-throw thrown))
            (let [data (ex-data thrown)]
              (expect (= :svar.spec/schema-rejected (:type data)))
              (expect (= "Looking at what I have so far" (:content data)))
              (expect (= 1 (count (:format-attempts data))))
              (expect (= 0 (:format-retries-attempted data)))
              (expect (= 0 (:format-retries-allowed data))))
            ;; Critical: only ONE call. No silent extra retry.
            (expect (= 1 (count @calls)))))))))

;; =============================================================================
;; Format retry — non-retryable type does NOT retry
;; =============================================================================

(defdescribe format-retry-only-fires-on-listed-types-test
  (describe ":svar.llm/empty-content with default :format-retry-on"
    (it "does not retry empty-content by default"
      (let [calls (atom [])
            responses [{:content nil}
                       {:content "{\"answer\":\"ok\"}"}]]
        (with-redefs [llm/chat-completion (mock-chat-completion responses calls)]
          (let [thrown (try (svar/ask! (test-router)
                              {:spec answer-spec
                               :messages [(svar/user "Reply ok")]
                               :format-retries 3})
                            ::no-throw
                            (catch clojure.lang.ExceptionInfo e e))]
            (expect (not= ::no-throw thrown))
            (expect (= :svar.llm/empty-content (:type (ex-data thrown))))
            (expect (= 1 (count @calls))))))))

  (describe ":svar.llm/empty-content with :format-retry-on extended"
    (it "retries empty-content when caller opts in"
      (let [calls (atom [])
            responses [{:content nil :reasoning "thinking..."}
                       {:content "{\"answer\":\"opted-in\"}"}]]
        (with-redefs [llm/chat-completion (mock-chat-completion responses calls)]
          (let [result (svar/ask! (test-router)
                         {:spec answer-spec
                          :messages [(svar/user "Reply")]
                          :format-retries 2
                          :format-retry-on #{:svar.spec/schema-rejected
                                             :svar.spec/required-field-missing
                                             :svar.llm/empty-content}})]
            (expect (= "opted-in" (get-in result [:result :answer])))
            (expect (= 2 (count @calls)))
            (let [first-att (first (:format-attempts result))]
              (expect (false? (:ok? first-att)))
              (expect (= :svar.llm/empty-content (:ex-type first-att))))))))))

;; =============================================================================
;; JSON-object-mode auto-injection
;; =============================================================================

(defdescribe json-object-mode-injects-response-format-test
  (describe ":json-object-mode? true with :openai-compatible-chat api-style"
    (it "auto-injects response_format: {type: \"json_object\"} into request body"
      (let [calls (atom [])
            responses [{:content "{\"answer\":\"yes\"}"}]]
        (with-redefs [llm/chat-completion (mock-chat-completion responses calls)]
          (svar/ask! (test-router)
            {:spec answer-spec
             :messages [(svar/user "Reply yes")]
             :json-object-mode? true})
          (let [extra-body (get-in (first @calls) [:retry-opts :extra-body])]
            (expect (= {:type "json_object"} (:response_format extra-body))))))))

  (describe ":json-object-mode? false on a flagged model"
    (it "explicitly opts out — no response_format injected"
      ;; Real `:zai` so the model picks up `:json-object-mode? true` via
      ;; metadata; caller's explicit `false` MUST override it.
      (let [calls (atom [])
            responses [{:content "{\"answer\":\"x\"}"}]
            r (svar/make-router
                [{:id :zai
                  :api-key "sk-test"
                  :base-url "https://example.invalid/v1"
                  :api-style :openai-compatible-chat
                  :models [{:name "glm-5.1"}]}])]
        (with-redefs [llm/chat-completion (mock-chat-completion responses calls)]
          (svar/ask! r
            {:spec answer-spec
             :messages [(svar/user "Reply x")]
             :json-object-mode? false})
          (let [extra-body (get-in (first @calls) [:retry-opts :extra-body])]
            (expect (nil? (:response_format extra-body))))))))

  (describe ":json-object-mode? auto-from-model-metadata"
    (it "GLM model metadata flag propagates without explicit caller opt"
      ;; Use the real `:zai` provider id so `KNOWN_PROVIDER_MODELS` merges
      ;; the `:json-object-mode? true` flag onto glm-5.1 during
      ;; `normalize-provider`. With a synthetic provider id (e.g. `:test`)
      ;; that lookup returns nil and the flag never lands on the model.
      (let [calls (atom [])
            responses [{:content "{\"answer\":\"glm\"}"}]
            r (svar/make-router
                [{:id :zai
                  :api-key "sk-test"
                  :base-url "https://example.invalid/v1"
                  :api-style :openai-compatible-chat
                  :models [{:name "glm-5.1"}]}])]
        (with-redefs [llm/chat-completion (mock-chat-completion responses calls)]
          (svar/ask! r
            {:spec answer-spec
             :messages [(svar/user "Reply glm")]})
          (let [extra-body (get-in (first @calls) [:retry-opts :extra-body])]
            (expect (= {:type "json_object"} (:response_format extra-body))))))))

  (describe "caller :extra-body :response_format always wins"
    (it "explicit caller-provided response_format is preserved verbatim"
      (let [calls (atom [])
            responses [{:content "{\"answer\":\"y\"}"}]
            custom-rf {:type "json_schema" :json_schema {:name "custom"}}]
        (with-redefs [llm/chat-completion (mock-chat-completion responses calls)]
          (svar/ask! (test-router)
            {:spec answer-spec
             :messages [(svar/user "Reply y")]
             :json-object-mode? true ;; would normally inject
             :extra-body {:response_format custom-rf}})
          (let [extra-body (get-in (first @calls) [:retry-opts :extra-body])]
            ;; Caller's value preserved, NOT clobbered by auto-injection
            (expect (= custom-rf (:response_format extra-body)))))))))

;; =============================================================================
;; Provider fallback on format error (`:on-format-error :fallback-provider`)
;; =============================================================================

(defn- mock-chat-completion-by-model
  "Variant of `mock-chat-completion` that dispatches on `model` per call so
   we can simulate one model returning prose forever and another returning
   valid JSON. Captures every call into `calls-atom`."
  [model->responses calls-atom]
  (let [idx (atom {})]
    (fn [messages model api-key url retry-opts]
      (let [n (long (get @idx model 0))
            rs (vec (get model->responses model []))
            response (or (get rs n) (last rs))]
        (swap! calls-atom conj
          {:messages messages :model model :api-key api-key
           :url url :retry-opts retry-opts :attempt n})
        (swap! idx update model (fnil inc 0))
        (merge {:content       (:content response "")
                :reasoning     (:reasoning response)
                :api-usage     (:api-usage response {:prompt_tokens 10 :completion_tokens 20 :total_tokens 30})
                :http-response (:http-response response {:status 200 :url url})}
          (select-keys response [:content :reasoning :api-usage :http-response]))))))

(defdescribe on-format-error-fallback-provider-test
  (describe ":on-format-error :fallback-provider with two providers, first emits prose"
    (it "falls back to second provider after first's format error"
      (let [calls (atom [])
            ;; Provider :p1 always emits prose -> schema-rejected.
            ;; Provider :p2 emits valid JSON.
            r (svar/make-router
                [{:id :openai
                  :api-key "sk-1"
                  :base-url "https://example.invalid/v1"
                  :api-style :openai-compatible-chat
                  :models [{:name "glm-5.1"}]}
                 {:id :anthropic
                  :api-key "sk-2"
                  :base-url "https://anthropic.invalid/v1"
                  :api-style :openai-compatible-chat ;; force openai-compatible-chat shape so spec parsing path is identical
                  :models [{:name "claude-haiku-4-5"}]}])]
        (with-redefs [llm/chat-completion
                      (mock-chat-completion-by-model
                        {"glm-5.1"          [{:content "Looking at the request..."}]
                         "claude-haiku-4-5" [{:content "{\"answer\":\"recovered\"}"}]}
                        calls)]
          (let [result (svar/ask! r
                         {:spec answer-spec
                          :messages [(svar/user "Reply")]
                          :on-format-error :fallback-provider})]
            (expect (= "recovered" (get-in result [:result :answer])))
            ;; Routed model should be the fallback, not the offender
            (expect (= "claude-haiku-4-5" (:routed/model result)))
            ;; Fallback trace records the format-error stop
            (let [trace (:routed/fallback-trace result)]
              (expect (some #(= :format-error (:reason %)) trace))
              (expect (some #(= :openai (:provider-id %)) trace))))))))

  (describe ":on-format-error :fallback-provider with all providers failing"
    (it "throws the LAST format error verbatim with envelope + fallback-trace"
      (let [calls (atom [])
            r (svar/make-router
                [{:id :openai
                  :api-key "sk-1"
                  :base-url "https://a.invalid/v1"
                  :api-style :openai-compatible-chat
                  :models [{:name "glm-5.1"}]}
                 {:id :anthropic
                  :api-key "sk-2"
                  :base-url "https://b.invalid/v1"
                  :api-style :openai-compatible-chat
                  :models [{:name "claude-haiku-4-5"}]}])]
        (with-redefs [llm/chat-completion
                      (mock-chat-completion-by-model
                        {"glm-5.1"          [{:content "prose-from-glm"}]
                         "claude-haiku-4-5" [{:content "prose-from-claude"}]}
                        calls)]
          (let [thrown (try (svar/ask! r
                              {:spec answer-spec
                               :messages [(svar/user "Reply")]
                               :on-format-error :fallback-provider})
                            ::no-throw
                            (catch clojure.lang.ExceptionInfo e e))]
            (expect (not= ::no-throw thrown))
            (let [data (ex-data thrown)]
              ;; The original format-error type is preserved (not wrapped
              ;; under :svar.llm/all-providers-exhausted).
              (expect (= :svar.spec/schema-rejected (:type data)))
              ;; Fallback-trace records BOTH attempts as :format-error
              (expect (= 2 (count (filter #(= :format-error (:reason %))
                                    (:routed/fallback-trace data)))))
              ;; format-failed set lists both providers
              (expect (= #{:openai :anthropic} (:format-failed data)))))))))

  (describe ":on-format-error :fail (default) does NOT fall back"
    (it "first format error throws immediately, second provider untouched"
      (let [calls (atom [])
            r (svar/make-router
                [{:id :openai
                  :api-key "sk-1"
                  :base-url "https://a.invalid/v1"
                  :api-style :openai-compatible-chat
                  :models [{:name "glm-5.1"}]}
                 {:id :anthropic
                  :api-key "sk-2"
                  :base-url "https://b.invalid/v1"
                  :api-style :openai-compatible-chat
                  :models [{:name "claude-haiku-4-5"}]}])]
        (with-redefs [llm/chat-completion
                      (mock-chat-completion-by-model
                        {"glm-5.1"          [{:content "prose"}]
                         "claude-haiku-4-5" [{:content "{\"answer\":\"never-reached\"}"}]}
                        calls)]
          (let [thrown (try (svar/ask! r
                              {:spec answer-spec
                               :messages [(svar/user "Reply")]})
                            ::no-throw
                            (catch clojure.lang.ExceptionInfo e e))]
            (expect (not= ::no-throw thrown))
            (expect (= :svar.spec/schema-rejected (:type (ex-data thrown))))
            ;; Only the first model was called
            (expect (= 1 (count @calls)))
            (expect (= "glm-5.1" (:model (first @calls))))))))))

;; =============================================================================
;; Format-retry / json-mode propagate through abstract! / eval! / refine!
;; =============================================================================
;;
;; The new opts (`:format-retries`, `:json-object-mode?`, `:on-format-error`,
;; `:format-retry-on`, `:cache-system?`) are svar-level options that any
;; helper invoking `ask!*` internally must forward. We exercise this by
;; mocking `chat-completion` and asserting that the request body ALWAYS
;; carries `response_format: {type: "json_object"}` once `:json-object-mode?
;; true` is set on the public-API call (regardless of which entrypoint it
;; came in through). Retries are exercised by toggling the first response
;; to prose and checking we don't bubble up.

(defdescribe svar-opts-propagate-through-eval-test
  (describe ":json-object-mode? true on `eval!` reaches the wire on every internal call"
    (it "every chat-completion call has response_format json_object set"
      (let [calls (atom [])
            ;; eval! makes one ask!* call internally with the eval spec.
            responses [{:content "{\"overall-score\":0.8,\"correct\":true,\"summary\":\"ok\"}"}]]
        (with-redefs [llm/chat-completion (mock-chat-completion responses calls)]
          (svar/eval! (test-router)
            {:task "Summarize the text."
             :output "A brief summary."
             :json-object-mode? true})
          (expect (pos? (count @calls)))
          (doseq [c @calls]
            (let [extra-body (get-in c [:retry-opts :extra-body])]
              (expect (= {:type "json_object"} (:response_format extra-body))))))))))

(defdescribe svar-opts-propagate-through-refine-test
  (describe ":format-retries 1 on `refine!` absorbs prose on intermediate calls"
    (it "refine!'s internal ask!* uses format-retries to recover from prose"
      ;; Asserts on the wire-level call sequence captured in `calls`. The
      ;; downstream refine! pipeline (decompose -> verify -> eval -> refine)
      ;; can NPE on simplistic mock responses since each phase has its own
      ;; spec; we don't care — the propagation test only needs to prove
      ;; the FIRST internal `ask!*` retried on prose. Wrap in try/catch.
      (let [calls (atom [])
            valid-initial "{\"answer\":\"recovered\"}"
            valid-decomp "{\"claims\":[]}"
            valid-verify "{\"verifications\":[]}"
            valid-eval "{\"overall-score\":1.0,\"correct\":true,\"summary\":\"ok\"}"
            responses [{:content "Looking at the request, I think..."} ;; bad
                       {:content valid-initial}                        ;; format-retry recovery
                       {:content valid-decomp}
                       {:content valid-verify}
                       {:content valid-eval}
                       {:content valid-initial}
                       {:content valid-eval}]]
        (with-redefs [llm/chat-completion (mock-chat-completion responses calls)]
          (try
            (svar/refine! (test-router)
              {:spec answer-spec
               :messages [(svar/user "Reply")]
               :iterations 1
               :format-retries 1})
            (catch Exception _ nil))
          ;; At least 2 wire calls: the prose attempt + the format-retry
          ;; attempt that recovered. The 2nd call MUST carry the format-
          ;; retry conversation turn appended.
          (expect (>= (count @calls) 2))
          (let [retry-msgs (:messages (second @calls))
                last-two  (take-last 2 retry-msgs)]
            (expect (= ["assistant" "user"] (mapv :role last-two)))
            (expect (re-find #"FORMAT RETRY"
                      (get-in (second last-two) [:content 0 :text])))))))))

(defdescribe svar-opts-propagate-through-abstract-test
  (describe ":json-object-mode? true on `abstract!` reaches every internal call"
    (it "all CoD ask!* calls carry response_format json_object"
      (let [calls (atom [])
            ;; abstract! does N CoD iterations + (optional) eval per iter.
            valid-cod "{\"summary\":\"ok\",\"entities\":[{\"entity\":\"X\",\"rationale\":\"r\",\"score\":0.9}]}"
            responses [{:content valid-cod}
                       {:content valid-cod}]]
        (with-redefs [llm/chat-completion (mock-chat-completion responses calls)]
          (svar/abstract! (test-router)
            {:text "The text to summarize. Voyager 1 launched in 1977."
             :iterations 1
             :target-length 30
             :json-object-mode? true})
          (expect (pos? (count @calls)))
          (doseq [c @calls]
            (let [extra-body (get-in c [:retry-opts :extra-body])]
              (expect (= {:type "json_object"} (:response_format extra-body)))))))))

  (describe "default CoD format retries"
    (it "abstract! absorbs one schema-rejected CoD response by default"
      (let [calls (atom [])
            valid-cod "{\"summary\":\"ok\",\"entities\":[{\"entity\":\"X\",\"rationale\":\"r\",\"score\":0.9}]}"
            responses [{:content "I should summarize Voyager first."}
                       {:content valid-cod}]]
        (with-redefs [llm/chat-completion (mock-chat-completion responses calls)]
          (let [result (svar/abstract! (test-router)
                         {:text "The text to summarize. Voyager 1 launched in 1977."
                          :iterations 1
                          :target-length 30})]
            (expect (= "ok" (get-in result [:result 0 :summary])))
            (expect (= 2 (count @calls)))
            (let [retry-msgs (:messages (second @calls))
                  last-two (take-last 2 retry-msgs)]
              (expect (= ["assistant" "user"] (mapv :role last-two)))
              (expect (re-find #"FORMAT RETRY"
                        (get-in (second last-two) [:content 0 :text]))))))))))

;; =============================================================================
;; Streaming + retries — retries forced to 0
;; =============================================================================

(defdescribe streaming-disables-format-retries-test
  (describe ":on-chunk + :format-retries N"
    (it "skips retries when streaming — first failure throws immediately"
      (let [calls (atom [])
            chunks (atom [])
            responses [{:content "Looking at things"}
                       {:content "{\"answer\":\"would-be\"}"}]]
        (with-redefs [llm/chat-completion (mock-chat-completion responses calls)]
          (let [thrown (try (svar/ask! (test-router)
                              {:spec answer-spec
                               :messages [(svar/user "Reply")]
                               :format-retries 3
                               :on-chunk (fn [c] (swap! chunks conj c))})
                            ::no-throw
                            (catch clojure.lang.ExceptionInfo e e))]
            (expect (not= ::no-throw thrown))
            (expect (= 1 (count @calls)))
            (expect (= :svar.spec/schema-rejected (:type (ex-data thrown))))))))))
