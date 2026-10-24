package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.awt.geom.Rectangle2D;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;

/**
 * What a page that moves costs, with real {@link Session}s ({@link SessionRig}) ticked 20 times a second as on a server:
 * the process's CPU per second of animation, the pictures each viewer is shown a second (against animation-fps), and
 * the bytes a second the map patches for them come to, compressed. N viewers of one page each have a session of their
 * own, as N players in views do; whether they share pictures is the engine's business.
 *
 * <p>Pages are given as name[:hover:selector]: a page alone just runs (its animations); with hover, every viewer moves
 * its pointer onto another element matching the selector every second (staggered), and back off it between.
 *
 * <p>System properties: dir (pages folder), pages, sessions (default 1,10,50), size (default 16x9), height (CSS px,
 * default 576, a view's), secs (measured, default 10), warm (default 3), fps (animation-fps, default 10), threads
 * (cpu-threads, default the cores minus one), out (a TSV to append to), tag (a label for its rows).
 */
public final class AnimBench {
    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(System.getProperty("dir", "src/main/resources/pages"));
        String[] pages = System.getProperty("pages", "spin-shop").split(",");
        int[] counts = Arrays.stream(System.getProperty("sessions", "1,10,50").split(",")).mapToInt(Integer::parseInt).toArray();
        String[] size = System.getProperty("size", "16x9").split("x");
        int cols = Integer.parseInt(size[0]), rows = Integer.parseInt(size[1]);
        double height = Double.parseDouble(System.getProperty("height", "576"));
        double secs = Double.parseDouble(System.getProperty("secs", "10")), warm = Double.parseDouble(System.getProperty("warm", "3"));
        int fps = Integer.getInteger("fps", 10), threads = Integer.getInteger("threads", 0);
        String out = System.getProperty("out"), tag = System.getProperty("tag", "");
        System.out.println("tag\tpage\tmode\tviewers\tcores\tcpu_ms_per_s\tfps_mean\tfps_min\tkb_per_s_viewer\tkb_per_s_all\tdrawn_per_s\tserver_ms_per_s"
                + "\twhole_per_s\tpart_per_s\ttaken_per_s\tlaid_out_per_s\tlaid_again_per_s");
        for (String spec : pages) {
            for (int n : counts) {
                String row = tag + "\t" + cell(dir, spec, n, cols, rows, height, secs, warm, fps, threads);
                System.out.println(row);
                if (out != null) java.nio.file.Files.writeString(Path.of(out), row + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
        }
        System.exit(0);
    }

    /** One viewer's canvas: the patches it was sent, as (tile before, tile after) pairs, counted while [counting]. */
    private static final class Watch {
        final Canvas canvas;
        Canvas.Snapshot last;
        volatile boolean counting;
        final AtomicLong frames = new AtomicLong();
        final List<byte[][]> patches = new ArrayList<>();

        Watch(int cols, int rows) {
            canvas = new Canvas(SessionRig.PALETTE, cols, rows, this::published);
        }

        private synchronized void published() {
            Canvas.Snapshot now = canvas.snapshot();
            if (counting && last != null) {
                boolean any = false;
                for (int i = 0; i < canvas.tiles(); i++) {
                    byte[] a = last.tile(i, Canvas.Encoding.HI), b = now.tile(i, Canvas.Encoding.HI);
                    if (a == b) continue;
                    patches.add(new byte[][]{a, b});
                    any = true;
                }
                if (any) frames.incrementAndGet();
            }
            last = now;
        }
    }

    private static String cell(Path dir, String spec, int n, int cols, int rows, double height, double secs, double warm, int fps, int threads) throws Exception {
        String[] parts = spec.split(":", 3);
        String page = parts[0];
        boolean hover = parts.length > 1 && parts[1].equals("hover");
        String selector = parts.length > 2 ? parts[2] : null;
        try (SessionRig rig = new SessionRig(dir, threads)) {
            MCGUIPlugin plugin = rig.plugin(64);
            YamlConfiguration cfg = get(plugin, "cfg");
            cfg.set("animation-fps", fps);
            Page p = rig.pages.load(page).get(60, TimeUnit.SECONDS);
            if (p == null) throw new IllegalStateException("no page " + page);
            List<int[]> targets = new ArrayList<>();
            if (hover) {
                Document d = p.document.clone();
                int w = cols * 128, h = rows * 128;
                double scale = h / Screens.designHeight(p, height);
                HtmlRenderer.Frame f = HtmlRenderer.render(p, d, w, h, scale, 0, HtmlRenderer.state(null, null));
                for (Element e : d.select(selector)) {
                    Rectangle2D b = f.boundsOf(e);
                    if (b == null || b.getMaxY() - f.scrollY() > f.viewportHeight()) continue;
                    targets.add(new int[]{(int) (b.getCenterX() * scale), (int) ((b.getCenterY() - f.scrollY()) * scale)});
                }
                if (targets.isEmpty()) throw new IllegalStateException("nothing matches " + selector);
            }
            List<Watch> watches = new ArrayList<>();
            List<Session> sessions = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Watch w = new Watch(cols, rows);
                watches.add(w);
                sessions.add(rig.onServer(() -> {
                    Session s = new Session(plugin, rig.renderers, w.canvas, UUID.randomUUID(), height);
                    s.open(page, null, false);
                    return s;
                }));
            }
            rig.quiet();
            long period = 50_000_000L;
            int[] tick = {SessionRig.TICK.get()};
            Runnable step = () -> {
                int now = SessionRig.TICK.incrementAndGet();
                tick[0] = now;
                rig.onServer(() -> {
                    for (int i = 0; i < sessions.size(); i++) {
                        Session s = sessions.get(i);
                        if (hover) {
                            // Each viewer onto its next target every second, and off it (beside the first) half a second later, staggered.
                            int phase = (now + i * 7) % 20;
                            if (phase == 0) {
                                int[] t = targets.get(((now + i * 7) / 20 + i) % targets.size());
                                s.pointer(t[0], t[1]);
                            } else if (phase == 10) {
                                s.pointer(4, 4);
                            }
                        }
                        s.tick(now);
                    }
                });
            };
            run(step, (long) (warm * 20), period);
            for (Watch w : watches) w.counting = true;
            Map<String, Long> threads0 = threadCpu();
            long cpu0 = OS.getProcessCpuTime(), server0 = rig.serverCpu.get(), t0 = System.nanoTime();
            long drawn0 = drawn(rig);
            long[] laid0 = layouts();
            long whole0 = sum(sessions, "drewWhole"), part0 = sum(sessions, "drewPart"), took0 = sum(sessions, "tookFrames");
            int ticks = (int) Math.round(secs * 20);
            run(step, ticks, period);
            for (Watch w : watches) w.counting = false;
            double wall = (System.nanoTime() - t0) / 1e9;
            double cpuMs = (OS.getProcessCpuTime() - cpu0) / 1e6, serverMs = (rig.serverCpu.get() - server0) / 1e6;
            Map<String, Long> threads1 = threadCpu();
            StringBuilder by = new StringBuilder();
            double java = 0;
            for (Map.Entry<String, Long> e : threads1.entrySet()) {
                double ms = (e.getValue() - threads0.getOrDefault(e.getKey(), 0L)) / 1e6 / wall;
                java += ms;
                if (ms >= 1) by.append(String.format(Locale.ROOT, " %s %.0f", e.getKey(), ms));
            }
            System.err.printf(Locale.ROOT, "cpu ms/s %s x%d: %.0f, of which%s, outside Java threads (GC) %.0f%n", spec, n, cpuMs / wall, by, cpuMs / wall - java);
            double drawn = drawn(rig) - drawn0;
            long[] laid = layouts();
            double whole = sum(sessions, "drewWhole") - whole0, part = sum(sessions, "drewPart") - part0;
            double took = sum(sessions, "tookFrames") - took0;
            double fpsSum = 0, fpsMin = Double.MAX_VALUE, bytes = 0;
            Map<byte[], Long> sizes = new IdentityHashMap<>();
            Deflater deflater = new Deflater();
            for (Watch w : watches) {
                double f = w.frames.get() / wall;
                fpsSum += f;
                fpsMin = Math.min(fpsMin, f);
                synchronized (w) {
                    for (byte[][] pt : w.patches) bytes += sizes.computeIfAbsent(pt[1], k -> patchBytes(pt[0], pt[1], deflater));
                }
            }
            rig.onServer(() -> sessions.forEach(Session::dispose));
            return String.format(Locale.ROOT, "%s\t%s\t%d\t%.2f\t%.0f\t%.2f\t%.2f\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f\t%.1f", page, hover ? "hover" : "run", n,
                    cpuMs / wall / 1000, cpuMs / wall, fpsSum / n, fpsMin, bytes / 1024 / wall / n, bytes / 1024 / wall, drawn / wall, serverMs / wall,
                    whole / wall, part / wall, took / wall, (laid[0] - laid0[0]) / wall, (laid[1] - laid0[1]) / wall);
        }
    }

