package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.style.Motion;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page's CSS reaches the elements of an inline SVG, as in a browser: a sheet's rule beats a presentation attribute,
 * an element's own style attribute beats a sheet's rule, values inherit through the SVG and into it from the HTML round
 * it, and :hover rules repaint it.
 */
class InlineSvgCssTest {
    private static final Page.Loader OFFLINE = new Page.Loader(false, null);
    private static final int RED = 0xFFFF0000, GREEN = 0xFF00FF00, BLUE = 0xFF0000FF;

    /** A page with [css], and a 100x100 svg in a div#w holding [inside] (drawn in a 10x10 viewBox). */
    private static Page page(String css, String inside) {
        return Page.of("<html><head><style>html,body{margin:0;background:#fff} svg{display:block;width:100px;height:100px}" + css
                + "</style></head><body><div id=w><svg viewBox=\"0 0 10 10\">" + inside + "</svg></div></body></html>",
                Path.of("svgcss.html").toAbsolutePath(), OFFLINE);
    }

    private static int centre(Page page, Selector.State state) {
        return HtmlRenderer.render(page, page.document, 200, 200, 1, 0, state).image().getRGB(50, 50);
    }

    private static int centre(Page page) {
        return centre(page, Selector.State.NONE);
    }

    private static final String SQUARE = "<polygon class=edge points=\"0,0 10,0 10,10 0,10\"/>";

    @Test
    void aSheetRuleFillsAnSvgElement() {
        assertEquals(GREEN, centre(page(".edge{fill:#0f0}", SQUARE)));
        // fill-opacity too: half green over white.
        int c = centre(page(".edge{fill:#0f0;fill-opacity:.5}", SQUARE));
        assertEquals(0xFF, (c >> 8) & 0xFF);
        assertTrue(((c >> 16) & 0xFF) > 100 && ((c >> 16) & 0xFF) < 156, Integer.toHexString(c));
    }

    @Test
    void precedenceIsTheBrowsers() {
        // A sheet's rule beats the presentation attribute...
        assertEquals(BLUE, centre(page(".edge{fill:#00f}", "<polygon class=edge fill=\"red\" points=\"0,0 10,0 10,10 0,10\"/>")));
        // ...the element's own style attribute beats the sheet...
        assertEquals(RED, centre(page(".edge{fill:#00f}", "<polygon class=edge style=\"fill:red\" points=\"0,0 10,0 10,10 0,10\"/>")));
        // ...and !important in a sheet beats that.
        assertEquals(BLUE, centre(page(".edge{fill:#00f!important}", "<polygon class=edge style=\"fill:red\" points=\"0,0 10,0 10,10 0,10\"/>")));
        // With no rule for it, the attribute stands.
        assertEquals(RED, centre(page(".other{fill:#00f}", "<polygon class=edge fill=\"red\" points=\"0,0 10,0 10,10 0,10\"/>")));
    }

    @Test
    void valuesInheritThroughTheSvgAndIntoIt() {
        // A group's fill reaches the polygon in it.
        assertEquals(GREEN, centre(page("g{fill:#0f0}", "<g>" + SQUARE + "</g>")));
        // The HTML round the svg passes it on too...
        assertEquals(BLUE, centre(page("#w{fill:#00f}", SQUARE)));
        // ...but not over what the svg says itself.
        Page own = Page.of("<html><head><style>html,body{margin:0;background:#fff} svg{display:block;width:100px;height:100px} #w{fill:#00f}"
                + "</style></head><body><div id=w><svg fill=\"red\" viewBox=\"0 0 10 10\">" + SQUARE + "</svg></div></body></html>",
                Path.of("svgcss.html").toAbsolutePath(), OFFLINE);
        assertEquals(RED, centre(own));
    }

    @Test
    void currentColorIsEachElementsOwnColour() {
        assertEquals(GREEN, centre(page("svg{color:#00f} .edge{color:#0f0;fill:currentColor}", SQUARE)));
        assertEquals(BLUE, centre(page("svg{color:#00f}", "<polygon fill=\"currentColor\" points=\"0,0 10,0 10,10 0,10\"/>")));
    }

