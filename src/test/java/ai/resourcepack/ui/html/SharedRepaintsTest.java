package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.paint.Painter;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hover repaints shared between sessions ({@link SharedRepaints}): a repaint one session drew, applied by another in
 * the same state to its own picture, is exactly what that session would have drawn; nothing is shared between
 * different documents (a page filled with {@code {player}} gives each player a document of their own) or, on a page
 * whose images arrive late, between different pictures; a repaint being drawn is waited for, not drawn twice; and
 * drawing ahead neither draws what another session drew or is drawing, nor goes over its CPU budget.
 */
class SharedRepaintsTest {
    private static final int W = 2048, H = 1152;
    private static final double SCALE = 2;
    private static final Page.Loader OFFLINE = new Page.Loader(false, null);

    @AfterEach
    void unlimited() {
        HoverAhead.budget(1);
    }

    private static int[] pixels(BufferedImage img) {
        return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }

    private static HtmlRenderer.Frame copy(HtmlRenderer.Frame f) {
        BufferedImage from = f.image(), to = new BufferedImage(from.getWidth(), from.getHeight(), from.getType());
        System.arraycopy(pixels(from), 0, pixels(to), 0, pixels(from).length);
        return new HtmlRenderer.Frame(to, f.scale(), f.viewportWidth(), f.viewportHeight(), f.pageHeight(), f.scrollY(), f.hits(), f.styles(), f.document(), f.page());
    }

    private static Page shipped(String name) throws IOException {
        return Page.load(Path.of("src/main/resources/pages/" + name + ".html"), OFFLINE);
    }

