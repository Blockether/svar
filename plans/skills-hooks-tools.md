# Plan: Skills, Hooks, Tools, Concurrency

> Source PRD: `./PLAN.md`

## Architectural decisions

Durable decisions applying across all slices:

- **Discovery paths**: project > global > plugin
  - Project (walk from cwd to git root): `.svar/skills/`, `.claude/skills/`, `.opencode/skills/`, `.agents/skills/`, `skills/`
  - Global: `~/.svar/skills/`, `~/.claude/skills/`, `~/.config/opencode/skills/`, `~/.agents/skills/`
  - Plugins (opt-in): `~/.claude/plugins/*/skills/`
- **Frontmatter gate**: `compatibility: [svar]` required → else skill ignored by SVAR
- **SCI bindings renamed**: `llm-query` → `sub-rlm-query`, `llm-query-batch` → `sub-rlm-query-batch`, `fetch-content` → `fetch-document-content`
- **Clojure-side local rename**: `sub-llm-query-fn` → `cheap-sub-rlm-query-fn` (avoids sub-sub confusion)
- **Output contract (uniform across modes)**:
  ```
  {:content <str>            ; prose, always populated (SUB_RLM_QUERY_SPEC :content is required)
   :code    <vec<str>|nil>   ; Clojure expressions, matches ITERATION_SPEC cardinality/many
   :tokens  <map>            ; {:input :output :reasoning :cached}
   :reasoning <str|nil>      ; native reasoning tokens, if provider supports
   :routed/provider-id <kw>
   :routed/model <str>
   :routed/base-url <str>
   ;; When iterated (Phase 4+):
   :iter    <int>            ; N iterations
   :result  <any|nil>        ; :final map from sub-RLM
   :trace   <vec|nil>        ; opt-in via :include-trace
   :skills-loaded <vec|nil>}
  ```
- **`SUB_RLM_QUERY_SPEC`** in `schema.clj`: drives the sub-rlm-query shape. Fields:
  - `:content` — string, cardinality/one, required — prose answer
  - `:code` — string, cardinality/many, optional — Clojure expressions
  - Provider-enforced JSON → no regex fence parsing, always consistent shape
- **No `:spec` parameter on sub-rlm-query**. Sub-RLM always returns `{:content :code}`. Callers needing typed data ask the sub-LLM to emit code that `def`s the data, then eval. Single primitive, no structured-output branch.
- **Concurrency settings** on `query-env!`: `{:concurrency {:max-parallel-llm 8 :max-skills-per-call 2 :default-timeout-ms 30000 :http-timeout-ms 20000}}`
- **Semaphore**: reentrant, thread-id keyed, query-env-scoped singleton
- **SCI ctx scoping**: Option C (runtime allowlist gate) as default; upgrade later if spike shows cheap fresh-ctx path
- **Init order**: `create-sci-context` (builds tool registry) → `load-skills` (validates `requires.tools` against registry)
- **Trove log IDs**: `::sub-llm-call` → `::sub-rlm-call`, `::sub-llm-response` → `::sub-rlm-response`
- **Module layout**:
  - `internal/rlm/skills.clj` — discovery, parse, validate, registry
  - `internal/rlm/sub.clj` — `run-sub-rlm-query` iterated primitive
  - `internal/rlm/batch.clj` — `sub-rlm-query-batch` parallel fan-out
- **Plugin trust**: `:skills/load-plugins false` default
- **Caveman rule**: everything that lands in `build-system-prompt` follows CLAUDE.md caveman rules (drop articles, → for causality, no hedging)

---

## Phase 1: Mechanical rename ✅ DONE

**User stories**: naming prerequisite for everything

### What was built

Pure rename sweep across the codebase. Zero behavior change at the shape level; the single-shot `sub-rlm-query` now uses the renamed symbols throughout src/test.

Files touched:
- `routing.clj` — `make-routed-llm-query-fn` → `make-routed-sub-rlm-query-fn`, trove IDs
- `tools.clj` — SCI bindings `'llm-query` → `'sub-rlm-query`, `'fetch-content` → `'fetch-document-content`, `create-sci-context` param rename
- `core.clj` — import, call sites, system prompt docs
- `internal/rlm.clj` — 3 call sites; `sub-llm-query-fn` → `cheap-sub-rlm-query-fn`, second local `sub-llm-fn` → `cheap-sub-rlm-fn`
- `db.clj`, `schema.clj`, `trajectory.clj` — doc refs + scoring regex + legacy `rlm-query` removal
- `rlm_test.clj` — test block + call sites + keyword key + doctest strings
- `README.md`, `CHANGELOG.md`, `CLAUDE.md` — zero matches (internal-only symbols, no public API break)
- `bench/`, `scripts/`, `docs/` — zero matches

