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
        int trials = Integer.getInteger("trials", 16), capacity = Integer.getInteger("layers", 32), threads = Integer.getInteger("threads", 4);
        String json = Files.readString(Path.of(System.getProperty("targets", "build/hover-targets.json")));
        Path dir = Files.createTempDirectory("hover-timeline");
        copy(from, dir);
        for (String page : pages) {
            List<int[][]> ts = targets(json, page);
            List<double[]> off = new ArrayList<>(), on = new ArrayList<>();
            Random rnd = new Random(7);
            for (int i = 0; i < trials; i++) {
                int[][] t = ts.get(rnd.nextInt(ts.size()));
                for (boolean layers : i % 2 == 0 ? new boolean[]{false, true} : new boolean[]{true, false}) {
                    double[] r = trial(dir, page, t, layers, capacity, threads, rnd.nextLong());
                    (layers ? on : off).add(r);
                    System.out.printf("%-7s %-3s box %-20s enter %7.1f ms ahead %s  leave %7.1f ms ahead %s  arm %6.1f ms cpu (%d armed, %d used)%n",
                            page, layers ? "on" : "off", java.util.Arrays.toString(t[0]), r[0], r[1] > 0 ? "y" : "n", r[2], r[3] > 0 ? "y" : "n",
                            r[4], (int) r[5], (int) r[6]);
                }
            }
            for (String tag : new String[]{"off", "on"}) {
                List<double[]> rs = tag.equals("off") ? off : on;
                System.out.printf("SUMMARY %-7s %-3s enter p50 %6.1f p90 %6.1f ahead %d/%d   leave p50 %6.1f p90 %6.1f ahead %d/%d   arm cpu mean %.1f ms%n",
                        page, tag, q(rs, 0, 0.5), q(rs, 0, 0.9), (int) rs.stream().filter(r -> r[1] > 0).count(), rs.size(),
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

    /** One fresh session: a settle, a warm-up hover, then the timed trial. {enter ms, ahead, leave ms, ahead, arm ms, armed, used}. */
    private static double[] trial(Path dir, String page, int[][] t, boolean layersOn, int capacity, int threads, long seed) throws Exception {
        try (SessionRig rig = new SessionRig(dir, threads)) {
            if (rig.pages.load(page).get() == null) throw new IllegalStateException("no page " + page);
            Session s = rig.open(rig.plugin(4), page, COLS, ROWS, 576);
            rig.quiet();
            Canvas layerCanvas = new Canvas(SessionRig.PALETTE, capacity, 1);
            HoverLayers layers = layersOn ? layerSet(layerCanvas, capacity) : null;
            if (layersOn) rig.onServer(() -> s.layers(layers));
            Random rnd = new Random(seed);
            int[] box = t[0], r = t[1], c = t[2];
            // Somewhere else first, so the rest is a move as on the PC (the last trial's leave).
            rig.onServer(() -> s.pointer(6, 1146));
            rig.quiet();
            ClientHovers h = layersOn ? get(s, "hovers") : null;
            long arm0 = h == null ? 0 : h.armNs;
            rig.onServer(() -> s.pointer(r[0], r[1]));
            Thread.sleep((long) (700 + 300 * rnd.nextDouble()));
            double arm = h == null ? 0 : (h.armNs - arm0) / 1e6;
            int armed = h == null ? 0 : h.count(), used = layers == null ? 0 : layers.used();
            double[] in = glide(rig, s, r, c, box, true);
            Thread.sleep(500);
            double[] out = glide(rig, s, c, r, box, false);
            rig.quiet();
            return new double[]{in[0], in[1], out[0], out[1], arm, armed, used};
        }
    }

    /**
     * Reports the pointer along a to b as the client does, every 50 ms over a 150 ms glide; from the first report on the
     * other side of [box]'s edge: ms until the session's picture is of the hover there, 0 if the client shows it by itself.
     */
    private static double[] glide(SessionRig rig, Session s, int[] a, int[] b, int[] box, boolean entering) throws Exception {
        HoverAhead ha = get(s, "hoverAhead");
        long taken0 = ha.taken.get();
        long t0 = -1;
        List<Element> want = null;
        for (int i = 1; i <= 3; i++) {
            int x = a[0] + (b[0] - a[0]) * i / 3, y = a[1] + (b[1] - a[1]) * i / 3;
            boolean inside = x >= box[0] && y >= box[1] && x < box[0] + box[2] && y < box[1] + box[3];
            if (t0 < 0 && inside == entering) {
                HtmlRenderer.Frame f = get(s, "frame");
                double sc = f.scale();
                Element e = f.elementAt(x / sc, y / sc);
                List<Element> key = f.page().styles.stateKey(e, false);
                // What the hover's picture is drawn for: the session's own element (the frame's document is the session's).
                want = key;
                ClientHovers h = get(s, "hovers");
                List<Element> drawn = get(s, "drawnHover");
                boolean layered = h != null && h.armed(f, key);
                t0 = System.nanoTime();
                rig.onServer(() -> s.pointer(x, y));
                if (layered || key.equals(drawn)) return new double[]{0, 1};
                // The next reports come while it is drawn, as they do.
                long until = t0 + 50_000_000L;
                while (System.nanoTime() < until && !shows(s, key)) Thread.onSpinWait();
                continue;
            }
            rig.onServer(() -> s.pointer(x, y));
            if (i < 3) Thread.sleep(50);
        }
        if (want == null) return new double[]{-1, 0};
        long deadline = t0 + 2_000_000_000L;
        while (!shows(s, want) && System.nanoTime() < deadline) Thread.sleep(0, 200_000);
        double ms = (System.nanoTime() - t0) / 1e6;
        return new double[]{shows(s, want) ? ms : -1, ha.taken.get() > taken0 ? 1 : 0};
    }

    private static boolean shows(Session s, List<Element> key) {
        List<Element> drawn = get(s, "drawnHover");
        return drawn.equals(key);
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
