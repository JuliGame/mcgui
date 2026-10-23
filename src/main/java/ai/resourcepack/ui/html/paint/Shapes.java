package ai.resourcepack.ui.html.paint;

import ai.resourcepack.ui.html.layout.Box;
import ai.resourcepack.ui.html.style.Len;

import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/** Rounded rectangles with a radius per corner, as CSS draws border boxes. */
final class Shapes {
    private Shapes() {}

    private static final double K = 0.5522847498;

    /** Corner radii {tlx, tly, trx, try, brx, bry, blx, bly} of a box's border box, scaled down so they fit. */
    static double[] radii(Box b) {
        double[] r = new double[8];
        Len[][] spec = b.style.radius;
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            r[i * 2] = Math.max(0, spec[i][0].of(b.w));
            r[i * 2 + 1] = Math.max(0, spec[i][1].of(b.h));
            if (r[i * 2] > 0 && r[i * 2 + 1] > 0) any = true;
            else {
                r[i * 2] = 0;
                r[i * 2 + 1] = 0;
            }
        }
        if (!any) return null;
        double f = 1;
        f = Math.min(f, ratio(b.w, r[0] + r[2]));
        f = Math.min(f, ratio(b.w, r[6] + r[4]));
        f = Math.min(f, ratio(b.h, r[1] + r[7]));
        f = Math.min(f, ratio(b.h, r[3] + r[5]));
        if (f < 1) for (int i = 0; i < 8; i++) r[i] *= f;
        return r;
    }

    private static double ratio(double side, double sum) {
        return sum <= 0 ? 1 : side / sum;
    }

    /** The radii [r] shrunk by edge widths {top, right, bottom, left}: the inner edge of a border. */
    static double[] inset(double[] r, double top, double right, double bottom, double left) {
        if (r == null) return null;
        return new double[]{Math.max(0, r[0] - left), Math.max(0, r[1] - top), Math.max(0, r[2] - right), Math.max(0, r[3] - top),
                Math.max(0, r[4] - right), Math.max(0, r[5] - bottom), Math.max(0, r[6] - left), Math.max(0, r[7] - bottom)};
    }

    /** The radii grown by [d] (a shadow's spread), staying round. */
    static double[] outset(double[] r, double d) {
        if (r == null) return null;
        double[] o = new double[8];
        for (int i = 0; i < 8; i++) o[i] = r[i] > 0 ? Math.max(0, r[i] + d) : 0;
        return o;
    }

    static Shape rect(double x, double y, double w, double h, double[] r) {
        if (w <= 0 || h <= 0) return new Rectangle2D.Double(x, y, Math.max(0, w), Math.max(0, h));
        if (r == null) return new Rectangle2D.Double(x, y, w, h);
        Path2D.Double p = new Path2D.Double();
        double tlx = r[0], tly = r[1], trx = r[2], tryy = r[3], brx = r[4], bry = r[5], blx = r[6], bly = r[7];
        p.moveTo(x + tlx, y);
        p.lineTo(x + w - trx, y);
        if (trx > 0) p.curveTo(x + w - trx + trx * K, y, x + w, y + tryy - tryy * K, x + w, y + tryy);
        p.lineTo(x + w, y + h - bry);
        if (brx > 0) p.curveTo(x + w, y + h - bry + bry * K, x + w - brx + brx * K, y + h, x + w - brx, y + h);
        p.lineTo(x + blx, y + h);
        if (blx > 0) p.curveTo(x + blx - blx * K, y + h, x, y + h - bly + bly * K, x, y + h - bly);
        p.lineTo(x, y + tly);
        if (tlx > 0) p.curveTo(x, y + tly - tly * K, x + tlx - tlx * K, y, x + tlx, y);
        p.closePath();
        return p;
    }

    /**
     * Fills [outer] less [hole] (null for none) with [g]'s colour as whole device pixels, when every edge of both lands on
     * a pixel edge under [g]'s transform (a scale and a move, as boxes at 2x are): exactly the pixels an antialiased
     * fill of that ring covers, each wholly, without rasterising it (a ring the size of its box, edge to edge, for a
     * 2px border or a sharp inset shadow). Returns false, painting nothing, otherwise.
     */
    static boolean fillPixels(Graphics2D g, Rectangle2D outer, Rectangle2D hole) {
        AffineTransform t = g.getTransform();
        if ((t.getType() & ~(AffineTransform.TYPE_TRANSLATION | AffineTransform.TYPE_UNIFORM_SCALE | AffineTransform.TYPE_GENERAL_SCALE)) != 0) return false;
        double sx = t.getScaleX(), sy = t.getScaleY(), tx = t.getTranslateX(), ty = t.getTranslateY();
        int ox0 = pixel(outer.getMinX() * sx + tx), oy0 = pixel(outer.getMinY() * sy + ty);
        int ox1 = pixel(outer.getMaxX() * sx + tx), oy1 = pixel(outer.getMaxY() * sy + ty);
        if (ox0 == NOT || oy0 == NOT || ox1 == NOT || oy1 == NOT) return false;
        int hx0 = 0, hy0 = 0, hx1 = 0, hy1 = 0;
        if (hole != null && hole.getWidth() > 0 && hole.getHeight() > 0) {
            hx0 = pixel(hole.getMinX() * sx + tx);
            hy0 = pixel(hole.getMinY() * sy + ty);
            hx1 = pixel(hole.getMaxX() * sx + tx);
            hy1 = pixel(hole.getMaxY() * sy + ty);
            if (hx0 == NOT || hy0 == NOT || hx1 == NOT || hy1 == NOT) return false;
            // Only the part of the hole inside [outer] takes anything away.
            hx0 = Math.max(hx0, ox0);
            hy0 = Math.max(hy0, oy0);
            hx1 = Math.min(hx1, ox1);
            hy1 = Math.min(hy1, oy1);
        }
        g.setTransform(new AffineTransform());
        if (hx1 <= hx0 || hy1 <= hy0) {
            if (ox1 > ox0 && oy1 > oy0) g.fill(new java.awt.Rectangle(ox0, oy0, ox1 - ox0, oy1 - oy0));
        } else {
            // Above the hole, below it, and either side of it.
            if (hy0 > oy0) g.fill(new java.awt.Rectangle(ox0, oy0, ox1 - ox0, hy0 - oy0));
            if (oy1 > hy1) g.fill(new java.awt.Rectangle(ox0, hy1, ox1 - ox0, oy1 - hy1));
            if (hx0 > ox0) g.fill(new java.awt.Rectangle(ox0, hy0, hx0 - ox0, hy1 - hy0));
            if (ox1 > hx1) g.fill(new java.awt.Rectangle(hx1, hy0, ox1 - hx1, hy1 - hy0));
        }
        g.setTransform(t);
        return true;
    }

    /**
     * Clips [g] to [s] as {@code g.clip} does. Inside a square clip that holds all of [s] (a page's overflow: hidden),
     * that is [s] itself: set as it is, not intersected through an Area (the same pixels: [s]'s edges, a rounded
     * corner's curve included, come through an Area unchanged when nothing cuts them).
     */
    static void clip(Graphics2D g, Shape s) {
        if (g.getClip() instanceof Rectangle2D c && c.contains(s.getBounds2D())) g.setClip(s);
        else g.clip(s);
    }

    private static final int NOT = Integer.MIN_VALUE;

    /** [v] as a whole pixel edge, if it is one (to well within the sixteenth where antialiasing samples); else {@link #NOT}. */
    private static int pixel(double v) {
        double r = Math.rint(v);
        return Math.abs(v - r) < 1e-6 && Math.abs(r) < 1e9 ? (int) r : NOT;
    }
}
