# SVAR RLM — Critical Architecture Review

Last reviewed: March 2026

---

## CRITICAL — Fix NOW

### ~~1. SCI Sandbox allows arbitrary Java reflection~~ — FALSE ALARM

**Status: VERIFIED SECURE** (March 2026)

SCI's `:classes` option is a strict **allow-list**. Only 5 classes are accessible: `Pattern`, `Matcher`, `LocalDate`, `Period`, `UUID`. Everything else is blocked by SCI at the interpreter level:

- `java.lang.Runtime/getRuntime` → BLOCKED ("Unable to resolve symbol")
- `slurp` / `spit` → BLOCKED ("Unable to resolve symbol")
- `Thread` → BLOCKED ("Unable to resolve classname")
- `ProcessBuilder` → BLOCKED

The `:deny` list (`[require import ns eval load-string read-string]`) is an additional safety layer on top of the class allow-list. The sandbox is solid.

---

### ~~2. Consecutive error handling is broken — strategy restart is unreachable~~ — FIXED

**Status: FIXED** (March 2026)

`consecutive-errors` now only resets to 0 when at least one code execution succeeds. Empty responses, all-error executions, and parse failures all increment the counter. Strategy restart (5 consecutive errors → anti-knowledge injection, up to 3 restarts) is now reachable.

---

### ~~3. Missing SCI bindings documented in the prompt~~ — FALSE ALARM

**Status: VERIFIED CORRECT** (March 2026)

`str-lower`, `str-includes?`, `str-truncate` ARE available. Defined in `db.clj`, imported to `tools.clj` via `:refer :all`, registered as SCI bindings (lines 580-583). The audit was wrong.

---

## HIGH — Fix soon

### ~~4. Cost tracking is incomplete and inaccurate~~ — FIXED

**Status: FIXED** (March 2026)

All `query-env!` cost sources now tracked:
- Iteration loop (exact via `:api-usage`) ✓
- Refinement (actual from `refine!*` return, was 2-6x off with estimation) ✓
- Planning phase `ask!` ✓
- Refinement fallback `ask!` (spec re-parse) ✓
- Auto-vote learnings ✓

**Separate operations** (return their own cost independently):
- `generate-qa-env!` (dedup, revision, passage selection) — not merged into query cost, tracked separately
- `ingest-to-env!` (entity extraction) — separate operation
- `index!` (vision calls) — separate operation

---

### ~~5. `request-more-iterations` has no rate limiting~~ — FIXED

**Status: FIXED** (March 2026)

