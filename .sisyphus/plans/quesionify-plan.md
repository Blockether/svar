# Questionify/TOC Core.async Plan

## TL;DR

> **Quick Summary**: Replace expensive Phase 1 TOC browsing with fast-model TOC routing, parallelize Phase 2 batches via `core.async`, and add missing verification checks + dedup/revision improvements while keeping the system vector-free and TOC-first.
>
> **Deliverables**:
> - Core.async parallel batch execution in `questionify!` (Phase 2)
> - Fast-model TOC routing for Phase 1 using existing TOC data (no vectors)
> - Verification upgrades: answerability + answer-consistency
> - Dedup batching + revision sub-phase + k-candidates + multi-hop + personas (roadmap scope)
>
> **Estimated Effort**: Large
> **Parallel Execution**: YES — 2 waves
> **Critical Path**: Thread-safe env for parallel query! → core.async batch pipeline → Phase 1 fast-model routing → verification upgrades

---

## Context

### Original Request
"Ok, bro please do full planning in TASKS.md" (path restricted → plan must be saved under `.sisyphus/plans/`).

### Interview Summary
**Key Discussions**:
- Must avoid complex math/vector search. Use TOC-first selection with fast model.
- Use `core.async` for parallel Phase 2 batches and for TOC routing (Phase 1).
- `FINAL`/`FINAL-VAR` values should remain Clojure values — avoid `pr-str` roundtrip.

**Research Findings**:
- `questionify!` pipeline and prompts live in `src/clj/com/blockether/svar/internal/rlm.clj` (~3610–3993).
- TOC tools are already exposed: `list-toc-entries`, `search-toc-entries`, `get-toc-entry`.
- `core.async/pipeline-blocking` pattern exists in `pageindex/vision.clj` (~1300+).

### Metis Review
**Identified Gaps (addressed in plan)**:
- **Critical**: `locals-atom` thread safety. Parallel `query!` calls must not share mutable locals. Plan includes env-forking per batch.

---

## Work Objectives

### Core Objective
Make `questionify!` faster and cheaper by routing TOC selection with a cheap model and running Phase 2 in parallel (core.async), while preserving correctness by improving verification checks and dedup/revision workflows.

### Concrete Deliverables
- Phase 2 parallelization (core.async) with per-batch env isolation
- Phase 1 fast-model TOC routing (no SCI loop)
- Verification checks: answerability + answer-consistency
- Dedup batching, revision sub-phase, k-candidates, multi-hop and persona diversity

### Definition of Done
- `questionify!` produces correct outputs with unnamespaced keys and no `pr-str` roundtrip when `refine?` is false
- Phase 2 runs in parallel without locals collision
- Phase 1 uses fast model with TOC data only
- Tests pass: `clojure -M:test --md README.md`

### Must Have
- No vectors, no embeddings, no complex math
- Use `core.async` (not `pmap`/`futures`)
- Reuse existing TOC functions and PageIndex data

### Must NOT Have (Guardrails)
- Do NOT introduce vector DBs or embedding search
- Do NOT break existing unnamespaced output keys
- Do NOT add new dependencies unless required for core.async (already in deps)

---

## Verification Strategy (MANDATORY)

### Test Decision
- **Infrastructure exists**: YES (lazytest + README doctests)
- **Automated tests**: Tests-after (run once after changes)
- **Framework**: lazytest (`clojure -M:test --md README.md`)

### Agent-Executed QA Scenarios (MANDATORY — ALL tasks)

Each task below includes QA scenarios with concrete commands or checks. All verification is automated (no human action).

---

## Execution Strategy

### Parallel Execution Waves

**Wave 1 (Start Immediately):**
- Task 1: Parallel-safe env for `query!` (locals isolation)
- Task 2: Phase 2 core.async parallel batches

**Wave 2 (After Wave 1):**
- Task 3: Phase 1 fast-model TOC routing
- Task 4: Verification upgrades (answerability + answer-consistency)
- Task 5: Dedup batching + revision sub-phase
- Task 6: k-candidates + multi-hop + personas

Critical Path: Task 1 → Task 2 → Task 3

---

## TODOs

> Implementation + Test = ONE Task. Every task includes QA scenarios.

### 1) Make `query!` parallel-safe (locals isolation)

**What to do**:
- Identify where `locals-atom` is created in env (`create-rlm-env`) and how `query!` uses it.
- Create a helper to fork the env for parallel use:
  - Share immutable config/context and `db-info-atom`
  - Create a fresh `locals-atom` per call
- Ensure `query!` uses per-call locals and does not share mutable locals across batches.

**Must NOT do**:
- Do not add locks around `locals-atom` (avoid performance bottlenecks)

**Recommended Agent Profile**:
- **Category**: `unspecified-high`
  - Reason: touches execution core and concurrency safety
- **Skills**: none required

**Parallelization**:
- Can Run In Parallel: NO
- Blocks: Task 2

**References**:
- `src/clj/com/blockether/svar/internal/rlm.clj: create-rlm-env, query!` — env structure and locals usage
- `src/clj/com/blockether/svar/internal/rlm.clj: safe-bindings/locals-atom` — where locals are merged

**Acceptance Criteria**:
- `query!` can be called concurrently without locals clobbering (verified by QA scenario)

