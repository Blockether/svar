# SVAR — Recommendations for Users

So you want to use SVAR. Maybe for structured LLM output. Maybe for the RLM (Recursive Language Model) — the agent that reasons by writing and executing Clojure code in a sandbox. Maybe for Q&A generation over your documents. This guide is the opinionated "what actually works, what to avoid" doc.

---

## Which tool should I reach for?

| You want... | Use this | Don't use |
|---|---|---|
| One-shot structured output from any LLM | `ask!` with a spec | RLM (overkill) |
| Summary of a document | `abstract!` (Chain of Density) | `ask!` (no iterative refinement) |
| Self-evaluated / refined LLM output | `refine!` | `ask!` + manual retry loop |
| Generate test data matching a spec | `sample!` | Hand-written fixtures |
| Agent that reads docs + reasons + writes code | `query-env!` (RLM) | `ask!` in a while loop |
| Q&A pairs for fine-tuning | `query-env-qa!` | DIY |
| Index a PDF for later retrieval | `index!` | Custom PDF parsing |
| Block prompt injection / toxic input | `static-guard` + `moderation-guard` | LLM-only moderation |

**Heuristic**: if you need >3 iterations or need the LLM to *read* something before answering → RLM. Otherwise `ask!`.

---

## Core API recommendations

### `ask!` — the workhorse

```clojure
(svar/ask! router
  {:spec  my-spec
   :messages [(svar/system "You are …")
              (svar/user "…")]
   :model "gpt-4o"})
```

**Do:**
- Design your spec with required/optional fields explicit. Required fields force the LLM to answer; optional fields let it say "I don't know" without hallucinating.
- Use `HUMANIZE` on string fields that the LLM tends to over-flower ("Certainly! Here is…").
- Use unions (`:union #{...}`) for "this OR that" answers, not a catch-all `string`.
- Set `:routing {:optimize :cost}` for throwaway calls. Let the router pick a cheap model.

**Don't:**
- Don't parse free-text responses with regex. That's what specs exist for.
- Don't set `:check-context? false` unless you know exactly why. The context-window check saves you from mysterious truncation bugs.
- Don't pass `:model` when `:routing {:optimize :cost}` is set. You'll override the optimizer.

### Routers — cost + reliability

Set up your router once at boot, not per-call:

```clojure
(def router
  (svar/make-router
    [{:id :openai :api-key (env "OPENAI_API_KEY") :base-url "https://api.openai.com/v1"
      :models [{:name "gpt-4o"}]}
     {:id :local :base-url "http://localhost:1234/v1"
      :models [{:name "glm-4" :input-cost 0 :output-cost 0}]}]))
```

**Do:**
- Add a local (zero-cost) provider first. The router will prefer it for `:optimize :cost`.
- Monitor `(svar/router-stats router)` in production. You'll catch budget drift early.
- Call `(svar/reset-provider! router :id)` when a provider recovers after an outage — the circuit breaker stays open otherwise.

**Don't:**
- Don't make a new router per request. The circuit breaker and budget tracker are stateful; you'll reset them every call.

---

## RLM — the agentic engine

### When RLM is worth it

RLM iteratively lets the LLM write Clojure, execute it in a sandbox, see results, and keep going until it calls `FINAL`. Each iteration is a full LLM call. Cost scales with iterations.

**Use RLM when:**
- The answer requires reading many documents (RLM can `search-documents`, `fetch-document-content`).
- The LLM needs to *compute* something (math, filtering, aggregation) the LLM itself is bad at.
- You want the LLM to check its own work via intermediate code execution.

**Don't use RLM when:**
- A single `ask!` with the context inline would work. RLM adds 5-50x latency.
- Your cost budget is tight. A single 50-iter RLM query on gpt-4o can hit several dollars.
- You need deterministic output. RLM trajectories are non-deterministic by design.

### Creating an env

```clojure
(def env
  (svar/create-env router
    {:db "/var/lib/my-app/rlm"           ; persistent — survives restart
     :skills/load-plugins false            ; don't pick up random .claude/skills
     :concurrency {:max-parallel-llm 4     ; cap HTTP fan-out
                   :max-skills-per-call 2
                   :default-timeout-ms 60000}}))
```

