(ns com.blockether.svar.internal.failure-test
  "Contract for `com.blockether.svar.internal.failure`, svar's single source of
   truth for provider/gateway failure classification.

   The failure mode this namespace exists for: a SELF-HOSTED, SHARED LiteLLM
   gateway. It is not a well-behaved cloud endpoint — it 502s while a pod
   rolls, returns `litellm.APIConnectionError` wrapper text with no usable
   status, drops the socket before the first byte, throttles with a
   `Retry-After`, and occasionally answers \"model not found\" for a model that
   is merely on another deployment. None of that is the user's request being
   wrong, so none of it may surface as a hard failure. Everything below pins
   exactly which shapes retry and which must not."
  (:require
   [clojure.string :as str]
   [lazytest.core :refer [defdescribe describe expect it]]
   [com.blockether.svar.internal.failure :as sut]))

(defn- err
  "An `ex-info` shaped like the ones svar's HTTP layer throws."
  ([msg] (err msg {}))
  ([msg data] (ex-info msg data)))

(defdescribe transient-classification-test
  (describe "gateway wrapper text with no HTTP status"
    (it "retries LiteLLM's APIConnectionError"
      (expect (:retryable? (sut/classify (err "litellm.APIConnectionError: connection error")))))

    (it "retries LiteLLM's InternalServerError"
      (expect (:retryable? (sut/classify (err "litellm.InternalServerError: Internal Server Error")))))

    (it "retries a routing miss while a deployment is rolling"
      (expect (:retryable? (sut/classify (err "No healthy deployment available for model")))))

    (it "retries an upstream proxy timeout"
      (expect (:retryable? (sut/classify (err "upstream request timeout")))))

    (it "retries a bare bad gateway body"
      (expect (:retryable? (sut/classify (err "<html>502 Bad Gateway</html>"))))))

  (describe "HTTP statuses"
    (it "treats every transient status as retryable"
      (expect (every? (fn [s] (:retryable? (sut/classify (err "boom" {:status s}))))
                sut/TRANSIENT_STATUS_CODES)))

    (it "never retries a definitive 4xx"
      (expect (not-any? (fn [s] (:retryable? (sut/classify (err "bad" {:status s}))))
                [400 401 403 404 422])))

    (it "does not retry a 400 whose body merely mentions transient wording"
      (expect (not (:retryable?
                    (sut/classify (err "Exceptional status code: 400"
                                    {:status 400
                                     :body   "the model is overloaded, please retry your request"}))))))))

(defdescribe hard-failure-classification-test
  (describe "account state beats every transient signal"
    (it "never retries quota exhaustion, even on 429"
      (let [c (sut/classify (err "rate limit reached: monthly usage limit reached"
                              {:status 429}))]
        (expect (= :quota-exhausted (:category c)))
        (expect (not (:retryable? c)))))

    (it "never retries a billing failure"
      (expect (not (:retryable? (sut/classify (err "billing: available balance is 0")))))))

  (describe "credentials"
    (it "classifies a 401 as :auth"
      (expect (= :auth (:category (sut/classify (err "nope" {:status 401}))))))

    (it "classifies an invalid-api-key 400 as :auth, not invalid-request"
      (expect (= :auth (:category (sut/classify (err "Invalid API key provided"
                                                  {:status 400}))))))))

(defdescribe reached-model-test
  (describe ":reached-model? answers 'can I safely send this again'"
    (it "is false when the connection never produced a response"
      (let [c (sut/classify (err "Connection reset by peer before response"))]
        (expect (= :transport-drop (:category c)))
        (expect (false? (:reached-model? c)))
        (expect (:retryable? c))))

    (it "is false for a connect-phase failure"
      (let [c (sut/classify (ex-info "wrapped" {} (java.net.ConnectException. "Connection refused")))]
        (expect (= :connect-timeout (:category c)))
        (expect (false? (:reached-model? c)))
        (expect (:retryable? c))))

    (it "is true for a request the provider judged and rejected"
      (expect (true? (:reached-model? (sut/classify (err "nope" {:status 422}))))))))

