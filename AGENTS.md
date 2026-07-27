# Svar repository guidance

Svar covers structured LLM output and routing. It does not own databases, agents, RLM/PageIndex/git ingestion, benchmarks, guards, humanizers, or removed CoD/CoVe-style APIs.

## Communication

Use compact caveman style for agent responses, internal comments, and log messages: remove filler, keep substance. Public API docstrings and README prose use clear normal English. Use full sentences for destructive actions and architecture trade-offs.

## Vis-native execution

- Use Vis native tools: `grep` first when location is unknown, then `struct_index`/`cat`; edit supported Clojure with `struct_patch`; use `lint_code`, `format_code`, and `run_tests` rather than shell replicas.
- A managed Clojure REPL is guaranteed. Read live session state, reuse/start it with `repl`, reproduce with `repl_eval`, reload edited namespaces, and execute the changed path. Use the smallest relevant test when REPL proof is insufficient.
- Preserve unrelated work and stop only resources you started.

## Live endpoint and README

- Blockether One is live and expected to be available.
- README examples such as `svar/ask!` and `svar/ask-code!` must execute; do not hide them in `comment` forms.
- The only `comment` exception is environment-specific config creation. Downstream example code still executes.
- Never add `lazytest/skip=true`.

## Tests

- Use Lazytest, not `clojure.test`: `defdescribe`, `describe`, `it`, `expect`, `throws?`.
- Every Clojure README block is a doctest. Use `;;=>` assertions.
- Run the smallest relevant test while developing. Final verification is `./verify.sh`; README-only proof is `clojure -M:test --md README.md`.

## Logging

Use `taoensso.trove` v1.1.0, not Telemere:

```clojure
(trove/log! {:level :info :id ::event :data {:key value} :msg "event done"})
```
