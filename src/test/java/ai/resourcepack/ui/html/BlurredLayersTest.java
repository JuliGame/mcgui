package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.paint.BlurredLayers;
import org.junit.jupiter.api.Test;

import java.awt.image.DataBufferInt;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A blurred background kept for the page ({@link BlurredLayers}) draws the very pixels painting and blurring it again would. */
class BlurredLayersTest {
    private static final Page.Loader OFFLINE = new Page.Loader(false, null);

    /** A blurred backdrop past the edges of an overflow: hidden page, as the vanilla pages' panorama is, and cards over it. */
    private static final String PAGE = "<html><head><style>html,body{margin:0;height:100%;overflow:hidden}"
            + ".bg{position:absolute;left:-24px;top:-24px;right:-24px;bottom:-24px;"
            + "background:repeating-linear-gradient(90deg,rgba(255,255,255,.6) 0 6px,transparent 6px 18px),linear-gradient(135deg,#f06,#0c6 40%,#06f);"
            + "filter:blur(10px)}"
            + ".card{position:absolute;top:120px;width:200px;height:160px;background:rgba(0,0,0,.4);color:#fff;font-size:20px}"
            + ".card:hover{background:rgba(255,255,255,.3)}.a{left:30px}.b{left:400px}.c{left:790px;top:400px}"
            + "</style></head><body><div class=bg>INSIDE</div><div class='card a'>One</div><div class='card b'>Two</div>"
            + "<div class='card c'>Three</div></body></html>";

    private static Page page(String inBackground) {
        return Page.of(PAGE.replace("INSIDE", inBackground), Path.of("blurred.html").toAbsolutePath(), OFFLINE);
    }

    private static int[] pixels(Page page) {
        HtmlRenderer.Frame f = HtmlRenderer.render(page, page.document.clone(), 2048, 1152, 2, 0, Selector.State.NONE);
        return ((DataBufferInt) f.image().getRaster().getDataBuffer()).getData();
    }

    @Test
    void keptLayerDrawsWhatPaintingItAgainWould() {
        // The same page with an empty element in the blurred box: that one is painted and blurred every time.
        Page kept = page(""), painted = page("<div></div>");
        int before = HtmlRenderer.maxBands;
        try {
            for (int bands : new int[]{1, 4}) {
                HtmlRenderer.maxBands = bands;
                BlurredLayers.clear();
                int[] fresh = pixels(painted);
                // Made by the first picture, drawn from by the next.
                for (int round = 0; round < 2; round++) {
                    int[] got = pixels(kept);
                    int worst = 0;
                    for (int i = 0; i < got.length; i++) {
                        for (int shift = 0; shift < 32; shift += 8) worst = Math.max(worst, Math.abs((got[i] >> shift & 255) - (fresh[i] >> shift & 255)));
                    }
                    assertTrue(worst <= 1, bands + " bands, picture " + round + ": the kept layer differs by " + worst);
                }
                assertEquals(1, BlurredLayers.size(kept), "layers kept for the page");
                assertEquals(0, BlurredLayers.size(painted), "a box with something in it is not kept");
            }
        } finally {
            HtmlRenderer.maxBands = before;
        }
    }

    @Test
    void hoversOverAKeptLayerMatchAFreshPicture() {
        RepaintTest.Walk w = RepaintTest.walk(page(""), 2);
        assertTrue(w.worst() <= 1, "kept blurred layer: " + w.where());
        assertEquals(w.steps(), w.partials(), "hovers that were not partial");
    }
}
