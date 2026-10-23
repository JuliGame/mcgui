package ai.resourcepack.arcade.scene;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;

/**
 * Picture work that Java2D does slowly. A scene that draws its world small and stretches it to the canvas with
 * {@code drawImage} and bilinear interpolation pays about 34 ms a frame for the stretch alone on a 16x9 canvas
 * (Java2D's general transform path), more than a 30 fps frame has; {@link #smooth} does the same in about 3 ms.
 */
public final class Pixels {
    private Pixels() {}

    /** The stretched picture {@link #drawSmooth} copies from, one per thread and size. */
    private static final ThreadLocal<BufferedImage> STRETCHED = new ThreadLocal<>();

    /**
     * {@code g.drawImage(from, x, y, width, height, null)} with bilinear interpolation, in a tenth of the time: for
     * a {@code TYPE_INT_RGB} picture drawn small and stretched up to the frame.
     */
    public static void drawSmooth(Graphics2D g, BufferedImage from, int x, int y, int width, int height) {
        BufferedImage to = STRETCHED.get();
        if (to == null || to.getWidth() != width || to.getHeight() != height) {
            to = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            STRETCHED.set(to);
        }
        smooth(from, to);
        g.drawImage(to, x, y, null);
    }

    /**
     * Stretches [from] over the whole of [to] with bilinear interpolation, as {@code drawImage} with
     * {@code VALUE_INTERPOLATION_BILINEAR} would (pixel centres to pixel centres, edges held). Both must be
     * {@code TYPE_INT_RGB} images (not sub-images); then draw [to] at full size, which is a plain copy.
     */
    public static void smooth(BufferedImage from, BufferedImage to) {
        int sw = from.getWidth(), sh = from.getHeight(), dw = to.getWidth(), dh = to.getHeight();
        int[] src = pixels(from), dst = pixels(to);
        // For each column of [to]: the source column on its left, and how far towards the next (0 to 256).
        int[] x0 = new int[dw], fx = new int[dw];
        for (int x = 0; x < dw; x++) {
            double s = Math.max(0, (x + 0.5) * sw / dw - 0.5);
            int i = Math.min((int) s, sw - 1);
            x0[x] = i;
            fx[x] = i >= sw - 1 ? 0 : (int) Math.round((s - i) * 256);
        }
        // Two source rows stretched across, kept while the rows of [to] fall between the same two.
        int[] upper = new int[dw], lower = new int[dw];
        int upperRow = -1, lowerRow = -1;
        for (int y = 0; y < dh; y++) {
            double s = Math.max(0, (y + 0.5) * sh / dh - 0.5);
            int r = Math.min((int) s, sh - 1), r1 = Math.min(r + 1, sh - 1);
            int fy = r >= sh - 1 ? 0 : (int) Math.round((s - r) * 256);
            if (upperRow != r) {
                if (lowerRow == r) {
                    int[] t = upper;
                    upper = lower;
                    lower = t;
                    upperRow = r;
                    lowerRow = -1;
                } else {
                    across(src, r * sw, sw, x0, fx, upper);
                    upperRow = r;
                }
            }
            int out = y * dw;
            if (fy == 0) {
                System.arraycopy(upper, 0, dst, out, dw);
                continue;
            }
            if (lowerRow != r1) {
                across(src, r1 * sw, sw, x0, fx, lower);
                lowerRow = r1;
            }
            int gy = 256 - fy;
            for (int x = 0; x < dw; x++) dst[out + x] = mix(upper[x], lower[x], gy, fy);
        }
    }

    /** Source row [row] (its first pixel at [row]) stretched to [into]'s width. */
    private static void across(int[] src, int row, int sw, int[] x0, int[] fx, int[] into) {
        int last = row + sw - 1;
        for (int x = 0; x < into.length; x++) {
            int i = row + x0[x], f = fx[x];
            into[x] = f == 0 ? src[i] : mix(src[i], src[Math.min(i + 1, last)], 256 - f, f);
        }
    }

    /** [a] × [wa] + [b] × [wb], wa + wb = 256, channel by channel; red and blue together, then green. */
    private static int mix(int a, int b, int wa, int wb) {
        int rb = ((a & 0xFF00FF) * wa + (b & 0xFF00FF) * wb + 0x800080) >>> 8 & 0xFF00FF;
        int g = ((a & 0xFF00) * wa + (b & 0xFF00) * wb + 0x8000) >>> 8 & 0xFF00;
        return 0xFF000000 | rb | g;
    }

    private static int[] pixels(BufferedImage image) {
        if (image.getType() != BufferedImage.TYPE_INT_RGB || !(image.getRaster().getSampleModel() instanceof SinglePixelPackedSampleModel sm)
                || sm.getScanlineStride() != image.getWidth() || image.getRaster().getSampleModelTranslateX() != 0
                || image.getRaster().getSampleModelTranslateY() != 0) {
            throw new IllegalArgumentException("Pixels.smooth takes whole TYPE_INT_RGB images");
        }
        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }
}
