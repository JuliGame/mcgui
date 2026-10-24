package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.api.HoverLayers;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import org.jsoup.nodes.Element;

import java.awt.geom.Rectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hovers inside hovers with hover layers on (a button in a card whose hover differs from the card's alone), on real
 * {@link Session}s with real render threads and ticks: for each such pair on a page, the pointer rests outside the outer
 * element, goes onto the inner one, rests, goes to the outer one only, rests, back onto the inner one, rests, and out.
 * Per move: the share of the changed pixels the client shows the moment the pointer gets there, before the server has
 * heard of it ({@link HoverTimeline.Seen}: the picture with every armed layer the pointer is in laid over it, against a
 * fresh picture of the hover there), and ms until it shows all of them.
 *
 * <p>System properties: dir (pages), pages (comma separated), pairs (most per page, default 4), layers (default 32),
 * rest (ms, default 800).
 */
public final class NestedHovers {
    private static final int COLS = 16, ROWS = 9;

    public static void main(String[] args) throws Exception {
        Path from = Path.of(System.getProperty("dir", "src/main/resources/pages"));
        int most = Integer.getInteger("pairs", 4), capacity = Integer.getInteger("layers", 32), rest = Integer.getInteger("rest", 800);
        Path dir = Files.createTempDirectory("nested-hovers");
        HoverTimeline.copy(from, dir);
        String[] moves = {"into inner", "inner to outer", "outer to inner", "out"};
        int[] whole = new int[moves.length], tried = new int[moves.length];
        for (String page : System.getProperty("pages", "crates,auction").split(",")) {
            try (SessionRig rig = new SessionRig(dir, 4)) {
                if (rig.pages.load(page).get() == null) throw new IllegalStateException("no page " + page);
                Session s = rig.open(rig.plugin(4), page, COLS, ROWS, 576);
                rig.quiet();
                HoverLayers layers = HoverTimeline.layerSet(new Canvas(SessionRig.PALETTE, capacity, 1), capacity);
                Page p = rig.pages.loaded(page);
                HoverTimeline.Seen seen = new HoverTimeline.Seen(layers, p, s);
                rig.onServer(() -> s.layers(seen));
                rig.onServer(() -> s.pointer(6, 1146));
                rig.quiet();
                var ticker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                long[] tick = {0};
                ticker.scheduleAtFixedRate(() -> rig.onServer(() -> s.tick(++tick[0])), 50, 50, java.util.concurrent.TimeUnit.MILLISECONDS);
                for (int[][] pts : pairs(s, most)) {
                    // rest outside, inner, outer only, inner, outside
                    int[][] path = {pts[2], pts[0], pts[1], pts[0], pts[2]};
                    move(rig, s, path[0]);
                    Thread.sleep(rest);
                    StringBuilder line = new StringBuilder(String.format("%-8s inner %-12s outer %-12s", page,
                            java.util.Arrays.toString(pts[0]), java.util.Arrays.toString(pts[1])));
                    for (int m = 0; m < moves.length; m++) {
                        int[] to = path[m + 1];
                        List<Element> key = HoverTimeline.keyAt(s, to[0], to[1]);
                        seen.fresh(key, to[0], to[1]);
                        if (Boolean.getBoolean("diag")) System.out.println("  " + diag(s, key));
                        double now = seen.atOnce(key, to[0], to[1]);
                        if (Boolean.getBoolean("diag")) System.out.println("    at " + to[0] + "," + to[1] + ": " + seen.last);
                        long t0 = System.nanoTime();
                        move(rig, s, to);
                        double ms = now >= 1 ? 0 : -1;
                        while (ms < 0 && System.nanoTime() - t0 < 2_000_000_000L) {
                            if (seen.atOnce(key, to[0], to[1]) >= 1) ms = (System.nanoTime() - t0) / 1e6;
                            else Thread.sleep(1);
                        }
                        tried[m]++;
                        if (now >= 1) whole[m]++;
                        line.append(String.format("  %s %5.1f%% %6.1f ms", moves[m], 100 * now, ms));
                        Thread.sleep(rest);
                    }
                    System.out.println(line);
                }
                ticker.shutdownNow();
                rig.quiet();
            }
        }
        StringBuilder sum = new StringBuilder("SUMMARY whole at once:");
        for (int m = 0; m < moves.length; m++) sum.append(String.format("  %s %d/%d", moves[m], whole[m], tried[m]));
        System.out.println(sum);
        System.exit(0);
    }

    private static void move(SessionRig rig, Session s, int[] to) {
        rig.onServer(() -> s.pointer(to[0], to[1]));
    }

    /** Why [key] has a layer or not, as the pointer goes there: armed, known never to have one, drawn ahead (ready). */
    @SuppressWarnings("unchecked")
    private static String diag(Session s, List<Element> key) {
        try {
            ClientHovers h = HoverTimeline.get(s, "hovers");
            HtmlRenderer.Frame f = HoverTimeline.get(s, "frame");
            List<Element> drawn = HoverTimeline.get(s, "drawnHover"), press = HoverTimeline.get(s, "drawnPress");
            var never = ClientHovers.class.getDeclaredField("never");
            never.setAccessible(true);
            ai.resourcepack.ui.html.HoverAhead ha = HoverTimeline.get(s, "hoverAhead");
            var readies = ha.ready(f, drawn, press, press, k -> true);
            var mine = readies.stream().filter(r -> r.hoverKey().equals(key)).findFirst().orElse(null);
            String why = "";
            if (mine != null) {
                // What working its layer out comes to: where it shows and whether it fits, and the maps it takes.
                var where = ClientHovers.class.getDeclaredMethod("where", HtmlRenderer.Frame.class, java.awt.image.BufferedImage.class,
                        ai.resourcepack.ui.html.HoverAhead.Ready.class, List.class, Map.class, Map.class);
                where.setAccessible(true);
                Object sh = where.invoke(null, f, f.image(), mine, drawn, new java.util.IdentityHashMap<>(), new java.util.IdentityHashMap<>());
                int[] place = new int[4];
                int[] px = ClientHovers.layerPixels(f.image(), mine.prepared(), place);
                why = " " + sh.toString().replaceAll("\\[I@[0-9a-f]+", "#") + " layer " + java.util.Arrays.toString(place)
                        + (px == null ? " (changes nothing)" : "");
                var inc = sh.getClass().getDeclaredMethod("include");
                var exc = sh.getClass().getDeclaredMethod("exclude");
                inc.setAccessible(true);
                exc.setAccessible(true);
                why += " in " + java.util.Arrays.toString((int[]) inc.invoke(sh)) + " out " + java.util.Arrays.toString((int[]) exc.invoke(sh));
            }
            return String.format("to %s from %s: armed %s never %s ready %s (%d armed)%s", names(key), names(drawn), h.armed(f, key),
                    ((java.util.Set<List<Element>>) never.get(h)).contains(key), mine != null, h.count(), why);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String names(List<Element> key) {
        return key.stream().map(e -> e.tagName() + "." + e.className().replace(' ', '.')).toList().toString();
    }

    /**
     * Up to [most] nested pairs on the page as drawn: each {inner point, outer-only point, outside point} in canvas pixels,
     * one pair per distinct outer element.
     */
    private static List<int[][]> pairs(Session s, int most) {
        HtmlRenderer.Frame f = HoverTimeline.get(s, "frame");
        org.jsoup.nodes.Document doc = HoverTimeline.get(s, "doc");
        Page p = f.page();
        double sc = f.scale();
        int w = f.image().getWidth(), h = f.image().getHeight();
        Map<Element, int[][]> out = new LinkedHashMap<>();
        for (Element e : doc.getAllElements()) {
            if (out.size() >= most) break;
            List<Element> key = p.styles.stateKey(e, false);
            if (key.size() < 2 || key.get(0) != e) continue;
            Element outer = key.get(key.size() - 1);
            if (out.containsKey(outer)) continue;
            Rectangle2D ib = f.boundsOf(e), ob = f.boundsOf(outer);
            if (ib == null || ob == null) continue;
            int[] in = {(int) (ib.getCenterX() * sc), (int) ((ib.getCenterY() - f.scrollY()) * sc)};
            if (in[0] < 0 || in[1] < 0 || in[0] >= w || in[1] >= h || !HoverTimeline.keyAt(s, in[0], in[1]).equals(key)) continue;
            int[] only = null, outside = null;
            List<Element> outerKey = p.styles.stateKey(outer, false);
            // The outer-only point and the point outside nearest the inner one: where a player's pointer goes from it.
            double best = Double.MAX_VALUE;
            for (int y = (int) (ob.getMinY() * sc) + 6; y < ob.getMaxY() * sc - 6; y += 8) {
                for (int x = (int) (ob.getMinX() * sc) + 6; x < ob.getMaxX() * sc - 6; x += 8) {
                    double d = Math.hypot(x - in[0], y - in[1]);
                    if (d < best && x >= 0 && y >= 0 && x < w && y < h && HoverTimeline.keyAt(s, x, y).equals(outerKey)) {
                        only = new int[]{x, y};
                        best = d;
                    }
                }
            }
            int[][] beside = {{(int) (ob.getMinX() * sc) - 12, in[1]}, {(int) (ob.getMaxX() * sc) + 12, in[1]},
                    {in[0], (int) (ob.getMinY() * sc) - 12}, {in[0], (int) (ob.getMaxY() * sc) + 12}};
            best = Double.MAX_VALUE;
            for (int[] b : beside) {
                double d = Math.hypot(b[0] - in[0], b[1] - in[1]);
                if (d < best && b[0] >= 0 && b[1] >= 0 && b[0] < w && b[1] < h && !HoverTimeline.keyAt(s, b[0], b[1]).contains(outer)) {
                    outside = b;
                    best = d;
                }
            }
            if (only != null && outside != null) out.put(outer, new int[][]{in, only, outside});
        }
        return new ArrayList<>(out.values());
    }
}
