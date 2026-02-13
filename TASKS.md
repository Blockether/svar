# TASKS -- humanize.clj Overhaul

---

## GLM-5 ANALYSIS

> **Verified against runtime**: All claims below tested via `clojure -M -e` against actual code.

### Executive Summary

The task plan is **well-structured and fundamentally sound**. Key findings from parallel research of industry implementations, academic literature, and verified code execution:

**Strengths**:
- Correct identification of single-word false positives as the core problem
- Category-based tier split (T4) is more robust than word-count heuristics
- Spec-driven humanization (T7) is architecturally sound — the field DSL cleanly supports new optional booleans via the existing `cond->` pattern

**Verified bugs (confirmed by running code)**:
- `"I live in Foster City"` → `"I live in encourage City"` ✗
- `"The React framework is popular"` → `"The React structure is popular"` ✗
- `"The Tuscan landscape is beautiful"` → `"The Tuscan field is beautiful"` ✗
- `"The journey from Paris took 3 hours"` → `"The process from Paris took 3 hours"` ✗
- `"The leverage ratio is 3:1"` → `"The use ratio is 3:1"` ✗
- `"elevated permissions are needed"` → `"improved permissions are needed"` ✗
- `"delve into X, delve into Y, delve into Z"` → `"explore X, explore into Y, delve into Z"` ✗ (only first two partially handled, third skipped entirely)

**CORRECTION — `"navigate"` does NOT match inside `"navigation"`**:
- `(.contains "navigation" "navigate")` → `false`
- `"The navigation menu works"` → unchanged ✓
- T3's rationale wrongly uses this as a motivating example. The real problem is **standalone word false positives** (foster, framework, landscape, etc.), not substring-inside-word matching. T3 is still needed for correctness, but the TASKS.md rationale for T3 should be corrected.

---

### Code Audit Findings

#### Current `replace-phrase` Algorithm (lines 457-479)

```clojure
(defn- replace-phrase [text pattern replacement]
  (let [pattern-lower (str/lower-case pattern)
        text-lower (str/lower-case text)]
    (if (str/includes? text-lower pattern-lower)
      (let [idx (.indexOf text-lower pattern-lower)]
        (str (subs text 0 idx)
             replacement
             (subs text (+ idx (count pattern)))))
      text)))
```

**Confirmed bugs**:
1. **Single occurrence only** — `.indexOf` returns first match; subsequent occurrences ignored. Verified: `"delve into X, delve into Y, delve into Z"` → only first `"delve into"` replaced by the `"delve into"` pattern; the second is caught by shorter `"delve"` pattern (producing `"explore into Y"`); the third `"delve into"` is missed entirely.
2. **No case preservation** — replacement inserted verbatim regardless of original case
3. **No exclusion zones** — patterns applied inside code blocks, URLs, inline code

**CORRECTED**: `"navigate"` does NOT match inside `"navigation"` — the string `"navigate"` is not a substring of `"navigation"`. The real boundary problem is that single-word patterns like `"foster"` match `"Foster"` as a standalone word (case-insensitive), corrupting proper nouns. Word boundary issues would appear with patterns like `"empower"` matching inside `"empowerment"` (verified: `(.contains "empowerment" "empower")` → true).

#### Pattern Inventory (actual runtime counts)

| Category | Documented | Actual | Delta |
|----------|------------|--------|-------|
| AI_IDENTITY_PATTERNS | 30 | 31 | +1 |
| REFUSAL_PATTERNS | 20 | 20 | 0 |
| HEDGING_PATTERNS | 34 | 34 | 0 |
| KNOWLEDGE_PATTERNS | 18 | 18 | 0 |
| PUNCTUATION_PATTERNS | 6 | 6 | 0 |
| OVERUSED_VERBS_PATTERNS | 72 | 72 | 0 |
| OVERUSED_ADJECTIVES_PATTERNS | 33 | 33 | 0 |
| OVERUSED_NOUNS_AND_TRANSITIONS_PATTERNS | 29 | 29 | 0 |
| OPENING_CLICHE_PATTERNS | 33 | 32 | -1 |
| CLOSING_CLICHE_PATTERNS | 28 | 29 | +1 |
| **Sum of individual** | ~303 | **304** | +1 |
| **DEFAULT_PATTERNS (merged)** | ~300 | **304** | +4 |

