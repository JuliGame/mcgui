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
        assertTrue(set.arm("a", include, exclude, 7, px, x, y, w, h));
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
        // The first map from the first opaque pixel, 128 x 126 of the canvas; the second where the first ends.
        assertArrayEquals(new int[]{200, 100}, places[0]);
        assertArrayEquals(new int[]{328, 128}, places[1]);
        // A map's row 2 is the canvas row it starts at: canvas (330, 130) is the second map's (2, 4), opaque; canvas
        // (300, 110), the first map's (100, 12), is not.
        assertTrue(snap.tile(1, Canvas.Encoding.HI)[4 * 128 + 2] != Palette.CLEAR_ID);
        assertEquals(Palette.CLEAR_ID, snap.tile(0, Canvas.Encoding.HI)[12 * 128 + 100]);
        assertTrue(snap.tile(0, Canvas.Encoding.HI)[4 * 128 + 2] != Palette.CLEAR_ID);
    }

    @Test
    void aStripTakesAMapFor128PixelsOfItWhereverItLies() {
        // A row of buttons 456 x 68 across two tile rows and four tile columns: eight of the view's tiles, four maps.
        int x = 524, y = 596, w = 456, h = 68;
        List<int[]> places = LayerSet.places(solid(w, h, 0xFFFFFFFF), x, y, w, x, y, x + w, y + h);
        assertEquals(4, places.size());
        for (int i = 0; i < 4; i++) assertArrayEquals(new int[]{x + 128 * i, y}, places.get(i));
        // A tall one: a map holds 126 rows.
        assertEquals(2, LayerSet.places(solid(100, 127, 0xFFFFFFFF), 0, 0, 100, 0, 0, 100, 127).size());
        assertEquals(1, LayerSet.places(solid(100, 126, 0xFFFFFFFF), 0, 0, 100, 0, 0, 100, 126).size());
    }

    @Test
    void framesAreWantedOnlyForHeldMaps() {
        Canvas canvas = new Canvas(PALETTE, 8, 1);
        LayerSet set = new LayerSet(PALETTE, canvas, null, COLS, ROWS, 8, 0);
        assertArrayEquals(new boolean[8], set.framed());
        assertTrue(set.arm("a", new int[]{0, 0, 300, 100}, null, 1, solid(300, 100, 0xFFFF0000), 0, 0, 300, 100));
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
        assertTrue(set.arm("a", new int[]{0, 0, 300, 100}, null, 1, solid(300, 100, 0xFFFF0000), 0, 0, 300, 100));
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
        assertTrue(set.arm("a", new int[]{0, 0, 384, 100}, null, 1, solid(384, 100, 0xFF00FF00), 0, 0, 384, 100));
        assertTrue(set.arm("c", new int[]{0, 200, 384, 300}, null, 1, solid(384, 100, 0xFF00FF00), 0, 200, 384, 100));
        assertFalse(set.arm("b", new int[]{0, 400, 256, 500}, null, 1, solid(256, 100, 0xFF00FF00), 0, 400, 256, 100));
        assertFalse(set.armed("b"));
        assertEquals(6, set.used());
        // Armed again in its own place, a layer may use the maps it had.
        assertTrue(set.arm("a", new int[]{0, 0, 256, 100}, null, 1, solid(256, 100, 0xFF00FF00), 0, 0, 256, 100));
        assertEquals(5, set.used());
        // Nothing opaque: nothing to arm.
        assertFalse(set.arm("d", new int[]{0, 0, 10, 10}, null, 1, new int[100], 0, 0, 10, 10));
        set.clear();
        assertEquals(0, set.used());
    }

    @Test
    void aLayerNeedingMoreThanHalfTheMapsIsNeverArmed() {
        Canvas canvas = new Canvas(PALETTE, 6, 1);
        LayerSet set = new LayerSet(PALETTE, canvas, null, COLS, ROWS, 8, 0);
        int[] wide = solid(512, 100, 0xFF00FF00);
        assertEquals(4, set.mapsFor(wide, 0, 0, 512, 100));
        assertFalse(set.arm("a", new int[]{0, 0, 512, 100}, null, 1, wide, 0, 0, 512, 100));
        assertEquals(0, set.used());
        assertEquals(3, set.mapsFor(solid(384, 100, 0xFF00FF00), 0, 0, 384, 100));
    }
}
