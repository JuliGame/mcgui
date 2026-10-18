package ai.resourcepack.ui.html.paint;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/** A Gaussian blur, approximated by three box blurs, on an ARGB image (premultiplied-safe for one colour). */
final class Blur {
    private Blur() {}

    /**
     * Wider blurs are this wide: one this wide already spreads every pixel over any picture, and the
     * box sums stay within an int ({@code 255 * box} for a box about four sigmas across).
     */
    private static final double MAX_SIGMA = 100_000;

    static void blur(BufferedImage img, double sigma) {
        if (!(sigma >= 0.5)) return;
        sigma = Math.min(sigma, MAX_SIGMA);
        int w = img.getWidth(), h = img.getHeight();
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int[] tmp = new int[px.length];
        int[] sizes = boxes(sigma);
        for (int s : sizes) {
            int r = (s - 1) / 2;
            pass(px, tmp, w, h, r);
            pass(tmp, px, h, w, r);
        }
    }

    /**
     * How much smaller to work for a blur of [sigma] pixels. A wide blur leaves nothing finer than a
     * few pixels, so it is computed on a picture shrunk to keep sigma about 3 and scaled back up:
     * the same look at a fraction of the cost.
     */
    static double shrink(double sigma) {
        return sigma > 6 ? Math.min(16, sigma / 3) : 1;
    }

    /** [src] blurred by [sigma]: [src] itself (blurred in place) or a smaller blurred copy to draw at [src]'s size. */
    static BufferedImage blurredCopy(BufferedImage src, double sigma) {
        double k = shrink(sigma);
        if (k <= 1) {
            blur(src, sigma);
            return src;
        }
        int w = Math.max(1, (int) Math.ceil(src.getWidth() / k)), h = Math.max(1, (int) Math.ceil(src.getHeight() / k));
        BufferedImage small = new BufferedImage(w, h, src.getType());
        java.awt.Graphics2D g = small.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.setComposite(java.awt.AlphaComposite.Src);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        blur(small, sigma / k);
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

    /** One horizontal box blur of radius [r] over every channel, writing transposed (so two calls do both axes). */
    private static void pass(int[] src, int[] dst, int w, int h, int r) {
        int d = r + r + 1;
        // Each output is round(sum / d), worked out as (2 sum + d) / 2d with a multiply instead of four
        // double divisions: exact, since sums are at most 255 d and an odd d never leaves a half to round.
        long m = ((1L << 40) + 2L * d - 1) / (2L * d);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            // The window round x = 0: r copies of the first pixel (the edge repeats), the row up to r,
            // and copies of the last pixel for whatever of the window lies past the row's end.
            int first = src[row], last = src[row + w - 1], beyond = Math.max(0, r - (w - 1));
            int a = r * (first >>> 24) + beyond * (last >>> 24), rr = r * (first >> 16 & 255) + beyond * (last >> 16 & 255);
            int g = r * (first >> 8 & 255) + beyond * (last >> 8 & 255), b = r * (first & 255) + beyond * (last & 255);
            for (int i = 0, n = Math.min(r, w - 1); i <= n; i++) {
                int p = src[row + i];
                a += p >>> 24;
                rr += p >> 16 & 255;
                g += p >> 8 & 255;
                b += p & 255;
            }
            for (int x = 0; x < w; x++) {
                dst[x * h + y] = (int) ((2L * a + d) * m >>> 40) << 24 | (int) ((2L * rr + d) * m >>> 40) << 16
                        | (int) ((2L * g + d) * m >>> 40) << 8 | (int) ((2L * b + d) * m >>> 40);
                int out = src[row + Math.max(0, x - r)];
                int in = src[row + Math.min(w - 1, x + r + 1)];
                a += (in >>> 24) - (out >>> 24);
                rr += (in >> 16 & 255) - (out >> 16 & 255);
                g += (in >> 8 & 255) - (out >> 8 & 255);
                b += (in & 255) - (out & 255);
            }
        }
    }
}
