(ns com.blockether.svar.internal.llm-cache-test
  "Prompt caching + multi-block content + spec-prompt placement.

   These tests cover the wire-shape contract that downstream agent loops rely
   on for prompt caching:

   - `cached` markers survive `system`/`user`/`assistant` canonical content.
   - Anthropic emits `cache_control: {type: \"ephemeral\"}` per marked block.
   - GPT-5.6+ Responses emits exact rolling `prompt_cache_breakpoint` markers
     where the ENDPOINT takes them; older OpenAI wires, and a backend that
     refuses the field, strip unsupported `:svar/*` markers.
   - `extract-anthropic-response-data` surfaces
     `cache_creation_input_tokens` / `cache_read_input_tokens` on
     `:prompt_tokens_details` so downstream callers see real cache hits.

   Pure data, except one funnel test that stubs the transport — no LLM calls."
  (:require [clojure.string]
            [com.blockether.svar.core :as svar]
            [com.blockether.svar.internal.failure :as failure]
            [lazytest.core :refer [defdescribe expect it]]
            [com.blockether.svar.internal.llm :as sut]))

;;; ── cached helper ─────────────────────────────────────────────────────

(defdescribe cached-helper-test
             (it "tags a text block with :svar/cache true (default 5min)"
                 (expect (= {:type "text" :text "hello" :svar/cache true} (sut/cached "hello"))))
             (it "honors :ttl :1h"
                 (expect (= {:type "text" :text "hello" :svar/cache true :svar/cache-ttl :1h}
                            (sut/cached "hello" {:ttl :1h}))))
             (it "rejects unknown ttl values at body-build time"
                 ;; cached itself is permissive (just stores the keyword); the throw
                 ;; happens when build-anthropic-request-body translates it.
                 (let [bad-msg (sut/system [(sut/cached "x" {:ttl :weird})])]
                   (expect (try
                             (#'sut/build-anthropic-request-body [bad-msg] "claude-haiku-4-5" nil)
                             false
                             (catch clojure.lang.ExceptionInfo e
                               (= :svar.core/invalid-cache-ttl (:type (ex-data e)))))))))

;;; ── Anthropic body shape ──────────────────────────────────────────────

(defdescribe
  anthropic-system-string-shape-test
  (it "emits :system as STRING when no system block carries a cache marker"
      (let [msgs
            [(sut/system "core agent prompt") (sut/user "what is 2+2?")]

            body
            (#'sut/build-anthropic-request-body msgs "claude-haiku-4-5" nil)]

        (expect (= "core agent prompt" (:system body)))
        (expect (string? (:system body)))))
  (it "emits :system as ARRAY when any block is cached"
      (let [msgs
            [(sut/system [(sut/cached "stable agent rules") "live env block"])
             (sut/user "what is 2+2?")]

            body
            (#'sut/build-anthropic-request-body msgs "claude-haiku-4-5" nil)

            sys
            (:system body)]

        (expect (vector? sys))
        (expect (= 2 (count sys)))
        (expect (= {:type "text" :text "stable agent rules" :cache_control {:type "ephemeral"}}
                   (first sys)))
        ;; Second (uncached) block has no :cache_control and no :svar/* keys.
        (expect (= {:type "text" :text "live env block"} (second sys)))))
  (it "emits :cache_control with :ttl \"1h\" when :svar/cache-ttl :1h"
      (let [body (#'sut/build-anthropic-request-body
                  [(sut/system [(sut/cached "x" {:ttl :1h})])]
                  "claude-haiku-4-5"
                  nil)]
        (expect (= {:type "ephemeral" :ttl "1h"} (:cache_control (first (:system body)))))))
  (it "merges multiple system messages preserving cache markers"
      (let [msgs
            [(sut/system "rules-A") (sut/system [(sut/cached "rules-B")])]

            body
            (#'sut/build-anthropic-request-body msgs "claude-haiku-4-5" nil)]

        (expect (vector? (:system body)))
        (expect (= 2 (count (:system body))))
        (expect (nil? (:cache_control (first (:system body)))))
        (expect (= {:type "ephemeral"} (:cache_control (second (:system body)))))))
  (it "translates cache markers on user content blocks too"
      (let [msgs
            [(sut/system "rules")
             {:role "user" :content [(sut/cached "long context") "current ask"]}]

            body
            (#'sut/build-anthropic-request-body msgs "claude-haiku-4-5" nil)

            user-msg
            (-> body
                :messages
                first)]

        (expect (vector? (:content user-msg)))
        (expect (= {:type "text" :text "long context" :cache_control {:type "ephemeral"}}
                   (first (:content user-msg))))
        (expect (= {:type "text" :text "current ask"} (second (:content user-msg))))))
  (it "leaves bare-text user messages as plain strings on the wire"
      (let [body
            (#'sut/build-anthropic-request-body
             [(sut/system "rules") (sut/user "hi")]
             "claude-haiku-4-5"
             nil)

            user-msg
            (-> body
                :messages
                first)]

        (expect (= "hi" (:content user-msg)))
        (expect (string? (:content user-msg))))))

;;; ── OpenAI body shape (cache markers stripped) ────────────────────────

(defdescribe openai-strips-svar-markers-test
             (it "strips :svar/cache from system content"
                 (let [body
                       (#'sut/build-request-body
                        [(sut/system [(sut/cached "stable") "live"]) (sut/user "ask")]
                        "gpt-4o"
                        nil)

                       sys-msg
                       (->> (:messages body)
                            (filter #(= "system" (:role %)))
                            first)]

                   (expect (vector? (:content sys-msg)))
                   (expect (every? #(nil? (:svar/cache %)) (:content sys-msg)))
                   (expect (every? #(nil? (:cache_control %)) (:content sys-msg)))))
             (it
               "collapses single plain-text content back to string"
               (let [body
                     (#'sut/build-request-body [(sut/system "rules") (sut/user "hi")] "gpt-4o" nil)

                     msgs
                     (:messages body)]

                 (expect (= "rules" (:content (first msgs))))
                 (expect (= "hi" (:content (second msgs))))))
             (it "strips :svar/cache from user content blocks"
                 (let [body
                       (#'sut/build-request-body
                        [(sut/system "rules") {:role "user" :content [(sut/cached "stable") "ask"]}]
                        "gpt-4o"
                        nil)

                       user-msg
                       (-> body
                           :messages
                           second)]

                   (expect (vector? (:content user-msg)))
                   (expect (every? #(nil? (:svar/cache %)) (:content user-msg))))))

(defdescribe
  openai-responses-prompt-cache-breakpoint-test
  (it "GPT-5.6 marks the previous turn boundary so a growing conversation reads the cache"
      (let [body
            (#'sut/build-openai-responses-request-body
             [{:role "system" :content [(sut/cached "stable rules")]}
              {:role "user" :content "turn one"} {:role "assistant" :content "answer one"}
              {:role "user" :content [(sut/cached "turn two")]}]
             "gpt-5.6-sol"
             {:prompt_cache_key "session-1"})

            messages
            (filterv #(= "message" (:type %)) (:input body))

            first-user
            (first messages)

            current-user
            (last messages)]

        ;; GPT-5.6 matches exact breakpoints and does not fall back to an older
        ;; unmarked prefix. The previous user boundary is the prefix written by
        ;; the preceding request; marking it is what reuses the whole prior turn.
        (expect (= {:mode "explicit"} (get-in first-user [:content 0 :prompt_cache_breakpoint])))
        (expect (= {:mode "explicit"}
                   (get-in current-user [:content 0 :prompt_cache_breakpoint])))))
  (it "carries a rolling breakpoint through function_call_output tool results"
      (let [body
            (#'sut/build-openai-responses-request-body
             [{:role "user" :content "run it"}
              {:role "assistant"
               :content [{:type "tool_use"
                          :id "call-1|item-1"
                          :name "python_execution"
                          :input {:code "print(1)"}}]}
              {:role "user"
               :content
               [{:type "tool_result" :tool_use_id "call-1|item-1" :content "1" :svar/cache true}]}]
             "gpt-5.6-sol"
             {:prompt_cache_key "session-1"})

            first-user
            (first (:input body))

            tool-output
            (last (:input body))]

        (expect (= {:mode "explicit"} (get-in first-user [:content 0 :prompt_cache_breakpoint])))
        (expect (= "input_text" (get-in tool-output [:output 0 :type])))
        (expect (= {:mode "explicit"} (get-in tool-output [:output 0 :prompt_cache_breakpoint])))))
  (it "models before GPT-5.6 keep stripping unsupported breakpoint fields"
      (let [body (#'sut/build-openai-responses-request-body
                  [{:role "user" :content [(sut/cached "stable")]}]
                  "gpt-5.5"
                  {})]
        (expect (nil? (get-in body [:input 0 :content 0 :prompt_cache_breakpoint]))))))

(defdescribe
  responses-explicit-cache-endpoint-test
  ;; Regression: `prompt_cache_breakpoint` was gated on the MODEL NAME alone, so
  ;; a `gpt-5.6-*` model served by the ChatGPT Codex backend answered HTTP 400
  ;; "prompt_cache_breakpoint is not supported on this model" from the second
  ;; request of a turn onwards - the first request has a single input boundary,
  ;; so no rolling marker is written yet - and the agent turn died there.
  (it "refuses the field for the ChatGPT Codex backend and keeps it for OpenAI"
      (expect (not (#'sut/responses-explicit-cache-endpoint?
                    :openai-codex
                    "https://chatgpt.com/backend-api")))
      ;; a differently named provider pointed at that same backend is that wire
      (expect (not (#'sut/responses-explicit-cache-endpoint?
                    :my-codex
                    "https://chatgpt.com/backend-api")))
      (expect (#'sut/responses-explicit-cache-endpoint? :openai "https://api.openai.com/v1"))
      (expect (#'sut/responses-explicit-cache-endpoint? :gateway "https://gw.example.invalid/v1")))
  (it
    "builds a GPT-5.6 body without markers once the endpoint refuses them"
    (let [msgs
          [{:role "user" :content "turn one"} {:role "assistant" :content "answer one"}
           {:role "user" :content [(sut/cached "turn two")]}]

          off
          (#'sut/build-openai-responses-request-body msgs "gpt-5.6-sol" {} {:explicit-cache? false})

          on
          (#'sut/build-openai-responses-request-body msgs "gpt-5.6-sol" {} nil)]

      (expect (nil? (re-find #"prompt_cache_breakpoint" (pr-str off))))
      ;; the model contract itself is unchanged - only the endpoint gate moved
      (expect (some? (re-find #"prompt_cache_breakpoint" (pr-str on))))))
  (it "gates a session delta by the endpoint answer, not the model name"
      ;; Regression: the incremental input a session sends from its second turn on
      ;; decided the marker from the model name alone, so a cached block still put
      ;; the field the Codex backend refuses on the wire of every later turn.
      (let [msgs
            [{:role "user" :content [(sut/cached "turn two")]}]

            off
            (#'sut/messages->responses-input msgs "gpt-5.6-sol" false)

            on
            (#'sut/messages->responses-input msgs "gpt-5.6-sol" true)

            old
            (#'sut/messages->responses-input msgs "gpt-5.5" true)]

        (expect (nil? (re-find #"prompt_cache_breakpoint" (pr-str off))))
        (expect (some? (re-find #"prompt_cache_breakpoint" (pr-str on))))
        ;; the model contract still decides on an endpoint that takes the field
        (expect (nil? (re-find #"prompt_cache_breakpoint" (pr-str old))))))
  (it "keeps the field off a Codex request built by the dispatch funnel"
      ;; The reported shape: system + task + tool call + tool result is the second
      ;; iteration of any agent turn, and it is where the rolling marker first
      ;; lands on an item the ChatGPT Codex backend rejects with HTTP 400.
      (let [captured
            (atom [])

            router
            (svar/make-router [{:id :openai-codex
                                :api-key "test-key"
                                :base-url "https://chatgpt.com/backend-api"
                                :api-style :openai-compatible-responses
                                :responses-path "/codex/responses"
                                :models
                                [{:name "gpt-5.6" :context 100000 :input 1.0 :output 1.0}]}])]

        (with-redefs [sut/openai-responses-completion (fn [body _opts]
                                                        (swap! captured conj body)
                                                        {:content "ok" :model "gpt-5.6" :usage {}})]
          (svar/ask! router
                     {:messages
                      [{:role "system" :content "agent rules"} {:role "user" :content "task"}
                       {:role "assistant"
                        :content [{:type "tool_use" :id "call_1|fc_1" :name "run" :input {}}]}
                       {:role "user"
                        :content [{:type "tool_result" :tool_use_id "call_1|fc_1" :content "1"}]}]
                      :routing {:provider :openai-codex :model "gpt-5.6"}}))
        (expect (= 1 (count @captured)))
        (expect (nil? (re-find #"prompt_cache_breakpoint" (pr-str @captured))))
        ;; what Codex itself caches on is untouched
        (expect (some? (:prompt_cache_key (first @captured)))))))

(defdescribe
  explicit-cache-self-heal-verdict-test
  (it "self-heals on the endpoint's own rejection, and only before output"
      (let [refusal (fn [data]
                      (ex-info "prompt_cache_breakpoint is not supported on this model"
                               (merge {:status 400} data)))]
        (expect (failure/retry-without-explicit-cache? (refusal {})))
        ;; already streamed bytes: a resend would duplicate them
        (expect (not (failure/retry-without-explicit-cache? (refusal {:content-acc-len 12}))))
        (expect (not (failure/retry-without-explicit-cache? (ex-info "Overloaded"
                                                                     {:status 529}))))))
  (it "remembers the host that refused the field"
      (let [url "https://breakpoint-test.example.invalid/v1"]
        (try (expect (not (failure/explicit-cache-refused-host? url)))
             (failure/mark-explicit-cache-refused! url)
             (expect (failure/explicit-cache-refused-host? url))
             (expect (not (failure/explicit-cache-refused-host?
                            "https://other.example.invalid/v1")))
             (finally (swap! failure/explicit-cache-refused-hosts* disj
                        "breakpoint-test.example.invalid")))))
  (it
    "drops the markers and retries once when an endpoint refuses them"
    (let [captured
          (atom [])

          calls
          (atom 0)

          router
          (svar/make-router [{:id :gw
                              :api-key "k"
                              :base-url "https://cachegate.example.invalid/v1"
                              :api-style :openai-compatible-responses
                              :models [{:name "gpt-5.6" :context 100000 :input 1.0 :output 1.0}]}])

          msgs
          [{:role "system" :content "agent rules"} {:role "user" :content "task"}
           {:role "assistant" :content [{:type "tool_use" :id "call_1|fc_1" :name "run" :input {}}]}
           {:role "user" :content [{:type "tool_result" :tool_use_id "call_1|fc_1" :content "1"}]}]

          respond
          (fn [body _opts]
            (swap! captured conj body)
            (if (= 1 (swap! calls inc))
              (throw (ex-info "prompt_cache_breakpoint is not supported on this model"
                              {:status 400 :source :provider}))
              {:content "ok" :model "gpt-5.6" :usage {}}))]

      (try (with-redefs [sut/openai-responses-completion respond]
             (svar/ask! router {:messages msgs :routing {:provider :gw :model "gpt-5.6"}})
             ;; the host is remembered, so the next turn never sends the field again
             (svar/ask! router {:messages msgs :routing {:provider :gw :model "gpt-5.6"}}))
           (expect (= 3 @calls))
           (expect (= [1 0 0]
                      (mapv #(count (re-seq #"prompt_cache_breakpoint" (pr-str %))) @captured)))
           (finally (swap! failure/explicit-cache-refused-hosts* disj
                      "cachegate.example.invalid"))))))

(defdescribe openai-canonical-usage-test
             ;; Phase A: normalize-openai-usage emits canonical shape — :input-tokens
             ;; TOTAL inclusive, :input-tokens-details split into regular/cache-write/
             ;; cache-read. Invariant: regular + cache-write + cache-read = input-tokens.
             (it "Chat Completions: surfaces total + cache-read split"
                 (let [usage (#'sut/normalize-openai-usage
                              {"prompt_tokens" 100
                               "completion_tokens" 10
                               "total_tokens" 110
                               "prompt_tokens_details" {"cached_tokens" 80}})]
                   (expect (= 100 (:input-tokens usage)))
                   (expect (= 10 (:output-tokens usage)))
                   (expect (= 110 (:total-tokens usage)))
                   (let [d (:input-tokens-details usage)]
                     (expect (= 80 (:cache-read d)))
                     (expect (= 0 (:cache-write d)))
                     (expect (= 20 (:regular d))))))
             (it "Responses API: same canonical shape from input_tokens_details"
                 (let [usage (#'sut/normalize-openai-usage
                              {"input_tokens" 100
                               "output_tokens" 10
                               "total_tokens" 110
                               "input_tokens_details" {"cached_tokens" 80}})]
                   (expect (= 100 (:input-tokens usage)))
                   (expect (= 80 (get-in usage [:input-tokens-details :cache-read]))))))

(defdescribe
  anthropic-canonical-usage-test
  ;; Anthropic raw input_tokens excludes cached + cache-creation. Phase A
  ;; canonical sums all three to TOTAL, splits onto :input-tokens-details.
  (it "sums uncached + cache-read into TOTAL"
      (let [envelope
            {:parsed {"content" [{"type" "text" "text" "hi"}]
                      "usage" {"input_tokens" 100 "output_tokens" 10 "cache_read_input_tokens" 80}}}

            {:keys [api-usage]}
            (#'sut/extract-anthropic-response-data envelope)]

        (expect (= 180 (:input-tokens api-usage)))
        (expect (= 10 (:output-tokens api-usage)))
        (let [d (:input-tokens-details api-usage)]
          (expect (= 100 (:regular d)))
          (expect (= 80 (:cache-read d)))
          (expect (= 0 (:cache-write d))))))
  (it "surfaces cache_creation_input_tokens as :cache-write"
      (let [envelope
            {:parsed {"content" [{"type" "text" "text" "hi"}]
                      "usage"
                      {"input_tokens" 100 "output_tokens" 10 "cache_creation_input_tokens" 90}}}

            {:keys [api-usage]}
            (#'sut/extract-anthropic-response-data envelope)]

        (expect (= 190 (:input-tokens api-usage)))
        (expect (= 90 (get-in api-usage [:input-tokens-details :cache-write])))))
  (it "still produces canonical shape when neither cache field is present"
      (let [envelope
            {:parsed {"content" [{"type" "text" "text" "hi"}]
                      "usage" {"input_tokens" 100 "output_tokens" 10}}}

            {:keys [api-usage]}
            (#'sut/extract-anthropic-response-data envelope)]

        (expect (= 100 (:input-tokens api-usage)))
        (let [d (:input-tokens-details api-usage)]
          (expect (= 100 (:regular d)))
          (expect (= 0 (:cache-read d)))
          (expect (= 0 (:cache-write d)))))))

;;; ── Content normalization edge cases ──────────────────────────────────

(defdescribe
  normalize-content-test
  (it "wraps a bare string" (expect (= [{:type "text" :text "x"}] (#'sut/normalize-content "x"))))
  (it "passes through a single text block"
      (expect (= [{:type "text" :text "x"}] (#'sut/normalize-content {:type "text" :text "x"}))))
  (it "treats nil/empty as []"
      (expect (= [] (#'sut/normalize-content nil)))
      (expect (= [] (#'sut/normalize-content []))))
  (it "wraps strings inside a vector"
      (expect (= [{:type "text" :text "a"} {:type "text" :text "b"}]
                 (#'sut/normalize-content ["a" "b"]))))
  (it "preserves :svar/cache markers through normalization"
      (let [in
            [(sut/cached "stable") "live"]

            out
            (#'sut/normalize-content in)]

        (expect (= 2 (count out)))
        (expect (true? (-> out
                           first
                           :svar/cache)))
        (expect (nil? (-> out
                          second
                          :svar/cache))))))

;;; ── apply-llm-opts middleware (Phase 0 / svar 0.6.0) ─────────────────────
;;
;; The single chokepoint runs once per direct LLM call. These tests
;; pin every wire-body-affecting behaviour so the call paths from
;; ask!* / ask-code!* (and the wrappers that delegate to ask!*) all
;; produce the same shape regardless of caller-side opt phrasing.
;;
;; Bug-tracking ids match vis PLAN.md.

(defdescribe
  apply-llm-opts-test
  (it "S1: top-level :system opt becomes the first :role system message"
      ;; The middleware also runs auto-cache (S3), so the content gets
      ;; normalised into a block vector with `:svar/cache true` on the
      ;; last block. Assert SHAPE of the result, not the bare string
      ;; the caller passed.
      (let [[msgs _] (sut/apply-llm-opts [{:role "user" :content "hi"}] {:system "TOP-LEVEL"})]
        (expect (= "system"
                   (-> msgs
                       first
                       :role)))
        (expect (= "TOP-LEVEL"
                   (-> msgs
                       first
                       :content
                       first
                       :text)))
        (expect (true? (-> msgs
                           first
                           :content
                           last
                           :svar/cache)))))
  (it "S2: keyword :role :system is normalised to string"
      (let [[msgs _] (sut/apply-llm-opts [{:role :system :content "x"} {:role :user :content "hi"}]
                                         {})]
        (expect (= "system"
                   (-> msgs
                       first
                       :role)))
        (expect (= "user"
                   (-> msgs
                       second
                       :role)))))
  (it "S3: auto-cache marker added to LAST block of LAST system message"
      (let [[msgs _] (sut/apply-llm-opts [{:role "system" :content "stable"}
                                          {:role "user" :content "hi"}]
                                         {})]
        (let [sys-content (-> msgs
                              first
                              :content)]
          (expect (vector? sys-content))
          (expect (true? (-> sys-content
                             last
                             :svar/cache))))))
  (it "S3: auto-cache is NO-OP when caller already tagged a block manually"
      (let [in
            [{:role "system"
              :content [{:type "text" :text "a"} {:type "text" :text "b" :svar/cache true}]}
             {:role "user" :content "hi"}]

            [msgs _]
            (sut/apply-llm-opts in {})]

        ;; First block stays unmarked; second keeps the caller's marker;
        ;; auto-mode does NOT add a third marker to the same position.
        (let [sys-content (-> msgs
                              first
                              :content)]
          (expect (nil? (-> sys-content
                            first
                            :svar/cache)))
          (expect (true? (-> sys-content
                             second
                             :svar/cache))))))
  (it "S3: no system message → no-op (does not invent one)"
      (let [[msgs _] (sut/apply-llm-opts [{:role "user" :content "hi"}] {})]
        (expect (= 1 (count msgs)))
        (expect (= "user"
                   (-> msgs
                       first
                       :role)))))
  (it "S5: :cache-key opt forwards to :extra-body :prompt_cache_key"
      (let [[_ opts] (sut/apply-llm-opts [{:role "user" :content "hi"}] {:cache-key "session-abc"})]
        (expect (= "session-abc"
                   (-> opts
                       :extra-body
                       :prompt_cache_key)))))
  (it "S5: existing :extra-body keys preserved alongside :prompt_cache_key"
      (let [[_ opts] (sut/apply-llm-opts [{:role "user" :content "hi"}]
                                         {:cache-key "k" :extra-body {:temperature 0.3}})]
        (expect (= 0.3
                   (-> opts
                       :extra-body
                       :temperature)))
        (expect (= "k"
                   (-> opts
                       :extra-body
                       :prompt_cache_key)))))
  (it "S5: no :cache-key, NO api-style → :extra-body untouched"
      (let [[_ opts] (sut/apply-llm-opts [{:role "user" :content "hi"}]
                                         {:extra-body {:temperature 0.3}})]
        (expect (= {:temperature 0.3} (:extra-body opts)))))
  (it "S5/S7: no :cache-key + openai-style + system content → AUTO-GEN cache key"
      ;; Codex specifically refuses to surface cached_tokens in
      ;; response.completed unless prompt_cache_key is on the wire.
      ;; svar 0.6.0 auto-generates a stable system-prompt-SHA1-derived
      ;; key when the caller didn't pass one AND api-style is
      ;; openai-compatible-*. Anthropic ignores the field (stripped at
      ;; wire-build time) so we skip the auto-gen there to save work.
      (let [[_ opts] (sut/apply-llm-opts [{:role "system" :content "stable system prompt"}
                                          {:role "user" :content "hi"}]
                                         {:api-style :openai-compatible-responses})]
        (let [key (get-in opts [:extra-body :prompt_cache_key])]
          (expect (string? key))
          (expect (clojure.string/starts-with? key "svar-auto-")))))
  (it "S7: auto-gen key is DETERMINISTIC — same system + same conversation head produces same key"
      (let [run #(get-in (second (sut/apply-llm-opts (into [{:role "system" :content "identical"}
                                                            {:role "user" :content "opening turn"}]
                                                           %)
                                                     {:api-style :openai-compatible-chat}))
                         [:extra-body :prompt_cache_key])]
        (expect (= (run [])
                   (run [{:role "assistant" :content "a"}])
                   (run [{:role "assistant" :content "a"} {:role "user" :content "and then?"}])))))
  (it "S7: auto-gen key SEPARATES conversations that share one system prompt"
      ;; The whole point: one key = one routing bucket with a per-key request
      ;; ceiling. Every concurrent session hashing to the SAME key overflows it
      ;; and the sessions evict each other's prefixes.
      (let [run #(get-in (second (sut/apply-llm-opts [{:role "system" :content "identical"}
                                                      {:role "user" :content %}]
                                                     {:api-style :openai-compatible-chat}))
                         [:extra-body :prompt_cache_key])]
        (expect (not= (run "session one") (run "session two")))
        ;; ...while the system half still keeps them on a common prefix.
        (expect (= (subs (run "session one") 0 (count "svar-auto-"))
                   (subs (run "session two") 0 (count "svar-auto-"))))))
  (it "S7: auto-gen key changes when the SYSTEM prompt changes"
      (let [run #(get-in (second (sut/apply-llm-opts [{:role "system" :content %}
                                                      {:role "user" :content "same head"}]
                                                     {:api-style :openai-compatible-chat}))
                         [:extra-body :prompt_cache_key])]
        (expect (not= (run "system A") (run "system B")))))
  (it "S7: system-only conversation still gets a key (head half simply absent)"
      (let [[_ opts]
            (sut/apply-llm-opts [{:role "system" :content "stable system prompt"}]
                                {:api-style :openai-compatible-chat})

            key
            (get-in opts [:extra-body :prompt_cache_key])]

        (expect (string? key))
        (expect (clojure.string/starts-with? key "svar-auto-"))))
  (it "S7: explicit :cache-key wins over auto-gen"
      (let [[_ opts] (sut/apply-llm-opts
                       [{:role "system" :content "some system"} {:role "user" :content "hi"}]
                       {:api-style :openai-compatible-chat :cache-key "my-explicit-key"})]
        (expect (= "my-explicit-key" (get-in opts [:extra-body :prompt_cache_key])))))
  (it "S7: openai-style + NO system content → no auto-key (nothing stable to hash)"
      (let [[_ opts] (sut/apply-llm-opts [{:role "user" :content "hi"}]
                                         {:api-style :openai-compatible-chat})]
        (expect (nil? (get-in opts [:extra-body :prompt_cache_key])))))
  (it "S7: anthropic api-style does NOT auto-generate (field would be stripped anyway)"
      (let [[_ opts] (sut/apply-llm-opts [{:role "system" :content "some system"}
                                          {:role "user" :content "hi"}]
                                         {:api-style :anthropic})]
        (expect (nil? (get-in opts [:extra-body :prompt_cache_key]))))))

(defdescribe
  apply-llm-opts-anthropic-wire-test
  (it "S3 wire: anthropic body emits cache_control on auto-tagged system"
      (let [[msgs _]
            (sut/apply-llm-opts [{:role "system" :content "stable"} {:role "user" :content "hi"}]
                                {})

            body
            (#'sut/build-anthropic-request-body msgs "claude-haiku-4-5" {})]

        (expect (= [{:type "text" :text "stable" :cache_control {:type "ephemeral"}}]
                   (:system body)))))
  (it "S2 wire: anthropic accepts keyword :role :system thanks to normalisation"
      ;; Pre-fix this throws HTTP 400 on Anthropic. Post-fix the message
      ;; reaches `:system` and `:messages` is untouched.
      (let [[msgs _]
            (sut/apply-llm-opts [{:role :system :content "sys text"} {:role :user :content "hi"}]
                                {})

            body
            (#'sut/build-anthropic-request-body msgs "claude-haiku-4-5" {})]

        (expect (some? (:system body)))
        (expect (= [{:role "user" :content "hi"}] (:messages body)))))
  (it "S5 wire: anthropic body STRIPS :prompt_cache_key (OpenAI-only field)"
      ;; Caller passes :cache-key; apply-llm-opts lifts it into
      ;; :extra-body :prompt_cache_key; anthropic body builder MUST drop
      ;; it before send (Anthropic 400s on unknown wire fields).
      (let [[_ opts]
            (sut/apply-llm-opts [{:role "user" :content "hi"}] {:cache-key "k"})

            body
            (#'sut/build-anthropic-request-body
             [{:role "user" :content "hi"}]
             "claude-haiku-4-5"
             (:extra-body opts))]

        (expect (not (contains? body :prompt_cache_key))))))

(defdescribe anthropic-extra-body-sanitization-test
             ;; Regression, Vis session b30f87ac-f20e-4d7f-9fd2-416788d10527:
             ;; Codex Priority leaked into an Anthropic fallback request.
             (it "strips Codex Priority while preserving Anthropic service tiers"
                 (let [body #(#'sut/build-anthropic-request-body
                               [{:role "user" :content "hi"}]
                               "claude-haiku-4-5"
                               {:service_tier % :temperature 0.3})]
                   (expect (not (contains? (body "priority") :service_tier)))
                   (expect (= "auto" (:service_tier (body "auto"))))
                   (expect (= "standard_only" (:service_tier (body "standard_only"))))
                   (expect (= 0.3 (:temperature (body "priority"))))))
             (it "strips OpenAI Responses text verbosity from Anthropic requests"
                 (let [body (#'sut/build-anthropic-request-body
                             [{:role "user" :content "hi"}]
                             "claude-haiku-4-5"
                             {:text {:verbosity "high"}})]
                   (expect (not (contains? body :text))))))

(defdescribe
  apply-llm-opts-1h-beta-header-test
  (it "S6: :1h cache-ttl marker triggers extended-cache-ttl-2025-04-11 beta header"
      (let [msgs
            [{:role "system"
              :content [{:type "text" :text "x" :svar/cache true :svar/cache-ttl :1h}]}
             {:role "user" :content "hi"}]

            headers
            (#'sut/request-headers :anthropic "sk-x" :anthropic-coding-plan msgs nil nil)]

        (expect (= "extended-cache-ttl-2025-04-11" (get headers "anthropic-beta")))))
  (it "S6: :5min (default) cache-ttl does NOT trigger the 1h beta header"
      (let [msgs
            [{:role "system" :content [{:type "text" :text "x" :svar/cache true}]}
             {:role "user" :content "hi"}]

            headers
            (#'sut/request-headers :anthropic "sk-x" :anthropic-coding-plan msgs nil nil)]

        (expect (not= "extended-cache-ttl-2025-04-11" (get headers "anthropic-beta")))))
  (it
    "S6: 1h beta header coexists with the OAuth claude-code beta header"
    (let [msgs
          [{:role "system"
            :content [{:type "text" :text "x" :svar/cache true :svar/cache-ttl :1h}]}]

          ;; OAuth-style anthropic key prefix triggers the static beta
          ;; header in `make-llm-headers`.
          headers
          (#'sut/request-headers :anthropic "sk-ant-oat01-xxxx" :anthropic-coding-plan msgs nil nil)

          beta
          (get headers "anthropic-beta")]

      (expect (re-find #"extended-cache-ttl-2025-04-11" beta))
      (expect (re-find #"claude-code-20250219" beta)))))

;;; ── cache OUTCOME log ─────────────────────────────────────────────────

(defdescribe
  log-cache-outcome-test
  "`::cache-decision` says what svar asked for; `::cache-outcome` says what the
   provider delivered. Only the second one can catch a degraded server-side
   prefix cache."
  (it "reports the DELIVERED hit ratio and the re-prefilled remainder"
      (let [data (#'sut/log-cache-outcome!
                  {:api-style :openai-compatible-responses
                   :model "gpt-5.6-terra"
                   :provider-id :openai-codex
                   :cache-key "session-abc"
                   :api-usage {:input-tokens 100000
                               :input-tokens-details
                               {:regular 25000 :cache-write 0 :cache-read 75000}}
                   :duration-ms 1200})]
        (expect (= 0.75 (:cache-hit-ratio data)))
        (expect (= 25000 (:uncached-tokens data)))
        (expect (= 75000 (:cached-tokens data)))
        (expect (= "session-abc" (:cache-key data)))))
  (it "a COLD call is reported as 0.0 — not as missing data"
      (let [data (#'sut/log-cache-outcome!
                  {:api-usage {:input-tokens 4096
                               :input-tokens-details {:cache-read 0 :cache-write 0}}})]
        (expect (= 0.0 (:cache-hit-ratio data)))
        (expect (= 4096 (:uncached-tokens data)))))
  (it "no usage / zero input → nil (nothing to say)"
      (expect (nil? (#'sut/log-cache-outcome! {:api-usage nil})))
      (expect (nil? (#'sut/log-cache-outcome! {:api-usage {:input-tokens 0}})))))
