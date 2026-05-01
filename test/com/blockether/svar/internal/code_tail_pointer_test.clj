(ns com.blockether.svar.internal.code-tail-pointer-test
  "Tests for the code-format tail-pointer behavior in `ask-code!*`.

   Contract under test:

     - Default ON: a short reminder is appended as the LAST text block of
       the LAST user message, naming the chosen `:lang` and pointing the
       model at the fenced-code contract. Wording is parameterised by
       `:lang` so callers asking for `\"python\"` get a Python reminder.

     - `:code-tail-pointer? false` opts out \u2014 the user message is sent
       verbatim with no tail pointer.

     - Multimodal user content is preserved (tail pointer is added as one
       extra text block; image blocks are untouched).

     - When no user message exists in `:messages`, a synthetic user
       message carrying just the pointer is appended (degenerate input).

   All tests mock `llm/chat-completion` and inspect the messages
   captured at call time. No live LLM traffic. Mirrors
   `schema_tail_pointer_test.clj` for the `ask-code!*` path."
  (:require
   [clojure.string :as str]
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.core :as svar]
   [com.blockether.svar.internal.llm :as llm]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- mock-chat-completion
  "Captures every call into `calls-atom` and returns a canned response with
   one ```clojure``` fence so extraction yields a non-empty :result."
  [response calls-atom]
  (fn [messages model api-key url retry-opts]
    (swap! calls-atom conj
      {:messages messages :model model :api-key api-key
       :url url :retry-opts retry-opts})
    {:content       (:content response "```clojure\n(answer \"ok\")\n```")
     :reasoning     (:reasoning response)
     :api-usage     (:api-usage response {:prompt_tokens 10 :completion_tokens 20 :total_tokens 30})
     :http-response (:http-response response {:status 200 :url url})}))

(defn- test-router []
  (svar/make-router
    [{:id :test
      :api-key "sk-test"
      :base-url "https://example.invalid/v1"
      :api-style :openai-compatible-chat
      :models [{:name "test-model"}]}]))

(defn- user-msgs [msgs]
  (filterv #(= "user" (:role %)) msgs))

(defn- text-blocks
  "Returns the vec of text blocks for a message, normalizing string-content
   to a single-element vec for uniform inspection."
  [{:keys [content]}]
  (cond
    (string? content) [{:type "text" :text content}]
    (vector? content) (filterv #(= "text" (:type %)) content)
    :else []))

(defn- joined-text [msg]
  (->> (text-blocks msg) (map :text) (str/join "\n")))

;; =============================================================================
;; Default ON \u2014 pointer appended to last user, names the chosen lang
;; =============================================================================

(defdescribe code-tail-pointer-default-on-test
  (describe "default behavior (no :code-tail-pointer? key)"

    (it "appends a tail pointer to the last user message"
      (let [calls (atom [])]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask-code! (test-router)
            {:messages [(svar/system "You are helpful.")
                        (svar/user "Reply with (answer \"ok\").")]}))
        (let [msgs (:messages (first @calls))
              last-user (last (user-msgs msgs))
              tail-text (joined-text last-user)]
          ;; Pointer is present
          (expect (re-find #"fences" tail-text))
          (expect (re-find #"No prose outside fences" tail-text))
          ;; Original user content preserved
          (expect (re-find #"\(answer \"ok\"\)" tail-text)))))

    (it "names the default :lang \"clojure\" in the reminder"
      (let [calls (atom [])]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask-code! (test-router)
            {:messages [(svar/user "go")]}))
        (let [msgs (:messages (first @calls))
              tail-text (joined-text (last (user-msgs msgs)))]
          (expect (re-find #"clojure source" tail-text))
          (expect (re-find #"```clojure" tail-text)))))

    (it "names a non-default :lang in the reminder"
      (let [calls (atom [])]
        (with-redefs [llm/chat-completion (mock-chat-completion
                                            {:content "```python\nprint('hi')\n```"} calls)]
          (svar/ask-code! (test-router)
            {:messages [(svar/user "go")]
             :lang "python"}))
        (let [msgs (:messages (first @calls))
              tail-text (joined-text (last (user-msgs msgs)))]
          (expect (re-find #"python source" tail-text))
          (expect (re-find #"```python" tail-text))
          (expect (not (re-find #"clojure" tail-text))))))

    (it "places the pointer as the LAST text block of the last user message"
      (let [calls (atom [])]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask-code! (test-router)
            {:messages [(svar/user "first thing")
                        (svar/user "second thing")]}))
        (let [msgs (:messages (first @calls))
              users (user-msgs msgs)
              last-u (last users)
              blocks (text-blocks last-u)]
          ;; Pointer attached to the LAST user, not earlier ones
          (expect (re-find #"fences" (joined-text last-u)))
          (expect (not (re-find #"fences" (joined-text (first users)))))
          ;; Pointer is the LAST block of last-user content.
          (expect (re-find #"fences" (:text (last blocks)))))))))

;; =============================================================================
;; Opt-out \u2014 :code-tail-pointer? false sends user message verbatim
;; =============================================================================

(defdescribe code-tail-pointer-opt-out-test
  (describe ":code-tail-pointer? false"

    (it "skips the tail pointer entirely"
      (let [calls (atom [])]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask-code! (test-router)
            {:messages [(svar/user "Reply with (answer \"ok\").")]
             :code-tail-pointer? false}))
        (let [msgs (:messages (first @calls))
              last-user (last (user-msgs msgs))
              tail-text (joined-text last-user)]
          (expect (= "Reply with (answer \"ok\")." tail-text))
          (expect (not (re-find #"fences" tail-text))))))

    (it "treats nil/missing as ON (only literal `false` opts out)"
      (let [calls (atom [])]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask-code! (test-router)
            {:messages [(svar/user "go")]
             :code-tail-pointer? nil}))
        (let [msgs (:messages (first @calls))
              last-user (last (user-msgs msgs))]
          (expect (re-find #"fences" (joined-text last-user))))))))

;; =============================================================================
;; Multimodal preservation \u2014 pointer added as text block, images untouched
;; =============================================================================

(defdescribe code-tail-pointer-multimodal-preserved-test
  (describe "user message with image content"

    (it "appends the pointer as a text block without disturbing image blocks"
      (let [calls (atom [])
            img-block {:type "image_url"
                       :image_url {:url "data:image/png;base64,AAAA"}}]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask-code! (test-router)
            {:messages [{:role "user"
                         :content [{:type "text" :text "describe this"}
                                   img-block]}]}))
        (let [msgs (:messages (first @calls))
              last-user (last (user-msgs msgs))
              content (:content last-user)
              types (mapv :type content)]
          (expect (vector? content))
          (expect (some #(= "image_url" %) types))
          (expect (= "text" (:type (last content))))
          (expect (re-find #"fences" (:text (last content))))
          ;; Original prompt text still present
          (expect (some #(= "describe this" (:text %))
                    (filter #(= "text" (:type %)) content))))))))

;; =============================================================================
;; Degenerate input \u2014 no user message \u2192 synthesize one
;; =============================================================================

(defdescribe code-tail-pointer-no-user-message-test
  (describe "messages contain only system content"

    (it "synthesizes a user message carrying just the pointer"
      (let [calls (atom [])]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask-code! (test-router)
            {:messages [(svar/system "Generate a sample.")]}))
        (let [msgs (:messages (first @calls))
              users (user-msgs msgs)]
          (expect (= 1 (count users)))
          (expect (re-find #"fences" (joined-text (first users)))))))))
