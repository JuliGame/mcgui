package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.layout.Box;
import ai.resourcepack.ui.html.layout.BoxBuilder;
import ai.resourcepack.ui.html.layout.Layout;
import ai.resourcepack.ui.html.paint.Painter;
import ai.resourcepack.ui.html.style.Style;
import ai.resourcepack.ui.html.style.Styler;
import org.jsoup.nodes.Element;

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

/**
 * Draws a {@link Page}: styles it, lays it out and paints it at a given size, scroll position and
 * hover state. Each call works from scratch, so it is safe to call from any thread for any page.
 */
public final class HtmlRenderer {
    private HtmlRenderer() {}

    /**
     * A drawn page.
     *
     * @param viewportWidth  in CSS pixels
     * @param pageHeight     the whole page's height in CSS pixels (scrolling stops at pageHeight - viewportHeight)
     * @param scrollY        where the view actually is, after clamping
     */
    public record Frame(BufferedImage image, double scale, double viewportWidth, double viewportHeight, double pageHeight, double scrollY,
                        List<Painter.Hit> hits, Map<Element, Style> styles, org.jsoup.nodes.Document document, Page page) {

        /** The topmost element at (x, y) in CSS pixels of the viewport that takes the pointer, or null. */
        public Element elementAt(double x, double y) {
            Point2D p = new Point2D.Double(x, y + scrollY);
            for (int i = hits.size() - 1; i >= 0; i--) {
                Painter.Hit h = hits.get(i);
                if (!h.pointer() || h.clip() != null && !h.clip().contains(p)) continue;
                if (h.shape().contains(p)) return h.element();
            }
            return null;
        }

        public Style style(Element e) {
            return e == null ? null : styles.get(e);
        }

        public double maxScroll() {
            return Math.max(0, pageHeight - viewportHeight);
        }

        /** Where [e] starts on the page (its first painted piece), in CSS pixels from the page's top; NaN if not drawn. */
        public double topOf(Element e) {
            for (Painter.Hit h : hits) if (h.element() == e) return h.shape().getBounds2D().getY();
            return Double.NaN;
        }
    }

    /** Hover and press state: [hovered] and [pressed] are the elements under the pointer (their ancestors are added). */
    public static Selector.State state(Element hovered, Element pressed) {
        return new Selector.State(chain(hovered), chain(pressed));
    }

    private static Set<Element> chain(Element e) {
        if (e == null) return Set.of();
        Set<Element> out = new HashSet<>();
        for (Element p = e; p != null; p = p.parent()) out.add(p);
        return out;
    }

    /**
     * Renders [page] into a [width] × [height] pixel image, [scale] image pixels per CSS pixel,
     * scrolled down by [scrollY] CSS pixels.
     */
    public static Frame render(Page page, int width, int height, double scale, double scrollY, Selector.State state) {
        return render(page, page.document, width, height, scale, scrollY, state);
    }

    /**
     * Renders [document], a copy of [page]'s own (a player's, with their placeholders filled in),
     * using the page's styles, fonts and images.
     */
    public static Frame render(Page page, org.jsoup.nodes.Document document, int width, int height, double scale, double scrollY, Selector.State state) {
        return render(page, document, width, height, scale, scrollY, state, null, null);
    }

    /**
     * Repaints only [clip] (CSS pixels of the picture) of [base], the image of an earlier frame of the
     * same page at the same scroll: what a change of hover state needs. Everything is still styled
     * and laid out, so the frame's hit regions are whole; only the pixels outside the clip are skipped.
     * [base] may be taller than the view (a {@link ScrollCache}'s picture, from [scrollY] down).
     */
    public static Frame render(Page page, org.jsoup.nodes.Document document, int width, int height, double scale, double scrollY,
                               Selector.State state, BufferedImage base, java.awt.geom.Rectangle2D clip) {
        return draw(page, document, width, height, scale, scrollY, base != null ? base.getHeight() : height, state, base, clip);
    }

    /**
     * Renders up to [maxRows] rows (at least the view's [height]) of the page round the view at [scrollY], for a
     * [width] × [height] view: more of them ahead of the scroll ([down]: below the view) than behind. The frame's
     * image is that taller picture and its scrollY where the picture starts, a whole number of pixels from the
     * page's top, so a view at any whole pixel inside it is a copy of its rows ({@link ScrollCache}).
     */
    public static Frame renderStrip(Page page, org.jsoup.nodes.Document document, int width, int height, double scale, double scrollY,
                                    boolean down, int maxRows, Selector.State state) {
        double vw = width / scale, vh = height / scale;
        Laid laid = lay(page, document, vw, vh, state);
        int pageRows = Math.max(height, (int) Math.floor(laid.pageHeight * scale + 1e-6));
        int rows = Math.max(height, Math.min(maxRows, pageRows));
        int spare = rows - height, behind = down ? spare / 4 : spare - spare / 4;
        int topRow = Math.max(0, Math.min(pageRows - rows, (int) Math.round(scrollY * scale) - behind));
        return paint(page, laid, new BufferedImage(width, rows, BufferedImage.TYPE_INT_ARGB_PRE), scale, vw, vh, topRow / scale, true, null, document);
    }

    /** A document styled and laid out for a view, not painted yet. */
    private record Laid(Styler styler, Box root, double pageHeight) {}

