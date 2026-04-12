# PLAN.md — The Great Rlm.clj Breakup + Codebase Hardening

Style: caveman-full. Machine + agent context.

## Goal

Kill the god namespace. `rlm.clj` (3535 lines, 92 fns) → 7 focused modules. Fix re-export boilerplate via macro. Harden error handling, fix race condition, eliminate atom sprawl. Every phase independently shippable — verify.sh green after each.

---

## Current State (the mess)

```
rlm.clj (3535 lines) — EVERYTHING lives here:
  L86-181    create-env          → 13 atoms, env construction
  L183-345   register-env-fn/def!, hooks, ingest helpers
  L345-505   ingest-to-env!, ingest-git!, dispose-env!
  L506-909   query-env!          → 400-line monster, 30 let bindings
  L911-990   list-queries, export-trajectories!, trace formatting
  L992-1362  QA prompt builders, multi-hop, verification, dedup, revision
  L1363-1553 QA manifest persistence (crash-resume)
  L1553-1870 generate-qa-env!    → 300-line monster
  L1872-1960 save-qa!
  L1960-2045 File type detection helpers
  L2045-2900 PageIndex internals (translate IDs, group nodes, TOC, PDF extract)
  L2900-3082 index! orchestration + manifest
  L3082-3535 index! impl, load-index, inspect
```

---

## Target State

```
rlm.clj           (~200 lines) — thin facade: create-env, dispose-env!, query-env!, re-exports
rlm/env.clj       (~250 lines) — env construction, atom grouping, register-env-fn/def!, hooks
rlm/query.clj     (~350 lines) — query-env! decomposed into phases
rlm/qa.clj        (~500 lines) — generate-qa-env!, save-qa!, prompt builders, dedup, revision
rlm/qa_manifest.clj (~200 lines) — QA manifest persistence, fingerprinting, crash-resume
rlm/pageindex.clj  (~600 lines) — index!, load-index, inspect, file detection, PDF/TOC/node processing
rlm/trace.clj     (~80 lines)  — format-trace, pprint-trace, print-trace
core.clj           (~150 lines) — re-export macro kills 80% boilerplate
```

Plus cross-cutting fixes: race condition, swallowed exceptions, count shadowing, cost accumulator dedup.

---

## Phases

### Phase 0 — Re-Export Macro (core.clj: 628 → ~150 lines)

**What**: macro `re-export` that copies var + meta from source ns. Kills 107 `#_{:clojure-lsp/ignore}` annotations + 500 lines of mechanical defs. Programmatic generation for TYPE_INT_V_1..12, TYPE_STRING_V_1..12, TYPE_DOUBLE_V_1..12.

**Files**:
- `src/clj/com/blockether/svar/core.clj` — rewrite

