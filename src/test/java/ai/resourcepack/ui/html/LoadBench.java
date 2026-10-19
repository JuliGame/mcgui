package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.paint.Painter;
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
    /** -Dverify=true: every hover taken from what was drawn ahead is compared with the repaint itself (costs a repaint each). */
    static final boolean VERIFY = Boolean.getBoolean("verify");
    private static final java.lang.management.ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();

    // ------------------------------------------------------------------------------------ run

    public static void main(String[] args) throws Exception {
        List<String> pageSpecs = list("pages", "menu,landing,shop");
        List<Integer> sessions = ints("sessions", "1,2,4,8,16,32");
        List<Integer> threads = ints("render", "1,2,4");
        List<Integer> bands = ints("bands", "4");
        List<String> modes = list("modes", "walk");
        // Hover targets drawn ahead per session (HoverAhead; 0 for none), and repetitions of the whole matrix.
        List<Integer> aheads = ints("ahead", "0");
        int reps = Integer.getInteger("reps", 1);
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
            if (!Boolean.getBoolean("nowarmcell")) run(prep, new Cfg("walk", 2, 2, 4, 3, 1, false, "", aheads.get(aheads.size() - 1)), palette);
            for (int rep = 0; rep < reps; rep++) {
                for (String mode : modes) {
                    for (int b : bands) {
                        for (int n : sessions) {
                            for (int r : threads) {
                                // A/B of drawing hovers ahead, interleaved cell by cell.
                                for (int a : aheads) {
                                    Result res = run(prep, new Cfg(mode, n, r, b, secs, warm, send, tag, a), palette);
                                    System.out.println(res.line(spec));
                                    if (!out.isEmpty()) Files.writeString(Path.of(out), res.csv(spec) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                                }
                            }
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

    record Cfg(String mode, int sessions, int renderThreads, int bands, double secs, double warm, boolean send, String tag, int ahead) {}

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
                targets.add(new int[]{(int) Math.round(cx * SCALE), (int) Math.round(cy * SCALE), (int) Math.round(a.getWidth() * SCALE), (int) Math.round(a.getHeight() * SCALE)});
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
            Sim s = new Sim(i, prep, new Canvas(palette, W / 128, H / 128, sender == null ? () -> {} : sender::wake), renderers, server, cfg.mode, cfg.ahead);
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
        long[] ah0 = aheadTotals(sims);
        long t0 = System.nanoTime();
        long tEnd = t0 + (long) (cfg.secs * 1e9);
        sleepUntil(tEnd);
        long cpu1 = OS.getProcessCpuTime();
        long[] th1 = threadCpu();
        long[] sys1 = procStat();
        long t1 = System.nanoTime();
        long[] ah1 = aheadTotals(sims);
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
        for (int i = 0; i < ah1.length; i++) r.ahead[i] = ah1[i] - ah0[i];
        r.firstMs = sims.stream().mapToDouble(s -> s.firstFrameNs > 0 ? (s.firstFrameNs - open) / 1e6 : Double.NaN).toArray();
        r.secs = wall;
        sims.clear();
        return r;
    }

    /** Drawing ahead, summed over the sessions: prepared, abandoned, taken, cpu ns, cpu ns of those taken, bytes allocated, hover frames from it, hover frames painted, verify mismatches. */
    private static long[] aheadTotals(List<Sim> sims) {
        long[] t = new long[12];
        for (Sim s : sims) {
            HoverAhead a = s.ahead;
            t[0] += a.prepared.get();
            t[1] += a.abandoned.get();
            t[2] += a.taken.get();
            t[3] += a.cpuNs.get();
            t[4] += a.usedNs.get();
            t[5] += a.allocBytes.get();
            t[6] += s.aheadFrames.get();
            t[7] += s.paintedFrames.get();
            t[8] += s.mismatches.get();
            t[9] += a.missNoJob.get();
            t[10] += a.missNotPicked.get();
            t[11] += a.missUnfinished.get();
        }
        return t;
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
    /** [cpuNs]: the render thread's CPU for the render part (not the draw); [ahead]: taken from what was drawn ahead. */
    private record Rec(long done, long queueNs, long renderNs, long drawNs, boolean partial, int tiles, int coalesced, long cpuNs, boolean ahead) {}

    /** [ahead]: answered by a frame taken from what was drawn ahead. */
    private record Lat(long at, int kind, long ns, boolean ahead) {}

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
        // Drawing hovers ahead, as Session does (HoverAhead).
        final HoverAhead ahead;
        private HoverAhead.Job job;
        private long pointerAt;
        private double pointerVx, pointerVy;
        /** Hover frames taken from what was drawn ahead, and hover frames repainted; verify: taken ones that differed from a repaint. */
        final AtomicInteger aheadFrames = new AtomicInteger(), paintedFrames = new AtomicInteger(), mismatches = new AtomicInteger();

        Sim(int id, Prep prep, Canvas canvas, ExecutorService renderers, ScheduledThreadPoolExecutor server, String mode, int ahead) {
            this.id = id;
            this.prep = prep;
            this.page = prep.page;
            this.canvas = canvas;
            this.renderers = renderers;
            this.server = server;
            this.mode = mode;
            this.rnd = new Random(1000 + id);
            this.ahead = new HoverAhead(ahead, 4L * W * H);
            for (int t = 0; t < TILES; t++) lastTiles[t] = canvas.tile(t, Canvas.Encoding.HI);
        }

        void close() {
            synchronized (this) {
                closed = true;
                if (job != null) job.cancel();
            }
            ahead.drop();
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
                if (job != null) job.wanted(job.of(page, doc, scrollY), key(hover), List.of());
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
            HoverAhead.makeRoom();
            renderers.execute(() -> {
                try {
                    long t0 = System.nanoTime(), c0 = THREAD_MX.getCurrentThreadCpuTime();
                    HtmlRenderer.Frame before = frame;
                    HtmlRenderer.Frame f = null;
                    Rectangle2D changed = null;
                    boolean partial = false, wasAhead = false;
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
                            for (Req r : mine) lats.add(new Lat(r.t, r.kind, now - r.t, false));
                            return;
                        }
                        Repaint.Prepared ready = ahead.take(before, drawnHover, List.of(), hoverKey, List.of());
                        if (ready != null && VERIFY) verify(ready, p, d, s, state, before, moved);
                        Repaint.Result r = ready != null ? ready.apply() : Repaint.partial(p, d, W, H, SCALE, s, state, before, moved, HtmlRenderer.DEFAULT_TIMEOUT_MS);
                        (ready != null ? aheadFrames : paintedFrames).incrementAndGet();
                        wasAhead = ready != null;
                        if (r.frame() != null) {
                            f = r.frame();
                            changed = r.changed();
                            partial = true;
                        } else {
                            why(4 + r.why().ordinal());
                        }
                    }
                    if (f == null) f = HtmlRenderer.render(p, d, W, H, SCALE, s, state);
                    long t1 = System.nanoTime(), c1 = THREAD_MX.getCurrentThreadCpuTime();
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
                    ahead.drop();
                    if (firstFrameNs == 0) firstFrameNs = t2;
                    recs.add(new Rec(t2, t0 - submitted, t1 - t0, t2 - t1, partial, tiles, mine.size() - 1, c1 - c0, wasAhead));
                    for (Req r : mine) lats.add(new Lat(r.t, r.kind, t2 - r.t, wasAhead));
                    if (!partial) execute(this::repoint);
                } catch (RuntimeException | Error e) {
                    System.err.println("render failed: " + e);
                    e.printStackTrace();
                    ahead.drop();
                } finally {
                    done();
                }
            });
        }

        /** Session.done: this session's turn to draw is over. */
        private void done() {
            synchronized (Sim.this) {
                job = null;
                if (dirty && !closed) {
                    dirty = false;
                    execute(() -> {
                        synchronized (Sim.this) {
                            if (!closed) submit();
                            else rendering = false;
                        }
                    });
                    return;
                }
                rendering = false;
                if (mode.equals("stress") && !closed) execute(Sim.this::advance);
            }
            if (ahead.on() && pointerX >= 0) execute(this::lookAhead);
        }

        /** Session.lookAhead. */
        private synchronized void lookAhead() {
            if (closed || rendering || !ahead.on() || doc == null || pointerX < 0 || !page.styles.hovers() || page.siblingStates() || !idle()) return;
            HtmlRenderer.Frame f = frame;
            if (f == null || f.page() != page || f.document() != doc) return;
            if (Math.abs(f.scrollY() - Math.max(0, Math.min(scrollY, f.maxScroll()))) >= 0.01) return;
            double x = pointerX / SCALE, y = pointerY / SCALE;
            if (ahead.done(f, x, y)) return;
            List<Element> hoverKey = key(hover);
            if (!hoverKey.equals(drawnHover)) return;
            boolean resting = System.nanoTime() - pointerAt > 150_000_000L;
            HoverAhead.Job j = ahead.job(page, doc, W, H, SCALE, f, false, drawnHover, List.of(), hoverKey, null, List.of(), x, y,
                    resting ? 0 : pointerVx / SCALE, resting ? 0 : pointerVy / SCALE, HtmlRenderer.DEFAULT_TIMEOUT_MS);
            job = j;
            rendering = true;
            renderers.execute(() -> {
                try {
                    ahead.run(j);
                } catch (RuntimeException | Error e) {
                    ahead.drop();
                    e.printStackTrace();
                } finally {
                    done();
                }
            });
        }

        private boolean idle() {
            return renderers instanceof java.util.concurrent.ThreadPoolExecutor pool && pool.getActiveCount() < pool.getMaximumPoolSize()
                    && pool.getQueue().isEmpty() && HoverAhead.quiet();
        }

        /** -Dverify=true: what was drawn ahead against the repaint itself, over a copy of the picture. */
        private void verify(Repaint.Prepared ready, Page p, Document d, double s, Selector.State state, HtmlRenderer.Frame before, Set<Element> moved) {
            java.awt.image.BufferedImage from = before.image(), to = new java.awt.image.BufferedImage(from.getWidth(), from.getHeight(), from.getType());
            int[] a = ((java.awt.image.DataBufferInt) from.getRaster().getDataBuffer()).getData(), b = ((java.awt.image.DataBufferInt) to.getRaster().getDataBuffer()).getData();
            System.arraycopy(a, 0, b, 0, a.length);
            HtmlRenderer.Frame copy = new HtmlRenderer.Frame(to, before.scale(), before.viewportWidth(), before.viewportHeight(), before.pageHeight(), before.scrollY(),
                    before.hits(), before.styles(), before.document(), before.page());
            Repaint.Result real = Repaint.partial(p, d, W, H, SCALE, s, state, copy, moved, 0);
            // Applied to the copy of the picture as it is now, so the picture itself is left for the session to apply it to.
            int[] mine = a.clone();
            Repaint.Result r = ready.apply();
            int[] applied = ((java.awt.image.DataBufferInt) r.frame().image().getRaster().getDataBuffer()).getData();
            boolean same = real.frame() != null && java.util.Arrays.equals(applied, b) && real.changed().equals(r.changed());
            System.arraycopy(mine, 0, applied, 0, mine.length);
            if (!same) {
                mismatches.incrementAndGet();
                System.err.println("verify: drawn ahead differs from the repaint (session " + id + ")");
            }
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
            long now = System.nanoTime();
            if (pointerX >= 0 && now - pointerAt < 250_000_000L && now > pointerAt) {
                double dt = (now - pointerAt) / 1e9;
                pointerVx = (x - pointerX) / dt;
                pointerVy = (y - pointerY) / dt;
            } else {
                pointerVx = pointerVy = 0;
            }
            pointerAt = now;
            pointerX = x;
            pointerY = y;
            Element e = counterpart(at(x, y), doc);
            boolean differs = e != hover && !key(hover).equals(key(e));
            hover = e;
            if (differs) render(HOVER);
            else lookAhead();
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
            if (mode.equals("hand")) {
                handTick(ts);
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

        // ---- hand: a pointer moved the way a hand moves one
        private int handAt = -1, moveTick, moveTicks;
        private double fromX, fromY, toX, toY, viaX, viaY;

        /**
         * One 20 Hz sample of a hand: it rests on a target 0.3–1.5 s (the client reports nothing while the view is still,
         * now and then a pixel of drift), then moves to the next, usually one of the three nearest (60%), else any. A move
         * takes as long as Fitts' law says (0.15 s + 0.12 s per bit of distance over size), along a slightly bent path,
         * fast in the middle and slow at both ends (minimum jerk), and ends somewhere inside the target, not at its centre.
         */
        void handTick(List<int[]> ts) {
            if (handAt < 0) {
                handAt = rnd.nextInt(ts.size());
                int[] t = ts.get(handAt);
                px = t[0];
                py = t[1];
                pointer((int) px, (int) py);
                dwell = 6 + rnd.nextInt(25);
                return;
            }
            if (dwell > 0) {
                dwell--;
                if (rnd.nextInt(10) < 3) pointer((int) px + rnd.nextInt(3) - 1, (int) py + rnd.nextInt(3) - 1);
                if (dwell == 0) startMove(ts);
                return;
            }
            moveTick++;
            double tau = Math.min(1, moveTick / (double) moveTicks), sm = tau * tau * tau * (10 - 15 * tau + 6 * tau * tau);
            // A quadratic Bezier through the bend point.
            px = (1 - sm) * (1 - sm) * fromX + 2 * (1 - sm) * sm * viaX + sm * sm * toX;
            py = (1 - sm) * (1 - sm) * fromY + 2 * (1 - sm) * sm * viaY + sm * sm * toY;
            pointer((int) Math.round(px), (int) Math.round(py));
            if (tau >= 1) dwell = 6 + rnd.nextInt(25);
        }

        private void startMove(List<int[]> ts) {
            int next;
            if (ts.size() > 1 && rnd.nextInt(10) < 6) {
                // One of the three nearest others.
                int[] at = ts.get(handAt);
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < ts.size(); i++) if (i != handAt) order.add(i);
                order.sort((a, b) -> Double.compare(Math.hypot(ts.get(a)[0] - at[0], ts.get(a)[1] - at[1]), Math.hypot(ts.get(b)[0] - at[0], ts.get(b)[1] - at[1])));
                next = order.get(rnd.nextInt(Math.min(3, order.size())));
            } else {
                next = ts.size() > 1 ? (handAt + 1 + rnd.nextInt(ts.size() - 1)) % ts.size() : handAt;
            }
            int[] t = ts.get(next);
            handAt = next;
            fromX = px;
            fromY = py;
            toX = t[0] + (rnd.nextDouble() - 0.5) * 0.6 * t[2];
            toY = t[1] + (rnd.nextDouble() - 0.5) * 0.6 * t[3];
            double dx = toX - fromX, dy = toY - fromY, dist = Math.hypot(dx, dy), size = Math.max(16, Math.min(t[2], t[3]));
            double bend = (rnd.nextDouble() - 0.5) * 0.3;
            viaX = (fromX + toX) / 2 - dy * bend;
            viaY = (fromY + toY) / 2 + dx * bend;
            double secs = 0.15 + 0.12 * Math.log(dist / size + 1) / Math.log(2);
            moveTicks = Math.max(1, (int) Math.round(secs * 20));
            moveTick = 0;
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

    /** Why a repaint was not partial: 0 first picture, 1 other document or page, 2 scrolled, 3 nothing changed, then Repaint.Why from PARTIAL (4, never counted): 5 sibling states, 6 no dirty region, 7 region too big, 8 grew too big, 9 layout moved. */
    static final AtomicInteger[] WHY = new AtomicInteger[4 + Repaint.Why.values().length];
    static {
        for (int i = 0; i < WHY.length; i++) WHY[i] = new AtomicInteger();
    }

    static void why(int i) {
        WHY[i].incrementAndGet();
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
        int[] why = new int[4 + Repaint.Why.values().length];
        /** CPU ms in the window: render threads, painters, encoders, sender, server thread; and the whole process. */
        final double[] threadCpuMs = new double[5];
        double processCpuMs, allocMb;
        long requests, dropped, partialFrames, fullFrames, noops;
        long[] qWait = {}, rPartial = {}, rFull = {}, drawFull = {}, drawPartial = {};
        /** Hover latency of requests answered from what was drawn ahead, and of the others; and aheadTotals over the window. */
        long[] hoverAhead = {}, hoverPainted = {};
        /** Render-thread CPU of partial frames taken from what was drawn ahead, and of those repainted. */
        long[] cpuAhead = {}, cpuPainted = {};
        final long[] ahead = new long[12];
        double tilesFull, tilesPartial;

        Result(Cfg cfg) {
            this.cfg = cfg;
        }

        void collect(List<Sim> sims, long t0, long t1) {
            List<Long> hv = new ArrayList<>(), sc = new ArrayList<>(), st = new ArrayList<>(), op = new ArrayList<>(), ha = new ArrayList<>(), hp = new ArrayList<>();
            List<Long> ca = new ArrayList<>(), cp = new ArrayList<>();
            List<Long> qw = new ArrayList<>(), rp = new ArrayList<>(), rf = new ArrayList<>(), df = new ArrayList<>(), dp = new ArrayList<>();
            long tf = 0, tp = 0;
            for (Sim s : sims) {
                synchronized (s.lats) {
                    for (Lat l : s.lats) {
                        if (l.at < t0 || l.at >= t1) continue;
                        if (l.ns > SLOW_NS) System.out.printf("  slow request: session %d kind %d asked at %.0f ms into the window, answered after %.0f ms%n", s.id, l.kind, (l.at - t0) / 1e6, l.ns / 1e6);
                        requests++;
                        switch (l.kind) {
                            case HOVER -> {
                                hv.add(l.ns);
                                (l.ahead ? ha : hp).add(l.ns);
                            }
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
                            (r.ahead ? ca : cp).add(r.cpuNs);
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
            hoverAhead = sorted(ha);
            cpuAhead = sorted(ca);
            cpuPainted = sorted(cp);
            hoverPainted = sorted(hp);
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
            if (cfg.ahead == 0) b.append(String.format(" | render cpu/partial frame %.1f ms", pct(cpuPainted, .5)));
            if (cfg.ahead > 0) {
                long hoverFrames = ahead[6] + ahead[7];
                b.append(String.format(" | ahead=%d: hover frames %d/%d drawn ahead (%.0f%%), hover from ahead %s (n=%d) painted %s (n=%d); render cpu/partial frame from ahead %.1f painted %.1f ms; prepared %d taken %d stopped %d; missed: no drawing ahead %d, not picked %d, not reached %d;"
                                + " cpu ahead %.0f ms (used %.0f, wasted %.0f = %.1f%% of render cpu), alloc ahead %.0f MB%s",
                        cfg.ahead, ahead[6], hoverFrames, 100.0 * ahead[6] / Math.max(1, hoverFrames), m3(hoverAhead), hoverAhead.length, m3(hoverPainted), hoverPainted.length,
                        pct(cpuAhead, .5), pct(cpuPainted, .5), ahead[0], ahead[2], ahead[1], ahead[9], ahead[10], ahead[11], ahead[3] / 1e6, ahead[4] / 1e6, (ahead[3] - ahead[4]) / 1e6, 100 * (ahead[3] - ahead[4]) / 1e6 / Math.max(1, threadCpuMs[0]),
                        ahead[5] / 1048576.0, VERIFY ? String.format(", verify mismatches %d", ahead[8]) : ""));
            }
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
                    "w_batches", "w_tiles", "w_patches", "w_raw", "w_defl", "w_full", "w_deflate_ms", "w_diff_ms", "sender_cpu", "cpu_render_ms", "cpu_painters_ms", "cpu_encoders_ms", "cpu_send_ms", "cpu_server_ms", "cpu_process_ms", "alloc_mb",
                    "ahead", "ah_prepared", "ah_stopped", "ah_taken", "ah_cpu_ms", "ah_used_ms", "ah_alloc_mb", "ah_frames", "painted_frames", "ah_mismatch",
                    "hover_ahead_n", "hover_ahead_p50", "hover_ahead_p90", "hover_painted_n", "hover_painted_p50", "hover_painted_p90", "gc_live_after", "cpu_part_ahead_p50", "cpu_part_painted_p50", "cpu_part_painted_p90", "miss_nojob", "miss_notpicked", "miss_unfinished");
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
            v.add(String.valueOf(cfg.ahead));
            v.add(String.valueOf(ahead[0]));
            v.add(String.valueOf(ahead[1]));
            v.add(String.valueOf(ahead[2]));
            v.add(f(ahead[3] / 1e6));
            v.add(f(ahead[4] / 1e6));
            v.add(f(ahead[5] / 1048576.0));
            v.add(String.valueOf(ahead[6]));
            v.add(String.valueOf(ahead[7]));
            v.add(String.valueOf(ahead[8]));
            for (long[] h : new long[][]{hoverAhead, hoverPainted}) {
                v.add(String.valueOf(h.length));
                v.add(f(pct(h, .5)));
                v.add(f(pct(h, .9)));
            }
            v.add(f(liveMb));
            v.add(f(pct(cpuAhead, .5)));
            v.add(f(pct(cpuPainted, .5)));
            v.add(f(pct(cpuPainted, .9)));
            v.add(String.valueOf(ahead[9]));
            v.add(String.valueOf(ahead[10]));
            v.add(String.valueOf(ahead[11]));
            return String.join(",", v);
        }

        private static String f(double d) {
            return Double.isNaN(d) ? "" : String.format(Locale.ROOT, "%.2f", d);
        }
    }
}
