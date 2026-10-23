package ai.resourcepack.arcade;

import ai.resourcepack.arcade.scene.Host;
import ai.resourcepack.arcade.scene.Key;
import ai.resourcepack.arcade.scene.Scene;
import ai.resourcepack.arcade.scene.Seat;
import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.render.Palette;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneRunnerTest {
    private static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));

    private static final SceneRunner.Room ROOM = new SceneRunner.Room() {
        @Override public void sound(SceneRunner runner, String sound, float volume, float pitch) {}
        @Override public int score(SceneRunner runner, Seat seat, int score) { return 0; }
        @Override public List<Host.Score> scores(SceneRunner runner) { return List.of(); }
        @Override public void empty(SceneRunner runner) {}
    };

    /** A scene that only says how fast it runs. */
    private static Scene still(int fps) {
        return new Scene() {
            @Override public int cols() { return 1; }
            @Override public int rows() { return 1; }
            @Override public int fps() { return fps; }
            @Override public void start(Host host) {}
            @Override public void tick(double dt) {}
            @Override public void draw(Graphics2D g) {
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, 128, 128);
            }
        };
    }

    /** Frames made by [fps] over [runs] loop runs, each up to [jitterMs] off its time. */
    private static int frames(int fps, int runs, double jitterMs) {
        SceneRunner r = new SceneRunner("t", still(fps), new Canvas(PALETTE, 1, 1), true, ROOM);
        Random random = new Random(1);
        long start = 1_000_000_000_000L;
        int made = 0;
        for (int k = 0; k < runs; k++) {
            long at = start + k * Loop.PERIOD_NANOS + (long) ((random.nextDouble() * 2 - 1) * jitterMs * 1e6);
            if (r.due(at) >= 0) made++;
        }
        return made;
    }

    @Test
    void aThirtyFrameGameMakesAFrameEveryRunHoweverTheRunsJitter() {
        // Before: a run a microsecond early skipped its frame, about half of them.
        assertEquals(300, frames(30, 300, 3));
        assertEquals(300, frames(30, 300, 10));
    }

    @Test
    void slowerGamesSkipRunsEvenly() {
        assertEquals(150, frames(15, 300, 3), 1);
        assertEquals(100, frames(10, 300, 3), 1);
    }

    @Test
    void aLateRunIsNotFollowedByACatchUpFrame() {
        SceneRunner r = new SceneRunner("t", still(30), new Canvas(PALETTE, 1, 1), true, ROOM);
        long p = Loop.PERIOD_NANOS, t = 1_000_000_000_000L;
        assertTrue(r.due(t) >= 0);
        assertTrue(r.due(t + p) >= 0);
        // A frame that took two and a half runs: the loop then runs the missed ones at once.
        assertTrue(r.due(t + 2 * p + p / 2) >= 0);
        assertEquals(-1, r.due(t + 2 * p + p / 2 + 1_000_000));
        assertTrue(r.due(t + 3 * p) >= 0);
    }

    /**
     * Every game, played for a while: the canvas, handed only the tiles that changed each frame, holds exactly what
     * a canvas handed the last frame whole does. And every frame paints every pixel (a frame drawn twice over two
     * different backgrounds comes out the same), which handing over only what changed relies on.
     */
    @Test
    void tilesHandedOverEqualTheWholeFrameForEveryGame() {
        for (String name : Games.ALL.keySet()) {
            Scene scene = Games.ALL.get(name).get();
            Canvas canvas = new Canvas(PALETTE, scene.cols(), scene.rows());
            SceneRunner runner = new SceneRunner(name, scene, canvas, true, ROOM);
            Seat seat = runner.sit(UUID.randomUUID(), "Test");
            Random random = new Random(3);
            for (int f = 0; f < 90; f++) {
                if (f % 10 == 5) scene.key(seat, Key.values()[random.nextInt(5)], true);
                if (f % 10 == 8) for (Key k : Key.values()) scene.key(seat, k, false);
                if (f % 15 == 0) scene.click(seat, random.nextInt(canvas.width()), random.nextInt(canvas.height()), false);
                scene.pointer(seat, random.nextInt(canvas.width()), random.nextInt(canvas.height()));
                runner.frame(1 / 30.0);
            }
            Canvas whole = new Canvas(PALETTE, scene.cols(), scene.rows());
            whole.draw(runner.shown(), 0, 0);
            for (int t = 0; t < canvas.tiles(); t++) {
                assertArrayEquals(whole.tile(t, Canvas.Encoding.HI), canvas.tile(t, Canvas.Encoding.HI), name + " tile " + t);
            }
            assertEquals(0, unpainted(scene, canvas.width(), canvas.height()), name + ": pixels its frame leaves as they were");
        }
    }

    /** Pixels of a frame of [scene] that depend on what was in the image before it drew. */
    private static int unpainted(Scene scene, int w, int h) {
        int[][] got = new int[2][];
        int[] under = {0xFF00FF, 0x00FF01};
        for (int i = 0; i < 2; i++) {
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(new Color(under[i]));
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            scene.draw(g);
            g.dispose();
            got[i] = image.getRGB(0, 0, w, h, null, 0, w);
        }
        int n = 0;
        for (int i = 0; i < got[0].length; i++) if (got[0][i] != got[1][i]) n++;
        return n;
    }
}
