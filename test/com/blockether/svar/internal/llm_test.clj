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
   (java.io ByteArrayInputStream InputStream)))

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

(defn- slow-heartbeat-stream []
  (let [closed? (atom false)
        payload (.getBytes ": ping\n\n" "UTF-8")
        idx (atom 0)
        next-byte (fn []
                    (let [i @idx]
                      (swap! idx #(mod (inc %) (alength payload)))
                      (bit-and (aget payload i) 0xff)))]
    (proxy [InputStream] []
      (read
        ([]
         (if @closed?
           -1
           (do
             (Thread/sleep 5)
             (next-byte))))
        ([buf off len]
         (if @closed?
           -1
           (do
             (Thread/sleep 5)
             (aset-byte buf off (unchecked-byte (next-byte)))
             1))))
      (close []
        (reset! closed? true)))))

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
      ;; `http-get!` now takes an optional opts map (api-style /
      ;; provider-id / llm-headers / query-params) so callers don't
      ;; have to special-case OAuth headers per-provider. Use a
      ;; variadic stub here so the test contract follows the
      ;; production signature.
      (with-redefs-fn {#'sut/http-get! (fn [_url _api-key & _opts]
                                         {:data [{:id "gpt-4o"}
                                                 {:id "gpt-5"}
                                                 {:id "gpt-5.1-codex"}
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
      (with-redefs-fn {#'sut/http-get! (fn [_url _api-key & _opts]
                                         {:data [{:id "claude-sonnet-4.6"}
                                                 {:id "gpt-4o"}
                                                 {:id "gpt-5.1-codex"}
                                                 {:id "gpt-5.3-codex"}
                                                 {:id "gpt-5.4"}
                                                 {:id "gpt-5.5"}
                                                 {:id "gemini-3-pro-preview"}]})}
        (fn []
          (expect (= ["claude-sonnet-4.6" "gpt-5.3-codex" "gpt-5.4" "gpt-5.5" "gemini-3-pro-preview"]
                    (mapv :id (svar/models! router))))))))

  (it "keeps z.ai Coding Plan glm-5v-turbo in /models output"
    (let [router (svar/make-router [{:id :zai-coding
                                     :api-key "sk-test"
                                     :models [{:name "glm-4.7"}]}])]
      (with-redefs-fn {#'sut/http-get! (fn [_url _api-key & _opts]
                                         {:data [{:id "glm-4.7"}
                                                 {:id "glm-5-turbo"}
                                                 {:id "glm-5v-turbo"}]})}
        (fn []
          (expect (= ["glm-4.7" "glm-5-turbo" "glm-5v-turbo"]
                    (mapv :id (svar/models! router))))))))

  (it "forwards api-style + provider-id + llm-headers + query-params to http-get!"
    (let [router (svar/make-router [{:id :openai-codex
                                     :api-key "sk-test"
                                     :llm-headers {"chatgpt-account-id" "acct_123"}
                                     :models [{:name "gpt-5.5"}]}])
          captured (atom nil)]
      (with-redefs-fn {#'sut/http-get! (fn [url _api-key & [opts]]
                                         (reset! captured (assoc opts :url url))
                                         {:models [{:slug "gpt-5.5" :id "gpt-5.5"}]})}
        (fn []
          (svar/models! router)
          (let [c @captured]
            ;; Codex `KNOWN_PROVIDERS` pins the per-provider models
            ;; endpoint and `client_version` query param. svar must
            ;; surface both on the http-get call without callers
            ;; ever poking at provider internals.
            (expect (= :openai-compatible-responses (:api-style c)))
            (expect (= :openai-codex (:provider-id c)))
            (expect (= {"chatgpt-account-id" "acct_123"} (:llm-headers c)))
            (expect (= {"client_version" "1.0.0"} (:query-params c)))
            (expect (.endsWith ^String (:url c) "/codex/models")))))))

  (it "normalizes ChatGPT-backend `{:models [{:slug ...}]}` shape to {:id ...}"
    (let [router (svar/make-router [{:id :openai-codex
                                     :api-key "sk-test"
                                     :models [{:name "gpt-5.5"}]}])]
      (with-redefs-fn {#'sut/http-get! (fn [_url _api-key & _opts]
                                         {:models [{:slug "gpt-5.5" :display_name "GPT-5.5"}
                                                   {:slug "gpt-5.4"}
                                                   {:slug "gpt-5.3-codex"}]})}
        (fn []
          (expect (= ["gpt-5.3-codex" "gpt-5.4" "gpt-5.5"]
                    (sort (mapv :id (svar/models! router))))))))))

