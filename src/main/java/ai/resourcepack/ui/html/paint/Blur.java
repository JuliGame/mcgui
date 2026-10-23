package ai.resourcepack.ui.html.paint;

import ai.resourcepack.ui.html.RenderBudget;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * A Gaussian blur, approximated by three box blurs, on an ARGB image (premultiplied-safe for one colour). Each box runs
 * along the rows, then down the columns with a running sum per column, so both passes read and write memory in order
 * (the old way wrote each pass transposed, a cache miss per pixel on a big picture). Every output is round(sum / box),
 * exactly, whichever way it is worked out.
 */
final class Blur {
    private Blur() {}

    /**
     * Wider blurs are this wide: one this wide already spreads every pixel over any picture, and the
     * box sums stay within an int ({@code 255 * box} for a box about four sigmas across).
     */
    private static final double MAX_SIGMA = 100_000;
    /**
     * Boxes up to this wide sum two channels in one int (red and blue, alpha and green: 16 bits each holds 255 × 257)
     * and divide by looking the sum up ({@link #quotients}); wider ones, rare, work one channel at a time.
     */
    private static final int PACKED = 257;
    private static final int LANES = 0x00FF00FF;
    private static final java.util.concurrent.atomic.AtomicReferenceArray<byte[]> QUOTIENTS = new java.util.concurrent.atomic.AtomicReferenceArray<>(PACKED + 1);

    static void blur(BufferedImage img, double sigma, RenderBudget budget) {
        if (!(sigma >= 0.5)) return;
        sigma = Math.min(sigma, MAX_SIGMA);
        int w = img.getWidth(), h = img.getHeight();
        // The image's array may be longer than its pixels (a PixelPool image): only w x h of it are blurred.
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int[] sizes = boxes(sigma);
        if (sizes[2] > PACKED) {
            // Each channel on its own: the same sums, rounded the same way.
            int[] plane = PixelPool.ints(w * h), out = PixelPool.ints(w * h);
            try {
                for (int shift = 0; shift < 32; shift += 8) {
                    for (int i = 0; i < w * h; i++) plane[i] = px[i] >>> shift & 255;
                    channel(plane, w, h, sizes, budget);
                    if (shift == 0) System.arraycopy(plane, 0, out, 0, w * h);
                    else for (int i = 0; i < w * h; i++) out[i] |= plane[i] << shift;
                }
                System.arraycopy(out, 0, px, 0, w * h);
            } finally {
                PixelPool.give(plane);
                PixelPool.give(out);
            }
            return;
        }
        int[] tmp = PixelPool.ints(w * h), rb = PixelPool.ints(w), ag = PixelPool.ints(w);
        try {
            for (int s : sizes) {
                int r = (s - 1) / 2;
                rows(px, tmp, w, h, r, budget);
                columns(tmp, px, w, h, r, rb, ag, budget);
            }
        } finally {
            PixelPool.give(tmp);
            PixelPool.give(rb);
            PixelPool.give(ag);
        }
    }

    /**
     * Only the alpha of [img] blurred by [sigma], every pixel then [rgb] at that alpha, premultiplied (an
     * {@code TYPE_INT_ARGB_PRE} image): a shadow, one colour wherever it is, costs a quarter of a whole blur. Within a
     * level of blurring each premultiplied channel.
     */
    static void shadow(BufferedImage img, double sigma, int rgb, RenderBudget budget) {
        int w = img.getWidth(), h = img.getHeight(), n = w * h;
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int[] a = PixelPool.ints(n);
        try {
            for (int i = 0; i < n; i++) a[i] = px[i] >>> 24;
            if (sigma >= 0.5) channel(a, w, h, boxes(Math.min(sigma, MAX_SIGMA)), budget);
            int[] colour = new int[256];
            int cr = rgb >> 16 & 255, cg = rgb >> 8 & 255, cb = rgb & 255;
            for (int v = 0; v < 256; v++) colour[v] = v << 24 | (cr * v + 127) / 255 << 16 | (cg * v + 127) / 255 << 8 | (cb * v + 127) / 255;
            for (int i = 0; i < n; i++) px[i] = colour[a[i]];
        } finally {
            PixelPool.give(a);
        }
    }

