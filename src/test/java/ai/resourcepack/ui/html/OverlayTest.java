package ai.resourcepack.ui.html;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.render.Palette;
import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.style.Motion;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An overlay page ({@code <meta name="ui:overlay">}) is painted on nothing: transparent wherever it paints nothing, so a
 * view shows the world there. A page without the meta is still painted on white, and a hover's partial repaint of an
 * overlay page clears what it repaints as a fresh picture would.
 */
class OverlayTest {
    private static final int W = 2048, H = 1152;
    private static final Page.Loader OFFLINE = new Page.Loader(false, null);

    private static int[] pixels(HtmlRenderer.Frame f) {
        return ((DataBufferInt) f.image().getRaster().getDataBuffer()).getData();
    }

    @Test
    void marketOverlayIsAPanelOnNothing() throws IOException {
        Page page = Page.load(Path.of("src/main/resources/pages/market-overlay.html"), OFFLINE);
        assertTrue(page.overlay());
        assertEquals(0x8C080A16, page.overlayTint());
        // ui:height 720 on a 1152-pixel view: the 1024 x 576 stall is 80% of the view, in the middle.
        double scale = H / 720.0;
        HtmlRenderer.Frame f = HtmlRenderer.render(page, page.document.clone(), W, H, scale, 0, Selector.State.NONE);
        int[] px = pixels(f);
        int clear = 0;
        for (int p : px) if (p >>> 24 == 0) clear++;
        double share = clear / (double) px.length;
        System.out.printf("market-overlay: %.1f%% of the view is transparent%n", share * 100);
        // The stall and its 3-pixel ring: 1 - (1030 x 582) / (1280 x 720) = 35%.
        assertTrue(share > 0.345 && share < 0.355, "transparent share " + share);
        assertEquals(0, px[10 * W + 10] >>> 24, "a corner of the view");
        assertEquals(0, px[(H / 2) * W + 100] >>> 24, "left of the stall");
        assertEquals(255, px[(H / 2) * W + W / 2] >>> 24, "the middle of the stall");
    }

    @Test
    void ordinaryPagesAreStillOnWhite() throws IOException {
        Page page = Page.of("<html><body style='margin:0'><div style='width:10px;height:10px;background:red'></div></body></html>",
                Path.of("plain.html").toAbsolutePath(), OFFLINE);
        assertFalse(page.overlay());
        HtmlRenderer.Frame f = HtmlRenderer.render(page, page.document.clone(), 256, 128, 1, 0, Selector.State.NONE);
        for (int p : pixels(f)) assertEquals(255, p >>> 24);
        Page none = Page.of("<html><head><meta name='ui:overlay' content='none'></head><body></body></html>", Path.of("none.html").toAbsolutePath(), OFFLINE);
        assertTrue(none.overlay());
        assertEquals(0, none.overlayTint());
        assertFalse(none.overlayBlur());
        Page blurred = Page.load(Path.of("src/main/resources/pages/market-blur.html"), OFFLINE);
        assertTrue(blurred.overlay());
        assertTrue(blurred.overlayBlur());
        assertEquals(0x4D080A16, blurred.overlayTint());
    }

    /**
     * At 2x, where pixel edges are whole. At the demo's own 1.6x a hover of its close button repaints one pixel column
     * of the stall's rounded, overflow-hidden edge differently from a fresh picture, on an opaque page of the same
     * layout too: a partial repaint's clip changes how Java2D rasterises the overflow clip (see HtmlRenderer.paintBands).
     */
    @Test
    void hoverRepaintsOfAnOverlayMatchAFreshPicture() throws IOException {
        Page page = Page.load(Path.of("src/main/resources/pages/market-overlay.html"), OFFLINE);
        RepaintTest.Walk w = RepaintTest.walk(page, 2.0);
        assertTrue(w.partials() > 0, "no hover was a partial repaint");
        assertTrue(w.worst() <= 1, "a partial repaint differs from a fresh picture, " + w.where());
    }

