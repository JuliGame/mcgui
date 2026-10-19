package ai.resourcepack.ui;

import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.html.Repaint;
import ai.resourcepack.ui.html.ScrollCache;
import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.net.MapSender;
import ai.resourcepack.uiframework.net.Wire;
import ai.resourcepack.uiframework.render.Palette;
import ai.resourcepack.uiframework.world.MapWall;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A session that took its first picture from {@link FirstPictures} must be indistinguishable from one that drew
 * it: the same picture, canvas and hit regions after its first picture and after every hover, press, scroll and
 * refresh that follows, each drawn as Session draws it (a partial repaint over the last picture, a view cut from a
 * tall picture, or a whole picture). And a picture is only ever taken for exactly the document it is of.
 */
class FirstPicturesTest {
    private static final int COLS = 4, ROWS = 3, W = COLS * 128, H = ROWS * 128;
    private static final double SCALE = 1;
    private static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));

    /** A wall's page: placeholders anyone sees alike, hover and press states, a shadow, a backdrop, and more than a view to scroll. */
    private static final String HTML = """
            <html><head><style>
            body { margin: 0; font: 16px sans-serif; background: linear-gradient(#123, #345); color: white; }
            header { padding: 12px; font-size: 22px; backdrop-filter: blur(4px); background: rgba(255,255,255,.1); }
            .card { margin: 10px; padding: 14px; border-radius: 8px; background: #2a4a6a; box-shadow: 0 2px 6px rgba(0,0,0,.5); }
            .card:hover { background: #4a7aaa; box-shadow: 0 4px 14px rgba(0,0,0,.7); }
            .card:active { background: #e0a030; }
            a { color: #9cf; } a:hover { color: #fff; text-decoration: underline; }
            </style></head><body>
            <header>Lobby: {online} online of {max}</header>
            """ + cards(18) + "</body></html>";

    private static String cards(int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append("<div class=card>Card ").append(i).append(" <a href=\"#\">open {online}</a></div>\n");
        return s.toString();
    }

    private static Page page() {
        return Page.of(HTML, Path.of(".").toAbsolutePath(), new Page.Loader(false, null));
    }

    /** Fills as Placeholders does on a server with [online] players, for a viewer called [name]. */
    private static Placeholders.Filled fill(Page page, String name, int online) {
        UnaryOperator<String> anyone = t -> t.replace("{online}", String.valueOf(online)).replace("{max}", "20");
        return Placeholders.fill(page.document, t -> anyone.apply(t).replace("{player}", name), anyone);
    }

    private static FirstPictures.Key key(Page page, Placeholders.Filled f) {
        return new FirstPictures.Key("lobby", page, COLS, ROWS, SCALE, f.key());
    }

    /** One session's drawing, as Session.submit does it, cut down to one view's sequence of events. */
    private static final class Viewer {
        final FirstPictures pictures;
        final Page page;
        final Canvas canvas = new Canvas(PALETTE, COLS, ROWS);
        Placeholders.Filled filled;
        Document doc;
        HtmlRenderer.Frame frame;
        BufferedImage borrowed;
        ScrollCache strip;
        Element hover, pressed;
        double scroll;
        List<Element> drawnHover = List.of(), drawnPress = List.of();
        /** Whether the last first picture was taken from the cache. */
        boolean took;

        Viewer(FirstPictures pictures, Page page) {
            this.pictures = pictures;
            this.page = page;
        }

        /** Session.show and then refresh: a new copy of the page, its first picture taken (shared, whole) if it can be. */
        void show(Placeholders.Filled f) {
            Document old = doc;
            filled = f;
            if (hover == null && pressed == null && scroll == 0) {
                FirstPictures.Key k = key(page, f);
                FirstPictures.Claim c = pictures.claim(k, p -> {});
                if (c.picture() != null) {
                    doc = c.picture().document();
                    canvas.show(c.picture().canvas);
                    borrowed = c.picture().image();
                    frame = c.picture().frame;
                    strip = null;
                    drawnHover = drawnPress = List.of();
                    took = true;
                    return;
                }
                assertTrue(c.mine());
                took = false;
                doc = f.copy();
                draw();
                FirstPictures.Picture p = FirstPictures.Picture.of(k, frame, canvas.copy());
                borrowed = frame.image();
                pictures.publish(k, p);
                return;
            }
            doc = f.copy();
            hover = counterpart(hover, old, doc);
            pressed = counterpart(pressed, old, doc);
            draw();
        }

        void hover(Element e) {
            hover = counterpart(e, e.ownerDocument(), doc);
            draw();
        }

        void press(Element e) {
            pressed = counterpart(e, e.ownerDocument(), doc);
            draw();
        }

        void scrollTo(double y) {
            scroll = ScrollCache.snap(Math.max(0, Math.min(frame.maxScroll(), y)), SCALE);
            draw();
        }

        private void draw() {
            HtmlRenderer.Frame before = frame;
            var state = HtmlRenderer.state(hover, pressed);
            List<Element> hk = page.styles.stateKey(hover, false), pk = page.styles.stateKey(pressed, true);
            boolean same = before != null && before.document() == doc;
            double at = same ? Math.max(0, Math.min(scroll, before.maxScroll())) : scroll;
            if (strip != null && !(same && strip.of(page, doc, W, H, SCALE) && strip.covers(at))) strip = null;
            boolean still = same && Math.abs(before.scrollY() - at) < 0.01;
            Set<Element> moved = Collections.newSetFromMap(new IdentityHashMap<>());
            if (same) {
                for (Element e : drawnHover) if (!hk.contains(e)) moved.add(e);
                for (Element e : hk) if (!drawnHover.contains(e)) moved.add(e);
                for (Element e : drawnPress) if (!pk.contains(e)) moved.add(e);
                for (Element e : pk) if (!drawnPress.contains(e)) moved.add(e);
            }
            HtmlRenderer.Frame f = null;
            boolean redraw = false;
            if (strip != null && !moved.isEmpty()) {
                Repaint.Result r = Repaint.partial(page, doc, W, H, SCALE, strip.strip().scrollY(), state, strip.strip(), moved, 0);
                if (r.frame() == null) {
                    strip = null;
                    redraw = true;
                } else {
                    strip.patched(r.frame());
                }
            }
            if (strip != null) {
                f = strip.frame(at);
            } else if (still && !redraw && !moved.isEmpty()) {
                HtmlRenderer.Frame base = before.image() == borrowed ? FirstPictures.own(before) : before;
                Repaint.Result r = Repaint.partial(page, doc, W, H, SCALE, before.scrollY(), state, base, moved, 0);
                f = r.frame();
            } else if (same && !still && ScrollCache.possible(before)) {
                strip = ScrollCache.paint(page, doc, W, H, SCALE, at, before.scrollY(), state, ScrollCache.VIEWS, 0);
                if (strip != null) f = strip.frame(at);
            }
            if (f == null) f = HtmlRenderer.render(page, doc, W, H, SCALE, scroll, state, null, null, 0);
            canvas.draw(f.image(), 0, 0);
            frame = f;
            drawnHover = hk;
            drawnPress = pk;
        }

        /** What the canvas publishes. */
        byte[][] tiles() {
            Canvas.Snapshot s = canvas.snapshot();
            byte[][] out = new byte[COLS * ROWS][];
            for (int t = 0; t < out.length; t++) out[t] = s.tile(t, Canvas.Encoding.HI);
            return out;
        }

        /** Where in document order the element the pointer finds at each point of a grid is (-1 for none). */
        List<Integer> hitGrid() {
            List<Element> all = doc.getAllElements();
            List<Integer> out = new ArrayList<>();
            for (int y = 4; y < H; y += 16) {
                for (int x = 4; x < W; x += 16) {
                    Element e = frame.elementAt(x / SCALE, y / SCALE);
                    out.add(e == null ? -1 : indexOf(all, e));
                }
            }
            return out;
        }
    }

    private static int indexOf(List<Element> all, Element e) {
        for (int i = 0; i < all.size(); i++) if (all.get(i) == e) return i;
        return -2; // an element of another document
    }

    private static Element counterpart(Element e, Document from, Document to) {
        if (e == null) return null;
        if (from == to) return e;
        int i = indexOf(from.getAllElements(), e);
        return i < 0 ? null : to.getAllElements().get(i);
    }

    private static int[] pixels(HtmlRenderer.Frame f) {
        int[] all = ((DataBufferInt) f.image().getRaster().getDataBuffer()).getData();
        return java.util.Arrays.copyOf(all, W * H);
    }

    /** The largest difference in any channel of any pixel. */
    private static int worst(int[] a, int[] b) {
        int worst = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == b[i]) continue;
            for (int s = 0; s < 32; s += 8) worst = Math.max(worst, Math.abs((a[i] >> s & 255) - (b[i] >> s & 255)));
        }
        return worst;
    }

    private static void assertSameAs(Viewer fresh, Viewer taken, String step) {
        assertEquals(0, worst(pixels(fresh.frame), pixels(taken.frame)), step + ": the pictures differ");
        byte[][] a = fresh.tiles(), b = taken.tiles();
        for (int t = 0; t < a.length; t++) assertArrayEquals(a[t], b[t], step + ": tile " + t + " differs");
        assertEquals(fresh.hitGrid(), taken.hitGrid(), step + ": the pointer finds other elements");
        assertEquals(fresh.frame.scrollY(), taken.frame.scrollY(), step);
        assertEquals(fresh.frame.pageHeight(), taken.frame.pageHeight(), step);
    }

    /** The cards with a :hover rule of their own, in document order, in [doc]. */
    private static List<Element> cardsOf(Document doc) {
        return doc.select(".card");
    }

    @Test
    void aTakenFirstPictureCarriesOnLikeADrawnOne() {
        FirstPictures pictures = new FirstPictures(16, 64L << 20);
        Page page = page();
        Viewer a = new Viewer(pictures, page), b = new Viewer(pictures, page);
        a.show(fill(page, "Alex", 3));
        b.show(fill(page, "Steve", 3));
        assertFalse(a.took);
        assertTrue(b.took, "the second viewer takes the first one's picture");
        assertSameAs(a, b, "first picture");

        List<Element> ca = cardsOf(a.doc), cb = cardsOf(b.doc);
        record Step(String name, Runnable onA, Runnable onB) {}
        List<Step> steps = List.of(
                new Step("hover card 1", () -> a.hover(ca.get(1)), () -> b.hover(cb.get(1))),
                new Step("press card 1", () -> a.press(ca.get(1)), () -> b.press(cb.get(1))),
                new Step("release", () -> { a.pressed = null; a.draw(); }, () -> { b.pressed = null; b.draw(); }),
                new Step("hover card 2", () -> a.hover(ca.get(2)), () -> b.hover(cb.get(2))),
                new Step("scroll down", () -> a.scrollTo(160), () -> b.scrollTo(160)),
                new Step("hover card 5 in the tall picture", () -> a.hover(ca.get(5)), () -> b.hover(cb.get(5))),
                new Step("scroll down more", () -> a.scrollTo(320), () -> b.scrollTo(320)),
                new Step("refresh, hovering", () -> a.show(fill(page, "Alex", 4)), () -> b.show(fill(page, "Steve", 4))),
                new Step("hover gone, back to the top", () -> { a.hover = null; a.scrollTo(0); }, () -> { b.hover = null; b.scrollTo(0); }));
        for (Step s : steps) {
            s.onA.run();
            s.onB.run();
            assertSameAs(a, b, s.name);
        }

        // A refresh at the top with nothing hovered is a first picture again: drawn by one, taken by the other.
        a.show(fill(page, "Alex", 5));
        b.show(fill(page, "Steve", 5));
        assertFalse(a.took);
        assertTrue(b.took);
        assertSameAs(a, b, "refresh at the top");
        b.hover(cardsOf(b.doc).get(0));
        a.hover(cardsOf(a.doc).get(0));
        assertSameAs(a, b, "hover after a refresh");

        // After both have painted over what they shared, a newcomer still gets the page as first drawn.
        Viewer c = new Viewer(pictures, page), fresh = new Viewer(new FirstPictures(16, 64L << 20), page);
        c.show(fill(page, "Kim", 5));
        fresh.show(fill(page, "Kim", 5));
        assertTrue(c.took);
        assertFalse(fresh.took);
        assertSameAs(fresh, c, "a newcomer after the others hovered");
    }

    @Test
    void anotherRevisionSizeOrValueIsNotTaken() {
        FirstPictures pictures = new FirstPictures(16, 64L << 20);
        Page v1 = page(), v2 = page();
        Viewer a = new Viewer(pictures, v1);
        a.show(fill(v1, "Alex", 3));
        FirstPictures.Key k1 = key(v1, a.filled);
        assertNotNull(pictures.get(k1));

        // The page file reloaded: the same name, a new revision.
        Placeholders.Filled f2 = fill(v2, "Alex", 3);
        assertEquals(a.filled.key(), f2.key());
        assertTrue(pictures.claim(key(v2, f2), p -> {}).mine(), "a new revision is drawn, not taken");
        assertTrue(pictures.claim(new FirstPictures.Key("lobby", v1, COLS, ROWS, 2, a.filled.key()), p -> {}).mine(), "another scale is drawn");
        assertTrue(pictures.claim(new FirstPictures.Key("lobby", v1, COLS + 1, ROWS, SCALE, a.filled.key()), p -> {}).mine(), "another size is drawn");
        assertTrue(pictures.claim(key(v1, fill(v1, "Alex", 4)), p -> {}).mine(), "other placeholder values are drawn");

        pictures.invalidate("lobby", v2);
        assertNull(pictures.get(k1), "a reload lets go of the old revision");
        // A picture of the old revision that lands after the reload is not kept.
        Viewer late = new Viewer(new FirstPictures(16, 64L << 20), v1);
        late.show(fill(v1, "Alex", 3));
        pictures.claim(k1, p -> {});
        pictures.publish(k1, late.pictures.get(k1));
        assertNull(pictures.get(k1));
    }

    @Test
    void aPageFilledWithTheViewersOwnValuesIsPersonal() {
        Page own = Page.of("<html><body><h1>Welcome, {player}</h1><p>{online} online</p></body></html>", Path.of("."), new Page.Loader(false, null));
        Placeholders.Filled alex = fill(own, "Alex", 3), steve = fill(own, "Steve", 3);
        assertTrue(alex.personal());
        assertTrue(steve.personal());
        assertNotEquals(alex.key(), steve.key());

        Page shared = page();
        Placeholders.Filled x = fill(shared, "Alex", 3), y = fill(shared, "Steve", 3), z = fill(shared, "Steve", 4);
        assertFalse(x.personal());
        assertEquals(x.key(), y.key(), "values anyone sees alike are shared");
        assertNotEquals(y.key(), z.key(), "but only while they are the same");
        // Filled for nobody in particular (a shared wall, the warm-up): never personal.
        Placeholders.Filled nobody = Placeholders.fill(own.document, t -> t.replace("{online}", "3"), null);
        assertFalse(nobody.personal());
    }

    @Test
    void oneDrawsWhileOthersWaitAndAFailureIsNotKept() {
        FirstPictures pictures = new FirstPictures(16, 64L << 20);
        Page page = page();
        FirstPictures.Key k = key(page, fill(page, "Alex", 3));
        assertTrue(pictures.claim(k, p -> {}).mine());
        List<FirstPictures.Picture> told = new ArrayList<>();
        FirstPictures.Claim second = pictures.claim(k, told::add);
        assertFalse(second.mine());
        assertNull(second.picture());
        pictures.abandon(k);
        assertEquals(1, told.size());
        assertNull(told.get(0), "a waiter hears that it was not drawn");
        assertNull(pictures.get(k));
        assertTrue(pictures.claim(k, p -> {}).mine(), "and the next asks to draw it");
    }

    @Test
    void keptWithinItsCountAndBytes() {
        Page page = page();
        Viewer probe = new Viewer(new FirstPictures(16, 64L << 20), page);
        probe.show(fill(page, "Alex", 0));
        long one = probe.pictures.get(key(page, probe.filled)).bytes;
        FirstPictures pictures = new FirstPictures(16, 2 * one + one / 2);
        List<FirstPictures.Key> keys = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Viewer v = new Viewer(pictures, page);
            v.show(fill(page, "Alex", i));
            keys.add(key(page, v.filled));
        }
        assertEquals(2, pictures.size());
        assertTrue(pictures.bytes() <= 2 * one + one / 2);
        assertNull(pictures.get(keys.get(0)), "the least recently used goes first");
        FirstPictures few = new FirstPictures(1, 64L << 20);
        new Viewer(few, page).show(fill(page, "Alex", 0));
        new Viewer(few, page).show(fill(page, "Alex", 1));
        assertEquals(1, few.size());
    }

    /** A wire that keeps what one client has: each map's colours, and how many patches it was sent. */
    private static final class Client implements Wire {
        final Map<Integer, byte[]> maps = new HashMap<>();
        int patches;

        @Override
        public Object patch(int mapId, int x, int y, int w, int h, byte[] colors) {
            return new int[][]{{mapId, x, y, w, h}};
        }

        @Override
        public Object repoint(MapWall wall, int tile, int mapId) {
            return null;
        }

        @Override
        public Frames frames(Player player) {
            return null;
        }

        @Override
        public boolean write(Player player, List<Object> packets, boolean together, Consumer<Boolean> done) {
            patches += packets.size();
            done.accept(true);
            return true;
        }
    }

    private static Player player() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (self, m, args) -> switch (m.getName()) {
            case "getUniqueId" -> id;
            case "hashCode" -> id.hashCode();
            case "equals" -> self == args[0];
            default -> null;
        });
    }

    /** Patches the sender writes to a client who has nothing, sent [canvas]. */
    private static int patchesFor(Canvas canvas) {
        MapWall wall = new MapWall(null, 0, 100, 0, BlockFace.NORTH, COLS, ROWS, 7000);
        Client client = new Client();
        MapSender sender = new MapSender(client, PALETTE, Integer.MAX_VALUE, Logger.getAnonymousLogger());
        sender.update(List.of(new MapSender.Assignment(player(), wall, canvas, Canvas.Encoding.HI, null)));
        for (int i = 0; i < 4; i++) sender.pass();
        return client.patches;
    }

    @Test
    void aTakenPictureIsSentOnceAMap() {
        FirstPictures pictures = new FirstPictures(16, 64L << 20);
        Page page = page();
        new Viewer(pictures, page).show(fill(page, "Alex", 3));
        Viewer b = new Viewer(pictures, page);
        b.show(fill(page, "Steve", 3));
        assertTrue(b.took);
        assertEquals(COLS * ROWS, patchesFor(b.canvas), "one patch a map");
        // The kept canvas and the one that took it publish the very same arrays: nothing is encoded again.
        assertSame(pictures.get(key(page, b.filled)).canvas.snapshot().tile(0, Canvas.Encoding.HI), b.canvas.snapshot().tile(0, Canvas.Encoding.HI));
    }
}
