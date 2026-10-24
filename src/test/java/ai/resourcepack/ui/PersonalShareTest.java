package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.html.SharedRepaints;
import ai.resourcepack.ui.html.paint.Painter;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Personal pages share what is not the player's own (Personal): a page greeting each player by name. The invariant is
 * pixels: whatever is shared, every player's picture equals, to the pixel, a fresh picture of their own copy of the
 * page, their canvas shows what a session drawing everything itself shows, and their frame (what clicks find) is of
 * their own copy. Two names of different lengths: in a header of fixed size the long one only runs on further, and
 * the rest is shared; in a header that grows to fit, the long one wraps and pushes everything down, and nothing is.
 */
class PersonalShareTest {
    private static final int COLS = 4, ROWS = 4, W = COLS * 128, H = ROWS * 128;
    /** Pages whose header keeps its size, and grows to fit what it says; and one whose only personal value is in a command. */
    private static final String FIXED = "fixed", FLOW = "flow", COMMAND = "command";
    private static final String SHORT = "Al", LONG = "Maximilian_Longn";

    @TempDir
    Path dir;
    private SessionRig rig;

    private static String html(String bodyClass, String greeting, String chest) {
        StringBuilder s = new StringBuilder("""
                <html><head><style>
                body { margin: 0; font: 16px sans-serif; background: linear-gradient(#123, #345); color: white; }
                header { padding: 12px; font-size: 22px; backdrop-filter: blur(4px); background: rgba(255,255,255,.1); }
                .fixed header { height: 26px; overflow: hidden; white-space: nowrap; }
                .flow header { width: 300px; }
                .card { margin: 10px; padding: 12px; border-radius: 8px; background: #2a4a6a; box-shadow: 0 2px 6px rgba(0,0,0,.5); }
                .card:hover { background: #4a7aaa; box-shadow: 0 4px 14px rgba(0,0,0,.7); }
                </style></head>
                """);
        s.append("<body class=").append(bodyClass).append(">\n<header>").append(greeting).append("</header>\n");
        // Under the header, a card that says the player's name: its hovers, and those of what is near either, are each player's own.
        s.append("<div class=card>").append(chest).append("</div>\n");
        for (int i = 1; i < 7; i++) {
            s.append("<div class=card").append(i == 4 ? " data-console=\"give {player} diamond\"" : "").append(">Card ").append(i).append("</div>\n");
        }
        return s.append("</body></html>").toString();
    }

    @BeforeEach
    void setUp() throws Exception {
        SessionRig.TICK.set(1000);
        Files.writeString(dir.resolve(FIXED + ".html"), html("fixed", "Welcome to the market, {player}", "{player}'s chest"));
        Files.writeString(dir.resolve(FLOW + ".html"), html("flow", "Welcome to the market, {player}", "{player}'s chest"));
        Files.writeString(dir.resolve(COMMAND + ".html"), html("fixed", "Welcome to the market", "The chest"));
        rig = new SessionRig(dir, 4);
        for (String p : List.of(FIXED, FLOW, COMMAND)) assertNotNull(rig.pages.load(p).get(30, TimeUnit.SECONDS));
        SessionRig.COMMANDS.clear();
    }

