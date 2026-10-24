package ai.resourcepack.ui;

import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.framework.api.Canvas;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Real sessions ({@link SessionRig}) that take a kept first picture share it, whole, until they draw: its canvas's
 * tiles and colours, its frame and image, and its document. Such a session must then carry on exactly as one that drew
 * everything itself, through hovers, scrolls, refreshes and a reload, and whatever it draws must never reach the kept
 * picture or the sessions still sharing it.
 */
class SharedPictureTest {
    private static final int COLS = 4, ROWS = 3, W = COLS * 128, H = ROWS * 128;
    private static final String NAME = "lobby";

    @TempDir
    Path dir;
    private SessionRig rig;

    private static String html(String title) {
        StringBuilder s = new StringBuilder("""
                <html><head><meta name="ui:refresh" content="1"><style>
                body { margin: 0; font: 16px sans-serif; background: linear-gradient(#123, #345); color: white; }
                header { padding: 12px; font-size: 22px; backdrop-filter: blur(4px); background: rgba(255,255,255,.1); }
                .card { margin: 10px; padding: 14px; border-radius: 8px; background: #2a4a6a; box-shadow: 0 2px 6px rgba(0,0,0,.5); }
                .card:hover { background: #4a7aaa; box-shadow: 0 4px 14px rgba(0,0,0,.7); }
                a { color: #9cf; } a:hover { color: #fff; text-decoration: underline; }
                </style></head><body>
                """);
        s.append("<header>").append(title).append(": {online} online of {max}</header>\n");
        for (int i = 0; i < 14; i++) s.append("<div class=card>Card ").append(i).append(" <a href=\"#\">open {online}</a></div>\n");
        return s.append("</body></html>").toString();
    }

    @BeforeEach
    void setUp() throws Exception {
        SessionRig.ONLINE.set(3);
        SessionRig.TICK.set(1000);
        Files.writeString(dir.resolve(NAME + ".html"), html("Lobby"));
        rig = new SessionRig(dir, 4);
        assertNotNull(rig.pages.load(NAME).get(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    private Session open(MCGUIPlugin plugin) {
        Session s = rig.open(plugin, NAME, COLS, ROWS, H);
        rig.quiet();
        return s;
    }

    /** Where each card is in the page, in CSS pixels from its top (the scale is 1). */
    private double[] cardTops() {
        Page page = rig.pages.loaded(NAME);
        Document d = Placeholders.fill(page.document, t -> t, null).copy();
        HtmlRenderer.Frame f = HtmlRenderer.render(page, d, W, H, 1, 0, HtmlRenderer.state(null, null));
        List<Element> cards = d.select(".card");
        double[] tops = new double[cards.size()];
        for (int i = 0; i < tops.length; i++) tops[i] = f.topOf(cards.get(i));
        return tops;
    }

    /** What a viewer of [c] sees: every pixel of its colours, and every tile as sent with and without the pack. */
    private record Seen(int[] pixels, byte[][] hi, byte[][] plain) {}

    private static Seen seen(Canvas c) {
        Canvas.Snapshot s = c.snapshot();
        byte[][] hi = new byte[c.tiles()][], plain = new byte[c.tiles()][];
        for (int t = 0; t < hi.length; t++) {
            hi[t] = s.tile(t, Canvas.Encoding.HI);
            plain[t] = s.tile(t, Canvas.Encoding.PLAIN);
        }
        return new Seen(c.thumbnail(c.width(), c.height()), hi, plain);
    }

    /** The largest difference in any channel of any pixel. */
    private static int worst(int[] a, int[] b) {
        int worst = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == b[i]) continue;
            for (int s = 0; s < 24; s += 8) worst = Math.max(worst, Math.abs((a[i] >> s & 255) - (b[i] >> s & 255)));
        }
        return worst;
    }

    private static void assertAlike(Seen fresh, Seen shared, String step) {
        assertEquals(0, worst(fresh.pixels, shared.pixels), step + ": the pictures differ");
        for (int t = 0; t < fresh.hi.length; t++) {
            assertArrayEquals(fresh.hi[t], shared.hi[t], step + ": tile " + t + " differs");
            assertArrayEquals(fresh.plain[t], shared.plain[t], step + ": plain tile " + t + " differs");
        }
    }

    private static int[] pixels(java.awt.image.BufferedImage img) {
        return ((DataBufferInt) img.getRaster().getDataBuffer()).getData().clone();
    }

    /** The kept picture of the page as it is now, filled for nobody in particular. */
    private FirstPictures.Picture kept(MCGUIPlugin plugin) {
        Page page = rig.pages.loaded(NAME);
        String key = rig.onServer(() -> Placeholders.fill(page.document, null).key());
        return SessionRig.pictures(plugin).get(new FirstPictures.Key(NAME, page, COLS, ROWS, 1, key));
    }

    private void both(Session a, Session b, java.util.function.Consumer<Session> step) {
        rig.onServer(() -> {
            step.accept(a);
            step.accept(b);
        });
        rig.quiet();
    }

    @Test
    void aSessionSharingAPictureCarriesOnLikeOneThatDrewItsOwn() throws IOException, Exception {
        MCGUIPlugin sharing = rig.plugin(16), alone = rig.plugin(0);
        Session drew = open(sharing);
        FirstPictures.Picture first = kept(sharing);
        assertNotNull(first, "the first session's picture is kept");
        Seen keptAtFirst = seen(first.canvas);
        int[] keptImage = pixels(first.image());

        Session took = open(sharing), fresh = open(alone);
        assertSame(first.canvas.snapshot().tile(0, Canvas.Encoding.HI), took.canvas.snapshot().tile(0, Canvas.Encoding.HI),
                "the second session shows the kept tiles themselves");
        assertAlike(seen(fresh.canvas), seen(took.canvas), "first picture");

        double[] tops = cardTops();
        record Step(String name, java.util.function.Consumer<Session> act) {}
        List<Step> steps = List.of(
                new Step("hover card 1", s -> s.pointer(60, (int) tops[1] + 12)),
                new Step("hover card 2", s -> s.pointer(60, (int) tops[2] + 12)),
                new Step("pointer gone", Session::pointerGone),
                new Step("scroll down", s -> s.scroll(1)),
                new Step("hover in the scrolled view", s -> s.pointer(60, (int) tops[3] + 12 - 80)),
                new Step("scroll down more", s -> s.scroll(1)),
                new Step("pointer gone, back to the top", s -> {
                    s.pointerGone();
                    s.scroll(-2);
                }));
        for (Step s : steps) {
            both(took, fresh, s.act);
            assertAlike(seen(fresh.canvas), seen(took.canvas), s.name);
            // Whatever it drew, it drew into copies of its own.
            assertAlike(keptAtFirst, seen(first.canvas), s.name + ": the kept picture's canvas");
            assertArrayEquals(keptImage, pixels(first.image()), s.name + ": the kept picture's image");
        }

        // A refresh with new values at the top, nothing hovered: a first picture again, drawn by one and taken by the other.
        SessionRig.ONLINE.set(4);
        int now = SessionRig.TICK.addAndGet(40);
        rig.onServer(() -> drew.tick(now));
        rig.quiet();
        both(took, fresh, s -> s.tick(now));
        assertAlike(seen(fresh.canvas), seen(took.canvas), "refresh at the top");
        assertSame(drew.canvas.snapshot().tile(0, Canvas.Encoding.HI), took.canvas.snapshot().tile(0, Canvas.Encoding.HI), "taken again");
        both(took, fresh, s -> s.pointer(60, (int) tops[0] + 12));
        assertAlike(seen(fresh.canvas), seen(took.canvas), "hover after a refresh");

        // A refresh while hovering carries the hover over to the new copy.
        SessionRig.ONLINE.set(5);
        int later = SessionRig.TICK.addAndGet(40);
        both(took, fresh, s -> s.tick(later));
        assertAlike(seen(fresh.canvas), seen(took.canvas), "refresh while hovering");

        // The page file changes: every session shows the new one, the sharing ones from the new revision's picture.
        Page old = rig.pages.loaded(NAME);
        Path file = dir.resolve(NAME + ".html");
        Files.writeString(file, html("Hall"));
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000));
        rig.pages.checkForChanges();
        assertNotSame(old, rig.pages.loaded(NAME));
        rig.onServer(() -> {
            SessionRig.pictures(sharing).invalidate(NAME, rig.pages.loaded(NAME));
            drew.pointerGone();
            drew.reloaded(NAME);
        });
        rig.quiet();
        both(took, fresh, Session::pointerGone);
        both(took, fresh, s -> s.reloaded(NAME));
        assertAlike(seen(fresh.canvas), seen(took.canvas), "reload");
        assertSame(drew.canvas.snapshot().tile(0, Canvas.Encoding.HI), took.canvas.snapshot().tile(0, Canvas.Encoding.HI), "the reloaded page taken");
        both(took, fresh, s -> s.pointer(60, (int) tops[1] + 12));
        assertAlike(seen(fresh.canvas), seen(took.canvas), "hover after a reload");

