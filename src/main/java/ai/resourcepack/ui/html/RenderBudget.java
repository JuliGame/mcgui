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

    private final long start = System.nanoTime(), limitNs;
    private final long millis;
    private volatile boolean cancelled;

    private RenderBudget(long millis) {
        this.millis = millis;
        this.limitNs = millis > 0 ? millis * 1_000_000L : Long.MAX_VALUE;
    }

    /** A budget of [millis] from now; 0 or less for none. */
    public static RenderBudget of(long millis) {
        return millis > 0 ? new RenderBudget(millis) : NONE;
    }

    /** Throws {@link RenderAbandoned} once the deadline has passed or another part of the render failed. */
    public void check() {
        if (cancelled || System.nanoTime() - start > limitNs) throw abandoned();
    }

    /** Makes every later {@link #check} throw: the picture is not going to be finished. */
    public void cancel() {
        if (this != NONE) cancelled = true;
    }

    private RenderAbandoned abandoned() {
        return new RenderAbandoned(cancelled && System.nanoTime() - start <= limitNs
                ? "the render was given up"
                : "took longer than " + millis + " ms to draw (render-timeout)");
    }
}
