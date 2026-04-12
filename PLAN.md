# PLAN.md — Skills, Hooks, Tools, Concurrency

Style: caveman-full. Machine + agent context.

## Goal

SVAR gives primitives for tools, hooks, skills. Main RLM decides parallelism + skill selection. Skills = sub-RLM recipes, invoked via `sub-rlm-query` / `sub-rlm-query-batch`. Main context stays tiny — compact skill manifest only, bodies never leak.

---

## Architecture

### Three primitives

1. **Tools** — SCI fns in sandbox. Registered via `register-tool!`. v3 hook infra exists (tools.clj:972+).
2. **Hooks** — `:before`/`:after`/`:wrap` chains per tool + global. Built. Needs public wrappers.
3. **Skills** — sub-RLM recipes in SKILL.md. Main RLM invokes via `sub-rlm-query` with explicit `:skills [...]`. New.

### Encapsulation rule

- Skill **bodies** never touch main RLM context.
- Main system prompt gets compact **manifest**: name + short desc (≤200 chars) per skill.
- Main RLM reads manifest, picks skills, passes to `sub-rlm-query` calls.
- Bodies load only inside the `sub-rlm-query` call that references them.
- Main context bounded: ≈20 tok/skill. 20 skills ≈ 400 tok hard ceiling.

### Who decides what

| Decision | Who | When |
|---|---|---|
| Which skills exist | SVAR loader | `query-env!` init |
| Which skills are loadable | SVAR loader | `query-env!` init (`requires` validation) |
| Which skills to invoke for a query | **Main RLM** | Per `sub-rlm-query` call, explicit `:skills [...]` |
| How to parallelize | **Main RLM** | Via `sub-rlm-query-batch` |
| Concurrency cap | SVAR engine | Via `:concurrency` settings |

No router LLM pass. No auto-activation. Main RLM fully in control.

---

## SKILL.md spec — frozen

```yaml
---
name: ocr-pdf                          # REQ, [a-z0-9-]{1,64}, matches dir (lowercase)
description: |                         # REQ, ≤1024 chars, full detail
  OCR image-based PDFs via GLM-OCR 2-pass. Use when user asks to
  extract text from scanned/image PDFs. Returns cleaned md + code.
description-short: |                   # OPT, ≤200 chars, for main manifest
  OCR image PDFs → cleaned md + code.  # SVAR-only extension. Auto-truncated from description if absent.
compatibility: [svar]                  # REQ. Missing svar → skill ignored.

agent:                                 # sub-RLM config
  tools:      [sub-rlm-query, fetch-document-content, search-documents]
  reasoning:  false
  max-iter:   5
  timeout-ms: 30000

requires:                              # availability gate, validated at query-env! init
  docs: true
  git:  false
  env:  [BLOCKETHER_API_KEY]

version: 1.0.0                         # OPT
license: MIT                           # OPT
---

## System prompt
<body — becomes sub-RLM system prompt when skill loaded>

## When to use
## Procedure
## Pitfalls
## Verification
```

### Load-time validation (runs at `query-env!` init, AFTER SCI ctx built)

Init order is fixed:
1. `create-sci-context` builds SCI registry (all available tools)
2. `load-skills` validates `requires.tools` against the built registry

Reverse order → every skill fails `:missing-tool`. Fixed init sequence, explicit in Step 8.

Validation:
- `compatibility` normalized → set of lowercase strings → must contain `"svar"`.
- `name` lowercase `[a-z0-9-]{1,64}`, matches dir name lowercase. Mixed case anywhere → reject (cross-platform FS gotcha).
- `requires.tools` all in SCI registry → else drop with `:missing-tool`.
- `requires.docs: true` + no docs → drop.
- `requires.git: true` + no repo → drop.
- `requires.env` vars set → drop on miss.
- `agent.max-iter` clamped to global cap.
- `agent.tools` all in SCI registry.
- Body token count ≤ 4000 (≈300 lines) → reject if over.
- Unknown top-level keys → warn, keep.

---

## Discovery paths — frozen

