package ai.resourcepack.arcade;

import ai.resourcepack.arcade.scene.Host;
import ai.resourcepack.arcade.scene.Key;
import ai.resourcepack.arcade.scene.Scene;
import ai.resourcepack.arcade.scene.Seat;
import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.api.Click;
import ai.resourcepack.ui.framework.api.CloseReason;
import ai.resourcepack.ui.framework.api.View;
import ai.resourcepack.ui.framework.api.ViewContent;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Runs one {@link Scene} on the arcade's loop: hands it its players' input, ticks it at its own frame rate,
 * and puts what it draws on its canvas. Only the tiles that differ from the frame before are handed to the
 * canvas, and only what changed in them is sent, so a scene can simply draw the whole frame every time.
 */
final class SceneRunner implements Loop.Ticker, Host {
    /** What the plugin does for a runner, on the server thread. */
    interface Room {
        void sound(SceneRunner runner, String sound, float volume, float pitch);

        int score(SceneRunner runner, Seat seat, int score);

        List<Host.Score> scores(SceneRunner runner);

        /** The last player got up from a runner nobody else can see (a game of one's own). */
        void empty(SceneRunner runner);
    }

    final String id;
    final Scene scene;
    final Canvas canvas;
    /** Shown on a cabinet: kept running when nobody plays. */
    final boolean cabinet;
    private final Room room;
    /**
     * Frames are drawn into these by turns: what the one drawn now differs from the one before is what goes to the
     * canvas, tile by tile. Each frame is painted whole, so the other holds the last frame, which the canvas shows.
     */
    private final BufferedImage[] frames = new BufferedImage[2];
    private final int[][] pixels = new int[2][];
    private int drawing;
    /** Whether the canvas has been handed a whole frame yet. */
    private boolean handed;
    private final ConcurrentLinkedQueue<Input> input = new ConcurrentLinkedQueue<>();
    final FrameLog log = new FrameLog();
    private final List<Seat> seats = new CopyOnWriteArrayList<>();
    /** When the next frame is due and the last was made (System.nanoTime); 0 before the first. */
    private long next, last;
    private int sounds;

    SceneRunner(String id, Scene scene, Canvas canvas, boolean cabinet, Room room) {
        this.id = id;
        this.scene = scene;
        this.canvas = canvas;
        this.cabinet = cabinet;
        this.room = room;
        for (int i = 0; i < 2; i++) {
            frames[i] = new BufferedImage(canvas.width(), canvas.height(), BufferedImage.TYPE_INT_RGB);
            pixels[i] = ((DataBufferInt) frames[i].getRaster().getDataBuffer()).getData();
        }
        scene.start(this);
    }

    /** Server thread: sits [name] down, or null when every seat is taken. */
    synchronized Seat sit(UUID player, String name) {
        for (Seat s : seats) if (s.id().equals(player)) return s;
        if (seats.size() >= scene.maxPlayers()) return null;
        int index = 0;
        while (taken(index)) index++;
        Seat seat = new Seat(player, name, index);
        List<Seat> sorted = new ArrayList<>(seats);
        sorted.add(seat);
        sorted.sort((a, b) -> Integer.compare(a.index(), b.index()));
        seats.clear();
        seats.addAll(sorted);
        input.add(new Input(System.nanoTime(), 'j', s -> s.joined(seat)));
        return seat;
    }

    private boolean taken(int index) {
        for (Seat s : seats) if (s.index() == index) return true;
        return false;
    }

    synchronized void leave(Seat seat) {
        seats.remove(seat);
        input.add(new Input(System.nanoTime(), 'l', s -> s.left(seat)));
        if (seats.isEmpty() && !cabinet) room.empty(this);
    }

    List<Seat> seated() {
        return List.copyOf(seats);
    }

    /** Whether anybody is playing: from any thread, nothing made. */
    boolean anySeated() {
        return !seats.isEmpty();
    }

    @Override
    public void tick(double dt) {
        double elapsed = due(System.nanoTime());
        if (elapsed >= 0) frame(elapsed);
    }

    /**
     * Whether a frame is due at [now] (System.nanoTime) for a loop run then: the seconds since the last frame if so,
     * else -1. Frames fall on a grid of 1/fps, give or take half a run of the loop. The loop's runs jitter, and one
     * that came a hair early used to skip its frame (and make the next one twice as late), which held 30 fps
     * games near 20 with frames 33 and 67 ms apart.
     */
    double due(long now) {
        long step = 1_000_000_000L / Math.max(1, Math.min(30, scene.fps()));
        if (last != 0 && now < next - Loop.PERIOD_NANOS / 2) return -1;
        double elapsed = last == 0 ? step / 1e9 : Math.min(0.1, (now - last) / 1e9);
        last = now;
        // After a late run, on from now: never two frames back to back to catch up.
        next = Math.max(next + step, now + step - Loop.PERIOD_NANOS / 4);
        return elapsed;
    }

