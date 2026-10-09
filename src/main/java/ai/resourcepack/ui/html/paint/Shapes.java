package ai.resourcepack.ui.html.paint;

import ai.resourcepack.ui.html.layout.Box;
import ai.resourcepack.ui.html.style.Len;

import java.awt.Shape;
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
}
