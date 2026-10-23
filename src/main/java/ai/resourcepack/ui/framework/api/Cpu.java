package ai.resourcepack.ui.framework.api;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one budget of threads for work that burns CPU: drawing pictures, converting and encoding them, and whatever
 * else a plugin on top of this one wants done off the server thread. A server has so many cores and the game's own
 * thread needs one of them, so every plugin that does heavy work here shares one pool of {@code cpu-threads}
 * (default: the cores minus one) rather than each making a pool of its own. Several pools add up to more runnable
 * threads than cores, and then the game's thread waits for a core like everybody else, which shows as a late tick.
 *
 * <p>Submit work to {@link #pool()}. Work may wait for work it submits itself (a render job waiting for its bands,
 * {@link Canvas#draw} waiting for its encoders): a pool thread that waits runs queued work in the meantime, or, if
 * there is none for it to run, hands its place to a spare thread, so the number of threads running stays at the
 * budget and a wait can never use the pool up. Work that waits for something outside the pool (the network, a lock
 * held by the game's thread) must not run here.
 */
public final class Cpu {
    private static int configured;
    private static ForkJoinPool pool;

    private Cpu() {}

    /** The default budget: the cores less one for the game's thread, and at least one. */
    public static int defaultThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    /** How many threads the pool runs at once. */
    public static synchronized int threads() {
        return configured > 0 ? configured : defaultThreads();
    }

    /**
     * The shared pool, made on first use. Do not keep it: {@link #configure} may replace it.
     * Callers on a pool thread (a render job) fork into the same pool, those outside it queue to it.
     */
    public static synchronized ForkJoinPool pool() {
        if (pool == null) {
            AtomicInteger n = new AtomicInteger();
            pool = new ForkJoinPool(threads(), p -> {
                ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p);
                t.setName("UIEngine-cpu-" + n.incrementAndGet());
                t.setDaemon(true);
                // Does nothing on Linux, where the scheduler ignores Java priorities; elsewhere it yields to the game.
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }, null, false);
        }
        return pool;
    }

    /**
     * Sets the budget (at least 1; 0 or less means {@link #defaultThreads}). Call it before the pool is used: a pool
     * already made is shut down after the work it holds, and a new one made on next use.
     */
    public static synchronized void configure(int threads) {
        configured = threads > 0 ? threads : 0;
        if (pool != null && pool.getParallelism() != threads()) shutdown();
    }

    /** Stops the pool once its queued work is done; the next use makes a new one. */
    public static synchronized void shutdown() {
        if (pool != null) pool.shutdown();
        pool = null;
    }
}