**Macro shape**:
```clojure
(defmacro re-export
  "Re-exports vars from source namespace, preserving metadata."
  [source-ns & syms]
  `(do ~@(map (fn [sym]
                `(let [src-var# (var ~(symbol (str source-ns) (str sym)))]
                   (def ~(with-meta sym {}) @src-var#)
                   (alter-meta! (var ~sym) merge (select-keys (meta src-var#) [:doc :arglists]))))
              syms)))
```

**Vector types — generated**:
```clojure
(doseq [[prefix kw-prefix] [["INT" "int"] ["STRING" "string"] ["DOUBLE" "double"]]
        n (range 1 13)]
  (intern *ns*
    (with-meta (symbol (str "TYPE_" prefix "_V_" n))
      {:doc (format "Type: Fixed-size %s vector (%d element%s)." kw-prefix n (if (= n 1) "" "s"))})
    (keyword "spec.type" (str kw-prefix "-v-" n))))
```

**Verify**: `./verify.sh` green. All README doctests pass (they `require` core).

**Commit**: `refactor(core): re-export macro, kill 500 lines of boilerplate`

---

### Phase 1 — Extract `rlm/trace.clj` (easiest, zero deps)

**What**: move `format-trace`, `pprint-trace`, `print-trace` (L925-990) → new `rlm/trace.clj`. Pure functions, no state, no circular deps.

**Files**:
- NEW `src/clj/com/blockether/svar/internal/rlm/trace.clj`
- `src/clj/com/blockether/svar/internal/rlm.clj` — remove fns, add require, delegate
- `src/clj/com/blockether/svar/core.clj` — update re-export source

**Verify**: `./verify.sh` green.

**Commit**: `refactor(rlm): extract trace formatting to rlm/trace.clj`

---

### Phase 2 — Extract `rlm/pageindex.clj` (1575 lines → own module)

**What**: everything from L1960 to end of file (PageIndex internals + `index!` + `load-index` + `inspect`). This is ~1575 lines — nearly half the file — and has ZERO coupling to query-env!/generate-qa-env!. Clean cut.

**Functions moving**:
- `extract-doc-name`, `extract-extension`, `file-type`, `SUPPORTED_EXTENSIONS`, `supported-extension?`, `file-path?`
- `translate-page-ids`, `translate-all-ids`, `visual-node?`, `last-visual-of-type`, `group-continuations`
- `collect-all-nodes`, `has-toc-entries?`, `heading-level->toc-level`, `build-toc-from-structure`
- `link-toc-entries`, `postprocess-toc`, `collect-section-descriptions`, `generate-document-abstract`
- `validate-page-number`, `normalize-range`, `normalize-page-spec`, `filter-pages`
- `extract-text`, `detect-input-type`, `strip-nil-keys`, `render-page-pngs!`, `write-embedded-image-nodes!`
- `extract-pdf-pages`, `finalize-pdf-document`, `derive-index-path`, `ensure-absolute`
- `write-document-edn!`, `file-hash`, `read-manifest`, `manifest-write-lock`, `write-manifest!`, `update-manifest-page!`
- `read-document-edn`, `with-index-lock!`, `index!`, `load-index`, `print-toc-tree`, `inspect`

**Files**:
- NEW `src/clj/com/blockether/svar/internal/rlm/pageindex.clj` (orchestration module — uses pdf.clj, markdown.clj, vision.clj)
- `src/clj/com/blockether/svar/internal/rlm.clj` — remove all above, add require + delegate `index!`, `load-index`

**Verify**: `./verify.sh` green. `test/com/blockether/svar/internal/rlm/pageindex_test.clj` must still pass.

**Commit**: `refactor(rlm): extract PageIndex to rlm/pageindex.clj (~1575 lines)`

---

### Phase 3 — Extract `rlm/qa_manifest.clj` + `rlm/qa.clj`

**What**: two extractions in one phase (qa_manifest has no test coupling, qa.clj depends on it).

#### 3a — `rlm/qa_manifest.clj` (~200 lines)

Functions moving (L1363-1553):
- `QA_MANIFEST_VERSION`, `sha256-hex`, `digest-update!`, `digest->sha256`
- `qa-corpus-revision`, `qa-corpus-documents`, `qa-corpus-toc-entries`, `qa-corpus-page-nodes`
- `qa-corpus-content-hash`, `qa-corpus-cache-hit!`, `qa-corpus-cache-miss!`
- `compute-qa-corpus-snapshot`, `qa-corpus-snapshot`
- `qa-manifest-fingerprint`, `fresh-qa-manifest`, `qa-manifest-path`
- `read-qa-manifest`, `qa-manifest-write-lock`, `write-qa-manifest!`, `update-qa-batch-status!`

**Fix while extracting**: `qa-manifest-write-lock` → per-env or per-path lock (currently global `Object.` shared across all envs).

#### 3b — `rlm/qa.clj` (~500 lines)

Functions moving:
- `build-toc-based-selection-prompt`, `build-generation-prompt`, `create-multi-hop-pairs`
- `build-verification-prompt`, `compute-distribution`, `dedup-batch`, `DEDUP_WINDOW_SIZE`
- `deduplicate-questions`, `revise-questions`, `filter-verified-questions`, `fork-env-for-query`
- `generate-qa-env!`, `save-qa!`
- `invalidate-qa-corpus-snapshot-cache!`, `qa-corpus-snapshot-stats`

**Fix while extracting**: `create-multi-hop-pairs` — replace `(atom [])` + `swap! conj` with `for` comprehension. Pure fn, no atoms needed.

**Fix while extracting**: rename `:keys [count ...]` destructure in `generate-qa-env!` → `:keys [target-count ...]`. Kills all 15 `clojure.core/count` qualifications.

**Files**:
- NEW `src/clj/com/blockether/svar/internal/rlm/qa_manifest.clj`
- NEW `src/clj/com/blockether/svar/internal/rlm/qa.clj`
- `src/clj/com/blockether/svar/internal/rlm.clj` — remove ~700 lines, delegate

**Verify**: `./verify.sh` green.

**Commit**: `refactor(rlm): extract QA pipeline to rlm/qa.clj + rlm/qa_manifest.clj`

---

### Phase 4 — Extract `rlm/env.clj` (env construction + registration)

**What**: `create-env` and all registration/hook wrappers → own module. This is where the atom grouping fix lives.

**Functions moving**:
- `create-env` (L86-181)
- `register-env-fn!` (L196-267)
- `register-hook!`, `unregister-hook!`, `list-tool-hooks`, `list-registered-tools` (L268-313)
- `register-env-def!` (L314-344)

**Structural fix — atom grouping**: reduce 13 atoms → 5 by merging related state:

```clojure
;; BEFORE: 13 separate atoms
{:depth-atom (atom 0)
 :var-index-cache-atom (atom nil)
 :var-index-revision-atom (atom -1)
 :qa-corpus-snapshot-cache-atom (atom nil)
 :qa-corpus-snapshot-stats-atom (atom nil)
 ...}

;; AFTER: 5 grouped atoms
{:depth-atom        (atom 0)                          ; standalone — concurrent access
 :var-index-atom    (atom {:cache nil :revision -1})   ; related pair → single atom
 :qa-corpus-atom    (atom {:snapshot nil :stats nil})   ; related pair → single atom
 :tool-registry-atom (atom {})                         ; standalone — independent lifecycle
 :state-atom        (atom {:custom-bindings {}          ; everything else
                           :custom-docs {}
                           :db-info nil
                           :skill-registry nil
                           :rlm-env nil
                           :conversation-ref nil})}
```

**Every deref site** in core.clj, tools.clj, query.clj, qa.clj must update. Use helper fns:
- `(env-db-info env)` → `(:db-info @(:state-atom env))`
- `(env-update-state! env f)` → `(swap! (:state-atom env) f)`

**Files**:
- NEW `src/clj/com/blockether/svar/internal/rlm/env.clj`
- `src/clj/com/blockether/svar/internal/rlm.clj` — remove, delegate
- ALL files that deref env atoms — update access patterns

**Risk**: highest-risk phase. Every atom access changes. Run full test suite multiple times.

**Verify**: `./verify.sh` green.

**Commit**: `refactor(rlm): extract env.clj, group 13 atoms → 5`

---

### Phase 5 — Extract `rlm/query.clj` (decompose query-env!)

**What**: the 400-line `query-env!` monster → phases in `rlm/query.clj`. Facade `query-env!` in `rlm.clj` stays as thin wrapper.

**Decomposition**:

```clojure
;; rlm/query.clj

(defn prepare-query-context
  "Validates inputs, resolves bindings, sets up atoms.
   Returns query-context map."
  [env messages opts]
  ...)

(defn run-iteration-phase
  "Calls iteration-loop, collects raw result.
   Returns {:trace :iterations :answer :confidence :sources ...}"
  [env query-ctx]
  ...)

(defn run-refinement-phase
  "Cross-model verify if confidence < threshold.
   Returns updated result with :eval-scores :refinement-count."
  [env query-ctx raw-result]
  ...)

(defn update-q-values!
  "Q-value reward for cited/uncited/unfetched pages."
  [env query-ctx result]
  ...)

(defn finalize-query-result
  "Merges cost, tokens, claims → final result map."
  [query-ctx raw-result refinement-result]
  ...)
```

**Also moving**: `Q_REWARD_UNCITED`, `Q_REWARD_FAILURE`, `confidence->base-reward`, `compute-q-reward` — these are query-specific, not env-level.

**Cost accumulator dedup fix**: extract shared pattern:

```clojure
(defn make-cost-accumulator []
  (atom {:input 0 :output 0 :reasoning 0 :cached 0 :total 0}))

(defn accumulate-cost! [acc cost-map]
  (swap! acc (fn [a] (merge-with + a (select-keys cost-map [:input :output :reasoning :cached :total])))))
```

Use in both `rlm/query.clj` and `rlm/core.clj` — kill the duplicate merge-cost!/accumulate-usage! divergence.

**Files**:
- NEW `src/clj/com/blockether/svar/internal/rlm/query.clj`
- `src/clj/com/blockether/svar/internal/rlm.clj` — `query-env!` becomes ~30 lines: validate, delegate, return
- `src/clj/com/blockether/svar/internal/rlm/core.clj` — use shared cost accumulator

**Verify**: `./verify.sh` green. `test/com/blockether/svar/internal/rlm_test.clj` must pass (all query-env! tests).

**Commit**: `refactor(rlm): decompose query-env! into phased rlm/query.clj`

---

### Phase 6 — Fix Race Condition in `routing.clj`

**What**: `with-depth-tracking` read-then-write on `depth-atom` is not atomic. Two concurrent `sub-rlm-query-batch` calls can exceed max depth.

**Current (broken)**:
```clojure
(if (>= @depth-atom *max-recursion-depth*)   ;; READ
  {:error true}
  (do (swap! depth-atom inc)                  ;; WRITE — gap between read and write
    (try (f) (finally (swap! depth-atom dec)))))
```

**Fix (atomic CAS)**:
```clojure
(let [acquired (volatile! false)]
  (swap! depth-atom
    (fn [d]
      (if (>= d *max-recursion-depth*)
        d
        (do (vreset! acquired true) (inc d)))))
  (if-not @acquired
    {:content "Max recursion depth exceeded" :error true}
    (try (f) (finally (swap! depth-atom dec)))))
```

**Files**:
- `src/clj/com/blockether/svar/internal/rlm/routing.clj`

**Also add test**: `test/com/blockether/svar/internal/rlm/routing_test.clj` — concurrent depth exhaustion test.

**Verify**: `./verify.sh` green.

**Commit**: `fix(rlm): atomic depth check in with-depth-tracking, add concurrency test`

---

### Phase 7 — Harden Exception Handling (swallowed exceptions audit)

**What**: add `trove/log!` at `:warn` or `:debug` to all silent catch blocks. 20+ locations across rlm.clj (now split), core.clj, db.clj, sub.clj, llm.clj.

**Rules**:
- Data-path catches (db.clj fulltext fallback, entity deser, rehydrate) → `:warn` level
- SCI sandbox helper catches (date parsing, format helpers) → `:debug` level (nil-on-bad-input is intended)
- SSE/stream catches (llm.clj) → `:warn` level
- Auto-refine catches (sub.clj) → `:warn` level

**Shape** (every catch block gets):
```clojure
(catch Exception e
  (trove/log! {:level :warn :id ::fn-name-fallback
               :data {:error (ex-message e)} :msg "description"})
  nil) ;; or {} or [] — keep existing fallback
```

**Files**: all files with silent catches (db.clj, core.clj, sub.clj, llm.clj, and the new split modules).

**Also fix**: `auto-refine-async!` in `sub.clj` — check `(:conn @db-info-atom)` before transacting. Don't write to closed DB.

**Verify**: `./verify.sh` green.

**Commit**: `fix(rlm): add logging to 20+ silent catch blocks, guard auto-refine against closed DB`

---

### Phase 8 — Tests for Untested RLM Modules

**What**: 6 RLM internal modules have ZERO tests. Add focused unit tests for the most critical ones.

**Priority order** (risk × complexity):

1. **`routing_test.clj`** — depth tracking (incl. concurrent CAS test from Phase 6), max-depth enforcement
2. **`concurrency_test.clj`** — reentrant semaphore under concurrent load, thread-id keying, deadlock-freedom
3. **`batch_test.clj`** — parallel fan-out ordering, error isolation, semaphore bounding
4. **`skills_test.clj`** — SKILL.md parsing, validation rules, change detection, content hashing
5. **`trajectory_test.clj`** — query scoring, JSONL export format

`sub.clj` tested indirectly via rlm_test.clj integration tests — lower priority.

**Files**:
- NEW `test/com/blockether/svar/internal/rlm/routing_test.clj`
- NEW `test/com/blockether/svar/internal/rlm/concurrency_test.clj`
- NEW `test/com/blockether/svar/internal/rlm/batch_test.clj`
- NEW `test/com/blockether/svar/internal/rlm/skills_test.clj`
- NEW `test/com/blockether/svar/internal/rlm/trajectory_test.clj`

**Verify**: `./verify.sh` green. Test count should increase from ~830 → ~900+.

**Commit**: `test(rlm): add unit tests for routing, concurrency, batch, skills, trajectory`

---

## Phase Dependency Graph

```
Phase 0 (re-export macro)     — independent, do first
Phase 1 (trace.clj)           — independent, easiest extraction
Phase 2 (pageindex.clj)       — independent, biggest line reduction
Phase 3 (qa.clj + manifest)   — independent of 1,2 but touches rlm.clj
Phase 4 (env.clj)             — AFTER 1,2,3 (fewer lines to audit atom access)
Phase 5 (query.clj)           — AFTER 4 (needs env accessor helpers)
Phase 6 (race condition)      — independent, can do anytime
Phase 7 (exception hardening) — AFTER 1-5 (catches move with extractions)
Phase 8 (tests)               — AFTER 6 (routing test needs CAS fix), otherwise independent
```

**Recommended execution order**: 0 → 1 → 2 → 3 → 6 → 4 → 5 → 7 → 8

Each phase = 1 commit. `verify.sh` green after each. No big-bang merge.

---

## Line Count Impact

| File | Before | After |
|------|--------|-------|
| `rlm.clj` | 3535 | ~200 (facade + re-exports from sub-modules) |
| `core.clj` | 628 | ~150 (re-export macro) |
| `rlm/trace.clj` | — | ~80 (new) |
| `rlm/pageindex.clj` | — | ~600 (new, from rlm.clj L1960-3535) |
| `rlm/qa.clj` | — | ~500 (new, from rlm.clj L992-1960) |
| `rlm/qa_manifest.clj` | — | ~200 (new, from rlm.clj L1363-1553) |
| `rlm/env.clj` | — | ~250 (new, from rlm.clj L86-345) |
| `rlm/query.clj` | — | ~350 (new, from rlm.clj L506-909) |

**Net**: ~4163 lines → ~2330 lines across 8 files. Max file: ~600 lines. No file > 700.

---

## Risk Mitigation

1. **Circular deps**: env.clj ← query.clj ← core.clj. No cycles. Sub.clj still uses `requiring-resolve` for core/iteration-loop (unchanged — separate tech-debt ticket).
2. **Atom grouping (Phase 4)**: highest risk. Every deref changes. Mitigate: accessor fns, mechanical find-replace, run tests after each atom merge.
3. **Re-export macro (Phase 0)**: if macro approach causes issues with clj-kondo/lsp, fallback to `potemkin/import-vars` or keep mechanical defs but generate vector types programmatically (still saves ~100 lines).
4. **PageIndex extraction (Phase 2)**: `load-index` forward declaration in rlm.clj → becomes normal require. Simpler.
5. **Test count**: verify.sh enforces >500 cases. Phase 8 adds ~70+ new tests. Must not accidentally lose existing tests during extractions.

---

## What This Plan Does NOT Cover (future work)

- **Circular dep elimination** (sub.clj requiring-resolve) — needs interface/protocol extraction, separate effort
- **Inconsistent error handling** (anomaly vs ex-info vs silent) — convention doc + incremental alignment
- **Half-baked features cleanup** (citations, Q-values, streaming) — product decision: finish or delete
- **Shared HTTP client shutdown** — add shutdown hook, low priority
- **Pricing data dedup in router.clj** — canonical model metadata, incremental
- **`generate-qa-env!` further decomposition** — after Phase 3 extraction, can split phases internally
