package ai.resourcepack.screens.api;

import ai.resourcepack.screens.render.Palette;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.IntStream;

/**
 * A picture [cols] × [rows] maps big (128 pixels a map), and what it looks like as map colour bytes.
 * Draw into it from any one thread at a time; what it publishes is read by the engine's sender thread.
 *
 * <p>Kept as interleaved YCbCr, the form the hi-colour encoding stores, so only the 128×128 tiles a
 * drawing actually changed are re-encoded, and only those are re-sent: a static page costs nothing
 * once it has arrived, and hovering a button re-sends a few tiles. Published tile arrays are never
 * mutated, so the sender compares them by identity.
 *
 * <p>Make one with {@link ScreenEngine#canvas(int, int)}. One canvas may be shown on any number of
 * screens and views at once.
 */
public final class Canvas {
    /** Shared by every canvas; leaves a core for the game. */
    private static final ForkJoinPool ENCODERS = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    public static final int TILE = 128 * 128;

    private final Palette palette;
    private final int cols, rows, width, height;
    private final byte[] ycc;
    private final AtomicReferenceArray<byte[]> hi;
    /** HI_OWN tiles with the HI array each was made from, so a stale one is never handed out. */
    private final AtomicReferenceArray<Own> hiOwn;
    private final AtomicReferenceArray<byte[]> plain;
    private volatile long version;

    public Canvas(Palette palette, int cols, int rows) {
        if (cols < 1 || rows < 1 || cols * rows > 1024) throw new IllegalArgumentException("A canvas is 1 to 1024 maps");
        this.palette = palette;
        this.cols = cols;
        this.rows = rows;
        this.width = cols * 128;
        this.height = rows * 128;
        this.ycc = new byte[width * height * 3];
        int tiles = cols * rows;
        hi = new AtomicReferenceArray<>(tiles);
        hiOwn = new AtomicReferenceArray<>(tiles);
        plain = new AtomicReferenceArray<>(tiles);
        for (int i = 0; i < ycc.length; i += 3) {
            ycc[i] = 0;
            ycc[i + 1] = (byte) 128;
            ycc[i + 2] = (byte) 128;
        }
        List<Integer> all = new ArrayList<>(tiles);
        for (int t = 0; t < tiles; t++) all.add(t);
        encode(all);
    }

    public int cols() { return cols; }
    public int rows() { return rows; }
    /** In pixels: cols × 128. */
    public int width() { return width; }
    /** In pixels: rows × 128. */
    public int height() { return height; }
    public int tiles() { return cols * rows; }
    /** Goes up whenever a drawing changes a pixel. */
    public long version() { return version; }

    /**
     * Draw [image] with its top-left at (x, y). Alpha is ignored: pixels replace what was there, so
     * flatten a translucent picture onto a background first.
     */
    public void draw(BufferedImage image, int x, int y) {
        int w = image.getWidth(), h = image.getHeight();
        int[] argb;
        if (image.getType() == BufferedImage.TYPE_INT_RGB || image.getType() == BufferedImage.TYPE_INT_ARGB) {
            argb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            if (argb.length != w * h) argb = image.getRGB(0, 0, w, h, null, 0, w);
        } else {
            argb = image.getRGB(0, 0, w, h, null, 0, w);
        }
        draw(argb, w, h, x, y);
    }