    /** The frame the canvas was last handed (its pixels, not a copy): for tests. */
    BufferedImage shown() {
        return frames[drawing ^ 1];
    }

    /** One frame, [elapsed] seconds after the last: the input since, the scene's tick, its drawing, the canvas. */
    void frame(double elapsed) {
        sounds = 0;
        log.begin();
        for (Input e; (e = input.poll()) != null; ) {
            log.input(e.at, e.kind);
            e.action.accept(scene);
        }
        scene.tick(elapsed);
        log.ticked();
        BufferedImage frame = frames[drawing];
        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        try {
            scene.draw(g);
        } finally {
            g.dispose();
        }
        log.drawn();
        long at = System.nanoTime();
        Canvas.Snapshot before = canvas.snapshot();
        // Only the tiles that differ from the last frame: converting a pixel to map colours costs whether or not it changed.
        List<Rectangle> changed = handed ? changed(pixels[drawing], pixels[drawing ^ 1]) : null;
        if (changed == null) canvas.draw(frame, 0, 0);
        else canvas.draw(frame, 0, 0, changed);
        log.shown(System.nanoTime() - at, FrameLog.changed(canvas, before));
        handed = true;
        drawing ^= 1;
    }

    /** The 128-pixel tiles where [now] differs from [before], as runs of tiles along each row of them. */
    private List<Rectangle> changed(int[] now, int[] before) {
        int w = canvas.width(), cols = canvas.cols();
        List<Rectangle> out = new ArrayList<>();
        boolean[] dirty = new boolean[cols];
        for (int top = 0; top < canvas.height(); top += 128) {
            Arrays.fill(dirty, false);
            int found = 0;
            for (int y = top; y < top + 128 && found < cols; y++) {
                int row = y * w;
                for (int c = 0; c < cols; c++) {
                    if (dirty[c]) continue;
                    int from = row + c * 128;
                    if (!Arrays.equals(now, from, from + 128, before, from, from + 128)) {
                        dirty[c] = true;
                        found++;
                    }
                }
            }
            for (int c = 0; c < cols; ) {
                if (!dirty[c]) {
                    c++;
                    continue;
                }
                int start = c;
                while (c < cols && dirty[c]) c++;
                out.add(new Rectangle(start * 128, top, (c - start) * 128, 128));
            }
        }
        return out;
    }

    /** Something a player did, for the scene's next frame: when it arrived, and what it was (for {@link FrameLog}). */
    private record Input(long at, char kind, Consumer<Scene> action) {}

    // ---- Host ----

    @Override
    public int width() {
        return canvas.width();
    }

    @Override
    public int height() {
        return canvas.height();
    }

    @Override
    public List<Seat> seats() {
        return List.copyOf(seats);
    }

    @Override
    public void sound(String sound, float volume, float pitch) {
        // A scene that plays a sound on every frame for every particle would flood the players.
        if (++sounds > 6) return;
        room.sound(this, sound, volume, pitch);
    }

    @Override
    public int score(Seat seat, int score) {
        return room.score(this, seat, score);
    }

    @Override
    public List<Score> scores() {
        return room.scores(this);
    }

    // ---- A seated player's view ----

    /** What a seated player drives: everything they do goes to the scene, on its own thread. */
    final class SceneView implements ViewContent {
        final Seat seat;

        SceneView(Seat seat) {
            this.seat = seat;
        }

        SceneRunner runner() {
            return SceneRunner.this;
        }

        @Override
        public Canvas canvas() {
            return canvas;
        }

        @Override
        public void pointer(View view, int x, int y) {
            input.add(new Input(System.nanoTime(), 'p', s -> s.pointer(seat, x, y)));
        }

        @Override
        public void click(View view, int x, int y, Click click) {
            input.add(new Input(System.nanoTime(), 'c', s -> s.click(seat, x, y, click == Click.RIGHT)));
        }

        @Override
        public void scroll(View view, int x, int y, int notches) {
            input.add(new Input(System.nanoTime(), 's', s -> s.scroll(seat, notches)));
        }

        @Override
        public void key(View view, ai.resourcepack.ui.framework.api.Key key, boolean down) {
            Key k = Key.valueOf(key.name());
            input.add(new Input(System.nanoTime(), down ? 'k' : 'u', s -> s.key(seat, k, down)));
        }

        @Override
        public void closed(View view, CloseReason reason) {
            leave(seat);
        }
    }
}