Note: Sum of individuals (304) equals merged DEFAULT_PATTERNS (304) — no key collisions across categories.

**Identity patterns (key == value, zero-effect)** — 2 confirmed:
- `"I'm not designed to"` → `"I'm not designed to"`
- `"I'm not programmed to"` → `"I'm not programmed to"`

**Substring ordering conflicts** (handled by longest-first sort, but real substring matches):
- `tapestry` ⊂ `tapestry of`
- `landscape` ⊂ `landscape of`
- `realm` ⊂ `realm of`
- `paradigm` ⊂ `paradigm shift`
- `empower` ⊂ `empowerment` (verb conjugation creates actual boundary issue)
- `delve` ⊂ `delve into` (longest-first sort ensures `delve into` matched first)

---

### Industry Best Practices Research

#### 1. Word Boundary Detection

**T3 approach is correct**: Conditional boundary checking (not naive `\b`) handles:
- Patterns ending with spaces (`"arguably "`)
- Patterns with internal punctuation (`"In conclusion, "`)
- Apostrophe patterns (`"today's"`)

**Recommendation**: Consider `(?U)` Unicode flag for international text, though all current patterns are ASCII English.

#### 2. Exclusion Zones (T5)

**Industry standard**: Placeholder-replace-restore pattern (extract zones → replace with unique tokens → humanize → restore).

**T5 currently covers**: fenced code blocks, inline code, URLs.

**Worth considering for acceptance criteria**: Email addresses (`user@example.com`). HTML entities and LaTeX are unlikely in LLM structured output via `ask!` — skip unless proven needed.

#### 3. Tier Calibration (T4)

**Industry finding**: Some implementations use probabilistic thresholds (10-60% application rates per tier).

**T4 is deterministic binary (on/off)** — this is simpler and appropriate for a rule-based replacement library. Probabilistic tiers make sense for NLP/embedding-based humanizers, not static pattern matching.

**Recommendation**: Keep binary. Don't over-engineer.

#### 4. Early Convergence Detection

**Industry pattern**: Stop processing if no significant change in N consecutive patterns.

**Assessment**: For 304 patterns applied via `reduce`, this is a micro-optimization. The entire `humanize-string` call is O(n*m) where n=text length and m=pattern count. With 304 patterns on short LLM outputs (<10KB), convergence detection adds complexity for negligible gain.

**Recommendation**: Skip. Not worth a task.

---

### Spec DSL Extension Analysis (T7)

**Current `field` function** uses variadic keyword args with defaults:
```clojure
[& {the-name ::name the-type ::type the-cardinality ::cardinality
    the-description ::description the-required ::required
    the-values ::values the-target ::target
    :or {the-required true}}]
```

**Return map** built with `cond->`:
- Always: `::name`, `::type`, `::cardinality`, `::description`
- Conditional: `::union` (when not required), `::values`, `::target`

**Adding `::humanize?`** follows the exact same pattern:
- Add `the-humanize? ::humanize?` to destructuring with `:or {the-humanize? false}`
- Add `(cond-> ... the-humanize? (assoc ::humanize? true))` to return map
- No validation needed beyond type check (boolean)

**Downstream impact** — verified by tracing consumers:
1. `spec->prompt` / `render-baml-field` / `field->baml-type` — NO CHANGE needed. `::humanize?` is post-processing metadata, invisible to prompt generation.
2. `str->data-with-spec` / `build-key-mapping` / `build-keyword-fields` — NO CHANGE. Parsing is unaffected.
3. `ask!` — NEW: after `result` binding, walk the result and apply humanizer fn to string values whose spec field has `::humanize? true`.

