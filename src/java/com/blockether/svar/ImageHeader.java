package com.blockether.svar;

/**
 * Reads an image's pixel size straight out of its HEADER bytes.
 *
 * <p>Vision token estimation needs exactly one fact about an image: how big it
 * is. Asking {@code javax.imageio} for that drags the whole {@code java.desktop}
 * module — AWT toolkit, fontconfig, the image-reader SPI — into every process
 * that merely counts tokens, and into every GraalVM native image embedding svar.
 * This class decodes no pixels and touches no JDK module beyond
 * {@code java.base}.
 *
 * <p>Understands PNG, JPEG, GIF, BMP and WebP (VP8 / VP8L / VP8X) — every format
 * an LLM vision endpoint accepts. Anything unrecognised, truncated or malformed
 * answers {@code null} rather than throwing, so the caller can fall back to a
 * fixed estimate.
 */
public final class ImageHeader {

    private ImageHeader() {}

    /** Unsigned byte {@code i} of {@code b}, or -1 past either end. */
    private static int u8(byte[] b, int i) {
        return (i >= 0 && i < b.length) ? (b[i] & 0xff) : -1;
    }

    private static int be16(byte[] b, int i) {
        return (u8(b, i) << 8) | u8(b, i + 1);
    }

    private static int be32(byte[] b, int i) {
        return (u8(b, i) << 24) | (u8(b, i + 1) << 16) | (u8(b, i + 2) << 8) | u8(b, i + 3);
    }

    private static int le16(byte[] b, int i) {
        return u8(b, i) | (u8(b, i + 1) << 8);
    }

    private static int le24(byte[] b, int i) {
        return u8(b, i) | (u8(b, i + 1) << 8) | (u8(b, i + 2) << 16);
    }

    private static int le32(byte[] b, int i) {
        return le24(b, i) | (u8(b, i + 3) << 24);
    }

    /** True when the ASCII {@code sig} sits at offset {@code off} of {@code b}. */
    private static boolean magic(byte[] b, int off, String sig) {
        int n = sig.length();
        if (off < 0 || off + n > b.length) {
            return false;
        }
        for (int k = 0; k < n; k++) {
            if (u8(b, off + k) != sig.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private static int[] size(int w, int h) {
        return new int[] {w, h};
    }

    /** Walks JPEG segment markers to the first SOF and reads its size. */
    private static int[] jpeg(byte[] b) {
        int n = b.length;
        int p = 2;
        while (p + 9 < n) {
            if (u8(b, p) != 0xff) {
                p++;
                continue;
            }
            int m = u8(b, p + 1);
            // fill byte, TEM, RSTn / SOI / EOI carry no length
            if (m == 0xff || m == 0x01 || (m >= 0xd0 && m <= 0xd9)) {
                p++;
                continue;
            }
            // SOFn, except DHT (c4), JPG (c8) and DAC (cc)
            if (m >= 0xc0 && m <= 0xcf && m != 0xc4 && m != 0xc8 && m != 0xcc) {
                return size(be16(b, p + 7), be16(b, p + 5));
            }
            int len = be16(b, p + 2);
            if (len <= 1) {
                return null;
            }
            p += 2 + len;
        }
        return null;
    }

    /** Reads the size from a WebP VP8 / VP8L / VP8X chunk. */
    private static int[] webp(byte[] b) {
        if (magic(b, 12, "VP8 ")) {
            return b.length >= 30 ? size(le16(b, 26) & 0x3fff, le16(b, 28) & 0x3fff) : null;
        }
        if (magic(b, 12, "VP8L")) {
            if (b.length < 25) {
                return null;
            }
            int b1 = u8(b, 21);
            int b2 = u8(b, 22);
            int b3 = u8(b, 23);
            int b4 = u8(b, 24);
            return size(1 + (b1 | ((b2 & 0x3f) << 8)),
                        1 + ((b2 >> 6) | (b3 << 2) | ((b4 & 0x0f) << 10)));
        }
        if (magic(b, 12, "VP8X")) {
            return b.length >= 30 ? size(1 + le24(b, 24), 1 + le24(b, 27)) : null;
        }
        return null;
    }

    /**
     * The image's {@code [width, height]} in pixels, or {@code null} when the
     * bytes are absent, truncated or of an unknown format.
     */
    public static int[] dimensions(byte[] b) {
        if (b == null || b.length < 12) {
            return null;
        }
        try {
            if (u8(b, 0) == 0x89 && magic(b, 1, "PNG\r\n")) {
                return (b.length >= 24 && magic(b, 12, "IHDR"))
                        ? size(be32(b, 16), be32(b, 20))
                        : null;
            }
            if (u8(b, 0) == 0xff && u8(b, 1) == 0xd8) {
                return jpeg(b);
            }
            if (magic(b, 0, "GIF8")) {
                return size(le16(b, 6), le16(b, 8));
            }
            if (magic(b, 0, "RIFF") && magic(b, 8, "WEBP")) {
                return webp(b);
            }
            if (magic(b, 0, "BM")) {
                return b.length >= 26 ? size(Math.abs(le32(b, 18)), Math.abs(le32(b, 22))) : null;
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
