package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.css.Values;
import ai.resourcepack.ui.html.paint.Painter;
import ai.resourcepack.ui.html.style.Len;
import ai.resourcepack.ui.html.style.Style;
import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.render.Palette;
import com.sun.management.OperatingSystemMXBean;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.Deflater;

/**
 * What many players at once cost: N sessions in one process, the way the plugin runs them. Each has its
 * own copy of the page's document and its own UI Framework {@link Canvas} (2048 x 1152, drawn at 2x),
 * renders go through one fixed pool of R threads (the plugin's {@code render-threads}), at most one in
 * flight per session with the newest request winning (as {@code Session.render} coalesces), and every
 * finished frame is handed to {@code Canvas.draw}. Inputs run on one "server thread".
 *
 * <p>{@link Sim} mirrors {@code Session.submit}: hover and press changes repaint only a dirty region on
 * top of the last picture, anything else (a scroll, a new page, a page where hover restyles siblings)
 * is a full picture, and a full picture is followed by a check of what is under the pointer. The dirty
 * region logic is copied from Session (private there): keep them in step.
 *
 * <p>Modes: {@code walk} (a pointer walking across the page's hover targets, sampled at 20 Hz, dwelling a
 * while on each, plus a scroll notch a second), {@code flick} (every 20 Hz sample lands on another hover
 * target, so every one is a repaint: the worst a hand can do) and {@code stress} (every frame asks for
 * the next as soon as it lands: a scroll sweep, or the page nudged when it does not scroll).
 *
 * <p>Optionally ({@code -Dsend=true}) a sender thread does what MapSender does with each canvas
 * (snapshot the changed tiles, diff each against what the client has, build the patch) and deflates each
 * patch packet at the default level, to count the bytes a viewer would receive.
 *
 * <p>Run with {@code ./gradlew loadBench}; knobs are {@code -P} properties, see build.gradle.
 */
public final class LoadBench {
    static final int W = 2048, H = 1152;
    static final double SCALE = H / 576.0;
    static final int TILES = (W / 128) * (H / 128);
    static final double SCROLL_STEP = 80;
    static final double STRESS_STEP = 37;
    /** Requests answered later than this are listed (-Dslow=<ms>). */
    static final long SLOW_NS = Long.getLong("slow", 10_000) * 1_000_000L;

    // ------------------------------------------------------------------------------------ run

    public static void main(String[] args) throws Exception {
        List<String> pageSpecs = list("pages", "menu,landing,shop");
        List<Integer> sessions = ints("sessions", "1,2,4,8,16,32");
        List<Integer> threads = ints("render", "1,2,4");
        List<Integer> bands = ints("bands", "4");
        List<String> modes = list("modes", "walk");
        double secs = Double.parseDouble(System.getProperty("secs", "12"));
        double warm = Double.parseDouble(System.getProperty("warm", "2"));
        boolean send = Boolean.getBoolean("send");
        HtmlRenderer.adaptiveBands = Boolean.parseBoolean(System.getProperty("adaptive", "true"));
        String out = System.getProperty("out", "");
        String tag = System.getProperty("tag", "");
        Path samples = Path.of(System.getProperty("samples", "samples")), shipped = Path.of(System.getProperty("shipped", "src/main/resources/pages"));
        Page.Loader loader = new Page.Loader(true, Path.of("build/samples/cache"));
        Palette palette = new Palette(RenderBench::mapColour);

        if (!out.isEmpty() && Files.notExists(Path.of(out))) {
            Files.writeString(Path.of(out), Result.header() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE);
        }
        System.out.printf("cores=%d  heap=%d MB  gc=%s  secs=%.0f warm=%.0f send=%s tag=%s%n", Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() >> 20, ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).toList(),
                secs, warm, send, tag);
        for (String spec : pageSpecs) {
            Page page = Page.load(resolve(spec, samples, shipped), loader);
            Prep prep = new Prep(page);
            System.out.printf("%n== %s: page %.0f px tall (view %.0f), %d hover targets%n", spec, prep.pageHeight, prep.viewHeight, prep.targets.size());
            // JIT, fonts, pools: one throwaway cell per page.
            if (!Boolean.getBoolean("nowarmcell")) run(prep, new Cfg("walk", 2, 2, 4, 3, 1, false, ""), palette);
            for (String mode : modes) {
                for (int b : bands) {
                    for (int n : sessions) {
                        for (int r : threads) {
                            Result res = run(prep, new Cfg(mode, n, r, b, secs, warm, send, tag), palette);
                            System.out.println(res.line(spec));
                            if (!out.isEmpty()) Files.writeString(Path.of(out), res.csv(spec) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                        }
                    }
                }
            }
        }
        System.exit(0);
    }

