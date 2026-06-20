# svar native tool calling — design

Goal: replace fence-based `ask-code!` with **native tool calling** across all wires.
"No tool call in the response = the turn is the answer" (maki / Claude-Code model),
enforced by the API shape — impossible for the model to get wrong with a stray fence.

## Wires (only 3)

| api-style | providers | call dict | result item |
|---|---|---|---|
| `:anthropic` | Claude direct, copilot-Claude, **GLM/zai** (z.ai anthropic endpoint) | `tool_use` content block | `tool_result` content block (user msg) |
| `:openai-compatible-chat` | **Gemini** (OpenAI gateway), copilot misc | `message.tool_calls[]` | `{role:"tool", tool_call_id, content}` msg |
| `:openai-compatible-responses` | codex/gpt, copilot-GPT | `function_call` output item | `function_call_output` input item |

**GLM rides the `:anthropic` wire**, NOT chat-completions. z.ai exposes an
Anthropic-Messages-compatible endpoint (`https://api.z.ai/api/anthropic/v1`, the
one Claude Code drives via `ANTHROPIC_BASE_URL`). svar already routes ANY base-url
through the `:anthropic` builder (`make-chat-url` appends `/messages`; ollama/lmstudio
do exactly this — router.clj:151). So the two primary targets (Claude + GLM) share
the cleanest wire with free `tool_use` round-trip. Provider config:
`{:base-url "https://api.z.ai/api/anthropic/v1" :api-style :anthropic :api-key <coding-key>}`.
This also dodges the GLM chat-completions cache bug (svar 0.7.30 era).

Central dispatch already exists: `chat-completion` (llm.clj:3250) → responses vs
streaming vs retry; each selects its body builder by `:api-style`. `:tools` just
has to flow through `opts` to every body builder.

## Canonical abstraction (wire-agnostic)

Follows svar's EXISTING thinking-block hoist pattern (anthropic native; chat →
`reasoning_content`; responses → reasoning items). Tool calls/results are new
canonical block types that hoist the same way.

### Tool definition (caller input)
```clojure
{:name "run_python"
 :description "Execute Python in the sandbox; tools are functions."
 :schema {:type "object"
          :properties {"code" {:type "string"}}
          :required ["code"]}}
```

### Canonical tool-call block (on assistant message :content)
```clojure
{:type "tool_use" :id "toolu_…" :name "run_python" :input {"code" "rg({...})"}}
```
- anthropic — native, already round-trips (`anthropic-wire->canonical-block` :else
  passthrough, llm.clj:1199; `anthropic-block` passthrough).
- chat — **hoist to message level** in `build-request-body` (1970):
  `:tool_calls [{:id id :type "function" :function {:name name :arguments (json/encode input)}}]`
  (exactly like `reasoning_content` is hoisted, 2006).
- responses — **hoist to an input item** in `responses-message-input-entries` (1847):
  `{:type "function_call" :call_id id :name name :arguments (json/encode input)}`
  (placed like reasoning items are, before/with the message).

### Canonical tool-result block (on a user/"tool" message :content)
```clojure
{:type "tool_result" :tool_use_id "toolu_…" :content "<stdout / value text>"}
```
- anthropic — native user content block (passthrough).
- chat — **split into its own message** `{:role "tool" :tool_call_id id :content …}`
  (build-request-body must expand these out of content into sibling messages).
- responses — input item `{:type "function_call_output" :call_id id :output …}`.

## Request serialization — new `tools->wire`
- anthropic: `{:name :description :input_schema schema}` → body `:tools`
- chat: `{:type "function" :function {:name :description :parameters schema}}` → body `:tools`
- responses: `{:type "function" :name :description :parameters schema}` → body `:tools`
- `:tool-choice` → anthropic `{:type "auto"|"any"|"tool" :name}`,
  chat `"auto"|"required"|{:type "function" :function{:name}}`,
  responses `"auto"|"required"|{:type "function" :name}`.

Inject in: `build-anthropic-request-body` (1085), `build-request-body` (1970),
`build-openai-responses-request-body` (1865).

## Response extraction — add `:tool-calls`
- anthropic `extract-anthropic-response-data` (1238): filter content for
  `{:type "tool_use"}` → `:tool-calls [{:id :name :input}]`.
- chat/responses `extract-response-data` (1713): read
  `choices[0].message.tool_calls` (chat) and `output[]` `function_call` items
  (responses); `json/decode` the `:arguments` string → `:input`.
- Each extractor must ALSO keep the tool_use blocks on `:assistant-message` so the
  round-trip replays them (anthropic free; chat/responses: add to the canonical
  assistant builders 1687 / 1673).

## Streaming deltas (accumulate partial tool calls)
- anthropic: `content_block_start{tool_use}` + `input_json_delta` (append) +
  `content_block_stop` → finalize input json. (`make-anthropic-stream-delta-fn` 1272.)
- chat: `choices[].delta.tool_calls[]` index-keyed arg fragments. (`extract-stream-delta` 2360.)
- responses: `response.output_item.added{function_call}` +
  `response.function_call_arguments.delta` + `.done`.

## Public API (core.clj) — FINAL SHAPE

Tools live on **`ask-code!`** (keeps its name; mechanism changed from fence
extraction → native tool calling). `ask!` stays structured-`:spec`-only and is
NOT given tools. `extract-code-blocks` + `internal/codes.clj` + `:lang` /
`:lenient` / `:code-tail-pointer?` + the fence normalizer are DELETED.

```clojure
(svar/ask-code! router
  {:messages [...]        ; canonical, includes prior tool_use / tool_result blocks
   :tools [tool-def …]
   :tool-choice :auto     ; :auto | :required | {:name "…"}
   :reasoning-level :medium
   :on-chunk f})
;; =>
{:stop-reason :tool-calls          ; model wants to act
 :tool-calls [{:id :name :input}]
 :content nil-or-text
 :reasoning "…"
 :assistant-message {…}            ; MUST append to :messages next call
 :api-usage {…} :tokens {…} :raw …}
;; OR
{:stop-reason :end                 ; NO tool call => this IS the answer
 :tool-calls []
 :content "the final answer text"
 :assistant-message {…} :reasoning "…" :api-usage {…} :tokens {…} :raw …}
```

## Removals
- svar: `ask-code!`, `ask-code!*` (4667 / 4455), `extract-code-blocks`,
  fence machinery in `internal/codes.clj` used only by ask-code.
- KEEP `ask!` (structured spec) — independent of fences, untouched.
- vis (separate change): fence reader, `done()`, the done-gate, finalize-prose
  detection — all replaced by the tool loop.

## vis integration (one tool)
Single tool `run_python({code})`. The whole engine (AST per-form eval, verbs,
session dict, async, summarize) is unchanged — the model's code just moves from a
```python fence to `input.code`. Loop:
1. `ask-tools!` with `[run_python]`.
2. `:tool-calls` → run each code via existing `run-python-block` → append
   `assistant-message` + `tool_result` messages → repeat.
3. `:end` → `:content` is the final answer; turn ends. (No tool call = done.)

## Build order
1. anthropic wire end-to-end (cleanest; round-trip mostly free) + `ask-tools!`.
   **Covers BOTH primary providers: Claude (copilot) AND GLM (z.ai anthropic
   endpoint).** Spike validates the two targets that matter most.
2. responses wire (codex/gpt).
3. chat wire (Gemini + any other openai-compat).
4. streaming tool-call deltas for each.
5. remove ask-code!/fence.
6. wire vis (single run_python, rip done/fence/gate).
