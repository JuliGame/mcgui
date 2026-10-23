package ai.resourcepack.ui.html;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * render-timeout counts the drawing thread's CPU, not time on the clock: a render slowed only by a busy machine
 * is not given up, one that really takes too long is, and either way a render cannot outlast a few times its limit.
 */
class RenderBudgetTest {
    /** A thread that got no CPU (a busy machine) is past its limit on the clock and not given up, until the clock cap. */
    @Test
    void timeWithoutCpuIsNotCounted() throws InterruptedException {
        RenderBudget b = RenderBudget.of(100);
        long t0 = System.nanoTime();
        Thread.sleep(250);
        b.check();
        long before = RenderBudget.timedOut();
        RenderAbandoned e = assertThrows(RenderAbandoned.class, () -> {
            while (true) {
                Thread.sleep(20);
                b.check();
            }
        });
        double ms = (System.nanoTime() - t0) / 1e6;
        assertTrue(ms >= RenderBudget.WALL_TIMES * 100 - 1 && ms < RenderBudget.WALL_TIMES * 100 + 400, "given up at the clock cap, not before: " + ms);
        assertTrue(e.getMessage().contains("render-timeout"), e.getMessage());
        assertEquals(before + 1, RenderBudget.timedOut());
    }

    /** A render that keeps its thread busy is given up once it has used its limit. */
    @Test
    void cpuUsedIsCounted() {
        RenderBudget b = RenderBudget.of(100);
        long t0 = System.nanoTime();
        assertThrows(RenderAbandoned.class, () -> {
            long x = 0;
            while (true) {
                for (int i = 0; i < 10_000; i++) x += i * 31L ^ x;
                b.check();
                if (x == 42) System.out.print("");
            }
        });
        double ms = (System.nanoTime() - t0) / 1e6;
        // Not before its limit; how long after depends on how much CPU the machine gave it.
        assertTrue(ms >= 99, "given up after its limit, not before: " + ms);
    }

    /** Several bands finding out: counted once, and every later check of any band throws. */
    @Test
    void countedOnce() throws InterruptedException {
        RenderBudget b = RenderBudget.of(50);
        long before = RenderBudget.timedOut();
        Thread.sleep(RenderBudget.WALL_TIMES * 50 + 20);
        for (int i = 0; i < 3; i++) assertThrows(RenderAbandoned.class, b::check);
        Thread other = new Thread(() -> assertThrows(RenderAbandoned.class, b::check));
        other.start();
        other.join();
        assertEquals(before + 1, RenderBudget.timedOut());
    }

    /** A cancelled render says so, and is not counted as too slow. */
    @Test
    void cancelledIsNotTimedOut() {
        RenderBudget b = RenderBudget.of(1000);
        long before = RenderBudget.timedOut();
        b.cancel();
        RenderAbandoned e = assertThrows(RenderAbandoned.class, b::check);
        assertEquals("the render was given up", e.getMessage());
        assertEquals(before, RenderBudget.timedOut());
    }
}
