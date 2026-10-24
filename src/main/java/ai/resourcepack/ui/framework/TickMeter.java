package ai.resourcepack.ui.framework;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/**
 * What the plugin's own work costs the server thread each tick: the time its tick tasks take and the bytes they
 * allocate, over the last {@link #WINDOW} ticks. {@code /mcgui stats} shows it, and the bench reads it. Measuring is two
 * clock reads and two reads of the thread's allocation counter a task, and allocates nothing. Server thread only.
 */
public final class TickMeter {
    /** Ticks kept: ten seconds. */
    public static final int WINDOW = 200;

    private static final com.sun.management.ThreadMXBean THREADS = threads();

    private final long[] nanos = new long[WINDOW], bytes = new long[WINDOW];
    private final int[] tickOf = new int[WINDOW];
    private long startNanos, startBytes;

    public TickMeter() {
        Arrays.fill(tickOf, Integer.MIN_VALUE);
    }

    private static com.sun.management.ThreadMXBean threads() {
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean t && t.isThreadAllocatedMemorySupported()) {
            if (!t.isThreadAllocatedMemoryEnabled()) t.setThreadAllocatedMemoryEnabled(true);
            return t;
        }
        return null;
    }

    /** Bytes this thread has allocated so far, or 0 where the JVM cannot say. */
    static long allocated() {
        return THREADS == null ? 0 : THREADS.getCurrentThreadAllocatedBytes();
    }

    /** A tick task starts. */
    public void begin() {
        startNanos = System.nanoTime();
        startBytes = allocated();
    }

    /** The task started by {@link #begin} ends, in tick [tick]: added to that tick's total. */
    public void end(int tick) {
        long dt = System.nanoTime() - startNanos, db = allocated() - startBytes;
        int i = Math.floorMod(tick, WINDOW);
        if (tickOf[i] != tick) {
            tickOf[i] = tick;
            nanos[i] = 0;
            bytes[i] = 0;
        }
        nanos[i] += dt;
        bytes[i] += db;
    }

    /** The ticks measured of the last {@link #WINDOW} before [now]: how many, and their time and allocation. */
    public record Summary(int ticks, double meanMicros, double p50Micros, double p99Micros, double maxMicros, double meanBytes, long maxBytes) {
        public String json() {
            return String.format(Locale.ROOT, "{\"ticks\":%d,\"mean_us\":%.2f,\"p50_us\":%.2f,\"p99_us\":%.2f,\"max_us\":%.2f,\"mean_bytes\":%.1f,\"max_bytes\":%d}",
                    ticks, meanMicros, p50Micros, p99Micros, maxMicros, meanBytes, maxBytes);
        }
    }

    public Summary summary(int now) {
        long[] t = new long[WINDOW];
        int n = 0;
        long sumT = 0, sumB = 0, maxB = 0;
        for (int i = 0; i < WINDOW; i++) {
            if (tickOf[i] > now || tickOf[i] <= now - WINDOW) continue;
            t[n++] = nanos[i];
            sumT += nanos[i];
            sumB += bytes[i];
            maxB = Math.max(maxB, bytes[i]);
        }
        if (n == 0) return new Summary(0, 0, 0, 0, 0, 0, 0);
        Arrays.sort(t, 0, n);
        return new Summary(n, sumT / 1e3 / n, t[(n - 1) / 2] / 1e3, t[Math.min(n - 1, (int) Math.ceil(0.99 * n) - 1)] / 1e3, t[n - 1] / 1e3,
                (double) sumB / n, maxB);
    }
}