```
PROJECT (walk from cwd to git root):
  .svar/skills/*/SKILL.md          ← SVAR-native, highest precedence
  .claude/skills/*/SKILL.md
  .opencode/skills/*/SKILL.md
  .agents/skills/*/SKILL.md
  skills/*/SKILL.md

GLOBAL:
  ~/.svar/skills/*/SKILL.md
  ~/.claude/skills/*/SKILL.md
  ~/.config/opencode/skills/*/SKILL.md
  ~/.agents/skills/*/SKILL.md

PLUGINS (opt-in via :skills/load-plugins true, default false):
  ~/.claude/plugins/*/skills/*/SKILL.md
```

Collision: project > global > plugin. First `name` wins. Discards logged.

Parser: **clj-yaml** (safe mode — reject `!!java/*` tags). Frontmatter split = handroll (~15 LoC).

### Skill filtering opts on `query-env!`

```clojure
{:skills/allow          [:ocr-pdf :summarize]  ; whitelist (mutex with deny)
 :skills/deny           [:bad-one]             ; blacklist (mutex with allow)
 :skills/roots          [".svar/skills"]       ; restrict discovery paths
 :skills/load-plugins   false}                 ; opt-in to plugin dirs
```

Applied after load, before manifest generation.

---

## `sub-rlm-query` — unified primitive (replaces old `llm-query`)

**NAMING NOTE**: preserves the existing "query" suffix. SCI binding symbol goes from `'llm-query` to `'sub-rlm-query`. The existing Clojure-side local binding `sub-llm-query-fn` in `internal/rlm.clj:140,557` (a cost-optimized pre-bound variant) renames to `cheap-sub-rlm-query-fn` — local-scope, zero external impact.

### Modes

```clojure
;; MODE 1 — cheap single-shot (current llm-query behavior preserved)
(sub-rlm-query "what is 2+2?")
(sub-rlm-query {:prompt "..." :routing {:optimize :cost}})
;   No skills, no tools, no iter. One chat completion.
;   Returns {:content <prose> :code <vec<str>|nil>}.

;; MODE 2 — ad-hoc iterated (tools, no skill) — NEW machinery
(sub-rlm-query {:prompt    "analyze doc 1, extract entities"
                :tools     [fetch-document-content search-documents]
                :max-iter  5})

;; MODE 3 — skill-based (main RLM picked from manifest) — NEW machinery
(sub-rlm-query {:prompt "ocr doc 1, return cleaned md"
                :skills [:ocr-pdf]})
;   Skill body → sub-RLM system prompt.
;   Sub-RLM runs with skill's agent.tools allowlist.
;   Max 2 skills per call (enforced, :max-skills-per-call setting).
```

**MODE 2/3 are new primitives, NOT a rename.** Single-shot is the only existing path today. Building the iterated path is Step 9a below.

### Output contract — uniform, differentiated by spec path

```clojure
{:content      <str|nil>        ; raw LLM text when NO :spec used (prose path, mode 1/2/3)
                                ; nil when :spec used (spec path — structured data in :result)
 :code         <vec<str>|nil>   ; extracted from :content fences, matches ITERATION_SPEC cardinality
                                ; nil when :content nil OR zero fences
                                ; matches schema.clj:317 :spec.cardinality/many — same shape codebase-wide
 :result       <any|nil>        ; parsed spec :result when :spec used (from llm/ask!)
                                ; also :final map when iterated
 :iter         <int>             ; 1 for single-shot, N for iterated
 :tokens       <int>             ; total (sum across iters + nested calls)
 :trace        <vec|nil>         ; iteration log — OPT-IN via {:include-trace true}
                                ; default nil to prevent context bloat in batches
 :skills-loaded <vec|nil>        ; echoed from input, renamed from :skills-used
 :routed/provider-id ...
 :routed/model       ...}
```

**`:content` vs `:spec` path split** (contract mismatch fix):
- No `:spec`: `:content` is raw prose, `:code` auto-extracted from ```clojure fences.
- With `:spec`: `:content` nil, `:code` nil, `:result` holds structured data from `llm/ask!`.
- Current `routing.clj:57` returns `(pr-str (:result r))` as `:content` — WRONG for our contract. Fix: strip that, set `:content nil :result (:result r)` in the spec path.

**`:trace` is NOT in default result.** Iteration log could be 10-50 KB per call × 50 batch items = 2.5 MB bubbling up. Opt-in via `{:include-trace true}`. Default result is slim.

### Recursion depth

Sub-rlm-query can nest. Cap via existing `*max-recursion-depth*` (schema.clj:42). Default 3.
Implementation: existing `depth-atom` pattern at routing.clj:27 — pass through nested calls, increment on entry, decrement on exit.

### Timeout semantics — locked

- `:timeout-ms` = total wall-clock budget for the entire call, including all sub-RLM iterations.
- Sub-RLM iterations carve from that budget. No per-iter multiplier.
- **Nested** `sub-rlm-query` calls inherit `min(caller-specified, parent-remaining)` via dynamic var `*sub-rlm-deadline*` (absolute instant). Child never exceeds parent.
- Separate `:http-timeout-ms` per HTTP request (default 20000).
- Budget exhausted mid-iter → partial result with `:error :timeout`.

### Precedence table (call-site + skill + global)

```
max-iter  = (min caller-max-iter skill-agent-max-iter global-cap)
            default 1 when no skills + no caller (degenerate loop = single-shot)
