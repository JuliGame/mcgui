package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.paint.Painter;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One session's hover repaints drawn before they are asked for. While the pointer rests (or heads somewhere) and
 * the render threads have nothing else to do, the repaints for the few hover targets nearest the pointer are worked
 * out over the picture on the screen ({@link Repaint#prepare}) and kept; when the pointer then lands on one of them,
 * the session takes it ({@link #take}) instead of painting, and has exactly what {@link Repaint#partial} would have
 * drawn. A prepared repaint is of one picture (by identity), one drawn hover and press state, and one wanted one:
 * any other picture finds nothing. All of them go when the picture changes.
 *
 * <p>Drawing ahead never holds up real work: a session draws ahead only in place of a render of its own (one at a
 * time per session, as ever), only when the render threads are idle, and every drawing ahead stops at its next
 * budget check as soon as any session asks for a real picture ({@link #makeRoom}), unless it is drawing the very
 * state its own session has just asked for.
 */
public final class HoverAhead {
    /** Speeds in CSS pixels a second: below SLOW the pointer counts as resting; above FAST only what lies ahead is drawn. */
    static final double SLOW = 150, FAST = 400;

    /** The drawings ahead under way, of every session: stopped when real work comes. */
    private static final Set<Job> RUNNING = ConcurrentHashMap.newKeySet();
    /**
     * Drawings ahead at once, of every session, at most: one. However many sessions rest their pointers, drawing ahead
     * takes at most one render thread, one core, and one repaint's garbage at a time (LoadBench, 16 sessions: two at
     * once doubled the garbage and the GC time for the same hits).
     */
    static final int AT_ONCE = 1;
    private static final java.util.concurrent.atomic.AtomicInteger STARTED = new java.util.concurrent.atomic.AtomicInteger();

    /** Sessions that wanted a turn while another had it: one is offered the next turn (each runs its own check again). */
    private static final Set<Runnable> WAITING = ConcurrentHashMap.newKeySet();

    /**
     * A turn to draw ahead, if no other session has it; {@link #release} it when done (or if it never ran). Without one,
     * [retry] (the same object each time for one session) is run when a turn comes free.
     */
    public static boolean claim(Runnable retry) {
        for (int n; (n = STARTED.get()) < AT_ONCE; ) if (STARTED.compareAndSet(n, n + 1)) return true;
        WAITING.add(retry);
        return false;
    }

    public static void release() {
        STARTED.decrementAndGet();
        for (Runnable r : WAITING) {
            if (WAITING.remove(r)) {
                r.run();
                return;
            }
        }
    }

    /** Hover targets drawn ahead at most; 0 for none. */
    public final int most;
    /** Bytes of prepared pixels kept at most. */
    private final long maxBytes;

    // Guarded by this. Everything here is of the picture [of].
    private HtmlRenderer.Frame of;
    /** A copy of [of] to draw over, when its picture is shared with other sessions and so may not be painted, even for a while. */
    private HtmlRenderer.Frame copy;
    private final List<Entry> entries = new ArrayList<>();
    private long bytes;
    /** Where the last drawing ahead that finished was done from: nothing more to draw from there. */
    private HtmlRenderer.Frame doneOf;
    private double doneX, doneY;

    // Render thread only (a session draws one thing at a time): the hover targets of the picture [targetsOf].
    private HtmlRenderer.Frame targetsOf;
    private List<Target> targets = List.of();

    /**
     * Counters, for benchmarks and debug output: repaints drawn ahead, drawings stopped part way, repaints taken; CPU
     * (ns) and bytes allocated by all drawing ahead, and the CPU of the repaints that were then taken.
     */
    public final AtomicLong prepared = new AtomicLong(), abandoned = new AtomicLong(), taken = new AtomicLong(),
            cpuNs = new AtomicLong(), allocBytes = new AtomicLong(), usedNs = new AtomicLong();
    /** Hovers not drawn ahead: no drawing ahead over their picture began; it did, but did not pick them; it picked them but did not get to them. */
    public final AtomicLong missNoJob = new AtomicLong(), missNotPicked = new AtomicLong(), missUnfinished = new AtomicLong();
    /** The picture last drawn ahead over, and the keys picked for it (guarded by this). */
    private HtmlRenderer.Frame pickedOf;
    private List<List<Element>> picked = List.of();
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();

    /** A repaint worked out ahead: from the [drawnHover]/[drawnPress] state of the picture to [hoverKey]/[pressKey]. Null [prepared]: not partial. */
    private record Entry(List<Element> drawnHover, List<Element> drawnPress, List<Element> hoverKey, List<Element> pressKey, Repaint.Prepared prepared,
                         long cpuNs) {
        boolean is(List<Element> dh, List<Element> dp, List<Element> hk, List<Element> pk) {
            return drawnHover.equals(dh) && drawnPress.equals(dp) && hoverKey.equals(hk) && pressKey.equals(pk);
        }
    }

    /** Elements of one hover key: one of them (to hover) and where they are, in CSS pixels of the view. */
    private record Target(List<Element> key, Element element, List<Rectangle2D> boxes) {}

    /** [most] hover targets at a time, keeping at most [maxBytes] of their pixels. */
    public HoverAhead(int most, long maxBytes) {
        this.most = Math.max(0, most);
        this.maxBytes = maxBytes;
    }

    public boolean on() {
        return most > 0;
    }

    /** Whether nothing else is drawing a whole picture: a drawing ahead would take a core from its bands. */
    public static boolean quiet() {
        return HtmlRenderer.painting() == 0;
    }

    /** Real work is coming: every drawing ahead stops, but for one drawing what its own session has just asked for. */
    public static void makeRoom() {
        for (Job j : RUNNING) j.budget.cancel();
    }

    /** Whether a drawing ahead from (x, y) over [f] would draw nothing new: one finished there, and nothing has changed. */
    public synchronized boolean done(HtmlRenderer.Frame f, double x, double y) {
        return doneOf == f && Math.abs(x - doneX) < 8 && Math.abs(y - doneY) < 8;
    }

    /**
     * The repaint of [before] from its drawn state ([drawnHover], [drawnPress]) to [hoverKey] and [pressKey], if it was
     * drawn ahead, ready to {@link Repaint.Prepared#apply}; else null. Everything else kept goes: the picture is about
     * to change either way.
     */
    public synchronized Repaint.Prepared take(HtmlRenderer.Frame before, List<Element> drawnHover, List<Element> drawnPress,
                                              List<Element> hoverKey, List<Element> pressKey) {
        Entry found = null;
        if (before == of) for (Entry e : entries) {
            if (e.prepared != null && e.is(drawnHover, drawnPress, hoverKey, pressKey)) found = e;
        }
        drop();
        if (found == null) {
            (pickedOf != before ? missNoJob : picked.contains(hoverKey) ? missUnfinished : missNotPicked).incrementAndGet();
            return null;
        }
        taken.incrementAndGet();
        usedNs.addAndGet(found.cpuNs);
        return found.prepared;
    }

    /** Lets go of everything kept: the picture changed, or the session closed. */
    public synchronized void drop() {
        of = null;
        copy = null;
        entries.clear();
        bytes = 0;
        doneOf = null;
    }

    /** The hover keys drawn ahead and kept (tests). */
    synchronized List<List<Element>> keys() {
        List<List<Element>> out = new ArrayList<>();
        for (Entry e : entries) if (e.prepared != null) out.add(e.hoverKey);
        return out;
    }

    private synchronized boolean has(HtmlRenderer.Frame before, List<Element> dh, List<Element> dp, List<Element> hk, List<Element> pk) {
        if (before != of) return false;
        for (Entry e : entries) if (e.is(dh, dp, hk, pk)) return true;
        return false;
    }

    /** Keeps [e], of [before], if there is room. */
    private synchronized void keep(HtmlRenderer.Frame before, Entry e) {
        if (before != of) {
            drop();
            of = before;
        }
        long b = e.prepared == null ? 0 : e.prepared.bytes();
        if (bytes + b > maxBytes) return;
        bytes += b;
        entries.add(e);
    }

    /** What to draw over for [before]: its own picture, or, when that is shared ([shared]), one copy of it, made once per picture. */
    private synchronized HtmlRenderer.Frame base(HtmlRenderer.Frame before, boolean shared) {
        if (!shared) return before;
        if (of != before) {
            drop();
            of = before;
        }
        if (copy == null) {
            BufferedImage from = before.image();
            BufferedImage to = new BufferedImage(from.getWidth(), from.getHeight(), from.getType());
            int[] src = ((DataBufferInt) from.getRaster().getDataBuffer()).getData();
            System.arraycopy(src, 0, ((DataBufferInt) to.getRaster().getDataBuffer()).getData(), 0, src.length);
            copy = new HtmlRenderer.Frame(to, before.scale(), before.viewportWidth(), before.viewportHeight(), before.pageHeight(), before.scrollY(),
                    before.hits(), before.styles(), before.document(), before.page());
        }
        return copy;
    }

    /**
     * A drawing ahead over [before] (the picture on the screen, of [doc] at [width] x [height], [scale]), drawn with
     * [drawnHover] and [drawnPress]; [shared] if its picture is shared with other sessions. The pointer is at (x, y),
     * moving at (vx, vy), CSS pixels (a second); [pressed] is pressed, with [pressKey]; [hoverKey] is the hover now.
     */
    public Job job(Page page, Document doc, int width, int height, double scale, HtmlRenderer.Frame before, boolean shared,
                   List<Element> drawnHover, List<Element> drawnPress, List<Element> hoverKey, Element pressed, List<Element> pressKey,
                   double x, double y, double vx, double vy, long timeoutMs) {
        return new Job(page, doc, width, height, scale, before, shared, drawnHover, drawnPress, hoverKey, pressed, pressKey, x, y, vx, vy,
                RenderBudget.cancellable(timeoutMs));
    }

    public static final class Job {
        final Page page;
        final Document doc;
        final int width, height;
        final double scale, x, y, vx, vy;
        final HtmlRenderer.Frame before;
        final boolean shared;
        final List<Element> drawnHover, drawnPress, hoverKey, pressKey;
        final Element pressed;
        final RenderBudget budget;
        /** The hover key being drawn now, or null. */
        private volatile List<Element> drawing;
        /** Stop after the one being drawn: its session wants it. */
        private volatile boolean last;

        private Job(Page page, Document doc, int width, int height, double scale, HtmlRenderer.Frame before, boolean shared,
                    List<Element> drawnHover, List<Element> drawnPress, List<Element> hoverKey, Element pressed, List<Element> pressKey,
                    double x, double y, double vx, double vy, RenderBudget budget) {
            this.page = page;
            this.doc = doc;
            this.width = width;
            this.height = height;
            this.scale = scale;
            this.before = before;
            this.shared = shared;
            this.drawnHover = drawnHover;
            this.drawnPress = drawnPress;
            this.hoverKey = hoverKey;
            this.pressed = pressed;
            this.pressKey = pressKey;
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.budget = budget;
        }

        /** Whether it draws over the picture [p]'s document [d] would show at [scroll]. */
        public boolean of(Page p, Document d, double scroll) {
            return p == page && d == doc && Math.abs(Math.max(0, Math.min(scroll, before.maxScroll())) - before.scrollY()) < 0.01;
        }

        /**
         * Its session asks for a picture of [hoverKey] and [pressKey], of the same document and view ([same]): if that is
         * what is being drawn, it is finished (and then taken), else the drawing stops now.
         */
        public void wanted(boolean same, List<Element> hoverKey, List<Element> pressKey) {
            List<Element> d = drawing;
            if (same && d != null && d.equals(hoverKey) && this.pressKey.equals(pressKey)) last = true;
            else budget.cancel();
        }

        public void cancel() {
            budget.cancel();
        }
    }

    /** Draws ahead for [job], nearest target first, until done, stopped, or out of room. On a render thread. */
    public void run(Job job) {
        RUNNING.add(job);
        boolean finished = false;
        long cpu0 = THREADS.getCurrentThreadCpuTime(), alloc0 = THREADS.getCurrentThreadAllocatedBytes();
        try {
            List<Target> ranked = rank(job, targets(job.before));
            synchronized (this) {
                if (pickedOf != job.before) picked = new ArrayList<>();
                pickedOf = job.before;
                for (Target t : ranked) if (!picked.contains(t.key)) picked.add(t.key);
            }
            HtmlRenderer.Frame base = null;
            for (Target t : ranked) {
                if (job.budget.cancelled() || job.last || !quiet()) return;
                if (has(job.before, job.drawnHover, job.drawnPress, t.key, job.pressKey)) continue;
                Set<Element> moved = changed(job.drawnHover, job.drawnPress, t.key, job.pressKey);
                if (moved.isEmpty()) continue;
                if (base == null) base = base(job.before, job.shared);
                job.drawing = t.key;
                long c0 = THREADS.getCurrentThreadCpuTime();
                Repaint.Prepared p;
                try {
                    p = Repaint.prepare(job.page, job.doc, job.width, job.height, job.scale, job.before.scrollY(),
                            HtmlRenderer.state(t.element, job.pressed), base, moved, job.budget);
                } catch (RenderAbandoned e) {
                    abandoned.incrementAndGet();
                    return;
                } finally {
                    job.drawing = null;
                }
                prepared.incrementAndGet();
                keep(job.before, new Entry(job.drawnHover, job.drawnPress, t.key, job.pressKey, p, THREADS.getCurrentThreadCpuTime() - c0));
            }
            finished = true;
        } finally {
            RUNNING.remove(job);
            cpuNs.addAndGet(THREADS.getCurrentThreadCpuTime() - cpu0);
            allocBytes.addAndGet(THREADS.getCurrentThreadAllocatedBytes() - alloc0);
            if (finished) {
                synchronized (this) {
                    if (of == job.before || of == null) {
                        doneOf = job.before;
                        doneX = job.x;
                        doneY = job.y;
                    }
                }
            }
        }
    }

    /** The elements whose state differs between the drawn state and the wanted one (as Session works it out). */
    static Set<Element> changed(List<Element> drawnHover, List<Element> drawnPress, List<Element> hoverKey, List<Element> pressKey) {
        Set<Element> changed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Element e : drawnHover) if (!hoverKey.contains(e)) changed.add(e);
        for (Element e : hoverKey) if (!drawnHover.contains(e)) changed.add(e);
        for (Element e : drawnPress) if (!pressKey.contains(e)) changed.add(e);
        for (Element e : pressKey) if (!drawnPress.contains(e)) changed.add(e);
        return changed;
    }

    /** [f]'s hover targets: every hover key its pointer regions have, with where they are in the view. */
    private List<Target> targets(HtmlRenderer.Frame f) {
        if (targetsOf == f) return targets;
        Map<Element, List<Element>> keyOf = new IdentityHashMap<>();
        Map<List<Element>, Target> byKey = new LinkedHashMap<>();
        Rectangle2D view = new Rectangle2D.Double(0, 0, f.viewportWidth(), f.viewportHeight());
        for (Painter.Hit h : f.hits()) {
            if (!h.pointer()) continue;
            Rectangle2D b = h.shape().getBounds2D();
            Rectangle2D box = new Rectangle2D.Double(b.getX(), b.getY() - f.scrollY(), b.getWidth(), b.getHeight());
            if (h.clip() != null) {
                Rectangle2D c = h.clip().getBounds2D();
                Rectangle2D.intersect(box, new Rectangle2D.Double(c.getX(), c.getY() - f.scrollY(), c.getWidth(), c.getHeight()), box);
            }
            if (box.isEmpty() || !box.intersects(view)) continue;
            List<Element> key = keyOf.computeIfAbsent(h.element(), e -> f.page().styles.stateKey(e, false));
            // The innermost element of the key's own: hovering it is hovering the key.
            byKey.computeIfAbsent(key, k -> new Target(k, h.element(), new ArrayList<>())).boxes.add(box);
        }
        // Nothing hovered: where no element takes the pointer, or one without :hover rules does. Always there to leave to.
        byKey.putIfAbsent(List.of(), new Target(List.of(), null, List.of()));
        targetsOf = f;
        targets = List.copyOf(byKey.values());
        return targets;
    }

    /**
     * CSS pixels between two boxes across which the pointer can go from one straight into the other. The client reports
     * the pointer 20 times a second, so a moving pointer jumps a gap as wide as it goes in a report or so; one at rest
     * jumps a gutter on its first move often enough (LoadBench, hand mode) that half a map row is allowed for.
     */
    static final double GAP = 32;
    /** Seconds between two reports of the pointer, with some to spare. */
    static final double REPORT = 0.075;

    /**
     * The targets worth drawing for [job], best first, at most {@link #most}. The next state is whatever the pointer
     * is reported over next. On a target that is what lies under it (the page round a card), or a target close enough
     * to jump to between two reports ({@link #GAP}); a target further off is reached only through what lies under, which
     * is a new picture, and drawn ahead from that one. Off any target (or on one that has no box of its own) it meets the nearest. Those it is heading for count as
     * nearer and those behind it as further; when it moves fast, only those ahead (and what lies under) are drawn.
     */
    private List<Target> rank(Job job, List<Target> all) {
        double speed = Math.hypot(job.vx, job.vy);
        // The boxes of the hovered target the pointer is in, and how far it is from leaving them.
        List<Rectangle2D> on = new ArrayList<>();
        double exit = 0;
        for (Target t : all) {
            if (!t.key.equals(job.hoverKey)) continue;
            for (Rectangle2D b : t.boxes) {
                if (!b.contains(job.x, job.y)) continue;
                on.add(b);
                exit = Math.max(exit, Math.min(Math.min(job.x - b.getMinX(), b.getMaxX() - job.x), Math.min(job.y - b.getMinY(), b.getMaxY() - job.y)));
            }
        }
        List<double[]> scored = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Target t = all.get(i);
            if (t.key.equals(job.hoverKey)) continue;
            double best = Double.MAX_VALUE, cos = 0;
            // Nothing hovered is always under: leaving the target reaches it, wherever the page has no box of its own.
            boolean under = t.key.isEmpty(), touching = on.isEmpty();
            if (under) best = exit;
            for (Rectangle2D b : t.boxes) {
                double nx = Math.max(b.getMinX(), Math.min(job.x, b.getMaxX())), ny = Math.max(b.getMinY(), Math.min(job.y, b.getMaxY()));
                double dx = nx - job.x, dy = ny - job.y, d = Math.hypot(dx, dy);
                double c = d == 0 || speed == 0 ? 0 : (dx * job.vx + dy * job.vy) / (d * speed);
                if (d == 0) {
                    under = true;
                    d = exit;
                }
                if (!touching) for (Rectangle2D o : on) touching |= gap(b, o) <= Math.max(GAP, speed * REPORT);
                if (d < best) {
                    best = d;
                    cos = c;
                }
            }
            if (!under && !touching) continue;
            if (!under && speed > FAST && cos < 0.5) continue;
            double score = speed > SLOW ? best * (1 - 0.6 * cos) : best;
            scored.add(new double[]{score, i});
        }
        scored.sort((a, b) -> Double.compare(a[0], b[0]));
        List<Target> out = new ArrayList<>(Math.min(most, scored.size()));
        for (int i = 0; i < scored.size() && out.size() < most; i++) out.add(all.get((int) scored.get(i)[1]));
        return out;
    }

    /** The distance between two boxes; 0 if they meet. */
    private static double gap(Rectangle2D a, Rectangle2D b) {
        double dx = Math.max(0, Math.max(a.getMinX() - b.getMaxX(), b.getMinX() - a.getMaxX()));
        double dy = Math.max(0, Math.max(a.getMinY() - b.getMaxY(), b.getMinY() - a.getMaxY()));
        return Math.hypot(dx, dy);
    }
}
