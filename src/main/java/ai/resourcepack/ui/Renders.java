package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.Cpu;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * Where the plugin's render jobs run: the framework's shared {@link Cpu} pool, the same threads that paint a
 * picture's bands and encode its tiles, so all of it together never runs more threads than {@code cpu-threads}.
 *
 * <p>Jobs wait here, not in the pool, and go to it at most one per pool thread, the most urgent {@link Kind} first:
 * the pool's own queue is first come first served, so on a busy server a page somebody just opened waited behind
 * every hover and animation frame already queued (measured: 4 s median with 40 players hovering and 10 watching an
 * animation). Within a kind, first come first served.
 *
 * <p>This also counts the plugin's own jobs so that {@link #close} can stop taking new ones and wait for the ones
 * running (the pool is not the plugin's to shut down), and keeps how long the last jobs of each kind waited for a
 * thread and ran, for {@code /mcgui stats}. Not final: tests count the jobs they hand it.
 */
class Renders implements Executor {
    /** What a job draws, most urgent first. */
    enum Kind {
        /** A page's first picture (shown, or navigated to): the player sees nothing new until it is drawn. */
        SHOW,
        /** A picture somebody is waiting for: a hover, a click, a scroll, a refresh. */
        PICTURE,
        /** The start-up warm-up and the kept first pictures it draws. */
        WARM,
        /** An animation's next moment, which the clock asked for: late, it is only a dropped frame. */
        MOTION,
        /** A hover drawn ahead, in case the pointer reaches it. */
        AHEAD,
        /** The picture of a view nobody has drawn over for a while, put away (ParkedPicture): only ever memory saved. */
        PARK
    }

    /** Jobs kept per kind for {@link #times}. */
    private static final int KEEP = 512;
    private static final Kind[] KINDS = Kind.values();

    /** Jobs taken and not yet finished or dropped, queued or in the pool; and of those, in the pool. Under this object's lock. */
    private int running, inPool;
    private boolean closed;
    @SuppressWarnings("unchecked")
    private final ArrayDeque<Runnable>[] waiting = new ArrayDeque[KINDS.length];
    /** Per kind, the last {@link #KEEP} jobs' wait for a thread and run, in nanoseconds; and how many ran in all. Under this object's lock. */
    private final long[][] waits = new long[KINDS.length][KEEP], runs = new long[KINDS.length][KEEP];
    private final long[] count = new long[KINDS.length];

    Renders() {
        for (int k = 0; k < KINDS.length; k++) waiting[k] = new ArrayDeque<>();
    }

    @Override
    public void execute(Runnable job) {
        execute(Kind.PICTURE, job);
    }

    public void execute(Kind kind, Runnable job) {
        long queued = System.nanoTime();
        Runnable run = () -> {
            long start = System.nanoTime();
            try {
                // Closed since it went to the pool: skipped.
                synchronized (this) {
                    if (closed) return;
                }
                job.run();
            } finally {
                long end = System.nanoTime();
                synchronized (this) {
                    int k = kind.ordinal(), i = (int) (count[k]++ % KEEP);
                    waits[k][i] = start - queued;
                    runs[k][i] = end - start;
                    inPool--;
                    if (--running == 0) notifyAll();
                }
                dispatch();
            }
        };
        synchronized (this) {
            if (closed) return;
            running++;
            waiting[kind.ordinal()].add(run);
        }
        dispatch();
    }

    /** Hands the pool the most urgent jobs waiting, while it has a thread for each. */
    private void dispatch() {
        int threads = Cpu.pool().getParallelism();
        while (true) {
            Runnable next = null;
            synchronized (this) {
                if (closed || inPool >= threads) return;
                for (ArrayDeque<Runnable> q : waiting) if ((next = q.poll()) != null) break;
                if (next == null) return;
                inPool++;
            }
            try {
                Cpu.pool().execute(next);
            } catch (RuntimeException e) {
                // The pool refused it (shutting down): it never runs.
                synchronized (this) {
                    inPool--;
                    if (--running == 0) notifyAll();
                }
                throw e;
            }
        }
    }

    /** Whether a pool thread is free and nothing waits for one: work that is only a guess may run now without delaying anyone's. */
    boolean idle() {
        ForkJoinPool pool = Cpu.pool();
        return !queued() && pool.getRunningThreadCount() < pool.getParallelism();
    }

    /**
     * Whether any work waits for a pool thread: a job here, or a picture's bands and tiles in the pool's own queue. Work
     * that is only a guess, under way, stops for it.
     */
    boolean queued() {
        synchronized (this) {
            for (ArrayDeque<Runnable> q : waiting) if (!q.isEmpty()) return true;
        }
        ForkJoinPool pool = Cpu.pool();
        return pool.getQueuedSubmissionCount() > 0 || pool.getQueuedTaskCount() > 0;
    }

    /**
     * The last jobs of [kind]: how many ran in all, and of the last few hundred the wait for a thread and the run,
     * median, 99th percentile and longest, in milliseconds, as JSON.
     */
    synchronized String times(Kind kind) {
        int k = kind.ordinal(), n = (int) Math.min(count[k], KEEP);
        long[] w = Arrays.copyOf(waits[k], n), r = Arrays.copyOf(runs[k], n);
        Arrays.sort(w);
        Arrays.sort(r);
        return String.format(Locale.ROOT, "{\"jobs\":%d,\"wait_ms\":[%.1f,%.1f,%.1f],\"run_ms\":[%.1f,%.1f,%.1f]}",
                count[k], at(w, 0.5), at(w, 0.99), at(w, 1), at(r, 0.5), at(r, 0.99), at(r, 1));
    }

    private static double at(long[] sorted, double q) {
        return sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1, Math.max(0, (int) Math.ceil(q * sorted.length) - 1))] / 1e6;
    }

    /** Takes no more jobs, drops those still waiting, and waits up to [timeout] for those running to finish. */
    void close(long timeout, TimeUnit unit) throws InterruptedException {
        long end = System.nanoTime() + unit.toNanos(timeout);
        synchronized (this) {
            closed = true;
            for (ArrayDeque<Runnable> q : waiting) {
                running -= q.size();
                q.clear();
            }
            if (running == 0) notifyAll();
            for (long left; running > 0 && (left = end - System.nanoTime()) > 0; ) TimeUnit.NANOSECONDS.timedWait(this, left);
        }
    }
}
