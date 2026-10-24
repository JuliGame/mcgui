package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.html.HoverAhead;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.html.style.Motion;
import org.jsoup.nodes.Document;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pages that move, through real {@link Session}s: a page whose transitions are not running costs what a page without
 * them does (its first picture is shared, its hovers are drawn ahead and repainted in part), and while something moves
 * each picture repaints only what moves, and still equals a fresh picture of its moment.
 */
class AnimationTest {
    private static final int COLS = 4, ROWS = 3, W = COLS * 128, H = ROWS * 128;

    /** Three cards whose hover fades and lifts them over half a second. */
    private static final String CARDS = "<html><head><style>html,body{margin:0;height:100%;background:#123}"
            + ".grid{display:flex;gap:20px;padding:30px}"
            + ".card{width:100px;height:120px;background:#2a4a6a;border-radius:8px;transition:background-color .5s linear,transform .5s linear}"
            + ".card:hover{background:#ffffff;transform:translateY(-6px)}"
            + "</style></head><body><div class=grid><div class=card></div><div class=card></div><div class=card></div></div></body></html>";

    /** Some text and a small spinner that turns for ever. */
    private static final String SPINNER = "<html><head><style>html,body{margin:0;height:100%;background:linear-gradient(#123,#345)}"
            + "p{font-size:20px;color:#fff;margin:20px}"
            + "@keyframes spin{to{transform:rotate(360deg)}}"
            + ".spin{position:absolute;left:400px;top:280px;width:40px;height:40px;box-sizing:border-box;border:6px solid #456;"
            + "border-top-color:#fb4;border-radius:50%;animation:spin 1s linear infinite}"
            + "</style></head><body><p>Loading the rest of the page...</p><div class=spin></div></body></html>";

    /** A bar whose width grows and shrinks for ever, over some text. */
    private static final String GROW = "<html><head><style>html,body{margin:0;height:100%;background:#123}"
            + "@keyframes grow{from{width:40px}to{width:200px}}"
            + ".bar{height:30px;margin:40px;background:#fb4;animation:grow 1s linear infinite alternate}"
            + "p{font-size:20px;color:#fff;margin:40px}</style></head><body><div class=bar></div><p>Below the bar</p></body></html>";

    /** A spinner on a panel with a backdrop blur, over hard stripes (where a blur's sampling grid shows if it moves). */
    private static final String SPIN_BLUR = "<html><head><style>html,body{margin:0;height:100%;"
            + "background:repeating-linear-gradient(90deg,#000 0 7px,#fff 7px 13px)}"
            + "@keyframes spin{to{transform:rotate(360deg)}}"
            + ".panel{position:absolute;left:300px;top:200px;width:180px;height:150px;border-radius:12px;"
            + "background:rgba(20,30,40,.4);backdrop-filter:blur(8px)}"
            + ".spin{position:absolute;left:370px;top:250px;width:40px;height:40px;box-sizing:border-box;border:6px solid #456;"
            + "border-top-color:#fb4;border-radius:50%;animation:spin 1s linear infinite}"
            + "</style></head><body><div class=panel></div><div class=spin></div></body></html>";

    @TempDir
    Path dir;
    private SessionRig rig;
    private MCGUIPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        SessionRig.TICK.set(1000);
        Files.writeString(dir.resolve("cards.html"), CARDS);
        Files.writeString(dir.resolve("spinner.html"), SPINNER);
        Files.writeString(dir.resolve("grow.html"), GROW);
        Files.writeString(dir.resolve("spinblur.html"), SPIN_BLUR);
        rig = new SessionRig(dir, 4);
        plugin = rig.plugin(16);
        for (String name : new String[]{"cards", "spinner", "grow", "spinblur"}) assertNotNull(rig.pages.load(name).get(30, TimeUnit.SECONDS));
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

    private Session open(String name) {
        Session s = rig.open(plugin, name, COLS, ROWS, H);
        rig.quiet();
        return s;
    }

    private void onServer(Runnable r) {
        rig.onServer(r);
        rig.quiet();
    }

    /** One tick of the server, [ticks] after the last. */
    private void tick(Session s, int ticks) {
        int now = SessionRig.TICK.addAndGet(ticks);
        onServer(() -> s.tick(now));
    }