tools     = skill.agent.tools when :skills passed (caller :tools ignored + warn)
            else caller :tools
            else parent SCI ctx bindings
timeout   = (min caller-timeout skill-agent-timeout env-default-timeout)
skills    = dedupe + reject :nonexistent + (take max-skills-per-call)
```

### Call-site schema — precise

```
:skills [:a :b]   → valid, dedup, validate names exist → else :unknown-skill error
:skills []        → same as absent (mode 1/2)
:skills nil       → same as absent
:skills :none     → same as absent (explicit)
:tools + :skills  → :tools ignored, warn
:max-iter 0       → error :invalid-max-iter
:max-iter N       → clamped to (min N skill.agent.max-iter global-cap)
(zero args)       → error :missing-prompt
```

---

## `sub-rlm-query-batch` — parallel fan-out (replaces old `llm-query-batch`)

```clojure
(sub-rlm-query-batch
  [{:prompt "q1"}                                    ; cheap single-shot
   {:prompt "..." :tools [fetch-document-content]}   ; ad-hoc with tools
   {:prompt "ocr d1" :skills [:ocr-pdf]}             ; skill
   {:prompt "ocr d2" :skills [:ocr-pdf]}             ; skill parallel
   {:prompt "summarize d3" :skills [:summarize]}])
;=> [{:content :code ...} {...} {...} {...} {...}]   ; same order
```

### Implementation requirements

- Heterogeneous modes mix freely.
- Parallelism via `sci.addons.future` + **query-env-scoped reentrant semaphore**.
- Semaphore bounded by `:concurrency.max-parallel-llm` (default 8).
- **Reentrant keyed by thread id** — same thread can re-enter without blocking. Prevents deadlock when nested `sub-rlm-query` calls contend with outer batch items.
- Scope: ONE semaphore per `query-env!` session. Shared across batches + nested calls.
- Slot held during actual HTTP request only. Iterating sub-RLMs release between iters.
- **`:timeout-ms` clock starts at slot acquisition**, not batch submission. Queue wait unbounded by default; optional `:queue-timeout-ms` for bounded queuing.
- **Cancel propagation**: batch reads parent env's `:cancel-atom` (core.clj:1013). Checks before each slot acquisition. In-flight items complete (no HTTP interrupt); queued items return `{:error :cancelled}`.
- **Binding propagation**: use native `(future ...)` macro (captures bindings via `bound-fn*`). Never raw `ExecutorService.submit`. Dynamic vars `*max-recursion-depth*`, `*sub-rlm-deadline*` propagate automatically.
- **Per-item errors**: result vec same size as input. Errored items = `{:error <keyword> :message <str> :cause <any>}`. Batch itself never throws unless opts malformed.
- Order-preserving result vec.

---

## Concurrency — settings, not prompt

### Settings block on `query-env!`

```clojure
(svar/query-env!
  {:concurrency {:max-parallel-llm    8       ; HTTP calls in flight, query-env-scoped reentrant sem
                 :max-skills-per-call 2       ; ceiling on :skills [...] count
                 :default-timeout-ms  30000   ; total per-call wall clock
                 :http-timeout-ms     20000}  ; per HTTP request
   ...})
