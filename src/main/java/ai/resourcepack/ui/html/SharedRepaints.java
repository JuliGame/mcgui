package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.paint.Painter;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;

/**
 * Hover repaints, drawn once and taken by every session in the same state: one player's hover, or a drawing ahead for
 * any of them ({@link HoverAhead}), serves every other player who goes from the same picture to the same hover.
 *
 * <p>A repaint ({@link Repaint.Prepared}) is the pixels a hover changes and the frame it makes, and is right for any
 * picture equal to the one it was worked out over. Pictures are equal when everything they are drawn from is: the
 * document (by identity: copies of a page made for different players, or changed by a click, are different objects
 * and so never match, which is what keeps a page filled with {@code {player}} to its own player), the page revision,
 * the size, the scale, the scroll, and the hover and press state drawn ({@link Key}). Every repaint equals a fresh
 * picture of its state (RepaintTest), so two sessions in one state hold the same pixels, however each got there.
 * Pages whose pictures change with nothing in that list are not shared: one whose images arrive after it is drawn
 * ({@link Page#schemed}) is keyed by the picture itself as well, and a page that moves is never asked.
 *
 * <p>A repaint being drawn is claimed ({@link #claim}): whoever wants the same one meanwhile waits for it instead of
 * drawing it too. A repaint that turned out not partial is kept as that (null), so nobody tries again. Kept by least
 * recent use within a number of bytes. Safe from any thread.
 */
public final class SharedRepaints {
    /**
     * Everything a repaint depends on: from the picture of [doc] (a page revision, at [width] x [height] canvas pixels,
     * [scale], scrolled to [scroll]) drawn with [drawnHover] and [drawnPress], to [hoverKey] and [pressKey]. [picture]
     * is the picture's image where its pixels depend on more than that (a page whose images arrive late), else null.
     */
    public record Key(Document doc, Page page, int width, int height, double scale, double scroll, BufferedImage picture,
                      List<Element> drawnHover, List<Element> drawnPress, List<Element> hoverKey, List<Element> pressKey) {}

    /**
     * What {@link #claim} found. [kept]: the repaint is known, and is [prepared] (null: not partial, draw a whole
     * picture; or, if [own], partial but only right for the copy that drew it: draw your own). Else [mine]: the caller
     * is to draw it and then {@link #put}, {@link #putOwn} or {@link #abandon} it; or neither, and [pending] completes
     * with it (or null if it was not drawn after all, or is not one to take).
     */
    public record Claim(boolean kept, Repaint.Prepared prepared, boolean mine, CompletableFuture<Repaint.Prepared> pending, boolean own) {
        static final Claim MINE = new Claim(false, null, true, null, false);

        /** A repaint known (another session's, handed over as it landed): [prepared], null if it is not partial. */
        public static Claim kept(Repaint.Prepared prepared) {
            return new Claim(true, prepared, false, null, false);
        }

        /** Longest a session waits for another's drawing when it has no render-timeout of its own. */
        static final long WAIT_MS = 10_000;

        /**
         * The repaint, waiting for it if somebody else is drawing it, up to [timeoutMs] (0: {@link #WAIT_MS}); null if it
         * is not partial, was not drawn, or took longer (then draw it yourself). On a {@link ai.resourcepack.ui.framework.api.Cpu}
         * pool thread the pool runs another thread in this one's place meanwhile (CompletableFuture waits through
         * {@link java.util.concurrent.ForkJoinPool#managedBlock}).
         */
        public Repaint.Prepared await(long timeoutMs) {
            if (kept || pending == null) return prepared;
            try {
                return pending.get(timeoutMs > 0 ? timeoutMs : WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                return null;
            }
        }
    }

    /**
     * A repaint kept: null [prepared] if it is not partial, or if [own], it is but each copy draws its own (it paints a
     * player's own values, see {@link Personal}); [ahead] while it was drawn ahead and nobody has taken it yet.
     */
    record Entry(Repaint.Prepared prepared, long bytes, boolean ahead, long cpuNs, boolean own) {}

    private long maxBytes;
    private final LinkedHashMap<Object, Entry> kept = new LinkedHashMap<>(64, 0.75f, true);
    /** Repaints being drawn, with how many wait for each. */
    private final Map<Object, Pending> drawing = new HashMap<>();
    private long bytes;
    /** Entries whose key holds a picture ({@link Key#picture}): {@link #forget} has nothing to look for while there are none. */
    private int pictureKeyed;