    @Test
    void aGradientNamedBySheetWorks() {
        String defs = "<defs><linearGradient id=g><stop offset=\"0\" stop-color=\"#0f0\"/><stop offset=\"1\" stop-color=\"#0f0\"/></linearGradient></defs>";
        assertEquals(GREEN, centre(page(".edge{fill:url(#g)}", defs + SQUARE)));
        // stop-color from the sheet, too.
        String styled = "<defs><linearGradient id=g><stop class=s offset=\"0\"/><stop class=s offset=\"1\"/></linearGradient></defs>";
        assertEquals(BLUE, centre(page(".s{stop-color:#00f} .edge{fill:url(#g)}", styled + SQUARE)));
    }

    @Test
    void displayAndVisibilityFromTheSheet() {
        String two = "<polygon fill=\"red\" points=\"0,0 10,0 10,10 0,10\"/><polygon class=top fill=\"#0f0\" points=\"0,0 10,0 10,10 0,10\"/>";
        assertEquals(GREEN, centre(page("", two)));
        assertEquals(RED, centre(page(".top{display:none}", two)));
        assertEquals(RED, centre(page(".top{visibility:hidden}", two)));
    }

    @Test
    void aHoverRuleRestylesTheSvg() {
        Page page = page(".edge{fill:#c8c8c8} #w:hover .edge{fill:#55ff55}", SQUARE);
        assertEquals(0xFFC8C8C8, centre(page));
        assertEquals(0xFF55FF55, centre(page, HtmlRenderer.state(page.document.getElementById("w"), null)));
        assertEquals(0xFFC8C8C8, centre(page));
    }

    /** A transition on an SVG element's fill: its picture is drawn again every frame, though no box moved. */
    @Test
    void aTransitionOnAnSvgFillRedrawsIt() {
        Page page = page(".edge{fill:#000;transition:fill 1s linear} #w:hover .edge{fill:#fff}", SQUARE);
        Document d = page.document.clone();
        Selector.State hovered = HtmlRenderer.state(d.getElementById("w"), null);
        Motion m = Motion.at(0);
        Motion.set(m);
        try {
            HtmlRenderer.render(page, d, 200, 200, 1, 0, Selector.State.NONE);
            HtmlRenderer.render(page, d, 200, 200, 1, 0, hovered);
            m.stopAt(0.5);
            int half = HtmlRenderer.render(page, d, 200, 200, 1, 0, hovered).image().getRGB(50, 50);
            assertTrue((half & 0xFF) > 100 && (half & 0xFF) < 156, Integer.toHexString(half));
            m.stopAt(2);
            assertEquals(0xFFFFFFFF, HtmlRenderer.render(page, d, 200, 200, 1, 0, hovered).image().getRGB(50, 50));
        } finally {
            Motion.clear();
        }
    }

    /** Hovering the HTML round an svg whose polygons the page's CSS lights up repaints in part, to a fresh picture's pixels. */
    @Test
    void hoveringRepaintsAsAFreshPicture() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            body.append("<div class=hex style='left:").append(100 + i * 140).append("px'><svg viewBox=\"0 0 130 150\">")
                    .append("<polygon class=edge points=\"65,0 130,37.5 130,112.5 65,150 0,112.5 0,37.5\"/>")
                    .append("<polygon class=face points=\"65,4 126,39.5 126,110.5 65,146 4,110.5 4,39.5\"/></svg></div>");
        }
        Page page = Page.of("<html><head><style>html,body{margin:0;background:#123}"
                + ".hex{position:absolute;top:100px;width:130px;height:150px} .hex svg{display:block;width:130px;height:150px}"
                + ".edge{fill:#c8c8c8;fill-opacity:.3} .face{fill:#1e1e1e;fill-opacity:.9} .hex:hover .edge{fill:#55ff55;fill-opacity:1}"
                + "</style></head><body>" + body + "</body></html>", Path.of("hexsvg.html").toAbsolutePath(), OFFLINE);
        RepaintTest.Walk w = RepaintTest.walk(page, 2);
        assertTrue(w.worst() <= 1, w.where());
        assertTrue(w.partials() > 0, "no hover was a partial repaint");
    }
}
