package ai.resourcepack.ui.html;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * How many of the process's worker threads (render, painter, encoder: the pools, not the garbage collector or the
 * benchmark's own threads) are runnable at one moment, sampled every 2 ms from /proc. More than cores minus one means
 * the game's thread has to share a core with them. Unlike the ticker's lateness this does not depend on what else
 * the machine is running.
 */
final class RunQueue extends Thread {
    private static final String[] POOLS = {"ForkJoinPool", "UIEngine-cpu", "LoadBench-rend"};
    private volatile boolean stop;
    private int[] counts = new int[1 << 16];
    private long[] at = new long[1 << 16];
    private int n;

    RunQueue() {
        super("LoadBench-runq");
        setDaemon(true);
    }

    @Override
    public void run() {
        Path task = Path.of("/proc/self/task");
        while (!stop && n < counts.length) {
            int running = 0;
            try (var dirs = Files.newDirectoryStream(task)) {
                for (Path d : dirs) {
                    try {
                        String s = Files.readString(d.resolve("stat"));
                        int open = s.indexOf('('), close = s.lastIndexOf(')');
                        if (close < 0 || close + 2 >= s.length() || s.charAt(close + 2) != 'R') continue;
                        String name = s.substring(open + 1, close);
                        for (String p : POOLS) if (name.startsWith(p)) { running++; break; }
                    } catch (IOException e) {
                        // the thread ended
                    }
                }
            } catch (IOException e) {
                return;
            }
            counts[n] = running;
            at[n++] = System.nanoTime();
            LockSupport.parkNanos(2_000_000);
        }
    }

    /** {mean, p99, max runnable pool threads, % of samples with more than [limit]} for samples in [from, to). */
    double[] report(long from, long to, int limit) {
        stop = true;
        LockSupport.unpark(this);
        try {
            join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Integer> v = new ArrayList<>();
        for (int i = 0; i < n; i++) if (at[i] >= from && at[i] < to) v.add(counts[i]);
        if (v.isEmpty()) return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        int[] a = v.stream().mapToInt(Integer::intValue).sorted().toArray();
        return new double[]{Arrays.stream(a).average().orElse(0), a[Math.min(a.length - 1, (int) (a.length * .99))], a[a.length - 1],
                100.0 * Arrays.stream(a).filter(x -> x > limit).count() / a.length};
    }
}
