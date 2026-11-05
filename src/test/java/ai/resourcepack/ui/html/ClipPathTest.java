package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.style.Motion;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** clip-path: the basic shapes cut the box and what is in it, pixels and hit regions alike, through transforms and opacity. */
class ClipPathTest {
    private static final Page.Loader OFFLINE = new Page.Loader(false, null);
    private static final int RED = 0xFFFF0000, WHITE = 0xFFFFFFFF;

    private static Page page(String css, String body) {
        return Page.of("<html><head><style>html,body{margin:0;background:#fff}" + css + "</style></head><body>" + body + "</body></html>",
                Path.of("clip.html").toAbsolutePath(), OFFLINE);
    }

    private static HtmlRenderer.Frame render(Page page) {
        return HtmlRenderer.render(page, page.document, 400, 300, 1, 0, Selector.State.NONE);
    }

    /** The colour at (x, y), opaque (the picture is premultiplied over white, so opaque everywhere). */
    private static int at(HtmlRenderer.Frame f, int x, int y) {
        return f.image().getRGB(x, y);
    }

    /** A 200x200 red box at the top left with [clip] as its clip-path. */
    private static HtmlRenderer.Frame box(String clip) {
        return render(page("#b{width:200px;height:200px;background:red;clip-path:" + clip + "}", "<div id=b></div>"));
    }

    @Test
    void polygonCutsToItsPoints() {
        HtmlRenderer.Frame f = box("polygon(50% 0%, 100% 100%, 0% 100%)");
        assertEquals(RED, at(f, 100, 150));
        assertEquals(RED, at(f, 100, 30));
        assertEquals(WHITE, at(f, 20, 20));
        assertEquals(WHITE, at(f, 180, 20));
        assertEquals(WHITE, at(f, 30, 100));
        // Lengths and percentages mix, and evenodd leaves the overlap of a star's points out.
        f = box("polygon(evenodd, 0 0, 200px 0, 200px 200px, 0 200px, 0 0, 50px 50px, 150px 50px, 150px 150px, 50px 150px, 50px 50px)");
        assertEquals(RED, at(f, 20, 20));
        assertEquals(WHITE, at(f, 100, 100));
    }

    @Test
    void polygonEdgesAreAntialiased() {
        HtmlRenderer.Frame f = box("polygon(0 0, 200px 0, 0 200px)");
        // Along the diagonal, pixels are partly covered: neither red nor white.
        boolean partial = false;
        for (int x = 90; x < 110; x++) {
            int c = at(f, x, 199 - x);
            if (c != RED && c != WHITE) partial = true;
        }
        assertTrue(partial, "a slanted cut edge should be antialiased");
    }

    @Test
    void circleEllipseAndInset() {
        HtmlRenderer.Frame f = box("circle(50px at 100px 100px)");
        assertEquals(RED, at(f, 100, 100));
        assertEquals(RED, at(f, 100, 60));
        assertEquals(WHITE, at(f, 100, 40));
        assertEquals(WHITE, at(f, 10, 10));
        f = box("circle(closest-side at left 60px top 60px)");
        assertEquals(RED, at(f, 60, 10));
        assertEquals(WHITE, at(f, 125, 60));
        f = box("ellipse(50% 25%)");
        assertEquals(RED, at(f, 10, 100));
        assertEquals(WHITE, at(f, 100, 30));
        assertEquals(RED, at(f, 100, 60));
        f = box("inset(20px round 30px)");
        assertEquals(WHITE, at(f, 10, 100));
        assertEquals(RED, at(f, 25, 100));
        assertEquals(WHITE, at(f, 22, 22));
        assertEquals(RED, at(f, 40, 40));
        f = box("inset(10% 20% 30% 40%)");
        assertEquals(WHITE, at(f, 70, 100));
        assertEquals(RED, at(f, 90, 100));
        assertEquals(WHITE, at(f, 170, 100));
        assertEquals(WHITE, at(f, 100, 15));
        assertEquals(WHITE, at(f, 100, 150));
    }

    @Test
    void referenceBoxes() {
        // 20px of padding and a 10px border: the content box is 120x120 from (30, 30).
        String css = "#b{width:120px;height:120px;padding:20px;border:10px solid red;background:red;clip-path:inset(0) content-box}";
        HtmlRenderer.Frame f = render(page(css, "<div id=b></div>"));
        assertEquals(WHITE, at(f, 25, 100));
        assertEquals(RED, at(f, 35, 100));
        f = render(page(css.replace("content-box", "padding-box"), "<div id=b></div>"));
        assertEquals(WHITE, at(f, 5, 100));
        assertEquals(RED, at(f, 15, 100));
        // A reference box alone is its own shape, rounded corners included.
        f = render(page("#b{width:200px;height:200px;border-radius:50%;background:red;clip-path:border-box}", "<div id=b></div>"));
        assertEquals(RED, at(f, 100, 100));
        assertEquals(WHITE, at(f, 10, 10));
    }

    @Test
    void noneAndWhatItCannotReadDrawUncut() {
        for (String clip : new String[]{"none", "url(#mask)", "path('M0 0 L10 10')", "polygon(nonsense)"}) {
            HtmlRenderer.Frame f = box(clip);
            assertEquals(RED, at(f, 5, 5), clip);
            assertEquals(RED, at(f, 195, 195), clip);
        }
    }

    @Test
    void cutsWhatIsInsideToo() {
        HtmlRenderer.Frame f = render(page("#b{width:200px;height:200px;clip-path:circle(50px)}"
                + "#c{position:absolute;left:0;top:0;width:300px;height:300px;background:red}", "<div id=b><div id=c></div></div>"));
        assertEquals(RED, at(f, 100, 100));
        assertEquals(WHITE, at(f, 10, 10));
        assertEquals(WHITE, at(f, 250, 250));
    }

