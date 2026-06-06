<h2 align="center">
  <img width="40%" alt="SVAR logo" src="logo.png"><br/>
</h2>

<div align="center">
<i>svar</i> — "answer" in Swedish. Type-safe LLM output for Clojure, inspired by <a href="https://github.com/BoundaryML/baml">BAML</a>.
<br/>
<sub>Works with any text-producing LLM — no structured output support required.</sub>
</div>

<div align="center">
  <h2>
    <a href="https://clojars.org/com.blockether/svar"><img src="https://img.shields.io/clojars/v/com.blockether/svar?color=%23007ec6&label=clojars" alt="Clojars version"></a>
    <a href="https://github.com/Blockether/svar/blob/main/LICENSE">
      <img src="https://img.shields.io/badge/license-Apache%202.0-green" alt="License - Apache 2.0">
    </a>
  </h2>
</div>

<div align="center">
<h3>

[Rationale](#rationale) • [Functionalities](#functionalities) • [Quick Start](#quick-start) • [Router](#router) • [Usage](#usage) • [Spec DSL](#spec-dsl-reference)

</h3>
</div>

## Rationale

JSON Schema is the de facto way to get structured output from LLMs — but it's incomplete. Union types have poor cross-provider support, and the entire approach requires your LLM provider to support structured output mode. That rules out local models, smaller providers, and any setup where you just have a text completion endpoint.

SVAR takes a different approach: let the LLM produce plain text, then parse and correct the output post-step. This works with **any** text-producing LLM — OpenAI, Anthropic, local Ollama, vLLM, whatever you have. No provider lock-in, no feature flags, no "this model doesn't support JSON mode" surprises.

## Functionalities

| Category | Functions | Description |
|----------|-----------|-------------|
| [**Router**](#router) | `make-router`, `router-stats`, `reset-budget!`, `reset-provider!` | Multi-provider routing with circuit breakers, cost budgets, automatic fallback. The entry point to the library. |
| [**Structured Output**](#schemaless-adaptive-parsing-ask) | `ask!` | LLM → validated Clojure map via spec. Works with any text-producing LLM — SAP parser handles malformed JSON, unquoted keys, trailing commas, markdown blocks. Supports [streaming](#streaming) via `:on-chunk`. Token counting + cost estimation via JTokkit. |
| [**Code Output**](#code-output-ask-code) | `ask-code!`, `extract-code-blocks` | Plain-text completion + fenced code-block extraction. Filters by `:lang`, keeps untagged fences, concatenates matching blocks, exposes raw text + parsed blocks. Supports [streaming](#streaming) via `:on-chunk`. |
| [**Spec DSL**](#spec-dsl-reference) | `spec`, `field`, `spec->prompt`, `validate-data` | Define output shapes: types, enums, refs, optional fields, namespaced keys, fixed-size vectors. |
| [**Parsing**](#parsing--validation) | `str->data`, `str->data-with-spec`, `data->str` | Schemaless and spec-validated JSON↔Clojure. Handles malformed JSON out of the box. |
| [**Models**](#available-models-models) | `models!` | List available models from your provider. |

## Quick Start

```clojure lazytest/skip=true
;; deps.edn
{:deps {com.blockether/svar {:mvn/version "0.7.4"}}}
```

```clojure
(require '[com.blockether.svar.core :as svar])

;; Create a router — the single entry point for all LLM calls.
;; Every function takes the router as its first argument.
(comment
  (def router (svar/make-router [{:id :openai
                                  :api-key (System/getenv "OPENAI_API_KEY")
                                  :models [{:name "gpt-4o"}]}])))
```

## Router

The router is the single entry point for all LLM calls. Create it once at boot, pass it to every function. It handles provider selection, circuit breaking, cost budgets, and automatic fallback.

### Basic Setup

```clojure
(comment
  ;; Single provider
  (def router
    (svar/make-router [{:id :openai
                        :api-key (System/getenv "OPENAI_API_KEY")
                        :models [{:name "gpt-4o"}
                                 {:name "gpt-4o-mini"}]}])))
```

### Multi-Provider with Fallback

Vector order = priority. If the first provider fails (rate limit, outage), the router automatically falls back to the next:

```clojure
(comment
  ;; First provider is preferred; second is fallback
  (def router
    (svar/make-router
      [{:id :anthropic
        :api-key (System/getenv "ANTHROPIC_API_KEY")
        :models [{:name "claude-sonnet-4-20250514"}]}
       {:id :openai
        :api-key (System/getenv "OPENAI_API_KEY")
        :models [{:name "gpt-4o"}]}])))
```

### Cost Budgets & Circuit Breakers

```clojure
(comment
  ;; Spend limits + circuit breaker tuning
  (def router
    (svar/make-router
      [{:id :openai
        :api-key (System/getenv "OPENAI_API_KEY")
        :models [{:name "gpt-4o"} {:name "gpt-4o-mini"}]}]
      {:budget {:max-tokens 1000000 :max-cost 5.0}  ;; hard spend cap
       :failure-threshold 5                          ;; failures before circuit opens
       :recovery-ms 60000})))                        ;; ms before retry after open
```

### Routing Options

Every `ask!` call accepts `:routing` to control provider/model selection:

```clojure
(comment
  ;; Let the router pick the cheapest model
  (svar/ask! router {:spec my-spec
                     :messages [(svar/user "...")]
                     :routing {:optimize :cost}})

  ;; Or the most capable
  (svar/ask! router {:spec my-spec
                     :messages [(svar/user "...")]
                     :routing {:optimize :intelligence}})

  ;; Pin to a specific provider + model
  (svar/ask! router {:spec my-spec
                     :messages [(svar/user "...")]
                     :routing {:provider :openai :model "gpt-4o-mini"}}))
```

### Observability

```clojure
(comment
  ;; Cumulative + windowed stats per provider:
  ;;   :total      - {:requests N :tokens N}
  ;;   :providers  - per-provider circuit-breaker state, windowed + cumulative stats
  ;;   :budget     - {:limit ... :spent {:total-tokens N :total-cost N}}
  (svar/router-stats router)

  ;; Reset spend counters (e.g. start of billing cycle)
  (svar/reset-budget! router)

  ;; Manually close a circuit breaker after provider recovers
  (svar/reset-provider! router :openai))
```

## Usage

### API styles

svar now uses explicit transport names for OpenAI-compatible providers:

- `:openai-compatible-chat` → `/chat/completions`
- `:openai-compatible-responses` → `/responses`
- `:anthropic` → `/messages`

Known provider profiles choose the right transport for you. For custom providers, set `:api-style` explicitly:

```clojure
(comment
  (def router
    (svar/make-router
      [{:id :my-openai-gateway
        :api-key (System/getenv "MY_GATEWAY_API_KEY")
        :base-url "https://gateway.example.com/v1"
        :api-style :openai-compatible-chat
        :models [{:name "gpt-4o"}]}
       {:id :my-responses-gateway
        :api-key (System/getenv "MY_RESPONSES_API_KEY")
        :base-url "https://gateway.example.com/v1"
        :api-style :openai-compatible-responses
        :models [{:name "gpt-5.5"}]}])))
```

### Message Helpers

Build message vectors for LLM interactions with `system`, `user`, `assistant`, and `image`:

```clojure
(svar/system "You are a helpful assistant.")
;; => {:role "system", :content "You are a helpful assistant."}

(svar/user "What is 2+2?")
;; => {:role "user", :content "What is 2+2?"}

(svar/assistant "The answer is 4.")
;; => {:role "assistant", :content "The answer is 4."}

(svar/image "iVBORw0KGgo=")
;; => {:svar/type :image, :base64 "iVBORw0KGgo=", :media-type "image/png"}

(svar/image "iVBORw0KGgo=" "image/jpeg")
;; => {:svar/type :image, :base64 "iVBORw0KGgo=", :media-type "image/jpeg"}

;; URLs are also supported — passed through directly to the LLM API
(svar/image "https://example.com/photo.jpg")
;; => {:svar/type :image, :url "https://example.com/photo.jpg"}

;; Multimodal: user message with base64 image attachment
(svar/user "Describe this" (svar/image "iVBORw0KGgo=" "image/jpeg"))
;; => {:role "user", :content [{:type "image_url", :image_url {:url "data:image/jpeg;base64,iVBORw0KGgo="}} {:type "text", :text "Describe this"}]}

;; Multimodal: user message with URL image — no data URI wrapping
(svar/user "What's in this image?" (svar/image "https://example.com/photo.jpg"))
;; => {:role "user", :content [{:type "image_url", :image_url {:url "https://example.com/photo.jpg"}} {:type "text", :text "What's in this image?"}]}
```

### Schemaless Adaptive Parsing (`ask!`)

SVAR doesn't require your LLM to support structured output mode. Instead, `ask!` sends a spec-generated prompt that instructs the LLM to respond in JSON, then parses the response with SAP (Schemaless Adaptive Parsing) — a Java-based parser that handles malformed JSON, unquoted keys, trailing commas, markdown code blocks, and more. This means `ask!` works with **any** text-producing LLM.

```clojure
(def person-spec
  (svar/spec
    (svar/field svar/NAME :name
                svar/TYPE svar/TYPE_STRING
                svar/CARDINALITY svar/CARDINALITY_ONE
                svar/DESCRIPTION "Full name")
    (svar/field svar/NAME :age
                svar/TYPE svar/TYPE_INT
                svar/CARDINALITY svar/CARDINALITY_ONE
                svar/DESCRIPTION "Age in years")))

(comment
  (def ask-result
    (svar/ask! router {:spec person-spec
                       :messages [(svar/system "Extract person information from the text.")
                                  (svar/user "John Smith is a 42-year-old engineer.")]
                       :model "gpt-4o"}))

  (:result ask-result)
  ;; => {:name "John Smith", :age 42}
  )
```

Under the hood, `spec->prompt` translates the spec into a schema the LLM can follow:

```clojure lazytest/skip=true
(println (svar/spec->prompt person-spec))
;; Answer in JSON using this schema:
;; {
;;   // Full name (required)
;;   name: string,
;;   // Age in years (required)
;;   age: int,
;; }
```

Returns `{:result <data> :tokens {:input N :output N :total N} :cost {:input-cost N :output-cost N :total-cost N} :duration-ms N}`.

### Code Output (`ask-code!`)

Use `ask-code!` when you want source text, not JSON. svar asks for plain text, extracts fenced code blocks, filters by `:lang`, keeps untagged fences as matches for any language, and concatenates the selected blocks into `:result`.

```clojure
(svar/extract-code-blocks "Before\n```clojure\n(+ 1 1)\n```\nAfter")
;; => [{:lang "clojure", :source "(+ 1 1)"}]

(svar/extract-code-blocks "(println \"no fence\")")
;; => [{:lang nil, :source "(println \"no fence\")"}]
```

```clojure
(comment
  (def code-result
    (svar/ask-code! router
      {:messages [(svar/system "Reply with Clojure code only.")
                  (svar/user "Write a function `square`.")]
       :model "gpt-4o"
       :lang "clojure"}))

  (:result code-result)
  ;; => "(defn square [x] (* x x))"

  (:blocks code-result)
  ;; => [{:lang "clojure", :source "(defn square [x] (* x x))"}]
  )
```

Returns `{:result <source> :blocks [{:lang <str-or-nil> :source <str>} ...] :raw <full-assistant-text> :reasoning <provider-reasoning-when-present> :tokens {:input N :output N :reasoning N :total N} :cost {:input-cost N :output-cost N :total-cost N} :duration-ms N}`.

`ask-code!` accepts the same routing, reasoning, verbosity, network, and streaming controls as `ask!`, minus the structured-output-only knobs (`:spec`, `:format-retries`, `:format-retry-on`, `:json-object-mode?`).

### Reasoning depth and output verbosity

Two knobs, different jobs:

- `:reasoning` = how hard the model thinks before answering. Use `:quick`, `:balanced`, or `:deep`.
- `:verbosity` = how verbose the visible answer should be. Use `:low`, `:medium`, or `:high`.

They are independent. Example: `:reasoning :deep` + `:verbosity :low` means think hard, answer briefly.

```clojure
(comment
  (svar/ask! router
    {:spec person-spec
     :messages [(svar/system "Extract person info.")
                (svar/user "John Smith is a 42-year-old engineer.")]
     :model "gpt-5.5"
     :reasoning :deep
     :verbosity :low}))

(comment
  (svar/ask-code! router
    {:messages [(svar/user "Write a compact Clojure fn that squares a number.")]
     :model "gpt-5.5"
     :lang "clojure"
     :reasoning :balanced
     :verbosity :low}))
```

`:reasoning` is provider-agnostic — svar translates it to the right wire shape for the selected model. `:verbosity` is honored on providers that expose a visible-output verbosity control (notably OpenAI Responses-style endpoints such as `:openai-codex`) and ignored elsewhere.

### Provider-noise hardening (`:format-retries`, `:json-object-mode?`, `:on-format-error`)

Some providers — notably the GLM family (`glm-5.1`, `glm-4.7`, ...) under
`:reasoning :deep` — occasionally emit a bare prose string in `content`
instead of the schema-conformant JSON object svar's spec asks for. The
response looks like `"Looking at the request, I think..."` with the actual
thinking dumped into `content` past the reasoning channel. svar rejects
loudly with `:svar.spec/schema-rejected`, but if every rejection bubbles
up to your agent loop you pay for provider noise out of your iteration
budget and lose post-mortem signal.

Three opt-in tools absorb the noise inside a single `ask!` call:

```clojure
(comment
  (svar/ask! router
    {:spec my-spec
     :messages [(svar/user "...")]
     :model "glm-5.1"
     :reasoning :deep
     :format-retries 2                        ;; retry locally on schema-rejected
     :json-object-mode? true                  ;; auto-on for GLM — explicit override
     :on-format-error :fallback-provider}))   ;; if model is broken, try next
```

**`:format-retries N`** — when the provider returns content that fails
schema parsing, svar appends a tiny FORMAT-RETRY turn (the model's bad
response + a short corrective instruction) and re-calls the provider up
to N times. Tokens for the bad attempts are still billed (the provider
produced them) but the caller sees one logical `ask!` call. Each attempt
is recorded in `:format-attempts` on success or in the terminal
exception's ex-data. Streaming (`:on-chunk`) forces retries to 0.

**`:json-object-mode?`** — on `:openai-compatible-chat` api-style providers, injects
`response_format: {type: "json_object"}` into the request body. GLM
models (`glm-5.1`, `glm-4.7`, `glm-5-turbo`, `glm-4.6`, `glm-4.6v`) are
opted in by default across `:zai` and `:zai-coding`
providers. Caller's `:extra-body :response_format` always wins; pass
`:json-object-mode? false` to opt out a flagged model.

**`:on-format-error :fallback-provider`** — if the chosen model fails
format parsing, treat it as a transient error and try the next provider
in the fleet, excluding the offender. When all providers fail, svar
throws the LAST format error's full envelope with `:routed/trace`
and `:format-failed` merged into ex-data. Default is `:fail`.

#### Forensic envelope on every error

Any exception thrown from `ask!` carries the full call context in
`ex-data` — no truncation:

```clojure
(comment
  (try (svar/ask! router opts)
    (catch clojure.lang.ExceptionInfo e
      (let [d (ex-data e)]
        (:type d)            ;; :svar.spec/schema-rejected, :svar.llm/empty-content, ...
        (:model d)           ;; "glm-5.1"
        (:api-style d)       ;; :openai-compatible-chat
        (:chat-url d)        ;; "https://llm.blockether.com/v1/chat/completions"
        (:duration-ms d)     ;; 14696.749
        (:api-usage d)       ;; provider tokens
        (:reasoning d)       ;; full reasoning_content (or nil)
        (:content d)         ;; FULL untruncated content of the last attempt
        (:http-response d)   ;; {:parsed :raw-body :url :status}
        (:format-attempts d) ;; vec of every attempt with full content/reasoning
        ))))
```

Use this to persist the failing call into your DB / display it in a TUI /
reproduce it without re-invoking the LLM.

### Parsing & Validation

SVAR works with any text-producing LLM because parsing happens post-step. The SAP parser handles malformed JSON out of the box:

```clojure
;; Spec-validated parsing — handles clean and malformed JSON
(svar/str->data-with-spec "{\"name\": \"John Smith\", \"age\": 42}" person-spec)
;; => {:name "John Smith", :age 42}

(svar/str->data-with-spec "{name: \"John Smith\", age: 42,}" person-spec)
;; => {:name "John Smith", :age 42}

;; Schemaless parsing — no spec needed
(svar/str->data "{\"city\": \"Paris\", \"population\": 2161000}")
;; => {:value {:city "Paris", :population 2161000}, :warnings []}

;; Serialize Clojure data to JSON
(svar/data->str {:name "John" :age 42})
;; => "{\"name\":\"John\",\"age\":42}"

;; Validate parsed data against a spec
(svar/validate-data person-spec {:name "John Smith" :age 42})
;; => {:valid? true}

(svar/validate-data person-spec {:name "John Smith"})
;; => {:valid? false, :errors [{:error :missing-required-field, :field :age, :path "age"}]}
```

### Available Models (`models!`)

Lists all models available from your LLM provider.

```clojure
(comment
  (def models (svar/models! router))

  ;; Every model has an :id field
  (every? :id models)
  ;; => true
  )
```

### Spec DSL Reference

The spec DSL defines the shape of LLM output. Every field has a name, type, cardinality, and description.

#### All Types

| Constant | Type |
|----------|------|
| `TYPE_STRING` | String |
| `TYPE_INT` | Integer |
| `TYPE_FLOAT` | Float |
| `TYPE_BOOL` | Boolean |
| `TYPE_DATE` | ISO date (YYYY-MM-DD) |
| `TYPE_DATETIME` | ISO datetime |
| `TYPE_KEYWORD` | Clojure keyword (rendered as string, keywordized on parse) |
| `TYPE_REF` | Reference to another spec |
| `TYPE_INT_V_1` … `TYPE_INT_V_12` | Fixed-size integer vectors (1–12 elements) |
| `TYPE_STRING_V_1` … `TYPE_STRING_V_12` | Fixed-size string vectors |
| `TYPE_DOUBLE_V_1` … `TYPE_DOUBLE_V_12` | Fixed-size double vectors |

#### Keyword Type (`TYPE_KEYWORD`)

String values automatically become Clojure keywords on parse — useful for status codes, categories, and enum-like fields that you want as keywords in your code:

```clojure
(def status-spec
  (svar/spec
    (svar/field svar/NAME :status
                svar/TYPE svar/TYPE_KEYWORD
                svar/CARDINALITY svar/CARDINALITY_ONE
                svar/DESCRIPTION "Current status")))

;; String "active" in JSON becomes keyword :active in Clojure
(svar/str->data-with-spec "{\"status\": \"active\"}" status-spec)
;; => {:status :active}
```

#### Enums (`VALUES`)

When a field should only contain one of a fixed set of values — status codes, categories, severity levels — use `VALUES` with a map of `{value description}`. The descriptions are included in the LLM prompt so it understands what each value means, which dramatically improves output accuracy:

```clojure
(def sentiment-spec
  (svar/spec
    (svar/field svar/NAME :sentiment
                svar/TYPE svar/TYPE_STRING
                svar/CARDINALITY svar/CARDINALITY_ONE
                svar/DESCRIPTION "Sentiment classification"
                svar/VALUES {"positive" "Favorable or optimistic tone"
                             "negative" "Unfavorable or critical tone"
                             "neutral" "Balanced or factual tone"})
    (svar/field svar/NAME :confidence
                svar/TYPE svar/TYPE_FLOAT
                svar/CARDINALITY svar/CARDINALITY_ONE
                svar/DESCRIPTION "Confidence score from 0.0 to 1.0")))

;; Valid enum value passes
(svar/validate-data sentiment-spec {:sentiment "positive" :confidence 0.95})
;; => {:valid? true}

;; Invalid enum value caught
(:valid? (svar/validate-data sentiment-spec {:sentiment "happy" :confidence 0.8}))
;; => false
```

#### Optional Fields (`REQUIRED`)

Fields are required by default — the LLM must provide a value. Set `REQUIRED false` when a field might legitimately be absent (e.g., a phone number the source text doesn't mention). Optional fields parse as `nil` when missing, and validation passes without them:

```clojure
(def contact-spec
  (svar/spec
    (svar/field svar/NAME :name
                svar/TYPE svar/TYPE_STRING
                svar/CARDINALITY svar/CARDINALITY_ONE
                svar/DESCRIPTION "Full name")
    (svar/field svar/NAME :phone
                svar/TYPE svar/TYPE_STRING
                svar/CARDINALITY svar/CARDINALITY_ONE
                svar/REQUIRED false
                svar/DESCRIPTION "Phone number if available")))

;; Validation passes without optional fields
(svar/validate-data contact-spec {:name "Jane Doe"})
;; => {:valid? true}

;; But fails without required fields
(svar/validate-data contact-spec {:phone "555-1234"})
;; => {:valid? false, :errors [{:error :missing-required-field, :field :name, :path "name"}]}
```

#### Collections (`CARDINALITY_MANY`)

When a field holds multiple values — tags, authors, line items — use `CARDINALITY_MANY`. The LLM returns a JSON array, parsed as a Clojure vector:

```clojure
(def article-spec
  (svar/spec
    (svar/field svar/NAME :title
                svar/TYPE svar/TYPE_STRING
                svar/CARDINALITY svar/CARDINALITY_ONE
                svar/DESCRIPTION "Article title")
    (svar/field svar/NAME :tags
                svar/TYPE svar/TYPE_STRING
                svar/CARDINALITY svar/CARDINALITY_MANY
                svar/DESCRIPTION "List of tags")))

;; Arrays are parsed as Clojure vectors
(svar/str->data-with-spec "{\"title\": \"SVAR Guide\", \"tags\": [\"clojure\", \"llm\", \"parsing\"]}" article-spec)
;; => {:title "SVAR Guide", :tags ["clojure" "llm" "parsing"]}

(svar/validate-data article-spec {:title "SVAR Guide" :tags ["clojure" "llm"]})
;; => {:valid? true}
```

#### Nested Specs (`TYPE_REF` / `TARGET`)

When your LLM output has nested objects — a company with an address, an order with line items — you define each sub-object as its own named spec, then reference it with `TYPE_REF` + `TARGET`. This keeps specs composable and reusable: define `Address` once, reference it from `Company`, `Person`, `Order`, etc.

Pass referenced specs via `{:refs [address-spec]}` so the prompt generator and parser know how to handle them. Combine with `CARDINALITY_MANY` for arrays of nested objects (e.g., branch offices).

```clojure
(def address-spec
  (svar/spec :Address
    (svar/field svar/NAME :street
                svar/TYPE svar/TYPE_STRING
                svar/CARDINALITY svar/CARDINALITY_ONE
                svar/DESCRIPTION "Street address")
    (svar/field svar/NAME :city
                svar/TYPE svar/TYPE_STRING
                svar/CARDINALITY svar/CARDINALITY_ONE
                svar/DESCRIPTION "City name")))

(def company-spec
  (svar/spec
    {:refs [address-spec]}
    (svar/field svar/NAME :name
                svar/TYPE svar/TYPE_STRING
                svar/CARDINALITY svar/CARDINALITY_ONE
                svar/DESCRIPTION "Company name")
    (svar/field svar/NAME :headquarters
                svar/TYPE svar/TYPE_REF
                svar/CARDINALITY svar/CARDINALITY_ONE
                svar/TARGET :Address
                svar/DESCRIPTION "HQ address")
    (svar/field svar/NAME :branches
                svar/TYPE svar/TYPE_REF
                svar/CARDINALITY svar/CARDINALITY_MANY
                svar/TARGET :Address
                svar/DESCRIPTION "Branch office addresses")))

;; Parse nested JSON — refs become nested maps/vectors automatically
(svar/str->data-with-spec
  "{\"name\": \"Acme Corp\", \"headquarters\": {\"street\": \"123 Main St\", \"city\": \"SF\"}, \"branches\": [{\"street\": \"456 Oak Ave\", \"city\": \"LA\"}]}"
  company-spec)
;; => {:name "Acme Corp", :headquarters {:street "123 Main St", :city "SF"}, :branches [{:street "456 Oak Ave", :city "LA"}]}

;; Ref registry maps spec names to their definitions
(vec (keys (svar/build-ref-registry company-spec)))
;; => [:Address]
```

#### Namespaced Keys (`KEY-NS`)

When LLM output maps directly to Datomic/Datalevin entities, you want namespaced keys (`:page.node/type` instead of `:type`). `KEY-NS` adds a namespace prefix to all keys during parsing, so you can transact LLM results straight into your database without manual key transformation.

This is especially useful when multiple specs share field names like `:type` or `:id` — namespacing disambiguates them.

```clojure
(def node-spec
  (svar/spec :node
    {svar/KEY-NS "page.node"}
    (svar/field svar/NAME :type
                svar/TYPE svar/TYPE_STRING
                svar/CARDINALITY svar/CARDINALITY_ONE
                svar/DESCRIPTION "Node type")
    (svar/field svar/NAME :content
                svar/TYPE svar/TYPE_STRING
                svar/CARDINALITY svar/CARDINALITY_ONE
                svar/DESCRIPTION "Text content")))

;; Keys are automatically namespaced — ready for d/transact!
(svar/str->data-with-spec "{\"type\": \"heading\", \"content\": \"Introduction\"}" node-spec)
;; => {:page.node/type "heading", :page.node/content "Introduction"}
```

### Streaming

Pass `:on-chunk` to `ask!` to enable SSE streaming. The callback fires with partially-parsed results as they arrive, then a final call with `:done? true` including token counts and cost:

```clojure
(comment
  ;; Stream structured output — callback receives progressive partial results
  (svar/ask! router
    {:spec person-spec
     :messages [(svar/system "Extract person info.")
                (svar/user "John Smith is a 42-year-old engineer.")]
     :model "gpt-4o"
     :on-chunk (fn [{:keys [result reasoning tokens cost done?]}]
                (if done?
                  (println "Final:" result "cost:" (:total-cost cost))
                  (println "Partial:" result)))}))
```

Callback shape:

| Key | While streaming | Final (`:done? true`) |
|-----|----------------|----------------------|
| `:result` | Best-effort partial parse | Fully validated + coerced |
| `:reasoning` | Accumulated reasoning text | Full reasoning |
| `:tokens` | `nil` | `{:input N :output N :reasoning N :total N}` |
| `:cost` | `nil` | `{:input-cost N :output-cost N :total-cost N}` |
| `:done?` | `false` | `true` |

Streaming works with all routing options — `:optimize`, provider pinning, fallback:

```clojure
(comment
  (svar/ask! router
    {:spec person-spec
     :messages [(svar/user "...")]
     :routing {:optimize :cost}
     :on-chunk (fn [{:keys [result done?]}]
                (when done? (println result)))}))
```

### `spec->prompt`

`ask!` auto-generates the LLM prompt from your spec. You can inspect what gets sent:

```clojure lazytest/skip=true
(println (svar/spec->prompt company-spec))
;; Answer in JSON using this schema:
;; Address {
;;   // Street address (required)
;;   street: string,
;;   // City name (required)
;;   city: string,
;; }
;;
;; {
;;   // Company name (required)
;;   name: string,
;;   // HQ address (required)
;;   headquarters: Address,
;;   // Branch office addresses (required)
;;   branches: Address[],
;; }
```

## Further reading

- [`CHANGELOG.md`](CHANGELOG.md) — version history

## License

Apache License 2.0 — see [LICENSE](LICENSE).
