package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.paint.PixelPool;

import java.awt.image.BufferedImage;

/**
 * Where one viewer's whole pictures are painted: an image from {@link PixelPool#picture}, and when it goes back. A whole
 * picture's image used to be new every time (9.4 MB at 2048 x 1152, a humongous array to G1) and the last one garbage.
 *
 * <p>The rule: an image goes back only once nothing can read it. What a viewer shows is read later (a hover is repainted
 * into it, a click looks at it), so the image of the frame shown is kept until another frame is shown; an image shared with
 * others ({@link #share}, a kept first picture) is never handed back; one taken for a render that failed or was replaced
 * is handed back at once. Canvases copy what they are drawn, so they hold no image.
 *
 * <p>For one viewer, one render at a time (its render thread; the next render may be on another thread, after the last
 * has handed over). Each render calls {@link #start} with the frame shown, then {@link #take} for each whole picture it
 * paints, then {@link #shown} or {@link #failed}.
 */
public final class PooledPicture {
    /** The pooled image this viewer drew and has shown: kept while it is shown, handed back at the next render after. */
    private BufferedImage held;
    /** Taken by the render in progress. */
    private BufferedImage fresh;

    /** A render starts with [shown] on the screen (null for nothing): what was held and is no longer shown goes back. */
    public void start(HtmlRenderer.Frame shown) {
        if (held != null && (shown == null || held != shown.image())) {
            PixelPool.givePicture(held);
            held = null;
        }
        fresh = null;
    }

    /**
     * A [width] × [height] image to paint a whole picture into, holding anything: the one this render took already if it
     * took one (what it held is not shown, so it may be painted over), else one from the pool.
     */
    public BufferedImage take(int width, int height) {
        if (fresh == null || fresh.getWidth() != width || fresh.getHeight() != height) {
            if (fresh != null) PixelPool.givePicture(fresh);
            fresh = PixelPool.picture(width, height);
        }
        return fresh;
    }

    /** [image] is shared with others from now on (a kept first picture): never handed back. */
    public void share(BufferedImage image) {
        if (fresh == image) fresh = null;
        if (held == image) held = null;
    }

    /** The render ended with [f] shown. What was shown before goes back unless [f] is a repaint of it. */
    public void shown(HtmlRenderer.Frame f) {
        BufferedImage now = f.image();
        if (held != null && held != now) PixelPool.givePicture(held);
        if (held != now) held = fresh == now ? fresh : null;
        if (fresh != null && fresh != now) PixelPool.givePicture(fresh);
        fresh = null;
    }

    /** The render failed, and [shown] is still on the screen: what it took and did not show goes back (nothing paints into it now). */
    public void failed(HtmlRenderer.Frame shown) {
        if (fresh != null && (shown == null || fresh != shown.image())) PixelPool.givePicture(fresh);
        fresh = null;
    }
}
