package ai.resourcepack.ui.framework.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * The client's map colours, plus the two encodings a screen is sent in.
 *
 * <p><b>HI</b> (needs the resource pack): YCbCr 4:2:0 in 240 distinct map colours. Symbol = luma × 8 +
 * digit: 30 luma levels per pixel, and the four digits of each 2×2 block carry Cb and Cr at 64 levels
 * each. The pack's text shader (every item-frame map is drawn with the text pipeline) turns a symbol
 * back into its meaning through a perfect hash and averages the map pixels under each window pixel,
 * so the picture scales smoothly and keeps its colours day and night. The top-left 2×2 of every map is
 * a marker made of four more colours, so the shader only ever touches our maps; its bottom two are
 * swapped on a screen that is somebody's own fullscreen view, which the shader draws in front of the
 * world while they are seated.
 *
 * <p><b>PLAIN</b> (vanilla clients): the nearest real map colour through a 32×32×32 lookup, with
 * ordered dithering.
 */
public final class Palette {
    public static final int LUMA_LEVELS = 30;
    public static final int DIGITS = 8;
    public static final int SYMBOLS = LUMA_LEVELS * DIGITS;
    static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

    public final int[] rgbById = new int[256];
    public final byte[] symbolId = new byte[SYMBOLS];
    public final int[] symbolRgb = new int[SYMBOLS];
    public final byte[] markerId = new byte[4];
    public final int[] markerRgb = new int[4];
    private final byte[] plainLut = new byte[32 * 32 * 32];
    /** Luma level (times DIGITS) for each Bayer cell (16) and Y byte (256), dither included. */
    private final int[] lumaRow = new int[16 * 256];
    /** 64-level chroma for the sum of four Cb (or Cr) bytes; 32 is exactly neutral, so greys stay grey. */
    private final int[] chromaLevel = new int[4 * 255 + 1];

    /** [colorOf] is the client's map colour table: packed id to ARGB, as {@code MapColor.getColorFromPackedId}. */
    public Palette(IntUnaryOperator colorOf) {
        Map<Integer, Integer> distinct = new LinkedHashMap<>();
        List<Integer> opaque = new ArrayList<>();
        for (int id = 4; id < 256; id++) {
            int argb;
            try {
                argb = colorOf.applyAsInt(id);
            } catch (RuntimeException e) {
                continue;
            }
            if ((argb >>> 24) != 0xFF) continue;
            rgbById[id] = argb & 0xFFFFFF;
            opaque.add(id);
            distinct.putIfAbsent(argb & 0xFFFFFF, id);
        }
        List<Integer> unique = new ArrayList<>(distinct.values());
        if (unique.size() < SYMBOLS + 4) {
            throw new IllegalStateException("Only " + unique.size() + " distinct map colours, need " + (SYMBOLS + 4));
        }
        for (int s = 0; s < SYMBOLS; s++) {
            symbolId[s] = (byte) (int) unique.get(s);
            symbolRgb[s] = rgbById[unique.get(s)];
        }
        for (int m = 0; m < 4; m++) {
            markerId[m] = (byte) (int) unique.get(SYMBOLS + m);
            markerRgb[m] = rgbById[unique.get(SYMBOLS + m)];
        }
        for (int r = 0; r < 32; r++) for (int g = 0; g < 32; g++) for (int b = 0; b < 32; b++) {
            int rr = r * 255 / 31, gg = g * 255 / 31, bb = b * 255 / 31;
            int best = opaque.get(0);
            long bestD = Long.MAX_VALUE;
            for (int id : opaque) {
                int c = rgbById[id];
                int dr = rr - (c >> 16 & 255), dg = gg - (c >> 8 & 255), db = bb - (c & 255);
                long d = 3L * dr * dr + 6L * dg * dg + (long) db * db;
                if (d < bestD) {
                    bestD = d;
                    best = id;
                }
            }
            plainLut[(r << 10) | (g << 5) | b] = (byte) best;
        }
        for (int k = 0; k < lumaRow.length; k++) {
            float dither = (BAYER[k >> 8] + 0.5f) / 16f - 0.5f;
            int level = Math.round((k & 255) / 255f * (LUMA_LEVELS - 1) + dither);
            lumaRow[k] = Math.max(0, Math.min(LUMA_LEVELS - 1, level)) * DIGITS;
        }
        for (int sum = 0; sum < chromaLevel.length; sum++) {
            chromaLevel[sum] = Math.max(0, Math.min(63, Math.round((sum / 4f - 128f) / 255f * 63) + 32));
        }
    }

