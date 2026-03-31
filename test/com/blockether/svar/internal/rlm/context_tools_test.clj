(ns com.blockether.svar.internal.rlm.context-tools-test
  (:require
   [lazytest.core :refer [defdescribe describe expect it throws?]]
   [sci.core :as sci]
   [com.blockether.svar.internal.rlm.tools :as tools]))

(defn- make-ctx
  "Create a sci context and return the p-atom for testing."
  []
  (let [{:keys [p-atom sci-ctx]} (tools/create-sci-context nil (fn [_] {:content "ok"}) nil (atom {}) nil nil)]
    {:p-atom p-atom :sci-ctx sci-ctx}))

(defn- eval-in [ctx code]
  (sci.core/eval-string* (:sci-ctx ctx) code))

(defdescribe context-tools-test
  (describe "initial state"
    (it "starts with empty context"
      (let [{:keys [p-atom]} (make-ctx)]
        (expect (= [] (:context @p-atom))))))

  (describe "ctx-add!"
    (it "adds a string to context"
      (let [ctx (make-ctx)]
        (eval-in ctx "(ctx-add! \"hello world\")")
        (expect (= ["hello world"] (:context @(:p-atom ctx))))))

    (it "appends multiple entries"
      (let [ctx (make-ctx)]
        (eval-in ctx "(ctx-add! \"first\")")
        (eval-in ctx "(ctx-add! \"second\")")
        (eval-in ctx "(ctx-add! \"third\")")
        (expect (= ["first" "second" "third"] (:context @(:p-atom ctx)))))))

  (describe "ctx-remove!"
    (it "removes by index"
      (let [ctx (make-ctx)]
        (eval-in ctx "(ctx-add! \"a\")")
        (eval-in ctx "(ctx-add! \"b\")")
        (eval-in ctx "(ctx-add! \"c\")")
        (eval-in ctx "(ctx-remove! 1)")
        (expect (= ["a" "c"] (:context @(:p-atom ctx))))))

    (it "removes first entry"
      (let [ctx (make-ctx)]
        (eval-in ctx "(ctx-add! \"a\")")
        (eval-in ctx "(ctx-add! \"b\")")
        (eval-in ctx "(ctx-remove! 0)")
        (expect (= ["b"] (:context @(:p-atom ctx))))))

    (it "removes last entry"
      (let [ctx (make-ctx)]
        (eval-in ctx "(ctx-add! \"a\")")
        (eval-in ctx "(ctx-add! \"b\")")
        (eval-in ctx "(ctx-add! \"c\")")
        (eval-in ctx "(ctx-remove! 2)")
        (expect (= ["a" "b"] (:context @(:p-atom ctx)))))))

  (describe "ctx-clear!"
    (it "removes all entries"
      (let [ctx (make-ctx)]
        (eval-in ctx "(ctx-add! \"a\")")
        (eval-in ctx "(ctx-add! \"b\")")
        (eval-in ctx "(ctx-clear!)")
        (expect (= [] (:context @(:p-atom ctx)))))))

  (describe "ctx-replace!"
    (it "replaces a single entry"
      (let [ctx (make-ctx)]
        (eval-in ctx "(ctx-add! \"a\")")
        (eval-in ctx "(ctx-add! \"b\")")
        (eval-in ctx "(ctx-add! \"c\")")
        (eval-in ctx "(ctx-replace! 1 1 \"B-replaced\")")
        (expect (= ["a" "B-replaced" "c"] (:context @(:p-atom ctx))))))

    (it "replaces a range of entries with one summary"
      (let [ctx (make-ctx)]
        (eval-in ctx "(ctx-add! \"a\")")
        (eval-in ctx "(ctx-add! \"b\")")
        (eval-in ctx "(ctx-add! \"c\")")
        (eval-in ctx "(ctx-add! \"d\")")
        (eval-in ctx "(ctx-add! \"e\")")
        (eval-in ctx "(ctx-replace! 1 3 \"b+c+d summary\")")
        (expect (= ["a" "b+c+d summary" "e"] (:context @(:p-atom ctx))))))

    (it "replaces from start"
      (let [ctx (make-ctx)]
        (eval-in ctx "(ctx-add! \"a\")")
        (eval-in ctx "(ctx-add! \"b\")")
        (eval-in ctx "(ctx-add! \"c\")")
        (eval-in ctx "(ctx-add! \"d\")")
        (eval-in ctx "(ctx-replace! 0 2 \"a+b+c merged\")")
        (expect (= ["a+b+c merged" "d"] (:context @(:p-atom ctx))))))

    (it "replaces to end"
      (let [ctx (make-ctx)]
        (eval-in ctx "(ctx-add! \"a\")")
        (eval-in ctx "(ctx-add! \"b\")")
        (eval-in ctx "(ctx-add! \"c\")")
        (eval-in ctx "(ctx-add! \"d\")")
        (eval-in ctx "(ctx-replace! 2 3 \"c+d merged\")")
        (expect (= ["a" "b" "c+d merged"] (:context @(:p-atom ctx))))))

    (it "replaces all entries"
      (let [ctx (make-ctx)]
        (eval-in ctx "(ctx-add! \"a\")")
        (eval-in ctx "(ctx-add! \"b\")")
        (eval-in ctx "(ctx-add! \"c\")")
        (eval-in ctx "(ctx-replace! 0 2 \"everything merged\")")
        (expect (= ["everything merged"] (:context @(:p-atom ctx)))))))

  (describe "removed learning tools"
    (it "does not expose learn!"
      (let [ctx (make-ctx)]
        (expect (throws? Exception #(eval-in ctx "(learn! \"insight one\")")))))

    (it "does not expose forget!"
      (let [ctx (make-ctx)]
        (expect (throws? Exception #(eval-in ctx "(forget! 0)")))))))
