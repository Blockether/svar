(ns com.blockether.svar.internal.llm-replay-hygiene-test
  "Defensive replay hygiene before a provider request is built — the svar-side
   equivalent of pi-ai's `transformMessages` guards. Two failure modes are
   covered:

   1. CROSS-MODEL reasoning replay. A thinking block's signature (Anthropic HMAC
      / OpenAI encrypted reasoning item) is model-bound; replaying one model's
      reasoning into a DIFFERENT model's call is a hard provider 400. vis cycles
      models mid-session, so this is reachable. The producing model is stamped on
      the canonical assistant message (`stamp-assistant-model`); request build
      drops thinking whose stamp differs from the target model.

   2. ABORTED / ERRORED turns. Their partial content (reasoning with no following
      item, half-written tool calls) is exactly what OpenAI rejects with
      'reasoning without following item', so such turns are skipped entirely."
  (:require
   [clojure.string :as str]
   [com.blockether.svar.internal.llm :as sut]
   [lazytest.core :refer [defdescribe expect it]]))

(def ^:private build-responses @#'sut/build-openai-responses-request-body)
(def ^:private build-anthropic  @#'sut/build-anthropic-request-body)
(def ^:private sanitize         @#'sut/sanitize-replayed-messages)
(def ^:private stamp            @#'sut/stamp-assistant-model)
(def ^:private build-chat       @#'sut/build-request-body)

(def ^:private sig-A
  "{\"type\":\"reasoning\",\"id\":\"rs_A\",\"encrypted_content\":\"ENC_A\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"t\"}]}")

(defn- convo
  "A 4-message conversation whose assistant turn (with `assistant-extra` merged
   in) carries a thinking block + a tool call, followed by its tool result."
  [assistant-extra]
  [{:role "system" :content "agent"}
   {:role "user" :content "fix"}
   (merge {:role "assistant"
           :content [{:type "thinking" :thinking "t" :thinking-signature sig-A :redacted? false}
                     {:type "tool_use" :id "c1" :name "run_python" :input {:code "x"}}]}
     assistant-extra)
   {:role "user" :content [{:type "tool_result" :tool_use_id "c1" :content "1"}]}])

(defn- responses-reasoning-count [target extra]
  (->> (build-responses (convo extra) target {})
    :input (filter #(= "reasoning" (:type %))) count))

(defn- anthropic-thinking-count [target extra]
  (->> (build-anthropic (convo extra) target {})
    :messages
    (mapcat #(when (sequential? (:content %)) (:content %)))
    (filter #(= "thinking" (:type %)))
    count))

(defn- anthropic-has-assistant? [target extra]
  (boolean (some #(= "assistant" (some-> (:role %) name))
             (:messages (build-anthropic (convo extra) target {})))))

(defdescribe stamp-assistant-model-test
  (it "tags the canonical assistant message with the producing model"
    (expect (= "m-A" (get-in (stamp {:assistant-message {:role "assistant" :content []}} "m-A")
                       [:assistant-message :model]))))
  (it "is a no-op when there is no assistant message or no model"
    (expect (= {:content "x"} (stamp {:content "x"} "m-A")))
    (expect (= {:assistant-message {:role "assistant"}}
              (stamp {:assistant-message {:role "assistant"}} nil)))))

(defdescribe chat-wire-strips-model-stamp-test
  (it "drops the :model stamp from assistant messages in the OpenAI chat body"
    (let [msgs [{:role "user" :content "hi"}
                {:role "assistant" :content "hello" :model "devstral-latest"}
                {:role "user" :content "bye"}]
          body (build-chat msgs "devstral-latest")]
      ;; top-level :model stays
      (expect (= "devstral-latest" (:model body)))
      ;; no message carries :model (would 422 on strict providers like Mistral)
      (expect (not-any? #(contains? % :model) (:messages body))))))

(defdescribe cross-model-reasoning-guard-test
  (it "OpenAI Responses: keeps reasoning for the SAME model, drops it for a DIFFERENT model"
    (expect (= 1 (responses-reasoning-count "m-A" {:model "m-A"})))
    (expect (= 0 (responses-reasoning-count "m-B" {:model "m-A"}))))
  (it "Anthropic: keeps thinking for the SAME model, drops it for a DIFFERENT model"
    (expect (= 1 (anthropic-thinking-count "m-A" {:model "m-A"})))
    (expect (= 0 (anthropic-thinking-count "m-B" {:model "m-A"}))))
  (it "leaves UNSTAMPED (legacy) turns untouched — we can't tell, so we don't guess"
    (expect (= 1 (responses-reasoning-count "m-B" {})))
    (expect (= 1 (anthropic-thinking-count "m-B" {})))))

(defdescribe aborted-turn-guard-test
  (it "skips assistant turns flagged :aborted / :error / :interrupted (either key)"
    (expect (false? (anthropic-has-assistant? "m-A" {:status :aborted :model "m-A"})))
    (expect (false? (anthropic-has-assistant? "m-A" {:stop-reason :error :model "m-A"})))
    (expect (false? (anthropic-has-assistant? "m-A" {:status "interrupted" :model "m-A"}))))
  (it "keeps a normal completed assistant turn"
    (expect (true? (anthropic-has-assistant? "m-A" {:model "m-A"})))))

(defdescribe sanitize-is-safe-test
  (it "is a no-op on a plain user/assistant array with no stamps or flags"
    (let [msgs [{:role "user" :content "hi"}
                {:role "assistant" :content [{:type "text" :text "ok"}]}]]
      (expect (= msgs (sanitize msgs "any-model"))))))

(defn- result-ids-for
  "tool_use_ids of every tool_result block across `msgs`."
  [msgs]
  (->> msgs
    (mapcat (fn [m] (when (sequential? (:content m))
                      (keep :tool_use_id (filter #(= "tool_result" (:type %)) (:content m))))))
    set))

(defdescribe orphan-tool-result-test
  ;; A tool call that FAILED/was interrupted (often after a transient retry or
  ;; two) leaves a dangling tool_use with no tool_result — a hard provider 400 /
  ;; an unanswered call. sanitize must synthesize a placeholder so every tool_use
  ;; is answered. Healthy turns are untouched.
  (it "injects a result for a tool_use dangling at the end of the array"
    (let [out (sanitize [{:role "user" :content "go"}
                         {:role "assistant" :content [{:type "tool_use" :id "c1" :name "cat" :input {}}]}]
                "m")]
      (expect (contains? (result-ids-for out) "c1"))))
  (it "leaves an already-resolved tool_use untouched (no duplicate result)"
    (let [msgs [{:role "assistant" :content [{:type "tool_use" :id "c1"}]}
                {:role "user" :content [{:type "tool_result" :tool_use_id "c1" :content "ok"}]}]]
      (expect (= msgs (sanitize msgs "m")))))
  (it "merges a missing result into the partial results turn (Anthropic single-turn rule)"
    (let [out (sanitize [{:role "assistant" :content [{:type "tool_use" :id "c1"} {:type "tool_use" :id "c2"}]}
                         {:role "user" :content [{:type "tool_result" :tool_use_id "c1" :content "ok"}]}]
                "m")]
      ;; both ids resolved, and still ONE results turn (not a second user message)
      (expect (= #{"c1" "c2"} (result-ids-for out)))
      (expect (= 2 (count out)))))
  (it "is a no-op when there are no tool calls at all"
    (let [msgs [{:role "user" :content "hi"}
                {:role "assistant" :content [{:type "text" :text "ok"}]}]]
      (expect (= msgs (sanitize msgs "m")))))
  (it "flags the synthetic result is_error (pi parity)"
    (let [out (sanitize [{:role "assistant" :content [{:type "tool_use" :id "c1"}]}] "m")]
      (expect (true? (get-in out [1 :content 0 :is_error]))))))

(def ^:private normalize-id  @#'sut/normalize-id-part)

(defdescribe tool-id-normalization-test
  ;; A tool-call id valid for one provider can be INVALID for the next (Anthropic
  ;; requires ^[a-zA-Z0-9_-]{1,64}$). A Copilot proxy / model switch that replays
  ;; it raw gets the tool_result rejected or dropped. sanitize rewrites the id on
  ;; BOTH the tool_use and its tool_result, consistently.
  (it "sanitizes to [A-Za-z0-9_-], <=64 chars"
    (expect (= "call_AbC-123" (normalize-id "call_AbC-123")))
    (expect (= "fc_abc_call_xyz" (normalize-id "fc_abc|call_xyz!@#")))
    (expect (= 64 (count (normalize-id (apply str (repeat 70 "a")))))))
  (it "rewrites a tool_use id and its tool_result id to the SAME normalized value"
    (let [out (sanitize [{:role "assistant" :content [{:type "tool_use" :id "fc|x!" :name "cat"}]}
                         {:role "user" :content [{:type "tool_result" :tool_use_id "fc|x!" :content "ok"}]}]
                "m")
          use-id (get-in out [0 :content 0 :id])
          res-id (get-in out [1 :content 0 :tool_use_id])]
      (expect (= "fc_x" use-id))
      (expect (= use-id res-id))))
  (it "leaves already-valid ids untouched"
    (let [msgs [{:role "assistant" :content [{:type "tool_use" :id "call_ok-1"}]}
                {:role "user" :content [{:type "tool_result" :tool_use_id "call_ok-1" :content "x"}]}]]
      (expect (= msgs (sanitize msgs "m"))))))

(defdescribe anthropic-wire-is-error-test
  (it "emits is_error on the Anthropic tool_result wire block"
    (let [body (build-anthropic
                 [{:role "user" :content [{:type "tool_result" :tool_use_id "c1"
                                           :is_error true :content "boom"}]}]
                 "claude-x" {})
          blocks (->> (:messages body) (mapcat :content))]
      (expect (some #(and (= "tool_result" (:type %)) (true? (:is_error %))) blocks)))))

(def ^:private responses-id @#'sut/responses-tool-call-id)
(def ^:private normalize-id3 @#'sut/normalize-tool-call-id)

(defdescribe responses-composite-id-test
  ;; OpenAI Responses gives a function_call BOTH a call_id (pairs with the output)
  ;; and an fc_ item id (pairs with the preceding reasoning item). Dropping the
  ;; fc_ id breaks reasoning-model replay. svar carries the composite
  ;; `call_id|item_id` and splits it back on the wire. (pi parity.)
  (it "extracts the composite call_id|item_id"
    (expect (= "call_abc|fc_xyz" (responses-id {:call_id "call_abc" :id "fc_xyz" :name "cat"})))
    (expect (= "call_only" (responses-id {:call_id "call_only"})))
    (expect (= "fc_only" (responses-id {:id "fc_only"}))))
  (it "normalize preserves the composite for OpenAI, collapses for others"
    (expect (= "call_abc|fc_xyz" (normalize-id3 "call_abc|fc_xyz" true)))
    (expect (= "call_abc_fc_xyz" (normalize-id3 "call_abc|fc_xyz" false))))
  (it "Responses re-emits function_call with BOTH id (fc_) and call_id, output keyed on call_id"
    (let [in (:input (build-responses
                       [{:role "assistant" :content [{:type "tool_use" :id "call_abc|fc_xyz" :name "cat" :input {"path" "x"}}]}
                        {:role "user" :content [{:type "tool_result" :tool_use_id "call_abc|fc_xyz" :content "ok"}]}]
                       "gpt-5" {}))
          fc (first (filter #(= "function_call" (:type %)) in))
          fo (first (filter #(= "function_call_output" (:type %)) in))]
      (expect (= "fc_xyz" (:id fc)))
      (expect (= "call_abc" (:call_id fc)))
      (expect (= "call_abc" (:call_id fo)))
      (expect (= (:call_id fc) (:call_id fo)))))
  (it "Anthropic replay of a composite id collapses it consistently (call↔result still match)"
    (let [blocks (->> (:messages (build-anthropic
                                   [{:role "assistant" :content [{:type "tool_use" :id "call_abc|fc_xyz" :name "cat" :input {}}]}
                                    {:role "user" :content [{:type "tool_result" :tool_use_id "call_abc|fc_xyz" :content "ok"}]}]
                                   "claude-x" {}))
                   (mapcat :content))
          tu (first (filter #(= "tool_use" (:type %)) blocks))
          tr (first (filter #(= "tool_result" (:type %)) blocks))]
      (expect (= (:id tu) (:tool_use_id tr)))
      (expect (not (str/includes? (str (:id tu)) "|"))))))

(def ^:private sig-long-id
  (str "{\"type\":\"reasoning\",\"id\":\"rs_" (apply str (repeat 64 \a))
    "\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"t\"}]}"))

(defdescribe responses-reasoning-id-ceiling-test
  ;; GitHub Copilot / Codex mints reasoning ids that exceed the 64-char
  ;; ceiling its own validator enforces on every :input item, rejecting the
  ;; replay with HTTP 400 `Invalid input[1].id: string too long`. Tool-call
  ;; ids are already clamped in normalize-tool-ids; reasoning-item ids must
  ;; be clamped on decode too, since a reasoning item is the only other
  ;; id-bearing input item svar emits.
  (it "clamps a >64-char reasoning id to <=64 and the wire contract"
    (let [convo [{:role "user" :content "go"}
                 {:role "assistant"
                  :content [{:type "thinking" :thinking "t" :thinking-signature sig-long-id :redacted? false}
                            {:type "text" :text "ok"}]}
                 {:role "user" :content "again"}]
          input (:input (build-responses convo "gpt-5" {}))
          r     (first (filter #(= "reasoning" (:type %)) input))]
      (expect (some? r))
      (expect (<= (count (:id r)) 64))
      (expect (some? (re-matches #"[A-Za-z0-9_-]{1,64}" (:id r)))))))
