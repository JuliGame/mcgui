package ai.resourcepack.ui.framework.api;

import ai.resourcepack.ui.framework.render.Palette;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Canvases that share their colours (copy-on-write: {@link Canvas#copy}, {@link Canvas#show}) and their published tile
 * arrays must never change an array once it is handed out: the map sender and its shared compression cache keep
 * compressed bytes by the array's identity, for every viewer of any canvas that publishes it. A drawing into a canvas
 * that shares its colours makes new arrays for the tiles it changes and leaves every array handed out before as it was,
 * in every canvas, and every canvas's tiles are always those of its own picture.
 */
class SharedTilesTest {
    private static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));
    private static final int COLS = 3, ROWS = 2, W = COLS * 128, H = ROWS * 128;

    /** Every array seen, by identity, with its bytes when first seen: what a cache keyed by identity would serve. */
    private final Map<byte[], byte[]> seen = new IdentityHashMap<>();

    /** A canvas and the picture it should show. */
    private record Shown(String name, Canvas canvas, int[] picture) {}

    private static int[] noise(Random r, int w, int h) {
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) px[i] = r.nextInt();
        return px;
    }

    private static void paint(Shown s, int[] block, int w, int h, int x, int y) {
        s.canvas.draw(block, w, h, x, y);
        for (int j = 0; j < h; j++) System.arraycopy(block, j * w, s.picture, (y + j) * W + x, w);
    }

    /**
     * Every tile of every canvas, in every encoding: an array seen before still holds the bytes it had then, and the
     * tile is what a canvas that never shared anything makes of the same picture.
     */
    private void check(String step, Shown... all) {
        for (Shown s : all) {
            Canvas fresh = new Canvas(PALETTE, COLS, ROWS);
            fresh.draw(s.picture, W, H, 0, 0);
            for (Canvas.Encoding e : Canvas.Encoding.values()) {
                for (int t = 0; t < COLS * ROWS; t++) {
                    byte[] got = s.canvas.tile(t, e);
                    byte[] first = seen.putIfAbsent(got, got.clone());
                    String where = step + ": " + s.name + " tile " + t + " " + e;
                    if (first != null) assertArrayEquals(first, got, where + ": an array handed out before has changed");
                    assertArrayEquals(fresh.tile(t, e), got, where + ": not the tile of its own picture");
                }
            }
        }
    }

    @Test
    void aDrawingIntoSharedColoursMakesNewArraysAndChangesNoneHandedOut() {
        Random r = new Random(11);
        Shown a = new Shown("a", new Canvas(PALETTE, COLS, ROWS), new int[W * H]);
        // Blank canvases share one blank tile: seen through any of them, it is black.
        Shown blank = new Shown("blank", new Canvas(PALETTE, COLS, ROWS), new int[W * H]);
        java.util.Arrays.fill(a.picture, 0xFF000000);
        java.util.Arrays.fill(blank.picture, 0xFF000000);
        check("blank", a, blank);

        paint(a, noise(r, W, H), W, H, 0, 0);
        check("a drawn", a, blank);

        // b and c share a's colours and tile arrays.
        Shown b = new Shown("b", a.canvas.copy(), a.picture.clone());
        Shown c = new Shown("c", new Canvas(PALETTE, COLS, ROWS), a.picture.clone());
        c.canvas.show(a.canvas);
        for (int t = 0; t < COLS * ROWS; t++) {
            assertSame(a.canvas.tile(t, Canvas.Encoding.HI), b.canvas.tile(t, Canvas.Encoding.HI), "copy shares tile " + t);
            assertSame(a.canvas.tile(t, Canvas.Encoding.HI), c.canvas.tile(t, Canvas.Encoding.HI), "show shares tile " + t);
        }
        check("shared", a, b, c);
        // A view's tiles carry their place, made once for every canvas showing the same tile there.
        for (int t = 0; t < COLS * ROWS; t++) {
            assertSame(a.canvas.tile(t, Canvas.Encoding.HI_TILED), b.canvas.tile(t, Canvas.Encoding.HI_TILED), "copy shares HI_TILED " + t);
            assertSame(a.canvas.tile(t, Canvas.Encoding.HI_TILED), c.canvas.tile(t, Canvas.Encoding.HI_TILED), "show shares HI_TILED " + t);
        }
        assertEquals(0, a.canvas.ownMaps() + b.canvas.ownMaps() + c.canvas.ownMaps(), "nothing drawn since sharing: every map shared");

        // b draws into tile 0 only: it detaches, tile 0 is a new array, the others are still a's.
        byte[] a0 = a.canvas.tile(0, Canvas.Encoding.HI), a1 = a.canvas.tile(1, Canvas.Encoding.HI);
        paint(b, noise(r, 40, 30), 40, 30, 10, 20);
        assertNotSame(a0, b.canvas.tile(0, Canvas.Encoding.HI), "b's changed tile is a new array");
        assertSame(a1, b.canvas.tile(1, Canvas.Encoding.HI), "b's unchanged tile is still a's");
        assertSame(a0, a.canvas.tile(0, Canvas.Encoding.HI), "a keeps its tile");
        check("b drew", a, b, c);
        assertEquals(1, b.canvas.ownMaps(), "b copied only the map it drew into");
        assertEquals(0, a.canvas.ownMaps() + c.canvas.ownMaps(), "a and c still share every map");

        // The canvas b shared from draws too, over a tile b and c still show from it.
        paint(a, noise(r, 100, 100), 100, 100, 200, 140);
        assertSame(a1, c.canvas.tile(1, Canvas.Encoding.HI), "c still shows a's old tile");
        check("a drew", a, b, c);
        assertEquals(2, a.canvas.ownMaps(), "a copied the two maps it drew into");

        // c scrolls by a map row and draws the row that came in: moved tiles keep their arrays, new ones are new.
        int[] row = noise(r, W, 128);
        byte[] movingUp = c.canvas.tile(COLS, Canvas.Encoding.HI);
        c.canvas.scroll(1, toImage(row, W, 128), 0, H - 128);
        System.arraycopy(c.picture, 128 * W, c.picture, 0, (H - 128) * W);
        System.arraycopy(row, 0, c.picture, (H - 128) * W, 128 * W);
        assertSame(movingUp, c.canvas.tile(0, Canvas.Encoding.HI), "a tile that only moved keeps its array");
        check("c scrolled", a, b, c);

        // A canvas showing b's picture after b drew, then drawing the very pixels it already has: nothing changes.
        Shown d = new Shown("d", new Canvas(PALETTE, COLS, ROWS), b.picture.clone());
        d.canvas.show(b.canvas);
        paint(d, b.picture.clone(), W, H, 0, 0);
        for (int t = 0; t < COLS * ROWS; t++) assertSame(b.canvas.tile(t, Canvas.Encoding.HI), d.canvas.tile(t, Canvas.Encoding.HI), "unchanged tile " + t);
        paint(b, noise(r, W, H), W, H, 0, 0);
        check("everything drawn again", a, b, c, d, blank);
    }

    /**
     * Canvases that share alike, each drawn on its own: where their pictures are alike they publish the very same tiles,
     * colours shared; where one then draws, only it changes, into new arrays, and nothing handed out changes.
     */
    @Test
    void canvasesThatShareAlikeShowOneTileWhereTheyAreAlike() {
        Random r = new Random(23);
        int[] picture = noise(r, W, H);
        Shown a = new Shown("a", new Canvas(PALETTE, COLS, ROWS), picture.clone());
        Shown b = new Shown("b", new Canvas(PALETTE, COLS, ROWS), picture.clone());
        // A third that does not share alike: its own arrays, the same bytes.
        Shown c = new Shown("c", new Canvas(PALETTE, COLS, ROWS), picture.clone());
        a.canvas.shareAlike(true);
        b.canvas.shareAlike(true);
        paint(a, picture.clone(), W, H, 0, 0);
        paint(b, picture.clone(), W, H, 0, 0);
        paint(c, picture.clone(), W, H, 0, 0);
        for (int t = 0; t < COLS * ROWS; t++) {
            for (Canvas.Encoding e : new Canvas.Encoding[]{Canvas.Encoding.HI, Canvas.Encoding.HI_OWN, Canvas.Encoding.HI_TILED, Canvas.Encoding.PLAIN}) {
                assertSame(a.canvas.tile(t, e), b.canvas.tile(t, e), "alike: tile " + t + " " + e);
            }
            assertNotSame(a.canvas.tile(t, Canvas.Encoding.HI), c.canvas.tile(t, Canvas.Encoding.HI), "c shares alike");
        }
        assertEquals(0, a.canvas.ownMaps() + b.canvas.ownMaps(), "the colours of a tile shared alike are shared");
        check("drawn alike", a, b, c);
        // b draws over tile 4: a new tile there; a keeps its own; the rest are still one.
        byte[] a4 = a.canvas.tile(4, Canvas.Encoding.HI);
        paint(b, noise(r, 30, 30), 30, 30, 140, 150);
        assertNotSame(a4, b.canvas.tile(4, Canvas.Encoding.HI));
        assertSame(a4, a.canvas.tile(4, Canvas.Encoding.HI));
        assertSame(a.canvas.tile(0, Canvas.Encoding.HI), b.canvas.tile(0, Canvas.Encoding.HI));
        check("b drew", a, b, c);
        // a draws the same pixels there: it then shows b's tile.
        int[] patch = new int[30 * 30];
        for (int j = 0; j < 30; j++) System.arraycopy(b.picture, (150 + j) * W + 140, patch, j * 30, 30);
        paint(a, patch, 30, 30, 140, 150);
        assertSame(b.canvas.tile(4, Canvas.Encoding.HI), a.canvas.tile(4, Canvas.Encoding.HI), "the same pixels again: b's tile");
        check("a drew the same", a, b, c);
    }

    private static java.awt.image.BufferedImage toImage(int[] px, int w, int h) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, px, 0, w);
        return img;
    }
}
