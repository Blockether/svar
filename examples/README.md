# GLM-4.6v EOF Reproduction

**The `examples/reproduce_eof.clj` script demonstrates the Blockether proxy EOF issue.**

## What it does:
- Extracts page 13 from `resources-test/chapter.pdf` using `glm-4.6v`
- Runs 5 attempts in a loop to demonstrate the connection drop pattern

## How to run:
```bash
clj -M:dev -cp src/clj:examples -M examples/reproduce_eof.clj -e "(reproduce-eof/-main \"resources-test/chapter.pdf\")"
```

## Expected output:
```
=== GLM-4.6v EOF Reproduction ===
PDF: resources-test/chapter.pdf
Model: glm-4.6v
Max attempts: 5

Attempt 1/5...
Result: {...map...}

Attempt 2/5...
Retrying HTTP request {:attempt 2, :reason :connection-error, :error "EOF reached while reading", :delay-ms 2000}
...
```

## Key observations:
1. Most attempts fail with "EOF reached while reading" - this is the Blockether proxy dropping connections mid-response
2. Successful retries confirm our `with-retry` fix works for transient connection errors
3. Pages 13 and 14 consistently fail all 5 retry attempts (exhaust max) - the GLM backend likely times out before completing large JSON response
4. `glm-4.6v` through Blockether proxy is unreliable for vision extraction

## Workarounds:
- Use `:vision-model "gpt-4o"` in production - GPT-4o is stable through the same proxy
- Or use a direct API endpoint that doesn't go through the Blockether proxy (e.g., direct OpenAI API)
- Or increase the proxy timeout (currently 480s/8 min for `glm-4.6v`, but GLM backend may still time out)
