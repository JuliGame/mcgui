package ai.resourcepack.ui.framework.world;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.api.HoverLayers;
import ai.resourcepack.ui.framework.pack.Shaders;
import ai.resourcepack.ui.framework.render.Palette;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 */
final class LayerSet implements HoverLayers {
    private static final int[] NOWHERE = {0, 0, 0, 0};

    final MapWall wall;
    final Canvas canvas;
    private final Palette palette;
    private final int cols, rows, speed, side;
    /**
     * By map: the key of the layer holding it (null for none), the canvas pixel its first picture row starts at (see
     * markLayer), and the bytes it was last given.
     */
    private final Object[] owner;
    private final int[] atX, atY;
    private final byte[][] shown;
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

    /** One converted map's colours, per thread: 48 KB, used by one {@link #arm} at a time. */
    private static final ThreadLocal<byte[]> YCC = ThreadLocal.withInitial(() -> new byte[Canvas.TILE * 3]);

    /** Picture rows a layer's map holds: its top two are its data's. */
    static final int MAP_ROWS = 126;

    @Override
    public int mapsFor(int[] argb, int x, int y, int w, int h) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(cols * 128, x + w), y1 = Math.min(rows * 128, y + h);
        return x1 <= x0 || y1 <= y0 ? 0 : places(argb, x, y, w, x0, y0, x1, y1).size();
    }

    @Override
    public boolean arm(Object key, int[] include, int[] exclude, int priority, int[] argb, int x, int y, int w, int h) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(cols * 128, x + w), y1 = Math.min(rows * 128, y + h);
        if (x1 <= x0 || y1 <= y0) return false;
        int[] in = include, out = exclude == null ? NOWHERE : exclude;
        int prio = Math.max(0, Math.min(PRIORITIES - 1, priority));
        // Where its maps go, first: a layer with no room is turned down before the work of encoding it.
        List<int[]> places = places(argb, x, y, w, x0, y0, x1, y1);
        if (places.isEmpty() || places.size() > owner.length / 2) {
            disarm(key);
            return false;
        }
        synchronized (this) {
            int[] mine = held.get(key);
            if (owner.length - used + (mine == null ? 0 : mine.length) < places.size()) {
                disarm(key);
                return false;
            }
        }
        // Encoded outside the lock: it is most of the work, and touches nothing shared.
        List<byte[]> tiles = new ArrayList<>();
        byte[] ycc = YCC.get();
        for (int[] place : places) {
            int cx = place[0], cy = place[1];
            java.util.Arrays.fill(ycc, (byte) 0);
            for (int py = Math.max(y0, cy); py < Math.min(y1, cy + MAP_ROWS); py++) {
                int s = (py - y) * w - x, d = ((py - cy + 2) * 128 - cx) * 3;
                for (int px = Math.max(x0, cx); px < Math.min(x1, cx + 128); px++) {
                    int p = argb[s + px];
                    // Under half opaque: transparent (Cb and Cr 0), where the view's own shows.
                    if (p >>> 24 >= 128) Palette.rgbToYcc(p, ycc, d + px * 3);
                }
            }
            byte[] hi = new byte[Canvas.TILE];
            palette.encodeHi(ycc, 128, 0, 0, hi, true);
            tiles.add(palette.markLayer(hi, cols, rows, cx, cy, speed, side, prio, in, out));
        }
        synchronized (this) {
            Map<Integer, byte[]> given = new HashMap<>();
            release(key, given);
            if (owner.length - used < tiles.size()) {
                canvas.put(given);
                return false;
            }
            int[] slots = new int[tiles.size()];
            int k = 0;
            for (int i = 0; i < owner.length && k < slots.length; i++) {
                if (owner[i] != null) continue;
                owner[i] = key;
                atX[i] = places.get(k)[0];
                atY[i] = places.get(k)[1];
                shown[i] = tiles.get(k);
                given.put(i, shown[i]);
                slots[k++] = i;
            }
            used += slots.length;
            changes++;
            held.put(key, slots);
            canvas.put(given);
            return true;
        }
    }

    /**
     * Where the maps of a layer of [argb] ([w] wide, its first pixel at canvas (x, y); [x0, x1) × [y0, y1) of it inside
     * the canvas) go, each as the canvas pixel of its first picture row's first pixel: 128 × {@link #MAP_ROWS} pixels
     * each, anywhere on the canvas (the client places a layer's map by what it carries, not on the view's tiles), over
     * every 2 × 2 block with a pixel half opaque or more. Greedy: the first such block not yet covered, row by row, starts
     * a map's band of rows, which reaches as far left as it can while keeping that block. A row of buttons 456 pixels
     * across takes 4 maps this way where the view's tiles under it number 8.
     */
    static List<int[]> places(int[] argb, int x, int y, int w, int x0, int y0, int x1, int y1) {
        int gx = x0 & ~1, gy = y0 & ~1;
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
                int left = bx;
                for (int r = by; r < Math.min(bh, by + down); r++) {
                    for (int c = Math.max(0, bx - across + 1); c < left; c++) {
                        if (want[r * bw + c]) {
                            left = c;
                            break;
                        }
                    }
                }
                for (int r = by; r < Math.min(bh, by + down); r++) {
                    for (int c = left; c < Math.min(bw, left + across); c++) want[r * bw + c] = false;
                }
                places.add(new int[]{gx + left * 2, gy + by * 2});
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
