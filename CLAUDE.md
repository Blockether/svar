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

### Scope — CRITICAL

| Context | Style | Why |
|---------|-------|-----|
| **RLM iterations** (thinking, analysis, code review) | **Caveman full** | Token budget real. Waste = slower, costlier. |
| **RLM final answer** (`:final` response) | **Normal English** | User-facing. Direct, factual. No AI filler. |
| **SCI sandbox tool docstrings** | **Caveman lite** | Terse. Short sentences OK. |
| **Agent-to-agent** (logs, traces) | **Caveman full** | Machine consumers. Zero filler. |
| **API docstrings** (public vars, README) | **Normal English** | Dev-facing. Clear, precise. |
| **Log messages** (`trove/log!`) | **Caveman full** | "RLM schema: auto-merged attrs" not "The RLM schema was automatically merged..." |

Escalate → full sentences only: destructive actions, arch trade-offs, user asks deep explanation.

## Test Framework

- **lazytest**, not `clojure.test`
- Import: `[lazytest.core :refer [defdescribe describe expect it throws?]]`
- All README code blocks → testable + tested

### README Doc Tests

Lazytest markdown doctest. `;;=> ` = assertion.

```bash
clojure -M:test --md README.md   # README tests
clojure -M:test --md README.md   # all tests (unit + README)
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

Test count: **~830 cases** (verify.sh fails if <500).

## Logging

- `taoensso.trove` v1.1.0, NOT `taoensso.telemere`
- Pattern: `(trove/log! {:level :info :id ::my-id :data {:key val} :msg "message"})`
- Alias: `[taoensso.trove :as trove]`

## SCI Sandbox (RLM)

RLM agent → SCI sandbox. Config: `src/clj/com/blockether/svar/internal/rlm/tools.clj`.

### Adding namespaces to SCI

**Simple** (clojure.string, clojure.set, clojure.walk, charred.api, fast-edn.core):
```clojure
;; ns->sci-map → all public non-macro vars
'clojure.string (ns->sci-map 'clojure.string)
```

**Complex** (zprint, macros/.cljc/rewrite-clj):
```clojure
;; ns->sci-map crashes. requiring-resolve per-fn:
'zprint.core (let [r #(deref (requiring-resolve (symbol "zprint.core" (str %))))]
               {'zprint-str (r 'zprint-str)
                'zprint (r 'zprint)})
```

**Aliases** (`str/split` not `clojure.string/split`):
```clojure
:ns-aliases {'str 'clojure.string
             'edn 'fast-edn.core
             'zp 'zprint.core}
```

**Class imports** (`Math/sqrt` not `java.lang.Math/sqrt`):
```clojure
:classes {'java.lang.Math Math ...}
:imports '{Math java.lang.Math ...}
```
Babashka convention: `'{BareName fqcn}`. Full list: `babashka/impl/classes.clj`.

### SCI APIs (babashka source)

- `sci/copy-var` — var + meta (doc, arglists). Per-fn.
- `sci/create-ns` — ns obj for vars.
- `sci/new-dynamic-var` — dynamic vars (`*print-right-margin*`).
- `sci/intern` — value → SCI var in ns.
- `:deny` — `'[require import ns eval load-string read-string]`

### DON'T

- `ns->sci-map` on heavy/macro libs (zprint, rewrite-clj, spec) → crash
- `clojure.pprint` in SCI → too heavy. Use zprint.
- `clojure.core/read-string` → code exec. Use `fast-edn.core/read-string` (data only).

## Benchmarks

Sequential only (no parallel JVMs):
```bash
clojure -M:bench -- --bench 4clojure --agent query-env --provider blockether --model glm-5-turbo
clojure -M:bench -- --bench humaneval --agent query-env --provider blockether --model glm-5-turbo
clojure -M:bench -- --bench swebench-verified --agent query-env --provider blockether --model glm-5-turbo
```

Layout: `bench/com/blockether/svar/bench/benches/{fourclojure,humaneval,swebench_verified}.clj`
Common: `bench/com/blockether/svar/bench/common.clj`
Runner: `bench/com/blockether/svar/bench/runner.clj`
Trajectories → EDN at `bench/trajectories/{bench}/{model}/{run-ts}/{task-id}.edn`.
