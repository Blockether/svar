(ns com.blockether.svar.internal.rlm.context-tools-test
  (:require
   [clojure.string :as str]
   [lazytest.core :refer [defdescribe describe expect it throws?]]
   [sci.core :as sci]
   [com.blockether.svar.internal.rlm.tools :as tools]))

(defn- make-ctx
  "Create a sci context for testing."
  []
  (let [{:keys [sci-ctx initial-ns-keys]} (tools/create-sci-context nil (fn [_] {:content "ok"}) nil nil)]
    {:sci-ctx sci-ctx :initial-ns-keys initial-ns-keys}))

(defn- eval-in [ctx code]
  (sci/eval-string* (:sci-ctx ctx) code))

(defdescribe var-based-state-test
  (describe "def with docstrings"
    (it "def creates a var accessible in SCI"
      (let [ctx (make-ctx)]
        (eval-in ctx "(def results \"search results\" [1 2 3])")
        (expect (= [1 2 3] (eval-in ctx "results")))))

    (it "docstring is accessible via meta"
      (let [ctx (make-ctx)]
        (eval-in ctx "(def results \"search results\" [1 2 3])")
        (expect (= "search results" (eval-in ctx "(:doc (meta (var results)))"))))))

  (describe "var index"
    (it "build-var-index returns nil when no user vars"
      (let [{:keys [sci-ctx initial-ns-keys]} (make-ctx)]
        (expect (nil? (tools/build-var-index sci-ctx initial-ns-keys))))))

  (describe "vars persist across evaluations"
    (it "def'd vars persist across eval calls"
      (let [ctx (make-ctx)]
        (eval-in ctx "(def x \"first var\" 42)")
        (eval-in ctx "(def y \"second var\" (* x 2))")
        (expect (= 84 (eval-in ctx "y")))))))