```

**Four knobs.** No separate pools. Tools use Clojure default thread pool (local, not LLM).

Concurrency is env-scoped, fixed at `query-env!` init. Per-call `:routing` can tune cost/speed but cannot override concurrency caps.

---

## Main RLM system prompt additions — CAVEMAN, locked

Two new caveman blocks for `build-system-prompt` (core.clj:383):

### Block 1 — LLM primitives (always present)

```
LLM:
- (sub-rlm-query "...") — single-shot. {:content :code}
- (sub-rlm-query {:prompt :tools :max-iter :skills}) — iterated w/ opt skills
- (sub-rlm-query-batch [{...} {...}]) — parallel, sem-capped
- Output: {:content :code :result :iter :tokens :skills-loaded}
- :code is vec<str> — matches iter spec cardinality
- (doc sub-rlm-query-batch) for full shape
```

Replaces core.clj:404 `future/pmap/promise/deliver OK.` line.

### Block 2 — Skills manifest (only when ≥1 skill loaded)

```
SKILLS (pass :skills [...] to sub-rlm-query/batch, max 2 per call):
  :ocr-pdf       — OCR image PDFs → md + code
  :summarize     — Per-doc summary → struct + code
  :extract-ents  — Entity extraction → entity vec + code
  :git-triage    — Bug hunt over commits → hypothesis + code
```

Format: `:name — <description-short ≤200 chars>`. One line/skill. No bodies. No `agent`/`requires`.

Hard cap: 20 skills × ~30 tok = ~600 tok worst case. Over 20 → truncate + log discard.

Both blocks follow CLAUDE.md caveman rules: drop articles, one-word-when-enough, no hedging, `→` for causality.

---

## Rename pass — two merged renames

### (A) `fetch-content` → `fetch-document-content`

Files touched (verified via grep):
- `src/clj/com/blockether/svar/internal/rlm/tools.clj` — `make-fetch-content-fn` + SCI binding `'fetch-content` at line 500
- `src/clj/com/blockether/svar/internal/rlm/db.clj`
- `src/clj/com/blockether/svar/internal/rlm/core.clj` — system prompt docs
- `src/clj/com/blockether/svar/internal/rlm/schema.clj`
- `src/clj/com/blockether/svar/internal/rlm/trajectory.clj`
- `test/com/blockether/svar/internal/rlm_test.clj`
- `README.md` — doctest blocks
- `CHANGELOG.md` — BREAKING entry
- **Audit before committing**: `grep -rn fetch-content bench/ scripts/ docs/ CLAUDE.md`
- **Datalevin persisted trajectories**: bump schema version; document breakage.
- **Bench trajectories** (`bench/trajectories/**/*.edn`): literal strings inside persisted code; sed-based migration script OR note as breakage.

### (B) `llm-query` → `sub-rlm-query` + `llm-query-batch` → `sub-rlm-query-batch`

Files touched (full grep verified):
- `src/clj/com/blockether/svar/internal/rlm/routing.clj` — `make-routed-llm-query-fn` → `make-routed-sub-rlm-query-fn`, fn literal name, trove IDs `::sub-llm-call` → `::sub-rlm-call`, `::sub-llm-response` → `::sub-rlm-response`
- `src/clj/com/blockether/svar/internal/rlm/tools.clj` — SCI binding `'llm-query` → `'sub-rlm-query` at line 489, docstrings, `create-sci-context` parameter name `llm-query-fn` → `sub-rlm-query-fn`
- `src/clj/com/blockether/svar/internal/rlm/core.clj` — import at line 12, call site at line 78, system prompt docs, trove IDs
- **`src/clj/com/blockether/svar/internal/rlm.clj`** (was missing from previous plan) — 3 call sites:
  - `rlm.clj:139` — `llm-query-fn` local binding → `sub-rlm-query-fn`
  - `rlm.clj:140` — `sub-llm-query-fn` (cost-optimized variant) → `cheap-sub-rlm-query-fn`
  - `rlm.clj:557` — second cost-optimized instance → `cheap-sub-rlm-query-fn`
- `test/com/blockether/svar/internal/rlm_test.clj` — `make-routed-llm-query-fn-test` block at line 663, call sites at 669, 679
- `README.md` — doctest blocks
- `CHANGELOG.md` — BREAKING entry
- `CLAUDE.md` — any references
- **Audit**: `grep -rn 'llm-query\|::sub-llm' src/ test/ bench/ scripts/ docs/ CLAUDE.md README.md`
- **`svar/core.clj` public API audit — DONE**: grep returned zero matches. `llm-query` and `fetch-content` are **internal-only symbols**. Rename does NOT break external library consumers. Good news, no migration burden.

### Trove log IDs — enumerated

