package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.paint.Painter;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hover drawn ahead ({@link HoverAhead}) and then taken must be exactly the repaint the session would have drawn
 * when the hover came (worst difference 0, same rectangle, same hit regions), and drawing ahead must leave the
 * picture on the screen as it was, also when it is stopped part way.
 */
class HoverAheadTest {
    private static final int W = 2048, H = 1152;
    private static final double SCALE = 2;
    private static final Page.Loader OFFLINE = new Page.Loader(false, null);

    private static int[] pixels(BufferedImage img) {
        return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }

    private static HtmlRenderer.Frame copy(HtmlRenderer.Frame f) {
        BufferedImage from = f.image(), to = new BufferedImage(from.getWidth(), from.getHeight(), from.getType());
        System.arraycopy(pixels(from), 0, pixels(to), 0, pixels(from).length);
        return new HtmlRenderer.Frame(to, f.scale(), f.viewportWidth(), f.viewportHeight(), f.pageHeight(), f.scrollY(), f.hits(), f.styles(), f.document(), f.page());
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

    private static Rectangle2D where(HtmlRenderer.Frame f, Element e) {
        Rectangle2D a = null;
        for (Painter.Hit h : f.hits()) {
            boolean inside = false;
            for (Element p = h.element(); p != null && !inside; p = p.parent()) inside = p == e;
            if (!inside) continue;
            Rectangle2D b = h.shape().getBounds2D();
            if (a == null) a = new Rectangle2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight());
            else a.add(b);
        }
        return a;
    }

    private record Walk(int steps, int taken) {}

    /**
     * Enters and leaves every hover target in turn, as a session does. Before each step every target is drawn ahead
     * over the picture on the screen, from where the pointer is; then the step itself is drawn as the session draws it
     * (Repaint.partial over a copy of the picture) and compared with what was drawn ahead.
     */
    private static Walk walk(String name, Page page) {
        Document doc = page.document.clone();
        HtmlRenderer.Frame cur = HtmlRenderer.render(page, doc, W, H, SCALE, 0, Selector.State.NONE);
        List<Element> drawn = List.of();
        double px = 1, py = 1;
        // Every target is drawn ahead, not only the nearest: what is checked here is that what is taken is the repaint.
        int steps = 0, taken = 0;
        for (Element target : targets(page, cur)) {
            for (Element hover : new Element[]{target, null}) {
                List<Element> key = page.styles.stateKey(hover, false);
                Set<Element> moved = HoverAhead.changed(drawn, List.of(), key, List.of());
                if (moved.isEmpty()) continue;
                // The pointer on the target, as when its hover has just been reported (entering) or it rests there (leaving).
                Rectangle2D on = where(cur, target);
                if (on != null) {
                    px = on.getCenterX();
                    py = on.getCenterY();
                }
                steps++;
                HoverAhead ahead = new HoverAhead(64, Long.MAX_VALUE);
                int[] shown = pixels(cur.image()).clone();
                ahead.run(ahead.job(page, doc, W, H, SCALE, cur, drawn, List.of(), drawn, null, List.of(), px, py, 0, 0, 0));
                assertArrayEquals(shown, pixels(cur.image()), name + ": drawing ahead changed the picture on the screen");

                Repaint.Result real = Repaint.partial(page, doc, W, H, SCALE, 0, HtmlRenderer.state(hover, null), copy(cur), moved, 0);
                Repaint.Prepared ready = ahead.take(cur, drawn, List.of(), key, List.of());
                if (real.frame() == null) {
                    assertNull(ready, name + ": drawn ahead, but the session would draw a whole picture");
                    cur = HtmlRenderer.render(page, doc, W, H, SCALE, 0, HtmlRenderer.state(hover, null));
                } else {
                    assertNotNull(ready, name + ": " + (hover == null ? "leaving " : "entering ") + target.normalName() + "." + target.className() + " was not drawn ahead");
                    Repaint.Result r = ready.apply(cur);
                    taken++;
                    String step = name + ", " + (hover == null ? "leaving " : "entering ") + target.normalName() + "." + target.className();
                    assertEquals(real.changed(), r.changed(), step + ": another rectangle");
                    int[] a = pixels(real.frame().image()), b = pixels(r.frame().image());
                    int worst = 0, at = -1;
                    for (int i = 0; i < a.length; i++) {
                        if (a[i] == b[i]) continue;
                        for (int shift = 0; shift < 32; shift += 8) worst = Math.max(worst, Math.abs(((a[i] >> shift) & 255) - ((b[i] >> shift) & 255)));
                        at = i;
                    }
                    assertEquals(0, worst, step + ": differs from the session's repaint, e.g. at pixel (" + at % W + ", " + at / W + ")");
                    assertEquals(real.frame().hits().size(), r.frame().hits().size(), step + ": other hit regions");
                    for (int i = 0; i < real.frame().hits().size(); i++) {
                        Painter.Hit x = real.frame().hits().get(i), y = r.frame().hits().get(i);
                        assertTrue(x.element() == y.element() && x.shape().getBounds2D().equals(y.shape().getBounds2D()), step + ": hit region " + i + " differs");
                    }
                    assertEquals(real.frame().styles().keySet(), r.frame().styles().keySet(), step + ": styles of other elements");
                    cur = r.frame();
                }
                drawn = key;
            }
        }
        System.out.printf("HoverAheadTest %s: %d hover steps, %d taken from what was drawn ahead, all identical%n", name, steps, taken);
        return new Walk(steps, taken);
    }

    private static Page file(String path) throws IOException {
        return Page.load(Path.of(path), OFFLINE);
    }

    @Test
    void shippedMenuDrawnAheadIsTheRepaint() throws IOException {
        Walk w = walk("pages/menu", file("src/main/resources/pages/menu.html"));
        assertEquals(w.steps, w.taken);
    }