    private static List<String> list(String key, String def) {
        return Arrays.stream(System.getProperty(key, def).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static List<Integer> ints(String key, String def) {
        return list(key, def).stream().map(Integer::valueOf).toList();
    }

    /** "samples:menu", "pages:shop", or a bare name (samples first, then the shipped pages). */
    private static Path resolve(String spec, Path samples, Path shipped) {
        if (spec.startsWith("samples:")) return samples.resolve(spec.substring(8) + ".html");
        if (spec.startsWith("pages:")) return shipped.resolve(spec.substring(6) + ".html");
        Path p = samples.resolve(spec + ".html");
        return Files.exists(p) ? p : shipped.resolve(spec + ".html");
    }

    record Cfg(String mode, int sessions, int renderThreads, int bands, double secs, double warm, boolean send, String tag) {}

    // ------------------------------------------------------------------------------------ page prep

    /** What a page offers the drivers: where its hover targets are (canvas pixels) and how far it scrolls. */
    static final class Prep {
        final Page page;
        final List<int[]> targets = new ArrayList<>();
        final double pageHeight, viewHeight, maxScroll;

        Prep(Page page) {
            this.page = page;
            Document copy = page.document.clone();
            HtmlRenderer.Frame f = HtmlRenderer.render(page, copy, W, H, SCALE, 0, Selector.State.NONE);
            pageHeight = f.pageHeight();
            viewHeight = f.viewportHeight();
            maxScroll = f.maxScroll();
            IdentityHashMap<Element, Rectangle2D> area = new IdentityHashMap<>();
            for (Painter.Hit h : f.hits()) {
                Rectangle2D b = h.shape().getBounds2D();
                for (Element e = h.element(); e != null; e = e.parent()) {
                    // An element with a :hover rule of its own (its descendants only inherit the key).
                    if (!page.styles.stateKey(e, false).contains(e)) continue;
                    Rectangle2D a = area.get(e);
                    if (a == null) area.put(e, new Rectangle2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight()));
                    else a.add(b);
                }
            }
            // Document order, so a run is reproducible.
            for (Element e : copy.getAllElements()) {
                Rectangle2D a = area.get(e);
                if (a == null || a.getWidth() < 12 || a.getHeight() < 12) continue;
                double cx = a.getCenterX(), cy = a.getCenterY();
                if (cy < 0 || cy >= f.viewportHeight() || cx < 0 || cx >= f.viewportWidth()) continue;
                // Skip a target that sits entirely inside one already listed (a link inside a card is the same spot).
                targets.add(new int[]{(int) Math.round(cx * SCALE), (int) Math.round(cy * SCALE)});
            }
        }
    }

    // ------------------------------------------------------------------------------------ one cell

    private static final OperatingSystemMXBean OS = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final int CORES = Runtime.getRuntime().availableProcessors();

    static Result run(Prep prep, Cfg cfg, Palette palette) throws Exception {
        HtmlRenderer.maxBands = cfg.bands;
        System.gc();
        AtomicInteger tn = new AtomicInteger();
        ExecutorService renderers = Executors.newFixedThreadPool(cfg.renderThreads, r -> {
            Thread t = new Thread(r, "LoadBench-render-" + tn.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        ScheduledThreadPoolExecutor server = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "LoadBench-server");
            t.setDaemon(true);
            return t;
        });
        server.setRemoveOnCancelPolicy(true);
        boolean stress = cfg.mode.equals("stress");
        Sender sender = cfg.send ? new Sender() : null;
        List<Sim> sims = new ArrayList<>();
        for (int i = 0; i < cfg.sessions; i++) {
            Sim s = new Sim(i, prep, new Canvas(palette, W / 128, H / 128, sender == null ? () -> {} : sender::wake), renderers, server, cfg.mode);
            sims.add(s);
            if (sender != null) sender.add(s.canvas);
        }
        if (sender != null) sender.start();

        long open = System.nanoTime();
        // Everybody opens the page at once (a first picture each), then the drivers start.
        for (Sim s : sims) server.execute(s::open);
        Random rnd = new Random(7);
        if (!stress) {
            for (Sim s : sims) {
                server.scheduleAtFixedRate(s::hoverTick, 300 + rnd.nextInt(50), 50, TimeUnit.MILLISECONDS);
                if (prep.maxScroll > 1) server.scheduleAtFixedRate(s::scrollNotch, 300 + rnd.nextInt(1000), 1000, TimeUnit.MILLISECONDS);
            }
        }
        long warmEnd = open + (long) (cfg.warm * 1e9);
        sleepUntil(warmEnd);
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) if (p.getType() == MemoryType.HEAP) p.resetPeakUsage();
        long gc0 = gcMs(), gcN0 = gcCount();
        long cpu0 = OS.getProcessCpuTime();
        long[] th0 = threadCpu();
        long[] sys0 = procStat();
        long sender0 = sender == null ? 0 : sender.reset();
        for (AtomicInteger w : WHY) w.set(0);
        long t0 = System.nanoTime();
        long tEnd = t0 + (long) (cfg.secs * 1e9);
        sleepUntil(tEnd);
        long cpu1 = OS.getProcessCpuTime();
        long[] th1 = threadCpu();
        long[] sys1 = procStat();
        long t1 = System.nanoTime();
        long gc1 = gcMs(), gcN1 = gcCount();
        long peak = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) if (p.getType() == MemoryType.HEAP) peak += p.getPeakUsage().getUsed();
        Sender.Totals wire = sender == null ? null : sender.snapshot();

        // Stop the inputs, let what is in flight land.
        int[] why = Arrays.stream(WHY).mapToInt(AtomicInteger::get).toArray();
        long senderEnd = sender == null ? 0 : sender.cpuNs();
        server.shutdownNow();
        for (Sim s : sims) s.close();
        renderers.shutdown();
        renderers.awaitTermination(60, TimeUnit.SECONDS);
        if (sender != null) sender.stop();
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long live = rt.totalMemory() - rt.freeMemory();
        // Hold the sessions until after the measurement of live heap.
        int keep = sims.size();

