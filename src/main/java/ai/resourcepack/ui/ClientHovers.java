package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.HoverLayers;
import ai.resourcepack.ui.html.HoverAhead;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Repaint;
import ai.resourcepack.ui.html.paint.Painter;
import org.jsoup.nodes.Element;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The hovers a view's client shows by itself ({@link HoverLayers}): each hover drawn ahead over the picture on the
 * screen ({@link HoverAhead}) is handed to the client as a layer, with the rectangle of the view where the pointer is
 * over its target, so it shows the frame the pointer gets there rather than once the server has heard of it, drawn the
 * hover and sent it. While the pointer goes from one such hover to another, or back to the state the picture was drawn
 * in, the server draws and sends nothing: the session keeps its picture ({@link Session#pointer}).
 *
 * <p>A layer is of one picture: all of them go the moment the picture changes ({@link #reset}). A target is given a
 * layer only if its rectangle is where the pointer is over it, give or take a few rounded corners; where the client then
 * shows a layer the pointer is not over (a corner, or something over the target), the server takes it off as soon as it
 * hears where the pointer is ({@link #shows}).
 */
final class ClientHovers {
    /** Canvas pixels between the points tried when checking a target's rectangle, at the least. */
    private static final int STEP = 4;
    /** The most points tried for one target: a bigger one is tried further apart. */
    private static final int SAMPLES = 512;
    /** The share of those points that may be over something else (rounded corners), as the server puts those right. */
    private static final double SLACK = 0.03;

    private final HoverLayers layers;

    // Guarded by this. The picture the layers are of, the hover and press state it was drawn in, the layers by key, the
    // hovers that will never have one over it (their rectangle will not do, they change nothing, or too much), and each
    // layer worked out over it, armed or not (one taken off for room is armed again from it).
    private HtmlRenderer.Frame of;
    private List<Element> ofHover = List.of();
    private final Map<List<Element>, Armed> armed = new HashMap<>();
    private final java.util.Set<List<Element>> never = new java.util.HashSet<>();
    private final Map<List<Element>, Worked> worked = new HashMap<>();

    /** A layer: its target's key, where it shows (canvas pixels; [exclude] may be null) and its priority. */
    private record Armed(List<Element> key, int[] include, int[] exclude, int priority) {
        boolean showsAt(int x, int y) {
            return in(include, x, y) && (exclude == null || !in(exclude, x, y));
        }
    }

    /**
     * Counters, for the bench and debug output: layers armed, targets whose rectangle would not do, layers taken off by
     * the server, and the render threads' CPU time spent arming, ns.
     */
    long armedCount, refused, corrected, armNs;
    private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();

    ClientHovers(HoverLayers layers) {
        this.layers = layers;
    }

    /** The picture changed (or the page went, or the pointer left): no layer is of it. */
    synchronized void reset() {
        if (of == null && armed.isEmpty()) return;
        of = null;
        armed.clear();
        never.clear();
        worked.clear();
        layers.clear();
    }

    /**
     * Arms a layer for each hover in [ready] drawn ahead over [f], drawn in [drawnHover], that has none yet: on the render
     * thread, while [f]'s picture is not being drawn into. [px], [py]: the pointer, in canvas pixels; when the maps run
     * out, the layers furthest from it go first.
     */
    synchronized void arm(HtmlRenderer.Frame f, List<Element> drawnHover, List<HoverAhead.Ready> ready, int px, int py) {
        long t0 = THREADS.getCurrentThreadCpuTime();
        try {
            armEach(f, drawnHover, ready, px, py);
        } finally {
            armNs += THREADS.getCurrentThreadCpuTime() - t0;
        }
    }

    private void armEach(HtmlRenderer.Frame f, List<Element> drawnHover, List<HoverAhead.Ready> ready, int px, int py) {
        if (f != of) {
            if (of != null || !armed.isEmpty()) layers.clear();
            armed.clear();
            never.clear();
            worked.clear();
            of = f;
            ofHover = drawnHover;
        }
        if (ready.isEmpty()) return;
        BufferedImage img = f.image();
        // A parked picture (no image kept): nothing to work layers out over until it is drawn again.
        if (img == null) return;
        int cw = img.getWidth(), ch = img.getHeight();
        double s = f.scale();
        Map<Element, List<Element>> keys = new IdentityHashMap<>();
        int[] drawnBox = drawnHover.isEmpty() ? null : box(boxesOf(f, drawnHover, keys), s, cw, ch);
        for (HoverAhead.Ready r : ready) {
            if (armed.containsKey(r.hoverKey()) || never.contains(r.hoverKey()) || r.hoverKey().equals(drawnHover)) continue;
            Worked w = worked.get(r.hoverKey());
            if (w == null) {
                int[] include = box(r.boxes(), s, cw, ch);
                // Over the drawn state's own target (unless this one is inside it), the picture is right as it is.
                int[] exclude = include == null || drawnBox == null || !overlaps(drawnBox, include) || inside(include, drawnBox) ? null : drawnBox;
                // A hover that starts a transition is animated by the server: the client could show only its first frame.
                if (include == null || r.prepared().motion() != null && r.prepared().motion().active() || !fits(f, r.hoverKey(), include, exclude, keys)) {
                    never.add(r.hoverKey());
                    refused++;
                    continue;
                }
                int priority = priority(f, r.hoverKey(), keys);
                int[] place = new int[4];
                int[] layer = layerPixels(img, r.prepared(), place);
                if (layer == null) {
                    // It changes nothing: the client shows it as it is, with no layer at all.
                    armed.put(r.hoverKey(), new Armed(r.hoverKey(), include, exclude, priority));
                    continue;
                }
                // One that needs more than half the maps would leave room for no other (a hover that changes a large panel).
                if (layers.mapsFor(layer, place[0], place[1], place[2], place[3]) > layers.capacity() / 2) {
                    never.add(r.hoverKey());
                    refused++;
                    continue;
                }
                w = new Worked(new Armed(r.hoverKey(), include, exclude, priority), layer, place);
                worked.put(r.hoverKey(), w);
            }
            Armed a = w.armed;
            while (!layers.arm(a.key, a.include, a.exclude, a.priority, w.layer, w.place[0], w.place[1], w.place[2], w.place[3])) {
                if (!evict(px, py, a.include)) break;
            }
            // No room near the pointer now: armed from what was worked out when the pointer comes closer.
            if (!layers.armed(a.key)) continue;
            armed.put(a.key, a);
            armedCount++;
        }
    }

    /** A layer worked out over the picture the layers are of: where it shows, its pixels and their place. */
    private record Worked(Armed armed, int[] layer, int[] place) {}

    /**
     * The pixels of [p]'s hover over [img] (the picture it was worked out over) in the 2x2 blocks it changes, transparent
     * in the rest, cut to those blocks: the view's own picture shows where the layer is transparent, a map with nothing
     * changed is not taken at all, and what is sent compresses to nearly nothing. Its place in [place] (x, y, width,
     * height, all even); null if it changes nothing.
     */
    private static int[] layerPixels(BufferedImage img, Repaint.Prepared p, int[] place) {
        Rectangle at = p.at();
        int cw = img.getWidth(), ch = img.getHeight();
        int x0 = Math.max(0, at.x & ~1), y0 = Math.max(0, at.y & ~1);
        int x1 = Math.min(cw, (at.x + at.width + 1) & ~1), y1 = Math.min(ch, (at.y + at.height + 1) & ~1);
        if (x1 <= x0 || y1 <= y0) return null;
        // The picture may be cut from a taller one (ScrollCache): its rows start where its raster says.
        java.awt.image.WritableRaster raster = img.getRaster();
        int stride = ((java.awt.image.SinglePixelPackedSampleModel) raster.getSampleModel()).getScanlineStride();
        DataBufferInt db = (DataBufferInt) raster.getDataBuffer();
        int offset = db.getOffset() - raster.getSampleModelTranslateY() * stride - raster.getSampleModelTranslateX();
        int[] base = db.getData(), px = p.pixels();
        int bw = (x1 - x0) / 2, bh = (y1 - y0) / 2;
        boolean[] changed = new boolean[bw * bh];
        int bx0 = bw, by0 = bh, bx1 = -1, by1 = -1;
        for (int y = Math.max(y0, at.y); y < Math.min(y1, at.y + at.height); y++) {
            int b = offset + y * stride, s = (y - at.y) * at.width - at.x, row = (y - y0) / 2 * bw;
            for (int x = Math.max(x0, at.x); x < Math.min(x1, at.x + at.width); x++) {
                if (px[s + x] == base[b + x]) continue;
                int bx = (x - x0) / 2, by = (y - y0) / 2;
                changed[row + bx] = true;
                bx0 = Math.min(bx0, bx);
                bx1 = Math.max(bx1, bx);
                by0 = Math.min(by0, by);
                by1 = Math.max(by1, by);
            }
        }
        if (bx1 < 0) return null;
        int lx = x0 + bx0 * 2, ly = y0 + by0 * 2, lw = (bx1 - bx0 + 1) * 2, lh = (by1 - by0 + 1) * 2;
        int[] out = new int[lw * lh];
        for (int y = ly; y < ly + lh; y++) {
            int b = offset + y * stride, s = (y - at.y) * at.width - at.x, row = (y - y0) / 2 * bw, o = (y - ly) * lw - lx;
            boolean inY = y >= at.y && y < at.y + at.height;
            for (int x = lx; x < lx + lw; x++) {
                if (!changed[row + (x - x0) / 2]) continue;
                out[o + x] = inY && x >= at.x && x < at.x + at.width ? px[s + x] : base[b + x];
            }
        }
        place[0] = lx;
        place[1] = ly;
        place[2] = lw;
        place[3] = lh;
        return out;
    }

    /**
     * Whether the client, its pointer at canvas pixel (x, y) over [f] (drawn in [drawnHover]), shows the picture of hover
     * [key] as it is: the layer of that key in front, or no layer at all if [key] is what the picture was drawn in. Any
     * layer in front that the pointer is not over is taken off first. On the server thread, as the pointer is reported.
     */
    synchronized boolean shows(HtmlRenderer.Frame f, int x, int y, List<Element> key, List<Element> drawnHover) {
        if (f != of || armed.isEmpty()) return key.equals(drawnHover);
        while (true) {
            Armed top = null;
            for (Armed a : armed.values()) if (a.showsAt(x, y) && (top == null || a.priority > top.priority)) top = a;
            if (top == null) return key.equals(ofHover);
            if (top.key.equals(key)) return true;
            // The pointer is inside it (an element of its own in the target): part of the picture is right, and the
            // server draws the rest.
            if (key.containsAll(top.key) && !key.equals(ofHover)) return false;
            armed.remove(top.key);
            layers.disarm(top.key);
            corrected++;
        }
    }

    /** Whether hover [key] has a layer over [f]. */
    synchronized boolean armed(HtmlRenderer.Frame f, List<Element> key) {
        return f == of && armed.containsKey(key);
    }

    synchronized int count() {
        return armed.size();
    }

    /**
     * Takes off the layer furthest from the pointer at (px, py), if it is further than [include] (the one that needs
     * the maps); false if there is none.
     */
    private boolean evict(int px, int py, int[] include) {
        Armed far = null;
        double best = distance(include, px, py);
        for (Armed a : armed.values()) {
            double d = distance(a.include, px, py);
            if (d > best) {
                best = d;
                far = a;
            }
        }
        if (far == null) return false;
        armed.remove(far.key);
        layers.disarm(far.key);
        return true;
    }

    private static double distance(int[] r, int px, int py) {
        return Math.hypot(Math.max(r[0], Math.min(px, r[2])) - px, Math.max(r[1], Math.min(py, r[3])) - py);
    }

    /** The bounds of [boxes] (CSS pixels of the view) in canvas pixels, {left, top, right, bottom}, inside the canvas; null if empty. */
    private static int[] box(List<Rectangle2D> boxes, double s, int cw, int ch) {
        if (boxes == null || boxes.isEmpty()) return null;
        Rectangle2D u = null;
        for (Rectangle2D b : boxes) {
            if (u == null) u = (Rectangle2D) b.clone();
            else u.add(b);
        }
        // A canvas pixel x is over the box when x / scale is inside it (Session.at): from ceil(left * scale) to ceil(right * scale).
        int l = Math.max(0, (int) Math.ceil(u.getMinX() * s - 1e-9)), t = Math.max(0, (int) Math.ceil(u.getMinY() * s - 1e-9));
        int r = Math.min(cw, (int) Math.ceil(u.getMaxX() * s - 1e-9)), b = Math.min(ch, (int) Math.ceil(u.getMaxY() * s - 1e-9));
        return r > l && b > t ? new int[]{l, t, r, b} : null;
    }

    /** The boxes of [f]'s pointer regions whose key is [key], in CSS pixels of the view. */
    private static List<Rectangle2D> boxesOf(HtmlRenderer.Frame f, List<Element> key, Map<Element, List<Element>> keys) {
        List<Rectangle2D> out = new ArrayList<>();
        for (Painter.Hit h : f.hits()) {
            if (!h.pointer() || !keyOf(f, h.element(), keys).equals(key)) continue;
            Rectangle2D b = h.shape().getBounds2D();
            Rectangle2D box = new Rectangle2D.Double(b.getX(), b.getY() - f.scrollY(), b.getWidth(), b.getHeight());
            if (h.clip() != null) {
                Rectangle2D c = h.clip().getBounds2D();
                Rectangle2D.intersect(box, new Rectangle2D.Double(c.getX(), c.getY() - f.scrollY(), c.getWidth(), c.getHeight()), box);
            }
            if (!box.isEmpty()) out.add(box);
        }
        return out;
    }

    private static List<Element> keyOf(HtmlRenderer.Frame f, Element e, Map<Element, List<Element>> keys) {
        return keys.computeIfAbsent(e, el -> f.page().styles.stateKey(el, false));
    }

    /**
     * Whether, at nearly every point of [include] outside [exclude], the pointer is over [key]'s target or something inside
     * it: tried at most {@link #SAMPLES} points, {@link #STEP} canvas pixels apart or more, against [f]'s pointer regions,
     * as {@link HtmlRenderer.Frame#elementAt}. Every point is inside nothing hovered ([key] empty).
     */
    private static boolean fits(HtmlRenderer.Frame f, List<Element> key, int[] include, int[] exclude, Map<Element, List<Element>> keys) {
        if (key.isEmpty()) return true;
        double s = f.scale(), sy = f.scrollY();
        Rectangle2D area = new Rectangle2D.Double(include[0] / s, include[1] / s + sy, (include[2] - include[0]) / s, (include[3] - include[1]) / s);
        // Only the regions that can be under a point of it, top first.
        List<Painter.Hit> near = new ArrayList<>();
        List<Painter.Hit> hits = f.hits();
        for (int i = hits.size() - 1; i >= 0; i--) {
            Painter.Hit h = hits.get(i);
            if (h.pointer() && h.shape().getBounds2D().intersects(area)) near.add(h);
        }
        int step = Math.max(STEP, (int) Math.ceil(Math.sqrt((double) (include[2] - include[0]) * (include[3] - include[1]) / SAMPLES)));
        int tried = 0, wrong = 0;
        Point2D.Double p = new Point2D.Double();
        for (int y = include[1] + step / 2; y < include[3]; y += step) {
            for (int x = include[0] + step / 2; x < include[2]; x += step) {
                if (exclude != null && in(exclude, x, y)) continue;
                tried++;
                p.setLocation(x / s, y / s + sy);
                Element at = null;
                for (Painter.Hit h : near) {
                    if (h.clip() != null && !h.clip().contains(p)) continue;
                    if (h.shape().contains(p)) {
                        at = h.element();
                        break;
                    }
                }
                if (!keyOf(f, at, keys).containsAll(key)) wrong++;
            }
        }
        return tried > 0 && wrong <= tried * SLACK;
    }

    /**
     * Higher for a target painted later (in front), 1 to {@code HoverLayers.PRIORITIES - 1}, from where its last pointer
     * region comes in [f]'s painting order; 0 for nothing hovered, which shows only where no other layer does.
     */
    private static int priority(HtmlRenderer.Frame f, List<Element> key, Map<Element, List<Element>> keys) {
        if (key.isEmpty()) return 0;
        List<Painter.Hit> hits = f.hits();
        int last = 0;
        for (int i = 0; i < hits.size(); i++) if (hits.get(i).pointer() && keyOf(f, hits.get(i).element(), keys).equals(key)) last = i;
        int top = HoverLayers.PRIORITIES - 1;
        return 1 + (int) Math.min(top - 1, (long) last * top / Math.max(1, hits.size()));
    }

    private static boolean in(int[] r, int x, int y) {
        return x >= r[0] && y >= r[1] && x < r[2] && y < r[3];
    }

    private static boolean overlaps(int[] a, int[] b) {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    /** Whether [a] is inside [b]. */
    private static boolean inside(int[] a, int[] b) {
        return a[0] >= b[0] && a[1] >= b[1] && a[2] <= b[2] && a[3] <= b[3];
    }
}
