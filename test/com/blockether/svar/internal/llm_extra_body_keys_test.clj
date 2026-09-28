(ns com.blockether.svar.internal.llm-extra-body-keys-test
  "Request bodies spell each `:extra-body` member once.

   Configuration, clients and extensions send JSON string keys, while svar's
   own layers use keywords. These tests cover how svar merges those layers and
   the exact JSON each transport posts.

   Pure data, except the transport tests, which stub the HTTP client - no LLM
   calls."
  (:require [babashka.http-client :as http]
            [charred.api :as json]
            [com.blockether.svar.core :as svar]
            [com.blockether.svar.internal.llm :as sut]
            [com.blockether.svar.internal.router :as router]
            [lazytest.core :refer [defdescribe describe expect it]]))

(def ^:private MODEL_EXTRA_BODY
  "Responses options for one model, keyed as a Python extension returns them."
  {"reasoning" {"summary" "detailed"} "include" ["reasoning.encrypted_content"] "store" false})

(def ^:private RESPONSES_REPLY
  {"id" "resp_1"
   "output" [{"type" "message" "role" "assistant" "content" [{"type" "output_text" "text" "ok"}]}]
   "usage" {"input_tokens" 1 "output_tokens" 1 "total_tokens" 2}})

(def ^:private CHAT_REPLY
  {"id" "chatcmpl_1"
   "model" "gpt-6-luna"
   "choices" [{"index" 0
               "message" {"role" "assistant" "content" "{\"answer\":\"ok\"}"}
               "finish_reason" "stop"}]
   "usage" {"prompt_tokens" 1 "completion_tokens" 1 "total_tokens" 2}})

(def ^:private ANTHROPIC_REPLY
  {"id" "msg_1"
   "type" "message"
   "role" "assistant"
   "content" [{"type" "text" "text" "ok"}]
   "stop_reason" "end_turn"
   "usage" {"input_tokens" 1 "output_tokens" 1}})

(defn- posted-bodies
  "Calls `f` while every HTTP POST answers `reply`; returns the JSON request
   bodies that were posted."
  [reply f]
  (let [posted (atom [])]
    (with-redefs [http/post (fn [_url opts]
                              (swap! posted conj (:body opts))
                              {:status 200 :headers {} :body (json/write-json-str reply)})]
      (f))
    @posted))

(defn- member-count
  "How many times the JSON text `body` spells a member named `member-name`."
  [body member-name]
  (count (re-seq (re-pattern (str "\"" member-name "\":")) body)))

(defn- responses-bodies
  "Request bodies `svar/ask!` posts for a routed Responses model configured
   with `model-extra-body`, merged with the caller's `opts`."
  [model-extra-body opts]
  (let [router (svar/make-router
                 [{:id :responses-proxy
                   :api-key "test-key"
                   :base-url "http://127.0.0.1:1/v1"
                   :api-style :openai-compatible-responses
                   :models [{:name "gpt-6-luna" :context 1050000 :extra-body model-extra-body}]}])]
    (posted-bodies RESPONSES_REPLY
                   #(svar/ask! router
                               (merge {:messages [{:role "user" :content "hello"}]
                                       :routing {:provider :responses-proxy :model "gpt-6-luna"}}
                                      opts)))))

(defdescribe
  keyword-body-test
  (it "turns every JSON member name, at any depth, into a keyword"
      (expect
        (= {:reasoning {:summary "detailed"} :include ["reasoning.encrypted_content"] :store false}
           (router/keyword-body MODEL_EXTRA_BODY)))
      (expect (= {:tools [{:type "function" :parameters {:properties {:user-id {:type "string"}}}}]}
                 (router/keyword-body {"tools" [{"type" "function"
                                                 "parameters" {"properties"
                                                               {"user-id" {"type" "string"}}}}]}))))
  (it "keeps the keyword entry of a member given under both spellings"
      (expect (= {:max_tokens 5 :reasoning {:effort "high"}}
                 (router/keyword-body {"max_tokens" 3
                                       :max_tokens 5
                                       "reasoning" {"summary" "detailed"}
                                       :reasoning {"effort" "high"}}))))
  (it "never spells one of svar's own namespaced options"
      (let [body (router/keyword-body {"svar/tools" [{"name" "run"}]})]
        (expect (nil? (:svar/tools body)))
        (expect (= [{:name "run"}] (get body (keyword nil "svar/tools"))))))
  (it "serializes every member name as it arrived"
      (let [body
            {"svar/tools" [] "provider-state" {"id" "resp_1"} "metadata" {"user id" "7" "a.b" 1}}]
        (expect (= (json/read-json (json/write-json-str body))
                   (json/read-json (json/write-json-str (router/keyword-body body)))))))
  (it "returns keyword maps and nil as given"
      (let [body {:max_tokens 5 :reasoning {:effort "high"}}]
        (expect (identical? body (router/keyword-body body))))
      (expect (nil? (router/keyword-body nil)))))

