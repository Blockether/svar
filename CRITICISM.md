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

### 3. Missing SCI bindings documented in the prompt

The system prompt references `str-lower`, `str-includes?`, and `str-truncate` as available helper functions, but they are NOT in `SAFE_BINDINGS`. The LLM calls them, gets a NameError, wastes an iteration.

**Fix**: Add them to SAFE_BINDINGS or remove them from the prompt.

**Location**: `rlm/tools.clj` SAFE_BINDINGS (line 11-48), `rlm/core.clj` system prompt string helper section.

---

## HIGH — Fix soon

### 4. Cost tracking is incomplete and inaccurate

**What's tracked**: Iteration loop LLM calls (exact via `:api-usage`), refinement (estimated), auto-vote (not tracked).

**What's NOT tracked**: Planning phase `ask!` call, sub-RLM query costs, entity extraction during ingest, deduplication/revision in `generate-qa-env!`.

**Refinement estimation is wildly off**: Multiplies total message tokens by estimated call count. A 3-iteration refinement with 2000-token messages estimates 22,000 input tokens when actual is ~3,500. Error factor: 2-6x.

**Location**: `rlm.clj` lines 596-606 (refinement estimation), lines 467-473 (planning, no cost capture).

---

### 5. `request-more-iterations` has no rate limiting

The LLM can call `(request-more-iterations 500)` and immediately get 500 iterations. `MAX_ITERATION_CAP` is 500. No per-query budget, no logging, no human-in-the-loop confirmation. A single query can run for 500 x 30s = 4+ hours.

**Fix**: Cap extensions at 50 per request. Log all extension requests. Add optional `:max-total-iterations` hard cap on `query-env!`.

**Location**: `rlm.clj` lines 406-412 (request-more-iterations binding), `rlm/schema.clj` MAX_ITERATION_CAP.

---

### 6. Document search returns full content — massive token waste

`search-document-pages` returns full `:page.node/content` for each result. Default `top-k=10` means ~5,000 tokens per search. If the LLM does 3 searches per iteration across 10 iterations, that's 150,000 tokens of search results alone.

**Fix**: Add `:include-content? false` mode that returns metadata-only results (id, type, page-id, description preview). The LLM then calls `get-document-page` for specific nodes it needs.

**Location**: `rlm/tools.clj` make-search-page-nodes-fn, `rlm/db.clj` db-search-page-nodes.

---

### 7. History accumulates unbounded by default

If `max-context-tokens` is not explicitly set, ALL messages are sent to the LLM on every iteration. After 50 iterations with code + results, that's 100+ messages. Semantic context selection (`select-rlm-iteration-context`) only activates when `max-context-tokens` is provided.

**Fix**: Default `max-context-tokens` to a reasonable value (e.g., model's context window * 0.6). Never send unbounded history.

**Location**: `rlm/core.clj` lines 987-999 (message selection logic).

---

## MEDIUM — Should fix

### 8. Auto-learn pipeline may be partially dead

`auto-vote-learnings!` is implemented and called. `auto-extract-learnings!`, `auto-define-tags!`, `auto-link-learnings!`, `auto-vote-links!` were added in this session to `rlm/core.clj` and wired into `rlm.clj` via `async/thread`. However, the split file architecture means the functions in `rlm/core.clj` shadow or complement those in the monolith `rlm.clj`. Needs verification that the full pipeline (extract -> define-tags -> link -> vote-links) actually fires end-to-end.

The specs (`AUTOLEARN_SPEC`, `AUTOTAG_SPEC`, `AUTOLINK_SPEC`, `LINK_VOTE_SPEC`) exist in `rlm/schema.clj` but may not be exercised by tests.

**Location**: `rlm.clj` lines 555-575 (async pipeline), `rlm/core.clj` auto-* functions.

---

### 9. Active learnings injection is unbounded

Pre-fetched learnings (5 active + up to 25 neighbors via 1-hop graph traversal) are injected into the system prompt with no token cap. With verbose learning insights, this can add 2000+ tokens to every iteration.

**Fix**: Cap at 5 active + 3 neighbors. Truncate insight text to 80 chars. Enforce a token budget for the learnings section.

**Location**: `rlm.clj` lines 451-465 (pre-fetch), `rlm/core.clj` format-active-learnings.

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
