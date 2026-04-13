# PLAN.md — svar post-RLM-excision

Style: caveman-full. Machine + agent context.

## What happened

RLM + PageIndex + git ingestion + SQLite store + bench → moved to `../vis`.
svar is now a pure **structured-LLM-output + routing** lib. Previously
entangled Datalog/SQLite + pageindex/skill/RLM machinery is gone. See
commit log for the cutover (commits `0edace75a9` → SQLite cutover,
subsequent commit = full excision).

## Shape now

```
src/clj/com/blockether/svar/
├── core.clj                 — public API (ask!, abstract!, eval!, refine!,
│                              sample!, models!, spec DSL, guards, humanizers)
├── allure_reporter.clj      — lazytest → Allure reporter
└── internal/
    ├── llm.clj              — router + providers + HTTP + token + budget
    ├── jsonish.clj          — tolerant JSON reader for LLM output
    ├── spec.clj             — spec DSL (fields/specs/prompt gen)
    ├── guard.clj            — prompt-injection + moderation guards
    ├── humanize.clj         — AI-phrase stripper
    ├── router.clj           — internal routing helpers
    └── util.clj
```

Deps (deps.edn): anomaly, http-client, charred, trove, jtokkit,
core.async, lazytest. **Nothing DB-shaped, nothing RLM-shaped.**

## Next steps — svar

### Phase A — public API polish (do first)

- [ ] **Update README.md** — strip `create-env` / `query-env!` / `ingest-to-env!` /
      `index!` / `load-index` examples. All RLM + PageIndex doc-test blocks in
      README must be deleted or moved to vis. Re-run `clojure -M:test --md README.md`.
- [ ] **Update CLAUDE.md** — the "SCI Sandbox (RLM)" section is stale here. Move it
      to vis/CLAUDE.md. Keep only: lazytest rules, `clj-paren-repair` rule, caveman
      style, `./verify.sh` pipeline. Benchmarks section belongs to vis now.
- [ ] **Verify `./verify.sh`** — already green (424 cases). Check the `<500` floor
      guard in verify.sh — it warns "expected ~830". Lower the floor to something
      realistic for the pared-down lib, e.g. 300.

### Phase B — release

- [ ] Bump version in `build.clj` / `pom` / deployment metadata. This is a
      breaking change (RLM API gone). Major version bump.
- [ ] CHANGELOG entry: `RLM + PageIndex + git ingestion moved to new vis project.
      svar is now a focused LLM-output + spec lib.`
- [ ] Deploy: `clojure -T:build jar && clojure -X:deploy` — verify Clojars artifact.

### Phase C — no-regret tidying

- [ ] `docs/RECOMMENDATIONS.md` references RLM — audit and strip.
- [ ] `.github/` CI workflow: drop any `:bench` alias invocation (alias gone).
- [ ] `test/com/blockether/svar/internal/spec_test.clj:972` still references
      "rlm.clj" in a docstring comment. Nit — update to "legacy".

## Non-goals (do NOT pull back into svar)

- ❌ Datalog / SQLite / JDBC
- ❌ PageIndex (PDF/markdown/vision extraction)
- ❌ RLM agent loop (SCI, sub-rlm-query, tools, skills, hooks)
- ❌ Git ingestion / JGit
- ❌ QA pipeline / trajectory export
- ❌ Benchmark runner

svar's remit: **take messages → LLM → validated Clojure data.** That's it.
Context-management / agent loops / persistence belong to `vis`.

## Rules

- No circular dep: vis → svar. svar never `require`s anything from
  `com.blockether.vis.*`.
- Keep `deps.edn` minimal. Every line earns its keep.
- `ask!` / `abstract!` / `sample!` stay the core public fns. If an
  RLM feature wants to call LLMs, it calls svar from vis, not the other way.