    /** One channel ([v], values 0 to 255, [w] x [h]) blurred by boxes of [sizes], in place. */
    private static void channel(int[] v, int w, int h, int[] sizes, RenderBudget budget) {
        int[] tmp = PixelPool.ints(w * h), sums = PixelPool.ints(w);
        try {
            for (int s : sizes) {
                int r = (s - 1) / 2;
                long m = divisor(s);
                int beyond = Math.max(0, r - (w - 1));
                for (int y = 0; y < h; y++) {
                    budget.check();
                    int row = y * w;
                    int sum = r * v[row] + beyond * v[row + w - 1];
                    for (int i = 0, n = Math.min(r, w - 1); i <= n; i++) sum += v[row + i];
                    for (int x = 0; x < w; x++) {
                        tmp[row + x] = (int) ((2L * sum + s) * m >>> 40);
                        sum += v[row + Math.min(w - 1, x + r + 1)] - v[row + Math.max(0, x - r)];
                    }
                }
                beyond = Math.max(0, r - (h - 1));
                int last = (h - 1) * w;
                for (int x = 0; x < w; x++) sums[x] = r * tmp[x] + beyond * tmp[last + x];
                for (int j = 0, n = Math.min(r, h - 1); j <= n; j++) for (int x = 0; x < w; x++) sums[x] += tmp[j * w + x];
                for (int y = 0; y < h; y++) {
                    budget.check();
                    int row = y * w, out = Math.max(0, y - r) * w, in = Math.min(h - 1, y + r + 1) * w;
                    for (int x = 0; x < w; x++) v[row + x] = (int) ((2L * sums[x] + s) * m >>> 40);
                    for (int x = 0; x < w; x++) sums[x] += tmp[in + x] - tmp[out + x];
                }
            }
        } finally {
            PixelPool.give(tmp);
            PixelPool.give(sums);
        }
    }

    /**
     * For a box [d] wide: round(sum / d) is {@code (2 sum + d) * m >>> 40}, a multiply instead of a division, exact since
     * sums are at most 255 d and an odd d never leaves a half to round.
     */
    private static long divisor(int d) {
        return ((1L << 40) + 2L * d - 1) / (2L * d);
    }

    /** round(s / d) for every sum s of a box [d] wide (odd, at most {@link #PACKED}): looked up, two channels a pixel. */
    private static byte[] quotients(int d) {
        byte[] q = QUOTIENTS.get(d);
        if (q == null) {
            q = new byte[255 * d + 1];
            long m = divisor(d);
            for (int s = 0; s < q.length; s++) q[s] = (byte) ((2L * s + d) * m >>> 40);
            // Threads racing here make equal tables.
            QUOTIENTS.set(d, q);
        }
        return q;
    }

    /** The pixel whose channels are the sums [rb] (red, blue) and [ag] (alpha, green), each divided by the box ([q]). */
    private static int divide(int rb, int ag, byte[] q) {
        return (q[ag >>> 16] & 255) << 24 | (q[rb >>> 16] & 255) << 16 | (q[ag & 0xFFFF] & 255) << 8 | (q[rb & 0xFFFF] & 255);
    }

    /**
     * A box of radius [r] along each row of [src] into [dst], the edge pixels repeating past the ends. The sums of two
     * channels share an int: added and taken away whole, each lane stays the sum of its own channel.
     */
    private static void rows(int[] src, int[] dst, int w, int h, int r, RenderBudget budget) {
        byte[] q = quotients(2 * r + 1);
        int beyond = Math.max(0, r - (w - 1));
        for (int y = 0; y < h; y++) {
            budget.check();
            int row = y * w, first = src[row], last = src[row + w - 1];
            int rb = r * (first & LANES) + beyond * (last & LANES), ag = r * (first >>> 8 & LANES) + beyond * (last >>> 8 & LANES);
            for (int i = 0, n = Math.min(r, w - 1); i <= n; i++) {
                int p = src[row + i];
                rb += p & LANES;
                ag += p >>> 8 & LANES;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = divide(rb, ag, q);
                int out = src[row + Math.max(0, x - r)], in = src[row + Math.min(w - 1, x + r + 1)];
                rb += (in & LANES) - (out & LANES);
                ag += (in >>> 8 & LANES) - (out >>> 8 & LANES);
            }
        }
    }

