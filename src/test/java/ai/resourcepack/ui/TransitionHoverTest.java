package ai.resourcepack.ui;

import ai.resourcepack.ui.html.HoverAhead;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.html.style.Motion;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.geom.Rectangle2D;
import java.awt.image.DataBufferInt;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hovers on a page whose hover starts a transition, through real {@link Session}s. While nothing on it moves, the page
 * is drawn as one that never moves: its hovers are drawn ahead and repainted in part. But the picture after the hover is
 * the transition's first moment, never its end (drawn ahead, the hover is styled with a copy of the session's motion,
 * which the session takes on with it), each picture while it runs is a picture of its moment, repainted over the last,
 * over a blurred backdrop worked out on the page's grid, and the hover sound fires on entering, as on a page without
 * transitions.
 */
class TransitionHoverTest {
    private static final int COLS = 4, ROWS = 3, W = COLS * 128, H = ROWS * 128;
    private static final String SOUND = "block.note_block.hat";

    @TempDir
    Path dir;
    private SessionRig rig;

    /** Three cards over a blurred picture (blur(10px): a grid blur), each with a tooltip; [move]: their hover is a transition. */
    private static String html(boolean move) {
        return "<html><head><style>html,body{margin:0;height:100%;overflow:hidden;background:#000}"
                + ".pano{position:absolute;left:-24px;top:-24px;right:-24px;bottom:-24px;filter:blur(10px);"
                + "background:linear-gradient(90deg,#000 0 30%,#fff 30% 60%,#f80 60%),linear-gradient(#08f,#0f8)}"
                + ".cards{position:absolute;top:70px;left:30px;display:flex;gap:24px}"
                + ".card{position:relative;width:128px;height:170px;border-radius:10px;background:#2a4a6a;box-shadow:0 6px 12px rgba(0,0,0,.5);transform:translateY(0)"
                + (move ? ";transition:background-color 1s linear,transform 1s linear" : "") + "}"
                + ".card:hover{background:#ffffff;transform:translateY(-8px)}"
                + ".tip{display:none;position:absolute;left:70px;top:-16px;z-index:10;padding:4px 8px;white-space:nowrap;background:#100010;"
                + "border:2px solid #5000ff;color:#fff;font-size:14px}"
                + ".card:hover .tip{display:block}"
                + "</style></head><body><div class=pano></div><div class=cards>"
                + ("<div class=card data-hover-sound=\"" + SOUND + "\"><div class=tip>A card<br>with a tooltip</div></div>").repeat(3)
                + "</div></body></html>";
    }

