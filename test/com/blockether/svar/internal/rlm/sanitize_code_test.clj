(ns com.blockether.svar.internal.rlm.sanitize-code-test
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [sci.core :as sci]
   [com.blockether.svar.internal.paren-repair :as paren-repair]))

(def sanitize-code paren-repair/repair-code)

(defn- eval-sanitized
  "Sanitize code then eval in SCI. Returns result or {:error msg}."
  [code]
  (let [sanitized (sanitize-code code)
        bindings {'+ + '- - '* * '/ / 'str str 'count count
                                           'map map 'filter filter 'reduce reduce 'mapv mapv
                                           'conj conj 'assoc assoc 'get get 'merge merge
                                           'inc inc 'dec dec 'vec vec 'into into
                                           'println println 'pr-str pr-str 'keyword keyword
                                           'first first 'rest rest 'seq seq 'nil? nil?
                                           'last last 'butlast butlast 'take take 'drop drop
                                           '> > '< < '= = 'not not 'true? true? 'some some
                                           'keys keys 'vals vals 'select-keys select-keys
                                           'FINAL (fn
                                                    ([answer] {:rlm/final true :answer answer})
                                                    ([answer opts] (merge {:rlm/final true :answer answer} opts)))
                                           'ctx-add! (fn [text] (str "Added: " text))
                                           'ctx-remove! (fn [idx] (str "Removed: " idx))
                                           'learn! (fn [text & [_pri]] (str "Learned: " text))
                                           'list-dir (fn [& _] {:path "." :entries [{:name "a.clj" :type "file"} {:name "b.clj" :type "file"}] :total 2})
                                           'read-file (fn [& _] "file contents")
                                           'shell-exec (fn [& _] {:exit-code 0 :stdout "hello" :stderr "" :timed-out false})}
        ctx (sci/init {:namespaces {'user bindings 'sandbox bindings}})]
    (try
      (sci/eval-string* ctx sanitized)
      (catch Exception e
        {:error (ex-message e)}))))

(defn- q [s] (str "\"" s "\""))

;; Reusable code fragments
(def ^:private code-list-dir (str "(list-dir " (q ".") ")"))
(def ^:private code-final-simple (str "(FINAL {:answer [" (q "hello") "]})"))
(def ^:private code-final-learn (str "(FINAL {:answer [" (q "result") "] :learn [{:insight " (q "x") " :tags [" (q "bug") "]}]})"))
(def ^:private code-final-multi (str "(FINAL {:answer [" (q "Part 1") " " (q "Part 2") "] :reasoning " (q "done") "})"))
(def ^:private code-do-final (str "(do (def x " code-list-dir ") (FINAL {:answer [(str (:total x) " (q " files") ")]}))"))
(def ^:private code-let-count (str "(let [files (:entries " code-list-dir ")] (count files))"))

