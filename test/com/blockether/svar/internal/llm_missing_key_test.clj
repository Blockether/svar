;; Copyright (c) Blockether. See LICENSE.
(ns com.blockether.svar.internal.llm-missing-key-test
  "Regression: a provider with no API key failed with the error message `value`.

   `make-llm-headers` put `{\"x-api-key\" nil}` (Anthropic) or
   `{\"x-goog-api-key\" nil}` (Gemini) into the request. The JDK refuses a nil
   header value with `NullPointerException: value` before any byte leaves the
   host, so the person saw `value` instead of the provider's answer."
  (:require [clojure.string :as str]
            [lazytest.core :refer [defdescribe expect it]]
            [com.blockether.svar.internal.llm :as sut])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(def ^:private http-get! @#'sut/http-get!)

(defn- refusing-server
  "A loopback server that records each request's headers and answers 401."
  [seen]
  (doto (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
    (.createContext "/"
                    (reify
                      HttpHandler
                        (handle [_ exchange]
                          (let [^HttpExchange exchange
                                exchange

                                body
                                (.getBytes "{\"error\":{\"message\":\"no key\"}}"
                                           StandardCharsets/UTF_8)]

                            (swap! seen conj
                              (into {}
                                    (map (fn [[k v]]
                                           [(str/lower-case k) (vec v)]))
                                    (.getRequestHeaders exchange)))
                            (.sendResponseHeaders exchange 401 (alength body))
                            (with-open [out (.getResponseBody exchange)]
                              (.write out body))))))
    (.start)))

(defn- request-without-key
  [api-style]
  (let [seen
        (atom [])

        ^HttpServer server
        (refusing-server seen)]

    (try (let [url
               (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1/models")

               result
               (try (http-get! url nil {:api-style api-style}) (catch Exception e e))]

           {:error result :seen @seen})
         (finally (.stop server 0)))))

(defdescribe missing-api-key-test
             (it "sends an Anthropic request without the key header and shows the provider answer"
                 (let [{:keys [error seen]} (request-without-key :anthropic)]
                   (expect (= :svar/http-error (:type (ex-data error))) (str error))
                   (expect (= 401 (:status (ex-data error))))
                   (expect (str/includes? (str (:body (ex-data error))) "no key"))
                   (expect (= 1 (count seen)))
                   (expect (not (contains? (first seen) "x-api-key")))
                   (expect (= ["2023-06-01"] (get (first seen) "anthropic-version")))))
             (it "sends a Gemini request without the key header and shows the provider answer"
                 (let [{:keys [error seen]} (request-without-key :gemini)]
                   (expect (= :svar/http-error (:type (ex-data error))) (str error))
                   (expect (= 401 (:status (ex-data error))))
                   (expect (= 1 (count seen)))
                   (expect (not (contains? (first seen) "x-goog-api-key"))))))