### Acceptance criteria ✅ MET

- [x] `grep -rn 'llm-query\|fetch-content\|::sub-llm-call\|::sub-llm-response' src/ test/ bench/ scripts/ CLAUDE.md README.md` returns zero hits
- [x] `./verify.sh` full pipeline green (7/7 steps)
- [x] Test count stable (no regression)
- [ ] ~~Bench baselines captured~~ — deferred; benches take hours sequentially; run when the feature stabilizes at Phase 8
- [ ] ~~CHANGELOG BREAKING entries~~ — deferred to commit time
- [x] All src/test references updated to new names

---

## Phase 2A: Provider-enforced output contract ✅ DONE

**User stories**: guarantee `{:content :code}` shape from every sub-rlm-query; no regex parsing; eliminate the `(pr-str (:result r))` bug

### What was built

**Switched sub-rlm-query away from `llm/routed-chat-completion` to `llm/ask!` with a fixed spec.** Regex fence parsing never shipped — the structured-output path is strictly better.

- Added `SUB_RLM_QUERY_SPEC` in `schema.clj` (after `ITERATION_SPEC_CODE_ONLY`):
  - `:content` — string, cardinality/one, **required**
  - `:code` — string, cardinality/many, optional
- Rewrote `make-routed-sub-rlm-query-fn` in `routing.clj`:
  - One path only. No more `:spec` branch.
  - Calls `llm/ask! rlm-router {:spec SUB_RLM_QUERY_SPEC :messages [...] :routing ... :check-context? false}`
  - Unwraps `:result` → `{:content :code}`, preserves `:tokens`/`:reasoning`/`:routed/*`
  - `:code` = `(vec c)` or `nil` when absent/empty — matches `ITERATION_SPEC` cardinality
  - 2-arity signature: `(sub-rlm-query prompt)` + `(sub-rlm-query prompt {:routing ...})`. No `:spec` key accepted.
- Removed earlier dead-code additions:
  - `extract-code-blocks` regex helper (no regex needed)
  - `make-ask-fn` separate SCI binding (sub-rlm-query IS the ask path)
  - `NAME`/`TYPE`/`CARDINALITY`/`TYPE_*`/`CARDINALITY_*` constants from `tools.clj` base-bindings (no SCI consumer now that :spec caller arg is gone; `'spec` + `'field` remain bound for other uses)

### System prompt additions

Replaced `future/pmap/promise/deliver OK.` line in `build-system-prompt` with:
- `LLM SUB-CALLS` block — `{:content :code}` shape, batch rule, independent-vs-dependent guidance
- `GOTCHAS` expansion — explicit lazy-seq trap (`(future (map f xs))` = serial), eager-first preference, parallel via `sub-rlm-query-batch` or `(mapv deref (mapv #(future ...) xs))`

No SPEC section in the prompt (no caller-facing spec). No mention of "caveman" per user rule.

### Acceptance criteria ✅ MET

- [x] `llm/ask!` + `SUB_RLM_QUERY_SPEC` drives the single path
- [x] `:content` always populated (spec makes it required)
- [x] `:code` is `vec<str>` when present, `nil` when absent — matches `ITERATION_SPEC` cardinality
- [x] No `(pr-str (:result r))` stringify bug
- [x] No caller `:spec` parameter
- [x] `./verify.sh` full pipeline green
- [x] System prompt updated with the new shape + lazy-seq warnings

---

## Phase 2B: Concurrency settings plumbing — PENDING

**User stories**: shared infrastructure for later batch + nested-budget work

### What to build

Thread `:concurrency` settings through `query-env!` schema and into dynamic vars for nested calls to inherit. Construct the query-env-scoped reentrant semaphore (thread-id keyed) so Phase 4/6 can use it. Not exposed to users yet as a primitive — just wired up.

- `:concurrency` opt added to `query-env!` schema with defaults:
  - `:max-parallel-llm 8`
  - `:max-skills-per-call 2`
  - `:default-timeout-ms 30000`
  - `:http-timeout-ms 20000`
- Reentrant semaphore created as a query-env-scoped singleton (thread-id keyed; same thread re-enters without blocking).
- Dynamic var `*sub-rlm-deadline*` (absolute instant) for nested budget inheritance.
- Smoke tests: settings propagation, dynamic var inheritance, reentrancy no-deadlock.

