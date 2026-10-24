package ai.resourcepack.ui;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * What a new viewer of a page costs, with real {@link Session}s ({@link SessionRig}): the server thread's CPU time and
 * allocation to make the session and show its first picture (taken from a kept one, or drawn when none is kept), and the
 * live heap each session that only watches holds once it is showing its page.
 *
 * <p>System properties: pages (names in the shipped pages folder, default shop,rules), sizes (default 4x3,16x9),
 * opens (new viewers measured per cell, default 300), warm (new viewers before those, default 300), sessions (default 1,16,64), mode (open, heap or both).
 */
public final class SessionBench {
    private record Size(String name, int cols, int rows, double height) {}

    public static void main(String[] args) throws Exception {
        Path shipped = Path.of(System.getProperty("shipped", "src/main/resources/pages"));
        String[] pages = System.getProperty("pages", "shop,rules").split(",");
        List<Size> sizes = new ArrayList<>();
        for (String s : System.getProperty("sizes", "4x3,16x9").split(",")) {
            String[] p = s.split("x");
            int c = Integer.parseInt(p[0]), r = Integer.parseInt(p[1]);
            // A wall's design height is 128 CSS pixels a map row; a fullscreen view's is 576.
            sizes.add(new Size(s, c, r, c * r >= 100 ? 576 : r * 128));
        }
        int opens = Integer.getInteger("opens", 300);
        int warm = Integer.getInteger("warm", 300);
        int[] counts = Arrays.stream(System.getProperty("sessions", "1,16,64").split(",")).mapToInt(Integer::parseInt).toArray();
        String mode = System.getProperty("mode", "both");
        Path dir = Files.createTempDirectory("session-bench");
        // The whole folder: pages read their stylesheets from it.
        try (var files = Files.list(shipped)) {
            for (Path f : (Iterable<Path>) files::iterator) if (Files.isRegularFile(f)) Files.copy(f, dir.resolve(f.getFileName()));
        }
        if (!mode.equals("heap")) {
            System.out.printf("%-8s %-6s %-6s %10s %10s %10s %12s %12s%n", "page", "size", "path", "cpu p50 ms", "cpu p90 ms", "cpu mean ms",
                    "alloc p50 KB", "alloc p90 KB");
            for (String page : pages) for (Size size : sizes) {
                open(dir, page, size, warm, opens, true);
                open(dir, page, size, Math.max(10, warm / 10), Math.max(20, opens / 10), false);
            }
        }
        if (!mode.equals("open")) {
            System.out.printf("%-8s %-6s %5s %14s %14s %14s%n", "page", "size", "N", "live MB, 1", "live MB, 1+N", "KB a session");
            for (String page : pages) for (Size size : sizes) for (int n : counts) heap(dir, page, size, n);
        }
    }

    /**
     * [opens] new viewers of [page], one at a time: the server thread's CPU time and allocation for each, from making
     * its session to the last thing it handed the server thread before its first picture was on the canvas. [take]:
     * the page's first picture is kept, as for every viewer after the first; else every viewer draws it.
     */
    private static void open(Path dir, String page, Size size, int warm, int opens, boolean take) throws Exception {
        try (SessionRig rig = new SessionRig(dir, 2)) {
            rig.pages.load(page).get(30, TimeUnit.SECONDS);
            MCGUIPlugin plugin = rig.plugin(take ? 64 : 0);
            List<Session> live = new ArrayList<>();
            // The first viewer draws (and, with take, keeps) the picture; [warm] more warm the code up.
            for (int i = 0; i <= warm; i++) {
                if (i % 32 == 31) {
                    for (Session s : live) rig.onServer(s::dispose);
                    live.clear();
                }
                live.add(rig.open(plugin, page, size.cols, size.rows, size.height));
                rig.quiet();
            }
            double[] cpu = new double[opens], kb = new double[opens];
            for (int i = 0; i < opens; i++) {
                if (i % 32 == 0) {
                    // Only so many alive at once: a full screen that draws holds a picture of its own.
                    for (Session s : live) rig.onServer(s::dispose);
                    live.clear();
                    System.gc();
                }
                long c0 = rig.serverCpu.get(), b0 = rig.serverBytes.get();
                live.add(rig.open(plugin, page, size.cols, size.rows, size.height));
                rig.quiet();
                cpu[i] = (rig.serverCpu.get() - c0) / 1e6;
                kb[i] = (rig.serverBytes.get() - b0) / 1024.0;
            }
            Arrays.sort(cpu);
            Arrays.sort(kb);
            System.out.printf("%-8s %-6s %-6s %10.3f %10.3f %10.3f %12.1f %12.1f%n", page, size.name, take ? "take" : "draw", cpu[opens / 2],
                    cpu[opens * 9 / 10], Arrays.stream(cpu).average().orElse(0), kb[opens / 2], kb[opens * 9 / 10]);
        }
    }

    /** Live heap after a full collection with one session on [page] (the one that drew it), then with [n] more that took it. */
    private static void heap(Path dir, String page, Size size, int n) throws Exception {
        try (SessionRig rig = new SessionRig(dir, 2)) {
            rig.pages.load(page).get(30, TimeUnit.SECONDS);
            MCGUIPlugin plugin = rig.plugin(64);
            List<Session> live = new ArrayList<>();
            live.add(rig.open(plugin, page, size.cols, size.rows, size.height));
            rig.quiet();
            long one = liveHeap();
            for (int i = 0; i < n; i++) {
                live.add(rig.open(plugin, page, size.cols, size.rows, size.height));
                rig.quiet();
            }
            long all = liveHeap();
            System.out.printf("%-8s %-6s %5d %14.1f %14.1f %14.1f%n", page, size.name, n, one / 1048576.0, all / 1048576.0, (all - one) / 1024.0 / n);
            // Kept alive until measured.
            if (live.size() != n + 1) throw new IllegalStateException();
        }
    }

    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();

    private static long liveHeap() throws InterruptedException {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            System.gc();
            Thread.sleep(50);
            best = Math.min(best, MEMORY.getHeapMemoryUsage().getUsed());
        }
        return best;
    }
}
