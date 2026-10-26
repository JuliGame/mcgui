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
 *
 * <p><b>Transparency.</b> Map colours 0 to 3 are the client's only transparent ones (alpha 0, all four
 * alike): through an invisible frame the world behind shows. A picture marks a transparent pixel with
 * the YCbCr bytes (0, 0, 0) ({@link #isClear}), which no RGB colour converts to (Cb only reaches 0 at pure
 * yellow, Cr only at pure cyan). HI makes a whole 2×2 block transparent when at least two of its
 * pixels are (a block shares one chroma, so it is the smallest unit), PLAIN each pixel.
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
    /** The transparent map colour. */
    public static final byte CLEAR_ID = 0;
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

    /** {@link #encodeHi}'s bounds of a map with no opaque block, and of a map that is all opaque. */
    public static final int NO_BOUNDS = -1, FULL_BOUNDS = boundsOf(0, 0, 63, 63);

    /** Packed bounds of 2×2 blocks [bx0, bx1] × [by0, by1], each 0..63. */
    public static int boundsOf(int bx0, int by0, int bx1, int by1) {
        return bx0 | by0 << 6 | bx1 << 12 | by1 << 18;
    }

    /**
     * Encode the 128×128 region at (ox, oy) of an interleaved YCbCr image (JFIF full range, 3 bytes per
     * pixel, [stride] pixels per row) into one map's colour bytes. With [clear], pixels marked transparent
     * ({@link #isClear}) are honoured: a block with two or more is transparent, and a lone one in a block takes
     * an opaque neighbour's colour; without, there are none, and nothing is checked. Returns the bounds of the
     * opaque blocks ({@link #boundsOf}; {@link #NO_BOUNDS} if none, {@link #FULL_BOUNDS} without [clear]).
     */
    public int encodeHi(byte[] ycc, int stride, int ox, int oy, byte[] out, boolean clear) {
        int bx0 = 64, by0 = 64, bx1 = -1, by1 = -1;
        for (int by = 0; by < 128; by += 2) {
            int r0 = ((oy + by) * stride + ox) * 3;
            int r1 = r0 + stride * 3;
            int cellTop = ((by & 3) * 4) << 8;
            int cellBottom = (((by + 1) & 3) * 4) << 8;
            for (int bx = 0; bx < 128; bx += 2) {
                int a = r0 + bx * 3, b = a + 3, c = r1 + bx * 3, d = c + 3;
                int o = by * 128 + bx;
                if (clear) {
                    boolean ca = isClear(ycc, a), cb = isClear(ycc, b), cc = isClear(ycc, c), cd = isClear(ycc, d);
                    int n = (ca ? 1 : 0) + (cb ? 1 : 0) + (cc ? 1 : 0) + (cd ? 1 : 0);
                    if (n >= 2) {
                        out[o] = out[o + 1] = out[o + 128] = out[o + 129] = CLEAR_ID;
                        continue;
                    }
                    if (n == 1) {
                        // [a] if it is opaque, else [d] (then the only clear one is [a]).
                        int opaque = ca ? d : a;
                        if (ca) a = opaque;
                        else if (cb) b = opaque;
                        else if (cc) c = opaque;
                        else d = opaque;
                    }
                    int x = bx >> 1, y = by >> 1;
                    if (x < bx0) bx0 = x;
                    if (x > bx1) bx1 = x;
                    if (y < by0) by0 = y;
                    by1 = y;
                }
                int qcb = chromaLevel[(ycc[a + 1] & 255) + (ycc[b + 1] & 255) + (ycc[c + 1] & 255) + (ycc[d + 1] & 255)];
                int qcr = chromaLevel[(ycc[a + 2] & 255) + (ycc[b + 2] & 255) + (ycc[c + 2] & 255) + (ycc[d + 2] & 255)];
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
        if (!clear) return FULL_BOUNDS;
        return bx1 < 0 ? NO_BOUNDS : boundsOf(bx0, by0, bx1, by1);
    }

    /** The 64-level chroma HI sends for a block whose four Cb (or Cr) bytes add up to [sum]. */
    public int chromaLevelOf(int sum) {
        return chromaLevel[Math.max(0, Math.min(chromaLevel.length - 1, sum))];
    }

    /** Whether the YCbCr pixel at ycc[i] is the transparent mark: Cb and Cr both 0, which no RGB colour gives. */
    public static boolean isClear(byte[] ycc, int i) {
        return (ycc[i + 1] | ycc[i + 2]) == 0;
    }

    /**
     * The four symbols an own view carries for [tint] (ARGB), in the 2×2 block after its size: red, green,
     * blue and alpha, each 0..255 as 0..{@code SYMBOLS - 1}. The shader lays the tint over the world wherever
     * the picture is transparent.
     */
    private void markTint(byte[] own, int tint) {
        own[4] = symbolId[tintLevel(tint >> 16)];
        own[5] = symbolId[tintLevel(tint >> 8)];
        own[132] = symbolId[tintLevel(tint)];
        own[133] = symbolId[tintLevel(tint >>> 24)];
    }

    private static int tintLevel(int channel) {
        return Math.round((channel & 255) * (SYMBOLS - 1) / 255f);
    }

    /**
     * A copy of a HI tile marked as tile ([col], [row]) of somebody's own fullscreen view of [cols] × [rows]
     * maps: both bottom marker colours the fourth, the two pixels right of the marker the symbols for its
     * columns and rows, the 2×2 block after that its [tint] (see {@link #markTint}), and the tile's own column
     * and row below the size. The shader places the tile in the window from these alone, wherever its frame is.
     */
    public byte[] markTiled(byte[] hi, int cols, int rows, int col, int row, int tint) {
        byte[] own = hi.clone();
        own[128] = markerId[3];
        own[129] = markerId[3];
        own[2] = symbolId[Math.min(SYMBOLS - 1, cols)];
        own[3] = symbolId[Math.min(SYMBOLS - 1, rows)];
        own[130] = symbolId[Math.min(SYMBOLS - 1, col)];
        own[131] = symbolId[Math.min(SYMBOLS - 1, row)];
        markTint(own, tint);
        return own;
    }

    /**
     * An overlay's signature map for a view of [cols] × [rows] maps (see {@code Shaders.overlayChain}): transparent,
     * marked as a tile of a view whose client places it ({@link #markTiled}) but at column and row
     * {@code Shaders.SIGNATURE_TILE} ({@code SYMBOLS - 1}), which no tile has, with the view's [tint], and in pixel
     * (6, 0) its [flags] ({@code Shaders.SIGNATURE_BLUR}, {@code SIGNATURE_KEPT}). The shader puts it over the
     * window's four bottom-left pixels and writes the signature there.
     */
    public byte[] markSignature(int cols, int rows, int tint, int flags) {
        byte[] own = markTiled(new byte[128 * 128], cols, rows, SYMBOLS - 1, SYMBOLS - 1, tint);
        own[0] = markerId[0];
        own[1] = markerId[1];
        own[6] = signatureFlags(flags);
        return own;
    }

    /** Signature pixel (6, 0) for [flags]: what a patch of that one pixel sends when they change. */
    public byte signatureFlags(int flags) {
        return symbolId[flags];
    }

    /** The most a {@link #markLayer} edge can be: two symbols' worth. */
    public static final int MAX_EDGE = SYMBOLS * SYMBOLS - 1;

    /** {@link #markLayer}'s [side]: the view is along z (else x) from the player's eye; they look towards decreasing x or z. */
    public static final int LAYER_ALONG_Z = 2, LAYER_LOOKS_NEGATIVE = 1;

    /** {@link #markLayer}'s priorities run from 0 to one less than this: four of them, with the [side], fit in a symbol. */
    public static final int LAYER_PRIORITIES = SYMBOLS / 4;

    /**
     * A HI map marked as part of a hover layer of somebody's own fullscreen view of [cols] × [rows] maps whose pointer
     * their client draws (see {@link #markTiled}): its rows 2 to 127 are the canvas from pixel ([x], [y]) on, 128 across,
     * wherever that is (not one of the view's tiles), and it shows only while that pointer is inside [include] and
     * outside [exclude] (each {left, top, right, bottom} in canvas pixels, right and bottom not included). Its top two
     * marker colours are swapped and its bottom two both the fourth; then the view's columns and rows, and below them
     * [x] (two symbols, high first); then the pointer's speed in pixels per degree times
     * {@code Shaders.LAYER_SPEED_SCALE} ([speed], three symbols, high first) and a fourth symbol: four times the
     * [priority] plus the [side] the view is on ({@link #LAYER_ALONG_Z}, {@link #LAYER_LOOKS_NEGATIVE}); then the two
     * rectangles, each edge two symbols (high, low) and each rectangle two 2×2 blocks (left and top, then right and
     * bottom), include from x = 6 and exclude from x = 10; then [y] (two symbols, top row), and below it the turn hint
     * ({@link #markLayerHint}). Eight 2×2 blocks in all; the shader never shows the top two rows. Not a copy: [hi] is
     * marked in place.
     */
    public byte[] markLayer(byte[] hi, int cols, int rows, int x, int y, int speed, int side, int priority, int[] include, int[] exclude) {
        hi[0] = markerId[1];
        hi[1] = markerId[0];
        hi[128] = markerId[3];
        hi[129] = markerId[3];
        hi[2] = symbolId[Math.min(SYMBOLS - 1, cols)];
        hi[3] = symbolId[Math.min(SYMBOLS - 1, rows)];
        edge(hi, 130, x);
        edge(hi, 14, y);
        int s = Math.max(0, Math.min(SYMBOLS * SYMBOLS * SYMBOLS - 1, speed));
        hi[4] = symbolId[s / (SYMBOLS * SYMBOLS)];
        hi[5] = symbolId[s / SYMBOLS % SYMBOLS];
        hi[132] = symbolId[s % SYMBOLS];
        hi[133] = symbolId[Math.max(0, Math.min(LAYER_PRIORITIES - 1, priority)) * 4 + (side & 3)];
        rect(hi, 6, include);
        rect(hi, 10, exclude);
        return hi;
    }

    /**
     * Puts a layer's turn hint in [hi] (a map {@link #markLayer} marked): {@code Shaders.turnQuarter}, 0 for none, as the
     * symbol under its [y]. Not a copy: [hi] is marked in place.
     */
    public byte[] markLayerHint(byte[] hi, int quarter) {
        hi[142] = symbolId[quarter];
        return hi;
    }

    private void rect(byte[] hi, int x, int[] r) {
        edge(hi, x, r[0]);
        edge(hi, 128 + x, r[1]);
        edge(hi, x + 2, r[2]);
        edge(hi, 128 + x + 2, r[3]);
    }

    /** Canvas pixel [v] as two symbols at hi[at] and the next: high, then low. */
    private void edge(byte[] hi, int at, int v) {
        int e = Math.max(0, Math.min(MAX_EDGE, v));
        hi[at] = symbolId[e / SYMBOLS];
        hi[at + 1] = symbolId[e % SYMBOLS];
    }

    /** As {@link #encodeHi}, in plain colours, one pixel at a time: a transparent pixel is the transparent colour. */
    public void encodePlain(byte[] ycc, int stride, int ox, int oy, byte[] out, boolean clear) {
        for (int y = 0; y < 128; y++) {
            int i = ((oy + y) * stride + ox) * 3;
            for (int x = 0; x < 128; x++) {
                if (clear && isClear(ycc, i)) {
                    out[y * 128 + x] = CLEAR_ID;
                    i += 3;
                    continue;
                }
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
