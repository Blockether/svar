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
   [taoensso.trove :as trove])
  (:import
   (java.io BufferedReader InputStreamReader)))

;; =============================================================================
;; Correlation context
;; =============================================================================

(def ^:dynamic *log-context*
  "Optional map bound by callers to add context to SVAR logs.
   e.g. {:query-id \"abc\" :iteration 0}
   When bound, all HTTP logs include this context."
  nil)

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
  #{429 502 503 504})

(defn- retryable-exception?
  "Returns true if the exception represents a transient connection/read error
   that should be retried (e.g., proxy dropping connection mid-response).

   These errors have no HTTP status code because the response body was truncated
   or the connection was reset before a complete response was received."
  [^Exception e]
  (let [msg (or (ex-message e) "")
        cause (ex-cause e)
        cause-msg (when cause (or (ex-message cause) ""))]
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
     ;; babashka.http-client wraps errors in ExceptionInfo
      (and (instance? clojure.lang.ExceptionInfo e)
        (some-> cause retryable-exception?)))))

(def ^:private shared-http-executor
  "Virtual-thread-per-task executor that backs the shared HttpClient.
   Held separately from the client so shutdown-http-client! can close it
   explicitly. Without this the JVM keeps non-daemon threads alive, blocking
   clean exit in REPLs, test runners, and scripts."
  (delay (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)))

(def ^:private shared-http-client
  "Single shared HttpClient reused across ALL LLM requests. Without this each
   http/post call constructs a new JDK HttpClient with its own SelectorManager
   thread, which never gets GC'd before the JVM runs out of thread stack. Ran
   into this during the 4clojure benchmark (OOM after ~108 tasks).
   Uses shared-http-executor so blocked HTTP calls cost almost nothing and
   don't pin OS threads.

   HTTP/1.1 PIN — some remote providers and local LLM servers (LM Studio,
   Ollama) choke on HTTP/2 trailers. If you need HTTP/2 for a specific call,
   build a second client — do NOT flip this default."
  (delay
    (http/client (assoc http/default-client-opts
                   :executor @shared-http-executor
                   :version :http1.1))))

(defn shutdown-http-client!
  "Closes the shared HTTP client's virtual-thread executor.
   Idempotent — safe to call multiple times. Call before JVM exit in
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
   error sites include the raw response in ex-data — critical when a
   provider returns HTTP 200 with a shape we don't understand (reasoning
   only, partial JSON, undocumented fields) and vanilla `:message :content`
   extraction loses the evidence."
  [url body headers timeout-ms]
  (let [response (http/post url
                   {:client @shared-http-client
                    :headers headers
                    :body (json/write-json-str body)
                    :timeout timeout-ms})
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
     ;; :openai-compatible-chat and everything else — Bearer token
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

(defn- request-headers [api-style api-key provider-id messages llm-headers]
  (merge (make-llm-headers api-style api-key provider-id)
    (when (= :github-copilot provider-id)
      (copilot-static-headers))
    llm-headers
    (when (= :github-copilot provider-id)
      (copilot-dynamic-headers messages))))

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
;; still kicks in for stable ≥1024-tok prefixes — no client signal
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
  "Drop svar-internal `:svar/*` markers from a content block — used for
   wire formats that don't speak our markers (OpenAI etc)."
  [block]
  (into {} (remove (fn [[k _]] (and (keyword? k) (= "svar" (namespace k))))) block))

(defn- anthropic-block
  "Translate one canonical block → Anthropic wire shape, attaching
   `cache_control` when the block was tagged `:svar/cache true`."
  [block]
  (let [cc    (cache-control-for block)
        clean (strip-svar-keys block)]
    (cond-> clean
      cc (assoc :cache_control cc))))

(defn- anthropic-content
  "Walks canonical blocks → Anthropic wire content. Collapses to a bare
   string when there is exactly ONE plain text block with no cache
   marker, otherwise emits the array form."
  [blocks]
  (let [wire (mapv anthropic-block blocks)]
    (if (and (= 1 (count wire))
          (text-block? (first wire))
          (nil? (:cache_control (first wire))))
      (-> wire first :text)
      wire)))

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

(defn- truncate-error-body
  "Returns a short string suitable for `:body` / `:body-snippet` on
   `:svar.core/http-error` ex-data. Stringifies non-string bodies
   (`InputStream` callers should already have slurped). Returns nil for
   nil / blank input so `cond->` callers can skip the assoc cleanly.

   Truncates to `MAX_HTTP_ERROR_BODY_CHARS` and appends a `...<+N more>`
   suffix so downstream renderers (Vis chat error bubble) can show the
   first ~8 KB of an upstream provider error envelope verbatim — that
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
   call — Anthropic's HMAC `signature`, Anthropic redacted-thinking's
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
   plain text block so Anthropic doesn't reject the request — this
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
    ;; Missing signature — fall back to a text block so Anthropic doesn't
    ;; reject the next request and Claude doesn't start mimicking the
    ;; <thinking> tags in subsequent responses.
    {:type "text"
     :text (or thinking "")}))

(defn- anthropic-message-content
  "Builds the wire content for one message under `:anthropic` api-style.
   Walks each canonical content block: `{:type \"thinking\"}` blocks go
   through `canonical-thinking->anthropic-block` (signature/redacted
   rendered natively), everything else passes through `anthropic-block`
   unchanged. Force the array form whenever a thinking block is present
   — the single-text-collapse path would drop the signed blocks."
  [{:keys [content]}]
  (let [blocks       (normalize-content content)
        has-thinking? (some canonical-thinking-block? blocks)
        wire         (mapv (fn [b]
                             (if (canonical-thinking-block? b)
                               (canonical-thinking->anthropic-block b)
                               (anthropic-block b)))
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
   per-block `cache_control`) otherwise — Anthropic accepts both.

   `:svar/cache true` markers on user/assistant content blocks are
   translated to `cache_control: {type: \"ephemeral\"}` per block.
   Anthropic enforces a hard cap of 4 cache breakpoints per call.

   Assistant messages whose `:content` contains canonical
   `{:type \"thinking\"}` blocks emit them as native Anthropic
   `thinking` / `redacted_thinking` wire blocks (see
   `canonical-thinking->anthropic-block`). Echoing them back is how
   Anthropic's extended thinking is continued across calls — the model
   sees its own prior reasoning (signature-verified server-side) before
   the next user turn instead of re-thinking from scratch.

   `:max_tokens` is always present (Anthropic requirement) and
   `clamp-anthropic-thinking-max-tokens` ensures visible output has
   room above `:thinking.budget_tokens` when extended thinking is on."
  ([messages model extra-body]
   (build-anthropic-request-body messages model extra-body nil))
  ([messages model extra-body {:keys [anthropic-oauth?]}]
   (let [sys-blocks  (cond-> (vec (mapcat #(normalize-content (:content %))
                                    (filter #(= "system" (:role %)) messages)))
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
                       (remove #(= "system" (:role %)))
                       (mapv (fn [{:keys [role] :as msg}]
                               {:role role
                                :content (anthropic-message-content msg)})))
         max-tokens  (or (:max_tokens extra-body) 4096)
         body        (cond-> {:model model :messages non-system :max_tokens max-tokens}
                       system-wire (assoc :system system-wire)
                       (seq extra-body) (merge (dissoc extra-body :stream_options)))]
    ;; Re-assert system after merge so extra-body can't clobber it,
    ;; then apply the thinking-aware max_tokens clamp.
     (-> (cond-> body system-wire (assoc :system system-wire))
       clamp-anthropic-thinking-max-tokens))))

(defn- anthropic-wire->canonical-block
  "Translates one Anthropic wire content block to svar's canonical
   shape. `text` blocks become `{:type \"text\" :text str}`; `thinking`
   blocks become canonical thinking blocks carrying the HMAC signature
   in `:thinking-signature`; `redacted_thinking` blocks become
   canonical thinking blocks with `:redacted? true` and the encrypted
   data carried under `:thinking-signature`. Anything else passes
   through unchanged so future Anthropic block types (tool_use,
   server_tool_use, …) survive a round-trip."
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
   The result is a normal svar message map — callers append it to
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
             :api-usage     (when usage
                              (let [cache-read   (:cache_read_input_tokens usage)
                                    cache-create (:cache_creation_input_tokens usage)]
                                (cond-> {:prompt_tokens     (:input_tokens usage)
                                         :completion_tokens (:output_tokens usage)}
                                  (or cache-read cache-create)
                                  (assoc :prompt_tokens_details
                                    (cond-> {}
                                      cache-read   (assoc :cached_tokens cache-read)
                                      cache-create (assoc :cache_creation_tokens cache-create))))))
             :http-response envelope}
      canonical-msg (assoc :assistant-message canonical-msg))))

(defn- anthropic-stream-usage [u]
  (when u
    (let [cr (:cache_read_input_tokens u)
          cc (:cache_creation_input_tokens u)]
      (cond-> {:prompt_tokens (:input_tokens u) :completion_tokens (:output_tokens u)}
        (or cr cc) (assoc :prompt_tokens_details
                     (cond-> {}
                       cr (assoc :cached_tokens cr)
                       cc (assoc :cache_creation_tokens cc)))))))

(defn- extract-anthropic-stream-delta
  "Stateless one-shot extractor for individual Anthropic SSE events.
   Used by tests + non-aggregating callers; the streaming pipeline
   prefers `make-anthropic-stream-delta-fn` because that closure keeps
   per-block accumulator state (signature, redacted_thinking data) that
   has to round-trip on the next request."
  [chunk]
  (case (:type chunk)
    "content_block_delta"
    (let [delta (:delta chunk)]
      (case (:type delta)
        "text_delta"     {:content-delta (:text delta)     :reasoning-delta nil :api-usage nil}
        "thinking_delta" {:content-delta nil               :reasoning-delta (:thinking delta) :api-usage nil}
        {:content-delta nil :reasoning-delta nil :api-usage nil}))
    "message_delta"
    {:content-delta nil :reasoning-delta nil :api-usage (anthropic-stream-usage (:usage chunk))}
    "message_start"
    {:content-delta nil :reasoning-delta nil :api-usage (anthropic-stream-usage (get-in chunk [:message :usage]))}
    "message_stop"
    {:content-delta nil :reasoning-delta nil :api-usage nil :terminal? true}
    ;; default — ignore other event types
    {:content-delta nil :reasoning-delta nil :api-usage nil}))

(defn- make-anthropic-stream-delta-fn
  "Builds a stateful one-arg delta-fn closure for Anthropic SSE streams.
   Mirrors the public per-event shape returned by
   `extract-anthropic-stream-delta` (so the SSE aggregator stays
   uniform) and additionally accumulates partial wire blocks across
   `content_block_start` … `content_block_delta` … `content_block_stop`.
   Each closed block is converted to svar's canonical form
   (`anthropic-wire->canonical-block`) and flushed to the aggregator
   via `:provider-state {:provider :anthropic :blocks [<canonical>]}`.
   The aggregator concatenates blocks in arrival order — the same order
   Anthropic requires them re-sent in to keep the extended-thinking
   session valid.

   Why a closure: a flat per-event extractor cannot pair a
   `signature_delta` with the `thinking_delta` chunks that preceded it
   under the same block index, and Anthropic's `signature` field is
   required verbatim on replay — dropping it invalidates the next
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
        {:content-delta nil :reasoning-delta nil :api-usage (anthropic-stream-usage (:usage chunk))}

        "message_start"
        {:content-delta nil :reasoning-delta nil :api-usage (anthropic-stream-usage (get-in chunk [:message :usage]))}

        "message_stop"
        {:content-delta nil :reasoning-delta nil :api-usage nil :terminal? true}

        {:content-delta nil :reasoning-delta nil :api-usage nil}))))