### Acceptance criteria

- [ ] `(svar/query-env! {:concurrency {:max-parallel-llm 4 ...}})` accepted by schema
- [ ] Reentrant semaphore singleton present in the env map; unit-testable
- [ ] `*sub-rlm-deadline*` dynamic var bound at the call site and inherited across Clojure `future` macro bindings
- [ ] Same thread re-acquiring the semaphore does NOT deadlock
- [ ] `./verify.sh` full pipeline green

---

## Phase 3: Skills loader + manifest + single-shot skill invocation

**User stories**: skills discovery + validation; main RLM manifest; thinnest end-to-end skill invocation path

### What to build

Thinnest tracer bullet that demonstrates a skill working end-to-end. Single-shot path only — skill body is prepended as a `{:role "system" :content <body>}` entry in the `llm/ask!` messages vec used by `make-routed-sub-rlm-query-fn`. No iteration yet. The concat logic gets reused by the iterated path in Phase 5, but this slice is demoable on its own.

Build `internal/rlm/skills.clj`:
- Discovery: walk all project + global paths, respect `:skills/roots`, `:skills/allow`, `:skills/deny`, `:skills/load-plugins`
- Parse: `clj-yaml` safe mode, handroll frontmatter split
- Validate: `compatibility` gate, name lowercase + dir match, `requires.tools` against SCI registry, `requires.docs`/`git`/`env`, `agent.max-iter` clamp, body token cap 4000
- Dedupe + collision logging (project > global > plugin)
- Return registry map in rlm-env

Init order: `create-sci-context` runs first (Phase 1 verified this exists), THEN `load-skills` runs with the built SCI registry as input.

Inject compact caveman SKILLS manifest into `build-system-prompt` when ≥1 skill loaded:

```
SKILLS (pass :skills [...] to sub-rlm-query/batch, max 2 per call):
  :name — <description-short ≤200 chars>
```

Wire single-shot skill path: when `(sub-rlm-query {:prompt "..." :skills [:foo]})` fires, loader looks up `:foo`, prepends skill body to the system message of the existing single-shot call. Enforce `:max-skills-per-call` cap. Reject `:unknown-skill`. Dedupe dupes.

Add `svar/register-skill!` public fn for programmatic registration (uses same validation path).

### Acceptance criteria

- [ ] Drop `.svar/skills/hello/SKILL.md` with valid frontmatter + body → loader picks it up at `query-env!` init
- [ ] Main RLM system prompt contains caveman SKILLS block with `:hello` listed
- [ ] `(sub-rlm-query {:prompt "what's your name?" :skills [:hello]})` runs; response is shaped by the skill body
- [ ] Skill body NEVER appears in main RLM system prompt — only in the sub-call's prompt
- [ ] Invalid `compatibility` (missing `svar`) → skill dropped, logged at init
- [ ] `requires.tools` referencing unknown tool → skill dropped with `:missing-tool`
- [ ] Collision across project + global paths → project wins, global logged as discarded
- [ ] `:skills [:nonexistent]` → error `:unknown-skill`
- [ ] `:skills [:a :a]` → dedup to `[:a]`
- [ ] `:skills []` / `nil` / absent → same as mode 1 (no skill)
- [ ] Body > 4000 tokens → skill rejected
- [ ] YAML with `!!java/*` tag → rejected (clj-yaml safe mode)
- [ ] `svar/register-skill!` programmatic registration works + passes same validation
- [ ] 20+ fixture cases in `skills_test.clj` covering all validation paths
- [ ] `./verify.sh` full pipeline green

---

## Phase 4: Iterated sub-RLM primitive (no skills yet)

**User stories**: mode-2 ad-hoc iteration with tools; foundation for mode-3 skill integration

### What to build

Build new `internal/rlm/sub.clj` module with `run-sub-rlm-query` fn. This is the biggest slice — it builds the iterated machinery that earlier plans assumed already existed.

Signature accepts: parent env, system prompt, tool allowlist, prompt, max-iter, deadline, routing, cancel-atom, include-trace flag.

Responsibilities:
- Construct child rlm-env with narrowed SCI ctx (Option C — runtime allowlist gate on dispatch, weakest isolation but viable; upgrade if spike surfaces cheap alternative)
- Build initial messages `[{system} {user}]`
- Loop via existing `run-iteration` (core.clj:468) until `:final` OR `max-iter` OR deadline expired OR cancel-atom set
- Iteration spec selection via `provider-has-reasoning?` on child router
- Collect iterations into trace (only retained when `:include-trace`)
- Propagate nested deadlines via `*sub-rlm-deadline*` dynamic var
- Acquire reentrant semaphore slot for each HTTP call, release between iters
- Return uniform output contract map

