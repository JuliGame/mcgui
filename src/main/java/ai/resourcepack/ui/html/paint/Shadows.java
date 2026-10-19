package ai.resourcepack.ui.html.paint;

import ai.resourcepack.ui.html.RenderBudget;
import ai.resourcepack.ui.html.css.Values;
import ai.resourcepack.ui.html.style.Len;
import ai.resourcepack.ui.html.style.Style;
import ai.resourcepack.ui.html.text.TextShaper;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** box-shadow and text-shadow, blurred for real. */
final class Shadows {
    private Shadows() {}

    record Shadow(double x, double y, double blur, double spread, int color, boolean inset) {}

    /**
     * Blurs wider than this (CSS px) are this wide, as Firefox caps them: a shadow blurred further is a
     * faint haze over a whole picture, and each one costs a blur and a blend that big, per text run.
     */
    static final double MAX_BLUR = 300;

    static List<Shadow> parse(String value, Style s) {
        List<Shadow> out = new ArrayList<>();
        if (value == null || value.isBlank() || value.trim().equalsIgnoreCase("none")) return out;
        for (String part : Values.splitCommas(value)) {
            List<Double> lens = new ArrayList<>();
            Integer color = null;
            boolean inset = false;
            for (String tok : Values.splitSpaces(part)) {
                String l = tok.toLowerCase(Locale.ROOT);
                if (l.equals("inset")) {
                    inset = true;
                    continue;
                }
                Len len = Len.parse(tok, s.context);
                if (len != null) {
                    lens.add(len.of(0));
                    continue;
                }
                Integer c = Values.color(tok, s.color);
                if (c != null) color = c;
            }
            if (lens.size() < 2) continue;
            out.add(new Shadow(lens.get(0), lens.get(1), lens.size() > 2 ? Math.min(MAX_BLUR, Math.max(0, lens.get(2))) : 0, lens.size() > 3 ? lens.get(3) : 0,
                    color == null ? s.color : color, inset));
        }
        return out;
    }

    /** An outer box shadow for a border box at (x, y), only outside the box itself; [surface] as for {@link #blurred}. */
    static void outer(Graphics2D g, double x, double y, double w, double h, double[] radii, Shadow sh, double scale, java.awt.Rectangle surface, RenderBudget budget) {
        if ((sh.color >>> 24) == 0) return;
        double[] r = Shapes.outset(radii, sh.spread);
        Shape shape = Shapes.rect(x + sh.x - sh.spread, y + sh.y - sh.spread, w + 2 * sh.spread, h + 2 * sh.spread, r);
        Shape old = g.getClip();
        // Browsers cut the box itself out of its outer shadow, so a translucent box shows no shadow through it.
        Area outside = new Area(new Rectangle2D.Double(-1e5, -1e5, 2e5, 2e5));
        outside.subtract(new Area(Shapes.rect(x, y, w, h, radii)));
        g.clip(outside);
        blurred(g, shape, sh.color, sh.blur, scale, surface, budget);
        g.setClip(old);
    }

    /** An inset shadow inside [padding]. */
    static void inner(Graphics2D g, Shape padding, Shadow sh, double scale, java.awt.Rectangle surface, RenderBudget budget) {
        if ((sh.color >>> 24) == 0) return;
        Rectangle2D b = padding.getBounds2D();
        Area ring = new Area(new Rectangle2D.Double(b.getX() - sh.blur * 3 - Math.abs(sh.x) - 10, b.getY() - sh.blur * 3 - Math.abs(sh.y) - 10,
                b.getWidth() + sh.blur * 6 + Math.abs(sh.x) * 2 + 20, b.getHeight() + sh.blur * 6 + Math.abs(sh.y) * 2 + 20));
        Shape hole = AffineTransform.getTranslateInstance(sh.x, sh.y).createTransformedShape(shrink(padding, sh.spread));
        ring.subtract(new Area(hole));
        Shape old = g.getClip();
        g.clip(padding);
        blurred(g, ring, sh.color, sh.blur, scale, surface, budget);
        g.setClip(old);
    }

    private static Shape shrink(Shape s, double d) {
        if (d == 0) return s;
        Rectangle2D b = s.getBounds2D();
        double sx = Math.max(0, b.getWidth() - 2 * d) / Math.max(1e-6, b.getWidth());
        double sy = Math.max(0, b.getHeight() - 2 * d) / Math.max(1e-6, b.getHeight());
        AffineTransform t = new AffineTransform();
        t.translate(b.getCenterX(), b.getCenterY());
        t.scale(sx, sy);
        t.translate(-b.getCenterX(), -b.getCenterY());
        return t.createTransformedShape(s);
    }

