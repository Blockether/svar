# Plan: RLM Explicit State Redesign

> Source PRD: Grill session — 19 design decisions converged on 2026-03-31

## Architectural decisions

Durable decisions that apply across all phases:

- **Single state primitive**: SCI `def` with Clojure docstrings. No P-atom, no ctx-* functions, no system-generated vars. The model owns its namespace completely.
- **Prompt model**: Single-shot per iteration. No message history accumulation. The system builds a fresh prompt containing: system prompt, conversation thread, execution journal, execution results, carried vars, var index.
- **Response format**: `{"thinking": "string", "code": ["string"], "carry": ["var-name"], "final": null | {"answer": "string", "confidence": "high|medium|low", "summary": "string"}}`
- **Finalization**: `final` field in response — not a code function. When `final` is non-null, loop stops, `code` is ignored. Mutually exclusive.
- **Var index**: Always visible. Shows ALL vars ever def'd: name / type / size / doc (from Clojure docstring metadata).
- **Carry mechanism**: `carry` field in response lists var names whose full values are injected into the next iteration's prompt. Everything else appears only in the compact var index.
- **Execution journal**: Thinking text (journal-only, not a var) + var names per iteration. Cumulative mechanical squash every 5 iterations (concat with `---` separators).
- **Execution results**: Transient structured receipt from last iteration only. Shows success/failure, result-type, size, errors per code block.
- **Cross-turn persistence**: `final-result-N` stored in Datalevin, available across sessions. Model writes `summary` field used as docstring in var index.
- **Conversation thread**: Compact `[N] "query" → final-result-N` linkage shown in prompt.
- **Killed tools**: `rlm-query`, `FINAL` (SCI function), P-atom, ctx-add!/remove!/clear!/replace!, search-history, get-history, history-stats.
- **Kept tools**: `llm-query`, `llm-query-batch`, all document tools, `request-more-iterations` (max 50, extendable to 500).
- **Var GC**: None. All vars stay in index forever. SCI context holds values.

---

## Phase 1: Single-Shot Prompt + Var Index

**User stories**: def vars as primary state, var index always visible, docstrings as self-documentation

### What to build

Replace the message-accumulation loop with a single-shot prompt architecture. Each iteration, the system builds a fresh prompt from scratch — no growing `messages` vector.

Build var introspection infrastructure that extracts name, type, size, and docstring from all user-def'd vars in the SCI context. Render as a `<var_index>` table in the prompt.

End-to-end: modify `ITERATION_SPEC` in schema.clj → rewrite prompt builder in core.clj → restructure `iteration-loop` to build fresh prompts per iteration → implement var index extraction from SCI context (using `(meta (var x))` for `:doc`).

The loop should still work with the existing FINAL function and P-atom during this phase — those get removed later. The key change is: messages don't accumulate, and the var index appears in the prompt.

### Acceptance criteria

- [ ] Each iteration sends exactly one system message + one user message to the LLM (no multi-turn history)
- [ ] Var index section rendered in prompt showing name/type/size/doc for all user-def'd vars
- [ ] Docstrings on `def` are extracted via `(:doc (meta (var x)))` and appear in var index
- [ ] Vars without docstrings show `—` in the doc column
- [ ] Existing RLM tests pass (FINAL and P-atom still work during this transitional phase)
- [ ] A simple query that defs multiple vars shows them all in the var index on subsequent iterations

---

## Phase 2: Carry Mechanism + Execution Results

**User stories**: model explicitly selects what carries forward, structured execution feedback

### What to build

Add `carry` field to the response format (iteration spec). After parsing the LLM response, extract the carry list. When building the next iteration's prompt, inject full values of carried vars in a `<carried_vars>` section.

Add a transient `<execution_results>` section to the prompt showing structured receipts from the previous iteration's code executions: success/failure, result-type, size, error messages, stdout/stderr.

End-to-end: update `ITERATION_SPEC` / `ITERATION_SPEC_CODE_ONLY` in schema.clj → parse `carry` from LLM response in `run-iteration` → build `<carried_vars>` section in prompt builder → build `<execution_results>` section from previous iteration's execution data.

### Acceptance criteria

- [ ] `ITERATION_SPEC` includes `carry` as an optional array of strings
- [ ] Carried var values are serialized into `<carried_vars>` section in the prompt
- [ ] Vars not in the carry list appear only in the var index (name/type/size/doc, no value)
- [ ] `<execution_results>` shows per-code-block: success?, result-type, size, error, stdout, stderr
- [ ] Execution results are transient — only the last iteration's results appear
- [ ] System prompt documents the carry mechanism and execution results format

---

## Phase 3: Execution Journal + Squash

**User stories**: compressed execution journal, squash every 5 iterations

### What to build

Introduce an execution journal data structure that tracks per-iteration: thinking text + var names created/carried. Render it as an `<execution_journal>` section in the prompt.

