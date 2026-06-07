(ns com.blockether.svar.internal.llm-http-error-test
  "Regression: upstream HTTP error body must survive on
   `:svar.core/http-error` ex-data.

   Pre-fix behavior dropped the `:body` key from ex-data on both the
   non-stream `chat-completion-with-retry` and the streaming
   `chat-completion-streaming` paths. svar still trove-logged a 200-char
   body snippet at WARN, but every downstream consumer (Vis chat
   renderer, Vis loop logger, third-party callers) had to scrape the
   file-log to recover it. That made real provider errors invisible in
   chat \u2014 e.g. Anthropic's `messages.X.content.Y: Invalid signature in
   thinking block` 400 surfaced to the user as the empty wrapper
   `Exceptional status code: 400` and nothing else.

   These tests pin both halves of the contract:

   1. `truncate-error-body` is a pure size-bounded stringifier.
   2. The catch-and-rethrow path on the non-stream completion attaches
      `:body` (truncated) to the rethrown `:svar.core/http-error`
      ex-data so callers can render it without scraping logs."
  (:require
   [clojure.string :as str]
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.llm :as sut]))

;;; ── helper ─────────────────────────────────────────────────────────────

(def ^:private truncate-error-body @#'sut/truncate-error-body)
(def ^:private with-retry @#'sut/with-retry)
(def ^:private MAX-CHARS @#'sut/MAX_HTTP_ERROR_BODY_CHARS)
(def ^:private retryable-exception? @#'sut/retryable-exception?)
(def ^:private transient-network-error? @#'sut/transient-network-error?)
(def ^:private connection-error? @#'sut/connection-error?)
(def ^:private connection-error->ex-info @#'sut/connection-error->ex-info)

(defdescribe truncate-error-body-test
  (describe "pure stringifier"
    (it "returns nil for nil"
      (expect (nil? (truncate-error-body nil))))

    (it "returns nil for blank string"
      (expect (nil? (truncate-error-body "")))
      (expect (nil? (truncate-error-body "   \n  "))))

    (it "passes short strings through unchanged"
      (expect (= "{\"error\":\"boom\"}" (truncate-error-body "{\"error\":\"boom\"}"))))

    (it "truncates over-cap strings with a count suffix"
      (let [body (apply str (repeat (+ MAX-CHARS 50) "x"))
            out  (truncate-error-body body)]
        (expect (str/starts-with? out (apply str (repeat MAX-CHARS "x"))))
        (expect (str/includes? out "...<+50 more chars>"))))

    (it "stringifies non-string bodies (defensive)"
      (expect (= "42" (truncate-error-body 42))))))

;;; ── retry policy ────────────────────────────────────────────────────────

(defdescribe retry-policy-test
  (it "retries Anthropic 529 overloaded responses"
    (let [calls (atom 0)
          result (with-retry
                   (fn []
                     (if (= 1 (swap! calls inc))
                       (throw (ex-info "Exceptional status code: 529" {:status 529}))
                       :ok))
                   {:max-retries 2
                    :initial-delay-ms 0})]
      (expect (= :ok result))
      (expect (= 2 @calls)))))

;;; ── ex-data round-trip ─────────────────────────────────────────────────

;; The actual catch path lives in `chat-completion-with-retry` and
;; `chat-completion-streaming` \u2014 both private. We exercise the contract
;; at the public `chat-completion` entry point with a redefined
;; `http-post!` that throws an ExceptionInfo carrying a `:body` (the
;; shape babashka.http-client emits on non-2xx). The rethrown
;; `:svar.core/http-error` MUST still expose that body to callers.

(defdescribe http-error-ex-data-preserves-body-test
  (describe "non-stream chat-completion"
    (it "attaches the upstream response body to :svar.core/http-error ex-data"
      (let [anthropic-error-body
            (str "{\"type\":\"error\","
              "\"error\":{\"type\":\"invalid_request_error\","
              "\"message\":\"messages.1.content.1: Invalid `signature` in `thinking` block\"},"
              "\"request_id\":\"req_011CarodswSWgEdPfFaJakLs\"}")
            captured (with-redefs-fn
                       {#'com.blockether.svar.internal.llm/http-post!
                        (fn [_ _ _ _]
                          (throw (ex-info "Exceptional status code: 400"
                                   {:status 400
                                    :body anthropic-error-body})))}
                       (fn []
                         (try
                           (sut/chat-completion
                             [{:role "user" :content "hi"}]
                             "claude-opus-4-7"
                             "sk-fake"
                             "https://api.anthropic.com/v1"
                             {:api-style   :anthropic
                              :timeout-ms  1000
                              :max-retries 1})
                           (catch clojure.lang.ExceptionInfo e
                             (ex-data e)))))]
        (expect (= :svar.core/http-error (:type captured)))
        (expect (= 400 (:status captured)))
        (expect (string? (:body captured)))
        (expect (str/includes? (:body captured) "Invalid `signature` in `thinking` block"))
        (expect (str/includes? (:body captured) "request_id")))))

  (describe "streaming chat-completion"
    (it "retries connection-closed stream failures before any emitted stream output"
      (let [calls (atom 0)
            result (with-redefs-fn
                     {#'com.blockether.svar.internal.llm/http-post-stream!
                      (fn [_url _body _headers _timeout-ms _ttft-ms _idle-ms _delta-fn _on-delta]
                        (if (= 1 (swap! calls inc))
                          (throw (ex-info "Stream connection error: closed"
                                   {:type :svar.core/http-error
                                    :stream? true
                                    :content-acc-len 0
                                    :reasoning-acc-len 0}
                                   (java.io.IOException. "closed")))
                          {:content "ok"
                           :reasoning nil
                           :http-response {:status 200 :streaming? true}}))}
                     (fn []
                       (sut/chat-completion
                         [{:role "user" :content "hi"}]
                         "glm-5.1"
                         "sk-fake"
                         "https://example.test/v1"
                         {:on-chunk (constantly nil)
                          :max-retries 2
                          :initial-delay-ms 0})))]
        (expect (= 2 @calls))
        (expect (= "ok" (:content result)))))

    (it "does not retry after reasoning started"
      (let [calls (atom 0)
            captured (with-redefs-fn
                       {#'com.blockether.svar.internal.llm/http-post-stream!
                        (fn [& _]
                          (swap! calls inc)
                          (throw (ex-info "Stream connection error: closed"
                                   {:type :svar.core/http-error
                                    :stream? true
                                    :content-acc-len 0
                                    :reasoning-acc-len 12
                                    :reasoning "thinking..."}
                                   (java.io.IOException. "closed"))))}
                       (fn []
                         (try
                           (sut/chat-completion
                             [{:role "user" :content "hi"}]
                             "glm-5.1"
                             "sk-fake"
                             "https://example.test/v1"
                             {:on-chunk (constantly nil)
                              :max-retries 3
                              :initial-delay-ms 0})
                           nil
                           (catch clojure.lang.ExceptionInfo e
                             (ex-data e)))))]
        (expect (= 1 @calls))
        (expect (= :svar.core/http-error (:type captured)))
        (expect (= "thinking..." (:reasoning captured)))))

    (it "does not retry after visible content started"
      (let [calls (atom 0)
            captured (with-redefs-fn
                       {#'com.blockether.svar.internal.llm/http-post-stream!
                        (fn [& _]
                          (swap! calls inc)
                          (throw (ex-info "Stream connection error: reset"
                                   {:type :svar.core/http-error
                                    :stream? true
                                    :content-acc-len 4
                                    :partial-content "code"}
                                   (java.net.SocketException. "Connection reset"))))}
                       (fn []
                         (try
                           (sut/chat-completion
                             [{:role "user" :content "hi"}]
                             "glm-5.1"
                             "sk-fake"
                             "https://example.test/v1"
                             {:on-chunk (constantly nil)
                              :max-retries 3
                              :initial-delay-ms 0})
                           nil
                           (catch clojure.lang.ExceptionInfo e
                             (ex-data e)))))]
        (expect (= 1 @calls))
        (expect (= :svar.core/http-error (:type captured)))
        (expect (= "code" (:partial-content captured)))))

    (it "preserves partial reasoning on final stream failure"
      (let [captured (with-redefs-fn
                       {#'com.blockether.svar.internal.llm/http-post-stream!
                        (fn [& _]
                          (throw (ex-info "Stream connection error: closed"
                                   {:type :svar.core/http-error
                                    :stream? true
                                    :content-acc-len 0
                                    :reasoning-acc-len 18
                                    :reasoning "reasoning survived"}
                                   (java.io.IOException. "closed"))))}
                       (fn []
                         (try
                           (sut/chat-completion
                             [{:role "user" :content "hi"}]
                             "glm-5.1"
                             "sk-fake"
                             "https://example.test/v1"
                             {:on-chunk (constantly nil)
                              :max-retries 1
                              :initial-delay-ms 0})
                           nil
                           (catch clojure.lang.ExceptionInfo e
                             (ex-data e)))))]
        (expect (= :svar.core/http-error (:type captured)))
        (expect (= "reasoning survived" (:reasoning captured)))))))

;;; ── transient network-blip classification ──────────────────────────────

;; Regression: a brief connectivity blip (wifi handoff, VPN reconnect,
;; laptop sleep/wake) surfaces OS-level connection errors with NO HTTP
;; status - most commonly "Can't assign requested address" (EADDRNOTAVAIL)
;; once the idle watchdog has already closed a stalled stream and svar
;; retries into a still-recovering stack. These cleared in seconds but were
;; classified non-retryable, so svar threw `:svar.core/http-error` and Vis
;; failed the whole turn (losing all prior iterations). They must retry.

(defdescribe transient-network-error-test
  (describe "transient-network-error? cause-chain matching"
    (it "matches EADDRNOTAVAIL (Can't assign requested address)"
      (expect (transient-network-error?
                (java.net.BindException. "Can't assign requested address"))))

    (it "matches when wrapped several layers deep"
      (expect (transient-network-error?
                (ex-info "Stream connection error"
                  {:type :svar.core/http-error}
                  (ex-info "request failed" {}
                    (java.net.SocketException. "Network is unreachable"))))))

    (it "matches no route to host / host unreachable / connect timeout"
      (expect (transient-network-error?
                (java.net.NoRouteToHostException. "No route to host")))
      (expect (transient-network-error?
                (java.net.ConnectException. "Connection timed out")))
      (expect (transient-network-error?
                (java.net.UnknownHostException. "api.anthropic.com"))))

    (it "does NOT match connection refused (down service, not a blip)"
      (expect (not (transient-network-error?
                     (java.net.ConnectException. "Connection refused")))))

    (it "does NOT match unrelated errors"
      (expect (not (transient-network-error?
                     (ex-info "Exceptional status code: 400" {:status 400}))))
      (expect (not (transient-network-error? (NullPointerException.))))))

  (describe "retryable-exception? folds transient network errors in"
    (it "flags EADDRNOTAVAIL as retryable"
      (expect (retryable-exception?
                (ex-info "Can't assign requested address"
                  {:type :svar.core/http-error}
                  (java.net.BindException. "Can't assign requested address")))))

    (it "still flags pre-existing connection-reset / EOF cases"
      (expect (retryable-exception?
                (ex-info "boom" {} (java.net.SocketException. "Connection reset"))))))

  (describe "with-retry recovers from a transient connect blip"
    (it "retries EADDRNOTAVAIL then succeeds when the stack recovers"
      (let [calls (atom 0)
            result (with-retry
                     (fn []
                       (if (< (swap! calls inc) 3)
                         (throw (ex-info "Can't assign requested address"
                                  {:type :svar.core/http-error}
                                  (java.net.BindException. "Can't assign requested address")))
                         :ok))
                     {:max-retries 5 :initial-delay-ms 0})]
        (expect (= :ok result))
        (expect (= 3 @calls))))

    (it "does NOT retry a transient connect error once stream output started"
      ;; replaying after visible tokens would duplicate/rewind the trace
      (let [calls (atom 0)]
        (try
          (with-retry
            (fn []
              (swap! calls inc)
              (throw (ex-info "Can't assign requested address"
                       {:type :svar.core/http-error
                        :stream? true
                        :reasoning-acc-len 42
                        :reasoning "thinking..."}
                       (java.net.BindException. "Can't assign requested address"))))
            {:max-retries 5 :initial-delay-ms 0})
          (catch clojure.lang.ExceptionInfo _ nil))
        (expect (= 1 @calls))))))

;;; ── connect-phase failure: human-readable message ──────────────────────

;; Regression: a connect-phase failure (the TCP/TLS connection to the
;; provider never opened - refused, host down, DNS miss, connect timeout)
;; surfaced to the user as a bare `java.net.ConnectException` whose message
;; on JDK 25 / java.net.http is frequently nil, with no hint of WHICH
;; provider/endpoint died. svar now wraps these at the HTTP source with a
;; human-readable, provider-aware message that names the host and keeps the
;; original throwable as cause so retry classification is unchanged.

(defdescribe connection-error-test
  (describe "connection-error? cause-chain matching"
    (it "matches connect-phase exception classes"
      (expect (connection-error? (java.net.ConnectException. "Connection refused")))
      (expect (connection-error? (java.net.UnknownHostException. "api.test")))
      (expect (connection-error? (java.net.NoRouteToHostException. "x")))
      (expect (connection-error? (java.nio.channels.UnresolvedAddressException.))))

    (it "matches when wrapped several layers deep"
      (expect (connection-error?
                (ex-info "boom" {}
                  (java.io.IOException. "io"
                    (java.net.ConnectException. "Connection refused"))))))

    (it "does NOT match plain HTTP-status / non-network errors"
      (expect (not (connection-error?
                     (ex-info "Exceptional status code: 400" {:status 400}))))
      (expect (not (connection-error? (java.io.EOFException. "EOF"))))))

  (describe "connection-error->ex-info wrapping"
    (it "names the host, keeps :url + :connection-error?, preserves cause"
      (let [cause (java.net.ConnectException. "Connection refused")
            ex    (connection-error->ex-info cause "https://api.anthropic.com/v1/messages")]
        (expect (= :svar.core/http-error (:type (ex-data ex))))
        (expect (= "https://api.anthropic.com/v1/messages" (:url (ex-data ex))))
        (expect (true? (:connection-error? (ex-data ex))))
        (expect (identical? cause (ex-cause ex)))
        (expect (str/includes? (ex-message ex) "api.anthropic.com"))
        (expect (str/includes? (ex-message ex) "Connection refused"))))

    (it "falls back to a class-derived phrase when the chain has no message (JDK 25)"
      (let [ex (connection-error->ex-info (java.net.ConnectException.)
                 "https://router.test/v1/chat")]
        (expect (str/includes? (ex-message ex) "router.test"))
        (expect (str/includes? (ex-message ex) "connection could not be established"))
        (expect (not (str/includes? (ex-message ex) "java.net"))))))

  (describe "streaming chat-completion surfaces connect failures without retry"
    (it "does not retry a wrapped connect-phase failure and keeps the nice message"
      (let [calls (atom 0)
            captured (with-redefs-fn
                       {#'com.blockether.svar.internal.llm/http-post-stream!
                        (fn [url & _]
                          (swap! calls inc)
                          (throw (connection-error->ex-info
                                   (java.net.ConnectException. "Connection refused")
                                   url)))}
                       (fn []
                         (try
                           (sut/chat-completion
                             [{:role "user" :content "hi"}]
                             "glm-5.1"
                             "sk-fake"
                             "https://example.test/v1"
                             {:on-chunk (constantly nil)
                              :max-retries 3
                              :initial-delay-ms 0})
                           nil
                           (catch clojure.lang.ExceptionInfo e
                             {:data (ex-data e) :msg (ex-message e)}))))]
        (expect (= 1 @calls))
        (expect (= :svar.core/http-error (:type (:data captured))))
        (expect (true? (:connection-error? (:data captured))))
        (expect (str/includes? (:msg captured) "Could not connect to the model provider"))))))
