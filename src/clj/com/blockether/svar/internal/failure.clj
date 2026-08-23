(ns com.blockether.svar.internal.failure
  "Single source of truth for provider/gateway failure classification.

   Why this namespace exists: `llm` (low-level HTTP retry) and `router`
   (provider fallback) each grew their own copy of the same substring/status
   heuristics, so a newly observed transient had to be added twice and the two
   layers could disagree about whether the very same exception was retryable.
   Everything about \"what kind of failure is this and is it safe to retry\"
   now lives here, and both layers delegate.

   The driving deployment is a self-hosted LiteLLM gateway in front of many
   upstreams (Bedrock, Azure, vLLM). Under load it produces a steady drizzle of
   failures that are NOT the caller's fault and NOT the model's answer:

     litellm.Timeout: BedrockException: Timeout Error - litellm.Timeout:
     Connection timed out. Timeout passed=Timeout(connect=5.0, read=600.0 ...)
     Received Model Group=claude-sonnet-5 Available Model Group Fallbacks=None

     HTTP/1.1 header parser received no bytes

     No deployments available for selected model group

   Every one of those clears on a retry seconds later. Agents that feel
   \"bulletproof\" against such a gateway are not doing anything clever: they
   simply classify the whole transient family as retryable and back off with
   jitter, instead of surfacing the first blip as a hard failure.

   Classification is deliberately EVIDENCE-ORDERED, most specific first:
   account state (quota/billing) and definitive client errors win over any
   transient-looking wording, so a 400 whose body merely contains the word
   \"timeout\" is never retried.

   All predicates are pure: they take an already-lower-cased haystack plus an
   optional status, or a Throwable, and return data. No IO, no sleeping."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Status codes
;; =============================================================================

(def TRANSIENT_STATUS_CODES
  "HTTP statuses that mean \"try again\", never \"your request was wrong\".

   Beyond the classic 429/5xx set this includes:
     408 — Request Timeout. LiteLLM maps `litellm.Timeout` (the Bedrock connect
           timeout in the issue above) onto 408, and a bare 408 was previously
           treated as a definitive 4xx client error and failed on the first blip.
     425 — Too Early (TLS early-data replay refused by a fronting proxy).
     520-527 — Cloudflare's origin-side family (unknown error, web server down,
           connection timed out, origin unreachable, timeout occurred, SSL
           handshake failed, invalid SSL certificate). Cloudflare fronts several
           model providers, and 522/524 are pure transport blips.
     598/599 — non-standard network read/connect timeout codes emitted by some
           proxies (LiteLLM's httpx wrapper and nginx-based gateways).

   Note 529 (Anthropic \"overloaded\") is standard here, and 429 is included but
   `provider-limit-error?` vetoes it when the body proves account exhaustion."
  #{408 425 429 500 502 503 504 520 521 522 523 524 525 527 529 598 599})

;; =============================================================================
;; Account state — never retryable, even on 429
;; =============================================================================

(def NON_RETRYABLE_PROVIDER_LIMIT_PATTERNS
  "Lower-cased substrings that mark provider subscription/quota/billing
   exhaustion — a hard account state, NOT a transient throttle. Even on HTTP
   429 these must NOT retry (mirrors pi's NON_RETRYABLE_PROVIDER_LIMIT_ERROR
   pattern in packages/ai/src/utils/retry.ts)."
  ["gousagelimiterror" "freeusagelimiterror" "monthly usage limit reached" "available balance"
   "insufficient_quota" "out of budget" "quota exceeded" "billing"
   ;; Anthropic / OpenAI / LiteLLM / OpenRouter budget walls: same class,
   ;; different words. Every phrasing below is an account state, never a
   ;; throttle — retrying it burns attempts and still fails.
   "credit balance is too low" "balance is too low" "balance too low" "insufficient credits"
   "insufficient_credits" "insufficient balance" "insufficient_balance" "insufficient funds"
   "not enough credits" "no credits" "add credits" "credit limit" "exceeded your current quota"
   "usage limit reached" "over quota" "quota_exhausted" "quota_exceeded" "budget has been exceeded"
   "budget exceeded" "exceeded budget" "exceeded your budget" "budget limit" "exceededbudget"
   "crossed spend" "spend limit" "spending limit" "max budget" "plan limit reached"
   "subscription expired" "account_deactivated"
   ;; Anthropic's Claude subscription OAuth gate. This wording means the plan
   ;; will not fund a third-party request; repeating the same request cannot
   ;; change the account state.
   "third-party apps now draw" "draw from your extra usage" "draw from extra usage"
   "payment required" "payment_required"])

(defn provider-limit-error?
  "True when error text/body names a subscription/quota/billing exhaustion
   (account state) that must never be retried as a transient throttle."
  [hay]
  (boolean (and hay (some #(str/includes? hay %) NON_RETRYABLE_PROVIDER_LIMIT_PATTERNS))))

(def PROVIDER_LIMIT_STATUS_CODES
  "HTTP statuses that ARE an account/billing wall on their own, whatever the
   body says. 402 Payment Required is the whole point of the code."
  #{402})

(defn provider-limit-failure?
  "The single hard-account-state question: does this status or body mean the
   account is out of quota/credit/budget? Never retryable, never re-routable."
  [status hay]
  (boolean (or (contains? PROVIDER_LIMIT_STATUS_CODES
                          (some-> status
                                  long))
               (provider-limit-error? hay))))

;; =============================================================================
;; Transient wording
;; =============================================================================

(def ^:private GENERIC_TRANSIENT_PATTERNS
  "Provider-agnostic transient wording. Kept as the historical high-signal
   subset of pi's RETRYABLE_PROVIDER_ERROR pattern; omits bare numeric HTTP
   codes (already handled by `TRANSIENT_STATUS_CODES`) and \"connection
   refused\" (ECONNREFUSED means a wrong/down endpoint, not a blip)."
  ["overloaded" "rate.?limit" "too many requests" "service.?unavailable" "server.?error"
   "internal.?error" "provider.?returned.?error" "network.?error" "upstream.?connect" "fetch failed"
   "resource.?exhausted" "you can retry your request" "try your request again"
   "please retry your request"])

(def ^:private GATEWAY_TRANSIENT_PATTERNS
  "LiteLLM / reverse-proxy wording for upstream hiccups. A LiteLLM gateway
   re-raises upstream failures as `litellm.<ErrorClass>: <Upstream>Exception`
   text, frequently WITHOUT a status we can map (the wrapper answers 500 or
   nothing at all), so the class name in the text is the only evidence.

   `no deployments available` / `no healthy deployment` is LiteLLM's router
   saying every replica of a model group is momentarily busy or cooling down —
   the definitive \"come back in a second\" signal, not a missing model."
  ["litellm\\.timeout" "litellm\\.apiconnectionerror" "litellm\\.apierror"
   "litellm\\.internalservererror" "litellm\\.serviceunavailableerror" "litellm\\.ratelimiterror"
   "litellm\\.middlewareerror" "apiconnectionerror" "timeout error" "timed out" "timeout passed="
   "read timeout" "connect timeout" "connection timeout" "no deployments available"
   "no healthy deployment" "deployments are cooling down" "no fallback model group found"
   "bad gateway" "gateway time.?out" "upstream request timeout" "temporarily unavailable"
   "try again later" "please try again" "capacity" "throttlingexception" "modelnotreadyexception"
   "serviceunavailableexception" "internalserverexception"])

(def ^:private PRE_RESPONSE_DROP_PATTERNS
  "The connection died BEFORE any response bytes arrived: the model never saw
   the request, so a retry is not just safe, it is idempotent by construction.
   Worth its own family because the user-facing guidance differs — \"the
   provider rejected the request\" is actively wrong here."
  ["header parser received no bytes" "received no bytes" "empty reply from server"
   "server disconnected" "remote end closed connection" "connection reset by peer"
   "connection reset" "connection aborted" "socket hang up" "premature close"
   "peer closed connection" "eof occurred" "stream closed before response"
   "unexpected end of stream" "goaway" "connection closed before"])

(def RETRYABLE_TRANSIENT_MESSAGE_PATTERN
  "Regex over an error's already-lower-cased message+body marking a transient
   provider/transport failure that carries no mappable HTTP status."
  (re-pattern (str/join "|"
                        (concat GENERIC_TRANSIENT_PATTERNS
                                GATEWAY_TRANSIENT_PATTERNS
                                PRE_RESPONSE_DROP_PATTERNS))))

(def ^:private PRE_RESPONSE_DROP_PATTERN (re-pattern (str/join "|" PRE_RESPONSE_DROP_PATTERNS)))

(defn- definitive-client-error?
  "True for a 4xx that is not 408/425/429 — the provider made a judgement about
   the request itself, so transient-looking wording in the body is noise."
  [status]
  (boolean (and status (<= 400 (long status) 499) (not (contains? #{408 425 429} (long status))))))

(defn transient-message-error?
  "True when a statusless / wrapper / gateway transient shows up only in the
   error TEXT. `status` gates out definitive client errors so a 400/404 whose
   body happens to contain transient wording is never retried."
  [hay status]
  (boolean (and hay
                (not (definitive-client-error? status))
                (not (provider-limit-error? hay))
                (re-find RETRYABLE_TRANSIENT_MESSAGE_PATTERN hay))))

(defn pre-response-drop?
  "True when the failure text proves the connection died before any response
   bytes arrived — the request never reached the model."
  [hay]
  (boolean (and hay (re-find PRE_RESPONSE_DROP_PATTERN hay))))

;; =============================================================================
;; Non-transient but well-known families
;; =============================================================================

(def ^:private AUTH_PATTERNS
  ["authentication" "authorization" "unauthorized" "forbidden" "api key" "api-key" "access token"
   "oauth" "credential" "revoked"
   ;; An expired bearer/JWT/session token is a hard credential failure even when
   ;; a gateway incorrectly wraps it in a retryable status (for example 429).
   "token expired" "expired token" "token_expired" "jwt expired"])

(def ^:private MODEL_UNAVAILABLE_PATTERNS
  "The SELECTED MODEL is unusable on this endpoint — the gateway advertises it
   (or the caller guessed it) but inference is refused. Distinct from auth and
   from LiteLLM's transient \"no deployments available\"."
  ["model_not_supported" "model not supported" "unsupported model" "model_not_found"
   "model not found" "no such model" "unknown model" "invalid model" "not a valid model"
   "model does not exist" "does not exist or you do not have access" "not a valid model id"
   "invalid model name passed in" "llm provider not provided" "you passed in model="
   ;; Anthropic uses backticks in its ordinary unavailable-model response.
   "the model `"])

(def ^:private RESOURCE_MISMATCH_PATTERNS
  "The conversation/item is pinned to a specific backend deployment: Azure
   OpenAI stored-item affinity, or a provider-side session id that only one
   replica knows. Retrying the same request against a load-balanced gateway
   keeps landing on the wrong replica, so a blind retry is the WRONG advice —
   the fix is a fresh conversation (or pinning the deployment)."
  ["created under a different azure openai resource" "use the same resource that created the item"
   "previous_response_not_found" "previous response not found" "not found for this resource"
   "conversation not found" "item not found"])

(def ^:private TOOL_SCHEMA_PATTERNS
  "The provider path rejects a FIELD of the tool payload rather than the tool
   itself — e.g. LiteLLM translating an OpenAI-style tool definition onto a
   Bedrock/Claude path that forbids `tools[].custom.strict`. Not retryable as
   is; the payload has to change."
  ["extra inputs are not permitted" "additionalproperties" "unrecognized request argument"
   "unknown field" "unexpected keyword argument" "is not permitted"])

(def ^:private CONTEXT_LENGTH_PATTERNS
  ["context length" "context_length_exceeded" "maximum context" "too many tokens"
   "prompt is too long" "input is too long" "reduce the length of the messages"
   "exceeds the maximum" "max_tokens" "maximum number of tokens"])

(defn- any-substring? [hay patterns] (boolean (and hay (some #(str/includes? hay %) patterns))))

(defn auth-error?
  "True when provider text identifies unusable credentials, including expiry.
   These are hard failures regardless of an incorrect transient HTTP status."
  [hay]
  (any-substring? hay AUTH_PATTERNS))

;; =============================================================================
;; Exception → evidence
;; =============================================================================

(defn haystack
  "Lower-cased message + body of an exception, the text every predicate here
   consumes. Body first: the provider's own words are more specific than the
   HTTP wrapper's `Exceptional status code: 400`."
  [^Throwable e]
  (let [data
        (ex-data e)

        body
        (:body data)]

    (str (str/lower-case (str (when (string? body) body)))
         " "
         (str/lower-case (str (ex-message e))))))

(defn item-affinity-error?
  "True when the failure says the request replayed a SERVER-MINTED item the
   answering backend does not own — Azure OpenAI stored-item affinity behind a
   load-balancing gateway (LiteLLM fronting several Azure resources) is the
   canonical case. The REQUEST has to change (stop sending those ids); a blind
   retry lands on another replica and fails identically. The transport layer
   uses this to fall back to a stateless replay, `classify` to say so."
  [^Throwable e]
  (any-substring? (haystack e) RESOURCE_MISMATCH_PATTERNS))

(def ^:private REQUEST_ID_PATTERN
  "Correlation ids gateways print bare in an error body — a UUID, or an
   explicitly labelled request id. Preserved so a backend operator can grep
   for the exact failure instead of guessing from a timestamp."
  #"(?i)(?:request[ _-]?id[\"'\s:=]+)?([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")

(defn request-id
  "Extracts a correlation id from an exception's headers or body, or nil."
  [^Throwable e]
  (let [data
        (ex-data e)

        headers
        (:headers data)

        header-id
        (when (map? headers)
          (some (fn [k]
                  (get headers k))
                ["x-request-id" "X-Request-Id" "x-litellm-call-id" "request-id" :x-request-id
                 :request-id]))]

    (or (when (string? header-id) header-id)
        (second (re-find REQUEST_ID_PATTERN (str (:body data) " " (ex-message e)))))))

(defn retry-after-ms
  "Server-declared cooldown from a `Retry-After` header, in ms, or nil.
   Only the delta-seconds form is honored; an HTTP-date form is ignored rather
   than mis-parsed into a nonsense sleep."
  [^Throwable e]
  (let [headers
        (:headers (ex-data e))

        raw
        (when (map? headers)
          (some (fn [k]
                  (get headers k))
                ["retry-after" "Retry-After" :retry-after :Retry-After]))]

    (when raw
      (try (let [secs (Long/parseLong (str/trim (str raw)))]
             (when (nat-int? secs) (* 1000 secs)))
           (catch NumberFormatException _ nil)))))

(defn stream-output-started?
  "True when the provider already streamed visible content/reasoning before the
   failure. Such a call can NOT be silently retried — svar cannot rewind bytes
   the caller has already rendered."
  [^Throwable e]
  (let [data (ex-data e)]
    (or (pos? (long (or (:content-acc-len data) 0)))
        (pos? (long (or (:reasoning-acc-len data) 0)))
        (some? (:partial-content data))
        (some? (:reasoning data)))))

;; =============================================================================
;; Backoff
;; =============================================================================

(def RETRY_MAX_ATTEMPTS
  "Attempts for ONE same-provider HTTP call, including the first. Sleeps are
   therefore `RETRY_MAX_ATTEMPTS - 1`.

   Measured (vis, 2026-08-05): an Anthropic `overloaded_error` (HTTP 529) storm
   produced attempts 1-4 with 0,5 s → 6 s of jittered sleep, ~7 s of healing in
   total, and then surfaced as a hard turn failure. A provider overload window
   is tens of seconds, not seven, so the ladder gave up while the failure was
   still transient — the classification was never wrong, the BUDGET was. Six
   sleeps capped at `RETRY_MAX_DELAY_MS` spend at most ~45 s before the router
   is allowed to move the request to another provider."
  7)

(def RETRY_INITIAL_DELAY_MS "Un-jittered delay for the first same-provider retry." 1000)

(def RETRY_MAX_DELAY_MS
  "Ceiling for one of OUR OWN sleeps — the jittered exponential this namespace
   invents when the server declared nothing. A `Retry-After` the provider sent
   is never clamped to it: waking before the window it named just earns another
   refusal, so `retry-sleep-plan` honors a declared cooldown in full or stops."
  15000)

(def RETRY_MULTIPLIER "Growth factor between attempts." 2.0)

(def RETRY_DELAY_LADDER_MS
  "The same budget as `RETRY_MAX_ATTEMPTS`/`RETRY_INITIAL_DELAY_MS`/
   `RETRY_MAX_DELAY_MS`/`RETRY_MULTIPLIER`, spelled as the EXPLICIT sleep
   schedule the router's same-provider loop consumes (`:same-provider-delays-ms`).

   Measured (vis gateway events, 2026-08-07): every real OpenAI/Codex overload
   arrives MID-STREAM as `Provider stream failed (server_is_overloaded)` with
   status 529, so it never reaches `llm/with-retry` at all — it is healed by the
   router loop, which used to own a private `[2000 3000 6000]` ladder worth 11 s.
   Two ladders for one failure family meant the pre-stream 529 got 45 s of
   healing and the far more common streamed 529 got eleven seconds. One vector,
   derived here, ends that.

   The vector is spelled out rather than computed so it reads as the policy it
   is; `llm-retry-visibility-test` pins it against the four constants above."
  [1000 2000 4000 8000 15000 15000])

(def RETRY_PHASE_BUDGET_MS
  "Wall-clock cap for one same-provider retry phase. Direct calls spend it in
   `llm/with-retry`; routed calls spend it in the router-owned retry loop.

   Measured (vis session 07d38cba, 2026-08-07): an `anthropic-coding-plan` 429
   answered `Retry-After: 60`, again `60`, then `42`. The request that finally
   succeeded landed ~163 s after the first refusal — so a budget shorter than
   that turns a throttle the provider itself scheduled into a hard failure."
  180000)

(defn backoff-ms
  "Exponential backoff with FULL JITTER, the AWS-recommended shape.

   `base` is the un-jittered delay for this attempt (already multiplied), and
   the returned sleep is uniform in [base/2, base]. Jitter matters precisely in
   the failure mode this namespace targets: when one gateway hiccups, every
   in-flight agent retries at the same instant and re-creates the overload.
   `rand-fn` is injectable so tests stay deterministic."
  ([base max-ms] (backoff-ms base max-ms rand))
  ([base max-ms rand-fn]
   (let [capped
         (min (double base) (double max-ms))

         half
         (/ capped 2.0)]

     (long (+ half (* half (double (rand-fn))))))))

(defn retry-sleep-plan
  "How long to wait before the next same-provider attempt, or nil to stop.

   A server-declared `Retry-After` is an INSTRUCTION: waking before the window
   the provider named is a guaranteed repeat refusal that spends an attempt for
   nothing (vis session 07d38cba: every retry slept the ladder's own ceiling and
   re-entered a 60 s cooldown). So a declared cooldown is honored IN FULL or not
   at all — when it does not fit the phase's remaining budget the plan is nil and
   the caller hands the request to the next provider instead of retrying into a
   closed window.

   `:fallback-ms` is the caller's own wait when the server declared none — the
   jittered exponential of `llm/with-retry`, the configured ladder entry in the
   router — and IS clamped to the remaining budget. `:elapsed-ms` is what the
   phase has already spent and `:budget-ms` its cap (nil means
   `RETRY_PHASE_BUDGET_MS`, never 'unbounded'). `:respect-retry-after?` is the
   router's own policy switch and defaults to true."
  [^Throwable e
   {:keys [fallback-ms elapsed-ms budget-ms respect-retry-after?] :or {respect-retry-after? true}}]
  (let [budget
        (long (or budget-ms RETRY_PHASE_BUDGET_MS))

        remain
        (- budget (long (or elapsed-ms 0)))

        declared
        (when respect-retry-after? (retry-after-ms e))]

    (cond (not (pos? remain)) nil
          (some? declared) (when (<= (long declared) remain)
                             {:delay-ms (long declared) :retry-after-ms (long declared)})
          (some? fallback-ms) {:delay-ms (min (long fallback-ms) remain)})))

;; =============================================================================
;; Classification
;; =============================================================================

(def CATEGORIES
  "Stable, user-facing failure families. `:retryable?` is svar's own retry
   verdict; `:reached-model?` answers the question a user actually asks after a
   failure — did anything run, and can I safely send this again?"
  #{:auth :quota-exhausted :rate-limited :transport-drop :connect-timeout :upstream-timeout
    :gateway-unavailable :model-unavailable :resource-mismatch :tool-schema-unsupported
    :context-length-exceeded :stream-interrupted :invalid-request :unknown})

(defn- connect-phase-exception?
  [^Throwable e]
  (loop [t
         e

         n
         0]

    (cond (or (nil? t) (> n 16)) false
          (or (instance? java.net.ConnectException t)
              (instance? java.net.UnknownHostException t)
              (instance? java.net.NoRouteToHostException t)
              (instance? java.net.http.HttpConnectTimeoutException t)
              (instance? java.nio.channels.UnresolvedAddressException t))
          true
          :else (recur (.getCause t) (inc n)))))

(def ^:private WATCHDOG_TYPES
  #{:svar.core/stream-ttft-timeout :svar.core/stream-idle-timeout :svar.core/stream-semantic-timeout
    :svar.core/stream-truncated :svar.core/stream-incomplete})

(declare classify)

(defn- attempt-error-message
  "Human text retained on one router `:attempts` row."
  [error]
  (cond (nil? error) nil
        (string? error) error
        (instance? Throwable error) (ex-message error)
        (map? error) (or (:message error) (get error "message"))
        :else (str error)))

(defn- attempt->throwable
  "Rebuild one router attempt as classification evidence, stripping nested
   attempts so recursive classification is finite."
  ^Throwable [{:keys [status reason error]}]
  (let [nested-data
        (cond (instance? Throwable error) (ex-data error)
              (map? error) (or (:data error) error)
              :else nil)

        data
        (cond-> (dissoc (or nested-data {}) :attempts)
          status
          (assoc :status status))

        message
        (or (not-empty (attempt-error-message error))
            (case reason
              (:auth :authentication)
              "authentication failed"

              :model-unsupported
              "model not supported"

              :rate-limit
              "rate limit"

              :stream-timeout
              "upstream timeout"

              nil)
            "Provider attempt failed")]

    (ex-info message data (when (instance? Throwable error) error))))

(defn- attempt-classifications
  [data]
  (mapv (comp classify attempt->throwable) (filter map? (:attempts data))))

(defn classify
  "Classifies a provider/gateway failure into a stable, actionable shape.

   Returns:
     {:category        one of `CATEGORIES`
      :retryable?      safe for svar to re-send automatically
      :reached-model?  true / false / nil (unknown)
      :status          upstream HTTP status, when any
      :request-id      correlation id, when the gateway printed one
      :attempt-categories categories of retained router attempts, when any
      :all-attempts-category? whether every retained attempt has this category
      :summary         one-line human explanation
      :next-step       one-line human guidance}

   Order matters: account state and definitive client errors are decided before
   any transient wording, so a 400 that merely mentions \"timeout\" stays a hard
   failure."
  [^Throwable e]
  (let [data
        (ex-data e)

        status
        (:status data)

        hay
        (haystack e)

        etype
        (:type data)

        started?
        (stream-output-started? e)

        attempt-results
        (attempt-classifications data)

        attempt-categories
        (mapv :category attempt-results)

        unanimous-attempt-category
        (when (and (seq attempt-categories)
                   (apply = attempt-categories)
                   (not= :unknown (first attempt-categories)))
          (first attempt-categories))

        base
        {:status status :request-id (request-id e) :error-type etype}

        limit?
        (provider-limit-failure? status hay)

        answer
        (fn [category retryable? reached? summary next-step]
          (cond-> (assoc base
                    :category category
                    :retryable? (boolean (and retryable? (not started?)))
                    :reached-model? reached?
                    :summary summary
                    :next-step next-step)
            (seq attempt-categories)
            (assoc :attempt-categories
              attempt-categories :all-attempts-category?
              (= category unanimous-attempt-category))))

        attempt-answer
        (when unanimous-attempt-category
          (-> (peek attempt-results)
              (assoc :status (or status (:status (peek attempt-results)))
                     :request-id (request-id e)
                     :error-type etype
                     :attempt-categories attempt-categories
                     :all-attempts-category? true)
              (update :retryable? #(boolean (and % (not started?))))))]

    (cond
      limit? (answer :quota-exhausted
                     false true
                     "the account's quota/billing limit is exhausted"
                     "top up or switch to another provider — retrying cannot help")
      (auth-error? hay) (answer :auth
                                false false
                                "the provider rejected the credentials"
                                "check the API key/token for this provider")
      (contains? #{401 403}
                 (some-> status
                         long))
      (answer :auth
              false false
              "the provider rejected the credentials" "check the API key/token for this provider")
      (any-substring? hay RESOURCE_MISMATCH_PATTERNS)
      (answer
        :resource-mismatch
        false true
        "the conversation is pinned to a different backend resource than the one that answered"
        "start a fresh conversation, or pin the same deployment/resource that created it")
      (and (any-substring? hay TOOL_SCHEMA_PATTERNS) (str/includes? hay "tool"))
      (answer
        :tool-schema-unsupported
        false true
        "this provider path rejects a field of the tool payload"
        "drop the unsupported tool-schema field (or use a compatibility surface that accepts it)")
      (and (any-substring? hay CONTEXT_LENGTH_PATTERNS) (definitive-client-error? status))
      (answer :context-length-exceeded
              false true
              "the request exceeded the model's context/token budget"
              "shorten the input or lower max_tokens")
      (and (any-substring? hay MODEL_UNAVAILABLE_PATTERNS)
           (not (str/includes? hay "no deployments available")))
      (answer :model-unavailable
              false false
              "the selected model is not available on this endpoint"
              "pick a model the endpoint advertises (see its /v1/models list)")
      ;; ── transient families ────────────────────────────────────────────────
      (contains? WATCHDOG_TYPES etype)
      (answer :stream-interrupted
              (not started?)
              (not started?)
              "the stream stalled or ended before the provider's terminal marker"
              "retry; if it repeats, switch provider/model")
      (or (connect-phase-exception? e)
          (str/includes? hay "connection timed out")
          (str/includes? hay "connect timed out"))
      (answer
        :connect-timeout
        true false
        "the connection to the provider could not be established — the model never saw the request"
        "safe to retry; if it persists, check the gateway's health")
      (pre-response-drop? hay)
      (answer
        :transport-drop
        true false
        "the connection dropped before any response bytes arrived — the model never saw the request"
        "safe to retry; if it keeps failing, check the network and the gateway's status")
      (= 429
         (some-> status
                 long))
      (answer :rate-limited
              true false
              "the provider is throttling this key"
              "retry after the cooldown; sustained throttling means switching provider")
      (or (contains? #{408 425 504 522 524 598 599}
                     (some-> status
                             long))
          (str/includes? hay "timeout"))
      (answer :upstream-timeout
              true nil
              "the provider/gateway exceeded its deadline for this request"
              "retry; if it repeats, shorten the request or switch provider/model")
      (or (contains? TRANSIENT_STATUS_CODES
                     (some-> status
                             long))
          (transient-message-error? hay status))
      (answer :gateway-unavailable
              true nil
              "the gateway or its upstream is temporarily unavailable"
              "retry; if it persists, switch provider/model")
      (definitive-client-error? status)
      (answer :invalid-request
              false true
              "the provider rejected the request as invalid"
              "fix the request; retrying it unchanged will fail the same way")
      :else (or attempt-answer
                (answer :unknown
                        false nil
                        "the provider call failed"
                        "retry; if it persists, switch provider/model")))))

(defn retryable?
  "Convenience: svar's retry verdict for one exception."
  [^Throwable e]
  (:retryable? (classify e)))

;; =============================================================================
;; Transport-level (exception shape) classification
;;
;; Everything below used to live in `llm` (low-level HTTP retry) and `router`
;; (provider fallback) as two independent copies. It is transport evidence —
;; what the JVM/`java.net.http` threw — as opposed to the provider WORDING
;; handled above. Both kinds of evidence now answer the same question in one
;; place: soft (retry) vs hard (surface).
;; =============================================================================

(defn ex-chain
  "Exception + its causes, capped so a self-referential chain cannot loop."
  [^Throwable e]
  (take 16
        (take-while some?
                    (iterate (fn [^Throwable t]
                               (.getCause t))
                             e))))

(def TRANSIENT_NETWORK_ERROR_SUBSTRINGS
  "Lower-cased substrings of transient OS/network-layer connection errors.
   A brief connectivity blip (wifi handoff, VPN reconnect, captive portal,
   laptop sleep/wake) surfaces these while the local stack is momentarily
   down — they clear once the network returns, so they are safe to retry with
   backoff instead of failing the whole call.

   Deliberately excludes \"connection refused\" (ECONNREFUSED): a RST from the
   peer usually means a wrong endpoint / down service, not a transient blip,
   and retrying just hammers it."
  ["can't assign requested address"                                                 ; EADDRNOTAVAIL - local stack churning
   "cannot assign requested address" "network is unreachable"                       ; ENETUNREACH - interface down
   "network is down" "no route to host"                                             ; EHOSTUNREACH - routing not up yet
   "host is unreachable" "connection timed out"                                     ; connect-phase timeout
   "connect timed out" "operation timed out" "temporary failure in name resolution" ; DNS resolver had no network
   "name or service not known" "no address associated with hostname"
   "nodename nor servname provided"])      ; macOS DNS during blip

(defn transient-network-error?
  "True when any link in the cause chain looks like a transient OS/network
   connection error (see `TRANSIENT_NETWORK_ERROR_SUBSTRINGS`). Walks the whole
   chain because babashka.http-client and the router wrap the raw `java.net.*`
   exception several layers deep."
  [^Throwable e]
  (boolean (some (fn [^Throwable t]
                   (or
                     ;; UnknownHostException's message is just the hostname, so match
                     ;; it by class - a DNS miss during a blip is transient.
                     (instance? java.net.UnknownHostException t)
                     (let [m (some-> (ex-message t)
                                     str/lower-case)]
                       (and m (some #(str/includes? m %) TRANSIENT_NETWORK_ERROR_SUBSTRINGS)))))
                 (ex-chain e))))

(defn connection-error?
  "True when any link in the cause chain is a connect-phase network failure:
   the TCP/TLS connection to the provider could not be established at all
   (refused, host down/unreachable, DNS miss, connect-phase timeout). Distinct
   from mid-stream transport drops, which already carry an HTTP envelope and
   `:stream?` data and are classified by `transport-retryable?`."
  [^Throwable e]
  (connect-phase-exception? e))

(defn connection-error-reason
  "Human phrase for a connect-phase failure. Prefers a real (non-blank) message
   from the cause chain, but on JDK 25 / `java.net.http` the `ConnectException`
   chain is frequently all-nil messages, so fall back to a class-derived phrase
   instead of leaking a raw classname to the user."
  [^Throwable e]
  (or
    (some (fn [^Throwable t]
            (let [m (some-> (ex-message t)
                            str/trim)]
              (when-not (str/blank? m) m)))
          (ex-chain e))
    (some
      (fn [^Throwable t]
        (condp instance? t
          java.net.UnknownHostException "host not found (DNS lookup failed)"
          java.nio.channels.UnresolvedAddressException "could not resolve the host address"
          java.net.NoRouteToHostException "no route to host"
          java.net.http.HttpConnectTimeoutException "connection timed out"
          ;; JDK 25 / java.net.http collapses refused, host-down and some
          ;; DNS failures into a message-less ConnectException, so stay
          ;; general rather than falsely asserting "refused".
          java.net.ConnectException
          "the connection could not be established (the host may be down, or the base URL/port may be wrong)"
          nil))
      (ex-chain e))
    "connection failed"))

(defn connection-error->ex-info
  "Wrap a connect-phase failure in an `ex-info` carrying a human-readable,
   provider-aware message plus the `:url` that could not be reached — the raw
   `java.net.ConnectException` message is often nil/terse with no hint of which
   provider/endpoint died. Keeps the original throwable as cause so
   `transport-retryable?` still walks the real `java.net.*` exception."
  [^Throwable e url]
  (let [reason
        (connection-error-reason e)

        host
        (try (.getHost (java.net.URI. (str url))) (catch Exception _ nil))]

    (ex-info (str "Could not connect to the model provider" (when-not (str/blank? host)
                                                              (str " at " host))
                  ": " reason
                  ". The provider may be down or unreachable - check your network "
                  "connection and the provider's base URL.")
             {:type :svar.core/http-error
              :url url
              :connection-error? true
              :cause-class (.getName (class e))}
             e)))

(def DELIBERATE_STREAM_ABORT_TYPES
  "svar's OWN watchdog/caller stream aborts. Each is a DELIBERATE `InputStream`
   `.close` (idle/semantic watchdog fired, or caller cancel) — NOT a transient
   peer connection drop. The JDK surfaces the intentional close as an
   `IOException` whose message is 'Stream closed', which then trips the broad
   'closed' substring heuristic. Left unguarded, the HTTP retry loop misreads a
   watchdog abort as a retryable connection blip and silently re-hammers the
   SAME provider. These belong to bounded, observable router-level fallback."
  #{:svar.core/stream-idle-timeout :svar.core/stream-semantic-timeout :svar.core/stream-cancelled})

(def STREAM_WATCHDOG_ERROR_TYPES
  "Typed stream aborts safe to retry only before visible output. The low-level
   HTTP retry layer excludes these (it would wait one whole timeout per
   same-provider retry); the router instead performs observable provider
   fallback."
  #{:svar.core/stream-ttft-timeout :svar.core/stream-idle-timeout
    :svar.core/stream-semantic-timeout})

(def STREAM_INCOMPLETE_TYPES
  "SSE ended before the provider's terminal marker (`stream-truncated`) or the
   provider explicitly said `response.incomplete` (`stream-incomplete`). Both
   are transport/provider failures rather than a model answer, and retry (as in
   the OpenAI Codex CLI) usually succeeds — but only before visible output."
  #{:svar.core/stream-truncated :svar.core/stream-incomplete})

;; -----------------------------------------------------------------------------
;; Host connect-health registry
;; -----------------------------------------------------------------------------

(defn url->host
  "Host component of a URL string, or nil when absent/unparseable."
  [url]
  (when url (try (not-empty (.getHost (java.net.URI. (str url)))) (catch Exception _ nil))))

(def CONNECT_HEALTH_WINDOW_MS
  "How long ONE successful connection keeps a host classified as 'healthy'.
   A message-less `java.net.ConnectException` (JDK 25 collapses ECONNREFUSED,
   host-down and transient blips into one indistinguishable, message-free
   shape) is retried ONLY when THIS host connected successfully inside this
   window — recent proof the network path works, so the failure is a transient
   blip. A host never reached (LM Studio/Ollama down, wrong port) is absent
   from the registry and fails fast instead of eating retry backoff."
  (* 5 60 1000))

(def host-connect-health*
  "host -> epoch-ms of its most recent successful connection. Drives the health
   gate for message-less ConnectExceptions (see `CONNECT_HEALTH_WINDOW_MS`)."
  (atom {}))

(defn mark-connection-healthy!
  "Record that `url`'s host just connected successfully (HTTP response/headers
   received)."
  [url]
  (when-let [host (url->host url)]
    (swap! host-connect-health* assoc host (System/currentTimeMillis)))
  nil)

(defn host-connection-healthy?
  "True when `url`'s host connected successfully within
   `CONNECT_HEALTH_WINDOW_MS` — recent proof the network path to it works."
  [url]
  (boolean (when-let [t (get @host-connect-health* (url->host url))]
             (< (- (System/currentTimeMillis) (long t)) (long CONNECT_HEALTH_WINDOW_MS)))))

(defn message-less-connect-exception?
  "True when the cause chain holds a `java.net.ConnectException` with a blank
   message — the JDK-25 shape that collapses ECONNREFUSED, host-down and
   transient connectivity blips into one indistinguishable exception that
   cannot be classified by message substring alone."
  [^Throwable e]
  (boolean (some (fn [^Throwable t]
                   (and (instance? java.net.ConnectException t)
                        (str/blank? (or (ex-message t) ""))))
                 (ex-chain e))))

(defn healthy-host-connect-blip?
  "True when a message-less ConnectException hit a host that connected
   successfully moments ago. The prior success is evidence the network is
   healthy, so this is a transient blip worth retrying; a host never reached
   stays non-retryable and fails fast."
  [^Throwable e]
  (and (message-less-connect-exception? e) (host-connection-healthy? (:url (ex-data e)))))

;; -----------------------------------------------------------------------------
;; Stateless item-id registry (server-minted item affinity)
;; -----------------------------------------------------------------------------
;;
;; A Responses replay carries SERVER-minted ids (a `reasoning` item's `rs_…` id
;; and its `encrypted_content`, a `function_call` item's `fc_…` id). Public
;; OpenAI resolves those anywhere; a gateway load-balancing across several Azure
;; OpenAI resources does not, and answers with HTTP 400 "The requested item was
;; created under a different Azure OpenAI resource" (Blockether/vis#59). Every
;; blind retry lands on another replica and fails identically, so the RETRY
;; DECISION lives here next to the classification it is derived from; the
;; transport layer only reshapes the payload it was told to reshape.

(def stateless-item-hosts*
  "Hosts proven unable to resolve replayed server-minted item ids. Sticky for
   the process: the affinity is a property of the deployment, not of one turn."
  (atom #{}))

(defn mark-stateless-items!
  "Remember that `base-url`'s host cannot resolve server-minted item ids."
  [base-url]
  (when-let [h (url->host base-url)]
    (swap! stateless-item-hosts* conj h))
  nil)

(defn stateless-items-host?
  "True once this host has rejected a replayed server-minted item id."
  [base-url]
  (boolean (some-> (url->host base-url)
                   (@stateless-item-hosts*))))

(defn retry-without-server-item-ids?
  "THE verdict for the stateless-replay self-heal: this exact failure is an item
   affinity rejection AND nothing was streamed yet, so re-sending the turn
   without server-minted ids cannot duplicate visible output."
  [^Throwable e]
  (and (item-affinity-error? e) (not (stream-output-started? e))))

;; -----------------------------------------------------------------------------
;; Explicit prompt-cache breakpoint registry (endpoint capability)
;; -----------------------------------------------------------------------------
;;
;; A GPT-5.6+ Responses request can mark exact cache boundaries with
;; `prompt_cache_breakpoint`. Support is a property of the ENDPOINT, not of the
;; model name it was inferred from: the ChatGPT Codex backend answers HTTP 400
;; `prompt_cache_breakpoint is not supported on this model`, and every resend of
;; the same body fails identically - the REQUEST has to change. The transport
;; drops the markers; the verdict lives here next to the classification.

(def explicit-cache-refused-hosts*
  "Hosts proven to reject an explicit `prompt_cache_breakpoint`. Sticky for the
   process: the capability belongs to the deployment, not to one turn."
  (atom #{}))

(defn mark-explicit-cache-refused!
  "Remember that `base-url`'s host rejects explicit cache breakpoints."
  [base-url]
  (when-let [h (url->host base-url)]
    (swap! explicit-cache-refused-hosts* conj h))
  nil)

(defn explicit-cache-refused-host?
  "True once this host has rejected an explicit `prompt_cache_breakpoint`."
  [base-url]
  (boolean (some-> (url->host base-url)
                   (@explicit-cache-refused-hosts*))))

(defn retry-without-explicit-cache?
  "THE verdict for the cache-breakpoint self-heal: the endpoint named
   `prompt_cache_breakpoint` as the field it refuses AND nothing was streamed
   yet, so re-sending the turn without the markers cannot duplicate output."
  [^Throwable e]
  (and (str/includes? (haystack e) "prompt_cache_breakpoint") (not (stream-output-started? e))))
;; -----------------------------------------------------------------------------
;; The two verdicts every layer asks for
;; -----------------------------------------------------------------------------

(defn transport-retryable?
  "SOFT transport failure: a truncated/reset/dropped connection with no HTTP
   status, safe for the low-level HTTP layer to retry against the SAME provider.

   Excludes svar's own deliberate stream aborts (`DELIBERATE_STREAM_ABORT_TYPES`)
   — their 'Stream closed' message is indistinguishable from a peer drop, and
   retrying one silently re-hammers the provider for another full timeout."
  [^Throwable e]
  (let [msg
        (or (ex-message e) "")

        msg-lower
        (str/lower-case msg)

        data
        (ex-data e)

        cause
        (ex-cause e)

        cause-msg
        (when cause (or (ex-message cause) ""))

        cause-lower
        (str/lower-case (or cause-msg ""))]

    (and (not (contains? DELIBERATE_STREAM_ABORT_TYPES (:type data)))
         ;; A quota/credit/budget wall is a hard account state: never a soft
         ;; transport drop, however the body is worded.
         (not (provider-limit-failure? (:status data) (haystack e)))
         (boolean
           (or
             ;; charred.api/read-json fails on a truncated response body
             (str/includes? msg "EOF reached while reading")
             (str/includes? msg "Unexpected end of input")
             ;; java.net.http connection errors
             (instance? java.io.EOFException e)
             (instance? java.io.EOFException cause)
             (instance? java.net.SocketTimeoutException e)
             (instance? java.net.SocketTimeoutException cause)
             (and cause-msg (str/includes? cause-msg "Connection reset"))
             (and cause-msg (str/includes? cause-msg "EOF"))
             ;; transient OS/network-layer blips, whole cause chain
             (transient-network-error? e)
             ;; message-less ConnectException, but only to a proven-healthy host
             (healthy-host-connect-blip? e)
             ;; Peer ACCEPTED the connection then closed it before sending ANY
             ;; response byte ("HTTP/1.1 header parser received no bytes"): between
             ;; a connect-phase failure and a mid-stream drop, and the single most
             ;; common LiteLLM/tunnel/load-balancer blip. Nothing was produced, so
             ;; it is the safest retry of all.
             (str/includes? msg-lower "received no bytes")
             (str/includes? cause-lower "received no bytes")
             (and (:stream? data)
                  (or (str/includes? msg-lower "stream connection error")
                      (str/includes? msg-lower "connection reset")
                      (str/includes? msg-lower "connection closed")
                      (str/includes? msg-lower "closed")
                      (str/includes? cause-lower "connection reset")
                      (str/includes? cause-lower "connection closed")
                      (str/includes? cause-lower "closed")))
             ;; babashka.http-client wraps errors in ExceptionInfo
             (and (instance? clojure.lang.ExceptionInfo e)
                  (some-> cause
                          transport-retryable?)))))))

(defn transient-error?
  "The ROUTER's verdict: is this failure soft enough to try another provider /
   another attempt? Broader than `transport-retryable?` because the router may
   legitimately re-route a watchdog timeout or an HTTP status, but never a hard
   account/quota wall and never after visible output has been streamed.

   `opts` accepts `:transient-status-codes` (defaults to
   `TRANSIENT_STATUS_CODES`)."
  ([e] (transient-error? e nil))
  ([^Throwable e opts]
   (let [data
         (ex-data e)

         status
         (:status data)

         etype
         (:type data)

         codes
         (or (:transient-status-codes opts) TRANSIENT_STATUS_CODES)

         msg
         (ex-message e)

         msg-lower
         (str/lower-case (or msg ""))

         hay
         (haystack e)

         started?
         (stream-output-started? e)]

     (boolean
       (and
         ;; Credential expiry and subscription/quota/billing exhaustion are hard
         ;; account states, never transient throttles.
         (not (auth-error? hay))
         (not (provider-limit-failure? status hay))
         (or (and (contains? STREAM_WATCHDOG_ERROR_TYPES etype) (not started?))
             (and status (contains? codes status))
             (and (= etype :svar.core/http-error)
                  (or (some-> msg
                              (str/includes? "timed out"))
                      (and (:stream? data)
                           (not started?)
                           (or (str/includes? msg-lower "stream connection error")
                               (str/includes? msg-lower "connection reset")
                               (str/includes? msg-lower "connection closed")
                               (str/includes? msg-lower "closed")))))
             (and (contains? STREAM_INCOMPLETE_TYPES etype) (not started?))
             ;; Statusless / wrapper / gRPC transient visible only in the text.
             (transient-message-error? hay status)
             (instance? java.net.ConnectException e)
             (instance? java.net.SocketTimeoutException e)
             (some-> (ex-cause e)
                     ((fn [c]
                        (or (instance? java.net.ConnectException c)
                            (instance? java.net.SocketTimeoutException c)))))))))))

(defn low-level-retry-decision
  "Canonical same-provider HTTP retry policy for `llm/with-retry`.

   The returned map carries `:retry?`, the canonical `:classification`, a stable
   `:reason` when it retries and a `:no-retry-reason` when it does not. Routed
   calls set `:router-handles-transients?`; then this layer performs exactly one
   attempt and hands every transient failure to the router's single retry phase.
   Direct calls omit it and retain the low-level retry ladder."
  ([e] (low-level-retry-decision e nil))
  ([^Throwable e {:keys [router-handles-transients?]}]
   (let [classification
         (classify e)

         {:keys [category status retryable?]}
         classification

         started?
         (stream-output-started? e)

         ;; A known hard canonical category vetoes every generic retry path.
         hard-category?
         (contains? #{:auth :quota-exhausted :resource-mismatch :tool-schema-unsupported
                      :context-length-exceeded :model-unavailable :invalid-request}
                    category)

         transport-candidate?
         (and (not hard-category?) (not started?) (transport-retryable? e))

         ;; A direct call may retry response failures and genuine transport
         ;; drops, but never after visible output or router-owned watchdogs.
         ;; `:connect-timeout` and `:transport-drop` join that set only when the
         ;; failure carries an HTTP status: the shared LiteLLM gateway maps its
         ;; own timeout/drop wrappers onto 408/502 responses.
         response-candidate?
         (and (not started?)
              retryable?
              (or (contains? #{:rate-limited :upstream-timeout :gateway-unavailable} category)
                  (and (some? status) (contains? #{:connect-timeout :transport-drop} category))))

         retry-candidate?
         (boolean (or transport-candidate? response-candidate?))

         router-owned?
         (and router-handles-transients? retry-candidate?)

         retry?
         (and retry-candidate? (not router-owned?))]

     {:retry? retry?
      :reason (when retry?
                (cond transport-candidate? :connection-error
                      (and (= :gateway-unavailable category) (nil? status)) :transient-message
                      :else :http-status))
      :classification classification
      :no-retry-reason (when-not retry?
                         (cond hard-category? :hard-category
                               router-owned? :router-owned-transient
                               started? :output-already-streamed
                               (not retryable?) :not-retryable
                               :else :no-retry-path))})))
