package com.blockether.svar;

/**
 * Normalizes streamed/malformed Markdown fence boundaries into a shape the
 * line-based fence parser ({@link FenceBlocksParser}) can consume without
 * guessing.
 *
 * <p>One deterministic, allocation-bounded scan over the input. Total cost
 * O(input length) characters scanned. No regex engine. No backtracking.
 * No {@code Matcher.find()} restart-from-every-position behaviour. Live
 * repro that motivated this rewrite: Vis conversation {@code 0c8188ac},
 * thread parked in {@code Pattern$BmpCharPropertyGreedy.match}.</p>
 *
 * <p>Three normalizations per logical line:</p>
 * <ul>
 *   <li><b>opener-split</b>: {@code ^[ \t]*```[lang]?([ \t]+)?([\[({])} →
 *       split tag and following bracket onto separate lines, drop any
 *       whitespace between them.</li>
 *   <li><b>inline-boundary-split</b>:
 *       {@code [^\r\n`]`{3,}[ \t]+`{3,}[lang]?} inside a line → split the
 *       close+open run onto its own line pair, drop inter-fence
 *       whitespace.</li>
 *   <li><b>closer-split</b>: {@code [^\r\n`][ \t]*`{3,}[ \t]*\z} at line
 *       end → split the closing fence onto its own line.</li>
 * </ul>
 *
 * <p>Fast paths:</p>
 * <ul>
 *   <li>No carriage returns AND no backticks → return input unchanged.</li>
 *   <li>No backticks → only the line-ending normalization runs.</li>
 *   <li>Per line: no backticks → line copied straight through, no
 *       sub-scans, no allocations.</li>
 * </ul>
 */
public final class FenceNormalizer {

    private FenceNormalizer() {}

    private static final char BT = '`';

    public static String normalize(String input) {
        if (input == null || input.isEmpty()) return "";

        boolean hasCr = input.indexOf('\r') >= 0;
        boolean hasBt = input.indexOf(BT) >= 0;

        // Fast-fast path: nothing to do.
        if (!hasCr && !hasBt) return input;

        // Pass 1: line ending normalization. Skipped when there are no
        // carriage returns at all.
        String lf = hasCr ? normalizeLineEndings(input) : input;

        // Pass 2: backticks-only fast path. CRLF already cleaned up; no
        // fence transform can ever apply.
        if (!hasBt) return lf;

        // Pass 3: per-line transforms.
        StringBuilder out = new StringBuilder(lf.length() + 16);
        int n = lf.length();
        int lineStart = 0;
        for (int i = 0; i <= n; i++) {
            if (i == n || lf.charAt(i) == '\n') {
                transformLine(lf, lineStart, i, out);
                if (i < n) out.append('\n');
                lineStart = i + 1;
            }
        }
        return out.toString();
    }