**Compatibility**: Fully backward compatible.

---

### ask! Pipeline Integration (T7)

**Current pipeline** (verified from core.clj):
```
input → build-system-prompt → spec->prompt → build-user-content
  → preflight context check → chat-completion → token counting
  → str->data-with-spec (parse+validate) → return {:result :tokens :cost :duration-ms}
```

**Insertion point**: After `result` is bound (post-parse), before constructing return map.

**Internal call sites** (all 3 pass `:humanize? false`, plus 3 more in rlm/vision.clj):
- core.clj:1168 (`decompose-output`) — `:humanize? false`
- core.clj:1401 (`verify-claims`) — `:humanize? false`
- core.clj:1805 (`cod-iteration-step`) — `:humanize? false`
- rlm/vision.clj:792, 949, 1135 — `:humanize? false`

All 6 sites need cleanup (remove deprecated `:humanize? false`).

---

### Corrections to TASKS.md

**T3 rationale** — Remove the claim that `"navigate"` matches inside `"navigation"`. It doesn't (`"navigate"` is not a substring of `"navigation"`). The real boundary problem is:
- `"empower"` matching inside `"empowerment"` (confirmed: `.contains` → true)
- `"foster"` matching standalone `"Foster"` as a proper noun (case-insensitive match)
- Single-occurrence replacement missing subsequent matches

**T7 internal call sites** — TASKS.md mentions 3 sites (lines 1168, 1401, 1805). There are actually **6 sites** — 3 more in `rlm/internal/pageindex/vision.clj` (lines 792, 949, 1135).

**Pattern counts** — Documentation says ~300, actual is 304. Minor but should be corrected.

---

### Final Assessment

| Task | Soundness | Completeness | Risk |
|------|-----------|--------------|------|
| T1 (test corpus) | ✅ Good | ✅ 10+ cases sufficient to start | Low |
| T2 (remove no-ops) | ✅ Good | ✅ Complete | None |
| T3 (boundary matching) | ✅ Good | ⚠️ Rationale needs correction (navigate/navigation claim is false) | Low |
| T4 (tier split) | ✅ Good | ✅ Binary tiers appropriate for static patterns | Low |
| T5 (exclusion zones) | ✅ Good | ⚠️ Add email addresses to exclusion list | Low |
| T6 (case preservation) | ✅ Good | ✅ Complete | Low |
| T7 (spec-driven humanizer) | ✅ Excellent | ⚠️ Missing 3 call sites in rlm/vision.clj | Medium |
| T8 (artifact cleanup) | ✅ Good | ✅ Complete | Low |

**Overall**: Plan is ready for implementation. Corrections needed: T3 rationale example, T7 missing call sites, pattern count documentation. No new tasks recommended — T9 (convergence detection) is premature optimization for 304 patterns on short text.

---

## Problem Statement

`humanize.clj` is a post-processing string replacement module for stripping AI-style language from LLM outputs. Its core mechanism -- substring matching against ~300 static patterns -- has critical false-positive issues that corrupt valid English, technical terminology, and proper nouns. The module is partially dead-wired in `ask!`.

**Design goal**: The module should be renamed in spirit from "humanize everything" to "strip obvious AI tells". Default mode should be safe for arbitrary text. Aggressive word-swapping is opt-in only.

---

## T1: Regression test corpus (must-change / must-not-change)

**Status**: Pending
**Priority**: Critical
**Effort**: Small

**What**: Before touching any logic, create a definitive test corpus that pins down correct behavior. Two categories:

1. **Must-change strings** -- unambiguously AI-generated phrasing:
   - `"As an AI, I think yes."` -> strips AI identity
   - `"It's important to note that X"` -> strips hedging
   - `"In today's digital age, we..."` -> strips opening cliche
   - `"In conclusion, this works."` -> strips closing cliche
   - `"Based on my training data"` -> strips knowledge disclaimer
   - `"I cannot and will not do that."` -> simplifies refusal

