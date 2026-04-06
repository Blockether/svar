# AGENTS.md

## Environment Rules

### Blockether One LLM Endpoint

This development environment is automatically wired with **Blockether One** — a live LLM endpoint that is always available. This means:

1. **README code examples that call the LLM should be real** — do NOT wrap them in `(comment ...)` blocks. The LLM endpoint is live and accessible, so examples like `(svar/ask! ...)`, `(svar/abstract! ...)`, `(svar/eval! ...)`, `(svar/refine! ...)` should execute directly.

2. **No `lazytest/skip=true`** — all test blocks in the README must be runnable. Never add skip annotations.

3. **Config creation** is the only exception — `(def config (svar/make-config ...))` may be wrapped in `(comment ...)` since API keys are environment-specific. Everything downstream that uses `config` should run.

## Test Framework

- Use **lazytest**, not `clojure.test`
- Import: `[lazytest.core :refer [defdescribe describe expect it throws?]]`
- All README code blocks should be testable and tested

### README Doc Tests

Lazytest has built-in markdown doctest support. Code blocks in README.md with `;;=> ` assertions are executed and verified automatically.

**Run README tests:**
```bash
clojure -M:test --md README.md
```

**Run all tests (unit + README):**
```bash
clojure -M:test --md README.md
```

**How it works:**
- Every `` ```clojure `` block in README.md is executed by lazytest
- Lines with `;; => value` are assertions (lazytest checks the expression above equals `value`)
- Use `(comment ...)` ONLY for config creation (API keys are env-specific)
- Use `lazytest/skip=true` is FORBIDDEN — all blocks must be runnable
- To skip a block that requires a live API, restructure it so the API-dependent part is inside a `(comment ...)` while the setup code (specs, guards, humanizers) runs normally

## Verification

All verification logic lives in `./verify.sh`. Run it instead of a manual checklist.

```bash
./verify.sh              # Full: format → lint → compile-java → test → test-readme → git-check → secrets
./verify.sh --quick      # Format + lint only (no build/test)
```

Each step writes logs to `.verification/<step>.log` and exit codes to `.verification/<step>.code`.
On failure the script stops, shows the last 20 lines of the failing step's log, and tells you where to look.

### Test count sanity check
`verify.sh` validates that the test suite ran enough cases:
- Lazytest: **~830 cases** (fails if <500)

## Logging

- Use `taoensso.trove` (v1.1.0), NOT `taoensso.telemere`
- Single call pattern: `(trove/log! {:level :info :id ::my-id :data {:key val} :msg "message"})`
- Namespace alias: `[taoensso.trove :as trove]`

## SCI Sandbox (RLM)

The RLM agent runs code in an SCI (Small Clojure Interpreter) sandbox.
Configuration lives in `src/clj/com/blockether/svar/internal/rlm/tools.clj`.

### Adding namespaces to SCI

**Simple namespaces** (clojure.string, clojure.set, clojure.walk, charred.api, fast-edn.core):
```clojure
;; ns->sci-map pulls all public non-macro vars automatically
'clojure.string (ns->sci-map 'clojure.string)
```

**Complex namespaces** (zprint, anything with macros/.cljc/rewrite-clj):
```clojure
;; ns->sci-map will crash. Use requiring-resolve per-fn:
'zprint.core (let [r #(deref (requiring-resolve (symbol "zprint.core" (str %))))]
               {'zprint-str (r 'zprint-str)
                'zprint (r 'zprint)})
```

**Namespace aliases** (so model can use `str/split` instead of `clojure.string/split`):
```clojure
:ns-aliases {'str 'clojure.string
             'edn 'fast-edn.core
             'zp 'zprint.core}
```

**Class imports** (so model can use `Math/sqrt` instead of `java.lang.Math/sqrt`):
```clojure
;; Register class under full name in :classes
:classes {'java.lang.Math Math ...}
;; Then import the bare name
:imports '{Math java.lang.Math ...}
```
Follow Babashka convention: quoted map `'{BareName fqcn}`.
See `babashka/impl/classes.clj` for the full list.

### Proper SCI APIs (reference from babashka source)

- `sci/copy-var` - copies a var with meta (doc, arglists). Use for individual fns.
- `sci/create-ns` - creates a namespace object for attaching vars.
- `sci/new-dynamic-var` - for dynamic vars like `*print-right-margin*`.
- `sci/intern` - interns a value as a SCI var in a namespace.
- `:deny` - list of symbols to block: `'[require import ns eval load-string read-string]`

### What NOT to do

- Never use `ns->sci-map` on heavy/macro-heavy libs (zprint, rewrite-clj, spec)
- Never use real `clojure.pprint` in SCI - too heavy. Use zprint-backed fns instead.
- Never expose `read-string` from clojure.core (code execution). Use `fast-edn.core/read-string` (data only).

## Benchmarks

Run benchmarks one at a time (sequential), not parallel JVMs:
```bash
clojure -M:bench -- --bench 4clojure --agent query-env --provider blockether --model glm-5-turbo
clojure -M:bench -- --bench humaneval --agent query-env --provider blockether --model glm-5-turbo
clojure -M:bench -- --bench swebench-verified --agent query-env --provider blockether --model glm-5-turbo
```

Bench layout: `bench/com/blockether/svar/bench/benches/{fourclojure,humaneval,swebench_verified}.clj`
Common: `bench/com/blockether/svar/bench/common.clj`
Runner: `bench/com/blockether/svar/bench/runner.clj`

Trajectories saved as EDN to `bench/trajectories/{bench}/{model}/{run-ts}/{task-id}.edn`.
