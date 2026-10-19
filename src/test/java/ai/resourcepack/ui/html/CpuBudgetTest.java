package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.api.Cpu;
import ai.resourcepack.uiframework.render.Palette;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render jobs wait for their bands and the canvas waits for its encoders, all on one small pool: with a budget of one
 * thread the job holding it must run its own bands, and with several, jobs must not hold every thread while waiting
 * for work that has no thread left. Many sessions, each frame after another, at 1, 2 and 4 threads: if any wait
 * could starve, this does not finish.
 */
class CpuBudgetTest {
    private static final int SESSIONS = 12, FRAMES = 4;
    private static final String HTML = "<html><head><style>body{margin:0;background:linear-gradient(135deg,#f06,#06f 50%,#0c6)}"
            + ".panel{margin:60px 40px;padding:30px;height:420px;background:rgba(255,255,255,.2);backdrop-filter:blur(12px);border-radius:20px}"
            + "h1{font-size:44px;color:#fff;margin:0 0 12px}p{font-size:18px;color:#112}</style></head>"
            + "<body><div class=panel><h1>Budget</h1><p>Words for the painters to draw, in a few bands.</p></div></body></html>";

    @AfterEach
    void reset() {
        HtmlRenderer.adaptiveBands = true;
        HtmlRenderer.maxBands = 4;
        Cpu.configure(0);
    }

    @Test
    void sessionsOfFullFramesFinishAtAnyBudget() throws Exception {
        Page page = Page.of(HTML, Path.of("budget.html").toAbsolutePath(), new Page.Loader(false, null));
        Palette palette = new Palette(RenderBench::mapColour);
        // Every picture in four bands, however many are painting: the most waiting on the pool there can be.
        HtmlRenderer.adaptiveBands = false;
        HtmlRenderer.maxBands = 4;
        for (int budget : new int[]{1, 2, 4}) {
            Cpu.configure(budget);
            long start = System.nanoTime();
            AtomicInteger running = new AtomicInteger(), peak = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(SESSIONS);
            List<Canvas> canvases = new ArrayList<>();
            for (int s = 0; s < SESSIONS; s++) {
                Canvas canvas = new Canvas(palette, 8, 5, () -> {});
                canvases.add(canvas);
                frame(page, canvas, 0, running, peak, failure, done);
            }
            Thread watch = new Thread(() -> {
                while (done.getCount() > 0) {
                    peak.accumulateAndGet(Cpu.pool().getRunningThreadCount(), Math::max);
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            watch.setDaemon(true);
            watch.start();
            // A caller that is not a pool thread, mid-way through, joins the same queue without taking a thread of its own.
            HtmlRenderer.render(page, page.document.clone(), 1024, 640, 1, 0, Selector.State.NONE, null, null, 0);
            assertTrue(done.await(120, TimeUnit.SECONDS), "frames did not finish with a budget of " + budget + ": " + done.getCount() + " sessions still waiting");
            System.out.printf("budget %d: %d sessions x %d frames in %.1f s, at most %d pool threads running%n", budget, SESSIONS, FRAMES, (System.nanoTime() - start) / 1e9, peak.get());
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(FRAMES, running.get() / SESSIONS, "every session drew every frame");
            // Waiting threads hand their place on, so what runs is the budget (the one more is a hand-over in progress).
            assertTrue(peak.get() <= budget + 1, "budget " + budget + " but " + peak.get() + " pool threads ran at once");
        }
    }

    /** Frame [n] of a session: render it as a pool job, draw it, then ask for the next. */
    private static void frame(Page page, Canvas canvas, int n, AtomicInteger frames, AtomicInteger peak, AtomicReference<Throwable> failure, CountDownLatch done) {
        Cpu.pool().execute(() -> {
            try {
                var doc = page.document.clone();
                doc.body().attr("style", "position:relative;top:" + (n * 3) + "px");
                // No render-timeout: this is about waiting forever, not about how long a frame takes on a busy machine.
                HtmlRenderer.Frame f = HtmlRenderer.render(page, doc, 1024, 640, 1, 0, Selector.State.NONE, null, null, 0);
                canvas.draw(f.image(), 0, 0);
                frames.incrementAndGet();
                if (n + 1 < FRAMES) frame(page, canvas, n + 1, frames, peak, failure, done);
                else done.countDown();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
                while (done.getCount() > 0) done.countDown();
            }
        });
    }
}
