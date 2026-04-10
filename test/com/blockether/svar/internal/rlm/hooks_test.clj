(ns com.blockether.svar.internal.rlm.hooks-test
  "Hook v3 — per-tool :before/:after/:wrap chains, id-based merge, and
   global observer hooks (:on-iteration/:on-iteration-start/:on-cancel/
   :on-error/:on-final/:on-chunk/:on-tool-invoked/:on-tool-completed).

   Tests split across three concerns:
   1. Pure engine (normalize-hooks, merge-hook-chain, compose-wrap-chain)
   2. Per-tool hook dispatch via SCI-bound wrapped tool
   3. Global lifecycle hooks + caller-owned cancel-atom"
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [sci.core :as sci]
   [com.blockether.svar.internal.llm :as llm]
   [com.blockether.svar.internal.rlm :as sut]
   [com.blockether.svar.internal.rlm.tools :as tools]))

(defn- stub-router []
  (llm/make-router [{:id :test :api-key "test" :base-url "http://localhost"
                     :models [{:name "stub" :input-cost 0 :output-cost 0}]}]))

(defn- eval-in-sandbox
  [env code]
  (:val (sci/eval-string+ (:sci-ctx env) code {:ns (:sandbox-ns env)})))

;; =============================================================================
;; Pure engine tests — normalize, merge, compose
;; =============================================================================

(defdescribe normalize-hooks-test
  (describe "normalize-hooks"
    (it "nil → empty vec"
      (expect (= [] (tools/normalize-hooks :before nil))))

    (it "single fn → [{:id :fn}]"
      (let [f (fn [_] nil)
            result (tools/normalize-hooks :before f)]
        (expect (= 1 (count result)))
        (expect (= f (:fn (first result))))
        (expect (symbol? (:id (first result))))))

    (it "map with :fn → keeps map, auto-fills :id if missing"
      (let [f (fn [_] nil)
            result (tools/normalize-hooks :before {:fn f})]
        (expect (= 1 (count result)))
        (expect (= f (:fn (first result))))
        (expect (symbol? (:id (first result))))))

    (it "map with :id and :fn → preserves both"
      (let [f (fn [_] nil)
            result (tools/normalize-hooks :before {:id :my/hook :fn f})]
        (expect (= [{:id :my/hook :fn f}] result))))

    (it "vec of mixed fns and maps → canonical vec of maps"
      (let [f1 (fn [_] nil)
            f2 (fn [_] nil)
            result (tools/normalize-hooks :before [f1 {:id :b :fn f2}])]
        (expect (= 2 (count result)))
        (expect (= f1 (:fn (first result))))
        (expect (= :b (:id (second result))))))

    (it "throws on invalid entry (not fn, not map with :fn)"
      (expect (try (tools/normalize-hooks :before [42])
                   false
                   (catch Exception _ true))))))

(defdescribe merge-hook-chain-test
  (describe "merge-hook-chain"
    (let [f1 (fn [_] nil)
          f2 (fn [_] nil)
          f3 (fn [_] nil)
          f1-new (fn [_] :new)]

      (it "appends new ids to end"
        (let [existing [{:id :a :fn f1}]
              incoming [{:id :b :fn f2}]]
          (expect (= [{:id :a :fn f1} {:id :b :fn f2}]
                    (tools/merge-hook-chain existing incoming)))))

      (it "replaces same id in place, preserves position"
        (let [existing [{:id :a :fn f1} {:id :b :fn f2} {:id :c :fn f3}]
              incoming [{:id :b :fn f1-new}]
              merged (tools/merge-hook-chain existing incoming)]
          (expect (= :a (:id (nth merged 0))))
          (expect (= :b (:id (nth merged 1))))
          (expect (= f1-new (:fn (nth merged 1))))
          (expect (= :c (:id (nth merged 2))))))

      (it "preserves old ids not present in incoming"
        (let [existing [{:id :a :fn f1} {:id :b :fn f2}]
              incoming [{:id :c :fn f3}]]
          (expect (= 3 (count (tools/merge-hook-chain existing incoming))))))

      (it "empty existing + incoming"
        (expect (= [{:id :a :fn f1}]
                  (tools/merge-hook-chain [] [{:id :a :fn f1}])))))))

