package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.render.Palette;
import org.jsoup.nodes.Element;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * What a page costs a session, in the order a session pays it, at a default fullscreen view's size
 * (2048 x 1152, drawn at 2x): a full render, the canvas taking it (colour conversion and tile
 * encoding), a hover repaint of the region round the first element with a :hover rule, and the canvas
 * taking that. Median and 90th percentile of [runs] after a warm-up. Args: folders or .html files.
 * Run with {@code ./gradlew renderBench}; {@code -Pruns=<n>} {@code -Ponly=<name>}.
 *
 * <p>{@code -Pscroll=1}: what a notch of the wheel costs instead, scrolling each page down [notches]
 * notches and back up as a session does (80 CSS px a notch, whole pixels), drawn whole every notch and
 * cut from a {@link ScrollCache}, the two taking turns. Also checks every view cut from the cache, and
 * one hover repaint into it, against a picture drawn on its own at the same place.
 */
public final class RenderBench {
    private static final int W = 2048, H = 1152;
    private static final double SCALE = H / 576.0;

    public static void main(String[] args) throws Exception {
        int runs = Integer.getInteger("runs", 20);
        String only = System.getProperty("only", "");
        Page.Loader loader = new Page.Loader(true, Path.of("build/samples/cache"));
        Palette palette = new Palette(RenderBench::mapColour);
        if (Integer.getInteger("scroll", 0) > 0) {
            scroll(pages(args), only, Math.max(1, runs), Integer.getInteger("notches", 12), Integer.getInteger("views", ScrollCache.VIEWS), loader, palette);
            return;
        }
        for (Path file : pages(args)) {
            String name = file.getFileName().toString().replace(".html", "");
            if (!only.isEmpty() && !only.equals(name)) continue;
            Page page = Page.load(file, loader);
            Element target = hoverTarget(page);
            Selector.State hovered = HtmlRenderer.state(target, null);
            long[] full = new long[runs], draw = new long[runs], hover = new long[runs], hoverDraw = new long[runs];
            for (int i = -10; i < runs; i++) {
                Canvas canvas = new Canvas(palette, W / 128, H / 128);
                long t0 = System.nanoTime();
                HtmlRenderer.Frame f = HtmlRenderer.render(page, page.document, W, H, SCALE, 0, Selector.State.NONE);
                long t1 = System.nanoTime();
                canvas.draw(f.image(), 0, 0);
                long t2 = System.nanoTime();
                long t3 = t2, t4 = t2, t5 = t2;
                Rectangle2D dirty = target == null ? null : dirty(f, target);
                if (dirty != null) {
                    t3 = System.nanoTime();
                    HtmlRenderer.Frame h = HtmlRenderer.render(page, page.document, W, H, SCALE, 0, hovered, f.image(), dirty);
                    t4 = System.nanoTime();
                    int x0 = (int) Math.floor(dirty.getX() * SCALE), y0 = (int) Math.floor(dirty.getY() * SCALE);
                    int x1 = Math.min(W, (int) Math.ceil(dirty.getMaxX() * SCALE)), y1 = Math.min(H, (int) Math.ceil(dirty.getMaxY() * SCALE));
                    BufferedImage part = h.image().getSubimage(x0, y0, x1 - x0, y1 - y0);
                    canvas.draw(part, x0, y0);
                    t5 = System.nanoTime();
                }
                if (i < 0) continue;
                full[i] = t1 - t0;
                draw[i] = t2 - t1;
                hover[i] = t4 - t3;
                hoverDraw[i] = t5 - t4;
            }
            System.out.printf("%-12s full %s  draw %s | hover %s  hover-draw %s  (%s)%n", name, stat(full), stat(draw), stat(hover), stat(hoverDraw),
                    target == null ? "no :hover rule" : target.normalName() + (target.className().isEmpty() ? "" : "." + target.className().split(" ")[0]));
        }
    }

    /** CSS px a notch of the wheel scrolls (the default scroll-step). */
    private static final double STEP = 80;