    // The counters below are read by reflection, so that this bench also runs against builds that lack them (a baseline):
    // there they read 0.

    /** The sum of the sessions' counter [name] (an AtomicLong field), or 0 if a session has none. */
    private static long sum(List<Session> sessions, String name) throws ReflectiveOperationException {
        long n = 0;
        for (Session s : sessions) {
            try {
                n += ((AtomicLong) get(s, name)).get();
            } catch (NoSuchFieldException e) {
                return 0;
            }
        }
        return n;
    }

    /** HtmlRenderer.layouts(), or zeros if there is none. */
    private static long[] layouts() throws ReflectiveOperationException {
        try {
            return (long[]) HtmlRenderer.class.getMethod("layouts").invoke(null);
        } catch (NoSuchMethodException e) {
            return new long[2];
        }
    }

    private static final java.lang.management.ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    /** CPU time (ns) of the live threads by kind: their names without numbers. */
    private static Map<String, Long> threadCpu() {
        Map<String, Long> out = new java.util.TreeMap<>();
        for (java.lang.management.ThreadInfo t : THREADS.getThreadInfo(THREADS.getAllThreadIds())) {
            if (t == null) continue;
            long c = THREADS.getThreadCpuTime(t.getThreadId());
            if (c > 0) out.merge(t.getThreadName().replaceAll("\\d+", "#"), c, Long::sum);
        }
        return out;
    }