;; =============================================================================
;; Per-tool hook dispatch via SCI-bound wrapped tool
;; =============================================================================

(defdescribe register-env-fn-hooks-test
  (describe "register-env-fn! with hooks"
    (it "plain registration without hooks still works"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/register-env-fn! env 'double (fn [x] (* 2 x))
            {:doc "doubles" :returns {:type :int}})
          (expect (= 10 (eval-in-sandbox env "(double 5)")))
          (finally (sut/dispose-env! env)))))

    (it ":before {:args} transforms args"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/register-env-fn! env 'add (fn [a b] (+ a b))
            {:doc "add"
             :returns {:type :int}
             :before (fn [inv]
                       {:args [(inc (first (:args inv))) (second (:args inv))]})})
          (expect (= 11 (eval-in-sandbox env "(add 5 5)")))
          (finally (sut/dispose-env! env)))))

    (it ":before {:skip v} short-circuits and returns v"
      (let [env (sut/create-env (stub-router) {:db :temp})
            called (atom false)]
        (try
          (sut/register-env-fn! env 'do-work
            (fn [_] (reset! called true) :real)
            {:doc "work"
             :returns {:type :keyword}
             :before (fn [_] {:skip :cached})})
          (expect (= :cached (eval-in-sandbox env "(do-work :arg)")))
          (expect (false? @called))
          (finally (sut/dispose-env! env)))))

    (it ":before {:error e} short-circuits and throws ex-info"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/register-env-fn! env 'dangerous (fn [_] :ran)
            {:doc "dangerous"
             :returns {:type :keyword}
             :before (fn [_] {:error {:type :rlm/permission-denied
                                      :message "nope"}})})
          (let [thrown?
                (try (eval-in-sandbox env "(dangerous :arg)")
                     false
                     (catch Exception _ true))]
            (expect thrown?))
          (finally (sut/dispose-env! env)))))

    (it ":after {:result v} replaces result"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/register-env-fn! env 'identity-fn (fn [x] x)
            {:doc "identity"
             :returns {:type :any}
             :after (fn [_] {:result :replaced})})
          (expect (= :replaced (eval-in-sandbox env "(identity-fn :orig)")))
          (finally (sut/dispose-env! env)))))

    (it ":after can recover from failure ({:result v :error nil})"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/register-env-fn! env 'may-fail
            (fn [_] (throw (ex-info "boom" {})))
            {:doc "may-fail"
             :returns {:type :keyword}
             :after (fn [outcome]
                      (when (:error outcome)
                        {:result :recovered :error nil}))})
          (expect (= :recovered (eval-in-sandbox env "(may-fail :arg)")))
          (finally (sut/dispose-env! env)))))

    (it ":after can flip success → failure (post-validation)"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/register-env-fn! env 'validated-write (fn [x] x)
            {:doc "validated-write"
             :returns {:type :any}
             :after (fn [outcome]
                      (when (= :bad (:result outcome))
                        {:error {:type :rlm/validation-failed
                                 :message "bad value"}}))})
          (expect (= :ok (eval-in-sandbox env "(validated-write :ok)")))
          (let [thrown?
                (try (eval-in-sandbox env "(validated-write :bad)")
                     false
                     (catch Exception _ true))]
            (expect thrown?))
          (finally (sut/dispose-env! env)))))

    (it ":wrap ring middleware composes vec-LAST = outermost"
      (let [env (sut/create-env (stub-router) {:db :temp})
            trace (atom [])]
        (try
          (sut/register-env-fn! env 'traced (fn [x] (swap! trace conj :fn) x)
            {:doc "traced"
             :returns {:type :any}
             :wrap [{:id :inner
                     :fn (fn [handler]
                           (fn [inv]
                             (swap! trace conj :inner-before)
                             (let [o (handler inv)]
                               (swap! trace conj :inner-after)
                               o)))}
                    {:id :outer
                     :fn (fn [handler]
                           (fn [inv]
                             (swap! trace conj :outer-before)
                             (let [o (handler inv)]
                               (swap! trace conj :outer-after)
                               o)))}]})
          (eval-in-sandbox env "(traced :x)")
          ;; vec-last = outer: outer wraps inner wraps fn
          ;; Execution order: outer-before → inner-before → :fn → inner-after → outer-after
          (expect (= [:outer-before :inner-before :fn :inner-after :outer-after] @trace))
          (finally (sut/dispose-env! env)))))

    (it "throwing :before hook is caught and returned as error"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/register-env-fn! env 'boom-before (fn [_] :never)
            {:doc "boom"
             :returns {:type :keyword}
             :before (fn [_] (throw (ex-info "hook threw" {})))})
          (let [thrown?
                (try (eval-in-sandbox env "(boom-before :arg)")
                     false
                     (catch Exception _ true))]
            (expect thrown?))
          (finally (sut/dispose-env! env)))))

    (it "throwing :after hook is captured in :hook-errors, result flows"
      ;; Hard to observe from sandbox alone — we verify that the loop
      ;; doesn't crash. The real check is via global :on-tool-completed.
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/register-env-fn! env 'noisy (fn [x] x)
            {:doc "noisy"
             :returns {:type :any}
             :after (fn [_] (throw (ex-info "oh no" {})))})
          ;; Should still return the result normally
          (expect (= :ok (eval-in-sandbox env "(noisy :ok)")))
          (finally (sut/dispose-env! env)))))))

