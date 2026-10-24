package ai.resourcepack.arcade;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs every game 30 times a second on a thread of its own. Not on the server's tick: a canvas may be drawn
 * from any one thread, and MC GUI sends a drawing the moment it is published, so a game can run
 * faster than the server ticks and never costs the server thread a frame. With no games it does not run at all.
 */
final class Loop {
    /** Anything the loop runs: a game, a scene. */
    interface Ticker {
        void tick(double dt);
    }

    private static final long FRAME_MICROS = 1_000_000 / 30;
    /** How often the loop runs; a game at a lower frame rate skips runs. */
    static final long PERIOD_NANOS = FRAME_MICROS * 1000;

    private final List<Ticker> games = new CopyOnWriteArrayList<>();
    private final Logger log;
    private ScheduledExecutorService thread;
    /** The 30-a-second run, while there are games; null while there are none. Under this object's lock. */
    private ScheduledFuture<?> running;
    private long last;

    Loop(Logger log) {
        this.log = log;
    }

    synchronized void start() {
        thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Arcade-loop");
            t.setDaemon(true);
            return t;
        });
        if (!games.isEmpty()) run();
    }

    synchronized void stop() {
        if (thread != null) thread.shutdownNow();
        thread = null;
        running = null;
        games.clear();
    }

    synchronized void add(Ticker game) {
        games.add(game);
        if (running == null && thread != null) run();
    }

    synchronized void remove(Ticker game) {
        games.remove(game);
        if (games.isEmpty() && running != null) {
            running.cancel(false);
            running = null;
        }
    }

    private void run() {
        // Read by the loop's thread after this: the executor hands it over.
        last = System.nanoTime();
        running = thread.scheduleAtFixedRate(this::frame, 0, FRAME_MICROS, TimeUnit.MICROSECONDS);
    }

    private void frame() {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - last) / 1e9);
        last = now;
        for (Ticker g : games) {
            try {
                g.tick(dt);
            } catch (Throwable t) {
                // One broken game must not stop the others: a scheduled task that throws is never run again.
                log.log(Level.WARNING, "A game failed and was stopped", t);
                remove(g);
            }
        }
    }
}
