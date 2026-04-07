# Known 4clojure Problem Patterns

## Bare list literals (#17 map, #45 Iterate)

**Pattern**: Answer is a data literal like `(6 7 8)` or `(1 4 7 10 13)`.
Model self-tests with `'(6 7 8)` (quoted) in SCI but final answer JSON strips the quote.
bb verification: `(= (6 7 8) ...)` → Long cannot be cast to IFn.

**Status**: NOT CAUGHT by edamame (valid syntax). Need runtime validation.
**Fix**: Detect bare list starting with number/keyword in final answer → reject with hint "Quote list literals: use '(6 7 8) not (6 7 8)".

## Inline substitution paren breakage (#103 k-combinations)

**Pattern**: Complex multi-paren answer like `(fn comb [k s] ...)` with deeply nested `)))`.
When substituted inline into bb test form `(= (__ ...) expected)`, the nested parens
from the answer misalign with the bb script wrapper parens.

**Status**: Reverted def approach (broke multi-form answers like `:a :b :c`).
**Fix**: Hybrid — use inline for simple answers, def for complex fn expressions.

## Missing `is` symbol (#119 Win at Tic-Tac-Toe)

**Pattern**: 4clojure test uses `(is (= ...))` from `clojure.test`.
bb doesn't have `clojure.test/is` by default.

**Status**: Added `(def is identity)` to bb script. May not work if `is` is used in nested position.
**Fix**: Verify the shim works for all `is` usage patterns.

## bb timeout (#150 Palindromic Numbers)

**Pattern**: Model's solution is algorithmically correct but too slow for bb's 30s timeout.
The solution generates palindromes lazily but test checks `(take 26 ...)` starting from 0 
which requires computing many palindromes.

**Status**: bb-timeout-ms is 30s.
**Fix**: Increase bb-timeout-ms to 60s, or model needs a faster algorithm.

## Provider timeouts (#87, #111, #127, #173)

**Pattern**: LLM provider times out (300s) on hard problems requiring long reasoning.
Circuit breaker was cascading failures — FIXED with reset between tasks.

**Status**: Fixed cascading. Individual timeouts still occur (~3-4 per full run).
**Fix**: Add fallback providers, or increase provider timeout for known hard problems.