    /**
     * Fills [shape] with [color], blurred by [blur] CSS pixels (a Gaussian of sigma blur/2). [surface] is
     * the device bounds of what [g] draws on: only the part of the blur that can land there is worked out.
     */
    static void blurred(Graphics2D g, Shape shape, int color, double blur, double scale, java.awt.Rectangle surface, RenderBudget budget) {
        if (blur <= 0.25) {
            g.setColor(new Color(color, true));
            g.fill(shape);
            return;
        }
        AffineTransform dev = g.getTransform();
        Shape devShape = dev.createTransformedShape(shape);
        double sigma = blur / 2 * scale;
        int pad = (int) Math.ceil(sigma * 3) + 2;
        java.awt.Rectangle r = devShape.getBounds();
        r.grow(pad, pad);
        // A wide blur is worked out small and scaled back up (see Blur.shrink).
        double k = Blur.shrink(sigma);
        int w = Math.max(1, (int) Math.ceil(r.width / k)), h = Math.max(1, (int) Math.ceil(r.height / k));
        // The small picture is drawn at r, w x h pixels stretched to r's size.
        double sx = (double) r.width / w, sy = (double) r.height / h;
        // Only what can be seen: the surface, within the clip. Cut on the small picture's own pixel grid,
        // so the pixels kept are the ones the whole picture would have (bands painted apart agree), and
        // with a margin past the visible edge for the blur and the stretch to read from.
        java.awt.Rectangle vis = surface;
        if (g.getClip() != null) vis = vis.intersection(dev.createTransformedShape(g.getClip()).getBounds());
        int margin = pad + 2 * (int) Math.ceil(k);
        int i0 = Math.max(0, (int) Math.floor((vis.getMinX() - margin - r.x) / sx));
        int j0 = Math.max(0, (int) Math.floor((vis.getMinY() - margin - r.y) / sy));
        int i1 = Math.min(w, (int) Math.ceil((vis.getMaxX() + margin - r.x) / sx));
        int j1 = Math.min(h, (int) Math.ceil((vis.getMaxY() + margin - r.y) / sy));
        if (vis.isEmpty() || i1 <= i0 || j1 <= j0) return;
        if ((long) (i1 - i0) * (j1 - j0) > 25_000_000L) return;
        BufferedImage img = PixelPool.image(i1 - i0, j1 - j0, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D ig = img.createGraphics();
        // Fill with the shadow's colour at zero alpha first, so blurring only ever mixes alpha.
        ig.setComposite(AlphaComposite.Src);
        ig.setColor(new Color(color & 0xFFFFFF, true));
        ig.fillRect(0, 0, img.getWidth(), img.getHeight());
        ig.setComposite(AlphaComposite.SrcOver);
        ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ig.setColor(new Color(color | 0xFF000000));
        ig.translate(-i0, -j0);
        ig.scale(1 / k, 1 / k);
        ig.translate(-r.x, -r.y);
        ig.fill(devShape);
        ig.dispose();
        Blur.blur(img, sigma / k, budget);
        AffineTransform saved = g.getTransform();
        java.awt.Composite oldComposite = g.getComposite();
        Object oldInterpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        float alpha = (color >>> 24) / 255f;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha * (oldComposite instanceof AlphaComposite ac ? ac.getAlpha() : 1)));
        if (i0 == 0 && j0 == 0 && i1 == w && j1 == h) {
            g.setTransform(new AffineTransform());
            g.drawImage(img, r.x, r.y, r.width, r.height, null);
        } else {
            g.setTransform(new AffineTransform(sx, 0, 0, sy, r.x + i0 * sx, r.y + j0 * sy));
            g.drawImage(img, 0, 0, null);
        }
        g.setComposite(oldComposite);
        if (oldInterpolation != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
        g.setTransform(saved);
        PixelPool.give(img);
    }

    static void text(Graphics2D g, TextShaper shaper, String text, Font font, double x, double y, Shadow sh, double scale, java.awt.Rectangle surface, RenderBudget budget) {
        if ((sh.color >>> 24) == 0) return;
        Shape glyphs = shaper.outline(text, font, x, y);
        blurred(g, glyphs, sh.color, sh.blur, scale, surface, budget);
    }
}
