package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.TickMeter;
import ai.resourcepack.ui.framework.api.Cpu;
import ai.resourcepack.ui.html.HoverAhead;
import ai.resourcepack.ui.html.RenderBudget;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ForkJoinPool;

/**
 * {@code /mcgui stats}: what the plugin costs the server right now. The server thread's share each tick, the pictures
 * drawn (how long each waited for a thread and took), what is on screen, the plugin's threads and how long it took to
 * start. {@code /mcgui stats json} gives the same as one line of JSON, which the bench reads.
 */
final class Stats {
    private Stats() {}

    static List<String> lines(MCGUIPlugin plugin) {
        TickMeter.Summary t = plugin.framework().meter().summary(Bukkit.getCurrentTick());
        int[] c = plugin.screens().counts();
        Renders r = plugin.renderers();
        ForkJoinPool pool = Cpu.pool();
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "%d wall(s) with %d session(s), %d player(s) at a page; %.1f MB of map data sent.",
                c[0], c[1], c[2], plugin.framework().bytesSent() / 1e6));
        out.add(String.format(Locale.ROOT, "Server thread, last %d ticks: %.1f µs a tick (99th percentile %.1f, most %.1f), %.0f bytes allocated a tick.",
                t.ticks(), t.meanMicros(), t.p99Micros(), t.maxMicros(), t.meanBytes()));
        for (Renders.Kind k : Renders.Kind.values()) out.add("Jobs " + k.name().toLowerCase(Locale.ROOT) + ": " + r.times(k) + " (ms: median, 99th, most)");
        out.add("Pictures given up for taking too long: " + RenderBudget.timedOut() + ". Kept first pictures: " + plugin.firstPictures().stats() + ".");
        out.add(String.format(Locale.ROOT, "Hovers: %s; drawing ahead used %.1f s of CPU.", plugin.repaints().stats(), HoverAhead.spentNs() / 1e9));
        out.add(String.format(Locale.ROOT, "Threads: %s; cpu-threads %d (%d running, %d queued).", threads(), pool.getParallelism(),
                pool.getRunningThreadCount(), pool.getQueuedSubmissionCount() + pool.getQueuedTaskCount()));
        out.add(String.format(Locale.ROOT, "Started in %.0f ms on the server thread; warmed up %.0f ms after.", plugin.enableMillis(), plugin.warmMillis()));
        return out;
    }

    static String json(MCGUIPlugin plugin) {
        TickMeter.Summary t = plugin.framework().meter().summary(Bukkit.getCurrentTick());
        int[] c = plugin.screens().counts();
        Renders r = plugin.renderers();
        StringBuilder jobs = new StringBuilder();
        for (Renders.Kind k : Renders.Kind.values()) {
            jobs.append(jobs.isEmpty() ? "" : ",").append('"').append(k.name().toLowerCase(Locale.ROOT)).append("\":").append(r.times(k));
        }
        StringBuilder threads = new StringBuilder();
        for (Map.Entry<String, Integer> e : threadCounts().entrySet()) {
            threads.append(threads.isEmpty() ? "" : ",").append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        return String.format(Locale.ROOT, "{\"walls\":%d,\"wall_sessions\":%d,\"views\":%d,\"sent_mb\":%.3f,\"tick\":%s,\"jobs\":{%s},"
                        + "\"timed_out\":%d,\"hovers\":%s,\"ahead_cpu_s\":%.3f,\"threads\":{%s},\"enable_ms\":%.1f,\"warm_ms\":%.1f}",
                c[0], c[1], c[2], plugin.framework().bytesSent() / 1e6, t.json(), jobs, RenderBudget.timedOut(), plugin.repaints().json(),
                HoverAhead.spentNs() / 1e9, threads, plugin.enableMillis(), plugin.warmMillis());
    }

    /** The plugin's live threads by name, numbers left out: name -> how many. */
    private static Map<String, Integer> threadCounts() {
        Map<String, Integer> out = new TreeMap<>();
        for (Thread th : Thread.getAllStackTraces().keySet()) {
            String name = th.getName();
            if (name.startsWith("MCGUI")) out.merge(name.replaceAll("-?\\d+$", ""), 1, Integer::sum);
        }
        return out;
    }

    private static String threads() {
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, Integer> e : threadCounts().entrySet()) s.append(s.isEmpty() ? "" : ", ").append(e.getKey()).append(" x").append(e.getValue());
        return s.toString();
    }
}
