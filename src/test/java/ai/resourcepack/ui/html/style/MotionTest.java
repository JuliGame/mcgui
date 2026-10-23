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

    /** [html] drawn [state] with [m] current. */
    private static HtmlRenderer.Frame draw(Page page, org.jsoup.nodes.Document doc, Selector.State state, Motion m) {
        Motion.set(m);
        try {
            return HtmlRenderer.render(page, doc, 400, 300, 1, 0, state);
        } finally {
            Motion.clear();
        }
    }

    private static Page page(String html) {
        return Page.of(html, Path.of("m.html").toAbsolutePath(), new Page.Loader(false, null));
    }

    @Test
    void anEndNoKeyframeGivesIsTheElementsOwnValueOrTheInitialOne() {
        Page page = page("<style>@keyframes dim { to { opacity: .2 } } @keyframes turn { to { transform: rotate(360deg) } }"
                + " #a { animation: dim 1s linear } #b { opacity: .6; animation: dim 1s linear } #c { animation: turn 1s linear infinite }</style>"
                + "<div id=a>a</div><div id=b>b</div><div id=c>c</div>");
        HtmlRenderer.Frame f = draw(page, page.document.clone(), Selector.State.NONE, Motion.at(0.5));
        assertEquals(0.6, style(f, "a").opacity, 1e-6);
        assertEquals(0.4, style(f, "b").opacity, 1e-6);
        assertEquals("rotate(180deg)", style(f, "c").transform);
    }

    @Test
    void animationsThatRepeatRunOnThePagesClockAndThoseThatEndOnTheViewers() {
        Page page = page("<style>@keyframes fade { from { opacity: 0 } to { opacity: 1 } }"
                + " #loop { animation: fade 1s linear infinite } #once { animation: fade 2s linear }</style><div id=loop></div><div id=once></div>");
        Motion m = new Motion();
        m.moment(0.5, 10.25);
        HtmlRenderer.Frame f = draw(page, page.document.clone(), Selector.State.NONE, m);
        assertEquals(0.25, style(f, "loop").opacity, 1e-6);
        assertEquals(0.25, style(f, "once").opacity, 1e-6);
        assertTrue(m.active());
        assertTrue(m.personal(), "an animation that ends runs on the viewer's clock");
        m.moment(3, 20.75);
        f = draw(page, f.document(), Selector.State.NONE, m);
        assertEquals(0.75, style(f, "loop").opacity, 1e-6);
        assertTrue(m.active());
        assertFalse(m.personal(), "only an animation that repeats for ever moves: the page's");
        assertEquals(java.util.Set.of(f.document().getElementById("loop")), m.moved());
    }

    @Test
    void anAnimationAppliedLaterStartsWhenItIsApplied() {
        Page page = page("<style>@keyframes fade { from { opacity: 0 } to { opacity: 1 } } #t { height: 10px } #t:hover { animation: fade 1s linear }</style><div id=t></div>");
        var doc = page.document.clone();
        var t = doc.getElementById("t");
        Motion m = new Motion();
        m.moment(0, 0);
        draw(page, doc, Selector.State.NONE, m);
        m.moment(3, 3);
        assertEquals(0, style(draw(page, doc, HtmlRenderer.state(t, null), m), "t").opacity, 1e-6);
        m.moment(3.5, 3.5);
        assertEquals(0.5, style(draw(page, doc, HtmlRenderer.state(t, null), m), "t").opacity, 1e-6);
        // Taken away and applied again, it starts again.
        m.moment(4, 4);
        draw(page, doc, Selector.State.NONE, m);
        m.moment(4.25, 4.25);
        assertEquals(0, style(draw(page, doc, HtmlRenderer.state(t, null), m), "t").opacity, 1e-6);
    }

    @Test
    void aTransitionOfAPropertyNotDeclaredStartsFromItsInitialValue() {
        Page page = page("<style>#t { height: 10px; transition: transform 1s linear } #t:hover { transform: translateX(100px) }</style><div id=t></div>");
        var doc = page.document.clone();
        Motion m = Motion.at(0);
        draw(page, doc, Selector.State.NONE, m);
        draw(page, doc, HtmlRenderer.state(doc.getElementById("t"), null), m);
        m.stopAt(0.25);
        assertEquals("translateX(25px)", style(draw(page, doc, HtmlRenderer.state(doc.getElementById("t"), null), m), "t").transform);
    }

    @Test
    void aCopyTakenAtAnotherMomentRunsFromThere() {
        Page page = page("<style>#t { width: 100px; height: 10px; transition: width 1s linear } #t:hover { width: 300px }</style><div id=t></div>");
        var doc = page.document.clone();
        var t = doc.getElementById("t");
        Motion m = new Motion();
        m.moment(0, 0);
        draw(page, doc, Selector.State.NONE, m);
        // Drawn ahead at 2 s, on a copy: the session's own motion is left as it was.
        m.moment(2, 2);
        Motion ahead = m.copy();
        assertEquals(100, f(style(draw(page, doc, HtmlRenderer.state(t, null), ahead), "t").width), 1e-6);
        assertTrue(ahead.active());
        assertFalse(m.active());
        // Shown at 5 s: the transition starts then.
        Motion taken = ahead.copyAt(5);
        taken.moment(5.5, 5.5);
        assertEquals(200, f(style(draw(page, doc, HtmlRenderer.state(t, null), taken), "t").width), 1e-6);
    }

    @Test
    void anElementMidTransitionSharesItsStyleWithNoSibling() {
        // The siblings have the same rules, and the transition is only declared while hovered. Hovered a second time, the
        // first has a transition to its hover under way, which goes on once the pointer leaves (nothing then says to stop
        // it): left half way, it is styled with the same rules as its sibling, but moving.
        Page page = page("<style>.t { width: 100px; height: 10px } .t:hover { width: 300px; transition: width 1s linear }</style>"
                + "<div><div class=t id=a></div><div class=t id=b></div></div>");
        var doc = page.document.clone();
        var a = doc.getElementById("a");
        Motion m = Motion.at(0);
        draw(page, doc, Selector.State.NONE, m);
        draw(page, doc, HtmlRenderer.state(a, null), m);
        draw(page, doc, Selector.State.NONE, m);
        draw(page, doc, HtmlRenderer.state(a, null), m);
        m.stopAt(0.5);
        // A new copy of the document (as a click gives a session), so nothing is kept from the last pass over it.
        HtmlRenderer.Frame f = draw(page, doc.clone(), Selector.State.NONE, m);
        assertEquals(200, f(style(f, "a").width), 1e-6);
        assertEquals(100, f(style(f, "b").width), 1e-6);
    }

    /** A computed width in px. */
    private static double f(Object width) {
        return ((Len) width).of(0);
    }
}
