package ai.resourcepack.ui.html;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How long one render may take. A render thread cannot be stopped from outside, so styling, layout
 * and painting call {@link #check} as they go (once per element, box, layer, blur row), and the
 * render gives up with {@link RenderAbandoned} once it has used up its limit. Shared by the bands
 * of one picture: a band that fails {@link #cancel}s the others.
 *
 * <p>The limit is CPU time, not time on the clock: a picture that is slow only because the machine is busy (other
 * pictures being drawn, other processes, a starved core) is not a page that cannot be drawn, and giving it up would
 * show the player an error for nothing. What counts is the CPU of the thread that made the budget, the one drawing
 * the picture (it styles and lays the page out, and paints a band or more). Reading another thread's CPU costs a few
 * microseconds, so {@link #check} reads only the clock until the thread could have used the limit up (it cannot have
 * had more CPU than time went by), and then again only as often as the rest of the limit allows. However busy the
 * machine, a render that has run {@link #WALL_TIMES} times its limit on the clock is given up too, so a page whose
 * cost lands in bands painted on other threads cannot run unbounded.
 */
public final class RenderBudget {
    /** No deadline (tests, benchmarks): {@link #check} never throws unless cancelled. */
    public static final RenderBudget NONE = new RenderBudget(0);
    /** How many times its limit a render may run on the clock, however little CPU it got. */
    static final int WALL_TIMES = 5;
    /** Renders given up for taking longer than their limit, since the server started: {@code /mcgui stats} shows it. */
    private static final AtomicLong TIMED_OUT = new AtomicLong();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final boolean CPU = THREADS.isThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled();

    private final long start = System.nanoTime(), limitNs;
    private final long millis;
    /** The thread that made this budget, and its CPU time then (-1 where the JVM cannot say: the clock alone then). */
    private final long owner, ownerCpu;
    private volatile boolean cancelled, timedOut;
    /** Until this moment (System.nanoTime) the limit cannot have been used up, and {@link #check} reads only the clock. */
    private volatile long nextLook;

    private RenderBudget(long millis) {
        this.millis = millis;
        this.limitNs = millis > 0 ? millis * 1_000_000L : Long.MAX_VALUE;
        this.owner = Thread.currentThread().threadId();
        this.ownerCpu = millis > 0 && CPU ? THREADS.getCurrentThreadCpuTime() : -1;
        this.nextLook = millis > 0 ? start + limitNs : Long.MAX_VALUE;
    }

    /** A budget of [millis] of CPU from now, made on the thread that draws the picture; 0 or less for none. */
    public static RenderBudget of(long millis) {
        return millis > 0 ? new RenderBudget(millis) : NONE;
    }

    /** As {@link #of}, but always one of its own, so it can be {@link #cancel}led even with no deadline. */
    static RenderBudget cancellable(long millis) {
        return new RenderBudget(Math.max(0, millis));
    }

    boolean cancelled() {
        return cancelled;
    }

    /** Throws {@link RenderAbandoned} once the limit is used up or another part of the render failed. */
    public void check() {
        if (cancelled) throw abandoned();
        long look = nextLook;
        if (look != Long.MAX_VALUE && System.nanoTime() - look >= 0) look();
    }

    /** The clock has run the limit (or the rest of it) since the last look: has the drawing thread's CPU? */
    private synchronized void look() {
        if (cancelled) throw abandoned();
        long now = System.nanoTime(), wall = now - start;
        long cpu = ownerCpu < 0 ? -1 : THREADS.getThreadCpuTime(owner);
        // The drawing thread gone (its render failed and this is a band finishing), or no CPU times: the clock.
        cpu = cpu < 0 ? wall : cpu - ownerCpu;
        long wallLimit = WALL_TIMES * limitNs;
        if (cpu >= limitNs || wall >= wallLimit) {
            timedOut = true;
            cancelled = true;
            TIMED_OUT.incrementAndGet();
            throw abandoned();
        }
        // The thread cannot have more CPU than time goes by: nothing to look at until the rest of the limit has gone by.
        nextLook = now + Math.min(limitNs - cpu, wallLimit - wall);
    }

    /** Makes every later {@link #check} throw: the picture is not going to be finished. */
    public void cancel() {
        if (this != NONE) cancelled = true;
    }

    private RenderAbandoned abandoned() {
        return new RenderAbandoned(timedOut ? "took longer than " + millis + " ms to draw (render-timeout)" : "the render was given up");
    }

    /** How many renders have been given up for taking too long, since the server started. */
    public static long timedOut() {
        return TIMED_OUT.get();
    }
}