(defdescribe transparent-openai-responses-routing-test
  (describe "ask! transparency"
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
        ;; Pricing now flows from models.dev catalog ⊕ overlay; catalog
        ;; contributes `:cache-read` so we assert overlay keys with select-keys.
        (expect (= {:input 5.00 :cached-input 0.50 :output 30.00
                    :input-over-272k 10.00 :cached-input-over-272k 1.00
                    :output-over-272k 45.00}
                  (select-keys (:pricing model)
                    [:input :cached-input :output
                     :input-over-272k :cached-input-over-272k :output-over-272k])))
        (expect (= 272000 (:context model)))
        (with-redefs-fn {#'sut/http-post-stream! (fn [url body headers _timeout-ms _ttft-timeout-ms _idle-timeout-ms _delta-fn on-delta]
                                                   (swap! calls conj {:url url :body body :headers headers :on-delta on-delta})
                                                   {:content "{\"answer\":\"ok\"}"
                                                    :reasoning nil
                                                    :api-usage {:input-tokens 10
                                                                :output-tokens 5
                                                                :total-tokens 15
                                                                :input-tokens-details {:cache-read 7}}
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

    (it "Copilot Responses requests reuse OpenAI reasoning and honor Copilot header overrides"
      (let [calls (atom [])
            router (svar/make-router
                     [{:id :github-copilot
                       :api-key "sk-test"
                       :models [{:name "gpt-5.5"}]}])
            answer-spec (svar/spec
                          (svar/field svar/NAME :answer
                            svar/TYPE svar/TYPE_STRING
                            svar/CARDINALITY svar/CARDINALITY_ONE
                            svar/DESCRIPTION "answer"))]
        (with-redefs-fn {#'sut/http-post-stream! (fn [url body headers _timeout-ms _ttft-timeout-ms _idle-timeout-ms _delta-fn _on-delta]
                                                   (swap! calls conj {:url url :body body :headers headers})
                                                   {:content "{\"answer\":\"ok\"}"
                                                    :reasoning nil
                                                    :api-usage {:input-tokens 10
                                                                :output-tokens 5
                                                                :total-tokens 15}
                                                    :http-response {:url url
                                                                    :streaming? true
                                                                    :status 200}})}
          (fn []
            (let [result (svar/ask! router
                           {:spec answer-spec
                            :messages [(svar/system "Return JSON.")
                                       (svar/user "Reply ok")]
                            :reasoning :deep
                            :json-object-mode? true
                            :llm-headers {"X-Initiator" "agent"}})
                  {:keys [url body headers]} (first @calls)]
              (expect (= "ok" (get-in result [:result :answer])))
              (expect (= "https://api.individual.githubcopilot.com/responses" url))
              (expect (= "vscode/1.100.0" (get headers "Editor-Version")))
              (expect (= "copilot-chat/0.26.7" (get headers "Editor-Plugin-Version")))
              (expect (= "vscode-chat" (get headers "Copilot-Integration-Id")))
              (expect (= "GitHubCopilotChat/0.26.7" (get headers "User-Agent")))
              (expect (= "agent" (get headers "X-Initiator")))
              (expect (= "conversation-edits" (get headers "Openai-Intent")))
              (expect (= "text/event-stream" (get headers "Accept")))
              (expect (= true (:stream body)))
              (expect (= {:effort "high" :summary "detailed"} (:reasoning body)))
              (expect (= ["reasoning.encrypted_content"] (:include body))))))))

    (it "chat-completion sends Anthropic OAuth tokens with Claude Code headers and system identity"
      (let [seen (atom nil)
            messages [(svar/system "Follow project rules.")
                      (svar/user "hi")]]
        (with-redefs-fn {#'sut/http-post! (fn [_url body headers _timeout-ms]
                                            (reset! seen {:body body :headers headers})
                                            {:parsed {:content [{:type "text" :text "ok"}]
                                                      :usage {:input_tokens 10
                                                              :output_tokens 1}}
                                             :raw-body "{}"
                                             :url "https://api.anthropic.com/v1/messages"
                                             :status 200})}
          (fn []
            (sut/chat-completion messages "claude-sonnet-4-5" "sk-ant-oat01-test" "https://api.anthropic.com/v1"
              {:api-style :anthropic})
            (let [{:keys [body headers]} @seen]
              (expect (= "Bearer sk-ant-oat01-test" (get headers "Authorization")))
              (expect (nil? (get headers "x-api-key")))
              (expect (str/includes? (get headers "anthropic-beta") "claude-code-20250219"))
              (expect (str/includes? (get headers "anthropic-beta") "oauth-2025-04-20"))
              (expect (= "claude-cli/2.1.62" (get headers "user-agent")))
              (expect (= "cli" (get headers "x-app")))
              (expect (= "You are Claude Code, Anthropic's official CLI for Claude."
                        (get-in body [:system 0 :text])))
              (expect (= {:type "ephemeral"} (get-in body [:system 0 :cache_control])))
              (expect (= "Follow project rules." (get-in body [:system 1 :text])))
              (expect (= [{:role "user" :content "hi"}] (:messages body))))))))

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
            (expect (= "vscode/1.100.0" (get @seen "Editor-Version")))
            (expect (= "copilot-chat/0.26.7" (get @seen "Editor-Plugin-Version")))
            (expect (= "vscode-chat" (get @seen "Copilot-Integration-Id")))
            (expect (= "user" (get @seen "X-Initiator")))
            (expect (= "conversation-edits" (get @seen "Openai-Intent")))))))

    (it "chat-completion lets llm-headers override inferred Copilot X-Initiator"
      (let [seen (atom nil)
            messages [(svar/user "internal call")]]
        (with-redefs-fn {#'sut/http-post! (fn [_url _body headers _timeout-ms]
                                            (reset! seen headers)
                                            {:parsed {:choices [{:message {:content "ok"}}]}
                                             :raw-body "{}"
                                             :url "https://example.invalid/v1/chat/completions"
                                             :status 200})}
          (fn []
            (sut/chat-completion messages "gpt-4o" "sk-test" "https://example.invalid/v1"
              {:provider-id :github-copilot
               :llm-headers {"X-Initiator" "agent"}})
            (expect (= "agent" (get @seen "X-Initiator")))
            (expect (= "conversation-edits" (get @seen "Openai-Intent")))))))

    (it "chat-completion marks Copilot requests with prior assistant turns as agent initiated"
      (let [seen (atom nil)
            messages [(svar/user "first")
                      {:role "assistant" :content "previous"}
                      (svar/user "continue")]]
        (with-redefs-fn {#'sut/http-post! (fn [_url _body headers _timeout-ms]
                                            (reset! seen headers)
                                            {:parsed {:choices [{:message {:content "ok"}}]}
                                             :raw-body "{}"
                                             :url "https://example.invalid/v1/chat/completions"
                                             :status 200})}
          (fn []
            (sut/chat-completion messages "gpt-4o" "sk-test" "https://example.invalid/v1"
              {:provider-id :github-copilot})
            (expect (= "agent" (get @seen "X-Initiator")))))))

    (it "GitHub Copilot chat forces SSE streaming"
      (let [calls (atom [])
            messages [(svar/user "hi")]]
        (with-redefs-fn {#'sut/http-post-stream! (fn [url body headers _timeout-ms _ttft-timeout-ms _idle-timeout-ms _delta-fn on-delta]
                                                   (swap! calls conj {:url url :body body :headers headers :on-delta on-delta})
                                                   {:content "ok"
                                                    :reasoning nil
                                                    :api-usage {}
                                                    :http-response {:url url
                                                                    :streaming? true
                                                                    :status 200}})}
          (fn []
            (let [result (sut/chat-completion messages "gpt-4o" "sk-test" "https://api.individual.githubcopilot.com"
                           {:provider-id :github-copilot
                            :llm-headers {"User-Agent" "VisCopilot/0.1"}})
                  {:keys [url body headers on-delta]} (first @calls)]
              (expect (= "ok" (:content result)))
              (expect (= "https://api.individual.githubcopilot.com/chat/completions" url))
              (expect (= true (:stream body)))
              (expect (ifn? on-delta))
              (expect (= "text/event-stream" (get headers "Accept"))))))))))

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
            (expect (= 11 (get-in result [:api-usage :input-tokens])))
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
            (expect (= 5 (get-in result [:api-usage :input-tokens])))
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

    (it "responses transport separates multiple streamed reasoning summary parts"
      (let [events (atom [])
            stream (str
                     "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"reasoning\",\"id\":\"rs_123\",\"summary\":[]}}\n\n"
                     "data: {\"type\":\"response.reasoning_summary_part.added\",\"part\":{\"type\":\"summary_text\",\"text\":\"\"}}\n\n"
                     "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"**First**\"}\n\n"
                     "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"\\n\\nOne.\"}\n\n"
                     "data: {\"type\":\"response.reasoning_summary_part.added\",\"part\":{\"type\":\"summary_text\",\"text\":\"\"}}\n\n"
                     "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"**Second**\"}\n\n"
                     "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"\\n\\nTwo.\"}\n\n"
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
                          :on-chunk #(swap! events conj %)})]
            (expect (= "**First**\n\nOne.\n\n**Second**\n\nTwo." (:reasoning result)))
            (expect (= "**First**\n\nOne.\n\n**Second**\n\nTwo."
                      (get-in result [:provider-state :reasoning-items 0 :summary-text])))
            (expect (= (:reasoning result) (:reasoning (last @events))))))))

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