2. **Must-NOT-change strings** -- valid English the humanizer must not corrupt:
   - `"I live in Foster City"` (proper noun)
   - `"The navigation menu works"` (substring of pattern word)
   - `"The React framework is popular"` (technical term)
   - `"The Java ecosystem is large"` (technical term)
   - `"elevated permissions are needed"` (Linux term)
   - `"The leverage ratio is 3:1"` (finance term)
   - `"The Tuscan landscape is beautiful"` (geography)
   - `"The journey from Paris took 3 hours"` (literal journey)
   - `` "`optimize(query)`" `` (inline code)
   - `"See https://example.com/navigate/to"` (URL)

**Rationale**: Without this corpus, every subsequent task risks silent regression. Tests first, then refactor.

**Acceptance criteria**:
- Test file contains both categories with 10+ cases each
- Tests assert exact expected output for must-change
- Tests assert input == output for must-not-change
- Tests run and FAIL on the current implementation (proving the problems exist)
- After all subsequent tasks, all tests pass

---

## T2: Remove identity (no-op) patterns

**Status**: Pending
**Priority**: Low
**Effort**: Trivial

**What**: Remove patterns where key equals value (they do nothing):
```clojure
"I'm not designed to" "I'm not designed to"   ;; identity
"I'm not programmed to" "I'm not programmed to" ;; identity
```

**Rationale**: These burn CPU scanning text for zero effect. Delete them. The `"I am not designed to" -> "I'm not designed to"` variants are fine (contraction normalization).

**Acceptance criteria**:
- No pattern in any map has key == value
- Existing tests still pass

---

## T3: Boundary-aware matching in `replace-phrase`

**Status**: Pending
**Priority**: Critical
**Effort**: Medium

**What**: Replace the current `str/includes?` + `.indexOf` + `subs` approach in `replace-phrase` with boundary-aware matching. **Do NOT blindly wrap all patterns in `\b...\b` regex** -- this breaks patterns that end with spaces, commas, or punctuation.

**Approach**: Conditional boundary checking:
- If a pattern starts with a word-char (`\w`), require non-word-char (or start-of-string) before the match.
- If a pattern ends with a word-char, require non-word-char (or end-of-string) after the match.
- If a pattern starts/ends with whitespace or punctuation (e.g., `"arguably "`, `" -- "`, `"In conclusion, "`), match literally -- no boundary needed on that side.
- Case-insensitive.
- Replace ALL occurrences (not just first -- current behavior is a bug).

**Why not just `\b`**: Many patterns end with trailing space (`"arguably "`), comma-space (`"In conclusion, "`), or punctuation (`" --"`). `\b` is defined around `\w` boundaries and will fail to match these. Apostrophes in patterns like `"today's"` also interact poorly with `\b`.