**Agent-Executed QA Scenario**:
```
Scenario: parallel query! does not leak locals
  Tool: Bash
  Preconditions: tests runnable
  Steps:
    1. Run: clojure -M:test --md README.md
    2. Assert: exit code 0
  Expected Result: no failures
  Evidence: test output in terminal
```

---

### 2) Phase 2 parallel batches via core.async

**What to do**:
- Replace sequential `map-indexed` batch generation with `core.async/pipeline-blocking`.
- Use same pattern as `pageindex/vision.clj`.
- Add `:parallelism` option to `questionify!` (default 3).
- Use the env-forking helper for each batch to avoid locals collisions.

**Must NOT do**:
- No `pmap`/`futures`

**Recommended Agent Profile**:
- **Category**: `unspecified-high`
  - Reason: concurrency + core pipeline

**Parallelization**:
- Can Run In Parallel: YES (with Task 1 only after Task 1 is done)

**References**:
- `src/clj/com/blockether/svar/internal/rlm.clj: questionify! Phase 2` — batch loop
- `src/clj/com/blockether/svar/internal/rlm/internal/pageindex/vision.clj:1300+` — core.async pattern

**Acceptance Criteria**:
- `questionify!` returns same schema as before
- `:parallelism` opt respected

**Agent-Executed QA Scenario**:
```
Scenario: questionify! runs with parallelism
  Tool: Bash
  Preconditions: tests runnable
  Steps:
    1. Run: clojure -M:test --md README.md
    2. Assert: exit code 0
  Expected Result: no failures
  Evidence: test output
```

---

### 3) Phase 1 fast-model TOC routing (no vectors)

**What to do**:
- Add `:selection-model` option to `questionify!` (default `:model`).
- Replace Phase 1 `query!` with a single `llm/ask!` call using:
  - TOC data from `db-list-toc-entries` / `get-document-toc`
  - A rewritten prompt that asks the fast model to select sections
- Keep output spec as `CHUNK_SELECTION_SPEC`.

**Must NOT do**:
- No SCI loop for Phase 1
- No embeddings or vector search

**Recommended Agent Profile**:
- **Category**: `unspecified-high`

**Parallelization**:
- Can Run In Parallel: YES (Wave 2)

**References**:
- `rlm.clj: build-chunk-selection-prompt` — rewrite prompt for TOC data inline
- `rlm.clj: db-list-toc-entries/get-document-toc`

**Acceptance Criteria**:
- Phase 1 uses cheap model and TOC-only data
- Output matches `CHUNK_SELECTION_SPEC`

**Agent-Executed QA Scenario**:
```
Scenario: questionify! still returns passages and questions
  Tool: Bash
  Steps:
    1. Run: clojure -M:test --md README.md
    2. Assert: exit code 0
  Expected Result: no failures
```

---

### 4) Add answerability + answer-consistency checks

**What to do**:
- Update `VERIFICATION_RESULT_SPEC` to include `:answerable` and `:answer-consistent` booleans (or equivalent fields).
- Update `build-verification-prompt` to instruct checks:
  - Answerable from evidence span only
  - Answer matches question intent
- Update `filter-verified-questions` to drop `fail` or adjust logic as needed.

**Recommended Agent Profile**:
- **Category**: `unspecified-high`

**Parallelization**:
- Can Run In Parallel: YES

**References**:
- `rlm.clj: build-verification-prompt`, `VERIFICATION_RESULT_SPEC`

**Acceptance Criteria**:
- Verification output includes new fields
- Tests pass

**Agent-Executed QA Scenario**:
```
Scenario: verification spec changes do not break tests
  Tool: Bash
  Steps:
    1. Run: clojure -M:test --md README.md
    2. Assert: exit code 0
  Expected Result: no failures
```

---

### 5) Dedup batching + revision sub-phase

**What to do**:
- Modify `deduplicate-questions` to process in sliding windows (e.g., 20 at a time).
- Implement revision sub-phase for `needs-revision` in Phase 3 instead of dropping.

**References**:
- `rlm.clj: deduplicate-questions`, `filter-verified-questions`

**Acceptance Criteria**:
- Dedup works with >40 questions without collapsing
- needs-revision is revised, not dropped

**Agent-Executed QA Scenario**:
```
Scenario: dedup + revision flow
  Tool: Bash
  Steps:
    1. Run: clojure -M:test --md README.md
  Expected Result: no failures
```

---

### 6) k-candidates + multi-hop + personas

**What to do**:
- For each passage, generate k candidates, score, and keep best.
- Add multi-hop generation by pairing sections across TOC levels.
- Add persona prompts to diversify question styles.

**References**:
- `rlm.clj: build-generation-prompt`, `QUESTIONIFY_SPEC`

**Acceptance Criteria**:
- Questions include multi-hop and persona diversity
- Tests pass

**Agent-Executed QA Scenario**:
```
Scenario: generation pipeline still passes tests
  Tool: Bash
  Steps:
    1. Run: clojure -M:test --md README.md
  Expected Result: no failures
```

---

## Commit Strategy

Commit after each wave:
- Wave 1: `fix(rlm): isolate locals for parallel query` + `feat(questionify): core.async parallel batches`
- Wave 2: `feat(questionify): fast-model toc routing` + `feat(questionify): verification & dedup upgrades`

---

## Success Criteria

- All tests pass: `clojure -M:test --md README.md`
- Phase 2 parallel batches do not clobber locals
- Phase 1 uses fast-model TOC routing without vectors
- Verification includes answerability + answer-consistency
