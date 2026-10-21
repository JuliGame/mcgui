package ai.resourcepack.arcade;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs every game 30 times a second on a thread of its own. Not on the server's tick: a canvas may be drawn
 * from any one thread, and UI Framework sends a drawing the moment it is published, so a game can run
 * faster than the server ticks and never costs the server thread a frame.
 */
final class Loop {
    private static final long FRAME_MICROS = 1_000_000 / 30;

    private final List<Game> games = new CopyOnWriteArrayList<>();
    private final Logger log;
    private ScheduledExecutorService thread;
    private long last;

    Loop(Logger log) {
        this.log = log;
    }

    void start() {
        thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Arcade-loop");
            t.setDaemon(true);
            return t;
        });
        last = System.nanoTime();
        thread.scheduleAtFixedRate(this::frame, 0, FRAME_MICROS, TimeUnit.MICROSECONDS);
    }

    void stop() {
        if (thread != null) thread.shutdownNow();
        games.clear();
    }

    void add(Game game) {
        games.add(game);
    }

    void remove(Game game) {
        games.remove(game);
    }

    private void frame() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - last) / 1e9);
        last = now;
        for (Game g : games) {
            try {
                g.tick(dt);
            } catch (Throwable t) {
                // One broken game must not stop the others: a scheduled task that throws is never run again.
                log.log(Level.WARNING, "A game failed and was stopped", t);
                games.remove(g);
            }
        }
    }
}
