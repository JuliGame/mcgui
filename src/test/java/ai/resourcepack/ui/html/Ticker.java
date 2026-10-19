package ai.resourcepack.ui.html;

import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

/**
 * A stand-in for the server thread: wakes every 50 ms, does 0.5 ms of busy work (or -Dtickwork ms), and records how late it woke and
 * how late it finished. Anything that takes its core (more runnable threads than cores), or stops all threads (a
 * garbage collection pause), shows as lateness. LoadBench runs one beside the load.
 */
final class Ticker extends Thread {
    static final long PERIOD = 50_000_000L;
    /** Busy work per tick (-Dtickwork=<ms>, default 0.5): a light thread is woken at once whatever else runs, a busy one has to share. */
    static final long WORK = (long) (Double.parseDouble(System.getProperty("tickwork", "0.5")) * 1e6);
    private volatile boolean stop;
    private final long[] wake = new long[1 << 16], done = new long[1 << 16];
    private final long[] at = new long[1 << 16];
    private int n;

    Ticker() {
        super("LoadBench-tick");
        setDaemon(true);
    }

    @Override
    public void run() {
        long due = System.nanoTime() + PERIOD;
        double sink = 1;
        while (!stop && n < wake.length) {
            long d;
            while ((d = due - System.nanoTime()) > 0) LockSupport.parkNanos(d);
            long woke = System.nanoTime();
            long end = woke + WORK;
            while (System.nanoTime() < end) sink = Math.sqrt(sink + 1.5);
            long fin = System.nanoTime();
            at[n] = due;
            wake[n] = woke - due;
            done[n] = fin - due - WORK;
            n++;
            due += PERIOD;
            // A tick missed altogether (the thread lost seconds): the next is due a period after this one ended.
            if (due < fin) due = fin + PERIOD - (fin - due) % PERIOD;
        }
        if (sink == 0) System.out.print("");
    }

    /** Lateness in ms of the ticks due in [from, to): {wake p50, p99, max, finish p50, p99, max, count, % of ticks finishing over 5 ms late}. */
    double[] report(long from, long to) {
        stop = true;
        LockSupport.unpark(this);
        try {
            join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int k = 0;
        long[] w = new long[n], f = new long[n];
        for (int i = 0; i < n; i++) {
            if (at[i] < from || at[i] >= to) continue;
            w[k] = wake[i];
            f[k++] = done[i];
        }
        w = Arrays.copyOf(w, k);
        f = Arrays.copyOf(f, k);
        Arrays.sort(w);
        Arrays.sort(f);
        if (k == 0) return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN};
        return new double[]{w[k / 2] / 1e6, w[Math.min(k - 1, (int) (k * .99))] / 1e6, w[k - 1] / 1e6,
                f[k / 2] / 1e6, f[Math.min(k - 1, (int) (k * .99))] / 1e6, f[k - 1] / 1e6, k,
                100.0 * Arrays.stream(f).filter(x -> x > 5_000_000L).count() / k};
    }
}
