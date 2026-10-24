package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.api.HoverLayers;
import ai.resourcepack.ui.html.HoverAhead;
import ai.resourcepack.ui.html.HtmlRenderer;
import org.jsoup.nodes.Element;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The server's side of the PC hover trial (ih_pc.py), on real {@link Session}s with real render threads: the pointer
 * rests 0.7 to 1 s (wall time, not until quiet) beside a target, then glides onto its centre as the client reports it
 * (every 50 ms), then back. Per trial: ms from the first report inside the target to the hovered picture published
 * (0 when the client shows it by itself, a layer), whether it was drawn ahead, and what arming cost during the rest.
 * Hover layers off and on, interleaved by trial.
 *
 * <p>System properties: dir (pages), targets (targets.json of the PC rig), pages (default market,quests,crates),
 * trials (per page per setting, default 16), layers (default 32), threads (render threads, default 4). With pixels,
 * a hover counts as shown once what the client would show is a fresh picture of it, pixel for pixel (the PC rig's
 * measure): the picture with every armed layer the pointer is in laid over it, the higher priority in front; and each
 * trial prints the share of the hover's changed pixels the client shows the moment the pointer gets there.
 */
public final class HoverTimeline {
    private static final int COLS = 16, ROWS = 9;
    private static final boolean PIXELS = Boolean.getBoolean("pixels");

    public static void main(String[] args) throws Exception {
        Path from = Path.of(System.getProperty("dir", "src/main/resources/pages"));
        String[] pages = System.getProperty("pages", "market,quests,crates").split(",");
        int blocks = Integer.getInteger("blocks", 4), per = Integer.getInteger("per", 8), warm = Integer.getInteger("warm", 4);
        int capacity = Integer.getInteger("layers", 32), threads = Integer.getInteger("threads", 4);
        String only = System.getProperty("only", "");
        String json = Files.readString(Path.of(System.getProperty("targets", "build/hover-targets.json")));
        Path dir = Files.createTempDirectory("hover-timeline");
        copy(from, dir);
        for (String page : pages) {
            List<int[][]> ts = targets(json, page);
            if (!only.isEmpty()) ts.removeIf(t -> !java.util.Arrays.toString(t[0]).equals(only));
            List<double[]> off = new ArrayList<>(), on = new ArrayList<>();
            Random rnd = new Random(7);
            for (int b = 0; b < blocks; b++) {
                for (boolean layers : b % 2 == 0 ? new boolean[]{false, true} : new boolean[]{true, false}) {
                    long seed = rnd.nextLong();
                    for (double[] r : block(dir, page, ts, layers, capacity, threads, warm, per, seed)) {
                        (layers ? on : off).add(r);
                        System.out.printf("%-7s %-3s box %-20s enter %7.1f ms ahead %s busy %s  leave %7.1f ms ahead %s  arm %6.1f ms cpu (%d armed, %d used)%s%n",
                                page, layers ? "on" : "off", java.util.Arrays.toString(ts.get((int) r[7])[0]), r[0], r[1] > 0 ? "y" : "n",
                                r[8] > 0 ? "y" : "n", r[2], r[3] > 0 ? "y" : "n", r[4], (int) r[5], (int) r[6],
                                PIXELS ? String.format("  at once %5.1f%% / %5.1f%%", 100 * r[10], 100 * r[11]) : "");
                    }
                }
            }
            for (String tag : new String[]{"off", "on"}) {
                List<double[]> rs = tag.equals("off") ? off : on;
                System.out.printf("SUMMARY %-7s %-3s enter p50 %6.1f p90 %6.1f ahead %d/%d busy %d   leave p50 %6.1f p90 %6.1f ahead %d/%d   arm cpu mean %.1f ms"
                                + "   server cpu %.1f ms a trial%s%n",
                        page, tag, q(rs, 0, 0.5), q(rs, 0, 0.9), (int) rs.stream().filter(r -> r[1] > 0).count(), rs.size(),
                        (int) rs.stream().filter(r -> r[8] > 0).count(),
                        q(rs, 2, 0.5), q(rs, 2, 0.9), (int) rs.stream().filter(r -> r[3] > 0).count(), rs.size(),
                        rs.stream().mapToDouble(r -> r[4]).average().orElse(0), rs.stream().mapToDouble(r -> r[9]).average().orElse(0),
                        PIXELS ? String.format("   whole at once %d/%d enter, %d/%d leave", rs.stream().filter(r -> r[10] >= 1).count(), rs.size(),
                                rs.stream().filter(r -> r[11] >= 1).count(), rs.size()) : "");
            }
        }
        System.exit(0);
    }

