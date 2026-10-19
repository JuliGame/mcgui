package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.net.MapSender;
import ai.resourcepack.uiframework.render.Palette;
import ai.resourcepack.uiframework.world.MapWall;
import org.bukkit.block.BlockFace;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A page scrolled by whole map rows, as a Session does it (a view cut from a ScrollCache, the canvas moved by
 * {@link ScrollCache#tileRows} rows): what the client would show, worked out from which map each frame was pointed
 * at and the map colours sent so far, must be a fresh render of the page at the same place, tile for tile.
 */
class TileScrollTest {
    private static final int COLS = 8, ROWS = 6, W = COLS * 128, H = ROWS * 128;
    private static final double SCALE = 2, ROW = 128 / SCALE;
    private static final Page LONG;

    static {
        try {
            LONG = Page.load(Path.of("src/test/resources/scroll/long.html").toAbsolutePath(), new Page.Loader(false, null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final Palette palette = new Palette(RenderBench::mapColour);
    private Canvas canvas;
    private RecordingWire client;
    private MapSender sender;
    private ScrollCache cache;
    private double at;
    private Selector.State state = Selector.State.NONE;
    /** Every picture the canvas showed, as a fresh render: a bundle must leave the client on one of them. */
    private final List<byte[][]> shown = new ArrayList<>();

    @BeforeEach
    void open() {
        canvas = new Canvas(palette, COLS, ROWS);
        MapWall wall = new MapWall(null, 0, 100, 0, BlockFace.NORTH, COLS, ROWS, 5000);
        client = new RecordingWire(wall);
        sender = new MapSender(client, palette, Integer.MAX_VALUE, Logger.getAnonymousLogger());
        sender.update(List.of(new MapSender.Assignment(RecordingWire.player(), wall, canvas, Canvas.Encoding.HI, null)));
        // Part way down, so the view can go up three rows and down three.
        at = 4 * ROW;
        canvas.draw(HtmlRenderer.render(LONG, W, H, SCALE, at, state).image(), 0, 0);
        shown.add(fresh());
        client.afterBundle = () -> assertTrue(shown.stream().anyMatch(this::onClient), "a bundle left the client on a picture the page never showed");
        settle();
        assertOnClient();
        assertTrue(HtmlRenderer.render(LONG, W, H, SCALE, 0, state).maxScroll() > at + 4 * ROW, "the page scrolls far enough");
    }

    @Test
    void scrollsByWholeRowsShowTheFreshPicture() {
        for (int rows : new int[]{1, 2, -1, -3}) {
            long patches = client.patches, repoints = client.repoints, bundles = client.bundles;
            scroll(rows);
            settle();
            assertOnClient();
            assertTrue(client.patches - patches <= (long) COLS * Math.abs(rows),
                    "only the " + Math.abs(rows) + " rows that came in are sent, not " + (client.patches - patches) + " maps");
            assertTrue(client.repoints - repoints >= (long) COLS * (ROWS - Math.abs(rows)), "the rows that stayed are pointed at, not sent");
            assertEquals(1, client.bundles - bundles, "pointed and sent in one bundle");
        }
    }

    @Test
    void aScrollWhileTheLastIsInFlight() {
        scroll(1);
        sender.pass();
        assertTrue(client.inFlight(), "the first move is written and not yet on the client");
        scroll(2);
        sender.pass();
        scroll(-1);
        sender.pass();
        settle();
        assertOnClient();
    }

    @Test
    void aHoverRepaintBetweenTwoScrolls() {
        scroll(1);
        settle();
        Element card = cardInView();
        state = HtmlRenderer.state(card, null);
        cache = null;
        canvas.draw(HtmlRenderer.render(LONG, W, H, SCALE, at, state).image(), 0, 0);
        shown.add(fresh());
        settle();
        assertOnClient();
        scroll(1);
        settle();
        assertOnClient();
        state = Selector.State.NONE;
        cache = null;
        canvas.draw(HtmlRenderer.render(LONG, W, H, SCALE, at, state).image(), 0, 0);
        shown.add(fresh());
        scroll(-2);
        settle();
        assertOnClient();
    }

    /**
     * As Session does it: the view cut from a tall picture, and the canvas moved by the whole rows it moved, with
     * only the rows that came in drawn when the last view was cut from the same picture, else the whole view.
     */
    private void scroll(int rows) {
        double from = at;
        at = from + rows * ROW;
        boolean kept = cache != null && cache.covers(at);
        if (!kept) cache = ScrollCache.paint(LONG, LONG.document, W, H, SCALE, at, from, state, ScrollCache.VIEWS);
        HtmlRenderer.Frame view = cache.frame(at);
        assertEquals(rows, ScrollCache.tileRows(from, view.scrollY(), SCALE, ROWS));
        if (kept) {
            int band = Math.abs(rows) * ScrollCache.TILE, y0 = rows > 0 ? H - band : 0;
            canvas.scroll(rows, view.image().getSubimage(0, y0, W, band), 0, y0);
        } else {
            canvas.scroll(rows, view.image(), 0, 0);
        }
        shown.add(fresh());
    }

    /** Sender passes and deliveries until nothing more is sent. */
    private void settle() {
        int quiet = 0;
        for (int i = 0; i < 1000; i++) {
            sender.pass();
            if (client.deliver()) quiet = 0;
            else if (++quiet == 3) return;
            try {
                Thread.sleep(1); // the sender's budget refills with time
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("the sender never settled");
    }

    private Element cardInView() {
        HtmlRenderer.Frame f = HtmlRenderer.render(LONG, W, H, SCALE, at, Selector.State.NONE);
        for (Element e : LONG.document.select(".card")) {
            double top = f.topOf(e);
            if (top > at + 10 && top < at + H / SCALE - 120) return e;
        }
        throw new AssertionError("no card in view");
    }

    /** The page drawn on its own where the view is now, as map colours. */
    private byte[][] fresh() {
        Canvas c = new Canvas(palette, COLS, ROWS);
        c.draw(HtmlRenderer.render(LONG, W, H, SCALE, at, state).image(), 0, 0);
        byte[][] out = new byte[COLS * ROWS][];
        for (int t = 0; t < out.length; t++) out[t] = c.tile(t, Canvas.Encoding.HI);
        return out;
    }

    private boolean onClient(byte[][] picture) {
        for (int t = 0; t < picture.length; t++) if (!Arrays.equals(picture[t], client.shown(t))) return false;
        return true;
    }

    private void assertOnClient() {
        byte[][] want = shown.get(shown.size() - 1);
        for (int t = 0; t < want.length; t++) {
            assertArrayEquals(want[t], client.shown(t), "tile " + t + " (row " + t / COLS + ") at " + at);
        }
        assertFalse(client.inFlight());
    }
}
