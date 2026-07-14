(ns com.blockether.svar.internal.ratelimit
  "Provider rate-limit header parsing — single source of truth for the
   `:rate-limit` map surfaced on `ask!` / `ask-code!` results.

   Providers send their quota-reset clock on EVERY response (success or
   429) via headers; svar used to throw those headers away, so status
   renderers (vis footer, CLI) had no reset date to show. This ns parses
   the per-provider header families into ONE canonical shape:

     {:reset-at  <epoch-ms>          ;; soonest effective reset across windows
      :remaining <long>              ;; requests remaining in the primary window
      :limit     <long>              ;; primary-window limit
      :windows   {:requests {:reset-at :remaining :limit}
                  :tokens   {:reset-at :remaining :limit}
                  :unified  {:reset-at :remaining :status}}
      :raw       {<header> <value>}} ;; the rate-limit headers, verbatim

   `:reset-at` is the caller-facing 'effective date of reset' (epoch
   millis, UTC). Two provider dialects:

   - Anthropic (`:anthropic` — Claude API + coding-plan OAuth): reset
     values are ABSOLUTE — either unix epoch seconds
     (`anthropic-ratelimit-unified-reset`) or RFC-3339 timestamps
     (`anthropic-ratelimit-{requests,tokens}-reset`). The coding plan's
     5h/weekly window rides on the `-unified-` family.

   - OpenAI / Codex (`:openai-compatible-*`): reset values are RELATIVE
     Go-style durations (`x-ratelimit-reset-requests` = \"6m0s\",
     \"1s\", \"100ms\", \"1h2m3s\"); reset-at = now + duration.

   Returns nil when no rate-limit headers are present (never throws — a
   quirky header must not break the LLM call)."
  (:require
   [clojure.string :as str])
  (:import
   (java.time Instant)))

(defn- header
  "Case-insensitive header lookup. babashka.http-client lowercases keys,
   but be defensive across transports."
  [headers k]
  (when (map? headers)
    (or (get headers k)
      (get headers (str/lower-case k))
      (some (fn [[hk hv]]
              (when (and (string? hk) (.equalsIgnoreCase ^String hk k)) hv))
        headers))))

(defn- ->long
  "Parse a bare integer header value; nil on garbage."
  [s]
  (when (and (string? s) (re-matches #"\s*-?\d+\s*" s))
    (try (Long/parseLong (str/trim s)) (catch Exception _ nil))))

(defn- parse-absolute-ms
  "Anthropic reset: unix epoch seconds (or millis), or an RFC-3339
   instant. Returns epoch-ms or nil."
  [s]
  (when (string? s)
    (let [t (str/trim s)]
      (or
        ;; numeric → epoch seconds (10 digits) or millis (13 digits)
        (when-let [n (->long t)]
          (let [n (long n)]
            (if (>= n 1000000000000) n (* n 1000))))
        ;; RFC-3339 / ISO-8601 instant
        (try (.toEpochMilli (Instant/parse t)) (catch Exception _ nil))))))

(def ^:private duration-unit-ms
  {"ms" 1 "s" 1000 "m" 60000 "h" 3600000 "d" 86400000})

(defn- parse-duration-ms
  "OpenAI reset: Go-style duration string → milliseconds.
   \"1s\" → 1000, \"6m0s\" → 360000, \"100ms\" → 100, \"1h30m\" → 5400000,
   \"1.5s\" → 1500. Returns nil on garbage."
  [s]
  (when (string? s)
    (let [t (str/trim s)]
      (when (seq t)
        (let [pairs (re-seq #"(?i)(\d+(?:\.\d+)?)(ms|s|m|h|d)" t)]
          (when (seq pairs)
            (long (reduce (fn [^double acc [_ num unit]]
                            (+ acc (* (Double/parseDouble num)
                                     (double (get duration-unit-ms (str/lower-case unit) 0)))))
                    0.0 pairs))))))))

(defn- soonest
  "Smallest non-nil epoch-ms among the args, or nil."
  [& ms]
  (let [xs (filter some? ms)]
    (when (seq xs) (apply min xs))))

(defn- anthropic
  [headers _now-ms]
  (let [u-reset  (parse-absolute-ms (header headers "anthropic-ratelimit-unified-reset"))
        u-remain (->long (header headers "anthropic-ratelimit-unified-remaining"))
        u-status (header headers "anthropic-ratelimit-unified-status")
        r-reset  (parse-absolute-ms (header headers "anthropic-ratelimit-requests-reset"))
        r-remain (->long (header headers "anthropic-ratelimit-requests-remaining"))
        r-limit  (->long (header headers "anthropic-ratelimit-requests-limit"))
        t-reset  (parse-absolute-ms (header headers "anthropic-ratelimit-tokens-reset"))
        t-remain (->long (header headers "anthropic-ratelimit-tokens-remaining"))
        t-limit  (->long (header headers "anthropic-ratelimit-tokens-limit"))
        windows  (cond-> {}
                   (or u-reset u-remain u-status)
                   (assoc :unified (cond-> {}
                                     u-reset  (assoc :reset-at u-reset)
                                     u-remain (assoc :remaining u-remain)
                                     u-status (assoc :status u-status)))
                   (or r-reset r-remain r-limit)
                   (assoc :requests (cond-> {}
                                      r-reset  (assoc :reset-at r-reset)
                                      r-remain (assoc :remaining r-remain)
                                      r-limit  (assoc :limit r-limit)))
                   (or t-reset t-remain t-limit)
                   (assoc :tokens (cond-> {}
                                    t-reset  (assoc :reset-at t-reset)
                                    t-remain (assoc :remaining t-remain)
                                    t-limit  (assoc :limit t-limit))))]
    (when (seq windows)
      (cond-> {:windows windows}
        (soonest u-reset r-reset t-reset) (assoc :reset-at (soonest u-reset r-reset t-reset))
        (or u-remain r-remain)            (assoc :remaining (or u-remain r-remain))
        r-limit                           (assoc :limit r-limit)))))

(defn- openai
  [headers now-ms]
  (let [r-reset-ms (parse-duration-ms (header headers "x-ratelimit-reset-requests"))
        r-remain   (->long (header headers "x-ratelimit-remaining-requests"))
        r-limit    (->long (header headers "x-ratelimit-limit-requests"))
        t-reset-ms (parse-duration-ms (header headers "x-ratelimit-reset-tokens"))
        t-remain   (->long (header headers "x-ratelimit-remaining-tokens"))
        t-limit    (->long (header headers "x-ratelimit-limit-tokens"))
        r-reset    (when r-reset-ms (+ (long now-ms) (long r-reset-ms)))
        t-reset    (when t-reset-ms (+ (long now-ms) (long t-reset-ms)))
        windows    (cond-> {}
                     (or r-reset r-remain r-limit)
                     (assoc :requests (cond-> {}
                                        r-reset  (assoc :reset-at r-reset)
                                        r-remain (assoc :remaining r-remain)
                                        r-limit  (assoc :limit r-limit)))
                     (or t-reset t-remain t-limit)
                     (assoc :tokens (cond-> {}
                                      t-reset  (assoc :reset-at t-reset)
                                      t-remain (assoc :remaining t-remain)
                                      t-limit  (assoc :limit t-limit))))]
    (when (seq windows)
      (cond-> {:windows windows}
        (soonest r-reset t-reset) (assoc :reset-at (soonest r-reset t-reset))
        r-remain                  (assoc :remaining r-remain)
        r-limit                   (assoc :limit r-limit)))))

(defn- rate-limit-headers
  "The subset of `headers` that look like rate-limit headers, for :raw."
  [headers]
  (when (map? headers)
    (into {}
      (filter (fn [[k _]]
                (and (string? k)
                  (let [lk (str/lower-case k)]
                    (or (str/includes? lk "ratelimit")
                      (str/includes? lk "rate-limit")))))
        headers))))

(defn parse
  "Parse provider rate-limit headers into the canonical `:rate-limit`
   map (see ns docstring). `api-style` selects the header dialect;
   `headers` is the response header map. `now-ms` (optional) anchors
   OpenAI relative durations — defaults to `System/currentTimeMillis`.

   Returns nil when no rate-limit headers are present."
  ([api-style headers]
   (parse api-style headers (System/currentTimeMillis)))
  ([api-style headers now-ms]
   (when (map? headers)
     (let [base (case api-style
                  :anthropic (anthropic headers now-ms)
                  ;; :openai-compatible-chat / -responses / z.ai / codex / copilot
                  (openai headers now-ms))
           raw  (rate-limit-headers headers)]
       (when base
         (cond-> base
           (seq raw) (assoc :raw raw)))))))
