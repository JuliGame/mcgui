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
        HtmlRenderer.Frame open = HtmlRenderer.render(LONG, W, H, 1, 0, Selector.State.NONE);
        assertTrue(open.maxScroll() > 3 * H, "the page scrolls several views");
        ScrollCache cache = ScrollCache.paint(LONG, LONG.document, W, H, 1, 0, 0, Selector.State.NONE, ScrollCache.VIEWS);
        int checked = 0;
        // Every 37 px, so the blurs and the backdrop cross the view's edges at many places.
        for (double y = 0; y <= open.maxScroll(); y += 37) {
            if (!cache.covers(y)) cache = ScrollCache.paint(LONG, LONG.document, W, H, 1, y, y - 37, Selector.State.NONE, ScrollCache.VIEWS);
            HtmlRenderer.Frame cut = cache.frame(y);
            HtmlRenderer.Frame fresh = HtmlRenderer.render(LONG, W, H, 1, y, Selector.State.NONE);
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
        assertNull(ScrollCache.paint(fixed, fixed.document, W, H, 1, 100, 0, Selector.State.NONE, ScrollCache.VIEWS));
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