Current (routing.clj:32, 39, 47, 62):
- `::sub-llm-call` → `::sub-rlm-call`
- `::sub-llm-response` → `::sub-rlm-response`

External log monitors / metrics dashboards hard-coded to these IDs will break silently. Document in CHANGELOG.

### Rationale

- `fetch-content` → `fetch-document-content`: unambiguous, matches `search-documents`, makes room for `fetch-page-content`/`fetch-commit-content`.
- `llm-query` → `sub-rlm-query`: naming reflects reality. Every call is a (possibly degenerate) sub-RLM invocation. Preserves "query" suffix for continuity.
- `sub-llm-query-fn` → `cheap-sub-rlm-query-fn`: local binding rename. Avoids `sub-sub-rlm` confusion. Cost-optimized variant called out in the name.
- No backward-compat aliases. Clean break (per CLAUDE.md).

---

## Sub-RLM SCI context scoping — design spike

**Question**: when `sub-rlm-query {:skills [:ocr-pdf]}` runs, sub-RLM gets narrowed tools from `agent.tools`. How?

**Finding (W5)**: `create-sci-context` (tools.clj:476) takes `[context-data llm-query-fn db-info-atom conversation-ref-atom custom-bindings]`. **No allowlist param.** Bindings merged internally at tools.clj:529. Full `sci/init` is expensive (huge namespace map via `sci-future/install`).

### Options

**Option A — fresh ctx per call**
- New `create-sci-context` call with narrowed bindings.
- Cost: full `sci/init` per call (likely 50-200ms based on ns count).
- **Likely non-viable for batches or nested calls.**

**Option B — cache ctxs by allowlist hash**
- Build once per unique allowlist, reuse.
- Hazard: sub-RLM `def`s mutate ns — need child ctx or snapshot-restore pattern.
- Complex state management.

**Option C — single ctx, runtime allowlist gate**
- Parent ctx reused wholesale.
- Dispatch wrapper checks `(contains? allowlist tool-sym)` before invoke.
- Cheapest. Weakest isolation — `(resolve 'forbidden)` still works.
- **Likely only viable option.**

### Spike requirements (Step 7)

1. Benchmark `create-sci-context` + `sci/init` cost via `criterium`.
2. If >50ms → Option A rejected.
3. Option B viability depends on mutation isolation — spike the def/undef cycle.
4. Pick winner, document decision + benchmark numbers in this section (replace spike text).
5. **Fallback**: if spike inconclusive, default to Option C + mark as tech-debt. Forward progress guaranteed.

---

## Hooks primitives — public wrappers

Existing infra: `register-tool-def!` at tools.clj:1411 takes `:before`/`:after`/`:wrap`. Works. Needs public surface.

### New public API

```clojure
(svar/register-tool!  'my-fn {:fn f :doc "..." :args [...]})
(svar/register-hook!  'my-fn {:stage :before :id :my-hook :fn f})
(svar/unregister-hook! 'my-fn :before :my-hook)
(svar/list-hooks      'my-fn)
(svar/register-skill! {:name :x :description "..." :body "..." :agent {...} :requires {...}})
```

`register-skill!` covers the **asymmetry** — file-based discovery is not the only path. Programmatic registration uses the same validation + registry.

**Globals**: existing `query-env!` opts handle `:on-tool-invoked` / `:on-tool-completed`. Public API does NOT add runtime registration for globals (env-scoped only). Document.

Thin delegation to tools.clj v3 fns. No new infra.

---

## Build order — incremental commits

Each step = one commit. `./verify.sh` between. Low-risk steps (doc/prompt edits) use `--quick`.

### Step 0 — Benchmark baseline
- Run `4clojure`, `humaneval`, `swebench-verified` with current code.
- Capture trajectories in `.verification/baseline/`.
- No code change. Reference for Step 12 regression check.

### Step 1 — Concurrency settings plumbing
- `:concurrency` opt on `query-env!` schema (schema.clj).
- Thread through routing.clj.
- Dynamic var `*sub-rlm-deadline*` (absolute wall-clock instant) for nested budget inheritance.
- Defaults: `{:max-parallel-llm 8 :max-skills-per-call 2 :default-timeout-ms 30000 :http-timeout-ms 20000}`.
- Reentrant semaphore (thread-id keyed) as query-env-scoped singleton.
- Tests: settings propagation, reentrancy (no deadlock on nested acquire), deadline inheritance.

