package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.style.Motion;
import org.junit.jupiter.api.Test;

import java.awt.image.DataBufferInt;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How many bands a picture is painted in changes how long it takes, never what it looks like (but for rounding). */
class BandsTest {
    private static int[] pixels(Page page) {
        HtmlRenderer.Frame f = HtmlRenderer.render(page, page.document.clone(), 2048, 1152, 2, 0, Selector.State.NONE);
        return ((DataBufferInt) f.image().getRaster().getDataBuffer()).getData();
    }

    static void assertBandsAgree(String html) {
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

    /** Hard-edged repeating stripes and tiles: a band's own origin must not move a stripe's edge by a row. */
    @Test
    void hardEdgedStripesTheSameInEveryBand() {
        assertBandsAgree("<html><head><style>" + ScrollCacheTest.STRIPES_CSS + "</style></head><body>" + ScrollCacheTest.STRIPES_BODY + "</body></html>");
    }

    /** The page drawn at each of [moments] (seconds) in [bands] bands, hovering its first card from the first moment on. */
    private static int[][] moving(Page page, int bands, double[] moments) {
        HtmlRenderer.maxBands = bands;
        org.jsoup.nodes.Document d = page.document.clone();
        Selector.State hovered = HtmlRenderer.state(d.selectFirst(".card"), null);
        Motion m = Motion.at(0);
        Motion.set(m);
        try {
            HtmlRenderer.render(page, d, 2048, 1152, 2, 0, Selector.State.NONE);
            int[][] out = new int[moments.length][];
            for (int i = 0; i < moments.length; i++) {
                m.stopAt(moments[i]);
                HtmlRenderer.Frame f = HtmlRenderer.render(page, d, 2048, 1152, 2, 0, hovered);
                out[i] = ((DataBufferInt) f.image().getRaster().getDataBuffer()).getData().clone();
            }
            return out;
        } finally {
            Motion.clear();
        }
    }

    /**
     * A page that moves is drawn whole at every moment, in bands: its blurred picture (a grid blur, see Painter), itself
     * drifting by fractions of a pixel, and a card whose hover is a transition over it must come out the same in every band.
     */
    @Test
    void aMovingGridBlurTheSameInEveryBandAtEveryMoment() {
        Page page = Page.of("<html><head><style>@keyframes drift{from{transform:translate(0,0)}to{transform:translate(37px,11px)}}"
                + "html,body{margin:0;height:100%;overflow:hidden;background:#000}"
                + ".pano{position:absolute;left:-60px;top:-60px;right:-60px;bottom:-60px;filter:blur(10px);animation:drift 3s linear infinite;"
                + "background:linear-gradient(90deg,#000 0 30%,#fff 30% 60%,#f80 60%),linear-gradient(#08f,#0f8)}"
                + ".card{position:absolute;top:200px;width:200px;height:240px;border-radius:12px;background:#2a4a6a;transform:translateY(0);"
                + "transition:background-color 1s linear,transform 1s linear}"
                + ".card:hover{background:#fff;transform:translateY(-12px)}"
                + "</style></head><body><div class=pano></div><div class=card style=\"left:100px\"></div><div class=card style=\"left:420px\"></div></body></html>",
                Path.of("moving.html").toAbsolutePath(), new Page.Loader(false, null));
        double[] moments = {0, 0.37, 0.81, 1.6};
        int before = HtmlRenderer.maxBands;
        try {
            int[][] whole = moving(page, 1, moments);
            assertFalse(java.util.Arrays.equals(whole[1], whole[2]), "nothing moved");
            for (int bands : new int[]{2, 4}) {
                int[][] split = moving(page, bands, moments);
                for (int k = 0; k < moments.length; k++) {
                    int worst = 0;
                    for (int i = 0; i < whole[k].length; i++) {
                        for (int shift = 0; shift < 32; shift += 8) worst = Math.max(worst, Math.abs(((whole[k][i] >> shift) & 255) - ((split[k][i] >> shift) & 255)));
                    }
                    assertTrue(worst <= 1, bands + " bands differ from one painter by " + worst + " at " + moments[k] + " s");
                }
            }
        } finally {
            HtmlRenderer.maxBands = before;
        }
    }
}
