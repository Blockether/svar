(ns com.blockether.svar.internal.llm-content-verbatim-test
  "Regression: a multi-part assistant message body must be reassembled
   VERBATIM.

   Providers may deliver the assistant text as an array of content parts
   (chunks of one message body, or the full text carried only in the terminal
   `response.completed` output). svar previously joined those parts with
   `\"\\n\"` (`content-blocks-text`) and dropped whitespace-only parts
   (`content-part-text`). For lenient code mode - where the message body IS the
   program - that corrupted source two ways:

     1. every part landed on its own line: `header_src = cat(\"x\")` arrived as
        `header_src\\n=\\ncat(\"x\")` (the reported bug);
     2. whitespace-only parts vanished, gluing tokens and losing indentation:
        `def f():` + `\\n    ` + `return 1` collapsed to `def f():return 1`.

   Parts of one message are concatenated with `\"\"`; the model's own newlines
   live inside the parts."
  (:require
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.llm :as sut]))

(def ^:private content-part-text @#'sut/content-part-text)
(def ^:private content-blocks-text @#'sut/content-blocks-text)
(def ^:private response-output-text @#'sut/response-output-text)
(def ^:private extract-stream-delta @#'sut/extract-stream-delta)

(defdescribe content-part-text-test
  (describe "string parts"
    (it "keeps whitespace-only strings verbatim (indent / blank lines survive)"
      (expect (= "\n    " (content-part-text "\n    ")))
      (expect (= "  " (content-part-text "  "))))

    (it "keeps ordinary strings unchanged"
      (expect (= "return 1" (content-part-text "return 1")))))

  (describe "sequential parts"
    (it "concatenates nested parts with no separator, preserving whitespace"
      (expect (= "def f():\n    return 1"
                (content-part-text [{:type "text" :text "def f():"}
                                    {:type "text" :text "\n    "}
                                    {:type "text" :text "return 1"}]))))))

(defdescribe content-blocks-text-test
  (it "concatenates message body parts verbatim (no injected newlines)"
    (expect (= "header_src = cat(\"x\")"
              (content-blocks-text [{:type "text" :text "header_src = "}
                                    {:type "text" :text "cat(\"x\")"}]))))

  (it "preserves whitespace-only parts between tokens"
    (expect (= "a = 1\n\nb = 2"
              (content-blocks-text [{:type "text" :text "a = 1"}
                                    {:type "text" :text "\n\n"}
                                    {:type "text" :text "b = 2"}]))))

  (it "returns nil for an all-blank body"
    (expect (nil? (content-blocks-text [{:type "text" :text "   "}
                                        {:type "text" :text ""}])))))

(defdescribe response-output-text-test
  (it "reassembles a message whose body is split across parts (response.completed path)"
    (expect (= "x = 1"
              (response-output-text
                {:output [{:type "message"
                           :content [{:type "output_text" :text "x = "}
                                     {:type "output_text" :text "1"}]}]})))))

(defdescribe stream-delta-array-content-test
  (it "concatenates array-shaped chat delta content verbatim"
    (expect (= "foo = bar(baz)"
              (:content-delta
               (extract-stream-delta
                 {:choices [{:delta {:content [{:type "text" :text "foo = "}
                                               {:type "text" :text "bar(baz)"}]}}]}))))))