    private static int[] picture(Session s) {
        HtmlRenderer.Frame f = get(s, "frame");
        return ((DataBufferInt) f.image().getRaster().getDataBuffer()).getData().clone();
    }

    /** [page] drawn afresh with [state] after a picture with nothing hovered, both at [viewer] and [pageClock] seconds. */
    private static int[] fresh(Page page, double viewer, double pageClock, boolean hoverSecondCard) {
        Document d = page.document.clone();
        Motion m = new Motion();
        m.moment(viewer, pageClock);
        Motion.set(m);
        try {
            HtmlRenderer.Frame f = HtmlRenderer.render(page, d, W, H, 1, 0, HtmlRenderer.state(null, null));
            if (hoverSecondCard) f = HtmlRenderer.render(page, d, W, H, 1, 0, HtmlRenderer.state(d.select(".card").get(1), null));
            return ((DataBufferInt) f.image().getRaster().getDataBuffer()).getData().clone();
        } finally {
            Motion.clear();
        }
    }

    private static void assertSamePicture(int[] fresh, int[] drawn, String step) {
        int worst = 0, at = -1;
        for (int i = 0; i < fresh.length; i++) {
            if (fresh[i] == drawn[i]) continue;
            for (int s = 0; s < 32; s += 8) {
                int d = Math.abs((fresh[i] >> s & 255) - (drawn[i] >> s & 255));
                if (d > worst) {
                    worst = d;
                    at = i;
                }
            }
        }
        assertTrue(worst <= 1, step + ": the picture differs from a fresh one at (" + at % W + ", " + at / W + ") by " + worst);
    }

    @Test
    void aPageWhoseTransitionsAreNotRunningIsDrawnAsOneThatNeverMoves() {
        Page page = rig.pages.loaded("cards");
        assertTrue(page.moves());
        Session first = open("cards");
        // Its first picture is kept, and the next viewer takes it rather than drawing it.
        assertEquals(1, SessionRig.pictures(plugin).size(), "the first picture of a page with transitions was not kept");
        Session s = open("cards");
        assertSame(get(first, "frame"), get(s, "frame"), "the second viewer did not take the first picture");
        assertEquals(0, s.drewWhole.get() + s.drewPart.get(), "the second viewer drew its first picture");
        assertTrue(s.still());

        // Resting beside a card, its hover is drawn ahead; on it, that is what is shown: a repaint of part of the picture.
        Document doc = get(s, "doc");
        Rectangle2D card = ((HtmlRenderer.Frame) get(s, "frame")).boundsOf(doc.select(".card").get(1));
        int cx = (int) card.getCenterX(), cy = (int) card.getCenterY();
        onServer(() -> s.pointer(cx, (int) card.getMaxY() + 12));
        HoverAhead ahead = get(s, "hoverAhead");
        assertTrue(ahead.prepared.get() > 0, "nothing was drawn ahead");
        onServer(() -> s.pointer(cx, cy));
        assertTrue(ahead.taken.get() >= 1, "the hover was not drawn ahead");
        assertEquals(0, s.drewWhole.get(), "a hover drew the whole picture");
        // The transition's first moment, not its end.
        assertSamePicture(fresh(page, 0, SessionRig.TICK.get() / 20.0, true), picture(s), "hovered, 0 s");
        assertFalse(s.still(), "the hover started no transition");

        // While it runs, each picture repaints the card; then it is over, and the page is still again.
        for (int i = 0; i < 6; i++) tick(s, 2);
        assertEquals(0, s.drewWhole.get(), "a frame of the transition drew the whole picture");
        assertTrue(s.drewPart.get() >= 6, "the transition was not drawn: " + s.drewPart.get() + " pictures");
        assertTrue(s.still(), "the transition never ended");
        Document e = page.document.clone();
        int[] end = ((DataBufferInt) HtmlRenderer.render(page, e, W, H, 1, 0, HtmlRenderer.state(e.select(".card").get(1), null))
                .image().getRaster().getDataBuffer()).getData();
        assertSamePicture(end, picture(s), "hovered, over");
    }