    private static double q(List<double[]> rs, int k, double p) {
        double[] v = rs.stream().mapToDouble(r -> r[k]).filter(x -> x >= 0).sorted().toArray();
        return v.length == 0 ? -1 : v[Math.min(v.length - 1, (int) (p * v.length))];
    }

    /** CPU (ns) every thread but this one has used: the session's server thread, render threads and ticks, not the rig's waiting. */
    private static long othersCpu() {
        var mx = java.lang.management.ManagementFactory.getThreadMXBean();
        long me = Thread.currentThread().threadId(), sum = 0;
        for (long id : mx.getAllThreadIds()) {
            if (id == me) continue;
            long t = mx.getThreadCpuTime(id);
            if (t > 0) sum += t;
        }
        return sum;
    }

    /**
     * One session, as the PC rig keeps one page open: [warm] untimed trials, then [per] timed ones, on the targets of [ts]
     * in turn from one picked at random (as ih_pc.py goes). Per trial {enter ms, ahead, leave ms, ahead, arm ms, armed,
     * used, target, busy at entry}.
     */
    private static List<double[]> block(Path dir, String page, List<int[][]> ts, boolean layersOn, int capacity, int threads, int warm, int per,
                                        long seed) throws Exception {
        List<double[]> out = new ArrayList<>();
        try (SessionRig rig = new SessionRig(dir, threads)) {
            if (rig.pages.load(page).get() == null) throw new IllegalStateException("no page " + page);
            Session s = rig.open(rig.plugin(4), page, COLS, ROWS, 576);
            rig.quiet();
            Canvas layerCanvas = new Canvas(SessionRig.PALETTE, capacity, 1);
            HoverLayers layers = layersOn ? layerSet(layerCanvas, capacity) : null;
            Seen seen = PIXELS ? new Seen(layers, rig.pages.loaded(page), s) : null;
            if (layersOn) rig.onServer(() -> s.layers(seen != null ? seen : layers));
            Random rnd = new Random(seed);
            rig.onServer(() -> s.pointer(6, 1146));
            rig.quiet();
            // The server's ticks, as a session gets them (Screens.tick): what it does at rest happens on them.
            var ticker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            long[] tick = {0};
            ticker.scheduleAtFixedRate(() -> rig.onServer(() -> s.tick(++tick[0])), 50, 50, java.util.concurrent.TimeUnit.MILLISECONDS);
            int start = rnd.nextInt(ts.size());
            for (int i = 0; i < warm + per; i++) {
                int k = (start + i) % ts.size();
                long cpu0 = othersCpu();
                int[][] t = ts.get(k);
                ClientHovers h = layersOn ? get(s, "hovers") : null;
                long arm0 = h == null ? 0 : h.armNs;
                int[] box = t[0], r = t[1], c = t[2];
                rig.onServer(() -> s.pointer(r[0], r[1]));
                Thread.sleep((long) (700 + 300 * rnd.nextDouble()));
                double arm = h == null ? 0 : (h.armNs - arm0) / 1e6;
                int armed = h == null ? 0 : h.count(), used = layers == null ? 0 : layers.used();
                if (Boolean.getBoolean("restdebug")) {
                    HtmlRenderer.Frame f = get(s, "frame");
                    HoverAhead ha = get(s, "hoverAhead");
                    List<Element> dh = get(s, "drawnHover"), dp = get(s, "drawnPress"), restKey = keyAt(s, r[0], r[1]), want = keyAt(s, c[0], c[1]);
                    boolean ready = ha.ready(f, dh, dp, dp, x -> true).stream().anyMatch(x -> x.hoverKey().equals(want));
                    System.out.printf("REST %s drawn %d rest %d restArmed %s done %s ready %s rendering %s%n", java.util.Arrays.toString(box),
                            dh.size(), restKey.size(), h != null && h.armed(f, restKey), ha.done(f, r[0] / f.scale(), r[1] / f.scale()), ready,
                            get(s, "rendering"));
                }
                double[] in = glide(rig, s, r, c, box, true, seen);
                Thread.sleep(500);
                double[] o = glide(rig, s, c, r, box, false, seen);
                // As ih_pc.py: half a second before the next look, so the jump to the next rest is not a report's move.
                Thread.sleep(500);
                if (i >= warm) out.add(new double[]{in[0], in[1], o[0], o[1], arm, armed, used, k, in[2], (othersCpu() - cpu0) / 1e6, in[3], o[3]});
            }
            ticker.shutdownNow();
            rig.quiet();
        }
        return out;
    }