### Step 2 — Rename `fetch-content` → `fetch-document-content`
- Full grep sweep (src/test/bench/scripts/docs/CLAUDE.md/README).
- Datalevin schema version bump; bench trajectory migration script.
- CHANGELOG BREAKING.
- `./verify.sh` full.

### Step 3 — Rename `llm-query` → `sub-rlm-query` + `llm-query-batch` → `sub-rlm-query-batch`
- **Include `internal/rlm.clj`** (3 call sites). Rename local `sub-llm-query-fn` → `cheap-sub-rlm-query-fn`.
- Update routing.clj (`make-routed-llm-query-fn` → `make-routed-sub-rlm-query-fn`), tools.clj (SCI binding, create-sci-context param), core.clj, all trove IDs (`::sub-llm-call` → `::sub-rlm-call`, `::sub-llm-response` → `::sub-rlm-response`).
- Update test file (`make-routed-llm-query-fn-test` → `make-routed-sub-rlm-query-fn-test` + call sites).
- README doctests + CLAUDE.md references.
- CHANGELOG BREAKING.
- `./verify.sh` full.
- **This is a mechanical rename only.** No behavior change. MODE 2/3 remain unimplemented.

### Step 4 — `:code` extraction + contract differentiation
- Post-call parser: regex `(?s)```clojure\s*\n(.*?)```` → vec of captures.
- Populate `:code` as `vec<str>|nil` matching ITERATION_SPEC cardinality (schema.clj:317).
- **Fix spec-path bug**: remove `(pr-str (:result r))` from routing.clj:57. Spec path → `:content nil :result (:result r) :code nil`. No-spec path → `:content <prose> :code <vec|nil> :result nil`.
- Existing callers reading `:content` in no-spec mode unaffected.
- Tests: zero-fence → `:code nil`; single-fence → 1-elem vec; multi-fence → N-elem vec; malformed → graceful skip; spec path → `:content nil :code nil`.

### Step 5 — `sub-rlm-query-batch`
- New fn in new ns `internal/rlm/batch.clj` (keeps routing.clj focused on single-call path).
- Input: vec of opts maps, heterogeneous modes.
- Reentrant semaphore (Step 1) bounded by `:max-parallel-llm`, query-env-scoped.
- Timeout clock starts at slot acquisition. Optional `:queue-timeout-ms`.
- Uses native `(future ...)` macro for binding propagation.
- Per-item `{:error ...}` maps; batch never throws.
- Reads parent env `:cancel-atom` (core.clj:1013); propagates cancel to queued items.
- Order-preserving result vec.
- Tests: ordering, sem cap across batches, reentrancy no-deadlock, timeout timing, mixed modes, errors per item, cancel propagation.

### Step 6 — System prompt shrink + LLM block
- `build-system-prompt` (core.clj:383): drop `future/pmap/promise` line (core.clj:404).
- Add caveman LLM block (see "Main RLM system prompt additions" section).
- Zero skill info yet (manifest lands Step 10).
- `--quick` verify OK.

### Step 7 — SCI ctx scoping spike
- Benchmark `create-sci-context` + `sci/init` cost via `criterium`.
- Prototype Options A/B/C.
- Write benchmark numbers + decision into this doc's "Sub-RLM SCI context scoping" section.
- If inconclusive → default Option C, mark tech-debt.
- No production code yet.

### Step 8 — `internal/rlm/skills.clj` (loader)
- `scan-skill-paths` — walk discovery paths, respect `:skills/roots`, `:skills/load-plugins`.
- `parse-skill` — read SKILL.md, split frontmatter/body, **clj-yaml safe mode**.
- `normalize-compatibility` — handle scalar/seq/string/keyword YAML shapes.
- `validate-skill` — compatibility, name-lowercase, requires.tools (against SCI registry), requires.docs/git/env, max-iter clamp, body token count ≤4000.
- `load-skills` — scan + parse + validate + dedupe + filter by `:skills/allow`/`:skills/deny`.
- `skill-registry` — map in the rlm-env, populated at `query-env!` init.
- **Init order**: SCI ctx built FIRST (Step 8a), skills loaded SECOND (Step 8b). Explicit call sequence in `query-env!`.
- Add `clj-yaml` to `deps.edn`.
- Tests: fixtures covering all valid/invalid/collision cases:
  - valid minimal / all optional fields
  - missing name / description / compatibility
  - compatibility without svar / as scalar / as seq / as map
  - invalid YAML / missing `---` / BOM / CRLF
  - duplicate names (collision)
  - unknown top-level keys
  - `requires.tools` missing from registry
  - `requires.docs`/`git`/`env` unmet
  - over `max-iter` clamp
  - description > 1024 chars (warn, truncate in manifest)
  - body > 4000 tokens (reject)
  - YAML injection attack (`!!java/*` tag → rejected by safe mode)
  - mixed-case dir name

