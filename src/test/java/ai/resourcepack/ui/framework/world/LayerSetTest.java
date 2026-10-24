package ai.resourcepack.ui.framework.world;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.pack.Shaders;
import ai.resourcepack.ui.framework.render.Palette;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A view's hover layers ({@link LayerSet}): a layer takes a map only for each tile where it is opaque, every map carries
 * what text.vsh reads (Palette.markLayer: swapped top markers, the view's size, the tile, the speed, the side and
 * priority, the two rectangles), a map no layer holds never shows and wants no frame, and a layer with no room arms
 * nothing.
 */
class LayerSetTest {
    private static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));
    private static final int COLS = 16, ROWS = 9;

    private static int symbol(byte id) {
        for (int s = 0; s < Palette.SYMBOLS; s++) if (PALETTE.symbolId[s] == id) return s;
        throw new AssertionError("not a symbol: " + id);
    }

    private static int edge(byte[] t, int at) {
        return symbol(t[at]) * Palette.SYMBOLS + symbol(t[at + 1]);
    }

    /** The {left, top, right, bottom} at block x = [at] of a layer's map, as text.vsh reads it. */
    private static int[] rect(byte[] t, int at) {
        return new int[]{edge(t, at), edge(t, 128 + at), edge(t, at + 2), edge(t, 128 + at + 2)};
    }

    private static int[] solid(int w, int h, int argb) {
        int[] px = new int[w * h];
        Arrays.fill(px, argb);
        return px;
    }

    /** [px] prepared and armed as layer [key] of [set]. */
    private static boolean arm(LayerSet set, String key, int[] include, int[] exclude, int priority, int[] px, int x, int y, int w, int h) {
        return set.arm(key, include, exclude, priority, set.prepare(px, x, y, w, h, () -> false));
    }

    @Test
    void aLayerTakesAMapForEachPartItIsOpaqueIn() {
        Canvas canvas = new Canvas(PALETTE, 8, 1);
        LayerSet set = new LayerSet(PALETTE, canvas, null, COLS, ROWS, 12.5, Palette.LAYER_ALONG_Z | Palette.LAYER_LOOKS_NEGATIVE);
        // Pixels from (200, 100), opaque only left of x 256 above y 128, and right of x 256 below it.
        int x = 200, y = 100, w = 184, h = 100;
        int[] px = new int[w * h];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                boolean topLeft = x + i < 256 && y + j < 128, bottomRight = x + i >= 256 && y + j >= 128;
                if (topLeft || bottomRight) px[j * w + i] = 0xFF336699;
            }
        }
        int[] include = {210, 110, 380, 190}, exclude = {250, 120, 260, 130};
        assertTrue(arm(set, "a", include, exclude, 7, px, x, y, w, h));
        assertEquals(2, set.used());
        Canvas.Snapshot snap = canvas.snapshot();
        int[][] places = new int[2][];
        for (int slot = 0; slot < 2; slot++) {
            byte[] t = snap.tile(slot, Canvas.Encoding.HI);
            assertEquals(PALETTE.markerId[1], t[0], "top markers swapped");
            assertEquals(PALETTE.markerId[0], t[1]);
            assertEquals(PALETTE.markerId[3], t[128]);
            assertEquals(PALETTE.markerId[3], t[129]);
            assertEquals(COLS, symbol(t[2]));
            assertEquals(ROWS, symbol(t[3]));
            places[slot] = new int[]{edge(t, 130), edge(t, 14)};
            int speed = (symbol(t[4]) * Palette.SYMBOLS + symbol(t[5])) * Palette.SYMBOLS + symbol(t[132]);
            assertEquals(Math.round(12.5 * Shaders.LAYER_SPEED_SCALE), speed);
            assertEquals(7 * 4 + 3, symbol(t[133]), "priority four times, plus 2 for along z and 1 for looking towards negative z");
            assertArrayEquals(include, rect(t, 6));
            assertArrayEquals(exclude, rect(t, 10));
        }
        // The first map from a row 2 above the first opaque pixel (a map starts 2 below a multiple of 4), 128 x 126 of
        // the canvas; the second where the first ends, from the even block row at or above its first opaque pixel.
        assertArrayEquals(new int[]{200, 98}, places[0]);
        assertArrayEquals(new int[]{328, 126}, places[1]);
        // A map's row 2 is the canvas row it starts at: canvas (330, 130) is the second map's (2, 6), opaque; canvas
        // (300, 110), the first map's (100, 14), is not.
        assertTrue(snap.tile(1, Canvas.Encoding.HI)[6 * 128 + 2] != Palette.CLEAR_ID);
        assertEquals(Palette.CLEAR_ID, snap.tile(0, Canvas.Encoding.HI)[14 * 128 + 100]);
        assertTrue(snap.tile(0, Canvas.Encoding.HI)[4 * 128 + 2] != Palette.CLEAR_ID);
    }

    @Test
    void aStripTakesAMapFor128PixelsOfItWhereverItLies() {
        // A row of buttons 456 x 68 across two tile rows and four tile columns: eight of the view's tiles, four maps.
        int x = 524, y = 596, w = 456, h = 68;
        List<int[]> places = LayerSet.places(solid(w, h, 0xFFFFFFFF), x, y, w, x, y, x + w, y + h);
        assertEquals(4, places.size());
        // From a row 2 below a multiple of 4: two rows above the buttons.
        for (int i = 0; i < 4; i++) assertArrayEquals(new int[]{x + 128 * i, y - 2}, places.get(i));
        // A tall one: a map holds 126 rows.
        assertEquals(2, LayerSet.places(solid(100, 127, 0xFFFFFFFF), 0, 0, 100, 0, 0, 100, 127).size());
        assertEquals(1, LayerSet.places(solid(100, 126, 0xFFFFFFFF), 0, 0, 100, 0, 0, 100, 126).size());
    }

    /**
     * Wherever a layer lies, each pixel of it comes out of its map as the view's own tile has it once the picture catches
     * up (the same 4 x 4 dither cell and 2 x 2 colour block): a gradient under a layer does not shimmer as the picture
     * arrives under it.
     */
    @Test
    void aLayerEncodesItsPixelsAsTheViewDoes() {
        Canvas view = new Canvas(PALETTE, COLS, ROWS);
        int cw = COLS * 128, ch = ROWS * 128;
        int[] picture = new int[cw * ch];
        for (int j = 0; j < ch; j++) for (int i = 0; i < cw; i++) picture[j * cw + i] = 0xFF000000 | (i * 255 / cw) << 16 | (j * 255 / ch) << 8 | ((i ^ j) & 255);
        view.draw(picture, cw, ch, 0, 0);
        Canvas.Snapshot shown = view.snapshot();
        Canvas maps = new Canvas(PALETTE, 8, 1);
        LayerSet set = new LayerSet(PALETTE, maps, null, COLS, ROWS, 12.5, 0);
        for (int[] at : new int[][]{{301, 207}, {130, 5}, {642, 389}, {1000, 902}}) {
            int x = at[0] & ~1, y = at[1] & ~1, w = 150, h = 90;
            int[] px = new int[w * h];
            for (int j = 0; j < h; j++) System.arraycopy(picture, (y + j) * cw + x, px, j * w, w);
            LayerSet.Prepared p = (LayerSet.Prepared) set.prepare(px, x, y, w, h, () -> false);
            for (int k = 0; k < p.maps(); k++) {
                int mx = p.places()[k][0], my = p.places()[k][1];
                assertEquals(0, mx % 4, "a map's first column");
                assertEquals(2, my % 4, "a map's first picture row");
                byte[] t = p.tiles()[k];
                for (int r = 2; r < 128; r++) {
                    for (int c = 0; c < 128; c++) {
                        if (t[r * 128 + c] == Palette.CLEAR_ID) continue;
                        int vx = mx + c, vy = my + r - 2;
                        // A view tile's own top-left 2 x 2 block holds its markers, which the shader never draws.
                        if (vx % 128 < 2 && vy % 128 < 2) continue;
                        byte own = shown.tile(vy / 128 * COLS + vx / 128, Canvas.Encoding.HI)[vy % 128 * 128 + vx % 128];
                        assertEquals(own, t[r * 128 + c], "canvas pixel (" + vx + ", " + vy + ") of the layer at " + x + "," + y);
                    }
                }
            }
        }
    }

    @Test
    void framesAreWantedOnlyForHeldMaps() {
        Canvas canvas = new Canvas(PALETTE, 8, 1);
        LayerSet set = new LayerSet(PALETTE, canvas, null, COLS, ROWS, 8, 0);
        assertArrayEquals(new boolean[8], set.framed());
        assertTrue(arm(set, "a", new int[]{0, 0, 300, 100}, null, 1, solid(300, 100, 0xFFFF0000), 0, 0, 300, 100));
        // Only its three held maps want frames on the client; a map no layer holds has none.
        boolean[] framed = set.framed();
        assertArrayEquals(new boolean[]{true, true, true, false, false, false, false, false}, framed);
        byte[] free = canvas.snapshot().tile(5, Canvas.Encoding.HI);
        int[] none = rect(free, 6);
        assertFalse(none[0] < none[2] && none[1] < none[3], "a free map never shows");
    }

    @Test
    void disarmingEmptiesTheRectangleAndFreesTheMaps() {
        Canvas canvas = new Canvas(PALETTE, 8, 1);
        LayerSet set = new LayerSet(PALETTE, canvas, null, COLS, ROWS, 8, 0);
        assertTrue(arm(set, "a", new int[]{0, 0, 300, 100}, null, 1, solid(300, 100, 0xFFFF0000), 0, 0, 300, 100));
        assertEquals(3, set.used());
        byte[] before = canvas.snapshot().tile(0, Canvas.Encoding.HI);
        int changes = set.changes();
        set.disarm("a");
        assertEquals(0, set.used());
        assertFalse(set.armed("a"));
        assertTrue(set.changes() != changes, "the view is told to take the frames away");
        assertArrayEquals(new boolean[8], set.framed());
        byte[] after = canvas.snapshot().tile(0, Canvas.Encoding.HI);
        int[] r = rect(after, 6);
        assertFalse(r[0] < r[2] && r[1] < r[3], "a disarmed map still shows");
        // Only its marks changed: what the client is sent is a few pixels.
        int differ = 0;
        for (int i = 0; i < Canvas.TILE; i++) if (before[i] != after[i]) differ++;
        assertTrue(differ <= 16, differ + " bytes changed");
    }

    @Test
    void aLayerWithNoRoomArmsNothing() {
        Canvas canvas = new Canvas(PALETTE, 6, 1);
        LayerSet set = new LayerSet(PALETTE, canvas, null, COLS, ROWS, 8, 0);
        assertTrue(arm(set, "a", new int[]{0, 0, 384, 100}, null, 1, solid(384, 100, 0xFF00FF00), 0, 0, 384, 100));
        assertTrue(arm(set, "c", new int[]{0, 200, 384, 300}, null, 1, solid(384, 100, 0xFF00FF00), 0, 200, 384, 100));
        assertFalse(arm(set, "b", new int[]{0, 400, 256, 500}, null, 1, solid(256, 100, 0xFF00FF00), 0, 400, 256, 100));
        assertFalse(set.armed("b"));
        assertEquals(6, set.used());
        // Armed again in its own place, a layer may use the maps it had.
        assertTrue(arm(set, "a", new int[]{0, 0, 256, 100}, null, 1, solid(256, 100, 0xFF00FF00), 0, 0, 256, 100));
        assertEquals(5, set.used());
        // Nothing opaque: nothing to arm.
        assertFalse(arm(set, "d", new int[]{0, 0, 10, 10}, null, 1, new int[100], 0, 0, 10, 10));
        set.clear();
        assertEquals(0, set.used());
    }

    @Test
    void aLayerNeedingMoreThanHalfTheMapsIsNeverArmed() {
        Canvas canvas = new Canvas(PALETTE, 6, 1);
        LayerSet set = new LayerSet(PALETTE, canvas, null, COLS, ROWS, 8, 0);
        int[] wide = solid(512, 100, 0xFF00FF00);
        assertEquals(4, set.mapsFor(wide, 0, 0, 512, 100));
        assertFalse(arm(set, "a", new int[]{0, 0, 512, 100}, null, 1, wide, 0, 0, 512, 100));
        assertEquals(0, set.used());
        assertEquals(3, set.mapsFor(solid(384, 100, 0xFF00FF00), 0, 0, 384, 100));
    }

    /**
     * Two views of one size given the same pixels share one layer, encoded once: each only marks its maps, so the maps of
     * two views alike come out byte for byte the same.
     */
    @Test
    void aLayerIsEncodedOnceForEveryView() {
        LayerSet one = new LayerSet(PALETTE, new Canvas(PALETTE, 8, 1), null, COLS, ROWS, 8, 0);
        LayerSet two = new LayerSet(PALETTE, new Canvas(PALETTE, 8, 1), null, COLS, ROWS, 8, 0);
        int[] px = solid(300, 100, 0xFF4080C0);
        var a = one.prepare(px, 0, 0, 300, 100, () -> false);
        var b = two.prepare(px.clone(), 0, 0, 300, 100, () -> false);
        assertSame(a, b, "worked out twice");
        assertNotSame(a, one.prepare(solid(300, 100, 0xFF4080C1), 0, 0, 300, 100, () -> false), "other pixels found the same layer");
        int[] include = {0, 0, 300, 100};
        assertTrue(one.arm("a", include, null, 1, a));
        assertTrue(two.arm("a", include, null, 1, b));
        for (int i = 0; i < 3; i++) assertArrayEquals(one.canvas.snapshot().tile(i, Canvas.Encoding.HI), two.canvas.snapshot().tile(i, Canvas.Encoding.HI));
        // A view of another size cannot take it.
        LayerSet other = new LayerSet(PALETTE, new Canvas(PALETTE, 8, 1), null, 8, 4, 8, 0);
        assertThrows(IllegalArgumentException.class, () -> other.arm("a", include, null, 1, a));
    }

    /** A layer taken off as the picture under it changes, and armed again over the next, goes back into its maps: only marks are sent. */
    @Test
    void armedAgainItsMapsAreSentOnlyTheirMarks() {
        Canvas canvas = new Canvas(PALETTE, 8, 1);
        LayerSet set = new LayerSet(PALETTE, canvas, null, COLS, ROWS, 8, 0);
        assertTrue(arm(set, "x", new int[]{0, 200, 256, 300}, null, 1, solid(256, 100, 0xFF00FF00), 0, 200, 256, 100));
        assertTrue(arm(set, "a", new int[]{0, 0, 300, 100}, null, 1, solid(300, 100, 0xFFFF0000), 0, 0, 300, 100));
        Canvas.Snapshot before = canvas.snapshot();
        set.clear();
        // Over the next picture, in another order and with another rectangle: the same pixels.
        assertTrue(arm(set, "a", new int[]{0, 0, 290, 100}, null, 2, solid(300, 100, 0xFFFF0000), 0, 0, 300, 100));
        Canvas.Snapshot after = canvas.snapshot();
        for (int i = 2; i < 5; i++) {
            byte[] p = before.tile(i, Canvas.Encoding.HI), q = after.tile(i, Canvas.Encoding.HI);
            int differ = 0;
            for (int k = 0; k < Canvas.TILE; k++) if (p[k] != q[k]) differ++;
            assertTrue(differ <= 16, "map " + i + ": " + differ + " bytes changed");
        }
        assertArrayEquals(new int[]{0, 0, 290, 100}, rect(after.tile(2, Canvas.Encoding.HI), 6));
    }

    /** Preparing stops between maps when told to, and keeps nothing. */
    @Test
    void preparingStopsWhenToldTo() {
        LayerSet set = new LayerSet(PALETTE, new Canvas(PALETTE, 8, 1), null, COLS, ROWS, 8, 0);
        int[] px = solid(300, 100, 0xFF102030);
        assertNull(set.prepare(px, 0, 0, 300, 100, () -> true));
        int[] asked = {0};
        assertNotNull(set.prepare(px, 0, 0, 300, 100, () -> asked[0]++ < 0));
        assertEquals(3, asked[0], "asked between maps");
    }
}
