package ai.resourcepack.uiframework.api;

import ai.resourcepack.uiframework.render.Palette;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicReferenceArray;
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
    public static final int TILE = 128 * 128;

    private final Palette palette;
    private final int cols, rows, width, height;
    /**
     * The picture's colours, or null while it is all black (never drawn into). Shared with every canvas made by
     * {@link #copy()} or shown by {@link #show(Canvas)} until one of them draws: the first drawing into a canvas that
     * does not own its colours copies them first ({@link #detach()}), on the drawing thread. Never changed while shared.
     */
    private volatile byte[] ycc;
    /** Whether [ycc] is this canvas's alone, made by its own {@link #detach()}: only then is it drawn into in place. */
    private volatile boolean owned;
    /** The published tiles, never changed once published: a drawing publishes a new array. */
    private volatile Tile[] tiles;
    /** Odd while a drawing changes [ycc], so a PLAIN tile made from it meanwhile is not kept. */
    private volatile long changing;
    /**
     * HI_TILED tiles by place, with the HI array each was made from. Kept per place, not on the Tile: a
     * HI_TILED tile carries its own column and row, and a Tile moves when the canvas scrolls and is shared
     * by every place of a blank canvas.
     */
    private final AtomicReferenceArray<Own> hiTiled;
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
        int count = cols * rows;
        hiTiled = new AtomicReferenceArray<>(count);
        // Black, with no colours of its own until something is drawn (a 16x9 canvas's are 7 MB, and this runs on the
        // server thread when a viewer reaches a wall). Every tile of a blank canvas encodes to the same bytes, and tiles
        // are never changed once published, so they all share one Tile, as do all blank canvases of this size: what is
        // made from it alone (HI_OWN, PLAIN) depends only on the size. HI_TILED carries the tile's place, so it is never
        // kept on the Tile (see Snapshot.tile).
        Tile[] all = new Tile[count];
        Arrays.fill(all, blank(palette, cols, rows));
        tiles = all;
    }

    /** All black, 3 bytes a pixel, one map wide and high: what a blank tile is encoded from. */
    private static final byte[] BLACK = black(TILE * 3);
    /** The blank Tile of each palette and size. */
    private static final Map<Palette, Map<Integer, Tile>> BLANK = Collections.synchronizedMap(new WeakHashMap<>());

    private static Tile blank(Palette palette, int cols, int rows) {
        return BLANK.computeIfAbsent(palette, p -> new ConcurrentHashMap<>()).computeIfAbsent(cols << 16 | rows, k -> {
            byte[] h = new byte[TILE];
            palette.encodeHi(BLACK, 128, 0, 0, h);
            return new Tile(h);
        });
    }

    /** [n] bytes of black YCbCr: Y 0, Cb and Cr 128. Filled by doubling copies, not pixel by pixel. */
    private static byte[] black(int n) {
        byte[] b = new byte[n];
        b[1] = (byte) 128;
        b[2] = (byte) 128;
        for (int i = 3; i < n; i *= 2) System.arraycopy(b, 0, b, i, Math.min(i, n - i));
        return b;
    }

    /** A copy of [from] as it is now (see {@link #copy()}). */
    private Canvas(Canvas from) {
        this.published = from.published;
        this.palette = from.palette;
        this.cols = from.cols;
        this.rows = from.rows;
        this.width = from.width;
        this.height = from.height;
        this.ycc = from.ycc;
        this.hiTiled = new AtomicReferenceArray<>(cols * rows);
        this.tiles = from.tiles;
        this.version = from.version;
    }

    /**
     * A new canvas showing this one's picture: the same published tiles (the same arrays, so nothing is encoded,
     * and a viewer who already has them is sent nothing), the same colours, telling the same sender. Nothing is
     * copied: the colours are shared until either canvas draws, which copies them first. Call it from the thread
     * that draws into this one, or on a canvas nothing draws into any more.
     */
    public Canvas copy() {
        owned = false;
        return new Canvas(this);
    }

    /**
     * Show [from]'s picture, as one published change like a drawing: its tiles (the same arrays, nothing is
     * encoded or converted) and its colours, shared until either canvas draws (nothing is copied here, so this
     * is cheap enough for the server thread). [from] must be the same size, and nothing may draw into it
     * meanwhile (a {@link #copy()} kept aside, say). Draw into this canvas after it as after any drawing.
     */
    public void show(Canvas from) {
        if (from.cols != cols || from.rows != rows) throw new IllegalArgumentException("Not the same size: " + from.cols + "x" + from.rows);
        if (from == this) return;
        from.owned = false;
        changing++;
        try {
            owned = false;
            ycc = from.ycc;
        } finally {
            changing++;
        }
        tiles = from.tiles;
        version++;
        published.run();
    }

    /** Makes [ycc] this canvas's own before it is drawn into: a copy of the shared colours, or black. On the drawing thread. */
    private void detach() {
        if (owned) return;
        byte[] shared = ycc;
        ycc = shared == null ? black(width * height * 3) : shared.clone();
        owned = true;
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
        // Colours shared with another canvas (or none yet, all black) are copied before they are drawn into: here, on the drawing thread.
        detach();
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
        byte[] ycc = this.ycc;
        int bytes = k * 128 * width * 3;
        byte[] top = Arrays.copyOf(ycc, bytes);
        System.arraycopy(ycc, bytes, ycc, 0, ycc.length - bytes);
        System.arraycopy(top, 0, ycc, ycc.length - bytes, bytes);
        Tile[] was = next.clone();
        int n = was.length, by = k * cols;
        for (int t = 0; t < n; t++) next[t] = was[(t + by) % n];
    }

    /** One row of converted colours, per thread: a whole picture converts 36 bands of 32 rows on the {@link Cpu} pool's threads. */
    private static final ThreadLocal<byte[]> ROW = new ThreadLocal<>();

    /** Rows [from, to) of the canvas: convert, compare with what is there, store, and mark changed tiles. */
    private void convert(int[] rgb, int offset, int stride, int x, int y, int x0, int x1, int from, int to, boolean[] dirty) {
        int cw = x1 - x0;
        byte[] ycc = this.ycc;
        // Only this call uses it until it returns (nothing in here runs another task on this thread). It may be longer than a row.
        int n = cw * 3;
        byte[] row = ROW.get();
        if (row == null || row.length < n) ROW.set(row = new byte[Math.max(n, 2048 * 3)]);
        for (int py = from; py < to; py++) {
            int s = offset + (py - y) * stride + (x0 - x);
            for (int i = 0; i < cw; i++) Palette.rgbToYcc(rgb[s + i], row, i * 3);
            int d = (py * width + x0) * 3;
            if (Arrays.equals(row, 0, n, ycc, d, d + n)) continue;
            // Only the 128-pixel spans that differ mark their tiles dirty.
            int tileRow = (py / 128) * cols;
            int px = x0;
            while (px < x1) {
                int end = Math.min(x1, (px / 128 + 1) * 128);
                int a = (px - x0) * 3, b = (end - x0) * 3;
                if (!Arrays.equals(row, a, b, ycc, d + a, d + b)) dirty[tileRow + px / 128] = true;
                px = end;
            }
            System.arraycopy(row, 0, ycc, d, n);
        }
    }

    /** The picture shrunk to [w] × [h] RGB pixels, each the average of the area it covers. */
    public int[] thumbnail(int w, int h) {
        int[] out = new int[w * h];
        byte[] ycc = this.ycc;
        if (ycc == null) {
            Arrays.fill(out, Palette.yccToRgb(0, 128, 128));
            return out;
        }
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

        /** Whether [other] is of the same drawing: no tile has been published between the two. */
        public boolean sameAs(Snapshot other) {
            return other != null && other.at == at;
        }

        /** Tile [index]'s map colour bytes in [encoding]. The array is never changed after it is returned. */
        public byte[] tile(int index, Encoding encoding) {
            Tile t = at[index];
            return switch (encoding) {
                case HI -> t.hi;
                case HI_OWN -> t.own(palette, cols, rows);
                case HI_TILED -> {
                    Own own = hiTiled.get(index);
                    if (own == null || own.base != t.hi) {
                        own = new Own(t.hi, palette.markTiled(t.hi, cols, rows, index % cols, index / cols));
                        hiTiled.set(index, own);
                    }
                    yield own.tile;
                }
                case PLAIN -> t.plain(Canvas.this, index, at);
            };
        }
    }

    private record Own(byte[] base, byte[] tile) {}

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
            byte[] ycc = canvas.ycc;
            // No colours yet: the canvas is blank, and so is every tile of it.
            if (ycc == null) canvas.palette.encodePlain(BLACK, 128, 0, 0, out);
            else canvas.palette.encodePlain(ycc, canvas.width, (index % canvas.cols) * 128, (index / canvas.cols) * 128, out);
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

    /**
     * Runs [work] (which splits itself up) on {@link Cpu}'s pool and waits for it. Called from one of the pool's own
     * threads (a render job drawing its picture) it runs on that thread and splits into the pool, so it takes no
     * thread beyond the budget and cannot wait for a thread that is itself waiting.
     */
    private static void run(Runnable work) {
        ForkJoinPool pool = Cpu.pool();
        if (Thread.currentThread() instanceof ForkJoinWorkerThread w && w.getPool() == pool) {
            work.run();
            return;
        }
        try {
            pool.submit(work).get();
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
        /** HI_OWN, carrying each tile's place too, for a view whose pointer the viewer's client draws. */
        HI_TILED,
        /** Plain map colours, for players without the pack. */
        PLAIN
    }
}