### Step 9a — Build `run-sub-rlm-query` iterated primitive (NEW MACHINERY)
- **This is the biggest step.** Builds what the plan earlier assumed existed.
- New module: `internal/rlm/sub.clj` (or inline in routing.clj — TBD).
- Signature:
  ```clojure
  (run-sub-rlm-query
    {:parent-env    <rlm-env>
     :system-prompt <str>
     :tool-allowlist #{'sym ...}   ; from Step 7 decision
     :prompt        <str>
     :max-iter      <int>
     :deadline      <inst>         ; absolute wall clock
     :routing       <map>
     :cancel-atom   <atom>
     :include-trace? <bool>})
  ```
- Constructs child `rlm-env` with narrowed SCI ctx (per Step 7 decision) or uses runtime gate.
- Builds initial messages: `[{:role "system" :content system-prompt} {:role "user" :content prompt}]`.
- Loops via existing `run-iteration` (core.clj:468) until `:final` or `max-iter` reached or deadline expired or cancel-atom set.
- **Iteration spec selection**: `(if (provider-has-reasoning? (:router child-env)) ITERATION_SPEC_CODE_ONLY ITERATION_SPEC)` — reuse existing helper at routing.clj.
- Collects iterations into trace vec (only retained if `:include-trace?`).
- Propagates nested sub-rlm-query calls via `*sub-rlm-deadline*` dynamic var.
- Returns `{:content :code :result :iter :tokens :trace :skills-loaded ...}`.
- Uses reentrant semaphore for HTTP slot acquisition (Step 1).
- Tests: 1-iter cheap path, multi-iter with tools, deadline hit, cancel mid-iter, nested sub-rlm-query, recursion depth cap, reasoning/non-reasoning spec selection.

### Step 9b — Wire skill bodies into `run-sub-rlm-query`
- Apply Step 7 ctx scoping decision for tool narrowing.
- When `sub-rlm-query {:skills [:a :b]}`: build sub-RLM system prompt = **call-site order** concat of skill bodies with separator. `a` body first, then `b` body. Document: later overrides earlier on conflict.
- `tool-allowlist` = union of `:skills`' `agent.tools` (validated against parent ctx).
- Clamp `max-iter` and `timeout-ms` per precedence table.
- Enforce `:max-skills-per-call` cap → error if exceeded.
- `:skills-loaded` echoed in result.
- Reject unknown skill names with `:unknown-skill` error.
- Tests: skill body reaches sub-RLM system prompt, tools allowlisted, bodies never leak to parent context, call-site order respected, max-skills enforced, unknown-skill error, dedup dupes.

### Step 10 — Main RLM skills manifest injection
- Edit `build-system-prompt` (core.clj:383) to add caveman SKILLS block when ≥1 skill loaded.
- Format per "Main RLM system prompt additions" section.
- Auto-truncate `description` to 200 chars if `description-short` absent.
- Hard cap: 20 skills in manifest; over 20 → truncate alphabetically, log discard.
- Tests: manifest present when skills loaded, absent otherwise, token budget, truncation, caveman formatting.

### Step 11 — Hooks + register-skill! public wrappers
- `svar/register-tool!`, `svar/register-hook!`, `svar/unregister-hook!`, `svar/list-hooks`.
- `svar/register-skill!` — programmatic skill registration (validates via same path as file loader).
- Document: globals stay as `query-env!` opts (no runtime registration path).
- Thin delegation to tools.clj v3 fns + Step 8 skills loader.
- Tests: registration, unregistration, chain ordering, programmatic skill register.

### Step 12 — Benchmark verification
- Re-run `4clojure`, `humaneval`, `swebench-verified`.
- Compare to Step 0 baseline.
- Fail if regression >5% on any metric.
- Trajectories to `.verification/post/`.
- Investigate regressions before shipping.

