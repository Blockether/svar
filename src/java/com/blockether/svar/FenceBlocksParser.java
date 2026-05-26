package com.blockether.svar;

import java.util.ArrayList;
import java.util.List;

/**
 * Line-based parser for ``` Markdown fences. Companion to
 * {@link FenceNormalizer}: the normalizer reshapes glued/streamed fence
 * boundaries onto their own lines; this parser walks those lines and
 * emits a list of {@code Block(lang, source)} records.
 *
 * <p>Replaces the Clojure {@code parse-fenced-blocks} loop. On a 0.9 MB
 * fenced response the Clojure version spent ~5 ms in {@code str/split} +
 * 12 000 {@code re-matches} calls; this implementation walks the input
 * directly in a single pass without splitting or recomputing line
 * indices.</p>
 *
 * <p>Lenient+ rules — match what {@code parse-fenced-blocks} used to do:
 * </p>
 * <ul>
 *   <li>Plain open / close fences: {@code ```[lang]?} / {@code ```}.</li>
 *   <li>Glued close + open on a single fence line: a tagged fence whose
 *       backtick run is at least {@code open-len + 3} characters long is
 *       treated as the close of the current block plus the open of the
 *       next.</li>
 *   <li>Unclosed final fence at EOF → implicit close when body has real
 *       content and no nested fence line. This preserves streamed LLM output
 *       whose final ``` closer was truncated.</li>
 *   <li>Malformed final fence → return {@code malformed = true}; completed
 *       blocks remain available, invalid final block is dropped.</li>
 *   <li>Any extracted body that still contains a glued close+short-open
 *       fragment ({@code ```{3,}[ \t]+`{1,2}[A-Za-z0-9_+-]+}) → also
 *       malformed; reject the whole extraction.</li>
 * </ul>
 */
public final class FenceBlocksParser {

    private FenceBlocksParser() {}

    private static final char BT = '`';

    public static final class Block {
        public final String lang;   // null when fence had no tag
        public final String source; // body verbatim
        public Block(String lang, String source) { this.lang = lang; this.source = source; }
    }

    public static final class Result {
        public final List<Block> blocks;
        public final boolean sawFence;
        public final boolean malformed;
        public Result(List<Block> blocks, boolean sawFence, boolean malformed) {
            this.blocks = blocks; this.sawFence = sawFence; this.malformed = malformed;
        }
    }

