(ns com.blockether.svar.internal.llm-test
  "Tests for router model selection, preferences, and fallback logic."
  (:require
   [babashka.http-client :as http]
   [charred.api :as json]
   [clojure.string :as str]
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

(defdescribe provider-models-list-test
  (it "filters OpenAI Codex /models below GPT-5.3"
    (let [router (svar/make-router [{:id :openai-codex
                                     :api-key "sk-test"
                                     :models [{:name "gpt-5.5"}]}])]
      (with-redefs-fn {#'sut/http-get! (fn [_url _api-key]
                                         {:data [{:id "gpt-4o"}
                                                 {:id "gpt-5"}
                                                 {:id "gpt-5.2-codex"}
                                                 {:id "gpt-5.3-codex"}
                                                 {:id "gpt-5.4"}
                                                 {:id "gpt-5.5"}]})}
        (fn []
          (expect (= ["gpt-5.3-codex" "gpt-5.4" "gpt-5.5"]
                    (mapv :id (svar/models! router))))))))

  (it "filters GitHub Copilot /models below GPT-5.3 while keeping non-GPT families"
    (let [router (svar/make-router [{:id :github-copilot
                                     :api-key "sk-test"
                                     :models [{:name "gpt-5.4"}]}])]
      (with-redefs-fn {#'sut/http-get! (fn [_url _api-key]
                                         {:data [{:id "claude-sonnet-4-6"}
                                                 {:id "gpt-4o"}
                                                 {:id "gpt-5.1-codex"}
                                                 {:id "gpt-5.2-codex"}
                                                 {:id "gpt-5.3-codex"}
                                                 {:id "gpt-5.4"}
                                                 {:id "gemini-3-pro-preview"}]})}
        (fn []
          (expect (= ["claude-sonnet-4-6" "gpt-5.3-codex" "gpt-5.4" "gemini-3-pro-preview"]
                    (mapv :id (svar/models! router)))))))))

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
        (with-redefs-fn {#'sut/http-post-stream! (fn [url body headers _timeout-ms _delta-fn on-delta]
                                                   (swap! calls conj {:url url :body body :headers headers :on-delta on-delta})
                                                   {:content "{\"answer\":\"ok\"}"
                                                    :reasoning nil
                                                    :api-usage {:prompt_tokens 10
                                                                :completion_tokens 5
                                                                :total_tokens 15
                                                                :prompt_tokens_details {:cached_tokens 7}}
                                                    :http-response {:url url
                                                                    :streaming? true
                                                                    :status 200}})}
          (fn []
            (let [result (svar/ask! router
                           {:spec answer-spec
                            :messages [(svar/system "Return JSON.")
                                       (svar/user "Reply ok")]
                            :json-object-mode? true
                            :verbosity :high})
                  {:keys [url body headers]} (first @calls)]
              (expect (= "ok" (get-in result [:result :answer])))
              (expect (= 7 (get-in result [:tokens :cached])))
              (expect (= "https://chatgpt.com/backend-api/codex/responses" url))
              (expect (= "acct_123" (get headers "chatgpt-account-id")))
              (expect (= "text/event-stream" (get headers "Accept")))
              (expect (= true (:stream body)))
              (expect (nil? (:max_tokens body)))
              (expect (= false (:store body)))
              (expect (= ["reasoning.encrypted_content"] (:include body)))
              (expect (= {:summary "detailed"} (:reasoning body)))
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
        (with-redefs-fn {#'sut/http-post-stream! (fn [url body headers _timeout-ms _delta-fn on-delta]
                                                   (swap! calls conj {:url url :body body :headers headers :on-delta on-delta})
                                                   {:content "```clojure\n(+ 1 1)\n```"
                                                    :reasoning nil
                                                    :api-usage {:prompt_tokens 10
                                                                :completion_tokens 5
                                                                :total_tokens 15
                                                                :prompt_tokens_details {:cached_tokens 7}}
                                                    :http-response {:url url
                                                                    :streaming? true
                                                                    :status 200}})}
          (fn []
            (let [result (svar/ask-code! router {:messages [(svar/user "Return code")]})
                  {:keys [url body headers]} (first @calls)]
              (expect (= "(+ 1 1)" (:result result)))
              (expect (= 7 (get-in result [:tokens :cached])))
              (expect (= "https://chatgpt.com/backend-api/codex/responses" url))
              (expect (= "acct_123" (get headers "chatgpt-account-id")))
              (expect (= "text/event-stream" (get headers "Accept")))
              (expect (= true (:stream body)))
              (expect (nil? (:max_tokens body)))
              (expect (= {:summary "detailed"} (:reasoning body)))
              (expect (= "low" (get-in body [:text :verbosity]))))))))

    (it "ask-code! merges reasoning effort into nested Responses reasoning options"
      (let [calls (atom [])
            router (svar/make-router
                     [{:id :openai-codex
                       :api-key "sk-test"
                       :llm-headers {"chatgpt-account-id" "acct_123"}
                       :models [{:name "gpt-5.5"}]}])]
        (with-redefs-fn {#'sut/http-post-stream! (fn [url body headers _timeout-ms _delta-fn _on-delta]
                                                   (swap! calls conj {:url url :body body :headers headers})
                                                   {:content "```clojure\n(+ 1 1)\n```"
                                                    :reasoning nil
                                                    :api-usage {:prompt_tokens 10
                                                                :completion_tokens 5
                                                                :total_tokens 15}
                                                    :http-response {:url url
                                                                    :streaming? true
                                                                    :status 200}})}
          (fn []
            (svar/ask-code! router {:messages [(svar/user "Return code")]
                                    :reasoning :quick
                                    :extra-body {:reasoning {:summary "auto"}}})
            (let [{:keys [body]} (first @calls)]
              (expect (= {:effort "low" :summary "auto"}
                        (:reasoning body))))))))

    (it "chat-completion adds GitHub Copilot dynamic headers without replacing static headers"
      (let [seen (atom nil)
            messages [(svar/user "hi")]]
        (with-redefs-fn {#'sut/http-post! (fn [_url _body headers _timeout-ms]
                                            (reset! seen headers)
                                            {:parsed {:choices [{:message {:content "ok"}}]}
                                             :raw-body "{}"
                                             :url "https://example.invalid/v1/chat/completions"
                                             :status 200})}
          (fn []
            (sut/chat-completion messages "gpt-4o" "sk-test" "https://example.invalid/v1"
              {:provider-id :github-copilot
               :llm-headers {"User-Agent" "VisCopilot/0.1"}})
            (expect (= "VisCopilot/0.1" (get @seen "User-Agent")))
            (expect (= "user" (get @seen "X-Initiator")))
            (expect (= "conversation-edits" (get @seen "Openai-Intent")))))))

    (it "GitHub Copilot business chat forces SSE streaming"
      (let [calls (atom [])
            messages [(svar/user "hi")]]
        (with-redefs-fn {#'sut/http-post-stream! (fn [url body headers _timeout-ms _delta-fn on-delta]
                                                   (swap! calls conj {:url url :body body :headers headers :on-delta on-delta})
                                                   {:content "ok"
                                                    :reasoning nil
                                                    :api-usage {}
                                                    :http-response {:url url
                                                                    :streaming? true
                                                                    :status 200}})}
          (fn []
            (let [result (sut/chat-completion messages "gpt-4o" "sk-test" "https://proxy.business.githubcopilot.com"
                           {:provider-id :github-copilot
                            :llm-headers {"User-Agent" "VisCopilot/0.1"}})
                  {:keys [url body headers on-delta]} (first @calls)]
              (expect (= "ok" (:content result)))
              (expect (= "https://proxy.business.githubcopilot.com/chat/completions" url))
              (expect (= true (:stream body)))
              (expect (ifn? on-delta))
              (expect (= "text/event-stream" (get headers "Accept"))))))))

    (it "ask-code! sends prior encrypted reasoning items as Responses input sidecar"
      (let [calls (atom [])
            router (svar/make-router
                     [{:id :openai-codex
                       :api-key "sk-test"
                       :llm-headers {"chatgpt-account-id" "acct_123"}
                       :models [{:name "gpt-5.5"}]}])
            provider-state {:provider :openai-responses
                            :reasoning-items [{:id "rs_1"
                                               :type "reasoning"
                                               :summary []
                                               :encrypted-content "ciphertext"}]}]
        (with-redefs-fn {#'sut/http-post-stream! (fn [url body headers _timeout-ms _delta-fn _on-delta]
                                                   (swap! calls conj {:url url :body body :headers headers})
                                                   {:content "```clojure\n(+ 1 1)\n```"
                                                    :reasoning nil
                                                    :provider-state provider-state
                                                    :api-usage {:prompt_tokens 10
                                                                :completion_tokens 5
                                                                :total_tokens 15}
                                                    :http-response {:url url
                                                                    :streaming? true
                                                                    :status 200}})}
          (fn []
            (let [result (svar/ask-code! router {:messages [(svar/user "Return code")]
                                                 :provider-state provider-state})
                  {:keys [body]} (first @calls)]
              (expect (= {:type "reasoning"
                          :id "rs_1"
                          :summary []
                          :encrypted_content "ciphertext"}
                        (first (:input body))))
              (expect (nil? (:provider-state body)))
              (expect (= "ciphertext"
                        (get-in result [:provider-state :reasoning-items 0 :encrypted-content]))))))))

    (it "ask-code! extracts malformed streamed fences without leaking markers"
      (let [router (svar/make-router
                     [{:id :openai-codex
                       :api-key "sk-test"
                       :models [{:name "gpt-5.5"}]}])
            stream (str
                     "data: {\"type\":\"response.output_text.delta\",\"delta\":\"```clojure\\n```\\n\\n\"}\n\n"
                     "data: {\"type\":\"response.output_text.delta\",\"delta\":\"```clojure\\n(answer \\\"4\\\")```\"}\n\n"
                     "data: [DONE]\n\n")]
        (with-redefs [http/post (fn [_url _opts]
                                  {:status 200
                                   :body (ByteArrayInputStream. (.getBytes stream "UTF-8"))})]
          (let [events (atom [])
                result (svar/ask-code! router {:messages [(svar/user "Return code")]
                                               :on-chunk #(swap! events conj %)})]
            (expect (= "(answer \"4\")" (:result result)))
            (expect (not (re-find #"```" (:result result))))
            (expect (every? #(or (:done? %)
                               (not (str/blank? (:result %))))
                      @events))
            (expect (= "(answer \"4\")" (:result (last @events))))
            (expect (= true (:done? (last @events))))))))

    (it "ask-code! preserves whitespace-only deltas in streamed code"
      (let [router (svar/make-router
                     [{:id :openai-codex
                       :api-key "sk-test"
                       :models [{:name "gpt-5.5"}]}])
            stream (str
                     "data: {\"type\":\"response.output_text.delta\",\"delta\":\"```clojure\\n(v/cat p {:max-lines\"}\n\n"
                     "data: {\"type\":\"response.output_text.delta\",\"delta\":\" \"}\n\n"
                     "data: {\"type\":\"response.output_text.delta\",\"delta\":\"260})\\n```\"}\n\n"
                     "data: [DONE]\n\n")]
        (with-redefs [http/post (fn [_url _opts]
                                  {:status 200
                                   :body (ByteArrayInputStream. (.getBytes stream "UTF-8"))})]
          (let [result (svar/ask-code! router {:messages [(svar/user "Return code")]})]
            (expect (= "(v/cat p {:max-lines 260})" (:result result)))))))

    (it "ask-code! preserves whitespace-only OpenAI-chat reasoning deltas like content deltas"
      (let [router (svar/make-router
                     [{:id :zai-coding
                       :api-key "sk-test"
                       :base-url "https://example.invalid/v1"
                       :models [{:name "glm-5.1"}]}])
            events (atom [])
            stream (str
                     "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"passes\"}}]}\n\n"
                     "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\" \"}}]}\n\n"
                     "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"I continue\"}}]}\n\n"
                     "data: {\"choices\":[{\"delta\":{\"content\":\"```clojure\\n(+ 1\"}}]}\n\n"
                     "data: {\"choices\":[{\"delta\":{\"content\":\" \"}}]}\n\n"
                     "data: {\"choices\":[{\"delta\":{\"content\":\"2)\\n```\"}}]}\n\n"
                     "data: [DONE]\n\n")]
        (with-redefs [http/post (fn [_url _opts]
                                  {:status 200
                                   :body (ByteArrayInputStream. (.getBytes stream "UTF-8"))})]
          (let [result (svar/ask-code! router {:messages [(svar/user "Return code")]
                                               :on-chunk #(swap! events conj %)})]
            (expect (= "(+ 1 2)" (:result result)))
            (expect (= "passes I continue" (:reasoning result)))
            (expect (= "passes I continue" (:reasoning (last @events))))))))

    (it "ask-code! rejects complete-looking code when stream lacks terminal marker"
      (let [router (svar/make-router
                     [{:id :openai-codex
                       :api-key "sk-test"
                       :models [{:name "gpt-5.5"}]}])
            stream "data: {\"type\":\"response.output_text.delta\",\"delta\":\"```clojure\\n(answer \\\"4\\\")\\n```\"}\n\n"]
        (with-redefs [http/post (fn [_url _opts]
                                  {:status 200
                                   :body (ByteArrayInputStream. (.getBytes stream "UTF-8"))})]
          (try
            (svar/ask-code! router {:messages [(svar/user "Return code")]})
            (expect false)
            (catch clojure.lang.ExceptionInfo e
              (expect (= :svar.core/stream-truncated (:type (ex-data e)))))))))

    (it "ask-code! does not expose empty fences as executable blocks"
      (let [router (svar/make-router
                     [{:id :openai-codex
                       :api-key "sk-test"
                       :models [{:name "gpt-5.5"}]}])]
        (with-redefs-fn {#'sut/http-post-stream! (fn [url _body _headers _timeout-ms _delta-fn _on-delta]
                                                   {:content "```clojure\n```"
                                                    :reasoning nil
                                                    :api-usage {:prompt_tokens 10
                                                                :completion_tokens 3
                                                                :total_tokens 13}
                                                    :http-response {:url url
                                                                    :streaming? true
                                                                    :status 200}})}
          (fn []
            (let [events (atom [])
                  result (svar/ask-code! router {:messages [(svar/user "Return code")]
                                                 :on-chunk #(swap! events conj %)})]
              (expect (= "" (:result result)))
              (expect (= [] (:blocks result)))
              (expect (= [{:result "" :blocks [] :done? true}]
                        (mapv #(select-keys % [:result :blocks :done?]) @events))))))))))

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
            (expect (= "plan first" (get-in result [:provider-state :reasoning-items 0 :summary-text])))
            (expect (= 11 (get-in result [:api-usage :prompt_tokens])))
            (expect (= 200 (get-in result [:http-response :status]))))))))

  (describe "stream response fallback"
    (it "responses transport does not duplicate output_text.done after deltas"
      (let [stream (str
                     "data: {\"type\":\"response.output_text.delta\",\"delta\":\"```clojure\\n\"}\n\n"
                     "data: {\"type\":\"response.output_text.delta\",\"delta\":\"(answer \\\"4\\\")\\n```\"}\n\n"
                     "data: {\"type\":\"response.output_text.done\",\"text\":\"```clojure\\n(answer \\\"4\\\")\\n```\"}\n\n"
                     "data: [DONE]\n\n")]
        (with-redefs [http/post (fn [_url _opts]
                                  {:status 200
                                   :body (ByteArrayInputStream. (.getBytes stream "UTF-8"))})]
          (let [result (sut/openai-responses-completion
                         {:model "test-model"
                          :input [{:role "user" :content [{:type "input_text" :text "hi"}]}]}
                         {:api-key "sk-test"
                          :base-url "https://example.invalid/v1"
                          :responses-path "/codex/responses"})]
            (expect (= "```clojure\n(answer \"4\")\n```" (:content result)))))))

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
            (expect (nil? (:reasoning (first @events))))
            (expect (= "plan first" (:reasoning (second @events))))
            (expect (= "(def x 1)" (:content (second @events))))))))

    (it "responses transport backfills reasoning from output_item.done summaries"
      (let [events (atom [])
            stream (str
                     "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"reasoning\",\"summary\":[]}}\n\n"
                     "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"plan first\"}]}}\n\n"
                     "data: {\"type\":\"response.output_text.delta\",\"delta\":\"(def x 1)\"}\n\n"
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
            (expect (= "plan first" (get-in result [:provider-state :reasoning-items 0 :summary-text])))
            (expect (= "plan first" (:reasoning (second @events))))))))

    (it "responses transport preserves Pi-style raw reasoning item signature for replay"
      (let [raw-item {:type "reasoning"
                      :id "rs_123"
                      :status "completed"
                      :summary [{:type "summary_text" :text "plan first"}]
                      :encrypted_content "ciphertext"
                      :phase "commentary"}
            stream (str
                     "data: " (json/write-json-str {:type "response.output_item.done"
                                                    :item raw-item}) "\n\n"
                     "data: {\"type\":\"response.output_text.delta\",\"delta\":\"(def x 1)\"}\n\n"
                     "data: [DONE]\n\n")]
        (with-redefs [http/post (fn [_url _opts]
                                  {:status 200
                                   :body (ByteArrayInputStream. (.getBytes stream "UTF-8"))})]
          (let [result (sut/openai-responses-completion
                         {:model "test-model"
                          :input [{:role "user" :content [{:type "input_text" :text "hi"}]}]}
                         {:api-key "sk-test"
                          :base-url "https://example.invalid/v1"
                          :on-chunk (constantly nil)})]
            (expect (= raw-item (get-in result [:provider-state :reasoning-items 0 :raw-item])))))))

    (it "responses transport reconstructs streamed summary into raw reasoning item when done omits it"
      (let [stream (str
                     "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"reasoning\",\"id\":\"rs_123\",\"summary\":[]}}\n\n"
                     "data: {\"type\":\"response.reasoning_summary_part.added\",\"part\":{\"type\":\"summary_text\",\"text\":\"\"}}\n\n"
                     "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"plan\"}\n\n"
                     "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\" first\"}\n\n"
                     "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"reasoning\",\"id\":\"rs_123\",\"encrypted_content\":\"ciphertext\"}}\n\n"
                     "data: {\"type\":\"response.output_text.delta\",\"delta\":\"(def x 1)\"}\n\n"
                     "data: [DONE]\n\n")]
        (with-redefs [http/post (fn [_url _opts]
                                  {:status 200
                                   :body (ByteArrayInputStream. (.getBytes stream "UTF-8"))})]
          (let [result (sut/openai-responses-completion
                         {:model "test-model"
                          :input [{:role "user" :content [{:type "input_text" :text "hi"}]}]}
                         {:api-key "sk-test"
                          :base-url "https://example.invalid/v1"
                          :on-chunk (constantly nil)})]
            (expect (= "plan first" (:reasoning result)))
            (expect (= "plan first"
                      (get-in result [:provider-state :reasoning-items 0 :summary-text])))
            (expect (= [{:type "summary_text" :text "plan first"}]
                      (get-in result [:provider-state :reasoning-items 0 :raw-item :summary])))))))

    (it "responses transport stores encrypted-only reasoning only in provider-state"
      (let [events (atom [])
            stream (str
                     "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"reasoning\",\"summary\":[],\"encrypted_content\":\"ciphertext\"}}\n\n"
                     "data: {\"type\":\"response.output_text.delta\",\"delta\":\"(def x 1)\"}\n\n"
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
            (expect (nil? (:reasoning result)))
            (expect (= "ciphertext" (get-in result [:provider-state :reasoning-items 0 :encrypted-content])))
            (expect (nil? (:reasoning (first @events))))))))

    (it "responses transport preserves whitespace-only reasoning deltas like content deltas"
      (let [events (atom [])
            stream (str
                     "data: {\"type\":\"response.output_text.delta\",\"delta\":\"pass\"}\n\n"
                     "data: {\"type\":\"response.output_text.delta\",\"delta\":\" \"}\n\n"
                     "data: {\"type\":\"response.output_text.delta\",\"delta\":\"2\"}\n\n"
                     "data: {\"type\":\"response.reasoning.delta\",\"delta\":\"passes\"}\n\n"
                     "data: {\"type\":\"response.reasoning.delta\",\"delta\":\" \"}\n\n"
                     "data: {\"type\":\"response.reasoning.delta\",\"delta\":\"I continue\"}\n\n"
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
            (expect (= "pass 2" (:content result)))
            (expect (= "passes I continue" (:reasoning result)))
            (expect (= "pass 2" (:content (last @events))))
            (expect (= "passes I continue" (:reasoning (last @events))))))))))