        // All that drawing reached neither the picture kept at first nor the session that drew it.
        assertAlike(keptAtFirst, seen(first.canvas), "the first kept picture's canvas");
        assertArrayEquals(keptImage, pixels(first.image()), "the first kept picture's image");

        // A newcomer takes the reloaded page's picture, which the others have since drawn over, as if it drew it itself.
        Session late = open(sharing), lateFresh = open(alone);
        assertAlike(seen(lateFresh.canvas), seen(late.canvas), "a newcomer after the others drew");
        both(late, lateFresh, s -> s.pointer(60, (int) tops[2] + 12));
        assertAlike(seen(lateFresh.canvas), seen(late.canvas), "a newcomer's hover");
    }

    @Test
    void sessionsSharingAPictureDrawAtOnceWithoutTouchingEachOther() {
        MCGUIPlugin sharing = rig.plugin(16), alone = rig.plugin(0);
        int n = 8;
        Session first = open(sharing);
        Seen picture = seen(first.canvas);
        List<Session> took = new ArrayList<>(), fresh = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            took.add(open(sharing));
            fresh.add(open(alone));
        }
        double[] tops = cardTops();
        for (int round = 0; round < 3; round++) {
            int r = round;
            // Every session hovers its own card at once, on four render threads, half of them scrolled.
            rig.onServer(() -> {
                for (int i = 0; i < n; i++) {
                    int card = (i + r) % 6;
                    for (Session s : List.of(took.get(i), fresh.get(i))) {
                        if (r == 1 && i % 2 == 0) s.scroll(1);
                        if (r == 2 && i % 2 == 0) s.scroll(-1);
                        s.pointer(40 + 10 * i, (int) tops[card] + 12 - (r == 1 && i % 2 == 0 ? 80 : 0));
                    }
                }
            });
            rig.quiet();
            for (int i = 0; i < n; i++) assertAlike(seen(fresh.get(i).canvas), seen(took.get(i).canvas), "round " + r + ", session " + i);
        }
        // The first session never hovered: it still shows the picture they all started from.
        assertAlike(picture, seen(first.canvas), "the session whose picture they took");
    }
}
