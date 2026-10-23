package ai.resourcepack.ui.html.paint;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Pixel arrays handed back by whoever held them, once nothing can read them any more, and handed out again instead
 * of new ones. A whole picture used to leave 17 to 50 MB of garbage (the picture, the bands it is painted in, backdrop,
 * layer and blur copies), nearly all in arrays big enough for G1 to give each regions of its own ("humongous"), and
 * under load that drove collections whose pauses stop the server thread too.
 *
 * <p>Each thread keeps its own: what it was handed back is handed out only to whatever runs on it next, so an array
 * is never held by two at once as long as every holder hands back only what it holds, once, and no longer reads it.
 * An array may be taken on one thread and handed back on another (a session's picture is drawn by whichever render
 * thread is free). Two kinds:
 * <ul>
 *   <li>{@link #image} / {@link #give(BufferedImage)}: scratch images that live while one thing is painted (a band, a
 *   layer, a backdrop's copy, a shadow), backed by an array at least as long as they need and zeroed like a new one;
 *   never let out of the paint code (their arrays may be longer than width × height).</li>
 *   <li>{@link #picture} / {@link #givePicture}: a whole picture of exactly width × height, which a {@code Frame} and a
 *   canvas read; handed back by the session that drew it once no picture it shows uses it.</li>
 * </ul>
 * Bounded: a thread keeps at most {@link #SCRATCH_BYTES} of scratch and {@link #PICTURES} pictures, and a thread that
 * has not used its arrays for {@link #IDLE_NANOS} loses them, so a quiet server holds none.
 */
public final class PixelPool {
    private PixelPool() {}

    /** Arrays this small (ints) are left to the young generation: cheap there, and not worth the bookkeeping. */
    static final int MIN_INTS = 16 * 1024;
    /** Scratch kept per thread: a one-band picture with its margins (2048 x 1300 or so) and a backdrop's copy beside it. */
    static final long SCRATCH_BYTES = 24L << 20;
    /** Whole pictures kept per thread: enough for one view size at a time; another size replaces it. */
    static final int PICTURES = 1;
    /** A thread's arrays are dropped after this long unused. */
    static final long IDLE_NANOS = 30_000_000_000L;
    /**
     * What every thread's pool keeps at most, together: a sixteenth of the heap (128 MB of a 2 GB one), 32 to 256 MB. A
     * thread over it keeps an array only by dropping its own oldest, so a server with many cores does not hold a pool
     * per core's worth of pictures.
     */
    static final long TOTAL_BYTES = Math.max(32L << 20, Math.min(256L << 20, Runtime.getRuntime().maxMemory() / 16));
    /** Bytes kept by every pool now. */
    private static final java.util.concurrent.atomic.AtomicLong KEPT = new java.util.concurrent.atomic.AtomicLong();

    private static final ThreadLocal<Pool> POOL = ThreadLocal.withInitial(Pool::new);
    /** Every thread's pool, so an idle one can be emptied by another thread; dead threads' pools drop out. */
    private static final ConcurrentLinkedQueue<Entry> ALL = new ConcurrentLinkedQueue<>();

    /** A pool, and what it holds, which outlives it: a dead thread's pool goes with it, and its bytes come off {@link #KEPT}. */
    private record Entry(WeakReference<Pool> pool, java.util.concurrent.atomic.AtomicLong held) {}
    private static final java.util.concurrent.atomic.AtomicLong LAST_SWEEP = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    /** Bytes of the arrays made because none kept would do (tests, benchmarks). */
    private static final java.util.concurrent.atomic.LongAdder MADE = new java.util.concurrent.atomic.LongAdder();

    private static final ColorModel ARGB = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).getColorModel();
    private static final ColorModel ARGB_PRE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB_PRE).getColorModel();
    private static final int[] MASKS = {0x00ff0000, 0x0000ff00, 0x000000ff, 0xff000000};

    /**
     * A [w] × [h] image of [type] ({@code TYPE_INT_ARGB} or {@code TYPE_INT_ARGB_PRE}), every pixel 0 as in a new one, for
     * the calling code to paint into and hand back with {@link #give(BufferedImage)} when nothing reads it any more.
     */
    public static BufferedImage image(int w, int h, int type) {
        int n = Math.multiplyExact(w, h);
        if (n < MIN_INTS) return new BufferedImage(w, h, type);
        int[] a = pool().take(n);
        Arrays.fill(a, 0, n, 0);
        return wrap(a, w, h, type);
    }

    /** As {@link #image}, but holding anything: for code that sets every pixel itself before reading any. */
    public static BufferedImage uncleared(int w, int h, int type) {
        int n = Math.multiplyExact(w, h);
        if (n < MIN_INTS) return new BufferedImage(w, h, type);
        return wrap(pool().take(n), w, h, type);
    }

    /** At least [n] ints, holding anything: for the calling code alone, handed back with {@link #give(int[])}. */
    public static int[] ints(int n) {
        if (n < MIN_INTS) return new int[n];
        return pool().take(n);
    }

    /** [img], from {@link #image} (or any image nothing else reads, of a pooled size or not), not to be read again. */
    public static void give(BufferedImage img) {
        if (img != null && img.getRaster().getDataBuffer() instanceof DataBufferInt db) give(db.getData());
    }

    /** [a], not to be read again. */
    public static void give(int[] a) {
        if (a != null && a.length >= MIN_INTS) pool().keep(a);
    }

    /** A [w] × [h] premultiplied image backed by exactly w × h ints, holding anything (a picture paints every pixel). */
    public static BufferedImage picture(int w, int h) {
        int n = Math.multiplyExact(w, h);
        int[] a = pool().takePicture(n);
        return wrap(a, w, h, BufferedImage.TYPE_INT_ARGB_PRE);
    }

    /** [img], a whole picture from {@link #picture} nothing will read again (no frame shows it, no one shares it). */
    public static void givePicture(BufferedImage img) {
        if (img == null || !(img.getRaster().getDataBuffer() instanceof DataBufferInt db)) return;
        int[] a = db.getData();
        if (a.length == img.getWidth() * img.getHeight()) pool().keepPicture(a);
    }

    private static BufferedImage wrap(int[] a, int w, int h, int type) {
        ColorModel cm = type == BufferedImage.TYPE_INT_ARGB_PRE ? ARGB_PRE : type == BufferedImage.TYPE_INT_ARGB ? ARGB : null;
        if (cm == null) throw new IllegalArgumentException("Not an int ARGB type: " + type);
        WritableRaster r = Raster.createPackedRaster(new DataBufferInt(a, w * h), w, h, w, MASKS, null);
        return new BufferedImage(cm, r, cm.isAlphaPremultiplied(), null);
    }

    private static final boolean TRACE = Boolean.getBoolean("pixelpool.trace");

    /** Bytes of arrays made so far because no kept one would do (tests, benchmarks). */
    public static long madeBytes() {
        return MADE.sum();
    }

    /** Bytes kept by every live thread's pool now (tests, benchmarks). */
    public static long keptBytes() {
        long sum = 0;
        for (Entry e : ALL) sum += e.held().get();
        return sum;
    }

    /** Empties every thread's pool (tests, benchmarks). */
    public static void clear() {
        for (Entry e : ALL) {
            Pool p = e.pool().get();
            if (p != null) p.clear();
        }
    }

    /** This thread's pool; now and then, first, empties the pools of threads that have not used them for {@link #IDLE_NANOS}. */
    private static Pool pool() {
        long now = System.nanoTime(), last = LAST_SWEEP.get();
        // One thread at a time, holding no pool's lock while it takes each in turn.
        if (now - last > 5_000_000_000L && LAST_SWEEP.compareAndSet(last, now)) sweep(now);
        return POOL.get();
    }

    /** Empties idle pools, and forgets dead threads' (their arrays went with them). */
    private static void sweep(long now) {
        for (var it = ALL.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            Pool p = e.pool().get();
            if (p != null) {
                p.dropIfIdle(now);
            } else {
                it.remove();
                KEPT.addAndGet(-e.held().getAndSet(0));
            }
        }
    }

    /** Size classes an eighth of a power of two apart, so a need a few rows bigger than the last still fits. */
    static int rounded(int n) {
        int step = Math.max(1, Integer.highestOneBit(n) >> 3);
        return (int) Math.min(Integer.MAX_VALUE - 8, ((long) n + step - 1) / step * step);
    }

    /** One thread's arrays. Locked only so that another thread's sweep can empty it. */
    private static final class Pool {
        /** Scratch arrays, oldest given back first. */
        private final List<int[]> scratch = new ArrayList<>();
        private final List<int[]> pictures = new ArrayList<>();
        private long scratchBytes;
        private long used = System.nanoTime();
        private final java.util.concurrent.atomic.AtomicLong held = new java.util.concurrent.atomic.AtomicLong();

        Pool() {
            ALL.add(new Entry(new WeakReference<>(this), held));
        }

        /** [bytes] more (or fewer) kept here. */
        private void count(long bytes) {
            held.addAndGet(bytes);
            KEPT.addAndGet(bytes);
        }

        synchronized int[] take(int n) {
            touch();
            int best = -1;
            for (int i = 0; i < scratch.size(); i++) {
                int len = scratch.get(i).length;
                // The smallest that fits, and not one so big that a small need would hold it.
                if (len >= n && len <= 4L * n && (best < 0 || len < scratch.get(best).length)) best = i;
            }
            if (best < 0) {
                int[] a = new int[rounded(n)];
                MADE.add(4L * a.length);
                if (TRACE) System.err.printf("PixelPool: made %d ints for %d on %s (kept %s)%n", a.length, n, Thread.currentThread().getName(),
                        scratch.stream().map(x -> String.valueOf(x.length)).toList());
                return a;
            }
            return dropScratch(best);
        }

        synchronized void keep(int[] a) {
            touch();
            long b = 4L * a.length;
            // Within this thread's share and everybody's, making room by dropping its own oldest first.
            while (!scratch.isEmpty() && (scratchBytes + b > SCRATCH_BYTES || KEPT.get() + b > TOTAL_BYTES)) dropScratch(0);
            if (scratchBytes + b > SCRATCH_BYTES || KEPT.get() + b > TOTAL_BYTES) return;
            scratch.add(a);
            scratchBytes += b;
            count(b);
        }

        private int[] dropScratch(int i) {
            int[] a = scratch.remove(i);
            scratchBytes -= 4L * a.length;
            count(-4L * a.length);
            return a;
        }

        synchronized int[] takePicture(int n) {
            touch();
            for (int i = 0; i < pictures.size(); i++) {
                if (pictures.get(i).length == n) {
                    count(-4L * n);
                    return pictures.remove(i);
                }
            }
            MADE.add(4L * n);
            return new int[n];
        }

        synchronized void keepPicture(int[] a) {
            touch();
            long b = 4L * a.length;
            while (!pictures.isEmpty() && (pictures.size() >= PICTURES || KEPT.get() + b > TOTAL_BYTES)) count(-4L * pictures.remove(0).length);
            if (KEPT.get() + b > TOTAL_BYTES) return;
            pictures.add(a);
            count(b);
        }

        synchronized long bytes() {
            long b = scratchBytes;
            for (int[] a : pictures) b += 4L * a.length;
            return b;
        }

        synchronized void clear() {
            count(-bytes());
            scratch.clear();
            pictures.clear();
            scratchBytes = 0;
        }

        synchronized void dropIfIdle(long now) {
            if (now - used > IDLE_NANOS) clear();
        }

        private void touch() {
            used = System.nanoTime();
        }
    }
}
