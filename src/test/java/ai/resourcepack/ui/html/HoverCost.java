package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.render.Palette;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * What one hover costs on one thread, split the way a prepared hover would split it: the repaint
 * ({@link Repaint#partial}, what preparing a hover in advance takes away) and {@code Canvas.draw} of the
 * changed rectangle (what is left). For each hover target of each page (LoadBench's list, at its 2048 x 1152
 * view), from the picture with nothing hovered: on (none to the target) and off (the target to none).
 * CPU and allocation are the calling thread's (ThreadMXBean), so a busy machine does not move them.
 * Args: page specs as LoadBench takes them, or paths of page files (default pages:menu, pages:shop and every sample); -Druns=<n>.
 */
public final class HoverCost {
    public static void main(String[] args) throws Exception {
        List<String> specs = args.length > 0 ? Arrays.asList(args)
                : List.of("pages:menu", "pages:shop", "samples:menu", "samples:cards", "samples:landing", "samples:typography");
        int runs = Integer.getInteger("runs", 5);
        Path samples = Path.of(System.getProperty("samples", "examples/samples")), shipped = Path.of(System.getProperty("shipped", "src/main/resources/pages"));
        Page.Loader loader = new Page.Loader(true, Path.of("build/samples/cache"));
        Palette palette = new Palette(RenderBench::mapColour);
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long me = Thread.currentThread().getId();
        System.out.println("page targets | repaint cpu ms p50/p90/max | lay-only cpu ms p50 | repaint alloc MB p50 | draw wall ms p50/p90 | tiles p50 | not partial"
                + " | drawn ahead: cpu ms p50, alloc MB p50/p90, apply wall ms p50/max");
        for (String spec : specs) {
            Path file = spec.startsWith("samples:") ? samples.resolve(spec.substring(8) + ".html")
                    : spec.startsWith("pages:") ? shipped.resolve(spec.substring(6) + ".html") : Path.of(spec);
            Page page = Page.load(file, loader);
            LoadBench.Prep prep = new LoadBench.Prep(page);
            List<Double> cpu = new ArrayList<>(), lay = new ArrayList<>(), alloc = new ArrayList<>(), draw = new ArrayList<>(), tiles = new ArrayList<>(),
                    pcpu = new ArrayList<>(), palloc = new ArrayList<>(), apply = new ArrayList<>();
            int failed = 0;
            java.util.Map<Repaint.Why, Integer> whys = new java.util.EnumMap<>(Repaint.Why.class);
            Canvas canvas = new Canvas(palette, LoadBench.W / 128, LoadBench.H / 128, () -> {});
            for (int round = 0; round < runs + 2; round++) {
                boolean count = round >= 2;
                Document d = page.document.clone();
                HtmlRenderer.Frame base = HtmlRenderer.render(page, d, LoadBench.W, LoadBench.H, LoadBench.SCALE, 0, Selector.State.NONE, null, null, 0);
                canvas.draw(base.image(), 0, 0);
                for (int[] t : prep.targets) {
                    Element seen = base.elementAt(t[0] / LoadBench.SCALE, t[1] / LoadBench.SCALE);
                    List<Element> key = page.styles.stateKey(seen, false);
                    if (key.isEmpty()) continue;
                    // On: none to the target. Off: the target to none, from the picture just drawn.
                    HtmlRenderer.Frame on = null;
                    for (int dir = 0; dir < 2; dir++) {
                        HtmlRenderer.Frame before = dir == 0 ? copy(base) : on;
                        if (before == null) break;
                        Set<Element> moved = Collections.newSetFromMap(new IdentityHashMap<>());
                        moved.addAll(key);
                        Selector.State state = dir == 0 ? HtmlRenderer.state(seen, null) : Selector.State.NONE;
                        long c0 = mx.getThreadCpuTime(me), a0 = mx.getThreadAllocatedBytes(me);
                        Repaint.Result r = Repaint.partial(page, d, LoadBench.W, LoadBench.H, LoadBench.SCALE, 0, state, before, moved, 0);
                        long c1 = mx.getThreadCpuTime(me), a1 = mx.getThreadAllocatedBytes(me);
                        if (r.frame() == null) {
                            if (count) {
                                failed++;
                                whys.merge(r.why(), 1, Integer::sum);
                            }
                            break;
                        }
                        Rectangle2D ch = r.changed();
                        int x0 = Math.max(0, (int) Math.floor(ch.getX() * LoadBench.SCALE)), y0 = Math.max(0, (int) Math.floor(ch.getY() * LoadBench.SCALE));
                        int x1 = Math.min(LoadBench.W, (int) Math.ceil(ch.getMaxX() * LoadBench.SCALE)), y1 = Math.min(LoadBench.H, (int) Math.ceil(ch.getMaxY() * LoadBench.SCALE));
                        byte[][] tilesBefore = new byte[LoadBench.TILES][];
                        for (int i = 0; i < LoadBench.TILES; i++) tilesBefore[i] = canvas.tile(i, Canvas.Encoding.HI);
                        long w0 = System.nanoTime();
                        canvas.draw(r.frame().image().getSubimage(x0, y0, x1 - x0, y1 - y0), x0, y0);
                        long w1 = System.nanoTime();
                        int n = 0;
                        for (int i = 0; i < LoadBench.TILES; i++) if (canvas.tile(i, Canvas.Encoding.HI) != tilesBefore[i]) n++;
                        // The same repaint drawn ahead (HoverAhead), over another copy, then put in place.
                        HtmlRenderer.Frame other = copy(before);
                        long p0 = mx.getThreadCpuTime(me), pa0 = mx.getThreadAllocatedBytes(me);
                        Repaint.Prepared ready = Repaint.prepare(page, d, LoadBench.W, LoadBench.H, LoadBench.SCALE, 0, state, other, moved, RenderBudget.cancellable(0));
                        long p1 = mx.getThreadCpuTime(me), pa1 = mx.getThreadAllocatedBytes(me);
                        long q0 = System.nanoTime();
                        if (ready != null) ready.apply(other);
                        long q1 = System.nanoTime();
                        // Style, layout and the walk of the painter with nothing to paint: what any repaint costs before pixels.
                        long l0 = mx.getThreadCpuTime(me);
                        HtmlRenderer.render(page, d, LoadBench.W, LoadBench.H, LoadBench.SCALE, 0, state, copy(base).image(), new Rectangle2D.Double(0, 0, 1, 1), 0);
                        long l1 = mx.getThreadCpuTime(me);
                        if (count) {
                            cpu.add((c1 - c0) / 1e6);
                            alloc.add((a1 - a0) / 1048576.0);
                            draw.add((w1 - w0) / 1e6);
                            tiles.add((double) n);
                            lay.add((l1 - l0) / 1e6);
                            pcpu.add((p1 - p0) / 1e6);
                            palloc.add((pa1 - pa0) / 1048576.0);
                            apply.add((q1 - q0) / 1e6);
                        }
                        on = r.frame();
                    }
                }
            }
            String name = spec.contains("/") ? Path.of(spec).getFileName().toString() : spec;
            System.out.printf("%-20s %3d | %6.1f %6.1f %6.1f | %6.1f | %5.1f | %5.1f %5.1f | %3.0f | %d %s | %6.1f %5.1f %5.1f %5.2f %5.2f%n", name, prep.targets.size(), q(cpu, .5), q(cpu, .9), q(cpu, 1),
                    q(lay, .5), q(alloc, .5), q(draw, .5), q(draw, .9), q(tiles, .5), failed, whys, q(pcpu, .5), q(palloc, .5), q(palloc, .9), q(apply, .5), q(apply, 1));
        }
        System.exit(0);
    }

    private static HtmlRenderer.Frame copy(HtmlRenderer.Frame f) {
        BufferedImage src = f.image();
        BufferedImage img = new BufferedImage(src.getColorModel(), src.copyData(null), src.isAlphaPremultiplied(), null);
        return new HtmlRenderer.Frame(img, f.scale(), f.viewportWidth(), f.viewportHeight(), f.pageHeight(), f.scrollY(), f.hits(), f.styles(), f.document(), f.page());
    }

    private static double q(List<Double> l, double p) {
        if (l.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<>(l);
        Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) (p * s.size())));
    }
}
