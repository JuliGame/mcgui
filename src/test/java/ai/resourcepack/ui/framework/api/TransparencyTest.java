package ai.resourcepack.ui.framework.api;

import ai.resourcepack.ui.framework.render.Palette;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transparent canvas (an overlay page's): pixels under half alpha become map colour 0 in every encoding (whole 2×2
 * blocks in the hi-colour ones), markers and a view's size stay, an own view carries its tint, and nothing changes for
 * an opaque picture or a canvas with transparency off.
 */
class TransparencyTest {
    private static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));
    private static final int COLS = 2, ROWS = 2, W = COLS * 128, H = ROWS * 128;

    /** A panel in the middle of a transparent picture: opaque in x, y in [64, 192), transparent elsewhere. */
    private static BufferedImage panel() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        for (int y = 64; y < 192; y++) for (int x = 64; x < 192; x++) img.setRGB(x, y, 0xFF000040 | (x * 7 & 255) << 16 | (y * 5 & 255) << 8);
        return img;
    }

    private static boolean marker(int i) {
        return i == 0 || i == 1 || i == 128 || i == 129;
    }

    @Test
    void noColourIsTheTransparentMark() {
        byte[] ycc = new byte[3];
        for (int rgb = 0; rgb < 1 << 24; rgb++) {
            Palette.rgbToYcc(rgb, ycc, 0);
            if (Palette.isClear(ycc, 0)) throw new AssertionError("RGB " + Integer.toHexString(rgb) + " converts to the transparent mark");
        }
    }

    @Test
    void transparentAreaIsColourZero() {
        Canvas c = new Canvas(PALETTE, COLS, ROWS);
        c.transparency(true);
        c.draw(panel(), 0, 0);
        // Tile 0 is the top-left map: transparent but for its bottom-right quarter, which is the panel.
        byte[] hi = c.tile(0, Canvas.Encoding.HI);
        byte[] plain = c.tile(0, Canvas.Encoding.PLAIN);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                int i = y * 128 + x;
                boolean inside = x >= 64 && y >= 64;
                if (marker(i)) continue;
                assertEquals(inside, hi[i] != Palette.CLEAR_ID, "HI pixel " + x + "," + y);
                assertEquals(inside, plain[i] != Palette.CLEAR_ID, "PLAIN pixel " + x + "," + y);
            }
        }
        // The marker is always there, so the shader knows the map is ours, transparent or not.
        for (int m = 0; m < 4; m++) assertEquals(PALETTE.markerId[m], hi[m < 2 ? m : 126 + m]);
    }

    @Test
    void viewsCarryTheTint() {
        Canvas c = new Canvas(PALETTE, COLS, ROWS);
        c.transparency(true);
        int tint = 0x8C0A0816;
        c.tint(tint);
        c.draw(panel(), 0, 0);
        for (Canvas.Encoding e : new Canvas.Encoding[]{Canvas.Encoding.HI_OWN, Canvas.Encoding.HI_TILED}) {
            byte[] own = c.tile(3, e);
            int[] want = {tint >> 16 & 255, tint >> 8 & 255, tint & 255, tint >>> 24};
            int[] at = {4, 5, 132, 133};
            for (int k = 0; k < 4; k++) {
                int symbol = symbolOf(own[at[k]]);
                assertEquals(Math.round(want[k] * (Palette.SYMBOLS - 1) / 255f), symbol, e + " tint channel " + k);
            }
        }
        // A new tint goes out with the next drawing, as new arrays (the sender compares them by identity).
        byte[] before = c.tile(3, Canvas.Encoding.HI_TILED);
        c.tint(0);
        assertTrue(before == c.tile(3, Canvas.Encoding.HI_TILED), "the tint changed before a drawing published it");
        c.draw(panel(), 0, 0);
        byte[] after = c.tile(3, Canvas.Encoding.HI_TILED);
        assertNotSame(before, after);
        assertEquals(0, symbolOf(after[133]));
    }

    @Test
    void overlaysSendOnlyTheTilesTheirPanelCovers() {
        // The panel reaches into every tile; one in the bottom-right corner only into the last.
        Canvas c = new Canvas(PALETTE, COLS, ROWS);
        c.transparency(true);
        c.draw(panel(), 0, 0);
        for (int t = 0; t < COLS * ROWS; t++) assertTrue(c.snapshot().opaque(t), "tile " + t);
        BufferedImage corner = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        for (int y = 200; y < 220; y++) for (int x = 200; x < 220; x++) corner.setRGB(x, y, 0xFF336699);
        Canvas small = new Canvas(PALETTE, COLS, ROWS);
        small.transparency(true);
        small.draw(corner, 0, 0);
        for (int t = 0; t < COLS * ROWS; t++) assertEquals(t == 3, small.snapshot().opaque(t), "tile " + t);
        // Nothing transparent: every tile. Nothing opaque: none.
        Canvas opaque = new Canvas(PALETTE, COLS, ROWS);
        opaque.draw(panel(), 0, 0);
        for (int t = 0; t < COLS * ROWS; t++) assertTrue(opaque.snapshot().opaque(t));
        Canvas clear = new Canvas(PALETTE, COLS, ROWS);
        clear.transparency(true);
        clear.draw(new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE), 0, 0);
        for (int t = 0; t < COLS * ROWS; t++) assertFalse(clear.snapshot().opaque(t));
    }

    @Test
    void signatureMapCarriesTintAndFlags() {
        int tint = 0x8C0A0816;
        byte[] sig = PALETTE.markSignature(10, 6, tint, 3);
        // A tile of a client-placed view (both bottom markers the fourth), of the view's size, at the signature's place.
        assertEquals(PALETTE.markerId[0], sig[0]);
        assertEquals(PALETTE.markerId[1], sig[1]);
        assertEquals(PALETTE.markerId[3], sig[128]);
        assertEquals(PALETTE.markerId[3], sig[129]);
        assertEquals(10, symbolOf(sig[2]));
        assertEquals(6, symbolOf(sig[3]));
        assertEquals(ai.resourcepack.ui.framework.pack.Shaders.SIGNATURE_TILE, symbolOf(sig[130]));
        assertEquals(ai.resourcepack.ui.framework.pack.Shaders.SIGNATURE_TILE, symbolOf(sig[131]));
        int[] want = {tint >> 16 & 255, tint >> 8 & 255, tint & 255, tint >>> 24};
        int[] at = {4, 5, 132, 133};
        for (int k = 0; k < 4; k++) assertEquals(Math.round(want[k] * (Palette.SYMBOLS - 1) / 255f), symbolOf(sig[at[k]]), "tint channel " + k);
        assertEquals(3, symbolOf(sig[6]), "flags");
        assertEquals(sig[6], PALETTE.signatureFlags(3));
        // Nothing else: the map draws no picture.
        for (int i = 0; i < sig.length; i++) {
            boolean data = i < 7 || (i >= 128 && i < 134);
            if (!data) assertEquals(Palette.CLEAR_ID, sig[i], "pixel " + i);
        }
    }

    private static int symbolOf(byte id) {
        for (int s = 0; s < Palette.SYMBOLS; s++) if (PALETTE.symbolId[s] == id) return s;
        throw new AssertionError("not a symbol: " + (id & 255));
    }

    @Test
    void opaqueOrOffIsUnchanged() {
        BufferedImage img = panel();
        Canvas off = new Canvas(PALETTE, COLS, ROWS);
        off.draw(img, 0, 0);
        Canvas plainRgb = new Canvas(PALETTE, COLS, ROWS);
        int[] rgb = img.getRGB(0, 0, W, H, null, 0, W);
        plainRgb.draw(rgb, W, H, 0, 0);
        for (int t = 0; t < COLS * ROWS; t++) {
            for (Canvas.Encoding e : Canvas.Encoding.values()) {
                byte[] a = off.tile(t, e);
                assertFalse(containsClear(a), "transparency off made a transparent pixel: tile " + t + " " + e);
                assertArrayEquals(plainRgb.tile(t, e), a, "tile " + t + " " + e);
            }
        }
        // An opaque picture on a transparent canvas encodes as on any other.
        Canvas on = new Canvas(PALETTE, COLS, ROWS);
        on.transparency(true);
        on.draw(rgb, W, H, 0, 0);
        for (int t = 0; t < COLS * ROWS; t++) for (Canvas.Encoding e : Canvas.Encoding.values()) assertArrayEquals(plainRgb.tile(t, e), on.tile(t, e));
    }

    private static boolean containsClear(byte[] tile) {
        for (byte b : tile) if (b == Palette.CLEAR_ID) return true;
        return false;
    }

    /** What a wholly transparent map costs on the wire, against a busy one: printed, and far smaller. */
    @Test
    void transparentMapsAreCheap() {
        Canvas c = new Canvas(PALETTE, COLS, ROWS);
        c.transparency(true);
        c.draw(new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE), 0, 0);
        int clear = deflated(c.tile(0, Canvas.Encoding.HI_TILED));
        Canvas busy = new Canvas(PALETTE, COLS, ROWS);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        java.util.Random r = new java.util.Random(1);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) img.setRGB(x, y, (x * 3 + y) << 8 | r.nextInt(32));
        busy.draw(img, 0, 0);
        int full = deflated(busy.tile(0, Canvas.Encoding.HI_TILED));
        System.out.println("A transparent map deflates to " + clear + " bytes; a busy one to " + full);
        assertTrue(clear < 100, "a transparent map deflates to " + clear + " bytes");
        assertNotEquals(0, full);
    }

    private static int deflated(byte[] data) {
        Deflater d = new Deflater();
        d.setInput(data);
        d.finish();
        byte[] out = new byte[data.length * 2];
        int n = 0;
        while (!d.finished()) n += d.deflate(out, n, out.length - n);
        d.end();
        return n;
    }

    @Test
    void halfTransparentBlockRule() {
        // One transparent pixel in a 2x2 block: the block stays opaque. Two: the block is transparent.
        Canvas c = new Canvas(PALETTE, COLS, ROWS);
        c.transparency(true);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] all = new int[W * H];
        Arrays.fill(all, 0xFF808080);
        img.setRGB(0, 0, W, H, all, 0, W);
        img.setRGB(10, 10, 0);
        img.setRGB(20, 20, 0);
        img.setRGB(21, 20, 0);
        c.draw(img, 0, 0);
        byte[] hi = c.tile(0, Canvas.Encoding.HI);
        assertNotEquals(Palette.CLEAR_ID, hi[10 * 128 + 10]);
        assertNotEquals(Palette.CLEAR_ID, hi[11 * 128 + 11]);
        assertEquals(Palette.CLEAR_ID, hi[20 * 128 + 20]);
        assertEquals(Palette.CLEAR_ID, hi[21 * 128 + 21]);
    }
}