    private static final class Pending {
        final CompletableFuture<Repaint.Prepared> done = new CompletableFuture<>();
        int waiting;
    }

    /**
     * Counters, for /mcgui stats and benchmarks: repaints taken from here, drawn by a session for itself (of those, found
     * not to be partial), waited for while another drew them; drawn ahead and kept, and of those, taken at least once;
     * CPU (ns) of the repaints taken, which is what sharing saved.
     */
    private long hits, drawn, wholes, waits, ahead, aheadUsed, savedNs;

    /** At most [maxBytes] of repaints (0: none kept, but a repaint being drawn is still waited for). */
    public SharedRepaints(long maxBytes) {
        this.maxBytes = Math.max(0, maxBytes);
    }

    /** Keeps at most [maxBytes] from now on, letting go of the least recently used beyond it. */
    public synchronized void limit(long maxBytes) {
        this.maxBytes = Math.max(0, maxBytes);
        trim();
    }

    /**
     * The repaint for [key] if it is kept. Else, if somebody is drawing it, a claim to wait on; else the caller's to draw.
     * [take]: the caller will apply what it finds (counted, and a repaint drawn ahead is marked used).
     */
    public synchronized Claim claim(Object key, boolean take) {
        Entry e = kept.get(key);
        if (e != null) {
            if (take && e.prepared != null) took(key, e);
            return new Claim(true, e.prepared, false, null, e.own);
        }
        Pending p = drawing.get(key);
        if (p != null) {
            if (take) {
                p.waiting++;
                waits++;
            }
            return new Claim(false, null, false, p.done, false);
        }
        drawing.put(key, new Pending());
        return Claim.MINE;
    }

    /** The repaint for [key] if it is kept (taken: see {@link #claim}); null if not kept, or not partial. */
    public synchronized Repaint.Prepared take(Object key) {
        Entry e = kept.get(key);
        if (e == null || e.prepared == null) return null;
        took(key, e);
        return e.prepared;
    }

    /** Whether [key] is kept or being drawn: nothing to draw ahead. */
    public synchronized boolean known(Object key) {
        return kept.containsKey(key) || drawing.containsKey(key);
    }

    /** Whether somebody waits for the repaint [key] being drawn: a drawing ahead of it is then finished, not stopped. */
    public synchronized boolean awaited(Object key) {
        Pending p = drawing.get(key);
        return p != null && p.waiting > 0;
    }

    /** The repaint for [key] if it is kept, not counting it as taken. */
    synchronized Entry peek(Object key) {
        return kept.get(key);
    }

    private void took(Object key, Entry e) {
        hits++;
        savedNs += e.cpuNs;
        if (e.ahead) {
            aheadUsed++;
            // Counted once: from now on it is a repaint like any other.
            Entry used = new Entry(e.prepared, e.bytes, false, e.cpuNs, false);
            kept.put(key, used);
        }
    }

    /**
     * [key]'s claimant drew it: [prepared] (null: not partial), at [cpuNs] of CPU, [ahead] if before anyone asked.
     * Kept, and handed to whoever waits. Its frame's hit regions are worked out here, before other threads read them.
     */
    public void put(Object key, Repaint.Prepared prepared, long cpuNs, boolean ahead) {
        if (prepared != null) settle(prepared.frame());
        Pending p;
        synchronized (this) {
            p = drawing.remove(key);
            if (ahead) this.ahead++;
            else drawn++;
            if (prepared == null && !ahead) wholes++;
            long b = prepared == null ? 256 : prepared.bytes();
            if (b <= maxBytes) {
                // Waited for: it is taken at once, as a repaint of its own.
                boolean used = p != null && p.waiting > 0;
                Entry old = kept.put(key, new Entry(prepared, b, ahead && !used, cpuNs, false));
                if (old != null) removed(key, old);
                added(key, b);
                if (ahead && used) aheadUsed++;
                trim();
            }
            // Those who waited took it as if it had been kept.
            if (p != null && prepared != null) {
                hits += p.waiting;
                savedNs += (long) p.waiting * cpuNs;
            }
        }
        if (p != null) p.done.complete(prepared);
    }

    /** [key]'s claimant did not draw it (stopped, failed, or went another way): whoever waits draws it themselves. */
    public void abandon(Object key) {
        Pending p;
        synchronized (this) {
            p = drawing.remove(key);
        }
        if (p != null) p.done.complete(null);
    }

