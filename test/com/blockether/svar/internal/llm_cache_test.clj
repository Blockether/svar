(ns com.blockether.svar.internal.llm-cache-test
  "Prompt caching + multi-block content + spec-prompt placement.

   These tests cover the wire-shape contract that `vis` (and any other
   downstream agent loop) relies on for Anthropic prompt caching:

   - `cached` marker survives `system`/`user`/`assistant`.
   - Anthropic body emits `cache_control: {type: \"ephemeral\"}` per
     marked block; system is array-shaped only when needed.
   - OpenAI body strips `:svar/*` markers; multimodal content untouched.
   - `extract-anthropic-response-data` surfaces
     `cache_creation_input_tokens` / `cache_read_input_tokens` on
     `:prompt_tokens_details` so downstream callers see real cache hits.

   Pure-data tests only \u2014 no LLM calls."
  (:require
   [lazytest.core :refer [defdescribe expect it]]
   [com.blockether.svar.internal.llm :as sut]))

;;; ── cached helper ─────────────────────────────────────────────────────

(defdescribe cached-helper-test
  (it "tags a text block with :svar/cache true (default 5min)"
    (expect (= {:type "text" :text "hello" :svar/cache true}
              (sut/cached "hello"))))

  (it "honors :ttl :1h"
    (expect (= {:type "text" :text "hello" :svar/cache true :svar/cache-ttl :1h}
              (sut/cached "hello" {:ttl :1h}))))

  (it "rejects unknown ttl values at body-build time"
    ;; cached itself is permissive (just stores the keyword); the throw
    ;; happens when build-anthropic-request-body translates it.
    (let [bad-msg (sut/system [(sut/cached "x" {:ttl :weird})])]
      (expect
        (try (#'sut/build-anthropic-request-body
              [bad-msg] "claude-haiku-4-5" nil)
             false
             (catch clojure.lang.ExceptionInfo e
               (= :svar.core/invalid-cache-ttl (:type (ex-data e)))))))))

;;; ── Anthropic body shape ──────────────────────────────────────────────