Extend `sub-rlm-query` dispatch: when caller passes `:tools` and/or `:max-iter > 1`, route to `run-sub-rlm-query` instead of the single-shot path.

### Acceptance criteria

- [ ] `(sub-rlm-query {:prompt "..." :tools [search-documents] :max-iter 3})` runs up to 3 iterations
- [ ] Returns `{:iter 3 :content <prose> :code <vec> :result <:final if provided> :tokens N}`
- [ ] `{:include-trace true}` → result includes `:trace <vec>` of per-iter entries
- [ ] Default `:trace nil` (no bloat in batches)
- [ ] Deadline propagation: nested `sub-rlm-query` inherits `min(caller, parent-remaining)`
- [ ] Cancel-atom set mid-iter → call returns `{:error :cancelled :iter N}`
- [ ] Deadline expired mid-iter → call returns `{:error :timeout :iter N}`
- [ ] Tool allowlist enforced: calling a non-allowlisted tool sym from sub-RLM SCI code → runtime error
- [ ] Recursion cap (`*max-recursion-depth*`, default 3) enforced; attempt to nest deeper → error
- [ ] Reasoning-spec auto-selection works for providers with/without native reasoning
- [ ] Reentrant semaphore: nested call on same thread does NOT deadlock
- [ ] Unit + integration tests covering each branch
- [ ] `./verify.sh` full pipeline green

---

## Phase 5: Skills + iteration merged

**User stories**: full mode-3 skill invocation through the iterated path

### What to build

Replace Phase 3's single-shot skill path with the iterated path from Phase 4. When `sub-rlm-query` receives `:skills [...]`, it constructs the sub-system-prompt by concatenating skill bodies in **call-site order** with a separator. Routes to `run-sub-rlm-query` with:
- `tool-allowlist` = union of `:skills`' `agent.tools`, validated against parent ctx
- `system-prompt` = joined skill bodies
- `max-iter` = `(min caller skill-agent-max-iter global-cap)`
- `timeout-ms` = `(min caller skill-agent-timeout env-default)`

Enforce `:max-skills-per-call` cap at dispatch. Preserve Phase 3's `:unknown-skill` and dedup logic. Phase 3's single-shot skill path is removed.

### Acceptance criteria

- [ ] `(sub-rlm-query {:prompt "ocr d1" :skills [:ocr-pdf] :max-iter 5})` iterates with skill body as sub-system-prompt
- [ ] `:skills [:a :b]` → sub-system-prompt = `<a body>\n\n---\n\n<b body>` (call-site order)
- [ ] Tool allowlist narrowed to skill's `agent.tools`; other tools rejected at dispatch
- [ ] `:max-skills-per-call 2` enforced; `:skills [:a :b :c]` → error `:too-many-skills`
- [ ] Skill's `agent.max-iter 5`, caller `:max-iter 10` → clamped to 5
- [ ] Skill body NEVER appears in parent main RLM system prompt
- [ ] `:skills-loaded` echoed in result
- [ ] Passing `:tools` alongside `:skills` → `:tools` ignored + warning logged
- [ ] Tests cover: body concat order, tool narrowing, max-skills cap, precedence clamping, body-isolation invariant
- [ ] `./verify.sh` full pipeline green

---

## Phase 6: Parallel batch

**User stories**: main RLM parallelizes independent sub-calls for free

### What to build

**Note**: an `sub-rlm-query-batch` SCI binding ALREADY exists at `internal/rlm.clj:589` as an `async/thread` + `async/<!!` implementation accepting a vec of plain string prompts. Phase 6 REPLACES that implementation with a proper `internal/rlm/batch.clj` module: accepts vec of opts maps, uses the reentrant semaphore, propagates cancel-atom and deadline, per-item error maps.

Build `internal/rlm/batch.clj` with `sub-rlm-query-batch` fn. Takes a vec of opts maps (heterogeneous modes mix freely once iteration lands in Phase 4/5). Spawns one `(future ...)` per item wrapped in the reentrant semaphore from Phase 2B. Propagates `:cancel-atom` from parent env. Preserves order in result vec. Each item returns either its normal result map OR `{:error <keyword> :message <str> :cause <any>}` — batch itself never throws unless opts are malformed.

Use native Clojure `future` macro to ensure dynamic var propagation. Never use raw `ExecutorService.submit`. Timeout clock for each item starts at slot acquisition, not batch submission.

