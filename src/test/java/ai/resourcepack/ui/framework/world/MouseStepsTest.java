package ai.resourcepack.ui.framework.world;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading a player's sensitivity off their pitch, turned as a vanilla client turns it. */
class MouseStepsTest {
    /** A client looking around: its pitch, turned frame by frame as {@code MouseHandler.turnPlayer} and {@code Entity.turn} do. */
    private static final class Client {
        final double sensitivity;
        final Random random;
        float pitch;

        Client(double sensitivity, long seed) {
            this.sensitivity = sensitivity;
            this.random = new Random(seed);
        }

        /** One frame's turn by [counts] mouse counts, added to the float pitch as the client adds it. */
        void frame(double counts) {
            double ss = sensitivity * 0.6F + 0.2F;
            float turn = (float) (counts * (ss * ss * ss * 8.0)) * 0.15F;
            pitch = Math.max(-90f, Math.min(90f, pitch + turn));
        }

        /** One tick of looking around: a few frames of a few counts each, kept within ±60°. */
        void tick() {
            for (int f = 1 + random.nextInt(6); f > 0; f--) {
                long counts = Math.round(random.nextGaussian() * (1 + random.nextInt(26)));
                if (Math.abs(pitch + counts * MouseSteps.step(sensitivity)) > 60) counts = -counts;
                frame(counts);
            }
        }
    }

    /** [ticks] of [client] looking around, sampled once a tick; the step afterwards. */
    private static double learn(MouseSteps m, Client client, int ticks) {
        for (int t = 0; t < ticks; t++) {
            client.tick();
            m.sample(client.pitch);
        }
        return m.step();
    }

    @Test
    void readsTheSensitivityExactly() {
        for (double s : new double[]{0.0, 0.116, 0.5, 0.75, 1.0}) {
            for (long seed = 1; seed <= 5; seed++) {
                MouseSteps m = new MouseSteps(Double.NaN);
                double step = learn(m, new Client(s, seed), 110);
                assertEquals(MouseSteps.step(s), step, 1e-6, "sensitivity " + s + ", seed " + seed);
                assertEquals(s, MouseSteps.sensitivity(step), 5e-5, "sensitivity " + s + ", seed " + seed);
            }
        }
    }

    @Test
    void leavesOutTurnsTheServerMade() {
        // A teleport or a turn back from a view's edge every ten ticks: a jump of no whole number of counts,
        // which the server says it made (hold) before the next sample.
        for (double s : new double[]{0.116, 0.5}) {
            Client client = new Client(s, 7);
            Random server = new Random(11);
            MouseSteps m = new MouseSteps(Double.NaN);
            for (int t = 0; t < 400; t++) {
                client.tick();
                if (t % 10 == 0) {
                    client.pitch = Math.max(-60f, Math.min(60f, client.pitch + (float) (server.nextDouble() * 10 - 5)));
                    m.hold();
                }
                m.sample(client.pitch);
            }
            assertEquals(MouseSteps.step(s), m.step(), 1e-6, "sensitivity " + s);
        }
    }

    @Test
    void aPanTheServerMakesIsNotTakenForAStep() {
        // A plugin panning a still player's camera 0.25° a tick by teleports: every change is alike, so without
        // hold it reads as a step of 0.25° (131%); held, what was known stands.
        for (boolean held : new boolean[]{false, true}) {
            MouseSteps m = new MouseSteps(MouseSteps.step(0.5));
            float pitch = 0;
            int direction = 1;
            for (int t = 0; t < 600; t++) {
                if (Math.abs(pitch + direction * 0.25f) > 40) direction = -direction;
                pitch += direction * 0.25f;
                if (held) m.hold();
                m.sample(pitch);
            }
            if (held) assertEquals(MouseSteps.step(0.5), m.step(), 0);
            else assertEquals(0.25, m.step(), 1e-6);
        }
    }

    @Test
    void keepsWhatItKnowsWhileTurnsAreNotWholeCounts() {
        MouseSteps m = new MouseSteps(MouseSteps.step(0.116));
        Random r = new Random(3);
        float pitch = 0;
        for (int t = 0; t < 400; t++) {
            // Smooth camera, a trackpad: fractions of a count.
            pitch = Math.max(-60f, Math.min(60f, pitch + (float) (r.nextGaussian() * 3)));
            m.sample(pitch);
        }
        assertEquals(MouseSteps.step(0.116), m.step(), 0);

        MouseSteps fresh = new MouseSteps(Double.NaN);
        for (int t = 0; t < 400; t++) {
            pitch = Math.max(-60f, Math.min(60f, pitch + (float) (r.nextGaussian() * 3)));
            fresh.sample(pitch);
        }
        assertTrue(Double.isNaN(fresh.step()), "found " + fresh.step() + " in turns of no whole counts");
    }

    @Test
    void holdLeavesOutTheChangesAcrossIt() {
        // Twenty ticks of nothing but server turns, each held: nothing is learned from them.
        MouseSteps m = new MouseSteps(Double.NaN);
        Client client = new Client(0.5, 5);
        for (int t = 0; t < 200; t++) {
            client.pitch = (float) (Math.sin(t) * 40);
            m.hold();
            m.sample(client.pitch);
        }
        assertTrue(Double.isNaN(m.step()));
        // And learning carries on once the server stops turning them.
        assertEquals(MouseSteps.step(0.5), learn(m, client, 110), 1e-6);
    }
}
