package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.api.HoverLayers;
import ai.resourcepack.ui.html.HoverAhead;
import ai.resourcepack.ui.html.HtmlRenderer;
import org.jsoup.nodes.Element;

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
 * trials (per page per setting, default 16), layers (default 32), threads (render threads, default 4).
 */
public final class HoverTimeline {
    private static final int COLS = 16, ROWS = 9;

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
                        System.out.printf("%-7s %-3s box %-20s enter %7.1f ms ahead %s busy %s  leave %7.1f ms ahead %s  arm %6.1f ms cpu (%d armed, %d used)%n",
                                page, layers ? "on" : "off", java.util.Arrays.toString(ts.get((int) r[7])[0]), r[0], r[1] > 0 ? "y" : "n",
                                r[8] > 0 ? "y" : "n", r[2], r[3] > 0 ? "y" : "n", r[4], (int) r[5], (int) r[6]);
                    }
                }
            }
            for (String tag : new String[]{"off", "on"}) {
                List<double[]> rs = tag.equals("off") ? off : on;
                System.out.printf("SUMMARY %-7s %-3s enter p50 %6.1f p90 %6.1f ahead %d/%d busy %d   leave p50 %6.1f p90 %6.1f ahead %d/%d   arm cpu mean %.1f ms%n",
                        page, tag, q(rs, 0, 0.5), q(rs, 0, 0.9), (int) rs.stream().filter(r -> r[1] > 0).count(), rs.size(),
                        (int) rs.stream().filter(r -> r[8] > 0).count(),
                        q(rs, 2, 0.5), q(rs, 2, 0.9), (int) rs.stream().filter(r -> r[3] > 0).count(), rs.size(),
                        rs.stream().mapToDouble(r -> r[4]).average().orElse(0));
            }
        }
        System.exit(0);
    }

    private static double q(List<double[]> rs, int k, double p) {
        double[] v = rs.stream().mapToDouble(r -> r[k]).filter(x -> x >= 0).sorted().toArray();
        return v.length == 0 ? -1 : v[Math.min(v.length - 1, (int) (p * v.length))];
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
            if (layersOn) rig.onServer(() -> s.layers(layers));
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
                double[] in = glide(rig, s, r, c, box, true);
                Thread.sleep(500);
                double[] o = glide(rig, s, c, r, box, false);
                // As ih_pc.py: half a second before the next look, so the jump to the next rest is not a report's move.
                Thread.sleep(500);
                if (i >= warm) out.add(new double[]{in[0], in[1], o[0], o[1], arm, armed, used, k, in[2]});
            }
            ticker.shutdownNow();
            rig.quiet();
        }
        return out;
    }

    /**
     * Reports the pointer along a to b as the client does, every 50 ms over a 150 ms glide; from the first report on the
     * other side of [box]'s edge: ms until the session's picture is of the hover there, 0 if the client shows it by itself.
     */
    private static double[] glide(SessionRig rig, Session s, int[] a, int[] b, int[] box, boolean entering) throws Exception {
        HoverAhead ha = get(s, "hoverAhead");
        long taken0 = ha.taken.get();
        long t0 = -1, shownAt = -1;
        boolean busy = false;
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
                t0 = System.nanoTime();
                rig.onServer(() -> s.pointer(x, y));
                if (layered || key.equals(drawn)) return new double[]{0, 1, busy ? 1 : 0};
            } else {
                rig.onServer(() -> s.pointer(x, y));
            }
            if (i == 3) break;
            // The next report comes 50 ms later, as the client's do, whether or not the hover is drawn by then.
            long until = System.nanoTime() + 50_000_000L;
            while (System.nanoTime() < until) {
                if (want != null && shownAt < 0 && shows(s, want)) shownAt = System.nanoTime();
                Thread.onSpinWait();
            }
        }
        if (want == null) return new double[]{-1, 0, 0};
        long deadline = t0 + 2_000_000_000L;
        boolean dumped = false;
        while (shownAt < 0 && System.nanoTime() < deadline) {
            if (shows(s, want)) {
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
        return new double[]{shownAt > 0 ? (shownAt - t0) / 1e6 : -1, ha.taken.get() > taken0 ? 1 : 0, busy ? 1 : 0};
    }

    private static boolean shows(Session s, List<Element> key) {
        List<Element> drawn = get(s, "drawnHover");
        return drawn.equals(key);
    }

    /** The hover key the session gives the pointer at (x, y): its element there, in its own document. */
    @SuppressWarnings("unchecked")
    private static List<Element> keyAt(Session s, int x, int y) {
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

    private static void copy(Path from, Path to) throws Exception {
        try (var files = Files.walk(from)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Path d = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) Files.createDirectories(d);
                else Files.copy(f, d);
            }
        }
    }

    private static HoverLayers layerSet(Canvas canvas, int capacity) throws Exception {
        Class<?> c = Class.forName("ai.resourcepack.ui.framework.world.LayerSet");
        Constructor<?> k = c.getDeclaredConstructors()[0];
        k.setAccessible(true);
        return (HoverLayers) k.newInstance(SessionRig.PALETTE, canvas, null, COLS, ROWS, 8.0, 0);
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
