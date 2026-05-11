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
 *   <li>Unclosed or malformed final fence → return {@code malformed = true}
 *       and an empty block list.</li>
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
                } else if (inBlock && fl != null && fl.lang == null && fl.ticks >= openLen) {
                    blocks.add(new Block(openLang, sliceBody(s, bodyStart, bodyEnd)));
                    inBlock = false;
                    openLen = 0;
                    openLang = null;
                    bodyStart = -1;
                    bodyEnd = -1;
                    sawFence = true;
                } else if (inBlock && fl != null && fl.lang != null && fl.ticks >= openLen + 3) {
                    blocks.add(new Block(openLang, sliceBody(s, bodyStart, bodyEnd)));
                    openLen = fl.ticks - openLen;
                    openLang = fl.lang;
                    bodyStart = i + 1;
                    bodyEnd = i + 1;
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
            // unclosed final fence → malformed
            return new Result(new ArrayList<>(), true, true);
        }

        for (Block b : blocks) {
            if (containsMalformedGluedFence(b.source)) {
                return new Result(new ArrayList<>(), true, true);
            }
        }

        return new Result(blocks, sawFence, false);
    }

    /** Materialize the contiguous body slice. Empty when start >= end. */
    private static String sliceBody(String s, int start, int end) {
        if (start < 0 || end <= start) return "";
        return s.substring(start, end);
    }

    // ---------------------------------------------------------------------
    // Line classifier: ^([ \t]*)(`{3,})([A-Za-z0-9_+\-]*)[ \t]*$
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
        if (i != end) return null; // trailing junk after lang → not a fence line
        String lang = (langEnd > langStart) ? lowerAscii(s, langStart, langEnd) : null;
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
