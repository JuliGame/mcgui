package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScrollCacheTest {
    private static final Page.Loader OFFLINE = new Page.Loader(false, null);
    private static final int W = 640, H = 360;

    private static Page page(String css, String body) {
        return Page.of("<html><head><style>" + css + "</style></head><body>" + body + "</body></html>", Path.of("test.html").toAbsolutePath(), OFFLINE);
    }

    /** No background of its own (the white under the page shows), and blurs that the view's edges cross as it scrolls. */
    private static final Page LONG = page("""
            body { margin: 0; font: 16px sans-serif; }
            .card { margin: 20px; height: 140px; border-radius: 12px; background: #e0e7ff; box-shadow: 0 10px 30px rgba(0,0,0,.4); }
            .blob { margin: 0 200px; height: 160px; border-radius: 50%; background: #db2777; filter: blur(20px); }
            .glass { position: relative; margin: -120px 40px 0; height: 120px; background: rgba(255,255,255,.3); backdrop-filter: blur(10px); }
            """, "<div class=card>One</div><div class=card>Two</div><div class=blob></div><div class=glass>Glass</div>"
            + "<div class=card>Three</div><div class=blob></div><div class=card>Four</div><div class=card>Five</div><div class=blob></div>"
            + "<div class=card>Six</div><div class=card>Seven</div>");

    @Test
    void viewsCutFromATallPictureMatchViewsDrawnOnTheirOwn() {
        assertCutsMatch(LONG, 1);
    }

    /** Hard-edged stripes, level, slanted, in rings and tiled, whose edges fall on whole device pixels: one row out shows as 255 levels. */
    static final String STRIPES_CSS = """
            body { margin: 0; height: 2400px; background: repeating-linear-gradient(0deg, #000 0 37px, #fff 37px 74px); }
            .slant { margin: 100px 40px; height: 200px; background: repeating-linear-gradient(30deg, #c00 0 13px, #fff 13px 26px); }
            .ring { margin: 0 60px; height: 300px; background: repeating-radial-gradient(ellipse at 40% 30%, #c00 0 13px, #fff 13px 26px); }
            .tiles { margin: 0 80px; height: 600px; background: linear-gradient(#000 50%, #0c0 50%) 0 0 / 40px 37px; }
            .tiles:hover, .slant:hover, .ring:hover { outline: 3px solid #00f; }
            .out { margin: 20px 101px; width: 150px; height: 77px; background: #3b82f6; }
            .out:hover { background: #ef4444; }
            """;
    static final String STRIPES_BODY = "<div class=slant><div class=out></div></div><div class=ring><div class=out></div></div><div class=tiles><div class=out></div></div>";

    @Test
    void hardEdgedStripesCutFromATallPictureMatchViewsDrawnOnTheirOwn() {
        assertCutsMatch(page(STRIPES_CSS, STRIPES_BODY), 2);
    }

    /** Every 37 CSS px down the page, so whatever the page has crosses the view's edges at many places. */
    private static void assertCutsMatch(Page page, double scale) {
        int w = (int) (W * scale), h = (int) (H * scale);
        HtmlRenderer.Frame open = HtmlRenderer.render(page, w, h, scale, 0, Selector.State.NONE);
        assertTrue(open.maxScroll() > 3 * H, "the page scrolls several views");
        ScrollCache cache = ScrollCache.paint(page, page.document, w, h, scale, 0, 0, Selector.State.NONE, ScrollCache.VIEWS, 0);
        int checked = 0;
        for (double y = 0; y <= open.maxScroll(); y += 37) {
            if (!cache.covers(y)) cache = ScrollCache.paint(page, page.document, w, h, scale, y, y - 37, Selector.State.NONE, ScrollCache.VIEWS, 0);
            HtmlRenderer.Frame cut = cache.frame(y);
            HtmlRenderer.Frame fresh = HtmlRenderer.render(page, w, h, scale, y, Selector.State.NONE);
            assertEquals(fresh.scrollY(), cut.scrollY());
            int diff = diff(cut.image(), fresh.image());
            assertTrue(diff <= 1, "view at " + y + " differs by " + diff);
            checked++;
        }
        assertTrue(checked > 20);
    }

    @Test
    void aPageWithSomethingFixedIsNotCut() {
        Page fixed = page(".bar { position: fixed; top: 0; height: 40px; width: 100%; background: #333; } .tall { height: 2000px; }",
                "<div class=bar></div><div class=tall></div>");
        assertNull(ScrollCache.paint(fixed, fixed.document, W, H, 1, 100, 0, Selector.State.NONE, ScrollCache.VIEWS, 0));
    }

    private static int diff(BufferedImage a, BufferedImage b) {
        int[] pa = ((DataBufferInt) a.getRaster().getDataBuffer()).getData(), pb = ((DataBufferInt) b.getRaster().getDataBuffer()).getData();
        assertEquals(pa.length, pb.length);
        int worst = 0;
        for (int i = 0; i < pa.length; i++) {
            if (pa[i] == pb[i]) continue;
            for (int sh = 0; sh < 32; sh += 8) worst = Math.max(worst, Math.abs((pa[i] >>> sh & 255) - (pb[i] >>> sh & 255)));
        }
        return worst;
    }
}
