package ai.resourcepack.ui;

import ai.resourcepack.uiframework.api.Cpu;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * Where the plugin's render jobs run: UI Framework's shared {@link Cpu} pool, the same threads that paint a
 * picture's bands and encode its tiles, so all of it together never runs more threads than {@code cpu-threads}.
 * This only counts the plugin's own jobs so that {@link #close} can stop taking new ones and wait for the ones
 * running (the pool is not the plugin's to shut down).
 */
final class Renders implements Executor {
    private int running;
    private boolean closed;

    @Override
    public void execute(Runnable job) {
        synchronized (this) {
            if (closed) return;
            running++;
        }
        try {
            Cpu.pool().execute(() -> {
                try {
                    synchronized (this) {
                        if (closed) return;
                    }
                    job.run();
                } finally {
                    synchronized (this) {
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

    /** Takes no more jobs, skips those still queued, and waits up to [timeout] for those running to finish. */
    void close(long timeout, TimeUnit unit) throws InterruptedException {
        long end = System.nanoTime() + unit.toNanos(timeout);
        synchronized (this) {
            closed = true;
            for (long left; running > 0 && (left = end - System.nanoTime()) > 0; ) TimeUnit.NANOSECONDS.timedWait(this, left);
        }
    }
}
