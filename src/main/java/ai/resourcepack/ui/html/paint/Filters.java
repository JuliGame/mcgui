package ai.resourcepack.ui.html.paint;

import ai.resourcepack.ui.html.css.Values;
import ai.resourcepack.ui.html.style.Len;
import ai.resourcepack.ui.html.style.Style;

import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Locale;

/** filter (on a layer) and backdrop-filter (on what is already painted behind a box). */
final class Filters {
    private Filters() {}

    static void apply(BufferedImage img, String filter, double scale, Style s) {
        if (filter == null || filter.equals("none")) return;
        for (String fn : Values.splitSpaces(filter)) {
            int open = fn.indexOf('(');
            if (open < 0 || !fn.endsWith(")")) continue;
            String name = fn.substring(0, open).toLowerCase(Locale.ROOT);
            String arg = fn.substring(open + 1, fn.length() - 1).trim();
            if (name.equals("blur")) {
                Len l = Len.parse(arg.isEmpty() ? "0" : arg, s.context);
                if (l != null) Blur.blur(img, l.of(0) * scale);
                continue;
            }
            if (name.equals("drop-shadow")) continue;
            double amount = amount(arg, name);
            colour(Painter.pixels(img), name, amount);
        }
    }

    /** How much [filter] blurs, in CSS px (each blur() is a sigma, as {@link #apply} uses it): a picture spreads about three times this. */
    static double blurRadius(String filter, Style s) {
        if (filter == null || filter.equals("none")) return 0;
        double sum = 0;
        for (String fn : Values.splitSpaces(filter)) {
            if (!fn.toLowerCase(Locale.ROOT).startsWith("blur(") || !fn.endsWith(")")) continue;
            String arg = fn.substring(5, fn.length() - 1).trim();
            Len l = Len.parse(arg.isEmpty() ? "0" : arg, s.context);
            if (l != null) sum += Math.max(0, l.of(0));
        }
        return sum;
    }