    @AfterEach
    void tearDown() {
        rig.close();
        SessionRig.PLAYERS.clear();
        SessionRig.COMMANDS.clear();
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Session s, String field) {
        try {
            Field f = Session.class.getDeclaredField(field);
            f.setAccessible(true);
            return (T) f.get(s);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID player(String name) {
        UUID id = UUID.randomUUID();
        SessionRig.PLAYERS.put(id, name);
        return id;
    }

    private Session open(UIEnginePlugin plugin, String page, UUID viewer) {
        Session s = rig.open(plugin, page, COLS, ROWS, H, viewer);
        rig.quiet();
        return s;
    }

    /** Where to put the pointer to hover each card, in canvas pixels (the scale is 1), then a place over none. */
    private int[][] spots(String name) {
        Page page = rig.pages.loaded(name);
        Document d = page.document.clone();
        HtmlRenderer.Frame f = HtmlRenderer.render(page, d, W, H, 1, 0, HtmlRenderer.state(null, null));
        List<Element> cards = d.select(".card");
        List<int[]> out = new ArrayList<>();
        for (Element c : cards) {
            int y = (int) f.topOf(c) + 10;
            if (y < H - 4) out.add(new int[]{40, y});
        }
        out.add(new int[]{W - 3, H - 3});
        return out.toArray(new int[0][]);
    }

    private void point(Session s, int[] at) {
        rig.onServer(() -> s.pointer(at[0], at[1]));
        rig.quiet();
    }

    /** What a viewer of [c] sees: every tile as sent with the pack. */
    private static byte[][] seen(Canvas c) {
        Canvas.Snapshot s = c.snapshot();
        byte[][] hi = new byte[c.tiles()][];
        for (int t = 0; t < hi.length; t++) hi[t] = s.tile(t, Canvas.Encoding.HI);
        return hi;
    }

    private static void assertSeenAlike(Canvas expected, Canvas actual, String step) {
        byte[][] a = seen(expected), b = seen(actual);
        for (int t = 0; t < a.length; t++) assertArrayEquals(a[t], b[t], step + ": tile " + t + " differs");
    }

    private static int[] pixels(BufferedImage img) {
        return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }

    /**
     * [s]'s picture is, to the pixel, a fresh picture of its own copy of the page in its state; its frame, and every hit
     * region in it, is of that copy; and it shows none of [others]' names.
     */
    private static void assertOwnPicture(Session s, String step) {
        assertOwnPicture(s, step, 0);
    }

    /**
     * As {@link #assertOwnPicture(Session, String)}, within [levels] of a fresh picture: a chain of hover repaints is within
     * a level of one whoever draws it (RepaintTest), so after hovers what a player is shown is checked against what their
     * own session draws (a session that shares nothing), tile for tile, and against a fresh picture within a level.
     */
    private static void assertOwnPicture(Session s, String step, int levels) {
        HtmlRenderer.Frame f = get(s, "frame");
        Document d = get(s, "doc");
        assertSame(d, f.document(), step + ": the frame is not of the player's own copy");
        for (Painter.Hit h : f.hits()) assertSame(d, h.element().ownerDocument(), step + ": a hit region is of another copy");
        for (Element e : f.styles().keySet()) assertSame(d, e.ownerDocument(), step + ": a style is of another copy");
        Element hover = get(s, "hover"), pressed = get(s, "pressed");
        int w = f.image().getWidth(), hh = f.image().getHeight();
        HtmlRenderer.Frame fresh = HtmlRenderer.render(f.page(), d, w, hh, f.scale(), f.scrollY(), HtmlRenderer.state(hover, pressed));
        int[] a = pixels(fresh.image()), b = pixels(f.image());
        int differ = 0, worst = 0, x0 = w, y0 = hh, x1 = -1, y1 = -1;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == b[i]) continue;
            int by = 0;
            for (int sh = 0; sh < 32; sh += 8) by = Math.max(by, Math.abs(((a[i] >> sh) & 255) - ((b[i] >> sh) & 255)));
            worst = Math.max(worst, by);
            if (by <= levels) continue;
            differ++;
            x0 = Math.min(x0, i % w);
            x1 = Math.max(x1, i % w);
            y0 = Math.min(y0, i / w);
            y1 = Math.max(y1, i / w);
        }
        assertEquals(0, differ, step + ": " + differ + " pixels differ from a fresh picture of the player's own copy, by up to " + worst
                + ", within " + x0 + "," + y0 + " to " + x1 + "," + y1);
    }

    private static long count(Session s, String counter) {
        return ((AtomicLong) get(s, counter)).get();
    }

