package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.HoverLayers;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hovers a view's client shows by itself ({@link ClientHovers}), through a real {@link Session}: wherever the pointer
 * goes, what the client would show (the picture with every layer it shows laid over it, the layer of the highest
 * priority in front) is a fresh picture of the hover there, and a hover the client shows is not drawn by the server.
 * The client is played by the layers' own rule: a layer shows while the pointer is inside its include rectangle and
 * outside its exclude one.
 */
class ClientHoversTest {
    private static final int COLS = 6, ROWS = 3, W = COLS * 128, H = ROWS * 128;

    @TempDir
    Path dir;
    private SessionRig rig;

    /**
     * Rounded cards over a blurred picture, each with a tooltip; the first holds a button with a hover of its own, and the
     * second is lifted over the third where they overlap.
     */
    private static final String HTML = "<html><head><style>html,body{margin:0;height:100%;overflow:hidden;background:#000}"
            + ".pano{position:absolute;left:-24px;top:-24px;right:-24px;bottom:-24px;filter:blur(10px);"
            + "background:linear-gradient(90deg,#000 0 30%,#fff 30% 60%,#f80 60%),linear-gradient(#08f,#0f8)}"
            + ".cards{position:absolute;top:90px;left:30px;display:flex;gap:24px}"
            + ".card{position:relative;width:150px;height:200px;border-radius:18px;background:#2a4a6a;box-shadow:0 6px 12px rgba(0,0,0,.5)}"
            + ".card:hover{background:#ffffff}.card:active{background:#808080}"
            + ".card.lift{margin-right:-60px;z-index:1}"
            + ".tip{display:none;position:absolute;left:70px;top:-30px;z-index:10;padding:4px 8px;white-space:nowrap;background:#100010;"
            + "border:2px solid #5000ff;color:#fff;font-size:14px}"
            + ".card:hover .tip{display:block}"
            + ".btn{position:absolute;left:30px;top:120px;width:90px;height:40px;background:#c03030;border-radius:6px}"
            + ".btn:hover{background:#30c030}"
            + "</style></head><body><div class=pano></div><div class=cards>"
            + "<div class=card><div class=tip>One</div><div class=btn></div></div>"
            + "<div class='card lift'><div class=tip>Two</div></div>"
            + "<div class=card><div class=tip>Three</div></div>"
            + "</div></body></html>";