(defdescribe visible-output-test
  (describe "a call that already streamed visible output is never auto-retried"
    (it "refuses to retry a drop after content was emitted"
      (expect (not (:retryable?
                    (sut/classify (err "stream connection error"
                                    {:status 502 :content-acc-len 120}))))))

    (it "refuses to retry after reasoning was emitted"
      (expect (not (:retryable?
                    (sut/classify (err "connection reset"
                                    {:reasoning-acc-len 40}))))))

    (it "does retry the same failure when nothing was emitted yet"
      (expect (:retryable? (sut/classify (err "stream connection error" {:status 502})))))))

(defdescribe actionable-category-test
  (describe "categories a retry can never fix carry guidance instead"
    (it "names a model the endpoint does not serve"
      (let [c (sut/classify (err "The model `gpt-5-turbo` does not exist or you do not have access to it"
                              {:status 404}))]
        (expect (= :model-unavailable (:category c)))
        (expect (not (:retryable? c)))
        (expect (string? (:next-step c)))))

    (it "names an Azure/Bedrock resource mismatch"
      (let [c (sut/classify (err "Item 'rs_abc' was not found for this resource"
                              {:status 400}))]
        (expect (= :resource-mismatch (:category c)))
        (expect (not (:retryable? c)))))

    (it "names a rejected tool-schema field"
      (let [c (sut/classify (err "tools.0.custom.strict: Extra inputs are not permitted"
                              {:status 400}))]
        (expect (= :tool-schema-unsupported (:category c)))
        (expect (not (:retryable? c)))))

    (it "names a context overflow"
      (let [c (sut/classify (err "This model's maximum context length is 128000 tokens"
                              {:status 400}))]
        (expect (= :context-length-exceeded (:category c)))
        (expect (not (:retryable? c)))))

    (it "keeps every category inside the published set"
      (expect (every? (fn [e] (contains? sut/CATEGORIES (:category (sut/classify e))))
                [(err "whatever")
                 (err "boom" {:status 500})
                 (err "nope" {:status 401})
                 (err "billing")
                 (err "timeout" {:status 504})])))))

(defdescribe correlation-test
  (describe "request-id"
    (it "reads a header id"
      (expect (= "req_abc123"
                (sut/request-id (err "boom" {:headers {"x-request-id" "req_abc123"}})))))

    (it "falls back to an id printed bare in the body"
      (expect (some? (sut/request-id
                       (err "boom" {:body "Internal error. Request ID: 5f0a1c2e-1111-4222-8333-abcdef012345"})))))

    (it "is nil when the gateway printed none"
      (expect (nil? (sut/request-id (err "boom" {:body "nothing here"})))))

    (it "is carried on every classification"
      (expect (= "req_1" (:request-id (sut/classify (err "boom" {:status 500
                                                                 :headers {"x-request-id" "req_1"}}))))))))

