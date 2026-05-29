(ns com.blockether.svar.internal.anthropic-count-tokens-test
  "Anthropic-native pre-flight token counting.

   When the resolved provider is the DIRECT Anthropic API
   (`:api-style :anthropic`), svar asks Anthropic's free
   `POST /v1/messages/count_tokens` endpoint for the EXACT input-token
   count instead of approximating with the offline tiktoken estimate —
   but only near the context limit, where precision changes the overflow
   verdict. Proxied Claude (`:openai-compatible-chat`) has no such
   endpoint and keeps the offline estimate.

   Responsibility split (this is the architecture these tests pin):

   - `router/check-context-limit` owns the *policy*: the
     `CONTEXT_REFINE_UTILIZATION` threshold and the decision to call the
     injected `:exact-count-fn` thunk only near the limit. Pure, no HTTP.
   - `llm/anthropic-count-tokens` owns the *transport*: the actual
     count_tokens POST. Stubbed here — no network / no API key needed.
   - `llm/anthropic-exact-count-fn` owns the *gating*: supply a thunk only
     for `:anthropic` api-style (proxied Claude has no such endpoint)."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [clojure.string :as str]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.router :as router]))

(def ^:private anthropic-count-tokens @#'llm/anthropic-count-tokens)
(def ^:private anthropic-exact-count-fn @#'llm/anthropic-exact-count-fn)

(def ^:private msgs [{:role "system" :content "You are a scientist."}
                     {:role "user" :content "Hello, Claude — count me."}])

;; =============================================================================
;; router/check-context-limit — token-count source priority + refine policy
;; =============================================================================

(defdescribe check-context-limit-input-override-test
  (describe ":input-tokens override (priority 1)"
    (it "uses the supplied count verbatim instead of the offline estimate"
      (let [r (router/check-context-limit "claude-opus-4-8" msgs {:input-tokens 123456})]
        (expect (= 123456 (:input-tokens r)))))

    (it "a huge override flips the overflow verdict"
      (let [r (router/check-context-limit "claude-opus-4-8" msgs
                {:input-tokens 5000000 :context-limits {:default 1000}})]
        (expect (false? (:ok? r)))
        (expect (= 5000000 (:input-tokens r)))))

    (it "an explicit :input-tokens wins over :exact-count-fn (no refine call)"
      (let [calls (atom 0)
            r (router/check-context-limit "claude-opus-4-8" msgs
                {:input-tokens 42
                 :context-limits {:default 10}      ;; near/over limit
                 :exact-count-fn (fn [] (swap! calls inc) 999)})]
        (expect (zero? @calls))
        (expect (= 42 (:input-tokens r)))))

    (it "falls back to the offline estimate when nothing supplied"
      (let [r (router/check-context-limit "claude-opus-4-8" msgs {})]
        (expect (pos? (:input-tokens r)))
        (expect (< (:input-tokens r) 100))))))

(defdescribe check-context-limit-refine-test
  (describe ":exact-count-fn refinement policy lives in the router"
    (it "calls the thunk and uses the exact count when utilization is high"
      ;; tiny budget ⇒ even a short prompt is over CONTEXT_REFINE_UTILIZATION
      (let [calls (atom 0)
            r (router/check-context-limit "claude-opus-4-8" msgs
                {:context-limits {:default 10}
                 :exact-count-fn (fn [] (swap! calls inc) 999)})]
        (expect (= 1 @calls))
        (expect (= 999 (:input-tokens r)))
        (expect (false? (:ok? r)))))

    (it "does NOT call the thunk far from the limit (offline decides)"
      (let [calls (atom 0)
            r (router/check-context-limit "claude-opus-4-8" msgs
                {:context-limits {:default 1000000}
                 :exact-count-fn (fn [] (swap! calls inc) 999)})]
        (expect (zero? @calls))
        (expect (not= 999 (:input-tokens r)))
        (expect (true? (:ok? r)))))

    (it "falls back to offline when the thunk returns nil"
      (let [calls (atom 0)
            r (router/check-context-limit "claude-opus-4-8" msgs
                {:context-limits {:default 10}
                 :exact-count-fn (fn [] (swap! calls inc) nil)})]
        (expect (= 1 @calls))
        (expect (not= 999 (:input-tokens r)))
        (expect (false? (:ok? r)))))

    (it "threshold constant is exposed for callers/policy"
      (expect (= 0.85 router/CONTEXT_REFINE_UTILIZATION)))))

;; =============================================================================
;; llm/anthropic-count-tokens — endpoint wiring (stubbed http-post!)
;; =============================================================================

(defdescribe anthropic-count-tokens-test
  (describe "happy path"
    (it "POSTs /messages/count_tokens with Anthropic headers and parses input_tokens"
      (let [captured (atom nil)
            result (with-redefs-fn
                     {#'llm/http-post!
                      (fn [url body headers _timeout-ms]
                        (reset! captured {:url url :body body :headers headers})
                        {:parsed {:input_tokens 4242} :status 200})}
                     (fn []
                       (anthropic-count-tokens msgs "claude-opus-4-8"
                         {:api-key "sk-ant-fake"
                          :base-url "https://api.anthropic.com/v1"})))]
        (expect (= 4242 result))
        (expect (str/ends-with? (:url @captured) "/messages/count_tokens"))
        (let [h (:headers @captured)]
          (expect (= "sk-ant-fake" (get h "x-api-key")))
          (expect (= "2023-06-01" (get h "anthropic-version"))))
        (let [b (:body @captured)]
          (expect (= "claude-opus-4-8" (:model b)))
          (expect (vector? (:messages b)))
          ;; count_tokens must NOT carry generation params
          (expect (not (contains? b :max_tokens)))
          (expect (not (contains? b :stream))))))

    (it "collapses a trailing slash in base-url (no double slash)"
      (let [captured (atom nil)]
        (with-redefs-fn
          {#'llm/http-post!
           (fn [url _body _headers _t] (reset! captured url)
             {:parsed {:input_tokens 1} :status 200})}
          (fn []
            (anthropic-count-tokens msgs "claude-opus-4-8"
              {:api-key "k" :base-url "https://api.anthropic.com/v1/"})))
        (expect (= "https://api.anthropic.com/v1/messages/count_tokens" @captured)))))

  (describe "failure modes return nil (never throw)"
    (it "non-200 → nil"
      (expect (nil? (with-redefs-fn
                      {#'llm/http-post! (fn [_ _ _ _] {:parsed {:error "overloaded"} :status 529})}
                      (fn [] (anthropic-count-tokens msgs "claude-opus-4-8"
                               {:api-key "k" :base-url "https://api.anthropic.com/v1"}))))))

    (it "missing input_tokens in 200 body → nil"
      (expect (nil? (with-redefs-fn
                      {#'llm/http-post! (fn [_ _ _ _] {:parsed {} :status 200})}
                      (fn [] (anthropic-count-tokens msgs "claude-opus-4-8"
                               {:api-key "k" :base-url "https://api.anthropic.com/v1"}))))))

    (it "transport exception → nil (swallowed; real call must not break)"
      (expect (nil? (with-redefs-fn
                      {#'llm/http-post! (fn [_ _ _ _] (throw (ex-info "boom" {})))}
                      (fn [] (anthropic-count-tokens msgs "claude-opus-4-8"
                               {:api-key "k" :base-url "https://api.anthropic.com/v1"}))))))))

;; =============================================================================
;; llm/anthropic-exact-count-fn — api-style + opt-out gating
;; =============================================================================

(defdescribe anthropic-exact-count-fn-gating-test
  (describe "supplies a thunk only for direct Anthropic"
    (it ":anthropic → returns a working thunk"
      (let [f (anthropic-exact-count-fn msgs "claude-opus-4-8"
                {:api-style :anthropic :api-key "k"
                 :base-url "https://api.anthropic.com/v1"})]
        (expect (fn? f))
        (expect (= 7 (with-redefs-fn
                       {#'llm/http-post! (fn [_ _ _ _] {:parsed {:input_tokens 7} :status 200})}
                       (fn [] (f)))))))

    (it ":openai-compatible-chat → nil (proxied Claude has no count_tokens)"
      (expect (nil? (anthropic-exact-count-fn msgs "claude-opus-4.8"
                      {:api-style :openai-compatible-chat :api-key "k"
                       :base-url "https://openrouter.ai/api/v1"}))))))