    @Test
    void eachFrameOfAnAnimationRepaintsOnlyWhatMoves() {
        Page page = rig.pages.loaded("spinner");
        Session s = open("spinner");
        assertEquals(1, s.drewWhole.get());
        assertTrue(!s.still(), "the spinner does not move");
        Canvas canvas = s.canvas;
        // The spinner is in tile (3, 2), and only round it is repainted: no other tile may change. Turning is all that
        // moves, which painting alone reads: the page is not laid out again.
        int spinnerTile = 2 * COLS + 3;
        long[] laid = HtmlRenderer.layouts();
        for (int i = 0; i < 5; i++) {
            Canvas.Snapshot before = canvas.snapshot();
            tick(s, 2);
            Canvas.Snapshot after = canvas.snapshot();
            assertEquals(1, s.drewWhole.get(), "a frame drew the whole picture");
            assertEquals(i + 1, s.drewPart.get(), "a frame was not drawn");
            for (int t = 0; t < canvas.tiles(); t++) {
                if (t == spinnerTile) continue;
                assertSame(before.tile(t, Canvas.Encoding.HI), after.tile(t, Canvas.Encoding.HI), "frame " + i + ": tile " + t + " changed");
            }
            assertTrue(before.tile(spinnerTile, Canvas.Encoding.HI) != after.tile(spinnerTile, Canvas.Encoding.HI), "the spinner did not turn");
            int now = SessionRig.TICK.get();
            assertSamePicture(fresh(page, 0, now / 20.0, false), picture(s), "frame " + i);
        }
        long[] after = HtmlRenderer.layouts();
        assertTrue(after[1] - laid[1] >= 5, "the frames laid the page out again: " + (after[0] - laid[0]) + " afresh, " + (after[1] - laid[1]) + " kept");
    }

    @Test
    void anAnimationThatMovesLayoutIsLaidOutForEachFrame() {
        Page page = rig.pages.loaded("grow");
        Session s = open("grow");
        assertFalse(s.still(), "the bar does not grow");
        long[] laid = HtmlRenderer.layouts();
        for (int i = 0; i < 4; i++) {
            tick(s, 2);
            assertSamePicture(fresh(page, 0, SessionRig.TICK.get() / 20.0, false), picture(s), "frame " + i);
        }
        assertEquals(1, s.drewWhole.get(), "a frame drew the whole picture");
        long[] after = HtmlRenderer.layouts();
        assertTrue(after[0] - laid[0] >= 4, "a frame of a width that moves kept the last layout");
    }

    @Test
    void aSpinnerOverABackdropBlurRepaintsInPartAndMatches() {
        Page page = rig.pages.loaded("spinblur");
        Session s = open("spinblur");
        for (int i = 0; i < 4; i++) {
            tick(s, 2);
            assertSamePicture(fresh(page, 0, SessionRig.TICK.get() / 20.0, false), picture(s), "frame " + i);
        }
        assertEquals(1, s.drewWhole.get(), "a frame drew the whole picture");
    }

    @Test
    void viewersOfAnAnimationOnThePageClockShareEachFrame() {
        Page page = rig.pages.loaded("spinner");
        Session[] all = {open("spinner"), open("spinner"), open("spinner")};
        long drawn0 = 0, took0 = 0;
        for (Session s : all) {
            drawn0 += s.drewWhole.get() + s.drewPart.get();
            took0 += s.tookFrames.get();
        }
        for (int i = 0; i < 4; i++) {
            int now = SessionRig.TICK.addAndGet(2);
            onServer(() -> {
                for (Session s : all) s.tick(now);
            });
            // One drew it, the others show the very same tiles, and it is the picture of this moment.
            for (int t = 0; t < all[0].canvas.tiles(); t++) {
                byte[] tile = all[0].canvas.snapshot().tile(t, Canvas.Encoding.HI);
                for (Session s : all) assertSame(tile, s.canvas.snapshot().tile(t, Canvas.Encoding.HI), "frame " + i + ": tile " + t + " differs");
            }
            for (Session s : all) assertSamePicture(fresh(page, 0, now / 20.0, false), picture(s), "frame " + i);
        }
        long drawn = -drawn0, took = -took0;
        for (Session s : all) {
            drawn += s.drewWhole.get() + s.drewPart.get();
            took += s.tookFrames.get();
        }
        assertEquals(4, drawn, "each frame was not drawn once");
        assertEquals(8, took, "the other viewers did not take each frame");
    }
}
