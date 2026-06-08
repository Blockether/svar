# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [v0.7.9] - 2026-06-08

### Changed
- fix(llm): reassemble multi-part assistant message body verbatim
- release: update version files for v0.7.8, bump to next dev version


## [v0.7.8] - 2026-06-07

### Changed
- fix(llm): wrap connect-phase failures with a provider-aware message
- chore(ci): bump GitHub Actions to Node 24 runtimes
- release: update version files for v0.7.7, bump to next dev version


## [v0.7.7] - 2026-06-07

### Changed
- feat(ask-code): add :lenient mode — whole reply IS the code, no fence required
- release: update version files for v0.7.6, bump to next dev version


## [v0.7.6] - 2026-06-06

### Changed
- fix(llm): retry transient OS/network connection errors
- release: update version files for v0.7.5, bump to next dev version


## [v0.7.5] - 2026-06-06

### Changed
- fix(llm): pin streaming POST to the HTTP/1.1 shared client
- release: update version files for v0.7.4, bump to next dev version


## [v0.7.4] - 2026-06-06

### Changed
- fix(router): reasoning is a hint for explicit selection, not a hard gate
- release: update version files for v0.7.3, bump to next dev version


## [v0.7.3] - 2026-06-06

### Changed
- fix(router): route detected :context into the pre-flight context-overflow check
- release: update version files for v0.7.2, bump to next dev version


## [v0.7.2] - 2026-06-06

### Changed
- feat(router): LM Studio context auto-detection via native /api/v0/models
- release: update version files for v0.7.1, bump to next dev version


## [v0.7.1] - 2026-06-03

### Changed
- fix(graal): remove boxed math warnings
- fix(anthropic): drop empty text content blocks in wire serializer
- feat(router): :prefer-providers — declarative ordered provider preference
- feat(router): per-model 400 model_unsupported fallback + provider-pinned optimize
- Exclude unsupported Grok Copilot model
- docs: skip deps.edn block in README doctest; show correct unquoted coordinate
- docs: clarify deps.edn quote is a README-doctest artifact, not for real deps.edn
- release: update version files for v0.7.0, bump to next dev version


## [v0.7.0] - 2026-05-29

### Changed
- refactor!: structured-core only — remove agent-shaped pipelines (BREAKING)
- Fix four verified critical defects found in full review
- Fix GLM empty-content flake; o200k fallback + Anthropic exact pre-flight counts
- Add Claude Opus 4.8 model metadata
- Avoid retry after streamed reasoning
- Retry transient streaming provider failures
- release: update version files for v0.6.1, bump to next dev version


## [v0.6.1] - 2026-05-26

### Changed
- release: v0.6.1 — republish from main (v0.6.0 jar was built from stale main)
- Phase A — canonical usage shape: INCLUSIVE :input-tokens + details split (BREAKING)
- Phase 0 — auto-generate :cache-key for OpenAI-style when caller omits (S7)
- Phase 0 — auto-cache by default + fix 6 cache-related bugs (BREAKING)
- release: update version files for v0.6.0, bump to next dev version
- release: update version files for v0.6.0, bump to next dev version


## [v0.6.1] - 2026-05-26

### Fixed — v0.6.0 jar was published from stale main

No functional changes vs the v0.6.0 tag content. The CI release
workflow uploaded a jar built from the pre-Phase-0 main branch
(`resources/VERSION 0.5.11`), so `svar-0.6.0.jar` on Clojars does
NOT contain the Phase 0 / Phase A code described in the v0.6.0
changelog entry below. v0.6.1 republishes from main now that the
cherry-picked Phase 0 + Phase A commits actually landed on main.

## [v0.6.0] - 2026-05-26

### Changed
- release: v0.6.0 — canonical usage shape + auto-cache by default (BREAKING)
- Phase A — canonical usage shape: INCLUSIVE :input-tokens + details split (BREAKING)
- Phase 0 — auto-generate :cache-key for OpenAI-style when caller omits (S7)
- Phase 0 — auto-cache by default + fix 6 cache-related bugs (BREAKING)
- release: update version files for v0.5.10, bump to next dev version


## [v0.6.0] - 2026-05-26

### Changed
- release: v0.6.0 — canonical usage shape + auto-cache by default (BREAKING)
- Phase A — canonical usage shape: INCLUSIVE :input-tokens + details split (BREAKING)
- Phase 0 — auto-generate :cache-key for OpenAI-style when caller omits (S7)
- Phase 0 — auto-cache by default + fix 6 cache-related bugs (BREAKING)


## [v0.5.10] - 2026-05-26

### Changed
- release: v0.5.10 — fence parser tolerates trailing junk on bare closers
- fence parser: bare closer with trailing junk closes the block
- release: v0.5.9 — http-error message never nil/blank
- llm: never throw http-error with nil message
- release: v0.5.8 — fence parser nests inner lang-tagged samples
- fence parser: nest inner lang-tagged samples inside open block
- release: update version files for v0.5.7, bump to next dev version


## [v0.5.10] - 2026-05-26

### Fixed
- fence parser: bare closer with trailing junk on the same line now
  closes the block. Tagged openers stay strict. Fixes Vis session
  b94052f0 where a model emitted `` ```        -   -       `` after a
  clean closer; the line slipped past the classifier, leaked into the
  body, then edamame read the bare ` ``` ` as three nested syntax-quote
  reader macros wrapping the trailing junk → ~1 KB
  `(clojure.core/sequence (clojure.core/seq …))` macroexpansion that
  froze the Vis TUI for ~870 ms per zprint pass.

## [v0.5.9] - 2026-05-23

### Fixed
- llm: `http-error-message` helper guarantees the `:svar.core/http-error`
  ex-info's message is never nil/blank. babashka's HttpClient can
  surface low-level exceptions (stream finalisation, connection edge)
  with nil message; svar used to pipe that straight into
  `anomaly/fault!`, producing `ExceptionInfo: null` traces with
  `:com.blockether.anomaly.core/message nil` and no actionable signal
  for downstream consumers (Vis conv c8dc39b1).

## [v0.5.8] - 2026-05-23

### Fixed
- fence parser: nest inner lang-tagged samples inside open block. Fixes
  Vis conv 11d4f817 / t12/i1 where a `(done {:answer "…```clojure
  (deftest …) ```…"})` form was torn by the inner sample's bare
  ` ``` ` closing the outer `` ```clojure `` fence early. Outer block
  now closes only when nesting depth returns to zero.

## [v0.5.7] - 2026-05-23

### Changed
- router: fallback on empty stream truncation
- build-request-body: SVAR_DISABLE_REASONING_ECHO escape hatch + drop ineffective last-N knob
- router + llm: catalog-key resolver, server-managed reasoning, max-tokens-exceeded, fallback fixes, stream trace
- release: update version files for v0.5.6, bump to next dev version


## [v0.5.6] - 2026-05-20

### Changed
- fix(ask-code): require exactly one fenced code block
- release: update version files for v0.5.5, bump to next dev version


## [v0.5.5] - 2026-05-20

### Changed
- chore: release v0.5.5
- feat(ask-code!): surface :all-blocks/:saw-fence?/:malformed?; minimise tail pointer
- release: update version files for v0.5.4, bump to next dev version


## [v0.5.5] - 2026-05-19

### Added
- `ask-code!` / `ask-code!*` return maps and `:done? true` chunks now
  carry three extra observation keys alongside the existing
  `:blocks`:
  - `:all-blocks`  — the pre-`select-blocks` vec (every fence
    extracted, regardless of lang); callers diagnose wrong-lang or
    untagged drops via `(> (count :all-blocks) (count :blocks))`.
  - `:saw-fence?` — boolean; true when the raw response contained at
    least one fence-shaped line. Lets callers distinguish the
    fenceless-fallback path (`:saw-fence? false` plus one `:lang
    nil` block) from a clean fenced response.
  - `:malformed?` — boolean; true when the fence parser flagged a
    torn boundary (glued close+open or unclosed terminal fence). Use
    this to attach a more specific recovery hint than "parse failed".