(defdescribe anthropic-system-string-shape-test
  (it "emits :system as STRING when no system block carries a cache marker"
    (let [msgs [(sut/system "core agent prompt")
                (sut/user "what is 2+2?")]
          body (#'sut/build-anthropic-request-body msgs "claude-haiku-4-5" nil)]
      (expect (= "core agent prompt" (:system body)))
      (expect (string? (:system body)))))

  (it "emits :system as ARRAY when any block is cached"
    (let [msgs [(sut/system [(sut/cached "stable agent rules")
                             "live env block"])
                (sut/user "what is 2+2?")]
          body (#'sut/build-anthropic-request-body msgs "claude-haiku-4-5" nil)
          sys  (:system body)]
      (expect (vector? sys))
      (expect (= 2 (count sys)))
      (expect (= {:type "text" :text "stable agent rules"
                  :cache_control {:type "ephemeral"}}
                (first sys)))
      ;; Second (uncached) block has no :cache_control and no :svar/* keys.
      (expect (= {:type "text" :text "live env block"} (second sys)))))

  (it "emits :cache_control with :ttl \"1h\" when :svar/cache-ttl :1h"
    (let [body (#'sut/build-anthropic-request-body
                [(sut/system [(sut/cached "x" {:ttl :1h})])]
                "claude-haiku-4-5" nil)]
      (expect (= {:type "ephemeral" :ttl "1h"}
                (:cache_control (first (:system body)))))))

  (it "merges multiple system messages preserving cache markers"
    (let [msgs [(sut/system "rules-A")
                (sut/system [(sut/cached "rules-B")])]
          body (#'sut/build-anthropic-request-body msgs "claude-haiku-4-5" nil)]
      (expect (vector? (:system body)))
      (expect (= 2 (count (:system body))))
      (expect (nil? (:cache_control (first (:system body)))))
      (expect (= {:type "ephemeral"} (:cache_control (second (:system body)))))))

  (it "translates cache markers on user content blocks too"
    (let [msgs [(sut/system "rules")
                {:role "user" :content [(sut/cached "long context")
                                        "current ask"]}]
          body (#'sut/build-anthropic-request-body msgs "claude-haiku-4-5" nil)
          user-msg (-> body :messages first)]
      (expect (vector? (:content user-msg)))
      (expect (= {:type "text" :text "long context"
                  :cache_control {:type "ephemeral"}}
                (first (:content user-msg))))
      (expect (= {:type "text" :text "current ask"}
                (second (:content user-msg))))))

  (it "leaves bare-text user messages as plain strings on the wire"
    (let [body (#'sut/build-anthropic-request-body
                [(sut/system "rules") (sut/user "hi")] "claude-haiku-4-5" nil)
          user-msg (-> body :messages first)]
      (expect (= "hi" (:content user-msg)))
      (expect (string? (:content user-msg))))))

;;; ── OpenAI body shape (cache markers stripped) ────────────────────────

(defdescribe openai-strips-svar-markers-test
  (it "strips :svar/cache from system content"
    (let [body (#'sut/build-request-body
                [(sut/system [(sut/cached "stable")
                              "live"])
                 (sut/user "ask")]
                "gpt-4o" nil)
          sys-msg (->> (:messages body) (filter #(= "system" (:role %))) first)]
      (expect (vector? (:content sys-msg)))
      (expect (every? #(nil? (:svar/cache %)) (:content sys-msg)))
      (expect (every? #(nil? (:cache_control %)) (:content sys-msg)))))

  (it "collapses single plain-text content back to string"
    (let [body (#'sut/build-request-body
                [(sut/system "rules") (sut/user "hi")] "gpt-4o" nil)
          msgs (:messages body)]
      (expect (= "rules" (:content (first msgs))))
      (expect (= "hi" (:content (second msgs))))))

  (it "strips :svar/cache from user content blocks"
    (let [body (#'sut/build-request-body
                [(sut/system "rules")
                 {:role "user" :content [(sut/cached "stable") "ask"]}]
                "gpt-4o" nil)
          user-msg (-> body :messages second)]
      (expect (vector? (:content user-msg)))
      (expect (every? #(nil? (:svar/cache %)) (:content user-msg))))))

;;; ── OpenAI usage normalization (cache token surface) ─────────────────

(defdescribe openai-usage-cache-tokens-test
  (it "preserves Chat Completions prompt_tokens_details.cached_tokens"
    (let [usage (#'sut/normalize-openai-usage
                 {:prompt_tokens 100
                  :completion_tokens 10
                  :total_tokens 110
                  :prompt_tokens_details {:cached_tokens 80}})]
      (expect (= 100 (:prompt_tokens usage)))
      (expect (= 10 (:completion_tokens usage)))
      (expect (= 80 (get-in usage [:prompt_tokens_details :cached_tokens])))))

  (it "maps Responses input_tokens_details.cached_tokens to prompt_tokens_details.cached_tokens"
    (let [usage (#'sut/normalize-openai-usage
                 {:input_tokens 100
                  :output_tokens 10
                  :total_tokens 110
                  :input_tokens_details {:cached_tokens 80}})]
      (expect (= 100 (:prompt_tokens usage)))
      (expect (= 10 (:completion_tokens usage)))
      (expect (= 80 (get-in usage [:prompt_tokens_details :cached_tokens]))))))

;;; ── Anthropic usage normalization (cache token surface) ───────────────

(defdescribe anthropic-usage-cache-tokens-test
  (it "surfaces cache_read_input_tokens as :prompt_tokens_details/:cached_tokens"
    (let [envelope {:parsed {:content [{:type "text" :text "hi"}]
                             :usage   {:input_tokens             100
                                       :output_tokens            10
                                       :cache_read_input_tokens  80}}}
          {:keys [api-usage]} (#'sut/extract-anthropic-response-data envelope)]
      (expect (= 100 (:prompt_tokens api-usage)))
      (expect (= 10 (:completion_tokens api-usage)))
      (expect (= 80 (get-in api-usage [:prompt_tokens_details :cached_tokens])))))

  (it "surfaces cache_creation_input_tokens as :cache_creation_tokens"
    (let [envelope {:parsed {:content [{:type "text" :text "hi"}]
                             :usage   {:input_tokens                100
                                       :output_tokens               10
                                       :cache_creation_input_tokens 90}}}
          {:keys [api-usage]} (#'sut/extract-anthropic-response-data envelope)]
      (expect (= 90 (get-in api-usage [:prompt_tokens_details :cache_creation_tokens])))))

  (it "omits :prompt_tokens_details when neither cache field is present"
    (let [envelope {:parsed {:content [{:type "text" :text "hi"}]
                             :usage   {:input_tokens 100 :output_tokens 10}}}
          {:keys [api-usage]} (#'sut/extract-anthropic-response-data envelope)]
      (expect (nil? (:prompt_tokens_details api-usage))))))

;;; ── Content normalization edge cases ──────────────────────────────────

(defdescribe normalize-content-test
  (it "wraps a bare string"
    (expect (= [{:type "text" :text "x"}]
              (#'sut/normalize-content "x"))))

  (it "passes through a single text block"
    (expect (= [{:type "text" :text "x"}]
              (#'sut/normalize-content {:type "text" :text "x"}))))

  (it "treats nil/empty as []"
    (expect (= [] (#'sut/normalize-content nil)))
    (expect (= [] (#'sut/normalize-content []))))

  (it "wraps strings inside a vector"
    (expect (= [{:type "text" :text "a"} {:type "text" :text "b"}]
              (#'sut/normalize-content ["a" "b"]))))

  (it "preserves :svar/cache markers through normalization"
    (let [in [(sut/cached "stable") "live"]
          out (#'sut/normalize-content in)]
      (expect (= 2 (count out)))
      (expect (true? (-> out first :svar/cache)))
      (expect (nil? (-> out second :svar/cache))))))