    /**
     * Two players whose names differ in length, in a header that keeps its size: the second player's first picture is
     * the first's with their own name drawn in, and every hover of a card that is not theirs alone is drawn once for both.
     */
    @Test
    void whatIsNotThePlayersOwnIsShared() {
        UUID al = player(SHORT), max = player(LONG);
        UIEnginePlugin plugin = rig.plugin(16, 0), alone = rig.plugin(16, 0, false);
        Session a = open(plugin, FIXED, al), b = open(plugin, FIXED, max);
        Session ownA = open(alone, FIXED, al), ownB = open(alone, FIXED, max);
        assertNotSame((Object) get(a, "doc"), get(b, "doc"));
        assertEquals(1, count(b, "fromTheirs"), "the second player's first picture was not drawn over the first's");
        assertEquals(0, count(ownB, "fromTheirs"), "a first picture was shared with sharing off");
        assertSeenAlike(ownA.canvas, a.canvas, "opened, " + SHORT);
        assertSeenAlike(ownB.canvas, b.canvas, "opened, " + LONG);
        assertFalse(java.util.Arrays.deepEquals(seen(a.canvas), seen(b.canvas)), "both players see one name");
        assertOwnPicture(a, "opened, " + SHORT);
        assertOwnPicture(b, "opened, " + LONG);
        SharedRepaints shared = plugin.repaints();
        for (int[] at : spots(FIXED)) {
            for (Session s : new Session[]{a, b, ownA, ownB}) point(s, at);
            String step = "pointer at " + at[0] + "," + at[1];
            assertSeenAlike(ownA.canvas, a.canvas, step + ", " + SHORT);
            assertSeenAlike(ownB.canvas, b.canvas, step + ", " + LONG);
            assertOwnPicture(a, step + ", " + SHORT);
            assertOwnPicture(b, step + ", " + LONG);
        }
        // The hovers far enough from both names: between the cards further down.
        assertTrue(shared.hits() >= 2, "few hovers were shared: " + shared.stats());
        assertEquals(0, alone.repaints().hits(), "a hover was shared with sharing off: " + alone.repaints().stats());
    }

    /** A long name that wraps the header pushes every card down: the layouts differ, and nothing is shared between them. */
    @Test
    void aNameThatMovesTheRestSharesNothing() {
        UUID al = player(SHORT), max = player(LONG);
        UIEnginePlugin plugin = rig.plugin(16, 0), alone = rig.plugin(16, 0, false);
        Session a = open(plugin, FLOW, al), b = open(plugin, FLOW, max);
        Session ownB = open(alone, FLOW, max);
        HtmlRenderer.Frame fa = get(a, "frame"), fb = get(b, "frame");
        Element cardA = ((Document) get(a, "doc")).selectFirst(".card"), cardB = ((Document) get(b, "doc")).selectFirst(".card");
        assertTrue(fb.topOf(cardB) > fa.topOf(cardA) + 10, "the long name did not push the cards down");
        assertEquals(0, count(b, "fromTheirs"), "a first picture was taken over a layout that moved");
        assertSeenAlike(ownB.canvas, b.canvas, "opened, " + LONG);
        assertOwnPicture(b, "opened, " + LONG);
        SharedRepaints shared = plugin.repaints();
        // The same card hovered by both, each where it is in their own picture.
        for (int i = 0; i < 3; i++) {
            int ya = (int) fa.topOf(((Document) get(a, "doc")).select(".card").get(i)) + 10;
            int yb = (int) fb.topOf(((Document) get(b, "doc")).select(".card").get(i)) + 10;
            point(a, new int[]{40, ya});
            point(b, new int[]{40, yb});
            point(ownB, new int[]{40, yb});
            assertSeenAlike(ownB.canvas, b.canvas, "card " + i + ", " + LONG);
            assertOwnPicture(a, "card " + i + ", " + SHORT);
            assertOwnPicture(b, "card " + i + ", " + LONG);
        }
        assertEquals(0, shared.hits(), "a hover was shared between pictures laid out differently: " + shared.stats());
    }

