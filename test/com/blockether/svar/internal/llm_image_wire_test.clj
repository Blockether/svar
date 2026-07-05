(ns com.blockether.svar.internal.llm-image-wire-test
  "Regression: canonical `image_url` blocks must be translated to the
   Anthropic native `image` block on the `:anthropic` wire.

   `(user text (image b64 mime))` builds OpenAI-shaped
   `{:type \"image_url\" :image_url {:url \"data:...\"}}` blocks.
   `anthropic-block` previously passed them through UNCHANGED and the
   Anthropic Messages API 400s on the unknown type - multimodal user
   messages broke every `:anthropic` api-style call while working fine
   on openai-compatible-chat / responses."
  (:require
   [com.blockether.svar.internal.llm :as sut]
   [lazytest.core :refer [defdescribe describe expect it]]))

(def ^:private build-anthropic @#'sut/build-anthropic-request-body)
(def ^:private image-url-block->anthropic @#'sut/image-url-block->anthropic)

(def ^:private b64 "aGVsbG8=")

(defdescribe image-url-block->anthropic-test
  (describe "data URIs"
    (it "unpacks a base64 data URI into a base64 source"
      (expect (= {:type "image"
                  :source {:type "base64" :media_type "image/png" :data b64}}
                (image-url-block->anthropic
                  {:type "image_url"
                   :image_url {:url (str "data:image/png;base64," b64)}}))))

    (it "carries the declared media type verbatim"
      (expect (= "image/jpeg"
                (get-in (image-url-block->anthropic
                          {:type "image_url"
                           :image_url {:url (str "data:image/jpeg;base64," b64)}})
                  [:source :media_type])))))

  (describe "http(s) URLs"
    (it "wraps a plain URL in a url source"
      (expect (= {:type "image" :source {:type "url" :url "https://x.test/a.png"}}
                (image-url-block->anthropic
                  {:type "image_url"
                   :image_url {:url "https://x.test/a.png"}}))))))

(defdescribe anthropic-request-body-image-test
  (it "emits native image blocks for a multimodal user message"
    (let [body   (build-anthropic
                   [(sut/user "what is on this screenshot?"
                      (sut/image b64 "image/png"))]
                   "claude-test" {})
          blocks (-> body :messages first :content)]
      (expect (vector? blocks))
      (expect (= ["image" "text"] (mapv :type blocks)))
      (expect (= {:type "base64" :media_type "image/png" :data b64}
                (:source (first blocks))))
      (expect (nil? (some #(= "image_url" (:type %)) blocks)))))

  (it "leaves text-only user messages collapsed to a plain string"
    (let [body (build-anthropic [(sut/user "hi")] "claude-test" {})]
      (expect (= "hi" (-> body :messages first :content))))))
