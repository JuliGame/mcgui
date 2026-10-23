package ai.resourcepack.arcade;

import ai.resourcepack.arcade.scene.Host;
import ai.resourcepack.arcade.scene.Key;
import ai.resourcepack.arcade.scene.Scene;
import ai.resourcepack.arcade.scene.Seat;
import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.render.Palette;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.zip.Deflater;

/**
 * What one frame of each game costs, on one machine with no server: CPU of the game's tick, of its drawing and of
 * {@code Canvas.draw} (conversion and encoding, which spreads over the pool), the tiles each frame changes, and the
 * map bytes a viewer is sent for them (raw, as the sender's budget counts them, and deflated as the network sends
 * them). A bot plays: the pointer sweeps, keys go down and up, clicks every so often.
 *
 * <p>Run: {@code java -cp <test classpath> ai.resourcepack.arcade.ArcadeBench [game ...]} ({@code -Dframes=300},
 * {@code -Dwarm=90}, {@code -Dseated=false} for attract mode).
 */
public final class ArcadeBench {
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    public static void main(String[] args) {
        int frames = Integer.getInteger("frames", 300), warm = Integer.getInteger("warm", 90);
        boolean seated = Boolean.parseBoolean(System.getProperty("seated", "true"));
        List<String> names = new ArrayList<>(args.length > 0 ? Arrays.asList(args) : ArcadePlugin.gameNames());
        Palette palette = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));
        System.out.println("game        fps | tick ms  draw ms  canvas ms (cpu)  frame ms p50/p99 | tiles/frame  raw KB/frame  zip KB/frame | budget fps | changed frames");
        for (String name : names) {
            Result r = run(name, palette, frames, warm, seated);
            System.out.println(r);
        }
        System.exit(0);
    }

    record Result(String name, int fps, double tick, double draw, double canvasWall, double canvasCpu, double p50, double p99,
                  double tiles, double rawKb, double zipKb, int changed, int frames) {
        @Override
        public String toString() {
            double budgetFps = rawKb <= 0 ? 999 : 16_000_000 / (rawKb * 1024);
            return String.format(Locale.ROOT, "%-10s %4d | %7.2f  %7.2f  %6.2f (%6.2f)  %6.2f / %6.2f | %7.1f  %10.0f  %10.1f | %8.1f | %d/%d",
                    name, fps, tick, draw, canvasWall, canvasCpu, p50, p99, tiles, rawKb, zipKb, Math.min(budgetFps, fps), changed, frames);
        }
    }

    /** Plays [name] for [warm] frames, then measures [frames] more. */
    static Result run(String name, Palette palette, int frames, int warm, boolean seated) {
        Bot bot = new Bot(name);
        if (name.equals("breakout")) return breakout(palette, frames, warm, seated, bot);
        Scene scene = Games.ALL.get(name).get();
        Canvas canvas = new Canvas(palette, scene.cols(), scene.rows());
        BufferedImage frame = new BufferedImage(canvas.width(), canvas.height(), BufferedImage.TYPE_INT_RGB);
        Seat seat = new Seat(UUID.randomUUID(), "Bench", 0);
        List<Seat> seats = seated ? List.of(seat) : List.of();
        scene.start(new Host() {
            @Override public int width() { return canvas.width(); }
            @Override public int height() { return canvas.height(); }
            @Override public List<Seat> seats() { return seats; }
            @Override public void sound(String sound, float volume, float pitch) {}
            @Override public int score(Seat s, int score) { return 0; }
            @Override public List<Score> scores() { return List.of(); }
        });
        if (seated) scene.joined(seat);
        int fps = Math.max(1, Math.min(30, scene.fps()));
        double dt = 1.0 / fps;
        Meter m = new Meter(canvas);
        for (int f = 0; f < warm + frames; f++) {
            boolean measure = f >= warm;
            long c0 = THREADS.getCurrentThreadCpuTime(), w0 = System.nanoTime();
            if (seated) bot.play(scene, seat, f * dt, canvas.width(), canvas.height());
            scene.tick(dt);
            long c1 = THREADS.getCurrentThreadCpuTime();
            Graphics2D g = frame.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            scene.draw(g);
            g.dispose();
            long c2 = THREADS.getCurrentThreadCpuTime(), p2 = OS.getProcessCpuTime(), w2 = System.nanoTime();
            canvas.draw(frame, 0, 0);
            long w3 = System.nanoTime(), p3 = OS.getProcessCpuTime();
            if (measure) m.frame((c1 - c0) / 1e6, (c2 - c1) / 1e6, (w3 - w2) / 1e6, (p3 - p2) / 1e6, (w3 - w0) / 1e6);
            else m.sync();
        }
        return m.result(name, fps);
    }

    /** Neon Breakout is its own Game, which hands the canvas only what moved. */
    static Result breakout(Palette palette, int frames, int warm, boolean seated, Bot bot) {
        Canvas canvas = new Canvas(palette, 16, 9);
        Game game = new Game(canvas, new Game.Host() {
            @Override public void sound(Game.Sfx sfx, float pitch) {}
            @Override public int finished(String player, int score) { return 0; }
            @Override public List<Scores.Entry> top() { return List.of(); }
        });
        if (seated) game.seat("Bench");
        double dt = 1 / 30.0;
        Meter m = new Meter(canvas);
        for (int f = 0; f < warm + frames; f++) {
            long c0 = THREADS.getCurrentThreadCpuTime(), p0 = OS.getProcessCpuTime(), w0 = System.nanoTime();
            if (seated) {
                double t = f * dt;
                game.aim = 512 + 400 * Math.sin(t * 1.3);
                if (f % 45 == 0) game.press();
            }
            game.tick(dt);
            long c1 = THREADS.getCurrentThreadCpuTime(), p1 = OS.getProcessCpuTime(), w1 = System.nanoTime();
            // Its own canvas draws happen inside tick: the pool's share is the process CPU beyond this thread's.
            if (f >= warm) m.frame((c1 - c0) / 1e6, 0, 0, Math.max(0, (p1 - p0) - (c1 - c0)) / 1e6, (w1 - w0) / 1e6);
            else m.sync();
        }
        return m.result("breakout", 30);
    }

    /** Per-frame numbers, and the bytes a viewer who saw every frame is sent. */
    static final class Meter {
        final Canvas canvas;
        final byte[][] had;
        final List<double[]> rows = new ArrayList<>();
        final Deflater deflater = new Deflater(6);
        final byte[] zbuf = new byte[1 << 16];
        int changed;

        Meter(Canvas canvas) {
            this.canvas = canvas;
            had = new byte[canvas.tiles()][];
        }

        void sync() {
            Canvas.Snapshot s = canvas.snapshot();
            for (int t = 0; t < had.length; t++) had[t] = s.tile(t, Canvas.Encoding.HI);
        }

        void frame(double tick, double draw, double canvasWall, double canvasCpu, double wall) {
            Canvas.Snapshot s = canvas.snapshot();
            int tiles = 0;
            long raw = 0, zip = 0;
            for (int t = 0; t < had.length; t++) {
                byte[] now = s.tile(t, Canvas.Encoding.HI);
                if (now == had[t]) continue;
                int[] rect = diff(had[t], now);
                had[t] = now;
                if (rect == null) continue;
                tiles++;
                raw += (long) rect[2] * rect[3];
                byte[] part = new byte[rect[2] * rect[3]];
                for (int y = 0; y < rect[3]; y++) System.arraycopy(now, (rect[1] + y) * 128 + rect[0], part, y * rect[2], rect[2]);
                deflater.reset();
                deflater.setInput(part);
                deflater.finish();
                while (!deflater.finished()) zip += deflater.deflate(zbuf);
            }
            if (tiles > 0) changed++;
            rows.add(new double[]{tick, draw, canvasWall, canvasCpu, wall, tiles, raw, zip});
        }

        Result result(String name, int fps) {
            int n = rows.size();
            double[] sum = new double[8];
            double[] wall = new double[n];
            for (int i = 0; i < n; i++) {
                double[] r = rows.get(i);
                for (int k = 0; k < 8; k++) sum[k] += r[k];
                wall[i] = r[4];
            }
            Arrays.sort(wall);
            return new Result(name, fps, sum[0] / n, sum[1] / n, sum[2] / n, sum[3] / n, wall[n / 2], wall[Math.min(n - 1, (int) (n * 0.99))],
                    sum[5] / n, sum[6] / n / 1024, sum[7] / n / 1024, changed, n);
        }
    }

    /** As MapSender's: the smallest rectangle {x, y, w, h} covering every byte that differs, or null. */
    static int[] diff(byte[] previous, byte[] current) {
        if (previous == null) return new int[]{0, 0, 128, 128};
        int minX = 128, minY = 128, maxX = -1, maxY = -1;
        for (int y = 0; y < 128; y++) {
            int row = y * 128;
            int first = Arrays.mismatch(previous, row, row + 128, current, row, row + 128);
            if (first < 0) continue;
            int last = first;
            for (int x = 127; x > first; x--) if (previous[row + x] != current[row + x]) { last = x; break; }
            minX = Math.min(minX, first);
            maxX = Math.max(maxX, last);
            if (minY == 128) minY = y;
            maxY = y;
        }
        return maxX < 0 ? null : new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
    }

    /** A player who never stops: the pointer sweeps, a key goes down for a moment every so often, and clicks. */
    static final class Bot {
        final String game;
        final Random random = new Random(7);
        Key held;
        double heldUntil, nextKey, nextClick;
        boolean throttle;

        Bot(String game) {
            this.game = game;
        }

        void play(Scene scene, Seat seat, double t, int w, int h) {
            scene.pointer(seat, (int) (w / 2.0 + w * 0.4 * Math.sin(t * 0.9)), (int) (h / 2.0 + h * 0.3 * Math.sin(t * 1.4)));
            if (!throttle && game.equals("racer")) {
                scene.key(seat, Key.UP, true);
                throttle = true;
            }
            if (held != null && t >= heldUntil) {
                scene.key(seat, held, false);
                held = null;
            }
            if (held == null && t >= nextKey) {
                Key[] keys = game.equals("racer") ? new Key[]{Key.LEFT, Key.RIGHT} : new Key[]{Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT, Key.JUMP};
                held = keys[random.nextInt(keys.length)];
                scene.key(seat, held, true);
                heldUntil = t + 0.15 + random.nextDouble() * 0.3;
                nextKey = heldUntil + 0.1 + random.nextDouble() * 0.4;
            }
            if (t >= nextClick) {
                scene.click(seat, (int) (w / 2.0 + w * 0.4 * Math.sin(t * 0.9)), (int) (h / 2.0 + h * 0.3 * Math.sin(t * 1.4)), random.nextInt(5) == 0);
                nextClick = t + 0.5 + random.nextDouble();
            }
        }
    }
}