### Step 13 — Docs + README
- README `## Skills` section.
- SKILL.md template with commented fields.
- Example `./skills/ocr-pdf/SKILL.md`.
- Doctest blocks for `sub-rlm-query` modes + `sub-rlm-query-batch`.
- CLAUDE.md updates: new fn names, new SCI bindings, caveman rules for skill bodies.
- `--quick` verify OK.

---

## Open questions — real list

1. **Sub-RLM ctx scoping** — Step 7 spike outcome. Likely Option C (runtime gate). Affects Step 9a/9b.
2. **Bench trajectory migration** — sed script vs accept breakage. Decide in Step 2.
3. **Reasoning mode mismatch** — sub-RLM picks spec via `provider-has-reasoning?`. Wired in Step 9a.
4. **Skill hot-reload** — out of scope v1.
5. **`:skills/roots` override** — add to Step 8 (small cost).
6. **Plugin skill trust** — `:skills/load-plugins false` default. Revisit if community plugins emerge.

## Out of scope (v1)

- `svar/register-context!` user-defined state predicates.
- SKILL.md hot-reload.
- Bundled skill scripts (Python/bash). SCI-Clojure only.
- AGENTS.md parsing — separate concept.
- Router LLM pass for auto-skill-selection — dropped.
- Skill versioning beyond project>global>plugin precedence.
- Skill dependencies / composition declarations.
- Global hooks runtime registration (env-scoped only).
- Native-image compat for `clj-yaml`.

---

## Reference card — what main RLM sees (caveman, locked)

### Block 1 — LLM primitives (always)

```
LLM:
- (sub-rlm-query "...") — single-shot. {:content :code}
- (sub-rlm-query {:prompt :tools :max-iter :skills}) — iterated w/ opt skills
- (sub-rlm-query-batch [{...} {...}]) — parallel, sem-capped
- Output: {:content :code :result :iter :tokens :skills-loaded}
- :code is vec<str>
- (doc sub-rlm-query-batch) for full shape
```

### Block 2 — Skills manifest (when ≥1 loaded)

```
SKILLS (pass :skills [...] to sub-rlm-query/batch, max 2 per call):
  :ocr-pdf       — OCR image PDFs → md + code
  :summarize     — Per-doc summary → struct + code
  :extract-ents  — Entity extraction → entity vec + code
```

No bodies. No `agent`/`requires`. No concurrency lecture. Engine enforces caps.

### Inside `sub-rlm-query` (hidden from main RLM)

```
parent ctx → narrowed SCI ctx (tool allowlist via Step 7 decision) →
sub-RLM system prompt = skill bodies (call-site order) →
run-iteration loop bounded by timeout + max-iter + cancel-atom →
extract :code from :content →
return {:content :code :result :iter :tokens :skills-loaded ...}
```

Bodies bounded to call frame. Main context never sees them.

---

## Summary of W-findings (third grill pass, from actual code inspection)

| # | Finding | Status in plan |
|---|---|---|
| W1 | MODE 2/3 are new machinery, not rename — Step 9 split into 9a (build iterated primitive) + 9b (wire skills) | PATCHED |
| W2 | `internal/rlm.clj` 3 call sites missing from rename list | PATCHED — explicit in Step 3 |
| W3 | Existing `sub-llm-query-fn` naming collision — renamed to `cheap-sub-rlm-query-fn` | PATCHED |
| W4 | `:content` contract wrong for spec path — `(pr-str (:result r))` bug | PATCHED — Step 4 split path |
| W5 | `create-sci-context` no allowlist — Option A likely non-viable | PATCHED — Step 7 spike benchmark mandatory |
| W6 | `execute-code` takes single string, caller iterates — wording fix | PATCHED |
| W7 | `cancel-atom` wiring into batch | PATCHED — Step 5 explicit |
| W8 | Reasoning spec selection via `provider-has-reasoning?` for sub-RLM | PATCHED — Step 9a |
| W9 | `svar/core.clj` public API clean — no external breakage | PATCHED — noted in Rename (B) |
| W10 | Trove log IDs enumerated (`::sub-llm-*` → `::sub-rlm-*`) | PATCHED — explicit list |
| W11 | T2 binding-propagation retracted — Clojure `future` handles it natively | PATCHED — explicit note |
