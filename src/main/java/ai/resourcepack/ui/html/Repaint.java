package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.css.Values;
import ai.resourcepack.ui.html.paint.Painter;
import ai.resourcepack.ui.html.style.Len;
import ai.resourcepack.ui.html.style.Style;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A change of hover or press state drawn as a repaint of part of the last picture instead of a whole new
 * one. Works out which part, draws it over the last picture, and says why not when it cannot be done.
 *
 * <p>A backdrop-filter is what makes it hard: it blurs what was painted under its box, from a little past
 * its edges, so a rectangle painted on its own would blur the last picture's own pixels (already blurred,
 * with text on them) round its edge. Two things follow. A box with a backdrop that meets the rectangle is
 * painted again, so the rectangle is painted with a margin as deep as the blur reaches (twice as deep,
 * where backdrops are painted over other backdrops), and only the rectangle itself is kept. And a backdrop
 * that reads something that changed (painted before it, within its reach) changes too, so its box joins
 * the rectangle, until nothing more does.
 */
public final class Repaint {
    private Repaint() {}

    /** Why a repaint was not partial. */
    public enum Why {
        PARTIAL, SIBLING_STATES, NO_REGION, TOO_BIG, GREW_TOO_BIG, LAYOUT_MOVED
    }

    /**
     * [frame] is the new picture, drawn over the last one's pixels, and [changed] the CSS-pixel rectangle of it
     * that differs (all of the canvas that has to be redrawn). [frame] is null, and [changed] null, unless [why] is PARTIAL.
     */
    public record Result(HtmlRenderer.Frame frame, Rectangle2D changed, Why why) {}

    /** Room round a changed element for what it may draw outside itself on hover beyond its shadows and blur: a lift, a glow. */
    private static final double DIRTY_MARGIN = 48;

    /**
     * Draws the state [state] over [before], the picture of the same page and document at the same scroll, in which
     * the elements [moved] had the other state, each render within [timeoutMs] (0 for none: see
     * {@link HtmlRenderer#render(Page, Document, int, int, double, double, Selector.State, java.awt.image.BufferedImage, Rectangle2D, Rectangle2D, long)}).
     * Null result frame: draw everything.
     */
    public static Result partial(Page page, Document doc, int width, int height, double scale, double scroll, Selector.State state,
                                 HtmlRenderer.Frame before, Set<Element> moved, long timeoutMs) {
        // A page where hovering one element restyles its siblings ('+', '~') is drawn whole.
        if (page.siblingStates()) return fail(Why.SIBLING_STATES);
        Region region = region(before, moved);
        if (region.commit == null) return fail(region.why);
        if (!small(region.paint, before)) return fail(Why.TOO_BIG);
        HtmlRenderer.Frame f = HtmlRenderer.render(page, doc, width, height, scale, scroll, state, before.image(), region.paint, region.commit, timeoutMs);
        // The new state may reach further than the old one did (a shadow only hovering adds): then paint both.
        Region after = region(f, moved);
        if (after.commit == null) return fail(after.why);
        if (!region.covers(after)) {
            region = region.union(after);
            if (!small(region.paint, before)) return fail(Why.GREW_TOO_BIG);
            f = HtmlRenderer.render(page, doc, width, height, scale, scroll, state, before.image(), region.paint, region.commit, timeoutMs);
        }
        if (!sameLayout(before, f, region.commit)) return fail(Why.LAYOUT_MOVED);
        return new Result(f, region.commit, Why.PARTIAL);
    }

    private static Result fail(Why why) {
        return new Result(null, null, why);
    }

    /** What to paint and what to keep, in CSS pixels of the viewport; [commit] is null (with [why]) if it cannot be told. */
    record Region(Rectangle2D commit, Rectangle2D paint, Why why) {
        boolean covers(Region o) {
            return commit.contains(o.commit) && paint.contains(o.paint);
        }

        Region union(Region o) {
            return new Region(commit.createUnion(o.commit), paint.createUnion(o.paint), Why.PARTIAL);
        }
    }

    private static final Region NONE = new Region(null, null, Why.NO_REGION);

    /** A box with a backdrop-filter: where it is in the viewport and where it came in the painting order. */
    private record Backdrop(Rectangle2D box, int order) {}

    /**
     * The part of the viewport (CSS pixels) that [changed] and everything inside them cover in [f], grown by the
     * furthest their styles there paint outside themselves, and with the backdrops that read it. Null commit when
     * it cannot be told (nothing of theirs was drawn).
     */
    static Region region(HtmlRenderer.Frame f, Set<Element> changed) {
        Rectangle2D area = null;
        double reach = DIRTY_MARGIN;
        int first = Integer.MAX_VALUE;
        List<Painter.Hit> hits = f.hits();
        for (int i = 0; i < hits.size(); i++) {
            Painter.Hit h = hits.get(i);
            boolean inside = false;
            for (Element e = h.element(); e != null && !inside; e = e.parent()) inside = changed.contains(e);
            if (!inside) continue;
            Rectangle2D b = h.shape().getBounds2D();
            if (area == null) area = new Rectangle2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight());
            else area.add(b);
            reach = Math.max(reach, paintsOutside(f.style(h.element())));
            first = Math.min(first, i);
        }
        if (area == null) return NONE;
        Rectangle2D view = new Rectangle2D.Double(0, 0, f.viewportWidth(), f.viewportHeight());
        Rectangle2D commit = new Rectangle2D.Double(area.getX() - reach, area.getY() - f.scrollY() - reach,
                area.getWidth() + 2 * reach, area.getHeight() + 2 * reach).createIntersection(view);
        if (commit.isEmpty()) return NONE;

