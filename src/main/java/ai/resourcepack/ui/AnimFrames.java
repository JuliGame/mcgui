package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The pictures of pages that move, shared by every viewer in the same state. While only animations that repeat for ever
 * move on a page ({@code Motion#personal} is false), a picture is a picture of the page's clock, the same for every
 * viewer ({@link Session#moment}): every session showing the same document (one {@link FirstPictures} shared out), at
 * the same size, scale and scroll, with the same hover and press, wants the very same picture at each tick. The first
 * to ask draws it ({@link #claim}) and publishes it; the others take it: their canvases show its tiles (the same arrays,
 * compressed once for all their viewers) and they carry on from its frame, as from a first picture.
 *
 * <p>Nothing is copied unless someone takes a picture. A picture taken is shared from then on, so whoever drew it copies
 * it before painting the next over it; one nobody took is still its drawer's alone, painted over in place. Which it is,
 * is settled when its drawer starts its next picture ({@link #retire}): from then on it is never taken. Safe from any
 * thread.
 */
final class AnimFrames {
    /** Everything a picture of a page that moves depends on but the tick. The document and page by identity. */
    record Key(Document doc, Page page, int width, int height, double scale, double scroll, List<Element> hover, List<Element> press) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.doc == doc && k.page == page && k.width == width && k.height == height && k.scale == scale
                    && k.scroll == scroll && k.hover.equals(hover) && k.press.equals(press);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(doc) * 31 + Double.hashCode(scroll);
        }
    }

    /** One picture of [key] at [tick]: being drawn, published (with a frame, or none if it could not be), and then retired. */
    static final class Shared {
        final Key key;
        final int tick;
        // Guarded by the AnimFrames.
        private HtmlRenderer.Frame frame;
        private Canvas canvas;
        private Set<Element> moved = Set.of();
        private boolean published, taken, retired;
        private List<Consumer<Shared>> waiting = new ArrayList<>();

        private Shared(Key key, int tick) {
            this.key = key;
            this.tick = tick;
        }
    }

    /** What {@link #claim} found: a picture taken, or the caller is to draw one ([mine], or null: its own, shared with nobody), or it waits. */
    record Claim(Shared taken, Shared mine, boolean waits) {}

    private static final Claim WAIT = new Claim(null, null, true), OWN = new Claim(null, null, false);

    private final Map<Key, Shared> latest = new HashMap<>();
    /** The tick of the last {@link #sweep}. */
    private int swept;

    /**
     * The picture of [key] at [tick]. If one is published and not retired, [into] is made to show it (see {@link #frame})
     * and it is returned as taken. If one is being drawn, [waiter] is told when it is published (its frame then null if it
     * could not be), from the thread that publishes it, and the caller waits: then it {@link #take}s it. Else the caller
     * draws: it then must {@link #publish} or {@link #abandon} the one returned as [mine] (if any).
     */
    synchronized Claim claim(Key key, int tick, Canvas into, Consumer<Shared> waiter) {
        Shared s = latest.get(key);
        if (s != null && s.tick == tick) {
            if (!s.published) {
                s.waiting.add(waiter);
                return WAIT;
            }
            if (s.frame != null && !s.retired) {
                show(s, into);
                return new Claim(s, null, false);
            }
            // Drawn and painted over already, or it could not be drawn: the caller draws its own.
            return OWN;
        }
        if (s != null && s.tick > tick) return OWN;
        Shared mine = new Shared(key, tick);
        latest.put(key, mine);
        if (tick - swept >= 100 || tick < swept) sweep(tick);
        return new Claim(null, mine, false);
    }

    /** Takes [s], published since a {@link #claim} waited for it: whether [into] now shows it (not if it was retired, or not drawn). */
    synchronized boolean take(Shared s, Canvas into) {
        if (s.frame == null || s.retired) return false;
        show(s, into);
        return true;
    }

    private void show(Shared s, Canvas into) {
        s.taken = true;
        // Under the lock: its drawer does not draw into its canvas again before it retires it (see retire).
        into.show(s.canvas);
    }

    /**
     * [s] is drawn: [frame], on [canvas] (the drawer's own, which it draws nothing more into before it {@link #retire}s
     * [s]), [moved] in it. Whoever waits for it is told.
     */
    void publish(Shared s, HtmlRenderer.Frame frame, Canvas canvas, Set<Element> moved) {
        List<Consumer<Shared>> waiting;
        synchronized (this) {
            s.frame = frame;
            s.canvas = canvas;
            s.moved = moved;
            s.published = true;
            waiting = s.waiting;
            s.waiting = null;
        }
        for (Consumer<Shared> w : waiting) w.accept(s);
    }

    /** [s] could not be drawn (or is not the picture claimed after all): whoever waits draws their own. */
    void abandon(Shared s) {
        List<Consumer<Shared>> waiting;
        synchronized (this) {
            if (s.published) return;
            s.published = true;
            waiting = s.waiting;
            s.waiting = null;
        }
        for (Consumer<Shared> w : waiting) w.accept(s);
    }

    /**
     * [s]'s drawer is about to draw again (into its canvas, perhaps over its picture): nobody takes [s] from now on, and
     * it lets go of the picture. Its image if anyone took it (shared from now on: copied before it is painted over), else null.
     */
    synchronized java.awt.image.BufferedImage retire(Shared s) {
        s.retired = true;
        java.awt.image.BufferedImage shared = s.taken && s.frame != null ? s.frame.image() : null;
        s.frame = null;
        s.canvas = null;
        return shared;
    }

    /** [s]'s frame, once published (null if it could not be drawn). */
    synchronized HtmlRenderer.Frame frame(Shared s) {
        return s.frame;
    }

    /** What moved in [s]'s picture: what the next picture repaints. */
    synchronized Set<Element> moved(Shared s) {
        return s.moved;
    }

    /** Lets go of pictures of states nobody has drawn for five seconds (closed pages, other scrolls). */
    private void sweep(int tick) {
        swept = tick;
        for (Iterator<Shared> it = latest.values().iterator(); it.hasNext(); ) {
            Shared s = it.next();
            if (s.published && tick - s.tick > 100) it.remove();
        }
    }
}
