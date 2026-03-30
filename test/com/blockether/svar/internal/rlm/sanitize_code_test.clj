(ns com.blockether.svar.internal.rlm.sanitize-code-test
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [sci.core :as sci]
   [com.blockether.svar.internal.rlm.core :as core]))

(def sanitize-code #'core/sanitize-code)

(defn- eval-sanitized
  "Sanitize code then eval in SCI. Returns result or {:error msg}."
  [code]
  (let [sanitized (sanitize-code code)
        ctx (sci/init {:namespaces {'user {'+ + '- - '* * 'str str 'count count
                                           'map map 'filter filter 'reduce reduce
                                           'conj conj 'assoc assoc 'get get 'merge merge
                                           'inc inc 'dec dec 'vec vec 'do identity
                                           'println println 'pr-str pr-str 'keyword keyword
                                           'first first 'rest rest 'seq seq 'nil? nil?
                                           '> > '< < '= = 'not not 'true? true?
                                           'FINAL (fn
                                                    ([answer] {:rlm/final true :answer answer})
                                                    ([answer opts] (merge {:rlm/final true :answer answer} opts)))
                                           'ctx-add! (fn [text] (str "Added: " text))
                                           'list-dir (fn [& _] {:path "." :entries [{:name "a.clj" :type "file"}] :total 1})
                                           'read-file (fn [& _] "file contents")}}})]
    (try
      (sci/eval-string* ctx sanitized)
      (catch Exception e
        {:error (ex-message e)}))))

(defn- q [s] (str "\"" s "\""))

;; Build test code strings that contain inner quotes safely
(def ^:private code-list-dir (str "(list-dir " (q ".") ")"))
(def ^:private code-def-data (str "(def mydata " code-list-dir ")"))
(def ^:private code-final-simple (str "(FINAL {:answer [" (q "hello") "]})"))
(def ^:private code-final-learn (str "(FINAL {:answer [" (q "result") "] :learn [{:insight " (q "x") " :tags [" (q "bug") "]}]})"))
(def ^:private code-final-multi (str "(FINAL {:answer [" (q "Part 1") " " (q "Part 2") "] :reasoning " (q "done") "})"))
(def ^:private code-do-final (str "(do (def x " code-list-dir ") (FINAL {:answer [(str (:total x) " (q " files") ")]}))"))
(def ^:private code-let-count (str "(let [files (:entries " code-list-dir ")] (count files))"))

(defdescribe sanitize-code-test
  (describe "string matching — valid code unchanged"
            (it "simple expression"     (expect (= "(+ 1 2)" (sanitize-code "(+ 1 2)"))))
            (it "nested parens"         (expect (= "(defn foo [x] (+ x 1))" (sanitize-code "(defn foo [x] (+ x 1))"))))
            (it "map literal"           (expect (= "{:a 1 :b 2}" (sanitize-code "{:a 1 :b 2}"))))
            (it "vector"                (expect (= "[1 2 3]" (sanitize-code "[1 2 3]"))))
            (it "empty string"          (expect (= "" (sanitize-code ""))))
            (it "whitespace only"       (expect (= "" (sanitize-code "   ")))))

  (describe "string matching — strips extra delimiters"
            (it "extra )"               (expect (= "(+ 1 2)" (sanitize-code "(+ 1 2))"))))
            (it "extra ))"              (expect (= "(+ 1 2)" (sanitize-code "(+ 1 2)))"))))
            (it "extra }"               (expect (= "{:a 1}" (sanitize-code "{:a 1}}"))))
            (it "extra }}"              (expect (= "{:a {:b 1}}" (sanitize-code "{:a {:b 1}}}}"))))
            (it "extra ]"               (expect (= "[1 2 3]" (sanitize-code "[1 2 3]]"))))
            (it "extra ]]"              (expect (= "[[1] [2]]" (sanitize-code "[[1] [2]]]"))))
            (it "mixed })"              (expect (= code-final-simple (sanitize-code (str code-final-simple "})")))))
            (it "whitespace before )"   (expect (= "(+ 1 2)" (sanitize-code "(+ 1 2) )"))))
            (it "only closers"          (expect (= "" (sanitize-code "}})")))))

  (describe "string matching — preserves valid"
            (it "balanced nested"       (expect (= "(let [x {:a [1 2]}] x)" (sanitize-code "(let [x {:a [1 2]}] x)"))))
            (it "deeply nested"         (expect (= "(a (b (c (d))))" (sanitize-code "(a (b (c (d))))"))))
            (it "multi-form"            (expect (= "(def x 1) (def y 2)" (sanitize-code "(def x 1) (def y 2)"))))
            (it "multi-form extra )"    (expect (= "(def a 1) (def b 2)" (sanitize-code "(def a 1) (def b 2))")))))

  (describe "eval — simple expressions with extra delimiters"
            (it "math extra )"          (expect (= 3 (eval-sanitized "(+ 1 2))"))))
            (it "nested math extra ))"  (expect (= 6 (eval-sanitized "(+ 1 (+ 2 3)))"))))
            (it "map extra }"           (expect (= {:a 1 :b 2} (eval-sanitized "{:a 1 :b 2}}"))))
            (it "vector extra ]"        (expect (= [1 2 3] (eval-sanitized "[1 2 3]]"))))
            (it "valid math"            (expect (= 42 (eval-sanitized "(+ 40 2)"))))
            (it "string with delims"    (expect (= "hi (there) {}" (eval-sanitized "(str \"hi (there) {}\")")))))

  (describe "eval — FINAL patterns with extra delimiters"
            (it "simple FINAL extra })"
                (let [result (eval-sanitized (str code-final-simple "})"))]
                  (expect (true? (:rlm/final result)))
                  (expect (= ["hello"] (get-in result [:answer :answer])))))
            (it "FINAL with learn extra })"
                (let [result (eval-sanitized (str code-final-learn "})"))]
                  (expect (true? (:rlm/final result)))))
            (it "FINAL multi-part extra })}"
                (let [result (eval-sanitized (str code-final-multi "})}"))]
                  (expect (true? (:rlm/final result)))))
            (it "valid FINAL no extras"
                (let [result (eval-sanitized code-final-simple)]
                  (expect (true? (:rlm/final result))))))

  (describe "eval — ctx-add! and list-dir"
            (it "ctx-add! extra )"
                (expect (= "Added: hello" (eval-sanitized "(ctx-add! \"hello\"))"))))
            (it "list-dir valid"
                (let [result (eval-sanitized code-list-dir)]
                  (expect (= 1 (:total result)))))
            (it "def + list-dir extra )"
                (let [result (eval-sanitized (str code-def-data ")"))]
                  (expect (nil? (:error result))))))

  (describe "eval — complex multi-form"
            (it "do block with FINAL extra )}"
                (let [result (eval-sanitized (str code-do-final "})"))]
                  (expect (true? (:rlm/final result)))))
            (it "let with count"
                (expect (= 1 (eval-sanitized code-let-count))))
            (it "let with count extra )"
                (expect (= 1 (eval-sanitized (str code-let-count ")")))))
            (it "multi-def pipeline"
                (let [code "(do (def a (+ 1 2)) (def b (* a 3)) (FINAL {:answer [(str b)]}))"
                      result (eval-sanitized (str code ")"))]
                  (expect (true? (:rlm/final result)))))
            (it "nested let-if-do"
                (let [code "(let [x 5] (if (> x 3) (do (def result (* x 2)) result) 0))"
                      result (eval-sanitized (str code ")"))]
                  (expect (= 10 result))))
            (it "reduce over vector extra )"
                (expect (= 15 (eval-sanitized "(reduce + 0 [1 2 3 4 5]))"))))
            (it "nested maps in FINAL extra })"
                (let [code (str "(FINAL {:answer [" (q "done") "] :learn [{:insight " (q "a") " :tags [" (q "b") " " (q "c") "]}]})")
                      result (eval-sanitized (str code "})"))]
                  (expect (true? (:rlm/final result)))))))