(defdescribe backoff-test
  (describe "full jitter"
    (it "stays within [base/2, base]"
      (expect (= 500 (sut/backoff-ms 1000 60000 (constantly 0.0))))
      (expect (= 1000 (sut/backoff-ms 1000 60000 (constantly 1.0)))))

    (it "never exceeds the cap"
      (expect (<= (sut/backoff-ms 900000 5000 (constantly 1.0)) 5000)))

    (it "actually varies, so a fleet of agents does not retry in lockstep"
      (expect (< 1 (count (set (repeatedly 50 #(sut/backoff-ms 1000 60000))))))))

  (describe "next-delay-ms"
    (it "honors a server-declared Retry-After over its own backoff"
      (expect (= 3000 (sut/next-delay-ms (err "slow down" {:headers {"retry-after" "3"}})
                        1000 60000 (constantly 1.0)))))

    (it "clamps Retry-After to the max delay"
      (expect (= 5000 (sut/next-delay-ms (err "slow down" {:headers {"retry-after" "600"}})
                        1000 5000 (constantly 1.0)))))

    (it "ignores an unparsable HTTP-date Retry-After rather than sleeping nonsense"
      (expect (= 1000 (sut/next-delay-ms (err "slow down"
                                           {:headers {"retry-after" "Wed, 21 Oct 2015 07:28:00 GMT"}})
                        1000 60000 (constantly 1.0)))))

    (it "falls back to jittered backoff with no header"
      (expect (= 1000 (sut/next-delay-ms (err "boom" {:status 503}) 1000 60000 (constantly 1.0)))))))

;;; ── transport-level consolidation ──────────────────────────────────────────
;;
;; These used to live in `llm` (transport) and `router` (fallback) as two
;; independent copies of the same heuristics. They now have exactly ONE
;; definition; the layers above only ask "soft or hard?".

(defdescribe transport-retryable-test
  (describe "soft transport drops"
    (it "retries a truncated JSON body"
      (expect (sut/transport-retryable? (err "EOF reached while reading" {}))))

    (it "retries the LiteLLM/tunnel 'received no bytes' pre-response drop"
      (expect (sut/transport-retryable?
                (ex-info "HTTP/1.1 header parser received no bytes" {}))))

    (it "retries a mid-stream connection reset"
      (expect (sut/transport-retryable?
                (err "stream connection error" {:stream? true}))))

    (it "retries a transient OS/network blip through the cause chain"
      (expect (sut/transport-retryable?
                (ex-info "wrapped" {}
                  (java.net.SocketException. "Network is unreachable")))))

    (it "retries an EOFException cause"
      (expect (sut/transport-retryable? (ex-info "wrapped" {} (java.io.EOFException. "eof"))))))

  (describe "hard / deliberate aborts are never retried"
    (it "refuses svar's own idle-watchdog abort"
      (expect (false? (sut/transport-retryable?
                        (err "Stream closed" {:type :svar.core/stream-idle-timeout
                                              :stream? true})))))

    (it "refuses a caller cancel"
      (expect (false? (sut/transport-retryable?
                        (err "Stream closed" {:type :svar.core/stream-cancelled
                                              :stream? true})))))

    (it "refuses a plain application error"
      (expect (false? (sut/transport-retryable? (err "invalid api key" {:status 401})))))))

(defdescribe host-connect-health-test
  (describe "message-less ConnectException gate"
    (it "is NOT retryable for a host never reached"
      (reset! sut/host-connect-health* {})
      (expect (false? (sut/healthy-host-connect-blip?
                        (ex-info "" {:url "http://never-reached.test:1234/v1"}
                          (java.net.ConnectException.))))))

    (it "IS retryable right after that same host connected successfully"
      (reset! sut/host-connect-health* {})
      (sut/mark-connection-healthy! "http://healthy.test:4321/v1")
      (expect (sut/healthy-host-connect-blip?
                (ex-info "" {:url "http://healthy.test:4321/v1"}
                  (java.net.ConnectException.)))))

    (it "expires outside the health window"
      (reset! sut/host-connect-health*
        {"stale.test" (- (System/currentTimeMillis) (inc (long sut/CONNECT_HEALTH_WINDOW_MS)))})
      (expect (false? (sut/host-connection-healthy? "http://stale.test/v1"))))))

(defdescribe connection-error-test
  (describe "connect-phase detection + human message"
    (it "sees a ConnectException anywhere in the chain"
      (expect (sut/connection-error? (ex-info "wrapped" {} (java.net.ConnectException. "refused")))))

    (it "does not call a mid-stream drop a connect-phase failure"
      (expect (false? (sut/connection-error? (err "connection reset" {:stream? true})))))

    (it "names the provider host and keeps the cause"
      (let [e (sut/connection-error->ex-info (java.net.ConnectException.)
                "http://gw.internal:4000/v1/chat/completions")]
        (expect (str/includes? (ex-message e) "gw.internal"))
        (expect (true? (:connection-error? (ex-data e))))
        (expect (some? (ex-cause e)))))))

(defdescribe router-transient-error-test
  (describe "the router's broader soft/hard verdict"
    (it "re-routes a configured transient status"
      (expect (sut/transient-error? (err "boom" {:status 503}))))

    (it "honors a caller-narrowed status set"
      (expect (false? (sut/transient-error? (err "boom" {:status 503})
                        {:transient-status-codes #{429}}))))

    (it "re-routes a LiteLLM gateway wording with no status at all"
      (expect (sut/transient-error? (err "litellm.APIConnectionError: connection error" {}))))

    (it "re-routes a stream watchdog timeout before visible output"
      (expect (sut/transient-error? (err "idle" {:type :svar.core/stream-idle-timeout}))))

    (it "refuses the same watchdog timeout once output was streamed"
      (expect (false? (sut/transient-error?
                        (err "idle" {:type :svar.core/stream-idle-timeout
                                     :content-acc-len 42})))))

    (it "re-routes a truncated stream before visible output"
      (expect (sut/transient-error? (err "truncated" {:type :svar.core/stream-truncated}))))

    (it "never re-routes a hard quota/billing wall, whatever the status"
      (expect (false? (sut/transient-error?
                        (err "Your credit balance is too low" {:status 429})))))

    (it "never re-routes a definitive client error"
      (expect (false? (sut/transient-error? (err "invalid request: bad tool schema" {:status 400})))))))

(defdescribe single-source-of-truth-test
  (describe "no layer keeps its own copy of the heuristics"
    (it "llm's transport verdict IS failure/transport-retryable?"
      (expect (identical? sut/transport-retryable?
                @(requiring-resolve 'com.blockether.svar.internal.llm/retryable-exception?))))

    (it "llm's connect-phase check IS failure/connection-error?"
      (expect (identical? sut/connection-error?
                @(requiring-resolve 'com.blockether.svar.internal.llm/connection-error?))))

    (it "llm's stream-output gate IS failure/stream-output-started?"
      (expect (identical? sut/stream-output-started?
                @(requiring-resolve 'com.blockether.svar.internal.llm/stream-output-started?))))

    (it "the router's watchdog set IS failure/STREAM_WATCHDOG_ERROR_TYPES"
      (expect (identical? sut/STREAM_WATCHDOG_ERROR_TYPES
                @(requiring-resolve 'com.blockether.svar.internal.router/STREAM_WATCHDOG_ERROR_TYPES))))))

(defdescribe budget-wall-is-hard-test
  (describe "every phrasing of a quota/credit/budget wall"
    (it "is a hard :quota-exhausted failure even on a 429"
      (doseq [msg ["Your budget has been exceeded"
                   "ExceededBudget: Crossed spend within budget"
                   "Budget exceeded for key sk-***"
                   "Max budget reached for this key"
                   "Monthly spend limit reached"
                   "Your credit balance is too low to run this request"
                   "Insufficient credits — add credits to continue"
                   "You exceeded your current quota, please check your plan"
                   "Payment Required"]]
        (let [e (ex-info msg {:type :svar.core/http-error :status 429})
              c (sut/classify e)]
          (expect (= :quota-exhausted (:category c)) msg)
          (expect (false? (:retryable? c)) msg)
          (expect (false? (sut/transient-error? e)) msg)
          (expect (false? (sut/transport-retryable? e)) msg))))

    (it "treats a bare HTTP 402 as the same account wall"
      (let [e (ex-info "Exceptional status code: 402"
                {:type :svar.core/http-error :status 402})
            c (sut/classify e)]
        (expect (= :quota-exhausted (:category c)))
        (expect (false? (:retryable? c)))
        (expect (false? (sut/transient-error? e)))
        (expect (false? (sut/transport-retryable? e)))))

    (it "does not swallow genuinely soft overload"
      (let [e (ex-info "Overloaded" {:type :svar.core/http-error :status 529})]
        (expect (true? (:retryable? (sut/classify e))))
        (expect (true? (sut/transient-error? e)))))))
