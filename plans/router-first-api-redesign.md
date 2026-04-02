# Plan: Router-First API Redesign

> Source PRD: Grill-me interview — eliminate split-brain config/routing architecture

## Architectural decisions

Durable decisions that apply across all phases:

- **Single entry point**: `make-router` is the only way to create an LLM connection. No `make-config`, no env-var magic.
- **Router shape**: Plain Clojure map with atoms for mutable state. No protocols, no components.
- **Signature convention**: Every public function that needs an LLM takes `router` as its first positional argument. Env-bound functions (`query-env!`, `ingest-to-env!`, etc.) use the router stored in the env.
- **Namespace consolidation**: `config.clj` + `providers.clj` + `tokens.clj` → single `defaults.clj` (data + logic).
- **Breaking change strategy**: Hard break, no deprecation period.
- **No convenience helpers**: No `router` shorthand, no `simple-router`. Always explicit vector of providers.
- **Router-level defaults with per-provider overrides**: `:network` and `:tokens` can be set at the router level and overridden per provider.

---

## Phase 1: Namespace Consolidation (`defaults.clj`)

**User stories**: Eliminate scattered data/logic across three namespaces; single source of truth for pricing, context limits, normalization, tokenization.

### What to build

Create `defaults.clj` by merging the contents of `config.clj`, `providers.clj`, and `tokens.clj`. Move all constants (DEFAULT_MODEL, DEFAULT_BASE_URL, DEFAULT_TIMEOUT_MS, DEFAULT_RETRY, KNOWN_PROVIDERS, KNOWN_MODEL_METADATA, MODEL_PRICING, MODEL_CONTEXT_LIMITS, DEFAULT_MODEL_PRICING, DEFAULT_CONTEXT_LIMITS, DEFAULT_OUTPUT_RESERVE) and all logic (provider normalization, model inference, tokenization, cost estimation, context checking) into this one namespace. Update all 9 import sites (7 src + 2 test) to reference `defaults` instead. Delete the three original files. No API or behavioral changes — purely a move.

### Acceptance criteria

- [ ] `defaults.clj` exists with all data and logic from the three merged files
- [ ] `config.clj`, `providers.clj`, `tokens.clj` are deleted
- [ ] All 9 files that imported from the old namespaces compile against `defaults`
- [ ] `config_test.clj` and `tokens_test.clj` pass (updated imports, same assertions)
- [ ] `./verify.sh` passes with no regressions

---

## Phase 2: Router State — Circuit Breaker, Budget, Observability

**User stories**: Router becomes a richer stateful object with production-grade reliability features and visibility into LLM spend.

### What to build

Enhance the router map returned by `make-router` with three new capabilities:

**Circuit breaker** — classic three-state (closed → open → half-open) per provider. Replace the current simple cooldown mechanism. Configurable failure threshold, recovery timeout, and half-open probe behavior. State tracked in the router's atom alongside existing rate-limit data.

**Token budget** — aggregate spend tracking (total tokens and estimated cost) with pre-flight rejection. Before making an HTTP call, estimate the request cost; if it would exceed the configured budget, throw `{:type :svar/budget-exhausted}` without sending the request. Budget configuration via `:budget` key in router opts (`{:max-tokens N :max-cost N}`).

**Observability** — `(router-stats router)` returns cumulative lifetime stats and current-window stats per provider: request count, token counts (input/output), estimated cost, average latency, and circuit breaker state.

**Management functions** — `(reset-budget! router)` zeroes the spend counters. `(reset-provider! router provider-id)` manually resets a tripped circuit breaker to closed.

### Acceptance criteria

- [ ] Circuit breaker transitions: closed → open after N failures, open → half-open after timeout, half-open → closed on success / open on failure
- [ ] Budget pre-flight: requests rejected before HTTP call when budget would be exceeded
- [ ] `router-stats` returns cumulative + windowed data per provider
- [ ] `reset-budget!` zeroes spend counters; subsequent requests succeed within new budget
- [ ] `reset-provider!` resets a provider's circuit breaker to closed
- [ ] Existing rate-limit and cooldown behavior replaced (not duplicated) by circuit breaker
- [ ] Unit tests for all three features
- [ ] `./verify.sh` passes

---

## Phase 3: `make-router` Redesign

**User stories**: Single explicit entry point for LLM connection; no config concept, no env-var fallback.

### What to build

Redesign `make-router` to be the sole entry point:

```clojure
(make-router
  [{:id :openai :api-key "sk-..." :models [{:name "gpt-4o"}]
    :network {:timeout-ms 15000}}]  ;; per-provider override
  {:network {:timeout-ms 30000 :max-retries 3}  ;; router-level defaults
   :tokens {:check-context? true}
   :budget {:max-tokens 1000000 :max-cost 5.0}})
```

