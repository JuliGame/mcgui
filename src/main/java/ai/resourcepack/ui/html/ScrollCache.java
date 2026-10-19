package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.style.Style;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * A page painted taller than the view showing it, so that a scroll inside it is a copy of rows, not a new
 * picture. Painting is nearly all of a picture's cost (style and layout are a few ms of 10 to 80), and a
 * scroll changes nothing but where the view is, so the rows a scroll brings in are worth painting before
 * they are asked for, a few views' worth at a time.
 *
 * <p>Of one document, in one hover and press state, at one size: whoever keeps one (a session, one at
 * most) drops it when any of those change, or paints the change into it ({@link #patched}). A page with
 * something {@code position: fixed} is never cut from one, since what is fixed moves on the page with
 * every scroll; nor is a view at a fraction of a pixel, which a copy of whole rows cannot show (sessions
 * scroll by whole pixels, {@link #snap}). A view cut from one matches a picture drawn on its own at the
 * same place, blurs at its edges included ({@code HtmlRenderer.paintBands}).
 *
 * <p>Used by one thread at a time; {@link #idleNanos} may be read from any.
 */
public final class ScrollCache {
    /** How many views tall the picture is at most: four of a 2048 x 1152 view are 36 MiB, and seven notches of the wheel each way. */
    public static final int VIEWS = 4;

    private final int width, height;
    /** The tall picture, with the hit regions and styles of the page as it shows it; its scrollY is where it starts. */
    private HtmlRenderer.Frame strip;
    /** What views are copied into: the picture handed out is always this one. */
    private final BufferedImage view;
    private volatile long used = System.nanoTime();

    private ScrollCache(HtmlRenderer.Frame strip, int width, int height) {
        this.strip = strip;
        this.width = width;
        this.height = height;
        this.view = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
    }

    /**
     * Paints up to [views] views of [page]'s [document] round a [width] × [height] view at [scrollY] (more ahead of
     * a scroll from [from] than behind), or null if the page cannot be cut from one. Gives up with
     * {@link RenderAbandoned} after [timeoutMs] (0 for never).
     */
    public static ScrollCache paint(Page page, org.jsoup.nodes.Document document, int width, int height, double scale, double scrollY, double from,
                                    Selector.State state, int views, long timeoutMs) {
        HtmlRenderer.Frame strip = HtmlRenderer.renderStrip(page, document, width, height, scale, scrollY, scrollY >= from, views * height, state, timeoutMs);
        return possible(strip) ? new ScrollCache(strip, width, height) : null;
    }

    /** Whether a view of [f]'s page can be cut from a taller picture: nothing on it is fixed to the view. */
    public static boolean possible(HtmlRenderer.Frame f) {
        for (Style s : f.styles().values()) if (s.position.equals("fixed")) return false;
        return true;
    }

    /** [y] (CSS px) moved up to a whole pixel at [scale]: where a view can be cut from a tall picture. */
    public static double snap(double y, double scale) {
        return Math.floor(y * scale + 1e-6) / scale;
    }

    /** Device pixels a map is tall. */
    public static final int TILE = 128;

    /**
     * How many whole map rows a view [rows] maps tall moved when it scrolled from [from] to [to] (CSS px) at
     * [scale]: positive when the page moved up, so a canvas showing it can be moved by that many rows and keep
     * the maps its viewers already have. 0 when the move is not a whole number of rows, or is a whole view or more.
     */
    public static int tileRows(double from, double to, double scale, int rows) {
        double px = (to - from) * scale;
        long whole = Math.round(px);
        if (Math.abs(px - whole) > 1e-3 || whole == 0 || whole % TILE != 0) return 0;
        long k = whole / TILE;
        return Math.abs(k) < rows ? (int) k : 0;
    }

    /** Whether this is a picture of [document] of [page] for a [width] × [height] view at [scale]. */
    public boolean of(Page page, org.jsoup.nodes.Document document, int width, int height, double scale) {
        return strip.page() == page && strip.document() == document && this.width == width && this.height == height && strip.scale() == scale;
    }

    /** Where in the picture (its row) a view at [scrollY] starts, or -1 if the picture cannot show it. */
    private int row(double scrollY) {
        double r = (clamp(scrollY) - strip.scrollY()) * strip.scale();
        long row = Math.round(r);
        return Math.abs(r - row) < 1e-6 && row >= 0 && row + height <= strip.image().getHeight() ? (int) row : -1;
    }

    private double clamp(double scrollY) {
        return Math.max(0, Math.min(scrollY, strip.maxScroll()));
    }

    /** Whether a view at [scrollY] can be cut from this. */
    public boolean covers(double scrollY) {
        return row(scrollY) >= 0;
    }

    /** The view at [scrollY], which must be {@link #covers covered}. Its image is reused by the next call. */
    public HtmlRenderer.Frame frame(double scrollY) {
        int row = row(scrollY);
        if (row < 0) throw new IllegalArgumentException("Not in the picture: " + scrollY);
        int[] from = ((DataBufferInt) strip.image().getRaster().getDataBuffer()).getData();
        int[] to = ((DataBufferInt) view.getRaster().getDataBuffer()).getData();
        System.arraycopy(from, row * width, to, 0, width * height);
        used = System.nanoTime();
        HtmlRenderer.Frame s = strip;
        return new HtmlRenderer.Frame(view, s.scale(), s.viewportWidth(), s.viewportHeight(), s.pageHeight(), clamp(scrollY), s.hits(), s.styles(),
                s.document(), s.page());
    }

    /** The tall picture as a frame: what a repaint of part of it starts from (and paints into). */
    public HtmlRenderer.Frame strip() {
        return strip;
    }

    /** After [repainted], a repaint of part of {@link #strip()} into its own image, for a new hover or press state. */
    public void patched(HtmlRenderer.Frame repainted) {
        if (repainted.image() != strip.image()) throw new IllegalArgumentException("Not a repaint of this picture");
        strip = repainted;
    }

    /** How long since a view was last cut from this. */
    public long idleNanos() {
        return System.nanoTime() - used;
    }

    /** Pixel memory held: the tall picture and the view. */
    public long bytes() {
        return 4L * width * (strip.image().getHeight() + height);
    }
}