- `codes/extract-code-blocks-detail` — new internal helper that
  returns the full parser observation `{:blocks :saw-fence?
  :malformed?}`. The public `codes/extract-code-blocks` keeps its
  bare-vec contract by delegating.

### Changed
- `code-tail-pointer-text` shrunk from a 4-bullet `Rules:` block to a
  single-line directive: `"Reply with \`\`\`lang … \`\`\` fenced blocks;
  untagged or other-lang fences are DROPPED."`. The retired bullets
  (opener/closer on own line, blank line between blocks, no prose,
  no glued boundaries) were either over-prescription or already
  handled by the FenceNormalizer. Saves ~40 tokens per `ask-code!`
  call without weakening the strict-lang contract; the warning that
  untagged or wrong-lang fences are silently dropped stays explicit.

## [v0.5.4] - 2026-05-19

### Changed
- chore: release v0.5.4
- feat(router): bump :fallback-after-ms default 30s -> 60s; drop 2 flaky live tests
- feat(router): hard-cap fallback-after-ms + on-chunk passthrough + spec-aligned :routed/trace
- feat: harden SSE stream parsing
- svar: TTFT 10s->30s, idle 10s->45s
- svar: drop virtual threads; TTFT/idle default to 10s
- feat(llm): TTFT watchdog + unified streaming-timeout opts across public API
- fix(llm): raise DEFAULT_IDLE_TIMEOUT_MS 60s -> 120s
- feat(llm): idle-stream watchdog for streaming HTTP responses
- release: update version files for v0.5.3, bump to next dev version


## [v0.5.4] - 2026-05-19

### Changed
- `DEFAULT_RATE_LIMIT_ROUTING :fallback-after-ms` default bumped from
  30 000 ms to **60 000 ms**. Anthropic, OpenAI, and z.ai routinely emit
  `Retry-After` headers in the 30-60 s range under quota pressure on
  reasoning-heavy workloads; a 30 s budget clamped these to ~30 s and
  forced cross-provider fallback when the same provider was about to
  clear. 60 s lets the same-provider retry schedule complete in most
  real-world quota windows while still bounding the wait so a single
  user request cannot hang for minutes. Callers that need the prior
  behavior set `{:router {:rate-limit {:fallback-after-ms 30000}}}`
  explicitly.

### Added
- `:router :rate-limit` policy (`:same-provider-delays-ms`,
  `:fallback-after-ms`, `:respect-retry-after?`, `:fallback-provider?`)
  is now a hard cap on the same-provider 429 phase, not a wait floor.
  Each configured delay clamps to remaining budget so the loop never
  overshoots; once the schedule is exhausted OR `elapsed ≥ budget`,
  the router falls back immediately. The previous "pad to boundary"
  reading would have stalled requests deliberately past the budget
  and is gone.
- `:llm.routing/provider-retry` events now carry `:elapsed-ms` and
  `:error` alongside `:attempt` / `:delay-ms`; `:llm.routing/provider-fallback`
  events carry `:elapsed-ms` measured from the first 429 of the
  same-provider phase. Persistence + TUI consumers can render the
  wait reason without re-deriving from `:at-ms` diffs.
- `:on-chunk` is threaded from caller opts into `resolve-routing` prefs
  so routing events fire live alongside streaming content for every
  routed entrypoint (`ask!`, `ask-code!`, `abstract!`, `eval!`,
  `refine!`, `sample!`, `routed-chat-completion`). Previously they
  landed only in the final `:routed/trace` and the TUI saw nothing
  during multi-second 429 retry sleeps.

### Removed
- `core-test/abstract!-integration-test` (entire `defdescribe`, 8 cases
  across 4 `describe` blocks) and `router-zai-live-test/":deep +
  preserved succeeds — clear_thinking:false accepted"` removed. Every
  case asserted on the exact content shape returned by live LLM calls
  via the Blockether LiteLLM proxy (gpt-4o) or z.ai (glm-4.7); under
  load the proxy intermittently truncates JSON, returns HTTP 500 from
  a downstream Copilot auth hiccup, or emits empty content. Each
  flake reproduced in CI was an upstream infra problem, never an svar
  regression. `abstract!-baseline-test` and `router-zai-live-test`'s
  `:quick + preserved` variant cover the same code paths without the
  flake; once we have a deterministic recording fixture for Blockether
  One we can put the integration coverage back behind it.

### Added
- Time-to-first-token watchdog as a sibling of the idle-stream watchdog.
  New `:ttft-timeout-ms` option on `chat-completion` / `ask!` / `ask-code!`
  / `abstract!` / `eval!` / `refine!` / `sample!` / `routed-chat-completion`
  bounds the pre-headers phase (before `http/post` returns and the body
  `InputStream` becomes available). On fire it interrupts the calling
  thread inside `HttpClient.send -> CompletableFuture.get` and surfaces
  typed `:svar.core/stream-ttft-timeout`. Default 90 s
  (`router/DEFAULT_TTFT_TIMEOUT_MS`), matching Anthropic SDK PR #959.

  Two distinct phases, two distinct watchdogs:
  - TTFT covers "upstream accepts TCP+TLS, never sends response headers"
    — the iter-7 reproduction class. Idle watchdog cannot reach this
    phase because it needs the body stream to operate on.
  - Idle covers "headers received, then stream wedges mid-flight".

  Both keys flow through the same precedence chain (caller `opts` >
  router `:network` > package default) thanks to the new `LLM_PASSTHROUGH_KEYS`
  membership; passing an explicit `nil` per call disables each
  independently. `routed-chat-completion` now reads router defaults via
  the same helper, so direct callers get unified handling without
  re-implementing the precedence chain.

### Changed
- Watchdog tick-resolution capped at 5 s. Previously
  `start-idle-stream-watchdog!` used `(quot idle-timeout-ms 4)` as the
  per-tick sleep, which meant a 120 s default idle timeout parked the
  daemon thread for 30 s between checks — callers waiting on shutdown
  could sit for up to 30 s after the read loop ended. Clamped to
  `[100, 5000]` ms so caller-side shutdown latency is at most 5 s while
  the firing precision still tracks the configured deadline closely.
  `start-ttft-watchdog!` converted from a single `Thread/sleep
  ttft-timeout-ms` to a poll loop with the same clamp, eliminating a
  long-lived thread parked past caller completion.

