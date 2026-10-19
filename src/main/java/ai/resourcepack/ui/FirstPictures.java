package ai.resourcepack.ui;

import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.html.paint.Painter;
import ai.resourcepack.ui.html.style.Style;
import ai.resourcepack.uiframework.api.Canvas;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * The first picture of each page at each size, kept so that the next session to show that page there starts
 * from it instead of drawing it. Its canvas is handed the picture's tiles (the same arrays: nothing is drawn or
 * encoded), so the first thing its viewer is sent is the page, one packet a map, and it carries on from the
 * picture's hit regions and styles, put on its own copy of the document, exactly as if it had drawn it itself.
 *
 * <p>Keyed by all a first picture depends on ({@link Key}): the page, one revision of it (a reload makes a new
 * {@link Page}), the canvas's size in maps, the scale, and every placeholder value filled into the document.
 * Only a session's first picture of a document is kept or taken: at the top of the page, nothing hovered or
 * pressed, nothing clicked, no #fragment. A document filled with a player's own values is never kept
 * ({@link Placeholders.Filled#personal}). A picture that failed or was given up is never kept.
 *
 * <p>One session draws a missing picture; others wanting it meanwhile wait for it ({@link #claim}). Kept by
 * least recent use, within a count and a number of bytes. Safe from any thread.
 */
final class FirstPictures {
    /** Everything a first picture depends on. [page] is one revision; [name] is only for {@link #invalidate}. */
    record Key(String name, Page page, int cols, int rows, double scale, String fill) {}

    /** What {@link #claim} found: the picture, or none, and then whether the caller is to draw it (else it waits). */
    record Claim(Picture picture, boolean mine) {}

    private final int maxEntries;
    private final long maxBytes;
    private final LinkedHashMap<Key, Picture> kept = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes;
    /** Pictures being drawn, with who waits for each. */
    private final Map<Key, List<Consumer<Picture>>> drawing = new HashMap<>();
    /** Page revisions replaced since they were asked for: what is drawn of them is not kept. */
    private final Set<Page> retired = Collections.newSetFromMap(new WeakHashMap<>());
    private long hits, misses, waits;

    /** At most [maxEntries] pictures and [maxBytes] bytes of them (0 for none). */
    FirstPictures(int maxEntries, long maxBytes) {
        this.maxEntries = Math.max(0, maxEntries);
        this.maxBytes = Math.max(0, maxBytes);
    }

    boolean enabled() {
        return maxEntries > 0 && maxBytes > 0;
    }

    /**
     * The picture for [key] if it is kept. Else, if nobody is drawing it, the caller is to: it then must
     * {@link #publish} or {@link #abandon} it. Else [waiter] is told, from the thread that settles it, the picture
     * once it is drawn, or null if it was not.
     */
    synchronized Claim claim(Key key, Consumer<Picture> waiter) {
        Picture p = kept.get(key);
        if (p != null) {
            hits++;
            return new Claim(p, false);
        }
        List<Consumer<Picture>> waiting = drawing.get(key);
        if (waiting != null) {
            waits++;
            waiting.add(waiter);
            return new Claim(null, false);
        }
        misses++;
        drawing.put(key, new ArrayList<>());
        return new Claim(null, true);
    }

    /** The picture for [key] if it is kept, without claiming it. */
    synchronized Picture get(Key key) {
        return kept.get(key);
    }

    /**
     * [picture] (null if it could not be kept: see {@link Picture#of}) is the one [key]'s claimant drew: kept, and
     * handed to whoever waits for it.
     */
    void publish(Key key, Picture picture) {
        List<Consumer<Picture>> waiting;
        synchronized (this) {
            waiting = drawing.remove(key);
            if (picture != null && !retired.contains(key.page()) && picture.bytes <= maxBytes && enabled()) {
                Picture old = kept.put(key, picture);
                if (old != null) bytes -= old.bytes;
                bytes += picture.bytes;
                trim();
            }
        }
        if (waiting != null) for (Consumer<Picture> w : waiting) w.accept(picture);
    }

    /** [key]'s claimant could not draw it (it failed, was given up, or moved on): whoever waits draws for themselves. */
    void abandon(Key key) {
        publish(key, null);
    }

    /** Page [name] was reloaded: let go of every picture of a revision but [current] (null: of every revision). */
    synchronized void invalidate(String name, Page current) {
        for (Iterator<Map.Entry<Key, Picture>> it = kept.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Key, Picture> e = it.next();
            Key k = e.getKey();
            if (!k.name().equals(name) || k.page() == current) continue;
            retired.add(k.page());
            bytes -= e.getValue().bytes;
            it.remove();
        }
        for (Key k : drawing.keySet()) if (k.name().equals(name) && k.page() != current) retired.add(k.page());
    }

    /** [f] with a copy of its image of its own, to paint into: [f]'s is shared with a picture kept here. */
    static HtmlRenderer.Frame own(HtmlRenderer.Frame f) {
        BufferedImage from = f.image();
        return own(f, new BufferedImage(from.getWidth(), from.getHeight(), from.getType()));
    }

    /** As {@link #own(HtmlRenderer.Frame)}, copying into [to] (of [f]'s image's size and type, read by nothing else), a pooled picture say. */
    static HtmlRenderer.Frame own(HtmlRenderer.Frame f, BufferedImage to) {
        BufferedImage from = f.image();
        if (to.getWidth() != from.getWidth() || to.getHeight() != from.getHeight() || to.getType() != from.getType()) {
            throw new IllegalArgumentException("Not the same size and type as the picture");
        }
        int[] src = ((DataBufferInt) from.getRaster().getDataBuffer()).getData();
        System.arraycopy(src, 0, ((DataBufferInt) to.getRaster().getDataBuffer()).getData(), 0, from.getWidth() * from.getHeight());
        return new HtmlRenderer.Frame(to, f.scale(), f.viewportWidth(), f.viewportHeight(), f.pageHeight(), f.scrollY(), f.hits(), f.styles(),
                f.document(), f.page());
    }

    synchronized String stats() {
        return String.format("%d pictures, %.1f MiB; %d taken, %d waited for, %d drawn", kept.size(), bytes / 1048576.0, hits, waits, misses);
    }

    synchronized int size() {
        return kept.size();
    }

    synchronized long bytes() {
        return bytes;
    }

    private void trim() {
        Iterator<Picture> it = kept.values().iterator();
        while ((kept.size() > maxEntries || bytes > maxBytes) && it.hasNext()) {
            bytes -= it.next().bytes;
            it.remove();
        }
    }

    /**
     * One first picture: the frame drawn, its canvas, and where in document order each element its hit regions
     * and styles name is, so they can be put on another copy of the document. Never changed once made; its image
     * is shared by every frame made from it, so whoever paints into one copies it first.
     */
    static final class Picture {
        final Key key;
        private final HtmlRenderer.Frame frame;
        /** Each hit region's element, as its place in document order. */
        private final int[] hitAt;
        /** Each element's style, by place in document order; null where it has none. */
        private final Style[] styles;
        private final int styled;
        /** The canvas with the picture on it, which nothing draws into any more. */
        final Canvas canvas;
        final long bytes;

        private Picture(Key key, HtmlRenderer.Frame frame, int[] hitAt, Style[] styles, int styled, Canvas canvas) {
            this.key = key;
            this.frame = frame;
            this.hitAt = hitAt;
            this.styles = styles;
            this.styled = styled;
            this.canvas = canvas;
            BufferedImage img = frame.image();
            // Its pixels, the canvas's colours (3 bytes a pixel) and tiles (1), and a little for each region and style.
            long pixels = (long) img.getWidth() * img.getHeight();
            this.bytes = 4 * pixels + 4L * canvas.width() * canvas.height() + 96L * hitAt.length + 8L * styles.length;
        }

        /**
         * [frame], a whole first picture of [key]'s document, drawn onto [canvas], which nothing draws into after
         * (a {@link Canvas#copy()} taken at once). On the thread that drew it. Null if a hit region or style names
         * an element that is not in the document, which another copy could not be given.
         */
        static Picture of(Key key, HtmlRenderer.Frame frame, Canvas canvas) {
            Elements all = frame.document().getAllElements();
            Map<Element, Integer> place = new IdentityHashMap<>(all.size() * 2);
            for (int i = 0; i < all.size(); i++) place.put(all.get(i), i);
            List<Painter.Hit> hits = frame.hits();
            int[] hitAt = new int[hits.size()];
            for (int i = 0; i < hitAt.length; i++) {
                Painter.Hit h = hits.get(i);
                Integer at = place.get(h.element());
                if (at == null) return null;
                hitAt[i] = at;
                // Every session sharing these reads them: an Area works its bounds out the first time it is asked,
                // so that is done here, before they are shared, and they are only read from then on.
                h.shape().getBounds2D();
                if (h.clip() != null) h.clip().getBounds2D();
            }
            Style[] styles = new Style[all.size()];
            for (Map.Entry<Element, Style> e : frame.styles().entrySet()) {
                Integer at = place.get(e.getKey());
                if (at == null) return null;
                styles[at] = e.getValue();
            }
            return new Picture(key, frame, hitAt, styles, frame.styles().size(), canvas);
        }

        /** The picture (shared: copy it before painting into it). */
        BufferedImage image() {
            return frame.image();
        }

        /**
         * The picture as a frame of [doc], a copy of the same page filled with the same values: its regions and
         * styles on [doc]'s elements. Null if [doc] is not shaped like the document it was drawn of.
         */
        HtmlRenderer.Frame frameFor(Document doc) {
            Elements all = doc.getAllElements();
            if (all.size() != styles.length) return null;
            List<Painter.Hit> from = frame.hits();
            List<Painter.Hit> hits = new ArrayList<>(from.size());
            for (int i = 0; i < hitAt.length; i++) {
                Painter.Hit h = from.get(i);
                hits.add(new Painter.Hit(h.shape(), h.clip(), all.get(hitAt[i]), h.pointer()));
            }
            Map<Element, Style> styleOf = new IdentityHashMap<>(styled * 2);
            for (int i = 0; i < styles.length; i++) if (styles[i] != null) styleOf.put(all.get(i), styles[i]);
            HtmlRenderer.Frame f = frame;
            return new HtmlRenderer.Frame(f.image(), f.scale(), f.viewportWidth(), f.viewportHeight(), f.pageHeight(), f.scrollY(),
                    hits, styleOf, doc, f.page());
        }
    }
}