**Why not just regex**: Replacement strings passed to `str/replace` with regex treat `$` and `\` as special characters. All replacement strings must be quoted with `java.util.regex.Matcher/quoteReplacement`.

**Rationale**: The current substring matching has two problems: (1) patterns like `"empower"` match inside `"empowerment"` (confirmed: `(.contains "empowerment" "empower")` → true), and (2) only the first occurrence is replaced per pattern. Boundary awareness prevents inside-word false positives. Note: `"navigate"` does NOT match inside `"navigation"` (not a substring) — the real false-positive problem for words like `"foster"` matching `"Foster City"` is case-insensitive matching of standalone words, which T4 handles by moving single-word patterns to aggressive tier.

**Acceptance criteria**:
- `"The navigation menu"` unchanged (already works — `navigate` is not a substring of `navigation`)
- `"empowerment of youth"` unchanged (`empower` not matched inside `empowerment`)
- `"fostering collaboration"` still replaced (word boundary present before `fostering`)
- `"arguably "` patterns still match (trailing space, no word boundary needed)
- `"In conclusion, this works."` still stripped (trailing comma-space)
- `" -- "` em-dash patterns still match (punctuation, no boundary)
- Multiple occurrences in one string all replaced
- Replacement strings with `$` or `\` chars don't break

---

## T4: Split patterns into safe and aggressive tiers by category

**Status**: Pending
**Priority**: Critical
**Effort**: Medium

**What**: Separate patterns into two tiers based on **risk category**, not word count:

**Safe tier** (default, always applied) -- patterns that are unambiguously AI artifacts:
- `AI_IDENTITY_PATTERNS` -- "As an AI, I...", "I'm a language model..."
- `REFUSAL_PATTERNS` -- "I cannot and will not...", "I'm unable to..."
- `KNOWLEDGE_PATTERNS` -- "my training data", "As of my last update..."
- `PUNCTUATION_PATTERNS` -- em-dash normalization

**Aggressive tier** (opt-in via `:aggressive? true`) -- patterns that match real English:
- `HEDGING_PATTERNS` -- some are AI-specific ("It's important to note that") but others are normal English ("Typically", "Usually", "In general")
- `OVERUSED_VERBS_PATTERNS` -- "delve", "leverage", "foster" etc.
- `OVERUSED_ADJECTIVES_PATTERNS` -- "robust", "dynamic", "essential" etc.
- `OVERUSED_NOUNS_AND_TRANSITIONS_PATTERNS` -- "framework", "ecosystem", "moreover" etc.
- `OPENING_CLICHE_PATTERNS` -- "In today's digital age...", "Let's dive into..."
- `CLOSING_CLICHE_PATTERNS` -- "In conclusion...", "To summarize..."

**Why not phrase-vs-word split**: Word count is the wrong heuristic. Some single-word hedges like `"Typically"` are normal English. Some multi-word patterns like `"In conclusion, "` are things humans do write. The right split is by **how likely the pattern is to be a false positive in non-AI text**. Category-based is clearer and easier to curate.

**API changes**:
```clojure
;; New public constants
(def SAFE_PATTERNS ...)      ;; AI identity + refusal + knowledge + punctuation
(def AGGRESSIVE_PATTERNS ...) ;; hedging + verbs + adjectives + nouns + cliches

;; DEFAULT_PATTERNS stays as union for backward compat
(def DEFAULT_PATTERNS (merge SAFE_PATTERNS AGGRESSIVE_PATTERNS))

;; humanize-string gains opts map overload
(humanize-string text)                        ;; safe only (NEW default)
(humanize-string text {:aggressive? true})    ;; all patterns (OLD default behavior)
(humanize-string text {:patterns custom-map}) ;; custom (existing)

;; humanizer factory
(humanizer)                          ;; safe only
(humanizer {:aggressive? true})      ;; all patterns
(humanizer {:patterns custom-map})   ;; custom
```

**Rationale**: Default should be safe for arbitrary text. Users who want aggressive de-AI-ification opt in explicitly. `DEFAULT_PATTERNS` preserved for backward compatibility.

**Acceptance criteria**:
- Default `(humanize-string text)` only applies safe patterns
- `(humanize-string text {:aggressive? true})` applies all patterns
- `SAFE_PATTERNS` does not contain any single common English words
- `DEFAULT_PATTERNS` equals `(merge SAFE_PATTERNS AGGRESSIVE_PATTERNS)`
- Must-NOT-change corpus from T1 passes in default mode
- Must-change corpus for AI identity/refusal/knowledge still passes in default mode

---

## T5: Exclusion zones -- skip code blocks and URLs

**Status**: Pending
**Priority**: High
**Effort**: Medium

**What**: Before applying replacements, identify and protect regions that should never be modified:
- Markdown fenced code blocks (` ```...``` `)
- Inline code (`` `...` ``)
- URLs (`http://...`, `https://...`)

**Approach**: Extract exclusion zones, replace them with placeholders, apply humanization, restore originals.

**Rationale**: LLM outputs frequently contain code snippets and URLs. Replacing `"optimize"` inside a code block or `"navigate"` inside a URL is wrong regardless of tier. This applies even to safe patterns -- imagine `"framework"` in a `pip install framework` code block (in aggressive mode) or a URL containing `"model"` being corrupted.

