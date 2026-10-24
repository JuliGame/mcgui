package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.html.HoverAhead;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.html.SharedRepaints;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real sessions ({@link SessionRig}) on one page share their hover repaints ({@link SharedRepaints}): one draws, the
 * others in the same state take it, and every viewer sees exactly what a session drawing everything itself shows. A
 * page filled with a player's own values ({@code {player}}) shares nothing when sharing.personal-pages is off, and one
 * player's drawing ahead is not done again for the next.
 */
class SharedHoverTest {
    private static final int COLS = 4, ROWS = 3, W = COLS * 128, H = ROWS * 128;
    private static final String PUBLIC = "lobby", PERSONAL = "mine";

    @TempDir
    Path dir;
    private SessionRig rig;

    private static String html(String greeting) {
        StringBuilder s = new StringBuilder("""
                <html><head><style>
                body { margin: 0; font: 16px sans-serif; background: linear-gradient(#123, #345); color: white; }
                header { padding: 12px; font-size: 22px; backdrop-filter: blur(4px); background: rgba(255,255,255,.1); }
                .card { margin: 10px; padding: 14px; border-radius: 8px; background: #2a4a6a; box-shadow: 0 2px 6px rgba(0,0,0,.5); }
                .card:hover { background: #4a7aaa; box-shadow: 0 4px 14px rgba(0,0,0,.7); }
                </style></head><body>
                """);
        s.append("<header>").append(greeting).append("</header>\n");
        for (int i = 0; i < 6; i++) s.append("<div class=card>Card ").append(i).append("</div>\n");
        return s.append("</body></html>").toString();
    }

    @BeforeEach
    void setUp() throws Exception {
        SessionRig.TICK.set(1000);
        Files.writeString(dir.resolve(PUBLIC + ".html"), html("Welcome"));
        Files.writeString(dir.resolve(PERSONAL + ".html"), html("Welcome, {player}"));
        rig = new SessionRig(dir, 4);
        assertNotNull(rig.pages.load(PUBLIC).get(30, TimeUnit.SECONDS));
        assertNotNull(rig.pages.load(PERSONAL).get(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() {
        rig.close();
        SessionRig.PLAYERS.clear();
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

    private Session open(MCGUIPlugin plugin, String page, UUID viewer) {
        Session s = rig.open(plugin, page, COLS, ROWS, H, viewer);
        rig.quiet();
        return s;
    }

    /** Where to put the pointer to hover each card, in canvas pixels (the scale is 1), and last, a place over no card. */
    private int[][] spots() {
        Page page = rig.pages.loaded(PUBLIC);
        Document d = page.document.clone();
        HtmlRenderer.Frame f = HtmlRenderer.render(page, d, W, H, 1, 0, HtmlRenderer.state(null, null));
        List<Element> cards = d.select(".card");
        int[][] out = new int[cards.size() + 1][];
        for (int i = 0; i < cards.size(); i++) out[i] = new int[]{40, (int) f.topOf(cards.get(i)) + 10};
        out[cards.size()] = new int[]{W - 3, 20};
        return out;
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

    private void point(Session s, int[] at) {
        rig.onServer(() -> s.pointer(at[0], at[1]));
        rig.quiet();
    }

    @Test
    void playersInOneStateShareEveryHover() {
        MCGUIPlugin plugin = rig.plugin(16, 0), alone = rig.plugin(0, 0);
        Session a = open(plugin, PUBLIC, UUID.randomUUID()), b = open(plugin, PUBLIC, UUID.randomUUID());
        Session own = open(alone, PUBLIC, UUID.randomUUID());
        assertSame((Object) get(a, "doc"), get(b, "doc"), "the two sessions took one first picture, and with it one document");
        SharedRepaints shared = plugin.repaints();
        int hovers = 0;
        for (int[] at : spots()) {
            point(a, at);
            point(b, at);
            point(own, at);
            hovers++;
            String step = "pointer at " + at[0] + "," + at[1];
            assertSeenAlike(own.canvas, a.canvas, step + ", the session that drew it");
            assertSeenAlike(own.canvas, b.canvas, step + ", the session that took it");
            // Every hover the first session drew (a repaint, or found to need a whole picture), and the second took each
            // repaint; a hover that is a whole picture is drawn whole by each, as it is known not to be a repaint.
            assertEquals(hovers, shared.drawn(), step + ": hovers drawn (" + shared.stats() + ")");
            assertEquals(hovers - shared.wholes(), shared.hits(), step + ": the second session drew a hover the first had drawn (" + shared.stats() + ")");
        }
        assertTrue(shared.hits() >= 3, "few hovers were repaints: " + shared.stats());
        assertEquals(0, alone.repaints().hits(), "a session alone took a hover");
    }

    /**
     * A page filled with each player's name gives each a document of their own, and with sharing.personal-pages off a
     * hover of their own (PersonalShareTest has it on).
     */
    @Test
    void personalPagesShareNothingWithSharingOff() {
        UUID alex = UUID.randomUUID(), sam = UUID.randomUUID();
        SessionRig.PLAYERS.put(alex, "Alex");
        SessionRig.PLAYERS.put(sam, "Sam");
        MCGUIPlugin plugin = rig.plugin(16, 0, false), alone = rig.plugin(16, 0, false);
        Session a = open(plugin, PERSONAL, alex), b = open(plugin, PERSONAL, sam);
        Session ownA = open(alone, PERSONAL, alex), ownB = open(alone, PERSONAL, sam);
        assertNotSame((Object) get(a, "doc"), get(b, "doc"));
        assertTrue(((Document) get(a, "doc")).text().contains("Alex") && ((Document) get(b, "doc")).text().contains("Sam"), "not filled for each player");
        SharedRepaints shared = plugin.repaints();
        for (int[] at : spots()) {
            for (Session s : new Session[]{a, b, ownA, ownB}) point(s, at);
            String step = "pointer at " + at[0] + "," + at[1];
            assertSeenAlike(ownA.canvas, a.canvas, step + ", Alex");
            assertSeenAlike(ownB.canvas, b.canvas, step + ", Sam");
            assertFalse(java.util.Arrays.deepEquals(seen(a.canvas), seen(b.canvas)), step + ": Alex sees what Sam sees");
        }
        assertEquals(0, shared.hits(), "one player took another's hover on a page filled with their name");
        assertTrue(shared.drawn() > 0);
    }

    /** One player's pointer rests by a card: what is drawn ahead for them is not drawn again for another resting there too. */
    @Test
    void oneDrawingAheadServesEveryone() {
        MCGUIPlugin plugin = rig.plugin(16, 4);
        Session a = open(plugin, PUBLIC, UUID.randomUUID()), b = open(plugin, PUBLIC, UUID.randomUUID());
        int[] card = spots()[2], beside = {card[0], card[1] - 12};
        point(a, beside);
        HoverAhead aheadA = get(a, "hoverAhead"), aheadB = get(b, "hoverAhead");
        assertTrue(aheadA.prepared.get() > 0, "nothing was drawn ahead");
        point(b, beside);
        assertEquals(0, aheadB.prepared.get(), "drawn ahead again for the second session");
        point(b, card);
        assertTrue(aheadB.taken.get() >= 1, "the second session's hover was not the one drawn ahead for the first");
        assertTrue(plugin.repaints().aheadUsed() >= 1);
        point(a, card);
        assertSeenAlike(a.canvas, b.canvas, "both on the card");
    }
}
