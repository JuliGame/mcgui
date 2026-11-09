package ai.resourcepack.ui;

import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.html.res.AnimatedPictureTest;
import ai.resourcepack.ui.html.style.Motion;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GIFs that move, on real sessions: as an image, as a background, and drawn by a script. */
class AnimatedGifPageTest {
    private static final int COLS = 4, ROWS = 3, W = COLS * 128, H = ROWS * 128;
    private static final int RED = 0xFFFF0000, GREEN = 0xFF00FF00;

    private static final String IMG = "<html><body style='margin:0;background:#000'>"
            + "<img src=spin.gif style='position:absolute;left:100px;top:50px;width:200px;height:100px'></body></html>";
    private static final String BACKGROUND = "<html><body style='margin:0;background:#000'>"
            + "<div style='position:absolute;left:100px;top:50px;width:200px;height:100px;background:url(spin.gif);background-size:100% 100%'></div></body></html>";
    private static final String SCRIPT = "<html><body style='margin:0;background:#000'>"
            + "<canvas id=c width=200 height=100 style='position:absolute;left:100px;top:50px'></canvas><script>"
            + "var ctx = document.getElementById('c').getContext('2d'); var img = new Image(); img.src = 'spin.gif';"
            + "function loop() { ctx.drawImage(img, 0, 0, 200, 100); requestAnimationFrame(loop); } requestAnimationFrame(loop);"
            + "</script></body></html>";
    private static final String STILL = "<html><body style='margin:0;background:#000'>"
            + "<img src=spin.gif style='display:none'></body></html>";

    @TempDir
    Path dir;
    private SessionRig rig;
    private MCGUIPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        SessionRig.TICK.set(1000);
        Files.write(dir.resolve("spin.gif"), AnimatedPictureTest.loop(16, 16, 20, RED, GREEN));
        Files.writeString(dir.resolve("img.html"), IMG);
        Files.writeString(dir.resolve("background.html"), BACKGROUND);
        Files.writeString(dir.resolve("script.html"), SCRIPT);
        Files.writeString(dir.resolve("still.html"), STILL);
        rig = new SessionRig(dir, 4);
        plugin = rig.plugin(0);
        for (String name : new String[]{"img", "background", "script", "still"}) assertNotNull(rig.pages.load(name).get(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    private void playsThroughTicks(String page) {
        Session s = rig.open(plugin, page, COLS, ROWS, H);
        assertTrue(ticksUntil(s, AnimatedGifPageTest::isRed), "red: #" + Integer.toHexString(pixel(s)));
        assertTrue(ticksUntil(s, AnimatedGifPageTest::isGreen), "then green: #" + Integer.toHexString(pixel(s)));
        assertTrue(ticksUntil(s, AnimatedGifPageTest::isRed), "and red again: #" + Integer.toHexString(pixel(s)));
        rig.onServer(s::dispose);
    }

    @Test
    void anImagePlays() {
        playsThroughTicks("img");
    }

    @Test
    void aBackgroundPlays() {
        playsThroughTicks("background");
    }

    @Test
    void aScriptDrawsEachFrame() {
        playsThroughTicks("script");
    }

    @Test
    void aMovingGifNobodyCanSeeCostsNothing() throws Exception {
        Page page = rig.pages.load("still").get(30, TimeUnit.SECONDS);
        assertTrue(page.moves(), "it has a moving picture");
        Motion m = fresh(page, 1, 1);
        assertFalse(m.active(), "but display:none shows it nowhere");
    }

    @Test
    void everyBandOfAPictureIsOfOneMoment() throws Exception {
        Page page = rig.pages.load("img").get(30, TimeUnit.SECONDS);
        // At 0.25 s the second frame is showing, and each band of a whole picture is painted on a pool thread.
        Document d = page.document.clone();
        Motion m = new Motion();
        m.moment(0.25, 0.25);
        Motion.set(m);
        try {
            HtmlRenderer.Frame f = HtmlRenderer.render(page, d, W, H * 4, 1, 0, HtmlRenderer.state(null, null));
            BufferedImage img = f.image();
            assertTrue(isGreen(img.getRGB(200, 100)), "#" + Integer.toHexString(img.getRGB(200, 100)));
            assertTrue(m.active());
            assertFalse(m.personal(), "a GIF that loops is the same picture for every viewer");
        } finally {
            Motion.clear();
        }
    }

    private static Motion fresh(Page page, double viewer, double clock) {
        Document d = page.document.clone();
        Motion m = new Motion();
        m.moment(viewer, clock);
        Motion.set(m);
        try {
            HtmlRenderer.render(page, d, W, H, 1, 0, HtmlRenderer.state(null, null));
            return m;
        } finally {
            Motion.clear();
        }
    }

    /** Runs the server's ticks a little at a time until the middle of the picture passes [ok]. */
    private boolean ticksUntil(Session s, IntPredicate ok) {
        long end = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < end) {
            if (ok.test(pixel(s))) return true;
            int now = SessionRig.TICK.addAndGet(1);
            rig.onServer(() -> s.tick(now));
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        return false;
    }

    private static int pixel(Session s) {
        return s.canvas.thumbnail(W, H)[100 * W + 200];
    }

    private static boolean isRed(int c) {
        return (c >> 16 & 0xFF) > 150 && (c >> 8 & 0xFF) < 100 && (c & 0xFF) < 100;
    }

    private static boolean isGreen(int c) {
        return (c >> 8 & 0xFF) > 150 && (c >> 16 & 0xFF) < 100 && (c & 0xFF) < 100;
    }
}