    private static double amount(String arg, String name) {
        if (arg.isEmpty()) return name.equals("hue-rotate") ? 0 : 1;
        if (name.equals("hue-rotate")) {
            Double d = Values.angle(arg);
            return d == null ? 0 : d;
        }
        try {
            return arg.endsWith("%") ? Double.parseDouble(arg.substring(0, arg.length() - 1)) / 100 : Double.parseDouble(arg);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static void colour(int[] px, String name, double a) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int alpha = p >>> 24;
            if (alpha == 0) continue;
            double r = (p >> 16 & 255), g = (p >> 8 & 255), b = (p & 255);
            switch (name) {
                case "brightness" -> {
                    r *= a;
                    g *= a;
                    b *= a;
                }
                case "contrast" -> {
                    r = (r - 128) * a + 128;
                    g = (g - 128) * a + 128;
                    b = (b - 128) * a + 128;
                }
                case "grayscale" -> {
                    double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                    double t = Math.min(1, a);
                    r = r + (y - r) * t;
                    g = g + (y - g) * t;
                    b = b + (y - b) * t;
                }
                case "invert" -> {
                    double t = Math.min(1, a);
                    r = r + (255 - 2 * r) * t;
                    g = g + (255 - 2 * g) * t;
                    b = b + (255 - 2 * b) * t;
                }
                case "sepia" -> {
                    double t = Math.min(1, a);
                    double sr = 0.393 * r + 0.769 * g + 0.189 * b, sg = 0.349 * r + 0.686 * g + 0.168 * b, sb = 0.272 * r + 0.534 * g + 0.131 * b;
                    r = r + (sr - r) * t;
                    g = g + (sg - g) * t;
                    b = b + (sb - b) * t;
                }
                case "saturate" -> {
                    double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                    r = y + (r - y) * a;
                    g = y + (g - y) * a;
                    b = y + (b - y) * a;
                }
                case "opacity" -> alpha = (int) Math.round(alpha * Math.max(0, Math.min(1, a)));
                case "hue-rotate" -> {
                    double rad = Math.toRadians(a), cos = Math.cos(rad), sin = Math.sin(rad);
                    double nr = r * (0.213 + cos * 0.787 - sin * 0.213) + g * (0.715 - cos * 0.715 - sin * 0.715) + b * (0.072 - cos * 0.072 + sin * 0.928);
                    double ng = r * (0.213 - cos * 0.213 + sin * 0.143) + g * (0.715 + cos * 0.285 + sin * 0.140) + b * (0.072 - cos * 0.072 - sin * 0.283);
                    double nb = r * (0.213 - cos * 0.213 - sin * 0.787) + g * (0.715 - cos * 0.715 + sin * 0.715) + b * (0.072 + cos * 0.928 + sin * 0.072);
                    r = nr;
                    g = ng;
                    b = nb;
                }
                default -> {}
            }
            px[i] = (alpha << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
        }
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /** The blur radius (CSS px) of [s]'s backdrop-filter, 0 for none. */
    static double backdropBlur(Style s) {
        double blur = 0;
        for (String fn : Values.splitSpaces(s.backdropFilter)) {
            if (fn.toLowerCase(Locale.ROOT).startsWith("blur(")) {
                Len l = Len.parse(fn.substring(5, fn.length() - 1), s.context);
                if (l != null) blur = l.of(0);
            }
        }
        return blur;
    }

    /**
     * How far (device px) past a backdrop's edge, and past the clip, its blur reads what is painted: three sigma, the boxes'
     * rounding, and the grid cell its picture is made on.
     */
    static int backdropPad(double blur, double scale) {
        double sigma = blur / 2 * scale;
        return (int) Math.ceil(3 * sigma) + Blur.step(sigma) + 2;
    }

    /**
     * backdrop-filter: blur (and tint) what is already painted under [shape], then put it back inside the shape.
     * [originX] and [originY] are where [surface]'s top-left is in the picture it is a piece of (a band): the blur is worked
     * out on a grid fixed to that picture, so every way of painting it (whole, in bands, a rectangle at a time) blurs alike.
     */
    static void backdrop(Graphics2D g, BufferedImage surface, Shape shape, Style s, double scale, int originX, int originY) {
        String filter = s.backdropFilter;
        AffineTransform dev = g.getTransform();
        java.awt.Rectangle bounds = new java.awt.Rectangle(0, 0, surface.getWidth(), surface.getHeight());
        java.awt.Rectangle r = dev.createTransformedShape(shape).getBounds().intersection(bounds);
        if (r.isEmpty()) return;
        double blur = backdropBlur(s);
        double sigma = blur / 2 * scale;
        int k = Blur.step(sigma);
        int pad = backdropPad(blur, scale);
        java.awt.Rectangle grab = new java.awt.Rectangle(r);
        grab.grow(pad, pad);
        // Only what can be seen: a partial repaint clips to the part that changed.
        if (g.getClip() != null) {
            java.awt.Rectangle clipDev = dev.createTransformedShape(g.getClip()).getBounds();
            clipDev.grow(pad, pad);
            grab = grab.intersection(clipDev);
            if (grab.isEmpty()) return;
        }
        grab = grab.intersection(bounds);
        if (grab.isEmpty()) return;
        // Out to whole cells of the picture's grid, where the surface has nothing (its edge) the edge pixels carry on.
        int gx = Math.floorDiv(grab.x + originX, k) * k - originX, gy = Math.floorDiv(grab.y + originY, k) * k - originY;
        int gw = Math.floorDiv(grab.x + grab.width + originX + k - 1, k) * k - originX - gx;
        int gh = Math.floorDiv(grab.y + grab.height + originY + k - 1, k) * k - originY - gy;
        // Drawn rather than copied raw, so a premultiplied surface arrives as straight colour for the filters below.
        BufferedImage copy = new BufferedImage(gw, gh, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D cg = copy.createGraphics();
        cg.setComposite(java.awt.AlphaComposite.Src);
        cg.drawImage(surface.getSubimage(grab.x, grab.y, grab.width, grab.height), grab.x - gx, grab.y - gy, null);
        cg.dispose();
        extend(Painter.pixels(copy), gw, gh, grab.x - gx, grab.y - gy, grab.width, grab.height);
        // A backdrop is opaque where something was drawn; blur it like a photo (small, for a wide blur).
        copy = Blur.blurredCopy(copy, sigma, k);
        for (String fn : Values.splitSpaces(filter)) {
            String l = fn.toLowerCase(Locale.ROOT);
            int open = l.indexOf('(');
            if (open < 0 || l.startsWith("blur")) continue;
            String name = l.substring(0, open);
            colour(Painter.pixels(copy), name, amount(l.substring(open + 1, l.length() - 1).trim(), name));
        }
        Shape old = g.getClip();
        g.clip(shape);
        AffineTransform saved = g.getTransform();
        Shape devClip = g.getClip() == null ? null : dev.createTransformedShape(g.getClip());
        g.setTransform(new AffineTransform());
        if (devClip != null) g.setClip(devClip);
        Object oldInterpolation = g.getRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(copy, gx, gy, gw, gh, null);
        if (oldInterpolation != null) g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, oldInterpolation);
        g.setTransform(saved);
        g.setClip(old);
    }

    /** Fills what is round the [w] x [h] block at ([x], [y]) in a [width] x [height] picture with the block's nearest edge pixels. */
    private static void extend(int[] px, int width, int height, int x, int y, int w, int h) {
        if (x == 0 && y == 0 && w == width && h == height) return;
        for (int row = y; row < y + h; row++) {
            int base = row * width;
            for (int col = 0; col < x; col++) px[base + col] = px[base + x];
            for (int col = x + w; col < width; col++) px[base + col] = px[base + x + w - 1];
        }
        for (int row = 0; row < y; row++) System.arraycopy(px, y * width, px, row * width, width);
        for (int row = y + h; row < height; row++) System.arraycopy(px, (y + h - 1) * width, px, row * width, width);
    }

}