    @BeforeEach
    void setUp() throws Exception {
        SessionRig.TICK.set(1000);
        Files.writeString(dir.resolve("moving.html"), html(true));
        Files.writeString(dir.resolve("still.html"), html(false));
        rig = new SessionRig(dir, 4);
        assertNotNull(rig.pages.load("moving").get(30, TimeUnit.SECONDS));
        assertNotNull(rig.pages.load("still").get(30, TimeUnit.SECONDS));
        assertTrue(rig.pages.loaded("moving").moves());
        assertFalse(rig.pages.loaded("still").moves());
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Session s, String name) {
        try {
            Field f = Session.class.getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(s);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Session s, String name, Object value) {
        try {
            Field f = Session.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(s, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Session open(String name) {
        Session s = rig.open(rig.plugin(0), name, COLS, ROWS, H);
        rig.quiet();
        return s;
    }

    private void onServer(Runnable r) {
        rig.onServer(r);
        rig.quiet();
    }

    /** The session's last picture, as painted. */
    private static int[] picture(Session s) {
        HtmlRenderer.Frame f = get(s, "frame");
        return ((DataBufferInt) f.image().getRaster().getDataBuffer()).getData().clone();
    }

    private static int[] pixels(HtmlRenderer.Frame f) {
        return ((DataBufferInt) f.image().getRaster().getDataBuffer()).getData();
    }

    /** The largest difference in any channel of any pixel, and where. */
    private static String worst(int[] a, int[] b, int[] out) {
        int worst = 0, at = -1;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == b[i]) continue;
            for (int s = 0; s < 32; s += 8) {
                int d = Math.abs((a[i] >> s & 255) - (b[i] >> s & 255));
                if (d > worst) {
                    worst = d;
                    at = i;
                }
            }
        }
        out[0] = worst;
        return at < 0 ? "nowhere" : "pixel (" + at % W + ", " + at / W + ") by " + worst;
    }

    private static void assertSamePicture(int[] fresh, int[] drawn, String step) {
        int[] w = new int[1];
        String where = worst(fresh, drawn, w);
        assertTrue(w[0] <= 1, step + ": the picture differs from a fresh one at " + where);
    }

    /** Where the [i]th card is, unhovered, in canvas pixels (the scale is 1). */
    private static Rectangle2D card(Page page, int i) {
        Document d = page.document.clone();
        return HtmlRenderer.render(page, d, W, H, 1, 0, HtmlRenderer.state(null, null)).boundsOf(d.select(".card").get(i));
    }

    @Test
    void aHoverThatStartsATransitionIsDrawnAheadAtItsFirstMoment() {
        Page page = rig.pages.loaded("moving");
        Session s = open("moving");
        // The session's clock, stopped, so a fresh picture can be drawn of the same moment.
        set(s, "motion", Motion.at(0));
        onServer(s::render);
        Rectangle2D box = card(page, 1);
        int cx = (int) box.getCenterX(), cy = (int) box.getCenterY();

        // Resting beside the card: nothing on the page moves, so its hovers are drawn ahead, as on a page that never does.
        onServer(() -> s.pointer((int) box.getX() - 8, cy));
        HoverAhead ahead = get(s, "hoverAhead");
        assertTrue(ahead.prepared.get() > 0, "a page whose transitions are not running had no hovers drawn ahead");
        onServer(() -> s.pointer(cx, cy));
        assertTrue(ahead.taken.get() >= 1, "the hover was not the one drawn ahead");

        // The same pictures afresh, on a clock of their own: the page as it opened, then hovered at 0 s, 0.4 s and 1.5 s.
        Document d = page.document.clone();
        Element c = d.select(".card").get(1);
        Motion fresh = Motion.at(0);
        Motion.set(fresh);
        int[] start, partWay;
        try {
            HtmlRenderer.render(page, d, W, H, 1, 0, HtmlRenderer.state(null, null));
            start = pixels(HtmlRenderer.render(page, d, W, H, 1, 0, HtmlRenderer.state(c, null))).clone();
            fresh.stopAt(0.4);
            partWay = pixels(HtmlRenderer.render(page, d, W, H, 1, 0, HtmlRenderer.state(c, null))).clone();
        } finally {
            Motion.clear();
        }
        Document e = page.document.clone();
        int[] end = pixels(HtmlRenderer.render(page, e, W, H, 1, 0, HtmlRenderer.state(e.select(".card").get(1), null))).clone();
        assertTrue((end[cy * W + cx] & 255) > 0xF0, "the hover's end is not white");
        // Not the end: the card's middle is still nearly its own colour, nowhere near white.
        int[] drawn = picture(s);
        int middle = drawn[cy * W + cx];
        assertTrue((middle & 255) < 0x80, "the first picture of the hover shows the card at " + Integer.toHexString(middle) + ", near the transition's end");
        assertSamePicture(start, drawn, "hovered, 0 s");
        // The session took on the motion the hover was drawn ahead with: its transition is under way.
        Motion clock = get(s, "motion");
        assertTrue(clock.active(), "the transition is under way");

        // The next pictures come with the ticks, each a picture of its moment.
        clock.stopAt(0.4);
        int tick = SessionRig.TICK.addAndGet(2);
        onServer(() -> s.tick(tick));
        assertSamePicture(partWay, picture(s), "hovered, 0.4 s");
        clock.stopAt(1.5);
        int later = SessionRig.TICK.addAndGet(2);
        onServer(() -> s.tick(later));
        assertSamePicture(end, picture(s), "hovered, 1.5 s (over)");
        assertFalse(clock.active(), "the transition is over");

        // The sound of the card it came into.
        assertEquals(c.cssSelector(), get(s, "hoverSoundAt"), "the hover sound");
    }

    @Test
    void hoverSoundsFireOnHoversDrawnAhead() {
        Page page = rig.pages.loaded("still");
        Session s = open("still");
        Document d = page.document.clone();
        Rectangle2D one = card(page, 0), two = card(page, 1);
        int y = (int) one.getCenterY();
        onServer(() -> s.pointer((int) one.getX() - 8, y));
        assertNull(get(s, "hoverSoundAt"));
        HoverAhead ahead = get(s, "hoverAhead");
        assertTrue(ahead.prepared.get() > 0, "nothing was drawn ahead");

        onServer(() -> s.pointer((int) one.getCenterX(), y));
        assertTrue(ahead.taken.get() >= 1, "the hover was not the one drawn ahead");
        assertEquals(d.select(".card").get(0).cssSelector(), get(s, "hoverSoundAt"), "entering the first card");
        Document f = page.document.clone();
        assertSamePicture(pixels(HtmlRenderer.render(page, f, W, H, 1, 0, HtmlRenderer.state(f.select(".card").get(0), null))), picture(s), "first card");

        // Straight on to the next card, then out between them.
        onServer(() -> s.pointer((int) two.getCenterX(), y));
        assertEquals(d.select(".card").get(1).cssSelector(), get(s, "hoverSoundAt"), "entering the second card");
        onServer(() -> s.pointer((int) (one.getMaxX() + two.getX()) / 2, y));
        assertNull(get(s, "hoverSoundAt"), "between the cards");
        onServer(() -> s.pointer((int) two.getCenterX(), y));
        assertEquals(d.select(".card").get(1).cssSelector(), get(s, "hoverSoundAt"), "back into the second card");
    }
}
