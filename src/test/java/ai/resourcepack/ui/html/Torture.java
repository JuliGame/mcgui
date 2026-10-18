package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Renders hostile pages and reports how each went: load and render times, peak heap, and the exception
 * or the timeout, with the frames it came from. Each page is loaded and drawn on its own thread (default
 * stack size, as the plugin's render threads have) at 1024 x 576 CSS px drawn at 2x, then drawn again
 * scrolled half way down. A page that times out or runs the heap out leaves a thread that cannot be
 * stopped, so pages run in a child JVM that is replaced after one; a child that dies is reported as a crash.
 *
 * <p>Sources: a folder (every file in it), {@code cases} (the generated pathological pages, written to
 * {@code build/torture/cases}), or {@code fuzz:<count>:<seed>} (generated random pages, see {@link HtmlFuzz}).
 * Options: {@code -Dtimeout=<s>} (30), {@code -Dheap=<size>} (2g), {@code -Donly=<substring>},
 * {@code -Dout=<tsv>}. Run with {@code ./gradlew torture -Psrc=cases}.
 */
public final class Torture {
    private static final int W = 2048, H = 1152;
    private static final double SCALE = 2;
    static final Path BUILD = Path.of("build/torture");

    public static void main(String[] args) throws Exception {
        int timeout = Integer.getInteger("timeout", 30);
        String heap = System.getProperty("heap", "2g"), only = System.getProperty("only", "");
        Path out = Path.of(System.getProperty("out", BUILD.resolve("results.tsv").toString()));
        List<String> items = new ArrayList<>();
        for (String a : args) {
            if (a.equals("cases")) {
                Path dir = BUILD.resolve("cases");
                TortureCases.write(dir);
                items.addAll(files(dir));
            } else if (a.startsWith("fuzz:")) {
                String[] p = a.split(":");
                for (int i = 0; i < Integer.parseInt(p[1]); i++) items.add("fuzz:" + p[2] + ":" + i);
            } else {
                items.addAll(files(Path.of(a)));
            }
        }
        if (!only.isEmpty()) items.removeIf(s -> !s.contains(only));
        Files.createDirectories(BUILD);
        Path list = BUILD.resolve("items.txt");
        Files.write(list, items);
        Files.createDirectories(out.toAbsolutePath().getParent());
        List<String[]> results = new ArrayList<>();
        try (PrintStream tsv = new PrintStream(Files.newOutputStream(out), true, StandardCharsets.UTF_8)) {
            tsv.println("page\tbytes\tload_ms\tfull_ms\tscroll_ms\tpage_height\tpeak_mb\toutcome\tdetail");
            int next = 0;
            while (next < items.size()) {
                next = runChild(list, next, timeout, heap, items, results, tsv);
            }
        }
        summary(results);
    }

    /** Runs a child JVM from item [from] until it finishes or gives up; returns the next item to run. */
    private static int runChild(Path list, int from, int timeout, String heap, List<String> items, List<String[]> results, PrintStream tsv)
            throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-Xmx" + heap, "-Djava.awt.headless=true", "-Dtimeout=" + timeout,
                "-cp", System.getProperty("java.class.path"), Child.class.getName(), list.toString(), Integer.toString(from))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) lines.add(l);
            } catch (IOException ignored) {
            }
            lines.add("\u0000EOF");
        });
        reader.setDaemon(true);
        reader.start();
        int next = from;
        while (true) {
            // A child always answers within its timeout plus a scrolled render's; past that it is stuck in GC or worse.
            String l = lines.poll(timeout * 3L + 60, TimeUnit.SECONDS);
            if (l == null) {
                p.destroyForcibly();
                record(results, tsv, new String[]{items.get(next), "", "", "", "", "", "", "CRASH", "child unresponsive, killed"});
                return next + 1;
            }
            if (l.equals("\u0000EOF")) break;
            if (!l.startsWith("R\t")) continue;
            String[] f = l.substring(2).split("\t", -1);
            int index = Integer.parseInt(f[0]);
            String[] row = new String[f.length - 1];
            System.arraycopy(f, 1, row, 0, row.length);
            record(results, tsv, row);
            next = index + 1;
        }
        int code = p.waitFor();
        if (next < items.size() && code != 3 && code != 4) {
            record(results, tsv, new String[]{items.get(next), "", "", "", "", "", "", "CRASH", "child exited " + code});
            return next + 1;
        }
        return next;
    }

    private static void record(List<String[]> results, PrintStream tsv, String[] row) throws IOException {
        results.add(row);
        tsv.println(String.join("\t", row));
        System.out.printf("%-44s %9s B  load %7s  full %7s  scroll %7s  h %12s  heap %6s MB  %s %s%n", (Object[]) row);
        if (row[0].startsWith("fuzz:") && flagged(row)) {
            String[] p = row[0].split(":");
            Path dir = BUILD.resolve("fuzz-flagged");
            Files.createDirectories(dir);
            Files.write(dir.resolve(p[1] + "-" + p[2] + ".html"), HtmlFuzz.page(Long.parseLong(p[1]), Integer.parseInt(p[2])).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A page that threw, timed out, crashed, took over a second for either render or peaked over 512 MB of heap. */
    private static boolean flagged(String[] row) {
        return !row[7].equals("ok") || ms(row[3]) > 1000 || ms(row[4]) > 1000 || ms(row[6]) > 512;
    }

    private static double ms(String s) {
        return s.isEmpty() ? 0 : Double.parseDouble(s);
    }

    private static void summary(List<String[]> results) {
        Map<String, Integer> kinds = new TreeMap<>();
        int slow = 0, big = 0;
        for (String[] r : results) {
            String kind = r[7].equals("ok") ? "ok" : r[7] + " " + r[8].split(" ", 2)[0];
            if (r[7].equals("EXCEPTION")) kind = "EXCEPTION " + r[8].split(" @ ")[0].split(":")[0] + " @ " + topFrame(r[8]);
            kinds.merge(kind, 1, Integer::sum);
            if (ms(r[3]) > 1000 || ms(r[4]) > 1000) slow++;
            if (ms(r[6]) > 512) big++;
        }
        System.out.println();
        System.out.printf("pages %d, over 1 s %d, over 512 MB %d%n", results.size(), slow, big);
        kinds.forEach((k, n) -> System.out.printf("%6d  %s%n", n, k));
    }

    private static String topFrame(String detail) {
        String[] parts = detail.split(" @ ");
        return parts.length > 1 ? parts[1].split(" < ")[0] : "";
    }

    private static List<String> files(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of(dir.toString());
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).filter(f -> !f.toString().endsWith(".css") && !f.toString().endsWith(".ttf"))
                    .sorted().map(Path::toString).toList();
        }
    }

    /** Renders items from one index on, one per line of output, and exits 3 after a timeout and 4 after running out of heap. */
    public static final class Child {
        public static void main(String[] args) throws Exception {
            List<String> items = Files.readAllLines(Path.of(args[0]));
            int from = Integer.parseInt(args[1]);
            int timeout = Integer.getInteger("timeout", 30);
            PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
            // Pages print their problems; keep stdout for results.
            System.setOut(new PrintStream(PrintStream.nullOutputStream()));
            Page.Loader loader = new Page.Loader(false, BUILD.resolve("cache"));
            for (int i = from; i < items.size(); i++) {
                Run run = new Run(items.get(i), loader);
                System.gc();
                List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans().stream().filter(m -> m.getType() == MemoryType.HEAP).toList();
                long base = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                pools.forEach(MemoryPoolMXBean::resetPeakUsage);
                Thread t = new Thread(null, run, "torture-render", 0);
                t.setDaemon(true);
                t.start();
                t.join(TimeUnit.SECONDS.toMillis(timeout));
                long peak = 0;
                for (MemoryPoolMXBean m : pools) peak += m.getPeakUsage().getUsed();
                String outcome, detail;
                if (t.isAlive()) {
                    outcome = "TIMEOUT";
                    detail = "in " + run.stage + " @ " + frames(t.getStackTrace()) + painters();
                } else if (run.error != null) {
                    outcome = run.error instanceof OutOfMemoryError ? "OOM" : "EXCEPTION";
                    String msg = (run.error.getClass().getSimpleName() + ": " + run.error.getMessage()).replaceAll("\\s+", " ");
                    detail = msg.substring(0, Math.min(160, msg.length())) + " in " + run.stage + " @ " + frames(run.error.getStackTrace());
                } else {
                    outcome = "ok";
                    detail = run.problems;
                }
                out.println(String.join("\t", "R", Integer.toString(i), items.get(i), Long.toString(run.bytes), ms(run.loadNs), ms(run.fullNs),
                        ms(run.scrollNs), run.height < 0 ? "" : run.height < 1e7 || !Double.isFinite(run.height) ? String.format("%.0f", run.height) : String.format("%.3g", run.height), Long.toString(Math.max(0, peak - base) >> 20),
                        outcome, detail.replace('\t', ' ')));
                if (outcome.equals("TIMEOUT")) System.exit(3);
                if (outcome.equals("OOM")) System.exit(4);
            }
            System.exit(0);
        }

        /** Where the band painters are, when a render is stuck waiting for them. */
        private static String painters() {
            StringBuilder b = new StringBuilder();
            for (var e : Thread.getAllStackTraces().entrySet()) {
                if (!e.getKey().getName().startsWith("ForkJoinPool")) continue;
                StackTraceElement[] st = e.getValue();
                if (java.util.Arrays.stream(st).noneMatch(f -> f.getClassName().startsWith("ai.resourcepack"))) continue;
                b.append(" | band @ ").append(frames(st));
                break;
            }
            return b.toString();
        }

        private static String ms(long ns) {
            return ns < 0 ? "" : String.format("%.0f", ns / 1e6);
        }

        /** The distinct frames nearest the top (a stack overflow repeats a few), the first five, innermost first. */
        static String frames(StackTraceElement[] stack) {
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (StackTraceElement e : stack) {
                if (seen.size() == 5) break;
                seen.add(e.getClassName().replaceFirst(".*\\.", "") + "." + e.getMethodName() + ":" + e.getLineNumber());
            }
            return String.join(" < ", seen);
        }
    }

    /** One page: load, full render, scrolled render; what happened is read once the thread ends (or not). */
    private static final class Run implements Runnable {
        final String item;
        final Page.Loader loader;
        volatile String stage = "read";
        volatile long bytes = -1, loadNs = -1, fullNs = -1, scrollNs = -1;
        volatile double height = -1;
        volatile Throwable error;
        volatile String problems = "";

        Run(String item, Page.Loader loader) {
            this.item = item;
            this.loader = loader;
        }

        @Override
        public void run() {
            try {
                long t0 = System.nanoTime();
                Page page;
                if (item.startsWith("fuzz:")) {
                    String[] p = item.split(":");
                    String html = HtmlFuzz.page(Long.parseLong(p[1]), Integer.parseInt(p[2]));
                    bytes = html.length();
                    stage = "load";
                    page = Page.of(html, BUILD.resolve("fuzz.html").toAbsolutePath(), loader);
                } else {
                    Path file = Path.of(item).toAbsolutePath();
                    bytes = Files.size(file);
                    stage = "load";
                    page = Page.load(file, loader);
                }
                long t1 = System.nanoTime();
                loadNs = t1 - t0;
                problems = page.problems.size() + " problems";
                stage = "full";
                HtmlRenderer.Frame f = HtmlRenderer.render(page, W, H, SCALE, 0, Selector.State.NONE);
                long t2 = System.nanoTime();
                fullNs = t2 - t1;
                height = f.pageHeight();
                stage = "scroll";
                HtmlRenderer.render(page, W, H, SCALE, f.maxScroll() / 2, Selector.State.NONE);
                scrollNs = System.nanoTime() - t2;
                stage = "done";
            } catch (Throwable e) {
                error = e;
            }
        }
    }
}
