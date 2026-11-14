(ns com.blockether.svar.internal.server-fallback-test
  "Anthropic server-side refusal fallback (`fallbacks` + beta
   `server-side-fallback-2026-07-01`) across the public HTTP/SSE boundary."
  (:require [com.blockether.svar.core :as svar]
            [com.blockether.svar.internal.llm :as llm]
            [com.blockether.svar.internal.router :as router]
            [lazytest.core :refer [defdescribe describe it expect]]
            [charred.api :as json]
            [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

(def ^:private fallback-wire
  {"type" "fallback"
   "from" {"model" "claude-fable-5-2"}
   "to" {"model" "claude-opus-4-8"}
   "trigger" {"type" "refusal" "category" "cyber"}})

(def ^:private served-usage
  {"input_tokens" 412
   "output_tokens" 264
   "iterations"
   [{"type" "message" "model" "claude-fable-5-2" "input_tokens" 535 "output_tokens" 0}
    {"type" "fallback_message" "model" "claude-opus-4-8" "input_tokens" 412 "output_tokens" 264}]})

(def ^:private served-message
  "A reply that the fallback model served after Fable declined before any output."
  {"id" "msg_1"
   "type" "message"
   "role" "assistant"
   "model" "claude-opus-4-8"
   "content" [fallback-wire {"type" "thinking" "thinking" "plan" "signature" "sig-opus"}
              {"type" "text" "text" "answer"}]
   "stop_reason" "end_turn"
   "stop_details" nil
   "usage" served-usage})

(defn- plain-message
  [text]
  {"id" "msg_2"
   "type" "message"
   "role" "assistant"
   "model" "claude-fable-5-2"
   "content" [{"type" "text" "text" text}]
   "stop_reason" "end_turn"
   "usage" {"input_tokens" 5 "output_tokens" 1}})

(defn- sse
  [events]
  (apply str
    (map #(str "event: " (get % "type") "\ndata: " (json/write-json-str %) "\n\n") events)))

(def ^:private served-stream
  (sse
    [{"type" "message_start"
      "message" (assoc served-message
                  "content" []
                  "stop_reason" nil
                  "usage" {"input_tokens" 412 "output_tokens" 0})}
     {"type" "content_block_start" "index" 0 "content_block" fallback-wire}
     {"type" "content_block_stop" "index" 0}
     {"type" "content_block_start" "index" 1 "content_block" {"type" "thinking" "thinking" ""}}
     {"type" "content_block_delta" "index" 1 "delta" {"type" "thinking_delta" "thinking" "plan"}}
     {"type" "content_block_delta"
      "index" 1
      "delta" {"type" "signature_delta" "signature" "sig-opus"}}
     {"type" "content_block_stop" "index" 1}
     {"type" "content_block_start" "index" 2 "content_block" {"type" "text" "text" ""}}
     {"type" "content_block_delta" "index" 2 "delta" {"type" "text_delta" "text" "answer"}}
     {"type" "content_block_stop" "index" 2}
     {"type" "message_delta" "delta" {"stop_reason" "end_turn"} "usage" served-usage}
     {"type" "message_stop"}]))

(defn- fixture
  "Run `test-fn` with a router on a local fake Anthropic server. `respond` gets the
   request number and the parsed body and returns `{:json m}` or `{:sse s}`."
  [respond test-fn]
  (let [seen
        (atom [])

        server
        (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]

    (.createContext
      server
      "/v1/messages"
      (reify
        HttpHandler
          (handle [_ exchange]
            (let [^HttpExchange exchange
                  exchange

                  request
                  (json/read-json (slurp (.getRequestBody exchange)))

                  _
                  (swap! seen conj {:body request :headers (into {} (.getRequestHeaders exchange))})

                  {:keys [sse] :as reply}
                  (respond (count @seen) request)

                  body
                  (.getBytes ^String (or sse (json/write-json-str (:json reply))) "UTF-8")]

              (.set (.getResponseHeaders exchange)
                    "content-type"
                    (if sse "text/event-stream" "application/json"))
              (.sendResponseHeaders exchange 200 (long (alength body)))
              (with-open [out (.getResponseBody exchange)]
                (.write out body))
              (.close exchange)))))
    (.start server)
    (try (test-fn (svar/make-router
                    [{:id :anthropic
                      :api-key "test-key"
                      :base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1")
                      :api-style :anthropic
                      :models [{:name "claude-fable-5-2" :context 200000 :input 1.0 :output 1.0}
                               {:name "claude-haiku-5-5" :context 200000 :input 1.0 :output 1.0}]}])
                  seen)
         (finally (.stop server 0)))))

(defn- ask
  [router model opts]
  (svar/ask-code! router
                  (merge {:routing {:provider :anthropic :model model}
                          :messages [{:role "user" :content "hi"}]
                          :max-tokens 64}
                         opts)))

(defn- betas
  [seen n]
  (set (mapcat #(str/split % #",")
               (some (fn [[k v]]
                       (when (= "anthropic-beta" (str/lower-case k)) v))
                     (:headers (nth @seen n))))))

(defn- assistant-wire
  "Content of the replayed assistant message in request `n`."
  [seen n]
  (some #(when (= "assistant" (get % "role")) (get % "content"))
        (get-in (nth @seen n) [:body "messages"])))

(defdescribe
  server-fallback-request-test
  ;; User request: svar must support Anthropic server-side fallback (screenshot, vis cyber refusal).
  (it "sends fallbacks and the beta header to a model that supports them"
      (fixture (fn [_ _]
                 {:json (plain-message "ok")})
               (fn [router seen]
                 (ask router "claude-fable-5-2" {:server-fallbacks "default"})
                 (expect (= "default" (get-in (nth @seen 0) [:body "fallbacks"])))
                 (expect (contains? (betas seen 0) "server-side-fallback-2026-07-01")))))
  (it "sends neither to Claude Haiku 5.5, which has no server-side fallback"
      (fixture (fn [_ _]
                 {:json (plain-message "ok")})
               (fn [router seen]
                 (ask router "claude-haiku-5-5" {:server-fallbacks "default"})
                 (expect (not (contains? (get-in (nth @seen 0) [:body]) "fallbacks")))
                 (expect (not (contains? (betas seen 0) "server-side-fallback-2026-07-01"))))))
  (it "drops the requested model from a fallback list"
      (fixture
        (fn [_ _]
          {:json (plain-message "ok")})
        (fn [router seen]
          (ask router "claude-fable-5-2" {:server-fallbacks ["claude-fable-5-2" "claude-opus-4-8"]})
          (expect (= [{"model" "claude-opus-4-8"}] (get-in (nth @seen 0) [:body "fallbacks"])))))))

(defdescribe server-fallback-reply-test
             (describe
               "a reply that the fallback model served"
               (it "names the models of the handoff in a JSON reply"
                   (fixture (fn [_ _]
                              {:json served-message})
                            (fn [router _]
                              (let [r (ask router "claude-fable-5-2" {:server-fallbacks "default"})]
                                (expect (= "answer" (:content r)))
                                (expect (= {:from "claude-fable-5-2"
                                            :to "claude-opus-4-8"
                                            :category "cyber"
                                            :server-side? true}
                                           (:refusal-recovered r)))))))
               (it "names the models of the handoff in an SSE reply"
                   (fixture (fn [_ _]
                              {:sse served-stream})
                            (fn [router _]
                              (let [r (ask router
                                           "claude-fable-5-2"
                                           {:server-fallbacks "default"
                                            :on-chunk (fn [_])})]
                                (expect (= "answer" (:content r)))
                                (expect (= "claude-opus-4-8"
                                           (get-in r [:refusal-recovered :to])))))))))

(def ^:private mid-output-stream
  "Fable wrote a tool call, then declined; the fallback model wrote its own tool call."
  (sse
    [{"type" "message_start"
      "message" (assoc served-message
                  "model" "claude-fable-5-2"
                  "content" []
                  "stop_reason" nil
                  "usage" {"input_tokens" 412 "output_tokens" 0})}
     {"type" "content_block_start"
      "index" 0
      "content_block" {"type" "tool_use" "id" "toolu_declined" "name" "run" "input" {}}}
     {"type" "content_block_delta"
      "index" 0
      "delta" {"type" "input_json_delta" "partial_json" "{\"code\":\"a\"}"}}
     {"type" "content_block_stop" "index" 0}
     {"type" "content_block_start" "index" 1 "content_block" fallback-wire}
     {"type" "content_block_stop" "index" 1}
     {"type" "content_block_start"
      "index" 2
      "content_block" {"type" "tool_use" "id" "toolu_served" "name" "run" "input" {}}}
     {"type" "content_block_delta"
      "index" 2
      "delta" {"type" "input_json_delta" "partial_json" "{\"code\":\"b\"}"}}
     {"type" "content_block_stop" "index" 2}
     {"type" "message_delta" "delta" {"stop_reason" "tool_use"} "usage" served-usage}
     {"type" "message_stop"}]))

(defdescribe server-fallback-tool-call-test
             (it "returns only the tool call of the model that served the reply"
                 (fixture
                   (fn [_ _]
                     {:sse mid-output-stream})
                   (fn [router _]
                     (let [r (ask router
                                  "claude-fable-5-2"
                                  {:server-fallbacks "default"
                                   :on-chunk (fn [_])
                                   :tools [{:name "run"
                                            :description "Run code."
                                            :schema {:type "object"
                                                     :properties {:code {:type "string"}}}}]})]
                       (expect (= ["toolu_served"] (mapv :id (:tool-calls r))))
                       (expect (= ["fallback" "tool_use"]
                                  (mapv :type (get-in r [:assistant-message :content])))))))))

(defdescribe
  server-fallback-replay-test
  (it "echoes the fallback block in place and keeps the fallback model's signed thinking"
      (fixture (fn [n _]
                 (if (= 1 n) {:json served-message} {:json (plain-message "next")}))
               (fn [router seen]
                 (let [r (ask router "claude-fable-5-2" {:server-fallbacks "default"})]
                   (ask router
                        "claude-fable-5-2"
                        {:server-fallbacks "default"
                         :messages [{:role "user" :content "hi"} (:assistant-message r)
                                    {:role "user" :content "more"}]})
                   (expect (= ["fallback" "thinking" "text"]
                              (mapv #(get % "type") (assistant-wire seen 1))))
                   (expect (= "sig-opus" (get (second (assistant-wire seen 1)) "signature")))))))
  (it "drops the fallback block and its thinking when the next request has no fallbacks"
      (fixture (fn [n _]
                 (if (= 1 n) {:json served-message} {:json (plain-message "next")}))
               (fn [router seen]
                 (let [r (ask router "claude-fable-5-2" {:server-fallbacks "default"})]
                   (ask router
                        "claude-fable-5-2"
                        {:messages [{:role "user" :content "hi"} (:assistant-message r)
                                    {:role "user" :content "more"}]})
                   (expect (= "answer"
                              (let [c (assistant-wire seen 1)]
                                (if (string? c) c (get (first c) "text")))))))))
  (it "drops the declining model's thinking before a mid-output handoff"
      (let [sanitize
            @#'llm/sanitize-replayed-messages

            msg
            {:role "assistant"
             :content
             [{:type "thinking" :thinking "declined" :thinking-signature "sig-fable"}
              {:type "text" :text "partial"}
              {:type "fallback" :from {"model" "claude-fable-5-2"} :to {"model" "claude-opus-4-8"}}
              {:type "thinking" :thinking "plan" :thinking-signature "sig-opus"}
              {:type "text" :text "rest"}]}]

        (expect (= ["text" "fallback" "thinking" "text"]
                   (mapv :type (:content (first (sanitize [msg] nil false true))))))
        (expect (= ["text" "text"]
                   (mapv :type (:content (first (sanitize [msg] nil false false)))))))))

(defdescribe
  fallback-response-cost-test
  (doseq [stream?
          [false true]

          sticky?
          [false true]]

    (it
      (str "prices the serving model: stream=" stream? ", sticky=" sticky?)
      (let [usage
            (cond-> served-usage
              sticky?
              (assoc-in ["iterations" 1 "model"] nil))

            message
            (cond-> (assoc served-message "usage" usage)
              sticky?
              (assoc "content" [{"type" "text" "text" "answer"}]))

            events
            [{"type" "message_start"
              "message" (assoc message
                          "content" []
                          "stop_reason" nil)}
             {"type" "content_block_start"
              "index" 0
              "content_block" {"type" "text" "text" "answer"}}
             {"type" "content_block_stop" "index" 0}
             {"type" "message_delta" "delta" {"stop_reason" "end_turn"} "usage" usage}
             {"type" "message_stop"}]]

        (with-redefs [router/provider-model-pricing (fn [_ model]
                                                      (if (= "claude-opus-4-8" model)
                                                        {:input 1.0 :output 2.0}
                                                        {:input 10.0 :output 20.0}))]
          (fixture (fn [_ _]
                     (if stream? {:sse (if sticky? (sse events) served-stream)} {:json message}))
                   (fn [router _]
                     (let [r (ask router
                                  "claude-fable-5-2"
                                  (cond-> {:server-fallbacks "default"}
                                    stream?
                                    (assoc :on-chunk
                                      (fn [_]))))]
                       (expect (= "claude-fable-5-2" (:routed/model r)))
                       (expect (< (Math/abs (- 0.00094 (get-in r [:cost :total-cost]))) 1.0e-10))
                       (expect (= "claude-opus-4-8" (:response-model r)))
                       (expect (= "claude-opus-4-8" (get-in r [:refusal-recovered :to])))))))))))

(defdescribe
  nonempty-refusal-test
  (doseq [stream?
          [false true]

          tool?
          [false true]]

    (it
      (str "rejects refused output: stream=" stream? ", tool=" tool?)
      (let [details
            {"category" "cyber" "explanation" "declined"}

            message
            (cond-> (assoc served-message
                      "stop_reason" "refusal"
                      "stop_details" details)
              tool?
              (assoc "content"
                [{"type" "tool_use" "id" "refused-tool" "name" "run" "input" {"code" "partial"}}]))

            stream
            (-> (if tool? mid-output-stream served-stream)
                (str/replace
                  (if tool? "\"stop_reason\":\"tool_use\"" "\"stop_reason\":\"end_turn\"")
                  (str "\"stop_reason\":\"refusal\",\"stop_details\":"
                       (json/write-json-str details))))]

        (fixture (fn [_ _]
                   (if stream? {:sse stream} {:json message}))
                 (fn [router seen]
                   (let [error (try (ask router
                                         "claude-fable-5-2"
                                         (cond-> {:server-fallbacks "default"
                                                  :tools [{:name "run"
                                                           :description "Run code."
                                                           :schema {:type "object"
                                                                    :properties
                                                                    {:code {:type "string"}}}}]}
                                           stream?
                                           (assoc :on-chunk
                                             (fn [_]))))
                                    nil
                                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
                     (expect (= :svar.llm/refusal (:type error)))
                     (expect (= details (:stop-details error)))
                     (expect (= 1 (count @seen))))))))))
