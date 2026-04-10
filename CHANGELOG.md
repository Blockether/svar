# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `rlm/query-env!`: `:eval-timeout-ms` opt to override SCI eval timeout per call. Clamped to `[1s, 30min]` at the API boundary; throws on non-integer input. Nested `query-env!` calls inherit the outer binding.
- `rlm.schema/*eval-timeout-ms*` dynamic var replacing the former hardcoded `EVAL_TIMEOUT_MS` constant.
- `rlm.schema/MIN_EVAL_TIMEOUT_MS` (1s) and `rlm.schema/MAX_EVAL_TIMEOUT_MS` (30min) — hard bounds to prevent runaway SCI futures.
- SCI sandbox: `search-entities`, `get-entity`, `list-relationships` bound from existing `rlm.db` fns.
- `pageindex.vision/extract-text-from-pdf`: `:extraction-strategy` enum validation (throws on non-`#{:vision :ocr}`).

### Changed
- `internal/llm.clj` `shared-http-client`: HTTP/1.1 pin now documented as load-bearing for OCR. Mirror note added in `pageindex/vision.clj` above the OCR section.

### Deferred (planned — see `.omc/plans/autopilot-impl.md`)
- Git runtime wiring (`ingest-git!` + SCI tools: `search-commits`, `commit-history`, `file-history`, `commits-by-ticket`, `blame`) — requires `babashka.process` dep decision.
- Pluggable Q-reward + trajectory scoring through `query-env!`.
- Restore GC + `:iteration.var/schema-version` + re-exec mode — schema change; needs review.
- Flip `:extract-entities?` default to `true` — breaking; needs explicit consent.

## [v0.1.3] - 2026-03-15

### Changed
- feat: validate-data TYPE_REF recursion
- release: update version files for v0.1.2


## [v0.1.2] - 2026-03-12

### Changed
- ci: upgrade deploy workflow with Clojars existence check and version bumps
- docs: update CHANGELOG.md for v0.1.1
- crop logo white space
- Update logo width in README.md
- Update logo size in README.md
- Update README.md
- update logo
- feat(pageindex): add page range
- fix(examples): syntax
- Fix README: subtitle layout, non-breaking function names, and anchor links in table
- Fix CI env vars
- Reproduction
- Questionify and major refactor
- Fix ci/readme configuration
- Add RLM, spec fixes
- Initial commit


## [v0.1.1] - 2026-03-12

### Changed
- crop logo white space
- Update logo width in README.md
- Update logo size in README.md
- Update README.md
- update logo
- feat(pageindex): add page range
- fix(examples): syntax
- Fix README: subtitle layout, non-breaking function names, and anchor links in table
- Fix CI env vars
- Reproduction
- Questionify and major refactor
- Fix ci/readme configuration
- Add RLM, spec fixes
- Initial commit


[Unreleased]: https://github.com/Blockether/svar/compare/v0.1.3...HEAD
[v0.1.1]: https://github.com/Blockether/svar/releases/tag/v0.1.1
[v0.1.2]: https://github.com/Blockether/svar/releases/tag/v0.1.2
[v0.1.3]: https://github.com/Blockether/svar/releases/tag/v0.1.3
