package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.nio.file.Path;
import java.util.List;

/**
 * Drawing-ahead coverage, offline: {@code AheadCheck <page> <rx,ry,cx,cy>...}. For each trial (canvas px at 2048x1152,
 * scale 2) the pointer rests at r, drawing ahead runs as a session's would (at rest, and as just after a jump from the
 * previous trial's rest), then the pointer glides to c in three reports: a HIT if the first new hover state it reaches
 * was drawn ahead. Prints each trial and the hits of each mode (PaintCost's crates trials: 7 of 23 missed at rest with
 * the ranking by distance).
 */
public final class AheadCheck {
    static final int W = 2048, H = 1152;
    static final double S = 2;

    public static void main(String[] args) throws Exception {
        Page.Loader loader = new Page.Loader(true, Path.of("build/samples/cache"));
        Page page = Page.load(Path.of(args[0]), loader);
        double px = -1, py = -1;
        int trials = 0, restHits = 0, jumpHits = 0, drawn = 0;
        for (int a = 1; a < args.length; a++) {
            String[] v = args[a].split(",");
            double rx = Double.parseDouble(v[0]) / S, ry = Double.parseDouble(v[1]) / S, cx = Double.parseDouble(v[2]) / S, cy = Double.parseDouble(v[3]) / S;
            Document doc = page.document.clone();
            HtmlRenderer.Frame f0 = HtmlRenderer.render(page, doc, W, H, S, 0, Selector.State.NONE);
            Element re = f0.elementAt(rx, ry);
            List<Element> rk = page.styles.stateKey(re, false);
            HtmlRenderer.Frame f = rk.isEmpty() ? f0 : HtmlRenderer.render(page, doc, W, H, S, 0, HtmlRenderer.state(re, null));
            StringBuilder out = new StringBuilder(args[a] + " rest=" + names(rk));
            List<Element> need = null;
            for (int i = 1; i <= 3 && need == null; i++) {
                double x = rx + (cx - rx) * i / 3, y = ry + (cy - ry) * i / 3;
                List<Element> k = page.styles.stateKey(f.elementAt(x, y), false);
                if (!k.equals(rk)) need = k;
            }
            out.append(" need=").append(need == null ? "-" : names(need));
            trials++;
            for (String mode : new String[]{"rest", "jump"}) {
                HoverAhead ahead = new HoverAhead(4, new SharedRepaints(Long.MAX_VALUE));
                double vx = 0, vy = 0;
                if (mode.equals("jump") && px >= 0) {
                    vx = (rx - px) / 0.05;
                    vy = (ry - py) / 0.05;
                }
                HoverAhead.budget(1);
                HoverAhead.Job job = ahead.job(page, doc, W, H, S, f, rk, List.of(), rk, null, List.of(), rx, ry, vx, vy, 0);
                ahead.run(job);
                List<List<Element>> kept = ahead.keys();
                boolean hit = need != null && kept.contains(need);
                if (hit && mode.equals("rest")) restHits++;
                if (hit && mode.equals("jump")) jumpHits++;
                if (mode.equals("rest")) drawn += (int) ahead.prepared.get();
                out.append(" | ").append(mode).append(hit ? " HIT" : " MISS").append(" kept=");
                for (List<Element> k : kept) out.append(names(k)).append(' ');
            }
            System.out.println(out);
            px = rx;
            py = ry;
        }
        System.out.printf("AheadCheck: %d trials, drawn ahead at rest %d of them in time (%d repaints drawn), after a jump %d%n", trials, restHits, drawn, jumpHits);
    }

    static String names(List<Element> k) {
        StringBuilder b = new StringBuilder("[");
        for (Element e : k) b.append(e.className().isEmpty() ? e.normalName() : e.className().split(" ")[0]).append('@').append(e.siblingIndex()).append(',');
        return b.append(']').toString();
    }
}
