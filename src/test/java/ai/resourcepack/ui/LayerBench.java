package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.api.HoverLayers;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.paint.Painter;
import org.jsoup.nodes.Element;

import java.awt.geom.Rectangle2D;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.Deflater;

/**
 * What hover layers ({@link ClientHovers}) cost and save the server, with real {@link Session}s ({@link SessionRig}) on a
 * 16 × 9 view: for each target of a page, the pointer rests beside it (the hover is drawn ahead and, with layers, handed
 * to the client), then goes onto its centre and back out. Per target: whether it got a layer, the layer's maps and
 * compressed bytes, the CPU of the rest (all threads: drawing ahead, and arming), and of going in and out the CPU of
 * the pictures and the server thread (not drawing ahead), the pictures and the compressed bytes, with layers off and
 * on; and per page what arming cost the render threads.
 *
 * <p>System properties: dir (a pages folder, default the shipped one), pages (default market), layers (maps a view
 * gets, default 96), targets (most per page, default 30).
 */
public final class LayerBench {
    private static final java.lang.management.ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    /** CPU time of every live thread, ns: the render threads and the rig's server thread live as long as the rig. */
    private static long cpu() {
        long t = 0;
        for (long id : THREADS.getAllThreadIds()) t += Math.max(0, THREADS.getThreadCpuTime(id));
        return t;
    }
    private static final int COLS = 16, ROWS = 9;

    private record Target(List<Element> key, int[] box) {}

    public static void main(String[] args) throws Exception {
        Path from = Path.of(System.getProperty("dir", "src/main/resources/pages"));
        String[] pages = System.getProperty("pages", "market").split(",");
        int capacity = Integer.getInteger("layers", 96);
        int most = Integer.getInteger("targets", 30);
        Path dir = Files.createTempDirectory("layer-bench");
        copy(from, dir);
        System.out.printf("%-8s %-3s %-26s %5s %5s %8s %9s %8s %6s %9s %8s %6s %9s%n", "page", "on", "target", "maps", "layer", "rest ms",
                "in ms", "in pics", "in KB", "out ms", "out pics", "out KB", "layer KB");
        for (String page : pages) {
            for (boolean on : new boolean[]{false, true}) run(dir, page, on, capacity, most);
        }
    }

