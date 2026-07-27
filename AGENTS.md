# Svar repository guidance

Svar owns structured LLM output and routing, not databases, agents, ingestion, benchmarks, guards, or removed CoD/CoVe APIs.

## Contracts

- Blockether One is live and expected to be available.
- Never add `lazytest/skip=true`.
- Use Lazytest, not `clojure.test`.
- Final verification is `./verify.sh`.

## Logging

Use `taoensso.trove` v1.1.0, not Telemere.
