(ns com.blockether.svar.internal.llm
  "LLM client layer: HTTP transport, message construction, and all LLM interaction
   functions (ask!, abstract!, eval!, refine!, models!, sample!)."
  (:require
   [babashka.http-client :as http]
   [charred.api :as json]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.svar.internal.codes :as codes]
   [com.blockether.svar.internal.jsonish :as jsonish]
   [com.blockether.svar.internal.router :as router]
   [com.blockether.svar.internal.spec :as spec]
   [com.blockether.svar.internal.util :as util]
   [taoensso.trove :as trove]
   [com.blockether.svar.internal.usage :as usage])
  (:import
   (java.io BufferedReader InputStreamReader)
   (java.net URI)))

;; =============================================================================
;; Correlation context
;; =============================================================================

(def ^:dynamic *log-context*
  "Optional map bound by callers to add context to SVAR logs.
   e.g. {:query-id \"abc\" :iteration 0}
   When bound, all HTTP logs include this context."
  nil)

(def ^:private stream-line-trace-enabled?
  "True when -Dsvar.stream.trace=true (or =1) was passed at JVM start.
   Reads the system property ONCE at load time so the hot per-line
   trace branch reduces to a single cached boolean check; no
   property lookup per SSE line.

   When enabled, the SSE reader loop emits one
   `::stream-line-trace` log entry per N lines (and ALWAYS when the
   inter-line gap is >= STREAM_LINE_TRACE_GAP_MS), carrying:
     :line-count, :gap-ms, :line-preview (capped),
     :content-acc-len, :reasoning-acc-len, :last-event-type.
   Use to prove whether a long-running stream is (a) silent
   server-side, (b) producing periodic pings only, or (c) producing
   real model deltas - the three cases the watchdogs treat
   differently."
  (let [v (some-> (System/getProperty "svar.stream.trace") str/lower-case)]
    (contains? #{"true" "1" "yes" "on"} v)))

(def ^:private ^:const STREAM_LINE_TRACE_EVERY_N
  "Periodic-summary line interval when stream-line-trace is on."
  25)

(def ^:private ^:const STREAM_LINE_TRACE_GAP_MS
  "Force a stream-line-trace log when inter-line gap >= this many ms,
   regardless of the periodic interval. Catches 'real thinking pause'
   moments where N consecutive lines arrive in <1ms and then nothing
   for seconds."
  2000)

(def ^:private ^:const STREAM_LINE_TRACE_PREVIEW_CHARS
  "Cap on :line-preview length carried in each trace log."
  240)

(defn- log-data
  "Merges optional log context into structured log data."
  [data]
  (if-let [ctx *log-context*]
    (merge (select-keys ctx [:query-id :iteration]) data)
    data))

;; =============================================================================
;; HTTP Utilities
;; =============================================================================

(def ^:private RETRYABLE_STATUS_CODES
  "HTTP status codes that should trigger a retry."
  #{429 502 503 504 529})

(def ^:private TRANSIENT_NETWORK_ERROR_SUBSTRINGS
  "Lower-cased substrings of transient OS/network-layer connection errors.
   A brief connectivity blip (wifi handoff, VPN reconnect, captive portal,
   laptop sleep/wake) surfaces these while the local stack is momentarily
   down - they clear once the network returns, so they're safe to retry
   with backoff instead of failing the whole call.

   Deliberately excludes \"connection refused\" (ECONNREFUSED): a RST from
   the peer usually means a wrong endpoint / down service, not a transient
   blip, and retrying just hammers it."
  ["can't assign requested address"        ; EADDRNOTAVAIL - local stack churning
   "cannot assign requested address"
   "network is unreachable"                ; ENETUNREACH - interface down
   "network is down"
   "no route to host"                      ; EHOSTUNREACH - routing not up yet
   "host is unreachable"
   "connection timed out"                  ; connect-phase timeout
   "connect timed out"
   "operation timed out"
   "temporary failure in name resolution"  ; DNS resolver had no network
   "name or service not known"
   "no address associated with hostname"
   "nodename nor servname provided"])      ; macOS DNS during blip

(defn- ex-chain
  "Lazy seq of an exception and its causes, capped to avoid pathological
   self-referential chains."
  [^Throwable e]
  (take 16 (take-while some? (iterate (fn [^Throwable t] (.getCause t)) e))))

(defn- transient-network-error?
  "True when any link in the cause chain looks like a transient OS/network
   connection error (see `TRANSIENT_NETWORK_ERROR_SUBSTRINGS`). Walks the
   whole chain because babashka.http-client and the router wrap the raw
   `java.net.*` exception several layers deep."
  [^Throwable e]
  (boolean
    (some (fn [^Throwable t]
            (or
              ;; UnknownHostException's message is just the hostname, so
              ;; match it by class - a DNS miss during a blip is transient.
              (instance? java.net.UnknownHostException t)
              (let [m (some-> (ex-message t) str/lower-case)]
                (and m (some #(str/includes? m %) TRANSIENT_NETWORK_ERROR_SUBSTRINGS)))))
      (ex-chain e))))

(defn- connection-error?
  "True when any link in the cause chain is a connect-phase network failure:
   the TCP/TLS connection to the provider could not be established at all
   (refused, host down/unreachable, DNS miss, connect-phase timeout). Distinct
   from mid-stream transport drops, which already carry an HTTP envelope and
   `:stream?` data and are classified by `retryable-exception?`."
  [^Throwable e]
  (boolean
    (some (fn [^Throwable t]
            (or (instance? java.net.ConnectException t)
              (instance? java.net.UnknownHostException t)
              (instance? java.net.NoRouteToHostException t)
              (instance? java.net.http.HttpConnectTimeoutException t)
              (instance? java.nio.channels.UnresolvedAddressException t)))
      (ex-chain e))))

(defn- connection-error-reason
  "Human phrase for a connect-phase failure. Prefers a real (non-blank)
   message from the cause chain, but on JDK 25 / `java.net.http` the
   `ConnectException` chain is frequently all-nil messages, so fall back to a
   class-derived phrase instead of leaking a raw classname to the user."
  [^Throwable e]
  (or (some (fn [^Throwable t]
              (let [m (some-> (ex-message t) str/trim)]
                (when-not (str/blank? m) m)))
        (ex-chain e))
    (some (fn [^Throwable t]
            (condp instance? t
              java.net.UnknownHostException             "host not found (DNS lookup failed)"
              java.nio.channels.UnresolvedAddressException "could not resolve the host address"
              java.net.NoRouteToHostException           "no route to host"
              java.net.http.HttpConnectTimeoutException  "connection timed out"
              ;; JDK 25 / java.net.http collapses refused, host-down and some
              ;; DNS failures into a message-less ConnectException, so stay
              ;; general rather than falsely asserting "refused".
              java.net.ConnectException                 "the connection could not be established (the host may be down, or the base URL/port may be wrong)"
              nil))
      (ex-chain e))
    "connection failed"))

(defn- connection-error->ex-info
  "Wrap a connect-phase failure in an `ex-info` carrying a human-readable,
   provider-aware message plus the `:url` that could not be reached - the raw
   `java.net.ConnectException` message is often nil/terse with no hint of which
   provider/endpoint died. Keeps the original throwable as cause so
   `retryable-exception?` still walks the real `java.net.*` exception
   (transient blips like ENETUNREACH stay retryable; ECONNREFUSED stays
   non-retryable - retrying a down/misconfigured endpoint just hammers it)."
  [^Throwable e url]
  (let [reason (connection-error-reason e)
        host   (try (.getHost (java.net.URI. (str url)))
                    (catch Exception _ nil))]
    (ex-info (str "Could not connect to the model provider"
               (when-not (str/blank? host) (str " at " host))
               ": " reason
               ". The provider may be down or unreachable - check your network "
               "connection and the provider's base URL.")
      {:type              :svar.core/http-error
       :url               url
       :connection-error? true
       :cause-class       (.getName (class e))}
      e)))

(defn- stream-output-started?
  "True when a failed stream already emitted anything to the caller.
   Do not retry after reasoning either: TUI already displayed it, so replaying
   the stream duplicates/rewinds the visible trace."
  [^Exception e]
  (let [data (ex-data e)]
    (or (pos? (long (or (:content-acc-len data) 0)))
      (pos? (long (or (:reasoning-acc-len data) 0)))
      (some? (:partial-content data))
      (some? (:reasoning data)))))

(defn- retryable-exception?
  "Returns true if the exception represents a transient connection/read error
   that should be retried (e.g., proxy dropping connection mid-response).

   These errors have no HTTP status code because the response body was truncated
   or the connection was reset before a complete response was received."
  [^Exception e]
  (let [msg (or (ex-message e) "")
        msg-lower (str/lower-case msg)
        data (ex-data e)
        cause (ex-cause e)
        cause-msg (when cause (or (ex-message cause) ""))
        cause-lower (str/lower-case (or cause-msg ""))]
    (or
     ;; charred.api/read-json fails on truncated response body
      (str/includes? msg "EOF reached while reading")
      (str/includes? msg "Unexpected end of input")
     ;; java.net.http connection errors
      (instance? java.io.EOFException e)
      (instance? java.io.EOFException cause)
      (instance? java.net.SocketTimeoutException e)
      (instance? java.net.SocketTimeoutException cause)
      (and cause-msg (str/includes? cause-msg "Connection reset"))
      (and cause-msg (str/includes? cause-msg "EOF"))
     ;; transient OS/network-layer connection errors (brief connectivity
     ;; blip): EADDRNOTAVAIL, ENETUNREACH, EHOSTUNREACH, connect timeout,
     ;; transient DNS failures. Walks the full cause chain.
      (transient-network-error? e)
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
        (some-> cause retryable-exception?)))))

(def ^:private shared-http-executor
  "Cached thread pool that backs the shared HttpClient.

   Used to use a virtual-thread-per-task executor, but JDK virtual threads
   on HTTP/2 + JDK 25 occasionally pinned `CompletableFuture.get` past every
   timer (see TTFT watchdog rationale) and complicated cancellation. A
   plain cached thread pool with daemon threads keeps HTTP/1.1 streaming
   responsive and lets the JVM exit cleanly when `shutdown-http-client!`
   runs."
  (delay
    (let [counter (java.util.concurrent.atomic.AtomicInteger.)
          factory (reify java.util.concurrent.ThreadFactory
                    (newThread [_ r]
                      (doto (Thread. ^Runnable r
                              (str "svar-http-" (.incrementAndGet counter)))
                        (.setDaemon true))))]
      (java.util.concurrent.Executors/newCachedThreadPool factory))))

(def ^:private shared-http-client
  "Single shared HttpClient reused across ALL LLM requests. Without this each
   http/post call constructs a new JDK HttpClient with its own SelectorManager
   thread, which never gets GC'd before the JVM runs out of thread stack. Ran
   into this during the 4clojure benchmark (OOM after ~108 tasks).
   Uses shared-http-executor so blocked HTTP calls cost almost nothing and
   don't pin OS threads.

   HTTP/1.1 PIN - some remote providers and local LLM servers (LM Studio,
   Ollama) choke on HTTP/2 trailers. If you need HTTP/2 for a specific call,
   build a second client - do NOT flip this default."
  (delay
    (http/client (assoc http/default-client-opts
                   :executor @shared-http-executor
                   :version :http1.1))))

(defn shutdown-http-client!
  "Closes the shared HTTP client's virtual-thread executor.
   Idempotent - safe to call multiple times. Call before JVM exit in
   scripts/tests to avoid hanging on non-daemon threads. Registered as
   a shutdown hook automatically; manual invocation is only needed when
   the JVM would otherwise not exit.

   After shutdown, subsequent HTTP calls will fail with
   RejectedExecutionException. Do not call during active request traffic."
  []
  (when (realized? shared-http-executor)
    (try
      (.shutdown ^java.util.concurrent.ExecutorService @shared-http-executor)
      (catch Exception e
        (trove/log! {:level :warn :id ::http-executor-shutdown-failed
                     :data {:error (ex-message e)}
                     :msg "Failed to shutdown shared HTTP executor"}))))
  nil)

;; Register a JVM shutdown hook so the executor is always closed on normal exit.
;; Users don't need to call shutdown-http-client! manually in typical usage.
#_{:clj-kondo/ignore [:unused-private-var]}
(defonce ^:private http-shutdown-hook-registered?
  (do
    (.addShutdownHook (Runtime/getRuntime)
      (Thread. ^Runnable shutdown-http-client! "svar-http-shutdown-hook"))
    true))

(defn- http-post!
  "Makes an HTTP POST request with JSON body. Reuses the shared HttpClient.

   Returns an ENVELOPE map:
     {:parsed   <parsed JSON body or nil if parse failed>
      :raw-body <original response body string>
      :url      <request URL that was hit>
      :status   <HTTP status code, e.g. 200>}

   Exposing the envelope (instead of just the parsed body) lets downstream
   error sites include the raw response in ex-data - critical when a
   provider returns HTTP 200 with a shape we don't understand (reasoning
   only, partial JSON, undocumented fields) and vanilla `:message :content`
   extraction loses the evidence."
  [url body headers timeout-ms]
  (let [response (try
                   (http/post url
                     {:client @shared-http-client
                      :headers headers
                      :body (json/write-json-str body)
                      :timeout timeout-ms})
                   (catch Exception e
                     (if (connection-error? e)
                       (throw (connection-error->ex-info e url))
                       (throw e))))
        raw-body (:body response)
        parsed   (try (json/read-json raw-body :key-fn keyword)
                      (catch Exception _ nil))]
    {:parsed   parsed
     :raw-body raw-body
     :url      url
     :status   (:status response)}))

(declare make-llm-headers)

(defn- http-get!
  "Makes an HTTP GET request.

   Reuses the shared HttpClient to avoid thread/resource leaks.

   Default header dispatcher mirrors chat: api-style + provider-id
   determine whether the call needs Anthropic OAuth headers
   (`anthropic-version`, `anthropic-beta`, `user-agent`, `x-app`),
   Anthropic API-key headers (`x-api-key`), or generic Bearer auth.
   Provider-supplied `:llm-headers` (e.g. Codex `chatgpt-account-id`)
   are merged on top.

   Throws an `ex-info` with `:type :svar/http-error` on non-2xx so
   callers can decide whether to fall back to a catalog default."
  ([url api-key]
   (http-get! url api-key {}))
  ([url api-key {:keys [api-style provider-id llm-headers query-params]
                 :or   {api-style :openai-compatible-chat}}]
   (let [headers   (cond-> (make-llm-headers api-style api-key provider-id)
                     (seq llm-headers) (merge llm-headers))
         req-opts  (cond-> {:client  @shared-http-client
                            :headers headers
                            :throw   false}
                     (seq query-params) (assoc :query-params query-params))
         response  (http/get url req-opts)
         status    (:status response)
         raw-body  (:body response)]
     (when-not (and (integer? status) (<= 200 status 299))
       (throw (ex-info (str "GET " url " failed: HTTP " status)
                {:type        :svar/http-error
                 :status      status
                 :body        raw-body
                 :url         url
                 :provider-id provider-id
                 :api-style   api-style})))
     (json/read-json raw-body :key-fn keyword))))

;; =============================================================================
;; API-style dispatch (OpenAI vs Anthropic)
;; =============================================================================

(defn- anthropic-oauth-token?
  [token]
  (boolean (and (string? token) (str/includes? token "sk-ant-oat"))))

(defn- anthropic-oauth-headers
  [api-key]
  {"Authorization" (str "Bearer " api-key)
   "anthropic-version" "2023-06-01"
   "Content-Type" "application/json"
   "accept" "application/json"
   "anthropic-dangerous-direct-browser-access" "true"
   "anthropic-beta" "claude-code-20250219,oauth-2025-04-20"
   "user-agent" "claude-cli/2.1.62"
   "x-app" "cli"})

(defn- make-llm-headers
  "Builds HTTP headers for the given API style."
  ([api-style api-key]
   (make-llm-headers api-style api-key nil))
  ([api-style api-key provider-id]
   (case api-style
     :anthropic (cond
                  (= :github-copilot provider-id)
                  {"Authorization" (str "Bearer " api-key)
                   "anthropic-version" "2023-06-01"
                   "Content-Type" "application/json"}

                  (anthropic-oauth-token? api-key)
                  (anthropic-oauth-headers api-key)

                  :else
                  {"x-api-key" api-key
                   "anthropic-version" "2023-06-01"
                   "Content-Type" "application/json"})
     ;; :openai-compatible-chat and everything else - Bearer token
     {"Authorization" (str "Bearer " api-key)
      "Content-Type" "application/json"})))

(defn- message-has-image? [message]
  (some (fn [block]
          (= "image_url" (:type block)))
    (when (sequential? (:content message)) (:content message))))

(defn- copilot-agent-initiated? [messages]
  ;; Copilot classifies a request with prior assistant/tool context as agent
  ;; initiated. Last-role-only is wrong for multi-turn agent loops where the
  ;; final prompt is still a user-role continuation/journal message.
  (boolean
    (some (fn [message]
            (contains? #{"assistant" "tool"} (:role message)))
      messages)))

(defn- copilot-static-headers []
  (get-in router/KNOWN_PROVIDERS [:github-copilot :llm-headers]))

(defn- copilot-dynamic-headers [messages]
  (cond-> {"X-Initiator" (if (copilot-agent-initiated? messages) "agent" "user")
           "Openai-Intent" "conversation-edits"}
    (some message-has-image? messages) (assoc "Copilot-Vision-Request" "true")))

(defn- copilot-stream-required? [provider-id base-url]
  (and (= :github-copilot provider-id)
    (string? base-url)
    (boolean (re-find #"(?i)(proxy|api)\.(individual|business|enterprise)\.githubcopilot\.com" base-url))))

(declare messages-have-1h-cache?)

(defn- append-anthropic-beta
  "Append `value` to the comma-separated `anthropic-beta` header on
   `headers`. No-op when the value is already present."
  [headers value]
  (if-let [existing (get headers "anthropic-beta")]
    (let [tokens (->> (str/split (str existing) #",\s*")
                   (remove str/blank?)
                   set)]
      (if (contains? tokens value)
        headers
        (assoc headers "anthropic-beta"
          (str/join "," (conj (vec (sort tokens)) value)))))
    (assoc headers "anthropic-beta" value)))

(defn- request-headers [api-style api-key provider-id messages llm-headers]
  (cond-> (make-llm-headers api-style api-key provider-id)
    (= :github-copilot provider-id)
    (merge (copilot-static-headers))

    (= :github-copilot provider-id)
    (merge (copilot-dynamic-headers messages))

    ;; Auto-add the 1h-cache beta header when any block in this
    ;; request opts into the 1-hour cache tier. Without this header
    ;; Anthropic silently degrades `cache_control.ttl: "1h"` to the
    ;; 5-minute tier; cache writes still succeed but expire much
    ;; sooner than the caller asked for. Live wire-body inspection
    ;; (svar 0.5.10) confirmed the gap. Bug S6 in vis PLAN.md.
    (and (= api-style :anthropic) (messages-have-1h-cache? messages))
    (append-anthropic-beta "extended-cache-ttl-2025-04-11")

    ;; Caller headers win. Lets apps force Copilot X-Initiator through
    ;; :llm-headers when auto inference is wrong for internal calls.
    :always
    (merge llm-headers)))

(defn- make-chat-url
  "Builds the chat endpoint URL for the given API style.
   Returns nil when base-url is nil (provider not configured)."
  [base-url api-style]
  (when base-url
    (case api-style
      :anthropic (if (str/ends-with? base-url "/messages")
                   base-url
                   (str base-url "/messages"))
      ;; Responses transport builds its final endpoint from base-url +
      ;; :responses-path later; keep the provider root unchanged here.
      :openai-compatible-responses base-url
      ;; :openai-compatible-chat default
      (if (str/ends-with? base-url "/chat/completions")
        base-url
        (str base-url "/chat/completions")))))

(def ^:private ^:const ANTHROPIC_THINKING_OUTPUT_RESERVE
  "Tokens reserved for the visible response above Anthropic's thinking budget.
   Anthropic's API rejects requests where `max_tokens <= thinking.budget_tokens`
   because max_tokens counts thinking + output as one pool. We keep a small
   margin so the model can always produce at least a short visible answer
   even when the caller forgets to raise max_tokens for deep thinking."
  1024)

(defn- clamp-anthropic-thinking-max-tokens
  "When `:thinking` is enabled, ensures `max_tokens` leaves room for a visible
   response on top of the thinking budget. Silent-but-logged upgrade; keeps
   thinking enabled rather than failing the request.

   Does nothing when thinking is absent or already sized correctly."
  [body]
  (let [thinking        (:thinking body)
        enabled?        (and (map? thinking) (= "enabled" (:type thinking)))
        budget          (long (if enabled? (or (:budget_tokens thinking) 0) 0))
        current-max     (long (or (:max_tokens body) 0))
        required-min    (+ budget (long ANTHROPIC_THINKING_OUTPUT_RESERVE))]
    (if (and enabled? (pos? budget) (< current-max required-min))
      (do (trove/log! {:level :warn :id ::thinking-max-tokens-clamp
                       :data {:budget-tokens budget
                              :requested-max current-max
                              :clamped-max required-min}
                       :msg (str "Clamping :max_tokens to " required-min
                              " (budget_tokens=" budget " + " ANTHROPIC_THINKING_OUTPUT_RESERVE
                              " response reserve). Anthropic API requires max_tokens > budget_tokens.")})
          (assoc body :max_tokens required-min))
      body)))

;; =============================================================================
;; Cache Control + Multi-block Content
;; =============================================================================
;;
;; Schema placement (see `ask!*`): the full schema body is inlined into the
;; system message (head, cache-friendly) and a short tail pointer is
;; appended to the last user message (recency-friendly). Tail pointer sits
;; past the cache breakpoint so it is billed but never cached.
;;
;; Prompt caching marker: any content block carrying `:svar/cache true` is
;; emitted with `cache_control: {type: "ephemeral"}` on the `:anthropic`
;; api-style. Optional `:svar/cache-ttl :1h` selects Anthropic's 1-hour
;; tier (requires `extended-cache-ttl-2025-04-11` beta header on the
;; provider; without it the tier is silently ignored server-side and
;; behaves as 5min).
;;
;; On non-Anthropic styles (OpenAI, Z.ai, gateway-style proxies) the
;; marker is stripped from the wire body. OpenAI's implicit caching
;; still kicks in for stable ≥1024-tok prefixes - no client signal
;; required.
;;
;; Up to 4 cache breakpoints per Anthropic call. A `cache_control` on a
;; block caches everything in the request UP TO and INCLUDING that
;; block, so order your stable prefix accordingly: caller-system →
;; spec-prompt → (breakpoint) → dynamic user/assistant turns.

(defn- text-block?
  "True for an Anthropic-style text block: `{:type \"text\" :text str ...}`."
  [m]
  (and (map? m) (= "text" (:type m)) (string? (:text m))))

(defn- normalize-content
  "Coerces message :content into a vec of canonical content blocks.
   Accepts string, text-block map, image block, or vec of those.
   Returns `[]` for nil/empty so downstream walkers can rely on a vec."
  [content]
  (cond
    (nil? content)        []
    (string? content)     [{:type "text" :text content}]
    (text-block? content) [content]
    (and (map? content)
      (= "image_url" (:type content)))     [content]
    (vector? content)     (mapv (fn [b]
                                  (cond
                                    (string? b)        {:type "text" :text b}
                                    (and (map? b) (string? (:type b))) b
                                    :else (throw (ex-info
                                                   "Content block must be a string or {:type \"...\" ...}"
                                                   {:type :svar.core/invalid-content-block :got b}))))
                            content)
    :else (throw (ex-info "Unsupported :content shape"
                   {:type :svar.core/invalid-content :got (type content)}))))

(defn- cache-control-for
  "Returns Anthropic `cache_control` map for a `:svar/cache true` block,
   or nil. Honors `:svar/cache-ttl :1h` for the 1-hour tier."
  [block]
  (when (:svar/cache block)
    (let [ttl (:svar/cache-ttl block)]
      (case ttl
        nil   {:type "ephemeral"}
        :5min {:type "ephemeral"}
        :1h   {:type "ephemeral" :ttl "1h"}
        (throw (ex-info "Unknown :svar/cache-ttl. Expected :5min or :1h."
                 {:type :svar.core/invalid-cache-ttl :got ttl}))))))

(defn- strip-svar-keys
  "Drop svar-internal `:svar/*` markers from a content block - used for
   wire formats that don't speak our markers (OpenAI etc)."
  [block]
  (into {} (remove (fn [[k _]] (and (keyword? k) (= "svar" (namespace k))))) block))

;; ====================================================================
;; Caller-opt → wire-body chokepoint (Phase 0 / svar 0.6.0).
;;
;; `apply-llm-opts` runs once per call from both `ask!*` and `ask-code!*`
;; (the only two direct LLM call paths). Wrapper helpers (`abstract!*` /
;; `eval!*` / `refine!*` / `sample!*`) delegate to `ask!*` so they
;; inherit the same transformations through the existing
;; `llm-passthrough` merge.
;;
;; Effects — every one is silently a no-op on api-styles that don't care:
;;   - `:role :system` (keyword) normalises to `"system"` (string).
;;   - Top-level `:system` opt becomes a leading `{:role "system"}`
;;     message ahead of any caller-supplied system messages.
;;   - When no block already carries `:svar/cache true`, auto-tag the
;;     LAST block of the LAST system message so Anthropic gets exactly
;;     one cache breakpoint without caller boilerplate. (`anthropic-block`
;;     emits the `cache_control` marker; OpenAI / Z.ai / Gemini bodies
;;     strip the `:svar/*` keys via `strip-svar-keys`.)
;;   - `:cache-key` opt lifts to `:extra-body {:prompt_cache_key key}`
;;     for `:openai-compatible-chat` / `:openai-compatible-responses`
;;     api-styles. Boosts OpenAI's hash-of-first-256-tokens cache
;;     routing stickiness (per OpenAI docs: 60% → 87% hit-rate).
(defn- normalize-role
  "Turn `:role :system` (keyword) into `:role \"system\"` (string) so
   downstream filters that string-compare match either input. Idempotent."
  [msg]
  (if (keyword? (:role msg))
    (update msg :role name)
    msg))

(defn- merge-top-level-system
  "Prepend the top-level `:system` opt (string OR vec-of-content-blocks)
   as the FIRST `:role \"system\"` message. Caller-supplied role-system
   messages append after. Returns the (possibly augmented) messages vec."
  [messages system-arg]
  (if (or (nil? system-arg)
        (and (string? system-arg) (str/blank? system-arg))
        (and (sequential? system-arg) (empty? system-arg)))
    messages
    (vec (cons {:role "system" :content system-arg} messages))))

(defn- any-block-marker?
  "True when any content block in `messages` carries `:svar/cache true`.
   Used to skip auto-cache when the caller did manual placement —
   anthropic's 4-breakpoint budget shouldn't burn two slots on the
   same position."
  [messages]
  (boolean
    (some (fn [m]
            (let [content (:content m)]
              (cond
                (vector? content)     (some :svar/cache content)
                (sequential? content) (some :svar/cache content)
                :else                 false)))
      messages)))

(defn- last-system-index
  "Return the index of the LAST `:role \"system\"` message in the vec,
   or nil if no system message is present."
  [messages]
  (->> messages
    (map-indexed vector)
    (filter (fn [[_ m]] (= "system" (some-> (:role m) name))))
    last
    first))

(defn- auto-cache-last-system-block
  "Tag the LAST content block of the LAST system message with
   `:svar/cache true`. No-op when no system message exists OR when any
   block already carries the marker (manual placement wins).

   This implements the auto-cache default: callers get the Anthropic
   cache hit for free. `anthropic-block` translates the marker to the
   wire `cache_control` field; other api-styles strip it via
   `strip-svar-keys`."
  [messages]
  (if (any-block-marker? messages)
    messages
    (if-let [idx (last-system-index messages)]
      (let [sys-msg (nth messages idx)
            blocks  (vec (normalize-content (:content sys-msg)))
            n       (count blocks)]
        (if (zero? n)
          messages
          (assoc messages idx
            (assoc sys-msg :content
              (update blocks (dec n) assoc :svar/cache true)))))
      messages)))

(defn- stable-hash-of-system
  "Deterministic short hex hash of every `:role \"system\"` message's
   text content. Used as a fallback `:cache-key` when the caller did
   not provide one — ensures OpenAI / Codex / Z.ai requests with the
   SAME stable system prompt land on the same inference engine across
   calls (boosts cache hit-rate AND makes Codex surface
   `cached_tokens`, which it otherwise reports as 0 even when the
   cache fires server-side).

   Returns nil when no system content is present — keep auto-routing."
  [messages]
  (let [sys-text (->> messages
                   (filter #(= "system" (some-> (:role %) name)))
                   (mapcat (fn [m]
                             (let [c (:content m)]
                               (cond
                                 (string? c) [c]
                                 (sequential? c) (keep :text c)
                                 :else []))))
                   (str/join "\n")
                   not-empty)]
    (when sys-text
      ;; SHA-1 over the first 4096 chars is enough — OpenAI's routing
      ;; hash itself only consumes ~first 256 tokens of the prompt.
      ;; Stable across processes; idempotent for identical content.
      (let [bytes (.getBytes ^String
                    (subs sys-text 0 (min 4096 (count sys-text)))
                    "UTF-8")
            md    (doto (java.security.MessageDigest/getInstance "SHA-1")
                    (.update bytes))
            digest (.digest md)]
        (str "svar-auto-"
          (apply str (take 16 (map #(format "%02x" (bit-and (long %) 0xff))
                                (vec digest)))))))))

(defn- openai-style? [api-style]
  (or (= api-style :openai-compatible-chat)
    (= api-style :openai-compatible-responses)))

(defn- apply-cache-key-opt
  "Forward the caller's `:cache-key` opt onto `:extra-body` as
   `:prompt_cache_key`. When the caller did NOT pass `:cache-key`
   AND the api-style is OpenAI / Responses / Z.ai (openai-compatible-*),
   AUTO-GENERATE a stable key derived from the system-prompt SHA-1
   prefix. Codex specifically refuses to surface `cached_tokens` in
   `response.completed` unless `prompt_cache_key` is on the wire, so
   the auto-key turns Codex cache hits from invisible (latency-only
   evidence) into reported (canonical-shape `:cached` field non-zero).

   The Anthropic body builder strips `:prompt_cache_key` before wire
   send (Anthropic 400s on unknown fields), so it's safe to set the
   field unconditionally regardless of api-style — the downstream
   builder decides keep-or-drop. Net effect: every Anthropic call
   silently ignores the field, every OpenAI / Codex / Z.ai call gets
   sticky routing + cache visibility for free."
  [messages opts]
  (let [k (or (:cache-key opts)
            ;; Only auto-generate for openai-style. Anthropic doesn't
            ;; need it (caching is marker-based) and the field is
            ;; stripped from the wire anyway, so spending a hash on a
            ;; doomed value is wasted work.
            (when (openai-style? (:api-style opts))
              (stable-hash-of-system messages)))]
    (cond-> opts
      k (update :extra-body (fn [eb] (assoc (or eb {}) :prompt_cache_key k))))))

(defn apply-llm-opts
  "Single chokepoint where caller opts that affect the WIRE BODY get
   applied to the messages vec + opts map. Called once per direct LLM
   call (`ask!*` and `ask-code!*`); wrappers inherit by virtue of
   passing opts through `ask!*`.

   Returns `[messages opts]`.

   Telemere `::cache-decision` debug log fires once per call with the
   final cache-related decisions (`:api-style`, `:final-cache-key`,
   `:auto-key?`, `:auto-cache-marker-added?`). Trace surface for
   cache-bisection debugging — read the log handler output instead
   of adding ad-hoc prints."
  [messages opts]
  (let [msgs0 (-> messages
                vec
                (->> (mapv normalize-role))
                (merge-top-level-system (:system opts)))
        msgs  (auto-cache-last-system-block msgs0)
        opts' (apply-cache-key-opt msgs opts)]
    (trove/log!
      {:level :debug
       :id ::cache-decision
       :data {:api-style                (:api-style opts')
              :explicit-cache-key       (:cache-key opts)
              :final-cache-key          (get-in opts' [:extra-body :prompt_cache_key])
              :auto-key?                (and (nil? (:cache-key opts))
                                          (some? (get-in opts' [:extra-body :prompt_cache_key])))
              :auto-cache-marker-added? (not= msgs0 msgs)
              :system-msg-count         (count (filter #(= "system" (some-> (:role %) name)) msgs))}})
    [msgs opts']))

(defn- messages-have-1h-cache?
  "True when any content block in `messages` is tagged for the 1-hour
   cache TTL tier. Used to auto-append the `extended-cache-ttl-2025-04-11`
   beta header on Anthropic so the API actually honors the `ttl: \"1h\"`
   field instead of silently degrading to 5min."
  [messages]
  (boolean
    (some (fn [m]
            (let [content (:content m)]
              (cond
                (sequential? content) (some #(= :1h (:svar/cache-ttl %)) content)
                :else false)))
      messages)))

(defn- anthropic-block
  "Translate one canonical block → Anthropic wire shape, attaching
   `cache_control` when the block was tagged `:svar/cache true`."
  [block]
  (let [cc    (cache-control-for block)
        clean (strip-svar-keys block)]
    (cond-> clean
      cc (assoc :cache_control cc))))

(defn- openai-content
  "Walks canonical blocks → OpenAI wire content. Cache markers are
   stripped; OpenAI's implicit caching benefits from a stable prefix
   without any client signal."
  [blocks]
  (let [wire (mapv strip-svar-keys blocks)]
    (cond
      (zero? (count wire))                              ""
      (and (= 1 (count wire)) (text-block? (first wire))) (-> wire first :text)
      :else                                             wire)))

(def ^:private MAX_HTTP_ERROR_BODY_CHARS
  "Cap on raw upstream response body chars carried in `:svar.core/http-error`
   ex-data. Tradeoff: large enough that Anthropic / OpenAI / z.ai full JSON
   error envelopes survive intact (their longest `error.message` strings stay
   well below 4 KB), small enough that nothing pathological (entire failed
   stream body, retry-attempt logs, etc.) gets pinned on the exception and
   echoed by every downstream telemetry sink. 8 KiB matches Anthropic's own
   max-error-body documented limit."
  8192)

(defn- http-error-message
  "Construct a non-nil, human-readable message for an `:svar.core/http-error`
   ex-info from the original exception. Falls back through several signals
   so the downstream `(ex-message …)` is never nil:

     1. `(ex-message e)` when set
     2. `(.getMessage (ex-cause e))` when set
     3. summary built from ex-data `:status` / `:url` (e.g. `HTTP 503 at
        https://api.anthropic.com/v1/messages`)
     4. exception class name (e.g. `java.net.http.HttpTimeoutException`)
     5. literal fallback `\"HTTP request failed\"`

   Pre-fix: babashka's HttpClient occasionally surfaces low-level
   `IOException` / `HttpTimeoutException` with nil message, and svar
   blindly piped that into `anomaly/fault!`, producing the
   user-hostile `ExceptionInfo: null` trace (Vis conv c8dc39b1: model
   saw `:com.blockether.anomaly.core/message nil` and could not even
   pattern-match on the failure to retry intelligently)."
  [^Throwable e]
  (let [direct (some-> (ex-message e) (#(when-not (str/blank? %) %)))
        cause  (some-> (ex-cause e) ex-message (#(when-not (str/blank? %) %)))
        data   (ex-data e)
        status (:status data)
        url    (:url data)
        klass  (-> e class .getName)
        from-data (cond
                    (and status url) (str "HTTP " status " at " url)
                    status           (str "HTTP " status)
                    url              (str "request to " url " failed")
                    :else            nil)]
    (or direct cause from-data klass "HTTP request failed")))

(defn- truncate-error-body
  "Returns a short string suitable for `:body` / `:body-snippet` on
   `:svar.core/http-error` ex-data. Stringifies non-string bodies
   (`InputStream` callers should already have slurped). Returns nil for
   nil / blank input so `cond->` callers can skip the assoc cleanly.

   Truncates to `MAX_HTTP_ERROR_BODY_CHARS` and appends a `...<+N more>`
   suffix so downstream renderers (Vis chat error bubble) can show the
   first ~8 KB of an upstream provider error envelope verbatim - that
   first slice is where Anthropic's `error.message` (e.g. `Invalid
   signature in thinking block`) and `request_id` always live.

   Pre-fix Vis only saw `Exceptional status code: 400` because callers
   above us `(dissoc ex-data-map :body)`'d the only place this content
   lived. Surfacing the snippet from svar means every consumer (Vis
   loop logger, Vis chat renderer, third-party callers, tests) gets
   the same truthful payload without each one re-implementing the
   trove WARN-log scrape."
  [body]
  (cond
    (nil? body)                  nil
    (and (string? body)
      (str/blank? body))         nil
    (string? body)               (let [n (count body)
                                       cap (long MAX_HTTP_ERROR_BODY_CHARS)]
                                   (if (<= n cap)
                                     body
                                     (str (subs body 0 cap) "...<+" (- n cap) " more chars>")))
    :else                        (recur (str body))))

(defn- canonical-thinking-block?
  "Recognizes svar's canonical preserved-thinking content block. Mirrors
   pi-ai's shape: `{:type \"thinking\" :thinking str :thinking-signature
   str :redacted? bool}`. The thinking-signature is an opaque
   per-provider payload that has to round-trip verbatim on the next
   call - Anthropic's HMAC `signature`, Anthropic redacted-thinking's
   encrypted `data`, OpenAI Responses' JSON-encoded reasoning item,
   z.ai's exact `reasoning_content` text, etc. Wire serializers per
   api-style read this canonical shape and emit native wire blocks."
  [block]
  (and (map? block) (= "thinking" (:type block))))

(defn- canonical-thinking->anthropic-block
  "Translates one canonical thinking block to its Anthropic wire shape.
   `:redacted? true` becomes `redacted_thinking` carrying the encrypted
   data under `:data`; otherwise emits a normal `thinking` block whose
   `:signature` round-trips Anthropic's HMAC verbatim. Blocks that
   never received a signature (e.g. an aborted stream) degrade to a
   plain text block so Anthropic doesn't reject the request - this
   matches pi-ai's behavior."
  [{:keys [thinking thinking-signature redacted?]}]
  (cond
    redacted?
    {:type "redacted_thinking"
     :data thinking-signature}

    (and (string? thinking-signature) (not (str/blank? thinking-signature)))
    {:type "thinking"
     :thinking (or thinking "")
     :signature thinking-signature}

    :else
    ;; Missing signature - fall back to a text block so Anthropic doesn't
    ;; reject the next request and Claude doesn't start mimicking the
    ;; <thinking> tags in subsequent responses.
    {:type "text"
     :text (or thinking "")}))

(defn- demote-interior-thinking-blocks
  "Anthropic's contract: in any assistant message, all `thinking` /
   `redacted_thinking` blocks must appear BEFORE any `text` /
   `tool_use` / `tool_result` block. Interior thinking blocks (after
   a non-thinking block) are only legal under the
   `interleaved-thinking-2025-05-14` beta and only when anchored by a
   `tool_use` - never after plain text.

   Claude Code's `claude-code-20250219` beta sometimes streams a
   trailing thinking block after a text block in a SINGLE response.
   Anthropic accepts that on output, but its signature validator
   rejects it on REPLAY with `messages.X.content.Y: Invalid signature
   in thinking block` 400 (Vis conversation 6f5f7dbb). Whatever
   Anthropic's reasoning, the contract on the documented wire is
   leading-thinking-only.

   Strategy: keep every leading thinking block (with its HMAC
   signature) verbatim; once we hit the first non-thinking canonical
   block, demote any further thinking block to a plain text block -
   the model still sees its own prior reasoning, just as visible text
   instead of a signed block. This is the same fallback
   `canonical-thinking->anthropic-block` already does for
   missing-signature thinking, applied here for a different reason.

   z.ai / OpenAI Responses are NOT routed through this fn - z.ai's
   contract requires the run stay contiguous and verbatim, never
   reordered or demoted (see `preserved-thinking-replay-messages` in
   Vis loop.clj). This is Anthropic-only."
  [blocks]
  (let [{:keys [out _]}
        (reduce (fn [{:keys [out seen-non-thinking?]} b]
                  (cond
                    (not (canonical-thinking-block? b))
                    {:out (conj out b) :seen-non-thinking? true}

                    seen-non-thinking?
                    ;; Interior thinking - demote to text. Drops the
                    ;; signature on purpose; Anthropic would reject it
                    ;; anyway and the visible text round-trips fine.
                    {:out (conj out {:type "text"
                                     :text (or (:thinking b) "")})
                     :seen-non-thinking? true}

                    :else
                    {:out (conj out b) :seen-non-thinking? false}))
          {:out [] :seen-non-thinking? false}
          blocks)]
    out))

(defn- anthropic-message-content
  "Builds the wire content for one message under `:anthropic` api-style.
   Walks each canonical content block: `{:type \"thinking\"}` blocks go
   through `canonical-thinking->anthropic-block` (signature/redacted
   rendered natively), everything else passes through `anthropic-block`
   unchanged. Force the array form whenever a thinking block is present
   - the single-text-collapse path would drop the signed blocks.

   Before serialization, `demote-interior-thinking-blocks` rewrites
   any `thinking` block that appears AFTER a non-thinking block as a
   plain text block. See that fn's docstring for the full rationale
   (Anthropic 400 `Invalid signature in thinking block` on replay)."
  [{:keys [content]}]
  (let [blocks        (-> content normalize-content demote-interior-thinking-blocks)
        has-thinking? (some canonical-thinking-block? blocks)
        wire          (into []
                        ;; Drop empty text blocks at the point of
                        ;; production. The demote / missing-signature
                        ;; fallbacks (`demote-interior-thinking-blocks`,
                        ;; `canonical-thinking->anthropic-block` :else)
                        ;; emit `{:type "text" :text ""}` when a thinking
                        ;; block carried no text, and Anthropic 400s on ANY
                        ;; empty text block (`messages: text content blocks
                        ;; must be non-empty`) — replaying such an assistant
                        ;; message rejected the whole next request (Vis
                        ;; session ac065988). Non-text blocks (thinking,
                        ;; image, tool_use) always pass through.
                        (keep (fn [b]
                                (let [w (if (canonical-thinking-block? b)
                                          (canonical-thinking->anthropic-block b)
                                          (anthropic-block b))]
                                  (when-not (and (text-block? w)
                                              (str/blank? (:text w)))
                                    w))))
                        blocks)]
    (if (or has-thinking?
          (not= 1 (count wire))
          (not (text-block? (first wire)))
          (some? (:cache_control (first wire))))
      wire
      (-> wire first :text))))

(defn- build-anthropic-request-body
  "Builds request body in Anthropic Messages API format.

   Top-level `:system` is emitted as a STRING when no system block
   carries a cache marker, and as an ARRAY of text blocks (with
   per-block `cache_control`) otherwise - Anthropic accepts both.

   `:svar/cache true` markers on user/assistant content blocks are
   translated to `cache_control: {type: \"ephemeral\"}` per block.
   Anthropic enforces a hard cap of 4 cache breakpoints per call.

   Assistant messages whose `:content` contains canonical
   `{:type \"thinking\"}` blocks emit them as native Anthropic
   `thinking` / `redacted_thinking` wire blocks (see
   `canonical-thinking->anthropic-block`). Echoing them back is how
   Anthropic's extended thinking is continued across calls - the model
   sees its own prior reasoning (signature-verified server-side) before
   the next user turn instead of re-thinking from scratch.

   `:max_tokens` is always present (Anthropic requirement) and
   `clamp-anthropic-thinking-max-tokens` ensures visible output has
   room above `:thinking.budget_tokens` when extended thinking is on."
  ([messages model extra-body]
   (build-anthropic-request-body messages model extra-body nil))
  ([messages model extra-body {:keys [anthropic-oauth?]}]
   ;; S2 fix: accept both `:role :system` (keyword) and `:role "system"`
   ;; (string). Anthropic only accepts string role names; we normalise
   ;; here so callers can pass either.
   (let [system-role? (fn [m] (= "system" (some-> (:role m) name)))
         sys-blocks  (cond-> (vec (mapcat #(normalize-content (:content %))
                                    (filter system-role? messages)))
                       anthropic-oauth? (->> (into [{:type "text"
                                                     :text "You are Claude Code, Anthropic's official CLI for Claude."
                                                     :svar/cache true}])
                                          vec))
         any-cache?  (some :svar/cache sys-blocks)
         system-wire (cond
                       (empty? sys-blocks) nil
                       any-cache?          (mapv anthropic-block sys-blocks)
                       :else               (str/join "\n" (map :text sys-blocks)))
         non-system  (->> messages
                       (remove system-role?)
                       (mapv (fn [{:keys [role] :as msg}]
                               {:role (some-> role name)
                                :content (anthropic-message-content msg)})))
         max-tokens  (or (:max_tokens extra-body) 4096)
         ;; Drop fields Anthropic does NOT recognise (would 400 on
         ;; unknown). `:stream_options` is OpenAI-only;
         ;; `:prompt_cache_key` is OpenAI-only routing-stickiness key.
         anthropic-extra (dissoc extra-body :stream_options :prompt_cache_key)
         body        (cond-> {:model model :messages non-system :max_tokens max-tokens}
                       system-wire (assoc :system system-wire)
                       (seq anthropic-extra) (merge anthropic-extra))]
    ;; Re-assert system after merge so extra-body can't clobber it,
    ;; then apply the thinking-aware max_tokens clamp.
     (-> (cond-> body system-wire (assoc :system system-wire))
       clamp-anthropic-thinking-max-tokens))))

(def ^:private ^:const ANTHROPIC_COUNT_TOKENS_TIMEOUT_MS
  "Pre-flight count is a fast metadata call; cap it tight so a slow
   count_tokens never delays the real request. On timeout we fall back to
   the offline estimate."
  10000)

(defn- anthropic-count-tokens
  "Anthropic-native pre-flight token count: POST `{base-url}/messages/count_tokens`.

   When the resolved provider is the DIRECT Anthropic API (`:api-style
   :anthropic`), svar asks Anthropic itself how many input tokens a
   request will consume instead of approximating with the offline tiktoken
   estimate. Reuses the SAME message→Anthropic-body conversion as the real
   call, so the count reflects exactly what gets sent — system blocks,
   `:svar/cache` markers, echoed prior thinking blocks, tools. The endpoint
   is FREE and rate-limited separately from message creation.

   Returns `input_tokens` (long) on HTTP 200, or nil on any non-200 /
   timeout / parse failure so the caller falls back to the offline
   estimate. NEVER throws: an accurate-count outage must not break the
   actual LLM call.

   Proxied Claude (OpenRouter / LiteLLM, `:openai-compatible-chat`) does
   NOT expose this endpoint, so the caller gates this on `:anthropic`
   api-style."
  [messages model {:keys [api-key base-url provider-id llm-headers]}]
  (try
    (let [body    (-> (build-anthropic-request-body messages model nil)
                    ;; count_tokens takes the message-creation inputs minus
                    ;; generation params. Drop max_tokens/stream so the
                    ;; endpoint doesn't choke on fields it doesn't model.
                    (dissoc :max_tokens :stream :stream_options))
          headers (cond-> (make-llm-headers :anthropic api-key provider-id)
                    (seq llm-headers) (merge llm-headers))
          url     (str (str/replace base-url #"/+$" "") "/messages/count_tokens")
          {:keys [parsed status]} (http-post! url body headers ANTHROPIC_COUNT_TOKENS_TIMEOUT_MS)]
      (when (= 200 (long (or status 0)))
        (some-> (:input_tokens parsed) long)))
    (catch Exception e
      (trove/log! {:level :debug :id ::anthropic-count-tokens-failed
                   :data {:model model :error (ex-message e)}
                   :msg "count_tokens unavailable; offline estimate fallback"})
      nil)))

(defn- anthropic-exact-count-fn
  "Returns a 0-arg thunk that fetches the exact Anthropic `count_tokens`
   value for `messages`/`model`, or nil for non-Anthropic api-styles (which
   have no such endpoint). The router's `check-context-limit` decides
   WHETHER to call the thunk (near the limit only) — this just supplies the
   transport. nil ⇒ pure offline estimate."
  [messages model {:keys [api-style api-key base-url provider-id llm-headers]}]
  (when (= api-style :anthropic)
    (fn [] (anthropic-count-tokens messages model
             {:api-key api-key :base-url base-url
              :provider-id provider-id :llm-headers llm-headers}))))

(defn- anthropic-wire->canonical-block
  "Translates one Anthropic wire content block to svar's canonical
   shape. `text` blocks become `{:type \"text\" :text str}`; `thinking`
   blocks become canonical thinking blocks carrying the HMAC signature
   in `:thinking-signature`; `redacted_thinking` blocks become
   canonical thinking blocks with `:redacted? true` and the encrypted
   data carried under `:thinking-signature`. Anything else passes
   through unchanged so future Anthropic block types (tool_use,
   server_tool_use, ...) survive a round-trip."
  [block]
  (case (:type block)
    "text"
    {:type "text" :text (or (:text block) "")}

    "thinking"
    {:type "thinking"
     :thinking (or (:thinking block) "")
     :thinking-signature (or (:signature block) "")
     :redacted? false}

    "redacted_thinking"
    {:type "thinking"
     :thinking ""
     :thinking-signature (or (:data block) "")
     :redacted? true}

    block))

(defn- anthropic-canonical-assistant-message
  "Builds the canonical `:assistant-message` for an Anthropic response.
   The result is a normal svar message map - callers append it to
   `:messages` on the next call. nil when the response had no content
   blocks worth round-tripping."
  [content-blocks]
  (when (seq content-blocks)
    (let [canonical (mapv anthropic-wire->canonical-block content-blocks)]
      {:role "assistant"
       :content canonical})))

(defn- extract-anthropic-response-data
  "Extracts content, reasoning, usage, and the canonical assistant
   message from an Anthropic Messages API response envelope.
   Normalizes usage keys to OpenAI names so downstream token counting
   works unchanged, and preserves the raw envelope under :http-response
   for error-site attachment (see `extract-response-data` docstring).

   `:assistant-message` carries the response's full content as svar's
   provider-agnostic canonical message: text blocks plus any `thinking`
   blocks (with HMAC signature under `:thinking-signature`) and any
   `redacted_thinking` blocks (encrypted data under
   `:thinking-signature`, `:redacted? true`). Caller appends this map
   to `:messages` on the next call to keep Claude's extended thinking
   session active."
  [envelope]
  (let [response       (:parsed envelope)
        content-blocks (:content response)
        text-parts     (->> content-blocks
                         (filter #(= "text" (:type %)))
                         (map :text))
        thinking-parts (->> content-blocks
                         (filter #(= "thinking" (:type %)))
                         (map :thinking))
        usage          (:usage response)
        visible        (when (seq text-parts) (str/join "\n" text-parts))
        canonical-msg  (anthropic-canonical-assistant-message content-blocks)]
    (cond-> {:content       visible
             :reasoning     (when (seq thinking-parts) (str/trimr (str/join "\n" thinking-parts)))
             ;; Phase A canonical usage shape — :input-tokens always
             ;; TOTAL (anthropic-additive raw values are summed here).
             :api-usage     (usage/anthropic-canonical usage)
             :http-response envelope}
      canonical-msg (assoc :assistant-message canonical-msg))))

(defn- make-anthropic-stream-delta-fn
  "Builds a stateful one-arg delta-fn closure for Anthropic SSE streams.
   Per-event return shape (the SSE aggregator expects this uniformly):
   `{:content-delta s? :reasoning-delta s? :api-usage m? :terminal? b?}`.
   Additionally accumulates partial wire blocks across
   `content_block_start` ... `content_block_delta` ... `content_block_stop`.
   Each closed block is converted to svar's canonical form
   (`anthropic-wire->canonical-block`) and flushed to the aggregator
   via `:provider-state {:provider :anthropic :blocks [<canonical>]}`.
   The aggregator concatenates blocks in arrival order - the same order
   Anthropic requires them re-sent in to keep the extended-thinking
   session valid.

   Why a closure: a flat per-event extractor cannot pair a
   `signature_delta` with the `thinking_delta` chunks that preceded it
   under the same block index, and Anthropic's `signature` field is
   required verbatim on replay - dropping it invalidates the next
   request server-side."
  []
  (let [pending (atom {})] ;; index -> partial wire block map
    (fn [chunk]
      (case (:type chunk)
        "content_block_start"
        (let [idx (:index chunk)
              block (:content_block chunk)]
          (swap! pending assoc idx (or block {}))
          {:content-delta nil :reasoning-delta nil :api-usage nil})

        "content_block_delta"
        (let [idx   (:index chunk)
              delta (:delta chunk)]
          (case (:type delta)
            "text_delta"
            (do (swap! pending update-in [idx :text] (fnil str "") (:text delta))
                {:content-delta (:text delta) :reasoning-delta nil :api-usage nil})

            "thinking_delta"
            (do (swap! pending update-in [idx :thinking] (fnil str "") (:thinking delta))
                {:content-delta nil :reasoning-delta (:thinking delta) :api-usage nil})

            "signature_delta"
            (do (swap! pending update-in [idx :signature] (fnil str "") (:signature delta))
                {:content-delta nil :reasoning-delta nil :api-usage nil})

            ;; Anthropic also emits input_json_delta for tool_use blocks;
            ;; svar doesn't use Anthropic tool_use today, but keep the
            ;; accumulator forward-compatible by passing the raw delta
            ;; through under a synthetic :partial_json key.
            "input_json_delta"
            (do (swap! pending update-in [idx :partial_json] (fnil str "") (:partial_json delta))
                {:content-delta nil :reasoning-delta nil :api-usage nil})

            {:content-delta nil :reasoning-delta nil :api-usage nil}))

        "content_block_stop"
        (let [idx (:index chunk)
              wire-block (get @pending idx)]
          (swap! pending dissoc idx)
          (if (seq wire-block)
            ;; Convert wire → canonical and flush. Text blocks are
            ;; included so the aggregator can rebuild a faithful
            ;; canonical assistant message preserving block order;
            ;; thinking/redacted_thinking carry their signatures
            ;; verbatim for round-trip.
            {:content-delta nil :reasoning-delta nil :api-usage nil
             :provider-state {:provider :anthropic
                              :blocks [(anthropic-wire->canonical-block wire-block)]}}
            {:content-delta nil :reasoning-delta nil :api-usage nil}))

        "message_delta"
        {:content-delta nil :reasoning-delta nil :api-usage (usage/anthropic-canonical (:usage chunk))}

        "message_start"
        {:content-delta nil :reasoning-delta nil :api-usage (usage/anthropic-canonical (get-in chunk [:message :usage]))}

        "message_stop"
        {:content-delta nil :reasoning-delta nil :api-usage nil :terminal? true}

        {:content-delta nil :reasoning-delta nil :api-usage nil}))))

(defn- with-retry
  "Executes a function with exponential backoff retry for transient errors.

   Retries on HTTP status codes 429, 502, 503, 504, 529.

   Params:
   `f` - Function to execute (no args).
   `opts` - Map, optional:
     - :max-retries - Integer. Max retry attempts (default: 5).
     - :initial-delay-ms - Integer. Initial backoff (default: 1000ms).
     - :max-delay-ms - Integer. Max backoff cap (default: 60000ms).
     - :multiplier - Number. Backoff multiplier (default: 2.0).

   Returns:
   Result of f.

   Throws:
   ExceptionInfo if all retries exhausted."
  ([f] (with-retry f {}))
  ([f {:keys [max-retries initial-delay-ms max-delay-ms multiplier router-handles-rate-limit?]
       :or {max-retries 5
            initial-delay-ms 1000
            max-delay-ms 60000
            multiplier 2.0}}]
   (loop [attempt 1
          delay-ms initial-delay-ms]
     (let [result (try
                    {:success (f)}
                    (catch Exception e
                      (let [ex-data-map (ex-data e)
                            status (:status ex-data-map)
                            retryable-status? (and (contains? RETRYABLE_STATUS_CODES status)
                                                (not (and router-handles-rate-limit?
                                                       (= 429 status))))
                            retryable-conn? (and (not (stream-output-started? e))
                                              (retryable-exception? e))
                            can-retry? (< attempt (long max-retries))]
                        (if (and (or retryable-status? retryable-conn?) can-retry?)
                          {:retry true :error e :status status
                           :reason (if retryable-conn? :connection-error :http-status)}
                          {:error e}))))]
       (cond
         (:success result) (:success result)
         (:retry result) (do
                           (trove/log! {:level :warn :id ::http-retry
                                        :data (log-data {:attempt attempt
                                                         :reason (:reason result)
                                                         :delay-ms (long delay-ms)
                                                         :status (:status result)
                                                         :error (ex-message (:error result))})
                                        :msg "retrying transient HTTP failure"})
                           (Thread/sleep (long delay-ms))
                           (recur (inc attempt)
                             (min (* (double delay-ms) (double multiplier)) (double max-delay-ms))))
         :else (throw (:error result)))))))

(def ^:private content-block-types
  #{"text" "output_text"})

(def ^:private reasoning-block-types
  #{"thinking"
    "reasoning"
    "reasoning_text"
    "reasoning_summary"
    "reasoning_summary_text"
    "summary_text"})

(defn- content-part-text [part]
  (cond
    ;; Keep strings VERBATIM, including whitespace-only ones. Dropping blank
    ;; parts glues/loses source: a content array like
    ;; ["def f():" "\n    " "return 1"] must round-trip its newline + indent,
    ;; not collapse to "def f():return 1". Callers that need a presence check
    ;; still guard with `str/blank?` on the joined result.
    (string? part)
    part

    (map? part)
    (let [type (:type part)]
      (cond
        (content-block-types type)
        (some-> (or (:text part) (:delta part)) content-part-text)

        (= "message" type)
        (some-> (:content part) content-part-text)

        (nil? type)
        (some-> (:text part) content-part-text)

        :else nil))

    (sequential? part)
    (let [s (->> part (keep content-part-text) (str/join ""))]
      (when-not (str/blank? s) s))

    :else nil))

(defn- reasoning-part-text [part]
  (cond
    (string? part)
    (when-not (str/blank? part) part)

    (map? part)
    (let [type (:type part)]
      (cond
        (reasoning-block-types type)
        (or (some-> (:thinking part) reasoning-part-text)
          (some-> (:summary part) reasoning-part-text)
          (some-> (:content part) reasoning-part-text)
          (some-> (or (:text part) (:delta part)) reasoning-part-text))

        (= "message" type)
        (some-> (:content part) reasoning-part-text)

        (nil? type)
        (or (some-> (:thinking part) reasoning-part-text)
          (some-> (:summary part) reasoning-part-text)
          (some-> (or (:text part) (:delta part)) reasoning-part-text))

        :else nil))

    (sequential? part)
    (let [s (->> part
              (keep reasoning-part-text)
              (remove str/blank?)
              (str/join "\n\n"))]
      (when-not (str/blank? s) s))

    :else nil))

(defn- nonblank-str [s]
  (when-not (str/blank? (or s ""))
    s))

(defn- streaming-reasoning-delta-text
  "Streaming reasoning delta. Keep exact strings, incl whitespace-only
   tokens; dropping them glues reasoning words (`pass2`, `passesI`)."
  [part]
  (if (string? part)
    part
    (reasoning-part-text part)))

(defn- content-blocks-text [blocks]
  ;; Concatenate the text parts of ONE assistant message VERBATIM. These are
  ;; provider-side chunks of a single message body, not separate lines - the
  ;; model's own newlines already live inside the parts. Joining with "\n"
  ;; corrupted multi-part bodies (notably lenient code mode), splitting every
  ;; part onto its own line, e.g. `foo = bar(baz)` -> `foo\n=\nbar(baz)`.
  (let [s (->> blocks (keep content-part-text) (str/join ""))]
    (when-not (str/blank? s) s)))

(defn- reasoning-blocks-text [blocks]
  (let [s (->> blocks
            (filter #(reasoning-block-types (:type %)))
            (keep reasoning-part-text)
            (str/join "\n"))]
    (when-not (str/blank? s) (str/trimr s))))

(defn- response-output-text [response]
  (->> (:output response)
    (keep (fn [item]
            (case (:type item)
              "message"     (content-blocks-text (:content item))
              "output_text" (content-part-text item)
              nil)))
    (remove str/blank?)
    (str/join "\n")))

(defn- response-output-reasoning [response]
  (->> (:output response)
    (keep (fn [item]
            (case (:type item)
              "reasoning" (or (some-> (:content item) reasoning-part-text)
                            (some-> (:summary item) reasoning-part-text)
                            (reasoning-part-text item))
              "message"   (reasoning-blocks-text (:content item))
              nil)))
    (remove str/blank?)
    (str/join "\n\n")))

(defn- reasoning-item-state [item]
  (when (= "reasoning" (:type item))
    (cond-> {:type "reasoning"
             :raw-item item}
      (:id item) (assoc :id (:id item))
      (:status item) (assoc :status (:status item))
      (seq (:summary item)) (assoc :summary (:summary item))
      (not (str/blank? (or (reasoning-part-text (:summary item)) "")))
      (assoc :summary-text (reasoning-part-text (:summary item)))
      (:content item) (assoc :content (:content item))
      (not (str/blank? (or (reasoning-part-text (:content item)) "")))
      (assoc :content-text (reasoning-part-text (:content item)))
      (:encrypted_content item) (assoc :encrypted-content (:encrypted_content item)))))

(defn- reasoning-item-state-key [item]
  (or (:id item)
    (:encrypted-content item)
    (get-in item [:raw-item :id])
    (get-in item [:raw-item :encrypted_content])
    (:summary-text item)
    (:content-text item)
    (pr-str item)))

(defn- merge-reasoning-item-state [a b]
  (cond
    (nil? a) b
    (nil? b) a
    :else
    (let [raw (merge (:raw-item a) (:raw-item b))]
      (cond-> (merge a b)
        (seq raw) (assoc :raw-item raw)))))

(defn- dedupe-reasoning-items [items]
  (let [{:keys [order by-key]}
        (reduce (fn [{:keys [order by-key]} item]
                  (if item
                    (let [k (reasoning-item-state-key item)]
                      {:order (cond-> order (not (contains? by-key k)) (conj k))
                       :by-key (update by-key k merge-reasoning-item-state item)})
                    {:order order :by-key by-key}))
          {:order [] :by-key {}}
          items)]
    (mapv by-key order)))

(defn- merge-provider-state
  "Provider-aware aggregator. The streaming pipeline merges every
   `:provider-state` event coming out of `delta-fn` into a single
   running map; the merge strategy depends on which provider populated
   it. OpenAI Responses dedupes `:reasoning-items` by id; Anthropic
   appends finished content blocks to `:blocks` (one block per
   `content_block_stop` event); plain providers fall back to a flat
   merge."
  [a b]
  (cond
    (nil? a) b
    (nil? b) a
    :else
    (let [provider (or (:provider b) (:provider a))]
      (case provider
        :openai-responses
        (let [items (dedupe-reasoning-items
                      (concat (:reasoning-items a) (:reasoning-items b)))]
          (cond-> (merge a b)
            (seq items) (assoc :reasoning-items items)))

        :anthropic
        (let [blocks (vec (concat (or (:blocks a) []) (or (:blocks b) [])))]
          (cond-> (merge a b)
            (seq blocks) (assoc :blocks blocks)))

        (merge a b)))))

(defn- reasoning-item-provider-state [item]
  (when-let [state (reasoning-item-state item)]
    {:provider :openai-responses
     :reasoning-items [state]}))

(defn- openai-responses-state
  "Builds the OpenAI Responses preserved-thinking state from the
   response's `:output` array. The result is exposed under
   `:provider-state` for diagnostic / fallback uses; the canonical
   replay path lifts each reasoning item into a `{:type \"thinking\"}`
   block on `:assistant-message` so callers don't have to touch this
   shape directly."
  [response]
  (let [items (dedupe-reasoning-items
                (keep reasoning-item-state (:output response)))]
    (when (seq items)
      {:provider :openai-responses
       :reasoning-items items})))

(defn- normalize-openai-usage
  "Phase A: alias of `usage/openai-canonical`. Kept as a private name
   so the dozens of `:api-usage (normalize-openai-usage ...)` call
   sites can stay one-liners. Every entry point now emits the canonical
   `{:input-tokens TOTAL :input-tokens-details {:regular :cache-write
   :cache-read} ...}` shape."
  [usage]
  (usage/openai-canonical usage))

;; =============================================================================
;; LLM API
;; =============================================================================

;; =============================================================================
;; OpenAI Responses ↔ canonical converters
;;
;; Symmetric pair with the Anthropic converters above; pi-style
;; canonical thinking blocks are the single caller-facing shape, the
;; wire form is API-specific. Reasoning items live as standalone
;; `:input` entries on the wire (NOT inline content) so the message
;; expansion below lifts thinking blocks out of `:content` and
;; positions them right before their parent assistant message.
;; =============================================================================

(defn- responses-reasoning-item->canonical-thinking-block
  "Lifts one OpenAI Responses reasoning item (svar's deduped form, as
   produced by `reasoning-item-state`) into svar's canonical thinking
   block. The full raw item is JSON-encoded under `:thinking-signature`
   so a round-trip can re-emit the exact wire shape - including ids,
   encrypted_content, raw summary parts - byte-for-byte."
  [{:keys [summary-text content-text raw-item] :as item}]
  {:type "thinking"
   :thinking (or summary-text content-text "")
   :thinking-signature (json/write-json-str (or raw-item item))
   :redacted? false})

(defn- canonical-thinking-block->responses-reasoning-item
  "Decodes a canonical thinking block's `:thinking-signature` back into
   the OpenAI Responses wire-shape map the API expects in `:input`.
   Falls back to a synthesized minimal item if the signature is not
   parseable JSON - keeps round-trips robust without leaking thinking
   content the model would refuse on replay."
  [{:keys [thinking thinking-signature]}]
  (or (when (and (string? thinking-signature) (not (str/blank? thinking-signature)))
        (try (json/read-json thinking-signature :key-fn keyword)
             (catch Exception _ nil)))
    (when (and (string? thinking) (not (str/blank? thinking)))
      {:type "reasoning"
       :summary [{:type "summary_text" :text thinking}]})))

(defn- responses-extract-assistant-message
  "Builds the canonical `:assistant-message` for an OpenAI Responses
   call from a deduped reasoning-items vec plus the visible text. Used
   from both the streaming aggregator and any non-streaming Responses
   extract path so both surfaces produce the same canonical shape."
  [reasoning-items visible-text]
  (let [thinking-blocks (mapv responses-reasoning-item->canonical-thinking-block
                          (or reasoning-items []))
        text-blocks     (when (and (string? visible-text) (not (str/blank? visible-text)))
                          [{:type "text" :text visible-text}])
        content         (vec (concat thinking-blocks text-blocks))]
    (when (seq content)
      {:role "assistant" :content content})))

(defn- openai-chat-canonical-assistant-message
  "Builds the canonical `:assistant-message` for an OpenAI-compatible
   chat response. When the response carried `reasoning_content`
   (z.ai GLM Preserved Thinking, OpenRouter, etc.) it is captured as a
   canonical thinking block; the same text doubles as the
   `:thinking-signature` because z.ai's preservation contract is
   verbatim text echo with no separate HMAC. Caller appends the
   resulting message to `:messages` on the next call so the wire
   serializer can re-emit `reasoning_content` faithfully.

   Returns nil when there is no usable assistant content - callers
   then skip the replay step."
  [{:keys [content reasoning-content]}]
  (let [text? (and (string? content) (not (str/blank? content)))
        rc?   (and (string? reasoning-content) (not (str/blank? reasoning-content)))]
    (when (or text? rc?)
      (let [thinking-block (when rc?
                             {:type "thinking"
                              :thinking reasoning-content
                              :thinking-signature reasoning-content
                              :redacted? false})
            text-block     (when text?
                             {:type "text" :text content})]
        {:role "assistant"
         :content (vec (keep identity [thinking-block text-block]))}))))

(defn- extract-response-data
  "Extracts content, reasoning, and usage data from an OpenAI-compatible
   response envelope. Accepts the map returned by `http-post!`:
     {:parsed <parsed-body> :raw-body <string> :url <str> :status <int>}

   Handles standard chat-completions envelopes, block-array content,
   and Responses-style `:output` fallbacks some gateways only surface on
   the terminal payload.

   Returns: {:content :reasoning :provider-state :assistant-message
             :api-usage :http-response},
   where :http-response is the ORIGINAL envelope preserved so a downstream
   error site (e.g. the empty-content throw in `ask!*`) can attach the
   full raw response to its ex-data for triage. Callers that only want the
   content/reasoning can just destructure the three leaf keys.

   `:assistant-message` is populated when the response carried a
   server-issued preserved-reasoning channel (e.g. z.ai GLM
   `reasoning_content`, OpenAI Responses reasoning items). Append it
   to `:messages` on the next call to keep the thinking session
   active."
  [envelope]
  (let [response           (:parsed envelope)
        message            (get-in response [:choices 0 :message])
        raw-content        (:content message)
        message-reasoning-content (:reasoning_content message)
        raw-reasoning      (or message-reasoning-content
                             (:reasoning message)
                             (:reasoning_text message)
                             (:reasoning_summary message)
                             (:reasoning response)
                             (:reasoning_text response)
                             (:reasoning_summary response))
        block-content      (when (sequential? raw-content)
                             (content-blocks-text raw-content))
        block-reasoning    (when (sequential? raw-content)
                             (reasoning-blocks-text raw-content))
        fallback-content   (response-output-text response)
        fallback-reasoning (response-output-reasoning response)
        provider-state     (openai-responses-state response)
        content            (cond
                             (string? raw-content) raw-content
                             (not (str/blank? (or block-content ""))) block-content
                             (not (str/blank? (or fallback-content ""))) fallback-content
                             :else nil)
        reasoning          (or (reasoning-part-text raw-reasoning)
                             block-reasoning
                             (when-not (str/blank? fallback-reasoning) fallback-reasoning))
        ;; Two extract paths share this fn: chat-completions (z.ai
        ;; GLM, OpenRouter, plain OpenAI Chat - use
        ;; `openai-chat-canonical-assistant-message`) and OpenAI
        ;; Responses (rich `:output` array with reasoning items - use
        ;; `responses-extract-assistant-message`). Provider-state
        ;; presence picks the Responses path so we don't double-build
        ;; on chat-completions where it stays nil.
        canonical-msg      (or (when provider-state
                                 (responses-extract-assistant-message
                                   (:reasoning-items provider-state) content))
                             (openai-chat-canonical-assistant-message
                               {:content content
                                :reasoning-content (when (string? message-reasoning-content)
                                                     message-reasoning-content)}))]
    (cond-> {:content        content
             :reasoning      reasoning
             :provider-state provider-state
             :api-usage      (normalize-openai-usage (get-in response [:usage]))
             :http-response  envelope}
      canonical-msg (assoc :assistant-message canonical-msg))))

(defn- responses-text-content [content]
  (cond
    (string? content)
    content

    (sequential? content)
    (->> content
      (keep (fn [block]
              (cond
                (string? block) block
                (= "text" (:type block)) (:text block)
                (= "input_text" (:type block)) (:text block)
                (= "output_text" (:type block)) (:text block)
                :else nil)))
      (str/join "\n"))

    :else
    (str content)))

(defn- responses-content-blocks [role content]
  (let [text-type (if (= role "assistant") "output_text" "input_text")]
    (cond
      (string? content)
      [{:type text-type :text content}]

      (sequential? content)
      (->> content
        (keep (fn [block]
                (cond
                  (string? block)
                  {:type text-type :text block}

                  (contains? #{"text" "input_text" "output_text"} (:type block))
                  {:type text-type :text (:text block)}

                  (= "image_url" (:type block))
                  (when-let [url (get-in block [:image_url :url])]
                    {:type "input_image" :image_url url})

                  :else nil)))
        vec)

      :else
      [{:type text-type :text (str content)}])))

(defn- normalize-text-verbosity [v]
  (let [raw (cond
              (keyword? v) (name v)
              (string? v)  v
              :else        nil)
        s   (some-> raw str/trim str/lower-case)]
    (case s
      ("low" "medium" "high") s
      nil)))

(defn- response-format->text-format [response-format]
  (when response-format
    (case (:type response-format)
      "json_object" {:type "json_object"}
      "json_schema" {:type "json_schema"
                     :name (:name response-format)
                     :schema (:schema response-format)
                     :strict (:strict response-format)}
      nil)))

(defn- responses-message-input-entries
  "Expands one canonical message into the OpenAI Responses `:input`
   entries it generates: thinking blocks become standalone `reasoning`
   items emitted BEFORE the message itself, the rest of the content
   becomes the message entry. System messages are filtered out
   upstream; this helper handles user/assistant only."
  [{:keys [role content]}]
  (let [normalized (normalize-content content)
        [thinking-blocks rest-blocks] (if (= role "assistant")
                                        [(filterv canonical-thinking-block? normalized)
                                         (filterv (complement canonical-thinking-block?) normalized)]
                                        [nil normalized])
        reasoning-items (when (= role "assistant")
                          (vec (keep canonical-thinking-block->responses-reasoning-item thinking-blocks)))
        message-entry {:role    (if (= role "assistant") "assistant" "user")
                       :content (responses-content-blocks role rest-blocks)}]
    (vec (concat reasoning-items [message-entry]))))

(defn- build-openai-responses-request-body [messages model extra-body]
  (let [system-text (->> messages
                      (filter #(= "system" (:role %)))
                      (map (comp responses-text-content :content))
                      (remove str/blank?)
                      (str/join "\n\n"))
        ;; Canonical thinking blocks live inline on assistant messages.
        ;; Each one is hoisted out as a `reasoning` input entry placed
        ;; right before its parent message - the OpenAI Responses API
        ;; pairs reasoning items with the assistant turn that produced
        ;; them by ordering, not by id, so positional faithfulness here
        ;; is what keeps the thinking session valid.
        input       (->> messages
                      (remove #(= "system" (:role %)))
                      (mapcat responses-message-input-entries)
                      vec)
        effort      (:reasoning_effort extra-body)
        text-format (or (get-in extra-body [:text :format])
                      (response-format->text-format (:response_format extra-body)))
        text-verbosity (or (normalize-text-verbosity (:verbosity extra-body))
                         (normalize-text-verbosity (get-in extra-body [:text :verbosity])))
        max-output-tokens (or (:max_output_tokens extra-body) (:max_tokens extra-body))
        base-extra  (dissoc extra-body :reasoning_effort :response_format :verbosity :provider-state
                      :max_tokens :max_output_tokens)
        reasoning   (cond-> (:reasoning base-extra)
                      effort (assoc :effort effort))
        base-extra* (dissoc base-extra :reasoning)
        base-text   (or (:text base-extra) {})
        base-text   (cond-> base-text text-verbosity (assoc :verbosity text-verbosity))]
    (cond-> {:model model
             :input input}
      (not (str/blank? system-text)) (assoc :instructions system-text)
      max-output-tokens (assoc :max_output_tokens max-output-tokens)
      (seq reasoning) (assoc :reasoning reasoning)
      text-format (assoc :text (assoc base-text :format text-format))
      (seq base-extra*) (merge (cond-> base-extra* text-format (dissoc :text))))))

(defn- openai-chat-split-thinking
  "Splits a normalized canonical content vec into `[thinking-blocks
   non-thinking-blocks]`. OpenAI-style chat completions (z.ai included)
   carry preserved reasoning under a per-message `reasoning_content`
   string, not as inline content blocks - so we hoist the canonical
   thinking blocks out of the wire content and emit their text under
   the dedicated field instead."
  [blocks]
  [(filterv canonical-thinking-block? blocks)
   (filterv (complement canonical-thinking-block?) blocks)])

(defn- openai-chat-reasoning-content
  "Concatenates the reasoning text of every canonical thinking block on
   the message, in order, into the single string that z.ai / OpenRouter
   expect under `reasoning_content`.

   The `:thinking-signature` slot is used ONLY when it equals the
   visible `:thinking` — that identity is z.ai's own capture shape
   (exact-text echo contract, both fields get the same provider-issued
   text). For FOREIGN-born blocks the signature is an opaque payload
   that must never ride a chat wire as text: Anthropic's HMAC base64
   (leaked as visible 'reasoning' when a mid-session provider fallback
   re-routed Anthropic blocks to GLM — the blob then echoes back as
   reasoning_content and renders in the client as thinking), OpenAI
   Responses' JSON reasoning item, and Anthropic redacted-thinking's
   ENCRYPTED data (never to be sent to a third-party provider at all).
   Those replay their visible `:thinking` text instead; redacted blocks
   are dropped."
  [thinking-blocks]
  (let [parts (->> thinking-blocks
                (map (fn [{:keys [thinking-signature thinking redacted?]}]
                       (cond
                         redacted? nil
                         (and (string? thinking-signature)
                           (= thinking-signature thinking)) thinking-signature
                         :else thinking)))
                (remove str/blank?))]
    (when (seq parts)
      (str/join "\n" parts))))

(defn- echo-reasoning-disabled?
  "Read once at request-build time. When `SVAR_DISABLE_REASONING_ECHO`
   env is set to a truthy value (\"1\", \"true\", \"yes\"), svar drops the
   client-side `reasoning_content` echo entirely — prior assistant
   thinking is stripped from the wire body, and nothing is hoisted into
   the `reasoning_content` field.

   Intended for **debugging only**. Empirical A/B sweep on Z.ai
   coding plan / GLM-5.1 (vis E2E-C, n=3 per condition):
     :all (default)  6.67 iter mean, 5,624 thinking chars mean
     :none           7.00 iter mean, 6,622 thinking chars mean
   = no measurable speed-up from disabling echo (within variance), and
   ~15% MORE thinking compute per run because the model re-derives
   context every turn instead of carrying it forward. Keep `:all` on
   in production; flip the flag only when you need to bisect a model
   regression that you suspect involves stale carryover.

   Earlier experiments also tried a `SVAR_REASONING_ECHO_LAST_N=K`
   partial-echo knob (\"keep only last N thinking turns\"). It made
   things WORSE — a chopped reasoning chain confused the model more
   than either full echo or no echo (E2E-C / GLM-5.1: last-1 = 14
   iter, last-2 = 10 iter, last-3 = 8 iter, full = 6-8 iter). The
   knob was removed; do not reintroduce."
  []
  (boolean
    (when-let [v (System/getenv "SVAR_DISABLE_REASONING_ECHO")]
      (contains? #{"1" "true" "yes" "on"} (str/lower-case v)))))

(defn- build-request-body
  "Builds the request body for an OpenAI-compatible chat completion API.

   Multi-block content (`{:type \"text\" :text ...}` arrays, image blocks)
   passes through unchanged. svar-internal markers like `:svar/cache`
   are stripped - OpenAI implicit caching benefits from a stable prefix
   without any client signal.

   Canonical `{:type \"thinking\"}` blocks on assistant messages are
   hoisted out of `:content` and re-emitted under the dedicated
   `:reasoning_content` field so providers that speak the
   preserved-thinking convention (z.ai GLM, OpenRouter) keep the
   model's thinking session active across calls.

   ESCAPE HATCH: setting `SVAR_DISABLE_REASONING_ECHO=1` skips the
   thinking-block hoisting entirely — prior reasoning is dropped from
   the wire, useful when comparing weaker reasoning models with vs
   without preserved thinking (a glm-5.1 turn carrying 5K tokens of its
   own stale rumination drifts; the same turn without echo stays
   focused).

   Params:
   `messages` - Vector. Chat messages.
   `model` - String. Model name.
   `extra-body` - Map, optional. Additional params to merge into the request body
                  (e.g. {:reasoning_effort \"medium\"} for reasoning-capable providers)."
  ([messages model]
   (build-request-body messages model nil))
  ([messages model extra-body]
   (let [echo-off? (echo-reasoning-disabled?)
         processed (mapv (fn [{:keys [role content] :as m}]
                           (let [normalized (normalize-content content)
                                 [thinking-blocks rest-blocks]
                                 (if (= role "assistant")
                                   (openai-chat-split-thinking normalized)
                                   [nil normalized])
                                 reasoning-content
                                 (when (and (not echo-off?) (seq thinking-blocks))
                                   (openai-chat-reasoning-content thinking-blocks))
                                 base (-> m
                                        (assoc :content (openai-content rest-blocks))
                                        (cond-> (= role "system") (assoc :role "system")))]
                             (cond-> base
                               reasoning-content (assoc :reasoning_content reasoning-content))))
                     messages)]
     (cond-> {:model model :messages processed}
       (seq extra-body) (merge extra-body)))))

(defn- sanitize-messages-for-logging
  "Removes base64 image data from messages for safe logging.

   Replaces large base64 strings with placeholder to avoid huge log entries.

   Params:
   `messages` - Vector. Chat messages.

   Returns:
   Vector. Messages with base64 data replaced by placeholder."
  [messages]
  (mapv (fn [msg]
          (if (vector? (:content msg))
            ;; Multimodal content - filter out image data
            (update msg :content
              (fn [content-blocks]
                (mapv (fn [block]
                        (if (= "image_url" (:type block))
                                ;; Replace base64 data with placeholder
                          (let [url (get-in block [:image_url :url] "")]
                            (if (str/starts-with? url "data:")
                              (assoc-in block [:image_url :url]
                                (str (first (str/split url #",")) ",<BASE64_DATA_TRUNCATED>"))
                              block))
                          block))
                  content-blocks)))
            ;; Text-only content - pass through
            msg))
    messages))

(def ^:private API_KEY_ERROR_PATTERNS
  "Known error patterns that indicate API key configuration issues."
  [{:pattern #"(?i)no.connected.db"
    :message "API key is invalid or not configured. Check your API key."}
   {:pattern #"(?i)invalid.api.key"
    :message "API key is invalid. Check your API key."}
   {:pattern #"(?i)authentication|unauthorized|api.key"
    :message "API authentication failed. Check your API key."}])

(defn- detect-api-key-error
  "Checks if an error response indicates an API key configuration issue."
  [response-body]
  (when response-body
    (some (fn [{:keys [pattern message]}]
            (when (re-find pattern response-body)
              message))
      API_KEY_ERROR_PATTERNS)))

(declare extract-stream-delta http-post-stream!)

(defn responses-url
  "Builds an OpenAI Responses-style endpoint URL.

   `responses-path` defaults to `/responses`. Handles callers that pass a
   root base URL OR a chat-completions URL that needs path translation."
  ([base-url] (responses-url base-url "/responses"))
  ([base-url responses-path]
   (let [base       (or (some-> base-url str str/trim not-empty) "")
         path       (str (when-not (str/starts-with? responses-path "/") "/")
                      responses-path)
         normalized (-> base
                      (str/replace #"/+$" "")
                      (str/replace #"/chat/completions$" ""))
         parent     (when-let [[_ p] (re-matches #"(.+)/[^/]+" path)] p)]
     (cond
       (str/blank? normalized) path
       (str/ends-with? normalized path) normalized
       (and parent (not= parent "/") (str/ends-with? normalized parent))
       (str normalized (subs path (count parent)))
       :else (str normalized path)))))

(def ^:dynamic *stream-semantic-timeout-ms*
  router/DEFAULT_SEMANTIC_TIMEOUT_MS)

(def ^:dynamic *cancel-fn*
  "Optional no-arg predicate for caller-driven cancellation. When bound to
   a fn that returns truthy, an in-flight streaming call aborts ASAP — a
   blocking JDK socket read does NOT respond to `Thread.interrupt()`, so
   the only fast lever post-headers is closing the body `InputStream`. A
   daemon watchdog polls this predicate and, on fire:
     - closes the SSE `InputStream` (unblocks a parked `.readLine`), and
     - interrupts the caller thread (unparks the pre-headers
       `CompletableFuture.get` and any retry backoff sleep).
   The reader loop also checks it between lines so an actively-streaming
   response breaks within one delta. Surfaces `:svar.core/stream-cancelled`.
   nil = no cancellation hook (default — zero overhead, no watchdog)."
  nil)

(def ^:private stream-finalization-error-types
  #{:svar.core/stream-incomplete
    :svar.core/stream-truncated
    :svar.core/stream-semantic-timeout
    ;; Caller cancellation must propagate verbatim (not be reclassified as
    ;; a connection error or retried) — it's a clean terminal outcome.
    :svar.core/stream-cancelled})

(defn- stream-finalization-error? [e]
  (contains? stream-finalization-error-types (:type (ex-data e))))

(defn openai-responses-completion
  "Low-level OpenAI Responses transport.

   Caller owns request-body construction. svar handles URL resolution,
   HTTP POST, SSE accumulation, response extraction, reasoning fallback,
   and normalized return shape.

   Params:
   `request-body` - Map. Responses API request body.
   `opts` - Map:
     - :api-key         - Bearer token.
     - :base-url        - Provider base URL.
     - :responses-path  - Endpoint path, default `/responses`.
     - :headers         - Extra headers merged over the default auth headers.
     - :timeout-ms      - Request timeout.
     - :on-chunk        - Optional streaming callback.

   Returns same normalized shape as `chat-completion`:
   {:content :reasoning :provider-state :api-usage :http-response}"
  [request-body {:keys [api-key base-url responses-path headers timeout-ms ttft-timeout-ms idle-timeout-ms semantic-timeout-ms on-chunk]
                 :or   {responses-path "/responses"
                        timeout-ms router/DEFAULT_TIMEOUT_MS
                        ttft-timeout-ms router/DEFAULT_TTFT_TIMEOUT_MS
                        idle-timeout-ms router/DEFAULT_IDLE_TIMEOUT_MS
                        semantic-timeout-ms router/DEFAULT_SEMANTIC_TIMEOUT_MS}}]
  (let [url           (responses-url base-url responses-path)
        model         (:model request-body)
        codex?        (= "/codex/responses" responses-path)
        stream?       (or on-chunk codex?)
        request-body  (cond-> request-body
                        codex? (dissoc :max_tokens :max_output_tokens)
                        stream? (assoc :stream true))
        http-headers  (merge {"Authorization" (str "Bearer " api-key)
                              "Content-Type"  "application/json"}
                        (when stream? {"Accept" "text/event-stream"})
                        headers)
        llm-request   {:model model :base-url base-url :responses-path responses-path}]
    (trove/log! {:level :info
                 :data (log-data {:model model
                                  :url url
                                  :timeout-ms timeout-ms
                                  :ttft-timeout-ms ttft-timeout-ms
                                  :idle-timeout-ms idle-timeout-ms
                                  :semantic-timeout-ms semantic-timeout-ms
                                  :stream? (boolean stream?)})
                 :msg "responses request dispatched"})
    (try
      (if stream?
        (binding [*stream-semantic-timeout-ms* semantic-timeout-ms]
          (http-post-stream! url request-body http-headers timeout-ms ttft-timeout-ms idle-timeout-ms extract-stream-delta
            (when on-chunk
              (fn [{:keys [content-acc reasoning-acc provider-state api-usage]}]
                (on-chunk {:content content-acc :reasoning (nonblank-str reasoning-acc)
                           :provider-state provider-state
                           :api-usage api-usage :done? false})))))
        (extract-response-data (http-post! url request-body http-headers timeout-ms)))
      (catch Exception e
        (if (stream-finalization-error? e)
          (throw e)
          (let [ex-data-map   (ex-data e)
                response-body (when (string? (:body ex-data-map)) (:body ex-data-map))
                api-key-error (detect-api-key-error response-body)
                base-message  (http-error-message e)
                error-message (if api-key-error
                                (str api-key-error " (Original: " base-message ")")
                                base-message)]
            (when api-key-error
              (trove/log! {:level :error :id ::api-key-error
                           :data (log-data {:api-key-error api-key-error
                                            :api-key-length (count api-key)
                                            :api-key-prefix (when api-key (subs api-key 0 (min 8 (count api-key))))})
                           :msg "detected API key configuration failure"}))
            (anomaly/fault! error-message
              (cond-> (merge (dissoc ex-data-map :body)
                        {:type :svar.core/http-error
                         :llm-request llm-request})
                response-body (assoc :body (truncate-error-body response-body))
                api-key-error (assoc :api-key-error api-key-error)))))))))

(defn- chat-completion-with-retry
  "Calls the LLM API with exponential backoff retry for rate limits."
  [messages model api-key base-url retry-opts timeout-ms extra-body api-style]
  (let [api-style    (or api-style :openai-compatible-chat)
        provider-id  (:provider-id retry-opts)
        llm-headers  (:llm-headers retry-opts)
        anthropic-oauth? (and (= api-style :anthropic) (anthropic-oauth-token? api-key))
        request-body (if (= api-style :anthropic)
                       (build-anthropic-request-body messages model extra-body
                         {:anthropic-oauth? anthropic-oauth?})
                       (build-request-body messages model extra-body))
        input-tokens (router/count-messages model messages)
        chat-url     (make-chat-url base-url api-style)
        headers      (request-headers api-style api-key provider-id messages llm-headers)
        extract-fn   (if (= api-style :anthropic) extract-anthropic-response-data extract-response-data)
        _ (trove/log! {:level :info
                       :data (log-data {:model model
                                        :input-tokens input-tokens
                                        :max-output-tokens (:max_tokens extra-body)
                                        :timeout-ms timeout-ms})
                       :msg "HTTP request dispatched"})]
    (try
      (with-retry
        (fn []
          (try
            ;; `http-post!` returns a full envelope {:parsed :raw-body :url
            ;; :status}. `extract-fn` knows how to unwrap it and, crucially,
            ;; re-emit it under :http-response so callers higher up can
            ;; attach the raw response to error ex-data (see `ask!*`).
            (let [envelope (http-post! chat-url request-body headers timeout-ms)]
              (extract-fn envelope))
            (catch clojure.lang.ExceptionInfo e
              (when-let [body (:body (ex-data e))]
                (trove/log! {:level :warn :id ::llm-error-body
                             :data (log-data {:status (:status (ex-data e))
                                              :body-snippet (if (string? body)
                                                              (subs body 0 (min 200 (count body)))
                                                              (str body))})
                             :msg "captured upstream error body snippet"}))
              (throw e))))
        retry-opts)
      (catch Exception e
        ;; Check for API key configuration errors
        (let [ex-data-map (ex-data e)
              response-body (:body ex-data-map)
              api-key-error (detect-api-key-error response-body)
              sanitized-request {:model model
                                 :base-url base-url
                                 :timeout-ms timeout-ms
                                 :api-key-length (count api-key)
                                 :api-key-prefix (when api-key (subs api-key 0 (min 8 (count api-key))))
                                 :messages (sanitize-messages-for-logging messages)}
              base-message  (http-error-message e)
              error-message (if api-key-error
                              (str api-key-error " (Original: " base-message ")")
                              base-message)]
          (when api-key-error
            (trove/log! {:level :error :id ::api-key-error
                         :data (log-data {:api-key-error api-key-error
                                          :api-key-length (count api-key)
                                          :api-key-prefix (when api-key (subs api-key 0 (min 8 (count api-key))))})
                         :msg "detected API key configuration failure"}))
          (anomaly/fault! error-message
            (cond-> (merge (ex-data e) {:type :svar.core/http-error
                                        :llm-request sanitized-request})
              api-key-error (assoc :api-key-error api-key-error))))))))

;; =============================================================================
;; SSE Streaming
;; =============================================================================

(def ^:private sse-done ::sse-done)

(defn- parse-sse-data
  "Parses an SSE data payload. Returns parsed JSON map, `sse-done`, or nil."
  [^String data-str]
  (let [trimmed (str/trim data-str)]
    (cond
      (= trimmed "[DONE]") sse-done
      (str/blank? trimmed) nil
      :else
      (try
        (json/read-json trimmed :key-fn keyword)
        (catch Exception _ nil)))))

(defn- sse-field-line
  "Parses one SSE line into `{:field f :value v}`. Comments return nil.
   Per SSE spec, only one optional ASCII space after `:` is stripped;
   tabs are preserved."
  [^String line]
  (when-not (str/starts-with? line ":")
    (let [idx (.indexOf line ":")]
      (if (neg? idx)
        {:field line :value ""}
        (let [raw (subs line (inc idx))]
          {:field (subs line 0 idx)
           :value (if (str/starts-with? raw " ") (subs raw 1) raw)})))))

(defn- parse-sse-event
  "Parses aggregated SSE event fields. Joins multi-line `data:` with LF
   and injects `event:` as `:type` when provider data omits one."
  [event-type data-lines]
  (when (seq data-lines)
    (let [parsed (parse-sse-data (str/join "\n" data-lines))]
      (cond
        (= sse-done parsed) sse-done
        (and (map? parsed) (seq event-type))
        (cond-> (assoc parsed :sse-event-type event-type)
          (nil? (:type parsed)) (assoc :type event-type))
        :else parsed))))

(defn- stream-event-type
  [chunk]
  (or (:type chunk)
    (:object chunk)))

(defn- stream-finish-reason
  [chunk]
  (or (:finish_reason chunk)
    (:finish-reason chunk)
    (get-in chunk [:choices 0 :finish_reason])
    (get-in chunk [:choices 0 :finish-reason])
    ;; Anthropic message_delta: {:delta {:stop_reason "end_turn"|"max_tokens"|…}}.
    ;; Without it every Anthropic stream finalized with finish-reason nil —
    ;; a max_tokens truncation was indistinguishable from a clean end_turn
    ;; when diagnosing empty-content responses (vis session 372994ce).
    (get-in chunk [:delta :stop_reason])
    (get-in chunk [:response :status])
    (:status chunk)))

(defn- stream-semantic-event?
  "True when parsed stream event means model/progress, not transport keepalive."
  [parsed extracted content-piece reasoning-piece]
  (let [event-type (stream-event-type parsed)
        finish-reason (stream-finish-reason parsed)]
    (boolean
      (or content-piece
        reasoning-piece
        finish-reason
        (:provider-state extracted)
        (:api-usage extracted)
        (:terminal? extracted)
        (:incomplete? extracted)
        (and event-type
          (not (contains? #{"ping" "heartbeat" "keepalive"} event-type))
          (or (str/starts-with? event-type "response.")
            (str/includes? event-type ".delta")
            (str/includes? event-type ".done")
            (str/includes? event-type "message")))))))

(defn- stream-finalization-summary
  [{:keys [terminal incomplete last-event-type last-finish-reason
           content-acc reasoning-acc response]}]
  (cond-> {:terminal? (boolean terminal)
           :terminal-kind (:kind terminal)
           :terminal-event-type (:event-type terminal)
           :last-event-type last-event-type
           :finish-reason last-finish-reason
           :incomplete? (boolean incomplete)
           :incomplete-reason (:reason incomplete)
           :content-acc-len (.length ^StringBuilder content-acc)
           :reasoning-acc-len (.length ^StringBuilder reasoning-acc)}
    response (assoc :http-status (:status response))))

(defn- extract-stream-delta
  "Extracts content and reasoning deltas from a streaming SSE chunk."
  [chunk]
  (if-let [event-type (:type chunk)]
    (cond
      (= "response.output_text.delta" event-type)
      ;; Preserve exact stream deltas. A whitespace-only delta can be
      ;; semantically required source, e.g. `:max-lines` + ` ` + `260`.
      ;; Generic text extractors drop blank strings; streaming must not.
      {:content-delta (:delta chunk)
       :reasoning-delta nil
       :content-fallback nil
       :reasoning-fallback nil
       :api-usage (normalize-openai-usage (:usage chunk))}

      (= "response.output_text.done" event-type)
      {:content-delta nil
       :reasoning-delta nil
       :content-fallback (some-> (:text chunk) content-part-text)
       :reasoning-fallback nil
       :api-usage (normalize-openai-usage (:usage chunk))}

      (= "response.reasoning_summary_part.added" event-type)
      {:content-delta nil
       :reasoning-delta (when (:svar/reasoning-summary-part-boundary? chunk) "\n\n")
       :content-fallback nil
       :reasoning-fallback nil
       :api-usage (normalize-openai-usage (:usage chunk))}

      (contains? #{"response.reasoning.delta"
                   "response.reasoning.done"
                   "response.reasoning_text.delta"
                   "response.reasoning_text.done"
                   "response.reasoning_summary.delta"
                   "response.reasoning_summary.done"
                   "response.reasoning_summary_text.delta"
                   "response.reasoning_summary_text.done"}
        event-type)
      {:content-delta nil
       :reasoning-delta (when (str/includes? event-type ".delta")
                          (some-> (:delta chunk) streaming-reasoning-delta-text))
       :content-fallback nil
       :reasoning-fallback (when (str/includes? event-type ".done")
                             (some-> (:text chunk) reasoning-part-text))
       :api-usage (normalize-openai-usage (:usage chunk))}

      (contains? #{"response.output_item.added" "response.output_item.done"}
        event-type)
      (let [item (:item chunk)]
        {:content-delta nil
         :reasoning-delta nil
         :content-fallback nil
         :reasoning-fallback (when (and (= "reasoning" (:type item))
                                     (= "response.output_item.done" event-type))
                               (reasoning-part-text item))
         :provider-state (when (= "response.output_item.done" event-type)
                           (reasoning-item-provider-state item))
         :api-usage (normalize-openai-usage (:usage chunk))})

      (contains? #{"response.completed" "response.done" "response.incomplete"}
        event-type)
      (let [response (:response chunk)]
        {:content-delta nil
         :reasoning-delta nil
         :content-fallback (response-output-text response)
         :reasoning-fallback (response-output-reasoning response)
         :provider-state (openai-responses-state response)
         :api-usage (normalize-openai-usage (or (:usage response) (:usage chunk)))
         :terminal? true
         :incomplete? (= "response.incomplete" event-type)
         :incomplete-reason (or (get-in response [:incomplete_details :reason])
                              (get-in response [:incomplete-details :reason]))})

      :else
      {:content-delta nil
       :reasoning-delta nil
       :content-fallback nil
       :reasoning-fallback nil
       :api-usage (normalize-openai-usage (:usage chunk))})
    (let [delta       (get-in chunk [:choices 0 :delta])
          raw-content (:content delta)
          reasoning   (or (:reasoning_content delta)
                        (:reasoning delta)
                        (:reasoning_text delta)
                        (:reasoning_summary delta))]
      {:content-delta (cond
                        ;; Preserve exact string deltas, including a
                        ;; single-space token between adjacent code tokens.
                        (string? raw-content) raw-content
                        (sequential? raw-content) (content-blocks-text raw-content)
                        :else nil)
       :reasoning-delta (or (streaming-reasoning-delta-text reasoning)
                          (when (sequential? raw-content)
                            (reasoning-blocks-text raw-content)))
       :content-fallback nil
       :reasoning-fallback nil
       :api-usage (normalize-openai-usage (:usage chunk))})))

(defn- append-summary-text-to-last-part [item delta]
  (let [summary (vec (or (:summary item) []))
        idx (dec (count summary))]
    (if (neg? idx)
      (assoc item :summary [{:type "summary_text" :text (or delta "")}])
      (assoc item :summary
        (update summary idx update :text str (or delta ""))))))

(defn- apply-reasoning-summary-part-done [item part]
  (if part
    (let [summary (vec (or (:summary item) []))]
      (if (seq summary)
        (assoc item :summary (assoc summary (dec (count summary)) part))
        (assoc item :summary [part])))
    (append-summary-text-to-last-part item "\n\n")))

(defn- enrich-responses-reasoning-event [current-reasoning-item event]
  (case (:type event)
    "response.output_item.added"
    (let [item (:item event)]
      (when (= "reasoning" (:type item))
        (reset! current-reasoning-item item))
      event)

    "response.reasoning_summary_part.added"
    (let [boundary? (seq (:summary @current-reasoning-item))]
      (swap! current-reasoning-item update :summary (fnil conj []) (:part event))
      (cond-> event
        boundary? (assoc :svar/reasoning-summary-part-boundary? true)))

    "response.reasoning_summary_text.delta"
    (do
      (swap! current-reasoning-item append-summary-text-to-last-part (:delta event))
      event)

    "response.reasoning_summary_part.done"
    (do
      (swap! current-reasoning-item apply-reasoning-summary-part-done (:part event))
      event)

    "response.output_item.done"
    (let [item (:item event)]
      (if (= "reasoning" (:type item))
        (let [merged (merge @current-reasoning-item item)]
          (reset! current-reasoning-item nil)
          (assoc event :item merged))
        event))

    event))

(defn- slurp-input-stream
  "Safely reads an InputStream to string. Returns nil on failure."
  [is]
  (try (slurp is) (catch Exception _ nil)))

(defn- start-ttft-watchdog!
  "Daemon thread that interrupts `caller` if `headers-received?-atom` is
   still false after `ttft-timeout-ms`. Used to bound the wait inside
   `http/post` -> `HttpClient.send` -> `CompletableFuture.get` when the
   upstream accepts the connection but never returns response headers.

   Distinct from `start-idle-stream-watchdog!`: that one closes the body
   `InputStream` (which is unavailable in this phase — the whole reason
   we need a separate watchdog). `Thread.interrupt()` on the caller is
   the lever that reaches the parked `CompletableFuture.get` cleanly:
   `Signaller.block` checks the interrupted flag on unpark and surfaces
   `InterruptedException`, which propagates out of `HttpClient.send` as
   `IOException` (wrapped by babashka.http-client as ExceptionInfo).
   Caller distinguishes via the `ttft-fired?-atom` flag we set BEFORE
   the interrupt; on a successful return the caller flips
   `headers-received?-atom` true and we exit without interrupting.

   Returns the thread so the caller can interrupt it on success."
  [^Thread caller ttft-timeout-ms headers-received?-atom ttft-fired?-atom]
  (let [;; Clamp the per-tick sleep to [100, 5000] ms so the watchdog
        ;; wakes within 5s of caller success regardless of the configured
        ;; ttft-timeout-ms. Without this a 90s timeout would park the
        ;; daemon thread for the full 90s after a healthy response
        ;; returned in 50ms, wasting a thread slot.
        check-ms    (-> (long ttft-timeout-ms) (quot 4) (max 100) (min 5000))
        deadline-ns (+ (System/nanoTime) (* (long ttft-timeout-ms) 1000000))
        runnable    (fn []
                      (try
                        (loop []
                          (Thread/sleep (long check-ms))
                          (cond
                            ;; Caller signalled success between ticks; exit
                            ;; without interrupting.
                            @headers-received?-atom nil
                            ;; Deadline crossed; recheck flag once more under
                            ;; race-window guard, then fire.
                            (>= (System/nanoTime) deadline-ns)
                            (when-not @headers-received?-atom
                              (reset! ttft-fired?-atom true)
                              (.interrupt caller))
                            :else (recur)))
                        (catch InterruptedException _ nil)
                        (catch Throwable _ nil)))
        thread      (doto (Thread. ^Runnable runnable "svar-ttft-watchdog")
                      (.setDaemon true)
                      (.start))]
    thread))

(defn- start-idle-stream-watchdog!
  "Daemon thread. Closes `stream` if `last-byte-ns-atom` stale > idle-ms.
   Reader loop bumps the atom every line; when wall-clock gap exceeds the
   threshold the close kicks the parked `.readLine` with an `IOException`
   and the surrounding catch re-throws as `:svar.core/stream-idle-timeout`.
   On normal completion the caller flips `alive?-atom` to false and
   interrupts the thread—the watchdog exits without touching the stream.

   Why a separate timer, not `HttpRequest.timeout`: the JDK request timeout
   covers headers + total wall-clock, doesn't fire on a mid-stream idle
   (HTTP/2 frames open, no body delta), and on JDK 25 + streaming bodies
   is known to miss entirely. The idle watchdog is signal-driven: stalls
   surface fast, slow-but-progressing reasoning streams (which emit deltas
   every few seconds) keep flowing.

   `on-fire` called once with elapsed-ms before the close; lets the caller
   stamp telemetry. Returns the thread for shutdown."
  [^java.io.InputStream stream idle-timeout-ms last-byte-ns-atom alive?-atom on-fire]
  (let [;; Clamp the per-tick sleep to [100, 5000] ms. The watchdog stays
        ;; sensitive enough to fire close to the configured deadline while
        ;; never blocking caller shutdown for longer than 5s on long
        ;; timeouts (120s idle default would otherwise sleep 30s per tick).
        check-ms (-> (long idle-timeout-ms) (quot 4) (max 100) (min 5000))
        runnable (fn []
                   (try
                     (loop []
                       (Thread/sleep (long check-ms))
                       (when @alive?-atom
                         (let [elapsed-ms (long (/ (- (System/nanoTime) (long @last-byte-ns-atom)) 1000000))]
                           (if (>= elapsed-ms (long idle-timeout-ms))
                             (do
                               (try (on-fire elapsed-ms) (catch Throwable _ nil))
                               (try (.close stream) (catch Throwable _ nil)))
                             (recur)))))
                     (catch InterruptedException _ nil)
                     (catch Throwable _ nil)))
        thread   (doto (Thread. ^Runnable runnable "svar-idle-stream-watchdog")
                   (.setDaemon true)
                   (.start))]
    thread))

(defn- start-semantic-stream-watchdog!
  "Daemon thread. Closes `stream` if model/progress events stop while
   transport may still emit pings/comments."
  [^java.io.InputStream stream semantic-timeout-ms last-semantic-ns-atom alive?-atom on-fire]
  (let [check-ms (-> (long semantic-timeout-ms) (quot 4) (max 100) (min 5000))
        runnable (fn []
                   (try
                     (loop []
                       (Thread/sleep (long check-ms))
                       (when @alive?-atom
                         (let [elapsed-ms (long (/ (- (System/nanoTime) (long @last-semantic-ns-atom)) 1000000))]
                           (if (>= elapsed-ms (long semantic-timeout-ms))
                             (do
                               (try (on-fire elapsed-ms) (catch Throwable _ nil))
                               (try (.close stream) (catch Throwable _ nil)))
                             (recur)))))
                     (catch InterruptedException _ nil)
                     (catch Throwable _ nil)))
        thread   (doto (Thread. ^Runnable runnable "svar-semantic-stream-watchdog")
                   (.setDaemon true)
                   (.start))]
    thread))

(defn- start-cancel-watchdog!
  "Daemon thread driving caller-requested cancellation (`*cancel-fn*`).
   Polls `cancel-requested?` every `CANCEL_POLL_MS`; on the first truthy
   read it sets `cancel-fired?` then applies BOTH levers (a blocking socket
   read ignores `Thread.interrupt()`, and a not-yet-arrived body has no
   stream to close, so neither lever alone covers every phase):
     - closes the body `InputStream` if present (`stream-ref`) — unblocks a
       parked `.readLine` mid-stream;
     - interrupts `caller` — unparks the pre-headers `CompletableFuture.get`
       and any retry/backoff `Thread/sleep`.
   Exits when `alive?-atom` flips false (caller's `finally`) or after firing.
   Returns the thread so the caller can interrupt it on normal completion."
  [^Thread caller cancel-requested? stream-ref cancel-fired? alive?-atom]
  (let [runnable (fn []
                   (try
                     (loop []
                       (Thread/sleep 50)
                       (when @alive?-atom
                         (if (cancel-requested?)
                           (do
                             (reset! cancel-fired? true)
                             (when-let [s @stream-ref]
                               (try (.close ^java.io.InputStream s) (catch Throwable _ nil)))
                             (try (.interrupt caller) (catch Throwable _ nil)))
                           (recur))))
                     (catch InterruptedException _ nil)
                     (catch Throwable _ nil)))
        thread   (doto (Thread. ^Runnable runnable "svar-cancel-watchdog")
                   (.setDaemon true)
                   (.start))]
    thread))

(defn- http-post-stream!
  "Makes a streaming HTTP POST request. Reads SSE events and fires on-delta
   for each. `headers` - HTTP headers map. `delta-fn` - extracts delta
   from parsed SSE chunk. `ttft-timeout-ms` - optional time-to-first-token
   ceiling for the pre-headers phase (nil/0 disables). `idle-timeout-ms`
   - optional inter-chunk idle ceiling for the post-headers stream
   (nil/0 disables).

   Returns an envelope:
     {:content :reasoning :api-usage
      :http-response {:url :streaming? :status}}
   Mirrors the shape of non-streaming `extract-response-data` so
   downstream callers destructure uniformly. There is no `:raw-body` on
   streaming paths - the SSE chunks are consumed incrementally, so the
   accumulated `:content` / `:reasoning` are the closest analogues."
  [url body headers timeout-ms ttft-timeout-ms idle-timeout-ms delta-fn on-delta]
  (let [_ (trove/log! {:level :info :id ::stream-started
                       :data  (log-data {:url url
                                         :timeout-ms timeout-ms
                                         :ttft-timeout-ms ttft-timeout-ms
                                         :idle-timeout-ms idle-timeout-ms
                                         :semantic-timeout-ms *stream-semantic-timeout-ms*})
                       :msg   "stream HTTP POST dispatched"})
        request-start-ns  (System/nanoTime)
        caller-thread     (Thread/currentThread)
        headers-received? (atom false)
        ttft-fired?       (atom false)
        ttft-watchdog     (when (and (number? ttft-timeout-ms) (pos? (long ttft-timeout-ms)))
                            (start-ttft-watchdog! caller-thread ttft-timeout-ms
                              headers-received? ttft-fired?))
        ;; Caller-driven cancellation (bound `*cancel-fn*`). Captured on the
        ;; caller thread so the watchdog (different thread, no dynamic
        ;; binding) can read it. Zero cost when no cancel-fn is bound.
        cancel-fn         *cancel-fn*
        cancel-requested? (fn [] (boolean (and cancel-fn (try (cancel-fn) (catch Throwable _ false)))))
        cancel-fired?     (atom false)
        cancel-alive?     (atom true)
        stream-ref        (atom nil)
        cancel-watchdog   (when cancel-fn
                            (start-cancel-watchdog! caller-thread cancel-requested?
                              stream-ref cancel-fired? cancel-alive?))
        response (try
                   (http/post url
                     {:client @shared-http-client
                      :headers headers
                      :body (json/write-json-str body)
                      :timeout timeout-ms
                      :as :stream})
                   (catch clojure.lang.ExceptionInfo e
                     ;; If the TTFT watchdog fired, the interrupt may
                     ;; surface as ExceptionInfo wrapping IOException.
                     ;; Reclassify before propagating; otherwise convert
                     ;; InputStream body to string and re-throw as today.
                     (when @cancel-fired?
                       (Thread/interrupted)
                       (throw (ex-info "Stream cancelled by caller (pre-headers)."
                                {:type :svar.core/stream-cancelled :stream? true :url url} e)))
                     (if @ttft-fired?
                       (do
                         ;; Consume any leftover interrupt so we don't
                         ;; poison unrelated code further up the stack.
                         (Thread/interrupted)
                         (trove/log! {:level :warn :id ::stream-ttft-timeout
                                      :data (log-data {:url url
                                                       :ttft-timeout-ms ttft-timeout-ms})
                                      :msg "TTFT timeout, no headers received"})
                         (throw (ex-info (str "Stream TTFT timeout (" ttft-timeout-ms
                                           "ms with no response headers): " (ex-message e))
                                  {:type :svar.core/stream-ttft-timeout
                                   :stream? true :url url
                                   :ttft-timeout-ms ttft-timeout-ms
                                   :cause-class (.getName (class e))}
                                  e)))
                       (if (connection-error? e)
                         (throw (connection-error->ex-info e url))
                         (let [ed (ex-data e)
                               body-str (when (instance? java.io.InputStream (:body ed))
                                          (slurp-input-stream (:body ed)))]
                           (throw (ex-info (ex-message e)
                                    (cond-> (dissoc ed :body)
                                      body-str (assoc :body body-str))
                                    (ex-cause e)))))))
                   (catch java.io.IOException e
                     ;; Same reclassification for raw IOExceptions (the
                     ;; JDK may surface InterruptedIOException here).
                     (when @cancel-fired?
                       (Thread/interrupted)
                       (throw (ex-info "Stream cancelled by caller (pre-headers)."
                                {:type :svar.core/stream-cancelled :stream? true :url url} e)))
                     (if @ttft-fired?
                       (do
                         (Thread/interrupted)
                         (trove/log! {:level :warn :id ::stream-ttft-timeout
                                      :data (log-data {:url url
                                                       :ttft-timeout-ms ttft-timeout-ms})
                                      :msg "TTFT timeout, no headers received"})
                         (throw (ex-info (str "Stream TTFT timeout (" ttft-timeout-ms
                                           "ms with no response headers): " (ex-message e))
                                  {:type :svar.core/stream-ttft-timeout
                                   :stream? true :url url
                                   :ttft-timeout-ms ttft-timeout-ms
                                   :cause-class (.getName (class e))}
                                  e)))
                       (if (connection-error? e)
                         (throw (connection-error->ex-info e url))
                         (throw e))))
                   (finally
                     ;; Order matters: flip the flag BEFORE interrupting
                     ;; the watchdog so the watchdog's recheck sees the
                     ;; success. Then interrupt to wake it from sleep
                     ;; immediately; without this it'd live until the
                     ;; full ttft-timeout-ms.
                     (reset! headers-received? true)
                     (when ttft-watchdog
                       (try (.interrupt ^Thread ttft-watchdog) (catch Throwable _ nil)))))
        _ (trove/log! {:level :debug :id ::stream-headers
                       :data (log-data {:url url
                                        :status (:status response)
                                        :headers-elapsed-ms (long (/ (- (System/nanoTime) request-start-ns) 1000000))})
                       :msg "stream headers received"})
        input-stream (:body response)
        ;; Hand the body to the cancel watchdog so it can close it (the only
        ;; way to unblock a parked `.readLine` on a cancel mid-stream).
        _ (reset! stream-ref input-stream)
        content-acc (StringBuilder.)
        reasoning-acc (StringBuilder.)
        usage-atom (atom nil)
        provider-state-atom (atom nil)
        current-reasoning-item (atom nil)
        terminal-event (atom nil)
        last-event-type (atom nil)
        last-finish-reason (atom nil)
        incomplete-response (atom nil)
        last-byte-ns (atom (System/nanoTime))
        last-semantic-ns (atom (System/nanoTime))
        idle-fired? (atom false)
        semantic-fired? (atom false)
        watchdog-alive? (atom true)
        semantic-timeout-ms *stream-semantic-timeout-ms*
        watchdog (when (and (number? idle-timeout-ms) (pos? (long idle-timeout-ms)))
                   (start-idle-stream-watchdog!
                     input-stream
                     idle-timeout-ms
                     last-byte-ns
                     watchdog-alive?
                     (fn [elapsed-ms]
                       (reset! idle-fired? true)
                       (trove/log! {:level :warn :id ::stream-idle-timeout
                                    :data (log-data {:url url
                                                     :idle-timeout-ms idle-timeout-ms
                                                     :elapsed-ms elapsed-ms
                                                     :content-acc-len (.length content-acc)
                                                     :reasoning-acc-len (.length reasoning-acc)})
                                    :msg "stream idle, closing"}))))
        semantic-watchdog (when (and (number? semantic-timeout-ms) (pos? (long semantic-timeout-ms)))
                            (start-semantic-stream-watchdog!
                              input-stream
                              semantic-timeout-ms
                              last-semantic-ns
                              watchdog-alive?
                              (fn [elapsed-ms]
                                (reset! semantic-fired? true)
                                (trove/log! {:level :warn :id ::stream-semantic-timeout
                                             :data (log-data {:url url
                                                              :semantic-timeout-ms semantic-timeout-ms
                                                              :elapsed-ms elapsed-ms
                                                              :content-acc-len (.length content-acc)
                                                              :reasoning-acc-len (.length reasoning-acc)})
                                             :msg "stream semantic timeout, closing"}))))]
    (try
      (with-open [reader (BufferedReader. (InputStreamReader. ^java.io.InputStream input-stream "UTF-8"))]
        (letfn [(handle-parsed! [parsed]
                  (cond
                    (= sse-done parsed)
                    (do
                      (reset! terminal-event {:kind :done-marker})
                      (reset! last-semantic-ns (System/nanoTime)))

                    parsed
                    (let [parsed (enrich-responses-reasoning-event current-reasoning-item parsed)
                          {:keys [content-delta reasoning-delta content-fallback reasoning-fallback
                                  provider-state api-usage terminal? incomplete? incomplete-reason]
                           :as extracted}
                          (delta-fn parsed)
                          content-piece   (or content-delta
                                            (when (zero? (.length content-acc))
                                              (some-> content-fallback content-part-text)))
                          reasoning-piece (or reasoning-delta
                                            (when (zero? (.length reasoning-acc))
                                              (some-> reasoning-fallback reasoning-part-text)))]
                      (when-let [event-type (stream-event-type parsed)]
                        (reset! last-event-type event-type))
                      (when-let [finish-reason (stream-finish-reason parsed)]
                        (reset! last-finish-reason finish-reason)
                        (when (and (not @terminal-event)
                                (not (contains? #{"in_progress" "queued" "running"} finish-reason)))
                          (reset! terminal-event {:kind :finish-reason
                                                  :event-type (stream-event-type parsed)})))
                      (when terminal?
                        (reset! terminal-event {:kind :terminal-event
                                                :event-type (stream-event-type parsed)}))
                      (when incomplete?
                        (reset! incomplete-response {:reason incomplete-reason :chunk parsed}))
                      (when (stream-semantic-event? parsed extracted content-piece reasoning-piece)
                        (reset! last-semantic-ns (System/nanoTime)))
                      (when content-piece (.append content-acc content-piece))
                      (when reasoning-piece (.append reasoning-acc reasoning-piece))
                      (when provider-state
                        (swap! provider-state-atom merge-provider-state provider-state))
                      (when api-usage (reset! usage-atom api-usage))
                      (when on-delta
                        (on-delta {:content-delta content-piece
                                   :reasoning-delta reasoning-piece
                                   :content-acc (str content-acc)
                                   :reasoning-acc (str reasoning-acc)
                                   :provider-state @provider-state-atom
                                   :api-usage api-usage})))))
                (dispatch-event! [event-type data-lines]
                  (when-let [parsed (parse-sse-event event-type data-lines)]
                    (handle-parsed! parsed)))]
          (loop [event-type nil
                 data-lines []
                 line-count (long 0)
                 last-line-ns (System/nanoTime)]
            (let [line (.readLine reader)
                  now-ns (System/nanoTime)
                  gap-ms (long (/ (- now-ns last-line-ns) 1000000))]
              ;; Reset idle on every observed line: comments, blank
              ;; separators, and data all prove transport liveness.
              (reset! last-byte-ns now-ns)
              ;; Caller cancelled while deltas are still flowing: break
              ;; between lines (the watchdog's stream-close covers a parked
              ;; read; this covers a fast stream that never parks). Cheap
              ;; volatile read — set by the cancel watchdog.
              (when @cancel-fired?
                (throw (ex-info "Stream cancelled by caller."
                         {:type :svar.core/stream-cancelled :stream? true :url url})))
              ;; Optional per-line trace. Gated on the cached boolean
              ;; so the disabled path is a single branch with zero
              ;; allocations - measurable improvement at high event
              ;; rates (anthropic delta storms peak >2000 lines/s).
              (when (and stream-line-trace-enabled?
                      (or (>= gap-ms STREAM_LINE_TRACE_GAP_MS)
                        (== 0 (Math/floorMod line-count (long STREAM_LINE_TRACE_EVERY_N)))))
                (trove/log! {:level :info :id ::stream-line-trace
                             :data (log-data
                                     {:url url
                                      :line-count line-count
                                      :gap-ms gap-ms
                                      :line-len (when line (count line))
                                      :line-preview (when line
                                                      (subs line 0
                                                        (min STREAM_LINE_TRACE_PREVIEW_CHARS
                                                          (count line))))
                                      :line-eof? (nil? line)
                                      :pending-event-type event-type
                                      :last-event-type @last-event-type
                                      :content-acc-len (.length content-acc)
                                      :reasoning-acc-len (.length reasoning-acc)})
                             :msg "sse line"}))
              (when (some? line)
                (if (str/blank? line)
                  (do
                    (dispatch-event! event-type data-lines)
                    (recur nil [] (unchecked-inc line-count) now-ns))
                  (let [{:keys [field value]} (sse-field-line line)]
                    (case field
                      "event" (recur value data-lines (unchecked-inc line-count) now-ns)
                      "data"  (recur event-type (conj data-lines value) (unchecked-inc line-count) now-ns)
                      (recur event-type data-lines (unchecked-inc line-count) now-ns)))))))))
      (when @semantic-fired?
        (let [stream-finalization (stream-finalization-summary
                                    {:terminal @terminal-event
                                     :incomplete @incomplete-response
                                     :last-event-type @last-event-type
                                     :last-finish-reason @last-finish-reason
                                     :content-acc content-acc
                                     :reasoning-acc reasoning-acc
                                     :response response})]
          (throw (ex-info (str "Stream semantic timeout (" semantic-timeout-ms
                            "ms without model/progress event).")
                   {:type :svar.core/stream-semantic-timeout
                    :stream? true
                    :url url
                    :semantic-timeout-ms semantic-timeout-ms
                    :stream-finalization stream-finalization
                    :content-acc-len (.length content-acc)
                    :reasoning-acc-len (.length reasoning-acc)
                    :partial-content (when (pos? (.length content-acc)) (str content-acc))
                    :reasoning (when (pos? (.length reasoning-acc)) (str reasoning-acc))}))))
      (when @idle-fired?
        (let [stream-finalization (stream-finalization-summary
                                    {:terminal @terminal-event
                                     :incomplete @incomplete-response
                                     :last-event-type @last-event-type
                                     :last-finish-reason @last-finish-reason
                                     :content-acc content-acc
                                     :reasoning-acc reasoning-acc
                                     :response response})]
          (throw (ex-info (str "Stream idle timeout (" idle-timeout-ms "ms with no bytes).")
                   {:type :svar.core/stream-idle-timeout
                    :stream? true
                    :url url
                    :idle-timeout-ms idle-timeout-ms
                    :stream-finalization stream-finalization
                    :content-acc-len (.length content-acc)
                    :reasoning-acc-len (.length reasoning-acc)
                    :partial-content (when (pos? (.length content-acc)) (str content-acc))
                    :reasoning (when (pos? (.length reasoning-acc)) (str reasoning-acc))}))))
      (when-let [incomplete @incomplete-response]
        (let [stream-finalization (stream-finalization-summary
                                    {:terminal @terminal-event
                                     :incomplete incomplete
                                     :last-event-type @last-event-type
                                     :last-finish-reason @last-finish-reason
                                     :content-acc content-acc
                                     :reasoning-acc reasoning-acc
                                     :response response})]
          (throw (ex-info "Stream ended with incomplete response."
                   {:type :svar.core/stream-incomplete
                    :stream? true
                    :url url
                    :reason (:reason incomplete)
                    :stream-finalization stream-finalization
                    :content-acc-len (.length content-acc)
                    :reasoning-acc-len (.length reasoning-acc)
                    :partial-content (when (pos? (.length content-acc)) (str content-acc))
                    :reasoning (when (pos? (.length reasoning-acc)) (str reasoning-acc))}))))
      (when-not @terminal-event
        (let [stream-finalization (stream-finalization-summary
                                    {:terminal nil
                                     :incomplete nil
                                     :last-event-type @last-event-type
                                     :last-finish-reason @last-finish-reason
                                     :content-acc content-acc
                                     :reasoning-acc reasoning-acc
                                     :response response})]
          (throw (ex-info "Stream ended before terminal marker."
                   {:type :svar.core/stream-truncated
                    :stream? true
                    :url url
                    :stream-finalization stream-finalization
                    :content-acc-len (.length content-acc)
                    :reasoning-acc-len (.length reasoning-acc)
                    :partial-content (when (pos? (.length content-acc)) (str content-acc))
                    :reasoning (when (pos? (.length reasoning-acc)) (str reasoning-acc))}))))
      (let [final-content   (let [s (str content-acc)] (when-not (str/blank? s) s))
            final-reasoning (let [s (str reasoning-acc)] (when-not (str/blank? s) s))
            ps              @provider-state-atom
              ;; Each provider has a dedicated extract-assistant-message
              ;; helper that maps its accumulated provider-state shape
              ;; to svar's canonical assistant message. Anthropic
              ;; flushes already-canonical blocks in arrival order;
              ;; OpenAI Responses surfaces reasoning items that we lift
              ;; into thinking blocks (signed wire shape JSON-encoded
              ;; under :thinking-signature for byte-perfect replay).
              ;; Plain chat completions (z.ai GLM, OpenRouter, etc.)
              ;; arrive without a populated provider-state; build the
              ;; canonical message from the streamed visible text plus
              ;; the accumulated reasoning_content channel so callers
              ;; round-trip through the same shape as non-streaming.
            assistant-msg   (case (:provider ps)
                              :anthropic
                              (when (seq (:blocks ps))
                                {:role "assistant"
                                 :content (vec (:blocks ps))})

                              :openai-responses
                              (responses-extract-assistant-message
                                (:reasoning-items ps) final-content)

                              (openai-chat-canonical-assistant-message
                                {:content final-content
                                 :reasoning-content final-reasoning}))
            stream-finalization (stream-finalization-summary
                                  {:terminal @terminal-event
                                   :incomplete nil
                                   :last-event-type @last-event-type
                                   :last-finish-reason @last-finish-reason
                                   :content-acc content-acc
                                   :reasoning-acc reasoning-acc
                                   :response response})]
        (trove/log! {:level :debug :id ::stream-finalized
                     :data  (log-data (assoc stream-finalization :url url))
                     :msg   "stream finalized"})
        (cond-> {:content        final-content
                 :reasoning      final-reasoning
                 :provider-state ps
                 :api-usage      @usage-atom
                 :stream-finalization stream-finalization
                 :http-response  {:url        url
                                  :streaming? true
                                  :status     (:status response)
                                  :stream-finalization stream-finalization}}
          assistant-msg (assoc :assistant-message assistant-msg)))
      (catch clojure.lang.ExceptionInfo e
        ;; Cancellation wins over idle/semantic reclassification — the
        ;; watchdog closed the stream / interrupted us on purpose.
        (when @cancel-fired?
          (Thread/interrupted)
          (throw (ex-info "Stream cancelled by caller."
                   {:type :svar.core/stream-cancelled :stream? true :url url} e)))
        (if (stream-finalization-error? e)
          (throw e)
          (let [stream-finalization (stream-finalization-summary
                                      {:terminal @terminal-event
                                       :incomplete @incomplete-response
                                       :last-event-type @last-event-type
                                       :last-finish-reason @last-finish-reason
                                       :content-acc content-acc
                                       :reasoning-acc reasoning-acc
                                       :response response})
                idle?               @idle-fired?
                semantic?           @semantic-fired?]
            (throw (ex-info (cond
                              semantic? (str "Stream semantic timeout (" semantic-timeout-ms
                                          "ms without model/progress event): " (ex-message e))
                              idle?     (str "Stream idle timeout (" idle-timeout-ms "ms with no bytes): " (ex-message e))
                              :else     (str "Stream connection error: " (ex-message e)))
                     {:type (cond semantic? :svar.core/stream-semantic-timeout
                                  idle?     :svar.core/stream-idle-timeout
                                  :else     :svar.core/http-error)
                      :stream? true :url url
                      :idle-timeout-ms (when idle? idle-timeout-ms)
                      :semantic-timeout-ms (when semantic? semantic-timeout-ms)
                      :cause-class (.getName (class e))
                      :stream-finalization stream-finalization
                      :content-acc-len (.length content-acc)
                      :reasoning-acc-len (.length reasoning-acc)
                      :partial-content (when (pos? (.length content-acc)) (str content-acc))
                      :reasoning (when (pos? (.length reasoning-acc)) (str reasoning-acc))}
                     e)))))
      (catch Exception e
        ;; A watchdog-closed stream surfaces here as a plain IOException —
        ;; reclassify as cancellation before the idle/connection branches.
        (when @cancel-fired?
          (Thread/interrupted)
          (throw (ex-info "Stream cancelled by caller."
                   {:type :svar.core/stream-cancelled :stream? true :url url} e)))
        (let [stream-finalization (stream-finalization-summary
                                    {:terminal @terminal-event
                                     :incomplete @incomplete-response
                                     :last-event-type @last-event-type
                                     :last-finish-reason @last-finish-reason
                                     :content-acc content-acc
                                     :reasoning-acc reasoning-acc
                                     :response response})
              idle?               @idle-fired?
              semantic?           @semantic-fired?]
          (throw (ex-info (cond
                            semantic? (str "Stream semantic timeout (" semantic-timeout-ms
                                        "ms without model/progress event): " (ex-message e))
                            idle?     (str "Stream idle timeout (" idle-timeout-ms "ms with no bytes): " (ex-message e))
                            :else     (str "Stream connection error: " (ex-message e)))
                   {:type (cond semantic? :svar.core/stream-semantic-timeout
                                idle?     :svar.core/stream-idle-timeout
                                :else     :svar.core/http-error)
                    :stream? true :url url
                    :idle-timeout-ms (when idle? idle-timeout-ms)
                    :semantic-timeout-ms (when semantic? semantic-timeout-ms)
                    :cause-class (.getName (class e))
                    :stream-finalization stream-finalization
                    :content-acc-len (.length content-acc)
                    :reasoning-acc-len (.length reasoning-acc)
                    :partial-content (when (pos? (.length content-acc)) (str content-acc))
                    :reasoning (when (pos? (.length reasoning-acc)) (str reasoning-acc))}
                   e))))
      (finally
        ;; Always stop the watchdog — reset alive? first so a racing
        ;; check sees the flag, then interrupt the sleep loop. Idempotent
        ;; if no watchdog was started (nil thread).
        (reset! watchdog-alive? false)
        (when watchdog
          (try (.interrupt ^Thread watchdog) (catch Throwable _ nil)))
        (when semantic-watchdog
          (try (.interrupt ^Thread semantic-watchdog) (catch Throwable _ nil)))
        (reset! cancel-alive? false)
        (when cancel-watchdog
          (try (.interrupt ^Thread cancel-watchdog) (catch Throwable _ nil)))
        ;; The cancel watchdog interrupts the caller thread to break parks;
        ;; clear any residual interrupt so it can't poison the caller's next
        ;; blocking op now that cancellation is captured as an exception.
        (when @cancel-fired? (Thread/interrupted))))))

(defn- chat-completion-streaming
  "Streaming variant of chat-completion. Sends stream:true, reads SSE events,
   fires on-chunk with accumulated text. Returns same shape as non-streaming."
  [messages model api-key base-url retry-opts timeout-ms ttft-timeout-ms idle-timeout-ms semantic-timeout-ms extra-body on-chunk api-style]
  (let [api-style    (or api-style :openai-compatible-chat)
        provider-id  (:provider-id retry-opts)
        llm-headers  (:llm-headers retry-opts)
        anthropic?   (= api-style :anthropic)
        anthropic-oauth? (and anthropic? (anthropic-oauth-token? api-key))
        base-body    (if anthropic?
                       (build-anthropic-request-body messages model extra-body
                         {:anthropic-oauth? anthropic-oauth?})
                       (build-request-body messages model extra-body))
        request-body (cond-> (assoc base-body :stream true)
                       (not anthropic?) (assoc :stream_options {:include_usage true}))
        chat-url     (make-chat-url base-url api-style)
        headers      (merge (request-headers api-style api-key provider-id messages llm-headers)
                       {"Accept" "text/event-stream"})
        ;; Anthropic uses a stateful closure so per-block
        ;; signature/text accumulation survives the SSE event boundary.
        ;; Other providers stay on the stateless extractor.
        delta-fn     (if anthropic? (make-anthropic-stream-delta-fn) extract-stream-delta)]
    (try
      (with-retry
        (fn []
          (binding [*stream-semantic-timeout-ms* semantic-timeout-ms]
            (http-post-stream! chat-url request-body headers timeout-ms ttft-timeout-ms idle-timeout-ms delta-fn
              (fn [{:keys [content-acc reasoning-acc provider-state api-usage]}]
                (on-chunk {:content content-acc :reasoning (nonblank-str reasoning-acc)
                           :provider-state provider-state
                           :api-usage api-usage :done? false})))))
        retry-opts)
      (catch Exception e
        (if (stream-finalization-error? e)
          (throw e)
          (let [ex-data-map (ex-data e)
                response-body (let [b (:body ex-data-map)]
                                (when (string? b) b))
                api-key-error (detect-api-key-error response-body)
                base-message  (http-error-message e)
                error-message (if api-key-error
                                (str api-key-error " (Original: " base-message ")")
                                base-message)]
            (anomaly/fault! error-message
              (cond-> (merge (dissoc (ex-data e) :body) {:type :svar.core/http-error
                                                         :llm-request {:model model :base-url base-url}})
                response-body (assoc :body (truncate-error-body response-body))
                api-key-error (assoc :api-key-error api-key-error)
                true          (assoc :cause-class (some-> (ex-cause e) class .getName)
                                :original-message (some-> (ex-cause e) ex-message))))))))))

(defn chat-completion
  "Calls the LLM API (OpenAI compatible) with the given messages.

   Params:
   `messages` - Vector. Chat messages.
   `model` - String. Model name.
   `api-key` - String. Bearer token.
   `base-url` - String. API base URL.
   `opts` - Map, optional:
     - :timeout-ms      - Integer. Whole-request timeout (default:
                          router/DEFAULT_TIMEOUT_MS).
     - :ttft-timeout-ms - Integer. Time-to-first-token timeout for
                          streaming responses (default:
                          router/DEFAULT_TTFT_TIMEOUT_MS). Bounds the
                          wait between the request leaving and the
                          response headers arriving. Surfaces
                          `:svar.core/stream-ttft-timeout`. Pass `nil`
                          to disable.
     - :idle-timeout-ms - Integer. Inter-chunk idle timeout for streaming
                          responses (default: router/DEFAULT_IDLE_TIMEOUT_MS).
                          Closes the SSE stream if no bytes arrive for this
                          long; surfaces `:svar.core/stream-idle-timeout`.
                          Pass `nil` to disable.
     - :semantic-timeout-ms - Integer. Model/progress timeout for streaming
                              responses while transport bytes still arrive.
                              Surfaces `:svar.core/stream-semantic-timeout`.
                              Pass `nil` to disable.
     - :extra-body - Map. Additional params for the API request body.
     - :on-chunk - Function. When provided, enables SSE streaming. Callback receives
                   accumulated text string after each chunk.
     - :max-retries, :initial-delay-ms, :max-delay-ms, :multiplier - retry config.

   Returns:
   Map with :content, :reasoning (may be nil), :api-usage."
  ([messages model api-key base-url]
   (chat-completion messages model api-key base-url {}))
  ([messages model api-key base-url opts]
   (let [timeout-ms      (get opts :timeout-ms router/DEFAULT_TIMEOUT_MS)
         ;; `contains?` (not `get` with default) so a caller can pass
         ;; `:ttft-timeout-ms nil` / `:idle-timeout-ms nil` to explicitly
         ;; disable each watchdog without falling through to the default.
         ttft-timeout-ms (if (contains? opts :ttft-timeout-ms)
                           (:ttft-timeout-ms opts)
                           router/DEFAULT_TTFT_TIMEOUT_MS)
         idle-timeout-ms (if (contains? opts :idle-timeout-ms)
                           (:idle-timeout-ms opts)
                           router/DEFAULT_IDLE_TIMEOUT_MS)
         semantic-timeout-ms (if (contains? opts :semantic-timeout-ms)
                               (:semantic-timeout-ms opts)
                               router/DEFAULT_SEMANTIC_TIMEOUT_MS)
         extra-body     (:extra-body opts)
         on-chunk       (or (:on-chunk opts)
                          (when (copilot-stream-required? (:provider-id opts) base-url)
                            (constantly nil)))
         api-style      (:api-style opts)
         responses-path (:responses-path opts)
         llm-headers    (:llm-headers opts)
         provider-id     (:provider-id opts)
         headers         (merge (when (= :github-copilot provider-id)
                                  (copilot-static-headers))
                           (when (= :github-copilot provider-id)
                             (copilot-dynamic-headers messages))
                           ;; Caller headers win. Mirrors request-headers for
                           ;; Responses transport.
                           llm-headers)]
     (if (= api-style :openai-compatible-responses)
       (openai-responses-completion
         (build-openai-responses-request-body messages model extra-body)
         {:api-key         api-key
          :base-url        base-url
          :responses-path  (or responses-path "/responses")
          :headers         headers
          :timeout-ms      timeout-ms
          :ttft-timeout-ms ttft-timeout-ms
          :idle-timeout-ms idle-timeout-ms
          :semantic-timeout-ms semantic-timeout-ms
          :on-chunk        on-chunk})
       (if on-chunk
         (chat-completion-streaming
           messages model api-key base-url opts timeout-ms ttft-timeout-ms idle-timeout-ms semantic-timeout-ms extra-body on-chunk api-style)
         (chat-completion-with-retry
           messages model api-key base-url opts timeout-ms extra-body api-style))))))

(defn- url? [s] (or (str/starts-with? s "http://") (str/starts-with? s "https://")))

(defn- build-user-content
  "Builds user message content, supporting both text-only and multimodal formats."
  [text images]
  (if (empty? images)
    ;; Text-only: return plain string
    text
    ;; Multimodal: build content array with images first, then text
    (let [image-blocks (mapv (fn [{:keys [url base64 media-type] :or {media-type "image/png"}}]
                               {:type "image_url"
                                :image_url {:url (if url
                                                   url
                                                   (str "data:" media-type ";base64," base64))}})
                         images)
          text-block {:type "text" :text text}]
      (conj image-blocks text-block))))

;; =============================================================================
;; Message Construction Helpers (Public API)
;; =============================================================================

(defn image
  "Creates an image attachment for use with `user` messages.

   Accepts either base64-encoded image data or an HTTP(S) URL.
   URLs are passed through directly to the LLM API; base64 strings
   are wrapped in a data URI with the given media-type.

   Params:
   `source` - String. Base64-encoded image data or an image URL (http/https).
   `media-type` - String, optional. MIME type (default: \"image/png\").
                  Ignored when source is a URL.

   Returns:
   Map marker that `user` recognizes and converts to multimodal content.
   When source is a URL, returns {:svar/type :image :url \"...\"}.
   When source is base64, returns {:svar/type :image :base64 \"...\" :media-type \"...\"}."
  ([source]
   (image source "image/png"))
  ([source media-type]
   (if (url? source)
     {:svar/type :image :url source}
     {:svar/type :image :base64 source :media-type media-type})))

(defn cached
  "Wraps text in a cacheable content block.

   On `:anthropic` api-style, emitted as a text block carrying
   `cache_control: {type: \"ephemeral\"}`. On other api-styles the marker
   is stripped from the wire - OpenAI's implicit caching reads the same
   stable prefix without any client signal.

   Anthropic enforces ≤ 4 cache breakpoints per call. A `cache_control`
   on a block caches everything in the request UP TO and INCLUDING that
   block, so place cached blocks at the END of your stable prefix
   (caller-system + spec, before any dynamic user/assistant turns).

   Params:
   `text` - String. Block content.
   `opts` - Map, optional:
     - `:ttl` - `:5min` (default) or `:1h`. The 1-hour tier requires
       Anthropic's `extended-cache-ttl-2025-04-11` beta header on the
       provider; without it Anthropic falls back to the 5min tier.

   Returns:
   Content block map: `{:type \"text\" :text text :svar/cache true}`,
   plus `:svar/cache-ttl` when set. Use inside `system`/`user`/`assistant`
   content vectors.

   Example:
     (svar/system [(svar/cached big-stable-prompt)
                   live-env-block])
     ;; Anthropic emits :system as an array, last cached block becomes
     ;; the cache breakpoint."
  ([text] (cached text {}))
  ([text {:keys [ttl]}]
   (cond-> {:type "text" :text text :svar/cache true}
     ttl (assoc :svar/cache-ttl ttl))))

(defn system
  "Creates a system message.

   Params:
   `content` - String OR vector of content blocks (strings, text-blocks
               `{:type \"text\" :text str ...}`, or `(cached ...)` blocks).
               Cache markers are honored on Anthropic api-style and
               stripped on others.

   Returns:
   Message map with :role \"system\"."
  [content]
  {:role "system" :content content})

(defn user
  "Creates a user message, optionally with images for multimodal models.

   Params:
   `content` - String OR vector of content blocks.
   `images` - Zero or more image maps created with `image`."
  [content & images]
  (if (seq images)
    {:role "user"
     :content (build-user-content content (mapv #(select-keys % [:url :base64 :media-type]) images))}
    {:role "user" :content content}))

(defn assistant
  "Creates an assistant message (for few-shot examples or conversation history).

   Params:
   `content` - String OR vector of content blocks.

   Returns:
   Message map with :role \"assistant\"."
  [content]
  {:role "assistant" :content content})

;; =============================================================================
;; Config Resolution Helper
;; =============================================================================

(defn- resolve-opts
  "Extracts effective values from router + opts.
   Router provides network/tokens defaults. Opts provides per-call overrides.
   If :provider-id is present, uses provider-scoped pricing/context overlays.

   Returns a map carrying the resolved network/pricing/context values plus
   the per-call LLM tuning options."
  [router {:keys [model timeout-ms ttft-timeout-ms idle-timeout-ms semantic-timeout-ms check-context? output-reserve api-key
                  base-url provider-id api-style extra-body provider-state cache-system?
                  format-retries format-retry-on on-format-error
                  responses-path llm-headers verbosity context]
           :as opts}]
  (let [{:keys [network tokens]} router
        default-pricing (or (:pricing tokens) router/MODEL_PRICING)
        default-context-limits (or (:context-limits tokens) router/MODEL_CONTEXT_LIMITS)
        pricing (if provider-id
                  (assoc default-pricing model (router/provider-model-pricing provider-id model))
                  default-pricing)
        ;; Prefer the routed model's explicit `:context` (set on the runtime
        ;; provider — e.g. LM Studio detection or a user override) over the
        ;; static catalog lookup, which knows nothing about runtime-configured
        ;; local models and would fall back to DEFAULT_CONTEXT_LIMIT.
        model-context (or context
                        (when provider-id (router/provider-model-context provider-id model)))
        context-limits (cond-> default-context-limits
                         model-context (assoc model (long model-context)))]
    (cond-> {:model model
             :timeout-ms (or timeout-ms (:timeout-ms network) router/DEFAULT_TIMEOUT_MS)
             ;; TTFT + idle timeouts: caller > router > package default.
             ;; `contains?` (not `or`) at each step so an explicit nil
             ;; disables the corresponding watchdog without falling
             ;; through.
             :ttft-timeout-ms (cond
                                (contains? opts :ttft-timeout-ms)    ttft-timeout-ms
                                (contains? network :ttft-timeout-ms) (:ttft-timeout-ms network)
                                :else                                router/DEFAULT_TTFT_TIMEOUT_MS)
             :idle-timeout-ms (cond
                                (contains? opts :idle-timeout-ms)    idle-timeout-ms
                                (contains? network :idle-timeout-ms) (:idle-timeout-ms network)
                                :else                                router/DEFAULT_IDLE_TIMEOUT_MS)
             :semantic-timeout-ms (cond
                                    (contains? opts :semantic-timeout-ms)    semantic-timeout-ms
                                    (contains? network :semantic-timeout-ms) (:semantic-timeout-ms network)
                                    :else                                    router/DEFAULT_SEMANTIC_TIMEOUT_MS)
             :check-context? (if (some? check-context?) check-context? (if (contains? tokens :check-context?) (:check-context? tokens) true))
             :output-reserve (or output-reserve (:output-reserve tokens))
             :api-key api-key
             :base-url base-url
             :api-style (or api-style :openai-compatible-chat)
             :provider-id provider-id
             :network network
             :pricing pricing
             :context-limits context-limits}
      ;; Caller-provided LLM-shaping options pass through verbatim. We use
      ;; `some?` for most so we don't fabricate keys when the caller didn't
      ;; set them. For `:json-object-mode?` we use `contains?` because an
      ;; explicit `false` opts out a model that's flagged in metadata, and
      ;; we must distinguish it from "missing" (which inherits the flag).
      (some? extra-body)                     (assoc :extra-body extra-body)
      (some? provider-state)                 (assoc :provider-state provider-state)
      (some? cache-system?)                  (assoc :cache-system? cache-system?)
      (some? format-retries)                 (assoc :format-retries format-retries)
      (some? format-retry-on)                (assoc :format-retry-on format-retry-on)
      (contains? opts :json-object-mode?)    (assoc :json-object-mode? (:json-object-mode? opts))
      (some? on-format-error)                (assoc :on-format-error on-format-error)
      (some? responses-path)                 (assoc :responses-path responses-path)
      (some? llm-headers)                    (assoc :llm-headers llm-headers)
      (some? verbosity)                      (assoc :verbosity verbosity))))

;; =============================================================================
;; Provider Router (fallback, rate limiting, provider selection)
;; =============================================================================
;;
;; All LLM calls (ask!, refine!, chat-completion) go through the router.
;; The router selects the best provider, handles rate limits, and retries
;; on transient errors with automatic fallback to the next provider.

;; =============================================================================
;; Router delegation - all routing logic lives in router.clj
;; =============================================================================

(def make-router
  "Creates a router from a vector of provider maps. Delegates to router/make-router."
  router/make-router)

(def select-provider
  "Returns [provider model-map] or nil. Delegates to router/select-provider."
  router/select-provider)

(def router-stats
  "Returns cumulative + windowed stats. Delegates to router/router-stats."
  router/router-stats)

(def reset-budget!
  "Resets the router's budget counters. Delegates to router/reset-budget!."
  router/reset-budget!)

(def reset-provider!
  "Resets a provider's circuit breaker. Delegates to router/reset-provider!."
  router/reset-provider!)

(defn- routing-opts-with-reasoning
  "Merges svar-level opts that influence routing/fallback into the `:routing`
   map so `resolve-routing` can build a complete prefs map:
     - `:reasoning`         → implies `:require-reasoning? true`
     - `:on-format-error`   → enables format-error provider fallback
     - `:format-retry-on`   → customises the format-error type set
     - `:on-chunk`          → surfaced to the router so routing events
                              (`:llm.routing/provider-retry`/`-fallback`/
                              `-format-fallback`) fire live alongside
                              streaming content chunks. Without this
                              passthrough, callers see no progress during
                              multi-second 429 retry sleeps.
   Returns the augmented `:routing` map. Called by every routed entrypoint
   before `resolve-routing`."
  [opts]
  (cond-> (or (:routing opts) {})
    (:reasoning opts)            (assoc :reasoning (:reasoning opts))
    (:on-format-error opts)      (assoc :on-format-error (:on-format-error opts))
    (:format-retry-on opts)      (assoc :format-retry-on (:format-retry-on opts))
    (contains? opts :on-chunk)   (assoc :on-chunk (:on-chunk opts))))

(defn- inject-routed-params
  "Injects router-chosen `[provider model-map]` + caller opts into the opts map
   destined for a `*!*` primitive. Centralises the reasoning translation so
   every routed entrypoint gets the same treatment:

   - `:model`, `:api-key`, `:base-url`, `:api-style`, `:provider-id` come from
     the selected provider/model.
   - `:extra-body` is built from (max_tokens auto-params) < (auto reasoning) <
     (caller extra-body). The abstract `:reasoning :quick|:balanced|:deep` opt
     is translated per the model's api-style; non-reasoning models ignore it.
   - `:reasoning` and `:preserved-thinking?` are consumed here and removed
     downstream (they're svar-level opts, not provider params). Callers who
     set explicit reasoning keys inside `:extra-body` keep those overrides.
   - `:json-object-mode?` is propagated from the routed model's metadata
     when the caller didn't set it explicitly. Used by `ask!*` to auto-inject
     `response_format: {type: \"json_object\"}` on
     `:openai-compatible-chat` api-style - hardens prose-leaking models (GLM
     family historically leaks prose into
     `content` under `:deep` reasoning).
   - `:llm-headers` merges provider defaults with caller headers; caller wins."
  [opts provider model-map]
  (let [ctx (long (or (:context model-map) router/DEFAULT_CONTEXT_LIMIT))
        ;; Quarter of the input context is a reasonable headroom for a
        ;; single response; the rest stays for the prompt + tool
        ;; outputs. But some providers cap output independently of
        ;; context (Copilot caps Claude-sonnet-4.6 at 32K output even
        ;; though context is 200K, models.dev `:limit.output`). When
        ;; the merged model-map carries an explicit `:output-limit`,
        ;; clamp the auto budget to it so requests do not advertise a
        ;; max_tokens the provider will silently truncate — that's the
        ;; failure mode behind the empty-content / comment-only loop
        ;; in session 52983a42 once `auto-params` started producing
        ;; >output-cap budgets.
        output-cap (some-> (:output-limit model-map) long)
        quarter (long (* 0.25 ctx))
        auto-params {:max_tokens (if output-cap (min quarter (long output-cap)) quarter)}
        api-style (or (:api-style model-map) (:api-style provider))
        reasoning-params (router/reasoning-extra-body
                           api-style model-map (:reasoning opts)
                           {:preserved-thinking? (:preserved-thinking? opts)})
        merged-body (cond-> (merge (:extra-body provider) (:extra-body model-map) auto-params reasoning-params (:extra-body opts))
                      (:verbosity opts) (assoc :verbosity (:verbosity opts)))
        merged-headers (not-empty (merge (:llm-headers provider) (:llm-headers opts)))
        ;; Caller's explicit :json-object-mode? (true OR false) wins; otherwise
        ;; inherit the routed model's metadata flag. `contains?` so explicit
        ;; `false` opts out of auto-injection even when the model is flagged.
        json-object-mode? (if (contains? opts :json-object-mode?)
                            (:json-object-mode? opts)
                            (:json-object-mode? model-map))]
    (-> opts
      (dissoc :reasoning :preserved-thinking?)
      (assoc
        :model (:name model-map)
        :api-key (:api-key provider)
        :base-url (:base-url provider)
        :api-style (or (:api-style model-map) (:api-style provider))
        :provider-id (:id provider)
        :router-handles-rate-limit? true
        :json-object-mode? json-object-mode?
        :extra-body merged-body)
      ;; Forward the routed model's real context window so `resolve-opts`'
      ;; pre-flight `check-context-limit` uses it instead of re-deriving from
      ;; the static catalog (which has nothing for runtime-configured local
      ;; models → DEFAULT_CONTEXT_LIMIT). This is what carries LM Studio's
      ;; detected window into the overflow check.
      (cond-> (:context model-map)
        (assoc :context (:context model-map)))
      (cond-> (some? (:responses-path provider))
        (assoc :responses-path (:responses-path provider)))
      (cond-> merged-headers
        (assoc :llm-headers merged-headers)))))

(defn- resolved-network-timeout
  "Single point of truth for the streaming-timeout precedence chain:
   caller > router > package-default. `contains?` (not `or`) at each
   layer so an explicit `nil` from the caller disables the watchdog
   without falling through to the router default.

   `k` is one of `:timeout-ms`, `:ttft-timeout-ms`, `:idle-timeout-ms`,
   `:semantic-timeout-ms`. `default` is `router/DEFAULT_*_MS`. Mirrors what `resolve-opts` /
   `ask-code!*` / `ask!*` already do internally; lifted here so every
   public entrypoint (`routed-chat-completion`, `chat-completion`,
   `ask!`, `ask-code!`, `abstract!`, `eval!`, `refine!`, `sample!`)
   resolves the same way."
  [opts router-network k default]
  (cond
    (contains? opts k)           (get opts k)
    (contains? router-network k) (get router-network k)
    :else                        default))

(defn routed-chat-completion
  "Routes a chat-completion across providers with fallback.

   opts may include:
     - :routing, :on-chunk, :reasoning, :extra-body — routing inputs.
     - :timeout-ms      — whole-request HTTP timeout (svar passes this
                          to babashka.http-client's `:timeout`).
     - :ttft-timeout-ms — time-to-first-token (pre-headers) ceiling.
                          Interrupts `HttpClient.send` if the upstream
                          never returns response headers within the
                          window. Raises `:svar.core/stream-ttft-timeout`.
     - :idle-timeout-ms — inter-chunk idle ceiling for streaming. Closes
                          the SSE `InputStream` if no bytes arrive within
                          the window. Raises `:svar.core/stream-idle-timeout`.
     - :semantic-timeout-ms — model/progress ceiling for streaming while
                              transport pings may continue. Raises
                              `:svar.core/stream-semantic-timeout`.

   Precedence per key: caller `opts` > router `:network` > package
   default. Pass an explicit `nil` to disable the corresponding watchdog."
  [router messages opts]
  (let [resolved (router/resolve-routing router (routing-opts-with-reasoning opts))
        network  (:network router)
        timeout-ms      (resolved-network-timeout opts network :timeout-ms      router/DEFAULT_TIMEOUT_MS)
        ttft-timeout-ms (resolved-network-timeout opts network :ttft-timeout-ms router/DEFAULT_TTFT_TIMEOUT_MS)
        idle-timeout-ms (resolved-network-timeout opts network :idle-timeout-ms router/DEFAULT_IDLE_TIMEOUT_MS)
        semantic-timeout-ms (resolved-network-timeout opts network :semantic-timeout-ms router/DEFAULT_SEMANTIC_TIMEOUT_MS)]
    (router/with-provider-fallback router (:prefs resolved)
      (fn [provider model-map]
        (let [{:keys [extra-body api-style responses-path llm-headers]}
              (inject-routed-params opts provider model-map)]
          (chat-completion messages (:name model-map)
            (:api-key provider)
            (:base-url provider)
            (cond-> {:extra-body      extra-body
                     :api-style       api-style
                     :timeout-ms      timeout-ms
                     :ttft-timeout-ms ttft-timeout-ms
                     :idle-timeout-ms idle-timeout-ms
                     :semantic-timeout-ms semantic-timeout-ms}
              (:id provider)     (assoc :provider-id (:id provider))
              (:on-chunk opts)   (assoc :on-chunk (:on-chunk opts))
              responses-path     (assoc :responses-path responses-path)
              llm-headers        (assoc :llm-headers llm-headers))))))))

;; =============================================================================
;; ask!* - Low-level structured output (primitive, no routing)
;; =============================================================================

;; Forward declaration - ask!* is defined next, ask! (routed) wraps it below
(declare ask!*)

;; =============================================================================
;; ask! - Routed structured output (ALL calls go through the router)
;; =============================================================================

(defn ask!
  "Structured output with automatic provider routing, fallback, and rate limiting.

   Params:
   `router` - Router instance from make-router. Required.
   `opts` - Map. Accepts all opts that ask!* accepts, plus:
     :routing - Map with routing preferences:
       :optimize - :cost, :intelligence, or :speed (nil = first model)
       :provider - keyword, override specific provider
       :model - string, override specific model
       :on-transient-error - :hybrid (default), :auto-route-cross-providers,
                             :fallback-model-in-the-same-provider, :fail
     :reasoning - Abstract reasoning depth: :quick, :balanced, or :deep
       (strings + OpenAI-style :low/:medium/:high aliases also accepted).
       Automatically translated per the selected model's api-style:
         OpenAI/GPT-5/o-series → `{:reasoning_effort \"low|medium|high\"}`
         Anthropic Claude 4.x  → `{:thinking {:type \"enabled\" :budget_tokens N}}`
         Z.ai GLM-4.6+         → `{:thinking {:type \"enabled\"|\"disabled\"}}`
       Models without `:reasoning?` in their metadata ignore this silently.
       Anything set in `:extra-body` wins over this automatic translation.
     :preserved-thinking? - Z.ai-only. When true AND the selected model uses
       `:reasoning-style :zai-thinking`, emits `clear_thinking: false` so
       reasoning_content is preserved across assistant turns (Preserved
       Thinking on GLM-5 / GLM-4.7). Callers MUST echo the unmodified
       reasoning_content back in subsequent assistant turns or quality and
       cache hit rates degrade. No-op on non-z.ai styles. The Coding Plan
       endpoint (`:zai-coding`) has preserved thinking ON by default
       server-side - setting this flag is harmless there.
     :extra-body - Map merged into the API request body. Use this to pass
       provider-specific params (e.g. {:temperature 0.3}, {:top_p 0.9}) or
       to override reasoning params with explicit wire-shape overrides.
     :cache-system? - When true, marks the system message (caller-system +
       inlined spec-prompt) as a cache breakpoint. On Anthropic this
       emits `cache_control: {type: \"ephemeral\"}` on the last block of
       the system message; on other api-styles the marker is stripped
       (OpenAI implicit caching keys on the same stable prefix without a
       client signal). For finer control, use `(svar/cached ...)` blocks
       directly inside system/user/assistant content vectors.
     :schema-tail-pointer? - Boolean, default true. Appends a short
       reminder to the last user message that points back to the cached
       schema in the system message. Restores recency-driven schema
       adherence after head-inlining the schema body. Pass `false` for
       head-only mode. See `ask!*` for the full rationale.
     :on-format-error - Routing strategy when a provider returns content
       that fails schema parsing. One of:
         :fail               (default) - throw with the full forensic envelope.
         :fallback-provider  - treat the failure as transient and try the
            next provider/model in the fleet, excluding the offender. The
            final exception (when all providers fail) is the LAST format
            error seen, with `:routed/trace` and `:format-failed`
            merged into ex-data. Pairs well with `:format-retries N` per
            provider - retries first absorb prose-leaks locally, fallback
            kicks in only when the whole model is broken for this spec.
     :format-retries / :format-retry-on / :json-object-mode? - See `ask!*`.
       These reach the LLM call via the routed-params pipeline.

   Network / streaming timeouts (precedence per key: caller `opts` > router
   `:network` > package default; pass an explicit `nil` to disable):
     :timeout-ms      - Whole-request HTTP timeout. Default
                        `router/DEFAULT_TIMEOUT_MS` (5 min).
     :ttft-timeout-ms - Time-to-first-token: bounds the pre-headers
                        phase by interrupting the calling thread inside
                        `HttpClient.send` if no response headers arrive
                        within the window. Surfaces typed ex-info
                        `:type :svar.core/stream-ttft-timeout`. Default
                        `router/DEFAULT_TTFT_TIMEOUT_MS` (90 s).
     :idle-timeout-ms - Inter-chunk idle ceiling for streaming responses.
                        Closes the body `InputStream` if no SSE bytes
                        arrive within the window. Surfaces
                        `:type :svar.core/stream-idle-timeout`. Resets
                        on every line incl. SSE `: ping` comments, so
                        the watchdog is ping-aware for free. Default
                        `router/DEFAULT_IDLE_TIMEOUT_MS` (120 s).
     For Opus-style extended-thinking workloads bump `:idle-timeout-ms`
     to 240000-300000; reproductions of legitimate 185 s silences are
     documented in anthropics/claude-agent-sdk-typescript#44."
  [router opts]
  ;; Bind the caller's cancellation hook across the whole routed call (all
  ;; fallback attempts + backoff sleeps). See `*cancel-fn*`.
  (binding [*cancel-fn* (or (:cancel-fn opts) *cancel-fn*)]
    (let [resolved (router/resolve-routing router (routing-opts-with-reasoning opts))]
      (router/with-provider-fallback
        router (:prefs resolved)
        (fn [provider model-map]
          (ask!* router (inject-routed-params opts provider model-map)))))))
;; =============================================================================
;; ask!* - Main structured output function (primitive)
;; =============================================================================

;; =============================================================================
;; Format-retry support - in-process retry on schema-rejected provider output
;; =============================================================================
;;
;; Some providers (notably GLM-5.1 under `:deep` reasoning) emit a bare prose
;; string in `content` instead of the schema-conformant JSON object. svar
;; rejects loudly with `:svar.spec/schema-rejected`, but if every rejection
;; bubbles up to the caller's agent loop (Vis/RLM/etc.), the user pays for
;; provider noise out of their iteration budget AND loses post-mortem signal.
;;
;; `:format-retries` lets `ask!*` absorb that noise locally: catch the
;; schema-rejection, append a tiny FORMAT-RETRY turn to messages, and re-call
;; the provider. Tokens are still billed (we cannot avoid the bad attempt's
;; tokens - the provider already produced them) but the caller sees one
;; logical `ask!` call, not N visible failed iterations.
;;
;; Retries are NOT semantic agent retries. The retry message is a transport
;; instruction, not feedback to the model's task. Keep it short.

(def ^:private DEFAULT_FORMAT_RETRY_TYPES
  "Exception `:type`s that trigger an in-process format retry. Default set
   covers schema-shape failures (bare prose, wrong top-level type, missing
   required fields). Callers can extend via `:format-retry-on`."
  #{:svar.spec/schema-rejected
    :svar.spec/required-field-missing})

;; =============================================================================
;; Schema tail pointer - recency anchor past the cache breakpoint
;; =============================================================================
;;
;; The full schema-prompt is inlined into the SYSTEM message for cache
;; friendliness (Anthropic breakpoint covers caller-system + schema in one
;; cached prefix; OpenAI implicit caching keys on the same stable head).
;; That placement, taken alone, weakens schema adherence: the model attends
;; most strongly to the most recent tokens before generation, and a long
;; multi-turn transcript can dilute a head-anchored schema.
;;
;; The tail pointer is a tiny (~30 tok) reminder appended as the LAST text
;; block of the LAST user message. It does NOT repeat the schema body -
;; that stays cached at the head - it just points back to it. Sits PAST
;; any cache breakpoint on Anthropic, so it never bloats the cached prefix
;; and never burns a breakpoint slot. Tokens billed per call are cheap and
;; constant.
;;
;; Empirically restores recency-driven schema adherence to pre-v0.4.0
;; levels while keeping the v0.4.0 cache wins. Callers who explicitly want
;; the head-only placement (e.g. quirky local models that double-emit on
;; reminders) can opt out with `:schema-tail-pointer? false`.

(def ^:private SCHEMA_TAIL_POINTER
  "Short schema-pointer text appended as the last block of the last user
   message. Does not repeat the schema body - points back to the cached
   schema in the system message. Sits past the cache breakpoint, so it is
   billed but never cached, never burns a breakpoint slot."
  (str "Reply with one JSON object matching the schema in the system message.\n"
    "First non-whitespace character MUST be `{`. "
    "No prose. No markdown commentary. No explanation."))

(defn- append-schema-tail-pointer
  "Appends `SCHEMA_TAIL_POINTER` as a trailing text block on the LAST user
   message. If no user message exists in `messages`, appends a new user
   message carrying just the pointer (degenerate input - schema-only ask).

   Multimodal content is preserved: the pointer is added as one extra text
   block alongside any existing text/image blocks in the user content vec."
  [messages]
  (let [v (vec messages)
        last-user-idx (->> v
                        (map-indexed vector)
                        (filter (fn [[_ m]] (= "user" (:role m))))
                        last
                        first)]
    (if last-user-idx
      (update v last-user-idx
        (fn [{:keys [content] :as m}]
          (assoc m :content
            (conj (normalize-content content)
              {:type "text" :text SCHEMA_TAIL_POINTER}))))
      (conj v {:role "user"
               :content [{:type "text" :text SCHEMA_TAIL_POINTER}]}))))

(defn- code-tail-pointer-text
  "One-line format reminder appended as the last text block of the last
   user message in `ask-code!*`. Mirrors `SCHEMA_TAIL_POINTER` for the
   plain-text-with-fenced-code path: nudges the model back to the format
   contract right before generation, restoring recency-driven adherence
   on long transcripts.

   Kept to ONE line on purpose: longer reminders waste per-call tokens and
   dilute the immediately-preceding task text.

   Two contracts, by mode:
   - default (fenced): the model must tag exactly one ```lang fence, else the
     block is dropped — the strict-lang warning stays explicit so a model
     emitting untagged ``` fences sees why its code never reached the runtime.
   - `:lenient` (single-engine): there is NO fence — the WHOLE reply is the
     program — so preaching fences is actively wrong. The reminder flips to the
     pure-code contract: code only, run verbatim, no prose/heading (a leading
     sentence makes the entire reply a syntax error)."
  [lang lenient?]
  (if lenient?
    (str "Reply with code ONLY — your ENTIRE message runs verbatim as one " lang
      " program; no prose or Markdown outside " lang " comments. A leading sentence "
      "or heading makes the whole reply a syntax error. Write plain ASCII in code: a "
      "smart em-dash (—), en-dash, curly quote (“ ” ‘ ’), or × outside a string or "
      "comment is itself a syntax error — use '-' and straight quotes (file/ctx text "
      "you read may contain them; keep such text quoted).")
    (str "Reply with exactly one ```" lang " … ``` fenced block; untagged or other-lang fences are DROPPED.")))

(defn- append-code-tail-pointer
  "`append-schema-tail-pointer` for the `ask-code!*` path. Appends the
   `code-tail-pointer-text` as a trailing text block on the LAST user
   message; synthesises a user message carrying just the pointer when
   `messages` has none. Multimodal content is preserved. `lenient?` selects
   the pure-code vs fenced reminder."
  [messages lang lenient?]
  (let [v (vec messages)
        pointer (code-tail-pointer-text lang lenient?)
        last-user-idx (->> v
                        (map-indexed vector)
                        (filter (fn [[_ m]] (= "user" (:role m))))
                        last
                        first)]
    (if last-user-idx
      (update v last-user-idx
        (fn [{:keys [content] :as m}]
          (assoc m :content
            (conj (normalize-content content)
              {:type "text" :text pointer}))))
      (conj v {:role "user"
               :content [{:type "text" :text pointer}]}))))

(defn- format-retry-prompt
  "Tiny re-prompt appended after the model's failed assistant response. Kept
   short - long retry messages waste tokens on every attempt and dilute the
   instruction. Echoes the parser's exact reason verbatim so the model sees
   which contract clause it violated."
  [attempt total reason received-type]
  (str "FORMAT RETRY (" attempt "/" total ").\n"
    "Previous response violated the structured-output contract:\n"
    "  reason: " (name (or reason :unknown)) "\n"
    "  received-type: " (or received-type "?") "\n\n"
    "Return ONLY one JSON object matching the schema in the system message.\n"
    "First non-whitespace character MUST be `{`.\n"
    "No prose. No markdown commentary. No explanation."))

(defn- append-format-retry-turn
  "Appends [assistant<previous-bad-content>, user<retry-prompt>] to messages
   so the model sees its own failed output AND the corrective instruction.
   Empirically outperforms a bare retry instruction - the model self-diagnoses
   what it produced."
  [messages prev-content attempt total reason received-type]
  (conj (vec messages)
    {:role "assistant" :content [{:type "text" :text (or prev-content "")}]}
    {:role "user"      :content [{:type "text"
                                  :text (format-retry-prompt
                                          attempt total reason received-type)}]}))

(defn- envelope-data
  "Forensic envelope merged into every error ex-data thrown from `ask!*`. No
   truncation - callers (Vis triage, post-mortem tooling) need the full raw
   value to reproduce / persist / display. If a consumer wants a preview, it
   can substr `:content` itself; svar must not destroy forensic data here."
  [{:keys [model api-style chat-url duration-ms api-usage
           reasoning content provider-state http-response provider-id]}]
  (cond-> {:model         model
           :api-style     api-style
           :chat-url      chat-url
           :duration-ms   duration-ms
           :api-usage     api-usage
           :reasoning     reasoning
           :content       content
           :provider-state provider-state
           :http-response http-response}
    provider-id (assoc :provider-id provider-id)))

(defn- api-usage->tokens
  "Project canonical api-usage shape (Phase A) onto the flat caller-facing
   `:tokens` map returned by ask! / ask-code!.

   The canonical shape carries :input-tokens / :input-tokens-details /
   :output-tokens / :output-tokens-details / :total-tokens / :raw.
   This projection flattens onto the historical keys with TOTAL semantics:

     {:input         <input-tokens, TOTAL across providers>
      :output        <output-tokens>
      :reasoning     <output-tokens-details.reasoning>
      :total         <total-tokens>
      :cached        <input-tokens-details.cache-read>
      :cache-created <input-tokens-details.cache-write>
      :input-regular <input-tokens-details.regular>}

   `:input` now ALWAYS means TOTAL prompt tokens — Anthropic-additive
   raw values are summed at the canonical-normalizer boundary, so vis
   loop / TUI footer / Telegram tagline read one consistent value."
  [api-usage]
  (usage/canonical->tokens api-usage))

(defn- token-stats->tokens
  [token-stats]
  (let [cached (:cached-tokens token-stats)
        cache-created (:cache-creation-tokens token-stats)]
    (cond-> {:input     (:input-tokens token-stats)
             :output    (:output-tokens token-stats)
             :reasoning (:reasoning-tokens token-stats)
             :total     (:total-tokens token-stats)}
      (some? cached) (assoc :cached cached)
      (some? cache-created) (assoc :cache-created cache-created))))

(defn ask!*
  "Low-level ask - calls the LLM directly without routing. Use ask! instead.

   Includes automatic pre-flight context limit checking. If your input exceeds
   the model's context window, throws a clear error with actionable suggestions
   BEFORE making the API call.

   Supports multimodal input via the `user` + `image` helpers.

   Params:
   `opts` - Map with keys:
     - :spec - Spec definition, required.
     - :messages - Vector of message maps, required.
     - :model - String, required. LLM model to use.
     - :output-reserve - Integer, optional.
     - :check-context? - Boolean, optional. When on (default), DIRECT
       Anthropic providers (`:api-style :anthropic`) auto-refine the
       pre-flight estimate with Anthropic's free exact `count_tokens` API
       near the context limit (>= `router/CONTEXT_REFINE_UTILIZATION`),
       falling back to the offline tiktoken estimate on any failure.
     - :timeout-ms - Integer, optional.
     - :format-retries - Integer, optional. Default 0. Number of in-process
       retries when the provider returns content that fails schema parsing
       (e.g. bare prose, wrong top-level type, missing required fields).
       Each retry is a separate HTTP call - tokens are still billed by the
       provider - but the caller sees one logical `ask!` call rather than
       multiple visible failures. Disabled when `:on-chunk` is provided
       (streaming + retries don't compose). See also `:format-retry-on`.
     - :format-retry-on - Set of exception `:type` keywords that trigger a
       format retry. Defaults to
       #{:svar.spec/schema-rejected :svar.spec/required-field-missing}.
       Callers can opt in to retrying `:svar.llm/empty-content` (provider
       returned reasoning but no content) by including it in the set.
     - :json-object-mode? - Boolean, optional. When true AND the selected
       provider uses `:openai-compatible-chat` api-style, auto-injects
       `response_format: {type: \"json_object\"}` into the request body.
       Hardens prose-leaking models (GLM family historically leaks prose into
       `content` under `:deep` reasoning). When unset, `ask!` uses the routed
       model's metadata (GLM family is opted in by default). When the caller
       already sets `:response_format` in `:extra-body`, that wins.
     - :schema-tail-pointer? - Boolean, optional. Default true. Appends a
       short (~30 tok) reminder as the last text block of the last user
       message that points back to the schema in the system message. Does
       NOT repeat the schema body - the body stays cached at the head.
       Restores recency-driven schema adherence after head-inlining the
       schema for cache friendliness. Pass `false` for head-only mode
       (rare - mostly for quirky local models that double-emit on
       reminders, or for benchmarking the placement effect).

   Returns:
   Map with :result, :tokens, :cost, :duration-ms. When format retries
   occurred, includes :format-attempts - a vector of per-attempt records
   carrying the full content, reasoning, api-usage, and rejection reason for
   each failed attempt before the final success.

   Throws:
   ex-info with full forensic envelope merged into ex-data: :model,
   :api-style, :chat-url, :duration-ms, :api-usage, :reasoning, :content,
   :http-response, plus :format-attempts when retries were exhausted. No
   truncation - ex-data is the canonical post-mortem record."
  [router opts0]
  (let [{:keys [spec schema-tail-pointer?] :as opts0} opts0
        {:keys [model api-key base-url api-style timeout-ms ttft-timeout-ms idle-timeout-ms semantic-timeout-ms check-context? output-reserve network pricing context-limits responses-path llm-headers]} (resolve-opts router opts0)
        provider-id (:provider-id opts0)
        chat-url (make-chat-url base-url api-style)
        schema-prompt (spec/spec->prompt spec)
        ;; Schema placement: full schema body is inlined into the SYSTEM
        ;; message (head, cache-friendly) AND a tiny tail pointer is
        ;; appended to the LAST user message (recency-friendly). The
        ;; pointer does not repeat the schema body - it just points back
        ;; to the cached schema in system. Trade-off rationale:
        ;;  - Schema body is the most stable thing in the payload; head
        ;;    placement lets a single cache_control breakpoint (or OpenAI
        ;;    implicit caching) cache caller-system + schema together.
        ;;  - Tail pointer sits past the breakpoint, so it is billed per
        ;;    call but never cached and never burns a breakpoint slot.
        ;;  - Recency-bias on schema adherence is restored without losing
        ;;    cache wins. Pre-v0.4.0 (schema as the only tail content)
        ;;    had recency but no caching. v0.4.0 (head-only) had caching
        ;;    but degraded adherence. This is both.
        ;;  - `:schema-tail-pointer? false` opts out (head-only mode) for
        ;;    quirky models that double-emit on reminders.
        ;; Step 1: schema injection FIRST so that auto-cache can tag
        ;; the actual last block (schema-prompt) after we've added it.
        ;; Running apply-llm-opts before schema injection would tag the
        ;; ORIGINAL last block, but then schema-injection appends a new
        ;; block past the marker — cache breakpoint lands on the
        ;; pre-schema block, schema body falls outside the cache prefix.
        in-msgs   (vec (:messages opts0))
        sys-idx   (->> in-msgs
                    (map-indexed vector)
                    (some (fn [[i m]] (when (= "system" (some-> (:role m) name)) i))))
        with-spec (if sys-idx
                    (update in-msgs sys-idx
                      (fn [{:keys [content] :as m}]
                        (assoc m :content
                          (conj (normalize-content content)
                            {:type "text" :text schema-prompt}))))
                    (into [{:role "system"
                            :content [{:type "text" :text schema-prompt}]}]
                      in-msgs))
        ;; Step 2: caller-opt → wire-body chokepoint. Runs ONCE per call
        ;; so role normalisation / top-level :system / auto-cache /
        ;; :cache-key forwarding all land in one place. With schema
        ;; already injected, auto-cache tags the last block which is
        ;; the schema prompt itself — exactly what we want.
        ;; Wrappers (abstract!*, eval!*, refine!*, sample!*) inherit by
        ;; merging caller opts into their inner ask!* call.
        [with-cache opts] (apply-llm-opts with-spec opts0)
        ;; Tail pointer ON by default - only skipped when caller passes
        ;; an explicit `false`. `nil`/missing → ON.
        with-tail (if (false? schema-tail-pointer?)
                    with-cache
                    (append-schema-tail-pointer with-cache))
        base-messages with-tail
          ;; Pre-flight context check (also counts input tokens for reuse)
        check-opts (cond-> {:context-limits context-limits
                            :exact-count-fn (anthropic-exact-count-fn base-messages model
                                              {:api-style api-style :api-key api-key
                                               :base-url base-url :provider-id provider-id
                                               :llm-headers llm-headers})}
                     output-reserve (assoc :output-reserve output-reserve))
        context-check (when check-context?
                        (let [check (router/check-context-limit model base-messages check-opts)]
                          (when-not (:ok? check)
                            (anomaly/incorrect! (:error check)
                              {:type :svar.core/context-overflow
                               :model model
                               :input-tokens (:input-tokens check)
                               :max-input-tokens (:max-input-tokens check)
                               :overflow (:overflow check)
                               :utilization (:utilization check)
                               :suggestion (str "Reduce task content by ~"
                                             (int (* (double (:overflow check)) 0.75)) " words, "
                                             "or use a larger context model.")}))
                          check))
          ;; API call - streaming if :on-chunk provided
        on-chunk (:on-chunk opts)
        streaming-on-chunk (when on-chunk
                             (fn [{:keys [content reasoning provider-state api-usage]}]
                               (let [tokens (api-usage->tokens api-usage)
                                     cost (when api-usage
                                            (router/estimate-cost model
                                              (or (:input-tokens api-usage) 0)
                                              (or (:output-tokens api-usage) 0)
                                              pricing
                                              {:api-usage api-usage
                                               :api-style api-style}))
                                     partial-map (jsonish/parse-partial content)
                                     coerced (when partial-map
                                               (try (spec/str->data-with-spec
                                                      (json/write-json-str partial-map) spec)
                                                    (catch Exception _ partial-map)))]
                                 ;; Fire callback when reasoning OR content is available.
                                 ;; Reasoning streams before content - don't gate on content.
                                 (when (or coerced (some? reasoning))
                                   (on-chunk {:result coerced
                                              :reasoning reasoning
                                              :provider-state provider-state
                                              :tokens tokens
                                              :cost (when cost (select-keys cost [:input-cost :output-cost :total-cost]))
                                              :done? false})))))
        ;; `:json-object-mode?` auto-injection - caller's `:extra-body
        ;; :response_format` always wins. OpenAI chat-completions and
        ;; OpenAI Responses both support JSON mode; Anthropic ignores it.
        ;; `:json-object-mode?` auto-injection - caller's `:extra-body
        ;; :response_format` always wins. OpenAI chat-completions and
        ;; OpenAI Responses both support JSON mode; Anthropic ignores it.
        caller-extra-body (or (:extra-body opts) {})
        extra-body (cond-> caller-extra-body
                     (and (contains? #{:openai-compatible-chat :openai-compatible-responses} api-style)
                       (:json-object-mode? opts)
                       (not (contains? caller-extra-body :response_format)))
                     (assoc :response_format {:type "json_object"}))
        retry-opts (cond-> (merge network {:timeout-ms timeout-ms :api-style api-style
                                           ;; See `ask-code!*`: carry resolved streaming
                                           ;; timeouts verbatim so explicit nil disables any.
                                           :ttft-timeout-ms ttft-timeout-ms
                                           :idle-timeout-ms idle-timeout-ms
                                           :semantic-timeout-ms semantic-timeout-ms})
                     provider-id (assoc :provider-id provider-id)
                     streaming-on-chunk (assoc :on-chunk streaming-on-chunk)
                     (seq extra-body) (assoc :extra-body extra-body)
                     responses-path (assoc :responses-path responses-path)
                     llm-headers (assoc :llm-headers llm-headers))
        ;; Format-retry config. Streaming + retries don't compose (per-attempt
        ;; partial-parse callbacks would confuse consumers about which attempt
        ;; the stream belongs to), so retries are forced to 0 in streaming.
        format-retries (long (or (:format-retries opts) 0))
        retry-types (or (:format-retry-on opts) DEFAULT_FORMAT_RETRY_TYPES)
        effective-retries (if streaming-on-chunk 0 format-retries)
        do-attempt
        (fn do-attempt [msgs attempt-n]
          (let [[{:keys [content reasoning provider-state api-usage http-response]} attempt-duration-ms]
                (util/with-elapsed
                  (chat-completion msgs model api-key chat-url retry-opts))]
            (trove/log! {:level :info
                         :data (log-data {:model model
                                          :duration-ms attempt-duration-ms
                                          :input-tokens (:input-tokens api-usage)
                                          :output-tokens (:output-tokens api-usage)
                                          ;; nil distinguishes "no field in response" from
                                          ;; "field present but empty" - crucial for triaging
                                          ;; provider quirks where content is omitted entirely
                                          ;; versus returned as an empty string.
                                          :reasoning-length (when reasoning (count reasoning))
                                          :content-length   (when content (count content))
                                          :content-preview  (when content
                                                              (subs content 0 (min 200 (count content))))
                                          :attempt          attempt-n})
                         :msg "HTTP response received"})
            ;; Some providers (notably reasoning-capable models) return HTTP 200
            ;; with a non-empty `reasoning_content` but an empty or nil `content`
            ;; field - usually because the output budget was consumed by reasoning
            ;; or the spec could not be satisfied for the given input. Letting this
            ;; propagate into `jsonish/parse-json` leaks a parser-internal
            ;; "Input cannot be nil or empty" IllegalArgumentException to callers.
            ;; Instead surface a typed, prompt-quality error with the model's
            ;; reasoning attached, so RLM loops can feed an actionable message back
            ;; to the model and persist the reasoning for triage. Full envelope,
            ;; never truncated.
            (when (str/blank? content)
              (throw (ex-info
                       (str "The model produced reasoning but no structured JSON output. "
                         "This usually means the response budget was consumed by reasoning, "
                         "the spec could not be satisfied for the given input, or the task "
                         "is ambiguous. Retry by emitting a minimal valid JSON matching the "
                         "iteration spec; if the task is ambiguous, clarify intent or shrink "
                         "context.")
                       (assoc (envelope-data
                                {:model model :api-style api-style :chat-url chat-url
                                 :duration-ms attempt-duration-ms :api-usage api-usage
                                 :reasoning reasoning :content content
                                 :provider-state provider-state
                                 :http-response http-response :provider-id provider-id})
                         :type :svar.llm/empty-content
                         :attempt attempt-n))))
            {:content        content
             :reasoning      reasoning
             :provider-state provider-state
             :api-usage      api-usage
             :http-response  http-response
             :duration-ms    attempt-duration-ms}))]
    (loop [msgs base-messages
           attempt 0
           prior-attempts []]
      ;; Two-step outcome: HTTP first (which may throw `:svar.llm/empty-content`
      ;; with the envelope ALREADY in ex-data because `do-attempt` populated
      ;; it), then parse (which throws `:svar.spec/schema-rejected` /
      ;; `:svar.spec/required-field-missing` from the spec layer - spec has
      ;; no idea about HTTP, so its ex-data carries no envelope). We capture
      ;; the HTTP envelope OUTSIDE the parse try/catch so it's available for
      ;; both branches and merges into the terminal throw regardless of which
      ;; step failed.
      (let [http-outcome
            (try
              (assoc (do-attempt msgs attempt) :ok? true)
              (catch clojure.lang.ExceptionInfo e
                (let [data (ex-data e)
                      ex-type (:type data)]
                  {:ok?           false
                   :ex            e
                   :ex-type       ex-type
                   :reason        (:reason data)
                   :received-type (:received-type data)
                   :retryable?    (contains? retry-types ex-type)
                   :content       (:content data)
                   :reasoning     (:reasoning data)
                   :provider-state (:provider-state data)
                   :api-usage     (:api-usage data)
                   :http-response (:http-response data)
                   :duration-ms   (:duration-ms data)})))
            ;; If HTTP succeeded, attempt the parse. Bind envelope first so
            ;; it's in scope for both success and parse-failure branches.
            {:keys [content reasoning provider-state api-usage http-response duration-ms]} http-outcome
            parse-outcome
            (when (:ok? http-outcome)
              (try
                (let [token-stats (router/count-and-estimate model msgs content
                                    (cond-> {:pricing pricing
                                             :api-usage api-usage
                                             :api-style api-style}
                                      context-check (assoc :input-tokens (:input-tokens context-check))))]
                  {:ok? true
                   :result (spec/str->data-with-spec content spec)
                   :token-stats token-stats})
                (catch clojure.lang.ExceptionInfo e
                  (let [data (ex-data e)
                        ex-type (:type data)]
                    {:ok?           false
                     :ex            e
                     :ex-type       ex-type
                     :reason        (:reason data)
                     :received-type (:received-type data)
                     :retryable?    (contains? retry-types ex-type)}))))
            ;; Unify outcome:
            ;;   - HTTP failed       -> http-outcome
            ;;   - HTTP ok + parse ok-> parse-outcome merged with envelope
            ;;   - HTTP ok + parse !ok -> parse-outcome merged with envelope
            outcome (cond
                      (not (:ok? http-outcome)) http-outcome
                      (:ok? parse-outcome)
                      (assoc parse-outcome
                        :content content :reasoning reasoning
                        :provider-state provider-state
                        :api-usage api-usage :http-response http-response
                        :duration-ms duration-ms)
                      :else
                      (assoc parse-outcome
                        :content content :reasoning reasoning
                        :provider-state provider-state
                        :api-usage api-usage :http-response http-response
                        :duration-ms duration-ms))]
        (cond
          ;; SUCCESS - parse worked; fire done callback, return.
          (:ok? outcome)
          (let [{:keys [result token-stats]} outcome
                final-result result
                attempt-record {:attempt     attempt
                                :ok?         true
                                :duration-ms duration-ms
                                :api-usage   api-usage
                                :content     content
                                :reasoning   reasoning
                                :provider-state provider-state}
                all-attempts (conj prior-attempts attempt-record)]
            (when on-chunk
              (on-chunk {:result final-result
                         :reasoning reasoning
                         :provider-state provider-state
                         :tokens (token-stats->tokens token-stats)
                         :cost (select-keys (:cost token-stats) [:input-cost :output-cost :total-cost])
                         :done? true}))
            (cond-> {:result final-result
                     :tokens (token-stats->tokens token-stats)
                     :cost (select-keys (:cost token-stats) [:input-cost :output-cost :total-cost])
                     :duration-ms duration-ms}
              reasoning              (assoc :reasoning reasoning)
              provider-state         (assoc :provider-state provider-state)
              ;; Only surface :format-attempts when retries actually happened.
              ;; Empty / single-element vec is noise on the happy path.
              (> (count all-attempts) 1) (assoc :format-attempts all-attempts)))

          ;; RETRYABLE FAILURE with budget remaining - append format-retry turn
          ;; and recur. Token cost of the bad attempt is already sunk (provider
          ;; produced + billed it); we record it for forensic accounting.
          (and (:retryable? outcome)
            (< attempt effective-retries))
          (let [{:keys [reason received-type ex-type content reasoning provider-state
                        api-usage http-response duration-ms]} outcome
                ;; For empty-content the model produced no `content` to echo
                ;; back. Synthesize a placeholder so the assistant turn isn't
                ;; literally empty (some providers reject empty assistant
                ;; messages on the next call).
                echo-content (if (str/blank? content)
                               "<no content; reasoning-only response>"
                               content)
                attempt-record {:attempt       attempt
                                :ok?           false
                                :ex-type       ex-type
                                :reason        reason
                                :received-type received-type
                                :duration-ms   duration-ms
                                :api-usage     api-usage
                                :content       content
                                :reasoning     reasoning
                                :provider-state provider-state
                                :http-response http-response}]
            (trove/log! {:level :warn :id ::format-retry
                         :data (log-data {:model model :attempt attempt
                                          :max-retries effective-retries
                                          :reason reason
                                          :received-type received-type
                                          :ex-type ex-type
                                          :content-length (when content (count content))})
                         :msg "format-retry: parse failed; re-prompting"})
            (recur (append-format-retry-turn msgs echo-content
                     (inc attempt) effective-retries
                     (or reason ex-type) received-type)
              (inc attempt)
              (conj prior-attempts attempt-record)))

          ;; TERMINAL FAILURE - either non-retryable type or retries exhausted.
          ;; Re-throw with full forensic envelope merged into ex-data.
          :else
          (let [{:keys [ex ex-type reason received-type content reasoning provider-state
                        api-usage http-response duration-ms]} outcome
                attempt-record {:attempt       attempt
                                :ok?           false
                                :ex-type       ex-type
                                :reason        reason
                                :received-type received-type
                                :duration-ms   duration-ms
                                :api-usage     api-usage
                                :content       content
                                :reasoning     reasoning
                                :provider-state provider-state
                                :http-response http-response}
                all-attempts (conj prior-attempts attempt-record)]
            (throw (ex-info (ex-message ex)
                     (merge (ex-data ex)
                       (envelope-data
                         {:model model :api-style api-style :chat-url chat-url
                          :duration-ms duration-ms :api-usage api-usage
                          :reasoning reasoning :content content
                          :provider-state provider-state
                          :http-response http-response :provider-id provider-id})
                       {:format-attempts          all-attempts
                        :format-retries-attempted attempt
                        :format-retries-allowed   effective-retries})
                     ex))))))))

;; =============================================================================
;; ask-code! / ask-code!* - Plain-text completion + fenced code-block extraction
;; =============================================================================
;;
;; Sibling of `ask!` for callers that want raw source (typically Clojure)
;; instead of a structured JSON envelope. No spec, no schema-prompt inlining,
;; no JSON-mode tricks. Sends `:messages` verbatim, parses the assistant
;; response with `codes/extract-code-blocks`, filters by `:lang` (default
;; "clojure"), and returns the concatenated source.
;;
;; Empty `:result` (no matching code blocks) is a VALID success - the caller
;; decides what to do (semantic retry with reminder, treat as no-op, etc.).
;; svar throws only on transport-level failures: HTTP errors propagate from
;; `chat-completion`; `:svar.llm/empty-content` is thrown when the provider
;; returns no content at all (reasoning-only response). No format-retry loop
;; here - extraction shape is the caller's contract, not svar's.
;;
;; Tail-pointer placement (`:code-tail-pointer?`) mirrors the schema-tail
;; pointer on `ask!*`: a short code-format reminder is appended as the last
;; text block of the last user message, default ON, opt-out via
;; `:code-tail-pointer? false`. Restores recency-driven format adherence on
;; long transcripts without burning a cache breakpoint.

(defn ask-code!*
  "Low-level ask-code - calls the LLM directly without routing. Use `ask-code!`.

   See `ask-code!` for the full param + return contract."
  [router opts0]
  (let [;; Caller-opt → wire-body chokepoint. Runs ONCE per call so
        ;; role normalisation / top-level :system / auto-cache /
        ;; :cache-key forwarding all land in one place regardless of
        ;; entry point.
        [messages opts1] (apply-llm-opts (:messages opts0) opts0)
        opts             (assoc opts1 :messages messages)
        {:keys [on-chunk code-tail-pointer?]} opts
        lang (:lang opts)
        _ (when-not (and (string? lang) (not (str/blank? lang)))
            ;; `:lang` is REQUIRED. No default. `select-blocks` filters
            ;; strictly on this tag; without it we don't know what to keep.
            (anomaly/incorrect! ":lang is required on ask-code! and must be a non-blank string"
              {:type :svar.core/invalid-lang :lang lang}))
        {:keys [model api-key base-url api-style timeout-ms ttft-timeout-ms idle-timeout-ms semantic-timeout-ms output-reserve
                check-context? network pricing context-limits responses-path llm-headers]}
        (resolve-opts router opts)
        provider-id (:provider-id opts)
        chat-url (make-chat-url base-url api-style)
        in-msgs (vec messages)
        ;; Default ON: append the code-format reminder as the LAST text
        ;; block of the LAST user message. Only literal `false` opts out
        ;; (nil / missing keep the default), matching ask!*'s semantics.
        with-tail (if (false? code-tail-pointer?)
                    in-msgs
                    (append-code-tail-pointer in-msgs lang (boolean (:lenient opts))))
        check-opts (cond-> {:context-limits context-limits
                            :exact-count-fn (anthropic-exact-count-fn with-tail model
                                              {:api-style api-style :api-key api-key
                                               :base-url base-url :provider-id provider-id
                                               :llm-headers llm-headers})}
                     output-reserve (assoc :output-reserve output-reserve))
        context-check (when check-context?
                        (let [check (router/check-context-limit model with-tail check-opts)]
                          (when-not (:ok? check)
                            (anomaly/incorrect! (:error check)
                              {:type :svar.core/context-overflow
                               :model model
                               :input-tokens (:input-tokens check)
                               :max-input-tokens (:max-input-tokens check)
                               :overflow (:overflow check)
                               :utilization (:utilization check)
                               :suggestion (str "Reduce task content by ~"
                                             (int (* (double (:overflow check)) 0.75)) " words, "
                                             "or use a larger context model.")}))
                          check))
        ;; Per-chunk on-chunk: signal progress only. Never re-parse the
        ;; accumulated buffer here — `codes/extract-code-blocks` is
        ;; O(N) per call and the buffer grows monotonically, so doing
        ;; it on every SSE delta is O(N²) and pegs CPU on long
        ;; responses (reproduced live in Vis conv 0c8188ac, glm-5.1,
        ;; thread stuck in `normalize-fence-closers`). The final parse
        ;; happens exactly once after `chat-completion` returns and is
        ;; surfaced in the `:done? true` chunk + return value below.
        streaming-on-chunk (when on-chunk
                             (fn [{:keys [content reasoning provider-state api-usage]}]
                               (let [tokens (api-usage->tokens api-usage)
                                     cost (when api-usage
                                            (router/estimate-cost model
                                              (or (:input-tokens api-usage) 0)
                                              (or (:output-tokens api-usage) 0)
                                              pricing
                                              {:api-usage api-usage
                                               :api-style api-style}))]
                                 (when (or (not (str/blank? (or reasoning "")))
                                         (not (str/blank? (or content ""))))
                                   (on-chunk {:blocks    nil
                                              :raw       content
                                              :reasoning reasoning
                                              :provider-state provider-state
                                              :tokens    tokens
                                              :cost      (when cost (select-keys cost [:input-cost :output-cost :total-cost]))
                                              :done?     false})))))
        caller-extra-body (or (:extra-body opts) {})
        retry-opts (cond-> (merge network {:timeout-ms timeout-ms :api-style api-style
                                           ;; Carry resolved streaming timeouts verbatim
                                           ;; (incl. explicit nil) so `chat-completion`
                                           ;; sees post-`resolve-opts` precedence.
                                           :ttft-timeout-ms ttft-timeout-ms
                                           :idle-timeout-ms idle-timeout-ms
                                           :semantic-timeout-ms semantic-timeout-ms})
                     provider-id (assoc :provider-id provider-id)
                     streaming-on-chunk (assoc :on-chunk streaming-on-chunk)
                     (seq caller-extra-body) (assoc :extra-body caller-extra-body)
                     responses-path (assoc :responses-path responses-path)
                     llm-headers (assoc :llm-headers llm-headers))
        [{:keys [content reasoning provider-state assistant-message api-usage http-response
                 stream-finalization]} duration-ms]
        (util/with-elapsed
          (chat-completion with-tail model api-key chat-url retry-opts))
        stream-finalization (or stream-finalization
                              (:stream-finalization http-response))]
    (trove/log! {:level :info
                 :data (log-data (cond-> {:model model
                                          :duration-ms duration-ms
                                          :input-tokens (:input-tokens api-usage)
                                          :output-tokens (:output-tokens api-usage)
                                          :reasoning-length (when reasoning (count reasoning))
                                          :content-length   (when content (count content))
                                          :content-preview  (when content
                                                              (subs content 0 (min 200 (count content))))}
                                   stream-finalization
                                   (assoc :stream-finalization stream-finalization)))
                 :msg "ask-code! HTTP response received"})
    (when (str/blank? content)
      ;; Distinguish max_tokens cap from genuine empty-content. Both
      ;; arrive here with `:content blank`, but the failure modes are
      ;; different:
      ;;   :svar.llm/max-tokens-exceeded — model burnt the entire
      ;;     output budget on hidden reasoning before opening a code
      ;;     fence. Provider reports `finish_reason: \"length\"`.
      ;;     Retry path: bump `:extra-body {:max_tokens N}` 2× and try
      ;;     once more. Reasoning length is real evidence to show the
      ;;     user / log: model was thinking, just couldn't ship.
      ;;   :svar.llm/empty-content — model returned nothing useful with
      ;;     `finish_reason: \"stop\"` or no reason at all (rare; e.g.
      ;;     prompt confused the model). Retry path: caller-level
      ;;     `:format-retry-on` opt-in only; svar does NOT auto-retry
      ;;     because the next call would emit the same blank.
      ;;
      ;; Surfaces a dedicated `:retry-hint` payload so callers can
      ;; either (a) bump max_tokens directly or (b) re-route to a
      ;; longer-output model. `:reasoning-length` lets the UI render a
      ;; \"model spent ~2K tokens reasoning before timing out\" line
      ;; instead of the generic \"no content\" placeholder.
      (let [finish-reason (some-> stream-finalization :finish-reason str)
            length-cap? (= "length" finish-reason)
            reasoning-len (long (or (some-> reasoning count) 0))
            output-tokens (long (or (:output-tokens api-usage) 0))
            base-envelope (envelope-data
                            {:model model :api-style api-style :chat-url chat-url
                             :duration-ms duration-ms :api-usage api-usage
                             :reasoning reasoning :content content
                             :provider-state provider-state
                             :assistant-message assistant-message
                             :http-response http-response
                             :stream-finalization stream-finalization
                             :provider-id provider-id})]
        (if length-cap?
          (throw (ex-info
                   (str "Stream truncated at max_tokens (" output-tokens
                     " output tokens consumed, " reasoning-len
                     " went to hidden reasoning, 0 to visible content). "
                     "Raise `:extra-body {:max_tokens N}` for this call "
                     "or shorten input/reasoning so the model has room "
                     "to emit content.")
                   (assoc base-envelope
                     :type :svar.llm/max-tokens-exceeded
                     :finish-reason finish-reason
                     :reasoning-length reasoning-len
                     :output-tokens output-tokens
                     :retry-hint :raise-max-tokens)))
          (throw (ex-info
                   (str "The model produced reasoning but no content. "
                     "`ask-code!` needs textual content with code fences.")
                   (assoc base-envelope :type :svar.llm/empty-content))))))
    (let [lenient? (boolean (:lenient opts))
          {:keys [blocks saw-fence? malformed?]}
          (if lenient?
            ;; Lenient mode: the whole reply IS the code (single-engine
            ;; callers). No fence scan, no lang filtering, nothing dropped —
            ;; `codes/lenient-block` strips at most one outer wrapper fence.
            {:blocks    (if-let [b (codes/lenient-block content lang)] [b] [])
             :saw-fence? false
             :malformed? false}
            (codes/extract-code-blocks-detail content))
          selected    (->> (if lenient? blocks (codes/select-blocks blocks lang))
                        (remove #(str/blank? (:source %)))
                        vec)
          token-stats (router/count-and-estimate model with-tail content
                        (cond-> {:pricing pricing
                                 :api-usage api-usage
                                 :api-style api-style}
                          context-check (assoc :input-tokens (:input-tokens context-check))))
          tokens      (token-stats->tokens token-stats)
          cost        (select-keys (:cost token-stats) [:input-cost :output-cost :total-cost])]
      (when on-chunk
        (on-chunk {:blocks    selected
                   :all-blocks blocks
                   :saw-fence? saw-fence?
                   :malformed? malformed?
                   :raw       content
                   :reasoning reasoning
                   :provider-state provider-state
                   :stream-finalization stream-finalization
                   :tokens    tokens
                   :cost      cost
                   :done?     true}))
      (cond-> {:blocks      selected
               ;; `:all-blocks` is the pre-`select-blocks` vec. Callers
               ;; diagnose wrong-lang fences via `(count :all-blocks) >
               ;; (count :blocks)` and multi-fence emission via the same
               ;; count plus `:saw-fence?` / `:malformed?`. svar itself
               ;; only routes the lang-filtered `:blocks`; these extras
               ;; are observational.
               :all-blocks  blocks
               :saw-fence?  saw-fence?
               :malformed?  malformed?
               :raw         content
               :tokens      tokens
               :cost        cost
               :duration-ms duration-ms}
        reasoning           (assoc :reasoning reasoning)
        provider-state      (assoc :provider-state provider-state)
        assistant-message   (assoc :assistant-message assistant-message)
        http-response       (assoc :http-response http-response)
        stream-finalization (assoc :stream-finalization stream-finalization)))))

(defn ask-code!
  "Plain-text completion + fenced code-block extraction. Routed sibling of
   `ask!`.

   Params: same routing/network/streaming opts as `ask!`, minus `:spec`,
   `:format-retries`, `:format-retry-on`, `:json-object-mode?`. Adds:
     :lang - String, default \"clojure\". Selects blocks tagged that lang
             (case-insensitive) PLUS untagged blocks. Required-with-default;
             must be a non-blank string (`nil` / `\"\"` rejected).
     :code-tail-pointer? - Boolean, default true. Appends a short code-format
             reminder as the last text block of the last user message,
             pointing at the format contract (\"reply with `lang` source
             inside ```lang ... ``` fences\"). Restores recency-driven format
             adherence on long transcripts. Set to `false` to opt out.
     :lenient - Boolean, default false. Single-engine mode: the WHOLE reply
             is treated as one `:lang` block — no multi-fence scan, no lang
             filtering, nothing dropped (`codes/lenient-block` strips at most
             one outer wrapper fence). For agents whose every reply is one
             program for one turn; pair with `:code-tail-pointer? false`.

   Returns:
   {:result      <concatenated source string of selected blocks>
    :blocks      [{:lang <str-or-nil> :source <str>} ...]
    :raw         <full assistant text content>
    :reasoning   <provider reasoning channel, when present>
    :provider-state <opaque provider continuation state, when present>
    :assistant-message svar's canonical assistant turn:
                       `{:role \"assistant\" :content [<blocks>]}`.
                       Append it to `:messages` on the next call to
                       keep preserved thinking alive. Canonical
                       `{:type \"thinking\"}` content blocks carry the
                       per-provider preserved-reasoning signature
                       (Anthropic HMAC, OpenAI Responses raw item JSON,
                       z.ai verbatim text) under `:thinking-signature`;
                       the wire serializer transforms them to native
                       shapes. Only present for providers that emit
                       preserved reasoning.
    :tokens      {:input :output :reasoning :cached :total}
    :cost        {:input-cost :output-cost :total-cost}
    :duration-ms <ms>
    :stream-finalization {:terminal? :terminal-kind :finish-reason
                          :content-acc-len ...} ; streaming calls only
    :http-response {:status :streaming? :stream-finalization ...}}

   Empty `:blocks` is a valid success.

   Throws ex-info on transport-level failure (`:svar.llm/empty-content` when
   the provider returns no content; HTTP errors from `chat-completion`).
   Throws `:svar.core/invalid-lang` when `:lang` is missing/blank."
  [router opts]
  ;; Bind the caller's cancellation hook for the whole routed call so every
  ;; provider-fallback attempt (and its backoff sleeps) honours it. See
  ;; `*cancel-fn*`. `or` preserves an outer binding when opts omits it.
  (binding [*cancel-fn* (or (:cancel-fn opts) *cancel-fn*)]
    (let [resolved (router/resolve-routing router (routing-opts-with-reasoning opts))]
      (router/with-provider-fallback
        router (:prefs resolved)
        (fn [provider model-map]
          (ask-code!* router (inject-routed-params opts provider model-map)))))))

;; =============================================================================
;; models! - Fetch available models
;; =============================================================================

(defn- provider-model-id [model]
  (cond
    (string? model) model
    (map? model)    (or (:id model) (:name model) (get model "id") (get model "name"))
    :else           (str model)))

(defn- filter-provider-models [provider-id models]
  (if provider-id
    (filterv #(router/provider-model-visible? provider-id (provider-model-id %)) models)
    (vec models)))

(defn- host-root
  "scheme://authority of a URL — drops path/query. `http://h:1234/v1` → `http://h:1234`.
   Used when a provider's models endpoint lives at host root, not under the
   chat base path (LM Studio: `/api/v0/...`). Returns base-url unchanged when
   it can't be parsed."
  [base-url]
  (try
    (let [u (URI. base-url)]
      (str (.getScheme u) "://" (.getAuthority u)))
    (catch Exception _ base-url)))

(defn- models-endpoint-url
  "Build the models-listing URL for a provider. With `:models-base :host` the
   `:models-path` hangs off the host root (LM Studio's native REST); otherwise
   it appends to the chat base-url (OpenAI/Anthropic/Codex convention)."
  [base-url models-base models-path]
  (if (= :host models-base)
    (str (host-root base-url) models-path)
    (str base-url models-path)))

(defn- enrich-lmstudio-model
  "Map LM Studio native `/api/v0/models` fields onto svar model keys.
   Prefers `loaded_context_length` (the window the runtime ACTUALLY loaded —
   LM Studio can load a 262k model at 16k to fit RAM, and advertising the max
   would make the server truncate) over `max_context_length`. Surfaces
   `tool_use` capability and load state. Non-native shapes pass through."
  [m]
  (let [ctx (or (:loaded_context_length m) (:max_context_length m))]
    (cond-> m
      ctx                              (assoc :context (long ctx))
      (some #{"tool_use"} (:capabilities m)) (assoc :tool-call? true)
      (some? (:state m))               (assoc :loaded? (= "loaded" (:state m))))))

(defn- shape-models
  "Apply provider-specific model normalization keyed by `:models-shape`."
  [models-shape models]
  (case models-shape
    :lmstudio (mapv enrich-lmstudio-model models)
    models))

(defn- normalize-models-response
  "Normalize a `/models` response body to a vector of model maps with
   at least `{:id ...}`.

   Recognized shapes:
     - OpenAI/Anthropic standard:  `{:data [{:id ... :name ...} ...]}`
     - ChatGPT backend (Codex):    `{:models [{:slug ... :display_name ...} ...]}`
     - Plain vector:               `[{:id ...} ...]`

   Codex `slug` maps to `:id` so downstream `provider-model-id` works
   without special-casing. The original keys are preserved."
  [body]
  (cond
    (sequential? body) (vec body)

    (and (map? body) (sequential? (:data body)))
    (vec (:data body))

    (and (map? body) (sequential? (:models body)))
    (->> (:models body)
      (mapv (fn [m]
              (cond-> m
                (and (not (:id m)) (:slug m)) (assoc :id (:slug m))))))

    :else []))

(defn models!
  "Fetches available models from the LLM API. Provider-scoped model exclusions
   are applied to the returned `/models` list.

   Header dispatch (`make-llm-headers` via `http-get!`) covers:
     - Anthropic OAuth tokens (subscription / Claude Code) → full set
       of Anthropic OAuth headers including `anthropic-version`,
       `anthropic-beta`, `user-agent`, `x-app`.
     - Anthropic API keys → `x-api-key` + `anthropic-version`.
     - Everything else → `Authorization: Bearer <token>`.

   Provider-supplied `:llm-headers` are merged on top so providers
   like OpenAI Codex can attach `chatgpt-account-id`.

   Returns `[]` on HTTP failure rather than throwing - callers fall
   back to a catalog default. Set `:opts {:strict? true}` to surface
   the underlying `ex-info` instead."
  ([router] (models! router {}))
  ([router opts]
   ;; NOTE: `resolve-opts` defaults `:api-style` to
   ;; `:openai-compatible-chat` when the caller didn't pass one, so we
   ;; can't trust `(:api-style resolved)` to detect "unset". Fall back
   ;; to the selected provider whenever the caller didn't explicitly
   ;; pin `:api-style` / `:provider-id` / `:llm-headers` in opts.
   (let [resolved              (resolve-opts router opts)
         [selected-provider _] (when-not (:base-url resolved)
                                 (router/select-provider router {:strategy :root}))
         api-key               (or (:api-key resolved) (:api-key selected-provider))
         base-url              (or (:base-url resolved) (:base-url selected-provider))
         provider-id           (or (:provider-id opts) (:id selected-provider))
         api-style             (or (:api-style opts)
                                 (:api-style selected-provider)
                                 :openai-compatible-chat)
         llm-headers           (or (:llm-headers opts)
                                 (:llm-headers selected-provider))
         ;; Per-provider hooks live in svar's `KNOWN_PROVIDERS` so the
         ;; whole stack stays platform-agnostic: callers pass a
         ;; provider-id and svar knows which endpoint, query params,
         ;; and response shape to expect (Anthropic `/v1/models`,
         ;; OpenAI `/models`, Codex `/codex/models?client_version=...`,
         ;; Z.ai `/models`, ...). Caller-supplied opts override.
         known-provider        (when provider-id
                                 (get router/KNOWN_PROVIDERS provider-id))
         models-path           (or (:models-path opts)
                                 (:models-path known-provider)
                                 "/models")
         models-query-params   (or (:models-query-params opts)
                                 (:models-query-params known-provider))
         models-base           (or (:models-base opts) (:models-base known-provider))
         models-shape          (or (:models-shape opts) (:models-shape known-provider))
         models-url            (models-endpoint-url base-url models-base models-path)
         strict?               (boolean (:strict? opts))
         http-opts             (cond-> {:api-style   api-style
                                        :provider-id provider-id
                                        :llm-headers llm-headers}
                                 (seq models-query-params)
                                 (assoc :query-params models-query-params))
         body                  (try
                                 (http-get! models-url api-key http-opts)
                                 (catch clojure.lang.ExceptionInfo ex
                                   (when strict? (throw ex))
                                   nil))
         models                (shape-models models-shape (normalize-models-response body))]
     (filter-provider-models provider-id models))))

