package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.paint.Painter;
import ai.resourcepack.ui.html.paint.PixelPool;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One session's hover repaints drawn before they are asked for. While the pointer rests (or heads somewhere) and
 * the render threads have nothing else to do, the repaints for the few hover targets nearest the pointer are worked
 * out ({@link Repaint#prepare}) over a copy of the picture on the screen, made for the drawing ahead in a
 * {@link PixelPool} picture of the render thread's and handed back when it ends, and kept in the {@link SharedRepaints}
 * every session takes from: so a hover one session drew ahead (or drew when it came) is drawn for every session in the
 * same state, and nothing is drawn ahead that is kept there already or being drawn. When the pointer then lands on
 * one of them, the session takes it instead of painting, and applies it to what it would have painted into
 * ({@link Repaint.Prepared#apply}), which then holds exactly what {@link Repaint#partial} would have drawn. A drawing
 * ahead never paints into a picture a frame shows, a session holds, or FirstPictures shares, not even for a moment.
 *
 * <p>Drawing ahead never holds up real work: a session draws ahead only in place of a render of its own (one at a
 * time per session, as ever), only when the render threads are idle, one session at a time ({@link #AT_ONCE}), within a
 * CPU budget for all of them ({@link #budget}), and every drawing ahead stops at its next budget check as soon as any
 * session asks for a real picture ({@link #makeRoom}), unless it is drawing the very state its own session has just
 * asked for, or one another session waits for.
 */
public final class HoverAhead {
    /** The drawings ahead under way, of every session: stopped when real work comes. */
    private static final Set<Job> RUNNING = ConcurrentHashMap.newKeySet();
    /**
     * Drawings ahead at once, of every session, at most: one. However many sessions rest their pointers, drawing ahead
     * takes at most one render thread, one core, and one repaint's garbage at a time (LoadBench, 16 sessions: two at
     * once doubled the garbage and the GC time for the same hits).
     */
    static final int AT_ONCE = 1;
    private static final AtomicInteger STARTED = new AtomicInteger();

    /** Sessions that wanted a turn while another had it, or the budget was spent: one is offered the next turn. */
    private static final Set<Runnable> WAITING = ConcurrentHashMap.newKeySet();

    /**
     * The CPU all drawing ahead may use, every session's together: [share] of one core on average, in bursts of up to
     * {@link #BURST_NS} (four targets of the heaviest shipped page). Drawing ahead is a guess, and the cores it would
     * burn are the ones chunk loading and other plugins use while the server thread waits for nothing.
     */
    private static double share = 1;
    static final long BURST_NS = 500_000_000L;
    private static double credit = BURST_NS;
    private static long creditAt = System.nanoTime();

    /**
     * Sets the CPU budget for drawing ahead: [cores] of one core on average (0: none; at most 1, as {@link #AT_ONCE} is),
     * starting with a full burst.
     */
    public static synchronized void budget(double cores) {
        share = Math.max(0, Math.min(1, cores));
        credit = BURST_NS;
        creditAt = System.nanoTime();
    }

    /** Whether the budget has CPU left to draw ahead with. */
    static synchronized boolean affordable() {
        long now = System.nanoTime();
        credit = Math.min(BURST_NS, credit + (now - creditAt) * share);
        creditAt = now;
        return share > 0 && credit > 0;
    }

    private static synchronized void spend(long ns) {
        credit -= ns;
        spent += ns;
    }

    /** CPU (ns) every drawing ahead has used since the plugin started. */
    private static long spent;

    public static synchronized long spentNs() {
        return spent;
    }

    /**
     * A turn to draw ahead, if no other session has it and the budget allows; {@link #release} it when done (or if it
     * never ran). Without one, [retry] (the same object each time for one session) is run when a turn comes free.
     */
    public static boolean claim(Runnable retry) {
        if (!affordable()) {
            WAITING.add(retry);
            return false;
        }
        for (int n; (n = STARTED.get()) < AT_ONCE; ) if (STARTED.compareAndSet(n, n + 1)) return true;
        WAITING.add(retry);
        return false;
    }

    public static void release() {
        STARTED.decrementAndGet();
        offer();
    }

    /** Every tick: a session waiting for the budget to fill again is offered a turn once it has. */
    public static void wake() {
        if (!WAITING.isEmpty() && STARTED.get() < AT_ONCE && affordable()) offer();
    }

    private static void offer() {
        for (Runnable r : WAITING) {
            if (WAITING.remove(r)) {
                r.run();
                return;
            }
        }
    }

    /** Hover targets drawn ahead at most; 0 for none. */
    public final int most;
    /** Where repaints are kept, for every session (and taken from). */
    private final SharedRepaints shared;
    /** Told on the render thread after each repaint drawn ahead is kept. */
    private volatile Runnable onReady = () -> {};
    /** See {@link #personal}. */
    private volatile Personal.Copy copy;

    // Guarded by this: where the last drawing ahead that finished was done from; nothing more to draw from there.
    private HtmlRenderer.Frame doneOf;
    private double doneX, doneY;
    /** The hover keys this session drew ahead over [doneOf]'s state, in the order drawn (tests). */
    private final List<List<Element>> mine = new ArrayList<>();

    // Guarded by this: the hover targets of the picture [targetsOf].
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

    /** Elements of one hover key: one of them (to hover) and where they are, in CSS pixels of the view. */
    private record Target(List<Element> key, Element element, List<Rectangle2D> boxes) {}

    /** A repaint drawn ahead (or drawn by any session) from a picture's state to [hoverKey], whose [boxes] (CSS pixels of the view) lead there. */
    public record Ready(List<Element> hoverKey, List<Rectangle2D> boxes, Repaint.Prepared prepared) {}

    /** [most] hover targets at a time, kept in [shared]. */
    public HoverAhead(int most, SharedRepaints shared) {
        this(most, shared, false);
    }

    /** As above; [everyTarget]: those the pointer cannot reach next too, after those it can, nearest first (tests). */
    HoverAhead(int most, SharedRepaints shared, boolean everyTarget) {
        this.most = Math.max(0, most);
        this.shared = shared;
        this.everyTarget = everyTarget;
    }

    private final boolean everyTarget;

    public boolean on() {
        return most > 0;
    }

    public SharedRepaints shared() {
        return shared;
    }

    /** [r] runs on the render thread each time a repaint drawn ahead by this session is kept. */
    public void onReady(Runnable r) {
        onReady = r == null ? () -> {} : r;
    }

    /** Whether nothing else is drawing a whole picture: a drawing ahead would take a core from its bands. */
    public static boolean quiet() {
        return HtmlRenderer.painting() == 0;
    }

    /**
     * Real work is coming: every drawing ahead stops, but for one drawing what its own session has just asked for, or
     * what another session waits for ({@link SharedRepaints#claim}: claim before calling this).
     */
    public static void makeRoom() {
        for (Job j : RUNNING) {
            Object k = j.drawingKey;
            if (k != null && j.ahead.shared.awaited(k)) j.last = true;
            else j.budget.cancel();
        }
    }

    /**
     * What a repaint from [before] (a picture of its page and document, drawn with [drawnHover] and [drawnPress]) to
     * [hoverKey] and [pressKey] is kept under. A page whose pictures can change without its state changing (images
     * that arrive late), or one that moves (a repaint carries the motion of the session it was drawn for), is keyed by
     * the picture itself too, so only this picture finds it: such repaints are never shared.
     */
    public static SharedRepaints.Key key(HtmlRenderer.Frame before, List<Element> drawnHover, List<Element> drawnPress,
                                         List<Element> hoverKey, List<Element> pressKey) {
        BufferedImage img = before.image();
        Page page = before.page();
        return new SharedRepaints.Key(before.document(), page, img.getWidth(), img.getHeight(), before.scale(), before.scrollY(),
                page.schemed() || page.moves() ? img : null, drawnHover, drawnPress, hoverKey, pressKey);
    }

    /** Whether a drawing ahead from (x, y) over [f] would draw nothing new: one finished there, and nothing has changed. */
    public synchronized boolean done(HtmlRenderer.Frame f, double x, double y) {
        return doneOf == f && Math.abs(x - doneX) < 8 && Math.abs(y - doneY) < 8;
    }

    /**
     * The repaint from [before]'s drawn state ([drawnHover], [drawnPress]) to [hoverKey] and [pressKey], if it is kept
     * (drawn ahead, or drawn by any session), ready to {@link Repaint.Prepared#apply}; else null.
     */
    public Repaint.Prepared take(HtmlRenderer.Frame before, List<Element> drawnHover, List<Element> drawnPress,
                                 List<Element> hoverKey, List<Element> pressKey) {
        Repaint.Prepared p = shared.take(key(before, drawnHover, drawnPress, hoverKey, pressKey));
        counted(before, hoverKey, p);
        return p;
    }

    /**
     * Claims the repaint [key] (see {@link SharedRepaints#claim}), for a session about to draw it, and counts what it
     * found as {@link #take} does if [count] (not when asking again after waiting for it).
     */
    public SharedRepaints.Claim claim(Object key, HtmlRenderer.Frame before, List<Element> hoverKey, boolean count) {
        SharedRepaints.Claim c = shared.claim(key, count);
        if (count) counted(before, hoverKey, c.kept() ? c.prepared() : null);
        return c;
    }

    private void counted(HtmlRenderer.Frame before, List<Element> hoverKey, Repaint.Prepared p) {
        if (p != null) {
            taken.incrementAndGet();
            return;
        }
        synchronized (this) {
            (pickedOf != before ? missNoJob : picked.contains(hoverKey) ? missUnfinished : missNotPicked).incrementAndGet();
        }
    }

    /**
     * Forgets where it last drew ahead from: the picture changed, or the session closed. What it drew stays shared,
     * but for repaints only that picture could take (keyed by it, see {@link #key}), which go with it.
     */
    public void drop() {
        HtmlRenderer.Frame of;
        synchronized (this) {
            of = doneOf != null ? doneOf : pickedOf;
            doneOf = null;
            mine.clear();
        }
        if (of != null && of.image() != null) shared.forget(of.image());
    }

    /** The hover keys this session drew ahead from the picture it last finished drawing ahead over (tests). */
    synchronized List<List<Element>> keys() {
        return new ArrayList<>(mine);
    }

    /**
     * Every repaint kept from [before]'s state ([drawnHover], [drawnPress], [pressKey]) to one of its hover targets,
     * with where that target is: what is ready for whatever the pointer reaches next. Takes nothing.
     */
    public List<Ready> ready(HtmlRenderer.Frame before, List<Element> drawnHover, List<Element> drawnPress, List<Element> pressKey) {
        List<Ready> out = new ArrayList<>();
        Personal.Copy c = copyOf(before.document());
        for (Target t : targets(before)) {
            SharedRepaints.Entry e = shared.peek(key(before, drawnHover, drawnPress, t.key, pressKey));
            if (e == null && c != null) {
                // Another copy's, whose pixels are this one's as long as the pictures are alike round them and nothing
                // of this player's own is near them; with this copy's own frame.
                e = shared.peek(shareKey(c, before, drawnHover, drawnPress, t.key, pressKey));
                Repaint.Prepared p = e == null ? null : e.prepared();
                Personal.Copy from = p == null ? null : Personal.copy(p.frame().document());
                p = from == null || !Personal.clear(c, before, p) ? null : Personal.translate(p, from, c, before);
                if (p != null) out.add(new Ready(t.key, t.boxes, p));
                continue;
            }
            if (e != null && e.prepared() != null) out.add(new Ready(t.key, t.boxes, e.prepared()));
        }
        return out;
    }

    /**
     * A copy of [before] to draw over, in a {@link PixelPool} picture of this thread's: hand it back with
     * {@link PixelPool#givePicture} once nothing reads it. Never [before]'s own picture, which its session shows and
     * may share with others (FirstPictures).
     */
    private static HtmlRenderer.Frame scratch(HtmlRenderer.Frame before) {
        BufferedImage from = before.image();
        BufferedImage to = from.getType() == BufferedImage.TYPE_INT_ARGB_PRE ? PixelPool.picture(from.getWidth(), from.getHeight())
                : new BufferedImage(from.getWidth(), from.getHeight(), from.getType());
        int[] src = ((DataBufferInt) from.getRaster().getDataBuffer()).getData();
        System.arraycopy(src, 0, ((DataBufferInt) to.getRaster().getDataBuffer()).getData(), 0, from.getWidth() * from.getHeight());
        return new HtmlRenderer.Frame(to, before.scale(), before.viewportWidth(), before.viewportHeight(), before.pageHeight(), before.scrollY(),
                before.hits(), before.styles(), before.document(), before.page());
    }

    /**
     * A drawing ahead over [before] (the picture on the screen, of [doc] at [width] x [height], [scale]), drawn with
     * [drawnHover] and [drawnPress]. The pointer is at (x, y), moving at (vx, vy), CSS pixels (a second); [pressed] is
     * pressed, with [pressKey]; [hoverKey] is the hover now.
     */
    public Job job(Page page, Document doc, int width, int height, double scale, HtmlRenderer.Frame before,
                   List<Element> drawnHover, List<Element> drawnPress, List<Element> hoverKey, Element pressed, List<Element> pressKey,
                   double x, double y, double vx, double vy, long timeoutMs) {
        return job(page, doc, width, height, scale, before, drawnHover, drawnPress, hoverKey, pressed, pressKey, x, y, vx, vy, timeoutMs, copy);
    }

    private Job job(Page page, Document doc, int width, int height, double scale, HtmlRenderer.Frame before,
                    List<Element> drawnHover, List<Element> drawnPress, List<Element> hoverKey, Element pressed, List<Element> pressKey,
                    double x, double y, double vx, double vy, long timeoutMs, Personal.Copy copy) {
        return new Job(this, page, doc, width, height, scale, before, drawnHover, drawnPress, hoverKey, pressed, pressKey, x, y, vx, vy,
                RenderBudget.cancellable(timeoutMs), copy != null && copy.doc == doc ? copy : null);
    }

    /**
     * The session's document is [c], a player's own copy of a personal page, whose repaints other players' copies may
     * take and which may take theirs (see {@link Personal}); null when it is not one (or sharing them is off).
     */
    public void personal(Personal.Copy c) {
        copy = c;
    }

    /** The copy of {@link #personal}, if it is [doc]. */
    private Personal.Copy copyOf(Document doc) {
        Personal.Copy c = copy;
        return c != null && c.doc == doc ? c : null;
    }

    /**
     * What the repaint from [before]'s drawn state to [hoverKey] and [pressKey] is kept under for every session that
     * may take it: of [copy] (a personal page's copy: {@link Personal#key}), or else of [before]'s document ({@link #key}).
     */
    public static Object shareKey(Personal.Copy copy, HtmlRenderer.Frame before, List<Element> drawnHover, List<Element> drawnPress,
                                  List<Element> hoverKey, List<Element> pressKey) {
        Personal.Key k = copy == null ? null : Personal.key(copy, before, drawnHover, drawnPress, hoverKey, pressKey);
        return k != null ? k : key(before, drawnHover, drawnPress, hoverKey, pressKey);
    }

    public static final class Job {
        final HoverAhead ahead;
        final Page page;
        final Document doc;
        final int width, height;
        final double scale, x, y, vx, vy;
        final HtmlRenderer.Frame before;
        final List<Element> drawnHover, drawnPress, hoverKey, pressKey;
        final Element pressed;
        final RenderBudget budget;
        /** The player's own copy of a personal page it draws over, or null. */
        final Personal.Copy copy;
        /** The hover key being drawn now and the key it is kept under, or null. */
        private volatile List<Element> drawing;
        private volatile Object drawingKey;
        /** Stop after the one being drawn: a session wants it. */
        private volatile boolean last;

        private Job(HoverAhead ahead, Page page, Document doc, int width, int height, double scale, HtmlRenderer.Frame before,
                    List<Element> drawnHover, List<Element> drawnPress, List<Element> hoverKey, Element pressed, List<Element> pressKey,
                    double x, double y, double vx, double vy, RenderBudget budget, Personal.Copy copy) {
            this.ahead = ahead;
            this.page = page;
            this.doc = doc;
            this.width = width;
            this.height = height;
            this.scale = scale;
            this.before = before;
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
            this.copy = copy;
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

    /** Draws ahead for [job], nearest target first, until done, stopped, out of budget, or every one is known. On a render thread. */
    public void run(Job job) {
        RUNNING.add(job);
        boolean finished = false;
        long cpu0 = THREADS.getCurrentThreadCpuTime(), alloc0 = THREADS.getCurrentThreadAllocatedBytes();
        HtmlRenderer.Frame base = null;
        List<List<Element>> drew = new ArrayList<>();
        try {
            List<Target> ranked = rank(job, targets(job.before));
            synchronized (this) {
                if (pickedOf != job.before) picked = new ArrayList<>();
                pickedOf = job.before;
                for (Target t : ranked) if (!picked.contains(t.key)) picked.add(t.key);
            }
            // Up to [most] that a hover would show something by (a repaint that changes nothing, a card that is only
            // raised, takes no turn), trying at most twice as many.
            int useful = 0, tried = 0;
            for (Target t : ranked) {
                if (useful >= most || tried >= 2 * most) break;
                if (job.budget.cancelled() || job.last || !quiet() || !affordable()) return;
                Set<Element> moved = changed(job.drawnHover, job.drawnPress, t.key, job.pressKey);
                if (moved.isEmpty()) continue;
                Object key = shareKey(job.copy, job.before, job.drawnHover, job.drawnPress, t.key, job.pressKey);
                // For this copy alone, where one that paints the player's own values is kept.
                SharedRepaints.Key own = key instanceof Personal.Key ? key(job.before, job.drawnHover, job.drawnPress, t.key, job.pressKey) : null;
                // Kept already, or another session is drawing it: one drawing serves both. Unless what is kept is one
                // that paints a player's own values: then each copy draws its own, kept for itself alone.
                if (own != null && shared.known(own)) {
                    useful++;
                    continue;
                }
                SharedRepaints.Entry kept = shared.peek(key);
                boolean forOwn = kept != null && kept.own() && own != null;
                if (kept != null && !forOwn || kept == null && !shared.claim(key, false).mine()) {
                    // Ready, or being drawn by another session.
                    if (kept == null || kept.prepared() != null && !empty(kept.prepared())) useful++;
                    continue;
                }
                tried++;
                boolean settled = forOwn;
                long c0 = THREADS.getCurrentThreadCpuTime();
                try {
                    if (base == null) base = scratch(job.before);
                    job.drawingKey = key;
                    job.drawing = t.key;
                    Repaint.Prepared p = Repaint.prepare(job.page, job.doc, job.width, job.height, job.scale, job.before.scrollY(),
                            HtmlRenderer.state(t.element, job.pressed), base, moved, job.budget);
                    long cpu = THREADS.getCurrentThreadCpuTime() - c0;
                    prepared.incrementAndGet();
                    if (forOwn) {
                        shared.put(own, p, cpu, true);
                    } else if (own != null && p != null && !Personal.clear(job.copy, job.before, p)) {
                        // It paints the player's own values: every other copy draws its own, and this one keeps it.
                        shared.putOwn(key, true);
                        shared.put(own, p, cpu, true);
                    } else {
                        shared.put(key, own != null && p != null ? Personal.share(p, job.before) : p, cpu, true);
                    }
                    settled = true;
                    drew.add(t.key);
                    if (p != null && !empty(p)) useful++;
                    if (p != null) onReady.run();
                } catch (RenderAbandoned e) {
                    abandoned.incrementAndGet();
                    return;
                } finally {
                    job.drawing = null;
                    job.drawingKey = null;
                    if (!settled) shared.abandon(key);
                    spend(THREADS.getCurrentThreadCpuTime() - c0);
                }
            }
            finished = true;
        } finally {
            RUNNING.remove(job);
            // Nothing kept reads it: a prepared repaint holds its own pixels, and is applied to the session's picture.
            if (base != null && base.image().getType() == BufferedImage.TYPE_INT_ARGB_PRE) PixelPool.givePicture(base.image());
            cpuNs.addAndGet(THREADS.getCurrentThreadCpuTime() - cpu0);
            allocBytes.addAndGet(THREADS.getCurrentThreadAllocatedBytes() - alloc0);
            synchronized (this) {
                if (doneOf != job.before) mine.clear();
                mine.addAll(drew);
                if (finished) {
                    doneOf = job.before;
                    doneX = job.x;
                    doneY = job.y;
                }
            }
        }
    }

    /** Whether [p] changes no pixel (a hover that only raises a box over what it does not overlap). */
    private static boolean empty(Repaint.Prepared p) {
        return p.pixels().length == 0;
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
    private synchronized List<Target> targets(HtmlRenderer.Frame f) {
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
     * CSS pixels the pointer goes from rest in its first report, at most, as far as drawing ahead counts: a glide from a
     * rest by a card reaches some 50 px into it (the trials in rigs/hl/targets.json, 3 reports over 150 px).
     */
    static final double REACH = 56;
    /** Seconds between two reports of the pointer. */
    static final double REPORT = 0.05;
    /** CSS pixels between the points where the next report is looked for. */
    static final double STEP = 6;

    /**
     * The targets worth drawing for [job], likeliest first: by the chance that the next report of the pointer lands on
     * each. Where it lands is taken as anywhere in a disc round the pointer, {@link #REACH} wide at rest, and round where
     * it is heading (half a report on) and as wide as three quarters of a report's travel when it moves. The chance of a
     * target is the share of that disc it is what the pointer is over, topmost first as a hover finds it (a card's row
     * over the card: the row's state, not the card's). The state the pointer is in now is left out.
     */
    private List<Target> rank(Job job, List<Target> all) {
        HtmlRenderer.Frame f = job.before;
        double speed = Math.hypot(job.vx, job.vy);
        double reach = Math.max(REACH, 0.75 * speed * REPORT);
        double cx = job.x + 0.5 * job.vx * REPORT, cy = job.y + 0.5 * job.vy * REPORT;
        Map<List<Element>, Target> byKey = new java.util.HashMap<>();
        for (Target t : all) byKey.put(t.key, t);
        Map<Element, List<Element>> keyOf = new IdentityHashMap<>();
        Map<List<Element>, double[]> score = new LinkedHashMap<>();
        // Rings of points, as many to a ring as its length holds: each point stands for the same area.
        for (double r = STEP / 2; r <= reach; r += STEP) {
            int n = Math.max(6, (int) Math.round(2 * Math.PI * r / STEP));
            for (int a = 0; a < n; a++) {
                double ang = 2 * Math.PI * (a + 0.5 * ((int) (r / STEP) & 1)) / n;
                double x = cx + r * Math.cos(ang), y = cy + r * Math.sin(ang);
                if (x < 0 || y < 0 || x >= f.viewportWidth() || y >= f.viewportHeight()) continue;
                Element e = f.elementAt(x, y);
                List<Element> key = e == null ? List.of() : keyOf.computeIfAbsent(e, k -> f.page().styles.stateKey(k, false));
                if (key.equals(job.hoverKey) || !byKey.containsKey(key)) continue;
                double d = Math.hypot(x - job.x, y - job.y);
                double[] s = score.computeIfAbsent(key, k -> new double[]{0, Double.MAX_VALUE});
                s[0]++;
                s[1] = Math.min(s[1], d);
            }
        }
        List<Map.Entry<List<Element>, double[]>> ranked = new ArrayList<>(score.entrySet());
        // Likeliest first; of two as likely, the nearer.
        ranked.sort((p, q) -> p.getValue()[0] != q.getValue()[0] ? Double.compare(q.getValue()[0], p.getValue()[0]) : Double.compare(p.getValue()[1], q.getValue()[1]));
        List<Target> out = new ArrayList<>(ranked.size());
        for (Map.Entry<List<Element>, double[]> e : ranked) out.add(byKey.get(e.getKey()));
        if (everyTarget) {
            List<Target> rest = new ArrayList<>();
            for (Target t : all) if (!t.key.equals(job.hoverKey) && !score.containsKey(t.key)) rest.add(t);
            rest.sort(java.util.Comparator.comparingDouble(t -> distance(t, job.x, job.y)));
            out.addAll(rest);
        }
        return out;
    }

    /** How far (CSS px) the nearest of [t]'s boxes is from (x, y); 0 inside one, and for what lies under everything. */
    private static double distance(Target t, double x, double y) {
        double best = t.boxes.isEmpty() ? 0 : Double.MAX_VALUE;
        for (Rectangle2D b : t.boxes) {
            double nx = Math.max(b.getMinX(), Math.min(x, b.getMaxX())), ny = Math.max(b.getMinY(), Math.min(y, b.getMaxY()));
            best = Math.min(best, Math.hypot(nx - x, ny - y));
        }
        return best;
    }
}
