(ns com.blockether.svar.internal.usage
  "Canonical token-usage shape — single source of truth across providers.

   Phase A of svar 0.6.0. Replaces the hybrid pre-0.6 shape that emitted
   `:prompt_tokens` with provider-dependent semantics (Anthropic
   additive, OpenAI inclusive) under the same key. Every provider
   normalizer now produces the SAME shape; downstream consumers read
   one set of keys regardless of which model served the call.

   Canonical shape — INVARIANT: `regular + cache-write + cache-read = input-tokens`:

     {:input-tokens          <long>   ;; TOTAL prompt tokens (always inclusive)
      :output-tokens         <long>   ;; TOTAL completion tokens
      :input-tokens-details  {:regular     <long>   ;; not from cache, not written
                              :cache-write <long>   ;; written this request (1.25× input rate, anthropic; 0 else)
                              :cache-read  <long>}  ;; served from cache (0.1× input rate, anthropic; ~10-50% off, openai)
      :output-tokens-details {:reasoning   <long>}  ;; subset of output-tokens
      :total-tokens          <long>   ;; convenience = input-tokens + output-tokens
      :raw                   <map>}   ;; original provider envelope (debug / forensics)

   Provider differences:

   - Anthropic Messages API (`:anthropic` api-style): RAW
     `input_tokens` excludes cached AND cache-creation. Canonical
     `:input-tokens` adds all three so the value is TOTAL.

   - OpenAI Chat / Responses (`:openai-compatible-*` api-styles): RAW
     `prompt_tokens` / `input_tokens` IS the total. Cached subset lives
     under `prompt_tokens_details.cached_tokens` /
     `input_tokens_details.cached_tokens`. No native `cache-write`
     concept (server-managed implicit caching), so `:cache-write` is
     always 0 here UNLESS the provider proxies Anthropic via OpenRouter
     and surfaces `cache_creation_input_tokens` as a pydantic extra
     field.

   - Z.ai (GLM coding-plan / OpenAI-compatible-chat): same as OpenAI.

   Industry alignment (May 2026):

   - Vercel AI SDK V3 spec (vercel/ai#9921): `inputTokens` always TOTAL
     with `inputTokensDetails {regular, cacheWrite, cacheRead}`.
   - OpenTelemetry `gen_ai.usage.input_tokens` (≥ v1.37): SHOULD be
     inclusive (all kinds of input tokens).
   - Claude Code official statusline JSON:
     `context_window.total_input_tokens = input + cache_creation + cache_read`.

   Reject: the additive convention (e.g. litellm PR #23342) leaves
   `total_tokens` inconsistent with `prompt_tokens` and breaks naive
   aggregation downstream."
  (:require
   [taoensso.trove :as trove]))

;; =============================================================================
;; Canonical shape constructors per provider
;; =============================================================================

(defn ^:private long-or-0 [v]
  (long (or v 0)))

(defn ^:private build-canonical
  "Build the canonical shape from already-split components. Enforces the
   invariant by computing :total-tokens and :input-tokens-details.regular
   when callers omit them.

   When `:regular` is supplied, the invariant is asserted (debug-level
   log when violated; never throws so a quirky provider response cannot
   bring down the LLM call)."
  [{:keys [input-tokens output-tokens raw cache-read cache-write regular reasoning]
    :or {cache-read 0 cache-write 0 reasoning 0}}]
  (let [input-tokens  (long-or-0 input-tokens)
        output-tokens (long-or-0 output-tokens)
        cache-read    (long-or-0 cache-read)
        cache-write   (long-or-0 cache-write)
        reasoning     (long-or-0 reasoning)
        regular       (if (some? regular)
                        (long-or-0 regular)
                        ;; Compute when omitted. Anthropic feeds us split
                        ;; values directly so we calculate input-tokens
                        ;; from regular + cache; OpenAI feeds us total +
                        ;; subset so we calculate regular from total -
                        ;; cache.
                        (max 0 (- input-tokens cache-read cache-write)))
        ;; Invariant check — log but don't throw. The cost calculator
        ;; will still produce sane output even if a provider returns
        ;; weird data; user-facing surfaces just see a 0/0/0 fallback.
        computed-total (+ regular cache-write cache-read)]
    (when (and (pos? input-tokens) (not= computed-total input-tokens))
      (trove/log!
        {:level :debug
         :id ::invariant-violation
         :data {:provider-input-tokens input-tokens
                :computed-sum          computed-total
                :regular               regular
                :cache-write           cache-write
                :cache-read            cache-read}}))
    (cond-> {:input-tokens         input-tokens
             :output-tokens        output-tokens
             :input-tokens-details {:regular     regular
                                    :cache-write cache-write
                                    :cache-read  cache-read}
             :total-tokens         (+ input-tokens output-tokens)}
      (pos? reasoning) (assoc :output-tokens-details {:reasoning reasoning})
      raw              (assoc :raw raw))))

(defn anthropic-canonical
  "Anthropic Messages API → canonical shape.

   Anthropic is ADDITIVE: `input_tokens` excludes cached AND
   cache_creation. To get a TOTAL we sum all three.

   `usage` is the raw `:usage` object from an Anthropic response
   (`message_start.message.usage`, top-level `:usage` on non-stream,
   or a `message_delta.usage` delta). Missing fields are treated as 0.

   Returns nil for nil input."
  [usage]
  (when usage
    (let [input-uncached (long-or-0 (:input_tokens usage))
          cache-read     (long-or-0 (:cache_read_input_tokens usage))
          cache-write    (long-or-0 (:cache_creation_input_tokens usage))]
      (build-canonical
        {:input-tokens  (+ input-uncached cache-write cache-read)
         :regular       input-uncached
         :cache-write   cache-write
         :cache-read    cache-read
         :output-tokens (:output_tokens usage)
         :raw           usage}))))

(defn openai-canonical
  "OpenAI Chat / Responses API → canonical shape.

   OpenAI is INCLUSIVE: `prompt_tokens` / `input_tokens` IS the total.
   Cached subset lives under `prompt_tokens_details.cached_tokens` or
   `input_tokens_details.cached_tokens`. OpenRouter-proxied Anthropic
   may surface `cache_creation_input_tokens` as a pydantic-extra; we
   subtract both from the total to compute `regular`.

   Accepts EITHER `:prompt_tokens` (Chat API) or `:input_tokens`
   (Responses API). Cache subkey is `:prompt_tokens_details` /
   `:input_tokens_details`; we read whichever is present.

   Returns nil for nil input."
  [usage]
  (when usage
    (let [in-tot         (long-or-0 (or (:prompt_tokens usage)
                                      (:input_tokens usage)))
          out-tot        (long-or-0 (or (:completion_tokens usage)
                                      (:output_tokens usage)))
          details        (or (:prompt_tokens_details usage)
                           (:input_tokens_details usage))
          cache-read     (long-or-0 (:cached_tokens details))
          ;; OpenRouter → Anthropic surfaces cache_creation_input_tokens
          ;; as a pydantic-extra. Standard OpenAI Chat / Responses does
          ;; not have a cache-write field (server-managed). 0 default
          ;; covers both.
          cache-write    (long-or-0 (or (:cache_creation_tokens details)
                                      (:cache_creation_input_tokens usage)
                                      (:cache_write_tokens details)))
          out-details    (or (:completion_tokens_details usage)
                           (:output_tokens_details usage))
          reasoning      (long-or-0 (:reasoning_tokens out-details))]
      (build-canonical
        {:input-tokens  in-tot
         :cache-write   cache-write
         :cache-read    cache-read
         :output-tokens out-tot
         :reasoning     reasoning
         :raw           usage}))))

;; =============================================================================
;; Caller-facing :tokens projection
;; =============================================================================

(defn canonical->tokens
  "Project the canonical shape to the FLAT `:tokens` map returned by
   `ask!` / `ask-code!`. Backward-compatible field names where
   possible:

     {:input          <input-tokens, TOTAL>
      :output         <output-tokens>
      :reasoning      <output-tokens-details.reasoning>
      :total          <total-tokens>
      :cached         <input-tokens-details.cache-read>
      :cache-created  <input-tokens-details.cache-write>
      :input-regular  <input-tokens-details.regular>}

   This is the shape downstream consumers (vis loop, vis TUI footer,
   CLI bracket, Telegram tagline) read. `:input` now ALWAYS means
   TOTAL prompt tokens (not anthropic's pre-cache RAW)."
  [canonical]
  (when canonical
    (let [details (:input-tokens-details canonical)]
      {:input         (:input-tokens canonical)
       :output        (:output-tokens canonical)
       :reasoning     (long-or-0 (get-in canonical [:output-tokens-details :reasoning]))
       :total         (:total-tokens canonical)
       :cached        (long-or-0 (:cache-read details))
       :cache-created (long-or-0 (:cache-write details))
       :input-regular (long-or-0 (:regular details))})))