**Do:**
- Use `:db "path"` for production. `:temp` erases all state on dispose — fine for tests, terrible for prod.
- Always `(svar/dispose-env! env)` in a `finally` block or shutdown hook. Datalevin keeps file handles open; the JVM won't exit cleanly without dispose.
- Pre-ingest documents with `ingest-to-env!` before querying. Ingestion takes minutes for large PDFs; you don't want the first user query to block on it.

**Don't:**
- Don't create one env per request. The SCI sandbox initialization is expensive (~50-200ms).
- Don't share one env across unrelated tenants. Conversation history and Q-values pool across all queries on the env.

### Queries

```clojure
(svar/query-env! env [(svar/user "Summarize doc-1 into 3 bullets.")]
  {:max-iterations 20      ; hard ceiling — prevents runaway agent
   :threshold 0.8          ; confidence cutoff for refinement
   :verify? true           ; enable claim citation
   :debug? false})         ; flip to true only when debugging
```

**Do:**
- Start with `:max-iterations 20`. If your queries hit the ceiling consistently, your prompt needs work — not more iterations.
- Use `:debug? true` during development. The trace output tells you exactly what the LLM did.
- Pass a cancel atom: `{:cancel-atom my-atom}` — lets your caller interrupt the agent cleanly.

**Don't:**
- Don't set `:max-iterations` > 100. You're hiding a real problem.
- Don't skip `:verify?` for user-facing answers. Uncited claims are unreliable.

### Skills

Skills are sub-RLM recipes in `SKILL.md` files. The main RLM sees a compact manifest (name + short description); bodies are loaded only when the main RLM explicitly invokes `(sub-rlm-query {:skills [:my-skill]})`.

**Do:**
- Put skills in `.svar/skills/my-skill/SKILL.md` (or your custom dir — see "Renaming the .svar directory" below).
- Keep skill bodies under 300 lines. Longer bodies bloat the sub-RLM context.
- Write `When to use` and `When NOT to use` sections — LLMs pick better when constraints are explicit.

**Don't:**
- Don't put skills in `.claude/skills` if you want SVAR-specific behavior. SVAR reads from multiple dirs for compat, but `.svar/skills` wins collisions.
- Don't include secrets in skills. They're stored in Datalevin and flow through traces.

---

## Renaming the `.svar` directory

By default SVAR looks for skills, caches, and project config under `.svar/` at project root. This is customizable:

### Option 1 — environment variable (simplest)

```bash
SVAR_DIR=.myorg clj -M ...
```

SVAR will look for skills at `.myorg/skills/` and save new skills there. Other agent dirs (`.claude/skills`, `.opencode/skills`) still work for compat.

### Option 2 — dynamic rebinding (programmatic)

```clojure
(require '[com.blockether.svar.internal.rlm.skills :as skills])

(binding [skills/*svar-dir* ".myorg"]
  (svar/query-env! env messages opts))
```

Use this when you want different envs in the same JVM to see different skill roots (e.g., multi-tenant setups).

### When to rename

- You maintain a fork / vendor-specific distribution of SVAR and want skills branded under your org.
- You're running multiple agent frameworks side by side and want a namespace.
- You're doing A/B testing between skill sets.

**Don't rename** just to be different. The default `.svar` is what the ecosystem expects.

---

## Q&A generation (`query-env-qa!`)

Generates grounded Q&A pairs for fine-tuning, eval harnesses, or synthetic test sets.

```clojure
(svar/query-env-qa! env
  {:target-count 50           ; was :count pre-refactor — renamed to avoid shadowing
   :difficulty #{:apply :analyze}
   :categories #{:factual :inferential}
   :multi-hop? true            ; cross-section questions
   :verify? true
   :parallel 4})
```

**Do:**
- Pass `:personas #{:student :researcher}` for diverse phrasing. Prevents every question sounding like the same LLM.
- Set `:verify? true`. Verification catches trivial / ungrounded / self-revealing questions.
- Use `:selection-model` (cheap) + `:model` (capable) for Phase 1 / Phase 2 split. Phase 1 just picks passages — use a small model.

**Don't:**
- Don't set `:count` > 500 without persistent `:db`. Phase 2 is parallel and restartable via `qa-manifest.edn` — losing a 500-question run to a crash is painful.
- Don't skip `:verify?` for production eval sets. Unverified pairs include "what does this passage say?" type trivia.

