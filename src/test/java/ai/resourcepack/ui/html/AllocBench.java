package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.render.Palette;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.awt.geom.Rectangle2D;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Bytes allocated per picture, on every thread (render, painters, encoders), as a session draws it:
 * {@code full} a whole new picture and the canvas taking it, {@code hover} a hover change repainted over the last
 * picture and the canvas taking the rectangle, {@code notch} a notch of the wheel on a page that scrolls (cut from
 * a tall picture, as Session does). One picture at a time, so nothing else allocates meanwhile.
 * Args: .html files. {@code -Druns=<n>} (default 12), {@code -Dops=full,hover,notch}.
 * Run with {@code ./gradlew allocBench -Ppages=a.html,b.html}.
 */
public final class AllocBench {
    static final int W = LoadBench.W, H = LoadBench.H;
    static final double SCALE = LoadBench.SCALE;
    private static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        int runs = Integer.getInteger("runs", 12);
        List<String> ops = Arrays.asList(System.getProperty("ops", "full,hover,notch").split(","));
        Page.Loader loader = new Page.Loader(true, Path.of("build/samples/cache"));
        Palette palette = new Palette(RenderBench::mapColour);
        System.out.printf("%-10s %-6s %5s %10s %10s %10s %10s%n", "page", "op", "n", "render MB", "draw MB", "total MB", "mean MB");
        for (String a : args) {
            Path file = Path.of(a);
            String name = file.getFileName().toString().replace(".html", "");
            Page page = Page.load(file, loader);
            LoadBench.Prep prep = new LoadBench.Prep(page);
            for (String op : ops) {
                Seat seat = new Seat(page, new Canvas(palette, W / 128, H / 128));
                long[] r = new long[runs], d = new long[runs];
                int done = 0;
                for (int i = -4; i < runs; i++) {
                    long[] m = switch (op) {
                        case "full" -> seat.full(prep, i);
                        case "hover" -> seat.hover(prep, i);
                        case "notch" -> seat.notch(prep, i);
                        default -> throw new IllegalArgumentException(op);
                    };
                    if (m == null) break;
                    if (i >= 0) {
                        r[i] = m[0];
                        d[i] = m[1];
                        done++;
                    }
                }
                if (done == 0) {
                    System.out.printf("%-10s %-6s %5s%n", name, op, "-");
                    continue;
                }
                long[] t = new long[done];
                double mean = 0;
                for (int i = 0; i < done; i++) {
                    t[i] = r[i] + d[i];
                    mean += t[i] / 1048576.0 / done;
                }
                System.out.printf("%-10s %-6s %5d %10.2f %10.2f %10.2f %10.2f%n", name, op, done, med(r, done), med(d, done), med(t, done), mean);
            }
        }
        System.exit(0);
    }

    private static double med(long[] v, int n) {
        long[] c = Arrays.copyOf(v, n);
        Arrays.sort(c);
        return (n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2.0) / 1048576.0;
    }

    /** Bytes allocated so far by every live thread. */
    static long allocated() {
        long[] ids = MX.getAllThreadIds();
        long sum = 0;
        for (long b : MX.getThreadAllocatedBytes(ids)) if (b > 0) sum += b;
        return sum;
    }

    /** One viewer, drawing as Session does. */
    static final class Seat {
        final Page page;
        final Canvas canvas;
        final Document doc, nudged;
        HtmlRenderer.Frame frame;
        ScrollCache cache;
        List<Element> drawnHover = List.of();
        Element hover;

        Seat(Page page, Canvas canvas) {
            this.page = page;
            this.canvas = canvas;
            doc = page.document.clone();
            // A page that does not scroll gets a whole new picture by moving its body 3 px (LoadBench's stress).
            nudged = page.document.clone();
            Element body = nudged.body();
            if (body != null) body.attr("style", "position:relative;top:3px");
        }

        /** A whole picture: scrolled 37 px from the last one, or the body moved, then drawn on the canvas. */
        long[] full(LoadBench.Prep prep, int i) {
            boolean scrolls = prep.maxScroll > 1;
            Document d = scrolls || i % 2 == 0 ? doc : nudged;
            double s = scrolls ? (i % 2 == 0 ? 0 : Math.min(37, prep.maxScroll)) : 0;
            long a0 = allocated();
            HtmlRenderer.Frame f = whole(d, s, Selector.State.NONE);
            long a1 = allocated();
            canvas.draw(f.image(), 0, 0);
            long a2 = allocated();
            shown(f);
            return new long[]{a1 - a0, a2 - a1};
        }

        /** A hover change: the pointer onto the next target (or off it), repainted over the last picture. */
        long[] hover(LoadBench.Prep prep, int i) {
            if (prep.targets.isEmpty()) return null;
            if (frame == null) shown(drawn(whole(doc, 0, Selector.State.NONE)));
            int[] t = prep.targets.get(Math.floorMod(i, prep.targets.size()));
            Element e = i % 2 == 0 ? frame.elementAt(t[0] / SCALE, t[1] / SCALE) : null;
            List<Element> key = page.styles.stateKey(e, false);
            Set<Element> moved = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Element x : drawnHover) if (!key.contains(x)) moved.add(x);
            for (Element x : key) if (!drawnHover.contains(x)) moved.add(x);
            var state = HtmlRenderer.state(e, null);
            long a0 = allocated();
            long a1, a2;
            if (moved.isEmpty()) {
                a1 = a2 = a0;
            } else {
                Repaint.Result r = Repaint.partial(page, doc, W, H, SCALE, frame.scrollY(), state, frame, moved, HtmlRenderer.DEFAULT_TIMEOUT_MS);
                if (r.frame() != null) {
                    HtmlRenderer.Frame f = r.frame();
                    a1 = allocated();
                    Rectangle2D c = r.changed();
                    int x0 = Math.max(0, (int) Math.floor(c.getX() * SCALE)), y0 = Math.max(0, (int) Math.floor(c.getY() * SCALE));
                    int x1 = Math.min(W, (int) Math.ceil(c.getMaxX() * SCALE)), y1 = Math.min(H, (int) Math.ceil(c.getMaxY() * SCALE));
                    if (x1 > x0 && y1 > y0) canvas.draw(f.image().getSubimage(x0, y0, x1 - x0, y1 - y0), x0, y0);
                    a2 = allocated();
                    shown(f);
                } else {
                    HtmlRenderer.Frame f = whole(doc, frame.scrollY(), state);
                    a1 = allocated();
                    canvas.draw(f.image(), 0, 0);
                    a2 = allocated();
                    shown(f);
                    System.out.println("  (hover drawn whole: " + r.why() + ")");
                }
            }
            hover = e;
            drawnHover = key;
            return new long[]{a1 - a0, a2 - a1};
        }

        /** A notch of the wheel (80 CSS px, back up at the bottom), cut from a tall picture as Session does. */
        long[] notch(LoadBench.Prep prep, int i) {
            if (prep.maxScroll <= 1) return null;
            if (frame == null) shown(drawn(whole(doc, 0, Selector.State.NONE)));
            int per = (int) Math.ceil(prep.maxScroll / 80);
            int k = Math.floorMod(i + 4, 2 * per);
            double at = ScrollCache.snap(Math.min(prep.maxScroll, (k < per ? k + 1 : 2 * per - k - 1) * 80.0), SCALE);
            HtmlRenderer.Frame before = frame;
            long a0 = allocated();
            ScrollCache c = cache;
            if (c != null && !c.covers(at)) c = null;
            if (c == null) c = ScrollCache.paint(page, doc, W, H, SCALE, at, before.scrollY(), Selector.State.NONE, ScrollCache.VIEWS, 0);
            HtmlRenderer.Frame f = c.frame(at);
            long a1 = allocated();
            canvas.draw(f.image(), 0, 0);
            long a2 = allocated();
            cache = c;
            shown(f);
            return new long[]{a1 - a0, a2 - a1};
        }

        HtmlRenderer.Frame drawn(HtmlRenderer.Frame f) {
            canvas.draw(f.image(), 0, 0);
            return f;
        }

        // ---- where a picture comes from and goes, as Session does it

        HtmlRenderer.Frame whole(Document d, double s, Selector.State state) {
            return HtmlRenderer.render(page, d, W, H, SCALE, s, state, null, null, HtmlRenderer.DEFAULT_TIMEOUT_MS);
        }

        void shown(HtmlRenderer.Frame f) {
            frame = f;
        }
    }
}
