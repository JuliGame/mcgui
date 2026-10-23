package ai.resourcepack.ui.html.paint;

import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.html.RenderBudget;
import ai.resourcepack.ui.html.css.Values;
import ai.resourcepack.ui.html.layout.Box;
import ai.resourcepack.ui.html.res.Resources;
import ai.resourcepack.ui.html.style.Len;
import ai.resourcepack.ui.html.style.Style;
import ai.resourcepack.ui.html.text.Fonts;
import ai.resourcepack.ui.html.text.TextShaper;
import org.jsoup.nodes.Element;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Paints a laid-out box tree with Java2D, in CSS pixels (the caller scales), in CSS's painting order:
 * a stacking context's own background, then its negative z-index children, then the normal flow
 * (block backgrounds, then inline content), then positioned children by z-index. Records, in the
 * same order, a hit region per box so a point can be traced back to the element on top.
 */
public final class Painter {
    private final TextShaper shaper;
    private final Resources res;
    private final double viewportW, viewportH;
    private final double scrollY;
    /** Checked per stacking context, box, line and blur row: a picture that takes too long is given up. */
    private final RenderBudget budget;
    /** The surface being painted on, for backdrop-filter. */
    private BufferedImage surface;
    private double deviceScale = 1;
    /** Where the surface's top-left is on the page (device px): a band or a view starts part way down, a layer part way in. */
    private int originX, originY;

    /**
     * Where something was drawn, in page coordinates, and which element it belongs to. [pointer] is false
     * where the pointer goes through it (visibility: hidden, pointer-events: none), as browsers hit-test;
     * such a region still says where its element is (to scroll to it, or to repaint round it).
     */
    public record Hit(Shape shape, Shape clip, Element element, boolean pointer) {}

    public final List<Hit> hits = new ArrayList<>();
    /** False for a painter whose hit regions nobody reads: then it makes none. */
    private boolean recordHits = true;
    /** Page-space clip in force (for hit regions); null for none. */
    private Shape pageClip;
    /** Page-space transform in force (for hit regions). */
    private AffineTransform pageTx = new AffineTransform();

    /**
     * Paints the page on nothing rather than on white, so wherever it paints nothing the picture is transparent: an
     * overlay page ({@code Page.overlay}). What was there is cleared, as white covers it otherwise.
     */
    private final boolean onNothing;

    /**
     * [res] is the page painted, or only where its images come from. Every painter of an overlay page paints it on
     * nothing, so a whole picture, each of its bands and a hover's partial repaint all start from the same background.
     */
    public Painter(TextShaper shaper, Resources res, double viewportW, double viewportH, double scrollY, RenderBudget budget) {
        this.shaper = shaper;
        this.res = res;
        this.viewportW = viewportW;
        this.viewportH = viewportH;
        this.scrollY = scrollY;
        this.budget = budget;
        this.onNothing = res instanceof Page p && p.overlay();
    }

    /**
     * This painter, making no hit regions: for the bands of a picture after the first, which paint the
     * same boxes. A hit region is a transformed copy of a box's shape, and a page with millions of
     * inline fragments ran out of memory making them in every band.
     */
    public Painter withoutHits() {
        recordHits = false;
        return this;
    }

    /**
     * Of the surface, only [keep] (device px) is kept: a band's own rows, or a repaint's area. A layer is then filtered
     * and blended in only round those, as far as its filter's blur reads, not over the band's whole margin, and blurred
     * shadows and backdrops are only worked out there. Not for a band of a page with a backdrop-filter ({@link #backdrops}),
     * whose backdrops read the band's margin as painted; a repaint's area already holds the margin its backdrops read.
     */
    public Painter keeping(java.awt.Rectangle keep) {
        needed = keep;
        return this;
    }

    /**
     * Of the surface, only [kept] (device px, within what {@link #keeping} needs) ends up in the picture: a repaint's
     * own rectangle, inside the margin it paints round it. A blurred layer kept for the page ({@link BlurredLayers})
     * may then be drawn from wherever its pixels there read only what this surface holds of it.
     */
    public Painter kept(java.awt.Rectangle kept) {
        this.kept = kept;
        return this;
    }

    /** Whether anything in [root]'s tree has a backdrop-filter. */
    public static boolean backdrops(Box root) {
        List<Box> todo = new ArrayList<>(List.of(root));
        while (!todo.isEmpty()) {
            Box b = todo.remove(todo.size() - 1);
            if (!b.style.backdropFilter.equals("none")) return true;
            todo.addAll(b.children);
        }
        return false;
    }

    /** Paints the page into [g] (already scaled to CSS pixels) over [surface], the image [g] draws on. */
    public void paint(Graphics2D g, Box root, BufferedImage surface, double scale) {
        this.surface = surface;
        this.deviceScale = scale;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        // The canvas: the root's background, or the body's when the root has none (CSS propagates it).
        Box canvasSource = root;
        if (transparent(root.style)) {
            for (Box c : root.children) {
                if (c.element != null && c.element.normalName().equals("body")) {
                    canvasSource = c;
                    break;
                }
            }
        }
        // All of the page, not only the view: a picture can be taller than the view (a ScrollCache's), and a band
        // paints rows past the picture's edges for the blurs there.
        Rectangle2D all = new Rectangle2D.Double(0, -scrollY, viewportW, Math.max(scrollY + viewportH, root.overflowBottom));
        if (onNothing) {
            Composite was = g.getComposite();
            g.setComposite(AlphaComposite.Clear);
            g.fill(all);
            g.setComposite(was);
        } else {
            g.setColor(Color.WHITE);
            g.fill(all);
        }
        if (!transparent(canvasSource.style)) {
            Rectangle2D page = new Rectangle2D.Double(0, 0, viewportW, Math.max(viewportH, root.overflowBottom));
            AffineTransform saved = g.getTransform();
            g.translate(0, -scrollY);
            if (canvasSource.style.backgroundColor != 0) {
                g.setColor(new Color(canvasSource.style.backgroundColor, true));
                g.fill(page);
            }
            if (!canvasSource.style.backgroundImages.isEmpty()) {
                Rectangle2D area = canvasSource == root ? page : new Rectangle2D.Double(0, 0, viewportW, Math.max(viewportH, root.overflowBottom));
                Backgrounds.paint(g, canvasSource.style, res, area, page, surfaceBounds(), budget);
            }
            g.setTransform(saved);
        }
        propagated = canvasSource;
        g.translate(0, -scrollY);
        pageTx = new AffineTransform();
        context(g, root, root.x, root.y);
    }

    private Box propagated;

    private static boolean transparent(Style s) {
        return (s.backgroundColor >>> 24) == 0 && s.backgroundImages.isEmpty();
    }

    // ---------------------------------------------------------------- stacking

    private record Deferred(Box box, double x, double y, Shape clip, Shape pageClip, int order) {}