    /** Elements with a :hover rule of their own and a box on the view, in document order. */
    private static List<Element> targets(Page page, HtmlRenderer.Frame f) {
        Set<Element> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Painter.Hit h : f.hits()) {
            for (Element e = h.element(); e != null; e = e.parent()) if (page.styles.stateKey(e, false).contains(e)) seen.add(e);
        }
        List<Element> out = new ArrayList<>();
        for (Element e : f.document().getAllElements()) if (seen.contains(e)) out.add(e);
        return out;
    }

    /**
     * Two sessions on one document walk the same hovers. The first draws each repaint and keeps it; the second, on a
     * picture of its own that started equal, takes each one instead of drawing. Its picture must stay exactly the first
     * one's, with the same rectangle and hit regions, at every step, and equal a fresh picture of the state at the end.
     */
    private static int walk(String name, Page page) {
        Document doc = page.document.clone();
        SharedRepaints shared = new SharedRepaints(Long.MAX_VALUE);
        HtmlRenderer.Frame a = HtmlRenderer.render(page, doc, W, H, SCALE, 0, Selector.State.NONE), b = copy(a);
        List<Element> drawn = List.of();
        int taken = 0;
        Element last = null;
        for (Element target : targets(page, a)) {
            for (Element hover : new Element[]{target, null}) {
                List<Element> key = page.styles.stateKey(hover, false);
                Set<Element> moved = HoverAhead.changed(drawn, List.of(), key, List.of());
                if (moved.isEmpty()) continue;
                String step = name + ", " + (hover == null ? "leaving " : "entering ") + target.normalName() + "." + target.className();
                SharedRepaints.Key ka = HoverAhead.key(a, drawn, List.of(), key, List.of());
                SharedRepaints.Claim mine = shared.claim(ka, true);
                assertTrue(mine.mine(), step + ": nobody drew it yet");
                Repaint.Result ra = Repaint.partial(page, doc, W, H, SCALE, 0, HtmlRenderer.state(hover, null), a, moved, 0);
                shared.put(ka, ra.frame() == null ? null : Repaint.Prepared.of(ra), 1, false);

                SharedRepaints.Key kb = HoverAhead.key(b, drawn, List.of(), key, List.of());
                assertEquals(ka, kb, step + ": two equal pictures in one state have one key");
                SharedRepaints.Claim found = shared.claim(kb, true);
                assertTrue(found.kept(), step + ": not kept");
                if (ra.frame() == null) {
                    assertNull(found.prepared(), step + ": kept as partial, but the first session drew a whole picture");
                    a = HtmlRenderer.render(page, doc, W, H, SCALE, 0, HtmlRenderer.state(hover, null));
                    b = copy(a);
                } else {
                    Repaint.Result rb = found.prepared().apply(b);
                    taken++;
                    assertEquals(ra.changed(), rb.changed(), step + ": another rectangle");
                    assertArrayEquals(pixels(ra.frame().image()), pixels(rb.frame().image()), step + ": the second session's picture differs");
                    assertEquals(ra.frame().hits().size(), rb.frame().hits().size(), step + ": other hit regions");
                    for (int i = 0; i < ra.frame().hits().size(); i++) {
                        Painter.Hit x = ra.frame().hits().get(i), y = rb.frame().hits().get(i);
                        assertTrue(x.element() == y.element() && x.shape().getBounds2D().equals(y.shape().getBounds2D()), step + ": hit region " + i + " differs");
                    }
                    a = ra.frame();
                    b = rb.frame();
                }
                drawn = key;
                last = hover;
            }
        }
        // As every chain of repaints (RepaintTest): within a level of a fresh picture of the state.
        HtmlRenderer.Frame fresh = HtmlRenderer.render(page, doc, W, H, SCALE, 0, HtmlRenderer.state(last, null));
        int[] x = pixels(fresh.image()), y = pixels(b.image());
        int worst = 0;
        for (int i = 0; i < x.length; i++) {
            if (x[i] != y[i]) for (int s = 0; s < 32; s += 8) worst = Math.max(worst, Math.abs((x[i] >> s & 255) - (y[i] >> s & 255)));
        }
        assertTrue(worst <= 1, name + ": the shared repaints add up to a picture " + worst + " levels from a fresh one");
        assertEquals(taken, shared.hits(), name + ": hits counted");
        return taken;
    }

    @Test
    void aRepaintOneSessionDrewIsAnotherSessionsRepaint() throws IOException {
        assertTrue(walk("pages/menu", shipped("menu")) > 0);
        assertTrue(walk("pages/shop", shipped("shop")) > 0);
        assertTrue(walk("pages/market", shipped("market")) > 0);
        assertTrue(walk("nested", Page.of(RepaintTest.NESTED, Path.of("nested.html").toAbsolutePath(), OFFLINE)) >= 3);
    }

    /** Copies of a page made for different players (or changed by a click) are different documents: never one key. */
    @Test
    void differentDocumentsNeverShare() throws IOException {
        Page page = shipped("menu");
        Document mine = page.document.clone(), theirs = page.document.clone();
        HtmlRenderer.Frame a = HtmlRenderer.render(page, mine, W, H, SCALE, 0, Selector.State.NONE);
        HtmlRenderer.Frame b = HtmlRenderer.render(page, theirs, W, H, SCALE, 0, Selector.State.NONE);
        Element ta = targets(page, a).get(0), tb = theirs.getAllElements().get(mine.getAllElements().indexOf(ta));
        SharedRepaints shared = new SharedRepaints(Long.MAX_VALUE);
        List<Element> ka = page.styles.stateKey(ta, false), kb = page.styles.stateKey(tb, false);
        SharedRepaints.Key keyA = HoverAhead.key(a, List.of(), List.of(), ka, List.of()), keyB = HoverAhead.key(b, List.of(), List.of(), kb, List.of());
        assertNotEquals(keyA, keyB);
        assertTrue(shared.claim(keyA, true).mine());
        Repaint.Result r = Repaint.partial(page, mine, W, H, SCALE, 0, HtmlRenderer.state(ta, null), copy(a), HoverAhead.changed(List.of(), List.of(), ka, List.of()), 0);
        shared.put(keyA, Repaint.Prepared.of(r), 1, false);
        SharedRepaints.Claim other = shared.claim(keyB, true);
        assertTrue(other.mine(), "another document's session found the repaint");
        assertNull(shared.take(keyB));
        assertEquals(0, shared.hits());
        shared.abandon(keyB);
    }

    /** A page whose images arrive after it is drawn: its pictures are keyed by the picture itself, as no two are known equal. */
    @Test
    void aPageWithLateImagesSharesNothingBetweenPictures() {
        Page.SCHEMES.put("late", u -> null);
        try {
            Page page = Page.of("<html><body><img src=\"late:x\"><a href=\"#\" style=\"display:block\">x</a><style>a:hover{color:red}</style></body></html>",
                    Path.of("late.html").toAbsolutePath(), OFFLINE);
            assertTrue(page.schemed());
            Document doc = page.document.clone();
            HtmlRenderer.Frame a = HtmlRenderer.render(page, doc, W, H, SCALE, 0, Selector.State.NONE), b = copy(a);
            List<Element> key = page.styles.stateKey(doc.selectFirst("a"), false);
            assertNotEquals(HoverAhead.key(a, List.of(), List.of(), key, List.of()), HoverAhead.key(b, List.of(), List.of(), key, List.of()));
            assertEquals(HoverAhead.key(a, List.of(), List.of(), key, List.of()), HoverAhead.key(a, List.of(), List.of(), key, List.of()));
        } finally {
            Page.SCHEMES.remove("late");
        }
    }

    /** One draws, the others wait for it (and get it); if it is not drawn after all, they are told so and draw it themselves. */
    @Test
    void aRepaintBeingDrawnIsWaitedFor() throws Exception {
        Page page = shipped("menu");
        Document doc = page.document.clone();
        HtmlRenderer.Frame f = HtmlRenderer.render(page, doc, W, H, SCALE, 0, Selector.State.NONE);
        List<Element> key = page.styles.stateKey(targets(page, f).get(0), false);
        SharedRepaints shared = new SharedRepaints(Long.MAX_VALUE);
        SharedRepaints.Key k = HoverAhead.key(f, List.of(), List.of(), key, List.of());
        assertTrue(shared.claim(k, true).mine());
        SharedRepaints.Claim waiting = shared.claim(k, true);
        assertFalse(waiting.mine());
        assertFalse(waiting.kept());
        assertTrue(shared.awaited(k));
        AtomicReference<Repaint.Prepared> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            got.set(waiting.await(0));
            done.countDown();
        });
        t.start();
        Repaint.Result r = Repaint.partial(page, doc, W, H, SCALE, 0, HtmlRenderer.state(targets(page, f).get(0), null), copy(f),
                HoverAhead.changed(List.of(), List.of(), key, List.of()), 0);
        Repaint.Prepared p = Repaint.Prepared.of(r);
        shared.put(k, p, 1, false);
        done.await();
        assertSame(p, got.get(), "the waiter got another repaint");
        assertFalse(shared.awaited(k));

        // Given up: the waiter hears null at once.
        SharedRepaints.Key k2 = HoverAhead.key(f, key, List.of(), List.of(), List.of());
        assertTrue(shared.claim(k2, true).mine());
        SharedRepaints.Claim w2 = shared.claim(k2, true);
        shared.abandon(k2);
        assertNull(w2.await(0));
        assertTrue(shared.claim(k2, true).mine(), "given up, it is anybody's to draw");
    }

    /** Kept within its bytes, least recently used out first; a whole-picture answer is kept too; a reload lets go. */
    @Test
    void keptWithinItsBytesAndLetGoOnReload() throws IOException {
        Page page = shipped("menu");
        Document doc = page.document.clone();
        HtmlRenderer.Frame f = HtmlRenderer.render(page, doc, W, H, SCALE, 0, Selector.State.NONE);
        List<Element> ts = targets(page, f);
        List<Repaint.Prepared> ps = new ArrayList<>();
        List<SharedRepaints.Key> ks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            List<Element> key = page.styles.stateKey(ts.get(i), false);
            Repaint.Result r = Repaint.partial(page, doc, W, H, SCALE, 0, HtmlRenderer.state(ts.get(i), null), copy(f),
                    HoverAhead.changed(List.of(), List.of(), key, List.of()), 0);
            ps.add(Repaint.Prepared.of(r));
            ks.add(HoverAhead.key(f, List.of(), List.of(), key, List.of()));
        }
        long room = ps.get(0).bytes() + ps.get(1).bytes() + 300;
        SharedRepaints shared = new SharedRepaints(room);
        for (int i = 0; i < 2; i++) {
            shared.claim(ks.get(i), false);
            shared.put(ks.get(i), ps.get(i), 1, false);
        }
        assertNotNull(shared.take(ks.get(0)));
        shared.claim(ks.get(2), false);
        shared.put(ks.get(2), ps.get(2), 1, false);
        assertTrue(shared.bytes() <= room);
        assertNull(shared.take(ks.get(1)), "the least recently used one is kept");
        assertNotNull(shared.take(ks.get(0)));

        SharedRepaints.Key whole = HoverAhead.key(f, List.of(), List.of(), List.of(ts.get(0), ts.get(1)), List.of());
        shared.claim(whole, false);
        shared.put(whole, null, 1, false);
        SharedRepaints.Claim c = shared.claim(whole, true);
        assertTrue(c.kept());
        assertNull(c.prepared(), "not partial: drawn whole, and not tried again");

        shared.invalidate(p -> p == page);
        assertEquals(0, shared.size());
        assertEquals(0, shared.bytes());
    }

    /**
     * Two sessions in one state rest their pointers in one place: the second draws nothing ahead, since the first drew
     * it all, and takes what the first drew. What was drawn ahead and used is counted.
     */
    @Test
    void drawingAheadIsNotDoneTwiceForTwoSessions() throws IOException {
        Page page = shipped("menu");
        Document doc = page.document.clone();
        SharedRepaints shared = new SharedRepaints(Long.MAX_VALUE);
        HtmlRenderer.Frame a = HtmlRenderer.render(page, doc, W, H, SCALE, 0, Selector.State.NONE), b = copy(a);
        Element t = targets(page, a).get(0);
        Rectangle2D on = null;
        for (Painter.Hit h : a.hits()) if (h.element() == t || t.children().contains(h.element())) on = h.shape().getBounds2D();
        assertNotNull(on);
        HoverAhead one = new HoverAhead(4, shared), two = new HoverAhead(4, shared);
        int[] ready = {0};
        one.onReady(() -> ready[0]++);
        one.run(one.job(page, doc, W, H, SCALE, a, List.of(), List.of(), List.of(), null, List.of(), on.getMinX() - 4, on.getCenterY(), 0, 0, 0));
        assertTrue(one.prepared.get() > 0);
        assertEquals(one.keys().size(), ready[0], "told of each repaint drawn ahead");
        two.run(two.job(page, doc, W, H, SCALE, b, List.of(), List.of(), List.of(), null, List.of(), on.getMinX() - 4, on.getCenterY(), 0, 0, 0));
        assertEquals(0, two.prepared.get(), "drawn ahead again for the second session");
        assertEquals(one.prepared.get(), shared.ahead());
        List<HoverAhead.Ready> r = two.ready(b, List.of(), List.of(), List.of());
        assertEquals(one.keys().size(), r.size(), "ready for the second session: what the first drew ahead");
        List<Element> key = page.styles.stateKey(t, false);
        assertNotNull(two.take(b, List.of(), List.of(), key, List.of()));
        assertEquals(1, shared.aheadUsed());
        assertNotNull(one.take(a, List.of(), List.of(), key, List.of()));
        assertEquals(1, shared.aheadUsed(), "a repaint drawn ahead counts as used once");
    }

    /** With no CPU budget nothing is drawn ahead, and no turn is given; with a little, drawing stops once it is spent. */
    @Test
    void drawingAheadStaysWithinItsBudget() throws IOException {
        Page page = shipped("market");
        Document doc = page.document.clone();
        HtmlRenderer.Frame f = HtmlRenderer.render(page, doc, W, H, SCALE, 0, Selector.State.NONE);
        HoverAhead.budget(0);
        assertFalse(HoverAhead.claim(() -> {}), "a turn with no budget");
        HoverAhead none = new HoverAhead(8, new SharedRepaints(Long.MAX_VALUE));
        none.run(none.job(page, doc, W, H, SCALE, f, List.of(), List.of(), List.of(), null, List.of(), 1000, 300, 0, 0, 0));
        assertEquals(0, none.prepared.get(), "drawn ahead with no budget");

        // A thousandth of a core, starting with a full burst: drawing ahead goes on until the burst is spent, then stops.
        HoverAhead.budget(0.001);
        HoverAhead some = new HoverAhead(8, new SharedRepaints(Long.MAX_VALUE));
        some.run(some.job(page, doc, W, H, SCALE, f, List.of(), List.of(), List.of(), null, List.of(), 1000, 300, 0, 0, 0));
        assertTrue(some.prepared.get() > 0, "nothing drawn ahead with a full burst");
        org.junit.jupiter.api.Assumptions.assumeTrue(some.cpuNs.get() >= HoverAhead.BURST_NS, "every target fitted in one burst here");
        assertTrue(some.prepared.get() < 8, "went on past the burst");
        HoverAhead more = new HoverAhead(8, new SharedRepaints(Long.MAX_VALUE));
        more.run(more.job(page, doc, W, H, SCALE, f, List.of(), List.of(), List.of(), null, List.of(), 1000, 300, 0, 0, 0));
        assertEquals(0, more.prepared.get(), "drew ahead again with the budget spent");
        assertFalse(HoverAhead.claim(() -> {}), "a turn with the budget spent");
    }
}