(defn- with-retry
  "Executes a function with exponential backoff retry for transient errors.
   
   Retries on HTTP status codes 429, 502, 503, 504.
   
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
  ([f {:keys [max-retries initial-delay-ms max-delay-ms multiplier]
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
                            retryable-status? (contains? RETRYABLE_STATUS_CODES status)
                            retryable-conn? (retryable-exception? e)
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
    (string? part)
    (when-not (str/blank? part) part)

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
  (let [s (->> blocks (keep content-part-text) (str/join "\n"))]
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

(defn- normalize-openai-usage [usage]
  (when usage
    (let [input-details  (:input_tokens_details usage)
          output-details (:output_tokens_details usage)]
      (if (or (contains? usage :input_tokens)
            (contains? usage :output_tokens))
        (let [prompt-details (cond-> {}
                               (:cached_tokens input-details)
                               (assoc :cached_tokens (:cached_tokens input-details))

                               (:cache_creation_tokens input-details)
                               (assoc :cache_creation_tokens (:cache_creation_tokens input-details))

                               (:cache_write_tokens input-details)
                               (assoc :cache_write_tokens (:cache_write_tokens input-details)))]
          (cond-> {:prompt_tokens     (:input_tokens usage)
                   :completion_tokens (:output_tokens usage)
                   :total_tokens      (:total_tokens usage)}
            (seq prompt-details)
            (assoc :prompt_tokens_details prompt-details)

            (:reasoning_tokens output-details)
            (assoc :completion_tokens_details
              {:reasoning_tokens (:reasoning_tokens output-details)})))
        usage))))

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
   so a round-trip can re-emit the exact wire shape — including ids,
   encrypted_content, raw summary parts — byte-for-byte."
  [{:keys [summary-text content-text raw-item] :as item}]
  {:type "thinking"
   :thinking (or summary-text content-text "")
   :thinking-signature (json/write-json-str (or raw-item item))
   :redacted? false})

(defn- canonical-thinking-block->responses-reasoning-item
  "Decodes a canonical thinking block's `:thinking-signature` back into
   the OpenAI Responses wire-shape map the API expects in `:input`.
   Falls back to a synthesized minimal item if the signature is not
   parseable JSON — keeps round-trips robust without leaking thinking
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

   Returns nil when there is no usable assistant content — callers
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
        ;; GLM, OpenRouter, plain OpenAI Chat — use
        ;; `openai-chat-canonical-assistant-message`) and OpenAI
        ;; Responses (rich `:output` array with reasoning items — use
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
        ;; right before its parent message — the OpenAI Responses API
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
   string, not as inline content blocks — so we hoist the canonical
   thinking blocks out of the wire content and emit their text under
   the dedicated field instead."
  [blocks]
  [(filterv canonical-thinking-block? blocks)
   (filterv (complement canonical-thinking-block?) blocks)])

(defn- openai-chat-reasoning-content
  "Concatenates the `:thinking-signature` of every canonical thinking
   block on the message, in order, into the single string that z.ai /
   OpenRouter expect under `reasoning_content`. We use the signature
   (not the visible `:thinking`) because z.ai's preservation contract
   is exact-text echo — svar populates both fields with the same
   provider-issued text on capture."
  [thinking-blocks]
  (let [parts (->> thinking-blocks
                (map (fn [{:keys [thinking-signature thinking]}]
                       (or thinking-signature thinking)))
                (remove str/blank?))]
    (when (seq parts)
      (str/join "\n" parts))))

(defn- build-request-body
  "Builds the request body for an OpenAI-compatible chat completion API.

   Multi-block content (`{:type \"text\" :text ...}` arrays, image blocks)
   passes through unchanged. svar-internal markers like `:svar/cache`
   are stripped — OpenAI implicit caching benefits from a stable prefix
   without any client signal.

   Canonical `{:type \"thinking\"}` blocks on assistant messages are
   hoisted out of `:content` and re-emitted under the dedicated
   `:reasoning_content` field so providers that speak the
   preserved-thinking convention (z.ai GLM, OpenRouter) keep the
   model's thinking session active across calls.

   Params:
   `messages` - Vector. Chat messages.
   `model` - String. Model name.
   `extra-body` - Map, optional. Additional params to merge into the request body
                  (e.g. {:reasoning_effort \"medium\"} for reasoning-capable providers)."
  ([messages model]
   (build-request-body messages model nil))
  ([messages model extra-body]
   (let [processed (mapv (fn [{:keys [role content] :as m}]
                           (let [normalized (normalize-content content)
                                 [thinking-blocks rest-blocks]
                                 (if (= role "assistant")
                                   (openai-chat-split-thinking normalized)
                                   [nil normalized])
                                 reasoning-content
                                 (when (seq thinking-blocks)
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

(def ^:private stream-finalization-error-types
  #{:svar.core/stream-incomplete
    :svar.core/stream-truncated})

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
  [request-body {:keys [api-key base-url responses-path headers timeout-ms on-chunk]
                 :or   {responses-path "/responses"
                        timeout-ms router/DEFAULT_TIMEOUT_MS}}]
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
                                  :stream? (boolean stream?)})
                 :msg "responses request dispatched"})
    (try
      (if stream?
        (http-post-stream! url request-body http-headers timeout-ms extract-stream-delta
          (when on-chunk
            (fn [{:keys [content-acc reasoning-acc provider-state api-usage]}]
              (on-chunk {:content content-acc :reasoning (nonblank-str reasoning-acc)
                         :provider-state provider-state
                         :api-usage api-usage :done? false}))))
        (extract-response-data (http-post! url request-body http-headers timeout-ms)))
      (catch Exception e
        (if (stream-finalization-error? e)
          (throw e)
          (let [ex-data-map   (ex-data e)
                response-body (when (string? (:body ex-data-map)) (:body ex-data-map))
                api-key-error (detect-api-key-error response-body)
                error-message (if api-key-error
                                (str api-key-error " (Original: " (ex-message e) ")")
                                (ex-message e))]
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
              error-message (if api-key-error
                              (str api-key-error " (Original: " (ex-message e) ")")
                              (ex-message e))]
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
  "Parses a single SSE data line. Returns parsed JSON map, `sse-done`, or nil."
  [^String data-str]
  (let [trimmed (str/trim data-str)]
    (cond
      (= trimmed "[DONE]") sse-done
      (str/blank? trimmed) nil
      :else
      (try
        (json/read-json trimmed :key-fn keyword)
        (catch Exception _ nil)))))

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

(defn- http-post-stream!
  "Makes a streaming HTTP POST request. Reads SSE events and fires on-delta
   for each. `headers` - HTTP headers map. `delta-fn` - extracts delta
   from parsed SSE chunk.

   Returns an envelope:
     {:content :reasoning :api-usage
      :http-response {:url :streaming? :status}}
   Mirrors the shape of non-streaming `extract-response-data` so
   downstream callers destructure uniformly. There is no `:raw-body` on
   streaming paths — the SSE chunks are consumed incrementally, so the
   accumulated `:content` / `:reasoning` are the closest analogues."
  [url body headers timeout-ms delta-fn on-delta]
  (let [response (try
                   (http/post url
                     {:headers headers
                      :body (json/write-json-str body)
                      :timeout timeout-ms
                      :as :stream})
                   (catch clojure.lang.ExceptionInfo e
                     ;; Convert InputStream body to string for error handling
                     (let [ed (ex-data e)
                           body-str (when (instance? java.io.InputStream (:body ed))
                                      (slurp-input-stream (:body ed)))]
                       (throw (ex-info (ex-message e)
                                (cond-> (dissoc ed :body)
                                  body-str (assoc :body body-str))
                                (ex-cause e))))))
        input-stream (:body response)
        content-acc (StringBuilder.)
        reasoning-acc (StringBuilder.)
        usage-atom (atom nil)
        provider-state-atom (atom nil)
        current-reasoning-item (atom nil)
        terminal-seen? (atom false)
        incomplete-response (atom nil)]
    (try
      (with-open [reader (BufferedReader. (InputStreamReader. ^java.io.InputStream input-stream "UTF-8"))]
        (loop []
          (let [line (.readLine reader)]
            (when (some? line)
              (when (str/starts-with? line "data: ")
                (let [data-str (subs line 6)
                      parsed (parse-sse-data data-str)]
                  (cond
                    (= sse-done parsed)
                    (reset! terminal-seen? true)

                    parsed
                    (let [parsed (enrich-responses-reasoning-event current-reasoning-item parsed)
                          {:keys [content-delta reasoning-delta content-fallback reasoning-fallback
                                  provider-state api-usage terminal? incomplete? incomplete-reason]}
                          (delta-fn parsed)
                          content-piece   (or content-delta
                                            (when (zero? (.length content-acc))
                                              (some-> content-fallback content-part-text)))
                          reasoning-piece (or reasoning-delta
                                            (when (zero? (.length reasoning-acc))
                                              (some-> reasoning-fallback reasoning-part-text)))]
                      (when terminal? (reset! terminal-seen? true))
                      (when incomplete?
                        (reset! incomplete-response {:reason incomplete-reason :chunk parsed}))
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
                                   :api-usage api-usage}))))))
              (recur))))
        (when-let [incomplete @incomplete-response]
          (throw (ex-info "Stream ended with incomplete response."
                   {:type :svar.core/stream-incomplete
                    :stream? true
                    :url url
                    :reason (:reason incomplete)
                    :content-acc-len (.length content-acc)
                    :reasoning-acc-len (.length reasoning-acc)
                    :partial-content (when (pos? (.length content-acc)) (str content-acc))})))
        (when-not @terminal-seen?
          (throw (ex-info "Stream ended before terminal marker."
                   {:type :svar.core/stream-truncated
                    :stream? true
                    :url url
                    :content-acc-len (.length content-acc)
                    :reasoning-acc-len (.length reasoning-acc)
                    :partial-content (when (pos? (.length content-acc)) (str content-acc))})))
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
                                   :reasoning-content final-reasoning}))]
          (cond-> {:content        final-content
                   :reasoning      final-reasoning
                   :provider-state ps
                   :api-usage      @usage-atom
                   :http-response  {:url        url
                                    :streaming? true
                                    :status     (:status response)}}
            assistant-msg (assoc :assistant-message assistant-msg))))
      (catch clojure.lang.ExceptionInfo e
        (if (stream-finalization-error? e)
          (throw e)
          (throw (ex-info (str "Stream connection error: " (ex-message e))
                   {:type :svar.core/http-error :stream? true :url url
                    :cause-class (.getName (class e))
                    :content-acc-len (.length content-acc)
                    :reasoning-acc-len (.length reasoning-acc)
                    :partial-content (when (pos? (.length content-acc)) (str content-acc))}
                   e))))
      (catch Exception e
        (throw (ex-info (str "Stream connection error: " (ex-message e))
                 {:type :svar.core/http-error :stream? true :url url
                  :cause-class (.getName (class e))
                  :content-acc-len (.length content-acc)
                  :reasoning-acc-len (.length reasoning-acc)
                  :partial-content (when (pos? (.length content-acc)) (str content-acc))}
                 e))))))

(defn- chat-completion-streaming
  "Streaming variant of chat-completion. Sends stream:true, reads SSE events,
   fires on-chunk with accumulated text. Returns same shape as non-streaming."
  [messages model api-key base-url retry-opts timeout-ms extra-body on-chunk api-style]
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
      (http-post-stream! chat-url request-body headers timeout-ms delta-fn
        (fn [{:keys [content-acc reasoning-acc provider-state api-usage]}]
          (on-chunk {:content content-acc :reasoning (nonblank-str reasoning-acc)
                     :provider-state provider-state
                     :api-usage api-usage :done? false})))
      (catch Exception e
        (if (stream-finalization-error? e)
          (throw e)
          (let [ex-data-map (ex-data e)
                response-body (let [b (:body ex-data-map)]
                                (when (string? b) b))
                api-key-error (detect-api-key-error response-body)
                error-message (if api-key-error
                                (str api-key-error " (Original: " (ex-message e) ")")
                                (ex-message e))]
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
     - :timeout-ms - Integer. Request timeout (default: router/DEFAULT_TIMEOUT_MS).
     - :extra-body - Map. Additional params for the API request body.
     - :on-chunk - Function. When provided, enables SSE streaming. Callback receives
                   accumulated text string after each chunk.
     - :max-retries, :initial-delay-ms, :max-delay-ms, :multiplier — retry config.

   Returns:
   Map with :content, :reasoning (may be nil), :api-usage."
  ([messages model api-key base-url]
   (chat-completion messages model api-key base-url {}))
  ([messages model api-key base-url opts]
   (let [timeout-ms     (get opts :timeout-ms router/DEFAULT_TIMEOUT_MS)
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
                           llm-headers
                           (when (= :github-copilot provider-id)
                             (copilot-dynamic-headers messages)))]
     (if (= api-style :openai-compatible-responses)
       (openai-responses-completion
         (build-openai-responses-request-body messages model extra-body)
         {:api-key        api-key
          :base-url       base-url
          :responses-path (or responses-path "/responses")
          :headers        headers
          :timeout-ms     timeout-ms
          :on-chunk       on-chunk})
       (if on-chunk
         (chat-completion-streaming
           messages model api-key base-url opts timeout-ms extra-body on-chunk api-style)
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
   is stripped from the wire — OpenAI's implicit caching reads the same
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

(def ^:private LLM_PASSTHROUGH_KEYS
  "Keys that any helper invoking `ask!*` (or `eval!*` / `refine!*`) must
   forward from caller opts to the inner LLM call. Centralised here so
   adding a new svar-level option doesn't require chasing every internal
   `(select-keys ... [...])` site. Includes:
     - routing-resolved fields (`:model`, `:api-key`, `:base-url`,
       `:api-style`, `:provider-id`)
     - LLM tuning (`:extra-body`, `:timeout-ms`, `:check-context?`,
       `:output-reserve`, `:cache-system?`)
     - format-noise hardening (`:format-retries`, `:format-retry-on`,
       `:json-object-mode?`, `:on-format-error`)
   Used by `abstract!*`, `eval!*`, `refine!*`, `sample!*`, and the CoVe
   helpers. Without this, calling e.g. `(svar/abstract! router
   {:format-retries 2 ...})` would silently drop `:format-retries` on the
   way to the inner `ask!*` call."
  [:model :api-key :base-url :api-style :provider-id
   :extra-body :provider-state :timeout-ms :check-context? :output-reserve :cache-system?
   :format-retries :format-retry-on :json-object-mode? :on-format-error
   :responses-path :llm-headers :verbosity])

(defn- llm-passthrough
  "Returns the subset of `opts` that should be forwarded to a nested
   `ask!*` / `eval!*` / `refine!*` call. Use everywhere a helper builds
   inner LLM opts so format-retry / json-mode / envelope behaviour is
   uniform across the public surface."
  [opts]
  (select-keys opts LLM_PASSTHROUGH_KEYS))

(defn- resolve-opts
  "Extracts effective values from router + opts.
   Router provides network/tokens defaults. Opts provides per-call overrides.
   If :provider-id is present, uses provider-scoped pricing/context overlays.

   Returns a map carrying both the resolved network/pricing/context values
   AND the LLM_PASSTHROUGH_KEYS verbatim, so internal helpers can
   `(merge (llm-passthrough resolved-opts) ...)` and have the new opts
   propagate without enumerating them at every call site."
  [router {:keys [model timeout-ms check-context? output-reserve api-key
                  base-url provider-id api-style extra-body provider-state cache-system?
                  format-retries format-retry-on on-format-error
                  responses-path llm-headers verbosity]
           :as opts}]
  (let [{:keys [network tokens]} router
        default-pricing (or (:pricing tokens) router/MODEL_PRICING)
        default-context-limits (or (:context-limits tokens) router/MODEL_CONTEXT_LIMITS)
        pricing (if provider-id
                  (assoc default-pricing model (router/provider-model-pricing provider-id model))
                  default-pricing)
        context-limits (if provider-id
                         (assoc default-context-limits model (router/provider-model-context provider-id model))
                         default-context-limits)]
    (cond-> {:model model
             :timeout-ms (or timeout-ms (:timeout-ms network) router/DEFAULT_TIMEOUT_MS)
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
;; Router delegation — all routing logic lives in router.clj
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
   Returns the augmented `:routing` map. Called by every routed entrypoint
   before `resolve-routing`."
  [opts]
  (cond-> (or (:routing opts) {})
    (:reasoning opts)        (assoc :reasoning (:reasoning opts))
    (:on-format-error opts)  (assoc :on-format-error (:on-format-error opts))
    (:format-retry-on opts)  (assoc :format-retry-on (:format-retry-on opts))))

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
     `:openai-compatible-chat` api-style — hardens prose-leaking models (GLM
     family historically leaks prose into
     `content` under `:deep` reasoning)."
  [opts provider model-map]
  (let [ctx (long (or (:context model-map) 8192))
        auto-params {:max_tokens (long (* 0.25 ctx))}
        api-style (or (:api-style model-map) (:api-style provider))
        reasoning-params (router/reasoning-extra-body
                           api-style model-map (:reasoning opts)
                           {:preserved-thinking? (:preserved-thinking? opts)})
        merged-body (cond-> (merge (:extra-body provider) (:extra-body model-map) auto-params reasoning-params (:extra-body opts))
                      (:verbosity opts) (assoc :verbosity (:verbosity opts)))
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
        :json-object-mode? json-object-mode?
        :extra-body merged-body)
      (cond-> (some? (:responses-path provider))
        (assoc :responses-path (:responses-path provider)))
      (cond-> (some? (:llm-headers provider))
        (assoc :llm-headers (:llm-headers provider))))))

(defn routed-chat-completion
  "Routes a chat-completion across providers with fallback.
   opts may include :routing, :on-chunk, :reasoning, :extra-body."
  [router messages opts]
  (let [resolved (router/resolve-routing router (routing-opts-with-reasoning opts))]
    (router/with-provider-fallback router (:prefs resolved)
      (fn [provider model-map]
        (let [{:keys [extra-body api-style responses-path llm-headers]}
              (inject-routed-params opts provider model-map)]
          (chat-completion messages (:name model-map)
            (:api-key provider)
            (:base-url provider)
            (cond-> {:extra-body extra-body :api-style api-style}
              (:id provider)     (assoc :provider-id (:id provider))
              (:on-chunk opts)   (assoc :on-chunk (:on-chunk opts))
              responses-path     (assoc :responses-path responses-path)
              llm-headers        (assoc :llm-headers llm-headers))))))))

;; =============================================================================
;; ask!* - Low-level structured output (primitive, no routing)
;; =============================================================================

;; Forward declaration — ask!* is defined next, ask! (routed) wraps it below
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
       server-side — setting this flag is harmless there.
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
         :fail               (default) — throw with the full forensic envelope.
         :fallback-provider  — treat the failure as transient and try the
            next provider/model in the fleet, excluding the offender. The
            final exception (when all providers fail) is the LAST format
            error seen, with `:routed/fallback-trace` and `:format-failed`
            merged into ex-data. Pairs well with `:format-retries N` per
            provider — retries first absorb prose-leaks locally, fallback
            kicks in only when the whole model is broken for this spec.
     :format-retries / :format-retry-on / :json-object-mode? - See `ask!*`.
       These reach the LLM call via the routed-params pipeline."
  [router opts]
  (let [resolved (router/resolve-routing router (routing-opts-with-reasoning opts))]
    (router/with-provider-fallback
      router (:prefs resolved)
      (fn [provider model-map]
        (ask!* router (inject-routed-params opts provider model-map))))))
;; =============================================================================
;; ask!* - Main structured output function (primitive)
;; =============================================================================

(defn- apply-spec-humanizer
  "Applies a humanizer function to spec fields marked with ::humanize? true."
  [result spec-def humanizer-fn]
  (let [fields (::spec/fields spec-def)
        humanizable-fields (filter ::spec/humanize? fields)]
    (reduce
      (fn [acc field-def]
        (let [field-name (::spec/name field-def)
             ;; Get the simple key (strip namespace for lookup)
              simple-key (keyword (name field-name))
              cardinality (::spec/cardinality field-def)
              current-val (get acc simple-key)]
          (cond
           ;; Single string value
            (and (= cardinality :spec.cardinality/one)
              (string? current-val))
            (assoc acc simple-key (humanizer-fn current-val))

           ;; Many - vector of strings
            (and (= cardinality :spec.cardinality/many)
              (vector? current-val))
            (assoc acc simple-key
              (mapv (fn [v] (if (string? v) (humanizer-fn v) v))
                current-val))

           ;; Not a string or nil - leave unchanged
            :else acc)))
      result
      humanizable-fields)))

;; =============================================================================
;; Format-retry support — in-process retry on schema-rejected provider output
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
;; tokens — the provider already produced them) but the caller sees one
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
;; Schema tail pointer — recency anchor past the cache breakpoint
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
;; block of the LAST user message. It does NOT repeat the schema body —
;; that stays cached at the head — it just points back to it. Sits PAST
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
   message. Does not repeat the schema body — points back to the cached
   schema in the system message. Sits past the cache breakpoint, so it is
   billed but never cached, never burns a breakpoint slot."
  (str "Reply with one JSON object matching the schema in the system message.\n"
    "First non-whitespace character MUST be `{`. "
    "No prose. No markdown commentary. No explanation."))

(defn- append-schema-tail-pointer
  "Appends `SCHEMA_TAIL_POINTER` as a trailing text block on the LAST user
   message. If no user message exists in `messages`, appends a new user
   message carrying just the pointer (degenerate input — schema-only ask).

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
  "Short code-format reminder appended as the last text block of the last
   user message in `ask-code!*`. Mirrors `SCHEMA_TAIL_POINTER` for the
   plain-text-with-fenced-code path: nudges the model back to the format
   contract right before generation, restoring recency-driven adherence on
   long transcripts. Parameterised by `lang` so the reminder names the
   tag the caller asked `ask-code!` to extract."
  [lang]
  (str "Reply with exactly one " lang " source block in Markdown fences. "
    "Opening fence line must contain only ```" lang ". Closing fence line must contain only ```. "
    "All executable source goes between those two fence lines. "
    "Fence lines must be on their own lines: no code on fence line, "
    "no glued fences like ``````" lang " or ``` ```" lang ". "
    "No prose outside fences. No commentary, no explanation."))

(defn- append-code-tail-pointer
  "`append-schema-tail-pointer` for the `ask-code!*` path. Appends the
   `code-tail-pointer-text` as a trailing text block on the LAST user
   message; synthesises a user message carrying just the pointer when
   `messages` has none. Multimodal content is preserved."
  [messages lang]
  (let [v (vec messages)
        pointer (code-tail-pointer-text lang)
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
   short — long retry messages waste tokens on every attempt and dilute the
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
   Empirically outperforms a bare retry instruction — the model self-diagnoses
   what it produced."
  [messages prev-content attempt total reason received-type]
  (conj (vec messages)
    {:role "assistant" :content [{:type "text" :text (or prev-content "")}]}
    {:role "user"      :content [{:type "text"
                                  :text (format-retry-prompt
                                          attempt total reason received-type)}]}))

(defn- envelope-data
  "Forensic envelope merged into every error ex-data thrown from `ask!*`. No
   truncation — callers (Vis triage, post-mortem tooling) need the full raw
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
  [api-usage]
  (when api-usage
    (let [cached (get-in api-usage [:prompt_tokens_details :cached_tokens])
          cache-created (or (get-in api-usage [:prompt_tokens_details :cache_creation_tokens])
                          (get-in api-usage [:prompt_tokens_details :cache_write_tokens]))]
      (cond-> {:input     (:prompt_tokens api-usage)
               :output    (:completion_tokens api-usage)
               :reasoning (get-in api-usage [:completion_tokens_details :reasoning_tokens])
               :total     (:total_tokens api-usage)}
        (some? cached) (assoc :cached cached)
        (some? cache-created) (assoc :cache-created cache-created)))))

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
  "Low-level ask — calls the LLM directly without routing. Use ask! instead.

   Includes automatic pre-flight context limit checking. If your input exceeds
   the model's context window, throws a clear error with actionable suggestions
   BEFORE making the API call.

   Supports multimodal input via the `user` + `image` helpers.

   Params:
   `opts` - Map with keys:
     - :spec - Spec definition, required.
     - :messages - Vector of message maps, required.
     - :model - String, required. LLM model to use.
     - :humanizer - Function, optional. Applied to ::spec/humanize? fields.
     - :output-reserve - Integer, optional.
     - :check-context? - Boolean, optional.
     - :timeout-ms - Integer, optional.
     - :format-retries - Integer, optional. Default 0. Number of in-process
       retries when the provider returns content that fails schema parsing
       (e.g. bare prose, wrong top-level type, missing required fields).
       Each retry is a separate HTTP call — tokens are still billed by the
       provider — but the caller sees one logical `ask!` call rather than
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
       NOT repeat the schema body — the body stays cached at the head.
       Restores recency-driven schema adherence after head-inlining the
       schema for cache friendliness. Pass `false` for head-only mode
       (rare — mostly for quirky local models that double-emit on
       reminders, or for benchmarking the placement effect).

   Returns:
   Map with :result, :tokens, :cost, :duration-ms. When format retries
   occurred, includes :format-attempts — a vector of per-attempt records
   carrying the full content, reasoning, api-usage, and rejection reason for
   each failed attempt before the final success.

   Throws:
   ex-info with full forensic envelope merged into ex-data: :model,
   :api-style, :chat-url, :duration-ms, :api-usage, :reasoning, :content,
   :http-response, plus :format-attempts when retries were exhausted. No
   truncation — ex-data is the canonical post-mortem record."
  [router {:keys [spec messages humanizer cache-system? schema-tail-pointer?] :as opts}]
  (let [{:keys [model api-key base-url api-style timeout-ms check-context? output-reserve network pricing context-limits responses-path llm-headers]} (resolve-opts router opts)
        provider-id (:provider-id opts)
        chat-url (make-chat-url base-url api-style)
        schema-prompt (spec/spec->prompt spec)
        ;; Schema placement: full schema body is inlined into the SYSTEM
        ;; message (head, cache-friendly) AND a tiny tail pointer is
        ;; appended to the LAST user message (recency-friendly). The
        ;; pointer does not repeat the schema body — it just points back
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
        in-msgs   (vec messages)
        sys-idx   (->> in-msgs
                    (map-indexed vector)
                    (some (fn [[i m]] (when (= "system" (:role m)) i))))
        with-spec (if sys-idx
                    (update in-msgs sys-idx
                      (fn [{:keys [content] :as m}]
                        (assoc m :content
                          (conj (normalize-content content)
                            {:type "text" :text schema-prompt}))))
                    (into [{:role "system"
                            :content [{:type "text" :text schema-prompt}]}]
                      in-msgs))
        ;; `:cache-system? true` flips the `:svar/cache` flag on the LAST
        ;; block of the system message (now schema-prompt). On Anthropic
        ;; the whole system message becomes one cache breakpoint covering
        ;; caller-system + schema. On other styles the marker is stripped.
        with-cache (if cache-system?
                     (let [si (->> with-spec
                                (map-indexed vector)
                                (some (fn [[i m]] (when (= "system" (:role m)) i))))]
                       (update with-spec si
                         (fn [{:keys [content] :as m}]
                           (let [blocks (normalize-content content)
                                 last-i (dec (count blocks))]
                             (assoc m :content
                               (update blocks last-i assoc :svar/cache true))))))
                     with-spec)
        ;; Tail pointer ON by default — only skipped when caller passes
        ;; an explicit `false`. `nil`/missing → ON.
        with-tail (if (false? schema-tail-pointer?)
                    with-cache
                    (append-schema-tail-pointer with-cache))
        base-messages with-tail
          ;; Pre-flight context check (also counts input tokens for reuse)
        check-opts (cond-> {:context-limits context-limits}
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
          ;; API call — streaming if :on-chunk provided
        on-chunk (:on-chunk opts)
        streaming-on-chunk (when on-chunk
                             (fn [{:keys [content reasoning provider-state api-usage]}]
                               (let [tokens (api-usage->tokens api-usage)
                                     cost (when api-usage
                                            (router/estimate-cost model
                                              (or (:prompt_tokens api-usage) 0)
                                              (or (:completion_tokens api-usage) 0)
                                              pricing
                                              {:api-usage api-usage
                                               :api-style api-style
                                               :cache-tokens-in-input? (not= api-style :anthropic)}))
                                     partial-map (jsonish/parse-partial content)
                                     coerced (when partial-map
                                               (try (spec/str->data-with-spec
                                                      (json/write-json-str partial-map) spec)
                                                    (catch Exception _ partial-map)))]
                                 ;; Fire callback when reasoning OR content is available.
                                 ;; Reasoning streams before content — don't gate on content.
                                 (when (or coerced (some? reasoning))
                                   (on-chunk {:result coerced
                                              :reasoning reasoning
                                              :provider-state provider-state
                                              :tokens tokens
                                              :cost (when cost (select-keys cost [:input-cost :output-cost :total-cost]))
                                              :done? false})))))
        ;; `:json-object-mode?` auto-injection — caller's `:extra-body
        ;; :response_format` always wins. OpenAI chat-completions and
        ;; OpenAI Responses both support JSON mode; Anthropic ignores it.
        ;; `:json-object-mode?` auto-injection — caller's `:extra-body
        ;; :response_format` always wins. OpenAI chat-completions and
        ;; OpenAI Responses both support JSON mode; Anthropic ignores it.
        caller-extra-body (or (:extra-body opts) {})
        extra-body (cond-> caller-extra-body
                     (and (contains? #{:openai-compatible-chat :openai-compatible-responses} api-style)
                       (:json-object-mode? opts)
                       (not (contains? caller-extra-body :response_format)))
                     (assoc :response_format {:type "json_object"}))
        retry-opts (cond-> (merge network {:timeout-ms timeout-ms :api-style api-style})
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
                                          :input-tokens (:prompt_tokens api-usage)
                                          :output-tokens (:completion_tokens api-usage)
                                          ;; nil distinguishes "no field in response" from
                                          ;; "field present but empty" — crucial for triaging
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
            ;; field — usually because the output budget was consumed by reasoning
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
      ;; `:svar.spec/required-field-missing` from the spec layer — spec has
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
          ;; SUCCESS — parse worked; humanize, fire done callback, return.
          (:ok? outcome)
          (let [{:keys [result token-stats]} outcome
                final-result (if humanizer
                               (apply-spec-humanizer result spec humanizer)
                               result)
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

          ;; RETRYABLE FAILURE with budget remaining — append format-retry turn
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

          ;; TERMINAL FAILURE — either non-retryable type or retries exhausted.
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
;; Empty `:result` (no matching code blocks) is a VALID success — the caller
;; decides what to do (semantic retry with reminder, treat as no-op, etc.).
;; svar throws only on transport-level failures: HTTP errors propagate from
;; `chat-completion`; `:svar.llm/empty-content` is thrown when the provider
;; returns no content at all (reasoning-only response). No format-retry loop
;; here — extraction shape is the caller's contract, not svar's.
;;
;; Tail-pointer placement (`:code-tail-pointer?`) mirrors the schema-tail
;; pointer on `ask!*`: a short code-format reminder is appended as the last
;; text block of the last user message, default ON, opt-out via
;; `:code-tail-pointer? false`. Restores recency-driven format adherence on
;; long transcripts without burning a cache breakpoint.

(defn ask-code!*
  "Low-level ask-code — calls the LLM directly without routing. Use `ask-code!`.

   See `ask-code!` for the full param + return contract."
  [router {:keys [messages on-chunk code-tail-pointer?] :as opts}]
  (let [lang (or (:lang opts) "clojure")
        _ (when-not (and (string? lang) (not (str/blank? lang)))
            (anomaly/incorrect! ":lang must be a non-blank string"
              {:type :svar.core/invalid-lang :lang lang}))
        {:keys [model api-key base-url api-style timeout-ms output-reserve
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
                    (append-code-tail-pointer in-msgs lang))
        check-opts (cond-> {:context-limits context-limits}
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
        streaming-on-chunk (when on-chunk
                             (fn [{:keys [content reasoning provider-state api-usage]}]
                               (let [tokens (api-usage->tokens api-usage)
                                     cost (when api-usage
                                            (router/estimate-cost model
                                              (or (:prompt_tokens api-usage) 0)
                                              (or (:completion_tokens api-usage) 0)
                                              pricing
                                              {:api-usage api-usage
                                               :api-style api-style
                                               :cache-tokens-in-input? (not= api-style :anthropic)}))
                                     blocks   (codes/extract-code-blocks content)
                                     selected (->> (codes/select-blocks blocks lang)
                                                (remove #(str/blank? (:source %)))
                                                vec)
                                     partial-result (codes/concat-sources selected)]
                                 (when (or (not (str/blank? partial-result))
                                         (not (str/blank? (or reasoning ""))))
                                   (on-chunk {:result    partial-result
                                              :blocks    selected
                                              :raw       content
                                              :reasoning reasoning
                                              :provider-state provider-state
                                              :tokens    tokens
                                              :cost      (when cost (select-keys cost [:input-cost :output-cost :total-cost]))
                                              :done?     false})))))
        caller-extra-body (or (:extra-body opts) {})
        retry-opts (cond-> (merge network {:timeout-ms timeout-ms :api-style api-style})
                     provider-id (assoc :provider-id provider-id)
                     streaming-on-chunk (assoc :on-chunk streaming-on-chunk)
                     (seq caller-extra-body) (assoc :extra-body caller-extra-body)
                     responses-path (assoc :responses-path responses-path)
                     llm-headers (assoc :llm-headers llm-headers))
        [{:keys [content reasoning provider-state assistant-message api-usage http-response]} duration-ms]
        (util/with-elapsed
          (chat-completion with-tail model api-key chat-url retry-opts))]
    (trove/log! {:level :info
                 :data (log-data {:model model
                                  :duration-ms duration-ms
                                  :input-tokens (:prompt_tokens api-usage)
                                  :output-tokens (:completion_tokens api-usage)
                                  :reasoning-length (when reasoning (count reasoning))
                                  :content-length   (when content (count content))
                                  :content-preview  (when content
                                                      (subs content 0 (min 200 (count content))))})
                 :msg "ask-code! HTTP response received"})
    (when (str/blank? content)
      (throw (ex-info
               (str "The model produced reasoning but no content. "
                 "`ask-code!` needs textual content with code fences.")
               (assoc (envelope-data
                        {:model model :api-style api-style :chat-url chat-url
                         :duration-ms duration-ms :api-usage api-usage
                         :reasoning reasoning :content content
                         :provider-state provider-state
                         :assistant-message assistant-message
                         :http-response http-response :provider-id provider-id})
                 :type :svar.llm/empty-content))))
    (let [blocks      (codes/extract-code-blocks content)
          selected    (->> (codes/select-blocks blocks lang)
                        (remove #(str/blank? (:source %)))
                        vec)
          result      (codes/concat-sources selected)
          token-stats (router/count-and-estimate model with-tail content
                        (cond-> {:pricing pricing
                                 :api-usage api-usage
                                 :api-style api-style}
                          context-check (assoc :input-tokens (:input-tokens context-check))))
          tokens      (token-stats->tokens token-stats)
          cost        (select-keys (:cost token-stats) [:input-cost :output-cost :total-cost])]
      (when on-chunk
        (on-chunk {:result    result
                   :blocks    selected
                   :raw       content
                   :reasoning reasoning
                   :provider-state provider-state
                   :tokens    tokens
                   :cost      cost
                   :done?     true}))
      (cond-> {:result      result
               :blocks      selected
               :raw         content
               :tokens      tokens
               :cost        cost
               :duration-ms duration-ms}
        reasoning         (assoc :reasoning reasoning)
        provider-state    (assoc :provider-state provider-state)
        assistant-message (assoc :assistant-message assistant-message)))))

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
             inside ```lang … ``` fences\"). Restores recency-driven format
             adherence on long transcripts. Set to `false` to opt out.

   Returns:
   {:result      <concatenated source string of selected blocks>
    :blocks      [{:lang <str-or-nil> :source <str>} …]
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
    :duration-ms <ms>}

   Empty `:result` is a valid success.

   Throws ex-info on transport-level failure (`:svar.llm/empty-content` when
   the provider returns no content; HTTP errors from `chat-completion`)."
  [router opts]
  (let [resolved (router/resolve-routing router (routing-opts-with-reasoning opts))]
    (router/with-provider-fallback
      router (:prefs resolved)
      (fn [provider model-map]
        (ask-code!* router (inject-routed-params opts provider model-map))))))

;; =============================================================================
;; abstract! - Chain of Density summarization
;; =============================================================================

;; Forward declare primitive helpers used by abstract!*
(declare abstract!* refine!* eval!*)

;; =============================================================================
;; abstract! - Routed summarization (ALL calls go through the router)
;; =============================================================================

(defn abstract!
  "Routed abstract! — provider fallback + rate limiting.
   Accepts `:reasoning :quick|:balanced|:deep` (translated per api-style)."
  [router opts]
  (let [resolved (router/resolve-routing router (routing-opts-with-reasoning opts))]
    (router/with-provider-fallback
      router (:prefs resolved)
      (fn [provider model-map]
        (abstract!* router (inject-routed-params opts provider model-map))))))

(def ^:private DEFAULT_ITERATIONS
  "Default number of Chain of Density iterations."
  5)

(def ^:private DEFAULT_TARGET_LENGTH
  "Default target length in words for Chain of Density summaries."
  80)

(def ^:private DEFAULT_COD_FORMAT_RETRIES
  "Default schema-format retries for Chain of Density iteration calls."
  2)

(def ^:private COD_ENTITY_CRITERIA
  "Shared XML definition of entity selection criteria.
   Faithful to Adams et al., 2023 — entity types are intentionally open-ended."
  "<entity_criteria>
    <criterion name=\"atomic\">Each entity must be a single named thing — a person, place, date, organization, concept, or term. NOT a phrase, clause, or description.</criterion>
    <criterion name=\"relevant\">Related to the main story or central theme</criterion>
    <criterion name=\"specific\">A proper noun, date, or precise term from the text — not a generic description or paraphrase</criterion>
    <criterion name=\"faithful\">Must appear verbatim or near-verbatim in the source text — no invented modifiers or rewordings</criterion>
</entity_criteria>")

(defn- build-cod-first-iteration-objective
  "Builds the Chain of Density objective for the first iteration (no previous summary)."
  [target-length special-instructions]
  (str "<chain_of_density_iteration>
    <task>Create the first summary of the provided text, covering only the most prominent entities.</task>
    
    <instructions>
        <instruction>Identify key salient entities from the text to include</instruction>
        <instruction>Write a factual summary (4-5 sentences, ~" target-length " words) that covers the broad topic</instruction>
        <instruction>Use ONLY words and facts that appear in the source text — do not add adjectives, modifiers, or characterizations not present in the original</instruction>
        <instruction>Do NOT describe what the text is (e.g. \"this article discusses\") — summarize WHAT IT SAYS</instruction>
        <instruction>Do NOT add evaluative language (e.g. \"revolutionary\", \"groundbreaking\", \"important\") unless the source text uses those exact words</instruction>
        <instruction>This is the sparse starting point — later iterations will add more entities and compress further</instruction>
    </instructions>
    
    " COD_ENTITY_CRITERIA "
    "
    (when special-instructions
      (str "
    <special_instructions>
        " special-instructions "
    </special_instructions>
    "))
    "
    <output_requirements>
        <field name=\"entities\">Array of objects, each with 'entity' (atomic name), 'rationale' (why it's salient), and 'score' (0.0-1.0 salience)</field>
        <field name=\"summary\">The initial factual summary (~" target-length " words)</field>
    </output_requirements>
</chain_of_density_iteration>"))

(defn- build-cod-subsequent-iteration-objective
  "Builds the Chain of Density objective for subsequent iterations (has previous summary).
   Special instructions are placed BEFORE guidelines so the LLM doesn't deprioritize them."
  [target-length special-instructions]
  (str "<chain_of_density_iteration>
    <task>Create a denser version of the previous summary by incorporating missing entities.</task>
    
    <process>
        <step number=\"1\">Review the already-extracted entities list — do NOT re-extract any of them</step>
        <step number=\"2\">Identify salient entities from the SOURCE TEXT that are missing from BOTH the entity list AND the previous summary</step>
        <step number=\"3\">Rewrite the summary to incorporate these genuinely new entities while maintaining the same word count</step>
    </process>
    
    " (str/replace COD_ENTITY_CRITERIA
        "</entity_criteria>"
        (str "    <criterion name=\"novel\">Not present in the previous summary or the already-extracted entities list</criterion>\n"
          "</entity_criteria>")) "
    "
    (when special-instructions
      (str "
    <special_instructions>
        " special-instructions "
    </special_instructions>
    "))
    "
    <guidelines>
        <instruction>Make every word count by rewriting for better flow</instruction>
        <instruction>Create space through fusion, compression, and removing fillers</instruction>
        <instruction>Use ONLY information present in the source text — remove any interpretation, framing, or meta-commentary</instruction>
        <instruction>Do NOT describe what the text is — summarize WHAT IT SAYS</instruction>
        <instruction>The summary must be self-contained and understandable without the source</instruction>
        <instruction>Missing entities can appear anywhere in the new summary</instruction>
        <instruction>Never drop entities from the previous summary</instruction>
        <instruction>If no space can be made, add fewer new entities rather than dropping old ones</instruction>
        <constraint>Maintain approximately ~" target-length " words</constraint>
    </guidelines>
    
    <output_requirements>
        <field name=\"entities\">Array of NEW entity objects added in this iteration, each with 'entity' (atomic name), 'rationale' (why it's salient), and 'score' (0.0-1.0 salience)</field>
        <field name=\"summary\">The rewritten denser summary (~" target-length " words)</field>
    </output_requirements>
</chain_of_density_iteration>"))

(defn- build-cod-task
  "Builds the task content for a Chain of Density iteration.
   For subsequent iterations, includes the accumulated entity names so the LLM
   knows what's already been extracted and avoids re-extraction or phrase-gaming."
  [source-text previous-summary accumulated-entities]
  (cond-> (str "<source_text>\n" source-text "\n</source_text>")
    previous-summary
    (str "\n\n<previous_summary>\n" previous-summary "\n</previous_summary>")

    (seq accumulated-entities)
    (str "\n\n<already_extracted_entities>\n"
      (str/join ", " accumulated-entities)
      "\n</already_extracted_entities>")))

(defn- build-cod-entity-spec
  "Builds the entity sub-spec for Chain of Density iterations.
   Each entity has a name, brief rationale, and a salience score."
  []
  (spec/spec :CodEntity
    (spec/field ::spec/name :entity
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Atomic entity name — a single person, place, date, concept, or thing")
    (spec/field ::spec/name :rationale
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Brief rationale: why this entity is salient to the text")
    (spec/field ::spec/name :score
      ::spec/type :spec.type/float
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Salience score from 0.0 to 1.0 — how central this entity is to the text's main point")))

(defn- build-cod-spec
  "Builds the spec for a single Chain of Density iteration output.
   Each entity includes a rationale justifying its salience and a 0.0-1.0 score."
  []
  (spec/spec
    {:refs [(build-cod-entity-spec)]}
    (spec/field ::spec/name :entities
      ::spec/type :spec.type/ref
      ::spec/cardinality :spec.cardinality/many
      ::spec/target :CodEntity
      ::spec/description "Salient entities identified in this iteration, each with rationale and salience score")
    (spec/field ::spec/name :summary
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "The summary for this iteration")))

(def ^:private COD_EVAL_CRITERIA
  "Evaluation criteria for Chain of Density summaries."
  {:faithfulness "Does the summary ONLY contain information present in the source text? No fabricated framing, meta-commentary, or interpretation."
   :density "Does the summary pack salient entities and key information efficiently?"
   :coherence "Is the summary well-structured, fluent, and easy to follow?"
   :completeness "Does the summary cover the most important aspects of the source?"})

(defn- cod-iteration-step
  "Performs a single Chain of Density iteration step.
   Tracks accumulated entity names across iterations to prevent re-extraction."
  [router source-text target-length resolved-opts special-instructions eval?
   {:keys [iterations previous-summary accumulated-entities] :as _state}]
  (let [first-iteration? (nil? previous-summary)
        objective (if first-iteration?
                    (build-cod-first-iteration-objective target-length special-instructions)
                    (build-cod-subsequent-iteration-objective target-length special-instructions))
        task (build-cod-task source-text previous-summary accumulated-entities)
        ask-resp (ask!* router (merge (llm-passthrough resolved-opts)
                                 {:spec (build-cod-spec)
                                  :messages [(system objective)
                                             (user task)]}))
        result (:result ask-resp)
        _ (when-not (map? result)
            (trove/log! {:level :warn
                         :id ::cod-non-map-result
                         :data {:result-type (type result) :result (pr-str result)}
                         :msg "SAP returned non-map for CoD spec — check jsonish/spec pipeline"}))
        ;; First-iteration nil summary is unrecoverable; subsequent iterations fall back.
        _ (when (and first-iteration? (nil? (:summary result)))
            (anomaly/fault! "LLM returned nil summary on first CoD iteration"
              {:result result}))
        result (cond-> result
                 (nil? (:summary result))
                 (assoc :summary previous-summary))
        eval-resp (when eval?
                    (eval!* router (merge (llm-passthrough resolved-opts)
                                     {:task (str "Summarize the following text:\n\n" source-text)
                                      :output (:summary result)
                                      :criteria COD_EVAL_CRITERIA})))
        result (if eval-resp
                 (assoc result :score (:overall-score eval-resp))
                 result)
        _ (when (empty? (:entities result))
            (trove/log! {:level :warn :id ::cod-empty-entities
                         :data {:iteration (inc (count iterations)) :first? first-iteration?}
                         :msg "CoD iteration returned zero entities"}))
        ;; Case-insensitive dedup against accumulated entities
        accumulated-lower (set (map str/lower-case (or accumulated-entities [])))
        new-entity-names (->> (keep :entity (:entities result))
                           (remove #(contains? accumulated-lower (str/lower-case %))))
        ;; Aggregate token/cost from ask + eval
        iter-tokens (merge-with + (:tokens ask-resp) (:tokens eval-resp))
        iter-cost (merge-with + (:cost ask-resp) (:cost eval-resp))]
    {:iterations (conj iterations result)
     :previous-summary (:summary result)
     :accumulated-entities (into (or accumulated-entities []) new-entity-names)
     :total-tokens (merge-with + (:total-tokens _state) iter-tokens)
     :total-cost (merge-with + (:total-cost _state) iter-cost)}))

(defn- build-cod-refinement-spec
  "Builds a spec for the CoVe refinement pass on a CoD summary."
  []
  (spec/spec
    (spec/field ::spec/name :summary
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "The refined, faithful summary")))

(defn abstract!*
  "Creates a dense, entity-rich summary of text using Chain of Density prompting.
   
   Based on \"From Sparse to Dense: GPT-4 Summarization with Chain of Density Prompting\"
   (Adams et al., 2023). Iteratively refines a summary by identifying missing salient
   entities and incorporating them while maintaining a fixed length.
   
    When `:eval?` is true, each iteration is scored against the source text using `eval!`,
   giving a quality gradient across iterations (`:score` field per iteration).
   
   When `:refine?` is true, the final summary is verified against the source text using
   Chain of Verification (CoVe) via `refine!`. This catches hallucinated framing,
   unfaithful claims, and information not present in the source.
   
   Opts:
     :text - String. Source text to summarize.
     :model - String. LLM model to use.
     :iterations - Integer. Number of CoD densification iterations (default: 5).
     :target-length - Integer. Target summary length in words (default: 80).
     :special-instructions - String, optional. Additional instructions for the LLM.
     :eval? - Boolean. Score each iteration for a quality gradient (default: false).
     :refine? - Boolean. Run CoVe faithfulness verification on final summary (default: false).
     :threshold - Float. Min eval score for refinement early stop, 0.0-1.0 (default: 0.9)."
  [router {:keys [text iterations target-length special-instructions eval? refine? threshold] :as opts
           :or {iterations DEFAULT_ITERATIONS
                target-length DEFAULT_TARGET_LENGTH
                eval? false
                refine? false
                threshold 0.9}}]
  (let [resolved (cond-> (resolve-opts router opts)
                   (not (contains? opts :format-retries))
                   (assoc :format-retries DEFAULT_COD_FORMAT_RETRIES))
        step-fn (partial cod-iteration-step router text target-length resolved special-instructions eval?)
        initial-state {:iterations [] :previous-summary nil :accumulated-entities []
                       :total-tokens {} :total-cost {}}
        final-state (loop [state     initial-state
                           remaining (long iterations)]
                      (if (zero? remaining)
                        state
                        (let [next-state (step-fn state)
                              iters     (:iterations next-state)]
                          (if (and eval? (>= (count iters) 2))
                            (let [prev-score (:score (nth iters (- (count iters) 2)))
                                  curr-score (:score (last iters))]
                              (if (or (and curr-score (>= (double curr-score) (double threshold)))
                                    (and prev-score curr-score
                                      (< (Math/abs (- (double curr-score) (double prev-score))) 0.02)))
                                next-state
                                (recur next-state (unchecked-dec remaining))))
                            (recur next-state (unchecked-dec remaining))))))
        cod-iterations (:iterations final-state)
        [result duration-ms] (util/with-elapsed
                               (if (and refine? (seq cod-iterations))
                                 (let [final-summary (:summary (last cod-iterations))
                                       refine-result (refine!* router (merge (llm-passthrough resolved)
                                                                        {:spec (build-cod-refinement-spec)
                                                                         :messages [(system (str "You are verifying and refining a summary for faithfulness. "
                                                                                              "Every claim in the summary must be grounded in the source text. "
                                                                                              "Remove any meta-commentary, interpretive framing, or information not present in the source. "
                                                                                              "Preserve entity density and the ~" target-length " word length constraint."))
                                                                                    (user (str "<source_text>\n" text "\n</source_text>\n\n"
                                                                                            "<summary_to_verify>\n" final-summary "\n</summary_to_verify>"))]
                                                                         :iterations 1
                                                                         :threshold threshold
                                                                         :documents [{:id "source"
                                                                                      :pages [{:page "0" :text text}]}]}))
                                       refined-summary (get-in refine-result [:result :summary]
                                                         (:result refine-result))]
                                   (conj (vec (butlast cod-iterations))
                                     (assoc (last cod-iterations)
                                       :summary (if (string? refined-summary) refined-summary final-summary)
                                       :refined? true
                                       :refinement-score (:final-score refine-result))))
                                 cod-iterations))]
    {:result result
     :tokens (:total-tokens final-state)
     :cost (:total-cost final-state)
     :duration-ms duration-ms}))

;; =============================================================================
;; eval! - LLM Self-Evaluation
;; =============================================================================

(def ^:private EVAL_CRITERIA
  "Default evaluation criteria for LLM self-evaluation."
  {:accuracy "Does the output correctly answer the original task/question?"
   :completeness "Does the output fully address all aspects of the task?"
   :relevance "Is the output relevant and on-topic?"
   :coherence "Is the output well-structured and logically coherent?"
   :fairness "Is the output balanced, objective, and free from unfair treatment of any group or perspective?"
   :bias "INVERSE CRITERION - Score the AMOUNT of bias present: 0.0 = no bias (best), 1.0 = extreme bias (worst)."})

(defn- build-eval-spec
  "Builds the spec for evaluation output."
  [_criteria]
  (spec/spec
   ;; Top-level assessment
    (spec/field ::spec/name :overall-score
      ::spec/type :spec.type/float
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Overall quality score from 0.0 to 1.0, weighted average of criteria")
    (spec/field ::spec/name :correct?
      ::spec/type :spec.type/bool
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Whether the output is fundamentally correct (true/false)")
    (spec/field ::spec/name :summary
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Brief overall assessment summary")
   ;; Criteria evaluations as a vector
    (spec/field ::spec/name :criteria
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/many
      ::spec/description "Evaluation of each criterion")
    (spec/field ::spec/name :criteria/name
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Name of the criterion (e.g., accuracy, completeness)")
    (spec/field ::spec/name :criteria/score
      ::spec/type :spec.type/float
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Score from 0.0 to 1.0 for this criterion")
    (spec/field ::spec/name :criteria/confidence
      ::spec/type :spec.type/float
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Confidence in this score assessment from 0.0 to 1.0")
    (spec/field ::spec/name :criteria/reasoning
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Brief reasoning explaining the score")
   ;; Issues as a vector with full details
    (spec/field ::spec/name :issues
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/many
      ::spec/required false
      ::spec/description "List of issues found, empty array if none")
    (spec/field ::spec/name :issues/issue
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Description of the issue")
    (spec/field ::spec/name :issues/severity
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/values {"high" "Major issue that invalidates the output"
                     "medium" "Significant issue that degrades quality"
                     "low" "Minor issue that should be noted"}
      ::spec/description "Severity level of the issue")
    (spec/field ::spec/name :issues/confidence
      ::spec/type :spec.type/float
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Confidence in this issue assessment from 0.0 to 1.0")
    (spec/field ::spec/name :issues/reasoning
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Explanation of why this is an issue")
    (spec/field ::spec/name :issues/mitigation
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/required false
      ::spec/description "Optional suggestion for how to fix or mitigate this issue")))

(defn- build-eval-objective
  "Builds the evaluation objective prompt."
  [criteria ground-truths]
  (let [criteria-list (->> criteria
                        (map (fn [[k v]] (str "- " (name k) ": " v)))
                        (str/join "\n"))
        ground-truths-list (when (seq ground-truths)
                             (->> ground-truths
                               (map-indexed (fn [i gt] (str "        <fact id=\"" (inc (long i)) "\">" gt "</fact>")))
                               (str/join "\n")))]
    (str "<evaluation_task>
    <role>You are a rigorous evaluator assessing LLM outputs for quality and correctness.</role>
    
    <instructions>
        <instruction>Carefully analyze the ORIGINAL TASK and the OUTPUT to evaluate</instruction>
        <instruction>Score each criterion from 0.0 to 1.0 with honest, critical assessment</instruction>
        <instruction>For each criterion, provide a score, confidence (0.0-1.0), and reasoning</instruction>
        <instruction>Identify any issues with severity, confidence, reasoning, and optional mitigation</instruction>
        <instruction>Be skeptical - do not assume correctness without verification</instruction>
        <instruction>Consider edge cases and potential failure modes</instruction>
    </instructions>
    
    <evaluation_criteria>
        Evaluate the output against EACH of these criteria (include ALL in the criteria array):
" criteria-list "
    </evaluation_criteria>
    
    <scoring_guidelines>
        <guideline range=\"0.9-1.0\">Excellent - No issues, fully meets criterion</guideline>
        <guideline range=\"0.7-0.9\">Good - Minor issues, mostly meets criterion</guideline>
        <guideline range=\"0.5-0.7\">Acceptable - Some issues, partially meets criterion</guideline>
        <guideline range=\"0.3-0.5\">Poor - Significant issues, barely meets criterion</guideline>
        <guideline range=\"0.0-0.3\">Failing - Major issues, does not meet criterion</guideline>
    </scoring_guidelines>
    "
      (when ground-truths-list
        (str "
    <ground_truths>
        <note>Use these reference facts to verify correctness. The output should align with ALL of these.</note>
" ground-truths-list "
    </ground_truths>
    "))
      "
    <output_requirements>
        <requirement>Include an entry in 'criteria' array for EACH criterion listed above</requirement>
        <requirement>Calculate overall-score as weighted average of criteria scores</requirement>
        <requirement>Set correct? to false if ANY high-severity issue exists</requirement>
        <requirement>For each issue, include: issue description, severity, confidence, reasoning, and optionally a mitigation suggestion</requirement>
        <requirement>List issues from most to least severe (empty array if none)</requirement>
    </output_requirements>
</evaluation_task>")))

(defn- build-eval-task
  "Builds the evaluation task content."
  [original-task output context]
  (str "<original_task>\n" original-task "\n</original_task>\n\n"
    "<output_to_evaluate>\n" (if (string? output) output (pr-str output)) "\n</output_to_evaluate>"
    (when context
      (str "\n\n<additional_context>\n" context "\n</additional_context>"))))

(defn- build-scores
  "Builds scores map from criteria vector."
  [eval-result]
  (let [criteria (:criteria eval-result)
        ;; Build scores map from criteria vector
        criteria-scores (->> criteria
                          (map (fn [{:keys [name score]}]
                                 [(keyword name) score]))
                          (into {}))]
    (assoc criteria-scores :overall (:overall-score eval-result))))

(defn eval!
  "Routed eval! — provider fallback + rate limiting.
   Accepts `:reasoning :quick|:balanced|:deep` (translated per api-style)."
  [router opts]
  (let [prefs (cond (:strategy opts) (select-keys opts [:strategy])
                    (:prefer opts) (select-keys opts [:prefer :capabilities :exclude-model])
                    :else {:strategy :root})]
    (router/with-provider-fallback
      router prefs
      (fn [provider model-map]
        (eval!* router (inject-routed-params opts provider model-map))))))

(defn eval!*
  "Low-level eval — calls ask!* directly without routing. Use eval! instead."
  [router {:keys [task output messages criteria ground-truths context]
           :as opts
           :or {criteria EVAL_CRITERIA}}]
  (let [{:keys [model api-key base-url api-style provider-id]} (resolve-opts router opts)
         ;; Resolve task: explicit :task wins, else extract from :messages
        effective-task (or task
                         (when messages
                           (->> messages
                             (remove #(= "assistant" (:role %)))
                             (map :content)
                             (str/join "\n")))
                         "")
        eval-spec (build-eval-spec criteria)
        objective (build-eval-objective criteria ground-truths)
        eval-task (build-eval-task effective-task output context)
        [{:keys [result tokens cost]} duration-ms]
        (util/with-elapsed
          (ask!* router (merge (llm-passthrough
                                 (merge {:model model :api-key api-key
                                         :base-url base-url :api-style api-style
                                         :provider-id provider-id}
                                   (select-keys opts LLM_PASSTHROUGH_KEYS)))
                          {:spec eval-spec
                           :messages [(system objective)
                                      (user eval-task)]})))
        scores (build-scores result)]
    (assoc result
      :scores scores
      :duration-ms duration-ms
      :tokens tokens
      :cost cost)))

;; =============================================================================
;; refine! - Iterative refinement with decomposition and verification
;; =============================================================================

(def ^:private DEFAULT_REFINE_ITERATIONS
  "Default number of refinement iterations."
  3)

(def ^:private DEFAULT_REFINE_THRESHOLD
  "Default quality threshold for early stopping."
  0.9)

;; Decomposition spec and functions
(defn- build-decomposition-spec
  "Builds the spec for decomposing output into verifiable claims."
  []
  (spec/spec
    (spec/field ::spec/name :claims
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/many
      ::spec/description "Verifiable claims or assertions extracted from the output")
    (spec/field ::spec/name :claims/claim
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "The specific claim or assertion text")
    (spec/field ::spec/name :claims/category
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/values {"factual" "Objective fact that can be independently verified"
                     "inference" "Logical conclusion derived from other information"
                     "subjective" "Opinion, judgment, or subjective assessment"}
      ::spec/description "Category of the claim")
    (spec/field ::spec/name :claims/confidence
      ::spec/type :spec.type/float
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Confidence that this claim is accurate (0.0 to 1.0)")
    (spec/field ::spec/name :claims/verifiable?
      ::spec/type :spec.type/bool
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Whether this claim can be independently verified")))

(defn- build-decomposition-objective
  "Builds the objective prompt for claim decomposition."
  [original-objective]
  (str "<decomposition_task>
    <role>You are an expert analyst who identifies and extracts verifiable claims from text.</role>
    
    <context>
        <original_objective>" original-objective "</original_objective>
    </context>
    
    <instructions>
        <instruction>Analyze the OUTPUT and extract all distinct claims or assertions</instruction>
        <instruction>Each claim should be a single, atomic statement that can be evaluated</instruction>
        <instruction>Categorize each claim as factual, inference, or subjective</instruction>
        <instruction>Rate your confidence in each claim's accuracy (0.0 to 1.0)</instruction>
        <instruction>Mark whether each claim can be independently verified</instruction>
        <instruction>Focus on claims that matter most to the original objective</instruction>
        <instruction>Include both explicit and implicit claims</instruction>
    </instructions>
    
    <claim_guidelines>
        <guideline>Factual claims: Statements about concrete facts, numbers, dates, names</guideline>
        <guideline>Inference claims: Conclusions, implications, or derived statements</guideline>
        <guideline>Subjective claims: Opinions, evaluations, or value judgments</guideline>
        <guideline>Prioritize claims that are critical to the output's correctness</guideline>
    </claim_guidelines>
    
    <output_requirements>
        <requirement>Extract 3-10 claims depending on output complexity</requirement>
        <requirement>Order claims by importance to the original objective</requirement>
        <requirement>Be thorough - missing claims means missing verification opportunities</requirement>
    </output_requirements>
</decomposition_task>"))

(defn- build-decomposition-task
  "Builds the task content for decomposition."
  [original-task output]
  (str "<original_task>\n" original-task "\n</original_task>\n\n"
    "<output_to_decompose>\n" (if (string? output) output (pr-str output)) "\n</output_to_decompose>"))

(defn- decompose-output
  "Extracts verifiable claims from an LLM output using DuTy-inspired decomposition."
  [output original-task original-objective model router llm-opts]
  (:result (ask!* router (merge llm-opts
                           {:spec (build-decomposition-spec)
                            :messages [(system (build-decomposition-objective original-objective))
                                       (user (build-decomposition-task original-task output))]
                            :model model}))))

;; Verification helper functions (truncation, source documents, claim formatting)

(defn- format-claims-for-verification
  "Formats claims into a string for the verification task."
  [claims]
  (->> claims
    (map-indexed (fn [i claim]
                   (str "<claim id=\"" (inc (long i)) "\">\n"
                     "  <text>" (:claim claim) "</text>\n"
                     "  <category>" (:category claim) "</category>\n"
                     "  <confidence>" (:confidence claim) "</confidence>\n"
                     "</claim>")))
    (str/join "\n")))

(def ^:private MAX_DOCUMENT_CONTENT_CHARS 16000)

(defn- truncate-document-pages
  [pages remaining]
  (loop [pages pages
         remaining (long remaining)
         acc []]
    (if (or (empty? pages) (<= remaining 0))
      [acc remaining]
      (let [{:keys [page text]} (first pages)
            text (or text "")
            trimmed (if (> (long (count text)) remaining)
                      (subs text 0 (int remaining))
                      text)
            remaining' (- remaining (long (count trimmed)))
            acc' (conj acc {:page page :text trimmed})]
        (recur (rest pages) remaining' acc')))))

(defn- truncate-documents
  [documents max-chars]
  (loop [documents documents
         remaining (long max-chars)
         acc []]
    (if (or (empty? documents) (<= remaining 0))
      acc
      (let [doc (first documents)
            [pages remaining'] (truncate-document-pages (:pages doc) remaining)
            acc' (conj acc (assoc doc :pages pages))]
        (recur (rest documents) (long remaining') acc')))))

(defn- build-source-documents-block
  [documents]
  (when (seq documents)
    (let [truncated-documents (truncate-documents documents MAX_DOCUMENT_CONTENT_CHARS)]
      (str "<source_documents>\n"
        (str/join "\n"
          (map (fn [{:keys [id pages]}]
                 (str "  <document id=\"" id "\">\n"
                   (str/join "\n"
                     (map (fn [{:keys [page text]}]
                            (str "    <page number=\"" page "\">" text "</page>"))
                       pages))
                   "\n  </document>"))
            truncated-documents))
        "\n</source_documents>"))))

;; =============================================================================
;; Factored CoVe: Question Planning + Independent Per-Claim Verification
;; =============================================================================

(defn- build-question-planning-spec
  "Builds the spec for generating verification questions (without answers)."
  []
  (spec/spec
    (spec/field ::spec/name :questions
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/many
      ::spec/description "Verification questions, one per claim")
    (spec/field ::spec/name :questions/claim
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "The original claim text being verified")
    (spec/field ::spec/name :questions/question
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "A targeted verification question to test this claim")))

(defn- build-question-planning-objective
  "Builds the system prompt for verification question planning."
  [original-objective]
  (str "<question_planning_task>
    <role>You are an expert fact-checker who designs targeted verification questions.</role>
    
    <context>
        <original_objective>" original-objective "</original_objective>
    </context>
    
    <instructions>
        <instruction>For each claim, generate ONE specific verification question</instruction>
        <instruction>The question should be answerable independently without seeing the original output</instruction>
        <instruction>Questions should test the factual accuracy of the claim</instruction>
        <instruction>Do NOT answer the questions - only generate them</instruction>
    </instructions>
    
    <question_guidelines>
        <guideline>Questions should be specific and testable</guideline>
        <guideline>Avoid yes/no questions - prefer questions that require detailed answers</guideline>
        <guideline>Each question should focus on the most critical aspect of its claim</guideline>
        <guideline>Questions should be self-contained - understandable without additional context</guideline>
    </question_guidelines>
</question_planning_task>"))

(defn- build-question-planning-task
  "Builds the task content for verification question planning."
  [original-task claims]
  (str "<original_task>\n" original-task "\n</original_task>\n\n"
    "<claims_to_plan_questions_for>\n" (format-claims-for-verification claims) "\n</claims_to_plan_questions_for>"))

(defn- plan-verification-questions
  "Step 1 of Factored CoVe: Generate verification questions without answers."
  [claims original-task original-objective model router llm-opts]
  (:result (ask!* router (merge llm-opts
                           {:spec (build-question-planning-spec)
                            :messages [(system (build-question-planning-objective original-objective))
                                       (user (build-question-planning-task original-task claims))]
                            :model model}))))

(defn- build-single-verification-spec
  "Builds the spec for independently verifying a single claim."
  [has-documents?]
  (if has-documents?
    (spec/spec
      (spec/field ::spec/name :answer
        ::spec/type :spec.type/string
        ::spec/cardinality :spec.cardinality/one
        ::spec/description "Your independent answer to the verification question")
      (spec/field ::spec/name :verdict
        ::spec/type :spec.type/string
        ::spec/cardinality :spec.cardinality/one
        ::spec/values {"correct" "The claim is accurate based on your independent answer"
                       "incorrect" "The claim contradicts your independent answer"
                       "partially-correct" "The claim is partly accurate but needs refinement"
                       "uncertain" "Cannot determine accuracy with available information"}
        ::spec/description "Verdict comparing your answer against the original claim")
      (spec/field ::spec/name :reasoning
        ::spec/type :spec.type/string
        ::spec/cardinality :spec.cardinality/one
        ::spec/description "Explanation of your reasoning")
      (spec/field ::spec/name :correction
        ::spec/type :spec.type/string
        ::spec/cardinality :spec.cardinality/one
        ::spec/required false
        ::spec/description "Suggested correction if claim is incorrect or partially correct")
      (spec/field ::spec/name :document-id
        ::spec/type :spec.type/string
        ::spec/cardinality :spec.cardinality/one
        ::spec/required false
        ::spec/description "Document ID supporting or contradicting the claim")
      (spec/field ::spec/name :page
        ::spec/type :spec.type/int
        ::spec/cardinality :spec.cardinality/one
        ::spec/required false
        ::spec/description "Page number supporting or contradicting the claim")
      (spec/field ::spec/name :section
        ::spec/type :spec.type/string
        ::spec/cardinality :spec.cardinality/one
        ::spec/required false
        ::spec/description "Section supporting or contradicting the claim"))
    (spec/spec
      (spec/field ::spec/name :answer
        ::spec/type :spec.type/string
        ::spec/cardinality :spec.cardinality/one
        ::spec/description "Your independent answer to the verification question")
      (spec/field ::spec/name :verdict
        ::spec/type :spec.type/string
        ::spec/cardinality :spec.cardinality/one
        ::spec/values {"correct" "The claim is accurate based on your independent answer"
                       "incorrect" "The claim contradicts your independent answer"
                       "partially-correct" "The claim is partly accurate but needs refinement"
                       "uncertain" "Cannot determine accuracy with available information"}
        ::spec/description "Verdict comparing your answer against the original claim")
      (spec/field ::spec/name :reasoning
        ::spec/type :spec.type/string
        ::spec/cardinality :spec.cardinality/one
        ::spec/description "Explanation of your reasoning")
      (spec/field ::spec/name :correction
        ::spec/type :spec.type/string
        ::spec/cardinality :spec.cardinality/one
        ::spec/required false
        ::spec/description "Suggested correction if claim is incorrect or partially correct"))))

(defn- build-single-verification-objective
  "Builds the system prompt for independently answering a single verification question."
  [documents]
  (if (seq documents)
    "<independent_verification>
    <role>You are a rigorous fact-checker answering a verification question.</role>
    
    <instructions>
        <instruction>Answer the verification question using ONLY the provided source documents and your knowledge</instruction>
        <instruction>You are NOT shown the original output being verified - this is intentional</instruction>
        <instruction>Answer as accurately and independently as possible</instruction>
        <instruction>Then compare your answer against the original claim to determine a verdict</instruction>
        <instruction>Cite the specific document, page, and section that supports your answer</instruction>
    </instructions>
    
    <principles>
        <principle>Be skeptical - do not assume the claim is correct</principle>
        <principle>Base your answer on evidence, not assumptions</principle>
        <principle>Mark uncertain when you genuinely cannot determine the answer</principle>
    </principles>
</independent_verification>"
    "<independent_verification>
    <role>You are a rigorous fact-checker answering a verification question.</role>
    
    <instructions>
        <instruction>Answer the verification question independently using your knowledge</instruction>
        <instruction>You are NOT shown the original output being verified - this is intentional</instruction>
        <instruction>Answer as accurately and independently as possible</instruction>
        <instruction>Then compare your answer against the original claim to determine a verdict</instruction>
    </instructions>
    
    <principles>
        <principle>Be skeptical - do not assume the claim is correct</principle>
        <principle>Base your answer on evidence, not assumptions</principle>
        <principle>Mark uncertain when you genuinely cannot determine the answer</principle>
    </principles>
</independent_verification>"))

(defn- build-single-verification-task
  "Builds the task for independently verifying a single claim."
  [claim-text question documents]
  (str "<claim_to_verify>" claim-text "</claim_to_verify>\n\n"
    "<verification_question>" question "</verification_question>"
    (when (seq documents)
      (str "\n\n" (build-source-documents-block documents)))))

(defn- verify-single-claim
  "Step 2 of Factored CoVe: Answer a single verification question independently."
  [claim-text question model router documents llm-opts]
  (:result (ask!* router (merge llm-opts
                           {:spec (build-single-verification-spec (boolean (seq documents)))
                            :messages [(system (build-single-verification-objective documents))
                                       (user (build-single-verification-task claim-text question documents))]
                            :model model}))))

(defn- verifiable-claim?
  "Returns true if a claim should be sent to verification."
  [claim]
  (and (not= false (:verifiable? claim))
    (not= "subjective" (:category claim))))

(defn- filter-verifiable-claims
  "Splits claims into verifiable and non-verifiable groups."
  [claims]
  (let [grouped (group-by verifiable-claim? claims)]
    {:verifiable (get grouped true [])
     :non-verifiable (get grouped false [])}))

(defn- verify-claims
  "Verifies extracted claims using Factored CoVe (independent per-claim verification)."
  [claims original-task original-objective model router documents llm-opts]
  (let [{:keys [verifiable non-verifiable]} (filter-verifiable-claims claims)]
    (if (empty? verifiable)
      ;; All claims are non-verifiable — return as uncertain
      {:verifications (mapv (fn [claim]
                              {:claim (:claim claim)
                               :question "N/A"
                               :answer "N/A"
                               :verdict "uncertain"
                               :reasoning "Claim is subjective or not independently verifiable"})
                        non-verifiable)}
      ;; Factored CoVe
      (let [;; Step 1: Plan verification questions (one LLM call — sees all claims)
            planning-result (plan-verification-questions verifiable original-task
                              original-objective model router llm-opts)
            planned-questions (:questions planning-result)

            ;; Step 2: Answer each question independently (one LLM call per question)
            factored-verifications
            (mapv (fn [planned-q]
                    (let [claim-text (:claim planned-q)
                          question (:question planned-q)
                          result (verify-single-claim claim-text question model router documents llm-opts)]
                      (merge {:claim claim-text :question question} result)))
              planned-questions)

            ;; Non-verifiable claims → uncertain without LLM calls
            skipped-results
            (mapv (fn [claim]
                    {:claim (:claim claim)
                     :question "N/A"
                     :answer "N/A"
                     :verdict "uncertain"
                     :reasoning "Claim is subjective or not independently verifiable"})
              non-verifiable)]
        {:verifications (into factored-verifications skipped-results)}))))

;; =============================================================================
;; Factor+Revise: Cross-Claim Inconsistency Detection
;; =============================================================================

(defn- format-verifications-for-inconsistency-detection
  "Formats verification results for cross-claim inconsistency detection."
  [verifications]
  (->> verifications
    (map-indexed (fn [i v]
                   (str "<verified_claim id=\"" (inc (long i)) "\">\n"
                     "  <claim>" (:claim v) "</claim>\n"
                     "  <independent_answer>" (:answer v) "</independent_answer>\n"
                     "  <verdict>" (:verdict v) "</verdict>\n"
                     "  <reasoning>" (:reasoning v) "</reasoning>\n"
                     (when (:correction v)
                       (str "  <correction>" (:correction v) "</correction>\n"))
                     "</verified_claim>")))
    (str/join "\n")))

(defn- build-inconsistency-detection-spec
  "Builds the spec for detecting cross-claim inconsistencies."
  []
  (spec/spec
    (spec/field ::spec/name :inconsistencies
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/many
      ::spec/description "Cross-claim inconsistencies detected between verification results and original output. Empty array if none found.")
    (spec/field ::spec/name :inconsistencies/claims
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "The conflicting claim texts, quoted verbatim from the verification results")
    (spec/field ::spec/name :inconsistencies/type
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/values {"contradiction" "Two or more claims directly contradict each other"
                     "inconsistency" "Claims are not directly contradictory but cannot both be fully accurate"
                     "drift" "Verification answer reveals the original output diverged from facts in a way not caught by individual verdicts"}
      ::spec/description "Type of inconsistency")
    (spec/field ::spec/name :inconsistencies/description
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Clear description of the inconsistency and why it matters")
    (spec/field ::spec/name :inconsistencies/severity
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/values {"high" "Factual contradiction that must be resolved"
                     "medium" "Notable inconsistency that should be addressed"
                     "low" "Minor tension that may be acceptable"}
      ::spec/description "Severity of the inconsistency")
    (spec/field ::spec/name :inconsistencies/resolution
      ::spec/type :spec.type/string
      ::spec/cardinality :spec.cardinality/one
      ::spec/description "Suggested resolution for the inconsistency")))

(defn- build-inconsistency-detection-objective
  "Builds the system prompt for cross-claim inconsistency detection."
  [original-objective]
  (str "<inconsistency_detection>
    <role>You are an expert analyst who identifies contradictions and inconsistencies across multiple verified claims.</role>

    <context>
        <original_objective>" original-objective "</original_objective>
    </context>

    <instructions>
        <instruction>Compare ALL verification answers against each other to find cross-claim contradictions</instruction>
        <instruction>Compare each verification answer against the original output to find undetected divergences</instruction>
        <instruction>Look for cases where individually 'correct' claims conflict with each other</instruction>
        <instruction>Identify factual drift — where the original output subtly diverges from verification answers</instruction>
        <instruction>If no inconsistencies are found, return an empty inconsistencies array</instruction>
    </instructions>

    <detection_guidelines>
        <guideline>Focus on factual and logical contradictions, not stylistic differences</guideline>
        <guideline>A contradiction is when two claims cannot both be true simultaneously</guideline>
        <guideline>An inconsistency is when claims are in tension but not directly contradictory</guideline>
        <guideline>Drift is when the original output says X but verification evidence says Y, even if the individual verdict was 'correct'</guideline>
        <guideline>Quote the specific conflicting texts verbatim</guideline>
    </detection_guidelines>
</inconsistency_detection>"))

(defn- build-inconsistency-detection-task
  "Builds the task content for cross-claim inconsistency detection."
  [original-output verifications]
  (str "<original_output>\n" (if (string? original-output) original-output (pr-str original-output)) "\n</original_output>\n\n"
    "<verification_results>\n" (format-verifications-for-inconsistency-detection verifications) "\n</verification_results>\n\n"
    "<instruction>Identify any cross-claim contradictions, inconsistencies, or factual drift between the verification results and the original output. If none are found, return an empty inconsistencies array.</instruction>"))

(defn- detect-inconsistencies
  "Step 3 of Factor+Revise CoVe: Explicit cross-claim inconsistency detection."
  [current-output verifications original-objective model router llm-opts]
  (if (< (long (count verifications)) 2)
    ;; Need at least 2 verified claims to detect cross-claim inconsistencies
    {:inconsistencies []}
    (:result (ask!* router (merge llm-opts
                             {:spec (build-inconsistency-detection-spec)
                              :messages [(system (build-inconsistency-detection-objective original-objective))
                                         (user (build-inconsistency-detection-task current-output verifications))]
                              :model model})))))

;; Refinement functions
(defn- format-verifications-for-refinement
  "Formats verification results into feedback for refinement."
  [verifications]
  (->> verifications
    (map-indexed (fn [i v]
                   (str "<verification id=\"" (inc (long i)) "\">\n"
                     "  <claim>" (:claim v) "</claim>\n"
                     "  <verdict>" (:verdict v) "</verdict>\n"
                     "  <reasoning>" (:reasoning v) "</reasoning>\n"
                     (when (:correction v)
                       (str "  <correction>" (:correction v) "</correction>\n"))
                     "</verification>")))
    (str/join "\n")))

(defn- format-issues-for-refinement
  "Formats evaluation issues into feedback for refinement."
  [issues]
  (if (empty? issues)
    "<issues>None identified</issues>"
    (->> issues
      (map-indexed (fn [i issue]
                     (str "<issue id=\"" (inc (long i)) "\" severity=\"" (:severity issue) "\">\n"
                       "  <description>" (:issue issue) "</description>\n"
                       "  <reasoning>" (:reasoning issue) "</reasoning>\n"
                       (when (:mitigation issue)
                         (str "  <mitigation>" (:mitigation issue) "</mitigation>\n"))
                       "</issue>")))
      (str/join "\n"))))

(defn- format-inconsistencies-for-refinement
  "Formats cross-claim inconsistencies into feedback for refinement."
  [inconsistencies]
  (if (empty? inconsistencies)
    "<cross_claim_inconsistencies>None detected</cross_claim_inconsistencies>"
    (str "<cross_claim_inconsistencies>\n"
      (->> inconsistencies
        (map-indexed (fn [i incon]
                       (str "  <inconsistency id=\"" (inc (long i)) "\" type=\"" (:type incon) "\" severity=\"" (:severity incon) "\">\n"
                         "    <claims>" (:claims incon) "</claims>\n"
                         "    <description>" (:description incon) "</description>\n"
                         "    <resolution>" (:resolution incon) "</resolution>\n"
                         "  </inconsistency>")))
        (str/join "\n"))
      "\n</cross_claim_inconsistencies>")))

(defn- build-refinement-objective
  "Builds the objective prompt for output refinement."
  [original-objective iteration]
  (str "<refinement_task>
    <role>You are an expert editor who improves outputs based on verification feedback.</role>
    
    <context>
        <original_objective>" original-objective "</original_objective>
        <iteration>" iteration "</iteration>
    </context>
    
    <instructions>
        <instruction>Review the ORIGINAL OUTPUT and the VERIFICATION FEEDBACK</instruction>
        <instruction>Address ALL issues marked as incorrect or partially-correct</instruction>
        <instruction>Apply suggested corrections where provided</instruction>
        <instruction>Resolve ALL cross-claim inconsistencies — these are conflicts between claims that were individually verified but contradict each other</instruction>
        <instruction>Maintain all aspects that were verified as correct and are not part of an inconsistency</instruction>
        <instruction>Improve clarity and accuracy without changing correct content</instruction>
        <instruction>Ensure the refined output fully addresses the original task</instruction>
    </instructions>
    
    <refinement_principles>
        <principle>Prioritize resolving cross-claim contradictions — these indicate structural errors in the output</principle>
        <principle>Prioritize fixing high-severity issues next</principle>
        <principle>Do not introduce new errors while fixing existing ones</principle>
        <principle>Preserve the overall structure and intent of the original output</principle>
        <principle>Be conservative - only change what needs to be changed</principle>
        <principle>Ensure corrections are factually accurate</principle>
    </refinement_principles>
</refinement_task>"))

(defn- build-refinement-task
  "Builds the task content for refinement with full context."
  [original-task current-output verifications evaluation-issues inconsistencies]
  (str "<original_task>\n" original-task "\n</original_task>\n\n"
    "<current_output>\n" (if (string? current-output) current-output (pr-str current-output)) "\n</current_output>\n\n"
    "<verification_feedback>\n" (format-verifications-for-refinement verifications) "\n</verification_feedback>\n\n"
    (format-inconsistencies-for-refinement inconsistencies) "\n\n"
    "<evaluation_issues>\n" (format-issues-for-refinement evaluation-issues) "\n</evaluation_issues>\n\n"
    "<instruction>Generate a refined version of the output that resolves all cross-claim inconsistencies, addresses the verification feedback and evaluation issues, while maintaining correct content.</instruction>"))

(defn- should-stop?
  "Determines if refinement should stop based on strategy and current state."
  [stop-strategy threshold current-score current-iteration max-iterations]
  (let [current-score (double current-score)
        threshold (double threshold)
        current-iteration (long current-iteration)
        max-iterations (long max-iterations)]
    (case stop-strategy
      :threshold (>= current-score threshold)
      :fixed (>= current-iteration max-iterations)
      :both (or (>= current-score threshold)
              (>= current-iteration max-iterations))
      ;; Default to :both behavior
      (or (>= current-score threshold)
        (>= current-iteration max-iterations)))))

(defn- refinement-iteration-step
  "Performs a single refinement iteration:
   decompose -> verify -> detect inconsistencies -> evaluate -> refine."
  [spec original-objective original-task model router llm-opts eval-criteria documents
   {:keys [current-output iterations iteration-num prompt-evolution] :as _state}]
  (let [iteration (inc (long iteration-num))

        [{:keys [claims verifications inconsistencies evaluation
                 refined-output refinement-objective refinement-task]} iter-duration-ms]
        (util/with-elapsed
          (let [;; Step 1: Decompose - extract claims from current output
                decomposition (decompose-output current-output original-task original-objective
                                model router llm-opts)
                claims (:claims decomposition)

                ;; Step 2: Verify - check claims with independent per-claim verification
                verification (verify-claims claims original-task original-objective model router documents llm-opts)
                verifications (:verifications verification)

                ;; Step 3: Detect cross-claim inconsistencies (Factor+Revise)
                inconsistency-result (detect-inconsistencies current-output verifications
                                       original-objective model router llm-opts)
                inconsistencies (or (:inconsistencies inconsistency-result) [])

                ;; Step 4: Evaluate - get quality assessment
                evaluation (eval!* router {:task original-task
                                           :output current-output
                                           :model model
                                           :api-key (:api-key llm-opts)
                                           :base-url (:base-url llm-opts)
                                           :api-style (:api-style llm-opts)
                                           :provider-id (:provider-id llm-opts)
                                           :criteria eval-criteria})

                ;; Step 5: Refine - generate improved output incorporating all feedback
                refinement-objective (build-refinement-objective original-objective iteration)
                refinement-task (build-refinement-task original-task current-output
                                  verifications (:issues evaluation)
                                  inconsistencies)
                {:keys [result]} (ask!* router (merge llm-opts
                                                 {:spec spec
                                                  :messages [(system refinement-objective)
                                                             (user refinement-task)]
                                                  :model model}))]
            {:claims claims :verifications verifications
             :inconsistencies inconsistencies :evaluation evaluation
             :refined-output result :refinement-objective refinement-objective
             :refinement-task refinement-task}))

        ;; Build iteration record
        incorrect-count (->> verifications (filter #(= "incorrect" (:verdict %))) count)
        iteration-record {:iteration iteration
                          :output current-output
                          :claims claims
                          :verification {:verifications verifications
                                         :incorrect-count incorrect-count
                                         :uncertain-count (->> verifications
                                                            (filter #(= "uncertain" (:verdict %)))
                                                            count)}
                          :inconsistencies {:detected inconsistencies
                                            :count (count inconsistencies)
                                            :high-severity-count (->> inconsistencies
                                                                   (filter #(= "high" (:severity %)))
                                                                   count)}
                          :evaluation evaluation
                          :refinements (->> verifications
                                         (filter #(contains? #{"incorrect" "partially-correct"} (:verdict %)))
                                         (mapv #(select-keys % [:claim :verdict :correction])))
                          :duration-ms iter-duration-ms}

        ;; Track prompt evolution
        prompt-record {:objective refinement-objective
                       :task refinement-task
                       :iteration iteration}]

    {:current-output refined-output
     :iterations (conj iterations iteration-record)
     :iteration-num iteration
     :latest-score (:overall-score evaluation)
     :prompt-evolution (conj prompt-evolution prompt-record)}))

(defn- calculate-deltas
  "Compute score differences between adjacent iterations."
  [scores]
  (if (<= (long (count scores)) 1)
    []
    (mapv #(- (double %1) (double %2)) (rest scores) scores)))

(defn- determine-trend
  "Analyze deltas to determine improvement trend."
  [deltas]
  (if (empty? deltas)
    :stable
    (let [threshold 0.01
          all-positive? (every? #(> (double %) threshold) deltas)
          all-negative? (every? #(< (double %) (- threshold)) deltas)]
      (cond
        all-positive? :improving
        all-negative? :declining
        :else :stable))))

(defn- compute-gradient
  "Compute gradient map from a vector of scores."
  [scores]
  (let [deltas (calculate-deltas scores)
        trend (determine-trend deltas)
        total (if (empty? scores) 0.0 (- (double (last scores)) (double (first scores))))]
    {:deltas deltas
     :trend trend
     :total total}))

(defn refine!*
  "Low-level refine — calls ask!* directly without routing. Use refine! instead.
   
   Implements the Factor+Revise variant of Chain of Verification (CoVe)
   from Dhuliawala et al. (2023), combined with DuTy decomposition."
  [router {:keys [spec messages iterations threshold stop-strategy
                  window-size criteria documents]
           :as opts
           :or {iterations DEFAULT_REFINE_ITERATIONS
                threshold DEFAULT_REFINE_THRESHOLD
                stop-strategy :both
                window-size 3
                criteria EVAL_CRITERIA}}]
  (let [resolved-opts (resolve-opts router opts)
        {:keys [model api-key base-url api-style provider-id]} resolved-opts
        llm-opts (merge (llm-passthrough resolved-opts)
                   ;; Caller's original opts can carry passthrough keys that
                   ;; resolve-opts didn't see (e.g. when refine!* is invoked
                   ;; from inside a routed entrypoint that already mapped
                   ;; them). Last-merge-wins semantics keep caller intent.
                   (select-keys opts LLM_PASSTHROUGH_KEYS)
                   {:model model :api-key api-key :base-url base-url
                    :api-style api-style :provider-id provider-id})
         ;; Extract objective/task from messages for internal decompose/verify/eval pipeline
        original-objective (or (->> messages (filter #(= "system" (:role %))) first :content) "")
        original-task (or (->> messages (filter #(= "user" (:role %))) first :content) "")
          ;; Phase 1: Generate initial output
        {:keys [result]} (ask!* router (merge llm-opts
                                         {:spec spec
                                          :messages messages}))
        initial-output result

          ;; Phase 2: Iterative refinement loop
        initial-state {:current-output initial-output
                       :iterations []
                       :iteration-num 0
                       :latest-score 0.0
                       :prompt-evolution []}

        step-fn (partial refinement-iteration-step
                  spec original-objective original-task model router llm-opts criteria documents)

          ;; Run iterations until stopping condition met
        [final-state total-duration-ms]
        (util/with-elapsed
          (loop [state initial-state]
            (if (should-stop? stop-strategy threshold
                  (:latest-score state)
                  (:iteration-num state)
                  iterations)
              state
              (recur (step-fn state)))))

          ;; Phase 3: Final evaluation
        final-output (:current-output final-state)
        final-evaluation (eval!* router {:task original-task
                                         :output final-output
                                         :model model
                                         :api-key api-key
                                         :base-url base-url
                                         :api-style api-style
                                         :provider-id provider-id
                                         :criteria criteria})
        final-score (:overall-score final-evaluation)

          ;; Phase 4: Compute gradient and window
        all-scores (conj (mapv #(get-in % [:evaluation :overall-score]) (:iterations final-state))
                     final-score)
        gradient (compute-gradient all-scores)
        window-scores (vec (take-last window-size all-scores))
        iterations-count (:iteration-num final-state)
        converged? (>= (double final-score) (double threshold))]

    {:result final-output
     :iterations (:iterations final-state)
     :final-evaluation final-evaluation
     :final-score final-score
     :converged? converged?
     :iterations-count iterations-count
     :total-duration-ms total-duration-ms
     :tokens (:total-tokens final-state)
     :cost (:total-cost final-state)
     :gradient gradient
     :prompt-evolution (:prompt-evolution final-state)
     :window {:size window-size
              :scores window-scores}}))

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

   Returns `[]` on HTTP failure rather than throwing — callers fall
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
         models-url            (str base-url models-path)
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
         models                (normalize-models-response body)]
     (filter-provider-models provider-id models))))

;; =============================================================================
;; sample! - Generate test data samples with self-correction
;; =============================================================================

(declare sample!*)

(def ^:private SAMPLE_CRITERIA
  "Default evaluation criteria for generated samples."
  {:diversity "Are the generated samples diverse and varied across all fields? Samples should cover different cases and not be repetitive."
   :realism "Are the samples realistic and plausible? Values should be believable, not placeholder-like."
   :completeness "Do all samples include all required fields with appropriate values?"
   :correctness "Do the field values match the expected types and constraints (e.g., valid emails, reasonable ages)?"})

(def ^:private DEFAULT_SAMPLE_ITERATIONS
  "Default number of sample refinement iterations."
  3)

(def ^:private DEFAULT_SAMPLE_THRESHOLD
  "Default quality threshold for sample generation."
  0.9)

(defn- build-sample-refinement-feedback
  "Builds feedback for sample refinement based on evaluation results."
  [evaluation n]
  (let [issues (:issues evaluation)
        summary (:summary evaluation)
        scores (:scores evaluation)]
    (str "<evaluation_feedback>\n"
      "  <summary>" summary "</summary>\n"
      "  <scores>" (pr-str (dissoc scores :overall)) "</scores>\n"
      (when (seq issues)
        (str "  <issues>\n"
          (->> issues
            (map (fn [{:keys [issue severity reasoning mitigation]}]
                   (str "    <issue severity=\"" severity "\">\n"
                     "      <description>" issue "</description>\n"
                     (when reasoning (str "      <reasoning>" reasoning "</reasoning>\n"))
                     (when mitigation (str "      <mitigation>" mitigation "</mitigation>\n"))
                     "    </issue>")))
            (str/join "\n"))
          "\n  </issues>\n"))
      "</evaluation_feedback>\n\n"
      "Regenerate " n " improved samples addressing the feedback above. "
      "Maintain what was good and fix the identified issues.")))

(defn sample!
  "Routed sample! — provider fallback + rate limiting.
   Accepts `:reasoning :quick|:balanced|:deep` (translated per api-style)."
  [router opts]
  (let [resolved (router/resolve-routing router (routing-opts-with-reasoning opts))]
    (router/with-provider-fallback
      router (:prefs resolved)
      (fn [provider model-map]
        (sample!* router (inject-routed-params opts provider model-map))))))

(defn sample!*
  "Low-level sample — generates test data without routing. Use sample! instead."
  [router {:keys [spec messages criteria iterations threshold]
           :as opts
           n :count
           :or {criteria SAMPLE_CRITERIA
                iterations DEFAULT_SAMPLE_ITERATIONS
                threshold DEFAULT_SAMPLE_THRESHOLD}}]
  (if (zero? (long n))
    {:samples []
     :scores {}
     :final-score 0.0
     :converged? true
     :iterations-count 0
     :duration-ms 0.0}

    (let [{:keys [model]} (resolve-opts router opts)

          ;; Build items spec wrapping user's spec
          item-spec (assoc spec ::spec/spec-name :Item)
          items-spec (spec/spec
                       {:refs [item-spec]}
                       (spec/field ::spec/name :items
                         ::spec/type :spec.type/ref
                         ::spec/cardinality :spec.cardinality/many
                         ::spec/description "Array of generated samples"
                         ::spec/target :Item))

          ;; Build generation messages
          count-instruction (str "Generate exactly " n " sample items. "
                              "Each item should be unique and realistic. "
                              "Ensure diversity across all samples.")
          generation-messages (if messages
                                (conj (vec messages) (user count-instruction))
                                [(system (str "You are a test data generator. Generate realistic, diverse "
                                           "samples that match the provided specification. "
                                           "Ensure variety and quality."))
                                 (user count-instruction)])

          ;; Generation + self-correction loop
          [{:keys [samples scores final-score converged? iterations-count]} total-duration-ms]
          (util/with-elapsed
            (loop [iter 0
                   current-messages generation-messages
                   best-samples nil
                   best-score 0.0]
              (let [{:keys [result]} (ask!* router {:spec items-spec
                                                    :messages current-messages
                                                    :model model})
                    current-samples (vec (:items result))

                    ;; Evaluate generated samples
                    evaluation (eval!* router {:messages generation-messages
                                               :output result
                                               :model model
                                               :criteria criteria})
                    score (double (:overall-score evaluation))
                    better? (> score (double best-score))
                    new-best-samples (if better? current-samples (or best-samples current-samples))
                    new-best-score (if better? score best-score)]

                (if (or (>= score (double threshold))
                      (>= (long iter) (dec (long iterations))))
                  ;; Done — return best result
                  {:samples new-best-samples
                   :scores (:scores evaluation)
                   :final-score new-best-score
                   :converged? (>= score (double threshold))
                   :iterations-count (inc (long iter))}

                  ;; Self-correct: feed back evaluation and regenerate
                  (let [feedback (build-sample-refinement-feedback evaluation n)]
                    (recur (inc (long iter))
                      (conj current-messages
                        (assistant (spec/data->str result))
                        (user feedback))
                      new-best-samples
                      new-best-score))))))]

      {:samples samples
       :scores scores
       :final-score final-score
       :converged? converged?
       :iterations-count iterations-count
       :duration-ms total-duration-ms})))

;; =============================================================================
;; refine! - Routed refinement (ALL calls go through the router)
;; =============================================================================

(defn refine!
  "Routed refine — iterative refinement with provider fallback and rate limiting.
   Accepts `:reasoning :quick|:balanced|:deep` (translated per api-style)."
  [router opts]
  (let [resolved (router/resolve-routing router (routing-opts-with-reasoning opts))]
    (router/with-provider-fallback
      router (:prefs resolved)
      (fn [provider model-map]
        (refine!* router (inject-routed-params opts provider model-map))))))
