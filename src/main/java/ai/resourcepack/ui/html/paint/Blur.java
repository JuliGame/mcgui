package ai.resourcepack.ui.html.paint;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/** A Gaussian blur, approximated by three box blurs, on an ARGB image (premultiplied-safe for one colour). */
final class Blur {
    private Blur() {}

    static void blur(BufferedImage img, double sigma) {
        if (sigma < 0.5) return;
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
        double scale = 1.0 / (r + r + 1);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            long a = 0, rr = 0, g = 0, b = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[row + Math.min(w - 1, Math.max(0, i))];
                a += p >>> 24;
                rr += p >> 16 & 255;
                g += p >> 8 & 255;
                b += p & 255;
            }
            for (int x = 0; x < w; x++) {
                dst[x * h + y] = ((int) (a * scale + 0.5) << 24) | ((int) (rr * scale + 0.5) << 16) | ((int) (g * scale + 0.5) << 8) | (int) (b * scale + 0.5);
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