    /**
     * A value in an attribute nothing draws (a command) does not stop sharing, and a click on a shared picture runs the
     * clicking player's own command.
     */
    @Test
    void aClickRunsThePlayersOwnCommand() {
        UUID al = player(SHORT), max = player(LONG);
        UIEnginePlugin plugin = rig.plugin(16, 0);
        Session a = open(plugin, COMMAND, al), b = open(plugin, COMMAND, max);
        assertEquals(1, count(b, "fromTheirs"), "the second player's first picture was not the first's");
        assertSeenAlike(a.canvas, b.canvas, "opened: nothing drawn is either player's own");
        assertOwnPicture(b, "opened");
        int[] card = spots(COMMAND)[4];
        point(a, card);
        point(b, card);
        assertTrue(plugin.repaints().hits() >= 1, "the hover was not shared: " + plugin.repaints().stats());
        assertOwnPicture(b, "on the card");
        rig.onServer(() -> b.click(card[0], card[1]));
        rig.quiet();
        rig.onServer(() -> a.click(card[0], card[1]));
        rig.quiet();
        assertEquals(List.of("give " + LONG + " diamond", "give " + SHORT + " diamond"), SessionRig.COMMANDS);
    }

    /**
     * The shipped market, which greets each player by name in a header whose flexible spacer takes up what a longer
     * name leaves: the second player's first picture is the first's with the name and the spacer drawn again, to the
     * pixel a fresh picture of their own copy; and their hovers after it are their own session's.
     */
    @Test
    void theShippedMarketIsShared() throws Exception {
        Path src = Path.of("src/main/resources/pages");
        try (var all = Files.walk(src)) {
            for (Path p : (Iterable<Path>) all::iterator) {
                Path to = dir.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else if (!Files.exists(to)) Files.copy(p, to);
            }
        }
        assertNotNull(rig.pages.load("market").get(60, TimeUnit.SECONDS));
        UIEnginePlugin plugin = rig.plugin(16, 0), alone = rig.plugin(16, 0, false);
        UUID one = player("Fake1"), two = player("Fake_With_A_Long");
        Session a = rig.open(plugin, "market", 16, 9, 576, one);
        rig.quiet();
        Session b = rig.open(plugin, "market", 16, 9, 576, two);
        rig.quiet();
        Session ownB = rig.open(alone, "market", 16, 9, 576, two);
        rig.quiet();
        assertEquals(1, count(b, "fromTheirs"), "the second player's first picture was not the first's");
        assertOwnPicture(a, "opened, Fake1");
        assertOwnPicture(b, "opened, Fake_With_A_Long");
        assertSeenAlike(ownB.canvas, b.canvas, "opened, Fake_With_A_Long");
        // The product cards along the shelf, in canvas pixels (the view is 2048 x 1152 at 2x).
        for (int x : new int[]{931, 1228, 1525}) {
            for (Session s : new Session[]{a, b, ownB}) point(s, new int[]{x, 356});
            assertSeenAlike(ownB.canvas, b.canvas, "on the card at " + x + ", Fake_With_A_Long");
            assertOwnPicture(a, "on the card at " + x + ", Fake1", 1);
            assertOwnPicture(b, "on the card at " + x + ", Fake_With_A_Long", 1);
        }
        // The products' hovers are each player's own: the market's backdrop blurs read far enough that the greeting
        // is within reach of what a product's hover changes.
    }

    /** Players opening the page at the same moment: one draws it whole, the others wait for it and draw in only their own names. */
    @Test
    void openedAtOnceItIsDrawnOnce() {
        UIEnginePlugin plugin = rig.plugin(16, 0);
        List<Session> sessions = new ArrayList<>();
        for (String name : List.of("Al", "Bo", "Cy", "Di", "Ed", "Flo")) sessions.add(rig.open(plugin, FIXED, COLS, ROWS, H, player(name)));
        rig.quiet();
        long whole = 0, theirs = 0;
        for (Session s : sessions) {
            whole += count(s, "drewWhole");
            theirs += count(s, "fromTheirs");
            assertOwnPicture(s, "opened");
        }
        assertEquals(1, whole, "drawn whole more than once");
        assertEquals(sessions.size() - 1, theirs);
    }
}