    /**
     * Reports the pointer along a to b as the client does, every 50 ms over a 150 ms glide; from the first report on the
     * other side of [box]'s edge: ms until the session's picture is of the hover there, 0 if the client shows it by itself.
     * With [seen] (pixels): until what the client shows is a fresh picture of it, and the share of its changed pixels the
     * client showed at once.
     */
    private static double[] glide(SessionRig rig, Session s, int[] a, int[] b, int[] box, boolean entering, Seen seen) throws Exception {
        HoverAhead ha = get(s, "hoverAhead");
        long taken0 = ha.taken.get();
        long t0 = -1, shownAt = -1;
        boolean busy = false;
        double atOnce = 1;
        int px = -1, py = -1;
        List<Element> want = null;
        for (int i = 1; i <= 3; i++) {
            int x = a[0] + (b[0] - a[0]) * i / 3, y = a[1] + (b[1] - a[1]) * i / 3;
            boolean inside = x >= box[0] && y >= box[1] && x < box[0] + box[2] && y < box[1] + box[3];
            if (t0 < 0 && inside == entering) {
                HtmlRenderer.Frame f = get(s, "frame");
                List<Element> key = keyAt(s, x, y);
                want = key;
                ClientHovers h = get(s, "hovers");
                List<Element> drawn = get(s, "drawnHover");
                boolean layered = h != null && h.armed(f, key);
                // A render thread of this session's at work as the pointer gets there (drawing ahead, or arming).
                busy = get(s, "rendering");
                px = x;
                py = y;
                if (seen != null) {
                    // Worked out before the clock starts (once a state): the client's layers show the moment the pointer gets there.
                    seen.fresh(key, x, y);
                    atOnce = seen.atOnce(key, x, y);
                }
                t0 = System.nanoTime();
                if (atOnce >= 1 && seen != null) shownAt = t0;
                rig.onServer(() -> s.pointer(x, y));
                if (seen == null && (layered || key.equals(drawn))) return new double[]{0, 1, busy ? 1 : 0, 1};
            } else {
                if (seen != null && t0 >= 0 && shownAt < 0 && inside == entering) {
                    // Still not shown: from here, what the pointer is over now is what the client should show.
                    want = keyAt(s, x, y);
                    seen.fresh(want, x, y);
                    px = x;
                    py = y;
                }
                rig.onServer(() -> s.pointer(x, y));
            }
            if (i == 3) break;
            // The next report comes 50 ms later, as the client's do, whether or not the hover is drawn by then.
            long until = System.nanoTime() + 50_000_000L;
            while (System.nanoTime() < until) {
                if (want != null && shownAt < 0 && shows(s, want, seen, px, py)) shownAt = System.nanoTime();
                if (seen != null) Thread.sleep(1);
                else Thread.onSpinWait();
            }
        }
        if (want == null) return new double[]{-1, 0, 0, 1};
        long deadline = t0 + 2_000_000_000L;
        boolean dumped = false;
        while (shownAt < 0 && System.nanoTime() < deadline) {
            if (shows(s, want, seen, px, py)) {
                shownAt = System.nanoTime();
                break;
            }
            if (!dumped && Boolean.getBoolean("stacks") && System.nanoTime() - t0 > 400_000_000L) {
                dumped = true;
                System.out.println("STALL rendering=" + get(s, "rendering") + " dirty=" + get(s, "dirty") + " ahead=" + get(s, "ahead")
                        + " drawnHover=" + get(s, "drawnHover") + " want=" + want);
                for (var e : Thread.getAllStackTraces().entrySet()) {
                    StackTraceElement[] st = e.getValue();
                    if (java.util.Arrays.stream(st).noneMatch(x -> x.getClassName().startsWith("ai.resourcepack"))) continue;
                    System.out.println("  thread " + e.getKey().getName());
                    for (int i = 0; i < Math.min(18, st.length); i++) System.out.println("    " + st[i]);
                }
            }
            Thread.sleep(0, 200_000);
        }
        return new double[]{shownAt > 0 ? (shownAt - t0) / 1e6 : -1, ha.taken.get() > taken0 ? 1 : 0, busy ? 1 : 0, atOnce};
    }

