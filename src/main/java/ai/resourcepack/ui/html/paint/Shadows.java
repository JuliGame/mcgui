package ai.resourcepack.ui.html.paint;

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
            out.add(new Shadow(lens.get(0), lens.get(1), lens.size() > 2 ? Math.max(0, lens.get(2)) : 0, lens.size() > 3 ? lens.get(3) : 0,
                    color == null ? s.color : color, inset));
        }
        return out;
    }

    /** An outer box shadow for a border box at (x, y), only outside the box itself. */
    static void outer(Graphics2D g, double x, double y, double w, double h, double[] radii, Shadow sh, double scale) {
        if ((sh.color >>> 24) == 0) return;
        double[] r = Shapes.outset(radii, sh.spread);
        Shape shape = Shapes.rect(x + sh.x - sh.spread, y + sh.y - sh.spread, w + 2 * sh.spread, h + 2 * sh.spread, r);
        Shape old = g.getClip();
        // Browsers cut the box itself out of its outer shadow, so a translucent box shows no shadow through it.
        Area outside = new Area(new Rectangle2D.Double(-1e5, -1e5, 2e5, 2e5));
        outside.subtract(new Area(Shapes.rect(x, y, w, h, radii)));
        g.clip(outside);
        blurred(g, shape, sh.color, sh.blur, scale);
        g.setClip(old);
    }

    /** An inset shadow inside [padding]. */
    static void inner(Graphics2D g, Shape padding, Shadow sh, double scale) {
        if ((sh.color >>> 24) == 0) return;
        Rectangle2D b = padding.getBounds2D();
        Area ring = new Area(new Rectangle2D.Double(b.getX() - sh.blur * 3 - Math.abs(sh.x) - 10, b.getY() - sh.blur * 3 - Math.abs(sh.y) - 10,
                b.getWidth() + sh.blur * 6 + Math.abs(sh.x) * 2 + 20, b.getHeight() + sh.blur * 6 + Math.abs(sh.y) * 2 + 20));
        Shape hole = AffineTransform.getTranslateInstance(sh.x, sh.y).createTransformedShape(shrink(padding, sh.spread));
        ring.subtract(new Area(hole));
        Shape old = g.getClip();
        g.clip(padding);
        blurred(g, ring, sh.color, sh.blur, scale);
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

    /** Fills [shape] with [color], blurred by [blur] CSS pixels (a Gaussian of sigma blur/2). */
    static void blurred(Graphics2D g, Shape shape, int color, double blur, double scale) {
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
        // Only what can still be seen: the clip, grown by the blur.
        if (g.getClip() != null) {
            java.awt.Rectangle clip = dev.createTransformedShape(g.getClip()).getBounds();
            clip.grow(pad, pad);
            r = r.intersection(clip);
            if (r.isEmpty()) return;
        }
        r.grow(pad, pad);
        if ((long) r.width * r.height > 25_000_000L) return;
        BufferedImage img = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ig = img.createGraphics();
        // Fill with the shadow's colour at zero alpha first, so blurring only ever mixes alpha.
        ig.setComposite(AlphaComposite.Src);
        ig.setColor(new Color(color & 0xFFFFFF, true));
        ig.fillRect(0, 0, r.width, r.height);
        ig.setComposite(AlphaComposite.SrcOver);
        ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ig.setColor(new Color(color | 0xFF000000));
        ig.translate(-r.x, -r.y);
        ig.fill(devShape);
        ig.dispose();
        Blur.blur(img, sigma);
        AffineTransform saved = g.getTransform();
        g.setTransform(new AffineTransform());
        java.awt.Composite oldComposite = g.getComposite();
        float alpha = (color >>> 24) / 255f;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha * (oldComposite instanceof AlphaComposite ac ? ac.getAlpha() : 1)));
        g.drawImage(img, r.x, r.y, null);
        g.setComposite(oldComposite);
        g.setTransform(saved);
    }

    static void text(Graphics2D g, TextShaper shaper, String text, Font font, double x, double y, Shadow sh, double scale) {
        if ((sh.color >>> 24) == 0) return;
        Shape glyphs = shaper.outline(text, font, x, y);
        blurred(g, glyphs, sh.color, sh.blur, scale);
    }
}