- Idle-stream watchdog for streaming HTTP responses. New
  `:idle-timeout-ms` option on `chat-completion` / `ask!` / `ask-code!`
  closes the SSE `InputStream` if no bytes arrive within the window and
  surfaces a typed `:svar.core/stream-idle-timeout` ex-info. Default is
  `router/DEFAULT_IDLE_TIMEOUT_MS` (120s / 2 min, matching Anthropic's
  own SDK proposal `anthropics/anthropic-sdk-typescript#867` for the
  per-request override). Pass `:idle-timeout-ms nil` to disable; bump
  to 240-300 s for Opus extended-thinking workloads (anthropics/claude-
  agent-sdk-typescript#44 documents ~185 s legitimate silences). Distinct
  from the existing `:timeout-ms` (whole-request cap): the idle watchdog
  tolerates arbitrarily long total durations as long as the stream keeps
  emitting bytes (content deltas, SSE `: ping` keepalives, or blank
  separators — every `.readLine` resets the timer, so the watchdog is
  ping-aware for free). Motivation: on JDK 25 + HTTP/2 streaming bodies
  `HttpRequest.Builder.timeout` doesn't reliably fire when the upstream
  sends headers and then stalls without body frames (real repro: 11-minute
  hang on z.ai glm-5.1 past the 5-min request timeout, never raised).
  The watchdog is signal-driven so stalls surface in seconds regardless
  of the JDK timer's mood.
- `::stream-started` and `::stream-headers` trove logs around the
  streaming HTTP boundary, paired with the existing `::stream-finalized`.
  Closes the observability gap that made mid-call stalls invisible
  (previously only `:stop`-shaped events were emitted).

## [v0.5.3] - 2026-05-13

### Changed
- release v0.5.3 \u2014 strict :lang on ask-code!, blocks-only return, Markdown-code-blocks prompt
- strict :lang on ask-code! + drop :result + Markdown-code-blocks prompt
- release: update version files for v0.5.2, bump to next dev version


## [v0.5.3] - 2026-05-13

### Changed (BREAKING)
- `ask-code!` `:lang` is now REQUIRED. The previous `"clojure"` default is
  gone; callers must pass an explicit non-blank string. Throws
  `:svar.core/invalid-lang` otherwise.
- `select-blocks` now drops `:lang nil` (untagged) blocks unconditionally
  instead of treating them as a wildcard match. Models MUST tag their
  Markdown code block with the requested lang. The lenient+ fenceless-
  fallback path in `extract-code-blocks` still produces a `:lang nil`
  block, but it no longer survives `select-blocks`.
- `ask-code!` return map no longer contains `:result` (the concatenated
  source string). `:blocks` is the single source of truth; callers that
  want a concatenated string call `codes/concat-sources` themselves. The
  streaming `on-chunk` payload also drops `:result`.
- `code-tail-pointer-text` rewritten as a compact 5-line Rules: list.
  Now spells out the strict-lang contract ("Untagged or other-lang blocks
  are DROPPED") so models learn the rule in-context instead of being
  punished invisibly. Says "Markdown code blocks" instead of "fences"
  for precision. ~280 chars vs the previous ~480.

## [v0.5.2] - 2026-05-12

### Changed
- fix: let llm-headers override Copilot initiator
- release: update version files for v0.5.1, bump to next dev version

## [v0.5.1] - 2026-05-12

### Changed
- fix: recover trailing unclosed fenced streams
- release: update version files for v0.5.0, bump to next dev version


## [v0.5.0] - 2026-05-11

### Changed
- release: v0.5.0 — Java fence parsers, kill O(N²) per-chunk re-parse
- perf: kill O(N²) per-chunk re-parse + Java fence parsers
- release: update version files for v0.4.15, bump to next dev version


## [v0.5.0] - 2026-05-11

### Fixed
- **Streaming responses no longer hang on long completions.** `ask-code!*`
  used to invoke `codes/extract-code-blocks` on every SSE delta over the
  *full* accumulated buffer. Combined with `(?m)…$` regex normalizers,
  total work was quadratic in stream size. Live repro on glm-5.1 wedged a
  Vis TUI virtual thread in `java.util.regex.Pattern$BmpCharPropertyGreedy.match`
  for minutes (Vis conversation `0c8188ac`). Streaming `on-chunk` now
  signals progress only (`:raw`, `:reasoning`, `:done?`); the final
  parse happens exactly once after the stream closes and is surfaced in
  the `:done? true` chunk and the return value. Mid-stream `:result` /
  `:blocks` are `nil` — a deliberate contract change.

### Added
- **`com.blockether.svar.FenceNormalizer` (Java).** Single linear scan
  over the input. Three fence transforms (opener-split,
  inline-boundary-split, closer-split) folded into one fused per-line
  emit. Fast paths for inputs without carriage returns and without
  backticks. Replaces the prior `(?m)…` regex pipeline in
  `codes/normalize-*`.
- **`com.blockether.svar.FenceBlocksParser` (Java).** Line-based fence
  block parser. Body materialised with one `String.substring` per block
  (bodies are contiguous slices of the normalized input). Replaces the
  Clojure `parse-fenced-blocks` loop.
- **Bench suite under `bench/`** with `:bench` alias (`criterium`
  `0.4.6`). Reproduces the Vis hang scenario and tracks throughput of
  the new Java parsers.

### Performance
- `extract-code-blocks` end-to-end on a 0.87 MB / 12 000-line fenced
  response: **2.80 ms** (~310 MB/s). Pre-fix: minutes of CPU.
- `FenceNormalizer/normalize`: 1.02 ms on the same input.
- `FenceBlocksParser/parse`: 1.54 ms on the same input.
- `pathological-line` (78 KB single line with stray backticks +
  glued closer): 166 µs.

### Changed
- `codes/normalize-fence-openers`, `normalize-fence-closers`,
  `normalize-inline-fence-boundaries`, `parse-fenced-blocks`,
  `malformed-fence-fragment?` and the `FENCE_LINE_RE` constant are
  gone. Their behaviour now lives in the Java parsers behind the
  existing public `codes/extract-code-blocks` API.

## [v0.4.15] - 2026-05-10

### Changed
- release: v0.4.15 — models.dev catalog integration
- release: update version files for v0.4.14, bump to next dev version


## [v0.4.15] - 2026-05-10

### Added
- **models.dev catalog integration.** Bundled snapshot at
  `resources/models.dev.json` (1.9 MB, 118 providers) drives pricing,
  context, modalities, cache-read/write, family, capability flags,
  knowledge cutoff, and release dates for every known provider model.
  Refresh with `make refresh-models`.
- New ns `com.blockether.svar.internal.modelsdev` exposing `catalog`,
  `provider-models`, `provider-meta`, `normalize-model`, `resolve-models`,
  and `merge-overlay`.
- `:pricing-source` overlay key on `KNOWN_PROVIDERS` redirects catalog
  lookup to a different provider id. `:openai-codex` and
  `:anthropic-coding-plan` now meter at **retail** OpenAI / Anthropic
  rates (honest accounting once plan quota is exceeded).
- `:modalities`, `:cache-read`, `:cache-write`, `:input-limit`,
  `:output-limit`, `:family`, `:knowledge-cutoff`, `:release-date`,
  `:tool-call?`, `:attachment?`, `:open-weights?`, `:temperature?`
  surface on every `provider-model-entry` / `(:models provider)`.

### Changed
- `KNOWN_PROVIDER_MODELS` slimmed to wire/policy-only overlays.
  Pricing/context flow from the catalog by default; overlays add only
  what the catalog can't express (Anthropic 5m/1h cache tiers,
  OpenAI long-context tiers, GLM `:json-object-mode?`, Copilot
  per-model `:extra-body` + `:reasoning-style`).
- `provider-model-entry` returns catalog ⊕ overlay merge (overlay wins
  on wire keys, pricing maps deep-merge so overlay rate overrides keep
  catalog `:cache-read` / `:cache-write`).
- `MODEL_CONTEXT_LIMITS` and `MODEL_PRICING` now union catalog + overlay
  via the new private `merged-provider-models`, so legacy
  `tokens/estimate-cost` and `context-limit` see the full catalog.
- `:zai-coding` no longer duplicates `:zai`'s GLM table; inherits via
  new `:provider-model-source :zai` (overlay) +
  `:pricing-source :zai` (retail metering for subscription overage).
- `resources/` is now on `:paths` in `deps.edn` and copied into the jar
  by `build.clj` so the bundled catalog ships with every release.

### Removed
- Built-in `:blockether` provider (`KNOWN_PROVIDERS` entry, model table,
  `BLOCKETHER_LLM_DEFAULT_MODEL` env fallback, README built-in mention).
  Still fully usable as a user-supplied custom provider — callers pass
  `:base-url` and per-model `:pricing` / `:reasoning?` like any other
  custom provider.

## [v0.4.14] - 2026-05-09

### Changed
- anthropic: demote interior thinking blocks on wire to dodge replay 400
- preserve upstream HTTP body on :svar.core/http-error ex-data


## [v0.4.13] - 2026-05-09

### Changed
- feat: platform-agnostic OAuth-aware models! + Codex /codex/models
- release: update version files for v0.4.12, bump to next dev version


## [v0.4.13] - 2026-05-09

### Added
- `models!` is now OAuth-aware and platform-agnostic. The internal `http-get!`
  routes through the same `make-llm-headers` dispatcher chat does, so
  Anthropic OAuth tokens (Claude Code subscription) attach
  `anthropic-version`, `anthropic-beta`, `user-agent`, `x-app`; Anthropic
  API keys attach `x-api-key`; everything else falls back to bearer auth.
  Provider `:llm-headers` (e.g. Codex `chatgpt-account-id`) merge on top.
- Per-provider `:models-path` + `:models-query-params` hooks in
  `KNOWN_PROVIDERS`. `:openai-codex` now points at
  `/codex/models?client_version=1.0.0` to surface the live Codex
  inference fleet (gpt-5.5, gpt-5.4, gpt-5.3-codex, ...). The bare
  `/models` route on the same host returns chatgpt.com product
  metadata, not inference models.
- `normalize-models-response` accepts both OpenAI/Anthropic
  `{:data [...]}` and ChatGPT-backend `{:models [{:slug ...}]}`
  shapes; `:slug` promotes to `:id` so downstream filters work
  unchanged.
- `models!` returns `[]` on HTTP failure by default; pass
  `{:strict? true}` to surface the underlying `ex-info`.

### Fixed
- Anthropic Claude subscription `/v1/models` no longer 400s on
  "anthropic-version: header is required" — the OAuth header set
  flows through the same code path used for `/v1/messages`.

## [v0.4.12] - 2026-05-08

### Changed
- feat: extension + llm improvements
- release: update version files for v0.4.11, bump to next dev version


## [v0.4.11] - 2026-05-08

### Changed
- fix: align Anthropic OAuth and adaptive thinking
- release: update version files for v0.4.10, bump to next dev version


## [v0.4.10] - 2026-05-07

### Changed
- Add Anthropic subscription provider support
- release: update version files for v0.4.9, bump to next dev version


## [v0.4.9] - 2026-05-07

### Changed
- Reuse OpenAI reasoning for Copilot
- release: update version files for v0.4.8, bump to next dev version


## [v0.4.8] - 2026-05-07

### Changed
- Fix Copilot reasoning wire format
- release: update version files for v0.4.7, bump to next dev version


## [v0.4.7] - 2026-05-07

### Changed
- Fix Copilot GPT prompt budgets
- release: update version files for v0.4.6, bump to next dev version


## [v0.4.6] - 2026-05-07

### Changed
- Fix Codex GPT context windows
- release: update version files for v0.4.5, bump to next dev version


## [v0.4.5] - 2026-05-07

### Changed
- Fix model context limits and add GLM-5V Turbo
- release: update version files for v0.4.4, bump to next dev version


## [v0.4.4] - 2026-05-04

### Changed
- fix(github-copilot): normalize Claude routing metadata
- release: update version files for v0.4.3, bump to next dev version


## [v0.4.3] - 2026-05-04

### Changed
- Preserve reasoning summary boundaries
- Harden code fence extraction and pricing metadata
- Keep encrypted Responses reasoning out of visible reasoning
- Force streaming for Copilot business endpoints
- Support Copilot routing and Responses reasoning state
- release: update version files for v0.4.2, bump to next dev version


## [v0.4.2] - 2026-05-03

### Changed
- Surface cached token usage
- Max retries for the COD
- Preserve whitespace-only stream deltas
- Reject truncated SSE streams
- Harden ask-code streaming chunks
- Harden ask-code fence extraction
- fix(codex): stream responses endpoint
- Rename OpenAI api-style keys and document transports
- Document ask-code and verbosity controls
- Add transparent OpenAI Responses routing and verbosity control
- Add generic reasoning fallbacks for output-style responses
- release: update version files for v0.4.1, bump to next dev version


## [v0.4.1] - 2026-04-29

### Changed
- ask-code!: plain-text completion + fenced code-block extraction
- Add schema-tail-pointer
- release: update version files for v0.4.0, bump to next dev version


### Added
- `ask-code!` / `ask-code!*`: plain-text completion + fenced code-block
  extraction. Sibling of `ask!` for callers that want raw source (e.g.
  Clojure for an RLM agent loop) instead of a structured JSON envelope.
  No spec, no schema-prompt inlining, no JSON-mode tricks. Sends
  `:messages` verbatim, parses the assistant response, filters by
  `:lang` (default `"clojure"`), and returns the concatenated source.
  Returns `{:result :blocks :raw :reasoning :tokens :cost :duration-ms}`;
  empty `:result` (no matching blocks) is a valid success — the caller
  decides what to do with it. Throws on transport-level failures only
  (`:svar.llm/empty-content`, HTTP errors).
- `ask-code!` / `ask-code!*`: `:code-tail-pointer?` option (default
  `true`). Mirrors the `ask!*` schema-tail-pointer feature for the
  fenced-code path: appends a short, lang-aware reminder as the LAST
  text block of the LAST user message ("Reply with `lang` source inside
  ```lang … ``` fences. …"). Restores recency-driven format adherence on
  long transcripts without burning a cache breakpoint. Set to `false` to
  opt out (e.g. quirky local models that double-emit on reminders).
- `extract-code-blocks` (re-exported on `svar.core`): pure utility that
  parses fenced code blocks from a raw text string. Returns a vector of
  `{:lang <str-or-nil> :source <str>}`. Lenient+: matches
  ` ```clojure ` / ` ``` ` (untagged) / falls back to treating the entire
  input as one untagged block when no fence is present. Multi-block aware.

## [v0.4.0] - 2026-04-28

### Changed
- release: v0.4.0 — provider-noise hardening (format-retries, json-mode, envelope)
- release: update version files for v0.3.11, bump to next dev version


## [v0.4.0] - 2026-04-28

### Added
- `ask!` / `ask!*`: `:format-retries` option (default `0`). When the
  provider returns content that fails schema parsing
  (`:svar.spec/schema-rejected`, `:svar.spec/required-field-missing`),
  svar can re-prompt the model with a tiny FORMAT-RETRY turn and try
  again locally instead of bubbling the failure to the caller. Each
  attempt is recorded in the result map under `:format-attempts` (only
  surfaced when retries actually happen) and in the terminal exception's
  ex-data. Lets agent loops absorb provider-format noise without burning
  user-visible iteration budget. Configurable via `:format-retry-on`
  (default set: `#{:svar.spec/schema-rejected :svar.spec/required-field-missing}`;
  callers can opt in to retrying `:svar.llm/empty-content` too).
- `ask!` / `ask!*`: `:json-object-mode?` option. When true on `:openai`
  api-style providers, auto-injects `response_format: {type: "json_object"}`
  into the request body. Hardens models that historically leak prose into
  `content` under `:deep` reasoning. Defaults to model metadata: GLM family
  (`glm-5.1`, `glm-4.7`, `glm-5-turbo`, `glm-4.6`, `glm-4.6v`) is opted in
  by default across `:zai`, `:zai-coding`, and `:blockether` providers.
  Caller's explicit `:extra-body :response_format` always wins.
- `ask!`: `:on-format-error` routing strategy. `:fail` (default) preserves
  current behavior; `:fallback-provider` treats schema/format-typed errors
  as transient and tries the next provider/model in the fleet, excluding
  the offender. The terminal exception (when all providers fail) carries
  the LAST format error's full envelope plus `:routed/trace` and
  `:format-failed`.
- Schema-rejection and `:svar.llm/empty-content` exceptions now carry the
  full forensic envelope (`:model`, `:api-style`, `:chat-url`,
  `:duration-ms`, `:api-usage`, `:reasoning`, `:content`, `:http-response`,
  `:format-attempts`) verbatim in ex-data — no truncation. Lets callers
  reproduce / persist / display the failing call without scraping logs.

### Changed
- Schema enforcement banner (`SCHEMA_ENFORCEMENT_BANNER` rendered into
  every spec prompt) now explicitly states the top-level value must be a
  JSON object (not a JSON string) and the first non-whitespace character
  must be `{`. Reduces the GLM prose-leak rate without auto-injection.
- All svar primitives that wrap `ask!*` internally (`abstract!`,
  `abstract!*`, `eval!`, `eval!*`, `refine!`, `refine!*`, `sample!`)
  now propagate the new options (`:format-retries`, `:format-retry-on`,
  `:json-object-mode?`, `:on-format-error`, `:cache-system?`,
  `:extra-body`, `:timeout-ms`, `:check-context?`, `:output-reserve`)
  through every internal LLM call. Previously these were silently
  dropped at the `select-keys` boundary inside helpers — e.g.
  `(svar/abstract! router {:format-retries 2 ...})` would have ignored
  the retry budget on every CoD iteration. Centralised through a new
  private `LLM_PASSTHROUGH_KEYS` constant + `llm-passthrough` helper so
  future svar-level options stay consistent across the public surface.


## [v0.3.11] - 2026-04-27

### Changed
- llm: prompt caching, multi-block content, spec-prompt placement
- verify: add GraalVM safety ratchet, fix existing reflection / boxed-math hits

### Fixed
- spec: count ref usages across the whole spec graph (main spec + every
  spec in the registry) instead of just the main spec's fields.
  Transitively-referenced refs (e.g. `Group.items: item[]` when only
  `:group` is in the main spec's `:refs`) used to be partitioned as
  "unused" and dropped from the rendered prompt; the LLM saw
  `items: item[]` with no `item { … }` definition and degraded into
  positional arrays. Adds a regression test under
  `spec->prompt-test → transitive ref usage`.

## [v0.3.10] - 2026-04-26

### Changed
- fix: remove <objective> wrapping from system messages
- release: update version files for v0.3.9, bump to next dev version

## [v0.3.9] - 2026-04-25

### Changed
- fix: update streaming tests for reasoning-first chunks
- fix: stream reasoning chunks before content arrives
- release: update version files for v0.3.8, bump to next dev version


## [v0.3.8] - 2026-04-22

### Changed
- chore(release): rename SVAR_VERSION to VERSION and bump to 0.3.8
- refactor(router): replace resolve-root-model with resolve-effective-model
- release: update version files for v0.3.6, bump to next dev version


## [v0.3.6] - 2026-04-20

### Changed
- fix: remove per-field strict rejection, fix schema messages, auto-wrap bare vectors
- release: update version files for v0.3.5, bump to next dev version


## [v0.3.5] - 2026-04-20

### Changed
- feat: full EDN/Clojure fence support + deeply nested keyword parsing
- release: update version files for v0.3.4, bump to next dev version


## [v0.3.4] - 2026-04-20

### Changed
- feat: expose raw HTTP response in :svar.llm/empty-content ex-data
- release: update version files for v0.3.3, bump to next dev version


## [v0.3.3] - 2026-04-20

### Changed
- fix: surface empty-content from providers as :svar.llm/empty-content
- release: update version files for v0.3.2, bump to next dev version


## [v0.3.2] - 2026-04-19

### Changed
- feat: ::values vector shorthand for values-only enums
- release: update version files for v0.3.1, bump to next dev version


## [v0.3.2] - 2026-04-19

### Added
- **`::values` vector shorthand** — enum fields can now declare values as a
  plain vector `[\"high\" \"medium\" \"low\"]` instead of a `{value desc}`
  map. `spec->prompt` emits the inline `\"a\" or \"b\"` type union but
  skips the per-value comment block — meaningful savings on system
  prompts that declare self-explanatory enums (`confidence`, `model
  class`, `answer-type`, etc.). The validator treats both shapes
  identically. Back-compatible: existing `{value desc}` maps keep
  emitting per-value docs unchanged.

## [v0.3.1] - 2026-04-19

### Changed
- test: expand live :zai-coding reasoning coverage (quick/balanced/deep + preserved)
- feat: abstract reasoning-depth API + per-style translation
- release: update version files for v0.3.0, bump to next dev version

## [v0.3.0] - 2026-04-17

### Changed
- fix: improve SSE streaming error diagnostics
- refactor: let callers control reasoning params
- fix: allow reserved chars in spec descriptions
- fix: stop auto-wrapping bare arrays for single :many specs
- chore: drop allure reporting and test fixture cruft
- release: update version files for v0.2.0, bump to next dev version


## [v0.2.0] - 2026-04-14

### Changed
- feat: add reasoning-params to glm-5.1 and glm-5-turbo — default medium, RLM overrides dynamically
- feat: add *log-context* for log correlation — callers can bind {:query-id :iteration} to prefix all HTTP logs
- fix: demote SAP warnings to :debug — ExtractedFromMarkdown noise
- feat: compact logs for SAP warnings, HTTP retries, errors — single-line with symbols
- feat: compact single-line LLM logs — → HTTP model=X tokens=N and ← HTTP model=X 150ms in=N out=N
- feat: add Streaming section to README, consolidate parsing sections, fix streaming on-chunk paren issue + nil-result suppression, pass tokens/cost in streaming chunks
- feat: add Router section to README, consolidate Functionalities table, rename cb-* opts to failure-threshold/recovery-ms, remove RECOMMENDATIONS.md
- chore: strip RLM from RECOMMENDATIONS.md, lower verify.sh test floor to 350
- chore: remove target, .omc, PLAN.md — RLM cleanup
- refactor!: excise RLM, PageIndex, git ingestion, bench, paren-repair — moved to ../vis
- wip(rlm): production cutover to SQLite — Datalevin gone from src/
- feat(rlm): SQLite store foundation — DDL, FTS5, entity/conv/query/iter ops
- refactor(rlm): kill :db-info-atom — 37 sites, ZERO mutations, pure ceremony
- refactor(rlm): encapsulate env atom access behind rlm-env/* accessor fns
- refactor(rlm): eliminate sub → core cyclic dep via parameter injection
- fix: HTTP client shutdown hook + kill requiring-resolve hacks in sub.clj
- fix(router): refresh pricing data (2026-04-12 audit)
- refactor(rlm): Phase 4 — extract rlm/env.clj + atom grouping (12 → 7)
- feat(rlm): Phase 7 + 8 + named conversations + PLAN update
- refactor(rlm): Phase 5 — extract query.clj + configurable .svar dir + user recs
- fix(rlm): Phase 6 — atomic CAS in with-depth-tracking (race condition)
- refactor(rlm): Phase 3 — extract QA pipeline to rlm/qa.clj + rlm/qa_manifest.clj
- refactor(rlm): Phase 0-2 — core.clj compaction, trace + PageIndex extraction, QA rename
- feat(rlm): skill change detection, reference files, body auto-patch
- feat(rlm): async skill auto-refine + depth guard on skill loading
- feat(rlm): skills-as-documents + skill-manage SCI tool
- feat(rlm): parallel batch + public hooks/tools API
- feat(rlm): skills system, sub-rlm-query, concurrency primitives
- refactor(rlm): hook system v3 — eliminate dynamic vars, namespaced status/error ids, extract rlm/data.clj
- Continuation
- feat(rlm): cancel-query! + :on-iteration callback for query control
- refactor(rlm): persist git :repo attachments in Datalevin, drop env atoms
- feat(rlm): wire git runtime via JGit, multi-repo aware, git- prefixed SCI tools
- feat(rlm): adaptive eval-timeout, rel-query SCI tools, enum guard
- feat(rlm): persist vars as child entities, thread llm-opts through CoVe, fix duration tracking
- fix(rlm): fix re-find bug, nil-guard make-chat-url, stabilize integration tests
- fix(rlm): restore-var binds in SCI sandbox, cleanup dead params and nil consistency
- feat(rlm): add conversation restore tools
- chore: update test fixture manifest
- fix(test): update caveman assertion to match current prompt
- fix(test): guard streaming tests behind integration-tests-enabled?
- feat(rlm): add git commit ingestion pipeline
- Add mandatory clj-paren-repair for Clojure delimiter errors
- chore: ultra-caveman CLAUDE.md communication style rules
- refactor(rlm): split caveman output — iterations terse, final answer normal English
- chore: update test fixture manifest
- refactor(rlm): update tests, docs, and bench for explicit :db spec
- refactor(rlm): wire explicit :db spec through create-env and create-rlm-env
- refactor(rlm): add explicit db-spec API to create-rlm-conn
- chore: remove CRITICISM.md
- Engage Caveman!
- fix(rlm): gate SCI execution and validation on Clojure language only
- fix(rlm): skip edamame validation for non-Clojure final answers
- chore: checkpoint rlm hardening and workspace updates
- perf(rlm): cache QA corpus snapshot for manifest fingerprinting
- fix(rlm): harden QA manifest resume fingerprinting and persistence
- feat(router): add minimax-m2.7, gemma4:31b, qwen3.5:397b to blockether
- refactor(rlm): simplify get-locals to sci/eval-form + reduce-kv
- feat(rlm): RL Q-values, co-occurrence edges, certainty normalization, QA resume, SCI isolation
- fix(bench): restore test form in failure reports, safe string embedding
- fix(bench): fix 7 unbalanced test forms in 4clojure dataset, bb script debug
- refactor(rlm): split parse-clojure-syntax into composable check functions
- fix(rlm): bare list detection in parse-clojure-syntax, bb timeout 60s, PROBLEMS.md
- fix(vitality): lazy certainty decay as pure computation, no DB writes per query
- fix(vitality): wire Bayesian certainty into page vitality scoring
- feat(vitality): Bayesian certainty per document (Beta distribution)
- fix(vitality): merge spreading activation with find-related, fix canonical sibling traversal
- test: memory system tests (batch search, BFS, canonical-id, spreading activation)
- feat(vitality): spreading activation across connected pages
- fix: PageIndex ID collision, QA batch retry, actionable error messages
- feat: auto-translate non-English content to English at ingest + query
- fix(test): sanitize_code_test quality improvements
- fix(test): remove redundant 'user namespace from sanitize_code_test
- fix(test): sanitize_code_test SCI namespace isolation
- fix(bench): reset circuit breaker between tasks to prevent cascading failures
- feat: search-documents-batch! + results->markdown renderer
- refactor: migrate all query-env! callers to messages vector
- feat(entity): multimodal query-env!, canonical-id linking, BFS graph traversal
- feat(entity): unified entity model with closed type enums
- feat(bench): add median/p90/p99/std-dev stats and --compare command
- docs(rlm): add deref-with-timeout hint to system prompt
- feat(rlm): add SCI futures addon (future, pmap, promise, delay, deliver)
- feat(rlm): rename SCI default ns from 'user to 'sandbox
- feat(rlm): proper SCI stdout capture, functions in var index, docstring hint
- feat(rlm): edamame syntax validation for code blocks and final answers
- fix(bench): revert to inline substitution, trim RLM prompt, REPL hint
- feat(bench): separate PI prompt, update qwen context to 128K
- feat(bench): PI local support via router, improved strip-code-fence, qwen 9b model
- feat(vitality): ACT-R memory decay with type-aware metabolic rates
- feat(rlm): API-style dispatch, LM Studio provider, sandbox fixes, bench hardening
- feat: OCR extraction strategy, Anthropic API support, HTTP/1.1 fix, LM Studio provider
- fix(rlm): allow resolve/ns-resolve in SCI (read-only, needed for doc injection)
- fix(rlm): remove Object/Thread/Class from SCI imports, proper :deny list
- fix(rlm): restore clojure.java.process require (linter stripped it)
- feat(rlm): add BigInteger + BigDecimal to SCI classes and imports
- feat(rlm): execute code blocks BEFORE accepting final (self-test gate)
- fix(rlm): auto-repair final answer parens before storing
- feat(rlm): "NEVER guess, ALWAYS test" rule in system prompt
- feat(rlm): reject untested finals + mandatory self-test in 4clojure
- fix(rlm): validate-final uses keyword case, not name conversion
- fix(llm,vision): cap vision max_tokens at 15K, merge caller extra-body
- fix(rlm): validate-final handles keyword and string answer-type/language
- feat(rlm): lazytest in SCI + fix validate-final enum handling
- fix(rlm): generic NPE hint instead of misleading method-on-nil assumption
- feat(rlm): specialized error hints with context extraction
- feat(rlm): conditional error hints in feedback, not system prompt
- fix(rlm): trim system prompt 1748 -> 667 tokens
- feat(bench): 4clojure self-test workflow + query-opts passthrough
- fix(rlm): reject final answers with bare % args outside #()
- fix(rlm): remove all SCI :deny restrictions
- fix(rlm): allow require/import/ns in SCI, add vector/LazySeq/nil hints
- refactor(rlm): remove str-* and set-* helpers, use namespace aliases
- refactor(rlm): remove 119 redundant SAFE_BINDINGS, SCI has them built-in
- feat(rlm): add abs, parse-long, parse-double, parse-boolean, parse-uuid, infinite?, NaN? to SCI
- fix(bench): increase task timeout 5min -> 10min, fix misleading label
- feat(rlm): transducer hint in COMMON ERRORS system prompt
- fix(rlm): reject final answers containing __ placeholder
- feat(rlm): validators for all FINAL_SPEC languages
- feat(rlm): FINAL_SPEC answer-type + language fields for typed validation
- refactor(rlm): move final answer validation to spec-level validator
- feat(rlm): validate final answers that contain code before accepting
- refactor(rlm): use sci/copy-ns for standard namespaces in SCI
- docs: add SCI sandbox and benchmark sections to CLAUDE.md
- fix(rlm): drop real clojure.pprint from SCI - pure zprint backed
- feat(rlm): clojure.pprint/pprint backed by zprint, keep print-table/cl-format
- feat(rlm): real clojure.pprint in SCI + more error hints in system prompt
- feat(rlm): expose more zprint fns in SCI (czprint, set-options!, zprint-file-str)
- fix(rlm): manually bind zprint fns instead of ns->sci-map
- feat(rlm): add pprint and pp aliases for zprint.core in SCI
- feat(rlm): alias clojure.edn -> fast-edn, clojure.pprint -> zprint in SCI
- feat(rlm): swap pprint for zprint, clojure.edn for fast-edn in SCI
- feat(rlm): add clojure.edn + clojure.pprint to SCI namespaces
- fix(bench): denormalize trajectory EDN - inline conversation, strip db refs
- refactor(rlm): align SCI :imports with Clojure/Babashka defaults
- feat(rlm): system prompt - each code[] entry must be complete expression
- fix(rlm): coalesce fragment code blocks before execution
- refactor(rlm): use SCI :imports for bare class names instead of duplicate :classes
- fix(rlm): bare class aliases in SCI (Character, Math, String, etc.)
- fix(rlm): restore DEBUGGING section in system prompt
- feat(rlm): COMMON ERRORS section in system prompt with actionable fixes
- fix(deps): downgrade datalevin 0.10.7 -> 0.10.3
- feat(rlm): pre-exec lint for nested #() + system prompt hints
- fix(bench): 4clojure prompt hints - nested #(), quoted lists, inline expr
- feat(rlm): add (sh) shell tool via clojure.java.process
- fix(rlm): lower realize-value limit to 100 elements
- fix(rlm): shared HTTP client, virtual threads, PersistentQueue, bounded lazy-seq, JSON/walk namespaces
- test(pageindex): remove broken output-dir validation test
- feat(rlm): paren-repair module for auto-fixing LLM delimiter errors
- refactor(bench): reorganize bench layout - common, benches/ subdir, trajectory EDN persistence
- feat(rlm): parallel per-page indexing + --force flag
- refactor(rlm)!: split build-index :path into extract + finalize phases
- refactor(vision): split fidelity prompt — copy text, keep detecting visuals
- Fixes
- test(pageindex): update filter tests for vision-layer filtering invariant
- feat(bench): trajectory collection, runtime hints, provider+model routing fix
- feat(rlm): system prompt improvements - data literals, SCI-as-REPL, style rules
- refactor(rlm): consolidate document tools into search-documents + fetch-content
- chore: remove legacy and backward-compat code
- chore(gitignore): bench-logs, bench/trajectories, schema-therapy pageindex variants
- rename: P-add! → fetch-content
- refactor(rlm)!: token-efficient system prompt + discoverable tool docs
- refactor(trajectory): build and store system-prompt at create-env time
- fix(trajectory): store system-prompt once, skip if already set
- fix(trajectory): store system-prompt on conversation at iteration-loop time
- fix(trajectory): resolve Datalevin entity refs, JSONL export working
- refactor(trajectory)!: rewrite trajectory.clj for query-based hierarchy
- refactor(trajectory)!: hierarchical schema — conversation → query → iteration
- feat(trajectory): add :iteration/final field for terminal answer
- refactor(trajectory): separate code/results fields, remove status
- fix(trajectory): sanitize executions for EDN serialization
- refactor(trajectory)!: iteration snapshots instead of sequential messages
- fix(trajectory): store messages on error/empty paths, include next-optimize
- fix(trajectory): reconstruct-conversation uses stored ITERATION_SPEC content
- fix(rlm): store ITERATION_SPEC JSON as assistant message content
- feat(rlm): persist iteration messages + executions for trajectory fine-tuning
- refactor(logging): merge ask-routed + chat-completion into llm-request/llm-response
- feat(logging): comprehensive LLM call chain logging
- refactor(rlm): proper types — enum next-optimize, nested ref final
- refactor(rlm)!: remove carry mechanism, enforce docstrings
- feat(rlm): improve system prompt — llm-query for coding tasks, general workflow
- feat(rlm): expand SCI sandbox with common Java classes
- fix(verify): reduce false positives in secret scan regex
- feat(rlm): LLM-driven model selection via :next-optimize in iteration spec
- refactor!: migrate ALL LLM functions to :routing API
- refactor!: migrate all ask! callers to :routing opts
- fix!: remove legacy ask! API, fix reasoning detection, add routing validation
- refactor(router): move router-stats, reset-budget!, reset-provider! to router.clj
- refactor!: delete defaults.clj, merge everything into router.clj
- refactor(router): add env-keys to KNOWN_PROVIDERS, simplify bench runner
- feat(llm): add :routing opts to ask!, RLM uses auto params
- refactor(router): create router.clj — single source of truth for routing
- fix(rlm): derive max_tokens from model context window (25%)
- fix(llm): merge reasoning-params into ask! extra-body
- refactor(rlm)!: replace routed-chat-completion with ask! in iteration loop
- feat(bench)!: overhaul benchmark suite — 4clojure, HumanEval, Pi agent, multi-provider
- chore: suppress unused-var warnings, clean up formatting
- feat(bench): add coding suites and EDNL run aggregation
- fix: streaming robustness, sci/intern, budget accuracy, done signal
- feat!: router-first API, RLM cleanup, SSE streaming
- fix: show errors in progress line when non-zero
- fix: prefix unused errors binding in print-progress


### Added

**Hook system v3** — per-tool `:before`/`:after`/`:wrap` chains and a global lifecycle `:hooks` map, both wired through `register-env-fn!` and `query-env!`. Policy (deny / transform / recover) lives in per-tool chains; observation (logging / metrics / UI streaming) lives in global hooks. See `rlm.tools/normalize-hooks` / `execute-tool` / `wrap-tool-for-sci` for the full engine.

Per-tool chains (attached via `register-env-fn!` tool-def):
  - `:before` — each hook receives the invocation map, may return `{:args v}` (transform args), `{:skip v}` (short-circuit, :after still runs), `{:error e}` (short-circuit to error), or nil.
  - `:after` — receives the outcome map, may return `{:result v}`, `{:error e}`, `{:result v :error nil}` (recover from failure), or nil. Independent sequential chain, NOT paired setup/teardown.
  - `:wrap` — ring-style middleware, vector is vec-LAST = outermost (matches `(-> handler inner outer)` convention).
  - Idempotent layered registration: calling `register-env-fn!` twice on the same symbol merges hooks by `:id`, same id replaces in place, new ids append, old ids preserved.
  - Throws caught and logged; iteration loop continues.
  - `(:invoke inv-map)` — call another registered tool through its own per-tool chain, bypassing global observers. Depth-tracked via explicit `query-ctx` (`:depth`), cap `MAX_HOOK_DEPTH` = 8.

Global lifecycle hooks (`query-env!` `:hooks {:on-iteration ... :on-cancel ...}`) — all pure observers, return ignored, exceptions swallowed:
  - `:on-iteration` — fires after `store-iteration!` with `{:iteration :status :thinking :executions :final-result :error :duration-ms}`. Status ∈ `#{:error :empty :success :final}`. Replaces the top-level `:on-iteration` opt.
  - Hook payloads + error terminal returns now also include `:status-id` namespaced keywords (for example `:rlm.status/error`, `:rlm.status/cancelled`) alongside legacy `:status`.
  - `:on-cancel` — fires when the cancel-atom is observed true.
  - `:on-chunk` — migrated from top-level `:on-chunk` opt into `:hooks`.
  - `:on-tool-invoked` / `:on-tool-completed` — fire around the per-tool pipeline for tools registered via `register-env-fn!`.

`query-env!` opts:
  - `:hooks {...}` — canonical entry point for global lifecycle hooks.
  - `:cancel-atom (atom false)` — caller-owned atom. Flip from any thread to cancel the in-progress query; the iteration loop finishes the current cycle and returns `{:status :cancelled}`. If omitted, `query-env!` creates a fresh one locally.
  - `:eval-timeout-ms` — unchanged. Clamped `[1s, 30min]`.

Inspection / maintenance:
  - `rlm/list-tool-hooks env sym` → `{:before [{:id :position :fn-name}] :after [...] :wrap [...]}`
  - `rlm/list-registered-tools env` → `[{:sym :hook-counts {:before :after :wrap}}]`
  - `rlm/unregister-hook! env sym stage id` → true/false

Other additions (unchanged from prior unreleased shipping):
- `rlm.schema/*eval-timeout-ms*` dynamic var replacing the former hardcoded `EVAL_TIMEOUT_MS` constant.
- `rlm.schema/MIN_EVAL_TIMEOUT_MS` (1s) and `rlm.schema/MAX_EVAL_TIMEOUT_MS` (30min) — hard bounds to prevent runaway SCI futures.
- SCI sandbox: `search-entities`, `get-entity`, `list-relationships` bound from existing `rlm.db` fns.
- `pageindex.vision/extract-text-from-pdf`: `:extraction-strategy` enum validation (throws on non-`#{:vision :ocr}`).
- **Git runtime wiring (W2 + W2b multi-repo).** `rlm/ingest-git!` opens git repos via JGit (no shell-out), reads commits, stores them as `:event` + `:person` + `:file` entities with relationships, and attaches each open `Repository` to the env keyed by repo-name. **Multi-repo by default** — call `ingest-git!` multiple times with distinct `:repo-name` values and every attached repo remains queryable. `.gitignore` does not affect JGit, so repos living inside gitignored subdirectories (e.g. `external-repos/foo/.git`) work transparently. The SCI sandbox exposes seven git query tools — all prefixed `git-` — that remain invisible unless `ingest-git!` has been called:
  - DB-backed (cross-repo, scope via `:document-id`): **`git-search-commits`**, **`git-commit-history`**, **`git-commits-by-ticket`**
  - JGit-backed (auto-dispatch, no `:repo` opt needed): **`git-file-history`**, **`git-blame`**, **`git-commit-diff`**, **`git-commit-parents`**
  - Path-based tools auto-route by path: single-repo mode accepts relative or absolute paths; multi-repo mode requires an absolute path inside one attached repo's worktree (throws `:rlm/no-repo-for-path` with `:reason :relative-path` otherwise).
  - SHA-based tools auto-route via real object-database presence check (not just SHA syntax validity). Ref names like `HEAD` are ambiguous in multi-repo mode and throw `:rlm/ambiguous-ref`.
  - System prompt renders one `GIT REPO` context block per attached repo (name, path, head short-sha, branch, commits-ingested). In multi-repo mode the tool-doc list advertises "pass ABSOLUTE path" guidance.
  - `dispose-env!` closes every attached `Repository` and clears both atoms.
  - New dep: `org.eclipse.jgit/org.eclipse.jgit 6.10.0`.
- `rlm.schema` commit attrs: `:commit/parents` (many strings, for graph walking) and `:commit/author-email` (denormalized for `db-search-commits` queries by author).
- `rlm.db/db-search-commits` — query commit entities by `:category`, `:since`, `:until`, `:ticket`, `:path`, `:author-email`, `:document-id`, `:limit`. Backs the `search-commits` / `commit-history` / `commits-by-ticket` SCI tools.
- `rlm.db/db-commit-by-sha` — SHA-prefix lookup of a single commit entity.

### Breaking

- **`rlm/query-env!` opt changes.** Top-level `:on-chunk` and `:on-iteration` are removed. Move them into the `:hooks` map: `{:hooks {:on-chunk ... :on-iteration ...}}`.
- **`rlm/cancel-query!` removed.** Cancellation is caller-owned now: create an `(atom false)`, pass it via `:cancel-atom` opt to `query-env!`, and `(reset! the-atom true)` from any thread to cancel.
- **Env map no longer carries `:cancel-atom`.** Callers that reached into the env to flip cancellation must pass their own atom via the `:cancel-atom` query-env! opt.
- **`rlm.tools` hook engine refactor.** Replaced transient hook dynamic bindings with explicit per-query context threading (`query-ctx`) plus caller-owned atoms (`:cancel-atom`, `:current-iteration-atom`) where mutation is required.
- Env map no longer carries `:custom-bindings-atom` for fns — register-env-fn! writes to the new `:tool-registry-atom` instead. `:custom-bindings-atom` is still used by `register-env-def!` for constants/values.
- Entity extraction transaction pipeline moved out of `rlm.core` into `rlm.data` helper functions (`store-extraction-results!` + normalized entity/relationship tx builders).

### Changed
- `internal/llm.clj` `shared-http-client`: HTTP/1.1 pin now documented as load-bearing for OCR. Mirror note added in `pageindex/vision.clj` above the OCR section.
- `rlm/git.clj` rewritten: **replaced `clojure.java.shell/sh` with JGit interop.** Pure parsers (`parse-commit-message`, `extract-ticket-refs`, `prefix->category`, `commit->entity`, `ingest-commits!`) preserved and unit-tested. New IO surface: `open-repo`, `git-available?`, `read-commits`, `head-info`, `blame`, `commit-diff`, `file-history`, `commit-parents`. The old `read-git-log` / custom `git log --format=sha:%H%n...` string parser is gone.

### Deferred (planned — see `.omc/plans/autopilot-impl.md`)
- Pluggable Q-reward + trajectory scoring through `query-env!`.
- Restore GC + `:iteration.var/schema-version` + re-exec mode — schema change; needs review.
- Flip `:extract-entities?` default to `true` — breaking; needs explicit consent.

## [v0.1.3] - 2026-03-15

### Changed
- feat: validate-data TYPE_REF recursion
- release: update version files for v0.1.2


## [v0.1.2] - 2026-03-12

### Changed
- ci: upgrade deploy workflow with Clojars existence check and version bumps
- docs: update CHANGELOG.md for v0.1.1
- crop logo white space
- Update logo width in README.md
- Update logo size in README.md
- Update README.md
- update logo
- feat(pageindex): add page range
- fix(examples): syntax
- Fix README: subtitle layout, non-breaking function names, and anchor links in table
- Fix CI env vars
- Reproduction
- Questionify and major refactor
- Fix ci/readme configuration
- Add RLM, spec fixes
- Initial commit


## [v0.1.1] - 2026-03-12

### Changed
- crop logo white space
- Update logo width in README.md
- Update logo size in README.md
- Update README.md
- update logo
- feat(pageindex): add page range
- fix(examples): syntax
- Fix README: subtitle layout, non-breaking function names, and anchor links in table
- Fix CI env vars
- Reproduction
- Questionify and major refactor
- Fix ci/readme configuration
- Add RLM, spec fixes
- Initial commit


[Unreleased]: https://github.com/Blockether/svar/compare/v0.7.9...HEAD
[v0.5.3]: https://github.com/Blockether/svar/releases/tag/v0.5.3
[v0.1.1]: https://github.com/Blockether/svar/releases/tag/v0.1.1
[v0.1.2]: https://github.com/Blockether/svar/releases/tag/v0.1.2
[v0.1.3]: https://github.com/Blockether/svar/releases/tag/v0.1.3
[v0.2.0]: https://github.com/Blockether/svar/releases/tag/v0.2.0
[v0.3.0]: https://github.com/Blockether/svar/releases/tag/v0.3.0
[v0.3.1]: https://github.com/Blockether/svar/releases/tag/v0.3.1
[v0.3.2]: https://github.com/Blockether/svar/releases/tag/v0.3.2
[v0.3.3]: https://github.com/Blockether/svar/releases/tag/v0.3.3
[v0.3.4]: https://github.com/Blockether/svar/releases/tag/v0.3.4
[v0.3.5]: https://github.com/Blockether/svar/releases/tag/v0.3.5
[v0.3.6]: https://github.com/Blockether/svar/releases/tag/v0.3.6
[v0.3.8]: https://github.com/Blockether/svar/releases/tag/v0.3.8
[v0.3.9]: https://github.com/Blockether/svar/releases/tag/v0.3.9
[v0.3.10]: https://github.com/Blockether/svar/releases/tag/v0.3.10
[v0.3.11]: https://github.com/Blockether/svar/releases/tag/v0.3.11
[v0.4.0]: https://github.com/Blockether/svar/releases/tag/v0.4.0
[v0.4.1]: https://github.com/Blockether/svar/releases/tag/v0.4.1
[v0.4.2]: https://github.com/Blockether/svar/releases/tag/v0.4.2
[v0.4.3]: https://github.com/Blockether/svar/releases/tag/v0.4.3
[v0.4.4]: https://github.com/Blockether/svar/releases/tag/v0.4.4
[v0.4.5]: https://github.com/Blockether/svar/releases/tag/v0.4.5
[v0.4.6]: https://github.com/Blockether/svar/releases/tag/v0.4.6
[v0.4.7]: https://github.com/Blockether/svar/releases/tag/v0.4.7
[v0.4.8]: https://github.com/Blockether/svar/releases/tag/v0.4.8
[v0.4.9]: https://github.com/Blockether/svar/releases/tag/v0.4.9
[v0.4.10]: https://github.com/Blockether/svar/releases/tag/v0.4.10
[v0.4.11]: https://github.com/Blockether/svar/releases/tag/v0.4.11
[v0.4.12]: https://github.com/Blockether/svar/releases/tag/v0.4.12
[v0.4.13]: https://github.com/Blockether/svar/releases/tag/v0.4.13
[v0.4.15]: https://github.com/Blockether/svar/releases/tag/v0.4.15
[v0.5.0]: https://github.com/Blockether/svar/releases/tag/v0.5.0
[v0.5.1]: https://github.com/Blockether/svar/releases/tag/v0.5.1
[v0.5.2]: https://github.com/Blockether/svar/releases/tag/v0.5.2
[v0.5.4]: https://github.com/Blockether/svar/releases/tag/v0.5.4
[v0.5.5]: https://github.com/Blockether/svar/releases/tag/v0.5.5
[v0.5.6]: https://github.com/Blockether/svar/releases/tag/v0.5.6
[v0.5.7]: https://github.com/Blockether/svar/releases/tag/v0.5.7
[v0.5.8]: https://github.com/Blockether/svar/releases/tag/v0.5.8
[v0.5.9]: https://github.com/Blockether/svar/releases/tag/v0.5.9
[v0.5.10]: https://github.com/Blockether/svar/releases/tag/v0.5.10
[v0.6.0]: https://github.com/Blockether/svar/releases/tag/v0.6.0
[v0.6.1]: https://github.com/Blockether/svar/releases/tag/v0.6.1
[v0.7.0]: https://github.com/Blockether/svar/releases/tag/v0.7.0
[v0.7.1]: https://github.com/Blockether/svar/releases/tag/v0.7.1
[v0.7.2]: https://github.com/Blockether/svar/releases/tag/v0.7.2
[v0.7.3]: https://github.com/Blockether/svar/releases/tag/v0.7.3
[v0.7.4]: https://github.com/Blockether/svar/releases/tag/v0.7.4
[v0.7.5]: https://github.com/Blockether/svar/releases/tag/v0.7.5
[v0.7.6]: https://github.com/Blockether/svar/releases/tag/v0.7.6
[v0.7.7]: https://github.com/Blockether/svar/releases/tag/v0.7.7
[v0.7.8]: https://github.com/Blockether/svar/releases/tag/v0.7.8
[v0.7.9]: https://github.com/Blockether/svar/releases/tag/v0.7.9