        List<Backdrop> backdrops = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            Painter.Hit h = hits.get(i);
            Style s = f.style(h.element());
            if (s == null || s.backdropFilter.equals("none")) continue;
            Rectangle2D b = h.shape().getBounds2D();
            Rectangle2D box = new Rectangle2D.Double(b.getX(), b.getY() - f.scrollY(), b.getWidth(), b.getHeight()).createIntersection(view);
            if (!box.isEmpty()) backdrops.add(new Backdrop(box, i));
        }
        if (backdrops.isEmpty()) return new Region(commit, commit, Why.PARTIAL);

        // How far (CSS px) a blur reads past what it draws on: a pixel or so more than its three boxes, per backdrop.
        double pad = Painter.blurReach(f.styles().values(), f.scale()) / f.scale() + 3 / f.scale();
        // A backdrop painted after something that changed, and within its reach of it, changes where it reads it.
        boolean grew = true;
        while (grew) {
            grew = false;
            Rectangle2D reads = grow(commit, pad);
            for (Backdrop b : backdrops) {
                if (b.order <= first || !b.box.intersects(reads)) continue;
                Rectangle2D out = b.box.createIntersection(reads);
                if (!commit.contains(out)) {
                    commit = commit.createUnion(out);
                    grew = true;
                }
            }
        }
        // Each backdrop that meets the rectangle is painted again, reading what is painted under it, which is the
        // rectangle's own paint only as far as the margin reaches: backdrops painted over other backdrops make the
        // chain longer, and each link needs its own reach.
        int[] depth = new int[backdrops.size()];
        int deepest = 0;
        for (int i = 0; i < backdrops.size(); i++) {
            Backdrop b = backdrops.get(i);
            int d = 1;
            Rectangle2D reads = grow(b.box, pad);
            for (int j = 0; j < i; j++) if (backdrops.get(j).box.intersects(reads)) d = Math.max(d, depth[j] + 1);
            depth[i] = d;
            if (b.box.intersects(commit)) deepest = Math.max(deepest, d);
        }
        if (deepest == 0) return new Region(commit, commit, Why.PARTIAL);
        return new Region(commit, grow(commit, deepest * pad).createIntersection(view), Why.PARTIAL);
    }

    private static Rectangle2D grow(Rectangle2D r, double by) {
        return new Rectangle2D.Double(r.getX() - by, r.getY() - by, r.getWidth() + 2 * by, r.getHeight() + 2 * by);
    }

    /** Whether the area to paint is small enough that painting just it beats painting everything. */
    private static boolean small(Rectangle2D region, HtmlRenderer.Frame f) {
        return region.getWidth() * region.getHeight() < 0.6 * f.viewportWidth() * f.viewportHeight();
    }

    /** How far past its box (CSS pixels) an element styled [s] can paint: its outer shadows, its text's shadows, its blur. */
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
            // Offset and spread, and the blur: a Gaussian of sigma blur/2, drawn out to three sigma.
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
            // blur(r) is a Gaussian of sigma r, drawn out to three sigma.
            if (l != null) reach = Math.max(reach, 3 * Math.abs(l.of(0)) + 2);
        }
        return reach;
    }

    /**
     * Whether nothing outside [region] moved between two frames: if something did, a partial repaint is not enough.
     * Boxes are matched by element, not by place in the list: a hover that lifts a box (a transform) paints it later,
     * which reorders the list without moving anything else.
     */
    private static boolean sameLayout(HtmlRenderer.Frame a, HtmlRenderer.Frame b, Rectangle2D region) {
        if (a.hits().size() != b.hits().size()) return false;
        Map<Element, List<Rectangle2D>> before = new IdentityHashMap<>();
        for (Painter.Hit h : a.hits()) before.computeIfAbsent(h.element(), k -> new ArrayList<>()).add(h.shape().getBounds2D());
        Map<Element, Integer> seen = new IdentityHashMap<>();
        for (Painter.Hit h : b.hits()) {
            List<Rectangle2D> boxes = before.get(h.element());
            int n = seen.merge(h.element(), 1, Integer::sum) - 1;
            if (boxes == null || n >= boxes.size()) return false;
            Rectangle2D ra = boxes.get(n), rb = h.shape().getBounds2D();
            if (Math.abs(ra.getX() - rb.getX()) < 0.5 && Math.abs(ra.getY() - rb.getY()) < 0.5
                    && Math.abs(ra.getWidth() - rb.getWidth()) < 0.5 && Math.abs(ra.getHeight() - rb.getHeight()) < 0.5) continue;
            Rectangle2D va = new Rectangle2D.Double(ra.getX(), ra.getY() - a.scrollY(), ra.getWidth(), ra.getHeight());
            Rectangle2D vb = new Rectangle2D.Double(rb.getX(), rb.getY() - b.scrollY(), rb.getWidth(), rb.getHeight());
            if (!region.contains(va) || !region.contains(vb)) return false;
        }
        return true;
    }
}
