package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Declaration;
import ai.resourcepack.ui.html.css.Stylesheet;
import ai.resourcepack.ui.html.paint.Painter;
import ai.resourcepack.ui.html.style.Style;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * What players' copies of one personal page have in common: a page filled with a value of each player's own (their
 * name) differs between two players only where those values are, so most of what one player's copy draws is what
 * another's would. Two uses, both held to one invariant: <b>what a player is shown is, to the pixel, what their own copy
 * of the page draws</b>, and their clicks, hit regions and commands are their own copy's.
 *
 * <ul>
 * <li>A hover repaint one player's copy drew is another's ({@link #key}, {@link #translate}) when both pictures are laid
 * out alike round what the repaint painted (every box and line of text there, and whatever paints there from further
 * off, the same element in the same place) and neither player's own values paint anywhere near it ({@link #clear}).
 * Its pixels are then the pixels the other copy's own repaint makes; its frame is the other copy's: its own elements,
 * and away from the repaint its own boxes and styles.
 * <li>A first picture one player's copy drew is another's with only that player's own values repainted over it
 * ({@link #retarget}, then {@link Repaint#partial} of {@link Copy#personal}), which falls back to a whole picture when
 * anything else would move.
 * </ul>
 *
 * <p>Copies are matched by place: two copies of a page filled from the same template ({@code Placeholders.Filled})
 * hold the same elements in the same order, the n-th of one being the n-th of the other. Only a copy as it was filled
 * (nothing clicked has changed it) is a {@link Copy}. An element is a player's own ({@link Copy#personal}) if its own
 * text holds a player's value, or an attribute of it that can change how it is drawn does: one that is painted or
 * loaded (src, value...), or one a style sheet reads. Attributes nothing draws ({@code data-*} that no selector reads,
 * {@code href}, {@code title}) are left out: what a click on them runs is worked out from the clicking player's own
 * copy anyway.
 */
public final class Personal {
    private Personal() {}

    /** Attributes a personal value in which never changes what is drawn, unless a style sheet reads them. */
    private static final Set<String> UNDRAWN = Set.of("href", "title", "target", "rel", "download", "hreflang", "ping", "referrerpolicy", "name", "for");

    /** Room (CSS px) round a player's own text for glyphs that reach past the box of their line, as a fraction of its font size. */
    private static final double OVERHANG = 0.35;
    /** Room (CSS px) round a repaint's painted area: what a painter draws round the part it keeps (Painter.drawnBounds). */
    private static final double SLACK = 4;

    /** One player's copy of a personal page, as it was filled. */
    public static final class Copy {
        public final Document doc;
        public final Page page;
        /** What the copy was filled from, with a mark where each of the player's own values went. */
        public final String template;
        private final Element[] order;
        private final Map<Element, Integer> index;
        /** The elements whose drawing may be the player's own (see the class). */
        private final Set<Element> personal;

        // The last picture measured (guarded by this), and where the player's own values paint in it. Only its identity
        // is wanted: held weakly, so a copy kept by a first picture does not keep a whole picture of some session's.
        private java.lang.ref.WeakReference<HtmlRenderer.Frame> measured;
        private List<Rectangle2D> ink;

        private Copy(Document doc, Page page, String template, Element[] order, Map<Element, Integer> index, Set<Element> personal) {
            this.doc = doc;
            this.page = page;
            this.template = template;
            this.order = order;
            this.index = index;
            this.personal = personal;
        }

        /** The elements whose drawing may be the player's own: what a first picture taken from another copy repaints. */
        public Set<Element> personal() {
            return Collections.unmodifiableSet(personal);
        }

        int indexOf(Element e) {
            Integer i = index.get(e);
            return i == null ? -1 : i;
        }

        /** This copy's counterpart of [e], an element of [from]; null if there is none. */
        Element counterpart(Element e, Copy from) {
            int i = from.indexOf(e);
            return i < 0 || i >= order.length ? null : order[i];
        }

        private List<Integer> indices(List<Element> key) {
            if (key.isEmpty()) return List.of();
            Integer[] out = new Integer[key.size()];
            for (int i = 0; i < out.length; i++) {
                Integer n = index.get(key.get(i));
                if (n == null) return null;
                out[i] = n;
            }
            return List.of(out);
        }

        /** Where the player's own values paint in [f] ({@link #ink}): worked out once a picture. */
        private synchronized void measure(HtmlRenderer.Frame f) {
            if (measured != null && measured.get() == f) return;
            ink = ink(f, personal);
            measured = new java.lang.ref.WeakReference<>(f);
        }
    }

    /**
     * The copy each document is, if one was made of it, held weakly both ways: a copy refers to its document (and to
     * every element in it), so a copy held strongly here would keep its document, and every copy ever made, for good.
     * It lives as long as something that needs it holds it: the session showing the document, a first picture of it
     * (FirstPictures), and a repaint drawn over it and shared with other copies ({@link #share}).
     */
    private static final Map<Document, java.lang.ref.WeakReference<Copy>> COPIES = Collections.synchronizedMap(new WeakHashMap<>());
    /** The attributes each page revision's style sheet reads (null: it reads them all, through attr()). */
    private static final Map<Page, Set<String>> READ = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * [doc] (a copy of [page] filled from [template], not changed since) as a {@link Copy}, whose player's own values
     * are in the own text of [text] and in the attributes [attrs] names. {@link #copy} finds it for as long as something
     * holds it (see {@link #COPIES}).
     */
    public static Copy of(Document doc, Page page, String template, Set<Element> text, Map<Element, List<String>> attrs) {
        Copy known = copy(doc);
        if (known != null) return known;
        Elements all = doc.getAllElements();
        Element[] order = all.toArray(new Element[0]);
        Map<Element, Integer> index = new IdentityHashMap<>(order.length * 2);
        for (int i = 0; i < order.length; i++) index.put(order[i], i);
        Set<Element> personal = Collections.newSetFromMap(new IdentityHashMap<>());
        personal.addAll(text);
        Set<String> read = READ.computeIfAbsent(page, Personal::attributesRead);
        for (Map.Entry<Element, List<String>> e : attrs.entrySet()) {
            for (String name : e.getValue()) {
                if (drawn(name, read)) {
                    personal.add(e.getKey());
                    break;
                }
            }
        }
        Copy c = new Copy(doc, page, template, order, index, personal);
        COPIES.put(doc, new java.lang.ref.WeakReference<>(c));
        return c;
    }

    /** The {@link Copy} [doc] is, or null. */
    public static Copy copy(Document doc) {
        java.lang.ref.WeakReference<Copy> c = doc == null ? null : COPIES.get(doc);
        return c == null ? null : c.get();
    }

    private static boolean drawn(String attr, Set<String> read) {
        String name = attr.toLowerCase(Locale.ROOT);
        if (read == null || read.contains(name)) return true;
        return !(name.startsWith("data-") || name.startsWith("aria-") || name.startsWith("ui:") || UNDRAWN.contains(name));
    }

    /** Every attribute name [page]'s style sheet tests (lower case), or null if it reads attribute values into what it draws (attr()). */
    private static Set<String> attributesRead(Page page) {
        Set<String> out = new HashSet<>();
        for (Stylesheet.Rule r : page.styles.rules()) {
            r.selector().attributes(out);
            for (Declaration d : r.declarations()) if (d.value() != null && d.value().toLowerCase(Locale.ROOT).contains("attr(")) return null;
        }
        Set<String> lower = new HashSet<>();
        for (String s : out) lower.add(s.toLowerCase(Locale.ROOT));
        return lower;
    }

    /**
     * What a hover repaint of a personal copy is kept under: the template, the size, scale and scroll, and the state
     * drawn and wanted, by place. A repaint found under it is another copy's only if the two pictures are laid out alike
     * round it and neither shows its player's own values there ({@link #translate}, {@link #clear}).
     */
    public record Key(Page page, String template, int width, int height, double scale, double scroll,
                      List<Integer> drawnHover, List<Integer> drawnPress, List<Integer> hoverKey, List<Integer> pressKey) {}

    /**
     * What a repaint from [before] (a picture of [c]'s copy, drawn with [drawnHover] and [drawnPress]) to [hoverKey] and
     * [pressKey] is kept under, to be found by other players' copies; null if it is not a picture of [c].
     */
    public static Key key(Copy c, HtmlRenderer.Frame before, List<Element> drawnHover, List<Element> drawnPress,
                          List<Element> hoverKey, List<Element> pressKey) {
        if (before.document() != c.doc || before.page() != c.page) return null;
        List<Integer> dh = c.indices(drawnHover), dp = c.indices(drawnPress), hk = c.indices(hoverKey), pk = c.indices(pressKey);
        if (dh == null || dp == null || hk == null || pk == null) return null;
        return new Key(c.page, c.template, before.image().getWidth(), before.image().getHeight(), before.scale(), before.scrollY(), dh, dp, hk, pk);
    }

    /**
     * Whether nothing of the player's own in [before] (a picture of [c]'s copy) paints within reach of what the repaint
     * [p] changes ({@link #zone}). Then that repaint's pixels are the same over any copy laid out alike there: none of
     * them depends on a player's own value.
     */
    public static boolean clear(Copy c, HtmlRenderer.Frame before, Repaint.Prepared p) {
        if (p.paint() == null) return false;
        c.measure(before);
        List<Rectangle2D> ink;
        synchronized (c) {
            ink = c.ink;
        }
        Rectangle2D near = zone(p, before);
        for (Rectangle2D r : ink) if (r.intersects(near)) return false;
        return true;
    }

    /**
     * Where what [p] (a repaint of [before]) changes can depend on anything, in page CSS pixels: round the pixels it
     * changes, as far as the blurs it painted round them read (what it painted past what it keeps), and as far again as
     * any blur on the page reads, which a blurred layer met whole reaches, and a little for a painter's rounding.
     */
    static Rectangle2D zone(Repaint.Prepared p, HtmlRenderer.Frame before) {
        java.awt.Rectangle at = p.at();
        double s = before.scale();
        Rectangle2D c = p.changed(), pt = p.paint();
        double margin = Math.max(0, Math.max(Math.max(c.getMinX() - pt.getMinX(), pt.getMaxX() - c.getMaxX()),
                Math.max(c.getMinY() - pt.getMinY(), pt.getMaxY() - c.getMaxY())));
        double blur = 2 * (ai.resourcepack.ui.html.paint.Painter.blurReach(before.styles().values(), s) + 3) / s;
        for (Style st : before.styles().values()) if (st != null) blur = Math.max(blur, Repaint.blurReach(st, s));
        double r = margin + blur + SLACK;
        return new Rectangle2D.Double(at.x / s - r, at.y / s + before.scrollY() - r, at.width / s + 2 * r, at.height / s + 2 * r);
    }

    /**
     * [p] (worked out over a picture of another copy, see {@link #share}) as a repaint of the same state of [to]'s copy,
     * whose picture is [toBefore]; null if it is not one. It is when the two pictures are laid out alike round what [p]
     * painted: every box and line of text there, and anything that paints there from further off (a shadow), is the
     * same element by place, in the same place and painting order, in both. With nothing of either player's own there
     * ({@link #clear}, for each), [p]'s pixels are then the ones [to]'s own repaint draws. Its frame is [to]'s own: round
     * what it painted, the repaint's boxes and styles (the same for [to]'s copy); elsewhere, [toBefore]'s, which the
     * repaint leaves as they were.
     */
    public static Repaint.Prepared translate(Repaint.Prepared p, Copy from, Copy to, HtmlRenderer.Frame toBefore) {
        if (from == to) return p;
        HtmlRenderer.Frame f = p.frame(), was = p.origin();
        if (was == null || f.document() != from.doc || was.document() != from.doc || toBefore.document() != to.doc
                || from.order.length != to.order.length || from.page != to.page) return null;
        if (was.scrollY() != toBefore.scrollY() || was.scale() != toBefore.scale() || was.viewportWidth() != toBefore.viewportWidth()
                || was.viewportHeight() != toBefore.viewportHeight()) return null;
        Rectangle2D zone = zone(p, was);
        // What is near: by place in the copies, every element with a box in either picture that reaches the zone.
        boolean[] near = new boolean[to.order.length];
        markNear(was, from, zone, near);
        markNear(toBefore, to, zone, near);
        markNear(f, from, zone, near);
        // Near, the two pictures are the same, box for box, in the same order.
        if (!sameNear(was, from, toBefore, to, near)) return null;
        for (Element e : to.personal) if (near[to.indexOf(e)]) return null;
        for (Element e : from.personal) if (near[from.indexOf(e)]) return null;
        // The repaint's frame round it; [to]'s own elsewhere, in the repaint's painting order.
        Map<Element, List<Painter.Hit>> own = new IdentityHashMap<>();
        for (Painter.Hit h : toBefore.hits()) {
            if (!near[to.indexOf(h.element())]) own.computeIfAbsent(h.element(), k -> new ArrayList<>()).add(h);
        }
        List<Painter.Hit> hits = new ArrayList<>(toBefore.hits().size() + 8);
        Set<Element> placed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Painter.Hit h : f.hits()) {
            int i = from.indexOf(h.element());
            if (i < 0) return null;
            Element e = to.order[i];
            if (near[i]) {
                hits.add(new Painter.Hit(h.shape(), h.clip(), e, h.pointer()));
            } else if (placed.add(e)) {
                List<Painter.Hit> mine = own.get(e);
                if (mine == null) return null;
                hits.addAll(mine);
            }
        }
        if (!placed.containsAll(own.keySet())) return null;
        Map<Element, Style> styles = new IdentityHashMap<>(toBefore.styles().size() * 2);
        for (Map.Entry<Element, Style> s : toBefore.styles().entrySet()) styles.put(s.getKey(), s.getValue());
        for (Map.Entry<Element, Style> s : f.styles().entrySet()) {
            int i = from.indexOf(s.getKey());
            if (i < 0) return null;
            if (near[i]) styles.put(to.order[i], s.getValue());
        }
        return p.with(new HtmlRenderer.Frame(null, f.scale(), f.viewportWidth(), f.viewportHeight(), toBefore.pageHeight(), f.scrollY(), hits, styles,
                to.doc, to.page));
    }

    /**
     * [p], worked out over [before] (a picture of a personal copy), as one that other copies may take ({@link #translate}),
     * holding that copy, which those find by its document ({@link #copy}), for as long as it is kept.
     */
    public static Repaint.Prepared share(Repaint.Prepared p, HtmlRenderer.Frame before) {
        return p.over(before, copy(before.document()));
    }

    /** Marks in [near] (by place in [c]) every element with a box in [f] that, with what it paints outside itself, reaches [zone]. */
    private static void markNear(HtmlRenderer.Frame f, Copy c, Rectangle2D zone, boolean[] near) {
        Map<Element, Double> reach = new IdentityHashMap<>();
        for (Painter.Hit h : f.hits()) {
            int i = c.indexOf(h.element());
            if (i < 0 || near[i]) continue;
            double r = reach.computeIfAbsent(h.element(), e -> Repaint.paintsOutside(f.style(e), f.scale()) + 1);
            Rectangle2D b = h.shape().getBounds2D();
            if (b.getMaxX() + r > zone.getMinX() && b.getMinX() - r < zone.getMaxX() && b.getMaxY() + r > zone.getMinY() && b.getMinY() - r < zone.getMaxY()) {
                near[i] = true;
            }
        }
    }

    /** Whether the boxes of the [near] elements in [a] (of copy [ca]) and [b] (of [cb]) are the same, in the same painting order. */
    private static boolean sameNear(HtmlRenderer.Frame a, Copy ca, HtmlRenderer.Frame b, Copy cb, boolean[] near) {
        List<Painter.Hit> x = nearHits(a, ca, near), y = nearHits(b, cb, near);
        if (x.size() != y.size()) return false;
        for (int i = 0; i < x.size(); i++) {
            Painter.Hit p = x.get(i), q = y.get(i);
            if (ca.indexOf(p.element()) != cb.indexOf(q.element()) || p.pointer() != q.pointer()) return false;
            if (!p.shape().getBounds2D().equals(q.shape().getBounds2D())) return false;
            if (p.clip() == null ? q.clip() != null : q.clip() == null || !p.clip().getBounds2D().equals(q.clip().getBounds2D())) return false;
        }
        return true;
    }

    private static List<Painter.Hit> nearHits(HtmlRenderer.Frame f, Copy c, boolean[] near) {
        List<Painter.Hit> out = new ArrayList<>();
        for (Painter.Hit h : f.hits()) {
            int i = c.indexOf(h.element());
            if (i >= 0 && near[i]) out.add(h);
        }
        return out;
    }

    /**
     * [f], a picture of [from]'s copy, as a picture of [to]'s copy before its own values are drawn in: the same pixels
     * and boxes, every element [to]'s counterpart. What {@link Repaint#partial} of [to]'s {@link Copy#personal} then
     * repaints over it is [to]'s own first picture. Null if the copies do not match.
     */
    public static HtmlRenderer.Frame retarget(HtmlRenderer.Frame f, Copy from, Copy to) {
        if (f.document() != from.doc || from.order.length != to.order.length || from.page != to.page) return null;
        List<Painter.Hit> hits = new ArrayList<>(f.hits().size());
        for (Painter.Hit h : f.hits()) {
            Element e = to.counterpart(h.element(), from);
            if (e == null) return null;
            hits.add(new Painter.Hit(h.shape(), h.clip(), e, h.pointer()));
        }
        Map<Element, Style> styles = new IdentityHashMap<>(f.styles().size() * 2);
        for (Map.Entry<Element, Style> s : f.styles().entrySet()) {
            Element e = to.counterpart(s.getKey(), from);
            if (e == null) return null;
            styles.put(e, s.getValue());
        }
        return new HtmlRenderer.Frame(f.image(), f.scale(), f.viewportWidth(), f.viewportHeight(), f.pageHeight(), f.scrollY(), hits, styles, to.doc, to.page);
    }

    /**
     * Where [personal]'s elements may paint in [f] (page CSS pixels): each of their boxes and lines, grown by what
     * they paint outside themselves (shadows, their own blur), by what every element round them paints outside itself
     * (a blurred layer spreads what is in it), and for text,
     * by what its glyphs may reach past its line.
     */
    static List<Rectangle2D> ink(HtmlRenderer.Frame f, Set<Element> personal) {
        List<Rectangle2D> out = new ArrayList<>();
        if (personal.isEmpty()) return out;
        Map<Element, Double> reach = new IdentityHashMap<>();
        for (Painter.Hit hit : f.hits()) {
            Element e = hit.element();
            if (!personal.contains(e)) continue;
            Double r = reach.get(e);
            if (r == null) {
                Style s = f.style(e);
                double grow = Repaint.paintsOutside(s, f.scale()) + (s == null ? 16 : OVERHANG * s.fontSize) + 1;
                for (Element a = e.parent(); a != null; a = a.parent()) {
                    Style as = f.style(a);
                    if (as != null) grow += Repaint.paintsOutside(as, f.scale());
                }
                reach.put(e, r = grow);
            }
            Rectangle2D b = hit.shape().getBounds2D();
            out.add(new Rectangle2D.Double(b.getX() - r, b.getY() - r, b.getWidth() + 2 * r, b.getHeight() + 2 * r));
        }
        return out;
    }
}
