package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.css.Values;
import ai.resourcepack.ui.html.paint.Painter;
import ai.resourcepack.ui.html.paint.PixelPool;
import ai.resourcepack.ui.html.style.Len;
import ai.resourcepack.ui.html.style.Style;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A change of hover or press state drawn as a repaint of part of the last picture instead of a whole new
 * one. Works out which part, draws it over the last picture, and says why not when it cannot be done. The
 * picture is a view, or a {@link ScrollCache}'s taller one: coordinates here are CSS pixels from its top.
 *
 * <p>Blurs are what make it hard. A {@code filter: blur()} blurs a layer of its own, which a repaint paints
 * only inside its rectangle, so a layer the rectangle cut would blur with a cut edge: the rectangle takes in
 * all of every such layer it meets that something which changed is in (or round), as far as its blur reads. A
 * blurred layer the change is apart from looks the same after (a blurred picture behind the whole page): it is
 * only painted as far round the rectangle as its blur reads, and the rectangle stays as it was. A backdrop-filter blurs what was painted under
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
        return partial(page, doc, width, height, scale, scroll, state, before, moved, () -> RenderBudget.of(timeoutMs), null);
    }

    /** As above, each render within a budget from [budgets]; [undo], if not null, is told what each render may paint over first. */
    private static Result partial(Page page, Document doc, int width, int height, double scale, double scroll, Selector.State state,
                                  HtmlRenderer.Frame before, Set<Element> moved, Supplier<RenderBudget> budgets, Undo undo) {
        // A page where hovering one element restyles its siblings ('+', '~') is drawn whole.
        if (page.siblingStates()) return fail(Why.SIBLING_STATES);
        Region region = region(before, moved);
        if (region.commit == null) return fail(region.why);
        if (!small(region.paint, before)) return fail(Why.TOO_BIG);
        if (undo != null) undo.save(region.paint);
        HtmlRenderer.Frame f = HtmlRenderer.render(page, doc, width, height, scale, scroll, state, before.image(), region.paint, region.commit, budgets.get());
        // The new state may reach further than the old one did (a shadow only hovering adds): then paint both.
        Region after = region(f, moved);
        if (after.commit == null) return fail(after.why);
        if (!region.covers(after)) {
            region = region.union(after);
            if (!small(region.paint, before)) return fail(Why.GREW_TOO_BIG);
            if (undo != null) undo.save(region.paint);
            f = HtmlRenderer.render(page, doc, width, height, scale, scroll, state, before.image(), region.paint, region.commit, budgets.get());
        }
        if (!sameLayout(before, f, region.commit)) return fail(Why.LAYOUT_MOVED);
        return new Result(f, region.commit, Why.PARTIAL);
    }

    /**
     * A partial repaint worked out in advance: the pixels it paints, kept apart, and the frame it makes (with no picture
     * of its own). {@link #apply} puts the pixels into a picture that holds what the one it was worked out over held
     * (that one, or a copy of it), which then is what {@link #partial} would have left, and the result is what it would
     * have returned. Only while that picture has not changed since.
     */
    public static final class Prepared {
        private final HtmlRenderer.Frame frame;
        private final Rectangle2D changed;
        private final java.awt.Rectangle at;
        private final int[] pixels;

        private Prepared(HtmlRenderer.Frame frame, Rectangle2D changed, java.awt.Rectangle at, int[] pixels) {
            this.frame = frame;
            this.changed = changed;
            this.at = at;
            this.pixels = pixels;
        }

        /** What it holds on to, roughly: its pixels (the frame's styles and regions are shared in part with the picture's). */
        public long bytes() {
            return 4L * pixels.length + 64L * frame.hits().size();
        }

        /** Paints the repaint into [onto]'s picture (the picture it was worked out over, or a copy of it) and gives the frame it makes. */
        public Result apply(HtmlRenderer.Frame onto) {
            BufferedImage img = onto.image();
            int[] px = pixels(img);
            int stride = img.getWidth();
            for (int y = 0; y < at.height; y++) System.arraycopy(pixels, y * at.width, px, (at.y + y) * stride + at.x, at.width);
            HtmlRenderer.Frame f = new HtmlRenderer.Frame(img, frame.scale(), frame.viewportWidth(), frame.viewportHeight(), frame.pageHeight(),
                    frame.scrollY(), frame.hits(), frame.styles(), frame.document(), frame.page());
            return new Result(f, changed, Why.PARTIAL);
        }
    }

    /**
     * {@link #partial} worked out without changing [before]'s picture: painted over it, the pixels kept apart, and the
     * picture put back as it was, also when it fails or [budget] gives up ({@link RenderAbandoned}). Null if the repaint
     * would not be partial. [before]'s picture must not be read or painted by anything else meanwhile (HoverAhead
     * gives it a copy of its own), and the result keeps no hold on it.
     */
    public static Prepared prepare(Page page, Document doc, int width, int height, double scale, double scroll, Selector.State state,
                                   HtmlRenderer.Frame before, Set<Element> moved, RenderBudget budget) {
        Undo undo = new Undo(before.image(), scale);
        try {
            Result r = partial(page, doc, width, height, scale, scroll, state, before, moved, () -> budget, undo);
            if (r.frame() == null) return null;
            // Only the rectangle kept changes (the rest of what was painted is put back by the render itself), give or
            // take the pixel Java2D may round a clip edge out by; and of that, often only the middle (a card, not the
            // margin round it for its shadow). Kept: the smallest rectangle round every pixel that differs.
            java.awt.Rectangle tight = undo.differing(undo.device(r.changed()).intersection(undo.bounds));
            HtmlRenderer.Frame f = r.frame();
            HtmlRenderer.Frame pictureless = new HtmlRenderer.Frame(null, f.scale(), f.viewportWidth(), f.viewportHeight(), f.pageHeight(), f.scrollY(),
                    f.hits(), f.styles(), f.document(), f.page());
            return new Prepared(pictureless, r.changed(), tight, undo.copy(tight, new int[tight.width * tight.height]));
        } finally {
            undo.restore();
        }
    }

    /**
     * A picture's pixels under the rectangles a repaint is about to paint, saved before each, to be put back in turn. A
     * repaint paints only inside its clip, and Java2D may round a clip edge out by a pixel: one more all round is saved.
     * Each save is a {@link PixelPool} array of this thread's, handed back by {@link #restore}.
     */
    private static final class Undo {
        private final BufferedImage img;
        private final double scale;
        private final ArrayDeque<java.awt.Rectangle> rects = new ArrayDeque<>();
        private final ArrayDeque<int[]> saved = new ArrayDeque<>();
        java.awt.Rectangle bounds;

        Undo(BufferedImage img, double scale) {
            this.img = img;
            this.scale = scale;
        }

        /** [r] (CSS pixels) in whole device pixels of the picture, with a pixel more all round. */
        java.awt.Rectangle device(Rectangle2D r) {
            int w = img.getWidth(), h = img.getHeight();
            int x0 = Math.max(0, (int) Math.floor(r.getX() * scale) - 1), y0 = Math.max(0, (int) Math.floor(r.getY() * scale) - 1);
            int x1 = Math.min(w, (int) Math.ceil(r.getMaxX() * scale) + 1), y1 = Math.min(h, (int) Math.ceil(r.getMaxY() * scale) + 1);
            return new java.awt.Rectangle(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
        }

        void save(Rectangle2D clip) {
            java.awt.Rectangle r = device(clip);
            saved.push(copy(r, PixelPool.ints(r.width * r.height)));
            rects.push(r);
            bounds = bounds == null ? r : bounds.union(r);
        }

        /**
         * The smallest rectangle inside [r] where the picture now differs from what it was before the first save: each
         * pixel against the first save that holds it (later ones hold what earlier renders painted).
         */
        java.awt.Rectangle differing(java.awt.Rectangle r) {
            int[] now = pixels(img);
            java.awt.Rectangle[] rs = new java.awt.Rectangle[rects.size()];
            int[][] from = new int[rs.length][];
            Iterator<java.awt.Rectangle> ri = rects.descendingIterator();
            Iterator<int[]> si = saved.descendingIterator();
            for (int i = 0; i < rs.length; i++) {
                rs[i] = ri.next();
                from[i] = si.next();
            }
            int stride = img.getWidth(), x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
            // Per row, the stretches each save holds first, each compared at once (Arrays.mismatch) from the left and,
            // where it differs, back from the right.
            List<int[]> spans = new ArrayList<>(), next = new ArrayList<>();
            for (int y = r.y; y < r.y + r.height; y++) {
                spans.clear();
                spans.add(new int[]{r.x, r.x + r.width});
                for (int i = 0; i < rs.length && !spans.isEmpty(); i++) {
                    java.awt.Rectangle q = rs[i];
                    if (y < q.y || y >= q.y + q.height) continue;
                    next.clear();
                    for (int[] sp : spans) {
                        int a = Math.max(sp[0], q.x), b = Math.min(sp[1], q.x + q.width);
                        if (a >= b) {
                            next.add(sp);
                            continue;
                        }
                        if (sp[0] < a) next.add(new int[]{sp[0], a});
                        if (b < sp[1]) next.add(new int[]{b, sp[1]});
                        int row = (y - q.y) * q.width - q.x, at = y * stride;
                        int m = java.util.Arrays.mismatch(from[i], row + a, row + b, now, at + a, at + b);
                        if (m < 0) continue;
                        int last = b - 1;
                        while (from[i][row + last] == now[at + last]) last--;
                        x0 = Math.min(x0, a + m);
                        x1 = Math.max(x1, last);
                        y0 = Math.min(y0, y);
                        y1 = y;
                    }
                    List<int[]> t = spans;
                    spans = next;
                    next = t;
                }
            }
            return x1 < 0 ? new java.awt.Rectangle(r.x, r.y, 0, 0) : new java.awt.Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
        }

        int[] copy(java.awt.Rectangle r, int[] out) {
            int[] px = pixels(img);
            for (int y = 0; y < r.height; y++) System.arraycopy(px, (r.y + y) * img.getWidth() + r.x, out, y * r.width, r.width);
            return out;
        }

        /** Last saved first: each puts back what was there before the render it was saved for, and its array goes back to the pool. */
        void restore() {
            int[] px = pixels(img);
            while (!rects.isEmpty()) {
                java.awt.Rectangle r = rects.pop();
                int[] from = saved.pop();
                for (int y = 0; y < r.height; y++) System.arraycopy(from, y * r.width, px, (r.y + y) * img.getWidth() + r.x, r.width);
                PixelPool.give(from);
            }
        }
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

        // Every layer a filter blurs, as far as the blur reads round it; and every backdrop. A blurred layer with
        // nothing that changed inside it, and not itself inside something that changed, comes out the same: it is
        // painted only as far round the rectangle as its blur reads, not whole (the vanilla pages' blurred panorama).
        List<Rectangle2D> layers = new ArrayList<>();
        Map<Element, Rectangle2D> still = new IdentityHashMap<>();
        Map<Element, Double> stillReach = new IdentityHashMap<>();
        List<Backdrop> backdrops = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            Painter.Hit h = hits.get(i);
            Style s = f.style(h.element());
            if (s == null) continue;
            double blur = blurReach(s.filter, s);
            if (blur > 0 && !still.containsKey(h.element())) {
                Rectangle2D b = extent(f, h.element());
                Rectangle2D layer = new Rectangle2D.Double(b.getX() - blur, b.getY() - f.scrollY() - blur, b.getWidth() + 2 * blur, b.getHeight() + 2 * blur);
                if (apart(h.element(), changed)) {
                    still.put(h.element(), layer);
                    stillReach.put(h.element(), blur);
                } else {
                    layers.add(layer);
                }
            }
            if (!s.backdropFilter.equals("none")) {
                Rectangle2D b = h.shape().getBounds2D();
                Rectangle2D box = new Rectangle2D.Double(b.getX(), b.getY() - f.scrollY(), b.getWidth(), b.getHeight());
                if (box.intersects(view)) backdrops.add(new Backdrop(box, i));
            }
        }
        // A backdrop reads the margin as painted, still layers included: there they are painted whole, as before.
        if (!backdrops.isEmpty()) {
            layers.addAll(still.values());
            still.clear();
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
        // A still blurred layer the rectangle meets is painted round it as far as its blur reads (all of them added up,
        // for one nested in another), so its pixels in the rectangle blur what a whole picture's do; the rest is put back.
        double margin = 0;
        for (Map.Entry<Element, Rectangle2D> e : still.entrySet()) {
            if (e.getValue().intersects(commit)) margin += stillReach.get(e.getKey());
        }
        if (margin > 0) {
            Rectangle2D reads = grow(commit, margin);
            if (pastEdge(f, reads)) return new Region(null, null, Why.PICTURE_EDGE);
            paint = reads.createIntersection(view);
        }
        return new Region(whole(commit, f, view), whole(paint, f, view), Why.PARTIAL);
    }

    /** Whether [layer] and every element in [changed] are apart: none of them is [layer], inside it, or round it. */
    private static boolean apart(Element layer, Set<Element> changed) {
        for (Element e = layer; e != null; e = e.parent()) if (changed.contains(e)) return false;
        for (Element c : changed) {
            for (Element e = c; e != null; e = e.parent()) if (e == layer) return false;
        }
        return true;
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
     * which reorders the list without moving anything else. A box that is in only one of the two (a tooltip that
     * hovering shows, with {@code display}) must be inside [region] too.
     */
    private static boolean sameLayout(HtmlRenderer.Frame a, HtmlRenderer.Frame b, Rectangle2D region) {
        Map<Element, List<Rectangle2D>> before = new IdentityHashMap<>();
        for (Painter.Hit h : a.hits()) before.computeIfAbsent(h.element(), k -> new ArrayList<>()).add(h.shape().getBounds2D());
        Map<Element, Integer> seen = new IdentityHashMap<>();
        for (Painter.Hit h : b.hits()) {
            List<Rectangle2D> boxes = before.get(h.element());
            int n = seen.merge(h.element(), 1, Integer::sum) - 1;
            Rectangle2D rb = h.shape().getBounds2D();
            if (boxes == null || n >= boxes.size()) {
                if (!inside(region, rb, b)) return false;
                continue;
            }
            Rectangle2D ra = boxes.get(n);
            if (Math.abs(ra.getX() - rb.getX()) < 0.5 && Math.abs(ra.getY() - rb.getY()) < 0.5
                    && Math.abs(ra.getWidth() - rb.getWidth()) < 0.5 && Math.abs(ra.getHeight() - rb.getHeight()) < 0.5) continue;
            if (!inside(region, ra, a) || !inside(region, rb, b)) return false;
        }
        // Boxes gone (a tooltip hidden again).
        for (Map.Entry<Element, List<Rectangle2D>> e : before.entrySet()) {
            List<Rectangle2D> boxes = e.getValue();
            for (int n = seen.getOrDefault(e.getKey(), 0); n < boxes.size(); n++) if (!inside(region, boxes.get(n), a)) return false;
        }
        return true;
    }

    /** Whether [box] (page CSS pixels of [f]) is inside [region] (of [f]'s picture). */
    private static boolean inside(Rectangle2D region, Rectangle2D box, HtmlRenderer.Frame f) {
        return region.contains(new Rectangle2D.Double(box.getX(), box.getY() - f.scrollY(), box.getWidth(), box.getHeight()));
    }

    private static int[] pixels(BufferedImage img) {
        return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }
}
