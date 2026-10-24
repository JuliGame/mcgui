package ai.resourcepack.ui.framework.world;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.api.HoverLayers;
import ai.resourcepack.ui.framework.pack.Shaders;
import ai.resourcepack.ui.framework.render.Palette;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * A view's {@link HoverLayers}: a row of [capacity] hidden maps (its own {@link #canvas}, sent in HI), each shown by a
 * frame of {@link #wall}, which lies in the camera's block as the view's own do. A layer takes a map for each tile it
 * touches, marked with that tile's place, the view's size, pointer speed and side and its rectangles
 * ({@link Palette#markLayer}), from which the pack's shader places it and decides, every frame, whether it shows. A map
 * no layer holds carries an empty rectangle, so it never shows.
 *
 * <p>Only a held map has a frame on the client ({@link #framed}, which the view brings into line once the maps have
 * reached it): every map frame the client has costs it a draw call each frame, about 9 µs, even when the shader puts
 * its quad where nothing is drawn.
 *
 * <p>A layer's maps are encoded once for every view on the server ({@link #prepare}, kept by their pixels in
 * {@link #KEPT}) and only marked per view: fifty players looking at one picture encode its layers once, and a layer
 * whose pixels come out the same over a new picture is not encoded again. Armed again where the client still has its
 * maps (a layer taken off as the picture changed, and armed once more over the next), it goes back into them, so the
 * client is sent only their marks.
 */
final class LayerSet implements HoverLayers {
    private static final int[] NOWHERE = {0, 0, 0, 0};

    final MapWall wall;
    final Canvas canvas;
    private final Palette palette;
    private final int cols, rows, speed, side;
    /**
     * By map: the key of the layer holding it (null for none), the canvas pixel its first picture row starts at (see
     * markLayer), the bytes it was last given, and the unmarked picture they were marked from (a {@link Prepared}'s).
     */
    private final Object[] owner;
    private final int[] atX, atY;
    private final byte[][] shown, source;
    private final Map<Object, int[]> held = new HashMap<>();
    private int used;
    /** Bumped whenever which maps are held changes. */
    private volatile int changes;

    /** For a view of [cols] × [rows] maps whose pointer moves [speed] pixels a degree, on [side] (see markLayer). */
    LayerSet(Palette palette, Canvas canvas, MapWall wall, int cols, int rows, double speed, int side) {
        this.palette = palette;
        this.canvas = canvas;
        this.wall = wall;
        this.cols = cols;
        this.rows = rows;
        this.speed = (int) Math.round(speed * Shaders.LAYER_SPEED_SCALE);
        this.side = side;
        int n = canvas.tiles();
        owner = new Object[n];
        atX = new int[n];
        atY = new int[n];
        shown = new byte[n][];
        source = new byte[n][];
        // Every map starts as a layer that never shows: a plain blank tile would be a screen, drawn where its frame lies.
        byte[] ycc = new byte[Canvas.TILE * 3];
        byte[] empty = new byte[Canvas.TILE];
        palette.encodeHi(ycc, 128, 0, 0, empty, true);
        palette.markLayer(empty, cols, rows, 0, 0, this.speed, side, 0, NOWHERE, NOWHERE);
        Map<Integer, byte[]> all = new HashMap<>();
        for (int i = 0; i < n; i++) {
            shown[i] = empty;
            all.put(i, empty);
        }
        canvas.put(all);
    }

    @Override public int capacity() { return owner.length; }

    @Override public synchronized int used() { return used; }

    @Override public synchronized boolean armed(Object key) { return held.containsKey(key); }

    /** Bumped whenever {@link #framed} changes. */
    int changes() { return changes; }

    /** Which maps a layer holds now: the ones whose frames the client should have. */
    synchronized boolean[] framed() {
        boolean[] out = new boolean[owner.length];
        for (int i = 0; i < out.length; i++) out[i] = owner[i] != null;
        return out;
    }

    /** One converted map's colours, per thread: 48 KB, used by one {@link #prepare} at a time. */
    private static final ThreadLocal<byte[]> YCC = ThreadLocal.withInitial(() -> new byte[Canvas.TILE * 3]);

    /** Picture rows a layer's map holds: its top two are its data's. */
    static final int MAP_ROWS = 126;

    /**
     * A layer worked out for views of [cols] × [rows] maps: where each of its maps goes (the canvas pixel of its first
     * picture row) and its picture, encoded and not marked. Shared by every view that arms it: never written to.
     */
    record Prepared(int cols, int rows, int[][] places, byte[][] tiles) implements Layer {
        @Override public int maps() { return tiles.length; }
    }

    /** What a layer's pixels are kept under in {@link #KEPT}: its place, the views' size, and two hashes of its pixels. */
    private record Pixels(Palette palette, int cols, int rows, int x, int y, int w, int h, long a, long b) {}

    /**
     * Layers worked out lately, by their pixels, for every view: at most {@link #KEPT_BYTES} of maps, the least recently
     * used let go first.
     */
    private static final LinkedHashMap<Pixels, Prepared> KEPT = new LinkedHashMap<>(64, 0.75f, true);
    static final long KEPT_BYTES = 16L << 20;
    private static long keptBytes;

    @Override
    public int mapsFor(int[] argb, int x, int y, int w, int h) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(cols * 128, x + w), y1 = Math.min(rows * 128, y + h);
        return x1 <= x0 || y1 <= y0 ? 0 : places(argb, x, y, w, x0, y0, x1, y1).size();
    }

    @Override
    public Layer prepare(int[] argb, int x, int y, int w, int h, BooleanSupplier stop) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(cols * 128, x + w), y1 = Math.min(rows * 128, y + h);
        if (x1 <= x0 || y1 <= y0) return new Prepared(cols, rows, new int[0][], new byte[0][]);
        Pixels key = pixels(palette, cols, rows, argb, x, y, w, h);
        synchronized (KEPT) {
            Prepared kept = KEPT.get(key);
            if (kept != null) return kept;
        }
        List<int[]> places = places(argb, x, y, w, x0, y0, x1, y1);
        byte[][] tiles = new byte[places.size()][];
        byte[] ycc = YCC.get();
        for (int k = 0; k < tiles.length; k++) {
            if (stop.getAsBoolean()) return null;
            int cx = places.get(k)[0], cy = places.get(k)[1];
            java.util.Arrays.fill(ycc, (byte) 0);
            for (int py = Math.max(y0, cy); py < Math.min(y1, cy + MAP_ROWS); py++) {
                int s = (py - y) * w - x, d = ((py - cy + 2) * 128 - cx) * 3;
                for (int px = Math.max(x0, cx); px < Math.min(x1, cx + 128); px++) {
                    int p = argb[s + px];
                    // Under half opaque: transparent (Cb and Cr 0), where the view's own shows.
                    if (p >>> 24 >= 128) Palette.rgbToYcc(p, ycc, d + px * 3);
                }
            }
            tiles[k] = new byte[Canvas.TILE];
            palette.encodeHi(ycc, 128, 0, 0, tiles[k], true);
        }
        Prepared made = new Prepared(cols, rows, places.toArray(new int[0][]), tiles);
        synchronized (KEPT) {
            if (KEPT.put(key, made) == null) keptBytes += (long) tiles.length * Canvas.TILE;
            for (var it = KEPT.values().iterator(); keptBytes > KEPT_BYTES && it.hasNext(); ) {
                keptBytes -= (long) it.next().tiles.length * Canvas.TILE;
                it.remove();
            }
        }
        return made;
    }

    /** [argb]'s key in {@link #KEPT}: two 64-bit hashes of its pixels, with different mixes, so two that differ never meet. */
    private static Pixels pixels(Palette palette, int cols, int rows, int[] argb, int x, int y, int w, int h) {
        long a = 0x9E3779B97F4A7C15L, b = 0xC2B2AE3D27D4EB4FL;
        for (int p : argb) {
            a = (a ^ p) * 0xFF51AFD7ED558CCDL;
            a ^= a >>> 29;
            b = Long.rotateLeft(b + p * 0x165667B19E3779F9L, 31) * 0x85EBCA77C2B2AE63L;
        }
        return new Pixels(palette, cols, rows, x, y, w, h, a, b);
    }

    /** Layers kept for every view, and their bytes (tests). */
    static long keptBytes() {
        synchronized (KEPT) {
            return keptBytes;
        }
    }

    @Override
    public boolean arm(Object key, int[] include, int[] exclude, int priority, Layer layer) {
        Prepared p = (Prepared) layer;
        if (p.cols != cols || p.rows != rows) throw new IllegalArgumentException("a layer of a view of another size");
        int[] in = include, out = exclude == null ? NOWHERE : exclude;
        int prio = Math.max(0, Math.min(PRIORITIES - 1, priority));
        int n = p.tiles.length;
        synchronized (this) {
            Map<Integer, byte[]> given = new HashMap<>();
            release(key, given);
            if (n == 0 || n > owner.length / 2 || owner.length - used < n) {
                canvas.put(given);
                return false;
            }
            int[] slots = new int[n];
            java.util.Arrays.fill(slots, -1);
            // First the maps the client still has this layer's pictures in: only their marks are sent.
            for (int k = 0; k < n; k++) {
                for (int i = 0; i < owner.length; i++) {
                    if (owner[i] == null && source[i] == p.tiles[k] && atX[i] == p.places[k][0] && atY[i] == p.places[k][1]) {
                        take(i, k, key, p, prio, in, out, given);
                        slots[k] = i;
                        break;
                    }
                }
            }
            for (int k = 0, i = 0; k < n; k++) {
                if (slots[k] >= 0) continue;
                while (owner[i] != null) i++;
                take(i, k, key, p, prio, in, out, given);
                slots[k] = i;
            }
            used += n;
            changes++;
            held.put(key, slots);
            canvas.put(given);
            return true;
        }
    }

    /** Map [i] takes map [k] of [p], as layer [key]. Under the lock. */
    private void take(int i, int k, Object key, Prepared p, int prio, int[] in, int[] out, Map<Integer, byte[]> given) {
        owner[i] = key;
        atX[i] = p.places[k][0];
        atY[i] = p.places[k][1];
        source[i] = p.tiles[k];
        shown[i] = palette.markLayer(p.tiles[k].clone(), cols, rows, atX[i], atY[i], speed, side, prio, in, out);
        given.put(i, shown[i]);
    }

    /**
     * Where the maps of a layer of [argb] ([w] wide, its first pixel at canvas (x, y); [x0, x1) × [y0, y1) of it inside
     * the canvas) go, each as the canvas pixel of its first picture row's first pixel: 128 × {@link #MAP_ROWS} pixels
     * each, anywhere on the canvas (the client places a layer's map by what it carries, not on the view's tiles), over
     * every 2 × 2 block with a pixel half opaque or more. Greedy: the first such block not yet covered, row by row, starts
     * a map's band of rows, which reaches as far left as it can while keeping that block. A row of buttons 456 pixels
     * across takes 4 maps this way where the view's tiles under it number 8.
     *
     * <p>Each map starts on a column that is a multiple of 4 and a row 2 below one (its picture starts on its third row),
     * so its 4 × 4 dither cells and 2 × 2 colour blocks ({@link Palette#encodeHi}) fall where the view's own tiles have
     * them, and a pixel comes out of the layer as the view's own picture has it once it catches up: anywhere else a
     * gradient or a soft edge shimmers as the picture arrives under the layer. Only a layer in the canvas's top two rows
     * cannot start there (a map's place is never above the canvas).
     */
    static List<int[]> places(int[] argb, int x, int y, int w, int x0, int y0, int x1, int y1) {
        boolean aligned = y0 >= 2;
        int gx = x0 & ~3, gy = aligned ? ((y0 - 2) & ~3) + 2 : y0 & ~1;
        int bw = (x1 - gx + 1) / 2, bh = (y1 - gy + 1) / 2;
        boolean[] want = new boolean[bw * bh];
        for (int py = y0; py < y1; py++) {
            int s = (py - y) * w - x, row = (py - gy) / 2 * bw;
            for (int px = x0; px < x1; px++) if (argb[s + px] >>> 24 >= 128) want[row + (px - gx) / 2] = true;
        }
        int across = 64, down = MAP_ROWS / 2;
        List<int[]> places = new ArrayList<>();
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                if (!want[by * bw + bx]) continue;
                // An even block, so the map's place keeps the view's phase; the row above is all covered by now.
                int top = aligned ? by & ~1 : by;
                int left = bx;
                for (int r = top; r < Math.min(bh, top + down); r++) {
                    // From where the left edge, made even, still reaches this block.
                    for (int c = Math.max(0, bx - across + 2); c < left; c++) {
                        if (want[r * bw + c]) {
                            left = c;
                            break;
                        }
                    }
                }
                left &= ~1;
                for (int r = top; r < Math.min(bh, top + down); r++) {
                    for (int c = left; c < Math.min(bw, left + across); c++) want[r * bw + c] = false;
                }
                places.add(new int[]{gx + left * 2, gy + top * 2});
            }
        }
        return places;
    }

    @Override
    public synchronized void disarm(Object key) {
        Map<Integer, byte[]> given = new HashMap<>();
        release(key, given);
        canvas.put(given);
    }

    @Override
    public synchronized void clear() {
        Map<Integer, byte[]> given = new HashMap<>();
        for (Object key : List.copyOf(held.keySet())) release(key, given);
        canvas.put(given);
    }

    /**
     * Frees [key]'s maps, each re-marked with an empty rectangle into [given]: only the marks differ, so the client is
     * sent a few pixels, and the map never shows until a layer takes it again. Under the lock.
     */
    private void release(Object key, Map<Integer, byte[]> given) {
        int[] slots = held.remove(key);
        if (slots == null) return;
        for (int i : slots) {
            byte[] off = shown[i].clone();
            palette.markLayer(off, cols, rows, atX[i], atY[i], speed, side, 0, NOWHERE, NOWHERE);
            shown[i] = off;
            owner[i] = null;
            given.put(i, off);
        }
        used -= slots.length;
        changes++;
    }

}
