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
   [com.blockether.svar.internal.llm :as sut]
   [lazytest.core :refer [defdescribe expect it]]))

(def ^:private build-responses @#'sut/build-openai-responses-request-body)
(def ^:private build-anthropic  @#'sut/build-anthropic-request-body)
(def ^:private sanitize         @#'sut/sanitize-replayed-messages)
(def ^:private stamp            @#'sut/stamp-assistant-model)

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
