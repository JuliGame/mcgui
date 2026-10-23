package ai.resourcepack.ui.html.style;

import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.html.css.Selector;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionTest {
    @Test
    void mixesNumbersColoursAndTransforms() {
        assertEquals("0.5", Motion.mix("opacity", "0", "1", 0.5));
        assertEquals("rgba(128, 0, 128, 1)", Motion.mix("color", "red", "#0000ff", 0.5));
        assertEquals("rotate(45deg)", Motion.mix("transform", "none", "rotate(90deg)", 0.5));
        assertEquals("translateX(15px) scale(3)", Motion.mix("transform", "translateX(10px) scale(2)", "translateX(20px) scale(4)", 0.5));
        assertEquals("scale(1.5)", Motion.mix("transform", "none", "scale(2)", 0.5));
        // Different shapes cannot be mixed: they change halfway.
        assertEquals("block", Motion.mix("display", "block", "flex", 0.4));
        assertEquals("flex", Motion.mix("display", "block", "flex", 0.6));
    }

    @Test
    void easesAsCssDoes() {
        assertEquals(0.5, Motion.Easing.parse("linear").at(0.5), 1e-9);
        assertEquals(0.5, Motion.Easing.parse("ease-in-out").at(0.5), 1e-3);
        assertTrue(Motion.Easing.parse("ease-in").at(0.5) < 0.5);
        assertEquals(0.5, Motion.Easing.parse("steps(4)").at(0.6), 1e-9);
        assertEquals(1, Motion.Easing.parse("step-start").at(0.01), 1e-9);
    }

    @Test
    void readsTheShorthands() {
        Map<String, String> out = new LinkedHashMap<>();
        Motion.expandAnimation("spin 2s linear infinite, pulse 500ms 1s alternate both", out::put);
        assertEquals("spin, pulse", out.get("animation-name"));
        assertEquals("2s, 500ms", out.get("animation-duration"));
        assertEquals("0s, 1s", out.get("animation-delay"));
        assertEquals("linear, ease", out.get("animation-timing-function"));
        assertEquals("infinite, 1", out.get("animation-iteration-count"));
        assertEquals("normal, alternate", out.get("animation-direction"));
        assertEquals("none, both", out.get("animation-fill-mode"));
        out.clear();
        Motion.expandTransition("background-color .2s ease-out, transform 300ms cubic-bezier(.2, 0, .2, 1) 50ms", out::put);
        assertEquals("background-color, transform", out.get("transition-property"));
        assertEquals(".2s, 300ms", out.get("transition-duration"));
        assertEquals("ease-out, cubic-bezier(.2, 0, .2, 1)", out.get("transition-timing-function"));
        assertEquals("0s, 50ms", out.get("transition-delay"));
    }

    private static final String PAGE = """
            <style>
              @keyframes fade { from { opacity: 0 } to { opacity: 1 } }
              @keyframes grow { 0% { width: 100px } 50% { width: 300px } 100% { width: 200px } }
              #a { animation: fade 2s linear; }
              #b { animation: grow 2s linear forwards; height: 10px; }
              #c { animation: fade 1s linear 1s; }
            </style>
            <div id="a">a</div><div id="b"></div><div id="c">c</div>
            """;

    /** The page drawn at [seconds] after it was shown. */
    private static HtmlRenderer.Frame at(double seconds) {
        Page page = Page.of(PAGE, Path.of("motion.html").toAbsolutePath(), new Page.Loader(false, null));
        assertTrue(page.moves());
        Motion.set(Motion.at(seconds));
        try {
            return HtmlRenderer.render(page, page.document.clone(), 400, 300, 1, 0, Selector.State.NONE);
        } finally {
            Motion.clear();
        }
    }

    private static Style style(HtmlRenderer.Frame f, String id) {
        return f.style(f.document().getElementById(id));
    }

    @Test
    void animatesThroughTheKeyframes() {
        HtmlRenderer.Frame half = at(1);
        assertEquals(0.5, style(half, "a").opacity, 1e-6);
        assertEquals(300, f(style(half, "b").width), 1e-6);
        // Delayed by a second, and no fill: its own value until then, and the first frame's from then on.
        assertEquals(1, style(at(0.5), "c").opacity, 1e-6);
        assertEquals(0, style(half, "c").opacity, 1e-6);
        HtmlRenderer.Frame later = at(1.5);
        assertEquals(0.75, style(later, "a").opacity, 1e-6);
        assertEquals(250, f(style(later, "b").width), 1e-6);
        assertEquals(0.5, style(later, "c").opacity, 1e-6);
        HtmlRenderer.Frame done = at(5);
        assertEquals(1, style(done, "a").opacity, 1e-6);
        // fill-mode forwards keeps the last frame.
        assertEquals(200, f(style(done, "b").width), 1e-6);
    }

    @Test
    void aTransitionRunsFromTheOldValueToTheNew() {
        Page page = Page.of("<style>#t { width: 100px; height: 10px; background-color: #000000; transition: width 1s linear, background-color 1s linear }"
                + " #t:hover { width: 300px; background-color: #ffffff }</style><div id=t></div>", Path.of("t.html").toAbsolutePath(), new Page.Loader(false, null));
        var doc = page.document.clone();
        var t = doc.getElementById("t");
        Motion m = Motion.at(0);
        Motion.set(m);
        try {
            HtmlRenderer.Frame f = HtmlRenderer.render(page, doc, 400, 300, 1, 0, Selector.State.NONE);
            assertEquals(100, f(style(f, "t").width), 1e-6);
            // Hovered: it sets off from where it was...
            f = HtmlRenderer.render(page, doc, 400, 300, 1, 0, HtmlRenderer.state(t, null));
            assertEquals(100, f(style(f, "t").width), 1e-6);
            assertTrue(m.active());
            m.stopAt(0.5);
            f = HtmlRenderer.render(page, doc, 400, 300, 1, 0, HtmlRenderer.state(t, null));
            assertEquals(200, f(style(f, "t").width), 1e-6);
            assertEquals(0xFF808080, style(f, "t").backgroundColor);
            m.stopAt(2);
            f = HtmlRenderer.render(page, doc, 400, 300, 1, 0, HtmlRenderer.state(t, null));
            assertEquals(300, f(style(f, "t").width), 1e-6);
            assertFalse(m.active());
        } finally {
            Motion.clear();
        }
    }

    @Test
    void withoutAClockNothingMoves() {
        Page page = Page.of(PAGE, Path.of("motion.html").toAbsolutePath(), new Page.Loader(false, null));
        HtmlRenderer.Frame f = HtmlRenderer.render(page, page.document.clone(), 400, 300, 1, 0, Selector.State.NONE);
        assertEquals(1, style(f, "a").opacity, 1e-6);
        assertFalse(Page.of("<p>still</p>", Path.of("still.html").toAbsolutePath(), new Page.Loader(false, null)).moves());
    }

    /** A computed width in px. */
    private static double f(Object width) {
        return ((Len) width).of(0);
    }
}
