package ai.resourcepack.ui.framework.render;

/**
 * The mark of a page made on mcgui.dev: a four-byte tag spelled in one-level Cb nudges on flat 2×2 blocks of the
 * picture, faint enough not to be seen and read back from a screenshot by mcgui.dev's checker, which sums thousands of
 * them per bit. Which blocks carry and which bit each holds come from a hash of the block's place on the canvas, so a
 * reader needs nothing but the picture. Drawn only for a page that says it came from mcgui.dev, and only with
 * {@code builder-mark} on in config.yml.
 *
 * <p>The hash, the tag and the density are a contract with mcgui.dev's reader ({@code export-mark.ts} there): change
 * one and no page marked before reads.
 */
public final class BuilderMark {
    private BuilderMark() {}

    /** One block in this many is a carrier; half of those, by their bit, are nudged. */
    static final int DENSITY = 8;
    private static final int[] TAG_BITS = bits("MCG1");

    private static int[] bits(String tag) {
        int[] out = new int[tag.length() * 8];
        for (int i = 0; i < out.length; i++) out[i] = tag.charAt(i / 8) >> (7 - i % 8) & 1;
        return out;
    }

    static int hash(int bx, int by) {
        int h = bx * 374761393 + by * 668265263 + 0x6d63;
        h = (h ^ (h >>> 13)) * 1274126177;
        h = (h ^ (h >>> 15)) * 0x2c1b3c6d;
        return h ^ (h >>> 16);
    }

    /** Whether the block at (bx, by) is lit: a carrier whose bit, against the hash's own, says nudge it. */
    static boolean lit(int bx, int by) {
        int h = hash(bx, by);
        if (Integer.remainderUnsigned(h >>> 9, DENSITY) != 0) return false;
        return (TAG_BITS[(h >>> 1) % 32] ^ (h & 1)) == 1;
    }

    /**
     * Marks the lit blocks wholly inside [x0, x1) × [y0, y1) of [ycc] (a [width] × [height] canvas, 3 bytes a pixel)
     * whose 3×3 blocks round them are one flat colour, raising their Cb one level, and flags each tile it changes in
     * [dirty] ([cols] tiles a row). The blocks round them may already carry a nudge of their own.
     */
    public static void apply(Palette palette, byte[] ycc, int width, int height, int cols, int x0, int y0, int x1, int y1, boolean[] dirty) {
        int bw = width / 2, bh = height / 2;
        int bx0 = Math.max(1, (x0 + 1) / 2), by0 = Math.max(1, (y0 + 1) / 2);
        int bx1 = Math.min(bw - 2, x1 / 2 - 1), by1 = Math.min(bh - 2, y1 / 2 - 1);
        for (int by = by0; by <= by1; by++) {
            for (int bx = bx0; bx <= bx1; bx++) {
                // The top-left block of every map is its marker.
                if ((bx & 63) == 0 && (by & 63) == 0) continue;
                if (!lit(bx, by)) continue;
                int c = (2 * by * width + 2 * bx) * 3;
                int y = ycc[c] & 255, cb = ycc[c + 1] & 255, cr = ycc[c + 2] & 255;
                if (cb == 0 && cr == 0) continue; // transparent
                int level = palette.chromaLevelOf(cb * 4);
                if (level >= 63 || !flat(palette, ycc, width, bx, by, y, cb, cr, level)) continue;
                int k = 1;
                while (k <= 16 && palette.chromaLevelOf(cb * 4 + k) == level) k++;
                if (k > 16 || palette.chromaLevelOf(cb * 4 + k) != level + 1 || cb + (k + 3) / 4 > 255) continue;
                for (int i = 0; i < 4; i++) {
                    int p = c + ((i >> 1) * width + (i & 1)) * 3;
                    ycc[p + 1] = (byte) (cb + k / 4 + (i < k % 4 ? 1 : 0));
                }
                dirty[(2 * by / 128) * cols + 2 * bx / 128] = true;
            }
        }
    }

    /** Whether the 3×3 blocks round (bx, by) are all luma [y] and Cr [cr], the middle one Cb [cb], the rest Cb [level] or one above. */
    private static boolean flat(Palette palette, byte[] ycc, int width, int bx, int by, int y, int cb, int cr, int level) {
        for (int py = 2 * by - 2; py < 2 * by + 4; py++) {
            int row = py * width * 3;
            for (int px = 2 * bx - 2; px < 2 * bx + 4; px++) {
                int i = row + px * 3;
                if ((ycc[i] & 255) != y || (ycc[i + 2] & 255) != cr) return false;
                int b = ycc[i + 1] & 255;
                if (b == cb) continue;
                boolean middle = px >= 2 * bx && px < 2 * bx + 2 && py >= 2 * by && py < 2 * by + 2;
                if (middle) return false;
                int l = palette.chromaLevelOf(b * 4);
                if (l != level && l != level + 1) return false;
            }
        }
        return true;
    }
}
