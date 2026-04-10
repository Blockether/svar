(ns com.blockether.svar.internal.rlm.on-iteration-test
  "W10 — :on-iteration callback. Fires synchronously at the end of each
   iteration's main body with a structured summary map containing
   :iteration :status :thinking :executions :final-result :error :duration-ms.

   The callback fires on three paths:
   * :error   — when the LLM call itself fails (ex-info caught)
   * :empty   — when the LLM returned no code blocks
   * :success — when the iteration ran code and produced results
   * :final   — when the iteration ran code and set `final`

   A throwing callback is caught and swallowed (logged) so the iteration
   loop keeps running."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.rlm :as sut]
   [com.blockether.svar.internal.rlm.core :as rlm-core]))

(defn- stub-router []
  (llm/make-router [{:id :test :api-key "test" :base-url "http://localhost"
                     :models [{:name "stub" :input-cost 0 :output-cost 0}]}]))

(defdescribe on-iteration-opt-plumbed-test
  (describe ":on-iteration opt threading"
    (it "query-env! accepts :on-iteration without error when no iterations run"
      (let [env (sut/create-env (stub-router) {:db :temp})
            fired (atom [])]
        (try
          ;; :max-iterations 0 — iteration-loop returns :max-iterations
          ;; immediately. Callback should NOT fire because no iterations
          ;; ever start their body.
          (let [result (sut/query-env! env [(llm/user "noop")]
                         {:max-iterations 0
                          :on-iteration (fn [evt] (swap! fired conj evt))})]
            (expect (= :max-iterations (:status result)))
            (expect (empty? @fired)))
          (finally (sut/dispose-env! env)))))))

(defdescribe on-iteration-error-path-test
  (describe "query-env! :on-iteration on error"
    (it "fires callback with :status :error when the LLM call throws"
      ;; stub-router points at http://localhost which refuses the connection,
      ;; so the first LLM call throws, iteration-loop catches it at the
      ;; ::iteration-error branch, stores an error iteration, and fires
      ;; :on-iteration with :status :error.
      (let [env (sut/create-env (stub-router) {:db :temp})
            fired (atom [])]
        (try
          (sut/query-env! env [(llm/user "test")]
            {:max-iterations 2
             :on-iteration (fn [evt] (swap! fired conj evt))})
          (expect (pos? (count @fired)))
          (expect (some #(= :error (:status %)) @fired))
          (expect (every? #(contains? % :iteration) @fired))
          (expect (every? #(contains? % :duration-ms) @fired))
          (finally (sut/dispose-env! env)))))))

(defdescribe on-iteration-throwing-callback-swallowed-test
  (describe "throwing :on-iteration callback"
    (it "query-env! keeps running when callback throws"
      (let [env (sut/create-env (stub-router) {:db :temp})
            fire-count (atom 0)]
        (try
          ;; Callback increments and always throws — iteration-loop should
          ;; catch, log, and continue. The final result still comes back.
          (let [result (sut/query-env! env [(llm/user "test")]
                         {:max-iterations 3
                          :on-iteration (fn [_]
                                          (swap! fire-count inc)
                                          (throw (ex-info "boom" {})))})]
            (expect (some? (:status result)))
            (expect (pos? @fire-count)))
          (finally (sut/dispose-env! env)))))))

(defdescribe on-iteration-event-shape-test
  (describe "iteration event map shape"
    (it "event map has all documented keys"
      (let [env (sut/create-env (stub-router) {:db :temp})
            fired (atom nil)]
        (try
          (sut/query-env! env [(llm/user "test")]
            {:max-iterations 1
             :on-iteration (fn [evt] (reset! fired evt))})
          (let [evt @fired]
            (expect (some? evt))
            (expect (contains? evt :iteration))
            (expect (contains? evt :status))
            (expect (contains? evt :thinking))
            (expect (contains? evt :executions))
            (expect (contains? evt :final-result))
            (expect (contains? evt :error))
            (expect (contains? evt :duration-ms))
            (expect (contains? #{:error :empty :success :final} (:status evt))))
          (finally (sut/dispose-env! env)))))))