Remove all env-var fallback logic (`OPENAI_API_KEY`, `BLOCKETHER_LLM_API_KEY`, etc.). The user passes `:api-key` explicitly — if they want env vars, they call `(System/getenv ...)` themselves. Delete `make-config` and `config->router`. Router-level `:network` and `:tokens` become defaults that individual providers can override.

### Acceptance criteria

- [ ] `make-router` accepts `(providers)` and `(providers opts)` arities
- [ ] No env-var reads anywhere in router creation
- [ ] Per-provider `:network`/`:tokens` overrides merge over router-level defaults
- [ ] `make-config` function deleted
- [ ] `config->router` function deleted
- [ ] `sanitize-config` updated or removed (no config to sanitize)
- [ ] Unit tests for new `make-router` contract
- [ ] `./verify.sh` passes

---

## Phase 4: Router-First Signatures — LLM Functions

**User stories**: Consistent `(fn router opts)` signature for all direct LLM calls.

### What to build

Change the 5 public routed LLM functions and `models!` to take router as first positional argument:

- `(ask! router opts)` — was `(ask! opts)` with `:router`/`:config` in opts
- `(abstract! router opts)` — same
- `(eval! router opts)` — same
- `(refine! router opts)` — same
- `(sample! router opts)` — same
- `(models! router)` or `(models! router opts)` — same

Change all internal `*` functions (`ask!*`, `abstract!*`, `eval!*`, `refine!*`, `sample!*`) to also take router as first positional argument, replacing the `:api-key`/`:base-url`/`:model`/`:provider-id` keys they currently receive. The router is threaded explicitly through every layer.

Update all internal call sites within `llm.clj` where these functions call each other (e.g., `eval!` calling `ask!*`, `refine!` calling `refine!*`).

### Acceptance criteria

- [ ] All 5 public LLM functions + `models!` take `(router opts)` or `(router)` as signature
- [ ] All internal `*` functions take router as first positional arg
- [ ] No function in `llm.clj` reads `:config` or `:router` from an opts map
- [ ] Internal call chains thread router explicitly
- [ ] Unit tests updated and passing for all changed functions
- [ ] `./verify.sh` passes

---

## Phase 5: Router-First Signatures — RLM & PageIndex

**User stories**: Router-first for env creation and document indexing; env functions unchanged.

### What to build

Change `create-env` to `(create-env router opts)`. The env stores the router internally. All env-bound functions (`query-env!`, `ingest-to-env!`, `dispose-env!`, `generate-qa-env!`, `save-qa!`, `list-trajectories`, `export-trajectories!`) continue taking `env` as first arg — they extract the router from it.

Update the ~6 call sites in `rlm.clj` that pass `:router` or `:config` to LLM functions — these now pass the router positionally. Update the 3 call sites in `rlm/core.clj`. Update `rlm/routing.clj` (`make-routed-llm-query-fn`) to thread router positionally.

Change `index!` to `(index! router opts)`. `load-index` stays unchanged (no LLM needed).

Update `rlm/pageindex/vision.clj` — internal functions that pass `:config`/`:router` to LLM calls switch to positional router.

### Acceptance criteria

- [ ] `create-env` takes `(router opts)` signature
- [ ] Env stores router; env-bound functions extract it internally
- [ ] All `rlm.clj` call sites pass router positionally to LLM functions
- [ ] All `rlm/core.clj` call sites updated
- [ ] `rlm/routing.clj` threads router positionally
- [ ] `index!` takes `(router opts)` signature
- [ ] `load-index` unchanged
- [ ] RLM test suite passes with updated fixtures
- [ ] `./verify.sh` passes

---

## Phase 6: Public API & Documentation

**User stories**: Clean public surface, updated docs, no references to old config concept.

### What to build

Update `core.clj` re-exports:
- Remove `make-config` re-export
- Add `make-router` re-export
- Add `router-stats`, `reset-budget!`, `reset-provider!` re-exports
- Update all function re-exports to match new signatures

Update all 4 test files (36+ references):
- `config_test.clj` → becomes `defaults_test.clj` or router creation tests
- `rlm_test.clj` — all `{:config test-config}` → router-first calls
- `pageindex_test.clj` — update `:config` references
- `core_test.clj` — update any `make-config` references

Rewrite README.md:
- Replace `make-config` example with `make-router`
- Update all `ask!`/`refine!`/etc. examples to router-first signatures
- Add examples for `router-stats`, budget, circuit breaker
- Ensure all code blocks pass lazytest doctests

### Acceptance criteria

- [ ] `core.clj` exports `make-router`, `router-stats`, `reset-budget!`, `reset-provider!`
- [ ] `core.clj` no longer exports `make-config`
- [ ] No test file references `make-config`, `:config`, or `config->router`
- [ ] README.md uses router-first examples throughout
- [ ] `clojure -M:test --md README.md` passes (all doc tests green)
- [ ] `./verify.sh` passes with full test count (~830+ cases)
- [ ] No references to `config.clj`, `providers.clj`, or `tokens.clj` anywhere in the codebase
