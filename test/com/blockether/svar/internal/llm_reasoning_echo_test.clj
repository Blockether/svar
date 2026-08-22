(ns com.blockether.svar.internal.llm-reasoning-echo-test
  "A Responses envelope carries the request's reasoning CONFIG under
   `reasoning` - `{\"effort\" \"high\" \"summary\" \"detailed\"}` - while its
   reasoning TEXT rides `output` reasoning items alone. openai/codex splits the
   two the same way: its wire model knows reasoning only as an output item and
   builds the top-level `reasoning` object from the request's effort/summary
   settings. These pin that the extraction never reads the setting as thinking."
  (:require [com.blockether.svar.internal.llm :as sut]
            [lazytest.core :refer [defdescribe expect it]]))

(def ^:private extract @#'sut/extract-response-data)
(def ^:private reasoning-text @#'sut/reasoning-part-text)

(def ^:private config-echo {"effort" "high" "summary" "detailed"})

(defn- envelope
  [response]
  {:parsed response
   :raw-body ""
   :url "https://chatgpt.com/backend-api/codex/responses"
   :status 200})

(defn- responses-body
  [summary]
  {"id" "resp_1"
   "object" "response"
   "status" "completed"
   "reasoning" config-echo
   "output"
   (into (if summary
           [{"id" "rs_1" "type" "reasoning" "summary" [{"type" "summary_text" "text" summary}]}]
           [])
         [{"id" "msg_1"
           "type" "message"
           "role" "assistant"
           "content" [{"type" "output_text" "text" "answer"}]}])})

(defdescribe
  reasoning-config-echo-test
  ;; Regression: the config echo won over the output items, so a caller rendered
  ;; the literal word `detailed` as the model's thinking on every turn.
  (it "reads the output item's summary, not the config echo"
      (let [{:keys [content reasoning]} (extract (envelope (responses-body
                                                             "**Checking the footer**")))]
        (expect (= "answer" content))
        (expect (= "**Checking the footer**" reasoning))))
  (it "reports no reasoning when the model wrote no summary"
      (let [{:keys [content reasoning]} (extract (envelope (responses-body nil)))]
        (expect (= "answer" content))
        (expect (nil? reasoning))))
  (it "still reads a chat-completions reasoning channel"
      ;; The gate is about the Responses envelope only - chat completions keep
      ;; carrying preserved thinking on the message.
      (let [{:keys [reasoning]} (extract (envelope {"choices" [{"message" {"content" "answer"
                                                                           "reasoning_content"
                                                                           "step one"}}]}))]
        (expect (= "step one" reasoning))))
  (it "still reads a gateway's own top-level reasoning text"
      (let [{:keys [reasoning]} (extract (envelope {"choices" [{"message" {"content" "answer"}}]
                                                    "reasoning" "step one"}))]
        (expect (= "step one" reasoning))))
  (it "refuses the config echo as reasoning text"
      (expect (nil? (reasoning-text config-echo)))
      (expect (nil? (reasoning-text {"effort" "medium" "summary" nil})))
      (expect (nil? (reasoning-text {"summary" "auto"}))))
  (it "keeps reading a real reasoning item"
      (expect (= "thought"
                 (reasoning-text {"type" "reasoning"
                                  "summary" [{"type" "summary_text" "text" "thought"}]})))
      (expect (= "thought"
                 (reasoning-text {"summary" [{"type" "summary_text" "text" "thought"}]})))))
