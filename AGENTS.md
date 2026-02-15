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

## Logging

- Use `taoensso.trove` (v1.1.0), NOT `taoensso.telemere`
- Single call pattern: `(trove/log! {:level :info :id ::my-id :data {:key val} :msg "message"})`
- Namespace alias: `[taoensso.trove :as trove]`