    @Test
    void composesWithTransformAndOpacity() {
        // Moved right 100px and scaled to half about its centre: the circle's centre lands at (200, 100), radius 25.
        HtmlRenderer.Frame f = box("circle(50px); transform:translate(100px,0) scale(.5)");
        assertEquals(RED, at(f, 200, 100));
        assertEquals(RED, at(f, 200, 80));
        assertEquals(WHITE, at(f, 200, 70));
        assertEquals(WHITE, at(f, 100, 100));
        f = box("circle(50px); opacity:.5");
        int c = at(f, 100, 100);
        assertEquals(0xFF, (c >> 16) & 0xFF);
        assertTrue(((c >> 8) & 0xFF) > 100 && ((c >> 8) & 0xFF) < 156, Integer.toHexString(c));
        assertEquals(WHITE, at(f, 10, 10));
    }

    @Test
    void hitTestingFollowsTheShape() {
        Page page = page("#b{width:200px;height:200px;background:red;clip-path:polygon(50% 0%, 100% 100%, 0% 100%)}"
                + "#c{margin-top:100px;width:200px;height:50px;background:blue}", "<div id=b><div id=c></div></div>");
        HtmlRenderer.Frame f = render(page);
        Element b = page.document.getElementById("b"), c = page.document.getElementById("c");
        assertEquals(b, f.elementAt(100, 150));
        assertNotEquals(b, f.elementAt(20, 20));
        assertNotEquals(c, f.elementAt(10, 110), "a child is cut by its parent's clip-path for the pointer too");
        assertEquals(c, f.elementAt(100, 120));
    }

    @Test
    void hitTestingFollowsATransformedShape() {
        Page page = page("#b{width:200px;height:200px;background:red;clip-path:circle(50px);transform:translate(100px,0)}", "<div id=b></div>");
        HtmlRenderer.Frame f = render(page);
        Element b = page.document.getElementById("b");
        assertEquals(b, f.elementAt(200, 100));
        assertNotEquals(b, f.elementAt(110, 10));
    }

    /** A shape transitioned to another one moves its points; at the end it is the new shape. */
    @Test
    void aTransitionMovesThePoints() {
        Page page = page("#b{width:200px;height:200px;background:red;clip-path:inset(0 0 0 0);transition:clip-path 1s linear}"
                + "#b:hover{clip-path:inset(0 0 0 100px)}", "<div id=b></div>");
        org.jsoup.nodes.Document d = page.document.clone();
        Selector.State hovered = HtmlRenderer.state(d.getElementById("b"), null);
        Motion m = Motion.at(0);
        Motion.set(m);
        try {
            HtmlRenderer.render(page, d, 400, 300, 1, 0, Selector.State.NONE);
            HtmlRenderer.render(page, d, 400, 300, 1, 0, hovered);
            m.stopAt(0.5);
            HtmlRenderer.Frame half = HtmlRenderer.render(page, d, 400, 300, 1, 0, hovered);
            assertEquals(WHITE, at(half, 40, 100));
            assertEquals(RED, at(half, 60, 100));
            m.stopAt(2);
            HtmlRenderer.Frame end = HtmlRenderer.render(page, d, 400, 300, 1, 0, hovered);
            assertEquals(WHITE, at(end, 90, 100));
            assertEquals(RED, at(end, 110, 100));
        } finally {
            Motion.clear();
        }
    }

    /** A hover that scales a clipped hexagon (the radial menu sample) repaints in part to the same pixels as a fresh picture. */
    @Test
    void hoveringClippedShapesRepaintsAsAFreshPicture() {
        Page page = Page.of("<html><head><style>html,body{margin:0;background:#123}"
                + ".hex{position:absolute;width:130px;height:150px;background:rgba(200,200,200,.5);"
                + "clip-path:polygon(50% 0%,100% 25%,100% 75%,50% 100%,0% 75%,0% 25%);display:flex;align-items:center;justify-content:center}"
                + ".hex:hover{background:#5f5;transform:scale(1.07);z-index:5}"
                + ".in{width:calc(100% - 8px);height:calc(100% - 9.2px);background:linear-gradient(#3a3a3a,#181818);"
                + "clip-path:polygon(50% 0%,100% 25%,100% 75%,50% 100%,0% 75%,0% 25%)}"
                + "</style></head><body>"
                + "<div class=hex style='left:100px;top:100px'><div class=in></div></div>"
                + "<div class=hex style='left:230px;top:100px'><div class=in></div></div>"
                + "<div class=hex style='left:165px;top:212.5px'><div class=in></div></div>"
                + "</body></html>", Path.of("hex.html").toAbsolutePath(), OFFLINE);
        RepaintTest.Walk w = RepaintTest.walk(page, 2);
        assertTrue(w.worst() <= 1, w.where());
        assertTrue(w.partials() > 0, "no hover was a partial repaint");
    }

    @Test
    void bandsAgreeOnACut() {
        BandsTest.assertBandsAgree("<html><head><style>body{margin:0;background:#fff}"
                + "div{position:absolute;left:100px;top:100px;width:600px;height:900px;background:linear-gradient(#f06,#06f);"
                + "clip-path:polygon(50% 0%,100% 25%,100% 75%,50% 100%,0% 75%,0% 25%);opacity:.8}"
                + "</style></head><body><div></div></body></html>");
        // A wide blur (worked out small, on the page's grid) cut after it.
        BandsTest.assertBandsAgree("<html><head><style>body{margin:0;background:#fff}"
                + "div{position:absolute;left:100px;top:100px;width:600px;height:900px;background:linear-gradient(#f06,#06f);"
                + "clip-path:circle(40%);filter:blur(12px)}"
                + "</style></head><body><div></div></body></html>");
    }
}
