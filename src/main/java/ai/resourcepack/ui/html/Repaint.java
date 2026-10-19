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
 * one. Works out which part, draws it over the last picture, and says why not when it cannot be done. The
 * picture is a view, or a {@link ScrollCache}'s taller one: coordinates here are CSS pixels from its top.
 *
 * <p>Blurs are what make it hard. A {@code filter: blur()} blurs a layer of its own, which a repaint paints
 * only inside its rectangle, so a layer the rectangle cut would blur with a cut edge: the rectangle takes in
 * all of every such layer it meets, as far as its blur reads. A backdrop-filter blurs what was painted under
 * its box, from a little past its edges, so a rectangle painted on its own would blur the last picture's own
 * pixels (already blurred, with text on them) round its edge. Two things follow. A box with a backdrop that
 * meets the rectangle is painted again, so the rectangle is painted with a margin as deep as the blur reaches
 * (twice as deep, where backdrops are painted over other backdrops), and only the rectangle itself is kept.
 * And a backdrop that reads something that changed (painted before it, within its reach) changes too, so its
 * box joins the rectangle, until nothing more does. A blur that reads past the top or bottom of the picture
 * where the page goes on is drawn by a whole picture with the page there under it
 * ({@code HtmlRenderer.paintBands}), which a repaint of the picture cannot do: then it is drawn whole.
 */
public final class Repaint {
    private Repaint() {}

    /** Why a repaint was not partial. */
    public enum Why {
        PARTIAL, SIBLING_STATES, NO_REGION, TOO_BIG, GREW_TOO_BIG, LAYOUT_MOVED, PICTURE_EDGE
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

    /** A box with a backdrop-filter: where it is in the picture and where it came in the painting order. */
    private record Backdrop(Rectangle2D box, int order) {}

    /**
     * The part of the picture (CSS pixels) that [changed] and everything inside them cover in [f], grown by the
     * furthest their styles there paint outside themselves, over every blurred layer it meets, and with the
     * backdrops that read it; in whole pixels. Null commit when it cannot be told (nothing of theirs was drawn)
     * or cannot be repainted in part (a blur reads past the picture's edge, see the class).
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
        Rectangle2D view = picture(f);
        Rectangle2D wanted = new Rectangle2D.Double(area.getX() - reach, area.getY() - f.scrollY() - reach, area.getWidth() + 2 * reach, area.getHeight() + 2 * reach);
        Rectangle2D commit = wanted.createIntersection(view);
        if (commit.isEmpty()) return NONE;
        // On a page with a blur a whole picture paints on past its top and bottom where the page goes on
        // (HtmlRenderer.paintBands), and what it draws near those edges differs from a picture that stops there.
        if (pastEdge(f, wanted) && Painter.blurReach(f.styles().values(), f.scale()) > 0) return new Region(null, null, Why.PICTURE_EDGE);

        // Every layer a filter blurs, as far as the blur reads round it; and every backdrop.
        List<Rectangle2D> layers = new ArrayList<>();
        List<Backdrop> backdrops = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            Painter.Hit h = hits.get(i);
            Style s = f.style(h.element());
            if (s == null) continue;
            double blur = blurReach(s.filter, s);
            if (blur > 0) {
                Rectangle2D b = extent(f, h.element());
                layers.add(new Rectangle2D.Double(b.getX() - blur, b.getY() - f.scrollY() - blur, b.getWidth() + 2 * blur, b.getHeight() + 2 * blur));
            }
            if (!s.backdropFilter.equals("none")) {
                Rectangle2D b = h.shape().getBounds2D();
                Rectangle2D box = new Rectangle2D.Double(b.getX(), b.getY() - f.scrollY(), b.getWidth(), b.getHeight());
                if (box.intersects(view)) backdrops.add(new Backdrop(box, i));
            }
        }
        // How far (CSS px) a blur reads past what it draws on: a pixel or so more than its three boxes, per backdrop.
        double pad = backdrops.isEmpty() ? 0 : Painter.blurReach(f.styles().values(), f.scale()) / f.scale() + 3 / f.scale();
        for (boolean grew = true; grew; ) {
            grew = false;
            // A blurred layer the rectangle meets is painted whole, and what it blurs is what is kept.
            for (Rectangle2D layer : layers) {
                Rectangle2D in = layer.createIntersection(view);
                if (!commit.intersects(in) || commit.contains(in)) continue;
                if (pastEdge(f, layer)) return new Region(null, null, Why.PICTURE_EDGE);
                commit = commit.createUnion(in);
                grew = true;
            }
            // A backdrop painted after something that changed, and within its reach of it, changes where it reads it.
            Rectangle2D reads = grow(commit, pad);
            for (Backdrop b : backdrops) {
                if (b.order <= first || !b.box.intersects(reads)) continue;
                Rectangle2D out = b.box.createIntersection(reads).createIntersection(view);
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
        Rectangle2D paint = deepest == 0 ? commit : grow(commit, deepest * pad).createIntersection(view);
        if (deepest > 0) {
            // What the backdrops read in the margin has to be right too: no blurred layer cut there either.
            for (boolean grew = true; grew; ) {
                grew = false;
                for (Rectangle2D layer : layers) {
                    Rectangle2D in = layer.createIntersection(view);
                    if (!paint.intersects(in) || paint.contains(in)) continue;
                    if (pastEdge(f, layer)) return new Region(null, null, Why.PICTURE_EDGE);
                    paint = paint.createUnion(in);
                    grew = true;
                }
            }
            for (Backdrop b : backdrops) {
                if (b.box.intersects(paint) && pastEdge(f, grow(b.box, pad))) return new Region(null, null, Why.PICTURE_EDGE);
            }
        }
        return new Region(whole(commit, f, view), whole(paint, f, view), Why.PARTIAL);
    }

    /** The picture [f] is, in CSS pixels from its top: the view, or a ScrollCache's taller one. */
    private static Rectangle2D picture(HtmlRenderer.Frame f) {
        return new Rectangle2D.Double(0, 0, f.viewportWidth(), f.image().getHeight() / f.scale());
    }

