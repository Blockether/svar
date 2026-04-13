# SVAR — Recommendations for Users

SVAR is a structured LLM output + routing library. This guide covers what works, what to avoid.

---

## Which tool should I reach for?

| You want... | Use this | Don't use |
|---|---|---|
| One-shot structured output from any LLM | `ask!` with a spec | Manual JSON parsing |
| Summary of a document | `abstract!` (Chain of Density) | `ask!` (no iterative refinement) |
| Self-evaluated / refined LLM output | `refine!` | `ask!` + manual retry loop |
| Generate test data matching a spec | `sample!` | Hand-written fixtures |
| Block prompt injection / toxic input | `static-guard` + `moderation-guard` | LLM-only moderation |

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

## Debugging — when things go wrong

### "Cost is higher than expected"

1. `(svar/router-stats router)` — which provider / model burned the tokens?
2. Are you caching? Providers like Anthropic charge less on cached input. Use the provider's cache headers.
3. Set `:routing {:optimize :cost}` to let the router pick cheaper models automatically.

### "Tests fail sporadically in CI"

Integration tests hit the real LLM endpoint. They will occasionally fail due to:
- Model output drift (the LLM returns something slightly different than the spec expects)
- Provider hiccups (rate limits, upstream 500s)

**Do:** gate them behind `(when (integration-tests-enabled?) ...)` and run them nightly, not per-PR. Use `make-mock-ask-response` for unit tests.

---

## Rules of thumb

1. **Default to simple.** Try `ask!` first.
2. **Always have a fallback model.** Routers + circuit breakers only help if there's somewhere to fall back TO.
3. **Instrument before you scale.** `router-stats` and the `:debug?` flag exist for a reason.
4. **Terse prompts = cheaper + faster.** Tokens cost money.

---

## Further reading

- [`README.md`](../README.md) — full API reference + doctests
- [`CLAUDE.md`](../CLAUDE.md) — agent guidelines
- [`CHANGELOG.md`](../CHANGELOG.md) — version history + breaking changes
