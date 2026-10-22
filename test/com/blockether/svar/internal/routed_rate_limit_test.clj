(ns com.blockether.svar.internal.routed-rate-limit-test
  "A routed call spends ONE retry phase: the router's. A real local server answers
   every request with 429, so the request count shows each retry layer that ran.
   Before the fix, `ask!*` and `ask-code!*` dropped `:router-handles-transients?`, so
   the low-level HTTP ladder retried a 429 six times before the router saw it. Then
   `:fail-fast?` never applied, and the router ladder multiplied those requests."
  (:require [lazytest.core :refer [defdescribe describe expect it]]
            [com.blockether.svar.internal.llm :as llm])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(defn- with-busy-server
  "Calls `f` with the base URL of a local server that answers 429 to every request.
   Returns the number of requests that the server received."
  [f]
  (let [requests
        (atom 0)

        server
        (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]

    (.createContext
      server
      "/"
      (reify
        HttpHandler
          (handle [_ exchange]
            (let [^HttpExchange exchange
                  exchange

                  body
                  (.getBytes
                    "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit_exceeded\"}}")]

              (swap! requests inc)
              (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
              (.set (.getResponseHeaders exchange) "Retry-After" "1")
              (.sendResponseHeaders exchange 429 (alength body))
              (with-open [out (.getResponseBody exchange)]
                (.write out body))))))
    (.start server)
    (try (f (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1"))
         @requests
         (finally (.stop server 0)))))

(defn- busy-router
  [base-url rate-limit]
  (llm/make-router
    [{:id :busy :api-key "k" :base-url base-url :models [{:name "busy-1" :context 200000}]}]
    {:rate-limit (merge {:fallback-provider? false} rate-limit)}))

(defn- call-status
  "Runs `call` and returns the HTTP status of the error that it throws."
  [call]
  (try (call) nil (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))

(def ^:private messages [{:role "user" :content "hi"}])

(defdescribe
  routed-rate-limit-test
  (describe ":fail-fast? ends a routed call at its first 429"
            (it "sends one request for ask-code!"
                (let [status (atom nil)]
                  (expect (= 1
                             (with-busy-server
                               (fn [base-url]
                                 (reset! status (call-status #(llm/ask-code!
                                                                (busy-router base-url
                                                                             {:fail-fast? true})
                                                                {:messages messages})))))))
                  (expect (= 429 @status))))
            (it "sends one request for ask!"
                (expect (= 1
                           (with-busy-server
                             (fn [base-url]
                               (call-status #(llm/ask! (busy-router base-url {:fail-fast? true})
                                                       {:spec nil :messages messages}))))))))
  (describe "without :fail-fast?"
            (it "retries only on the router schedule"
                ;; One request, then one for each router delay. The nested low-level
                ;; ladder sent 7 requests for each of these 3 router attempts.
                (expect (= 3
                           (with-busy-server (fn [base-url]
                                               (call-status #(llm/ask-code!
                                                               (busy-router
                                                                 base-url
                                                                 {:same-provider-delays-ms [0 0]
                                                                  :respect-retry-after? false})
                                                               {:messages messages})))))))))