    /** Draw a [w] × [h] block of RGB pixels (row-major; the top byte is ignored) with its top-left at (x, y). */
    public void draw(int[] rgb, int w, int h, int x, int y) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(width, x + w), y1 = Math.min(height, y + h);
        if (x0 >= x1 || y0 >= y1) return;
        int cw = x1 - x0;
        byte[] row = new byte[cw * 3];
        boolean[] dirty = new boolean[cols * rows];
        boolean any = false;
        for (int py = y0; py < y1; py++) {
            int s = (py - y) * w + (x0 - x);
            for (int i = 0; i < cw; i++) Palette.rgbToYcc(rgb[s + i], row, i * 3);
            int d = (py * width + x0) * 3;
            if (Arrays.equals(row, 0, row.length, ycc, d, d + row.length)) continue;
            // Only the 128-pixel spans that differ mark their tiles dirty.
            int tileRow = (py / 128) * cols;
            int px = x0;
            while (px < x1) {
                int end = Math.min(x1, (px / 128 + 1) * 128);
                int a = (px - x0) * 3, b = (end - x0) * 3;
                if (!Arrays.equals(row, a, b, ycc, d + a, d + b)) {
                    dirty[tileRow + px / 128] = true;
                    any = true;
                }
                px = end;
            }
            System.arraycopy(row, 0, ycc, d, row.length);
        }
        if (!any) return;
        List<Integer> changed = new ArrayList<>();
        for (int t = 0; t < dirty.length; t++) if (dirty[t]) changed.add(t);
        encode(changed);
        version++;
    }

    /** The picture shrunk to [w] × [h] RGB pixels, each the average of the area it covers. */
    public int[] thumbnail(int w, int h) {
        int[] out = new int[w * h];
        for (int oy = 0; oy < h; oy++) {
            int y0 = oy * height / h, y1 = Math.max(y0 + 1, (oy + 1) * height / h);
            for (int ox = 0; ox < w; ox++) {
                int x0 = ox * width / w, x1 = Math.max(x0 + 1, (ox + 1) * width / w);
                long sy = 0, sb = 0, sr = 0;
                for (int py = y0; py < y1; py++) {
                    int i = (py * width + x0) * 3;
                    for (int px = x0; px < x1; px++, i += 3) {
                        sy += ycc[i] & 255;
                        sb += ycc[i + 1] & 255;
                        sr += ycc[i + 2] & 255;
                    }
                }
                long n = (long) (y1 - y0) * (x1 - x0);
                out[oy * w + ox] = Palette.yccToRgb((int) (sy / n), (int) (sb / n), (int) (sr / n));
            }
        }
        return out;
    }

    /** One tile's map colour bytes in [encoding]. The array is never changed after it is returned. */
    public byte[] tile(int index, Encoding encoding) {
        return switch (encoding) {
            case HI -> hi.get(index);
            case PLAIN -> plain.get(index);
            case HI_OWN -> {
                // Derived lazily from the HI tile and kept until that changes: only views need it.
                byte[] base = hi.get(index);
                Own own = hiOwn.get(index);
                if (own == null || own.base != base) {
                    own = new Own(base, palette.markOwn(base, cols, rows));
                    hiOwn.set(index, own);
                }
                yield own.tile;
            }
        };
    }

    private record Own(byte[] base, byte[] tile) {}

    /** Tiles are independent, so a full-screen change is encoded on several cores at once. */
    private void encode(List<Integer> list) {
        if (list.isEmpty()) return;
        if (list.size() == 1) {
            encode(list.get(0));
            return;
        }
        try {
            ENCODERS.submit(() -> IntStream.range(0, list.size()).parallel().forEach(i -> encode(list.get(i)))).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private void encode(int t) {
        int ox = (t % cols) * 128, oy = (t / cols) * 128;
        // Fresh arrays each time: the sender may still hold the old ones.
        byte[] h = new byte[TILE];
        byte[] p = new byte[TILE];
        palette.encodeHi(ycc, width, ox, oy, h);
        palette.encodePlain(ycc, width, ox, oy, p);
        hi.set(t, h);
        plain.set(t, p);
    }

    /** How a tile is sent: which colours mean what to the player's client. */
    public enum Encoding {
        /** For players with the engine's resource pack. */
        HI,
        /** HI, marked as the viewer's own fullscreen view: their client draws it in front of the world. */
        HI_OWN,
        /** Plain map colours, for players without the pack. */
        PLAIN
    }
}
