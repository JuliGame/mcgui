package ai.resourcepack.ui.html;

/**
 * How long one render may take. A render thread cannot be stopped from outside, so styling, layout
 * and painting call {@link #check} as they go (once per element, box, layer, blur row), and the
 * render gives up with {@link RenderAbandoned} once the deadline has passed. Shared by the bands
 * of one picture: a band that fails {@link #cancel}s the others.
 */
public final class RenderBudget {
    /** No deadline (tests, benchmarks): {@link #check} never throws unless cancelled. */
    public static final RenderBudget NONE = new RenderBudget(0);
    /** Renders given up for taking longer than their limit, since the server started: {@code /ui stats} shows it. */
    private static final java.util.concurrent.atomic.AtomicLong TIMED_OUT = new java.util.concurrent.atomic.AtomicLong();

    private final long start = System.nanoTime(), limitNs;
    private final long millis;
    private volatile boolean cancelled, timedOut;

    private RenderBudget(long millis) {
        this.millis = millis;
        this.limitNs = millis > 0 ? millis * 1_000_000L : Long.MAX_VALUE;
    }

    /** A budget of [millis] from now; 0 or less for none. */
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

    /** Throws {@link RenderAbandoned} once the deadline has passed or another part of the render failed. */
    public void check() {
        if (cancelled) throw abandoned();
        if (System.nanoTime() - start > limitNs) late();
    }

    /** Past the deadline: given up, and counted once however many bands find out. */
    private synchronized void late() {
        if (!cancelled) {
            timedOut = true;
            cancelled = true;
            TIMED_OUT.incrementAndGet();
        }
        throw abandoned();
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
