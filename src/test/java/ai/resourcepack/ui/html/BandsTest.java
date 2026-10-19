package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import org.junit.jupiter.api.Test;

import java.awt.image.DataBufferInt;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** How many bands a picture is painted in changes how long it takes, never what it looks like (but for rounding). */
class BandsTest {
    private static int[] pixels(Page page) {
        HtmlRenderer.Frame f = HtmlRenderer.render(page, page.document.clone(), 2048, 1152, 2, 0, Selector.State.NONE);
        return ((DataBufferInt) f.image().getRaster().getDataBuffer()).getData();
    }

    @Test
    void bandCountOnlyMovesRoundingOfABlur() {
        String html = "<html><head><style>body{margin:0;background:linear-gradient(135deg,#f06,#06f 50%,#0c6)}"
                + ".panel{margin:120px 80px;padding:40px;height:760px;background:rgba(255,255,255,.2);backdrop-filter:blur(16px);border-radius:24px}"
                + "h1{font-size:60px;color:#fff;margin:0 0 20px}p{font-size:22px;color:#112}</style></head>"
                + "<body><div class=panel><h1>Bands</h1><p>One paragraph of text, so each band has glyphs to draw or skip.</p></div></body></html>";
        Page page = Page.of(html, Path.of("bands.html").toAbsolutePath(), new Page.Loader(false, null));
        int before = HtmlRenderer.maxBands;
        try {
            HtmlRenderer.maxBands = 1;
            int[] whole = pixels(page);
            for (int bands : new int[]{2, 4}) {
                HtmlRenderer.maxBands = bands;
                int[] split = pixels(page);
                int worst = 0;
                for (int i = 0; i < whole.length; i++) {
                    for (int shift = 0; shift < 32; shift += 8) worst = Math.max(worst, Math.abs(((whole[i] >> shift) & 255) - ((split[i] >> shift) & 255)));
                }
                assertTrue(worst <= 1, bands + " bands differ from one painter by " + worst);
            }
        } finally {
            HtmlRenderer.maxBands = before;
        }
    }
}
