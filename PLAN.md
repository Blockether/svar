# PLAN.md — Rlm.clj Breakup + Codebase Hardening (STATUS)

Style: caveman-full. Machine + agent context.

## Goal

Kill the god namespace. `rlm.clj` (originally 3535 lines, 92 fns) → focused modules. Harden error handling, fix race conditions, eliminate atom sprawl. Every phase independently shippable — `verify.sh` green after each.

---

## Status

| Phase | What | Status |
|-------|------|--------|
| 0 | core.clj compaction via doseq/intern for vector types | ✅ DONE |
| 1 | Extract rlm/trace.clj | ✅ DONE |
| 2 | Extract rlm/pageindex.clj | ✅ DONE (partial — some fns stayed for test semantics) |
| 3 | Extract rlm/qa.clj + rlm/qa_manifest.clj | ✅ DONE |
| 5 | Extract rlm/query.clj + decompose query-env! into phases | ✅ DONE |
| 6 | Race condition fix (atomic CAS in with-depth-tracking) + concurrent test | ✅ DONE |
| 7 | Log-annotate silent catch blocks | ✅ DONE |
| 8 | Unit tests: batch, concurrency, skills, trajectory | ✅ DONE |
| 4 | Extract rlm/env.clj + atom grouping (13 → 5) | ⏳ IN PROGRESS |

Plus bonus changes:
- ✅ Rename `generate-qa-env!` → `query-env-qa!` (consistent with `query-env!`)
- ✅ Rename `:count` opt → `:target-count` (kills 15+ `clojure.core/count` qualifications from shadowing)
- ✅ `create-multi-hop-pairs` atom → pure `for` comprehension
- ✅ `SVAR_DIR` env var + `*svar-dir*` dynamic var for renaming `.svar` dir
- ✅ `docs/RECOMMENDATIONS.md` — opinionated usage guide

---

## Final File Structure

```
src/clj/com/blockether/svar/
├── core.clj                    (~293 lines, was 628)
├── internal/
│   ├── rlm.clj                 (~1610 lines, was 3535) — thin facade + delegates
│   └── rlm/
│       ├── core.clj            (iteration loop, code execution)
│       ├── db.clj              (Datalevin layer)
│       ├── schema.clj          (specs, dynamic bindings)
│       ├── tools.clj           (SCI sandbox + hook system)
│       ├── routing.clj         (sub-rlm-query routing, depth tracking — atomic CAS)
│       ├── sub.clj             (sub-RLM execution)
│       ├── batch.clj           (parallel fan-out)
│       ├── concurrency.clj     (reentrant semaphore, deadline tracking)
│       ├── skills.clj          (SKILL.md discovery, validation, *svar-dir*)
│       ├── trajectory.clj      (query scoring + JSONL export)
│       ├── git.clj             (JGit integration)
│       ├── data.clj            (entity/relationship ingestion)
│       ├── trace.clj           (trace formatting — NEW)
│       ├── query.clj           (query-env! phases — NEW)
│       ├── qa.clj              (Q&A generation pipeline — NEW)
│       ├── qa_manifest.clj     (QA crash-resume manifest — NEW)
│       ├── pageindex.clj       (PageIndex helpers + load-index + inspect — NEW)
│       └── pageindex/
│           ├── markdown.clj
│           ├── pdf.clj
│           └── vision.clj
```

Test count: ~830 → ~1000+ cases (new Phase 8 tests).

---

## Remaining Work — Phase 4 (env.clj + atom grouping)

### Problem

`create-env` constructs **13 separate atoms** inside a single function. No encapsulation, state coordination by convention only.

```clojure
;; Current (rlm.clj ~L86-181)
{:depth-atom                     (atom 0)
 :custom-bindings-atom           (atom {})
 :custom-docs-atom               (atom {})
 :tool-registry-atom             (atom {})
 :db-info-atom                   db-info-atom
 :var-index-cache-atom           (atom nil)
 :var-index-revision-atom        (atom -1)
 :qa-corpus-snapshot-cache-atom  (atom nil)
 :qa-corpus-snapshot-stats-atom  (atom {...})
 :skill-registry-atom            (atom nil)
 :rlm-env-atom                   (atom nil)
 :conversation-ref-atom          (atom nil)
 ...}
```

### Target

Group related state into fewer atoms + introduce accessor fns so callers don't deref directly.

```clojure
;; After
{:depth-atom          (atom 0)                              ; standalone, concurrent access
 :tool-registry-atom  (atom {})                             ; standalone, independent lifecycle
 :db-info-atom        db-info-atom                          ; existing, DB connection info
 :var-index-atom      (atom {:cache nil :revision -1})      ; related pair → single atom
 :qa-corpus-atom      (atom {:snapshot nil :stats {...}})   ; related pair → single atom
 :state-atom          (atom {:custom-bindings {}
                             :custom-docs {}
                             :skill-registry nil
                             :rlm-env nil
                             :conversation-ref nil})}
```

### Execution Strategy

1. Create `rlm/env.clj` with `create-env`, `register-env-fn!`, `register-env-def!`, `dispose-env!`, hook helpers.
2. Add accessor fns: `env-state`, `env-update-state!`, `env-var-index`, `env-qa-corpus`, etc.
3. Audit every `:atom-key` access in:
   - `rlm.clj` (delegates only)
   - `rlm/core.clj` (iteration loop)
   - `rlm/query.clj` (query phases)
   - `rlm/qa.clj` (QA pipeline)
   - `rlm/qa_manifest.clj` (snapshot cache)
   - `rlm/sub.clj` (sub-RLM)
   - Tests that directly peek at atoms via `(:xxx-atom env)`
4. Replace direct deref with accessor fns.
5. `rlm.clj` delegates `create-env` to `rlm-env/create-env`.

### Risk

High — every atom deref must be updated. Tests reference some atoms directly (`(:qa-corpus-snapshot-cache-atom env)` in rlm_test.clj). Either update the tests or keep legacy keys as fn delegates that read from the grouped atom.

### Deferral Rationale

Net benefit vs risk is currently low — the 13-atom pattern is ugly but not broken. Deferring until either: (a) a bug manifests from atom coordination, or (b) a new feature forces consolidation.

---

## What This Plan Did NOT Cover (follow-up tech debt)

- **Circular dep elimination** (sub.clj `requiring-resolve`) — needs protocol extraction, separate effort
- **Inconsistent error handling** (anomaly vs ex-info vs silent) — convention doc + incremental alignment
- **Half-baked features cleanup** (citations collected but not verified, Q-values computed but not used for active learning, streaming partial) — product decision: finish or delete
- **Shared HTTP client shutdown** — add shutdown hook, low priority
- **Pricing data dedup in router.clj** — canonical model metadata, incremental
- **generate-qa-env! further internal decomposition** — phases still live in one function, could split