Implement cumulative mechanical squash: every 5 iterations, all previous journal entries get concatenated with `---` separators into a single squashed block. After squash, the prompt shows: one squashed block + up to 5 recent unsquashed entries.

Thinking is journal-only — it never appears in the var index or as a def'd var.

End-to-end: create journal data structure (vector of `{:iteration N :thinking "..." :var-names [...]}`) → render `<execution_journal>` in prompt builder → implement squash logic (every 5 iterations, cumulative concat) → track which vars were created per iteration.

### Acceptance criteria

- [ ] Execution journal rendered in prompt with thinking + var names per iteration
- [ ] At iteration 6, iterations 1-5 are squashed into a single block with `---` separators
- [ ] Squash is cumulative — at iteration 11, one block covers iterations 1-10
- [ ] Unsquashed recent iterations (up to 5) shown individually below the squashed block
- [ ] Thinking text never appears in the var index
- [ ] Journal grows linearly; squash block is mechanical concat (no LLM calls)

---

## Phase 4: Final as Response Field + Persistence

**User stories**: final in response format not code, final-result-N persisted in Datalevin

### What to build

Move finalization from the `FINAL` SCI function to a `final` field in the response format. When `final` is non-null, the loop stops immediately and `code` is ignored (mutually exclusive).

The `final` map shape: `{answer: string, confidence: high|medium|low, summary: string}`.

After loop termination, persist the result as `final-result-N` in Datalevin with a new schema entity type. The `summary` field becomes the docstring in the var index.

End-to-end: update `ITERATION_SPEC` to include `final` field → modify `run-iteration` to detect non-null final before code execution → add `final-result` entity to Datalevin schema → implement `db-store-final-result!` → store on loop termination.

### Acceptance criteria

- [ ] `final` field in response spec: `null | {answer, confidence, summary}`
- [ ] When `final` is non-null, code blocks are not executed
- [ ] `final` detection replaces `check-result-for-final` (no more `:rlm/final` sentinel)
- [ ] `final-result-N` entity stored in Datalevin with answer, confidence, summary, timestamp, env-id
- [ ] System prompt documents the `final` field and its mutually-exclusive relationship with `code`

---

## Phase 5: Conversation Thread + Cross-Session Continuity

**User stories**: compact conversation thread, final-result-N accessible across sessions

### What to build

Build a compact `<conversation>` section in the prompt that shows all previous user queries linked to their final results: `[N] "user query" → final-result-N`.

On new query start, rehydrate previous `final-result-N` vars from Datalevin into the var index. The model can carry any previous final result to inspect its full value.

End-to-end: query Datalevin for previous final results on loop start → inject into SCI context as def'd vars with summary as docstring → render `<conversation>` section in prompt → ensure final-result vars appear in var index alongside current loop vars.

### Acceptance criteria

- [ ] `<conversation>` section shows `[N] "query text" → final-result-N` for all previous turns
- [ ] Current query shown as `[current] "query text"`
- [ ] Previous `final-result-N` vars appear in var index with summary as docstring
- [ ] Model can carry a previous `final-result-N` to get its full value in `<carried_vars>`
- [ ] Cross-session: starting a new process against the same Datalevin DB shows previous final results
- [ ] Empty conversation (first query) renders cleanly with just `[current]`

---

## Phase 6: Legacy Cleanup

**User stories**: remove all the P-atom ctx-* bullshit

### What to build

Remove all legacy state mechanisms that have been superseded by the explicit var model:

- **P-atom**: Remove from `create-rlm-env`, `create-sci-context`, all prompt builder references
- **ctx-add! / ctx-remove! / ctx-clear! / ctx-replace!**: Remove SCI bindings and implementations
- **FINAL function**: Remove from SCI bindings (replaced by response field)
- **rlm-query**: Remove SCI binding and `run-sub-rlm` implementation
- **search-history / get-history / history-stats**: Remove SCI bindings
- **System prompt**: Remove all references to killed tools, update workflow guidance, update critical section
- **Auto-appended iteration summaries**: Remove from iteration loop (replaced by execution journal)

Update system prompt to document only the new model: def with docstrings, carry mechanism, final field, var index, execution journal.

### Acceptance criteria

- [ ] No references to P-atom, `@P`, or `:context` vector in core.clj or tools.clj
- [ ] `ctx-add!`, `ctx-remove!`, `ctx-clear!`, `ctx-replace!` bindings removed
- [ ] `FINAL` function binding removed from SCI context
- [ ] `rlm-query` binding and `run-sub-rlm` removed
- [ ] `search-history`, `get-history`, `history-stats` bindings removed
- [ ] System prompt reflects only the new explicit state model
- [ ] No auto-appended iteration summaries in the loop
- [ ] All existing tests updated to reflect new architecture
- [ ] `verify.sh` passes clean
