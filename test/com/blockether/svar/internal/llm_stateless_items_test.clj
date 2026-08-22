(ns com.blockether.svar.internal.llm-stateless-items-test
  "Stateless replay for OpenAI-Responses gateways that cannot resolve a
   SERVER-MINTED item id (Blockether/vis#59).

   The Responses wire lets the client replay items the server minted: a
   `reasoning` item's `rs_…` id + `encrypted_content` and a `function_call`
   item's `fc_…` id. Public OpenAI resolves those anywhere; a gateway that
   load-balances across several Azure OpenAI resources does not, and answers
   HTTP 400 'the requested item was created under a different Azure OpenAI
   resource' on whichever replica the request lands on — so retrying is
   useless, the REQUEST has to change.

   `:stateless-items? true` (vis: `is_stateless: true` on the provider) drops
   exactly those server-owned fields, and `failure/item-affinity-error?` lets
   the transport self-heal on the first such 400."
  (:require
   [com.blockether.svar.internal.failure :as failure]
   [com.blockether.svar.internal.llm :as sut]
   [lazytest.core :refer [defdescribe expect it]]))

(def ^:private build-responses @#'sut/build-openai-responses-request-body)
(def ^:private strip @#'sut/strip-server-item-ids)
(def ^:private inject @#'sut/inject-routed-params)

(def ^:private sig
  "{\"type\":\"reasoning\",\"id\":\"rs_A\",\"encrypted_content\":\"ENC_A\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"t\"}]}")

(def ^:private convo
  [{:role "system" :content "agent"}
   {:role "user" :content "fix"}
   {:role "assistant"
    :content [{:type "thinking" :thinking "t" :thinking-signature sig :redacted? false}
              {:type "tool_use" :id "c1|fc_1" :name "run_python" :input {:code "x"}}]}
   {:role "user" :content [{:type "tool_result" :tool_use_id "c1|fc_1" :content "1"}]}])

(defn- input-of [stateless?]
  (:input (build-responses convo "m-A" {} {:stateless-items? stateless?})))

(defn- items-of [stateless? type]
  (filterv #(= type (:type %)) (input-of stateless?)))

(defdescribe stateful-replay-is-unchanged-test
  (it "by default still replays the server-minted ids — public OpenAI wants them"
    (let [reasoning (first (items-of false "reasoning"))
          fn-call   (first (items-of false "function_call"))]
      (expect (= "rs_A" (:id reasoning)))
      (expect (= "ENC_A" (:encrypted_content reasoning)))
      (expect (= "fc_1" (:id fn-call)))
      (expect (= "c1" (:call_id fn-call))))))

(defdescribe stateless-replay-drops-server-ids-test
  (it "drops the reasoning id + encrypted_content and the function_call id"
    (let [reasoning (first (items-of true "reasoning"))
          fn-call   (first (items-of true "function_call"))]
      ;; the summary is client-readable text, so the item survives — only the
      ;; server-owned id and the resource-bound ciphertext go.
      (expect (not (contains? reasoning :id)))
      (expect (not (contains? reasoning :encrypted_content)))
      (expect (seq (:summary reasoning)))
      (expect (not (contains? fn-call :id)))
      ;; call_id is CLIENT pairing, never server-minted: it MUST survive or the
      ;; function_call_output no longer matches its call.
      (expect (= "c1" (:call_id fn-call)))
      (expect (= "run_python" (:name fn-call)))))
  (it "keeps the conversation itself intact"
    (let [msgs (items-of true "message")]
      (expect (= ["user"] (mapv :role msgs)))
      (expect (seq (items-of true "function_call_output")))))
  (it "keeps a reasoning item that still carries a summary the model can read"
    (let [kept (strip [{:type "reasoning" :id "rs_1" :encrypted_content "x"
                        :summary [{:type "summary_text" :text "t"}]}])]
      (expect (= [{:type "reasoning" :summary [{:type "summary_text" :text "t"}]}] kept))))
  (it "drops a reasoning item that would be left empty"
    (expect (= [] (strip [{:type "reasoning" :id "rs_1" :encrypted_content "x"}]))))
  (it "leaves items that carry no server id alone"
    (let [msg {:type "message" :role "user" :content [{:type "input_text" :text "hi"}]}]
      (expect (= [msg] (strip [msg]))))))

(defdescribe provider-opt-propagates-test
  (it "carries a provider's :stateless-items? into the per-call opts"
    (let [provider {:id :gw :api-key "k" :base-url "https://gw/v1"
                    :api-style :openai-compatible-responses}]
      (expect (true? (:stateless-items?
                      (inject {} (assoc provider :stateless-items? true) {:name "m"}))))
      ;; absent stays absent — the flag must never appear on its own
      (expect (not (contains? (inject {} provider {:name "m"}) :stateless-items?))))))

(defdescribe item-affinity-error-test
  (it "recognises the Azure cross-resource item rejection"
    (expect (failure/item-affinity-error?
              (ex-info "Item 'rs_A' of type 'reasoning' was created under a different Azure OpenAI resource"
                {:status 400})))
    (expect (= :resource-mismatch
              (:category (failure/classify
                           (ex-info "the requested item was created under a different Azure OpenAI resource"
                             {:status 400})))))
    (expect (false? (:retryable? (failure/classify
                                   (ex-info "was created under a different Azure OpenAI resource"
                                     {:status 400}))))))
  (it "does not fire on unrelated failures"
    (expect (not (failure/item-affinity-error? (ex-info "Overloaded" {:status 529}))))
    (expect (not (failure/item-affinity-error? (ex-info "connection reset by peer" {}))))))

(defdescribe stateless-retry-verdict-test
  (it "self-heals only before any visible output"
    (let [affinity (fn [data]
                     (ex-info "the requested item was created under a different Azure OpenAI resource"
                       (merge {:status 400} data)))]
      (expect (failure/retry-without-server-item-ids? (affinity {})))
      ;; already streamed bytes: a resend would duplicate them
      (expect (not (failure/retry-without-server-item-ids? (affinity {:content-acc-len 12}))))
      (expect (not (failure/retry-without-server-item-ids? (affinity {:reasoning-acc-len 3}))))
      ;; unrelated failure never triggers the stateless replay
      (expect (not (failure/retry-without-server-item-ids? (ex-info "Overloaded" {:status 529}))))))
  (it "remembers the host that rejected replayed item ids"
    (let [url "https://stateless-test.example.invalid/v1"]
      (try
        (expect (not (failure/stateless-items-host? url)))
        (failure/mark-stateless-items! url)
        (expect (failure/stateless-items-host? url))
        ;; sticky per HOST, not per path
        (expect (failure/stateless-items-host? "https://stateless-test.example.invalid/other"))
        (expect (not (failure/stateless-items-host? "https://other.example.invalid/v1")))
        (finally
          (swap! failure/stateless-item-hosts* disj "stateless-test.example.invalid"))))))
