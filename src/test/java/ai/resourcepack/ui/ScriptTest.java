package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.Key;
import ai.resourcepack.ui.script.PageScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pages with a {@code <script>}, through real {@link Session}s: what the script draws on its canvas reaches the screen,
 * keys and the pointer reach the script, a repaint of the page around the canvas keeps the canvas, a script that never
 * ends a frame is stopped, and a page with no script has none.
 */
class ScriptTest {
    private static final int COLS = 4, ROWS = 3, W = COLS * 128, H = ROWS * 128;

    /** A canvas filled red each frame, green once W is pressed, with a blue bar where the pointer is; a button to hover. */
    private static final String GAME = "<html><head><style>html,body{margin:0;height:100%;background:#000}"
            + "canvas{position:absolute;left:100px;top:50px}"
            + "button{position:absolute;left:100px;top:250px;width:120px;height:40px;background:#333;border:0}"
            + "button:hover{background:#ff0}</style></head><body>"
            + "<canvas id=c width=200 height=100></canvas><button>Hover me</button>"
            + "<script>"
            + "const c = document.getElementById('c'); const ctx = c.getContext('2d');"
            + "let colour = 'rgb(255,0,0)'; let px = -1;"
            + "document.addEventListener('keydown', e => { if (e.key === 'w') colour = 'rgb(0,255,0)'; });"
            + "c.addEventListener('mousemove', e => { px = e.offsetX; });"
            + "function Painter() {}"
            + "Painter.prototype.draw = function () { ctx.fillStyle = colour; ctx.fillRect(0, 0, c.width, c.height);"
            + "  if (px >= 0) { ctx.fillStyle = '#00f'; ctx.fillRect(px - 5, 0, 10, 100); } };"
            + "const p = new Painter();"
            + "function loop() { p.draw(); requestAnimationFrame(loop); }"
            + "requestAnimationFrame(loop);"
            + "</script></body></html>";

    private static final String FOREVER = "<html><body style='margin:0;background:#000'><canvas width=100 height=100></canvas>"
            + "<script>requestAnimationFrame(function () { while (true) {} });</script></body></html>";

    private static final String PLAIN = "<html><body style='margin:0;background:#000'><canvas width=100 height=100></canvas></body></html>";

    @TempDir
    Path dir;
    private SessionRig rig;
    private MCGUIPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        SessionRig.TICK.set(1000);
        Files.writeString(dir.resolve("game.html"), GAME);
        Files.writeString(dir.resolve("forever.html"), FOREVER);
        Files.writeString(dir.resolve("plain.html"), PLAIN);
        rig = new SessionRig(dir, 4);
        plugin = rig.plugin(0);
        for (String name : new String[]{"game", "forever", "plain"}) assertNotNull(rig.pages.load(name).get(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    @Test
    void drawsKeysPointerAndSurvivesRepaints() {
        Session s = rig.open(plugin, "game", COLS, ROWS, H);
        // The canvas is at (100, 50) to (300, 150): red once the script has drawn.
        assertTrue(waitFor(() -> red(pixel(s, 200, 100))), "the canvas is drawn: " + hex(pixel(s, 200, 100)));
        assertTrue(dark(pixel(s, 50, 20)), "the page round it is the page's");

        // The button below it lights up: a repaint of part of the page, which must not take the canvas off.
        rig.onServer(() -> s.pointer(150, 270));
        assertTrue(waitFor(() -> yellow(pixel(s, 150, 270))), "the hover is drawn: " + hex(pixel(s, 150, 270)));
        assertTrue(red(pixel(s, 200, 100)), "the canvas is still there after the repaint");

        // W goes to the script, and is not a scroll.
        rig.onServer(() -> s.scriptKey(Key.UP, true));
        assertTrue(waitFor(() -> green(pixel(s, 200, 100))), "W reached the script: " + hex(pixel(s, 200, 100)));
        rig.onServer(() -> s.scriptKey(Key.UP, false));

        // The pointer over the canvas, 60 CSS pixels into it: a blue bar there.
        rig.onServer(() -> s.pointer(160, 100));
        assertTrue(waitFor(() -> blue(pixel(s, 160, 100))), "the pointer reached the script: " + hex(pixel(s, 160, 100)));
        assertTrue(green(pixel(s, 250, 100)), "the rest of the canvas is as drawn");

        rig.onServer(s::dispose);
        assertNull(script(s), "closing the page stops its script");
    }

    @Test
    void aFrameThatNeverEndsIsStopped() {
        Session s = rig.open(plugin, "forever", COLS, ROWS, H);
        PageScript js = script(s);
        assertNotNull(js);
        assertTrue(waitFor(() -> stopped(js)), "the script was stopped");
        // The server thread was never involved: it answers at once.
        long t0 = System.nanoTime();
        rig.onServer(() -> s.pointer(10, 10));
        assertTrue(System.nanoTime() - t0 < 1_000_000_000L);
        rig.onServer(s::dispose);
    }

    @Test
    void aPageWithoutAScriptHasNone() {
        Session s = rig.open(plugin, "plain", COLS, ROWS, H);
        assertNull(script(s));
        rig.onServer(() -> assertFalse(s.scriptKey(Key.UP, true), "keys go where they always went"));
        rig.onServer(s::dispose);
    }

    // ---------------------------------------------------------------- helpers

    private static int pixel(Session s, int x, int y) {
        return s.canvas.thumbnail(W, H)[y * W + x];
    }

    private static boolean waitFor(BooleanSupplier ok) {
        long end = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < end) {
            if (ok.getAsBoolean()) return true;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return ok.getAsBoolean();
    }

    private static int r(int rgb) { return rgb >> 16 & 255; }

    private static int g(int rgb) { return rgb >> 8 & 255; }

    private static int b(int rgb) { return rgb & 255; }

    private static boolean red(int c) { return r(c) > 180 && g(c) < 80 && b(c) < 80; }

    private static boolean green(int c) { return g(c) > 180 && r(c) < 80 && b(c) < 80; }

    private static boolean blue(int c) { return b(c) > 180 && r(c) < 80 && g(c) < 80; }

    private static boolean yellow(int c) { return r(c) > 180 && g(c) > 180 && b(c) < 80; }

    private static boolean dark(int c) { return r(c) < 40 && g(c) < 40 && b(c) < 40; }

    private static String hex(int c) { return String.format("#%06x", c & 0xFFFFFF); }

    private static PageScript script(Session s) {
        try {
            Field f = Session.class.getDeclaredField("script");
            f.setAccessible(true);
            return (PageScript) f.get(s);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean stopped(PageScript js) {
        try {
            Field f = PageScript.class.getDeclaredField("stopped");
            f.setAccessible(true);
            return f.getBoolean(js);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
