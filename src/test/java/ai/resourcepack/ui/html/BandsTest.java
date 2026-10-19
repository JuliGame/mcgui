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

    private static void assertBandsAgree(String html) {
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

    @Test
    void bandCountOnlyMovesRoundingOfABlur() {
        assertBandsAgree("<html><head><style>body{margin:0;background:linear-gradient(135deg,#f06,#06f 50%,#0c6)}"
                + ".panel{margin:120px 80px;padding:40px;height:760px;background:rgba(255,255,255,.2);backdrop-filter:blur(16px);border-radius:24px}"
                + "h1{font-size:60px;color:#fff;margin:0 0 20px}p{font-size:22px;color:#112}</style></head>"
                + "<body><div class=panel><h1>Bands</h1><p>One paragraph of text, so each band has glyphs to draw or skip.</p></div></body></html>");
    }

    /** A blur is worked out on a shrunken picture: where that picture's grid starts must not depend on which band it is. */
    @Test
    void hardEdgesUnderABackdropBlurTheSameInEveryBand() {
        assertBandsAgree("<html><head><style>body{margin:0;background:linear-gradient(90deg,#000 0 50%,#fff 50%)}"
                + "div{position:absolute;box-sizing:border-box}"
                + ".bar{left:0;top:301px;width:1024px;height:123px;background:#f80}"
                + ".a{left:200px;top:90px;width:620px;height:400px;background:rgba(255,255,255,.15);backdrop-filter:blur(16px)}"
                + ".b{left:330px;top:150px;width:300px;height:280px;background:rgba(0,0,0,.2);backdrop-filter:blur(7px)}"
                + "</style></head><body><div class=bar></div><div class=a></div><div class=b></div></body></html>");
    }
}
