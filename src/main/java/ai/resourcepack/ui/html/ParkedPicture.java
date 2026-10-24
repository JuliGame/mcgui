package ai.resourcepack.ui.html;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The picture of a view nobody has drawn over for a while, put away: its pixels in blocks of 128 × 128, each block
 * shared with every parked picture that holds the same pixels there. Players' views of one page are alike but for
 * a few blocks (their own values, what they hover), so idle views of it hold little more than where they differ,
 * instead of a whole picture each (9.4 MB at 2048 × 1152). {@link #unpark} puts the pixels back into a picture to
 * paint over, exactly as they were.
 *
 * <p>Only a premultiplied picture backed by exactly its own width × height ints (a {@link
 * ai.resourcepack.ui.html.paint.PixelPool#picture}, or a scroll cache's view) is parked: {@link #parks}.
 */
public final class ParkedPicture {
    /** Pixels a block is wide and high. */
    static final int BLOCK = 128;

    private final int width, height;
    /** Row by row of blocks; an edge block is as wide and high as the picture leaves it. */
    private final int[][] blocks;

    private ParkedPicture(int width, int height, int[][] blocks) {
        this.width = width;
        this.height = height;
        this.blocks = blocks;
    }

    /** Whether [img] can be parked: premultiplied, its own ints and nothing else (not a part of a bigger picture). */
    public static boolean parks(BufferedImage img) {
        return img != null && img.getType() == BufferedImage.TYPE_INT_ARGB_PRE && img.getRaster().getDataBuffer() instanceof DataBufferInt db
                && db.getOffset() == 0 && db.getNumBanks() == 1 && db.getData().length == img.getWidth() * img.getHeight()
                && img.getRaster().getSampleModelTranslateX() == 0 && img.getRaster().getSampleModelTranslateY() == 0;
    }

    /** [img]'s pixels (see {@link #parks}), each block shared with any parked block of the same pixels. [img] is only read. */
    public static ParkedPicture of(BufferedImage img) {
        if (!parks(img)) throw new IllegalArgumentException("Not a picture that parks");
        int w = img.getWidth(), h = img.getHeight();
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int cols = (w + BLOCK - 1) / BLOCK, rows = (h + BLOCK - 1) / BLOCK;
        int[][] blocks = new int[cols * rows][];
        for (int by = 0; by < rows; by++) {
            for (int bx = 0; bx < cols; bx++) {
                blocks[by * cols + bx] = SHARED.block(px, w, bx * BLOCK, by * BLOCK, Math.min(BLOCK, w - bx * BLOCK), Math.min(BLOCK, h - by * BLOCK));
            }
        }
        return new ParkedPicture(w, h, blocks);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Writes the pixels into [into] (a picture that {@link #parks}, of this size), every one, and returns it. */
    public BufferedImage unpark(BufferedImage into) {
        if (!parks(into) || into.getWidth() != width || into.getHeight() != height) throw new IllegalArgumentException("Not a picture of this size");
        int[] px = ((DataBufferInt) into.getRaster().getDataBuffer()).getData();
        int cols = (width + BLOCK - 1) / BLOCK;
        for (int i = 0; i < blocks.length; i++) {
            int x = (i % cols) * BLOCK, y = (i / cols) * BLOCK, bw = Math.min(BLOCK, width - x), bh = Math.min(BLOCK, height - y);
            int[] b = blocks[i];
            for (int r = 0; r < bh; r++) System.arraycopy(b, r * bw, px, (y + r) * width + x, bw);
        }
        return into;
    }

    /** How many of its blocks are the very blocks [other] holds at the same place (tests). */
    public int sameBlocks(ParkedPicture other) {
        int n = 0;
        for (int i = 0; i < Math.min(blocks.length, other.blocks.length); i++) if (blocks[i] == other.blocks[i]) n++;
        return n;
    }

    private static final Blocks SHARED = new Blocks();

    /**
     * Every parked block, by its pixels, held weakly: a block lives as long as a parked picture holds it. Only parking
     * looks blocks up, so one lock for all.
     */
    private static final class Blocks {
        private final Map<Integer, List<Ref>> byHash = new HashMap<>();
        private final ReferenceQueue<int[]> gone = new ReferenceQueue<>();

        private static final class Ref extends WeakReference<int[]> {
            final int hash;

            Ref(int[] b, int hash, ReferenceQueue<int[]> q) {
                super(b, q);
                this.hash = hash;
            }
        }

        /** The block of [bw] × [bh] pixels at (x, y) of [px] ([stride] wide): one parked already with these pixels, or a copy. */
        synchronized int[] block(int[] px, int stride, int x, int y, int bw, int bh) {
            purge();
            int hash = hash(px, stride, x, y, bw, bh);
            List<Ref> refs = byHash.get(hash);
            if (refs != null) {
                for (Ref r : refs) {
                    int[] b = r.get();
                    if (b != null && b.length == bw * bh && same(b, px, stride, x, y, bw, bh)) return b;
                }
            }
            int[] b = new int[bw * bh];
            for (int r = 0; r < bh; r++) System.arraycopy(px, (y + r) * stride + x, b, r * bw, bw);
            byHash.computeIfAbsent(hash, k -> new ArrayList<>(1)).add(new Ref(b, hash, gone));
            return b;
        }

        private void purge() {
            for (java.lang.ref.Reference<? extends int[]> r; (r = gone.poll()) != null; ) {
                Ref ref = (Ref) r;
                List<Ref> refs = byHash.get(ref.hash);
                if (refs != null && refs.remove(ref) && refs.isEmpty()) byHash.remove(ref.hash);
            }
        }

        /** Four lanes, so the multiplies do not wait on each other. */
        private static int hash(int[] px, int stride, int x, int y, int bw, int bh) {
            int a = 1, b = 2, c = 3, d = 4;
            for (int r = 0; r < bh; r++) {
                int o = (y + r) * stride + x, i = 0;
                for (; i + 3 < bw; i += 4) {
                    a = a * 31 + px[o + i];
                    b = b * 31 + px[o + i + 1];
                    c = c * 31 + px[o + i + 2];
                    d = d * 31 + px[o + i + 3];
                }
                for (; i < bw; i++) a = a * 31 + px[o + i];
            }
            return ((a * 31 + b) * 31 + c) * 31 + d + bw * 7919 + bh;
        }

        private static boolean same(int[] block, int[] px, int stride, int x, int y, int bw, int bh) {
            for (int r = 0; r < bh; r++) {
                int o = (y + r) * stride + x;
                if (!Arrays.equals(block, r * bw, r * bw + bw, px, o, o + bw)) return false;
            }
            return true;
        }
    }
}
