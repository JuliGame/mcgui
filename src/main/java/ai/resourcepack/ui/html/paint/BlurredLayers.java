package ai.resourcepack.ui.html.paint;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Blurred layers that come out the same in every picture of a page: a box with nothing in it but its own background
 * (no children, text, borders or shadows), under a wide {@code filter: blur()} ({@link Filters#gridBlur}), as the
 * vanilla pages' blurred panorama is. {@link Painter} works one out once, whole, on the page's grid, and every picture,
 * band and repaint after draws its part of it from here instead of painting the box and blurring it again: the same
 * pixels where they are kept (see {@link Painter#kept}). Kept per page (weakly: a reloaded page is a new one) and per
 * everything the layer's pixels depend on ({@link Painter} makes the key), within {@link #MAX_BYTES} for all pages.
 */
public final class BlurredLayers {
    private BlurredLayers() {}

    /**
     * A layer's blurred picture, [k] times smaller than the page: its top-left pixel is at ([x], [y]) on the page (device
     * px, a multiple of [k] each). [area] is the part of the page the layer was painted over (device px of the page).
     */
    record Layer(BufferedImage small, int x, int y, int k, java.awt.Rectangle area) {
        long bytes() {
            return 4L * small.getWidth() * small.getHeight();
        }
    }

    /** Bytes of blurred layers kept for every page together; past it, all are let go and made again as needed. */
    static final long MAX_BYTES = 32L << 20;

    private static final Map<Object, Map<String, Layer>> PAGES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong BYTES = new AtomicLong();

    /** The layer of [page] for [key], made by [make] (on this thread, once for all asking at once) if it is not kept. */
    static Layer get(Object page, String key, Supplier<Layer> make) {
        Map<String, Layer> layers = PAGES.computeIfAbsent(page, p -> new ConcurrentHashMap<>());
        Layer kept = layers.get(key);
        if (kept != null) return kept;
        Layer made = layers.computeIfAbsent(key, k -> {
            Layer l = make.get();
            BYTES.addAndGet(l.bytes());
            return l;
        });
        if (BYTES.get() > MAX_BYTES) clear();
        return made;
    }

    /** Lets every kept layer go (tests; and when over {@link #MAX_BYTES}). */
    public static void clear() {
        synchronized (PAGES) {
            for (Map<String, Layer> m : PAGES.values()) m.clear();
            PAGES.clear();
        }
        BYTES.set(0);
    }

    /** Layers kept for [page] (tests). */
    public static int size(Object page) {
        Map<String, Layer> m = PAGES.get(page);
        return m == null ? 0 : m.size();
    }
}