    private static String normalizeLineEndings(String input) {
        int n = input.length();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = input.charAt(i);
            if (c == '\r') {
                sb.append('\n');
                if (i + 1 < n && input.charAt(i + 1) == '\n') i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Transform a single logical line {@code src[start, end)} into
     * {@code out}, in one fused pass over the line.
     *
     * <p>Strategy: locate the opener split (if any) up front; locate the
     * closer split (if any) by scanning the tail; then emit the body once
     * while detecting inline-boundary splits as we go. No intermediate
     * buffers.</p>
     */
    private static void transformLine(CharSequence src, int start, int end, StringBuilder out) {
        if (start >= end) return;

        // Opener split. `bodyStart` is the position from which the rest of
        // the line (the bracketed payload) begins. If no opener matches,
        // `bodyStart == start` and nothing is emitted yet.
        int bodyStart = emitOpenerAndSplit(src, start, end, out);

        // Closer split. Returns the split index inside [bodyStart, end), or
        // -1 if the line does not end in a recognised closer.
        int closerAt = findCloserSplit(src, bodyStart, end);
        int bodyEnd = (closerAt < 0) ? end : closerAt;

        // Emit body, expanding inline-boundary splits as we go. When the
        // body contains no inline-boundary match (the overwhelmingly
        // common case) this degenerates to a single `out.append(src,
        // bodyStart, bodyEnd)` bulk copy.
        emitBodyWithInlineSplits(src, bodyStart, bodyEnd, out);

        // Tail (the closer) on its own line.
        if (closerAt >= 0) {
            out.append('\n');
            out.append(src, closerAt, end);
        }
    }

    // ---------------------------------------------------------------------
    // Opener: ^[ \t]*```[lang]?([ \t]+)?[\(\[\{]
    // Emits everything up to the bracket on its own line; returns position
    // of the first bracket char (== `bodyStart`). When no opener matches,
    // emits nothing and returns `start`.
    // ---------------------------------------------------------------------
    private static int emitOpenerAndSplit(CharSequence src, int start, int end, StringBuilder out) {
        int i = start;
        while (i < end && isHorizontalWs(src.charAt(i))) i++;
        int btStart = i;
        while (i < end && src.charAt(i) == BT) i++;
        if (i - btStart < 3) return start;
        while (i < end && isLangChar(src.charAt(i))) i++;
        int tagEnd = i;
        while (i < end && isHorizontalWs(src.charAt(i))) i++;
        int afterWs = i;
        if (afterWs >= end) return start;
        char nxt = src.charAt(afterWs);
        if (nxt != '(' && nxt != '[' && nxt != '{') return start;
        // Split: emit "[indent][ticks][lang]\n", return afterWs as bodyStart.
        out.append(src, start, tagEnd);
        out.append('\n');
        return afterWs;
    }

    // ---------------------------------------------------------------------
    // Closer: …[^\r\n`][ \t]*`{3,}[ \t]*\z
    // Returns the index immediately after the last non-bt non-nl character
    // before the trailing whitespace + backtick run; -1 if no closer.
    // ---------------------------------------------------------------------
    private static int findCloserSplit(CharSequence src, int start, int end) {
        int i = end;
        while (i > start && isHorizontalWs(src.charAt(i - 1))) i--;
        int wsEnd = i;
        while (i > start && src.charAt(i - 1) == BT) i--;
        if (wsEnd - i < 3) return -1;
        while (i > start && isHorizontalWs(src.charAt(i - 1))) i--;
        if (i <= start) return -1;
        char before = src.charAt(i - 1);
        if (before == BT || before == '\r' || before == '\n') return -1;
        return i;
    }

    // ---------------------------------------------------------------------
    // Inline: …[^\r\n`]`{3,}[ \t]+`{3,}[lang]?…
    // Emits src[start, end) to `out`, expanding any inline-boundary match
    // into "…non-bt\n closeRun \n openRun[lang]…". Whitespace between the
    // two runs is consumed.
    // ---------------------------------------------------------------------
    private static void emitBodyWithInlineSplits(CharSequence src, int start, int end, StringBuilder out) {
        int segStart = start;
        int i = start;
        while (i < end) {
            char c = src.charAt(i);
            if (c != BT) { i++; continue; }
            // Predecessor must be a non-bt non-nl char within the body.
            if (i == segStart) { i = skipRun(src, end, i, BT); continue; }
            char prev = src.charAt(i - 1);
            if (prev == BT || prev == '\r' || prev == '\n') {
                i = skipRun(src, end, i, BT);
                continue;
            }
            int btStart = i;
            i = skipRun(src, end, i, BT);
            if (i - btStart < 3) continue;
            int wsStart = i;
            while (i < end && isHorizontalWs(src.charAt(i))) i++;
            if (i == wsStart) continue;
            if (i >= end || src.charAt(i) != BT) continue;
            int bt2Start = i;
            i = skipRun(src, end, i, BT);
            if (i - bt2Start < 3) continue;
            while (i < end && isLangChar(src.charAt(i))) i++;
            int openEnd = i;
            out.append(src, segStart, btStart);
            out.append('\n');
            out.append(src, btStart, wsStart);
            out.append('\n');
            out.append(src, bt2Start, openEnd);
            segStart = openEnd;
        }
        out.append(src, segStart, end);
    }

    private static int skipRun(CharSequence src, int end, int i, char ch) {
        while (i < end && src.charAt(i) == ch) i++;
        return i;
    }

    private static boolean isHorizontalWs(char c) { return c == ' ' || c == '\t'; }

    private static boolean isLangChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
            || (c >= '0' && c <= '9') || c == '_' || c == '+' || c == '-';
    }
}
