package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.net.MapSender;
import ai.resourcepack.uiframework.render.Palette;
import ai.resourcepack.uiframework.world.MapWall;
import org.bukkit.block.BlockFace;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * What a notch of the wheel costs the server and the connection, offline (`gradlew tileBench`): a long page on a
 * 16x9 view at scale 2, scrolled down and back up a notch at a time, views cut from a ScrollCache as Session does.
 * Three ways: one map row a notch with the canvas moved (frames pointed at the maps the client has, only the new
 * row sent), the same notches drawn whole (every changed map sent again, as before), and the default 80 CSS px
 * notch drawn whole. Per notch: maps and re-points sent, raw and deflated colour bytes, and the process CPU time
 * of the canvas step and the sender passes, summed over the JVM's threads, (the cut itself is the same 2 ms in all three, and left out).
 * -Pruns=<n>, -Pnotches=<n>.
 */
public final class TileBench {
    private static final int COLS = 16, ROWS = 9, W = COLS * 128, H = ROWS * 128;
    private static final double SCALE = 2;
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** CPU time of every Java thread so far, in ns: the canvas converts and encodes on a pool, so one thread's is not enough. */
    private static long cpu() {
        long sum = 0;
        for (long t : THREADS.getThreadCpuTime(THREADS.getAllThreadIds())) if (t > 0) sum += t;
        return sum;
    }

    private record Mode(String name, double step, boolean shift) {}

    private record Notch(long maps, long repoints, long raw, long deflated, long cpuNanos, long canvasNanos) {}

    public static void main(String[] args) throws Exception {
        int runs = Integer.getInteger("runs", 5);
        int notches = Integer.getInteger("notches", 12);
        Page page = Page.load(Path.of(args[0]).toAbsolutePath(), new Page.Loader(false, null));
        Palette palette = new Palette(RenderBench::mapColour);
        List<Mode> modes = List.of(new Mode("row, moved", 128 / SCALE, true), new Mode("row, whole", 128 / SCALE, false),
                new Mode("80 px, whole", 80, false));
        List<List<Notch>> all = new ArrayList<>();
        for (Mode m : modes) all.add(new ArrayList<>());
        for (int run = -2; run < runs; run++) {
            // Take turns going first, so a slow moment of a shared machine does not land on one mode.
            for (int i = 0; i < modes.size(); i++) {
                int k = Math.floorMod(i + run, modes.size());
                List<Notch> out = pass(page, palette, modes.get(k), notches);
                if (run >= 0) all.get(k).addAll(out);
            }
        }
        System.out.printf("%s, %dx%d maps at scale %.0f, %d notches down and up, %d runs%n", args[0], COLS, ROWS, SCALE, notches, runs);
        System.out.printf("%-14s %8s %8s %10s %10s %9s %15s %15s%n", "mode", "maps", "repoints", "raw KB", "deflated KB", "packets", "CPU ms med/p90",
                "canvas part");
        for (int i = 0; i < modes.size(); i++) {
            List<Notch> n = all.get(i);
            long[] cpu = n.stream().mapToLong(Notch::cpuNanos).sorted().toArray();
            long[] canvas = n.stream().mapToLong(Notch::canvasNanos).sorted().toArray();
            System.out.printf("%-14s %8.1f %8.1f %10.1f %10.1f %9.1f %7.2f/%7.2f %7.2f/%7.2f%n", modes.get(i).name,
                    avg(n, Notch::maps), avg(n, Notch::repoints), avg(n, Notch::raw) / 1024, avg(n, Notch::deflated) / 1024,
                    avg(n, x -> x.maps + x.repoints), cpu[cpu.length / 2] / 1e6, cpu[(int) (cpu.length * 0.9)] / 1e6,
                    canvas[canvas.length / 2] / 1e6, canvas[(int) (canvas.length * 0.9)] / 1e6);
        }
    }

    private static double avg(List<Notch> n, java.util.function.ToLongFunction<Notch> f) {
        return n.stream().mapToLong(f).average().orElse(0);
    }

    /** One page opened at the top, [notches] notches down and as many back up; one entry per notch that moved. */
    private static List<Notch> pass(Page page, Palette palette, Mode mode, int notches) {
        Canvas canvas = new Canvas(palette, COLS, ROWS);
        MapWall wall = new MapWall(null, 0, 100, 0, BlockFace.NORTH, COLS, ROWS, 5000);
        RecordingWire client = new RecordingWire(wall);
        client.deflate = true;
        MapSender sender = new MapSender(client, palette, Integer.MAX_VALUE, Logger.getAnonymousLogger());
        sender.update(List.of(new MapSender.Assignment(RecordingWire.player(), wall, canvas, Canvas.Encoding.HI, null)));
        HtmlRenderer.Frame open = HtmlRenderer.render(page, W, H, SCALE, 0, Selector.State.NONE);
        canvas.draw(open.image(), 0, 0);
        settle(sender, client, new long[1]);
        ScrollCache cache = null;
        double at = 0;
        List<Notch> out = new ArrayList<>();
        int[] dirs = new int[2 * notches];
        Arrays.fill(dirs, 0, notches, 1);
        Arrays.fill(dirs, notches, dirs.length, -1);
        for (int dir : dirs) {
            double from = at;
            double to = ScrollCache.snap(Math.max(0, Math.min(open.maxScroll(), at + dir * mode.step)), SCALE);
            if (to == from) continue;
            at = to;
            boolean kept = cache != null && cache.covers(at);
            if (!kept) cache = ScrollCache.paint(page, page.document, W, H, SCALE, at, from, Selector.State.NONE, ScrollCache.VIEWS, 0);
            HtmlRenderer.Frame view = cache.frame(at);
            long maps = client.patches, repoints = client.repoints, raw = client.rawBytes, deflated = client.deflatedBytes;
            long[] cpu = new long[1];
            long c0 = cpu();
            // As Session: only the rows that came in when both views are cut from one picture, else the whole view.
            int rows = mode.shift ? ScrollCache.tileRows(from, view.scrollY(), SCALE, ROWS) : 0;
            if (rows != 0 && kept) {
                int band = Math.abs(rows) * ScrollCache.TILE, y0 = rows > 0 ? H - band : 0;
                canvas.scroll(rows, view.image().getSubimage(0, y0, W, band), 0, y0);
            } else {
                canvas.scroll(rows, view.image(), 0, 0);
            }
            cpu[0] += cpu() - c0;
            long drawn = cpu[0];
            settle(sender, client, cpu);
            out.add(new Notch(client.patches - maps, client.repoints - repoints, client.rawBytes - raw, client.deflatedBytes - deflated, cpu[0], drawn));
        }
        return out;
    }

    /** Passes and deliveries until nothing more goes; the passes' CPU time is added to [cpu]. */
    private static void settle(MapSender sender, RecordingWire client, long[] cpu) {
        int quiet = 0;
        while (quiet < 3) {
            long c0 = cpu();
            sender.pass();
            cpu[0] += cpu() - c0;
            if (client.deliver()) quiet = 0;
            else quiet++;
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }
}