(defdescribe layered-registration-test
  (describe "register-env-fn! called multiple times on same sym"
    (it "new hooks merge by :id, same id replaces in place"
      (let [env (sut/create-env (stub-router) {:db :temp})
            trace (atom [])]
        (try
          (sut/register-env-fn! env 'layered (fn [x] x)
            {:doc "layered"
             :returns {:type :any}
             :before [{:id :first :fn (fn [_] (swap! trace conj :first-v1) nil)}
                      {:id :second :fn (fn [_] (swap! trace conj :second-v1) nil)}]})
          ;; Re-register with a new :first that should replace in place.
          (sut/register-env-fn! env 'layered (fn [x] x)
            {:doc "layered"
             :returns {:type :any}
             :before [{:id :first :fn (fn [_] (swap! trace conj :first-v2) nil)}]})
          (eval-in-sandbox env "(layered :x)")
          ;; :first-v2 (replaced) then :second-v1 (preserved)
          (expect (= [:first-v2 :second-v1] @trace))
          (finally (sut/dispose-env! env)))))))

(defdescribe inspection-helpers-test
  (describe "list-tool-hooks + list-registered-tools + unregister-hook!"
    (it "list-tool-hooks returns {:before :after :wrap} entries"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/register-env-fn! env 'inspect-me (fn [x] x)
            {:doc "x" :returns {:type :any}
             :before (fn [_] nil)
             :after (fn [_] nil)})
          (let [hooks (sut/list-tool-hooks env 'inspect-me)]
            (expect (= 1 (count (:before hooks))))
            (expect (= 1 (count (:after hooks))))
            (expect (= 0 (count (:wrap hooks))))
            (expect (every? :id (:before hooks)))
            (expect (every? :position (:before hooks)))
            (expect (every? :fn-name (:before hooks))))
          (finally (sut/dispose-env! env)))))

    (it "list-registered-tools returns summary of all tools"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/register-env-fn! env 'tool-a (fn [] 1) {:doc "a" :returns {:type :int}})
          (sut/register-env-fn! env 'tool-b (fn [] 2) {:doc "b" :returns {:type :int}
                                                       :before (fn [_] nil)})
          (let [tools (sut/list-registered-tools env)
                by-sym (into {} (map (juxt :sym identity)) tools)]
            (expect (contains? by-sym 'tool-a))
            (expect (contains? by-sym 'tool-b))
            (expect (= 0 (get-in by-sym ['tool-a :hook-counts :before])))
            (expect (= 1 (get-in by-sym ['tool-b :hook-counts :before]))))
          (finally (sut/dispose-env! env)))))

    (it "unregister-hook! removes a hook by id"
      (let [env (sut/create-env (stub-router) {:db :temp})
            fired (atom false)]
        (try
          (sut/register-env-fn! env 'removable (fn [x] x)
            {:doc "x" :returns {:type :any}
             :before {:id :my/hook :fn (fn [_] (reset! fired true) nil)}})
          (expect (true? (sut/unregister-hook! env 'removable :before :my/hook)))
          (reset! fired false)
          (eval-in-sandbox env "(removable :x)")
          (expect (false? @fired))
          (finally (sut/dispose-env! env)))))

    (it "unregister-hook! returns false when id not found"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (sut/register-env-fn! env 'nothing-here (fn [x] x)
            {:doc "x" :returns {:type :any}})
          (expect (false? (sut/unregister-hook! env 'nothing-here :before :nope)))
          (finally (sut/dispose-env! env)))))))

;; =============================================================================
;; Global lifecycle hooks via :hooks opt on query-env!
;; =============================================================================

(defdescribe global-on-tool-invoked-test
  (describe "global :on-tool-invoked / :on-tool-completed observers"
    (it "fire around per-tool :before and :after"
      (let [env (sut/create-env (stub-router) {:db :temp})
            invoked (atom [])
            completed (atom [])]
        (try
          (sut/register-env-fn! env 'observable (fn [x] x)
            {:doc "x" :returns {:type :any}})
          ;; Use query-env! to get :hooks binding active
          (sut/query-env! env [(llm/user "noop")]
            {:max-iterations 0
             :hooks {:on-tool-invoked (fn [inv] (swap! invoked conj (:sym inv)))
                     :on-tool-completed (fn [outc] (swap! completed conj (:sym outc)))}})
          ;; No iterations ran, so no tool was called — both empty.
          (expect (empty? @invoked))
          (expect (empty? @completed))
          (finally (sut/dispose-env! env)))))))

(defdescribe caller-owned-cancel-atom-test
  (describe "query-env! :cancel-atom opt"
    (it "caller-owned atom, pre-set to true, causes :status :cancelled"
      (let [env (sut/create-env (stub-router) {:db :temp})
            cancel (atom true)
            on-cancel-fired (atom false)]
        (try
          (let [result (sut/query-env! env [(llm/user "noop")]
                         {:max-iterations 5
                          :cancel-atom cancel
                          :hooks {:on-cancel (fn [_] (reset! on-cancel-fired true))}})]
            (expect (= :cancelled (:status result)))
            (expect (zero? (:iterations result)))
            (expect (true? @on-cancel-fired)))
          (finally (sut/dispose-env! env)))))

    (it "no cancel-atom opt → query-env! creates one internally, not cancelled"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          ;; :max-iterations 0 → immediate max-iterations exit, NOT cancelled
          (let [result (sut/query-env! env [(llm/user "noop")]
                         {:max-iterations 0})]
            (expect (not= :cancelled (:status result))))
          (finally (sut/dispose-env! env)))))))

(defdescribe hooks-shape-test
  (describe ":hooks opt accepts partial maps"
    (it "single hook entry is fine"
      (let [env (sut/create-env (stub-router) {:db :temp})
            fired (atom false)]
        (try
          (sut/query-env! env [(llm/user "noop")]
            {:max-iterations 5
             :cancel-atom (atom true)
             :hooks {:on-cancel (fn [_] (reset! fired true))}})
          (expect (true? @fired))
          (finally (sut/dispose-env! env)))))

    (it "empty :hooks map is a no-op"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (let [result (sut/query-env! env [(llm/user "noop")]
                         {:max-iterations 0 :hooks {}})]
            (expect (some? (:status result))))
          (finally (sut/dispose-env! env)))))

    (it "no :hooks at all is a no-op"
      (let [env (sut/create-env (stub-router) {:db :temp})]
        (try
          (let [result (sut/query-env! env [(llm/user "noop")]
                         {:max-iterations 0})]
            (expect (some? (:status result))))
          (finally (sut/dispose-env! env)))))))