    /** Runs [step] [ticks] times, one every [period] ns, as a server's ticks come. */
    private static void run(Runnable step, long ticks, long period) throws InterruptedException {
        long next = System.nanoTime();
        for (long i = 0; i < ticks; i++) {
            step.run();
            next += period;
            long wait = next - System.nanoTime();
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
        }
    }

    /**
     * Roughly the compressed size of the patch that brings a viewer's map from [a] to [b]: the rectangle of bytes that
     * differ, deflated, plus a packet's header.
     */
    private static long patchBytes(byte[] a, byte[] b, Deflater deflater) {
        int x0 = 128, y0 = 128, x1 = -1, y1 = -1;
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                int i = y * 128 + x;
                if (i < a.length && i < b.length && a[i] == b[i]) continue;
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
            }
        }
        if (x1 < 0) return 0;
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        byte[] rect = new byte[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(b, (y0 + y) * 128 + x0, rect, y * w, w);
        deflater.reset();
        deflater.setInput(rect);
        deflater.finish();
        byte[] buf = new byte[rect.length + 64];
        long size = 0;
        while (!deflater.finished()) size += deflater.deflate(buf);
        return size + 12;
    }

    private static long drawn(SessionRig rig) throws ReflectiveOperationException {
        return ((AtomicLong) get(rig, "drawn")).get();
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return (T) f.get(o);
            } catch (NoSuchFieldException ignored) {
                // Further up.
            }
        }
        throw new NoSuchFieldException(name);
    }
}