    private static boolean shows(Session s, List<Element> key, Seen seen, int x, int y) {
        if (seen != null) return seen.atOnce(key, x, y) >= 1;
        List<Element> drawn = get(s, "drawnHover");
        return drawn.equals(key);
    }

    /**
     * What the client would show: [inner] (the view's layers, or none), remembering each armed layer's pixels and where
     * it shows, laid over the session's picture; and fresh pictures of the page in each hover state, to compare with.
     */
    static final class Seen implements HoverLayers {
        record Pix(int[] argb, int x, int y, int w, int h, HoverLayers.Layer inner) implements HoverLayers.Layer {
            @Override public int maps() { return inner.maps(); }
        }
        record On(int[] include, int[] exclude, int priority, Pix pix) {}

        private final HoverLayers inner;
        private final ai.resourcepack.ui.html.Page page;
        private final Session s;
        private final java.util.Map<Object, On> on = new java.util.HashMap<>();
        private final java.util.Map<List<Element>, int[]> fresh = new java.util.HashMap<>();
        private int[] buf;

        Seen(HoverLayers inner, ai.resourcepack.ui.html.Page page, Session s) {
            this.inner = inner;
            this.page = page;
            this.s = s;
        }

        @Override public int capacity() { return inner == null ? 0 : inner.capacity(); }
        @Override public int used() { return inner.used(); }
        @Override public int mapsFor(int[] argb, int x, int y, int w, int h) { return inner.mapsFor(argb, x, y, w, h); }

        @Override
        public HoverLayers.Layer prepare(int[] argb, int x, int y, int w, int h, java.util.function.BooleanSupplier stop) {
            HoverLayers.Layer l = inner.prepare(argb, x, y, w, h, stop);
            return l == null ? null : new Pix(argb.clone(), x, y, w, h, l);
        }

        @Override
        public synchronized boolean arm(Object key, int[] include, int[] exclude, int priority, HoverLayers.Layer layer) {
            Pix p = (Pix) layer;
            boolean ok = inner.arm(key, include, exclude, priority, p.inner);
            if (ok) on.put(key, new On(include.clone(), exclude == null ? null : exclude.clone(), priority, p));
            else on.remove(key);
            return ok;
        }