---

## PageIndex

Indexes a PDF/MD/TXT into an EDN + PNGs directory next to the file. Feeds RLM or retrieval pipelines.

```clojure
;; Default vision strategy — single-pass, handles tables/images
(svar/index! "docs/manual.pdf" {:vision-model "gpt-4o" :parallel 5})

;; OCR strategy — faster with local OCR
(svar/index! "docs/manual.pdf"
  {:extraction-strategy :ocr
   :ocr-model "glm-ocr"      ; local via LM Studio
   :text-model "gpt-5-mini"
   :parallel 3})
```

**Do:**
- Use `:extraction-strategy :ocr` for image-heavy PDFs. Two-pass (local OCR → remote text structuring) is ~10x cheaper than vision.
- Set `:parallel 3-5`. Higher causes rate-limit churn; lower wastes capacity.
- Commit `.pageindex/document.edn` to git for deterministic retrieval. Re-indexing is expensive.

**Don't:**
- Don't re-index on every app boot. Check `:document/updated-at` against the source file mtime.
- Don't use `:refine?` true unless accuracy matters more than cost. It doubles the LLM calls per page.

---

## Hooks — observe + mutate

The hook system (`register-hook!` + global lifecycle hooks) lets you observe or modify tool calls, iterations, cancels.

**Use for:**
- Metrics export (`:on-iteration` → StatsD / Prometheus)
- Cost limiting (`:on-iteration-start` checking your external budget)
- Red-teaming (`:wrap` on tool calls, logging adversarial inputs)

**Don't use for:**
- Business logic. Hooks are cross-cutting — putting domain rules in them is a recipe for spaghetti.

---

## Debugging — when things go wrong

### "RLM hit max-iterations without calling FINAL"

1. Check the trace: `(svar/pprint-trace (:trace result))`
2. Look for the LLM trying the same code repeatedly (repetition detector warns at 3x). This means the LLM is stuck — prompt issue.
3. Look for tool calls returning massive content (>10KB). The LLM is probably confused by the blob. Shard your docs or use `search-documents` with `:top-k 3`.

### "Cost is higher than expected"

1. `(svar/router-stats router)` — which provider / model burned the tokens?
2. Are you caching? Providers like Anthropic charge less on cached input. Use the provider's cache headers.
3. Set `:routing {:optimize :cost}` on `sub-rlm-query` calls — the sub-RLM doesn't need the smartest model.

### "Tests fail sporadically in CI"

Integration tests hit the real LLM endpoint. They will occasionally fail due to:
- Model output drift (the LLM returns something slightly different than the spec expects)
- Provider hiccups (rate limits, upstream 500s)

**Do:** gate them behind `(when (integration-tests-enabled?) ...)` and run them nightly, not per-PR. Use `make-mock-ask-response` for unit tests.

### "Datalevin conn errors on dispose"

Your code held a reference to a query inside a `(future ...)` that outlived the env. The future tried to transact after `dispose-env!` closed the conn.

**Fix:** wait for all futures before disposing. The skill auto-refine path is fire-and-forget (noted in PLAN.md Phase 7) — if this hits you in production, pin your queries to a cancel-atom and dispose only after all are done.

---

## Rules of thumb

1. **Default to simple.** Try `ask!` first. Reach for RLM only when you've proven you need it.
2. **Always have a fallback model.** Routers + circuit breakers only help if there's somewhere to fall back TO.
3. **Instrument before you scale.** `router-stats`, trace pprint, and the `:debug?` flag exist for a reason.
4. **Caveman communication in RLM system prompts.** Tokens cost money. Terse = cheaper + faster. See CLAUDE.md caveman rules.
5. **Persistent DB > in-memory DB in production.** Always.
6. **Dispose envs. Always.** Use `with-open`-style patterns.
7. **Cancel atoms are free insurance.** Every query-env! call should accept one.
8. **Skill bodies are secrets-adjacent.** They flow through traces. Scrub before logging.

---

## Further reading

- [`README.md`](../README.md) — full API reference + doctests
- [`CLAUDE.md`](../CLAUDE.md) — agent guidelines + communication rules
- [`PLAN.md`](../PLAN.md) — active refactor plan + known issues
- [`CHANGELOG.md`](../CHANGELOG.md) — version history + breaking changes