(defdescribe sanitize-code-test

  (describe "eval — valid code runs correctly"
    (it "simple math"           (expect (= 3 (eval-sanitized "(+ 1 2)"))))
    (it "nested math"           (expect (= 11 (eval-sanitized "(+ 1 (* 2 (+ 3 2)))"))))
    (it "map literal"           (expect (= {:a 1 :b 2} (eval-sanitized "{:a 1 :b 2}"))))
    (it "vector"                (expect (= [1 2 3] (eval-sanitized "[1 2 3]"))))
    (it "nested structures"     (expect (= {:a [1 {:b 2}]} (eval-sanitized "{:a [1 {:b 2}]}"))))
    (it "string with delims"    (expect (= "hi (there) {}" (eval-sanitized "(str \"hi (there) {}\")"))))
    (it "FINAL valid"           (expect (true? (:rlm/final (eval-sanitized code-final-simple)))))
    (it "list-dir valid"        (expect (= 2 (:total (eval-sanitized code-list-dir)))))
    (it "let valid"             (expect (= 2 (eval-sanitized code-let-count)))))

  (describe "eval — extra single closing delimiter"
    (it "extra )"               (expect (= 3 (eval-sanitized "(+ 1 2))"))))
    (it "extra }"               (expect (= {:a 1} (eval-sanitized "{:a 1}}"))))
    (it "extra ]"               (expect (= [1 2] (eval-sanitized "[1 2]]"))))
    (it "FINAL extra )"         (expect (true? (:rlm/final (eval-sanitized (str code-final-simple ")"))))))
    (it "FINAL extra }"         (expect (true? (:rlm/final (eval-sanitized (str code-final-simple "}"))))))
    (it "ctx-add! extra )"      (expect (= "Added: hi" (eval-sanitized "(ctx-add! \"hi\"))")))))

  (describe "eval — extra double closing delimiters"
    (it "extra ))"              (expect (= 3 (eval-sanitized "(+ 1 2)))"))))
    (it "extra }}"              (expect (= {:a {:b 1}} (eval-sanitized "{:a {:b 1}}}}"))))
    (it "extra ]]"              (expect (= [[1] [2]] (eval-sanitized "[[1] [2]]]"))))
    (it "FINAL extra })"        (expect (true? (:rlm/final (eval-sanitized (str code-final-simple "})"))))))
    (it "FINAL extra })}"       (expect (true? (:rlm/final (eval-sanitized (str code-final-simple "})}")))))))

  (describe "eval — mixed extra delimiters"
    (it "extra })"              (expect (true? (:rlm/final (eval-sanitized (str code-final-simple "})"))))))
    (it "extra )}"              (expect (true? (:rlm/final (eval-sanitized (str code-final-simple ")}"))))))
    (it "learn FINAL extra })"  (expect (true? (:rlm/final (eval-sanitized (str code-final-learn "})"))))))
    (it "multi FINAL extra })}" (expect (true? (:rlm/final (eval-sanitized (str code-final-multi "})}"))))))
    (it "whitespace + extra )"  (expect (= 3 (eval-sanitized "(+ 1 2) )"))))
    (it "only closers"          (expect (= "" (sanitize-code "}})")))))

  (describe "eval — complex multi-form with extra delimiters"
    (it "do block FINAL extra })"
      (expect (true? (:rlm/final (eval-sanitized (str code-do-final "})"))))))
    (it "let count extra )"
      (expect (= 2 (eval-sanitized (str code-let-count ")")))))
    (it "multi-def pipeline extra )"
      (let [code "(do (def a (+ 1 2)) (def b (* a 3)) (FINAL {:answer [(str b)]}))"
            result (eval-sanitized (str code ")"))]
        (expect (true? (:rlm/final result)))))
    (it "nested let-if-do extra )"
      (let [code "(let [x 5] (if (> x 3) (do (def r (* x 2)) r) 0))"
            result (eval-sanitized (str code ")"))]
        (expect (= 10 result))))
    (it "reduce extra )"
      (expect (= 15 (eval-sanitized "(reduce + 0 [1 2 3 4 5]))"))))
    (it "4 sequential defs extra )"
      (let [code "(do (def a 1) (def b 2) (def c (+ a b)) c)"
            result (eval-sanitized (str code ")"))]
        (expect (= 3 result))))
    (it "mapv + filter pipeline extra )"
      (let [code (str "(let [entries (:entries " code-list-dir ")] (mapv :name entries))")
            result (eval-sanitized (str code ")"))]
        (expect (= ["a.clj" "b.clj"] result))))
    (it "shell-exec in do extra )"
      (let [code "(do (def out (shell-exec \"ls\")) (:stdout out))"
            result (eval-sanitized (str code ")"))]
        (expect (= "hello" result)))))

  (describe "eval — deeply nested FINAL patterns (real LLM output)"
    (it "FINAL with nested learn + sources extra })"
      (let [code (str "(FINAL {:answer [" (q "Analysis done") "]"
                   " :learn [{:insight " (q "Bug found") " :tags [" (q "bug") " " (q "parser") "]}]"
                   " :sources [{:source " (q "core.clj") " :type :file}]"
                   " :reasoning " (q "Checked 3 files") "})")
            result (eval-sanitized (str code "})"))]
        (expect (true? (:rlm/final result)))))
    (it "FINAL with 3 answer parts extra }})"
      (let [code (str "(FINAL {:answer [" (q "Intro") " " (q "Body with details") " " (q "Conclusion") "]})")
            result (eval-sanitized (str code "}})"))]
        (expect (true? (:rlm/final result)))
        (expect (= ["Intro" "Body with details" "Conclusion"]
                  (get-in result [:answer :answer])))))
    (it "ctx-add then FINAL in do extra })"
      (let [code (str "(do (ctx-add! " (q "saved findings") ") (FINAL {:answer [" (q "done") "]}))")
            result (eval-sanitized (str code "})"))]
        (expect (true? (:rlm/final result))))))

  (describe "eval — missing closing delimiters (auto-added)"
    (it "missing )"             (expect (= 3 (eval-sanitized "(+ 1 2"))))
    (it "missing ))"            (expect (= 6 (eval-sanitized "(+ 1 (+ 2 3"))))
    (it "missing }"             (expect (= {:a 1} (eval-sanitized "{:a 1"))))
    (it "missing ]"             (expect (= [1 2 3] (eval-sanitized "[1 2 3"))))
    (it "missing ] and }"       (expect (= {:a [1 2]} (eval-sanitized "{:a [1 2"))))
    (it "missing ) on FINAL"
      (expect (true? (:rlm/final (eval-sanitized (str "(FINAL {:answer [" (q "hi") "]}"))))))
    (it "missing ]) on FINAL"
      (expect (true? (:rlm/final (eval-sanitized (str "(FINAL {:answer [" (q "hi")))))))
    (it "missing ) on nested let"
      (expect (= 3 (eval-sanitized "(let [x 1 y 2] (+ x y"))))
    (it "missing ) on do block"
      (let [result (eval-sanitized "(do (def x 5) x")]
        (expect (= 5 result))))
    (it "missing }} on nested map"
      (expect (= {:a {:b {:c 1}}} (eval-sanitized "{:a {:b {:c 1"))))
    (it "missing ]] on nested vector"
      (expect (= [[1] [2 3]] (eval-sanitized "[[1] [2 3"))))
    (it "missing ) on ctx-add!"
      (expect (= "Added: test" (eval-sanitized "(ctx-add! \"test\""))))
    (it "missing }) on FINAL with learn"
      (let [code (str "(FINAL {:answer [" (q "done") "] :learn [{:insight " (q "x") " :tags [" (q "t") "]")
            result (eval-sanitized code)]
        (expect (true? (:rlm/final result))))))

  (describe "eval — preserves valid code (no false stripping)"
    (it "balanced nested map"
      (expect (= {:a {:b [1 2]}} (eval-sanitized "{:a {:b [1 2]}}"))))
    (it "deeply nested parens"
      (expect (= 4 (eval-sanitized "(+ 1 (+ 1 (+ 1 1)))"))))
    (it "multi-form stays valid"
      (let [code "(do (def x 1) (def y 2) (+ x y))"
            result (eval-sanitized code)]
        (expect (= 3 result))))
    (it "vector of maps"
      (expect (= [{:a 1} {:b 2}] (eval-sanitized "[{:a 1} {:b 2}]"))))
    (it "map of vectors"
      (expect (= {:a [1 2] :b [3 4]} (eval-sanitized "{:a [1 2] :b [3 4]}"))))))
