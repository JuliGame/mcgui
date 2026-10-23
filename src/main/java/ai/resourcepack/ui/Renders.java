package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.api.Cpu;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * Where the plugin's render jobs run: the framework's shared {@link Cpu} pool, the same threads that paint a
 * picture's bands and encode its tiles, so all of it together never runs more threads than {@code cpu-threads}.
 * This only counts the plugin's own jobs so that {@link #close} can stop taking new ones and wait for the ones
 * running (the pool is not the plugin's to shut down), and keeps how long the last jobs of each {@link Kind} waited
 * for a thread and ran, for {@code /ui stats}. Not final: tests count the jobs they hand it.
 */
class Renders implements Executor {
    /** What a job draws. */
    enum Kind {
        /** A picture somebody is waiting for: a page shown, a hover, a click, a scroll, a refresh, an animation's next moment. */
        PICTURE,
        /** A hover drawn ahead, in case the pointer reaches it. */
        AHEAD,
        /** The start-up warm-up and the kept first pictures it draws. */
        WARM
    }

    /** Jobs kept per kind for {@link #times}. */
    private static final int KEEP = 512;

    private int running;
    private boolean closed;
    /** Per kind, the last {@link #KEEP} jobs' wait for a thread and run, in nanoseconds; and how many ran in all. Under this object's lock. */
    private final long[][] waits = new long[Kind.values().length][KEEP], runs = new long[Kind.values().length][KEEP];
    private final long[] count = new long[Kind.values().length];

    @Override
    public void execute(Runnable job) {
        execute(Kind.PICTURE, job);
    }

    public void execute(Kind kind, Runnable job) {
        synchronized (this) {
            if (closed) return;
            running++;
        }
        long queued = System.nanoTime();
        try {
            Cpu.pool().execute(() -> {
                long start = System.nanoTime();
                try {
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
                        if (--running == 0) notifyAll();
                    }
                }
            });
        } catch (RuntimeException e) {
            synchronized (this) {
                if (--running == 0) notifyAll();
            }
            throw e;
        }
    }

    /** Whether a pool thread is free and nothing waits for one: work that is only a guess may run now without delaying anyone's. */
    boolean idle() {
        ForkJoinPool pool = Cpu.pool();
        return pool.getRunningThreadCount() < pool.getParallelism() && pool.getQueuedSubmissionCount() == 0 && pool.getQueuedTaskCount() == 0;
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

    /** Takes no more jobs, skips those still queued, and waits up to [timeout] for those running to finish. */
    void close(long timeout, TimeUnit unit) throws InterruptedException {
        long end = System.nanoTime() + unit.toNanos(timeout);
        synchronized (this) {
            closed = true;
            for (long left; running > 0 && (left = end - System.nanoTime()) > 0; ) TimeUnit.NANOSECONDS.timedWait(this, left);
        }
    }
}
