# Draft: questionify core.async plan

## Requirements (confirmed)
- Produce a full plan ("full planning") and save it as a markdown file under `.sisyphus/plans/` (user asked for TASKS.md but path not allowed).
- Use core.async for parallelization (A1) and for TOC routing (A5).
- Avoid complex math and vector search; use TOC-first selection with a fast/cheap model.
- Ensure keywords used in analysis are correct; align with existing code conventions.

## Technical Decisions
- Use `core.async/pipeline-blocking` pattern (as in `pageindex/vision.clj`) for parallel batch execution in Phase 2 and for fast-model TOC routing.
- Preserve native Clojure values from `FINAL`/`FINAL-VAR` when `refine?` is false; avoid pr-str roundtrips (already implemented).

## Research Findings
- PageIndex is our own module; TOC navigation functions are already exposed (`list-toc-entries`, `search-toc-entries`, `get-toc-entry`).
- `questionify!` phases are defined in `rlm.clj` with prompts at ~3610–3738 and main pipeline at ~3828–3993.
- `core.async` usage pattern exists in `pageindex/vision.clj` via `async/pipeline-blocking`.

## Open Questions
- Plan filename for `.sisyphus/plans/{name}.md` (user confirmed to use allowed path, but did not choose a name).

## Scope Boundaries
- INCLUDE: Planning for A1 (parallel batches), A5 (fast-model TOC routing), verification checks (answerability/answer-consistency), dedup batching, revision sub-phase, k-candidates, personas (as per analysis roadmap).
- EXCLUDE: Vector search, complex math, embeddings.