    /**
     * Parse a fence-normalized buffer. Input is assumed to have already
     * been run through {@link FenceNormalizer#normalize(String)}.
     */
    public static Result parse(String s) {
        if (s == null || s.isEmpty()) {
            return new Result(new ArrayList<>(), false, false);
        }
        final int n = s.length();
        final List<Block> blocks = new ArrayList<>();
        boolean inBlock = false;
        int openLen = 0;
        String openLang = null;
        // Body is always a contiguous slice of the input between the
        // opening fence's trailing '\n' and the closing fence line. We
        // therefore record the slice bounds and materialise the body with
        // a single `String.substring` call when the block closes — zero
        // per-line copies, zero StringBuilder churn.
        int bodyStart = -1;   // index of first body char (just after the opener's '\n')
        int bodyEnd = -1;     // index one past the last body char (just before the closer's leading '\n')
        boolean sawFence = false;

        // LLM-friendly fence nesting. CommonMark fenced blocks do not
        // nest, but coding-agent models routinely emit code samples
        // *inside* an outer ```clojure block — typically inside a string
        // argument to (done {:answer "… ```clojure (deftest …) ``` …"}).
        // Without nesting, the inner sample's bare ``` closes the outer
        // block early (Vis conv 11d4f817 / t12/i1: torn (done …) form,
        // dropped trailer keys, model retry burns a whole iteration).
        //
        // Rule: while in-block, a tagged-opener fence line of equal
        // backtick length is treated as PUSH (an inner sample opening);
        // the next bare closer of equal-or-greater length POPS instead
        // of closing the outer block. The outer block closes only when
        // nesting depth returns to zero.
        int innerDepth = 0;

        int lineStart = 0;
        for (int i = 0; i <= n; i++) {
            if (i == n || s.charAt(i) == '\n') {
                int lineEnd = i;
                FenceLine fl = recognizeFenceLine(s, lineStart, lineEnd);

                if (!inBlock && fl != null) {
                    inBlock = true;
                    openLen = fl.ticks;
                    openLang = fl.lang;
                    bodyStart = i + 1;
                    bodyEnd = i + 1; // empty body until the next body line is seen
                    sawFence = true;
                } else if (inBlock && fl != null && fl.lang != null
                             && fl.ticks == openLen) {
                    // Inner tagged opener inside same-length outer fence:
                    // push nesting and keep the line as body content.
                    innerDepth++;
                    bodyEnd = lineEnd;
                    sawFence = true;
                } else if (inBlock && fl != null && fl.lang == null && fl.ticks >= openLen) {
                    if (innerDepth > 0) {
                        // Pop nested sample close; line stays inside body.
                        innerDepth--;
                        bodyEnd = lineEnd;
                        sawFence = true;
                    } else {
                        blocks.add(new Block(openLang, sliceBody(s, bodyStart, bodyEnd)));
                        inBlock = false;
                        openLen = 0;
                        openLang = null;
                        bodyStart = -1;
                        bodyEnd = -1;
                        sawFence = true;
                    }
                } else if (inBlock && fl != null && fl.lang != null && fl.ticks >= openLen + 3) {
                    blocks.add(new Block(openLang, sliceBody(s, bodyStart, bodyEnd)));
                    openLen = fl.ticks - openLen;
                    openLang = fl.lang;
                    bodyStart = i + 1;
                    bodyEnd = i + 1;
                    innerDepth = 0;
                    sawFence = true;
                } else {
                    if (inBlock) {
                        // Extend body up to the END of this line (the '\n'
                        // separator stays inside the body when more lines
                        // follow; it's trimmed off if this turns out to be
                        // the line right before the closer).
                        bodyEnd = lineEnd;
                    }
                    if (fl != null) sawFence = true;
                }

                lineStart = i + 1;
            }
        }

        if (inBlock) {
            // EOF after opener. LLM streams often lose only final ``` closer.
            // Treat non-empty, fence-free body as implicitly closed; drop bad
            // final body but keep earlier complete blocks for recovery.
            //
            // `containsStandaloneFenceLine` rejects bodies that look like
            // they have unmatched inner fences — but with nesting enabled
            // a body that contains a balanced inner ```lang … ``` pair is
            // legitimate (a code sample inside an :answer string), so we
            // first attempt to fold matched inner pairs out of the body
            // before checking for leftover standalone fence lines.
            String source = sliceBody(s, bodyStart, bodyEnd);
            if (!isBlank(source) && !hasUnpairedInnerFenceLine(source)) {
                blocks.add(new Block(openLang, source));
            } else {
                return new Result(blocks, true, true);
            }
        }

        for (Block b : blocks) {
            if (containsMalformedGluedFence(b.source)) {
                return new Result(new ArrayList<>(), true, true);
            }
        }

        return new Result(blocks, sawFence, false);
    }

    /**
     * True when the body contains a fence line that does not pair into
     * a tagged-open + bare-close span. Allows balanced inner samples
     * (e.g. ```clojure … ``` inside a string) while still flagging a
     * lone trailing fence as torn.
     */
    private static boolean hasUnpairedInnerFenceLine(String source) {
        if (source == null || source.isEmpty()) return false;
        int n = source.length();
        int depth = 0;
        int lineStart = 0;
        for (int i = 0; i <= n; i++) {
            if (i == n || source.charAt(i) == '\n') {
                FenceLine fl = recognizeFenceLine(source, lineStart, i);
                if (fl != null) {
                    if (fl.lang != null) {
                        depth++;
                    } else if (depth > 0) {
                        depth--;
                    } else {
                        return true; // bare close with nothing to close
                    }
                }
                lineStart = i + 1;
            }
        }
        return depth != 0; // tagged opener with no matching close
    }

    /** Materialize the contiguous body slice. Empty when start >= end. */
    private static String sliceBody(String s, int start, int end) {
        if (start < 0 || end <= start) return "";
        return s.substring(start, end);
    }