    /**
     * Hovers whose repaint reaches past the cards onto the transparent part (a glow round a card): however the repaint
     * is painted, it must start from nothing there too, as a fresh picture does, not from white.
     */
    @Test
    void hoverRepaintsNextToTheTransparentPartStayTransparent() {
        Page page = Page.of("""
                <meta name="ui:overlay" content="rgba(8, 10, 22, 0.5)">
                <style>
                  html, body { margin: 0; height: 100%; }
                  .card { position: absolute; top: 120px; width: 160px; height: 200px; border-radius: 12px; background: #24304a; }
                  .card:hover { background: #34507a; box-shadow: 0 0 24px 6px rgba(255, 200, 80, .8); }
                </style>
                <div class="card" style="left: 80px"></div><div class="card" style="left: 400px"></div>
                """, Path.of("glow.html").toAbsolutePath(), OFFLINE);
        assertTrue(page.overlay());
        HtmlRenderer.Frame f = HtmlRenderer.render(page, page.document.clone(), W, H, 2, 0, Selector.State.NONE);
        // Just left of the first card, where its glow will reach: nothing there before the hover.
        assertEquals(0, pixels(f)[(2 * 220) * W + 2 * 70] >>> 24);
        RepaintTest.Walk w = RepaintTest.walk(page, 2.0);
        assertEquals(w.steps(), w.partials(), "hovers that were not partial repaints");
        assertTrue(w.worst() <= 1, "a partial repaint differs from a fresh picture, " + w.where());
    }

    private static final String SLIDING = """
            <meta name="ui:overlay" content="rgba(8, 10, 22, 0.5)">
            <style>
              @keyframes slide { from { transform: translateX(0) } to { transform: translateX(300px) } }
              html, body { margin: 0; height: 100%; }
              #panel { position: absolute; left: 20px; top: 40px; width: 120px; height: 120px; background: #c04020;
                border-radius: 12px; animation: slide 1s linear forwards; }
            </style>
            <div id="panel"></div>
            """;

    /**
     * An overlay page that moves is drawn whole at each moment (Session, Motion) into an image that held something else
     * (a pooled one): every picture is the page on nothing, and the canvas the session draws them into, as a view's,
     * stays exactly what a fresh canvas would make of that one picture. So where the panel was is colour 0 again once
     * it has moved on, and what was transparent stays colour 0 from frame to frame.
     */
    @Test
    void anOverlayThatMovesIsOnNothingAtEveryMoment() {
        Page page = Page.of(SLIDING, Path.of("sliding.html").toAbsolutePath(), OFFLINE);
        assertTrue(page.overlay());
        assertTrue(page.moves());
        int cols = 4, rows = 2, w = cols * 128, h = rows * 128;
        Palette palette = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));
        BufferedImage into = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        java.util.Arrays.fill(((DataBufferInt) into.getRaster().getDataBuffer()).getData(), 0xFFFF0000);
        Canvas shown = new Canvas(palette, cols, rows);
        for (double t : new double[]{0, 0.5, 1, 0}) {
            Motion.set(Motion.at(t));
            HtmlRenderer.Frame f, fresh;
            try {
                f = HtmlRenderer.render(page, page.document.clone(), into, 1, 0, Selector.State.NONE, 0);
                fresh = HtmlRenderer.render(page, page.document.clone(), w, h, 1, 0, Selector.State.NONE);
            } finally {
                Motion.clear();
            }
            int[] px = pixels(f);
            int left = 20 + (int) Math.round(300 * t);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    boolean panel = x >= left && x < left + 120 && y >= 40 && y < 160;
                    // Inside the panel's rounded corners only the straight edges are certain.
                    boolean corner = (x < left + 12 || x >= left + 108) && (y < 52 || y >= 148);
                    if (!panel) assertEquals(0, px[y * w + x], "t=" + t + ": transparent at " + x + "," + y);
                    else if (!corner) assertEquals(255, px[y * w + x] >>> 24, "t=" + t + ": opaque at " + x + "," + y);
                }
            }
            shown.transparency(page.overlay());
            shown.tint(page.overlayTint());
            shown.draw(f.image(), 0, 0);
            Canvas once = new Canvas(palette, cols, rows);
            once.transparency(true);
            once.tint(page.overlayTint());
            once.draw(fresh.image(), 0, 0);
            for (int i = 0; i < cols * rows; i++) {
                for (Canvas.Encoding e : new Canvas.Encoding[]{Canvas.Encoding.HI_OWN, Canvas.Encoding.PLAIN}) {
                    assertArrayEquals(once.tile(i, e), shown.tile(i, e), "t=" + t + ": tile " + i + " " + e);
                }
            }
            // The top-left map holds the panel at the start and nothing once it has slid away, the top-right one the reverse.
            int empty = t == 1 ? 0 : t == 0 ? 3 : -1;
            if (empty >= 0) {
                byte[] hi = shown.tile(empty, Canvas.Encoding.HI);
                for (int i = 0; i < hi.length; i++) {
                    if (i != 0 && i != 1 && i != 128 && i != 129) assertEquals(Palette.CLEAR_ID, hi[i], "t=" + t + ": map " + empty + " pixel " + i);
                }
            }
        }
    }
}
