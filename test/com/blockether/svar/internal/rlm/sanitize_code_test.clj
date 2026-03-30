(ns com.blockether.svar.internal.rlm.sanitize-code-test
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.rlm.core :as core]))

;; Access the private function
(def sanitize-code #'core/sanitize-code)

(defdescribe sanitize-code-test
  (describe "valid code — no changes"
            (it "simple expression"
                (expect (= "(+ 1 2)" (sanitize-code "(+ 1 2)"))))
            (it "nested parens"
                (expect (= "(defn foo [x] (+ x 1))" (sanitize-code "(defn foo [x] (+ x 1))"))))
            (it "map literal"
                (expect (= "{:a 1 :b 2}" (sanitize-code "{:a 1 :b 2}"))))
            (it "vector"
                (expect (= "[1 2 3]" (sanitize-code "[1 2 3]"))))
            (it "nested structures"
                (expect (= "(FINAL {:answer [\"hello\"]})" (sanitize-code "(FINAL {:answer [\"hello\"]})"))))
            (it "empty string"
                (expect (= "" (sanitize-code ""))))
            (it "whitespace only"
                (expect (= "" (sanitize-code "   ")))))

  (describe "extra closing paren"
            (it "single extra )"
                (expect (= "(+ 1 2)" (sanitize-code "(+ 1 2))"))))
            (it "double extra ))"
                (expect (= "(+ 1 2)" (sanitize-code "(+ 1 2)))"))))
            (it "extra ) on nested"
                (expect (= "(defn foo [x] (+ x 1))" (sanitize-code "(defn foo [x] (+ x 1)))")))))

  (describe "extra closing brace"
            (it "single extra }"
                (expect (= "{:a 1}" (sanitize-code "{:a 1}}"))))
            (it "extra } on FINAL"
                (expect (= "(FINAL {:answer [\"hi\"]})" (sanitize-code "(FINAL {:answer [\"hi\"]})}") )))
            (it "double extra }}"
                (expect (= "{:a {:b 1}}" (sanitize-code "{:a {:b 1}}}}")))))

  (describe "extra closing bracket"
            (it "single extra ]"
                (expect (= "[1 2 3]" (sanitize-code "[1 2 3]]"))))
            (it "extra ] on nested"
                (expect (= "[[1] [2]]" (sanitize-code "[[1] [2]]]")))))

  (describe "mixed extra delimiters"
            (it "extra } then )"
                (expect (= "(FINAL {:answer [\"hello\"]})" (sanitize-code "(FINAL {:answer [\"hello\"]})})"))))
            (it "the actual LLM bug pattern — extra })"
                (expect (= "(FINAL {:answer [\"test\"] :learn [{:insight \"x\" :tags [\"y\"]}] :reasoning \"z\"})"
                           (sanitize-code "(FINAL {:answer [\"test\"] :learn [{:insight \"x\" :tags [\"y\"]}] :reasoning \"z\"})})"))))
            (it "extra ]) on vector in parens"
                (expect (= "(ctx-add! [1 2 3])" (sanitize-code "(ctx-add! [1 2 3])])")))))

  (describe "does NOT strip valid closing delimiters"
            (it "balanced nested map"
                (expect (= "(let [x {:a [1 2]}] x)" (sanitize-code "(let [x {:a [1 2]}] x)"))))
            (it "deeply nested"
                (expect (= "(a (b (c (d))))" (sanitize-code "(a (b (c (d))))"))))
            (it "map as last arg"
                (expect (= "(fn {:keys [a b]})" (sanitize-code "(fn {:keys [a b]})"))))
            (it "multiple top-level forms are left alone"
                (expect (= "(def x 1)" (sanitize-code "(def x 1)")))))

  (describe "real-world FINAL patterns"
            (it "simple FINAL"
                (expect (= "(FINAL {:answer [\"Hello!\"]})" (sanitize-code "(FINAL {:answer [\"Hello!\"]})"))))
            (it "FINAL with learn"
                (expect (= "(FINAL {:answer [\"result\"] :learn [{:insight \"i\" :tags [\"t\"]}]})"
                           (sanitize-code "(FINAL {:answer [\"result\"] :learn [{:insight \"i\" :tags [\"t\"]}]})"))))
            (it "FINAL with extra })"
                (expect (= "(FINAL {:answer [\"result\"] :learn [{:insight \"i\" :tags [\"t\"]}]})"
                           (sanitize-code "(FINAL {:answer [\"result\"] :learn [{:insight \"i\" :tags [\"t\"]}]})})"))))
            (it "FINAL with extra }})"
                (expect (= "(FINAL {:answer [\"result\"] :learn [{:insight \"i\" :tags [\"t\"]}]})"
                           (sanitize-code "(FINAL {:answer [\"result\"] :learn [{:insight \"i\" :tags [\"t\"]}]})}})"))))
            (it "FINAL with multiline-like long answer"
                (let [code "(FINAL {:answer [\"Part 1 of answer\" \"Part 2 with special chars: /path/to/file\" \"Part 3 with \\\"quotes\\\"\"] :reasoning \"Found in section 7\"})"]
                  (expect (= code (sanitize-code code)))))
            (it "FINAL with multiline-like long answer and extra delimiters"
                (let [clean "(FINAL {:answer [\"Part 1\" \"Part 2\"] :reasoning \"done\"})"
                      dirty "(FINAL {:answer [\"Part 1\" \"Part 2\"] :reasoning \"done\"})})"]
                  (expect (= clean (sanitize-code dirty))))))

  (describe "multi-form code"
            (it "multiple defs"
                (expect (= "(def x 1) (def y 2)" (sanitize-code "(def x 1) (def y 2)"))))
            (it "def then if"
                (expect (= "(def x 1) (if true (do x) nil)" (sanitize-code "(def x 1) (if true (do x) nil)"))))
            (it "defn with body"
                (expect (= "(defn foo [x] (let [y (* x 2)] (+ y 1)))" (sanitize-code "(defn foo [x] (let [y (* x 2)] (+ y 1)))"))))
            (it "multi-form with extra )"
                (expect (= "(def a 1) (def b 2)" (sanitize-code "(def a 1) (def b 2))"))))
            (it "complex nested with extra })"
                (expect (= "(def data {:a [1 2]}) (FINAL {:answer [data]})"
                           (sanitize-code "(def data {:a [1 2]}) (FINAL {:answer [data]})})")))))

  (describe "edge cases"
            (it "only closing delimiters"
                (expect (= "" (sanitize-code "}})"))))
            (it "string with parens inside — counts correctly"
                (expect (= "(str \"(hello)\")" (sanitize-code "(str \"(hello)\")"))))
            (it "whitespace around extra delimiter"
                (expect (= "(+ 1 2)" (sanitize-code "(+ 1 2) )"))))))
