(ns com.blockether.svar.internal.tokens-test
  "Tests for token counting utilities.
   
   Ported from unbound.backend.shared.llm.internal.tokens-test
   
   NOTE: These tests require Java to be compiled first.
   Run `make compile-java` in the svar package before running tests."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.tokens :as sut]))

;; =============================================================================
;; Context Limits Tests
;; =============================================================================

(defdescribe context-limit-test
  "Tests for model context limits"

  (describe "known models"
            (it "returns 128000 for gpt-4o"
                (expect (= 128000 (sut/context-limit "gpt-4o"))))

            (it "returns 128000 for gpt-4-turbo"
                (expect (= 128000 (sut/context-limit "gpt-4-turbo"))))

            (it "returns 8192 for gpt-4"
                (expect (= 8192 (sut/context-limit "gpt-4"))))

            (it "returns 200000 for claude-3-5-sonnet"
                (expect (= 200000 (sut/context-limit "claude-3-5-sonnet"))))

            (it "returns 2000000 for gemini-1.5-pro"
                (expect (= 2000000 (sut/context-limit "gemini-1.5-pro")))))

  (describe "unknown models"
            (it "returns default for unknown model"
                (expect (= 8192 (sut/context-limit "unknown-model-xyz"))))))

;; =============================================================================
;; Max Input Tokens Tests
;; =============================================================================

(defdescribe max-input-tokens-test
  "Tests for max input token calculation"

  (describe "default output reserve"
            (it "uses default reserve of 0 (trust API)"
                (let [result (sut/max-input-tokens "gpt-4o")]
        ;; 128000 - 0 (default reserve) = 128000
                  (expect (= 128000 result)))))

  (describe "custom output reserve"
            (it "allows custom output reserve"
                (let [result (sut/max-input-tokens "gpt-4o" {:output-reserve 8192})]
        ;; 128000 - 8192 = 119808
                  (expect (= 119808 result)))))

  (describe "trim ratio"
            (it "uses trim ratio when specified"
                (let [result (sut/max-input-tokens "gpt-4o" {:trim-ratio 0.75})]
        ;; 128000 * 0.75 = 96000
                  (expect (= 96000 result))))))

;; =============================================================================
;; Token Counting Tests
;; =============================================================================

(defdescribe count-tokens-test
  "Tests for count-tokens function"

  (it "counts tokens for simple text"
      (let [tokens (sut/count-tokens "gpt-4o" "Hello, world!")]
        (expect (pos? tokens))
        (expect (< tokens 10))))

  (it "returns more tokens for longer text"
      (let [short-tokens (sut/count-tokens "gpt-4o" "Hi")
            long-tokens (sut/count-tokens "gpt-4o" "Hello, this is a much longer piece of text that should have more tokens")]
        (expect (< short-tokens long-tokens))))

  (it "handles empty string"
      (let [tokens (sut/count-tokens "gpt-4o" "")]
        (expect (zero? tokens))))

  (it "falls back to cl100k_base for unknown models"
    ;; Should not throw, should use fallback encoding
      (let [tokens (sut/count-tokens "unknown-model-xyz" "Hello world")]
        (expect (pos? tokens)))))

;; =============================================================================
;; Message Counting Tests
;; =============================================================================

(defdescribe count-messages-test
  "Tests for count-messages function"

  (it "counts tokens for simple message array"
      (let [messages [{:role "user" :content "Hello!"}]
            tokens (sut/count-messages "gpt-4o" messages)]
        (expect (pos? tokens))))

  (it "includes overhead for multiple messages"
      (let [single [{:role "user" :content "Hello"}]
            multiple [{:role "system" :content "You are helpful."}
                      {:role "user" :content "Hello"}]
            single-tokens (sut/count-messages "gpt-4o" single)
            multiple-tokens (sut/count-messages "gpt-4o" multiple)]
      ;; Multiple messages should have more tokens due to overhead
        (expect (< single-tokens multiple-tokens))))

  (it "handles system and user roles"
      (let [messages [{:role "system" :content "Be helpful."}
                      {:role "user" :content "What is 2+2?"}
                      {:role "assistant" :content "4"}]
            tokens (sut/count-messages "gpt-4o" messages)]
        (expect (pos? tokens)))))

;; =============================================================================
;; Cost Estimation Tests
;; =============================================================================

(defdescribe estimate-cost-test
  "Tests for estimate-cost function"

  (it "calculates cost for known model"
      (let [cost (sut/estimate-cost "gpt-4o" 1000 500)]
        (expect (pos? (:input-cost cost)))
        (expect (pos? (:output-cost cost)))
        (expect (pos? (:total-cost cost)))
        (expect (= "gpt-4o" (:model cost)))))

  (it "uses default pricing for unknown models"
      (let [cost (sut/estimate-cost "unknown-model" 1000 500)]
        (expect (pos? (:total-cost cost)))))

  (it "calculates total as sum of input and output"
      (let [cost (sut/estimate-cost "gpt-4o" 1000000 1000000)]
        (expect (= (:total-cost cost)
                   (+ (:input-cost cost) (:output-cost cost))))))

  (it "includes pricing rates in result"
      (let [cost (sut/estimate-cost "gpt-4o" 1000 500)]
        (expect (contains? (:pricing cost) :input))
        (expect (contains? (:pricing cost) :output)))))

;; =============================================================================
;; Count and Estimate Tests
;; =============================================================================

(defdescribe count-and-estimate-test
  "Tests for count-and-estimate function"

  (it "returns token counts and cost"
      (let [messages [{:role "user" :content "Hello!"}]
            output "Hello! How can I help you?"
            result (sut/count-and-estimate "gpt-4o" messages output)]
        (expect (pos? (:input-tokens result)))
        (expect (pos? (:output-tokens result)))
        (expect (= (:total-tokens result)
                   (+ (:input-tokens result) (:output-tokens result))))
        (expect (map? (:cost result)))
        (expect (pos? (get-in result [:cost :total-cost]))))))

;; =============================================================================
;; Format Cost Tests
;; =============================================================================

(defdescribe format-cost-test
  "Tests for format-cost function"

  (it "formats regular cost"
      (expect (= "$0.0025" (sut/format-cost 0.0025))))

  (it "formats very small cost"
      (expect (= "<$0.0001" (sut/format-cost 0.00001))))

  (it "formats zero cost"
      (expect (= "<$0.0001" (sut/format-cost 0)))))

;; =============================================================================
;; Text Truncation Tests
;; =============================================================================

(defdescribe truncate-text-test
  "Tests for token-aware text truncation"

  (describe "within limit"
            (it "returns text unchanged when within limit"
                (let [text "Hello"
                      result (sut/truncate-text "gpt-4o" text 100)]
                  (expect (= text result)))))

  (describe "exceeds limit"
            (it "truncates text when exceeds limit"
                (let [text (apply str (repeat 1000 "word "))
                      result (sut/truncate-text "gpt-4o" text 10)]
                  (expect (< (count result) (count text)))))))

;; =============================================================================
;; Context Limit Check Tests
;; =============================================================================

(defdescribe check-context-limit-test
  "Tests for pre-flight context limit checks"

  (describe "within limit"
            (it "returns ok for small messages"
                (let [messages [{:role "user" :content "Hello"}]
                      result (sut/check-context-limit "gpt-4o" messages)]
                  (expect (:ok? result))
                  (expect (pos? (:input-tokens result)))
                  (expect (pos? (:max-input-tokens result))))))

  (describe "exceeds limit"
            (it "returns not ok for huge messages"
                (let [huge-content (apply str (repeat 100000 "word "))
                      messages [{:role "user" :content huge-content}]
                      result (sut/check-context-limit "gpt-4" messages)]
        ;; gpt-4 has 8192 context, should exceed
                  (expect (not (:ok? result)))))))
