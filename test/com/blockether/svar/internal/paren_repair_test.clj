(ns com.blockether.svar.internal.paren-repair-test
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.paren-repair :as sut]))

(defdescribe repair-code-test

  (describe "regression - to-roman extra bracket"
    (it "removes extra ] in nested vector literal"
      (expect (= "(defn to-roman [n]\n  (let [pairs [[1000 \"M\"] [1 \"I\"]]]\n    (reduce + 0 pairs)))"
                (sut/repair-code "(defn to-roman [n]\n  (let [pairs [[1000 \"M\"] [1 \"I\"]]]]\n    (reduce + 0 pairs)))")))))

  (describe "no-op on valid code"
    (it "simple expression"
      (expect (= "(+ 1 2)" (sut/repair-code "(+ 1 2)"))))
    (it "empty string"
      (expect (= "" (sut/repair-code ""))))
    (it "multiple top-level forms"
      (expect (= "(def x 1) (def y 2)" (sut/repair-code "(def x 1) (def y 2)"))))
    (it "nested balanced"
      (expect (= "(foo [bar {:a 1}])" (sut/repair-code "(foo [bar {:a 1}])")))))

  (describe "paren-repair - missing closers"
    (it "unclosed single paren"
      (expect (= "(foo (bar))" (sut/repair-code "(foo (bar"))))
    (it "unclosed bracket"
      (expect (= "[1 2 3]" (sut/repair-code "[1 2 3"))))
    (it "unclosed nested map"
      (expect (= "{:a {:b 1}}" (sut/repair-code "{:a {:b 1"))))
    (it "missing close on defn"
      (expect (= "(defn f [x] (+ x 1))" (sut/repair-code "(defn f [x] (+ x 1)")))))

  (describe "paren-repair - extra closers"
    (it "extra trailing paren"
      (expect (= "(foo)" (sut/repair-code "(foo))"))))
    (it "extra trailing bracket"
      (expect (= "[[1 2]]" (sut/repair-code "[[1 2]]]"))))
    (it "nested extra bracket"
      (expect (= "[[1 2]]" (sut/repair-code "[[1 2]]]")))))

  (describe "delimiter-repair - mismatched closers"
    (it "paren closed by bracket"
      (expect (= "(foo)" (sut/repair-code "(foo]"))))
    (it "bracket closed by paren"
      (expect (= "[foo]" (sut/repair-code "[foo)"))))
    (it "nested mismatch - inner"
      (expect (= "(foo [bar])" (sut/repair-code "(foo [bar)]")))))

  (describe "string literals are respected"
    (it "delimiters inside string not touched"
      (expect (= "\"(]\"" (sut/repair-code "\"(]\""))))
    (it "string with parens stays valid"
      (expect (= "(str \"hi (there)\")" (sut/repair-code "(str \"hi (there)\")"))))
    (it "escaped quote inside string"
      (expect (= "\"a\\\"b\"" (sut/repair-code "\"a\\\"b\"")))))

  (describe "character literals are respected"
    (it "char literal open paren not counted"
      (expect (= "(def c \\()" (sut/repair-code "(def c \\()"))))
    (it "char literal close paren not counted"
      (expect (= "(def c \\))" (sut/repair-code "(def c \\))")))))

  (describe "line comments are respected"
    (it "open paren in comment not counted"
      (expect (= ";; (foo\n(bar)" (sut/repair-code ";; (foo\n(bar)"))))
    (it "unclosed paren in comment does not cause repair"
      (expect (= ";; (foo" (sut/repair-code ";; (foo")))))

  (describe "parse-error? predicate"
    (it "matches Unmatched delimiter"
      (expect (true? (sut/parse-error? "Unmatched delimiter: ], expected: ) to match ( at [2 3]"))))
    (it "matches EOF while reading"
      (expect (true? (sut/parse-error? "EOF while reading"))))
    (it "matches Unexpected EOF"
      (expect (true? (sut/parse-error? "Unexpected EOF"))))
    (it "matches Invalid token"
      (expect (true? (sut/parse-error? "Invalid token: ##NaN"))))
    (it "does not match Unable to resolve symbol"
      (expect (false? (sut/parse-error? "Unable to resolve symbol: foo in this context"))))
    (it "does not match ClassCastException"
      (expect (false? (sut/parse-error? "ClassCastException: Long cannot be cast to String"))))
    (it "does not match NullPointerException"
      (expect (false? (sut/parse-error? "NullPointerException"))))
    (it "returns false for nil"
      (expect (false? (sut/parse-error? nil))))))