    // ---------------------------------------------------------------------
    // Line classifier: ^([ \t]*)(`{3,})([A-Za-z0-9_+\-]*)[ \t]*(.*)$
    //
    // CommonMark says the trailing region must be whitespace-only, but
    // coding LLMs routinely emit a clean closer followed by stray
    // punctuation/dashes on the same line (e.g. "```        -   -    ").
    // Vis session b94052f0 froze for ~870 ms per paint because that
    // trailing run leaked into the body, then edamame read the bare
    // ``` as three nested syntax-quote reader macros wrapping the
    // junk → ~1 KB macroexpansion fed into zprint on every render.
    //
    // Pragmatic relaxation: BARE closers (no lang chars at all) accept
    // any trailing chars after the ticks+whitespace. Tagged openers stay
    // strict so a body line like "```clojure noise" remains body, not a
    // bogus opener that would steal the rest of the response.
    // ---------------------------------------------------------------------
    private static final class FenceLine {
        final int ticks;
        final String lang; // null when missing
        FenceLine(int ticks, String lang) { this.ticks = ticks; this.lang = lang; }
    }

    private static FenceLine recognizeFenceLine(String s, int start, int end) {
        int i = start;
        while (i < end && isHorizontalWs(s.charAt(i))) i++;
        int btStart = i;
        while (i < end && s.charAt(i) == BT) i++;
        int ticks = i - btStart;
        if (ticks < 3) return null;
        int langStart = i;
        while (i < end && isLangChar(s.charAt(i))) i++;
        int langEnd = i;
        while (i < end && isHorizontalWs(s.charAt(i))) i++;
        boolean hasLang = (langEnd > langStart);
        // Tagged opener: trailing junk after lang+ws disqualifies (keeps
        // body lines that happen to start with ```name word safe).
        if (hasLang && i != end) return null;
        // Bare closer: any trailing content after ticks+ws is tolerated.
        // (When `i == end` the rule is unchanged — still a closer.)
        String lang = hasLang ? lowerAscii(s, langStart, langEnd) : null;
        return new FenceLine(ticks, lang);
    }

    private static String lowerAscii(String s, int start, int end) {
        // Lang chars are restricted to [A-Za-z0-9_+-]; only ASCII letters
        // need lowering. A direct char loop avoids allocating an
        // intermediate substring for the common all-lowercase case.
        boolean needsLower = false;
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') { needsLower = true; break; }
        }
        if (!needsLower) return s.substring(start, end);
        StringBuilder sb = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isBlank(String source) {
        if (source == null || source.isEmpty()) return true;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return false;
        }
        return true;
    }

    private static boolean containsStandaloneFenceLine(String source) {
        if (source == null || source.isEmpty()) return false;
        int n = source.length();
        int lineStart = 0;
        for (int i = 0; i <= n; i++) {
            if (i == n || source.charAt(i) == '\n') {
                if (recognizeFenceLine(source, lineStart, i) != null) return true;
                lineStart = i + 1;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Malformed-glued-fence detector: `{3,}[ \t]+`{1,2}[A-Za-z0-9_+-]+
    // ---------------------------------------------------------------------
    private static boolean containsMalformedGluedFence(String source) {
        if (source == null || source.isEmpty()) return false;
        int n = source.length();
        int i = 0;
        while (i < n) {
            // find a run of 3+ backticks
            if (source.charAt(i) != BT) { i++; continue; }
            int runStart = i;
            while (i < n && source.charAt(i) == BT) i++;
            int run1 = i - runStart;
            if (run1 < 3) continue;
            // [ \t]+
            int wsStart = i;
            while (i < n && isHorizontalWs(source.charAt(i))) i++;
            if (i == wsStart) continue;
            // `{1,2}
            int run2Start = i;
            while (i < n && source.charAt(i) == BT) i++;
            int run2 = i - run2Start;
            if (run2 < 1 || run2 > 2) continue;
            // [A-Za-z0-9_+-]+
            int langStart = i;
            while (i < n && isLangChar(source.charAt(i))) i++;
            if (i > langStart) return true;
        }
        return false;
    }

    private static boolean isHorizontalWs(char c) { return c == ' ' || c == '\t'; }

    private static boolean isLangChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
            || (c >= '0' && c <= '9') || c == '_' || c == '+' || c == '-';
    }
}
