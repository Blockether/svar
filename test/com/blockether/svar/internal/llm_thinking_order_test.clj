(ns com.blockether.svar.internal.llm-thinking-order-test
  "Regression: Anthropic rejects assistant messages whose `thinking`
   blocks appear AFTER a `text` / `tool_use` block on REPLAY. Failure
   surfaces as

       400 messages.X.content.Y: Invalid signature in thinking block

   Vis conversation 6f5f7dbb-1e74-4f64-9223-6c3e28ee9dd0 stored an
   assistant message with canonical content `[thinking, text, thinking]`
   from a single Claude Code streaming response (the
   `claude-code-20250219` beta legitimately emits this). Anthropic
   accepts it on output, then rejects it when Vis echoes it back as
   prior context.

   Fix lives in svar's `:anthropic` wire serializer
   (`demote-interior-thinking-blocks`): keep all LEADING signed
   thinking blocks verbatim, demote any thinking block that follows a
   non-thinking block to a plain `text` block (drops the signature on
   purpose; Anthropic rejects interior signed blocks anyway, visible
   text round-trips fine).

   The non-Anthropic providers (z.ai preserved-thinking, OpenAI
   Responses) are NOT routed through this code path \u2014 their contracts
   require the canonical run stay contiguous and verbatim. Only
   `build-anthropic-request-body` performs the demotion."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.llm :as sut]))

(def ^:private demote @#'sut/demote-interior-thinking-blocks)
(def ^:private build-body @#'sut/build-anthropic-request-body)

;;; ── pure block transform ───────────────────────────────────────────────

(defdescribe demote-interior-thinking-blocks-test
  (describe "leading thinking only"
    (it "keeps a [thinking, text] message verbatim (no demotion)"
      (let [in [{:type "thinking" :thinking "plan" :thinking-signature "sig-A"}
                {:type "text" :text "answer"}]]
        (expect (= in (demote in)))))

    (it "keeps multiple leading thinking blocks verbatim"
      (let [in [{:type "thinking" :thinking "step 1" :thinking-signature "sig-A"}
                {:type "thinking" :thinking "step 2" :thinking-signature "sig-B"}
                {:type "text" :text "ok"}]]
        (expect (= in (demote in))))))

  (describe "interior thinking demotion"
    (it "demotes a trailing thinking that follows a text block"
      ;; Exact shape recovered from conversation 6f5f7dbb iteration
      ;; a0a93a34: [thinking-with-sig, text, thinking-with-sig].
      (let [in  [{:type "thinking" :thinking "first" :thinking-signature "sig-A"}
                 {:type "text" :text "visible body"}
                 {:type "thinking" :thinking "second" :thinking-signature "sig-B"}]
            out (demote in)]
        (expect (= 3 (count out)))
        ;; First two unchanged.
        (expect (= (nth in 0) (nth out 0)))
        (expect (= (nth in 1) (nth out 1)))
        ;; Third demoted: type flipped to "text", :thinking text
        ;; preserved as :text, signature gone.
        (expect (= {:type "text" :text "second"} (nth out 2)))))

    (it "demotes EVERY thinking block after the first non-thinking block"
      (let [in  [{:type "thinking" :thinking "lead" :thinking-signature "sig"}
                 {:type "text" :text "body"}
                 {:type "thinking" :thinking "interior-1" :thinking-signature "x"}
                 {:type "text" :text "more"}
                 {:type "thinking" :thinking "interior-2" :thinking-signature "y"}]
            out (demote in)]
        (expect (= 5 (count out)))
        (expect (= "thinking" (:type (nth out 0))))
        (expect (= "text"     (:type (nth out 1))))
        (expect (= "text"     (:type (nth out 2))))
        (expect (= "interior-1" (:text (nth out 2))))
        (expect (= "text"     (:type (nth out 3))))
        (expect (= "text"     (:type (nth out 4))))
        (expect (= "interior-2" (:text (nth out 4))))))

    (it "tolerates an empty thinking text on demotion (\"\" not nil)"
      (let [in  [{:type "text" :text "x"}
                 {:type "thinking" :thinking nil :thinking-signature "s"}]
            out (demote in)]
        (expect (= "" (:text (second out)))))))

  (describe "no-op cases"
    (it "passes through a thinking-only message"
      (let [in [{:type "thinking" :thinking "plan" :thinking-signature "sig"}]]
        (expect (= in (demote in)))))

    (it "passes through a text-only message"
      (let [in [{:type "text" :text "hi"}]]
        (expect (= in (demote in)))))

    (it "is identity on []"
      (expect (= [] (demote []))))))

;;; ── integration with build-anthropic-request-body ──────────────────────

;; Reach into the private serializer to confirm the demotion lands in
;; the actual wire body that hits Anthropic. Vis composes
;; canonical messages; svar emits Anthropic native shape; this test
;; pins the seam where the bug lived.

(defdescribe anthropic-wire-body-thinking-order-test
  (it "wire body for [thinking, text, thinking] demotes the trailing thinking"
    (let [messages [{:role    "user"
                     :content "redo it"}
                    {:role    "assistant"
                     :content [{:type "thinking" :thinking "plan A"
                                :thinking-signature "sig-A"}
                               {:type "text" :text "first answer"}
                               {:type "thinking" :thinking "plan B"
                                :thinking-signature "sig-B"}]}
                    {:role    "user"
                     :content "and now follow-up"}]
          body (build-body messages "claude-opus-4-7" {:max_tokens 1024})
          assistant-wire (-> body :messages second :content)]
      ;; Three blocks survive (we demote, not drop).
      (expect (= 3 (count assistant-wire)))
      ;; Leading thinking keeps signature verbatim \u2014 must round-trip.
      (expect (= "thinking" (:type (nth assistant-wire 0))))
      (expect (= "sig-A" (:signature (nth assistant-wire 0))))
      ;; Middle text untouched.
      (expect (= "text" (:type (nth assistant-wire 1))))
      (expect (= "first answer" (:text (nth assistant-wire 1))))
      ;; Trailing thinking is now a text block \u2014 NO :signature key on
      ;; the wire (Anthropic would reject it).
      (expect (= "text" (:type (nth assistant-wire 2))))
      (expect (= "plan B" (:text (nth assistant-wire 2))))
      (expect (not (contains? (nth assistant-wire 2) :signature)))))

  (it "wire body for already-leading thinking is byte-identical (no churn for healthy messages)"
    (let [messages [{:role "user" :content "go"}
                    {:role    "assistant"
                     :content [{:type "thinking" :thinking "ok"
                                :thinking-signature "sig"}
                               {:type "text" :text "done"}]}
                    {:role "user" :content "next"}]
          body (build-body messages "claude-opus-4-7" {:max_tokens 1024})
          assistant-wire (-> body :messages second :content)]
      (expect (= 2 (count assistant-wire)))
      (expect (= "thinking" (:type (first assistant-wire))))
      (expect (= "sig" (:signature (first assistant-wire))))
      (expect (= "text" (:type (second assistant-wire))))
      (expect (= "done" (:text (second assistant-wire))))))

  (it "wire body for [text, thinking] (model started with text, then a thinking trailer) demotes the trailing thinking"
    (let [messages [{:role "user" :content "hi"}
                    {:role    "assistant"
                     :content [{:type "text" :text "hello"}
                               {:type "thinking" :thinking "wait"
                                :thinking-signature "sig"}]}
                    {:role "user" :content "more"}]
          body (build-body messages "claude-opus-4-7" {:max_tokens 1024})
          wire (-> body :messages second :content)]
      (expect (= 2 (count wire)))
      (expect (= "text" (:type (first wire))))
      (expect (= "text" (:type (second wire))))
      (expect (= "wait" (:text (second wire))))))

  ;; Vis session ac065988: a long extended-thinking response whose
  ;; TRAILING thinking block carried no text. `demote-interior-thinking-blocks`
  ;; turned it into `{:type "text" :text ""}`; replaying that assistant
  ;; message 400'd the next call with
  ;;   `messages: text content blocks must be non-empty`.
  ;; The serializer must DROP empty/blank text blocks, never emit them.
  (it "drops an empty demoted thinking block instead of emitting an empty text block (ac065988)"
    (let [messages [{:role "user" :content "go"}
                    {:role    "assistant"
                     :content [{:type "text" :text "the answer"}
                               {:type "thinking" :thinking ""
                                :thinking-signature "sig"}]}
                    {:role "user" :content "next"}]
          body (build-body messages "claude-opus-4-7" {:max_tokens 1024})
          wire (-> body :messages second :content)]
      ;; Empty trailing block removed — only the real text survives, so the
      ;; single-text-collapse path emits the plain string (no empty block).
      (expect (= "the answer" wire))))

  (it "drops a missing-signature thinking block that has no text (would serialize to empty text)"
    (let [messages [{:role "user" :content "go"}
                    {:role    "assistant"
                     :content [{:type "thinking" :thinking ""
                                :thinking-signature ""}
                               {:type "text" :text "real body"}]}
                    {:role "user" :content "next"}]
          body (build-body messages "claude-opus-4-7" {:max_tokens 1024})
          wire (-> body :messages second :content)]
      ;; A thinking block is present (empty sig → demoted), so the array
      ;; form is kept; the empty text is dropped, leaving the real body.
      (expect (= [{:type "text" :text "real body"}] wire))))

  (it "drops the empty text block but keeps a co-present signed thinking block (no collapse)"
    (let [messages [{:role "user" :content "go"}
                    {:role    "assistant"
                     :content [{:type "thinking" :thinking "lead"
                                :thinking-signature "sig-A"}
                               {:type "text" :text "body"}
                               {:type "thinking" :thinking ""
                                :thinking-signature "sig-B"}]}
                    {:role "user" :content "next"}]
          body (build-body messages "claude-opus-4-7" {:max_tokens 1024})
          wire (-> body :messages second :content)]
      ;; Leading signed thinking + the real text survive; the empty
      ;; demoted trailer is dropped. Two blocks, no empty text.
      (expect (= 2 (count wire)))
      (expect (= "thinking" (:type (nth wire 0))))
      (expect (= "sig-A" (:signature (nth wire 0))))
      (expect (= {:type "text" :text "body"} (nth wire 1))))))
