(ns com.blockether.svar.internal.tokens-test
  "Tests for token counting utilities.
   
   Ported from unbound.backend.shared.llm.internal.tokens-test
   
   NOTE: These tests require Java to be compiled first.
   Run `make compile-java` in the svar package before running tests."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.router :as sut]))

;; =============================================================================
;; Context Limits Tests
;; =============================================================================

(defdescribe context-limit-test
  "Tests for model context limits"

  (describe "known models"
    (it "returns 128000 for gpt-4o"
      (expect (= 128000 (sut/context-limit "gpt-4o"))))

    (it "returns 1047576 (≈1M) for gpt-4.1 — catalog-exact value"
      (expect (= 1047576 (sut/context-limit "gpt-4.1"))))

    (it "returns Copilot provider context for claude-sonnet-4.6"
      ;; Pre-fix this row claimed 1M context — Anthropic-native value
      ;; copy-pasted into the `:github-copilot` overlay. Copilot proxy
      ;; actually caps Claude-sonnet-4.6 at 200K context (128K input,
      ;; 32K output per models.dev). The overlay now omits `:context`
      ;; entirely so the models.dev catalog supplies the real number.
      (expect (= 200000 (sut/context-limit "claude-sonnet-4.6"))))

    (it "returns Anthropic provider context for claude-sonnet-4-6"
      (expect (= 200000 (sut/context-limit "claude-sonnet-4-6"))))

    (it "returns 1000000 for claude-opus-4-8"
      (expect (= 1000000 (sut/context-limit "claude-opus-4-8"))))

    (it "returns 1000000 for claude-opus-4-6"
      (expect (= 1000000 (sut/context-limit "claude-opus-4-6"))))

    (it "returns conservative flattened GPT-5.5 context"
      (expect (= 272000 (sut/context-limit "gpt-5.5"))))

    (it "keeps provider-scoped GPT-5.5 contexts exact"
      (expect (= 1050000 (sut/provider-model-context :openai "gpt-5.5")))
      (expect (= 272000 (sut/provider-model-context :openai-codex "gpt-5.5"))))

    (it "uses Codex catalog prompt windows for current GPT coding models"
      (expect (= 272000 (sut/provider-model-context :openai-codex "gpt-5.3-codex")))
      (expect (= 272000 (sut/provider-model-context :openai-codex "gpt-5.4")))
      (expect (= 272000 (sut/provider-model-context :openai-codex "gpt-5.4-mini")))
      (expect (= 272000 (sut/provider-model-context :openai-codex "gpt-5.6-sol")))
      (expect (= 272000 (sut/provider-model-context :openai-codex "gpt-5.6-terra"))))

    (it "uses Copilot prompt budgets for visible GPT reasoning coding models"
      (doseq [name ["gpt-5.3-codex" "gpt-5.4" "gpt-5.4-mini" "gpt-5.5"]]
        (expect (= 272000 (sut/provider-model-context :github-copilot name))))))

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

  (it "falls back to o200k_base for unknown models"
    ;; Should not throw, should use fallback encoding (o200k_base — closer
    ;; than cl100k to Claude/Gemini/GLM and newer OpenAI models; see
    ;; router/model->encoding).
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
      (expect (contains? (:pricing cost) :output))))

  (it "tracks public OpenAI standard cached pricing"
    (expect (= {:input 5.00 :cached-input 0.50 :output 30.00
                :input-over-272k 10.00 :cached-input-over-272k 1.00
                :output-over-272k 45.00}
              (select-keys (:pricing (sut/estimate-cost "gpt-5.5" 1 1))
                [:input :cached-input :output
                 :input-over-272k :cached-input-over-272k :output-over-272k])))
    (expect (= {:input 2.50 :cached-input 0.25 :output 15.00
                :input-over-272k 5.00 :cached-input-over-272k 0.50
                :output-over-272k 22.50}
              (select-keys (:pricing (sut/estimate-cost "gpt-5.4" 1 1))
                [:input :cached-input :output
                 :input-over-272k :cached-input-over-272k :output-over-272k])))
    (expect (= {:input 1.75 :cached-input 0.175 :output 14.00}
              (select-keys (:pricing (sut/estimate-cost "gpt-5.3-codex" 1 1))
                [:input :cached-input :output])))
    (expect (= {:input 0.75 :cached-input 0.075 :output 4.50}
              (select-keys (:pricing (sut/estimate-cost "gpt-5.4-mini" 1 1))
                [:input :cached-input :output]))))

  (it "applies OpenAI long-context pricing to uncached cached and output components"
    (let [cost (sut/estimate-cost "gpt-5.5" 300000 1000
                 {"gpt-5.5" {:input 5.00 :cached-input 0.50 :output 30.00
                             :input-over-272k 10.00 :cached-input-over-272k 1.00
                             :output-over-272k 45.00}}
                 {:cached-tokens 100000})]
      (expect (= 200000 (:input-uncached-tokens cost)))
      (expect (= 100000 (:input-cached-tokens cost)))
      (expect (< (Math/abs (- 2.0 (:input-uncached-cost cost))) 1.0E-12))
      (expect (< (Math/abs (- 0.1 (:input-cached-cost cost))) 1.0E-12))
      (expect (< (Math/abs (- 0.045 (:output-cost cost))) 1.0E-12))
      (expect (< (Math/abs (- 2.145 (:total-cost cost))) 1.0E-12))))

  (it "tracks public Anthropic and Gemini cache pricing"
    (expect (= {:input 5.00 :cached-input 0.50
                :cache-write-5m 6.25 :cache-write-1h 10.00
                :output 25.00}
              (select-keys (:pricing (sut/estimate-cost "claude-opus-4-8" 1 1))
                [:input :cached-input :cache-write-5m :cache-write-1h :output])))
    (expect (= {:input 1.25 :cached-input 0.125 :output 10.00
                :input-over-200k 2.50 :cached-input-over-200k 0.25
                :output-over-200k 15.00}
              (select-keys (:pricing (sut/estimate-cost "gemini-2.5-pro" 1 1))
                [:input :cached-input :output
                 :input-over-200k :cached-input-over-200k :output-over-200k]))))

  (it "separates uncached input, cached input, output, and total cost"
    (let [pricing {"gpt-4o" {:input 2.00 :cached-input 0.50 :output 10.00}}
          cost (sut/estimate-cost "gpt-4o" 1000 500 pricing
                 {:cached-tokens 400})]
      (expect (= 600 (:input-uncached-tokens cost)))
      (expect (= 400 (:input-cached-tokens cost)))
      (expect (< (Math/abs (- 0.0012 (:input-uncached-cost cost))) 1.0E-12))
      (expect (< (Math/abs (- 0.0002 (:input-cached-cost cost))) 1.0E-12))
      (expect (< (Math/abs (- 0.0014 (:input-cost cost))) 1.0E-12))
      (expect (< (Math/abs (- 0.005 (:output-cost cost))) 1.0E-12))
      (expect (< (Math/abs (- 0.0064 (:total-cost cost))) 1.0E-12))))

  (it "prices Anthropic cache writes separately (Phase A: input-tokens is TOTAL inclusive)"
    ;; Pre-Phase-A this test asserted input=700 was the uncached prompt size
    ;; via `:cache-tokens-in-input? false`. After Phase A canonical input-tokens
    ;; is ALWAYS the TOTAL (anthropic-additive raw values are summed at the
    ;; canonical-normalizer boundary), so the caller passes input=1000 (uncached
    ;; 700 + cache-read 100 + cache-write 200) and uncached is computed inside.
    (let [pricing {"claude-sonnet-4-6" {:input 3.00 :cached-input 0.30
                                        :cache-write-5m 3.75 :cache-write-1h 6.00
                                        :output 15.00}}
          cost (sut/estimate-cost "claude-sonnet-4-6" 1000 50 pricing
                 {:cached-tokens 100
                  :cache-creation-tokens 200})]
      (expect (= 700 (:input-uncached-tokens cost)))
      (expect (= 100 (:input-cached-tokens cost)))
      (expect (= 200 (:input-cache-write-tokens cost)))
      (expect (< (Math/abs (- 0.0021 (:input-uncached-cost cost))) 1.0E-12))
      (expect (< (Math/abs (- 0.00003 (:input-cached-cost cost))) 1.0E-12))
      (expect (< (Math/abs (- 0.00075 (:input-cache-write-cost cost))) 1.0E-12))
      (expect (< (Math/abs (- 0.00075 (:output-cost cost))) 1.0E-12))
      (expect (< (Math/abs (- 0.00363 (:total-cost cost))) 1.0E-12)))))

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
      (expect (pos? (get-in result [:cost :total-cost])))))

  (it "passes cached and cache-creation usage into cost breakdown (Phase A canonical shape)"
    (let [messages [{:role "system" :content [{:type "text" :text "stable"
                                               :svar/cache true}]}
                    {:role "user" :content "Hello!"}]
          ;; Phase A canonical shape: :input-tokens TOTAL, details split out.
          api-usage {:input-tokens         1000
                     :output-tokens        100
                     :input-tokens-details {:regular 500 :cache-write 200 :cache-read 300}
                     :total-tokens         1100}
          pricing {"gpt-4o" {:input 2.00 :cached-input 0.50
                             :cache-write-5m 2.50 :output 10.00}}
          result (sut/count-and-estimate "gpt-4o" messages "ok"
                   {:api-usage api-usage :pricing pricing})]
      (expect (= 300 (:cached-tokens result)))
      (expect (= 200 (:cache-creation-tokens result)))
      (expect (= 500 (get-in result [:cost :input-uncached-tokens])))
      (expect (= 300 (get-in result [:cost :input-cached-tokens])))
      (expect (= 200 (get-in result [:cost :input-cache-write-tokens]))))))

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