        @Override public synchronized boolean armed(Object key) { return inner.armed(key); }
        @Override public synchronized void disarm(Object key) { inner.disarm(key); on.remove(key); }
        @Override public synchronized void clear() { inner.clear(); on.clear(); }

        /** A fresh picture of the page with the pointer at (x, y), whose hover is [key]: once a state. */
        int[] fresh(List<Element> key, int x, int y) {
            int[] f = fresh.get(key);
            if (f != null) return f;
            HtmlRenderer.Frame now = get(s, "frame");
            org.jsoup.nodes.Document doc = get(s, "doc");
            Element e = counterpartAt(s, x, y);
            org.jsoup.nodes.Document d = page.document.clone();
            Element same = e == null ? null : d.getAllElements().get(doc.getAllElements().indexOf(e));
            BufferedImage img = now.image();
            f = pixels(HtmlRenderer.render(page, d, img.getWidth(), img.getHeight(), now.scale(), now.scrollY(), HtmlRenderer.state(same, null)).image());
            fresh.put(key, f);
            return f;
        }

        /**
         * The share of the pixels where the session's picture is not a fresh one of hover [key] that the client shows
         * right, its pointer at (x, y): 1 if it shows a fresh picture.
         */
        double atOnce(List<Element> key, int x, int y) {
            int[] want = fresh(key, x, y);
            HtmlRenderer.Frame f = get(s, "frame");
            int[] base = pixels(f.image());
            if (base.length != want.length) return 0;
            int w = f.image().getWidth();
            if (buf == null || buf.length != base.length) buf = new int[base.length];
            System.arraycopy(base, 0, buf, 0, base.length);
            List<On> shown = new ArrayList<>();
            // Only the list under the lock: the server thread disarms through it as it hears the pointer.
            synchronized (this) {
                for (var en : on.entrySet()) {
                    On l = en.getValue();
                    if (in(l.include, x, y) && (l.exclude == null || !in(l.exclude, x, y)) && inner.armed(en.getKey())) shown.add(l);
                }
            }
            shown.sort(java.util.Comparator.comparingInt(On::priority));
            for (On l : shown) {
                Pix p = l.pix;
                for (int yy = 0; yy < p.h; yy++) {
                    for (int xx = 0; xx < p.w; xx++) {
                        int v = p.argb[yy * p.w + xx];
                        if (v >>> 24 >= 128) buf[(p.y + yy) * w + p.x + xx] = v;
                    }
                }
            }
            int changed = 0, wrong = 0, x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
            for (int i = 0; i < want.length; i++) {
                if (!near(base[i], want[i])) changed++;
                if (!near(buf[i], want[i])) {
                    wrong++;
                    x0 = Math.min(x0, i % w);
                    x1 = Math.max(x1, i % w);
                    y0 = Math.min(y0, i / w);
                    y1 = Math.max(y1, i / w);
                }
            }
            StringBuilder d = new StringBuilder(String.format("changed %d wrong %d in [%d,%d - %d,%d]; showing", changed, wrong, x0, y0, x1, y1));
            for (On l : shown) d.append(String.format(" {prio %d in %s out %s at %d,%d %dx%d}", l.priority, java.util.Arrays.toString(l.include),
                    java.util.Arrays.toString(l.exclude), l.pix.x, l.pix.y, l.pix.w, l.pix.h));
            last = d.toString();
            return wrong == 0 ? 1 : changed == 0 ? 0 : Math.max(0, 1 - (double) wrong / changed);
        }

        /** What the last {@link #atOnce} found: the pixels changed and wrong, and the layers showing. */
        volatile String last = "";

        private static boolean near(int a, int b) {
            if (a == b) return true;
            for (int sh = 0; sh < 32; sh += 8) if (Math.abs((a >> sh & 255) - (b >> sh & 255)) > 2) return false;
            return true;
        }

        private static boolean in(int[] r, int x, int y) {
            return x >= r[0] && y >= r[1] && x < r[2] && y < r[3];
        }

