# RLM & questionify! — Unified Architecture Analysis

> Consolidated, brutally honest assessment of the Recursive Language Model (RLM) and `questionify!` pipeline.
> Synthesizes prior analyses with literature benchmarks (RAGAS, DataMorgana, QGEval, RAGEval, 2024–2026 research).

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [RLM Architecture Assessment](#2-rlm-architecture-assessment)
3. [`questionify!` Pipeline Assessment](#3-questionify-pipeline-assessment)
4. [Literature Comparison](#4-literature-comparison)
5. [Gap Analysis (Prioritized)](#5-gap-analysis-prioritized)
6. [Improvement Roadmap](#6-improvement-roadmap)
7. [Cost & Risk Snapshot](#7-cost--risk-snapshot)
8. [Appendix: Key References & Code Locations](#8-appendix-key-references--code-locations)

---

## 1. Executive Summary

### What We Have

The RLM is a **code-execution-based RAG engine**: the LLM writes and runs Clojure in a sandboxed SCI environment to search, filter, aggregate, and iteratively refine answers. `questionify!` sits on top, generating Q&A pairs using a 4-phase pipeline (selection → generation → verification → dedup).

Our own **PageIndex module** (`pageindex/core.clj`, `pageindex/vision.clj`, `pageindex/spec.clj`) already builds the document model: it extracts or generates a TOC tree, links entries to sections via `target-section-id`, and structures content into pages → nodes. The RLM sandbox already exposes `list-toc-entries`, `search-toc-entries`, `get-toc-entry` to the LLM. **The infrastructure for TOC-first navigation exists.**

### Verdict

**RLM is architecturally novel**: it's closer to how a human analyst actually works (iterate, search, compute, refine). Most RAG systems are fixed pipelines; RLM lets the LLM *build* the pipeline. This is a real differentiator.

**`questionify!` is solid but inefficient and incomplete**: Phase 3 verification is best-in-class, but Phase 1 burns LLM iterations on work that can be handled by a fast/cheap model using the TOC we already have. Phase 2 lacks multi-hop, personas, and candidate selection. The missing top QGEval checks (answerability + answer-consistency) are serious quality gaps.

**Explicit direction**: no complex math, no vector search, no embeddings. Lean harder into **TOC-first selection with a fast/cheap model**, then refine with the strong verification loop already present. The TOC tree is the index — we built it, we should use it better.

### Scores (1–5)

| Component | Score | Assessment |
|-----------|-------|------------|
| **RLM Core Architecture** | 4/5 | Innovative code-execution loop; operational maturity is lagging (single-threaded, no recovery). |
| **RLM Search Infrastructure** | 2/5 | Substring search only. No ranking, BM25, or semantic search. |
| **Data Model (PageIndex)** | 4/5 | Rich structure (pages, nodes, TOC, entities, relationships). EDN ok at current scale. TOC extraction/generation is solid. |
| **questionify! Phase 1** | 2/5 | Uses the expensive model to browse TOC — wasteful when a cheap model can do TOC routing. |
| **questionify! Phase 2** | 3.5/5 | Good prompts + evidence spans. Missing multi-hop, personas, k-candidates. |
| **questionify! Phase 3** | 4.5/5 | Best-in-class verification with code-execution search. |
| **questionify! Phase 4** | 2.5/5 | LLM dedup is right idea, but single-shot doesn't scale. |
| **Spec System** | 3.5/5 | Clean DSL but not strongly enforced in generation. |

---

## 2. RLM Architecture Assessment

### 2.1 What's Genuinely Strong

**Code-Execution Loop** (rlm.clj ~2772–2855)

- LLM composes its own retrieval pipeline in Clojure.
- Iterative search/refine is natural and powerful.
- Supports sub-queries (`rlm-query`) and internal LLM calls (`llm-query`).

**SCI Sandbox** (rlm.clj ~2108–2192)

The LLM can:
- Navigate documents, TOC entries, entities, relationships.
- Search and compute over structured corpus.
- Produce structured output via `spec` + `field`.

**PageIndex Data Model** (pageindex/core.clj, spec.clj, vision.clj)

- Extracts TOC from actual document TOC pages, or generates from Section/Heading structure.
- Hierarchical levels (l1, l2, l3), parent-child linking, section-to-page mapping.
- `link-toc-entries` resolves TOC entries to their target sections.
- This is the foundation that query and questionify already navigate.

**Verification via Code Execution (CoVe)**

- LLM can actually search to verify claims or evidence.
- This is a differentiator vs. RAGAS/DataMorgana/QGEval (which recommend checks but don't execute them).

### 2.2 What's Weak / Fragile

**Single-threaded execution**

- Every `query!` is blocking and sequential.
- `questionify!` batch generation is 3x slower than it needs to be.

**Substring search only**

- No semantic matching, no ranking, no fuzzy matching.
- LLM compensates with repeated queries, burning tokens and time.

**No recovery mechanisms**

- Syntax errors / infinite loops hang or burn iterations.
- No per-iteration limits, timeouts, or guardrails inside SCI.

**Planning phase is optional but underused**

- The plan is often ignored; it's extra latency if not enforced.

**`query!` return path had a wasteful pr-str roundtrip** *(FIXED)*

- `FINAL`/`FINAL-VAR` returned native Clojure values from SCI, but `query!` was `pr-str`'ing them to strings, then re-parsing through `str->data-with-spec` (which applied `key-ns` namespacing). When `refine?` was false (all of questionify), this was pure waste. When re-parse failed, it triggered an emergency `llm/ask!` fallback to recover data it already had.
- **Fixed**: when `refine?` is false, the native Clojure value flows through directly. When `refine?` is true but the answer is unchanged (converged), the original value is preserved. Re-parse only fires when refinement actually modifies the answer.

**Thread safety for concurrent `query!` calls**

- Each `query!` call creates its own SCI sandbox context. Reads from the shared `env` (`db-info-atom`, `:store`) are safe for concurrent Phase 2 batches (read-only). Writes (learnings, message history) use atoms and are thread-safe. No mutex needed for the current parallelization plan.

---

## 3. `questionify!` Pipeline Assessment

### 3.1 Phase 1 — Passage Selection (2/5)

**Current**: The expensive model manually browses documents/TOC with `query!` — calling `list-toc-entries` and `search-toc-entries` — and selects passages. The TOC infrastructure is there, but the wrong model is doing the browsing.

**Problems**:
- Wastes 5–15 iterations of the expensive model on what is essentially TOC navigation.
- No importance scoring, no coverage guarantees, no stratification.
- No entity-awareness or multi-hop selection.

**Best fix (no vectors, no complex math)**: Use a **fast/cheap model** for TOC routing. The TOC tree is already built by PageIndex. The fast model reads the compact TOC (title + level + description), picks top-N sections, and hands off section content to the RLM for the actual work. This preserves the strongest part (verification) and cuts the most expensive part (expensive-model browsing).

**Optimized Phase 1 flow**:
1. **List TOC** (`list-toc-entries`) — already available, returns title/level/description/target-page.
2. **Fast-model routing**: cheap model selects top-N TOC entries for the task (for questionify: spread across sections; for query: focus on relevant sections).
3. **Section expand**: fetch linked nodes for chosen TOC entries via `target-page` / `target-section-id`.
4. **RLM refinement**: run `query!`/verification against these sections; expand to adjacent TOC siblings only if answerability fails.

### 3.2 Phase 2 — Q&A Generation (3.5/5)

**Strengths**:
- Evidence spans required.
- Self-containedness enforced.
- Good prompt examples + Bloom's taxonomy + categories.

**Missing**:
- Multi-hop questions (cross-section reasoning).
- Personas for diversity.
- k-candidate generation and selection.
- Entity-relationship questions.

### 3.3 Phase 3 — Verification (4.5/5)

**Best-in-class** because verification can search the corpus directly.

**Missing top QGEval checks**:
1. **Answerability** — can the question be answered using *only* the evidence span?
2. **Answer Consistency** — does the answer actually address the question?

These are QGEval's #1 and #2 failure modes across QG models.

**Also missing**:
- Clarity / ambiguity
- Fluency (lower priority)

### 3.4 Phase 4 — Deduplication (2.5/5)

**Good**: LLM-based semantic dedup (not Jaccard).

**Bad**: single-shot list-based dedup fails past ~40 questions; no batching or clustering.

---

## 4. Literature Comparison

### vs RAGAS (2024)

- **RAGAS wins** on multi-hop, persona diversity, async batching.
- **SVAR wins** on verification (RAGAS doesn't verify at all).

### vs DataMorgana (2025)

- **DataMorgana wins** on candidate generation and controlled diversity.
- **SVAR wins** on evidence spans + verification infrastructure.

### vs QGEval (2024)

- SVAR checks 3/7 critical dimensions.
- Missing the **two highest-impact checks**: answerability & answer-consistency.

### vs RAGEval (2024)

- RAGEval has better schema-driven and entity-relationship evaluation.
- SVAR has stronger code-executed verification.

### vs External TOC-based Systems (DocsRay, DeepRead, ConTReGen)

These systems validate the direction we're already on:
- **DocsRay** (2025): generates pseudo-TOC via LLM, then two-stage hierarchical retrieval. We already have real TOC — advantage us.
- **DeepRead** (2026): "locate-then-read" agent using document structure. Our RLM does this, but Phase 1 is overengineered.
- **ConTReGen** (ACL 2024): tree-structured retrieval with context-driven expansion. Similar to our TOC sibling expansion idea.

**Key takeaway**: all of these confirm that TOC-first routing + LLM refinement (no vectors) is the right architecture. We already have the hardest part (PageIndex + SCI sandbox + verification). We just need to stop wasting the expensive model on TOC browsing.

---

## 5. Gap Analysis (Prioritized)

### Critical (Fix First)

1. **Phase 1 uses expensive model for TOC browsing** → Use fast/cheap model for TOC routing; the infrastructure (`list-toc-entries`, `search-toc-entries`, `target-section-id` linking) already exists.
2. **Sequential batch execution** → Parallelize Phase 2 via `core.async/pipeline-blocking` (pattern from vision.clj).
3. **No answerability check** → Add to verification prompt/spec.
4. **No multi-hop questions** → Generate entity-linked passage pairs via TOC cross-references.

### Important (Fix Next)

5. **No answer-consistency check** → Add to verification.
6. **Dedup doesn't scale** → Batch/sliding-window dedup.
7. **needs-revision dropped** → Add revision sub-phase.
8. **No k-candidate generation** → Generate 3, score, select best.
9. **No persona diversity** → Generate personas from doc summaries.
10. **No sandbox guardrails** → Per-iteration timeouts, loop detection, max output size. Without this, production use is risky.

### Nice-to-Have (Fix Later)

11. **Substring search only** → Implement BM25.
12. **No adversarial questions** → SyNeg-style hard negatives.
13. **No IRT difficulty calibration** → Statistical difficulty scoring.
14. **Limited output formats** → JSONL / HF datasets / SQuAD.

---

## 6. Improvement Roadmap

### Phase A — Quick Wins (hours to 1–2 days)

- **A1** Parallel batch execution via `core.async` (Phase 2) — pipeline of N concurrent `query!` calls
- **A2** Answerability check (Phase 3)
- **A3** Answer-consistency check (Phase 3)
- **A4** Batch dedup (Phase 4)
- **A5** Fast-model TOC routing via `core.async` (Phase 1) — cheap model selects TOC sections, feeds into RLM

### Phase B — Structural Improvements (3–5 days)

- **B1** TOC routing heuristics for questionify (spread across TOC levels, avoid adjacent sections)
- **B2** Multi-hop question generation (TOC cross-section linking)
- **B3** Revision sub-phase for `needs-revision`
- **B4** k-candidate generation
- **B5** JSONL output

### Phase C — Advanced Features (1–2 weeks)

- **C1** Persona-based diversity
- **C2** BM25 search
- **C3** Entity-relationship questions
- **C4** Adversarial question generation
- **C5** IRT difficulty calibration

**Recommended order**:

```
A5 → A1 → A2 → A3 → B1 → B2 → A4 → B3 → B4 → C1 → C2
```

---

## 7. Cost & Risk Snapshot

### Cost (Per ~10 Questions)

*Assumes GPT-4o ($2.50/1M in, $10/1M out) as expensive model, GPT-4o-mini ($0.15/1M in, $0.60/1M out) as fast model.*

| Phase | Current Cost | Optimized Cost |
|------|---------------|----------------|
| Phase 1 (expensive model → fast model) | $0.80–2.40 | ~$0.02 |
| Phase 2 | $1.50 | $1.50 |
| Phase 3 | $0.50–1.00 | $0.50–1.00 |
| Phase 4 | $0.20 | $0.20 |
| **Total** | **$3.00–5.60** | **$2.22–2.72** |

**Time**: ~90–180s → ~30–60s with parallelization + fast-model routing.

### Key Risks

- **Infinite loops / bad code**: no sandbox guardrails.
- **Context overflow**: questionify Phase 1 has no max context tokens.
- **Non-determinism**: high variability; consider temperature=0 and sorted results.

---

## 8. Appendix: Key References & Code Locations

### References

- RAGAS (2024)
- DataMorgana (2025)
- QGEval (2024)
- RAGEval (2024)
- DocsRay (arXiv:2507.23217, 2025)
- DeepRead (arXiv:2602.05014, 2026)
- ConTReGen (ACL 2024, EMNLP Findings)
- KAQG / BloomLLM (2024–2025)
- SyNeg (2024)
- SimpleStrat (2024)
- SemDeDup (2023)

### Key Code Locations

**PageIndex module** (`src/clj/com/blockether/svar/internal/rlm/internal/pageindex/`):
- `core.clj` — TOC extraction, generation from headings, `link-toc-entries`, `postprocess-toc`, `build-toc-from-structure`
- `spec.clj` — `:document.toc/*` specs, `:toc/level`, `:toc/page`
- `vision.clj` — LLM-driven page extraction, `toc-entry-spec`

**RLM** (`src/clj/com/blockether/svar/internal/rlm.clj`):
- `query!` loop: ~2772–2855
- SCI sandbox: ~2108–2192
- TOC functions: `make-list-toc-entries-fn` (~1907), `make-search-toc-entries-fn` (~1890), `db-search-toc-entries` (~1634), `db-list-toc-entries` (~1678)
- Search functions: ~1523–1561
- System prompt (incl. TOC tools): ~2470–2715
- `questionify!`: ~3828–3993
- Phase 1 prompt: ~3610–3645
- Phase 2 prompt: ~3647–3700
- Phase 3 prompt: ~3702–3738
- Dedup: ~3753–3804
- Specs: ~3451–3607
- `save-questionify!`: ~3998–4077