Update `build-system-prompt` LLM block to document the new shape (opts maps, not plain strings) so the RLM knows to pass maps when it needs iteration/skills later.

BREAKING: the old vec-of-strings shape is replaced. `internal/rlm.clj:589` binding removed; `batch.clj` implementation becomes the only one. Document in CHANGELOG.

### Acceptance criteria

- [ ] `(sub-rlm-query-batch [{...} {...} {...}])` runs N items in parallel
- [ ] Semaphore enforced: batch of 20 with cap 8 → at most 8 in-flight HTTP calls across the batch
- [ ] Reentrant: a batched item that itself calls `sub-rlm-query` (nesting) does not deadlock
- [ ] Cancel propagation: setting `:cancel-atom` mid-batch → queued items return `{:error :cancelled}`; in-flight items complete their current HTTP then stop
- [ ] Order preserved: result vec at index i corresponds to input vec at index i
- [ ] Per-item errors surface as `{:error ...}` maps; batch never throws
- [ ] Heterogeneous modes (single-shot + iterated + skill) mix freely in one batch
- [ ] Deadline propagation across batched items (dynamic var via bound-fn*)
- [ ] Two concurrent batches share the same semaphore (global across batches, per query-env)
- [ ] `./verify.sh` full pipeline green

---

## Phase 7: Public hooks/tools/skills API

**User stories**: external code (SCI sandbox, user-facing SVAR API) can register tools, hooks, and skills programmatically

### What to build

Thin public wrappers over the existing v3 hook infra (tools.clj:1411) and the Phase 3 skills registry:

- `svar/register-tool!` — thin delegation to `register-tool-def!`
- `svar/register-hook!` — single hook entry for a specific stage (`:before`/`:after`/`:wrap`) of a specific tool
- `svar/unregister-hook!` — remove by tool sym + stage + id
- `svar/list-hooks` — describe chains attached to a tool
- `svar/register-skill!` — programmatic skill registration (already built in Phase 3, but formally expose)

Document: global hooks (`:on-tool-invoked`, `:on-tool-completed`) remain env-scoped via `query-env!` opts — no runtime global registration. This is intentional.

### Acceptance criteria

- [ ] `(svar/register-tool! 'my-fn {:fn f :doc "..." :args [...]})` adds tool to the tool registry
- [ ] Newly registered tool is callable from SCI sandbox in the next `query-env!` init
- [ ] `(svar/register-hook! 'my-fn {:stage :before :id :x :fn f})` attaches hook
- [ ] `(svar/unregister-hook! 'my-fn :before :x)` removes hook
- [ ] `(svar/list-hooks 'my-fn)` returns `{:before [...] :after [...] :wrap [...]}`
- [ ] `(svar/register-skill! {...})` adds skill to registry with full validation
- [ ] Docstrings on public fns explain globals-are-env-scoped rule
- [ ] Integration test: register tool + hook + skill from outside, verify RLM sees them
- [ ] `./verify.sh` full pipeline green

---

## Phase 8: Verification + docs + release

**User stories**: shippable — no regressions, docs reflect reality, CLAUDE.md updated

### What to build

Run `4clojure`, `humaneval`, `swebench-verified` benches and compare to Phase 1 baseline in `.verification/baseline/`. Fail if regression >5% on any metric. Investigate + fix any regressions before merging.

Write:
- README `## Skills` section with caveman overview + example
- SKILL.md template with commented fields (in README or separate template file)
- Example skill at `./skills/ocr-pdf/SKILL.md` (working, tested)
- Doctest blocks for `sub-rlm-query` modes + `sub-rlm-query-batch` output contract
- CLAUDE.md updates: new fn names, new SCI bindings, caveman rules for skill bodies, init order, settings block

CHANGELOG final entry summarizing the full delta.

### Acceptance criteria

- [ ] Benches run post-changes; trajectories in `.verification/post/`
- [ ] Regression <5% on every bench metric vs baseline
- [ ] README has `## Skills` section with working example
- [ ] `./skills/ocr-pdf/SKILL.md` loads cleanly at `query-env!` init
- [ ] README doctest blocks for `sub-rlm-query` / `sub-rlm-query-batch` pass `clojure -M:test --md README.md`
- [ ] CLAUDE.md updated with new names + init order + skill conventions
- [ ] CHANGELOG has a final summary entry
- [ ] `./verify.sh` full pipeline green
- [ ] Manual smoke test: start a real SVAR session, load a skill, invoke it via main RLM in a batched call, observe manifest + skill invocation + batched parallelism working end-to-end
