# Plan: Remaining Memory System Features

> Source: Session 2026-04-07. Continuation of unified entity model + vitality system.

## Architectural decisions

- **Storage**: Datalevin EAV, unified entity model (shipped)
- **Vitality**: ACT-R decay with type-aware metabolic rates (shipped)
- **Entity types**: Closed enum, 14 types (shipped)
- **Graph traversal**: BFS via find-related with canonical-id expansion (shipped)
- **No embeddings**: Fulltext + RLM parallel search + vitality replaces embeddings
- **All new features build on**: unified entity model, vitality scoring, relationship graph

---

## Phase 0: Migrate all query-env! callers to messages vector

**Tasks**: Unlisted (fallout from #18 multimodal change)

### What to build

Update ALL callers of `query-env!` from string to `[(llm/user "...")]` vector format. This includes benchmarks, README examples, core.clj docstring, test files, and any scripts.

### Acceptance criteria

- [ ] `bench/common.clj` passes vector to query-env!
- [ ] `bench/fourclojure.clj`, `bench/humaneval.clj`, `bench/swebench_verified.clj` updated
- [ ] `README.md` all query-env! examples use vector format
- [ ] `core.clj` query-env! docstring updated
- [ ] `rlm_test.clj` all query-env! calls use vector format
- [ ] Full test suite passes
- [ ] README doc tests pass (`clojure -M:test --md README.md`)

---

## Phase 1: Batch Search + Markdown Renderer

**Tasks**: #12, #13

### What to build

`search-documents-batch!` accepts a vector of queries, runs them in parallel via Datalevin, merges and deduplicates results. Returns a compact markdown string optimized for LLM token consumption — hierarchical headings, bullet lists, vitality zones inline. Available in SCI sandbox as `(search-batch ["query1" "query2" "query3"])`. The markdown renderer converts any Datalog result set (pages, TOC, entities) into clean markdown.

### Acceptance criteria

- [ ] `search-documents-batch!` takes vector of queries + opts, returns merged deduped results
- [ ] Results ranked by combined vitality + relevance across all queries
- [ ] Duplicate nodes (same page.node/id from multiple queries) appear once with highest score
- [ ] `results->markdown` converts page nodes, TOC entries, entities to compact markdown
- [ ] Markdown output uses `## Section` / `- bullet` / `> quote` for structure, vitality zone as tag `[active]`
- [ ] Available in SCI sandbox as `search-batch` and `results->md`
- [ ] Tests for dedup, ranking, markdown formatting

---

## Phase 2: Fix Paren Repair Tests

**Tasks**: #20 (fix 2 remaining failures)

### What to build

Fix the 2 failing tests in `sanitize_code_test.clj` (lines 149, 153). The `ctx-add!` and `FINAL` with learn cases fail after paren_repair.clj changes. Diagnose root cause and fix either the repair logic or the test expectations.

### Acceptance criteria

- [ ] `sanitize_code_test` passes all 55 cases
- [ ] Full test suite: 741+ cases, 0 failures
- [ ] `verify.sh` passes

---

## Phase 3: Spreading Activation

**Tasks**: #22

### What to build

When an entity is accessed (via fetch-content or find-related), propagate a vitality boost to neighboring entities in the relationship graph. BFS from accessed entity, boost = `utility * damping^hop` (damping=0.6, max 2 hops). Ebbinghaus decay on activation boosts over time. Cross-document propagation via canonical-id.

### Acceptance criteria

- [ ] Accessing entity A boosts vitality of directly related entities B, C
- [ ] Boost decays with hop distance (damping=0.6 per hop)
- [ ] Boosts decay over time (Ebbinghaus: retention = e^(-t/30*strength))
- [ ] Cross-document: accessing entity in doc A warms canonical siblings in doc B
- [ ] Activation boosts stored in Datalevin (new schema fields on entity)
- [ ] Tests for: single-hop boost, multi-hop decay, cross-doc propagation, time decay

---

## Phase 4: Bayesian Certainty per Document

**Tasks**: #23

### What to build

Add Beta distribution certainty scoring at the document level. Each document has alpha/beta parameters. Alpha increases on confirmed access (agent reads and uses content). Beta increases over time at a configurable rate. Re-indexing the same document causes beta to jump (content changed = old knowledge less certain). Certainty = alpha/(alpha+beta) feeds into vitality scoring as a multiplier.

### Acceptance criteria

- [ ] `:document/certainty-alpha` and `:document/certainty-beta` in schema
- [ ] Certainty initialized at alpha=2.0, beta=1.0 on ingest
- [ ] Agent access (fetch-content with document) increases alpha by 1.0
- [ ] Time decay: beta increases at 0.01/day (configurable)
- [ ] Re-index detection: content hash change → beta += 5.0
- [ ] `document-certainty` function returns score + confidence interval
- [ ] Certainty score multiplied into page vitality computation
- [ ] Tests for: initial certainty, access boost, time decay, re-index jump

---

## Phase 5: RL Q-values for Memory Retrieval

**Tasks**: #24

### What to build

Q-value per page that learns from session outcomes. After a query completes successfully, pages that were fetched during that query get a positive reward. Pages fetched but leading to dead ends get negative reward. Q-values use EMA update (alpha=0.1). High-Q pages decay slower (metabolic rate multiplier 0.7), low-Q pages decay faster (multiplier 1.3). UCB-Tuned exploration bonus for under-retrieved pages to prevent cold-start starvation.

### Acceptance criteria

- [ ] `:page/q-value` in schema (double, default 0.5)
- [ ] After successful query: fetched pages get reward +1.0
- [ ] After failed query: fetched pages get reward -0.15
- [ ] Forward citation (page referenced in answer): reward +1.5
- [ ] Q-value EMA update: Q = Q + alpha * (reward - Q)
- [ ] Q-value modulates metabolic rate in vitality computation
- [ ] UCB-Tuned exploration bonus in search reranking for low-access pages
- [ ] Tests for: reward propagation, EMA convergence, metabolic rate modulation

---

## Phase 6: Co-occurrence Edges

**Tasks**: #25

### What to build

Track which pages are co-retrieved within the same query session. When pages A and B are both fetched in the same query, create or strengthen a co-occurrence edge between them. Edge strength uses Ebbinghaus retention: `retention = e^(-days_since / (30 * strength))`. Pages frequently co-retrieved resist decay together — if one is accessed, the co-occurring page gets a small vitality bump.

### Acceptance criteria

- [ ] Co-occurrence edge schema: `:cooccurrence/page-a`, `:cooccurrence/page-b`, `:cooccurrence/count`, `:cooccurrence/last-seen`
- [ ] Edge created/strengthened when 2+ pages fetched in same query
- [ ] Ebbinghaus retention decay on edge strength over time
- [ ] Co-occurring pages get small vitality bump when partner is accessed
- [ ] Deduplication: (A,B) and (B,A) are the same edge
- [ ] Tests for: edge creation, strength increment, time decay, vitality bump

---

## Phase 7: Bugfixes

**Tasks**: #26, #27, #28, #29

### What to build

Fix four known issues:
1. **Router leak** (#26): dispose-env! should clean up router state (atoms, provider slots)
2. **ID collision** (#27): page.node/id and document.toc/id share local ID space — prefix or namespace them
3. **Batch retry** (#28): generate-qa-env! should retry failed batches (transient network errors)
4. **Error messages** (#29): Timeout/max-iterations/error-budget messages should include actionable guidance for LLM self-correction

### Acceptance criteria

- [ ] dispose-env! cleans up router (no leaked atoms after 100 create/dispose cycles)
- [ ] PageIndex IDs never collide between page nodes and TOC entries on same page
- [ ] generate-qa-env! retries failed batches up to 3 times with backoff
- [ ] Error messages include what happened + what to do next
- [ ] Tests for each fix
