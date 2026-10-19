package ai.resourcepack.ui.html.res;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** Decoding images: bitmaps through ImageIO, SVG through JSVG. */
public final class Pictures {
    private Pictures() {}

    /** Bitmaps bigger than this many pixels are read at every second (third, ...) pixel: 16 MP is 64 MB decoded. */
    static final long MAX_PIXELS = 16_000_000;
    /** Past this, a bitmap is refused: even read sparsely, one row of it would be a large allocation. */
    private static final int MAX_SIDE = 65_536;

    /** A picture from file bytes, or null if they are neither a bitmap Java reads nor an SVG. */
    public static Picture decode(byte[] data, String nameHint) {
        if (data == null || data.length == 0) return null;
        if (looksLikeSvg(data, nameHint)) return svg(new String(data, StandardCharsets.UTF_8));
        try {
            return bitmap(data);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * ImageIO.read, but looking at the stated size first: a few kilobytes of PNG can state 30000 x 30000
     * and decode to gigabytes. Large photos come out subsampled to under {@link #MAX_PIXELS}, keeping
     * their stated size as their natural size, so a page lays them out and crops them as a browser would.
     */
    static Raster bitmap(byte[] data) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) return null;
                ImageReadParam param = reader.getDefaultReadParam();
                int step = step(w, h);
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage img = reader.read(0, param);
                return img == null ? null : new Raster(img, w, h);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Every [step]th pixel of a w x h bitmap is read, to keep it under {@link #MAX_PIXELS}. */
    private static int step(long w, long h) {
        return w * h > MAX_PIXELS ? (int) Math.ceil(Math.sqrt((double) (w * h) / MAX_PIXELS)) : 1;
    }

    /**
     * About how many bytes [data] takes once decoded by {@link #decode} (four per pixel read), from
     * its header alone; 0 for an SVG (drawn from its markup each time) or what is no bitmap at all.
     */
    public static long decodedBytes(byte[] data, String nameHint) {
        if (data == null || data.length == 0 || looksLikeSvg(data, nameHint)) return 0;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (in == null) return 0;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return 0;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) return 0;
                int step = step(w, h);
                return ((w + step - 1) / step) * ((h + step - 1) / step) * 4;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return 0;
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

    /** A bitmap, [width] x [height] as its file states, which [img] may hold at a lower resolution. */
    record Raster(BufferedImage img, double width, double height) implements Picture {

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