(defdescribe normalize-provider-extra-body-test
             (it "merges JSON-keyed configuration over the provider defaults"
                 (let [provider (router/normalize-provider
                                  0
                                  {:id :openai-codex
                                   :api-key "test-key"
                                   :models [{:name "gpt-6-astra"}]
                                   :extra-body {"reasoning" {"summary" "concise"} "store" true}})]
                   (expect (= (merge (get-in router/KNOWN_PROVIDERS [:openai-codex :extra-body])
                                     {:reasoning {:summary "concise"} :store true})
                              (:extra-body provider)))))
             (it "gives each model's JSON-keyed extra body keyword keys"
                 (let [provider (router/normalize-provider
                                  0
                                  {:id :responses-proxy
                                   :api-key "test-key"
                                   :base-url "http://127.0.0.1:1/v1"
                                   :api-style :openai-compatible-responses
                                   :models [{:name "gpt-6-luna" :extra-body MODEL_EXTRA_BODY}]})]
                   (expect (= {:reasoning {:summary "detailed"}
                               :include ["reasoning.encrypted_content"]
                               :store false}
                              (:extra-body (first (:models provider))))))))

(defdescribe
  responses-request-members-test
  ;; Blockether/vis#291: a model's JSON-keyed `reasoning` and svar's own
  ;; `:reasoning` were both serialized, so the request had two `reasoning`
  ;; members and the provider rejected it.
  (describe "with a model's JSON-keyed extra body"
            (it "sends one reasoning member that carries the requested effort"
                (let [[body :as bodies] (responses-bodies MODEL_EXTRA_BODY {:reasoning :deep})]
                  (expect (= 1 (count bodies)))
                  (expect (= 1 (member-count body "reasoning")))
                  (expect (= 1 (member-count body "include")))
                  (expect (= 1 (member-count body "store")))
                  (expect (= {"summary" "detailed" "effort" "high"}
                             (get (json/read-json body) "reasoning")))))
            (it "lets the caller's JSON-keyed members replace the model's"
                (let [[body]
                      (responses-bodies MODEL_EXTRA_BODY
                                        {:extra-body {"reasoning" {"effort" "low"} "store" true}})

                      request
                      (json/read-json body)]

                  (expect (= 1 (member-count body "reasoning")))
                  (expect (= 1 (member-count body "store")))
                  (expect (= {"effort" "low"} (get request "reasoning")))
                  (expect (true? (get request "store")))))
            (it "applies the caller's JSON-keyed reasoning_effort instead of sending it raw"
                (let [[body] (responses-bodies MODEL_EXTRA_BODY
                                               {:reasoning :deep
                                                :extra-body {"reasoning_effort" "low"}})]
                  (expect (= 1 (member-count body "reasoning")))
                  (expect (zero? (member-count body "reasoning_effort")))
                  (expect (= {"summary" "detailed" "effort" "low"}
                             (get (json/read-json body) "reasoning")))))))

(defdescribe
  chat-request-members-test
  (it "sends the keyword max_tokens once when a body also spells it in JSON"
      (let [[body :as bodies]
            (posted-bodies CHAT_REPLY
                           #(sut/chat-completion [{:role "user" :content "hi"}]
                                                 "gpt-6-luna" "test-key"
                                                 "http://127.0.0.1:1/v1"
                                                 {:api-style :openai-compatible-chat
                                                  :extra-body {"max_tokens" 3 :max_tokens 5}}))]
        (expect (= 1 (count bodies)))
        (expect (= 1 (member-count body "max_tokens")))
        (expect (= 5 (get (json/read-json body) "max_tokens")))))
  (it "keeps a caller's JSON-keyed response_format in JSON object mode"
      (let [response-format
            {"type" "json_schema" "json_schema" {"name" "answer" "schema" {"type" "object"}}}

            [body :as bodies]
            (posted-bodies CHAT_REPLY
                           #(sut/ask!* {}
                                       {:messages [{:role "user" :content "hi"}]
                                        :model "gpt-6-luna"
                                        :api-style :openai-compatible-chat
                                        :api-key "test-key"
                                        :base-url "http://127.0.0.1:1/v1"
                                        :context 100000
                                        :output-reserve 0
                                        :json-object-mode? true
                                        :extra-body {"response_format" response-format}}))]

        (expect (= 1 (count bodies)))
        (expect (= 1 (member-count body "response_format")))
        (expect (= response-format (get (json/read-json body) "response_format")))))
  (it "drops a caller's JSON-keyed OpenAI options from Anthropic requests"
      (let [[body :as bodies]
            (posted-bodies ANTHROPIC_REPLY
                           #(sut/chat-completion [{:role "user" :content "hi"}]
                                                 "claude-haiku-4-5" "test-key"
                                                 "http://127.0.0.1:1/v1"
                                                 {:api-style :anthropic
                                                  :extra-body {"text" {"verbosity" "high"}
                                                               "stream_options" {"include_usage"
                                                                                 true}
                                                               "prompt_cache_key" "session-1"
                                                               "service_tier" "priority"
                                                               "temperature" 0.3}}))

            sent
            (json/read-json body)]

        (expect (= 1 (count bodies)))
        (expect (not-any? #(contains? sent %)
                          ["text" "stream_options" "prompt_cache_key" "service_tier"]))
        (expect (= 0.3 (get sent "temperature")))))
  (it "keeps a caller's JSON-keyed Anthropic service tier"
      (let [[body] (posted-bodies ANTHROPIC_REPLY
                                  #(sut/chat-completion [{:role "user" :content "hi"}]
                                                        "claude-haiku-4-5" "test-key"
                                                        "http://127.0.0.1:1/v1"
                                                        {:api-style :anthropic
                                                         :extra-body {"service_tier"
                                                                      "standard_only"}}))]
        (expect (= "standard_only" (get (json/read-json body) "service_tier"))))))