    private static void scroll(List<Path> files, String only, int runs, int notches, int views, Page.Loader loader, Palette palette) throws IOException {
        System.out.printf("Per notch, ms median/p90 (mean of the sums); views cut from a cache of up to %d views%n", views);
        System.out.printf("%-12s %7s | %-43s | %-43s | %-11s %-13s %8s | %s%n", "page", "notches", "drawn whole: render, draw, sum (mean)",
                "cut from cache: render, draw, sum (mean)", "builds/pass", "build ms", "MiB", "worst diff: views, hover, hover scrolled");
        for (Path file : files) {
            String name = file.getFileName().toString().replace(".html", "");
            if (!only.isEmpty() && !only.equals(name)) continue;
            Page page = Page.load(file, loader);
            HtmlRenderer.Frame open = HtmlRenderer.render(page, page.document, W, H, SCALE, 0, Selector.State.NONE);
            double[] at = positions(open.maxScroll(), notches);
            if (at.length == 0) {
                System.out.printf("%-12s does not scroll (page %.0f px, view %.0f px)%n", name, open.pageHeight(), open.viewportHeight());
                continue;
            }
            boolean possible = ScrollCache.possible(open);
            int n = at.length;
            long[] wholeR = new long[runs * n], wholeD = new long[runs * n], cutR = new long[runs * n], cutD = new long[runs * n];
            List<Long> builds = new ArrayList<>();
            long bytes = 0;
            int worst = -1;
            for (int i = -3; i < runs; i++) {
                for (int pass = 0; pass < 2; pass++) {
                    // Take turns at going first, so neither always runs on a warmer (or busier) machine.
                    boolean cached = (pass == 0) == (i % 2 == 0);
                    Canvas canvas = new Canvas(palette, W / 128, H / 128);
                    canvas.draw(open.image(), 0, 0);
                    ScrollCache c = null;
                    double from = 0;
                    for (int k = 0; k < n; k++) {
                        boolean built = false;
                        long t0 = System.nanoTime();
                        HtmlRenderer.Frame f;
                        if (cached && possible) {
                            // As Session does when only the scroll moved.
                            if (c == null || !c.covers(at[k])) {
                                c = ScrollCache.paint(page, page.document, W, H, SCALE, at[k], from, Selector.State.NONE, views);
                                built = true;
                            }
                            f = c.frame(at[k]);
                        } else {
                            f = HtmlRenderer.render(page, page.document, W, H, SCALE, at[k], Selector.State.NONE);
                        }
                        long t1 = System.nanoTime();
                        canvas.draw(f.image(), 0, 0);
                        long t2 = System.nanoTime();
                        from = at[k];
                        if (cached && i == -3) {
                            HtmlRenderer.Frame fresh = HtmlRenderer.render(page, page.document, W, H, SCALE, at[k], Selector.State.NONE);
                            worst = Math.max(worst, diff(f.image(), fresh.image()));
                        }
                        if (i < 0) continue;
                        int j = i * n + k;
                        (cached ? cutR : wholeR)[j] = t1 - t0;
                        (cached ? cutD : wholeD)[j] = t2 - t1;
                        if (built) builds.add(t1 - t0);
                    }
                    if (c != null) bytes = Math.max(bytes, c.bytes());
                }
            }
            int[] hoverWorst = possible ? hoverCheck(page, at[n / 2], views) : new int[]{-1, -1};
            long[] wholeSum = sum(wholeR, wholeD), cutSum = sum(cutR, cutD);
            long[] build = builds.stream().mapToLong(Long::longValue).toArray();
            System.out.printf("%-12s %7d | %s %s %s (%5.1f) | %s %s %s (%5.1f) | %11.1f %13s %8.1f | %d, %d, %d%s%n", name, n,
                    stat(wholeR), stat(wholeD), stat(wholeSum), mean(wholeSum), stat(cutR), stat(cutD), stat(cutSum), mean(cutSum),
                    builds.size() / (double) runs, build.length == 0 ? "-" : stat(build), bytes / 1048576.0, worst, hoverWorst[0], hoverWorst[1],
                    possible ? "" : "  (position: fixed: drawn whole)");
        }
    }

