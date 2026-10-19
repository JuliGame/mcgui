package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.paint.PixelPool;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Reused pixel arrays never show through: not in a picture painted over old ones, and never in a picture still kept. */
class PixelPoolTest {
    private static int[] pixels(BufferedImage img) {
        return Arrays.copyOf(((DataBufferInt) img.getRaster().getDataBuffer()).getData(), img.getWidth() * img.getHeight());
    }

    /** Arrays this thread keeps, of every size these pages need, filled with an opaque colour no page here paints. */
    private static void dirty() {
        List<int[]> taken = new ArrayList<>();
        for (int n = 20_000; n < 1_400_000; n = n * 3 / 2) taken.add(PixelPool.ints(n));
        for (int[] a : taken) {
            Arrays.fill(a, 0xFF13F7A1);
            PixelPool.give(a);
        }
    }

    /**
     * Layers (opacity and filter), their part copies, a backdrop's copy and its small blurred picture, a blurred shadow, a
     * blur's work array and gradient text are all painted on reused arrays; a picture over arrays full of an old picture
     * must be the picture over new ones. Whole pictures painted straight into a reused picture (one band, no blur) too.
     */
    @Test
    void aPictureOverReusedArraysIsThePictureOverNewOnes() {
        Page page = Page.of("<html><head><style>body{margin:0;background:linear-gradient(135deg,#f06,#06f 50%,#0c6)}"
                + ".fade{position:absolute;left:40px;top:30px;width:420px;height:260px;opacity:.5;border-radius:30px;background:#fff}"
                + ".soft{position:absolute;left:520px;top:40px;width:300px;height:200px;filter:blur(6px) saturate(1.5);background:#ff0}"
                + ".glass{position:absolute;left:100px;top:320px;width:700px;height:200px;backdrop-filter:blur(14px) brightness(1.1);"
                + "background:rgba(255,255,255,.15);box-shadow:0 10px 40px rgba(0,0,0,.6)}"
                + "h1{position:absolute;left:560px;top:280px;margin:0;font-size:60px;background:linear-gradient(90deg,#fff,#000);"
                + "-webkit-background-clip:text;background-clip:text;color:transparent}"
                + "</style></head><body><div class=fade>Half</div><div class=soft></div><div class=glass></div><h1>Text</h1></body></html>",
                Path.of("pool.html").toAbsolutePath(), new Page.Loader(false, null));
        Page plain = Page.of("<html><body style='margin:0'><p style='font-size:40px'>Nothing to blur</p></body></html>",
                Path.of("plain.html").toAbsolutePath(), new Page.Loader(false, null));
        int before = HtmlRenderer.maxBands;
        try {
            for (int bands : new int[]{1, 4}) {
                HtmlRenderer.maxBands = bands;
                for (Page p : List.of(page, plain)) {
                    PixelPool.clear();
                    int[] fresh = pixels(HtmlRenderer.render(p, p.document.clone(), 1024, 576, 1, 0, Selector.State.NONE).image());
                    // The painter threads' arrays hold the other page, this thread's a colour neither paints.
                    Page other = p == page ? plain : page;
                    HtmlRenderer.render(other, other.document.clone(), 1024, 576, 1, 0, Selector.State.NONE);
                    dirty();
                    BufferedImage into = PixelPool.picture(1024, 576);
                    Arrays.fill(((DataBufferInt) into.getRaster().getDataBuffer()).getData(), 0xFF13F7A1);
                    int[] reused = pixels(HtmlRenderer.render(p, p.document.clone(), into, 1, 0, Selector.State.NONE, 0).image());
                    assertArrayEquals(fresh, reused, "painted over reused arrays (" + bands + " bands) differs from over new ones");
                }
            }
        } finally {
            HtmlRenderer.maxBands = before;
            PixelPool.clear();
        }
    }

    /** The pixels behind [img]: a pooled image is a new wrapper round an array used before. */
    private static int[] data(BufferedImage img) {
        return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }

    private static HtmlRenderer.Frame frame(BufferedImage img) {
        return new HtmlRenderer.Frame(img, 1, img.getWidth(), img.getHeight(), img.getHeight(), 0, List.of(), java.util.Map.of(), null, null);
    }

    /** A render that paints a whole picture, as Session does, filling it with [colour] to stand for what it paints. */
    private static BufferedImage paint(PooledPicture pooled, HtmlRenderer.Frame shown, int colour) {
        pooled.start(shown);
        BufferedImage img = pooled.take(200, 100);
        Arrays.fill(((DataBufferInt) img.getRaster().getDataBuffer()).getData(), colour);
        return img;
    }

    private static void assertStill(BufferedImage img, int colour, String what) {
        int[] px = pixels(img);
        for (int p : px) assertEquals(colour, p, what + " changed under a later picture");
    }

    /**
     * Session's sequence: whole pictures, a repaint into the one shown, one kept for other sessions (shared), a view
     * from elsewhere (a scroll cache's), a failed render. The picture shown, and one shared, never change under later
     * pictures; one no longer shown is painted over again (so the pool is used at all).
     */
    @Test
    void aPictureShownOrSharedIsNeverPaintedOver() {
        PixelPool.clear();
        PooledPicture pooled = new PooledPicture();
        try {
            BufferedImage a = paint(pooled, null, 1);
            pooled.shown(frame(a));
            BufferedImage b = paint(pooled, frame(a), 2);
            assertNotSame(data(a), data(b), "the picture shown was painted over");
            assertStill(a, 1, "the picture shown");
            pooled.shown(frame(b));

            // A repaint into the picture shown keeps it shown: the next whole picture goes elsewhere.
            pooled.start(frame(b));
            pooled.shown(frame(b));
            BufferedImage c = paint(pooled, frame(b), 3);
            assertNotSame(data(b), data(c));
            assertSame(data(a), data(c), "a picture no longer shown is painted over again");
            assertStill(b, 2, "the picture shown");

            // Kept for other sessions: shared for good.
            pooled.share(c);
            pooled.shown(frame(c));
            BufferedImage d = paint(pooled, frame(c), 4);
            pooled.shown(frame(d));
            BufferedImage e = paint(pooled, frame(d), 5);
            pooled.shown(frame(e));
            BufferedImage f = paint(pooled, frame(e), 6);
            pooled.shown(frame(f));
            for (BufferedImage x : List.of(d, e, f)) assertNotSame(data(c), data(x), "a shared picture was painted over");
            assertStill(c, 3, "a shared picture");

            // A view from elsewhere replaces it; a render that fails leaves the view shown.
            BufferedImage view = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB_PRE);
            pooled.start(frame(f));
            pooled.shown(frame(view));
            BufferedImage g = paint(pooled, frame(view), 7);
            pooled.failed(frame(view));
            BufferedImage h = paint(pooled, frame(view), 8);
            assertSame(data(g), data(h), "a picture taken by a failed render is used again");
            pooled.shown(frame(h));
            assertStill(c, 3, "a shared picture");
        } finally {
            PixelPool.clear();
        }
    }
}