    /** Paints a stacking context (or a positioned box treated as one) whose border box is at (x, y). */
    private void context(Graphics2D g, Box box, double x, double y) {
        budget.check();
        Style s = box.style;
        if (box.fixed) y += scrollY;
        AffineTransform savedTx = g.getTransform();
        AffineTransform savedPage = pageTx;
        Shape savedPageClip = pageClip;
        AffineTransform t = transform(box, x, y);
        if (t != null) {
            g.transform(t);
            // Scaled to nothing (or past what a double holds): not rendered, as in browsers, and no hit region.
            if (!drawable(g.getTransform())) {
                g.setTransform(savedTx);
                return;
            }
            pageTx = new AffineTransform(pageTx);
            pageTx.concatenate(t);
        }
        boolean layer = s.opacity < 1 || !s.filter.equals("none");
        if (layer && s.opacity <= 0) {
            g.setTransform(savedTx);
            pageTx = savedPage;
            return;
        }
        if (layer && layers < MAX_LAYERS) {
            layers++;
            layered(g, box, x, y);
            layers--;
        } else {
            contextBody(g, box, x, y);
        }
        g.setTransform(savedTx);
        pageTx = savedPage;
        pageClip = savedPageClip;
    }

    private void contextBody(Graphics2D g, Box box, double x, double y) {
        if (box != propagated || box.element == null || !box.element.normalName().equals("html")) {
            if (box != propagated) background(g, box, x, y);
            else bordersOnly(g, box, x, y);
        } else {
            bordersOnly(g, box, x, y);
        }
        List<Deferred> deferred = new ArrayList<>();
        Shape clip = g.getClip();
        flow(g, box, x, y, deferred);
        g.setClip(clip);
        deferred.sort((a, b) -> {
            int za = a.box.style.zIndex == null ? 0 : a.box.style.zIndex, zb = b.box.style.zIndex == null ? 0 : b.box.style.zIndex;
            return za != zb ? Integer.compare(za, zb) : Integer.compare(a.order, b.order);
        });
        // Negative z-index first would belong under the flow; painting them here in order is close enough
        // for pages, which use negative z-index for decorations behind their own siblings.
        for (Deferred d : deferred) {
            Shape old = g.getClip();
            Shape oldPage = pageClip;
            g.setClip(d.clip);
            pageClip = d.pageClip;
            context(g, d.box, d.x, d.y);
            g.setClip(old);
            pageClip = oldPage;
        }
    }

    /**
     * Layers nested deeper than this are not made: such a box is painted straight onto the layer
     * round it, its opacity and filter left out. Each layer is an image as big as what it covers, all
     * of them held at once, and 500 nested translucent boxes would otherwise want gigabytes per band.
     */
    private static final int MAX_LAYERS = 8;
    /** Layers being painted into, round the box being painted. */
    private int layers;
    /** The part of [surface] (device px) whose pixels matter, or null for all of it: see {@link #keeping}. */
    private java.awt.Rectangle needed;
    /** The part of [surface] (device px) actually kept, within [needed], or null for all of [needed]: see {@link #kept}. */
    private java.awt.Rectangle kept;

    /** Opacity and filters: the subtree painted on a layer of its own, then blended in. */
    private void layered(Graphics2D g, Box box, double x, double y) {
        AffineTransform dev = g.getTransform();
        // The layer covers what the subtree can paint, within the surface being painted (the screen, or an
        // enclosing layer) and the clip: not the whole surface for every translucent badge.
        java.awt.Rectangle r = new java.awt.Rectangle(0, 0, surface.getWidth(), surface.getHeight());
        if (g.getClipBounds() != null) {
            java.awt.Rectangle clipDev = dev.createTransformedShape(g.getClip()).getBounds();
            r = r.intersection(clipDev);
        }
        Reach reach = new Reach();
        reach(box, x, y, reach);
        java.awt.Rectangle painted = null;
        // A backdrop-filter inside reads what is painted round it, which a smaller layer would cut off.
        if (!reach.backdrop) {
            painted = dev.createTransformedShape(reach.area).getBounds();
            // Blurs spread in device pixels whatever the transforms, so their room is added after mapping.
            int pad = (int) Math.ceil(reach.blur) + 2;
            painted.grow(pad, pad);
            r = r.intersection(painted);
        }
        if (r.width <= 0 || r.height <= 0) {
            // Nothing of it lands on this surface (another band's rows, outside a hover's clip), but its hit
            // regions are still wanted: the first band's painter makes them for the whole picture.
            if (recordHits) hitsOnly(g, box, x, y);
            return;
        }
        // The part of the layer (its own pixels) worth filtering and blending in: what its filter reads to
        // make the pixels needed (a blur reads its support round each; colour filters and opacity only the
        // pixel itself). The layer is still painted whole: cut short, Java2D would round curved and slanted
        // edges a level differently along the cut, and a band would differ from a whole picture.
        java.awt.Rectangle part = null;
        if (needed != null && !reach.backdrop) {
            int support = Filters.blurSupport(box.style.filter, box.style, deviceScale);
            part = new java.awt.Rectangle(needed.x - r.x, needed.y - r.y, needed.width, needed.height);
            part.grow(support, support);
            part = part.intersection(new java.awt.Rectangle(r.width, r.height));
            if (part.isEmpty()) {
                // Only in a band's margin, where nothing reads it: no need to paint it, only its hit regions.
                if (recordHits) hitsOnly(g, box, x, y);
                return;
            }
        }
        double grid = Filters.gridBlur(box.style.filter, box.style, deviceScale);
        if (grid > 0 && painted != null && fromKept(g, box, x, y, r, part, painted, grid)) return;
        // Colour filters work on straight colour, so a filtered layer stays plain ARGB; opacity alone blends faster premultiplied.
        // From this thread's pool, handed back once blended in: nothing keeps a layer.
        BufferedImage img = PixelPool.image(r.width, r.height, box.style.filter.equals("none") ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_ARGB);
        Graphics2D lg = img.createGraphics();
        lg.setRenderingHints(g.getRenderingHints());
        lg.translate(-r.x, -r.y);
        lg.transform(dev);
        if (g.getClip() != null) lg.setClip(g.getClip());
        BufferedImage savedSurface = surface;
        java.awt.Rectangle savedNeeded = needed, savedKept = kept;
        surface = img;
        // Layers inside are needed exactly where this one's filter reads.
        needed = part;
        kept = null;
        // The layer starts at r on the surface: a backdrop inside it blurs on the same grid as one outside.
        originX += r.x;
        originY += r.y;
        contextBody(lg, box, x, y);
        originX -= r.x;
        originY -= r.y;
        surface = savedSurface;
        needed = savedNeeded;
        kept = savedKept;
        lg.dispose();
        int ox = r.x, oy = r.y;
        if (part != null && (part.width < r.width || part.height < r.height)) {
            BufferedImage whole = img;
            img = copy(whole, part);
            PixelPool.give(whole);
            ox += part.x;
            oy += part.y;
        }
        if (grid > 0) {
            BufferedImage small = gridBlurred(img, ox, oy, grid);
            PixelPool.give(img);
            int k = Blur.step(grid), gx = Math.floorDiv(originX + ox, k) * k - originX, gy = Math.floorDiv(originY + oy, k) * k - originY;
            blend(g, small, gx, gy, small.getWidth() * k, small.getHeight() * k, (float) box.style.opacity);
            PixelPool.give(small);
            return;
        }
        Filters.apply(img, box.style.filter, deviceScale, box.style, budget);
        blend(g, img, ox, oy, img.getWidth(), img.getHeight(), (float) box.style.opacity);
        PixelPool.give(img);
    }

