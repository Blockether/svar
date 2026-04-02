# Plan: Streaming Support

> Source PRD: Grill-me interview — SSE streaming with callback-based API

## Architectural decisions

Durable decisions that apply across all phases:

- **Protocol**: OpenAI-compatible SSE streaming (`stream: true`, `data: {...}\n\n` events, `data: [DONE]` terminator)
- **API pattern**: `:on-chunk` callback in opts map. Optional — omit for synchronous behavior. Return value unchanged.
- **Callback shapes**:
  - `chat-completion`: accumulated text string (full state, not deltas)
  - `ask!`: spec-coerced partial map with nils for unfinished fields
  - `query-env!`: `{:iteration N :thinking str :code [str] :final nil/result-map}`
- **Partial semantics**: Strings accumulate incrementally. Numbers atomic (nil → 42). Lists grow by appending. Missing fields nil.
- **Error handling**: Stream drops = transient error = router retries with next provider. Never return partial as final.
- **Scope**: Only `ask!`, `chat-completion`, and `query-env!` support streaming. `refine!`, `abstract!`, `eval!`, `sample!` stay synchronous.
- **Implementation**: All streaming code in `llm.clj` next to existing HTTP functions. No new namespaces.
- **Parser**: Java `JsonishParser` already has `CompletionState` tracking for partial/incomplete parsing. Expose through Clojure `jsonish` wrapper.

---

## Phase 1: SSE Reader + Raw Text Streaming (`chat-completion`)

**User stories**: As a developer, I want to see LLM text appearing in real-time via a callback, so I can build responsive UIs and avoid timeout issues with long-running calls.

### What to build

End-to-end streaming from HTTP request to callback invocation. When `:on-chunk` is provided in `chat-completion` opts, add `stream: true` to the request body and switch from `http-post!` (buffered) to a new streaming HTTP call that reads SSE events from the response InputStream. Parse each `data: {...}` line, extract the content delta, accumulate into a full text string, and fire the callback with the accumulated text after each chunk. Handle `data: [DONE]` as stream completion. Accumulate `api-usage` from the final chunk (OpenAI sends usage in the last event when `stream_options: {include_usage: true}`). Return the same `{:content :reasoning :api-usage}` shape as the non-streaming path. Stream errors (connection drops, malformed SSE) are thrown as exceptions — the router's `with-provider-fallback` treats them as transient and retries. Also handle reasoning content streaming (Anthropic extended thinking format — array of blocks with `type: "thinking"` and `type: "text"`).

### Acceptance criteria

- [ ] `chat-completion` accepts `:on-chunk` callback in opts
- [ ] When `:on-chunk` present, HTTP request includes `"stream": true` and `"stream_options": {"include_usage": true}`
- [ ] SSE events are parsed line-by-line from the response InputStream
- [ ] Callback receives accumulated text (not deltas) after each content chunk
- [ ] `data: [DONE]` terminates the stream cleanly
- [ ] Final return value is identical to non-streaming: `{:content :reasoning :api-usage}`
- [ ] `api-usage` is captured from the final SSE event
- [ ] Connection drops mid-stream throw an exception (transient error for router retry)
- [ ] `routed-chat-completion` passes `:on-chunk` through to `chat-completion`
- [ ] Retry logic (`with-retry`) works with streaming — retries the full stream on transient error
- [ ] Non-streaming path is completely unchanged when `:on-chunk` is absent
- [ ] Unit tests for SSE parsing (mock InputStream with known SSE events)
- [ ] Unit tests for accumulated text callback behavior

---

## Phase 2: Structured Streaming (`ask!`)

**User stories**: As a developer, I want to see structured output fields filling in progressively as the LLM generates them, so I can build UIs that show partial results with proper types.

### What to build

Wire `ask!` to use streaming `chat-completion` and parse the accumulated text into a spec-coerced partial map on each chunk. Expose the Java `JsonishParser`'s partial parsing capability through the Clojure `jsonish` wrapper — the parser already tracks `CompletionState.INCOMPLETE` for unclosed strings/objects/arrays. On each chunk from `chat-completion`, run the accumulated text through jsonish partial parsing, then through spec type coercion (`str->data-with-spec`). The callback receives a map where complete fields have their proper types (int, float, keyword, etc.) and incomplete/missing fields are nil. Numbers only appear when complete (atomic — never partial digits). Strings accumulate character by character. The final callback and return value use the same full validation path as non-streaming.

### Acceptance criteria

- [ ] `ask!` accepts `:on-chunk` callback in opts
- [ ] `jsonish` Clojure wrapper exposes a partial-parse function that handles truncated JSON
- [ ] On each chunk: accumulated text → jsonish partial parse → spec coercion → callback
- [ ] Callback receives a map with proper types for complete fields, nil for incomplete/missing
- [ ] Numbers are atomic: nil until fully parsed, then the correct int/float
- [ ] Strings accumulate incrementally
- [ ] Lists grow by appending complete items
- [ ] Final return value identical to non-streaming `ask!`
- [ ] Errors during partial parsing are silently skipped (callback not fired for unparseable chunks)
- [ ] Unit tests for partial spec coercion with various truncation points
- [ ] Integration test: `ask!` with `:on-chunk` returns same result as without

---

## Phase 3: RLM Iteration Streaming (`query-env!`)

**User stories**: As a developer, I want to see the RLM's thinking in real-time during each iteration, so I can show "the LLM is reasoning..." progress in a UI, and receive the final answer via the same callback.

### What to build

Wire `query-env!` to accept `:on-chunk` and pass it through to the iteration loop. During each iteration, `routed-chat-completion` streams with a callback that parses the partial JSON response to extract `thinking` and `code` fields. The `query-env!` callback fires with `{:iteration N :thinking str :code [str] :final nil}` as tokens arrive. When the iteration loop completes (FINAL detected or budget exhausted), fire one last callback with `:final` set to the full result map including `:answer`, `:confidence`, `:summary`, `:iterations`, `:status`. The `:thinking` and `:code` fields in the final callback reflect the complete values from the last iteration.

### Acceptance criteria

- [ ] `query-env!` accepts `:on-chunk` callback in opts
- [ ] During iteration streaming, callback receives `{:iteration N :thinking str :code [str] :final nil}`
- [ ] `:thinking` field accumulates incrementally as the LLM generates it
- [ ] `:code` field populates as code blocks are completed in the JSON
- [ ] On loop completion, callback fires with `:final` set to full result map
- [ ] Final callback includes complete `:thinking` and `:code` from the last iteration
- [ ] `query-env!` return value unchanged regardless of streaming
- [ ] Non-streaming path completely unchanged when `:on-chunk` absent
- [ ] Streaming works correctly across multiple iterations (callback fires for each)
- [ ] Integration test: `query-env!` with `:on-chunk` and mock chat returns same result as without
