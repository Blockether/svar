(ns com.blockether.svar.internal.llm-retry-visibility-test
  "The same-provider HTTP ladder in `llm/with-retry` is where a provider
   OVERLOAD is healed: Anthropic answers HTTP 529 `overloaded_error`, OpenAI
   answers `server_is_overloaded`, and both clear seconds later. Two things
   have to be true for that to be a non-event for the human:

     1. the ladder must be DEEP enough to outlast the overload window, and
     2. every wait must be ANNOUNCED, because it happens inside one `ask!`
        call — a silent ladder is a frozen UI with the only evidence in a log
        file."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.failure :as failure]
   [com.blockether.svar.internal.router :as router]
   [com.blockether.svar.internal.llm :as llm]))

(defn- overloaded!
  "The exception svar's stream layer throws for an Anthropic/OpenAI overload."
  []
  (ex-info "Provider stream failed (overloaded_error): Overloaded"
    {:type :svar.core/http-error :status 529 :stream? true}))

(def ^:private fast
  "Default ATTEMPT budget, negligible sleeps: the schedule under test is the
   attempt count, not the wall clock."
  {:initial-delay-ms 1 :max-delay-ms 1})

;; Regression, vis issue: a provider overload (HTTP 529 `overloaded_error`)
;; surfaced as a hard turn failure after ~7 s of healing, and the whole ladder
;; was invisible — nothing but `::http-retry` :warn lines in ~/.vis/vis.log.
(defdescribe overload-retry-test
  (describe "budget"
    (it "keeps retrying an overload past the old 5-attempt ceiling"
      (let [attempts (atom 0)
            result   (#'llm/with-retry
                      (fn []
                        (if (< (long (swap! attempts inc)) 6)
                          (throw (overloaded!))
                          :ok))
                      fast)]
        (expect (= :ok result))
        (expect (= 6 @attempts))))

    (it "still gives up, and rethrows the provider's own error"
      (let [attempts (atom 0)]
        (expect (re-find #"Overloaded"
                  (try (#'llm/with-retry (fn [] (swap! attempts inc) (throw (overloaded!))) fast)
                    (catch clojure.lang.ExceptionInfo e (ex-message e)))))
        ;; The shipped ladder is 7 attempts = 6 sleeps. It used to be 5 = 4
        ;; sleeps, ~7 s, shorter than an Anthropic/OpenAI overload window.
        (expect (= 7 @attempts)))))

  (describe "visibility"
    (it "announces every wait as a routing event the surfaces already paint"
      (let [seen     (atom [])
            attempts (atom 0)]
        (#'llm/with-retry
         (fn []
           (if (< (long (swap! attempts inc)) 3)
             (throw (overloaded!))
             :ok))
         (assoc fast :on-retry #(swap! seen conj %)
           :provider-id :anthropic-coding-plan
           :model "claude-opus-4"))
        (expect (= 2 (count @seen)))
        (expect (every? #(= :llm.routing/provider-retry (:event/type %)) @seen))
        (expect (= [1 2] (mapv :attempt @seen)))
        (expect (every? #(= 529 (:status %)) @seen))
        (expect (every? #(nat-int? (:delay-ms %)) @seen))
        (expect (every? #(= "anthropic-coding-plan" (:from-provider %)) @seen))
        (expect (every? #(= "claude-opus-4" (:from-model %)) @seen))
        (expect (every? #(re-find #"Overloaded" (:error %)) @seen))))

    (it "says nothing when the call succeeds"
      (let [seen (atom [])]
        (expect (= :ok (#'llm/with-retry (constantly :ok)
                                         (assoc fast :on-retry #(swap! seen conj %)))))
        (expect (empty? @seen))))))

;; Regression, vis gateway events 2026-08-07: a real OpenAI/Codex overload never
;; reaches `llm/with-retry` at all — it arrives MID-STREAM as `Provider stream
;; failed (server_is_overloaded)` / status 529 and is healed by the ROUTER's
;; same-provider loop, which owned a private `[2000 3000 6000]` ladder. The
;; streamed 529 (the common one) therefore got 11 s of healing while the
;; pre-stream 529 got 45 s.
(defdescribe streamed-overload-ladder-test
  (describe "one policy for both ladders"
    (it "router's same-provider schedule IS the same-provider retry ladder"
      ;; The literals are the shipped policy: 7 attempts = 6 sleeps, 1 s doubling
      ;; up to the 15 s ceiling, 45 s of healing (the mid-stream 529 used to get
      ;; [2000 3000 6000] = 11 s).
      (expect (= [1000 2000 4000 8000 15000 15000]
                (:same-provider-delays-ms router/DEFAULT_RATE_LIMIT_ROUTING))))

    (it "outlasts a provider overload window instead of 11 s"
      (let [ladder (:same-provider-delays-ms router/DEFAULT_RATE_LIMIT_ROUTING)
            total  (long (reduce + ladder))]
        (expect (= 6 (count ladder)))
        (expect (apply <= ladder))
        (expect (<= 40000 total))
        ;; and still inside the router's hard wall-clock cap.
        (expect (<= total (long (:fallback-after-ms router/DEFAULT_RATE_LIMIT_ROUTING))))))

    (it "classifies the streamed OpenAI overload as router-retryable"
      (let [e (ex-info "Provider stream failed (server_is_overloaded): Our servers are currently overloaded. Please try again later."
                {:type :svar.core/stream-failed :source :provider :stream? true
                 :status 529 :output-started? false
                 :provider-error-code "server_is_overloaded"})]
        (expect (= :gateway-unavailable (:category (failure/classify e))))
        (expect (failure/transient-error? e))))))
