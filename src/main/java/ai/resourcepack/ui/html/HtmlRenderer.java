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
     * Repaints only [clip] (CSS pixels of the viewport) of [base], the image of an earlier frame of the
     * same page at the same scroll: what a change of hover state needs. Everything is still styled
     * and laid out, so the frame's hit regions are whole; only the pixels outside the clip are skipped.
     */
    public static Frame render(Page page, org.jsoup.nodes.Document document, int width, int height, double scale, double scrollY,
                               Selector.State state, BufferedImage base, java.awt.geom.Rectangle2D clip) {
        return render(page, document, width, height, scale, scrollY, state, base, clip, clip);
    }

    /**
     * As above, but only [keep] (a rectangle inside [clip]) keeps what was painted: the rest of [clip] is painted
     * so that what is drawn inside [keep] (a backdrop-filter reads past its box) matches a whole picture's,
     * and then put back as it was in [base].
     */
    public static Frame render(Page page, org.jsoup.nodes.Document document, int width, int height, double scale, double scrollY,
                               Selector.State state, BufferedImage base, java.awt.geom.Rectangle2D clip, java.awt.geom.Rectangle2D keep) {
        double vw = width / scale, vh = height / scale;
        Element html = document.selectFirst("html");
        if (html == null) html = document;
        Styler styler = new Styler(Page.UA, page.styles, vw, vh, state, page.uri);
        styler.run(html);
        Box root = new BoxBuilder(styler, page, page.uri).build(html);
        Layout layout = new Layout(page.shaper, vw, vh);
        double pageHeight = layout.run(root);
        double scroll = Math.max(0, Math.min(scrollY, pageHeight - vh));
        // Premultiplied: Java2D blends onto it directly, where a plain ARGB surface costs a conversion of
        // every pixel both ways on every fill. The page is painted on opaque white, so nothing reads alpha.
        BufferedImage img = base != null ? base : new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] ring = null;
        int rx0 = 0, ry0 = 0, rx1 = 0, ry1 = 0;
        if (base != null && clip != null && keep != null && !keep.contains(clip)) {
            // Out to a pixel past the clip, which Java2D may round outwards.
            rx0 = Math.max(0, (int) Math.floor(clip.getX() * scale) - 1);
            ry0 = Math.max(0, (int) Math.floor(clip.getY() * scale) - 1);
            rx1 = Math.min(width, (int) Math.ceil(clip.getMaxX() * scale) + 1);
            ry1 = Math.min(height, (int) Math.ceil(clip.getMaxY() * scale) + 1);
            ring = new int[(rx1 - rx0) * (ry1 - ry0)];
            int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            for (int y = ry0; y < ry1; y++) System.arraycopy(px, y * width + rx0, ring, (y - ry0) * (rx1 - rx0), rx1 - rx0);
        }
        List<Painter.Hit> hits;
        boolean full = base == null && clip == null;
        // A full picture counts itself while it paints, so the ones painting at once share the painter threads.
        int painting = full ? PAINTING.incrementAndGet() : 0;
        try {
            int bands = full ? bandsFor(height, painting) : 1;
            if (bands > 1) {
                hits = paintBands(page, root, img, bands, Painter.blurReach(styler.styles.values(), scale), scale, vw, vh, scroll);
            } else {
                Graphics2D g = img.createGraphics();
                g.scale(scale, scale);
                if (clip != null) g.clip(clip);
                Painter painter = new Painter(page.shaper, page, vw, vh, scroll);
                painter.paint(g, root, img, scale);
                g.dispose();
                hits = painter.hits;
            }
        } finally {
            if (full) PAINTING.decrementAndGet();
        }
        if (ring != null) {
            // Everything in the clip but [keep] goes back as it was.
            int kx0 = Math.max(0, (int) Math.floor(keep.getX() * scale)), ky0 = Math.max(0, (int) Math.floor(keep.getY() * scale));
            int kx1 = Math.min(width, (int) Math.ceil(keep.getMaxX() * scale)), ky1 = Math.min(height, (int) Math.ceil(keep.getMaxY() * scale));
            int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            for (int y = ry0; y < ry1; y++) {
                boolean rowKept = y >= ky0 && y < ky1;
                for (int x = rx0; x < rx1; x++) {
                    if (rowKept && x >= kx0 && x < kx1) continue;
                    px[y * width + x] = ring[(y - ry0) * (rx1 - rx0) + x - rx0];
                }
            }
        }
        return new Frame(img, scale, vw, vh, pageHeight, scroll, hits, new IdentityHashMap<>(styler.styles), document, page);
    }

    /** Full pictures are painted as up to this many horizontal bands at once (1: one painter), none under MIN_BAND_ROWS rows. LoadBench changes it. */
    static volatile int maxBands = 4;
    private static final int MIN_BAND_ROWS = 192;
    /**
     * Whether the painter threads are shared out between the full pictures painting at once (else each takes maxBands).
     * Every band repaints its own margin and walks the whole page, so four bands cost up to 70% more CPU than one
     * painter on a page with a backdrop blur: worth it while the painters are idle, a waste once render threads
     * compete for them (LoadBench, with 16 sessions on 2 render threads: 30% less CPU a frame, and the median hover
     * 40% sooner than either four bands or none). LoadBench changes it to compare.
     */
    static volatile boolean adaptiveBands = true;
    private static final java.util.concurrent.atomic.AtomicInteger PAINTING = new java.util.concurrent.atomic.AtomicInteger();

    /** Bands for a full picture, [painting] pictures (this one included) being painted: each gets its share of the painter threads. */
    private static int bandsFor(int height, int painting) {
        int bands = Math.min(maxBands, height / MIN_BAND_ROWS);
        if (!adaptiveBands || bands <= 1) return bands;
        return Math.max(1, Math.min(bands, PAINTERS.getParallelism() / painting));
    }

    /** Shared by every session's full repaints; leaves a core for the game. */
    private static final ForkJoinPool PAINTERS = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));

    /**
     * Paints [root] into [img] as [bands] horizontal bands at once, each with its own painter into its own
     * picture, then copies each band's rows in. Styling and layout are done once and only read here.
     * Each band also paints [reach] rows past its edges, so a backdrop-filter near an edge blurs what a
     * whole picture would have under it rather than another band's half-painted rows. Returns the hit
     * regions (every band's painter finds them all; the first band's are kept).
     */
    private static List<Painter.Hit> paintBands(Page page, Box root, BufferedImage img, int bands, int reach, double scale,
                                                double vw, double vh, double scroll) {
        int width = img.getWidth(), height = img.getHeight();
        int[] out = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        List<ForkJoinTask<List<Painter.Hit>>> tasks = new ArrayList<>(bands);
        for (int b = 0; b < bands; b++) {
            int y0 = height * b / bands, y1 = height * (b + 1) / bands;
            // Twice the reach: a blur in the margin itself reads up to one reach further out.
            int top = Math.max(0, y0 - 2 * reach), bottom = Math.min(height, y1 + 2 * reach);
            tasks.add(PAINTERS.submit(() -> {
                BufferedImage band = new BufferedImage(width, bottom - top, BufferedImage.TYPE_INT_ARGB_PRE);
                Graphics2D g = band.createGraphics();
                g.translate(0, -top);
                g.scale(scale, scale);
                // No clip: the band's own edges stop the drawing, so the painter sees exactly what a whole
                // picture's would (a clip changes how Java2D intersects overflow clips, by a pixel).
                Painter painter = new Painter(page.shaper, page, vw, vh, scroll).origin(0, top);
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
