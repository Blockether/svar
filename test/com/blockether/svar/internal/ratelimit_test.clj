(ns com.blockether.svar.internal.ratelimit-test
  "Tests for provider rate-limit header parsing → canonical :rate-limit."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.ratelimit :as sut]))

(def ^:private now 1700000000000)

(defdescribe ratelimit-parse-test
  "Parse provider rate-limit headers into the canonical shape."

  (describe "anthropic — absolute reset clocks"
    (it "parses the unified window (epoch seconds → reset-at ms)"
      (let [r (sut/parse :anthropic
                {"anthropic-ratelimit-unified-reset"     "1700003600"
                 "anthropic-ratelimit-unified-remaining" "42"
                 "anthropic-ratelimit-unified-status"    "allowed"}
                now)]
        (expect (= 1700003600000 (:reset-at r)))
        (expect (= 42 (:remaining r)))
        (expect (= 1700003600000 (get-in r [:windows :unified :reset-at])))
        (expect (= "allowed" (get-in r [:windows :unified :status])))))

    (it "parses RFC-3339 request/token reset timestamps"
      (let [r (sut/parse :anthropic
                {"anthropic-ratelimit-requests-reset"     "2023-11-14T22:13:20Z"
                 "anthropic-ratelimit-requests-remaining" "5"
                 "anthropic-ratelimit-requests-limit"     "50"}
                now)]
        (expect (= 1700000000000 (:reset-at r)))
        (expect (= 5 (:remaining r)))
        (expect (= 50 (:limit r)))))

    (it "picks the SOONEST reset across windows"
      (let [r (sut/parse :anthropic
                {"anthropic-ratelimit-requests-reset" "1700003600"
                 "anthropic-ratelimit-tokens-reset"   "1700001000"}
                now)]
        (expect (= 1700001000000 (:reset-at r))))))

  (describe "openai / codex — relative duration resets"
    (it "adds the Go-style duration to now"
      (let [r (sut/parse :openai-compatible-responses
                {"x-ratelimit-reset-requests"     "6m0s"
                 "x-ratelimit-remaining-requests" "9"
                 "x-ratelimit-limit-requests"     "10"
                 "x-ratelimit-reset-tokens"       "1.5s"}
                now)]
        (expect (= (+ now 360000) (get-in r [:windows :requests :reset-at])))
        (expect (= (+ now 1500) (get-in r [:windows :tokens :reset-at])))
        ;; soonest of the two
        (expect (= (+ now 1500) (:reset-at r)))
        (expect (= 9 (:remaining r)))
        (expect (= 10 (:limit r)))))

    (it "parses compound durations (1h2m3s, 100ms)"
      (let [r (sut/parse :openai-compatible-chat
                {"x-ratelimit-reset-requests" "1h2m3s"} now)]
        (expect (= (+ now 3723000) (get-in r [:windows :requests :reset-at]))))
      (let [r (sut/parse :openai-compatible-chat
                {"x-ratelimit-reset-tokens" "100ms"} now)]
        (expect (= (+ now 100) (get-in r [:windows :tokens :reset-at]))))))

  (describe "absent / malformed headers"
    (it "returns nil when no rate-limit headers present"
      (expect (nil? (sut/parse :anthropic {"content-type" "application/json"} now))))
    (it "returns nil for nil / non-map headers"
      (expect (nil? (sut/parse :anthropic nil now)))
      (expect (nil? (sut/parse :openai-compatible-chat "not-a-map" now))))
    (it "is case-insensitive on header names"
      (let [r (sut/parse :anthropic {"Anthropic-RateLimit-Unified-Reset" "1700003600"} now)]
        (expect (= 1700003600000 (:reset-at r)))))))
