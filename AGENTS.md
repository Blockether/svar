# Svar repository guidance

Svar owns structured LLM output and routing. Keep changes inside that boundary: do not reintroduce databases, agents, ingestion, benchmarks, guards, or the removed CoD/CoVe APIs.

## Non-negotiable contracts

- Blockether One is live and expected to be available.
- Use Lazytest, never `clojure.test`; never add `lazytest/skip=true`.
- Use `taoensso.trove` v1.1.0, not Telemere.

## Verification

Run focused checks while iterating, then run `./verify.sh` as the final check.
