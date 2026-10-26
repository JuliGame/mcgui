package ai.resourcepack.ui.script.engine;

import ai.resourcepack.ui.html.res.Picture;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * One {@code <canvas>} a script draws on (or one it made with {@code document.createElement}): [width] × [height]
 * canvas units, kept as a picture [scale] times that in pixels, so a canvas on a page drawn at twice its CSS size is
 * drawn at twice the detail rather than stretched. The script draws into [back] on its own thread; at the end of a
 * frame that changed it, [back] is copied to [front] (under the page's lock), which is what goes on the screen.
 */
final class ScriptCanvas {
    final int index;
    /** On the page (an element of it), or made by the script and only drawn into others. */
    final boolean onPage;
    int width, height;
    final double scale;
    BufferedImage back, front;
    private Graphics2D g;
    /** Drawn into since [front] was last brought up to date. */
    private boolean changed;
    Canvas2D context;

    ScriptCanvas(int index, boolean onPage, int width, int height, double scale) {
        this.index = index;
        this.onPage = onPage;
        this.scale = scale;
        resize(width, height);
    }

    /** New size, cleared, as setting a canvas's width or height does (even to the same value). */
    void resize(int w, int h) {
        width = Math.max(0, Math.min(w, 4096));
        height = Math.max(0, Math.min(h, 4096));
        int pw = Math.max(1, (int) Math.round(width * scale)), ph = Math.max(1, (int) Math.round(height * scale));
        if (g != null) g.dispose();
        back = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB_PRE);
        front = onPage ? new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB_PRE) : back;
        g = back.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        if (context != null) context.reset();
        changed = true;
    }

    Graphics2D graphics() {
        return g;
    }

    void changed() {
        changed = true;
    }

    /** Whether it changed since the last call; [front] is then made the same as [back]. Under the page's lock. */
    boolean publish() {
        if (!changed) return false;
        changed = false;
        if (front != back) {
            int[] from = ((DataBufferInt) back.getRaster().getDataBuffer()).getData();
            int[] to = ((DataBufferInt) front.getRaster().getDataBuffer()).getData();
            System.arraycopy(from, 0, to, 0, from.length);
        }
        return true;
    }

    // ---------------------------------------------------------------- what drawImage takes

    /** The canvas a script value stands for (a canvas element's {@code __canvas}), or null. */
    static ScriptCanvas of(Object value) {
        if (value instanceof Scriptable o) {
            Object c = ScriptableObject.getProperty(o, "__canvas");
            if (c instanceof Handle h && h.canvas != null) return h.canvas;
        }
        return null;
    }

    /** The pixels a script value draws (a canvas, an image), or null for anything else or a picture not loaded. */
    static BufferedImage imageOf(Object value) {
        ScriptCanvas c = of(value);
        if (c != null) return c.back;
        if (value instanceof Scriptable o) {
            Object img = ScriptableObject.getProperty(o, "__image");
            if (img instanceof Handle h && h.picture != null) return h.pixels();
        }
        return null;
    }

    /** Pixels of [value]'s picture per unit of its size as the script sees it: a canvas's scale, 1 for an image. */
    static double unitsOf(Object value) {
        ScriptCanvas c = of(value);
        return c != null ? c.scale : 1;
    }

    /** What the script holds for a canvas or a picture: opaque to it, read back here. */
    static final class Handle extends ScriptableObject {
        final ScriptCanvas canvas;
        final Picture picture;
        private BufferedImage pixels;

        Handle(ScriptCanvas canvas, Picture picture) {
            this.canvas = canvas;
            this.picture = picture;
        }

        @Override
        public String getClassName() {
            return "Handle";
        }

        /** The picture at its own size, drawn once. */
        BufferedImage pixels() {
            if (pixels == null) {
                int w = Math.max(1, (int) Math.round(picture.width() > 0 ? picture.width() : 300));
                int h = Math.max(1, (int) Math.round(picture.height() > 0 ? picture.height() : 150));
                pixels = new BufferedImage(Math.min(w, 4096), Math.min(h, 4096), BufferedImage.TYPE_INT_ARGB_PRE);
                Graphics2D g = pixels.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                picture.draw(g, 0, 0, pixels.getWidth(), pixels.getHeight());
                g.dispose();
            }
            return pixels;
        }
    }
}
