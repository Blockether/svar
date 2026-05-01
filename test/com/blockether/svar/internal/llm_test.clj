(ns com.blockether.svar.internal.llm-test
  "Tests for router model selection, preferences, and fallback logic."
  (:require
   [babashka.http-client :as http]
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.core :as svar]
   [com.blockether.svar.internal.llm :as sut])
  (:import
   (java.io ByteArrayInputStream)))

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
    (it "selects the most intelligent model across the whole fleet"
      ;; Cross-provider ranking: provider-b's `genius` (:frontier) outranks
      ;; provider-a's `big` (:high) even though provider-a has higher priority.
      ;; Priority is a tiebreaker WITHIN the same model tier, not a dominant
      ;; sort key. See `candidate-sort-key` in router.clj.
      (expect (= "genius" (selected-model (make-router) {:prefer :intelligence})))))

  (describe "prefer :speed"
    (it "selects fastest model"
      (expect (= "tiny" (selected-model (make-router) {:prefer :speed}))))))

(defdescribe vector-prefer-test
  (describe "[:cost :speed]"
    (it "cheapest first, fastest as tiebreaker"
      (expect (= "tiny" (selected-model (make-router) {:prefer [:cost :speed]})))))

  (describe "[:intelligence :cost]"
    (it "smartest across the fleet, cheapest as tiebreaker within tier"
      ;; :frontier beats :high regardless of provider priority.
      (expect (= "genius" (selected-model (make-router) {:prefer [:intelligence :cost]})))))

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

    (it "smartest vision-capable model across the fleet"
      ;; `genius` (frontier + vision) in provider-b beats `big` (high + vision)
      ;; in provider-a despite priority ordering.
      (expect (= "genius" (selected-model (make-router) {:prefer :intelligence :capabilities #{:vision}})))))

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

(defdescribe transparent-openai-responses-routing-test
  (describe "ask! / ask-code! transparency"
    (it "ask! uses provider-level responses-path, headers, pricing, context, and dynamic verbosity"
      (let [calls (atom [])
            router (svar/make-router
                     [{:id :openai-codex
                       :api-key "sk-test"
                       :llm-headers {"chatgpt-account-id" "acct_123"}
                       :models [{:name "gpt-5.5"}]}])
            answer-spec (svar/spec
                          (svar/field svar/NAME :answer
                            svar/TYPE svar/TYPE_STRING
                            svar/CARDINALITY svar/CARDINALITY_ONE
                            svar/DESCRIPTION "answer"))
            [provider model] (sut/select-provider router {:strategy :root})]
        (expect (= :openai-compatible-responses (:api-style provider)))
        (expect (= "/codex/responses" (:responses-path provider)))
        (expect (= {:input 5.00 :output 30.00} (:pricing model)))
        (expect (= 400000 (:context model)))
        (with-redefs-fn {#'sut/http-post! (fn [url body headers _timeout-ms]
                                            (swap! calls conj {:url url :body body :headers headers})
                                            {:parsed {:output [{:type "message"
                                                                :content [{:type "output_text"
                                                                           :text "{\"answer\":\"ok\"}"}]}]
                                                      :usage {:input_tokens 10
                                                              :output_tokens 5
                                                              :total_tokens 15}}
                                             :raw-body "{}"
                                             :url url
                                             :status 200})}
          (fn []
            (let [result (svar/ask! router
                           {:spec answer-spec
                            :messages [(svar/system "Return JSON.")
                                       (svar/user "Reply ok")]
                            :json-object-mode? true
                            :verbosity :high})
                  {:keys [url body headers]} (first @calls)]
              (expect (= "ok" (get-in result [:result :answer])))
              (expect (= "https://chatgpt.com/backend-api/codex/responses" url))
              (expect (= "acct_123" (get headers "chatgpt-account-id")))
              (expect (= false (:store body)))
              (expect (= ["reasoning.encrypted_content"] (:include body)))
              (expect (= "high" (get-in body [:text :verbosity])))
              (expect (= {:type "json_object"}
                        (get-in body [:text :format]))))))))

    (it "ask-code! reaches the same transport without caller glue code"
      (let [calls (atom [])
            router (svar/make-router
                     [{:id :openai-codex
                       :api-key "sk-test"
                       :llm-headers {"chatgpt-account-id" "acct_123"}
                       :models [{:name "gpt-5.5"}]}])]
        (with-redefs-fn {#'sut/http-post! (fn [url body headers _timeout-ms]
                                            (swap! calls conj {:url url :body body :headers headers})
                                            {:parsed {:output [{:type "message"
                                                                :content [{:type "output_text"
                                                                           :text "```clojure\n(+ 1 1)\n```"}]}]
                                                      :usage {:input_tokens 10
                                                              :output_tokens 5
                                                              :total_tokens 15}}
                                             :raw-body "{}"
                                             :url url
                                             :status 200})}
          (fn []
            (let [result (svar/ask-code! router {:messages [(svar/user "Return code")]})
                  {:keys [url body headers]} (first @calls)]
              (expect (= "(+ 1 1)" (:result result)))
              (expect (= "https://chatgpt.com/backend-api/codex/responses" url))
              (expect (= "acct_123" (get headers "chatgpt-account-id")))
              (expect (= "low" (get-in body [:text :verbosity]))))))))))

(defdescribe response-output-fallback-test
  (describe "responses-url"
    (it "rewrites chat-completions URLs and preserves existing responses URLs"
      (expect (= "https://example.invalid/v1/responses"
                (sut/responses-url "https://example.invalid/v1/chat/completions")))
      (expect (= "https://chatgpt.com/backend-api/codex/responses"
                (sut/responses-url "https://chatgpt.com/backend-api/codex" "/codex/responses")))
      (expect (= "https://chatgpt.com/backend-api/codex/responses"
                (sut/responses-url "https://chatgpt.com/backend-api/codex/responses" "/codex/responses")))))

  (describe "non-stream response fallback"
    (it "responses transport extracts content + reasoning from terminal :output payload"
      (with-redefs-fn {#'sut/http-post! (fn [_url _body _headers _timeout-ms]
                                          {:parsed {:output [{:type "reasoning"
                                                              :summary [{:text "plan first"}]}
                                                             {:type "message"
                                                              :content [{:type "output_text"
                                                                         :text "{\"answer\":\"ok\"}"}]}]
                                                    :usage {:prompt_tokens 11
                                                            :completion_tokens 7
                                                            :total_tokens 18}}
                                           :raw-body "{}"
                                           :url "https://example.invalid/v1/responses"
                                           :status 200})}
        (fn []
          (let [result (sut/openai-responses-completion
                         {:model "test-model"
                          :input [{:role "user" :content [{:type "input_text" :text "hi"}]}]}
                         {:api-key "sk-test"
                          :base-url "https://example.invalid/v1"})]
            (expect (= "{\"answer\":\"ok\"}" (:content result)))
            (expect (= "plan first" (:reasoning result)))
            (expect (= 11 (get-in result [:api-usage :prompt_tokens])))
            (expect (= 200 (get-in result [:http-response :status]))))))))

  (describe "stream response fallback"
    (it "responses transport backfills terminal reasoning without duplicating prior content"
      (let [events (atom [])
            stream (str
                     "data: {\"type\":\"response.output_text.delta\",\"delta\":\"(def x 1)\"}\n\n"
                     "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"reasoning\",\"summary\":[{\"text\":\"plan first\"}]},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"(def x 1)\"}]}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":4,\"total_tokens\":9}}}\n\n"
                     "data: [DONE]\n\n")]
        (with-redefs [http/post (fn [_url _opts]
                                  {:status 200
                                   :body (ByteArrayInputStream. (.getBytes stream "UTF-8"))})]
          (let [result (sut/openai-responses-completion
                         {:model "test-model"
                          :input [{:role "user" :content [{:type "input_text" :text "hi"}]}]}
                         {:api-key "sk-test"
                          :base-url "https://example.invalid/v1"
                          :on-chunk #(swap! events conj %)})]
            (expect (= "(def x 1)" (:content result)))
            (expect (= "plan first" (:reasoning result)))
            (expect (= 5 (get-in result [:api-usage :prompt_tokens])))
            (expect (= "(def x 1)" (:content (first @events))))
            (expect (= "" (:reasoning (first @events))))
            (expect (= "plan first" (:reasoning (second @events))))
            (expect (= "(def x 1)" (:content (second @events))))))))))
