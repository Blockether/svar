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

## Logging

- Use `taoensso.trove` (v1.1.0), NOT `taoensso.telemere`
- Single call pattern: `(trove/log! {:level :info :id ::my-id :data {:key val} :msg "message"})`
- Namespace alias: `[taoensso.trove :as trove]`