        Result r = new Result(cfg);
        r.keep = keep;
        r.prep = prep;
        r.collect(sims, t0, t1);
        double wall = (t1 - t0) / 1e9;
        r.cpu = (cpu1 - cpu0) / 1e9 / wall / CORES * 100;
        long busy = (sys1[0] - sys0[0]), total = (sys1[1] - sys0[1]);
        double own = (cpu1 - cpu0) / 1e7; // ticks of 10 ms
        r.otherCpu = Math.max(0, (busy - own) / (double) Math.max(1, total) * 100);
        r.stealPct = (sys1[2] - sys0[2]) / (double) Math.max(1, total) * 100;
        r.liveMb = live / 1048576.0;
        r.peakMb = peak / 1048576.0;
        r.gcMs = gc1 - gc0;
        r.gcN = gcN1 - gcN0;
        r.wire = wire;
        for (int i = 0; i < 5; i++) r.threadCpuMs[i] = (th1[i] - th0[i]) / 1e6;
        for (int i = 0; i < 5; i++) r.allocMb += (th1[5 + i] - th0[5 + i]) / 1048576.0;
        r.processCpuMs = (cpu1 - cpu0) / 1e6;
        r.why = why;
        r.senderCpu = sender == null ? 0 : (senderEnd - sender0) / 1e9 / wall * 100;
        r.firstMs = sims.stream().mapToDouble(s -> s.firstFrameNs > 0 ? (s.firstFrameNs - open) / 1e6 : Double.NaN).toArray();
        r.secs = wall;
        sims.clear();
        return r;
    }

    /** CPU ns so far of {render threads, painter pool, encoder pool, sender, server thread}, then the bytes each group has allocated: what a frame costs whatever else the machine is doing. */
    private static long[] threadCpu() {
        var mx = ManagementFactory.getThreadMXBean();
        var sun = (com.sun.management.ThreadMXBean) mx;
        long[] out = new long[10];
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            long c = mx.getThreadCpuTime(t.getId());
            if (c < 0) continue;
            String n = t.getName();
            int g = n.startsWith("LoadBench-render") ? 0 : n.startsWith("ForkJoinPool-1-") ? 1 : n.startsWith("ForkJoinPool-2-") ? 2
                    : n.equals("LoadBench-send") ? 3 : n.equals("LoadBench-server") ? 4 : -1;
            if (g < 0) continue;
            out[g] += c;
            out[5 + g] += Math.max(0, sun.getThreadAllocatedBytes(t.getId()));
        }
        return out;
    }

    private static void sleepUntil(long nanoTime) {
        long d;
        while ((d = nanoTime - System.nanoTime()) > 0) LockSupport.parkNanos(Math.min(d, 20_000_000));
    }

    private static long gcMs() {
        long t = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) t += Math.max(0, b.getCollectionTime());
        return t;
    }

    private static long gcCount() {
        long t = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) t += Math.max(0, b.getCollectionCount());
        return t;
    }

    /** {busy ticks, total ticks, steal ticks} of the whole machine from /proc/stat. */
    private static long[] procStat() {
        try {
            String[] f = Files.readAllLines(Path.of("/proc/stat")).get(0).trim().split("\\s+");
            long[] v = new long[10];
            for (int i = 1; i < f.length && i < 11; i++) v[i - 1] = Long.parseLong(f[i]);
            long total = 0;
            for (int i = 0; i < 8; i++) total += v[i];
            long idle = v[3] + v[4];
            return new long[]{total - idle, total, v[7]};
        } catch (IOException | RuntimeException e) {
            return new long[]{0, 1, 0};
        }
    }

    // ------------------------------------------------------------------------------------ a session

    private static final int OPEN = 0, HOVER = 1, SCROLL = 2, STRESS = 3;

    private record Req(long t, int kind) {}

    /** One finished frame. */
    private record Rec(long done, long queueNs, long renderNs, long drawNs, boolean partial, int tiles, int coalesced) {}

    private record Lat(long at, int kind, long ns) {}

    /** Session's rendering flow with the Bukkit parts taken out. */
    static final class Sim {
        final int id;
        final Prep prep;
        final Page page;
        final Canvas canvas;
        final ExecutorService renderers;
        final ScheduledThreadPoolExecutor server;
        final String mode;
        final Random rnd;

        // server thread only
        Document doc;
        double scrollY;
        Element hover;
        int pointerX = -1, pointerY = -1;
        int scrollDir = 1, stressDir = 1, tick, targetIx, dwell;
        double px, py;
        int[] goal;

        volatile HtmlRenderer.Frame frame;
        private boolean rendering, dirty, closed;
        private List<Req> reqs = new ArrayList<>();
        private List<Element> drawnHover = List.of(), drawnPress = List.of();
        private final byte[][] lastTiles = new byte[TILES][];
        final List<Rec> recs = Collections.synchronizedList(new ArrayList<>());
        final List<Lat> lats = Collections.synchronizedList(new ArrayList<>());
        volatile long firstFrameNs;

        Sim(int id, Prep prep, Canvas canvas, ExecutorService renderers, ScheduledThreadPoolExecutor server, String mode) {
            this.id = id;
            this.prep = prep;
            this.page = prep.page;
            this.canvas = canvas;
            this.renderers = renderers;
            this.server = server;
            this.mode = mode;
            this.rnd = new Random(1000 + id);
            for (int t = 0; t < TILES; t++) lastTiles[t] = canvas.tile(t, Canvas.Encoding.HI);
        }

        void close() {
            synchronized (this) {
                closed = true;
            }
        }

        // ---- the page opens (Session.show)
        void open() {
            doc = page.document.clone();
            scrollY = 0;
            render(OPEN);
        }

        // ---- Session.render
        synchronized void render(int kind) {
            if (closed) return;
            reqs.add(new Req(System.nanoTime(), kind));
            if (rendering) {
                dirty = true;
                return;
            }
            rendering = true;
            submit();
        }

        private List<Element> key(Element e) {
            return page.styles.stateKey(e, false);
        }

        private void submit() {
            Page p = page;
            Document d = doc;
            double s = scrollY;
            var state = HtmlRenderer.state(hover, null);
            List<Element> hoverKey = key(hover);
            List<Req> mine = reqs;
            reqs = new ArrayList<>();
            long submitted = System.nanoTime();
            renderers.execute(() -> {
                try {
                    long t0 = System.nanoTime();
                    HtmlRenderer.Frame before = frame;
                    HtmlRenderer.Frame f = null;
                    Rectangle2D changed = null;
                    boolean partial = false;
                    if (before == null) why(0);
                    else if (before.document() != d || before.page() != p) why(1);
                    else if (Math.abs(before.scrollY() - Math.max(0, Math.min(s, before.maxScroll()))) >= 0.01) why(2);
                    if (before != null && before.document() == d && before.page() == p && before.scale() == SCALE
                            && Math.abs(before.scrollY() - Math.max(0, Math.min(s, before.maxScroll()))) < 0.01) {
                        Set<Element> moved = changedState(hoverKey);
                        if (moved.isEmpty()) {
                            why(3);
                            drawnHover = hoverKey;
                            // Nothing to draw: the requests are answered now.
                            long now = System.nanoTime();
                            for (Req r : mine) lats.add(new Lat(r.t, r.kind, now - r.t));
                            return;
                        }
                        changed = p.siblingStates() ? null : dirtyRegion(before, moved);
                        if (p.siblingStates()) why(4);
                        else if (changed == null) why(5);
                        else if (!small(changed, before)) why(6);
                        if (changed != null && small(changed, before)) {
                            f = HtmlRenderer.render(p, d, W, H, SCALE, s, state, before.image(), changed);
                            Rectangle2D after = dirtyRegion(f, moved);
                            if (after == null) {
                                why(7);
                                f = null;
                            } else if (!changed.contains(after)) {
                                changed = changed.createUnion(after);
                                f = small(changed, before) ? HtmlRenderer.render(p, d, W, H, SCALE, s, state, before.image(), changed) : null;
                            }
                            if (f != null && sameLayout(before, f, changed)) partial = true;
                            else {
                                why(8);
                                f = null;
                            }
                        }
                    }
                    if (f == null) f = HtmlRenderer.render(p, d, W, H, SCALE, s, state);
                    long t1 = System.nanoTime();
                    if (partial) {
                        int x0 = Math.max(0, (int) Math.floor(changed.getX() * SCALE)), y0 = Math.max(0, (int) Math.floor(changed.getY() * SCALE));
                        int x1 = Math.min(W, (int) Math.ceil(changed.getMaxX() * SCALE));
                        int y1 = Math.min(H, (int) Math.ceil(changed.getMaxY() * SCALE));
                        if (x1 > x0 && y1 > y0) canvas.draw(f.image().getSubimage(x0, y0, x1 - x0, y1 - y0), x0, y0);
                    } else {
                        canvas.draw(f.image(), 0, 0);
                    }
                    long t2 = System.nanoTime();
                    int tiles = 0;
                    for (int t = 0; t < TILES; t++) {
                        byte[] cur = canvas.tile(t, Canvas.Encoding.HI);
                        if (cur != lastTiles[t]) {
                            lastTiles[t] = cur;
                            tiles++;
                        }
                    }
                    drawnHover = hoverKey;
                    frame = f;
                    if (firstFrameNs == 0) firstFrameNs = t2;
                    recs.add(new Rec(t2, t0 - submitted, t1 - t0, t2 - t1, partial, tiles, mine.size() - 1));
                    for (Req r : mine) lats.add(new Lat(r.t, r.kind, t2 - r.t));
                    if (!partial) execute(this::repoint);
                } catch (RuntimeException | Error e) {
                    System.err.println("render failed: " + e);
                    e.printStackTrace();
                } finally {
                    synchronized (Sim.this) {
                        if (dirty && !closed) {
                            dirty = false;
                            execute(() -> {
                                synchronized (Sim.this) {
                                    if (!closed) submit();
                                    else rendering = false;
                                }
                            });
                        } else {
                            rendering = false;
                            if (mode.equals("stress") && !closed) execute(Sim.this::advance);
                        }
                    }
                }
            });
        }

        private void execute(Runnable r) {
            try {
                server.execute(r);
            } catch (RejectedExecutionException e) {
                synchronized (this) {
                    rendering = false;
                }
            }
        }

        // ---- input (Session.pointer, repoint, scroll)
        private Element at(int x, int y) {
            HtmlRenderer.Frame f = frame;
            if (f == null || f.page() != page) return null;
            return f.elementAt(x / SCALE, y / SCALE);
        }

        private Element counterpart(Element e, Document to) {
            if (e == null) return null;
            Document from = e.ownerDocument();
            if (from == to) return e;
            if (from == null) return null;
            int i = from.getAllElements().indexOf(e);
            var all = to.getAllElements();
            return i >= 0 && i < all.size() ? all.get(i) : null;
        }

        void pointer(int x, int y) {
            pointerX = x;
            pointerY = y;
            Element e = counterpart(at(x, y), doc);
            if (e == hover) return;
            boolean differs = !key(hover).equals(key(e));
            hover = e;
            if (differs) render(HOVER);
        }

        void repoint() {
            if (closed || pointerX < 0) return;
            Element e = counterpart(at(pointerX, pointerY), doc);
            boolean differs = !key(e).equals(drawnHover);
            hover = e;
            if (differs) render(HOVER);
        }

        void scroll(int notches) {
            HtmlRenderer.Frame f = frame;
            if (f == null) return;
            double to = Math.max(0, Math.min(f.maxScroll(), scrollY + notches * SCROLL_STEP));
            if (to == scrollY) return;
            scrollY = to;
            render(SCROLL);
        }

        // ---- the simulated hand
        void scrollNotch() {
            HtmlRenderer.Frame f = frame;
            if (f == null) return;
            if (scrollDir > 0 && scrollY >= f.maxScroll() - 0.5) scrollDir = -1;
            else if (scrollDir < 0 && scrollY <= 0.5) scrollDir = 1;
            scroll(scrollDir);
        }

        /** 20 Hz: one pointer sample. */
        void hoverTick() {
            List<int[]> ts = prep.targets;
            if (ts.isEmpty()) return;
            if (mode.equals("flick")) {
                int[] t = ts.get(targetIx++ % ts.size());
                pointer(t[0], t[1]);
                return;
            }
            if (goal == null) {
                goal = ts.get(rnd.nextInt(ts.size()));
                if (px == 0 && py == 0) {
                    px = goal[0];
                    py = goal[1];
                }
                dwell = 8 + rnd.nextInt(16);
            }
            if (dwell > 0) {
                dwell--;
                if (dwell == 0) goal = ts.get(rnd.nextInt(ts.size()));
                pointer((int) px + rnd.nextInt(3) - 1, (int) py + rnd.nextInt(3) - 1);
                return;
            }
            // About 1200 canvas pixels a second: crossing the screen takes a second or so.
            double dx = goal[0] - px, dy = goal[1] - py, dist = Math.hypot(dx, dy), step = 60;
            if (dist <= step) {
                px = goal[0];
                py = goal[1];
                dwell = 8 + rnd.nextInt(16);
            } else {
                px += dx / dist * step;
                py += dy / dist * step;
            }
            pointer((int) px, (int) py);
        }

        /** Stress: the next frame, as soon as the last landed. */
        void advance() {
            synchronized (this) {
                if (closed) return;
            }
            HtmlRenderer.Frame f = frame;
            double max = f == null ? 0 : f.maxScroll();
            if (max > 1) {
                scrollY += stressDir * STRESS_STEP;
                if (scrollY >= max) {
                    scrollY = max;
                    stressDir = -1;
                } else if (scrollY <= 0) {
                    scrollY = 0;
                    stressDir = 1;
                }
            } else {
                // A page that does not scroll: nudge everything on it a few pixels, so every tile changes.
                tick++;
                doc = doc.clone();
                hover = null;
                Element body = doc.body();
                if (body != null) body.attr("style", "position:relative;top:" + (tick * 3 % 24) + "px");
            }
            render(STRESS);
        }

        // ---- copied from Session (private there)

        private Set<Element> changedState(List<Element> hoverKey) {
            Set<Element> changed = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Element e : drawnHover) if (!hoverKey.contains(e)) changed.add(e);
            for (Element e : hoverKey) if (!drawnHover.contains(e)) changed.add(e);
            return changed;
        }
    }

    /** Why a repaint was not partial: 0 first picture, 1 other document or page, 2 scrolled, 3 nothing changed, 4 sibling states, 5 no dirty region, 6 region too big, 7 grew on hover, 8 layout moved. */
    static final AtomicInteger[] WHY = new AtomicInteger[9];
    static {
        for (int i = 0; i < WHY.length; i++) WHY[i] = new AtomicInteger();
    }

    static void why(int i) {
        WHY[i].incrementAndGet();
    }

    private static final double DIRTY_MARGIN = 48;

    private static Rectangle2D dirtyRegion(HtmlRenderer.Frame f, Set<Element> changed) {
        Rectangle2D area = null;
        double reach = DIRTY_MARGIN;
        for (Painter.Hit h : f.hits()) {
            boolean inside = false;
            for (Element e = h.element(); e != null && !inside; e = e.parent()) inside = changed.contains(e);
            if (!inside) continue;
            Rectangle2D b = h.shape().getBounds2D();
            if (area == null) area = new Rectangle2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight());
            else area.add(b);
            reach = Math.max(reach, paintsOutside(f.style(h.element())));
        }
        if (area == null) return null;
        Rectangle2D view = new Rectangle2D.Double(0, 0, f.viewportWidth(), f.viewportHeight());
        Rectangle2D padded = new Rectangle2D.Double(area.getX() - reach, area.getY() - f.scrollY() - reach,
                area.getWidth() + 2 * reach, area.getHeight() + 2 * reach);
        Rectangle2D out = padded.createIntersection(view);
        if (out.isEmpty()) return null;
        for (Painter.Hit h : f.hits()) {
            Style s = f.style(h.element());
            if (s == null || s.backdropFilter.equals("none")) continue;
            Rectangle2D b = h.shape().getBounds2D();
            if (out.intersects(b.getX(), b.getY() - f.scrollY(), b.getWidth(), b.getHeight())) return null;
        }
        return out;
    }

    private static boolean small(Rectangle2D region, HtmlRenderer.Frame f) {
        return region.getWidth() * region.getHeight() < 0.6 * f.viewportWidth() * f.viewportHeight();
    }

    private static double paintsOutside(Style s) {
        if (s == null) return 0;
        return Math.max(Math.max(shadowReach(s.boxShadow, s), shadowReach(s.textShadow, s)), blurReach(s.filter, s));
    }

    private static double shadowReach(String value, Style s) {
        if (value == null || value.isBlank() || value.trim().equalsIgnoreCase("none")) return 0;
        double reach = 0;
        for (String shadow : Values.splitCommas(value)) {
            double[] lens = new double[4];
            int n = 0;
            boolean inset = false;
            for (String tok : Values.splitSpaces(shadow)) {
                if (tok.equalsIgnoreCase("inset")) {
                    inset = true;
                    continue;
                }
                Len l = Len.parse(tok, s.context);
                if (l != null && n < 4) lens[n++] = Math.abs(l.of(0));
            }
            if (inset || n < 2) continue;
            reach = Math.max(reach, Math.max(lens[0], lens[1]) + lens[3] + 1.5 * lens[2] + 2);
        }
        return reach;
    }

    private static double blurReach(String filter, Style s) {
        if (filter == null || filter.equals("none")) return 0;
        double reach = 0;
        for (String fn : Values.splitSpaces(filter)) {
            if (!fn.toLowerCase(Locale.ROOT).startsWith("blur(") || !fn.endsWith(")")) continue;
            String arg = fn.substring(5, fn.length() - 1).trim();
            Len l = Len.parse(arg.isEmpty() ? "0" : arg, s.context);
            if (l != null) reach = Math.max(reach, 3 * Math.abs(l.of(0)) + 2);
        }
        return reach;
    }

    private static boolean sameLayout(HtmlRenderer.Frame a, HtmlRenderer.Frame b, Rectangle2D region) {
        if (a.hits().size() != b.hits().size()) return false;
        for (int i = 0; i < a.hits().size(); i++) {
            Rectangle2D ra = a.hits().get(i).shape().getBounds2D(), rb = b.hits().get(i).shape().getBounds2D();
            if (Math.abs(ra.getX() - rb.getX()) < 0.5 && Math.abs(ra.getY() - rb.getY()) < 0.5
                    && Math.abs(ra.getWidth() - rb.getWidth()) < 0.5 && Math.abs(ra.getHeight() - rb.getHeight()) < 0.5) continue;
            Rectangle2D va = new Rectangle2D.Double(ra.getX(), ra.getY() - a.scrollY(), ra.getWidth(), ra.getHeight());
            Rectangle2D vb = new Rectangle2D.Double(rb.getX(), rb.getY() - b.scrollY(), rb.getWidth(), rb.getHeight());
            if (!region.contains(va) || !region.contains(vb)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------------------ the wire

    /**
     * What MapSender does with each canvas, for one viewer each: snapshot the tiles that differ from what
     * the client has, send each as the smallest rectangle covering its changed bytes, and count the bytes of
     * that map packet raw and deflated (zlib at the default level, as the connection's compression does,
     * which leaves packets under 256 bytes alone).
     */
    static final class Sender {
        record Totals(long batches, long tiles, long patches, long rawBytes, long deflatedBytes, long fullTileBytes, long deflateNs, long diffNs) {}

        private static final int HEADER = 14;
        private final List<Canvas> canvases = new ArrayList<>();
        private final List<byte[][]> sent = new ArrayList<>();
        private final Thread thread = new Thread(this::loop, "LoadBench-send");
        private volatile boolean running = true;
        private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        private final byte[] buf = new byte[HEADER + 128 * 128 + 1024];
        private final byte[] outBuf = new byte[HEADER + 128 * 128 + 1024];
        private long batches, tiles, patches, raw, deflated, full, deflateNs, diffNs;

        Sender() {
            thread.setDaemon(true);
        }

        void add(Canvas c) {
            canvases.add(c);
            byte[][] s = new byte[TILES][];
            // The client has the first picture already: only what changes after counts.
            for (int t = 0; t < TILES; t++) s[t] = c.tile(t, Canvas.Encoding.HI);
            sent.add(s);
        }

        void start() {
            thread.start();
        }

        void wake() {
            LockSupport.unpark(thread);
        }

        void stop() throws InterruptedException {
            running = false;
            wake();
            thread.join(5000);
        }

        long reset() {
            synchronized (this) {
                batches = tiles = patches = raw = deflated = full = deflateNs = diffNs = 0;
            }
            return cpuNs();
        }

        long cpuNs() {
            return ManagementFactory.getThreadMXBean().getThreadCpuTime(thread.getId());
        }

        synchronized Totals snapshot() {
            return new Totals(batches, tiles, patches, raw, deflated, full, deflateNs, diffNs);
        }

        private void loop() {
            while (running) {
                for (int i = 0; i < canvases.size(); i++) pass(canvases.get(i), sent.get(i));
                LockSupport.parkNanos(8_000_000L);
            }
        }

        private void pass(Canvas canvas, byte[][] has) {
            long diffStart = System.nanoTime();
            int changedTiles = 0, patchCount = 0;
            long rawBytes = 0, deflatedBytes = 0, deflateTime = 0;
            for (int t = 0; t < TILES; t++) {
                byte[] cur = canvas.tile(t, Canvas.Encoding.HI);
                if (cur == has[t]) continue;
                changedTiles++;
                int[] rect = diff(has[t], cur);
                has[t] = cur;
                if (rect == null) continue;
                patchCount++;
                int w = rect[2], h = rect[3];
                int n = HEADER;
                // Packet id, map id, scale, locked, no icons, then the rectangle (columns, rows, x, z) and its length.
                Arrays.fill(buf, 0, HEADER, (byte) 1);
                for (int y = 0; y < h; y++) {
                    System.arraycopy(cur, (rect[1] + y) * 128 + rect[0], buf, n, w);
                    n += w;
                }
                rawBytes += n;
                if (n < 256) {
                    deflatedBytes += n;
                    continue;
                }
                long d0 = System.nanoTime();
                deflater.reset();
                deflater.setInput(buf, 0, n);
                deflater.finish();
                int total = 0;
                while (!deflater.finished()) total += deflater.deflate(outBuf, 0, outBuf.length);
                deflateTime += System.nanoTime() - d0;
                deflatedBytes += total;
            }
            if (changedTiles == 0) return;
            long diffTime = System.nanoTime() - diffStart - deflateTime;
            synchronized (this) {
                batches++;
                tiles += changedTiles;
                patches += patchCount;
                raw += rawBytes;
                deflated += deflatedBytes;
                full += (long) changedTiles * 128 * 128;
                deflateNs += deflateTime;
                diffNs += diffTime;
            }
        }

        private static int[] diff(byte[] previous, byte[] current) {
            int minX = 128, minY = 128, maxX = -1, maxY = -1;
            for (int y = 0; y < 128; y++) {
                int row = y * 128;
                for (int x = 0; x < 128; x++) {
                    if (previous[row + x] != current[row + x]) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        maxY = y;
                    }
                }
            }
            return maxX < 0 ? null : new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
        }
    }

    // ------------------------------------------------------------------------------------ results

    static final class Result {
        final Cfg cfg;
        int keep;
        Prep prep;
        double secs, cpu, otherCpu, stealPct, liveMb, peakMb, senderCpu;
        long gcMs, gcN;
        double[] firstMs;
        Sender.Totals wire;
        long[] hover = {}, scroll = {}, stressLat = {}, openLat = {};
        int[] why = new int[9];
        /** CPU ms in the window: render threads, painters, encoders, sender, server thread; and the whole process. */
        final double[] threadCpuMs = new double[5];
        double processCpuMs, allocMb;
        long requests, dropped, partialFrames, fullFrames, noops;
        long[] qWait = {}, rPartial = {}, rFull = {}, drawFull = {}, drawPartial = {};
        double tilesFull, tilesPartial;

        Result(Cfg cfg) {
            this.cfg = cfg;
        }

        void collect(List<Sim> sims, long t0, long t1) {
            List<Long> hv = new ArrayList<>(), sc = new ArrayList<>(), st = new ArrayList<>(), op = new ArrayList<>();
            List<Long> qw = new ArrayList<>(), rp = new ArrayList<>(), rf = new ArrayList<>(), df = new ArrayList<>(), dp = new ArrayList<>();
            long tf = 0, tp = 0;
            for (Sim s : sims) {
                synchronized (s.lats) {
                    for (Lat l : s.lats) {
                        if (l.at < t0 || l.at >= t1) continue;
                        if (l.ns > SLOW_NS) System.out.printf("  slow request: session %d kind %d asked at %.0f ms into the window, answered after %.0f ms%n", s.id, l.kind, (l.at - t0) / 1e6, l.ns / 1e6);
                        requests++;
                        switch (l.kind) {
                            case HOVER -> hv.add(l.ns);
                            case SCROLL -> sc.add(l.ns);
                            case STRESS -> st.add(l.ns);
                            default -> op.add(l.ns);
                        }
                    }
                }
                synchronized (s.recs) {
                    for (Rec r : s.recs) {
                        if (r.done < t0 || r.done >= t1) continue;
                        dropped += r.coalesced;
                        qw.add(r.queueNs);
                        if (r.partial) {
                            partialFrames++;
                            rp.add(r.renderNs);
                            dp.add(r.drawNs);
                            tp += r.tiles;
                        } else {
                            fullFrames++;
                            rf.add(r.renderNs);
                            df.add(r.drawNs);
                            tf += r.tiles;
                        }
                    }
                }
            }
            hover = sorted(hv);
            scroll = sorted(sc);
            stressLat = sorted(st);
            openLat = sorted(op);
            qWait = sorted(qw);
            rPartial = sorted(rp);
            rFull = sorted(rf);
            drawFull = sorted(df);
            drawPartial = sorted(dp);
            tilesFull = fullFrames == 0 ? 0 : tf / (double) fullFrames;
            tilesPartial = partialFrames == 0 ? 0 : tp / (double) partialFrames;
        }

        private static long[] sorted(List<Long> l) {
            long[] a = l.stream().mapToLong(Long::longValue).toArray();
            Arrays.sort(a);
            return a;
        }

        static double pct(long[] s, double q) {
            if (s.length == 0) return Double.NaN;
            return s[Math.min(s.length - 1, (int) (q * s.length))] / 1e6;
        }

        private static String m3(long[] s) {
            return s.length == 0 ? "      -        " : String.format("%6.0f/%6.0f/%6.0f", pct(s, .5), pct(s, .9), pct(s, .99));
        }

        private double fps() {
            return (partialFrames + fullFrames) / secs;
        }

        String line(String page) {
            double[] f = Arrays.stream(firstMs).filter(d -> !Double.isNaN(d)).sorted().toArray();
            StringBuilder b = new StringBuilder();
            b.append(String.format("%-8s %-6s N=%-2d R=%d b=%d | hover %s (n=%d)  scroll %s (n=%d)", page.replaceAll(".*:", ""), cfg.mode, cfg.sessions,
                    cfg.renderThreads, cfg.bands, m3(hover), hover.length, m3(scroll), scroll.length));
            if (cfg.mode.equals("stress")) b.append(String.format("  stress-lat %s", m3(stressLat)));
            b.append(String.format(" | drop %d/%d | fr %.1f/s (full %d, part %d) | render full %.0f/%.0f part %.0f  draw full %.0f part %.0f  queue %.0f/%.0f ms",
                    dropped, requests, fps(), fullFrames, partialFrames, pct(rFull, .5), pct(rFull, .9), pct(rPartial, .5), pct(drawFull, .5),
                    pct(drawPartial, .5), pct(qWait, .5), pct(qWait, .9)));
            long fr = Math.max(1, partialFrames + fullFrames);
            b.append(String.format(" | cpu ms/frame render %.0f painters %.0f encoders %.0f total %.0f", threadCpuMs[0] / fr, threadCpuMs[1] / fr, threadCpuMs[2] / fr, processCpuMs / fr));
            b.append(String.format(" alloc %.1f MB/frame", allocMb / fr));
            b.append(String.format(" | cpu %.0f%% other %.0f%% steal %.0f%% | live %.0f MB peak %.0f MB gc %d ms/%d | open %.0f/%.0f ms", cpu, otherCpu, stealPct, liveMb,
                    peakMb, gcMs, gcN, f.length == 0 ? Double.NaN : f[f.length / 2], f.length == 0 ? Double.NaN : f[f.length - 1]));
            if (cfg.mode.equals("stress")) b.append(String.format(" | tiles/frame full %.0f", tilesFull));
            else b.append(String.format(" | why-full first/doc/scroll/nop/sib/nodirty/big/grew/moved %s", Arrays.toString(why)));
            if (wire != null && wire.batches() > 0) {
                b.append(String.format(" | wire %.1f batches/s, %.1f tiles %.1f patches, raw %.0f KB defl %.0f KB per batch, deflate %.1f ms diff %.1f ms, sender cpu %.0f%%, %.2f MB/s defl",
                        wire.batches() / secs, wire.tiles() / (double) wire.batches(), wire.patches() / (double) wire.batches(),
                        wire.rawBytes() / 1024.0 / wire.batches(), wire.deflatedBytes() / 1024.0 / wire.batches(), wire.deflateNs() / 1e6 / wire.batches(),
                        wire.diffNs() / 1e6 / wire.batches(), senderCpu, wire.deflatedBytes() / 1048576.0 / secs));
            }
            return b.toString();
        }

        static String header() {
            return String.join(",", "page", "mode", "tag", "send", "bands", "sessions", "render", "secs", "hover_n", "hover_p50", "hover_p90", "hover_p99", "scroll_n",
                    "scroll_p50", "scroll_p90", "scroll_p99", "stress_n", "stress_p50", "stress_p90", "stress_p99", "requests", "dropped", "full_frames", "partial_frames",
                    "fps", "render_full_p50", "render_full_p90", "render_part_p50", "render_part_p90", "draw_full_p50", "draw_full_p90", "draw_part_p50", "queue_p50",
                    "queue_p90", "tiles_full", "tiles_part", "cpu", "other_cpu", "steal", "live_mb", "peak_mb", "gc_ms", "gc_n", "open_p50", "open_max",
                    "w_batches", "w_tiles", "w_patches", "w_raw", "w_defl", "w_full", "w_deflate_ms", "w_diff_ms", "sender_cpu", "cpu_render_ms", "cpu_painters_ms", "cpu_encoders_ms", "cpu_send_ms", "cpu_server_ms", "cpu_process_ms", "alloc_mb");
        }

        String csv(String page) {
            double[] f = Arrays.stream(firstMs).filter(d -> !Double.isNaN(d)).sorted().toArray();
            Sender.Totals w = wire == null ? new Sender.Totals(0, 0, 0, 0, 0, 0, 0, 0) : wire;
            List<String> v = new ArrayList<>();
            v.add(page);
            v.add(cfg.mode);
            v.add(cfg.tag);
            v.add(String.valueOf(cfg.send));
            v.add(String.valueOf(cfg.bands));
            v.add(String.valueOf(cfg.sessions));
            v.add(String.valueOf(cfg.renderThreads));
            v.add(f(secs));
            for (long[] s : new long[][]{hover, scroll, stressLat}) {
                v.add(String.valueOf(s.length));
                v.add(f(pct(s, .5)));
                v.add(f(pct(s, .9)));
                v.add(f(pct(s, .99)));
            }
            v.add(String.valueOf(requests));
            v.add(String.valueOf(dropped));
            v.add(String.valueOf(fullFrames));
            v.add(String.valueOf(partialFrames));
            v.add(f(fps()));
            v.add(f(pct(rFull, .5)));
            v.add(f(pct(rFull, .9)));
            v.add(f(pct(rPartial, .5)));
            v.add(f(pct(rPartial, .9)));
            v.add(f(pct(drawFull, .5)));
            v.add(f(pct(drawFull, .9)));
            v.add(f(pct(drawPartial, .5)));
            v.add(f(pct(qWait, .5)));
            v.add(f(pct(qWait, .9)));
            v.add(f(tilesFull));
            v.add(f(tilesPartial));
            v.add(f(cpu));
            v.add(f(otherCpu));
            v.add(f(stealPct));
            v.add(f(liveMb));
            v.add(f(peakMb));
            v.add(String.valueOf(gcMs));
            v.add(String.valueOf(gcN));
            v.add(f(f.length == 0 ? Double.NaN : f[f.length / 2]));
            v.add(f(f.length == 0 ? Double.NaN : f[f.length - 1]));
            v.add(String.valueOf(w.batches()));
            v.add(String.valueOf(w.tiles()));
            v.add(String.valueOf(w.patches()));
            v.add(String.valueOf(w.rawBytes()));
            v.add(String.valueOf(w.deflatedBytes()));
            v.add(String.valueOf(w.fullTileBytes()));
            v.add(f(w.deflateNs() / 1e6));
            v.add(f(w.diffNs() / 1e6));
            v.add(f(senderCpu));
            for (double d : threadCpuMs) v.add(f(d));
            v.add(f(processCpuMs));
            v.add(f(allocMb));
            return String.join(",", v);
        }

        private static String f(double d) {
            return Double.isNaN(d) ? "" : String.format(Locale.ROOT, "%.2f", d);
        }
    }
}
