# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

**Hook system v3** — per-tool `:before`/`:after`/`:wrap` chains and a global lifecycle `:hooks` map, both wired through `register-env-fn!` and `query-env!`. Policy (deny / transform / recover) lives in per-tool chains; observation (logging / metrics / UI streaming) lives in global hooks. See `rlm.tools/normalize-hooks` / `execute-tool` / `wrap-tool-for-sci` for the full engine.

Per-tool chains (attached via `register-env-fn!` tool-def):
  - `:before` — each hook receives the invocation map, may return `{:args v}` (transform args), `{:skip v}` (short-circuit, :after still runs), `{:error e}` (short-circuit to error), or nil.
  - `:after` — receives the outcome map, may return `{:result v}`, `{:error e}`, `{:result v :error nil}` (recover from failure), or nil. Independent sequential chain, NOT paired setup/teardown.
  - `:wrap` — ring-style middleware, vector is vec-LAST = outermost (matches `(-> handler inner outer)` convention).
  - Idempotent layered registration: calling `register-env-fn!` twice on the same symbol merges hooks by `:id`, same id replaces in place, new ids append, old ids preserved.
  - Throws caught and logged; iteration loop continues.
  - `(:invoke inv-map)` — call another registered tool through its own per-tool chain, bypassing global observers. Depth-tracked via explicit `query-ctx` (`:depth`), cap `MAX_HOOK_DEPTH` = 8.

Global lifecycle hooks (`query-env!` `:hooks {:on-iteration ... :on-cancel ...}`) — all pure observers, return ignored, exceptions swallowed:
  - `:on-iteration` — fires after `store-iteration!` with `{:iteration :status :thinking :executions :final-result :error :duration-ms}`. Status ∈ `#{:error :empty :success :final}`. Replaces the top-level `:on-iteration` opt.
  - Hook payloads + error terminal returns now also include `:status-id` namespaced keywords (for example `:rlm.status/error`, `:rlm.status/cancelled`) alongside legacy `:status`.
  - `:on-cancel` — fires when the cancel-atom is observed true.
  - `:on-chunk` — migrated from top-level `:on-chunk` opt into `:hooks`.
  - `:on-tool-invoked` / `:on-tool-completed` — fire around the per-tool pipeline for tools registered via `register-env-fn!`.

`query-env!` opts:
  - `:hooks {...}` — canonical entry point for global lifecycle hooks.
  - `:cancel-atom (atom false)` — caller-owned atom. Flip from any thread to cancel the in-progress query; the iteration loop finishes the current cycle and returns `{:status :cancelled}`. If omitted, `query-env!` creates a fresh one locally.
  - `:eval-timeout-ms` — unchanged. Clamped `[1s, 30min]`.

Inspection / maintenance:
  - `rlm/list-tool-hooks env sym` → `{:before [{:id :position :fn-name}] :after [...] :wrap [...]}`
  - `rlm/list-registered-tools env` → `[{:sym :hook-counts {:before :after :wrap}}]`
  - `rlm/unregister-hook! env sym stage id` → true/false

Other additions (unchanged from prior unreleased shipping):
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

### Breaking

- **`rlm/query-env!` opt changes.** Top-level `:on-chunk` and `:on-iteration` are removed. Move them into the `:hooks` map: `{:hooks {:on-chunk ... :on-iteration ...}}`.
- **`rlm/cancel-query!` removed.** Cancellation is caller-owned now: create an `(atom false)`, pass it via `:cancel-atom` opt to `query-env!`, and `(reset! the-atom true)` from any thread to cancel.
- **Env map no longer carries `:cancel-atom`.** Callers that reached into the env to flip cancellation must pass their own atom via the `:cancel-atom` query-env! opt.
- **`rlm.tools` hook engine refactor.** Replaced transient hook dynamic bindings with explicit per-query context threading (`query-ctx`) plus caller-owned atoms (`:cancel-atom`, `:current-iteration-atom`) where mutation is required.
- Env map no longer carries `:custom-bindings-atom` for fns — register-env-fn! writes to the new `:tool-registry-atom` instead. `:custom-bindings-atom` is still used by `register-env-def!` for constants/values.
- Entity extraction transaction pipeline moved out of `rlm.core` into `rlm.data` helper functions (`store-extraction-results!` + normalized entity/relationship tx builders).

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