(defdescribe http-error-message-test
  ;; Vis conv c8dc39b1: babashka's HttpClient surfaced a low-level
  ;; exception with nil message, svar piped that straight into
  ;; anomaly/fault!, and downstream observers saw the unhelpful
  ;; `ExceptionInfo: null` trace with `:com.blockether.anomaly.core/message nil`.
  ;; `http-error-message` synthesises a usable message from whatever
  ;; signal IS available so the final ex-info message is never nil.
  (let [fmt @(ns-resolve 'com.blockether.svar.internal.llm 'http-error-message)]
    (describe "http-error-message"
      (it "prefers the direct ex-message when set"
        (let [e (ex-info "real boom" {})]
          (expect (= "real boom" (fmt e)))))

      (it "skips blank direct messages and falls back to ex-cause's message"
        (let [cause (Exception. "cause says it")
              e     (ex-info "" {} cause)]
          (expect (= "cause says it" (fmt e)))))

      (it "synthesises HTTP <status> at <url> from ex-data when neither message is set"
        (let [e (ex-info nil {:status 503 :url "https://api.anthropic.com/v1/messages"})]
          (expect (= "HTTP 503 at https://api.anthropic.com/v1/messages" (fmt e)))))

      (it "uses just the status when url is absent"
        (let [e (ex-info nil {:status 429})]
          (expect (= "HTTP 429" (fmt e)))))

      (it "uses just the url when status is absent"
        (let [e (ex-info nil {:url "https://x.invalid/y"})]
          (expect (= "request to https://x.invalid/y failed" (fmt e)))))

      (it "falls back to the exception class name when nothing else helps"
        (let [e (Exception.)]
          (expect (= "java.lang.Exception" (fmt e)))))

      (it "final-fallback string is never nil or blank"
        ;; ExceptionInfo with nil message AND no ex-data AND no cause:
        ;; class name still wins. The literal `\"HTTP request failed\"`
        ;; only ever fires if class is also somehow nil, but contract
        ;; guarantees non-nil + non-blank.
        (let [e (ex-info nil {})
              m (fmt e)]
          (expect (string? m))
          (expect (not (clojure.string/blank? m))))))))

