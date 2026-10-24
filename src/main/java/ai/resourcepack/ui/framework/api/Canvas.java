package ai.resourcepack.ui.framework.api;

import ai.resourcepack.ui.framework.render.BuilderMark;
import ai.resourcepack.ui.framework.render.Palette;

import java.awt.Rectangle;
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
     * The picture's colours a map at a time (128 × 128 pixels, 3 bytes a pixel, row by row), by tile index; null for a
     * map all black (never drawn into). A map's colours are shared with every canvas made by {@link #copy()} or shown by
     * {@link #show(Canvas)} until one of them draws into that map: a drawing copies only the maps it changes, on the
     * drawing thread ({@link #own(int)}), so canvases that show one picture and differ in a few maps (a hover, a
     * player's name) hold only those few of their own. This array is the canvas's own; a shared map in it is never
     * changed.
     */
    private volatile byte[][] ycc;
    /** The maps of [ycc] this canvas copied for itself (null where shared or black): only those are drawn into in place. */
    private volatile AtomicReferenceArray<byte[]> mine;
    /** The published tiles, never changed once published: a drawing publishes a new array. */
    private volatile Tile[] tiles;
    /** Odd while a drawing changes [ycc], so a PLAIN tile made from it meanwhile is not kept. */
    private volatile long changing;
    /**
     * HI_TILED tiles by place, with the HI array each was made from, for a Tile that keeps its own HI_TILED for
     * another place ({@link Tile#tiled}): a HI_TILED tile carries its own column and row, a Tile moves when the
     * canvas scrolls, and one Tile is at every place of a blank canvas.
     */
    private final AtomicReferenceArray<Own> hiTiled;
    private volatile long version;
    /** Drawings' pixels less than half opaque are transparent (see {@link #transparency(boolean)}). */
    private volatile boolean transparent;
    /** Later drawings carry mcgui.dev's mark (see {@link #builderMark(boolean)}). */
    private volatile boolean builderMark;
    /** Tiles are shared with every canvas that has a tile of the same colours (see {@link #shareAlike(boolean)}). */
    private volatile boolean alike;
    /**
     * What a view lays over the world where the picture is transparent (ARGB; see {@link #tint(int)}): as published,
     * always written just before [tiles], so whoever reads [tiles] first sees the tint that came with them.
     */
    private volatile int tint;
    /** The tint the next drawing publishes; only the drawing thread uses it. */
    private int nextTint;
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
        ycc = new byte[count][];
        mine = new AtomicReferenceArray<>(count);
        // Black, with no colours of its own until something is drawn (a 16x9 canvas's are 7 MB, and this runs on the
        // server thread when a viewer reaches a wall). Every tile of a blank canvas encodes to the same bytes, and tiles
        // are never changed once published, so they all share one Tile, as do all blank canvases of this size: what is
        // made from it alone (HI_OWN, PLAIN) depends only on the size. HI_TILED carries the tile's place (see Tile.tiled).
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
            return new Tile(h, palette.encodeHi(BLACK, 128, 0, 0, h, false));
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
        this.ycc = from.ycc.clone();
        this.mine = new AtomicReferenceArray<>(cols * rows);
        this.hiTiled = new AtomicReferenceArray<>(cols * rows);
        this.tiles = from.tiles;
        this.version = from.version;
        this.transparent = from.transparent;
        this.builderMark = from.builderMark;
        this.alike = from.alike;
        this.tint = from.tint;
        this.nextTint = from.tint;
    }

    /**
     * A new canvas showing this one's picture: the same published tiles (the same arrays, so nothing is encoded,
     * and a viewer who already has them is sent nothing), the same colours, telling the same sender. Nothing is
     * copied: the colours are shared until either canvas draws, which copies the maps it draws into first. Call it
     * from the thread that draws into this one, or on a canvas nothing draws into any more.
     */
    public Canvas copy() {
        mine = new AtomicReferenceArray<>(cols * rows);
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
        from.mine = new AtomicReferenceArray<>(cols * rows);
        changing++;
        try {
            ycc = from.ycc.clone();
            mine = new AtomicReferenceArray<>(cols * rows);
        } finally {
            changing++;
        }
        transparent = from.transparent;
        tint = nextTint = from.tint;
        tiles = from.tiles;
        version++;
        published.run();
    }

    /**
     * Show [from]'s picture with [patch] drawn over it at (x, y), as one published change: what {@link #show(Canvas)}
     * and then {@link #draw} would show, without [from]'s pixels under [patch] ever being published here (another
     * player's own values, say). Null [patch]: [from] as it is.
     */
    public void show(Canvas from, BufferedImage patch, int x, int y) {
        if (from.cols != cols || from.rows != rows) throw new IllegalArgumentException("Not the same size: " + from.cols + "x" + from.rows);
        if (patch == null) {
            show(from);
            return;
        }
        from.mine = new AtomicReferenceArray<>(cols * rows);
        changing++;
        try {
            ycc = from.ycc.clone();
            mine = new AtomicReferenceArray<>(cols * rows);
        } finally {
            changing++;
        }
        transparent = from.transparent;
        int was = nextTint;
        tint = from.tint;
        nextTint = was;
        draw(patch, x, y, from.tiles);
    }

    /**
     * Whether later drawings may be transparent. With it on, a pixel of an image with alpha that is less than
     * half opaque is transparent, and one at least half opaque is drawn as its colour, as without (so a
     * premultiplied image's edges are flattened onto black). Through a transparent pixel the world behind
     * shows: behind a wall's invisible frames on a wall, and on a view the world behind the player's window,
     * under the {@link #tint(int)}. Hi-colour maps are transparent in 2×2 blocks (a block with two transparent
     * pixels or more), plain ones pixel by pixel. Off by default; images without alpha are always opaque.
     */
    public void transparency(boolean on) {
        transparent = on;
    }

    /**
     * Whether later drawings carry mcgui.dev's faint mark ({@link ai.resourcepack.ui.framework.render.BuilderMark}):
     * on for a page made there, while the server leaves {@code builder-mark} on.
     */
    public void builderMark(boolean on) {
        builderMark = on;
    }

    /**
     * Whether later drawings share each tile they change with every canvas (of this palette and size) that has a tile of
     * the same colours: one tile, its colours and what is made from it (the same arrays, so they are compressed once,
     * and a viewer who has them is sent nothing) for, say, every player's own picture of one page, alike but for their
     * names. A tile's colours are then never drawn into in place, so each changed tile costs a copy of its colours on
     * the next drawing: for pictures that change in a few places now and then, not a game's every frame. Off by default.
     */
    public void shareAlike(boolean on) {
        alike = on;
    }

    public boolean transparency() {
        return transparent;
    }

    /**
     * What a view of this canvas lays over the world where its picture is transparent: an ARGB colour (alpha
     * 0, the default, for none: the world as it is). Seen only by players with the resource pack, at 240
     * levels a channel; walls are never tinted. It is published with the next drawing (or scroll), together
     * with its tiles, so a viewer never gets a picture with another picture's tint. From the thread that draws.
     */
    public void tint(int argb) {
        nextTint = argb;
    }

    /** The tint the next drawing publishes. */
    public int tint() {
        return nextTint;
    }

    /**
     * Map [t]'s colours, this canvas's own to draw into: a copy of the shared ones (or black), made once, whichever of
     * a drawing's threads asks first. On a drawing thread.
     */
    private byte[] own(int t) {
        AtomicReferenceArray<byte[]> m = mine;
        byte[] b = m.get(t);
        if (b != null) return b;
        synchronized (m) {
            b = m.get(t);
            if (b == null) {
                byte[][] all = ycc;
                b = all[t] == null ? BLACK.clone() : all[t].clone();
                all[t] = b;
                m.set(t, b);
            }
            return b;
        }
    }

    /** Map [t]'s colours to read: black for one never drawn into. */
    private static byte[] colours(byte[][] ycc, int t) {
        byte[] b = ycc[t];
        return b == null ? BLACK : b;
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

    /** How many maps' colours this canvas holds alone, shared with no other canvas (tests). */
    int ownMaps() {
        AtomicReferenceArray<byte[]> m = mine;
        int n = 0;
        for (int t = 0; t < m.length(); t++) if (m.get(t) != null) n++;
        return n;
    }

    /**
     * Draw [image] with its top-left at (x, y). Pixels replace what was there, so flatten a translucent
     * picture onto a background first (a premultiplied image's colours are already flattened onto black);
     * alpha only counts with {@link #transparency(boolean)} on. An int RGB or ARGB image, or a sub-image of
     * one, is read in place.
     */
    public void draw(BufferedImage image, int x, int y) {
        paint(0, image, x, y, null, tiles);
    }

    /**
     * Draw only [areas] of [image], which has its top-left at (x, y), as one change: the parts of a frame that
     * changed, say. [areas] are in canvas pixels. Each pixel handed to a canvas is converted to map colours
     * whether or not it changed, so a picture that is redrawn whole but changes in places (a game's frame) costs
     * far less handed over this way. Nothing of [image] outside [areas] is read; an empty list changes nothing.
     */
    public void draw(BufferedImage image, int x, int y, List<Rectangle> areas) {
        paint(0, image, x, y, areas, tiles);
    }

    /**
     * Move the picture up by [rows] whole map rows (down if negative), then draw [image] at (x, y), and
     * publish both as one change. Tiles that only moved keep their arrays, so a viewer who has them is
     * re-pointed rather than sent them again; the rows moved in from the other edge hold what scrolled
     * out until [image] covers them (draw the whole new view, or at least the rows that came in).
     */
    public void scroll(int rows, BufferedImage image, int x, int y) {
        paint(rows, image, x, y, null, tiles);
    }

    /** [image] drawn at (x, y) over the tiles [start] (another canvas's, see {@link #show(Canvas, BufferedImage, int, int)}), published once. */
    private void draw(BufferedImage image, int x, int y, Tile[] start) {
        paint(0, image, x, y, null, start);
    }

    /** {@link #scroll}, drawing only [areas] (canvas pixels) of [image], or all of it for null, over the tiles [start]. */
    private void paint(int rows, BufferedImage image, int x, int y, List<Rectangle> areas, Tile[] start) {
        int w = image.getWidth(), h = image.getHeight();
        int type = image.getType();
        boolean alpha = transparent && image.getColorModel().hasAlpha();
        WritableRaster r = image.getRaster();
        if ((type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_ARGB_PRE)
                && r.getSampleModel() instanceof SinglePixelPackedSampleModel sm && r.getDataBuffer() instanceof DataBufferInt db) {
            // A sub-image shares its parent's array: start where it does, and step by the parent's row length.
            int stride = sm.getScanlineStride();
            int offset = db.getOffset() - r.getSampleModelTranslateY() * stride - r.getSampleModelTranslateX();
            apply(start, rows, db.getData(), offset, stride, w, h, x, y, alpha, areas);
        } else {
            apply(start, rows, image.getRGB(0, 0, w, h, null, 0, w), 0, w, w, h, x, y, alpha, areas);
        }
    }

    /** Draw a [w] × [h] block of RGB pixels (row-major; the top byte is ignored) with its top-left at (x, y). */
    public void draw(int[] rgb, int w, int h, int x, int y) {
        apply(tiles, 0, rgb, 0, w, w, h, x, y, false, null);
    }

    /**
     * Publish finished map colour bytes as tiles, as one change: index to bytes, sent as they are in {@link Encoding#HI}.
     * For a view's hover layers, whose tiles carry more than a picture ({@code Palette.markLayer}): a canvas used this
     * way is never drawn into, and only sent in HI. The arrays must never change once given.
     */
    public void put(Map<Integer, byte[]> given) {
        if (given.isEmpty()) return;
        Tile[] next = tiles.clone();
        for (Map.Entry<Integer, byte[]> e : given.entrySet()) next[e.getKey()] = new Tile(e.getValue(), Palette.FULL_BOUNDS);
        tiles = next;
        version++;
        published.run();
    }

    /** Rows per piece of work when converting a big drawing on several cores. */
    private static final int BAND = 32;

    /**
     * Move by [shift] map rows, then draw: [rgb] holds the drawing's pixel (i, j) at [offset] + j × [stride] + i,
     * and it covers the [w] × [h] canvas pixels from (x, y). Only [areas] of it (canvas pixels) are drawn, or all of
     * it for null. [alpha]: its top byte is alpha, and a pixel under half is transparent.
     */
    private void apply(Tile[] start, int shift, int[] rgb, int offset, int stride, int w, int h, int x, int y, boolean alpha, List<Rectangle> areas) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(width, x + w), y1 = Math.min(height, y + h);
        // What to convert, in canvas pixels, in bands of rows a core each: {left, top, right, bottom}.
        List<int[]> bands = new ArrayList<>();
        long pixels = 0;
        if (areas == null) {
            pixels += band(bands, x0, y0, x1, y1);
        } else {
            for (Rectangle a : areas) {
                pixels += band(bands, Math.max(x0, a.x), Math.max(y0, a.y), Math.min(x1, a.x + a.width), Math.min(y1, a.y + a.height));
            }
        }
        boolean moves = shift != 0 && Math.abs(shift) < rows;
        boolean draws = !bands.isEmpty();
        // Over another canvas's tiles: shown even if nothing is drawn.
        boolean shows = start != tiles;
        if (!moves && !draws && !shows) return;
        // Colours shared with another canvas (or none yet, all black) are copied map by map as they are drawn into
        // (own), on the drawing threads.
        Tile[] next = start.clone();
        changing++;
        try {
            if (moves) rotate(shift, next);
            List<Integer> changed = new ArrayList<>();
            if (draws) {
                boolean[] dirty = new boolean[cols * rows];
                // Rows are independent: a big drawing (a whole new page) is converted on several cores at once.
                if (bands.size() > 1 && pixels >= 128 * 128) {
                    run(() -> bands.parallelStream().forEach(b -> convert(rgb, offset, stride, x, y, b[0], b[2], b[1], b[3], dirty, alpha)));
                } else {
                    for (int[] b : bands) convert(rgb, offset, stride, x, y, b[0], b[2], b[1], b[3], dirty, alpha);
                }
                if (builderMark) {
                    BuilderMark.Tiles marked = new BuilderMark.Tiles() {
                        private final byte[][] all = ycc;

                        @Override public byte[] read(int t) { return colours(all, t); }
                        @Override public byte[] write(int t) { return own(t); }
                    };
                    if (areas == null) BuilderMark.apply(palette, marked, width, height, cols, x0, y0, x1, y1, dirty);
                    else for (Rectangle a : areas) {
                        BuilderMark.apply(palette, marked, width, height, cols, Math.max(x0, a.x), Math.max(y0, a.y),
                                Math.min(x1, a.x + a.width), Math.min(y1, a.y + a.height), dirty);
                    }
                }
                for (int t = 0; t < dirty.length; t++) if (dirty[t]) changed.add(t);
            }
            if (!moves && !shows && changed.isEmpty() && tint == nextTint) return;
            encode(changed, next);
        } finally {
            changing++;
        }
        tint = nextTint;
        tiles = next;
        version++;
        published.run();
    }

    /** Adds [x0, x1) × [y0, y1) to [bands], cut into {@link #BAND} rows each; its pixels (0 if it is empty). */
    private static long band(List<int[]> bands, int x0, int y0, int x1, int y1) {
        if (x0 >= x1 || y0 >= y1) return 0;
        for (int b = y0; b < y1; b += BAND) bands.add(new int[]{x0, b, x1, Math.min(y1, b + BAND)});
        return (long) (x1 - x0) * (y1 - y0);
    }

    /** The picture's map rows, and [next]'s tiles with them, moved up by [shift] map rows, round to the other edge. */
    private void rotate(int shift, Tile[] next) {
        int k = Math.floorMod(shift, rows);
        int n = next.length, by = k * cols;
        byte[][] ycc = this.ycc, was = ycc.clone();
        AtomicReferenceArray<byte[]> m = mine, moved = new AtomicReferenceArray<>(n);
        Tile[] tilesWere = next.clone();
        for (int t = 0; t < n; t++) {
            int from = (t + by) % n;
            ycc[t] = was[from];
            moved.set(t, m.get(from));
            next[t] = tilesWere[from];
        }
        mine = moved;
    }

    /** One row of converted colours, per thread: a whole picture converts 36 bands of 32 rows on the {@link Cpu} pool's threads. */
    private static final ThreadLocal<byte[]> ROW = new ThreadLocal<>();

    /** Rows [from, to) of the canvas: convert, compare with what is there, store, and mark changed tiles. */
    private void convert(int[] rgb, int offset, int stride, int x, int y, int x0, int x1, int from, int to, boolean[] dirty, boolean alpha) {
        int cw = x1 - x0;
        byte[][] ycc = this.ycc;
        // Only this call uses it until it returns (nothing in here runs another task on this thread). It may be longer than a row.
        int n = cw * 3;
        byte[] row = ROW.get();
        if (row == null || row.length < n) ROW.set(row = new byte[Math.max(n, 2048 * 3)]);
        for (int py = from; py < to; py++) {
            int s = offset + (py - y) * stride + (x0 - x);
            // A row the same as the one above (a picture drawn small and scaled up, a flat background) is already in
            // [row]: converting it again was most of a scaled-up game's frame.
            if (py == from || !Arrays.equals(rgb, s, s + cw, rgb, s - stride, s - stride + cw)) {
                if (alpha) {
                    for (int i = 0; i < cw; i++) {
                        int p = rgb[s + i];
                        if (p >>> 24 < 128) row[i * 3] = row[i * 3 + 1] = row[i * 3 + 2] = 0; // Palette.isClear
                        else Palette.rgbToYcc(p, row, i * 3);
                    }
                } else {
                    // Runs of one colour are converted once.
                    int last = ~rgb[s];
                    for (int i = 0, o = 0; i < cw; i++, o += 3) {
                        int p = rgb[s + i];
                        if (p == last) {
                            row[o] = row[o - 3];
                            row[o + 1] = row[o - 2];
                            row[o + 2] = row[o - 1];
                        } else {
                            Palette.rgbToYcc(p, row, o);
                            last = p;
                        }
                    }
                }
            }
            // Each 128-pixel span is compared with its map's colours, and only a span that differs is stored (its map
            // copied first if it is shared) and marks its tile dirty.
            int tileRow = (py / 128) * cols, inTile = (py & 127) * 128;
            int px = x0;
            while (px < x1) {
                int end = Math.min(x1, (px / 128 + 1) * 128);
                int a = (px - x0) * 3, b = (end - x0) * 3;
                int t = tileRow + px / 128, d = (inTile + (px & 127)) * 3;
                if (!Arrays.equals(row, a, b, colours(ycc, t), d, d + b - a)) {
                    System.arraycopy(row, a, own(t), d, b - a);
                    dirty[t] = true;
                }
                px = end;
            }
        }
    }

    /** The picture shrunk to [w] × [h] RGB pixels, each the average of the area it covers (black where all of it is transparent). */
    public int[] thumbnail(int w, int h) {
        int[] out = new int[w * h];
        byte[][] ycc = this.ycc;
        for (int oy = 0; oy < h; oy++) {
            int y0 = oy * height / h, y1 = Math.max(y0 + 1, (oy + 1) * height / h);
            for (int ox = 0; ox < w; ox++) {
                int x0 = ox * width / w, x1 = Math.max(x0 + 1, (ox + 1) * width / w);
                long sy = 0, sb = 0, sr = 0, n = 0;
                for (int py = y0; py < y1; py++) {
                    int row = (py / 128) * cols, inTile = (py & 127) * 128;
                    for (int px = x0; px < x1; px++) {
                        byte[] c = colours(ycc, row + px / 128);
                        int i = (inTile + (px & 127)) * 3;
                        if (Palette.isClear(c, i)) continue;
                        sy += c[i] & 255;
                        sb += c[i + 1] & 255;
                        sr += c[i + 2] & 255;
                        n++;
                    }
                }
                out[oy * w + ox] = n == 0 ? 0 : Palette.yccToRgb((int) (sy / n), (int) (sb / n), (int) (sr / n));
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
        // [tiles] first: the tint written before them is at least as new.
        Tile[] at = tiles;
        return new Snapshot(at, tint);
    }

    /** The tiles of one published drawing. */
    public final class Snapshot {
        private final Tile[] at;
        private final int tint;

        private Snapshot(Tile[] at, int tint) {
            this.tint = tint;
            this.at = at;
        }

        /** Whether [other] is of the same drawing: no tile has been published between the two. */
        public boolean sameAs(Snapshot other) {
            return other != null && other.at == at;
        }

        /**
         * Whether tile [index] has anything opaque in it: an overlay sends only those (its panel), and nothing is drawn
         * where the rest would be. Every tile of a picture with nothing transparent.
         */
        public boolean opaque(int index) {
            return at[index].bounds != Palette.NO_BOUNDS;
        }

        /** Tile [index]'s map colour bytes in [encoding]. The array is never changed after it is returned. */
        public byte[] tile(int index, Encoding encoding) {
            Tile t = at[index];
            int tint = this.tint;
            return switch (encoding) {
                case HI -> t.hi;
                case HI_OWN -> t.own(palette, cols, rows, tint);
                case HI_TILED -> {
                    byte[] kept = t.tiled(palette, cols, rows, index, tint);
                    if (kept != null) yield kept;
                    Own own = hiTiled.get(index);
                    if (own == null || own.base != t.hi || own.tint != tint) {
                        own = new Own(t.hi, tint, palette.markTiled(t.hi, cols, rows, index % cols, index / cols, tint));
                        hiTiled.set(index, own);
                    }
                    yield own.tile;
                }
                case PLAIN -> t.plain(Canvas.this, index, at);
            };
        }
    }

    private record Own(byte[] base, int tint, byte[] tile) {}

    /**
     * One encoded tile, with what is made from it on demand. Moves with the picture when it scrolls, so
     * what was made from it moves too and stays the same array.
     */
    private static final class Tile {
        final byte[] hi;
        /** Where its opaque 2×2 blocks are ({@link Palette#encodeHi}'s bounds). */
        final int bounds;
        /** The colours it was encoded from, if it is shared by its colours ({@link #shareAlike}): never changed. Else null. */
        final byte[] ycc;
        private byte[] own, plain, tiled;
        private int ownTint, tiledAt = -1, tiledTint;

        Tile(byte[] hi, int bounds) {
            this(hi, bounds, null);
        }

        Tile(byte[] hi, int bounds, byte[] ycc) {
            this.hi = hi;
            this.bounds = bounds;
            this.ycc = ycc;
        }

        /** Made once for each tint, the first time a view needs it; one array whichever thread asks first. */
        synchronized byte[] own(Palette palette, int cols, int rows, int tint) {
            if (own == null || ownTint != tint) {
                own = palette.markOwn(hi, cols, rows, tint);
                ownTint = tint;
            }
            return own;
        }

        /**
         * HI_TILED at place [index] with [tint], kept on the tile for the first place and tint it is asked for: every canvas
         * that shows this tile there (a first picture many views took, a hover they share) then sends one array. Null at
         * any other (a blank canvas's one tile is at every place, and a tile moves when its canvas scrolls): the canvas
         * keeps those itself.
         */
        synchronized byte[] tiled(Palette palette, int cols, int rows, int index, int tint) {
            if (tiled == null) {
                tiled = palette.markTiled(hi, cols, rows, index % cols, index / cols, tint);
                tiledAt = index;
                tiledTint = tint;
            }
            return tiledAt == index && tiledTint == tint ? tiled : null;
        }

        /** Made from the picture the first time somebody without the pack looks, while this tile is at [index] in [at]. */
        byte[] plain(Canvas canvas, int index, Tile[] at) {
            synchronized (this) {
                if (plain != null) return plain;
            }
            byte[] out = new byte[TILE];
            if (ycc != null) {
                // Its own colours, which never change.
                canvas.palette.encodePlain(ycc, 128, 0, 0, out, canvas.transparent);
                synchronized (this) {
                    if (plain == null) plain = out;
                    return plain;
                }
            }
            long before = canvas.changing;
            canvas.palette.encodePlain(colours(canvas.ycc, index), 128, 0, 0, out, canvas.transparent);
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
        byte[] colours = colours(ycc, t);
        if (alike && colours != BLACK) {
            // The very tile another canvas (or this one before) made of the same colours: nothing encoded, and a viewer
            // who has it is sent nothing. Its colours are this canvas's from now on too, shared, so never changed in place.
            Tile same = Alike.find(palette, cols, rows, transparent, colours);
            if (same == null) {
                byte[] h = new byte[TILE];
                same = Alike.add(palette, cols, rows, transparent, new Tile(h, palette.encodeHi(colours, 128, 0, 0, h, transparent), colours));
            }
            ycc[t] = same.ycc;
            mine.set(t, null);
            into[t] = same;
            return;
        }
        // A fresh array each time: the sender may still hold the old one. PLAIN is made from it when asked for.
        byte[] h = new byte[TILE];
        int bounds = palette.encodeHi(colours, 128, 0, 0, h, transparent);
        into[t] = new Tile(h, bounds);
    }

    /**
     * Tiles of canvases that share alike ({@link #shareAlike}), by their colours, palette, size and transparency, each
     * held weakly: as long as a canvas shows it. Looked up from several encoding threads at once.
     */
    private static final class Alike {
        private static final java.util.concurrent.ConcurrentHashMap<Integer, List<Ref>> BY = new java.util.concurrent.ConcurrentHashMap<>();
        private static final java.lang.ref.ReferenceQueue<Tile> GONE = new java.lang.ref.ReferenceQueue<>();

        private static final class Ref extends java.lang.ref.WeakReference<Tile> {
            final int hash, cols, rows;
            final Palette palette;
            final boolean clear;

            Ref(Tile t, int hash, Palette palette, int cols, int rows, boolean clear) {
                super(t, GONE);
                this.hash = hash;
                this.palette = palette;
                this.cols = cols;
                this.rows = rows;
                this.clear = clear;
            }

            boolean of(Palette palette, int cols, int rows, boolean clear) {
                return this.palette == palette && this.cols == cols && this.rows == rows && this.clear == clear;
            }
        }

        static Tile find(Palette palette, int cols, int rows, boolean clear, byte[] colours) {
            purge();
            List<Ref> refs = BY.get(hash(colours, cols, rows, clear));
            if (refs == null) return null;
            synchronized (refs) {
                for (Ref r : refs) {
                    Tile t = r.get();
                    if (t != null && r.of(palette, cols, rows, clear) && Arrays.equals(t.ycc, colours)) return t;
                }
            }
            return null;
        }

        /** [t], unless one of the same colours was added meanwhile: then that one. */
        static Tile add(Palette palette, int cols, int rows, boolean clear, Tile t) {
            int h = hash(t.ycc, cols, rows, clear);
            List<Ref> refs = BY.computeIfAbsent(h, k -> new ArrayList<>(1));
            synchronized (refs) {
                for (Ref r : refs) {
                    Tile o = r.get();
                    if (o != null && r.of(palette, cols, rows, clear) && Arrays.equals(o.ycc, t.ycc)) return o;
                }
                refs.add(new Ref(t, h, palette, cols, rows, clear));
            }
            return t;
        }

        private static void purge() {
            for (java.lang.ref.Reference<? extends Tile> g; (g = GONE.poll()) != null; ) {
                Ref r = (Ref) g;
                BY.computeIfPresent(r.hash, (k, refs) -> {
                    synchronized (refs) {
                        refs.remove(r);
                        return refs.isEmpty() ? null : refs;
                    }
                });
            }
        }

        /** Four lanes, so the multiplies do not wait on each other. */
        private static int hash(byte[] b, int cols, int rows, boolean clear) {
            int a = 1, c = 2, d = 3, e = 4, i = 0;
            for (; i + 3 < b.length; i += 4) {
                a = a * 31 + b[i];
                c = c * 31 + b[i + 1];
                d = d * 31 + b[i + 2];
                e = e * 31 + b[i + 3];
            }
            for (; i < b.length; i++) a = a * 31 + b[i];
            return (((a * 31 + c) * 31 + d) * 31 + e) * 31 + (cols << 16 | rows) * 2 + (clear ? 1 : 0);
        }
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
