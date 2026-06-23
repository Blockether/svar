(ns com.blockether.svar.internal.llm-tool-calling-test
  "Native tool calling: per-wire tool/tool-choice shaping, request-body
   injection, response tool_use extraction, and the anthropic round-trip."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.llm :as sut]))

(def ^:private tool-def->wire   @#'sut/tool-def->wire)
(def ^:private tools->wire      @#'sut/tools->wire)
(def ^:private tool-choice->wire @#'sut/tool-choice->wire)
(def ^:private build-anthropic  @#'sut/build-anthropic-request-body)
(def ^:private build-chat       @#'sut/build-request-body)
(def ^:private build-responses  @#'sut/build-openai-responses-request-body)
(def ^:private extract-anthropic @#'sut/extract-anthropic-response-data)
(def ^:private extract-openai    @#'sut/extract-response-data)
(def ^:private responses-input   @#'sut/responses-message-input-entries)
(def ^:private assemble-chat-frags @#'sut/assemble-chat-tool-call-fragments)
(def ^:private openai-responses-state @#'sut/openai-responses-state)
(def ^:private merge-provider-state @#'sut/merge-provider-state)
(def ^:private fn-item->tool-call @#'sut/function-call-item->tool-call)
(def ^:private dedupe-tool-calls @#'sut/dedupe-tool-calls)
(def ^:private extract-stream-delta @#'sut/extract-stream-delta)
(def ^:private build-gemini      @#'sut/build-gemini-request-body)
(def ^:private extract-gemini    @#'sut/extract-gemini-response-data)
(def ^:private gemini-tool-config @#'sut/gemini-tool-config)

(def ^:private run-python
  {:name "run_python"
   :description "Execute Python in the sandbox."
   :schema {:type "object"
            :properties {"code" {:type "string"}}
            :required ["code"]}})

(defdescribe tool-def-shaping-test
  (describe "tool-def->wire per api-style"
    (it "anthropic uses :name/:description/:input_schema"
      (let [w (tool-def->wire :anthropic run-python)]
        (expect (= "run_python" (:name w)))
        (expect (= "Execute Python in the sandbox." (:description w)))
        (expect (= (:schema run-python) (:input_schema w)))
        (expect (nil? (:parameters w)))))

    (it "openai-chat nests under :function with :parameters"
      (let [w (tool-def->wire :openai-compatible-chat run-python)]
        (expect (= "function" (:type w)))
        (expect (= "run_python" (get-in w [:function :name])))
        (expect (= (:schema run-python) (get-in w [:function :parameters])))))

    (it "responses is flat :type/:name/:parameters"
      (let [w (tool-def->wire :openai-compatible-responses run-python)]
        (expect (= "function" (:type w)))
        (expect (= "run_python" (:name w)))
        (expect (= (:schema run-python) (:parameters w)))
        (expect (nil? (:function w)))))

    (it "missing schema falls back to an empty object schema"
      (expect (= {:type "object" :properties {}}
                (:input_schema (tool-def->wire :anthropic {:name "x"})))))))

(defdescribe tool-choice-shaping-test
  (describe "tool-choice->wire"
    (it "anthropic: auto/any/tool"
      (expect (= {:type "auto"} (tool-choice->wire :anthropic :auto)))
      (expect (= {:type "any"}  (tool-choice->wire :anthropic :required)))
      (expect (= {:type "tool" :name "run_python"}
                (tool-choice->wire :anthropic {:name "run_python"}))))
    (it "chat: string/required/none/forced-function"
      (expect (= "auto"     (tool-choice->wire :openai-compatible-chat :auto)))
      (expect (= "required" (tool-choice->wire :openai-compatible-chat :required)))
      (expect (= "none"     (tool-choice->wire :openai-compatible-chat :none)))
      (expect (= {:type "function" :function {:name "run_python"}}
                (tool-choice->wire :openai-compatible-chat "run_python"))))
    (it "responses: forced function is flat"
      (expect (= {:type "function" :name "run_python"}
                (tool-choice->wire :openai-compatible-responses {:name "run_python"}))))))

(defdescribe anthropic-body-injection-test
  (let [msgs [{:role "user" :content "hi"}]]
    (it "injects shaped :tools + :tool_choice and strips :svar/* keys"
      (let [body (build-anthropic msgs "claude"
                   {:svar/tools [run-python] :svar/tool-choice :required})]
        (expect (= 1 (count (:tools body))))
        (expect (= "run_python" (:name (first (:tools body)))))
        (expect (= {:type "any"} (:tool_choice body)))
        ;; svar-internal keys never reach the wire
        (expect (not (contains? body :svar/tools)))
        (expect (not (contains? body :svar/tool-choice)))))

    (it "no tools => no :tools / :tool_choice keys"
      (let [body (build-anthropic msgs "claude" {})]
        (expect (not (contains? body :tools)))
        (expect (not (contains? body :tool_choice)))))

    (it "tool_choice without tools is dropped"
      (let [body (build-anthropic msgs "claude" {:svar/tool-choice :required})]
        (expect (not (contains? body :tool_choice)))))))

(defdescribe other-wire-injection-test
  (let [msgs [{:role "user" :content "hi"}]]
    (it "chat body carries function-shaped tools, no :svar/* leak"
      (let [body (build-chat msgs "glm" {:svar/tools [run-python]})]
        (expect (= "function" (:type (first (:tools body)))))
        (expect (not (contains? body :svar/tools)))))
    (it "responses body carries flat tools"
      (let [body (build-responses msgs "gpt" {:svar/tools [run-python] :svar/tool-choice :auto})]
        (expect (= "run_python" (:name (first (:tools body)))))
        (expect (= "auto" (:tool_choice body)))
        (expect (not (contains? body :svar/tools)))))))

(defdescribe anthropic-tool-call-extraction-test
  (it "extracts tool_use blocks as canonical :tool-calls and keeps them on assistant-message"
    (let [envelope {:parsed {:content [{:type "text" :text "let me run that"}
                                       {:type "tool_use" :id "toolu_1"
                                        :name "run_python"
                                        :input {"code" "rg(\"x\")"}}]
                             :usage {:input_tokens 10 :output_tokens 5}}}
          out (extract-anthropic envelope)]
      (expect (= "let me run that" (:content out)))
      (expect (= [{:id "toolu_1" :name "run_python" :input {"code" "rg(\"x\")"}}]
                (:tool-calls out)))
      ;; tool_use survives into the canonical assistant message for round-trip
      (let [blocks (get-in out [:assistant-message :content])]
        (expect (some #(= "tool_use" (:type %)) blocks)))))

  (it "no tool_use => no :tool-calls key (plain answer)"
    (let [out (extract-anthropic {:parsed {:content [{:type "text" :text "the answer"}]
                                           :usage {:input_tokens 3 :output_tokens 2}}})]
      (expect (not (contains? out :tool-calls)))
      (expect (= "the answer" (:content out))))))

(defdescribe chat-tool-call-extraction-test
  (it "extracts message.tool_calls (JSON-string args decoded) + carries tool_use on assistant-message"
    (let [envelope {:parsed {:choices [{:message {:content nil
                                                  :tool_calls [{:id "call_1" :type "function"
                                                                :function {:name "run_python"
                                                                           :arguments "{\"code\":\"print(1)\"}"}}]}}]
                             :usage {:prompt_tokens 9 :completion_tokens 4}}}
          out (extract-openai envelope)]
      (expect (= [{:id "call_1" :name "run_python" :input {:code "print(1)"}}]
                (:tool-calls out)))
      (expect (some #(= "tool_use" (:type %)) (get-in out [:assistant-message :content])))))

  (it "plain chat answer => no :tool-calls"
    (let [out (extract-openai {:parsed {:choices [{:message {:content "hello"}}]
                                        :usage {:prompt_tokens 1 :completion_tokens 1}}})]
      (expect (not (contains? out :tool-calls)))
      (expect (= "hello" (:content out))))))

(defdescribe responses-tool-call-extraction-test
  (it "extracts function_call items from :output and decodes arguments"
    (let [envelope {:parsed {:output [{:type "function_call" :call_id "fc_1"
                                       :name "run_python" :arguments "{\"code\":\"print(2)\"}"}]
                             :usage {:input_tokens 7 :output_tokens 3}}}
          out (extract-openai envelope)]
      (expect (= [{:id "fc_1" :name "run_python" :input {:code "print(2)"}}]
                (:tool-calls out))))))

(defdescribe responses-round-trip-test
  (it "assistant tool_use -> function_call item; user tool_result -> function_call_output item"
    (let [asst (responses-input {:role "assistant"
                                 :content [{:type "tool_use" :id "fc_1" :name "run_python"
                                            :input {:code "print(2)"}}]})
          usr  (responses-input {:role "user"
                                 :content [{:type "tool_result" :tool_use_id "fc_1" :content "2\n"}]})]
      (expect (= "function_call" (:type (first asst))))
      (expect (= "fc_1" (:call_id (first asst))))
      (expect (= "{\"code\":\"print(2)\"}" (:arguments (first asst))))
      (expect (= "function_call_output" (:type (first usr))))
      (expect (= "fc_1" (:call_id (first usr))))
      (expect (= "2\n" (:output (first usr))))))

  (it "a text message entry carries :type \"message\" (Codex backend rejects a typeless item)"
    ;; Regression: the ChatGPT Codex backend (chatgpt.com/backend-api/codex/
    ;; responses) validates strictly and 400s a typeless message item with
    ;; {"detail":"Unsupported content type"}. A text-bearing user/assistant
    ;; turn must emit `{:type "message" :role ... :content [...]}`.
    (let [usr  (responses-input {:role "user" :content "hello there"})
          asst (responses-input {:role "assistant" :content [{:type "text" :text "hi"}]})]
      (expect (= "message" (:type (first usr))))
      (expect (= "user" (:role (first usr))))
      (expect (= [{:type "input_text" :text "hello there"}] (:content (first usr))))
      (expect (= "message" (:type (first asst))))
      (expect (= "output_text" (:type (first (:content (first asst))))))))

  (it "every Responses input item has a non-nil :type"
    ;; A mixed turn (text + tool_use + thinking, then a tool_result) must not
    ;; produce any item without `:type` — the trap that broke the Codex wire.
    (let [input ((deref (var sut/build-openai-responses-request-body))
                 [{:role "system" :content "sys"}
                  {:role "user" :content "do it"}
                  {:role "assistant"
                   :content [{:type "thinking" :thinking "h" :thinking-signature "sig"}
                             {:type "text" :text "working"}
                             {:type "tool_use" :id "c1" :name "run_python" :input {:code "1"}}]}
                  {:role "user" :content [{:type "tool_result" :tool_use_id "c1" :content "1\n"}]}]
                 "gpt-5.5" {})]
      (expect (every? (comp some? :type) (:input input))))))

(defdescribe chat-round-trip-test
  (it "assistant tool_use -> message-level :tool_calls; user tool_result -> separate role:tool message"
    (let [msgs [{:role "user" :content "go"}
                {:role "assistant" :content [{:type "tool_use" :id "call_1" :name "run_python"
                                              :input {:code "print(1)"}}]}
                {:role "user" :content [{:type "tool_result" :tool_use_id "call_1" :content "1\n"}]}]
          body (build-chat msgs "glm" {})
          wire (:messages body)
          asst (first (filter #(= "assistant" (:role %)) wire))
          tool (first (filter #(= "tool" (:role %)) wire))]
      (expect (= "run_python" (get-in asst [:tool_calls 0 :function :name])))
      (expect (= "{\"code\":\"print(1)\"}" (get-in asst [:tool_calls 0 :function :arguments])))
      (expect (= "call_1" (:tool_call_id tool)))
      (expect (= "1\n" (:content tool)))
      ;; the tool_result-only user message must NOT survive as an empty user msg
      (expect (= 1 (count (filter #(= "user" (:role %)) wire)))))))

(defdescribe chat-streaming-fragment-assembly-test
  (it "reassembles delta.tool_calls fragments (id/name first, args concatenated) by index"
    (let [frags [{:index 0 :id "call_1" :type "function" :function {:name "run_python" :arguments "{\"co"}}
                 {:index 0 :function {:arguments "de\":\"print(1)\"}"}}]
          out (assemble-chat-frags frags)]
      (expect (= [{:id "call_1" :name "run_python" :input {:code "print(1)"}}] out))))

  (it "handles two parallel tool calls keyed by distinct :index"
    (let [frags [{:index 0 :id "a" :function {:name "f" :arguments "{}"}}
                 {:index 1 :id "b" :function {:name "g" :arguments "{\"x\":1}"}}]
          out (assemble-chat-frags frags)]
      (expect (= 2 (count out)))
      (expect (= "a" (:id (first out))))
      (expect (= {:x 1} (:input (second out))))))

  (it "merge-provider-state accumulates openai-chat fragments across chunks"
    (let [a {:provider :openai-chat :tool-call-fragments [{:index 0 :id "c" :function {:name "f" :arguments "{\"a"}}]}
          b {:provider :openai-chat :tool-call-fragments [{:index 0 :function {:arguments "\":2}"}}]}
          merged (merge-provider-state a b)
          calls (assemble-chat-frags (:tool-call-fragments merged))]
      (expect (= [{:id "c" :name "f" :input {:a 2}}] calls)))))

(defdescribe responses-streaming-function-call-test
  (it "extract-stream-delta surfaces a completed function_call as provider-state :tool-calls"
    (let [out (extract-stream-delta
                {:type "response.output_item.done"
                 :item {:type "function_call" :call_id "call_1" :name "run_python"
                        :arguments "{\"code\":\"print(6*7)\"}"}})]
      (expect (= :openai-responses (get-in out [:provider-state :provider])))
      (expect (= [{:id "call_1" :name "run_python" :input {:code "print(6*7)"}}]
                (get-in out [:provider-state :tool-calls])))))

  (it "ignores function_call args delta (accumulation handled at output_item.done)"
    (let [out (extract-stream-delta {:type "response.function_call_arguments.delta" :delta "{\"" :output_index 0})]
      (expect (nil? (:provider-state out)))))

  (it "function-call-item->tool-call decodes a complete item; nil for non-function items"
    (expect (= {:id "c" :name "f" :input {:x 1}}
              (fn-item->tool-call {:type "function_call" :call_id "c" :name "f" :arguments "{\"x\":1}"})))
    (expect (nil? (fn-item->tool-call {:type "reasoning"}))))

  (it "merge-provider-state concats + dedupes responses tool calls across output_item.done events"
    (let [a {:provider :openai-responses :tool-calls [{:id "c1" :name "f" :input {}}]}
          b {:provider :openai-responses :tool-calls [{:id "c2" :name "g" :input {}}]}
          dup {:provider :openai-responses :tool-calls [{:id "c1" :name "f" :input {}}]}
          m (merge-provider-state (merge-provider-state a b) dup)]
      (expect (= ["c1" "c2"] (mapv :id (:tool-calls m)))))))

(defdescribe responses-state-tool-calls-test
  (it "openai-responses-state carries function_call items as :tool-calls"
    (let [ps (openai-responses-state {:output [{:type "function_call" :call_id "fc" :name "run_python"
                                                :arguments "{\"code\":\"x\"}"}]})]
      (expect (= [{:id "fc" :name "run_python" :input {:code "x"}}] (:tool-calls ps)))))
  (it "returns nil when neither reasoning items nor tool calls present"
    (expect (nil? (openai-responses-state {:output [{:type "message"}]})))))

(defdescribe gemini-wire-test
  (describe "build-gemini-request-body"
    (it "maps system→systemInstruction, tools→functionDeclarations, choice→toolConfig"
      (let [body (build-gemini ["x"] "gemini-3-pro"
                   {:svar/tools [run-python] :svar/tool-choice :required})]
        (expect (= [{:functionDeclarations
                     [{:name "run_python" :parameters (:schema run-python)
                       :description "Execute Python in the sandbox."}]}]
                  (:tools body)))
        (expect (= {:functionCallingConfig {:mode "ANY"}} (:toolConfig body)))
        ;; no :svar/* leak
        (expect (not (contains? body :svar/tools)))))

    (it "system message folds into systemInstruction; user→\"user\", assistant→\"model\""
      (let [body (build-gemini [{:role "system" :content "be terse"}
                                {:role "user" :content "hi"}
                                {:role "assistant" :content "yo"}]
                   "gemini-3-pro" {})]
        (expect (= {:parts [{:text "be terse"}]} (:systemInstruction body)))
        (expect (= ["user" "model"] (mapv :role (:contents body))))
        (expect (= [{:text "hi"}] (:parts (first (:contents body))))))))

  (describe "gemini-tool-config"
    (it "auto/required/none/named"
      (expect (= {:functionCallingConfig {:mode "AUTO"}} (gemini-tool-config :auto)))
      (expect (= {:functionCallingConfig {:mode "NONE"}} (gemini-tool-config :none)))
      (expect (= {:functionCallingConfig {:mode "ANY" :allowedFunctionNames ["run_python"]}}
                (gemini-tool-config {:name "run_python"})))))

  (describe "extract-gemini-response-data"
    (it "pulls text + functionCall (args already a map) into :tool-calls + assistant tool_use"
      (let [out (extract-gemini {:parsed {:candidates [{:content {:role "model"
                                                                  :parts [{:text "let me run it"}
                                                                          {:functionCall {:name "run_python"
                                                                                          :args {:code "print(1)"}}}]}}]
                                          :usageMetadata {:promptTokenCount 10 :candidatesTokenCount 4}}})]
        (expect (= "let me run it" (:content out)))
        (expect (= "run_python" (:name (first (:tool-calls out)))))
        (expect (= {:code "print(1)"} (:input (first (:tool-calls out)))))
        (expect (some #(= "tool_use" (:type %)) (get-in out [:assistant-message :content])))
        (expect (= 10 (get-in out [:api-usage :input-tokens])))))

    (it "plain text answer => no :tool-calls"
      (let [out (extract-gemini {:parsed {:candidates [{:content {:parts [{:text "42"}]}}]
                                          :usageMetadata {:promptTokenCount 1 :candidatesTokenCount 1}}})]
        (expect (not (contains? out :tool-calls)))
        (expect (= "42" (:content out))))))

  (describe "round-trip"
    (it "tool_use→functionCall part; tool_result→functionResponse with name resolved by id"
      (let [body (build-gemini [{:role "user" :content "go"}
                                {:role "assistant"
                                 :content [{:type "tool_use" :id "gemini-run_python-0"
                                            :name "run_python" :input {:code "print(1)"}}]}
                                {:role "user"
                                 :content [{:type "tool_result" :tool_use_id "gemini-run_python-0"
                                            :content "1\n"}]}]
                   "gemini-3-pro" {})
            model-msg (first (filter #(= "model" (:role %)) (:contents body)))
            last-user (last (:contents body))]
        (expect (= {:name "run_python" :args {:code "print(1)"}}
                  (:functionCall (first (:parts model-msg)))))
        (expect (= "run_python" (get-in (first (:parts last-user)) [:functionResponse :name])))
        (expect (= {:result "1\n"} (get-in (first (:parts last-user)) [:functionResponse :response])))))))

(defdescribe anthropic-round-trip-test
  (it "prior tool_use (assistant) + tool_result (user) blocks re-emit onto the wire body"
    (let [msgs [{:role "user" :content "do it"}
                {:role "assistant"
                 :content [{:type "tool_use" :id "toolu_1" :name "run_python"
                            :input {"code" "1+1"}}]}
                {:role "user"
                 :content [{:type "tool_result" :tool_use_id "toolu_1" :content "2"}]}]
          body (build-anthropic msgs "claude" {:svar/tools [run-python]})
          wire-msgs (:messages body)
          asst (first (filter #(= "assistant" (:role %)) wire-msgs))
          usr  (last wire-msgs)]
      (expect (some #(= "tool_use" (:type %)) (:content asst)))
      (expect (= "toolu_1" (:tool_use_id (first (:content usr)))))
      (expect (= "tool_result" (:type (first (:content usr))))))))
