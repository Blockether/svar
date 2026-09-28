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
  canonical-extra-body-test
  (it "spells JSON member names as keywords"
      (expect
        (= {:reasoning {:summary "detailed"} :include ["reasoning.encrypted_content"] :store false}
           (router/canonical-extra-body MODEL_EXTRA_BODY))))
  (it "keeps the keyword entry of a member given under both spellings"
      (expect (= {:max_tokens 5 :reasoning {:summary "detailed" :effort "high"}}
                 (router/canonical-extra-body {"max_tokens" 3
                                               :max_tokens 5
                                               "reasoning" {"summary" "detailed" "effort" "low"}
                                               :reasoning {:effort "high"}}))))
  (it "leaves other member names and nested values as given"
      (expect (= {"svar/tools" [{"name" "run"}]
                  "provider-state" {"id" "resp_1"}
                  :metadata {"trace" "1"}
                  :response_format {:type "json_schema"
                                    :json_schema {"name" "answer" "schema" {"type" "object"}}}}
                 (router/canonical-extra-body {"svar/tools" [{"name" "run"}]
                                               "provider-state" {"id" "resp_1"}
                                               "metadata" {"trace" "1"}
                                               "response_format" {"type" "json_schema"
                                                                  "json_schema"
                                                                  {"name" "answer"
                                                                   "schema" {"type" "object"}}}}))))
  (it "returns keyword maps and nil unchanged"
      (let [body {:max_tokens 5 :reasoning {:effort "high"}}]
        (expect (identical? body (router/canonical-extra-body body))))
      (expect (nil? (router/canonical-extra-body nil)))))

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
                              (:extra-body provider))))))

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
        (expect (= response-format (get (json/read-json body) "response_format"))))))
