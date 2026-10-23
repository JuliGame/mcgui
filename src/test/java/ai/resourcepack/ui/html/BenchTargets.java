package ai.resourcepack.ui.html;

import java.nio.file.Path;

/**
 * The hover targets of each page given, as LoadBench and HoverCost find them, in canvas pixels of a 16x9 view
 * (2048 x 1152): one JSON line per page, {@code {"page":..., "targets":[[x, y], ...]}}. {@code bench/bench.py} points
 * a view's pointer at them to time a hover on a server.
 */
public final class BenchTargets {
    public static void main(String[] args) throws Exception {
        Page.Loader loader = new Page.Loader(false, null);
        for (String file : args) {
            LoadBench.Prep prep = new LoadBench.Prep(Page.load(Path.of(file), loader));
            StringBuilder b = new StringBuilder();
            for (int[] t : prep.targets) b.append(b.isEmpty() ? "" : ",").append('[').append(t[0]).append(',').append(t[1]).append(']');
            System.out.println("{\"page\":\"" + Path.of(file).getFileName().toString().replace(".html", "") + "\",\"targets\":[" + b + "]}");
        }
    }
}
