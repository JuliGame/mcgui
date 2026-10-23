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
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Runs one {@link Scene} on the arcade's loop: hands it its players' input, ticks it at its own frame rate,
 * and puts what it draws on its canvas. The canvas sends only what changed, so a scene can simply draw
 * the whole frame every time.
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
    private final BufferedImage frame;
    private final ConcurrentLinkedQueue<Consumer<Scene>> input = new ConcurrentLinkedQueue<>();
    private final List<Seat> seats = new CopyOnWriteArrayList<>();
    private double carry;
    private int sounds;

    SceneRunner(String id, Scene scene, Canvas canvas, boolean cabinet, Room room) {
        this.id = id;
        this.scene = scene;
        this.canvas = canvas;
        this.cabinet = cabinet;
        this.room = room;
        frame = new BufferedImage(canvas.width(), canvas.height(), BufferedImage.TYPE_INT_RGB);
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
        input.add(s -> s.joined(seat));
        return seat;
    }

    private boolean taken(int index) {
        for (Seat s : seats) if (s.index() == index) return true;
        return false;
    }

    synchronized void leave(Seat seat) {
        seats.remove(seat);
        input.add(s -> s.left(seat));
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
        carry += dt;
        double step = 1.0 / Math.max(1, Math.min(30, scene.fps()));
        if (carry + 1e-6 < step) return;
        double elapsed = Math.min(carry, 0.1);
        carry = 0;
        sounds = 0;
        for (Consumer<Scene> e; (e = input.poll()) != null; ) e.accept(scene);
        scene.tick(elapsed);
        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        try {
            scene.draw(g);
        } finally {
            g.dispose();
        }
        canvas.draw(frame, 0, 0);
    }

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
            input.add(s -> s.pointer(seat, x, y));
        }

        @Override
        public void click(View view, int x, int y, Click click) {
            input.add(s -> s.click(seat, x, y, click == Click.RIGHT));
        }

        @Override
        public void scroll(View view, int x, int y, int notches) {
            input.add(s -> s.scroll(seat, notches));
        }

        @Override
        public void key(View view, ai.resourcepack.ui.framework.api.Key key, boolean down) {
            Key k = Key.valueOf(key.name());
            input.add(s -> s.key(seat, k, down));
        }

        @Override
        public void closed(View view, CloseReason reason) {
            leave(seat);
        }
    }
}