    private static void copy(Path from, Path to) throws Exception {
        try (var files = Files.walk(from)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Path d = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) Files.createDirectories(d);
                else Files.copy(f, d);
            }
        }
    }

    private static void run(Path dir, String page, boolean on, int capacity, int most) throws Exception {
        try (SessionRig rig = new SessionRig(dir, 4)) {
            if (rig.pages.load(page).get() == null) throw new IllegalStateException("no page " + page);
            Session s = rig.open(rig.plugin(4), page, COLS, ROWS, 576);
            rig.quiet();
            Canvas layerCanvas = new Canvas(SessionRig.PALETTE, capacity, 1);
            HoverLayers layers = on ? geometry(layerSet(layerCanvas, capacity)) : null;
            if (on) rig.onServer(() -> s.layers(layers));
            Canvas canvas = get(s, "canvas");
            List<Target> targets = targets(get(s, "frame"), canvas.width(), canvas.height(), most);
            double sumRest = 0, sumIn = 0, sumOut = 0, kbIn = 0, kbOut = 0, kbLayer = 0;
            int picsIn = 0, picsOut = 0, armed = 0, maps = 0;
            for (Target t : targets) {
                int[] b = t.box;
                int rx = Math.max(0, b[0] - 28), cy = (b[1] + b[3]) / 2, cx = (b[0] + b[2]) / 2;
                Canvas.Snapshot l0 = layerCanvas.snapshot();
                long c0 = cpu();
                rig.onServer(() -> s.pointer(rx, cy));
                rig.quiet();
                // Again, resting: whatever the move started has finished, and drawing ahead starts from here.
                rig.onServer(() -> s.pointer(rx, cy));
                rig.quiet();
                double rest = (cpu() - c0) / 1e6;
                Canvas.Snapshot l1 = layerCanvas.snapshot();
                double layerKb = on ? bytes(l0, l1, capacity, Canvas.Encoding.HI) / 1024.0 : 0;
                int used = on ? layers.used() : 0;
                ClientHovers h = on ? get(s, "hovers") : null;
                boolean got = on && h.armed(get(s, "frame"), t.key);
                long corr0 = on ? h.corrected : 0;
                if (System.getProperty("why") != null && on && !got) {
                    ai.resourcepack.ui.html.HoverAhead ha = get(s, "hoverAhead");
                    List<Element> dh = get(s, "drawnHover"), dp = get(s, "drawnPress");
                    var ready = ha.ready(get(s, "frame"), dh, dp, dp, k -> true);
                    boolean has = ready.stream().anyMatch(r -> r.hoverKey().equals(t.key));
                    System.out.println("  not armed: ready " + ready.size() + " has-target " + has + " refused " + h.refused + " drawnHover " + dh.size()
                            + (has ? " motion " + ready.stream().filter(r -> r.hoverKey().equals(t.key)).findFirst().get().prepared().motion() : ""));
                }
                double[] in = step(rig, s, canvas, cx, cy);
                if (System.getProperty("why") != null && on) System.out.println("  in: corrected " + (h.corrected - corr0) + " armed-now " + h.armed(get(s, "frame"), t.key) + " key " + t.key.size());
                double[] out = step(rig, s, canvas, rx, cy);
                System.out.printf("%-8s %-3s %-26.26s %5d %5s %8.1f %9.1f %8d %6.1f %9.1f %8d %6.1f %9.1f%n", page, on ? "on" : "off", name(t.key),
                        used, got ? "yes" : "-", rest, in[0], (int) in[1], in[2], out[0], (int) out[1], out[2], layerKb);
                sumRest += rest;
                sumIn += in[0];
                sumOut += out[0];
                picsIn += (int) in[1];
                picsOut += (int) out[1];
                kbIn += in[2];
                kbOut += out[2];
                kbLayer += layerKb;
                if (got) armed++;
                maps = Math.max(maps, used);
            }
            int n = targets.size();
            if (on) {
                ClientHovers hv = get(s, "hovers");
                System.out.printf("%-8s arming: %d layers, %.1f ms of render-thread CPU each (fits, pixels and encoding), %d refused, %d taken off by the server%n",
                        page, hv.armedCount, hv.armNs / 1e6 / Math.max(1, hv.armedCount), hv.refused, hv.corrected);
            }
            System.out.printf("%-8s %-3s %-26s %5d %5s %8.1f %9.1f %8.2f %6.1f %9.1f %8.2f %6.1f %9.1f   (means over %d targets, %d armed; maps: most used)%n",
                    page, on ? "on" : "off", "MEAN", maps, armed + "/" + n, sumRest / n, sumIn / n, picsIn / (double) n, kbIn / n, sumOut / n,
                    picsOut / (double) n, kbOut / n, kbLayer / n, n, armed);
        }
    }

    /** The pointer goes to (x, y) and everything settles: CPU ms, pictures published and compressed KB of the view's maps. */
    private static double[] step(SessionRig rig, Session s, Canvas canvas, int x, int y) {
        Canvas.Snapshot a = canvas.snapshot();
        long v = canvas.version();
        long d0 = drawCpu(rig);
        long c0 = cpu();
        java.util.Map<Long, Long> per = new java.util.HashMap<>();
        boolean why = System.getProperty("whycpu") != null;
        if (why) for (long id : THREADS.getAllThreadIds()) per.put(id, THREADS.getThreadCpuTime(id));
        rig.onServer(() -> s.pointer(x, y));
        rig.quiet();
        double ms = (cpu() - c0) / 1e6;
        if (why && ms > 300) {
            List<String> top = new ArrayList<>();
            for (java.lang.management.ThreadInfo ti : THREADS.getThreadInfo(THREADS.getAllThreadIds())) {
                if (ti == null) continue;
                long d = THREADS.getThreadCpuTime(ti.getThreadId()) - per.getOrDefault(ti.getThreadId(), 0L);
                if (d > 50_000_000L) top.add(ti.getThreadName() + "=" + d / 1_000_000);
            }
            System.out.println("  cpu: " + top);
        }
        return new double[]{(drawCpu(rig) - d0) / 1e6, canvas.version() - v, bytes(a, canvas.snapshot(), canvas.tiles(), Canvas.Encoding.HI_TILED) / 1024.0, ms};
    }

    /** CPU ns of everything but drawing ahead: the server thread, and the render threads' pictures. */
    private static long drawCpu(SessionRig rig) {
        long t = rig.serverCpu.get();
        for (var e : rig.renderCpu.entrySet()) if (e.getKey() != Renders.Kind.AHEAD) t += e.getValue().get();
        return t;
    }

    /** Compressed bytes of the map patches from [a] to [b], as the sender would send them: each tile's changed rectangle. */
    private static long bytes(Canvas.Snapshot a, Canvas.Snapshot b, int tiles, Canvas.Encoding enc) {
        long total = 0;
        Deflater d = new Deflater();
        byte[] buf = new byte[Canvas.TILE + 64];
        for (int i = 0; i < tiles; i++) {
            byte[] p = a.tile(i, enc), q = b.tile(i, enc);
            if (p == q) continue;
            int x0 = 128, y0 = 128, x1 = -1, y1 = -1;
            for (int k = 0; k < Canvas.TILE; k++) {
                if (p[k] == q[k]) continue;
                int x = k & 127, y = k >> 7;
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                x1 = Math.max(x1, x);
                y1 = Math.max(y1, y);
            }
            if (x1 < 0) continue;
            int w = x1 - x0 + 1, h = y1 - y0 + 1;
            byte[] patch = new byte[w * h];
            for (int y = 0; y < h; y++) System.arraycopy(q, (y0 + y) * 128 + x0, patch, y * w, w);
            d.reset();
            d.setInput(patch);
            d.finish();
            int n = 0;
            while (!d.finished()) n += d.deflate(buf);
            total += n + 16;
        }
        d.end();
        return total;
    }

    /** Every target the pointer can hover (one per hover key), in canvas pixels {left, top, right, bottom}, at most [most]. */
    private static List<Target> targets(HtmlRenderer.Frame f, int w, int h, int most) {
        List<Target> out = new ArrayList<>();
        Set<List<Element>> seen = new HashSet<>();
        double s = f.scale();
        for (Painter.Hit hit : f.hits()) {
            if (!hit.pointer()) continue;
            List<Element> key = f.page().styles.stateKey(hit.element(), false);
            if (key.isEmpty() || !seen.add(key)) continue;
            Rectangle2D r = hit.shape().getBounds2D();
            int l = (int) Math.ceil(r.getMinX() * s), t = (int) Math.ceil((r.getMinY() - f.scrollY()) * s);
            int ri = (int) Math.ceil(r.getMaxX() * s), b = (int) Math.ceil((r.getMaxY() - f.scrollY()) * s);
            if (l < 40 || t < 0 || ri > w || b > h || ri - l < 8 || b - t < 8) continue;
            out.add(new Target(key, new int[]{l, t, ri, b}));
            if (out.size() == most) break;
        }
        return out;
    }

    private static String name(List<Element> key) {
        if (key.isEmpty()) return "-";
        Element e = key.get(0);
        String c = e.className();
        return e.tagName() + (c.isEmpty() ? "" : "." + c.replace(' ', '.'));
    }

    /** The framework's own layers (world/LayerSet), with no frames: what arming costs, and the maps it fills. */
    private static HoverLayers layerSet(Canvas canvas, int capacity) throws Exception {
        Class<?> c = Class.forName("ai.resourcepack.ui.framework.world.LayerSet");
        Constructor<?> k = c.getDeclaredConstructors()[0];
        k.setAccessible(true);
        return (HoverLayers) k.newInstance(SessionRig.PALETTE, canvas, null, COLS, ROWS, 8.0, 0);
    }

    /** With -Dgeom: [layers], printing each layer armed: its pixels' place and size, its include rectangle and the maps it took. */
    private static HoverLayers geometry(HoverLayers layers) {
        if (System.getProperty("geom") == null) return layers;
        return new HoverLayers() {
            @Override public int capacity() { return layers.capacity(); }
            @Override public int used() { return layers.used(); }
            @Override public int mapsFor(int[] argb, int x, int y, int w, int h) { return layers.mapsFor(argb, x, y, w, h); }
            @Override public boolean armed(Object key) { return layers.armed(key); }
            @Override public void disarm(Object key) { layers.disarm(key); }
            @Override public void clear() { layers.clear(); }
            @Override public Layer prepare(int[] argb, int x, int y, int w, int h, java.util.function.BooleanSupplier stop) {
                int opaque = 0;
                for (int p : argb) if (p >>> 24 >= 128) opaque++;
                System.out.printf("  prepare at %d,%d %dx%d (%d opaque px)%n", x, y, w, h, opaque);
                return layers.prepare(argb, x, y, w, h, stop);
            }
            @Override public boolean arm(Object key, int[] include, int[] exclude, int priority, Layer layer) {
                int before = layers.used();
                boolean was = layers.armed(key);
                boolean ok = layers.arm(key, include, exclude, priority, layer);
                System.out.printf("  arm %s include %s -> %s, maps %d%n", ok ? "ok" : "NO", java.util.Arrays.toString(include), was ? "re-armed" : "new",
                        layers.used() - before);
                return ok;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Session s, String name) {
        try {
            Field f = Session.class.getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(s);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