    @BeforeEach
    void setUp() throws Exception {
        SessionRig.TICK.set(1000);
        Files.writeString(dir.resolve("cards.html"), HTML);
        rig = new SessionRig(dir, 4);
        assertNotNull(rig.pages.load("cards").get(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    /** The client's layers, as the client would hold them. */
    private static final class Client implements HoverLayers {
        record Pixels(int[] argb, int x, int y, int w, int h) implements HoverLayers.Layer {
            @Override public int maps() { return 1; }
        }
        record Layer(int[] include, int[] exclude, int priority, int[] argb, int x, int y, int w, int h) {}

        final Map<Object, Layer> layers = new LinkedHashMap<>();
        int arms, prepares;

        @Override public int capacity() { return 1000; }
        @Override public synchronized int used() { return layers.size(); }
        @Override public int mapsFor(int[] argb, int x, int y, int w, int h) { return 1; }

        @Override
        public synchronized HoverLayers.Layer prepare(int[] argb, int x, int y, int w, int h, java.util.function.BooleanSupplier stop) {
            assertTrue(x % 2 == 0 && y % 2 == 0 && w % 2 == 0 && h % 2 == 0, "a layer not in whole 2x2 blocks");
            prepares++;
            return new Pixels(argb.clone(), x, y, w, h);
        }

        @Override
        public synchronized boolean arm(Object key, int[] include, int[] exclude, int priority, HoverLayers.Layer layer) {
            Pixels p = (Pixels) layer;
            layers.put(key, new Layer(include.clone(), exclude == null ? null : exclude.clone(), priority, p.argb, p.x, p.y, p.w, p.h));
            arms++;
            return true;
        }

        @Override public synchronized boolean armed(Object key) { return layers.containsKey(key); }
        @Override public synchronized void disarm(Object key) { layers.remove(key); }
        @Override public synchronized void clear() { layers.clear(); }

        private static boolean in(int[] r, int x, int y) {
            return x >= r[0] && y >= r[1] && x < r[2] && y < r[3];
        }

        /** [picture] with every layer that shows with the pointer at (px, py) over it, lowest priority first. */
        synchronized int[] shown(int[] picture, int px, int py) {
            int[] out = picture.clone();
            List<Layer> on = new ArrayList<>();
            for (Layer l : layers.values()) if (in(l.include, px, py) && (l.exclude == null || !in(l.exclude, px, py))) on.add(l);
            on.sort((a, b) -> Integer.compare(a.priority, b.priority));
            for (Layer l : on) {
                for (int y = 0; y < l.h; y++) {
                    for (int x = 0; x < l.w; x++) {
                        int p = l.argb[y * l.w + x];
                        if (p >>> 24 >= 128) out[(l.y + y) * W + l.x + x] = p;
                    }
                }
            }
            return out;
        }
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

    private void onServer(Runnable r) {
        rig.onServer(r);
        rig.quiet();
    }

    private static int[] pixels(HtmlRenderer.Frame f) {
        return ((DataBufferInt) f.image().getRaster().getDataBuffer()).getData().clone();
    }

    private static void assertSamePicture(int[] fresh, int[] shown, String step) {
        int worst = 0, at = -1;
        for (int i = 0; i < fresh.length; i++) {
            if (fresh[i] == shown[i]) continue;
            for (int s = 0; s < 32; s += 8) {
                int d = Math.abs((fresh[i] >> s & 255) - (shown[i] >> s & 255));
                if (d > worst) {
                    worst = d;
                    at = i;
                }
            }
        }
        assertTrue(worst <= 1, step + ": the client would show a picture that differs from a fresh one at pixel (" + at % W + ", " + at / W + ") by " + worst);
    }

    private static Rectangle2D bounds(Page page, String selector, int i) {
        Document d = page.document.clone();
        return HtmlRenderer.render(page, d, W, H, 1, 0, HtmlRenderer.state(null, null)).boundsOf(d.select(selector).get(i));
    }

    @Test
    void theClientShowsAFreshPictureWhereverThePointerGoes() {
        Page page = rig.pages.loaded("cards");
        Session s = rig.open(rig.plugin(0), "cards", COLS, ROWS, H);
        rig.quiet();
        Client client = new Client();
        onServer(() -> s.layers(client));
        Rectangle2D one = bounds(page, ".card", 0), two = bounds(page, ".card", 1), three = bounds(page, ".card", 2), btn = bounds(page, ".btn", 0);
        int cy = (int) one.getCenterY();
        // Resting beside the first card: its hovers are drawn ahead, and the client given them.
        onServer(() -> s.pointer((int) one.getX() - 10, cy));
        assertTrue(client.arms > 0, "no hover was handed to the client");

        int[][] path = {
                {(int) one.getCenterX(), (int) one.getY() + 30},             // the first card
                {(int) btn.getCenterX(), (int) btn.getCenterY()},             // its button
                {(int) one.getCenterX(), (int) one.getY() + 30},              // back out of the button
                {(int) one.getX() + 2, (int) one.getY() + 2},                 // a rounded corner: nothing there
                {(int) (one.getMaxX() + two.getX()) / 2, cy},                 // between the cards
                {(int) two.getCenterX(), (int) two.getY() + 30},              // the second card
                {(int) two.getMaxX() - 10, (int) two.getY() + 100},           // where it lies over the third
                {(int) three.getMaxX() - 20, (int) three.getY() + 100},       // the third, past the second
                {(int) three.getMaxX() + 40, cy},                             // nothing
                {(int) one.getCenterX(), (int) one.getY() + 30},              // all the way back
        };
        int drawnByServer = 0, shownByClient = 0;
        for (int[] p : path) {
            HtmlRenderer.Frame before = get(s, "frame");
            onServer(() -> s.pointer(p[0], p[1]));
            // Again, the pointer resting there: what is drawn ahead from here reaches the client.
            onServer(() -> s.pointer(p[0], p[1]));
            HtmlRenderer.Frame after = get(s, "frame");
            if (after == before) shownByClient++;
            else drawnByServer++;
            Element hover = get(s, "hover");
            Document d = page.document.clone();
            Element same = hover == null ? null : d.getAllElements().get(hover.ownerDocument().getAllElements().indexOf(hover));
            int[] fresh = pixels(HtmlRenderer.render(page, d, W, H, 1, 0, HtmlRenderer.state(same, null)));
            assertSamePicture(fresh, client.shown(pixels(after), p[0], p[1]), "pointer at " + p[0] + "," + p[1]);
        }
        assertTrue(shownByClient >= path.length / 2, "the client showed only " + shownByClient + " of " + path.length + " hovers by itself");
        assertEquals(path.length, shownByClient + drawnByServer);
    }

    @Test
    void layersGoWhenThePictureChanges() {
        Page page = rig.pages.loaded("cards");
        Session s = rig.open(rig.plugin(0), "cards", COLS, ROWS, H);
        rig.quiet();
        Client client = new Client();
        onServer(() -> s.layers(client));
        Rectangle2D one = bounds(page, ".card", 0);
        onServer(() -> s.pointer((int) one.getX() - 10, (int) one.getCenterY()));
        assertTrue(client.used() > 0);
        HtmlRenderer.Frame before = get(s, "frame");
        // A click is a press, drawn by the server over a new picture: none of the old layers is of it.
        onServer(() -> s.click((int) one.getCenterX(), (int) one.getCenterY()));
        HtmlRenderer.Frame after = get(s, "frame");
        assertTrue(after != before, "the press drew no picture");
        ClientHovers h = get(s, "hovers");
        assertEquals(h.count(), client.used(), "layers left over from the picture before");
    }

    /**
     * A hover the client shows by itself is drawn into the picture once the pointer rests on it, from what was drawn
     * ahead, and not while the pointer still moves: the next hover the client cannot show is then one change from the
     * picture the player sees, not two.
     */
    @Test
    void thePictureCatchesUpWithAHoverTheClientShowsOnceThePointerRests() throws Exception {
        Page page = rig.pages.loaded("cards");
        Session s = rig.open(rig.plugin(0), "cards", COLS, ROWS, H);
        rig.quiet();
        Client client = new Client();
        onServer(() -> s.layers(client));
        Rectangle2D one = bounds(page, ".card", 0);
        onServer(() -> s.pointer((int) one.getX() - 10, (int) one.getY() + 30));
        HtmlRenderer.Frame before = get(s, "frame");
        // A tick as the pointer gets there (it has not rested): no picture.
        onServer(() -> {
            s.pointer((int) one.getCenterX(), (int) one.getY() + 30);
            s.tick(1001);
        });
        assertSame(before, get(s, "frame"), "the server drew a hover the client shows by itself, or caught up while the pointer moved");
        ai.resourcepack.ui.html.HoverAhead ahead = get(s, "hoverAhead");
        long taken = ahead.taken.get();
        Thread.sleep(150);
        onServer(() -> s.tick(1002));
        HtmlRenderer.Frame after = get(s, "frame");
        assertTrue(after != before, "the picture did not catch up");
        assertEquals(taken + 1, ahead.taken.get(), "caught up by drawing, not from what was drawn ahead");
        Element hover = get(s, "hover");
        assertEquals(page.styles.stateKey(hover, false), get(s, "drawnHover"));
        Document d = page.document.clone();
        Element same = d.getAllElements().get(hover.ownerDocument().getAllElements().indexOf(hover));
        int[] fresh = pixels(HtmlRenderer.render(page, d, W, H, 1, 0, HtmlRenderer.state(same, null)));
        assertSamePicture(fresh, client.shown(pixels(after), (int) one.getCenterX(), (int) one.getY() + 30), "caught up");
    }

    /**
     * Two players on one page, their pointers resting in one place: the second's layers are the first's, armed without
     * being worked out again.
     */
    @Test
    void layersAreWorkedOutOnceForEveryPlayerInTheSameState() {
        Page page = rig.pages.loaded("cards");
        var plugin = rig.plugin(4);
        Session one = rig.open(plugin, "cards", COLS, ROWS, H);
        rig.quiet();
        Session two = rig.open(plugin, "cards", COLS, ROWS, H);
        rig.quiet();
        Client first = new Client(), second = new Client();
        onServer(() -> one.layers(first));
        onServer(() -> two.layers(second));
        Rectangle2D card = bounds(page, ".card", 0);
        onServer(() -> one.pointer((int) card.getX() - 10, (int) card.getCenterY()));
        assertTrue(first.arms > 0 && first.prepares > 0, "nothing armed for the first");
        onServer(() -> two.pointer((int) card.getX() - 10, (int) card.getCenterY()));
        assertEquals(first.arms, second.arms, "the second armed other layers");
        assertEquals(0, second.prepares, "the second worked its layers out again");
    }

    /**
     * From a hover inside another onto the outer one alone, where the inner one changed nothing: the client shows the
     * picture as it is (the outer one's hover), not the layer of nothing hovered, which undoes the outer one too; and the
     * server, which knows what the client shows, draws nothing.
     */
    @Test
    void offAnInnerHoverOntoTheOuterOneTheClientKeepsTheOuterOne() throws Exception {
        Files.writeString(dir.resolve("nest.html"), "<html><head><style>html,body{margin:0;height:100%;overflow:hidden;background:#203040}"
                + ".box{position:absolute;left:40px;top:60px;width:300px;height:200px;background:#2a4a6a}.box:hover{background:#ffffff}"
                + ".mark{position:absolute;left:20px;top:20px;width:60px;height:60px;background:#123456}.mark:hover{z-index:3}"
                + "</style></head><body><div class=box><div class=mark></div></div></body></html>");
        assertNotNull(rig.pages.load("nest").get(30, TimeUnit.SECONDS));
        Page page = rig.pages.loaded("nest");
        Session s = rig.open(rig.plugin(0), "nest", COLS, ROWS, H);
        rig.quiet();
        Client client = new Client();
        onServer(() -> s.layers(client));
        Rectangle2D mark = bounds(page, ".mark", 0);
        int[] in = {(int) mark.getCenterX(), (int) mark.getCenterY()}, box = {(int) mark.getMaxX() + 12, (int) mark.getCenterY()};
        // On the mark: drawn in its hover (and the box's), then, resting there, the hovers round it handed to the client.
        onServer(() -> s.pointer(in[0], in[1]));
        onServer(() -> s.pointer(in[0], in[1]));
        assertEquals(2, ((List<?>) get(s, "drawnHover")).size(), "not drawn in the mark's hover");
        assertTrue(client.used() > 0, "nothing handed to the client");
        HtmlRenderer.Frame before = get(s, "frame");
        Document d = page.document.clone();
        int[] fresh = pixels(HtmlRenderer.render(page, d, W, H, 1, 0, HtmlRenderer.state(d.selectFirst(".box"), null)));
        assertSamePicture(fresh, client.shown(pixels(before), box[0], box[1]), "off the mark, before the server hears");
        onServer(() -> s.pointer(box[0], box[1]));
        assertSame(before, get(s, "frame"), "the server drew what the client shows");
    }

    /**
     * A layer holds the picture's own pixels for one 2x2 block all round what its hover changes: the client averages
     * the texels under each screen pixel within one map, and shows a layer only where it covers half of them, so without
     * them a thin border came out unlike the picture that later arrives under it.
     */
    @Test
    void aLayerKeepsABlockOfThePictureRoundWhatItChanges() {
        Page page = rig.pages.loaded("cards");
        Session s = rig.open(rig.plugin(0), "cards", COLS, ROWS, H);
        rig.quiet();
        Client client = new Client();
        onServer(() -> s.layers(client));
        Rectangle2D one = bounds(page, ".card", 0);
        onServer(() -> s.pointer((int) one.getX() - 10, (int) one.getCenterY()));
        int[] picture = pixels(get(s, "frame"));
        assertTrue(!client.layers.isEmpty(), "nothing armed");
        for (Client.Layer l : client.layers.values()) {
            int changed = 0;
            for (int by = l.y(); by < l.y() + l.h(); by += 2) {
                for (int bx = l.x(); bx < l.x() + l.w(); bx += 2) {
                    boolean differs = false;
                    for (int k = 0; k < 4; k++) {
                        int x = bx + (k & 1), y = by + (k >> 1), v = l.argb()[(y - l.y()) * l.w() + x - l.x()];
                        if (v >>> 24 >= 128 && v != picture[y * W + x]) differs = true;
                    }
                    if (!differs) continue;
                    changed++;
                    for (int ny = by - 2; ny <= by + 2; ny += 2) {
                        for (int nx = bx - 2; nx <= bx + 2; nx += 2) {
                            if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                            assertTrue(nx >= l.x() && ny >= l.y() && nx < l.x() + l.w() && ny < l.y() + l.h()
                                    && l.argb()[(ny - l.y()) * l.w() + nx - l.x()] >>> 24 >= 128, "no picture round changed block " + bx + "," + by);
                        }
                    }
                }
            }
            assertTrue(changed > 0, "a layer that changes nothing");
        }
    }

    /** Arming gives way: told to stop (a real picture is asked for), it arms nothing more. */
    @Test
    void armingStopsWhenToldTo() {
        Page page = rig.pages.loaded("cards");
        Session s = rig.open(rig.plugin(0), "cards", COLS, ROWS, H);
        rig.quiet();
        Client client = new Client();
        onServer(() -> s.layers(client));
        Rectangle2D card = bounds(page, ".card", 0);
        onServer(() -> s.pointer((int) card.getX() - 10, (int) card.getCenterY()));
        assertTrue(client.arms > 0);
        HtmlRenderer.Frame f = get(s, "frame");
        List<Element> drawnHover = get(s, "drawnHover"), drawnPress = get(s, "drawnPress");
        ai.resourcepack.ui.html.HoverAhead ahead = get(s, "hoverAhead");
        var ready = ahead.ready(f, drawnHover, drawnPress, drawnPress, k -> true);
        assertTrue(!ready.isEmpty());
        Client other = new Client();
        ClientHovers h = new ClientHovers(other);
        assertTrue(!h.arm(f, drawnHover, ready, 0, 0, () -> true), "went on when told to stop");
        assertEquals(0, other.arms);
        assertTrue(h.arm(f, drawnHover, ready, 0, 0, () -> false));
        assertTrue(other.arms > 0);
    }
}
