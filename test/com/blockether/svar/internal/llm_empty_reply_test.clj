(ns com.blockether.svar.internal.llm-empty-reply-test
  "Empty-reply resend ladder: `:svar.llm/empty-content` -> bounded re-sends of
   the IDENTICAL request to the SAME model with exponential backoff; every
   other failure propagates untouched.

   Covers the full contract:
     - backoff schedule + heal/exhaust/foreign-error paths (ladder unit)
     - burned-attempt usage accumulation (billing honesty)
     - `:on-resend` observability callback (fired per re-send, crash-safe)
     - resend eligibility (`:empty-reply-resend-eligible? false` = no burn)
     - interrupt during backoff still annotates `:empty-reply-resends`
     - `ask!` path: finish-reason classification (token cap vs clean stop vs
       transient stall) + ladder heal + honest cost over burned sends
     - `ask-code!` path: heal + resend keys + cost includes burned sends +
       `:on-empty-reply-resend` fires"
  (:require [lazytest.core :refer [defdescribe describe expect it]]
            [com.blockether.svar.core :as svar]
            [com.blockether.svar.internal.llm :as sut]
            [com.blockether.svar.internal.router :as router]))

(def ^:private resend! @#'sut/call-with-empty-reply-resend)
(def ^:private delay-ms @#'sut/empty-reply-resend-delay-ms)

(defn- empty-reply-ex
  ([] (empty-reply-ex {}))
  ([extra]
   (ex-info "The model produced neither text nor a tool call."
            (merge {:type :svar.llm/empty-content} extra))))

;; =============================================================================
;; Helpers — mock chat-completion (passes :stream-finalization through so the
;; finish-reason classifier sees exactly what a real stream would carry)
;; =============================================================================

(defn- mock-chat
  "with-redefs target for `llm/chat-completion`: emits `responses` in order
   (last one repeats), records each call into `calls-atom`."
  [responses calls-atom]
  (let [idx
        (atom 0)

        rs
        (vec responses)]

    (fn [messages model _api-key url retry-opts]
      (let [n
            (long @idx)

            response
            (or (get rs n) (last rs))]

        (swap! calls-atom conj
          {:messages messages :model model :url url :retry-opts retry-opts :attempt n})
        (swap! idx inc)
        response))))

(defn- test-router
  []
  (svar/make-router [{:id :test
                      :api-key "sk-test"
                      :base-url "https://example.invalid/v1"
                      :api-style :openai-compatible-chat
                      :models [{:name "test-model"}]}]))

;; Deterministic pricing for cost assertions (USD per 1M tokens). Routed calls
;; resolve pricing through `router/provider-model-pricing`, which consults the
;; live models.dev catalog — pin it so burned-send cost math is exact.
(def ^:private test-pricing {:input 10.0 :output 20.0})

(defn- fixed-pricing [_ _] test-pricing)

(def ^:private answer-spec
  (svar/spec (svar/field svar/NAME
                         :answer
                         svar/TYPE
                         svar/TYPE_STRING
                         svar/CARDINALITY
                         svar/CARDINALITY_ONE
                         svar/DESCRIPTION
                         "The single-word answer")))

(def ^:private usage-100k-in {:input-tokens 100000 :output-tokens 0 :total-tokens 100000})

(def ^:private usage-good {:input-tokens 100000 :output-tokens 1000 :total-tokens 101000})

(defn- approx= [a b] (< (Math/abs (- (double a) (double b))) 1e-6))

(defdescribe
  empty-reply-resend-test
  (describe "backoff schedule"
            (it "doubles from 2s: 2s / 4s / 8s"
                (expect (= [2000 4000 8000] (mapv delay-ms [1 2 3])))))
  (describe
    "resend ladder"
    (it "heals in place: 3 empties then a real reply — 4 identical sends, no error"
        ;; delay 0 → test runs instantly; ladder itself is what's under test
        (with-redefs [sut/EMPTY_REPLY_RESEND_BASE_DELAY_MS 0]
          (let [calls (atom 0)
                result (resend! {:model "m" :provider-id :p}
                                (fn []
                                  (if (<= (swap! calls inc) 3)
                                    (throw (empty-reply-ex))
                                    {:content "ok" :tool-calls []})))]

            (expect (= 4 @calls))
            (expect (= "ok" (:content result))))))
    (it "exhausts after 3 re-sends: typed error propagates with the re-send count"
        (with-redefs [sut/EMPTY_REPLY_RESEND_BASE_DELAY_MS 0]
          (let [calls (atom 0)
                thrown (try (resend! {:model "m" :provider-id :p}
                                     (fn []
                                       (swap! calls inc)
                                       (throw (empty-reply-ex))))
                            nil
                            (catch clojure.lang.ExceptionInfo e e))]

            (expect (some? thrown))
            (expect (= 4 @calls))
            (expect (= :svar.llm/empty-content (:type (ex-data thrown))))
            (expect (= 3 (:empty-reply-resends (ex-data thrown)))))))
    (it "does NOT re-send other errors — transport failure propagates on the 1st send"
        (let [calls
              (atom 0)

              thrown
              (try (resend! {:model "m" :provider-id :p}
                            (fn []
                              (swap! calls inc)
                              (throw (ex-info "boom" {:type :svar.core/http-error :status 500}))))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]

          (expect (= 1 @calls))
          (expect (= :svar.core/http-error (:type (ex-data thrown))))
          ;; foreign errors keep their ex-data verbatim — no resend bookkeeping
          (expect (nil? (:empty-reply-resends (ex-data thrown))))))
    (it "does NOT re-send a token-cap failure — more sends cannot fix max_tokens"
        (let [calls
              (atom 0)

              thrown
              (try (resend! {:model "m" :provider-id :p}
                            (fn []
                              (swap! calls inc)
                              (throw (ex-info "capped" {:type :svar.llm/max-tokens-exceeded}))))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]

          (expect (= 1 @calls))
          (expect (= :svar.llm/max-tokens-exceeded (:type (ex-data thrown))))))
    (it "honors caller cancellation — no re-send once *cancel-fn* fires"
        (with-redefs [sut/EMPTY_REPLY_RESEND_BASE_DELAY_MS 0]
          (binding [sut/*cancel-fn* (constantly true)]
            (let [calls (atom 0)
                  thrown (try (resend! {:model "m" :provider-id :p}
                                       (fn []
                                         (swap! calls inc)
                                         (throw (empty-reply-ex))))
                              nil
                              (catch clojure.lang.ExceptionInfo e e))]

              (expect (= 1 @calls))
              (expect (= :svar.llm/empty-content (:type (ex-data thrown))))
              (expect (= 0 (:empty-reply-resends (ex-data thrown)))))))))
  (describe
    "burned-attempt usage accounting"
    (it "a healed call surfaces the resend count + summed usage of DISCARDED attempts"
        (with-redefs [sut/EMPTY_REPLY_RESEND_BASE_DELAY_MS 0]
          (let [calls (atom 0)
                result (resend! {:model "m" :provider-id :p}
                                (fn []
                                  (if (<= (swap! calls inc) 2)
                                    (throw (empty-reply-ex {:api-usage {:input-tokens 70
                                                                        :output-tokens 2}}))
                                    {:content "ok"
                                     :api-usage {:input-tokens 70 :output-tokens 9}})))]

            (expect (= 2 (:empty-reply-resends result)))
            ;; 2 discarded attempts x {:in 70 :out 2} — the SUCCESSFUL attempt's
            ;; usage stays where it always was, in :api-usage.
            (expect (= {:input-tokens 140 :output-tokens 4} (:empty-reply-resend-usage result)))
            (expect (= {:input-tokens 70 :output-tokens 9} (:api-usage result))))))
    (it "a clean first-try success carries NO resend keys (no noise on the happy path)"
        (let [result (resend! {:model "m" :provider-id :p}
                              (fn []
                                {:content "ok" :api-usage {:input-tokens 70 :output-tokens 9}}))]
          (expect (not (contains? result :empty-reply-resends)))
          (expect (not (contains? result :empty-reply-resend-usage)))))
    (it "an exhausted call carries the summed usage of the discarded attempts in ex-data"
        (with-redefs [sut/EMPTY_REPLY_RESEND_BASE_DELAY_MS 0]
          (let [thrown (try (resend! {:model "m" :provider-id :p}
                                     (fn []
                                       (throw (empty-reply-ex {:api-usage {:input-tokens 70
                                                                           :output-tokens 2}}))))
                            nil
                            (catch clojure.lang.ExceptionInfo e e))
                data (ex-data thrown)]

            (expect (= 3 (:empty-reply-resends data)))
            ;; 3 discarded attempts; the terminal attempt's own usage rides in
            ;; the envelope's :api-usage as before.
            (expect (= {:input-tokens 210 :output-tokens 6} (:empty-reply-resend-usage data)))
            (expect (= {:input-tokens 70 :output-tokens 2} (:api-usage data)))))))
  (describe "resend observability (:on-resend)"
            (it "fires once per re-send with attempt / max / delay / model"
                (with-redefs [sut/EMPTY_REPLY_RESEND_BASE_DELAY_MS 0]
                  (let [calls (atom 0)
                        events (atom [])
                        result (resend! {:model "m"
                                         :provider-id :p
                                         :on-resend (fn [ev]
                                                      (swap! events conj ev))}
                                        (fn []
                                          (if (<= (swap! calls inc) 2)
                                            (throw (empty-reply-ex))
                                            {:content "ok"})))]

                    (expect (= "ok" (:content result)))
                    (expect (= [1 2] (mapv :attempt @events)))
                    (expect (every? #(= "m" (:model %)) @events))
                    (expect (every? #(= :p (:provider-id %)) @events))
                    (expect (every? #(number? (:delay-ms %)) @events))
                    (expect (every? #(= 3 (:max-resends %)) @events)))))
            (it "a crashing callback never breaks the ladder — the call still heals"
                (with-redefs [sut/EMPTY_REPLY_RESEND_BASE_DELAY_MS 0]
                  (let [calls (atom 0)
                        result (resend! {:model "m"
                                         :provider-id :p
                                         :on-resend (fn [_]
                                                      (throw (RuntimeException. "observer boom")))}
                                        (fn []
                                          (if (<= (swap! calls inc) 1)
                                            (throw (empty-reply-ex))
                                            {:content "ok"})))]

                    (expect (= "ok" (:content result)))))))
  (describe "resend eligibility"
            (it "an empty reply marked :empty-reply-resend-eligible? false is NOT re-sent"
                (with-redefs [sut/EMPTY_REPLY_RESEND_BASE_DELAY_MS 0]
                  (let [calls (atom 0)
                        thrown (try (resend! {:model "m" :provider-id :p}
                                             (fn []
                                               (swap! calls inc)
                                               (throw (empty-reply-ex {:empty-reply-resend-eligible?
                                                                       false}))))
                                    nil
                                    (catch clojure.lang.ExceptionInfo e e))]

                    (expect (= 1 @calls))
                    (expect (= :svar.llm/empty-content (:type (ex-data thrown))))
                    (expect (= 0 (:empty-reply-resends (ex-data thrown))))))))
  (describe "interrupt during backoff"
            (it
              "annotates the pending empty-reply error with :empty-reply-resends before rethrowing"
              ;; REAL base delay so the worker is asleep when we interrupt it.
              (let [thrown-p
                    (promise)

                    worker
                    (Thread. (fn []
                               (try (resend! {:model "m" :provider-id :p}
                                             (fn []
                                               (throw (empty-reply-ex))))
                                    (deliver thrown-p ::no-throw)
                                    (catch clojure.lang.ExceptionInfo e (deliver thrown-p e))
                                    (catch Throwable t (deliver thrown-p t)))))]

                (.start worker)
                (Thread/sleep 200)
                (.interrupt worker)
                (let [thrown (deref thrown-p 5000 ::timeout)]
                  (expect (instance? clojure.lang.ExceptionInfo thrown))
                  (expect (= :svar.llm/empty-content (:type (ex-data thrown))))
                  ;; interrupted before the 1st re-send completed → 0 re-sends burned
                  (expect (= 0 (:empty-reply-resends (ex-data thrown)))))))))

;; =============================================================================
;; ask! (structured output) — finish-reason classification + ladder
;; =============================================================================

(defdescribe
  ask-empty-reply-classification-test
  (describe "token-cap blank reply"
            (it "classifies as :svar.llm/max-tokens-exceeded and is NOT re-sent"
                (let [calls (atom [])]
                  (with-redefs [sut/chat-completion (mock-chat [{:content ""
                                                                 :reasoning "thinking..."
                                                                 :api-usage usage-100k-in
                                                                 :http-response {:status 200}
                                                                 :stream-finalization
                                                                 {:finish-reason "max_tokens"}}]
                                                               calls)]
                    (let [thrown (try (svar/ask! (test-router)
                                                 {:spec answer-spec
                                                  :messages [(svar/user
                                                               "Reply with the word ok.")]})
                                      ::no-throw
                                      (catch clojure.lang.ExceptionInfo e e))]
                      (expect (not= ::no-throw thrown))
                      (expect (= :svar.llm/max-tokens-exceeded (:type (ex-data thrown))))
                      ;; token cap is not a stall — exactly one HTTP call
                      (expect (= 1 (count @calls))))))))
  (describe
    "clean-stop blank reply"
    (it
      "still throws :svar.llm/empty-content (structured output NEEDS content) but burns no re-sends"
      (with-redefs [sut/EMPTY_REPLY_RESEND_BASE_DELAY_MS 0]
        (let [calls (atom [])]
          (with-redefs [sut/chat-completion (mock-chat [{:content ""
                                                         :reasoning "thinking..."
                                                         :api-usage usage-100k-in
                                                         :http-response {:status 200}
                                                         :stream-finalization {:finish-reason
                                                                               "stop"}}]
                                                       calls)]
            (let [thrown (try (svar/ask! (test-router)
                                         {:spec answer-spec
                                          :messages [(svar/user "Reply with the word ok.")]})
                              ::no-throw
                              (catch clojure.lang.ExceptionInfo e e))]
              (expect (not= ::no-throw thrown))
              (expect (= :svar.llm/empty-content (:type (ex-data thrown))))
              ;; a DELIBERATE stop is a prompt-quality problem, not a transport
              ;; stall — the ladder must not burn identical re-sends on it
              (expect (= 1 (count @calls)))
              (expect (= 0 (:empty-reply-resends (ex-data thrown))))))))))
  (describe
    "transient blank reply (unknown/truncated finish reason)"
    (it
      "heals via the same-model ladder; result carries resend keys and cost covers burned sends"
      (with-redefs [sut/EMPTY_REPLY_RESEND_BASE_DELAY_MS
                    0

                    router/provider-model-pricing
                    fixed-pricing]

        (let [calls (atom [])]
          (with-redefs [sut/chat-completion (mock-chat [{:content ""
                                                         :reasoning nil
                                                         :api-usage usage-100k-in
                                                         :http-response {:status 200}
                                                         :stream-finalization {:finish-reason nil}}
                                                        {:content ""
                                                         :reasoning nil
                                                         :api-usage usage-100k-in
                                                         :http-response {:status 200}
                                                         :stream-finalization {:finish-reason nil}}
                                                        {:content "{\"answer\":\"ok\"}"
                                                         :api-usage usage-good
                                                         :http-response {:status 200}
                                                         :stream-finalization {:finish-reason
                                                                               "stop"}}]
                                                       calls)]
            (let [result (svar/ask! (test-router)
                                    {:spec answer-spec
                                     :messages [(svar/user "Reply with the word ok.")]})]
              (expect (= "ok" (get-in result [:result :answer])))
              ;; 1 original + 2 re-sends, all identical, same model
              (expect (= 3 (count @calls)))
              (expect (= ["test-model" "test-model" "test-model"] (mapv :model @calls)))
              (expect (apply = (mapv :messages @calls)))
              (expect (= 2 (:empty-reply-resends result)))
              (expect (= {:input-tokens 200000 :output-tokens 0 :total-tokens 200000}
                         (:empty-reply-resend-usage result)))
              ;; cost is billed over ALL sends: (300k in * $10/M) + (1k out * $20/M)
              (expect (approx= 3.02 (get-in result [:cost :total-cost]))))))))))

;; =============================================================================
;; ask-code! (tool calling) — heal + resend keys + honest cost + callback
;; =============================================================================

(defdescribe
  ask-code-empty-reply-accounting-test
  (describe
    "healed tool-calling call"
    (it
      "surfaces resend keys, bills burned sends into :cost, fires :on-empty-reply-resend"
      (with-redefs [sut/EMPTY_REPLY_RESEND_BASE_DELAY_MS
                    0

                    router/provider-model-pricing
                    fixed-pricing]

        (let [calls
              (atom [])

              events
              (atom [])]

          (with-redefs [sut/chat-completion (mock-chat [{:content ""
                                                         :tool-calls []
                                                         :api-usage usage-100k-in
                                                         :http-response {:status 200}
                                                         :stream-finalization {:finish-reason nil}}
                                                        {:content "done"
                                                         :tool-calls []
                                                         :api-usage usage-good
                                                         :http-response {:status 200}
                                                         :stream-finalization {:finish-reason
                                                                               "stop"}}]
                                                       calls)]
            (let [result (svar/ask-code! (test-router)
                                         {:messages [(svar/user "Say done.")]
                                          :on-empty-reply-resend (fn [ev]
                                                                   (swap! events conj ev))})]
              (expect (= "done" (:content result)))
              (expect (= :end (:stop-reason result)))
              (expect (= 2 (count @calls)))
              (expect (= 1 (:empty-reply-resends result)))
              (expect (= {:input-tokens 100000 :output-tokens 0 :total-tokens 100000}
                         (:empty-reply-resend-usage result)))
              ;; :api-usage stays LAST-attempt (context-accurate)…
              (expect (= usage-good (:api-usage result)))
              ;; …while :cost is billed over ALL sends:
              ;; (200k in * $10/M) + (1k out * $20/M) = 2.02
              (expect (approx= 2.02 (get-in result [:cost :total-cost])))
              ;; the observability hook fired for the one re-send
              (expect (= [1] (mapv :attempt @events)))
              (expect (every? #(number? (:delay-ms %)) @events)))))))
    (it "a clean call carries no resend keys and bills only itself"
        (let [calls (atom [])]
          (with-redefs [router/provider-model-pricing fixed-pricing
                        sut/chat-completion (mock-chat [{:content "done"
                                                         :tool-calls []
                                                         :api-usage usage-good
                                                         :http-response {:status 200}
                                                         :stream-finalization {:finish-reason
                                                                               "stop"}}]
                                                       calls)]

            (let [result (svar/ask-code! (test-router) {:messages [(svar/user "Say done.")]})]
              (expect (= 1 (count @calls)))
              (expect (not (contains? result :empty-reply-resends)))
              (expect (not (contains? result :empty-reply-resend-usage)))
              ;; (100k in * $10/M) + (1k out * $20/M) = 1.02
              (expect (approx= 1.02 (get-in result [:cost :total-cost])))))))))

;; =============================================================================
;; Anthropic refusal (stop_reason "refusal") — classify, DON'T resend, surface
;; stop_details. A refused Fable/Opus 5 request is a documented, non-transient
;; outcome: replaying it to the SAME model earns the same refusal, so it must
;; NOT enter the empty-reply resend ladder. See vis session
;; 1d39a651-2e61-41bb-ac46-9194761022f0 (a bare refusal misread as an empty
;; stall + blind-retried 3x).
;; =============================================================================

(def ^:private anomaly-type @#'sut/empty-reply-anomaly-type)

(defdescribe
  refusal-classification-test
  (describe
    "empty-reply-anomaly-type finish-reason mapping"
    (it "maps refusal -> :svar.llm/refusal" (expect (= :svar.llm/refusal (anomaly-type "refusal"))))
    (it "maps model_context_window_exceeded -> :svar.llm/max-tokens-exceeded"
        (expect (= :svar.llm/max-tokens-exceeded (anomaly-type "model_context_window_exceeded"))))
    (it "maps pause_turn -> nil (legit stop, never a blind resend)"
        (expect (nil? (anomaly-type "pause_turn"))))
    (it "still maps an unknown/nil finish reason -> :svar.llm/empty-content"
        (expect (= :svar.llm/empty-content (anomaly-type nil)))
        (expect (= :svar.llm/empty-content (anomaly-type "weird_new_reason")))))
  (describe
    "ask-code! blank reply with stop_reason refusal"
    (it "throws :svar.llm/refusal, carries stop_details, and is NOT re-sent"
        (with-redefs [sut/EMPTY_REPLY_RESEND_BASE_DELAY_MS 0]
          (let [calls (atom [])]
            (with-redefs [sut/chat-completion
                          (mock-chat [{:content ""
                                       :tool-calls []
                                       :api-usage usage-100k-in
                                       :http-response {:status 200}
                                       :stream-finalization
                                       {:finish-reason "refusal"
                                        :stop-details {"type" "refusal"
                                                       "category" "cyber"
                                                       "explanation"
                                                       "Declined: could enable cyber harm."}}}]
                                     calls)]
              (let [thrown (try (svar/ask-code! (test-router)
                                                {:messages [(svar/user "Do the cyber thing.")]})
                                ::no-throw
                                (catch clojure.lang.ExceptionInfo e e))]
                (expect (not= ::no-throw thrown))
                (expect (= :svar.llm/refusal (:type (ex-data thrown))))
                ;; the classifier's category + explanation rode into ex-data
                (expect (= "cyber" (get (:stop-details (ex-data thrown)) "category")))
                ;; the human message displays the decline, not "stall/retry"
                (expect (re-find #"(?i)declined" (ex-message thrown)))
                ;; a refusal is deterministic — exactly ONE call, no resend ladder
                (expect (= 1 (count @calls))))))))))

;; =============================================================================
;; Automatic refusal fallback — a refused primary model client-side-switches to
;; the caller's `:refusal-fallbacks` model. Most of the mechanism lives in svar;
;; the caller (Vis) only names the fallback (e.g. claude-opus-4-8).
;; =============================================================================

(defn- two-model-router
  []
  (svar/make-router [{:id :test
                      :api-key "sk-test"
                      :base-url "https://example.invalid/v1"
                      :api-style :openai-compatible-chat
                      :models [{:name "primary-model"} {:name "fallback-model"}]}]))

(defn- model-aware-chat
  "chat-completion stub that answers by MODEL: `refuse-model` returns a
   stop_reason refusal, everything else returns `answer`. Records model order."
  [refuse-model answer calls-atom]
  (fn [_messages model _api-key _url _retry-opts]
    (swap! calls-atom conj model)
    (if (= model refuse-model)
      {:content ""
       :tool-calls []
       :api-usage usage-100k-in
       :http-response {:status 200}
       :stream-finalization {:finish-reason "refusal"
                             :stop-details {"type" "refusal"
                                            "category" "cyber"
                                            "explanation" "Declined: could enable cyber harm."}}}
      answer)))

(defdescribe
  refusal-fallback-test
  (describe
    "ask-code! with :refusal-fallbacks"
    (it "switches to the fallback model on refusal and annotates :refusal-recovered"
        (with-redefs [router/provider-model-pricing fixed-pricing]
          (let [calls (atom [])
                events (atom [])]

            (with-redefs [sut/chat-completion (model-aware-chat "primary-model"
                                                                {:content "done"
                                                                 :tool-calls []
                                                                 :api-usage usage-good
                                                                 :http-response {:status 200}
                                                                 :stream-finalization
                                                                 {:finish-reason "stop"}}
                                                                calls)]
              (let [result (svar/ask-code! (two-model-router)
                                           {:model "primary-model"
                                            :refusal-fallbacks ["fallback-model"]
                                            :on-refusal-fallback (fn [ev]
                                                                   (swap! events conj ev))
                                            :messages [(svar/user "Do the thing.")]})]
                (expect (= "done" (:content result)))
                (expect (= :end (:stop-reason result)))
                ;; primary refused, fallback answered — exactly that order
                (expect (= ["primary-model" "fallback-model"] @calls))
                ;; the result names who actually answered
                (expect (= "primary-model" (get-in result [:refusal-recovered :from])))
                (expect (= "fallback-model" (get-in result [:refusal-recovered :to])))
                ;; the observability hook fired once with the switch detail
                (expect (= 1 (count @events)))
                (expect (= "primary-model" (:from-model (first @events))))
                (expect (= "fallback-model" (:to-model (first @events))))
                (expect (= "cyber" (:category (first @events)))))))))
    (it "with NO fallback list, a refusal propagates untouched (one call)"
        (with-redefs [router/provider-model-pricing fixed-pricing]
          (let [calls (atom [])]
            (with-redefs [sut/chat-completion (model-aware-chat "primary-model" nil calls)]
              (let [thrown (try (svar/ask-code! (two-model-router)
                                                {:model "primary-model"
                                                 :messages [(svar/user "Do the thing.")]})
                                ::no-throw
                                (catch clojure.lang.ExceptionInfo e e))]
                (expect (not= ::no-throw thrown))
                (expect (= :svar.llm/refusal (:type (ex-data thrown))))
                (expect (= ["primary-model"] @calls)))))))
    (it "when EVERY fallback also refuses, the last refusal propagates enriched"
        (with-redefs [router/provider-model-pricing fixed-pricing]
          (let [calls (atom [])]
            ;; both models refuse: stub refuses regardless of model name
            (with-redefs [sut/chat-completion (fn [_m model _k _u _r]
                                                (swap! calls conj model)
                                                {:content ""
                                                 :tool-calls []
                                                 :api-usage usage-100k-in
                                                 :http-response {:status 200}
                                                 :stream-finalization {:finish-reason "refusal"
                                                                       :stop-details {"category"
                                                                                      "cyber"}}})]
              (let [thrown (try (svar/ask-code! (two-model-router)
                                                {:model "primary-model"
                                                 :refusal-fallbacks ["fallback-model"]
                                                 :messages [(svar/user "Do the thing.")]})
                                ::no-throw
                                (catch clojure.lang.ExceptionInfo e e))]
                (expect (not= ::no-throw thrown))
                (expect (= :svar.llm/refusal (:type (ex-data thrown))))
                ;; tried primary then the one fallback, then gave up
                (expect (= ["primary-model" "fallback-model"] @calls))
                (expect (seq (:refusal-fallbacks-tried (ex-data thrown)))))))))))