    /** A box of radius [r] down each column of [src] into [dst], as {@link #rows}, with a running sum per column ([rb], [ag]: [w] each). */
    private static void columns(int[] src, int[] dst, int w, int h, int r, int[] rb, int[] ag, RenderBudget budget) {
        byte[] q = quotients(2 * r + 1);
        int beyond = Math.max(0, r - (h - 1)), last = (h - 1) * w;
        for (int x = 0; x < w; x++) {
            int f = src[x], l = src[last + x];
            rb[x] = r * (f & LANES) + beyond * (l & LANES);
            ag[x] = r * (f >>> 8 & LANES) + beyond * (l >>> 8 & LANES);
        }
        for (int j = 0, n = Math.min(r, h - 1); j <= n; j++) {
            int row = j * w;
            for (int x = 0; x < w; x++) {
                int p = src[row + x];
                rb[x] += p & LANES;
                ag[x] += p >>> 8 & LANES;
            }
        }
        for (int y = 0; y < h; y++) {
            budget.check();
            int row = y * w, out = Math.max(0, y - r) * w, in = Math.min(h - 1, y + r + 1) * w;
            for (int x = 0; x < w; x++) dst[row + x] = divide(rb[x], ag[x], q);
            for (int x = 0; x < w; x++) {
                int pi = src[in + x], po = src[out + x];
                rb[x] += (pi & LANES) - (po & LANES);
                ag[x] += (pi >>> 8 & LANES) - (po >>> 8 & LANES);
            }
        }
    }

    /** How far (px) a blur of [sigma] reads round each pixel it makes: its three boxes' radii. 0 for none. */
    static int support(double sigma) {
        if (!(sigma >= 0.5)) return 0;
        int sum = 0;
        for (int s : boxes(Math.min(sigma, MAX_SIGMA))) sum += (s - 1) / 2;
        return sum;
    }

    /**
     * How much smaller to work for a blur of [sigma] pixels. A wide blur leaves nothing finer than a
     * few pixels, so it is computed on a picture shrunk to keep sigma about 3 and scaled back up:
     * the same look at a fraction of the cost.
     */
    static double shrink(double sigma) {
        return sigma > 6 ? Math.min(16, sigma / 3) : 1;
    }

    /**
     * The whole-pixel factor a backdrop's blur of [sigma] pixels is worked out smaller by (1: not at all). Whole, so that
     * pictures painted apart (a band, a repaint of a rectangle, the whole thing) can share one sampling grid.
     */
    static int step(double sigma) {
        return sigma > 6 ? (int) Math.min(16, Math.floor(sigma / 3)) : 1;
    }

    /**
     * [src], whose width and height are multiples of [k] (from {@link #step}), blurred by [sigma]: [src] itself, blurred
     * in place, if [k] is 1, else a copy [k] times smaller, blurred, to draw at [src]'s size, from this thread's
     * {@link PixelPool} (the caller hands it back). Each pixel of the small
     * picture is the one at its own multiple of [k] in [src], so a caller that starts [src] at a multiple of [k] of
     * a bigger picture gets what any other start gives.
     */
    static BufferedImage blurredCopy(BufferedImage src, double sigma, int k, RenderBudget budget) {
        if (k <= 1) {
            blur(src, sigma, budget);
            return src;
        }
        int w = src.getWidth() / k, h = src.getHeight() / k;
        // Every pixel of it is drawn below.
        BufferedImage small = PixelPool.uncleared(w, h, src.getType());
        java.awt.Graphics2D g = small.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.setComposite(java.awt.AlphaComposite.Src);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        blur(small, sigma / k, budget);
        return small;
    }

    /** Three box sizes whose combined blur matches a Gaussian of [sigma]. */
    private static int[] boxes(double sigma) {
        double ideal = Math.sqrt(12 * sigma * sigma / 3 + 1);
        int wl = (int) Math.floor(ideal);
        if (wl % 2 == 0) wl--;
        int wu = wl + 2;
        double mIdeal = (12 * sigma * sigma - 3 * wl * wl - 4 * 3 * wl - 3 * 3) / (-4.0 * wl - 4);
        long m = Math.round(mIdeal);
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) out[i] = i < m ? wl : wu;
        return out;
    }
}
