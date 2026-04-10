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
- **Git runtime wiring (W2 + W2b multi-repo).** `rlm/ingest-git!` opens git repos via JGit (no shell-out), reads commits, stores them as `:event` + `:person` + `:file` entities with relationships, and attaches each open `Repository` to the env keyed by repo-name. **Multi-repo by default** — call `ingest-git!` multiple times with distinct `:repo-name` values and every attached repo remains queryable. `.gitignore` does not affect JGit, so repos living inside gitignored subdirectories (e.g. `external-repos/foo/.git`) work transparently. The SCI sandbox exposes seven git query tools — all prefixed `git-` — that remain invisible unless `ingest-git!` has been called:
  - DB-backed (cross-repo, scope via `:document-id`): **`git-search-commits`**, **`git-commit-history`**, **`git-commits-by-ticket`**
  - JGit-backed (auto-dispatch, no `:repo` opt needed): **`git-file-history`**, **`git-blame`**, **`git-commit-diff`**, **`git-commit-parents`**
  - Path-based tools auto-route by path: single-repo mode accepts relative or absolute paths; multi-repo mode requires an absolute path inside one attached repo's worktree (throws `:rlm/no-repo-for-path` with `:reason :relative-path` otherwise).
  - SHA-based tools auto-route via real object-database presence check (not just SHA syntax validity). Ref names like `HEAD` are ambiguous in multi-repo mode and throw `:rlm/ambiguous-ref`.
  - System prompt renders one `GIT REPO` context block per attached repo (name, path, head short-sha, branch, commits-ingested). In multi-repo mode the tool-doc list advertises "pass ABSOLUTE path" guidance.
  - `dispose-env!` closes every attached `Repository` and clears both atoms.
  - New dep: `org.eclipse.jgit/org.eclipse.jgit 6.10.0`.
- `rlm.schema` commit attrs: `:commit/parents` (many strings, for graph walking) and `:commit/author-email` (denormalized for `db-search-commits` queries by author).
- `rlm.db/db-search-commits` — query commit entities by `:category`, `:since`, `:until`, `:ticket`, `:path`, `:author-email`, `:document-id`, `:limit`. Backs the `search-commits` / `commit-history` / `commits-by-ticket` SCI tools.
- `rlm.db/db-commit-by-sha` — SHA-prefix lookup of a single commit entity.

### Changed
- `internal/llm.clj` `shared-http-client`: HTTP/1.1 pin now documented as load-bearing for OCR. Mirror note added in `pageindex/vision.clj` above the OCR section.
- `rlm/git.clj` rewritten: **replaced `clojure.java.shell/sh` with JGit interop.** Pure parsers (`parse-commit-message`, `extract-ticket-refs`, `prefix->category`, `commit->entity`, `ingest-commits!`) preserved and unit-tested. New IO surface: `open-repo`, `git-available?`, `read-commits`, `head-info`, `blame`, `commit-diff`, `file-history`, `commit-parents`. The old `read-git-log` / custom `git log --format=sha:%H%n...` string parser is gone.

### Deferred (planned — see `.omc/plans/autopilot-impl.md`)
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
