package ai.resourcepack.uiframework.api;

import ai.resourcepack.uiframework.render.Palette;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * A picture [cols] × [rows] maps big (128 pixels a map), and what it looks like as map colour bytes.
 * Draw into it from any one thread at a time; what it publishes is read by the engine's sender thread.
 *
 * <p>Kept as interleaved YCbCr, the form the hi-colour encoding stores, so only the 128×128 tiles a
 * drawing actually changed are re-encoded, and only those are re-sent: a static page costs nothing
 * once it has arrived, and hovering a button re-sends a few tiles. Published tile arrays are never
 * mutated, so the sender compares them by identity. A drawing is published whole: {@link #snapshot()}
 * never sees half of one.
 *
 * <p>{@link #scroll} moves the picture by whole tiles and keeps each moved tile's arrays, so the sender
 * can tell that a viewer already has them and point their frames at the maps they are in instead of
 * sending them again.
 *
 * <p>Make one with {@link UIFramework#canvas(int, int)}. One canvas may be shown on any number of
 * screens and views at once.
 */
public final class Canvas {
    /** Shared by every canvas; leaves a core for the game. */
    private static final ForkJoinPool ENCODERS = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    public static final int TILE = 128 * 128;

    private final Palette palette;
    private final int cols, rows, width, height;
    private final byte[] ycc;
    /** The published tiles, never changed once published: a drawing publishes a new array. */
    private volatile Tile[] tiles;
    /** Odd while a drawing changes [ycc], so a PLAIN tile made from it meanwhile is not kept. */
    private volatile long changing;
    private volatile long version;
    /** Told whenever new tiles are published, so they go out at once (the engine's sender). */
    private final Runnable published;

    public Canvas(Palette palette, int cols, int rows) {
        this(palette, cols, rows, () -> {});
    }

    /** As {@link #Canvas(Palette, int, int)}, running [published] after every drawing that changed a tile. */
    public Canvas(Palette palette, int cols, int rows, Runnable published) {
        this.published = published;
        if (cols < 1 || rows < 1 || cols * rows > 1024) throw new IllegalArgumentException("A canvas is 1 to 1024 maps");
        this.palette = palette;
        this.cols = cols;
        this.rows = rows;
        this.width = cols * 128;
        this.height = rows * 128;
        this.ycc = new byte[width * height * 3];
        int count = cols * rows;
        for (int i = 0; i < ycc.length; i += 3) {
            ycc[i] = 0;
            ycc[i + 1] = (byte) 128;
            ycc[i + 2] = (byte) 128;
        }
        Tile[] all = new Tile[count];
        List<Integer> every = new ArrayList<>(count);
        for (int t = 0; t < count; t++) every.add(t);
        encode(every, all);
        tiles = all;
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
     * flatten a translucent picture onto a background first (a premultiplied image's colours are already
     * flattened onto black). An int RGB or ARGB image, or a sub-image of one, is read in place.
     */
    public void draw(BufferedImage image, int x, int y) {
        scroll(0, image, x, y);
    }

    /**
     * Move the picture up by [rows] whole map rows (down if negative), then draw [image] at (x, y), and
     * publish both as one change. Tiles that only moved keep their arrays, so a viewer who has them is
     * re-pointed rather than sent them again; the rows moved in from the other edge hold what scrolled
     * out until [image] covers them (draw the whole new view, or at least the rows that came in).
     */
    public void scroll(int rows, BufferedImage image, int x, int y) {
        int w = image.getWidth(), h = image.getHeight();
        int type = image.getType();
        WritableRaster r = image.getRaster();
        if ((type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_ARGB_PRE)
                && r.getSampleModel() instanceof SinglePixelPackedSampleModel sm && r.getDataBuffer() instanceof DataBufferInt db) {
            // A sub-image shares its parent's array: start where it does, and step by the parent's row length.
            int stride = sm.getScanlineStride();
            int offset = db.getOffset() - r.getSampleModelTranslateY() * stride - r.getSampleModelTranslateX();
            apply(rows, db.getData(), offset, stride, w, h, x, y);
        } else {
            apply(rows, image.getRGB(0, 0, w, h, null, 0, w), 0, w, w, h, x, y);
        }
    }

    /** Draw a [w] × [h] block of RGB pixels (row-major; the top byte is ignored) with its top-left at (x, y). */
    public void draw(int[] rgb, int w, int h, int x, int y) {
        apply(0, rgb, 0, w, w, h, x, y);
    }

    /** Rows per piece of work when converting a big drawing on several cores. */
    private static final int BAND = 32;

    /** Move by [shift] map rows, then draw: [rgb] holds the drawing's pixel (i, j) at [offset] + j × [stride] + i. */
    private void apply(int shift, int[] rgb, int offset, int stride, int w, int h, int x, int y) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(width, x + w), y1 = Math.min(height, y + h);
        boolean moves = shift != 0 && Math.abs(shift) < rows;
        boolean draws = x0 < x1 && y0 < y1;
        if (!moves && !draws) return;
        Tile[] next = tiles.clone();
        changing++;
        try {
            if (moves) rotate(shift, next);
            List<Integer> changed = new ArrayList<>();
            if (draws) {
                boolean[] dirty = new boolean[cols * rows];
                int bands = (y1 - y0 + BAND - 1) / BAND;
                // Rows are independent: a big drawing (a whole new page) is converted on several cores at once.
                if (bands > 1 && (long) (x1 - x0) * (y1 - y0) >= 128 * 128) {
                    run(() -> IntStream.range(0, bands).parallel().forEach(b ->
                            convert(rgb, offset, stride, x, y, x0, x1, y0 + b * BAND, Math.min(y1, y0 + (b + 1) * BAND), dirty)));
                } else {
                    convert(rgb, offset, stride, x, y, x0, x1, y0, y1, dirty);
                }
                for (int t = 0; t < dirty.length; t++) if (dirty[t]) changed.add(t);
            }
            if (!moves && changed.isEmpty()) return;
            encode(changed, next);
        } finally {
            changing++;
        }
        tiles = next;
        version++;
        published.run();
    }

    /** The picture's rows, and [next]'s tiles with them, moved up by [shift] map rows, round to the other edge. */
    private void rotate(int shift, Tile[] next) {
        int k = Math.floorMod(shift, rows);
        int bytes = k * 128 * width * 3;
        byte[] top = Arrays.copyOf(ycc, bytes);
        System.arraycopy(ycc, bytes, ycc, 0, ycc.length - bytes);
        System.arraycopy(top, 0, ycc, ycc.length - bytes, bytes);
        Tile[] was = next.clone();
        int n = was.length, by = k * cols;
        for (int t = 0; t < n; t++) next[t] = was[(t + by) % n];
    }

    /** Rows [from, to) of the canvas: convert, compare with what is there, store, and mark changed tiles. */
    private void convert(int[] rgb, int offset, int stride, int x, int y, int x0, int x1, int from, int to, boolean[] dirty) {
        int cw = x1 - x0;
        byte[] row = new byte[cw * 3];
        for (int py = from; py < to; py++) {
            int s = offset + (py - y) * stride + (x0 - x);
            for (int i = 0; i < cw; i++) Palette.rgbToYcc(rgb[s + i], row, i * 3);
            int d = (py * width + x0) * 3;
            if (Arrays.equals(row, 0, row.length, ycc, d, d + row.length)) continue;
            // Only the 128-pixel spans that differ mark their tiles dirty.
            int tileRow = (py / 128) * cols;
            int px = x0;
            while (px < x1) {
                int end = Math.min(x1, (px / 128 + 1) * 128);
                int a = (px - x0) * 3, b = (end - x0) * 3;
                if (!Arrays.equals(row, a, b, ycc, d + a, d + b)) dirty[tileRow + px / 128] = true;
                px = end;
            }
            System.arraycopy(row, 0, ycc, d, row.length);
        }
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
        return snapshot().tile(index, encoding);
    }

    /** The tiles as published now: one whole drawing, whatever is drawn after. */
    public Snapshot snapshot() {
        return new Snapshot(tiles);
    }

    /** The tiles of one published drawing. */
    public final class Snapshot {
        private final Tile[] at;

        private Snapshot(Tile[] at) {
            this.at = at;
        }

        /** Tile [index]'s map colour bytes in [encoding]. The array is never changed after it is returned. */
        public byte[] tile(int index, Encoding encoding) {
            Tile t = at[index];
            return switch (encoding) {
                case HI -> t.hi;
                case HI_OWN -> t.own(palette, cols, rows);
                case PLAIN -> t.plain(Canvas.this, index, at);
            };
        }
    }

    /**
     * One encoded tile, with what is made from it on demand. Moves with the picture when it scrolls, so
     * what was made from it moves too and stays the same array.
     */
    private static final class Tile {
        final byte[] hi;
        private byte[] own, plain;

        Tile(byte[] hi) {
            this.hi = hi;
        }

        /** Made once, the first time a view needs it; one array whichever thread asks first. */
        synchronized byte[] own(Palette palette, int cols, int rows) {
            if (own == null) own = palette.markOwn(hi, cols, rows);
            return own;
        }

        /** Made from the picture the first time somebody without the pack looks, while this tile is at [index] in [at]. */
        byte[] plain(Canvas canvas, int index, Tile[] at) {
            synchronized (this) {
                if (plain != null) return plain;
            }
            long before = canvas.changing;
            byte[] out = new byte[TILE];
            canvas.palette.encodePlain(canvas.ycc, canvas.width, (index % canvas.cols) * 128, (index / canvas.cols) * 128, out);
            // Kept only if the picture did not change under it and this tile is still where it was read from;
            // otherwise it may be of other pixels, and is made again next time.
            if ((before & 1) != 0 || canvas.changing != before || canvas.tiles != at) return out;
            synchronized (this) {
                if (plain == null) plain = out;
                return plain;
            }
        }
    }

    /** Tiles are independent, so a full-screen change is encoded on several cores at once. */
    private void encode(List<Integer> list, Tile[] into) {
        if (list.isEmpty()) return;
        if (list.size() == 1) encode(list.get(0), into);
        else run(() -> IntStream.range(0, list.size()).parallel().forEach(i -> encode(list.get(i), into)));
    }

    /** Runs [work] (which splits itself up) on the shared encoder pool and waits for it. */
    private static void run(Runnable work) {
        try {
            ENCODERS.submit(work).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private void encode(int t, Tile[] into) {
        // A fresh array each time: the sender may still hold the old one. PLAIN is made from it when asked for.
        byte[] h = new byte[TILE];
        palette.encodeHi(ycc, width, (t % cols) * 128, (t / cols) * 128, h);
        into[t] = new Tile(h);
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