**Acceptance criteria**:
- `` `optimize(query)` `` unchanged in aggressive mode
- `https://example.com/navigate/to/page` unchanged
- Fenced code blocks pass through entirely untouched
- Patterns adjacent to (but outside) code/URLs still get replaced

---

## T6: Case-preserving replacement for single-word substitutions

**Status**: Pending
**Priority**: Medium
**Effort**: Small

**What**: When replacing a single-word pattern, preserve the case of the original:
- `"Delve"` -> `"Explore"` (title case preserved)
- `"LEVERAGE"` -> `"USE"` (all-caps preserved)
- `"delve"` -> `"explore"` (lowercase preserved)

Only applies to single-word-to-single-word replacements. Multi-word phrase removals (replacement = `""`) don't need this.

**Rationale**: Currently `"Delve into this topic"` becomes `"explore into this topic"` -- broken capitalization at sentence start. This is most visible when aggressive mode is used.

**Acceptance criteria**:
- `"Delve into X"` -> `"Explore into X"` (not `"explore into X"`)
- `"LEVERAGE this"` -> `"USE this"` (all-caps)
- `"leverage this"` -> `"use this"` (lowercase, unchanged behavior)
- Multi-word patterns and empty replacements unaffected

---

## T7: Replace dead `humanize?` boolean with spec-driven `:humanizer` fn in `ask!`

**Status**: Pending
**Priority**: High
**Effort**: Medium

**What**: The current `:humanize?` boolean in `ask!` is dead code (destructured but never read). Replace it with a proper spec-driven humanization mechanism:

1. **Add `::humanize?` to the `field` DSL** -- a new optional boolean flag on field definitions. Marks which fields contain free-text safe to humanize (e.g., `description`, `summary`). Defaults to `false`.
2. **Replace `:humanize?` boolean with `:humanizer` fn in `ask!`** -- accepts a humanizer function (as returned by `(humanizer opts)`). When provided, `ask!` applies it **only** to string fields marked `::humanize? true` in the spec.
3. **Remove `:humanize? false`** from all 6 internal `ask!` calls: core.clj (lines 1168, 1401, 1805) and rlm/internal/pageindex/vision.clj (lines 792, 949, 1135).

**Why spec-driven**: `ask!` returns **validated structured data**. Blindly humanizing all strings breaks semantic contracts:
- An enum field `"framework"` becomes `"structure"` (invalid enum value)
- A proper-noun field `"Foster"` becomes `"encourage"`
- An ID field `"robust-system-v2"` gets corrupted

By marking humanizable fields in the spec, the spec author declares which fields are free-text at definition time. The humanizer only touches those fields.

**API**:
```clojure
;; Spec definition -- mark free-text fields
(svar/field ::svar/name :summary
            ::svar/type :spec.type/string
            ::svar/cardinality :spec.cardinality/one
            ::svar/description "Brief summary"
            ::svar/humanize? true)  ;; <-- NEW: this field is safe to humanize

(svar/field ::svar/name :status
            ::svar/type :spec.type/string
            ::svar/cardinality :spec.cardinality/one
            ::svar/description "Status enum"
            ::svar/values {"active" "Currently active" "archived" "No longer active"})
;; No ::humanize? -- defaults to false, never touched

;; ask! with custom humanizer
(svar/ask! {:config config
            :spec my-spec
            :objective "Extract info."
            :task "..."
            :model "gpt-4o"
            :humanizer (svar/humanizer)})              ;; safe-only patterns
            ;; or: :humanizer (svar/humanizer {:aggressive? true})
            ;; or: :humanizer (svar/humanizer {:patterns custom})
            ;; or: omit :humanizer entirely -- no humanization (default)
```