- Per-request cap: `MAX_EXTENSION_PER_REQUEST = 50` (LLM can't request 500 in one shot)
- All extension requests are logged with `:requested`, `:granted`, `:new-budget`, `:cap`
- `MAX_ITERATION_CAP = 500` remains as absolute ceiling

---

### ~~6. Document search returns full content — massive token waste~~ — FIXED

**Status: FIXED** (March 2026)

- `search-document-pages` now returns **brief metadata** only: `{:page.node/id :page.node/type :page.node/page-id :preview "first 150 chars..." :content-length N}`
- Full content fetched via `P-add!` using Datalevin lookup ref syntax:
  - `(P-add! [:page.node/id "abc"])` → content string
  - `(P-add! [:document/id "doc-1"])` → vector of ~4000 char chunked pages
  - `(P-add! [:document.toc/id "toc"])` → TOC description
- Per the RLM paper: LLM manages working data via `def` variables, P stays immutable
- Token savings: ~5,000 tokens per search → ~500 tokens (metadata only), 10x reduction

---

### ~~7. History accumulates unbounded by default~~ — FIXED

**Status: FIXED** (March 2026)

`max-context-tokens` now defaults to 60% of the model's context window (from `providers/context-limit`). Semantic context selection always activates after 4+ messages. No more unbounded history accumulation.

---

## MEDIUM — Should fix

### ~~8. Auto-learn pipeline partially dead~~ — REDESIGNED

**Status: REDESIGNED** (March 2026)

Based on research (Reflexion, ExpeL, Voyager, CoALA, ICLR 2026 MemAgents benchmark): "Retrieval matters 20x more than storage sophistication." Graph-based links replaced with flat learnings + tags + scopes + voting/decay.

Current pipeline (2 steps, both implemented and wired):
- `auto-vote-learnings!` ✓ — evaluates injected learnings, drives decay
- `auto-extract-learnings!` ✓ — extracts 1-3 reusable insights with tags + scope

Removed (no evidence of value): auto-define-tags!, auto-link-learnings!, auto-vote-links!, all learning-link schema/CRUD/neighborhood fetch.

---

### ~~9. Active learnings injection is unbounded~~ — FIXED

**Status: FIXED** (March 2026)

- Capped at 5 learnings max
- Insights truncated to 100 chars, context to 60 chars
- Tag glossary filtered to only tags on injected learnings (was ALL tags)
- Model-context learnings removed (noise)
- Single query-relevant fetch (top-k=5) instead of query+model blend
- auto-extract now async (fire-and-forget, doesn't delay response)
- auto-extract has quality gate (only fires on successful FINAL, not error-budget-exhausted)
- auto-extract trace summary includes code+results, not just thinking

---

### 10. Async thread resource leaks

Code execution uses `async/thread` with a 30s timeout (`async/alts!!`). When code times out, the thread keeps running in the background — `alts!!` returns nil but the thread is NOT cancelled. `StringWriter` / `PrintWriter` are never explicitly closed.

50 iterations with timeouts = 50 zombie threads consuming memory and CPU.

**Fix**: Use `future-cancel` or `Thread/interrupt` on timeout. Close writers in a finally block.

**Location**: `rlm/core.clj` lines 178-214 (code execution).

---

### 11. FINAL detection is fragile

- `{:rlm/final "true"}` (string) does NOT trigger FINAL — must be boolean `true`
- No answer validation before termination — if output spec requires `{:name :age}` but LLM returns `{:name "John"}`, FINAL fires anyway, validation happens later
- LLM sometimes writes `FINAL answer` without parens → gets function object reference instead of termination
- If the LLM calls FINAL in code block 3 of 5, blocks 4-5 are ignored

**Fix**: Accept truthy values for `:rlm/final`. Validate answer against output spec before accepting FINAL. Use the LAST FINAL call if multiple exist.

**Location**: `rlm/core.clj` lines 220-227 (check-result-for-final).

---

### 12. System prompt is ~5000 tokens of mixed-value content

Breakdown:
- Document tools: ~1200 tokens (9 tools with full schemas — bloated)
- Learnings tools: ~600 tokens (rarely used by LLM)
- History tools: ~300 tokens (rarely used)
- RLM patterns: ~400 tokens (LLM rarely follows them)
- Active learnings injection: 0-2000 tokens (unbounded)

~1300 tokens of low-value content per iteration. Over 50 iterations that's 65,000 wasted input tokens.

**Fix**: Make learnings/history/patterns sections optional. Collapse document tools to 3 core functions. Cap learnings injection.

**Location**: `rlm/core.clj` build-system-prompt (lines 437-720).

---

### 13. Concurrency is not safe

`depth-atom`, `hooks-atom`, `locals-atom` are per-env but shared across queries. Two parallel `query-env!` calls on the same env cause race conditions:
- `depth-atom` incremented by both → incorrect recursion depth
- `hooks-atom` modified by both → undefined behavior
- `locals-atom` mutated by SCI execution → cross-contamination

**Fix**: Create query-scoped atoms in `query-env!`, not in `create-env`. Or document that envs are NOT thread-safe.

**Location**: `rlm.clj` lines 137-144 (create-env atoms).

---

## LOW — Nice to have

### 14. `dispose-env!` doesn't clean up routers

Only closes the DB connection. Router state (atoms, provider slots) is never cleared. Creating and disposing 100 envs leaks 100 routers.

**Location**: `rlm.clj` lines 297-307.

---

### 15. PageIndex ID collision

`page.node/id` and `document.toc/id` share the same local ID space during translation. A paragraph with id="1" and a TOC entry with id="1" on the same page map to the same UUID.

**Location**: `rlm.clj` lines 1582-1644 (translate-page-ids).

---

### 16. `generate-qa-env!` has no retry for failed batches

Parallel batch processing uses `async/pipeline-blocking`. If one batch fails due to a transient network error, it returns empty — no retry. The batch is lost permanently.

**Location**: `rlm.clj` lines 1295-1328.

---

### 17. Error messages for timeout/max-iterations are too terse

- Timeout: `"Timeout (30s)"` — doesn't explain why or what to do
- Max iterations: `"Max iterations reached"` — doesn't mention `request-more-iterations`
- Error budget: `"Error budget exhausted after restart"` — cryptic

The LLM needs actionable guidance to self-correct.

**Location**: `rlm/core.clj` lines 192 (timeout), 948 (max-iterations), 981 (error-budget).

---

## What's GOOD

- Auto-variable system (`_rN`, `_stdoutN`) correctly implements the paper's constant-size metadata approach
- Function-object detection ("Did you mean to call it with parens?") is excellent UX
- Semantic context selection exists for bounded history management
- Provider-scoped pricing with fallback to global pricing is clean
- `INLINE_RESULT_THRESHOLD` forces the LLM to use variables instead of polluting context
- Reasoning-aware spec switching (ITERATION_SPEC vs ITERATION_SPEC_CODE_ONLY) saves tokens
- The overall architecture (SCI sandbox + iterative code execution + FINAL termination + sub-RLM recursion) is sound
- Adaptive iteration budget via `request-more-iterations` matches paper's recommendations
- Strategy restart with anti-knowledge injection is a good idea (even if currently unreachable)
- Learning decay via community voting prevents bad learnings from accumulating
