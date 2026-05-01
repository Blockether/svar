(ns com.blockether.svar.internal.schema-tail-pointer-test
  "Tests for the schema tail-pointer behavior in `ask!*`.

   Contract under test:

     - Default ON: a short reminder is appended as the LAST text block of
       the LAST user message, pointing back to the cached schema in the
       system message. Schema body is NOT repeated at the tail.

     - `:schema-tail-pointer? false` opts out — head-only mode.

     - Multimodal user content is preserved (tail pointer is added as one
       extra text block; image blocks are untouched).

     - When no user message exists in `:messages`, a synthetic user
       message carrying the pointer is appended (degenerate input).

     - The tail pointer sits PAST the system message, so on Anthropic with
       `:cache-system? true` the cache breakpoint stays on system and the
       pointer is billed but uncached.

   All tests mock `llm/chat-completion` and inspect the `messages`
   captured at call time. No live LLM traffic.

   Mirrors the v0.4.0 spec-prompt placement contract that
   `llm_cache_test.clj` covers at the wire-shape layer."
  (:require
   [clojure.string :as str]
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.core :as svar]
   [com.blockether.svar.internal.llm :as llm]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- mock-chat-completion
  "Captures every call into `calls-atom` and returns a canned response."
  [response calls-atom]
  (fn [messages model api-key url retry-opts]
    (swap! calls-atom conj
      {:messages messages :model model :api-key api-key
       :url url :retry-opts retry-opts})
    {:content       (:content response "{\"answer\":\"ok\"}")
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

(def ^:private answer-spec
  (svar/spec
    (svar/field svar/NAME :answer
      svar/TYPE svar/TYPE_STRING
      svar/CARDINALITY svar/CARDINALITY_ONE
      svar/DESCRIPTION "The single-word answer")))

(defn- system-msgs [msgs]
  (filterv #(= "system" (:role %)) msgs))

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
;; Default ON — pointer appended to last user, schema body NOT duplicated
;; =============================================================================

(defdescribe tail-pointer-default-on-test
  (describe "default behavior (no :schema-tail-pointer? key)"

    (it "appends a tail pointer to the last user message"
      (let [calls (atom [])]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask! (test-router)
            {:spec answer-spec
             :messages [(svar/system "You are helpful.")
                        (svar/user "Reply with ok.")]}))
        (let [msgs (:messages (first @calls))
              last-user (last (user-msgs msgs))
              tail-text (joined-text last-user)]
          ;; Pointer is present
          (expect (re-find #"schema in the system message" tail-text))
          (expect (re-find #"First non-whitespace character MUST be" tail-text))
          ;; Original user content is preserved
          (expect (re-find #"Reply with ok\." tail-text)))))

    (it "does NOT duplicate the schema body at the tail (body stays at head)"
      (let [calls (atom [])]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask! (test-router)
            {:spec answer-spec
             :messages [(svar/system "You are helpful.")
                        (svar/user "Reply with ok.")]}))
        (let [msgs (:messages (first @calls))
              sys (first (system-msgs msgs))
              last-user (last (user-msgs msgs))
              sys-text (joined-text sys)
              tail-text (joined-text last-user)]
          ;; Schema body keywords appear in system (BAML rendering of :answer)
          (expect (re-find #"answer" sys-text))
          ;; Tail pointer never repeats schema body keywords
          (expect (not (re-find #"class " tail-text)))
          (expect (not (re-find #"Output JSON" tail-text))))))

    (it "places the pointer as the LAST text block of the last user message"
      (let [calls (atom [])]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask! (test-router)
            {:spec answer-spec
             :messages [(svar/user "first thing")
                        (svar/user "second thing")]}))
        (let [msgs (:messages (first @calls))
              users (user-msgs msgs)
              last-u (last users)
              blocks (text-blocks last-u)]
          ;; Pointer attached to the LAST user, not earlier ones
          (expect (re-find #"schema in the system message"
                    (joined-text last-u)))
          (expect (not (re-find #"schema in the system message"
                         (joined-text (first users)))))
          ;; Pointer is the LAST block in the last-user content, not
          ;; somewhere in the middle.
          (expect (re-find #"schema in the system message"
                    (:text (last blocks)))))))))

;; =============================================================================
;; Opt-out — :schema-tail-pointer? false → head-only mode
;; =============================================================================

(defdescribe tail-pointer-opt-out-test
  (describe ":schema-tail-pointer? false"

    (it "skips the tail pointer entirely"
      (let [calls (atom [])]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask! (test-router)
            {:spec answer-spec
             :messages [(svar/user "Reply with ok.")]
             :schema-tail-pointer? false}))
        (let [msgs (:messages (first @calls))
              last-user (last (user-msgs msgs))
              tail-text (joined-text last-user)]
          ;; User message is unchanged from caller input
          (expect (= "Reply with ok." tail-text))
          (expect (not (re-find #"schema in the system message" tail-text))))))

    (it "still places the schema body in the system message (head)"
      (let [calls (atom [])]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask! (test-router)
            {:spec answer-spec
             :messages [(svar/user "Reply with ok.")]
             :schema-tail-pointer? false}))
        (let [msgs (:messages (first @calls))
              sys (first (system-msgs msgs))]
          (expect (some? sys))
          (expect (re-find #"answer" (joined-text sys))))))

    (it "treats nil/missing as ON (only literal `false` opts out)"
      (let [calls (atom [])]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask! (test-router)
            {:spec answer-spec
             :messages [(svar/user "Reply with ok.")]
             :schema-tail-pointer? nil}))
        (let [msgs (:messages (first @calls))
              last-user (last (user-msgs msgs))]
          (expect (re-find #"schema in the system message"
                    (joined-text last-user))))))))

;; =============================================================================
;; Multimodal preservation — pointer added as text block, images untouched
;; =============================================================================

(defdescribe tail-pointer-multimodal-preserved-test
  (describe "user message with image content"

    (it "appends the pointer as a text block without disturbing image blocks"
      (let [calls (atom [])
            img-block {:type "image_url"
                       :image_url {:url "data:image/png;base64,AAAA"}}]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask! (test-router)
            {:spec answer-spec
             :messages [{:role "user"
                         :content [{:type "text" :text "describe this"}
                                   img-block]}]}))
        (let [msgs (:messages (first @calls))
              last-user (last (user-msgs msgs))
              content (:content last-user)
              types (mapv :type content)]
          ;; Image block survives, pointer added at the end
          (expect (vector? content))
          (expect (some #(= "image_url" %) types))
          (expect (= "text" (:type (last content))))
          (expect (re-find #"schema in the system message"
                    (:text (last content))))
          ;; Original prompt text still present
          (expect (some #(= "describe this" (:text %))
                    (filter #(= "text" (:type %)) content))))))))

;; =============================================================================
;; Degenerate input — no user message → synthesize one
;; =============================================================================

(defdescribe tail-pointer-no-user-message-test
  (describe "messages contain only system content"

    (it "synthesizes a user message carrying just the pointer"
      (let [calls (atom [])]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask! (test-router)
            {:spec answer-spec
             :messages [(svar/system "Generate a sample.")]}))
        (let [msgs (:messages (first @calls))
              users (user-msgs msgs)]
          (expect (= 1 (count users)))
          (expect (re-find #"schema in the system message"
                    (joined-text (first users)))))))))

;; =============================================================================
;; Cache breakpoint placement — pointer sits PAST the system breakpoint
;; =============================================================================

(defdescribe tail-pointer-past-cache-breakpoint-test
  (describe "with :cache-system? true on Anthropic api-style"

    (it "marks the system message cached and leaves the user pointer uncached"
      (let [calls (atom [])
            anthropic-router (svar/make-router
                               [{:id :anth
                                 :api-key "sk-test"
                                 :base-url "https://example.invalid/v1"
                                 :api-style :anthropic
                                 :models [{:name "claude-test"}]}])]
        (with-redefs [llm/chat-completion (mock-chat-completion {} calls)]
          (svar/ask! anthropic-router
            {:spec answer-spec
             :messages [(svar/system "rules")
                        (svar/user "Reply with ok.")]
             :cache-system? true}))
        (let [msgs (:messages (first @calls))
              sys (first (system-msgs msgs))
              last-user (last (user-msgs msgs))
              sys-blocks (text-blocks sys)
              user-blocks (text-blocks last-user)]
          ;; Schema body in system, last system block has :svar/cache true
          (expect (true? (:svar/cache (last sys-blocks))))
          ;; Tail pointer block on user has NO cache marker — it sits past
          ;; the breakpoint and stays uncached every call.
          (let [pointer-block (->> user-blocks
                                (filter #(re-find #"schema in the system message"
                                           (:text %)))
                                first)]
            (expect (some? pointer-block))
            (expect (nil? (:svar/cache pointer-block)))))))))