    /**
     * [key]'s claimant drew it, and it is partial, but it paints the claimant's own values (see {@link Personal}): kept
     * as one each copy draws for itself, so nobody waits for another's. Whoever waits draws their own. [ahead]: drawn
     * ahead (counted where the drawer keeps it for itself).
     */
    public void putOwn(Object key, boolean ahead) {
        Pending p;
        synchronized (this) {
            p = drawing.remove(key);
            if (!ahead) drawn++;
            if (256 <= maxBytes) {
                Entry old = kept.put(key, new Entry(null, 256, false, 0, true));
                if (old != null) removed(key, old);
                added(key, 256);
                trim();
            }
        }
        if (p != null) p.done.complete(null);
    }

    /** Lets go of every repaint only the picture [img] could take ({@link Key#picture}): it is painted over from now on. */
    public synchronized void forget(BufferedImage img) {
        if (pictureKeyed == 0) return;
        for (Iterator<Map.Entry<Object, Entry>> it = kept.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Object, Entry> e = it.next();
            if (!(e.getKey() instanceof Key k) || k.picture() != img) continue;
            removed(e.getKey(), e.getValue());
            it.remove();
        }
    }

    /** Lets go of every repaint of a page revision [gone] says is no more (a reload). */
    public synchronized void invalidate(Predicate<Page> gone) {
        for (Iterator<Map.Entry<Object, Entry>> it = kept.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Object, Entry> e = it.next();
            if (!gone.test(pageOf(e.getKey()))) continue;
            removed(e.getKey(), e.getValue());
            it.remove();
        }
    }

    private void trim() {
        for (Iterator<Map.Entry<Object, Entry>> it = kept.entrySet().iterator(); bytes > maxBytes && it.hasNext(); ) {
            Map.Entry<Object, Entry> e = it.next();
            removed(e.getKey(), e.getValue());
            it.remove();
        }
    }

    private void added(Object key, long b) {
        bytes += b;
        if (key instanceof Key k && k.picture() != null) pictureKeyed++;
    }

    private void removed(Object key, Entry e) {
        bytes -= e.bytes;
        if (key instanceof Key k && k.picture() != null) pictureKeyed--;
    }

    private static Page pageOf(Object key) {
        return key instanceof Key k ? k.page() : ((Personal.Key) key).page();
    }

    /** An Area works out its bounds the first time it is asked: done before the regions are shared, so they are only read after. */
    private static void settle(HtmlRenderer.Frame f) {
        for (Painter.Hit h : f.hits()) {
            h.shape().getBounds2D();
            if (h.clip() != null) h.clip().getBounds2D();
        }
    }

    public synchronized int size() {
        return kept.size();
    }

    public synchronized long bytes() {
        return bytes;
    }

    /** Repaints taken from here (by sessions that did not draw them). */
    public synchronized long hits() {
        return hits;
    }

    /** Repaints sessions drew for themselves (not ahead), and of those, how many turned out not partial (whole pictures). */
    public synchronized long drawn() {
        return drawn;
    }

    public synchronized long wholes() {
        return wholes;
    }

    /** Repaints drawn ahead, and of those, how many were ever taken. */
    public synchronized long ahead() {
        return ahead;
    }

    public synchronized long aheadUsed() {
        return aheadUsed;
    }

    public synchronized String stats() {
        long asked = hits + drawn;
        return String.format(java.util.Locale.ROOT,
                "%d hover repaints kept (%.1f MiB); %d of %d hovers (%.0f%%) taken from them, %d of those after waiting, %.1f s of drawing saved; "
                        + "%d drawn whole; %d drawn ahead, %d of them used (%.0f%%)",
                kept.size(), bytes / 1048576.0, hits, asked, asked == 0 ? 0.0 : 100.0 * hits / asked, waits, savedNs / 1e9,
                wholes, ahead, aheadUsed, ahead == 0 ? 0.0 : 100.0 * aheadUsed / ahead);
    }

    /** The same as {@link #stats}, as one JSON object (for /mcgui stats json and the bench). */
    public synchronized String json() {
        return String.format(java.util.Locale.ROOT,
                "{\"kept\":%d,\"kept_mb\":%.1f,\"taken\":%d,\"drawn\":%d,\"waited\":%d,\"saved_s\":%.2f,\"whole\":%d,\"ahead\":%d,\"ahead_used\":%d}",
                kept.size(), bytes / 1048576.0, hits, drawn, waits, savedNs / 1e9, wholes, ahead, aheadUsed);
    }
}