**Implementation notes**:
- `field` gains optional `::humanize?` boolean (default false). Stored in field def map.
- `ask!` destructures `:humanizer` (a fn) instead of `:humanize?` (a boolean).
- After parsing + validation, if `:humanizer` is provided, walk the result map and apply the humanizer fn to every string value whose corresponding spec field has `::humanize? true`.
- For `:spec.cardinality/many` string fields with `::humanize? true`, apply to each element.
- Non-string fields with `::humanize? true` are ignored (no-op, no error).
- `::humanize?` is purely a post-processing hint -- it does NOT affect prompt generation, parsing, or validation.

**Rationale**: This is the only safe way to humanize inside `ask!`. The spec author knows which fields are free-text. The caller chooses which humanizer to use. Everything else is untouched.

**Acceptance criteria**:
- `field` accepts `::humanize?` boolean option (default false)
- `ask!` accepts `:humanizer` fn option (default nil -- no humanization)
- Old `:humanize?` boolean removed from `ask!` destructuring and docstring
- `:humanize? false` removed from all 6 internal `ask!` calls (core.clj: 1168, 1401, 1805; rlm/vision.clj: 792, 949, 1135)
- Only fields with `::humanize? true` are processed by the humanizer
- Enum fields, ref fields, keyword fields are never humanized regardless of flag
- Test: spec with mixed fields -- humanized field changes, non-humanized field untouched
- Test: no `:humanizer` provided -- no humanization occurs (backward compatible)
- Test: `:humanizer` provided but no fields have `::humanize? true` -- no changes

---

## T8: Minimal artifact cleanup after phrase removal

**Status**: Pending
**Priority**: Medium
**Effort**: Small

**What**: After all replacements and before whitespace normalization, apply deterministic cleanup for artifacts caused by deletions:
1. Strip leading punctuation/whitespace: `^[\s,;:]+`
2. Collapse doubled punctuation: `",\s*,"` -> `","`, `"\.\s*\."` -> `"."`
3. Fix spacing before punctuation: `"\s+([,.;:!?])"` -> `"$1"`

**Do NOT** attempt general sentence capitalization -- abbreviations, code, URLs, `"e.g."`, list items, and headings make this a rabbit hole.

**Rationale**: When `"As an AI, "` is removed from `"As an AI, I think yes."`, the result is `" I think yes."` (leading space) or potentially `", I think yes."` (orphaned comma). The existing `#"\s+" " "` + `trim` handles spaces but not punctuation remnants.

**Acceptance criteria**:
- `", , I think yes."` -> `", I think yes."` (collapsed double comma)
- `"  I think yes."` -> `"I think yes."` (leading whitespace stripped)
- `", I think yes."` -> `"I think yes."` (leading comma stripped, only at string start)
- Does not alter content inside code blocks or URLs (depends on T5)
- Does not attempt capitalization

---

## Task Dependency Graph

```
T1 (test corpus)          T2 (remove no-ops)
    |                         |
    v                         v
T3 (boundary-aware matching) <-+
    |
    v
T4 (safe/aggressive tier split + humanizer factory)
    |
    +---> T5 (exclusion zones)
    |
    +---> T6 (case-preserving replacement)
    |
    +---> T7 (spec-driven :humanizer in ask!)  -- depends on T4 (humanizer factory)
    |
    v
T8 (artifact cleanup -- final polish, after T3+T4+T5 stable)
```

## Recommended execution order

1. **T1** -- Test corpus first. Proves problems, gates all other work.
2. **T2** -- Trivial no-op removal. Quick win.
3. **T3** -- Boundary-aware matching. Foundational correctness fix.
4. **T4** -- Tier split. Changes default behavior to safe-only.
5. **T5** -- Exclusion zones for code/URLs.
6. **T6** -- Case preservation for aggressive mode.
7. **T7** -- Spec-driven `:humanizer` in `ask!`. Depends on T4 humanizer factory.
8. **T8** -- Artifact cleanup. Final polish.

## Effort estimate

**Medium-Large (2-3 days)** including tests. T1+T2+T3+T4 are the critical path. T7 (spec-driven humanizer in `ask!`) touches both the spec DSL and `ask!` pipeline. T5+T6+T8 are important polish but can be deferred.