    @Test
    void shippedShopDrawnAheadIsTheRepaint() throws IOException {
        Walk w = walk("pages/shop", file("src/main/resources/pages/shop.html"));
        assertTrue(w.taken > 0);
    }

    @Test
    void sampleMenuDrawnAheadIsTheRepaint() throws IOException {
        Walk w = walk("samples/menu", file("samples/menu.html"));
        assertEquals(w.steps, w.taken);
    }

    /** Backdrops nested, and on the canvas's edges: repaints with a margin painted and put back, and grown ones. */
    @Test
    void backdropsDrawnAheadAreTheRepaint() {
        assertTrue(walk("nested", Page.of(RepaintTest.NESTED, Path.of("nested.html").toAbsolutePath(), OFFLINE)).taken >= 3);
        assertTrue(walk("edges", Page.of(RepaintTest.EDGES, Path.of("edges.html").toAbsolutePath(), OFFLINE)).taken >= 3);
    }

    /**
     * The picture drawn ahead over is never painted, not even for a moment: it is the one its session shows, and may
     * be shared with other sessions (FirstPictures), who read it meanwhile. Drawing ahead goes over a copy of its own.
     * What it prepared, applied to another copy (as a session whose picture is shared does), is the session's repaint.
     */
    @Test
    void aSharedPictureIsLeftAlone() throws Exception {
        Page page = file("src/main/resources/pages/shop.html");
        Document doc = page.document.clone();
        HtmlRenderer.Frame cur = HtmlRenderer.render(page, doc, W, H, SCALE, 0, Selector.State.NONE);
        int[] shown = pixels(cur.image()).clone();
        // Another session reading the picture all the while it is drawn ahead over.
        AtomicBoolean drawing = new AtomicBoolean(true);
        AtomicInteger looks = new AtomicInteger(), changed = new AtomicInteger();
        Thread reader = new Thread(() -> {
            while (drawing.get()) {
                if (!Arrays.equals(shown, pixels(cur.image()))) changed.incrementAndGet();
                looks.incrementAndGet();
            }
        });
        reader.start();
        HoverAhead ahead = new HoverAhead(64, Long.MAX_VALUE);
        try {
            ahead.run(ahead.job(page, doc, W, H, SCALE, cur, List.of(), List.of(), List.of(), null, List.of(), 1, 1, 0, 0, 0));
        } finally {
            drawing.set(false);
            reader.join();
        }
        assertTrue(looks.get() > 1, "the picture was not watched");
        assertEquals(0, changed.get(), "the picture was painted while drawn ahead over (" + changed.get() + " of " + looks.get() + " looks)");
        assertArrayEquals(shown, pixels(cur.image()));
        Element t = targets(page, cur).get(0);
        List<Element> key = page.styles.stateKey(t, false);
        Repaint.Prepared ready = ahead.take(cur, List.of(), List.of(), key, List.of());
        assertNotNull(ready);
        HtmlRenderer.Frame own = copy(cur);
        Repaint.Result r = ready.apply(own);
        assertSame(own.image(), r.frame().image(), "applied to the copy it was given");
        Repaint.Result real = Repaint.partial(page, doc, W, H, SCALE, 0, HtmlRenderer.state(t, null), copy(cur),
                HoverAhead.changed(List.of(), List.of(), key, List.of()), 0);
        assertNotNull(real.frame());
        assertEquals(real.changed(), r.changed());
        assertArrayEquals(pixels(real.frame().image()), pixels(r.frame().image()), "differs from the session's repaint");
        assertArrayEquals(shown, pixels(cur.image()), "the shared picture was painted");
    }

    /** Stopped part way through (real work came), the picture on the screen is as it was, and nothing half drawn is kept. */
    @Test
    void stoppedPartWayLeavesThePictureAsItWas() throws Exception {
        Page page = file("src/main/resources/pages/menu.html");
        Document doc = page.document.clone();
        HtmlRenderer.Frame cur = HtmlRenderer.render(page, doc, W, H, SCALE, 0, Selector.State.NONE);
        int[] shown = pixels(cur.image()).clone();
        int stopped = 0;
        for (int d = 1; d <= 40; d += 3) {
            int delayMs = d;
            HoverAhead ahead = new HoverAhead(64, Long.MAX_VALUE);
            HoverAhead.Job job = ahead.job(page, doc, W, H, SCALE, cur, List.of(), List.of(), List.of(), null, List.of(), 1, 1, 0, 0, 0);
            Thread realWork = new Thread(() -> {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                }
                HoverAhead.makeRoom();
            });
            realWork.start();
            ahead.run(job);
            realWork.join();
            assertArrayEquals(shown, pixels(cur.image()), "stopped after " + delayMs + " ms");
            stopped += ahead.abandoned.get() > 0 ? 1 : 0;
            // What was kept is whole: taking it gives the session's own repaint.
            List<List<Element>> kept = ahead.keys();
            if (!kept.isEmpty()) {
                List<Element> key = kept.get(0);
                Repaint.Prepared ready = ahead.take(cur, List.of(), List.of(), key, List.of());
                Repaint.Result real = Repaint.partial(page, doc, W, H, SCALE, 0, HtmlRenderer.state(key.get(0), null), copy(cur),
                        HoverAhead.changed(List.of(), List.of(), key, List.of()), 0);
                ready.apply(cur);
                assertTrue(Arrays.equals(pixels(real.frame().image()), pixels(cur.image())), "a kept repaint differs after a stop at " + delayMs + " ms");
                // The picture as it was, for the next round.
                System.arraycopy(shown, 0, pixels(cur.image()), 0, shown.length);
            }
        }
        assertTrue(stopped > 0, "never stopped part way");
    }
}