    private static Laid lay(Page page, org.jsoup.nodes.Document document, double vw, double vh, Selector.State state) {
        Element html = document.selectFirst("html");
        if (html == null) html = document;
        Styler styler = new Styler(Page.UA, page.styles, vw, vh, state, page.uri);
        styler.run(html);
        Box root = new BoxBuilder(styler, page, page.uri).build(html);
        double pageHeight = new Layout(page.shaper, vw, vh).run(root);
        return new Laid(styler, root, pageHeight);
    }

    /** A picture [rows] tall (the view's [height], or a {@link ScrollCache}'s more) from [scrollY] down. */
    private static Frame draw(Page page, org.jsoup.nodes.Document document, int width, int height, double scale, double scrollY, int rows,
                              Selector.State state, BufferedImage base, java.awt.geom.Rectangle2D clip) {
        double vw = width / scale, vh = height / scale;
        Laid laid = lay(page, document, vw, vh, state);
        double scroll = Math.max(0, Math.min(scrollY, laid.pageHeight - rows / scale));
        // Premultiplied: Java2D blends onto it directly, where a plain ARGB surface costs a conversion of
        // every pixel both ways on every fill. The page is painted on opaque white, so nothing reads alpha.
        BufferedImage img = base != null ? base : new BufferedImage(width, rows, BufferedImage.TYPE_INT_ARGB_PRE);
        return paint(page, laid, img, scale, vw, vh, scroll, base == null && clip == null, clip, document);
    }

    /** Paints [laid] into [img] from [scroll] down: [whole], in bands at once; else, within [clip], over what is there. */
    private static Frame paint(Page page, Laid laid, BufferedImage img, double scale, double vw, double vh, double scroll, boolean whole,
                               java.awt.geom.Rectangle2D clip, org.jsoup.nodes.Document document) {
        List<Painter.Hit> hits;
        int reach = whole ? Painter.blurReach(laid.styler.styles.values(), scale) : 0;
        int bands = whole ? Math.max(1, Math.min(MAX_BANDS, img.getHeight() / MIN_BAND_ROWS)) : 1;
        if (bands > 1 || reach > 0) {
            hits = paintBands(page, laid.root, img, bands, reach, scale, vw, vh, scroll, laid.pageHeight);
        } else {
            Graphics2D g = img.createGraphics();
            g.scale(scale, scale);
            if (clip != null) g.clip(clip);
            Painter painter = new Painter(page.shaper, page, vw, vh, scroll);
            painter.paint(g, laid.root, img, scale);
            g.dispose();
            hits = painter.hits;
        }
        return new Frame(img, scale, vw, vh, laid.pageHeight, scroll, hits, new IdentityHashMap<>(laid.styler.styles), document, page);
    }

    /** Full pictures are painted as up to this many horizontal bands at once, none under this many rows. */
    private static final int MAX_BANDS = 4, MIN_BAND_ROWS = 192;
    /** Shared by every session's full repaints; leaves a core for the game. */
    private static final ForkJoinPool PAINTERS = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));

    /**
     * Paints [root] into [img] as [bands] horizontal bands at once, each with its own painter into its own
     * picture, then copies each band's rows in. Styling and layout are done once and only read here.
     * Each band also paints [reach] rows past its edges, so a backdrop-filter near an edge blurs what a
     * whole picture would have under it rather than another band's half-painted rows. That holds at the
     * picture's own top and bottom too, where there is more page: so a blur looks the same wherever the
     * view is, and a view cut from a taller picture ({@link ScrollCache}) matches one drawn on its own.
     * Returns the hit regions (every band's painter finds them all; the first band's are kept).
     */
    private static List<Painter.Hit> paintBands(Page page, Box root, BufferedImage img, int bands, int reach, double scale,
                                                double vw, double vh, double scroll, double pageHeight) {
        int width = img.getWidth(), height = img.getHeight();
        int[] out = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        // Whole rows of page above and below the picture.
        int above = (int) Math.floor(scroll * scale + 1e-6), below = Math.max(0, (int) Math.floor((pageHeight - scroll) * scale + 1e-6) - height);
        List<ForkJoinTask<List<Painter.Hit>>> tasks = new ArrayList<>(bands);
        for (int b = 0; b < bands; b++) {
            int y0 = height * b / bands, y1 = height * (b + 1) / bands;
            // Twice the reach: a blur in the margin itself reads up to one reach further out.
            int top = Math.max(-above, y0 - 2 * reach), bottom = Math.min(height + below, y1 + 2 * reach);
            tasks.add(PAINTERS.submit(() -> {
                BufferedImage band = new BufferedImage(width, bottom - top, BufferedImage.TYPE_INT_ARGB_PRE);
                Graphics2D g = band.createGraphics();
                g.translate(0, -top);
                g.scale(scale, scale);
                // No clip: the band's own edges stop the drawing, so the painter sees exactly what a whole
                // picture's would (a clip changes how Java2D intersects overflow clips, by a pixel).
                Painter painter = new Painter(page.shaper, page, vw, vh, scroll);
                painter.paint(g, root, band, scale);
                g.dispose();
                System.arraycopy(((DataBufferInt) band.getRaster().getDataBuffer()).getData(), (y0 - top) * width, out, y0 * width, (y1 - y0) * width);
                return painter.hits;
            }));
        }
        List<Painter.Hit> hits = null;
        for (ForkJoinTask<List<Painter.Hit>> t : tasks) {
            List<Painter.Hit> h = t.join();
            if (hits == null) hits = h;
        }
        return hits;
    }
}