    /** Whether [r] (CSS pixels of [f]'s picture) reaches past its top or bottom where the page goes on past them. */
    private static boolean pastEdge(HtmlRenderer.Frame f, Rectangle2D r) {
        double sc = f.scale();
        int rows = f.image().getHeight();
        boolean above = Math.floor(f.scrollY() * sc + 1e-6) > 0, below = Math.floor((f.pageHeight() - f.scrollY()) * sc + 1e-6) > rows;
        return above && r.getMinY() < 0 || below && r.getMaxY() > rows / sc;
    }

    /** [r] out to whole pixels of [f]'s picture: a clip edge part way through a pixel leaves that pixel half repainted. */
    private static Rectangle2D whole(Rectangle2D r, HtmlRenderer.Frame f, Rectangle2D view) {
        double sc = f.scale(), x0 = Math.floor(r.getX() * sc) / sc, y0 = Math.floor(r.getY() * sc) / sc;
        return new Rectangle2D.Double(x0, y0, Math.ceil(r.getMaxX() * sc) / sc - x0, Math.ceil(r.getMaxY() * sc) / sc - y0).createIntersection(view);
    }

    /** Where [e] and everything inside it are drawn in [f], in page CSS pixels. */
    private static Rectangle2D extent(HtmlRenderer.Frame f, Element e) {
        Rectangle2D area = null;
        for (Painter.Hit h : f.hits()) {
            boolean inside = false;
            for (Element p = h.element(); p != null && !inside; p = p.parent()) inside = p == e;
            if (!inside) continue;
            Rectangle2D b = h.shape().getBounds2D();
            if (area == null) area = new Rectangle2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight());
            else area.add(b);
        }
        return area;
    }

    private static Rectangle2D grow(Rectangle2D r, double by) {
        return new Rectangle2D.Double(r.getX() - by, r.getY() - by, r.getWidth() + 2 * by, r.getHeight() + 2 * by);
    }

    /**
     * Whether the area to paint is small enough that painting just it beats painting everything: the view, or a
     * ScrollCache's taller picture, whose whole repaint would cost several views.
     */
    private static boolean small(Rectangle2D region, HtmlRenderer.Frame f) {
        return region.getWidth() * region.getHeight() < 0.6 * f.viewportWidth() * f.image().getHeight() / f.scale();
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