(defdescribe lmstudio-models-shape-test
  "Local-provider context detection: LM Studio's native /api/v0/models
   reports context length the OpenAI-compatible /v1/models omits."
  (describe "models-endpoint-url"
    (it ":models-base :host hangs path off host root, not the /v1 chat base"
      (expect (= "http://localhost:1234/api/v0/models"
                ((var-get #'sut/models-endpoint-url) "http://localhost:1234/v1" :host "/api/v0/models"))))
    (it "respects host/port overrides"
      (expect (= "http://10.0.0.5:9000/api/v0/models"
                ((var-get #'sut/models-endpoint-url) "http://10.0.0.5:9000/v1" :host "/api/v0/models"))))
    (it "default base appends path to chat base-url (unchanged behavior)"
      (expect (= "https://api.openai.com/v1/models"
                ((var-get #'sut/models-endpoint-url) "https://api.openai.com/v1" nil "/models")))))
  (describe "enrich-lmstudio-model"
    (it "prefers loaded_context_length over max_context_length"
      (let [m ((var-get #'sut/enrich-lmstudio-model)
               {:id "m" :max_context_length 262144 :loaded_context_length 16384 :state "loaded"})]
        (expect (= 16384 (:context m)))
        (expect (true? (:loaded? m)))))
    (it "falls back to max_context_length when not loaded"
      (let [m ((var-get #'sut/enrich-lmstudio-model)
               {:id "m" :max_context_length 131072 :state "not-loaded"})]
        (expect (= 131072 (:context m)))
        (expect (false? (:loaded? m)))))
    (it "surfaces tool_use capability"
      (expect (true? (:tool-call? ((var-get #'sut/enrich-lmstudio-model)
                                   {:id "m" :capabilities ["tool_use"]})))))
    (it "leaves models without context fields untouched"
      (let [m ((var-get #'sut/enrich-lmstudio-model) {:id "m"})]
        (expect (nil? (:context m)))
        (expect (nil? (:tool-call? m))))))
  (describe "shape-models"
    (it "applies :lmstudio shaping"
      (expect (= 8192 (-> ((var-get #'sut/shape-models) :lmstudio [{:id "m" :max_context_length 8192}])
                        first :context))))
    (it "passes through unknown shapes unchanged"
      (let [models [{:id "m" :max_context_length 8192}]]
        (expect (= models ((var-get #'sut/shape-models) nil models)))))))

(defdescribe routed-context-limit-flow-test
  "Routed model's real :context must reach the pre-flight context check, not
   just the output-budget path. Regression: LM Studio models overflowed at the
   8192 default because check-context-limit re-derived from the static catalog."
  (let [inject       (var-get #'sut/inject-routed-params)
        resolve-opts (var-get #'sut/resolve-opts)
        sut-router-default @(requiring-resolve 'com.blockether.svar.internal.router/DEFAULT_CONTEXT_LIMIT)]
    (describe "inject-routed-params forwards :context"
      (it "carries the model-map's :context into opts"
        (expect (= 262144 (:context (inject {} {:id :lmstudio} {:name "m" :context 262144})))))
      (it "omits :context when the model-map has none"
        (expect (nil? (:context (inject {} {:id :lmstudio} {:name "m"}))))))
    (describe "resolve-opts context-limits"
      (it "prefers an explicit :context over the static catalog default"
        (let [resolved (resolve-opts {} {:model "m" :provider-id :lmstudio :context 262144})]
          (expect (= 262144 (get (:context-limits resolved) "m")))))
      (it "falls back to DEFAULT_CONTEXT_LIMIT for an unknown local model"
        (let [resolved (resolve-opts {} {:model "m" :provider-id :lmstudio})]
          (expect (= sut-router-default (get (:context-limits resolved) "m"))))))))

(defdescribe reasoning-content-echo-provenance-test
  "openai-chat reasoning_content echo: the :thinking-signature slot rides
   the wire ONLY when it IS the reasoning text (z.ai capture shape,
   signature == thinking). Foreign-born signatures are opaque payloads —
   Anthropic's HMAC base64 leaked as visible 'reasoning' when a provider
   fallback re-routed Anthropic blocks to GLM (the blob echoed back as
   reasoning_content and rendered as thinking in the client)."
  (let [build (var-get #'sut/build-request-body)
        msg   (fn [blocks] [{:role "assistant" :content blocks}
                            {:role "user" :content "next"}])
        rc-of (fn [body] (-> body :messages first :reasoning_content))]
    (it "z.ai-born block (signature == thinking) echoes verbatim"
      (let [body (build (msg [{:type "thinking"
                               :thinking "step by step"
                               :thinking-signature "step by step"
                               :redacted? false}
                              {:type "text" :text "answer"}])
                   "glm-5")]
        (expect (= "step by step" (rc-of body)))))
    (it "Anthropic-born block (opaque HMAC signature) echoes the THINKING text, never the signature"
      (let [hmac "CAIS9AkKYggOGAIqQFAKE-not-prose-claude-fable-5-thinking-hmac"
            body (build (msg [{:type "thinking"
                               :thinking "real visible reasoning"
                               :thinking-signature hmac
                               :redacted? false}
                              {:type "text" :text "answer"}])
                   "glm-5")
            rc   (rc-of body)]
        (expect (= "real visible reasoning" rc))
        (expect (not (str/includes? (str body) hmac)))))
    (it "Anthropic redacted block (encrypted data) is dropped from a foreign wire entirely"
      (let [body (build (msg [{:type "thinking"
                               :thinking ""
                               :thinking-signature "ENCRYPTED-REDACTED-PAYLOAD"
                               :redacted? true}
                              {:type "text" :text "answer"}])
                   "glm-5")]
        (expect (nil? (rc-of body)))
        (expect (not (str/includes? (str body) "ENCRYPTED-REDACTED-PAYLOAD")))))
    (it "empty-thinking Anthropic block (redact-thinking shape) contributes nothing"
      (let [body (build (msg [{:type "thinking"
                               :thinking ""
                               :thinking-signature "CAISfakeHMAC"
                               :redacted? false}
                              {:type "text" :text "answer"}])
                   "glm-5")]
        (expect (nil? (rc-of body)))))))

(defdescribe anthropic-stream-finish-reason-test
  "Anthropic message_delta carries the stop reason under [:delta :stop_reason];
   before this, every Anthropic stream finalized with finish-reason nil and a
   max_tokens truncation looked identical to a clean end_turn."
  (let [finish (var-get #'sut/stream-finish-reason)]
    (it "reads stop_reason from an Anthropic message_delta"
      (expect (= "end_turn" (finish {:type "message_delta"
                                     :delta {:stop_reason "end_turn" :stop_sequence nil}
                                     :usage {:output_tokens 42}})))
      (expect (= "max_tokens" (finish {:type "message_delta"
                                       :delta {:stop_reason "max_tokens"}}))))
    (it "OpenAI chat shape still wins its own field"
      (expect (= "stop" (finish {:choices [{:finish_reason "stop"}]}))))))
