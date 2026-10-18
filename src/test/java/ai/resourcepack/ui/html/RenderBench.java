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
 */
public final class RenderBench {
    private static final int W = 2048, H = 1152;
    private static final double SCALE = H / 576.0;

    public static void main(String[] args) throws Exception {
        int runs = Integer.getInteger("runs", 20);
        String only = System.getProperty("only", "");
        Page.Loader loader = new Page.Loader(true, Path.of("build/samples/cache"));
        Palette palette = new Palette(RenderBench::mapColour);
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

    private static int mapColour(int packed) {
        int base = packed >> 2;
        if (base <= 0 || base >= BASE.length) return 0;
        int c = BASE[base], m = SHADE[packed & 3];
        return 0xFF000000 | ((c >> 16 & 255) * m / 255) << 16 | ((c >> 8 & 255) * m / 255) << 8 | (c & 255) * m / 255;
    }
}