        /** [img]'s pixels, row by row: it may be cut from a taller picture (ScrollCache). */
        private static int[] pixels(BufferedImage img) {
            int w = img.getWidth(), h = img.getHeight();
            java.awt.image.WritableRaster raster = img.getRaster();
            int stride = ((java.awt.image.SinglePixelPackedSampleModel) raster.getSampleModel()).getScanlineStride();
            java.awt.image.DataBufferInt db = (java.awt.image.DataBufferInt) raster.getDataBuffer();
            int offset = db.getOffset() - raster.getSampleModelTranslateY() * stride - raster.getSampleModelTranslateX();
            int[] out = new int[w * h];
            for (int y = 0; y < h; y++) System.arraycopy(db.getData(), offset + y * stride, out, y * w, w);
            return out;
        }
    }

    /** The element of the session's own document the pointer at (x, y) is over (null: none). */
    private static Element counterpartAt(Session s, int x, int y) {
        try {
            var at = Session.class.getDeclaredMethod("at", int.class, int.class);
            var counterpart = Session.class.getDeclaredMethod("counterpart", Element.class, org.jsoup.nodes.Document.class);
            at.setAccessible(true);
            counterpart.setAccessible(true);
            return (Element) counterpart.invoke(null, at.invoke(s, x, y), get(s, "doc"));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The hover key the session gives the pointer at (x, y): its element there, in its own document. */
    @SuppressWarnings("unchecked")
    static List<Element> keyAt(Session s, int x, int y) {
        try {
            var at = Session.class.getDeclaredMethod("at", int.class, int.class);
            var counterpart = Session.class.getDeclaredMethod("counterpart", Element.class, org.jsoup.nodes.Document.class);
            var key = Session.class.getDeclaredMethod("key", Element.class, boolean.class);
            at.setAccessible(true);
            counterpart.setAccessible(true);
            key.setAccessible(true);
            Element e = (Element) counterpart.invoke(null, at.invoke(s, x, y), get(s, "doc"));
            return (List<Element>) key.invoke(s, e, false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<int[][]> targets(String json, String page) {
        // {"market": [{"name": ..., "box": [x, y, w, h], "r": [x, y], "c": [x, y]}, ...], ...}: read without a JSON library.
        int at = json.indexOf("\"" + page + "\"");
        List<int[][]> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{[^{}]*\\}").matcher(json);
        int stop = nextPage(json, at);
        m.region(at, stop);
        while (m.find()) {
            String o = m.group();
            out.add(new int[][]{nums(o, "box"), nums(o, "r"), nums(o, "c")});
        }
        return out;
    }

    private static int nextPage(String json, int at) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\}\\s*\\]").matcher(json);
        return m.find(at) ? m.end() : json.length();
    }

    private static int[] nums(String o, String key) {
        int i = o.indexOf("\"" + key + "\"");
        String list = o.substring(o.indexOf('[', i) + 1, o.indexOf(']', i));
        String[] p = list.split(",");
        int[] out = new int[p.length];
        for (int k = 0; k < p.length; k++) out[k] = (int) Math.round(Double.parseDouble(p[k].trim()));
        return out;
    }

    static void copy(Path from, Path to) throws Exception {
        try (var files = Files.walk(from)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Path d = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) Files.createDirectories(d);
                else Files.copy(f, d);
            }
        }
    }

    static HoverLayers layerSet(Canvas canvas, int capacity) throws Exception {
        Class<?> c = Class.forName("ai.resourcepack.ui.framework.world.LayerSet");
        Constructor<?> k = c.getDeclaredConstructors()[0];
        k.setAccessible(true);
        return (HoverLayers) k.newInstance(SessionRig.PALETTE, canvas, null, COLS, ROWS, 8.0, 0);
    }

    @SuppressWarnings("unchecked")
    static <T> T get(Session s, String name) {
        try {
            Field f = Session.class.getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(s);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
