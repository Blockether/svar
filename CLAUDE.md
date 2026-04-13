# AGENTS.md

## Environment Rules

### Blockether One LLM Endpoint

Blockether One = live LLM endpoint. Always available. Rules:

1. **README examples must execute** — `(svar/ask! ...)`, `(svar/abstract! ...)`, etc. No `(comment ...)` wrappers.
2. **No `lazytest/skip=true`** — all test blocks runnable. Never add skip.
3. **Config creation only exception** — `(def config ...)` → `(comment ...)` OK (API keys env-specific). Downstream code runs.

## Communication Style

**REQUIRED. Non-negotiable.** [caveman skill](https://github.com/JuliusBrussee/caveman/blob/main/skills/caveman/SKILL.md).

Ultra-caveman for this file. ~75% token cut. Substance stays, fluff dies.

### Rules

Drop: articles, filler, pleasantries, hedging, conjunctions. Abbrev (DB/auth/config/req/res/fn/impl). → for causality. 1 word when enough. Code unchanged.

Pattern: `[thing] [action] [reason]. [next step].`

### Scope

| Context | Style | Why |
|---------|-------|-----|
| **API docstrings** (public vars, README) | **Normal English** | Dev-facing. Clear, precise. |
| **Log messages** (`trove/log!`) | **Caveman full** | Machine consumers. Zero filler. |
| **Internal code comments** | **Caveman full** | Token budget. Reader scans. |

Escalate → full sentences only: destructive actions, arch trade-offs, user asks deep explanation.

## Test Framework

- **lazytest**, not `clojure.test`
- Import: `[lazytest.core :refer [defdescribe describe expect it throws?]]`
- All README code blocks → testable + tested

### README Doc Tests

Lazytest markdown doctest. `;;=> ` = assertion.

```bash
clojure -M:test --md README.md   # README tests
clojure -M:test                  # all unit tests
```

- Every `` ```clojure `` block → executed
- `;; => value` → assertion (expr above = value)
- `(comment ...)` → ONLY for config creation
- `lazytest/skip=true` → FORBIDDEN
- API-dep block → restructure: setup runs, API call inside `(comment ...)`

## Verification

`./verify.sh` → full pipeline. No manual checklist.

```bash
./verify.sh              # format → lint → compile-java → test → test-readme → git-check → secrets
./verify.sh --quick      # format + lint only
```

Logs → `.verification/<step>.log`. Exit codes → `.verification/<step>.code`. Fail → stops, shows last 20 lines.

## Logging

- `taoensso.trove` v1.1.0, NOT `taoensso.telemere`
- Pattern: `(trove/log! {:level :info :id ::my-id :data {:key val} :msg "message"})`
- Alias: `[taoensso.trove :as trove]`

## Scope

svar = **structured LLM output + routing**. Nothing DB-shaped, nothing agent-shaped.

Public API surface: `ask!`, `abstract!`, `eval!`, `refine!`, `sample!`, `models!`, spec DSL, guards, humanizers.

RLM / PageIndex / git ingestion / benchmarks → moved to `../vis`. Do NOT pull them back.