    /**
     * Encode the 128×128 region at (ox, oy) of an interleaved YCbCr image (JFIF full range, 3 bytes per
     * pixel, [stride] pixels per row) into one map's colour bytes.
     */
    public void encodeHi(byte[] ycc, int stride, int ox, int oy, byte[] out) {
        for (int by = 0; by < 128; by += 2) {
            int r0 = ((oy + by) * stride + ox) * 3;
            int r1 = r0 + stride * 3;
            int cellTop = ((by & 3) * 4) << 8;
            int cellBottom = (((by + 1) & 3) * 4) << 8;
            for (int bx = 0; bx < 128; bx += 2) {
                int a = r0 + bx * 3, b = a + 3, c = r1 + bx * 3, d = c + 3;
                int qcb = chromaLevel[(ycc[a + 1] & 255) + (ycc[b + 1] & 255) + (ycc[c + 1] & 255) + (ycc[d + 1] & 255)];
                int qcr = chromaLevel[(ycc[a + 2] & 255) + (ycc[b + 2] & 255) + (ycc[c + 2] & 255) + (ycc[d + 2] & 255)];
                int o = by * 128 + bx;
                int col = (bx & 3) << 8;
                out[o] = symbolId[lumaRow[cellTop + col + (ycc[a] & 255)] + (qcb >> 3)];
                out[o + 1] = symbolId[lumaRow[cellTop + col + (1 << 8) + (ycc[b] & 255)] + (qcb & 7)];
                out[o + 128] = symbolId[lumaRow[cellBottom + col + (ycc[c] & 255)] + (qcr >> 3)];
                out[o + 129] = symbolId[lumaRow[cellBottom + col + (1 << 8) + (ycc[d] & 255)] + (qcr & 7)];
            }
        }
        out[0] = markerId[0];
        out[1] = markerId[1];
        out[128] = markerId[2];
        out[129] = markerId[3];
    }

    /**
     * A copy of a HI tile marked as somebody's own fullscreen view of [cols] × [rows] maps: its bottom two
     * marker colours swapped, and the two pixels right of the marker the symbols for its columns and
     * rows, from which the shader stretches the view to the window. That 2×2 block shows the next one.
     */
    public byte[] markOwn(byte[] hi, int cols, int rows) {
        byte[] own = hi.clone();
        own[128] = markerId[3];
        own[129] = markerId[2];
        own[2] = symbolId[Math.min(SYMBOLS - 1, cols)];
        own[3] = symbolId[Math.min(SYMBOLS - 1, rows)];
        return own;
    }

    /**
     * A copy of a HI tile marked as tile ([col], [row]) of somebody's own fullscreen view of [cols] × [rows]
     * maps whose pointer their client draws: both bottom marker colours the fourth, the view's columns
     * and rows right of the marker as in {@link #markOwn}, and the tile's own column and row below those.
     * The shader places the tile in the window from these alone, wherever its frame is.
     */
    public byte[] markTiled(byte[] hi, int cols, int rows, int col, int row) {
        byte[] own = hi.clone();
        own[128] = markerId[3];
        own[129] = markerId[3];
        own[2] = symbolId[Math.min(SYMBOLS - 1, cols)];
        own[3] = symbolId[Math.min(SYMBOLS - 1, rows)];
        own[130] = symbolId[Math.min(SYMBOLS - 1, col)];
        own[131] = symbolId[Math.min(SYMBOLS - 1, row)];
        return own;
    }

    public void encodePlain(byte[] ycc, int stride, int ox, int oy, byte[] out) {
        for (int y = 0; y < 128; y++) {
            int i = ((oy + y) * stride + ox) * 3;
            for (int x = 0; x < 128; x++) {
                int yy = ycc[i] & 255;
                int cb = (ycc[i + 1] & 255) - 128;
                int cr = (ycc[i + 2] & 255) - 128;
                i += 3;
                int d = BAYER[(y & 3) * 4 + (x & 3)] / 2 - 4; // ±4 in 0..255, enough to break banding
                int r = clamp(yy + ((91881 * cr) >> 16) + d) >> 3;
                int g = clamp(yy - ((22554 * cb + 46802 * cr) >> 16) + d) >> 3;
                int b = clamp(yy + ((116130 * cb) >> 16) + d) >> 3;
                out[y * 128 + x] = plainLut[(r << 10) | (g << 5) | b];
            }
        }
    }

    /** The nearest plain map colour to an RGB colour (the pointer drawn into plain maps). */
    public byte plain(int rgb) {
        return plainLut[((rgb >> 19 & 31) << 10) | ((rgb >> 11 & 31) << 5) | (rgb >> 3 & 31)];
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /** JFIF RGB to YCbCr in fixed point, written as three bytes at out[at]. */
    public static void rgbToYcc(int p, byte[] out, int at) {
        int r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
        out[at] = (byte) ((19595 * r + 38470 * g + 7471 * b + 32768) >> 16);
        out[at + 1] = (byte) clamp(((-11059 * r - 21709 * g + 32768 * b + 32768) >> 16) + 128);
        out[at + 2] = (byte) clamp(((32768 * r - 27439 * g - 5329 * b + 32768) >> 16) + 128);
    }

    /** JFIF YCbCr to packed RGB, the inverse of {@link #rgbToYcc}. */
    public static int yccToRgb(int y, int cb, int cr) {
        int b = cb - 128, r = cr - 128;
        return (clamp((int) (y + 1.402 * r)) << 16) | (clamp((int) (y - 0.344136 * b - 0.714136 * r)) << 8) | clamp((int) (y + 1.772 * b));
    }
}
