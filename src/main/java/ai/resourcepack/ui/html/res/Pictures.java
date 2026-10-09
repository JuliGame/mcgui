package ai.resourcepack.ui.html.res;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Decoding images: bitmaps through ImageIO, SVG through JSVG. */
public final class Pictures {
    private Pictures() {}

    /** A picture from file bytes, or null if they are neither a bitmap Java reads nor an SVG. */
    public static Picture decode(byte[] data, String nameHint) {
        if (data == null || data.length == 0) return null;
        if (looksLikeSvg(data, nameHint)) return svg(new String(data, StandardCharsets.UTF_8));
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            return img == null ? null : new Raster(img);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean looksLikeSvg(byte[] data, String name) {
        if (name != null && name.toLowerCase().contains(".svg")) return true;
        String head = new String(data, 0, Math.min(data.length, 512), StandardCharsets.UTF_8).trim();
        return head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg"));
    }

    public static Picture svg(String markup) {
        try {
            SVGDocument doc = new SVGLoader().load(new ByteArrayInputStream(markup.getBytes(StandardCharsets.UTF_8)), URI.create("about:svg"),
                    LoaderContext.createDefault());
            if (doc == null) return null;
            double w = 0, h = 0;
            // Only a width and height the SVG states are a natural size; a bare viewBox is only a ratio.
            String open = markup.substring(0, Math.max(0, markup.indexOf('>')));
            if (open.matches("(?s).*\\swidth\\s*=.*") && open.matches("(?s).*\\sheight\\s*=.*")) {
                w = doc.size().getWidth();
                h = doc.size().getHeight();
            }
            return new Svg(doc, w, h);
        } catch (RuntimeException | Error e) {
            return null;
        }
    }

    private record Raster(BufferedImage img) implements Picture {
        @Override public double width() { return img.getWidth(); }
        @Override public double height() { return img.getHeight(); }

        @Override
        public void draw(Graphics2D g, double x, double y, double w, double h) {
            if (w <= 0 || h <= 0) return;
            Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            // Small pixel art scaled up (a Minecraft texture) stays crisp; photos are smoothed.
            double scale = w / img.getWidth() * Math.abs(g.getTransform().getScaleX());
            boolean pixelArt = img.getWidth() <= 64 && img.getHeight() <= 64 && scale >= 2;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, pixelArt ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                    : RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            AffineTransform t = new AffineTransform();
            t.translate(x, y);
            t.scale(w / img.getWidth(), h / img.getHeight());
            g.drawImage(img, t, null);
            if (old != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
        }
    }

    private record Svg(SVGDocument doc, double w, double h) implements Picture {
        @Override public double width() { return w; }
        @Override public double height() { return h; }

        @Override
        public void draw(Graphics2D g, double x, double y, double dw, double dh) {
            if (dw <= 0 || dh <= 0) return;
            Graphics2D c = (Graphics2D) g.create();
            try {
                doc.render((java.awt.Component) null, c, new ViewBox((float) x, (float) y, (float) dw, (float) dh));
            } catch (RuntimeException e) {
                // A broken SVG draws nothing.
            } finally {
                c.dispose();
            }
        }
    }
}
