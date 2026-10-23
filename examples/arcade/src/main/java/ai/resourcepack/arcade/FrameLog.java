package ai.resourcepack.arcade;

import ai.resourcepack.ui.framework.api.Canvas;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Locale;

/**
 * A game's last frames and the input they took, for {@code /arcade stats} and {@code /arcade trace}: when each frame
 * started and was handed to the canvas, the CPU its parts took, and for each input when it arrived and which frame
 * used it. Written by the loop thread (inputs arrive from the server thread), read by commands.
 */
final class FrameLog {
    private static final int N = 2048;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private final long[] start = new long[N], shown = new long[N];
    private final int[] tickUs = new int[N], drawUs = new int[N], canvasUs = new int[N], changed = new int[N];
    private int frames;
    private final long[] inputAt = new long[N];
    private final int[] inputFrame = new int[N];
    private final char[] inputKind = new char[N];
    private int inputs;

    // The frame being made, on the loop thread.
    private long frameStart, cpuMark;
    private int tick, draw;

    /** How many of [canvas]'s tiles it has published anew since [before] was taken. */
    static int changed(Canvas canvas, Canvas.Snapshot before) {
        Canvas.Snapshot now = canvas.snapshot();
        if (now.sameAs(before)) return 0;
        int n = 0;
        for (int t = 0; t < canvas.tiles(); t++) if (now.tile(t, Canvas.Encoding.HI) != before.tile(t, Canvas.Encoding.HI)) n++;
        return n;
    }

    /** Loop thread: a frame starts. */
    void begin() {
        frameStart = System.nanoTime();
        cpuMark = THREADS.getCurrentThreadCpuTime();
        tick = draw = 0;
    }

    /** Loop thread: an input that arrived at [at] (System.nanoTime) is used by the frame being made. */
    synchronized void input(long at, char kind) {
        int i = inputs++ % N;
        inputAt[i] = at;
        inputFrame[i] = frames;
        inputKind[i] = kind;
    }

    /** Loop thread: the game's tick is done. */
    void ticked() {
        long now = THREADS.getCurrentThreadCpuTime();
        tick = (int) ((now - cpuMark) / 1000);
        cpuMark = now;
    }

    /** Loop thread: the frame is drawn, and is about to go to the canvas. */
    void drawn() {
        long now = THREADS.getCurrentThreadCpuTime();
        draw = (int) ((now - cpuMark) / 1000);
        cpuMark = now;
    }

    /**
     * Loop thread: the canvas has the frame, [canvasNanos] after it was handed over; it published [tiles] changed
     * tiles (0: the frame changed nothing, and nothing is sent).
     */
    synchronized void shown(long canvasNanos, int tiles) {
        int i = frames++ % N;
        start[i] = frameStart;
        shown[i] = System.nanoTime();
        tickUs[i] = tick;
        drawUs[i] = draw;
        canvasUs[i] = (int) (canvasNanos / 1000);
        changed[i] = tiles;
    }

    /** The last frame's CPU of its tick and drawing, and the wall time of the canvas's part, in µs. */
    synchronized int[] last() {
        int i = (frames - 1) % N;
        return frames == 0 ? new int[3] : new int[]{tickUs[i], drawUs[i], canvasUs[i]};
    }

    /** One line: frames a second over the last [seconds], their spacing, CPU per frame, and input to canvas. */
    synchronized String summary(double seconds) {
        long now = System.nanoTime(), from = now - (long) (seconds * 1e9);
        int n = Math.min(frames, N), k = 0;
        double[] gaps = new double[n];
        double tickMs = 0, drawMs = 0, canvasMs = 0;
        long first = 0, last = 0, prev = 0;
        for (int f = frames - n; f < frames; f++) {
            int i = f % N;
            if (start[i] < from) {
                prev = shown[i];
                continue;
            }
            if (first == 0) first = shown[i];
            last = shown[i];
            if (prev != 0) gaps[k++] = (shown[i] - prev) / 1e6;
            prev = shown[i];
            tickMs += tickUs[i] / 1000.0;
            drawMs += drawUs[i] / 1000.0;
            canvasMs += canvasUs[i] / 1000.0;
        }
        if (k == 0) return "no frames";
        double[] g = Arrays.copyOf(gaps, k);
        Arrays.sort(g);
        int count = k + 1;
        double[] lat = latencies(from);
        return String.format(Locale.ROOT, "%.1f fps, spacing p50 %.1f p95 %.1f max %.1f ms; per frame tick %.2f draw %.2f canvas %.2f ms; input to canvas p50 %s p95 %s ms (%d)",
                k / ((last - first) / 1e9), g[k / 2], g[Math.min(k - 1, (int) (k * 0.95))], g[k - 1],
                tickMs / count, drawMs / count, canvasMs / count,
                lat.length == 0 ? "-" : String.format(Locale.ROOT, "%.1f", lat[lat.length / 2]),
                lat.length == 0 ? "-" : String.format(Locale.ROOT, "%.1f", lat[Math.min(lat.length - 1, (int) (lat.length * 0.95))]), lat.length);
    }

    /** From each input since [from] to the moment its frame went to the canvas, in ms, sorted. */
    private double[] latencies(long from) {
        int n = Math.min(inputs, N), k = 0;
        double[] out = new double[n];
        for (int e = inputs - n; e < inputs; e++) {
            int i = e % N;
            int f = inputFrame[i];
            if (inputAt[i] < from || f >= frames || f < frames - N) continue;
            out[k++] = (shown[f % N] - inputAt[i]) / 1e6;
        }
        out = Arrays.copyOf(out, k);
        Arrays.sort(out);
        return out;
    }

    /** Everything kept, as JSON: frames [index, start, shown, tick µs, draw µs, canvas µs, tiles changed] and inputs [at, frame, kind]. */
    synchronized String json() {
        StringBuilder b = new StringBuilder("{\"frames\":[");
        int n = Math.min(frames, N);
        for (int f = frames - n; f < frames; f++) {
            int i = f % N;
            if (f > frames - n) b.append(',');
            b.append('[').append(f).append(',').append(start[i]).append(',').append(shown[i]).append(',')
                    .append(tickUs[i]).append(',').append(drawUs[i]).append(',').append(canvasUs[i]).append(',').append(changed[i]).append(']');
        }
        b.append("],\"inputs\":[");
        n = Math.min(inputs, N);
        for (int e = inputs - n; e < inputs; e++) {
            int i = e % N;
            if (e > inputs - n) b.append(',');
            b.append('[').append(inputAt[i]).append(',').append(inputFrame[i]).append(",\"").append(inputKind[i]).append("\"]");
        }
        return b.append("]}").toString();
    }
}
