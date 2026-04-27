# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [v0.3.11] - 2026-04-27

### Fixed
- spec: count ref usages across the whole spec graph (main spec + every
  spec in the registry) instead of just the main spec's fields.
  Transitively-referenced refs (e.g. `Group.items: item[]` when only
  `:group` is in the main spec's `:refs`) used to be partitioned as
  "unused" and dropped from the rendered prompt; the LLM saw
  `items: item[]` with no `item { … }` definition and degraded into
  positional arrays. Adds a regression test under
  `spec->prompt-test → transitive ref usage`.

## [v0.3.10] - 2026-04-26

### Changed
- fix: remove <objective> wrapping from system messages
- release: update version files for v0.3.9, bump to next dev version

## [v0.3.9] - 2026-04-25

### Changed
- fix: update streaming tests for reasoning-first chunks
- fix: stream reasoning chunks before content arrives
- release: update version files for v0.3.8, bump to next dev version


## [v0.3.8] - 2026-04-22

### Changed
- chore(release): rename SVAR_VERSION to VERSION and bump to 0.3.8
- refactor(router): replace resolve-root-model with resolve-effective-model
- release: update version files for v0.3.6, bump to next dev version


## [v0.3.6] - 2026-04-20

### Changed
- fix: remove per-field strict rejection, fix schema messages, auto-wrap bare vectors
- release: update version files for v0.3.5, bump to next dev version


## [v0.3.5] - 2026-04-20

### Changed
- feat: full EDN/Clojure fence support + deeply nested keyword parsing
- release: update version files for v0.3.4, bump to next dev version


## [v0.3.4] - 2026-04-20

### Changed
- feat: expose raw HTTP response in :svar.llm/empty-content ex-data
- release: update version files for v0.3.3, bump to next dev version


## [v0.3.3] - 2026-04-20

### Changed
- fix: surface empty-content from providers as :svar.llm/empty-content
- release: update version files for v0.3.2, bump to next dev version


## [v0.3.2] - 2026-04-19

### Changed
- feat: ::values vector shorthand for values-only enums
- release: update version files for v0.3.1, bump to next dev version


## [v0.3.2] - 2026-04-19

### Added
- **`::values` vector shorthand** — enum fields can now declare values as a
  plain vector `[\"high\" \"medium\" \"low\"]` instead of a `{value desc}`
  map. `spec->prompt` emits the inline `\"a\" or \"b\"` type union but
  skips the per-value comment block — meaningful savings on system
  prompts that declare self-explanatory enums (`confidence`, `model
  class`, `answer-type`, etc.). The validator treats both shapes
  identically. Back-compatible: existing `{value desc}` maps keep
  emitting per-value docs unchanged.

## [v0.3.1] - 2026-04-19

### Changed
- test: expand live :zai-coding reasoning coverage (quick/balanced/deep + preserved)
- feat: abstract reasoning-depth API + per-style translation
- release: update version files for v0.3.0, bump to next dev version

## [v0.3.0] - 2026-04-17

### Changed
- fix: improve SSE streaming error diagnostics
- refactor: let callers control reasoning params
- fix: allow reserved chars in spec descriptions
- fix: stop auto-wrapping bare arrays for single :many specs
- chore: drop allure reporting and test fixture cruft
- release: update version files for v0.2.0, bump to next dev version


## [v0.2.0] - 2026-04-14

### Changed
- feat: add reasoning-params to glm-5.1 and glm-5-turbo — default medium, RLM overrides dynamically
- feat: add *log-context* for log correlation — callers can bind {:query-id :iteration} to prefix all HTTP logs
- fix: demote SAP warnings to :debug — ExtractedFromMarkdown noise
- feat: compact logs for SAP warnings, HTTP retries, errors — single-line with symbols
- feat: compact single-line LLM logs — → HTTP model=X tokens=N and ← HTTP model=X 150ms in=N out=N
- feat: add Streaming section to README, consolidate parsing sections, fix streaming on-chunk paren issue + nil-result suppression, pass tokens/cost in streaming chunks
- feat: add Router section to README, consolidate Functionalities table, rename cb-* opts to failure-threshold/recovery-ms, remove RECOMMENDATIONS.md
- chore: strip RLM from RECOMMENDATIONS.md, lower verify.sh test floor to 350
- chore: remove target, .omc, PLAN.md — RLM cleanup
- refactor!: excise RLM, PageIndex, git ingestion, bench, paren-repair — moved to ../vis
- wip(rlm): production cutover to SQLite — Datalevin gone from src/
- feat(rlm): SQLite store foundation — DDL, FTS5, entity/conv/query/iter ops
- refactor(rlm): kill :db-info-atom — 37 sites, ZERO mutations, pure ceremony
- refactor(rlm): encapsulate env atom access behind rlm-env/* accessor fns
- refactor(rlm): eliminate sub → core cyclic dep via parameter injection
- fix: HTTP client shutdown hook + kill requiring-resolve hacks in sub.clj
- fix(router): refresh pricing data (2026-04-12 audit)
- refactor(rlm): Phase 4 — extract rlm/env.clj + atom grouping (12 → 7)
- feat(rlm): Phase 7 + 8 + named conversations + PLAN update
- refactor(rlm): Phase 5 — extract query.clj + configurable .svar dir + user recs
- fix(rlm): Phase 6 — atomic CAS in with-depth-tracking (race condition)
- refactor(rlm): Phase 3 — extract QA pipeline to rlm/qa.clj + rlm/qa_manifest.clj
- refactor(rlm): Phase 0-2 — core.clj compaction, trace + PageIndex extraction, QA rename
- feat(rlm): skill change detection, reference files, body auto-patch
- feat(rlm): async skill auto-refine + depth guard on skill loading
- feat(rlm): skills-as-documents + skill-manage SCI tool
- feat(rlm): parallel batch + public hooks/tools API
- feat(rlm): skills system, sub-rlm-query, concurrency primitives
- refactor(rlm): hook system v3 — eliminate dynamic vars, namespaced status/error ids, extract rlm/data.clj
- Continuation
- feat(rlm): cancel-query! + :on-iteration callback for query control
- refactor(rlm): persist git :repo attachments in Datalevin, drop env atoms
- feat(rlm): wire git runtime via JGit, multi-repo aware, git- prefixed SCI tools
- feat(rlm): adaptive eval-timeout, rel-query SCI tools, enum guard
- feat(rlm): persist vars as child entities, thread llm-opts through CoVe, fix duration tracking
- fix(rlm): fix re-find bug, nil-guard make-chat-url, stabilize integration tests
- fix(rlm): restore-var binds in SCI sandbox, cleanup dead params and nil consistency
- feat(rlm): add conversation restore tools
- chore: update test fixture manifest
- fix(test): update caveman assertion to match current prompt
- fix(test): guard streaming tests behind integration-tests-enabled?
- feat(rlm): add git commit ingestion pipeline
- Add mandatory clj-paren-repair for Clojure delimiter errors
- chore: ultra-caveman CLAUDE.md communication style rules
- refactor(rlm): split caveman output — iterations terse, final answer normal English
- chore: update test fixture manifest
- refactor(rlm): update tests, docs, and bench for explicit :db spec
- refactor(rlm): wire explicit :db spec through create-env and create-rlm-env
- refactor(rlm): add explicit db-spec API to create-rlm-conn
- chore: remove CRITICISM.md
- Engage Caveman!
- fix(rlm): gate SCI execution and validation on Clojure language only
- fix(rlm): skip edamame validation for non-Clojure final answers
- chore: checkpoint rlm hardening and workspace updates
- perf(rlm): cache QA corpus snapshot for manifest fingerprinting
- fix(rlm): harden QA manifest resume fingerprinting and persistence
- feat(router): add minimax-m2.7, gemma4:31b, qwen3.5:397b to blockether
- refactor(rlm): simplify get-locals to sci/eval-form + reduce-kv
- feat(rlm): RL Q-values, co-occurrence edges, certainty normalization, QA resume, SCI isolation
- fix(bench): restore test form in failure reports, safe string embedding
- fix(bench): fix 7 unbalanced test forms in 4clojure dataset, bb script debug
- refactor(rlm): split parse-clojure-syntax into composable check functions
- fix(rlm): bare list detection in parse-clojure-syntax, bb timeout 60s, PROBLEMS.md
- fix(vitality): lazy certainty decay as pure computation, no DB writes per query
- fix(vitality): wire Bayesian certainty into page vitality scoring
- feat(vitality): Bayesian certainty per document (Beta distribution)
- fix(vitality): merge spreading activation with find-related, fix canonical sibling traversal
- test: memory system tests (batch search, BFS, canonical-id, spreading activation)
- feat(vitality): spreading activation across connected pages
- fix: PageIndex ID collision, QA batch retry, actionable error messages
- feat: auto-translate non-English content to English at ingest + query
- fix(test): sanitize_code_test quality improvements
- fix(test): remove redundant 'user namespace from sanitize_code_test
- fix(test): sanitize_code_test SCI namespace isolation
- fix(bench): reset circuit breaker between tasks to prevent cascading failures
- feat: search-documents-batch! + results->markdown renderer
- refactor: migrate all query-env! callers to messages vector
- feat(entity): multimodal query-env!, canonical-id linking, BFS graph traversal
- feat(entity): unified entity model with closed type enums
- feat(bench): add median/p90/p99/std-dev stats and --compare command
- docs(rlm): add deref-with-timeout hint to system prompt
- feat(rlm): add SCI futures addon (future, pmap, promise, delay, deliver)
- feat(rlm): rename SCI default ns from 'user to 'sandbox
- feat(rlm): proper SCI stdout capture, functions in var index, docstring hint
- feat(rlm): edamame syntax validation for code blocks and final answers
- fix(bench): revert to inline substitution, trim RLM prompt, REPL hint
- feat(bench): separate PI prompt, update qwen context to 128K
- feat(bench): PI local support via router, improved strip-code-fence, qwen 9b model
- feat(vitality): ACT-R memory decay with type-aware metabolic rates
- feat(rlm): API-style dispatch, LM Studio provider, sandbox fixes, bench hardening
- feat: OCR extraction strategy, Anthropic API support, HTTP/1.1 fix, LM Studio provider
- fix(rlm): allow resolve/ns-resolve in SCI (read-only, needed for doc injection)
- fix(rlm): remove Object/Thread/Class from SCI imports, proper :deny list
- fix(rlm): restore clojure.java.process require (linter stripped it)
- feat(rlm): add BigInteger + BigDecimal to SCI classes and imports
- feat(rlm): execute code blocks BEFORE accepting final (self-test gate)
- fix(rlm): auto-repair final answer parens before storing
- feat(rlm): "NEVER guess, ALWAYS test" rule in system prompt
- feat(rlm): reject untested finals + mandatory self-test in 4clojure
- fix(rlm): validate-final uses keyword case, not name conversion
- fix(llm,vision): cap vision max_tokens at 15K, merge caller extra-body
- fix(rlm): validate-final handles keyword and string answer-type/language
- feat(rlm): lazytest in SCI + fix validate-final enum handling
- fix(rlm): generic NPE hint instead of misleading method-on-nil assumption
- feat(rlm): specialized error hints with context extraction
- feat(rlm): conditional error hints in feedback, not system prompt
- fix(rlm): trim system prompt 1748 -> 667 tokens
- feat(bench): 4clojure self-test workflow + query-opts passthrough
- fix(rlm): reject final answers with bare % args outside #()
- fix(rlm): remove all SCI :deny restrictions
- fix(rlm): allow require/import/ns in SCI, add vector/LazySeq/nil hints
- refactor(rlm): remove str-* and set-* helpers, use namespace aliases
- refactor(rlm): remove 119 redundant SAFE_BINDINGS, SCI has them built-in
- feat(rlm): add abs, parse-long, parse-double, parse-boolean, parse-uuid, infinite?, NaN? to SCI
- fix(bench): increase task timeout 5min -> 10min, fix misleading label
- feat(rlm): transducer hint in COMMON ERRORS system prompt
- fix(rlm): reject final answers containing __ placeholder
- feat(rlm): validators for all FINAL_SPEC languages
- feat(rlm): FINAL_SPEC answer-type + language fields for typed validation
- refactor(rlm): move final answer validation to spec-level validator
- feat(rlm): validate final answers that contain code before accepting
- refactor(rlm): use sci/copy-ns for standard namespaces in SCI
- docs: add SCI sandbox and benchmark sections to CLAUDE.md
- fix(rlm): drop real clojure.pprint from SCI - pure zprint backed
- feat(rlm): clojure.pprint/pprint backed by zprint, keep print-table/cl-format
- feat(rlm): real clojure.pprint in SCI + more error hints in system prompt
- feat(rlm): expose more zprint fns in SCI (czprint, set-options!, zprint-file-str)
- fix(rlm): manually bind zprint fns instead of ns->sci-map
- feat(rlm): add pprint and pp aliases for zprint.core in SCI
- feat(rlm): alias clojure.edn -> fast-edn, clojure.pprint -> zprint in SCI
- feat(rlm): swap pprint for zprint, clojure.edn for fast-edn in SCI
- feat(rlm): add clojure.edn + clojure.pprint to SCI namespaces
- fix(bench): denormalize trajectory EDN - inline conversation, strip db refs
- refactor(rlm): align SCI :imports with Clojure/Babashka defaults
- feat(rlm): system prompt - each code[] entry must be complete expression
- fix(rlm): coalesce fragment code blocks before execution
- refactor(rlm): use SCI :imports for bare class names instead of duplicate :classes
- fix(rlm): bare class aliases in SCI (Character, Math, String, etc.)
- fix(rlm): restore DEBUGGING section in system prompt
- feat(rlm): COMMON ERRORS section in system prompt with actionable fixes
- fix(deps): downgrade datalevin 0.10.7 -> 0.10.3
- feat(rlm): pre-exec lint for nested #() + system prompt hints
- fix(bench): 4clojure prompt hints - nested #(), quoted lists, inline expr
- feat(rlm): add (sh) shell tool via clojure.java.process
- fix(rlm): lower realize-value limit to 100 elements
- fix(rlm): shared HTTP client, virtual threads, PersistentQueue, bounded lazy-seq, JSON/walk namespaces
- test(pageindex): remove broken output-dir validation test
- feat(rlm): paren-repair module for auto-fixing LLM delimiter errors
- refactor(bench): reorganize bench layout - common, benches/ subdir, trajectory EDN persistence
- feat(rlm): parallel per-page indexing + --force flag
- refactor(rlm)!: split build-index :path into extract + finalize phases
- refactor(vision): split fidelity prompt — copy text, keep detecting visuals
- Fixes
- test(pageindex): update filter tests for vision-layer filtering invariant
- feat(bench): trajectory collection, runtime hints, provider+model routing fix
- feat(rlm): system prompt improvements - data literals, SCI-as-REPL, style rules
- refactor(rlm): consolidate document tools into search-documents + fetch-content
- chore: remove legacy and backward-compat code
- chore(gitignore): bench-logs, bench/trajectories, schema-therapy pageindex variants
- rename: P-add! → fetch-content
- refactor(rlm)!: token-efficient system prompt + discoverable tool docs
- refactor(trajectory): build and store system-prompt at create-env time
- fix(trajectory): store system-prompt once, skip if already set
- fix(trajectory): store system-prompt on conversation at iteration-loop time
- fix(trajectory): resolve Datalevin entity refs, JSONL export working
- refactor(trajectory)!: rewrite trajectory.clj for query-based hierarchy
- refactor(trajectory)!: hierarchical schema — conversation → query → iteration
- feat(trajectory): add :iteration/final field for terminal answer
- refactor(trajectory): separate code/results fields, remove status
- fix(trajectory): sanitize executions for EDN serialization
- refactor(trajectory)!: iteration snapshots instead of sequential messages
- fix(trajectory): store messages on error/empty paths, include next-optimize
- fix(trajectory): reconstruct-conversation uses stored ITERATION_SPEC content
- fix(rlm): store ITERATION_SPEC JSON as assistant message content
- feat(rlm): persist iteration messages + executions for trajectory fine-tuning
- refactor(logging): merge ask-routed + chat-completion into llm-request/llm-response
- feat(logging): comprehensive LLM call chain logging
- refactor(rlm): proper types — enum next-optimize, nested ref final
- refactor(rlm)!: remove carry mechanism, enforce docstrings
- feat(rlm): improve system prompt — llm-query for coding tasks, general workflow
- feat(rlm): expand SCI sandbox with common Java classes
- fix(verify): reduce false positives in secret scan regex
- feat(rlm): LLM-driven model selection via :next-optimize in iteration spec
- refactor!: migrate ALL LLM functions to :routing API
- refactor!: migrate all ask! callers to :routing opts
- fix!: remove legacy ask! API, fix reasoning detection, add routing validation
- refactor(router): move router-stats, reset-budget!, reset-provider! to router.clj
- refactor!: delete defaults.clj, merge everything into router.clj
- refactor(router): add env-keys to KNOWN_PROVIDERS, simplify bench runner
- feat(llm): add :routing opts to ask!, RLM uses auto params
- refactor(router): create router.clj — single source of truth for routing
- fix(rlm): derive max_tokens from model context window (25%)
- fix(llm): merge reasoning-params into ask! extra-body
- refactor(rlm)!: replace routed-chat-completion with ask! in iteration loop
- feat(bench)!: overhaul benchmark suite — 4clojure, HumanEval, Pi agent, multi-provider
- chore: suppress unused-var warnings, clean up formatting
- feat(bench): add coding suites and EDNL run aggregation
- fix: streaming robustness, sci/intern, budget accuracy, done signal
- feat!: router-first API, RLM cleanup, SSE streaming
- fix: show errors in progress line when non-zero
- fix: prefix unused errors binding in print-progress


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


[Unreleased]: https://github.com/Blockether/svar/compare/v0.3.10...HEAD
[v0.1.1]: https://github.com/Blockether/svar/releases/tag/v0.1.1
[v0.1.2]: https://github.com/Blockether/svar/releases/tag/v0.1.2
[v0.1.3]: https://github.com/Blockether/svar/releases/tag/v0.1.3
[v0.2.0]: https://github.com/Blockether/svar/releases/tag/v0.2.0
[v0.3.0]: https://github.com/Blockether/svar/releases/tag/v0.3.0
[v0.3.1]: https://github.com/Blockether/svar/releases/tag/v0.3.1
[v0.3.2]: https://github.com/Blockether/svar/releases/tag/v0.3.2
[v0.3.3]: https://github.com/Blockether/svar/releases/tag/v0.3.3
[v0.3.4]: https://github.com/Blockether/svar/releases/tag/v0.3.4
[v0.3.5]: https://github.com/Blockether/svar/releases/tag/v0.3.5
[v0.3.6]: https://github.com/Blockether/svar/releases/tag/v0.3.6
[v0.3.8]: https://github.com/Blockether/svar/releases/tag/v0.3.8
[v0.3.9]: https://github.com/Blockether/svar/releases/tag/v0.3.9
[v0.3.10]: https://github.com/Blockether/svar/releases/tag/v0.3.10