    /** Where a session's view goes, [notches] notches down from the top and as many back up (Session.scroll). */
    private static double[] positions(double maxScroll, int notches) {
        List<Double> out = new ArrayList<>();
        double s = 0;
        for (int k = 0; k < 2 * notches; k++) {
            double to = ScrollCache.snap(Math.max(0, Math.min(maxScroll, s + (k < notches ? 1 : -1) * STEP)), SCALE);
            if (to == s) continue;
            out.add(s = to);
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * A hover repaint into a cache at [scrollY], of an element the view's bottom edge crosses (else any in view), as a
     * session does it (the region round it in the whole tall picture, not only in view). The worst difference from a
     * picture drawn on its own in that hover state of the view cut afterwards, and of the view a notch further down,
     * which shows what was repainted out of view; -1 when nothing in view has a :hover rule (or nothing is further down).
     */
    private static int[] hoverCheck(Page page, double scrollY, int views) {
        ScrollCache c = ScrollCache.paint(page, page.document, W, H, SCALE, scrollY, 0, Selector.State.NONE, views);
        HtmlRenderer.Frame view = c.frame(scrollY), strip = c.strip();
        double top = view.scrollY(), bottom = top + view.viewportHeight();
        Element target = null;
        for (Element e : page.document.getAllElements()) {
            if (page.styles.stateKey(e, false).isEmpty()) continue;
            Rectangle2D b = bounds(strip, e);
            if (b == null || b.getMaxY() < top || b.getMinY() > bottom) continue;
            if (target == null) target = e;
            if (b.getMinY() < bottom && b.getMaxY() > bottom) {
                target = e;
                break;
            }
        }
        if (target == null) return new int[]{-1, -1};
        Rectangle2D b = bounds(strip, target);
        double rows = strip.image().getHeight() / SCALE;
        // Room for the hovered shadows of the test pages; a session works it out from the styles (Session.dirtyRegion).
        double m = 120;
        Rectangle2D region = new Rectangle2D.Double(b.getX() - m, b.getY() - strip.scrollY() - m, b.getWidth() + 2 * m, b.getHeight() + 2 * m)
                .createIntersection(new Rectangle2D.Double(0, 0, strip.viewportWidth(), rows));
        Selector.State hovered = HtmlRenderer.state(target, null);
        c.patched(HtmlRenderer.render(page, page.document, W, H, SCALE, strip.scrollY(), hovered, strip.image(), region));
        int here = diff(c.frame(scrollY).image(), HtmlRenderer.render(page, page.document, W, H, SCALE, scrollY, hovered).image());
        double next = ScrollCache.snap(Math.min(view.maxScroll(), scrollY + STEP), SCALE);
        int further = next == scrollY || !c.covers(next) ? -1
                : diff(c.frame(next).image(), HtmlRenderer.render(page, page.document, W, H, SCALE, next, hovered).image());
        return new int[]{here, further};
    }

    /** Where [target]'s hit regions are on the page, or null. */
    private static Rectangle2D bounds(HtmlRenderer.Frame f, Element target) {
        Rectangle2D area = null;
        for (var hit : f.hits()) {
            boolean inside = false;
            for (Element e = hit.element(); e != null && !inside; e = e.parent()) inside = e == target;
            if (!inside) continue;
            Rectangle2D b = hit.shape().getBounds2D();
            if (area == null) area = new Rectangle2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight());
            else area.add(b);
        }
        return area;
    }

    /** The largest difference of any colour channel between two pictures of one size. */
    private static int diff(BufferedImage a, BufferedImage b) {
        int[] pa = ((java.awt.image.DataBufferInt) a.getRaster().getDataBuffer()).getData();
        int[] pb = ((java.awt.image.DataBufferInt) b.getRaster().getDataBuffer()).getData();
        int worst = 0;
        for (int i = 0; i < pa.length; i++) {
            int x = pa[i], y = pb[i];
            if (x == y) continue;
            for (int sh = 0; sh < 24; sh += 8) worst = Math.max(worst, Math.abs((x >> sh & 255) - (y >> sh & 255)));
        }
        return worst;
    }

    /** The mean in ms: what a notch costs on average, the builds of a cache shared out among the notches cut from it. */
    private static double mean(long[] ns) {
        return Arrays.stream(ns).average().orElse(0) / 1e6;
    }

    private static long[] sum(long[] a, long[] b) {
        long[] out = new long[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] + b[i];
        return out;
    }

    /** "median/p90" in ms. */
    private static String stat(long[] ns) {
        long[] s = ns.clone();
        Arrays.sort(s);
        return String.format("%6.1f/%6.1f", s[s.length / 2] / 1e6, s[Math.min(s.length - 1, (int) (s.length * 0.9))] / 1e6);
    }

    private static List<Path> pages(String[] args) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String a : args) {
            Path p = Path.of(a);
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    s.filter(f -> f.toString().endsWith(".html")).sorted().forEach(out::add);
                }
            } else {
                out.add(p);
            }
        }
        return out;
    }

    /** The first element on the page whose hover changes what matches, as a pointer would find it. */
    private static Element hoverTarget(Page page) {
        for (Element e : page.document.getAllElements()) if (!page.styles.stateKey(e, false).isEmpty()) return e;
        return null;
    }

    /** The CSS-pixel region a session repaints for a hover on [target]: its hit shapes plus 48 px, clipped to the view. */
    private static Rectangle2D dirty(HtmlRenderer.Frame f, Element target) {
        Rectangle2D area = null;
        for (var hit : f.hits()) {
            boolean inside = false;
            for (Element e = hit.element(); e != null && !inside; e = e.parent()) inside = e == target;
            if (!inside) continue;
            Rectangle2D b = hit.shape().getBounds2D();
            if (area == null) area = new Rectangle2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight());
            else area.add(b);
        }
        if (area == null) return null;
        Rectangle2D padded = new Rectangle2D.Double(area.getX() - 48, area.getY() - 48, area.getWidth() + 96, area.getHeight() + 96);
        Rectangle2D out = padded.createIntersection(new Rectangle2D.Double(0, 0, f.viewportWidth(), f.viewportHeight()));
        return out.isEmpty() ? null : out;
    }

    /** Vanilla's map colours (MapColor), packed id = base * 4 + brightness, as the server's palette sees them. */
    private static final int[] BASE = {0, 8368696, 16247203, 13092807, 16711680, 10526975, 10987431, 31744, 16777215, 10791096, 9923917, 7368816,
            4210943, 9402184, 16776437, 14188339, 11685080, 6724056, 15066419, 8375321, 15892389, 5000268, 10066329, 5013401, 8339378, 3361970,
            6704179, 6717235, 10040115, 1644825, 16445005, 6085589, 4882687, 55610, 8476209, 7340544, 13742497, 10441252, 9787244, 7367818,
            12223780, 6780213, 10505550, 3746083, 8874850, 5725276, 8014168, 4996700, 4993571, 5001770, 9321518, 2430480, 12398641, 9715553,
            6035741, 1474182, 3837580, 5647422, 1356933, 6579300, 14200723, 8365974};
    private static final int[] SHADE = {180, 220, 255, 135};

    static int mapColour(int packed) {
        int base = packed >> 2;
        if (base <= 0 || base >= BASE.length) return 0;
        int c = BASE[base], m = SHADE[packed & 3];
        return 0xFF000000 | ((c >> 16 & 255) * m / 255) << 16 | ((c >> 8 & 255) * m / 255) << 8 | (c & 255) * m / 255;
    }
}