    /** Draws [img] at ([x], [y]) on the surface, [w] x [h] (scaled up smoothly if bigger), at [opacity]. */
    private static void blend(Graphics2D g, BufferedImage img, int x, int y, int w, int h, float opacity) {
        AffineTransform saved = g.getTransform();
        g.setTransform(new AffineTransform());
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
        if (w == img.getWidth() && h == img.getHeight()) {
            g.drawImage(img, x, y, null);
        } else {
            Object interpolation = g.getRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, x, y, w, h, null);
            if (interpolation != null) g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, interpolation);
        }
        g.setComposite(old);
        g.setTransform(saved);
    }

    /**
     * [img], a layer whose top-left is at ([ox], [oy]) on the surface, blurred by [sigma] on a grid fixed to the page
     * ({@link Filters#gridBlur}): out to whole cells of the grid (the edge pixels carrying on), worked out [k] times
     * smaller ({@link Blur#blurredCopy}), to draw [k] times bigger from the cell its top-left falls in. So a band, a
     * rectangle repaint and a whole picture blur alike wherever they start, as a backdrop does.
     */
    private BufferedImage gridBlurred(BufferedImage img, int ox, int oy, double sigma) {
        int k = Blur.step(sigma), w = img.getWidth(), h = img.getHeight();
        int px = originX + ox, py = originY + oy;
        int gx = Math.floorDiv(px, k) * k - px, gy = Math.floorDiv(py, k) * k - py;
        int gw = Math.floorDiv(px + w + k - 1, k) * k - px - gx, gh = Math.floorDiv(py + h + k - 1, k) * k - py - gy;
        // Every pixel of it is copied or extended below.
        BufferedImage cells = PixelPool.uncleared(gw, gh, img.getType());
        int[] from = pixels(img), to = pixels(cells);
        for (int y = 0; y < h; y++) System.arraycopy(from, y * w, to, (y - gy) * gw - gx, w);
        Filters.extend(to, gw, gh, -gx, -gy, w, h);
        BufferedImage small = Blur.blurredCopy(cells, sigma, k, budget);
        PixelPool.give(cells);
        return small;
    }

    /**
     * Draws [box]'s layer, the grid blur of [sigma] ({@link #gridBlurred}) of nothing but its own background, from the one
     * kept for the page ({@link BlurredLayers}), making that first if need be; true if it did, false if this layer must
     * be painted as ever. [r] is the layer's part of the surface, [part] the part of [r] worth blurring (null: all), and
     * [painted] where it paints, as {@link #layered} works them out (device px of the surface).
     *
     * <p>The kept layer is the whole box's, over every page pixel it paints within the clip; a surface paints only its
     * own part, carrying its edge pixels on past its edges. The two agree wherever a pixel reads only pixels the surface
     * has, so the kept one is used only if every pixel kept here ({@link #kept}, else {@link #needed}, else all) does.
     */
    private boolean fromKept(Graphics2D g, Box box, double x, double y, java.awt.Rectangle r, java.awt.Rectangle part,
                             java.awt.Rectangle painted, double sigma) {
        Style s = box.style;
        if (!box.children.isEmpty() || box.lines != null && !box.lines.isEmpty() || box.marker != null || box.inner != Box.Inner.FLOW) return false;
        if (!s.boxShadow.equals("none") || !s.backdropFilter.equals("none") || box.b[0] != 0 || box.b[1] != 0 || box.b[2] != 0 || box.b[3] != 0) return false;
        AffineTransform dev = g.getTransform();
        if ((dev.getType() & ~(AffineTransform.TYPE_TRANSLATION | AffineTransform.TYPE_UNIFORM_SCALE | AffineTransform.TYPE_GENERAL_SCALE)) != 0) return false;
        // Page device px: the surface's top-left is at the origin.
        AffineTransform onPage = AffineTransform.getTranslateInstance(originX, originY);
        onPage.concatenate(dev);
        java.awt.Rectangle area = new java.awt.Rectangle(painted);
        area.translate(originX, originY);
        java.awt.Rectangle clip = null;
        if (g.getClip() != null) {
            // A clip of whole pixels only (an overflow: hidden page), which every surface cuts the same.
            Area a = new Area(onPage.createTransformedShape(g.getClip()));
            Rectangle2D c = a.getBounds2D();
            if (!a.isRectangular() || c.getX() != Math.rint(c.getX()) || c.getY() != Math.rint(c.getY())
                    || c.getMaxX() != Math.rint(c.getMaxX()) || c.getMaxY() != Math.rint(c.getMaxY())) return false;
            clip = c.getBounds();
            area = area.intersection(clip);
        }
        if (area.isEmpty()) return false;
        java.awt.Rectangle keep = kept != null ? kept : needed != null ? needed : surfaceBounds();
        java.awt.Rectangle reads = new java.awt.Rectangle(keep);
        int support = Filters.blurSupport(s.filter, s, deviceScale);
        reads.grow(support, support);
        reads.translate(originX, originY);
        reads = reads.intersection(area);
        java.awt.Rectangle have = new java.awt.Rectangle(r);
        have.translate(originX, originY);
        if (!reads.isEmpty() && !have.contains(reads)) return false;
        Rectangle2D on = onPage.createTransformedShape(new Rectangle2D.Double(x, y, box.w, box.h)).getBounds2D();
        String key = String.join("|", String.valueOf(sigma), String.valueOf(onPage.getScaleX()), String.valueOf(onPage.getScaleY()),
                Math.round(on.getX() * 1024) + "," + Math.round(on.getY() * 1024) + "," + Math.round(on.getWidth() * 1024) + "," + Math.round(on.getHeight() * 1024),
                String.valueOf(area), String.valueOf(clip), String.valueOf(s.hidden()), Integer.toHexString(s.backgroundColor),
                String.valueOf(s.backgroundImages), String.valueOf(s.backgroundPositions), String.valueOf(s.backgroundSizes),
                String.valueOf(s.backgroundRepeats), String.valueOf(s.backgroundClips), java.util.Arrays.toString(Shapes.radii(box)),
                String.valueOf(s.fontSize), String.valueOf(viewportW), String.valueOf(viewportH));
        java.awt.Rectangle within = area;
        BlurredLayers.Layer layer = BlurredLayers.get(res, key, () -> keptLayer(g, box, x, y, onPage, within, sigma));
        int k = layer.k();
        // What the layer here would blur, out to whole cells of the page's grid (as gridBlurred), and those cells of the kept one.
        java.awt.Rectangle work = part == null ? r : new java.awt.Rectangle(r.x + part.x, r.y + part.y, part.width, part.height);
        int cx = Math.floorDiv(originX + work.x, k) * k, cy = Math.floorDiv(originY + work.y, k) * k;
        int cx1 = Math.floorDiv(originX + work.x + work.width + k - 1, k) * k, cy1 = Math.floorDiv(originY + work.y + work.height + k - 1, k) * k;
        int sx = (cx - layer.x()) / k, sy = (cy - layer.y()) / k, sw = (cx1 - cx) / k, sh = (cy1 - cy) / k;
        if (sx < 0 || sy < 0 || sx + sw > layer.small().getWidth() || sy + sh > layer.small().getHeight()) return false;
        blend(g, layer.small().getSubimage(sx, sy, sw, sh), cx - originX, cy - originY, sw * k, sh * k, (float) s.opacity);
        if (recordHits) hitsOnly(g, box, x, y);
        return true;
    }

    /**
     * [box]'s layer over [area] of the page (device px), clipped as [g] is, blurred on the page's grid: painted as
     * {@link #layered} paints it, on an image of its own whose top-left is [area]'s, with [onPage] the transform to page
     * device px. Its own picture, not a pooled one: it is kept.
     */
    private BlurredLayers.Layer keptLayer(Graphics2D g, Box box, double x, double y, AffineTransform onPage, java.awt.Rectangle area, double sigma) {
        BufferedImage img = PixelPool.image(area.width, area.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D lg = img.createGraphics();
        lg.setRenderingHints(g.getRenderingHints());
        lg.translate(-area.x, -area.y);
        lg.transform(onPage);
        if (g.getClip() != null) lg.setClip(g.getClip());
        BufferedImage savedSurface = surface;
        java.awt.Rectangle savedNeeded = needed, savedKept = kept;
        int savedX = originX, savedY = originY;
        boolean savedHits = recordHits;
        surface = img;
        needed = null;
        kept = null;
        originX = area.x;
        originY = area.y;
        recordHits = false;
        BufferedImage small;
        try {
            contextBody(lg, box, x, y);
            small = gridBlurred(img, 0, 0, sigma);
        } finally {
            surface = savedSurface;
            needed = savedNeeded;
            kept = savedKept;
            originX = savedX;
            originY = savedY;
            recordHits = savedHits;
            lg.dispose();
            PixelPool.give(img);
        }
        int k = Blur.step(sigma);
        BufferedImage own = new BufferedImage(small.getWidth(), small.getHeight(), small.getType());
        int[] from = pixels(small), to = pixels(own);
        System.arraycopy(from, 0, to, 0, to.length);
        PixelPool.give(small);
        return new BlurredLayers.Layer(own, Math.floorDiv(area.x, k) * k, Math.floorDiv(area.y, k) * k, k, area);
    }

    /** The [part] of [img], as an image of its own of the same type (Blur and Filters work on a whole pixel array), from this thread's pool. */
    private static BufferedImage copy(BufferedImage img, java.awt.Rectangle part) {
        BufferedImage out = PixelPool.uncleared(part.width, part.height, img.getType());
        int[] from = pixels(img), to = pixels(out);
        for (int y = 0; y < part.height; y++) System.arraycopy(from, (part.y + y) * img.getWidth() + part.x, to, y * part.width, part.width);
        return out;
    }

    /** Walks [box]'s subtree for its hit regions alone: under an empty clip nothing is drawn, and text is not even laid out. */
    private void hitsOnly(Graphics2D g, Box box, double x, double y) {
        Graphics2D hg = (Graphics2D) g.create();
        hg.clip(new Rectangle2D.Double());
        try {
            contextBody(hg, box, x, y);
        } finally {
            hg.dispose();
        }
    }

    /**
     * Where a subtree can paint, in the user space it is painted in; how far (device px) its blurs spread
     * past that; and whether it has a backdrop-filter.
     */
    private static final class Reach {
        Rectangle2D area;
        double blur;
        boolean backdrop;

        void add(Rectangle2D r, double grow) {
            Rectangle2D g = new Rectangle2D.Double(r.getX() - grow, r.getY() - grow, r.getWidth() + 2 * grow, r.getHeight() + 2 * grow);
            if (area == null) area = g;
            else area.add(g);
        }
    }

    /** Room round every box for what is drawn just outside it: antialiased edges, a range's knob, a checked box's rim. */
    private static final double SLACK = 8;

    /**
     * Adds to [reach] where [box] (its border box at (x, y)) and everything it paints can draw, generously:
     * every box, its outer shadows, its text with an em to spare, its list marker, with nested transforms
     * applied; and how far blurred shadows and filter blurs spread from that. Walks the tree the way
     * painting does: anything new that paints outside its own box must be added here too, or a
     * translucent ancestor cuts it off.
     */
    private void reach(Box box, double x, double y, Reach reach) {
        Style s = box.style;
        Reach own = new Reach();
        own.add(new Rectangle2D.Double(x, y, box.w, box.h), SLACK);
        if (!s.backdropFilter.equals("none")) reach.backdrop = true;
        for (Shadows.Shadow sh : Shadows.parse(s.boxShadow, s)) {
            if (sh.inset()) continue;
            own.add(new Rectangle2D.Double(x + sh.x(), y + sh.y(), box.w, box.h), Math.max(0, sh.spread()) + SLACK);
            own.blur = Math.max(own.blur, shadowSpread(sh));
        }
        if (box.marker != null) {
            double fs = (box.markerStyle != null ? box.markerStyle : s).fontSize;
            own.add(new Rectangle2D.Double(x - 10 * fs, y, 10 * fs + box.w, box.h), SLACK);
        }
        if (box.lines != null) {
            for (Box.Line line : box.lines) {
                for (Box.Item it : line.items) {
                    if (it.text == null) continue;
                    double fs = it.style.fontSize;
                    Rectangle2D t = new Rectangle2D.Double(x + it.x - fs, y + it.baselineY - 2 * fs, it.w + 2 * fs, 3 * fs);
                    own.add(t, SLACK);
                    for (Shadows.Shadow sh : Shadows.parse(it.style.textShadow, it.style)) {
                        own.add(new Rectangle2D.Double(t.getX() + sh.x(), t.getY() + sh.y(), t.getWidth(), t.getHeight()), SLACK);
                        own.blur = Math.max(own.blur, shadowSpread(sh));
                    }
                }
            }
        }
        reachChildren(box, x, y, own);
        reach.backdrop |= own.backdrop;
        reach.add(own.area, 0);
        // A filter blur (sigma, device px) spreads everything inside, blurs included, about three sigma further;
        // Blur's three boxes reach a pixel or so more each, and a grid blur a cell or two more.
        double filter = Filters.blurRadius(s.filter, s) * deviceScale;
        reach.blur = Math.max(reach.blur, own.blur + (filter > 0 ? Math.max(3 * filter + 4, Filters.reach(s.filter, s, deviceScale) + 2) : 0));
    }

    /** How far (device px) a blurred shadow spreads past its shape, as {@link Shadows#blurred} pads it. */
    private double shadowSpread(Shadows.Shadow sh) {
        return sh.blur() <= Shadows.SHARP ? 0 : Math.ceil(sh.blur() / 2 * deviceScale * 3) + 2;
    }

    private void reachChildren(Box parent, double x, double y, Reach reach) {
        for (Box c : parent.children) {
            if (c.isText() || c.inner == Box.Inner.BREAK) continue;
            if (c.isInline()) {
                // No rectangle of its own: its pieces, and what is inside it, are placed from the block.
                if (c.fragments != null) for (Box.Fragment f : c.fragments) reach.add(new Rectangle2D.Double(x + f.x, y + f.y, f.w, f.h), SLACK);
                reachChildren(c, x, y, reach);
                continue;
            }
            double cx = x + c.x, cy = y + c.y + (c.fixed ? scrollY : 0);
            AffineTransform t = transform(c, cx, cy);
            if (t != null && !drawable(t)) continue; // not rendered (see context)
            Reach inner = new Reach();
            reach(c, cx, cy, inner);
            reach.add(t == null ? inner.area : t.createTransformedShape(inner.area).getBounds2D(), 0);
            reach.backdrop |= inner.backdrop;
            reach.blur = Math.max(reach.blur, inner.blur);
        }
    }

    private AffineTransform transform(Box box, double x, double y) {
        String t = box.style.transform;
        if (t.equals("none")) return null;
        AffineTransform a = Transforms.parse(t, box.w, box.h, box.style);
        if (a == null || a.isIdentity()) return null;
        double[] origin = Transforms.origin(box.style.transformOrigin, box.w, box.h, box.style);
        AffineTransform full = new AffineTransform();
        full.translate(x + origin[0], y + origin[1]);
        full.concatenate(a);
        full.translate(-(x + origin[0]), -(y + origin[1]));
        return full;
    }

    /** Whether drawing through [t] makes sense: every entry finite, and invertible (nothing is scaled to nothing). */
    private static boolean drawable(AffineTransform t) {
        double det = t.getDeterminant();
        return Double.isFinite(det) && Math.abs(det) > Double.MIN_VALUE && Double.isFinite(t.getScaleX()) && Double.isFinite(t.getScaleY())
                && Double.isFinite(t.getShearX()) && Double.isFinite(t.getShearY()) && Double.isFinite(t.getTranslateX()) && Double.isFinite(t.getTranslateY());
    }

    /** True for a box painted later, in its stacking context's positioned pass. */
    private static boolean deferredBox(Box c) {
        return c.style.isPositioned() || c.style.createsStackingContext();
    }

    /** The normal flow inside [box]: block children, then lines, collecting positioned ones. */
    private void flow(Graphics2D g, Box box, double x, double y, List<Deferred> deferred) {
        Shape savedClip = g.getClip();
        Shape savedPageClip = pageClip;
        if (box.style.clips() && box.parent != null) {
            Shape inner = padding(box, x, y);
            g.clip(inner);
            Shape pageShape = pageTx.createTransformedShape(inner);
            pageClip = pageClip == null ? pageShape : intersect(pageClip, pageShape);
        }
        if (box.marker != null) marker(g, box, x, y);
        for (Box c : box.children) {
            if (c.isText() || c.inner == Box.Inner.BREAK) continue;
            if (c.isInline()) {
                collectInline(g, c, x, y, deferred);
                continue;
            }
            if (c.atomic && box.lines != null && !deferredBox(c)) continue; // painted with its line
            double cx = x + c.x, cy = y + c.y;
            if (deferredBox(c)) {
                deferred.add(new Deferred(c, cx, cy, g.getClip(), pageClip, deferred.size()));
                continue;
            }
            if (c.style.hidden() && c.children.isEmpty()) continue;
            background(g, c, cx, cy);
            flow(g, c, cx, cy, deferred);
        }
        if (box.lines != null) lines(g, box, x, y, deferred);
        g.setClip(savedClip);
        pageClip = savedPageClip;
    }

    /** An inline box's positioned or block descendants, which it does not paint itself. */
    private void collectInline(Graphics2D g, Box inline, double x, double y, List<Deferred> deferred) {
        for (Box c : inline.children) {
            if (c.isText() || c.inner == Box.Inner.BREAK) continue;
            if (c.isInline()) {
                collectInline(g, c, x, y, deferred);
            } else if (deferredBox(c)) {
                deferred.add(new Deferred(c, x + c.x, y + c.y, g.getClip(), pageClip, deferred.size()));
            }
        }
    }

    private static Shape intersect(Shape a, Shape b) {
        Area area = new Area(a);
        area.intersect(new Area(b));
        return area;
    }

    // ---------------------------------------------------------------- lines

    private void lines(Graphics2D g, Box block, double x, double y, List<Deferred> deferred) {
        // Inline boxes' backgrounds and borders under the text.
        inlineDecorations(g, block, x, y);
        for (Box.Line line : block.lines) {
            budget.check();
            for (Box.Item it : line.items) {
                if (it.text != null) {
                    text(g, it, x, y);
                } else if (it.box != null) {
                    Box ab = it.box;
                    if (deferredBox(ab)) continue; // collected with the tree walk
                    if (ab.style.hidden()) continue;
                    double ax = x + ab.x, ay = y + ab.y;
                    // An inline-block paints as if it were a stacking context of its own.
                    context(g, ab, ax, ay);
                }
            }
        }
    }

    private void inlineDecorations(Graphics2D g, Box parent, double x, double y) {
        for (Box c : parent.children) {
            if (!c.isInline() || c.isText()) continue;
            if (c.fragments != null && !c.style.hidden()) {
                for (Box.Fragment f : c.fragments) fragment(g, c, f, x, y);
            }
            inlineDecorations(g, c, x, y);
        }
    }

    private void fragment(Graphics2D g, Box box, Box.Fragment f, double x, double y) {
        Style s = box.style;
        double fx = x + f.x, fy = y + f.y;
        Rectangle2D r = new Rectangle2D.Double(fx, fy, Math.max(0, f.w), f.h);
        double[] radii = Shapes.radii(box);
        Shape shape = Shapes.rect(r.getX(), r.getY(), r.getWidth(), r.getHeight(), radii);
        if ((s.backgroundColor >>> 24) != 0) {
            g.setColor(new Color(s.backgroundColor, true));
            g.fill(shape);
        }
        if (!s.backgroundImages.isEmpty()) Backgrounds.paint(g, s, res, r, shape, surfaceBounds(), budget);
        double left = f.first ? box.b[3] : 0, right = f.last ? box.b[1] : 0;
        drawBorders(g, s, r, radii, new double[]{box.b[0], right, box.b[2], left});
        hit(shape, s, box.element != null ? box.element : box.owner);
    }

    private void text(Graphics2D g, Box.Item it, double x, double y) {
        Style s = it.style;
        if (s.hidden()) return;
        String text = it.text;
        if (text.isBlank() && s.textDecorationLine.equals("none")) {
            hit(new Rectangle2D.Double(x + it.x, y + it.baselineY - s.fontSize, it.w, s.fontSize * 1.2), s, it.box.owner);
            return;
        }
        double tx = x + it.x, ty = y + it.baselineY;
        Font font = it.font;
        List<Shadows.Shadow> shadows = Shadows.parse(s.textShadow, s);
        // A band of a full picture is handed the whole page: text far above or below what it can show
        // costs a layout and a glyph raster for nothing. Its hit region is still wanted.
        Rectangle2D vis = visible(g, surfaceBounds());
        double reach = 2 * Math.max(s.fontSize, font.getSize2D());
        for (Shadows.Shadow sh : shadows) reach = Math.max(reach, 2 * s.fontSize + Math.abs(sh.y()) + 3 * sh.blur());
        if (vis == null || ty + reach < vis.getMinY() || ty - reach > vis.getMaxY()) {
            Fonts.Metrics m = shaper.fonts().metrics(font);
            hit(new Rectangle2D.Double(tx, ty - m.ascent(), it.w, m.ascent() + m.descent()), s, it.box.owner);
            return;
        }
        // text-shadow, first one on top. One not blurred is the text itself again, in its colour, drawn as the text is
        // (a glyph at a time, from the font's cache): filling the glyphs' outlines cost five times as much.
        for (int i = shadows.size() - 1; i >= 0; i--) {
            Shadows.Shadow sh = shadows.get(i);
            if (sh.blur() > Shadows.SHARP) {
                Shadows.text(g, shaper, text, font, tx + sh.x(), ty + sh.y(), sh, deviceScale, surfaceBounds(), budget);
            } else if ((sh.color() >>> 24) != 0) {
                g.setColor(new Color(sh.color(), true));
                glyphs(g, it, text, font, tx + sh.x(), ty + sh.y());
            }
        }
        Integer fill = s.textFillColor;
        boolean clipText = s.backgroundClips.contains("text") && !s.backgroundImages.isEmpty();
        Box owner = backgroundOwner(it.box, s);
        if (clipText && owner != null) {
            // Gradient text: the element's background, seen through the letters. Drawn as an
            // antialiased mask, because a clip to the glyph outlines would have jagged edges.
            Rectangle2D area = ownerArea(owner, x, y, it);
            gradientText(g, s, text, font, tx, ty, area);
        } else {
            int color = fill != null ? fill : s.color;
            if ((color >>> 24) != 0) {
                g.setColor(new Color(color, true));
                glyphs(g, it, text, font, tx, ty);
            }
        }
        decorations(g, it, tx, ty);
        Fonts.Metrics m = shaper.fonts().metrics(font);
        hit(new Rectangle2D.Double(tx, ty - m.ascent(), it.w, m.ascent() + m.descent()), s, it.box.owner);
    }

    /** [it]'s [text] at ([tx], [ty]) in [g]'s colour, its letter-spacing between words. */
    private void glyphs(Graphics2D g, Box.Item it, String text, Font font, double tx, double ty) {
        if (it.spacing != 0) {
            double cx = tx;
            for (String word : text.split("(?<= )")) {
                shaper.draw(g, word, font, cx, ty);
                cx += shaper.width(word, font) + (word.endsWith(" ") ? it.spacing : 0);
            }
        } else {
            shaper.draw(g, text, font, tx, ty);
        }
    }

    private void gradientText(Graphics2D g, Style s, String text, Font font, double tx, double ty, Rectangle2D area) {
        AffineTransform dev = g.getTransform();
        Shape glyphs = shaper.outline(text, font, tx, ty);
        java.awt.Rectangle r = dev.createTransformedShape(glyphs.getBounds2D()).getBounds();
        r.grow(2, 2);
        if (r.width <= 0 || r.height <= 0 || (long) r.width * r.height > 16_000_000L) return;
        BufferedImage mask = PixelPool.image(r.width, r.height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D mg = mask.createGraphics();
        mg.setRenderingHints(g.getRenderingHints());
        mg.translate(-r.x, -r.y);
        mg.transform(dev);
        mg.setColor(Color.WHITE);
        shaper.draw(mg, text, font, tx, ty);
        // Keep the mask's alpha, take the background's colour.
        mg.setComposite(AlphaComposite.SrcIn);
        if ((s.backgroundColor >>> 24) != 0) {
            mg.setColor(new Color(s.backgroundColor, true));
            mg.fill(area);
        }
        Backgrounds.paint(mg, s, res, area, area, new java.awt.Rectangle(r.width, r.height), budget);
        mg.dispose();
        AffineTransform saved = g.getTransform();
        g.setTransform(new AffineTransform());
        g.drawImage(mask, r.x, r.y, null);
        g.setTransform(saved);
        PixelPool.give(mask);
    }

    /** The box whose background shows through gradient text: the nearest ancestor with that background. */
    private static Box backgroundOwner(Box textBox, Style s) {
        for (Box b = textBox; b != null; b = b.parent) if (b.style == s && !b.isText()) return b;
        return textBox.parent;
    }

    private static Rectangle2D ownerArea(Box owner, double x, double y, Box.Item it) {
        if (owner.isInline() && owner.fragments != null && !owner.fragments.isEmpty()) {
            Box.Fragment f = owner.fragments.get(0);
            double minX = f.x, maxX = f.x + f.w, minY = f.y, maxY = f.y + f.h;
            for (Box.Fragment fr : owner.fragments) {
                minX = Math.min(minX, fr.x);
                maxX = Math.max(maxX, fr.x + fr.w);
                minY = Math.min(minY, fr.y);
                maxY = Math.max(maxY, fr.y + fr.h);
            }
            return new Rectangle2D.Double(x + minX, y + minY, maxX - minX, maxY - minY);
        }
        // A block's own text: its lines are relative to it, so its border box is at (x, y).
        return new Rectangle2D.Double(x, y, Math.max(owner.w, it.x + it.w), Math.max(owner.h, 1));
    }

    private void decorations(Graphics2D g, Box.Item it, double tx, double ty) {
        Style s = it.style;
        String line = s.textDecorationLine;
        if (line.equals("none")) return;
        int color = s.textDecorationColor != null ? s.textDecorationColor : s.color;
        LineMetrics lm = it.font.getLineMetrics("x", Fonts.FRC);
        double thick = Math.max(1, s.fontSize / 15);
        g.setColor(new Color(color, true));
        Stroke old = g.getStroke();
        float[] dash = switch (s.textDecorationStyle) {
            case "dashed" -> new float[]{(float) (thick * 3), (float) (thick * 2)};
            case "dotted" -> new float[]{(float) thick, (float) thick};
            default -> null;
        };
        g.setStroke(new BasicStroke((float) thick, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, dash, 0));
        double w = it.w;
        String text = it.text;
        // Trailing spaces are not underlined.
        if (text.endsWith(" ")) w -= shaper.width(" ", it.font) * (text.length() - text.stripTrailing().length());
        if (line.contains("underline")) {
            double uy = ty + Math.max(thick, lm.getUnderlineOffset() + thick / 2);
            g.draw(new Line2D.Double(tx, uy, tx + w, uy));
        }
        if (line.contains("line-through")) {
            double sy = ty + lm.getStrikethroughOffset();
            g.draw(new Line2D.Double(tx, sy, tx + w, sy));
        }
        if (line.contains("overline")) {
            double oy = ty - lm.getAscent() + thick;
            g.draw(new Line2D.Double(tx, oy, tx + w, oy));
        }
        g.setStroke(old);
    }

    private void marker(Graphics2D g, Box li, double x, double y) {
        if (Double.isNaN(li.baseline)) return;
        Style s = li.markerStyle != null ? li.markerStyle : li.style;
        Font font = shaper.withSpacing(shaper.fonts().font(s.fontFamilies, s.fontWeight, s.italic, s.fontSize), 0);
        String text = li.marker;
        boolean bullet = text.length() == 1 && !Character.isLetterOrDigit(text.charAt(0));
        double by = y + li.baseline;
        g.setColor(new Color(s.color, true));
        if (bullet) {
            // Bullets drawn as shapes, like browsers do, so every font gets the same dot.
            double size = s.fontSize * 0.35;
            double cx = x + li.contentX() - s.fontSize * 0.5 - size / 2;
            if (s.listStylePosition.equals("inside")) cx = x + li.contentX() + size;
            double cy = by - s.fontSize * 0.3;
            Shape dot = switch (text) {
                case "◦" -> new Ellipse2D.Double(cx - size / 2, cy - size / 2, size, size);
                case "▪" -> new Rectangle2D.Double(cx - size / 2, cy - size / 2, size, size);
                default -> new Ellipse2D.Double(cx - size / 2, cy - size / 2, size, size);
            };
            if (text.equals("◦")) {
                g.setStroke(new BasicStroke((float) Math.max(1, s.fontSize / 16)));
                g.draw(dot);
            } else {
                g.fill(dot);
            }
            return;
        }
        double w = shaper.width(text + " ", font);
        double mx = x + li.contentX() - w;
        shaper.draw(g, text, font, mx, by);
    }

    // ---------------------------------------------------------------- boxes

    private Shape padding(Box b, double x, double y) {
        double[] r = Shapes.inset(Shapes.radii(b), b.b[0], b.b[1], b.b[2], b.b[3]);
        return Shapes.rect(x + b.b[3], y + b.b[0], b.w - b.b[1] - b.b[3], b.h - b.b[0] - b.b[2], r);
    }

    private void background(Graphics2D g, Box b, double x, double y) {
        budget.check();
        Style s = b.style;
        double[] radii = Shapes.radii(b);
        Shape border = Shapes.rect(x, y, b.w, b.h, radii);
        if (!s.hidden()) {
            List<Shadows.Shadow> shadows = Shadows.parse(s.boxShadow, s);
            for (int i = shadows.size() - 1; i >= 0; i--) {
                Shadows.Shadow sh = shadows.get(i);
                if (!sh.inset()) Shadows.outer(g, x, y, b.w, b.h, radii, sh, deviceScale, neededBounds(), budget);
            }
            if (!s.backdropFilter.equals("none") && surface != null) Filters.backdrop(g, surface, border, s, deviceScale, originX, originY, needed, budget);
            Shape clipBox = switch (Backgrounds.pick(s.backgroundClips, 0, "border-box")) {
                case "padding-box" -> padding(b, x, y);
                case "content-box" -> Shapes.rect(x + b.contentX(), y + b.contentY(), b.contentW(), b.contentH(),
                        Shapes.inset(radii, b.b[0] + b.p[0], b.b[1] + b.p[1], b.b[2] + b.p[2], b.b[3] + b.p[3]));
                default -> border;
            };
            boolean textClip = s.backgroundClips.contains("text");
            if (!textClip && (s.backgroundColor >>> 24) != 0) {
                g.setColor(new Color(s.backgroundColor, true));
                g.fill(clipBox);
            }
            if (!textClip && !s.backgroundImages.isEmpty()) {
                Rectangle2D area = new Rectangle2D.Double(x + b.b[3], y + b.b[0], b.w - b.b[1] - b.b[3], b.h - b.b[0] - b.b[2]);
                Backgrounds.paint(g, s, res, area, clipBox, surfaceBounds(), budget);
            }
            if (b.inner == Box.Inner.REPLACED) replaced(g, b, x, y, radii);
            for (Shadows.Shadow sh : shadows) if (sh.inset()) Shadows.inner(g, padding(b, x, y), sh, deviceScale, neededBounds(), budget);
            drawBorders(g, s, new Rectangle2D.Double(x, y, b.w, b.h), radii, b.b);
        }
        hit(border, s, b.element != null ? b.element : b.owner);
    }

    private void bordersOnly(Graphics2D g, Box b, double x, double y) {
        double[] radii = Shapes.radii(b);
        drawBorders(g, b.style, new Rectangle2D.Double(x, y, b.w, b.h), radii, b.b);
        hit(Shapes.rect(x, y, b.w, b.h, radii), b.style, b.element);
    }

    private void hit(Shape s, Style style, Element e) {
        if (e == null || !recordHits) return;
        hits.add(new Hit(pageTx.createTransformedShape(s), pageClip, e, !style.hidden() && !style.pointerEvents.equals("none")));
    }

    /** The device bounds of the surface being painted on. */
    private java.awt.Rectangle surfaceBounds() {
        return new java.awt.Rectangle(surface.getWidth(), surface.getHeight());
    }

    /**
     * The device bounds of the surface whose pixels matter ({@link #keeping}): a blurred shadow is only worked out
     * there, exactly as it would be on the whole surface (see {@link Shadows#blurred}).
     */
    private java.awt.Rectangle neededBounds() {
        java.awt.Rectangle all = surfaceBounds();
        return needed == null ? all : all.intersection(needed);
    }

    /**
     * What [g] can still change of [surface] (the device bounds of what it draws on), in [g]'s user
     * space: the surface, within the clip if there is one. Null when that is nothing.
     */
    static Rectangle2D visible(Graphics2D g, java.awt.Rectangle surface) {
        Rectangle2D r;
        try {
            r = g.getTransform().createInverse().createTransformedShape(surface).getBounds2D();
        } catch (java.awt.geom.NoninvertibleTransformException e) {
            return null;
        }
        Shape clip = g.getClip();
        if (clip != null) Rectangle2D.intersect(r, clip.getBounds2D(), r);
        return r.isEmpty() ? null : r;
    }

    /** Borders: a ring per side, or one ring when all four match; dashed and dotted ones stroked. */
    private void drawBorders(Graphics2D g, Style s, Rectangle2D r, double[] radii, double[] widths) {
        boolean any = false;
        for (int i = 0; i < 4; i++) if (widths[i] > 0 && (s.borderColor[i] >>> 24) != 0) any = true;
        if (!any) return;
        double t = widths[0], rt = widths[1], bt = widths[2], lt = widths[3];
        Shape outer = Shapes.rect(r.getX(), r.getY(), r.getWidth(), r.getHeight(), radii);
        Shape inner = Shapes.rect(r.getX() + lt, r.getY() + t, r.getWidth() - lt - rt, r.getHeight() - t - bt, Shapes.inset(radii, t, rt, bt, lt));
        boolean same = true;
        for (int i = 1; i < 4; i++) {
            if (s.borderColor[i] != s.borderColor[0] || !s.borderStyle[i].equals(s.borderStyle[0]) || (widths[i] > 0) != (widths[0] > 0)) same = false;
        }
        String style = s.borderStyle[0];
        if (same && (style.equals("dashed") || style.equals("dotted"))) {
            double w = widths[0];
            g.setColor(new Color(s.borderColor[0], true));
            float[] dash = style.equals("dotted") ? new float[]{0.01f, (float) (w * 2)} : new float[]{(float) (w * 3), (float) (w * 2)};
            Stroke old = g.getStroke();
            g.setStroke(new BasicStroke((float) w, style.equals("dotted") ? BasicStroke.CAP_ROUND : BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, dash, 0));
            g.draw(Shapes.rect(r.getX() + w / 2, r.getY() + w / 2, r.getWidth() - w, r.getHeight() - w, Shapes.inset(radii, w / 2, w / 2, w / 2, w / 2)));
            g.setStroke(old);
            return;
        }
        if (same) {
            g.setColor(new Color(shade(s.borderColor[0], style, 0), true));
            if (outer instanceof Rectangle2D outerRect && inner instanceof Rectangle2D innerRect && Shapes.fillPixels(g, outerRect, innerRect)) return;
            Area ring = new Area(outer);
            ring.subtract(new Area(inner));
            g.fill(ring);
            return;
        }
        // Different sides: each side is the ring cut by the diagonals from the corners.
        double x0 = r.getX(), y0 = r.getY(), x1 = r.getMaxX(), y1 = r.getMaxY();
        double ix0 = x0 + lt, iy0 = y0 + t, ix1 = x1 - rt, iy1 = y1 - bt;
        Area ring = new Area(outer);
        ring.subtract(new Area(inner));
        double[][] quads = {
                {x0, y0, x1, y0, ix1, iy0, ix0, iy0},
                {x1, y0, x1, y1, ix1, iy1, ix1, iy0},
                {x1, y1, x0, y1, ix0, iy1, ix1, iy1},
                {x0, y1, x0, y0, ix0, iy0, ix0, iy1}};
        for (int i = 0; i < 4; i++) {
            if (widths[i] <= 0 || (s.borderColor[i] >>> 24) == 0 || s.borderStyle[i].equals("none") || s.borderStyle[i].equals("hidden")) continue;
            Path2D.Double q = new Path2D.Double();
            double[] p = quads[i];
            // Pushed outward a little so neighbouring sides meet without a hairline gap.
            q.moveTo(p[0], p[1]);
            q.lineTo(p[2], p[3]);
            q.lineTo(p[4], p[5]);
            q.lineTo(p[6], p[7]);
            q.closePath();
            Area side = new Area(q);
            side.intersect(ring);
            g.setColor(new Color(shade(s.borderColor[i], s.borderStyle[i], i), true));
            g.fill(side);
        }
    }

    /** inset/outset/groove/ridge borders: darker on two sides, like browsers draw them. */
    private static int shade(int argb, String style, int side) {
        boolean dark = switch (style) {
            case "inset", "groove" -> side == 0 || side == 3;
            case "outset", "ridge" -> side == 1 || side == 2;
            default -> false;
        };
        if (!dark) return argb;
        int r = (argb >> 16 & 255) * 2 / 3, gg = (argb >> 8 & 255) * 2 / 3, b = (argb & 255) * 2 / 3;
        return (argb & 0xFF000000) | (r << 16) | (gg << 8) | b;
    }

    // ---------------------------------------------------------------- replaced content

    private void replaced(Graphics2D g, Box b, double x, double y, double[] radii) {
        double cx = x + b.contentX(), cy = y + b.contentY(), cw = b.contentW(), ch = b.contentH();
        if (b.picture != null) {
            Shape old = g.getClip();
            g.clip(Shapes.rect(cx, cy, cw, ch, Shapes.inset(radii, b.b[0] + b.p[0], b.b[1] + b.p[1], b.b[2] + b.p[2], b.b[3] + b.p[3])));
            double nw = b.picture.width(), nh = b.picture.height();
            if (nw <= 0 || nh <= 0) {
                nw = cw;
                nh = ch;
            }
            double dw = cw, dh = ch;
            switch (b.style.objectFit) {
                case "contain", "scale-down" -> {
                    double sc = Math.min(cw / nw, ch / nh);
                    if (b.style.objectFit.equals("scale-down")) sc = Math.min(sc, 1);
                    dw = nw * sc;
                    dh = nh * sc;
                }
                case "cover" -> {
                    double sc = Math.max(cw / nw, ch / nh);
                    dw = nw * sc;
                    dh = nh * sc;
                }
                case "none" -> {
                    dw = nw;
                    dh = nh;
                }
                default -> {}
            }
            double[] pos = objectPosition(b.style, cw - dw, ch - dh);
            b.picture.draw(g, cx + pos[0], cy + pos[1], dw, dh);
            g.setClip(old);
            return;
        }
        if (b.control != null) Controls.draw(g, b, cx, cy, cw, ch, shaper);
    }

    private static double[] objectPosition(Style s, double freeX, double freeY) {
        List<String> p = Values.splitSpaces(s.objectPosition.toLowerCase(Locale.ROOT));
        String a = p.isEmpty() ? "50%" : p.get(0), b = p.size() > 1 ? p.get(1) : "50%";
        return new double[]{pos(a, freeX, s), pos(b, freeY, s)};
    }

    private static double pos(String v, double free, Style s) {
        return switch (v) {
            case "left", "top" -> 0;
            case "center" -> free / 2;
            case "right", "bottom" -> free;
            default -> {
                Len l = Len.parse(v, s.context);
                yield l == null ? free / 2 : l.of(free);
            }
        };
    }

    /** The surface painted on starts at ([x], [y]) device px of the page, so backdrops blur on a grid fixed to the page. */
    public Painter origin(int x, int y) {
        originX = x;
        originY = y;
        return this;
    }

    /**
     * How far (device px) a blur among [styles] reads what is painted round it: a backdrop-filter past
     * its clip, a filter past its element. A picture painted in pieces needs this much painted round
     * each piece for those blurs to match.
     */
    public static int blurReach(Iterable<Style> styles, double scale) {
        int reach = 0;
        for (Style s : styles) {
            if (!s.backdropFilter.equals("none")) reach = Math.max(reach, Filters.backdropPad(Filters.backdropBlur(s), scale));
            if (!s.filter.equals("none")) reach = Math.max(reach, Filters.reach(s.filter, s, scale));
        }
        return reach;
    }

    /** How far (CSS px) [s]'s filter blurs reach past what they draw, and read past what they make, at [scale]. */
    public static double filterReach(Style s, double scale) {
        return s.filter.equals("none") ? 0 : Filters.reach(s.filter, s, scale) / scale;
    }

    static int[] pixels(BufferedImage img) {
        return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }
}
